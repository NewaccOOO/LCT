#!/usr/bin/env bash
set -euo pipefail
# NFR-1: расчёт пресета medium через CLI для сидов 1–3, каждый не дольше 120 секунд стенных часов.
cd "$(dirname "$0")/../.."
source scripts/gates/env.sh
ensure_jar

LIMIT_S=120
mkdir -p data/synth data/out
for seed in 1 2 3; do
  input="data/synth/medium-$seed.geojson"
  if [ ! -f "$input" ]; then
    uv run --project tools python -m heatsynth --preset medium --seed "$seed" --out "${input%.geojson}.tmp.geojson" >&2
    mv "${input%.geojson}.tmp.geojson" "$input"
  fi
done

# Время меряет python3 (time.monotonic): так же работает с bash 3.2 на macOS и bash 5 на Ubuntu.
python3 - "$LIMIT_S" <<'EOF'
import math
import subprocess
import sys
import time

limit = float(sys.argv[1])
worst = 0.0
ok = True
for seed in (1, 2, 3):
    command = ["java", "-jar", "target/heatnet.jar", "--cli",
               f"data/synth/medium-{seed}.geojson", f"data/out/perf-medium-{seed}.geojson"]
    started = time.monotonic()
    code = subprocess.call(command, stdout=subprocess.DEVNULL)
    elapsed = time.monotonic() - started
    print(f"medium-{seed}: код {code}, {elapsed:.1f} с", file=sys.stderr)
    worst = max(worst, elapsed)
    ok = ok and code == 0 and elapsed <= limit
if not ok:
    sys.exit(f"perf.sh: не все три запуска завершились кодом 0 за {limit:.0f} с")
print(f"PERF medium OK max={math.ceil(worst)}s")
EOF
