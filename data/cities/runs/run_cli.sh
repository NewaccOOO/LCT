#!/usr/bin/env bash
# Очередь запусков тяжёлых JVM (CLI сервиса, сборка Gradle) для всех агентов: по одному (swap съедал диск).
# Использование: run_cli.sh <команда ...>   например: run_cli.sh java -Xmx6g -jar build/libs/heatnet.jar --cli IN OUT
# Время и пиковая память — в stderr (/usr/bin/time -l). Слот держится до конца команды.
LOCKS=/Users/a1111/HACK/LCT/LCT/.claude/worktrees/geojson-heat-network-moscow-spb-ea030e/data/cities/runs/.locks
mkdir -p "$LOCKS"
while :; do
  for slot in 1; do
    d="$LOCKS/slot$slot"
    if mkdir "$d" 2>/dev/null; then
      echo $$ > "$d/pid"
      trap 'rm -rf "$d"' EXIT
      /usr/bin/time -l "$@"
      exit $?
    fi
    # слот брошенного процесса освобождается
    pid=$(cat "$d/pid" 2>/dev/null)
    [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null && rm -rf "$d"
  done
  sleep 3
done
