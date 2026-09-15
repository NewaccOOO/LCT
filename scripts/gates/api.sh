#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/gates/env.sh
# Поднимает jar с -Xmx1g против PostgreSQL из compose и проверяет REST один критерий за запуск.

check="${1:-}"
case "$check" in
  AC-1.1 | AC-1.2 | AC-1.3 | AC-1.4 | NFR-2 | NFR-3) ;;
  *)
    echo "использование: scripts/gates/api.sh AC-1.1|AC-1.2|AC-1.3|AC-1.4|NFR-2|NFR-3" >&2
    exit 2
    ;;
esac

fail() {
  echo "api $check: $*" >&2
  exit 1
}

tmp="$(mktemp -d)"
app_pid=""
cleanup() {
  local status=$?
  if [ -n "$app_pid" ]; then
    if kill -0 "$app_pid" 2>/dev/null; then
      kill "$app_pid"
    fi
    while kill -0 "$app_pid" 2>/dev/null; do
      sleep 0.2
    done
  fi
  if [ "$status" -ne 0 ] && [ -f "$tmp/app.log" ]; then
    echo "api $check: последние строки журнала приложения:" >&2
    tail -n 40 "$tmp/app.log" >&2
  fi
  rm -rf "$tmp"
}
trap cleanup EXIT

ensure_jar

$COMPOSE up -d postgres >&2
ready=""
for _ in $(seq 60); do
  if $COMPOSE exec -T postgres pg_isready -h 127.0.0.1 -U heatnet -d heatnet >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 1
done
[ -n "$ready" ] || fail "PostgreSQL из compose не ответил pg_isready за 60 с"

port="$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')"
base="http://127.0.0.1:$port"
SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:${POSTGRES_HOST_PORT:-55432}/heatnet" \
  java -Xmx1g -jar target/heatnet.jar --server.port="$port" --app.data-dir="$tmp/data" >"$tmp/app.log" 2>&1 &
app_pid=$!

health=""
for _ in $(seq 120); do
  kill -0 "$app_pid" 2>/dev/null || fail "приложение завершилось при старте"
  if health="$(curl -s "$base/actuator/health")" && [[ "$health" == *'"status":"UP"'* ]]; then
    break
  fi
  sleep 1
done
[[ "$health" == *'"status":"UP"'* ]] || fail "/actuator/health не ответил UP за 120 с, последний ответ: $health"

# post FILE NAME: отправляет файл сырым телом, ответ пишет в $tmp/NAME.json, печатает HTTP-код.
post() {
  curl -sS -o "$tmp/$2.json" -w '%{http_code}' -H 'Content-Type: application/geo+json' \
    --data-binary @"$1" "$base/api/v1/jobs"
}

# created NAME: проверяет ответ 202 и печатает id задачи.
created() {
  python3 - "$tmp/$1.json" <<'EOF'
import json
import sys
import uuid

body = json.load(open(sys.argv[1]))
if body.get("status") != "QUEUED":
    sys.exit(f"ответ на POST без status QUEUED: {body}")
print(uuid.UUID(body["id"]))
EOF
}

# await_status ID EXPECTED DEADLINE_EPOCH: опрашивает задачу до статуса EXPECTED, ответ пишет в $tmp/job-ID.json.
await_status() {
  python3 - "$base" "$1" "$2" "$3" "$tmp/job-$1.json" <<'EOF'
import json
import sys
import time
import urllib.request

base, job_id, expected, deadline, out = sys.argv[1], sys.argv[2], sys.argv[3], float(sys.argv[4]), sys.argv[5]
allowed = {"QUEUED", "RUNNING", "DONE", "FAILED"}
while True:
    with urllib.request.urlopen(f"{base}/api/v1/jobs/{job_id}") as response:
        body = response.read()
    status = json.loads(body)["status"]
    if status not in allowed:
        sys.exit(f"задача {job_id}: недопустимый статус {status}")
    if status == expected:
        open(out, "wb").write(body)
        break
    if status in ("DONE", "FAILED"):
        sys.exit(f"задача {job_id} завершилась статусом {status}, ожидался {expected}: {body.decode()}")
    if time.time() > deadline:
        sys.exit(f"задача {job_id} не дошла до {expected} к сроку, статус {status}")
    time.sleep(1)
EOF
}

