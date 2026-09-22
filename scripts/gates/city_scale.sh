#!/usr/bin/env bash
# Гейты пакета city-scale. Запуск из корня: scripts/gates/city_scale.sh organizers|dense|slice|city
# organizers — выход на датасете организаторов побайтово равен эталону коммита e2e1018 (data/out/exp/real-baseline.out.geojson);
#   эталон не в git: это выход CLI на коммите e2e1018, он побайтово равен выходу v0.4.1 из scripts/bench.sh;
# dense — на сценах «густо» подключено не меньше ОКС, чем у эталона e2e1018 (он подключает не всех), и S rank 1 не хуже больше чем на 1 %;
# slice — срез города на 1600 ОКС, где пропускной способности сети хватает не всем, считается и проходит валидатор;
# city — файл «город» считается целиком при куче 12 ГБ не дольше часа.
set -uo pipefail
cd "$(dirname "$0")/../.."
source scripts/gates/env.sh
ensure_jar
out=data/out/exp
mkdir -p "$out"

case "${1:-}" in
organizers)
  java -jar target/heatnet.jar --cli data/real/dataset.geojson "$out/real-gate.out.geojson" > /dev/null 2>&1 || { echo "CLI упал"; exit 1; }
  cmp "$out/real-baseline.out.geojson" "$out/real-gate.out.geojson" && echo "ORGANIZERS SAME"
  ;;
dense)
  ok=1
  for triple in 50:146.514:49 100:380.164:94 200:890.049:185; do
    IFS=: read -r n base connected <<< "$triple"
    start=$(date +%s)
    java -jar target/heatnet.jar --cli "data/synth/dense-$n.geojson" "$out/dense-$n.gate.out.geojson" > /dev/null 2>&1 || { echo "густо-$n: CLI упал"; ok=0; continue; }
    python3 - "$out/dense-$n.gate.out.geojson" "$base" "$(( $(date +%s) - start ))" "$n" "$connected" <<'PY' || ok=0
import json, sys
path, base, sec, n, least = sys.argv[1], float(sys.argv[2]), sys.argv[3], int(sys.argv[4]), int(sys.argv[5])
data = json.load(open(path, encoding="utf-8"))
s = next(f["properties"] for f in data["features"] if f["properties"]["object_type"] == "variant_summary" and f["properties"]["rank"] == 1)
connected = n - len(s["unconnected_oks_ids"])
print(f"густо-{n}: S {s['score']} (эталон {base}), {sec} с, подключено {connected} (эталон {least})")
sys.exit(0 if connected >= least and s["score"] <= base * 1.01 else 1)
PY
  done
  [ "$ok" -eq 1 ] && echo "DENSE OK"
  ;;
slice)
  [ -f data/out/scale/near-1600.geojson ] || python3 scripts/city_scale_extract.py data/synth/city-1.geojson data/out/scale/near -n 1600 > /dev/null
  java -Xmx4g -XX:MaxNewSize=512m -jar target/heatnet.jar --cli data/out/scale/near-1600.geojson "$out/near-1600.gate.out.geojson" > /dev/null 2>&1 || { echo "CLI упал"; exit 1; }
  uv run --project tools python -m heatcheck data/out/scale/near-1600.geojson "$out/near-1600.gate.out.geojson" > "$out/near-1600.gate.check.txt" 2>&1
  status=$?
  tail -3 "$out/near-1600.gate.check.txt"
  [ "$status" -eq 0 ] && echo "SLICE VALID"
  ;;
city)
  /usr/bin/time -l java -Xmx12g -XX:MaxNewSize=512m -jar target/heatnet.jar --cli data/synth/city-1.geojson "$out/city-1.out.geojson" > "$out/city-1.gate.log" 2> "$out/city-1.gate.err"
  status=$?
  elapsed=$(awk '/ real /{print int($1)}' "$out/city-1.gate.err")
  peak=$(awk '/maximum resident set size/{print int($1 / 1048576)}' "$out/city-1.gate.err")
  echo "код $status, $elapsed с, пиковая память $peak МБ"
  [ "$status" -eq 0 ] && [ "$elapsed" -le 3600 ] && grep -q '"variant_summary"' "$out/city-1.out.geojson" && echo "CITY SCALE OK"
  ;;
*)
  echo "режим: organizers|dense|slice|city"; exit 2
  ;;
esac
