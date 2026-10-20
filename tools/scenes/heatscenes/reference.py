import math
from dataclasses import dataclass
from typing import Any

import numpy as np
import shapely
from scipy.sparse import csr_matrix
from scipy.sparse.csgraph import dijkstra
from shapely.geometry import LineString, Point

from heatscenes.scene import CLIP_PAD_M, Scene, dn_for, rules
from heatscenes.world import BUILDINGS

CELL_M = 2.0
GRID_PAD_M = 20.0
STEPS = ((1, 0), (0, 1), (1, 1), (1, -1))
AREAS = ("road", "tram_tracks")
LINES = ("gas_pipeline", "power_cable")
SAMPLE_M = 3.0
CHORD_MAX_M = 150.0
RAY_ANGLES_DEG = (-20.0, 0.0, 20.0)
AREA_MIN_DEG = 45.0
LINE_MIN_DEG = 70.0
STEP_OUT_M = 1.0


@dataclass
class Specials:
    bands: Any
    edges: np.ndarray
    edge_dir: np.ndarray
    edge_min_sin: np.ndarray
    edge_owner: np.ndarray
    tree: shapely.STRtree
    pipes: list[Any]
    chords: np.ndarray
    ring_lines: Any = None
    ring_tree: Any = None
    approach: np.ndarray | None = None


def width_of(dn: int) -> float:
    return next(r["width_m"] for r in rules()["diameters"] if r["dn"] == dn)


def oks_clearance(dn: int) -> float:
    return next(c["m"] for c in rules()["restrictions"]["oks_existing"]["clearance_m"] if dn <= c["dn_max"])


def local_items(scene: Scene, region: Any) -> list[Any]:
    if region is None:
        return scene.items
    items = [i for i in scene.items if i.geom is not None]
    cached = getattr(scene, "_tree", None)
    if cached is None or cached[0] != len(scene.items):
        cached = (len(scene.items), shapely.STRtree([i.geom for i in items]), items)
        scene._tree = cached
    _, tree, indexed = cached
    return [indexed[n] for n in sorted(tree.query(region, predicate="intersects"))]


def hard_obstacles(scene: Scene, dn: int, own: Any, region: Any = None) -> Any:
    half = width_of(dn) / 2
    parts = []
    for item in local_items(scene, region):
        if item.geom is None or item.id in own:
            continue
        if item.kind in BUILDINGS:
            parts.append(item.geom.buffer(oks_clearance(dn) + half, quad_segs=2))
            continue
        rule = rules()["restrictions"].get(item.kind)
        if rule and rule["rule"] == "forbid":
            parts.append(item.geom.buffer(rule["clearance_m"] + half, quad_segs=2))
    return shapely.union_all(parts) if parts else shapely.Polygon()


def band_width(item: Any, dn: int) -> float:
    half = width_of(dn) / 2
    if item.kind == "heat_network":
        own = width_of(item.props["diameter"]) / 2 if item.props.get("diameter") in DN_TABLE else 0.0
        return rules()["restrictions"]["heat_network"]["clearance_m"] + own + half
    rule = rules()["restrictions"][item.kind]
    return rule["clearance_m"] + rule.get("half_width_m", 0.0) + half


DN_TABLE = {r["dn"] for r in rules()["diameters"]}


def segments_of(geom: Any) -> np.ndarray:
    parts = []
    for g in shapely.get_parts(geom):
        lines = [g.exterior, *g.interiors] if g.geom_type == "Polygon" else [g]
        for line in lines:
            c = np.asarray(line.coords)
            if len(c) > 1:
                parts.append(np.stack([c[:-1], c[1:]], axis=1))
    return np.concatenate(parts) if parts else np.zeros((0, 2, 2))


