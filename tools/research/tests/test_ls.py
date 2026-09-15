"""Кандидат ls (T-2) на двух ручных сценах: общий ствол дешевле раздельных деревьев и обход препятствия."""
import json
import random
from pathlib import Path

import pytest
from pyproj import Transformer

from heatopt import (
    model,
    tm,
)
from heatopt.candidates import ls
from heatopt.candidates.__main__ import fingerprint
from heatopt.graph import VisGraph
from heatopt.scene import Scene

TO_WGS = Transformer.from_crs("EPSG:32637", "EPSG:4326", always_xy=True)
ORIGIN = (410000.0, 6180000.0)
BUDGET = 40
SEED = 7
DIRECT_M = 300.0
TURN_RUB = 2_000_000.0
QUALITY = [model.QualityRule("RQ-T", "поворот трассы", "A", "тест", "число поворотов", "0", "objective", "turn",
                             TURN_RUB, "тест")]


def wgs(x: float, y: float) -> list[float]:
    lon, lat = TO_WGS.transform(ORIGIN[0] + x, ORIGIN[1] + y)
    return [round(lon, 9), round(lat, 9)]


def feature(fid: str, object_type: str, geometry: str, coords, **props) -> dict:
    if geometry == "Point":
        coordinates = wgs(*coords)
    elif geometry == "LineString":
        coordinates = [wgs(*xy) for xy in coords]
    else:
        x0, y0, x1, y1 = coords
        coordinates = [[wgs(x0, y0), wgs(x1, y0), wgs(x1, y1), wgs(x0, y1), wgs(x0, y0)]]
    return {"type": "Feature", "geometry": {"type": geometry, "coordinates": coordinates},
            "properties": {"id": fid, "object_type": object_type, **props}}


def network() -> list[dict]:
    """Источник, камера и прямая труба Ду 300 с запасом пропускной способности: реконструкции нет."""
    return [
        feature("src-1", "source", "Point", (-400, 0)),
        feature("hn-1", "heat_network", "LineString", [(-400, 0), (-380, 0)], diameter=300, flow_tph=100.0,
                upstream_object_id="src-1"),
        feature("hc-1", "heat_chamber", "Point", (-380, 0), diameter=300, upstream_object_id="hn-1"),
        feature("hn-2", "heat_network", "LineString", [(-380, 0), (400, 0)], diameter=300, flow_tph=100.0,
                upstream_object_id="hc-1"),
    ]


def oks(n: int, x: float, y: float, flow: float) -> list[dict]:
    return [
        feature(f"oks-{n}", "oks_future", "Polygon", (x - 20, y, x + 20, y + 40), flow_tph=flow),
        feature(f"cp-{n}", "oks_connection_point", "Point", (x, y), oks_id=f"oks-{n}"),
    ]


SCENES = {
    # два ОКС за парком: общий путь вокруг угла парка и одна врезка дешевле двух деревьев
    "trunk": network() + [feature("rs-1", "restriction", "Polygon", (-150, 100, 150, 160), restriction_type="park")]
    + oks(1, -40, 290, 2.0) + oks(2, 40, 290, 1.5),
    # прямой путь от ОКС к трубе закрыт запретной площадкой
    "detour": network() + [feature("rs-1", "restriction", "Polygon", (-100, 80, 100, 220), restriction_type="prohibited_site")]
    + oks(1, 0, DIRECT_M, 2.0),
}


def load(name: str, folder: Path) -> tuple[Scene, VisGraph]:
    path = folder / f"{name}.geojson"
    path.write_text(json.dumps({"type": "FeatureCollection", "features": SCENES[name]}), encoding="utf-8")
    scene = Scene.load(path)
    return scene, VisGraph.build(scene, tangent=True)


@pytest.fixture(scope="module", params=sorted(SCENES))
def case(request, tmp_path_factory):
    scene, graph = load(request.param, tmp_path_factory.mktemp("ls"))
    return request.param, scene, graph, ls.solve(scene, graph, scene.rules, None, BUDGET, SEED)


def test_not_worse_than_baseline_and_valid(case):
    _, scene, graph, solution = case
    _, base, _ = tm.baseline(scene, graph)
    cost = model.cost(solution, scene)
    assert not cost.violations and not cost.unconnected
    assert not any(model.validate(scene, cost).values())
    assert cost.score_value <= base.score_value + 1e-9


def test_reproducible_with_same_seed(case):
    _, scene, graph, solution = case
    assert fingerprint(ls.solve(scene, graph, scene.rules, None, BUDGET, SEED)) == fingerprint(solution)


def descend(scene: Scene, graph: VisGraph, trees: list[tm.Tree]) -> tuple[ls.Plan, ls.Plan]:
    search = ls.Search(scene, graph, scene.rules, None, random.Random(SEED))
    search.left = BUDGET
    start = search.plan(trees, "test")
    return start, search.descend(start)


def test_scene_answer(case):
    """Общий ствол: поиск от раздельных деревьев сливает их в одно. Обход: поиск от дерева на дальней камере
    переносит его на врезку в трубу, трасса обходит площадку."""
    name, scene, graph, solution = case
    _, base, _ = tm.baseline(scene, graph)
    builder, recon = tm.TreeBuilder(scene, graph), tm.Recon(scene, graph.rules)
    if name == "trunk":
        separate = tm.forest(builder, recon, [["cp-1"], ["cp-2"]])
        start, end = descend(scene, graph, separate)
        assert len(start.trees) == 2 and len(end.trees) == 1
        assert len(solution.tie_ins) == 1
    else:
        chamber = next(n for n in graph.tie_nodes() if graph.nodes[n].kind == "tie_chamber")
        start, end = descend(scene, graph, [builder.build(chamber, ["cp-1"])])
        assert graph.nodes[end.trees[0].root_node].kind == "tie_pipe"
        assert model.cost(solution, scene).new_length > DIRECT_M + 1.0
    assert end.violations == 0 and end.value < start.value
    assert end.value <= base.score_value + 1e-9


def test_quality_mode_counts_turn_price(tmp_path):
    """Режим quality: целевая функция — S плюс цена поворотов, решение не хуже базового леса по ней."""
    scene, graph = load("detour", tmp_path)
    solution = ls.solve(scene, graph, scene.rules, QUALITY, BUDGET, SEED)
    _, base, _ = tm.baseline(scene, graph)
    cost = model.cost(solution, scene)
    assert not cost.violations and not any(model.validate(scene, cost).values())

    assert model.objective(cost, scene, QUALITY) <= model.objective(base, scene, QUALITY) + 1e-9
    assert fingerprint(ls.solve(scene, graph, scene.rules, QUALITY, BUDGET, SEED)) == fingerprint(solution)
