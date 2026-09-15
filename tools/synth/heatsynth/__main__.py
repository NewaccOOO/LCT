import argparse
import heapq
import itertools
import json
import math
import random
from collections import defaultdict
from collections.abc import Iterator
from dataclasses import dataclass
from pathlib import Path
from typing import (
    Any,
    NamedTuple,
)

from pyproj import Transformer
from shapely import (
    STRtree,
    dwithin,
)
from shapely.geometry import (
    LineString,
    Point,
    Polygon,
    mapping,
)
from shapely.geometry.base import BaseGeometry
from shapely.ops import (
    nearest_points,
    transform,
)

RULES_PATH = Path("rules/rules.json")
COORD_DIGITS = 9
BASE_UTM_M = (413000.0, 6179000.0)
CENTER_SHIFT_M = 3000.0
BLOCK_M = 500.0
SECTOR_GAP_M = 200.0
STEP_M = (80.0, 120.0)
MIN_SEGMENT_M = 40.0
MIN_BRANCH_M = 60.0
NODE_JITTER_M = 3.0
BRANCH_PROBABILITY = 0.85
SUB_BRANCH_PROBABILITY = 0.7
EXTRA_CHAMBER_PROBABILITY = 0.3
LOCAL_FLOW_MTPH = (300, 2500)
OKS_FLOW_MTPH = (5000, 40000)
GCAL_PER_TPH = 0.025
OKS_SIDE_M = (30.0, 80.0)
OKS_DISTANCE_M = (50.0, 400.0)
CHAMBER_RADIUS_M = 500.0
MIN_CHAMBERS_NEAR_OKS = 2
POLYGON_GAP_M = 12.0
NETWORK_GAP_M = 10.0
NODE_GAP_M = 10.0
CP_GAP_M = 12.0
ROAD_WIDTH_M = 20.0
ROAD_OVERLAP_M = 30.0
FILLER_REACH_M = 450.0
CELL_M = 200.0
MAX_ATTEMPTS = 20000
OBSTACLE_ATTEMPTS = 50
PAD_DISTANCE_M = 20000.0
PAD_CELL_M = 100.0
PAD_SIDE_M = 60.0
PAD_ROW = 1000
BYTES_PER_MB = 1024 * 1024
SUPPORT_SHIFT_M = 1.0
# Order matters in the OKS obstacle cycles: small presets put a forbid obstacle on OKS 0-2 and a crossing
# on OKS 2 only, so types outside the TZ table sit where those OKS pick them; filtering by --types keeps
# the old order of the default eight.
FORBID_TYPES = ("metro", "power_line_support", "park", "social_area", "prohibited_site", "water")
CROSSING_TYPES = ("road", "tram_tracks", "railway")
LINE_TYPES = ("gas_pipeline", "power_cable", "water_supply", "sewer")
POINT_TYPES = ("power_line_support",)
ID_KEYS = ("id", "oks_id", "upstream_object_id")
DEFAULT_TYPES = ("park", "social_area", "prohibited_site", "water", "road", "tram_tracks", "gas_pipeline", "power_cable")
RESTRICTION_SHARES = {
    "park": 0.2, "social_area": 0.1, "prohibited_site": 0.1, "water": 0.1,
    "road": 0.15, "tram_tracks": 0.15, "gas_pipeline": 0.1, "power_cable": 0.1,
    "metro": 0.05, "power_line_support": 0.05, "railway": 0.1, "water_supply": 0.1, "sewer": 0.1,
}


Bounds = tuple[float, float, float, float]
Feature = tuple[BaseGeometry, dict[str, str | int | float]]


class Preset(NamedTuple):
    oks: int
    restrictions: int
    segments: int


PRESETS = {
    "small": Preset(3, 20, 30),
    "medium": Preset(20, 200, 300),
    "large": Preset(100, 2000, 3000),
}


class Line(NamedTuple):
    origin: tuple[float, float]
    direction: tuple[float, float]
    length: float
    kind: str
    sector: int


class Oks(NamedTuple):
    polygon: Polygon
    point: Point
    segment: int
    approach: LineString
    flow: int


