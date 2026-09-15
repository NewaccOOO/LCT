from shapely.geometry import (
    LineString,
    MultiPoint,
    Polygon,
    box,
)
from shapely.geometry.base import BaseGeometry
from shapely.ops import split

from heatcheck.model import (
    Feature,
    diameter_for,
    next_diameter,
)
from heatscen import (
    Expect,
    Scene,
    rules,
    scenario,
)
from heatscen.expect import required_offset

TRUNK_END = 400.0
YARD_CP = (200.0, 130.0)
SOUTH_WALL = (40.0, 55.0)
# Запас к отступу в границах длины и ширинах проёмов: сервис строит граф на ступень Ду выше и упрощает буферы.
SLACK_M = 1.0
SMALL_DN = 65


def flow_for(dn: int) -> float:
    """Расход посередине между пропускной способностью предыдущей строки и строки dn: участку нужен ровно этот Ду."""
    rows = rules()["diameters"]
    index = next(i for i, row in enumerate(rows) if row["dn"] == dn)
    low = rows[index - 1]["capacity_tph"] if index else 0.0
    return round((low + rows[index]["capacity_tph"]) / 2, 1)


def trunk(sc: Scene, flow: float) -> None:
    """Источник, магистраль вдоль оси X, камера и продолжение: у точки подключения два кандидата врезки.

    Диаметр магистрали держит тройной расход ОКС, поэтому реконструкции нет.
    """
    dn = diameter_for(rules(), 3 * flow)
    sc.source("src", 0, 0)
    sc.pipe("hn-1", [(0, 0), (TRUNK_END, 0)], dn=dn, flow=flow, upstream="src")
    sc.chamber("hc-1", TRUNK_END, 0, dn=dn, upstream="hn-1")
    sc.pipe("hn-2", [(TRUNK_END, 0), (2 * TRUNK_END, 0)], dn=dn, flow=flow, upstream="hc-1")


def offset(kind: str, dn: int) -> float:
    """Отступ оси участка Ду dn от объекта типа kind по rules.json: clearance + width/2 (+ half_width_m у линий)."""
    object_type = "oks_existing" if kind == "oks_existing" else "restriction"
    return required_offset(rules(), Feature(kind, object_type, {"restriction_type": kind}, None), dn)


def routing_offset(kind: str, dn: int) -> float:
    return offset(kind, next_diameter(rules(), dn)) + SLACK_M


def detour(tie: tuple[float, float], cp: tuple[float, float], obstacle: BaseGeometry, off: float) -> float:
    """Длина обхода выпуклой оболочки препятствия, раздутого на off с острыми углами и торцами, с более короткой стороны прямой tie–cp."""
    chord = LineString([tie, cp])
    hull = obstacle.buffer(off, cap_style="square", join_style="mitre").convex_hull
    ends = MultiPoint([tie, cp])
    return min(ends.union(half).convex_hull.exterior.length - chord.length for half in split(hull, chord).geoms)


def rings(geom: BaseGeometry) -> list[list[tuple[float, float]]]:
    return [list(part.exterior.coords) for part in getattr(geom, "geoms", [geom])]


def tier_step(dn: int) -> float:
    """Разница отступа от здания между диапазоном Ду dn и соседним: вверх для первого диапазона, вниз для остальных."""
    tiers = rules()["restrictions"]["oks_existing"]["clearance_m"]
    index = next(i for i, tier in enumerate(tiers) if dn <= tier["dn_max"])
    return tiers[1]["m"] - tiers[0]["m"] if index == 0 else tiers[index]["m"] - tiers[index - 1]["m"]


def courtyard(gaps: list[tuple[float, float]]) -> BaseGeometry:
    """Двор из существующих зданий со стенами 15 м вокруг точки YARD_CP; проёмы в южной стене — (центр по x, ширина)."""
    bottom, top = SOUTH_WALL
    walls = box(85, bottom, 415, 215).difference(box(100, top, 400, 200))
    for center, width in gaps:
        walls = walls.difference(box(center - width / 2, bottom - 1, center + width / 2, top + 1))
    return walls


