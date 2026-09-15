import copy
import json
from collections.abc import Callable
from pathlib import Path

import pytest

from heatcheck import RULES
from heatcheck.validate import (
    CRASH_PREFIX,
    render_all,
    render_only,
    run_all,
    run_rule,
)

ROOT = Path(__file__).resolve().parents[3]
FIXTURES = Path(__file__).resolve().parent / "fixtures"
RULES_DATA = json.loads((ROOT / "rules" / "rules.json").read_text(encoding="utf-8"))
INPUT = json.loads((FIXTURES / "input.geojson").read_text(encoding="utf-8"))
OUTPUT = json.loads((FIXTURES / "output.geojson").read_text(encoding="utf-8"))


def find(data: dict, feature_id: str) -> dict:
    return next(f for f in data["features"] if f["properties"].get("id") == feature_id)


def props(data: dict, feature_id: str) -> dict:
    return find(data, feature_id)["properties"]


def break_schema(inp: dict, out: dict) -> None:
    del props(out, "v1_seg_1")["depth_start"]


def break_topology(inp: dict, out: dict) -> None:
    seg = find(out, "v2_seg_2")
    seg["properties"]["start_node_id"], seg["properties"]["end_node_id"] = "v2_node_2", "v2_node_1"
    seg["geometry"]["coordinates"].reverse()


def break_tie_in(inp: dict, out: dict) -> None:
    props(out, "v1_tie_1")["existing_object_type"] = "heat_network"


def break_flow(inp: dict, out: dict) -> None:
    props(out, "v1_seg_1")["flow_tph"] = 4.0


def break_diameter(inp: dict, out: dict) -> None:
    props(out, "v1_seg_1")["diameter"] = 100


def break_length_limit(inp: dict, out: dict) -> None:
    # По отдельности участки короче 181 м, вместе 199,44 м: цепочка считается суммой, а не самым длинным участком.
    for seg_id in ("v2_seg_1", "v2_seg_2", "v2_seg_3"):
        props(out, seg_id)["diameter"] = 50


def break_forbid(inp: dict, out: dict) -> None:
    ring = find(inp, "park_1")["geometry"]["coordinates"][0][:4]
    center = [sum(c[0] for c in ring) / 4, sum(c[1] for c in ring) / 4]
    find(out, "v1_seg_1")["geometry"]["coordinates"][1] = center


def break_special(inp: dict, out: dict) -> None:
    props(out, "v2_seg_2")["laying_method"] = "base"


def break_reconstruction(inp: dict, out: dict) -> None:
    out["features"].remove(find(out, "v2_recon_1"))


def break_chamber_recon(inp: dict, out: dict) -> None:
    out["features"].remove(find(out, "v2_chrecon_1"))


def break_cost(inp: dict, out: dict) -> None:
    props(out, "v1_tie_1")["cost"] = 4_000_000


def break_score(inp: dict, out: dict) -> None:
    props(out, "summary_1")["score"] += 0.5


def break_coverage(inp: dict, out: dict) -> None:
    props(out, "summary_1")["unconnected_oks_ids"] = ["oks_1"]


def break_variants(inp: dict, out: dict) -> None:
    out["features"] = [f for f in out["features"] if f["properties"]["variant_id"] != "2"]


def break_geometry(inp: dict, out: dict) -> None:
    coords = find(out, "v1_seg_1")["geometry"]["coordinates"]
    coords.insert(1, [(coords[0][0] + coords[1][0]) / 2, (coords[0][1] + coords[1][1]) / 2])


MUTATIONS: dict[str, Callable[[dict, dict], None]] = {
    "schema": break_schema,
    "topology": break_topology,
    "tie_in": break_tie_in,
    "flow": break_flow,
    "diameter": break_diameter,
    "length_limit": break_length_limit,
    "forbid": break_forbid,
    "special": break_special,
    "reconstruction": break_reconstruction,
    "chamber_recon": break_chamber_recon,
    "cost": break_cost,
    "score": break_score,
    "coverage": break_coverage,
    "variants": break_variants,
    "geometry": break_geometry,
}


