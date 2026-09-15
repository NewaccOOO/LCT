"""Бенчмарк сервиса, четырёх кандидатов и нижних оценок на сценах S, M, L и проверка его результатов.

run: python -m heatopt.bench run --classes S,M,L --seeds 1-30 --workers 3 [--out-dir data/research]
check: python -m heatopt.bench --check [--results data/research/results.json]

Сцена считается в процессе пула, каждый алгоритм — в дочернем процессе своей группы: таймаут убивает группу
вместе с java и пулом проверки рёбер, падение алгоритма пишется в status, сцена остаётся в выборке.
"""
import argparse
import hashlib
import importlib
import json
import math
import multiprocessing
import os
import re
import signal
import sys
import tempfile
import time
import traceback
from collections.abc import Callable
from concurrent.futures import (
    ProcessPoolExecutor,
    as_completed,
)
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

from heatopt import (
    model,
    service,
)
from heatopt.graph import (
    VisGraph,
    smallest_dn,
)
from heatopt.model import QualityRule
from heatopt.scene import (
    Scene,
    file_hash,
    generate,
)

CANDIDATES = ("ls", "dp", "bend", "master")
SERVICE = "service"
MODES = ("off", "on")
CLASSES = ("S", "M", "L")
EXACT_CLASSES = ("S", "M")
TIME_CLASS = "L"
RUN_STATUSES = ("ok", "failed", "timeout")
BOUND_STATUSES = ("optimal", "time_limit", "failed")
RUN_KEYS = ("S", "S_quality", "cost_rub", "length_m", "elapsed_s", "turns", "kinks", "chambers", "tie_ins",
            "unconnected", "violations", "output")
OUT_DIR = Path("data/research")
RESULTS_PATH = OUT_DIR / "results.json"
QUALITY_PATH = Path("docs/routing-quality.md")
REPORT_PATH = Path("docs/research/routing-report.md")
RECOMMENDED = re.compile(r"^Рекомендованный кандидат: `?(\w+)`?\s*$", re.M)
SEED = 1
EXACT_TIME_LIMIT_S = 1200
CANDIDATE_TIMEOUT_S = 600.0
EXACT_TIMEOUT_S = 1500.0
SCORE_TOL = 0.001
MIP_REL_GAP = 0.001
MIN_GAP_SCENES = 20
GAP_MEAN_MAX_PCT = 1.0
GAP_MAX_MAX_PCT = 3.0
BENCH_TIME_MAX_S = 43200
CANDIDATE_P95_MAX_S = 120.0
RECHECK_SCENES = 3
SHOWN_PROBLEMS = 5
FORK = multiprocessing.get_context("fork")


@dataclass
class Options:
    out_dir: Path
    jar: Path
    quality: list[QualityRule]
    budgets: dict[str, int]
    candidate_timeout_s: float
    exact_timeout_s: float


@dataclass
class Context:
    scene: Scene
    graph: VisGraph | None
    quality: list[QualityRule]
    jar: Path
    out_root: Path


def number(value: Any) -> float | None:
    """Конечное число из results.json или None: NaN, бесконечность, строка и пропуск не считаются числом."""
    if isinstance(value, bool) or not isinstance(value, int | float) or not math.isfinite(value):
        return None
    return float(value)


def nearest_rank(values: list[float], q: float) -> float:
    ordered = sorted(values)
    return ordered[max(0, math.ceil(q * len(ordered)) - 1)]


def scene_order(item: dict[str, Any]) -> tuple[int, int]:
    return CLASSES.index(item["class"]), item["seed"]


def load_quality() -> list[QualityRule]:
    rules_check = importlib.import_module("heatopt.rules_check")
    return [rule for rule in rules_check.load(QUALITY_PATH) if rule.priced]


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=1, allow_nan=False), encoding="utf-8")
    tmp.replace(path)


def _child(sender: Any, fn: Callable[..., Any], args: tuple[Any, ...]) -> None:
    os.setpgid(0, 0)
    try:
        sender.send(("ok", fn(*args)))
    except BaseException:
        sender.send(("failed", traceback.format_exc()[-2000:]))


def in_child(fn: Callable[..., Any], args: tuple[Any, ...], timeout_s: float) -> tuple[str, Any]:
    """fn(*args) в дочернем процессе (fork, своя группа): ("ok", результат), ("failed", текст ошибки) или ("timeout", None)."""
    receiver, sender = FORK.Pipe(duplex=False)
    process = FORK.Process(target=_child, args=(sender, fn, args))
    process.start()
    sender.close()
    try:
        if not receiver.poll(timeout_s):
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                process.kill()
            return "timeout", None
        return receiver.recv()
    except EOFError:
        process.join()
        return "failed", f"процесс алгоритма завершился с кодом {process.exitcode} без результата"
    finally:
        process.join()
        receiver.close()


