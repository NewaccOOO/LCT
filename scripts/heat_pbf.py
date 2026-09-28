"""Региональная выгрузка OSM (.osm.pbf) -> ответы «как у Overpass» для города, когда Overpass недоступен.

    uv run --project tools --with osmium python scripts/heat_pbf.py <pbf> <город> <relation городского округа> \
        <admin_level районов> <out_dir>

Пишет в <out_dir> (формат `out geom` Overpass, дальше их читает scripts/heat_osm.py без изменений):
q1_<город>.json — все теплосетевые объекты города (отбор по heat_osm.classify), q2_<город>.json — пусто,
adm_<город>.json — районы (boundary=administrative, admin_level, центр внутри городского округа),
streets_<город>.json — улицы с названиями (для scripts/heat_restore_pdf.py),
addr_<город>.json — здания с адресом точками (привязка источников и подписей схем по адресу).
"""
import json
import sys
from pathlib import Path

import osmium
from shapely.geometry import Point, box
from shapely.prepared import prep

sys.path.insert(0, str(Path(__file__).parent))
from heat_osm import classify, rel_geom  # noqa: E402

# объекты, у которых «ТЭЦ/котельная» в названии — остановки, улицы, магазины, а не источники
NOT_OBJECT = {"highway", "public_transport", "railway", "shop", "office", "place", "route", "waterway", "natural",
              "leisure", "tourism", "barrier", "entrance", "craft"}


def wanted(t):
    cls = classify(t)
    if cls is None:
        return None
    if cls in ("heat_source", "heat_substation") and classify({k: v for k, v in t.items() if k != "name"}) is None:
        return None if NOT_OBJECT & t.keys() else cls  # найден только по названию
    return cls


def pts(w):
    return [{"lat": n.lat, "lon": n.lon} for n in w.nodes if n.location.valid()]


def main(pbf, city, city_rel, level, out):
    city_rel, out = int(city_rel), Path(out)
    rels, need = {}, set()  # relation id -> (tags, [(role, way id)])
    for r in osmium.FileProcessor(pbf, osmium.osm.RELATION):
        t = dict(r.tags)
        adm = t.get("boundary") == "administrative" and (r.id == city_rel or t.get("admin_level") == level)
        if adm or (t.get("type") == "multipolygon" and wanted(t)):
            ms = [(m.role, m.ref) for m in r.members if m.type == "w"]
            rels[r.id] = (t, ms)
            need.update(ref for _, ref in ms)
    ways, heat, streets, addr = {}, [], [], []
    for o in osmium.FileProcessor(pbf, osmium.osm.NODE | osmium.osm.WAY).with_locations():
        t = dict(o.tags)
        if o.is_node():
            if wanted(t):
                heat.append({"type": "node", "id": o.id, "lat": o.lat, "lon": o.lon, "tags": t})
            elif "building" in t and "addr:housenumber" in t:
                addr.append({"type": "node", "id": o.id, "lat": o.lat, "lon": o.lon, "tags": t})
            continue
        if o.id in need:
            ways[o.id] = pts(o)
        if not t:
            continue
        if wanted(t):
            heat.append({"type": "way", "id": o.id, "tags": t, "geometry": pts(o)})
        elif "highway" in t and "name" in t:
            streets.append({"type": "way", "id": o.id, "tags": {k: t[k] for k in ("highway", "name")},"geometry": pts(o)})
        elif "building" in t and "addr:housenumber" in t:
            g = pts(o)
            if g:
                addr.append({"type": "node", "id": o.id, "_way": True, "tags": t,
                             "lat": sum(p["lat"] for p in g) / len(g), "lon": sum(p["lon"] for p in g) / len(g)})
    rel_el = {i: {"type": "relation", "id": i, "tags": t,
                  "members": [{"type": "way", "ref": ref, "role": role, "geometry": ways.get(ref, [])} for role, ref in ms]}
              for i, (t, ms) in rels.items()}
    city_g = rel_geom(rel_el[city_rel])
    inside = prep(city_g)
    bb = prep(box(*city_g.buffer(0.01).bounds))
    adm = [e for i, e in rel_el.items() if i != city_rel and e["tags"].get("boundary") == "administrative"
           and (g := rel_geom(e)) is not None and inside.contains(g.representative_point())]
    heat += [e for e in rel_el.values() if e["tags"].get("boundary") != "administrative"]

    def rep(e):
        if "lat" in e:
            return Point(e["lon"], e["lat"])
        g = e.get("geometry") or [p for m in e.get("members", []) for p in m["geometry"]]
        return Point(sum(p["lon"] for p in g) / len(g), sum(p["lat"] for p in g) / len(g)) if g else None

    heat = [e for e in heat if (p := rep(e)) is not None and inside.contains(p)]
    streets = [e for e in streets if e["geometry"] and bb.contains(Point(e["geometry"][0]["lon"], e["geometry"][0]["lat"]))]
    addr = [e for e in addr if inside.contains(Point(e["lon"], e["lat"]))]
    out.mkdir(parents=True, exist_ok=True)
    for name, els in (("q1", heat), ("q2", []), ("adm", adm), ("streets", streets), ("addr", addr),
                      ("city", [rel_el[city_rel]])):
        (out / f"{name}_{city}.json").write_text(json.dumps({"elements": els}, ensure_ascii=False, separators=(",", ":")))
        print(f"{name}_{city}.json: {len(els)}", file=sys.stderr)


if __name__ == "__main__":
    main(*sys.argv[1:6])
