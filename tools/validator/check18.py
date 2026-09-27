"""Проверка выхода по техническому приложению и разъяснениям от 18.09.2026.

Запуск из корня: uv run --project tools python tools/validator/check18.py <вход.geojson> <выход.geojson> [--no-shape]
Печатает нарушения по категориям (A — состав и ссылки, B — геометрия и отступы, C — расходы и ДУ, D — камеры,
E — стоимость и сводка), строки «i» — справочные. В конце CHECK18 OK и код 0, если нарушений нет. B16–B18 и B21 —
форма трассы (п. 5), B20 — поворот круче 90° на пути точки в камере или техническом узле (п. 2.1, разъяснение 5),
--no-shape их отключает вместе с B8 — финальный участок не от ближайшего допустимого входа в своё здание (п. 2.2).
B19 — финальный участок снова заходит в зону отступа своего здания или подходит в ней к другой стене (п. 2.2).
Строка «i» у пар вариантов — доля расхождения трасс (разд. 6): варианты различны от 10 % длины вне полосы 10 м
и при другом устройстве (разбиение точек по узлам врезки и объекты врезки), как у сервиса.
"""
import itertools
import json
import math
import re
import sys
from collections import Counter, defaultdict

import os

import numpy as np
import shapely
import shapely.ops
from pyproj import Transformer
from shapely import STRtree
from shapely.geometry import LineString, Point, shape

TO_UTM = Transformer.from_crs("EPSG:4326", "EPSG:32637", always_xy=True)

RULES = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "rules", "rules.json")))
DN = {d["dn"]: d for d in RULES["diameters"]}
DNS = sorted(DN)
ALLOWED = {"heat_network", "heat_chamber", "technical_node", "variant_summary"}
SUMMARY = ["rank", "construction_cost", "chamber_construction_cost", "existing_chamber_tie_in_count",
           "existing_chamber_tie_in_cost", "unconnected_penalty", "calculated_cost", "new_network_length",
           "score", "unconnected_oks_ids"]
# типы ограничений и их числа из rules.json: запреты с отступом и спецпроходы с коэффициентом и полосой margin
_TYPES = {t: r for t, r in RULES["restrictions"].items() if not t.startswith("_") and t != "oks_existing"}
FORBID = {t: r["clearance_m"] for t, r in _TYPES.items() if r["rule"] == "forbid"}
# тип, которого нет в rules.json, сервис считает запретом с отступом _fallback
FALLBACK = RULES["restrictions"]["_fallback"]["clearance_m"]
K_SPECIAL = {t: r["k_special"] for t, r in _TYPES.items() if r["rule"] == "special"}
MARGIN = {t: r["margin_m"] for t, r in _TYPES.items() if r["rule"] == "special"}
MIN_ANGLE = {t: r["min_angle_deg"] for t, r in _TYPES.items() if r["rule"] == "special" and "min_angle_deg" in r}
NODE_TOL = 0.05
# допуск границ полосы спецпрохода: сервис прижимает границу спецучастка к узлу ближе 0,08 м
ZONE_TOL = 0.1
EPS = 0.01
# форма трассы (п. 5): поворот — вершина с отклонением от 3°, короткое звено между поворотами — до 10 м
TURN_DEG = 3.0
SHORT_LINK_M = 10.0
# запасы замены в B16–B18 как у сервиса, толкование п. 5 (Router: CUT_MARGIN_M, CUT_APART_M, MAX_TURN_DEG,
# CUT_PIECE_M): отступ до зон с запасом сверх нормы, зазор до других участков новой сети, поворот и звено
CUT_MARGIN_M = 0.15
CUT_APART_M = 0.5
MAX_TURN_DEG = 89.9
CUT_PIECE_M = 1.05
# место камеры ветвления в B21 как у сервиса (TreeBuilder.spotAllowed, SpecialObjects.near): не ближе 3 м по участку
# к точке подключения, отступ от объектов специального прохода и сети с запасом 0,1 м
CHAMBER_GAP_M = 3.0
CHAMBER_NEAR_M = 0.1
# B21: камера переносится в узлы сетки ±SHIFT_M с шагом SHIFT_STEP_M и в точки на прямых звеньев у камеры, как у
# сервиса (TreeBuilder.unkinks); точка на прямой звена — ближе LINE_TOL_M
SHIFT_M = 6.0
SHIFT_STEP_M = 0.5
LINE_TOL_M = 1e-6
# вход финального участка (п. 2.2): ближайшая допустимая точка внешнего контура своего полигона, от которой звено до
# прежней вершины перед выходом держит запасы замены. Точки контура и концы участка на луче перебираются через
# ENTRY_STEP_M; вход сервиса дальше такого больше чем на ENTRY_TOL_M — B8. В зоне отступа финальный участок не подходит
# к своему зданию ближе, чем был, больше чем на APPROACH_M (B19, толкование «повторный подход к другой стене»); у
# кандидата во входе предел строже, APPROACH_ENTRY_M, а конец участка лежит за зоной сервиса, она шире нормы на
# ZONE_WIDER_M (ObstacleSet.SIMPLIFY_M): так проверка не требует входа, которого сервис не видит
ENTRY_STEP_M = 0.05
ENTRY_TOL_M = 0.1
APPROACH_M = 0.05
APPROACH_ENTRY_M = 0.03
ZONE_WIDER_M = 0.05
# B20 в камере ветвления — нарушение, если поворот снимает правка у камеры, как у сервиса (VariantEnumerator.turned):
# сдвиг камеры вдоль первого звена участка шагом 0,1 м до 10 м или излом звена у камеры на наименьший угол от 3,5° с
# шагом 0,5°, новая вершина в 1,05·2^k м от камеры; S растёт не больше TURN_TOLERANCE_S
TURN_STEP_M = 0.1
TURN_MAX_M = 10.0
BEND_STEP_DEG = 0.5
TURN_TOLERANCE_S = 0.002
# разд. 6: варианты одинаковы, если у каждого больше 90 % длины меньшего лежит в полосе 10 м от другого или у них одно
# устройство (VariantEnumerator.distinct); доля расхождения — 1 минус меньшая из этих длин, делённая на длину меньшего
SAME_ROUTE_M = 10.0
SAME_ROUTE_SHARE = 0.9
# --no-shape отключает B16–B18, B20 и B21: так старые категории сверяются с прежними прогонами
SHAPE = "--no-shape" not in sys.argv


def to_utm(geom):
    """Геометрия из EPSG:4326 в метры UTM 37N (EPSG:32637), как у сервиса."""
    return shapely.transform(geom, lambda xy: np.column_stack(TO_UTM.transform(xy[:, 0], xy[:, 1])))


def oks_clearance(dn):
    return 5.0 if dn < 500 else 7.0 if dn <= 800 else 9.0


def chamber_cost(dn):
    return next(c["cost"] for c in RULES["chamber_cost"] if c["dn_min"] <= dn <= c["dn_max"])


def dn_for(flow):
    return next((d for d in DNS if DN[d]["capacity_tph"] >= flow - 1e-9), None)


def crossing_near(line, chain, obj, margin):
    """Зона линейного объекта: margin вдоль трассы от точки её пересечения с объектом; участок в зоне, если до точки пересечения не дальше margin."""
    for part in chain:
        hit = part.intersection(obj)
        for pt in getattr(hit, "geoms", [hit]):
            if not pt.is_empty and line.distance(pt) < margin - 0.005:
                return True
    return False


def contour_points(shells, cg, limit):
    """Точки внешних контуров ближе limit к точке: через ENTRY_STEP_M вдоль сторон и ближайшая точка каждой стороны,
    по возрастанию расстояния."""
    found = []
    for sh in shells:
        c = np.asarray(sh.coords)
        for a, b in zip(c, c[1:]):
            side = LineString([a, b])
            if side.distance(cg) >= limit:
                continue
            n = max(1, math.ceil(side.length / ENTRY_STEP_M))
            found += [Point(a + (b - a) * k / n) for k in range(n + 1)]
            found.append(shapely.ops.nearest_points(side, cg)[0])
    found = [q for q in found if q.distance(cg) < limit]
    return sorted(found, key=lambda q: q.distance(cg))


def entry_window(cg, q, own, zone, dn, trees):
    """Где на луче от точки через точку контура q может кончиться финальный участок ДУ dn: (причина, None) или
    (None, (ux, uy, от, до)) в метрах от точки.

    Луч выходит из здания один раз и в зоне отступа своего здания не подходит к нему снова (к другой стене, больше
    чем на APPROACH_ENTRY_M), конец участка лежит за зоной отступа сервиса (норма + ZONE_WIDER_M) до нового входа луча
    в зону. Участок до конца не задевает зон чужих зданий и запретных объектов: это проверяет nearer_entry у каждого
    конца."""
    r = q.distance(cg)
    if r < 1e-6:
        return "точка на границе", None
    ux, uy = (q.x - cg.x) / r, (q.y - cg.y) / r
    need = oks_clearance(dn) + DN[dn]["width_m"] / 2
    along = lambda t: (cg.x + ux * t, cg.y + uy * t)
    ray = LineString([(cg.x, cg.y), along(r + need + 60)])
    hit = ray.intersection(zone)
    spans = sorted((cg.distance(Point(g.coords[0])), cg.distance(Point(g.coords[-1])))
                   for g in getattr(hit, "geoms", [hit]) if g.geom_type == "LineString" and g.length > 0)
    t1, t2 = spans[0][1], math.inf
    for a, b in spans[1:]:
        if a > t1 + 1e-6:
            t2 = a
            break
        t1 = max(t1, b)
    inside = ray.intersection(own) if t1 == math.inf else LineString([(cg.x, cg.y), along(t1)]).intersection(own)
    if len([g for g in getattr(inside, "geoms", [inside]) if g.length > 0.05]) != 1:
        return "луч снова входит в своё здание", None
    ts = np.arange(r, t1, ENTRY_STEP_M)
    d = shapely.distance(shapely.points(cg.x + ux * ts, cg.y + uy * ts), own)
    if len(d) and (np.maximum.accumulate(d) - d).max() > APPROACH_ENTRY_M:
        return "луч в зоне отступа подходит к другой стене своего здания", None
    return None, (ux, uy, t1, min(t2, t1 + 30))


def nearer_entry(cg, own, shells, dn, trees, limit, relink):
    """Ближайшая точка контура ближе limit к точке, через которую финальный участок ДУ dn допустим (entry_window), и
    конец участка на луче, при котором звено от прежней вершины до него годится (relink(конец) — причина или None).
    Возвращает (точка, конец, причины отказа у более близких точек); точка None — такой нет."""
    need = oks_clearance(dn) + DN[dn]["width_m"] / 2
    zone = own.buffer(need - EPS, quad_segs=64)
    reasons = Counter()
    for q in contour_points(shells, cg, limit):
        why, window = entry_window(cg, q, own, zone, dn, trees)
        if why is not None:
            reasons[why] += 1
            continue
        ux, uy, t1, t2 = window
        r = q.distance(cg)
        why = "за зоной отступа нет места для конца участка"
        for t in np.arange(r, t2, ENTRY_STEP_M):
            if t <= t1:
                continue
            end = Point(cg.x + ux * t, cg.y + uy * t)
            if end.distance(own) < need + ZONE_WIDER_M:
                continue
            part = LineString([(cg.x, cg.y), end.coords[0]])
            if any(og is not own and not og.equals(own) and part.distance(og) < need - EPS
                   for _, og in trees["oks_near"](part, need)) or any(
                    part.distance(rg) < FORBID.get(rt, FALLBACK) + DN[dn]["width_m"] / 2 - EPS
                    for _, rt, rg in trees["forbid_near"](part, max(FALLBACK, *FORBID.values()) + DN[dn]["width_m"] / 2)):
                why = "участок задевает зону чужого здания или запретного объекта"
                break
            why = relink((end.x, end.y))
            if why is None:
                return q, end, reasons
        reasons[re.sub(r"\d+(\.\d+)?", "N", why)] += 1
    return None, None, reasons


