#!/usr/bin/env bash
# Прогон jar по всем наборам: CLI через очередь, check18 (город целиком или срез 3×3 км), сводка analyze.py.
# Использование: bench_all.sh <jar> <метка> [набор ...]
set -u
cd "$(dirname "$0")/../../.."
source scripts/env.sh
JAR=$1; TAG=$2; shift 2
declare -A IN=(
  [dataset]=data/cities/runs/bench/dataset.geojson
  [dataset-corrected]=data/cities/runs/bench/dataset-corrected.geojson
  [medium-1]=data/cities/runs/bench/medium-1.geojson
  [medium-2]=data/cities/runs/bench/medium-2.geojson
  [medium-3]=data/cities/runs/bench/medium-3.geojson
  [spb]=data/cities/spb/input.geojson
  [spb-1809]=data/cities/spb/input_1809.geojson
  [moscow]=data/cities/moscow/input.geojson
  [moscow-1809]=data/cities/moscow/input_1809.geojson
  [nn]=data/cities/nnovgorod/input.geojson
  [nn-1809]=data/cities/nnovgorod/input_1809.geojson
)
SETS=${*:-dataset dataset-corrected medium-1 medium-2 medium-3 spb spb-1809 moscow moscow-1809 nn nn-1809}
for s in $SETS; do
  R=data/cities/runs/$TAG/$s; mkdir -p $R
  data/cities/runs/run_cli.sh java -Xmx12g -jar "$JAR" --cli "${IN[$s]}" $R/out.geojson > $R/cli.log 2> $R/cli.err
  code=$?
  t=$(awk '/ real /{print $1}' $R/cli.err); m=$(awk '/maximum resident/{printf "%.1f", $1/1073741824}' $R/cli.err)
  echo "$s код $code ${t} с ${m} ГБ" | tee -a data/cities/runs/$TAG/summary.txt
  [ $code -eq 0 ] || continue
  n=$(grep -c . "${IN[$s]}" 2>/dev/null)
  if [ "$(stat -f %z "${IN[$s]}")" -gt 20000000 ]; then
    python3 scripts/city_cut.py "${IN[$s]}" $R/out.geojson $R/cut.in.geojson $R/cut.out.geojson > /dev/null
    uv run --project tools python tools/validator/check18.py $R/cut.in.geojson $R/cut.out.geojson > $R/check18.txt 2>&1
    echo "  check18 (срез 3×3 км): $(tail -1 $R/check18.txt)" | tee -a data/cities/runs/$TAG/summary.txt
  else
    uv run --project tools python tools/validator/check18.py "${IN[$s]}" $R/out.geojson > $R/check18.txt 2>&1
    echo "  check18: $(tail -1 $R/check18.txt)" | tee -a data/cities/runs/$TAG/summary.txt
  fi
  python3 data/cities/runs/analyze.py $s "${IN[$s]}" $R/out.geojson $R/cli.log > $R/analyze.json 2>/dev/null
done
