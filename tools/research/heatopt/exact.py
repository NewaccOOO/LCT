"""Нижняя оценка по C-6. solve — точный оптимум задачи P на классах S и M: динамика Дрейфуса–Вагнера по
подмножествам терминалов и мастер-MILP выбора деревьев на HiGHS с точной ступенчатой реконструкцией. lp_bound —
потоковая LP с линейными оценками ступенчатых стоимостей снизу для любого класса. Все стоимости в единицах S.

python -m heatopt.exact --check сверяет обе оценки между собой и с базовым лесом на сценах S-1 и M-1."""
import argparse
import math
import sys
import time
from collections import defaultdict
from dataclasses import dataclass
from enum import StrEnum
from pathlib import Path
from typing import Any

import highspy
import numpy as np
from heatcheck.model import (
    NODE_TOL_M,
    chamber_cost,
    diameter_row,
)
from heatcheck.network import end_position

from heatopt import tm
from heatopt.graph import (
    PASSABLE,
    VisGraph,
    smallest_dn,
)
from heatopt.model import (
    Solution,
    TieIn,
)
from heatopt.scene import (
    Scene,
    generate,
)

MIP_GAP = 1e-3
MAX_TERMINALS = 8
LOG_DIR = Path("data/research/logs")
CHECK_SCENES = (("S", 1), ("M", 1))
CHECK_TIME_LIMIT_S = 1200.0
BOUND_TOL = 1e-6
SCORE_TOL = 1e-3
DEADLINE_STEP = 64
TIE_KINDS = ("tie_chamber", "tie_pipe")


class Status(StrEnum):
    OPTIMAL = "optimal"
    TIME_LIMIT = "time_limit"
    FAILED = "failed"


@dataclass
class Bound:
    objective: float
    bound: float
    status: Status
    elapsed: float
    cost_rub: float | None
    length_m: float | None
    solution: Solution | None
    log: Path


@dataclass
class Span:
    """Часть существующего участка между точками кандидатов врезки; ties — врезки, чья добавка через неё идёт."""
    pipe_id: str
    start: float
    end: float
    ties: list[int]


@dataclass
class Step:
    """Ступень реконструкции части: диаметр dn нужен, когда добавка больше threshold; rub и m — прирост к
    предыдущей ступени."""
    dn: int
    threshold: float
    rub: float
    meters: float


class Milp:
    """Модель HiGHS, собранная массивами: столбцы, строки и коэффициенты (строка, столбец, значение)."""

    def __init__(self):
        self.cost: list[np.ndarray] = []
        self.lower: list[np.ndarray] = []
        self.upper: list[np.ndarray] = []
        self.integer: list[np.ndarray] = []
        self.row_lower: list[np.ndarray] = []
        self.row_upper: list[np.ndarray] = []
        self.entries: list[tuple[np.ndarray, np.ndarray, np.ndarray]] = []
        self.cols = 0
        self.rows = 0
        self.offset = 0.0

    def add_cols(self, cost: Any, lower: float, upper: float, integer: bool = False) -> np.ndarray:
        cost = np.atleast_1d(np.asarray(cost, dtype=float))
        count = len(cost)
        self.cost.append(cost)
        self.lower.append(np.full(count, lower))
        self.upper.append(np.full(count, upper))
        self.integer.append(np.full(count, int(integer), dtype=np.int32))
        self.cols += count
        return np.arange(self.cols - count, self.cols)

    def add_rows(self, count: int, lower: float, upper: float) -> np.ndarray:
        self.row_lower.append(np.full(count, lower))
        self.row_upper.append(np.full(count, upper))
        self.rows += count
        return np.arange(self.rows - count, self.rows)

    def add(self, rows: Any, cols: Any, values: Any) -> None:
        rows, cols = np.atleast_1d(rows), np.atleast_1d(cols)
        shape = np.broadcast_shapes(rows.shape, cols.shape)
        self.entries.append((np.broadcast_to(rows, shape), np.broadcast_to(cols, shape), np.broadcast_to(np.asarray(values, dtype=float), shape)))

    def dominate(self, big: int, small: Any) -> None:
        """Строки big − small[i] ≥ 0 для каждого столбца small[i]."""
        small = np.atleast_1d(small)
        rows = self.add_rows(len(small), 0.0, np.inf)
        self.add(rows, big, 1.0)
        self.add(rows, small, -1.0)

    def run(self, log: Path, time_limit_s: float, gap: float | None) -> highspy.Highs:
        rows = np.concatenate([np.ravel(r) for r, _, _ in self.entries]).astype(np.int32)
        cols = np.concatenate([np.ravel(c) for _, c, _ in self.entries]).astype(np.int32)
        values = np.concatenate([np.ravel(v) for _, _, v in self.entries])
        order = np.lexsort((rows, cols))
        rows, cols, values = rows[order], cols[order], values[order]
        start = np.searchsorted(cols, np.arange(self.cols + 1)).astype(np.int32)
        log.parent.mkdir(parents=True, exist_ok=True)
        highs = highspy.Highs()
        highs.setOptionValue("output_flag", True)
        highs.setOptionValue("log_to_console", False)
        highs.setOptionValue("log_file", str(log))
        highs.setOptionValue("time_limit", max(time_limit_s, 1.0))
        if gap is not None:
            highs.setOptionValue("mip_rel_gap", gap)
        highs.passModel(
            self.cols, self.rows, len(values), int(highspy.MatrixFormat.kColwise), int(highspy.ObjSense.kMinimize),
            self.offset, np.concatenate(self.cost), np.concatenate(self.lower), np.concatenate(self.upper),
            np.concatenate(self.row_lower), np.concatenate(self.row_upper), start, rows, values, np.concatenate(self.integer),
        )
        highs.run()
        return highs


