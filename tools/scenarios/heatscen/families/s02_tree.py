from shapely.geometry import (
    LineString,
    Polygon,
)

from heatcheck.model import diameter_for
from heatscen import (
    Expect,
    Scene,
    rules,
    scenario,
)
from heatscen.families.s01_tie_in import (
    NORTH,
    SOUTH,
    chamber_tie,
    pipe_tie,
    trunk,
)

WEST = (-1, 0)
EAST = (1, 0)


def strip(y_from: float, y_to: float) -> Polygon:
    """Полоса поперёк всей сцены: обойти её дороже, чем пересечь."""
    return Polygon([(-500, y_from), (1500, y_from), (1500, y_to), (-500, y_to)])


def busy_chamber() -> Scene:
    """Магистраль с третьим участком у hc-1: в камере остаётся место для одного нового участка."""
    sc = trunk()
    sc.pipe("hn-3", [(300, 0), (300, -200)], dn=150, flow=10, upstream="hc-1")
    return sc


@scenario("S02-01", tz=["TZ-16", "TZ-18", "TZ-20"], title="два ОКС от камеры с одним свободным местом: ветвление в новой камере, узлов нет")
def branch_in_new_chamber() -> tuple[Scene, Expect]:
    sc = busy_chamber()
    a = sc.oks("oks-a", cp=(290, 150), flow=5, away=NORTH)
    b = sc.oks("oks-b", cp=(310, 150), flow=10, away=NORTH)
    dn = {flow: diameter_for(rules(), flow) for flow in (5, 10, 15)}
    return sc, Expect(
        tie_ins=[chamber_tie("hc-1", 150)],
        new_chambers=1,
        segment_dn={a: dn[5], b: dn[10]},
        dn_used=set(dn.values()),
        technical_nodes=0,
        unconnected=[],
    )


@scenario("S02-02", tz=["TZ-16", "TZ-20"], title="три ОКС вокруг одной точки: одно дерево одного диаметра без узлов")
def three_oks_hub() -> tuple[Scene, Expect]:
    sc = trunk()
    sc.oks("oks-w", cp=(585, 70), flow=1, away=WEST)
    sc.oks("oks-e", cp=(615, 70), flow=1, away=EAST)
    sc.oks("oks-n", cp=(600, 95), flow=1, away=NORTH)
    return sc, Expect(
        tie_ins=[pipe_tie("hn-2", 150)],
        dn_used={diameter_for(rules(), 3)},
        technical_nodes=0,
        unconnected=[],
    )


@scenario("S02-03", tz=["TZ-16", "TZ-17", "TZ-20"], title="четыре ОКС у тупиковой камеры: один луч из неё и две камеры ветвления")
def four_oks_at_dead_end_chamber() -> tuple[Scene, Expect]:
    # В hc-2 уже один участок, новых помещается три, но каждая ветка из камеры — своя врезка по 5 млн (протокол
    # 16.09.2026 п. 8). Дешевле всего один луч из hc-2 (5 млн) и две камеры ветвления по 3 млн на все четыре ОКС:
    # ветка предпочитает камеру ветвления второму лучу из камеры врезки, пока крюк короче цены врезки.
    sc = trunk()
    sc.oks("oks-n", cp=(900, 40), flow=5, away=NORTH)
    sc.oks("oks-s", cp=(900, -40), flow=5, away=SOUTH)
    sc.oks("oks-e", cp=(940, 0), flow=5, away=EAST)
    sc.oks("oks-nw", cp=(870, 40), flow=5, away=NORTH)
    return sc, Expect(
        tie_ins=[chamber_tie("hc-2", 150)],
        new_chambers=2,
        technical_nodes=0,
        unconnected=[],
    )


@scenario("S02-04", tz=["TZ-18", "TZ-24", "TZ-70"], title="переход дороги: технические узлы на обеих границах специального участка")
def road_crossing_nodes() -> tuple[Scene, Expect]:
    sc = trunk()
    sc.restriction("road-1", "road", strip(20, 30))
    sc.oks("oks-1", cp=(600, 60), flow=5, away=NORTH)
    return sc, Expect(
        tie_ins=[pipe_tie("hn-2", 150)],
        technical_nodes=2,
        special={"road": 1},
        costs_by_formula=True,
        unconnected=[],
    )


@scenario("S02-05", tz=["TZ-18", "TZ-24", "TZ-70"], title="две дороги подряд: четыре технических узла и два специальных участка")
def two_roads_four_nodes() -> tuple[Scene, Expect]:
    sc = trunk()
    sc.restriction("road-1", "road", strip(20, 30))
    sc.restriction("road-2", "road", strip(50, 60))
    sc.oks("oks-1", cp=(600, 90), flow=5, away=NORTH)
    return sc, Expect(
        technical_nodes=4,
        special={"road": 2},
        costs_by_formula=True,
        unconnected=[],
    )


@scenario("S02-06", tz=["TZ-18", "TZ-24", "TZ-70"], title="пересечение газопровода: узлы в 2 м по обе стороны")
def gas_crossing_nodes() -> tuple[Scene, Expect]:
    sc = trunk()
    sc.restriction("gas-1", "gas_pipeline", LineString([(-500, 30), (1500, 30)]))
    sc.oks("oks-1", cp=(600, 60), flow=5, away=NORTH)
    return sc, Expect(
        tie_ins=[pipe_tie("hn-2", 150)],
        technical_nodes=2,
        special={"gas_pipeline": 1},
        costs_by_formula=True,
        unconnected=[],
    )


