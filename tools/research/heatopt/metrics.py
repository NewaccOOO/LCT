"""Калькулятор метрик правил разумной трассы docs/routing-quality.md по варианту выхода (AC-2.2).

Метрики считаются по объектам варианта heatcheck в EPSG:32637 и входу сцены. turn, kink и chamber совпадают
с model.shape_metrics (C-9); --self-test это проверяет и на каждом выходе мутирует вариант так, чтобы
значение каждой метрики изменилось.
"""
import copy
import json
import math
import sys
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

import shapely
from heatcheck.model import (
    Feature,
    Obstacle,
    Variant,
    load_output,
    next_diameter,
)
from heatcheck.network import (
    Net,
    acute_angle_deg,
    build_net,
    deflection_deg,
    path_from_root,
)
from shapely import STRtree
from shapely.geometry import (
    LineString,
    Point,
)
from shapely.ops import substring

from heatopt import (
    model,
    rules_check,
)
from heatopt.scene import Scene

DOC_PATH = Path("docs/routing-quality.md")
ACUTE_DEG = 90.0
RIGHT_ANGLE_TOL_DEG = 1.0
CORRIDOR_M = 12.0
CORRIDOR_DEG = 15.0
FAN_DEG = 45.0
NEAR_TURN_M = 10.0
SHORT_ARM_M = 10.0
SHORT_INSERT_M = 10.0
# СП 124.13330.2012 п. 10.17б: (Ду до включительно, наибольшее расстояние между секционирующими задвижками, м)
SECTION_GAP_M = ((100, math.inf), (350, 1000.0), (500, 1500.0), (1400, 3000.0))
SELF_TEST_NAMES = ("small-1", "small-2", "small-3", "medium-1", "medium-2", "medium-3")
FIXTURE = (Path("tools/validator/tests/fixtures/input.geojson"), Path("tools/validator/tests/fixtures/output.geojson"))

XY = tuple[float, float]


@dataclass
class Turn:
    point: XY
    angle: float
    group: int | None


@dataclass
class Trace:
    scene: Scene
    variant: Variant
    net: Net
    segments: list[Feature]
    turns: list[Turn]
    roads: list[shapely.Geometry]
    road_angles: list[float]
    kink_tree: STRtree | None

    @classmethod
    def build(cls, scene: Scene, variant: Variant) -> "Trace":
        net = build_net(scene.inp, variant)
        segments = [s for s in variant.segments if s.id in net.start]
        turns = []
        for seg in segments:
            coords = list(seg.geom.coords)
            for i in range(1, len(coords) - 1):
                turns.append(Turn(coords[i], deflection_deg(coords[i - 1], coords[i], coords[i + 1]), None))
        for group in range(len(net.points)):
            for incoming in net.in_segs.get(group, []):
                for outgoing in net.out_segs.get(group, []):
                    angle = deflection_deg(incoming.geom.coords[-2], outgoing.geom.coords[0], outgoing.geom.coords[1])
                    turns.append(Turn(outgoing.geom.coords[0], angle, group))
        turns = [t for t in turns if t.angle is not None and t.angle >= model.MIN_TURN_DEG]
        kink_polygons = [
            o.feature.geom for o in scene.inp.forbid + scene.inp.special
            if is_polygon(o.feature.geom) and (o.params["rule"] == "forbid" or o.restriction_type in model.KINK_POLYGON_TYPES)
        ]
        roads = road_obstacles(scene)
        return cls(scene, variant, net, segments, turns, [o.feature.geom for o in roads], [o.params["min_angle_deg"] for o in roads],
                   STRtree(kink_polygons) if kink_polygons else None)


def is_polygon(geom: shapely.Geometry) -> bool:
    return geom.geom_type in ("Polygon", "MultiPolygon")


def road_obstacles(scene: Scene) -> list[Obstacle]:
    return [o for o in scene.inp.special if o.restriction_type in model.KINK_POLYGON_TYPES and is_polygon(o.feature.geom)]


def subsegments(seg: Feature) -> list[tuple[XY, XY]]:
    coords = list(seg.geom.coords)
    return list(zip(coords, coords[1:]))


def ring_edges(polygon: shapely.Geometry) -> list[tuple[XY, XY]]:
    return [
        (ring.coords[i], ring.coords[i + 1])
        for ring in shapely.get_parts(polygon.boundary) for i in range(len(ring.coords) - 1)
    ]


