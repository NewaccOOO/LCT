"""Кандидат bend: путь с меньшим числом поворотов при почти равной длине, коридоры вдоль дорог, воспроизводимость."""
import math

import numpy as np
import pytest
from heatcheck.model import diameter_row
from shapely.geometry import box

from heatopt import (
    model,
    tm,
)
from heatopt.candidates import bend
from heatopt.candidates.__main__ import fingerprint
from heatopt.graph import (
    Node,
    VisGraph,
)
from heatopt.model import QualityRule
from heatopt.scene import (
    Scene,
    generate,
    load_rules,
)

DN = 50
# путь через 3 и 4 на 0,3 м короче пути через 2, но поворачивает дважды
SHORTER_BY_M = 0.3
HALF_M = math.dist((0, 0), (50, 30))
LOW_Y = -math.sqrt((HALF_M - 25 - SHORTER_BY_M / 2) ** 2 - 25 ** 2)


def handmade(nodes: list[Node], pairs: list[tuple[int, int]]) -> VisGraph:
    xy = np.array([n.xy for n in nodes])
    u, v = np.array([a for a, _ in pairs]), np.array([b for _, b in pairs])
    length = np.hypot(*(xy[v] - xy[u]).T)
    return VisGraph(None, load_rules(), DN, None, None, nodes, u, v, length, np.zeros(len(u)), np.ones(len(u)), length.copy())


def turns(graph: VisGraph, path: list[int]) -> int:
    xy = [graph.nodes[i].xy for i in path]
    return sum(model.deflection_deg(a, b, c) >= model.MIN_TURN_DEG for a, b, c in zip(xy, xy[1:], xy[2:]))


def test_prefers_fewer_turns():
    nodes = [Node(0, 0, "terminal", "cp-1"), Node(100, 0, "tie_chamber", "hc-1", capacity=2),
             Node(50, 30, "reflex"), Node(25, LOW_Y, "reflex"), Node(75, LOW_Y, "reflex")]
    graph = handmade(nodes, [(0, 2), (2, 1), (0, 3), (3, 4), (4, 1)])
    weights = tm.edge_weights(graph, DN)
    one_turn = weights[0] + weights[1]
    two_turns = weights[2] + weights[3] + weights[4]
    assert two_turns < one_turn

    _, pred = tm.dijkstra(graph, 0, weights)
    plain = tm.path_nodes(graph, pred, 0, 1)
    assert plain == [0, 3, 4, 1] and turns(graph, plain) == 2

    meter = tm.meters(graph.rules, 1.0) + tm.rub(graph.rules, diameter_row(graph.rules, DN)["new_rub_m"])
    found = bend.search(bend.Lattice(graph, []), 0, np.asarray(weights), meter, meter)
    path = found.path(1)
    assert path == [0, 2, 1] and turns(graph, path) == 1
    # поворот 62° — без излома, повороты по 41° на пути 3–4 — изломы
    assert found.dist[1] == pytest.approx(one_turn + meter)
    assert tm.path_nodes(graph, found.pred, 0, 1)[-1] == 1


def test_corridor_share():
    road = box(0, 0, 200, 10)
    nodes = [Node(0, 15, "road"), Node(100, 16, "road"), Node(0, 30, "road"), Node(100, 30, "road"),
             Node(120, 20, "road"), Node(120, -20, "road")]
    graph = handmade(nodes, [(0, 1), (2, 3), (4, 5)])
    share = bend.corridor_share(graph, [road])
    assert share[0] == pytest.approx(1.0)
    assert share[1] == 0.0 and share[2] == 0.0


@pytest.fixture(scope="module")
def scene_graph():
    scene = Scene.load(generate("S", 1))
    return scene, VisGraph.build(scene, tangent=True)


def test_reproducible_and_valid(scene_graph):
    scene, graph = scene_graph
    _, base, _ = tm.baseline(scene, graph)
    first = bend.solve(scene, graph, scene.rules, None, 3, 7)
    second = bend.solve(scene, graph, scene.rules, None, 3, 7)
    assert fingerprint(first) == fingerprint(second)
    cost = model.cost(first, scene)
    assert not cost.violations and not any(model.validate(scene, cost).values())
    assert cost.score_value <= base.score_value + 1e-9


def test_quality_mode(scene_graph):
    scene, graph = scene_graph
    quality = [
        QualityRule("RQ-1", "поворот", "A", "тест", "", "", "objective", "turn", 150_000.0, "тест"),
        QualityRule("RQ-2", "излом", "A", "тест", "", "", "objective", "kink", 300_000.0, "тест"),
        QualityRule("RQ-3", "камера", "B", "тест", "", "", "objective", "chamber", 1_000_000.0, "тест"),
    ]
    _, base, _ = tm.baseline(scene, graph)
    first = bend.solve(scene, graph, scene.rules, quality, 3, 7)
    second = bend.solve(scene, graph, scene.rules, quality, 3, 7)
    assert fingerprint(first) == fingerprint(second)
    cost = model.cost(first, scene)
    assert not cost.violations
    assert bend.objective(cost, scene, quality) <= bend.objective(base, scene, quality) + 1e-9
