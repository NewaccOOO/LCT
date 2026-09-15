"""Единая модель стоимости (C-3): решение любого алгоритма собирается в вариант выхода по правилам сервиса
и считается по разделам 6–10 CONSTRAINTS.md и docs/interpretation.md.

Сборка повторяет NetworkAssembler: участки режутся в точках врезки, камер, смены диаметра и на границах
специальных зон; специальные зоны, расходы, реконструкция и камеры считаются функциями валидатора heatcheck,
поэтому стоимость совпадает с тем, что проверяет правило cost.
"""
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
    Feature,
    Output,
    Variant,
    chamber_cost,
    diameter_for,
    diameter_row,
    next_diameter,
)
from heatcheck.validate import check
from heatcheck.network import (
    Net,
    build_net,
    chamber_required,
    cluster,
    deflection_deg,
    network_loads,
    oks_flow,
    piece_geom,
    pieces as load_pieces,
    pipe_required,
    special_zones,
)
from pyproj import Transformer
from shapely import STRtree
from shapely.geometry import (
    LineString,
    Point,
    mapping,
)
from shapely.ops import substring

from heatopt.scene import Scene

INSERT_M = 2.0
CUT_MARGIN_M = 0.5
INSERT_GAP_M = 1.5
# первый участок от врезки не делится вставкой: валидатор прощает ему сближение с трубами врезки
TIE_CLEAR_M = 10.0
CUT_DEDUP_M = NODE_TOL_M
KINK_MAX_DEG = 50.0
KINK_FREE_M = 5.0
MIN_TURN_DEG = 3.0
# разрез внутри прямого куска: при склейке вершина убирается
STRAIGHT_CUT_DEG = 1e-6
MIN_PIECE_LEN_M = 1e-6
ZONE_HIT_M = 1e-3
KINK_POLYGON_TYPES = ("road", "tram_tracks")
TO_WGS = Transformer.from_crs("EPSG:32637", "EPSG:4326", always_xy=True)

XY = tuple[float, float]


@dataclass(frozen=True)
class TieIn:
    existing_id: str
    existing_type: str
    point: XY


@dataclass
class Solution:
    """Рёбра — полилинии в метрах EPSG:32637; общие узлы совпадают до 0,05 м. dn[i] — диаметр ребра i;
    если диаметры заданы не для всех рёбер, модель подбирает их сама (минимальный по расходу и вставки на
    ступень выше у предела длины, как в сервисе)."""
    edges: list[list[XY]]
    tie_ins: list[TieIn]
    flows: dict[int, float] = field(default_factory=dict)
    dn: dict[int, int] = field(default_factory=dict)
    elapsed: float = 0.0
    meta: dict[str, Any] = field(default_factory=dict)


@dataclass
class CostBreakdown:
    construction: float = 0.0
    chamber_construction: float = 0.0
    tie_in: float = 0.0
    reconstruction: float = 0.0
    chamber_reconstruction: float = 0.0
    penalty: float = 0.0
    new_length: float = 0.0
    recon_length: float = 0.0
    unconnected: list[str] = field(default_factory=list)
    violations: list[str] = field(default_factory=list)
    variant: Variant | None = None
    score_value: float = 0.0

    @property
    def total(self) -> float:
        return (self.construction + self.chamber_construction + self.tie_in + self.reconstruction
                + self.chamber_reconstruction + self.penalty)

    @property
    def length(self) -> float:
        return self.new_length + self.recon_length


@dataclass(frozen=True)
class QualityRule:
    """Правило разумной трассы из docs/routing-quality.md; цена в рублях за единицу метрики."""
    id: str
    title: str
    level: str
    source: str
    formula: str
    threshold: str
    application: str
    metric: str
    price_rub: float | None
    price_method: str

    @property
    def priced(self) -> bool:
        return self.application == "objective" and self.price_rub is not None


def unit_prices(rules_quality: list[QualityRule] | None) -> dict[str, float]:
    """Рубли за единицу метрики по правилам с ценой; ключи turn, kink, chamber есть всегда."""
    prices = dict.fromkeys(("turn", "kink", "chamber"), 0.0)
    for rule in rules_quality or []:
        if rule.priced:
            prices[rule.metric] = prices.get(rule.metric, 0.0) + rule.price_rub
    return prices