def next_to(geo, s, node):
    """Соседняя с узлом node вершина участка s."""
    c = list(geo[str(s["properties"]["id"])].coords)
    return c[1] if str(s["properties"]["start_node_id"]) == node else c[-2]


def turn_deg(a, b, c):
    ux, uy, vx, vy = b[0] - a[0], b[1] - a[1], c[0] - b[0], c[1] - b[1]
    nu, nv = math.hypot(ux, uy), math.hypot(vx, vy)
    if nu == 0 or nv == 0:
        return 0.0
    return math.degrees(math.acos(max(-1.0, min(1.0, (ux * vx + uy * vy) / nu / nv))))


def acute_deg(a, b, p, q):
    """Острый угол между прямыми a–b и p–q, градусы."""
    ux, uy, vx, vy = b[0] - a[0], b[1] - a[1], q[0] - p[0], q[1] - p[1]
    return math.degrees(math.atan2(abs(ux * vy - uy * vx), abs(ux * vx + uy * vy)))


def sides(g):
    """Стороны всех колец полигона или звенья линии."""
    if g.geom_type in ("Polygon", "MultiPolygon"):
        g = g.boundary
    for part in getattr(g, "geoms", [g]):
        c = list(part.coords)
        yield from zip(c, c[1:])


def utm(raw):
    return to_utm(shape(raw))


class Report:
    def __init__(self):
        self.count = Counter()
        self.sample = defaultdict(list)

    def add(self, key, text):
        self.count[key] += 1
        if len(self.sample[key]) < 3:
            self.sample[key].append(text)


def load_input(path):
    data = json.load(open(path))
    cps, chambers, pipes, oks, forbid, special, buildings = {}, {}, [], [], [], [], []
    id_types = {}
    # формат раздела 12: расход у oks_future, точка ссылается на него через oks_id; у датасета организаторов расход
    # у самой точки
    future = {str(f["properties"].get("id")): f["properties"].get("flow_tph") for f in data["features"]
              if f["properties"].get("object_type") == "oks_future"}
    for f in data["features"]:
        p = f["properties"]
        t = p.get("object_type")
        id_types[str(p.get("id"))] = type(p.get("id")).__name__
        if t == "oks_connection_point":
            flow = p["flow_tph"] if "flow_tph" in p else future[str(p["oks_id"])]
            cps[str(p["id"])] = (utm(f["geometry"]), flow)
            if "oks_id" in p:
                cps.setdefault(str(p["oks_id"]), cps[str(p["id"])])
        elif t in ("oks_existing", "oks_future"):
            # формат раздела 12: здания — отдельные объекты; старые категории их не проверяют, B16–B18 обходят
            buildings.append((str(p["id"]), utm(f["geometry"])))
        elif t == "heat_chamber":
            chambers[str(p["id"])] = utm(f["geometry"])
        elif t == "heat_network":
            pipes.append((str(p["id"]), utm(f["geometry"]), p.get("diameter")))
        elif t == "restriction":
            rt = p.get("restriction_type")
            g = utm(f["geometry"])
            if rt == "oks":
                oks.append((str(p["id"]), g))
            elif rt in K_SPECIAL:
                special.append((str(p["id"]), rt, g))
            elif rt in FORBID or rt not in RULES["restrictions"]:
                forbid.append((str(p["id"]), rt, g))
    return dict(cps=cps, chambers=chambers, pipes=pipes, oks=oks, forbid=forbid, special=special, id_types=id_types,
                buildings=buildings)


