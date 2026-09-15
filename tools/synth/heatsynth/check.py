"""Checks a synthetic input GeoJSON on its own, without generator code."""
import argparse
import json
import math
import re
import sys
from collections import (
    Counter,
    defaultdict,
)
from collections.abc import Iterator
from pathlib import Path
from typing import Any

from pyproj import Transformer
from shapely import STRtree
from shapely.geometry import (
    LineString,
    Point,
    shape,
)
from shapely.geometry.base import BaseGeometry
from shapely.ops import (
    nearest_points,
    transform,
)

RULES_PATH = Path("rules/rules.json")
CHUNK_CHARS = 1 << 20
SEPARATORS = re.compile(r"[\s,]*")
MAX_PRINTED_ERRORS = 30
END_TOLERANCE_M = 0.5
FLOW_TOLERANCE_TPH = 1e-6
LENGTH_TOLERANCE_M = 0.001
GAP_TOLERANCE_M = 0.01
MEDIUM_OKS = 20
MEDIUM_MIN_RESTRICTIONS = 200
MEDIUM_MIN_SEGMENTS = 300
MEDIUM_MIN_BLOCKED = 10
MEDIUM_MIN_CROSSED = 5
MIN_POLYGON_SIDE_M = 20.0
CHAMBER_RADIUS_M = 500.0
MIN_CHAMBERS_NEAR_OKS = 2
OVERLOAD_SHARE = 0.8
POLYGON_GAP_M = 12.0
NETWORK_GAP_M = 10.0
CP_POLYGON_GAP_M = 12.0
LINE_GAP_M = 10.0
POLYGONS = {"Polygon", "MultiPolygon"}
LINES = {"LineString", "MultiLineString"}
FORBID_TYPES = {"park", "social_area", "prohibited_site", "water"}
CROSSING_TYPES = {"road", "tram_tracks"}
LINE_TYPES = {"gas_pipeline", "power_cable"}
RESTRICTION_GEOMETRY = {kind: POLYGONS for kind in FORBID_TYPES | CROSSING_TYPES} | {kind: LINES for kind in LINE_TYPES}
SCHEMA = {
    "source": ({"Point"}, {"id", "object_type"}),
    "heat_network": ({"LineString"}, {"id", "object_type", "diameter", "flow_tph", "upstream_object_id"}),
    "heat_chamber": ({"Point"}, {"id", "object_type", "diameter", "upstream_object_id"}),
    "oks_future": (POLYGONS, {"id", "object_type", "flow_tph", "heat_load"}),
    "oks_connection_point": ({"Point"}, {"id", "object_type", "oks_id"}),
    "oks_existing": (POLYGONS, {"id", "object_type"}),
    "restriction": (set(), {"id", "object_type", "restriction_type"}),
}
FIELD_TYPES = {
    "id": str, "object_type": str, "oks_id": str, "restriction_type": str, "upstream_object_id": str,
    "diameter": int, "flow_tph": float, "heat_load": float,
}
UPSTREAM_TYPES = {"heat_network", "heat_chamber", "source"}

Stored = dict[str, list[tuple[dict[str, Any], BaseGeometry]]]


def fail(section: str, errors: list[str]) -> None:
    print(f"SYNTH {section} FAILED: {len(errors)} нарушений", file=sys.stderr)
    for error in errors[:MAX_PRINTED_ERRORS]:
        print(f"  {error}", file=sys.stderr)
    if len(errors) > MAX_PRINTED_ERRORS:
        print(f"  и ещё {len(errors) - MAX_PRINTED_ERRORS}", file=sys.stderr)
    sys.exit(1)


def read_features(path: Path) -> Iterator[Any]:
    """Streams features one by one so a 300 MB file never sits in memory as objects."""
    decoder = json.JSONDecoder()
    with path.open(encoding="utf-8") as file:
        buffer = file.read(CHUNK_CHARS)
        start = re.search(r'"features"\s*:\s*\[', buffer)
        if start is None:
            raise ValueError("нет массива features в начале файла")
        head = buffer[:start.start()]
        position = start.end()
        eof = False
        while True:
            position = SEPARATORS.match(buffer, position).end()
            if buffer.startswith("]", position):
                break
            try:
                feature, position = decoder.raw_decode(buffer, position)
            except json.JSONDecodeError:
                if eof:
                    raise
                chunk = file.read(CHUNK_CHARS)
                eof = chunk == ""
                buffer = buffer[position:] + chunk
                position = 0
                continue
            yield feature
        tail = buffer[position + 1:] + file.read()
    envelope = re.sub(r"\s", "", head + tail)
    if '"type":"FeatureCollection"' not in envelope:
        raise ValueError("корневой объект не FeatureCollection")


