from heatcheck.model import (
    diameter_for,
    diameter_row,
    next_diameter,
)
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
    trunk,
)
from heatscen.families.s02_tree import (
    busy_chamber,
    strip,
)
from heatscen.families.s03_flow_diameter import wide_trunk

# Координаты сцены округляются до 9 знаков в градусах, это до 0,15 мм длины: «ровно на пределе» ставится на 1 мм короче.
ROUNDING_M = 0.001


def limit(flow: float) -> float:
    return diameter_row(rules(), diameter_for(rules(), flow))["max_length_m"]


def straight_from_chamber(sc: Scene, flow: float, length: float) -> str:
    return sc.oks("oks-1", cp=(300, length), flow=flow, away=NORTH)


@scenario("S04-01", tz=["TZ-15"], title="прямая DN 50 ровно на предельной длине: один диаметр без узлов")
def dn50_chain_at_limit() -> tuple[Scene, Expect]:
    sc = trunk()
    straight_from_chamber(sc, 3, limit(3) - ROUNDING_M)
    return sc, Expect(
        tie_ins=[chamber_tie("hc-1", 150)],
        dn_used={diameter_for(rules(), 3)},
        technical_nodes=0,
        max_new_length=limit(3),
        unconnected=[],
    )


@scenario("S04-02", tz=["TZ-15", "TZ-24"], title="прямая DN 50 на 1 м длиннее предела: вставка DN 65 делит линию")
def dn50_chain_one_metre_over() -> tuple[Scene, Expect]:
    sc = trunk()
    straight_from_chamber(sc, 3, limit(3) + 1)
    dn = diameter_for(rules(), 3)
    return sc, Expect(
        tie_ins=[chamber_tie("hc-1", 150)],
        dn_used={dn, next_diameter(rules(), dn)},
        max_new_length=limit(3) + 1.05,
        unconnected=[],
    )


@scenario("S04-03", tz=["TZ-15"], title="две ветки DN 50 через камеру ветвления: вместе со стволом длиннее предела")
def chain_through_branch_chamber() -> tuple[Scene, Expect]:
    # Каждый путь от камеры врезки до ОКС около 165 м, но дерево из одного диаметра — не меньше 209 м.
    sc = busy_chamber()
    sc.oks("oks-a", cp=(260, 140), flow=1.5, away=NORTH)
    sc.oks("oks-b", cp=(340, 140), flow=1.5, away=NORTH)
    dn = diameter_for(rules(), 3)
    return sc, Expect(
        tie_ins=[chamber_tie("hc-1", 150)],
        dn_used={dn, next_diameter(rules(), dn)},
        unconnected=[],
    )


@scenario("S04-04", tz=["TZ-15", "TZ-24"], title="прямая DN 50 через дорогу длиннее предела: узлы специального участка отсчёт не прерывают")
def chain_through_special_nodes() -> tuple[Scene, Expect]:
    sc = trunk()
    sc.restriction("road-1", "road", strip(80, 90))
    straight_from_chamber(sc, 3, 190)
    dn = diameter_for(rules(), 3)
    return sc, Expect(
        dn_used={dn, next_diameter(rules(), dn)},
        special={"road": 1},
        unconnected=[],
    )


@scenario("S04-05", tz=["TZ-15"], title="ствол DN 65 и ветка DN 50 вместе длиннее предела DN 50: смена диаметра начинает отсчёт")
def diameter_change_restarts_count() -> tuple[Scene, Expect]:
    # Путь до дальнего ОКС около 300 м, но цепочка DN 50 после камеры ветвления — около 160 м, вставки не нужны.
    sc = busy_chamber()
    sc.oks("oks-near", cp=(290, 180), flow=3, away=NORTH)
    sc.oks("oks-far", cp=(430, 170), flow=3, away=NORTH)
    return sc, Expect(
        tie_ins=[chamber_tie("hc-1", 150)],
        dn_used={diameter_for(rules(), 3), diameter_for(rules(), 6)},
        technical_nodes=0,
        unconnected=[],
    )


@scenario("S04-06", tz=["TZ-15"], title="два дерева DN 50 по 100 м из одной камеры: общая цепочка 200 м длиннее предела")
def two_trees_share_chamber_chain() -> tuple[Scene, Expect]:
    sc = trunk()
    sc.oks("oks-n", cp=(300, 100), flow=3, away=NORTH)
    sc.oks("oks-s", cp=(300, -100), flow=3, away=SOUTH)
    dn = diameter_for(rules(), 3)
    return sc, Expect(
        tie_ins=[chamber_tie("hc-1", 150), chamber_tie("hc-1", 150)],  # по врезке на ветку, протокол 16.09.2026 п. 8
        new_chambers=0,
        dn_used={dn, next_diameter(rules(), dn)},
        unconnected=[],
    )


@scenario("S04-07", tz=["TZ-15"], title="прямая DN 65 ровно на предельной длине: один диаметр без узлов")
def dn65_chain_at_limit() -> tuple[Scene, Expect]:
    sc = trunk()
    straight_from_chamber(sc, 8, limit(8) - ROUNDING_M)
    return sc, Expect(
        dn_used={diameter_for(rules(), 8)},
        technical_nodes=0,
        max_new_length=limit(8),
        unconnected=[],
    )


@scenario("S04-08", tz=["TZ-15", "TZ-24"], title="прямая DN 65 на 1 м длиннее предела: вставка DN 80")
def dn65_chain_one_metre_over() -> tuple[Scene, Expect]:
    sc = trunk()
    straight_from_chamber(sc, 8, limit(8) + 1)
    dn = diameter_for(rules(), 8)
    return sc, Expect(
        dn_used={dn, next_diameter(rules(), dn)},
        max_new_length=limit(8) + 1.05,
        unconnected=[],
    )


@scenario("S04-09", tz=["TZ-15"], title="прямая DN 300 к ОКС 400 т/ч на 1 м длиннее предела 1718 м: вставка DN 400")
def dn300_chain_one_metre_over() -> tuple[Scene, Expect]:
    sc = wide_trunk()
    straight_from_chamber(sc, 400, limit(400) + 1)
    dn = diameter_for(rules(), 400)
    return sc, Expect(
        dn_used={dn, next_diameter(rules(), dn)},
        max_new_length=limit(400) + 1.05,
        unconnected=[],
    )
