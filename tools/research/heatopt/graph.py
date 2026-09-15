"""Граф видимости по правилам сервиса (C-4, ObstacleSet и TieInFinder): зоны запрета и сближения для одного
диаметра, узлы в выпуклых вершинах зон и вдоль сторон дорог, кандидаты врезки, рёбра с длиной специальных
частей и Kспец. Проверки отрезков векторные: shapely по массивам и numpy для углов пересечения."""
import json
import math
import multiprocessing
import os
import time
from collections import defaultdict
from concurrent.futures import ProcessPoolExecutor
from dataclasses import (
    dataclass,
    field,
)
from pathlib import Path
from typing import Any

import networkx as nx
import numpy as np
import shapely
from heatcheck.model import diameter_row
from shapely import STRtree
from shapely.geometry import (
    LineString,
    Point,
    box,
)

from heatopt.scene import Scene

SIMPLIFY_M = 0.05
NODE_OFFSET_M = 0.05
TIE_TOUCH_M = 0.5
TIE_EXIT_M = 10.0
ANGLE_MARGIN_DEG = 0.01
CROSSING_STEP_M = 20.0
MARGIN_QUAD_SEGS = 16
MITRE_LIMIT = 5.0
DEFAULT_MARGIN_M = 600.0
GROUP_DISTANCE_M = 300.0
KMEANS_ITERATIONS = 20
NEAREST = 3
DIST_MARGIN_M = 0.1
SAME_TIE_M = 1.0
END_GAP_EXTRA_M = 1.0
PIPE_SEGMENTS = 2
CHUNK = 100_000
PARALLEL_PAIRS = 400_000
EPS = 1e-9
PASSABLE = ("reflex", "road", "steiner")


@dataclass
class Node:
    x: float
    y: float
    kind: str
    ref: str | None = None
    ignored: frozenset[str] = frozenset()
    capacity: int = 0
    # соседи по кольцу зоны для выпуклой вершины: касательность рёбер
    ring: tuple[tuple[float, float], tuple[float, float]] | None = None

    @property
    def xy(self) -> tuple[float, float]:
        return (self.x, self.y)


@dataclass
class Special:
    id: str
    kind: str
    rule: dict[str, Any]
    geom: Any
    polygon: bool
    zone: Any
    margin_zone: Any
    sides: np.ndarray
    k: float
    min_angle: float | None


@dataclass
class Checked:
    ok: np.ndarray
    length: np.ndarray
    special_len: np.ndarray
    k_special: np.ndarray
    cost_len: np.ndarray


def zone(geom: Any, distance: float) -> Any:
    buffered = shapely.buffer(geom, distance + SIMPLIFY_M, cap_style="square", join_style="mitre", mitre_limit=MITRE_LIMIT)
    return shapely.simplify(buffered, SIMPLIFY_M, preserve_topology=True)


def node_zone(geom: Any, distance: float) -> Any:
    return zone(geom, distance + 2 * SIMPLIFY_M + NODE_OFFSET_M)


def sides_of(geom: Any) -> np.ndarray:
    lineal = geom.boundary if geom.geom_type in ("Polygon", "MultiPolygon") else geom
    rows = []
    for part in shapely.get_parts(lineal):
        coords = np.asarray(part.coords)[:, :2]
        rows.append(np.hstack([coords[:-1], coords[1:]]))
    return np.vstack(rows) if rows else np.zeros((0, 4))


def clearance(rule: dict[str, Any], dn: int) -> float:
    value = rule["clearance_m"]
    if isinstance(value, list):
        return next(tier["m"] for tier in value if dn <= tier["dn_max"])
    return value


