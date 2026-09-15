"""Кандидат ls (T-2, R-10): лес Такахаши–Мацуямы по стратегиям сервиса, локальный поиск над деревьями tm.Tree
и мультистарт с возмущением весов рёбер и пулом элитных решений. Ходы отбираются быстрой оценкой
tm.forest_estimate с ценой поворотов, изломов и камер, принимаются по model.objective и model.validate."""
import math
import random
import time
from collections import (
    Counter,
    defaultdict,
)
from collections.abc import (
    Callable,
    Iterator,
)
from dataclasses import (
    dataclass,
    replace,
)
from functools import partial
from typing import Any

import numpy as np
import shapely
from heatcheck.model import (
    TOUCH_TOL_M,
    diameter_row,
)
from shapely import STRtree
from shapely.geometry import Point

from heatopt import (
    model,
    tm,
)
from heatopt.graph import (
    PASSABLE,
    VisGraph,
)
from heatopt.model import (
    QualityRule,
    Solution,
)
from heatopt.scene import Scene

DEFAULT_BUDGET = 250
# ход стоит (len(graph.u) / EDGES_PER_UNIT) ** MOVE_COST_POWER единиц бюджета, но не меньше MIN_MOVE_UNIT: время
# хода на M-1 (66 тыс. рёбер) 0,07 с, на L-1 (340 тыс.) 0,75 с; сборка варианта на малом графе стоит столько же
EDGES_PER_UNIT = 100_000
MOVE_COST_POWER = 1.5
MIN_MOVE_UNIT = 0.5
ELITE_SIZE = 4
NOISE = 0.3
FAST_MARGIN_S = 0.02
IMPROVE_EPS_S = 1e-6
NEAREST_TREES = 2
STEINER_NEAR = 3
STEINER_MIN_GAIN_M = 10.0
FERMAT_ITERATIONS = 30
FERMAT_EPS_M = 1e-3
PSEUDO = "steiner:"
# окрестности спуска от дешёвых ходов уровня леса к дорогим перестройкам внутри дерева
NEIGHBORHOODS = (
    ("connect", "merge", "fuse", "move", "alone", "retie", "split"),
    ("path", "eliminate"),
    ("insert",),
)
SHAPE_KEYS = ("turn", "kink", "chamber")

Move = tuple[tuple, Callable[[], list[tm.Tree] | None]]


@dataclass
class Plan:
    trees: list[tm.Tree]
    value: float
    score: float
    fast: float
    violations: int
    unconnected: int
    strategy: str

    @property
    def rank(self) -> tuple[int, float]:
        """Сначала число неподключённых ОКС (журнал D-12: подключаются все достижимые), затем целевая функция."""
        return self.unconnected, self.value


def extract(tree: tm.Tree, top: int, skip: set[int], capacity: int | None = None) -> tm.Tree:
    """Копия части дерева от точки top без отрезков skip; точки перенумерованы, top становится точкой 0."""
    kids: dict[int, list[int]] = defaultdict(list)
    for i, seg in enumerate(tree.segs):
        if i not in skip:
            kids[seg.a].append(seg.b)
    new = tm.Tree(tree.root_node, tree.tie, tree.capacity if capacity is None else capacity)
    index: dict[int, int] = {}

    def add(pid: int) -> None:
        index[pid] = len(new.points)
        new.points.append(tree.points[pid])
        if pid in tree.graph_node:
            new.graph_node[index[pid]] = tree.graph_node[pid]

    add(top)
    stack = [top]
    while stack:
        pid = stack.pop()
        for child in kids[pid]:
            add(child)
            new.segs.append(tm.TreeSeg(index[pid], index[child]))
            new.children[index[pid]] += 1
            stack.append(child)
    new.terminals = {cp: index[pid] for cp, pid in tree.terminals.items() if pid in index}
    return new


def graft(tree: tm.Tree, at: int, sub: tm.Tree) -> None:
    """Подвешивает компактное поддерево sub к точке at дерева tree."""
    index = {0: at}
    for pid in range(1, len(sub.points)):
        index[pid] = len(tree.points)
        tree.points.append(sub.points[pid])
        if pid in sub.graph_node:
            tree.graph_node[index[pid]] = sub.graph_node[pid]
    for seg in sub.segs:
        tree.segs.append(tm.TreeSeg(index[seg.a], index[seg.b]))
        tree.children[index[seg.a]] += 1
    for cp, pid in sub.terminals.items():
        tree.terminals[cp] = index[pid]


