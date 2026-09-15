"""Отчёт сценарного прогона: покрытие условий ТЗ, семейства, случайный прогон, дефекты; --check сверяет его с файлом и git."""
import argparse
import json
import math
import re
import subprocess
import sys
from pathlib import Path
from typing import (
    Any,
    NamedTuple,
)

from heatscen.registry import load_all
from heatscen.runner import CACHE
from heatscen.scene import ROOT

INVENTORY = ROOT / "docs" / "specs" / "scenario-tests" / "tz-inventory.md"
DEFECTS = ROOT / "docs" / "testing" / "defects.md"
REPORT_REL = "docs/testing/scenario-report.md"
REPORT = ROOT / REPORT_REL
LAST_RUN = CACHE / "last-run.json"
SWEEP = CACHE / "sweep.json"
TZ_ID = re.compile(r"TZ-\d+")
IN_SCOPE = re.compile(r"да(?!\w)")
COMMIT = re.compile(r"[0-9a-f]{7,40}")
DEFECTS_HEADER = "| Сценарий | Симптом | Причина | Коммит |"
DEFECT_CELLS = 4
MIN_COVERAGE = 2
TIME_LIMIT_S = 1500
PASSED = "passed"
NO_DATA = "нет данных"


class Item(NamedTuple):
    tz: str
    condition: str


class Entry(NamedTuple):
    id: str
    tz: list[str]
    module: str
    scene_hash: str
    expectations: int


def cells(line: str) -> list[str]:
    return [cell.strip() for cell in line.strip().strip("|").split("|")]


def inventory() -> list[Item]:
    items = []
    for line in INVENTORY.read_text(encoding="utf-8").splitlines():
        if line.startswith("|"):
            row = cells(line)
            if TZ_ID.fullmatch(row[0]) and IN_SCOPE.match(row[-1]):
                items.append(Item(row[0], row[1]))
    return items


def registry() -> list[Entry]:
    entries = []
    for sc in load_all():
        scene, expect = sc.build()
        module = sc.build.__module__.rsplit(".", 1)[-1]
        entries.append(Entry(sc.id, sc.tz, module, scene.scene_hash(), len(expect.declared())))
    return entries


def defect_rows() -> list[list[str]]:
    text = DEFECTS.read_text(encoding="utf-8")
    if DEFECTS_HEADER not in text:
        sys.exit(f"report: в {DEFECTS.relative_to(ROOT)} нет таблицы с заголовком {DEFECTS_HEADER}")
    rows = []
    # Первые две строки — заголовок и разделитель таблицы.
    for line in text[text.index(DEFECTS_HEADER):].splitlines()[2:]:
        if not line.startswith("|"):
            break
        rows.append(cells(line))
    return rows


def load_json(path: Path) -> dict[str, Any] | None:
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else None


def status(entry: Entry, last_run: dict[str, Any] | None) -> str | None:
    if last_run is None:
        return None
    return last_run["scenarios"].get(entry.id, {}).get("status")


def counted(refs: list[Entry]) -> int:
    return len({entry.scene_hash for entry in refs if entry.expectations})


def verdict(refs: list[Entry], last_run: dict[str, Any] | None) -> str:
    number = counted(refs)
    if number < MIN_COVERAGE:
        return f"не покрыто: засчитано сценариев {number} из {MIN_COVERAGE}"
    if last_run is None:
        return NO_DATA
    failed = [f"{e.id} ({status(e, last_run)})" for e in refs if status(e, last_run) not in (None, PASSED)]
    if failed:
        return f"не прошли: {', '.join(failed)}"
    unknown = [e.id for e in refs if status(e, last_run) is None]
    if unknown:
        return f"нет данных по {', '.join(unknown)}"
    return "пройдено"


