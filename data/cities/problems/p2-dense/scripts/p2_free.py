"""Есть ли по правилам проход от точки ОКС к сети: свободное пространство = круг R минус зоны запрета (здания с отступом
5/7/9 м + полуширина пары, запретные территории с 1 м + полуширина). Дороги, линии и сеть пересекаемы. Связная часть
с точкой касается сети — трасса топологически есть; нет — нет при любом алгоритме.
Запуск: p2_free.py вход id[,id] [R] [--own]   --own: своё здание (oks_future) тоже препятствие, кроме финального луча."""
import json, sys
from shapely.geometry import shape, Point
from shapely.ops import transform, unary_union, nearest_points
from shapely.strtree import STRtree
from pyproj import Transformer

MAIN = "/Users/a1111/HACK/LCT/LCT/.claude/worktrees/geojson-heat-network-moscow-spb-ea030e"
src, ids = sys.argv[1], sys.argv[2].split(",")
R = float(sys.argv[3]) if len(sys.argv) > 3 and not sys.argv[3].startswith("--") else 300
T = Transformer.from_crs(4326, 32637, always_xy=True).transform
rules = json.load(open(MAIN + "/rules/rules.json"))
RR = rules["restrictions"]
fs = json.load(open(src))["features"]
G = [(f["properties"], transform(T, shape(f["geometry"]))) for f in fs if f["geometry"]]
FORBID = {k for k, v in RR.items() if not k.startswith("_") and v["rule"] == "forbid" and k != "oks_existing"}
obst = [(p, g) for p, g in G if p["object_type"] == "oks_existing" or
        (p["object_type"] == "restriction" and (p.get("restriction_type") in FORBID or p.get("restriction_type") == "oks"))]
otree = STRtree([g for _, g in obst])
net = [(p, g) for p, g in G if p["object_type"] in ("heat_network", "heat_chamber")]
ntree = STRtree([g for _, g in net])
fut = {p["id"]: (p, g) for p, g in G if p["object_type"] == "oks_future"}
cps = {p.get("oks_id", p["id"]): (p, g) for p, g in G if p["object_type"] == "oks_connection_point"}


def dn_for(flow):
    return next((d for d in rules["diameters"] if flow <= d["capacity_tph"]), rules["diameters"][-1])


for oid in ids:
    key = int(oid) if oid.isdigit() else oid
    cpp, cp = cps[key]
    flow = fut[key][0]["flow_tph"] if key in fut else cpp["flow_tph"]
    own = fut[key][1] if key in fut else next((g for _, g in obst if g.covers(cp)), None)
    d = dn_for(float(__import__("os").environ.get("FLOW", flow)))
    hw = d["width_m"] / 2
    zo = (5 if d["dn"] <= 400 else 7 if d["dn"] <= 800 else 9) + hw + float(__import__("os").environ.get("EXTRA", 0))
    disk = cp.buffer(R)
    zones, blockers = [], []
    for i in otree.query(disk):
        p, g = obst[i]
        if own is not None and (g is own or g.equals(own)):
            continue
        kind = p.get("restriction_type") if p["object_type"] == "restriction" else "oks_existing"
        kind = "oks_existing" if kind == "oks" else kind
        dist = zo if kind == "oks_existing" else RR[kind]["clearance_m"] + hw + (RR[kind].get("half_width_m") or 0) * (g.geom_type.endswith("LineString"))
        z = g.buffer(dist, quad_segs=8)
        zones.append(z)
        if z.contains(cp):
            blockers.append((kind, p.get("id"), round(g.distance(cp), 2), round(dist, 3)))
    Z = unary_union(zones)
    free = disk.difference(Z)
    start = cp
    note = ""
    if "--own" in sys.argv and own is not None:
        free = free.difference(own.buffer(zo))
        # выход финального луча: ближайшая точка контура, за зоной своего здания
        a = nearest_points(own.exterior, cp)[0]
        ux, uy = (a.x - cp.x) / cp.distance(a), (a.y - cp.y) / cp.distance(a)
        L = cp.distance(a) + zo + 0.35
        start = Point(cp.x + ux * L, cp.y + uy * L)
        note = f" exit_nearest_wall={cp.distance(a):.2f}"
    comp = None
    parts = getattr(free, "geoms", [free])
    for part in parts:
        if part.covers(start):
            comp = part
    print(f"\n=== {oid} flow={flow} DN{d['dn']} zone_oks={zo:.3f} R={R}{note}")
    if blockers:
        print("  точка внутри зон:", blockers)
    if comp is None:
        print("  старт в зоне запрета: свободной части нет")
        continue
    hits = [(net[i][0].get("id"), round(net[i][1].distance(cp), 1)) for i in ntree.query(comp) if net[i][1].intersects(comp)]
    near = sorted((round(net[i][1].distance(cp), 1), net[i][0].get("id")) for i in ntree.query(disk))[:4]
    print(f"  свободная часть со стартом: площадь {comp.area:.0f} м², рамка {[round(v) for v in comp.bounds]}")
    print(f"  сеть в этой части: {sorted(hits, key=lambda h: h[1])[:6] or 'НЕТ'}")
    print(f"  ближайшая сеть вообще: {near}")
