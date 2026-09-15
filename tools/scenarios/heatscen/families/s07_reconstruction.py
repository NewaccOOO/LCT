from typing import Any

from shapely.geometry import box

from heatcheck.model import (
    chamber_cost,
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

ARMOR_GAP_M = 0.5
ARMOR_DEPTH_M = 20.0
GATE_SLACK_M = 0.1
CHAMBER_GATE_MARGIN_M = 1.0
RING_INNER_M = 12.0
RING_WIDTH_M = 5.0
ENCLOSED_OKS_HALF_M = 4.0

Gate = tuple[float, float]


def recon_cost(dn: int, length: float) -> float:
    return length * diameter_row(rules(), dn)["recon_rub_m"]


def gate_half(flow: float) -> float:
    """Полуширина ворот в полосах парка для трассы от врезки: сервис строит граф на ступень выше Ду по расходу и с запасом 0,05 м."""
    dn = next_diameter(rules(), diameter_for(rules(), flow))
    return rules()["restrictions"]["park"]["clearance_m"] + diameter_row(rules(), dn)["width_m"] / 2 + GATE_SLACK_M


def chamber_gate_half() -> float:
    # в воротах камеры любая точка трубы не дальше max_dist_m от камеры, и врезка по TZ-50 идёт в саму камеру
    return rules()["chamber_rule"]["max_dist_m"] - CHAMBER_GATE_MARGIN_M


def chain(sc: Scene, links: list[tuple[float, int, float]]) -> list[float]:
    """Источник в (0, 0) и цепочка вдоль оси x: участок hn-i (длина, Ду, расход), в его конце камера hc-i с Ду по TZ-6."""
    sc.source("src", 0, 0)
    x, upstream, ends = 0.0, "src", []
    for i, (length, dn, flow) in enumerate(links, start=1):
        sc.pipe(f"hn-{i}", [(x, 0), (x + length, 0)], dn=dn, flow=flow, upstream=upstream)
        x += length
        next_dn = links[i][1] if i < len(links) else dn
        sc.chamber(f"hc-{i}", x, 0, dn=max(dn, next_dn), upstream=f"hn-{i}")
        upstream = f"hc-{i}"
        ends.append(x)
    return ends


def park(sc: Scene, x0: float, y0: float, x1: float, y1: float) -> None:
    sc.restriction(f"park-{len(sc.features)}", "park", box(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1)))


def armor(
    sc: Scene,
    x0: float,
    x1: float,
    top: list[Gate],
    bottom: list[Gate],
    source_x: float | None = 0.0,
    top_depth: float = ARMOR_DEPTH_M,
    bottom_depth: float = ARMOR_DEPTH_M,
) -> None:
    """Полосы парка вдоль сети на оси x от x0 до x1: новая сеть подходит к существующей только в воротах (x, полуширина)."""
    # Без брони врезка по ТЗ выгоднее ближе к источнику: ставка реконструкции выше ставки нового строительства, и длина
    # реконструкции перестала бы следовать из сцены (A-5). Ворота с одной стороны дают нишу на другой: участок от врезки
    # держит отступ от полосы напротив.
    for sign, gates, opposite, depth in ((1, top, bottom, top_depth), (-1, bottom, top, bottom_depth)):
        cuts = sorted([(x, half, False) for x, half in gates] + [(x, half, True) for x, half in opposite if (x, half) not in gates])
        start = x0
        for x, half, notch in cuts:
            park(sc, start, sign * ARMOR_GAP_M, x - half, sign * depth)
            if notch:
                park(sc, x - half, sign * (half + GATE_SLACK_M), x + half, sign * depth)
            start = x + half
        park(sc, start, sign * ARMOR_GAP_M, x1, sign * depth)
    if source_x is not None:
        if source_x - x0 < x1 - source_x:
            park(sc, x0, -ARMOR_GAP_M, source_x - ARMOR_GAP_M, ARMOR_GAP_M)
        else:
            park(sc, source_x + ARMOR_GAP_M, -ARMOR_GAP_M, x1, ARMOR_GAP_M)


def enclosed_oks(sc: Scene, id: str, cx: float, cy: float, flow: float) -> str:
    """ОКС внутри кольца воды: маршрута к нему нет (TZ-61)."""
    inner, outer = RING_INNER_M, RING_INNER_M + RING_WIDTH_M
    for x0, y0, x1, y1 in ((-outer, -outer, outer, -inner), (-outer, inner, outer, outer), (-outer, -inner, -inner, inner), (inner, -inner, outer, inner)):
        sc.restriction(f"water-{id}-{len(sc.features)}", "water", box(cx + x0, cy + y0, cx + x1, cy + y1))
    h = ENCLOSED_OKS_HALF_M
    return sc.oks(id, cp=(cx, cy - h), flow=flow, polygon=[[(cx - h, cy - h), (cx + h, cy - h), (cx + h, cy + h), (cx - h, cy + h)]])