@scenario("S02-07", tz=["TZ-16", "TZ-22", "TZ-23"], title="точки подключения в 1,5 м друг от друга: камера ветвления не ближе 1 м к ним")
def close_connection_points() -> tuple[Scene, Expect]:
    # Два участка прямо от камеры врезки длиннее общего ствола на 150 м, это дороже камеры ветвления.
    sc = trunk()
    sc.oks("oks-a", cp=(600, 150), flow=5, polygon=[[(578, 151), (598, 151), (598, 171), (578, 171)]])
    sc.oks("oks-b", cp=(601.5, 150), flow=5, polygon=[[(603.5, 151), (623.5, 151), (623.5, 171), (603.5, 171)]])
    return sc, Expect(
        tie_ins=[pipe_tie("hn-2", 150)],
        new_chambers=2,
        technical_nodes=0,
        unconnected=[],
    )


@scenario("S02-08", tz=["TZ-22", "TZ-18"], title="обход парка: кратчайший обход без лишних изломов и без узлов")
def park_detour_without_kinks() -> tuple[Scene, Expect]:
    # Кратчайший обход углов парка с отступом 1,2 м для DN 65 — 79,1 м.
    sc = trunk()
    sc.restriction("park-1", "park", Polygon([(580, 20), (620, 20), (620, 40), (580, 40)]))
    sc.oks("oks-1", cp=(600, 60), flow=5, away=NORTH)
    return sc, Expect(
        tie_ins=[pipe_tie("hn-2", 150)],
        technical_nodes=0,
        max_new_length=81,
        clearance=["park-1"],
        unconnected=[],
    )


@scenario("S02-09", tz=["TZ-22"], title="обход двух парков вразбежку: трасса без зигзагов")
def two_parks_slalom() -> tuple[Scene, Expect]:
    # Кратчайшая трасса между парками с отступом 1,2 м — 92,1 м, лишний зигзаг удлиняет её сильнее, чем на 2 м.
    sc = trunk()
    sc.restriction("park-1", "park", Polygon([(560, 20), (605, 20), (605, 35), (560, 35)]))
    sc.restriction("park-2", "park", Polygon([(595, 50), (640, 50), (640, 65), (595, 65)]))
    sc.oks("oks-1", cp=(600, 85), flow=5, away=NORTH)
    return sc, Expect(
        tie_ins=[pipe_tie("hn-2", 150)],
        technical_nodes=0,
        max_new_length=94,
        clearance=["park-1", "park-2"],
        unconnected=[],
    )


@scenario("S02-10", tz=["TZ-20", "TZ-23"], title="ОКС на одной прямой с врезкой: ствол к дальнему не касается ближнего")
def collinear_connection_points() -> tuple[Scene, Expect]:
    sc = trunk()
    sc.oks("oks-near", cp=(600, 30), flow=1, away=WEST)
    sc.oks("oks-far", cp=(600, 60), flow=1, away=NORTH)
    return sc, Expect(
        tie_ins=[pipe_tie("hn-2", 150)],
        dn_used={diameter_for(rules(), 2)},
        max_new_length=95,
        unconnected=[],
    )


@scenario("S02-11", tz=["TZ-16", "TZ-23"], title="три ОКС в ряд вдоль трубы: ветки не пересекаются и не накладываются")
def row_of_three() -> tuple[Scene, Expect]:
    # Дерево Штейнера с камерой у средней точки — 140,2 м.
    sc = trunk()
    sc.oks("oks-w", cp=(560, 60), flow=1, away=NORTH)
    sc.oks("oks-m", cp=(600, 60), flow=1, away=NORTH)
    sc.oks("oks-e", cp=(640, 60), flow=1, away=NORTH)
    return sc, Expect(
        tie_ins=[pipe_tie("hn-2", 150)],
        dn_used={diameter_for(rules(), 3)},
        technical_nodes=0,
        max_new_length=145,
        unconnected=[],
    )


@scenario("S02-12", tz=["TZ-18", "TZ-24"], title="ствол через дорогу к двум ОКС: узлы только на границах дороги, смена диаметра в камере")
def trunk_across_road_then_branch() -> tuple[Scene, Expect]:
    # Два участка прямо из камеры врезки дважды переходят дорогу и длиннее ствола на 130 м: камера ветвления дешевле.
    sc = trunk()
    sc.restriction("road-1", "road", strip(40, 50))
    a = sc.oks("oks-a", cp=(590, 150), flow=5, away=NORTH)
    b = sc.oks("oks-b", cp=(610, 150), flow=10, away=NORTH)
    dn = {flow: diameter_for(rules(), flow) for flow in (5, 10, 15)}
    return sc, Expect(
        tie_ins=[pipe_tie("hn-2", 150)],
        new_chambers=2,
        segment_dn={a: dn[5], b: dn[10]},
        dn_used=set(dn.values()),
        technical_nodes=2,
        special={"road": 1},
        unconnected=[],
    )


@scenario("S02-13", tz=["TZ-22", "TZ-24"], title="длинная трасса поперёк дороги: переход прямо, без ступеньки у границы специального участка")
def long_road_crossing_straight() -> tuple[Scene, Expect]:
    # Прямая от камеры до ОКС пересекает дорогу под 90°, сдвиг перехода вбок только удлиняет трассу и добавляет повороты.
    sc = trunk()
    sc.restriction("road-1", "road", strip(80, 90))
    sc.oks("oks-1", cp=(300, 190), flow=8, away=NORTH)
    return sc, Expect(
        tie_ins=[chamber_tie("hc-1", 150)],
        technical_nodes=2,
        special={"road": 1},
        max_new_length=190.05,
        unconnected=[],
    )
