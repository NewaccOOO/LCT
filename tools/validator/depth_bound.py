import heapq
import json
import sys
from collections import defaultdict

from pyproj import Transformer
from shapely import STRtree
from shapely.geometry import Point, shape
from shapely.ops import substring, transform

TO_M = Transformer.from_crs("EPSG:4326", "EPSG:32637", always_xy=True).transform
HEIGHT = {50: 0.125, 65: 0.140, 80: 0.160, 100: 0.180, 125: 0.225, 150: 0.250, 200: 0.315, 250: 0.400, 300: 0.450,
          400: 0.560, 500: 0.710, 600: 0.800, 700: 0.900, 800: 1.000, 900: 1.100, 1000: 1.200, 1200: 1.425, 1400: 1.600}
CROSS = {"gas_pipeline": (2.8, 0.4, 0.2), "power_cable": (2.7, 0.2, 0.5)}
HEAT_TOP, HEAT_CLEAR = 3.0, 0.5
UNDER_TOP = {"road": 1.0, "tram_tracks": 1.2}
NORMAL, MIN_H, SLOPE, K_PER_M = 3.0, 0.7, 0.10, 0.10
ZONE = 2.0
STEP = 0.0005
TOUCH = 0.05
IMPL = "--impl" in sys.argv
FREE_CHAMBERS = "--free-chambers" in sys.argv
if FREE_CHAMBERS:
    sys.argv.remove("--free-chambers")
if IMPL:
    SLOPE = 0.10 * 0.998
    sys.argv.remove("--impl")


def k(h):
    return 1 + K_PER_M * (h - NORMAL) if h > NORMAL else 1.0


def lines_of(g):
    return list(g.geoms) if g.geom_type.startswith("Multi") else [g]


def load_input(path):
    data = json.load(open(path, encoding="utf-8"))
    crossers, under, oks = [], [], set()
    for f in data["features"]:
        p, g = f["properties"], f["geometry"]
        if g is None:
            continue
        t = p.get("object_type")
        if t == "oks_connection_point":
            oks.add(str(p.get("id")))
        if t == "heat_network":
            dn = int(p.get("diameter") or 0)
            h = HEIGHT.get(dn, HEIGHT[max(d for d in HEIGHT if d <= dn)] if dn >= 50 else HEIGHT[50])
            crossers.append((transform(TO_M, shape(g)), HEAT_TOP, h, HEAT_CLEAR, True))
        elif t == "restriction":
            rt = p.get("restriction_type")
            geom = transform(TO_M, shape(g))
            if rt in CROSS and geom.geom_type in ("LineString", "MultiLineString"):
                for line in lines_of(geom):
                    crossers.append((line, *CROSS[rt], False))
            elif rt in UNDER_TOP and geom.geom_type in ("Polygon", "MultiPolygon"):
                under.append((geom, UNDER_TOP[rt]))
    return crossers, under, oks


def load_output(path):
    data = json.load(open(path, encoding="utf-8"))
    by_variant = defaultdict(lambda: {"segs": [], "tech": set(), "summary": None})
    for f in data["features"]:
        p = f["properties"]
        v = by_variant[str(p["variant_id"])]
        if p["object_type"] == "heat_network":
            v["segs"].append((p, transform(TO_M, shape(f["geometry"]))))
        elif p["object_type"] == "technical_node":
            v["tech"].add(str(p["id"]))
        elif p["object_type"] == "variant_summary":
            v["summary"] = p
    return by_variant


def allowed(parts, lo, hi):
    return [(max(a, lo), min(b, hi)) for a, b in parts if max(a, lo) <= min(b, hi)]


