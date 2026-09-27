#!/usr/bin/env bash
# Прогоны P6: прототип (несколько source) против v0.9.0 на датасете, medium-1..3 и СПб с тремя источниками.
MAIN=/Users/a1111/HACK/LCT/LCT/.claude/worktrees/geojson-heat-network-moscow-spb-ea030e
Q=$MAIN/data/cities/runs/run_cli.sh
PROTO=/Users/a1111/HACK/LCT/LCT/.claude/worktrees/agent-ad18cee7f2c72a4ef/build/libs/heatnet.jar
BASE=$MAIN/build/libs/heatnet.jar
OUT=$MAIN/data/cities/problems/p6-sources/runs
mkdir -p "$OUT"
run() { # имя jar вход
  local name=$1 jar=$2 in=$3
  local start=$(date +%s)
  $Q java -Xmx5g -jar "$jar" --cli "$in" "$OUT/$name.out.geojson" > "$OUT/$name.log" 2>&1
  echo "$name exit=$? $(( $(date +%s) - start ))s" | tee -a "$OUT/times.txt"
}
: > "$OUT/times.txt"
for b in dataset dataset-corrected medium-1 medium-2 medium-3; do
  run "proto-$b" "$PROTO" "$MAIN/data/cities/runs/bench/$b.geojson"
done
run base-multi "$BASE" "$MAIN/data/cities/problems/p6-sources/multi.geojson"
run proto-multi "$PROTO" "$MAIN/data/cities/problems/p6-sources/multi.geojson"
run proto-single "$PROTO" "$MAIN/data/cities/problems/p6-sources/single.geojson"
run base-single "$BASE" "$MAIN/data/cities/problems/p6-sources/single.geojson"
echo ALL DONE | tee -a "$OUT/times.txt"
