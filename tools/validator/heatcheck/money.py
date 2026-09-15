import itertools
from collections import defaultdict
from typing import Any

from heatcheck.model import (
    COST_TOL_RUB,
    FLOW_TOL_TPH,
    LENGTH_TOL_M,
    NODE_TOL_M,
    SCORE_TOL,
    Input,
    Output,
    RuleResult,
    Violation,
    chamber_cost,
    diameter_for,
    diameter_row,
)
from heatcheck.network import (
    build_net,
    chamber_required,
    network_loads,
    piece_geom,
    pieces,
    pipe_required,
    segment_k,
    special_zones,
)
from heatcheck.tree import is_number

COST_FIELDS = ("construction_cost", "chamber_construction_cost", "tie_in_cost", "reconstruction_cost", "chamber_reconstruction_cost")


def close(value: Any, expected: float, tolerance: float) -> bool:
    return is_number(value) and abs(value - expected) <= tolerance


def check_reconstruction(inp: Input, out: Output, rules: dict[str, Any]) -> RuleResult:
    violations, checked = [], 0
    for variant in out.variants.values():
        net = build_net(inp, variant)
        loads = network_loads(inp, net, variant)

        def add(object_id: str, message: str) -> None:
            violations.append(Violation(variant.id, object_id, message))

        expected = []
        for network_id, load in sorted(loads.items()):
            network = inp.by_id[network_id]
            existing_flow = float(network.props.get("flow_tph", 0))
            for start, end, added in pieces(load, network.geom.length):
                if added <= 0:
                    continue
                checked += 1
                required = diameter_for(rules, existing_flow + added)
                if required is None:
                    add(network_id, f"итоговый расход {existing_flow + added:.3f} на части {start:.2f}–{end:.2f} м больше пропускной способности таблицы")
                elif required > network.props.get("diameter"):
                    expected.append((network, start, end, added, required, piece_geom(inp, network, start, end)))

        matched = set()
        for recon in variant.recons:
            checked += 1
            props = recon.props
            hit = next((
                i for i, (network, *_, geom) in enumerate(expected)
                if i not in matched and props.get("existing_object_id") == network.id
                and recon.geom is not None and recon.geom.hausdorff_distance(geom) <= NODE_TOL_M
            ), None)
            if hit is None:
                add(recon.id, "реконструкция не совпадает ни с одной частью, где требуемый диаметр больше существующего")
                continue
            matched.add(hit)
            network, start, end, added, required, geom = expected[hit]
            existing_flow = float(network.props.get("flow_tph", 0))
            wanted = {
                "existing_flow_tph": existing_flow,
                "added_flow_tph": added,
                "calculated_flow_tph": existing_flow + added,
            }
            for name, value in wanted.items():
                if not close(props.get(name), value, FLOW_TOL_TPH):
                    add(recon.id, f"{name}={props.get(name)}, по расчёту {value:.3f}")
            if props.get("existing_diameter") != network.props.get("diameter"):
                add(recon.id, f"existing_diameter={props.get('existing_diameter')}, у участка {network.props.get('diameter')}")
            if props.get("required_diameter") != required:
                add(recon.id, f"required_diameter={props.get('required_diameter')}, по расчёту {required}")
            if not close(props.get("length"), recon.geom.length, LENGTH_TOL_M):
                add(recon.id, f"length={props.get('length')}, длина геометрии {recon.geom.length:.2f}")
        for i, (network, start, end, added, required, _) in enumerate(expected):
            if i not in matched:
                add(network.id, f"пропущена реконструкция части {start:.2f}–{end:.2f} м до Ду {required} при добавке {added:.3f} т/ч")
    return RuleResult(violations, checked)