def check_variant(inp, trees, vid, feats, rep):
    segs = [f for f in feats if f["properties"]["object_type"] == "heat_network"]
    by_id = {str(f["properties"]["id"]): f for f in feats}
    types = Counter(f["properties"]["object_type"] for f in feats)
    for t, n in types.items():
        if t not in ALLOWED:
            rep.count[f"A1 лишний тип объекта {t}"] += n

    geo = {}
    for f in feats:
        if f["geometry"]:
            geo[str(f["properties"]["id"])] = utm(f["geometry"])

    # A. ссылки узлов и совпадение концов
    def node_geom(node_id):
        if node_id in inp["cps"]:
            return inp["cps"][node_id][0], "cp"
        if node_id in inp["chambers"]:
            return inp["chambers"][node_id], "existing_chamber"
        f = by_id.get(node_id)
        if f is None:
            return None, "missing"
        return geo.get(node_id), f["properties"]["object_type"]

    adj = defaultdict(list)
    kinds = {}
    for s in segs:
        p = s["properties"]
        line = geo[str(p["id"])]
        for key, end in (("start_node_id", 0), ("end_node_id", -1)):
            node = str(p[key])
            g, kind = node_geom(node)
            kinds[node] = kind
            if kind not in ("cp", "existing_chamber", "heat_chamber", "technical_node"):
                rep.add(f"A2 узел участка ссылается на {kind}", f"{p['id']}.{key}={node}")
            if g is not None and Point(line.coords[end]).distance(g) > NODE_TOL:
                rep.add("A3 конец LineString не совпадает с узлом", f"{p['id']}.{key}")
            adj[node].append(s)

    # B. геометрия участков
    for s in segs:
        p = s["properties"]
        line = geo[str(p["id"])]
        c = list(line.coords)
        for i in range(1, len(c) - 1):
            a = turn_deg(c[i - 1], c[i], c[i + 1])
            if a > 90 + 0.5:
                rep.add("B1 поворот больше 90° внутри участка", f"{p['id']} {a:.1f}°")
            if a < 1.0:
                rep.add("B5 лишняя вершина без поворота (<1°)", f"{p['id']} {a:.2f}°")
        for i in range(len(c) - 1):
            if math.dist(c[i], c[i + 1]) < 1.0 and 1 <= i < len(c) - 2:
                rep.add("B6 звено короче 1 м между двумя поворотами", f"{p['id']} {math.dist(c[i], c[i + 1]):.2f} м")
        if p.get("laying_method") == "special" and len(c) > 2:
            rep.add("B2 специальный проход не одним прямым участком", f"{p['id']} вершин {len(c)}")
        if abs(line.length - p["length"]) > 0.05:
            rep.add("E0 length не равна длине геометрии", f"{p['id']}")
        if p.get("depth_start") is not None or p.get("depth_end") is not None:
            rep.add("E0 depth не null в 2D", f"{p['id']}")
        if p.get("diameter") not in DN:
            rep.add("C0 диаметр не из таблицы", f"{p['id']}")

    # повороты через технические узлы (узел степени 2 делит одну трассу)
    for node, lst in adj.items():
        if kinds.get(node) != "technical_node":
            continue
        if len(lst) != 2:
            rep.add("D1 technical_node не со степенью 2", f"{node} степень {len(lst)}")
            continue
        g = geo[node]
        pts = []
        for s in lst:
            c = list(geo[str(s["properties"]["id"])].coords)
            pts.append(c[1] if Point(c[0]).distance(g) <= NODE_TOL else c[-2])
        a = turn_deg(pts[0], (g.x, g.y), pts[1])
        if a > 90 + 0.5:
            rep.add("B1 поворот больше 90° в technical_node", f"{node} {a:.1f}°")
        p0, p1 = (s["properties"] for s in lst)
        if p0["laying_method"] == p1["laying_method"] == "base" and p0["diameter"] == p1["diameter"]:
            rep.add("D2 technical_node без смены параметров", f"{node}")
        if p0["diameter"] != p1["diameter"] and abs(p0["flow_tph"] - p1["flow_tph"]) < 1e-6:
            rep.add("C3 смена ДУ без смены расхода (technical_node)", f"{node} {p0['diameter']}/{p1['diameter']}")

    # разветвления и камеры
    for node, lst in adj.items():
        kind = kinds.get(node)
        if kind in ("cp", "technical_node") and len(lst) >= 3:
            rep.add(f"D3 разветвление не в камере ({kind})", node)
        if kind == "cp" and len(lst) == 2:
            rep.add("D4 трасса проходит транзитом через точку подключения", node)

    # C. дерево, расходы, ДУ
    ties = [n for n, k in kinds.items() if k not in ("cp", "technical_node")]
    tie_nodes = set()
    for n in ties:
        k = kinds[n]
        if k in ("tie_in", "existing_chamber"):
            tie_nodes.add(n)
        elif k == "heat_chamber":
            g = geo.get(n)
            if g is not None and any(pg.distance(g) <= 0.05 for pg in trees["pipes_near"](g)):
                tie_nodes.add(n)
    parent = {}
    order = []
    seen = set()
    stack = [(n, None) for n in tie_nodes]
    down = defaultdict(list)
    while stack:
        n, via = stack.pop()
        if n in seen:
            continue
        seen.add(n)
        order.append(n)
        for s in adj[n]:
            if s is via:
                continue
            p = s["properties"]
            other = str(p["end_node_id"]) if str(p["start_node_id"]) == n else str(p["start_node_id"])
            if other in seen:
                if other not in tie_nodes or n not in tie_nodes:
                    rep.add("C9 цикл в новой сети", str(p["id"]))
                continue
            parent[other] = (n, s)
            down[n].append((other, s))
            stack.append((other, s))
    unreached = [s for s in segs if str(s["properties"]["start_node_id"]) not in seen]
    if unreached:
        rep.count["C9 участки вне дерева от мест присоединения"] += len(unreached)

    cps_below = {}
    for n in reversed(order):
        acc = {n} if kinds.get(n) == "cp" else set()
        for ch_node, _ in down[n]:
            acc |= cps_below[ch_node]
        cps_below[n] = acc
    seg_below = {id(s_): cps_below[n] for n, (_, s_) in parent.items()}
    flow_below = {}
    for n in reversed(order):
        f = inp["cps"][n][1] if kinds.get(n) == "cp" else 0.0
        flow_below[n] = f + sum(flow_below[c] for c, _ in down[n])
    child_dn = {}
    for n in reversed(order):
        child_dn[n] = max((c_s["properties"]["diameter"] for _, c_s in down[n]), default=0)
    upsized = 0
    for n, (up, s) in parent.items():
        p = s["properties"]
        if abs(flow_below[n] - p["flow_tph"]) > 0.01:
            rep.add("C1 расход участка не равен сумме расходов ниже", f"{p['id']} {p['flow_tph']} vs {flow_below[n]:.2f}")
        need = dn_for(p["flow_tph"])
        if need is None or p["diameter"] < need:
            rep.add("C2 ДУ меньше нужного по расходу", f"{p['id']}")
        if p["diameter"] < child_dn[n]:
            rep.add("C4 ДУ уменьшается к месту присоединения", f"{p['id']} {p['diameter']} < {child_dn[n]}")
        if need is not None and p["diameter"] > max(need, child_dn[n]):
            upsized += 1
    # предельная длина по каждому пути: серии одинакового ДУ от точки к месту присоединения
    over = 0
    worst = []
    for cp in (n for n in order if kinds.get(n) == "cp"):
        n, run_dn, run = cp, None, 0.0
        while n in parent:
            up, s = parent[n]
            p = s["properties"]
            if p["diameter"] != run_dn:
                if run_dn is not None and run > DN[run_dn]["max_length_m"] + 0.01:
                    over += 1
                    worst.append((run - DN[run_dn]["max_length_m"], cp, run_dn, run))
                run_dn, run = p["diameter"], 0.0
            run += p["length"]
            n = up
        if run_dn is not None and run > DN[run_dn]["max_length_m"] + 0.01:
            over += 1
            worst.append((run - DN[run_dn]["max_length_m"], cp, run_dn, run))
    if over:
        rep.count["C5 превышена предельная длина ДУ на пути"] += over
        for w in sorted(worst, reverse=True)[:3]:
            rep.sample["C5 превышена предельная длина ДУ на пути"].append(f"точка {w[1]} ДУ{w[2]} {w[3]:.0f} м > {DN[w[2]]['max_length_m']}")
    # завышение ДУ: жадный минимум снизу вверх по цепочкам постоянного расхода — между узлами смены расхода ДУ один
    # (технические узлы цепочку не рвут); ДУ цепочки не меньше ДУ по расходу и ДУ детей, затем подъём, пока самый
    # длинный путь одного ДУ через цепочку не уложится в предел (п. 2.3, разъяснение 1)
    if upsized:
        greedy, run_top, chain_of, below_of = {}, {}, {}, {}
        for n in reversed(order):
            if n not in parent:
                continue
            p = parent[n][1]["properties"]
            kids = [c for c, _ in down[n]]
            if len(kids) == 1 and abs(parent[kids[0]][1]["properties"]["flow_tph"] - p["flow_tph"]) < 1e-6:
                chain = chain_of[kids[0]] + [n]
                below = below_of[kids[0]]
            else:
                chain, below = [n], kids
            total = sum(parent[m][1]["properties"]["length"] for m in chain)
            dn_ = max(dn_for(p["flow_tph"]) or DNS[0], max((greedy[b] for b in below), default=0))
            while True:
                run = total + max((run_top[b] for b in below if greedy[b] == dn_), default=0.0)
                if run <= DN[dn_]["max_length_m"] + 0.01 or DNS.index(dn_) + 1 >= len(DNS):
                    break
                dn_ = DNS[DNS.index(dn_) + 1]
            for m in chain:
                greedy[m] = dn_
            chain_of[n], below_of[n], run_top[n] = chain, below, run
        for n in parent:
            p = parent[n][1]["properties"]
            if p["diameter"] > greedy[n]:
                rep.add("C6 ДУ выше минимального, который проходит по расходу, монотонности и длине", f"{p['id']} {p['diameter']} > {greedy[n]}")
        rep.count["i  ДУ выше минимального по расходу и монотонности (всего)"] += upsized

    # камеры: примыкания, диаметр, стоимость
    new_chambers = [f for f in feats if f["properties"]["object_type"] == "heat_chamber"]
    tie_ids = [str(f["properties"]["id"]) for f in feats if f["properties"]["object_type"] == "tie_in"]
    at_tie = {}
    for ch in new_chambers:
        cid = str(ch["properties"]["id"])
        at_tie[cid] = [t for t in tie_ids if geo[t].distance(geo[cid]) <= NODE_TOL]
    chamber_cost_sum = 0.0
    for ch in new_chambers:
        cid = str(ch["properties"]["id"])
        g = geo[cid]
        if at_tie[cid]:
            adj[cid] = adj[cid] + [s for t in at_tie[cid] for s in adj[t]]
        on_pipe = [pid for pid, pg, _ in trees["pipes_near_ids"](g) if pg.distance(g) <= 0.05]
        split = 0
        pipe_dns = []
        for pid, pg, pdn in trees["pipes_near_ids"](g):
            if pg.distance(g) > 0.05:
                continue
            ends = Point(pg.coords[0]).distance(g) <= 0.05 or Point(pg.coords[-1]).distance(g) <= 0.05
            split += 1 if ends else 2
            pipe_dns.append(pdn)
        total = split + len(adj[cid])
        if total > 4:
            rep.add("D5 у новой камеры больше 4 примыканий", f"{cid}: сеть {split} + новых {len(adj[cid])}")
        if not on_pipe and len(adj[cid]) == 2:
            rep.add("D6 новая камера без разветвления (степень 2)", cid)
        if not adj[cid]:
            rep.add("D10 новая камера без новых участков (камера реконструкции)", f"{cid} ДУ{ch['properties'].get('diameter')}")
            continue
        new_dn = max((s["properties"]["diameter"] for s in adj[cid]), default=0)
        all_dn = max([new_dn] + [d for d in pipe_dns if isinstance(d, int)])
        out_dn = ch["properties"].get("diameter")
        if out_dn not in (new_dn, all_dn):
            rep.add("D7 diameter камеры не равен наибольшему ДУ примыканий", f"{cid} {out_dn} vs {new_dn}/{all_dn}")
        if on_pipe and all_dn != new_dn:
            rep.count["i  камеры на сети: ДУ по трубе больше ДУ новых (п.3.2 читаем как все примыкания)"] += 1
        if out_dn not in DN or abs(ch["properties"]["cost"] - chamber_cost(out_dn)) > 1:
            rep.add("D8 стоимость камеры не по таблице 3.2", cid)
        chamber_cost_sum += chamber_cost(all_dn)
        if on_pipe:
            near = [(inp["chambers"][e].distance(g), e) for e in trees["chambers_near"](g, 10.0)]
            for d, e in sorted(near):
                links = trees["chamber_links"](e) + len(adj[e]) + len(adj[cid])
                if links <= 4:
                    rep.add("D9 новая камера в 10 м от существующей, которую можно было использовать", f"{cid} → {e} {d:.1f} м")
                    break

    # tie_in старого формата: пересчёт в правила 18.09
    ties_old = [f for f in feats if f["properties"]["object_type"] == "tie_in"]
    new_tie_chambers, existing_tie_ins = 0, 0
    new_tie_chamber_cost = 0.0
    for t in ties_old:
        tid = str(t["properties"]["id"])
        dn = max((s["properties"]["diameter"] for s in adj[tid]), default=0)
        if t["properties"].get("existing_object_type") == "heat_chamber":
            existing_tie_ins += len(adj[tid])
        elif any(tid in v for v in at_tie.values()):
            rep.count["i  врезка в трубу уже с новой камерой в той же точке (старый формат)"] += 1
        else:
            new_tie_chambers += 1
            new_tie_chamber_cost += chamber_cost(dn)
            g = geo[tid]
            for d, e in sorted((inp["chambers"][e].distance(g), e) for e in trees["chambers_near"](g, 10.0)):
                if trees["chamber_links"](e) + len(adj[tid]) <= 4:
                    rep.add("D9 врезка в трубу в 10 м от существующей камеры, которую нужно было использовать", f"{tid} → {e} {d:.1f} м")
                    break
    for n, k in kinds.items():
        if k == "existing_chamber":
            existing_tie_ins += len(adj[n])

    # B. препятствия: ОКС (все полигоны), запретные, специальные
    # финальный прямой участок к точке: последнее звено участка у точки и коллинеарные с ним участки через
    # технические узлы (спецпроход режет прямую техническими узлами); по id участка — (точка, его прямая часть)
    final_piece = {}
    seg_by_id = {str(s_["properties"]["id"]): s_ for s_ in segs}
    for s in segs:
        p = s["properties"]
        for key, end in (("start_node_id", 0), ("end_node_id", -1)):
            if kinds.get(str(p[key])) != "cp":
                continue
            cp_id = str(p[key])
            c = list(geo[str(p["id"])].coords)
            piece = c[:2] if end == 0 else c[-2:]
            ray = LineString(piece)
            final_piece[str(p["id"])] = (cp_id, ray)
            # назад по цепочке: узел на дальнем конце звена
            node = str(p["start_node_id"]) if end == -1 else str(p["end_node_id"])
            cur = s
            while kinds.get(node) == "technical_node" and len(list(geo[str(cur["properties"]["id"])].coords)) == 2:
                nxt = [t for t in adj[node] if t is not cur]
                if len(nxt) != 1:
                    break
                cur = nxt[0]
                cc = list(geo[str(cur["properties"]["id"])].coords)
                far = str(cur["properties"]["start_node_id"]) if str(cur["properties"]["end_node_id"]) == node else str(cur["properties"]["end_node_id"])
                tail = cc[-2:] if str(cur["properties"]["end_node_id"]) == node else cc[:2][::-1]
                if turn_deg(tail[0], tail[1], piece[0] if end == 0 else piece[1]) > 1.0 and turn_deg(tail[0], tail[1], piece[1] if end == 0 else piece[0]) > 1.0:
                    break
                final_piece[str(cur["properties"]["id"])] = (cp_id, LineString(tail))
                node = far
    oks_viol, oks_own, oks_start = 0, 0, 0
    for s in segs:
        p = s["properties"]
        sid = str(p["id"])
        line = geo[sid]
        dn = p["diameter"]
        need = oks_clearance(dn) + DN[dn]["width_m"] / 2
        cp_here = final_piece.get(sid)
        for oid, og in trees["oks_near"](line, need):
            d = line.distance(og)
            if d >= need - EPS:
                continue
            own = cp_here is not None and og.buffer(0.01).contains(inp["cps"][cp_here[0]][0])
            if own:
                c = list(line.coords)
                ray = list(cp_here[1].coords)
                if len(c) <= 2:
                    rest = None
                elif tuple(ray[1]) == tuple(c[-1]) or tuple(ray[0]) == tuple(c[-1]):
                    rest = LineString(c[:-1])
                else:
                    rest = LineString(c[1:])
                rest_d = rest.distance(og) if rest is not None else math.inf
                if rest_d >= need - EPS:
                    continue
                oks_own += 1
                rep.add("B3 отступ до полигона своего ОКС нарушен до финального прямого участка", f"{sid} {rest_d:.2f} м < {need:.2f}")
                continue
            targets = set(trees["cps_in"](oid))
            if targets & seg_below.get(id(s), set()):
                key = "B3 отступ до полигона своего ОКС нарушен до финального прямого участка"
            else:
                key = "B3 отступ до чужого полигона ОКС нарушен" + (" (пересекает)" if d == 0 else "")
            oks_viol += 1
            rep.add(key, f"{sid} ДУ{dn} {d:.2f} м < {need:.2f} (ОКС {oid})")
            if d == 0 and "чужого" in key:
                rep.count["i  длина сети внутри чужих полигонов ОКС, м"] += round(line.intersection(og).length)
    for sid, (cp, piece) in final_piece.items():
        cg = inp["cps"][cp][0]
        own = [og for oid, og in trees["oks_near"](cg, 0.0) if og.buffer(0.01).contains(cg)]
        for og in own:
            pieces = [g for g in getattr(piece.intersection(og), "geoms", [piece.intersection(og)]) if g.length > 0.05]
            if len(pieces) > 1:
                rep.add("B4 финальный участок выходит из своего здания и входит в него снова", f"{sid} куски {[round(g.length, 1) for g in pieces]}")
    # B19: п. 2.2 снимает отступ к своему полигону только с части финального участка в зоне перед границей. Участок
    # от точки выходит из этой зоны один раз, иначе он снова подходит к своему зданию ближе отступа по своему Ду
    by_cp = defaultdict(list)
    for sid, (cp, piece) in final_piece.items():
        by_cp[cp].append(sid)
    for cp, sids in by_cp.items():
        cg = inp["cps"][cp][0]
        ray = shapely.line_merge(shapely.MultiLineString([final_piece[sid][1] for sid in sids]))
        dn = max(seg_by_id[sid]["properties"]["diameter"] for sid in sids)
        need = oks_clearance(dn) + DN[dn]["width_m"] / 2
        for og in (og for _, og in trees["oks_near"](cg, 0.0) if og.buffer(0.01).contains(cg)):
            hit = ray.intersection(og.buffer(need - EPS, quad_segs=64))
            parts = shapely.line_merge(shapely.MultiLineString(
                [g for g in getattr(hit, "geoms", [hit]) if g.geom_type == "LineString" and g.length > 0]))
            back = [g for g in getattr(parts, "geoms", [parts]) if g.length > 0.05 and g.distance(cg) > 0.05]
            if back:
                rep.add("B19 финальный участок повторно заходит в зону отступа своего здания",
                        f"{','.join(sids)} ДУ{dn} {min(g.distance(og) for g in back):.2f} м < {need:.2f}")
            # и в самой зоне после выхода из здания не подходит к нему снова, к другой стене
            c = list(ray.coords) if Point(ray.coords[0]).distance(cg) < 0.05 else list(ray.coords)[::-1]
            out = LineString(c).intersection(og)
            start = next((g.length for g in getattr(out, "geoms", [out]) if g.distance(cg) < 0.05), 0.0)
            ts = np.arange(start, ray.length, ENTRY_STEP_M)
            pts = [LineString(c).interpolate(t) for t in ts]
            d = shapely.distance(shapely.points([(p.x, p.y) for p in pts]), og) if pts else np.array([])
            d = d[:np.argmax(d >= need - EPS)] if (d >= need - EPS).any() else d
            if len(d) and (np.maximum.accumulate(d) - d).max() > APPROACH_M:
                rep.add("B19 финальный участок в зоне отступа своего здания подходит к другой стене",
                        f"{','.join(sids)} ДУ{dn} ближе на {(np.maximum.accumulate(d) - d).max():.2f} м")
    for s in segs:
        p = s["properties"]
        line = geo[str(p["id"])]
        dn = p["diameter"]
        for rid, rt, rg in trees["forbid_near"](line, max(FALLBACK, *FORBID.values()) + DN[dn]["width_m"] / 2):
            d = line.distance(rg)
            if d < FORBID.get(rt, FALLBACK) + DN[dn]["width_m"] / 2 - EPS:
                rep.add(f"B7 {'пересекает' if d == 0 else 'ближе отступа к'} {rt}", f"{p['id']} {d:.2f} м ({rt} {rid})")
    ties = {n: node_geom(n)[0] for n in tie_nodes}
    check_specials(trees, segs, geo, adj, kinds, ties, rep)
    # путь точки к месту присоединения в узле: участок ниже узла и участок к месту присоединения; в месте присоединения
    # путь новой сети кончается, направление теплоносителя в существующей сети не задано
    path_pairs = {n: [(cs, s) for _, cs in down[n]] for n, (_, s) in parent.items() if n in geo}
    if SHAPE:
        check_shape(trees, segs, geo, adj, kinds, final_piece, ties, parent, path_pairs, inp["cps"], rep)

    # E. стоимость участков по правилам 18.09 (без надбавки за поворот)
    seg_cost_new = 0.0
    for s in segs:
        p = s["properties"]
        line = geo[str(p["id"])]
        k = 1.0
        if p["laying_method"] == "special":
            chain = [line]
            for node in (str(p["start_node_id"]), str(p["end_node_id"])):
                for t in adj.get(node, []):
                    if t is not s and t["properties"]["laying_method"] == "special":
                        chain.append(geo[str(t["properties"]["id"])])
            hit = []
            for rid, rt, rg in trees["special_near"](line, 3.1):
                if rg.geom_type in ("Polygon", "MultiPolygon"):
                    if line.intersects(rg.buffer(MARGIN[rt] + 0.001)) and any(rg.intersects(part) for part in chain):
                        hit.append(rt)
                elif crossing_near(line, chain, rg, MARGIN[rt]):
                    hit.append(rt)
            if any(crossing_near(line, chain, pg, MARGIN["heat_network"]) for pg in trees["pipes_near"](line)):
                hit.append("heat_network")
            k = max((K_SPECIAL[rt] for rt in hit), default=1.0)
        cost = line.length * DN[p["diameter"]]["new_rub_m"] * k
        seg_cost_new += cost
        if abs(cost - p["cost"]) > max(1.0, 0.01 * DN[p["diameter"]]["new_rub_m"] * k):
            rep.count["E1 cost участка не равен L·c·Kспец (без надбавки за поворот)"] += 1
            if len(rep.sample["E1 cost участка не равен L·c·Kспец (без надбавки за поворот)"]) < 3:
                rep.sample["E1 cost участка не равен L·c·Kспец (без надбавки за поворот)"].append(f"{p['id']} {p['cost']:.0f} vs {cost:.0f} (x{p['cost'] / cost:.3f})")

    summary = next(f["properties"] for f in feats if f["properties"]["object_type"] == "variant_summary")
    missing = [k for k in SUMMARY if k not in summary]
    if missing:
        rep.add("E2 в сводке нет полей", ", ".join(missing))
    extra = [k for k in ("tie_in_cost", "reconstruction_cost", "chamber_reconstruction_cost", "reconstruction_length", "length") if k in summary]
    if extra:
        rep.add("i  в сводке поля старого формата (допускаются как доп. свойства)", ", ".join(extra))
    if summary.get("reconstruction_cost"):
        rep.add("E3 в construction_cost входит реконструкция", f"{summary['reconstruction_cost']:.0f} руб")
    if abs(summary.get("length", summary["new_network_length"]) - summary["new_network_length"]) > 0.01:
        rep.add("E4 в S учтена длина реконструкции", f"length={summary['length']} new={summary['new_network_length']}")
    ids = summary["unconnected_oks_ids"]
    known = [x for x in ids if str(x) in inp["id_types"]]
    wrong_type = sum(1 for x in known if type(x).__name__ != inp["id_types"][str(x)])
    if wrong_type:
        rep.add("E5 тип id в unconnected_oks_ids не как во входе", f"{wrong_type} из {len(known)} проверенных, всего {len(ids)}")
    if len(known) == len(ids):
        penalty = sum(1e8 + 5e5 * inp["cps"][str(x)][1] for x in ids)
    else:
        penalty = summary["unconnected_penalty"]
    tie_cost_out = summary.get("tie_in_cost", 0.0)
    with_all = sum(x["properties"]["cost"] for x in segs) + summary["chamber_construction_cost"] + tie_cost_out
    if abs(summary["construction_cost"] - with_all) > 1 and abs(summary["construction_cost"] - sum(x["properties"]["cost"] for x in segs)) <= 1:
        rep.add("E6 construction_cost без камер и врезок (п.6: должен включать)", f"{summary['construction_cost']:.0f} вместо {with_all:.0f}")
    construction = seg_cost_new + chamber_cost_sum + new_tie_chamber_cost + 5e6 * existing_tie_ins
    calculated = construction + penalty
    length = sum(s["properties"]["length"] for s in segs)
    score = 0.7 * calculated / 25e6 + 0.3 * length / 100
    parts = dict(seg_out=round(sum(x["properties"]["cost"] for x in segs)), seg_1809=round(seg_cost_new),
                 chambers=round(chamber_cost_sum), tie_chambers=round(new_tie_chamber_cost), existing_ties=existing_tie_ins)
    print("   parts", parts)
    return dict(variant=vid, score_out=summary["score"], score_1809=round(score, 3), unconnected=len(ids),
                construction_out=summary["construction_cost"], construction_1809=round(construction, 2),
                tie_new_chambers=new_tie_chambers, existing_tie_ins=existing_tie_ins)


