"""Набор датасетов для проверки режима глубины: по одному трудному случаю на файл.

Мир у всех один (как в scripts/depth_world.py): существующая теплосеть по оси x (y = 0) от источника, ОКС — квадраты
15×15 м по другую сторону улицы, точка подключения в 1 м внутри от южной стороны (как в датасете организаторов; на
самой границе 2D при Ду >= 500 трассу не находит — отдельная задача 2D); между ними объекты с вертикальными требованиями
табл. 2 (газ, кабель, существующая сеть) и полосы дороги и трамвая. Линии и полосы идут на всю ширину с запасом: обойти
их 2D-трасса не может. Все объекты "_source": "synthetic". Что проверяет каждый случай — в CASES.

Использование: python3 scripts/depth_suite.py <папка>   (пишет <папка>/<случай>.geojson и <папка>/README.md)
"""
import json
import os
import sys

from pyproj import Transformer

TO_WGS = Transformer.from_crs("EPSG:32637", "EPSG:4326", always_xy=True).transform
X0, Y0 = Transformer.from_crs("EPSG:4326", "EPSG:32637", always_xy=True).transform(37.60, 55.75)
# расходы под Ду (табл. 1): малый Ду 100–150, Ду 500, Ду 900, Ду 1000, Ду 1200, Ду 1400. Ду 1200–1400 через дорогу или
# трамвай 2D-расчёт сейчас не проводит (no_route, отдельная задача 2D): такие пересечения проверяются при Ду 900–1000
SMALL, D500, D900, D1000, D1200, D1400 = 40.0, 1500.0, 6000.0, 8500.0, 12000.0, 18000.0

# случай: (что проверяет, ОКС [(x, y, расход)], объекты [(тип, y | (x0, y0, x1, y1), полуширина полосы или 0)])
CASES = {
    "01_gas_far": ("газ далеко от узлов: «над» помещается, Kгл = 1, профиль только мельче 3,0 м",
                   [(100, 100, SMALL)], [("gas_pipeline", 50, 0)]),
    "02_gas_near_oks": ("газ в 3 м от полигона ОКС: подъём к «над» до точки подключения не помещается — глубина точки",
                        [(100, 100, SMALL)], [("gas_pipeline", 97, 0)]),
    "03_gas_near_tie": ("газ в 3 м от существующей сети: глубина камеры врезки",
                        [(100, 100, SMALL)], [("gas_pipeline", 3, 0)]),
    "04_cable_near_tie_big": ("кабель в 4 м от сети при Ду 900: «над» h <= 1,1, подъём 1,9 м не помещается",
                              [(100, 100, D900)], [("power_cable", 4, 0)]),
    "05_cable_under_tram_900": ("кабель под трамваем при Ду 900: «над» <= 1,1 < 1,2 — обязательно «под» 3,4 м",
                                [(100, 100, D900)], [("power_cable", 40, 0), ("tram_tracks", 40, 6)]),
    "06_gas_under_tram_900": ("газ под трамваем при Ду 900: «над» только в окне 1,2–1,5 м (трамвай снизу, газ сверху)",
                              [(100, 100, D900)], [("gas_pipeline", 40, 0), ("tram_tracks", 40, 6)]),
    "07_cable_under_road_1000": ("кабель под дорогой при Ду 1000: «над» ровно на 1,0 м (2,7 − 0,5 − 1,2 = 1,0 = пол дороги)",
                                 [(100, 100, D1000)], [("power_cable", 40, 0), ("road", 40, 8)]),
    "08_cable_1400_open": ("кабель без дороги при Ду 1400: «над» <= 0,6 < 0,7 (минимальная глубина) — «под»",
                           [(100, 100, D1400)], [("power_cable", 40, 0)]),
    "09_gas_cable_3m": ("газ и кабель в 3 м: зоны по 2 м перекрываются — одна зона, условия обоих объектов",
                        [(100, 100, D500)], [("gas_pipeline", 40, 0), ("power_cable", 43, 0)]),
    "10_gas_cable_same_point": ("газ и кабель пересекаются в точке трассы: одна зона, «над» по кабелю",
                                [(100, 100, D500)], [("gas_pipeline", (-400, 30, 600, 60), 0),
                                                     ("power_cable", (-400, 60, 600, 30), 0)]),
    "11_forced_then_over": ("вынужденное «под» кабеля (трамвай, Ду 900) в 8 м от газа: «над» газом не успевает — тоже «под»",
                            [(100, 100, D900)], [("power_cable", 30, 0), ("tram_tracks", 30, 6), ("gas_pipeline", 46, 0)]),
    "12_forced_far_over": ("вынужденное «под» кабеля и газ в 35 м: газ «над», между ними спуск и подъём",
                           [(100, 100, D900)], [("power_cable", 20, 0), ("tram_tracks", 20, 6), ("gas_pipeline", 60, 0)]),
    "13_dense_street": ("улица: дорога 16 м с газом, кабелем и трамваем, 4 пересечения подряд при Ду 900",
                        [(100, 110, D900)], [("road", 40, 12), ("tram_tracks", 36, 4), ("gas_pipeline", 30, 0),
                                             ("power_cable", 36, 0), ("gas_pipeline", 44, 0), ("power_cable", 50, 0)]),
    "14_oblique_cable": ("кабель под углом 45° к трассе: зона по 2 м вдоль трассы, косое пересечение",
                         [(100, 100, D500)], [("power_cable", (-3000, -3050, 3000, 2950), 0)]),
    "15_branch_between": ("камера ветвления двух ОКС между пересечениями: одна глубина узла на три цепочки",
                          [(40, 100, D500), (160, 100, D500)], [("gas_pipeline", 25, 0), ("power_cable", 80, 0)]),
    "16_trunk_mixed_du": ("общая магистраль на 5 ОКС, Ду 400–800 по участкам; при Ду 800 кабель под трамваем — «над» ровно 1,2 м",
                          [(30, 90, D500), (90, 110, 2500.0), (150, 95, 800.0), (210, 120, 3000.0), (270, 100, D500)],
                          [("power_cable", 20, 0), ("tram_tracks", 20, 5), ("gas_pipeline", 45, 0), ("road", 60, 6)]),
    "17_road_only": ("только дороги и трамвай без линий: верх не мельче 1,0 / 1,2 м, глубина 3,0 м, Kгл = 1",
                     [(100, 100, D500)], [("road", 30, 8), ("tram_tracks", 60, 5)]),
    "18_tram_small_du": ("кабель под трамваем при малом Ду: «над» 1,2–2,02 м допустимо",
                         [(100, 100, SMALL)], [("power_cable", 40, 0), ("tram_tracks", 40, 6)]),
    "19_heat_crossing": ("вторая существующая сеть ближе улицы: 2D врезается в неё, профиль на 3,0 м без пересечений "
                         "(пересечение сети без врезки — 145 в Москве и 2 в СПб, проверяются на городах)",
                         [(100, 100, D500)], [("heat_network", 50, 0)]),
    "20_two_forced_close": ("две вынужденные зоны в 6 м (кабели под трамваем, Ду 900): между ними «под» без возврата",
                            [(100, 100, D900)], [("power_cable", 30, 0), ("power_cable", 36, 0), ("tram_tracks", 33, 7)]),
}


