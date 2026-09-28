"""Восстановление теплосети из векторной «расчётной схемы тепловых сетей» (PDF схемы теплоснабжения Москвы).

    uv run --project tools --with pymupdf python scripts/heat_restore_pdf.py \
        <pdf> <osm_streets.json> <out.geojson> <страница>[,<страница>...] <ref>

Страница PDF (нумерация с 1) — лист формата A0: трубы — одна ломаная толщиной 0,96 pt, камеры — жёлтые квадраты,
подписи участков «L <м> / Dвн <мм> / G <т/ч>» с выноской, подложка — улицы с подписями названий.
Привязка: подписи улиц листа (центры) сопоставляются с одноимёнными улицами OSM (<osm_streets.json> — ответ
Overpass `way["highway"]["name"](bbox); out geom;`), аффинное преобразование лист → UTM 37N подбирается
итерациями «точка → ближайшая точка одноимённой улицы» с отбрасыванием выбросов. Выход — GeoJSON EPSG:4326:
heat_network (LineString, diameter = Ду из ряда сервиса по Dвн), heat_chamber (Point), source (Point),
consumer (Point, красные ромбы — вводы потребителей); `_source=restored`, `_ref`, `_georef_rmse_m`.
"""
import json
import re
import sys

import numpy as np
import pymupdf
from pyproj import Transformer
import shapely
from shapely import STRtree
from shapely.geometry import LineString, MultiLineString, Point, mapping
from shapely.ops import linemerge, split, substring, transform

DN = [50, 65, 80, 100, 125, 150, 200, 250, 300, 400, 500, 600, 700, 800, 900, 1000, 1200, 1400]
TO_UTM = Transformer.from_crs(4326, 32637, always_xy=True).transform
TO_WGS = Transformer.from_crs(32637, 4326, always_xy=True).transform
NUM = re.compile(r"-?\d+(?:[.,]\d+)?")


def dn_from_inner(d):
    """Dвн 207 → Ду 200, 514 → 500, 898 → 900: ближайший из ряда сервиса (Ду 350 в ряду нет → 300/400)."""
    return min(DN, key=lambda x: abs(x - d))


def norm(s):
    return re.sub(r"\s+", " ", s.upper().replace("Ё", "Е")).strip()


def osm_streets(path):
    by = {}
    for e in json.load(open(path))["elements"]:
        g = transform(TO_UTM, LineString([(p["lon"], p["lat"]) for p in e["geometry"]]))
        by.setdefault(norm(e["tags"]["name"]), []).append(g)
    return {k: MultiLineString(v) for k, v in by.items()}


def fit_affine(src, dst, w=None):
    a = np.hstack([src, np.ones((len(src), 1))])
    if w is not None:
        a, dst = a * w[:, None], dst * w[:, None]
    m, *_ = np.linalg.lstsq(a, dst, rcond=None)
    return m  # 3×2: [x y 1] @ m


def apply(m, pts):
    return np.hstack([pts, np.ones((len(pts), 1))]) @ m


