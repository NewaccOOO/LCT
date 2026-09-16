from collections.abc import Callable
from dataclasses import (
    dataclass,
    fields,
)
from functools import cached_property
from typing import (
    Any,
    NamedTuple,
)

import shapely
from shapely.geometry import (
    LineString,
    Point,
)
from shapely.geometry.base import BaseGeometry

from heatcheck.model import (
    Feature,
    Input,
    Output,
    Variant,
    diameter_row,
    load_input,
    load_output,
)
from heatcheck.money import turn_k
from heatcheck.validate import (
    examples,
    run_all,
)
from heatscen.runner import Run

LENGTH_TOL_M = 0.05
COST_TOL_RUB = 1.0
FLOW_TOL_TPH = 0.001
SCORE_TOL = 0.001
DIST_EPS_M = 0.001
ZONE_TOL_M = 0.05
TIE_TOL_M = 0.5
FLOAT_EPS = 1e-9
OCTILINEAR_SLACK = 1.05
STDERR_TAIL_CHARS = 400
OPTIONS = {"formula_tol_rub"}
RUN_FIELDS = {"exit_code", "diagnostics", "stderr_contains", "no_output"}
COST_KEYS = (
    "cost", "construction_cost", "chamber_construction_cost", "tie_in_cost", "reconstruction_cost",
    "chamber_reconstruction_cost", "unconnected_penalty", "calculated_cost",
)
TOLERANCES = (
    {key: LENGTH_TOL_M for key in ("length", "new_network_length", "reconstruction_length")}
    | {key: FLOW_TOL_TPH for key in ("flow_tph", "existing_flow_tph", "added_flow_tph", "calculated_flow_tph")}
    | {key: COST_TOL_RUB for key in COST_KEYS}
    | {"score": SCORE_TOL}
)


@dataclass
class Expect:
    """Решающие факты о выходе; сравнение с вариантом rank = 1. Поля и допуски — design.md D-3."""

    tie_ins: list[dict[str, Any]] | None = None
    new_chambers: int | list[int] | None = None
    segment_dn: dict[str, int] | None = None
    dn_used: set[int] | None = None
    special: dict[str, int] | None = None
    no_special: bool = False
    technical_nodes: int | None = None
    recon: list[dict[str, Any]] | None = None
    no_recon: bool = False
    chamber_recon: list[dict[str, Any]] | None = None
    unconnected: list[str] | None = None
    penalty: float | None = None
    variants: tuple[int, int] | None = None
    distinct_tie_in_sets: bool = False
    max_new_length: float | None = None
    summary: dict[str, Any] | None = None
    costs_by_formula: bool = False
    clearance: list[str] | None = None
    exit_code: int | None = None
    diagnostics: list[tuple[str, str]] | None = None
    stderr_contains: list[str] | None = None
    no_output: bool = False
    formula_tol_rub: float = COST_TOL_RUB

    def declared(self) -> list[str]:
        result = []
        for f in fields(self):
            value = getattr(self, f.name)
            if f.name not in OPTIONS and value is not None and value is not False:
                result.append(f.name)
        return result


class Zone(NamedTuple):
    restriction_type: str
    k_special: float
    area: BaseGeometry


@dataclass
class Context:
    run: Run
    rules: dict[str, Any]
    expect: Expect

    @cached_property
    def inp(self) -> Input:
        return load_input(self.run.input, self.rules)

    @cached_property
    def out(self) -> Output:
        return load_output(self.run.output)

    @cached_property
    def best(self) -> Variant | None:
        for variant in self.out.variants.values():
            if any(s.props.get("rank") == 1 for s in variant.summaries):
                return variant
        return None

    @property
    def summary(self) -> dict[str, Any]:
        return self.best.summaries[0].props

    @cached_property
    def zones(self) -> list[Zone]:
        return special_zones(self.inp, self.best)


def special_zones(inp: Input, variant: Variant) -> list[Zone]:
    """Зоны специальных объектов по сцене: полигон плюс margin_m, у линии — круги margin_m вокруг пересечений с трассой."""
    lines = [s.geom for s in variant.segments if isinstance(s.geom, LineString)]
    if not lines:
        return []
    trace = shapely.union_all(lines)
    ties = [t.geom for t in variant.tie_ins if t.geom is not None]
    zones = []
    for obstacle in inp.special:
        geom, margin = obstacle.feature.geom, obstacle.params["margin_m"]
        if geom.geom_type in ("Polygon", "MultiPolygon"):
            area = geom.buffer(margin)
        else:
            points = [Point(xy) for xy in shapely.get_coordinates(trace.intersection(geom))]
            if obstacle.restriction_type == "heat_network":
                points = [p for p in points if all(p.distance(t) > TIE_TOL_M for t in ties)]
            if not points:
                continue
            area = shapely.union_all([p.buffer(margin) for p in points])
        zones.append(Zone(obstacle.restriction_type, obstacle.params["k_special"], area))
    return zones


