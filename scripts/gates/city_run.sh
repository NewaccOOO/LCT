#!/usr/bin/env bash
# Гейт NFR-2 пакета city-dataset: сервис считает файл «город» через CLI до конца при куче 12 ГБ.
# Печатает CITY RUN OK и пиковую память процесса. Запуск из корня, занимает минуты.
set -uo pipefail
cd "$(dirname "$0")/../.."
source scripts/gates/env.sh
ensure_jar
mkdir -p data/out/city
/usr/bin/time -l java -Xmx12g -jar target/heatnet.jar --cli data/synth/city-1.geojson data/out/city/city-1.out.geojson 2> data/out/city/city-1.time.log
status=$?
peak=$(grep "maximum resident set size" data/out/city/city-1.time.log | awk '{print $1}')
echo "код $status, пиковая память $((peak / 1024 / 1024)) МБ"
[ "$status" -eq 0 ] && grep -q '"variant_summary"' data/out/city/city-1.out.geojson && echo "CITY RUN OK"
