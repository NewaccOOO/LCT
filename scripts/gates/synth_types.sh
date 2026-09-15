#!/usr/bin/env bash
set -euo pipefail
# Проверяет генератор без сервиса: для каждого подмножества типов из heatscen.sweep генерирует small дважды
# с одним сидом и префиксом, сверяет копии побайтно, затем heatsynth.check сверяет состав типов и префикс id.
cd "$(dirname "$0")/../.."
subsets=$(uv run --project tools python -c 'from heatscen.sweep import SUBSETS; print("\n".join(",".join(types) for types in SUBSETS.values()))')
mkdir -p data/synth
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

count=0
for types in $subsets; do
  prefix="t$count-"
  out="data/synth/types-$count.geojson"
  uv run --project tools python -m heatsynth --preset small --seed 1 --types "$types" --id-prefix "$prefix" --out "$out"
  uv run --project tools python -m heatsynth --preset small --seed 1 --types "$types" --id-prefix "$prefix" --out "$tmp/types-$count.geojson"
  cmp "$out" "$tmp/types-$count.geojson"
  uv run --project tools python -m heatsynth.check "$out" --types "$types" --id-prefix "$prefix"
  first="${first:-$types}"
  count=$((count + 1))
done
if [ "$count" -ne 4 ]; then
  echo "synth_types.sh: подмножеств $count, нужно 4" >&2
  exit 1
fi

# Контроль, что проверка не пустая: чужой состав типов и чужой префикс на том же файле должны упасть.
if uv run --project tools python -m heatsynth.check data/synth/types-0.geojson --types park --id-prefix t0- >/dev/null 2>&1; then
  echo "synth_types.sh: heatsynth.check не заметил лишние типы ограничений" >&2
  exit 1
fi
if uv run --project tools python -m heatsynth.check data/synth/types-0.geojson --types "$first" --id-prefix x- >/dev/null 2>&1; then
  echo "synth_types.sh: heatsynth.check не заметил id без префикса" >&2
  exit 1
fi
echo "SYNTH TYPES OK subsets=$count"
