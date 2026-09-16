import json
from pathlib import Path

from heatcheck.money import turn_k

RULES = json.loads(Path("rules/rules.json").read_text(encoding="utf-8"))


def test_turn_k_is_one_for_straight_and_standard_bends():
    assert turn_k(RULES, [(0, 0), (10, 0), (20, 0)]) == 1.0
    assert turn_k(RULES, [(0, 0), (10, 0), (20, 10)]) == 1.0
    assert turn_k(RULES, [(0, 0), (10, 0), (10, 10)]) == 1.0
    assert turn_k(RULES, [(0, 0), (10, 0), (20, 0.3)]) == 1.0  # излом 1,7° — не поворот


def test_turn_k_penalises_nonstandard_bend():
    assert turn_k(RULES, [(0, 0), (10, 0), (20, 5)]) == 1.5
    assert turn_k(RULES, [(0, 0), (10, 0), (20, 10), (30, 10), (40, 30)]) == 1.5
