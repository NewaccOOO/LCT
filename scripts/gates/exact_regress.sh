#!/usr/bin/env bash
# Гейт AC-0.2 пакета exact-subproblem: прежние проверки зелёные. Печатает REGRESS OK только при трёх успехах.
set -uo pipefail
cd "$(dirname "$0")/../.."
make test-unit > /dev/null 2>&1 || { echo "test-unit упал"; exit 1; }
make test-python > /dev/null 2>&1 || { echo "test-python упал"; exit 1; }
make test-scenarios > /dev/null 2>&1 || { echo "test-scenarios упал"; exit 1; }
echo "REGRESS OK"
