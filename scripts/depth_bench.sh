#!/usr/bin/env bash
# Бенч режима глубины: CLI через общую очередь, время/память, check18, S лучшего варианта.
# Использование: [FULL=1] scripts/depth_bench.sh <jar> <метка> <режим 2d|3d> [набор ...]
# Выход: data/depth/runs/<метка>/<набор>/{out.geojson,cli.err,check18.txt}, строка в data/depth/runs/<метка>/summary.txt
set -u
cd "$(dirname "$0")/.."
source scripts/env.sh
JAR=$1; TAG=$2; MODE=$3; shift 3
input() {
  case $1 in
    dataset) echo data/depth/dataset_clean.geojson ;;
    dataset-gas) echo data/depth/dataset_gas.geojson ;;
    moscow) echo data/cities/moscow/input_1809.geojson ;;
    spb) echo data/cities/spb/input_1809.geojson ;;
  esac
}
FLAG=""; CHECK=""
[ "$MODE" = 3d ] && FLAG="--depth" && CHECK="--depth"
for s in ${*:-dataset dataset-gas spb moscow}; do
  IN=$(input $s); R=data/depth/runs/$TAG/$MODE/$s; mkdir -p $R
  data/cities/runs/run_cli.sh java -Xmx12g -jar "$JAR" --cli $FLAG "$IN" $R/out.geojson > $R/cli.log 2> $R/cli.err
  code=$?
  t=$(awk '/ real /{print $1}' $R/cli.err); m=$(awk '/maximum resident/{printf "%.1f", $1/1073741824}' $R/cli.err)
  line="$s $MODE код $code ${t} с ${m} ГБ"
  if [ $code -eq 0 ]; then
    if [ "$(stat -L -f %z "$IN")" -gt 20000000 ] && [ "${FULL:-0}" != 1 ]; then
      # город: check18 по срезу 3×3 км (полный — FULL=1, Москва ~30 мин)
      python3 scripts/city_cut.py "$IN" $R/out.geojson $R/cut.in.geojson $R/cut.out.geojson > /dev/null
      uv run --project tools python tools/validator/check18.py $CHECK $R/cut.in.geojson $R/cut.out.geojson > $R/check18.txt 2>&1
      cut="срез "
    else
      uv run --project tools python tools/validator/check18.py $CHECK "$IN" $R/out.geojson > $R/check18.txt 2>&1
      cut=""
    fi
    line="$line | $(python3 scripts/depth_summary.py $R/out.geojson) | ${cut}$(tail -1 $R/check18.txt)"
  fi
  echo "$line" | tee -a data/depth/runs/$TAG/summary.txt
done
