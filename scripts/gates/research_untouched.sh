#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
# AC-0.1: сервис, генератор, валидатор и правила совпадают с точкой ветвления от feature/backend-core,
# включая незакоммиченные и неотслеживаемые файлы.
base="$(git merge-base HEAD feature/backend-core)"
paths=(src rules/rules.json tools/synth tools/validator tools/pyproject.toml)
git diff --quiet "$base" -- "${paths[@]}"
changed="$(git status --porcelain --untracked-files=all -- "${paths[@]}")"
test -z "$changed"
echo "SERVICE UNTOUCHED"
