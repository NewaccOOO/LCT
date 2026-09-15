"""Общая основа кандидатов: веса рёбер в единицах S, Дейкстра по графу видимости, дерево Такахаши–Мацуямы
с обрезкой маршрута в первой точке касания дерева (как TreeBuilder сервиса), быстрая оценка реконструкции
и базовый лес по стратегиям сервиса (каждый ОКС отдельно, группы близких ОКС, половины k-means)."""
import heapq
import math
from collections import defaultdict
from dataclasses import (
    dataclass,
    field,
)
from typing import Any

import numpy as np
import shapely
from heatcheck.model import (
    NODE_TOL_M,
    chamber_cost,
    diameter_for,
    diameter_row,
)
from shapely import STRtree
from shapely.geometry import (
    LineString,
    Point,
)

from heatopt import model
from heatopt.graph import (
    PASSABLE,
    VisGraph,
    groups,
    kmeans,
)
from heatopt.model import (
    Solution,
    TieIn,
)
from heatopt.scene import Scene

TOUCH_M = 0.05
MIN_PIECE_M = 1.0
JUNCTION_SPECIAL_GAP_M = 1.0
MAX_BRANCHES = 3
MAX_RETRIES = 6
SHARED_ROOT_CLIP_M = 0.15
FOREIGN_TIE_M = 0.6
JUNCTION_CLIP_M = 0.15
TURNS_PER_OBSTACLE = 3
TURNS_BASE = 4
BLOCKED_CACHE_SIZE = 4096
JUNCTION_REACH_M = 4.0
TOP_TIES = 3
PREFILTER = 2
INF = math.inf
XY = tuple[float, float]



def dn_for_flow(rules: dict[str, Any], flow: float) -> int:
    return diameter_for(rules, flow) or rules["diameters"][-1]["dn"]


def rub(rules: dict[str, Any], value: float) -> float:
    """Рубли в единицах S."""
    return rules["score"]["w_cost"] * value / rules["score"]["cost_base"]


def meters(rules: dict[str, Any], value: float) -> float:
    """Метры протяжённости в единицах S."""
    return rules["score"]["w_length"] * value / rules["score"]["length_base_m"]


def edge_weights(graph: VisGraph, dn: int) -> list[float]:
    """Вес ребра графа в единицах S при диаметре dn: длина в L и стоимость с Kспец в C."""
    cache = graph.__dict__.setdefault("_weights", {})
    if dn not in cache:
        price = diameter_row(graph.rules, dn)["new_rub_m"]
        values = meters(graph.rules, 1.0) * graph.length + rub(graph.rules, price) * graph.cost_len
        cache[dn] = values.tolist()
    return cache[dn]


def edge_lines(graph: VisGraph) -> np.ndarray:
    cache = graph.__dict__
    if "_lines" not in cache:
        xy = np.array([n.xy for n in graph.nodes], dtype=float)
        cache["_lines"] = shapely.linestrings(np.stack([xy[graph.u], xy[graph.v]], axis=1))
        cache["_line_tree"] = STRtree(cache["_lines"])
    return cache["_lines"]


def dijkstra(graph: VisGraph, source: int, weights: list[float], blocked_nodes: set[int] = frozenset(),
             blocked_edges: set[int] = frozenset(), targets: set[int] | None = None) -> tuple[list[float], list[int]]:
    """Расстояния от source; через терминалы и врезки путь не проходит, они только концы."""
    n = len(graph.nodes)
    dist = [INF] * n
    pred = [-1] * n
    passable = graph.__dict__.setdefault("_passable", [node.kind in PASSABLE for node in graph.nodes])
    adj = graph.adj
    dist[source] = 0.0
    heap = [(0.0, source)]
    left = set(targets) if targets is not None else None
    while heap:
        d, v = heapq.heappop(heap)
        if d > dist[v]:
            continue
        if left is not None:
            left.discard(v)
            if not left:
                break
        if v != source and not passable[v]:
            continue
        for w, e in adj[v]:
            if w in blocked_nodes or e in blocked_edges:
                continue
            nd = d + weights[e]
            if nd < dist[w]:
                dist[w] = nd
                pred[w] = e
                heapq.heappush(heap, (nd, w))
    return dist, pred