# error_body NAME: проверяет формат ошибки {"message", "errors": []}.
error_body() {
  python3 - "$tmp/$1.json" <<'EOF'
import json
import sys

body = json.load(open(sys.argv[1]))
if not isinstance(body.get("message"), str) or not body["message"] or body.get("errors") != []:
    sys.exit(f"ответ не в формате ошибки: {body}")
EOF
}

# status_of URL NAME: GET, тело в $tmp/NAME.json, печатает HTTP-код.
status_of() {
  curl -sS -o "$tmp/$2.json" -w '%{http_code}' "$1"
}

synth() {
  local out="data/synth/$1.geojson"
  shift
  if [ ! -f "$out" ]; then
    mkdir -p data/synth
    uv run --project tools python -m heatsynth "$@" --out "$out" >&2
  fi
}

sample="data/samples/small-1.geojson"

case "$check" in
  AC-1.1)
    code="$(post "$sample" small)"
    [ "$code" = 202 ] || fail "POST small ответил $code вместо 202"
    created small >/dev/null

    code="$(curl -sS -o "$tmp/empty.json" -w '%{http_code}' -H 'Content-Type: application/geo+json' \
      --data-binary '' "$base/api/v1/jobs")"
    [ "$code" = 400 ] || fail "POST с пустым телом ответил $code вместо 400"
    error_body empty

    printf '[1]' >"$tmp/array.geojson"
    code="$(post "$tmp/array.geojson" array)"
    [ "$code" = 400 ] || fail "POST с телом [1] ответил $code вместо 400"
    error_body array
    ;;

  AC-1.2)
    code="$(post "$sample" small)"
    [ "$code" = 202 ] || fail "POST small ответил $code вместо 202"
    id="$(created small)"
    await_status "$id" DONE "$(($(date +%s) + 180))"
    python3 - "$tmp/job-$id.json" <<'EOF'
import json
import sys

job = json.load(open(sys.argv[1]))
for field in ("createdAt", "startedAt", "finishedAt"):
    if not isinstance(job.get(field), str):
        sys.exit(f"у задачи DONE нет {field}: {job}")
summary = job.get("summary")
if not isinstance(summary, list) or not summary:
    sys.exit(f"summary у задачи DONE не непустой массив: {job}")
for item in summary:
    if not isinstance(item.get("rank"), int) or not isinstance(item.get("score"), (int, float)):
        sys.exit(f"в сводке варианта нет rank или score: {item}")
EOF
    code="$(status_of "$base/api/v1/jobs/$(python3 -c 'import uuid; print(uuid.uuid4())')" unknown)"
    [ "$code" = 404 ] || fail "GET неизвестной задачи ответил $code вместо 404"
    error_body unknown
    ;;

  AC-1.3)
    ids=()
    for seed in 1 2 3; do
      synth "medium-$seed" --preset medium --seed "$seed"
      code="$(post "data/synth/medium-$seed.geojson" "medium-$seed")"
      [ "$code" = 202 ] || fail "POST medium-$seed ответил $code вместо 202"
      id="$(created "medium-$seed")"
      ids+=("$id")
    done
    code="$(status_of "$base/api/v1/jobs/${ids[2]}/result" third-result)"
    [ "$code" = 409 ] || fail "result третьей задачи до DONE ответил $code вместо 409"
    error_body third-result

    await_status "${ids[0]}" DONE "$(($(date +%s) + 300))"
    code="$(curl -sS -D "$tmp/result.headers" -o "$tmp/result.geojson" -w '%{http_code}' \
      "$base/api/v1/jobs/${ids[0]}/result")"
    [ "$code" = 200 ] || fail "result задачи DONE ответил $code вместо 200"
    grep -qi '^content-type: application/geo+json' "$tmp/result.headers" \
      || fail "у result не тот Content-Type: $(grep -i '^content-type' "$tmp/result.headers")"
    uv run --project tools python -m heatcheck data/synth/medium-1.geojson "$tmp/result.geojson" --only schema >&2

    code="$(curl -sS -o "$tmp/input.geojson" -w '%{http_code}' "$base/api/v1/jobs/${ids[0]}/input")"
    [ "$code" = 200 ] || fail "input ответил $code вместо 200"
    cmp data/synth/medium-1.geojson "$tmp/input.geojson" >&2
    ;;

  AC-1.4)
    python3 - "$sample" "$tmp/broken.geojson" <<'EOF'
