"""Перспективная застройка Н. Новгорода по схеме теплоснабжения → future_scheme.geojson (перспективные ОКС).

    uv run --project tools --with pymupdf python scripts/heat_future_nn.py

Источник — Схема теплоснабжения до 2030 г. (актуализация на 2026 г.), Обосновывающие материалы, Глава 2, Прил. 1,
Ч. 3, табл. 2.1 «Прогноз ввода строений различного назначения … до 2030 года»: район, источник, объект с адресом,
срок ввода, площади МКД / ИЖФ / ОДЗ (тыс. м²), расчётная нагрузка (Гкал/ч). Адрес → здание или улица OSM
(cache/nnovgorod/osm/embed_osm.json, scripts/heat_embed_nn.py osm). Контур: стройка OSM у адреса (building=construction,
landuse=construction) — `real`; иначе дома типичной формы на свободном месте у адреса — `inferred`. Нагрузка — из
схемы (`_load_source=scheme`), делится между домами объекта пропорционально площади; flow_tph = нагрузка × 1000 /
(150 − 70) — график 150/70 (Глава 1). Кстовский район в город (8 районов) не входит — его строки не берутся.
"""
import json
import math
import re
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT / "data" / "cities" / "cache" / "nnovgorod"
RAW = ROOT / "data" / "cities" / "raw" / "nnovgorod"
PDF = CACHE / "scheme2026" / "002.001. Глава 2. Приложение 1 Часть 3.pdf"
REF = ("Схема теплоснабжения г. Н. Новгорода до 2030 г. (актуализация на 2026 г.), шифр 22401, Обосновывающие "
       "материалы, Гл. 2 Прил. 1 Ч. 3, табл. 2.1 «Прогноз ввода строений различного назначения … до 2030 года»")

YEAR = re.compile(r"^20[2-3]\d$")
VALUE = re.compile(r"^-?\d[\d ]*(?:,\d+)?$")


def num(s):
    s = (s or "").strip()
    return float(s.replace(" ", "").replace(",", ".")) if VALUE.match(s) else 0.0


def parse():
    """Строки табл. 2.1 (ячейки таблиц PDF): [{district, source, name, year, mkd, izhf, odz, total, load}], площади —
    тыс. м²; проверка — суммы нагрузок по источникам против строк «<источник> Итог»."""
    import pymupdf
    items, totals, district = [], [], None
    clean = lambda c: re.sub(r"\s+", " ", (c or "").replace("\n", " ")).strip()
    for page in list(pymupdf.open(PDF))[6:]:
        for tab in page.find_tables().tables:
            for row in tab.extract():
                c = [clean(x) for x in row] + [""] * 10
                vals = [num(x) for x in c[3:9]]
                load = next((v for x, v in zip(c[7:9], vals[4:6]) if x), 0.0)
                if YEAR.match(c[2]):
                    items.append(dict(district=district, source=c[0], name=c[1], year=int(c[2]), mkd=vals[0],
                                      izhf=vals[1], odz=vals[2], total=vals[3], load=load))
                elif c[0].endswith(" район") and not c[1]:
                    district = c[0]
                elif c[0].endswith("Итог") and c[0] != "Общий итог":
                    totals.append((district, c[0][:-4].strip(), load))
                elif c[1] and items and not c[0].startswith("Наименование"):
                    items[-1]["name"] += " " + c[1]  # перенос ячейки на следующую страницу
    loads = defaultdict(float)
    for it in items:
        loads[(it["district"], it["source"])] += it["load"]
    bad = [(d, s, v, round(loads[(d, s)], 4)) for d, s, v in totals if abs(v - loads[(d, s)]) > 1e-3]
    return items, bad


# ---------------------------------------------------------------- адрес → место

STREET_TYPES = {"улица", "проспект", "переулок", "шоссе", "набережная", "бульвар", "площадь", "проезд", "съезд",
                "тупик", "спуск", "микрорайон", "аллея", "линия", "парк", "имени", "им"}
