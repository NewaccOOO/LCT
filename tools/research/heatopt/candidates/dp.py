"""Кандидат dp (T-3): точные диаметры динамикой по дереву при фиксированном лесе и цикл «топология ↔ диаметры».

Диаметры. Цепочка одного Ду — связное множество кусков этого Ду через любые узлы (docs/interpretation.md).
Вставка на ступень выше не касается узлов, поэтому она сама себе цепочка, а задача распадается на компоненты
рёбер одного минимального Ду: вставки нужны только в компоненте длиннее предела, и цена метра вставки внутри
компоненты одна. Камеры вставки не меняют: у узлов остаются куски минимального Ду. Динамика идёт снизу вверх
по путям между узлами ветвления. Состояние пути — семейство «петель» (cost, base, floor): вставок на cost
метров, открытая вверх цепочка base, и её можно укоротить до floor, удлиняя последнюю вставку метр за метр.
Семейства детей в узле складываются попарно, доминируемые петли отбрасываются. Разрезы — по правилам
планировщика модели: не ближе 1,5 м к узлам и вершинам, 10 м к врезке и 6 м к объектам специального прохода.

Цикл. Рёбра графа перевзвешиваются ценой метра по диаметру, который на них назначила динамика, деревья
перестраиваются с теми же врезками, лучшее решение выбирается по model.objective: S или S со штрафами правил.
"""
import math
import time
from collections import defaultdict
from typing import (
    Any,
    NamedTuple,
)

import numpy as np
import shapely
from heatcheck.model import (
    diameter_row,
    next_diameter,
)
from heatcheck.network import cluster
from shapely import STRtree
from shapely.geometry import (
    LineString,
    Point,
)
from shapely.ops import substring

from heatopt import (
    model,
    tm,
)
from heatopt.graph import VisGraph
from heatopt.model import (
    QualityRule,
    Solution,
)
from heatopt.scene import Scene

# миллионы просмотров рёбер графа поисками кратчайших путей в раундах перевзвешивания: детерминированно и
# пропорционально времени; на S и M хватает на все раунды, на L не хватает и на одно перестроение леса
DEFAULT_BUDGET = 10
MAX_ROUNDS = 5
# порог короткой вставки RQ-10 из docs/routing-quality.md плюс 1 см, чтобы после округления координат выхода
# вставка не оказалась короче порога
MIN_INSERT_M = 10.01
# кусок у вставки не короче, чем оставляет планировщик модели: подотрезок от 1 м (правило geometry) с запасом
MIN_PIECE_M = model.INSERT_GAP_M
# запас на округление координат выхода до 9 знаков
CHAIN_MARGIN_M = 0.1
EDGE_TOL_M = 1e-3
MAX_HINGES = 256
# ponytail: проверка валидатором стоит секунды на L; дальше первых решений нарушения у тех же лесов повторяются
MAX_VALIDATIONS = 6
EPS = 1e-9

Interval = tuple[float, float]


class Hinge(NamedTuple):
    cost: float
    base: float
    floor: float
    plan: tuple

    def at(self, t: float) -> float:
        return self.cost + max(0.0, self.base - t)


LEAF = Hinge(0.0, 0.0, 0.0, ("leaf",))


class Path(NamedTuple):
    """Путь между узлами ветвления, врезками и точками подключения; координаты от верхнего узла."""
    top: int
    bottom: int
    coords: list[tuple[float, float]]
    dn: int
    length: float
    top_clear: float


def subtract(intervals: list[Interval], holes: list[Interval]) -> list[Interval]:
    for lo_h, hi_h in holes:
        rest = []
        for lo, hi in intervals:
            if hi_h <= lo or lo_h >= hi:
                rest.append((lo, hi))
                continue
            if lo_h > lo:
                rest.append((lo, lo_h))
            if hi_h < hi:
                rest.append((hi_h, hi))
        intervals = rest
    return intervals


def stretched(cuts: list[Interval], reach: float, length: float) -> tuple[float, float, float] | None:
    """Самое высокое начало не выше reach; конец, попавший к вершине, сдвигается вверх, и вставка удлиняется."""
    cap = min(reach, cuts[-1][1] - length)
    start = next((min(hi, cap) for lo, hi in reversed(cuts) if lo <= cap), None)
    if start is None:
        return None
    lo, hi = next((lo, hi) for lo, hi in cuts if hi >= start + length)
    return start, max(lo, start + length), hi