def build(items: list[Item], entries: list[Entry], last_run: dict[str, Any] | None, sweep: dict[str, Any] | None, defects: list[list[str]]) -> str:
    refs = {item.tz: [e for e in entries if item.tz in e.tz] for item in items}
    uncovered = sum(counted(refs[item.tz]) < MIN_COVERAGE for item in items)
    statuses = [status(e, last_run) for e in entries]
    passed, unknown = statuses.count(PASSED), statuses.count(None)
    lines = [
        "# Отчёт сценарного прогона",
        "",
        "Отчёт собирает команда `uv run --project tools python -m heatscen.report` из `docs/specs/scenario-tests/tz-inventory.md`, "
        "реестра сценариев `heatscen`, `data/scenarios/last-run.json`, `data/scenarios/sweep.json` и `docs/testing/defects.md`. "
        "Руками файл не правят: `heatscen.report --check` пересобирает его и сравнивает с файлом в репозитории.",
        "",
        "## Итог",
        "",
        f"- Условий ТЗ в скоупе: {len(items)}. Покрыты двумя и более сценариями: {len(items) - uncovered}, не покрыты: {uncovered}.",
        f"- Сценариев в реестре: {len(entries)}, из них с объявленными ожиданиями: {sum(bool(e.expectations) for e in entries)}.",
    ]
    if last_run is None:
        lines.append(f"- Последний полный прогон сценариев: {NO_DATA}.")
    else:
        lines.append(f"- Последний полный прогон сценариев: прошло {passed}, не прошло {len(entries) - passed - unknown}, нет данных {unknown}.")
    if sweep is None:
        lines.append(f"- Случайный прогон: {NO_DATA}.")
    else:
        lines.append(
            f"- Случайный прогон: сидов {sweep['seeds']}, упало {len(sweep['failed'])}, "
            f"специальных участков {sweep['special']}, реконструкций {sweep['recon']}."
        )
    lines += [
        f"- Дефектов в журнале: {len(defects)}.",
        "",
        "## Покрытие условий ТЗ",
        "",
        f"Условие покрыто, если на него ссылаются не меньше {MIN_COVERAGE} сценариев с объявленными ожиданиями и разными сценами. "
        "Вердикт «пройдено» значит, что в последнем полном прогоне прошли все сценарии условия.",
        "",
        "| TZ | Условие | Сценарии | Вердикт |",
        "|---|---|---|---|",
    ]
    for item in items:
        ids = ", ".join(e.id for e in refs[item.tz]) or "—"
        lines.append(f"| {item.tz} | {item.condition} | {ids} | {verdict(refs[item.tz], last_run)} |")

    lines += [
        "",
        "## Семейства сценариев",
        "",
        "| Семейство | Модуль | Сценариев | Прошло | Не прошло | Нет данных |",
        "|---|---|---|---|---|---|",
    ]
    families: dict[tuple[str, str], list[Entry]] = {}
    for entry in entries:
        families.setdefault((entry.id.split("-")[0], entry.module), []).append(entry)
    for (family, module), members in families.items():
        states = [status(e, last_run) for e in members]
        ok, none = states.count(PASSED), states.count(None)
        lines.append(f"| {family} | `{module}` | {len(members)} | {ok} | {len(members) - ok - none} | {none} |")

    lines += ["", "## Случайный прогон", ""]
    if sweep is None:
        lines.append(f"{NO_DATA.capitalize()}: нет файла `data/scenarios/sweep.json`.")
    else:
        failed = ", ".join(str(seed) for seed in sweep["failed"]) or "нет"
        lines += [
            f"Сидов: {sweep['seeds']}. Упавшие сиды: {failed}. Специальных участков: {sweep['special']}, реконструкций: {sweep['recon']}.",
            "",
            "| Подмножество | Типы ограничений | Сидов | Упало | Специальных участков | Реконструкций |",
            "|---|---|---|---|---|---|",
        ]
        for subset, data in sweep["subsets"].items():
            types = ", ".join(f"`{t}`" for t in data["types"])
            lines.append(f"| {subset} | {types} | {data['seeds']} | {data['failed']} | {data['special']} | {data['recon']} |")

    lines += ["", "## Дефекты", ""]
    if not defects:
        lines.append("В `docs/testing/defects.md` строк нет.")
    else:
        lines += [DEFECTS_HEADER, "|---|---|---|---|"]
        lines += [f"| {' | '.join(row)} |" for row in defects]
    return "\n".join(lines) + "\n"


