import math
from collections import defaultdict
from collections.abc import Iterable
from dataclasses import dataclass
from typing import (
    Any,
    NamedTuple,
)

import shapely
from shapely import STRtree
from shapely.geometry import (
    LineString,
    MultiLineString,
    Point,
)
from shapely.geometry.base import BaseGeometry
from shapely.ops import substring

from heatcheck.model import (
    EXISTING_TOL_M,
    LENGTH_TOL_M,
    NODE_TOL_M,
    Feature,
    Input,
    Obstacle,
    Variant,
    diameter_for,
)


@dataclass
class Net:
    node_points: dict[str, Point]
    points: list[Point]
    members: list[set[str]]
    group_of: dict[str, int]
    start: dict[str, int]
    end: dict[str, int]
    out_segs: dict[int, list[Feature]]
    in_segs: dict[int, list[Feature]]
    roots: list[int]
    cp_oks: dict[str, str]
    oks_below: dict[str, frozenset[str]]

    def incident(self, group: int) -> list[Feature]:
        return self.out_segs.get(group, []) + self.in_segs.get(group, [])

    def root_oks(self, group: int) -> frozenset[str]:
        return frozenset().union(*(self.oks_below[s.id] for s in self.out_segs.get(group, [])))


class Load(NamedTuple):
    full: float
    partial: list[tuple[float, float]]


class Zone(NamedTuple):
    obstacle: Obstacle
    geom: BaseGeometry
    area: BaseGeometry


def cluster(count: int, pairs: Iterable[tuple[int, int]]) -> list[int]:
    parent = list(range(count))

    def find(i: int) -> int:
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    for a, b in pairs:
        parent[find(a)] = find(b)
    return [find(i) for i in range(count)]


def build_net(inp: Input, variant: Variant) -> Net:
    points = {f.id: f.geom for f in variant.tie_ins + variant.chambers + variant.nodes if isinstance(f.geom, Point)}
    cp_oks = {}
    for seg in variant.segments:
        for key in ("start_node_id", "end_node_id"):
            node = inp.by_id.get(str(seg.props.get(key)))
            if node is not None and node.object_type == "oks_connection_point" and isinstance(node.geom, Point):
                points[node.id] = node.geom
                cp_oks[node.id] = str(node.props.get("oks_id"))

    ids = list(points)
    geoms = [points[i] for i in ids]
    pairs = zip(*STRtree(geoms).query(geoms, predicate="dwithin", distance=NODE_TOL_M)) if geoms else []
    labels = cluster(len(ids), pairs)
    compact = {label: n for n, label in enumerate(dict.fromkeys(labels))}
    group_points: list[Point | None] = [None] * len(compact)
    members: list[set[str]] = [set() for _ in compact]
    group_of = {}
    for node_id, label in zip(ids, labels):
        group = compact[label]
        group_of[node_id] = group
        members[group].add(node_id)
        if group_points[group] is None:
            group_points[group] = points[node_id]

    start, end = {}, {}
    out_segs: dict[int, list[Feature]] = defaultdict(list)
    in_segs: dict[int, list[Feature]] = defaultdict(list)
    for seg in variant.segments:
        s = group_of.get(str(seg.props.get("start_node_id")))
        e = group_of.get(str(seg.props.get("end_node_id")))
        if s is None or e is None or not isinstance(seg.geom, LineString):
            continue
        start[seg.id], end[seg.id] = s, e
        out_segs[s].append(seg)
        in_segs[e].append(seg)
    roots = sorted({group_of[t.id] for t in variant.tie_ins if t.id in group_of})

    oks_below: dict[str, frozenset[str]] = {}

    def below(seg: Feature, seen: set[str]) -> frozenset[str]:
        if seg.id in oks_below or seg.id in seen:
            return oks_below.get(seg.id, frozenset())
        seen.add(seg.id)
        group = end[seg.id]
        found = {cp_oks[m] for m in members[group] if m in cp_oks}
        for child in out_segs.get(group, []):
            if child is not seg:
                found |= below(child, seen)
        oks_below[seg.id] = frozenset(found)
        return oks_below[seg.id]

    for seg in variant.segments:
        if seg.id in start:
            below(seg, set())
    return Net(points, group_points, members, group_of, start, end, out_segs, in_segs, roots, cp_oks, oks_below)