def path_nodes(graph: VisGraph, pred: list[int], source: int, target: int) -> list[int]:
    path = [target]
    while path[-1] != source:
        e = pred[path[-1]]
        if e < 0:
            return []
        a, b = int(graph.u[e]), int(graph.v[e])
        path.append(a if b == path[-1] else b)
    return path[::-1]



@dataclass
class TreeSeg:
    a: int
    b: int


@dataclass
class Tree:
    """Дерево одной врезки: точки (xy) и прямые отрезки между ними от корня к листьям."""
    root_node: int
    tie: TieIn
    capacity: int
    points: list[XY] = field(default_factory=list)
    graph_node: dict[int, int] = field(default_factory=dict)
    segs: list[TreeSeg] = field(default_factory=list)
    children: dict[int, int] = field(default_factory=lambda: defaultdict(int))
    terminals: dict[str, int] = field(default_factory=dict)
    unconnected: list[str] = field(default_factory=list)

    def point_id(self, xy: XY, graph_node: int | None = None) -> int:
        for i, p in enumerate(self.points):
            if math.dist(p, xy) <= TOUCH_M:
                return i
        self.points.append(xy)
        if graph_node is not None:
            self.graph_node[len(self.points) - 1] = graph_node
        return len(self.points) - 1

    def lines(self) -> list[LineString]:
        return [LineString([self.points[s.a], self.points[s.b]]) for s in self.segs]

    def graph_nodes(self) -> set[int]:
        return set(self.graph_node.values())

    def polylines(self) -> list[list[XY]]:
        return [[self.points[s.a], self.points[s.b]] for s in self.segs]


def blocked_by(graph: VisGraph, others: list[Tree], root_node: int | None = None) -> tuple[set[int], set[int]]:
    """Рёбра графа, которые касаются чужих деревьев, и узлы чужих деревьев. У деревьев с той же камерой врезки
    окрестность общего корня не считается касанием."""
    edges, nodes = set(), set()
    for tree in others:
        shared = tree.root_node == root_node
        edges |= tree_blocked_edges(graph, tree, shared)
        nodes |= {n for i, n in tree.graph_node.items() if i != 0 or not shared}
    return edges, nodes


def tree_blocked_edges(graph: VisGraph, tree: Tree, shared: bool) -> set[int]:
    """Рёбра графа у одного дерева; кэш по геометрии дерева: лес перестраивает одни и те же соседние деревья."""
    key = (tree.points[0], tuple((tree.points[seg.a], tree.points[seg.b]) for seg in tree.segs), shared)
    cache = graph.__dict__.setdefault("_blocked_cache", {})
    if key in cache:
        return cache[key]
    clip = Point(tree.points[0]).buffer(SHARED_ROOT_CLIP_M) if shared else None
    lines = [line.difference(clip) if clip is not None else line for line in tree.lines()]
    if not shared:
        # валидатор не видит пересечения трубы у чужой врезки: к ней не подходим ближе 0,5 м
        lines.append(Point(tree.points[0]).buffer(FOREIGN_TIE_M))
    lines = [line for line in lines if not line.is_empty]
    hit = set()
    if lines:
        edge_lines(graph)
        _, found = graph.__dict__["_line_tree"].query(np.array(lines, dtype=object), predicate="dwithin", distance=2 * TOUCH_M)
        hit = set(np.unique(found).tolist())
    if len(cache) > BLOCKED_CACHE_SIZE:
        cache.clear()
    cache[key] = hit
    return hit


def junction_allowed(graph: VisGraph, xy: XY) -> bool:
    """Камера ветвления не в зоне сближения объекта специального прохода и не в полосе специального участка."""
    point = Point(xy)
    obstacles = graph.obstacles
    if obstacles.special_tree is None:
        return True
    for s in obstacles.special_tree.query(point, predicate="dwithin", distance=JUNCTION_REACH_M):
        special = obstacles.specials[s]
        if special.zone.contains(point) or special.geom.distance(point) <= special.rule["margin_m"] + JUNCTION_SPECIAL_GAP_M:
            return False
    return True