@dataclass
class Network:
    nodes: list[tuple[float, float]]
    parent: list[int]
    children: list[list[int]]
    segments: list[tuple[int, int]]
    chambers: list[int]
    flows: list[int]


def rectangle(center: tuple[float, float], angle: float, length: float, width: float) -> Polygon:
    ux, uy = math.cos(angle), math.sin(angle)
    vx, vy = -uy, ux
    cx, cy = center
    return Polygon([
        (cx + a * ux * length / 2 + b * vx * width / 2, cy + a * uy * length / 2 + b * vy * width / 2)
        for a, b in ((-1, -1), (1, -1), (1, 1), (-1, 1))
    ])


def build_network(rng: random.Random, count: int, center: tuple[float, float]) -> Network:
    trunks = rng.randint(3, 5)
    half = math.pi / trunks
    turn = rng.uniform(0, 2 * math.pi)
    axes = []
    for sector in range(trunks):
        angle = turn + 2 * half * sector
        axes.append(((math.cos(angle), math.sin(angle)), (-math.sin(angle), math.cos(angle))))
    net = Network([center], [-1], [[]], [], [], [])
    heap = []
    order = itertools.count()

    def push(node: int, line: Line, position: float, path: float) -> None:
        nxt = position + rng.uniform(*STEP_M)
        station = (math.floor(position / BLOCK_M) + 1) * BLOCK_M
        if nxt > station - MIN_SEGMENT_M:
            nxt = station
        if nxt > line.length - MIN_SEGMENT_M:
            nxt = line.length
        heapq.heappush(heap, (path + nxt - position, next(order), node, line, nxt))

    for sector, (along, _) in enumerate(axes):
        push(0, Line(center, along, math.inf, "trunk", sector), 0.0, 0.0)
    while len(net.segments) < count:
        path, _, start, line, position = heapq.heappop(heap)
        lattice = (line.origin[0] + line.direction[0] * position, line.origin[1] + line.direction[1] * position)
        node = len(net.nodes)
        net.nodes.append((
            lattice[0] + rng.uniform(-NODE_JITTER_M, NODE_JITTER_M),
            lattice[1] + rng.uniform(-NODE_JITTER_M, NODE_JITTER_M),
        ))
        net.parent.append(len(net.segments))
        net.children.append([])
        net.children[start].append(len(net.segments))
        net.segments.append((start, node))
        if position < line.length:
            push(node, line, position, path)
        if position % BLOCK_M != 0 or line.kind == "sub":
            continue
        along, across = axes[line.sector]
        if line.kind == "trunk":
            length = position * math.tan(half) - SECTOR_GAP_M / (2 * math.cos(half))
            for sign in (1, -1):
                if rng.random() < BRANCH_PROBABILITY and length >= MIN_BRANCH_M:
                    branch = Line(lattice, (sign * across[0], sign * across[1]), length, "branch", line.sector)
                    push(node, branch, 0.0, path)
        elif rng.random() < SUB_BRANCH_PROBABILITY:
            push(node, Line(lattice, along, BLOCK_M - SECTOR_GAP_M, "sub", line.sector), 0.0, path)

    net.chambers = [
        node for node in range(1, len(net.nodes))
        if len(net.children[node]) >= 2 or (net.children[node] and rng.random() < EXTRA_CHAMBER_PROBABILITY)
    ]
    local = [rng.randint(*LOCAL_FLOW_MTPH) for _ in net.segments]
    net.flows = local[:]
    for segment in reversed(range(len(net.segments))):
        net.flows[segment] += sum(net.flows[child] for child in net.children[net.segments[segment][1]])
    return net


