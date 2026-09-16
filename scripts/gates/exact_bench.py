#!/usr/bin/env python3
"""Гейт пакета exact-subproblem: стенд с циклом точной подзадачи и без него.

Запуск из корня: python3 scripts/gates/exact_bench.py [--iterations N]
Считает четыре сцены стенда (датасет и medium 1–3) дважды: с циклом выключенным (heatnet.exact.iterations=0)
и включённым по умолчанию. Пишет выходы в data/out/exact/gate-<режим>-<сцена>.geojson и печатает маркеры:
  EXACT OFF UNCHANGED   — выходы без цикла побайтно равны data/out/bench/*-base.geojson (регрессии нет)
  EXACT DETERMINISTIC   — два прогона с циклом на датасете побайтно равны
  EXACT DATASET S=<число> — S варианта 1 на датасете с циклом
  EXACT GAIN OK         — датасет S <= 12.80, каждая сцена medium не хуже базы больше чем на 0.10
  EXACT TIME OK         — датасет с циклом не дольше 360 с, без цикла не дольше 60 с
  EXACT VALID           — heatcheck без нарушений на всех выходах с циклом
  EXACT BENCH PASSED    — все проверки выше прошли
Числа базы берутся из data/out/bench/<сцена>-base.geojson (сделать заранее: scripts/bench.sh base на коммите до цикла).
"""
import filecmp
import json
import os
import subprocess
import sys
import time

DATASET_MAX_S = 12.80
MEDIUM_TOLERANCE = 0.10
DATASET_MAX_SEC = 360
OFF_MAX_SEC = 60
SCENES = [("dataset", "data/real/dataset.geojson")] + [(f"medium-{s}", f"data/synth/medium-{s}.geojson") for s in (1, 2, 3)]


def score(path):
    data = json.load(open(path, encoding="utf-8"))
    return next(f["properties"]["score"] for f in data["features"]
                if f["properties"]["object_type"] == "variant_summary" and f["properties"]["rank"] == 1)


def run(inp, out, iterations):
    t0 = time.monotonic()
    subprocess.run(["java", f"-Dheatnet.exact.iterations={iterations}", "-jar", "target/heatnet.jar", "--cli", inp, out],
                   check=True, capture_output=True)
    return time.monotonic() - t0


def main():
    iterations = None
    if "--iterations" in sys.argv:
        iterations = sys.argv[sys.argv.index("--iterations") + 1]
    os.makedirs("data/out/exact", exist_ok=True)
    ok = True
    # без цикла: регрессии нет и время прежнее
    for name, inp in SCENES:
        out = f"data/out/exact/gate-off-{name}.geojson"
        dt = run(inp, out, 0)
        base = f"data/out/bench/{name}-base.geojson"
        if not os.path.exists(base):
            print(f"нет базы {base}: сначала scripts/bench.sh base на коммите до цикла")
            return 1
        if not filecmp.cmp(out, base, shallow=False):
            print(f"без цикла выход {name} отличается от базы")
            ok = False
        if name == "dataset" and dt > OFF_MAX_SEC:
            print(f"без цикла датасет {dt:.0f} с > {OFF_MAX_SEC}")
            ok = False
    if ok:
        print("EXACT OFF UNCHANGED")
    # с циклом
    times, scores = {}, {}
    for name, inp in SCENES:
        out = f"data/out/exact/gate-on-{name}.geojson"
        args = [] if iterations is None else ["-Dheatnet.exact.iterations=" + iterations]
        t0 = time.monotonic()
        subprocess.run(["java", *args, "-jar", "target/heatnet.jar", "--cli", inp, out], check=True, capture_output=True)
        times[name] = time.monotonic() - t0
        scores[name] = score(out)
    again = "data/out/exact/gate-on-dataset-again.geojson"
    subprocess.run(["java", *([] if iterations is None else ["-Dheatnet.exact.iterations=" + iterations]),
                    "-jar", "target/heatnet.jar", "--cli", SCENES[0][1], again], check=True, capture_output=True)
    if filecmp.cmp("data/out/exact/gate-on-dataset.geojson", again, shallow=False):
        print("EXACT DETERMINISTIC")
    else:
        print("два прогона с циклом на датасете различаются")
        ok = False
    print(f"EXACT DATASET S={scores['dataset']:.3f}")
    gain = scores["dataset"] <= DATASET_MAX_S
    for name, _ in SCENES[1:]:
        base_s = score(f"data/out/bench/{name}-base.geojson")
        if scores[name] > base_s + MEDIUM_TOLERANCE:
            print(f"{name}: S {scores[name]:.3f} хуже базы {base_s:.3f} больше чем на {MEDIUM_TOLERANCE}")
            gain = False
    if gain:
        print("EXACT GAIN OK")
    else:
        ok = False
    if times["dataset"] <= DATASET_MAX_SEC:
        print("EXACT TIME OK")
    else:
        print(f"датасет с циклом {times['dataset']:.0f} с > {DATASET_MAX_SEC}")
        ok = False
    valid = True
    for name, inp in SCENES:
        result = subprocess.run(["uv", "run", "--project", "tools", "python", "-m", "heatcheck", inp,
                                 f"data/out/exact/gate-on-{name}.geojson"], capture_output=True, text=True)
        if "VALIDATION PASSED" not in result.stdout:
            print(f"валидатор: {name}\n{result.stdout[-800:]}")
            valid = False
    if valid:
        print("EXACT VALID")
    else:
        ok = False
    for name, _ in SCENES:
        print(f"  {name}: S {scores[name]:.3f}, {times[name]:.0f} с")
    if ok:
        print("EXACT BENCH PASSED")
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
