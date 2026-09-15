#!/usr/bin/env bash
set -euo pipefail
# Прогоняет сценарии heatscen через CLI сервиса. Аргументы уходят в pytest, например -k S14.
# Без аргументов пишет data/scenarios/last-run.json и требует не меньше 120 прошедших сценариев.
cd "$(dirname "$0")/../.."
source scripts/gates/env.sh
ensure_jar
mkdir -p data/scenarios
rm -f data/scenarios/results.json

status=0
uv run --project tools pytest -q -p no:cacheprovider tests/scenarios "$@" || status=$?

uv run --project tools python - "$status" "$#" <<'PY'
import json
import shutil
import sys

MIN_FULL_RUN = 120
status, argc = int(sys.argv[1]), int(sys.argv[2])
path = "data/scenarios/results.json"
try:
    data = json.load(open(path, encoding="utf-8"))
except FileNotFoundError:
    sys.exit(f"scenarios.sh: pytest не записал {path}, код pytest {status}")
if argc == 0:
    shutil.copyfile(path, "data/scenarios/last-run.json")
counts = {}
for entry in data["scenarios"].values():
    counts[entry["status"]] = counts.get(entry["status"], 0) + 1
passed = counts.pop("passed", 0)
failed = counts.pop("failed", 0) + counts.pop("error", 0)
skipped = counts.pop("skipped", 0) + counts.pop("xfailed", 0) + counts.pop("xpassed", 0)
problems = []
if status != 0:
    problems.append(f"код pytest {status}")
if failed or skipped or counts:
    problems.append(f"упало {failed}, пропущено или xfail {skipped}, прочие статусы {counts}")
if passed == 0:
    problems.append("не прошёл ни один сценарий")
if argc == 0 and passed < MIN_FULL_RUN:
    problems.append(f"прошло {passed} сценариев, для полного прогона нужно не меньше {MIN_FULL_RUN}")
if problems:
    sys.exit("scenarios.sh: " + "; ".join(problems))
print(f"SCENARIOS OK passed={passed} failed=0 skipped=0")
PY