def status_of(highs: highspy.Highs) -> Status:
    status = highs.getModelStatus()
    if status == highspy.HighsModelStatus.kOptimal:
        return Status.OPTIMAL
    if status == highspy.HighsModelStatus.kTimeLimit:
        return Status.TIME_LIMIT
    return Status.FAILED


def log_path(scene: Scene, kind: str) -> Path:
    return LOG_DIR / f"{scene.path.stem}-{kind}.log"


def tie_in_of(graph: VisGraph, node: int) -> TieIn:
    n = graph.nodes[node]
    return TieIn(n.ref, "heat_chamber" if n.kind == "tie_chamber" else "heat_network", n.xy)


def spans(scene: Scene, graph: VisGraph) -> list[Span]:
    """Части существующей сети, по которым идёт добавка врезок графа: цепочка upstream_object_id целиком, у трубы
    врезки — от точки врезки к источнику (как heatcheck.network.pieces и tm.Recon)."""
    recon = tm.Recon(scene, graph.rules)
    full: dict[str, list[int]] = defaultdict(list)
    partial: dict[str, list[tuple[float, int]]] = defaultdict(list)
    for t in graph.tie_nodes():
        node = graph.nodes[t]
        if node.kind == "tie_pipe":
            partial[node.ref].append((recon.position(node.ref, node.xy), t))
        existing = scene.inp.by_id[node.ref]
        for pipe_id in recon.chain(str(existing.props.get("upstream_object_id"))):
            full[pipe_id].append(t)
    result = []
    for pipe_id in sorted(set(full) | set(partial)):
        length = scene.pipes[pipe_id].geom.length
        bounds = [0.0] + sorted(min(max(p, 0.0), length) for p, _ in partial[pipe_id]) + [length]
        for start, end in zip(bounds, bounds[1:]):
            if end > start:
                below = [t for p, t in partial[pipe_id] if p >= end - NODE_TOL_M]
                result.append(Span(pipe_id, start, end, full[pipe_id] + below))
    return result


def recon_steps(rules: dict[str, Any], pipe: Any, length: float) -> list[Step]:
    """Ступени реконструкции части длиной length по диаметрам таблицы выше существующего."""
    table = rules["diameters"]
    existing_flow = float(pipe.props.get("flow_tph", 0))
    index = next(i for i, row in enumerate(table) if row["dn"] == pipe.props["diameter"])
    steps = []
    for i in range(index + 1, len(table)):
        previous = table[i - 1]["recon_rub_m"] if i > index + 1 else 0.0
        steps.append(Step(table[i]["dn"], table[i - 1]["capacity_tph"] - existing_flow,
                          length * (table[i]["recon_rub_m"] - previous), length if i == index + 1 else 0.0))
    return steps


