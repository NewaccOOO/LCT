"""Кандидат bend (T-4, R-12): путь от ОКС к дереву ищется Дейкстрой по состояниям «вершина + входящее ребро»
с ценой поворота и излома (C-9), рёбра вдоль дорог и трамвая в поиске дешевле (коридоры). Деревья и лес строит
tm, итог — лучший по целевой функции среди базового леса и своих лесов."""
import heapq
import math
import random
import time
from typing import Any

import numpy as np
import shapely
from heatcheck.model import diameter_row
from shapely import STRtree

from heatopt import (
    model,
    tm,
)
from heatopt.graph import (
    PASSABLE,
    VisGraph,
    sides_of,
)
from heatopt.model import (
    QualityRule,
    Solution,
)
from heatopt.scene import Scene

DEFAULT_BUDGET = 1
# цена вершины в режиме quality=None: метры трубы диаметра ребра за поворот и ещё столько же за излом
TURN_M = 1.0
KINK_M = 1.0
CORRIDOR_FACTOR = 0.95
CORRIDOR_BAND_M = 12.0
CORRIDOR_ANGLE_DEG = 15.0
CORRIDOR_TYPES = ("road", "tram_tracks")
# после проходов по разбиениям с настройками по умолчанию бюджет перебирает эти множители коридора и цены поворота
FACTOR_CHOICES = (0.85, 0.9, 1.0)
SCALE_CHOICES = (0.5, 1.0, 3.0)
# ponytail: не больше 4 приходов в узел; при 8 цена пути до врезок на L меняется до 0,004 S, поиск дольше на треть
MAX_LABELS = 4
COS_TURN = math.cos(math.radians(model.MIN_TURN_DEG))
COS_KINK = math.cos(math.radians(model.KINK_MAX_DEG))
COS_CORRIDOR = math.cos(math.radians(CORRIDOR_ANGLE_DEG))
INF = math.inf


class Lattice:
    """Направленные рёбра графа подряд по узлу выхода (CSR): у каждого узел прихода, ребро и единичное направление.
    Состояние поиска — номер направленного ребра в этом порядке."""

    def __init__(self, graph: VisGraph, polygons: list[Any]):
        n, m = len(graph.nodes), len(graph.u)
        xy = np.array([node.xy for node in graph.nodes], dtype=float).reshape(-1, 2)
        u, v = graph.u.astype(np.int64), graph.v.astype(np.int64)
        unit = (xy[v] - xy[u]) / graph.length[:, None]
        tail, head = np.concatenate([u, v]), np.concatenate([v, u])
        order = np.argsort(tail, kind="stable")
        self.nodes, self.states = n, 2 * m
        self.start = np.concatenate([[0], np.cumsum(np.bincount(tail, minlength=n))]).tolist()
        self.edge = order % m
        self.dx, self.dy = np.concatenate([unit[:, 0], -unit[:, 0]])[order], np.concatenate([unit[:, 1], -unit[:, 1]])[order]
        self.head = head[order]
        self.head_list, self.dx_list, self.dy_list = self.head.tolist(), self.dx.tolist(), self.dy.tolist()
        # два состояния каждого ребра: чтобы запретить ребро в обе стороны
        position = np.empty(2 * m, dtype=np.int64)
        position[order] = np.arange(2 * m)
        self.edge_states = position.reshape(2, m).T
        self.passable = [node.kind in PASSABLE for node in graph.nodes]
        self.kink_free = [True] * n
        if polygons and n:
            near, _ = STRtree(polygons).query(shapely.points(xy), predicate="dwithin", distance=model.KINK_FREE_M)
            for i in np.unique(near).tolist():
                self.kink_free[i] = False


class Search:
    """Результат поиска от одного терминала: цена прихода в узлы и пути по состояниям."""

    def __init__(self, lattice: Lattice, source: int, dist: list[float], first: list[int], parent: np.ndarray):
        self.lattice, self.source, self.dist, self.first, self.parent = lattice, source, dist, first, parent
        # ребро лучшего прихода в узел: цепочка таких рёбер тоже ведёт к источнику (tm.path_nodes)
        edge = lattice.edge
        self.pred = [int(edge[s]) if s >= 0 else -1 for s in first]

    def path(self, target: int) -> list[int]:
        states = []
        s = self.first[target]
        while s >= 0:
            states.append(s)
            s = int(self.parent[s])
        nodes = [self.source] + [self.lattice.head_list[s] for s in reversed(states)]
        # повторный приход в узел с другим направлением даёт петлю: она вырезается
        path: list[int] = []
        seen: dict[int, int] = {}
        for node in nodes:
            if node in seen:
                for dropped in path[seen[node] + 1:]:
                    del seen[dropped]
                del path[seen[node] + 1:]
            else:
                seen[node] = len(path)
                path.append(node)
        return path


