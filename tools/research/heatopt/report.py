"""Таблица бенчмарка и проверка отчёта ресёрча.

build: python -m heatopt.report build [--results data/research/results.json] пишет docs/research/routing-benchmark.md.
check: python -m heatopt.report --check [--results ...] печатает BENCH REPORT OK rows=<n>, OBJECTIVE OK priced_rules=<m>
и REPORT OK или строку «<ИМЯ> FAILED: причина». Код 1 — results.json или scenes.json не прочитаны, либо не прошла
проверка REPORT (гейт AC-4.1 ищет подстроку «REPORT OK», которая есть и в «BENCH REPORT OK»).

Формат docs/research/routing-report.md для проверки REPORT. Заголовки Markdown любого уровня, текст заголовка ровно:
«Метод и модель», «Таблица бенчмарка» (в разделе ссылка на routing-benchmark.md), «Правила разумности» (ссылка на
routing-quality.md), «Рекомендация», «Черновик ADR» с вложенными заголовками «Контекст», «Варианты», «Решение»,
«Последствия», а также «Границы применимости» и «Трудозатраты внедрения».

В разделе «Рекомендация» две отдельные строки без разметки:

    Рекомендованный кандидат: ls
    Средний разрыв: 0,42 %; максимальный: 2,10 %; сцен S: 24; сцен M: 21

Имя — ls, dp, bend, master или service. Числа — разрыв max(0, S − S_opt) / S_opt в режиме quality=off на сценах S и M
с доказанным оптимумом, как в GAP у heatopt.bench --check: проценты с двумя знаками, разделитель запятая или точка,
сверка до 0,01 п. п.; число сцен S и M — точно. Если кандидат не решил часть таких сцен, разрыв считается по решённым.

В разделе «Трудозатраты внедрения» по строке на каждого кандидата: имя, двоеточие и оценка с числом, например
`- ls: 6 чел.-дней на перенос локального поиска в Java`.
"""
import argparse
import math
import re
import statistics
import sys
from pathlib import Path
from typing import Any

from heatopt import bench
from heatopt.bench import (
    CANDIDATES,
    CLASSES,
    EXACT_CLASSES,
    REPORT_PATH,
    RESULTS_PATH,
    SCORE_TOL,
    SERVICE,
    failure,
    number,
)

BENCH_MD = Path("docs/research/routing-benchmark.md")
ALGORITHMS = (SERVICE, *CANDIDATES)
MIN_ROWS = 15
REPORT_TOL_PP = 0.01
HEADER = ("Алгоритм", "Класс", "Сцен ok", "Разрыв средний, %", "Разрыв медиана, %", "Разрыв максимум, %", "База разрыва",
          "Доказан оптимум, %", "Повороты", "Изломы", "Камеры", "Врезки", "Время p50, с", "Время p95, с", "S средний",
          "S с разумностью средний")
SECTIONS = ("Метод и модель", "Таблица бенчмарка", "Правила разумности", "Рекомендация", "Черновик ADR",
            "Границы применимости", "Трудозатраты внедрения")
ADR_PARTS = ("Контекст", "Варианты", "Решение", "Последствия")
LINKS = {"Таблица бенчмарка": "routing-benchmark.md", "Правила разумности": "routing-quality.md"}
HEADING = re.compile(r"^(#{1,6})\s+(.+?)\s*$")
GAP_LINE = re.compile(r"^Средний разрыв: (\d+(?:[.,]\d+)?) %; максимальный: (\d+(?:[.,]\d+)?) %; сцен S: (\d+); сцен M: (\d+)\s*$", re.M)
INTRO = """# Бенчмарк алгоритмов трассировки

Файл строит `uv run --project tools/research python -m heatopt.report build` из `data/research/results.json`,
`heatopt.report --check` сверяет его с пересчётом. Руками файл не правится.

Разрыв на сцене — (S − S_ref) / S_ref. На классах S и M S_ref — доказанный оптимум точной модели, а без него — лучшая
нижняя граница точной модели или LP; значение, где такие сцены есть, помечено звёздочкой. На классе L S_ref — LP-граница.
«База разрыва» показывает, сколько сцен сравнено с оптимумом и сколько с границей. «Сцен ok» — запуски со статусом ok;
разрыв, метрики C-9 и средние S считаются по ним. Время p50 и p95 — по всем сценам класса: упавший или прерванный
запуск считается бесконечным (∞). «S с разумностью» — S плюс штрафы правил с ценой из `docs/routing-quality.md`.
"""


def fmt(value: float | None, digits: int) -> str:
    if value is None:
        return "—"
    return "∞" if math.isinf(value) else f"{value:.{digits}f}"


def mean(values: list[float]) -> float | None:
    return sum(values) / len(values) if values else None