def register(labels, streets):
    """labels: [(name, (x, y))] на листе → аффинное преобразование в UTM и RMSE по подписям (м)."""
    pairs = [(n, p) for n, p in labels if n in streets]
    names = [n for n, _ in pairs]
    src = np.array([p for _, p in pairs])
    # начальное приближение: центр подписей улицы ↔ центр одноимённой улицы OSM, робастно по улицам
    uniq = sorted(set(names))
    s0 = np.array([src[[i for i, n in enumerate(names) if n == u]].mean(0) for u in uniq])
    d0 = np.array([[streets[u].centroid.x, streets[u].centroid.y] for u in uniq])
    keep = np.ones(len(uniq), bool)
    for _ in range(10):
        m = fit_affine(s0[keep], d0[keep])
        r = np.linalg.norm(apply(m, s0) - d0, axis=1)
        keep = r <= max(np.median(r[keep]) * 2.5, 50)
    # уточнение: подпись → ближайшая точка своей улицы
    groups = {u: np.array([i for i, n in enumerate(names) if n == u]) for u in uniq}

    def closest(cur):
        dst = np.empty_like(cur)
        for u, idx in groups.items():
            ln = shapely.shortest_line(streets[u], shapely.points(cur[idx]))
            dst[idx] = shapely.get_coordinates(ln).reshape(-1, 2, 2)[:, 0]
        return dst

    for it in range(40):
        cur = apply(m, src)
        dst = closest(cur)
        r = np.linalg.norm(cur - dst, axis=1)
        ok = r <= max(np.percentile(r, 80) * 2, 15 if it > 15 else 80)
        m = fit_affine(src[ok], dst[ok])
    cur = apply(m, src)
    r = np.linalg.norm(cur - closest(cur), axis=1)
    ok = r <= max(np.percentile(r, 80) * 2, 15)
    return m, float(np.sqrt(np.mean(r[ok] ** 2))), int(ok.sum()), len(r)


def page_objects(page):
    d = page.get_text("dict")
    lines = [(("".join(s["text"] for s in l["spans"])).strip(), l["spans"][0]["size"], l["bbox"])
             for b in d["blocks"] for l in b.get("lines", [])]
    spans = [(s["text"].strip(), s["bbox"]) for b in d["blocks"] for l in b.get("lines", []) for s in l["spans"]]
    street_labels = [(norm(t), ((bb[0] + bb[2]) / 2, (bb[1] + bb[3]) / 2)) for t, sz, bb in lines
                     if 4 < sz < 5 and t.isupper() and len(t) > 4]
    segs, chambers, consumers, leaders, big = [], [], [], [], []
    for dr in page.get_drawings():
        col, fill, w, r = dr.get("color"), dr.get("fill"), dr.get("width") or 0, dr["rect"]
        if col == (0.0, 0.0, 0.0) and abs(w - 0.96) < 0.01 and len(dr["items"]) > 100:
            segs += [((it[1].x, it[1].y), (it[2].x, it[2].y)) for it in dr["items"] if it[0] == "l"]
        elif fill == (1.0, 1.0, 0.0) and r.width < 5:
            chambers.append(((r.x0 + r.x1) / 2, (r.y0 + r.y1) / 2))
        elif fill == (1.0, 0.0, 0.0) and r.width < 8:
            consumers.append(((r.x0 + r.x1) / 2, (r.y0 + r.y1) / 2))
        elif col == (0.0, 0.0, 0.0) and abs(w - 0.24) < 0.01 and len(dr["items"]) == 1 and dr["items"][0][0] == "l":
            leaders.append(((dr["items"][0][1].x, dr["items"][0][1].y), (dr["items"][0][2].x, dr["items"][0][2].y)))
        elif fill == (0.0, 0.0, 0.0) and r.width > 30 and r.height > 20:
            big.append(((r.x0 + r.x1) / 2, (r.y0 + r.y1) / 2))  # значок ТЭЦ
    # подписи участков: «L n» + ниже «D»«вн»«n» + ниже «G n»
    ls = [(t, bb) for t, bb in spans if t.startswith("L ") and NUM.search(t)]
    vn = np.array([bb[:2] for t, bb in spans if t == "вн"] or np.empty((0, 2)))  # «D» «вн» «612»
    ds = [(float(t), bb) for t, bb in spans if re.fullmatch(r"\d+", t)]
    dxy = np.array([bb[:2] for _, bb in ds] or np.empty((0, 2)))
    gs = [(t, bb) for t, bb in spans if t.startswith("G ") and NUM.search(t)]
    gxy = np.array([bb[:2] for _, bb in gs] or np.empty((0, 2)))
    boxes = []
    for t, bb in ls:
        v = vn[(vn[:, 1] - bb[1] > 5) & (vn[:, 1] - bb[1] < 13) & (np.abs(vn[:, 0] - bb[0]) < 12)]
        if not len(v):
            continue
        k = np.flatnonzero((np.abs(dxy[:, 1] - v[0, 1]) < 2) & (dxy[:, 0] > v[0, 0]) & (dxy[:, 0] - v[0, 0] < 15))
        if not len(k):
            continue
        gk = np.flatnonzero((gxy[:, 1] - bb[1] > 13) & (gxy[:, 1] - bb[1] < 24) & (np.abs(gxy[:, 0] - bb[0]) < 3))
        boxes.append({"L": float(NUM.search(t).group().replace(",", ".")), "D": ds[k[0]][0],
                      "G": float(NUM.search(gs[gk[0]][0]).group().replace(",", ".")) if len(gk) else None,
                      "bbox": (bb[0] - 3, bb[1] - 3, bb[0] + 40, bb[1] + 30)})
    names = [(t, ((bb[0] + bb[2]) / 2, (bb[1] + bb[3]) / 2)) for t, bb in spans if re.fullmatch(r"к?\d+[\w/]*", t)]
    titles = [(t, ((bb[0] + bb[2]) / 2, (bb[1] + bb[3]) / 2)) for t, sz, bb in lines
              if sz > 15 and re.search(r"ТЭЦ|РТС|КТС|ГЭС|ТЭС", t)]
    near = lambda c: min(titles, key=lambda tc: Point(tc[1]).distance(Point(c)))
    big = [(near(c)[0], c) for c in big if titles and Point(near(c)[1]).distance(Point(c)) < 120]  # без легенды
    return street_labels, segs, chambers, consumers, leaders, boxes, big, names


