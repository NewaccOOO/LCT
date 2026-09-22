#!/usr/bin/env bash
# Гейты пакета rules-1809. Запуск из корня: scripts/gates/rules_1809.sh organizers|dense|city
# organizers — оба датасета организаторов считаются, все точки подключены, tools/validator/check18.py без нарушений;
# dense — сцены «густо» 50/100/200 считаются и проходят check18;
# city — файл «город» считается целиком при куче 12 ГБ не дольше часа, срез выхода проходит check18, неподключённых нет.
set -uo pipefail
cd "$(dirname "$0")/../.."
source scripts/gates/env.sh
ensure_jar
out=data/out/r18
mkdir -p "$out"
check() { uv run --project tools python tools/validator/check18.py "$1" "$2" > "$3" 2>&1; local s=$?; tail -1 "$3"; return $s; }

case "${1:-}" in
organizers)
  ok=1
  for pair in real:data/real/dataset.geojson corrected:data/real/dataset-corrected.geojson; do
    IFS=: read -r name input <<< "$pair"
    java -jar target/heatnet.jar --cli "$input" "$out/$name.out.geojson" > "$out/$name.log" 2>&1 || { echo "$name: CLI упал"; ok=0; continue; }
    check "$input" "$out/$name.out.geojson" "$out/$name.check18.txt" || ok=0
    python3 - "$out/$name.out.geojson" "$name" <<'PY' || ok=0
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
rows = [f["properties"] for f in data["features"] if f["properties"]["object_type"] == "variant_summary"]
for s in rows:
    print(f"{sys.argv[2]}: вариант {s['variant_id']} S {s['score']} неподключённых {len(s['unconnected_oks_ids'])}")
sys.exit(0 if rows and all(not s["unconnected_oks_ids"] for s in rows) else 1)
PY
  done
  [ "$ok" -eq 1 ] && echo "ORGANIZERS OK"
  ;;
dense)
  ok=1
  for n in 50 100 200; do
    start=$(date +%s)
    java -jar target/heatnet.jar --cli "data/synth/dense-$n.geojson" "$out/dense-$n.out.geojson" > "$out/dense-$n.log" 2>&1 || { echo "густо-$n: CLI упал"; ok=0; continue; }
    python3 - "$out/dense-$n.out.geojson" "$(( $(date +%s) - start ))" "$n" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
s = next(f["properties"] for f in data["features"] if f["properties"]["object_type"] == "variant_summary" and f["properties"]["rank"] == 1)
print(f"густо-{sys.argv[3]}: S {s['score']}, {sys.argv[2]} с, подключено {int(sys.argv[3]) - len(s['unconnected_oks_ids'])}")
PY
    check "data/synth/dense-$n.geojson" "$out/dense-$n.out.geojson" "$out/dense-$n.check18.txt" || ok=0
  done
  [ "$ok" -eq 1 ] && echo "DENSE OK"
  ;;
city)
  /usr/bin/time -l java -Xmx12g -XX:MaxNewSize=512m -jar target/heatnet.jar --cli data/synth/city-1.geojson "$out/city-1.out.geojson" > "$out/city-1.log" 2> "$out/city-1.err"
  status=$?
  elapsed=$(awk '/ real /{print int($1)}' "$out/city-1.err")
  peak=$(awk '/maximum resident set size/{print int($1 / 1048576)}' "$out/city-1.err")
  echo "код $status, $elapsed с, пиковая память $peak МБ"
  [ "$status" -eq 0 ] || exit 1
  python3 scripts/city_cut.py data/synth/city-1.geojson "$out/city-1.out.geojson" "$out/city-1.cut.geojson" "$out/city-1.cut.out.geojson" || exit 1
  check "$out/city-1.cut.geojson" "$out/city-1.cut.out.geojson" "$out/city-1.check18.txt" || exit 1
  [ "$elapsed" -le 3600 ] && echo "CITY OK"
  ;;
*)
  echo "режим: organizers|dense|city"; exit 2
  ;;
esac