def chamber_steps(rules: dict[str, Any], diameter: int) -> list[tuple[int, float]]:
    """(диаметр, прирост рублей) ступеней реконструкции камеры исходного диаметра diameter."""
    steps, previous = [], 0.0
    for row in rules["diameters"]:
        price = chamber_cost(rules, row["dn"])
        if row["dn"] > diameter and price > previous:
            steps.append((row["dn"], price - previous))
            previous = price
    return steps


class SubsetTrees:
    """Шаг 1 C-6. label[D, v] — наименьшая S-стоимость дерева терминалов D, которое последним ребром входит в v
    (или сливается в v с камерой); merged[D, v] — слияние в v без камеры. cost[D, j] — дерево врезки ties[j]
    с ветвлением в корне, врезкой и камерой врезки в трубу."""

    def __init__(self, scene: Scene, graph: VisGraph, rules: dict[str, Any], deadline: float):
        self.graph, self.rules, self.scene, self.deadline = graph, rules, scene, deadline
        node_of = graph.terminal_nodes()
        self.terminals = [node_of[t.cp_id] for t in scene.terminals]
        flows = [t.flow for t in scene.terminals]
        k = len(flows)
        if k > MAX_TERMINALS:
            raise ValueError(f"точный оптимум считается не больше чем для {MAX_TERMINALS} терминалов, в сцене {k}")
        size = 1 << k
        members = [[i for i in range(k) if mask >> i & 1] for mask in range(size)]
        self.members = members
        self.flow = np.array([sum(flows[i] for i in m) for m in members])
        self.dn = [tm.dn_for_flow(rules, f) for f in self.flow]
        self.peak_dn = [tm.dn_for_flow(rules, max((flows[i] for i in m), default=0.0)) for m in members]
        self.masks = sorted(range(1, size), key=int.bit_count)
        n = len(graph.nodes)
        self.passable = np.array([node.kind in PASSABLE for node in graph.nodes])
        self.distances: dict[int, np.ndarray] = {}
        self.label = np.full((size, n), np.inf)
        self.merged = np.full((size, n), np.inf)
        self.source = np.zeros((size, n), dtype=np.int32)
        self.merge_split = np.zeros((size, n), dtype=np.int32)
        self.subsets(n)
        self.distances.clear()
        self.roots()

    def all_pairs(self, dn: int) -> np.ndarray:
        """Флойд–Уоршелл с промежуточными узлами только из проходимых: через терминалы и врезки путь не идёт."""
        if dn not in self.distances:
            weights = np.asarray(tm.edge_weights(self.graph, dn))
            dist = np.full((len(self.graph.nodes), len(self.graph.nodes)), np.inf)
            dist[self.graph.u, self.graph.v] = weights
            dist[self.graph.v, self.graph.u] = weights
            np.fill_diagonal(dist, 0.0)
            for step, k in enumerate(np.nonzero(self.passable)[0]):
                if step % DEADLINE_STEP == 0 and time.perf_counter() > self.deadline:
                    raise TimeoutError
                np.minimum(dist, dist[:, k, None] + dist[k], out=dist)
            self.distances[dn] = dist
        return self.distances[dn]

    def subsets(self, n: int) -> None:
        columns = np.arange(n)
        for mask in self.masks:
            if time.perf_counter() > self.deadline:
                raise TimeoutError
            dist = self.all_pairs(self.dn[mask])
            if mask.bit_count() == 1:
                term = self.terminals[mask.bit_length() - 1]
                self.label[mask] = dist[term]
                self.source[mask] = term
                continue
            low = mask & -mask
            best = np.full(n, np.inf)
            split = np.zeros(n, dtype=np.int32)
            sub = (mask - 1) & mask
            while sub:
                if sub & low:
                    rest = mask ^ sub
                    value = self.label[sub] + np.minimum(self.label[rest], self.merged[rest])
                    better = value < best
                    best[better] = value[better]
                    split[better] = sub
                sub = (sub - 1) & mask
            best[~self.passable] = np.inf
            self.merged[mask] = best
            self.merge_split[mask] = split
            sources = np.nonzero(np.isfinite(best))[0]
            if not len(sources):
                continue
            camera = tm.rub(self.rules, chamber_cost(self.rules, self.dn[mask]))
            through = best[sources, None] + camera + dist[sources]
            arg = through.argmin(axis=0)
            self.label[mask] = through[arg, columns]
            self.source[mask] = sources[arg]

    def roots(self) -> None:
        """Корень ветвится без камеры; у врезки в трубу камера по наибольшему из диаметров рёбер корня и трубы,
        поэтому разбиение по детям корня считается отдельно для каждой цены камеры theta."""
        graph, rules = self.graph, self.rules
        self.ties = graph.tie_nodes()
        edge = self.label[:, self.ties]
        camera = np.zeros_like(edge)
        for j, t in enumerate(self.ties):
            node = graph.nodes[t]
            if node.kind == "tie_pipe":
                pipe_dn = self.scene.pipes[node.ref].props["diameter"]
                camera[:, j] = [tm.rub(rules, chamber_cost(rules, max(dn, pipe_dn))) for dn in self.dn]
        self.thetas = np.unique(camera[1:])
        self.root_split: list[np.ndarray] = []
        best = np.full(edge.shape, np.inf)
        self.choice = np.zeros(edge.shape, dtype=np.int32)
        for index, theta in enumerate(self.thetas):
            allowed = np.where(camera <= theta, edge, np.inf)
            value = allowed.copy()
            split = np.repeat(np.arange(len(edge), dtype=np.int32)[:, None], len(self.ties), axis=1)
            for mask in self.masks:
                low = mask & -mask
                sub = (mask - 1) & mask
                while sub:
                    if sub & low:
                        candidate = allowed[sub] + value[mask ^ sub]
                        better = candidate < value[mask]
                        value[mask, better] = candidate[better]
                        split[mask, better] = sub
                    sub = (sub - 1) & mask
            total = value + theta
            better = total < best
            best[better] = total[better]
            self.choice[better] = index
            self.root_split.append(split)
        best[0] = np.inf
        self.cost = best + tm.rub(rules, rules["tie_in_cost"])

    def tree(self, mask: int, j: int) -> tuple[list[list[int]], dict[int, int], float, float]:
        """Дерево cost[mask, j]: пути узлов графа, диаметры путей, рубли и метры."""
        paths: list[tuple[list[int], list[int], int]] = []
        junctions: list[int] = []
        t = self.ties[j]
        split = self.root_split[self.choice[mask, j]]
        rest, root_dn = mask, 0
        while rest:
            sub = int(split[rest, j])
            self.arrive(sub, t, paths, junctions)
            root_dn = max(root_dn, self.dn[sub])
            rest ^= sub
        rules, graph = self.rules, self.graph
        rub = rules["tie_in_cost"] + sum(chamber_cost(rules, self.dn[m]) for m in junctions)
        node = graph.nodes[t]
        if node.kind == "tie_pipe":
            rub += chamber_cost(rules, max(root_dn, self.scene.pipes[node.ref].props["diameter"]))
        meters = 0.0
        for _, edges, dn in paths:
            meters += float(graph.length[edges].sum())
            rub += diameter_row(rules, dn)["new_rub_m"] * float(graph.cost_len[edges].sum())
        return [nodes for nodes, _, _ in paths], {i: dn for i, (_, _, dn) in enumerate(paths)}, rub, meters

    def arrive(self, mask: int, v: int, paths: list[tuple[list[int], list[int], int]], junctions: list[int]) -> None:
        graph = self.graph
        u = int(self.source[mask, v])
        if u != v:
            _, pred = tm.dijkstra(graph, u, tm.edge_weights(graph, self.dn[mask]), targets={v})
            nodes, edges = [v], []
            while nodes[-1] != u:
                e = pred[nodes[-1]]
                edges.append(e)
                nodes.append(int(graph.u[e]) if int(graph.v[e]) == nodes[-1] else int(graph.v[e]))
            paths.append((nodes[::-1], edges, self.dn[mask]))
        if mask.bit_count() > 1:
            junctions.append(mask)
            self.split_at(mask, u, paths, junctions)

    def split_at(self, mask: int, v: int, paths: list[tuple[list[int], list[int], int]], junctions: list[int]) -> None:
        sub = int(self.merge_split[mask, v])
        rest = mask ^ sub
        self.arrive(sub, v, paths, junctions)
        if self.merged[rest, v] < self.label[rest, v]:
            self.split_at(rest, v, paths, junctions)
        else:
            self.arrive(rest, v, paths, junctions)