class TreeBuilder:
    def __init__(self, scene: Scene, graph: VisGraph, flows: dict[str, float] | None = None):
        self.scene = scene
        self.graph = graph
        self.rules = graph.rules
        self.terminal_node = graph.terminal_nodes()
        self.flow = flows or {t.cp_id: t.flow for t in scene.terminals}
        self.free_tables: dict[tuple[str, int], tuple[list[float], list[int]]] = {}
        self.foreign: list[LineString] = []
        self._turn_polygons: STRtree | None = None

    def tie_of(self, node: int) -> TieIn:
        n = self.graph.nodes[node]
        return TieIn(n.ref, "heat_chamber" if n.kind == "tie_chamber" else "heat_network", n.xy)

    def table(self, cp: str, dn: int, blocked_nodes: set[int], blocked_edges: set[int], weights_for) -> tuple[list[float], list[int]]:
        weights = weights_for(dn) if weights_for else edge_weights(self.graph, dn)
        if blocked_nodes or blocked_edges or weights_for:
            return dijkstra(self.graph, self.terminal_node[cp], weights, blocked_nodes, blocked_edges)
        key = (cp, dn)
        if key not in self.free_tables:
            self.free_tables[key] = dijkstra(self.graph, self.terminal_node[cp], weights)
        return self.free_tables[key]

    def build(self, tie_node: int, cps: list[str], others: list["Tree"] = (), weight_dn: dict[str, int] | None = None,
              weights_for=None, root_capacity: int | None = None) -> "Tree":
        """Такахаши–Мацуяма: на каждом шаге присоединяется ОКС с самым лёгким путём до дерева. weights_for(dn)
        подменяет веса рёбер (штраф за изгиб, коридоры, перевзвешивание по расходу)."""
        graph = self.graph
        node = graph.nodes[tie_node]
        # capacity — свободные места для ветвей у корня: у камеры врезки их делят все деревья этой камеры
        tree = Tree(tie_node, self.tie_of(tie_node), node.capacity if root_capacity is None else root_capacity)
        tree.point_id(node.xy, tie_node)
        self.foreign = [line for other in others for line in other.lines()]
        blocked_edges, blocked_nodes = blocked_by(graph, list(others), tie_node)
        blocked_nodes.discard(tie_node)
        remaining = [cp for cp in cps if cp in self.terminal_node]
        tree.unconnected += [cp for cp in cps if cp not in self.terminal_node]
        tables: dict[str, tuple[list[float], list[int]]] = {}
        extra: dict[str, set[int]] = defaultdict(set)
        retries: dict[str, int] = defaultdict(int)
        while remaining:
            best = None
            for cp in remaining:
                if cp not in tables:
                    dn = (weight_dn or {}).get(cp) or dn_for_flow(self.rules, self.flow[cp])
                    tables[cp] = self.table(cp, dn, blocked_nodes, blocked_edges | extra[cp], weights_for)
                dist = tables[cp][0]
                for pid, gnode in tree.graph_node.items():
                    if dist[gnode] < INF and (best is None or dist[gnode] < best[0]) and self.attachable(tree, pid):
                        best = (dist[gnode], cp, gnode)
            if best is None:
                tree.unconnected += remaining
                break
            _, cp, target = best
            nodes = path_nodes(graph, tables[cp][1], self.terminal_node[cp], target)
            failed = self.attach(tree, cp, nodes)
            if failed is None:
                remaining.remove(cp)
                continue
            retries[cp] += 1
            if retries[cp] > MAX_RETRIES:
                tree.unconnected.append(cp)
                remaining.remove(cp)
                continue
            extra[cp] |= failed
            del tables[cp]
        self.straighten(tree)
        return tree

    def straighten(self, tree: "Tree") -> None:
        """Как Router.straighten сервиса: убирает вершины с отклонением меньше 3° и у отрезков короче метра, если
        спрямлённый отрезок допустим. Узлы ветвления, корень и терминалы остаются."""
        leaves = set(tree.terminals.values())
        changed = True
        while changed:
            changed = False
            parent = {seg.b: i for i, seg in enumerate(tree.segs)}
            child = defaultdict(list)
            for i, seg in enumerate(tree.segs):
                child[seg.a].append(i)
            for pid, i in parent.items():
                if pid == 0 or pid in leaves or len(child[pid]) != 1:
                    continue
                up, down = tree.segs[i], tree.segs[child[pid][0]]
                a, b, c = tree.points[up.a], tree.points[pid], tree.points[down.b]
                angle = model.deflection_deg(a, b, c)
                short = math.dist(a, b) < MIN_PIECE_M or math.dist(b, c) < MIN_PIECE_M
                if (angle is None or angle < model.MIN_TURN_DEG or short) and self.segment(tree, a, c) is not None \
                        and self.clear(tree, a, c, {i, child[pid][0]}):
                    tree.segs[i] = TreeSeg(up.a, down.b)
                    tree.segs.pop(child[pid][0])
                    tree.graph_node.pop(pid, None)
                    tree.children.pop(pid, None)
                    changed = True
                    break

    def clear(self, tree: "Tree", a: XY, c: XY, skip: set[int]) -> bool:
        """Спрямлённый отрезок не касается остальных участков дерева и чужих деревьев вне своих концов."""
        line = LineString([a, c]).difference(shapely.union_all([Point(a).buffer(JUNCTION_CLIP_M), Point(c).buffer(JUNCTION_CLIP_M)]))
        if line.is_empty:
            return True
        others = [seg_line for k, seg_line in enumerate(tree.lines()) if k not in skip] + self.foreign
        return all(line.distance(other) > 2 * TOUCH_M for other in others)

    def turns_ok(self, tree: "Tree") -> bool:
        """Правило geometry: на пути от врезки до точки подключения поворотов не больше 3k + 4."""
        if self._turn_polygons is None:
            polygons = [o.feature.geom for o in self.scene.inp.forbid + self.scene.inp.special
                        if o.feature.geom.geom_type in ("Polygon", "MultiPolygon") and (o.params["rule"] == "forbid" or "min_angle_deg" in o.params)]
            self._turn_polygons = STRtree(polygons)
        parent = {seg.b: seg.a for seg in tree.segs}
        for pid in tree.terminals.values():
            chain = [pid]
            while chain[-1] != 0 and chain[-1] in parent:
                chain.append(parent[chain[-1]])
            coords = [tree.points[k] for k in reversed(chain)]
            turns = sum(1 for k in range(1, len(coords) - 1)
                        if (model.deflection_deg(coords[k - 1], coords[k], coords[k + 1]) or 0.0) >= model.MIN_TURN_DEG)
            crossed = len(self._turn_polygons.query(LineString([coords[0], coords[-1]]), predicate="intersects"))
            if turns > TURNS_PER_OBSTACLE * crossed + TURNS_BASE:
                return False
        return True

    def attachable(self, tree: "Tree", pid: int) -> bool:
        if pid == 0:
            return tree.children[0] < tree.capacity
        gnode = tree.graph_node.get(pid)
        if gnode is not None and self.graph.nodes[gnode].kind not in PASSABLE:
            return False
        return tree.children[pid] < MAX_BRANCHES and junction_allowed(self.graph, tree.points[pid])

    def attach(self, tree: "Tree", cp: str, nodes: list[int]) -> set[int] | None:
        """Путь от терминала режется в первой точке касания дерева, там ставится узел ветвления. Возвращает None
        при успехе или рёбра графа, которые при повторе нужно обойти."""
        graph = self.graph
        coords = [graph.nodes[n].xy for n in nodes]
        lines = tree.lines()
        index = STRtree(lines) if lines else None
        cut = None
        for i in range(len(coords) - 1):
            seg = LineString([coords[i], coords[i + 1]])
            hits = []
            if index is not None:
                for j in index.query(seg, predicate="dwithin", distance=TOUCH_M):
                    # у совпадающих отрезков shortest_line даёт любую точку наложения, нужна первая вдоль пути
                    near = shapely.get_coordinates(seg.intersection(lines[j].buffer(TOUCH_M)))
                    if len(near):
                        hits.append((min(seg.project(Point(xy)) for xy in near), int(j)))
            elif seg.distance(Point(tree.points[0])) <= TOUCH_M:
                hits.append((seg.project(Point(tree.points[0])), -1))
            if hits:
                cut = (i, *min(hits))
                break
        if cut is None:
            return edges_between(graph, nodes[-2:])
        i, at, j = cut
        bad = edges_between(graph, nodes[i:i + 2])
        touch = LineString([coords[i], coords[i + 1]]).interpolate(at).coords[0]
        pid = 0 if j < 0 else None
        if j >= 0:
            seg = tree.segs[j]
            on = LineString([tree.points[seg.a], tree.points[seg.b]])
            pos = on.project(Point(touch))
            if pos <= MIN_PIECE_M:
                pid = seg.a
            elif pos >= on.length - MIN_PIECE_M:
                pid = seg.b
            touch = tree.points[pid] if pid is not None else on.interpolate(pos).coords[0]
        if pid is not None and not self.attachable(tree, pid):
            return bad
        if pid is None and not junction_allowed(graph, touch):
            return bad
        head = coords[:i + 1]
        while head and math.dist(head[-1], touch) < MIN_PIECE_M:
            head.pop()
        if not head or self.segment(tree, touch, head[-1]) is None or not LineString(head + [touch]).is_simple:
            return bad
        if pid is None:
            seg = tree.segs[j]
            pid = tree.point_id(touch)
            tree.segs[j] = TreeSeg(seg.a, pid)
            tree.segs.append(TreeSeg(pid, seg.b))
            tree.children[pid] += 1
        tree.children[pid] += 1
        prev = pid
        for k in range(len(head) - 1, -1, -1):
            new = tree.point_id(head[k], nodes[k])
            tree.segs.append(TreeSeg(prev, new))
            if k > 0:
                tree.children[new] += 1
            prev = new
        tree.terminals[cp] = prev
        return None

    def segment(self, tree: "Tree", a: XY, b: XY) -> tuple[float, float, float, float] | None:
        """Проверка отрезка графом; конец у корня передаётся первым: объекты врезки пропускаются только от него."""
        root = self.graph.nodes[tree.root_node]
        if math.dist(b, root.xy) <= TOUCH_M:
            a, b = b, a
        ignored = root.ignored if math.dist(a, root.xy) <= TOUCH_M else frozenset()
        return self.graph.segment(a, b, ignored)


