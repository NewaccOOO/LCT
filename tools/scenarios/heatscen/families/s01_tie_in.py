from shapely.geometry import Polygon

from heatcheck.model import diameter_for
from heatscen import (
    Expect,
    Scene,
    rules,
    scenario,
)

NORTH = (0, 1)
SOUTH = (0, -1)


def trunk(dn: int = 150, flow: float = 20.0) -> Scene:
    """Источник, hn-1 300 м до камеры hc-1, hn-2 600 м до камеры hc-2 на оси x."""
    sc = Scene()
    sc.source("src", 0, 0)
    sc.pipe("hn-1", [(0, 0), (300, 0)], dn=dn, flow=flow, upstream="src")
    sc.chamber("hc-1", 300, 0, dn=dn, upstream="hn-1")
    sc.pipe("hn-2", [(300, 0), (900, 0)], dn=dn, flow=flow, upstream="hc-1")
    sc.chamber("hc-2", 900, 0, dn=dn, upstream="hn-2")
    return sc


def chamber_tie(chamber_id: str, dn: int) -> dict[str, object]:
    return {"existing_object_id": chamber_id, "existing_object_type": "heat_chamber", "existing_diameter": dn}


def pipe_tie(pipe_id: str, dn: int) -> dict[str, object]:
    return {"existing_object_id": pipe_id, "existing_object_type": "heat_network", "existing_diameter": dn}


@scenario("S01-01", tz=["TZ-50", "TZ-66"], title="точка врезки на трубе в 9,5 м от камеры: врезка в камеру")
def chamber_within_limit() -> tuple[Scene, Expect]:
    sc = trunk()
    cp = sc.oks("oks-1", cp=(300 + rules()["chamber_rule"]["max_dist_m"] - 0.5, 40), flow=5, away=NORTH)
    return sc, Expect(
        tie_ins=[chamber_tie("hc-1", 150)],
        new_chambers=0,
        segment_dn={cp: diameter_for(rules(), 5)},
        unconnected=[],
    )


@scenario("S01-02", tz=["TZ-50", "TZ-53"], title="точка врезки на трубе ровно в 10 м от камеры: врезка в камеру")
def chamber_at_limit() -> tuple[Scene, Expect]:
    sc = trunk()
    sc.oks("oks-1", cp=(300 + rules()["chamber_rule"]["max_dist_m"], 40), flow=5, away=NORTH)
    return sc, Expect(
        tie_ins=[chamber_tie("hc-1", 150)],
        new_chambers=0,
        summary={"chamber_construction_cost": 0, "tie_in_cost": rules()["tie_in_cost"]},
        unconnected=[],
    )


@scenario("S01-03", tz=["TZ-50", "TZ-53"], title="точка врезки в 10,5 м от камеры: врезка в трубу и новая камера")
def chamber_beyond_limit_behind_wall() -> tuple[Scene, Expect]:
    # Без стены маршрут от самой камеры дешевле новой камеры на 3 млн; стена уводит его в обход на 270 м.
    sc = trunk()
    sc.restriction("wall", "prohibited_site", Polygon([(304, -150), (307, -150), (307, 150), (304, 150)]))
    cp = sc.oks("oks-1", cp=(300 + rules()["chamber_rule"]["max_dist_m"] + 0.5, 40), flow=5, away=NORTH)
    return sc, Expect(
        tie_ins=[pipe_tie("hn-2", 150)],
        new_chambers=1,
        segment_dn={cp: diameter_for(rules(), 5)},
        summary={"tie_in_cost": rules()["tie_in_cost"]},
        unconnected=[],
    )


@scenario("S01-04", tz=["TZ-17", "TZ-50"], title="у камеры три участка: четвёртым становится новый, врезка в камеру")
def chamber_with_three_links() -> tuple[Scene, Expect]:
    sc = trunk()
    sc.pipe("hn-3", [(300, 0), (300, -200)], dn=150, flow=10, upstream="hc-1")
    sc.oks("oks-1", cp=(305, 40), flow=5, away=NORTH)
    return sc, Expect(
        tie_ins=[chamber_tie("hc-1", 150)],
        new_chambers=0,
        max_new_length=41,
        unconnected=[],
    )


