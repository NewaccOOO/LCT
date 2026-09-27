"""Входной GeoJSON сервиса по реальной карте города: OSM (Overpass) + достраивание по нормативам.

Запуск из корня:
    uv run --project tools python scripts/city_geojson.py build moscow [--bbox S,W,N,E]
    uv run --project tools python scripts/city_geojson.py check moscow
"""
import argparse
import hashlib
import json
import math
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import defaultdict
from pathlib import Path

import numpy as np
import shapely
import shapely.affinity
import shapely.ops
from pyproj import Transformer
from shapely import STRtree
from shapely.geometry import LineString, MultiLineString, MultiPolygon, Point, Polygon, box
from shapely.ops import polygonize, unary_union

ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT / "data" / "cities" / "cache"
UA = "lct-heatnet-city/0.1"
ENDPOINTS = (
    "https://z.overpass-api.de/api/interpreter",
    "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    "https://z.overpass-api.de/api/interpreter",
    "https://overpass-api.de/api/interpreter",
    "https://z.overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
)
MARGIN_M = 300.0

# установленная тепловая мощность: ТЭЦ-16 — 1422,2 Гкал/ч (ПАО «Мосэнерго», mosenergo.gazprom.ru),
# Южная ТЭЦ-22 — 2353,0 Гкал/ч (ПАО «ТГК-1», tgc1.ru). Шаг камер на магистралях: ТЭЦ-16 — 1674 камеры на 357,9 км
# трассы, ≈ 210 м (Схема теплоснабжения Москвы до 2035, актуализация 2026, книга 1.3); ТЭЦ-22 — 221 камера на
# 66,5 км магистралей, ≈ 300 м (схема теплоснабжения Санкт-Петербурга, data/cities/sources.md)
CITIES = {
    "moscow": dict(bbox=(55.760, 37.430, 55.820, 37.600), plant_name="ТЭЦ-16", plant_hint=(55.782, 37.503),
                   plant_gcal_h=1422.2, min_future=501, chamber_step_m=210.0, bridge_m=1.0),
    "spb": dict(bbox=(59.815, 30.380, 59.862, 30.470), plant_name="ТЭЦ-22", plant_hint=(59.825, 30.455),
                plant_gcal_h=2353.0, min_future=50, chamber_step_m=300.0, bridge_m=12.0),
    # Нижний Новгород целиком: 8 районов (admin_level 9 в relation 335495) без Кстовского района, который OSM с 2025 г.
    # включает в городской округ, а схема теплоснабжения города не покрывает. Сеть — граф схемы (scripts/heat_embed_nn.py),
    # источники — все из Гл. 4 схемы с координатами (вариант а), --one-source — одна Сормовская ТЭЦ (вариант б).
    # Камера на 221 м трассы — вся сеть АО «Теплоэнерго» (5 475 ТК на 1 212 км, data/cities/sources_nnovgorod.md).
    "nnovgorod": dict(bbox=(56.132, 43.5475, 56.422, 44.1548), epsg=32638, districts="nnovgorod/osm/adm_nnovgorod.json",
                      plant_name="Сормовская ТЭЦ", plant_hint=(56.3529, 43.8911), plant_gcal_h=696.0, min_future=501,
                      chamber_step_m=221.0, bridge_m=1.0, png_m_per_px=8.0),
}


def cache_path(query):
    return CACHE / f"{hashlib.sha256(query.encode()).hexdigest()[:24]}.json"


def overpass(query, attempts=60):
    """Ответ Overpass из кэша data/cities/cache/<sha256>.json; без кэша — сервер и зеркала по кругу с паузой.
    None — если за attempts попыток никто не ответил."""
    path = cache_path(query)
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8"))
    body = urllib.parse.urlencode({"data": query}).encode()
    for attempt in range(attempts):
        url = ENDPOINTS[attempt % len(ENDPOINTS)]
        try:
            request = urllib.request.Request(url, data=body, headers={"User-Agent": UA})
            with urllib.request.urlopen(request, timeout=100) as response:
                raw = response.read()
            data = json.loads(raw)
            if "remark" in data and "error" in data["remark"].lower():
                raise ValueError(data["remark"][:200])
        except (urllib.error.URLError, TimeoutError, ValueError, OSError) as error:
            print(f"overpass {url}: {error}; повтор", file=sys.stderr, flush=True)
            time.sleep(min(20, 2 * (attempt + 1)))
            continue
        CACHE.mkdir(parents=True, exist_ok=True)
        path.write_bytes(raw)
        print(f"overpass {url}: {len(data['elements'])} объектов", file=sys.stderr, flush=True)
        return data
    return None


def fetch_tile(template, tile, depth=0):
    """Элементы плитки; если зеркала не отвечают, плитка делится на четыре. Деление записывается в кэш меткой,
    и повторный запуск идёт по тем же плиткам из кэша."""
    s, w, n, e = tile
    query = "[out:json][timeout:180];" + template.format(b="{:.6f},{:.6f},{:.6f},{:.6f}".format(*tile))
    marker = cache_path(query).with_suffix(".split")
    if not marker.exists():
        data = overpass(query, attempts=8 if depth < 3 else 60)
        if data is not None:
            return data["elements"]
        if depth >= 3:
            sys.exit("Overpass не ответил ни с одного зеркала")
        CACHE.mkdir(parents=True, exist_ok=True)
        marker.write_text("split\n")
    ms, mw = (s + n) / 2, (w + e) / 2
    parts = ((s, w, ms, mw), (s, mw, ms, e), (ms, w, n, mw), (ms, mw, n, e))
    return [el for part in parts for el in fetch_tile(template, part, depth + 1)]


def padded(bbox, margin_m=MARGIN_M):
    s, w, n, e = bbox
    dlat = margin_m / 111_000
    dlon = margin_m / (111_000 * math.cos(math.radians((s + n) / 2)))
    return s - dlat, w - dlon, n + dlat, e + dlon


def queries(bbox):
    """Запросы Overpass по группам слоёв, {b} — bbox плитки. Источники тепла ищутся одним запросом с запасом 3 км."""
    return {
        "buildings": """(way["building"]({b});relation["building"]["type"="multipolygon"]({b});
way["demolished:building"]({b});way["razed:building"]({b});way["was:building"]({b}););out geom;""",
        "highways": """(way["highway"]({b});way["area:highway"]({b});relation["area:highway"]({b}););out geom;""",
        "rail": """(way["railway"]({b}););out geom;""",
        "areas": """(way["landuse"]({b});relation["landuse"]["type"="multipolygon"]({b});
way["leisure"]({b});relation["leisure"]["type"="multipolygon"]({b});
way["natural"]({b});relation["natural"]["type"="multipolygon"]({b});
way["waterway"]({b});way["water"]({b});
way["amenity"]({b});relation["amenity"]["type"="multipolygon"]({b});
way["military"]({b});relation["military"]({b});
way["power"~"^(plant|substation)$"]({b});relation["power"~"^(plant|substation)$"]({b});
way["healthcare"]({b}););out geom;""",
        "parts": """(way["building:part"]({b});relation["building:part"]["type"="multipolygon"]({b}););out geom;""",
        "utilities": """(node["power"~"^(tower|pole|portal)$"]({b});way["power"~"^(line|minor_line|cable)$"]({b});
way["man_made"="pipeline"]({b});node["manhole"]({b});node["man_made"~"^(manhole|pipeline_marker)$"]({b});
node["pipeline"]({b});way["substance"]({b});way["utility"]({b}););out geom;""",
    }


TILES = 3


PBF = {"moscow": "moscow-latest.osm.pbf", "spb": "saint_petersburg-latest.osm.pbf",
       "nnovgorod": "nnovgorod/nizhny_novgorod_oblast-latest.osm.pbf"}
# выгрузки OSM: https://download.openstreetmap.fr/extracts/russia/ (central_federal_district/moscow-latest.osm.pbf,
# northwestern_federal_district/saint_petersburg-latest.osm.pbf), кладутся в data/cities/cache/


def group_of(kind, tags):
    """Группы слоёв как в запросах Overpass (queries): по типу объекта OSM и тегам."""
    groups = []
    mp = kind == "relation" and tags.get("type") == "multipolygon"
    if (kind == "way" or mp) and "building" in tags or kind == "way" and any(
            k in tags for k in ("demolished:building", "razed:building", "was:building")):
        groups.append("buildings")
    if (kind == "way" or mp) and "building:part" in tags:
        groups.append("parts")
    if kind == "way" and ("highway" in tags or "area:highway" in tags) or kind == "relation" and "area:highway" in tags:
        groups.append("highways")
    if kind == "way" and "railway" in tags:
        groups.append("rail")
    area_keys = ("landuse", "leisure", "natural", "amenity")
    if (kind == "way" and any(k in tags for k in area_keys + ("waterway", "water", "military", "healthcare"))
            or mp and any(k in tags for k in area_keys)
            or kind != "node" and (tags.get("military") or tags.get("power") in ("plant", "substation"))):
        groups.append("areas")
    if (kind == "node" and (tags.get("power") in ("tower", "pole", "portal") or "manhole" in tags or "pipeline" in tags
                            or tags.get("man_made") in ("manhole", "pipeline_marker"))
            or kind == "way" and (tags.get("power") in ("line", "minor_line", "cable") or tags.get("man_made") == "pipeline"
                                  or "substance" in tags or "utility" in tags)):
        groups.append("utilities")
    return groups


