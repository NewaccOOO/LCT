#!/usr/bin/env bash
set -euo pipefail
# Готовит шесть пар «синтетика → выход CLI» для validate_rule.sh: пресеты small и medium, сиды 1–3.
cd "$(dirname "$0")/../.."
source scripts/gates/env.sh

PRESETS=(small medium)
SEEDS=(1 2 3)
mkdir -p data/synth data/out

for preset in "${PRESETS[@]}"; do
  for seed in "${SEEDS[@]}"; do
    input="data/synth/$preset-$seed.geojson"
    if [ ! -f "$input" ] || [ -n "$(find tools/synth -type f -not -path '*/__pycache__/*' -newer "$input" -print -quit)" ]; then
      uv run --project tools python -m heatsynth --preset "$preset" --seed "$seed" --out "${input%.geojson}.tmp.geojson"
      mv "${input%.geojson}.tmp.geojson" "$input"
    fi
  done
done

if [ ! -f pom.xml ]; then
  echo "produce.sh: jar не собран: в корне нет pom.xml, собрать target/heatnet.jar не из чего" >&2
  exit 1
fi
ensure_jar

for preset in "${PRESETS[@]}"; do
  for seed in "${SEEDS[@]}"; do
    input="data/synth/$preset-$seed.geojson"
    output="data/out/$preset-$seed.geojson"
    if [ ! -f "$output" ] || [ target/heatnet.jar -nt "$output" ] || [ "$input" -nt "$output" ]; then
      java -jar target/heatnet.jar --cli "$input" "${output%.geojson}.tmp.geojson"
      mv "${output%.geojson}.tmp.geojson" "$output"
    fi
  done
done
