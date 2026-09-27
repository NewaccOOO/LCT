"""СПб v1 с тремя источниками: ТЭЦ-22 + две реальные котельные OSM Купчино, их сети отрезаны от сети ТЭЦ.

Выход: multi.geojson (3 source) и single.geojson (тот же вход, только ТЭЦ-22). Сеть и камеры без
upstream_object_id: сервис направляет их обходом от источников.
"""
import json
import sys
from pyproj import Transformer
from shapely.geometry import shape, Point, LineString
from shapely.ops import transform

M = "/Users/a1111/HACK/LCT/LCT/.claude/worktrees/geojson-heat-network-moscow-spb-ea030e"
OUT = sys.argv[1]
R = 400.0          # радиус района котельной, м
JOINT = 0.5
BOILERS = ["way/514166144", "way/527604531"]

fwd = Transformer.from_crs(4326, 32637, always_xy=True).transform
inv = Transformer.from_crs(32637, 4326, always_xy=True).transform
data = json.load(open(M + "/data/cities/runs/spb-v1/input.geojson"))
heat = json.load(open(M + "/data/cities/raw/spb/osm_heat.geojson"))
boilers = {f["properties"]["_osm_id"]: f for f in heat["features"] if f["properties"].get("_osm_id") in BOILERS}

feats = data["features"]
utm = {}
for i, f in enumerate(feats):
    t = f["properties"]["object_type"]
    if t in ("heat_network", "heat_chamber", "oks_connection_point"):
        utm[i] = transform(fwd, shape(f["geometry"]))

drop = set()
new_sources = []
for osm in BOILERS:
    b = boilers[osm]
    c = transform(fwd, shape(b["geometry"]).centroid)
    circle = c.buffer(R).boundary
    disk = c.buffer(R)
    inside = [i for i, g in utm.items() if feats[i]["properties"]["object_type"] == "heat_network" and disk.contains(g)]
    cut = [i for i, g in utm.items() if feats[i]["properties"]["object_type"] == "heat_network" and g.intersects(circle)]
    drop.update(cut)
    ends = [(Point(utm[i].coords[k]), i) for i in inside for k in (0, -1)]
    at, _ = min(ends, key=lambda e: e[0].distance(c))
    oks = sum(1 for i, g in utm.items() if feats[i]["properties"]["object_type"] == "oks_connection_point" and disk.contains(g))
    p = b["properties"]
    lon, lat = inv(at.x, at.y)
    new_sources.append({"type": "Feature", "geometry": {"type": "Point", "coordinates": [round(lon, 9), round(lat, 9)]},
                        "properties": {"id": "src-" + osm.replace("/", "-"), "object_type": "source",
                                       "name": p.get("name", "Котельная"), "operator": p.get("operator"),
                                       "_source": "real", "_osm": osm,
                                       "_snap_m": round(at.distance(c), 1)}})
    print(osm, p.get("name"), p.get("operator"), "inside", len(inside), "cut", len(cut), "snap_m",
          round(at.distance(c), 1), "oks_in_disk", oks)

kept_ends = [Point(utm[i].coords[k]) for i in utm if feats[i]["properties"]["object_type"] == "heat_network"
             and i not in drop for k in (0, -1)]
from shapely.strtree import STRtree
tree = STRtree(kept_ends)
lonely = set()
for i, g in utm.items():
    if feats[i]["properties"]["object_type"] == "heat_chamber" and len(tree.query(g.buffer(JOINT))) == 0:
        # камера на середине трубы тоже в сети: проверяем расстояние до оставшихся линий
        lonely.add(i)
lines = [utm[i] for i in utm if feats[i]["properties"]["object_type"] == "heat_network" and i not in drop]
ltree = STRtree(lines)
lonely = {i for i in lonely if len(ltree.query(utm[i].buffer(JOINT), predicate="intersects")) == 0}
print("dropped segments", len(drop), "dropped lonely chambers", len(lonely))

out = []
for i, f in enumerate(feats):
    if i in drop or i in lonely:
        continue
    if f["properties"]["object_type"] in ("heat_network", "heat_chamber"):
        f["properties"].pop("upstream_object_id", None)
    out.append(f)
single = {"type": "FeatureCollection", "features": out}
json.dump(single, open(OUT + "/single.geojson", "w"), ensure_ascii=False)
multi = {"type": "FeatureCollection", "features": out[:1] + new_sources + out[1:]}
assert out[0]["properties"]["object_type"] == "source"
json.dump(multi, open(OUT + "/multi.geojson", "w"), ensure_ascii=False)
print("written", len(out), len(out) + len(new_sources))