def kids_of(tree: tm.Tree) -> dict[int, list[int]]:
    kids: dict[int, list[int]] = defaultdict(list)
    for seg in tree.segs:
        kids[seg.a].append(seg.b)
    return kids


def is_key(tree: tm.Tree, kids: dict[int, list[int]], pid: int) -> bool:
    return pid == 0 or pid in tree.terminals.values() or len(kids[pid]) >= 2


def detach(tree: tm.Tree, q: int) -> tuple[tm.Tree, tm.Tree]:
    """Снимает ключевой путь над ключевой точкой q: остаток дерева и поддерево с корнем в q."""
    parent = {seg.b: i for i, seg in enumerate(tree.segs)}
    kids = kids_of(tree)
    path, cur = set(), q
    while True:
        i = parent[cur]
        path.add(i)
        cur = tree.segs[i].a
        if is_key(tree, kids, cur):
            break
    return extract(tree, 0, path), extract(tree, q, set())


def first_key_below(tree: tm.Tree, kids: dict[int, list[int]], pid: int) -> int:
    while not is_key(tree, kids, pid):
        pid = kids[pid][0]
    return pid


def flows_below(tree: tm.Tree, flow: dict[str, float]) -> dict[int, float]:
    kids = kids_of(tree)
    at = {pid: flow[cp] for cp, pid in tree.terminals.items()}
    below: dict[int, float] = {}
    for pid in reversed(dfs_order(kids)):
        below[pid] = at.get(pid, 0.0) + sum(below[child] for child in kids[pid])
    return below


def dfs_order(kids: dict[int, list[int]]) -> list[int]:
    order, stack = [], [0]
    while stack:
        pid = stack.pop()
        order.append(pid)
        stack += kids[pid]
    return order


def tree_key(tree: tm.Tree) -> tuple:
    """Геометрия дерева для памяти неудачных ходов и пула; дерево в плане после создания не меняется."""
    if "_key" not in tree.__dict__:
        ends = []
        for seg in tree.segs:
            a, b = (tuple(round(v, 2) for v in tree.points[pid]) for pid in (seg.a, seg.b))
            ends.append((a, b) if a <= b else (b, a))
        tree.__dict__["_key"] = (tree.root_node, tuple(sorted(ends)))
    return tree.__dict__["_key"]


def tree_geom(tree: tm.Tree) -> Any:
    if "_geom" not in tree.__dict__:
        tree.__dict__["_geom"] = shapely.union_all(tree.lines()) if tree.segs else Point(tree.points[0])
    return tree.__dict__["_geom"]


def well_formed(tree: tm.Tree) -> bool:
    """У каждой точки не больше одного входящего отрезка. Путь через два узла графа ближе 0,05 м склеивается
    Tree.point_id в петлю; при возмущённых весах такой путь бывает кратчайшим."""
    heads = [seg.b for seg in tree.segs]
    return len(heads) == len(set(heads)) and 0 not in heads and all(seg.a != seg.b for seg in tree.segs)


def touches_self(tree: tm.Tree) -> bool:
    """Отрезки дерева касаются друг друга вне круга 0,15 м вокруг общей точки — как правило topology валидатора."""
    lines = tree.lines()
    if len(lines) < 2:
        return False
    left, right = STRtree(lines).query(lines, predicate="dwithin", distance=TOUCH_TOL_M)
    for a, b in zip(left.tolist(), right.tolist()):
        if a >= b:
            continue
        shared = {tree.segs[a].a, tree.segs[a].b} & {tree.segs[b].a, tree.segs[b].b}
        if not shared:
            return True
        clip = Point(tree.points[shared.pop()]).buffer(tm.JUNCTION_CLIP_M)
        rest_a, rest_b = lines[a].difference(clip), lines[b].difference(clip)
        if not rest_a.is_empty and not rest_b.is_empty and rest_a.distance(rest_b) <= TOUCH_TOL_M:
            return True
    return False


def steiner_pairs(tree: tm.Tree, kids: dict[int, list[int]]) -> list[tuple[int, int, int]]:
    """Пары ключевых точек a и b из разных ветвей и ключевая точка top над их общим предком, по убыванию евклидова
    выигрыша точки Ферма треугольника top, a, b; пары с выигрышем меньше STEINER_MIN_GAIN_M отброшены."""
    parent = {seg.b: seg.a for seg in tree.segs}
    order = dfs_order(kids)
    chain = {0: [0]}
    for pid in order[1:]:
        chain[pid] = [pid] + chain[parent[pid]]
    keys = [pid for pid in order[1:] if is_key(tree, kids, pid)]
    pairs = []
    for k, a in enumerate(keys):
        above_a = set(chain[a])
        for b in keys[k + 1:]:
            if a in chain[b] or b in above_a:
                continue
            common = next(pid for pid in chain[b] if pid in above_a)
            top = next((pid for pid in chain[common][1:] if is_key(tree, kids, pid)), 0)
            gain = steiner_gain([tree.points[top], tree.points[a], tree.points[b]])
            if gain > STEINER_MIN_GAIN_M:
                pairs.append((gain, a, b, top))
    return [(a, b, top) for _, a, b, top in sorted(pairs, key=lambda item: -item[0])]


