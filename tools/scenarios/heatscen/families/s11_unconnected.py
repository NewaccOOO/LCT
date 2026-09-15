import math

from shapely.geometry import Polygon

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


def island(x: float, y: float, side: float, ring: float) -> Polygon:
    """Квадратное кольцо вокруг квадрата со стороной `side` и центром (x, y); `ring` — ширина кольца."""
    inner, outer = side / 2, side / 2 + ring
    return Polygon(
        [(x - outer, y - outer), (x + outer, y - outer), (x + outer, y + outer), (x - outer, y + outer)],
        [[(x - inner, y - inner), (x + inner, y - inner), (x + inner, y + inner), (x - inner, y + inner)]],
    )


def oks_at(sc: Scene, id: str, x: float, y: float, flow: float) -> str:
    """ОКС 10 × 10 м с центром (x, y), точка подключения на середине южной стороны."""
    return sc.oks(id, cp=(x, y - 5), flow=flow, polygon=[[(x - 5, y - 5), (x + 5, y - 5), (x + 5, y + 5), (x - 5, y + 5)]])


@scenario("S11-01", tz=["TZ-56", "TZ-61", "TZ-89"], title="ОКС на острове в кольце воды: в списке неподключённых со штрафом, второй ОКС подключён")
def oks_on_island_in_water() -> tuple[Scene, Expect]:
    sc = Scene()
    chain(sc, 2)
    cp = sc.oks("oks-1", cp=(-30, 40), flow=5.0)
    sc.restriction("water-1", "water", island(90, 90, 40, 30))
    oks_at(sc, "oks-2", 90, 90, flow=12.0)
    return sc, Expect(
        tie_ins=[chamber_tie("hc-1")],
        segment_dn={cp: diameter_for(rules(), 5.0)},
        unconnected=["oks-2"],
        penalty=penalty(12.0),
    )


@scenario("S11-02", tz=["TZ-56", "TZ-57", "TZ-61"], title="оба ОКС на островах: все неподключены, стоимость варианта равна штрафу")
def all_oks_unconnected() -> tuple[Scene, Expect]:
    sc = Scene()
    chain(sc, 2)
    sc.restriction("water-1", "water", island(-60, 90, 40, 30))
    oks_at(sc, "oks-1", -60, 90, flow=4.0)
    sc.restriction("water-2", "water", island(90, 90, 40, 30))
    oks_at(sc, "oks-2", 90, 90, flow=9.5)
    total = penalty(4.0, 9.5)
    return sc, Expect(
        unconnected=["oks-1", "oks-2"],
        penalty=total,
        summary={"new_network_length": 0.0, "tie_in_cost": 0.0, "calculated_cost": total},
    )


@scenario("S11-03", tz=["TZ-56", "TZ-61"], title="два ОКС внутри закрытой территории: штраф растёт с расходом и входит в стоимость варианта")
def penalty_grows_with_flow() -> tuple[Scene, Expect]:
    sc = Scene()
    chain(sc, 2)
    sc.oks("oks-1", cp=(-30, 40), flow=5.0)
    sc.restriction("site-1", "prohibited_site", Polygon(
        [(40, 50), (200, 50), (200, 150), (40, 150)],
        [[(60, 70), (180, 70), (180, 130), (60, 130)]],
    ))
    oks_at(sc, "oks-big", 90, 100, flow=150.0)
    oks_at(sc, "oks-small", 150, 100, flow=2.5)
    length = round(math.hypot(38, 40), 2)
    return sc, Expect(
        unconnected=["oks-big", "oks-small"],
        penalty=penalty(150.0, 2.5),
        summary={"calculated_cost": straight_cost(length, 5.0) + penalty(150.0, 2.5)},
    )


@scenario("S11-04", tz=["TZ-57", "TZ-61"], title="ОКС на острове озера: оба варианта подключения второго ОКС выдаются, остров в штрафе")
def variants_keep_unconnected_oks() -> tuple[Scene, Expect]:
    sc = Scene()
    two_branches(sc)
    sc.oks("oks-1", cp=(60, 20), flow=30.0)
    sc.restriction("lake-1", "water", island(0, 140, 40, 40))
    oks_at(sc, "oks-2", 0, 140, flow=6.0)
    return sc, Expect(
        tie_ins=[chamber_tie("hc-e")],
        unconnected=["oks-2"],
        penalty=penalty(6.0),
        variants=(2, 2),
        distinct_tie_in_sets=True,
    )


@scenario("S11-05", tz=["TZ-61", "TZ-89"], title="четыре ОКС за один запуск: три подключены, четвёртый за оградой в списке неподключённых")
def four_oks_one_unconnected() -> tuple[Scene, Expect]:
    sc = Scene()
    sc.source("src", 0, 0)
    sc.pipe("hn-1", [(0, 0), (150, 0)], dn=200, flow=60.0, upstream="src")
    sc.chamber("hc-1", 150, 0, dn=200, upstream="hn-1")
    sc.pipe("hn-2", [(150, 0), (300, 0)], dn=150, flow=30.0, upstream="hc-1")
    sc.chamber("hc-2", 300, 0, dn=150, upstream="hn-2")
    # ОКС в 30 м от сети и в 130 м друг от друга: три врезки дешевле общего дерева, у каждого свой участок DN по его расходу.
    cps = [oks_at(sc, f"oks-{name}", x, 35, flow=5.0) for name, x in (("a", 20), ("b", 150), ("c", 290))]
    sc.restriction("site-1", "prohibited_site", island(150, -120, 30, 20))
    oks_at(sc, "oks-d", 150, -120, flow=8.0)
    return sc, Expect(
        segment_dn={cp: diameter_for(rules(), 5.0) for cp in cps},
        unconnected=["oks-d"],
        penalty=penalty(8.0),
    )


@scenario("S11-06", tz=["TZ-57", "TZ-61"], title="ОКС окружён парком, соцплощадкой и закрытой территорией впритык: неподключён, второй ОКС подключён")
def oks_enclosed_by_mixed_restrictions() -> tuple[Scene, Expect]:
    sc = Scene()
    chain(sc, 2)
    cp = sc.oks("oks-1", cp=(60, 40), flow=5.0)
    # Четыре полосы перекрываются на углах: зазоров нет, обход невозможен.
    sc.restriction("park-1", "park", Polygon([(-120, 80), (-20, 80), (-20, 95), (-120, 95)]))
    sc.restriction("social-1", "social_area", Polygon([(-35, 80), (-20, 80), (-20, 180), (-35, 180)]))
    sc.restriction("site-1", "prohibited_site", Polygon([(-120, 165), (-20, 165), (-20, 180), (-120, 180)]))
    sc.restriction("park-2", "park", Polygon([(-120, 80), (-105, 80), (-105, 180), (-120, 180)]))
    oks_at(sc, "oks-2", -70, 130, flow=3.0)
    return sc, Expect(
        tie_ins=[chamber_tie("hc-2")],
        segment_dn={cp: diameter_for(rules(), 5.0)},
        unconnected=["oks-2"],
        penalty=penalty(3.0),
    )
