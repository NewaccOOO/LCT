from shapely.affinity import (
    rotate,
    translate,
)
from shapely.geometry import (
    LineString,
    MultiLineString,
    MultiPolygon,
    Point,
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
from heatscen.expect import LENGTH_TOL_M

NORTH = (0, 1)
# Длинные объекты: обход их концов на сотни метров дороже любого перехода.
LONG_M = 2000.0
TRACK_BED_M = 12.0


def offset(kind: str, dn: int) -> float:
    """Отступ от оси участка до объекта: clearance + width/2 + half_width_m."""
    params = rules()["restrictions"][kind]
    return params["clearance_m"] + diameter_row(rules(), dn)["width_m"] / 2 + params.get("half_width_m", 0)


def network(sc: Scene, ids: tuple[str, str, str], dn: int, flow: float) -> None:
    """Источник в начале координат, труба 600 м вдоль оси x и камера на её конце: ОКС над серединой трубы врезаются в трубу."""
    source_id, pipe_id, chamber_id = ids
    sc.source(source_id, 0, 0)
    sc.pipe(pipe_id, [(0, 0), (600, 0)], dn=dn, flow=flow, upstream=source_id)
    sc.chamber(chamber_id, 600, 0, dn=dn, upstream=pipe_id)


def pipe_tie(pipe_id: str) -> dict[str, object]:
    return {"existing_object_id": pipe_id, "existing_object_type": "heat_network"}


def chamber_tie(chamber_id: str) -> dict[str, object]:
    return {"existing_object_id": chamber_id, "existing_object_type": "heat_chamber"}


def strip(center: tuple[float, float], angle_deg: float, width: float) -> Polygon:
    """Полоса LONG_M × width с осью под angle_deg к оси x."""
    cx, cy = center
    return rotate(box(cx - LONG_M / 2, cy - width / 2, cx + LONG_M / 2, cy + width / 2), angle_deg, origin=center)


def axis(center: tuple[float, float], angle_deg: float) -> LineString:
    cx, cy = center
    return rotate(LineString([(cx - LONG_M / 2, cy), (cx + LONG_M / 2, cy)]), angle_deg, origin=center)


def unknown_type_warning(kind: str, count: int) -> str:
    clearance = f"{rules()['restrictions']['_fallback']['clearance_m']:.1f}".replace(".", ",")
    return f'ПРЕДУПРЕЖДЕНИЕ: restriction_type "{kind}" нет в справочнике, объектов: {count}, применено правило запрета с отступом {clearance} м'


@scenario("S14-01", tz=["TZ-11"], title="вестибюль метро на прямой к ОКС 200 т/ч: обход с отступом 10 м плюс половина ширины DN 250")
def metro_vestibule_detour() -> tuple[Scene, Expect]:
    sc = Scene()
    network(sc, ("ТЭЦ-21", "ТС-21/1", "ТК-21/2"), dn=400, flow=300)
    cp = sc.oks("ЖК Северный", cp=(300, 150), flow=200, away=NORTH)
    sc.restriction("вестибюль-М1", "metro", box(275, 50, 325, 90))
    return sc, Expect(
        tie_ins=[pipe_tie("ТС-21/1")],
        segment_dn={cp: diameter_for(rules(), 200)},
        clearance=["вестибюль-М1"],
        unconnected=[],
    )


@scenario("S14-02", tz=["TZ-11"], title="два входа метро одним MultiPolygon, проход между ними на 5 м шире отступа с каждой стороны: трасса прямая")
def metro_entrances_gap_straight() -> tuple[Scene, Expect]:
    sc = Scene()
    network(sc, ("source#7", "7/pipe", "7/chamber"), dn=200, flow=50)
    flow, cp_y = 40.0, 160.0
    sc.oks("7/oks", cp=(300, cp_y), flow=flow, away=NORTH)
    half_gap = offset("metro", diameter_for(rules(), flow)) + 5
    entrances = MultiPolygon([box(300 - half_gap - 30, 70, 300 - half_gap, 95), box(300 + half_gap, 70, 300 + half_gap + 30, 95)])
    sc.restriction("7/metro", "metro", entrances)
    return sc, Expect(
        tie_ins=[pipe_tie("7/pipe")],
        max_new_length=cp_y + LENGTH_TOL_M,
        clearance=["7/metro"],
        unconnected=[],
    )


@scenario("S14-03", tz=["TZ-11"], title="ось тоннеля метро линией поперёк прямой: обход конца тоннеля с отступом 10 м")
def metro_tunnel_axis_detour() -> tuple[Scene, Expect]:
    sc = Scene()
    network(sc, ("a1b2c3d4-0001", "a1b2c3d4-0002", "a1b2c3d4-0003"), dn=150, flow=30)
    cp = sc.oks("a1b2c3d4-0010", cp=(300, 140), flow=10, away=NORTH)
    sc.restriction("a1b2c3d4-0100", "metro", LineString([(220, 70), (380, 70)]))
    return sc, Expect(
        tie_ins=[pipe_tie("a1b2c3d4-0002")],
        segment_dn={cp: diameter_for(rules(), 10)},
        clearance=["a1b2c3d4-0100"],
        unconnected=[],
    )


@scenario("S14-04", tz=["TZ-11"], title="железнодорожные пути полигоном под 90°: один специальный участок, зона плюс 10 м, k 2,00")
def railway_strip_perpendicular() -> tuple[Scene, Expect]:
    sc = Scene()
    network(sc, ("Источник", "Магистраль", "Камера 1"), dn=200, flow=60)
    sc.oks("Школа", cp=(300, 150), flow=10, away=NORTH)
    sc.restriction("ЖД-пути", "railway", strip((300, 66), 0, TRACK_BED_M))
    return sc, Expect(
        special={"railway": 1},
        technical_nodes=2,
        costs_by_formula=True,
        unconnected=[],
    )


@scenario("S14-05", tz=["TZ-11"], title="две насыпи железной дороги одним MultiPolygon под 70° к прямой: два специальных участка с k 2,00")
def railway_two_beds_at_70deg() -> tuple[Scene, Expect]:
    # Оси насыпей в 38 м друг от друга: между зонами (полигон плюс 10 м) остаётся обычный участок, специальных участков два.
    sc = Scene()
    network(sc, ("501", "502", "503"), dn=200, flow=60)
    sc.oks("510", cp=(300, 200), flow=20, away=NORTH)
    bed = strip((300, 65), 0, TRACK_BED_M / 2)
    beds = MultiPolygon([rotate(bed, 20, origin=(300, 65)), rotate(translate(bed, 0, 38), 20, origin=(300, 65))])
    sc.restriction("520", "railway", beds)
    return sc, Expect(
        special={"railway": 2},
        technical_nodes=4,
        costs_by_formula=True,
        unconnected=[],
    )


@scenario("S14-06", tz=["TZ-11"], title="ось железнодорожного пути линией под 90°: зона ±10 м от пересечения, стоимость по k 2,00, трасса прямая")
def railway_axis_line_perpendicular() -> tuple[Scene, Expect]:
    sc = Scene()
    network(sc, ("rw6.src", "rw6.main", "rw6.tk"), dn=200, flow=60)
    cp_y = 170.0
    sc.oks("rw6.oks", cp=(300, cp_y), flow=30, away=NORTH)
    sc.restriction("rw6.track", "railway", axis((300, 80), 0))
    return sc, Expect(
        special={"railway": 1},
        technical_nodes=2,
        costs_by_formula=True,
        summary={"new_network_length": cp_y},
    )


@scenario("S14-07", tz=["TZ-11"], title="длинные пути под 50° к прямой: для дороги угол годился бы, для railway нужно 60°, трасса доворачивает")
def railway_50deg_turns_to_cross() -> tuple[Scene, Expect]:
    sc = Scene()
    network(sc, ("T7_SRC", "T7_NET", "T7_CH"), dn=200, flow=60)
    sc.oks("T7_OKS", cp=(300, 170), flow=8, away=NORTH)
    sc.restriction("T7_RAIL", "railway", strip((300, 80), 40, TRACK_BED_M))
    return sc, Expect(
        tie_ins=[pipe_tie("T7_NET")],
        special={"railway": 1},
        costs_by_formula=True,
        unconnected=[],
    )


@scenario("S14-08", tz=["TZ-11"], title="короткий тупик путей под 25° к прямой: обход его конца с отступом 4 м, без специального участка")
def railway_acute_short_siding_detour() -> tuple[Scene, Expect]:
    # Конец тупика выходит за прямую на 10 м: обход удлиняет трассу на несколько метров,
    # а переход добавил бы не меньше 32 м с k 2,00 и доворот до 60°.
    sc = Scene()
    network(sc, ("депо/src", "депо/теплосеть", "депо/ТК"), dn=200, flow=60)
    sc.oks("депо/цех", cp=(300, 160), flow=12, away=NORTH)
    sc.restriction("депо/тупик", "railway", rotate(box(230, 74, 305, 86), 65, origin=(305, 80)))
    return sc, Expect(
        tie_ins=[pipe_tie("депо/теплосеть")],
        no_special=True,
        clearance=["депо/тупик"],
        unconnected=[],
    )


@scenario("S14-09", tz=["TZ-11"], title="водопровод линией под 90°: специальный участок ±2 м, стоимость с k 1,10, трасса прямая")
def water_supply_perpendicular() -> tuple[Scene, Expect]:
    sc = Scene()
    network(sc, ("SOURCE", "PIPE_A", "CHAMBER_A"), dn=200, flow=60)
    cp_y = 130.0
    sc.oks("OKS_A", cp=(300, cp_y), flow=60, away=NORTH)
    sc.restriction("WATER_MAIN_900", "water_supply", axis((300, 55), 0))
    return sc, Expect(
        special={"water_supply": 1},
        technical_nodes=2,
        costs_by_formula=True,
        summary={"new_network_length": cp_y},
    )


@scenario("S14-10", tz=["TZ-11"], title="водопровод под 25° к прямой: угол не нормируется, трасса прямая со специальным участком")
def water_supply_sharp_angle_straight() -> tuple[Scene, Expect]:
    sc = Scene()
    network(sc, ("w10:src", "w10:net", "w10:tk"), dn=200, flow=60)
    cp_y = 140.0
    sc.oks("w10:oks", cp=(300, cp_y), flow=20, away=NORTH)
    sc.restriction("w10:water", "water_supply", axis((300, 70), 65))
    return sc, Expect(
        special={"water_supply": 1},
        max_new_length=cp_y + LENGTH_TOL_M,
        costs_by_formula=True,
        unconnected=[],
    )


@scenario("S14-11", tz=["TZ-11"], title="водопровод вдоль прямой в 1 м от неё: трасса отходит на 1,5 м плюс половина ширины, без специального участка")
def water_supply_parallel_nearby() -> tuple[Scene, Expect]:
    sc = Scene()
    network(sc, ("w11-src", "w11-net", "w11-tk"), dn=200, flow=60)
    sc.oks("w11-oks", cp=(300, 150), flow=30, away=NORTH)
    sc.restriction("w11-water", "water_supply", LineString([(301, 20), (301, 110)]))
    return sc, Expect(
        tie_ins=[pipe_tie("w11-net")],
        no_special=True,
        clearance=["w11-water"],
        unconnected=[],
    )


@scenario("S14-12", tz=["TZ-11"], title="две линии канализации одним MultiLineString: два специальных участка с k 1,10, трасса прямая")
def sewer_two_lines_multilinestring() -> tuple[Scene, Expect]:
    sc = Scene()
    network(sc, ("k12-src", "k12-hn", "k12-hc"), dn=100, flow=10)
    cp_y = 150.0
    sc.oks("k12-oks", cp=(300, cp_y), flow=5, away=NORTH)
    sc.restriction("k12-sewer", "sewer", MultiLineString([axis((300, 45), 0), axis((300, 95), 0)]))
    return sc, Expect(
        special={"sewer": 2},
        technical_nodes=4,
        costs_by_formula=True,
        summary={"new_network_length": cp_y},
    )


@scenario("S14-13", tz=["TZ-11"], title="ломаная канализация поперёк прямой к ОКС 400 т/ч: один специальный участок DN 300")
def sewer_bent_line_large_dn() -> tuple[Scene, Expect]:
    sc = Scene()
    network(sc, ("Котельная №3", "Тепломагистраль №3", "ТК-3"), dn=500, flow=800)
    flow, cp_y = 400.0, 220.0
    cp = sc.oks("Бизнес-центр", cp=(300, cp_y), flow=flow, away=NORTH)
    sc.restriction("Канализация К1", "sewer", LineString([(-700, 120), (200, 120), (1300, 60)]))
    return sc, Expect(
        special={"sewer": 1},
        segment_dn={cp: diameter_for(rules(), flow)},
        max_new_length=cp_y + LENGTH_TOL_M,
        costs_by_formula=True,
        unconnected=[],
    )


@scenario("S14-14", tz=["TZ-11"], title="канализация вдоль прямой к ОКС 150 т/ч в 0,6 м от неё: отступ 1,0 м плюс половина ширины DN 200")
def sewer_parallel_nearby_large_dn() -> tuple[Scene, Expect]:
    sc = Scene()
    network(sc, ("k14/src", "k14/net", "k14/tk"), dn=300, flow=200)
    flow = 150.0
    cp = sc.oks("k14/oks", cp=(300, 170), flow=flow, away=NORTH)
    sc.restriction("k14/sewer", "sewer", LineString([(299.4, 40), (299.4, 130)]))
    return sc, Expect(
        segment_dn={cp: diameter_for(rules(), flow)},
        no_special=True,
        clearance=["k14/sewer"],
        unconnected=[],
    )


@scenario("S14-15", tz=["TZ-11"], title="опора ВЛ точкой на прямой к ОКС: обход с отступом 2 м плюс половина ширины")
def power_line_support_on_path() -> tuple[Scene, Expect]:
    sc = Scene()
    network(sc, ("opora-src", "opora-net", "opora-tk"), dn=150, flow=40)
    sc.oks("opora-oks", cp=(300, 130), flow=15, away=NORTH)
    sc.restriction("ВЛ-10 оп.7", "power_line_support", Point(300, 55))
    return sc, Expect(
        tie_ins=[pipe_tie("opora-net")],
        clearance=["ВЛ-10 оп.7"],
        unconnected=[],
    )


@scenario("S14-16", tz=["TZ-11"], title="ряд опор ВЛ поперёк трассы DN 200, прямая проходит у одной опоры ближе отступа: трасса отходит от неё")
def power_line_supports_row() -> tuple[Scene, Expect]:
    sc = Scene()
    network(sc, ("ЦТП-14", "ЦТП-14/ввод", "ЦТП-14/ТК"), dn=300, flow=150)
    flow = 100.0
    dn = diameter_for(rules(), flow)
    cp = sc.oks("ЦТП-14/корпус", cp=(300, 170), flow=flow, away=NORTH)
    near = 300 + offset("power_line_support", dn) / 2
    supports = [f"опора {n + 1}" for n in range(9)]
    for n, support_id in enumerate(supports):
        sc.restriction(support_id, "power_line_support", Point(near + 45 * (n - 4), 75))
    return sc, Expect(
        tie_ins=[pipe_tie("ЦТП-14/ввод")],
        segment_dn={cp: dn},
        clearance=supports,
        unconnected=[],
    )


@scenario("S14-17", tz=["TZ-11", "TZ-92"], title="unknown_type: две площадки неизвестного типа, проход на 1 м шире отступа 1,0 м с каждой стороны: предупреждение, код 0, трасса прямая")
def unknown_type_gap_straight() -> tuple[Scene, Expect]:
    # Проход уже отступов 2 м и больше: прямая трасса значит, что неизвестный тип не получил правило строже _fallback.
    sc = Scene()
    network(sc, ("u17/src", "u17/net", "u17/tk"), dn=200, flow=80)
    flow, cp_y = 25.0, 140.0
    sc.oks("u17/oks", cp=(300, cp_y), flow=flow, away=NORTH)
    half_gap = offset("_fallback", diameter_for(rules(), flow)) + 1
    sc.restriction("u17/ground-west", "sports_ground", box(300 - half_gap - 40, 50, 300 - half_gap, 80))
    sc.restriction("u17/ground-east", "sports_ground", box(300 + half_gap, 50, 300 + half_gap + 40, 80))
    return sc, Expect(
        exit_code=0,
        stderr_contains=[unknown_type_warning("sports_ground", 2)],
        max_new_length=cp_y + LENGTH_TOL_M,
        clearance=["u17/ground-west", "u17/ground-east"],
        unconnected=[],
    )


@scenario("S14-18", tz=["TZ-11", "TZ-92"], title="unknown_type: два неизвестных типа, точки и линия — строка на каждый тип с числом объектов, обход с отступом 1 м")
def unknown_type_two_types_counted() -> tuple[Scene, Expect]:
    sc = Scene()
    network(sc, ("u18-src", "u18-net", "u18-tk"), dn=100, flow=10)
    sc.oks("u18-oks", cp=(300, 160), flow=3, away=NORTH)
    sc.restriction("u18-support-a", "bridge_support", Point(300, 40))
    sc.restriction("u18-support-b", "bridge_support", Point(520, 260))
    sc.restriction("u18-fence", "fence", LineString([(230, 95), (302, 95)]))
    return sc, Expect(
        exit_code=0,
        stderr_contains=[unknown_type_warning("bridge_support", 2), unknown_type_warning("fence", 1)],
        tie_ins=[pipe_tie("u18-net")],
        clearance=["u18-support-a", "u18-support-b", "u18-fence"],
        unconnected=[],
    )


@scenario("S14-19", tz=["TZ-11", "TZ-88"], title="набор из двух типов, железная дорога и опора: переход путей и обход опоры")
def two_types_railway_and_support() -> tuple[Scene, Expect]:
    sc = Scene()
    network(sc, ("set19-source", "set19-line", "set19-chamber"), dn=250, flow=150)
    sc.oks("set19-oks", cp=(300, 190), flow=40, away=NORTH)
    sc.restriction("set19-rail", "railway", strip((300, 66), 0, TRACK_BED_M))
    sc.restriction("set19-support", "power_line_support", Point(300, 125))
    return sc, Expect(
        special={"railway": 1},
        clearance=["set19-support"],
        costs_by_formula=True,
        unconnected=[],
    )


@scenario("S14-20", tz=["TZ-11", "TZ-88"], title="набор из двух типов, канализация и метро: переход канализации и обход вестибюля")
def two_types_sewer_and_metro() -> tuple[Scene, Expect]:
    sc = Scene()
    network(sc, ("set20:source", "set20:line", "set20:chamber"), dn=250, flow=150)
    sc.oks("set20:oks", cp=(300, 190), flow=50, away=NORTH)
    sc.restriction("set20:sewer", "sewer", axis((300, 40), 0))
    sc.restriction("set20:metro", "metro", box(270, 90, 330, 120))
    return sc, Expect(
        special={"sewer": 1},
        clearance=["set20:metro"],
        costs_by_formula=True,
        unconnected=[],
    )


@scenario("S14-21", tz=["TZ-11", "TZ-88"], title="набор без ограничений, ID с пробелами и кириллицей, ломаная труба: врезка в трубу, все участки base")
def no_restrictions_bent_pipe_text_ids() -> tuple[Scene, Expect]:
    sc = Scene()
    sc.source("Котельная «Южная»", 0, 0)
    sc.pipe("участок 1-2", [(0, 0), (200, 150), (500, 150)], dn=150, flow=40, upstream="Котельная «Южная»")
    sc.chamber("камера №2", 500, 150, dn=150, upstream="участок 1-2")
    cp_y = 230.0
    sc.oks("Детский сад 5", cp=(350, cp_y), flow=12, away=NORTH)
    return sc, Expect(
        tie_ins=[pipe_tie("участок 1-2")],
        new_chambers=1,
        no_special=True,
        max_new_length=cp_y - 150 + LENGTH_TOL_M,
        unconnected=[],
    )


@scenario("S14-22", tz=["TZ-11", "TZ-88"], title="набор без ограничений с числовыми ID: два ОКС у двух камер, две врезки в камеры, все участки base")
def no_restrictions_numeric_ids() -> tuple[Scene, Expect]:
    sc = Scene()
    sc.source("1", 0, 0)
    sc.pipe("100", [(0, 0), (400, 0)], dn=200, flow=60, upstream="1")
    sc.chamber("200", 400, 0, dn=200, upstream="100")
    sc.pipe("101", [(400, 0), (1000, 0)], dn=150, flow=30, upstream="200")
    sc.chamber("201", 1000, 0, dn=150, upstream="101")
    sc.oks("300", cp=(405, 60), flow=8, away=NORTH)
    sc.oks("301", cp=(1000, -70), flow=6, away=(0, -1))
    return sc, Expect(
        tie_ins=[chamber_tie("200"), chamber_tie("201")],
        new_chambers=0,
        no_special=True,
        costs_by_formula=True,
        unconnected=[],
    )


@scenario("S14-23", tz=["TZ-88"], title="набор без ограничений, ID входа совпадают с шаблоном выходных ID сервиса: выход без совпадений ID")
def no_restrictions_ids_like_output() -> tuple[Scene, Expect]:
    sc = Scene()
    sc.source("v1_node_1", 0, 0)
    sc.pipe("v1_seg_1", [(0, 0), (500, 0)], dn=150, flow=40, upstream="v1_node_1")
    sc.chamber("v2_ch_1", 500, 0, dn=150, upstream="v1_seg_1")
    sc.oks("v1_tie_1", cp=(250, 90), flow=10, away=NORTH)
    return sc, Expect(
        tie_ins=[pipe_tie("v1_seg_1")],
        new_chambers=1,
        no_special=True,
        unconnected=[],
    )