def row(results: dict[str, Any], name: str, mode: str, cls: str) -> list[str]:
    scenes = [s for s in results["scenes"] if s["class"] == cls]
    solved = []
    for scene in scenes:
        run_record = bench.get_run(scene, name, mode)
        score = bench.ok_score(run_record)
        if score is not None:
            solved.append((scene, run_record, score))
    gaps, estimated = [], 0
    for scene, _, score in solved:
        ref = bench.reference(scene)
        if ref is not None:
            value, proven = ref
            gaps.append((score - value) / value * 100)
            estimated += not proven
    mark = "*" if estimated else ""
    proven_share = None
    if cls in EXACT_CLASSES and scenes:
        proven_share = sum(bench.proven_optimum(s) is not None for s in scenes) / len(scenes) * 100
    times = []
    for scene in scenes:
        run_record = bench.get_run(scene, name, mode)
        elapsed = number(run_record.get("elapsed_s"))
        times.append(elapsed if run_record.get("status") == "ok" and elapsed is not None else math.inf)

    def average(key: str) -> float | None:
        return mean([v for _, r, _ in solved if (v := number(r.get(key))) is not None])

    return [
        name, cls, f"{len(solved)} из {len(scenes)}",
        fmt(mean(gaps), 2) + mark, fmt(statistics.median(gaps) if gaps else None, 2) + mark, fmt(max(gaps, default=None), 2) + mark,
        f"оптимум {len(gaps) - estimated}, граница {estimated}", fmt(proven_share, 0),
        fmt(average("turns"), 1), fmt(average("kinks"), 1), fmt(average("chambers"), 1), fmt(average("tie_ins"), 1),
        fmt(bench.nearest_rank(times, 0.5) if times else None, 1), fmt(bench.nearest_rank(times, 0.95) if times else None, 1),
        fmt(average("S"), 3), fmt(average("S_quality"), 3),
    ]


def table(results: dict[str, Any], mode: str) -> list[str]:
    classes = [cls for cls in CLASSES if any(s["class"] == cls for s in results["scenes"])]
    lines = ["| " + " | ".join(HEADER) + " |", "|" + "---|" * len(HEADER)]
    for cls in classes:
        for name in ALGORITHMS:
            lines.append("| " + " | ".join(row(results, name, "off" if name == SERVICE else mode, cls)) + " |")
    return lines


def render(results: dict[str, Any]) -> str:
    parts = [
        INTRO, "## Режим quality=off\n", "Кандидаты оптимизируют S раздела 10.\n", *table(results, "off"),
        "\n## Режим quality=on\n", "Кандидаты оптимизируют S со штрафами правил с ценой; строка service повторяет сервис для сравнения.\n",
        *table(results, "on"),
    ]
    return "\n".join(parts) + "\n"


def table_rows(results: dict[str, Any]) -> int:
    return len(table(results, "off")) - 2


def check_bench_report(results: dict[str, Any]) -> str:
    if not BENCH_MD.exists():
        return failure("BENCH REPORT", [f"нет файла {BENCH_MD}, его строит heatopt.report build"])
    expected, actual = render(results).splitlines(), BENCH_MD.read_text(encoding="utf-8").splitlines()
    if actual != expected:
        line = next((i for i, (a, b) in enumerate(zip(actual, expected)) if a != b), min(len(actual), len(expected)))
        return failure("BENCH REPORT", [f"{BENCH_MD} не совпадает с пересчётом из results.json, первое расхождение в строке {line + 1}"])
    rows = table_rows(results)
    if rows < MIN_ROWS:
        return failure("BENCH REPORT", [f"строк таблицы {rows}, нужно не меньше {MIN_ROWS}"])
    return f"BENCH REPORT OK rows={rows}"


def check_objective(results: dict[str, Any]) -> str:
    try:
        priced = bench.load_quality()
    except Exception as error:
        return failure("OBJECTIVE", [f"правила из {bench.QUALITY_PATH} не загружены: {error}"])
    problems = [] if priced else [f"в {bench.QUALITY_PATH} нет правил с ценой"]
    on_runs = differs = 0
    for scene in results["scenes"]:
        for name, mode, run_record in bench.runs(scene):
            score = bench.ok_score(run_record)
            if score is None:
                continue
            quality = number(run_record.get("S_quality"))
            if quality is None:
                problems.append(f"{scene['id']}: у {name}-{mode} нет S_quality")
                continue
            if quality < score - SCORE_TOL:
                problems.append(f"{scene['id']}: у {name}-{mode} S_quality {quality:.4f} меньше S {score:.4f}")
            if mode == "on":
                on_runs += 1
                differs += quality - score > 1e-6
    if not on_runs:
        problems.append("нет запусков режима on со status ok")
    elif not differs:
        problems.append("S_quality равен S на всех запусках режима on: штрафы в столбец не вошли")
    if problems:
        return failure("OBJECTIVE", problems)
    return f"OBJECTIVE OK priced_rules={len(priced)}"


