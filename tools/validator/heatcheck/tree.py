import itertools
import math
from collections import defaultdict
from collections.abc import Callable
from typing import Any

import shapely
from shapely import STRtree
from shapely.geometry import (
    LineString,
    Point,
)

from heatcheck.model import (
    COST_TOL_RUB,
    FLOW_TOL_TPH,
    LENGTH_TOL_M,
    MIN_SUBSEGMENT_M,
    MIN_TURN_DEG,
    NODE_TOL_M,
    TOUCH_TOL_M,
    VARIANT_TIE_DIST_M,
    Feature,
    Input,
    Output,
    RuleResult,
    Violation,
    diameter_for,
    diameter_row,
    next_diameter,
)
from heatcheck.network import (
    Net,
    build_net,
    chamber_required,
    cluster,
    deflection_deg,
    network_loads,
    oks_flow,
    path_from_root,
    pipe_required,
)

JUNCTION_CLIP_M = 3 * NODE_TOL_M


def is_number(value: Any) -> bool:
    return isinstance(value, int | float) and not isinstance(value, bool)


def chains(net: Net, segments: list[Feature], key: Callable[[Feature], Any]) -> list[list[Feature]]:
    index = {s.id: i for i, s in enumerate(segments)}
    pairs = []
    for group in range(len(net.points)):
        incident = [s for s in net.incident(group) if s.id in index]
        for a, b in itertools.combinations(incident, 2):
            if key(a) == key(b):
                pairs.append((index[a.id], index[b.id]))
    grouped: dict[int, list[Feature]] = defaultdict(list)
    for seg, label in zip(segments, cluster(len(segments), pairs)):
        grouped[label].append(seg)
    return list(grouped.values())


def expected_flow(inp: Input, net: Net, seg: Feature) -> float:
    return sum(oks_flow(inp, oks_id) for oks_id in net.oks_below[seg.id])


def check_topology(inp: Input, out: Output, rules: dict[str, Any]) -> RuleResult:
    violations, checked = [], 0
    chamber_rule = rules["chamber_rule"]
    for variant in out.variants.values():
        net = build_net(inp, variant)

        def add(object_id: str, message: str) -> None:
            violations.append(Violation(variant.id, object_id, message))

        segments = [s for s in variant.segments if s.id in net.start]
        checked += len(variant.segments)
        for seg in variant.segments:
            if seg.id not in net.start:
                add(seg.id, "концы участка не ссылаются на узлы варианта")
                continue
            coords = seg.geom.coords
            if net.start[seg.id] == net.end[seg.id]:
                add(seg.id, "участок начинается и заканчивается в одном узле")
            if Point(coords[0]).distance(net.node_points[str(seg.props["start_node_id"])]) > NODE_TOL_M:
                add(seg.id, "первая точка геометрии не совпадает с узлом start_node_id")
            if Point(coords[-1]).distance(net.node_points[str(seg.props["end_node_id"])]) > NODE_TOL_M:
                add(seg.id, "последняя точка геометрии не совпадает с узлом end_node_id")
            if not seg.geom.is_simple:
                add(seg.id, "участок пересекает сам себя")

        for node in variant.tie_ins + variant.chambers + variant.nodes:
            if node.id in net.group_of and not net.incident(net.group_of[node.id]):
                add(node.id, "к узлу не примыкает ни один новый участок")

        labels = cluster(len(net.points), ((net.start[s.id], net.end[s.id]) for s in segments))
        components: dict[int, list[Feature]] = defaultdict(list)
        for seg in segments:
            components[labels[net.start[seg.id]]].append(seg)
        for segs in components.values():
            groups = {net.start[s.id] for s in segs} | {net.end[s.id] for s in segs}
            roots = [g for g in groups if g in net.roots]
            if len(roots) != 1:
                add(segs[0].id, f"в связной части новой сети узлов врезки {len(roots)}, нужен один")
                continue
            root = roots[0]
            ties = [t for t in variant.tie_ins if net.group_of.get(t.id) == root]
            same_chamber = {(t.props.get("existing_object_type"), t.props.get("existing_object_id")) for t in ties}
            if len(ties) > 1 and (len(same_chamber) > 1 or next(iter(same_chamber))[0] != "heat_chamber"):
                add(ties[0].id, "несколько врезок в одном узле не в одну существующую камеру")
            if len(segs) != len(groups) - 1:
                add(segs[0].id, "новая сеть содержит цикл")
            for seg in net.in_segs.get(root, []):
                add(seg.id, "участок заканчивается в узле врезки: направление от врезки нарушено")
            for group in groups - {root}:
                incoming = net.in_segs.get(group, [])
                if len(incoming) != 1:
                    add(min(net.members[group]), f"в узел входит участков {len(incoming)}, нужен один")

        for group in range(len(net.points)):
            ties = [t for t in variant.tie_ins if net.group_of.get(t.id) == group]
            has_new_chamber = any(net.group_of.get(c.id) == group for c in variant.chambers)
            chamber_ties = [t for t in ties if t.props.get("existing_object_type") == "heat_chamber"]
            new_count = len(net.incident(group))
            if new_count >= 3 and not has_new_chamber and not chamber_ties:
                add(min(net.members[group]), f"ветвление из {new_count} участков не в камере")
            if not has_new_chamber and not chamber_ties:
                continue
            existing = 0
            for tie in ties:
                existing_id = str(tie.props.get("existing_object_id"))
                if tie.props.get("existing_object_type") == "heat_network":
                    existing = max(existing, 2)
                else:
                    existing = max(existing, len(inp.chamber_links.get(existing_id, [])))
            total = new_count + existing
            if total > chamber_rule["max_segments"] or total - 1 > chamber_rule["max_branches"]:
                add(min(net.members[group]), f"к камере примыкает участков {total} ({existing} существующих)")

        tree = STRtree([s.geom for s in segments])
        for i, j in zip(*tree.query([s.geom for s in segments], predicate="dwithin", distance=TOUCH_TOL_M)):
            if i >= j:
                continue
            a, b = segments[i], segments[j]
            shared = {net.start[a.id], net.end[a.id]} & {net.start[b.id], net.end[b.id]}
            clip = shapely.union_all([net.points[g].buffer(JUNCTION_CLIP_M) for g in shared])
            rest_a, rest_b = a.geom.difference(clip), b.geom.difference(clip)
            if not rest_a.is_empty and not rest_b.is_empty and rest_a.distance(rest_b) <= TOUCH_TOL_M:
                add(a.id, f"пересекает или касается участка {b.id} вне общего узла")
    return RuleResult(violations, checked)