def through_gap(center: float, dn: int) -> float:
    """Длина допустимой трассы от врезки под ОКС через проём с центром center: к проёму, сквозь стену, к ОКС."""
    off = routing_offset("oks_existing", dn)
    bottom, top = SOUTH_WALL
    return LineString([(YARD_CP[0], 0), (center, bottom - off), (center, top + off), YARD_CP]).length


def forbid_on_line(kind: str, obstacle: BaseGeometry, cp: tuple[float, float]) -> tuple[Scene, Expect]:
    sc = Scene()
    flow = flow_for(SMALL_DN)
    trunk(sc, flow)
    sc.oks("oks-1", cp=cp, flow=flow)
    if kind == "oks_existing":
        sc.existing_oks("obst-1", rings(obstacle))
    else:
        sc.restriction("obst-1", kind, obstacle)
    return sc, Expect(
        clearance=["obst-1"],
        no_special=True,
        unconnected=[],
        max_new_length=detour((cp[0], 0), cp, obstacle, routing_offset(kind, SMALL_DN)),
    )


@scenario("S05-01", tz=["TZ-29", "TZ-31", "TZ-33"], title="парк на прямой от врезки к ОКС: обход с отступом 1,0 м плюс полширины")
def park_on_straight_line() -> tuple[Scene, Expect]:
    return forbid_on_line("park", box(50, 50, 150, 100), (100, 150))


@scenario("S05-02", tz=["TZ-29", "TZ-31", "TZ-33"], title="социальный объект неправильной формы на прямой: обход")
def social_area_on_straight_line() -> tuple[Scene, Expect]:
    return forbid_on_line("social_area", Polygon([(50, 40), (180, 55), (160, 110), (65, 95)]), (110, 160))


@scenario("S05-03", tz=["TZ-29", "TZ-33"], title="запрещённая территория на прямой: обход")
def prohibited_site_on_straight_line() -> tuple[Scene, Expect]:
    return forbid_on_line("prohibited_site", box(80, 60, 160, 90), (120, 140))


@scenario("S05-04", tz=["TZ-29", "TZ-33"], title="водоём на прямой: обход")
def water_on_straight_line() -> tuple[Scene, Expect]:
    return forbid_on_line("water", Polygon([(90, 70), (220, 62), (230, 85), (100, 95)]), (160, 130))


@scenario("S05-05", tz=["TZ-29", "TZ-31", "TZ-32"], title="существующее здание на прямой, Ду меньше 500: обход с отступом 5 м")
def oks_existing_on_straight_line() -> tuple[Scene, Expect]:
    return forbid_on_line("oks_existing", box(170, 40, 230, 80), (200, 140))


def courtyard_scene(dn: int, gaps: list[tuple[float, float]]) -> tuple[Scene, str]:
    sc = Scene()
    flow = flow_for(dn)
    trunk(sc, flow)
    cp_id = sc.oks("oks-1", cp=YARD_CP, flow=flow)
    sc.existing_oks("yard-1", rings(courtyard(gaps)))
    return sc, cp_id


@scenario("S05-06", tz=["TZ-3", "TZ-32"], title="Ду 400, двор с одним проёмом: проём шире двух отступов 5 м, но уже двух по 7 м — трасса через него")
def oks_existing_dn400_single_gap() -> tuple[Scene, Expect]:
    dn = 400
    gap = 2 * offset("oks_existing", dn) + tier_step(dn)
    sc, cp_id = courtyard_scene(dn, [(YARD_CP[0], gap)])
    return sc, Expect(
        clearance=["yard-1"],
        unconnected=[],
        segment_dn={cp_id: dn},
        max_new_length=YARD_CP[1] + SLACK_M,
    )


@scenario("S05-07", tz=["TZ-3", "TZ-32"], title="Ду 500, двор с узким и широким проёмом: узкий годится только при 5 м, при Ду 500 нужен 7 м — трасса через широкий")
def oks_existing_dn500_wide_gap() -> tuple[Scene, Expect]:
    dn = 500
    narrow = 2 * offset("oks_existing", dn) - tier_step(dn)
    sc, cp_id = courtyard_scene(dn, [(YARD_CP[0], narrow), (320, 40)])
    return sc, Expect(
        clearance=["yard-1"],
        unconnected=[],
        segment_dn={cp_id: dn},
        max_new_length=through_gap(320, dn),
    )