def from_pbf(city, bbox):
    """Слои из выгрузки OSM (openstreetmap.fr, ODbL) в data/cities/cache/<город>.osm.pbf в том же виде, что ответы
    Overpass `out geom`; итог кэшируется в data/cities/cache/osm-<город>-<sha256 bbox>.json. Нужен pyosmium:
    uv run --project tools --with osmium python scripts/city_geojson.py fetch <город> (сборке он уже не нужен)."""
    import osmium
    pbf = CACHE / PBF[city]
    s, w, n, e = padded(bbox)
    ws, ww, wn, we = padded(bbox, 3000.0)
    keep = city_filter(city)
    inside = lambda lon, lat: w <= lon <= e and s <= lat <= n and (keep is None or keep(lon, lat))
    relations, members = {}, {}
    for r in osmium.FileProcessor(str(pbf), osmium.osm.RELATION):
        tags = dict(r.tags)
        groups = group_of("relation", tags)
        plant = tags.get("power") == "plant"
        if groups or plant:
            relations[r.id] = dict(tags=tags, groups=groups, plant=plant,
                                   members=[(m.type, m.ref, m.role) for m in r.members])
            for t, ref, _ in relations[r.id]["members"]:
                if t == "w":
                    members[ref] = None
    data = defaultdict(list)
    plants = []
    for o in osmium.FileProcessor(str(pbf)).with_locations():
        if o.is_node():
            tags = dict(o.tags)
            if not tags or not o.location.valid():
                continue
            lon, lat = o.location.lon, o.location.lat
            if tags.get("power") == "plant" and ws <= lat <= wn and ww <= lon <= we:
                plants.append({"type": "node", "id": o.id, "center": {"lat": lat, "lon": lon}, "tags": tags})
            if inside(lon, lat):
                for g in group_of("node", tags):
                    data[g].append({"type": "node", "id": o.id, "lat": lat, "lon": lon, "tags": tags})
        elif o.is_way():
            want = o.id in members
            tags = dict(o.tags)
            groups = group_of("way", tags) if tags else []
            plant = tags.get("power") == "plant"
            if not (want or groups or plant):
                continue
            try:
                pts = [{"lat": nd.location.lat, "lon": nd.location.lon} for nd in o.nodes]
            except osmium.InvalidLocationError:
                continue
            if want:
                members[o.id] = pts
            lats = [p["lat"] for p in pts]
            lons = [p["lon"] for p in pts]
            if plant and min(lats) <= wn and max(lats) >= ws and min(lons) <= we and max(lons) >= ww:
                plants.append({"type": "way", "id": o.id, "tags": tags, "center": {
                    "lat": (min(lats) + max(lats)) / 2, "lon": (min(lons) + max(lons)) / 2}})
            if groups and min(lats) <= n and max(lats) >= s and min(lons) <= e and max(lons) >= w and (
                    keep is None or any(keep(p["lon"], p["lat"]) for p in (pts[0], pts[len(pts) // 2], pts[-1]))):
                for g in groups:
                    data[g].append({"type": "way", "id": o.id, "tags": tags, "geometry": pts})
    for rid, r in sorted(relations.items()):
        geoms = [(t, ref, role, members.get(ref)) for t, ref, role in r["members"]]
        pts = [p for t, _, _, g in geoms if t == "w" and g for p in g]
        if not pts:
            continue
        lats = [p["lat"] for p in pts]
        lons = [p["lon"] for p in pts]
        element = {"type": "relation", "id": rid, "tags": r["tags"], "members": [
            {"type": "way", "ref": ref, "role": role, "geometry": g} for t, ref, role, g in geoms if t == "w" and g]}
        if r["plant"] and min(lats) <= wn and max(lats) >= ws and min(lons) <= we and max(lons) >= ww:
            plants.append({"type": "relation", "id": rid, "tags": r["tags"], "center": {
                "lat": (min(lats) + max(lats)) / 2, "lon": (min(lons) + max(lons)) / 2}})
        if min(lats) <= n and max(lats) >= s and min(lons) <= e and max(lons) >= w:
            for g in r["groups"]:
                data[g].append(element)
    result = {name: {"elements": data.get(name, [])} for name in queries(bbox)}
    result["plants"] = {"elements": plants}
    return result


def districts_wgs(city):
    """Районы города из cfg["districts"] (ответ Overpass или heat_pbf.py): [(название, полигон EPSG:4326)]."""
    sys.path.insert(0, str(ROOT / "scripts"))
    from heat_osm import districts
    return districts(CACHE / CITIES[city]["districts"])


def city_filter(city):
    """Для города с районами — проверка «точка в районах города с запасом ~500 м» (lon, lat) → bool, иначе None:
    так в кэш не идут соседние города внутри bbox (Бор, Кстово)."""
    if "districts" not in CITIES[city]:
        return None
    zone = unary_union([g for _, g in districts_wgs(city)]).buffer(0.006)
    shapely.prepare(zone)
    return lambda lon, lat: bool(shapely.contains_xy(zone, lon, lat))


def pbf_meta(city):
    path = CACHE / PBF[city]
    if not path.exists():
        return None
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    return dict(file=PBF[city], sha256=digest, bytes=path.stat().st_size)


def fetch(city, bbox):
    """Слои OSM района: из выгрузки .osm.pbf, если она лежит в кэше (основной путь, воспроизводимо и без лимитов),
    иначе из Overpass плитками TILES×TILES (большой запрос под нагрузкой отвечает 504), без повторов."""
    key = hashlib.sha256(json.dumps([city, list(bbox)]).encode()).hexdigest()[:16]
    cached = CACHE / f"osm-{city}-{key}.json"
    if cached.exists():
        data = json.loads(cached.read_text(encoding="utf-8"))
        for name, answer in data.items():
            print(f"{city} {name}: {len(answer['elements'])} объектов (выгрузка OSM)", file=sys.stderr)
        return data
    if (CACHE / PBF[city]).exists():
        data = from_pbf(city, bbox)
        cached.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
        return fetch(city, bbox)
    s, w, n, e = padded(bbox)
    data = {}
    for name, template in queries(bbox).items():
        elements, seen = [], set()
        for i in range(TILES):
            for j in range(TILES):
                tile = (s + (n - s) * i / TILES, w + (e - w) * j / TILES,
                        s + (n - s) * (i + 1) / TILES, w + (e - w) * (j + 1) / TILES)
                for el in fetch_tile(template, tile):
                    key = (el["type"], el["id"])
                    if key not in seen:
                        seen.add(key)
                        elements.append(el)
        data[name] = {"elements": elements}
    wide = "{:.6f},{:.6f},{:.6f},{:.6f}".format(*padded(bbox, 3000.0))
    data["plants"] = overpass("[out:json][timeout:180];" + f"""(nwr["power"="plant"]({wide});
nwr["plant:output:hot_water"]({wide});nwr["plant:output:heat"]({wide}););out tags center;""")
    for name, answer in data.items():
        print(f"{city} {name}: {len(answer['elements'])} объектов", file=sys.stderr)
    return data


# ---------------------------------------------------------------- OSM → геометрия в EPSG:32637

TO_UTM = Transformer.from_crs("EPSG:4326", "EPSG:32637", always_xy=True)
TO_WGS = Transformer.from_crs("EPSG:32637", "EPSG:4326", always_xy=True)
# замкнутая линия с этими ключами — площадь (вики OSM, «Area»)
AREA_KEYS = ("building", "landuse", "leisure", "amenity", "military", "area:highway", "boundary", "healthcare",
             "place", "demolished:building", "razed:building", "was:building", "building:part", "water", "power",
             "man_made", "natural")
NATURAL_LINES = {"coastline", "tree_row", "cliff", "ridge", "arete", "valley", "earth_bank", "gully"}
POWER_AREAS = {"plant", "substation", "generator"}


def utm(geom):
    return shapely.transform(geom, lambda c: np.column_stack(TO_UTM.transform(c[:, 0], c[:, 1])))


def wgs(geom):
    return shapely.transform(geom, lambda c: np.column_stack(TO_WGS.transform(c[:, 0], c[:, 1])))


def polygonal(geom):
    """Только площадные части геометрии, после make_valid."""
    if geom is None or geom.is_empty:
        return None
    if not geom.is_valid:
        geom = shapely.make_valid(geom)
    parts = [g for g in getattr(geom, "geoms", [geom]) if g.geom_type in ("Polygon", "MultiPolygon")]
    parts = [p for g in parts for p in getattr(g, "geoms", [g]) if p.area > 0]
    if not parts:
        return None
    return parts[0] if len(parts) == 1 else MultiPolygon(parts)


def linear(geom):
    if geom is None or geom.is_empty:
        return None
    parts = [p for g in getattr(geom, "geoms", [geom]) for p in getattr(g, "geoms", [g])
             if p.geom_type == "LineString" and p.length > 0]
    if not parts:
        return None
    return parts[0] if len(parts) == 1 else MultiLineString(parts)


def is_area(tags):
    if tags.get("area") == "no":
        return False
    if tags.get("area") == "yes":
        return True
    if tags.get("natural") in NATURAL_LINES:
        return False
    if tags.get("power") and tags.get("power") not in POWER_AREAS:
        return False
    if tags.get("man_made") and tags.get("man_made") in ("pipeline", "embankment", "cutline", "dyke"):
        return False
    if tags.get("waterway") in ("riverbank", "dock", "boatyard"):
        return True
    return any(k in tags for k in AREA_KEYS)


def element_geometry(el):
    """Геометрия элемента Overpass `out geom` в EPSG:32637: точка, линия, полигон или мультиполигон с дырами."""
    kind = el["type"]
    if kind == "node":
        return utm(Point(el["lon"], el["lat"]))
    if kind == "way":
        pts = [(p["lon"], p["lat"]) for p in el.get("geometry") or [] if p]
        if len(pts) < 2:
            return None
        if len(pts) >= 4 and pts[0] == pts[-1] and is_area(el.get("tags", {})):
            return polygonal(utm(Polygon(pts)))
        return utm(LineString(pts))
    rings = {"outer": [], "inner": []}
    for m in el.get("members", []):
        if m["type"] == "way" and m.get("geometry"):
            pts = [(p["lon"], p["lat"]) for p in m["geometry"] if p]
            if len(pts) >= 2:
                rings["inner" if m.get("role") == "inner" else "outer"].append(LineString(pts))
    outer = unary_union(list(polygonize(rings["outer"]))) if rings["outer"] else None
    if outer is None or outer.is_empty:
        return None
    inner = unary_union(list(polygonize(rings["inner"]))) if rings["inner"] else None
    if inner is not None and not inner.is_empty:
        outer = outer.difference(inner)
    return polygonal(utm(outer))


class Obj:
    """Объект OSM: id вида w123/r45/n6, теги, геометрия в UTM."""
    __slots__ = ("id", "tags", "geom")

    def __init__(self, oid, tags, geom):
        self.id, self.tags, self.geom = oid, tags, geom


def objects(answer):
    seen, result = set(), []
    for el in answer["elements"]:
        oid = el["type"][0] + str(el["id"])
        if oid in seen:
            continue
        seen.add(oid)
        geom = element_geometry(el)
        if geom is not None and not geom.is_empty:
            result.append(Obj(oid, el.get("tags", {}), geom))
    result.sort(key=lambda o: o.id)
    return result


def region_polygon(bbox, city=None):
    """Район сборки в UTM: bbox, а у города с районами (cfg["districts"]) — объединение районов."""
    if city and "districts" in CITIES[city]:
        return polygonal(utm(unary_union([g for _, g in districts_wgs(city)])))
    s, w, n, e = bbox
    return utm(shapely.segmentize(box(w, s, e, n), 0.0005))


def use_zone(city):
    """Зона UTM города: 37N (Москва, СПб) или из cfg["epsg"] (Н. Новгород, 44° в. д. — зона 38N)."""
    global TO_UTM, TO_WGS
    epsg = CITIES[city].get("epsg", 32637)
    TO_UTM = Transformer.from_crs("EPSG:4326", f"EPSG:{epsg}", always_xy=True)
    TO_WGS = Transformer.from_crs(f"EPSG:{epsg}", "EPSG:4326", always_xy=True)


def number(value):
    """Первое число тега OSM: «5», «5;9», «12 m», «3,5» → float или None."""
    if value is None:
        return None
    text = str(value).replace(",", ".")
    digits = ""
    for ch in text:
        if ch.isdigit() or (ch == "." and "." not in digits and digits):
            digits += ch
        elif digits:
            break
    try:
        return float(digits) if digits else None
    except ValueError:
        return None


def lifecycle(tags):
    """Объект ещё не построен или уже снесён: такие дороги, пути и трубы в ограничения не идут."""
    return any(tags.get(k) for k in ("construction", "proposed", "disused", "abandoned", "razed", "demolished")
               if k in ("disused", "abandoned")) or any(
        v in ("construction", "proposed", "disused", "abandoned", "razed", "demolished", "planned")
        for k, v in tags.items() if k in ("highway", "railway", "building", "power", "man_made", "waterway"))


# ---------------------------------------------------------------- здания и тепловая нагрузка

RESIDENTIAL = {"apartments", "residential", "house", "detached", "semidetached_house", "terrace", "dormitory",
               "bungalow", "hotel"}
PUBLIC = {"school", "kindergarten", "hospital", "clinic", "university", "college", "public", "civic", "government",
          "office", "commercial", "retail", "supermarket", "sports_centre", "sports_hall", "church", "cathedral",
          "chapel", "mosque", "temple", "train_station", "transportation", "fire_station", "police", "museum",
          "theatre", "library", "community_centre", "healthcare", "mall", "bank", "post_office", "gym"}
INDUSTRIAL = {"industrial", "warehouse", "manufacture", "factory", "hangar", "depot"}
# без отопления: гаражи, навесы, сараи, киоски, трансформаторные и насосные (building=service), резервуары,
# теплицы, парковки
UNHEATED = {"garage", "garages", "shed", "roof", "hut", "carport", "kiosk", "transformer_tower", "storage_tank",
            "greenhouse", "bunker", "toilets", "parking", "container", "tent", "ruins", "barn", "cowshed", "stable",
            "sty", "farm_auxiliary", "silo", "tower", "water_tower", "bridge", "guardhouse", "gatehouse", "no",
            "boathouse", "digester", "grandstand", "pavilion", "shelter", "allotment_house", "cabin",
            "service"}
# СП 131.13330.2020, табл. 3.1: температура наиболее холодной пятидневки обеспеченностью 0,92
T_DESIGN = {"moscow": -25.0, "spb": -24.0, "nnovgorod": -30.0}
# СНиП 2.04.07-86*, прил. 2: укрупнённый показатель максимального теплового потока на отопление жилых зданий,
# Вт на 1 м² общей площади, при расчётной температуре −20 и −25 °C; ключ — (постройка, этажность)
Q_O = {
    ("до 1985", "1-2"): (205, 213), ("до 1985", "3-4"): (117, 126), ("до 1985", "5+"): (79, 86),
    ("после 1985", "1-2"): (166, 173), ("после 1985", "3-4"): (91, 97), ("после 1985", "5+"): (73, 81),
}
# СНиП 2.04.07-86*, прил. 3: средний тепловой поток на ГВС на одного жителя при норме 105 л/сут (55 °C), Вт
Q_H_PERSON = 305.0
# СНиП 2.04.07-86*, п. 2.4: доля вентиляции общественных зданий k2 = 0,4 (до 1985) и 0,6 (после 1985)
K2 = {"до 1985": 0.4, "после 1985": 0.6}
# общая площадь квартир и помещений на этаж — 0,8 площади застройки (типовые секции: 75–85 %, принято 0,8)
AREA_SHARE = 0.8
# жилищная обеспеченность: 18 м²/чел для существующего фонда (СНиП 2.07.01-89*, п. 2.6), 30 м²/чел для новых домов
# (СП 42.13330.2016, табл. 2, экономический класс)
AREA_PER_PERSON = {"до 1985": 18.0, "после 1985": 18.0, "new": 30.0}
# расчётный расход сетевой воды закрытой системы (СП 124.13330.2012, разд. 8; СНиП 2.04.07-86*, п. 5.2):
# G = G_o + G_v + k3·G_hm, график 150/70 °C, ГВС по параллельной схеме от точки излома 70/30 °C; k3 = 1,0 — система
# от ТЭЦ с тепловым потоком больше 100 МВт
C_WATER = 4.187
DT_HEAT = 150.0 - 70.0
DT_HOT_WATER = 70.0 - 30.0
K3 = 1.0
W_PER_GCAL_H = 1.163e6
# внутренняя температура по назначению к расчётной жилой 20 °C (ГОСТ 30494): производство 16 °C, склад 12 °C
INDOOR = {"industrial": 16.0, "warehouse": 12.0}


# building=yes площадью до 80 м² — киоски, сараи, трансформаторные, будки: без отопления
SMALL_M2 = 80.0


def building_class(tags, landuse, area=1e9):
    """Назначение здания: residential / public / industrial / unheated по building=* и землепользованию квартала."""
    kind = tags.get("building", "yes")
    if tags.get("power") or tags.get("man_made") in ("storage_tank", "water_tower", "chimney", "silo"):
        return "unheated"
    if kind in RESIDENTIAL:
        return "residential"
    if kind in PUBLIC or tags.get("amenity") or tags.get("shop") or tags.get("office"):
        return "public"
    if kind in INDUSTRIAL:
        return "industrial"
    if kind in UNHEATED:
        return "unheated"
    if area < SMALL_M2:
        return "unheated"
    # building=yes и прочее: по землепользованию квартала, где стоит центр здания
    if landuse in ("residential",):
        return "residential"
    if landuse in ("industrial", "railway", "garages"):
        return "industrial" if landuse != "garages" else "unheated"
    return "public"


def storeys_row(levels):
    return "1-2" if levels <= 2 else "3-4" if levels <= 4 else "5+"


def heat_demand(kind, floor_m2, levels, era, t_design, new=False):
    """Расчётные нагрузки (Вт) отопления, вентиляции и средняя ГВС одного здания по СНиП 2.04.07-86*, прил. 2–3.
    floor_m2 — площадь всех этажей по наружному обмеру (площадь застройки × этажность, у сложных зданий — по частям)."""
    if kind == "unheated":
        return 0.0, 0.0, 0.0
    low, high = Q_O[(era, storeys_row(levels))]
    q_o = low + (high - low) * (-20.0 - t_design) / 5.0
    area = floor_m2 * AREA_SHARE
    q_heat = q_o * area
    if kind == "industrial":
        q_heat *= (INDOOR["industrial"] - t_design) / (20.0 - t_design)
        return q_heat, K2[era] * q_heat, 0.0
    if kind == "public":
        # вентиляция по k2, ГВС общественного здания — 10 % отопления (допущение: норм на здание неизвестного
        # назначения нет; порядок как у школ и офисов)
        return q_heat, K2[era] * q_heat, 0.1 * q_heat
    people = area / AREA_PER_PERSON["new" if new else era]
    return q_heat, 0.0, Q_H_PERSON * people


def flow_of(demand):
    """Расход сетевой воды, т/ч, и нагрузка, Гкал/ч, по нагрузкам (Вт) отопления, вентиляции и ГВС."""
    q_o, q_v, q_hm = demand
    g = 3.6 * (q_o + q_v) / (C_WATER * DT_HEAT) + K3 * 3.6 * q_hm / (C_WATER * DT_HOT_WATER)
    return g / 1000.0, (q_o + q_v + q_hm) / W_PER_GCAL_H


def year_of(tags):
    for key in ("start_date", "building:start_date", "construction_date", "year_of_construction"):
        value = number(tags.get(key))
        if value and 1700 < value < 2100:
            return int(value)
    return None


def era_of(tags, levels):
    """Постройка до или после 1985 г.: по start_date, без тега — дома от 17 этажей считаются новыми."""
    year = year_of(tags)
    if year is not None:
        return "после 1985" if year >= 1985 else "до 1985"
    return "после 1985" if levels >= 17 else "до 1985"


# ---------------------------------------------------------------- растр стоимости прокладки и дерево сети

INF = float("inf")
SQRT2 = math.sqrt(2.0)


class Grid:
    """Растр стоимости прокладки 1 м трубы. Ячейки по краю — непроходимые, поэтому соседи без проверки границ."""

    def __init__(self, bounds, cell, default):
        x0, y0, x1, y1 = bounds
        self.cell = cell
        self.x0, self.y0 = x0 - cell, y0 - cell
        self.nx = int(math.ceil((x1 - x0) / cell)) + 2
        self.ny = int(math.ceil((y1 - y0) / cell)) + 2
        self.cost = np.full((self.ny, self.nx), default, dtype=float)
        self.cost[0, :] = self.cost[-1, :] = self.cost[:, 0] = self.cost[:, -1] = INF

    def window(self, geom, pad=0.0):
        minx, miny, maxx, maxy = geom.bounds
        i0 = max(1, int((minx - pad - self.x0) / self.cell))
        i1 = min(self.nx - 2, int((maxx + pad - self.x0) / self.cell) + 1)
        j0 = max(1, int((miny - pad - self.y0) / self.cell))
        j1 = min(self.ny - 2, int((maxy + pad - self.y0) / self.cell) + 1)
        if i0 > i1 or j0 > j1:
            return None
        xs = self.x0 + (np.arange(i0, i1 + 1) + 0.5) * self.cell
        ys = self.y0 + (np.arange(j0, j1 + 1) + 0.5) * self.cell
        gx, gy = np.meshgrid(xs, ys)
        return (slice(j0, j1 + 1), slice(i0, i1 + 1)), gx, gy

    def paint(self, geom, value):
        """Ячейки, центр которых внутри площадной геометрии, получают стоимость value."""
        if geom is None or geom.is_empty:
            return
        win = self.window(geom)
        if win is None:
            return
        sl, gx, gy = win
        shapely.prepare(geom)
        mask = shapely.contains_xy(geom, gx, gy)
        self.cost[sl][mask] = value

    def index(self, x, y):
        return int((y - self.y0) / self.cell) * self.nx + int((x - self.x0) / self.cell)

    def center(self, k):
        j, i = divmod(k, self.nx)
        return self.x0 + (i + 0.5) * self.cell, self.y0 + (j + 0.5) * self.cell


def dijkstra(grid, starts):
    """Кратчайшие пути по 8 соседям от ячеек starts. Стоимость шага — средняя стоимость двух ячеек на длину шага;
    диагональ не срезает угол непроходимой ячейки. Равные расстояния разрешаются номером ячейки: результат
    детерминирован."""
    import heapq
    from array import array
    # pred — массив array, а не список: на растре района в миллионы ячеек это сотни мегабайт памяти
    cost = grid.cost.ravel().tolist()
    n, nx, half = len(cost), grid.nx, grid.cell / 2.0
    dist = [INF] * n
    pred = array("q", [-1]) * n
    heap = []
    for s in starts:
        dist[s] = 0.0
        heap.append((0.0, s))
    heapq.heapify(heap)
    straight = (1, -1, nx, -nx)
    diagonal = ((nx + 1, 1, nx), (nx - 1, -1, nx), (-nx + 1, 1, -nx), (-nx - 1, -1, -nx))
    pop, push = heapq.heappop, heapq.heappush
    while heap:
        d, k = pop(heap)
        if d > dist[k]:
            continue
        ck = cost[k]
        for o in straight:
            j = k + o
            cj = cost[j]
            if cj != INF:
                nd = d + (ck + cj) * half
                if nd < dist[j]:
                    dist[j] = nd
                    pred[j] = k
                    push(heap, (nd, j))
        for o, a, b in diagonal:
            j = k + o
            cj = cost[j]
            if cj != INF and cost[k + a] != INF and cost[k + b] != INF:
                nd = d + (ck + cj) * half * SQRT2
                if nd < dist[j]:
                    dist[j] = nd
                    pred[j] = k
                    push(heap, (nd, j))
    return dist, pred


def cell_tree(pred, source, targets):
    """Дерево кратчайших путей от источника (или от стартовых ячеек, у которых pred = −1) до целей: родитель каждой
    ячейки на путях и узлы ветвления."""
    parent, children = {}, defaultdict(list)
    for t in targets:
        c = t
        while pred[c] >= 0 and c not in parent:
            p = pred[c]
            parent[c] = p
            children[p].append(c)
            c = p
    return parent, children


def cell_edges(parent, children, source, nodes):
    """Рёбра дерева между узлами: (верхний узел, нижний узел, ячейки сверху вниз)."""
    nodes = set(nodes) | {source} | {c for c, ch in children.items() if len(ch) >= 2}
    edges = []
    for n in sorted(nodes):
        if n == source or n not in parent:
            continue
        path = [n]
        c = parent[n]
        while c not in nodes:
            path.append(c)
            c = parent[c]
        path.append(c)
        edges.append((c, n, path[::-1]))
    return nodes, edges


# ---------------------------------------------------------------- запись GeoJSON

TYPE_ORDER = ("source", "heat_network", "heat_chamber", "oks_future", "oks_connection_point", "oks_existing",
              "restriction")
DATASET_ORDER = ("source", "heat_network", "heat_chamber", "oks_connection_point", "restriction")


def rounded(geom):
    return shapely.transform(wgs(geom), lambda c: np.round(c, 9))


def final_geometry(geom):
    """Геометрия в EPSG:4326 с 9 знаками, валидная (OGC) уже после округления; None — если не спасти."""
    area = geom.geom_type in ("Polygon", "MultiPolygon")
    line = geom.geom_type in ("LineString", "MultiLineString")
    g = rounded(geom)
    for _ in range(3):
        if g.is_valid and not g.is_empty:
            return g
        g = shapely.make_valid(g)
        g = polygonal(g) if area else linear(g) if line else g
        if g is None:
            return None
        g = shapely.transform(g, lambda c: np.round(c, 9))
    return g if g.is_valid and not g.is_empty else None


def write_features(path, items, order=TYPE_ORDER):
    """items: (геометрия UTM, свойства). Одна фича на строку (scripts/city_cut.py читает построчно), порядок —
    по типу объекта, типу ограничения и id; повторный запуск даёт тот же файл байт в байт."""
    rows, dropped = [], 0
    for geom, props in items:
        g = final_geometry(geom)
        if g is None:
            assert props["object_type"] not in ("heat_network", "heat_chamber", "source"), props["id"]
            dropped += 1
            continue
        key = (order.index(props["object_type"]), props.get("restriction_type", ""), props["id"])
        rows.append((key, {"type": "Feature", "geometry": shapely.geometry.mapping(g), "properties": props}))
    rows.sort(key=lambda r: r[0])
    ids = [r[1]["properties"]["id"] for r in rows]
    assert len(ids) == len(set(ids)), "id повторяются"
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as out:
        out.write('{"type":"FeatureCollection","features":[\n')
        out.write(",\n".join(json.dumps(f, ensure_ascii=False, separators=(",", ":")) for _, f in rows))
        out.write("\n]}\n")
    return len(rows), dropped


# ---------------------------------------------------------------- ограничения из OSM

# ширина проезжей части без тегов width/lanes: число полос по категории улицы × ширина полосы
# (СП 42.13330.2016, разд. 11, табл. 11.2: магистрали 3,5–3,75 м × 4–8 полос, улицы районного значения
# 3,5 м × 2–4, местные 3,0 м × 2; основные внутриквартальные проезды 2 × 2,75 м, второстепенные 1 × 3,5 м)
ROAD_CLASS = {
    "motorway": (6, 3.75), "trunk": (6, 3.5), "primary": (4, 3.5), "secondary": (4, 3.5), "tertiary": (2, 3.5),
    "unclassified": (2, 3.0), "residential": (2, 3.0), "living_street": (2, 3.0), "road": (2, 3.0),
    "busway": (2, 3.5), "motorway_link": (1, 3.75), "trunk_link": (1, 3.75), "primary_link": (1, 3.5),
    "secondary_link": (1, 3.5), "tertiary_link": (1, 3.5), "service": (2, 2.75),
}
MINOR_SERVICE = {"driveway", "alley", "emergency_access"}
SKIP_SERVICE = {"parking_aisle", "drive-through"}
MAJOR = {"motorway", "trunk", "primary", "secondary", "motorway_link", "trunk_link", "primary_link", "secondary_link"}
LANE_M = 3.5
# трамвай: ширина одного пути по концам шпал 2,6–2,75 м, принято 3,0 м; второй путь через 3,2 м (межпутье)
TRAM_TRACK_M, TRAM_SPACING_M = 3.0, 3.2
# ж/д и открытые участки метро: основная площадка земляного полотна однопутной линии 5,5–7,6 м
# (СП 119.13330.2017, табл. 5.2), принято 6 м на путь
RAIL_TRACK_M = 6.0
# ширина русла без тега width: река 20 м, канал 8 м, ручей 3 м, канава и дренаж 2 м (типичные значения OSM)
WATERWAY_M = {"river": 20.0, "canal": 8.0, "stream": 3.0, "drain": 2.0, "ditch": 2.0, "tidal_channel": 5.0}
MIN_PART_M2 = 1.0


def road_width(tags):
    """Ширина проезжей части, м, и откуда она: тег width, тег lanes × 3,5 м или класс улицы."""
    width = number(tags.get("width"))
    if width and 2.0 <= width <= 60.0:
        return width, "width"
    lanes = number(tags.get("lanes"))
    if lanes and 1 <= lanes <= 12:
        return lanes * LANE_M, "lanes"
    kind = tags["highway"]
    count, lane = ROAD_CLASS[kind]
    if kind == "service" and tags.get("service") in MINOR_SERVICE:
        count, lane = 1, 3.5
    if tags.get("oneway") in ("yes", "1", "-1") and count > 1:
        count = math.ceil(count / 2)
    return count * lane, "class"


def clip(geom, region):
    if geom is None:
        return None
    g = geom.intersection(region) if not region.contains(geom) else geom
    if g.is_empty:
        return None
    return polygonal(g) if geom.geom_type in ("Polygon", "MultiPolygon") else linear(g) if "Line" in geom.geom_type else g


def restriction(rid, rtype, geom, source, **extra):
    props = {"id": rid, "object_type": "restriction", "restriction_type": rtype, "_source": source}
    props.update(extra)
    return geom, props


def is_carriageway(tags):
    kind = tags.get("highway")
    return (kind in ROAD_CLASS and not lifecycle(tags) and tags.get("service") not in SKIP_SERVICE
            and tags.get("area") != "yes" and tags.get("access") not in ("no",))


def roads(highways, region):
    """Проезжая часть: полигоны area:highway (real), остальное — ось с шириной по тегам или классу.
    Мосты и тоннели пропускаются: трасса проходит под мостом и над тоннелем, это не пересечение проезжей части."""
    areas = [o for o in highways if o.tags.get("area:highway") in ROAD_CLASS and o.geom.geom_type.endswith("Polygon")]
    area_union = unary_union([o.geom for o in areas]) if areas else None
    items, axes = [], []
    for o in areas:
        g = clip(o.geom, region)
        if g is not None:
            items.append(restriction(f"road-{o.id}", "road", g, "real", highway=o.tags["area:highway"]))
    for o in highways:
        t = o.tags
        if not is_carriageway(t) or o.geom.geom_type != "LineString":
            continue
        width, how = road_width(t)
        bridge = t.get("bridge") not in (None, "no")
        tunnel = t.get("tunnel") not in (None, "no")
        axes.append(dict(id=o.id, kind=t["highway"], service=t.get("service"), axis=o.geom, width=width,
                         bridge=bridge, tunnel=tunnel))
        if bridge or tunnel:
            continue
        axis = o.geom.difference(area_union) if area_union is not None and o.geom.intersects(area_union) else o.geom
        axis = linear(axis)
        if axis is None:
            continue
        g = clip(polygonal(axis.buffer(width / 2.0, quad_segs=2)), region)
        if g is not None and g.area >= MIN_PART_M2:
            items.append(restriction(f"road-{o.id}", "road", g, "real" if how == "width" else "inferred",
                                     highway=t["highway"], width_m=round(width, 2)))
    return items, axes


def tracks(rail, region):
    """Трамвай (tram_tracks), ж/д (railway), метро на поверхности (metro) — полигоны вокруг осей OSM.
    Тоннели не ограничение: трасса над тоннелем не пересекает путь. Мосты тоже пропускаются."""
    items = []
    for o in rail:
        t = o.tags
        kind = t.get("railway")
        if o.geom.geom_type != "LineString" or lifecycle(t) or t.get("tunnel") not in (None, "no") \
                or t.get("bridge") not in (None, "no") or t.get("location") == "underground":
            continue
        count = max(1, int(number(t.get("tracks")) or 1))
        if kind == "tram":
            width, rtype, prefix = TRAM_TRACK_M + (count - 1) * TRAM_SPACING_M, "tram_tracks", "tram"
        elif kind == "subway":
            width, rtype, prefix = RAIL_TRACK_M * count, "metro", "metro"
        elif kind in ("rail", "light_rail", "narrow_gauge", "monorail", "funicular", "preserved"):
            width, rtype, prefix = RAIL_TRACK_M * count, "railway", "rail"
        else:
            continue
        g = clip(polygonal(o.geom.buffer(width / 2.0, quad_segs=2)), region)
        if g is not None:
            items.append(restriction(f"{prefix}-{o.id}", rtype, g, "real"))
    return items


def area_layers(areas, region):
    """Вода, парки, территории соцобъектов, запретные территории — полигоны OSM, обрезанные по району."""
    water_polys, water_lines, items = [], [], []
    for o in areas:
        t = o.tags
        poly = o.geom.geom_type.endswith("Polygon")
        if poly and (t.get("natural") == "water" or t.get("waterway") in ("riverbank", "dock")
                     or t.get("landuse") in ("reservoir", "basin") or (t.get("water") and t.get("natural") != "wetland")):
            water_polys.append(o)
        elif not poly and t.get("waterway") in WATERWAY_M and t.get("tunnel") in (None, "no") and not lifecycle(t):
            water_lines.append(o)
    union = unary_union([o.geom for o in water_polys]) if water_polys else None
    for o in water_polys:
        g = clip(o.geom, region)
        if g is not None:
            items.append(restriction(f"water-{o.id}", "water", g, "real"))
    for o in water_lines:
        axis = linear(o.geom.difference(union)) if union is not None else o.geom
        if axis is None:
            continue
        width = number(o.tags.get("width"))
        how = "real" if width else "inferred"
        width = width if width and 0.5 <= width <= 300 else WATERWAY_M[o.tags["waterway"]]
        g = clip(polygonal(axis.buffer(width / 2.0, quad_segs=2)), region)
        if g is not None:
            items.append(restriction(f"water-{o.id}", "water", g, how))
    for o in areas:
        t = o.tags
        if not o.geom.geom_type.endswith("Polygon") or t.get("building"):
            continue
        rtype = area_type(t, o.geom.area)
        if rtype:
            g = clip(o.geom, region)
            if g is not None:
                items.append(restriction(f"{rtype[:4]}-{o.id}", rtype, g, "real"))
    return items


# парки: leisure=park, лес; сады и перелески во дворах — от 2000 м² (меньше — озеленение двора, а не парк)
PARK_MIN_M2 = 2000.0
SOCIAL = {"school", "kindergarten", "hospital", "clinic", "childcare"}
# запретные: военные объекты, кладбища, электростанции и подстанции, места заключения
PROHIBITED_LANDUSE = {"military", "cemetery"}


def area_type(tags, area):
    if tags.get("landuse") in PROHIBITED_LANDUSE or tags.get("military") or tags.get("amenity") in (
            "grave_yard", "prison") or tags.get("power") in ("plant", "substation"):
        return "prohibited_site"
    if tags.get("amenity") in SOCIAL or tags.get("healthcare") in ("hospital", "clinic"):
        return "social_area"
    if tags.get("leisure") == "park" or tags.get("landuse") == "forest" or tags.get("leisure") == "nature_reserve":
        return "park"
    if (tags.get("leisure") == "garden" or tags.get("natural") == "wood") and area >= PARK_MIN_M2:
        return "park"
    return None


def supports(utilities, region):
    return [restriction(f"pole-{o.id}", "power_line_support", o.geom, "real") for o in utilities
            if o.geom.geom_type == "Point" and o.tags.get("power") in ("tower", "pole", "portal")
            and region.contains(o.geom)]


HEAT_SUBSTANCES = {"hot_water", "heat", "steam", "heating"}


def substance(tags):
    return (tags.get("substance") or tags.get("type") or tags.get("content") or "").lower()


def utility_lines(utilities, region):
    """Коммуникации из OSM как есть (real): газ, кабели, водопровод, канализация. Трубы теплосети и люки —
    отдельно, из них собирается существующая сеть."""
    items, heat_pipes, manholes = [], [], []
    for o in utilities:
        t = o.tags
        if lifecycle(t):
            continue
        if o.geom.geom_type == "Point":
            if t.get("manhole") in ("heating", "heat") or (t.get("manhole") and substance(t) in HEAT_SUBSTANCES):
                manholes.append(o)
            continue
        if o.geom.geom_type != "LineString":
            continue
        rtype = None
        if t.get("power") == "cable" or (t.get("power") in ("line", "minor_line") and t.get("location") == "underground"):
            rtype, prefix = "power_cable", "cab"
        elif t.get("man_made") == "pipeline" or t.get("substance") or t.get("utility"):
            s = substance(t) or t.get("utility", "")
            if s in HEAT_SUBSTANCES:
                heat_pipes.append(o)
                continue
            rtype, prefix = {"gas": ("gas_pipeline", "gas"), "water": ("water_supply", "ws"),
                             "drinking_water": ("water_supply", "ws"), "sewage": ("sewer", "sew"),
                             "sewerage": ("sewer", "sew"), "wastewater": ("sewer", "sew"),
                             "rainwater": ("sewer", "sew")}.get(s, (None, None))
        if rtype:
            g = clip(o.geom, region)
            if g is not None:
                items.append(restriction(f"{prefix}-{o.id}", rtype, g, "real"))
    return items, heat_pipes, manholes


# ---------------------------------------------------------------- существующие здания

DEFAULT_LEVELS = {"residential": 9, "public": 3, "industrial": 2, "unheated": 1}
STOREY_M = 3.0
NEIGHBOUR_M = 200.0


class Building:
    __slots__ = ("id", "tags", "geom", "kind", "levels", "levels_source", "era", "flow", "load", "year", "floor")


def tagged_levels(tags):
    levels = number(tags.get("building:levels"))
    if levels and 1 <= levels <= 100:
        return int(round(levels))
    height = number(tags.get("height"))
    if height and 2.5 <= height <= 400:
        return max(1, int(round(height / STOREY_M)))
    return None


def quarters(axes, region):
    """Кварталы: полигоны, на которые район режут оси улиц (без внутриквартальных проездов)."""
    lines = [a["axis"] for a in axes if a["kind"] != "service"] + [region.exterior]
    return [p for p in polygonize(unary_union(lines)) if p.area > 100.0]


def part_levels(tags):
    levels = tagged_levels(tags)
    low = number(tags.get("building:min_level")) or 0
    return max(1, levels - int(low)) if levels else None


def floor_area(b, parts, part_tree):
    """Площадь этажей: у здания с частями building:part (башни на стилобате) — сумма по частям с их этажностью,
    непокрытая частями площадь — по наименьшей этажности частей; без частей — площадь застройки × этажность."""
    inside = [parts[i] for i in part_tree.query(b.geom, predicate="intersects")
              if b.geom.buffer(1.0).contains(parts[i][0].representative_point())]
    tagged = [(g, lv) for g, lv in inside if lv]
    covered = unary_union([g for g, _ in tagged]).intersection(b.geom).area if tagged else 0.0
    if covered < 0.5 * b.geom.area:
        return b.geom.area * b.levels
    floor = sum(g.intersection(b.geom).area * lv for g, lv in tagged)
    return floor + max(0.0, b.geom.area - covered) * min(lv for _, lv in tagged)


def existing_buildings(objs, part_objs, areas, axes, region, t_design):
    """oks_existing: все здания OSM, задевающие район, кроме строящихся (они — перспективные ОКС).
    Этажность без тега — медиана зданий того же назначения в квартале, иначе в радиусе 200 м, иначе по назначению."""
    landuse = [o for o in areas if o.tags.get("landuse") and o.geom.geom_type.endswith("Polygon")]
    land_tree = STRtree([o.geom for o in landuse])
    result = []
    for o in objs:
        t = o.tags
        if "building" not in t or t["building"] in ("no", "construction") or not o.geom.geom_type.endswith("Polygon"):
            continue
        if not o.geom.intersects(region):
            continue
        b = Building()
        b.id, b.tags, b.geom = o.id, t, o.geom
        centre = o.geom.representative_point()
        use = None
        for i in land_tree.query(centre, predicate="within"):
            use = landuse[i].tags["landuse"]
            break
        b.kind = building_class(t, use, o.geom.area)
        b.levels = tagged_levels(t)
        b.levels_source = "real" if b.levels else "inferred"
        b.year = year_of(t)
        result.append(b)
    blocks = quarters(axes, region)
    block_tree = STRtree(blocks)
    tagged = [b for b in result if b.levels]
    tagged_tree = STRtree([b.geom.representative_point() for b in tagged])
    for b in result:
        if b.levels:
            continue
        centre = b.geom.representative_point()
        pool = []
        for i in block_tree.query(centre, predicate="within"):
            pool = [tagged[j].levels for j in tagged_tree.query(blocks[i], predicate="contains")
                    if tagged[j].kind == b.kind]
            break
        if len(pool) < 3:
            pool = [tagged[j].levels for j in tagged_tree.query(centre, predicate="dwithin", distance=NEIGHBOUR_M)
                    if tagged[j].kind == b.kind]
        b.levels = int(np.median(pool)) if len(pool) >= 3 else DEFAULT_LEVELS[b.kind]
    parts = [(o.geom, part_levels(o.tags)) for o in part_objs if o.geom.geom_type.endswith("Polygon")]
    part_tree = STRtree([g for g, _ in parts])
    for b in result:
        b.era = era_of(b.tags, b.levels)
        b.floor = floor_area(b, parts, part_tree)
        b.flow, b.load = flow_of(heat_demand(b.kind, b.floor, b.levels, b.era, t_design))
    return result


# ---------------------------------------------------------------- перспективные ОКС

# разрывы при размещении новых домов на участке: до существующих зданий 12 м и между новыми 20 м (противопожарные
# 6–15 м по СП 4.13130.2013, табл. 1; инсоляция по СанПиН 1.2.3685-21 на практике даёт 20–30 м между длинными
# сторонами), до проезжей части 5 м (красная линия), до воды 10 м, до парков, соцобъектов и запретных 3 м
GAP_EXISTING_M, GAP_NEW_M, GAP_ROAD_M, GAP_WATER_M, GAP_OTHER_M = 12.0, 20.0, 5.0, 10.0, 3.0
GAP_LINE_M = 5.0
CP_INSET_M = 1.0
SITE_LANDUSE = {"construction", "brownfield", "greenfield"}
DEMOLISHED_KEYS = ("demolished:building", "razed:building", "was:building")
DEMOLISHED_PAD_M = 8.0


def modern_form(buildings):
    """Типичный новый дом района по реальным данным: медианы этажности и сторон описанного прямоугольника жилых домов
    с годом постройки от 2005 (или от 12 этажей без года). Без таких домов — секция 16×60 м, 14 этажей."""
    sample = [b for b in buildings if b.kind == "residential" and b.levels_source == "real"
              and ((b.year or 0) >= 2005 or (b.year is None and b.levels >= 12))]
    if len(sample) < 5:
        return 14, 16.0, 60.0, len(sample)
    depths, lengths = [], []
    for b in sample:
        rect = b.geom.minimum_rotated_rectangle
        xy = list(rect.exterior.coords)
        sides = sorted(math.dist(xy[i], xy[i + 1]) for i in range(2))
        depths.append(sides[0])
        lengths.append(sides[1])
    levels = int(np.median([b.levels for b in sample]))
    depth = float(np.clip(np.median(depths), 14.0, 24.0))
    length = float(np.clip(np.median(lengths), 30.0, 90.0))
    return levels, depth, length, len(sample)


def connection_point(poly, streets):
    """Точка подключения внутри контура в CP_INSET_M от стены, ближайшей к улице."""
    near = streets.query_nearest(poly, all_matches=False)
    street = streets.geometries[int(near[0])]
    wall = shapely.ops.nearest_points(poly.boundary, street)[0]
    inner = poly.buffer(-CP_INSET_M)
    if inner.is_empty:
        return poly.representative_point()
    return shapely.ops.nearest_points(inner.boundary, wall)[0]


def pack(free, depth, length, gap):
    """Жадная раскладка прямоугольных домов по свободной части участка: вдоль длинной оси участка рядами,
    сначала секции length×depth, затем точечные дома depth×depth в оставшиеся места."""
    placed = []
    for part in sorted(getattr(free, "geoms", [free]), key=lambda p: (-p.area, p.bounds)):
        if part.area < depth * depth:
            continue
        rect = part.minimum_rotated_rectangle
        xy = list(rect.exterior.coords)
        edge = max(((xy[i], xy[i + 1]) for i in range(2)), key=lambda e: math.dist(*e))
        angle = math.degrees(math.atan2(edge[1][1] - edge[0][1], edge[1][0] - edge[0][0]))
        origin = part.centroid
        avail = shapely.affinity.rotate(part, -angle, origin=origin)
        for w, h in ((length, depth), (depth * 1.4, depth)):
            minx, miny, maxx, maxy = avail.bounds if not avail.is_empty else (0, 0, -1, -1)
            y = miny + h / 2
            while y <= maxy - h / 2:
                x = minx + w / 2
                while x <= maxx - w / 2:
                    house = box(x - w / 2, y - h / 2, x + w / 2, y + h / 2)
                    if avail.contains(house):
                        placed.append(shapely.affinity.rotate(house, angle, origin=origin))
                        avail = avail.difference(house.buffer(gap, join_style="mitre"))
                        x += w + gap
                    else:
                        x += 4.0
                y += 4.0
    return placed


class Future:
    __slots__ = ("id", "geom", "kind", "levels", "source", "site", "cp", "flow", "load", "tags", "extra")


def future_buildings(objs, areas, existing, obstacles, region, streets, form, t_design):
    """Перспективные ОКС: реальные контуры строящихся зданий (building=construction), затем дома типичной для района
    формы на реальных свободных участках — стройплощадках, brownfield/greenfield, местах снесённых зданий."""
    levels, depth, length, _ = form
    ex_tree = STRtree([b.geom for b in existing])
    result, taken = [], []
    for o in objs:
        t = o.tags
        if t.get("building") != "construction" or not o.geom.geom_type.endswith("Polygon"):
            continue
        if not region.contains(o.geom.representative_point()):
            continue
        overlap = sum(o.geom.intersection(existing[i].geom).area for i in ex_tree.query(o.geom, predicate="intersects"))
        if overlap > 0.2 * o.geom.area or o.geom.area < 50.0:
            continue
        f = Future()
        f.id, f.geom, f.tags, f.source, f.site = f"oks-{o.id}", polygonal(o.geom), t, "real", o.id
        kind = t.get("construction", "apartments")
        f.kind = "public" if kind in PUBLIC else "residential"
        f.levels = tagged_levels(t) or levels
        result.append(f)
        taken.append(o.geom)
    sites = [o for o in areas if o.tags.get("landuse") in SITE_LANDUSE and o.geom.geom_type.endswith("Polygon")]
    sites += [Obj(o.id, o.tags, o.geom.buffer(DEMOLISHED_PAD_M, join_style="mitre")) for o in objs
              if "building" not in o.tags and any(k in o.tags for k in DEMOLISHED_KEYS)
              and o.geom.geom_type.endswith("Polygon")]
    blocked = [(g, GAP_EXISTING_M) for g in (b.geom for b in existing)] + [(g, GAP_NEW_M) for g in taken]
    for kind, geoms in obstacles.items():
        gap = {"road": GAP_ROAD_M, "water": GAP_WATER_M, "tram_tracks": GAP_WATER_M, "railway": GAP_WATER_M,
               "metro": GAP_WATER_M, "heat_network": GAP_LINE_M}.get(
            kind, GAP_LINE_M if kind.endswith("line") else GAP_OTHER_M)
        blocked += [(g, gap) for g in geoms]
    block_tree = STRtree([g for g, _ in blocked])
    used = unary_union(taken) if taken else Polygon()
    for site in sorted(sites, key=lambda o: o.id):
        area = clip(site.geom, region)
        if area is None:
            continue
        near = [blocked[i][0].buffer(blocked[i][1], quad_segs=2) for i in block_tree.query(area, predicate="dwithin",
                                                                                          distance=GAP_NEW_M)]
        free = area.difference(unary_union(near + [used.buffer(GAP_NEW_M)])) if near or not used.is_empty else area
        free = polygonal(free)
        if free is None:
            continue
        for k, house in enumerate(pack(free, depth, length, GAP_NEW_M), 1):
            f = Future()
            f.id, f.geom, f.tags, f.source, f.site = f"oks-{site.id}-{k}", house, {}, "inferred", site.id
            f.kind, f.levels = "residential", levels
            result.append(f)
            used = used.union(house)
    for f in result:
        f.cp = connection_point(f.geom, streets)
        era = "после 1985"
        f.flow, f.load = flow_of(heat_demand(f.kind, f.geom.area * f.levels, f.levels, era, t_design, new=True))
    return result


# ---------------------------------------------------------------- существующая теплосеть

CELL_M = 3.0
# стоимость прокладки 1 м трубы по типу земли (безразмерная, для выбора трасс): техническая полоса вдоль улицы 1,0 у
# магистральных улиц и 1,25 у местных и внутриквартальных проездов (на второй стороне улицы — +15 %), проезжая часть
# 3,0, двор и газон 4,0, соцобъект 5,0, парк 6,0, трамвай 8, ж/д и метро 20 (переход в футляре), мост 6, река 60
# (дюкер — только поперёк и там, где нет моста), территория станции 2,0; запретные территории непроходимы. Реальные и
# восстановленные трубы — 0,5: сеть идёт по ним. Сети кладут вдоль улиц и проездов, через двор — только последние
# метры до здания, поэтому двор вчетверо дороже полосы.
COST = {"open": 4.0, "social_area": 5.0, "park": 6.0, "major": 1.0, "minor": 1.25, "other_side": 1.15,
        "road": 3.0, "tram_tracks": 8.0, "railway": 20.0, "metro": 20.0, "bridge": 6.0, "water": 60.0, "plant": 2.0,
        "real": 0.5}
# ЦТП (man_made=heat_exchange_station из OSM): от магистрали к ЦТП — распределительная сеть, от ЦТП к зданиям
# квартала — внутриквартальная. ЦТП берёт здания в радиусе 500 м, ближние первыми, пока суммарный расход укладывается
# в пропускную способность Ду 200 (152 т/ч ≈ 12 Гкал/ч, типовой ЦТП 5–15 Гкал/ч); остальные здания — с ИТП,
# от распределительной сети напрямую
CTP_RADIUS_M, CTP_DN = 500.0, 200
# общие траншеи: после первого дерева ячейки под ним дешевеют (REUSE), и дерево строится заново — ветки к соседним
# зданиям сливаются в одну распределительную линию, а не идут веером (эвристика сетей с экономией на масштабе)
REUSE_PASSES, REUSE = 2, 0.6
# техническая полоса для сетей — до 8 м за кромкой проезжей части (тротуар и газон между дорогой и застройкой)
STRIP_M = 8.0
# отступ оси существующей трубы от стены здания: 2 м от наружной стенки канала (СП 42.13330.2016, табл. 12.5)
# плюс половина канала, принято 2,5 м; от перспективных зданий — 5 м (бесканальная прокладка, та же таблица)
CLEAR_EXISTING_M, CLEAR_FUTURE_M = 2.5, 5.0
# ветка к зданию кончается снаружи у стены, в 0,5 м от неё
WALL_GAP_M = 0.5
# камеры на достроенных магистралях (Ду от 500): шаг по реальным сетям города (CITIES[...]["chamber_step_m"]);
# участок длиннее MAIN_SPLIT_M делится на равные части не длиннее шага
MAIN_DN, MAIN_SPLIT_M = 500, 300.0
SIMPLIFY_M = (2.0, 1.0, 0.0)
MAX_DOWNSTREAM = 3


def side_preference(axes, heated):
    """Сторона улицы для сети: где больше отапливаемой площади в 40 м от проезжей части (+1 слева, −1 справа)."""
    tree = STRtree([b.geom for b in heated])
    result = {}
    for a in axes:
        score = []
        for sign in (1, -1):
            zone = a["axis"].buffer(sign * (a["width"] / 2 + 40.0), single_sided=True)
            score.append(sum(heated[i].geom.intersection(zone).area * heated[i].levels
                             for i in tree.query(zone, predicate="intersects")))
        result[a["id"]] = 1 if score[0] >= score[1] else -1
    return result


def cost_grid(region, layers, axes, heated, existing, future, real_lines, source, fence):
    grid = Grid(region.bounds, CELL_M, COST["open"])
    win = grid.window(region)
    inside = shapely.contains_xy(region, win[1], win[2])
    grid.cost[win[0]][~inside] = INF
    for kind in ("social_area", "park"):
        for g in layers.get(kind, []):
            grid.paint(g, COST[kind])
    sides = side_preference([a for a in axes if not a["tunnel"]], heated)
    for major in (False, True):
        for a in axes:
            if a["tunnel"] or a["bridge"] or (a["kind"] in MAJOR) != major:
                continue
            base = COST["major" if major else "minor"]
            for sign in (1, -1):
                strip = a["axis"].buffer(sign * (a["width"] / 2 + STRIP_M), single_sided=True)
                grid.paint(strip, base if sign == sides[a["id"]] else base * COST["other_side"])
    for kind in ("road", "tram_tracks", "railway", "metro"):
        for g in layers.get(kind, []):
            grid.paint(g, COST[kind])
    for line in real_lines:
        grid.paint(line.buffer(2.0), COST["real"])
    for g in layers.get("water", []):
        grid.paint(g, COST["water"])
    for a in axes:
        if a["bridge"]:
            grid.paint(a["axis"].buffer(a["width"] / 2), COST["bridge"])
    for g in layers.get("prohibited_site", []):
        grid.paint(g, INF)
    if fence is not None:
        # территория станции: выводы магистралей идут от главного корпуса к ограде по эстакадам и каналам
        grid.paint(fence, COST["plant"])
    for b in existing:
        grid.paint(b.geom.buffer(CLEAR_EXISTING_M, quad_segs=2), INF)
    for f in future:
        grid.paint(f.geom.buffer(CLEAR_FUTURE_M, quad_segs=2), INF)
    # у источника — выход коллектора: 8 м вокруг точки проходимы, даже если рядом корпус станции
    grid.paint(source.buffer(8.0), COST["plant"])
    return grid


def dn_for(flow, table):
    for row in table:
        if row["capacity_tph"] >= flow:
            return row["dn"]
    return table[-1]["dn"]


def inlet(grid, dist, building, blockers):
    """Ячейка подхода к зданию и конец ветки: свободная ячейка у стены с наименьшей стоимостью пути от источника
    плюс стоимость ветки через двор; ветка — прямой отрезок до ближайшей точки стены, не задевающий другие здания."""
    reach = CLEAR_EXISTING_M + 2.0 * grid.cell
    win = grid.window(building.geom, reach)
    if win is None:
        return None
    sl, gx, gy = win
    ks = (np.arange(sl[0].start, sl[0].stop)[:, None] * grid.nx + np.arange(sl[1].start, sl[1].stop)[None, :]).ravel()
    d = dist[ks]
    ok = np.isfinite(d)
    if not ok.any():
        return None
    ks, d, px, py = ks[ok], d[ok], gx.ravel()[ok], gy.ravel()[ok]
    wall = shapely.distance(building.geom, shapely.points(px, py))
    near = wall <= reach
    order = np.lexsort((ks[near], d[near] + COST["open"] * wall[near]))
    boundary = building.geom.boundary
    for i in order[:25]:
        k = int(ks[near][i])
        start = Point(grid.center(k))
        end = shapely.ops.nearest_points(boundary, start)[0]
        length = start.distance(end)
        if length <= WALL_GAP_M + 0.05:
            continue
        stop = Point(end.x + (start.x - end.x) * WALL_GAP_M / length, end.y + (start.y - end.y) * WALL_GAP_M / length)
        branch = LineString([start, stop])
        if any(blockers.geometries[j].intersects(branch) for j in blockers.query(branch)):
            continue
        return k, (stop.x, stop.y)
    return None


def split_line(coords, at):
    """Делит ломаную на расстоянии at от начала: (голова, хвост), точка деления — в обоих."""
    line = LineString(coords)
    point = line.interpolate(at)
    head = shapely.ops.substring(line, 0, at)
    tail = shapely.ops.substring(line, at, line.length)
    return list(head.coords), [(point.x, point.y)] + list(tail.coords)[1:]


def simplify(coords, blockers):
    """Ступеньки растра спрямляются Дугласом — Пекером, если спрямлённая линия не задевает зданий."""
    line = LineString(coords)
    for tol in SIMPLIFY_M:
        g = line.simplify(tol) if tol else line
        if not any(blockers.geometries[j].intersects(g) for j in blockers.query(g)):
            return list(g.coords)
    return coords


def window_grid(grid, geom, pad):
    """Часть растра вокруг geom с запасом pad, выровненная по сетке растра (центры ячеек те же)."""
    sl = grid.window(geom, pad)[0]
    j0, j1, i0, i1 = sl[0].start - 1, sl[0].stop + 1, sl[1].start - 1, sl[1].stop + 1
    sub = Grid.__new__(Grid)
    sub.cell = grid.cell
    sub.cost = grid.cost[j0:j1, i0:i1].copy()
    sub.cost[0, :] = sub.cost[-1, :] = sub.cost[:, 0] = sub.cost[:, -1] = INF
    sub.x0, sub.y0 = grid.x0 + i0 * grid.cell, grid.y0 + j0 * grid.cell
    sub.ny, sub.nx = sub.cost.shape
    return sub


def grow(grid, root, targets_of, passes):
    """Дерево кратчайших путей от ячейки root до целей targets_of(dist); после каждого прохода, кроме последнего,
    ячейки под деревом дешевеют (общие траншеи), и дерево строится заново."""
    for attempt in range(passes):
        dist, pred = dijkstra(grid, [root])
        targets = targets_of(np.asarray(dist))
        reached = {k: v for k, v in targets.items() if dist[k] < INF}
        parent, children = cell_tree(pred, root, sorted(reached))
        if attempt + 1 < passes:
            flat = grid.cost.ravel()
            used = np.fromiter(parent, dtype=np.int64)
            flat[used] = np.minimum(flat[used], flat[used] * REUSE)
    return dict(grid=grid, root=root, parent=parent, children=children, targets=targets, reached=reached)


def heat_network(trees, source, blockers, source_id):
    """Сеть из деревьев по растру: trees[0] — от источника, остальные — от ЦТП (корень дерева ЦТП совпадает с узлом
    ЦТП в дереве источника). Цели дерева: {ячейка: [(вид, нагрузка т/ч, конец ветки или None, метка)]}, вид —
    building (ветка к стене), exit (транзит за границу района), manhole (люк — камера), ctp (ЦТП — камера),
    pass (точка на реальной трубе). Возвращает участки и камеры с расходом и upstream_object_id."""
    main = trees[0]

    def key(t, c):
        return (0, trees[t]["alias"]) if t > 0 and c == trees[t]["root"] else (t, c)

    pos, attach, down = {}, defaultdict(list), defaultdict(list)
    for t, tree in enumerate(trees):
        nodes, edges = cell_edges(tree["parent"], tree["children"], tree["root"], tree["reached"])
        for n in nodes:
            pos.setdefault(key(t, n), tree["grid"].center(n))
        for n, items in tree["reached"].items():
            attach[key(t, n)] += items
            for kind, _, end, _ in items:
                if kind == "manhole":
                    pos[key(t, n)] = end
    pos[(0, main["root"])] = (source.x, source.y)
    for t, tree in enumerate(trees):
        _, edges = cell_edges(tree["parent"], tree["children"], tree["root"], tree["reached"])
        for u, v, path in edges:
            ku, kv = key(t, u), key(t, v)
            down[ku].append((kv, [pos[ku]] + [tree["grid"].center(c) for c in path[1:-1]] + [pos[kv]]))
    for u in down:
        down[u].sort(key=lambda item: item[0])
    start = (0, main["root"])

    def buildings_at(n):
        return [it for it in attach.get(n, []) if it[0] == "building"]

    def is_chamber(n):
        items = attach.get(n, [])
        loaded = any(it[0] != "building" and it[1] > 0 for it in items) and down[n]
        return n != start and (len(down[n]) + len(buildings_at(n)) >= 2 or loaded
                               or any(it[0] in ("manhole", "ctp") for it in items))

    segments, chambers = [], []
    stack = [(start, source_id)]
    while stack:
        n, up_id = stack.pop()
        branches = [(coords, v) for v, coords in down[n]]
        branches += [([pos[n], it[2]], None, it) for it in buildings_at(n)] if n != start else []
        for branch in branches:
            coords, m = list(branch[0]), branch[1]
            load, label, end_chamber = 0.0, None, None
            if m is None:
                load, label = branch[2][1], branch[2][3]
            else:
                while not is_chamber(m) and len(down[m]) == 1 and not buildings_at(m):
                    v, more = down[m][0]
                    coords += more[1:]
                    m = v
                if is_chamber(m):
                    end_chamber = m
                else:
                    for kind, flow, end, lab in attach.get(m, []):
                        load += flow
                        label = lab
                        if kind == "building":
                            coords.append(end)
            seg = dict(id=f"hn-{len(segments) + 1}", coords=simplify(coords, blockers), up=up_id, load=load,
                       label=label, source="inferred")
            segments.append(seg)
            if end_chamber is not None:
                items = attach.get(end_chamber, [])
                ref = next((it[3] for it in items if it[0] in ("manhole", "ctp")), None)
                kinds = {it[0] for it in items}
                level = "real" if "ctp" in kinds else "restored" if "manhole" in kinds else "inferred"
                extra = sum(it[1] for it in items if it[0] != "building")
                chambers.append(dict(id=f"hc-{len(chambers) + 1}", pos=pos[end_chamber], up=seg["id"],
                                     source=level, osm=ref, load=extra, ctp="ctp" in kinds))
                seg["end"] = chambers[-1]["id"]
                stack.append((end_chamber, chambers[-1]["id"]))
    unreached = [lab for tree in trees for k, items in tree["targets"].items() if k not in tree["reached"]
                 for *_, lab in items]
    return segments, chambers, unreached


def finish_network(segments, chambers, table, main_step):
    """Расход участка — сумма нагрузок ниже по сети. Ду: у восстановленного участка — Ду схемы (dn_hint), но не меньше
    нужного по расходу (пропускная способность из rules.json) и не больше Ду участка выше (ошибки распознавания
    подписей); у достроенного — наименьший по расходу. Участки идут от источника к концам, поэтому Ду не растёт.
    Камер с больше чем тремя участками вниз не бывает (CONSTRAINTS §5): лишние участки уходят во вторую камеру в 2,5 м
    по одной из веток. На достроенных магистралях (Ду от 500) — камеры с шагом main_step. Возвращает участки, камеры
    и число исправлений Ду схемы: (поднято по расходу, опущено до Ду выше)."""
    counter = {"hn": len(segments), "hc": len(chambers)}

    def new_id(kind):
        counter[kind] += 1
        return f"{kind}-{counter[kind]}"

    chamber_by_id = {c["id"]: c for c in chambers}
    below = defaultdict(list)
    for s in segments:
        below[s["up"]].append(s)
    for s in reversed(segments):
        flow = s["load"] + sum(t["flow"] for t in below[s["id"]])
        if "end" in s:
            c = chamber_by_id[s["end"]]
            flow += c["load"] + sum(t["flow"] for t in below[c["id"]])
        s["flow"] = flow
    by_id = {s["id"]: s for s in segments}
    raised = lowered = 0
    for s in segments:
        feed = by_id.get(chamber_by_id[s["up"]]["up"]) if s["up"] in chamber_by_id else by_id.get(s["up"])
        need = dn_for(s["flow"], table)
        ceiling = feed["dn"] if feed else 10 ** 6
        hint = s.get("dn_hint") or need
        s["dn"] = max(need, min(hint, ceiling))
        raised += hint < need
        lowered += hint > ceiling and hint > need
    # камера с больше чем тремя участками вниз: вторая камера рядом
    for c in list(chambers):
        outs = below[c["id"]]
        while len(outs) > MAX_DOWNSTREAM:
            host = max(outs[2:], key=lambda t: (LineString(t["coords"]).length, t["id"]))
            at = min(2.5, 0.4 * LineString(host["coords"]).length)
            head, tail = split_line(host["coords"], at)
            link = dict(id=new_id("hn"), coords=head, up=c["id"], load=0.0, label=None, source=host["source"],
                        flow=0.0, dn=host["dn"])
            c2 = dict(id=new_id("hc"), pos=tail[0], up=link["id"], source="inferred", osm=None, load=0.0, ctp=False)
            moved = [t for t in outs[2:] if t is not host]
            host["coords"], host["up"] = tail, c2["id"]
            for t in moved:
                t["coords"] = [tail[0]] + t["coords"][1:]
                t["up"] = c2["id"]
            link["flow"] = host["flow"] + sum(t["flow"] for t in moved)
            link["dn"] = max([dn_for(link["flow"], table)] + [t["dn"] for t in moved + [host]])
            segments.append(link)
            chambers.append(c2)
            chamber_by_id[c2["id"]] = c2
            link["end"] = c2["id"]
            below[c["id"]] = outs[:2] + [link]
            below[c2["id"]] = [host] + moved
            outs = below[c2["id"]]
            c = c2
    # камеры по длине магистралей
    for s in list(segments):
        line = LineString(s["coords"])
        if s["dn"] < MAIN_DN or line.length <= MAIN_SPLIT_M or s["source"] != "inferred":
            continue
        parts = math.ceil(line.length / main_step)
        step = line.length / parts
        rest, prev, end = s["coords"], s, s.get("end")
        for k in range(1, parts):
            head, rest = split_line(rest, step)
            prev["coords"] = head
            c = dict(id=new_id("hc"), pos=rest[0], up=prev["id"], source="inferred", osm=None, load=0.0, ctp=False)
            prev["end"] = c["id"]
            chambers.append(c)
            nxt = dict(id=new_id("hn"), coords=rest, up=c["id"], load=s["load"], label=s["label"],
                       source=s["source"], flow=s["flow"], dn=s["dn"])
            s["load"] = 0.0
            segments.append(nxt)
            prev = nxt
        if end is not None:
            prev["end"] = end
            chamber_by_id[end]["up"] = prev["id"]
    by_id = {s["id"]: s for s in segments}
    outs = defaultdict(list)
    for s in segments:
        outs[s["up"]].append(s["dn"])
    for c in chambers:
        c["dn"] = max(([by_id[c["up"]]["dn"]] if c["up"] in by_id else []) + outs[c["id"]])
    return segments, chambers, (raised, lowered)


# ---------------------------------------------------------------- восстановленная сеть (схемы теплоснабжения)

# стыки схемы: концы участков ближе 1 м — один узел (Разведчик: сеть связна при допуске 1 м); камеры схемы — к узлу
# или трубе в пределах 3,5 м (СКО привязки схемы 2,8–3,0 м); ввод потребителя — к зданию в пределах 15 м
RESTORED_SNAP_M, CHAMBER_SNAP_M, CONSUMER_SNAP_M = 1.0, 3.5, 15.0
# разомкнутое кольцо: конец участка в 1,5 м от камеры (дальше допуска стыка 0,5 м, docs/interpretation.md)
RING_GAP_M = 1.5
# заход трассы в здание до 5 м — ошибка привязки схемы, больше — транзит через техподполье
SMALL_INTRUSION_M = 5.0
# обход здания: растр 1 м, стоимость 1 + отклонение от исходной трассы / 5 м, здания с отступом 1 м непроходимы
DETOUR_CELL_M, DETOUR_PAD_M, DETOUR_CLEAR_M = 1.0, 40.0, 1.0
HOLE_M = 150.0
# тупик схемы с Ду от 300 в 150 м от края листа и без ввода потребителя — обрез трубы рамкой листа: магистраль
# продолжается за ним и несёт транзит
SHEET_EDGE_DN, SHEET_EDGE_M = 300, 150.0
# загрузка участка по отношению к пропускной способности Ду без подписи расхода: медиана по схеме ТЭЦ-16 (МОЭК) 0,25
DEFAULT_USAGE = 0.25


def load_restored(city):
    """Сеть, восстановленная Разведчиком по схеме теплоснабжения: data/cities/raw/<город>/restored_heat.geojson."""
    path = RAW / city / "restored_heat.geojson"
    if not path.exists():
        return None
    result = defaultdict(list)
    for f in json.loads(path.read_text(encoding="utf-8"))["features"]:
        p = f["properties"]
        g = utm(shapely.geometry.shape(f["geometry"]))
        result[p.get("_class") or p.get("object_type")].append((g, p))
    return result


def graph_from_lines(lines, chambers, region):
    """Граф сети: участки схемы, обрезанные по району; концы ближе RESTORED_SNAP_M — один узел, конец у середины
    другого участка — Т-стык (участок режется), камеры схемы — в узлах или с разрезом участка."""
    pieces = []
    for g, p in lines:
        if not g.intersects(region):
            continue
        c = g if region.contains(g) else g.intersection(region)
        pieces += [(part, p) for part in getattr(c, "geoms", [c]) if part.geom_type == "LineString" and part.length > 0.5]
    nodes, cells = [], defaultdict(list)

    def node_at(x, y, radius=RESTORED_SNAP_M):
        cx, cy = int(x // RESTORED_SNAP_M), int(y // RESTORED_SNAP_M)
        best = None
        reach = int(math.ceil(radius / RESTORED_SNAP_M))
        for dx in range(-reach, reach + 1):
            for dy in range(-reach, reach + 1):
                for n in cells.get((cx + dx, cy + dy), ()):
                    d = math.dist(nodes[n], (x, y))
                    if d <= radius and (best is None or d < best[0]):
                        best = (d, n)
        return best[1] if best else None

    def new_node(x, y):
        nodes.append((x, y))
        cells[(int(x // RESTORED_SNAP_M), int(y // RESTORED_SNAP_M))].append(len(nodes) - 1)
        return len(nodes) - 1

    ends = []
    for g, _ in pieces:
        pair = []
        for x, y in (g.coords[0], g.coords[-1]):
            n = node_at(x, y)
            pair.append(n if n is not None else new_node(x, y))
        ends.append(pair)
    degree = defaultdict(int)
    for u, v in ends:
        degree[u] += 1
        degree[v] += 1
    tree = STRtree([g for g, _ in pieces])
    splits = defaultdict(list)
    for n in range(len(nodes)):
        if degree[n] != 1:
            continue
        pt = Point(nodes[n])
        for j in sorted(tree.query(pt, predicate="dwithin", distance=RESTORED_SNAP_M)):
            g = pieces[j][0]
            d = g.project(pt)
            if n not in ends[j] and RESTORED_SNAP_M < d < g.length - RESTORED_SNAP_M:
                splits[j].append((d, n))
                break
    chamber_at = {}
    node_tree = STRtree([Point(xy) for xy in nodes])
    for pt, p in chambers:
        near = node_tree.query_nearest(pt, max_distance=CHAMBER_SNAP_M)
        if len(near):
            chamber_at.setdefault(int(near[0]), p)
            continue
        hit = tree.query_nearest(pt, max_distance=CHAMBER_SNAP_M)
        if len(hit):
            j = int(hit[0])
            g = pieces[j][0]
            d = g.project(pt)
            if 0.5 < d < g.length - 0.5:
                on = g.interpolate(d)
                n = new_node(on.x, on.y)
                splits[j].append((d, n))
                chamber_at[n] = p
    edges = []
    for j, (g, p) in enumerate(pieces):
        prev_d, prev_n = 0.0, ends[j][0]
        for d, n in sorted(splits[j]) + [(g.length, ends[j][1])]:
            if n == prev_n or d - prev_d < 0.05:
                continue
            coords = list(shapely.ops.substring(g, prev_d, d).coords)
            coords[0], coords[-1] = nodes[prev_n], nodes[n]
            edges.append(dict(u=prev_n, v=n, coords=coords, props=p, source=p.get("_source", "restored")))
            prev_d, prev_n = d, n
    exits = {n for n, xy in enumerate(nodes) if region.exterior.distance(Point(xy)) < 0.05}
    return nodes, edges, chamber_at, exits


def detour(coords, houses, house_tree):
    """Трасса в обход зданий рядом с исходной: кратчайший путь по растру 1 м, где стоимость растёт с отклонением
    от исходной линии, а здания с отступом DETOUR_CLEAR_M непроходимы. None — обойти нельзя."""
    line = LineString(coords)
    x0, y0, x1, y1 = line.buffer(DETOUR_PAD_M).bounds
    grid = Grid((x0, y0, x1, y1), DETOUR_CELL_M, 1.0)
    sl, gx, gy = grid.window(box(x0, y0, x1, y1))
    dev = shapely.distance(line, shapely.points(gx, gy))
    grid.cost[sl] = 1.0 + np.minimum(dev, DETOUR_PAD_M) / 5.0
    for i in house_tree.query(line.buffer(DETOUR_PAD_M)):
        grid.paint(houses[i].buffer(DETOUR_CLEAR_M, quad_segs=2), INF)
    passable = np.where(np.isfinite(grid.cost.ravel()), 0.0, INF)
    ends = [nearest_cell(grid, passable, Point(xy), 6.0) for xy in (coords[0], coords[-1])]
    if None in ends:
        return None
    dist, pred = dijkstra(grid, [ends[0]])
    if dist[ends[1]] == INF:
        return None
    path, c = [], ends[1]
    while c >= 0:
        path.append(grid.center(c))
        c = pred[c]
    route = [coords[0]] + path[::-1] + [coords[-1]]
    near = [houses[i] for i in house_tree.query(line.buffer(DETOUR_PAD_M))]
    for tol in (1.0, 0.5, 0.0):
        g = LineString(route).simplify(tol) if tol else LineString(route)
        if not any(h.intersects(g) for h in near):
            return list(g.coords)
    return None


def fix_crossings(nodes, edges, houses, stats):
    """Трассы схемы сквозь здания: узел ветвления внутри здания выносится наружу, тупиковая ветка к зданию кончается
    у стены снаружи, прочие участки обходят здание ближайшим путём. Помеченные _adjusted участки — восстановленные с
    поправкой; обойти нельзя — участок остаётся как в схеме, это записывается."""
    house_tree = STRtree(houses)
    degree = defaultdict(int)
    for e in edges:
        degree[e["u"]] += 1
        degree[e["v"]] += 1
    moved = 0
    for n, xy in enumerate(nodes):
        if degree[n] < 2:
            continue
        pt = Point(xy)
        for i in house_tree.query(pt, predicate="within"):
            ring = houses[i].buffer(1.0, quad_segs=2).exterior
            out = ring.interpolate(ring.project(pt))
            nodes[n] = (out.x, out.y)
            moved += 1
            break
    for e in edges:
        e["coords"][0], e["coords"][-1] = nodes[e["u"]], nodes[e["v"]]
    stats.update(nodes_moved=moved, small=0, small_m=0.0, large=0, large_m=0.0, trimmed=0, detoured=0, kept=0, kept_m=0.0)
    for e in edges:
        line = LineString(e["coords"])
        hits = [houses[i] for i in house_tree.query(line, predicate="intersects")]
        if not hits:
            continue
        inside = sum(line.intersection(h).length for h in hits)
        big = max(line.intersection(h).length for h in hits) > SMALL_INTRUSION_M
        stats["large" if big else "small"] += 1
        stats["large_m" if big else "small_m"] += inside
        leaf = next((end for end, n in ((0, e["u"]), (-1, e["v"])) if degree[n] == 1), None)
        if leaf is not None and any(h.contains(Point(e["coords"][leaf])) for h in hits):
            # ветка к зданию: конец у стены снаружи, WALL_GAP_M до стены
            seq = e["coords"] if leaf == -1 else e["coords"][::-1]
            g = LineString(seq)
            entry = min((g.project(Point(xy)) for h in hits
                         for xy in shapely.get_coordinates(g.intersection(h.boundary))), default=None)
            if entry is None:
                # отрезок целиком внутри здания, оба конца висячие: такой обрывок схемы не нужен
                e["drop"] = True
                stats["dropped"] = stats.get("dropped", 0) + 1
                continue
            if entry > WALL_GAP_M + 0.5:
                seq = list(shapely.ops.substring(g, 0.0, entry - WALL_GAP_M).coords)
                e["coords"] = seq if leaf == -1 else seq[::-1]
                end_node = e["v"] if leaf == -1 else e["u"]
                nodes[end_node] = e["coords"][-1] if leaf == -1 else e["coords"][0]
                e["adjusted"] = True
                stats["trimmed"] += 1
                line = LineString(e["coords"])
                if not any(houses[i].intersects(line) for i in house_tree.query(line)):
                    continue
        route = detour(e["coords"], houses, house_tree)
        if route is None:
            # обойти нельзя (плотная застройка, узел в проезде под зданием): транзит через здание, как в схеме
            e["through"] = True
            stats["kept"] += 1
            stats["kept_m"] += sum(LineString(e["coords"]).intersection(h).length for h in hits)
            continue
        e["coords"], e["adjusted"] = route, True
        stats["detoured"] += 1
    edges[:] = [e for e in edges if not e.get("drop")]


def bridge_components(nodes, edges, root, reach, houses, house_tree):
    """Связность схемы: висячий конец ближе reach к участку другой компоненты стягивается к нему короткой перемычкой;
    оставшиеся компоненты по очереди подключаются к ближайшей точке уже связанной с источником сети. Перемычки —
    inferred, прямые или в обход зданий; компонента, которую не подключить, не пересекая зданий, выбрасывается (её
    здания достроенная сеть подключит как «дыры»). Возвращает (стянуто концов, подключено компонент, выброшено)."""
    def components():
        parent = list(range(len(nodes)))

        def find(a):
            while parent[a] != a:
                parent[a] = parent[parent[a]]
                a = parent[a]
            return a
        for e in edges:
            parent[find(e["u"])] = find(e["v"])
        return [find(n) for n in range(len(nodes))]

    def route(n, i):
        """Перемычка от узла n к ближайшей точке участка i: (место на участке, ломаная) или None."""
        line = LineString(edges[i]["coords"])
        at = line.project(Point(nodes[n]))
        on = line.interpolate(at)
        coords = [nodes[n], (on.x, on.y)]
        if any(houses[k].intersects(LineString(coords)) for k in house_tree.query(LineString(coords))):
            coords = detour(coords, houses, house_tree)
        return None if coords is None else (at, coords)

    def link(n, i, at, coords):
        e = edges[i]
        line = LineString(e["coords"])
        if at < 0.5 or at > line.length - 0.5:
            m = e["u"] if at < 0.5 else e["v"]
        else:
            nodes.append(coords[-1])
            m = len(nodes) - 1
            head, tail = split_line(e["coords"], at)
            head[-1] = tail[0] = nodes[m]
            edges.append(dict(u=m, v=e["v"], coords=tail, props=e["props"], source=e["source"],
                              adjusted=e.get("adjusted", False), through=e.get("through", False)))
            e["v"], e["coords"] = m, head
        coords = list(coords)
        coords[-1] = nodes[m]
        edges.append(dict(u=n, v=m, coords=coords, props={}, source="inferred"))

    def candidates(pt, pool, tree, radius):
        hits = tree.query(pt, predicate="dwithin", distance=radius)
        return sorted((pool[k][1].distance(pt), pool[k][0]) for k in hits)[:12]

    degree = defaultdict(int)
    for e in edges:
        degree[e["u"]] += 1
        degree[e["v"]] += 1
    comp = components()
    joined = 0
    pool = [(i, LineString(e["coords"])) for i, e in enumerate(edges)]
    tree = STRtree([g for _, g in pool])
    for n in sorted(k for k in range(len(nodes)) if degree[k] == 1):
        pt = Point(nodes[n])
        for _, i in candidates(pt, pool, tree, reach):
            if comp[edges[i]["u"]] == comp[n]:
                continue
            found = route(n, i)
            if found:
                link(n, i, *found)
                joined += 1
                comp = components()
                break
    attached, dropped = 0, 0
    while True:
        comp = components()
        main = comp[root]
        rest = sorted({comp[n] for e in edges for n in (e["u"], e["v"]) if comp[n] != main})
        if not rest:
            break
        pool = [(i, LineString(e["coords"])) for i, e in enumerate(edges) if comp[e["u"]] == main]
        tree = STRtree([g for _, g in pool])
        order = []
        for c in rest:
            members = sorted({m for e in edges for m in (e["u"], e["v"]) if comp[m] == c})
            gap = min(float(tree.query_nearest(Point(nodes[m]), return_distance=True)[1][0]) for m in members)
            order.append((gap, c, members))
        gap, c, members = min(order)
        done = False
        for m in sorted(members, key=lambda m: (float(tree.query_nearest(Point(nodes[m]), return_distance=True)[1][0]), m))[:10]:
            pt = Point(nodes[m])
            for _, i in candidates(pt, pool, tree, gap + 60.0):
                found = route(m, i)
                if found:
                    link(m, i, *found)
                    done = True
                    break
            if done:
                break
        if done:
            attached += 1
        else:
            edges[:] = [e for e in edges if comp[e["u"]] != c]
            dropped += 1
    return joined, attached, dropped


def contract_short(nodes, edges, chamber_at, exits, limit=0.05):
    """Участки короче limit (узлы совпали после выноса из зданий и стяжек) стягиваются в один узел."""
    parent = list(range(len(nodes)))

    def find(a):
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a
    for e in edges:
        if LineString(e["coords"]).length < limit:
            parent[find(e["v"])] = find(e["u"])
    kept = []
    for e in edges:
        e["u"], e["v"] = find(e["u"]), find(e["v"])
        if e["u"] != e["v"] and LineString(e["coords"]).length >= limit:
            e["coords"][0], e["coords"][-1] = nodes[e["u"]], nodes[e["v"]]
            kept.append(e)
    edges[:] = kept
    for n in list(chamber_at):
        chamber_at.setdefault(find(n), chamber_at[n])
    exits |= {find(n) for n in exits}
    return find


def restored_tree(nodes, edges, root, chamber_at, exits, source_id):
    """Дерево от источника по графу (кратчайшие пути по длине); ребро вне дерева замыкает кольцо — оно размыкается
    у камеры: участок остаётся ветвью от ближнего к источнику конца и не доходит RING_GAP_M до камеры на дальнем.
    Возвращает участки и камеры в формате heat_network (до finish_network) и число разомкнутых колец."""
    import heapq
    adj = defaultdict(list)
    for k, e in enumerate(edges):
        length = LineString(e["coords"]).length
        adj[e["u"]].append((length, k, e["v"]))
        adj[e["v"]].append((length, k, e["u"]))
    dist, via = {root: 0.0}, {}
    heap = [(0.0, root)]
    while heap:
        d, n = heapq.heappop(heap)
        if d > dist[n]:
            continue
        for length, k, m in sorted(adj[n]):
            if d + length < dist.get(m, INF):
                dist[m] = d + length
                via[m] = k
                heapq.heappush(heap, (d + length, m))
    in_tree = set(via.values())
    children = defaultdict(list)
    for m, k in via.items():
        e = edges[k]
        parent = e["u"] if e["v"] == m else e["v"]
        coords = e["coords"] if e["u"] == parent else e["coords"][::-1]
        children[parent].append((k, m, coords))
    rings = 0
    for k, e in enumerate(edges):
        if k in in_tree or e["u"] not in dist or e["v"] not in dist:
            continue
        # кольцо: режем у камеры, при двух камерах или без них — у дальнего от источника конца
        ends = sorted((e["u"], e["v"]), key=lambda n: (n in chamber_at, dist[n], n))
        keep, cut = ends
        coords = e["coords"] if e["u"] == keep else e["coords"][::-1]
        line = LineString(coords)
        if line.length <= 2 * RING_GAP_M:
            continue
        coords = list(shapely.ops.substring(line, 0.0, line.length - RING_GAP_M).coords)
        children[keep].append((k, None, coords))
        rings += 1
    for n in children:
        children[n].sort(key=lambda item: item[0])
    chamber = {n for n in dist if n != root and (n in chamber_at or len(children[n]) >= 2)}
    segments, chambers, seg_of = [], [], {}
    queue = [(root, source_id)]
    while queue:
        n, up = queue.pop(0)
        for k, m, coords in children[n]:
            e = edges[k]
            p = e["props"]
            seg = dict(id=f"hn-{len(segments) + 1}", coords=coords, up=up, load=0.0, label=None, source=e["source"],
                       edge=k, node=m, adjusted=e.get("adjusted", False), ring=m is None,
                       through=e.get("through", False))
            if p.get("diameter"):
                seg["dn_hint"] = int(p["diameter"])
            if p.get("label_flow_tph"):
                seg["label_flow"] = float(p["label_flow_tph"])
            if p.get("_kind"):
                seg["kind"] = p["_kind"]
            segments.append(seg)
            if m is None:
                continue
            if m in chamber:
                ch = chamber_at.get(m, {})
                chambers.append(dict(id=f"hc-{len(chambers) + 1}", pos=nodes[m], up=seg["id"], osm=ch.get("name"),
                                     source=ch.get("_source", "restored") if m in chamber_at else "inferred",
                                     load=0.0, ctp=False))
                seg["end"] = chambers[-1]["id"]
                queue.append((m, chambers[-1]["id"]))
            else:
                queue.append((m, seg["id"]))
            seg_of[m] = seg
    unreached = [k for k, e in enumerate(edges) if e["u"] not in dist]
    return segments, chambers, rings, unreached


def accumulate(segments, chambers):
    """Расход каждого участка: нагрузка на нём плюс всё ниже по сети."""
    chamber_by_id = {c["id"]: c for c in chambers}
    below = defaultdict(list)
    for s in segments:
        below[s["up"]].append(s)
    for s in reversed(segments):
        flow = s["load"] + sum(t["flow"] for t in below[s["id"]])
        if "end" in s:
            c = chamber_by_id[s["end"]]
            flow += c["load"] + sum(t["flow"] for t in below[c["id"]])
        s["flow"] = flow
    return below


def restored_network(restored, region, source, source_id, heated, existing, future, layers, table, stats, bridge_m):
    """Существующая сеть по схеме теплоснабжения (restored): граф участков схемы в районе, связность, обход зданий,
    ветки к зданиям в «дырах» дальше 150 м (inferred), дерево от источника с размыканием колец. Нагрузка зданий —
    на ближайший участок (у зданий с вводом схемы — на участок у ввода); магистрали, уходящие за границу района, несут
    транзит — расчётный расход схемы (label_flow_tph) на этом участке. Удельная нагрузка калибруется по расчётным
    расходам схемы: k — медиана «расход схемы / расход по формуле» на участках без транзита."""
    nodes, edges, chamber_at, exits = graph_from_lines(restored["heat_network"], restored.get("heat_chamber", []),
                                                      region)
    houses = [b.geom for b in existing] + [f.geom for f in future]
    house_tree = STRtree(houses)
    stats["restored_km"] = round(sum(LineString(e["coords"]).length for e in edges) / 1000, 1)
    stats["restored_in_buildings_m"] = round(sum(LineString(e["coords"]).intersection(houses[i]).length
                                                 for e in edges for i in house_tree.query(LineString(e["coords"]),
                                                                                         predicate="intersects")), 1)
    fix_crossings(nodes, edges, houses, stats)
    points = [g for g, _ in restored.get("source", [])] or [source]
    anchor = min(points, key=lambda g: (g.distance(source), g.x))
    root = min(range(len(nodes)), key=lambda n: (math.dist(nodes[n], (anchor.x, anchor.y)), n))
    stats["ends_joined"], stats["components_attached"], stats["components_dropped"] = bridge_components(
        nodes, edges, root, bridge_m, houses, house_tree)
    root = contract_short(nodes, edges, chamber_at, exits)(root)
    prohibited = unary_union(layers.get("prohibited_site", [])) if layers.get("prohibited_site") else Polygon()
    scheme = [LineString(e["coords"]) for e in edges]
    net_tree = STRtree(scheme)
    holes = [b for b in heated if not b.geom.intersects(prohibited)
             and len(net_tree.query(b.geom, predicate="dwithin", distance=HOLE_M)) == 0]
    stats["holes"] = len(holes)
    hole_ids = {b.id for b in holes}
    segments, chambers, rings, lost = restored_tree(nodes, edges, root, chamber_at, exits, source_id)
    stats["rings_opened"] = rings
    stats["edges_unreached"] = len(lost)
    # нагрузки зданий
    consumers = [g for g, _ in restored.get("consumer", []) if region.contains(g)]
    bld_tree = STRtree([b.geom for b in heated])
    fed_by = {}
    for g in consumers:
        k = bld_tree.query_nearest(g, max_distance=CONSUMER_SNAP_M)
        if len(k):
            fed_by.setdefault(heated[int(k[0])].id, g)
    stats["consumers"], stats["consumer_buildings"] = len(consumers), len(fed_by)
    seg_geoms = [LineString(s["coords"]) for s in segments]
    seg_tree = STRtree(seg_geoms)
    attach = []
    for b in heated:
        if b.id in hole_ids:
            continue
        probe = fed_by.get(b.id, b.geom)
        k = seg_tree.query_nearest(probe, max_distance=HOLE_M)
        if len(k):
            attach.append((segments[int(k[0])], b))
    stats["buildings_on_network"] = len(attach)
    for seg, b in attach:
        seg["load"] += b.flow
    # транзит за границу района: расчётный расход схемы или средняя загрузка участков схемы по пропускной способности
    capacity = {r["dn"]: r["capacity_tph"] for r in table}
    ratios = sorted(s["label_flow"] / capacity[s["dn_hint"]] for s in segments
                    if s.get("label_flow") and s.get("dn_hint") in capacity)
    usage = ratios[len(ratios) // 2] if ratios else DEFAULT_USAGE
    transit = 0.0
    ends = {s["up"] for s in segments} | {s.get("end") for s in segments}
    by_id = {s["id"]: s for s in segments}
    up_seg = {c["id"]: c["up"] for c in chambers}

    def dn_known(s):
        """Ду схемы участка, а без подписи у магистрали — Ду ближайшего подписанного участка выше."""
        while s is not None:
            if s.get("dn_hint"):
                return s["dn_hint"]
            if s.get("kind") != "магистраль":
                return None
            s = by_id.get(up_seg.get(s["up"], s["up"]))
        return None

    # край листа схемы: выпуклая оболочка всех участков схемы (лист обрезает трубы по рамке)
    sheet = shapely.MultiPoint([xy for g, _ in restored["heat_network"] for xy in g.coords]).convex_hull.exterior
    consumer_tree = STRtree([g for g, _ in restored.get("consumer", [])] or [Point(0, 0)])
    for s in segments:
        leaf = s["id"] not in ends and "end" not in s and not s.get("ring")
        dn = dn_known(s)
        end = Point(s["coords"][-1])
        at_edge = leaf and sheet.distance(end) <= SHEET_EDGE_M and not len(
            consumer_tree.query(end, predicate="dwithin", distance=CONSUMER_SNAP_M))
        # граница района или край листа схемы: магистраль (Ду от 300) обрывается, а труба идёт дальше
        if (s.get("node") in exits or at_edge) and (dn or 0) >= SHEET_EDGE_DN:
            t = s.get("label_flow") or usage * capacity.get(dn, 0.0)
            s["transit"] = t
            transit += t
    stats["exits"], stats["transit_tph"], stats["usage"] = sum(1 for s in segments if "transit" in s), round(transit, 1), round(usage, 3)
    # калибровка удельной нагрузки по расчётным расходам схемы на участках без транзита ниже
    below = accumulate(segments, chambers)
    impure = {}
    for s in reversed(segments):
        down = below[s["id"]] + (below[s["end"]] if "end" in s else [])
        impure[s["id"]] = "transit" in s or s["source"] == "inferred" or any(impure[t["id"]] for t in down)
    pairs = [(s["label_flow"], s["flow"]) for s in segments if s.get("label_flow") and not impure[s["id"]]
             and s["flow"] > 1.0]
    k_cal = float(np.median([a / b for a, b in pairs])) if len(pairs) >= 20 else 1.0
    q = np.percentile([a / b for a, b in pairs], [25, 75]) if pairs else (0, 0)
    stats["calibration"] = dict(pairs=len(pairs), k=round(k_cal, 3), q25=round(float(q[0]), 3), q75=round(float(q[1]), 3))
    for s in segments:
        s["load"] = s["load"] * k_cal + s.get("transit", 0.0)
    return segments, chambers, lost, Point(nodes[root]), k_cal, holes, scheme


# ---------------------------------------------------------------- коммуникации по профилю улицы

# поперечный профиль улицы: все четыре сети — на стороне без теплосети (теплосеть отделена от них проезжей частью).
# Смещение оси от кромки проезжей части: кабель 1,5 м, водопровод 2,3 м, канализация 4,0 м, газопровод 5,3 м.
# Нормы (СП 42.13330.2016): до бортового камня (табл. 12.5) — водопровод 2,0 м, канализация, газопровод и кабель
# 1,5 м; между сетями в свету (табл. 12.6) — кабель–водопровод 0,5 м, водопровод–канализация 1,5 м (Ду до 200),
# канализация–газопровод низкого давления 1,0 м, кабель–газопровод 1,0 м; межосевые расстояния выше норм в свету
# на полусумму диаметров (≈0,3 м). До фундаментов зданий (табл. 12.5): кабель 0,6 м, водопровод 5 м, канализация
# 3 м, газопровод низкого давления 2 м — ближе линия обрезается.
PROFILE = (("power_cable", "cab", 1.5, 0.6), ("water_supply", "ws", 2.3, 5.0),
           ("sewer", "sew", 4.0, 3.0), ("gas_pipeline", "gas", 5.3, 2.0))
PROFILE_MIN_M = 10.0
REAL_NEAR_M = 10.0


def network_sides(axes, segments, fallback):
    """Сторона улицы с теплосетью: где больше её длины в полосе до 25 м за кромкой (+1 слева, −1 справа);
    без сети рядом — сторона по застройке (fallback)."""
    lines = [LineString(s["coords"]) for s in segments]
    tree = STRtree(lines)
    result = {}
    for a in axes:
        length = []
        for sign in (1, -1):
            zone = a["axis"].buffer(sign * (a["width"] / 2 + 25.0), single_sided=True)
            length.append(sum(lines[i].intersection(zone).length for i in tree.query(zone, predicate="intersects")))
        result[a["id"]] = fallback.get(a["id"], 1) if max(length) < 1.0 else (1 if length[0] >= length[1] else -1)
    return result


def profile_utilities(axes, sides, existing, future, water, real_items, region):
    """Газ, кабели, водопровод и канализация вдоль улиц, где их нет в OSM, по типовому поперечному профилю."""
    houses = [b.geom for b in existing] + [f.geom for f in future]
    house_tree = STRtree(houses)
    water_tree = STRtree(water) if water else None
    real = defaultdict(list)
    for g, props in real_items:
        real[props["restriction_type"]].append(g)
    real_tree = {k: STRtree(v) for k, v in real.items()}
    items = []
    for a in axes:
        if a["tunnel"] or a["bridge"] or a["kind"] in ("motorway", "motorway_link") or a["service"] in MINOR_SERVICE:
            continue
        for rtype, prefix, offset, clear in PROFILE:
            sign = -sides.get(a["id"], 1)
            line = linear(a["axis"].offset_curve(sign * (a["width"] / 2 + offset), quad_segs=2))
            if line is None:
                continue
            cut = [houses[i].buffer(clear, quad_segs=2) for i in house_tree.query(line, predicate="dwithin",
                                                                                  distance=clear)]
            if water_tree is not None:
                cut += [water[i] for i in water_tree.query(line, predicate="intersects")]
            if rtype in real_tree:
                cut += [real[rtype][i].buffer(REAL_NEAR_M) for i in real_tree[rtype].query(
                    line, predicate="dwithin", distance=REAL_NEAR_M)]
            if cut:
                line = linear(line.difference(unary_union(cut)))
            line = clip(line, region)
            if line is None:
                continue
            parts = [p for p in getattr(line, "geoms", [line]) if p.length >= PROFILE_MIN_M]
            if parts:
                g = parts[0] if len(parts) == 1 else MultiLineString(parts)
                items.append(restriction(f"{prefix}-{a['id']}", rtype, g, "inferred"))
    return items


# ---------------------------------------------------------------- источник и транзит

# присоединённая нагрузка источника — 85 % установленной тепловой мощности (резерв на отказ крупнейшего агрегата);
# 1 Гкал/ч при графике 150/70 °C — 12,5 т/ч сетевой воды
LOAD_SHARE = 0.85
TPH_PER_GCAL = 1.0e6 / (1.0 * DT_HEAT) / 1000.0
EXITS_MAX, EXIT_MIN_ANGLE, EXIT_MIN_M = 3, 60.0, 1000.0


def find_source(cfg, plants, areas, existing):
    """Реальный источник: power=plant с именем из конфигурации (иначе ближайший к подсказке). Точка источника —
    свободное место на территории станции у главного корпуса (самого большого здания за оградой): оттуда выводы
    магистралей расходятся в разные стороны, как у настоящей ТЭЦ. Без контура станции — её центр из OSM."""
    hint = utm(Point(cfg["plant_hint"][1], cfg["plant_hint"][0]))
    best = None
    for el in plants["elements"]:
        tags = el.get("tags", {})
        centre = el.get("center") or {"lat": el.get("lat"), "lon": el.get("lon")}
        if tags.get("power") != "plant" or centre.get("lat") is None:
            continue
        p = utm(Point(centre["lon"], centre["lat"]))
        named = cfg["plant_name"].lower() in (tags.get("name", "") + tags.get("official_name", "")).lower()
        key = (not named, p.distance(hint), el["type"], el["id"])
        if best is None or key < best[0]:
            best = (key, el["type"][0] + str(el["id"]), tags, p)
    _, oid, tags, centre = best
    fence = next((o.geom for o in areas if o.id == oid and o.geom.geom_type.endswith("Polygon")), None)
    if fence is None:
        return centre, oid, tags, None
    inside = [b.geom for b in existing if fence.contains(b.geom.representative_point())]
    free = fence.buffer(-5.0)
    if inside:
        free = free.difference(unary_union([g.buffer(CLEAR_EXISTING_M + 3.0) for g in inside]))
    if free.is_empty:
        return centre, oid, tags, fence
    main = max(inside, key=lambda g: (g.area, g.bounds)) if inside else fence
    return shapely.ops.nearest_points(free, main.centroid)[0], oid, tags, fence


def exit_points(region, axes, source):
    """Точки, где крупные улицы выходят за границу района, разнесённые по направлению не меньше чем на 60°:
    по ним магистрали уходят с транзитом в соседние районы."""
    boundary = region.exterior
    cands = []
    for a in axes:
        if a["kind"] not in ("trunk", "primary", "secondary") or a["tunnel"] or a["bridge"]:
            continue
        hit = a["axis"].intersection(boundary)
        for p in getattr(hit, "geoms", [hit]):
            if p.geom_type == "Point" and p.distance(source) >= EXIT_MIN_M:
                cands.append((a["kind"] != "trunk", -p.distance(source), p.x, p.y))
    cands.sort()
    chosen = []
    for *_, x, y in cands:
        angle = math.degrees(math.atan2(y - source.y, x - source.x))
        if all(abs((angle - b + 180) % 360 - 180) >= EXIT_MIN_ANGLE for b, _ in chosen):
            chosen.append((angle, Point(x, y)))
        if len(chosen) == EXITS_MAX:
            break
    return [p for _, p in chosen]


# ---------------------------------------------------------------- находки Разведчика

RAW = ROOT / "data" / "cities" / "raw"


def osm_ref(value):
    """«way/123» → «w123», как id объектов Overpass в этом скрипте."""
    text = str(value)
    for long, short in (("way/", "w"), ("node/", "n"), ("relation/", "r")):
        if text.startswith(long):
            return short + text[len(long):]
    return text


def raw_heat(city):
    """Находки Разведчика в data/cities/raw/<город>/*.geojson: трубы (_class=heat_pipe, у восстановленных по схемам —
    diameter), люки теплосети (heat_manhole), ЦТП (heat_substation). Берутся только _source real и restored;
    candidate (трубы без substance) теплосетью не считаются."""
    pipes, hatches, substations = [], [], []
    folder = RAW / city
    for path in sorted(folder.glob("*.geojson")) if folder.exists() else []:
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        for f in data.get("features", []):
            p = f.get("properties") or {}
            level = p.get("_source") or ("restored" if "restored" in path.name else "real")
            if not f.get("geometry") or level not in ("real", "restored"):
                continue
            g = utm(shapely.geometry.shape(f["geometry"]))
            ref = osm_ref(p.get("_osm_id") or p.get("_ref") or p.get("id") or path.stem)
            cls = p.get("_class") or ("heat_pipe" if "Line" in g.geom_type else
                                      "heat_manhole" if p.get("manhole") in ("heat", "heating") else None)
            dn = p.get("diameter")
            dn = int(dn) if isinstance(dn, (int, float)) or (isinstance(dn, str) and dn.isdigit()) else None
            if cls == "heat_pipe" and "Line" in g.geom_type:
                for k, part in enumerate(getattr(g, "geoms", [g])):
                    pipes.append((part, level, dn, ref if k == 0 else f"{ref}-{k}"))
            elif cls == "heat_manhole" and g.geom_type == "Point":
                hatches.append((g, level, ref))
            elif cls == "heat_substation":
                substations.append((g, ref, p))
    return pipes, hatches, substations


# ---------------------------------------------------------------- сборка

def nearest_cell(grid, dist, point, radius):
    win = grid.window(point, radius)
    if win is None:
        return None
    sl, gx, gy = win
    ks = (np.arange(sl[0].start, sl[0].stop)[:, None] * grid.nx + np.arange(sl[1].start, sl[1].stop)[None, :]).ravel()
    d = np.hypot(gx.ravel() - point.x, gy.ravel() - point.y)
    ok = np.isfinite(dist[ks]) & (d <= radius)
    if not ok.any():
        return None
    return int(ks[ok][np.lexsort((ks[ok], d[ok]))[0]])


def trace_share(coords, zones):
    """Доля длины ломаной в зоне 3 м от реальных или восстановленных трасс: {уровень: доля}, и Ду-подсказка."""
    line = LineString(coords)
    share, hint = {}, 0
    for level, tree, geoms, dns in zones:
        hit = [i for i in tree.query(line, predicate="intersects")]
        if not hit:
            continue
        inside = line.intersection(unary_union([geoms[i] for i in hit])).length / max(line.length, 1e-9)
        share[level] = inside
        if inside >= 0.5:
            hint = max([hint] + [dns[i] or 0 for i in hit])
    return share, hint


def raster_network(grid, source, source_id, heated, ctps, exits, transit, hatches, traces, blockers, table):
    """Достроенная сеть (inferred) по растру стоимости: дерево от источника к зданиям heated, ЦТП, люкам и реальным
    трубам; здания у ЦТП — поддеревом от ЦТП."""
    # ЦТП: здания в радиусе CTP_RADIUS_M, ближние первыми, пока расход ЦТП в пределах CTP_CAP_TPH
    cap = next(r["capacity_tph"] for r in table if r["dn"] == CTP_DN)
    assigned, ctp_load = {}, defaultdict(float)
    heated_tree = STRtree([b.geom for b in heated])
    pairs = sorted((heated[i].geom.distance(g), ref, heated[i].id, i) for ref, g in ctps
                   for i in heated_tree.query(g, predicate="dwithin", distance=CTP_RADIUS_M))
    for _, ref, _, i in pairs:
        b = heated[i]
        if b.id not in assigned and ctp_load[ref] + b.flow <= cap:
            assigned[b.id] = ref
            ctp_load[ref] += b.flow
    trees, anchors, left = [None], {}, []
    passable = np.where(np.isfinite(grid.cost.ravel()), 0.0, INF)
    for ref, g in ctps:
        k = nearest_cell(grid, passable, g.centroid, 25.0)
        mine = [b for b in heated if assigned.get(b.id) == ref]
        if k is None or not mine:
            left += mine
            continue
        anchors[ref] = k
        sub = window_grid(grid, unary_union([g] + [b.geom for b in mine]), 150.0)
        root = sub.index(*grid.center(k))

        def sub_targets(dist, sub=sub, mine=mine):
            targets = defaultdict(list)
            for b in sorted(mine, key=lambda b: b.id):
                hit = inlet(sub, dist, b, blockers)
                if hit:
                    targets[hit[0]].append(("building", b.flow, hit[1], b.id))
            return targets

        tree = grow(sub, root, sub_targets, REUSE_PASSES)
        tree["alias"] = k
        trees.append(tree)
        done = {lab for items in tree["reached"].values() for *_, lab in items}
        left += [b for b in mine if b.id not in done]
    direct = [b for b in heated if b.id not in assigned] + left

    no_inlet = []

    def targets_of(dist):
        targets = defaultdict(list)
        no_inlet.clear()
        for ref, k in anchors.items():
            targets[k].append(("ctp", 0.0, None, ref))
        for b in sorted(direct, key=lambda b: b.id):
            hit = inlet(grid, dist, b, blockers)
            if hit:
                targets[hit[0]].append(("building", b.flow, hit[1], b.id))
            else:
                no_inlet.append(b.id)
        for i, p in enumerate(exits, 1):
            k = nearest_cell(grid, dist, p, 60.0)
            if k is not None:
                targets[k].append(("exit", transit, None, f"exit-{i}"))
        for g, level, ref in hatches:
            k = nearest_cell(grid, dist, g, 6.0)
            if k is not None:
                targets[k].append(("manhole", 0.0, (g.x, g.y), ref))
        for g, level, dn, ref in traces:
            for part in getattr(g, "geoms", [g]):
                for at in np.arange(0.0, part.length + 1e-9, 25.0):
                    k = nearest_cell(grid, dist, part.interpolate(at), 4.0)
                    if k is not None and not targets.get(k):
                        targets[k].append(("pass", 0.0, None, ref))
        return targets

    trees[0] = grow(grid, grid.index(source.x, source.y), targets_of, REUSE_PASSES)
    # дерево ЦТП без связи с источником не входит в сеть: его здания — среди недостигнутых
    trees = [trees[0]] + [t for t in trees[1:] if t["alias"] in trees[0]["reached"]]
    segments, chambers, unreached = heat_network(trees, source, blockers, source_id)
    return segments, chambers, unreached + no_inlet


def merge_networks(segments, chambers, more, more_ch):
    """Две сети от одного источника в одну: id второй продолжают нумерацию первой."""
    shift = {}
    for s in more:
        shift[s["id"]] = f"hn-{len(segments) + len(shift) + 1}"
    for i, c in enumerate(more_ch, 1):
        shift[c["id"]] = f"hc-{len(chambers) + i}"
    for item in more + more_ch:
        item["id"] = shift[item["id"]]
        item["up"] = shift.get(item["up"], item["up"])
        if "end" in item:
            item["end"] = shift[item["end"]]
    return segments + more, chambers + more_ch


def object_items(segments, chambers, existing, future):
    """Объекты входа: участки и камеры сети, существующие и перспективные ОКС с точками подключения. Участок и камера
    сети без источника (upstream = None) пишутся без upstream_object_id."""
    items = []
    for s in segments:
        props = {"id": s["id"], "object_type": "heat_network", "diameter": s["dn"], "flow_tph": round(s["flow"], 3),
                 "upstream_object_id": s["up"], "_source": s["source"]}
        if s["up"] is None:
            del props["upstream_object_id"]
        if s.get("dn_hint"):
            props["_dn_scheme"] = s["dn_hint"]
        if s.get("label_flow"):
            props["_flow_scheme_tph"] = s["label_flow"]
        if s.get("adjusted"):
            props["_adjusted"] = True
        if s.get("ring"):
            props["_ring_cut"] = True
        if s.get("through"):
            props["_through_building"] = True
        if s.get("transit"):
            props["_transit_tph"] = round(s["transit"], 1)
        items.append((LineString(s["coords"]), props))
    for c in chambers:
        props = {"id": c["id"], "object_type": "heat_chamber", "diameter": c["dn"], "upstream_object_id": c["up"],
                 "_source": c["source"]}
        if c["up"] is None:
            del props["upstream_object_id"]
        if c["osm"]:
            props["_osm"] = c["osm"]
        if c.get("ctp"):
            props["_ctp"] = True
        items.append((Point(c["pos"]), props))
    for b in existing:
        items.append((b.geom, {"id": b.id, "object_type": "oks_existing", "building": b.tags.get("building"),
                               "levels": b.levels, "_source": "real", "_levels": b.levels_source, "_kind": b.kind,
                               "_flow_tph": round(b.flow, 3)}))
    for f in future:
        props = {"id": f.id, "object_type": "oks_future", "flow_tph": round(f.flow, 3), "heat_load": round(f.load, 4),
                 "levels": f.levels, "_source": f.source, "_kind": f.kind, "_site": f.site}
        props.update(getattr(f, "extra", None) or {})
        items.append((f.geom, props))
        items.append((f.cp, {"id": "cp-" + f.id[4:], "object_type": "oks_connection_point", "oks_id": f.id,
                             "_source": f.source}))
    return items


def build(city, bbox, one_source=False):
    cfg = CITIES[city]
    if "districts" in cfg:
        return build_nn(city, bbox, one_source)
    t_design = T_DESIGN[city]
    rules = json.loads((ROOT / "rules" / "rules.json").read_text(encoding="utf-8"))
    table = sorted(rules["diameters"], key=lambda r: r["dn"])
    data = fetch(city, bbox)
    region = region_polygon(bbox, city)
    osm = {name: objects(data[name]) for name in ("buildings", "parts", "highways", "rail", "areas", "utilities")}
    road_items, axes = roads(osm["highways"], region)
    restrictions = road_items + tracks(osm["rail"], region) + area_layers(osm["areas"], region)
    restrictions += supports(osm["utilities"], region)
    real_utils, heat_pipes, manholes = utility_lines(osm["utilities"], region)
    restrictions += real_utils
    layers = defaultdict(list)
    for g, p in restrictions:
        layers[p["restriction_type"]].append(g)
    existing = existing_buildings(osm["buildings"], osm["parts"], osm["areas"], axes, region, t_design)
    form = modern_form(existing)
    streets = STRtree([a["axis"] for a in axes if not a["tunnel"]])
    obstacles = {k: v for k, v in layers.items()}
    restored = load_restored(city)
    if restored and restored.get("heat_network"):
        # новые дома не ставятся на трубы схемы: охранная зона тепловой сети — 3 м от края канала, принято 5 м
        obstacles["heat_network"] = [g for g, _ in restored["heat_network"] if g.intersects(region)]
    future = future_buildings(osm["buildings"], osm["areas"], existing, obstacles, region, streets, form, t_design)
    # реальные и восстановленные трассы, люки и ЦТП: Overpass и находки Разведчика в raw/, без повторов по id OSM
    raw_pipes, raw_hatches, raw_subs = raw_heat(city)
    traces, hatches, ctps = {}, {}, {}
    for o in heat_pipes:
        traces[o.id] = (o.geom, "real", None, o.id)
    for g, level, dn, ref in raw_pipes:
        traces.setdefault(ref, (g, level, dn, ref))
    for o in manholes:
        hatches[o.id] = (o.geom, "restored", o.id)
    for g, level, ref in raw_hatches:
        hatches.setdefault(ref, (g, "restored", ref))
    for g, ref, _ in raw_subs:
        if region.contains(g.representative_point()):
            ctps[ref] = g
    traces = [(clip(g, region), level, dn, ref) for g, level, dn, ref in traces.values()]
    traces = sorted((t for t in traces if t[0] is not None), key=lambda t: t[3])
    hatches = sorted((h for h in hatches.values() if region.contains(h[0])), key=lambda h: h[2])
    ctps = sorted(ctps.items())
    for b in existing:
        # здание ЦТП — техническое, своей нагрузки в сети не несёт
        if any(ref == b.id or (g.geom_type == "Point" and b.geom.contains(g)) for ref, g in ctps):
            b.kind, b.flow, b.load = "unheated", 0.0, 0.0
    heated = [b for b in existing if b.kind != "unheated" and region.contains(b.geom.representative_point())]
    sides = side_preference([a for a in axes if not a["tunnel"]], heated)

    source, plant_id, plant_tags, fence = find_source(cfg, data["plants"], osm["areas"], existing)
    source_id = f"src-{plant_id}"
    district = sum(b.flow for b in heated)
    capacity = cfg["plant_gcal_h"] * TPH_PER_GCAL * LOAD_SHARE
    exits = exit_points(region, axes, source)
    transit = max(0.0, capacity - district) / max(1, len(exits))
    if transit <= 0:
        exits = []
    blockers = STRtree([b.geom for b in existing] + [f.geom for f in future])

    net_stats, k_cal = {}, 1.0

    def grid_of():
        return cost_grid(region, layers, axes, heated, existing, future, [t[0] for t in traces], source, fence)

    if restored and restored.get("heat_network"):
        segments, chambers, unreached, source, k_cal, holes, scheme = restored_network(
            restored, region, source, source_id, heated, existing, future, layers, table, net_stats, cfg["bridge_m"])
        for b in existing:
            b.flow, b.load = b.flow * k_cal, b.load * k_cal
        for f in future:
            f.flow, f.load = f.flow * k_cal, f.load * k_cal
        if holes:
            # здания вне схемы: достроенная сеть от того же источника; люки и трубы OSM — только вне трасс схемы
            near = unary_union([g.buffer(15.0) for g in scheme])
            far_traces = [t for t in traces if t[0].difference(near).length > 0.5 * t[0].length]
            far_hatches = [h for h in hatches if not near.contains(h[0])]
            more, more_ch, more_lost = raster_network(grid_of(), source, source_id, holes, ctps, [], 0.0, far_hatches,
                                                      far_traces, blockers, table)
            segments, chambers = merge_networks(segments, chambers, more, more_ch)
            unreached += more_lost
    else:
        segments, chambers, unreached = raster_network(grid_of(), source, source_id, heated, ctps, exits, transit,
                                                       hatches, traces, blockers, table)
    zones = []
    for level in ("real", "restored"):
        chosen = [(t[0].buffer(3.0), t[2]) for t in traces if t[1] == level]
        if chosen:
            zones.append((level, STRtree([g for g, _ in chosen]), [g for g, _ in chosen], [d for _, d in chosen]))
    for seg in segments:
        share, hint = trace_share(seg["coords"], zones)
        best = max(share.items(), key=lambda kv: kv[1], default=(None, 0.0))
        if best[1] >= 0.7:
            seg["source"] = best[0]
        if hint:
            seg["dn_hint"] = hint
    segments, chambers, dn_fixes = finish_network(segments, chambers, table, cfg["chamber_step_m"])
    sides = network_sides([a for a in axes if not a["tunnel"]], segments, sides)
    restrictions += profile_utilities(axes, sides, existing, future, layers["water"], real_utils, region)

    items = [(source, {"id": source_id, "object_type": "source", "name": plant_tags.get("name", ""),
                       "operator": plant_tags.get("operator", ""), "_source": "real", "_osm": plant_id})]
    items += object_items(segments, chambers, existing, future)
    items += restrictions
    out = ROOT / "data" / "cities" / city / "input.geojson"
    written, dropped = write_features(out, items)
    meta = dict(city=city, bbox=bbox, cell_m=CELL_M, plant=plant_id, district_flow_tph=round(district, 1),
                transit_per_exit_tph=round(transit, 1), exits=len(exits), unreached=sorted(map(str, unreached)),
                modern_form=form, written=written, dropped=dropped, future=len(future), k_cal=round(k_cal, 4),
                dn_raised=dn_fixes[0], dn_lowered=dn_fixes[1], network=net_stats, osm=pbf_meta(city))
    (out.parent / "build.json").write_text(json.dumps(meta, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(json.dumps(meta, ensure_ascii=False)[:2000], file=sys.stderr)
    return out


# ---------------------------------------------------------------- Нижний Новгород целиком

# перспективный ОКС схемы не ставится ближе 3 м к зданию и в полигоны дорог, воды, путей, парков, соцобъектов и
# запретных территорий, ближе 5 м к трубе схемы (охранная зона тепловой сети — 3 м от края канала, принято 5 м)
SCHEME_GAP_EXISTING_M, SCHEME_GAP_PIPE_M = 3.0, 5.0
SCHEME_BLOCK = ("road", "water", "railway", "tram_tracks", "metro", "park", "social_area", "prohibited_site")
# источник в варианте (б), кроме главного: запретная территория — площадка станции power=plant из OSM, иначе здание
# котельной с отступом 10 м, иначе квадрат 30 × 30 м вокруг точки схемы
SITE_PAD_M, SITE_SIDE_M = 10.0, 30.0
# частный сектор — индивидуальное теплоснабжение (котлы в доме): так в схеме Н. Новгорода территории ИЖС («Индивидуальное
# теплоснабжение», табл. 2.1 Гл. 2 Прил. 1 Ч. 3). Такие дома не потребители сети и не «дыры»: building=house, detached,
# semidetached_house, bungalow, cabin и здания до 2 этажей площадью до 250 м² с building=yes/residential
INDIVIDUAL = {"house", "detached", "semidetached_house", "bungalow", "cabin", "farm", "static_caravan"}
INDIVIDUAL_M2 = 250.0


# жилые и общественные здания дальше 1 км от сети схемы вне Автозаводского и Ленинского районов (там сеть
# «Теплосетей» достраивается целиком) — посёлки вне зон ЕТО: своё теплоснабжение, к сети не подключаются
OUTSIDE_ZONE_M = 1000.0


def individual(b):
    """Частный дом: building=house и подобные или небольшое (до 250 м²) жилое здание building=yes/residential не выше
    2 этажей; этажность без тега (медиана квартала) не в счёт."""
    kind = b.tags.get("building", "yes")
    low = b.levels <= 2 or b.levels_source != "real"
    return b.kind == "residential" and (kind in INDIVIDUAL or (
        kind in ("yes", "residential") and low and b.geom.area < INDIVIDUAL_M2))


def scheme_future(city, region, existing, layers, pipes, streets):
    """Перспективные ОКС по табл. 2.1 схемы (scripts/heat_future_nn.py → raw/<город>/future_scheme.geojson):
    нагрузка из схемы, flow_tph = нагрузка × 12,5 т/ч на Гкал/ч (150/70). Контур OSM (стройка или новый дом по
    адресу) — real, достроенный — inferred. Не проходят проверку места — отбрасываются (счёт в build.json)."""
    path = RAW / city / "future_scheme.geojson"
    if not path.exists():
        return [], {}
    ex_tree = STRtree([b.geom for b in existing])
    blocked = [g for k in SCHEME_BLOCK for g in layers.get(k, [])]
    block_tree = STRtree(blocked)
    pipe_tree = STRtree(pipes)
    ex_by_id = {b.id: b for b in existing}
    result, dropped, taken = [], defaultdict(int), []
    for k, f in enumerate(json.loads(path.read_text(encoding="utf-8"))["features"], 1):
        p = f["properties"]
        g = polygonal(utm(shapely.geometry.shape(f["geometry"])))
        if g is None:
            dropped["invalid"] += 1
            continue
        osm = p.get("_osm")
        near_ex = [i for i in ex_tree.query(g.buffer(SCHEME_GAP_EXISTING_M), predicate="intersects")
                   if existing[i].id != osm]
        why = ("outside" if not region.contains(g.representative_point()) else
               "building" if near_ex else
               "restriction" if len(block_tree.query(g, predicate="intersects")) else
               "pipe" if len(pipe_tree.query(g, predicate="dwithin", distance=SCHEME_GAP_PIPE_M)) else
               "other_future" if any(g.intersects(t) for t in taken[-50:]) else None)
        if why:
            dropped[why] += 1
            continue
        if osm in ex_by_id:  # новый дом уже в OSM как готовое здание: по схеме он перспективный
            existing.remove(ex_by_id.pop(osm))
        fu = Future()
        fu.id, fu.geom, fu.tags, fu.source, fu.site = f"oks-s{p['_item']}-{k}", g, {}, p["_source"], f"scheme-{p['_item']}"
        name = p["_name"].lower()
        fu.kind = "residential" if any(w in name for w in ("жил", "мкд", "многоквартир", "жк ")) else "public"
        fu.levels, fu.flow, fu.load = p["levels"], p["flow_tph"], p["heat_load"]
        fu.cp = connection_point(g, streets)
        fu.extra = {key: p[key] for key in ("_load_source", "_name", "_address", "_geocode", "_contour", "_year",
                                            "_scheme_source", "_district", "_osm") if key in p}
        result.append(fu)
        taken.append(g)
    return result, dict(dropped)


def source_site(pt, areas, existing):
    """Площадка источника для prohibited_site (вариант б): power=plant или котельная OSM, содержащая точку."""
    for o in areas:
        if (o.tags.get("power") == "plant" or "boiler" in o.tags.get("industrial", "")) \
                and o.geom.geom_type.endswith("Polygon") and o.geom.contains(pt):
            return o.geom, "real"
    for b in existing:
        if b.geom.contains(pt):
            return b.geom.buffer(SITE_PAD_M, join_style="mitre"), "real"
    return box(pt.x - SITE_SIDE_M / 2, pt.y - SITE_SIDE_M / 2, pt.x + SITE_SIDE_M / 2, pt.y + SITE_SIDE_M / 2), "inferred"


def build_nn(city, bbox, one_source=False):
    """Н. Новгород целиком: слои OSM по полигону восьми районов, сеть — граф схемы теплоснабжения, уложенный по улицам
    (scripts/heat_embed_nn.py embed → raw/nnovgorod/restored_heat.geojson), и достроенная сеть «Теплосетей» и веток
    к зданиям дальше 150 м (heat_embed_nn.nn_network); перспективные ОКС — табл. 2.1 схемы, затем стройки и свободные
    участки OSM. Вариант (а): все источники Гл. 4 с координатами в городе — source; (б) one_source — одна
    Сормовская ТЭЦ, площадки остальных источников — prohibited_site с _heat_source."""
    sys.path.insert(0, str(ROOT / "scripts"))
    import heat_embed_nn as nn
    cfg, t_design = CITIES[city], T_DESIGN[city]
    rules = json.loads((ROOT / "rules" / "rules.json").read_text(encoding="utf-8"))
    table = sorted(rules["diameters"], key=lambda r: r["dn"])
    t0 = time.time()
    log = lambda msg: print(f"[{time.time() - t0:.0f} с] {msg}", file=sys.stderr)
    data = fetch(city, bbox)
    region = region_polygon(bbox, city)
    osm = {name: objects(data[name]) for name in ("buildings", "parts", "highways", "rail", "areas", "utilities")}
    del data
    log("OSM прочитан")
    road_items, axes = roads(osm["highways"], region)
    restrictions = road_items + tracks(osm["rail"], region) + area_layers(osm["areas"], region)
    restrictions += supports(osm["utilities"], region)
    real_utils, heat_pipes, manholes = utility_lines(osm["utilities"], region)
    restrictions += real_utils
    layers = defaultdict(list)
    for g, p in restrictions:
        layers[p["restriction_type"]].append(g)
    existing = existing_buildings(osm["buildings"], osm["parts"], osm["areas"], axes, region, t_design)
    for b in existing:
        if individual(b):
            b.kind = "individual"
    form = modern_form(existing)
    streets = STRtree([a["axis"] for a in axes if not a["tunnel"]])
    log(f"ограничений {len(restrictions)}, зданий {len(existing)}, из них частных домов "
        f"{sum(b.kind == 'individual' for b in existing)}")
    pipes = [LineString(nn.utm_xy(f["geometry"]["coordinates"])) for f in json.loads(
        (RAW / city / "restored_heat.geojson").read_text(encoding="utf-8"))["features"]
        if f["properties"]["_class"] == "heat_network" and f["properties"]["_pipe"] == "heating"]
    scheme, scheme_dropped = scheme_future(city, region, existing, layers, pipes, streets)
    obstacles = {k: v for k, v in layers.items()}
    obstacles["heat_network"] = pipes
    obstacles["scheme_future"] = [f.geom.buffer(GAP_NEW_M - GAP_OTHER_M) for f in scheme]
    osm_future = [f for f in future_buildings(osm["buildings"], osm["areas"], existing, obstacles, region, streets,
                                              form, t_design)
                  if f.site not in {x.extra.get("_osm") for x in scheme}]
    future = scheme + osm_future
    log(f"перспективных ОКС: схема {len(scheme)} (отброшено {scheme_dropped}), OSM {len(osm_future)}")
    _, _, raw_subs = raw_heat(city)
    ctp_pts = [g for g, _, _ in raw_subs if g.geom_type == "Point"]
    ctp_tree = STRtree(ctp_pts) if ctp_pts else None
    for b in existing:
        if ctp_tree is not None and len(ctp_tree.query(b.geom, predicate="intersects")):
            b.kind, b.flow, b.load = "unheated", 0.0, 0.0
    heated = [b for b in existing if b.kind in ("residential", "public") and region.contains(b.geom.representative_point())]
    names = [n.split()[0] for n, _ in districts_wgs(city)]
    dpolys = [utm(g) for _, g in districts_wgs(city)]
    dtree = STRtree(dpolys)

    def district_of(pt):
        k = dtree.query(pt, predicate="within")
        return names[int(k[0])] if len(k) else None
    pipe_tree = STRtree(pipes)
    outside = [b for b in heated if district_of(b.geom.representative_point()) not in nn.AVTO_DISTRICTS
               and not len(pipe_tree.query(b.geom, predicate="dwithin", distance=OUTSIDE_ZONE_M))]
    for b in outside:
        b.kind = "outside_zone"
    heated = [b for b in heated if b.kind != "outside_zone"]
    log(f"зданий вне зон ЕТО (дальше {OUTSIDE_ZONE_M:.0f} м от сети схемы): {len(outside)}")
    segments, chambers, sources, src_at, net_stats = nn.nn_network(
        [(b.id, b.geom, b.flow) for b in heated], [b.geom for b in existing] + [f.geom for f in future], region,
        district_of, one_source, log, detour)
    net_tree = STRtree([LineString(s["coords"]) for s in segments])
    crossed = {i for i, f in enumerate(future) if len(net_tree.query(f.geom, predicate="intersects"))}
    future = [f for i, f in enumerate(future) if i not in crossed]
    segments, chambers, dn_fixes = finish_network(segments, chambers, table, cfg["chamber_step_m"])
    # последний обход зданий: концы участков сдвинуты слиянием узлов (до 0,3 м) и камерами finish_network
    net_stats["detoured_final"], net_stats["through_final_m"] = nn.avoid_buildings(
        [s for s in segments if not s.get("through")], [b.geom for b in existing] + [f.geom for f in future], detour)
    sides = side_preference([a for a in axes if not a["tunnel"]], heated)
    sides = network_sides([a for a in axes if not a["tunnel"]], segments, sides)
    restrictions += profile_utilities(axes, sides, existing, future, layers["water"], real_utils, region)
    log("коммуникации по профилю готовы")
    items = []
    for sid, pt, p in sources:
        if one_source and sid not in src_at:
            site, level = source_site(pt, osm["areas"], existing)
            items.append((site, {"id": f"site-{sid}", "object_type": "restriction", "restriction_type": "prohibited_site",
                                 "_source": level, "_heat_source": p["name"], "_org": p["org"],
                                 "_capacity_gcal_h": p["capacity_gcal_h"]}))
            continue
        at = Point(src_at[sid]) if sid in src_at else pt
        items.append((at, {"id": sid, "object_type": "source", "name": p["name"], "operator": p["org"],
                           "_source": "real", "_capacity_gcal_h": p["capacity_gcal_h"], "_load_gcal_h": p["load_gcal_h"],
                           "_geocode": p["_geocode"], "_on_network": sid in src_at}))
    items += object_items(segments, chambers, existing, future)
    items += restrictions
    out = ROOT / "data" / "cities" / city / "input.geojson"
    written, dropped = write_features(out, items)
    meta = dict(city=city, bbox=bbox, cell_m=None, variant="b" if one_source else "a",
                sources=sum(1 for _, p in items if p["object_type"] == "source"), sources_on_network=len(src_at),
                district_flow_tph=round(sum(b.flow for b in heated), 1), modern_form=form, written=written,
                dropped=dropped, future=len(future), future_scheme=len(scheme), future_scheme_dropped=scheme_dropped,
                future_crossed_by_network=len(crossed), k_cal=1.0, dn_raised=dn_fixes[0], dn_lowered=dn_fixes[1],
                network=net_stats, osm=pbf_meta(city))
    (out.parent / "build.json").write_text(json.dumps(meta, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(json.dumps(meta, ensure_ascii=False)[:3000], file=sys.stderr)
    return out


# ---------------------------------------------------------------- проверка полноты и правдоподобия

def share_table(features):
    """Доли real / restored / inferred по слоям: по числу объектов и по длине (линии) или площади (полигоны)."""
    stat = defaultdict(lambda: defaultdict(lambda: [0, 0.0]))
    for f in features:
        p = f["properties"]
        layer = p["object_type"] + ("/" + p["restriction_type"] if "restriction_type" in p else "")
        g = f["_utm"]
        size = g.length if "Line" in g.geom_type else g.area if "Polygon" in g.geom_type else 0.0
        cell = stat[layer][p.get("_source", "?")]
        cell[0] += 1
        cell[1] += size
    return stat


def check(city):
    """Проверки п. 4 задания по готовому входу; итог — data/cities/<город>/check.md и код 1 при провале."""
    folder = ROOT / "data" / "cities" / city
    meta = json.loads((folder / "build.json").read_text(encoding="utf-8"))
    rules = json.loads((ROOT / "rules" / "rules.json").read_text(encoding="utf-8"))
    capacity = {row["dn"]: row["capacity_tph"] for row in rules["diameters"]}
    known = {k for k in rules["restrictions"] if not k.startswith("_")}
    features = json.loads((folder / "input.geojson").read_text(encoding="utf-8"))["features"]
    invalid = 0
    for f in features:
        g = shapely.geometry.shape(f["geometry"])
        invalid += 0 if g.is_valid else 1
        f["_utm"] = utm(g)
    by = defaultdict(list)
    for f in features:
        by[f["properties"]["object_type"]].append(f)
    region = region_polygon(tuple(meta["bbox"]), city)
    lines = []
    ok = True

    def verdict(name, passed, detail):
        nonlocal ok
        ok &= passed
        lines.append(f"| {name} | {'да' if passed else 'НЕТ'} | {detail} |")

    ids = [f["properties"]["id"] for f in features]
    verdict("id уникальны", len(ids) == len(set(ids)), f"{len(ids)} объектов")
    verdict("геометрии валидны после округления", invalid == 0, f"невалидных {invalid}")
    unknown = sorted({f["properties"]["restriction_type"] for f in by["restriction"]} - known - {"oks"})
    verdict("типы ограничений из rules.json", not unknown, ", ".join(unknown) or "все известны")
    multi = meta.get("variant") == "a"
    if multi:
        verdict("источники: все из схемы с координатами (вариант а)", len(by["source"]) >= 1,
                f"{len(by['source'])}, из них корни сети {meta.get('sources_on_network')}")
    else:
        verdict("ровно один source", len(by["source"]) == 1, by["source"][0]["properties"].get("name", ""))
    pipes = {f["properties"]["id"]: f for f in by["heat_network"]}
    chambers = {f["properties"]["id"]: f for f in by["heat_chamber"]}
    source_ids = {f["properties"]["id"] for f in by["source"]}
    # дерево: цепочка upstream до источника, камеры ≤ 1 + 3 участков, ветвления только в камерах
    up = {i: f["properties"].get("upstream_object_id") for i, f in {**pipes, **chambers}.items()}
    broken = sourceless = 0
    for i in up:
        seen, cur = set(), i
        while cur in up and cur not in seen:
            seen.add(cur)
            cur = up[cur]
        sourceless += cur is None
        broken += cur is not None and cur not in source_ids
    # у сети без источника (компоненты схемы без источника с координатами, вариант б) цепочка кончается участком без
    # upstream_object_id — это не обрыв: сервис предупреждает и считает (docs/interpretation.md)
    verdict("участков сети от 50, цепочка upstream до source", len(pipes) >= 50 and broken == 0,
            f"участков {len(pipes)}, камер {len(chambers)}, оборванных цепочек {broken}"
            + (f", сети без источника (без upstream у корня): {sourceless}" if sourceless else ""))
    below = defaultdict(list)
    for i, f in pipes.items():
        below[f["properties"].get("upstream_object_id")].append(i)
    crowded = [c for c in chambers if len(below[c]) > 3]
    ends = defaultdict(set)
    for i, f in pipes.items():
        for xy in (f["_utm"].coords[0], f["_utm"].coords[-1]):
            ends[(round(xy[0], 1), round(xy[1], 1))].add(i)
    chamber_at = {(round(c["_utm"].x, 1), round(c["_utm"].y, 1)) for c in chambers.values()}
    src_at = {(round(f["_utm"].x, 1), round(f["_utm"].y, 1)) for f in by["source"]}
    forks = [k for k, v in ends.items() if len(v) >= 3 and k not in chamber_at and k not in src_at]
    verdict("камеры во всех ветвлениях, не больше 1 + 3 участков", not crowded and not forks,
            f"ветвлений без камеры {len(forks)}, камер с > 3 участками вниз {len(crowded)}")
    # Ду не растёт от источника, пропускная способность покрывает расход ниже
    grow = [i for i, f in pipes.items() if f["properties"].get("upstream_object_id") in chambers
            and pipes.get(chambers[f["properties"].get("upstream_object_id")]["properties"].get("upstream_object_id"), f)
            ["properties"]["diameter"] < f["properties"]["diameter"]]
    grow += [i for i, f in pipes.items() if f["properties"].get("upstream_object_id") in pipes
             and pipes[f["properties"].get("upstream_object_id")]["properties"]["diameter"] < f["properties"]["diameter"]]
    over = [i for i, f in pipes.items() if capacity[f["properties"]["diameter"]] < f["properties"]["flow_tph"] - 1e-6]
    short = []
    for c, cf in chambers.items():
        feed = pipes.get(cf["properties"].get("upstream_object_id"))
        downstream = sum(pipes[j]["properties"]["flow_tph"] for j in below[c])
        if feed is not None and downstream > feed["properties"]["flow_tph"] + 0.01:
            short.append(feed["properties"]["id"])
    verdict("Ду не растёт к концам, пропускная способность ≥ расхода ниже", not grow and not over and not short,
            f"рост Ду {len(grow)}, перегруз {len(over)}, расход меньше суммы ниже {len(short)}")
    # сквозь здания
    houses = [f["_utm"] for f in by["oks_existing"] + by["oks_future"]]
    tree = STRtree(houses)
    total = sum(f["_utm"].length for f in pipes.values())
    through, allowed, examples = 0.0, 0.0, []
    for i, f in pipes.items():
        g = f["_utm"]
        cut = sum(g.intersection(houses[j]).length for j in tree.query(g, predicate="intersects"))
        if cut > 0.01 and f["properties"].get("_through_building"):
            allowed += cut
        elif cut > 0.01:
            through += cut
            examples.append(i)
    verdict("0 % длины сети сквозь здания", through <= 0.01,
            f"{through:.1f} м из {total / 1000:.1f} км ({100 * through / max(total, 1):.3f} %) {examples[:5]}; "
            f"транзит схемы через здания, где обхода нет (_through_building): {allowed:.1f} м")
    # у улиц или на реальных трассах
    osm = objects(fetch(city, tuple(meta["bbox"]))["highways"])
    axes = [o.geom for o in osm if is_carriageway(o.tags) and o.geom.geom_type == "LineString"
            and o.tags.get("tunnel") in (None, "no")]
    zones = [a.buffer(30.0, quad_segs=2) for a in axes]
    zone_tree = STRtree(zones)
    near, by_level, level_total = 0.0, defaultdict(float), defaultdict(float)
    for f in pipes.values():
        g = f["_utm"]
        level = f["properties"]["_source"]
        hit = zone_tree.query(g, predicate="intersects")
        inside = g.intersection(unary_union([zones[i] for i in hit])).length if len(hit) else 0.0
        by_level[level] += inside
        level_total[level] += g.length
        near += inside if level == "inferred" else g.length
    detail = "; ".join(f"{k}: {100 * by_level[k] / level_total[k]:.1f} % в 30 м от оси" for k in sorted(level_total))
    verdict("≥ 80 % длины сети в 30 м от оси улицы или на реальной/восстановленной трассе", near >= 0.8 * total,
            f"{100 * near / max(total, 1):.1f} % ({detail})")
    # нет дыр: жилые и общественные здания района не дальше 150 м от сети
    net_tree = STRtree([f["_utm"] for f in pipes.values()])
    closed = [f["_utm"] for f in by["restriction"] if f["properties"]["restriction_type"] == "prohibited_site"]
    closed_tree = STRtree(closed)
    far, skipped = [], 0
    for f in by["oks_existing"]:
        if f["properties"].get("_kind") not in ("residential", "public"):
            continue
        g = f["_utm"]
        if not region.contains(g.representative_point()):
            continue
        if len(closed_tree.query(g, predicate="intersects")):
            skipped += 1
            continue
        k = net_tree.query_nearest(g, max_distance=150.0)
        if len(k) == 0:
            far.append(f["properties"]["id"])
    verdict("жилые и общественные здания не дальше 150 м от сети", not far,
            f"дальше: {len(far)} {far[:5]}; на запретных территориях (своё теплоснабжение, не проверяются): {skipped}")
    # перспективные ОКС: расход по формуле, без выбросов
    future = by["oks_future"]
    cps = [f for f in by["oks_connection_point"]]
    fut_ids = {f["properties"]["id"] for f in future}
    fut_by_id = {f["properties"]["id"]: f for f in future}
    inside = sum(1 for c in cps if c["properties"]["oks_id"] in fut_ids
                 and fut_by_id[c["properties"]["oks_id"]]["_utm"].contains(c["_utm"]))
    need = CITIES[city]["min_future"]
    verdict(f"перспективных ОКС с точками подключения ≥ {need}", len(cps) >= need and inside == len(cps),
            f"ОКС {len(future)}, точек {len(cps)}, внутри своего контура {inside}")
    t_design = T_DESIGN[city]
    worst, flows = 0.0, []
    for f in future:
        p = f["properties"]
        if p.get("_load_source") == "scheme":  # нагрузка из схемы теплоснабжения, график 150/70
            g = p["heat_load"] * TPH_PER_GCAL
        else:
            g, load = flow_of(heat_demand(p["_kind"], f["_utm"].area * p["levels"], p["levels"], "после 1985",
                                          t_design, new=True))
            g *= meta.get("k_cal", 1.0)
        worst = max(worst, abs(g - p["flow_tph"]) / max(g, 1e-9))
        flows.append(p["flow_tph"])
    q1, q3 = np.percentile(flows, [25, 75]) if flows else (0, 0)
    outliers = [x for x in flows if x > q3 + 3 * (q3 - q1) or x < q1 - 3 * (q3 - q1)]
    verdict("flow_tph по формуле, без выбросов", worst < 0.01,
            f"расхождение с формулой до {100 * worst:.2f} %; т/ч мин {min(flows, default=0):.2f}, медиана "
            f"{np.median(flows) if flows else 0:.2f}, макс {max(flows, default=0):.2f}; за 3 IQR: {len(outliers)}")
    counts = defaultdict(int)
    for f in features:
        p = f["properties"]
        counts[p["object_type"] + ("/" + p["restriction_type"] if "restriction_type" in p else "")] += 1
    shares = share_table(features)
    head = (f"bbox {meta['bbox']}, ячейка растра сети {meta['cell_m']} м" if meta.get("cell_m") else
            f"восемь районов города, вариант {meta.get('variant')}: сеть — граф схемы по улицам и дворам")
    report = [f"# Проверка входа {city}", "", head, "",
              "| Проверка | Итог | Подробности |", "|---|---|---|"] + lines
    report += ["", "## Объекты по типам", "", "| Слой | Объектов |", "|---|---|"]
    report += [f"| {k} | {v} |" for k, v in sorted(counts.items())]
    report += ["", "## Уровни данных", "",
               "| Слой | real, шт | restored, шт | inferred, шт | real | restored | inferred | мера |",
               "|---|---|---|---|---|---|---|---|"]
    for layer, cells in sorted(shares.items()):
        size = sum(c[1] for c in cells.values())
        unit = "длина" if layer in ("heat_network",) or any(
            k in layer for k in ("gas", "cable", "supply", "sewer")) else "площадь" if size > 0 else "число"
        row = [layer] + [str(cells[k][0]) if k in cells else "0" for k in ("real", "restored", "inferred")]
        for k in ("real", "restored", "inferred"):
            part = cells[k][1] / size if size > 0 else cells[k][0] / sum(c[0] for c in cells.values()) if k in cells else 0
            row.append(f"{100 * part:.1f} %" if k in cells else "0 %")
        report.append("| " + " | ".join(row + [unit]) + " |")
    (folder / "check.md").write_text("\n".join(report) + "\n", encoding="utf-8")
    print("\n".join(report))
    sys.exit(0 if ok else 1)


# ---------------------------------------------------------------- картинка района

PNG_M_PER_PX = 2.5
FILL = {"oks_existing": (150, 150, 150), "oks_future": (245, 158, 11), "road": (70, 70, 80), "water": (96, 165, 250),
        "park": (134, 199, 124), "social_area": (196, 181, 253), "prohibited_site": (252, 165, 165),
        "tram_tracks": (180, 120, 60), "railway": (120, 60, 150), "metro": (120, 60, 150)}
LINE = {"gas_pipeline": (234, 179, 8), "power_cable": (168, 85, 247), "water_supply": (6, 182, 212),
        "sewer": (120, 113, 108)}
NETWORK = {"real": (220, 38, 38), "restored": (234, 88, 12), "inferred": (29, 78, 216)}


def render(city, output=None):
    """PNG района: здания, дороги, вода, парки, коммуникации, существующая сеть (реальная и восстановленная —
    красным и оранжевым, достроенная — синим, толщина по Ду), перспективные ОКС и, если есть выход сервиса, новая сеть
    (пурпурным)."""
    from PIL import Image, ImageDraw
    folder = ROOT / "data" / "cities" / city
    features = json.loads((folder / "input.geojson").read_text(encoding="utf-8"))["features"]
    new = json.loads(Path(output).read_text(encoding="utf-8"))["features"] if output else []
    meta = json.loads((folder / "build.json").read_text(encoding="utf-8"))
    region = region_polygon(tuple(meta["bbox"]), city)
    x0, y0, x1, y1 = region.bounds
    step = CITIES[city].get("png_m_per_px", PNG_M_PER_PX)
    w, h = int((x1 - x0) / step) + 1, int((y1 - y0) / step) + 1
    image = Image.new("RGB", (w, h), (250, 250, 247))
    draw = ImageDraw.Draw(image)

    def px(coords):
        return [((x - x0) / step, (y1 - y) / step) for x, y, *_ in coords]

    def polygon(g, color):
        for part in getattr(g, "geoms", [g]):
            if part.geom_type == "Polygon":
                draw.polygon(px(part.exterior.coords), fill=color)
                for hole in part.interiors:
                    draw.polygon(px(hole.coords), fill=(250, 250, 247))

    def line(g, color, width):
        for part in getattr(g, "geoms", [g]):
            draw.line(px(part.coords), fill=color, width=width)

    layer = lambda f: f["properties"].get("restriction_type") or f["properties"]["object_type"]
    order = ["park", "water", "social_area", "prohibited_site", "road", "tram_tracks", "railway", "metro",
             "oks_existing", "oks_future"]
    shapes = [(layer(f), utm(shapely.geometry.shape(f["geometry"])), f["properties"]) for f in features]
    for name in order:
        for kind, g, _ in shapes:
            if kind == name and "Polygon" in g.geom_type:
                polygon(g, FILL[name])
    for kind, g, _ in shapes:
        if kind in LINE:
            line(g, LINE[kind], 1)
    for kind, g, p in shapes:
        if kind == "heat_network":
            line(g, NETWORK[p.get("_source", "inferred")], max(1, int(p["diameter"] / 250) + 1))
    for f in new:
        if f["properties"]["object_type"] == "heat_network" and str(f["properties"].get("variant_id")) == "1":
            line(utm(shapely.geometry.shape(f["geometry"])), (219, 39, 119), 2)
    for kind, g, p in shapes:
        if kind == "heat_chamber":
            x, y = px([(g.x, g.y)])[0]
            c = NETWORK[p.get("_source", "inferred")]
            draw.rectangle((x - 1, y - 1, x + 1, y + 1), fill=c)
        elif kind == "oks_connection_point":
            x, y = px([(g.x, g.y)])[0]
            draw.ellipse((x - 2, y - 2, x + 2, y + 2), fill=(0, 0, 0))
        elif kind == "source":
            x, y = px([(g.x, g.y)])[0]
            draw.ellipse((x - 9, y - 9, x + 9, y + 9), fill=(220, 38, 38), outline=(0, 0, 0), width=2)
    target = folder / ("map_out.png" if output else "map.png")
    image.save(target, optimize=True)
    return target


# ---------------------------------------------------------------- формат датасета организаторов (редакция 18.09)

# фундамент опоры ЛЭП в плане: деревянная или железобетонная опора ВЛ 0,4–10 кВ (power=pole) — квадрат 1×1 м
# (стойка СВ-110 сечением ≈0,3 м с ригелем и котлованом под пасынок), решётчатая опора ВЛ 35–220 кВ (power=tower) —
# 6×6 м по осям фундаментов (типовые опоры П110/П220, база 4–7 м), портал подстанции — 3×3 м; тег width, если есть
SUPPORT_M = {"pole": 1.0, "tower": 6.0, "portal": 3.0}


def address(tags):
    street, house = tags.get("addr:street"), tags.get("addr:housenumber")
    if not house:
        return None
    return ", ".join(x for x in (tags.get("addr:city"), street, house) if x)


def to_dataset(city, minimal):
    """Второй вход data/cities/<город>/input_1809.geojson в формате датасета организаторов (редакция 18.09): все
    здания — restriction с restriction_type = oks (адрес в address), расход — у точки подключения, без oks_existing,
    oks_future и oks_id; опоры ЛЭП — полигоны фундаментов. --minimal оставляет только обязательные атрибуты
    (и _source)."""
    folder = ROOT / "data" / "cities" / city
    meta = json.loads((folder / "build.json").read_text(encoding="utf-8"))
    data = fetch(city, tuple(meta["bbox"]))
    tags = {f"{el['type'][0]}{el['id']}": el.get("tags", {}) for name in ("buildings", "utilities")
            for el in data[name]["elements"]}
    features = json.loads((folder / "input.geojson").read_text(encoding="utf-8"))["features"]
    flows = {f["properties"]["id"]: f["properties"]["flow_tph"] for f in features
             if f["properties"]["object_type"] == "oks_future"}
    keep = {"heat_network": ("diameter",), "heat_chamber": (), "source": ()}
    items = []
    for f in features:
        p = f["properties"]
        kind = p["object_type"]
        g = utm(shapely.geometry.shape(f["geometry"]))
        base = {"id": p["id"], "object_type": kind}
        if kind in ("oks_existing", "oks_future"):
            q = {"id": p["id"], "object_type": "restriction", "restriction_type": "oks"}
            adr = address(tags.get(p["id"], {}))
            if adr:
                q["address"] = adr
            if not minimal:
                q.update({k: v for k, v in p.items() if k in ("building", "levels")})
        elif kind == "oks_connection_point":
            q = {**base, "flow_tph": flows[p["oks_id"]]}
        elif kind == "restriction" and p["restriction_type"] == "power_line_support" and g.geom_type == "Point":
            t = tags.get(p["id"].split("-", 1)[1], {})
            side = number(t.get("width")) or SUPPORT_M.get(t.get("power"), 1.0)
            g = box(g.x - side / 2, g.y - side / 2, g.x + side / 2, g.y + side / 2)
            q = {k: v for k, v in p.items() if not k.startswith("_")}
        elif minimal and kind in keep:
            q = {**base, **{k: p[k] for k in keep[kind]}}
        else:
            q = {k: v for k, v in p.items() if not k.startswith("_")}
        q["_source"] = p.get("_source", "real")
        items.append((g, q))
    out = folder / "input_1809.geojson"
    written, dropped = write_features(out, items, order=DATASET_ORDER)
    print(f"{out}: {written} объектов, отброшено {dropped}", file=sys.stderr)
    return out


def main():
    parser = argparse.ArgumentParser(description="Вход сервиса по реальной карте города (OSM + достраивание)")
    parser.add_argument("command", choices=("build", "check", "fetch", "png", "dataset"))
    parser.add_argument("--out", help="png: выход сервиса, новая сеть поверх")
    parser.add_argument("--format", choices=("section12", "dataset"), default="section12",
                        help="dataset: ещё и input_1809.geojson в формате датасета организаторов (редакция 18.09)")
    parser.add_argument("--minimal", action="store_true", help="dataset: только обязательные атрибуты")
    parser.add_argument("city", choices=sorted(CITIES))
    parser.add_argument("--bbox", help="S,W,N,E вместо района по умолчанию")
    parser.add_argument("--one-source", action="store_true",
                        help="nnovgorod: вариант (б) — один source (Сормовская ТЭЦ), остальные источники — prohibited_site")
    args = parser.parse_args()
    use_zone(args.city)
    bbox = tuple(float(v) for v in args.bbox.split(",")) if args.bbox else CITIES[args.city]["bbox"]
    if args.command == "fetch":
        fetch(args.city, bbox)
    elif args.command == "build":
        print(build(args.city, bbox, args.one_source))
        if args.format == "dataset":
            print(to_dataset(args.city, args.minimal))
    elif args.command == "dataset":
        print(to_dataset(args.city, args.minimal))
    elif args.command == "png":
        print(render(args.city, args.out))
    else:
        check(args.city)


if __name__ == "__main__":
    main()