def steiner_gain(corners: list[tuple[float, float]]) -> float:
    spot = fermat(corners)
    return math.dist(corners[0], corners[1]) + math.dist(corners[0], corners[2]) - sum(math.dist(spot, c) for c in corners)


def trim(tree: tm.Tree) -> tm.Tree:
    """Копия дерева без висячих ветвей, которые не ведут к точкам подключения."""
    keep = set(tree.terminals.values())
    segs = tree.segs
    while True:
        starts = {seg.a for seg in segs}
        kept = [seg for seg in segs if seg.b in starts or seg.b in keep]
        if len(kept) == len(segs):
            return extract(replace(tree, segs=kept), 0, set())
        segs = kept


def fermat(points: list[tuple[float, float]]) -> tuple[float, float]:
    """Точка Ферма (геометрическая медиана) итерациями Вайсфельда."""
    xy = np.array(points, dtype=float)
    at = xy.mean(axis=0)
    for _ in range(FERMAT_ITERATIONS):
        weights = 1.0 / np.maximum(np.hypot(*(xy - at).T), FERMAT_EPS_M)
        at = (xy * weights[:, None]).sum(axis=0) / weights.sum()
    return float(at[0]), float(at[1])


def shape_estimate(tree: tm.Tree) -> tuple[int, int, int]:
    """Повороты, изломы (без проверки полигонов рядом) и камеры дерева для быстрой оценки штрафов."""
    parent = {seg.b: seg.a for seg in tree.segs}
    turns = kinks = 0
    for seg in tree.segs:
        up = parent.get(seg.a)
        if up is None:
            continue
        angle = model.deflection_deg(tree.points[up], tree.points[seg.a], tree.points[seg.b])
        if angle is not None and angle >= model.MIN_TURN_DEG:
            turns += 1
            kinks += angle < model.KINK_MAX_DEG
    chambers = sum(1 for pid, n in tree.children.items() if pid != 0 and n >= 2)
    return turns, kinks, chambers + (tree.tie.existing_type == "heat_network")