def network(segs, chambers, leaders, boxes):
    """Цепочки между узлами, разрезанные по камерам; Dвн по выноскам подписей."""
    net = linemerge([LineString([(round(a[0], 1), round(a[1], 1)), (round(b[0], 1), round(b[1], 1))])
                     for a, b in segs if a != b])
    chains = list(net.geoms) if hasattr(net, "geoms") else [net]
    cham = [Point(c) for c in chambers]
    ctree = STRtree(cham)
    pieces = []
    for ch in chains:
        near = [cham[i] for i in ctree.query(ch.buffer(1.5))]
        cuts = [ch.interpolate(ch.project(c)) for c in near if 0.5 < ch.project(c) < ch.length - 0.5]
        parts = [ch]
        for c in cuts:
            parts = [g for p in parts for g in (split(p, c.buffer(0.01)).geoms if p.distance(c) < 0.02 else [p])
                     if g.length > 0.05]
        pieces += parts
    ptree = STRtree(pieces)
    hits = {}  # участок → [(положение вдоль участка, подпись)]
    ends = np.array([[a, c] for a, c in leaders] or np.empty((0, 2, 2)))  # n×2×2
    uniq = {(round(b["bbox"][0]), round(b["bbox"][1])): b for b in boxes}  # подписи рисуются дважды
    for b in uniq.values():
        x0, y0, x1, y1 = b["bbox"]
        inb = lambda q: (q[:, 0] >= x0 - 2) & (q[:, 0] <= x1 + 2) & (q[:, 1] >= y0 - 2) & (q[:, 1] <= y1 + 2)
        best = None
        for e in (0, 1):  # выноска: один конец у рамки подписи, другой снаружи — на трубе
            for far in ends[inb(ends[:, e]) & ~inb(ends[:, 1 - e]), 1 - e]:
                i = ptree.nearest(Point(far))
                d = pieces[i].distance(Point(far))
                if d < 2 and (best is None or d < best[0]):
                    best = (d, i, pieces[i].project(Point(far)))
        if best:
            hits.setdefault(best[1], []).append((best[2], b))
    # несколько подписей на одном участке — узлы без камер: режем посередине между точками выносок
    out, lab = [], {}
    for i, p in enumerate(pieces):
        h = []
        for sb in sorted(hits.get(i, []), key=lambda x: x[0]):  # дубли одной подписи (обводка текста)
            if not h or sb[1]["L"] != h[-1][1]["L"] or sb[1]["D"] != h[-1][1]["D"] or sb[0] - h[-1][0] > 10:
                h.append(sb)
        cuts = [0.0] + [(a[0] + b[0]) / 2 for a, b in zip(h, h[1:])] + [p.length]
        for k in range(max(len(h), 1)):
            seg = substring(p, cuts[k], cuts[k + 1]) if len(h) > 1 else p
            if h:
                lab[len(out)] = h[k][1]
            out.append(seg)
    pieces, ptree = out, STRtree(out)
    # участки без подписи: Dвн соседнего подписанного участка той же цепочки/узла, до 30 проходов
    dv = {i: b["D"] for i, b in lab.items()}
    for _ in range(30):
        new = {}
        for i, p in enumerate(pieces):
            if i in dv:
                continue
            nb = [dv[j] for j in ptree.query(p.buffer(0.3)) if j in dv and j != i]
            if nb:
                new[i] = min(nb)
        if not new:
            break
        dv.update(new)
    return pieces, lab, dv


