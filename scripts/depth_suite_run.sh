#!/usr/bin/env bash
set -u
cd "$(dirname "$0")/.."
source scripts/env.sh
JAR=$1; OUT=${2:-data/depth/suite_runs}
mkdir -p $OUT
printf "| Случай | граница выше C | вынужд. зон | C, руб. | разрыв, руб. | разрыв в допущ., руб. | строгая | G | check18 3D | замечания только 3D |\n|---|---|---|---|---|---|---|---|---|---|\n" > $OUT/summary.md
for IN in data/samples/depth/*.geojson; do
  n=$(basename $IN .geojson)
  java -jar $JAR --cli --depth $IN $OUT/$n.3d.geojson > /dev/null 2> $OUT/$n.3d.err || { echo "| $n | CLI 3D FAIL |" >> $OUT/summary.md; continue; }
  java -jar $JAR --cli $IN $OUT/$n.2d.geojson > /dev/null 2> $OUT/$n.2d.err
  b=$(uv run --project tools python tools/validator/depth_bound.py $IN $OUT/$n.3d.geojson)
  bi=$(uv run --project tools python tools/validator/depth_bound.py --impl $IN $OUT/$n.3d.geojson)
  st=$(uv run --project tools python tools/validator/depth_strict.py $OUT/$n.3d.geojson | tail -1)
  c3=$(uv run --project tools python tools/validator/check18.py --depth $IN $OUT/$n.3d.geojson 2>&1); echo "$c3" > $OUT/$n.check18_3d.txt
  c2=$(uv run --project tools python tools/validator/check18.py $IN $OUT/$n.2d.geojson 2>&1); echo "$c2" > $OUT/$n.check18_2d.txt
  zones=$(echo "$b" | grep -o 'вынужденных зон [0-9]*' | awk '{s+=$3} END{print s+0}')
  cost=$(echo "$b" | grep -o 'C=[0-9.]*' | head -1 | cut -d= -f2)
  gap=$(echo "$b" | grep -o 'разрыв=-\?[0-9.]*' | awk -F= '{s+=$2} END{printf "%.0f", s}')
  gapi=$(echo "$bi" | grep -o 'разрыв=-\?[0-9.]*' | awk -F= '{s+=$2} END{printf "%.0f", s}')
  g=$(echo "$c3" | grep -cE '^  G[0-9]')
  k3=$(echo "$c3" | grep -E '^  [A-G][0-9]+ ' | sed 's/:.*//' | sort -u); k2=$(echo "$c2" | grep -E '^  [A-G][0-9]+ ' | sed 's/:.*//' | sort -u)
  only=$(comm -23 <(echo "$k3") <(echo "$k2") | awk '{print $1}' | tr '\n' ' ')
  bf=$(printf '%s\n%s\n' "$b" "$bi" | grep -c "BOUND FAIL")
  echo "| $n | $bf | $zones | $cost | $gap | $gapi | $st | $g | $(echo "$c3" | tail -1) | ${only:-—} |" >> $OUT/summary.md
done
cat $OUT/summary.md
