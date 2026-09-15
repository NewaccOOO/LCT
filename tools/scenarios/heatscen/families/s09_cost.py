from typing import Any

from shapely.geometry import (
    LineString,
    box,
)

from heatcheck.model import (
    chamber_cost,
    diameter_for,
    diameter_row,
)
from heatscen import (
    Expect,
    Scene,
    rules,
    scenario,
)
from heatscen.families.s07_reconstruction import (
    ARMOR_DEPTH_M,
    armor,
    chain,
    chamber_gate_half,
    chamber_tie,
    enclosed_oks,
    gate_half,
    pipe_tie,
    recon_cost,
    recon_part,
)

EXAMPLE_SPECIAL_M = 145.2
EXAMPLE_TAIL_M = 14.8
EXAMPLE_RECON_M = 75.0
EXAMPLE_PIPE_M = 300.0
EXAMPLE_FORMULA_TOL_RUB = 100.0
ROAD_BELOW_PIPE_M = 0.1
THIN_STRIP_M = 2.5


def new_cost(dn: int, length: float, k: float = 1.0) -> float:
    return length * diameter_row(rules(), dn)["new_rub_m"] * k


def penalty(*flows: float) -> float:
    rule = rules()["penalty"]
    return sum(rule["fixed"] + rule["per_tph"] * flow for flow in flows)


def summary(
    construction: float,
    new_length: float,
    chambers: float = 0.0,
    ties: int = 1,
    recon: float = 0.0,
    recon_length: float = 0.0,
    chamber_recons: float = 0.0,
    fine: float = 0.0,
) -> dict[str, Any]:
    """Сводка варианта по TZ-54, TZ-59 и TZ-71 из стоимостей, посчитанных по сцене."""
    total = construction + chambers + ties * rules()["tie_in_cost"] + recon + chamber_recons + fine
    length = new_length + recon_length
    score = rules()["score"]
    return {
        "construction_cost": construction,
        "chamber_construction_cost": chambers,
        "tie_in_cost": ties * rules()["tie_in_cost"],
        "reconstruction_cost": recon,
        "chamber_reconstruction_cost": chamber_recons,
        "unconnected_penalty": fine,
        "calculated_cost": total,
        "new_network_length": new_length,
        "reconstruction_length": recon_length,
        "length": length,
        "score": score["w_cost"] * total / score["cost_base"] + score["w_length"] * length / score["length_base_m"],
    }


def without_special_costs(fields: dict[str, Any]) -> dict[str, Any]:
    # граница специальной части сдвинута в пределах допуска длины 0,05 м, и сумма стоимостей участков расходится со
    # сценой больше чем на 1 руб.; стоимость каждого участка по его длине проверяет costs_by_formula
    return {key: value for key, value in fields.items() if key not in ("construction_cost", "calculated_cost")}