class Obstacles:
    """Зоны одного диаметра dn в области area."""

    def __init__(self, scene: Scene, rules: dict[str, Any], dn: int, area: Any):
        self.scene, self.rules, self.dn, self.area = scene, rules, dn, area
        half = diameter_row(rules, dn)["width_m"] / 2
        restrictions = rules["restrictions"]
        forbid, node_zones, crossing_zones = [], [], []
        specials: list[Special] = []
        prepared_area = area

        def near(geom: Any, distance: float) -> bool:
            return geom.distance(prepared_area) <= distance

        for obstacle in scene.inp.forbid:
            geom = obstacle.feature.geom
            distance = clearance(obstacle.params, dn) + half
            if near(geom, distance):
                forbid.append(zone(geom, distance))
                node_zones.append(node_zone(geom, distance))
        for obstacle in scene.inp.special:
            geom = obstacle.feature.geom
            rule = obstacle.params
            if obstacle.restriction_type == "heat_network":
                pipe_half = diameter_row(rules, obstacle.feature.props.get("diameter"))["width_m"] / 2
                distance = clearance(rule, dn) + half + pipe_half
            else:
                distance = clearance(rule, dn) + half
                if geom.geom_type not in ("Polygon", "MultiPolygon"):
                    distance += rule.get("half_width_m", 0.0)
            if not near(geom, distance):
                continue
            polygon = geom.geom_type in ("Polygon", "MultiPolygon")
            nz = node_zone(geom, distance)
            node_zones.append(nz)
            if polygon and rule.get("min_angle_deg") is not None:
                crossing_zones.append(nz)
            specials.append(Special(
                obstacle.feature.id, obstacle.restriction_type, rule, geom, polygon, zone(geom, distance),
                shapely.buffer(geom, rule["margin_m"], quad_segs=MARGIN_QUAD_SEGS) if polygon else None,
                sides_of(geom), float(rule["k_special"]), rule.get("min_angle_deg"),
            ))
        self.forbid = list(shapely.get_parts(shapely.union_all(forbid))) if forbid else []
        self.forbid_tree = STRtree(self.forbid) if self.forbid else None
        self.specials = specials
        self.special_zones = np.array([s.zone for s in specials], dtype=object)
        self.special_objects = np.array([s.geom for s in specials], dtype=object)
        self.special_tree = STRtree(self.special_zones) if specials else None
        self.node_zones = node_zones
        self.crossing_zones = crossing_zones

    def inside_any(self, points: np.ndarray) -> np.ndarray:
        geoms = shapely.points(points)
        inside = np.zeros(len(points), dtype=bool)
        for tree in (self.forbid_tree, self.special_tree):
            if tree is not None and len(points):
                hit, _ = tree.query(geoms, predicate="intersects")
                inside[hit] = True
        return inside

    def reflex_nodes(self) -> list[Node]:
        seen: dict[tuple[float, float], Node] = {}
        for nz in self.node_zones:
            for polygon in shapely.get_parts(nz):
                if polygon.geom_type != "Polygon":
                    continue
                rings = [(polygon.exterior, True)] + [(hole, False) for hole in polygon.interiors]
                for ring, shell in rings:
                    coords = np.asarray(ring.coords)[:-1, :2]
                    n = len(coords)
                    if n < 3:
                        continue
                    prev, nxt = np.roll(coords, 1, axis=0), np.roll(coords, -1, axis=0)
                    cross = (coords[:, 0] - prev[:, 0]) * (nxt[:, 1] - coords[:, 1]) - (coords[:, 1] - prev[:, 1]) * (nxt[:, 0] - coords[:, 0])
                    ccw = shapely.is_ccw(ring)
                    obstacle_turn = 1 if shell == ccw else -1
                    for i in np.nonzero(np.sign(cross) == obstacle_turn)[0]:
                        key = (float(coords[i, 0]), float(coords[i, 1]))
                        seen.setdefault(key, Node(key[0], key[1], "reflex", ring=(tuple(prev[i]), tuple(nxt[i]))))
        for nz in self.crossing_zones:
            for xy in shapely.get_coordinates(shapely.segmentize(nz.boundary, CROSSING_STEP_M)):
                key = (float(xy[0]), float(xy[1]))
                if key not in seen:
                    seen[key] = Node(key[0], key[1], "road")
        nodes = list(seen.values())
        if not nodes:
            return []
        points = np.array([n.xy for n in nodes])
        keep = ~self.inside_any(points) & shapely.intersects(shapely.points(points), self.area)
        return [n for n, k in zip(nodes, keep) if k]

    def check(self, a: np.ndarray, b: np.ndarray, ignored: frozenset[str] = frozenset()) -> Checked:
        """Допустимость и вес отрезков a[i]–b[i]; ignored — объекты, которых касается конец a (врезка)."""
        m = len(a)
        length = np.hypot(b[:, 0] - a[:, 0], b[:, 1] - a[:, 1])
        ok = length > EPS
        special_len = np.zeros(m)
        k_special = np.ones(m)
        cost_len = length.copy()
        if m == 0:
            return Checked(ok, length, special_len, k_special, cost_len)
        lines = shapely.linestrings(np.stack([a, b], axis=1))
        if self.forbid_tree is not None:
            hit, _ = self.forbid_tree.query(lines, predicate="intersects")
            ok[hit] = False
        if ignored:
            ok &= self._leaves_network(a, b, length, ignored) & self._exits_zone(a, b, length, ignored)
        if self.special_tree is None:
            return Checked(ok, length, special_len, k_special, cost_len)
        idx = np.nonzero(ok)[0]
        rows, sp = self.special_tree.query(lines[idx], predicate="intersects")
        rows = idx[rows]
        if ignored and len(rows):
            skip = np.array([self.specials[s].id in ignored for s in sp], dtype=bool)
            rows, sp = rows[~skip], sp[~skip]
        if not len(rows):
            return Checked(ok, length, special_len, k_special, cost_len)
        crossing = shapely.intersects(lines[rows], self.special_objects[sp])
        ok[rows[~crossing]] = False
        rows, sp = rows[crossing], sp[crossing]
        if not len(rows):
            return Checked(ok, length, special_len, k_special, cost_len)
        spans: dict[int, list[tuple[float, float, float]]] = defaultdict(list)
        order = np.argsort(sp, kind="stable")
        rows, sp = rows[order], sp[order]
        bounds = np.flatnonzero(np.diff(sp)) + 1
        for chunk_rows, chunk_sp in zip(np.split(rows, bounds), np.split(sp, bounds)):
            special = self.specials[chunk_sp[0]]
            allowed, hits = self._crossing(a[chunk_rows], b[chunk_rows], special)
            ok[chunk_rows[~allowed]] = False
            good = chunk_rows[allowed]
            if not len(good):
                continue
            if special.polygon:
                parts, owner = shapely.get_parts(shapely.intersection(lines[good], special.margin_zone), return_index=True)
                keep = (shapely.get_type_id(parts) == 1) & (shapely.length(parts) > 0)
                parts, part_rows = parts[keep], good[owner[keep]]
                s0 = shapely.line_locate_point(lines[part_rows], shapely.get_point(parts, 0))
                s1 = shapely.line_locate_point(lines[part_rows], shapely.get_point(parts, -1))
                for row, lo, hi in zip(part_rows.tolist(), np.minimum(s0, s1).tolist(), np.maximum(s0, s1).tolist()):
                    spans[row].append((lo, hi, special.k))
            else:
                margin = special.rule["margin_m"]
                for row, at_list in zip(good, hits[allowed]):
                    for at in at_list:
                        if ignored and (at <= TIE_TOUCH_M or at >= length[row] - TIE_TOUCH_M):
                            continue
                        spans[row].append((max(0.0, at - margin), min(length[row], at + margin), special.k))
        for row, items in spans.items():
            if not ok[row]:
                continue
            items.sort()
            merged: list[list[float]] = []
            for start, end, k in items:
                if merged and start <= merged[-1][1] + 1e-6:
                    merged[-1][1] = max(merged[-1][1], end)
                    merged[-1][2] = max(merged[-1][2], k)
                else:
                    merged.append([start, end, k])
            special_len[row] = sum(e - s for s, e, _ in merged)
            k_special[row] = max(k for _, _, k in merged)
            cost_len[row] = length[row] + sum((k - 1) * (e - s) for s, e, k in merged)
        return Checked(ok, length, special_len, k_special, cost_len)

    def _crossing(self, a: np.ndarray, b: np.ndarray, special: Special) -> tuple[np.ndarray, np.ndarray]:
        """Правило crossingAllowed сервиса и расстояния до точек пересечения со сторонами вдоль отрезка."""
        sides = special.sides
        r = b - a
        s = sides[:, 2:] - sides[:, :2]
        qp_x = sides[None, :, 0] - a[:, None, 0]
        qp_y = sides[None, :, 1] - a[:, None, 1]
        denom = r[:, None, 0] * s[None, :, 1] - r[:, None, 1] * s[None, :, 0]
        t_num = qp_x * s[None, :, 1] - qp_y * s[None, :, 0]
        u_num = qp_x * r[:, None, 1] - qp_y * r[:, None, 0]
        parallel = np.abs(denom) < EPS
        with np.errstate(divide="ignore", invalid="ignore"):
            t = np.where(parallel, np.nan, t_num / denom)
            u = np.where(parallel, np.nan, u_num / denom)
        proper = ~parallel & (t >= -EPS) & (t <= 1 + EPS) & (u >= -EPS) & (u <= 1 + EPS)
        r_len = np.hypot(r[:, 0], r[:, 1])
        s_len = np.hypot(s[:, 0], s[:, 1])
        collinear = parallel & (np.abs(t_num) < EPS * np.maximum(r_len[:, None] * s_len[None, :], 1.0))
        if collinear.any():
            rr = np.maximum((r * r).sum(axis=1), EPS)
            t0 = (qp_x * r[:, None, 0] + qp_y * r[:, None, 1]) / rr[:, None]
            t1 = ((sides[None, :, 2] - a[:, None, 0]) * r[:, None, 0] + (sides[None, :, 3] - a[:, None, 1]) * r[:, None, 1]) / rr[:, None]
            overlap = (np.minimum(t0, t1) <= 1 + EPS) & (np.maximum(t0, t1) >= -EPS)
            collinear &= overlap
        allowed = proper.any(axis=1) & ~collinear.any(axis=1)
        if special.min_angle is not None:
            cross = np.abs(denom)
            dot = np.abs(r[:, None, 0] * s[None, :, 0] + r[:, None, 1] * s[None, :, 1])
            angle = np.degrees(np.arctan2(cross, dot))
            allowed &= ~(proper & (angle < special.min_angle + ANGLE_MARGIN_DEG)).any(axis=1)
        hits = np.empty(len(a), dtype=object)
        if not special.polygon:
            for i in range(len(a)):
                hits[i] = sorted(float(v) * r_len[i] for v in t[i][proper[i]])
        return allowed, hits

    def _exits_zone(self, a: np.ndarray, b: np.ndarray, length: np.ndarray, ignored: frozenset[str]) -> np.ndarray:
        """Дальше TIE_EXIT_M от врезки отрезок вне зоны отступа её труб: валидатор прощает сближение только участку,
        который начинается во врезке, а разрез по диаметру или спецзоне этот участок делит."""
        zones = [s.zone for s in self.specials if s.id in ignored]
        ok = np.ones(len(a), dtype=bool)
        far = length > TIE_EXIT_M
        if not zones or not far.any():
            return ok
        rows = np.nonzero(far)[0]
        start = a[rows] + (b[rows] - a[rows]) * (TIE_EXIT_M / length[rows])[:, None]
        tail = shapely.linestrings(np.stack([start, b[rows]], axis=1))
        ok[rows] = ~shapely.intersects(tail, shapely.union_all(zones))
        return ok

    def _leaves_network(self, a: np.ndarray, b: np.ndarray, length: np.ndarray, ignored: frozenset[str]) -> np.ndarray:
        """Отрезок от врезки дальше 0,5 м не пересекает участки, которых врезка касается (leavesNetwork)."""
        pipes = [self.scene.pipes[i].geom for i in ignored if i in self.scene.pipes]
        ok = length > TIE_TOUCH_M
        if not pipes:
            return ok
        with np.errstate(divide="ignore", invalid="ignore"):
            start = a + (b - a) * (TIE_TOUCH_M / np.maximum(length, EPS))[:, None]
        away = shapely.linestrings(np.stack([start, b], axis=1))
        return ok & ~shapely.intersects(away, shapely.union_all(pipes))


