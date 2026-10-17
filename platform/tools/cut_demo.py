#!/usr/bin/env python3
"""Демо-набор для платформы: вырезать вход вокруг новой сети и положить пару «вход + выход» в platform/demo.

    python3 platform/tools/cut_demo.py ВХОД ВЫХОД --name spb-kupchino --title "СПб, Купчино" [--margin 250]

Из входа остаются объекты, чей охват пересекает охват новой сети с запасом margin метров, координаты
округляются до 1e-6° (~0,1 м). Выход копируется как есть. Список наборов — platform/demo/index.json.
"""
import argparse
import json
import math
from pathlib import Path

DEMO = Path(__file__).resolve().parents[1] / "demo"


def coords(g):
    t, c = g["type"], g["coordinates"]
    if t == "Point":
        yield c
    elif t in ("LineString", "MultiPoint"):
        yield from c
    elif t in ("Polygon", "MultiLineString"):
        for r in c:
            yield from r
    elif t == "MultiPolygon":
        for p in c:
            for r in p:
                yield from r


def rnd(c):
    return [rnd(x) for x in c] if isinstance(c[0], list) else [round(c[0], 6), round(c[1], 6)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("input")
    ap.add_argument("output")
    ap.add_argument("--name", required=True)
    ap.add_argument("--title", required=True)
    ap.add_argument("--margin", type=float, default=250.0)
    a = ap.parse_args()
    fin = json.loads(Path(a.input).read_text(encoding="utf-8"))
    fout = json.loads(Path(a.output).read_text(encoding="utf-8"))
    pts = [p for f in fout["features"] if f.get("geometry") for p in coords(f["geometry"])]
    lon0, lon1 = min(p[0] for p in pts), max(p[0] for p in pts)
    lat0, lat1 = min(p[1] for p in pts), max(p[1] for p in pts)
    dlat = a.margin / 111195.0
    dlon = dlat / math.cos(math.radians((lat0 + lat1) / 2))
    lon0, lon1, lat0, lat1 = lon0 - dlon, lon1 + dlon, lat0 - dlat, lat1 + dlat
    keep = []
    for f in fin["features"]:
        g = f.get("geometry")
        if not g:
            continue
        cs = list(coords(g))
        if not cs:
            continue
        if max(p[0] for p in cs) < lon0 or min(p[0] for p in cs) > lon1 or max(p[1] for p in cs) < lat0 or min(p[1] for p in cs) > lat1:
            # источник и точки подключения нужны всегда: без них сцена теряет смысл
            if (f.get("properties") or {}).get("object_type") not in ("source", "oks_connection_point"):
                continue
        g = dict(g, coordinates=rnd(g["coordinates"]))
        keep.append(dict(f, geometry=g))
    DEMO.mkdir(exist_ok=True)
    sep = (",", ":")
    (DEMO / f"{a.name}.in.geojson").write_text(json.dumps({"type": "FeatureCollection", "features": keep}, ensure_ascii=False, separators=sep), encoding="utf-8")
    (DEMO / f"{a.name}.out.geojson").write_text(json.dumps(fout, ensure_ascii=False, separators=sep), encoding="utf-8")
    idx_path = DEMO / "index.json"
    idx = json.loads(idx_path.read_text(encoding="utf-8")) if idx_path.exists() else []
    idx = [d for d in idx if d["name"] != a.name] + [{"name": a.name, "title": a.title, "input": f"{a.name}.in.geojson", "output": f"{a.name}.out.geojson"}]
    idx_path.write_text(json.dumps(idx, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"{a.name}: объектов входа {len(keep)} из {len(fin['features'])}")


if __name__ == "__main__":
    main()
