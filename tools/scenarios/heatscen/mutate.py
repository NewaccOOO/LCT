"""Мутационная проверка: каждое объявленное поле Expect обязано отвергнуть искажение своего факта в сохранённом выходе."""
import copy
import json
import sys
from collections.abc import Callable
from typing import Any

from shapely.geometry import (
    LineString,
    mapping,
    shape,
)

from heatcheck.model import (
    Feature,
    next_diameter,
)
from heatscen.expect import (
    Context,
    Expect,
    check_fields,
    in_zone,
    same,
)
from heatscen.registry import load_all
from heatscen.runner import (
    CACHE,
    Run,
    cached,
)
from heatscen.scene import rules

SHIFT = 5.0
ABSENT_ID = "mutation-absent"
ID_SUFFIX = "-mutation"
STUB_LAT_DEG = 0.00001  # около 1,1 м по широте
FAILED_EXIT_CODE = 2


class CannotMutate(Exception):
    pass


def is_number(value: Any) -> bool:
    return isinstance(value, int | float) and not isinstance(value, bool)


def raw_of(ctx: Context, feature: Feature) -> dict[str, Any]:
    # parse_feature держит тот же словарь properties, что и сырой GeoJSON, по нему и ищем исходную фичу.
    return next(raw for raw in ctx.run.output["features"] if raw.get("properties") is feature.props)


def remove(ctx: Context, feature: Feature) -> None:
    ctx.run.output["features"] = [raw for raw in ctx.run.output["features"] if raw.get("properties") is not feature.props]


def add_copy(ctx: Context, feature: Feature, **changes: Any) -> None:
    raw = copy.deepcopy(raw_of(ctx, feature))
    raw["properties"].update(changes, id=f"{feature.id}{ID_SUFFIX}")
    ctx.run.output["features"].append(raw)


def first_tie_in(ctx: Context) -> Feature:
    if not ctx.best.tie_ins:
        raise CannotMutate("в варианте 1 нет врезок")
    return ctx.best.tie_ins[0]


def add_at(ctx: Context, tie: Feature, object_type: str, **props: Any) -> None:
    ctx.run.output["features"].append({
        "type": "Feature",
        "geometry": copy.deepcopy(raw_of(ctx, tie)["geometry"]),
        "properties": {"id": f"{object_type}{ID_SUFFIX}", "object_type": object_type, "variant_id": tie.props.get("variant_id"), **props},
    })


def first_match(features: list[Feature], item: dict[str, Any]) -> Feature:
    found = next((f for f in features if all(same(k, f.props.get(k), v) for k, v in item.items())), None)
    if found is None:
        raise CannotMutate(f"в варианте 1 нет записи {item}")
    return found


def first_segment(ctx: Context) -> Feature:
    if not ctx.best.segments:
        raise CannotMutate("в варианте 1 нет новых участков")
    return ctx.best.segments[0]


def step(ctx: Context, dn: Any) -> int:
    dns = [row["dn"] for row in ctx.rules["diameters"]]
    if dn not in dns:
        raise CannotMutate(f"диаметра {dn} нет в таблице")
    # У наибольшего диаметра ступени вверх нет, поэтому он опускается на ступень: искажение то же.
    return next_diameter(ctx.rules, dn) or dns[-2]


def by_rank(ctx: Context) -> list[Feature]:
    summaries = [s for variant in ctx.out.variants.values() for s in variant.summaries]
    return sorted(summaries, key=lambda s: s.props["rank"])


def mutate_tie_ins(ctx: Context, expected: list[dict[str, Any]]) -> None:
    if expected:
        first_match(ctx.best.tie_ins, expected[0]).props["existing_object_id"] = ABSENT_ID
        return
    ties = [t for variant in ctx.out.variants.values() for t in variant.tie_ins]
    if not ties:
        raise CannotMutate("в выходе нет ни одной врезки для копии")
    add_copy(ctx, ties[0], variant_id=ctx.summary.get("variant_id"))


def mutate_new_chambers(ctx: Context, expected: int | list[int]) -> None:
    if expected:
        if not ctx.best.chambers:
            raise CannotMutate("в варианте 1 нет новых камер")
        remove(ctx, ctx.best.chambers[0])
    else:
        tie = first_tie_in(ctx)
        add_at(ctx, tie, "heat_chamber", diameter=tie.props.get("required_diameter"))


def mutate_segment_dn(ctx: Context, expected: dict[str, int]) -> None:
    if not expected:
        raise CannotMutate("не задано ни одной точки подключения")
    cp_id = next(iter(expected))
    for seg in ctx.best.segments:
        if seg.props.get("end_node_id") == cp_id:
            seg.props["diameter"] = step(ctx, seg.props.get("diameter"))