def in_zone(seg: Feature, area: BaseGeometry) -> bool:
    return area.distance(seg.geom.interpolate(0.5, normalized=True)) <= ZONE_TOL_M


def same(key: str, actual: Any, expected: Any) -> bool:
    tol = TOLERANCES.get(key)
    if tol is not None:
        return isinstance(actual, int | float) and abs(actual - expected) <= tol + FLOAT_EPS
    if isinstance(expected, list | set | tuple):
        return isinstance(actual, list) and sorted(actual) == sorted(expected)
    return actual == expected


def pick(props: dict[str, Any], keys: list[str]) -> dict[str, Any]:
    return {key: props.get(key) for key in keys}


def match_all(name: str, kind: str, actual: list[Feature], expected: list[dict[str, Any]]) -> list[str]:
    keys = sorted({key for item in expected for key in item} | {"existing_object_id"})
    found = [pick(f.props, keys) for f in actual]
    messages = []
    if len(actual) != len(expected):
        messages.append(f"{name}: {kind} {len(actual)}, ожидалось {len(expected)}; в варианте: {found}")
    free = list(actual)
    for item in expected:
        match = next((f for f in free if all(same(k, f.props.get(k), v) for k, v in item.items())), None)
        if match is None:
            messages.append(f"{name}: нет записи {item}; в варианте: {found}")
        else:
            free.remove(match)
    return messages


def check_tie_ins(ctx: Context, expected: list[dict[str, Any]]) -> list[str]:
    return match_all("tie_ins", "врезок", ctx.best.tie_ins, expected)


def check_new_chambers(ctx: Context, expected: int | list[int]) -> list[str]:
    dns = sorted(c.props.get("diameter") for c in ctx.best.chambers)
    if isinstance(expected, int):
        return [] if len(dns) == expected else [f"new_chambers: новых камер {len(dns)}, ожидалось {expected}"]
    return [] if dns == sorted(expected) else [f"new_chambers: диаметры новых камер {dns}, ожидалось {sorted(expected)}"]


def check_segment_dn(ctx: Context, expected: dict[str, int]) -> list[str]:
    messages = []
    for cp_id, dn in expected.items():
        dns = [s.props.get("diameter") for s in ctx.best.segments if s.props.get("end_node_id") == cp_id]
        if not dns:
            messages.append(f"segment_dn: нет участка, который заканчивается в {cp_id}")
        elif any(d != dn for d in dns):
            messages.append(f"segment_dn: участок к {cp_id} диаметра {dns}, ожидался {dn}")
    return messages


def check_dn_used(ctx: Context, expected: set[int]) -> list[str]:
    dns = {s.props.get("diameter") for s in ctx.best.segments}
    return [] if dns == set(expected) else [f"dn_used: диаметры новых участков {sorted(dns, key=str)}, ожидалось {sorted(expected)}"]


def check_special(ctx: Context, expected: dict[str, int]) -> list[str]:
    messages = []
    special = [s for s in ctx.best.segments if s.props.get("laying_method") == "special"]
    for restriction_type, n in expected.items():
        areas = [z.area for z in ctx.zones if z.restriction_type == restriction_type]
        count = sum(1 for s in special if any(in_zone(s, a) for a in areas))
        if count < n:
            messages.append(f"special: участков special в зоне {restriction_type} {count}, ожидалось не меньше {n}")
    return messages


def check_no_special(ctx: Context, expected: bool) -> list[str]:
    ids = [s.id for s in ctx.best.segments if s.props.get("laying_method") != "base"]
    return [f"no_special: участки не base: {ids}"] if ids else []


def check_technical_nodes(ctx: Context, expected: int) -> list[str]:
    count = len(ctx.best.nodes)
    return [] if count == expected else [f"technical_nodes: технических узлов {count}, ожидалось {expected}"]


def check_recon(ctx: Context, expected: list[dict[str, Any]]) -> list[str]:
    return match_all("recon", "реконструкций", ctx.best.recons, expected)


def check_no_recon(ctx: Context, expected: bool) -> list[str]:
    ids = [r.props.get("existing_object_id") for r in ctx.best.recons]
    return [f"no_recon: реконструируются {ids}"] if ids else []


def check_chamber_recon(ctx: Context, expected: list[dict[str, Any]]) -> list[str]:
    return match_all("chamber_recon", "реконструкций камер", ctx.best.chamber_recons, expected)