def check_coverage(items: list[Item], entries: list[Entry]) -> bool:
    gaps = []
    for item in items:
        number = counted([e for e in entries if item.tz in e.tz])
        if number < MIN_COVERAGE:
            gaps.append(f"{item.tz} ({number})")
    if gaps:
        print(f"COVERAGE FAILED items={len(items)} uncovered={len(gaps)}: засчитано меньше {MIN_COVERAGE} сценариев у {', '.join(gaps)}")
        return False
    print(f"COVERAGE OK items={len(items)} uncovered=0")
    return True


def check_defects(rows: list[list[str]], entries: list[Entry]) -> bool:
    ids = {e.id for e in entries}
    problems = []
    for number, row in enumerate(rows, 1):
        if len(row) != DEFECT_CELLS or not all(row):
            problems.append(f"строка {number}: заполнены не все {DEFECT_CELLS} ячейки")
            continue
        scenario_id, commit = row[0].strip("`"), row[3].strip("`")
        if scenario_id not in ids:
            problems.append(f"строка {number}: сценария {scenario_id} нет в реестре")
        known = COMMIT.fullmatch(commit) and subprocess.run(
            ["git", "cat-file", "-e", f"{commit}^{{commit}}"], cwd=ROOT, capture_output=True
        ).returncode == 0
        if not known:
            problems.append(f"строка {number}: коммита {commit} нет в git")
    if problems:
        print(f"DEFECTS FAILED rows={len(rows)}: {'; '.join(problems)}")
        return False
    print(f"DEFECTS OK rows={len(rows)}")
    return True


def check_time(last_run: dict[str, Any] | None, sweep: dict[str, Any] | None) -> bool:
    missing = [path.relative_to(ROOT).as_posix() for path, data in ((LAST_RUN, last_run), (SWEEP, sweep)) if data is None]
    if missing:
        print(f"TIME FAILED: нет файлов {', '.join(missing)}")
        return False
    elapsed = math.ceil(last_run["elapsed"] + sweep["elapsed"])
    if elapsed > TIME_LIMIT_S:
        print(f"TIME FAILED elapsed={elapsed}s: больше {TIME_LIMIT_S} с")
        return False
    print(f"TIME OK elapsed={elapsed}s")
    return True


def check_report(text: str) -> bool:
    wanted = text.encode("utf-8")
    problems = []
    if not REPORT.exists() or REPORT.read_bytes() != wanted:
        problems.append(f"{REPORT_REL} на диске не совпадает с пересборкой")
    committed = subprocess.run(["git", "show", f"HEAD:{REPORT_REL}"], cwd=ROOT, capture_output=True)
    if committed.returncode != 0 or committed.stdout != wanted:
        problems.append(f"{REPORT_REL} в HEAD не совпадает с пересборкой")
    if problems:
        print(f"REPORT FAILED: {'; '.join(problems)}")
        return False
    print("REPORT OK")
    return True


def main() -> None:
    parser = argparse.ArgumentParser(prog="heatscen.report", description="Отчёт сценарного прогона")
    parser.add_argument("--check", action="store_true", help="не писать файл, а сверить пересборку с файлом и HEAD")
    args = parser.parse_args()

    items, entries, rows = inventory(), registry(), defect_rows()
    last_run, sweep = load_json(LAST_RUN), load_json(SWEEP)
    text = build(items, entries, last_run, sweep, rows)
    if not args.check:
        REPORT.parent.mkdir(parents=True, exist_ok=True)
        REPORT.write_bytes(text.encode("utf-8"))
        print(f"REPORT WRITTEN items={len(items)}")
        return

    # Каждая проверка печатает свой маркер сама, список собирается целиком, чтобы вывести все маркеры до выхода.
    results = [check_coverage(items, entries), check_defects(rows, entries), check_time(last_run, sweep), check_report(text)]
    if not all(results):
        sys.exit(1)


if __name__ == "__main__":
    main()
