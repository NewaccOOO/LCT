"""Геометрия ОКС P2 на СПб v1: соседи, зазоры, зоны."""
import json, sys
from shapely.geometry import shape
from shapely.ops import transform
from shapely.strtree import STRtree
from pyproj import Transformer

MAIN = "/Users/a1111/HACK/LCT/LCT/.claude/worktrees/geojson-heat-network-moscow-spb-ea030e"
src = sys.argv[1] if len(sys.argv) > 1 else MAIN + "/data/cities/runs/spb-v1/input.geojson"
T = Transformer.from_crs(4326, 32637, always_xy=True).transform
rules = json.load(open(MAIN + "/rules/rules.json"))
DIAM = rules["diameters"]
fs = json.load(open(src))["features"]
G = [(f["properties"], transform(T, shape(f["geometry"]))) for f in fs if f["geometry"]]


def dn_for(flow):
    for d in DIAM:
        if flow <= d["capacity_tph"]:
            return d


fut = {p["id"]: (p, g) for p, g in G if p["object_type"] == "oks_future"}
cps = {p["oks_id"]: g for p, g in G if p["object_type"] == "oks_connection_point"}
forbid_types = {"park", "social_area", "prohibited_site", "water", "railway", "metro", "power_line_support"}
obst = [(p, g) for p, g in G if p["object_type"] in ("oks_existing", "oks_future") or
        (p["object_type"] == "restriction" and p.get("restriction_type") in forbid_types)]
tree = STRtree([g for _, g in obst])
net = [g for p, g in G if p["object_type"] == "heat_network"]
ntree = STRtree(net)
ids = sys.argv[2].split(",") if len(sys.argv) > 2 else ["oks-w1432016796", "oks-w859480535", "oks-w857530905", "oks-w170318940"]
for oid in ids:
    p, poly = fut[oid]
    cp = cps[oid]
    d = dn_for(p["flow_tph"])
    hw = d["width_m"] / 2
    z_oks = 5 + hw if d["dn"] <= 400 else (7 + hw if d["dn"] <= 800 else 9 + hw)
    print(f"\n=== {oid} flow={p['flow_tph']} DN{d['dn']} w={d['width_m']} zone_oks={z_oks:.3f} area={poly.area:.0f} "
          f"cp_to_wall={poly.exterior.distance(cp):.2f} tags={ {k: v for k, v in p.items() if k.startswith('_')} }")
    i = ntree.nearest(cp)
    print(f"  nearest net {cp.distance(net[i]):.1f} m")
    for j in tree.query(poly.buffer(30)):
        q, g = obst[j]
        if g is poly or q.get("id") == oid:
            continue
        kind = q.get("restriction_type") or q["object_type"]
        z = z_oks if q["object_type"] in ("oks_existing", "oks_future") else rules["restrictions"][kind]["clearance_m"] + hw
        dp, dc = g.distance(poly), g.distance(cp)
        if dp < 15 or dc < z + 5:
            print(f"  {kind:14s} {q.get('id')!s:16s} {q.get('_source', '')!s:9s} gap={dp:6.2f} to_cp={dc:6.2f} "
                  f"zone={z:.2f} cp_in_zone={dc < z} overlap={g.intersection(poly).area:.1f} "
                  f"{'CONTAINS_POLY' if g.contains(poly) else ''} {'contains_cp' if g.contains(cp) else ''}")