def score(cost: CostBreakdown, rules: dict[str, Any]) -> float:
    weights = rules["score"]
    return weights["w_cost"] * cost.total / weights["cost_base"] + weights["w_length"] * cost.length / weights["length_base_m"]


def rub_to_score(rub: float, rules: dict[str, Any]) -> float:
    weights = rules["score"]
    return weights["w_cost"] * rub / weights["cost_base"]


def penalties(tree: CostBreakdown, scene: Scene, rules_quality: list[QualityRule]) -> float:
    """Сумма штрафов правил с ценой в рублях: цена × значение метрики варианта (AC-2.3)."""
    # metrics импортирует model: импорт на уровне модуля дал бы цикл
    from heatopt.metrics import evaluate

    values = evaluate(scene, tree.variant, rules_quality)
    return sum(rule.price_rub * values[rule.id] for rule in rules_quality if rule.priced)


def objective(tree: CostBreakdown, scene: Scene, rules_quality: list[QualityRule] | None) -> float:
    """S раздела 10, в режиме quality=on плюс штрафы, переведённые через 0,7 / 25 000 000 (C-3)."""
    if not rules_quality:
        return tree.score_value
    return tree.score_value + rub_to_score(penalties(tree, scene, rules_quality), scene.rules)


class NodeIndex:
    """Точки с отождествлением ближе допуска; сетка ячеек размером с допуск."""

    def __init__(self, tol: float = NODE_TOL_M):
        self.tol = tol
        self.points: list[XY] = []
        self.cells: dict[tuple[int, int], list[int]] = defaultdict(list)

    def add(self, xy: XY) -> int:
        x, y = xy
        cx, cy = math.floor(x / self.tol), math.floor(y / self.tol)
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for i in self.cells.get((cx + dx, cy + dy), ()):
                    if math.dist(self.points[i], xy) <= self.tol:
                        return i
        self.points.append((x, y))
        self.cells[(cx, cy)].append(len(self.points) - 1)
        return len(self.points) - 1


@dataclass
class Piece:
    edge: int
    start: float
    end: float
    dn: int | None
    a: int = -1
    b: int = -1
    geom: LineString | None = None
    k: float | None = None


def clean(coords: list[XY]) -> list[XY]:
    result = []
    for xy in coords:
        xy = (float(xy[0]), float(xy[1]))
        if not result or math.dist(result[-1], xy) > 1e-9:
            result.append(xy)
    return result


def cost(tree: Solution, scene: Scene, rules: dict[str, Any] | None = None, variant_id: str = "1") -> CostBreakdown:
    """Стоимость решения по разделам 6–10; нарушения правил, которые видны без геометрии препятствий, копятся
    в violations (полная проверка — validate)."""
    rules = rules or scene.rules
    return Assembly(tree, scene, rules, variant_id).run()