def check_chamber_recon(inp: Input, out: Output, rules: dict[str, Any]) -> RuleResult:
    violations, checked = [], 0
    for variant in out.variants.values():
        net = build_net(inp, variant)
        loads = network_loads(inp, net, variant)

        def add(object_id: str, message: str) -> None:
            violations.append(Violation(variant.id, object_id, message))

        tie_chambers = {}
        for tie in variant.tie_ins:
            existing = inp.by_id.get(str(tie.props.get("existing_object_id")))
            if existing is not None and existing.object_type == "heat_chamber" and tie.id in net.group_of:
                tie_chambers[existing.id] = (existing, net.group_of[tie.id])
        records = defaultdict(list)
        for record in variant.chamber_recons:
            records[str(record.props.get("existing_object_id"))].append(record)

        for chamber_id, (chamber, group) in sorted(tie_chambers.items()):
            checked += 1
            required = chamber_required(rules, inp, net, loads, chamber, group)
            original = chamber.props.get("diameter")
            found = records.pop(chamber_id, [])
            if required <= original:
                for record in found:
                    add(record.id, f"реконструкция не нужна: наибольший примыкающий Ду {required} не больше исходного {original}")
                continue
            if len(found) != 1:
                add(chamber_id, f"нужна одна реконструкция камеры до Ду {required}, записей {len(found)}")
                continue
            record = found[0]
            if record.props.get("existing_diameter") != original:
                add(record.id, f"existing_diameter={record.props.get('existing_diameter')}, у камеры {original}")
            if record.props.get("required_diameter") != required:
                add(record.id, f"required_diameter={record.props.get('required_diameter')}, по расчёту {required}")
            if not close(record.props.get("cost"), chamber_cost(rules, required), COST_TOL_RUB):
                add(record.id, f"cost={record.props.get('cost')}, по шкале {chamber_cost(rules, required)}")
            if record.geom is None or record.geom.distance(chamber.geom) > NODE_TOL_M:
                add(record.id, "точка реконструкции не совпадает с камерой")
        for record in itertools.chain.from_iterable(records.values()):
            checked += 1
            add(record.id, "реконструкция камеры, в которую нет врезки в этом варианте")

        for chamber in variant.chambers:
            if chamber.id not in net.group_of:
                continue
            checked += 1
            group = net.group_of[chamber.id]
            dns = [s.props.get("diameter") for s in net.incident(group)]
            for tie in variant.tie_ins:
                existing = inp.by_id.get(str(tie.props.get("existing_object_id")))
                if net.group_of.get(tie.id) == group and existing is not None and existing.object_type == "heat_network":
                    dns.append(pipe_required(rules, inp, loads, existing, tie.geom))
            expected = max((d for d in dns if isinstance(d, int)), default=None)
            if chamber.props.get("diameter") != expected:
                add(chamber.id, f"diameter={chamber.props.get('diameter')}, наибольший примыкающий {expected}")
    return RuleResult(violations, checked)