NEAR = re.compile(r"(?:у|напротив|рядом\s+с|за|в\s+районе|от|возле|около)\s+(?:д\.|дом\w*|домов)\s*(?:№+\s*)?(\d+[а-яА-Я]?(?:/\d+)?)")
AT = re.compile(r"^[\s,.]*(?:д\.|дом\s|№)?\s*(\d+[а-яА-Я]?(?:/\d+)?)(?![\d])")
TPH_PER_GCAL = 1000.0 / (150.0 - 70.0)   # т/ч на 1 Гкал/ч при графике 150/70


def stems(name):
    """Основы слов названия улицы OSM: слово без трёх последних букв (не короче 4): «Светлоярская улица» →
    ['светлоярс']; числа — как есть («50-летия Победы» → ['50', 'лет', 'побе'])."""
    out = []
    for w in re.findall(r"[а-яё]+|\d+", name.lower().replace("ё", "е")):
        if w.isdigit():
            out.append(w)
        elif w not in STREET_TYPES and len(w) >= 3:
            out.append(w[:max(3, len(w) - 3)])
    return out


class Geo:
    def __init__(self):
        sys.path.insert(0, str(Path(__file__).parent))
        import shapely
        from shapely import STRtree
        from shapely.geometry import LineString, Polygon
        from shapely.ops import unary_union
        from heat_embed_nn import load_layers, utm_xy, city_region, CARRIAGEWAY
        self.shapely = shapely
        layers = load_layers()
        self.districts = {n.split()[0]: shapely.Polygon(utm_xy(list(g.exterior.coords))) if g.geom_type == "Polygon"
                          else unary_union([shapely.Polygon(utm_xy(list(p.exterior.coords))) for p in g.geoms])
                          for n, g in city_region()}
        self.city = unary_union(list(self.districts.values()))
        streets = defaultdict(list)
        axes = []
        for _, t, _, pts in layers["highways"]:
            line = LineString(utm_xy(pts))
            if t.get("name"):
                streets[t["name"]].append(line)
            if t.get("highway") in CARRIAGEWAY and t.get("service") not in ("parking_aisle", "driveway"):
                axes.append(line)
        self.streets = {n: unary_union(v) for n, v in streets.items()}
        self.street_stems = {n: stems(n) for n in self.streets}
        self.axes = axes
        self.axis_tree = STRtree(axes)
        self.buildings, self.addr, self.construction = [], defaultdict(list), []
        for bid, t, rings in layers["buildings"]:
            g = shapely.make_valid(Polygon(utm_xy(rings[0])))
            if g.is_empty or g.area < 4:
                continue
            g = max(getattr(g, "geoms", [g]), key=lambda x: x.area)
            if g.geom_type != "Polygon":
                continue
            self.buildings.append((bid, t, g))
            if "addr:street" in t and "addr:housenumber" in t:
                self.addr[re.sub(r"\s+", "", t["addr:housenumber"]).upper()].append(len(self.buildings) - 1)
            if t.get("building") == "construction":
                self.construction.append(len(self.buildings) - 1)
        self.btree = STRtree([g for _, _, g in self.buildings])
        self.sites = [(sid, t, shapely.make_valid(Polygon(utm_xy(rings[0])))) for sid, t, rings in layers["sites"]
                      if t.get("landuse") == "construction"]
        self.placed = []   # контуры уже поставленных перспективных ОКС
        # запреты места те же, что проверяет сборка входа (scripts/city_geojson.py, scheme_future): полигоны дорог,
        # воды, путей, парков, соцобъектов, запретных территорий и трубы схемы с охранной зоной 5 м
        import city_geojson as cg
        cg.use_zone("nnovgorod")
        bbox = cg.CITIES["nnovgorod"]["bbox"]
        data = cg.fetch("nnovgorod", bbox)
        region = cg.region_polygon(bbox, "nnovgorod")
        objs = {k: cg.objects(data[k]) for k in ("highways", "rail", "areas")}
        del data
        items = cg.roads(objs["highways"], region)[0] + cg.tracks(objs["rail"], region) + cg.area_layers(objs["areas"], region)
        blocked = [g for g, p in items if p["restriction_type"] in cg.SCHEME_BLOCK]
        pipes = [LineString(utm_xy(f["geometry"]["coordinates"])).buffer(cg.SCHEME_GAP_PIPE_M + 1.0)
                 for f in json.loads((RAW / "restored_heat.geojson").read_text())["features"]
                 if f["properties"]["_class"] == "heat_network" and f["properties"]["_pipe"] == "heating"]
        self.block_tree = STRtree(blocked + pipes)

    def find_streets(self, text):
        """Улицы OSM, названные в тексте: [(позиция конца упоминания, имя улицы)], самые полные совпадения."""
        low = text.lower().replace("ё", "е")
        words = [(m.start(), m.end(), m.group()) for m in re.finditer(r"[а-яё]+|\d+", low)]
        hits = []
        for name, st in self.street_stems.items():
            if not st or all(x.isdigit() for x in st):
                continue
            ends = []
            for s in st:
                pos = [e for b, e, w in words if (w == s if s.isdigit() else w.startswith(s))]
                if not pos:
                    break
                ends.append(max(pos) if len(st) > 1 else pos[0])
            else:
                hits.append((max(ends), -len(st), name))
        best = {}
        for end, n, name in sorted(hits):
            best.setdefault(end, (n, name))
        return [(end, name) for end, (n, name) in sorted(best.items())]

    def building_at(self, street, house, region):
        st = stems(street)
        for hh in (house.upper(), house.upper().split("/")[0], re.match(r"\d+", house).group()):
            for i in self.addr.get(hh, []):
                bid, t, g = self.buildings[i]
                ost = stems(t["addr:street"])
                if all(any(o == s or o.startswith(s) or s.startswith(o) for o in ost) for s in st) \
                        and region.contains(g.representative_point()):
                    return i
        return None

    def locate(self, item, sources):
        """(точка, связь, адрес): связь — at (сам адрес), near (у дома), streets (в границах улиц / по улице),
        source (у источника тепла из графы «источник»); None — не найдено."""
        from shapely.geometry import Point
        from shapely.ops import nearest_points
        d = (item["district"] or "").split()[0]
        region = self.districts.get(d, self.city).buffer(1500) if d in self.districts else self.city
        text = item["name"]
        found = [(e, n) for e, n in self.find_streets(text) if self.streets[n].intersects(region)]
        for end, name in found:
            near = NEAR.search(text[end:end + 70])
            at = AT.match(text[end:end + 20])
            for m, how in ((at, "at"), (near, "near")):
                if m:
                    i = self.building_at(name, m.group(1), region)
                    if i is not None:
                        return self.buildings[i][2].centroid, how, f"{name}, {m.group(1)}", i
        if found:
            geoms = [self.streets[n].intersection(region) for _, n in found]
            geoms = [g for g in geoms if not g.is_empty]
            if len(geoms) >= 2:
                pts = [nearest_points(a, b) for k, a in enumerate(geoms) for b in geoms[k + 1:]]
                pts = [(p, q) for p, q in pts if p.distance(q) < 1500]
                if pts:
                    x = sum(p.x + q.x for p, q in pts) / (2 * len(pts))
                    y = sum(p.y + q.y for p, q in pts) / (2 * len(pts))
                    return Point(x, y), "streets", "в границах " + ", ".join(n for _, n in found), None
            g = geoms[0] if geoms else None
            if g is not None:
                return g.interpolate(0.5, normalized=True), "street", found[0][1], None
        src = sources.get(item["source"])
        if src is not None:
            return src, "source", item["source"], None
        return None

    def free(self, poly, gap=8.0):
        if not self.city.contains(poly):
            return False
        big = poly.buffer(gap)
        if len(self.btree.query(big, predicate="intersects")):
            return False
        if len(self.axis_tree.query(poly.buffer(7.0), predicate="intersects")):
            return False
        if len(self.block_tree.query(poly.buffer(1.0), predicate="intersects")):
            return False
        return not any(big.intersects(p) for p in self.placed[-400:]) and not any(
            poly.buffer(12.0).intersects(p) for p in self.placed)

    def put(self, centre, w, l, radius):
        """Прямоугольник w × l на свободном месте ближе всего к centre (спираль с шагом 12 м до radius), вдоль
        ближайшей оси улицы; None — места нет."""
        from shapely.affinity import rotate, translate
        from shapely.geometry import box
        i = self.axis_tree.query_nearest(centre, all_matches=False)
        ang = 0.0
        if len(i):
            ax = self.axes[int(i[0])]
            p = ax.interpolate(ax.project(centre))
            q = ax.interpolate(min(ax.length, ax.project(centre) + 5.0))
            ang = math.degrees(math.atan2(q.y - p.y, q.x - p.x)) if p.distance(q) > 0.1 else 0.0
        base = box(-l / 2, -w / 2, l / 2, w / 2)
        step = 12.0
        for r in range(0, int(radius / step) + 1):
            ring = [(0, 0)] if r == 0 else [(r * step * math.cos(t), r * step * math.sin(t))
                                            for t in [2 * math.pi * k / (6 * r) for k in range(6 * r)]]
            for dx, dy in ring:
                for a in (ang, ang + 90.0):
                    poly = translate(rotate(base, a, origin=(0, 0)), centre.x + dx, centre.y + dy)
                    if self.free(poly):
                        self.placed.append(poly)
                        return poly
        return None


