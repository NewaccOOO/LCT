#!/usr/bin/env python3
"""Гейт пакета exact-subproblem: журнал экспериментов сверяется с файлами выходов.

Запуск из корня: python3 scripts/gates/exact_journal.py
Читает docs/research/exact-subproblem/experiments.md. Каждая строка таблицы экспериментов обязана иметь: ID вида E-n,
дату, коммит (существует в git), сцену, параметры, S до, S после, время, файл выхода и вывод. Для каждой строки файл
существует, S варианта 1 в нём совпадает с колонкой «S после» с точностью 0,001, и вывод непустой. Строк не меньше
MIN_ENTRIES, сцен не меньше трёх разных, и есть хотя бы одна строка с выводом «отклонено» или «хуже»:
журнал без отрицательных результатов подозрителен. Печатает EXACT JOURNAL OK.
"""
import json
import re
import subprocess
import sys

PATH = "docs/research/exact-subproblem/experiments.md"
MIN_ENTRIES = 6
COLUMNS = ["id", "дата", "коммит", "сцена", "параметры", "s_до", "s_после", "секунд", "файл", "вывод"]


def main():
    text = open(PATH, encoding="utf-8").read()
    rows = [line for line in text.splitlines() if line.startswith("| E-")]
    problems = []
    scenes = set()
    negative = False
    for line in rows:
        cells = [c.strip() for c in line.strip("|").split("|")]
        if len(cells) != len(COLUMNS):
            problems.append(f"{cells[0]}: колонок {len(cells)}, нужно {len(COLUMNS)}")
            continue
        row = dict(zip(COLUMNS, cells))
        if not re.fullmatch(r"E-\d+", row["id"]):
            problems.append(f"{row['id']}: ID не вида E-n")
        if subprocess.run(["git", "cat-file", "-e", row["коммит"] + "^{commit}"], capture_output=True).returncode != 0:
            problems.append(f"{row['id']}: коммита {row['коммит']} нет в git")
        scenes.add(row["сцена"])
        try:
            data = json.load(open(row["файл"], encoding="utf-8"))
            actual = next(f["properties"]["score"] for f in data["features"]
                          if f["properties"]["object_type"] == "variant_summary" and f["properties"]["rank"] == 1)
            if abs(actual - float(row["s_после"].replace(",", "."))) > 0.001:
                problems.append(f"{row['id']}: в файле S {actual}, в журнале {row['s_после']}")
        except (OSError, StopIteration, ValueError) as e:
            problems.append(f"{row['id']}: файл {row['файл']} не читается: {e}")
        if not row["вывод"]:
            problems.append(f"{row['id']}: пустой вывод")
        if re.search(r"отклон|хуже", row["вывод"], re.IGNORECASE):
            negative = True
    if len(rows) < MIN_ENTRIES:
        problems.append(f"строк {len(rows)}, нужно не меньше {MIN_ENTRIES}")
    if len(scenes) < 3:
        problems.append(f"сцен {len(scenes)}, нужно не меньше трёх")
    if not negative:
        problems.append("нет ни одного отрицательного результата")
    for p in problems:
        print(p)
    if problems:
        return 1
    print(f"EXACT JOURNAL OK entries={len(rows)} scenes={len(scenes)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
