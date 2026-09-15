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
BUILDING = "oks"
POLYGON_GAP_M = 12.0
NETWORK_GAP_M = 10.0
CP_POLYGON_GAP_M = 12.0
LINE_GAP_M = 10.0
POLYGONS = {"Polygon", "MultiPolygon"}
LINES = {"LineString", "MultiLineString"}
FORBID_TYPES = {"park", "social_area", "prohibited_site", "water", "metro"}
POINT_TYPES = {"power_line_support"}
CROSSING_TYPES = {"road", "tram_tracks", "railway"}
LINE_TYPES = {"gas_pipeline", "power_cable", "water_supply", "sewer"}
RESTRICTION_GEOMETRY = (
    {kind: POLYGONS for kind in FORBID_TYPES | CROSSING_TYPES}
    | {kind: LINES for kind in LINE_TYPES}
    | {kind: {"Point"} for kind in POINT_TYPES}
)
DEFAULT_TYPES = ("park", "social_area", "prohibited_site", "water", "road", "tram_tracks", "gas_pipeline", "power_cable")
# формат датасета организаторов: у сети только диаметр, здания — ограничения oks, расход ОКС на точке подключения
SCHEMA = {
    "source": ({"Point"}, {"id", "object_type"}),
    "heat_network": ({"LineString"}, {"id", "object_type", "diameter"}),
    "heat_chamber": ({"Point"}, {"id", "object_type"}),
    "oks_connection_point": ({"Point"}, {"id", "object_type", "flow_tph"}),
    "restriction": (set(), {"id", "object_type", "restriction_type"}),
}
FIELD_TYPES = {"object_type": str, "restriction_type": str, "diameter": int, "flow_tph": float}

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


def id_ok(value: Any, prefix: str) -> bool:
    if prefix:
        return isinstance(value, str) and value.startswith(prefix) and value != prefix
    return isinstance(value, int) and not isinstance(value, bool) and value > 0


def value_ok(field: str, value: Any) -> bool:
    kind = FIELD_TYPES[field]
    if kind is str:
        return isinstance(value, str) and value != ""
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return False
    if kind is int and not isinstance(value, int):
        return False
    return math.isfinite(value) and value >= 0


def check_schema(path: Path, keep_restrictions: bool, dns: set[int], types: list[str], prefix: str) -> Stored:
    errors = []
    ids = set()
    counts = Counter()
    restriction_types = set()
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
            if restriction_type not in RESTRICTION_GEOMETRY and restriction_type != BUILDING:
                errors.append(f"{where}: неизвестный restriction_type {restriction_type!r}")
                continue
            allowed = RESTRICTION_GEOMETRY.get(restriction_type, POLYGONS)
            if restriction_type != BUILDING:
                restriction_types.add(restriction_type)
        missing = sorted(required - props.keys())
        extra = sorted(props.keys() - required)
        bad = sorted(field for field in required & props.keys() if field != "id" and not value_ok(field, props[field]))
        if "id" in props and not id_ok(props["id"], prefix):
            bad.append("id" if not prefix else f"id без префикса {prefix!r}")
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
        key = "building" if props.get("restriction_type") == BUILDING else object_type
        if key != "restriction" or keep_restrictions:
            stored[key].append((props, geom))

    if counts["source"] != 1:
        errors.append(f"source: {counts['source']} объектов, нужен один")
    absent = sorted(set(SCHEMA) - counts.keys())
    if absent:
        errors.append(f"нет объектов типов {absent}")
    absent = sorted(set(types) - restriction_types)
    if absent:
        errors.append(f"нет ограничений типов {absent}")
    unexpected = sorted(restriction_types - set(types))
    if unexpected:
        errors.append(f"ограничения типов {unexpected} не заказаны, ожидались только {sorted(types)}")
    if errors:
        fail("SCHEMA", errors)
    return stored


def check_network(stored: Stored, capacities: dict[int, float]) -> None:
    """Направление сети выводится обходом от источника по стыкам концов, как это делают сервис и валидатор."""
    errors = []
    source_props, source_point = stored["source"][0]
    segments = stored["heat_network"]
    ends = [(i, k, Point(line.coords[0 if k == 0 else -1])) for i, (_, line) in enumerate(segments) for k in (0, 1)]
    tree = STRtree([point for _, _, point in ends])

    def near(at: BaseGeometry) -> list[tuple[int, int]]:
        return [ends[j][:2] for j in sorted(tree.query(at, predicate="dwithin", distance=END_TOLERANCE_M))]

    parent: dict[int, int | None] = {}
    queue = []
    for i, k in near(source_point):
        if i not in parent:
            parent[i] = None
            queue.append((i, k))
    for i, k in queue:
        for j, kk in near(Point(segments[i][1].coords[-1 if k == 0 else 0])):
            if j not in parent:
                parent[j] = i
                queue.append((j, kk))
    for i, (props, _) in enumerate(segments):
        if i not in parent:
            errors.append(f"heat_network {props['id']!r}: обход от source по стыкам концов до участка не доходит")
        elif parent[i] is not None and segments[parent[i]][0]["diameter"] < props["diameter"]:
            errors.append(f"heat_network {props['id']!r}: диаметр больше, чем у участка {segments[parent[i]][0]['id']!r} ближе к источнику")
    for props, point in stored["heat_chamber"]:
        if not near(point):
            errors.append(f"heat_chamber {props['id']!r}: не стоит на конце участка, диаметр камеры не вывести")

    buildings = STRtree([geom for _, geom in stored["building"]])
    inside = Counter()
    for props, point in stored["oks_connection_point"]:
        found = buildings.query(point, predicate="within")
        if found.size != 1:
            errors.append(f"oks_connection_point {props['id']!r}: лежит внутри {found.size} зданий oks, нужно одно")
        inside.update(int(j) for j in found)
    for j, count in inside.items():
        if count > 1:
            errors.append(f"здание oks {stored['building'][j][0]['id']!r}: точек подключения {count}, генератор ставит одну")
    if errors:
        fail("NETWORK", errors)


