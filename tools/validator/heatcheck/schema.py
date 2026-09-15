import math
from collections import Counter
from typing import Any

from heatcheck.model import (
    Input,
    Output,
    RuleResult,
    Violation,
    raw_features,
)

STR, INT, NUM, NULL, STR_LIST = "string", "integer", "number", "null", "string_list"
BASE = {"id": STR, "object_type": STR, "variant_id": STR}
SCHEMA: dict[str, tuple[str | None, dict[str, str]]] = {
    "heat_network": ("LineString", BASE | {
        "start_node_id": STR, "end_node_id": STR, "flow_tph": NUM, "diameter": INT, "length": NUM,
        "laying_method": STR, "depth_start": NULL, "depth_end": NULL, "cost": NUM,
    }),
    "tie_in": ("Point", BASE | {
        "existing_object_id": STR, "existing_object_type": STR, "existing_diameter": INT,
        "required_diameter": INT, "cost": NUM,
    }),
    "heat_network_reconstruction": ("LineString", BASE | {
        "existing_object_id": STR, "existing_flow_tph": NUM, "added_flow_tph": NUM, "calculated_flow_tph": NUM,
        "existing_diameter": INT, "required_diameter": INT, "length": NUM, "cost": NUM,
    }),
    "heat_chamber": ("Point", BASE | {"diameter": INT, "cost": NUM}),
    "heat_chamber_reconstruction": ("Point", BASE | {
        "existing_object_id": STR, "existing_diameter": INT, "required_diameter": INT, "cost": NUM,
    }),
    "technical_node": ("Point", BASE),
    "variant_summary": (None, BASE | {
        "rank": INT, "construction_cost": NUM, "chamber_construction_cost": NUM, "tie_in_cost": NUM,
        "reconstruction_cost": NUM, "chamber_reconstruction_cost": NUM, "unconnected_penalty": NUM,
        "calculated_cost": NUM, "new_network_length": NUM, "reconstruction_length": NUM, "length": NUM,
        "score": NUM, "unconnected_oks_ids": STR_LIST,
    }),
}
ENUMS = {"laying_method": {"base", "special"}, "existing_object_type": {"heat_network", "heat_chamber"}}
NODE_TYPES = {"tie_in", "heat_chamber", "technical_node"}
REFERENCES = {
    "tie_in": {"heat_network", "heat_chamber"},
    "heat_network_reconstruction": {"heat_network"},
    "heat_chamber_reconstruction": {"heat_chamber"},
}


def type_ok(kind: str, value: Any) -> bool:
    if kind == STR:
        return isinstance(value, str)
    if kind == INT:
        return isinstance(value, int) and not isinstance(value, bool)
    if kind == NUM:
        return isinstance(value, int | float) and not isinstance(value, bool) and math.isfinite(value)
    if kind == NULL:
        return value is None
    return isinstance(value, list) and all(isinstance(v, str) for v in value)


def position_ok(position: Any) -> bool:
    return (
        isinstance(position, list) and len(position) == 2 and all(type_ok(NUM, c) for c in position)
        and -180 <= position[0] <= 180 and -90 <= position[1] <= 90
    )


def geometry_problem(expected: str | None, raw: dict[str, Any]) -> str | None:
    if "geometry" not in raw:
        return "нет члена geometry"
    geometry = raw["geometry"]
    if expected is None:
        return None if geometry is None else "geometry должна быть null"
    if not isinstance(geometry, dict) or geometry.get("type") != expected:
        return f"тип геометрии должен быть {expected}"
    coords = geometry.get("coordinates")
    if expected == "Point":
        return None if position_ok(coords) else "координаты Point не пара чисел в EPSG:4326"
    if not isinstance(coords, list) or len(coords) < 2 or not all(position_ok(p) for p in coords):
        return "у LineString меньше двух позиций или позиции не пары чисел в EPSG:4326"
    return None


def check_schema(inp: Input, out: Output, rules: dict[str, Any]) -> RuleResult:
    violations = []
    raws = raw_features(out.raw)
    if not isinstance(out.raw, dict) or out.raw.get("type") != "FeatureCollection" or not isinstance(out.raw.get("features"), list):
        violations.append(Violation("-", "-", "файл не FeatureCollection с массивом features"))

    variant_nodes: dict[str, set[str]] = {}
    for feature in out.features:
        if feature.object_type in NODE_TYPES:
            variant_nodes.setdefault(str(feature.props.get("variant_id")), set()).add(feature.id)
    id_counts = Counter(f.id for f in out.features)
    summaries = Counter(str(f.props.get("variant_id")) for f in out.features if f.object_type == "variant_summary")

    for number, (raw, feature) in enumerate(zip(raws, out.features)):
        props = feature.props
        variant_id = str(props.get("variant_id", "-"))
        object_id = props["id"] if isinstance(props.get("id"), str) else f"#{number}"

        def add(message: str) -> None:
            violations.append(Violation(variant_id, object_id, message))

        if not isinstance(raw, dict) or raw.get("type") != "Feature" or not isinstance(raw.get("properties"), dict):
            add("объект не Feature со словарём properties")
            continue
        if feature.object_type not in SCHEMA:
            add(f"недопустимый object_type {props.get('object_type')!r}")
            continue
        geometry_type, fields = SCHEMA[feature.object_type]
        for name in sorted(set(props) - set(fields)):
            add(f"чужое поле {name}")
        for name, kind in fields.items():
            if name not in props:
                add(f"нет поля {name}")
            elif not type_ok(kind, props[name]):
                add(f"поле {name} должно быть {kind}, получено {props[name]!r}")
            elif name in ENUMS and props[name] not in ENUMS[name]:
                add(f"поле {name}: недопустимое значение {props[name]!r}")
        problem = geometry_problem(geometry_type, raw)
        if problem is None and geometry_type is not None and feature.geom is None:
            problem = "геометрия не разбирается"
        if problem:
            add(problem)

        if id_counts[feature.id] > 1:
            add("id повторяется в выходном файле")
        if feature.id in inp.by_id:
            add("id совпадает с id входного объекта")

        for key in ("start_node_id", "end_node_id"):
            if feature.object_type != "heat_network" or key not in props:
                continue
            node = inp.by_id.get(str(props[key]))
            is_connection_point = node is not None and node.object_type == "oks_connection_point"
            if not is_connection_point and props[key] not in variant_nodes.get(variant_id, set()):
                add(f"{key}={props[key]!r} не ссылается на узел этого варианта или точку подключения ОКС")
        if feature.object_type in REFERENCES and "existing_object_id" in props:
            existing = inp.by_id.get(str(props["existing_object_id"]))
            if existing is None or existing.object_type not in REFERENCES[feature.object_type]:
                add(f"existing_object_id={props['existing_object_id']!r} не ссылается на входной объект нужного типа")
        if feature.object_type == "variant_summary" and type_ok(STR_LIST, props.get("unconnected_oks_ids")):
            for oks_id in props["unconnected_oks_ids"]:
                if oks_id not in inp.oks:
                    add(f"unconnected_oks_ids содержит {oks_id!r}, это не входной oks_future")
    for variant_id in out.variants:
        if summaries[variant_id] != 1:
            violations.append(Violation(variant_id, "-", f"записей variant_summary {summaries[variant_id]}, нужна одна"))
    return RuleResult(violations, len(out.features))