def check_specials(trees, segs, geo, adj, kinds, ties, rep):
    """B9–B15: спецпроходы, отступы от спецобъектов и существующей сети, пересечения новых участков между собой.

    Зона спецпрохода объекта (п. 4, docs/interpretation.md): у полигона — его буфер на margin_m, если прогон
    спецучастков (связные через узлы спецучастки) пересекает полигон; у линии — margin_m от точки пересечения с
    прогоном в обе стороны. Пересечение сети в точке врезки (не дальше 0,5 м от врезки) пересечением не считается."""
    if not segs:
        return
    sid_of = {id(s): str(s["properties"]["id"]) for s in segs}
    line_of = {id(s): geo[sid_of[id(s)]] for s in segs}
    nodes_of = {id(s): (str(s["properties"]["start_node_id"]), str(s["properties"]["end_node_id"])) for s in segs}
    special = [s for s in segs if s["properties"]["laying_method"] == "special"]

    def at_tie(pt):
        return any(pt.distance(t) <= 0.5 for t in ties.values())

    def crossings(line, rt, rg):
        pts = [Point(c) for c in shapely.get_coordinates(line.intersection(rg))]
        return [pt for pt in pts if not (rt == "heat_network" and at_tie(pt))]

    run_of = {}
    for s in special:
        if id(s) in run_of:
            continue
        run, stack = [], [s]
        while stack:
            t = stack.pop()
            if id(t) in run_of:
                continue
            run_of[id(t)] = run
            run.append(line_of[id(t)])
            stack += [u for n in nodes_of[id(t)] for u in adj[n]
                      if u["properties"]["laying_method"] == "special" and id(u) not in run_of]
    obj = {}
    zones = {}
    for s in special:
        line = line_of[id(s)]
        run = run_of[id(s)]
        z = {}
        for rid, rt, rg, _ in trees["spec_near"](line, max(MARGIN.values()) + ZONE_TOL):
            obj[(rt, rid)] = rg
            if rg.geom_type.endswith("Polygon"):
                if any(part.intersects(rg) for part in run):
                    z[(rt, rid)] = line.intersection(rg.buffer(MARGIN[rt] + ZONE_TOL))
            elif rg.geom_type != "Point":
                xs = [pt for part in run for pt in crossings(part, rt, rg)]
                if xs:
                    z[(rt, rid)] = line.intersection(shapely.union_all([x.buffer(MARGIN[rt] + ZONE_TOL) for x in xs]))
        zones[id(s)] = {k: g for k, g in z.items() if g.length > ZONE_TOL}

    for s in special:
        sid, line = sid_of[id(s)], line_of[id(s)]
        c = list(line.coords)
        # B13: спецучасток только там, где трасса пересекает спецобъект или идёт по его полосе
        if not zones[id(s)]:
            rep.add("B13 специальный участок не пересекает спецобъект и не лежит в его полосе", f"{sid} {line.length:.1f} м")
        else:
            out = line.length - shapely.union_all(list(zones[id(s)].values())).length
            if out > ZONE_TOL:
                rep.add("B13 специальный участок выходит за полосу спецпрохода", f"{sid} вне полосы {out:.2f} м из {line.length:.2f}")
        # B9: угол со стороной полигона или звеном линии в точке пересечения (п. 4, разъяснение 6)
        for rid, rt, rg, _ in trees["spec_near"](line, 0.0):
            if rt not in MIN_ANGLE:
                continue
            angles = [acute_deg(c[0], c[-1], p, q) for p, q in sides(rg) if LineString([p, q]).intersects(line)]
            if angles and min(angles) < MIN_ANGLE[rt] - 0.01:
                rep.add("B9 угол пересечения спецобъекта меньше min_angle_deg",
                        f"{sid} {min(angles):.2f}° < {MIN_ANGLE[rt]}° ({rt} {rid})")

    for node, lst in adj.items():
        spec_here = [t for t in lst if t["properties"]["laying_method"] == "special"]
        # B14: технический узел между спецучастками ставится только там, где меняется набор объектов (п. 4)
        if kinds.get(node) == "technical_node" and len(lst) == 2 and len(spec_here) == 2 \
                and set(zones[id(lst[0])]) == set(zones[id(lst[1])]):
            rep.add("B14 одно пересечение разбито на несколько спецучастков без смены набора объектов",
                    f"{node} {sorted(zones[id(lst[0])])}")
        # B12: спецучасток кончается там, где кончается полоса: иначе соседний участок в полосе тоже special
        for t in spec_here:
            tc = list(line_of[id(t)].coords)
            end = Point(tc[0] if nodes_of[id(t)][0] == node else tc[-1])
            for key in zones[id(t)]:
                rt, rid = key
                rg = obj[key]
                if rg.geom_type.endswith("Polygon"):
                    d = rg.distance(end)
                else:
                    d = min((x.distance(end) for x in crossings(line_of[id(t)], rt, rg)), default=math.inf)
                if d >= MARGIN[rt] - ZONE_TOL:
                    continue
                for s in lst:
                    if s is not t and key not in zones.get(id(s), {}):
                        rep.add("B12 спецпроход кончается внутри полосы margin_m",
                                f"{sid_of[id(s)]} у узла {node}: {rt} {rid} в {d:.2f} м < {MARGIN[rt]}")

    # звенья без отступа до объекта (ключи объектов по (id участка, номер звена)): прямая от врезки до сети, которая
    # её касается, и прямое продолжение спецучастка через узел — хвост того же прямого пересечения. Прямая идёт через
    # технические узлы, пока направление не меняется больше чем на 1°
    free = defaultdict(set)

    def along(node, prev, came, keys):
        for s in adj[node]:
            if s is came:
                continue
            c = list(line_of[id(s)].coords)
            start = nodes_of[id(s)][0] == node
            end, nb = (c[0], c[1]) if start else (c[-1], c[-2])
            i = 0 if start else len(c) - 2
            if prev is not None and turn_deg(prev, end, nb) >= 1.0 or keys <= free[(id(s), i)]:
                continue
            free[(id(s), i)] |= keys
            other = nodes_of[id(s)][1] if start else nodes_of[id(s)][0]
            if len(c) == 2 and kinds.get(other) == "technical_node":
                along(other, end, s, keys)

    for node, pt in ties.items():
        along(node, None, None, {(rt, rid) for rid, rt, _, _ in trees["spec_near"](pt, 0.5) if rt == "heat_network"})
    for t in special:
        tc = list(line_of[id(t)].coords)
        along(nodes_of[id(t)][0], tc[1], t, set(zones[id(t)]))
        along(nodes_of[id(t)][1], tc[-2], t, set(zones[id(t)]))

    # B10, B11: вне спецпрохода объекта участок держит отступ и объект не пересекает (п. 3.1, п. 4, разъяснение 7)
    for s in segs:
        p = s["properties"]
        sid, line = sid_of[id(s)], line_of[id(s)]
        c = list(line.coords)
        w2 = DN[p["diameter"]]["width_m"] / 2
        own = zones.get(id(s), {})
        for rid, rt, rg, extra in trees["spec_near"](line, trees["spec_reach"] + w2):
            key = (rt, rid)
            if key in own:
                continue
            need = _TYPES[rt]["clearance_m"] + w2 + extra
            pieces = [LineString(c[i:i + 2]) for i in range(len(c) - 1) if key not in free[(id(s), i)]]
            d = min((pc.distance(rg) for pc in pieces), default=math.inf)
            if d >= need - EPS:
                continue
            cat = "B11" if rt == "heat_network" else "B10"
            what = "существующую сеть вне врезки" if rt == "heat_network" else rt
            if d == 0:
                rep.add(f"{cat} пересекает {what} не специальным участком", f"{sid} {p['laying_method']} ({rt} {rid})")
            else:
                rep.add(f"{cat} ближе отступа к {'существующей сети' if rt == 'heat_network' else rt} вне спецпрохода",
                        f"{sid} {d:.2f} м < {need:.2f} ({rt} {rid})")

    # B15: новые участки не пересекаются и не касаются вне общего узла (п. 2.1): ближе 1 мм вне круга 0,15 м
    lines = [line_of[id(s)] for s in segs]
    for s, line in zip(segs, lines):
        if not line.is_simple:
            rep.add("B15 участок пересекает сам себя", sid_of[id(s)])
    left, right = STRtree(lines).query(lines, predicate="dwithin", distance=0.001)
    for i, j in zip(left, right):
        if i >= j:
            continue
        a, b = segs[i], segs[j]
        ca = list(lines[i].coords)
        shared = set(nodes_of[id(a)]) & set(nodes_of[id(b)])
        rest = lines[i]
        for n in shared:
            rest = rest.difference(Point(ca[0] if nodes_of[id(a)][0] == n else ca[-1]).buffer(0.15))
        if not rest.is_empty and rest.distance(lines[j]) < 0.001:
            rep.add("B15 новые участки пересекаются или касаются вне общего узла",
                    f"{sid_of[id(a)]} и {sid_of[id(b)]} {rest.distance(lines[j]) * 1000:.1f} мм")