def check_unconnected(ctx: Context, expected: list[str]) -> list[str]:
    actual = ctx.summary.get("unconnected_oks_ids")
    return [] if same("unconnected_oks_ids", actual, expected) else [f"unconnected: неподключённые {actual}, ожидалось {expected}"]


def check_penalty(ctx: Context, expected: float) -> list[str]:
    actual = ctx.summary.get("unconnected_penalty")
    return [] if same("unconnected_penalty", actual, expected) else [f"penalty: штраф {actual}, ожидался {expected}"]


def check_variants(ctx: Context, expected: tuple[int, int]) -> list[str]:
    low, high = expected
    count = sum(len(v.summaries) for v in ctx.out.variants.values())
    return [] if low <= count <= high else [f"variants: вариантов {count}, ожидалось от {low} до {high}"]


def check_distinct_tie_in_sets(ctx: Context, expected: bool) -> list[str]:
    sets = {v.id: sorted(str(t.props.get("existing_object_id")) for t in v.tie_ins) for v in ctx.out.variants.values() if v.summaries}
    unique = {tuple(s) for s in sets.values()}
    return [] if len(unique) == len(sets) else [f"distinct_tie_in_sets: наборы врезок вариантов совпадают: {sets}"]


def check_max_new_length(ctx: Context, expected: float) -> list[str]:
    # Границы семейств посчитаны по кратчайшему обходу; трасса из отрезков через 45° (протокол 16.09.2026 п. 9)
    # длиннее его не больше чем на OCTILINEAR_SLACK.
    limit = expected * OCTILINEAR_SLACK
    actual = ctx.summary.get("new_network_length")
    if isinstance(actual, int | float) and actual <= limit:
        return []
    return [f"max_new_length: new_network_length {actual}, граница {expected} × {OCTILINEAR_SLACK} = {limit:.2f}"]


def check_summary(ctx: Context, expected: dict[str, Any]) -> list[str]:
    messages = []
    for key, value in expected.items():
        actual = ctx.summary.get(key)
        if not same(key, actual, value):
            messages.append(f"summary: {key} = {actual}, ожидалось {value}")
    return messages


def bend_coords_of(ctx: Context, seg: Feature) -> list[tuple[float, float]]:
    """Как bend_coords валидатора: у участка из технического узла спереди предпоследняя точка входящего участка."""
    coords = list(seg.geom.coords)
    start = seg.props.get("start_node_id")
    if start in {n.id for n in ctx.best.nodes}:
        incoming = [s for s in ctx.best.segments if s.props.get("end_node_id") == start]
        if incoming:
            coords = [list(incoming[0].geom.coords)[-2]] + coords
    return coords


def check_costs_by_formula(ctx: Context, expected: bool) -> list[str]:
    messages = []
    tol = ctx.expect.formula_tol_rub
    for seg in ctx.best.segments:
        props = seg.props
        row = diameter_row(ctx.rules, props.get("diameter"))
        if row is None:
            messages.append(f"costs_by_formula: {seg.id} диаметра {props.get('diameter')} нет в таблице")
            continue
        k = 1.0
        if props.get("laying_method") == "special":
            k = max((z.k_special for z in ctx.zones if in_zone(seg, z.area)), default=None)
            if k is None:
                messages.append(f"costs_by_formula: специальный участок {seg.id} вне зон специальных объектов сцены")
                continue
        k *= turn_k(ctx.rules, bend_coords_of(ctx, seg))
        need = props.get("length", 0) * row["new_rub_m"] * k
        cost = props.get("cost")
        if not isinstance(cost, int | float) or abs(cost - need) > tol + FLOAT_EPS:
            messages.append(f"costs_by_formula: {seg.id} cost {cost}, по формуле {need:.2f} (k {k})")
    return messages


def obstacle_type(feature: Feature) -> str:
    if feature.object_type in ("oks_existing", "heat_network"):
        return feature.object_type
    return str(feature.props.get("restriction_type"))


def required_offset(rules: dict[str, Any], feature: Feature, dn: int) -> float:
    restrictions = rules["restrictions"]
    restriction_type = obstacle_type(feature)
    params = restrictions.get(restriction_type) or restrictions["_fallback"]
    clearance = params["clearance_m"]
    if isinstance(clearance, list):
        clearance = next(tier["m"] for tier in clearance if dn <= tier["dn_max"])
    offset = clearance + diameter_row(rules, dn)["width_m"] / 2 + params.get("half_width_m", 0)
    if restriction_type == "heat_network":
        offset += diameter_row(rules, feature.props.get("diameter"))["width_m"] / 2
    return offset


