"""Классы неподключённых ОКС по геометрии зон (без правил поворотов и спецпереходов):
  inside     — точка в зоне отступа чужого здания/запретной территории: по п. 2.2 трассы нет;
  enclosed   — все выходы из своего здания ведут в карманы зон, не касающиеся сети в радиусе R: трассы нет;
  by_new     — enclosed только из-за соседних НОВЫХ ОКС (без них проход есть): группа новых домов запирает сама себя;
  reachable  — есть свободный проход к сети: причина не в плотности (поиск, предельная длина, повороты, врезка).
Формат входа любой: oks_future + точки или restriction/oks + точки с flow_tph. p2_classify.py вход criteria.json [R]"""
import json, sys
from collections import Counter
from shapely.geometry import shape, Point
from shapely.ops import transform, unary_union
from shapely.strtree import STRtree
from pyproj import Transformer

MAIN = "/Users/a1111/HACK/LCT/LCT/.claude/worktrees/geojson-heat-network-moscow-spb-ea030e"
src, crit = sys.argv[1], sys.argv[2]
R = float(sys.argv[3]) if len(sys.argv) > 3 else 300
T = Transformer.from_crs(4326, 32637, always_xy=True).transform
rules = json.load(open(MAIN + "/rules/rules.json"))
RR = rules["restrictions"]
FORBID = {k for k, v in RR.items() if not k.startswith("_") and v["rule"] == "forbid" and k != "oks_existing"}
G = [(f["properties"], transform(T, shape(f["geometry"]))) for f in json.load(open(src))["features"] if f["geometry"]]
fut = {p["id"]: g for p, g in G if p["object_type"] == "oks_future"}
flows = {p["id"]: p.get("flow_tph") for p, g in G if p["object_type"] == "oks_future"}
obst = []  # (geom, kind, is_new, id)
for p, g in G:
    t, r = p["object_type"], p.get("restriction_type")
    if t in ("oks_existing", "oks_future") or r == "oks":
        obst.append((g, "oks", t == "oks_future", p["id"]))
    elif t == "restriction" and r in FORBID:
        obst.append((g, r, False, p["id"]))
otree = STRtree([o[0] for o in obst])
net = [g for p, g in G if p["object_type"] in ("heat_network", "heat_chamber")]
ntree = STRtree(net)
cps = {}
for p, g in G:
    if p["object_type"] == "oks_connection_point":
        key = p.get("oks_id", p["id"])
        cps[str(key)] = (g, flows.get(key, p.get("flow_tph")))
c = json.load(open(crit))
reasons = c[0]["criteria"]["unconnected_reasons"]
new_ids = {o[3] for o in obst if o[2]}
count, rows = Counter(), []
for rsn in reasons:
    oid = str(rsn["oks_id"])
    oid = "oks-" + oid[3:] if oid.startswith("cp-") and "oks-" + oid[3:] in fut else oid
    cp, flow = cps[oid]
    d = next((d for d in rules["diameters"] if flow <= d["capacity_tph"]), rules["diameters"][-1])
    hw = d["width_m"] / 2
    zo = (5 if d["dn"] <= 400 else 7 if d["dn"] <= 800 else 9) + hw
    disk = cp.buffer(R)
    own = fut.get(oid) or next((o[0] for i in otree.query(cp) for o in [obst[i]] if o[1] == "oks" and o[0].covers(cp)), None)
    others, others_old = [], []
    for i in otree.query(disk):
        g, kind, is_new, bid = obst[i]
        if own is not None and g is own:
            continue
        only = __import__("os").environ.get("ONLY")
        if only and only != ("oks" if kind == "oks" else "area"):
            continue
        z = g.buffer(zo if kind == "oks" else RR[kind]["clearance_m"] + hw, quad_segs=4)
        others.append(z)
        if not is_new or bid == oid:
            others_old.append(z)
    Z = unary_union(others)
    if Z.contains(cp):
        cls = "inside"
    else:
        def reach(zones):
            free = disk.difference(zones)
            if own is not None:
                free = free.difference(own.buffer(zo, quad_segs=4))
                ring = own.buffer(zo + 0.4, quad_segs=4).exterior
                starts = [ring.interpolate(t, normalized=True) for t in [k / 200 for k in range(200)]]
            else:
                starts = [cp]
            for part in getattr(free, "geoms", [free]):
                if any(part.covers(s) for s in starts) and any(net[i].intersects(part) for i in ntree.query(part)):
                    return True
            return False
        cls = "reachable" if reach(Z) else ("by_new" if reach(unary_union(others_old)) else "enclosed")
    count[cls] += 1
    rows.append((oid, rsn["reason"], cls, d["dn"]))
print(src.split("/data/cities/")[-1], "неподключённых", len(reasons), dict(count))
if "--list" in sys.argv:
    for r in rows:
        print(" ", *r)