def crossing_zones(segs, tech, crossers, under):
    tree_c = STRtree([c[0] for c in crossers]) if crossers else None
    tree_u = STRtree([u[0] for u in under]) if under else None
    zones = []
    for i, (p, line) in enumerate(segs):
        ends = (Point(line.coords[0]), Point(line.coords[-1]))
        node_ids = (str(p["start_node_id"]), str(p["end_node_id"]))
        hits = []
        h_new = HEIGHT[p["diameter"]]
        for j in (tree_c.query(line) if tree_c is not None else []):
            g, top, h_obj, clear, is_net = crossers[j]
            meet = line.intersection(g)
            if meet.is_empty:
                continue
            pts = [meet] if meet.geom_type == "Point" else [q for q in getattr(meet, "geoms", []) if q.geom_type == "Point"]
            for pt in pts:
                near = [e for e in (0, 1) if pt.distance(ends[e]) <= TOUCH]
                if is_net and near and node_ids[near[0]] not in tech:
                    continue
                hits.append((line.project(pt), [(MIN_H, top - clear - h_new), (top + h_obj + clear, 1e9)]))
        if not hits:
            continue
        hits.sort()
        groups = [[hits[0]]]
        for hit in hits[1:]:
            if hit[0] - groups[-1][-1][0] < 2 * ZONE:
                groups[-1].append(hit)
            else:
                groups.append([hit])
        for grp in groups:
            c0, c1 = grp[0][0], grp[-1][0]
            t0, t1 = max(0.0, c0 - ZONE), min(line.length, c1 + ZONE)
            parts = [(MIN_H, 1e9)]
            for _, need in grp:
                parts = [r for a, b in need for r in allowed(parts, a, b)]
            if t1 - t0 > 1e-9:
                zone_line = substring(line, t0, t1)
                for j in (tree_u.query(zone_line) if tree_u is not None else []):
                    poly, top = under[j]
                    if zone_line.intersects(poly):
                        parts = allowed(parts, top, 1e9)
            zones.append((i, c0, c1, parts, ZONE, ZONE))
    return zones


def cones(segs, forced):
    adj = defaultdict(list)
    for i, (p, line) in enumerate(segs):
        adj[str(p["start_node_id"])].append((i, 0))
        adj[str(p["end_node_id"])].append((i, 1))
    per_seg = defaultdict(list)
    for (i0, t0, t1, rl, rr), u in forced.items():
        reach = (u - MIN_H) / SLOPE + max(rl, rr)
        p0, line0 = segs[i0]
        dist, origin = {}, {}
        heap = [(t0, str(p0["start_node_id"]), rl), (line0.length - t1, str(p0["end_node_id"]), rr)]
        while heap:
            d, node, r = heapq.heappop(heap)
            if node in dist or d > reach:
                continue
            dist[node], origin[node] = d, r
            for i, side in adj[node]:
                p, line = segs[i]
                other = str(p["end_node_id"] if side == 0 else p["start_node_id"])
                if i != i0 and other not in dist:
                    heapq.heappush(heap, (d + line.length, other, r))
        for i, (p, line) in enumerate(segs):
            ds = dist.get(str(p["start_node_id"]), float("inf"))
            de = dist.get(str(p["end_node_id"]), float("inf"))
            own = (t0, t1) if i == i0 else None
            if own or ds < reach or de < reach:
                per_seg[i].append((u, ds, de, own, rl, rr, origin.get(str(p["start_node_id"])), origin.get(str(p["end_node_id"]))))
    return per_seg


def envelope(items, L, t):
    e = 0.0
    for u, ds, de, own, rl, rr, os_, oe in items:
        if own:
            d = max(0.0, own[0] - t - rl, t - own[1] - rr)
        else:
            d = min(ds + t - (ZONE if os_ is None else os_), de + L - t - (ZONE if oe is None else oe))
        e = max(e, u - SLOPE * max(0.0, d))
    return e


def zone_pieces(segs, adj, i, c0, c1, rl, rr):
    p0, line0 = segs[i]
    L0 = line0.length
    pieces = [(i, max(0.0, c0 - rl), min(L0, c1 + rr))]
    todo = []
    if c0 - rl < 0:
        todo.append((str(p0["start_node_id"]), rl - c0, i))
    if c1 + rr > L0:
        todo.append((str(p0["end_node_id"]), c1 + rr - L0, i))
    while todo:
        node, left, came = todo.pop()
        for j, side in adj[node]:
            if j == came:
                continue
            p, line = segs[j]
            L = line.length
            pieces.append((j, 0.0, min(L, left)) if side == 0 else (j, max(0.0, L - left), L))
            if left > L:
                todo.append((str(p["end_node_id"] if side == 0 else p["start_node_id"]), left - L, j))
    return pieces