def edges_between(graph: VisGraph, nodes: list[int]) -> set[int]:
    found = set()
    for a, b in zip(nodes, nodes[1:]):
        found |= {e for w, e in graph.adj[a] if w == b}
    return found


def solution(trees: list[Tree], meta: dict[str, Any] | None = None) -> Solution:
    edges, ties = [], []
    for tree in trees:
        if not tree.segs:
            continue
        edges += tree.polylines()
        ties.append(tree.tie)
    return Solution(edges, ties, meta=meta or {})



class Recon:
    """Стоимость реконструкции и камер врезки для набора (узел врезки, добавленный расход) без сборки варианта."""

    def __init__(self, scene: Scene, rules: dict[str, Any]):
        self.scene, self.rules = scene, rules
        self.by_id = scene.inp.by_id

    def chain(self, start_id: str) -> list[str]:
        result, seen = [], set()
        current = self.by_id.get(start_id)
        while current is not None and current.object_type != "source" and current.id not in seen:
            seen.add(current.id)
            if current.object_type == "heat_network":
                result.append(current.id)
            current = self.by_id.get(str(current.props.get("upstream_object_id")))
        return result

    def position(self, pipe_id: str, xy: XY) -> float:
        pipe = self.scene.pipes[pipe_id]
        measure = pipe.geom.project(Point(xy))
        return measure if self.scene.inp.upstream_first[pipe_id] else pipe.geom.length - measure

    def cost(self, loads: list[tuple[TieIn, float]]) -> float:
        """Рубли реконструкции участков и камер врезки плюс метры реконструкции в единицах S."""
        full: dict[str, float] = defaultdict(float)
        partial: dict[str, list[tuple[float, float]]] = defaultdict(list)
        chamber_flow: dict[str, float] = defaultdict(float)
        for tie, flow in loads:
            existing = self.by_id[tie.existing_id]
            if tie.existing_type == "heat_network":
                partial[existing.id].append((self.position(existing.id, tie.point), flow))
            else:
                chamber_flow[existing.id] += flow
            for pipe_id in self.chain(str(existing.props.get("upstream_object_id"))):
                full[pipe_id] += flow
        total_rub, total_m = 0.0, 0.0
        for pipe_id in set(full) | set(partial):
            pipe = self.scene.pipes[pipe_id]
            length = pipe.geom.length
            bounds = [0.0] + sorted(min(max(p, 0.0), length) for p, _ in partial[pipe_id]) + [length]
            existing_flow = float(pipe.props.get("flow_tph", 0))
            for start, end in zip(bounds, bounds[1:]):
                if end - start <= 0.05:
                    continue
                added = full[pipe_id] + sum(f for p, f in partial[pipe_id] if p >= end - NODE_TOL_M)
                required = diameter_for(self.rules, existing_flow + added) or self.rules["diameters"][-1]["dn"]
                if required > pipe.props["diameter"]:
                    piece = round(end - start, 2)
                    total_rub += piece * diameter_row(self.rules, required)["recon_rub_m"]
                    total_m += piece
        for chamber_id, flow in chamber_flow.items():
            chamber = self.scene.chambers[chamber_id]
            required = dn_for_flow(self.rules, flow)
            for pipe in self.scene.inp.chamber_links.get(chamber_id, []):
                added = full.get(pipe.id, 0.0)
                after = dn_for_flow(self.rules, float(pipe.props.get("flow_tph", 0)) + added) if added > 0 else pipe.props["diameter"]
                required = max(required, after, pipe.props["diameter"])
            if required > chamber.props["diameter"]:
                total_rub += chamber_cost(self.rules, required)
        return rub(self.rules, total_rub) + meters(self.rules, total_m)



