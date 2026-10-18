"""Случайный синтетический мир для стресс-проверки режима глубины: обойти пересечения нельзя.

Существующая теплосеть идёт по оси x (y = 0), ОКС — квадраты 15×15 м по другую сторону улицы (y 70–130), точка
подключения на южной стороне. Между ними на всю ширину (и с запасом 400 м) — линии газа и кабеля и полосы дороги и
трамвая в случайных местах: каждая трасса обязана их пересечь, часто у самой камеры или ОКС, иногда линия внутри
полосы трамвая. Расходы — до 9000 т/ч на ОКС (Ду до 1000–1400): там «над» кабелем или газом под трамваем запрещено
табл. 2 и глубже 3,0 м уйти обязательно. Все объекты "_source": "synthetic".

Использование: python3 scripts/depth_world.py <seed> <выход.geojson>
"""
import json
import random
import sys

from pyproj import Transformer

TO_WGS = Transformer.from_crs("EPSG:32637", "EPSG:4326", always_xy=True).transform
X0, Y0 = Transformer.from_crs("EPSG:4326", "EPSG:32637", always_xy=True).transform(37.60, 55.75)


def wgs(x, y):
    lon, lat = TO_WGS(X0 + x, Y0 + y)
    return [round(lon, 9), round(lat, 9)]


def main(seed, out):
    rnd = random.Random(int(seed))
    feats, ids = [], iter(range(1, 10_000))

    def add(props, geom):
        props = {"id": next(ids), **props}
        if props["object_type"] == "restriction":
            props["_source"] = "synthetic"
        feats.append({"type": "Feature", "properties": props, "geometry": geom})

    width = rnd.choice([150, 250, 400])
    add({"object_type": "source"}, {"type": "Point", "coordinates": wgs(-60, 0)})
    add({"object_type": "heat_network", "diameter": 1400},
        {"type": "LineString", "coordinates": [wgs(-60, 0), wgs(width + 60, 0)]})
    n = rnd.randint(2, 6)
    flows = [rnd.choice([rnd.uniform(5, 300), rnd.uniform(300, 3000), rnd.uniform(3000, 9000)]) for _ in range(n)]
    # сумма не больше пропускной способности Ду 1400 (22 501,9 т/ч): иначе 2D-расчёт падает (отдельная задача 2D)
    scale = min(1.0, 21000 / sum(flows))
    for i in range(n):
        x = width * (i + 0.5) / n + rnd.uniform(-10, 10)
        y = rnd.uniform(70, 130)
        ring = [wgs(x - 7.5, y), wgs(x + 7.5, y), wgs(x + 7.5, y + 15), wgs(x - 7.5, y + 15), wgs(x - 7.5, y)]
        add({"object_type": "restriction", "restriction_type": "oks"}, {"type": "MultiPolygon", "coordinates": [[ring]]})
        add({"object_type": "oks_connection_point", "flow_tph": round(flows[i] * scale, 2)},
            {"type": "Point", "coordinates": wgs(x, y)})
    lo, hi = -400, width + 400
    for _ in range(rnd.randint(1, 4)):
        y = rnd.uniform(4, 55)
        kind = rnd.choice(["gas_pipeline", "power_cable"])
        add({"object_type": "restriction", "restriction_type": kind},
            {"type": "LineString", "coordinates": [wgs(lo, y), wgs(hi, y)]})
        if rnd.random() < 0.6:  # линия под полосой трамвая или дороги
            half = rnd.uniform(3, 9)
            road = rnd.choice(["tram_tracks", "road"])
            yc = y + rnd.uniform(-half + 1, half - 1)
            ring = [wgs(lo, yc - half), wgs(hi, yc - half), wgs(hi, yc + half), wgs(lo, yc + half), wgs(lo, yc - half)]
            add({"object_type": "restriction", "restriction_type": road}, {"type": "Polygon", "coordinates": [ring]})
    json.dump({"type": "FeatureCollection", "features": feats}, open(out, "w", encoding="utf-8"), ensure_ascii=False)


if __name__ == "__main__":
    main(*sys.argv[1:3])
