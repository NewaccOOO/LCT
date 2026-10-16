import argparse
import json
from itertools import pairwise
from pathlib import Path

from pyproj import Transformer
from shapely import affinity
from shapely.geometry import (
    LineString,
    MultiPolygon,
    Point,
    box,
    mapping,
    shape,
)
from shapely.ops import (
    transform,
    unary_union,
)

DATASET = Path("data/real/dataset-corrected.geojson")
CRS84 = {"type": "name", "properties": {"name": "urn:ogc:def:crs:OGC:1.3:CRS84"}}
COORD_DIGITS = 9
ORIGIN_M = (414000.0, 6171500.0)
TRUNK_Y_M = (600, 400, 200, 0, -200, -400, -600)
TRUNK_DN = 500

TRAM_AXIS_M = [(70, 1200), (70, 150), (370, -50), (370, -1200)]
TRAM_WIDTH_M = 8.0
TRAM_OKS = [
    (box(200, 450, 240, 510), (202, 480), 18.0),
    (box(180, 250, 230, 280), (182, 265), 25.0),
    (box(420, 110, 460, 150), (422, 130), 12.0),
    (box(378, -230, 418, -180), (380, -205), 20.0),
    (box(480, -470, 540, -430), (482, -450), 30.0),
    (box(450, -720, 490, -680), (452, -700), 8.0),
]

METRO_AXIS_M = [(150, -400), (150, 400)]
METRO_VESTIBULE_M = box(125, 405, 185, 440)
METRO_OKS = [
    (box(300, 150, 340, 200), (302, 175), 20.0),
    (box(320, 0, 370, 40), (322, 20), 35.0),
    (box(300, -180, 340, -130), (302, -155), 15.0),
    (box(330, -360, 370, -320), (332, -340), 25.0),
    (box(175, -60, 205, -20), (177, -40), 10.0),
    (box(300, 470, 340, 500), (302, 485), 12.0),
]

RING_OUTER_M = 130.0
RING_INNER_M = 90.0
RING_OVERLAP_M = 1.0
RINGS = [
    ((350, 350), 0.0, (box(335, 340, 365, 360), (337, 350), 18.0)),
    ((350, 0), 1.5, (box(335, -10, 365, 10), (363, 0), 10.0)),
    ((350, -350), 6.0, (box(335, -360, 365, -340), (363, -350), 14.0)),
]
UNREACHABLE_OKS = [
    (box(150, 520, 190, 560), (152, 540), 20.0),
    (box(3000, -720, 3040, -680), (3002, -700), 15.0),
    (box(13000, -720, 13040, -680), (13002, -700), 15.0),
]

COMBINED_ROADS = [
    (((414253, 6173087), (414408, 6173513)), 16.0),
    (((414111, 6173145), (414237, 6173489)), 12.0),
    (((414807, 6173133), (414266, 6173330)), 16.0),
]
COMBINED_TRAM_ROAD = 0
COMBINED_TRAM_WIDTH_M = 7.0
COMBINED_CROSSINGS = [
    ("gas_pipeline", 0, 0.3),
    ("power_cable", 0, 0.6),
    ("power_cable", 1, 0.35),
    ("gas_pipeline", 1, 0.7),
    ("gas_pipeline", 2, 0.8),
    ("power_cable", 2, 0.6),
]
CROSSING_REACH_M = 60.0
BUILDING_GAP_M = 1.0

TO_WGS = Transformer.from_crs("EPSG:32637", "EPSG:4326", always_xy=True).transform
TO_UTM = Transformer.from_crs("EPSG:4326", "EPSG:32637", always_xy=True).transform


def rounded(value):
    if isinstance(value, float):
        return round(value, COORD_DIGITS)
    return [rounded(item) for item in value]


def feature(geom, props):
    coordinates = mapping(transform(TO_WGS, geom))["coordinates"]
    return {"type": "Feature", "properties": props, "geometry": {"type": geom.geom_type, "coordinates": rounded(coordinates)}}