def tie_points(scene: Scene) -> list[Point]:
    """Точки поиска врезок как в сервисе: точки подключения, центры групп близких ОКС и половин k-means."""
    points = [t.point for t in scene.terminals]
    for group in groups(scene):
        if len(group) > 1:
            points.append(center(group))
        if len(group) > 2:
            points += [center(part) for part in kmeans(group) if len(part) > 1]
    return points


def groups(scene: Scene) -> list[list[Point]]:
    cps = [t.point for t in scene.terminals]
    parent = list(range(len(cps)))

    def find(i: int) -> int:
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    for i in range(len(cps)):
        for j in range(i + 1, len(cps)):
            if cps[i].distance(cps[j]) <= GROUP_DISTANCE_M:
                parent[find(i)] = find(j)
    by_root: dict[int, list[Point]] = defaultdict(list)
    for i, cp in enumerate(cps):
        by_root[find(i)].append(cp)
    return list(by_root.values())


def center(points: list[Point]) -> Point:
    return Point(sum(p.x for p in points) / len(points), sum(p.y for p in points) / len(points))


def kmeans(points: list[Point]) -> list[list[Point]]:
    a, b = max(((p, q) for p in points for q in points), key=lambda pq: pq[0].distance(pq[1]))
    first, second = points, []
    for _ in range(KMEANS_ITERATIONS):
        first = [p for p in points if p.distance(a) <= p.distance(b)]
        second = [p for p in points if p.distance(a) > p.distance(b)]
        if not first or not second:
            break
        a, b = center(first), center(second)
    return [part for part in (first, second) if part]