def search(lattice: Lattice, source: int, weights: np.ndarray, turn: float, kink: float,
           blocked_nodes: set[int] = frozenset(), blocked_edges: set[int] = frozenset()) -> Search:
    """Дейкстра по состояниям: переход = вес ребра + turn при отклонении от 3° + kink при изломе (до 50°, узел без
    полигонов в 5 м). Приход в узел дороже лучшего на цену вершины и больше MAX_LABELS приходов не продолжаются:
    от них путь дальше не дешевле, чем от лучшего прихода."""
    cost_of = np.asarray(weights, dtype=float)[lattice.edge]
    if blocked_edges:
        cost_of[lattice.edge_states[list(blocked_edges)].ravel()] = INF
    best = np.full(lattice.nodes, INF)
    best[list(blocked_nodes)] = -INF
    best[source] = -INF
    sdist = np.full(lattice.states, INF)
    parent = np.full(lattice.states, -1, dtype=np.int32)
    settled_best = [INF] * lattice.nodes
    labels = [0] * lattice.nodes
    first = [-1] * lattice.nodes
    limit = turn + kink
    start, head, heads_of, dx_of, dy_of = lattice.start, lattice.head_list, lattice.head, lattice.dx, lattice.dy
    sdx, sdy, passable, kink_free = lattice.dx_list, lattice.dy_list, lattice.passable, lattice.kink_free
    # состояние -1 — стоим в источнике без направления
    heap: list[tuple[float, int]] = [(0.0, -1)]
    while heap:
        d, s = heapq.heappop(heap)
        node = head[s] if s >= 0 else source
        if s >= 0:
            if d > sdist[s] or labels[node] >= MAX_LABELS or d >= settled_best[node] + (limit if kink_free[node] else turn):
                continue
            if not labels[node]:
                settled_best[node], first[node] = d, s
            labels[node] += 1
            if not passable[node]:
                continue
        lo, hi = start[node], start[node + 1]
        cost = d + cost_of[lo:hi]
        if s >= 0 and limit:
            cos = sdx[s] * dx_of[lo:hi] + sdy[s] * dy_of[lo:hi]
            if kink and kink_free[node]:
                cost += np.where(cos <= COS_TURN, np.where(cos > COS_KINK, limit, turn), 0.0)
            else:
                cost += np.where(cos <= COS_TURN, turn, 0.0)
        heads = heads_of[lo:hi]
        better = np.flatnonzero((cost < sdist[lo:hi]) & (cost < best[heads] + limit))
        if not len(better):
            continue
        cost, heads = cost[better], heads[better]
        better += lo
        sdist[better] = cost
        parent[better] = s
        best[heads] = np.minimum(best[heads], cost)
        for item in zip(cost.tolist(), better.tolist()):
            heapq.heappush(heap, item)
    dist = [value if value > -INF else INF for value in best.tolist()]
    dist[source] = 0.0
    return Search(lattice, source, dist, first, parent)


def corridor_share(graph: VisGraph, polygons: list[Any]) -> np.ndarray:
    """Доля длины ребра в полосе CORRIDOR_BAND_M вокруг полигонов дорог и трамвая, если в этой части ребро идёт
    под углом не больше CORRIDOR_ANGLE_DEG к ближайшей стороне полигона; перекрытие полос не суммируется."""
    share = np.zeros(len(graph.u))
    if not len(graph.u):
        return share
    lines = tm.edge_lines(graph)
    line_tree = graph.__dict__["_line_tree"]
    xy = np.array([node.xy for node in graph.nodes], dtype=float)
    unit = (xy[graph.v] - xy[graph.u]) / graph.length[:, None]
    for polygon in polygons:
        band = shapely.buffer(polygon, CORRIDOR_BAND_M)
        sides = sides_of(polygon)
        a, d = sides[:, :2], sides[:, 2:] - sides[:, :2]
        norm = np.maximum(np.hypot(d[:, 0], d[:, 1]), 1e-12)
        # рёбра, не параллельные ни одной стороне, отсеиваются до пересечения с полосой
        edge = line_tree.query(band)
        cos = np.abs(unit[edge] @ (d / norm[:, None]).T)
        keep = (cos >= COS_CORRIDOR).any(axis=1)
        edge, cos = edge[keep], cos[keep]
        # куски ребра внутри полосы; у каждого своя ближайшая сторона в его середине
        pieces, owner = shapely.get_parts(shapely.intersection(lines[edge], band), return_index=True)
        size = shapely.length(pieces)
        pieces, owner, size = pieces[size > 0], owner[size > 0], size[size > 0]
        if not len(pieces):
            continue
        mid = shapely.get_coordinates(shapely.line_interpolate_point(pieces, 0.5, normalized=True))
        t = np.clip(((mid[:, None, :] - a) * d).sum(axis=2) / norm ** 2, 0.0, 1.0)
        gap = np.linalg.norm(mid[:, None, :] - a - t[..., None] * d, axis=2)
        along = cos[owner, gap.argmin(axis=1)] >= COS_CORRIDOR
        inside = np.zeros(len(edge))
        np.add.at(inside, owner[along], size[along])
        np.maximum.at(share, edge, np.minimum(inside / graph.length[edge], 1.0))
    return share


