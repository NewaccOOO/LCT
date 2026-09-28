"""Длина кратчайшего обхода зон запрета от точки до сети (растр 0,5 м, 8 соседей, без правил поворотов и
пересечений): нижняя оценка длины любой допустимой трассы. p2_geo_dist.py вход id DN R"""
import heapq, json, math, sys
import numpy as np
import shapely
from shapely.geometry import shape, box
from shapely.ops import transform, unary_union
from shapely.strtree import STRtree
from pyproj import Transformer

MAIN = "/Users/a1111/HACK/LCT/LCT/.claude/worktrees/geojson-heat-network-moscow-spb-ea030e"
src, oid, dn, R = sys.argv[1], sys.argv[2], int(sys.argv[3]), float(sys.argv[4])
T = Transformer.from_crs(4326, 32637, always_xy=True).transform
rules = json.load(open(MAIN + "/rules/rules.json"))
RR = rules["restrictions"]
hw = next(d for d in rules["diameters"] if d["dn"] == dn)["width_m"] / 2
G = [(f["properties"], transform(T, shape(f["geometry"]))) for f in json.load(open(src))["features"] if f["geometry"]]
cp = next(g for p, g in G if p.get("oks_id") == oid)
FORBID = {k for k, v in RR.items() if not k.startswith("_") and v["rule"] == "forbid" and k != "oks_existing"}
disk = box(cp.x - R, cp.y - R, cp.x + R, cp.y + R)
zones = []
for p, g in G:
    if not g.intersects(disk.buffer(20)):
        continue
    if p["object_type"] == "oks_existing":
        zones.append(g.buffer(5 + hw))
    elif p["object_type"] == "restriction" and p.get("restriction_type") in FORBID:
        zones.append(g.buffer(RR[p["restriction_type"]]["clearance_m"] + hw))
Z = unary_union(zones)
net = unary_union([g for p, g in G if p["object_type"] == "heat_network" and g.intersects(disk)])
S = 0.5
n = int(2 * R / S)
xs = cp.x - R + S * (np.arange(n) + 0.5)
ys = cp.y - R + S * (np.arange(n) + 0.5)
X, Y = np.meshgrid(xs, ys)
pts = shapely.points(X.ravel(), Y.ravel())
blocked = shapely.contains_xy(Z, X.ravel(), Y.ravel()).reshape(n, n)
netcell = (shapely.distance(pts, net) <= S * 0.75).reshape(n, n) & ~blocked
i0, j0 = int((cp.y - ys[0]) / S + 0.5), int((cp.x - xs[0]) / S + 0.5)
dist = np.full((n, n), np.inf)
dist[i0, j0] = 0
h = [(0.0, i0, j0)]
steps = [(di, dj, S * math.hypot(di, dj)) for di in (-1, 0, 1) for dj in (-1, 0, 1) if di or dj]
found = None
while h:
    d, i, j = heapq.heappop(h)
    if d > dist[i, j]:
        continue
    if netcell[i, j]:
        found = (d, xs[j], ys[i])
        break
    for di, dj, w in steps:
        a, b = i + di, j + dj
        if 0 <= a < n and 0 <= b < n and not blocked[a, b] and d + w < dist[a, b]:
            dist[a, b] = d + w
            heapq.heappush(h, (d + w, a, b))
print(oid, f"DN{dn}: кратчайший обход зон до сети", f"{found[0]:.0f} м до ({found[1]:.1f}, {found[2]:.1f})" if found else "не найден",
      f"предел длины DN{dn} {next(d for d in rules['diameters'] if d['dn'] == dn)['max_length_m']} м")