def signed_turn(a, b, c):
    """Отклонение в вершине b со знаком: плюс — влево, минус — вправо."""
    cross = (b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0])
    return math.copysign(turn_deg(a, b, c), cross)


def extend_cross(p1, p2, q1, q2):
    """Точка, где прямая p1→p2, продолженная вперёд, встречает прямую q2→q1, продолженную назад; None, если нет."""
    d1x, d1y = p2[0] - p1[0], p2[1] - p1[1]
    d2x, d2y = q1[0] - q2[0], q1[1] - q2[1]
    den = d1x * d2y - d1y * d2x
    if abs(den) < 1e-9:
        return None
    wx, wy = q2[0] - p1[0], q2[1] - p1[1]
    t = (wx * d2y - wy * d2x) / den
    u = (wx * d1y - wy * d1x) / den
    return (p1[0] + t * d1x, p1[1] + t * d1y) if t > 0 and u > 0 else None


def check_shape(trees, segs, geo, adj, kinds, final_piece, ties, parent, path_pairs, cps, rep):
    """B16–B18, B20, B21: лишние вершины, двойные повороты и зигзаги внутри участка, повороты на пути точки в узлах
    и изломы у камер ветвления (п. 5, п. 2.1, разъяснение 5). B8: финальный участок не от ближайшей точки контура
    своего здания, хотя ближе есть допустимый вход, от которого звено до прежней вершины перед выходом держит те же
    запасы (п. 2.2, см. nearer_entry).

    Замена возможна, если новая геометрия держит запасы сервиса (толкование п. 5): отступы до ОКС, запретных зон и
    спецобъектов по ДУ участка с запасом CUT_MARGIN_M (существующая сеть у врезки не мешает звену от врезки), не
    ближе CUT_APART_M к другим участкам новой сети (у отрезков из общего узла — у дальних концов), не пересекает
    себя, повороты в вершинах, а на пути точки и в камерах и технических узлах (path_pairs) не круче MAX_TURN_DEG,
    новые звенья не короче CUT_PIECE_M. Вершины финального участка к точке (точка выхода) и спецучастки не меняются.
    Два соседних поворота в одну сторону со звеном от SHORT_LINK_M — тоже B17, если их заменяет одна вершина в лучшей
    точке (one_bend) и путь с ней не длиннее прежнего.

    B20: поворот круче 90° на пути точки в техническом узле — нарушение; в камере ветвления — нарушение, если его
    снимает правка у камеры с теми же запасами (сдвиг камеры вдоль первого звена участка или излом звена у камеры, см.
    TURN_STEP_M, BEND_STEP_DEG) и S растёт не больше TURN_TOLERANCE_S, иначе справочная строка с причиной.

    B21: вершины у новой камеры ветвления лишние, если камеру можно перенести в точку рядом (сетка ±SHIFT_M, первые
    вершины её участков, точки на прямых звеньев у камеры), откуда у её участков вершин меньше, а S растёт не больше
    TURN_TOLERANCE_S. Каждый участок идёт из нового места прямой к первой или второй своей вершине. Новые звенья держат
    те же запасы, поворот в камере по пути от точки к врезке не круче MAX_TURN_DEG, финальный участок к точке остаётся
    на своей прямой, камера не ближе CHAMBER_GAP_M по участку к точке подключения и не ближе отступа с запасом
    CHAMBER_NEAR_M к объектам специального прохода и сети."""
    lines = [geo[str(s["properties"]["id"])] for s in segs]
    tree = STRtree(lines)
    tie_keys = {n: {(rt, rid) for rid, rt, _, _ in trees["spec_near"](pt, 0.5) if rt == "heat_network"}
                for n, pt in ties.items()}
    forbid_reach = max(FALLBACK, *FORBID.values())

    def node_turn(s, node, pt, nb):
        """Наибольший поворот на пути точки в узле node между соседними участками и звеном pt→nb участка s."""
        return max([turn_deg(next_to(geo, other, node), pt, nb) for cs, ps in path_pairs.get(node, [])
                    for other in [ps if cs is s else cs if ps is s else None] if other is not None], default=0.0)

    def blocked(k, new, changed, moved=None, min_piece=CUT_PIECE_M, apart=True, skip=None):
        """Почему геометрия new вместо участка k недопустима; None — допустима. changed — номера новых звеньев,
        moved — новые координаты других участков по номеру, min_piece — наименьшее новое звено, apart — проверять
        зазор до других участков, skip — узел, поворот в котором проверяет вызывающий."""
        moved = moved or {}
        p = segs[k]["properties"]
        start, end = str(p["start_node_id"]), str(p["end_node_id"])
        start, end = (None if start == skip else start), (None if end == skip else end)
        w2 = DN[p["diameter"]]["width_m"] / 2
        need = oks_clearance(p["diameter"]) + w2
        if not LineString(new).is_simple:
            return "участок пересекает сам себя"
        for i in range(max(1, changed[0]), min(len(new) - 1, changed[-1] + 2)):
            if turn_deg(new[i - 1], new[i], new[i + 1]) > MAX_TURN_DEG:
                return f"поворот {turn_deg(new[i - 1], new[i], new[i + 1]):.1f}° > {MAX_TURN_DEG}°"
        if changed[0] == 0 and node_turn(segs[k], start, new[0], new[1]) > MAX_TURN_DEG \
                or changed[-1] == len(new) - 2 and node_turn(segs[k], end, new[-1], new[-2]) > MAX_TURN_DEG:
            return f"поворот в узле на пути точки > {MAX_TURN_DEG}°"
        for i in changed:
            link = LineString(new[i:i + 2])
            if link.length < min_piece:
                return f"звено {link.length:.2f} м < {min_piece} м"
            for oid, og in trees["all_oks_near"](link, need + CUT_MARGIN_M):
                if link.distance(og) < need + CUT_MARGIN_M:
                    return f"отступ до ОКС {oid} {link.distance(og):.2f} м < {need:.2f} + {CUT_MARGIN_M} м"
            for rid, rt, rg in trees["forbid_near"](link, forbid_reach + w2 + CUT_MARGIN_M):
                if link.distance(rg) < FORBID.get(rt, FALLBACK) + w2 + CUT_MARGIN_M:
                    return (f"отступ до {rt} {rid} {link.distance(rg):.2f} м < "
                            f"{FORBID.get(rt, FALLBACK) + w2:.2f} + {CUT_MARGIN_M} м")
            exempt = set()
            if i == 0:
                exempt |= tie_keys.get(start, set())
            if i == len(new) - 2:
                exempt |= tie_keys.get(end, set())
            for rid, rt, rg, extra in trees["spec_near"](link, trees["spec_reach"] + w2 + CUT_MARGIN_M):
                norm = _TYPES[rt]["clearance_m"] + w2 + extra
                if (rt, rid) not in exempt and link.distance(rg) < norm + CUT_MARGIN_M:
                    return f"отступ до {rt} {rid} {link.distance(rg):.2f} м < {norm:.2f} + {CUT_MARGIN_M} м"
            why = crowded(k, new[i:i + 2], moved) if apart else None
            if why:
                return why
        return None

    def crowded(k, ab, moved):
        """Почему звено ab участка k ближе CUT_APART_M к другим участкам (moved — их новые координаты); None — нет.
        Отрезки из общего узла расходятся от него, у них зазор меряется у дальних концов, как Router.apart."""
        link = LineString(ab)
        near = list(tree.query(link, predicate="dwithin", distance=CUT_APART_M))
        for j in near + [j for j in moved if j not in near]:
            if j == k:
                continue
            oc = moved.get(j) or list(lines[j].coords)
            for q in zip(oc, oc[1:]):
                ends = [(x, y) for x in (0, 1) for y in (0, 1) if math.dist(ab[x], q[y]) <= NODE_TOL]
                if ends:
                    x, y = ends[0]
                    gap = min(LineString(q).distance(Point(ab[1 - x])), link.distance(Point(q[1 - y])))
                else:
                    gap = link.distance(LineString(q))
                if gap < CUT_APART_M:
                    return f"ближе {CUT_APART_M} м к участку {segs[j]['properties']['id']} ({gap:.2f} м)"
        return None

    def one_bend(k, c, j, m):
        """Кратчайшая одна вершина X вместо вершин j..m участка k с запасами blocked и длина прежнего пути:
        ((длина c[j-1]→X→c[m+1], X) или None, длина c[j-1]..c[m+1]). Поиск как у аудиторов: сетка в эллипсе с фокусами
        в соседях c[j-1], c[m+1], где сумма расстояний до фокусов не больше прежней длины плюс её излишка над хордой,
        затем спуск по длине от лучших допустимых точек сетки до шага 2 мм. Соседи внутри участка остаются поворотами
        от TURN_DEG. Запасы считаются сразу для всех точек, blocked подтверждает найденную."""
        p = segs[k]["properties"]
        s = segs[k]
        w2 = DN[p["diameter"]]["width_m"] / 2
        need = oks_clearance(p["diameter"]) + w2
        a, b = np.array(c[j - 1]), np.array(c[m + 1])
        d = math.dist(a, b)
        cur = sum(math.dist(c[i], c[i + 1]) for i in range(j - 1, m + 1))
        half = cur - d / 2
        minor = math.sqrt(half * half - d * d / 4)
        e1 = (b - a) / d
        e2 = np.array([-e1[1], e1[0]])
        step = max(minor / 20, 0.02)
        u, v = np.meshgrid(np.arange(-half, half, step), np.arange(-minor, minor, step))
        inside = (u / half) ** 2 + (v / minor) ** 2 <= 1
        grid = (a + b) / 2 + np.outer(u[inside], e1) + np.outer(v[inside], e2)
        region = shapely.MultiPoint(grid).convex_hull if len(grid) > 2 else LineString([a, b])
        start, end = str(p["start_node_id"]), str(p["end_node_id"])
        first, last = j == 1, m + 2 == len(c)
        # препятствия: геометрия, норма с запасом и какое звено (A→X — 0, X→B — 1) освобождено от неё у врезки
        obstacles = [(og, need + CUT_MARGIN_M, ()) for _, og in trees["all_oks_near"](region, need + CUT_MARGIN_M)]
        obstacles += [(rg, FORBID.get(rt, FALLBACK) + w2 + CUT_MARGIN_M, ())
                      for _, rt, rg in trees["forbid_near"](region, forbid_reach + w2 + CUT_MARGIN_M)]
        for rid, rt, rg, extra in trees["spec_near"](region, trees["spec_reach"] + w2 + CUT_MARGIN_M):
            free = [0] if first and (rt, rid) in tie_keys.get(start, set()) else []
            free += [1] if last and (rt, rid) in tie_keys.get(end, set()) else []
            obstacles.append((rg, _TYPES[rt]["clearance_m"] + w2 + extra + CUT_MARGIN_M, free))
        others = [q for jj in tree.query(region, predicate="dwithin", distance=CUT_APART_M) if jj != k
                  for oc in [list(lines[jj].coords)] for q in zip(oc, oc[1:])]
        # повороты на пути точки в узлах, если меняется первое или последнее звено участка
        path_nb = {e: [next_to(geo, o, node) for cs, ps in path_pairs.get(node, [])
                       for o in [ps if cs is s else cs if ps is s else None] if o is not None]
                   for e, node, on in ((0, start, first), (1, end, last)) if on}
        before = [c[j - 2]] if j >= 2 else path_nb.get(0, [])
        after = [c[m + 2]] if m + 2 < len(c) else path_nb.get(1, [])

        def turns_at(prev, pt, nxt):
            u1, u2 = pt - prev, nxt - pt
            cos = (u1 * u2).sum(-1) / np.linalg.norm(u1, axis=-1) / np.linalg.norm(u2, axis=-1)
            return np.degrees(np.arccos(np.clip(cos, -1, 1)))

        def fit(xs):
            """Маска точек xs, в которых X держит отступы, зазор, повороты, изломы соседей и длину звеньев."""
            la, lb = np.linalg.norm(xs - a, axis=1), np.linalg.norm(xs - b, axis=1)
            ok = (la >= CUT_PIECE_M) & (lb >= CUT_PIECE_M) & (turns_at(a, xs, b) <= MAX_TURN_DEG)
            for q in before:
                ok &= turns_at(np.array(q), a, xs) <= MAX_TURN_DEG
            for q in after:
                ok &= turns_at(np.array(q), b, xs) <= MAX_TURN_DEG
            # сосед внутри участка остаётся поворотом: вершина с изломом меньше 3° запрещена
            if j >= 2:
                ok &= turns_at(np.array(c[j - 2]), a, xs) >= TURN_DEG
            if m + 2 < len(c):
                ok &= turns_at(np.array(c[m + 2]), b, xs) >= TURN_DEG
            links = [shapely.linestrings(np.stack([np.broadcast_to(a, xs.shape), xs], 1)),
                     shapely.linestrings(np.stack([xs, np.broadcast_to(b, xs.shape)], 1))]
            for g, norm, free in obstacles:
                for e in (0, 1):
                    if e not in free:
                        ok &= shapely.distance(links[e], g) >= norm
            pts = shapely.points(xs)
            for q in others:
                for e, node in ((0, a), (1, b)):
                    near = [y for y in (0, 1) if math.dist(node, q[y]) <= NODE_TOL]
                    if near:
                        gap = np.minimum(shapely.distance(pts, LineString(q)),
                                         shapely.distance(links[e], Point(q[1 - near[0]])))
                    else:
                        gap = shapely.distance(links[e], LineString(q))
                    ok &= gap >= CUT_APART_M
            return ok

        length = lambda xs: np.linalg.norm(xs - a, axis=1) + np.linalg.norm(xs - b, axis=1)
        good = grid[fit(grid)] if len(grid) else grid
        dirs = np.array([(math.cos(t), math.sin(t)) for t in np.arange(16) * math.pi / 8])
        best = None
        for x in good[np.argsort(length(good))[:3]]:
            h = step
            while h > 0.002:
                cand = x + h * dirs
                cand = cand[fit(cand)]
                lc = length(cand)
                if len(cand) and lc.min() < length(x[None])[0]:
                    x = cand[lc.argmin()]
                else:
                    h /= 2
            lx = length(x[None])[0]
            if best is None or lx < best[0]:
                best = (lx, tuple(x))
        return best, cur

    for k, s in enumerate(segs):
        p = s["properties"]
        sid = str(p["id"])
        cp = str(p["end_node_id"])
        if kinds.get(cp) != "cp" or p["laying_method"] == "special":
            continue
        cg = cps[cp][0]
        c = list(lines[k].coords)
        head = c[:-2] if len(c) > 2 else c[:1]

        def relink(end):
            if turn_deg(head[-1], end, c[-1]) < TURN_DEG:
                return f"поворот у выхода меньше {TURN_DEG}°"
            return blocked(k, head + [end, c[-1]], [len(head) - 1])

        for og in (og for _, og in trees["oks_near"](cg, 0.0) if og.buffer(0.01).contains(cg)):
            shells = [og.exterior] if og.geom_type == "Polygon" else [g.exterior for g in og.geoms]
            to_edge = min(sh.distance(cg) for sh in shells)
            inside = LineString(c[-2:]).intersection(og).length
            if inside <= to_edge + ENTRY_TOL_M:
                continue
            q, end, reasons = nearer_entry(cg, og, shells, p["diameter"], trees, inside - ENTRY_TOL_M, relink)
            if q is None:
                rep.add("i  финальный участок не от ближайшей точки контура: ближе допустимого входа нет",
                        f"{sid} вход {inside:.2f} м, до границы {to_edge:.2f} м: {dict(reasons)}")
            else:
                rep.add("B8 финальный участок не от ближайшего допустимого входа",
                        f"{sid} ДУ{p['diameter']} вход {inside:.2f} м, допустимый {q.distance(cg):.2f} м")

    for k, s in enumerate(segs):
        p = s["properties"]
        sid = str(p["id"])
        c = list(lines[k].coords)
        if p["laying_method"] == "special" or len(c) < 3:
            continue
        fixed = {i for i in range(1, len(c) - 1) if sid in final_piece and tuple(c[i]) in final_piece[sid][1].coords}
        along = [0.0]
        for a, b in zip(c, c[1:]):
            along.append(along[-1] + math.dist(a, b))

        # А. вершина лишняя, если соседей можно соединить прямой
        for i in range(1, len(c) - 1):
            if i in fixed:
                continue
            why = blocked(k, c[:i] + c[i + 1:], [i - 1])
            if why is None:
                rep.add("B16 лишняя вершина: соседей можно соединить прямой",
                        f"{sid} вершина {i} {turn_deg(c[i - 1], c[i], c[i + 1]):.1f}°")
            else:
                rep.add("i  вершину убрать нельзя (B16)", f"{sid} вершина {i}: {why}")

        turns = [(i, signed_turn(c[i - 1], c[i], c[i + 1])) for i in range(1, len(c) - 1)]
        turns = [(i, a) for i, a in turns if abs(a) >= TURN_DEG]

        # Б. цепочка поворотов в одну сторону со звеньями короче 10 м: ищется её часть, которую заменяет один поворот
        # в точке пересечения продолженных крайних отрезков; сначала вся цепочка, потом короче
        chain = []
        for (i, a), nxt in zip(turns, turns[1:] + [None]):
            chain.append((i, a))
            if nxt is not None and nxt[1] * a > 0 and along[nxt[0]] - along[i] < SHORT_LINK_M:
                continue
            runs = sorted(((x, y) for x in range(len(chain)) for y in range(x + 1, len(chain))),
                          key=lambda r: r[0] - r[1])
            first = None
            for x, y in runs:
                j, m = chain[x][0], chain[y][0]
                total = sum(signed_turn(c[v - 1], c[v], c[v + 1]) for v in range(j, m + 1))
                cross = extend_cross(c[j - 1], c[j], c[m], c[m + 1])
                if fixed & set(range(j, m + 1)):
                    why = "в цепочке точка выхода финального участка"
                elif abs(total) > MAX_TURN_DEG:
                    why = f"сумма поворотов {abs(total):.1f}° > {MAX_TURN_DEG}°"
                elif cross is None:
                    why = "продолженные отрезки не пересекаются"
                else:
                    why = blocked(k, c[:j] + [cross] + c[m + 1:], [j - 1, j])
                angles = [round(t[1], 1) for t in chain[x:y + 1]]
                text = f"{sid} вершины {j}–{m} повороты {angles} на {along[m] - along[j]:.1f} м"
                if why is None:
                    rep.add("B17 двойной поворот можно заменить одним", f"{text} → {abs(total):.1f}°")
                    break
                first = first or f"{text}: {why}"
            else:
                if first:
                    rep.add("i  двойной поворот нельзя заменить одним (B17)", first)
            chain = []

        # Б2. два соседних поворота в одну сторону со звеном от 10 м: лишние, если их заменяет одна вершина в лучшей
        # точке и путь с ней не длиннее прежнего
        for (j, a), (m, b) in zip(turns, turns[1:]):
            if a * b < 0 or along[m] - along[j] < SHORT_LINK_M:
                continue
            text = f"{sid} вершины {j}–{m} повороты {a:.1f}° и {b:.1f}° на {along[m] - along[j]:.1f} м"
            if fixed & set(range(j, m + 1)):
                rep.add("i  два поворота одной вершиной не заменить (B17)", f"{text}: точка выхода финального участка")
                continue
            best, cur = one_bend(k, c, j, m)
            new = None if best is None else c[:j] + [best[1]] + c[m + 1:]
            why = "нет допустимой вершины" if best is None else blocked(k, new, [j - 1, j])
            if why is None and best[0] > cur:
                why = f"одна вершина длиннее на {best[0] - cur:.3f} м"
            if why is None:
                rep.add("B17 два поворота в одну сторону заменяет одна вершина, путь не длиннее",
                        f"{text} → {turn_deg(c[j - 1], best[1], c[m + 1]):.1f}° в {best[1][0]:.2f}, "
                        f"{best[1][1]:.2f}, путь {best[0] - cur:+.3f} м")
            else:
                rep.add("i  два поворота одной вершиной не заменить (B17)", f"{text}: {why}")

        # В. два поворота в разные стороны меньше 30° со звеном короче 10 м: зигзаг, если их можно убрать оба
        for (j, a), (m, b) in zip(turns, turns[1:]):
            if a * b > 0 or along[m] - along[j] >= SHORT_LINK_M or max(abs(a), abs(b)) >= 30.0:
                continue
            if fixed & set(range(j, m + 1)):
                why = "точка выхода финального участка"
            else:
                why = blocked(k, c[:j] + c[m + 1:], [j - 1])
            text = f"{sid} вершины {j}–{m} повороты {a:.1f}° и {b:.1f}° на {along[m] - along[j]:.1f} м"
            if why is None:
                rep.add("B18 зигзаг можно спрямить", text)
            else:
                rep.add("i  зигзаг нельзя спрямить (B18)", f"{text}: {why}")

    # Г. изломы у камеры ветвления: камера переносится в точку рядом, откуда у её участков меньше вершин
    index = {id(s): k for k, s in enumerate(segs)}

    def moved_line(x, c, drop):
        """Участок c (координаты от камеры) из точки x, без первой вершины при drop; вершина с изломом меньше TURN_DEG
        уходит, как у сервиса."""
        new = [x] + c[2 if drop else 1:]
        while len(new) > 2 and turn_deg(new[0], new[1], new[2]) < TURN_DEG:
            new = [x] + new[2:]
        return new

    def on_link(x, a, b):
        """Где x на прямой звена a→b: None — не на ней или за концом b, [] — в звене, [x, a] — на продолжении за a."""
        length = math.dist(a, b)
        ux, uy = (b[0] - a[0]) / length, (b[1] - a[1]) / length
        if abs(ux * (x[1] - a[1]) - uy * (x[0] - a[0])) > LINE_TOL_M:
            return None
        along = ux * (x[0] - a[0]) + uy * (x[1] - a[1])
        return None if along >= length - LINE_TOL_M else [] if along >= -LINE_TOL_M else [x, a]

    def move_blocked(cid, x, new, inc, out, up, width):
        """Почему камеру cid нельзя перенести в x, где её участки идут по new (координаты от камеры по id участка);
        None — можно. Первое звено на прямой прежнего звена проверяется только в новой части: в прежнем звене — никак,
        на продолжении за его начало — как финальный участок за точкой выхода. Финальный участок к точке остаётся на
        своей прямой."""
        pt = Point(x)
        for rid, rt, rg, extra in trees["spec_near"](pt, trees["spec_reach"] + width + CHAMBER_NEAR_M):
            if pt.distance(rg) <= _TYPES[rt]["clearance_m"] + width + extra + CHAMBER_NEAR_M:
                return f"камера в {pt.distance(rg):.2f} м от {rt} {rid}"
        for q in inc:
            if up is not None and q is not up and turn_deg(new[id(up)][1], x, new[id(q)][1]) > MAX_TURN_DEG:
                return f"поворот в камере {turn_deg(new[id(up)][1], x, new[id(q)][1]):.1f}° > {MAX_TURN_DEG}°"
        # новые координаты участков в их собственном направлении
        forward = {id(r): out[id(r)][0] == lines[index[id(r)]].coords[0] for r in inc}
        own = {index[id(r)]: new[id(r)] if forward[id(r)] else new[id(r)][::-1] for r in inc}
        for r in inc:
            c, line = out[id(r)], new[id(r)]
            rp = r["properties"]
            sid, k = str(rp["id"]), index[id(r)]
            if math.dist(x, line[1]) < CUT_PIECE_M:
                return f"{sid}: звено {math.dist(x, line[1]):.2f} м < {CUT_PIECE_M} м"
            if "cp" in (kinds.get(str(rp["start_node_id"])), kinds.get(str(rp["end_node_id"]))) \
                    and LineString(line).length < CHAMBER_GAP_M:
                return f"камера ближе {CHAMBER_GAP_M} м к точке подключения по {sid}"
            j = len(c) - len(line) + 1
            part = on_link(x, c[j - 1], c[j])
            if part is None and sid in final_piece and j == len(c) - 1:
                return f"{sid}: финальный участок уходит со своей прямой"
            if part == []:
                continue
            if part:
                # до прежнего начала звена новое только продолжение его прямой
                c = part + c[j:]
                c = c if forward[id(r)] else c[::-1]
                why = blocked(k, c, [0] if forward[id(r)] else [len(c) - 2], own, 0.0, False, cid) \
                    or crowded(k, line[:2], own)
            else:
                why = blocked(k, own[k], [0] if forward[id(r)] else [len(own[k]) - 2], own, skip=cid)
            if why:
                return f"{sid}: {why}"
        return None

    def move_ds(new, inc, out):
        """Изменение S при переносе камеры: длины участков камеры по цене их ДУ."""
        dc = dl = 0.0
        for r in inc:
            d = LineString(new[id(r)]).length - LineString(out[id(r)]).length
            dc += d * DN[r["properties"]["diameter"]]["new_rub_m"]
            dl += d
        return 0.7 * dc / 25e6 + 0.3 * dl / 100

    def move_spots(at, inc, out):
        """Места переноса: сетка ±SHIFT_M с шагом SHIFT_STEP_M, первые вершины участков, проекции камеры на прямые
        вторых звеньев и попарные пересечения прямых первых и вторых звеньев."""
        n = round(SHIFT_M / SHIFT_STEP_M)
        spots = [(at[0] + i * SHIFT_STEP_M, at[1] + j * SHIFT_STEP_M)
                 for i in range(-n, n + 1) for j in range(-n, n + 1) if i or j]
        links = []
        for r in inc:
            c = out[id(r)]
            links.append((c[0], c[1]))
            if len(c) > 2:
                links.append((c[1], c[2]))
                spots.append(c[1])
                spots.append(project_line(at, c[1], c[2]))
        for (a, b), (p, q) in itertools.combinations(links, 2):
            x = cross_lines(a, b, p, q)
            if x is not None and math.dist(x, at) > LINE_TOL_M:
                spots.append(x)
        return spots

    for cid in sorted(n for n, kind in kinds.items() if kind == "heat_chamber" and n not in ties):
        g = geo[cid]
        at = (g.x, g.y)
        inc = adj[cid]
        if len(inc) < 3 or any(s["properties"]["laying_method"] == "special" for s in inc):
            continue
        out = {}
        for s in inc:
            c = list(lines[index[id(s)]].coords)
            out[id(s)] = c if Point(c[0]).distance(g) <= NODE_TOL else c[::-1]
        up = parent[cid][1] if cid in parent else None
        width = DN[max(s["properties"]["diameter"] for s in inc)]["width_m"] / 2
        vertices = sum(len(c) - 2 for c in out.values())
        if not vertices:
            continue
        found = []
        for x in move_spots(at, inc, out):
            options = []
            for r in inc:
                c = out[id(r)]
                lines_r = {}
                for drop in (False, True)[:1 + (len(c) > 2)]:
                    line = moved_line(x, c, drop)
                    if math.dist(x, line[1]) >= CUT_PIECE_M and (
                            len(line) < 3 or turn_deg(line[0], line[1], line[2]) <= MAX_TURN_DEG):
                        lines_r[tuple(map(tuple, line))] = line
                options.append(list(lines_r.values()))
            for combo in itertools.product(*options):
                if sum(len(line) - 2 for line in combo) >= vertices:
                    continue
                new = {id(r): line for r, line in zip(inc, combo)}
                if up is not None and any(q is not up and turn_deg(new[id(up)][1], x, new[id(q)][1]) > MAX_TURN_DEG
                                          for q in inc):
                    continue
                ds = move_ds(new, inc, out)
                if ds <= TURN_TOLERANCE_S:
                    found.append((ds, x, new))
        found.sort(key=lambda f: f[0])
        first = None
        for ds, x, new in found:
            why = move_blocked(cid, x, new, inc, out, up, width)
            left = sum(len(line) - 2 for line in new.values())
            text = f"{cid}: перенос {math.dist(x, at):.2f} м, вершин у камеры {vertices} → {left}, ΔS {ds:+.6f}"
            if why is None:
                rep.add("B21 излом у камеры снимает перенос камеры", text)
                break
            first = first or f"{text}: {why}"
        else:
            if first:
                rep.add("i  излом у камеры переносом не снять (B21)", first)

    # Д. поворот круче 90° на пути точки в узле; в камере ветвления ищется правка, как у сервиса
    def bend_blocked(cid, r, inc, out, up, delta_sign, first_len):
        """Почему звено участка r у камеры нельзя повернуть на delta_sign·δ; (None, ΔS) — можно, см. B20."""
        c = out[id(r)]
        base = math.atan2(c[1][1] - c[0][1], c[1][0] - c[0][0])
        unit = None
        delta = TURN_DEG + BEND_STEP_DEG
        while unit is None and delta <= 90.0:
            a = base + delta_sign * math.radians(delta)
            probe = (c[0][0] + math.cos(a), c[0][1] + math.sin(a))
            if all(turn_deg((probe if r is up else out[id(up)][1]), c[0], (probe if q is r else out[id(q)][1]))
                   <= MAX_TURN_DEG for q in inc if q is not up):
                unit = probe
            delta += BEND_STEP_DEG
        if unit is None:
            return "нет угла, при котором все повороты в камере до 90°", None
        why = None
        rr = CUT_PIECE_M
        while rr + CUT_PIECE_M <= first_len:
            q = (c[0][0] + (unit[0] - c[0][0]) * rr, c[0][1] + (unit[1] - c[0][1]) * rr)
            new = [c[0], q] + c[1:]
            turns = [turn_deg(new[i - 1], new[i], new[i + 1]) for i in (1, 2) if i + 1 < len(new)]
            k = index[id(r)]
            forward = c[0] == lines[k].coords[0]
            own = new if forward else new[::-1]
            if min(turns) < TURN_DEG:
                why = why or f"излом {min(turns):.1f}° < {TURN_DEG}°"
            else:
                why = blocked(k, own, [0, 1] if forward else [len(own) - 3, len(own) - 2])
                if why is None:
                    d = LineString(new).length - LineString(c).length
                    return None, 0.7 * d * DN[r["properties"]["diameter"]]["new_rub_m"] / 25e6 + 0.3 * d / 100
            rr *= 2
        return why or f"звено {first_len:.2f} м короче двух по {CUT_PIECE_M} м", None

    for n, pairs in sorted(path_pairs.items()):
        g = geo[n]
        sharp = [(cs, s, turn_deg(next_to(geo, cs, n), (g.x, g.y), next_to(geo, s, n))) for cs, s in pairs]
        sharp = [(cs, s, a) for cs, s, a in sharp if a > 90.0]
        if not sharp:
            continue
        text = f"{n}: " + ", ".join(f"{cs['properties']['id']} → {s['properties']['id']} {a:.2f}°" for cs, s, a in sharp)
        if kinds.get(n) != "heat_chamber":
            rep.add("B20 поворот на пути точки в узле круче 90°", text)
            continue
        inc = adj[n]
        up = parent[n][1]
        if any(s["properties"]["laying_method"] == "special" for s in inc):
            rep.add("i  поворот на пути точки в камере круче 90° правкой у камеры не снять (B20)",
                    f"{text}: у камеры участок специального прохода")
            continue
        out = {}
        for s in inc:
            c = list(lines[index[id(s)]].coords)
            out[id(s)] = c if Point(c[0]).distance(g) <= NODE_TOL else c[::-1]
        width = DN[max(s["properties"]["diameter"] for s in inc)]["width_m"] / 2
        # участок прямо от камеры в здание: сдвиг камеры вдоль другого участка менял бы финальный участок
        direct = [s for s in inc if str(s["properties"]["id"]) in final_piece and len(out[id(s)]) == 2]
        first = None
        fix = None
        for t in inc:
            c = out[id(t)]
            first_len = math.dist(c[0], c[1])
            step = 1
            if any(r is not t for r in direct):
                first = first or f"сдвиг вдоль {t['properties']['id']}: участок прямо от камеры в здание"
                step = math.inf
            while fix is None and step * TURN_STEP_M <= min(TURN_MAX_M, first_len - CUT_PIECE_M) + 1e-9:
                f = step * TURN_STEP_M / first_len
                x = (c[0][0] + (c[1][0] - c[0][0]) * f, c[0][1] + (c[1][1] - c[0][1]) * f)
                new = {id(r): [x] + out[id(r)][1:] for r in inc}
                why = move_blocked(n, x, new, inc, out, up, width)
                if why is None:
                    ds = move_ds(new, inc, out)
                    if ds <= TURN_TOLERANCE_S:
                        fix = f"сдвиг камеры {step * TURN_STEP_M:.1f} м вдоль {t['properties']['id']}, ΔS {ds:+.6f}"
                    why = f"S выше на {ds:.6f}"
                first = first or f"сдвиг вдоль {t['properties']['id']}: {why}"
                step += 1
            sid = str(t["properties"]["id"])
            if fix is None and not (sid in final_piece and len(c) == 2):
                for sign in (-1, 1):
                    why, ds = bend_blocked(n, t, inc, out, up, sign, first_len)
                    if why is None and ds <= TURN_TOLERANCE_S:
                        fix = f"излом звена {sid} у камеры, ΔS {ds:+.6f}"
                        break
                    first = first or f"излом звена {sid}: {why or f'S выше на {ds:.6f}'}"
        if fix:
            rep.add("B20 поворот на пути точки в камере круче 90° снимает правка у камеры", f"{text}: {fix}")
        else:
            rep.add("i  поворот на пути точки в камере круче 90° правкой у камеры не снять (B20)", f"{text}: {first}")


