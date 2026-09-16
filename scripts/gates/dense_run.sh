#!/usr/bin/env bash
# Гейт AC-2.2 пакета city-dataset: на сценах «густо» сервис подключает все ОКС. Печатает S и время каждой сцены и DENSE RUN OK.
set -uo pipefail
cd "$(dirname "$0")/../.."
source scripts/gates/env.sh
ensure_jar
mkdir -p data/out/dense
ok=1
for n in 50 100 200; do
  start=$(date +%s)
  java -jar target/heatnet.jar --cli "data/synth/dense-$n.geojson" "data/out/dense/dense-$n.out.geojson" > /dev/null 2>&1 || { echo "густо-$n: CLI упал"; ok=0; continue; }
  uv run --project tools python - "$n" "$(( $(date +%s) - start ))" <<'PY' || ok=0
import json, sys
n, sec = sys.argv[1], sys.argv[2]
data = json.load(open(f"data/out/dense/dense-{n}.out.geojson", encoding="utf-8"))
s = next(f["properties"] for f in data["features"] if f["properties"]["object_type"] == "variant_summary" and f["properties"]["rank"] == 1)
print(f"густо-{n}: S {s['score']}, {sec} с, не подключены: {s['unconnected_oks_ids']}")
sys.exit(0 if not s["unconnected_oks_ids"] else 1)
PY
done
[ "$ok" -eq 1 ] && echo "DENSE RUN OK"