def solve_and_evaluate(ctx: Context, name: str, mode: str, seed: int | None, budget: int | None) -> dict[str, Any]:
    if name == SERVICE:
        started = time.perf_counter()
        solution = service.solve(ctx.scene, ctx.jar, ctx.out_root)
    else:
        if ctx.graph is None:
            raise RuntimeError("граф кандидатов не построен")
        module = importlib.import_module(f"heatopt.candidates.{name}")
        started = time.perf_counter()
        solution = module.solve(ctx.scene, ctx.graph, ctx.scene.rules, ctx.quality if mode == "on" else None, budget, seed)
    elapsed = time.perf_counter() - started
    cost = model.cost(solution, ctx.scene)
    shape = model.shape_metrics(ctx.scene, cost.variant)
    violations = model.validate(ctx.scene, cost)
    violations["model"] = len(cost.violations)
    output = ctx.out_root / ctx.scene.path.stem / f"{name}-{mode}.geojson"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(model.to_geojson([cost]), ensure_ascii=False), encoding="utf-8")
    values = {
        "S": cost.score_value, "S_quality": model.objective(cost, ctx.scene, ctx.quality),
        "cost_rub": cost.total, "length_m": cost.length, "elapsed_s": elapsed,
    }
    if any(number(v) is None for v in values.values()):
        raise ValueError(f"нечисловое значение в оценке решения: {values}")
    return {"status": "ok", **values, **shape, "unconnected": cost.unconnected, "violations": violations,
            "seed": seed, "budget": budget, "output": str(output)}


def run_algorithm(ctx: Context, name: str, mode: str, seed: int | None, budget: int | None, timeout_s: float) -> dict[str, Any]:
    status, value = in_child(solve_and_evaluate, (ctx, name, mode, seed, budget), timeout_s)
    if status == "ok":
        return value
    record = dict.fromkeys(RUN_KEYS)
    record.update(status=status, elapsed_s=timeout_s if status == "timeout" else None, seed=seed, budget=budget,
                  error=value or f"превышен лимит {timeout_s:.0f} с")
    return record


def solve_bound(scene: Scene, graph: VisGraph | None, method: str) -> dict[str, Any]:
    if graph is None:
        raise RuntimeError("граф нижней оценки не построен")
    exact = importlib.import_module("heatopt.exact")
    bound = getattr(exact, method)(scene, graph, scene.rules, EXACT_TIME_LIMIT_S)
    log = str(bound.log) if bound.log else None
    return {"status": bound.status, "objective": number(bound.objective), "bound": number(bound.bound),
            "elapsed_s": number(bound.elapsed), "log": log,
            "log_sha256": file_hash(Path(log)) if log and Path(log).exists() else None}


def run_bound(scene: Scene, graph: VisGraph | None, method: str, timeout_s: float) -> dict[str, Any]:
    status, value = in_child(solve_bound, (scene, graph, method), timeout_s)
    if status == "ok":
        return value
    return {"status": "failed", "objective": None, "bound": None, "elapsed_s": timeout_s if status == "timeout" else None,
            "log": None, "log_sha256": None, "error": value or f"превышен лимит {timeout_s:.0f} с"}


def build_graph(scene: Scene, **kwargs: Any) -> tuple[VisGraph | None, dict[str, Any]]:
    started = time.perf_counter()
    try:
        graph = VisGraph.build(scene, **kwargs)
        # списки смежности строятся до fork, чтобы запуски алгоритмов не строили их каждый заново
        graph.adj
    except Exception:
        return None, {"nodes": None, "edges": None, "build_s": None, "error": traceback.format_exc()[-2000:]}
    return graph, {"nodes": len(graph.nodes), "edges": len(graph.u), "build_s": round(time.perf_counter() - started, 3)}