def example_scene(mirrored: bool) -> tuple[Scene, Expect]:
    """Пример ТП §10.8: DN 200 через дорогу 145,2 м от врезки в DN 150, реконструкция 75 м; зеркально — источник справа."""
    # зона дороги начинается чуть ниже оси трубы, поэтому специальный участок идёт от самой врезки, как new_1 примера
    def x(value: float) -> float:
        return EXAMPLE_PIPE_M - value if mirrored else value

    side = -1 if mirrored else 1
    existing_flow, flow = 100, 80
    road = rules()["restrictions"]["road"]
    tie_x = EXAMPLE_RECON_M
    sc = Scene()
    sc.source("src", x(0), 0)
    sc.pipe("hn-1", [(x(0), 0), (x(EXAMPLE_PIPE_M), 0)], dn=150, flow=existing_flow, upstream="src")
    sc.chamber("hc-1", x(EXAMPLE_PIPE_M), 0, dn=150, upstream="hn-1")
    sc.pipe("hn-2", [(x(EXAMPLE_PIPE_M), 0), (x(EXAMPLE_PIPE_M + 150), 0)], dn=100, flow=20, upstream="hc-1")
    sc.chamber("hc-2", x(EXAMPLE_PIPE_M + 150), 0, dn=100, upstream="hn-2")
    near, far = road["margin_m"] - ROAD_BELOW_PIPE_M, EXAMPLE_SPECIAL_M - road["margin_m"]
    sc.restriction("road-1", "road", box(-200, min(side * near, side * far), 500, max(side * near, side * far)))
    sc.oks("oks-1", cp=(x(tie_x), side * (EXAMPLE_SPECIAL_M + EXAMPLE_TAIL_M)), flow=flow, away=(0, side))
    gate = [(x(tie_x), gate_half(flow))]
    x0, x1 = sorted((x(-20), x(EXAMPLE_PIPE_M - 15)))
    # полоса со стороны дороги тонкая: между трубой и дорогой всего 2,9 м
    armor(
        sc, x0, x1, top=gate, bottom=gate, source_x=x(0),
        top_depth=ARMOR_DEPTH_M if mirrored else THIN_STRIP_M,
        bottom_depth=THIN_STRIP_M if mirrored else ARMOR_DEPTH_M,
    )

    dn = diameter_for(rules(), flow)
    recon_dn = diameter_for(rules(), existing_flow + flow)
    construction = new_cost(dn, EXAMPLE_SPECIAL_M, road["k_special"]) + new_cost(dn, EXAMPLE_TAIL_M)
    return sc, Expect(
        tie_ins=[pipe_tie("hn-1", 150)],
        special={"road": 1},
        technical_nodes=1,
        new_chambers=[max(dn, recon_dn)],
        recon=[recon_part("hn-1", 150, existing_flow, flow, EXAMPLE_RECON_M)],
        costs_by_formula=True,
        formula_tol_rub=EXAMPLE_FORMULA_TOL_RUB,
        summary=without_special_costs(summary(
            construction, EXAMPLE_SPECIAL_M + EXAMPLE_TAIL_M, chambers=chamber_cost(rules(), max(dn, recon_dn)),
            recon=recon_cost(recon_dn, EXAMPLE_RECON_M), recon_length=EXAMPLE_RECON_M,
        )),
    )


@scenario("S09-01", tz=["TZ-54", "TZ-55", "TZ-59", "TZ-71"], title="одна врезка в трубу без реконструкции: сводка по формулам")
def summary_single_pipe_tie() -> tuple[Scene, Expect]:
    sc = Scene()
    chain(sc, [(300, 150, 20), (200, 100, 10)])
    sc.oks("oks-1", cp=(150, 50), flow=10, away=(0, 1))
    dn = diameter_for(rules(), 10)
    return sc, Expect(
        tie_ins=[pipe_tie("hn-1", 150)],
        costs_by_formula=True,
        summary=summary(new_cost(dn, 50), 50, chambers=chamber_cost(rules(), max(dn, 150))),
    )


@scenario("S09-02", tz=["TZ-54", "TZ-59", "TZ-71"], title="врезка в камеру с реконструкцией участка и камеры: пять стоимостей и длина с реконструкцией")
def summary_with_reconstruction() -> tuple[Scene, Expect]:
    sc = Scene()
    ends = chain(sc, [(70, 150, 60), (150, 100, 20)])
    sc.oks("oks-1", cp=(ends[0], 45), flow=8, away=(0, 1))
    gate = [(ends[0], chamber_gate_half())]
    armor(sc, -20, 205, top=gate, bottom=gate)
    dn, recon_dn = diameter_for(rules(), 8), diameter_for(rules(), 68)
    return sc, Expect(
        tie_ins=[chamber_tie("hc-1", 150)],
        costs_by_formula=True,
        summary=summary(
            new_cost(dn, 45), 45, recon=recon_cost(recon_dn, 70), recon_length=70,
            chamber_recons=chamber_cost(rules(), max(dn, recon_dn)),
        ),
    )


@scenario("S09-03", tz=["TZ-54", "TZ-56", "TZ-71"], title="один ОКС неподключён: штраф с расходом входит в итоговую стоимость и score")
def summary_with_penalty() -> tuple[Scene, Expect]:
    sc = Scene()
    chain(sc, [(300, 150, 20), (200, 100, 10)])
    sc.oks("oks-a", cp=(100, 50), flow=10, away=(0, 1))
    enclosed_oks(sc, "oks-b", 100, -200, flow=7)
    dn = diameter_for(rules(), 10)
    return sc, Expect(
        unconnected=["oks-b"],
        penalty=penalty(7),
        summary=summary(new_cost(dn, 50), 50, chambers=chamber_cost(rules(), max(dn, 150)), fine=penalty(7)),
    )


