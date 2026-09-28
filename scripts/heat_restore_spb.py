"""Восстановление сети ТЭЦ-22 (Купчино) из «Схемы Фрунзенской и Московской магистралей ТЭЦ-22»
(схема теплоснабжения СПб, актуализация на 2021 г., Книга 3, Прил. А–В, стр. 431).

    uv run --project tools --with pymupdf python scripts/heat_restore_spb.py <pdf> <osm_cache.json> <out.geojson>

<osm_cache.json> — выгрузка OSM Купчино (ключи buildings/areas: ответы Overpass `out geom`).
Подписей улиц на листе нет, поэтому привязка по зданиям: начальное подобие по опорным точкам (ТЭЦ-22,
Бухарестская 89, Софийская 54, парк Интернационалистов), затем ICP центров контуров зданий листа ↔ центров
зданий OSM (сходной площади) с сужением порога 150 → 12 м, аффинное преобразование лист → UTM 36N.
Стиль листа: трубы — синие 2,83 pt (магистрали поверх — красная осевая 0,85 pt), узлы — тёмные кружки 0,5 pt,
вводы в здания — пурпурные кружки и жёлтые квадраты; Ду подписаны только у магистралей («D=1400» и т. п.).
"""
import json
import re
import sys

import numpy as np
import pymupdf
import shapely
from pyproj import Transformer
from shapely import STRtree
from shapely.geometry import LineString, Point, Polygon, mapping
from shapely.ops import linemerge, transform

from heat_restore_pdf import apply, dn_from_inner, fit_affine

TO_UTM = Transformer.from_crs(4326, 32636, always_xy=True).transform
TO_WGS = Transformer.from_crs(32636, 4326, always_xy=True).transform
BLUE, RED = (0.0, 0.33, 0.65), (1.0, 0.0, 0.0)
BUILDING_FILLS = {(1.0, 0.65, 0.32), (0.84, 0.49, 0.32)}
REF = ("Схема теплоснабжения Санкт-Петербурга до 2033 г. (актуализация на 2021 г.), Книга 3 Глава 3 «Электронная "
       "модель», Прил. А–В, «Схема Фрунзенской и Московской магистралей ТЭЦ-22», https://www.gov.spb.ru/static/"
       "writable/ckeditor/uploads/2020/10/20/34/Книга_3_Глава_3__Приложения_А-В.pdf")
PAGE = 431


def rnd(c):
    return tuple(round(x, 2) for x in c) if c else None


def subpaths(items):
    """Замкнутые контуры рисунка: разрыв там, где отрезок не продолжает предыдущий."""
    out, cur = [], []
    for it in items:
        if it[0] == "re":
            out.append([(p.x, p.y) for p in (it[1].tl, it[1].tr, it[1].br, it[1].bl)])
            continue
        if it[0] == "qu":
            out.append([(p.x, p.y) for p in (it[1].ul, it[1].ur, it[1].lr, it[1].ll)])
            continue
        a, b = it[1], it[-1]
        if cur and abs(cur[-1][0] - a.x) + abs(cur[-1][1] - a.y) > 0.01:
            out.append(cur)
            cur = []
        if not cur:
            cur.append((a.x, a.y))
        cur.append((b.x, b.y))
    if cur:
        out.append(cur)
    return [Polygon(c).buffer(0) for c in out if len(c) >= 3]


def page_objects(page):
    blds, blue, red, nodes, inputs = [], [], [], [], []
    for d in page.get_drawings():
        col, fill, w, r = rnd(d.get("color")), rnd(d.get("fill")), round(d.get("width") or 0, 2), d["rect"]
        c = ((r.x0 + r.x1) / 2, (r.y0 + r.y1) / 2)
        if fill in BUILDING_FILLS:
            blds += [g for g in subpaths(d["items"]) if g.area > 15]
        elif col == BLUE and w == 2.83:
            blue += [((it[1].x, it[1].y), (it[-1].x, it[-1].y)) for it in d["items"] if it[0] == "l"]
        elif col == RED and w == 0.85:
            red += [((it[1].x, it[1].y), (it[-1].x, it[-1].y)) for it in d["items"] if it[0] == "l"]
        elif fill == (0.33, 0.33, 0.33) and r.width < 1:
            nodes.append(c)
        elif (fill == (0.99, 0.22, 1.0) or fill == (1.0, 1.0, 0.0)) and r.width < 2:
            inputs.append(c)
    text = {}
    for b in page.get_text("dict")["blocks"]:
        for l in b.get("lines", []):
            t = "".join(s["text"] for s in l["spans"]).strip()
            bb = l["bbox"]
            text.setdefault(t, []).append(((bb[0] + bb[2]) / 2, (bb[1] + bb[3]) / 2))
    return blds, blue, red, nodes, inputs, text


