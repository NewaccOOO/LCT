from shapely.geometry import Polygon

from heatcheck.model import diameter_for
from heatscen import (
    Expect,
    Scene,
    rules,
    scenario,
)


@scenario("S00-01", tz=["TZ-50", "TZ-63"], title="сцена фикстуры валидатора: врезка в ближнюю камеру без дороги")
def smoke_validator_fixture() -> tuple[Scene, Expect]:
    # Через ch_2 маршрут короче, но добавляет реконструкцию net_2 до Ду 100 и переход дороги: вариант через ch_1 дешевле.
    sc = Scene()
    sc.source("src", 0, 0)
    sc.pipe("net_1", [(0, 0), (200, 0)], dn=100, flow=15.0, upstream="src")
    sc.pipe("net_2", [(400, 0), (200, 0)], dn=80, flow=12.0, upstream="ch_1")
    sc.chamber("ch_1", 200, 0, dn=100, upstream="net_1")
    sc.chamber("ch_2", 400, 0, dn=80, upstream="net_2")
    cp = sc.oks("oks_1", cp=(320, 150), flow=5.0, polygon=[[(300, 150), (340, 150), (340, 190), (300, 190)]], heat_load=3.1)
    sc.restriction("park_1", "park", Polygon([(245, 45), (285, 45), (285, 95), (245, 95)]))
    sc.restriction("road_1", "road", Polygon([(330, 60), (480, 60), (480, 72), (330, 72)]))
    return sc, Expect(
        tie_ins=[{"existing_object_id": "ch_1", "existing_object_type": "heat_chamber"}],
        new_chambers=0,
        segment_dn={cp: diameter_for(rules(), 5.0)},
        no_recon=True,
        unconnected=[],
        clearance=["park_1"],
    )
