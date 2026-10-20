#!/usr/bin/env python3
import argparse
import json
import math
from array import array
from pathlib import Path

EARTH_M = 6371008.8
ROOT = Path(__file__).resolve().parents[2]
RULES = json.loads((ROOT / "rules/rules.json").read_text(encoding="utf-8"))
DN = sorted(RULES["diameters"], key=lambda d: d["dn"])
NORMAL_DEPTH = 3.0
MIN_DEPTH = 0.7
MAX_SLOPE = 0.10

UTILITIES = {
    "gas_pipeline": {"top": 2.8, "h": 0.40, "clear": 0.2, "label": "газопровод"},
    "power_cable": {"top": 2.7, "h": 0.20, "clear": 0.5, "label": "кабель до 35 кВ"},
    "heat_network": {"top": 3.0, "h": None, "clear": 0.5, "label": "теплосеть"},
    "water_supply": {"top": 2.5, "h": 0.30, "clear": None, "label": "водопровод (условно)"},
    "sewer": {"top": 3.5, "h": 0.40, "clear": None, "label": "канализация (условно)"},
}
UNDER = {"road": 1.0, "tram_tracks": 1.2}
POLYGONS = ["oks_existing", "oks_future", "road", "tram_tracks", "railway", "metro", "park", "social_area",
            "water", "prohibited_site"]
CELL = 50.0


def dn_row(dn):
    for d in DN:
        if dn is not None and d["dn"] >= dn:
            return d
    return DN[-1] if dn else DN[0]


def k_depth(h):
    return 1 + 0.1 * (h - NORMAL_DEPTH) if h > NORMAL_DEPTH else 1.0


def lines_of(g):
    t, c = g["type"], g["coordinates"]
    return [c] if t == "LineString" else c if t == "MultiLineString" else []


def rings_of(g):
    t, c = g["type"], g["coordinates"]
    return [c[0]] if t == "Polygon" else [p[0] for p in c] if t == "MultiPolygon" else []


