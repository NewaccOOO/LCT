"""Проверка выхода по техническому приложению и разъяснениям от 18.09.2026.

Запуск из корня: uv run --project tools python tools/validator/check18.py <вход.geojson> <выход.geojson>
Печатает нарушения по категориям (A — состав и ссылки, B — геометрия и отступы, C — расходы и ДУ, D — камеры,
E — стоимость и сводка), строки «i» — справочные. В конце CHECK18 OK и код 0, если нарушений нет.
"""
import json
import math
import sys
from collections import Counter, defaultdict

import os

import numpy as np
import shapely
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
FORBID = {"park": 1.0, "social_area": 1.0, "prohibited_site": 1.0, "water": 1.0, "railway": 1.0}
K_SPECIAL = {"road": 1.6, "tram_tracks": 1.75, "gas_pipeline": 1.25, "power_cable": 1.15, "heat_network": 1.05}
MARGIN = {"road": 3.0, "tram_tracks": 3.0, "gas_pipeline": 2.0, "power_cable": 2.0, "heat_network": 2.0}
NODE_TOL = 0.05
EPS = 0.01


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


def turn_deg(a, b, c):
    ux, uy, vx, vy = b[0] - a[0], b[1] - a[1], c[0] - b[0], c[1] - b[1]
    nu, nv = math.hypot(ux, uy), math.hypot(vx, vy)
    if nu == 0 or nv == 0:
        return 0.0
    return math.degrees(math.acos(max(-1.0, min(1.0, (ux * vx + uy * vy) / nu / nv))))


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
    cps, chambers, pipes, oks, forbid, special = {}, {}, [], [], [], []
    id_types = {}
    for f in data["features"]:
        p = f["properties"]
        t = p.get("object_type")
        id_types[str(p.get("id"))] = type(p.get("id")).__name__
        if t == "oks_connection_point":
            cps[str(p["id"])] = (utm(f["geometry"]), p["flow_tph"])
        elif t == "heat_chamber":
            chambers[str(p["id"])] = utm(f["geometry"])
        elif t == "heat_network":
            pipes.append((str(p["id"]), utm(f["geometry"]), p.get("diameter")))
        elif t == "restriction":
            rt = p.get("restriction_type")
            g = utm(f["geometry"])
            if rt == "oks":
                oks.append((str(p["id"]), g))
            elif rt in FORBID:
                forbid.append((str(p["id"]), rt, g))
            elif rt in K_SPECIAL:
                special.append((str(p["id"]), rt, g))
    return dict(cps=cps, chambers=chambers, pipes=pipes, oks=oks, forbid=forbid, special=special, id_types=id_types)


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
            shells = [og.exterior] if og.geom_type == "Polygon" else [g.exterior for g in og.geoms]
            to_edge = min(sh.distance(cg) for sh in shells)
            inside = piece.intersection(og).length
            if inside > to_edge + 1.0:
                # ближайшая сторона бывает перекрыта зоной соседнего здания, тогда выход идёт через другую сторону
                rep.add("i  финальный участок не от ближайшего внешнего контура (сторона перекрыта соседом)",
                        f"{sid} внутри {inside:.1f} м, до границы {to_edge:.1f} м")
    for s in segs:
        p = s["properties"]
        line = geo[str(p["id"])]
        dn = p["diameter"]
        for rid, rt, rg in trees["forbid_near"](line, 1.0 + DN[dn]["width_m"] / 2):
            d = line.distance(rg)
            if d < 1.0 + DN[dn]["width_m"] / 2 - EPS:
                rep.add(f"B7 {'пересекает' if d == 0 else 'ближе отступа к'} {rt}", f"{p['id']} {d:.2f} м ({rt} {rid})")

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

    return {
        "cps_in": cps_in,
        "oks_near": lambda g, d: [inp["oks"][i] for i in t_oks.query(g, predicate="dwithin", distance=d)],
        "forbid_near": lambda g, d: [inp["forbid"][i] for i in t_forbid.query(g, predicate="dwithin", distance=d)] if t_forbid else [],
        "special_near": lambda g, d: [inp["special"][i] for i in t_special.query(g, predicate="dwithin", distance=d)] if t_special else [],
        "pipes_near": lambda g: [inp["pipes"][i][1] for i in t_pipes.query(g, predicate="dwithin", distance=2.5)],
        "pipes_near_ids": lambda g: [inp["pipes"][i] for i in t_pipes.query(g, predicate="dwithin", distance=0.1)],
        "chambers_near": lambda g, d: [ch_ids[i] for i in t_ch.query(g, predicate="dwithin", distance=d)] if t_ch else [],
        "chamber_links": chamber_links,
    }


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
    if bad:
        print(f"CHECK18 VIOLATIONS {bad}")
        sys.exit(1)
    print("CHECK18 OK")


if __name__ == "__main__":
    main()