def cached(graph: VisGraph, key: str, make):
    store = graph.__dict__.setdefault("_bend", {})
    if key not in store:
        store[key] = make()
    return store[key]


class BendBuilder(tm.TreeBuilder):
    """TreeBuilder с поиском пути по состояниям: таблица — Search, в attach уходит путь с учётом поворотов."""

    def __init__(self, scene: Scene, graph: VisGraph, lattice: Lattice, discount: np.ndarray, prices: dict[str, float] | None, scale: float):
        super().__init__(scene, graph)
        self.lattice, self.discount, self.prices, self.scale = lattice, discount, prices, scale
        self.weights: dict[int, np.ndarray] = {}
        self.searches: dict[str, Search] = {}
        self.free: dict[tuple[str, int], Search] = {}

    def vertex_price(self, dn: int) -> tuple[float, float]:
        if self.prices is None:
            meter = tm.meters(self.rules, 1.0) + tm.rub(self.rules, diameter_row(self.rules, dn)["new_rub_m"])
            return TURN_M * meter * self.scale, KINK_M * meter * self.scale
        return model.rub_to_score(self.prices["turn"], self.rules) * self.scale, model.rub_to_score(self.prices["kink"], self.rules) * self.scale

    def table(self, cp: str, dn: int, blocked_nodes: set[int], blocked_edges: set[int], weights_for) -> tuple[list[float], list[int]]:
        if dn not in self.weights:
            self.weights[dn] = np.asarray(tm.edge_weights(self.graph, dn)) * self.discount
        key = (cp, dn)
        if blocked_nodes or blocked_edges or key not in self.free:
            found = search(self.lattice, self.terminal_node[cp], self.weights[dn], *self.vertex_price(dn), blocked_nodes, blocked_edges)
            if not blocked_nodes and not blocked_edges:
                self.free[key] = found
        else:
            found = self.free[key]
        # build берёт путь к цели из последней таблицы этого ОКС: attach подменяет его путём по состояниям
        self.searches[cp] = found
        return found.dist, found.pred

    def attach(self, tree: tm.Tree, cp: str, nodes: list[int]) -> set[int] | None:
        return super().attach(tree, cp, self.searches[cp].path(nodes[-1]))


def settings(names: list[str], first: str, budget: int, seed: int) -> list[tuple[str, float, float]]:
    """Разбиение, множитель коридора и масштаб цены поворота для каждого леса: сначала все разбиения с настройками
    по умолчанию (первым — разбиение базового леса), затем перебор в порядке, заданном seed."""
    defaults = [(name, CORRIDOR_FACTOR, 1.0) for name in [first] + [n for n in names if n != first]]
    extra = [(name, factor, scale) for name in names for factor in FACTOR_CHOICES for scale in SCALE_CHOICES]
    random.Random(seed).shuffle(extra)
    return (defaults + extra)[:budget]


def solve(scene: Scene, graph: VisGraph, rules: dict[str, Any], quality: list[QualityRule] | None, budget: int = DEFAULT_BUDGET,
          seed: int = 1) -> Solution:
    started = time.perf_counter()
    base, base_cost, _ = tm.baseline(scene, graph)
    lattice = cached(graph, "lattice", lambda: Lattice(graph, [
        o.feature.geom for o in scene.inp.forbid + scene.inp.special
        if o.feature.geom.geom_type in ("Polygon", "MultiPolygon") and (o.params["rule"] == "forbid" or o.restriction_type in model.KINK_POLYGON_TYPES)
    ]))
    share = cached(graph, "corridor", lambda: corridor_share(graph, [
        o.feature.geom for o in scene.inp.special
        if o.restriction_type in CORRIDOR_TYPES and o.feature.geom.geom_type in ("Polygon", "MultiPolygon")
    ]))
    prices = model.unit_prices(quality) if quality is not None else None
    recon = tm.Recon(scene, rules)
    partitions = tm.partitions(scene)
    base.meta["source"] = "baseline"
    found = [(model.objective(base_cost, scene, quality), base, base_cost)]
    for name, factor, scale in settings(list(partitions), base.meta["strategy"], budget, seed):
        builder = BendBuilder(scene, graph, lattice, 1.0 - (1.0 - factor) * share, prices, scale)
        trees = tm.forest(builder, recon, partitions[name])
        sol = tm.solution(trees, {"source": "bend", "strategy": name, "corridor": factor, "turn_scale": scale})
        cost = model.cost(sol, scene, rules)
        found.append((model.objective(cost, scene, quality), sol, cost))
    # D-12: ОКС не отключаются ради S, поэтому сначала лес с меньшим числом неподключённых
    ranked = sorted(found, key=lambda item: (len(item[2].unconnected), item[0]))
    # лес без нарушений модели; свой лес ещё проверяется валидатором, базовый — ответственность tm (T-0)
    chosen = next((item for item in ranked if not item[2].violations and (item[1] is base or not any(model.validate(scene, item[2]).values()))), ranked[0])
    value, sol, _ = chosen
    sol.meta["objective"] = value
    sol.elapsed = time.perf_counter() - started
    return sol