def solve(scene: Scene, graph: VisGraph, rules: dict[str, Any], time_limit_s: float, connect_all: bool = False) -> Bound:
    """Точный оптимум P (C-6): деревья подмножеств и мастер-MILP выбора деревьев с реконструкцией. connect_all
    запрещает оставлять неподключённым ОКС, до которого в графе есть дерево: база разрыва AC-1.2 (журнал D-12)."""
    started = time.perf_counter()
    log = log_path(scene, "exact-all" if connect_all else "exact")
    try:
        trees = SubsetTrees(scene, graph, rules, started + time_limit_s)
    except TimeoutError:
        return Bound(math.inf, 0.0, Status.TIME_LIMIT, time.perf_counter() - started, None, None, None, log)
    k = len(trees.terminals)
    total = scene.total_flow()
    ties = trees.ties
    penalty_rub = [rules["penalty"]["fixed"] + rules["penalty"]["per_tph"] * t.flow for t in scene.terminals]
    mask_penalty = np.array([tm.rub(rules, sum(penalty_rub[i] for i in m)) for m in trees.members])
    # дерево дороже штрафа своих ОКС не выбирается: реконструкция от лишней добавки не дешевеет
    masks, js = np.nonzero(np.isfinite(trees.cost) if connect_all else trees.cost < mask_penalty[:, None])
    reachable = [bool(np.isfinite(trees.cost[1 << i]).any()) for i in range(k)]
    milp = Milp()
    x = milp.add_cols(trees.cost[masks, js], 0.0, 1.0, integer=True)
    u = milp.add_cols([tm.rub(rules, p) for p in penalty_rub], 0.0, [0.0 if connect_all and r else 1.0 for r in reachable], integer=True)
    cover = milp.add_rows(k, 1.0, 1.0)
    milp.add(cover, u, 1.0)
    for i in range(k):
        milp.add(cover[i], x[(masks >> i) & 1 == 1], 1.0)
    load = milp.add_cols(np.zeros(len(ties)), 0.0, np.inf)
    balance = milp.add_rows(len(ties), 0.0, 0.0)
    milp.add(balance, load, 1.0)
    milp.add(balance[js], x, -trees.flow[masks])
    capacity = [1 if graph.nodes[t].kind == "tie_pipe" else graph.nodes[t].capacity for t in ties]
    for j, cap in enumerate(capacity):
        milp.add(milp.add_rows(1, -np.inf, cap), x[js == j], 1.0)

    column_of = {t: j for j, t in enumerate(ties)}
    money_cols, money_rub, money_meters = [], [], []
    span_steps: dict[str, list[tuple[Span, list[tuple[Step, int]]]]] = defaultdict(list)
    for span in spans(scene, graph):
        pipe = scene.pipes[span.pipe_id]
        loads = load[[column_of[t] for t in span.ties]]
        made = []
        for step in recon_steps(rules, pipe, span.end - span.start):
            threshold = max(step.threshold, 0.0)
            if threshold >= total:
                break
            z = milp.add_cols(tm.rub(rules, step.rub) + tm.meters(rules, step.meters), 0.0, 1.0, integer=True)
            row = milp.add_rows(1, -np.inf, threshold)
            milp.add(row, loads, 1.0)
            milp.add(row, z, -(total - threshold))
            money_cols.append(z[0])
            money_rub.append(step.rub)
            money_meters.append(step.meters)
            made.append((step, int(z[0])))
        span_steps[span.pipe_id].append((span, made))

    for j, t in enumerate(ties):
        node = graph.nodes[t]
        if node.kind != "tie_chamber":
            continue
        chamber = scene.chambers[node.ref]
        mine = np.nonzero(js == j)[0]
        if not len(mine):
            continue
        used = int(milp.add_cols(0.0, 0.0, 1.0)[0])
        milp.dominate(used, x[mine])
        for dn, price in chamber_steps(rules, chamber.props["diameter"]):
            q = int(milp.add_cols(tm.rub(rules, price), 0.0, 1.0)[0])
            money_cols.append(q)
            money_rub.append(price)
            money_meters.append(0.0)
            peak = mine[np.array([trees.peak_dn[m] >= dn for m in masks[mine]], dtype=bool)]
            milp.dominate(q, x[peak])
            for pipe in scene.inp.chamber_links.get(chamber.id, []):
                if pipe.props["diameter"] >= dn:
                    milp.dominate(q, used)
                    continue
                parts = span_steps.get(pipe.id)
                if not parts:
                    continue
                span, made = parts[0] if end_position(scene.inp, pipe, chamber.geom) == 0.0 else parts[-1]
                z = next((col for step, col in made if step.dn >= dn), None)
                if z is not None:
                    milp.add(milp.add_rows(1, -1.0, np.inf), [q, z, used], [1.0, -1.0, -1.0])

    highs = milp.run(log, started + time_limit_s - time.perf_counter(), MIP_GAP)
    status = status_of(highs)
    info = highs.getInfo()
    elapsed = time.perf_counter() - started
    if status == Status.FAILED or info.primal_solution_status != highspy.SolutionStatus.kSolutionStatusFeasible:
        bound = info.mip_dual_bound if status == Status.TIME_LIMIT else 0.0
        return Bound(math.inf, bound, status, elapsed, None, None, None, log)
    values = np.round(np.asarray(highs.getSolution().col_value))
    cost_rub = sum(p for p, chosen in zip(penalty_rub, values[u]) if chosen)
    cost_rub += float(np.dot(values[money_cols], money_rub))
    length_m = float(np.dot(values[money_cols], money_meters))
    edges, dn, tie_ins, meta = [], {}, [], []
    for index in np.nonzero(values[x] > 0)[0]:
        mask, j = int(masks[index]), int(js[index])
        paths, dns, rub, meters = trees.tree(mask, j)
        dn.update({len(edges) + i: d for i, d in dns.items()})
        edges += [[graph.nodes[n].xy for n in nodes] for nodes in paths]
        tie_ins.append(tie_in_of(graph, ties[j]))
        meta.append({"oks": [scene.terminals[i].oks_id for i in trees.members[mask]], "tie": graph.nodes[ties[j]].ref})
        cost_rub += rub
        length_m += meters
    objective = tm.rub(rules, cost_rub) + tm.meters(rules, length_m)
    solution = Solution(edges, tie_ins, dn=dn, elapsed=elapsed, meta={"trees": meta})
    return Bound(objective, info.mip_dual_bound, status, elapsed, cost_rub, length_m, solution, log)