def pipe_tie(object_id: str, dn: int) -> dict[str, Any]:
    return {"existing_object_id": object_id, "existing_object_type": "heat_network", "existing_diameter": dn}


def chamber_tie(object_id: str, dn: int) -> dict[str, Any]:
    return {"existing_object_id": object_id, "existing_object_type": "heat_chamber", "existing_diameter": dn}


def recon_part(object_id: str, dn: int, flow: float, added: float, length: float) -> dict[str, Any]:
    """Ожидаемая часть реконструкции по TZ-43…TZ-45: Ду по G_итог, ставка по требуемому Ду."""
    need = diameter_for(rules(), flow + added)
    return {
        "existing_object_id": object_id, "existing_diameter": dn, "required_diameter": need,
        "existing_flow_tph": flow, "added_flow_tph": added, "calculated_flow_tph": flow + added,
        "length": length, "cost": recon_cost(need, length),
    }


def chamber_recon(object_id: str, dn: int, need: int) -> dict[str, Any]:
    return {"existing_object_id": object_id, "existing_diameter": dn, "required_diameter": need, "cost": chamber_cost(rules(), need)}


@scenario("S07-01", tz=["TZ-42", "TZ-43", "TZ-44", "TZ-45", "TZ-67"], title="врезка внутри трубы: реконструируется часть от врезки к источнику")
def recon_part_toward_source() -> tuple[Scene, Expect]:
    sc = Scene()
    chain(sc, [(200, 150, 60), (150, 100, 20)])
    sc.oks("oks-1", cp=(80, 40), flow=10, away=(0, 1))
    armor(sc, -20, 185, top=[(80, gate_half(10))], bottom=[(80, gate_half(10))])
    return sc, Expect(
        tie_ins=[pipe_tie("hn-1", 150)],
        recon=[recon_part("hn-1", 150, 60, 10, 80.0)],
    )


@scenario("S07-02", tz=["TZ-42", "TZ-45", "TZ-46", "TZ-67"], title="участок нарисован от потребителя к источнику: реконструируется часть к источнику")
def recon_part_reversed_pipe() -> tuple[Scene, Expect]:
    sc = Scene()
    sc.source("src", 0, 0)
    sc.pipe("hn-1", [(200, 0), (0, 0)], dn=100, flow=20, upstream="src")
    sc.chamber("hc-1", 200, 0, dn=100, upstream="hn-1")
    sc.pipe("hn-2", [(200, 0), (360, 0)], dn=80, flow=10, upstream="hc-1")
    sc.chamber("hc-2", 360, 0, dn=80, upstream="hn-2")
    sc.oks("oks-1", cp=(130, 40), flow=5, away=(0, 1))
    armor(sc, -20, 185, top=[(130, gate_half(5))], bottom=[(130, gate_half(5))])
    return sc, Expect(
        tie_ins=[pipe_tie("hn-1", 100)],
        recon=[recon_part("hn-1", 100, 20, 5, 130.0)],
    )


@scenario("S07-03", tz=["TZ-41", "TZ-42", "TZ-46"], title="две врезки в один участок: между врезками запас есть, к источнику — реконструкция")
def recon_two_ties_one_pipe() -> tuple[Scene, Expect]:
    # на части между врезками идёт только расход дальнего ОКС (60 т/ч), к источнику — обоих (66 т/ч)
    sc = Scene()
    chain(sc, [(400, 150, 55), (150, 100, 20)])
    sc.oks("oks-a", cp=(300, 40), flow=5, away=(0, 1))
    sc.oks("oks-b", cp=(120, 40), flow=6, away=(0, 1))
    gates = [(120, gate_half(11)), (300, gate_half(11))]
    armor(sc, -20, 385, top=gates, bottom=gates)
    return sc, Expect(
        tie_ins=[pipe_tie("hn-1", 150), pipe_tie("hn-1", 150)],
        recon=[recon_part("hn-1", 150, 55, 11, 120.0)],
    )


