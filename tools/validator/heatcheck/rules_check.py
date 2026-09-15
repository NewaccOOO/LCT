import json
import re
import sys
from pathlib import Path

NUM = r"\d[\d ]*(?:,\d+)?"
RESTRICTION_TYPES = [
    "oks_existing", "park", "social_area", "prohibited_site", "water",
    "road", "tram_tracks", "gas_pipeline", "power_cable", "heat_network",
]


def num(text: str) -> float:
    return float(text.replace(" ", "").replace(",", "."))


def table_after(md: str, heading: str) -> list[list[str]]:
    lines = md[md.index(heading):].splitlines()[1:]
    rows = []
    for line in lines:
        if line.startswith("|"):
            rows.append([cell.strip() for cell in line.strip("|").split("|")])
        elif rows:
            break
    return rows[2:]


def compare(md: str, rules: dict) -> list[str]:
    errors = []

    def expect(name: str, actual, wanted):
        if actual is None or abs(float(actual) - float(wanted)) > 1e-9:
            errors.append(f"{name}: rules.json={actual} CONSTRAINTS.md={wanted}")

    by_dn = {d["dn"]: d for d in rules["diameters"]}
    prices = table_after(md, "### Таблица диаметров")
    sizes = {int(r[0]): r for r in table_after(md, "### Расчётные габариты пары труб")}
    if len(prices) != 18 or len(by_dn) != 18 or len(sizes) != 18:
        errors.append(f"diameters: ожидалось 18 строк, rules.json={len(by_dn)}, таблицы={len(prices)}/{len(sizes)}")
    for dn_text, capacity, max_length, new_rub, recon_rub in prices:
        dn = int(num(dn_text))
        row = by_dn.get(dn, {})
        for field, text in [("capacity_tph", capacity), ("max_length_m", max_length),
                            ("new_rub_m", new_rub), ("recon_rub_m", recon_rub),
                            ("width_m", sizes[dn][3]), ("height_m", sizes[dn][4])]:
            expect(f"diameters[{dn}].{field}", row.get(field), num(text))

    chambers = table_after(md, "### Камеры и врезки")
    if len(chambers) != 4 or len(rules["chamber_cost"]) != 4:
        errors.append("chamber_cost: ожидалось 4 диапазона")
    for (dn_range, cost), rule in zip(chambers, rules["chamber_cost"]):
        dn_min, dn_max = re.findall(r"\d+", dn_range)
        expect(f"chamber_cost[{dn_range}].dn_min", rule["dn_min"], dn_min)
        expect(f"chamber_cost[{dn_range}].dn_max", rule["dn_max"], dn_max)
        expect(f"chamber_cost[{dn_range}].cost", rule["cost"], num(cost))

    flat = re.sub(r"\s+", " ", md)
    tie_in = re.search(rf"независимая врезка стоит ({NUM}) руб", flat)
    expect("tie_in_cost", rules["tie_in_cost"], num(tie_in.group(1)))
    penalty = re.search(rf"Ш_ОКС = ({NUM}) \+ ({NUM}) × G_ОКС", flat)
    expect("penalty.fixed", rules["penalty"]["fixed"], num(penalty.group(1)))
    expect("penalty.per_tph", rules["penalty"]["per_tph"], num(penalty.group(2)))
    score = re.search(rf"S = ({NUM}) × \(C / ({NUM})\) \+ ({NUM}) × \(L / ({NUM})\)", flat)
    expect("score.w_cost", rules["score"]["w_cost"], num(score.group(1)))
    expect("score.cost_base", rules["score"]["cost_base"], num(score.group(2)))
    expect("score.w_length", rules["score"]["w_length"], num(score.group(3)))
    expect("score.length_base_m", rules["score"]["length_base_m"], num(score.group(4)))

    chamber_rule = re.search(r"не дальше (\d+) м от неё и после подключения к камере примыкает не больше (\w+) участков", flat)
    words = {"трёх": 3, "четырёх": 4}
    expect("chamber_rule.max_dist_m", rules["chamber_rule"]["max_dist_m"], chamber_rule.group(1))
    expect("chamber_rule.max_segments", rules["chamber_rule"]["max_segments"], words[chamber_rule.group(2)])
    branches = re.search(r"один к источнику и не больше (\w+) в остальные стороны", flat)
    expect("chamber_rule.max_branches", rules["chamber_rule"]["max_branches"], words[branches.group(1)])

    restrictions = {}
    for row in table_after(md, "## 7. Пространственные ограничения"):
        restriction_type = re.search(r"`(\w+)`", row[0]).group(1)
        restrictions[restriction_type] = row
    if sorted(restrictions) != sorted(RESTRICTION_TYPES):
        errors.append(f"restrictions: типы в CONSTRAINTS.md {sorted(restrictions)}")
    half_widths = {
        "gas_pipeline": re.search(rf"\| Газопровод \| ({NUM}) × ", md).group(1),
        "power_cable": re.search(rf"\| Силовой кабель до 35 кВ \| ({NUM}) × ", md).group(1),
    }
    max_dn = max(by_dn)
    for restriction_type in RESTRICTION_TYPES:
        row = restrictions[restriction_type]
        rule = rules["restrictions"].get(restriction_type, {})
        name = f"restrictions.{restriction_type}"
        wanted_rule = "forbid" if "запрещено" in row[1] else "special"
        if rule.get("rule") != wanted_rule:
            errors.append(f"{name}.rule: rules.json={rule.get('rule')} CONSTRAINTS.md={wanted_rule}")
        clearances = [num(m) for m in re.findall(rf"({NUM}) м\b", row[2])]
        if restriction_type == "oks_existing":
            tiers = rule.get("clearance_m", [])
            bounds = [int(b) for b in re.findall(r"(\d+)(?:–\d+)? мм", row[2])]
            wanted_tiers = [
                (max(dn for dn in by_dn if dn < bounds[0]), clearances[0]),
                (int(re.search(r"500–(\d+) мм", row[2]).group(1)), clearances[1]),
                (max_dn, clearances[2]),
            ]
            if [(t["dn_max"], t["m"]) for t in tiers] != wanted_tiers:
                errors.append(f"{name}.clearance_m: rules.json={tiers} CONSTRAINTS.md={wanted_tiers}")
        else:
            expect(f"{name}.clearance_m", rule.get("clearance_m"), clearances[0])
        angle = re.search(r"не менее (\d+)°", row[3])
        if angle or "min_angle_deg" in rule:
            expect(f"{name}.min_angle_deg", rule.get("min_angle_deg"), angle.group(1) if angle else -1)
        margin = re.search(rf"(?:плюс|по) ({NUM}) м", row[5])
        if margin or "margin_m" in rule:
            expect(f"{name}.margin_m", rule.get("margin_m"), num(margin.group(1)) if margin else -1)
        k_special = re.search(NUM, row[6])
        if k_special or "k_special" in rule:
            expect(f"{name}.k_special", rule.get("k_special"), num(k_special.group(0)) if k_special else -1)
        if restriction_type in half_widths or "half_width_m" in rule:
            wanted = num(half_widths[restriction_type]) / 2 if restriction_type in half_widths else -1
            expect(f"{name}.half_width_m", rule.get("half_width_m"), wanted)
    return errors


def main() -> None:
    constraints_path, rules_path = sys.argv[1:3]
    md = Path(constraints_path).read_text(encoding="utf-8")
    rules = json.loads(Path(rules_path).read_text(encoding="utf-8"))
    errors = compare(md, rules)
    if errors:
        print("RULES MISMATCH")
        for error in errors:
            print(f"  {error}")
        sys.exit(1)
    print("RULES MATCH")


if __name__ == "__main__":
    main()