class Assembly:
    def __init__(self, tree: Solution, scene: Scene, rules: dict[str, Any], variant_id: str):
        self.tree = tree
        self.scene = scene
        self.rules = rules
        self.vid = variant_id
        self.result = CostBreakdown()
        self.index = NodeIndex()
        self.cp_node: dict[int, str] = {}
        for terminal in scene.terminals:
            self.cp_node[self.index.add((terminal.point.x, terminal.point.y))] = terminal.cp_id
        self.lines: list[LineString | None] = []
        self.tie_nodes: dict[int, list[TieIn]] = defaultdict(list)

    def violation(self, message: str) -> None:
        self.result.violations.append(message)

    def run(self) -> CostBreakdown:
        for tie in self.tree.tie_ins:
            self.tie_nodes[self.index.add(tie.point)].append(tie)
        for coords in self.tree.edges:
            coords = clean(coords)
            self.lines.append(LineString(coords) if len(coords) >= 2 else None)
        oriented = self.orient()
        flows = self.flows(oriented)
        pieces = self.diameters(oriented, flows)
        pieces = self.cut_special(pieces)
        segments = self.merge(pieces)
        self.build_variant(segments, flows)
        return self.result


    def orient(self) -> dict[int, tuple[int, int]]:
        """Рёбра от врезок к листьям обходом в ширину; ребро вне связной части с врезкой — нарушение."""
        ends: dict[int, tuple[int, int]] = {}
        adjacent: dict[int, list[int]] = defaultdict(list)
        for i, line in enumerate(self.lines):
            if line is None:
                self.violation(f"ребро {i} короче допуска")
                continue
            a, b = self.index.add(line.coords[0]), self.index.add(line.coords[-1])
            if a == b:
                self.violation(f"ребро {i} начинается и заканчивается в одном узле")
                continue
            ends[i] = (a, b)
            adjacent[a].append(i)
            adjacent[b].append(i)
        oriented: dict[int, tuple[int, int]] = {}
        seen: set[int] = set()
        for root in sorted(self.tie_nodes):
            if root in seen:
                continue
            seen.add(root)
            queue = [root]
            while queue:
                node = queue.pop(0)
                for i in adjacent[node]:
                    if i in oriented:
                        continue
                    a, b = ends[i]
                    child = b if a == node else a
                    if child in seen:
                        self.violation(f"ребро {i} замыкает цикл или соединяет деревья")
                        oriented[i] = (node, child)
                        continue
                    if child == b:
                        oriented[i] = (a, b)
                    else:
                        oriented[i] = (b, a)
                        self.lines[i] = LineString(self.lines[i].coords[::-1])
                    seen.add(child)
                    if child in self.cp_node and len(adjacent[child]) > 1:
                        self.violation(f"точка подключения {self.cp_node[child]} не лист дерева")
                    if child in self.tie_nodes:
                        self.violation("путь проходит через узел другой врезки")
                    queue.append(child)
        for i in ends:
            if i not in oriented:
                self.violation(f"ребро {i} не связано ни с одной врезкой")
        return oriented

    def flows(self, oriented: dict[int, tuple[int, int]]) -> dict[int, float]:
        children: dict[int, list[int]] = defaultdict(list)
        for i, (a, _) in oriented.items():
            children[a].append(i)
        flow_of_cp = {t.cp_id: t.flow for t in self.scene.terminals}
        memo: dict[int, float] = {}

        def below(i: int, depth: int = 0) -> float:
            if i in memo:
                return memo[i]
            if depth > len(oriented):
                return 0.0
            node = oriented[i][1]
            total = flow_of_cp.get(self.cp_node.get(node, ""), 0.0)
            total += sum(below(child, depth + 1) for child in children[node] if child != i)
            memo[i] = total
            return total

        for i in oriented:
            below(i)
        self.children = children
        return memo


    def diameters(self, oriented: dict[int, tuple[int, int]], flows: dict[int, float]) -> list[Piece]:
        edges = sorted(oriented)
        minimal = {}
        for i in edges:
            if flows[i] <= 0:
                self.violation(f"ребро {i} не питает ни одного ОКС")
            minimal[i] = diameter_for(self.rules, flows[i]) if flows[i] > 0 else self.rules["diameters"][0]["dn"]
            if minimal[i] is None:
                self.violation(f"расход ребра {i} больше пропускной способности таблицы")
                minimal[i] = self.rules["diameters"][-1]["dn"]
        if self.tree.dn and all(i in self.tree.dn for i in edges):
            return [Piece(i, 0.0, self.lines[i].length, self.tree.dn[i]) for i in edges]
        return self.plan_inserts(oriented, minimal)

    def plan_inserts(self, oriented: dict[int, tuple[int, int]], minimal: dict[int, int]) -> list[Piece]:
        """Как DiameterPlanner сервиса: цепочка минимального диаметра копится от листьев, при превышении предела
        вставляется кусок на ступень выше длиной INSERT_M, не касающийся узлов."""
        parent_edge = {b: i for i, (_, b) in oriented.items()}
        order = []
        roots = [n for n in self.tie_nodes]
        stack = [(r, False) for r in roots]
        visited = set()
        while stack:
            node, done = stack.pop()
            if done:
                order.append(node)
                continue
            if node in visited:
                continue
            visited.add(node)
            stack.append((node, True))
            for i in self.children.get(node, []):
                if i in oriented and oriented[i][0] == node:
                    stack.append((oriented[i][1], False))
        cuts: dict[int, list[float]] = defaultdict(list)
        top: dict[int, float] = {}
        room: dict[int, float] = {}
        for node in order:
            kids = [i for i in self.children.get(node, []) if i in oriented and oriented[i][0] == node]
            by_dn: dict[int, list[int]] = defaultdict(list)
            for i in kids:
                by_dn[minimal[i]].append(i)
            open_len: dict[int, float] = {}
            up = parent_edge.get(node)
            for dn, group in by_dn.items():
                limit = diameter_row(self.rules, dn)["max_length_m"] - CUT_MARGIN_M
                if up is not None and minimal[up] == dn and self.lines[up].length < INSERT_M + 2 * INSERT_GAP_M:
                    # короткое ребро вверх не вмещает вставку и целиком войдёт в цепочку
                    limit -= self.lines[up].length
                group.sort(key=lambda i: -top[i])
                total = sum(top[i] for i in group)
                gap = TIE_CLEAR_M if node in self.tie_nodes else INSERT_GAP_M
                for i in group:
                    if total <= limit:
                        break
                    # вставка у верхнего узла ребра: ниже неё цепочка ребра уже не длиннее предела
                    if room[i] < gap + INSERT_GAP_M + INSERT_M or next_diameter(self.rules, dn) is None:
                        continue
                    total -= top[i]
                    cuts[i].append(gap)
                    top[i] = gap
                    total += top[i]
                open_len[dn] = total
            edge = parent_edge.get(node)
            if edge is None:
                continue
            dn = minimal[edge]
            limit = diameter_row(self.rules, dn)["max_length_m"] - CUT_MARGIN_M
            length = self.lines[edge].length
            acc = open_len.get(dn, 0.0)
            position = length
            while acc + position > limit and next_diameter(self.rules, dn) is not None:
                at = position - (limit - acc) - INSERT_M
                at = min(at, position - INSERT_M - (INSERT_GAP_M if acc == 0 else 0))
                if at < (TIE_CLEAR_M if oriented[edge][0] in self.tie_nodes else INSERT_GAP_M):
                    break
                cuts[edge].append(at)
                position = at
                acc = 0.0
            top[edge] = acc + position
            room[edge] = position
        result = []
        for i in sorted(oriented):
            length = self.lines[i].length
            bounds = sorted(cuts[i])
            start = 0.0
            for at in bounds:
                result.append(Piece(i, start, at, minimal[i]))
                result.append(Piece(i, at, at + INSERT_M, next_diameter(self.rules, minimal[i])))
                start = at + INSERT_M
            result.append(Piece(i, start, length, minimal[i]))
        return [p for p in result if p.end - p.start > MIN_PIECE_LEN_M]


    def piece_features(self, pieces: list[Piece]) -> Variant:
        variant = Variant(self.vid)
        node_ids: dict[int, str] = {}
        for node, ties in self.tie_nodes.items():
            for n, tie in enumerate(ties):
                feature = Feature(f"t{node}_{n}", "tie_in", {"existing_object_id": tie.existing_id}, Point(self.index.points[node]))
                variant.tie_ins.append(feature)
                node_ids.setdefault(node, feature.id)
        for piece in pieces:
            line = self.lines[piece.edge]
            piece.geom = substring(line, piece.start, piece.end)
            piece.a = self.index.add(line.interpolate(piece.start).coords[0]) if piece.start > 0 else self.index.add(line.coords[0])
            piece.b = self.index.add(line.interpolate(piece.end).coords[0]) if piece.end < line.length else self.index.add(line.coords[-1])
            for node in (piece.a, piece.b):
                if node not in node_ids:
                    if node in self.cp_node:
                        node_ids[node] = self.cp_node[node]
                    else:
                        node_ids[node] = f"n{node}"
                        variant.nodes.append(Feature(node_ids[node], "technical_node", {}, Point(self.index.points[node])))
            variant.segments.append(Feature(f"p{len(variant.segments)}", "heat_network", {
                "start_node_id": node_ids[piece.a], "end_node_id": node_ids[piece.b], "diameter": piece.dn,
            }, piece.geom))
        return variant

    def cut_special(self, pieces: list[Piece]) -> list[Piece]:
        if not pieces:
            return pieces
        variant = self.piece_features(pieces)
        net = build_net(self.scene.inp, variant)
        zones = special_zones(self.scene.inp, variant, net)
        if zones:
            tree = STRtree([p.geom for p in pieces])
            cuts: dict[int, list[float]] = defaultdict(list)
            for zone in zones:
                for part in shapely.get_parts(zone.geom):
                    for xy in (part.coords[0], part.coords[-1]):
                        point = Point(xy)
                        for idx in tree.query(point, predicate="dwithin", distance=1e-3):
                            piece = pieces[idx]
                            at = piece.geom.project(point)
                            if CUT_DEDUP_M < at < piece.geom.length - CUT_DEDUP_M:
                                cuts[idx].append(piece.start + at)
            if cuts:
                split = []
                for idx, piece in enumerate(pieces):
                    bounds = [piece.start]
                    for at in sorted(cuts.get(idx, [])):
                        if at - bounds[-1] > CUT_DEDUP_M and piece.end - at > CUT_DEDUP_M:
                            bounds.append(at)
                    bounds.append(piece.end)
                    split += [Piece(piece.edge, s, e, piece.dn) for s, e in zip(bounds, bounds[1:])]
                pieces = split
                variant = self.piece_features(pieces)
        # куски уже разрезаны по границам зон, поэтому принадлежность решает середина; segment_k валидатора
        # не видит куски короче 0,1 м между границами близких пересечений
        for piece in pieces:
            middle = piece.geom.interpolate(0.5, normalized=True)
            ks = [zone.obstacle.params["k_special"] for zone in zones if zone.geom.distance(middle) <= ZONE_HIT_M]
            piece.k = max(ks) if ks else None
        return pieces


    def merge(self, pieces: list[Piece]) -> list[Piece]:
        """Склеивает куски через узлы с одним входом и одним выходом без смены диаметра и прокладки."""
        out_of: dict[int, list[Piece]] = defaultdict(list)
        in_of: dict[int, list[Piece]] = defaultdict(list)
        for piece in pieces:
            out_of[piece.a].append(piece)
            in_of[piece.b].append(piece)
        merged = []
        for piece in pieces:
            if len(in_of[piece.a]) == 1 and len(out_of[piece.a]) == 1 and self.plain(piece.a) and same_kind(in_of[piece.a][0], piece):
                continue
            chain = [piece]
            while True:
                node = chain[-1].b
                if len(out_of[node]) == 1 and len(in_of[node]) == 1 and self.plain(node) and same_kind(chain[-1], out_of[node][0]):
                    chain.append(out_of[node][0])
                else:
                    break
            coords = list(chain[0].geom.coords)
            for nxt in chain[1:]:
                angle = deflection_deg(coords[-2], coords[-1], nxt.geom.coords[1])
                if angle is not None and angle < STRAIGHT_CUT_DEG:
                    coords.pop()
                coords += list(nxt.geom.coords)[1:]
            ks = [p.k for p in chain if p.k is not None]
            merged.append(Piece(chain[0].edge, 0.0, 0.0, chain[0].dn, chain[0].a, chain[-1].b, LineString(clean(coords)), max(ks) if ks else None))
        return merged

    def plain(self, node: int) -> bool:
        return node not in self.tie_nodes and node not in self.cp_node

    def build_variant(self, segments: list[Piece], flows: dict[int, float]) -> None:
        rules, scene, vid = self.rules, self.scene, self.vid
        variant = Variant(vid)
        out_of: dict[int, list[Piece]] = defaultdict(list)
        in_of: dict[int, list[Piece]] = defaultdict(list)
        for piece in segments:
            out_of[piece.a].append(piece)
            in_of[piece.b].append(piece)
        ids: dict[int, str] = {}
        chamber_nodes: list[int] = []
        tie_features: list[tuple[Feature, TieIn, int]] = []
        for node in sorted(self.tie_nodes):
            for tie in self.tie_nodes[node]:
                feature = Feature(f"v{vid}_tie_{len(tie_features) + 1}", "tie_in", {
                    "variant_id": vid, "existing_object_id": tie.existing_id, "existing_object_type": tie.existing_type,
                }, Point(self.index.points[node]))
                tie_features.append((feature, tie, node))
                variant.tie_ins.append(feature)
                ids.setdefault(node, feature.id)
                if tie.existing_type == "heat_network" and node not in chamber_nodes:
                    chamber_nodes.append(node)
        nodes = sorted({p.a for p in segments} | {p.b for p in segments})
        for node in nodes:
            if node in ids or node in self.cp_node:
                continue
            if len(out_of[node]) >= 2:
                chamber_nodes.append(node)
        for node in chamber_nodes:
            feature = Feature(f"v{vid}_ch_{len(variant.chambers) + 1}", "heat_chamber", {"variant_id": vid}, Point(self.index.points[node]))
            variant.chambers.append(feature)
            ids.setdefault(node, feature.id)
        for node in nodes:
            if node in ids:
                continue
            if node in self.cp_node:
                ids[node] = self.cp_node[node]
                continue
            feature = Feature(f"v{vid}_node_{len(variant.nodes) + 1}", "technical_node", {"variant_id": vid}, Point(self.index.points[node]))
            variant.nodes.append(feature)
            ids[node] = feature.id

        flow_of_cp = {t.cp_id: t.flow for t in scene.terminals}
        below_memo: dict[int, float] = {}

        def below(piece: Piece) -> float:
            key = id(piece)
            if key not in below_memo:
                below_memo[key] = flow_of_cp.get(self.cp_node.get(piece.b, ""), 0.0) + sum(below(c) for c in out_of[piece.b] if c is not piece)
            return below_memo[key]

        for piece in segments:
            length = round(piece.geom.length, 2)
            row = diameter_row(rules, piece.dn)
            k = piece.k if piece.k is not None else 1.0
            seg_cost = round(length * row["new_rub_m"] * k, 2)
            variant.segments.append(Feature(f"v{vid}_seg_{len(variant.segments) + 1}", "heat_network", {
                "variant_id": vid, "start_node_id": ids[piece.a], "end_node_id": ids[piece.b],
                "flow_tph": round(below(piece), 6), "diameter": piece.dn, "length": length,
                "laying_method": "special" if piece.k is not None else "base", "depth_start": None, "depth_end": None,
                "cost": seg_cost,
            }, piece.geom))
            self.result.construction += seg_cost
            self.result.new_length += length

        net = build_net(scene.inp, variant)
        loads = network_loads(scene.inp, net, variant)
        for feature, tie, node in tie_features:
            existing = scene.inp.by_id.get(tie.existing_id)
            if existing is None or existing.object_type != tie.existing_type:
                self.violation(f"врезка в неизвестный объект {tie.existing_id}")
                continue
            feature.props["existing_diameter"] = existing.props.get("diameter")
            group = net.group_of.get(feature.id)
            if group is None:
                continue
            if tie.existing_type == "heat_network":
                feature.props["required_diameter"] = pipe_required(rules, scene.inp, loads, existing, feature.geom)
            else:
                feature.props["required_diameter"] = chamber_required(rules, scene.inp, net, loads, existing, group)
                after = len(net.incident(group)) + len(scene.inp.chamber_links.get(existing.id, []))
                if after > min(rules["chamber_rule"]["max_segments"], rules["chamber_rule"]["max_branches"] + 1):
                    self.violation(f"к камере {existing.id} после подключения примыкает участков {after}")
            feature.props["cost"] = float(rules["tie_in_cost"])
            self.result.tie_in += rules["tie_in_cost"]

        for chamber in variant.chambers:
            group = net.group_of[chamber.id]
            dns = [s.props["diameter"] for s in net.incident(group)]
            for feature, tie, node in tie_features:
                existing = scene.inp.by_id.get(tie.existing_id)
                if net.group_of.get(feature.id) == group and existing is not None and existing.object_type == "heat_network":
                    dns.append(pipe_required(rules, scene.inp, loads, existing, feature.geom))
            dn = max(dns)
            branches = len(net.out_segs.get(group, []))
            existing_links = 2 if any(net.group_of.get(f.id) == group and t.existing_type == "heat_network" for f, t, _ in tie_features) else 1
            if branches + existing_links > rules["chamber_rule"]["max_segments"] or branches > rules["chamber_rule"]["max_branches"]:
                self.violation(f"у камеры {chamber.id} ответвлений {branches}")
            chamber.props.update({"diameter": dn, "cost": float(chamber_cost(rules, dn))})
            self.result.chamber_construction += chamber.props["cost"]

        seen_chambers = set()
        for feature, tie, node in tie_features:
            if tie.existing_type != "heat_chamber" or tie.existing_id in seen_chambers or feature.id not in net.group_of:
                continue
            seen_chambers.add(tie.existing_id)
            existing = scene.inp.by_id[tie.existing_id]
            required = chamber_required(rules, scene.inp, net, loads, existing, net.group_of[feature.id])
            if required > existing.props.get("diameter"):
                record_cost = float(chamber_cost(rules, required))
                variant.chamber_recons.append(Feature(f"v{vid}_chrecon_{len(variant.chamber_recons) + 1}", "heat_chamber_reconstruction", {
                    "variant_id": vid, "existing_object_id": existing.id, "existing_diameter": existing.props.get("diameter"),
                    "required_diameter": required, "cost": record_cost,
                }, existing.geom))
                self.result.chamber_reconstruction += record_cost

        for network_id, load in sorted(loads.items()):
            network = scene.inp.by_id[network_id]
            existing_flow = float(network.props.get("flow_tph", 0))
            for start, end, added in load_pieces(load, network.geom.length):
                if added <= 0:
                    continue
                required = diameter_for(rules, existing_flow + added)
                if required is None:
                    self.violation(f"итоговый расход участка {network_id} больше таблицы")
                    required = rules["diameters"][-1]["dn"]
                if required <= network.props.get("diameter"):
                    continue
                geom = piece_geom(scene.inp, network, start, end)
                length = round(geom.length, 2)
                recon_cost = round(length * diameter_row(rules, required)["recon_rub_m"], 2)
                variant.recons.append(Feature(f"v{vid}_recon_{len(variant.recons) + 1}", "heat_network_reconstruction", {
                    "variant_id": vid, "existing_object_id": network_id, "existing_flow_tph": existing_flow,
                    "added_flow_tph": round(added, 6), "calculated_flow_tph": round(existing_flow + added, 6),
                    "existing_diameter": network.props.get("diameter"), "required_diameter": required,
                    "length": length, "cost": recon_cost,
                }, geom))
                self.result.reconstruction += recon_cost
                self.result.recon_length += length

        connected = frozenset().union(*(net.root_oks(g) for g in net.roots)) if net.roots else frozenset()
        self.result.unconnected = sorted(oks_id for oks_id in scene.oks if oks_id not in connected)
        penalty = rules["penalty"]
        self.result.penalty = sum(penalty["fixed"] + penalty["per_tph"] * oks_flow(scene.inp, oks_id) for oks_id in self.result.unconnected)
        self.check_chains(variant, net)
        self.result.score_value = score(self.result, rules)
        variant.summaries.append(Feature(f"summary_{vid}", "variant_summary", {
            "variant_id": vid, "rank": int(vid) if vid.isdigit() else 1,
            "construction_cost": round(self.result.construction, 2),
            "chamber_construction_cost": round(self.result.chamber_construction, 2),
            "tie_in_cost": round(self.result.tie_in, 2), "reconstruction_cost": round(self.result.reconstruction, 2),
            "chamber_reconstruction_cost": round(self.result.chamber_reconstruction, 2),
            "unconnected_penalty": round(self.result.penalty, 2), "calculated_cost": round(self.result.total, 2),
            "new_network_length": round(self.result.new_length, 2), "reconstruction_length": round(self.result.recon_length, 2),
            "length": round(self.result.length, 2), "score": round(self.result.score_value, 3),
            "unconnected_oks_ids": self.result.unconnected,
        }, None))
        self.result.variant = variant

    def check_chains(self, variant: Variant, net: Net) -> None:
        segments = [s for s in variant.segments if s.id in net.start]
        index = {s.id: i for i, s in enumerate(segments)}
        pairs = []
        for group in range(len(net.points)):
            incident = [s for s in net.incident(group) if s.id in index]
            for i, a in enumerate(incident):
                for b in incident[i + 1:]:
                    if a.props["diameter"] == b.props["diameter"]:
                        pairs.append((index[a.id], index[b.id]))
        totals: dict[int, float] = defaultdict(float)
        labels = cluster(len(segments), pairs)
        for seg, label in zip(segments, labels):
            totals[label] += seg.geom.length
        for seg, label in zip(segments, labels):
            limit = diameter_row(self.rules, seg.props["diameter"])["max_length_m"]
            if totals[label] > limit:
                self.violation(f"цепочка Ду {seg.props['diameter']} длиной {totals[label]:.1f} м больше предела {limit} м")
                totals[label] = -math.inf


