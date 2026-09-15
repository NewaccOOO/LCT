import math

from shapely.geometry import Polygon

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


def chain(sc: Scene, chambers: int) -> None:
    # Участки 8 и 20 м: любая точка сети не дальше 10 м от камеры, поэтому кандидаты врезки — только камеры (TZ-50).
    sc.source("src", 0, 0)
    sc.pipe("hn-1", [(0, 0), (8, 0)], dn=150, flow=40.0, upstream="src")
    sc.chamber("hc-1", 8, 0, dn=150, upstream="hn-1")
    for n in range(2, chambers + 1):
        x0, x1 = 8 + 20 * (n - 2), 8 + 20 * (n - 1)
        sc.pipe(f"hn-{n}", [(x0, 0), (x1, 0)], dn=150, flow=20.0, upstream=f"hc-{n - 1}")
        sc.chamber(f"hc-{n}", x1, 0, dn=150, upstream=f"hn-{n}")


def two_branches(sc: Scene, dn: int = 150, flow: float = 20.0) -> None:
    # Две ветки по 8 м от источника на восток и запад: кандидатов врезки ровно два, камеры hc-e и hc-w.
    sc.source("src", 0, 0)
    sc.pipe("hn-e", [(0, 0), (8, 0)], dn=dn, flow=flow, upstream="src")
    sc.chamber("hc-e", 8, 0, dn=dn, upstream="hn-e")
    sc.pipe("hn-w", [(0, 0), (-8, 0)], dn=dn, flow=flow, upstream="src")
    sc.chamber("hc-w", -8, 0, dn=dn, upstream="hn-w")


def long_branches(sc: Scene) -> None:
    sc.source("src", 0, 0)
    sc.pipe("hn-1", [(0, 0), (100, 0)], dn=150, flow=40.0, upstream="src")
    sc.chamber("hc-1", 100, 0, dn=150, upstream="hn-1")
    sc.pipe("hn-2", [(0, 0), (-100, 0)], dn=150, flow=40.0, upstream="src")
    sc.chamber("hc-2", -100, 0, dn=150, upstream="hn-2")


def chamber_tie(chamber_id: str) -> dict[str, str]:
    return {"existing_object_id": chamber_id, "existing_object_type": "heat_chamber"}


def penalty(*flows: float) -> float:
    p = rules()["penalty"]
    return sum(p["fixed"] + p["per_tph"] * flow for flow in flows)


def straight_cost(length: float, flow: float) -> float:
    """Стоимость варианта из одной врезки в существующую камеру и прямого участка по расходу ОКС, длина уже округлена."""
    return rules()["tie_in_cost"] + length * diameter_row(rules(), diameter_for(rules(), flow))["new_rub_m"]


@scenario("S10-01", tz=["TZ-58", "TZ-60"], title="две камеры-кандидата: два варианта с разными врезками, ближняя камера первая")
def two_chambers_near_first() -> tuple[Scene, Expect]:
    sc = Scene()
    chain(sc, 2)
    sc.oks("oks-1", cp=(-30, 40), flow=5.0)
    return sc, Expect(tie_ins=[chamber_tie("hc-1")], variants=(2, 2), distinct_tie_in_sets=True)


@scenario("S10-02", tz=["TZ-58", "TZ-60"], title="ветки на восток и запад, ОКС на 300 т/ч: вариант через восточную камеру первый")
def two_branches_large_flow() -> tuple[Scene, Expect]:
    sc = Scene()
    two_branches(sc, dn=400, flow=100.0)
    cp = sc.oks("oks-1", cp=(60, 40), flow=300.0)
    return sc, Expect(
        tie_ins=[chamber_tie("hc-e")],
        segment_dn={cp: diameter_for(rules(), 300.0)},
        no_recon=True,
        variants=(2, 2),
        distinct_tie_in_sets=True,
    )


@scenario("S10-03", tz=["TZ-58"], title="два соседних ОКС далеко от сети: общее дерево с одной врезкой и новой камерой")
def shared_tree_for_close_oks() -> tuple[Scene, Expect]:
    # Две отдельные врезки стоят лишние 5 млн, ветвление прямо в камере врезки — две трубы по 110 м; ствол к камере у ОКС дешевле обоих.
    sc = Scene()
    long_branches(sc)
    cp_a = sc.oks("oks-a", cp=(140, 100), flow=5.0, away=(0, 1))
    cp_b = sc.oks("oks-b", cp=(160, 100), flow=5.0, away=(0, 1))
    return sc, Expect(
        tie_ins=[chamber_tie("hc-1")],
        new_chambers=1,
        dn_used={diameter_for(rules(), 5.0), diameter_for(rules(), 10.0)},
        segment_dn={cp_a: diameter_for(rules(), 5.0), cp_b: diameter_for(rules(), 5.0)},
    )


