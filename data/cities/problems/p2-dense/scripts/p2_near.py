"""Всё вокруг точки ОКС в радиусе R: тип, id, расстояние до точки и до полигона ОКС."""
import json, sys
from shapely.geometry import shape
from shapely.ops import transform
from shapely.strtree import STRtree
from pyproj import Transformer

MAIN = "/Users/a1111/HACK/LCT/LCT/.claude/worktrees/geojson-heat-network-moscow-spb-ea030e"
src, oid, R = sys.argv[1], sys.argv[2], float(sys.argv[3])
T = Transformer.from_crs(4326, 32637, always_xy=True).transform
fs = json.load(open(src))["features"]
G = [(f["properties"], transform(T, shape(f["geometry"]))) for f in fs if f["geometry"]]
poly = next(g for p, g in G if p.get("id") == oid)
cp = next(g for p, g in G if p.get("oks_id") == oid)
print("cp", cp.x, cp.y, "poly bounds", poly.bounds)
tree = STRtree([g for _, g in G])
rows = []
for j in tree.query(cp.buffer(R)):
    p, g = G[j]
    kind = p.get("restriction_type") or p["object_type"]
    rows.append((g.distance(cp), kind, p.get("id"), g.distance(poly), p.get("diameter") or p.get("_source", ""), g.geom_type))
for r in sorted(rows):
    print(f"{r[0]:7.2f} {r[1]:18s} {r[2]!s:22s} to_poly={r[3]:7.2f} {r[4]!s:8s} {r[5]}")