class Scene:
    def __init__(self, rng: random.Random, net: Network, extra: dict[str, float]):
        self.rng = rng
        self.extra = extra
        self.lines = [LineString([net.nodes[a], net.nodes[b]]) for a, b in net.segments]
        self.network_tree = STRtree(self.lines)
        self.chamber_tree = STRtree([Point(net.nodes[node]) for node in net.chambers])
        self.node_tree = STRtree([Point(net.nodes[node]) for node in [0, *net.chambers]])
        self.cells: dict[tuple[int, int], list[BaseGeometry]] = defaultdict(list)
        self.oks: list[Oks] = []
        self.existing: list[Polygon] = []
        self.restrictions: list[tuple[str, BaseGeometry]] = []
        xs = [x for x, _ in net.nodes]
        ys = [y for _, y in net.nodes]
        self.bounds = (min(xs), min(ys), max(xs), max(ys))

    def _keys(self, geom: BaseGeometry, pad: float) -> Iterator[tuple[int, int]]:
        minx, miny, maxx, maxy = geom.bounds
        for ix in range(math.floor((minx - pad) / CELL_M), math.floor((maxx + pad) / CELL_M) + 1):
            for iy in range(math.floor((miny - pad) / CELL_M), math.floor((maxy + pad) / CELL_M) + 1):
                yield ix, iy

    def is_free(self, geom: BaseGeometry, pending: list[BaseGeometry]) -> bool:
        nearby = [other for key in self._keys(geom, POLYGON_GAP_M) for other in self.cells.get(key, ())]
        return not any(dwithin(geom, other, POLYGON_GAP_M) for other in nearby + pending)

    def commit(self, geom: BaseGeometry) -> None:
        for key in self._keys(geom, 0.0):
            self.cells[key].append(geom)

    def zone(self, kind: str, geom: BaseGeometry) -> BaseGeometry:
        """The obstacle grown by its extra reach; gap checks and the grid use it instead of the obstacle."""
        extra = self.extra.get(kind, 0.0)
        return geom.buffer(extra) if extra else geom

    def near_network(self, geom: BaseGeometry, distance: float) -> bool:
        return self.network_tree.query(geom, predicate="dwithin", distance=distance).size > 0

    def near_nodes(self, geom: BaseGeometry, distance: float) -> bool:
        return self.node_tree.query(geom, predicate="dwithin", distance=distance).size > 0

    def is_road_misplaced(self, road: Polygon) -> bool:
        """A road may cross the network, but not cover a chamber or run along a segment."""
        if self.near_nodes(road, NODE_GAP_M):
            return True
        crossed = self.network_tree.query(road, predicate="intersects")
        return any(road.intersection(self.lines[i]).length > ROAD_OVERLAP_M for i in crossed)

    def place_oks(self, forbid_type: str | None, crossing_type: str | None) -> None:
        rng = self.rng
        for _ in range(MAX_ATTEMPTS):
            segment = rng.randrange(len(self.lines))
            (ax, ay), (bx, by) = self.lines[segment].coords
            share = rng.uniform(0.25, 0.75)
            side = rng.choice((1, -1))
            length = math.hypot(bx - ax, by - ay)
            nx, ny = -(by - ay) / length * side, (bx - ax) / length * side
            distance = rng.uniform(*OKS_DISTANCE_M)
            depth = rng.uniform(*OKS_SIDE_M)
            cx, cy = ax + (bx - ax) * share + nx * distance, ay + (by - ay) * share + ny * distance
            polygon = rectangle((cx + nx * depth / 2, cy + ny * depth / 2), math.atan2(ny, nx), depth, rng.uniform(*OKS_SIDE_M))
            point = Point(cx, cy)
            _, gaps = self.network_tree.query_nearest(polygon, return_distance=True)
            if not OKS_DISTANCE_M[0] <= gaps[0] <= OKS_DISTANCE_M[1] or not self.is_free(polygon, []):
                continue
            _, gaps = self.network_tree.query_nearest(point, return_distance=True)
            near = self.network_tree.query(point, predicate="dwithin", distance=gaps[0] + 1.0)
            if near.size != 1:
                continue
            target = nearest_points(self.lines[near[0]], point)[0]
            if (target.x - cx) * nx + (target.y - cy) * ny > -0.5 * gaps[0]:
                continue
            if self.chamber_tree.query(point, predicate="dwithin", distance=CHAMBER_RADIUS_M).size < MIN_CHAMBERS_NEAR_OKS:
                continue
            approach = LineString([point, target])
            pending = [polygon]
            obstacles = []
            forbid_build = self._support_obstacle if forbid_type in POINT_TYPES else self._forbid_obstacle
            for kind, build in ((forbid_type, forbid_build), (crossing_type, self._crossing_obstacle)):
                if kind is None:
                    continue
                obstacle = build(kind, approach, pending)
                if obstacle is None:
                    break
                pending.append(self.zone(kind, obstacle))
                obstacles.append((kind, obstacle))
            else:
                self.oks.append(Oks(polygon, point, int(near[0]), approach, rng.randint(*OKS_FLOW_MTPH)))
                for geom in pending:
                    self.commit(geom)
                self.restrictions.extend(obstacles)
                return
        raise RuntimeError("Не удалось разместить перспективный ОКС, увеличьте область или уменьшите число объектов")

    def _forbid_obstacle(self, kind: str, approach: LineString, pending: list[BaseGeometry]) -> Polygon | None:
        rng = self.rng
        (px, py), (tx, ty) = approach.coords
        ex, ey = (tx - px) / approach.length, (ty - py) / approach.length
        for _ in range(OBSTACLE_ATTEMPTS):
            along = rng.uniform(20.0, 50.0)
            across = rng.uniform(40.0, 140.0)
            low = CP_GAP_M + along / 2 + 2
            high = approach.length - NETWORK_GAP_M - along / 2 - 2
            if low > high:
                return None
            offset = rng.uniform(low, high)
            shift = rng.uniform(-across / 4, across / 4)
            center = (px + ex * offset - ey * shift, py + ey * offset + ex * shift)
            polygon = rectangle(center, math.atan2(ey, ex) + rng.uniform(-0.3, 0.3), along, across)
            zone = self.zone(kind, polygon)
            if polygon.intersects(approach) and not self.near_network(zone, NETWORK_GAP_M) and self.is_free(zone, pending):
                return polygon
        return None

    def _support_obstacle(self, kind: str, approach: LineString, pending: list[BaseGeometry]) -> Point | None:
        """A point a metre or less off the straight approach, so its clearance blocks the straight route."""
        rng = self.rng
        (px, py), (tx, ty) = approach.coords
        ex, ey = (tx - px) / approach.length, (ty - py) / approach.length
        low, high = CP_GAP_M, approach.length - NETWORK_GAP_M
        if low > high:
            return None
        for _ in range(OBSTACLE_ATTEMPTS):
            offset = rng.uniform(low, high)
            shift = rng.uniform(-SUPPORT_SHIFT_M, SUPPORT_SHIFT_M)
            point = Point(px + ex * offset - ey * shift, py + ey * offset + ex * shift)
            zone = self.zone(kind, point)
            if not self.near_network(zone, NETWORK_GAP_M) and self.is_free(zone, pending):
                return point
        return None

    def _crossing_obstacle(self, kind: str, approach: LineString, pending: list[BaseGeometry]) -> Polygon | None:
        rng = self.rng
        (px, py), (tx, ty) = approach.coords
        ex, ey = (tx - px) / approach.length, (ty - py) / approach.length
        for _ in range(OBSTACLE_ATTEMPTS):
            low = CP_GAP_M + ROAD_WIDTH_M
            high = approach.length - 5.0
            if low > high:
                return None
            length = rng.uniform(150.0, 500.0)
            offset = rng.uniform(low, high)
            angle = math.atan2(ey, ex) + math.pi / 2 + rng.uniform(-0.4, 0.4)
            shift = rng.uniform(-length / 4, length / 4)
            center = (px + ex * offset + math.cos(angle) * shift, py + ey * offset + math.sin(angle) * shift)
            polygon = rectangle(center, angle, length, ROAD_WIDTH_M)
            zone = self.zone(kind, polygon)
            if polygon.intersects(approach) and not self.is_road_misplaced(zone) and self.is_free(zone, pending):
                return polygon
        return None

    def place_filler(self, kind: str) -> None:
        rng = self.rng
        for _ in range(MAX_ATTEMPTS):
            anchor = self.lines[rng.randrange(len(self.lines))].interpolate(rng.random(), normalized=True)
            center = (anchor.x + rng.uniform(-FILLER_REACH_M, FILLER_REACH_M), anchor.y + rng.uniform(-FILLER_REACH_M, FILLER_REACH_M))
            angle = rng.uniform(0, math.pi)
            if kind in CROSSING_TYPES:
                geom = rectangle(center, angle, rng.uniform(150.0, 600.0), ROAD_WIDTH_M)
            elif kind in POINT_TYPES:
                geom = Point(center)
            elif kind == "water":
                geom = rectangle(center, angle, rng.uniform(80.0, 300.0), rng.uniform(20.0, 60.0))
            else:
                geom = rectangle(center, angle, rng.uniform(20.0, 150.0), rng.uniform(20.0, 150.0))
            zone = self.zone(kind, geom)
            blocked = self.is_road_misplaced(zone) if kind in CROSSING_TYPES else self.near_network(zone, NETWORK_GAP_M)
            if blocked or not self.is_free(zone, []):
                continue
            self.commit(zone)
            if kind == "oks_existing":
                self.existing.append(geom)
            else:
                self.restrictions.append((kind, geom))
            return
        raise RuntimeError(f"Не удалось разместить объект {kind}, увеличьте область или уменьшите число объектов")

    def place_line(self, kind: str, near_oks: bool, cp_tree: STRtree, building_tree: STRtree) -> None:
        rng = self.rng
        for _ in range(MAX_ATTEMPTS):
            if near_oks:
                base = self.oks[rng.randrange(len(self.oks))].approach
                cross = base.interpolate(rng.uniform(CP_GAP_M + 3, base.length - 5))
            else:
                base = self.lines[rng.randrange(len(self.lines))]
                cross = base.interpolate(rng.uniform(0.3, 0.7), normalized=True)
            (ax, ay), (bx, by) = base.coords
            angle = math.atan2(by - ay, bx - ax) + math.pi / 2 + rng.uniform(-0.7, 0.7)
            before, after, bend = rng.uniform(200.0, 600.0), rng.uniform(200.0, 600.0), rng.uniform(-0.25, 0.25)
            line = LineString([
                (cross.x - math.cos(angle) * before, cross.y - math.sin(angle) * before),
                (cross.x, cross.y),
                (cross.x + math.cos(angle + bend) * after, cross.y + math.sin(angle + bend) * after),
            ])
            if (
                self.network_tree.query(line, predicate="intersects").size == 0
                or self.near_nodes(line, NODE_GAP_M)
                or cp_tree.query(line, predicate="dwithin", distance=CP_GAP_M).size > 0
                or building_tree.query(line, predicate="intersects").size > 0
            ):
                continue
            self.restrictions.append((kind, line))
            return
        raise RuntimeError(f"Не удалось разместить линию {kind}, увеличьте область или уменьшите число объектов")


