"""Дымовой тест T-0: сцена S сид 1 → граф → сервис → единая модель стоимости → базовый лес."""
import json
import subprocess
import sys
from pathlib import Path

import pytest

from heatopt import model, service, tm
from heatopt.graph import VisGraph
from heatopt.scene import Scene, file_hash, generate

ROOT = Path(__file__).resolve().parents[3]


@pytest.fixture(scope="module")
def scene(tmp_path_factory):
    out = tmp_path_factory.mktemp("scenes")
    path = generate("S", 1, out)
    again = generate("S", 1, tmp_path_factory.mktemp("again"))
    assert file_hash(path) == file_hash(again)
    check = subprocess.run([sys.executable, "-m", "heatsynth.check", str(path)], capture_output=True, text=True, cwd=ROOT)
    assert check.returncode == 0, check.stderr
    return Scene.load(path)


@pytest.fixture(scope="module")
def jar():
    if not (ROOT / service.JAR).exists():
        subprocess.run(["bash", "-c", "source scripts/gates/env.sh && ensure_jar"], cwd=ROOT, check=True)
    return ROOT / service.JAR


def test_graph(scene, tmp_path):
    graph = VisGraph.build(scene)
    kinds = {n.kind for n in graph.nodes}
    assert {"terminal", "reflex"} <= kinds and graph.tie_nodes()
    assert len(graph.u) > 0 and (graph.cost_len >= graph.length - 1e-9).all()
    graph.to_json(tmp_path / "graph.json")
    data = json.loads((tmp_path / "graph.json").read_text())
    assert len(data["nodes"]) == len(graph.nodes) and len(data["edges"]) == len(graph.u)


def test_service_cost_matches_summary(scene, jar, tmp_path):
    solution = service.solve(scene, jar, tmp_path)
    cost = model.cost(solution, scene)
    declared = solution.meta["declared"]
    assert abs(cost.total - declared["calculated_cost"]) <= 1.0
    assert abs(cost.length - declared["length"]) <= 0.05
    assert abs(cost.score_value - declared["score"]) <= 0.001
    assert not cost.violations
    assert not any(model.validate(scene, cost).values())


def test_baseline_valid(scene):
    graph = VisGraph.build(scene)
    solution, cost, trees = tm.baseline(scene, graph)
    assert not cost.unconnected and not cost.violations
    assert not any(model.validate(scene, cost).values())
    metrics = model.shape_metrics(scene, cost.variant)
    assert metrics["tie_ins"] == len(solution.tie_ins) >= 1