def value_ok(field: str, value: Any) -> bool:
    kind = FIELD_TYPES[field]
    if kind is str:
        return isinstance(value, str) and value != ""
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return False
    if kind is int and not isinstance(value, int):
        return False
    return math.isfinite(value) and value >= 0


def check_schema(path: Path, keep_restrictions: bool, dns: set[int]) -> Stored:
    errors = []
    ids = set()
    kinds = {}
    counts = Counter()
    restriction_types = set()
    references = []
    stored = defaultdict(list)
    for number, feature in enumerate(read_features(path), 1):
        if not isinstance(feature, dict) or feature.get("type") != "Feature" or not isinstance(feature.get("properties"), dict):
            errors.append(f"фича №{number}: не Feature с объектом properties")
            continue
        props = feature["properties"]
        object_type = props.get("object_type")
        if object_type not in SCHEMA:
            errors.append(f"фича №{number}: неизвестный object_type {object_type!r}")
            continue
        where = f"{object_type} {props.get('id')!r}"
        allowed, required = SCHEMA[object_type]
        if object_type == "restriction":
            restriction_type = props.get("restriction_type")
            if restriction_type not in RESTRICTION_GEOMETRY:
                errors.append(f"{where}: неизвестный restriction_type {restriction_type!r}")
                continue
            allowed = RESTRICTION_GEOMETRY[restriction_type]
            restriction_types.add(restriction_type)
        missing = sorted(required - props.keys())
        extra = sorted(props.keys() - required)
        bad = sorted(field for field in required & props.keys() if not value_ok(field, props[field]))
        if missing or extra or bad:
            errors.append(f"{where}: нет полей {missing}, чужие поля {extra}, неверный тип значения {bad}")
            continue
        if "diameter" in props and props["diameter"] not in dns:
            errors.append(f"{where}: diameter {props['diameter']} нет в таблице диаметров")
        raw = feature.get("geometry")
        if not isinstance(raw, dict) or raw.get("type") not in allowed:
            errors.append(f"{where}: геометрия должна быть {sorted(allowed)}")
            continue
        try:
            geom = shape(raw)
        except Exception as error:
            errors.append(f"{where}: геометрия не читается: {error}")
            continue
        minx, miny, maxx, maxy = geom.bounds if not geom.is_empty else (math.nan,) * 4
        if not geom.is_valid or not (-180 <= minx <= maxx <= 180 and -90 <= miny <= maxy <= 90):
            errors.append(f"{where}: пустая, невалидная геометрия или координаты вне EPSG:4326")
            continue
        feature_id = props["id"]
        if feature_id in ids:
            errors.append(f"{where}: id повторяется")
        ids.add(feature_id)
        counts[object_type] += 1
        if object_type != "restriction":
            kinds[feature_id] = object_type
        if "upstream_object_id" in props:
            references.append((where, feature_id, props["upstream_object_id"], UPSTREAM_TYPES))
        if "oks_id" in props:
            references.append((where, feature_id, props["oks_id"], {"oks_future"}))
        if object_type != "restriction" or keep_restrictions:
            stored[object_type].append((props, geom))

    for where, feature_id, target, wanted in references:
        if target == feature_id or kinds.get(target) not in wanted:
            errors.append(f"{where}: ссылка {target!r} не ведёт на объект {sorted(wanted)}")
    points_per_oks = Counter(props["oks_id"] for props, _ in stored["oks_connection_point"])
    for props, _ in stored["oks_future"]:
        if points_per_oks[props["id"]] != 1:
            errors.append(f"oks_future {props['id']!r}: точек подключения {points_per_oks[props['id']]}, нужна одна")
    if counts["source"] != 1:
        errors.append(f"source: {counts['source']} объектов, нужен один")
    absent = sorted(set(SCHEMA) - counts.keys())
    if absent:
        errors.append(f"нет объектов типов {absent}")
    absent = sorted(set(RESTRICTION_GEOMETRY) - restriction_types)
    if absent:
        errors.append(f"нет ограничений типов {absent}")
    if errors:
        fail("SCHEMA", errors)
    return stored