def specials(scene: Scene, dn: int) -> Specials:
    cache = getattr(scene, "_specials", {})
    if cache.get("n") != len(scene.items):
        cache = {"n": len(scene.items)}
        scene._specials = cache
    if dn in cache:
        return cache[dn]
    bands, segs, mins, owners = [], [], [], []
    pipes = []
    for n, item in enumerate(scene.items):
        if item.geom is None:
            continue
        if item.kind in AREAS and item.geom.geom_type in ("Polygon", "MultiPolygon"):
            bands.append(item.geom.buffer(band_width(item, dn), quad_segs=2))
            s = segments_of(item.geom)
            limit = AREA_MIN_DEG
        elif item.kind in LINES or item.kind == "heat_network":
            bands.append(item.geom.buffer(band_width(item, dn), quad_segs=2))
            s = segments_of(item.geom)
            limit = LINE_MIN_DEG
            if item.kind == "heat_network":
                pipes.append((n, item.geom))
        else:
            continue
        segs.append(s)
        mins.append(np.full(len(s), math.sin(math.radians(limit))))
        owners.append(np.full(len(s), n))
    union = shapely.union_all(bands) if bands else shapely.Polygon()
    edges = np.concatenate(segs) if segs else np.zeros((0, 2, 2))
    d = edges[:, 1] - edges[:, 0]
    d /= np.maximum(np.linalg.norm(d, axis=1, keepdims=True), 1e-9)
    lines = shapely.linestrings(edges) if len(edges) else np.array([])
    sp = Specials(
        union,
        lines,
        d,
        np.concatenate(mins) if mins else np.zeros(0),
        np.concatenate(owners) if owners else np.zeros(0, int),
        shapely.STRtree(lines),
        pipes,
        np.zeros((0, 6)),
    )
    rings = segments_of(union)
    sp.ring_lines = shapely.linestrings(rings) if len(rings) else np.array([])
    sp.ring_tree = shapely.STRtree(sp.ring_lines)
    sp.chords = band_chords(sp)
    sp.approach = pipe_approach(sp)
    cache[dn] = sp
    return sp


def crossing_ok(sp: Specials, a: np.ndarray, b: np.ndarray, skip_owner: np.ndarray | None = None) -> np.ndarray:
    if not len(a):
        return np.zeros(0, bool)
    lines = shapely.linestrings(np.stack([a, b], axis=1))
    ok = np.ones(len(a), bool)
    if not len(sp.edges):
        return ok
    li, ei = sp.tree.query(lines, predicate="intersects")
    if not len(li):
        return ok
    d = (b - a) / np.maximum(np.linalg.norm(b - a, axis=1, keepdims=True), 1e-9)
    sin = np.abs(d[li, 0] * sp.edge_dir[ei, 1] - d[li, 1] * sp.edge_dir[ei, 0])
    bad = sin < sp.edge_min_sin[ei] - 1e-6
    if skip_owner is not None:
        bad &= sp.edge_owner[ei] != skip_owner[li]
    ok[li[bad]] = False
    return ok


def first_exit(sp: Specials, start: np.ndarray, d: np.ndarray) -> np.ndarray:
    out = np.full(len(start), np.inf)
    if not len(sp.ring_lines) or not len(start):
        return np.full(len(start), np.nan)
    rays = shapely.linestrings(np.stack([start, start + d * CHORD_MAX_M], axis=1))
    ri, ei = sp.ring_tree.query(rays, predicate="intersects")
    if len(ri):
        hp = shapely.get_coordinates(shapely.centroid(shapely.intersection(rays[ri], sp.ring_lines[ei])))
        dist = np.einsum("ij,ij->i", hp - start[ri], d[ri])
        keep = dist > 0.05
        np.minimum.at(out, ri[keep], dist[keep])
    out[~np.isfinite(out)] = np.nan
    return out


def pipe_approach(sp: Specials) -> np.ndarray:
    rows = []
    for n, pg in sp.pipes:
        dist = np.arange(0.0, pg.length + 0.01, CELL_M)
        pts = shapely.get_coordinates(shapely.line_interpolate_point(pg, dist))
        before = shapely.get_coordinates(shapely.line_interpolate_point(pg, np.clip(dist - 0.3, 0, pg.length)))
        after = shapely.get_coordinates(shapely.line_interpolate_point(pg, np.clip(dist + 0.3, 0, pg.length)))
        t = after - before
        t /= np.maximum(np.linalg.norm(t, axis=1, keepdims=True), 1e-9)
        for side in (1, -1):
            nrm = np.column_stack([-t[:, 1], t[:, 0]]) * side
            for ang in RAY_ANGLES_DEG:
                r = math.radians(ang)
                d = np.column_stack(
                    [
                        nrm[:, 0] * math.cos(r) - nrm[:, 1] * math.sin(r),
                        nrm[:, 0] * math.sin(r) + nrm[:, 1] * math.cos(r),
                    ]
                )
                reach = first_exit(sp, pts + d * 0.05, d)
                good = ~np.isnan(reach)
                if not good.any():
                    continue
                q = pts[good] + d[good] * (reach[good][:, None] + 0.05 + STEP_OUT_M)
                ok = crossing_ok(sp, pts[good], q, np.full(int(good.sum()), n))
                rows.append(np.column_stack([pts[good][ok], q[ok]]))
    return np.concatenate(rows) if rows else np.zeros((0, 4))