@scenario("S05-08", tz=["TZ-3", "TZ-32"], title="Ду 900, двор с узким и широким проёмом: узкий годится при 7 м, при Ду 900 нужен 9 м — трасса через широкий")
def oks_existing_dn900_wide_gap() -> tuple[Scene, Expect]:
    dn = 900
    narrow = 2 * offset("oks_existing", dn) - tier_step(dn)
    sc, cp_id = courtyard_scene(dn, [(YARD_CP[0], narrow), (320, 50)])
    return sc, Expect(
        clearance=["yard-1"],
        unconnected=[],
        segment_dn={cp_id: dn},
        max_new_length=through_gap(320, dn),
    )


@scenario("S05-09", tz=["TZ-3", "TZ-32"], title="Ду 400, двор с узким и широким проёмом: узкий проём достаточен при 5 м — трасса прямо через него, а не в обход")
def oks_existing_dn400_narrow_gap_preferred() -> tuple[Scene, Expect]:
    dn = 400
    narrow = 2 * offset("oks_existing", dn) + tier_step(dn)
    sc, cp_id = courtyard_scene(dn, [(YARD_CP[0], narrow), (320, 40)])
    return sc, Expect(
        clearance=["yard-1"],
        unconnected=[],
        segment_dn={cp_id: dn},
        max_new_length=YARD_CP[1] + SLACK_M,
    )


# Обе части мультиполигона лежат на прямой: сервис, который читает только одну часть, пройдёт сквозь другую.
@scenario("S05-10", tz=["TZ-3", "TZ-32"], title="существующий ОКС из двух полигонов (MultiPolygon), обе части на прямой: обход с отступом 5 м от каждой")
def oks_existing_multipolygon_on_line() -> tuple[Scene, Expect]:
    sc = Scene()
    flow = flow_for(SMALL_DN)
    trunk(sc, flow)
    sc.oks("oks-1", cp=(200, 160), flow=flow)
    buildings = box(170, 30, 230, 60).union(box(185, 95, 260, 125))
    sc.existing_oks("obst-1", rings(buildings))
    return sc, Expect(
        clearance=["obst-1"],
        no_special=True,
        unconnected=[],
        max_new_length=detour((200, 0), (200, 160), buildings, routing_offset("oks_existing", SMALL_DN)),
    )


@scenario("S05-11", tz=["TZ-3", "TZ-33"], title="водоём из двух полигонов (MultiPolygon), обе части на прямой: обход с отступом 1,0 м от каждой")
def water_multipolygon_on_line() -> tuple[Scene, Expect]:
    sc = Scene()
    flow = flow_for(SMALL_DN)
    trunk(sc, flow)
    sc.oks("oks-1", cp=(130, 150), flow=flow)
    water = box(60, 40, 180, 65).union(box(80, 95, 160, 115))
    sc.restriction("water-1", "water", water)
    return sc, Expect(
        clearance=["water-1"],
        no_special=True,
        unconnected=[],
        max_new_length=detour((130, 0), (130, 150), water, routing_offset("water", SMALL_DN)),
    )


def future_oks_across(polygon: list[list[tuple[float, float]]], cp2: tuple[float, float]) -> tuple[Scene, Expect]:
    # Точка подключения второго ОКС дальше 300 м от первой: сервис строит их деревья отдельно, каждое по прямой.
    sc = Scene()
    flow = flow_for(SMALL_DN)
    trunk(sc, flow)
    cp1 = (200.0, 150.0)
    sc.oks("oks-1", cp=cp1, flow=flow)
    sc.oks("oks-2", cp=cp2, flow=flow, polygon=polygon)
    return sc, Expect(
        no_special=True,
        unconnected=[],
        max_new_length=cp1[1] + cp2[1] + SLACK_M,
    )