def osm(cache):
    d = json.load(open(cache))

    def poly(e):
        if e["type"] == "relation":  # мультиполигон: внешние контуры
            rings = [LineString([(p["lon"], p["lat"]) for p in m["geometry"]]) for m in e.get("members", [])
                     if m.get("role") == "outer" and len(m.get("geometry", [])) > 1]
            ps = list(shapely.polygonize(rings).geoms) if rings else []
            return transform(TO_UTM, shapely.union_all(ps)).buffer(0) if ps else None
        if len(e.get("geometry", [])) < 4:
            return None
        return transform(TO_UTM, Polygon([(p["lon"], p["lat"]) for p in e["geometry"]])).buffer(0)

    blds, addr = [], {}
    for e in d["buildings"]["elements"]:
        g = poly(e)
        if g is None or g.is_empty:
            continue
        blds.append(g)
        t = e.get("tags", {})
        if "addr:street" in t:
            addr[(t["addr:street"], t.get("addr:housenumber", ""))] = g.centroid
    areas = {}
    for e in d["areas"]["elements"]:
        n = e.get("tags", {}).get("name")
        if n and e["type"] == "relation":
            pts = [(p["lon"], p["lat"]) for m in e.get("members", []) for p in m.get("geometry", [])]
            if pts:
                areas[n] = transform(TO_UTM, shapely.MultiPoint(pts)).convex_hull.centroid
    return blds, addr, areas


def register(pbl, obl, anchors):
    src = np.array([a for a, _ in anchors])
    dst = np.array([[b.x, b.y] for _, b in anchors])
    # начальное подобие (4 параметра): x' = a x - b y + tx, y' = b x + a y + ty (с учётом отражения листа)
    best = None
    for flip in (1, -1):
        s = src * [1, flip]
        a_ = np.array([[x, -y, 1, 0] for x, y in s] + [[y, x, 0, 1] for x, y in s])
        p, *_ = np.linalg.lstsq(a_, np.concatenate([dst[:, 0], dst[:, 1]]), rcond=None)
        r = np.linalg.norm(a_ @ p - np.concatenate([dst[:, 0], dst[:, 1]]))
        if best is None or r < best[0]:
            best = (r, flip, p)
    _, flip, (a, b, tx, ty) = best
    m = np.array([[a, b], [-b * flip, a * flip], [tx, ty]])
    pc = np.array([[g.centroid.x, g.centroid.y] for g in pbl])
    pa = np.array([g.area for g in pbl])
    oc = shapely.points([[g.centroid.x, g.centroid.y] for g in obl])
    oa = np.array([g.area for g in obl])
    tree = STRtree(oc)
    for gate in [150, 120, 90, 60, 45, 30, 20, 15, 12, 12, 12]:
        cur = apply(m, pc)
        scale = abs(np.linalg.det(m[:2]))
        idx = tree.nearest(shapely.points(cur))
        dd = shapely.distance(shapely.points(cur), oc[idx])
        ratio = pa * scale / oa[idx]
        ok = (dd < gate) & (ratio > 0.5) & (ratio < 2)
        tgt = shapely.get_coordinates(oc[idx])
        m = fit_affine(pc[ok], tgt[ok])
    cur = apply(m, pc)
    idx = tree.nearest(shapely.points(cur))
    dd = shapely.distance(shapely.points(cur), oc[idx])
    ratio = pa * abs(np.linalg.det(m[:2])) / oa[idx]
    ok = (dd < 12) & (ratio > 0.5) & (ratio < 2)
    anc = np.linalg.norm(apply(m, src) - dst, axis=1)
    return m, float(np.sqrt(np.mean(dd[ok] ** 2))), int(ok.sum()), len(pc), anc