def band_chords(sp: Specials) -> np.ndarray:
    rings = segments_of(sp.bands)
    if not len(rings):
        return np.zeros((0, 6))
    mid, tang = [], []
    for a, b in rings:
        length = math.dist(a, b)
        n = max(1, int(length // SAMPLE_M))
        for k in range(n):
            t = (k + 0.5) / n
            mid.append(a + (b - a) * t)
            tang.append((b - a) / max(length, 1e-9))
    p, t = np.asarray(mid), np.asarray(tang)
    nrm = np.column_stack([-t[:, 1], t[:, 0]])
    inside = shapely.contains_xy(sp.bands, p[:, 0] + nrm[:, 0] * 0.3, p[:, 1] + nrm[:, 1] * 0.3)
    nrm[~inside] *= -1
    rows = []
    for ang in RAY_ANGLES_DEG:
        r = math.radians(ang)
        d = np.column_stack(
            [nrm[:, 0] * math.cos(r) - nrm[:, 1] * math.sin(r), nrm[:, 0] * math.sin(r) + nrm[:, 1] * math.cos(r)]
        )
        reach = first_exit(sp, p + d * 0.05, d)
        good = ~np.isnan(reach)
        q = p[good] + d[good] * (reach[good][:, None] + 0.05)
        ok = crossing_ok(sp, p[good], q)
        rows.append(np.column_stack([p[good][ok], q[ok], d[good][ok]]))
    return np.concatenate(rows) if rows else np.zeros((0, 6))


def grid_of(scene: Scene, around: Point | None = None, radius: float | None = None) -> tuple[np.ndarray, np.ndarray]:
    minx, miny, maxx, maxy = scene.area.buffer(CLIP_PAD_M + GRID_PAD_M).bounds
    if around is not None and radius:
        minx, miny = max(minx, around.x - radius), max(miny, around.y - radius)
        maxx, maxy = min(maxx, around.x + radius), min(maxy, around.y + radius)
        return np.arange(minx, maxx, CELL_M), np.arange(miny, maxy, CELL_M)
    houses = [i.geom for i in scene.items if i.kind == "oks_future" or i.kind == "oks_connection_point"]
    if houses:
        hx0, hy0, hx1, hy1 = shapely.total_bounds(houses)
        minx, miny = min(minx, hx0 - GRID_PAD_M), min(miny, hy0 - GRID_PAD_M)
        maxx, maxy = max(maxx, hx1 + GRID_PAD_M), max(maxy, hy1 + GRID_PAD_M)
    return np.arange(minx, maxx, CELL_M), np.arange(miny, maxy, CELL_M)


def entry_rays(
    cp: Point, house: Any, zone_m: float, hard: Any, sp: Specials, any_wall: bool
) -> list[tuple[Point, float]]:
    ring = house.exterior
    nearest = ring.interpolate(ring.project(cp))
    base = cp.distance(nearest)
    samples = [ring.interpolate(t, normalized=True) for t in np.linspace(0, 1, 120, endpoint=False)]
    if base < 0.05:
        t = ring.project(cp)
        a, b = ring.interpolate(max(0.0, t - 0.5)), ring.interpolate(min(ring.length, t + 0.5))
        d = math.hypot(b.x - a.x, b.y - a.y) or 1.0
        for nx, ny in (((a.y - b.y) / d, (b.x - a.x) / d), ((b.y - a.y) / d, (a.x - b.x) / d)):
            if not house.contains(Point(cp.x + nx * 0.3, cp.y + ny * 0.3)):
                nearest = Point(cp.x + nx * 0.01, cp.y + ny * 0.01)
        base = cp.distance(nearest)
    group = samples if any_wall else [nearest] + [p for p in samples if cp.distance(p) <= base + 1.0]
    rays = []
    for b in group:
        d = cp.distance(b)
        if d < 1e-6:
            continue
        ux, uy = (b.x - cp.x) / d, (b.y - cp.y) / d
        k = zone_m + CELL_M
        end = Point(b.x + ux * k, b.y + uy * k)
        while (house.distance(end) < zone_m + CELL_M or sp.bands.contains(end)) and k < CHORD_MAX_M:
            k += STEP_OUT_M
            end = Point(b.x + ux * k, b.y + uy * k)
        if house.distance(end) < zone_m + CELL_M or sp.bands.contains(end):
            continue
        ray = LineString([cp, end])
        if ray.intersects(hard) or house.intersection(ray).length > d + 0.01:
            continue
        if not crossing_ok(sp, np.array([[b.x, b.y]]), np.array([[end.x, end.y]]))[0]:
            continue
        rays.append((end, ray.length))
    return rays


def shortest(
    scene: Scene, cp_id: Any, dn: int | None = None, block: tuple[str, ...] = (), radius: float | None = None
) -> float | None:
    return shortest_path(scene, cp_id, dn, block, radius)[0]


def shortest_path(
    scene: Scene,
    cp_id: Any,
    dn: int | None = None,
    block: tuple[str, ...] = (),
    radius: float | None = None,
    target: Any = None,
) -> tuple[float | None, list[tuple[float, float]]]:
    length, path = route(scene, cp_id, dn, radius, any_wall=False, target=target)
    if length is None:
        length, path = route(scene, cp_id, dn, radius, any_wall=True, target=target)
        if length is not None:
            scene.far_wall.add(str(cp_id))
    return length, path


def route(
    scene: Scene, cp_id: Any, dn: int | None, radius: float | None, any_wall: bool, target: Any = None
) -> tuple[float | None, list[tuple[float, float]]]:
    cp = scene.get(cp_id)
    dn = dn or dn_for(scene.flow(cp)) or 1400
    houses = [
        h for h in local_items(scene, cp.geom.buffer(1.0)) if h.kind == "oks_future" and h.geom.distance(cp.geom) < 0.01
    ]
    own = {cp.props.get("oks_id")} | {h.id for h in houses}
    xs, ys = grid_of(scene, cp.geom, radius)
    gx, gy = np.meshgrid(xs, ys)
    box = shapely.box(xs[0], ys[0], xs[-1], ys[-1])
    region = box.buffer(15.0) if radius else None
    hard = hard_obstacles(scene, dn, own, region)
    shapely.prepare(hard)
    sp = specials(scene, dn)
    zone_m = oks_clearance(dn) + width_of(dn) / 2
    if houses:
        house = houses[0].geom
        house = house if house.geom_type == "Polygon" else max(house.geoms, key=lambda g: g.area)
        blocked = shapely.union_all([hard, house.buffer(zone_m, quad_segs=2), sp.bands.intersection(box.buffer(5))])
        rays = entry_rays(cp.geom, house, zone_m, hard, sp, any_wall)
    else:
        blocked = shapely.union_all([hard, sp.bands.intersection(box.buffer(5))])
        rays = [(cp.geom, 0.0)] if not sp.bands.contains(cp.geom) else []
    if not rays or not sp.pipes:
        return None, []
    shapely.prepare(blocked)
    free = ~shapely.contains_xy(blocked, gx, gy)
    ny, nx = free.shape
    ids = np.arange(nx * ny).reshape(ny, nx)
    source, sink = nx * ny, nx * ny + 1
    rows, cols, weights = [], [], []

    def cells(x: np.ndarray, y: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
        i, j = np.rint((x - xs[0]) / CELL_M).astype(int), np.rint((y - ys[0]) / CELL_M).astype(int)
        inside = (i >= 0) & (i < nx) & (j >= 0) & (j < ny)
        ok = np.zeros(len(x), bool)
        ok[inside] = free[j[inside], i[inside]]
        return i, j, ok

    for end, length in rays:
        sx = int(np.clip(round((end.x - xs[0]) / CELL_M), 0, nx - 1))
        sy = int(np.clip(round((end.y - ys[0]) / CELL_M), 0, ny - 1))
        free[sy, sx] = True
        rows.append(np.array([source]))
        cols.append(np.array([ids[sy, sx]]))
        weights.append(np.array([length + Point(xs[sx], ys[sy]).distance(end) + 1e-6]))
    ch = sp.chords
    if len(ch):
        ax, ay = ch[:, 0] - ch[:, 4] * STEP_OUT_M, ch[:, 1] - ch[:, 5] * STEP_OUT_M
        bx, by = ch[:, 2] + ch[:, 4] * STEP_OUT_M, ch[:, 3] + ch[:, 5] * STEP_OUT_M
        ia, ja, oka = cells(ax, ay)
        ib, jb, okb = cells(bx, by)
        both = oka & okb
        if both.any():
            lines = shapely.linestrings(np.stack([np.column_stack([ax, ay]), np.column_stack([bx, by])], axis=1)[both])
            clean = ~shapely.intersects(lines, hard)
            sel = np.flatnonzero(both)[clean]
            rows.append(ids[ja[sel], ia[sel]])
            cols.append(ids[jb[sel], ib[sel]])
            weights.append(np.hypot(bx - ax, by - ay)[sel])
    ap = sp.approach
    if target is not None:
        ap = ap[shapely.contains_xy(target, ap[:, 0], ap[:, 1])]
    ap_sel, ap_nodes = np.array([], int), np.array([], int)
    if len(ap):
        iq, jq, okq = cells(ap[:, 2], ap[:, 3])
        if okq.any():
            lines = shapely.linestrings(np.stack([ap[okq, :2], ap[okq, 2:]], axis=1))
            sel = np.flatnonzero(okq)[~shapely.intersects(lines, hard)]
            ap_sel = sel
            ap_nodes = ids[jq[sel], iq[sel]]
            rows.append(ap_nodes)
            cols.append(np.full(len(sel), sink))
            weights.append(np.hypot(ap[sel, 2] - ap[sel, 0], ap[sel, 3] - ap[sel, 1]))
    for dx, dy in STEPS:
        y0, y1 = max(0, -dy), ny - max(0, dy)
        x0, x1 = 0, nx - dx
        a = free[y0:y1, x0:x1] & free[y0 + dy : y1 + dy, x0 + dx : x1 + dx]
        rows.append(ids[y0:y1, x0:x1][a])
        cols.append(ids[y0 + dy : y1 + dy, x0 + dx : x1 + dx][a])
        weights.append(np.full(int(a.sum()), CELL_M * math.hypot(dx, dy)))
    r, c, w = np.concatenate(rows), np.concatenate(cols), np.concatenate(weights)
    size = nx * ny + 2
    rr, cc, ww = np.concatenate([r, c]), np.concatenate([c, r]), np.concatenate([w, w])
    order = np.lexsort((ww, cc, rr))
    rr, cc, ww = rr[order], cc[order], ww[order]
    first = np.r_[True, (rr[1:] != rr[:-1]) | (cc[1:] != cc[:-1])]
    graph = csr_matrix((ww[first], (rr[first], cc[first])), shape=(size, size))
    dist, pred = dijkstra(graph, indices=source, directed=False, return_predecessors=True)
    best = dist[sink]
    if not np.isfinite(best):
        return None, []
    node = pred[sink]
    used = ap_sel[ap_nodes == node]
    k = used[np.argmin(np.hypot(ap[used, 2] - ap[used, 0], ap[used, 3] - ap[used, 1]))]
    path = [(float(ap[k, 0]), float(ap[k, 1]))]
    while node >= 0 and node != source:
        j, i = divmod(int(node), nx)
        path.append((float(xs[i]), float(ys[j])))
        node = pred[node]
    path.append((cp.geom.x, cp.geom.y))
    return float(best), path[::-1]


def bound(length: float) -> int:
    return math.ceil(1.3 * length + 10.0)
