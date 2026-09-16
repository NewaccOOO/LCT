import math
from typing import Any

import shapely
from shapely.geometry import (
    MultiPolygon,
    Polygon,
)

from heatcheck.model import diameter_for
from heatscen import (
    Expect,
    Scene,
    rules,
    scenario,
)
from heatscen.families.s10_variants import straight_cost
from heatscen.scene import (
    ORIGIN_E,
    ORIGIN_N,
    geojson,
)

OKS_FLOW_TPH = 100.0
OKS_CP = (60.0, 30.0)


def valid_scene() -> Scene:
    """Все семь типов объектов и все восемь атрибутов; две камеры-кандидата, чтобы вариантов было два."""
    sc = Scene()
    sc.source("src", 0, 0)
    sc.pipe("hn-e", [(0, 0), (8, 0)], dn=250, flow=50.0, upstream="src")
    sc.chamber("hc-e", 8, 0, dn=250, upstream="hn-e")
    sc.pipe("hn-w", [(0, 0), (-8, 0)], dn=250, flow=50.0, upstream="src")
    sc.chamber("hc-w", -8, 0, dn=250, upstream="hn-w")
    sc.oks("oks-1", cp=OKS_CP, flow=OKS_FLOW_TPH)
    sc.existing_oks("bld-1", [[(40, -80), (80, -80), (80, -40), (40, -40)]])
    sc.restriction("park-1", "park", Polygon([(-80, 40), (-40, 40), (-40, 80), (-80, 80)]))
    return sc


def feature(sc: Scene, id: str) -> dict[str, Any]:
    return next(f for f in sc.features if f["properties"].get("id") == id)


def props(sc: Scene, id: str) -> dict[str, Any]:
    return feature(sc, id)["properties"]


def rejected(feature_id: str, field: str) -> Expect:
    return Expect(exit_code=2, diagnostics=[(feature_id, field)], no_output=True)


def without(id: str, field: str) -> tuple[Scene, Expect]:
    sc = valid_scene()
    props(sc, id).pop(field)
    return sc, rejected(id, field)


@scenario("S12-01", tz=["TZ-4", "TZ-92"], title="у участка сети нет diameter: код 2 и диагностика hn-e diameter")
def pipe_without_diameter() -> tuple[Scene, Expect]:
    return without("hn-e", "diameter")


def inferred(id: str, field: str) -> tuple[Scene, Expect]:
    """Атрибут, который в датасете организаторов не приходит: сервис выводит его и считает варианты (docs/interpretation.md)."""
    sc = valid_scene()
    props(sc, id).pop(field)
    return sc, Expect(exit_code=0, unconnected=[], costs_by_formula=True)


@scenario("S12-02", tz=["TZ-4", "TZ-92"], title="у участка сети нет flow_tph, как в датасете: расход выводится по Ду, код 0")
def pipe_without_flow() -> tuple[Scene, Expect]:
    return inferred("hn-w", "flow_tph")


@scenario("S12-03", tz=["TZ-4", "TZ-7"], title="у камеры нет upstream_object_id, как в датасете: направление выводится обходом от источника, код 0")
def chamber_without_upstream() -> tuple[Scene, Expect]:
    return inferred("hc-e", "upstream_object_id")


@scenario("S12-04", tz=["TZ-4"], title="у ОКС нет flow_tph: код 2 и диагностика oks-1 flow_tph")
def oks_without_flow() -> tuple[Scene, Expect]:
    return without("oks-1", "flow_tph")


@scenario("S12-05", tz=["TZ-4"], title="у ОКС нет heat_load: атрибут справочный, расчёт идёт")
def oks_without_heat_load() -> tuple[Scene, Expect]:
    sc = valid_scene()
    props(sc, "oks-1").pop("heat_load")
    return sc, Expect(unconnected=[])


@scenario("S12-06", tz=["TZ-4"], title="у точки подключения нет oks_id: код 2 и диагностика oks-1-cp oks_id")
def connection_point_without_oks_id() -> tuple[Scene, Expect]:
    return without("oks-1-cp", "oks_id")