def edge_angle(polygon: shapely.Geometry, point: Point, a: XY, b: XY) -> float:
    """Острый угол между отрезком ab и ближайшей к точке стороной полигона."""
    c, d = min(ring_edges(polygon), key=lambda edge: LineString(edge).distance(point))
    return acute_angle_deg((b[0] - a[0], b[1] - a[1]), (d[0] - c[0], d[1] - c[1]))


def runs(trace: Trace, joins: Callable[[int, Feature, Feature], bool]) -> list[list[Feature]]:
    """Цепочки участков через узлы с одним входом и одним выходом, где joins разрешает склейку."""
    net = trace.net

    def passes(group: int) -> bool:
        ins, outs = net.in_segs.get(group, []), net.out_segs.get(group, [])
        return len(ins) == 1 and len(outs) == 1 and joins(group, ins[0], outs[0])

    result = []
    for seg in trace.segments:
        if passes(net.start[seg.id]):
            continue
        run, seen = [seg], {seg.id}
        while passes(net.end[run[-1].id]):
            nxt = net.out_segs[net.end[run[-1].id]][0]
            if nxt.id in seen:
                break
            run.append(nxt)
            seen.add(nxt.id)
        result.append(run)
    return result


def chain_coords(run: list[Feature]) -> list[XY]:
    coords = list(run[0].geom.coords)
    for seg in run[1:]:
        coords += list(seg.geom.coords)[1:]
    return coords


def armature_groups(trace: Trace) -> set[int]:
    """Узлы с запорной арматурой: врезки, камеры варианта и точки подключения ОКС (ввод в ИТП)."""
    net = trace.net
    groups = set(net.roots) | {net.group_of[c.id] for c in trace.variant.chambers if c.id in net.group_of}
    return groups | {net.group_of[cp] for cp in net.cp_oks}


def turn_count(trace: Trace) -> float:
    return len(trace.turns)


def kink_count(trace: Trace) -> float:
    kinks = 0
    for turn in trace.turns:
        if turn.angle < model.KINK_MAX_DEG:
            near = trace.kink_tree is not None and len(trace.kink_tree.query(Point(turn.point), predicate="dwithin", distance=model.KINK_FREE_M)) > 0
            kinks += not near
    return kinks


def acute_count(trace: Trace) -> float:
    return sum(turn.angle > ACUTE_DEG for turn in trace.turns)


def chamber_count(trace: Trace) -> float:
    return len(trace.variant.chambers)


def road_along_m(trace: Trace) -> float:
    total = 0.0
    tree = STRtree(trace.roads) if trace.roads else None
    for seg in trace.segments:
        for a, b in subsegments(seg):
            line = LineString([a, b])
            for index in tree.query(line, predicate="intersects") if tree else []:
                road = trace.roads[index]
                for piece in shapely.get_parts(line.intersection(road)):
                    if piece.length > 0 and edge_angle(road, piece.interpolate(0.5, normalized=True), a, b) < trace.road_angles[index]:
                        total += piece.length
    return total


def corridor_share(trace: Trace) -> float:
    length = sum(seg.geom.length for seg in trace.segments)
    if not trace.roads or length == 0:
        return 0.0
    roads = shapely.union_all(trace.roads)
    strip = roads.buffer(CORRIDOR_M).difference(roads)
    along = 0.0
    for seg in trace.segments:
        for a, b in subsegments(seg):
            for piece in shapely.get_parts(LineString([a, b]).intersection(strip)):
                if piece.length == 0:
                    continue
                middle = piece.interpolate(0.5, normalized=True)
                road = min(trace.roads, key=lambda r: r.distance(middle))
                if edge_angle(road, middle, a, b) <= CORRIDOR_DEG:
                    along += piece.length
    return along / length


def fan_count(trace: Trace) -> float:
    fans = 0
    for outs in trace.net.out_segs.values():
        directions = [(s.geom.coords[1][0] - s.geom.coords[0][0], s.geom.coords[1][1] - s.geom.coords[0][1]) for s in outs]
        fans += any(
            vector_angle(u, v) < FAN_DEG for i, u in enumerate(directions) for v in directions[i + 1:]
        )
    return fans


def vector_angle(u: XY, v: XY) -> float:
    cos = (u[0] * v[0] + u[1] * v[1]) / (math.hypot(*u) * math.hypot(*v))
    return math.degrees(math.acos(max(-1.0, min(cos, 1.0))))