def check_network(stored: Stored, capacities: dict[int, float]) -> None:
    errors = []
    source_props, source_point = stored["source"][0]
    source_id = source_props["id"]
    segments = {props["id"]: (props, line) for props, line in stored["heat_network"]}
    chambers = {props["id"]: (props, point) for props, point in stored["heat_chamber"]}
    network = segments | chambers

    reaches = {source_id: True}
    for start in network:
        path = []
        seen = set()
        current = start
        while current not in reaches and current not in seen:
            path.append(current)
            seen.add(current)
            current = network[current][0]["upstream_object_id"]
        ok = reaches.get(current, False)
        for item in path:
            reaches[item] = ok
        if not ok:
            errors.append(f"{network[start][0]['object_type']} {start!r}: цепочка upstream_object_id не доходит до source")

    def ends(line: LineString) -> list[Point]:
        return [Point(line.coords[0]), Point(line.coords[-1])]

    for feature_id, (props, geom) in network.items():
        upstream = props["upstream_object_id"]
        if upstream == source_id:
            targets = [source_point]
        elif upstream in chambers:
            targets = [chambers[upstream][1]]
        else:
            targets = ends(segments[upstream][1])
        mine = ends(geom) if feature_id in segments else [geom]
        gap = min(a.distance(b) for a in mine for b in targets)
        if gap > END_TOLERANCE_M:
            errors.append(f"{props['object_type']} {feature_id!r}: до {upstream!r} {gap:.2f} м, конец должен совпадать до {END_TOLERANCE_M} м")

    def diameter_for(flow: float) -> int | None:
        return next((dn for dn, capacity in sorted(capacities.items()) if capacity >= flow), None)

    children_flow = defaultdict(float)
    for feature_id, (props, _) in segments.items():
        if props["diameter"] != diameter_for(props["flow_tph"]):
            errors.append(f"heat_network {feature_id!r}: diameter {props['diameter']} не минимальный по расходу {props['flow_tph']}")
        if not reaches[feature_id]:
            continue
        parent = props["upstream_object_id"]
        while parent in chambers:
            parent = chambers[parent][0]["upstream_object_id"]
        if parent == source_id:
            continue
        parent_props = segments[parent][0]
        children_flow[parent] += props["flow_tph"]
        if parent_props["diameter"] < props["diameter"] or parent_props["flow_tph"] < props["flow_tph"] - FLOW_TOLERANCE_TPH:
            errors.append(f"heat_network {feature_id!r}: расход или диаметр больше, чем у {parent!r} ближе к источнику")
    for parent, flow in children_flow.items():
        if segments[parent][0]["flow_tph"] < flow - FLOW_TOLERANCE_TPH:
            errors.append(f"heat_network {parent!r}: расход {segments[parent][0]['flow_tph']} меньше суммы нижних участков {flow:.3f}")

    owners = []
    endpoints = []
    for feature_id, (_, line) in segments.items():
        for point in ends(line):
            owners.append(feature_id)
            endpoints.append(point)
    tree = STRtree(endpoints)
    for feature_id, (props, point) in chambers.items():
        adjacent = {owners[i] for i in tree.query(point, predicate="dwithin", distance=END_TOLERANCE_M)}
        wanted = max((segments[s][0]["diameter"] for s in adjacent), default=None)
        if props["diameter"] != wanted:
            errors.append(f"heat_chamber {feature_id!r}: diameter {props['diameter']}, максимум примыкающих участков {wanted}")
    if errors:
        fail("NETWORK", errors)


def min_side(geom: BaseGeometry) -> float:
    rings = []
    for polygon in getattr(geom, "geoms", [geom]):
        rings += [polygon.exterior, *polygon.interiors]
    return min(Point(a).distance(Point(b)) for ring in rings for a, b in zip(ring.coords, ring.coords[1:]))


def too_close(left: list[tuple[str, BaseGeometry]], right: list[tuple[str, BaseGeometry]], distance: float, what: str) -> list[str]:
    tree = STRtree([geom for _, geom in right])
    found = tree.query([geom for _, geom in left], predicate="dwithin", distance=distance - GAP_TOLERANCE_M)
    return [
        f"{left[i][0]} и {right[j][0]}: ближе {distance} м ({what})"
        for i, j in zip(*found)
        if left is not right or i < j
    ]


