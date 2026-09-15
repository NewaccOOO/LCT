"""Сверка ключей новых типов ограничений в rules.json с таблицей «Нормы для типов вне таблицы ТП» из research.md."""
import json
import sys
from pathlib import Path
from typing import Any

from heatcheck.rules_check import (
    num,
    table_after,
)

HEADING = "Нормы для типов вне таблицы ТП"
COLUMNS = ["restriction_type", "rule", "clearance_m", "min_angle_deg", "margin_m", "k_special", "Основание"]
NUMBER_KEYS = ["clearance_m", "min_angle_deg", "margin_m", "k_special"]
ROWS = 6
DASH = "—"
NUM_EPS = 1e-9


def compare(md: str, rules: dict[str, Any]) -> list[str]:
    if HEADING not in md:
        return [f"в research.md нет раздела «{HEADING}»"]
    header_line = next(line for line in md[md.index(HEADING):].splitlines() if line.startswith("|"))
    header = [cell.strip() for cell in header_line.strip("|").split("|")]
    if header != COLUMNS:
        return [f"колонки таблицы {header}, ожидались {COLUMNS}"]
    rows = table_after(md, HEADING)
    errors = [] if len(rows) == ROWS else [f"строк в таблице {len(rows)}, ожидалось {ROWS}"]
    for row in rows:
        if len(row) != len(COLUMNS):
            errors.append(f"строка {row}: ячеек {len(row)}, ожидалось {len(COLUMNS)}")
            continue
        cells = dict(zip(COLUMNS, row))
        restriction_type = cells["restriction_type"].strip("`")
        rule = rules["restrictions"].get(restriction_type)
        name = f"restrictions.{restriction_type}"
        if rule is None:
            errors.append(f"{name}: типа нет в rules.json")
            continue
        if cells["Основание"] in ("", DASH):
            errors.append(f"{name}: в таблице не указано основание")
        if rule.get("rule") != cells["rule"]:
            errors.append(f"{name}.rule: rules.json={rule.get('rule')} research.md={cells['rule']}")
        for key in NUMBER_KEYS:
            actual, text = rule.get(key), cells[key]
            if text == DASH:
                if key in rule:
                    errors.append(f"{name}.{key}: rules.json={actual}, в research.md прочерк и ключа быть не должно")
            elif isinstance(actual, bool) or not isinstance(actual, int | float) or abs(actual - num(text)) > NUM_EPS:
                errors.append(f"{name}.{key}: rules.json={actual} research.md={text}")
    return errors


def main() -> None:
    research_path, rules_path = sys.argv[1:3]
    md = Path(research_path).read_text(encoding="utf-8")
    rules = json.loads(Path(rules_path).read_text(encoding="utf-8"))
    errors = compare(md, rules)
    if errors:
        print("RULES EXT MISMATCH")
        for error in errors:
            print(f"  {error}")
        sys.exit(1)
    print("RULES EXT MATCH")


if __name__ == "__main__":
    main()