def chamber_near_turn(trace: Trace) -> float:
    count = 0
    for chamber in trace.variant.chambers:
        own = trace.net.group_of.get(chamber.id)
        count += any(t.group != own and chamber.geom.distance(Point(t.point)) <= NEAR_TURN_M for t in trace.turns)
    return count


def short_arm_count(trace: Trace) -> float:
    count = 0
    for run in runs(trace, lambda group, prev, nxt: True):
        coords = chain_coords(run)
        bends = [
            coords[i] for i in range(1, len(coords) - 1)
            if (angle := deflection_deg(coords[i - 1], coords[i], coords[i + 1])) is not None and angle >= model.MIN_TURN_DEG
        ]
        count += sum(math.dist(p, q) < SHORT_ARM_M for p, q in zip(bends, bends[1:]))
    return count


def short_insert_count(trace: Trace) -> float:
    net = trace.net
    count = 0
    for run in runs(trace, lambda group, prev, nxt: prev.props.get("diameter") == nxt.props.get("diameter")):
        dn = run[0].props.get("diameter")
        ins = net.in_segs.get(net.start[run[0].id], [])
        outs = net.out_segs.get(net.end[run[-1].id], [])
        passing = len(ins) == 1 and len(outs) == 1 and len(net.out_segs.get(net.start[run[0].id], [])) == 1 and len(net.in_segs.get(net.end[run[-1].id], [])) == 1
        if passing and ins[0].props.get("diameter") != dn and outs[0].props.get("diameter") != dn:
            count += sum(s.geom.length for s in run) < SHORT_INSERT_M
    return count


def detour_ratio(trace: Trace) -> float:
    net, worst = trace.net, 0.0
    for cp in net.cp_oks:
        path = path_from_root(net, net.group_of[cp])
        if not path:
            continue
        chord = net.points[net.start[path[0].id]].distance(net.node_points[cp])
        if chord > 0:
            worst = max(worst, sum(s.geom.length for s in path) / chord)
    return worst


def lead_max_m(trace: Trace) -> float:
    net, armature, worst = trace.net, armature_groups(trace), 0.0
    for cp in net.cp_oks:
        path = path_from_root(net, net.group_of[cp])
        length = 0.0
        for seg in reversed(path or []):
            length += seg.geom.length
            if net.start[seg.id] in armature:
                break
        worst = max(worst, length)
    return worst


def section_gap_count(trace: Trace) -> float:
    armature = armature_groups(trace)
    count = 0
    for run in runs(trace, lambda group, prev, nxt: group not in armature):
        dn = max(s.props.get("diameter") for s in run)
        limit = next(gap for dn_max, gap in SECTION_GAP_M if dn <= dn_max)
        count += max(0, math.ceil(sum(s.geom.length for s in run) / limit) - 1)
    return count


def pipe_tie_count(trace: Trace) -> float:
    return sum(t.props.get("existing_object_type") == "heat_network" for t in trace.variant.tie_ins)


def road_node_count(trace: Trace) -> float:
    points = [Point(t.point) for t in trace.turns] + [c.geom for c in trace.variant.chambers]
    tree = STRtree(trace.roads) if trace.roads else None
    return sum(len(tree.query(p, predicate="intersects")) > 0 for p in points) if tree else 0


def oblique_crossing_count(trace: Trace) -> float:
    count = 0
    edges = [ring_edges(road) for road in trace.roads]
    for seg in trace.segments:
        for a, b in subsegments(seg):
            line = LineString([a, b])
            for road, ring in zip(trace.roads, edges):
                if not line.intersects(road.boundary):
                    continue
                for c, d in ring:
                    if line.intersects(LineString([c, d])):
                        angle = acute_angle_deg((b[0] - a[0], b[1] - a[1]), (d[0] - c[0], d[1] - c[1]))
                        count += angle < ACUTE_DEG - RIGHT_ANGLE_TOL_DEG
    return count


METRICS: dict[str, Callable[[Trace], float]] = {
    "turn": turn_count,
    "kink": kink_count,
    "acute": acute_count,
    "chamber": chamber_count,
    "road_along_m": road_along_m,
    "corridor_share": corridor_share,
    "fan": fan_count,
    "chamber_near_turn": chamber_near_turn,
    "short_arm": short_arm_count,
    "short_insert": short_insert_count,
    "detour_ratio": detour_ratio,
    "lead_max_m": lead_max_m,
    "section_gap": section_gap_count,
    "pipe_tie": pipe_tie_count,
    "oblique_crossing": oblique_crossing_count,
    "road_node": road_node_count,
}