@scenario("S07-04", tz=["TZ-40", "TZ-41", "TZ-43"], title="врезки в две ветки: реконструируется только общий участок у источника")
def recon_common_upstream() -> tuple[Scene, Expect]:
    # по отдельности каждая врезка даёт на hn-1 61 т/ч, вместе 67 т/ч; ветки hn-2 и hn-3 держат свой расход
    sc = Scene()
    sc.source("src", 0, 0)
    sc.pipe("hn-1", [(0, 0), (120, 0)], dn=150, flow=55, upstream="src")
    sc.chamber("hc-1", 120, 0, dn=150, upstream="hn-1")
    sc.pipe("hn-2", [(120, 0), (520, 0)], dn=100, flow=15, upstream="hc-1")
    sc.chamber("hc-2", 520, 0, dn=100, upstream="hn-2")
    sc.pipe("hn-3", [(120, 0), (120, 400)], dn=100, flow=15, upstream="hc-1")
    sc.chamber("hc-3", 120, 400, dn=100, upstream="hn-3")
    sc.oks("oks-a", cp=(420, -40), flow=6, away=(0, -1))
    sc.oks("oks-b", cp=(160, 300), flow=6, away=(1, 0))
    return sc, Expect(
        tie_ins=[pipe_tie("hn-2", 100), pipe_tie("hn-3", 100)],
        recon=[recon_part("hn-1", 150, 55, 12, 120.0)],
    )


@scenario("S07-05", tz=["TZ-40", "TZ-44", "TZ-46"], title="каскад: расход врезки реконструирует все участки до источника, каждый своей линией")
def recon_cascade_to_source() -> tuple[Scene, Expect]:
    sc = Scene()
    chain(sc, [(60, 150, 60), (60, 125, 35), (150, 100, 20), (130, 80, 10)])
    sc.oks("oks-1", cp=(200, 40), flow=12, away=(0, 1))
    armor(sc, -20, 255, top=[(200, gate_half(12))], bottom=[(200, gate_half(12))])
    return sc, Expect(
        tie_ins=[pipe_tie("hn-3", 100)],
        recon=[
            recon_part("hn-3", 100, 20, 12, 80.0),
            recon_part("hn-2", 125, 35, 12, 60.0),
            recon_part("hn-1", 150, 60, 12, 60.0),
        ],
    )


@scenario("S07-06", tz=["TZ-43", "TZ-44"], title="итоговый расход ровно равен пропускной способности: реконструкции нет")
def no_recon_at_capacity() -> tuple[Scene, Expect]:
    sc = Scene()
    added = 10
    chain(sc, [(300, 150, diameter_row(rules(), 150)["capacity_tph"] - added), (200, 100, 20)])
    sc.oks("oks-1", cp=(150, 40), flow=added, away=(0, 1))
    return sc, Expect(
        tie_ins=[pipe_tie("hn-1", 150)],
        no_recon=True,
    )


@scenario("S07-07", tz=["TZ-40", "TZ-44"], title="цепочка к источнику обрывается на участке с запасом пропускной способности")
def recon_stops_at_spare_pipe() -> tuple[Scene, Expect]:
    sc = Scene()
    chain(sc, [(100, 200, 100), (150, 150, 60), (200, 100, 20)])
    sc.oks("oks-1", cp=(180, 40), flow=10, away=(0, 1))
    armor(sc, -20, 235, top=[(180, gate_half(10))], bottom=[(180, gate_half(10))])
    return sc, Expect(
        tie_ins=[pipe_tie("hn-2", 150)],
        recon=[recon_part("hn-2", 150, 60, 10, 80.0)],
    )


@scenario("S07-08", tz=["TZ-44", "TZ-45", "TZ-67"], title="требуемый Ду через ступень: ставка реконструкции по требуемому DN 250")
def recon_rate_two_steps_up() -> tuple[Scene, Expect]:
    sc = Scene()
    chain(sc, [(200, 150, 60), (150, 100, 20)])
    sc.oks("oks-1", cp=(70, 40), flow=100, away=(0, 1))
    armor(sc, -20, 185, top=[(70, gate_half(100))], bottom=[(70, gate_half(100))])
    return sc, Expect(
        tie_ins=[pipe_tie("hn-1", 150)],
        recon=[recon_part("hn-1", 150, 60, 100, 70.0)],
    )


@scenario("S07-09", tz=["TZ-43", "TZ-45"], title="магистраль DN 400: G_итог выше пропускной способности, ставка DN 500")
def recon_rate_large_pipe() -> tuple[Scene, Expect]:
    sc = Scene()
    chain(sc, [(250, 400, 900), (200, 300, 300)])
    sc.oks("oks-1", cp=(90, 50), flow=60, away=(0, 1))
    armor(sc, -20, 235, top=[(90, gate_half(60))], bottom=[(90, gate_half(60))])
    return sc, Expect(
        tie_ins=[pipe_tie("hn-1", 400)],
        recon=[recon_part("hn-1", 400, 900, 60, 90.0)],
    )