def project_line(p, a, b):
    """Проекция точки p на прямую a–b."""
    ux, uy = b[0] - a[0], b[1] - a[1]
    t = ((p[0] - a[0]) * ux + (p[1] - a[1]) * uy) / (ux * ux + uy * uy)
    return (a[0] + t * ux, a[1] + t * uy)


def cross_lines(a, b, p, q):
    """Пересечение прямых a–b и p–q; None — они параллельны."""
    dx, dy, ex, ey = b[0] - a[0], b[1] - a[1], q[0] - p[0], q[1] - p[1]
    den = dx * ey - dy * ex
    if abs(den) < 1e-9 * math.hypot(dx, dy) * math.hypot(ex, ey):
        return None
    t = ((p[0] - a[0]) * ey - (p[1] - a[1]) * ex) / den
    return (a[0] + t * dx, a[1] + t * dy)


def trees_for(inp):
    oks_geoms = [g for _, g in inp["oks"]]
    t_oks = STRtree(oks_geoms)
    t_forbid = STRtree([g for _, _, g in inp["forbid"]]) if inp["forbid"] else None
    t_special = STRtree([g for _, _, g in inp["special"]]) if inp["special"] else None
    t_pipes = STRtree([g for _, g, _ in inp["pipes"]])
    ch_ids = list(inp["chambers"])
    t_ch = STRtree([inp["chambers"][c] for c in ch_ids]) if ch_ids else None
    links = {}

    def chamber_links(cid):
        if cid not in links:
            g = inp["chambers"][cid]
            n = 0
            for i in t_pipes.query(g, predicate="dwithin", distance=0.5):
                pg = inp["pipes"][i][1]
                ends = Point(pg.coords[0]).distance(g) <= 0.5 or Point(pg.coords[-1]).distance(g) <= 0.5
                n += 1 if ends else 2
            links[cid] = n
        return links[cid]

    t_cp = STRtree([g for g, _ in inp["cps"].values()])
    cp_ids = list(inp["cps"])
    oks_by_id = dict(inp["oks"])

    def cps_in(oid):
        g = oks_by_id[oid]
        return [cp_ids[i] for i in t_cp.query(g, predicate="dwithin", distance=0.01)]

    # спецобъекты вместе с существующей сетью (разъяснение 10): id, тип, геометрия, полуширина объекта для отступа
    # (half_width_m у линий, половина ширины пары по ДУ у сети; у полигона отступ от границы)
    spec = [(rid, rt, g, 0.0 if g.geom_type.endswith("Polygon") else _TYPES[rt].get("half_width_m", 0.0))
            for rid, rt, g in inp["special"]]
    spec += [(pid, "heat_network", g, DN.get(pdn, {"width_m": 0.0})["width_m"] / 2) for pid, g, pdn in inp["pipes"]]
    t_spec = STRtree([o[2] for o in spec])
    all_oks = inp["oks"] + inp["buildings"]
    t_all_oks = STRtree([g for _, g in all_oks])

    return {
        "spec_near": lambda g, d: [spec[i] for i in t_spec.query(g, predicate="dwithin", distance=d)],
        "spec_reach": max(_TYPES[rt]["clearance_m"] + extra for _, rt, _, extra in spec) if spec else 0.0,
        "cps_in": cps_in,
        "oks_near": lambda g, d: [inp["oks"][i] for i in t_oks.query(g, predicate="dwithin", distance=d)],
        "all_oks_near": lambda g, d: [all_oks[i] for i in t_all_oks.query(g, predicate="dwithin", distance=d)],
        "forbid_near": lambda g, d: [inp["forbid"][i] for i in t_forbid.query(g, predicate="dwithin", distance=d)] if t_forbid else [],
        "special_near": lambda g, d: [inp["special"][i] for i in t_special.query(g, predicate="dwithin", distance=d)] if t_special else [],
        "pipes_near": lambda g: [inp["pipes"][i][1] for i in t_pipes.query(g, predicate="dwithin", distance=2.5)],
        "pipes_near_ids": lambda g: [inp["pipes"][i] for i in t_pipes.query(g, predicate="dwithin", distance=0.1)],
        "chambers_near": lambda g, d: [ch_ids[i] for i in t_ch.query(g, predicate="dwithin", distance=d)] if t_ch else [],
        "chamber_links": chamber_links,
    }


