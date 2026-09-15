#!/usr/bin/env bash
set -euo pipefail
# Случайный прогон: 300 сидов генератора с чередованием подмножеств типов через CLI и все правила heatcheck.
# Маркеры SWEEP TYPES OK и SWEEP OK печатает heatscen.sweep после всех проверок, итог пишется в data/scenarios/sweep.json.
cd "$(dirname "$0")/../.."
source scripts/gates/env.sh
ensure_jar
uv run --project tools python -m heatscen.sweep --seeds 300 --workers 3
