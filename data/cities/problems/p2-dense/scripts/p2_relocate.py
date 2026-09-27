"""Где в здании ОКС можно было поставить точку подключения: внутренний контур на 1 м от стены (как у генератора)
минус зоны отступа чужих зданий и запретных территорий по Ду ОКС. Печатает долю допустимого контура и точку,
ближайшую к прежней. Запуск: p2_relocate.py вход id[,id]"""
import json, sys
from shapely.geometry import shape
from shapely.ops import transform, unary_union, nearest_points
from shapely.strtree import STRtree
from pyproj import Transformer

MAIN = "/Users/a1111/HACK/LCT/LCT/.claude/worktrees/geojson-heat-network-moscow-spb-ea030e"
src, ids = sys.argv[1], sys.argv[2].split(",")
T = Transformer.from_crs(4326, 32637, always_xy=True).transform
rules = json.load(open(MAIN + "/rules/rules.json"))
RR = rules["restrictions"]
G = [(f["properties"], transform(T, shape(f["geometry"]))) for f in json.load(open(src))["features"] if f["geometry"]]
FORBID = {k for k, v in RR.items() if not k.startswith("_") and v["rule"] == "forbid" and k != "oks_existing"}
obst = [(p, g) for p, g in G if p["object_type"] in ("oks_existing", "oks_future") or
        (p["object_type"] == "restriction" and p.get("restriction_type") in FORBID)]
tree = STRtree([g for _, g in obst])
fut = {p["id"]: (p, g) for p, g in G if p["object_type"] == "oks_future"}
cps = {p["oks_id"]: g for p, g in G if p["object_type"] == "oks_connection_point"}
for oid in ids:
    p, own = fut[oid]
    cp = cps[oid]
    d = next(d for d in rules["diameters"] if p["flow_tph"] <= d["capacity_tph"])
    hw = d["width_m"] / 2
    zones = []
    for i in tree.query(own.buffer(20)):
        q, g = obst[i]
        if q.get("id") == oid:
            continue
        kind = "oks" if q["object_type"] != "restriction" else q["restriction_type"]
        zones.append(g.buffer((5 if kind == "oks" else RR[kind]["clearance_m"]) + hw + 0.3))
    ring = own.buffer(-1.0).exterior
    ok = ring.difference(unary_union(zones)) if zones else ring
    best = nearest_points(ok, cp)[0] if not ok.is_empty else None
    print(f"{oid}: контур на 1 м от стены {ring.length:.0f} м, вне зон соседей (+0,3 м) {ok.length:.0f} м "
          f"({100 * ok.length / ring.length:.0f} %); ближайшая допустимая точка в {cp.distance(best):.1f} м от прежней"
          if best else f"{oid}: допустимого места нет")
    if best is not None:
        lon, lat = Transformer.from_crs(32637, 4326, always_xy=True).transform(best.x, best.y)
        print(f"  новая точка {lon:.9f},{lat:.9f}")