class Search:
    def __init__(self, scene: Scene, graph: VisGraph, rules: dict[str, Any], quality: list[QualityRule] | None,
                 rng: random.Random):
        self.scene, self.graph, self.rules, self.quality, self.rng = scene, graph, rules, quality, rng
        self.builder = tm.TreeBuilder(scene, graph)
        self.recon = tm.Recon(scene, rules)
        prices = model.unit_prices(quality)
        self.prices = {name: prices[name] for name in SHAPE_KEYS if prices[name]}
        self.weights_for: Callable[[int], list[float]] | None = None
        self.xy = np.array([n.xy for n in graph.nodes], dtype=float)
        self.passable = np.array([n.kind in PASSABLE for n in graph.nodes])
        self.left = 0.0
        self.unit = max((len(graph.u) / EDGES_PER_UNIT) ** MOVE_COST_POWER, MIN_MOVE_UNIT)
        self.failed: set[tuple] = set()
        self.accepted: Counter[str] = Counter()
        self.starts = 0
        self.best: Plan | None = None

    def run(self, budget: int) -> tuple[Plan | None, Plan]:
        """Лучший план без нарушений валидатора и базовый лес (тот же выбор, что tm.baseline)."""
        self.left = budget
        # цикл tm.baseline с общим построителем: таблицы Дейкстры переиспользуются, все стратегии — старты
        starts = [self.plan(tm.forest(self.builder, self.recon, partition), name)
                  for name, partition in tm.partitions(self.scene).items()]
        base = min(starts, key=lambda p: p.score)
        queue = sorted((p for p in starts if p.value < math.inf), key=lambda p: p.rank)
        pool: list[Plan] = []
        while self.left > 0 and (queue or pool):
            if queue:
                current = queue.pop(0)
            else:
                current = self.perturb(pool[self.rng.randrange(len(pool))])
                if current.value == math.inf:
                    continue
            self.starts += 1
            current = self.descend(current)
            forest = sorted(tree_key(t) for t in current.trees)
            if all(sorted(tree_key(t) for t in p.trees) != forest for p in pool):
                pool = sorted(pool + [current], key=lambda p: p.rank)[:ELITE_SIZE]
        return self.best, base

    def plan(self, trees: list[tm.Tree], strategy: str) -> Plan:
        cost = model.cost(tm.solution(trees), self.scene, self.rules)
        value = self.objective(cost)
        violations = sum(model.validate(self.scene, cost).values())
        plan = Plan(trees, value, cost.score_value, self.fast(trees), violations, len(cost.unconnected), strategy)
        self.remember(plan)
        return plan

    def remember(self, plan: Plan) -> None:
        if plan.violations == 0 and plan.value < math.inf and (self.best is None or plan.rank < self.best.rank):
            self.best = plan

    def objective(self, cost: model.CostBreakdown) -> float:
        """S или, в режиме quality, S со штрафами всех правил с ценой — как S_quality бенчмарка."""
        return math.inf if cost.violations else model.objective(cost, self.scene, self.quality)

    def fast(self, trees: list[tm.Tree]) -> float:
        value = tm.forest_estimate(self.builder, self.recon, trees) + sum(self.special_extra(t) for t in trees)
        if self.prices:
            totals = [sum(values) for values in zip(*(shape_estimate(t) for t in trees))] or [0, 0, 0]
            rub = sum(self.prices.get(name, 0.0) * total for name, total in zip(SHAPE_KEYS, totals))
            value += model.rub_to_score(rub, self.rules)
        return value

    def special_extra(self, tree: tm.Tree) -> float:
        """Надбавка Kспец специальных участков в единицах S: tm.tree_estimate считает трубу без неё."""
        if "_special" not in tree.__dict__:
            extra = 0.0
            if tree.segs:
                below = flows_below(tree, self.builder.flow)
                a = np.array([tree.points[seg.a] for seg in tree.segs], dtype=float)
                b = np.array([tree.points[seg.b] for seg in tree.segs], dtype=float)
                checked = self.graph.obstacles.check(a, b)
                for seg, length, weighted in zip(tree.segs, checked.length.tolist(), checked.cost_len.tolist()):
                    if weighted > length:
                        price = diameter_row(self.rules, tm.dn_for_flow(self.rules, below[seg.b]))["new_rub_m"]
                        extra += tm.rub(self.rules, price * (weighted - length))
            tree.__dict__["_special"] = extra
        return tree.__dict__["_special"]

    def descend(self, plan: Plan) -> Plan:
        """Спуск по окрестностям NEIGHBORHOODS: следующая окрестность — только в локальном оптимуме предыдущих,
        после принятого хода — снова первая. Внутри окрестности первое улучшение по кругу: обход продолжается
        со следующей позиции. Неудачные ходы запоминаются; остановка в локальном оптимуме или по бюджету."""
        level, position = 0, [0] * len(NEIGHBORHOODS)
        while self.left > 0 and level < len(NEIGHBORHOODS):
            moves = [item for item in self.moves(plan) if item[0][0] in NEIGHBORHOODS[level]]
            start = position[level] % max(len(moves), 1)
            for offset, (memo, move) in enumerate(moves[start:] + moves[:start]):
                if memo in self.failed:
                    continue
                if self.left <= 0:
                    return plan
                self.left -= self.unit
                trees = move()
                better = self.judge(trees, plan) if trees is not None else None
                if better is None:
                    self.failed.add(memo)
                    continue
                self.accepted[memo[0]] += 1
                plan = better
                position[level] = start + offset + 1
                level = 0
                break
            else:
                level += 1
        return plan

    def judge(self, trees: list[tm.Tree], plan: Plan) -> Plan | None:
        if not self.fits(trees):
            return None
        fast = self.fast(trees)
        if fast > plan.fast + FAST_MARGIN_S:
            return None
        cost = model.cost(tm.solution(trees), self.scene, self.rules)
        value, unconnected = self.objective(cost), len(cost.unconnected)
        if (unconnected, value) >= (plan.unconnected, plan.value - IMPROVE_EPS_S):
            return None
        violations = sum(model.validate(self.scene, cost).values())
        if violations > plan.violations:
            return None
        better = Plan(trees, value, cost.score_value, fast, violations, unconnected, plan.strategy)
        self.remember(better)
        return better

    def room(self, tie: int, others: list[tm.Tree]) -> int:
        """Свободные ветви у врезки при чужих деревьях, как в tm.forest: в точке трубы — одна врезка."""
        node = self.graph.nodes[tie]
        at_tie = [t.children[0] for t in others if t.root_node == tie]
        if node.kind == "tie_pipe":
            return 0 if at_tie else node.capacity
        return node.capacity - sum(at_tie)

    def fits(self, trees: list[tm.Tree]) -> bool:
        return all(tree.segs and tree.terminals and tree.children[0] <= self.room(tree.root_node, self.others(trees, i))
                   for i, tree in enumerate(trees))

    def moves(self, plan: Plan) -> Iterator[Move]:
        trees = plan.trees
        keys = [tree_key(t) for t in trees]
        connected = {cp for t in trees for cp in t.terminals}
        for cp in [t.cp_id for t in self.scene.terminals if t.cp_id not in connected]:
            yield ("connect", cp, tuple(keys)), partial(self.alone, trees, None, cp)
        pairs = sorted((d, i, j) for i, tree in enumerate(trees) for d, j in self.nearest(tree_geom(tree), trees, i))
        fused = set()
        for _, i, j in pairs:
            yield ("merge", keys[i], keys[j]), partial(self.merge, trees, i, j)
            if (j, i) not in fused:
                fused.add((i, j))
                yield ("fuse", keys[i], keys[j]), partial(self.fuse, trees, i, j)
        transfers = sorted((d, i, cp, j) for i, tree in enumerate(trees) if len(tree.terminals) >= 2
                           for cp, pid in tree.terminals.items() for d, j in self.nearest(Point(tree.points[pid]), trees, i))
        for _, i, cp, j in transfers:
            yield ("move", cp, keys[i], keys[j]), partial(self.move, trees, i, cp, j)
        for i, tree in enumerate(trees):
            if len(tree.terminals) >= 2:
                for cp in tree.terminals:
                    yield ("alone", cp, keys[i]), partial(self.alone, trees, i, cp)
        for i in range(len(trees)):
            yield ("retie", keys[i]), partial(self.retie, trees, i)
        for i, tree in enumerate(trees):
            kids = kids_of(tree)
            count = flows_below(tree, dict.fromkeys(tree.terminals, 1.0))
            splits = [pid for pid in dfs_order(kids) if pid != 0 and len(kids[pid]) >= 2]
            if len(kids[0]) >= 2:
                splits += [first_key_below(tree, kids, child) for child in kids[0]]
            for q in dict.fromkeys(splits):
                if 1 < count[q] < len(tree.terminals):
                    yield ("split", keys[i], tree.points[q]), partial(self.split, trees, i, q)
        for i, tree in enumerate(trees):
            kids = kids_of(tree)
            for q in dfs_order(kids)[1:]:
                if is_key(tree, kids, q) and q in tree.graph_node:
                    yield ("path", keys[i], tree.points[q]), partial(self.reroute, trees, i, q)
        for i, tree in enumerate(trees):
            if len(tree.terminals) < 2:
                continue
            kids = kids_of(tree)
            for q in dfs_order(kids)[1:]:
                if len(kids[q]) >= 2:
                    yield ("eliminate", keys[i], tree.points[q]), partial(self.eliminate, trees, i, q)
            for a, b, top in steiner_pairs(tree, kids):
                yield ("insert", keys[i], tree.points[a], tree.points[b]), partial(self.insert, trees, i, a, b, top)

    def nearest(self, geom: Any, trees: list[tm.Tree], skip: int) -> list[tuple[float, int]]:
        return sorted((geom.distance(tree_geom(t)), j) for j, t in enumerate(trees) if j != skip)[:NEAREST_TREES]

    def steiner_nodes(self, tree: tm.Tree, a: int, b: int, top: int) -> list[int]:
        """Узлы графа вне дерева для вставки: ближайшие к точке Ферма треугольника top, a, b и, если a и b на узлах
        графа, лучшие по сумме кратчайших путей до a и b при диаметрах их расходов и до top при общем расходе."""
        used = list(tree.graph_nodes())
        x, y = fermat([tree.points[top], tree.points[a], tree.points[b]])
        near = np.where(self.passable, np.hypot(self.xy[:, 0] - x, self.xy[:, 1] - y), np.inf)
        near[used] = np.inf
        found = [int(n) for n in np.argsort(near, kind="stable")[:STEINER_NEAR] if near[n] < np.inf]
        if a in tree.graph_node and b in tree.graph_node:
            below = flows_below(tree, self.builder.flow)
            parent = {seg.b: seg.a for seg in tree.segs}
            while top not in tree.graph_node:
                top = parent[top]
            cp_at = {pid: cp for cp, pid in tree.terminals.items()}
            total = np.zeros(len(self.graph.nodes))
            for pid, flow in ((a, below[a]), (b, below[b]), (top, below[a] + below[b])):
                sid = cp_at.get(pid) or self.pseudo(tree.graph_node[pid])
                total += np.asarray(self.builder.table(sid, tm.dn_for_flow(self.rules, flow), set(), set(), None)[0])
            total[~self.passable] = np.inf
            total[used] = np.inf
            found += [int(n) for n in np.argsort(total, kind="stable")[:STEINER_NEAR] if total[n] < np.inf]
        return list(dict.fromkeys(found))

    def others(self, trees: list[tm.Tree], *skip: int) -> list[tm.Tree]:
        return [t for k, t in enumerate(trees) if k not in skip]

    def with_capacity(self, tree: tm.Tree, others: list[tm.Tree]) -> tm.Tree:
        return extract(tree, 0, set(), self.room(tree.root_node, others))

    def straighten(self, tree: tm.Tree, others: list[tm.Tree]) -> tm.Tree:
        """Спрямление построителя с чужими участками текущего хода; build оставляет в foreign свои."""
        self.builder.foreign = [line for other in others for line in other.lines()]
        self.builder.straighten(tree)
        return extract(tree, 0, set())

    def pseudo(self, node: int) -> str:
        """Узел графа как обязательная точка построителя: Штейнер или ключевая точка поддерева."""
        sid = f"{PSEUDO}{node}"
        if sid not in self.builder.terminal_node:
            self.builder.terminal_node[sid] = node
            self.builder.flow[sid] = 0.0
        return sid

    def attach_subtree(self, grown: tm.Tree, sub: tm.Tree, others: list[tm.Tree]) -> tm.Tree | None:
        """Поддерево подключается от своего корня; корень вне узлов графа (точка касания пути) заменяют ветви под
        ним, и точка ветвления складывается заново."""
        if 0 in sub.graph_node:
            return self.connect(grown, sub, others)
        kids = kids_of(sub)
        parts = [extract(sub, first_key_below(sub, kids, child), set()) for child in kids[0]]
        for part in sorted(parts, key=lambda part: -sum(self.builder.flow[cp] for cp in part.terminals)):
            grown = self.attach_subtree(grown, part, others)
            if grown is None:
                return None
        return grown

    def connect(self, rest: tm.Tree, sub: tm.Tree, others: list[tm.Tree], flow: float | None = None) -> tm.Tree | None:
        """Кратчайший путь при весах диаметра расхода поддерева (или flow) от его корня до остатка дерева: сначала
        по свободной таблице, при касании своих или чужих участков — в обход них."""
        q_node = sub.graph_node.get(0)
        if q_node is None:
            return None
        sid = next((cp for cp, pid in sub.terminals.items() if pid == 0), None) or self.pseudo(q_node)
        dn = tm.dn_for_flow(self.rules, sum(self.builder.flow[cp] for cp in sub.terminals) if flow is None else flow)
        # поддерево входит в дерево rest: для blocked_by его корень общий, иначе вокруг q встанет зона чужой врезки
        sub = replace(sub, root_node=rest.root_node)
        for blocked in (False, True):
            edges, nodes = tm.blocked_by(self.graph, others + [sub], rest.root_node) if blocked else (set(), set())
            nodes.discard(rest.root_node)
            dist, pred = self.builder.table(sid, dn, nodes, edges, self.weights_for)
            targets = [(dist[n], pid, n) for pid, n in rest.graph_node.items()
                       if dist[n] < math.inf and self.builder.attachable(rest, pid)]
            if not targets:
                continue
            _, _, target = min(targets)
            trial = extract(rest, 0, set(), rest.capacity)
            path = tm.path_nodes(self.graph, pred, q_node, target)
            if not path or self.builder.attach(trial, sid, path) is not None or not well_formed(trial):
                continue
            graft(trial, trial.terminals.pop(sid), sub)
            tree = self.straighten(trial, others)
            if self.sound(tree, others):
                return tree
        return None

    def sound(self, tree: tm.Tree, others: list[tm.Tree]) -> bool:
        # при возмущённых весах и после прививки путь может задеть своё дерево, а model.cost на таком зацикливается
        return not touches_self(tree) and not tm.conflicts(tree, others) and self.builder.turns_ok(tree)

    def rebuild(self, tie: int, cps: list[str], others: list[tm.Tree]) -> tm.Tree | None:
        """Дерево Такахаши–Мацуямы на той же врезке при текущих (возмущённых) весах в обход чужих деревьев."""
        tree = self.builder.build(tie, cps, others, None, self.weights_for, self.room(tie, others))
        return tree if not self.lost(tree, cps) and well_formed(tree) and self.sound(tree, others) else None

    def lost(self, tree: tm.Tree, cps: list[str]) -> bool:
        return not tree.segs or any(cp in tree.unconnected for cp in cps)

    def options(self, cps: list[str], others: list[tm.Tree], exclude: int | None = None) -> tm.Tree | None:
        """Новое дерево блока на лучшей по предельной оценке свободной врезке, как в tm.forest."""
        free = [t for t in self.graph.tie_nodes() if t != exclude and self.room(t, others) > 0]
        loads = [(t.tie, sum(self.builder.flow[cp] for cp in t.terminals)) for t in others]
        for _, tie, tree in tm.block_options(self.builder, self.recon, cps, free, tm.TOP_TIES, loads):
            room = self.room(tie, others)
            if tree.children[0] > room or tm.conflicts(tree, others):
                tree = self.builder.build(tie, cps, others, root_capacity=room)
            if not self.lost(tree, cps) and self.builder.turns_ok(tree):
                return tree
        return None

    def detach_rest(self, trees: list[tm.Tree], i: int, q: int) -> tuple[tm.Tree, tm.Tree]:
        rest, sub = detach(trees[i], q)
        return self.straighten(rest, self.others(trees, i)), sub

    def merge(self, trees: list[tm.Tree], i: int, j: int) -> list[tm.Tree] | None:
        """Слияние: ветви дерева j без путей от его корня подключаются к дереву i."""
        others = self.others(trees, i, j)
        grown = self.with_capacity(trees[i], others)
        donor = trees[j]
        kids = kids_of(donor)
        for child in kids[0]:
            grown = self.attach_subtree(grown, extract(donor, first_key_below(donor, kids, child), set()), others)
            if grown is None:
                return None
        return [grown if k == i else t for k, t in enumerate(trees) if k != j]

    def fuse(self, trees: list[tm.Tree], i: int, j: int) -> list[tm.Tree] | None:
        """Слияние с выбором врезки: одно дерево Такахаши–Мацуямы для ОКС обоих деревьев."""
        others = self.others(trees, i, j)
        tree = self.options(list(trees[i].terminals) + list(trees[j].terminals), others)
        return None if tree is None else [tree if k == i else t for k, t in enumerate(trees) if k != j]

    def move(self, trees: list[tm.Tree], i: int, cp: str, j: int) -> list[tm.Tree] | None:
        """Перенос ОКС в соседнее дерево: путь к нему снимается, ОКС подключается к дереву j."""
        rest, sub = self.detach_rest(trees, i, trees[i].terminals[cp])
        others = self.others(trees, i, j) + [rest]
        grown = self.connect(self.with_capacity(trees[j], others), sub, others)
        if grown is None:
            return None
        return [rest if k == i else grown if k == j else t for k, t in enumerate(trees)]

    def alone(self, trees: list[tm.Tree], i: int | None, cp: str) -> list[tm.Tree] | None:
        """Разделение: ОКС получает своё дерево на лучшей свободной врезке."""
        kept = list(trees)
        if i is not None:
            kept[i], _ = self.detach_rest(trees, i, trees[i].terminals[cp])
        tree = self.options([cp], kept)
        return None if tree is None else kept + [tree]

    def split(self, trees: list[tm.Tree], i: int, q: int) -> list[tm.Tree] | None:
        """Разделение: поддерево под точкой ветвления уходит в отдельное дерево на своей врезке."""
        rest, sub = self.detach_rest(trees, i, q)
        kept = [rest if k == i else t for k, t in enumerate(trees)]
        tree = self.options(list(sub.terminals), kept)
        return None if tree is None else kept + [tree]

    def retie(self, trees: list[tm.Tree], i: int) -> list[tm.Tree] | None:
        """Перенос дерева на другую врезку."""
        tree = self.options(list(trees[i].terminals), self.others(trees, i), exclude=trees[i].root_node)
        return None if tree is None else [tree if k == i else t for k, t in enumerate(trees)]

    def reroute(self, trees: list[tm.Tree], i: int, q: int) -> list[tm.Tree] | None:
        """Перекладка ключевого пути над точкой q при весах диаметра расхода под ней."""
        others = self.others(trees, i)
        rest, sub = detach(trees[i], q)
        tree = self.connect(self.with_capacity(rest, others), sub, others)
        if tree is None or tree_key(tree) == tree_key(trees[i]):
            return None
        return [tree if k == i else t for k, t in enumerate(trees)]

    def eliminate(self, trees: list[tm.Tree], i: int, q: int) -> list[tm.Tree] | None:
        """Удаление ключевой вершины: пути над и под точкой ветвления q снимаются, поддеревья под ней по убыванию
        расхода подключаются к остатку заново и сходятся там, где путь следующего касается уже проложенных."""
        tree, others = trees[i], self.others(trees, i)
        kids = kids_of(tree)
        rest, _ = detach(tree, q)
        grown = self.with_capacity(self.straighten(rest, others), others)
        sub = extract(tree, q, set())
        # корень без узла графа: attach_subtree подключит ветви под q, а не саму точку
        sub.graph_node.pop(0, None)
        grown = self.attach_subtree(grown, sub, others)
        if grown is None or tree_key(grown) == tree_key(tree):
            return None
        return [grown if k == i else t for k, t in enumerate(trees)]

    def insert(self, trees: list[tm.Tree], i: int, a: int, b: int, top: int) -> list[tm.Tree] | None:
        """Вставка узла Штейнера: ключевые пути над a и b снимаются, путь от узла Штейнера при расходе обеих ветвей
        прокладывается к остатку дерева, затем a и b подключаются заново и сходятся у узла. Из узлов
        steiner_nodes берётся лучший по быстрой оценке; каждый сверх первого стоит хода."""
        tree, others = trees[i], self.others(trees, i)
        parent = {seg.b: k for k, seg in enumerate(tree.segs)}
        kids = kids_of(tree)
        cut = set()
        for end in (a, b):
            while True:
                cut.add(parent[end])
                end = tree.segs[parent[end]].a
                if is_key(tree, kids, end):
                    break
        below = flows_below(tree, self.builder.flow)
        rest = self.with_capacity(self.straighten(trim(extract(tree, 0, cut)), others), others)
        best = None
        for count, node in enumerate(self.steiner_nodes(tree, a, b, top)):
            if count:
                self.left -= self.unit
            hub = tm.Tree(tree.root_node, tree.tie, tree.capacity, [self.graph.nodes[node].xy], {0: node})
            grown = self.connect(rest, hub, others, below[a] + below[b])
            for end in sorted((a, b), key=lambda end: -below[end]):
                if grown is not None:
                    grown = self.attach_subtree(grown, extract(tree, end, set()), others)
            if grown is None:
                continue
            grown = self.straighten(trim(grown), others)
            if tree_key(grown) == tree_key(tree) or not self.sound(grown, others):
                continue
            forest = [grown if k == i else t for k, t in enumerate(trees)]
            if self.fits(forest):
                fast = self.fast(forest)
                if best is None or fast < best[0]:
                    best = (fast, forest)
        return None if best is None else best[1]

    def perturb(self, plan: Plan) -> Plan:
        """Рестарт: деревья плана перестраиваются по очереди при весах рёбер с случайным множителем."""
        noise = np.random.default_rng(self.rng.randrange(2 ** 32)).uniform(1 - NOISE, 1 + NOISE, len(self.graph.u))
        cache: dict[int, list[float]] = {}

        def weights_for(dn: int) -> list[float]:
            if dn not in cache:
                cache[dn] = (np.asarray(tm.edge_weights(self.graph, dn)) * noise).tolist()
            return cache[dn]

        self.weights_for = weights_for
        trees = list(plan.trees)
        for i, old in enumerate(plan.trees):
            self.left -= self.unit
            tree = self.rebuild(old.root_node, list(old.terminals), self.others(trees, i))
            if tree is not None:
                trees[i] = tree
        self.weights_for = None
        if not self.fits(trees):
            return Plan(trees, math.inf, math.inf, math.inf, 0, len(self.scene.terminals), plan.strategy)
        return self.plan(trees, plan.strategy)


def solve(scene: Scene, graph: VisGraph, rules: dict[str, Any], quality: list[QualityRule] | None, budget: int,
          seed: int) -> Solution:
    started = time.perf_counter()
    search = Search(scene, graph, rules, quality, random.Random(seed))
    best, base = search.run(budget)
    chosen = best if best is not None and best.rank <= base.rank else base
    meta = {"strategy": chosen.strategy, "budget": budget, "seed": seed, "starts": search.starts,
            "unused_budget": max(search.left, 0), "accepted": dict(sorted(search.accepted.items())),
            "baseline_score": base.score}
    solution = tm.solution(chosen.trees, meta)
    solution.elapsed = time.perf_counter() - started
    return solution
