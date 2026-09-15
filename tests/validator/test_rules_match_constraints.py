import copy
import json
from pathlib import Path

from heatcheck.rules_check import compare

ROOT = Path(__file__).resolve().parents[2]
CONSTRAINTS = (ROOT / "architecture" / "CONSTRAINTS.md").read_text(encoding="utf-8")
RULES = json.loads((ROOT / "rules" / "rules.json").read_text(encoding="utf-8"))


def test_rules_json_matches_constraints():
    assert compare(CONSTRAINTS, RULES) == []


def test_changed_value_is_reported():
    rules = copy.deepcopy(RULES)
    rules["restrictions"]["road"]["k_special"] = 1.5

    assert compare(CONSTRAINTS, rules) == ["restrictions.road.k_special: rules.json=1.5 CONSTRAINTS.md=1.6"]