def divergence(a, b):
    """Доля расхождения трасс двух вариантов по разд. 6, см. SAME_ROUTE_SHARE."""
    shorter = min(a.length, b.length)
    if shorter == 0:
        return 0.0 if a.length == b.length else 1.0
    inside = min(a.intersection(b.buffer(SAME_ROUTE_M)).length, b.intersection(a.buffer(SAME_ROUTE_M)).length)
    return max(0.0, 1 - inside / shorter)


def layout(inp, trees, feats):
    """Устройство варианта (разд. 6): у каждой связной части новой сети её точки подключения и объекты врезки —
    существующие камеры и трубы, на оси которых стоят её новые камеры. Одно устройство — те же деревья с врезками,
    сдвинутыми по тем же трубам (VariantEnumerator.layout)."""
    root = {}

    def find(n):
        while root.setdefault(n, n) != n:
            n = root[n]
        return n

    chambers = {str(f["properties"]["id"]): utm(f["geometry"]) for f in feats
                if f["properties"]["object_type"] == "heat_chamber"}
    for f in feats:
        p = f["properties"]
        if p["object_type"] == "heat_network":
            root[find(str(p["start_node_id"]))] = find(str(p["end_node_id"]))
    parts = defaultdict(set)
    for n in list(root):
        if n in inp["cps"] or n in inp["chambers"]:
            parts[find(n)].add(n)
        elif n in chambers:
            parts[find(n)].update(pid for pid, pg, _ in trees["pipes_near_ids"](chambers[n])
                                  if pg.distance(chambers[n]) <= 0.05)
    return {frozenset(part) for part in parts.values()}


