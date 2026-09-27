"""Вход с перенесёнными точками подключения: точка, лежащая в зоне отступа чужого здания или запретной территории,
переносится в ближайшую точку внутреннего контура (1 м от стены), которая дальше зон на 0,3 м, — как поставил бы
точку генератор с учётом отступов. Запуск: p2_cpfix.py вход выход"""
import json, sys
from shapely.geometry import shape, Point
from shapely.ops import transform, unary_union, nearest_points
from shapely.strtree import STRtree
from pyproj import Transformer

MAIN = "/Users/a1111/HACK/LCT/LCT/.claude/worktrees/geojson-heat-network-moscow-spb-ea030e"
src, dst = sys.argv[1], sys.argv[2]
T = Transformer.from_crs(4326, 32637, always_xy=True).transform
BACK = Transformer.from_crs(32637, 4326, always_xy=True).transform
rules = json.load(open(MAIN + "/rules/rules.json"))
RR = rules["restrictions"]
doc = json.load(open(src))
fs = doc["features"]
FORBID = {k for k, v in RR.items() if not k.startswith("_") and v["rule"] == "forbid" and k != "oks_existing"}
obst = [(f["properties"], transform(T, shape(f["geometry"]))) for f in fs if f["properties"]["object_type"] in ("oks_existing", "oks_future")
        or (f["properties"]["object_type"] == "restriction" and f["properties"].get("restriction_type") in FORBID)]
tree = STRtree([g for _, g in obst])
fut = {p["id"]: (p, g) for p, g in obst if p["object_type"] == "oks_future"}
moved = 0
for f in fs:
    p = f["properties"]
    if p["object_type"] != "oks_connection_point":
        continue
    fp, own = fut[p["oks_id"]]
    cp = transform(T, shape(f["geometry"]))
    d = next(d for d in rules["diameters"] if fp["flow_tph"] <= d["capacity_tph"])
    hw = d["width_m"] / 2
    zones = []
    for i in tree.query(own.buffer(20)):
        q, g = obst[i]
        if q.get("id") == fp["id"]:
            continue
        kind = "oks" if q["object_type"] != "restriction" else q["restriction_type"]
        zones.append(g.buffer((5 if kind == "oks" else RR[kind]["clearance_m"]) + hw + 0.3))
    if not zones or not any(z.contains(cp) for z in zones):
        continue
    ok = own.buffer(-1.0).exterior.difference(unary_union(zones))
    if ok.is_empty:
        print(p["oks_id"], "места нет")
        continue
    new = nearest_points(ok, cp)[0]
    f["geometry"]["coordinates"] = [round(v, 9) for v in BACK(new.x, new.y)]
    moved += 1
    print(p["oks_id"], f"перенесена на {cp.distance(new):.1f} м")
json.dump(doc, open(dst, "w"), ensure_ascii=False)
print("перенесено", moved)