def run_scene(entry: dict[str, Any], options: Options) -> dict[str, Any]:
    scene = Scene.load(Path(entry["path"]))
    out_root = options.out_dir / "outputs"
    record = {"id": entry["id"], "class": entry["class"], "seed": entry["seed"], "hash": entry["sha256"]}
    ctx = Context(scene, None, options.quality, options.jar, out_root)
    record["service"] = run_algorithm(ctx, SERVICE, "off", None, None, options.candidate_timeout_s)
    ctx.graph, record["graph"] = build_graph(scene, tangent=True)
    record["candidates"] = {
        name: {mode: run_algorithm(ctx, name, mode, SEED, options.budgets[name], options.candidate_timeout_s) for mode in MODES}
        for name in CANDIDATES
    }
    bound_graph, record["bound_graph"] = build_graph(scene, dn_guess=smallest_dn(scene, scene.rules))
    record["exact"] = run_bound(scene, bound_graph, "solve", options.exact_timeout_s) if entry["class"] in EXACT_CLASSES else None
    record["lp"] = run_bound(scene, bound_graph, "lp_bound", options.exact_timeout_s)
    return record


def parse_seeds(text: str) -> list[int]:
    seeds = []
    for part in text.split(","):
        low, _, high = part.partition("-")
        seeds += range(int(low), int(high or low) + 1)
    return seeds