class Layer:

    def __init__(self, kind, dim=2):
        self.kind, self.dim = kind, dim
        self.pos, self.starts, self.ids, self.attr = array("f"), array("I"), [], []

    def add(self, pts, fid, attr=None):
        self.starts.append(len(self.pos) // self.dim)
        for p in pts:
            self.pos.extend(p[:self.dim])
        self.ids.append(fid)
        if attr is not None:
            self.attr.append(attr)


def seg_x(a, b, c, d):
    rx, ry, sx, sy = b[0] - a[0], b[1] - a[1], d[0] - c[0], d[1] - c[1]
    den = rx * sy - ry * sx
    if abs(den) < 1e-12:
        return None
    qx, qy = c[0] - a[0], c[1] - a[1]
    t, u = (qx * sy - qy * sx) / den, (qx * ry - qy * rx) / den
    if 0 <= t <= 1 and 0 <= u <= 1:
        return t, a[0] + t * rx, a[1] + t * ry
    return None


def in_ring(x, y, ring):
    inside, j = False, len(ring) - 1
    for i in range(len(ring)):
        xi, yi, xj, yj = ring[i][0], ring[i][1], ring[j][0], ring[j][1]
        if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi:
            inside = not inside
        j = i
    return inside


def cells(a, b):
    x0, x1 = sorted((a[0], b[0]))
    y0, y1 = sorted((a[1], b[1]))
    for i in range(int(x0 // CELL), int(x1 // CELL) + 1):
        for j in range(int(y0 // CELL), int(y1 // CELL) + 1):
            yield i, j


def main():
    ap = argparse.ArgumentParser(description="3D-сцена теплосети для scripts/ui3d/index.html")
    ap.add_argument("input")
    ap.add_argument("output")
    ap.add_argument("--name", help="имя сцены; по умолчанию имя входа")
    args = ap.parse_args()
    name = args.name or Path(args.input).stem
    fin = json.loads(Path(args.input).read_text(encoding="utf-8"))["features"]
    fout = json.loads(Path(args.output).read_text(encoding="utf-8"))["features"]

    lons, lats = [], []
    for f in fin:
        g = f.get("geometry")
        if g and g["type"] == "Point":
            lons.append(g["coordinates"][0])
            lats.append(g["coordinates"][1])
    lon0, lat0 = sum(lons) / len(lons), sum(lats) / len(lats)
    k = math.cos(math.radians(lat0))

    def xy(p):
        return (math.radians(p[0] - lon0) * EARTH_M * k, math.radians(p[1] - lat0) * EARTH_M)

    layers = {}

    def layer(key, kind, dim=2):
        return layers.setdefault(key, Layer(kind, dim))

    grid = {}
    zones = {t: [] for t in UNDER}
    for f in fin:
        p, g = f.get("properties") or {}, f.get("geometry")
        if not g:
            continue
        t = p.get("object_type")
        rt = p.get("restriction_type") or t
        fid = str(p.get("id"))
        if rt in UTILITIES:
            u = UTILITIES[rt]
            dn = p.get("diameter")
            h = dn_row(dn)["height_m"] if rt == "heat_network" else u["h"]
            for line in lines_of(g):
                pts = [xy(c) for c in line]
                layer("u:" + rt, "path").add(pts, fid, dn)
                for a, b in zip(pts, pts[1:]):
                    for c in cells(a, b):
                        grid.setdefault(c, []).append((rt, fid, a, b, u["top"], h))
        elif rt in POLYGONS:
            for ring in rings_of(g):
                pts = [xy(c) for c in ring]
                layer("p:" + rt, "polygon").add(pts, fid)
                if rt in zones:
                    xs, ys = [q[0] for q in pts], [q[1] for q in pts]
                    zones[rt].append(((min(xs), min(ys), max(xs), max(ys)), pts))
        elif g["type"] == "Point":
            layer("n:" + str(rt), "point").add([xy(g["coordinates"])], fid, p.get("flow_tph"))

    zgrid = {}
    for rt, zs in zones.items():
        for z in zs:
            (x0, y0, x1, y1), _ = z
            for c in cells((x0, y0), (x1, y1)):
                zgrid.setdefault(c, []).append((rt, z))

    props, summaries, by_id = [], [], {}
    for f in fout:
        p = f.get("properties") or {}
        if p.get("object_type") == "variant_summary":
            summaries.append(p)
        elif p.get("id"):
            by_id[p["id"]] = p
    segs = []
    crossings = []
    for f in fout:
        p, g = f.get("properties") or {}, f.get("geometry")
        if not g:
            continue
        t, v = p.get("object_type"), str(p.get("variant_id"))
        idx = len(props)
        props.append(p)
        if g["type"] == "Point":
            layer("o:" + t, "point").add([xy(g["coordinates"])], idx)
            continue
        pts = [xy(c) for c in g["coordinates"]]
        if t != "heat_network":
            layer("o:" + t, "path").add(pts, idx)
            continue
        row = dn_row(p.get("diameter"))
        d0 = p["depth_start"] if p.get("depth_start") is not None else NORMAL_DEPTH
        d1 = p["depth_end"] if p.get("depth_end") is not None else NORMAL_DEPTH
        lens = [0.0]
        for a, b in zip(pts, pts[1:]):
            lens.append(lens[-1] + math.hypot(b[0] - a[0], b[1] - a[1]))
        total = lens[-1] or 1.0
        zc = [-(d0 + (d1 - d0) * s / total) - row["height_m"] / 2 for s in lens]
        layer("o:heat_network", "path", 3).add([(q[0], q[1], z) for q, z in zip(pts, zc)], idx)
        segs.append(idx)

        tied = {(by_id.get(p.get(e)) or {}).get("existing_object_id") for e in ("start_node_id", "end_node_id")}
        seen = set()
        slope = abs(d1 - d0) / total
        issues = []
        if slope > MAX_SLOPE + 1e-9:
            issues.append(f"уклон {slope:.3f} > {MAX_SLOPE}")
        if min(d0, d1) < MIN_DEPTH - 1e-9:
            issues.append(f"глубина {min(d0, d1):.2f} < {MIN_DEPTH} м")
        for i, (a, b) in enumerate(zip(pts, pts[1:])):
            cand = {}
            for c in cells(a, b):
                for e in grid.get(c, ()):
                    cand[id(e)] = e
            for rt, eid, c1, c2, top, eh in cand.values():
                if rt == "heat_network" and eid in tied:
                    continue
                hit = seg_x(a, b, c1, c2)
                if not hit or (rt, eid, round(hit[1], 1), round(hit[2], 1)) in seen:
                    continue
                seen.add((rt, eid, round(hit[1], 1), round(hit[2], 1)))
                s = lens[i] + hit[0] * (lens[i + 1] - lens[i])
                if rt == "heat_network" and min(s, total - s) <= 0.05:
                    continue
                hn = d0 + (d1 - d0) * s / total
                u = UTILITIES[rt]
                over, under = top - (hn + row["height_m"]), hn - (top + eh)
                side = "над" if over >= under else "под"
                gap = max(over, under)
                ok = None if u["clear"] is None else gap >= u["clear"] - 1e-9
                above_max = top - u["clear"] - row["height_m"] if u["clear"] is not None else None
                below_min = top + eh + u["clear"] if u["clear"] is not None else None
                crossings.append({
                    "seg": idx, "v": v, "x": round(hit[1], 2), "y": round(hit[2], 2), "s": round(s, 2),
                    "type": rt, "eid": eid, "top": top, "eh": eh, "hn": round(hn, 3), "H": row["height_m"],
                    "side": side, "gap": round(gap, 3), "need": u["clear"], "ok": ok,
                    "above": round(above_max, 3) if above_max is not None and above_max >= MIN_DEPTH else None,
                    "below": round(below_min, 3) if below_min is not None else None,
                    "k_below": round(k_depth(below_min), 3) if below_min is not None else None,
                })
            for q, sq in ((a, lens[i]), (b, lens[i + 1]), (((a[0] + b[0]) / 2, (a[1] + b[1]) / 2), (lens[i] + lens[i + 1]) / 2)):
                for rt, ((x0, y0, x1, y1), ring) in zgrid.get((int(q[0] // CELL), int(q[1] // CELL)), ()):
                    if x0 <= q[0] <= x1 and y0 <= q[1] <= y1 and in_ring(q[0], q[1], ring):
                        hq = d0 + (d1 - d0) * sq / total
                        if hq < UNDER[rt] - 1e-9:
                            issues.append(f"{rt}: верх {hq:.2f} м мельче {UNDER[rt]} м")
                        break
        if issues:
            crossings.append({"seg": idx, "v": v, "x": round(pts[0][0], 2), "y": round(pts[0][1], 2), "s": 0,
                              "type": "segment", "eid": p.get("id"), "ok": False, "issues": sorted(set(issues))})

    blob, meta = bytearray(), []
    for key, L in layers.items():
        entry = {"key": key, "kind": L.kind, "dim": L.dim, "count": len(L.ids), "ids": L.ids}
        if L.attr and any(a is not None for a in L.attr):
            entry["attr"] = L.attr
        entry["pos"] = [len(blob), len(L.pos)]
        blob += L.pos.tobytes()
        entry["starts"] = [len(blob), len(L.starts)]
        blob += L.starts.tobytes()
        meta.append(entry)
    out_dir = Path(__file__).resolve().parent / "data" / name
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "scene.bin").write_bytes(bytes(blob))
    scene = {
        "name": name, "input": Path(args.input).name, "output": Path(args.output).name,
        "origin": [lon0, lat0], "layers": meta, "props": props, "summaries": summaries,
        "crossings": crossings, "utilities": UTILITIES, "under": UNDER, "diameters": DN,
        "rules": {"normal": NORMAL_DEPTH, "min": MIN_DEPTH, "slope": MAX_SLOPE},
        "has_depth": any(props[i].get("depth_start") is not None for i in segs),
    }
    (out_dir / "scene.json").write_text(json.dumps(scene, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    index = sorted(d.name for d in out_dir.parent.iterdir() if (d / "scene.json").exists())
    (out_dir.parent / "index.json").write_text(json.dumps(index), encoding="utf-8")
    bad = sum(1 for c in crossings if c["ok"] is False)
    print(f"{out_dir}: слоёв {len(meta)}, {len(blob) / 1e6:.1f} МБ, участков {len(segs)}, "
          f"пересечений {sum(1 for c in crossings if c['type'] != 'segment')}, нарушений {bad}")


def selfcheck():
    assert seg_x((0, 0), (2, 0), (1, -1), (1, 1))[1:] == (1.0, 0.0)
    assert seg_x((0, 0), (1, 0), (0, 1), (1, 1)) is None
    assert in_ring(0.5, 0.5, [(0, 0), (1, 0), (1, 1), (0, 1), (0, 0)])
    assert not in_ring(2, 2, [(0, 0), (1, 0), (1, 1), (0, 1), (0, 0)])
    assert dn_row(300)["height_m"] == 0.45 and dn_row(350)["dn"] == 400
    assert abs(k_depth(4.21) - 1.121) < 1e-9 and k_depth(2.0) == 1.0
    assert 3.0 - (2.8 + 0.4) < 0.2


if __name__ == "__main__":
    selfcheck()
    main()