def check_medium(stored: Stored, capacities: dict[int, float]) -> None:
    errors = []
    oks = {props["id"]: props for props, _ in stored["oks_future"]}
    segments = stored["heat_network"]
    restrictions = [(props["restriction_type"], props["id"], geom) for props, geom in stored["restriction"]]
    if len(oks) != MEDIUM_OKS:
        errors.append(f"oks_future: {len(oks)}, нужно {MEDIUM_OKS}")
    if len(restrictions) < MEDIUM_MIN_RESTRICTIONS:
        errors.append(f"restriction: {len(restrictions)}, нужно не меньше {MEDIUM_MIN_RESTRICTIONS}")
    if len(segments) < MEDIUM_MIN_SEGMENTS:
        errors.append(f"heat_network: {len(segments)}, нужно не меньше {MEDIUM_MIN_SEGMENTS}")
    for kind, feature_id, geom in restrictions:
        if kind not in LINE_TYPES and min_side(geom) < MIN_POLYGON_SIDE_M - LENGTH_TOLERANCE_M:
            errors.append(f"restriction {feature_id!r}: сторона {min_side(geom):.3f} м короче {MIN_POLYGON_SIDE_M} м")

    network_tree = STRtree([line for _, line in segments])
    chamber_tree = STRtree([point for _, point in stored["heat_chamber"]])
    existing = [(f"oks_existing {props['id']!r}", geom) for props, geom in stored["oks_existing"]]
    forbid = [(f"restriction {feature_id!r}", geom) for kind, feature_id, geom in restrictions if kind in FORBID_TYPES] + existing
    crossing = [(f"restriction {feature_id!r}", geom) for kind, feature_id, geom in restrictions if kind in CROSSING_TYPES]
    lines = [(f"restriction {feature_id!r}", geom) for kind, feature_id, geom in restrictions if kind in LINE_TYPES]
    forbid_tree = STRtree([geom for _, geom in forbid])
    crossing_tree = STRtree([geom for _, geom in crossing])
    blocked = crossed = 0
    for props, point in stored["oks_connection_point"]:
        where = f"oks_connection_point {props['id']!r}"
        nearest, _ = network_tree.query_nearest(point, return_distance=True, all_matches=True)
        target = nearest_points(segments[nearest[0]][1], point)[0]
        approach = LineString([point, target])
        blocked += forbid_tree.query(approach, predicate="intersects").size > 0
        crossed += crossing_tree.query(approach, predicate="intersects").size > 0
        chambers_near = chamber_tree.query(point, predicate="dwithin", distance=CHAMBER_RADIUS_M).size
        if chambers_near < MIN_CHAMBERS_NEAR_OKS:
            errors.append(f"{where}: камер в радиусе {CHAMBER_RADIUS_M} м {chambers_near}, нужно не меньше {MIN_CHAMBERS_NEAR_OKS}")
        oks_flow = oks[props["oks_id"]]["flow_tph"]
        for index in nearest:
            segment = segments[index][0]
            capacity = capacities[segment["diameter"]]
            if segment["flow_tph"] < OVERLOAD_SHARE * capacity - FLOW_TOLERANCE_TPH:
                errors.append(f"{where}: ближайший участок {segment['id']!r} загружен {segment['flow_tph']} из {capacity} т/ч, нужно не меньше {OVERLOAD_SHARE:.0%}")
            if segment["flow_tph"] + oks_flow <= capacity:
                errors.append(f"{where}: расход ОКС {oks_flow} не выводит участок {segment['id']!r} за пропускную способность {capacity} т/ч")
    if blocked < MEDIUM_MIN_BLOCKED:
        errors.append(f"запрещённый полигон на прямой к сети у {blocked} ОКС, нужно не меньше {MEDIUM_MIN_BLOCKED}")
    if crossed < MEDIUM_MIN_CROSSED:
        errors.append(f"дорога или трамвайные пути на прямой к сети у {crossed} ОКС, нужно не меньше {MEDIUM_MIN_CROSSED}")

    # the gaps below keep a corridor around every obstacle, so each OKS has a detour
    buildings = [(f"oks_future {props['id']!r}", geom) for props, geom in stored["oks_future"]]
    polygons = forbid + crossing + buildings
    network = [(f"heat_network {props['id']!r}", line) for props, line in segments]
    points = [(f"oks_connection_point {props['id']!r}", point) for props, point in stored["oks_connection_point"]]
    chambers = [(f"heat_chamber {props['id']!r}", point) for props, point in stored["heat_chamber"]]
    errors += too_close(polygons, polygons, POLYGON_GAP_M, "зазор между полигонами")
    errors += too_close(forbid, network, NETWORK_GAP_M, "запрещённый полигон у сети")
    errors += too_close(forbid + crossing, points, CP_POLYGON_GAP_M, "полигон у точки подключения")
    errors += too_close(lines, points + chambers, LINE_GAP_M, "линия у точки подключения или камеры")
    if errors:
        fail("MEDIUM", errors)


def main() -> None:
    parser = argparse.ArgumentParser(prog="heatsynth.check", description="Проверка синтетического входного GeoJSON")
    parser.add_argument("file", type=Path)
    parser.add_argument("--preset", choices=["medium"], help="дополнительно проверить пороги пресета")
    args = parser.parse_args()
    rules = json.loads(RULES_PATH.read_text(encoding="utf-8"))
    capacities = {row["dn"]: row["capacity_tph"] for row in rules["diameters"]}

    stored = check_schema(args.file, args.preset == "medium", set(capacities))
    print("SYNTH SCHEMA OK")
    to_utm = Transformer.from_crs("EPSG:4326", "EPSG:32637", always_xy=True)
    utm = {
        object_type: [(props, transform(to_utm.transform, geom)) for props, geom in items]
        for object_type, items in stored.items()
    }
    check_network(utm, capacities)
    print("SYNTH NETWORK OK")
    if args.preset == "medium":
        check_medium(utm, capacities)
        print("SYNTH MEDIUM OK")


if __name__ == "__main__":
    main()