def check_tie_in(inp: Input, out: Output, rules: dict[str, Any]) -> RuleResult:
    violations, checked = [], 0
    chamber_rule = rules["chamber_rule"]
    chambers = [c for c in inp.of_type("heat_chamber") if c.geom is not None]
    chamber_tree = STRtree([c.geom for c in chambers])
    for variant in out.variants.values():
        net = build_net(inp, variant)
        loads = network_loads(inp, net, variant)
        taken: dict[str, int] = defaultdict(int)
        for group in net.roots:
            for tie in variant.tie_ins:
                if net.group_of.get(tie.id) == group and tie.props.get("existing_object_type") == "heat_chamber":
                    taken[str(tie.props.get("existing_object_id"))] = len(net.incident(group))

        for tie in variant.tie_ins:
            checked += 1

            def add(message: str) -> None:
                violations.append(Violation(variant.id, tie.id, message))

            props = tie.props
            existing = inp.by_id.get(str(props.get("existing_object_id")))
            if existing is None or existing.geom is None or tie.id not in net.group_of:
                add("врезка не привязана к существующему объекту или не является узлом")
                continue
            group = net.group_of[tie.id]
            new_count = len(net.incident(group))
            new_chambers = [c for c in variant.chambers if net.group_of.get(c.id) == group]
            if existing.object_type != props.get("existing_object_type"):
                add(f"existing_object_type={props.get('existing_object_type')!r}, а объект {existing.id} это {existing.object_type}")
            if props.get("existing_diameter") != existing.props.get("diameter"):
                add(f"existing_diameter={props.get('existing_diameter')}, у объекта {existing.props.get('diameter')}")

            if existing.object_type == "heat_chamber":
                if tie.geom.distance(existing.geom) > NODE_TOL_M:
                    add("врезка в камеру стоит не в точке камеры")
                if new_chambers:
                    add("при врезке в существующую камеру в узле стоит новая камера")
                after = new_count + len(inp.chamber_links.get(existing.id, []))
                if after > chamber_rule["max_segments"]:
                    add(f"после подключения к камере примыкает участков {after}")
                required = chamber_required(rules, inp, net, loads, existing, group)
            elif existing.object_type == "heat_network":
                if tie.geom.distance(existing.geom) > NODE_TOL_M:
                    add("врезка в трубу стоит не на оси участка")
                if not new_chambers:
                    add("в точке врезки в трубу нет новой камеры")
                for index in chamber_tree.query(tie.geom, predicate="dwithin", distance=chamber_rule["max_dist_m"]):
                    chamber = chambers[index]
                    after = len(inp.chamber_links.get(chamber.id, [])) + taken[chamber.id] + new_count
                    if after <= chamber_rule["max_segments"]:
                        distance = tie.geom.distance(chamber.geom)
                        add(f"камера {chamber.id} в {distance:.2f} м, после подключения у неё было бы {after} участков: врезка должна быть в камеру")
                required = pipe_required(rules, inp, loads, existing, tie.geom)
            else:
                add(f"врезка в объект типа {existing.object_type}")
                continue
            if props.get("required_diameter") != required:
                add(f"required_diameter={props.get('required_diameter')}, по расчёту {required}")
    return RuleResult(violations, checked)


