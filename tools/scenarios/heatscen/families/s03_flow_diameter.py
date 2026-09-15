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
from heatscen.families.s01_tie_in import (
    NORTH,
    chamber_tie,
    trunk,
)

DIAMETERS = rules()["diameters"]
WIDEST = DIAMETERS[-1]["dn"]
OVER_CAPACITY_TPH = 0.001
CP = (300, 40)


def wide_trunk() -> Scene:
    """Магистраль наибольшего диаметра без текущего расхода: любой ОКС из таблицы подключается без реконструкции."""
    return trunk(dn=WIDEST, flow=0.0)


def single_oks(flow: float) -> tuple[Scene, Expect]:
    sc = wide_trunk()
    cp = sc.oks("oks-1", cp=CP, flow=flow, away=NORTH)
    return sc, Expect(
        tie_ins=[chamber_tie("hc-1", WIDEST)],
        segment_dn={cp: diameter_for(rules(), flow)},
        costs_by_formula=True,
        no_recon=True,
        unconnected=[],
    )


def register_rows() -> None:
    """По два сценария на строку таблицы 4.1: расход ровно по пропускной способности и на 0,001 т/ч больше."""
    number = 0
    for at_capacity in (True, False):
        for row in DIAMETERS[:len(DIAMETERS) if at_capacity else -1]:
            number += 1
            dn, capacity = row["dn"], row["capacity_tph"]
            flow = capacity if at_capacity else round(capacity + OVER_CAPACITY_TPH, 3)

            def build(flow: float = flow) -> tuple[Scene, Expect]:
                return single_oks(flow)

            shown = f"{flow:.3f}".rstrip("0").rstrip(".").replace(".", ",")
            if at_capacity:
                build.__name__ = f"flow_equals_capacity_dn{dn}"
                title = f"ОКС {shown} т/ч — ровно пропускная способность DN {dn}: участок DN {dn}"
            else:
                build.__name__ = f"flow_above_capacity_dn{dn}"
                title = f"ОКС {shown} т/ч — на 0,001 т/ч больше пропускной способности DN {dn}: участок следующего диаметра"
            scenario(f"S03-{number:02d}", tz=["TZ-10", "TZ-14", "TZ-26"], title=title)(build)


register_rows()


@scenario("S03-36", tz=["TZ-13", "TZ-14"], title="ствол к двум ОКС по 100 т/ч несёт 200 т/ч и шире веток")
def trunk_sums_two_flows() -> tuple[Scene, Expect]:
    # Два участка прямо из камеры длиннее общего ствола на 130 м DN 200, это дороже камеры ветвления.
    sc = wide_trunk()
    a = sc.oks("oks-a", cp=(290, 150), flow=100, away=NORTH)
    b = sc.oks("oks-b", cp=(310, 150), flow=100, away=NORTH)
    return sc, Expect(
        segment_dn={a: diameter_for(rules(), 100), b: diameter_for(rules(), 100)},
        dn_used={diameter_for(rules(), 100), diameter_for(rules(), 200)},
        unconnected=[],
    )


@scenario("S03-37", tz=["TZ-13", "TZ-14"], title="три ОКС по 90 т/ч: ветки, пары и общий ствол по сумме расходов")
def trunk_sums_three_flows() -> tuple[Scene, Expect]:
    # 90, 180 и 270 т/ч дают только два диаметра при любом порядке ветвления.
    sc = wide_trunk()
    cps = [sc.oks(f"oks-{i}", cp=(x, 150), flow=90, away=NORTH) for i, x in enumerate((270, 300, 330), start=1)]
    return sc, Expect(
        segment_dn={cp: diameter_for(rules(), 90) for cp in cps},
        dn_used={diameter_for(rules(), 90), diameter_for(rules(), 270)},
        unconnected=[],
    )


@scenario("S03-38", tz=["TZ-10"], title="heat_load 0,001 Гкал/ч при расходе 40 т/ч: диаметр и стоимость по расходу")
def heat_load_tiny() -> tuple[Scene, Expect]:
    sc = wide_trunk()
    cp = sc.oks("oks-1", cp=CP, flow=40, heat_load=0.001, away=NORTH)
    dn = diameter_for(rules(), 40)
    return sc, Expect(
        segment_dn={cp: dn},
        summary={"new_network_length": 40, "construction_cost": 40 * diameter_row(rules(), dn)["new_rub_m"]},
        unconnected=[],
    )


@scenario("S03-39", tz=["TZ-10"], title="heat_load 5000 Гкал/ч при расходе 40 т/ч: диаметр и стоимость по расходу")
def heat_load_huge() -> tuple[Scene, Expect]:
    sc = wide_trunk()
    cp = sc.oks("oks-1", cp=CP, flow=40, heat_load=5000, away=NORTH)
    dn = diameter_for(rules(), 40)
    return sc, Expect(
        segment_dn={cp: dn},
        summary={"new_network_length": 40, "construction_cost": 40 * diameter_row(rules(), dn)["new_rub_m"]},
        unconnected=[],
    )


def park_across_route() -> Polygon:
    return Polygon([(CP[0] - 20, 10), (CP[0] + 20, 10), (CP[0] + 20, 25), (CP[0] - 20, 25)])


@scenario("S03-40", tz=["TZ-27", "TZ-14"], title="обход парка трассой DN 50: отступ 1 м плюс половина ширины пары 0,40 м")
def pair_width_dn50_detour() -> tuple[Scene, Expect]:
    # Кратчайший обход угла парка с отступом 1,2 м — 65,7 м.
    sc = wide_trunk()
    sc.restriction("park-1", "park", park_across_route())
    cp = sc.oks("oks-1", cp=CP, flow=3, away=NORTH)
    return sc, Expect(
        segment_dn={cp: diameter_for(rules(), 3)},
        clearance=["park-1"],
        max_new_length=68,
        unconnected=[],
    )


@scenario("S03-41", tz=["TZ-27", "TZ-14"], title="обход парка трассой DN 1000: отступ 1 м плюс половина ширины пары 2,65 м")
def pair_width_dn1000_detour() -> tuple[Scene, Expect]:
    # Кратчайший обход угла парка с отступом 2,325 м — 68,9 м.
    sc = wide_trunk()
    sc.restriction("park-1", "park", park_across_route())
    cp = sc.oks("oks-1", cp=CP, flow=9000, away=NORTH)
    return sc, Expect(
        segment_dn={cp: diameter_for(rules(), 9000)},
        clearance=["park-1"],
        max_new_length=71,
        unconnected=[],
    )