def wgs(x, y):
    lon, lat = TO_WGS(X0 + x, Y0 + y)
    return [round(lon, 9), round(lat, 9)]


def build(oks, objects, width=200):
    feats, ids = [], iter(range(1, 10_000))

    def add(props, geom):
        props = {"id": next(ids), **props}
        if props["object_type"] == "restriction" or props.get("_synthetic"):
            props["_source"] = "synthetic"
        props.pop("_synthetic", None)
        feats.append({"type": "Feature", "properties": props, "geometry": geom})

    add({"object_type": "source"}, {"type": "Point", "coordinates": wgs(-60, 0)})
    add({"object_type": "heat_network", "diameter": 1400},
        {"type": "LineString", "coordinates": [wgs(-60, 0), wgs(width + 60, 0)]})
    for x, y, flow in oks:
        ring = [wgs(x - 7.5, y), wgs(x + 7.5, y), wgs(x + 7.5, y + 15), wgs(x - 7.5, y + 15), wgs(x - 7.5, y)]
        add({"object_type": "restriction", "restriction_type": "oks"}, {"type": "MultiPolygon", "coordinates": [[ring]]})
        add({"object_type": "oks_connection_point", "flow_tph": flow}, {"type": "Point", "coordinates": wgs(x, y + 1)})
    lo, hi = -3000, width + 3000  # объезд полосы по краю дороже любого пересечения
    for kind, where, half in objects:
        x0, y0, x1, y1 = where if isinstance(where, tuple) else (lo, where, hi, where)
        if half:
            ring = [wgs(x0, y0 - half), wgs(x1, y1 - half), wgs(x1, y1 + half), wgs(x0, y0 + half), wgs(x0, y0 - half)]
            add({"object_type": "restriction", "restriction_type": kind}, {"type": "Polygon", "coordinates": [ring]})
        elif kind == "heat_network":
            # вторая существующая сеть между улицей и ОКС
            add({"object_type": "heat_network", "diameter": 300, "_synthetic": True},
                {"type": "LineString", "coordinates": [wgs(x0, y0), wgs(x1, y1)]})
        else:
            add({"object_type": "restriction", "restriction_type": kind},
                {"type": "LineString", "coordinates": [wgs(x0, y0), wgs(x1, y1)]})
    return {"type": "FeatureCollection", "features": feats}


def main(out_dir):
    os.makedirs(out_dir, exist_ok=True)
    lines = ["# Набор для режима глубины", "", "Генератор: `scripts/depth_suite.py`. Прогон: `scripts/depth_suite_run.sh`.", "",
             "| Случай | Что проверяет |", "|---|---|"]
    for name, (why, oks, objects) in CASES.items():
        json.dump(build(oks, objects), open(os.path.join(out_dir, name + ".geojson"), "w", encoding="utf-8"),
                  ensure_ascii=False)
        lines.append(f"| `{name}` | {why} |")
    open(os.path.join(out_dir, "README.md"), "w", encoding="utf-8").write("\n".join(lines) + "\n")
    print(f"{len(CASES)} случаев в {out_dir}")


if __name__ == "__main__":
    main(sys.argv[1])