def tie_candidates(scene: Scene, rules: dict[str, Any], dn: int, points: list[Point]) -> list[Node]:
    """TieInFinder сервиса: три ближайшие камеры и проекции на три ближайших участка для каждой точки."""
    chamber_rule = rules["chamber_rule"]
    node_limit = min(chamber_rule["max_segments"], chamber_rule["max_branches"] + 1)
    links = {cid: len(scene.inp.chamber_links.get(cid, [])) for cid in scene.chambers}
    pipes = list(scene.pipes.values())
    chambers = list(scene.chambers.values())
    pipe_tree = STRtree([p.geom for p in pipes])

    def touching(point: Point) -> frozenset[str]:
        return frozenset(pipes[i].id for i in pipe_tree.query(point, predicate="dwithin", distance=TIE_TOUCH_M))

    def chamber_node(chamber) -> Node | None:
        capacity = node_limit - links[chamber.id]
        if capacity <= 0:
            return None
        return Node(chamber.geom.x, chamber.geom.y, "tie_chamber", chamber.id, touching(chamber.geom), capacity)

    width = diameter_row(rules, dn)["width_m"]
    network_rule = rules["restrictions"]["heat_network"]
    found: list[Node] = []
    for point in points:
        for chamber in sorted(chambers, key=lambda c: c.geom.distance(point))[:NEAREST]:
            node = chamber_node(chamber)
            if node is not None and not any(n.kind == "tie_chamber" and n.ref == node.ref for n in found):
                found.append(node)
        for pipe in sorted(pipes, key=lambda p: p.geom.distance(point))[:NEAREST]:
            line = pipe.geom
            gap = clearance(network_rule, dn) + width / 2 + diameter_row(rules, pipe.props["diameter"])["width_m"] / 2 + END_GAP_EXTRA_M
            if line.length <= 2 * gap:
                continue
            tie = line.interpolate(max(gap, min(line.length - gap, line.project(point))))
            best = None
            for chamber in chambers:
                distance = chamber.geom.distance(tie)
                if distance <= chamber_rule["max_dist_m"] + DIST_MARGIN_M and links[chamber.id] + 1 <= chamber_rule["max_segments"]:
                    if best is None or distance < best.geom.distance(tie):
                        best = chamber
            if best is not None:
                node = chamber_node(best)
                if node is not None and not any(n.kind == "tie_chamber" and n.ref == node.ref for n in found):
                    found.append(node)
                continue
            if any(n.ref == pipe.id and math.dist(n.xy, (tie.x, tie.y)) <= SAME_TIE_M for n in found):
                continue
            found.append(Node(tie.x, tie.y, "tie_pipe", pipe.id, touching(tie), node_limit - PIPE_SEGMENTS))
    return found