def forms(item):
    """Дома объекта: [(площадь этажей м², этажей, ширина, длина)]. Площадь — из схемы; без площади — по нагрузке
    (0,1 Гкал/ч на 1 000 м², средняя удельная по табл. 2.1). МКД до 20 тыс. м² в доме, 17/12/9/5 этажей по площади,
    секция шириной 16 м; ОДЗ — до 15 тыс. м², 3–5 этажей, прямоугольник 1 : 1,5."""
    area = item["total"] * 1000.0 or item["load"] / 0.1 * 1000.0
    mkd = item["mkd"] >= item["odz"] and item["mkd"] > 0
    cap = 20000.0 if mkd else 15000.0
    n = max(1, math.ceil(area / cap))
    out = []
    for _ in range(n):
        a = area / n
        if mkd:
            lv = 17 if a >= 12000 else 12 if a >= 6000 else 9 if a >= 3000 else 5
            foot = a / lv
            w = 16.0
            l = max(16.0, foot / w)
        else:
            lv = 5 if a >= 6000 else 3 if a >= 600 else 1
            foot = a / lv
            w = max(8.0, math.sqrt(foot / 1.5))
            l = 1.5 * w
        out.append((a, lv, w, l))
    return out


def source_points(geo):
    """Источник из графы «источник» → точка: heat_sources.geojson по совпадению названия, иначе адрес в названии."""
    sys.path.insert(0, str(Path(__file__).parent))
    from heat_embed_nn import utm_xy
    from shapely.geometry import Point
    feats = json.loads((RAW / "heat_sources.geojson").read_text())["features"]
    pts = {}
    for f in feats:
        (x, y), = utm_xy([f["geometry"]["coordinates"]])
        pts[f["properties"]["name"]] = Point(x, y)
    return pts