def propagate(segs, zones, fixed=None):
    adj = defaultdict(list)
    for j, (p, line) in enumerate(segs):
        adj[str(p["start_node_id"])].append((j, 0))
        adj[str(p["end_node_id"])].append((j, 1))
    pieces = [zone_pieces(segs, adj, i, t0, t1, rl, rr) for i, t0, t1, parts, rl, rr in zones]
    group = list(range(len(zones)))

    def root(x):
        while group[x] != x:
            group[x] = group[group[x]]
            x = group[x]
        return x
    by_seg = defaultdict(list)
    for z, ps in enumerate(pieces):
        for j, a, b in ps:
            by_seg[j].append((a, b, z))
    for items in by_seg.values():
        items.sort()
        for k in range(1, len(items)):
            for m in range(k):
                if items[m][1] > items[k][0] + 1e-9:
                    group[root(items[k][2])] = root(items[m][2])
    members = defaultdict(list)
    for z in range(len(zones)):
        members[root(z)].append(z)
    gparts = {}
    for g, zs in members.items():
        parts = [(MIN_H, 1e9)]
        for z in zs:
            parts = [r for lo, hi in zones[z][3] for r in allowed(parts, lo, hi)]
        gparts[g] = parts
    key = lambda z: (zones[z][0], zones[z][1], zones[z][2], zones[z][4], zones[z][5])
    forced = dict(fixed or {})
    for g, zs in members.items():
        if gparts[g] and not allowed(gparts[g], MIN_H, NORMAL):
            for z in zs:
                forced[key(z)] = min(a for a, _ in gparts[g])
    for _ in range(100):
        per_seg = cones(segs, forced)
        changed = False
        for g, zs in members.items():
            emax = 0.0
            for z in zs:
                for j, a, b in pieces[z]:
                    L = segs[j][1].length
                    n = max(2, int((b - a) / 0.02) + 1)
                    emax = max(emax, max(envelope(per_seg.get(j, []), L, a + (b - a) * m / (n - 1)) for m in range(n)))
            rest = allowed(gparts[g], emax, 1e9)
            if not rest:
                continue
            u = min(a for a, _ in rest)
            if u > NORMAL + 1e-9 and any(u > forced.get(key(z), 0) + 1e-9 for z in zs):
                for z in zs:
                    forced[key(z)] = max(u, forced.get(key(z), 0))
                changed = True
        if not changed:
            return forced, per_seg
    return forced, cones(segs, forced)


def bound(segs, per_seg):
    extra = 0.0
    for i, items in per_seg.items():
        p, line = segs[i]
        L = line.length
        kk = (k(p["depth_start"]) + k(p["depth_end"])) / 2
        w = p["cost"] / kk / L if L > 0 else 0.0
        n = max(1, int(L / STEP))
        h = L / n
        area = sum(max(0.0, envelope(items, L, (m + 0.5) * h) - 0.5 * SLOPE * h - NORMAL) for m in range(n)) * h
        extra += w * K_PER_M * area
    return extra


def fixed_chambers(segs, tech, oks):
    fixed, seen = {}, set()
    for i, (p, line) in enumerate(segs):
        for key, t, h in ((str(p["start_node_id"]), 0.0, p["depth_start"]), (str(p["end_node_id"]), line.length, p["depth_end"])):
            if key in tech or key in oks or key in seen or abs(h - NORMAL) > 1e-6:
                continue
            seen.add(key)
            fixed[(i, t, t, 0.0, 0.0)] = NORMAL
    return fixed


def main(inp, out):
    crossers, under, oks = load_input(inp)
    worst, low = 0.0, 0.0
    for vid, v in sorted(load_output(out).items(), key=lambda kv: kv[0]):
        segs, s = v["segs"], v["summary"]
        if s is None or not segs:
            continue
        flat = sum(p["cost"] / ((k(p["depth_start"]) + k(p["depth_end"])) / 2) / p["length"] * line.length
                   if p["length"] else 0.0 for p, line in segs)
        cost = sum(p["cost"] for p, _ in segs)
        rest = s["calculated_cost"] - cost
        zones = crossing_zones(segs, v["tech"], crossers, under)
        fixed = {} if FREE_CHAMBERS else fixed_chambers(segs, v["tech"], oks)
        forced, per_seg = propagate(segs, zones, fixed)
        extra = bound(segs, per_seg)
        lb = flat + rest + extra
        gap = s["calculated_cost"] - lb
        tol = 0.005 * len(segs) + 0.01 + sum(p["cost"] / p["length"] * 1e-6 + p["cost"] * 2e-7
                                              for p, line in segs if p["length"])
        worst = max(worst, gap - tol)
        if gap < -tol:
            low = min(low, gap)
        print(f"вариант {vid}: C={s['calculated_cost']:.2f} нижняя граница={lb:.2f} (C_2D той же трассы {flat + rest:.2f}"
              f" + вынужденная глубина {extra:.2f}, вынужденных зон {len(forced) - len(fixed)} из {len(zones)}) разрыв={gap:.2f} руб. "
              f"(ΔS={0.7 * gap / 25e6:.6f}, {100 * gap / lb:.5f} %)")
    if low < 0:
        print(f"BOUND FAIL {low:.2f} руб.: граница выше стоимости")
    print("DEPTH OPTIMAL" if worst <= 0 else f"DEPTH GAP {worst:.2f} руб. сверх округления")


if __name__ == "__main__":
    main(*sys.argv[1:3])
