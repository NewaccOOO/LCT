#!/usr/bin/env python3
"""Гейт NFR-2 пакета exact-subproblem: одна итерация цикла на датасете не дольше 15 секунд.

Запуск из корня: python3 scripts/gates/exact_iteration.py
Считает датасет с циклом по умолчанию, из лога берёт строки "exact:" с полем ms=<число> и проверяет максимум.
Печатает EXACT ITERATION OK.
"""
import re
import subprocess
import sys

LIMIT_MS = 15000
result = subprocess.run(["java", "-jar", "target/heatnet.jar", "--cli", "data/real/dataset.geojson",
                         "data/out/exact/gate-iteration.geojson"], capture_output=True, text=True)
times = [int(m) for m in re.findall(r"exact:.*?\bms=(\d+)", result.stdout + result.stderr)]
if not times:
    print("в логе нет строк exact: с полем ms=")
    sys.exit(1)
worst = max(times)
print(f"итераций {len(times)}, самая долгая {worst} мс")
if worst > LIMIT_MS:
    sys.exit(1)
print("EXACT ITERATION OK")
