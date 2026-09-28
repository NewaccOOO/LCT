"""Укладка графа сети АО «Теплоэнерго» (Н. Новгород) на карту по улицам OSM → restored_heat.geojson.

    uv run --project tools --with osmium python scripts/heat_embed_nn.py osm      # слои OSM города из .osm.pbf
    uv run --project tools python scripts/heat_embed_nn.py embed                  # укладка, метрики
    uv run --project tools python scripts/heat_embed_nn.py avtozavod              # Ду и длины «Теплосетей» по районам

Граф — scheme_sections.csv.gz (Схема теплоснабжения до 2030 г., актуализация 2026, Гл. 1 Прил. 2, Ч. 1–4: участки
«узел – узел – Ду – длина – вид – район»), опорные узлы — scheme_anchors.geojson (потребители и источники, привязанные
к зданиям OSM по адресу) и heat_sources.geojson. Методика — data/cities/sources_nnovgorod.md, «Методика восстановления»:
1. граф без параллельных труб (отопление и ГВС одного канала — одно ребро трассы), ключевые узлы — опорные и узлы
   со степенью ≠ 2, между ними цепочки;
2. опорные узлы, противоречащие соседям (прямое расстояние > 1,5 × длина по графу + 50 м), отбрасываются;
3. ключевой узел без адреса ставится в узел графа улиц, где кратчайшие расстояния по улицам до уже уложенных соседей
   лучше всего согласуются с длинами цепочек (не меньше двух уложенных соседей); затем проход уточнения по всем соседям;
4. цепочка — кратчайший путь по улицам и проездам OSM между концами, промежуточные узлы — по накопленной длине.
Узел, который так поставить нельзя (меньше двух уложенных соседей), не выдумывается: его участки уходят в
restored_unlaid.csv.gz и достраиваются на этапе 2 (inferred).
"""
import csv
import gzip
import heapq
import json
import math
import re
import statistics
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np
import shapely
from pyproj import Transformer
from shapely import STRtree
from shapely.geometry import LineString, Point, mapping, shape
from shapely.ops import substring, unary_union

ROOT = Path(__file__).resolve().parent.parent
RAW = ROOT / "data" / "cities" / "raw" / "nnovgorod"
CACHE = ROOT / "data" / "cities" / "cache" / "nnovgorod"
PBF = CACHE / "nizhny_novgorod_oblast-latest.osm.pbf"
LAYERS = CACHE / "osm" / "embed_osm.json"
TO_UTM = Transformer.from_crs("EPSG:4326", "EPSG:32638", always_xy=True)  # UTM 38N: 42–48° в. д.
TO_WGS = Transformer.from_crs("EPSG:32638", "EPSG:4326", always_xy=True)
REF = ("Схема теплоснабжения г. Н. Новгорода до 2030 г. (актуализация на 2026 г.), шифр 22401, Обосновывающие "
       "материалы, Гл. 1 Прил. 2 «Тепловые сети», Ч. 1–4 (таблицы участков электронной модели АО «Теплоэнерго»); "
       "укладка по улицам OSM — scripts/heat_embed_nn.py")

# дороги, по которым может идти трасса: всё, кроме строящихся, проектных, платформ и помещений
NO_ROUTE = {"proposed", "construction", "abandoned", "razed", "platform", "bus_stop", "elevator", "corridor",
            "raceway", "via_ferrata", "services", "rest_area", "emergency_bay", "planned", "disused"}
SITE = {"construction", "brownfield", "greenfield"}
KEEP_TAGS = ("highway", "service", "name", "bridge", "tunnel", "area", "layer", "building", "addr:street",
             "addr:housenumber", "building:levels", "levels", "construction", "start_date", "landuse", "amenity",
             "name", "access", "width", "lanes", "oneway", "height")


def city_region():
    """Восемь районов города (admin_level 9 внутри relation 335495) без Кстовского района."""
    sys.path.insert(0, str(Path(__file__).parent))
    from heat_osm import districts
    return districts(CACHE / "osm" / "adm_nnovgorod.json")


# ---------------------------------------------------------------- OSM