def same_kind(a: Piece, b: Piece) -> bool:
    """Соседние специальные куски разных зон сервис не делит: участок один, Kспец наибольший. Почти прямой
    стык (меньше 3°) остаётся узлом: внутри участка такая вершина нарушила бы правило geometry."""
    if a.dn != b.dn or (a.k is None) != (b.k is None):
        return False
    angle = deflection_deg(a.geom.coords[-2], a.geom.coords[-1], b.geom.coords[1])
    return angle is not None and (angle >= MIN_TURN_DEG or angle < STRAIGHT_CUT_DEG)


def shape_metrics(scene: Scene, variant: Variant) -> dict[str, int]:
    """Метрики C-9: повороты (отклонение ≥ 3° в вершине или узле пути), изломы (3–50° без запрета, дороги или
    трамвая в 5 м), камеры варианта и врезки."""
    polygons = [
        o.feature.geom for o in scene.inp.forbid + scene.inp.special
        if o.feature.geom.geom_type in ("Polygon", "MultiPolygon") and (o.params["rule"] == "forbid" or o.restriction_type in KINK_POLYGON_TYPES)
    ]
    tree = STRtree(polygons) if polygons else None
    net = build_net(scene.inp, variant)
    turns, kinks = 0, 0
    vertices: list[tuple[XY, XY, XY]] = []
    for seg in variant.segments:
        coords = list(seg.geom.coords)
        vertices += [(coords[i - 1], coords[i], coords[i + 1]) for i in range(1, len(coords) - 1)]
    for group in range(len(net.points)):
        for incoming in net.in_segs.get(group, []):
            for outgoing in net.out_segs.get(group, []):
                vertices.append((incoming.geom.coords[-2], outgoing.geom.coords[0], outgoing.geom.coords[1]))
    for a, b, c in vertices:
        angle = deflection_deg(a, b, c)
        if angle is None or angle < MIN_TURN_DEG:
            continue
        turns += 1
        if angle < KINK_MAX_DEG:
            near = tree is not None and len(tree.query(Point(b), predicate="dwithin", distance=KINK_FREE_M)) > 0
            kinks += not near
    return {"turns": turns, "kinks": kinks, "chambers": len(variant.chambers), "tie_ins": len(variant.tie_ins)}


