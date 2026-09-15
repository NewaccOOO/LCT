#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
# Генерирует три пресета дважды, сверяет копии побайтно и только потом проверяет содержимое.
mkdir -p data/synth
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
for preset in small medium large; do
  uv run --project tools python -m heatsynth --preset "$preset" --seed 1 --out "data/synth/$preset-1.geojson"
  uv run --project tools python -m heatsynth --preset "$preset" --seed 1 --out "$tmp/$preset-1.geojson"
  cmp "data/synth/$preset-1.geojson" "$tmp/$preset-1.geojson"
done
uv run --project tools python -m heatsynth.check data/synth/small-1.geojson
uv run --project tools python -m heatsynth.check data/synth/medium-1.geojson --preset medium
uv run --project tools python -m heatsynth.check data/synth/large-1.geojson
