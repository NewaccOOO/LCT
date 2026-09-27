#!/usr/bin/env bash
# Прогоны P2 подряд в одном слоте очереди. Режимы (MODES): base — как v0.9.0; obst — oks_future препятствие;
# full — ещё и врезка вне зон. Запуск: run_cli.sh bash p2_batch.sh <jar> <набор...>
JAR=$1; shift
MAIN=/Users/a1111/HACK/LCT/LCT/.claude/worktrees/geojson-heat-network-moscow-spb-ea030e
OUT=$MAIN/data/cities/problems/p2-dense/runs
mkdir -p "$OUT"
for name in "$@"; do
  case $name in
    spb-v1) IN=$MAIN/data/cities/runs/spb-v1/input.geojson ;;
    spb-v1-1809) IN=$MAIN/data/cities/runs/spb-v1-1809/input.geojson ;;
    *) IN=$MAIN/data/cities/runs/bench/$name.geojson ;;
  esac
  for mode in ${MODES:-base full}; do
    case $mode in
      base) P="-Dheatnet.tie.free=false -Dheatnet.future.obstacle=false" ;;
      obst) P="-Dheatnet.tie.free=false -Dheatnet.future.obstacle=true" ;;
      full) P="-Dheatnet.tie.free=true -Dheatnet.future.obstacle=true" ;;
    esac
    java -Xmx5g $P -jar "$JAR" --cli "$IN" "$OUT/$name.$mode.out.geojson" > "$OUT/$name.$mode.log" 2>&1
    echo "$name $mode exit $?"
  done
done