def min_side(geom: BaseGeometry) -> float:
    rings = []
    for polygon in getattr(geom, "geoms", [geom]):
        rings += [polygon.exterior, *polygon.interiors]
    return min(Point(a).distance(Point(b)) for ring in rings for a, b in zip(ring.coords, ring.coords[1:]))


def too_close(left: list[tuple[str, BaseGeometry]], right: list[tuple[str, BaseGeometry]], distance: float, what: str) -> list[str]:
    if not left or not right:
        return []
    tree = STRtree([geom for _, geom in right])
    found = tree.query([geom for _, geom in left], predicate="dwithin", distance=distance - GAP_TOLERANCE_M)
    return [
        f"{left[i][0]} и {right[j][0]}: ближе {distance} м ({what})"
        for i, j in zip(*found)
        if left is not right or i < j
    ]


def existing_flow(capacities: dict[int, float], share: float, dn: int) -> float:
    """Текущий расход участка, который выводят сервис и валидатор: cap(Ду-1) + share × (cap(Ду) − cap(Ду-1))."""
    previous = max((capacity for other, capacity in capacities.items() if other < dn), default=0.0)
    return previous + share * (capacities[dn] - previous)


def check_medium(stored: Stored, capacities: dict[int, float], share: float) -> None:
    errors = []
    segments = stored["heat_network"]
    restrictions = [(props["restriction_type"], props["id"], geom) for props, geom in stored["restriction"]]
    if len(stored["oks_connection_point"]) != MEDIUM_OKS:
        errors.append(f"oks_connection_point: {len(stored['oks_connection_point'])}, нужно {MEDIUM_OKS}")
    if len(restrictions) < MEDIUM_MIN_RESTRICTIONS:
        errors.append(f"restriction: {len(restrictions)}, нужно не меньше {MEDIUM_MIN_RESTRICTIONS}")
    if len(segments) < MEDIUM_MIN_SEGMENTS:
        errors.append(f"heat_network: {len(segments)}, нужно не меньше {MEDIUM_MIN_SEGMENTS}")
    for kind, feature_id, geom in restrictions:
        if geom.geom_type in POLYGONS and min_side(geom) < MIN_POLYGON_SIDE_M - LENGTH_TOLERANCE_M:
            errors.append(f"restriction {feature_id!r}: сторона {min_side(geom):.3f} м короче {MIN_POLYGON_SIDE_M} м")

    network_tree = STRtree([line for _, line in segments])
    chamber_tree = STRtree([point for _, point in stored["heat_chamber"]])
    point_tree = STRtree([point for _, point in stored["oks_connection_point"]])
    future = [(f"здание oks {props['id']!r}", geom) for props, geom in stored["building"] if point_tree.query(geom, predicate="contains").size]
    existing = [(f"здание oks {props['id']!r}", geom) for props, geom in stored["building"] if not point_tree.query(geom, predicate="contains").size]
    forbid = [(f"restriction {feature_id!r}", geom) for kind, feature_id, geom in restrictions if kind in FORBID_TYPES | POINT_TYPES] + existing
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
        oks_flow = props["flow_tph"]
        for index in nearest:
            segment = segments[index][0]
            capacity = capacities[segment["diameter"]]
            if existing_flow(capacities, share, segment["diameter"]) + oks_flow <= capacity:
                errors.append(f"{where}: расход ОКС {oks_flow} не выводит участок {segment['id']!r} за пропускную способность {capacity} т/ч")
    if blocked < MEDIUM_MIN_BLOCKED:
        errors.append(f"запрещённый полигон на прямой к сети у {blocked} ОКС, нужно не меньше {MEDIUM_MIN_BLOCKED}")
    if crossed < MEDIUM_MIN_CROSSED:
        errors.append(f"дорога или трамвайные пути на прямой к сети у {crossed} ОКС, нужно не меньше {MEDIUM_MIN_CROSSED}")

    # the gaps below keep a corridor around every obstacle, so each OKS has a detour
    polygons = forbid + crossing + future
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
    parser.add_argument(
        "--types", type=lambda value: value.split(","), default=list(DEFAULT_TYPES),
        help="ровно эти restriction_type должны быть в файле, через запятую; по умолчанию восемь типов таблицы ТЗ",
    )
    parser.add_argument("--id-prefix", default="", help="с чего начинается каждый id")
    args = parser.parse_args()
    unknown = [kind for kind in args.types if kind not in RESTRICTION_GEOMETRY]
    if unknown:
        parser.error(f"неизвестные типы ограничений {unknown}, известны: {', '.join(sorted(RESTRICTION_GEOMETRY))}")
    rules = json.loads(RULES_PATH.read_text(encoding="utf-8"))
    capacities = {row["dn"]: row["capacity_tph"] for row in rules["diameters"]}

    stored = check_schema(args.file, args.preset == "medium", set(capacities), args.types, args.id_prefix)
    print("SYNTH SCHEMA OK")
    to_utm = Transformer.from_crs("EPSG:4326", "EPSG:32637", always_xy=True)
    utm = {
        object_type: [(props, transform(to_utm.transform, geom)) for props, geom in items]
        for object_type, items in stored.items()
    }
    check_network(utm, capacities)
    print("SYNTH NETWORK OK")
    if args.preset == "medium":
        check_medium(utm, capacities, rules["existing_flow"]["share"])
        print("SYNTH MEDIUM OK")


if __name__ == "__main__":
    main()
