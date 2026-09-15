import math

from shapely.affinity import rotate
from shapely.geometry import (
    LineString,
    MultiLineString,
    Polygon,
    box,
)

from heatcheck.model import (
    diameter_for,
    diameter_row,
)
from heatscen import (
    Expect,
    Scene,
    rules,
    scenario,
)
from heatscen.families.s05_forbid import (
    SLACK_M,
    SMALL_DN,
    detour,
    flow_for,
    offset,
    routing_offset,
    trunk,
)

# Длинные объекты: обход их концов намного длиннее перехода.
LONG_M = 1000.0
ANGLED_M = 600.0
ROAD_M = 12.0
TRAM_M = 8.0


def params(kind: str) -> dict:
    return rules()["restrictions"][kind]


def strip(center: tuple[float, float], angle_deg: float, length: float, width: float) -> Polygon:
    """Полоса дороги или путей length × width с осью под angle_deg к оси X."""
    cx, cy = center
    return rotate(box(cx - length / 2, cy - width / 2, cx + length / 2, cy + width / 2), angle_deg, origin=center)


def axis(center: tuple[float, float], angle_deg: float, length: float) -> LineString:
    cx, cy = center
    return rotate(LineString([(cx - length / 2, cy), (cx + length / 2, cy)]), angle_deg, origin=center)


def single_oks(sc: Scene, cp: tuple[float, float], dn: int) -> None:
    flow = flow_for(dn)
    trunk(sc, flow)
    sc.oks("oks-1", cp=cp, flow=flow)


def polygon_zone(kind: str, width: float) -> float:
    """Длина специального участка при переходе полосы под прямым углом: ширина плюс margin_m с каждой стороны."""
    return width + 2 * params(kind)["margin_m"]


def line_zone(kind: str) -> float:
    return 2 * params(kind)["margin_m"]


def straight_crossing(kind: str, obstacle, cp: tuple[float, float], dn: int) -> tuple[Scene, Expect]:
    # Переход без ограничения угла или под прямым углом не удлиняет трассу: она идёт по прямой от врезки к ОКС.
    sc = Scene()
    single_oks(sc, cp, dn)
    sc.restriction("obj-1", kind, obstacle)
    return sc, Expect(
        special={kind: 1},
        technical_nodes=2,
        costs_by_formula=True,
        summary={"new_network_length": cp[1]},
    )


def angled_crossing(kind: str, zone: float, obstacle, cp: tuple[float, float], dn: int) -> tuple[Scene, Expect]:
    # Переход под допустимым углом сервис может довернуть к прямому; это короче, чем прямая плюс вся зона.
    sc = Scene()
    single_oks(sc, cp, dn)
    sc.restriction("obj-1", kind, obstacle)
    return sc, Expect(
        special={kind: 1},
        technical_nodes=2,
        costs_by_formula=True,
        max_new_length=cp[1] + zone,
    )


@scenario("S06-01", tz=["TZ-31", "TZ-34"], title="короткая дорога поперёк прямой под 30°: переход запрещён, трасса обходит её конец с отступом 1,5 м")
def road_30deg_short_detour() -> tuple[Scene, Expect]:
    sc = Scene()
    single_oks(sc, (200, 120), SMALL_DN)
    road = strip((200, 60), 60, 20, TRAM_M)
    sc.restriction("road-1", "road", road)
    return sc, Expect(
        no_special=True,
        clearance=["road-1"],
        unconnected=[],
        max_new_length=detour((200, 0), (200, 120), road, routing_offset("road", SMALL_DN)),
    )


