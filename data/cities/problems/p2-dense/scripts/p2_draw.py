"""Картинка окрестности ОКС: здания (серые) и их зоны (розовые), запретные территории (зелёные), дороги (жёлтые),
линии (тонкие), сеть (красная), новая сеть из выхода (синяя), точка (чёрная). Запуск: p2_draw.py вход выход_сервиса id R png"""
import json, sys
from shapely.geometry import shape
from shapely.ops import transform
from pyproj import Transformer
from PIL import Image, ImageDraw

MAIN = "/Users/a1111/HACK/LCT/LCT/.claude/worktrees/geojson-heat-network-moscow-spb-ea030e"
src, out, oid, R, png = sys.argv[1], sys.argv[2], sys.argv[3], float(sys.argv[4]), sys.argv[5]
T = Transformer.from_crs(4326, 32637, always_xy=True).transform
rules = json.load(open(MAIN + "/rules/rules.json"))
G = [(f["properties"], transform(T, shape(f["geometry"]))) for f in json.load(open(src))["features"] if f["geometry"]]
O = []
if out != "-":
    O = [(f["properties"], transform(T, shape(f["geometry"]))) for f in json.load(open(out))["features"]
         if f["geometry"] and str(f["properties"].get("variant_id")) == "1"]
cpp, cp = next((p, g) for p, g in G if p.get("oks_id") == oid or (p["object_type"] == "oks_connection_point" and str(p["id"]) == oid))
flow = next((p["flow_tph"] for p, g in G if p.get("id") == oid and p["object_type"] == "oks_future"), cpp.get("flow_tph"))
d = next(d for d in rules["diameters"] if flow <= d["capacity_tph"])
zo = 5 + d["width_m"] / 2
S = 1000
k = S / (2 * R)
img = Image.new("RGB", (S, S), "white")
dr = ImageDraw.Draw(img, "RGBA")
box = cp.buffer(R).envelope


def xy(c):
    return [((x - cp.x + R) * k, (R - (y - cp.y)) * k) for x, y in c]


def poly(g, fill, outline=None):
    for p in getattr(g, "geoms", [g]):
        if p.geom_type == "Polygon":
            dr.polygon(xy(p.exterior.coords), fill=fill, outline=outline)
            for h in p.interiors:
                dr.polygon(xy(h.coords), fill=(255, 255, 255, 255))


def line(g, fill, w):
    for p in getattr(g, "geoms", [g]):
        if p.geom_type == "LineString":
            dr.line(xy(p.coords), fill=fill, width=w)


for p, g in G:
    if not g.intersects(box):
        continue
    t, r = p["object_type"], p.get("restriction_type")
    if t == "oks_existing" or r == "oks":
        poly(g.buffer(zo), (255, 0, 0, 40))
    elif r in ("social_area", "park", "prohibited_site", "water", "railway", "metro"):
        poly(g.buffer(1 + d["width_m"] / 2), (0, 160, 0, 50))
for p, g in G:
    if not g.intersects(box):
        continue
    t, r = p["object_type"], p.get("restriction_type")
    if r == "road" or r == "tram_tracks":
        poly(g, (230, 200, 0, 90), (180, 150, 0, 255))
    elif r in ("social_area", "park", "prohibited_site", "water", "railway", "metro"):
        poly(g, (0, 160, 0, 90), (0, 100, 0, 255))
    elif t == "oks_existing" or r == "oks":
        poly(g, (110, 110, 110, 255))
    elif t == "oks_future":
        poly(g, (60, 60, 200, 120), (0, 0, 150, 255))
    elif g.geom_type.endswith("LineString") and t == "restriction":
        line(g, (120, 120, 120, 160), 1)
    elif t == "heat_network":
        line(g, (220, 0, 0, 255), 3)
for p, g in O:
    if p["object_type"] == "heat_network" and g.intersects(box):
        line(g, (0, 80, 255, 255), 3)
x, y = xy([(cp.x, cp.y)])[0]
dr.ellipse([x - 5, y - 5, x + 5, y + 5], fill="black")
dr.text((10, 10), f"{oid} DN{d['dn']} zone {zo:.2f} m, R {R} m, 10 m = {10 * k:.0f} px", fill="black")
img.save(png)