def osm():
    """Улицы и проезды (все highway, по которым можно проложить трубу), здания и участки стройки / пустыри
    в границах восьми районов с запасом 500 м → cache/nnovgorod/osm/embed_osm.json (lon/lat, 7 знаков)."""
    import osmium
    from shapely.prepared import prep
    from heat_osm import rel_geom
    region = unary_union([g for _, g in city_region()]).buffer(0.006)
    inside = prep(region)
    w, s, e, n = region.bounds
    rels, need = {}, set()
    for r in osmium.FileProcessor(str(PBF), osmium.osm.RELATION):
        t = dict(r.tags)
        if t.get("type") == "multipolygon" and ("building" in t or t.get("landuse") in SITE):
            rels[r.id] = (t, [(m.role, m.ref) for m in r.members if m.type == "w"])
            need.update(ref for _, ref in rels[r.id][1])
    out = {"highways": [], "buildings": [], "sites": []}
    ways = {}
    tags_of = lambda t: {k: t[k] for k in KEEP_TAGS if k in t}
    for o in osmium.FileProcessor(str(PBF), osmium.osm.NODE | osmium.osm.WAY).with_locations():
        if not o.is_way():
            continue
        t = dict(o.tags)
        hw = t.get("highway")
        route = hw and hw not in NO_ROUTE and t.get("area") != "yes"
        bld = "building" in t
        site = t.get("landuse") in SITE
        if not (route or bld or site or o.id in need):
            continue
        try:
            pts = [(round(nd.lon, 7), round(nd.lat, 7)) for nd in o.nodes]
            ids = [nd.ref for nd in o.nodes]
        except osmium.InvalidLocationError:
            continue
        if o.id in need:
            ways[o.id] = pts
        lon, lat = pts[len(pts) // 2]
        if not (w <= lon <= e and s <= lat <= n) or not inside.contains(Point(lon, lat)):
            continue
        if route and len(pts) >= 2:
            out["highways"].append([o.id, tags_of(t), ids, pts])
        if (bld or site) and len(pts) >= 4 and pts[0] == pts[-1]:
            out["buildings" if bld else "sites"].append([f"w{o.id}", tags_of(t), [pts]])
    for rid, (t, ms) in rels.items():
        el = {"members": [{"type": "way", "role": role, "geometry": [{"lon": x, "lat": y} for x, y in ways[ref]]}
                          for role, ref in ms if ref in ways]}
        g = rel_geom(el) if el["members"] else None
        if g is None or g.is_empty or not inside.contains(g.representative_point()):
            continue
        rings = [[(round(x, 7), round(y, 7)) for x, y in p.exterior.coords] for p in getattr(g, "geoms", [g])]
        out["buildings" if "building" in t else "sites"].append([f"r{rid}", tags_of(t), rings])
    LAYERS.write_text(json.dumps(out, ensure_ascii=False, separators=(",", ":")))
    print({k: len(v) for k, v in out.items()}, file=sys.stderr)


def load_layers():
    return json.loads(LAYERS.read_text())


def utm_xy(pts):
    x, y = TO_UTM.transform([p[0] for p in pts], [p[1] for p in pts])
    return list(zip(x, y))


def wgs_line(coords):
    lon, lat = TO_WGS.transform([c[0] for c in coords], [c[1] for c in coords])
    return [[round(a, 7), round(b, 7)] for a, b in zip(lon, lat)]


def wgs_point(x, y):
    lon, lat = TO_WGS.transform(x, y)
    return [round(lon, 7), round(lat, 7)]


# ---------------------------------------------------------------- граф улиц

STEP_M = 25.0          # длинные отрезки улиц делятся: место узла сети выбирается не реже чем через 25 м
SNAP_M = 200.0         # здание-опора дальше 200 м от любого проезда не используется
YARD_M = 10.0          # шаг решётки дворов
YARD_REACH_M = 60.0    # решётка — в 60 м от зданий с потребителями схемы
YARD_COST = 1.25       # трасса дворами дороже, чем вдоль проезда: магистрали идут по улицам, дворами — короткие
ENTRY_M = 20.0         # ввод в здание — из узла графа не дальше 20 м от стены
# проезжая часть (для метрики «в 30 м от оси улицы»), как ROAD_CLASS в scripts/city_geojson.py
CARRIAGEWAY = {"motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential",
               "living_street", "road", "busway", "motorway_link", "trunk_link", "primary_link", "secondary_link",
               "tertiary_link", "service"}


class Streets:
    """Граф улиц, проездов и дорожек OSM в UTM 38N: узлы — точки OSM и точки деления длинных отрезков."""

    def __init__(self, highways):
        self.xy, self.adj, index = [], [], {}
        seg_a, seg_b = [], []
        self.axes = []
        for _, tags, ids, pts in highways:
            xy = utm_xy(pts)
            if tags.get("highway") in CARRIAGEWAY and tags.get("service") not in ("parking_aisle", "drive-through"):
                self.axes.append(LineString(xy))
            prev = None
            for nid, p in zip(ids, xy):
                k = index.get(nid)
                if k is None:
                    k = index[nid] = self.add(p)
                if prev is not None and prev != k:
                    for a, b in self.link_dense(prev, k):
                        seg_a.append(a)
                        seg_b.append(b)
                prev = k
        self.seg = np.array([seg_a, seg_b]).T
        xy = np.array(self.xy)
        self.lines = shapely.linestrings(np.stack([xy[self.seg[:, 0]], xy[self.seg[:, 1]]], axis=1))
        self.tree = STRtree(self.lines)

    def add(self, p):
        self.xy.append(p)
        self.adj.append([])
        return len(self.xy) - 1

    def link(self, a, b, w):
        self.adj[a].append((b, w))
        self.adj[b].append((a, w))

    def link_dense(self, a, b):
        (x1, y1), (x2, y2) = self.xy[a], self.xy[b]
        pieces = max(1, math.ceil(math.hypot(x2 - x1, y2 - y1) / STEP_M))
        chain = [a] + [self.add((x1 + (x2 - x1) * i / pieces, y1 + (y2 - y1) * i / pieces))
                       for i in range(1, pieces)] + [b]
        out = []
        for u, v in zip(chain, chain[1:]):
            self.link(u, v, math.dist(self.xy[u], self.xy[v]))
            out.append((u, v))
        return out

    def add_yards(self, buildings, near):
        """Решётка дворов с шагом YARD_M в YARD_REACH_M от зданий near: узлы вне зданий, связи с 8 соседями, если
        отрезок не задевает здание, и с ближайшим узлом улицы в YARD_M. Так трасса может идти дворами наискось,
        как квартальные сети на деле, а не только по осям проездов."""
        n_street = self.n_street = len(self.xy)
        zone = unary_union([p.buffer(YARD_REACH_M, quad_segs=2) for p in near])
        shapely.prepare(zone)
        x0, y0, x1, y1 = zone.bounds
        X, Y = np.meshgrid(np.arange(x0, x1, YARD_M), np.arange(y0, y1, YARD_M))
        ok = shapely.contains_xy(zone, X, Y)
        pts = shapely.points(X[ok], Y[ok])
        inside = np.zeros(len(pts), bool)
        inside[self.btree.query(pts, predicate="intersects")[0]] = True
        cells = np.flatnonzero(ok.ravel())[~inside]
        ids = np.full(X.size, -1, dtype=np.int64)
        ids[cells] = np.arange(len(cells)) + n_street
        for x, y in zip(X.ravel()[cells], Y.ravel()[cells]):
            self.add((float(x), float(y)))
        ids = ids.reshape(X.shape)
        edges = 0
        for di, dj in ((0, 1), (1, 0), (1, 1), (1, -1)):
            h, w_ = ids.shape
            a = ids[0:h - di, max(0, -dj):w_ - max(0, dj)]
            b = ids[di:h, max(0, dj):w_ - max(0, -dj)]
            both = (a >= 0) & (b >= 0)
            ua, ub = a[both], b[both]
            xy = np.array(self.xy)
            segs = shapely.linestrings(np.stack([xy[ua], xy[ub]], axis=1))
            blocked = np.zeros(len(segs), bool)
            blocked[self.btree.query(segs, predicate="intersects")[0]] = True
            w = YARD_M * math.hypot(di, dj)
            for u, v in zip(ua[~blocked].tolist(), ub[~blocked].tolist()):
                self.link(u, v, w)
                edges += 1
        street_pts = shapely.points(np.array(self.xy[:n_street]))
        (iy, ist), d = STRtree(street_pts).query_nearest(shapely.points(np.array(self.xy[n_street:])),
                                                        max_distance=YARD_M, return_distance=True, all_matches=False)
        for i, k, dd in zip(iy.tolist(), ist.tolist(), d.tolist()):
            self.link(n_street + i, k, max(dd, 0.1))
        self.nodes = STRtree(shapely.points(np.array(self.xy)))
        print(f"решётка дворов: узлов {len(cells)}, связей {edges}, со улицами {len(iy)}", file=sys.stderr)

    def entries(self, poly):
        """Узлы графа в ENTRY_M от контура здания: [(узел, расстояние до стены)]."""
        ks = self.nodes.query(poly, predicate="dwithin", distance=ENTRY_M)
        out = [(int(k), float(poly.distance(Point(self.xy[k])))) for k in ks]
        if not out:
            k = int(self.nodes.query_nearest(poly, max_distance=SNAP_M, all_matches=False)[0]) \
                if len(self.nodes.query_nearest(poly, max_distance=SNAP_M)) else None
            if k is not None:
                out = [(k, float(poly.distance(Point(self.xy[k]))))]
        return out

    def ball(self, starts, limit):
        """Кратчайшие расстояния по улицам от starts [(узел, начальное расстояние)] до limit: {узел: м}."""
        dist, best = {}, {}
        heap = list((d, k) for k, d in starts)
        heapq.heapify(heap)
        adj = self.adj
        while heap:
            d, k = heapq.heappop(heap)
            if k in dist:
                continue
            dist[k] = d
            for nb, w in adj[k]:
                nd = d + w
                if nd <= limit and nd < best.get(nb, math.inf) and nb not in dist:
                    best[nb] = nd
                    heapq.heappush(heap, (nd, nb))
        return dist

    def path(self, starts, targets, limit):
        """Кратчайший путь от starts до одного из targets [(узел, добавка)]: список узлов или None."""
        pred, done, best = {}, set(), {}
        goal = dict(targets)
        heap = [(d, k, -1) for k, d in starts]
        heapq.heapify(heap)
        adj = self.adj
        found, total = None, math.inf
        ns = getattr(self, "n_street", len(self.xy))
        while heap:
            d, k, p = heapq.heappop(heap)
            if d >= total:
                break
            if k in done:
                continue
            done.add(k)
            pred[k] = p
            if k in goal and d + goal[k] < total:
                found, total = k, d + goal[k]
            for nb, w in adj[k]:
                nd = d + (w * YARD_COST if k >= ns or nb >= ns else w)
                if nd <= limit and nd < best.get(nb, math.inf) and nb not in done:
                    best[nb] = nd
                    heapq.heappush(heap, (nd, nb, k))
        if found is None:
            return None
        out = [found]
        while pred[out[-1]] != -1:
            out.append(pred[out[-1]])
        return out[::-1]


# ---------------------------------------------------------------- граф сети по таблицам

def pipe_of(kind):
    k = kind.lower()
    return ("inactive" if k.startswith("недейств") else "steam" if k.startswith("паропровод") else
            "hot_water" if "гвс" in k else "heating")


CTP = re.compile(r"^(?:КП-)?ЦТП[ОГИ]?\s*-\s*(.+)$")
CTP_SUFFIX = re.compile(r"[\s_.]+(ВВП.*|гвс.*|ГВС.*|от\.?|отоп.*|гр\.?\s?эл.*|э\d.*|насосы|пов\.нас\.)$", re.I)


def ctp_base(n):
    """«ЦТП-317», «ЦТПО-317», «ЦТПГ-317», «ЦТП-704 ВВП ГВС» → «317»: вход ЦТП со стороны магистрали и выходы
    квартальных сетей отопления (ЦТПО) и ГВС (ЦТПГ) — одно здание, в графе — один узел."""
    m = CTP.match(n)
    if not m:
        return None
    d = re.match(r"\d+", m.group(1))
    return d.group() if d else CTP_SUFFIX.sub("", m.group(1)).strip(" ._")


def canon_names(names):
    groups = defaultdict(list)
    for n in names:
        b = ctp_base(n)
        if b:
            groups[b].append(n)
    return {n: min(v) for v in groups.values() if len(v) > 1 for n in v}


def heat_graph():
    rows = [r for r in csv.DictReader(gzip.open(RAW / "scheme_sections.csv.gz", "rt")) if r["part"] in "1234"]
    alias = canon_names({r["node_from"] for r in rows} | {r["node_to"] for r in rows})
    canon = lambda n: alias.get(n, n)
    pairs = defaultdict(list)
    for r in rows:
        a, b = canon(r["node_from"]), canon(r["node_to"])
        if a != b:
            pairs[tuple(sorted((a, b)))].append(r)
    nbrs = defaultdict(dict)
    for (a, b), rs in pairs.items():
        heat = [float(r["length_m"]) for r in rs if pipe_of(r["kind"]) == "heating"]
        length = statistics.median(heat or [float(r["length_m"]) for r in rs])  # трубы одного канала
        nbrs[a][b] = nbrs[b][a] = length
    par = {}

    def root(x):
        while par.setdefault(x, x) != x:
            par[x] = par[par[x]]
            x = par[x]
        return x
    for a, b in pairs:
        par[root(a)] = root(b)
    km = defaultdict(float)
    for r in rows:
        km[root(canon(r["node_from"]))] += float(r["length_m"])
    order = {c: i + 1 for i, c in enumerate(sorted(km, key=lambda c: (-km[c], c)))}
    comp = {n: order[root(n)] for n in nbrs}
    comp.update({n: comp[c] for n, c in alias.items() if c in comp})
    return rows, pairs, nbrs, comp, canon


def heat_ball(nbrs, start, limit):
    dist, heap = {}, [(0.0, start)]
    while heap:
        d, k = heapq.heappop(heap)
        if k in dist:
            continue
        dist[k] = d
        for nb, w in nbrs[k].items():
            if d + w <= limit and nb not in dist:
                heapq.heappush(heap, (d + w, nb))
    return dist


def load_anchors(nbrs, canon):
    """Опорные узлы: {узел: (x, y, роль, адрес)}; Сормовская ТЭЦ — по heat_sources.geojson (узел «Сормовская ТЭЦ»)."""
    anchors = {}
    for f in json.loads((RAW / "scheme_anchors.geojson").read_text())["features"]:
        p = f["properties"]
        n = canon(p["node"])
        if n in nbrs and n not in anchors:
            (x, y), = utm_xy([f["geometry"]["coordinates"]])
            anchors[n] = (x, y, p["role"], p.get("_addr", ""))
    for n, (x, y, addr) in relaxed_anchors(nbrs, anchors).items():
        anchors[n] = (x, y, "consumer", addr)
    for f in json.loads((RAW / "heat_sources.geojson").read_text())["features"]:
        if f["properties"]["name"] == "Сормовская ТЭЦ" and "Сормовская ТЭЦ" in nbrs:
            (x, y), = utm_xy([f["geometry"]["coordinates"]])
            anchors["Сормовская ТЭЦ"] = (x, y, "source", "ул. Коминтерна, 45")
    return anchors


STREET_WORDS = {"ул", "улица", "пр", "пр-т", "проспект", "пер", "переулок", "ш", "шоссе", "наб", "набережная", "б-р",
                "бульвар", "пл", "площадь", "проезд", "пр-д", "д", "кот", "котельная", "бмк", "бмку", "пос", "п", "мкр",
                "микрорайон", "с", "к", "кп", "г", "литер", "лит"}  # как в scripts/heat_tables_nn.py


def street_tokens(text):
    """Слова названия улицы без типа улицы и чисел: «Победы 40лет» → ['победы', 'лет']."""
    words = re.findall(r"[а-яё]+", text.lower().replace("ё", "е"))
    return [w for w in words if len(w) > 1 and w not in STREET_WORDS]


def relaxed_anchors(nbrs, anchors):
    """Потребители, которых Разведчик не привязал: адрес «ПТ-улица,дом» с числами в названии улицы и дробными
    номерами («Кораб,26/1» → Корабельная 26/1 или 26), а из нескольких подходящих зданий — ближайшее к привязанным
    узлам той же компоненты (не дальше 3 км). Ошибки отсеет та же проверка противоречий."""
    sys.path.insert(0, str(Path(__file__).parent))
    # компонента — по обходу графа, центр — медиана привязанных узлов
    seen, centre = {}, {}
    for start in sorted(nbrs):
        if start in seen:
            continue
        stack, members = [start], []
        seen[start] = start
        while stack:
            k = stack.pop()
            members.append(k)
            for o in nbrs[k]:
                if o not in seen:
                    seen[o] = start
                    stack.append(o)
        pts = [anchors[m][:2] for m in members if m in anchors]
        if pts:
            centre[start] = (statistics.median(p[0] for p in pts), statistics.median(p[1] for p in pts))
    idx = defaultdict(list)
    for _, tags, rings in load_layers()["buildings"]:
        if "addr:street" in tags and "addr:housenumber" in tags:
            h = re.sub(r"\s+", "", tags["addr:housenumber"]).upper()
            (x, y), = utm_xy([(sum(p[0] for p in rings[0]) / len(rings[0]), sum(p[1] for p in rings[0]) / len(rings[0]))])
            item = (street_tokens(tags["addr:street"]), x, y, f'{tags["addr:street"]}, {tags["addr:housenumber"]}')
            for hh in {h, re.match(r"\d*", h).group(), h.split("/")[0]}:
                if hh:
                    idx[hh].append(item)
    out = {}
    for n in sorted(nbrs):
        if n in anchors or not n.startswith("ПТ") or seen[n] not in centre:
            continue
        m = re.match(r"ПТ\s*-\s*(.*?[А-Яа-яё])\s*[,.\s]\s*(\d+(?:/\d+)?\s*[а-яА-Я]?)(?![\d])", n)
        if not m:
            continue
        toks, house = street_tokens(m.group(1))[-2:], re.sub(r"\s+", "", m.group(2)).upper()
        if not toks:
            continue
        cx, cy = centre[seen[n]]
        for hh in (house, house.split("/")[0], re.match(r"\d+", house).group()):
            cand = [c for c in idx.get(hh, []) if all(any(o.startswith(t[:5]) for o in c[0]) for t in toks)]
            if cand:
                c = min(cand, key=lambda c: math.hypot(c[1] - cx, c[2] - cy))
                if math.hypot(c[1] - cx, c[2] - cy) <= 3000.0:
                    out[n] = (c[1], c[2], c[3])
                break
    print(f"привязано дополнительно по ослабленному адресу: {len(out)}", file=sys.stderr)
    return out


CHECK_M = 600.0


def contradictions(nbrs, anchors):
    """Опоры, противоречащие соседям: прямое расстояние > 1,5 × длина по графу + 50 м у большинства пар в 600 м
    по графу (проверка Разведчика; это ошибки адреса)."""
    score = defaultdict(lambda: [0, 0])
    for a in sorted(anchors):
        for b, length in heat_ball(nbrs, a, CHECK_M).items():
            if b in anchors and a < b:
                bad = math.dist(anchors[a][:2], anchors[b][:2]) > 1.5 * length + 50.0
                score[a][bad] += 1
                score[b][bad] += 1
    return {a for a, (good, bad) in score.items() if bad > good}


def chains_of(nbrs, key):
    """Цепочки узлов степени 2 между ключевыми узлами: [(узлы, длины участков)]."""
    out, seen = [], set()
    for k in sorted(key):
        for nb in sorted(nbrs[k]):
            if (k, nb) in seen:
                continue
            path, lens, prev, cur = [k, nb], [nbrs[k][nb]], k, nb
            while cur not in key:
                nxt = next(x for x in nbrs[cur] if x != prev)
                lens.append(nbrs[cur][nxt])
                prev, cur = cur, nxt
                path.append(cur)
            seen.add((k, nb))
            seen.add((cur, prev))
            out.append((path, lens))
    return out


# ---------------------------------------------------------------- укладка

SIGMA0, SIGMA1 = 20.0, 0.25     # допуск согласия «расстояние по улицам ≈ длина цепочки»: 20 м + 25 % длины
REACH = 1.6                     # дальше 1,6 × длина + 60 м по улицам от уложенного соседа узел не ищется


class Placed:
    """Уложенный узел: старты поиска по графу [(узел, добавка)], точка, контур здания (у опор), невязка, способ."""
    __slots__ = ("starts", "xy", "poly", "fit", "how")

    def __init__(self, starts, xy, poly, fit, how):
        self.starts, self.xy, self.poly, self.fit, self.how = starts, xy, poly, fit, how


HOP_M = 1500.0      # ограничение по узлам через цепочки: до 1,5 км по графу сети
HOP_RHO = 0.9       # путь по дереву сети через несколько узлов длиннее пути по улицам: ожидается 0,9 × длина
HOP_SIGMA1 = 0.4    # и допуск шире: 20 м + 40 % длины


def trilaterate(streets, cons):
    """Узел графа улиц, где расстояния по улицам до уложенных узлов согласуются с ожидаемыми:
    cons [(Placed, ожидаемое расстояние, допуск)], min Σ ((d − ожидаемое) / допуск)², при равенстве — ближе ко всем.
    Возвращает (узел, невязка RMS в долях допуска) или None."""
    cons = sorted(cons, key=lambda c: c[1])
    balls = [(streets.ball(p.starts, REACH * e + 60.0), e, sg, REACH * e + 60.0) for p, e, sg in cons]
    best = None
    for s in balls[0][0]:
        err = near = 0.0
        for dist, e, sg, R in balls:
            d = dist.get(s)
            if d is None:
                d = R * 1.25
            err += ((d - e) / sg) ** 2
            near += d / (e + 30.0)
        key = (round(err, 6), near, s)
        if best is None or key < best:
            best = key
    return None if best is None else (best[2], math.sqrt(best[0] / len(balls)))


def direct(kn, placed, n):
    return [(placed[o], L, SIGMA0 + SIGMA1 * L) for o, L in kn[n] if o in placed]


def multi_hop(kn, placed, n):
    """Ограничения через цепочки неуложенных узлов: ближайшие уложенные узлы по каждому направлению от n
    в HOP_M по графу сети → ([(Placed, 0,9 × длина, допуск)], число направлений)."""
    dist, heap, found = {n: 0.0}, [(0.0, n, None)], {}
    while heap:
        d, k, first = heapq.heappop(heap)
        if d > dist.get(k, math.inf):
            continue
        if k != n and k in placed:
            found.setdefault(first, []).append((d, k))
            continue
        for o, L in kn[k]:
            nd = d + L
            if nd <= HOP_M and nd < dist.get(o, math.inf):
                dist[o] = nd
                heapq.heappush(heap, (nd, o, first if first is not None else o))
    cons = [(placed[k], HOP_RHO * d if d > L0 else d, SIGMA0 + (HOP_SIGMA1 if d > L0 else SIGMA1) * d)
            for first, hits in found.items() for L0 in [dict(kn[n]).get(first, -1)]
            for d, k in sorted(hits)[:2]]
    return cons, len(found)


def place(only=""):
    t0 = __import__("time").time()
    layers = load_layers()
    streets = Streets(layers["highways"])
    print(f"улицы: узлов {len(streets.xy)}, отрезков {len(streets.seg)}, осей проезжей части {len(streets.axes)}; "
          f"{__import__('time').time() - t0:.0f} с", file=sys.stderr)
    rows, pairs, nbrs, comp, canon = heat_graph()
    if only:  # отладка: только компоненты из списка «2,3»
        keep = {int(c) for c in only.split(",")}
        nbrs = {n: v for n, v in nbrs.items() if comp[n] in keep}
        rows = [r for r in rows if canon(r["node_from"]) in nbrs]
        pairs = {k: v for k, v in pairs.items() if k[0] in nbrs}
    anchors = load_anchors(nbrs, canon)
    bad = contradictions(nbrs, anchors)
    names = sorted(a for a in anchors if a not in bad)
    buildings = buildings_utm(layers)
    streets.btree = STRtree(buildings)
    polys = {}
    for a in names:
        pt = Point(anchors[a][:2])
        hit = [buildings[i] for i in streets.btree.query(pt, predicate="dwithin", distance=5.0)]
        polys[a] = min(hit, key=lambda g: (g.distance(pt), g.area)) if hit else pt.buffer(0.5)
    streets.add_yards(buildings, list({id(g): g for g in polys.values()}.values()))
    placed, far = {}, 0
    for a in names:
        starts = streets.entries(polys[a])
        if len(nbrs[a]) != 1 and starts:  # узел с несколькими цепочками — одна точка ввода
            starts = [min(starts, key=lambda kd: (kd[1], kd[0]))]
        if not starts:
            far += 1
            continue
        placed[a] = Placed(starts, anchors[a][:2], polys[a], 0.0, "anchor")
    print(f"опор {len(anchors)}, противоречащих соседям {len(bad)}, дальше {SNAP_M:.0f} м от проездов {far}; "
          f"уложено опор {len(placed)}; {__import__('time').time() - t0:.0f} с", file=sys.stderr)

    key = {n for n in nbrs if len(nbrs[n]) != 2} | set(placed)
    chains = chains_of(nbrs, key)
    kn = defaultdict(list)  # ключевой узел → [(сосед, длина цепочки)]
    for i, (path, lens) in enumerate(chains):
        if path[0] != path[-1]:
            kn[path[0]].append((path[-1], sum(lens)))
            kn[path[-1]].append((path[0], sum(lens)))
    # постановка ключевых узлов без адреса: сначала те, у кого больше уложенных соседей и короче цепочки;
    # когда таких нет — узел с ограничениями через цепочки неуложенных узлов не меньше чем с двух направлений
    heap, order = [], []

    def push(n):
        if n in placed:
            return
        known = [(o, L) for o, L in kn[n] if o in placed]
        if len(known) >= 2:
            heapq.heappush(heap, (-len(known), sum(L for _, L in known), n))

    def settle(n, cons, how):
        hit = trilaterate(streets, cons)
        if hit is None:
            return False
        placed[n] = Placed([(hit[0], 0.0)], streets.xy[hit[0]], None, hit[1], how)
        order.append(n)
        for o, _ in kn[n]:
            push(o)
        if len(order) % 2000 == 0:
            print(f"  поставлено {len(order)}, {__import__('time').time() - t0:.0f} с", file=sys.stderr)
        return True

    def strict():
        while heap:
            cnt, _, n = heapq.heappop(heap)
            if n in placed:
                continue
            cons = direct(kn, placed, n)
            if len(cons) == -cnt:  # иначе запись устарела: соседей уже больше, новая запись в куче
                settle(n, cons, "trilateration")

    for n in sorted(kn):
        push(n)
    strict()
    while True:
        waiting = []
        for n in sorted(kn):
            if n not in placed:
                cons, dirs = multi_hop(kn, placed, n)
                if dirs >= 2:
                    waiting.append((-dirs, sum(e for _, e, _ in cons), n))
        progress = False
        for _, _, n in sorted(waiting):
            if n in placed:
                continue
            cons, dirs = multi_hop(kn, placed, n)
            if dirs >= 2 and settle(n, cons, "multi_hop"):
                progress = True
                strict()
        if not progress:
            break
    # уточнение: каждый поставленный узел — по всем уложенным соседям (в т. ч. поставленным позже)
    moved = 0
    for n in order:
        cons = direct(kn, placed, n)
        if len(cons) < 2:
            continue
        hit = trilaterate(streets, cons)
        if hit and hit[0] != placed[n].starts[0][0]:
            moved += 1
            placed[n] = Placed([(hit[0], 0.0)], streets.xy[hit[0]], None, hit[1], placed[n].how)
        elif hit:
            placed[n].fit = hit[1]
    print(f"поставлено узлов без адреса {len(order)}, из них через цепочки {sum(placed[n].how == 'multi_hop' for n in order)}"
          f" (уточнением сдвинуто {moved}); {__import__('time').time() - t0:.0f} с", file=sys.stderr)
    return streets, rows, pairs, nbrs, comp, anchors, bad, placed, chains, canon


SIMPLIFY_M = 4.0


def wall_point(poly, xy):
    p = shapely.ops.nearest_points(poly.boundary, Point(xy))[0]
    return (p.x, p.y)


def crosses(streets, line, own):
    """Линия заходит в здание (кроме зданий own, у которых она начинается или кончается) больше чем на 0,5 м."""
    for i in streets.btree.query(line, predicate="intersects"):
        g = streets.btree.geometries[i]
        if not any(g is o for o in own) and line.intersection(g).length > 0.5:
            return True
    return False


PULL_M = 60.0


def pull(streets, pts, own):
    """Спрямление ступенек решётки: из каждой точки — прямо в самую дальнюю точку пути не дальше PULL_M,
    если отрезок не заходит в здания."""
    out, i = [pts[0]], 0
    while i < len(pts) - 1:
        j = i + 1
        for k in range(len(pts) - 1, i + 1, -1):
            if math.dist(pts[i], pts[k]) <= PULL_M and not crosses(streets, LineString([pts[i], pts[k]]), own):
                j = k
                break
        out.append(pts[j])
        i = j
    return out


def lay(streets, a, b, total):
    """Трасса цепочки: от стены здания a по улицам и дворам до стены здания b (у узлов без здания — от узла графа);
    без пути по графу — прямая, если она не длиннее 1,3 × длина + 30 м. (координаты, способ) или (None, причина)."""
    nodes = streets.path(a.starts, b.starts, max(3.0 * total, total + 400.0))
    if nodes is None:
        if math.dist(a.xy, b.xy) <= 1.3 * total + 30.0:
            return [a.xy, b.xy], "straight"
        return None, "no_route"
    coords = [streets.xy[k] for k in nodes]
    if a.poly is not None:
        coords.insert(0, wall_point(a.poly, coords[0]))
    if b.poly is not None:
        coords.append(wall_point(b.poly, coords[-1]))
    out = [coords[0]]
    for c in coords[1:]:
        if math.dist(c, out[-1]) > 0.05:
            out.append(c)
    if len(out) < 2:
        return None, "collapsed"
    out = pull(streets, out, [g for g in (a.poly, b.poly) if g is not None])
    return out, "street"


def buildings_utm(layers):
    out = []
    for _, tags, rings in layers["buildings"]:
        for ring in rings:
            g = shapely.make_valid(shapely.Polygon(utm_xy(ring)))
            out += [p for p in getattr(g, "geoms", [g]) if p.geom_type == "Polygon" and p.area > 1.0]
    return out


def axis_share(streets, lines, step=5.0, near=30.0):
    """Доля длины линий в near м от оси проезжей части (по точкам через step м)."""
    tree = STRtree(streets.axes)
    pts = [ln.interpolate(d) for ln in lines for d in np.arange(step / 2, ln.length, step)]
    if not pts:
        return 0.0
    hit, _ = tree.query_nearest(pts, max_distance=near, return_distance=True, all_matches=False)
    return len(set(hit[0].tolist())) / len(pts)


NODE_CLASS = (("ТК", "heat_chamber"), ("ПТ", "consumer"), ("ЦТП", "heat_substation"))


def embed(only=""):
    streets, rows, pairs, nbrs, comp, anchors, bad, placed, chains, canon = place(only)
    pos = {n: p.xy for n, p in placed.items()}
    geom_of, how_of, ratio_of, fit_of, reason = {}, {}, {}, {}, {}
    for path, lens in chains:
        a, b, total = path[0], path[-1], sum(lens)
        pk = [tuple(sorted(e)) for e in zip(path, path[1:])]
        if a == b or a not in placed or b not in placed:
            why = "loop" if a == b else "end_unplaced"
            for e in pk:
                reason[e] = why
            continue
        coords, how = lay(streets, placed[a], placed[b], total)
        if coords is None:
            for e in pk:
                reason[e] = how
            continue
        line = LineString(coords)
        cum = np.concatenate([[0.0], np.cumsum(lens)])
        at = cum / total * line.length if total > 0 else np.linspace(0, line.length, len(path))
        ratio = line.length / total if total > 0 else None
        for j, e in enumerate(pk):
            g = substring(line, float(at[j]), float(at[j + 1]))
            if g.geom_type != "LineString" or g.length < 0.05:
                p = line.interpolate(float(at[j]))
                g = LineString([(p.x, p.y), (p.x + 0.05, p.y)])  # участок нулевой длины по таблице
            geom_of[e] = (path[j], g)
            how_of[e], ratio_of[e] = how, ratio
            fit_of[e] = max(placed[a].fit, placed[b].fit)
        pos[a], pos[b] = coords[0], coords[-1]
        for j, n in enumerate(path[1:-1], 1):
            p = line.interpolate(float(at[j]))
            pos[n] = (p.x, p.y)
    feats, unlaid = [], []
    for e, rs in sorted(pairs.items()):
        for r in rs:
            a_, b_ = canon(r["node_from"]), canon(r["node_to"])
            base = {"diameter": int(float(r["diameter"])), "label_length_m": float(r["length_m"]),
                    "d_label_mm": float(r["d_label_mm"]), "kind": r["kind"], "_pipe": pipe_of(r["kind"]),
                    "district": r["district"], "_from": r["node_from"], "_to": r["node_to"],
                    "_component": comp[r["node_from"]], "_page": int(r["page"])}
            if e not in geom_of:
                unlaid.append(dict(base, reason=reason.get(e, "no_chain")))
                continue
            start, g = geom_of[e]
            xy = list(g.coords) if start == a_ else list(g.coords)[::-1]
            props = {"_source": "restored", "_ref": REF, "_class": "heat_network", **base,
                     "_len_ratio": None if ratio_of[e] is None else round(ratio_of[e], 3),
                     "_route": how_of[e], "_fit": round(fit_of[e], 2)}
            feats.append({"type": "Feature", "geometry": {"type": "LineString", "coordinates": wgs_line(xy)},
                          "properties": props})
    for n, xy in sorted(pos.items()):
        role = anchors[n][2] if n in anchors else None
        cls = "source" if role == "source" else next((c for pre, c in NODE_CLASS if n.startswith(pre)), None)
        if cls is None:
            continue
        props = {"_source": "restored", "_ref": REF, "_class": cls, "name": n, "_component": comp[n],
                 "_placed": "address" if n in anchors and n not in bad else placed[n].how if n in placed else "chain"}
        if n in anchors:
            props["_addr"] = anchors[n][3]
        feats.append({"type": "Feature", "geometry": {"type": "Point", "coordinates": wgs_point(*xy)},
                      "properties": props})
    out = RAW / "restored_heat.geojson" if not only else CACHE / f"restored_heat_{only.replace(',', '_')}.geojson"
    with open(out, "w") as fh:
        fh.write('{"type":"FeatureCollection","features":[\n')
        fh.write(",\n".join(json.dumps(f, ensure_ascii=False, separators=(",", ":")) for f in feats))
        fh.write("\n]}\n")
    cols = ["_from", "_to", "diameter", "label_length_m", "d_label_mm", "kind", "_pipe", "district", "_component",
            "_page", "reason"]
    with gzip.open(RAW / "restored_unlaid.csv.gz" if not only else CACHE / "restored_unlaid_test.csv.gz", "wt",
                   newline="") as fh:
        w = csv.DictWriter(fh, cols)
        w.writeheader()
        w.writerows(unlaid)
    report(streets, rows, feats, unlaid, geom_of, bad, anchors, placed, only)


def report(streets, rows, feats, unlaid, geom_of, bad, anchors, placed, only):
    lines = [f for f in feats if f["properties"]["_class"] == "heat_network"]
    total = sum(float(r["length_m"]) for r in rows)
    laid = sum(f["properties"]["label_length_m"] for f in lines)
    ratios = sorted(f["properties"]["_len_ratio"] for f in lines if f["properties"]["_len_ratio"] is not None)
    q = lambda p: ratios[min(len(ratios) - 1, int(p * len(ratios)))] if ratios else None
    good = sum(abs(x - 1) < 0.25 for x in ratios) / max(1, len(ratios))
    geoms = [g for _, g in geom_of.values()]
    by_comp = defaultdict(lambda: [0.0, 0.0])
    for r in rows:
        by_comp[None][0] += 0
    for f in lines:
        by_comp[f["properties"]["_component"]][0] += f["properties"]["label_length_m"]
    for u in unlaid:
        by_comp[u["_component"]][1] += u["label_length_m"]
    reasons = defaultdict(float)
    for u in unlaid:
        reasons[u["reason"]] += u["label_length_m"] / 1000
    rep = {
        "sections": len(rows), "sections_laid": len(lines), "km_table": round(total / 1000, 1),
        "km_laid": round(laid / 1000, 1), "share_laid": round(laid / total, 4),
        "len_ratio_median": q(0.5), "len_ratio_iqr": [q(0.25), q(0.75)], "share_ratio_within_25pct": round(good, 4),
        "share_ratio_within_25pct_by_length": round(sum(f["properties"]["label_length_m"] for f in lines
            if f["properties"]["_len_ratio"] is not None and abs(f["properties"]["_len_ratio"] - 1) < 0.25) / max(laid, 1), 4),
        "share_ratio_within_25pct_sections_ge_10m": round(
            sum(abs(f["properties"]["_len_ratio"] - 1) < 0.25 for f in lines if f["properties"]["_len_ratio"] is not None
                and f["properties"]["label_length_m"] >= 10) / max(1, sum(1 for f in lines if f["properties"]["_len_ratio"]
                is not None and f["properties"]["label_length_m"] >= 10)), 4),
        "share_within_30m_of_axis": round(axis_share(streets, geoms), 4),
        "unlaid_km_by_reason": {k: round(v, 1) for k, v in sorted(reasons.items())},
        "anchors": len(anchors), "anchors_contradicting": len(bad),
        "nodes_trilaterated": sum(p.how == "trilateration" for p in placed.values()),
        "nodes_multi_hop": sum(p.how == "multi_hop" for p in placed.values()),
        "fit_median": statistics.median([p.fit for p in placed.values() if p.how != "anchor"] or [0]),
        "components_laid_share": {c: round(v[0] / (v[0] + v[1]), 3) for c, v in sorted(by_comp.items(),
                                  key=lambda kv: -(kv[1][0] + kv[1][1]))[:12] if c is not None and v[0] + v[1] > 0},
    }
    path = CACHE / ("embed_report.json" if not only else "embed_report_test.json")
    path.write_text(json.dumps(rep, ensure_ascii=False, indent=1))
    print(json.dumps(rep, ensure_ascii=False, indent=1), file=sys.stderr)


def teploseti_district(system):
    """ТСР ООО «Теплосети» → район: ТСР Ленинский (Ржавка, Героя Попова, пр. Ленина, Ленинская магистраль) —
    Ленинский район, остальные (Заводской, Соцгородской, Юго-Западный, Северный — кварталы Соцгорода и ул. Газовской,
    ГР ГАЗ, бесхозяйные Автозаводского) — Автозаводский (названия трубопроводов, Гл. 1 Прил. 2 Ч. 5)."""
    return "Ленинский" if "Ленинск" in system else "Автозаводский"


def avtozavod():
    """Сеть ООО «Теплосети» (Автозаводская ТЭЦ, узлов нет): трасса = длина подающих труб (п), по районам —
    км отопления и ГВС, Ду по длине → raw/nnovgorod/teploseti_stats.json (для достройки на этапе 2 и сверки)."""
    rows = [r for r in csv.DictReader(gzip.open(RAW / "scheme_sections.csv.gz", "rt")) if r["part"] == "5"]
    out = {}
    for r in rows:
        if r["pipe"] != "п":
            continue
        purpose = "heating" if "отоплен" in r["purpose"] else "hot_water" if "ГВС" in r["purpose"] else "other"
        d = out.setdefault(teploseti_district(r["system"]), {"heating": {}, "hot_water": {}, "other": {}})
        dn = d[purpose].setdefault(str(int(float(r["diameter"]))), 0.0)
        d[purpose][str(int(float(r["diameter"])))] = dn + float(r["length_m"])
    rep = {}
    for dist, v in out.items():
        rep[dist] = {}
        for purpose, by_dn in v.items():
            km = sum(by_dn.values()) / 1000
            if not km:
                continue
            mean = sum(int(k) * m for k, m in by_dn.items()) / sum(by_dn.values())
            rep[dist][purpose] = {"km_route": round(km, 1), "dn_mean_by_length": round(mean),
                                  "km_by_dn": {k: round(m / 1000, 2) for k, m in sorted(by_dn.items(), key=lambda kv: int(kv[0]))}}
    rep["_ref"] = REF.replace("Ч. 1–4 (таблицы участков электронной модели АО «Теплоэнерго»)",
                              "Ч. 5 (ООО «Теплосети», трубопроводы Автозаводской ТЭЦ); трасса — подающие трубы")
    (RAW / "teploseti_stats.json").write_text(json.dumps(rep, ensure_ascii=False, indent=1))
    print(json.dumps({k: {p: v[p]["km_route"] for p in v} for k, v in rep.items() if k != "_ref"}, ensure_ascii=False))


# ---------------------------------------------------------------- сеть для входа сервиса (этап 2)

HOLE_M = 150.0              # здание дальше 150 м от сети — «дыра», к нему достраивается ветка (PLAN.md)
AVTO_ORGS = ("ООО «Автозаводская ТЭЦ»", "ООО «Генерация тепла»")
AVTO_MIN_GCAL = 50.0        # корни достроенной сети «Теплосетей»: Автозаводская ТЭЦ, «Ленинская», «Северная»
AVTO_DISTRICTS = ("Автозаводский", "Ленинский")
SOURCE_SNAP_M = 300.0       # источник → узел схемы с тем же адресом не дальше 300 м
RING_GAP_M = 1.5            # разомкнутое кольцо не доходит 1,5 м до камеры (как в scripts/city_geojson.py)
FOREST_LIMIT_M = 4000.0
COVER_M = 150.0             # здание в 150 м от уже проложенной ветки своей ветки не получает: нагрузка — на ближайший участок


def city_sources(region):
    """Источники из heat_sources.geojson (Гл. 4 схемы) с точкой внутри восьми районов: [(id, Point, свойства)]."""
    out = []
    for i, f in enumerate(json.loads((RAW / "heat_sources.geojson").read_text())["features"], 1):
        (x, y), = utm_xy([f["geometry"]["coordinates"]])
        if region.contains(Point(x, y)):
            out.append((f"src-{i}", Point(x, y), f["properties"]))
    return out


def restored_graph():
    """Трубы отопления из restored_heat.geojson: узлы по именам схемы (ЦТП/ЦТПО/ЦТПГ — один узел), по одной трубе
    на пару узлов (параллельные трубы ГВС того же канала — не теплосеть отопления), концы — в точке узла."""
    feats = json.loads((RAW / "restored_heat.geojson").read_text())["features"]
    lines = [f["properties"] | {"_xy": f["geometry"]["coordinates"]} for f in feats
             if f["properties"]["_class"] == "heat_network" and f["properties"]["_pipe"] == "heating"]
    alias = canon_names({p["_from"] for p in lines} | {p["_to"] for p in lines})
    canon = lambda n: alias.get(n, n)
    best = {}
    for p in lines:
        a, b = canon(p["_from"]), canon(p["_to"])
        if a != b and (tuple(sorted((a, b))) not in best or best[tuple(sorted((a, b)))][0]["diameter"] < p["diameter"]):
            best[tuple(sorted((a, b)))] = (p, a, b)
    pos, edges = {}, []
    for key in sorted(best):
        p, a, b = best[key]
        xy = utm_xy(p["_xy"])
        pos.setdefault(a, xy[0])
        pos.setdefault(b, xy[-1])
        xy[0], xy[-1] = pos[a], pos[b]  # концы разных труб одного узла — в одной точке (сдвиг до ширины ввода)
        if len(xy) == 2 and math.dist(*xy) < 0.05:
            xy[1] = (xy[1][0] + 0.05, xy[1][1])
        edges.append(dict(u=a, v=b, coords=xy, dn=p["diameter"], source="restored", name=f"{p['_from']} – {p['_to']}",
                          scheme=p["_component"]))
    points = [f for f in feats if f["geometry"]["type"] == "Point"]
    chambers = {canon(f["properties"]["name"]) for f in points if f["properties"]["_class"] == "heat_chamber"}
    src_nodes = {canon(f["properties"]["name"]): utm_xy([f["geometry"]["coordinates"]])[0] for f in points
                 if f["properties"]["_class"] == "source"}
    return pos, edges, chambers, src_nodes


def forest(streets, seeds, targets, limit=FOREST_LIMIT_M):
    """Кратчайшие пути по улицам и дворам (двор дороже, YARD_COST) от ближайшего семени до каждого здания-цели:
    seeds {узел улиц: метка}, targets [(id, контур)]. Возвращает рёбра леса [(узел, узел)], концы веток
    {id здания: (узел ввода, точка у стены)} и метку семени каждого узла."""
    pred, dist, best = {}, {}, {}
    heap = [(0.0, k, -1) for k in sorted(seeds)]
    ns = streets.n_street
    adj = streets.adj
    while heap:
        d, k, p = heapq.heappop(heap)
        if k in dist:
            continue
        dist[k], pred[k] = d, p
        for nb, w in adj[k]:
            nd = d + (w * YARD_COST if k >= ns or nb >= ns else w)
            if nd <= limit and nd < best.get(nb, math.inf) and nb not in dist:
                best[nb] = nd
                heapq.heappush(heap, (nd, nb, k))
    edges, ends = set(), {}
    covered = set()
    cells = defaultdict(list)  # клетка COVER_M → точки уже проложенных веток
    todo = []
    for bid, poly in targets:
        starts = [(k, off) for k, off in streets.entries(poly) if k in dist]
        if starts:
            k = min(starts, key=lambda ko: (dist[ko[0]] + ko[1], ko[0]))[0]
            todo.append((dist[k], bid, poly, k))
    for _, bid, poly, k in sorted(todo, key=lambda t: (t[0], t[1])):
        x0, y0, x1, y1 = poly.bounds
        near = [p for i in range(int(x0 // COVER_M) - 1, int(x1 // COVER_M) + 2)
                for j in range(int(y0 // COVER_M) - 1, int(y1 // COVER_M) + 2) for p in cells.get((i, j), [])]
        if near and shapely.distance(poly, shapely.points(near)).min() <= COVER_M:
            covered.add(bid)
            continue  # ветка к соседнему зданию уже проходит ближе COVER_M: здание — на ней (нагрузка на ближайший участок)
        ends[bid] = (k, wall_point(poly, streets.xy[k]))
        while True:
            x, y = streets.xy[k]
            cells[(int(x // COVER_M), int(y // COVER_M))].append((x, y))
            if pred[k] == -1 or (pred[k], k) in edges:
                break
            edges.add((pred[k], k))
            k = pred[k]
    return sorted(edges), ends, covered


def nn_network(heated, obstacles, region, district_of, one_source=False, log=print, detour_fn=None):
    """Существующая сеть города для входа: восстановленная сеть Теплоэнерго (restored), достроенная сеть «Теплосетей»
    в Автозаводском и Ленинском районах от Автозаводской ТЭЦ, «Ленинской» и «Северной» и ветки к зданиям дальше 150 м
    от сети (inferred). heated [(id, контур, расход т/ч)], obstacles — контуры всех зданий, district_of(точка) → район.
    Вариант (а) — все источники с точкой в городе `source`, корни своих компонент; (б) one_source — только
    Сормовская ТЭЦ. Возвращает участки и камеры в формате scripts/city_geojson.py (до finish_network), источники,
    источники вне сети (для prohibited_site в варианте б) и сводку."""
    t0 = __import__("time").time()
    stats = {}
    pos, edges, ch_names, src_nodes = restored_graph()
    stats["restored_km"] = round(sum(LineString(e["coords"]).length for e in edges) / 1000, 1)
    sources = city_sources(region)
    root_of = {}  # узел графа → id источника
    for sid, pt, props in sources:
        near = [(math.dist(xy, (pt.x, pt.y)), n) for n, xy in src_nodes.items() if n in pos]
        if props["name"] == "Сормовская ТЭЦ":
            near = [(0.0, "Сормовская ТЭЦ")] if "Сормовская ТЭЦ" in pos else near
        d, n = min(near, default=(math.inf, None))
        if n is not None and d <= SOURCE_SNAP_M and n not in root_of:
            root_of[n] = sid
    # источник без узла схемы по адресу — корень ближайшей компоненты без источника, если её узел не дальше 300 м
    comp, stack_ = {}, []
    adj_ = defaultdict(list)
    for e in edges:
        adj_[e["u"]].append(e["v"])
        adj_[e["v"]].append(e["u"])
    for n in sorted(adj_):
        if n in comp:
            continue
        comp[n], stack_ = n, [n]
        while stack_:
            k = stack_.pop()
            for o in adj_[k]:
                if o not in comp:
                    comp[o] = n
                    stack_.append(o)
    rooted = {comp[n] for n in root_of}
    node_names = sorted(adj_)
    node_tree = STRtree(shapely.points([pos[n] for n in node_names]))
    by_near = 0
    for sid, pt, props in sorted(sources, key=lambda s: -(s[2]["capacity_gcal_h"] or 0)):
        if sid in root_of.values():
            continue
        cand = sorted((pt.distance(Point(pos[node_names[i]])), node_names[i])
                      for i in node_tree.query(pt, predicate="dwithin", distance=SOURCE_SNAP_M))
        for d, n in cand:
            if comp[n] not in rooted:
                root_of[n] = sid
                rooted.add(comp[n])
                by_near += 1
                break
    stats["sources_by_address"], stats["sources_by_proximity"] = len(root_of) - by_near, by_near
    net_tree = STRtree([LineString(e["coords"]) for e in edges])
    avto = [s for s in sources if s[2]["org"] in AVTO_ORGS and (s[2]["capacity_gcal_h"] or 0) >= AVTO_MIN_GCAL]
    far = [(bid, g) for bid, g, _ in heated if not len(net_tree.query(g, predicate="dwithin", distance=HOLE_M))]
    avto_targets = [(b, g) for b, g in far if district_of(g.representative_point()) in AVTO_DISTRICTS]
    holes = [(b, g) for b, g in far if district_of(g.representative_point()) not in AVTO_DISTRICTS]
    stats.update(far_buildings=len(far), avto_targets=len(avto_targets), holes=len(holes))
    streets = Streets(load_layers()["highways"])
    streets.btree = STRtree(obstacles)
    streets.add_yards(obstacles, [g for _, g in far])
    log(f"сеть: графы готовы, {__import__('time').time() - t0:.0f} с")
    extra, tag = [], {}
    # «Теплосети»: лес от источников ЕТО Автозаводской ТЭЦ; остальные дыры — от ближайшего узла восстановленной сети
    snap = streets.nodes.query_nearest(shapely.points([(p.x, p.y) for _, p, _ in avto]), all_matches=False)[1]
    seeds = {int(k): ("src", sid, p) for k, (sid, p, _) in zip(snap, avto)}
    e1, ends1, cov1 = forest(streets, seeds, avto_targets, limit=20000.0)
    names = sorted(pos)
    snap = streets.nodes.query_nearest(shapely.points([pos[n] for n in names]), all_matches=False)[1]
    seeds2 = {k: ("tree", None, None) for e in e1 for k in e}  # вторые ветки примыкают и к лесу «Теплосетей»
    for k, n in zip(snap.tolist(), names):
        seeds2.setdefault(int(k), ("node", n, None))
    left = [(b, g) for b, g in avto_targets if b not in ends1 and b not in cov1]
    e2, ends2, cov2 = forest(streets, seeds2, holes + left)
    stats["covered_by_branch"] = len(cov1) + len(cov2)
    stats["avto_reached"], stats["holes_reached"] = len(ends1), len(ends2)
    for es, ends, seed, level in ((e1, ends1, seeds, "avto"), (e2, ends2, seeds2, "hole")):
        used = {k for e in es for k in e} | {k for k, _ in ends.values()}
        for a, b in es:
            extra.append(dict(u=("s", a), v=("s", b), coords=[streets.xy[a], streets.xy[b]], dn=None,
                              source="inferred", name=level))
        for bid, (k, wall) in ends.items():
            if math.dist(streets.xy[k], wall) > 0.05:
                extra.append(dict(u=("s", k), v=("b", bid), coords=[streets.xy[k], wall], dn=None, source="inferred",
                                  name=level))
            else:
                tag[("s", k)] = bid
        for k, (kind, ref, p) in seed.items():
            if k not in used or kind == "tree":
                continue
            at = (p.x, p.y) if kind == "src" else pos[ref]
            node = ("p", ref) if kind == "src" else ref
            extra.append(dict(u=node, v=("s", k), coords=[at, streets.xy[k]] if math.dist(at, streets.xy[k]) > 0.05
                              else [at, (at[0] + 0.05, at[1])], dn=None, source="inferred", name=level))
            if kind == "src":
                pos[node] = at
                root_of[node] = ref
    for e in extra:
        for key in ("u", "v"):
            if e[key] not in pos:
                pos[e[key]] = e["coords"][0] if key == "u" else e["coords"][-1]
    for _ in range(3):
        added = bridges(streets, pos, edges + extra, root_of)
        extra += added
        stats["bridges"] = stats.get("bridges", 0) + len(added)
        if not added:
            break
    edges_all, pos = merge_close(edges + extra, pos, root_of)
    if detour_fn is not None:
        stats["detoured"], stats["through_m"] = avoid_buildings(edges_all, obstacles, detour_fn)
    if one_source:
        root_of = {n: sid for n, sid in root_of.items() if n == "Сормовская ТЭЦ"}
    segments, chambers, stats["rings_opened"], stats["no_source_km"] = tree_segments(
        pos, edges_all, ch_names, root_of)
    # нагрузки зданий — на ближайший участок в 150 м
    seg_tree = STRtree([LineString(s["coords"]) for s in segments])
    on = 0
    for bid, g, flow in heated:
        k = seg_tree.query_nearest(g, max_distance=HOLE_M, all_matches=False)
        if len(k):
            segments[int(k[0])]["load"] += flow
            on += 1
    stats["buildings_on_network"] = on
    inferred_km = defaultdict(float)
    for e in extra:
        d = district_of(Point(e["coords"][0])) or "?"
        inferred_km[(e["name"], d)] += LineString(e["coords"]).length / 1000
    stats["inferred_km"] = {f"{k[0]}/{k[1]}": round(v, 1) for k, v in sorted(inferred_km.items())}
    src_at = {sid: pos[n] for n, sid in root_of.items()}
    log(f"сеть: участков {len(segments)}, камер {len(chambers)}; {__import__('time').time() - t0:.0f} с")
    return segments, chambers, sources, src_at, stats


NO_SOURCE = defaultdict(float)
MERGE_M = 0.3


def merge_close(edges, pos, root_of):
    """Узлы ближе MERGE_M — один узел (вводы разных узлов схемы в одну точку стены, участки нулевой длины):
    иначе в одной точке сходятся концы трёх участков без камеры. Рёбра внутри одного узла отбрасываются."""
    names = sorted({e[k] for e in edges for k in ("u", "v")}, key=str)
    pts = shapely.points([pos[n] for n in names])
    tree = STRtree(pts)
    par = list(range(len(names)))

    def root(i):
        while par[i] != i:
            par[i] = par[par[i]]
            i = par[i]
        return i
    a, b = tree.query(pts, predicate="dwithin", distance=MERGE_M)
    for i, j in zip(a.tolist(), b.tolist()):
        if i != j:
            ri, rj = root(i), root(j)
            # корень-источник остаётся представителем группы
            if names[ri] in root_of:
                par[rj] = ri
            else:
                par[ri] = rj
    alias = {n: names[root(i)] for i, n in enumerate(names)}
    out, seen = [], {}
    for e in edges:
        u, v = alias[e["u"]], alias[e["v"]]
        if u == v:
            continue
        coords = list(e["coords"])
        coords[0], coords[-1] = pos[u], pos[v]
        pair = tuple(sorted((u, v), key=str))
        if pair in seen:  # две трубы между одними узлами после слияния — одна, с большим Ду
            k = seen[pair]
            if (e["dn"] or 0) > (out[k]["dn"] or 0):
                out[k] = dict(e, u=u, v=v, coords=coords)
            continue
        seen[pair] = len(out)
        out.append(dict(e, u=u, v=v, coords=coords))
    for n, r in alias.items():
        if n != r and n in root_of and r not in root_of:
            root_of[r] = root_of.pop(n)
    return out, pos


def avoid_buildings(edges, houses, detour_fn):
    """Участок, заходящий в здание дальше 0,01 м (не считая касания стены у ввода), обходит его (detour_fn из
    scripts/city_geojson.py, растр 1 м); обойти нельзя — остаётся, как есть, и помечается _through_building."""
    tree = STRtree(houses)
    moved, kept = 0, 0.0
    for e in edges:
        line = LineString(e["coords"])
        cut = sum(line.intersection(houses[i]).length for i in tree.query(line, predicate="intersects"))
        if cut <= 0.005:
            continue
        route = None
        if line.length > 2.0:  # концы у стены (ввод) отодвигаются на 0,6 м внутрь трассы: касание стены — не заход
            inner = list(substring(line, 0.6, line.length - 0.6).coords)
            route = detour_fn(inner, houses, tree)
            if route is not None:
                route = [e["coords"][0]] + route + [e["coords"][-1]]
        if route is None:
            e["through"] = True
            kept += cut
            continue
        e["coords"] = route
        moved += 1
    return moved, round(kept, 1)
BRIDGE_M = 600.0


def components(edges):
    adj = defaultdict(list)
    for e in edges:
        adj[e["u"]].append(e["v"])
        adj[e["v"]].append(e["u"])
    comp = {}
    for n in sorted(adj, key=str):
        if n in comp:
            continue
        comp[n], stack = n, [n]
        while stack:
            k = stack.pop()
            for o in adj[k]:
                if o not in comp:
                    comp[o] = n
                    stack.append(o)
    return comp


def bridges(streets, pos, edges, root_of):
    """Куски одной компоненты схемы, разорванные неуложенными участками: кусок без источника — перемычкой по улицам
    от ближайшего своего узла до куска той же компоненты схемы с источником (или до самого длинного куска, если
    источника у компоненты нет), не длиннее BRIDGE_M (inferred). Разные системы не связываются."""
    comp = components(edges)
    scheme, size = {}, defaultdict(float)
    for e in edges:
        if "scheme" in e:
            scheme.setdefault(e["u"], e["scheme"])
            scheme.setdefault(e["v"], e["scheme"])
            size[comp[e["u"]]] += LineString(e["coords"]).length
    main = {}
    for n, sc in scheme.items():
        c = comp[n]
        if n in root_of:
            main[sc] = (1, c)
        elif main.get(sc, (0, None))[0] == 0 and size[c] > size.get(main.get(sc, (0, None))[1], -1):
            main[sc] = (0, c)
    out = []
    ns, adj = streets.n_street, streets.adj
    groups = defaultdict(list)
    for n, sc in scheme.items():
        groups[sc].append(n)
    for sc, members in sorted(groups.items()):
        target = main[sc][1]
        if all(comp[n] == target for n in members):
            continue
        members.sort(key=str)
        snap = streets.nodes.query_nearest(shapely.points([pos[n] for n in members]), all_matches=False)[1].tolist()
        seeds = {}
        for n, k in zip(members, snap):
            if comp[n] == target:
                seeds.setdefault(k, n)
        pred, dist, best = {}, {}, {}
        heap = [(0.0, k, -1) for k in sorted(seeds)]
        while heap:
            d, k, p = heapq.heappop(heap)
            if k in dist:
                continue
            dist[k], pred[k] = d, p
            for nb, w in adj[k]:
                nd = d + (w * YARD_COST if k >= ns or nb >= ns else w)
                if nd <= BRIDGE_M and nd < best.get(nb, math.inf) and nb not in dist:
                    best[nb] = nd
                    heapq.heappush(heap, (nd, nb, k))
        pick = {}
        for n, k in zip(members, snap):
            c = comp[n]
            if c == target or k not in dist:
                continue
            cost = dist[k] + math.dist(pos[n], streets.xy[k])
            if c not in pick or cost < pick[c][0]:
                pick[c] = (cost, n, k)
        for c, (_, n, k) in sorted(pick.items(), key=lambda kv: str(kv[0])):
            path = [k]
            while pred[path[-1]] != -1:
                path.append(pred[path[-1]])
            other = seeds[path[-1]]
            coords = [pos[n]] + [streets.xy[x] for x in path] + [pos[other]]
            coords = [c0 for i, c0 in enumerate(coords) if i == 0 or math.dist(c0, coords[i - 1]) > 0.05]
            if len(coords) < 2:
                coords = [pos[n], (pos[n][0] + 0.05, pos[n][1])]
            out.append(dict(u=n, v=other, coords=coords, dn=None, source="inferred", name="bridge"))
    return out


def tree_segments(pos, edges, ch_names, root_of):
    """Дерево от корней-источников по длине (кратчайшие пути), рёбра вне дерева — разомкнутые кольца; компоненты без
    источника — от конца самого толстого участка, верхний участок без upstream_object_id. Цепочки узлов без ветвления
    сливаются в один участок; камера — узел схемы ТК или ветвление."""
    adj = defaultdict(list)
    for k, e in enumerate(edges):
        length = LineString(e["coords"]).length
        adj[e["u"]].append((length, k, e["v"]))
        adj[e["v"]].append((length, k, e["u"]))
    dist, par, top = {}, {}, {}
    key = lambda n: str(n)

    def grow(roots):
        heap = [(0.0, key(r), r, r, None, None) for r in roots]
        heapq.heapify(heap)
        while heap:
            d, _, n, r, k, p = heapq.heappop(heap)
            if n in dist:
                continue
            dist[n], top[n] = d, r
            if p is not None:
                par[n] = (k, p)
            for length, k2, m in adj[n]:
                if m not in dist:
                    heapq.heappush(heap, (d + length, key(m), m, r, k2, n))
    grow(sorted((n for n in root_of if n in adj), key=key))
    for k in sorted(range(len(edges)), key=lambda k: (-(edges[k]["dn"] or 0), k)):
        e = edges[k]
        if e["u"] not in dist:
            grow([e["u"] if len(adj[e["u"]]) <= len(adj[e["v"]]) else e["v"]])
    children = defaultdict(list)
    tree_edges = set()
    for n, (k, p) in par.items():
        children[p].append((k, n, None))
        tree_edges.add(k)
    rings = 0
    for k, e in enumerate(edges):
        if k in tree_edges:
            continue
        keep, cut = sorted((e["u"], e["v"]), key=lambda n: (n in ch_names, dist[n], key(n)))
        coords = e["coords"] if e["u"] == keep else e["coords"][::-1]
        line = LineString(coords)
        if line.length > 2 * RING_GAP_M:
            children[keep].append((k, None, list(substring(line, 0.0, line.length - RING_GAP_M).coords)))
            rings += 1
    for n in children:
        children[n].sort(key=lambda it: (it[0], key(it[1])))
    segments, chambers = [], []
    roots = [(r, root_of.get(r)) for r in sorted({top[n] for n in top}, key=key)]
    no_source = 0.0
    for r, sid in roots:
        if sid is None and len(children[r]) >= 2:  # корень сети без источника с ветвлением — камера без upstream
            chambers.append(dict(id=f"hc-{len(chambers) + 1}", pos=pos[r], up=None,
                                 source="restored" if r in ch_names else "inferred",
                                 osm=r if isinstance(r, str) else None, load=0.0, ctp=False))
            sid = chambers[-1]["id"]
        stack = [(r, sid)]
        while stack:
            n, up = stack.pop()
            for k, m, ring_coords in children[n]:
                e = edges[k]
                coords = ring_coords or (e["coords"] if e["u"] == n else e["coords"][::-1])
                coords, dn, src, through = list(coords), e["dn"], e["source"], e.get("through", False)
                while m is not None and m not in ch_names and len(children[m]) == 1 and \
                        edges[children[m][0][0]]["source"] == src and children[m][0][2] is None:
                    k2, m2, _ = children[m][0]
                    e2 = edges[k2]
                    more = e2["coords"] if e2["u"] == m else e2["coords"][::-1]
                    coords += list(more)[1:]
                    through |= e2.get("through", False)
                    dn = min(x for x in (dn, e2["dn"]) if x) if (dn or e2["dn"]) else None
                    m = m2
                seg = dict(id=f"hn-{len(segments) + 1}", coords=coords, up=up, load=0.0, label=None, source=src,
                           ring=ring_coords is not None, through=through)
                if dn:
                    seg["dn_hint"] = int(dn)
                if sid is None:
                    no_source += LineString(coords).length
                    NO_SOURCE[str(r)] += LineString(coords).length
                segments.append(seg)
                if m is None:
                    continue
                if m in ch_names or len(children[m]) >= 2:
                    chambers.append(dict(id=f"hc-{len(chambers) + 1}", pos=pos[m], up=seg["id"],
                                         source="restored" if m in ch_names else "inferred",
                                         osm=m if isinstance(m, str) else None, load=0.0, ctp=False))
                    seg["end"] = chambers[-1]["id"]
                    stack.append((m, chambers[-1]["id"]))
                else:
                    stack.append((m, seg["id"]))
    return segments, chambers, rings, round(no_source / 1000, 1)


if __name__ == "__main__":
    {"osm": osm, "embed": embed, "avtozavod": avtozavod}[sys.argv[1]](*sys.argv[2:])