def check_flow(inp: Input, out: Output, rules: dict[str, Any]) -> RuleResult:
    violations, checked = [], 0
    for variant in out.variants.values():
        net = build_net(inp, variant)
        for seg in variant.segments:
            if seg.id not in net.start:
                continue
            checked += 1
            expected = expected_flow(inp, net, seg)
            declared = seg.props.get("flow_tph")
            if expected <= 0:
                violations.append(Violation(variant.id, seg.id, "участок не питает ни одного ОКС"))
            elif not is_number(declared) or abs(declared - expected) > FLOW_TOL_TPH:
                violations.append(Violation(variant.id, seg.id, f"flow_tph={declared}, сумма ОКС ниже по дереву {expected:.3f}"))
    return RuleResult(violations, checked)


def check_diameter(inp: Input, out: Output, rules: dict[str, Any]) -> RuleResult:
    violations, checked = [], 0
    for variant in out.variants.values():
        net = build_net(inp, variant)
        segments = [s for s in variant.segments if s.id in net.start]
        minimal = {s.id: diameter_for(rules, expected_flow(inp, net, s)) for s in segments}
        chain_of = {}
        for chain in chains(net, segments, lambda s: minimal[s.id]):
            length = sum(s.geom.length for s in chain)
            chain_of.update({s.id: length for s in chain})
        for seg in segments:
            checked += 1
            dn, low = seg.props.get("diameter"), minimal[seg.id]

            def add(message: str) -> None:
                violations.append(Violation(variant.id, seg.id, message))

            if low is None:
                add("расход больше пропускной способности наибольшего диаметра")
            elif dn == next_diameter(rules, low):
                limit = diameter_row(rules, low)["max_length_m"]
                if chain_of[seg.id] <= limit:
                    add(f"диаметр {dn} на ступень выше минимального {low}, а цепочка на {low} длиной {chain_of[seg.id]:.2f} м не длиннее {limit} м")
            elif dn != low:
                add(f"диаметр {dn}, минимальный по расходу {low}")
    return RuleResult(violations, checked)


def check_length_limit(inp: Input, out: Output, rules: dict[str, Any]) -> RuleResult:
    violations, checked = [], 0
    for variant in out.variants.values():
        net = build_net(inp, variant)
        segments = [s for s in variant.segments if s.id in net.start]
        checked += len(segments)
        for seg in segments:
            declared = seg.props.get("length")
            if not is_number(declared) or abs(declared - seg.geom.length) > LENGTH_TOL_M:
                violations.append(Violation(variant.id, seg.id, f"length={declared}, длина геометрии {seg.geom.length:.2f}"))
        for chain in chains(net, segments, lambda s: s.props.get("diameter")):
            dn = chain[0].props.get("diameter")
            row = diameter_row(rules, dn)
            length = sum(s.geom.length for s in chain)
            ids = sorted(s.id for s in chain)
            if row is None:
                violations.append(Violation(variant.id, ids[0], f"диаметр {dn} не из таблицы"))
            elif length > row["max_length_m"]:
                message = f"цепочка диаметра {dn} из {len(chain)} участков длиной {length:.2f} м больше предела {row['max_length_m']} м"
                violations.append(Violation(variant.id, ids[0], message))
    return RuleResult(violations, checked)