def sections(text: str) -> dict[str, tuple[int, str]]:
    """Заголовок → (уровень, текст раздела до следующего заголовка того же или более высокого уровня)."""
    lines = text.splitlines()
    heads = [(i, len(m.group(1)), m.group(2)) for i, line in enumerate(lines) if (m := HEADING.match(line))]
    found = {}
    for n, (start, level, title) in enumerate(heads):
        end = next((i for i, lvl, _ in heads[n + 1:] if lvl <= level), len(lines))
        found.setdefault(title, (level, "\n".join(lines[start + 1:end])))
    return found


def check_report(results: dict[str, Any]) -> str:
    if not REPORT_PATH.exists():
        return failure("REPORT", [f"нет файла {REPORT_PATH}"])
    text = REPORT_PATH.read_text(encoding="utf-8")
    found = sections(text)
    problems = [f"нет раздела «{title}»" for title in SECTIONS if title not in found]
    if len(bench.RECOMMENDED.findall(text)) > 1:
        # bench --check берёт первую такую строку во всём файле, поэтому она должна быть одна
        problems.append("строка «Рекомендованный кандидат» встречается в отчёте больше одного раза")
    if "Черновик ADR" in found:
        adr = sections(found["Черновик ADR"][1])
        problems += [f"в «Черновик ADR» нет подраздела «{part}»" for part in ADR_PARTS if part not in adr]
    problems += [f"в разделе «{title}» нет ссылки на {link}" for title, link in LINKS.items()
                 if title in found and link not in found[title][1]]
    if "Трудозатраты внедрения" in found:
        effort = found["Трудозатраты внедрения"][1]
        problems += [f"в «Трудозатраты внедрения» нет строки «- {name}: <оценка с числом>»" for name in CANDIDATES
                     if not re.search(rf"^\s*[-*]\s+`?{name}`?:.*\d", effort, re.M)]
    if "Рекомендация" in found:
        problems += recommendation_problems(results, found["Рекомендация"][1])
    if problems:
        return failure("REPORT", problems)
    return "REPORT OK"


def recommendation_problems(results: dict[str, Any], text: str) -> list[str]:
    name_match, gap_match = bench.RECOMMENDED.search(text), GAP_LINE.search(text)
    if not name_match or not gap_match:
        return ["в «Рекомендация» нет строк «Рекомендованный кандидат: <имя>» и «Средний разрыв: <x> %; максимальный: <y> %; сцен S: <n>; сцен M: <m>»"]
    name = name_match.group(1)
    if name not in ALGORITHMS:
        return [f"рекомендован неизвестный алгоритм «{name}»"]
    gap = bench.gap_stats(results, name)
    if not gap.values:
        return [f"у {name} нет сцен с доказанным оптимумом для сверки чисел"]
    written_mean, written_max = (float(gap_match.group(i).replace(",", ".")) for i in (1, 2))
    written_s, written_m = int(gap_match.group(3)), int(gap_match.group(4))
    problems = []
    if abs(written_mean - gap.mean) > REPORT_TOL_PP + 1e-9:
        problems.append(f"средний разрыв в отчёте {written_mean} %, по results.json {gap.mean:.3f} %")
    if abs(written_max - gap.max) > REPORT_TOL_PP + 1e-9:
        problems.append(f"максимальный разрыв в отчёте {written_max} %, по results.json {gap.max:.3f} %")
    if (written_s, written_m) != (gap.scenes["S"], gap.scenes["M"]):
        problems.append(f"сцен S и M в отчёте {written_s} и {written_m}, по results.json {gap.scenes['S']} и {gap.scenes['M']}")
    return problems


def main() -> None:
    parser = argparse.ArgumentParser(prog="heatopt.report", description="Таблица бенчмарка и проверка отчёта")
    parser.add_argument("command", nargs="?", choices=["build"], help="build — записать routing-benchmark.md")
    parser.add_argument("--check", action="store_true", help="проверить таблицу, целевую функцию и отчёт по AC-1.3, AC-2.3, AC-4.1")
    parser.add_argument("--results", type=Path, default=RESULTS_PATH)
    args = parser.parse_args()
    if args.check == (args.command == "build"):
        parser.error("нужна ровно одна команда: build или --check")
    try:
        results, _, _ = bench.load_inputs(args.results)
    except (OSError, ValueError) as error:
        print(failure("BENCH REPORT", [f"результаты бенчмарка не прочитаны: {error}"]))
        sys.exit(1)
    if args.command == "build":
        BENCH_MD.parent.mkdir(parents=True, exist_ok=True)
        BENCH_MD.write_text(render(results), encoding="utf-8")
        print(f"Записан {BENCH_MD}: строк в таблице режима off {table_rows(results)}")
        return
    print(check_bench_report(results), flush=True)
    print(check_objective(results), flush=True)
    verdict = check_report(results)
    print(verdict)
    sys.exit(0 if verdict == "REPORT OK" else 1)


if __name__ == "__main__":
    main()