@scenario("S06-02", tz=["TZ-30", "TZ-34"], title="длинная дорога под 30° к прямой: трасса доворачивает и переходит под углом не меньше 45°")
def road_30deg_long_turns_to_cross() -> tuple[Scene, Expect]:
    sc = Scene()
    single_oks(sc, (200, 120), SMALL_DN)
    center, angle = (200, 60), 60
    sc.restriction("road-1", "road", strip(center, angle, ANGLED_M, TRAM_M))
    # Допустимый переход под прямым углом через центр полосы: от врезки к точке перед зоной, поперёк, к ОКС.
    nx, ny = -math.sin(math.radians(angle)), math.cos(math.radians(angle))
    d = TRAM_M / 2 + params("road")["margin_m"] + SLACK_M
    before, after = (center[0] - nx * d, center[1] - ny * d), (center[0] + nx * d, center[1] + ny * d)
    return sc, Expect(
        special={"road": 1},
        costs_by_formula=True,
        max_new_length=LineString([(200, 0), before, after, (200, 120)]).length,
    )


@scenario("S06-03", tz=["TZ-30", "TZ-34", "TZ-55"], title="дорога под 60° к прямой: специальный участок, k 1,60, технические узлы на границах зоны")
def road_60deg_special() -> tuple[Scene, Expect]:
    return angled_crossing("road", polygon_zone("road", ROAD_M), strip((200, 60), 30, ANGLED_M, ROAD_M), (200, 120), SMALL_DN)


@scenario("S06-04", tz=["TZ-30", "TZ-34", "TZ-39", "TZ-55"], title="дорога под 90°: специальный участок — полигон плюс 3 м с каждой стороны, стоимость × 1,60")
def road_perpendicular_zone_plus_margin() -> tuple[Scene, Expect]:
    return straight_crossing("road", strip((200, 60), 0, LONG_M, ROAD_M), (200, 120), SMALL_DN)


@scenario("S06-05", tz=["TZ-30", "TZ-35", "TZ-39", "TZ-55"], title="трамвайные пути под 90°: зона путей плюс 3 м, стоимость × 1,75")
def tram_perpendicular_zone_and_k() -> tuple[Scene, Expect]:
    return straight_crossing("tram_tracks", strip((150, 70), 0, LONG_M, TRAM_M), (150, 150), 100)


@scenario("S06-06", tz=["TZ-30", "TZ-35", "TZ-55"], title="трамвайные пути под 50° к прямой: переход допустим, специальный участок, k 1,75")
def tram_50deg_special() -> tuple[Scene, Expect]:
    return angled_crossing("tram_tracks", polygon_zone("tram_tracks", TRAM_M), strip((200, 70), 40, ANGLED_M, TRAM_M), (200, 140), 100)


@scenario("S06-07", tz=["TZ-30", "TZ-31", "TZ-36", "TZ-39"], title="газопровод поперёк прямой: специальный участок по 2 м от точки пересечения, всего 4 м, × 1,25")
def gas_perpendicular_4m_special() -> tuple[Scene, Expect]:
    return straight_crossing("gas_pipeline", axis((150, 50), 0, LONG_M), (150, 110), SMALL_DN)


@scenario("S06-08", tz=["TZ-30", "TZ-36", "TZ-55"], title="газопровод под 45° к прямой: угол не ограничен, специальный участок 4 м вдоль трассы")
def gas_oblique_4m_along_route() -> tuple[Scene, Expect]:
    return straight_crossing("gas_pipeline", axis((180, 70), 45, ANGLED_M), (180, 140), 150)


@scenario("S06-09", tz=["TZ-30", "TZ-37", "TZ-39"], title="силовой кабель поперёк прямой: специальный участок 4 м, стоимость × 1,15")
def power_cable_perpendicular() -> tuple[Scene, Expect]:
    return straight_crossing("power_cable", axis((120, 45), 0, LONG_M), (120, 110), SMALL_DN)


@scenario("S06-10", tz=["TZ-30", "TZ-37", "TZ-55"], title="два кабеля одним MultiLineString: два специальных участка и четыре технических узла")
def power_cable_multilinestring_two_crossings() -> tuple[Scene, Expect]:
    sc = Scene()
    dn = SMALL_DN
    single_oks(sc, (220, 120), dn)
    cables = MultiLineString([[(-300, 40), (700, 40)], [(-300, 80), (700, 80)]])
    sc.restriction("cable-1", "power_cable", cables)
    return sc, Expect(
        special={"power_cable": 2},
        technical_nodes=4,
        costs_by_formula=True,
        summary={"new_network_length": 120},
    )


