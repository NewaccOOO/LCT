import argparse
import json
import sys
from pathlib import Path

from heatcheck import RULES
from heatcheck.validate import (
    render_all,
    render_only,
    run_all,
    run_rule,
)


def main() -> None:
    parser = argparse.ArgumentParser(prog="heatcheck", description="Проверка выходного GeoJSON по правилам кейса")
    parser.add_argument("input", help="входной GeoJSON")
    parser.add_argument("output", help="выходной GeoJSON сервиса")
    parser.add_argument("--only", choices=RULES, help="проверить одно правило")
    parser.add_argument("--rules", default="rules/rules.json", help="путь к rules.json")
    args = parser.parse_args()
    input_data, output_data, rules = (
        json.loads(Path(path).read_text(encoding="utf-8")) for path in (args.input, args.output, args.rules)
    )
    if args.only:
        result = run_rule(args.only, input_data, output_data, rules)
        print(render_only(args.only, result))
        sys.exit(1 if result.violations else 0)
    results = run_all(input_data, output_data, rules)
    print(render_all(results))
    sys.exit(1 if any(result.violations for result in results.values()) else 0)


if __name__ == "__main__":
    main()