def main():
    inp = load_input(sys.argv[1])
    out = json.load(open(sys.argv[2]))
    trees = trees_for(inp)
    variants = defaultdict(list)
    for f in out["features"]:
        variants[str(f["properties"]["variant_id"])].append(f)
    ids = Counter(str(f["properties"]["id"]) for f in out["features"])
    dup = sum(1 for v in ids.values() if v > 1)
    bad = 0
    for vid in sorted(variants):
        rep = Report()
        if dup:
            rep.add("A0 неуникальные id во всём файле", str(dup))
        res = check_variant(inp, trees, vid, variants[vid], rep)
        print(f"== вариант {vid}: {res}")
        for key in sorted(rep.count):
            print(f"  {key}: {rep.count[key]}  {rep.sample.get(key, [])}")
            if not key.startswith("i "):
                bad += rep.count[key]
    nets = {vid: shapely.union_all([to_utm(shape(f["geometry"])) for f in feats
                                     if f["properties"]["object_type"] == "heat_network"]) for vid, feats in variants.items()}
    layouts = {vid: layout(inp, trees, feats) for vid, feats in variants.items()}
    vids = sorted(nets)
    pairs = []
    for i, a in enumerate(vids):
        for b in vids[i + 1:]:
            share = divergence(nets[a], nets[b])
            if share < 1 - SAME_ROUTE_SHARE:
                same = " совпадают"
            elif layouts[a] == layouts[b]:
                same = " совпадают по устройству"
            else:
                same = ""
            pairs.append(f"{a}-{b} {100 * share:.1f} %{same}")
    if pairs:
        print(f"  i  расхождение трасс пар вариантов вне полосы {SAME_ROUTE_M:.0f} м, различны от "
              f"{100 * (1 - SAME_ROUTE_SHARE):.0f} % при другом устройстве (разд. 6): " + ", ".join(pairs))
    if bad:
        print(f"CHECK18 VIOLATIONS {bad}")
        sys.exit(1)
    print("CHECK18 OK")


if __name__ == "__main__":
    main()
