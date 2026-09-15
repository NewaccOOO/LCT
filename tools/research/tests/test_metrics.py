"""Негативные контроли калькулятора метрик и проверки таблицы правил (AC-2.1, AC-2.2)."""
import json
import subprocess
import sys
from pathlib import Path

import pytest
from heatcheck.model import (
    Feature,
    load_output,
)
from shapely.geometry import (
    LineString,
    Point,
)

from heatopt import (
    metrics,
    model,
    rules_check,
)
from heatopt.scene import Scene

ROOT = Path(__file__).resolve().parents[3]
DOC = Path("docs/routing-quality.md")


@pytest.fixture(autouse=True)
def root(monkeypatch):
    monkeypatch.chdir(ROOT)


@pytest.fixture
def text() -> str:
    return DOC.read_text(encoding="utf-8")


def rule_lines(text: str) -> list[str]:
    return [line for line in text.splitlines() if line.startswith("| RQ-")]


def with_cell(text: str, line: str, column: str, value: str) -> str:
    parts = line.split("|")
    parts[rules_check.COLUMNS.index(column) + 1] = f" {value} "
    return text.replace(line, "|".join(parts))


def test_doc_passes(text):
    rules, errors = rules_check.check(text)
    assert errors == []
    assert len(rules) >= rules_check.MIN_RULES and sum(r.priced for r in rules) >= rules_check.MIN_PRICED


def test_real_outputs_not_zero():
    rules = rules_check.load(DOC)
    by_metric = {r.metric: r.id for r in rules}
    for input_path, output_path in metrics.self_test_pairs():
        scene = Scene.load(input_path)
        variant = load_output(json.loads(output_path.read_text(encoding="utf-8"))).variants["1"]
        values = metrics.evaluate(scene, variant, rules)
        assert values[by_metric["turn"]] > 0, output_path
        assert values[by_metric["detour_ratio"]] >= 1, output_path
        assert values[by_metric["lead_max_m"]] > 0, output_path
        shape = model.shape_metrics(scene, variant)
        assert (values[by_metric["turn"]], values[by_metric["kink"]], values[by_metric["chamber"]]) == (shape["turns"], shape["kinks"], shape["chambers"])


def test_every_mutation_changes_its_metric():
    rules = rules_check.load(DOC)
    errors, mutations = metrics.self_test(rules)
    assert errors == []
    assert mutations == len(rules)


def test_split_by_technical_node_changes_nothing():
    """Ложная чувствительность: узел посреди прямого подотрезка не меняет форму трассы."""
    rules = rules_check.load(DOC)
    for input_path, output_path in metrics.self_test_pairs():
        scene = Scene.load(input_path)
        variant = load_output(json.loads(output_path.read_text(encoding="utf-8"))).variants["1"]
        base = metrics.evaluate(scene, variant, rules)
        seg = metrics.longest(variant)
        coords = list(seg.geom.coords)
        i, a, b = metrics.longest_sub(seg)
        middle = ((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
        variant.nodes.append(Feature("split", "technical_node", {"variant_id": variant.id}, Point(middle)))
        variant.segments.append(Feature("split_tail", "heat_network", {**seg.props, "start_node_id": "split"}, LineString([middle] + coords[i + 1:])))
        seg.geom, seg.props = LineString(coords[:i + 1] + [middle]), {**seg.props, "end_node_id": "split"}
        assert metrics.evaluate(scene, variant, rules) == pytest.approx(base), output_path


def test_penalties_follow_metrics():
    rules = rules_check.load(DOC)
    scene = Scene.load(Path("data/synth/medium-1.geojson"))
    variant = load_output(json.loads(Path("data/out/medium-1.geojson").read_text(encoding="utf-8"))).variants["1"]
    values = metrics.evaluate(scene, variant, rules)
    tree = model.CostBreakdown(variant=variant, score_value=1.0)
    expected = sum(r.price_rub * values[r.id] for r in rules if r.priced)
    assert expected > 0
    assert model.penalties(tree, scene, rules) == pytest.approx(expected)
    assert model.objective(tree, scene, rules) == pytest.approx(1.0 + 0.7 * expected / 25_000_000)


def test_rejects_c_level_with_price(text):
    line = next(l for l in rule_lines(text) if l.split("|")[3].strip() == "C")
    _, errors = rules_check.check(with_cell(text, line, "Цена, руб.", "10 000"))
    assert any("уровня C" in e for e in errors)


def test_rejects_c_level_in_objective(text):
    line = next(l for l in rule_lines(text) if l.split("|")[3].strip() == "C")
    _, errors = rules_check.check(with_cell(text, line, "Применение", "objective"))
    assert any("уровня C" in e for e in errors)


def test_rejects_empty_source(text):
    _, errors = rules_check.check(with_cell(text, rule_lines(text)[0], "Источник", ""))
    assert any("нет ссылки" in e for e in errors)


def test_rejects_price_not_matching_method(text):
    line = next(l for l in rule_lines(text) if l.split("|")[7].strip() == "objective")
    _, errors = rules_check.check(with_cell(text, line, "Цена, руб.", "999 999"))
    assert any("не равна расчёту" in e for e in errors)


def test_rejects_unknown_metric(text):
    _, errors = rules_check.check(with_cell(text, rule_lines(text)[0], "Метрика", "beauty"))
    assert any("не считает" in e for e in errors)


def test_cli_fails_on_less_than_twelve_rules(text, tmp_path):
    broken = text
    for line in rule_lines(text)[11:]:
        broken = broken.replace(line + "\n", "")
    path = tmp_path / "routing-quality.md"
    path.write_text(broken, encoding="utf-8")
    result = subprocess.run([sys.executable, "-m", "heatopt.rules_check", str(path)], capture_output=True, text=True)
    assert result.returncode == 1
    assert "правил 11" in result.stdout and "RULES DOC OK" not in result.stdout