def check_clearance(ctx: Context, expected: list[str]) -> list[str]:
    messages = []
    for object_id in expected:
        feature = ctx.inp.by_id.get(object_id)
        if feature is None or feature.geom is None:
            messages.append(f"clearance: во входе нет объекта {object_id} с геометрией")
            continue
        for seg in ctx.best.segments:
            dn = seg.props.get("diameter")
            if diameter_row(ctx.rules, dn) is None:
                messages.append(f"clearance: {seg.id} диаметра {dn} нет в таблице")
                continue
            need, distance = required_offset(ctx.rules, feature, dn), seg.geom.distance(feature.geom)
            if distance < need - DIST_EPS_M:
                messages.append(f"clearance: {seg.id} в {distance:.2f} м от {object_id}, нужно не меньше {need:.2f} м")
    return messages


def stderr_tail(run: Run) -> str:
    return run.stderr[-STDERR_TAIL_CHARS:].strip()


def check_exit_code(ctx: Context, expected: int) -> list[str]:
    code = ctx.run.exit_code
    return [] if code == expected else [f"exit_code: CLI завершился кодом {code}, ожидался {expected}; stderr: {stderr_tail(ctx.run)}"]


def check_diagnostics(ctx: Context, expected: list[tuple[str, str]]) -> list[str]:
    lines = ctx.run.stderr.splitlines()
    missing = [(fid, field) for fid, field in expected if not any(line.startswith(f"{fid} {field}:") for line in lines)]
    return [f"diagnostics: нет диагностик {missing}; stderr: {stderr_tail(ctx.run)}"] if missing else []


def check_stderr_contains(ctx: Context, expected: list[str]) -> list[str]:
    missing = [text for text in expected if text not in ctx.run.stderr]
    return [f"stderr_contains: в stderr нет {missing}; stderr: {stderr_tail(ctx.run)}"] if missing else []


def check_no_output(ctx: Context, expected: bool) -> list[str]:
    return [] if ctx.run.output is None else ["no_output: выходной файл создан"]


CHECKS: dict[str, Callable[[Context, Any], list[str]]] = {
    "tie_ins": check_tie_ins,
    "new_chambers": check_new_chambers,
    "segment_dn": check_segment_dn,
    "dn_used": check_dn_used,
    "special": check_special,
    "no_special": check_no_special,
    "technical_nodes": check_technical_nodes,
    "recon": check_recon,
    "no_recon": check_no_recon,
    "chamber_recon": check_chamber_recon,
    "unconnected": check_unconnected,
    "penalty": check_penalty,
    "variants": check_variants,
    "distinct_tie_in_sets": check_distinct_tie_in_sets,
    "max_new_length": check_max_new_length,
    "summary": check_summary,
    "costs_by_formula": check_costs_by_formula,
    "clearance": check_clearance,
    "exit_code": check_exit_code,
    "diagnostics": check_diagnostics,
    "stderr_contains": check_stderr_contains,
    "no_output": check_no_output,
}


def check_fields(run: Run, expect: Expect, rules: dict[str, Any]) -> list[str]:
    """Только объявленные поля Expect, без валидатора; каждое сообщение начинается с `<поле>:`."""
    ctx = Context(run, rules, expect)
    messages = []
    for name in expect.declared():
        if name not in RUN_FIELDS:
            if run.output is None:
                messages.append(f"{name}: нет выходного файла, код CLI {run.exit_code}")
                continue
            if ctx.best is None:
                messages.append(f"{name}: в выходе нет варианта с rank = 1")
                continue
        try:
            messages += CHECKS[name](ctx, getattr(expect, name))
        except Exception as error:  # битый выход сервиса не должен ронять прогон: поле, которое не смогло проверить, падает
            messages.append(f"{name}: проверка упала с исключением {type(error).__name__}: {error}")
    return messages


def check(run: Run, expect: Expect, rules: dict[str, Any]) -> list[str]:
    """Поля Expect плюс все правила валидатора, если сценарий ждёт успешный расчёт. Пустой список — успех."""
    messages = [] if expect.declared() else ["expect: сценарий не объявил ни одного ожидания"]
    messages += check_fields(run, expect, rules)
    if expect.exit_code not in (None, 0):
        return messages
    if expect.exit_code is None and run.exit_code != 0:
        messages.append(f"run: CLI завершился кодом {run.exit_code}; stderr: {stderr_tail(run)}")
    if run.output is None:
        messages.append("run: нет выходного файла")
        return messages
    for rule, result in run_all(run.input, run.output, rules).items():
        if result.violations:
            messages.append("\n".join([f"validator: {rule}: нарушений {len(result.violations)}", *examples(result)]))
    return messages
