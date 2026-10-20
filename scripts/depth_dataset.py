import json
import math
import sys

from pyproj import Transformer
from shapely.geometry import LineString, MultiLineString, shape
from shapely.ops import transform, unary_union

TO_M = Transformer.from_crs("EPSG:4326", "EPSG:32637", always_xy=True).transform
TO_WGS = Transformer.from_crs("EPSG:32637", "EPSG:4326", always_xy=True).transform
HALF_LEN_M = 150
BUILDING_GAP_M = 2.0
MIN_PIECE_M = 10

NODE_GAP_M = 2.0
PLAN = [
    ("gas_pipeline", 0, 0.5, 0, 0, "газ поперёк длинного участка далеко от узлов: «над», Kгл = 1"),
    ("power_cable", 1, 0.5, 0, 0, "кабель поперёк второго по длине участка далеко от узлов: «над»"),
    ("gas_pipeline", 2, 0.5, -4, 0, "газ и кабель в 8 м друг от друга: общая глубина без возврата на 3,0 м"),
    ("power_cable", 2, 0.5, 4, 0, "кабель — пара к газу выше"),
    ("gas_pipeline", 3, 0.0, 8, 0, "газ в 8 м от начального узла: «над» не помещается (≈8,5 м), «под» помещается (6 м)"),
    ("power_cable", 4, 1.0, -7, 0, "кабель в 7 м от конечного узла: «над» не помещается, «под» помещается"),
    ("gas_pipeline", 6, 0.0, 4.5, 0, "газ в 4,5 м от начального узла: не помещается ни «над», ни «под» — глубина в узле", 30),
    ("power_cable", 0, 0.25, 0, 45, "кабель под углом 45° к нормали: спецпроход по 2 м вдоль трассы, косое пересечение"),
]


def main(clean_path, out2d_path, out_path):
    data = json.load(open(clean_path, encoding="utf-8"))
    blocked = []
    for f in data["features"]:
        p = f["properties"]
        if p.get("object_type") == "restriction" and p.get("restriction_type") in ("oks", "water", "railway"):
            blocked.append(transform(TO_M, shape(f["geometry"])).buffer(BUILDING_GAP_M))
        elif p.get("object_type") in ("source", "heat_chamber", "oks_connection_point"):
            blocked.append(transform(TO_M, shape(f["geometry"])).buffer(NODE_GAP_M))
    blocked = unary_union(blocked)

    segs = []
    with open(out2d_path, encoding="utf-8") as f:
        for line in f:
            line = line.strip().strip(",")
            if '"heat_network"' in line and '"variant_id":"1"' in line:
                feat = json.loads(line)
                segs.append(transform(TO_M, shape(feat["geometry"])))
    segs.sort(key=lambda s: -s.length)

    next_id = max(int(f["properties"]["id"]) for f in data["features"]) + 1
    added = []
    for kind, idx, frac, shift, angle, why, *half in PLAN:
        half = half[0] if half else HALF_LEN_M
        seg = segs[idx]
        at = min(max(frac * seg.length + shift, 0.5), seg.length - 0.5)
        p0, p1 = seg.interpolate(max(at - 0.5, 0)), seg.interpolate(min(at + 0.5, seg.length))
        c = seg.interpolate(at)
        dx, dy = p1.x - p0.x, p1.y - p0.y
        n = (dx * dx + dy * dy) ** 0.5
        nx, ny = -dy / n, dx / n
        if angle:
            a = math.radians(angle)
            nx, ny = nx * math.cos(a) - ny * math.sin(a), nx * math.sin(a) + ny * math.cos(a)
        full = LineString([(c.x - nx * half, c.y - ny * half), (c.x + nx * half, c.y + ny * half)])
        rest = full.difference(blocked)
        pieces = list(rest.geoms) if isinstance(rest, MultiLineString) else [rest]
        for piece in pieces:
            if piece.is_empty or piece.length < MIN_PIECE_M or piece.distance(c) > 0.01:
                continue
            wgs = transform(TO_WGS, piece)
            data["features"].append({"type": "Feature", "properties": {
                "id": next_id, "object_type": "restriction", "restriction_type": kind, "_source": "synthetic"},
                "geometry": {"type": "LineString", "coordinates": [list(xy) for xy in wgs.coords]}})
            crossings = piece.intersection(unary_union(segs))
            added.append((next_id, kind, round(piece.length, 1), len(getattr(crossings, "geoms", [crossings])), why))
            next_id += 1
    json.dump(data, open(out_path, "w", encoding="utf-8"), ensure_ascii=False)
    for row in added:
        print(*row, sep="\t")


if __name__ == "__main__":
    main(*sys.argv[1:4])