def path_from_root(net: Net, group: int) -> list[Feature] | None:
    path, seen = [], set()
    while group not in net.roots:
        incoming = net.in_segs.get(group, [])
        if len(incoming) != 1 or group in seen:
            return None
        seen.add(group)
        path.append(incoming[0])
        group = net.start[incoming[0].id]
    return path[::-1]


def oks_flow(inp: Input, oks_id: str) -> float:
    oks = inp.by_id.get(oks_id)
    return float(oks.props.get("flow_tph", 0)) if oks is not None and oks.object_type == "oks_future" else 0.0


def root_tie(net: Net, variant: Variant, group: int) -> Feature:
    return min((t for t in variant.tie_ins if net.group_of.get(t.id) == group), key=lambda t: t.id)


def position(inp: Input, network: Feature, point: Point) -> float:
    measure = network.geom.project(point)
    return measure if inp.upstream_first[network.id] else network.geom.length - measure


def network_loads(inp: Input, net: Net, variant: Variant) -> dict[str, Load]:
    full: dict[str, float] = defaultdict(float)
    partial: dict[str, list[tuple[float, float]]] = defaultdict(list)
    for group in net.roots:
        tie = root_tie(net, variant, group)
        flow = sum(oks_flow(inp, oks_id) for oks_id in net.root_oks(group))
        existing = inp.by_id.get(str(tie.props.get("existing_object_id")))
        if existing is None or existing.object_type not in ("heat_network", "heat_chamber"):
            continue
        if existing.object_type == "heat_network":
            partial[existing.id].append((position(inp, existing, tie.geom), flow))
        current = inp.by_id.get(str(existing.props.get("upstream_object_id")))
        seen = set()
        while current is not None and current.object_type != "source" and current.id not in seen:
            seen.add(current.id)
            if current.object_type == "heat_network":
                full[current.id] += flow
            current = inp.by_id.get(str(current.props.get("upstream_object_id")))
    return {nid: Load(full[nid], sorted(partial[nid])) for nid in set(full) | set(partial)}


def added_at(load: Load, pos: float) -> float:
    return load.full + sum(flow for p, flow in load.partial if p >= pos - NODE_TOL_M)


def pieces(load: Load, length: float) -> list[tuple[float, float, float]]:
    bounds = [0.0] + [min(max(p, 0.0), length) for p, _ in load.partial] + [length]
    result = []
    for start, end in zip(bounds, bounds[1:]):
        if end - start > LENGTH_TOL_M:
            result.append((start, end, added_at(load, end)))
    return result


def piece_geom(inp: Input, network: Feature, start: float, end: float) -> BaseGeometry:
    length = network.geom.length
    if inp.upstream_first[network.id]:
        return substring(network.geom, start, end)
    return substring(network.geom, length - end, length - start)


def diameter_after(rules: dict[str, Any], network: Feature, added: float) -> int:
    existing = network.props.get("diameter")
    if added <= 0:
        return existing
    required = diameter_for(rules, float(network.props.get("flow_tph", 0)) + added)
    return max(existing, required or existing)


def pipe_required(rules: dict[str, Any], inp: Input, loads: dict[str, Load], network: Feature, point: Point) -> int:
    load = loads.get(network.id)
    return diameter_after(rules, network, added_at(load, position(inp, network, point)) if load else 0.0)


def chamber_required(rules: dict[str, Any], inp: Input, net: Net, loads: dict[str, Load], chamber: Feature, group: int) -> int:
    dns = [s.props.get("diameter") for s in net.incident(group)]
    for network in inp.chamber_links.get(chamber.id, []):
        load = loads.get(network.id)
        added = added_at(load, end_position(inp, network, chamber.geom)) if load else 0.0
        dns.append(diameter_after(rules, network, added))
    return max((d for d in dns if isinstance(d, int)), default=chamber.props.get("diameter"))


def end_position(inp: Input, network: Feature, point: Point) -> float:
    first, last = Point(network.geom.coords[0]), Point(network.geom.coords[-1])
    near_first = first.distance(point) <= last.distance(point)
    return 0.0 if near_first == inp.upstream_first[network.id] else network.geom.length