def main(pdf, streets_path, out, pages, ref):
    streets = osm_streets(streets_path)
    doc = pymupdf.open(pdf)
    feats = []
    for pno in (int(x) for x in pages.split(",")):
        page = doc[pno - 1]
        sl, segs, chambers, consumers, leaders, boxes, big, names = page_objects(page)
        m, rmse, used, total = register(sl, streets)
        def to_geo(x, y, m=m):
            u = apply(m, np.column_stack([np.atleast_1d(x), np.atleast_1d(y)]))
            lon, lat = TO_WGS(u[:, 0], u[:, 1])
            return (lon, lat) if np.ndim(x) else (float(lon[0]), float(lat[0]))

        print(f"стр. {pno}: подписей улиц {total}, в привязке {used}, RMSE {rmse:.1f} м; отрезков {len(segs)}, "
              f"камер {len(chambers)}, подписей участков {len(boxes)}, выносок {len(leaders)}", file=sys.stderr)
        base = {"_source": "restored", "_ref": f"{ref}, стр. {pno}", "_georef_rmse_m": round(rmse, 1)}
        pieces, lab, dv = network(segs, chambers, leaders, boxes)
        for i, p in enumerate(pieces):
            g = transform(to_geo, p)
            pr = dict(base, _class="heat_network", _page=pno)
            if i in dv:
                pr.update(d_inner_mm=dv[i], diameter=dn_from_inner(dv[i]), _diameter_from="label" if i in lab else "neighbor")
            if i in lab:
                pr.update(label_length_m=lab[i]["L"], label_flow_tph=lab[i]["G"])
            feats.append({"type": "Feature", "geometry": mapping(g), "properties": pr})
        nt = STRtree([Point(c) for _, c in names]) if names else None
        for c in chambers:
            pr = dict(base, _class="heat_chamber", _page=pno)
            if nt is not None:
                j = nt.nearest(Point(c))
                if Point(names[j][1]).distance(Point(c)) < 25:
                    pr["name"] = names[j][0]
            feats.append({"type": "Feature", "geometry": mapping(Point(to_geo(*c))), "properties": pr})
        for c in consumers:
            feats.append({"type": "Feature", "geometry": mapping(Point(to_geo(*c))),
                          "properties": dict(base, _class="consumer", _page=pno)})
        for name, c in dict((round(c[0]), (n, c)) for n, c in big).values():  # значок рисуется дважды
            feats.append({"type": "Feature", "geometry": mapping(Point(to_geo(*c))),
                          "properties": dict(base, _class="source", _page=pno, name=name)})
    json.dump({"type": "FeatureCollection", "features": feats}, open(out, "w"), ensure_ascii=False, separators=(",", ":"))


if __name__ == "__main__":
    assert [dn_from_inner(d) for d in (100, 207, 309, 414, 514, 612, 898, 1200)] == [100, 200, 300, 400, 500, 600, 900, 1200]
    main(*sys.argv[1:6])
