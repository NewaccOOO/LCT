#!/usr/bin/env bash
# Гейты AC-0.1 и AC-0.2 пакета city-dataset: прежние пресеты генератора не изменились, прежние тесты зелёные.
# Эталон data/synth/medium-1.base.geojson снимается до правок: cp data/synth/medium-1.geojson data/synth/medium-1.base.geojson
set -uo pipefail
cd "$(dirname "$0")/../.."
[ -f data/synth/medium-1.base.geojson ] || { echo "нет эталона data/synth/medium-1.base.geojson: снять до правок"; exit 1; }
uv run --project tools python -m heatsynth --preset medium --seed 1 --out data/synth/medium-1.regress.geojson > /dev/null 2>&1 || { echo "генератор medium упал"; exit 1; }
cmp -s data/synth/medium-1.regress.geojson data/synth/medium-1.base.geojson && echo "SYNTH REGRESS OK" || { echo "пресет medium изменился"; exit 1; }
make test-python > /dev/null 2>&1 || { echo "test-python упал"; exit 1; }
make test-scenarios > /dev/null 2>&1 || { echo "test-scenarios упал"; exit 1; }
echo "SYNTH TESTS OK"