def evaluate(scene: Scene, variant: Variant, rules_quality: list[model.QualityRule]) -> dict[str, float]:
    trace = Trace.build(scene, variant)
    values: dict[str, float] = {}
    for rule in rules_quality:
        if rule.metric not in values:
            values[rule.metric] = float(METRICS[rule.metric](trace))
    return {rule.id: values[rule.metric] for rule in rules_quality}


def compute(input_geojson: Path, output_geojson: Path, rules_quality: list[model.QualityRule]) -> dict[str, dict[str, float]]:
    scene = Scene.load(input_geojson)
    output = load_output(json.loads(Path(output_geojson).read_text(encoding="utf-8")))
    return {vid: evaluate(scene, variant, rules_quality) for vid, variant in output.variants.items()}


def longest(variant: Variant) -> Feature:
    return max(variant.segments, key=lambda s: s.geom.length)


def unit(a: XY, b: XY) -> tuple[XY, XY]:
    length = math.dist(a, b)
    t = ((b[0] - a[0]) / length, (b[1] - a[1]) / length)
    return t, (-t[1], t[0])


def shift(p: XY, *moves: tuple[XY, float]) -> XY:
    return (p[0] + sum(v[0] * k for v, k in moves), p[1] + sum(v[1] * k for v, k in moves))


def insert(seg: Feature, index: int, points: list[XY]) -> None:
    coords = list(seg.geom.coords)
    seg.geom = LineString(coords[:index + 1] + points + coords[index + 1:])


def longest_sub(seg: Feature) -> tuple[int, XY, XY]:
    return max(((i, a, b) for i, (a, b) in enumerate(subsegments(seg))), key=lambda item: math.dist(item[1], item[2]))


def bump(seg: Feature, ratio: float, extra_m: float = 0.0) -> XY:
    """Вершина в середине длинного подотрезка со сдвигом ratio × половина длины плюс extra_m; при extra_m = 0
    отклонение в вершине 2·atan(ratio)."""
    i, a, b = longest_sub(seg)
    t, n = unit(a, b)
    top = shift(a, (t, math.dist(a, b) / 2), (n, ratio * math.dist(a, b) / 2 + extra_m))
    insert(seg, i, [top])
    return top


def mutate_turn(scene: Scene, variant: Variant) -> Variant:
    bump(longest(variant), 0.4)
    return variant


def mutate_kink(scene: Scene, variant: Variant) -> Variant | None:
    trace = Trace.build(scene, variant)
    for seg in sorted(variant.segments, key=lambda s: -s.geom.length):
        i, a, b = longest_sub(seg)
        t, n = unit(a, b)
        for at in (0.5, 0.3, 0.7, 0.15, 0.85):
            top = shift(a, (t, at * math.dist(a, b)), (n, 0.1 * math.dist(a, b) / 2))
            if trace.kink_tree is None or not len(trace.kink_tree.query(Point(top), predicate="dwithin", distance=model.KINK_FREE_M)):
                insert(seg, i, [top])
                return variant
    return None


def mutate_acute(scene: Scene, variant: Variant) -> Variant:
    bump(longest(variant), 2.0)
    return variant


def road_detour(scene: Scene, variant: Variant, depth: float) -> Variant | None:
    """Проводит самый длинный участок вдоль длинной стороны ближайшей дороги на глубине depth от кромки
    (плюс — внутри полигона, минус — снаружи)."""
    seg = longest(variant)
    roads = [o.feature.geom for o in road_obstacles(scene)]
    if not roads:
        return None
    road = min(roads, key=lambda r: r.distance(seg.geom))
    c, d = max(ring_edges(road), key=lambda edge: math.dist(*edge))
    t, n = unit(c, d)
    probe = shift(c, (t, math.dist(c, d) / 2), (n, 0.05))
    inward = 1.0 if road.contains(Point(probe)) else -1.0
    points = [shift(c, (t, k * math.dist(c, d)), (n, inward * depth)) for k in (0.3, 0.7)]
    i, _, _ = longest_sub(seg)
    insert(seg, i, points)
    return variant


