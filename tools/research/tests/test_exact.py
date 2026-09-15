"""AC-3.1: точная модель возвращает оптимум, посчитанный вручную в файле сцены (ключ expected), LP-граница не выше."""
import json
from pathlib import Path

import pytest

from heatopt import exact
from heatopt.graph import (
    VisGraph,
    smallest_dn,
)
from heatopt.scene import Scene

SCENES = ("one-oks-straight", "shared-trunk", "detour", "chamber-vs-pipe", "recon-choice")
SCENES_DIR = Path(__file__).parent / "scenes"
TIME_LIMIT_S = 120.0
COST_TOL_RUB = 1.0
SCORE_TOL = 0.001
BOUND_TOL = 1e-6


@pytest.mark.parametrize("name", SCENES)
def test_hand_optimum(name):
    path = SCENES_DIR / f"{name}.geojson"
    expected = json.loads(path.read_text(encoding="utf-8"))["expected"]
    scene = Scene.load(path)
    graph = VisGraph.build(scene, dn_guess=smallest_dn(scene, scene.rules))
    found = exact.solve(scene, graph, scene.rules, TIME_LIMIT_S)
    lp = exact.lp_bound(scene, graph, scene.rules, TIME_LIMIT_S)
    assert found.status == exact.Status.OPTIMAL
    assert abs(found.cost_rub - expected["cost_rub"]) <= COST_TOL_RUB
    assert abs(found.objective - expected["score"]) <= SCORE_TOL
    assert lp.status == exact.Status.OPTIMAL
    assert lp.bound <= found.bound + BOUND_TOL