@scenario("S12-07", tz=["TZ-4", "TZ-92"], title="у ограничения нет restriction_type: код 2 и диагностика park-1 restriction_type")
def restriction_without_type() -> tuple[Scene, Expect]:
    return without("park-1", "restriction_type")


@scenario("S12-08", tz=["TZ-4", "TZ-5"], title="у объекта нет id: код 2 и диагностика по порядковому номеру объекта")
def feature_without_id() -> tuple[Scene, Expect]:
    sc = valid_scene()
    sc.restriction("park-2", "park", Polygon([(-80, -80), (-40, -80), (-40, -40), (-80, -40)]))
    props(sc, "park-2").pop("id")
    # Объект без id диагностика называет номером в массиве features с единицы: `#<n>` по AC-1.4 backend-core.
    return sc, rejected(f"#{len(sc.features)}", "id")


@scenario("S12-09", tz=["TZ-4", "TZ-92"], title="у объекта нет object_type: код 2 и диагностика bld-1 object_type")
def feature_without_object_type() -> tuple[Scene, Expect]:
    return without("bld-1", "object_type")


@scenario("S12-10", tz=["TZ-4", "TZ-92"], title="diameter камеры 250.5 не целое: код 2 и диагностика hc-w diameter")
def fractional_chamber_diameter() -> tuple[Scene, Expect]:
    sc = valid_scene()
    props(sc, "hc-w")["diameter"] = 250.5
    return sc, rejected("hc-w", "diameter")


@scenario("S12-11", tz=["TZ-5", "TZ-92"], title="два участка сети с одним id: код 2 и диагностика id")
def duplicate_pipe_id() -> tuple[Scene, Expect]:
    sc = valid_scene()
    sc.pipe("hn-e", [(8, 0), (28, 0)], dn=250, flow=20.0, upstream="hc-e")
    return sc, rejected("hn-e", "id")


@scenario("S12-12", tz=["TZ-5"], title="существующий ОКС с id перспективного: код 2 и диагностика id")
def duplicate_id_across_types() -> tuple[Scene, Expect]:
    sc = valid_scene()
    props(sc, "bld-1")["id"] = "oks-1"
    return sc, rejected("oks-1", "id")


@scenario("S12-13", tz=["TZ-4", "TZ-92"], title="oks_id точки подключения ссылается на несуществующий ОКС: код 2 и диагностика oks_id")
def dangling_oks_id() -> tuple[Scene, Expect]:
    sc = valid_scene()
    props(sc, "oks-1-cp")["oks_id"] = "oks-404"
    return sc, rejected("oks-1-cp", "oks_id")


@scenario("S12-14", tz=["TZ-7", "TZ-92"], title="upstream_object_id участка ссылается на несуществующий объект: код 2 и диагностика")
def dangling_upstream() -> tuple[Scene, Expect]:
    sc = valid_scene()
    props(sc, "hn-w")["upstream_object_id"] = "src-404"
    return sc, rejected("hn-w", "upstream_object_id")


@scenario("S12-15", tz=["TZ-7"], title="upstream_object_id камеры ссылается на перспективный ОКС: код 2 и диагностика")
def upstream_to_oks() -> tuple[Scene, Expect]:
    sc = valid_scene()
    props(sc, "hc-w")["upstream_object_id"] = "oks-1"
    return sc, rejected("hc-w", "upstream_object_id")


@scenario("S12-16", tz=["TZ-7", "TZ-92"], title="участок и камера ссылаются друг на друга и не доходят до источника: код 2 и диагностика цикла")
def upstream_cycle() -> tuple[Scene, Expect]:
    # Диагностика ожидается у первого в файле объекта цикла: чтение потоковое, порядок файла — единственный однозначный выбор.
    sc = valid_scene()
    sc.pipe("hn-x", [(28, 0), (48, 0)], dn=250, flow=20.0, upstream="hc-x")
    sc.chamber("hc-x", 28, 0, dn=250, upstream="hn-x")
    return sc, rejected("hn-x", "upstream_object_id")


