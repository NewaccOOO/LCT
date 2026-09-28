#!/usr/bin/env bash
# check18 по выходам прогонов P2: p2_check.sh "вход-относительно-runs выход-имя" ...
MAIN=/Users/a1111/HACK/LCT/LCT/.claude/worktrees/geojson-heat-network-moscow-spb-ea030e
R=$MAIN/data/cities/problems/p2-dense/runs
cd /Users/a1111/HACK/LCT/LCT/.claude/worktrees/agent-a6df04e9c446b78d5 || exit 1
for x in "$@"; do
  set -- $x
  uv run --project $MAIN/tools python tools/validator/check18.py "$MAIN/data/cities/runs/$1" "$R/$2.out.geojson" > "$R/$2.check18.txt" 2>&1
  echo "== $2: $(grep 'CHECK18 VIOLATIONS' "$R/$2.check18.txt")"
  grep "^  [A-Z][0-9]" "$R/$2.check18.txt" | cut -c1-220
done
