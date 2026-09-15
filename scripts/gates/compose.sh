#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/gates/env.sh
port="${APP_PORT:-8080}"
log="$(mktemp)"
trap 'rm -f "$log"; $COMPOSE down -v >/dev/null 2>&1' EXIT

if ! $COMPOSE up -d --build >"$log" 2>&1; then
  cat "$log" >&2
  echo "compose: не удалось поднять сервисы" >&2
  exit 1
fi

health=""
for _ in $(seq 120); do
  if health="$(curl -s "localhost:$port/actuator/health")" && [[ "$health" == *'"status":"UP"'* ]]; then
    break
  fi
  sleep 2
done
if [[ "$health" != *'"status":"UP"'* ]]; then
  $COMPOSE logs app >&2
  echo "compose: /actuator/health не ответил UP за 4 минуты, последний ответ: $health" >&2
  exit 1
fi

curl -sSf -o /dev/null "localhost:$port/swagger-ui/index.html"
curl -sSf "localhost:$port/v3/api-docs" | python3 -c '
import json
import sys

methods = {"get", "put", "post", "delete", "options", "head", "patch", "trace"}
paths = json.load(sys.stdin).get("paths", {})
found = sorted(f"{method.upper()} {path}" for path, item in paths.items() for method in item if method in methods)
expected = sorted([
    "POST /api/v1/jobs",
    "GET /api/v1/jobs",
    "GET /api/v1/jobs/{id}",
    "GET /api/v1/jobs/{id}/result",
    "GET /api/v1/jobs/{id}/input",
])
if found != expected:
    sys.exit(f"compose: в /v3/api-docs операции {found}, ожидались {expected}")
'

echo "COMPOSE OK"