@scenario("S05-12", tz=["TZ-3", "TZ-29"], title="перспективный ОКС на прямой к другому ОКС препятствием не считается: трасса прямо через его полигон")
def oks_future_is_not_obstacle() -> tuple[Scene, Expect]:
    return future_oks_across([[(100, 60), (700, 60), (700, 100), (100, 100)]], (650, 60))


@scenario("S05-13", tz=["TZ-3", "TZ-29"], title="перспективный ОКС из двух полигонов (MultiPolygon) на прямой: не препятствие")
def oks_future_multipolygon_is_not_obstacle() -> tuple[Scene, Expect]:
    return future_oks_across(
        [[(120, 70), (280, 70), (280, 110), (120, 110)], [(640, 80), (720, 80), (720, 120), (640, 120)]],
        (680, 80),
    )


def cp_in_clearance(kind: str, obstacle: BaseGeometry) -> tuple[Scene, Expect]:
    # Точка подключения oks-1 в (200, 150) ближе отступа к препятствию: любой последний участок нарушает запрет.
    sc = Scene()
    flow1, flow2 = flow_for(SMALL_DN), flow_for(80)
    trunk(sc, flow1 + flow2)
    sc.oks("oks-1", cp=(200, 150), flow=flow1, away=(-1, 0))
    sc.oks("oks-2", cp=(650, 120), flow=flow2)
    if kind == "oks_existing":
        sc.existing_oks("obst-1", rings(obstacle))
    else:
        sc.restriction("obst-1", kind, obstacle)
    penalty = rules()["penalty"]
    return sc, Expect(
        clearance=["obst-1"],
        unconnected=["oks-1"],
        penalty=penalty["fixed"] + penalty["per_tph"] * flow1,
    )


@scenario("S05-14", tz=["TZ-33", "TZ-61"], title="точка подключения в половине отступа от парка: ОКС неподключённый со штрафом, второй подключён")
def cp_inside_park_clearance_unconnected() -> tuple[Scene, Expect]:
    distance = offset("park", SMALL_DN) / 2
    return cp_in_clearance("park", box(200 + distance, 120, 260, 180))


@scenario("S05-15", tz=["TZ-32", "TZ-61"], title="точка подключения ближе 5 м к существующему зданию: ОКС неподключённый со штрафом")
def cp_inside_oks_existing_clearance_unconnected() -> tuple[Scene, Expect]:
    distance = offset("oks_existing", SMALL_DN) / 2
    return cp_in_clearance("oks_existing", Polygon([(200 + distance, 130), (240, 125), (245, 175), (200 + distance, 170)]))


@scenario("S05-16", tz=["TZ-31", "TZ-33"], title="коридор между парком и социальным объектом шире двух отступов: трасса прямо по коридору")
def corridor_wide_enough() -> tuple[Scene, Expect]:
    sc = Scene()
    flow = flow_for(SMALL_DN)
    trunk(sc, flow)
    sc.oks("oks-1", cp=(200, 150), flow=flow)
    half = routing_offset("park", SMALL_DN) - SLACK_M / 2
    sc.restriction("park-1", "park", box(40, 50, 200 - half, 100))
    sc.restriction("social-1", "social_area", box(200 + half, 50, 360, 100))
    return sc, Expect(
        clearance=["park-1", "social-1"],
        no_special=True,
        unconnected=[],
        max_new_length=150 + SLACK_M,
    )


@scenario("S05-17", tz=["TZ-31", "TZ-33"], title="коридор между запретной территорией и водоёмом уже двух отступов: трасса в обход обоих")
def corridor_too_narrow() -> tuple[Scene, Expect]:
    sc = Scene()
    flow = flow_for(SMALL_DN)
    trunk(sc, flow)
    sc.oks("oks-1", cp=(230, 150), flow=flow)
    half = offset("water", SMALL_DN) - SLACK_M / 4
    site, water = box(120, 60, 230 - half, 95), box(230 + half, 55, 330, 90)
    sc.restriction("site-1", "prohibited_site", site)
    sc.restriction("water-1", "water", water)
    return sc, Expect(
        clearance=["site-1", "water-1"],
        no_special=True,
        unconnected=[],
        max_new_length=detour((230, 0), (230, 150), site.union(water), routing_offset("water", SMALL_DN)),
    )