def mutate_road_along(scene: Scene, variant: Variant) -> Variant | None:
    return road_detour(scene, variant, 0.2)


def mutate_corridor(scene: Scene, variant: Variant) -> Variant | None:
    return road_detour(scene, variant, -5.0)


def mutate_chamber(scene: Scene, variant: Variant) -> Variant:
    seg = longest(variant)
    variant.chambers.append(Feature("mut_chamber", "heat_chamber", {"variant_id": variant.id}, seg.geom.interpolate(0.5, normalized=True)))
    return variant


def mutate_fan(scene: Scene, variant: Variant) -> Variant | None:
    net = build_net(scene.inp, variant)
    for group, outs in net.out_segs.items():
        start = next((m for m in sorted(net.members[group]) if m not in net.cp_oks), None)
        if len(outs) != 1 or start is None:
            continue
        a, b = outs[0].geom.coords[0], outs[0].geom.coords[1]
        t, n = unit(a, b)
        end = shift(a, (t, 20.0 * math.cos(math.radians(10))), (n, 20.0 * math.sin(math.radians(10))))
        variant.nodes.append(Feature("mut_fan_node", "technical_node", {"variant_id": variant.id}, Point(end)))
        variant.segments.append(Feature("mut_fan", "heat_network", {
            "variant_id": variant.id, "start_node_id": start, "end_node_id": "mut_fan_node", "diameter": outs[0].props.get("diameter"),
        }, LineString([a, end])))
        return variant
    return None


def mutate_chamber_near_turn(scene: Scene, variant: Variant) -> Variant:
    top = bump(longest(variant), 0.4)
    variant.chambers.append(Feature("mut_chamber", "heat_chamber", {"variant_id": variant.id}, Point(shift(top, ((1.0, 0.0), 5.0)))))
    return variant


def mutate_short_arm(scene: Scene, variant: Variant) -> Variant:
    seg = longest(variant)
    i, a, b = longest_sub(seg)
    t, n = unit(a, b)
    first = shift(a, (t, math.dist(a, b) / 2 - 3.0))
    insert(seg, i, [first, shift(first, (t, 3.0), (n, 4.0))])
    return variant


def mutate_short_insert(scene: Scene, variant: Variant) -> Variant:
    seg = longest(variant)
    length, dn = seg.geom.length, seg.props.get("diameter")
    cut_a, cut_b = length / 2 - SHORT_INSERT_M / 4, length / 2 + SHORT_INSERT_M / 4
    other = next_diameter(scene.rules, dn) or scene.rules["diameters"][-2]["dn"]
    end_id, geom = seg.props["end_node_id"], seg.geom
    for node_id, at in (("mut_n1", cut_a), ("mut_n2", cut_b)):
        variant.nodes.append(Feature(node_id, "technical_node", {"variant_id": variant.id}, geom.interpolate(at)))
    seg.geom, seg.props = substring(geom, 0, cut_a), {**seg.props, "end_node_id": "mut_n1"}
    variant.segments.append(Feature("mut_insert", "heat_network", {**seg.props, "start_node_id": "mut_n1", "end_node_id": "mut_n2", "diameter": other}, substring(geom, cut_a, cut_b)))
    variant.segments.append(Feature("mut_tail", "heat_network", {**seg.props, "start_node_id": "mut_n2", "end_node_id": end_id}, substring(geom, cut_b, length)))
    return variant


def mutate_far_leaf(scene: Scene, variant: Variant) -> Variant | None:
    """Далёкий крюк на последнем участке пути к первому ОКС: растут и обход, и подводящий участок."""
    net = build_net(scene.inp, variant)
    span = sum(s.geom.length for s in variant.segments)
    for cp in sorted(net.cp_oks):
        path = path_from_root(net, net.group_of[cp])
        if path:
            bump(next(s for s in variant.segments if s.id == path[-1].id), 0.0, 10 * span + 100)
            return variant
    return None


def mutate_section_gap(scene: Scene, variant: Variant) -> Variant:
    seg = longest(variant)
    seg.props = {**seg.props, "diameter": max(seg.props.get("diameter"), 150)}
    bump(seg, 0.0, 2000.0)
    return variant


def mutate_pipe_tie(scene: Scene, variant: Variant) -> Variant:
    tie = variant.tie_ins[0]
    kind = tie.props.get("existing_object_type")
    tie.props = {**tie.props, "existing_object_type": "heat_chamber" if kind == "heat_network" else "heat_network"}
    return variant