def mutate_dn_used(ctx: Context, expected: set[int]) -> None:
    low = min((seg.props.get("diameter") for seg in ctx.best.segments), default=None)
    if low is None:
        raise CannotMutate("в варианте 1 нет новых участков")
    for seg in ctx.best.segments:
        if seg.props.get("diameter") == low:
            seg.props["diameter"] = step(ctx, low)


def mutate_special(ctx: Context, expected: dict[str, int]) -> None:
    if not expected:
        raise CannotMutate("не задано ни одного типа ограничения")
    restriction_type = next(iter(expected))
    areas = [zone.area for zone in ctx.zones if zone.restriction_type == restriction_type]
    inside = [seg for seg in ctx.best.segments if any(in_zone(seg, area) for area in areas)]
    if not inside:
        raise CannotMutate(f"в варианте 1 нет участков в зоне {restriction_type}")
    for seg in inside:
        seg.props["laying_method"] = "base"


def mutate_no_special(ctx: Context, expected: bool) -> None:
    first_segment(ctx).props["laying_method"] = "special"


def mutate_technical_nodes(ctx: Context, expected: int) -> None:
    if expected:
        if not ctx.best.nodes:
            raise CannotMutate("в варианте 1 нет технических узлов")
        remove(ctx, ctx.best.nodes[0])
    else:
        add_at(ctx, first_tie_in(ctx), "technical_node")


def mutate_recon(ctx: Context, expected: list[dict[str, Any]]) -> None:
    if expected:
        remove(ctx, first_match(ctx.best.recons, expected[0]))
    else:
        mutate_no_recon(ctx, True)


def mutate_no_recon(ctx: Context, expected: bool) -> None:
    add_copy(ctx, first_segment(ctx), object_type="heat_network_reconstruction")


def mutate_chamber_recon(ctx: Context, expected: list[dict[str, Any]]) -> None:
    if not expected:
        tie = first_tie_in(ctx)
        add_at(ctx, tie, "heat_chamber_reconstruction", existing_object_id=tie.props.get("existing_object_id"))
        return
    props = first_match(ctx.best.chamber_recons, expected[0]).props
    if not is_number(props.get("cost")):
        raise CannotMutate(f"у записи {expected[0]} нет числового cost")
    props["cost"] += SHIFT


def mutate_unconnected(ctx: Context, expected: list[str]) -> None:
    if expected:
        ctx.summary["unconnected_oks_ids"] = []
        return
    oks = ctx.inp.of_type("oks_future")
    if not oks:
        raise CannotMutate("в сцене нет ОКС")
    ctx.summary["unconnected_oks_ids"] = [oks[0].id]


def mutate_penalty(ctx: Context, expected: float) -> None:
    ctx.summary["unconnected_penalty"] += SHIFT


def mutate_variants(ctx: Context, expected: tuple[int, int]) -> None:
    low, _ = expected
    summaries = by_rank(ctx)
    if len(summaries) <= low:
        if not summaries:
            raise CannotMutate("в выходе нет вариантов")
        variant_id = str(summaries[-1].props.get("variant_id"))
        ctx.run.output["features"] = [
            raw for raw in ctx.run.output["features"] if str(raw.get("properties", {}).get("variant_id")) != variant_id
        ]
        return
    number = summaries[-1].props["rank"] + 1
    while str(number) in ctx.out.variants:
        number += 1
    variant_id = number if isinstance(ctx.summary.get("variant_id"), int) else str(number)
    for raw in list(ctx.run.output["features"]):
        props = raw.get("properties", {})
        if str(props.get("variant_id")) == ctx.best.id:
            twin = copy.deepcopy(raw)
            twin["properties"].update(id=f"{props.get('id')}{ID_SUFFIX}", variant_id=variant_id)
            if props.get("object_type") == "variant_summary":
                twin["properties"]["rank"] = number
            ctx.run.output["features"].append(twin)


def mutate_distinct_tie_in_sets(ctx: Context, expected: bool) -> None:
    last = by_rank(ctx)[-1]
    target = ctx.out.variants[str(last.props.get("variant_id"))]
    if target.id == ctx.best.id:
        raise CannotMutate("в выходе один вариант")
    for tie in target.tie_ins:
        remove(ctx, tie)
    for tie in ctx.best.tie_ins:
        add_copy(ctx, tie, variant_id=last.props.get("variant_id"))


def mutate_max_new_length(ctx: Context, expected: float) -> None:
    ctx.summary["new_network_length"] = expected + 1


def mutate_summary(ctx: Context, expected: dict[str, Any]) -> None:
    keys = [key for key, value in expected.items() if is_number(value) and is_number(ctx.summary.get(key))]
    if not keys:
        raise CannotMutate("в ожидании сводки нет числовых полей")
    for key in keys:
        ctx.summary[key] += SHIFT