@scenario("S07-10", tz=["TZ-41", "TZ-42", "TZ-46"], title="две врезки в один участок с разных сторон: две части реконструкции со своими расходами")
def recon_two_parts_one_pipe() -> tuple[Scene, Expect]:
    # ОКС по разные стороны трубы: общее дерево обходит броню на 400 м и дороже двух врезок
    sc = Scene()
    chain(sc, [(450, 150, 60), (150, 100, 20)])
    sc.oks("oks-a", cp=(100, 40), flow=6, away=(0, 1))
    sc.oks("oks-b", cp=(40, -40), flow=3, away=(0, -1))
    armor(sc, -20, 435, top=[(100, gate_half(9))], bottom=[(40, gate_half(9))])
    return sc, Expect(
        tie_ins=[pipe_tie("hn-1", 150), pipe_tie("hn-1", 150)],
        recon=[recon_part("hn-1", 150, 60, 6, 60.0), recon_part("hn-1", 150, 60, 9, 40.0)],
    )


@scenario("S07-11", tz=["TZ-9", "TZ-40"], title="Ду к источнику уменьшается: вход принят, реконструируется только узкий участок")
def recon_narrowing_pipe_tie() -> tuple[Scene, Expect]:
    sc = Scene()
    chain(sc, [(100, 150, 60), (350, 200, 60)])
    sc.oks("oks-1", cp=(190, 40), flow=10, away=(0, 1))
    armor(sc, -20, 115, top=[], bottom=[])
    return sc, Expect(
        exit_code=0,
        tie_ins=[pipe_tie("hn-2", 200)],
        recon=[recon_part("hn-1", 150, 60, 10, 100.0)],
    )


@scenario("S07-12", tz=["TZ-9", "TZ-44"], title="Ду к источнику уменьшается, врезка в камеру: каждый участок цепочки считается отдельно")
def recon_narrowing_chamber_tie() -> tuple[Scene, Expect]:
    sc = Scene()
    ends = chain(sc, [(100, 100, 20), (150, 150, 20), (150, 100, 10)])
    sc.pipe("hn-5", [(ends[0], 0), (ends[0], 200)], dn=80, flow=5, upstream="hc-1")
    sc.chamber("hc-5", ends[0], 200, dn=80, upstream="hn-5")
    sc.oks("oks-1", cp=(ends[1], 30), flow=5, away=(0, 1))
    gate = [(ends[1], chamber_gate_half())]
    armor(sc, -20, 385, top=gate, bottom=gate)
    return sc, Expect(
        exit_code=0,
        tie_ins=[chamber_tie("hc-2", 150)],
        recon=[recon_part("hn-1", 100, 20, 5, 100.0)],
        chamber_recon=[],
    )


@scenario("S07-13", tz=["TZ-40", "TZ-43"], title="общее дерево двух ОКС из камеры: G_нов — сумма расходов, участок ниже камеры не трогается")
def recon_shared_tree_flow() -> tuple[Scene, Expect]:
    # hn-2 ниже камеры врезки загружен до 13 из 13,2 т/ч: прибавка расхода к нему дала бы лишнюю реконструкцию
    sc = Scene()
    ends = chain(sc, [(120, 100, 18), (180, 80, 13)])
    sc.oks("oks-a", cp=(ends[0] - 10, 60), flow=3, away=(0, 1))
    sc.oks("oks-b", cp=(ends[0] + 10, 60), flow=3, away=(0, 1))
    gate = [(ends[0], chamber_gate_half())]
    armor(sc, -20, 285, top=gate, bottom=gate)
    return sc, Expect(
        tie_ins=[chamber_tie("hc-1", 100)],
        recon=[recon_part("hn-1", 100, 18, 6, 120.0)],
    )


@scenario("S07-14", tz=["TZ-40", "TZ-44"], title="расход неподключённого ОКС в реконструкцию не идёт")
def no_recon_from_unconnected() -> tuple[Scene, Expect]:
    sc = Scene()
    chain(sc, [(300, 150, 62), (200, 100, 20)])
    sc.oks("oks-a", cp=(150, 40), flow=3, away=(0, 1))
    enclosed_oks(sc, "oks-b", 150, -190, flow=10)
    return sc, Expect(
        tie_ins=[pipe_tie("hn-1", 150)],
        unconnected=["oks-b"],
        no_recon=True,
    )