def exact(cuts: list[Interval], reach: float, length: float) -> tuple[float, float, float] | None:
    """Самое высокое начало не выше reach, при котором вставка ровно заданной длины."""
    for lo_end, hi_end in reversed(cuts):
        top = min(reach, hi_end - length)
        for lo, hi in reversed(cuts):
            start = min(hi, top)
            if start + length < lo_end:
                break
            if start >= lo:
                return start, start + length, hi_end
    return None


def place(runs: list[list[Interval]], low: float, reach: float, length: float) -> list[tuple[float, float, float]]:
    """Вставки с началом в [low, reach] (метры от нижнего узла), концы в допустимых точках разреза одного отрезка
    вдали от объектов специального прохода: самая высокая и самая высокая без удлинения. Каждая — начало, конец
    и предел удлинения конца."""
    options = []
    for finder in (stretched, exact):
        found = next((f for cuts in reversed(runs) if (f := finder(cuts, reach, length)) is not None), None)
        if found is not None and found[0] >= low and found not in options:
            options.append(found)
    return options


def prune(hinges: list[Hinge], limit: float) -> list[Hinge]:
    kept: list[Hinge] = []
    for h in sorted((h for h in hinges if h.floor <= limit + EPS), key=lambda h: (h.cost, h.floor, h.cost + h.base)):
        if not any(k.floor <= h.floor + EPS and k.cost + k.base <= h.cost + h.base + EPS for k in kept):
            kept.append(h)
    # ponytail: семейства на реальных деревьях — единицы петель; обрезка страхует от взрыва на экзотике
    return kept[:MAX_HINGES]


