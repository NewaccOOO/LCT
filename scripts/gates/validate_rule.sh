#!/usr/bin/env bash
set -euo pipefail
# Проверяет одно правило heatcheck на шести парах из produce.sh. Маркер RULE печатает только heatcheck.
cd "$(dirname "$0")/../.."

rule="${1:?укажите правило: scripts/gates/validate_rule.sh RULE}"
bash scripts/gates/produce.sh

failed=0
checked=0
outputs=()
for preset in small medium; do
  for seed in 1 2 3; do
    input="data/synth/$preset-$seed.geojson"
    output="data/out/$preset-$seed.geojson"
    outputs+=("$output")
    echo "== $preset-$seed"
    if ! report=$(uv run --project tools python -m heatcheck "$input" "$output" --only "$rule"); then
      failed=1
    fi
    printf '%s\n' "$report"
    n=$(printf '%s\n' "$report" | sed -n 's/^checked=//p')
    checked=$((checked + ${n:-0}))
  done
done

if [ "$failed" -ne 0 ]; then
  echo "validate_rule.sh: правило $rule не прошло хотя бы на одном наборе" >&2
  exit 1
fi
if [ "$checked" -eq 0 ]; then
  echo "validate_rule.sh: правило $rule ничего не проверило, сумма checked по шести наборам 0" >&2
  exit 1
fi

if [ "$rule" = reconstruction ] || [ "$rule" = special ]; then
  count=$(uv run --project tools python -c '
import json
import sys

rule, paths = sys.argv[1], sys.argv[2:]
total = 0
for path in paths:
    for feature in json.load(open(path, encoding="utf-8"))["features"]:
        props = feature["properties"]
        if rule == "reconstruction":
            total += props.get("object_type") == "heat_network_reconstruction"
        else:
            total += props.get("object_type") == "heat_network" and props.get("laying_method") == "special"
print(total)
' "$rule" "${outputs[@]}")
  if [ "$count" -eq 0 ]; then
    echo "validate_rule.sh: в шести выходах нет ни одного объекта для правила $rule, проверка прошла вхолостую" >&2
    exit 1
  fi
fi
