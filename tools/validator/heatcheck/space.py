from collections import defaultdict
from typing import Any

import shapely
from shapely import STRtree
from shapely.geometry import LineString

from heatcheck.model import (
    DIST_EPS_M,
    EXISTING_TOL_M,
    LENGTH_TOL_M,
    Input,
    Output,
    RuleResult,
    Violation,
    diameter_row,
    max_offset,
    required_offset,
)
from heatcheck.network import (
    acute_angle_deg,
    build_net,
    overlaps_zone,
    special_zones,
)


def check_forbid(inp: Input, out: Output, rules: dict[str, Any]) -> RuleResult:
    if not inp.forbid:
        return RuleResult([], 0)
    violations, checked = [], 0
    tree = STRtree([o.feature.geom for o in inp.forbid])
    reach = max_offset(rules)
    for variant in out.variants.values():
        for seg in variant.segments:
            if not isinstance(seg.geom, LineString):
                continue
            checked += 1
            dn = seg.props.get("diameter")
            if diameter_row(rules, dn) is None:
                violations.append(Violation(variant.id, seg.id, f"диаметр {dn} не из таблицы, отступ не определён"))
                continue
            for index in tree.query(seg.geom, predicate="dwithin", distance=reach):
                obstacle = inp.forbid[index]
                need = required_offset(rules, obstacle, dn)
                distance = seg.geom.distance(obstacle.feature.geom)
                if distance < need - DIST_EPS_M:
                    where = "пересекает" if distance == 0 else f"в {distance:.2f} м от"
                    message = f"{where} {obstacle.restriction_type} {obstacle.feature.id}, нужен отступ {need:.2f} м"
                    violations.append(Violation(variant.id, seg.id, message))
    return RuleResult(violations, checked)


def check_special(inp: Input, out: Output, rules: dict[str, Any]) -> RuleResult:
    violations, checked = [], 0
    tree = STRtree([o.feature.geom for o in inp.special])
    reach = max_offset(rules)
    for variant in out.variants.values():
        net = build_net(inp, variant)
        zones = special_zones(inp, variant, net)

        def add(object_id: str, message: str) -> None:
            violations.append(Violation(variant.id, object_id, message))

        segments = [s for s in variant.segments if s.id in net.start]
        special = [s for s in segments if s.props.get("laying_method") == "special"]
        base = [s for s in segments if s.props.get("laying_method") != "special"]
        checked += len(zones) + len(special)
        zone_area = shapely.union_all([z.area for z in zones])

        adjacent: dict[int, set[str]] = defaultdict(set)
        for seg in special:
            crossed = {z.obstacle.feature.id for z in zones if overlaps_zone(seg.geom, z.area)}
            if not crossed:
                add(seg.id, "специальный участок не проходит через объект со специальным проходом")
                continue
            outside = seg.geom.difference(zone_area).length
            if outside > LENGTH_TOL_M:
                add(seg.id, f"специальный участок выходит за зону перехода на {outside:.2f} м")
            adjacent[net.start[seg.id]] |= crossed
            adjacent[net.end[seg.id]] |= crossed

        for seg in base:
            crossed = sorted(z.obstacle.feature.id for z in zones if overlaps_zone(seg.geom, z.area))
            if crossed:
                add(seg.id, f"обычный участок лежит в зоне специального перехода через {', '.join(crossed)}")

        for zone in zones:
            min_angle = zone.obstacle.params.get("min_angle_deg")
            boundary = zone.obstacle.feature.geom.boundary
            if min_angle is None:
                continue
            ring_edges = [
                (ring.coords[i], ring.coords[i + 1])
                for ring in shapely.get_parts(boundary) for i in range(len(ring.coords) - 1)
            ]
            for seg in segments:
                if not seg.geom.intersects(boundary):
                    continue
                coords = seg.geom.coords
                for a, b in zip(coords, coords[1:]):
                    edge = LineString([a, b])
                    for c, d in ring_edges:
                        if edge.length == 0 or not edge.intersects(LineString([c, d])):
                            continue
                        angle = acute_angle_deg((b[0] - a[0], b[1] - a[1]), (d[0] - c[0], d[1] - c[1]))
                        if angle < min_angle:
                            add(seg.id, f"пересекает {zone.obstacle.feature.id} под углом {angle:.1f}°, нужно не меньше {min_angle}°")

        ties_at = defaultdict(list)
        for tie in variant.tie_ins:
            if tie.id in net.group_of:
                ties_at[net.group_of[tie.id]].append(tie.geom)
        for seg in base:
            dn = seg.props.get("diameter")
            if diameter_row(rules, dn) is None:
                add(seg.id, f"диаметр {dn} не из таблицы, отступ не определён")
                continue
            exempt = adjacent[net.start[seg.id]] | adjacent[net.end[seg.id]]
            for index in tree.query(seg.geom, predicate="dwithin", distance=reach):
                obstacle = inp.special[index]
                geom = obstacle.feature.geom
                if obstacle.feature.id in exempt:
                    continue
                touches_tie = any(geom.distance(t) <= EXISTING_TOL_M for t in ties_at.get(net.start[seg.id], []))
                if obstacle.restriction_type == "heat_network" and touches_tie:
                    continue
                need = required_offset(rules, obstacle, dn)
                distance = seg.geom.distance(geom)
                if distance < need - DIST_EPS_M:
                    add(seg.id, f"в {distance:.2f} м от {obstacle.restriction_type} {obstacle.feature.id}, нужен отступ {need:.2f} м")
    return RuleResult(violations, checked)