class Planner:
    """Разбиение рёбер фиксированного леса на куски минимального Ду и Ду на ступень выше."""

    def __init__(self, scene: Scene, solution: Solution, rules: dict[str, Any], min_insert_m: float):
        self.scene, self.solution, self.rules, self.min_insert = scene, solution, rules, min_insert_m
        asm = model.Assembly(solution, scene, rules, "1")
        for tie in solution.tie_ins:
            asm.tie_nodes[asm.index.add(tie.point)].append(tie)
        asm.lines = [LineString(c) if len(c := model.clean(edge)) >= 2 else None for edge in solution.edges]
        self.oriented = asm.orient()
        self.flows = asm.flows(self.oriented)
        self.asm = asm
        self.paths: list[Path] = []
        self.runs: list[list[list[Interval]]] = []
        self.below: dict[int, list[int]] = defaultdict(list)
        self.memo: dict[int, list[Hinge]] = {}

    def limit(self, dn: int) -> float:
        return diameter_row(self.rules, dn)["max_length_m"] - CHAIN_MARGIN_M

    def run(self) -> Solution | None:
        asm = self.asm
        if asm.result.violations or any(self.flows[i] <= 0 for i in self.oriented):
            return None
        self.split_paths()
        self.cut_windows()
        eligible = self.eligible()
        found: dict[int, list[Interval]] = {}
        parent_dn = {path.bottom: path.dn for path in self.paths}
        for node in sorted(self.below):
            groups: dict[int, list[int]] = defaultdict(list)
            for p in self.below[node]:
                groups[self.paths[p].dn].append(p)
            for dn, members in sorted(groups.items()):
                if dn == parent_dn.get(node) or not eligible[members[0]]:
                    continue
                limit = self.limit(dn)
                family = self.merge(members, limit)
                if not family:
                    return None
                best = min(family, key=lambda h: h.at(limit))
                self.realize(best, limit, found)
        return self.output(found)

    def split_paths(self) -> None:
        asm, children = self.asm, self.asm.children
        queue = sorted(asm.tie_nodes)
        seen = set(queue)
        while queue:
            top = queue.pop(0)
            for i in sorted(e for e in children.get(top, []) if self.oriented[e][0] == top):
                coords = list(asm.lines[i].coords)
                node = self.oriented[i][1]
                while node not in asm.cp_node and node not in asm.tie_nodes and len(children.get(node, [])) == 1:
                    j = children[node][0]
                    coords += list(asm.lines[j].coords)[1:]
                    node = self.oriented[j][1]
                length = LineString(coords).length
                top_clear = model.TIE_CLEAR_M if top in asm.tie_nodes else MIN_PIECE_M
                self.below[top].append(len(self.paths))
                self.paths.append(Path(top, node, coords, tm.dn_for_flow(self.rules, self.flows[i]), length, top_clear))
                if node not in seen and children.get(node):
                    seen.add(node)
                    queue.append(node)

    def cut_windows(self) -> None:
        """Для каждого пути — отрезки дальше NO_CUT_M от объектов специального прохода (там и спецучастки, и зоны
        сближения, которые валидатор прощает только смежным участкам), в каждом — точки, где разрез не ближе
        MIN_PIECE_M к вершине. Позиции в метрах от нижнего узла."""
        areas = [o.feature.geom.buffer(model.NO_CUT_M + model.NODE_TOL_M) for o in self.scene.inp.special]
        index = STRtree(areas) if areas else None
        for path in self.paths:
            line = LineString(path.coords)
            length = path.length
            holes = []
            if index is not None:
                for z in sorted(index.query(line, predicate="intersects")):
                    for part in shapely.get_parts(line.intersection(areas[z])):
                        ends = [length - line.project(Point(xy)) for xy in part.coords]
                        if ends:
                            holes.append((min(ends), max(ends)))
            vertices, walked = [], 0.0
            for a, b in zip(path.coords, path.coords[1:-1]):
                walked += math.dist(a, b)
                vertices.append((length - walked - MIN_PIECE_M, length - walked + MIN_PIECE_M))
            runs = []
            for run in subtract([(MIN_PIECE_M, length - path.top_clear)], sorted(holes)):
                cuts = [(lo, hi) for lo, hi in subtract([run], vertices) if hi >= lo]
                if cuts:
                    runs.append(cuts)
            self.runs.append(runs)

    def eligible(self) -> list[bool]:
        """Компоненты путей одного минимального Ду; вставки допустимы только в компоненте длиннее предела."""
        at_node: dict[int, list[int]] = defaultdict(list)
        for p, path in enumerate(self.paths):
            at_node[path.top].append(p)
            at_node[path.bottom].append(p)
        pairs = [(a, b) for members in at_node.values() for a in members for b in members
                 if a < b and self.paths[a].dn == self.paths[b].dn]
        labels = cluster(len(self.paths), pairs)
        total: dict[int, float] = defaultdict(float)
        for label, path in zip(labels, self.paths):
            total[label] += path.length
        return [total[label] > diameter_row(self.rules, path.dn)["max_length_m"] for label, path in zip(labels, self.paths)]

    def merge(self, members: list[int], limit: float) -> list[Hinge]:
        family = [LEAF]
        for p in members:
            family = prune([Hinge(a.cost + b.cost, a.base + b.base, a.floor + b.floor, ("sum", a, b))
                            for a in family for b in self.family(p)], limit)
        return family

    def family(self, p: int) -> list[Hinge]:
        if p in self.memo:
            return self.memo[p]
        path = self.paths[p]
        limit = self.limit(path.dn)
        up = next_diameter(self.rules, path.dn)
        up_limit = self.limit(up) if up is not None else 0.0
        same = [c for c in self.below[path.bottom] if self.paths[c].dn == path.dn]
        hinges = []
        for below in self.merge(same, limit):
            hinges.append(Hinge(below.cost, below.base + path.length, below.floor + path.length, ("pass", p, below)))
            a = min(below.base, limit - MIN_PIECE_M)
            if up is None or a < below.floor - EPS:
                continue
            # частичные расстановки (метры вставок, вставки, верх уже в пределе); лишние отсекаются по Парето
            states = [(below.cost + below.base - a, (), False)]
            while states:
                grown = []
                for cost, inserts, fits in states:
                    low, reach = (inserts[-1][1] + MIN_PIECE_M, inserts[-1][1] + limit) if inserts else (MIN_PIECE_M, limit - a)
                    for start, end, stretch in place(self.runs[p], low, reach, self.min_insert):
                        placed = (*inserts, (start, end))
                        grown_cost = cost + end - start
                        hinges.append(Hinge(grown_cost, path.length - end, path.length - min(stretch, start + up_limit),
                                            ("cut", p, below, a, placed)))
                        # верх уже в пределе: ещё одна вставка нужна, только чтобы укоротить его сильнее, чем
                        # удлинением, а третья поверх неё верх не укоротит
                        if not fits:
                            grown.append((grown_cost, placed, path.length - end <= limit))
                states = []
                for state in sorted(grown, key=lambda item: (item[0], -item[1][-1][1])):
                    if not states or state[1][-1][1] > states[-1][1][-1][1] + EPS:
                        states.append(state)
        self.memo[p] = prune(hinges, limit)
        return self.memo[p]

    def realize(self, hinge: Hinge, target: float, found: dict[int, list[Interval]]) -> None:
        kind = hinge.plan[0]
        if kind == "sum":
            _, a, b = hinge.plan
            excess = max(0.0, a.base + b.base - target)
            cut_a = min(excess, a.base - a.floor)
            self.realize(a, a.base - cut_a, found)
            self.realize(b, b.base - (excess - cut_a), found)
        elif kind == "pass":
            _, p, below = hinge.plan
            self.realize(below, max(target - self.paths[p].length, below.floor), found)
        elif kind == "cut":
            _, p, below, a, inserts = hinge.plan
            start, end = inserts[-1]
            found[p] = [*inserts[:-1], (start, end + max(0.0, hinge.base - target))]
            self.realize(below, a, found)

    def output(self, found: dict[int, list[Interval]]) -> Solution:
        edges, dns, insert_m = [], {}, 0.0
        for p, path in enumerate(self.paths):
            line = LineString(path.coords)
            bounds, sizes = [0.0], []
            for start, end in sorted((path.length - e, path.length - s) for s, e in found.get(p, [])):
                bounds += [start, end]
                sizes += [path.dn, next_diameter(self.rules, path.dn)]
                insert_m += end - start
            bounds.append(path.length)
            sizes.append(path.dn)
            for (a, b), dn in zip(zip(bounds, bounds[1:]), sizes):
                coords = list(substring(line, a, b).coords)
                if a == 0.0:
                    coords[0] = path.coords[0]
                if b == path.length:
                    coords[-1] = path.coords[-1]
                # по отрезку на ребро, как в исходном лесе: вершину с изломом меньше 3° модель оставит узлом
                for xy, nxt in zip(coords, coords[1:]):
                    dns[len(edges)] = dn
                    edges.append([xy, nxt])
        meta = {**self.solution.meta, "sized": True, "insert_m": round(insert_m, 3)}
        return Solution(edges, list(self.solution.tie_ins), dn=dns, meta=meta)