def scene(oks, restrictions):
    items = [(Point(0, TRUNK_Y_M[0]), {"object_type": "source"})]
    items += [(LineString([(0, a), (0, b)]), {"object_type": "heat_network", "diameter": TRUNK_DN}) for a, b in pairwise(TRUNK_Y_M)]
    items += [(Point(0, y), {"object_type": "heat_chamber"}) for y in TRUNK_Y_M[1:-1]]
    items += [(geom, {"object_type": "restriction", "restriction_type": kind}) for kind, geom in restrictions]
    for polygon, point, flow in oks:
        items.append((MultiPolygon([polygon]), {"object_type": "restriction", "restriction_type": "oks"}))
        items.append((Point(point), {"object_type": "oks_connection_point", "flow_tph": flow}))
    return [feature(affinity.translate(geom, *ORIGIN_M), {"id": number, **props}) for number, (geom, props) in enumerate(items, 1)]


def collection(name, features):
    return {"type": "FeatureCollection", "name": name, "crs": CRS84, "features": features}


def ring(center, slit):
    cx, cy = center
    annulus = Point(center).buffer(RING_OUTER_M).difference(Point(center).buffer(RING_INNER_M))
    reach = RING_OUTER_M + 1
    cut = box(cx, cy - slit / 2, cx + reach, cy + slit / 2)
    water = annulus.intersection(box(cx - reach, cy - RING_OVERLAP_M, cx + reach, cy + reach)).difference(cut)
    park = annulus.intersection(box(cx - reach, cy - reach, cx + reach, cy + RING_OVERLAP_M)).difference(cut)
    return [("water", water), ("park", park)]


def strip(axis, width, keep_out):
    return LineString(axis).buffer(width / 2, cap_style="flat").difference(keep_out)


def crossing(axis, share, keep_out):
    (ax, ay), (bx, by) = axis.coords
    nx, ny = -(by - ay) / axis.length, (bx - ax) / axis.length
    station = axis.interpolate(share, normalized=True)
    line = LineString([
        (station.x - nx * CROSSING_REACH_M, station.y - ny * CROSSING_REACH_M),
        (station.x + nx * CROSSING_REACH_M, station.y + ny * CROSSING_REACH_M),
    ])
    pieces = line.difference(keep_out)
    return next(piece for piece in getattr(pieces, "geoms", [pieces]) if piece.distance(station) < 1e-6)


def combined():
    data = json.loads(DATASET.read_text(encoding="utf-8"))
    buildings = [shape(f["geometry"]) for f in data["features"] if f["properties"].get("restriction_type") == "oks"]
    keep_out = transform(TO_UTM, unary_union(buildings)).buffer(BUILDING_GAP_M)
    added = [("road", strip(axis, width, keep_out)) for axis, width in COMBINED_ROADS]
    added.append(("tram_tracks", strip(COMBINED_ROADS[COMBINED_TRAM_ROAD][0], COMBINED_TRAM_WIDTH_M, keep_out)))
    for kind, road, share in COMBINED_CROSSINGS:
        added.append((kind, crossing(LineString(COMBINED_ROADS[road][0]), share, keep_out)))
    first = max(f["properties"]["id"] for f in data["features"]) + 1
    return data["features"] + [
        feature(geom, {"id": first + number, "object_type": "restriction", "restriction_type": kind})
        for number, (kind, geom) in enumerate(added)
    ]


def main():
    parser = argparse.ArgumentParser(description="Наборы по замечаниям экспертов 16.10: трамвай, метро, недостижимые ОКС, датасет с дорогами")
    parser.add_argument("--out", type=Path, default=Path("data/synth"), help="куда писать e4-*.geojson")
    args = parser.parse_args()
    tram = LineString(TRAM_AXIS_M)
    rings = [item for center, slit, _ in RINGS for item in ring(center, slit)]
    sets = {
        "e4-tram-polygon": scene(TRAM_OKS, [("tram_tracks", tram.buffer(TRAM_WIDTH_M / 2, cap_style="flat", join_style="mitre"))]),
        "e4-tram-line": scene(TRAM_OKS, [("tram_tracks", tram)]),
        "e4-metro": scene(METRO_OKS, [("metro", LineString(METRO_AXIS_M)), ("metro", METRO_VESTIBULE_M)]),
        "e4-unreachable": scene([oks for _, _, oks in RINGS] + UNREACHABLE_OKS, rings),
        "e4-combined": combined(),
    }
    args.out.mkdir(parents=True, exist_ok=True)
    for name, features in sets.items():
        path = args.out / f"{name}.geojson"
        path.write_text(json.dumps(collection(name, features), ensure_ascii=False), encoding="utf-8")
        print(path)


if __name__ == "__main__":
    main()
