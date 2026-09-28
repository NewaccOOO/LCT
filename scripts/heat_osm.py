"""Теплосетевые объекты OSM города: сырой ответ Overpass -> GeoJSON EPSG:4326 + сводка по районам.

    uv run --project tools python scripts/heat_osm.py <город> <overpass_dir>

<overpass_dir> содержит ответы Overpass (запросы в data/cities/sources.md, раздел OSM):
q1_<город>.json (трубопроводы, люки, тепловые пункты), q2_<город>.json (источники тепла),
adm_<город>.json (административные районы). Пишет data/cities/raw/<город>/osm_heat.geojson,
osm_pipeline_untagged.geojson, osm_heat_sources.geojson (источники точками) и печатает сводку по районам в markdown.
"""
import json
import re
import sys
from collections import defaultdict
from pathlib import Path

from pyproj import Geod
from shapely.geometry import LineString, Point, Polygon, mapping, shape
from shapely.ops import linemerge, polygonize, unary_union

GEOD = Geod(ellps="WGS84")
HEAT = re.compile(r"hot_water|heat|steam")
NAME_SUB = re.compile(r"ЦТП|ИТП|[Тт]епловой пункт")
NAME_SRC = re.compile(r"[Кк]отельн(?!иков)|ТЭЦ|РТС|КТС|[Тт]еплоцентрал|[Тт]еплоэлектро|[Тт]епловая станция")
NAME_NOT = re.compile(r"[Пп]роходная|[Нн]асосная|[Пп]руды|[Пп]роект|[Уу]лица|[Пп]роезд|[Мм]агазин|[Мм]азутн")
AREA_KEYS =("building", "power", "landuse", "man_made", "industrial", "amenity", "utility")


def classify(t):
    mm, sub = t.get("man_made", ""), t.get("substation", "")
    if mm == "pipeline":
        return "heat_pipe" if HEAT.search(t.get("substance", "") + t.get("type", "")) else (
            "pipe_untagged" if "substance" not in t and "type" not in t else None)
    if "manhole" in t and "heat" in t["manhole"]:
        return "heat_manhole"
    if mm == "heat_exchange_station" or "heat" in sub or t.get("utility", "").startswith("heat"):
        return "heat_substation"
    if t.get("power") == "plant":
        return None if t.get("plant:source") in ("hydro", "solar", "waste", "diesel", "wind") else "heat_source"
    if (t.get("industrial", "") in ("heating_station", "boiler_house") or "boiler" in mm
            or "boiler" in t.get("building", "") or "plant:output:hot_water" in t):
        return "heat_source"
    name = t.get("name", "")
    if "power" in t or "route" in t or NAME_NOT.search(name):  # ЛЭП «ТЭЦ-21 — Бутаково», проходные, насосные
        return None
    if NAME_SUB.search(name):
        return "heat_substation"
    if NAME_SRC.search(name):
        return "heat_source"
    return None


def way_geom(e, as_area):
    pts = [(p["lon"], p["lat"]) for p in e["geometry"]]
    if as_area and len(pts) >= 4 and pts[0] == pts[-1]:
        return Polygon(pts)
    return LineString(pts)


def rel_geom(e):
    lines = [LineString([(p["lon"], p["lat"]) for p in m["geometry"]])
             for m in e.get("members", []) if m["type"] == "way" and m.get("geometry")]
    polys = [p.buffer(0) for p in polygonize(linemerge(lines))] if lines else []
    if not polys:
        return None
    outer = [p for p in polys if not any(p.within(q) for q in polys if q is not p)]
    holes = [p for p in polys if p not in outer]
    g = unary_union(outer).difference(unary_union(holes)) if holes else unary_union(outer)
    return g if g.is_valid else g.buffer(0)


def geom(e, t):
    if e["type"] == "node":
        return Point(e["lon"], e["lat"])
    if e["type"] == "way":
        return way_geom(e, t.get("man_made") != "pipeline" and any(k in t for k in AREA_KEYS))
    return rel_geom(e)