def plan_diameters(scene: Scene, solution: Solution, rules: dict[str, Any] | None = None,
                   min_insert_m: float = MIN_INSERT_M) -> Solution | None:
    """Решение с разрезанными рёбрами и диаметрами всех кусков; None, если лес собран с нарушениями или
    разбиения под предельную длину нет."""
    return Planner(scene, Solution(solution.edges, solution.tie_ins, meta=dict(solution.meta)), rules or scene.rules, min_insert_m).run()


def edge_prices(graph: VisGraph, variant: Any) -> dict[int, float]:
    """Средняя цена метра нового участка на рёбрах графа, по которым проходит вариант."""
    lines = tm.edge_lines(graph)
    index = graph.__dict__["_line_tree"]
    total: dict[int, float] = defaultdict(float)
    covered: dict[int, float] = defaultdict(float)
    for seg in variant.segments:
        price = diameter_row(graph.rules, seg.props["diameter"])["new_rub_m"]
        coords = list(seg.geom.coords)
        for a, b in zip(coords, coords[1:]):
            piece = LineString([a, b])
            for e in sorted(index.query(piece.interpolate(0.5, normalized=True), predicate="dwithin", distance=EDGE_TOL_M)):
                if lines[e].distance(Point(a)) <= EDGE_TOL_M and lines[e].distance(Point(b)) <= EDGE_TOL_M:
                    total[int(e)] += piece.length * price
                    covered[int(e)] += piece.length
    return {e: total[e] / covered[e] for e in sorted(total) if covered[e] > 0}


class Reweighted(tm.TreeBuilder):
    """Такахаши–Мацуяма с ценой метра на рёбрах прошлого леса по назначенному там диаметру."""

    def __init__(self, scene: Scene, graph: VisGraph, prices: dict[int, float], max_scans: int):
        super().__init__(scene, graph)
        self.max_scans = max_scans
        self.edges = np.array(list(prices), dtype=np.int64)
        self.prices = np.array(list(prices.values()), dtype=float)
        self.weights: dict[int, list[float]] = {}
        self.tables: dict[tuple[str, int], tuple[list[float], list[int]]] = {}
        self.scans = 0

    def weights_for(self, dn: int) -> list[float]:
        if dn not in self.weights:
            graph, rules, e = self.graph, self.rules, self.edges
            values = np.array(tm.edge_weights(graph, dn))
            price = np.maximum(self.prices, diameter_row(rules, dn)["new_rub_m"])
            values[e] = tm.meters(rules, 1.0) * graph.length[e] + tm.rub(rules, 1.0) * price * graph.cost_len[e]
            self.weights[dn] = values.tolist()
        return self.weights[dn]

    def build(self, tie_node, cps, others=(), weight_dn=None, weights_for=None, root_capacity=None) -> tm.Tree:
        return super().build(tie_node, cps, others, weight_dn, weights_for or self.weights_for, root_capacity)

    def table(self, cp, dn, blocked_nodes, blocked_edges, weights_for):
        if blocked_nodes or blocked_edges or weights_for is None:
            self.scans += 2 * len(self.graph.u)
            return super().table(cp, dn, blocked_nodes, blocked_edges, weights_for)
        if (cp, dn) not in self.tables:
            self.scans += 2 * len(self.graph.u)
            self.tables[(cp, dn)] = tm.dijkstra(self.graph, self.terminal_node[cp], weights_for(dn))
        return self.tables[(cp, dn)]