@dataclass
class VisGraph:
    scene: Scene
    rules: dict[str, Any]
    dn: int
    area: Any
    obstacles: Obstacles
    nodes: list[Node]
    u: np.ndarray
    v: np.ndarray
    length: np.ndarray
    special_len: np.ndarray
    k_special: np.ndarray
    cost_len: np.ndarray
    build_seconds: float = 0.0
    _adj: list[list[tuple[int, int]]] | None = field(default=None, repr=False)

    @classmethod
    def build(cls, scene: Scene, rules: dict[str, Any] | None = None, dn_guess: int | None = None,
              margin: float = DEFAULT_MARGIN_M, points: list[Point] | None = None,
              extra_nodes: list[Node] = (), tangent: bool = False) -> "VisGraph":
        """dn_guess по умолчанию — на ступень выше диаметра по суммарному расходу сцены (C-4). tangent оставляет
        у выпуклых вершин только касательные рёбра: кратчайшие пути сохраняются, рёбер в разы меньше."""
        started = time.perf_counter()
        rules = rules or scene.rules
        dn = dn_guess or default_dn(scene, rules)
        ties = tie_candidates(scene, rules, dn, points if points is not None else tie_points(scene))
        anchors = [(t.point.x, t.point.y) for t in scene.terminals] + [n.xy for n in ties] + [n.xy for n in extra_nodes]
        xs, ys = [p[0] for p in anchors], [p[1] for p in anchors]
        area = box(min(xs) - margin, min(ys) - margin, max(xs) + margin, max(ys) + margin)
        obstacles = Obstacles(scene, rules, dn, area)
        nodes = [Node(t.point.x, t.point.y, "terminal", t.cp_id) for t in scene.terminals] + ties + list(extra_nodes)
        nodes += obstacles.reflex_nodes()
        graph = cls(scene, rules, dn, area, obstacles, nodes, *connect(obstacles, nodes, tangent))
        graph.build_seconds = time.perf_counter() - started
        return graph

    @property
    def adj(self) -> list[list[tuple[int, int]]]:
        if self._adj is None:
            adj: list[list[tuple[int, int]]] = [[] for _ in self.nodes]
            for e, (i, j) in enumerate(zip(self.u.tolist(), self.v.tolist())):
                adj[i].append((j, e))
                adj[j].append((i, e))
            self._adj = adj
        return self._adj

    def kinds(self, kind: str) -> list[int]:
        return [i for i, n in enumerate(self.nodes) if n.kind == kind]

    def terminal_nodes(self) -> dict[str, int]:
        return {n.ref: i for i, n in enumerate(self.nodes) if n.kind == "terminal"}

    def tie_nodes(self) -> list[int]:
        return [i for i, n in enumerate(self.nodes) if n.kind in ("tie_chamber", "tie_pipe")]

    def to_networkx(self, weight: str = "cost_len") -> nx.Graph:
        g = nx.Graph()
        for i, node in enumerate(self.nodes):
            g.add_node(i, kind=node.kind, x=node.x, y=node.y)
        values = getattr(self, weight)
        g.add_weighted_edges_from(zip(self.u.tolist(), self.v.tolist(), values.tolist()))
        return g

    def segment(self, a: tuple[float, float], b: tuple[float, float], ignored: frozenset[str] = frozenset()) -> tuple[float, float, float, float] | None:
        """(длина, длина специальных частей, Kспец, взвешенная длина) отрезка a–b или None, если он недопустим."""
        checked = self.obstacles.check(np.array([a], dtype=float), np.array([b], dtype=float), ignored)
        if not checked.ok[0]:
            return None
        return float(checked.length[0]), float(checked.special_len[0]), float(checked.k_special[0]), float(checked.cost_len[0])

    def visible(self, xy: tuple[float, float], targets: np.ndarray | None = None, ignored: frozenset[str] = frozenset()) -> tuple[np.ndarray, Checked]:
        """Узлы графа (из targets или все), видимые из точки xy, и веса отрезков до них."""
        idx = np.arange(len(self.nodes)) if targets is None else np.asarray(targets)
        b = np.array([self.nodes[i].xy for i in idx], dtype=float).reshape(-1, 2)
        a = np.repeat(np.array([xy], dtype=float), len(idx), axis=0)
        checked = self.obstacles.check(a, b, ignored)
        keep = checked.ok
        return idx[keep], Checked(keep[keep], checked.length[keep], checked.special_len[keep], checked.k_special[keep], checked.cost_len[keep])

    def to_json(self, path: Path) -> None:
        data = {
            "dn": self.dn,
            "build_seconds": round(self.build_seconds, 3),
            "nodes": [[round(n.x, 3), round(n.y, 3), n.kind, n.ref, sorted(n.ignored), n.capacity] for n in self.nodes],
            "edges": [[int(i), int(j), round(float(l), 3), round(float(s), 3), float(k), round(float(c), 3)]
                      for i, j, l, s, k, c in zip(self.u, self.v, self.length, self.special_len, self.k_special, self.cost_len)],
        }
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        Path(path).write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")


