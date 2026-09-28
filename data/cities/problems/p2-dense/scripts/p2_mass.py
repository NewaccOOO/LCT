"""Насколько массовы стройки вплотную к зданиям и внутри запретных территорий: по каждому ОКС входа зазор до соседнего
здания, расстояние точки до соседа и до запретных территорий, попадание точки в зону отступа.
Запуск: p2_mass.py вход [--list]"""
import json, sys
from collections import Counter, defaultdict
from shapely.geometry import shape
from shapely.ops import transform
from shapely.strtree import STRtree
from pyproj import Transformer

MAIN = "/Users/a1111/HACK/LCT/LCT/.claude/worktrees/geojson-heat-network-moscow-spb-ea030e"
src = sys.argv[1]
T = Transformer.from_crs(4326, 32637, always_xy=True).transform
rules = json.load(open(MAIN + "/rules/rules.json"))
DIAM = rules["diameters"]
fs = json.load(open(src))["features"]
G = [(f["properties"], transform(T, shape(f["geometry"]))) for f in fs if f["geometry"]]


def dn_for(flow):
    return next((d for d in DIAM if flow <= d["capacity_tph"]), DIAM[-1])


def zone_oks(d):
    return (5 if d["dn"] <= 400 else 7 if d["dn"] <= 800 else 9) + d["width_m"] / 2


buildings = [(p, g) for p, g in G if p["object_type"] in ("oks_existing", "oks_future")
             or (p["object_type"] == "restriction" and p.get("restriction_type") == "oks")]
btree = STRtree([g for _, g in buildings])
AREAS = ("social_area", "prohibited_site", "park")
areas = [(p, g) for p, g in G if p["object_type"] == "restriction" and p.get("restriction_type") in AREAS]
atree = STRtree([g for _, g in areas]) if areas else None
fut = {p["id"]: (p, g) for p, g in G if p["object_type"] == "oks_future"}
rows = []
for p, cp in ((p, g) for p, g in G if p["object_type"] == "oks_connection_point"):
    if fut:
        fp, own = fut[p["oks_id"]]
        flow, source, oid = fp.get("flow_tph", 0), fp.get("_source", "?"), fp["id"]
    else:
        idx = [i for i in btree.query(cp) if buildings[i][1].covers(cp)]
        if not idx:
            continue
        own, flow, source, oid = buildings[idx[0]][1], p.get("flow_tph", 0), "dataset", p["id"]
    d = dn_for(flow)
    z = zone_oks(d)
    hw = d["width_m"] / 2
    gap, cpd, nb = 1e9, 1e9, None
    for i in btree.query(own.buffer(20)):
        g = buildings[i][1]
        if g is own or g.equals(own):
            continue
        if g.distance(own) < gap:
            gap, nb = g.distance(own), buildings[i][0].get("id")
        cpd = min(cpd, g.distance(cp))
    inside_area, cp_area = [], 1e9
    if atree is not None:
        for i in atree.query(own.buffer(5)):
            q, g = areas[i]
            if g.intersects(own):
                inside_area.append(q["restriction_type"])
            cp_area = min(cp_area, g.distance(cp))
    rows.append(dict(id=oid, source=source, dn=d["dn"], gap=gap, nb=nb, cp_nb=cpd, cp_in_oks_zone=cpd < z,
                     areas=sorted(set(inside_area)), cp_area=cp_area, cp_in_area_zone=cp_area < 1 + hw,
                     cp_wall=own.boundary.distance(cp)))

by = defaultdict(list)
for r in rows:
    by[r["source"]].append(r)
print(src.split("/data/cities/")[-1], "ОКС:", len(rows))
for s, rs in sorted(by.items()):
    c = Counter()
    for r in rs:
        c["n"] += 1
        c["gap<0.5 (вплотную)"] += r["gap"] < 0.5
        c["gap<5"] += r["gap"] < 5
        c["gap<10"] += r["gap"] < 10
        c["cp в зоне соседа"] += r["cp_in_oks_zone"]
        c["на social/prohibited/park"] += bool(r["areas"])
        c["cp в зоне social/prohibited/park"] += r["cp_in_area_zone"]
    for a in AREAS:
        c["  на " + a] = sum(a in r["areas"] for r in rs)
    print(" ", s, dict(c))
if "--list" in sys.argv:
    for r in sorted(rows, key=lambda r: r["gap"]):
        if r["gap"] < 10 or r["areas"] or r["cp_in_area_zone"] or "--all" in sys.argv:
            print(f"  {r['id']!s:24s} {r['source']:8s} DN{r['dn']:<4d} gap={r['gap']:6.2f} ({r['nb']}) cp_nb={r['cp_nb']:6.2f} "
                  f"in_zone={r['cp_in_oks_zone']!s:5s} areas={r['areas']} cp_area={r['cp_area']:.1f} cp_wall={r['cp_wall']:.2f}")
