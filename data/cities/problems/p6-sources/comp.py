"""Компоненты сети multi.geojson по касанию до 0,5 м (стыки и Т-стыки) и какие из них достаёт каждый источник."""
import json, collections
from pyproj import Transformer
from shapely.geometry import shape
from shapely.ops import transform
from shapely.strtree import STRtree

P = "/Users/a1111/HACK/LCT/LCT/.claude/worktrees/geojson-heat-network-moscow-spb-ea030e/data/cities/problems/p6-sources/multi.geojson"
fwd = Transformer.from_crs(4326, 32637, always_xy=True).transform
F = json.load(open(P))["features"]
segs = [(f["properties"]["id"], transform(fwd, shape(f["geometry"]))) for f in F if f["properties"]["object_type"] == "heat_network"]
srcs = [(f["properties"]["id"], transform(fwd, shape(f["geometry"]))) for f in F if f["properties"]["object_type"] == "source"]
geoms = [g for _, g in segs]
tree = STRtree(geoms)
parent = list(range(len(geoms)))
def find(a):
    while parent[a] != a:
        parent[a] = parent[parent[a]]
        a = parent[a]
    return a
for i, g in enumerate(geoms):
    for j in tree.query(g, predicate="dwithin", distance=0.5):
        parent[find(i)] = find(int(j))
size = collections.Counter(find(i) for i in range(len(geoms)))
print("segments", len(geoms), "components", len(size), "sizes", sorted(size.values(), reverse=True)[:8])
reached = set()
for sid, p in srcs:
    comps = {find(int(j)) for j in tree.query(p, predicate="dwithin", distance=0.5)}
    reached |= comps
    print(sid, "components", [size[c] for c in comps])
print("apart segments", sum(n for c, n in size.items() if c not in reached))

# врезки выхода: в какую компоненту сети
O = P.replace("multi.geojson", "runs/proto-multi.out.geojson")
out = json.load(open(O))["features"]
owner = {}
for sid, p in srcs:
    for j in tree.query(p, predicate="dwithin", distance=0.5):
        owner[find(int(j))] = sid
chambers_in = {str(f["properties"]["id"]): transform(fwd, shape(f["geometry"])) for f in F if f["properties"]["object_type"] == "heat_chamber"}
ties = collections.Counter()
for f in out:
    p = f["properties"]
    if p["object_type"] == "heat_chamber":
        g = transform(fwd, shape(f["geometry"]))
        hit = [int(j) for j in tree.query(g, predicate="dwithin", distance=0.05)]
        if hit:
            ties[owner.get(find(hit[0]), "без источника")] += 1
    if p["object_type"] == "heat_network":
        for k in ("start_node_id", "end_node_id"):
            if str(p[k]) in chambers_in:
                hit = [int(j) for j in tree.query(chambers_in[str(p[k])], predicate="dwithin", distance=0.5)]
                ties[("камера", owner.get(find(hit[0]), "без источника") if hit else "?")] += 1
print("врезки по сетям:", dict(ties))
