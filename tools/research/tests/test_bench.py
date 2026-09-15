"""bench и report на синтетическом results.json с двумя сценами: маркеры есть на хороших данных и пропадают на испорченных."""
import copy
import hashlib
import json
import math
import re
import sys
import time
import types
from pathlib import Path

import pytest

from heatopt import (
    bench,
    report,
)
from heatopt.model import QualityRule

RESULTS = Path("data/research/results.json")
PRICED = 6
GOOD_REPORT = """# Отчёт

## Метод и модель
Текст.

## Таблица бенчмарка
См. [таблицу](routing-benchmark.md).

## Правила разумности
См. [правила](../routing-quality.md).

## Рекомендация
Рекомендованный кандидат: bend
Средний разрыв: {mean} %; максимальный: 0,50 %; сцен S: 1; сцен M: 1

## Черновик ADR
### Контекст
### Варианты
### Решение
### Последствия

## Границы применимости
Текст.

## Трудозатраты внедрения
- ls: 5 чел.-дней
- dp: 3 чел.-дня
- bend: 4 чел.-дня
- master: 10 чел.-дней
"""


def fake_generate(cls, seed, out_dir):
    path = Path(out_dir) / f"{cls}-{seed}.geojson"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(f"сцена {cls}-{seed}", encoding="utf-8")
    return path


def run_record(score, quality, elapsed, turns, kinks, chambers, seed=None, budget=None):
    return {"status": "ok", "S": score, "S_quality": quality, "cost_rub": score * 1e6, "length_m": 1000.0,
            "elapsed_s": elapsed, "turns": turns, "kinks": kinks, "chambers": chambers, "tie_ins": 1,
            "unconnected": [], "violations": {"model": 0}, "seed": seed, "budget": budget, "output": "out.geojson"}


def bound_record(status, objective, bound, elapsed, log):
    Path(log).parent.mkdir(parents=True, exist_ok=True)
    Path(log).write_text(f"лог {log}", encoding="utf-8")
    return {"status": status, "objective": objective, "bound": bound, "elapsed_s": elapsed, "log": log,
            "log_sha256": hashlib.sha256(Path(log).read_bytes()).hexdigest()}


def scene_record(cls, scale):
    sid = f"{cls}-1"
    path = fake_generate(cls, 1, "data/research/scenes")
    candidates = {
        name: {"off": run_record(10.05 * scale, 10.5 * scale, 20.0, 5, 2, 3, bench.SEED, 7),
               "on": run_record(10.1 * scale, 10.3 * scale, 40.0, 4, 1, 2, bench.SEED, 7)}
        for name in bench.CANDIDATES
    }
    return {
        "id": sid, "class": cls, "seed": 1, "hash": hashlib.sha256(path.read_bytes()).hexdigest(),
        "graph": {"nodes": 100, "edges": 1000, "build_s": 2.0}, "bound_graph": {"nodes": 100, "edges": 1200, "build_s": 2.0},
        "service": run_record(10.5 * scale, 10.9 * scale, 3.0, 6, 3, 3),
        "candidates": candidates,
        "exact": bound_record("optimal", 10.0 * scale, 9.995 * scale, 100.0, f"data/research/logs/{sid}-exact.log"),
        "lp": bound_record("optimal", 9.0 * scale, 9.0 * scale, 5.0, f"data/research/logs/{sid}-lp.log"),
    }


def write_results(results):
    RESULTS.write_text(json.dumps(results, ensure_ascii=False), encoding="utf-8")
    entries = [{"id": s["id"], "class": s["class"], "seed": s["seed"], "path": f"data/research/scenes/{s['id']}.geojson",
                "sha256": s["hash"]} for s in results["scenes"]]
    (RESULTS.parent / "scenes.json").write_text(json.dumps(entries), encoding="utf-8")


def shifted_run(shift):
    def fake(ctx, name, mode, seed, budget, timeout_s):
        results = json.loads(RESULTS.read_text(encoding="utf-8"))
        scene = next(s for s in results["scenes"] if s["id"] == ctx.scene.stem)
        fresh = copy.deepcopy(bench.get_run(scene, name, mode))
        fresh["S"] += shift
        return fresh
    return fake