def run(classes: list[str], seeds: list[int], workers: int, out_dir: Path, jar: Path,
        candidate_timeout_s: float, exact_timeout_s: float) -> int:
    if not jar.exists():
        print(f"Нет jar сервиса {jar}: соберите его ensure_jar из scripts/gates/env.sh", file=sys.stderr)
        return 1
    started = time.monotonic()
    budgets = {name: importlib.import_module(f"heatopt.candidates.{name}").DEFAULT_BUDGET for name in CANDIDATES}
    importlib.import_module("heatopt.exact")
    quality = load_quality()
    scenes_path, results_path = out_dir / "scenes.json", out_dir / "results.json"
    index = {e["id"]: e for e in json.loads(scenes_path.read_text(encoding="utf-8"))} if scenes_path.exists() else {}
    requested = []
    for cls in classes:
        for seed in seeds:
            path = generate(cls, seed, out_dir / "scenes")
            index[f"{cls}-{seed}"] = {"id": f"{cls}-{seed}", "class": cls, "seed": seed, "path": str(path), "sha256": file_hash(path)}
            requested.append(f"{cls}-{seed}")
    write_json(scenes_path, sorted(index.values(), key=scene_order))

    results = json.loads(results_path.read_text(encoding="utf-8")) if results_path.exists() else None
    if results is not None and results.get("budgets") != budgets:
        stale = results_path.with_suffix(".stale.json")
        results_path.replace(stale)
        print(f"Бюджеты кандидатов изменились, старые результаты перенесены в {stale}, прогон начат заново")
        results = None
    if results is None:
        results = {"started": datetime.now().isoformat(timespec="seconds"), "elapsed_s": 0, "workers": workers,
                   "budgets": budgets, "scenes": []}
    results["workers"] = workers
    done = {s["id"]: s for s in results["scenes"] if s["id"] in index and index[s["id"]]["sha256"] == s["hash"]}
    todo = [index[scene_id] for scene_id in requested if scene_id not in done]
    print(f"Сцен в прогоне {len(requested)}, уже готовы {len(requested) - len(todo)}, считаются {len(todo)}", flush=True)

    prior_s = results["elapsed_s"]
    os.environ.setdefault("HEATOPT_GRAPH_WORKERS", str(max(1, (os.cpu_count() or 1) // workers)))
    options = Options(out_dir, jar, quality, budgets, candidate_timeout_s, exact_timeout_s)
    lost = 0
    with ProcessPoolExecutor(max_workers=workers, mp_context=FORK) as pool:
        futures = {pool.submit(run_scene, entry, options): entry["id"] for entry in todo}
        for future in as_completed(futures):
            try:
                record = future.result()
            except Exception:
                lost += 1
                print(f"Сцена {futures[future]} не посчитана, повторный запуск её досчитает:\n{traceback.format_exc()}", file=sys.stderr)
                continue
            done[record["id"]] = record
            results["scenes"] = sorted(done.values(), key=scene_order)
            results["elapsed_s"] = math.ceil(prior_s + time.monotonic() - started)
            write_json(results_path, results)
            statuses = [run_record["status"] for _, _, run_record in runs(record)]
            print(f"Сцена {record['id']} готова: запусков ok {statuses.count('ok')} из {len(statuses)}, "
                  f"exact {(record['exact'] or {}).get('status', '—')}, lp {record['lp']['status']}", flush=True)
    results["scenes"] = sorted(done.values(), key=scene_order)
    results["elapsed_s"] = math.ceil(prior_s + time.monotonic() - started)
    write_json(results_path, results)
    print(f"Прогон закончен: сцен в results.json {len(done)}, не посчитано {lost}, стенное время {results['elapsed_s']} с")
    return 1 if lost else 0


def get_run(scene: dict[str, Any], name: str, mode: str) -> dict[str, Any]:
    if name == SERVICE:
        value = scene.get("service")
    else:
        candidates = scene.get("candidates")
        modes = candidates.get(name) if isinstance(candidates, dict) else None
        value = modes.get(mode) if isinstance(modes, dict) else None
    return value if isinstance(value, dict) else {}


def runs(scene: dict[str, Any]) -> list[tuple[str, str, dict[str, Any]]]:
    return [(SERVICE, "off", get_run(scene, SERVICE, "off"))] + [
        (name, mode, get_run(scene, name, mode)) for name in CANDIDATES for mode in MODES
    ]


def ok_score(run_record: dict[str, Any]) -> float | None:
    return number(run_record.get("S")) if run_record.get("status") == "ok" else None


def bound_of(scene: dict[str, Any], key: str) -> dict[str, Any]:
    value = scene.get(key)
    return value if isinstance(value, dict) else {}


def proven_optimum(scene: dict[str, Any]) -> float | None:
    exact = bound_of(scene, "exact")
    objective = number(exact.get("objective"))
    if exact.get("status") != "optimal" or objective is None or objective <= 0:
        return None
    return objective


def reference(scene: dict[str, Any]) -> tuple[float, bool] | None:
    """База разрыва: (доказанный оптимум, True) или (лучшая нижняя граница точной модели и LP, False)."""
    optimum = proven_optimum(scene)
    if optimum is not None:
        return optimum, True
    bounds = [b for b in (number(bound_of(scene, "exact").get("bound")), number(bound_of(scene, "lp").get("bound"))) if b is not None]
    if not bounds or max(bounds) <= 0:
        return None
    return max(bounds), False


@dataclass
class Gap:
    values: list[float]
    scenes: dict[str, int]
    missing: list[str]

    @property
    def mean(self) -> float | None:
        return sum(self.values) / len(self.values) if self.values else None

    @property
    def max(self) -> float | None:
        return max(self.values) if self.values else None


def gap_stats(results: dict[str, Any], name: str, mode: str = "off") -> Gap:
    """AC-1.2: разрыв max(0, S − S_opt) / S_opt в процентах на сценах S и M с доказанным оптимумом; сцена, где
    алгоритм не дал решения со status ok, попадает в missing."""
    gap = Gap([], dict.fromkeys(EXACT_CLASSES, 0), [])
    for scene in results["scenes"]:
        optimum = proven_optimum(scene)
        if scene["class"] not in EXACT_CLASSES or optimum is None:
            continue
        score = ok_score(get_run(scene, name, mode))
        if score is None:
            gap.missing.append(scene["id"])
            continue
        gap.values.append(max(0.0, score - optimum) / optimum * 100)
        gap.scenes[scene["class"]] += 1
    return gap


def failure(name: str, problems: list[str]) -> str:
    shown = "; ".join(problems[:SHOWN_PROBLEMS])
    more = f"; ещё проблем {len(problems) - SHOWN_PROBLEMS}" if len(problems) > SHOWN_PROBLEMS else ""
    return f"{name} FAILED: {shown}{more}"


def load_inputs(results_path: Path) -> tuple[dict[str, Any], list[dict[str, Any]], str]:
    """results.json, scenes.json рядом с ним и sha256 содержимого results.json; ValueError при повреждении."""
    raw = results_path.read_bytes()
    results = json.loads(raw)
    entries = json.loads((results_path.parent / "scenes.json").read_text(encoding="utf-8"))
    if not isinstance(results, dict) or not isinstance(results.get("scenes"), list) or not isinstance(entries, list):
        raise ValueError("results.json или scenes.json не по схеме: нет списка сцен")
    for item in results["scenes"] + entries:
        if (not isinstance(item, dict) or not isinstance(item.get("id"), str) or item.get("class") not in CLASSES
                or isinstance(item.get("seed"), bool) or not isinstance(item.get("seed"), int)):
            raise ValueError(f"сцена без id, класса или сида: {str(item)[:200]}")
    for entry in entries:
        if not isinstance(entry.get("path"), str) or not isinstance(entry.get("sha256"), str):
            raise ValueError(f"в scenes.json у сцены {entry['id']} нет пути или sha256")
    return results, entries, hashlib.sha256(raw).hexdigest()


def recommended(results: dict[str, Any]) -> tuple[str | None, str]:
    if REPORT_PATH.exists():
        match = RECOMMENDED.search(REPORT_PATH.read_text(encoding="utf-8"))
        if match:
            return match.group(1), f"строка «Рекомендованный кандидат» в {REPORT_PATH}"
    means = {name: gap_stats(results, name).mean for name in CANDIDATES}
    ranked = sorted((mean, name) for name, mean in means.items() if mean is not None)
    if not ranked:
        return None, "ни у одного кандидата нет сцен для разрыва"
    return ranked[0][1], f"наименьший средний разрыв, в {REPORT_PATH} строки нет"


def check_gap(results: dict[str, Any], name: str | None) -> str:
    if name not in CANDIDATES:
        return failure("GAP", [f"рекомендован «{name}», а проверяются только кандидаты {', '.join(CANDIDATES)}"])
    gap = gap_stats(results, name)
    problems = [f"нет решения {name} в режиме off на сценах с доказанным оптимумом: {', '.join(gap.missing)}"] if gap.missing else []
    problems += [f"сцен {cls} с доказанным оптимумом {count}, нужно не меньше {MIN_GAP_SCENES}"
                 for cls, count in gap.scenes.items() if count < MIN_GAP_SCENES]
    if not gap.values:
        problems.append("нет ни одной сцены с доказанным оптимумом и решением кандидата")
    elif gap.mean > GAP_MEAN_MAX_PCT:
        problems.append(f"средний разрыв {gap.mean:.3f}% больше {GAP_MEAN_MAX_PCT}%")
    if gap.values and gap.max > GAP_MAX_MAX_PCT:
        problems.append(f"максимальный разрыв {gap.max:.3f}% больше {GAP_MAX_MAX_PCT}%")
    if problems:
        return failure("GAP", problems)
    return f"GAP OK candidate={name} mean={gap.mean:.3f}% max={gap.max:.3f}% scenes_s={gap.scenes['S']} scenes_m={gap.scenes['M']}"


def check_rationality(results: dict[str, Any], name: str | None) -> str:
    if name not in CANDIDATES:
        return failure("RATIONALITY", [f"рекомендован «{name}», а проверяются только кандидаты {', '.join(CANDIDATES)}"])
    problems, diffs = [], []
    for cls in CLASSES:
        scenes = [s for s in results["scenes"] if s["class"] == cls]
        if not scenes:
            continue
        sums = {side: dict.fromkeys(("turns", "kinks", "chambers", "S"), 0.0) for side in ("candidate", "service")}
        for scene in scenes:
            for side, run_record in (("candidate", get_run(scene, name, "on")), ("service", get_run(scene, SERVICE, "off"))):
                values = {key: number(run_record.get(key)) for key in sums[side]}
                if run_record.get("status") != "ok" or None in values.values():
                    problems.append(f"{scene['id']}: у {name if side == 'candidate' else SERVICE} нет решения со status ok и метриками")
                    continue
                for key, value in values.items():
                    sums[side][key] += value
        if problems:
            continue
        for key in ("turns", "kinks", "chambers"):
            if sums["candidate"][key] > sums["service"][key] + 1e-9:
                problems.append(f"класс {cls}: среднее {key} {sums['candidate'][key] / len(scenes):.2f} больше, "
                                f"чем у сервиса {sums['service'][key] / len(scenes):.2f}")
        if sums["service"]["S"] <= 0:
            problems.append(f"класс {cls}: средний S сервиса не больше нуля")
            continue
        diff = (sums["candidate"]["S"] - sums["service"]["S"]) / sums["service"]["S"] * 100
        if diff > 0:
            problems.append(f"класс {cls}: средний S больше сервиса на {diff:.3f}%")
        diffs.append(diff)
    if not diffs and not problems:
        problems.append("в results.json нет сцен")
    if problems:
        return failure("RATIONALITY", problems)
    return f"RATIONALITY OK candidate={name} s_vs_service={max(diffs):.3f}%"


def check_bounds(results: dict[str, Any]) -> str:
    problems = []
    for scene in results["scenes"]:
        sid = scene["id"]
        lp = number(bound_of(scene, "lp").get("bound"))
        exact = bound_of(scene, "exact")
        exact_bound, exact_objective = number(exact.get("bound")), number(exact.get("objective"))
        if lp is None:
            problems.append(f"{sid}: нет LP-границы")
            continue
        if exact_bound is not None and lp > exact_bound + SCORE_TOL:
            problems.append(f"{sid}: LP {lp:.4f} выше границы точной модели {exact_bound:.4f}")
        if exact_bound is not None and exact_objective is not None and exact_bound > exact_objective + SCORE_TOL:
            problems.append(f"{sid}: граница точной модели {exact_bound:.4f} выше её решения {exact_objective:.4f}")
        for name, mode, run_record in runs(scene):
            score = ok_score(run_record)
            if score is None:
                continue
            for label, value in (("LP", lp), ("граница точной модели", exact_bound)):
                if value is not None and value > score + SCORE_TOL:
                    problems.append(f"{sid}: {label} {value:.4f} выше S {score:.4f} у {name}-{mode}")
    if not results["scenes"]:
        problems.append("в results.json нет сцен")
    if problems:
        return failure("BOUNDS", problems)
    return f"BOUNDS OK scenes={len(results['scenes'])}"


def check_bench_time(results: dict[str, Any]) -> str:
    elapsed = results.get("elapsed_s")
    if isinstance(elapsed, bool) or not isinstance(elapsed, int) or elapsed < 0:
        return failure("BENCH TIME", [f"elapsed_s не целое число секунд: {elapsed!r}"])
    workers = results.get("workers")
    if isinstance(workers, bool) or not isinstance(workers, int) or workers < 1:
        return failure("BENCH TIME", [f"workers не положительное целое: {workers!r}"])
    busy = 0.0
    for scene in results["scenes"]:
        durations = [r.get("elapsed_s") for _, _, r in runs(scene)]
        durations += [bound_of(scene, key).get("elapsed_s") for key in ("exact", "lp")]
        durations += [bound_of(scene, key).get("build_s") for key in ("graph", "bound_graph")]
        busy += sum(v for v in map(number, durations) if v is not None)
    problems = []
    if elapsed > BENCH_TIME_MAX_S:
        problems.append(f"прогон шёл {elapsed} с, лимит {BENCH_TIME_MAX_S} с")
    # сцена считается в одном процессе последовательно, поэтому стенное время не меньше суммы замеров на процесс
    if busy / workers > elapsed + 1:
        problems.append(f"elapsed_s {elapsed} с меньше суммы замеров {busy:.0f} с на {workers} процесса")
    if problems:
        return failure("BENCH TIME", problems)
    return f"BENCH TIME OK elapsed={elapsed}s"


def check_candidate_time(results: dict[str, Any]) -> str:
    scenes = [s for s in results["scenes"] if s["class"] == TIME_CLASS]
    if not scenes:
        return failure("CANDIDATE TIME", [f"нет сцен класса {TIME_CLASS}"])
    p95 = {}
    for name in CANDIDATES:
        times = []
        for scene in scenes:
            run_record = get_run(scene, name, "on")
            elapsed = number(run_record.get("elapsed_s"))
            # упавший или прерванный запуск не уложился в лимит
            times.append(elapsed if run_record.get("status") == "ok" and elapsed is not None else math.inf)
        p95[name] = nearest_rank(times, 0.95)
    print(f"Время кандидатов на классе {TIME_CLASS} в режиме on, p95: " + ", ".join(f"{n} {t:.1f} с" for n, t in p95.items()))
    worst = max(p95.values())
    if worst > CANDIDATE_P95_MAX_S:
        return failure("CANDIDATE TIME", [f"p95 {worst:.1f} с больше {CANDIDATE_P95_MAX_S:.0f} с"])
    return f"CANDIDATE TIME OK class={TIME_CLASS} p95={worst:.1f}s"


def recheck_scene(scene: dict[str, Any], entry: dict[str, Any], jar: Path, quality: list[QualityRule], timeout_s: float) -> list[str]:
    """Пересчёт сервиса и кандидатов в обоих режимах с записанными seed и budget; S и S_quality сверяются до SCORE_TOL."""
    sid = scene["id"]
    loaded = Scene.load(Path(entry["path"]))
    graph, _ = build_graph(loaded, tangent=True)
    if graph is None:
        return [f"{sid}: граф кандидатов не построен при пересчёте"]
    problems = []
    with tempfile.TemporaryDirectory() as tmp:
        ctx = Context(loaded, graph, quality, jar, Path(tmp))
        for name, mode, recorded in runs(scene):
            if recorded.get("status") != "ok":
                continue
            fresh = run_algorithm(ctx, name, mode, recorded.get("seed"), recorded.get("budget"), timeout_s)
            if fresh["status"] != "ok":
                problems.append(f"{sid} {name}-{mode}: пересчёт завершился со status {fresh['status']}")
                continue
            for key in ("S", "S_quality"):
                was, now = number(recorded.get(key)), number(fresh.get(key))
                if was is None or now is None or abs(was - now) > SCORE_TOL:
                    problems.append(f"{sid} {name}-{mode}: {key} записан {was}, пересчитан {now}")
            # метрики C-9 идут в RATIONALITY, поэтому сверяются вместе с S
            for key in ("turns", "kinks", "chambers", "tie_ins"):
                if recorded.get(key) != fresh.get(key):
                    problems.append(f"{sid} {name}-{mode}: {key} записан {recorded.get(key)}, пересчитан {fresh.get(key)}")
    return problems


def check_bench(results: dict[str, Any], entries: list[dict[str, Any]], digest: str, jar: Path, timeout_s: float) -> str:
    problems = []
    scenes = results["scenes"]
    by_entry = {e["id"]: e for e in entries}
    ids = [s["id"] for s in scenes]
    if len(set(ids)) != len(ids):
        problems.append("в results.json повторяются сцены")
    if set(ids) != set(by_entry):
        problems.append(f"сцены results.json и scenes.json расходятся: {sorted(set(ids) ^ set(by_entry))[:10]}")
    counts = {cls: sum(s["class"] == cls for s in scenes) for cls in CLASSES}
    if len({count for count in counts.values() if count}) != 1:
        problems.append(f"сцен нет или по классам их разное число: {counts}")
    budgets = results.get("budgets") if isinstance(results.get("budgets"), dict) else {}
    problems += [f"в budgets нет целого бюджета {name}" for name in CANDIDATES
                 if isinstance(budgets.get(name), bool) or not isinstance(budgets.get(name), int)]
    with tempfile.TemporaryDirectory() as tmp:
        for scene in scenes:
            sid, entry = scene["id"], by_entry.get(scene["id"])
            if entry is None:
                continue
            if (entry["class"], entry["seed"]) != (scene["class"], scene["seed"]) or entry["sha256"] != scene.get("hash"):
                problems.append(f"{sid}: класс, сид или хеш в results.json и scenes.json расходятся")
            if sid != f"{scene['class']}-{scene['seed']}":
                problems.append(f"{sid}: id не совпадает с классом и сидом")
            path = Path(entry["path"])
            if not path.exists() or file_hash(path) != entry["sha256"]:
                problems.append(f"{sid}: файл сцены {path} отсутствует или изменён")
            if file_hash(generate(scene["class"], scene["seed"], Path(tmp))) != entry["sha256"]:
                problems.append(f"{sid}: heatsynth по классу и сиду даёт другую сцену")
    service_ok = exact_optimal = lp_ok = 0
    for scene in scenes:
        sid = scene["id"]
        for name, mode, run_record in runs(scene):
            status = run_record.get("status")
            if status not in RUN_STATUSES:
                problems.append(f"{sid}: нет запуска {name}-{mode}")
                continue
            if status != "ok":
                # AC-1.1: у каждой сцены есть результат сервиса и каждого кандидата в обоих режимах
                problems.append(f"{sid}: у {name}-{mode} нет результата, status {status}")
            elif number(run_record.get("S")) is None:
                problems.append(f"{sid}: у {name}-{mode} status ok без числового S")
            if name != SERVICE and (run_record.get("seed") != SEED or run_record.get("budget") != budgets.get(name)):
                problems.append(f"{sid}: у {name}-{mode} seed или budget не совпадает с прогоном")
        if ok_score(get_run(scene, SERVICE, "off")) is not None:
            service_ok += 1
        else:
            problems.append(f"{sid}: у сервиса нет решения со status ok")
        exact, lp = bound_of(scene, "exact"), bound_of(scene, "lp")
        if scene["class"] in EXACT_CLASSES:
            if exact.get("status") not in BOUND_STATUSES:
                problems.append(f"{sid}: точная модель не запускалась")
            elapsed = number(exact.get("elapsed_s"))
            if exact.get("status") in ("optimal", "time_limit") and (elapsed is None or elapsed > EXACT_TIMEOUT_S):
                problems.append(f"{sid}: точная модель шла {elapsed} с, больше лимита прогона {EXACT_TIMEOUT_S:.0f} с")
            if exact.get("status") == "optimal":
                objective, bound = number(exact.get("objective")), number(exact.get("bound"))
                if objective is None or bound is None or objective <= 0:
                    problems.append(f"{sid}: status optimal без objective и bound")
                elif objective - bound > MIP_REL_GAP * objective + 1e-6:
                    problems.append(f"{sid}: status optimal при разрыве границ {(objective - bound) / objective:.4%}")
                else:
                    exact_optimal += 1
        # граница LP, прерванной по времени, не гарантирована
        if lp.get("status") == "optimal" and number(lp.get("bound")) is not None:
            lp_ok += 1
        else:
            problems.append(f"{sid}: нет LP-границы со status optimal")
        for label, record in (("exact", exact), ("lp", lp)):
            log = record.get("log")
            if log and (not Path(log).exists() or file_hash(Path(log)) != record.get("log_sha256")):
                problems.append(f"{sid}: лог {label} {log} отсутствует или не совпадает с log_sha256")
    if problems:
        return failure("BENCH", problems)

    chosen = sorted(scenes, key=lambda s: hashlib.sha256(f"{digest}:{s['id']}".encode()).hexdigest())[:RECHECK_SCENES]
    print(f"Пересчёт сцен {', '.join(s['id'] for s in chosen)} (выбраны по sha256 results.json {digest[:12]})", flush=True)
    try:
        quality = load_quality()
    except Exception as error:
        return failure("BENCH", [f"правила разумности для пересчёта не загружены: {error}"])
    os.environ.setdefault("HEATOPT_GRAPH_WORKERS", str(max(1, (os.cpu_count() or 1) // len(chosen))))
    with ProcessPoolExecutor(max_workers=len(chosen), mp_context=FORK) as pool:
        futures = [pool.submit(recheck_scene, s, by_entry[s["id"]], jar, quality, timeout_s) for s in chosen]
        for scene, future in zip(chosen, futures):
            try:
                found = future.result()
            except Exception as error:
                found = [f"{scene['id']}: пересчёт упал: {error}"]
            print(f"Пересчёт {scene['id']}: {'совпал' if not found else 'расхождений ' + str(len(found))}", flush=True)
            problems += found
    if problems:
        return failure("BENCH", problems)
    return (f"BENCH OK scenes={len(scenes)} service={service_ok} candidates={len(CANDIDATES)} "
            f"exact={exact_optimal} lp={lp_ok}")


def check(results_path: Path, jar: Path, timeout_s: float) -> int:
    try:
        results, entries, digest = load_inputs(results_path)
    except (OSError, ValueError) as error:
        print(f"BENCH FAILED: результаты бенчмарка не прочитаны: {error}")
        return 1
    name, source = recommended(results)
    print(f"Кандидат для GAP и RATIONALITY: {name} ({source})")
    print(check_gap(results, name), flush=True)
    print(check_rationality(results, name), flush=True)
    print(check_bounds(results), flush=True)
    print(check_bench_time(results), flush=True)
    print(check_candidate_time(results), flush=True)
    print(check_bench(results, entries, digest, jar, timeout_s), flush=True)
    return 0


def main() -> None:
    parser = argparse.ArgumentParser(prog="heatopt.bench", description="Бенчмарк алгоритмов трассировки и его проверка")
    parser.add_argument("command", nargs="?", choices=["run"], help="run — посчитать сцены")
    parser.add_argument("--check", action="store_true", help="проверить results.json по AC-1.1, AC-1.2, AC-1.4, AC-3.2, NFR-1, NFR-2")
    parser.add_argument("--classes", default="S,M,L")
    parser.add_argument("--seeds", default="1-30")
    parser.add_argument("--workers", type=int, default=3)
    parser.add_argument("--out-dir", type=Path, default=OUT_DIR)
    parser.add_argument("--results", type=Path, default=RESULTS_PATH)
    parser.add_argument("--jar", type=Path, default=service.JAR)
    parser.add_argument("--candidate-timeout", type=float, default=CANDIDATE_TIMEOUT_S, help="лимит на запуск сервиса или кандидата, с")
    parser.add_argument("--exact-timeout", type=float, default=EXACT_TIMEOUT_S, help="лимит на точную модель и LP, с")
    args = parser.parse_args()
    if args.check == (args.command == "run"):
        parser.error("нужна ровно одна команда: run или --check")
    if args.check:
        sys.exit(check(args.results, args.jar, args.candidate_timeout))
    classes = args.classes.split(",")
    if any(cls not in CLASSES for cls in classes):
        parser.error(f"классы только из {', '.join(CLASSES)}")
    sys.exit(run(classes, parse_seeds(args.seeds), args.workers, args.out_dir, args.jar, args.candidate_timeout, args.exact_timeout))


if __name__ == "__main__":
    main()
