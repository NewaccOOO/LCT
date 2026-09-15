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
from dataclasses import dataclass
from functools import partial
from typing import Any

import numpy as np
import shapely
from heatcheck.model import diameter_row
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

DEFAULT_BUDGET = 120
# ход стоит len(graph.u) / EDGES_PER_UNIT единиц бюджета, но не меньше MIN_MOVE_UNIT: Дейкстра и поиск касаний
# растут с числом рёбер, а сборка и проверка варианта на малом графе стоят столько же
EDGES_PER_UNIT = 100_000
MIN_MOVE_UNIT = 0.25
ELITE_SIZE = 4
NOISE = 0.3
FAST_MARGIN_S = 0.02
IMPROVE_EPS_S = 1e-6
NEAREST_TREES = 2
STEINER_NEAR = 3
PSEUDO = "steiner:"
SHAPE_KEYS = ("turn", "kink", "chamber")

Move = tuple[tuple, Callable[[], list[tm.Tree] | None]]


@dataclass
class Plan:
    trees: list[tm.Tree]
    value: float
    score: float
    fast: float
    violations: int
    strategy: str


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
    """Отрезки дерева касаются друг друга вне круга 0,15 м вокруг общей точки (как правило топологии)."""
    lines = tree.lines()
    if len(lines) < 2:
        return False
    left, right = STRtree(lines).query(lines, predicate="dwithin", distance=2 * tm.TOUCH_M)
    for a, b in zip(left.tolist(), right.tolist()):
        if a >= b:
            continue
        shared = {tree.segs[a].a, tree.segs[a].b} & {tree.segs[b].a, tree.segs[b].b}
        if not shared:
            return True
        clip = Point(tree.points[shared.pop()]).buffer(tm.SHARED_ROOT_CLIP_M)
        if lines[a].difference(clip).distance(lines[b].difference(clip)) <= 2 * tm.TOUCH_M:
            return True
    return False


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
        self.unit = max(len(graph.u) / EDGES_PER_UNIT, MIN_MOVE_UNIT)
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
        queue = sorted((p for p in starts if p.value < math.inf), key=lambda p: p.value)
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
                pool = sorted(pool + [current], key=lambda p: p.value)[:ELITE_SIZE]
        return self.best, base

    def plan(self, trees: list[tm.Tree], strategy: str) -> Plan:
        cost = model.cost(tm.solution(trees), self.scene, self.rules)
        value = self.objective(cost)
        violations = sum(model.validate(self.scene, cost).values())
        plan = Plan(trees, value, cost.score_value, self.fast(trees), violations, strategy)
        self.remember(plan)
        return plan

    def remember(self, plan: Plan) -> None:
        if plan.violations == 0 and plan.value < math.inf and (self.best is None or plan.value < self.best.value):
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
        """Первое улучшение с памятью неудачных ходов; остановка в локальном оптимуме или по бюджету."""
        while self.left > 0:
            for memo, move in self.moves(plan):
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
                break
            else:
                return plan
        return plan

    def judge(self, trees: list[tm.Tree], plan: Plan) -> Plan | None:
        if not self.fits(trees):
            return None
        fast = self.fast(trees)
        if fast > plan.fast + FAST_MARGIN_S:
            return None
        cost = model.cost(tm.solution(trees), self.scene, self.rules)
        value = self.objective(cost)
        if value >= plan.value - IMPROVE_EPS_S:
            return None
        violations = sum(model.validate(self.scene, cost).values())
        if violations > plan.violations:
            return None
        better = Plan(trees, value, cost.score_value, fast, violations, plan.strategy)
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
            below = flows_below(tree, self.builder.flow)
            kids = kids_of(tree)
            for q in dfs_order(kids)[1:]:
                if len(kids[q]) >= 2:
                    yield ("eliminate", keys[i], tree.points[q]), partial(self.eliminate, trees, i, q)
            branches = {tree.graph_node[pid]: below[pid] for pid in dfs_order(kids)
                        if pid != 0 and len(kids[pid]) >= 2 and pid in tree.graph_node}
            for node, flow in self.steiner_candidates(tree, kids, below):
                yield ("insert", keys[i], node), partial(self.resteiner, trees, i, {**branches, node: flow})

    def nearest(self, geom: Any, trees: list[tm.Tree], skip: int) -> list[tuple[float, int]]:
        return sorted((geom.distance(tree_geom(t)), j) for j, t in enumerate(trees) if j != skip)[:NEAREST_TREES]

    def steiner_candidates(self, tree: tm.Tree, kids: dict[int, list[int]], below: dict[int, float]) -> list[tuple[int, float]]:
        """Узлы графа, ближайшие к точкам ветвления дерева (включая корень с несколькими ветвями)."""
        used = tree.graph_nodes()
        found: dict[int, float] = {}
        for pid in dfs_order(kids):
            if len(kids[pid]) < 2:
                continue
            x, y = tree.points[pid]
            dist = np.where(self.passable, np.hypot(self.xy[:, 0] - x, self.xy[:, 1] - y), np.inf)
            order = np.argsort(dist, kind="stable")[:STEINER_NEAR + len(used) + len(found)].tolist()
            near = [n for n in order if n not in used and n not in found and dist[n] < np.inf]
            for node in near[:STEINER_NEAR]:
                found[node] = below[pid]
        return list(found.items())

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

    def connect(self, rest: tm.Tree, sub: tm.Tree, others: list[tm.Tree]) -> tm.Tree | None:
        """Кратчайший путь при весах диаметра расхода поддерева от его корня до остатка дерева: сначала по
        свободной таблице, при касании своих или чужих участков — в обход них."""
        q_node = sub.graph_node.get(0)
        if q_node is None:
            return None
        sid = next((cp for cp, pid in sub.terminals.items() if pid == 0), None) or self.pseudo(q_node)
        dn = tm.dn_for_flow(self.rules, sum(self.builder.flow[cp] for cp in sub.terminals))
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

    def rebuild(self, tie: int, cps: list[str], steiner: dict[int, float], others: list[tm.Tree]) -> tm.Tree | None:
        """Дерево Такахаши–Мацуямы с узлами Штейнера как обязательными точками; висячие ветви к ним обрезаются."""
        extra = [self.pseudo(node) for node in steiner]
        weight_dn = {sid: tm.dn_for_flow(self.rules, flow) for sid, flow in zip(extra, steiner.values())}
        room = self.room(tie, others)
        tree = None
        if self.weights_for is None:
            tree = self.builder.build(tie, cps + extra, (), weight_dn, root_capacity=room)
        if tree is None or self.lost(tree, cps) or tm.conflicts(tree, others):
            tree = self.builder.build(tie, cps + extra, others, weight_dn, self.weights_for, room)
        if self.lost(tree, cps) or not well_formed(tree):
            return None
        tree = self.prune(tree, cps, others)
        return tree if self.sound(tree, others) else None

    def lost(self, tree: tm.Tree, cps: list[str]) -> bool:
        return not tree.segs or any(cp in tree.unconnected for cp in cps)

    def prune(self, tree: tm.Tree, cps: list[str], others: list[tm.Tree]) -> tm.Tree:
        tree.terminals = {cp: pid for cp, pid in tree.terminals.items() if cp in cps}
        keep = set(tree.terminals.values())
        segs = tree.segs
        while True:
            starts = {seg.a for seg in segs}
            kept = [seg for seg in segs if seg.b in starts or seg.b in keep]
            if len(kept) == len(segs):
                break
            segs = kept
        tree.segs = segs
        tree.children = defaultdict(int, Counter(seg.a for seg in segs))
        return self.straighten(tree, others)

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
            _, sub = detach(donor, first_key_below(donor, kids, child))
            grown = self.connect(grown, sub, others)
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
        subs = [extract(tree, first_key_below(tree, kids, child), set()) for child in kids[q]]
        for sub in sorted(subs, key=lambda sub: -sum(self.builder.flow[cp] for cp in sub.terminals)):
            grown = self.connect(grown, sub, others)
            if grown is None:
                return None
        return None if tree_key(grown) == tree_key(tree) else [grown if k == i else t for k, t in enumerate(trees)]

    def resteiner(self, trees: list[tm.Tree], i: int, steiner: dict[int, float]) -> list[tm.Tree] | None:
        """Вставка узла Штейнера: перестройка дерева с узлами ветвления и новым узлом как обязательными точками."""
        tree = self.rebuild(trees[i].root_node, list(trees[i].terminals), steiner, self.others(trees, i))
        return None if tree is None else [tree if k == i else t for k, t in enumerate(trees)]

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
            tree = self.rebuild(old.root_node, list(old.terminals), {}, self.others(trees, i))
            if tree is not None:
                trees[i] = tree
        self.weights_for = None
        if not self.fits(trees):
            return Plan(trees, math.inf, math.inf, math.inf, 0, plan.strategy)
        return self.plan(trees, plan.strategy)


def solve(scene: Scene, graph: VisGraph, rules: dict[str, Any], quality: list[QualityRule] | None, budget: int,
          seed: int) -> Solution:
    started = time.perf_counter()
    search = Search(scene, graph, rules, quality, random.Random(seed))
    best, base = search.run(budget)
    chosen = best if best is not None and best.value <= base.value else base
    meta = {"strategy": chosen.strategy, "budget": budget, "seed": seed, "starts": search.starts,
            "unused_budget": max(search.left, 0), "accepted": dict(sorted(search.accepted.items())),
            "baseline_score": base.score}
    solution = tm.solution(chosen.trees, meta)
    solution.elapsed = time.perf_counter() - started
    return solution
