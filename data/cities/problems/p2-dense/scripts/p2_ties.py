"""Кандидаты врезки как у TieInFinder.find (3 ближайшие камеры + проекции точки на 3 ближайшие трубы) и где они лежат:
в зоне запрета, в свободной части точки или отрезаны. Плюс ближайшие к точке места сети в её свободной части.
Запуск: p2_ties.py вход id[,id] [R]"""
import json, sys
from shapely.geometry import shape, Point
from shapely.ops import transform, unary_union, nearest_points
from shapely.strtree import STRtree
from pyproj import Transformer

MAIN = "/Users/a1111/HACK/LCT/LCT/.claude/worktrees/geojson-heat-network-moscow-spb-ea030e"
src, ids = sys.argv[1], sys.argv[2].split(",")
R = float(sys.argv[3]) if len(sys.argv) > 3 else 400
T = Transformer.from_crs(4326, 32637, always_xy=True).transform
rules = json.load(open(MAIN + "/rules/rules.json"))
RR = rules["restrictions"]
G = [(f["properties"], transform(T, shape(f["geometry"]))) for f in json.load(open(src))["features"] if f["geometry"]]
FORBID = {k for k, v in RR.items() if not k.startswith("_") and v["rule"] == "forbid" and k != "oks_existing"}
obst = [(p, g) for p, g in G if p["object_type"] == "oks_existing" or
        (p["object_type"] == "restriction" and (p.get("restriction_type") in FORBID or p.get("restriction_type") == "oks"))]
otree = STRtree([g for _, g in obst])
segs = [(p, g) for p, g in G if p["object_type"] == "heat_network"]
chs = [(p, g) for p, g in G if p["object_type"] == "heat_chamber"]
stree, ctree = STRtree([g for _, g in segs]), STRtree([g for _, g in chs])
roads = [g for p, g in G if p.get("restriction_type") in ("road", "tram_tracks")]
rtree = STRtree(roads)
fut = {p["id"]: (p, g) for p, g in G if p["object_type"] == "oks_future"}
cps = {p.get("oks_id", p["id"]): (p, g) for p, g in G if p["object_type"] == "oks_connection_point"}


def dn_for(flow):
    return next((d for d in rules["diameters"] if flow <= d["capacity_tph"]), rules["diameters"][-1])


def where(pt, zones_by_id, comp):
    inz = [i for i, z in zones_by_id if z.contains(pt)]
    road = any(r.buffer(3).contains(pt) for r in (roads[i] for i in rtree.query(pt.buffer(3))))
    return f"{'в зоне ' + ','.join(inz[:3]) if inz else 'свободно'}{' в полосе дороги' if road else ''}" \
           f"{' ДОСТИЖИМО' if comp is not None and comp.covers(pt) and not inz else ''}"


for oid in ids:
    key = int(oid) if oid.isdigit() else oid
    cpp, cp = cps[key]
    flow = fut[key][0]["flow_tph"] if key in fut else cpp["flow_tph"]
    own = fut[key][1] if key in fut else None
    d = dn_for(flow)
    hw = d["width_m"] / 2
    zo = (5 if d["dn"] <= 400 else 7 if d["dn"] <= 800 else 9) + hw
    disk = cp.buffer(R)
    zones = []
    for i in otree.query(disk):
        p, g = obst[i]
        if own is not None and g.equals(own):
            continue
        kind = "oks_existing" if p["object_type"] == "oks_existing" or p.get("restriction_type") == "oks" else p["restriction_type"]
        dist = zo if kind == "oks_existing" else RR[kind]["clearance_m"] + hw
        zones.append((f"{kind}:{p.get('id')}", g.buffer(dist, quad_segs=8)))
    Z = unary_union([z for _, z in zones])
    free = disk.difference(Z)
    comp = next((part for part in getattr(free, "geoms", [free]) if part.covers(cp)), None)
    print(f"\n=== {oid} DN{d['dn']} zone_oks={zo:.3f}")
    near_c = sorted(ctree.query(cp.buffer(R)), key=lambda i: chs[i][1].distance(cp))[:3]
    for i in near_c:
        p, g = chs[i]
        print(f"  камера {p['id']} {g.distance(cp):6.1f} м: {where(g, zones, comp)}")
    near_s = sorted(stree.query(cp.buffer(R)), key=lambda i: segs[i][1].distance(cp))[:3]
    for i in near_s:
        p, g = segs[i]
        foot = nearest_points(g, cp)[0]
        print(f"  труба {p['id']} Ду{p.get('diameter')} {g.distance(cp):6.1f} м, проекция: {where(foot, zones, comp)}")
    if comp is not None:
        # ближайшие к точке места сети, куда можно дойти (часть трубы в свободной части точки)
        best = []
        for i in stree.query(comp):
            p, g = segs[i]
            inter = g.intersection(comp)
            if not inter.is_empty:
                q = nearest_points(inter, cp)[0]
                best.append((q.distance(cp), p["id"], p.get("diameter"), round(inter.length, 1)))
        best.sort()
        print("  достижимые трубы (расстояние по прямой, id, Ду, длина доступной части):", [(round(a, 1), b, c, e) for a, b, c, e in best[:6]])
