"""Кандидат dp (T-3): точные диаметры при фиксированном лесе против планировщика сервиса и модели."""
import json
import subprocess
from pathlib import Path

import pytest
from pyproj import Transformer

from heatopt import model, rules_check, service, tm
from heatopt.candidates import dp
from heatopt.graph import VisGraph
from heatopt.scene import Scene

ROOT = Path(__file__).resolve().parents[3]
TO_WGS = Transformer.from_crs("EPSG:32637", "EPSG:4326", always_xy=True)
ORIGIN = (413000.0, 6179000.0)
CHAMBER = (400.0, 0.0)
# у сервиса вставка на ступень выше длиной 2 м (DiameterPlanner.MIN_PIECE_M), у планировщика модели тоже (INSERT_M)
SERVICE_INSERT_M = 2.0


def point(xy):
    return {"type": "Point", "coordinates": wgs(xy)}


def wgs(xy):
    lon, lat = TO_WGS.transform(ORIGIN[0] + xy[0], ORIGIN[1] + xy[1])
    return [round(lon, 9), round(lat, 9)]


def feature(geometry, **props):
    return {"type": "Feature", "geometry": geometry, "properties": props}


def write_scene(path: Path, terminals: list[tuple[float, float]]) -> Scene:
    """Труба DN200 от источника к камере, ОКС по 1,5 т/ч: деревья из них целиком DN50 с пределом 181 м."""
    features = [
        feature(point((0.0, 0.0)), id="src", object_type="source"),
        feature({"type": "LineString", "coordinates": [wgs((0.0, 0.0)), wgs(CHAMBER)]},
                id="net_1", object_type="heat_network", diameter=200, flow_tph=20.0, upstream_object_id="src"),
        feature(point(CHAMBER), id="ch_1", object_type="heat_chamber", diameter=200, upstream_object_id="net_1"),
    ]
    for n, (x, y) in enumerate(terminals, 1):
        ring = [wgs(xy) for xy in ((x - 10, y), (x + 10, y), (x + 10, y + 20), (x - 10, y + 20), (x - 10, y))]
        features.append(feature({"type": "Polygon", "coordinates": [ring]}, id=f"oks_{n}", object_type="oks_future", flow_tph=1.5, heat_load=1.0))
        features.append(feature(point((x, y)), id=f"cp_{n}", object_type="oks_connection_point", oks_id=f"oks_{n}"))
    path.write_text(json.dumps({"type": "FeatureCollection", "features": features}), encoding="utf-8")
    return Scene.load(path)


def local(xy):
    return (ORIGIN[0] + xy[0], ORIGIN[1] + xy[1])


@pytest.fixture(scope="module")
def jar():
    if not (ROOT / service.JAR).exists():
        subprocess.run(["bash", "-c", "source scripts/gates/env.sh && ensure_jar"], cwd=ROOT, check=True)
    return ROOT / service.JAR


def validator_clean(scene: Scene, cost: model.CostBreakdown) -> None:
    assert not cost.violations
    violations = model.validate(scene, cost)
    assert violations["diameter"] == 0 and violations["length_limit"] == 0, violations
    assert not any(violations.values()), violations


def test_not_worse_than_service_on_its_topology(jar, tmp_path):
    """Дерево сервиса из 516 м DN50 с двумя ветками. Сравнение идёт на топологии сервиса: те же рёбра, камеры,
    врезка и реконструкция, меняется только разбиение на куски DN50 и DN65. Полная S сравнивала бы ещё и
    маршрутизатор tm с маршрутизатором сервиса, и выигрыш или проигрыш диаметров в ней не виден. Минимальная
    вставка берётся сервисная, 2 м, иначе сравнивались бы правила, а не расстановка.

    Сервис ставит три вставки. Двух достаточно и меньше нельзя: ствол от врезки до камеры ветвления длиннее
    181 м, а без вставки на ветке цепочка в камере ветвления — обе ветки плюс кусок ствола, больше 226 м."""
    scene = write_scene(tmp_path / "two-branches.geojson", [(310.0, 330.0), (490.0, 330.0)])
    served = service.solve(scene, jar, tmp_path)
    served_cost = model.cost(served, scene)
    validator_clean(scene, served_cost)
    segments = served_cost.variant.segments
    assert {s.props["diameter"] for s in segments} == {50, 65} and len(served_cost.variant.tie_ins) == 1
    assert len(served_cost.variant.chambers) == 1 and served_cost.new_length > 500
    assert sum(s.props["length"] for s in segments if s.props["diameter"] == 65) == pytest.approx(3 * SERVICE_INSERT_M)

    planned = dp.plan_diameters(scene, served, scene.rules, SERVICE_INSERT_M)
    assert planned.meta["insert_m"] == pytest.approx(2 * SERVICE_INSERT_M)
    planned_cost = model.cost(planned, scene)
    validator_clean(scene, planned_cost)
    assert planned_cost.new_length == pytest.approx(served_cost.new_length, abs=0.05)
    assert planned_cost.chamber_construction == served_cost.chamber_construction
    assert planned_cost.construction <= served_cost.construction + 1.0
    assert planned_cost.total <= served_cost.total + 1.0

    with_rule = model.cost(dp.plan_diameters(scene, served, scene.rules), scene)
    validator_clean(scene, with_rule)