import copy
import json
import sys

data = json.load(open(sys.argv[1]))
features = data["features"]

def first(object_type):
    return next(f for f in features if f["properties"]["object_type"] == object_type)

first("heat_network")["properties"]["flow_tph"] = -5
first("heat_chamber")["properties"]["diameter"] = 12.5
first("oks_connection_point")["properties"]["oks_id"] = "oks-missing"
features.append(copy.deepcopy(first("restriction")))
json.dump(data, open(sys.argv[2], "w"))
EOF
    code="$(post "$tmp/broken.geojson" broken)"
    [ "$code" = 202 ] || fail "POST битого файла ответил $code вместо 202"
    id="$(created broken)"
    await_status "$id" FAILED "$(($(date +%s) + 120))"
    python3 - "$tmp/job-$id.json" <<'EOF'
import json
import sys

job = json.load(open(sys.argv[1]))
error = job.get("error") or {}
errors = error.get("errors")
if not isinstance(error.get("message"), str) or not isinstance(errors, list) or len(errors) < 4:
    sys.exit(f"в error.errors меньше четырёх записей: {error}")
for item in errors:
    if not all(isinstance(item.get(key), str) and item[key] for key in ("featureId", "field", "problem")):
        sys.exit(f"у записи нет featureId, field или problem: {item}")
missing = {"flow_tph", "diameter", "id", "oks_id"} - {item["field"] for item in errors}
if missing:
    sys.exit(f"нет диагностик по полям {sorted(missing)}: {errors}")
if job.get("summary") is not None:
    sys.exit(f"у задачи FAILED есть summary: {job}")
EOF
    code="$(status_of "$base/api/v1/jobs/$id/result" broken-result)"
    [ "$code" = 409 ] || fail "result задачи FAILED ответил $code вместо 409"
    error_body broken-result
    ;;

  NFR-2)
    synth small-pad300 --preset small --seed 1 --pad-mb 300
    size="$(($(wc -c <data/synth/small-pad300.geojson)))"
    [ "$size" -ge $((300 * 1024 * 1024)) ] || fail "data/synth/small-pad300.geojson меньше 300 МБ: $size байт"
    code="$(post data/synth/small-pad300.geojson pad300)"
    [ "$code" = 202 ] || fail "POST файла 300 МБ ответил $code вместо 202"
    id="$(created pad300)"
    await_status "$id" DONE "$(($(date +%s) + 900))"
    ;;

  NFR-3)
    deadline="$(($(date +%s) + 180))"
    pids=()
    for i in 1 2 3 4 5; do
      post "$sample" "nfr3-$i" >"$tmp/nfr3-$i.code" &
      pids+=("$!")
    done
    for pid in "${pids[@]}"; do
      wait "$pid"
    done
    ids=()
    for i in 1 2 3 4 5; do
      code="$(cat "$tmp/nfr3-$i.code")"
      [ "$code" = 202 ] || fail "одновременный POST $i ответил $code вместо 202"
      id="$(created "nfr3-$i")"
      ids+=("$id")
    done
    for id in "${ids[@]}"; do
      await_status "$id" DONE "$deadline"
    done
    ;;
esac

echo "API $check OK"