@scenario("S10-04", tz=["TZ-58"], title="два ОКС у разных камер в 200 м друг от друга: раздельные деревья с двумя врезками")
def separate_trees_for_far_oks() -> tuple[Scene, Expect]:
    sc = Scene()
    long_branches(sc)
    cp_a = sc.oks("oks-a", cp=(100, 60), flow=5.0, away=(0, 1))
    cp_b = sc.oks("oks-b", cp=(-100, 60), flow=5.0, away=(0, 1))
    return sc, Expect(
        tie_ins=[chamber_tie("hc-1"), chamber_tie("hc-2")],
        new_chambers=0,
        dn_used={diameter_for(rules(), 5.0)},
        segment_dn={cp_a: diameter_for(rules(), 5.0), cp_b: diameter_for(rules(), 5.0)},
    )


@scenario("S10-05", tz=["TZ-60", "TZ-63"], title="ОКС на равном расстоянии от двух камер: два варианта равной стоимости")
def equal_cost_variants() -> tuple[Scene, Expect]:
    sc = Scene()
    two_branches(sc)
    sc.oks("oks-1", cp=(0, 60), flow=5.0, away=(0, 1))
    length = round(math.hypot(8, 60), 2)
    return sc, Expect(
        variants=(2, 2),
        distinct_tie_in_sets=True,
        summary={"new_network_length": length, "calculated_cost": straight_cost(length, 5.0)},
    )


@scenario("S10-06", tz=["TZ-57", "TZ-58"], title="ОКС во дворе здания не подключается, у второго ОКС два варианта врезки")
def variants_with_oks_in_courtyard() -> tuple[Scene, Expect]:
    sc = Scene()
    chain(sc, 2)
    sc.oks("oks-1", cp=(-30, 40), flow=5.0)
    sc.add(Polygon([(40, 60), (120, 60), (120, 140), (40, 140)], [[(60, 80), (100, 80), (100, 120), (60, 120)]]), id="bld-1", object_type="oks_existing")
    sc.oks("oks-2", cp=(80, 95), flow=7.0, polygon=[[(75, 95), (85, 95), (85, 105), (75, 105)]])
    return sc, Expect(
        tie_ins=[chamber_tie("hc-1")],
        unconnected=["oks-2"],
        penalty=penalty(7.0),
        variants=(2, 2),
        distinct_tie_in_sets=True,
    )


@scenario("S10-07", tz=["TZ-58", "TZ-60"], title="ближняя камера требует реконструкции: первым идёт вариант через дальнюю")
def variant_without_reconstruction_first() -> tuple[Scene, Expect]:
    # Через hc-e на 9,6 м короче, но участок DN 50 и камера DN 50 реконструируются: плюс 3,9 млн, вариант через hc-w дешевле на четверть.
    sc = Scene()
    sc.source("src", 0, 0)
    sc.pipe("hn-e", [(0, 0), (8, 0)], dn=50, flow=3.0, upstream="src")
    sc.chamber("hc-e", 8, 0, dn=50, upstream="hn-e")
    sc.pipe("hn-w", [(0, 0), (-8, 0)], dn=150, flow=10.0, upstream="src")
    sc.chamber("hc-w", -8, 0, dn=150, upstream="hn-w")
    sc.oks("oks-1", cp=(30, 40), flow=5.0)
    return sc, Expect(
        tie_ins=[chamber_tie("hc-w")],
        no_recon=True,
        chamber_recon=[],
        variants=(2, 2),
        distinct_tie_in_sets=True,
    )


@scenario("S10-08", tz=["TZ-57", "TZ-63"], title="ОКС на поляне внутри парка не подключается, варианты подключения второго ОКС сохраняются")
def variants_with_oks_inside_park() -> tuple[Scene, Expect]:
    sc = Scene()
    two_branches(sc)
    sc.oks("oks-1", cp=(-60, 30), flow=30.0)
    sc.restriction("park-1", "park", Polygon([(30, 30), (150, 30), (150, 150), (30, 150)], [[(70, 70), (110, 70), (110, 110), (70, 110)]]))
    sc.oks("oks-2", cp=(90, 85), flow=12.5, polygon=[[(85, 85), (95, 85), (95, 95), (85, 95)]])
    return sc, Expect(
        tie_ins=[chamber_tie("hc-w")],
        unconnected=["oks-2"],
        penalty=penalty(12.5),
        variants=(2, 2),
        distinct_tie_in_sets=True,
    )


@scenario("S10-09", tz=["TZ-58"], title="четыре камеры-кандидата: не больше трёх вариантов, врезки попарно различны")
def at_most_three_variants() -> tuple[Scene, Expect]:
    sc = Scene()
    chain(sc, 4)
    sc.oks("oks-1", cp=(-30, 40), flow=5.0)
    return sc, Expect(tie_ins=[chamber_tie("hc-1")], variants=(2, 3), distinct_tie_in_sets=True)
