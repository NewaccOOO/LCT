"""Срез выхода города для проверки check18: окно 3×3 км вокруг середины новой сети варианта 1.

Запуск: python3 scripts/city_cut.py <вход> <выход> <срез входа> <срез выхода> [полуширина окна, м] [долгота широта]
Долгота и широта задают центр окна вместо середины сети.
В срез выхода попадают деревья, целиком лежащие в окне; сводка пересчитывается по ним. В срез входа — объекты
в окне с запасом 200 м, существующая сеть и камеры целиком. Файлы читаются построчно: у входа и выхода по одной фиче на строку.
"""
import json
import math
import sys

MARGIN_DEG_LAT = 200 / 111_000
MARGIN_DEG_LON = 200 / 62_000
PENALTY_FIXED, PENALTY_PER_TPH, TIE_IN = 100_000_000, 500_000, 5_000_000


def features(path):
    """Фичи файла, по одной на строку; у выхода сервиса первая фича идёт в строке с заголовком коллекции."""
    with open(path, encoding="utf-8") as src:
        for line in src:
            body = line.strip()
            head = body.find('"features":[')
            if head >= 0:
                body = body[head + len('"features":['):]
            body = body.strip().strip(",")
            if body.endswith("]}"):
                body = body[:-2].rstrip(",")
            if body.startswith('{"type":"Feature"'):
                yield json.loads(body)


def coords(geometry):
    if geometry is None:
        return []
    if geometry["type"] == "Point":
        return [geometry["coordinates"]]
    if geometry["type"] == "LineString":
        return geometry["coordinates"]
    flat = []
    for part in geometry["coordinates"]:
        for ring in (part if geometry["type"] == "MultiPolygon" else [part]):
            flat.extend(ring)
    return flat


def inside(box, points):
    return all(box[0] <= x <= box[2] and box[1] <= y <= box[3] for x, y in points)


def network_middle(out):
    """Медиана начал новых участков варианта 1: источник может стоять далеко от подключений."""
    xs, ys = [], []
    for f in features(out):
        p = f["properties"]
        if p["object_type"] == "heat_network" and str(p["variant_id"]) == "1":
            x, y = f["geometry"]["coordinates"][0]
            xs.append(x)
            ys.append(y)
            if len(xs) >= 50_000:
                break
    if not xs:
        sys.exit("в выходе нет участков варианта 1")
    xs.sort()
    ys.sort()
    return xs[len(xs) // 2], ys[len(ys) // 2]


def main():
    src, out, cut_src, cut_out = sys.argv[1:5]
    half_m = float(sys.argv[5]) if len(sys.argv) > 5 else 1500
    sx, sy = (float(sys.argv[6]), float(sys.argv[7])) if len(sys.argv) > 7 else network_middle(out)
    dx, dy = half_m / 62_000, half_m / 111_000
    box = (sx - dx, sy - dy, sx + dx, sy + dy)
    wide = (box[0] - MARGIN_DEG_LON, box[1] - MARGIN_DEG_LAT, box[2] + MARGIN_DEG_LON, box[3] + MARGIN_DEG_LAT)

    chambers, cps, flows = set(), set(), {}
    kept_in = 0
    with open(cut_src, "w", encoding="utf-8") as dst:
        dst.write('{"type":"FeatureCollection","features":[\n')
        first = True
        for f in features(src):
            p = f["properties"]
            pts = coords(f["geometry"])
            # сеть и камеры берутся целиком: у трубы в километры обе вершины бывают вне окна, и без неё проверка не
            # узнаёт камеру врезки на ней
            if p.get("object_type") in ("source", "heat_network", "heat_chamber") \
                    or any(wide[0] <= x <= wide[2] and wide[1] <= y <= wide[3] for x, y in pts):
                dst.write(("" if first else ",\n") + json.dumps(f, ensure_ascii=False, separators=(",", ":")))
                first = False
                kept_in += 1
                if p.get("object_type") == "heat_chamber":
                    chambers.add(str(p["id"]))
                elif p.get("object_type") == "oks_connection_point":
                    cps.add(str(p["id"]))
                    flows[str(p["id"])] = p.get("flow_tph", 0.0)
        dst.write("\n]}\n")

    # деревья по вариантам: компоненты по id узлов, целиком в окне
    parent = {}

    def find(a):
        while parent.setdefault(a, a) != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a

    outside = set()
    segments, others, summaries = [], [], {}
    for f in features(out):
        p = f["properties"]
        t = p["object_type"]
        v = str(p["variant_id"])
        if t == "variant_summary":
            summaries[v] = f
            continue
        pts = coords(f["geometry"])
        near = any(wide[0] <= x <= wide[2] and wide[1] <= y <= wide[3] for x, y in pts)
        if not near:
            continue
        if t == "heat_network":
            a, b = (v, str(p["start_node_id"])), (v, str(p["end_node_id"]))
            parent[find(a)] = find(b)
            if inside(box, pts):
                segments.append(f)
            else:
                outside.add(a)
                outside.add(b)
        else:
            others.append(f)
    bad_roots = {find(n) for n in outside}
    kept = [f for f in segments if find((str(f["properties"]["variant_id"]), str(f["properties"]["start_node_id"]))) not in bad_roots]
    used = {(str(f["properties"]["variant_id"]), str(f["properties"][k])) for f in kept for k in ("start_node_id", "end_node_id")}
    kept_others = [f for f in others if (str(f["properties"]["variant_id"]), str(f["properties"]["id"])) in used]
    with open(cut_out, "w", encoding="utf-8") as dst:
        dst.write('{"type":"FeatureCollection","features":[\n')
        rows = kept_others + kept
        for v, summary in sorted(summaries.items()):
            s = dict(summary["properties"])
            segs = [f["properties"] for f in kept if str(f["properties"]["variant_id"]) == v]
            chs = [f["properties"] for f in kept_others if str(f["properties"]["variant_id"]) == v and f["properties"]["object_type"] == "heat_chamber"]
            ties = sum(1 for q in segs for k in ("start_node_id", "end_node_id") if str(q[k]) in chambers)
            connected = {str(q[k]) for q in segs for k in ("start_node_id", "end_node_id")}
            unconnected = [x for x in s["unconnected_oks_ids"] if str(x) in cps and str(x) not in connected]
            s["chamber_construction_cost"] = round(sum(c["cost"] for c in chs), 2)
            s["existing_chamber_tie_in_count"] = ties
            s["existing_chamber_tie_in_cost"] = ties * TIE_IN
            s["construction_cost"] = round(sum(q["cost"] for q in segs) + s["chamber_construction_cost"] + ties * TIE_IN, 2)
            s["unconnected_penalty"] = round(sum(PENALTY_FIXED + PENALTY_PER_TPH * flows.get(str(x), 0.0) for x in unconnected), 2)
            s["calculated_cost"] = round(s["construction_cost"] + s["unconnected_penalty"], 2)
            s["new_network_length"] = round(sum(q["length"] for q in segs), 2)
            s["score"] = round(0.7 * s["calculated_cost"] / 25_000_000 + 0.3 * s["new_network_length"] / 100, 4)
            s["unconnected_oks_ids"] = unconnected
            rows.append({"type": "Feature", "geometry": None, "properties": s})
        for i, f in enumerate(rows):
            dst.write(("" if i == 0 else ",\n") + json.dumps(f, ensure_ascii=False, separators=(",", ":")))
        dst.write("\n]}\n")
    print(f"срез: вход {kept_in} фич, выход {len(kept)} участков, {len(kept_others)} узлов, отброшено деревьев на границе {len(bad_roots)}")


if __name__ == "__main__":
    main()