def crossing_network(branch: list[tuple[float, float]], cp: tuple[float, float]) -> tuple[Scene, Expect]:
    # Ветка Ду 50 идёт от источника и пересекает прямую к ОКС. Врезка в неё тянет реконструкцию всей ветки
    # до источника, поэтому врезка в магистраль с переходом через ветку дешевле в разы.
    sc = Scene()
    dn = 100
    single_oks(sc, cp, dn)
    branch_dn = rules()["diameters"][0]["dn"]
    sc.pipe("hn-3", branch, dn=branch_dn, flow=flow_for(branch_dn), upstream="src")
    return sc, Expect(
        tie_ins=[{"existing_object_id": "hn-1", "existing_object_type": "heat_network"}],
        special={"heat_network": 1},
        technical_nodes=2,
        costs_by_formula=True,
        summary={"new_network_length": cp[1]},
    )


@scenario("S06-11", tz=["TZ-30", "TZ-38", "TZ-39"], title="существующая теплосеть поперёк трассы без врезки в неё: специальный участок 4 м, × 1,05")
def heat_network_crossing_without_tie_in() -> tuple[Scene, Expect]:
    return crossing_network([(0, 0), (0, 60), (600, 60)], (200, 120))


@scenario("S06-12", tz=["TZ-30", "TZ-38", "TZ-55"], title="существующая теплосеть под 45° к трассе без врезки: специальный участок 4 м вдоль трассы, × 1,05")
def heat_network_oblique_crossing() -> tuple[Scene, Expect]:
    return crossing_network([(0, 0), (0, 20), (180, 200)], (120, 200))


def nested_zones(outer: str, width: float, inner: str, center: tuple[float, float], dn: int) -> tuple[Scene, Expect]:
    sc = Scene()
    cp = (center[0], 2 * center[1])
    single_oks(sc, cp, dn)
    sc.restriction("outer-1", outer, strip(center, 0, LONG_M, width))
    sc.restriction("inner-1", inner, axis(center, 0, LONG_M))
    return sc, Expect(
        special={outer: 1, inner: 1},
        technical_nodes=2,
        costs_by_formula=True,
        summary={"new_network_length": cp[1]},
    )


@scenario("S06-13", tz=["TZ-30", "TZ-35", "TZ-37"], title="кабель вдоль оси трамвайных путей: одна зона внутри другой, участок получает наибольший k 1,75")
def tram_and_cable_take_max_k() -> tuple[Scene, Expect]:
    return nested_zones("tram_tracks", TRAM_M, "power_cable", (180, 60), SMALL_DN)


@scenario("S06-14", tz=["TZ-30", "TZ-34", "TZ-36"], title="газопровод под дорогой: одна зона внутри другой, участок получает наибольший k 1,60")
def road_and_gas_take_max_k() -> tuple[Scene, Expect]:
    return nested_zones("road", ROAD_M, "gas_pipeline", (230, 75), 150)


def near_pass(kind: str, obstacle, obstacle_id: str, cp: tuple[float, float]) -> tuple[Scene, Expect]:
    sc = Scene()
    single_oks(sc, cp, SMALL_DN)
    sc.restriction(obstacle_id, kind, obstacle)
    return sc, Expect(
        no_special=True,
        clearance=[obstacle_id],
        unconnected=[],
        max_new_length=detour((cp[0], 0), cp, obstacle, routing_offset(kind, SMALL_DN)),
    )


# Объект вдоль прямой ближе отступа начинается и кончается в 15–25 м от врезки и ОКС: обход у его торцов даёт
# повороты круче 3°, иначе трасса со сдвигом на метр нарушала бы правило изломов.
@scenario("S06-15", tz=["TZ-31", "TZ-34"], title="дорога вдоль прямой в четверти отступа: пересечь её под 45° нельзя, трасса идёт рядом на 1,5 м плюс полширины")
def road_alongside_near_pass_clearance() -> tuple[Scene, Expect]:
    edge = 200 + offset("road", SMALL_DN) / 4
    return near_pass("road", box(edge, 15, edge + ROAD_M, 95), "road-1", (200, 120))