def rules_module():
    rules = [QualityRule(f"RQ-{i}", "правило", "A", "СП", "формула", "порог", "objective", "turn", 1000.0, "метод")
             for i in range(1, PRICED + 1)]
    rules.append(QualityRule("RQ-99", "отчётное", "C", "практика", "формула", "порог", "report", "kink", None, ""))
    module = types.ModuleType("heatopt.rules_check")
    module.load = lambda path: rules
    return module


@pytest.fixture
def good(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    monkeypatch.setattr(bench, "MIN_GAP_SCENES", 1)
    monkeypatch.setattr(bench, "TIME_CLASS", "M")
    monkeypatch.setattr(report, "MIN_ROWS", 10)
    monkeypatch.setattr(bench, "generate", fake_generate)
    monkeypatch.setattr(bench.Scene, "load", lambda path: path)
    monkeypatch.setattr(bench, "build_graph", lambda scene, **kwargs: (object(), {}))
    monkeypatch.setattr(bench, "run_algorithm", shifted_run(0.0))
    monkeypatch.setitem(sys.modules, "heatopt.rules_check", rules_module())
    results = {"started": "2026-09-15T20:00:00", "elapsed_s": 1000, "workers": 3,
               "budgets": dict.fromkeys(bench.CANDIDATES, 7), "scenes": [scene_record("S", 1), scene_record("M", 10)]}
    write_results(results)
    return results


def bench_check(capsys):
    code = bench.check(RESULTS, Path("heatnet.jar"), 5.0)
    return code, capsys.readouterr().out


def report_check(capsys, monkeypatch):
    monkeypatch.setattr(sys, "argv", ["heatopt.report", "--check"])
    with pytest.raises(SystemExit) as exit_info:
        report.main()
    return exit_info.value.code, capsys.readouterr().out


def test_bench_markers_on_good_data(good, capsys):
    code, out = bench_check(capsys)
    assert code == 0, out
    assert "GAP OK candidate=bend mean=0.500% max=0.500% scenes_s=1 scenes_m=1" in out
    assert re.search(r"RATIONALITY OK candidate=\w+ s_vs_service=(-\d+(\.\d+)?|0(\.0+)?)%", out), out
    assert "BOUNDS OK scenes=2" in out
    assert re.search(r"BENCH TIME OK elapsed=(\d{1,4}|[1-3]\d{4}|4[0-2]\d{3}|43[01]\d{2}|43200)s", out), out
    assert "CANDIDATE TIME OK class=M p95=40.0s" in out
    assert "BENCH OK scenes=2 service=2 candidates=4 exact=2 lp=2" in out
    assert "FAILED" not in out


def mutate_gap(results):
    for name in bench.CANDIDATES:
        results["scenes"][0]["candidates"][name]["off"]["S"] = 10.5


def mutate_lp(results):
    results["scenes"][0]["lp"]["bound"] = 10.2


def mutate_candidate_time(results):
    results["scenes"][1]["candidates"]["ls"]["on"]["elapsed_s"] = 130.0


def mutate_bench_time(results):
    results["elapsed_s"] = 50000


def mutate_fake_elapsed(results):
    results["elapsed_s"] = 100


def mutate_missing_candidate(results):
    del results["scenes"][0]["candidates"]["dp"]


def mutate_nan_score(results):
    results["scenes"][1]["service"]["S"] = math.nan


def mutate_optimal_without_proof(results):
    results["scenes"][0]["exact"]["bound"] = 9.0


def mutate_timeout_run(results):
    results["scenes"][1]["candidates"]["dp"]["on"].update(status="timeout", S=None, elapsed_s=600.0)


@pytest.mark.parametrize(("mutate", "lost", "kept"), [
    (mutate_gap, "GAP OK", "BOUNDS OK"),
    (mutate_lp, "BOUNDS OK", "GAP OK"),
    (mutate_candidate_time, "CANDIDATE TIME OK", "BENCH TIME OK"),
    (mutate_timeout_run, "CANDIDATE TIME OK", "BOUNDS OK"),
    (mutate_bench_time, "BENCH TIME OK", "CANDIDATE TIME OK"),
    (mutate_fake_elapsed, "BENCH TIME OK", "CANDIDATE TIME OK"),
    (mutate_missing_candidate, "BENCH OK", "BENCH TIME OK"),
    (mutate_timeout_run, "BENCH OK", "BOUNDS OK"),
    (mutate_nan_score, "BENCH OK", "GAP OK"),
    (mutate_optimal_without_proof, "BENCH OK", "BOUNDS OK"),
])
def test_bench_marker_lost_on_bad_data(good, capsys, mutate, lost, kept):
    mutate(good)
    write_results(good)
    code, out = bench_check(capsys)
    assert code == 0, out
    assert lost not in out and lost.replace(" OK", " FAILED") in out, out
    assert kept in out, out


def test_bench_recheck_mismatch(good, capsys, monkeypatch):
    monkeypatch.setattr(bench, "run_algorithm", shifted_run(0.01))
    code, out = bench_check(capsys)
    assert "BENCH OK" not in out and "пересчитан" in out, out
    assert "GAP OK" in out


def test_bench_changed_scene_file(good, capsys):
    Path("data/research/scenes/M-1.geojson").write_text("другая сцена", encoding="utf-8")
    code, out = bench_check(capsys)
    assert "BENCH OK" not in out and "BENCH FAILED" in out, out


def test_bench_changed_log(good, capsys):
    Path("data/research/logs/S-1-exact.log").write_text("подменённый лог", encoding="utf-8")
    code, out = bench_check(capsys)
    assert "BENCH OK" not in out and "log_sha256" in out, out


def test_bench_damaged_files(good, capsys):
    RESULTS.write_text("{не json", encoding="utf-8")
    code, out = bench_check(capsys)
    assert code == 1 and " OK" not in out, out


def test_recommended_from_report(good, capsys):
    Path("docs/research").mkdir(parents=True)
    report.REPORT_PATH.write_text(GOOD_REPORT.format(mean="0,50").replace("кандидат: bend", "кандидат: service"), encoding="utf-8")
    code, out = bench_check(capsys)
    assert "GAP OK" not in out and "RATIONALITY OK" not in out and "«service»" in out, out


@pytest.fixture
def good_report(good):
    Path("docs/research").mkdir(parents=True)
    report.BENCH_MD.write_text(report.render(good), encoding="utf-8")
    report.REPORT_PATH.write_text(GOOD_REPORT.format(mean="0,50"), encoding="utf-8")
    return good


def test_report_markers_on_good_data(good_report, capsys, monkeypatch):
    code, out = report_check(capsys, monkeypatch)
    assert code == 0, out
    assert "BENCH REPORT OK rows=10" in out
    assert f"OBJECTIVE OK priced_rules={PRICED}" in out
    assert re.search(r"^REPORT OK$", out, re.M), out
    table = report.BENCH_MD.read_text(encoding="utf-8")
    assert "| S | 1 из 1 | 0.50 | 0.50 | 0.50 | оптимум 1, граница 0 | 100 |" in table
    assert "| service | M | 1 из 1 | 5.00 |" in table


def test_report_wrong_numbers(good_report, capsys, monkeypatch):
    report.REPORT_PATH.write_text(GOOD_REPORT.format(mean="0,40"), encoding="utf-8")
    code, out = report_check(capsys, monkeypatch)
    assert code == 1 and "REPORT FAILED" in out and not re.search(r"^REPORT OK", out, re.M), out
    assert "BENCH REPORT OK" in out


def test_report_missing_section(good_report, capsys, monkeypatch):
    report.REPORT_PATH.write_text(GOOD_REPORT.format(mean="0,50").replace("### Последствия\n", "").replace("- master: 10 чел.-дней\n", ""), encoding="utf-8")
    code, out = report_check(capsys, monkeypatch)
    assert code == 1 and "Последствия" in out and "master" in out, out


def test_report_edited_table(good_report, capsys, monkeypatch):
    report.BENCH_MD.write_text(report.BENCH_MD.read_text(encoding="utf-8").replace("| 0.50 |", "| 0.10 |", 1), encoding="utf-8")
    code, out = report_check(capsys, monkeypatch)
    assert "BENCH REPORT OK" not in out and "BENCH REPORT FAILED" in out, out


def test_objective_without_penalties(good_report, capsys, monkeypatch):
    for scene in good_report["scenes"]:
        for modes in scene["candidates"].values():
            modes["on"]["S_quality"] = modes["on"]["S"]
    write_results(good_report)
    report.BENCH_MD.write_text(report.render(good_report), encoding="utf-8")
    code, out = report_check(capsys, monkeypatch)
    assert "OBJECTIVE OK" not in out and "OBJECTIVE FAILED" in out, out


def slow(*args):
    time.sleep(30)


def broken(*args):
    raise RuntimeError("кандидат упал")


def test_in_child_failure_and_timeout():
    status, error = bench.in_child(broken, (), 10.0)
    assert status == "failed" and "кандидат упал" in error
    started = time.monotonic()
    assert bench.in_child(slow, (), 0.5) == ("timeout", None)
    assert time.monotonic() - started < 5


def fake_solve(ctx, name, mode, seed, budget):
    if name == "dp" and mode == "on":
        raise RuntimeError("dp упал")
    if name == "bend" and mode == "on":
        time.sleep(30)
    return run_record(10.0, 10.2, 1.0, 1, 0, 1, seed, budget)


def fake_bound(scene, graph, method):
    return {"status": "optimal", "objective": 9.0, "bound": 9.0, "elapsed_s": 1.0, "log": None, "log_sha256": None}


def test_run_survives_failures_and_resumes(tmp_path, monkeypatch, capsys):
    monkeypatch.chdir(tmp_path)
    for name in bench.CANDIDATES:
        module = types.ModuleType(f"heatopt.candidates.{name}")
        module.DEFAULT_BUDGET = 7
        monkeypatch.setitem(sys.modules, module.__name__, module)
    monkeypatch.setitem(sys.modules, "heatopt.exact", types.ModuleType("heatopt.exact"))
    monkeypatch.setitem(sys.modules, "heatopt.rules_check", rules_module())
    monkeypatch.setattr(bench, "generate", fake_generate)
    monkeypatch.setattr(bench.Scene, "load", lambda path: types.SimpleNamespace(path=path, rules={}))
    monkeypatch.setattr(bench, "build_graph", lambda scene, **kwargs: (object(), {"nodes": 1, "edges": 1, "build_s": 0.1}))
    monkeypatch.setattr(bench, "smallest_dn", lambda scene, rules: 50)
    monkeypatch.setattr(bench, "solve_and_evaluate", fake_solve)
    monkeypatch.setattr(bench, "solve_bound", fake_bound)
    jar = tmp_path / "heatnet.jar"
    jar.write_text("jar")
    out_dir = Path("data/research")
    assert bench.run(["S", "M"], [1], 2, out_dir, jar, 1.0, 5.0) == 0
    results = json.loads((out_dir / "results.json").read_text(encoding="utf-8"))
    assert [s["id"] for s in results["scenes"]] == ["S-1", "M-1"]
    s1 = results["scenes"][0]
    assert s1["candidates"]["dp"]["on"]["status"] == "failed" and "dp упал" in s1["candidates"]["dp"]["on"]["error"]
    assert s1["candidates"]["bend"]["on"]["status"] == "timeout" and s1["candidates"]["bend"]["on"]["elapsed_s"] == 1.0
    assert s1["candidates"]["ls"]["on"]["status"] == "ok" and s1["exact"]["status"] == "optimal"
    assert results["scenes"][1]["exact"]["status"] == "optimal"
    assert isinstance(results["elapsed_s"], int) and results["budgets"] == dict.fromkeys(bench.CANDIDATES, 7)
    entries = json.loads((out_dir / "scenes.json").read_text(encoding="utf-8"))
    assert {e["id"] for e in entries} == {"S-1", "M-1"} and all(len(e["sha256"]) == 64 for e in entries)
    capsys.readouterr()
    assert bench.run(["S", "M"], [1], 2, out_dir, jar, 1.0, 5.0) == 0
    assert "уже готовы 2, считаются 0" in capsys.readouterr().out