def special_zones(inp: Input, variant: Variant, net: Net) -> list[Zone]:
    segments = [s for s in variant.segments if s.id in net.start]
    if not segments or not inp.special:
        return []
    trace = shapely.union_all([s.geom for s in segments])
    tree = STRtree([o.feature.geom for o in inp.special])
    ties = [t.geom for t in variant.tie_ins if t.geom is not None]
    zones = []
    for index in tree.query(trace, predicate="intersects"):
        obstacle = inp.special[index]
        if obstacle.feature.geom.geom_type in ("Polygon", "MultiPolygon"):
            parts = polygon_zone(trace, obstacle)
        else:
            parts = line_zone(net, segments, obstacle, ties)
        geom = shapely.union_all([p for p in parts if p.length > 0])
        if geom.length > 0:
            zones.append(Zone(obstacle, geom, geom.buffer(NODE_TOL_M)))
    return zones


def polygon_zone(trace: BaseGeometry, obstacle: Obstacle) -> list[BaseGeometry]:
    polygon = obstacle.feature.geom
    inside = trace.intersection(polygon.buffer(obstacle.params["margin_m"]))
    lines = [g for g in shapely.get_parts(shapely.get_parts(inside)) if g.geom_type == "LineString"]
    if not lines:
        return []
    parts = list(shapely.get_parts(shapely.line_merge(MultiLineString(lines))))
    labels = cluster(len(parts), (
        (i, j) for i in range(len(parts)) for j in range(i + 1, len(parts)) if parts[i].intersects(parts[j])
    ))
    crossing = {labels[i] for i, part in enumerate(parts) if part.intersects(polygon)}
    return [part for i, part in enumerate(parts) if labels[i] in crossing]


def line_zone(net: Net, segments: list[Feature], obstacle: Obstacle, ties: list[Point]) -> list[BaseGeometry]:
    line = obstacle.feature.geom
    parts = []
    for seg in segments:
        hit = seg.geom.intersection(line)
        for x, y in shapely.get_coordinates(hit):
            point = Point(x, y)
            at_tie = any(point.distance(t) <= EXISTING_TOL_M and line.distance(t) <= EXISTING_TOL_M for t in ties)
            if obstacle.restriction_type == "heat_network" and at_tie:
                continue
            parts += route_parts(net, seg, seg.geom.project(point), obstacle.params["margin_m"])
    return parts


def route_parts(net: Net, seg: Feature, pos: float, margin: float) -> list[BaseGeometry]:
    length = seg.geom.length
    parts = [substring(seg.geom, max(pos - margin, 0), min(pos + margin, length))]
    if pos - margin < 0:
        parts += walk(net, net.start[seg.id], seg, margin - pos)
    if pos + margin > length:
        parts += walk(net, net.end[seg.id], seg, pos + margin - length)
    return parts


def walk(net: Net, group: int, came_from: Feature, remaining: float) -> list[BaseGeometry]:
    parts = []
    for seg in net.incident(group):
        if seg is came_from:
            continue
        length = seg.geom.length
        if net.start[seg.id] == group:
            parts.append(substring(seg.geom, 0, min(remaining, length)))
            far = net.end[seg.id]
        else:
            parts.append(substring(seg.geom, max(length - remaining, 0), length))
            far = net.start[seg.id]
        if remaining > length > 0:
            parts += walk(net, far, seg, remaining - length)
    return parts


def overlaps_zone(line: BaseGeometry, area: BaseGeometry) -> bool:
    # Куски сравниваются по одному: касание двух соседних зон по краям не складывается в перекрытие.
    lines = [part for part in shapely.get_parts(line.intersection(area)) if part.length > 0]
    pieces = shapely.get_parts(shapely.line_merge(MultiLineString(lines))) if lines else []
    return max((piece.length for piece in pieces), default=0.0) > NODE_TOL_M + LENGTH_TOL_M


def segment_k(zones: list[Zone], seg: Feature) -> float | None:
    return max((z.obstacle.params["k_special"] for z in zones if overlaps_zone(seg.geom, z.area)), default=None)


def deflection_deg(a: tuple[float, float], b: tuple[float, float], c: tuple[float, float]) -> float | None:
    ux, uy, vx, vy = b[0] - a[0], b[1] - a[1], c[0] - b[0], c[1] - b[1]
    if math.hypot(ux, uy) == 0 or math.hypot(vx, vy) == 0:
        return None
    return abs(math.degrees(math.atan2(ux * vy - uy * vx, ux * vx + uy * vy)))


def acute_angle_deg(u: tuple[float, float], v: tuple[float, float]) -> float:
    cos = abs(u[0] * v[0] + u[1] * v[1]) / (math.hypot(*u) * math.hypot(*v))
    return math.degrees(math.acos(min(cos, 1.0)))