@scenario("S09-04", tz=["TZ-56"], title="два неподключённых ОКС: общий штраф — сумма штрафов")
def penalty_sum_two_unconnected() -> tuple[Scene, Expect]:
    sc = Scene()
    chain(sc, [(300, 150, 20), (200, 100, 10)])
    sc.oks("oks-a", cp=(220, 50), flow=5, away=(0, 1))
    enclosed_oks(sc, "oks-b1", 60, -200, flow=4)
    enclosed_oks(sc, "oks-b2", 240, -200, flow=12.5)
    return sc, Expect(
        unconnected=["oks-b1", "oks-b2"],
        penalty=penalty(4, 12.5),
        summary={"unconnected_penalty": penalty(4, 12.5)},
    )


@scenario("S09-05", tz=["TZ-55", "TZ-72"], title="пример ТП 10.8: DN 200 через дорогу 145,2 м и реконструкция 75 м")
def example_108() -> tuple[Scene, Expect]:
    return example_scene(mirrored=False)


@scenario("S09-06", tz=["TZ-55", "TZ-72"], title="пример ТП 10.8 зеркально: источник справа, дорога снизу")
def example_108_mirrored() -> tuple[Scene, Expect]:
    return example_scene(mirrored=True)


@scenario("S09-07", tz=["TZ-59", "TZ-60"], title="rank 1 у варианта с наименьшим score: врезка в трубу рядом, а не в дальнюю камеру")
def rank_best_pipe_tie() -> tuple[Scene, Expect]:
    sc = Scene()
    chain(sc, [(250, 200, 50), (100, 150, 20)])
    sc.oks("oks-1", cp=(120, 60), flow=20, away=(0, 1))
    dn = diameter_for(rules(), 20)
    return sc, Expect(
        tie_ins=[pipe_tie("hn-1", 200)],
        summary=summary(new_cost(dn, 60), 60, chambers=chamber_cost(rules(), max(dn, 200))),
    )


@scenario("S09-08", tz=["TZ-54", "TZ-60"], title="rank 1 у врезки в камеру: без новой камеры дешевле врезки в трубу рядом")
def rank_best_chamber_tie() -> tuple[Scene, Expect]:
    sc = Scene()
    ends = chain(sc, [(150, 150, 30), (200, 100, 20)])
    sc.oks("oks-1", cp=(ends[0], 40), flow=5, away=(0, 1))
    return sc, Expect(
        tie_ins=[chamber_tie("hc-1", 150)],
        new_chambers=0,
        costs_by_formula=True,
        summary=summary(new_cost(diameter_for(rules(), 5), 40), 40),
    )


@scenario("S09-09", tz=["TZ-54", "TZ-55", "TZ-71"], title="две независимые врезки: стоимости и длины двух деревьев складываются")
def summary_two_ties() -> tuple[Scene, Expect]:
    sc = Scene()
    chain(sc, [(800, 200, 50), (200, 150, 20)])
    sc.oks("oks-a", cp=(150, 50), flow=10, away=(0, 1))
    sc.oks("oks-b", cp=(550, 50), flow=15, away=(0, 1))
    dn_a, dn_b = diameter_for(rules(), 10), diameter_for(rules(), 15)
    return sc, Expect(
        tie_ins=[pipe_tie("hn-1", 200), pipe_tie("hn-1", 200)],
        costs_by_formula=True,
        summary=summary(
            new_cost(dn_a, 50) + new_cost(dn_b, 50), 100, ties=2,
            chambers=chamber_cost(rules(), max(dn_a, 200)) + chamber_cost(rules(), max(dn_b, 200)),
        ),
    )


@scenario("S09-10", tz=["TZ-54", "TZ-55"], title="переход газопровода: специальная часть 4 м с Kспец 1,25, остальное по базовой ставке")
def cost_gas_crossing() -> tuple[Scene, Expect]:
    sc = Scene()
    chain(sc, [(300, 150, 20), (200, 100, 10)])
    sc.oks("oks-1", cp=(150, 60), flow=10, away=(0, 1))
    sc.restriction("gas-1", "gas_pipeline", LineString([(-50, 30), (350, 30)]))
    gas = rules()["restrictions"]["gas_pipeline"]
    dn, span = diameter_for(rules(), 10), 2 * gas["margin_m"]
    return sc, Expect(
        special={"gas_pipeline": 1},
        technical_nodes=2,
        costs_by_formula=True,
        summary=without_special_costs(summary(
            new_cost(dn, 60 - span) + new_cost(dn, span, gas["k_special"]), 60, chambers=chamber_cost(rules(), max(dn, 150)),
        )),
    )