@scenario("S01-05", tz=["TZ-17", "TZ-50"], title="у камеры четыре участка: врезка в трубу в 9 м от неё с новой камерой")
def full_chamber_pipe_nearby() -> tuple[Scene, Expect]:
    # Парк закрывает выход на север от трубы hn-1 у камеры: иначе врезка в hn-1 дороже врезки в hn-2 всего на 1,6 м трассы.
    sc = trunk()
    sc.pipe("hn-3", [(300, 0), (100, -200)], dn=150, flow=5, upstream="hc-1")
    sc.pipe("hn-4", [(300, 0), (500, -200)], dn=150, flow=5, upstream="hc-1")
    sc.restriction("park-1", "park", Polygon([(150, 3), (303, 3), (303, 150), (150, 150)]))
    sc.oks("oks-1", cp=(309, 40), flow=5, away=NORTH)
    return sc, Expect(
        tie_ins=[pipe_tie("hn-2", 150)],
        new_chambers=1,
        max_new_length=41,
        unconnected=[],
    )


@scenario("S01-06", tz=["TZ-17", "TZ-19", "TZ-53"], title="два ОКС по разные стороны камеры с двумя участками: оба в камеру, каждая ветка своей врезкой")
def two_oks_both_sides_of_chamber() -> tuple[Scene, Expect]:
    # Протокол 16.09.2026 п. 8: несколько новых веток к одной камере — несколько независимых врезок.
    sc = trunk()
    north = sc.oks("oks-n", cp=(300, 40), flow=5, away=NORTH)
    south = sc.oks("oks-s", cp=(300, -40), flow=10, away=SOUTH)
    return sc, Expect(
        tie_ins=[chamber_tie("hc-1", 150), chamber_tie("hc-1", 150)],
        new_chambers=0,
        segment_dn={north: diameter_for(rules(), 5), south: diameter_for(rules(), 10)},
        summary={"tie_in_cost": 2 * rules()["tie_in_cost"]},
        unconnected=[],
    )


@scenario("S01-07", tz=["TZ-17"], title="три ОКС у камеры с двумя участками: врезка в трубу с двумя камерами дешевле трёх врезок в камеру")
def three_oks_at_chamber_with_two_links() -> tuple[Scene, Expect]:
    # Три ветки из камеры — три врезки по 5 млн (протокол 16.09.2026 п. 8). Врезка в трубу рядом стоит 5 млн плюс
    # новая камера 3 млн плюс камера ветвления 3 млн, поэтому дешевле одна врезка в hn-2 с двумя новыми камерами.
    sc = trunk()
    sc.oks("oks-n", cp=(300, 40), flow=5, away=NORTH)
    sc.oks("oks-s", cp=(300, -40), flow=5, away=SOUTH)
    sc.oks("oks-ne", cp=(340, 40), flow=5, away=NORTH)
    return sc, Expect(
        tie_ins=[pipe_tie("hn-2", 150)],
        new_chambers=2,
        unconnected=[],
    )


@scenario("S01-08", tz=["TZ-19", "TZ-66"], title="врезка в середину трубы с новой камерой")
def pipe_middle() -> tuple[Scene, Expect]:
    sc = trunk()
    cp = sc.oks("oks-1", cp=(600, 40), flow=5, away=NORTH)
    return sc, Expect(
        tie_ins=[pipe_tie("hn-2", 150)],
        new_chambers=1,
        segment_dn={cp: diameter_for(rules(), 5)},
        max_new_length=40.05,
        unconnected=[],
    )


@scenario("S01-09", tz=["TZ-19", "TZ-66"], title="врезка у тупикового конца трубы без камеры")
def pipe_dead_end() -> tuple[Scene, Expect]:
    sc = trunk()
    sc.pipe("hn-3", [(900, 0), (1200, 0)], dn=100, flow=5, upstream="hc-2")
    sc.oks("oks-1", cp=(1210, 40), flow=5, away=NORTH)
    return sc, Expect(
        tie_ins=[pipe_tie("hn-3", 100)],
        new_chambers=1,
        max_new_length=43,
        unconnected=[],
    )