def lp_bound(scene: Scene, graph: VisGraph, rules: dict[str, Any], time_limit_s: float) -> Bound:
    """Потоковая LP (C-6): добавка идёт от врезок по новой сети к терминалам; ступенчатые стоимости заменены
    линейными оценками снизу, камеры ветвления и реконструкция камер — нулём."""
    started = time.perf_counter()
    log = log_path(scene, "lp")
    total = scene.total_flow()
    kinds = np.array([node.kind for node in graph.nodes])
    passable = np.isin(kinds, PASSABLE)
    is_tie = np.isin(kinds, TIE_KINDS)
    emit = passable | is_tie
    receive = passable | (kinds == "terminal")
    unit = np.full(len(graph.u), np.inf)
    for row in rules["diameters"]:
        unit = np.minimum(unit, np.asarray(tm.edge_weights(graph, row["dn"])) / min(row["capacity_tph"], total))
        if row["capacity_tph"] >= total:
            break
    forward = emit[graph.u] & receive[graph.v]
    backward = emit[graph.v] & receive[graph.u]
    tails = np.concatenate([graph.u[forward], graph.v[backward]])
    heads = np.concatenate([graph.v[forward], graph.u[backward]])

    milp = Milp()
    nodes = milp.add_rows(len(graph.nodes), 0.0, 0.0)
    arcs = milp.add_cols(np.concatenate([unit[forward], unit[backward]]), 0.0, np.inf)
    milp.add(nodes[tails], arcs, -1.0)
    milp.add(nodes[heads], arcs, 1.0)
    ties = graph.tie_nodes()
    smallest_camera = min(c["cost"] for c in rules["chamber_cost"])
    # у врезки в камеру новой камеры нет: только tie_in_cost
    tie_rub = [rules["tie_in_cost"] + (smallest_camera if graph.nodes[t].kind == "tie_pipe" else 0.0) for t in ties]
    supply = milp.add_cols(np.array([tm.rub(rules, r) for r in tie_rub]) / total, 0.0, np.inf)
    milp.add(nodes[ties], supply, 1.0)
    node_of = graph.terminal_nodes()
    penalties = np.array([tm.rub(rules, rules["penalty"]["fixed"] + rules["penalty"]["per_tph"] * t.flow) for t in scene.terminals])
    served = milp.add_cols(-penalties, 0.0, 1.0)
    milp.offset = float(penalties.sum())
    milp.add(nodes[[node_of[t.cp_id] for t in scene.terminals]], served, [-t.flow for t in scene.terminals])

    column_of = {t: j for j, t in enumerate(ties)}
    for span in spans(scene, graph):
        pipe = scene.pipes[span.pipe_id]
        existing_flow = float(pipe.props.get("flow_tph", 0))
        length = span.end - span.start
        slopes = [
            length * (tm.rub(rules, row["recon_rub_m"]) + tm.meters(rules, 1.0)) / min(row["capacity_tph"] - existing_flow, total)
            for row in rules["diameters"] if row["dn"] > pipe.props["diameter"] and row["capacity_tph"] > existing_flow
        ]
        if not slopes:
            continue
        slope = min(slopes)
        slack = max(diameter_row(rules, pipe.props["diameter"])["capacity_tph"] - existing_flow, 0.0)
        recon = milp.add_cols(1.0, 0.0, np.inf)
        row = milp.add_rows(1, -slope * slack, np.inf)
        milp.add(row, recon, 1.0)
        milp.add(row, supply[[column_of[t] for t in span.ties]], -slope)

    highs = milp.run(log, started + time_limit_s - time.perf_counter(), None)
    status = status_of(highs)
    elapsed = time.perf_counter() - started
    if status != Status.OPTIMAL:
        return Bound(math.inf, 0.0, status, elapsed, None, None, None, log)
    value = highs.getInfo().objective_function_value
    return Bound(value, value, status, elapsed, None, None, None, log)