def rebuild(builder: Reweighted, trees: list[tm.Tree]) -> list[tm.Tree] | None:
    """Те же врезки и блоки ОКС, деревья строятся заново в прежнем порядке с проверками tm.forest: касание
    чужих — повтор с обходом, предел поворотов. Ветви у камеры врезки делят её деревья, каждому следующему
    остаётся хотя бы одна."""
    result: list[tm.Tree] = []
    taken: dict[int, int] = defaultdict(int)
    for n, old in enumerate(trees):
        root, cps = old.root_node, sorted(old.terminals)
        later = sum(1 for other in trees[n + 1:] if other.root_node == root)
        room = builder.graph.nodes[root].capacity - taken[root] - later
        tree = builder.build(root, cps, root_capacity=room)
        if tree.unconnected or not tree.segs or tm.conflicts(tree, result):
            tree = builder.build(root, cps, result, root_capacity=room)
        if tree.unconnected or not tree.segs or not builder.turns_ok(tree) or builder.scans > builder.max_scans:
            return None
        taken[root] += tree.children[0]
        result.append(tree)
    return result


def shape(trees: list[tm.Tree]) -> str:
    return repr([[(round(x, 3), round(y, 3)) for edge in tree.polylines() for x, y in edge] for tree in trees])


def solve(scene: Scene, graph: VisGraph, rules: dict[str, Any], quality: list[QualityRule] | None, budget: int,
          seed: int, min_insert_m: float = MIN_INSERT_M) -> Solution:
    """Старт — леса стратегий tm.baseline, каждый и без изменений (страховка «не хуже базового»), и с диаметрами
    динамики; затем раунды перевзвешивания и перестроения каждого леса, пока он меняется, не больше MAX_ROUNDS
    и пока поиски кратчайших путей просмотрели меньше budget миллионов рёбер. Алгоритм детерминирован, seed не
    используется."""
    started = time.perf_counter()
    builder, recon = tm.TreeBuilder(scene, graph), tm.Recon(scene, rules)
    found: list[tuple[float, int, Solution, model.CostBreakdown]] = []
    fronts: dict[str, tuple[list[tm.Tree], model.CostBreakdown]] = {}

    def evaluate(solution: Solution) -> model.CostBreakdown:
        cost = model.cost(solution, scene, rules)
        value = model.objective(cost, scene, quality) if not cost.violations else cost.score_value
        found.append((value, len(found), solution, cost))
        return cost

    def sized(trees: list[tm.Tree], meta: dict[str, Any]) -> model.CostBreakdown:
        plain = tm.solution(trees, meta)
        planned = plan_diameters(scene, plain, rules, min_insert_m)
        return evaluate(planned if planned is not None else plain)

    for name, partition in tm.partitions(scene).items():
        trees = tm.forest(builder, recon, partition)
        evaluate(tm.solution(trees, {"strategy": name, "round": 0, "sized": False}))
        fronts[name] = (trees, sized(trees, {"strategy": name, "round": 0, "sized": False}))
    left = budget * 1_000_000
    for round_no in range(1, MAX_ROUNDS + 1):
        for name, (trees, cost) in list(fronts.items()):
            # перестроение леса — хотя бы один поиск от каждого ОКС; не начинается, если на это не хватит бюджета
            if 2 * len(graph.u) * sum(len(tree.terminals) for tree in trees) > left:
                del fronts[name]
                continue
            reweighted = Reweighted(scene, graph, edge_prices(graph, cost.variant), left)
            new = rebuild(reweighted, trees)
            left -= reweighted.scans
            if new is None or shape(new) == shape(trees):
                del fronts[name]
                continue
            fronts[name] = (new, sized(new, {"strategy": name, "round": round_no, "sized": False}))
        if not fronts:
            break
    # сначала решения без нарушений модели, потом с меньшим числом неподключённых ОКС (D-12: ОКС не отключаются
    # ради S), потом по целевой функции; из первых MAX_VALIDATIONS берётся первое без нарушений валидатора
    ranked = sorted(found, key=lambda item: (bool(item[3].violations), len(item[3].unconnected), item[0], item[1]))
    clean = (sol for _, _, sol, cost in ranked[:MAX_VALIDATIONS] if not cost.violations and not any(model.validate(scene, cost).values()))
    solution = next(clean, ranked[0][2])
    solution.elapsed = time.perf_counter() - started
    return solution