@scenario("S01-10", tz=["TZ-19", "TZ-53"], title="два далёких ОКС: две независимые врезки в разные камеры")
def two_far_oks_two_tie_ins() -> tuple[Scene, Expect]:
    # Общее дерево добавило бы 600 м трассы, это дороже второй врезки в несколько раз.
    sc = trunk()
    sc.oks("oks-a", cp=(300, 40), flow=5, away=NORTH)
    sc.oks("oks-b", cp=(900, 40), flow=5, away=NORTH)
    return sc, Expect(
        tie_ins=[chamber_tie("hc-1", 150), chamber_tie("hc-2", 150)],
        new_chambers=0,
        summary={"tie_in_cost": 2 * rules()["tie_in_cost"]},
        unconnected=[],
    )


@scenario("S01-11", tz=["TZ-19", "TZ-53"], title="два близких ОКС вдали от камер: одна врезка в трубу и общий ствол")
def two_close_oks_one_tie_in() -> tuple[Scene, Expect]:
    # Вторая врезка стоит 5 + 3 млн, а общий ствол экономит 130 м трассы и требует одной камеры ветвления.
    sc = trunk()
    sc.oks("oks-a", cp=(590, 150), flow=5, away=NORTH)
    sc.oks("oks-b", cp=(610, 150), flow=5, away=NORTH)
    return sc, Expect(
        tie_ins=[pipe_tie("hn-2", 150)],
        new_chambers=2,
        summary={"tie_in_cost": rules()["tie_in_cost"]},
        unconnected=[],
    )


@scenario("S01-12", tz=["TZ-66"], title="required_diameter врезки в трубу DN 300 равен диаметру нового участка к ОКС 5 т/ч")
def required_diameter_pipe_small_oks() -> tuple[Scene, Expect]:
    sc = trunk(dn=300, flow=100)
    sc.oks("oks-1", cp=(600, 40), flow=5, away=NORTH)
    return sc, Expect(
        tie_ins=[pipe_tie("hn-2", 300) | {"required_diameter": diameter_for(rules(), 5)}],
        unconnected=[],
    )


@scenario("S01-13", tz=["TZ-66"], title="required_diameter врезки в камеру DN 400 равен диаметру нового участка к ОКС 30 т/ч")
def required_diameter_chamber_wider_than_new() -> tuple[Scene, Expect]:
    sc = trunk(dn=400, flow=100)
    sc.oks("oks-1", cp=(300, 40), flow=30, away=NORTH)
    return sc, Expect(
        tie_ins=[chamber_tie("hc-1", 400) | {"required_diameter": diameter_for(rules(), 30)}],
        new_chambers=0,
        unconnected=[],
    )


@scenario("S01-14", tz=["TZ-66"], title="required_diameter врезки в загруженную трубу без реконструкции равен диаметру нового участка")
def required_diameter_pipe_loaded() -> tuple[Scene, Expect]:
    # 200 + 60 т/ч укладываются в DN 250, реконструкции нет; новый участок на 60 т/ч уже трубы.
    sc = trunk(dn=250, flow=200)
    sc.oks("oks-1", cp=(600, 40), flow=60, away=NORTH)
    return sc, Expect(
        tie_ins=[pipe_tie("hn-2", 250) | {"required_diameter": diameter_for(rules(), 60)}],
        no_recon=True,
        unconnected=[],
    )


@scenario("S01-15", tz=["TZ-66"], title="required_diameter врезки в трубу у источника для ОКС 100 т/ч")
def required_diameter_pipe_near_source() -> tuple[Scene, Expect]:
    sc = trunk(dn=300, flow=100)
    sc.oks("oks-1", cp=(150, -40), flow=100, away=SOUTH)
    return sc, Expect(
        tie_ins=[pipe_tie("hn-1", 300) | {"required_diameter": diameter_for(rules(), 100)}],
        new_chambers=1,
        unconnected=[],
    )


@scenario("S01-16", tz=["TZ-66"], title="required_diameter врезки в тупиковую камеру DN 300 равен диаметру нового участка к ОКС 60 т/ч")
def required_diameter_dead_end_chamber() -> tuple[Scene, Expect]:
    sc = trunk(dn=300, flow=100)
    sc.oks("oks-1", cp=(900, 40), flow=60, away=NORTH)
    return sc, Expect(
        tie_ins=[chamber_tie("hc-2", 300) | {"required_diameter": diameter_for(rules(), 60)}],
        no_recon=True,
        unconnected=[],
    )