def mutate_costs_by_formula(ctx: Context, expected: bool) -> None:
    props = first_segment(ctx).props
    if not is_number(props.get("cost")):
        raise CannotMutate(f"у участка {props.get('id')} нет числового cost")
    props["cost"] += ctx.expect.formula_tol_rub + SHIFT


def mutate_clearance(ctx: Context, expected: list[str]) -> None:
    seg = first_segment(ctx)
    if not expected:
        raise CannotMutate("не задано ни одного объекта")
    raw = next((f for f in ctx.run.input["features"] if f.get("properties", {}).get("id") == expected[0]), None)
    if raw is None or raw.get("geometry") is None:
        raise CannotMutate(f"во входе нет объекта {expected[0]} с геометрией")
    point = shape(raw["geometry"]).representative_point()
    raw_of(ctx, seg)["geometry"] = mapping(LineString([(point.x, point.y), (point.x, point.y + STUB_LAT_DEG)]))


def mutate_exit_code(ctx: Context, expected: int) -> None:
    ctx.run.exit_code = 0 if expected else FAILED_EXIT_CODE


def mutate_stderr(ctx: Context, expected: list[Any]) -> None:
    ctx.run.stderr = ""


def mutate_no_output(ctx: Context, expected: bool) -> None:
    ctx.run.output = {"type": "FeatureCollection", "features": []}


MUTATIONS: dict[str, Callable[[Context, Any], None]] = {
    "tie_ins": mutate_tie_ins,
    "new_chambers": mutate_new_chambers,
    "segment_dn": mutate_segment_dn,
    "dn_used": mutate_dn_used,
    "special": mutate_special,
    "no_special": mutate_no_special,
    "technical_nodes": mutate_technical_nodes,
    "recon": mutate_recon,
    "no_recon": mutate_no_recon,
    "chamber_recon": mutate_chamber_recon,
    "unconnected": mutate_unconnected,
    "penalty": mutate_penalty,
    "variants": mutate_variants,
    "distinct_tie_in_sets": mutate_distinct_tie_in_sets,
    "max_new_length": mutate_max_new_length,
    "summary": mutate_summary,
    "costs_by_formula": mutate_costs_by_formula,
    "clearance": mutate_clearance,
    "exit_code": mutate_exit_code,
    "diagnostics": mutate_stderr,
    "stderr_contains": mutate_stderr,
    "no_output": mutate_no_output,
}


def missed_reasons(run: Run, expect: Expect, rules: dict[str, Any]) -> list[tuple[str, str]]:
    """Поля Expect, чью мутацию check_fields не отверг, с причиной; порядок полей как в expect.declared()."""
    before = check_fields(run, expect, rules)
    result = []
    for name in expect.declared():
        prefix = f"{name}:"
        failing = next((message for message in before if message.startswith(prefix)), None)
        if failing is not None:
            result.append((name, f"ожидание не выполняется и без мутации ({failing.splitlines()[0]})"))
            continue
        ctx = Context(copy.deepcopy(run), rules, expect)
        try:
            MUTATIONS[name](ctx, getattr(expect, name))
        except CannotMutate as error:
            result.append((name, f"мутацию нельзя применить: {error}"))
            continue
        if not any(message.startswith(prefix) for message in check_fields(ctx.run, expect, rules)):
            result.append((name, "мутация не отвергнута"))
    return result


def missed(run: Run, expect: Expect, rules: dict[str, Any]) -> list[str]:
    return [name for name, _ in missed_reasons(run, expect, rules)]


def main() -> None:
    scenarios, table = load_all(), rules()
    expectations, lines = 0, []
    for sc in scenarios:
        scene, expect = sc.build()
        names = expect.declared()
        expectations += len(names)
        if not names:
            lines.append(f"{sc.id} expect: сценарий не объявил ни одного ожидания")
            continue
        run = cached(sc.id)
        if run is None:
            lines += [f"{sc.id} {name}: нет кэша прогона, запустите bash scripts/gates/scenarios.sh" for name in names]
            continue
        meta = json.loads((CACHE / sc.id / "run.json").read_text(encoding="utf-8"))
        if meta["scene_hash"] != scene.scene_hash():
            lines += [f"{sc.id} {name}: кэш прогона собран для другой версии сцены, запустите bash scripts/gates/scenarios.sh" for name in names]
            continue
        lines += [f"{sc.id} {name}: {reason}" for name, reason in missed_reasons(run, expect, table)]
    for line in lines:
        print(line)
    if lines:
        print(f"MUTATION FAILED scenarios={len(scenarios)} expectations={expectations} missed={len(lines)}")
        sys.exit(1)
    print(f"MUTATION OK scenarios={len(scenarios)} expectations={expectations} missed=0")


if __name__ == "__main__":
    main()
