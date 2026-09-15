"""Проверка кандидата: python -m heatopt.candidates <name> --check."""
import argparse
import importlib
import json
import sys
import time

from heatopt import (
    model,
    tm,
)
from heatopt.graph import VisGraph
from heatopt.scene import (
    Scene,
    generate,
)

CHECK_SCENES = (("S", 1), ("S", 2), ("M", 1), ("L", 1))
TIME_LIMIT_S = 100.0
SCORE_TOL = 1e-9


def fingerprint(solution: model.Solution) -> str:
    edges = [[(round(x, 6), round(y, 6)) for x, y in edge] for edge in solution.edges]
    ties = [(t.existing_id, t.existing_type, round(t.point[0], 6), round(t.point[1], 6)) for t in solution.tie_ins]
    return json.dumps([edges, ties, sorted(solution.dn.items())])


def check(name: str) -> None:
    """На S-1, S-2, M-1, L-1: воспроизводимость, сборка без нарушений, S не хуже базового леса, время на L."""
    module = importlib.import_module(f"heatopt.candidates.{name}")
    failures = []
    for cls, seed in CHECK_SCENES:
        scene = Scene.load(generate(cls, seed))
        graph = VisGraph.build(scene, tangent=True)
        _, base, _ = tm.baseline(scene, graph)
        started = time.perf_counter()
        first = module.solve(scene, graph, scene.rules, None, module.DEFAULT_BUDGET, 1)
        elapsed = time.perf_counter() - started
        second = module.solve(scene, graph, scene.rules, None, module.DEFAULT_BUDGET, 1)
        cost = model.cost(first, scene)
        violations = {rule: count for rule, count in model.validate(scene, cost).items() if count}
        label = f"{cls}-{seed}"
        print(f"{label}: S={cost.score_value:.3f} baseline={base.score_value:.3f} time={elapsed:.1f}s violations={violations}")
        if fingerprint(first) != fingerprint(second):
            failures.append(f"{label}: два запуска с одним seed и budget дали разные решения")
        if cost.violations:
            failures.append(f"{label}: модель нашла нарушения {cost.violations[:3]}")
        if cls != "L" and violations:
            failures.append(f"{label}: валидатор нашёл нарушения {violations}")
        if len(cost.unconnected) > len(base.unconnected):
            failures.append(f"{label}: неподключённых ОКС {len(cost.unconnected)}, у базового леса {len(base.unconnected)}")
        if cost.score_value > base.score_value + SCORE_TOL:
            failures.append(f"{label}: S {cost.score_value:.4f} хуже базового леса {base.score_value:.4f}")
        if cls == "L" and elapsed > TIME_LIMIT_S:
            failures.append(f"{label}: {elapsed:.1f} с больше {TIME_LIMIT_S} с")
    for failure in failures:
        print(failure)
    if failures:
        sys.exit(1)
    print(f"CANDIDATE CHECK OK name={name} scenes={len(CHECK_SCENES)}")


def main() -> None:
    parser = argparse.ArgumentParser(prog="heatopt.candidates", description="Проверка кандидата на четырёх сценах")
    parser.add_argument("name", choices=["ls", "dp", "bend", "master"])
    parser.add_argument("--check", action="store_true", required=True)
    args = parser.parse_args()
    check(args.name)


if __name__ == "__main__":
    main()