def overload(rng: random.Random, net: Network, oks: list[Oks], capacities: list[int]) -> None:
    """Loads the segment nearest to each OKS so that adding the OKS flow needs a larger diameter."""
    smallest_flow: dict[int, int] = {}
    for item in oks:
        smallest_flow[item.segment] = min(smallest_flow.get(item.segment, item.flow), item.flow)
    depth = []
    for start, _ in net.segments:
        depth.append(0 if start == 0 else depth[net.parent[start]] + 1)
    # deepest first: raising a segment changes only its ancestors, which come later
    for segment in sorted(smallest_flow, key=lambda s: (-depth[s], s)):
        flow = net.flows[segment]
        capacity = next(c for c in capacities if c >= flow)
        low = max(-(-capacity * 4 // 5) + 1, capacity - smallest_flow[segment] * 4 // 5)
        if flow >= low:
            continue
        delta = rng.randint(low, capacity - 50) - flow
        while segment != -1:
            net.flows[segment] += delta
            segment = net.parent[net.segments[segment][0]]


def restriction_budget(total: int, types: list[str]) -> dict[str, int]:
    # fsum keeps the weight of the default eight at exactly 1.0, so their budget stays byte for byte
    weight = math.fsum(RESTRICTION_SHARES[kind] for kind in types)
    budget = {kind: max(1, int(total * RESTRICTION_SHARES[kind] / weight)) for kind in types}
    budget[types[0]] = max(1, budget[types[0]] + total - sum(budget.values()))
    return budget


def rounded(value: float | tuple) -> float | list:
    if isinstance(value, float):
        return round(value, COORD_DIGITS)
    return [rounded(item) for item in value]


def generate(preset: Preset, seed: int, rules: dict[str, Any], types: list[str]) -> tuple[list[Feature], Bounds]:
    rng = random.Random(seed)
    table = sorted(rules["diameters"], key=lambda row: row["dn"])
    capacities = [round(row["capacity_tph"] * 1000) for row in table]
    # The gaps below were tuned for the TZ table; a type that keeps the route farther away
    # (clearance or special zone) grows its obstacles by the difference, so a detour stays open.
    reach = {
        kind: max(rules["restrictions"][kind]["clearance_m"], rules["restrictions"][kind].get("margin_m", 0.0))
        for kind in {*DEFAULT_TYPES, *types}
    }
    base = max(reach[kind] for kind in DEFAULT_TYPES)
    extra = {kind: reach[kind] - base for kind in types if reach[kind] > base}
    selected = [kind for kind in RESTRICTION_SHARES if kind in types]
    forbid = [kind for kind in FORBID_TYPES if kind in types]
    crossing = [kind for kind in CROSSING_TYPES if kind in types]
    lines = [kind for kind in LINE_TYPES if kind in types]
    center = (BASE_UTM_M[0] + rng.uniform(-CENTER_SHIFT_M, CENTER_SHIFT_M), BASE_UTM_M[1] + rng.uniform(-CENTER_SHIFT_M, CENTER_SHIFT_M))
    net = build_network(rng, preset.segments, center)
    scene = Scene(rng, net, extra)
    budget = restriction_budget(preset.restrictions, selected)
    for index in range(preset.oks):
        forbid_type = forbid[index % len(forbid)] if forbid and index % 5 < 3 else None
        crossing_type = crossing[index % len(crossing)] if crossing and index % 5 >= 2 else None
        scene.place_oks(forbid_type, crossing_type)
        for kind in (forbid_type, crossing_type):
            if kind is not None:
                budget[kind] -= 1
    for kind in (*forbid, *crossing):
        for _ in range(budget[kind]):
            scene.place_filler(kind)
    for _ in range(preset.restrictions // 4):
        scene.place_filler("oks_existing")
    cp_tree = STRtree([item.point for item in scene.oks])
    building_tree = STRtree([item.polygon for item in scene.oks] + scene.existing)
    for kind in lines:
        for number in range(budget[kind]):
            scene.place_line(kind, number % 2 == 0, cp_tree, building_tree)
    overload(rng, net, scene.oks, capacities)

    def diameter(flow: int) -> int:
        return next(row["dn"] for row, capacity in zip(table, capacities) if capacity >= flow)

    chamber_ids = {node: f"hc-{number}" for number, node in enumerate(net.chambers, 1)}
    features = [(Point(center), {"id": "src-1", "object_type": "source"})]
    for node, chamber_id in chamber_ids.items():
        adjacent = [net.parent[node], *net.children[node]]
        features.append((Point(net.nodes[node]), {
            "id": chamber_id, "object_type": "heat_chamber",
            "diameter": max(diameter(net.flows[s]) for s in adjacent),
            "upstream_object_id": f"hn-{net.parent[node] + 1}",
        }))
    for segment, (start, _) in enumerate(net.segments):
        if start == 0:
            upstream = "src-1"
        else:
            upstream = chamber_ids.get(start, f"hn-{net.parent[start] + 1}")
        features.append((scene.lines[segment], {
            "id": f"hn-{segment + 1}", "object_type": "heat_network",
            "diameter": diameter(net.flows[segment]), "flow_tph": net.flows[segment] / 1000,
            "upstream_object_id": upstream,
        }))
    for number, polygon in enumerate(scene.existing, 1):
        features.append((polygon, {"id": f"oe-{number}", "object_type": "oks_existing"}))
    for number, (kind, geom) in enumerate(scene.restrictions, 1):
        features.append((geom, {"id": f"rs-{number}", "object_type": "restriction", "restriction_type": kind}))
    for number, item in enumerate(scene.oks, 1):
        features.append((item.polygon, {
            "id": f"oks-{number}", "object_type": "oks_future",
            "flow_tph": item.flow / 1000, "heat_load": round(item.flow / 1000 * GCAL_PER_TPH, 6),
        }))
        features.append((item.point, {"id": f"cp-{number}", "object_type": "oks_connection_point", "oks_id": f"oks-{number}"}))
    return features, scene.bounds


def pad_features(bounds: Bounds, to_wgs: Transformer, prefix: str) -> Iterator[dict[str, Any]]:
    """Endless far-away parks on a grid, generated row by row to keep memory flat."""
    x0 = bounds[2] + PAD_DISTANCE_M
    y0 = bounds[1]
    number = 0
    for row in itertools.count():
        xs, ys = [], []
        for column in range(PAD_ROW):
            left, bottom = x0 + column * PAD_CELL_M, y0 + row * PAD_CELL_M
            right, top = left + PAD_SIDE_M, bottom + PAD_SIDE_M
            xs += [left, right, right, left, left]
            ys += [bottom, bottom, top, top, bottom]
        lons, lats = to_wgs.transform(xs, ys)
        for column in range(PAD_ROW):
            number += 1
            ring = [[round(lons[i], COORD_DIGITS), round(lats[i], COORD_DIGITS)] for i in range(column * 5, column * 5 + 5)]
            yield {
                "type": "Feature",
                "geometry": {"type": "Polygon", "coordinates": [ring]},
                "properties": {"id": f"{prefix}pad-{number}", "object_type": "restriction", "restriction_type": "park"},
            }


def write_collection(out: Path, features: list[Feature], bounds: Bounds, pad_mb: float, prefix: str) -> None:
    """Writes features with every id and reference to an id prefixed, then pads the file up to pad_mb."""
    to_wgs = Transformer.from_crs("EPSG:32637", "EPSG:4326", always_xy=True)
    out.parent.mkdir(parents=True, exist_ok=True)
    limit = pad_mb * BYTES_PER_MB
    with out.open("w", encoding="utf-8") as file:
        written = file.write('{"type":"FeatureCollection","features":[\n')
        separator = ""
        for geom, properties in features:
            coordinates = mapping(transform(to_wgs.transform, geom))["coordinates"]
            geometry = {"type": geom.geom_type, "coordinates": rounded(coordinates)}
            properties = {key: prefix + value if key in ID_KEYS else value for key, value in properties.items()}
            feature = {"type": "Feature", "geometry": geometry, "properties": properties}
            written += file.write(separator + json.dumps(feature, separators=(",", ":")))
            separator = ",\n"
        if written < limit:
            for feature in pad_features(bounds, to_wgs, prefix):
                written += file.write(separator + json.dumps(feature, separators=(",", ":")))
                if written >= limit:
                    break
        file.write("\n]}\n")


def main() -> None:
    parser = argparse.ArgumentParser(prog="heatsynth", description="Генератор синтетического входного GeoJSON")
    parser.add_argument("--preset", choices=PRESETS, required=True)
    parser.add_argument("--seed", type=int, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--pad-mb", type=float, default=0.0, help="дописать далёкие полигоны park до размера файла в МБ")
    parser.add_argument(
        "--types", type=lambda value: value.split(","), default=list(DEFAULT_TYPES),
        help="какие restriction_type размещать, через запятую; по умолчанию восемь типов таблицы ТЗ",
    )
    parser.add_argument("--id-prefix", default="", help="префикс всех id и ссылок oks_id, upstream_object_id")
    args = parser.parse_args()
    rules = json.loads(RULES_PATH.read_text(encoding="utf-8"))
    unknown = [kind for kind in args.types if kind not in RESTRICTION_SHARES]
    if unknown:
        parser.error(f"генератор не знает типы ограничений {unknown}, известны: {', '.join(RESTRICTION_SHARES)}")
    missing = [kind for kind in args.types if kind not in rules["restrictions"]]
    if missing:
        parser.error(f"типов ограничений {missing} нет в {RULES_PATH}, генератор берёт оттуда отступы")
    if args.pad_mb > 0 and "park" not in args.types:
        parser.error("--pad-mb дописывает ограничения park, добавьте park в --types")
    features, bounds = generate(PRESETS[args.preset], args.seed, rules, args.types)
    write_collection(args.out, features, bounds, args.pad_mb, args.id_prefix)


if __name__ == "__main__":
    main()