def main(pdf, cache, out):
    page = pymupdf.open(pdf)[PAGE - 1]
    pbl, blue, red, nodes, inputs, text = page_objects(page)
    obl, addr, areas = osm(cache)
    anchors = [(text["ТЭЦ-22"][0], areas["Южная ТЭЦ-22"]),
               (text["Бухарестская,89"][0], addr[("Бухарестская улица", "89")]),
               (text["ул. Софийская, 54 корп.2"][0], addr[("Софийская улица", "54")]),
               (text["Парк Интернационалистов"][0], areas["парк Интернационалистов"])]
    m, rmse, used, total, anc = register(pbl, obl, anchors)
    print(f"зданий листа {total}, в привязке {used}, RMSE {rmse:.1f} м; невязки опорных точек, м: "
          f"{', '.join(f'{x:.0f}' for x in anc)}", file=sys.stderr)

    def to_geo(x, y):
        u = apply(m, np.column_stack([np.atleast_1d(x), np.atleast_1d(y)]))
        lon, lat = TO_WGS(u[:, 0], u[:, 1])
        return (lon, lat) if np.ndim(x) else (float(lon[0]), float(lat[0]))

    base = {"_source": "restored", "_ref": f"{REF}, стр. {PAGE}", "_page": PAGE, "_georef_rmse_m": round(rmse, 1)}
    lines = lambda segs: [LineString([(round(a[0], 1), round(a[1], 1)), (round(b[0], 1), round(b[1], 1))])
                          for a, b in segs if a != b]
    net = linemerge(lines(blue))
    chains = list(net.geoms) if hasattr(net, "geoms") else [net]
    red_u = shapely.union_all(lines(red)).buffer(1.5)
    dl = [(float(re.sub(r"\D", "", t)), c) for t, cs in text.items() if t.startswith("D=") for c in cs]
    feats = []
    for ch in chains:
        mag = ch.intersection(red_u).length > 0.6 * ch.length
        pr = dict(base, _class="heat_network", _kind="магистраль" if mag else "распределительная")
        near = [dc for dc in dl if ch.distance(Point(dc[1])) < 60]  # ≈ 200 м: подпись относится к этой трассе
        if mag and near:
            d_label = min(near, key=lambda dc: ch.distance(Point(dc[1])))[0]
            pr.update(diameter=dn_from_inner(d_label), d_label_mm=d_label, _diameter_from="label")  # D=1400 и т. п. — уже Ду
        else:
            pr["_diameter_from"] = "none"
        feats.append({"type": "Feature", "geometry": mapping(transform(to_geo, ch)), "properties": pr})
    for cls, pts in (("heat_chamber", nodes), ("consumer", inputs)):
        for c in dict.fromkeys((round(x, 1), round(y, 1)) for x, y in pts):  # символы рисуются дважды
            feats.append({"type": "Feature", "geometry": mapping(Point(to_geo(*c))), "properties": dict(base, _class=cls)})
    # источник — конец магистрали у значка ТЭЦ-22 (подпись стоит рядом со значком)
    tl = Point(text["ТЭЦ-22"][0])
    ends = [Point(q) for ch in chains if ch.intersection(red_u).length > 0.6 * ch.length for q in (ch.coords[0], ch.coords[-1])]
    c = min(ends, key=tl.distance)
    feats.append({"type": "Feature", "geometry": mapping(Point(to_geo(c.x, c.y))),
                  "properties": dict(base, _class="source", name="Южная ТЭЦ-22 (начало магистралей на схеме)")})
    json.dump({"type": "FeatureCollection", "features": feats}, open(out, "w"), ensure_ascii=False, separators=(",", ":"))


if __name__ == "__main__":
    main(*sys.argv[1:4])