def tree_estimate(builder: TreeBuilder, tree: Tree) -> float:
    """S-оценка новых участков, камер ветвления, врезки и камеры врезки в трубу без реконструкции."""
    rules = builder.rules
    below: dict[int, float] = defaultdict(float)
    children: dict[int, list[TreeSeg]] = defaultdict(list)
    for seg in tree.segs:
        children[seg.a].append(seg)
    flow_at = {pid: builder.flow[cp] for cp, pid in tree.terminals.items()}

    def total(pid: int) -> float:
        if pid not in below:
            below[pid] = flow_at.get(pid, 0.0) + sum(total(s.b) for s in children[pid])
        return below[pid]

    value = 0.0
    for seg in tree.segs:
        dn = dn_for_flow(rules, total(seg.b))
        length = math.dist(tree.points[seg.a], tree.points[seg.b])
        value += meters(rules, length) + rub(rules, diameter_row(rules, dn)["new_rub_m"] * length)
    for pid, segs in children.items():
        if pid != 0 and len(segs) >= 2:
            value += rub(rules, chamber_cost(rules, dn_for_flow(rules, total(pid))))
    if tree.segs:
        value += rub(rules, rules["tie_in_cost"])
        if tree.tie.existing_type == "heat_network":
            value += rub(rules, chamber_cost(rules, max(dn_for_flow(rules, total(0)), builder.scene.pipes[tree.tie.existing_id].props["diameter"])))
    return value


