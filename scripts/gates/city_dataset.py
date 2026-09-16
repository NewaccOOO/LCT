#!/usr/bin/env python3
"""Гейты пакета city-dataset: файл «город» и сцены «густо».

Запуск из корня: python3 scripts/gates/city_dataset.py [city|dense|stats]
  city  — data/synth/city-1.geojson не меньше 3 ГБ, фич не меньше 1 000 000, heatsynth.check проходит потоково,
          печатает CITY FILE OK
  dense — data/synth/dense-{50,100,200}.geojson есть, точек подключения ровно 50/100/200, heatsynth.check проходит,
          печатает DENSE FILES OK
  stats — docs/testing/dataset-report.md содержит таблицу распределений с колонками для датасета организаторов и
          города, и медиана числа вершин здания города в пределах 10–20, медиана площади 900–3600 м²
          (у датасета организаторов 14 вершин и 1826 м²), печатает DATASET STATS OK
Без аргумента — все три.
"""
import os
import re
import subprocess
import sys

GB = 1024 ** 3


def count(pattern, path):
    return int(subprocess.run(["grep", "-c", pattern, path], capture_output=True, text=True).stdout.strip() or 0)


def check(path, *extra):
    result = subprocess.run(["uv", "run", "--project", "tools", "python", "-m", "heatsynth.check", path, *extra],
                            capture_output=True, text=True)
    if result.returncode != 0:
        print(f"heatsynth.check {path}: {(result.stdout + result.stderr)[-600:]}")
    return result.returncode == 0


def city():
    path = "data/synth/city-1.geojson"
    if not os.path.exists(path):
        print(f"нет {path}")
        return False
    size = os.path.getsize(path)
    features = count('"type": *"Feature"', path)
    print(f"город: {size / GB:.2f} ГБ, фич {features}")
    ok = size >= 3 * GB and features >= 1_000_000 and check(path)
    if ok:
        print("CITY FILE OK")
    return ok


def dense():
    ok = True
    for n in (50, 100, 200):
        path = f"data/synth/dense-{n}.geojson"
        if not os.path.exists(path):
            print(f"нет {path}")
            ok = False
            continue
        points = count('"oks_connection_point"', path)
        print(f"густо-{n}: точек подключения {points}")
        ok = ok and points == n and check(path)
    if ok:
        print("DENSE FILES OK")
    return ok


def stats():
    path = "docs/testing/dataset-report.md"
    if not os.path.exists(path):
        print(f"нет {path}")
        return False
    text = open(path, encoding="utf-8").read()
    verts = re.search(r"медиана вершин здания.*?\|\s*([\d.,]+)\s*\|\s*([\d.,]+)\s*\|", text, re.IGNORECASE | re.DOTALL)
    area = re.search(r"медиана площади здания.*?\|\s*([\d.,]+)\s*\|\s*([\d.,]+)\s*\|", text, re.IGNORECASE | re.DOTALL)
    if not verts or not area:
        print("в отчёте нет строк «медиана вершин здания» и «медиана площади здания» с двумя числами: организаторы, город")
        return False
    v = float(verts.group(2).replace(",", ".").replace(" ", ""))
    a = float(area.group(2).replace(",", ".").replace(" ", ""))
    print(f"город: медиана вершин {v}, медиана площади {a}")
    ok = 10 <= v <= 20 and 900 <= a <= 3600
    if ok:
        print("DATASET STATS OK")
    gen = re.search(r"генерация:\s*([\d.,]+)\s*с,\s*([\d.,]+)\s*МБ", text)
    if gen:
        sec = float(gen.group(1).replace(",", "."))
        mb = float(gen.group(2).replace(",", "."))
        print(f"генерация: {sec:.0f} с, {mb:.0f} МБ")
        if sec <= 1800 and mb <= 4096:
            print("CITY GEN TIME OK")
        else:
            ok = False
    else:
        print("в отчёте нет строки «генерация: … с, … МБ»")
        ok = False
    return ok


if __name__ == "__main__":
    which = sys.argv[1] if len(sys.argv) > 1 else "all"
    steps = {"city": city, "dense": dense, "stats": stats}
    todo = [steps[which]] if which in steps else list(steps.values())
    sys.exit(0 if all(step() for step in todo) else 1)