def build():
    from shapely.geometry import mapping
    sys.path.insert(0, str(Path(__file__).parent))
    from heat_embed_nn import TO_WGS
    items, bad = parse()
    geo = Geo()
    srcs = source_points(geo)
    key = lambda s: re.sub(r"[^а-яё0-9]", "", s.lower())
    by_key = {key(n): p for n, p in srcs.items()}
    sources = {}
    for it in items:
        s = it["source"]
        if s in sources:
            continue
        p = by_key.get(key(s)) or next((p for k, p in by_key.items() if key(s) and (key(s) in k or k in key(s))), None)
        if p is None:
            loc = geo.locate(dict(name=s, district=it["district"], source=""), {})
            p = loc[0] if loc and loc[1] in ("at", "near") else None
        sources[s] = p
    used_constr, feats, stats = set(), [], defaultdict(int)
    for n, it in enumerate(items, 1):
        if it["district"] == "Кстовский район":
            stats["skip_kstovo"] += 1
            continue
        if it["source"].startswith("Индивидуальное") or it["load"] <= 0:
            stats["skip_individual_or_no_load"] += 1
            continue
        loc = geo.locate(it, sources)
        if loc is None:
            stats["not_located"] += 1
            continue
        centre, how, addr, bi = loc
        stats[f"geocode_{how}"] += 1
        shapes = forms(it)
        radius = 250.0 if how in ("at", "near") else 600.0
        total_area = sum(a for a, *_ in shapes)
        # реальные контуры: здание по самому адресу, если оно строится или новое; стройки OSM рядом
        real = []
        if how == "at" and bi is not None:
            t = geo.buildings[bi][1]
            if t.get("building") == "construction" or re.match(r"20(2[3-9]|3)", t.get("start_date", "")):
                real.append(bi)
        near = sorted((geo.buildings[i][2].distance(centre), i) for i in geo.construction
                      if i not in used_constr and i not in real and geo.buildings[i][2].distance(centre) <= radius / 2)
        real += [i for _, i in near][:max(0, len(shapes) - len(real))]
        for k, (a, lv, w, l) in enumerate(shapes):
            if k < len(real):
                i = real[k]
                used_constr.add(i)
                bid, t, poly = geo.buildings[i]
                geo.placed.append(poly)
                level, contour, osm = "real", "osm_construction" if t.get("building") == "construction" else "osm_building", bid
            else:
                poly = geo.put(centre, w, l, radius)
                if poly is None:
                    stats["no_free_place"] += 1
                    continue
                level, contour, osm = "inferred", "typical", None
            load = it["load"] * a / total_area
            x, y = poly.exterior.xy
            lon, lat = TO_WGS.transform(list(x), list(y))
            props = {"object_type": "oks_future", "_source": level, "heat_load": round(load, 4),
                     "flow_tph": round(load * TPH_PER_GCAL, 3), "_load_source": "scheme", "_ref": REF,
                     "_item": n, "_name": it["name"], "_scheme_source": it["source"], "_district": it["district"],
                     "_year": it["year"], "_address": addr, "_geocode": how, "_contour": contour,
                     "floor_area_m2": round(a), "levels": lv}
            if osm:
                props["_osm"] = osm
            feats.append({"type": "Feature", "properties": props,
                          "geometry": {"type": "Polygon", "coordinates": [[[round(p, 7), round(q, 7)] for p, q in zip(lon, lat)]]}})
            stats[f"buildings_{level}"] += 1
    with open(RAW / "future_scheme.geojson", "w") as fh:
        fh.write('{"type":"FeatureCollection","features":[\n')
        fh.write(",\n".join(json.dumps(f, ensure_ascii=False, separators=(",", ":")) for f in feats))
        fh.write("\n]}\n")
    print(json.dumps(dict(stats), ensure_ascii=False), file=sys.stderr)
    print(f"нагрузка: в файле {sum(f['properties']['heat_load'] for f in feats):.1f} Гкал/ч из "
          f"{sum(i['load'] for i in items if i['district'] != 'Кстовский район'):.1f} по 8 районам", file=sys.stderr)


if __name__ == "__main__":
    build()