def check_cost(inp: Input, out: Output, rules: dict[str, Any]) -> RuleResult:
    violations, checked = [], 0
    for variant in out.variants.values():
        net = build_net(inp, variant)
        zones = special_zones(inp, variant, net)
        sums = dict.fromkeys(COST_FIELDS + ("new_network_length", "reconstruction_length"), 0.0)

        def add(object_id: str, message: str) -> None:
            violations.append(Violation(variant.id, object_id, message))

        def verify(feature_id: str, cost: Any, expected: float | None, total: str) -> None:
            if is_number(cost):
                sums[total] += cost
            if expected is None:
                add(feature_id, "стоимость не рассчитывается: нет диаметра в таблице, длины или Kспец")
            elif not close(cost, expected, COST_TOL_RUB):
                add(feature_id, f"cost={cost}, по формуле {expected:.2f}")

        for seg in variant.segments:
            checked += 1
            props = seg.props
            row, length = diameter_row(rules, props.get("diameter")), props.get("length")
            k = 1.0
            if props.get("laying_method") == "special":
                k = segment_k(zones, seg) if seg.id in net.start else None
            expected = length * row["new_rub_m"] * k if row and is_number(length) and k else None
            verify(seg.id, props.get("cost"), expected, "construction_cost")
            sums["new_network_length"] += length if is_number(length) else 0
        for tie in variant.tie_ins:
            checked += 1
            verify(tie.id, tie.props.get("cost"), rules["tie_in_cost"], "tie_in_cost")
        for chamber in variant.chambers:
            checked += 1
            verify(chamber.id, chamber.props.get("cost"), chamber_cost(rules, chamber.props.get("diameter")), "chamber_construction_cost")
        for recon in variant.recons:
            checked += 1
            row, length = diameter_row(rules, recon.props.get("required_diameter")), recon.props.get("length")
            expected = length * row["recon_rub_m"] if row and is_number(length) else None
            verify(recon.id, recon.props.get("cost"), expected, "reconstruction_cost")
            sums["reconstruction_length"] += length if is_number(length) else 0
        for record in variant.chamber_recons:
            checked += 1
            expected = chamber_cost(rules, record.props.get("required_diameter"))
            verify(record.id, record.props.get("cost"), expected, "chamber_reconstruction_cost")

        for summary in variant.summaries:
            checked += 1
            props = summary.props
            for name, total in sums.items():
                tolerance = LENGTH_TOL_M if name.endswith("length") else COST_TOL_RUB
                if not close(props.get(name), total, tolerance):
                    add(summary.id, f"{name}={props.get(name)}, сумма по объектам {total:.2f}")
            parts = [props.get(name) for name in COST_FIELDS + ("unconnected_penalty",)]
            if all(is_number(p) for p in parts) and not close(props.get("calculated_cost"), sum(parts), COST_TOL_RUB):
                add(summary.id, f"calculated_cost={props.get('calculated_cost')}, сумма пяти стоимостей и штрафа {sum(parts):.2f}")
            lengths = [props.get("new_network_length"), props.get("reconstruction_length")]
            if all(is_number(p) for p in lengths) and not close(props.get("length"), sum(lengths), LENGTH_TOL_M):
                add(summary.id, f"length={props.get('length')}, сумма длин {sum(lengths):.2f}")
    return RuleResult(violations, checked)


def check_score(inp: Input, out: Output, rules: dict[str, Any]) -> RuleResult:
    violations = []
    weights = rules["score"]
    summaries = [s for v in out.variants.values() for s in v.summaries]
    scored = []
    for summary in summaries:
        props = summary.props
        cost, length, score, rank = (props.get(name) for name in ("calculated_cost", "length", "score", "rank"))
        variant_id = str(props.get("variant_id"))
        if not all(is_number(v) for v in (cost, length, score)) or not isinstance(rank, int):
            violations.append(Violation(variant_id, summary.id, "нет чисел для расчёта score или rank"))
            continue
        expected = weights["w_cost"] * cost / weights["cost_base"] + weights["w_length"] * length / weights["length_base_m"]
        if abs(score - expected) > SCORE_TOL + 1e-9:
            violations.append(Violation(variant_id, summary.id, f"score={score}, по формуле {expected:.4f}"))
        if variant_id != str(rank):
            violations.append(Violation(variant_id, summary.id, f"variant_id={variant_id!r} не равен rank={rank}"))
        scored.append((summary, score, rank))
    ranks = sorted(rank for _, _, rank in scored)
    if ranks != list(range(1, len(ranks) + 1)):
        violations.append(Violation("-", "-", f"ранги {ranks} не идут подряд с 1"))
    for (a, score_a, rank_a), (b, score_b, rank_b) in itertools.combinations(scored, 2):
        if (score_a - score_b) * (rank_a - rank_b) < 0:
            violations.append(Violation(str(b.props.get("variant_id")), b.id, f"rank не по возрастанию score относительно {a.id}"))
    return RuleResult(violations, len(summaries))