def shared_chamber_forest() -> model.Solution:
    """Два дерева из камеры ch_1: вверх ствол 100 м и две ветки по 247 м, вниз ребро 120 м. Все рёбра DN50,
    цепочки сходятся в узле ветвления и в камере, общей для двух деревьев."""
    ties = [model.TieIn("ch_1", "heat_chamber", local(CHAMBER)), model.TieIn("ch_1", "heat_chamber", local(CHAMBER))]
    junction = (400.0, 100.0)
    edges = [
        [local(CHAMBER), local(junction)],
        [local(junction), local((310.0, 330.0))],
        [local(junction), local((490.0, 330.0))],
        [local(CHAMBER), local((400.0, -120.0))],
    ]
    return model.Solution(edges, ties)


def test_fixed_forest_optimum_and_validator(tmp_path):
    """Оптимум посчитан вручную, предел цепочки с запасом 180,9 м. Ветки длиннее предела, на каждой нужна
    вставка, и над вставкой длины w остаётся не меньше 246,98 − 180,9 − w, при w = 2 м это 64,08 м. Если на
    стволе вставки нет, в одну цепочку через узел ветвления и камеру попадают обе ветки, ствол 100 м и хотя бы
    10 м ребра вниз (вставка не ближе 10 м к врезке): это больше предела, пока вставки веток не удлинены
    на 57 м. Вставка на стволе делит цепочку: у узла ветвления 128,16 м плюс низ ствола, у камеры верх ствола
    45,26 м плюс ребро вниз 120 м. Значит, оптимум — три вставки минимальной длины: 6 м при 2 м, 30,03 м при 10,01 м.
    Планировщик модели на этом лесе ставит вставки жадно, динамика не должна быть дороже."""
    scene = write_scene(tmp_path / "shared-chamber.geojson", [(310.0, 330.0), (490.0, 330.0), (400.0, -120.0)])
    forest = shared_chamber_forest()
    planner_cost = model.cost(forest, scene)
    validator_clean(scene, planner_cost)

    planned = dp.plan_diameters(scene, forest, scene.rules, SERVICE_INSERT_M)
    assert planned.meta["insert_m"] == pytest.approx(3 * SERVICE_INSERT_M)
    planned_cost = model.cost(planned, scene)
    validator_clean(scene, planned_cost)
    assert planned_cost.total <= planner_cost.total + 1.0

    default = dp.plan_diameters(scene, forest, scene.rules)
    assert default.meta["insert_m"] == pytest.approx(3 * dp.MIN_INSERT_M)
    validator_clean(scene, model.cost(default, scene))


def test_solve_reproducible_and_valid(tmp_path):
    """Цикл целиком на ручной сцене: два запуска совпадают, все ОКС подключены, решение проходит валидатор и не хуже
    базового леса."""
    scene = write_scene(tmp_path / "solve.geojson", [(310.0, 330.0), (490.0, 330.0), (400.0, -120.0)])
    graph = VisGraph.build(scene, tangent=True)
    _, base, _ = tm.baseline(scene, graph)
    first = dp.solve(scene, graph, scene.rules, None, dp.DEFAULT_BUDGET, 1)
    second = dp.solve(scene, graph, scene.rules, None, dp.DEFAULT_BUDGET, 1)
    assert (first.edges, first.dn, first.tie_ins) == (second.edges, second.dn, second.tie_ins)
    cost = model.cost(first, scene)
    validator_clean(scene, cost)
    assert not cost.unconnected
    assert cost.score_value <= base.score_value + 1e-9


def test_quality_on_prefers_long_inserts(tmp_path):
    """RQ-10 штрафует вставку короче 10 м на 42 012 руб. На DN50 удлинить вставку с 2 до 10 м дешевле
    (8 м × 4 608 руб.), поэтому с правилами разумности решение берёт диаметры динамики, а по одной S —
    лес с 2-метровыми вставками планировщика модели."""
    scene = write_scene(tmp_path / "quality.geojson", [(310.0, 330.0), (490.0, 330.0)])
    graph = VisGraph.build(scene, tangent=True)
    quality = rules_check.load(ROOT / "docs/routing-quality.md")
    plain = dp.solve(scene, graph, scene.rules, None, dp.DEFAULT_BUDGET, 1)
    sized = dp.solve(scene, graph, scene.rules, quality, dp.DEFAULT_BUDGET, 1)
    assert not plain.meta["sized"] and sized.meta["sized"]
    plain_cost, sized_cost = model.cost(plain, scene), model.cost(sized, scene)
    validator_clean(scene, sized_cost)
    assert plain_cost.score_value < sized_cost.score_value
    assert model.objective(sized_cost, scene, quality) < model.objective(plain_cost, scene, quality)