def default_dn(scene: Scene, rules: dict[str, Any]) -> int:
    dns = [d["dn"] for d in rules["diameters"]]
    by_flow = next((d["dn"] for d in rules["diameters"] if d["capacity_tph"] >= scene.total_flow()), dns[-1])
    index = dns.index(by_flow)
    return dns[min(index + 1, len(dns) - 1)]


def smallest_dn(scene: Scene, rules: dict[str, Any]) -> int:
    """Диаметр графа нижней оценки: наименьший среди расходов отдельных ОКС (C-4)."""
    flows = [t.flow for t in scene.terminals] or [0.0]
    return next(d["dn"] for d in rules["diameters"] if d["capacity_tph"] >= min(flows))


_SHARED: dict[str, Any] = {}


def _check_chunk(task: tuple[np.ndarray, np.ndarray, frozenset[str]]) -> tuple[np.ndarray, ...]:
    ii, jj, ignored = task
    obstacles, nodes, xy, tangent = _SHARED["obstacles"], _SHARED["nodes"], _SHARED["xy"], _SHARED["tangent"]
    if tangent:
        keep = tangent_mask(nodes, xy, ii, jj)
        ii, jj = ii[keep], jj[keep]
    checked = obstacles.check(xy[ii], xy[jj], ignored)
    ok = checked.ok
    return ii[ok], jj[ok], checked.length[ok], checked.special_len[ok], checked.k_special[ok], checked.cost_len[ok]