@scenario("S12-17", tz=["TZ-4", "TZ-92"], title="неизвестный object_type valve: код 2 и диагностика object_type")
def unknown_object_type() -> tuple[Scene, Expect]:
    sc = valid_scene()
    sc.add(shapely.Point(4, 4), id="valve-1", object_type="valve")
    return sc, rejected("valve-1", "object_type")


class GeometryCollectionScene(Scene):
    def to_geojson(self) -> dict[str, Any]:
        return {"type": "GeometryCollection", "features": self.features}


@scenario("S12-18", tz=["TZ-1", "TZ-92"], title="корень файла GeometryCollection вместо FeatureCollection: код 2 и диагностика type")
def root_not_feature_collection() -> tuple[Scene, Expect]:
    sc = GeometryCollectionScene()
    sc.features = valid_scene().features
    return sc, rejected("#0", "type")


@scenario("S12-19", tz=["TZ-1", "TZ-2"], title="координаты участка в метрах EPSG:32637 вместо градусов EPSG:4326: код 2 и диагностика geometry")
def coordinates_not_wgs84() -> tuple[Scene, Expect]:
    sc = valid_scene()
    feature(sc, "hn-e")["geometry"] = {"type": "LineString", "coordinates": [[ORIGIN_E, ORIGIN_N], [ORIGIN_E + 8, ORIGIN_N]]}
    return sc, rejected("hn-e", "geometry")


@scenario("S12-20", tz=["TZ-1", "TZ-3"], title="перспективный и существующий ОКС MultiPolygon: вход принят, ОКС подключён")
def multipolygon_accepted() -> tuple[Scene, Expect]:
    sc = valid_scene()
    feature(sc, "oks-1")["geometry"] = geojson(MultiPolygon([
        Polygon([(60, 30), (80, 30), (80, 50), (60, 50)]),
        Polygon([(90, 30), (100, 30), (100, 40), (90, 40)]),
    ]))
    feature(sc, "bld-1")["geometry"] = geojson(MultiPolygon([
        Polygon([(40, -80), (80, -80), (80, -40), (40, -40)]),
        Polygon([(100, -80), (120, -80), (120, -60), (100, -60)]),
    ]))
    return sc, Expect(
        exit_code=0,
        tie_ins=[{"existing_object_id": "hc-e", "existing_object_type": "heat_chamber"}],
        segment_dn={"oks-1-cp": diameter_for(rules(), OKS_FLOW_TPH)},
    )


@scenario("S12-21", tz=["TZ-2"], title="длина и стоимость участка считаются в метрах EPSG:32637")
def lengths_in_utm_meters() -> tuple[Scene, Expect]:
    sc = valid_scene()
    length = round(math.hypot(OKS_CP[0] - 8, OKS_CP[1]), 2)
    return sc, Expect(exit_code=0, summary={"new_network_length": length, "calculated_cost": straight_cost(length, OKS_FLOW_TPH)})


@scenario("S12-22", tz=["TZ-4", "TZ-92"], title="flow_tph ОКС передан строкой \"100\": код 2 и диагностика oks-1 flow_tph")
def oks_flow_as_string() -> tuple[Scene, Expect]:
    sc = valid_scene()
    props(sc, "oks-1")["flow_tph"] = "100"
    return sc, rejected("oks-1", "flow_tph")


@scenario("S12-23", tz=["TZ-3", "TZ-92"], title="камера с геометрией LineString вместо Point: код 2 и диагностика hc-e geometry")
def chamber_with_line_geometry() -> tuple[Scene, Expect]:
    sc = valid_scene()
    feature(sc, "hc-e")["geometry"] = geojson(shapely.LineString([(8, 0), (8, 5)]))
    return sc, rejected("hc-e", "geometry")