def check_coverage(inp: Input, out: Output, rules: dict[str, Any]) -> RuleResult:
    violations, checked = [], 0
    penalty = rules["penalty"]
    for variant in out.variants.values():
        net = build_net(inp, variant)
        connected = frozenset().union(*(net.root_oks(g) for g in net.roots))
        if len(variant.summaries) != 1:
            violations.append(Violation(variant.id, "-", "нет единственной сводки варианта"))
            continue
        summary = variant.summaries[0]
        listed = summary.props.get("unconnected_oks_ids")
        listed = listed if isinstance(listed, list) else []
        if len(listed) != len(set(listed)):
            violations.append(Violation(variant.id, summary.id, "unconnected_oks_ids содержит повторы"))
        for oks in inp.of_type("oks_future"):
            checked += 1
            if oks.id in connected and oks.id in listed:
                violations.append(Violation(variant.id, oks.id, "ОКС подключён и перечислен в unconnected_oks_ids"))
            if oks.id not in connected and oks.id not in listed:
                violations.append(Violation(variant.id, oks.id, "ОКС не подключён и не перечислен в unconnected_oks_ids"))
        expected = sum(penalty["fixed"] + penalty["per_tph"] * oks_flow(inp, oks_id) for oks_id in set(listed))
        declared = summary.props.get("unconnected_penalty")
        if not is_number(declared) or abs(declared - expected) > COST_TOL_RUB:
            violations.append(Violation(variant.id, summary.id, f"unconnected_penalty={declared}, по расчёту {expected:.2f}"))
    return RuleResult(violations, checked)


def check_variants(inp: Input, out: Output, rules: dict[str, Any]) -> RuleResult:
    violations = []
    variants = list(out.variants.values())
    if not 2 <= len(variants) <= 3:
        violations.append(Violation("-", "-", f"вариантов {len(variants)}, нужно от двух до трёх"))
    shapes = {}
    for variant in variants:
        net = build_net(inp, variant)
        ids = frozenset(str(t.props.get("existing_object_id")) for t in variant.tie_ins)
        points = [t.geom for t in variant.tie_ins if t.geom is not None]
        partition = frozenset(net.root_oks(g) for g in net.roots)
        shapes[variant.id] = (ids, points, partition)
    for a, b in itertools.combinations(variants, 2):
        ids_a, points_a, partition_a = shapes[a.id]
        ids_b, points_b, partition_b = shapes[b.id]
        far = any(
            all(p.distance(q) > VARIANT_TIE_DIST_M for q in others)
            for mine, others in ((points_a, points_b), (points_b, points_a))
            for p in mine
        )
        if ids_a == ids_b and not far and partition_a == partition_b:
            violations.append(Violation(b.id, "-", f"вариант не отличается от варианта {a.id}"))
    return RuleResult(violations, len(variants))


def check_geometry(inp: Input, out: Output, rules: dict[str, Any]) -> RuleResult:
    violations, checked = [], 0
    polygons = [
        o.feature.geom for o in inp.forbid + inp.special
        if o.feature.geom.geom_type in ("Polygon", "MultiPolygon") and (o.params["rule"] == "forbid" or "min_angle_deg" in o.params)
    ]
    polygon_tree = STRtree(polygons)
    for variant in out.variants.values():
        net = build_net(inp, variant)

        def add(object_id: str, message: str) -> None:
            violations.append(Violation(variant.id, object_id, message))

        special_groups = set()
        for seg in variant.segments:
            if seg.id in net.start and seg.props.get("laying_method") == "special":
                special_groups |= {net.start[seg.id], net.end[seg.id]}
        for seg in variant.segments:
            if seg.id not in net.start:
                continue
            checked += 1
            coords = list(seg.geom.coords)
            for i in range(1, len(coords) - 1):
                angle = deflection_deg(coords[i - 1], coords[i], coords[i + 1])
                if angle is not None and angle < MIN_TURN_DEG:
                    add(seg.id, f"вершина {i} с отклонением {angle:.2f}° меньше {MIN_TURN_DEG}°")
            last = len(coords) - 2
            for i in range(last + 1):
                length = math.dist(coords[i], coords[i + 1])
                near_special = (i == 0 and net.start[seg.id] in special_groups) or (i == last and net.end[seg.id] in special_groups)
                if length < MIN_SUBSEGMENT_M and not near_special:
                    add(seg.id, f"подотрезок {i} длиной {length:.2f} м короче {MIN_SUBSEGMENT_M} м")

        for group, members in enumerate(net.members):
            for cp_id in sorted(m for m in members if m in net.cp_oks):
                path = path_from_root(net, group)
                if not path:
                    continue
                checked += 1
                coords = list(path[0].geom.coords)
                for seg in path[1:]:
                    coords += list(seg.geom.coords)[1:]
                turns = 0
                for i in range(1, len(coords) - 1):
                    angle = deflection_deg(coords[i - 1], coords[i], coords[i + 1])
                    turns += angle is not None and angle >= MIN_TURN_DEG
                root = net.start[path[0].id]
                chord = LineString([net.points[root], net.node_points[cp_id]])
                k = len(polygon_tree.query(chord, predicate="intersects"))
                if turns > 3 * k + 4:
                    add(cp_id, f"на пути от врезки поворотов {turns}, допустимо {3 * k + 4} при k={k}")
    return RuleResult(violations, checked)