def connect(obstacles: Obstacles, nodes: list[Node], tangent: bool) -> tuple[np.ndarray, ...]:
    """Все допустимые пары узлов. Терминал–терминал и врезка–врезка не соединяются: терминал только лист,
    врезка только корень. Больше PARALLEL_PAIRS пар проверяются в дочерних процессах (fork)."""
    xy = np.array([node.xy for node in nodes], dtype=float)
    kinds = np.array([node.kind for node in nodes])
    is_tie = np.isin(kinds, ("tie_chamber", "tie_pipe"))
    is_terminal = kinds == "terminal"
    tasks = []
    plain = np.nonzero(~is_tie)[0]
    if len(plain) > 1:
        a, b = np.triu_indices(len(plain), k=1)
        i, j = plain[a], plain[b]
        keep = ~(is_terminal[i] & is_terminal[j])
        i, j = i[keep], j[keep]
        tasks += [(i[s:s + CHUNK], j[s:s + CHUNK], frozenset()) for s in range(0, len(i), CHUNK)]
    for t in np.nonzero(is_tie)[0]:
        tasks.append((np.full(len(plain), t), plain, nodes[t].ignored))
    _SHARED.update(obstacles=obstacles, nodes=nodes, xy=xy, tangent=tangent)
    pairs = sum(len(task[0]) for task in tasks)
    workers = int(os.environ.get("HEATOPT_GRAPH_WORKERS", min(8, os.cpu_count() or 1)))
    if pairs > PARALLEL_PAIRS and workers > 1:
        with ProcessPoolExecutor(max_workers=workers, mp_context=multiprocessing.get_context("fork")) as pool:
            results = list(pool.map(_check_chunk, tasks))
    else:
        results = [_check_chunk(task) for task in tasks]
    _SHARED.clear()
    arrays = [np.concatenate([r[k] for r in results]) if results else np.zeros(0) for k in range(6)]
    return (arrays[0].astype(np.int32), arrays[1].astype(np.int32), *arrays[2:])


def tangent_mask(nodes: list[Node], xy: np.ndarray, i: np.ndarray, j: np.ndarray) -> np.ndarray:
    keep = np.ones(len(i), dtype=bool)
    for ends, others in ((i, j), (j, i)):
        rings = [nodes[k].ring for k in ends]
        has = np.array([r is not None for r in rings])
        if not has.any():
            continue
        sel = np.nonzero(has)[0]
        prev = np.array([rings[k][0] for k in sel])
        nxt = np.array([rings[k][1] for k in sel])
        v = xy[ends[sel]]
        d = xy[others[sel]] - v
        c1 = d[:, 0] * (prev[:, 1] - v[:, 1]) - d[:, 1] * (prev[:, 0] - v[:, 0])
        c2 = d[:, 0] * (nxt[:, 1] - v[:, 1]) - d[:, 1] * (nxt[:, 0] - v[:, 0])
        keep[sel] &= c1 * c2 >= -EPS
    return keep
