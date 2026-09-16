#!/usr/bin/env bash
# Стенд качества: S, статьи стоимости и время расчёта на датасете организаторов и сценах medium (сиды 1–3).
# Использование: scripts/bench.sh [метка]. Сцены генерируются, если их нет. Печатает таблицу и сумму S.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/gates/env.sh
ensure_jar
mkdir -p data/synth data/out/bench
for seed in 1 2 3; do
  input="data/synth/medium-$seed.geojson"
  [ -f "$input" ] || uv run --project tools python -m heatsynth --preset medium --seed "$seed" --out "$input" >&2
done
label="${1:-run}"
python3 - "$label" <<'PY'
import json, subprocess, sys, time
label = sys.argv[1]
inputs = [("dataset", "data/real/dataset.geojson")] + [(f"medium-{s}", f"data/synth/medium-{s}.geojson") for s in (1, 2, 3)]
total = 0.0
print(f"{'сцена':10} {'S':>7} {'стоим':>7} {'участ':>6} {'камер':>6} {'врезк':>6} {'рекон':>6} {'длина':>6} {'сек':>5}")
for name, path in inputs:
    out = f"data/out/bench/{name}-{label}.geojson"
    t0 = time.monotonic()
    subprocess.run(["java", "-jar", "target/heatnet.jar", "--cli", path, out], check=True, capture_output=True)
    dt = time.monotonic() - t0
    data = json.load(open(out, encoding="utf-8"))
    s = next(f["properties"] for f in data["features"] if f["properties"]["object_type"] == "variant_summary" and f["properties"]["rank"] == 1)
    total += s["score"]
    m = lambda k: s[k] / 1e6
    print(f"{name:10} {s['score']:7.3f} {m('calculated_cost'):7.1f} {m('construction_cost'):6.1f} {m('chamber_construction_cost'):6.1f} {m('tie_in_cost'):6.1f} {m('reconstruction_cost')+m('chamber_reconstruction_cost'):6.1f} {s['length']:6.0f} {dt:5.0f}")
print(f"сумма S: {total:.3f}")
PY