def test_fixture_passes_all_rules():
    results = run_all(INPUT, OUTPUT, RULES_DATA)

    assert list(results) == RULES
    assert render_all(results).endswith("VALIDATION PASSED"), render_all(results)
    assert all(result.checked > 0 for result in results.values())


@pytest.mark.parametrize("rule", RULES)
def test_mutation_fails_its_rule(rule):
    inp, out = copy.deepcopy(INPUT), copy.deepcopy(OUTPUT)
    MUTATIONS[rule](inp, out)

    result = run_rule(rule, inp, out, RULES_DATA)
    report = render_only(rule, result)

    assert f"RULE {rule}: FAILED ({len(result.violations)})" in report, report
    assert not any(v.message.startswith(CRASH_PREFIX) for v in result.violations), report


def test_variant_without_new_network_does_not_crash_topology():
    # Вариант, где все ОКС неподключены, допустим: у него только сводка.
    inp, out = copy.deepcopy(INPUT), copy.deepcopy(OUTPUT)
    out["features"] = [f for f in out["features"] if f["properties"]["variant_id"] != "2" or f["properties"]["object_type"] == "variant_summary"]

    result = run_rule("topology", inp, out, RULES_DATA)

    assert not result.violations, render_only("topology", result)


def test_single_variant_allowed_only_without_tie_ins():
    # Все ОКС неподключены: отличающегося второго варианта не существует, одна сводка без врезок допустима.
    inp, out = copy.deepcopy(INPUT), copy.deepcopy(OUTPUT)
    out["features"] = [f for f in out["features"] if f["properties"]["object_type"] == "variant_summary" and f["properties"]["variant_id"] == "1"]

    result = run_rule("variants", inp, out, RULES_DATA)

    assert not result.violations, render_only("variants", result)


def test_tie_in_required_diameter_is_new_segment_diameter():
    # ТП §10.2, пример 10.8: у врезки диаметр новой сети (200), а не существующей после подключения (150 или 250).
    inp, out = copy.deepcopy(INPUT), copy.deepcopy(OUTPUT)
    tie = props(out, "v1_tie_1")
    assert tie["required_diameter"] == props(out, "v1_seg_1")["diameter"] < tie["existing_diameter"]
    tie["required_diameter"] = tie["existing_diameter"]

    result = run_rule("tie_in", inp, out, RULES_DATA)

    assert [v.object_id for v in result.violations] == ["v1_tie_1"], render_only("tie_in", result)


@pytest.mark.parametrize(("restriction_type", "geometry_type", "shift_deg", "fails"), [
    ("power_line_support", "Point", 0.0, True),
    ("railway", "Point", 0.0, True),
    ("depot_xyz", "Polygon", 0.0, True),
    ("depot_xyz", "Point", 0.01, False),
])
def test_point_and_unknown_type_are_bypassed(restriction_type, geometry_type, shift_deg, fails):
    # Точка любого типа и тип вне справочника обходятся с отступом правила forbid; вдали от трассы не мешают.
    inp, out = copy.deepcopy(INPUT), copy.deepcopy(OUTPUT)
    (x0, y0), (x1, y1) = find(out, "v1_seg_1")["geometry"]["coordinates"][:2]
    x, y = (x0 + x1) / 2 + shift_deg, (y0 + y1) / 2
    d = 0.00001
    coordinates = [x, y] if geometry_type == "Point" else [[[x - d, y - d], [x + d, y - d], [x + d, y + d], [x - d, y + d], [x - d, y - d]]]
    props = {"id": "extra_1", "object_type": "restriction", "restriction_type": restriction_type}
    inp["features"].append({"type": "Feature", "geometry": {"type": geometry_type, "coordinates": coordinates}, "properties": props})

    results = run_all(inp, out, RULES_DATA)

    assert bool(results["forbid"].violations) == fails, render_all(results)
    assert not any(result.violations for rule, result in results.items() if rule != "forbid"), render_all(results)
