import math

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
from heatscen.families.s10_variants import (
    chain,
    chamber_tie,
    penalty,
    straight_cost,
    two_branches,
)
from heatscen.families.s11_unconnected import (
    island,
    oks_at,
)


def score(cost: float, length: float) -> float:
    s = rules()["score"]
    return round(s["w_cost"] * cost / s["cost_base"] + s["w_length"] * length / s["length_base_m"], 3)


def summary_of_west_oks() -> dict[str, float]:
    """Сводка варианта 1 для ОКС на 30 т/ч в точке (-50, 30) при сети two_branches: врезка в hc-w и прямой участок."""
    length = round(math.hypot(42, 30), 2)
    cost = straight_cost(length, 30.0)
    return {
        "construction_cost": cost - rules()["tie_in_cost"],
        "chamber_construction_cost": 0.0,
        "tie_in_cost": rules()["tie_in_cost"],
        "reconstruction_cost": 0.0,
        "chamber_reconstruction_cost": 0.0,
        "new_network_length": length,
        "reconstruction_length": 0.0,
        "length": length,
    }


@scenario("S13-01", tz=["TZ-64", "TZ-65", "TZ-70"], title="врезка в трубу через дорогу: новая камера, участок special и два технических узла на границах зоны")
def pipe_tie_across_road() -> tuple[Scene, Expect]:
    # Вторая ветка на юг даёт другой объект врезки, но оттуда к ОКС больше 160 м и дорогу под острым углом не пересечь.
    sc = Scene()
    sc.source("src", 0, 0)
    sc.pipe("hn-1", [(0, 0), (300, 0)], dn=150, flow=40.0, upstream="src")
    sc.pipe("hn-2", [(0, 0), (0, -300)], dn=150, flow=40.0, upstream="src")
    sc.restriction("road-1", "road", Polygon([(-50, 20), (350, 20), (350, 32), (-50, 32)]))
    cp = sc.oks("oks-1", cp=(150, 60), flow=5.0, away=(0, 1))
    return sc, Expect(
        tie_ins=[{"existing_object_id": "hn-1", "existing_object_type": "heat_network"}],
        new_chambers=[150],
        segment_dn={cp: diameter_for(rules(), 5.0)},
        special={"road": 1},
        technical_nodes=2,
        no_recon=True,
    )


@scenario("S13-02", tz=["TZ-65", "TZ-70"], title="трасса пересекает газопровод: участок special и технические узлы по 2 м от точки пересечения")
def gas_crossing_nodes() -> tuple[Scene, Expect]:
    sc = Scene()
    chain(sc, 2)
    sc.restriction("gas-1", "gas_pipeline", LineString([(-100, 20), (100, 20)]))
    sc.oks("oks-1", cp=(-30, 40), flow=5.0)
    return sc, Expect(
        tie_ins=[chamber_tie("hc-1")],
        special={"gas_pipeline": 1},
        technical_nodes=2,
    )


@scenario("S13-03", tz=["TZ-64", "TZ-65"], title="три ОКС одним деревом: участки заканчиваются в точках подключения, сами точки в выход не попадают")
def three_oks_tree_ends_at_connection_points() -> tuple[Scene, Expect]:
    sc = Scene()
    chain(sc, 2)
    cps = [sc.oks(f"oks-{n}", cp=(x, 150), flow=5.0, away=(0, 1)) for n, x in ((1, -60), (2, -40), (3, -20))]
    return sc, Expect(segment_dn={cp: diameter_for(rules(), 5.0) for cp in cps}, unconnected=[])


@scenario("S13-04", tz=["TZ-63", "TZ-71"], title="сводка варианта: пять стоимостей, длины и score по формуле")
def summary_fields_by_formula() -> tuple[Scene, Expect]:
    sc = Scene()
    two_branches(sc)
    sc.oks("oks-1", cp=(-50, 30), flow=30.0)
    expected = summary_of_west_oks()
    cost = straight_cost(expected["length"], 30.0)
    return sc, Expect(
        tie_ins=[chamber_tie("hc-w")],
        summary=expected | {"unconnected_penalty": 0.0, "calculated_cost": cost, "score": score(cost, expected["length"])},
    )


@scenario("S13-05", tz=["TZ-63", "TZ-64"], title="два варианта в одном файле: у каждого своя сводка и свои объекты")
def two_variants_in_one_file() -> tuple[Scene, Expect]:
    sc = Scene()
    chain(sc, 2)
    cp = sc.oks("oks-1", cp=(60, 40), flow=5.0)
    return sc, Expect(
        tie_ins=[chamber_tie("hc-2")],
        segment_dn={cp: diameter_for(rules(), 5.0)},
        variants=(2, 2),
        distinct_tie_in_sets=True,
    )


@scenario("S13-06", tz=["TZ-64", "TZ-71"], title="сводка при неподключённом ОКС: штраф в calculated_cost и score")
def summary_with_unconnected() -> tuple[Scene, Expect]:
    sc = Scene()
    two_branches(sc)
    sc.oks("oks-1", cp=(-50, 30), flow=30.0)
    sc.restriction("social-1", "social_area", island(60, 120, 30, 20))
    oks_at(sc, "oks-2", 60, 120, flow=20.0)
    expected = summary_of_west_oks()
    cost = straight_cost(expected["length"], 30.0) + penalty(20.0)
    return sc, Expect(
        unconnected=["oks-2"],
        summary=expected | {"unconnected_penalty": penalty(20.0), "calculated_cost": cost, "score": score(cost, expected["length"])},
    )