def check() -> None:
    """На S-1 и M-1: оптимум доказан, LP ≤ граница MILP ≤ решение MILP, граница не выше S базового леса."""
    failures = []
    optimal = 0
    for cls, seed in CHECK_SCENES:
        label = f"{cls}-{seed}"
        scene = Scene.load(generate(cls, seed))
        rules = scene.rules
        bound_graph = VisGraph.build(scene, dn_guess=smallest_dn(scene, rules))
        lp = lp_bound(scene, bound_graph, rules, CHECK_TIME_LIMIT_S)
        exact = solve(scene, bound_graph, rules, CHECK_TIME_LIMIT_S)
        candidates = VisGraph.build(scene, tangent=True)
        _, base, _ = tm.baseline(scene, candidates)
        print(f"{label}: граф {len(bound_graph.nodes)} узлов, {len(bound_graph.u)} рёбер; LP={lp.bound:.4f} ({lp.status}, {lp.elapsed:.1f} с); "
              f"MILP S={exact.objective:.4f} граница={exact.bound:.4f} ({exact.status}, {exact.elapsed:.1f} с); "
              f"базовый лес S={base.score_value:.4f}")
        if exact.status == Status.OPTIMAL:
            optimal += 1
        else:
            failures.append(f"{label}: MILP завершился со статусом {exact.status}")
        if lp.status != Status.OPTIMAL:
            failures.append(f"{label}: LP завершилась со статусом {lp.status}")
        if lp.bound > exact.bound + BOUND_TOL:
            failures.append(f"{label}: LP-граница {lp.bound:.6f} выше границы MILP {exact.bound:.6f}")
        if exact.bound > exact.objective + BOUND_TOL:
            failures.append(f"{label}: граница MILP {exact.bound:.6f} выше его решения {exact.objective:.6f}")
        if exact.bound > base.score_value + SCORE_TOL:
            failures.append(f"{label}: граница MILP {exact.bound:.4f} выше S базового леса {base.score_value:.4f}")
    for failure in failures:
        print(failure)
    if failures:
        sys.exit(1)
    print(f"EXACT CHECK OK scenes={len(CHECK_SCENES)} optimal={optimal}")


def main() -> None:
    parser = argparse.ArgumentParser(prog="heatopt.exact", description="Проверка точной модели и LP-границы на S-1 и M-1")
    parser.add_argument("--check", action="store_true", required=True)
    parser.parse_args()
    check()


if __name__ == "__main__":
    main()