def forest_estimate(builder: TreeBuilder, recon: Recon, trees: list[Tree]) -> float:
    rules = builder.rules
    connected = {cp for tree in trees for cp in tree.terminals}
    penalty = sum(rules["penalty"]["fixed"] + rules["penalty"]["per_tph"] * t.flow for t in builder.scene.terminals if t.cp_id not in connected)
    loads = [(tree.tie, sum(builder.flow[cp] for cp in tree.terminals)) for tree in trees if tree.segs]
    return sum(tree_estimate(builder, t) for t in trees) + recon.cost(loads) + rub(rules, penalty)


def block_options(builder: TreeBuilder, recon: Recon, cps: list[str], ties: list[int], limit: int = TOP_TIES,
                  loads: list[tuple[TieIn, float]] = (), others: list[Tree] = ()) -> list[tuple[float, int, Tree]]:
    """Лучшие врезки для блока ОКС по предельной S-оценке: дерево, врезка и прирост реконструкции с учётом
    уже принятых деревьев (реконструкция общей цепочки к источнику нелинейна)."""
    rules = builder.rules
    flow = sum(builder.flow[cp] for cp in cps)
    loads = list(loads)
    base = recon.cost(loads)
    rough = []
    for tie in ties:
        paths = sum(builder.table(cp, dn_for_flow(rules, builder.flow[cp]), set(), set(), None)[0][tie] for cp in cps if cp in builder.terminal_node)
        if paths < INF:
            rough.append((paths + recon.cost(loads + [(builder.tie_of(tie), flow)]) - base, tie))
    rough.sort()
    options = []
    for _, tie in rough[:max(limit * PREFILTER, limit)]:
        tree = builder.build(tie, cps)
        if tree.unconnected or not tree.segs or not builder.turns_ok(tree):
            continue
        options.append((tree_estimate(builder, tree) + recon.cost(loads + [(tree.tie, flow)]) - base, tie, tree))
    options.sort(key=lambda item: item[0])
    return options[:limit]