def districts(path):
    out = []
    for e in json.load(open(path))["elements"]:
        g = rel_geom(e)
        if g is not None and not g.is_empty:
            out.append((e["tags"].get("name", str(e["id"])), g))
    return out


def km(g):
    return GEOD.geometry_length(g) / 1000


def main(city, ov):
    ov = Path(ov)
    els, seen = [], set()
    for q in ("q1", "q2"):
        for e in json.load(open(ov / f"{q}_{city}.json"))["elements"]:
            if (e["type"], e["id"]) not in seen:
                seen.add((e["type"], e["id"]))
                els.append(e)
    dist = districts(ov / f"adm_{city}.json")
    feats = {"real": [], "untagged": []}
    stats = defaultdict(lambda: defaultdict(float))
    names = defaultdict(list)
    for e in els:
        t = e.get("tags", {})
        cls = classify(t)
        if cls is None:
            continue
        g = geom(e, t)
        if g is None or g.is_empty:
            continue
        if not g.is_valid:
            g = g.buffer(0)
        rep = g.representative_point()
        dn = next((n for n, d in dist if d.contains(rep)), "вне районов")
        props = dict(t)
        props.update({"_source": "real" if cls != "pipe_untagged" else "candidate", "_class": cls,
                      "_osm_id": f"{e['type']}/{e['id']}", "_district": dn})
        if cls in ("heat_pipe", "pipe_untagged"):
            props["_length_km"] = round(km(g), 3)
            for n, d in dist:
                if d.intersects(g):
                    stats[n][cls + "_km"] += km(g.intersection(d))
        else:
            stats[dn][cls] += 1
            if cls == "heat_source" and t.get("name"):
                names[dn].append(t["name"])
        feats["untagged" if cls == "pipe_untagged" else "real"].append(
            {"type": "Feature", "geometry": mapping(g), "properties": props})
    out = Path("data/cities/raw") / city
    out.mkdir(parents=True, exist_ok=True)
    # все источники тепла точками (центр контура) — для выбора главного источника и сверки со схемой теплоснабжения
    feats["sources"] = [{"type": "Feature", "geometry": mapping(shape(f["geometry"]).representative_point()),
                         "properties": f["properties"]} for f in feats["real"] if f["properties"]["_class"] == "heat_source"]
    for key, fn in (("real", "osm_heat.geojson"), ("untagged", "osm_pipeline_untagged.geojson"),
                    ("sources", "osm_heat_sources.geojson")):
        fc = {"type": "FeatureCollection", "features": sorted(feats[key], key=lambda f: f["properties"]["_osm_id"])}
        (out / fn).write_text(json.dumps(fc, ensure_ascii=False, separators=(",", ":")))
    cols = ("heat_pipe_km", "pipe_untagged_km", "heat_manhole", "heat_substation", "heat_source")
    print(f"| район | км труб тепло | км труб без substance | люки | тепл. пункты | источники | названия источников |")
    print("|---|---|---|---|---|---|---|")
    tot = defaultdict(float)
    for n in sorted(stats, key=lambda n: -(stats[n]["heat_pipe_km"] * 10 + stats[n]["heat_manhole"])):
        s = stats[n]
        for c in cols:
            tot[c] += s[c]
        print(f"| {n} | {s['heat_pipe_km']:.1f} | {s['pipe_untagged_km']:.1f} | {int(s['heat_manhole'])} | "
              f"{int(s['heat_substation'])} | {int(s['heat_source'])} | {', '.join(sorted(set(names[n]))[:6])} |")
    print(f"| **итого** | {tot['heat_pipe_km']:.1f} | {tot['pipe_untagged_km']:.1f} | {int(tot['heat_manhole'])} | "
          f"{int(tot['heat_substation'])} | {int(tot['heat_source'])} | |")


if __name__ == "__main__":
    main(*sys.argv[1:3])
