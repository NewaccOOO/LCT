#!/usr/bin/env bash
bench=$(cd "$(dirname "$0")" && pwd)
lock=$bench/heavy.lock
queue=$bench/heavy.queue
mkdir -p "$queue"
ticket=$(date +%s)-$(printf %07d $$)
touch "$queue/$ticket"
owned=0
trap 'rm -f "$queue/$ticket"; [ "$owned" = 1 ] && rm -rf "$lock"' EXIT
while :; do
  first=
  for name in $(ls "$queue" | sort); do
    if kill -0 $((10#${name#*-})) 2>/dev/null; then first=$name; break; fi
    rm -f "$queue/$name"
  done
  owner=$(cat "$lock/pid" 2>/dev/null)
  if [ -d "$lock" ] && [ -n "$owner" ] && ! kill -0 "$owner" 2>/dev/null; then rm -rf "$lock"; fi
  if [ "$first" = "$ticket" ] && mkdir "$lock" 2>/dev/null; then owned=1; break; fi
  sleep 2
done
echo $$ > "$lock/pid"
"$@"