def forest(builder: TreeBuilder, recon: Recon, partition: list[list[str]]) -> list[Tree]:
    """Лес по разбиению: блоки от больших к меньшим берут врезку с наименьшей предельной оценкой; блок, которому
    не нашлось дерева, распадается на одиночные ОКС."""
    graph = builder.graph
    ties = graph.tie_nodes()
    queue = sorted(partition, key=lambda block: (-len(block), block[0]))
    trees: list[Tree] = []
    branches: dict[int, int] = defaultdict(int)

    def room(tie: int) -> int:
        node = graph.nodes[tie]
        if node.kind == "tie_pipe":
            # в одной точке трубы валидатор допускает только одну врезку
            return 0 if branches[tie] else node.capacity
        return node.capacity - branches[tie]

    while queue:
        cps = queue.pop(0)
        free = [t for t in ties if room(t) > 0]
        loads = [(tree.tie, sum(builder.flow[cp] for cp in tree.terminals)) for tree in trees]
        chosen = None
        for _, tie, tree in block_options(builder, recon, cps, free, TOP_TIES, loads):
            if tree.children[0] > room(tie) or conflicts(tree, trees):
                tree = builder.build(tie, cps, trees, root_capacity=room(tie))
            if tree.segs and not tree.unconnected and builder.turns_ok(tree):
                chosen = (tie, tree)
                break
        if chosen is not None:
            trees.append(chosen[1])
            branches[chosen[0]] += chosen[1].children[0]
        elif len(cps) > 1:
            queue += [[cp] for cp in cps]
    return trees


def conflicts(tree: Tree, others: list[Tree]) -> bool:
    """Дерево касается чужих деревьев вне общей камеры врезки."""
    mine = tree.lines()
    if not mine or not others:
        return False
    root = Point(tree.points[0])
    geom = shapely.union_all(mine)
    for other in others:
        theirs = shapely.union_all(other.lines())
        if other.root_node == tree.root_node:
            clip = root.buffer(SHARED_ROOT_CLIP_M)
            if geom.difference(clip).distance(theirs.difference(clip)) <= 2 * TOUCH_M:
                return True
        elif geom.distance(theirs) <= 2 * TOUCH_M or geom.distance(Point(other.points[0])) <= FOREIGN_TIE_M:
            return True
    return False


def partitions(scene: Scene) -> dict[str, list[list[str]]]:
    by_point = {(t.point.x, t.point.y): t.cp_id for t in scene.terminals}
    grouped = [[by_point[(p.x, p.y)] for p in group] for group in groups(scene)]
    split = []
    for group in groups(scene):
        parts = kmeans(group) if len(group) > 2 else [group]
        split += [[by_point[(p.x, p.y)] for p in part] for part in parts]
    result = {"singles": [[t.cp_id] for t in scene.terminals]}
    if any(len(g) > 1 for g in grouped):
        result["groups"] = grouped
    if any(len(g) > 2 for g in grouped):
        result["kmeans"] = split
    return result


def baseline(scene: Scene, graph: VisGraph) -> tuple[Solution, model.CostBreakdown, list[Tree]]:
    """Лучший по S лес из стратегий сервиса; оценка каждого леса — единая модель стоимости."""
    builder = TreeBuilder(scene, graph)
    recon = Recon(scene, graph.rules)
    best = None
    for name, partition in partitions(scene).items():
        trees = forest(builder, recon, partition)
        sol = solution(trees, {"strategy": name})
        cost = model.cost(sol, scene)
        if best is None or cost.score_value < best[1].score_value:
            best = (sol, cost, trees)
    return best
