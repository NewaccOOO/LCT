from typing import Any

from heatcheck.model import (
    Input,
    Output,
    RuleResult,
    Violation,
    load_input,
    load_output,
)
from heatcheck.money import (
    check_chamber_recon,
    check_cost,
    check_reconstruction,
    check_score,
)
from heatcheck.schema import check_schema
from heatcheck.space import (
    check_forbid,
    check_special,
)
from heatcheck.tree import (
    check_coverage,
    check_diameter,
    check_flow,
    check_geometry,
    check_length_limit,
    check_tie_in,
    check_topology,
    check_variants,
)

CRASH_PREFIX = "проверка упала с исключением"
MAX_EXAMPLES = 5
CHECKS = {
    "schema": check_schema,
    "topology": check_topology,
    "tie_in": check_tie_in,
    "flow": check_flow,
    "diameter": check_diameter,
    "length_limit": check_length_limit,
    "forbid": check_forbid,
    "special": check_special,
    "reconstruction": check_reconstruction,
    "chamber_recon": check_chamber_recon,
    "cost": check_cost,
    "score": check_score,
    "coverage": check_coverage,
    "variants": check_variants,
    "geometry": check_geometry,
}


def check(rule: str, inp: Input, out: Output, rules: dict[str, Any]) -> RuleResult:
    try:
        return CHECKS[rule](inp, out, rules)
    except Exception as error:  # битый выход не должен ронять валидатор: правило, которое не смогло проверить, падает
        return RuleResult([Violation("-", "-", f"{CRASH_PREFIX}: {type(error).__name__}: {error}")], 0)


def run_rule(rule: str, input_data: Any, output_data: Any, rules: dict[str, Any]) -> RuleResult:
    return check(rule, load_input(input_data, rules), load_output(output_data), rules)


def run_all(input_data: Any, output_data: Any, rules: dict[str, Any]) -> dict[str, RuleResult]:
    inp, out = load_input(input_data, rules), load_output(output_data)
    return {rule: check(rule, inp, out, rules) for rule in CHECKS}


def examples(result: RuleResult) -> list[str]:
    return [f"    variant={v.variant_id} id={v.object_id}: {v.message}" for v in result.violations[:MAX_EXAMPLES]]


def render_only(rule: str, result: RuleResult) -> str:
    status = f"FAILED ({len(result.violations)})" if result.violations else "PASSED"
    return "\n".join([f"checked={result.checked}", f"RULE {rule}: {status}", *examples(result)])


def render_all(results: dict[str, RuleResult]) -> str:
    lines = [f"{'правило':<16}{'нарушений':>10}{'checked':>9}"]
    for rule, result in results.items():
        lines.append(f"{rule:<16}{len(result.violations):>10}{result.checked:>9}")
        lines += examples(result)
    failed = [rule for rule, result in results.items() if result.violations]
    lines.append(f"VALIDATION FAILED: {', '.join(failed)}" if failed else "VALIDATION PASSED")
    return "\n".join(lines)