def mutate_oblique(scene: Scene, variant: Variant) -> Variant | None:
    seg = longest(variant)
    roads = [o.feature.geom for o in road_obstacles(scene)]
    if not roads:
        return None
    road = min(roads, key=lambda r: r.distance(seg.geom))
    c, d = max(ring_edges(road), key=lambda edge: math.dist(*edge))
    t, n = unit(c, d)
    middle = shift(c, (t, math.dist(c, d) / 2))
    inward = 1.0 if road.contains(Point(shift(middle, (n, 0.05)))) else -1.0
    i, _, _ = longest_sub(seg)
    insert(seg, i, [shift(middle, (t, -4.0), (n, -4.0 * inward)), shift(middle, (t, 0.2), (n, 0.2 * inward))])
    return variant


MUTATIONS: dict[str, Callable[[Scene, Variant], Variant | None]] = {
    "turn": mutate_turn,
    "kink": mutate_kink,
    "acute": mutate_acute,
    "chamber": mutate_chamber,
    "road_along_m": mutate_road_along,
    "corridor_share": mutate_corridor,
    "fan": mutate_fan,
    "chamber_near_turn": mutate_chamber_near_turn,
    "short_arm": mutate_short_arm,
    "short_insert": mutate_short_insert,
    "detour_ratio": mutate_far_leaf,
    "lead_max_m": mutate_far_leaf,
    "section_gap": mutate_section_gap,
    "pipe_tie": mutate_pipe_tie,
    "oblique_crossing": mutate_oblique,
    "road_node": mutate_road_along,
}
SHAPE_KEYS = {"turn": "turns", "kink": "kinks", "chamber": "chambers"}


def self_test_pairs() -> list[tuple[Path, Path]]:
    return [(Path(f"data/synth/{name}.geojson"), Path(f"data/out/{name}.geojson")) for name in SELF_TEST_NAMES] + [FIXTURE]


def self_test(rules_quality: list[model.QualityRule]) -> tuple[list[str], int]:
    """Ошибки и число правил, чья мутация изменила метрику на каждом выходе."""
    errors, failed = [], set()
    for input_path, output_path in self_test_pairs():
        scene = Scene.load(input_path)
        variant = load_output(json.loads(output_path.read_text(encoding="utf-8"))).variants["1"]
        base = evaluate(scene, variant, rules_quality)
        checks = [(variant, "исходный вариант")]
        for rule in rules_quality:
            mutated = MUTATIONS[rule.metric](scene, copy.deepcopy(variant))
            if mutated is None:
                errors.append(f"{output_path}: мутация для {rule.id} ({rule.metric}) неприменима")
                failed.add(rule.id)
                continue
            value = evaluate(scene, mutated, [rule])[rule.id]
            if value == base[rule.id]:
                errors.append(f"{output_path}: мутация не изменила {rule.id} ({rule.metric}) = {value}")
                failed.add(rule.id)
            checks.append((mutated, f"мутация {rule.id}"))
        for checked, label in checks:
            shape = model.shape_metrics(scene, checked)
            values = evaluate(scene, checked, [r for r in rules_quality if r.metric in SHAPE_KEYS])
            for rule in rules_quality:
                if rule.metric in SHAPE_KEYS and values[rule.id] != shape[SHAPE_KEYS[rule.metric]]:
                    errors.append(f"{output_path}, {label}: {rule.id} = {values[rule.id]}, shape_metrics = {shape[SHAPE_KEYS[rule.metric]]}")
    return errors, len({rule.id for rule in rules_quality} - failed)


def main() -> None:
    args = sys.argv[1:]
    rules_quality = rules_check.load(DOC_PATH)
    if args == ["--self-test"]:
        errors, mutations = self_test(rules_quality)
        if errors:
            print("METRICS FAILED")
            for error in errors:
                print(f"  {error}")
            sys.exit(1)
        print(f"METRICS OK outputs={len(self_test_pairs())} rules={len(rules_quality)} mutations={mutations}")
        return
    if len(args) != 2:
        print("использование: python -m heatopt.metrics <вход.geojson> <выход.geojson> | --self-test")
        sys.exit(2)
    print(json.dumps(compute(Path(args[0]), Path(args[1]), rules_quality), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