def validate(scene: Scene, tree: CostBreakdown) -> dict[str, int]:
    """Нарушения правил валидатора heatcheck для собранного варианта (без schema, score и variants)."""
    out = Output(None, [], {tree.variant.id: tree.variant})
    rules_to_run = ("topology", "tie_in", "flow", "diameter", "length_limit", "forbid", "special",
                    "reconstruction", "chamber_recon", "cost", "coverage", "geometry")
    return {rule: len(check(rule, scene.inp, out, scene.rules).violations) for rule in rules_to_run}


def to_geojson(trees: list[CostBreakdown]) -> dict[str, Any]:
    """Варианты в EPSG:4326 в формате выхода сервиса (для heatcheck и калькулятора метрик)."""
    features = []
    for tree in trees:
        v = tree.variant
        for feature in v.segments + v.tie_ins + v.recons + v.chambers + v.chamber_recons + v.nodes + v.summaries:
            geometry = None
            if feature.geom is not None:
                geometry = mapping(shapely.transform(feature.geom, lambda xy: _to_wgs(xy)))
                geometry = {"type": geometry["type"], "coordinates": _round(geometry["coordinates"])}
            props = {"id": feature.id, "object_type": feature.object_type, **feature.props}
            features.append({"type": "Feature", "geometry": geometry, "properties": props})
    return {"type": "FeatureCollection", "features": features}


def _to_wgs(xy):
    lon, lat = TO_WGS.transform(xy[:, 0], xy[:, 1])
    return np.column_stack([lon, lat])


def _round(value):
    if isinstance(value, float):
        return round(value, 9)
    return [_round(v) for v in value]