@scenario("S06-16", tz=["TZ-31", "TZ-36"], title="газопровод вдоль прямой в четверти отступа: трасса идёт рядом на 2,0 м плюс полуширины трубы и газопровода")
def gas_alongside_near_pass_clearance() -> tuple[Scene, Expect]:
    x = 220 + offset("gas_pipeline", SMALL_DN) / 4
    return near_pass("gas_pipeline", LineString([(x, 15), (x, 100)]), "gas-1", (220, 130))


@scenario("S06-17", tz=["TZ-31", "TZ-37"], title="прямая проходит в полуотступе от конца кабеля: трасса отходит на 2,0 м плюс полуширины, без перехода")
def power_cable_end_near_pass_clearance() -> tuple[Scene, Expect]:
    gap = offset("power_cable", SMALL_DN) / 2
    return near_pass("power_cable", LineString([(160 + gap, 70), (400, 250)]), "cable-1", (160, 140))


@scenario("S06-18", tz=["TZ-45", "TZ-44"], title="реконструируемая часть трубы проходит под дорогой: стоимость по ставке реконструкции без k 1,60")
def reconstruction_under_road_without_k() -> tuple[Scene, Expect]:
    sc = Scene()
    old_dn, flow = rules()["diameters"][0]["dn"], flow_for(SMALL_DN)
    old_flow = flow_for(old_dn)
    sc.source("src", 0, 0)
    sc.pipe("hn-1", [(0, 0), (300, 0)], dn=old_dn, flow=old_flow, upstream="src")
    sc.chamber("hc-1", 300, 0, dn=old_dn, upstream="hn-1")
    sc.pipe("hn-2", [(300, 0), (600, 0)], dn=old_dn, flow=old_flow, upstream="hc-1")
    sc.oks("oks-1", cp=(250, 80), flow=flow)
    sc.restriction("road-1", "road", strip((100, 0), 90, 400, ROAD_M))
    required = diameter_for(rules(), old_flow + flow)
    length = 250
    return sc, Expect(
        recon=[{
            "existing_object_id": "hn-1",
            "required_diameter": required,
            "length": length,
            "cost": length * diameter_row(rules(), required)["recon_rub_m"],
        }],
        no_special=True,
    )


@scenario("S06-19", tz=["TZ-45", "TZ-55"], title="новая трасса переходит дорогу, реконструкция идёт под трамвайными путями: k только у нового участка")
def special_k_on_new_segment_not_on_reconstruction() -> tuple[Scene, Expect]:
    sc = Scene()
    old_dn, dn = SMALL_DN, 80
    old_flow, flow = flow_for(old_dn), flow_for(dn)
    sc.source("src", 0, 0)
    sc.pipe("hn-1", [(0, 0), (400, 0)], dn=old_dn, flow=old_flow, upstream="src")
    sc.chamber("hc-1", 400, 0, dn=old_dn, upstream="hn-1")
    sc.pipe("hn-2", [(400, 0), (800, 0)], dn=old_dn, flow=old_flow, upstream="hc-1")
    sc.oks("oks-1", cp=(300, 120), flow=flow)
    sc.restriction("tram-1", "tram_tracks", strip((150, 0), 90, 60, TRAM_M))
    road_width = 10.0
    sc.restriction("road-1", "road", strip((300, 55), 0, LONG_M, road_width))
    required = diameter_for(rules(), old_flow + flow)
    length = 300
    return sc, Expect(
        recon=[{
            "existing_object_id": "hn-1",
            "required_diameter": required,
            "length": length,
            "cost": length * diameter_row(rules(), required)["recon_rub_m"],
        }],
        special={"road": 1},
        costs_by_formula=True,
        summary={"new_network_length": 120},
    )
