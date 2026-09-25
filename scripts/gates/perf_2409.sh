#!/usr/bin/env bash
# Гейты пакета perf-2409: вариант 1 не хуже v0.6.3. Запуск из корня после scripts/gates/rules_1809.sh того же режима:
#   scripts/gates/perf_2409.sh small — датасет организаторов (оба файла) и «густо» 50/100/200: S не выше и все точки подключены;
#   scripts/gates/perf_2409.sh city  — город: неподключённых не больше, чем у v0.6.3.
set -uo pipefail
cd "$(dirname "$0")/../.."
python3 - "${1:-}" <<'PY'
import json, sys

# вариант 1 версии 0.6.3 на этой машине: S и число неподключённых
BASE_SMALL = {"real": (13.729, 0), "corrected": (13.730, 0), "dense-50": (56.126, 0), "dense-100": (115.644, 0),
              "dense-200": (233.268, 0)}
BASE_CITY_UNCONNECTED = 3130660


def first(path):
    """Сводка варианта 1; выход пишется по фиче на строку, файл города не грузится в память целиком."""
    with open(path, encoding="utf-8") as src:
        for line in src:
            if '"variant_summary"' in line and '"rank":1,' in line:
                body = line[line.find('{"type":"Feature"'):].strip().rstrip(",")
                if body.endswith("]}"):
                    body = body[:-2].rstrip(",")
                return json.loads(body)["properties"]
    sys.exit(f"{path}: нет сводки варианта 1")


mode, ok = sys.argv[1], True
if mode == "small":
    for name, (score, unconnected) in BASE_SMALL.items():
        s = first(f"data/out/r18/{name}.out.geojson")
        good = s["score"] <= score and len(s["unconnected_oks_ids"]) <= unconnected
        ok &= good
        print(f"{name}: S {score} -> {s['score']}, неподключённых {len(s['unconnected_oks_ids'])}", "" if good else "ХУЖЕ")
    print("SMALL NO REGRESSION" if ok else "SMALL REGRESSION")
elif mode == "city":
    s = first("data/out/r18/city-1.out.geojson")
    n = len(s["unconnected_oks_ids"])
    ok = n <= BASE_CITY_UNCONNECTED
    print(f"город: неподключённых {BASE_CITY_UNCONNECTED} -> {n}, S {s['score']}")
    print("CITY NO REGRESSION" if ok else "CITY REGRESSION")
else:
    sys.exit("режим: small|city")
sys.exit(0 if ok else 1)
PY
