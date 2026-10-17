#!/usr/bin/env bash
# Стресс режима глубины: случайные миры seed from..to — jar, сертификат depth_bound (по правилу и --impl), строгая
# проверка depth_strict и check18 --depth. Строка на мир.
JAR=$1; FROM=$2; TO=$3; D=${4:-data/depth/stress/world}
mkdir -p $D
for s in $(seq $FROM $TO); do
  uv run --project tools python scripts/depth_world.py $s $D/w$s.geojson
  java -jar $JAR --cli --depth $D/w$s.geojson $D/w$s.out.geojson > /dev/null 2> $D/w$s.err || { echo "w$s CLI FAIL"; continue; }
  b=$(uv run --project tools python tools/validator/depth_bound.py $D/w$s.geojson $D/w$s.out.geojson)
  bi=$(uv run --project tools python tools/validator/depth_bound.py --impl $D/w$s.geojson $D/w$s.out.geojson)
  st=$(uv run --project tools python tools/validator/depth_strict.py $D/w$s.out.geojson | tail -1)
  c=$(uv run --project tools python tools/validator/check18.py --depth $D/w$s.geojson $D/w$s.out.geojson 2>&1)
  zones=$(echo "$b" | grep -o 'вынужденных зон [0-9]*' | awk '{s+=$3} END{print s}')
  gap=$(echo "$b" | grep -o 'разрыв=-\?[0-9.]*' | awk -F= '{s+=$2} END{printf "%.0f", s}')
  gapi=$(echo "$bi" | grep -o 'разрыв=-\?[0-9.]*' | awk -F= '{s+=$2} END{printf "%.0f", s}')
  cost=$(echo "$b" | grep -o 'C=[0-9.]*' | awk -F= '{s+=$2} END{printf "%.0f", s}')
  g=$(echo "$c" | grep -cE '^  G[0-9]')
  other=$(echo "$c" | grep -E '^  [A-F][0-9]' | sed 's/:.*//' | awk '{print $1}' | sort -u | tr '\n' ',')
  bf=$(printf '%s\n%s\n' "$b" "$bi" | grep -c "BOUND FAIL")
  echo "w$s граница_выше=$bf зон=$zones ручн=$(grep -c ручная $D/w$s.err) C=$cost разрыв=$gap разрыв_impl=$gapi $st G=$g прочие=[$other] $(echo "$c" | tail -1)"
done
