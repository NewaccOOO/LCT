"""Таблицы участков тепловых сетей из Схемы теплоснабжения Н. Новгорода (актуализация на 2026 г.) -> CSV.

    uv run --project tools --with pymupdf python scripts/heat_tables_nn.py <папка с PDF> <out.csv>

Источник — «Обосновывающие материалы…, Глава 1, Приложение 2 «Тепловые сети», Части 1–6» (шифр 22401.ОМ-ПСТ.001.002):
Части 1–4 — АО «Теплоэнерго» по РТС: «имя начального узла, имя конечного узла, диаметр (Ду), длина, вид участка,
район» — это граф сети электронной модели (узлы ТК-…, ВД-…, ОТВ-…, УТ-…, ПТ-<улица>,<дом> — потребитель);
Часть 5 — ООО «Теплосети» (Автозаводская ТЭЦ): трубопровод, участок, назначение, наружный d (м), п/о, длина, год;
Часть 6 — ООО «Нижновтеплоэнерго», ООО «КСК», ООО «СТН-Энергосети», ПАО «НИТЕЛ»: магистраль, «ТК a - ТК b»,
наружный d (мм), длина 2-трубная, год, прокладка. Ду сервиса: по ряду 50…1400 (наружный d → Ду как в СПб).

    uv run --project tools --with pymupdf python scripts/heat_tables_nn.py sources <Глава 4.pdf> <addr.json> \
        <osm_heat_sources.geojson> <out.geojson>

Источники тепла из Главы 4 (балансы мощности, шифр 22401.ОМ-ПСТ.004.000, табл. 2.1–2.6): название с адресом,
организация, установленная мощность и фактическая нагрузка 2024 г. (Гкал/ч); координаты — по адресу из OSM
(addr_<город>.json от heat_pbf.py), ТЭЦ — по источникам OSM. Пишет <out.geojson> (найденные) и <out>.csv (все).
    uv run --project tools --with pymupdf python scripts/heat_tables_nn.py graph <sections.csv.gz> <addr.json> \
        <heat_sources.csv> <out_dir>

Граф АО «Теплоэнерго» (Части 1–4): компоненты связности, источники в каждой (по адресу из Главы 4), перемычки;
узлы с адресом («ПТ-Чаад,24а», «ул.Базарная,6», «ЦТП-Минина,1а») → здания OSM того же района. Пишет
<out_dir>/scheme_anchors.geojson (привязанные узлы — опорные точки восстановления) и scheme_components.csv.
"""
import json
import csv
import gzip
import re
import sys
from pathlib import Path

import pymupdf

DN = [50, 65, 80, 100, 125, 150, 200, 250, 300, 400, 500, 600, 700, 800, 900, 1000, 1200, 1400]
OUTER = {57: 50, 76: 65, 89: 80, 108: 100, 114: 100, 133: 125, 159: 150, 219: 200, 273: 250, 325: 300, 377: 300,
         426: 400, 530: 500, 529: 500, 630: 600, 720: 700, 820: 800, 920: 900, 1020: 1000, 1220: 1200, 1420: 1400}
KIND = re.compile(r"^(квартальный|магистральный|перемычка|т/н |недействующая|паропровод|ТС сторонний)")
NUM = re.compile(r"^\d+(?:[.,]\d+)?$")
TITLE = re.compile(r"^Таблица [\d.]+ ?[–-] ?(Характеристики участков тепловых сетей.*?)(?:\s*\((?:начало|продолжение)\))?\s*$")


def dn(d):
    return min(DN, key=lambda x: abs(x - d))


def dn_outer(d):
    d = round(d)
    return OUTER.get(d) or dn(d * 0.9)


def fnum(s):
    return float(s.replace(",", "."))


def lines_of(pdf):
    for pno, page in enumerate(pymupdf.open(pdf), 1):
        for ln in page.get_text().split("\n"):
            yield pno, ln.strip()


def parse(pdf, part):
    L = list(lines_of(pdf))
    txt = [t for _, t in L]
    table, rows = "", []
    for i, (pno, t) in enumerate(L):
        m = TITLE.match(t)
        if m and "....." not in t:
            table = m.group(1).replace("Характеристики участков тепловых сетей", "").strip(" ,")
            continue
        base = {"part": part, "page": pno, "table": table}
        if part <= 4 and KIND.match(t) and i >= 4 and NUM.match(txt[i - 2]) and NUM.match(txt[i - 1]):
            d = fnum(txt[i - 2])
            rows.append(dict(base, org="АО «Теплоэнерго»", system=table, node_from=txt[i - 4], node_to=txt[i - 3],
                             d_label_mm=d, diameter=dn(d), length_m=fnum(txt[i - 1]), kind=t, district=txt[i + 1]))
        elif part == 5 and re.fullmatch(r"\d,\d+", t) and re.fullmatch(r"[а-я/]{1,3}", txt[i + 1]) and NUM.match(txt[i + 2]):
            d = fnum(t) * 1000
            rows.append(dict(base, org="ООО «Теплосети»", system=txt[i - 4], line=txt[i - 3], section=txt[i - 2],
                             purpose=txt[i - 1], d_label_mm=d, diameter=dn_outer(d), pipe=txt[i + 1],
                             length_m=fnum(txt[i + 2]), year=txt[i + 4] if i + 4 < len(txt) else ""))
        elif part == 6 and re.fullmatch(r"[ПОГЦ](?:, ?[ПОГЦ])*", t) and NUM.match(txt[i + 1]) and NUM.match(txt[i + 2]):
            d, sec = fnum(txt[i + 1]), txt[i - 1]
            a, _, b = sec.partition(" - ") if " - " in sec else sec.partition("-")
            rows.append(dict(base, org=table.split("»")[0] + "»" if "«" in table else table, system=table,
                             line=txt[i - 2], section=sec, node_from=a.strip(), node_to=b.strip(), pipe=t,
                             d_label_mm=d, diameter=dn_outer(d), length_m=fnum(txt[i + 2]),
                             year=txt[i + 3], kind=txt[i + 4]))
    return rows


def main(folder, out):
    rows = []
    for f in sorted(Path(folder).glob("*Приложение 2 Часть *.pdf")):
        part = int(re.search(r"Часть (\d)", f.name).group(1))
        r = parse(f, part)
        print(f"{f.name}: {len(r)} участков, {sum(x['length_m'] for x in r) / 1000:.1f} км", file=sys.stderr)
        rows += r
    cols = ["part", "page", "org", "system", "table", "line", "section", "node_from", "node_to", "purpose", "pipe",
            "d_label_mm", "diameter", "length_m", "kind", "district", "year"]
    with (gzip.open(out, "wt", newline="") if out.endswith(".gz") else open(out, "w", newline="")) as fh:
        w = csv.DictWriter(fh, cols, extrasaction="ignore")
        w.writeheader()
        w.writerows(rows)


SKIP = re.compile(r"^$|^20\d\d$|^Показатель$|^Наименование показателя$|^Единица$|^измерения$|^Гкал/ч$"
                  r"|ОБОСНОВЫВАЮЩИЕ|22401\.ОМ|НАГРУЗКИ ПОТРЕБИТЕЛЕЙ»|^\d{1,3}$")
STOP = re.compile(r"^(Потери|Резерв|Тепловая|\"Фактическая|Располагаемая|Установленная|отопление|ГВС|Таблица|Доля|Прирост"
                  r"|Максимум|Дефицит|Присоединенная|Затраты|Ограничения|-?\d[\d ]*(,\d+)?$)")
VAL = re.compile(r"^-?\d[\d ]*(?:,\d+)?$")
TABLE_ORG = {"2.1": "ПАО «Т Плюс»", "2.2": "ООО «Автозаводская ТЭЦ»", "2.3": "ООО «Автозаводская ТЭЦ»",
             "2.4": "АО «Теплоэнерго»", "2.6": "Кстовский район (присоединён в 2025 г.)"}
TPL = {"2.1": "Сормовская ТЭЦ", "2.2": "Автозаводская ТЭЦ", "2.3": "Котельная «Ленинская» Автозаводской ТЭЦ, Монастырка ул., 5А"}
STREET_WORDS = {"ул", "улица", "пр", "пр-т", "проспект", "пер", "переулок", "ш", "шоссе", "наб", "набережная", "б-р",
                "бульвар", "пл", "площадь", "проезд", "пр-д", "д", "кот", "котельная", "бмк", "бмку", "пос", "п", "мкр",
                "микрорайон", "с", "к", "кп", "г", "литер", "лит"}


def balance_rows(lines):
    """Глава 4, табл. 2.1–2.6: (таблица, название, УТМ 2024, нагрузка 2024) по каждому источнику."""
    table = ""
    for i, t in enumerate(lines):
        if t.startswith("Таблица 2.") and "...." not in t:
            table = re.match(r"Таблица (2\.\d)", t).group(1)
        if not t.startswith("Установленная тепловая мощность") or table == "2.7":
            continue
        vals = [v for v in lines[i + 1:i + 14] if VAL.match(v)][:11]
        k, name = i - 1, []
        while k > 0 and (SKIP.search(lines[k]) or lines[k].startswith("===")):
            k -= 1
        while k > 0 and not STOP.match(lines[k]) and not SKIP.search(lines[k]) and len(name) < 3:
            name.insert(0, lines[k])
            k -= 1
        load = None
        for j in range(i, min(i + 60, len(lines))):
            if lines[j].startswith(('"Фактическая" тепловая нагрузка', "Присоединенная тепловая нагрузка, в т.ч",
                                    "Тепловая нагрузка потребителей", "Присоединенная тепловая нагрузка, Гкал")):
                v = [x for x in lines[j + 1:j + 14] if VAL.match(x)]
                load = fnum(v[4].replace(" ", "")) if len(v) > 4 else None
                break
        yield table, TPL.get(table) or " ".join(name), fnum(vals[4].replace(" ", "")) if len(vals) > 4 else None, load


def words(s):
    return [w for w in re.findall(r"[а-яё]+", s.lower().replace("ё", "е")) if w not in STREET_WORDS and len(w) > 1]


def house(s):
    m = re.search(r"(\d+)\s*-?\s*([а-яА-Я])?(?:\s*(?:к|корп)\.?\s*(\d+))?\s*(?:\(|$|,|\s)", s + " ")
    return (m.group(1) + (m.group(2) or "").upper() + (f"к{m.group(3)}" if m.group(3) else "")) if m else None


def geocoder(addr_path, extra=()):
    """Адрес «улица, дом» → точка здания OSM; для городских таблиц — только в 8 районах (в округ с 2025 г. входит
    Кстовский район с одноимёнными улицами)."""
    sys.path.insert(0, str(Path(__file__).parent))
    from heat_osm import districts
    from shapely.geometry import Point
    from shapely.ops import unary_union
    from shapely.prepared import prep
    city8 = prep(unary_union([g for _, g in districts(Path(addr_path).parent / "adm_nnovgorod.json")]))
    idx = {}
    for e in list(json.load(open(addr_path))["elements"]) + list(extra):
        t = e["tags"]
        if "addr:street" in t and "addr:housenumber" in t:
            h = re.sub(r"\s+", "", t["addr:housenumber"]).upper().replace("К", "к")
            idx.setdefault(h, []).append((words(t["addr:street"]), e["lon"], e["lat"], t["addr:street"],
                                          city8.contains(Point(e["lon"], e["lat"]))))

    def find(name, anywhere=False):
        m = re.search(r"(.*?)[, ]\s*(?:д\.\s*)?(\d+\s*-?\s*[а-яА-Я]?(?:\s*к\.?\s*\d+)?)\b", name)
        if not m:
            return None
        sw, h = words(m.group(1).split(",")[-1].split("»")[-1]), house(m.group(2))
        if not sw or not h:
            return None
        # котельная — обычно пристройка «31В» без адреса в OSM: тогда дом «31» той же улицы (≈ 50–200 м)
        for hh, how in ((h, ""), (re.sub(r"к\d+$", "", h), ""), (re.match(r"\d+", h).group(), " (по дому без литеры)")):
            for ow, lon, lat, st, in8 in idx.get(hh, []):
                if (in8 or anywhere) and all(any(o.startswith(w[:5]) for o in ow) for w in sw[-2:]):
                    return lon, lat, f"{st}, {hh}{how}"
        return None
    return find


def sources(ch4, addr, osm_src, out):
    lines = [t for _, t in lines_of(ch4)]
    fc = json.load(open(osm_src))["features"]
    find = geocoder(addr, [{"tags": f["properties"], "lon": f["geometry"]["coordinates"][0],
                            "lat": f["geometry"]["coordinates"][1]} for f in fc])
    osm = [(f["properties"].get("name", ""), f["geometry"]["coordinates"], f["properties"]["_osm_id"])
           for f in fc]
    rows, feats = [], []
    for table, name, cap, load in balance_rows(lines):
        org = TABLE_ORG.get(table) or (re.findall(r"(?:ООО|АО|ОАО|ПАО|ФГУП|ФКУ|ГБУЗ|МП|ЗАО|ФГБУ|ГБПОУ|НОУ)\s*«[^»]+»?", name)
                                       or ["прочие"])[-1]
        r = {"name": name, "org": org, "capacity_gcal_h": cap, "load_gcal_h": load, "_table": f"Глава 4, табл. {table}"}
        hit = next(((c[0], c[1], f"OSM {i}") for n, c, i in osm if table in TPL and n and TPL[table] == n), None) \
            or find(name, anywhere=table == "2.6")
        if hit and not hit[2].startswith("OSM"):  # адрес → ближайший источник OSM в 250 м, если есть
            near = min(osm, key=lambda o: (o[1][0] - hit[0]) ** 2 + ((o[1][1] - hit[1]) * 1.8) ** 2)
            if ((near[1][0] - hit[0]) ** 2 + ((near[1][1] - hit[1]) * 1.8) ** 2) ** 0.5 * 62000 < 250:
                hit = (near[1][0], near[1][1], f"OSM {near[2]} у адреса {hit[2]}")
        if hit:
            r.update(lon=round(hit[0], 6), lat=round(hit[1], 6), _geocode=hit[2])
            feats.append({"type": "Feature", "geometry": {"type": "Point", "coordinates": [r["lon"], r["lat"]]},
                          "properties": dict(r, _source="scheme")})
        rows.append(r)
    json.dump({"type": "FeatureCollection", "features": feats}, open(out, "w"), ensure_ascii=False, indent=0)
    with open(Path(out).with_suffix(".csv"), "w", newline="") as fh:
        w = csv.DictWriter(fh, ["name", "org", "capacity_gcal_h", "load_gcal_h", "lon", "lat", "_geocode", "_table"])
        w.writeheader()
        w.writerows(sorted(rows, key=lambda r: -(r["capacity_gcal_h"] or 0)))
    print(f"источников {len(rows)}, с координатами {len(feats)}, мощность {sum(r['capacity_gcal_h'] or 0 for r in rows):.0f} Гкал/ч",
          file=sys.stderr)

STD = re.compile(r"^(ОТВ|ТК|ВД|УТ|ВДГ|ШО|ПЕР|РД|ЗАГ|ПАВ|НПС|РСТ|И\.П\.)[- ]")


def node_addr(n):
    """«ПТ-Кр.Зорь,11а гвс» → (['кр', 'зорь'], '11А'); «ул.Базарная,6» → (['базарная'], '6')."""
    n = re.sub(r"^(ПТ|ЦТП[ОИГ]?|КП|ГЭУ|ПТЭ)\s*-\s*", "", n)
    m = re.match(r"(.*?[А-Яа-яё])\s*[,.]\s*(\d+\s*[а-яА-Я]?)(?![\d])", n)
    if not m:
        return None, None
    toks = [t for t in re.split(r"[.\s-]+", m.group(1).lower().replace("ё", "е")) if len(t) > 1 and t not in STREET_WORDS]
    return toks, re.sub(r"\s+", "", m.group(2)).upper()


def graph(sections, addr, sources_csv, out_dir):
    sys.path.insert(0, str(Path(__file__).parent))
    from heat_osm import districts
    from shapely.geometry import Point
    from shapely.prepared import prep
    out_dir = Path(out_dir)
    dist = [(n.split()[0], prep(g)) for n, g in districts(Path(addr).parent / "adm_nnovgorod.json")]
    idx = {}
    for e in json.load(open(addr))["elements"]:
        t = e["tags"]
        if "addr:street" not in t:
            continue
        d = next((n for n, g in dist if g.contains(Point(e["lon"], e["lat"]))), None)
        h = re.sub(r"\s+", "", t["addr:housenumber"]).upper()
        for hh in {h, re.match(r"\d*", h).group()}:
            idx.setdefault((d, hh), []).append((words(t["addr:street"]), e["lon"], e["lat"], f'{t["addr:street"]}, {h}'))

    def locate(toks, h, d):
        for hh, how in ((h, "exact"), (re.match(r"\d*", h).group(), "house_digits")):
            cand = {c[3].rsplit(",", 1)[0]: c for c in idx.get((d, hh), [])
                    if all(any(o.startswith(tk) for o in c[0]) for tk in toks)}
            if len(cand) == 1:
                return next(iter(cand.values())), how
            if len(cand) > 1:
                return None, "ambiguous"
        return None, "no_match"

    op = gzip.open if sections.endswith(".gz") else open
    te = [r for r in csv.DictReader(op(sections, "rt")) if r["part"] in "1234"]
    par = {}

    def root(x):
        while par.setdefault(x, x) != x:
            par[x] = par[par[x]]
            x = par[x]
        return x
    for r in te:
        par[root(r["node_from"])] = root(r["node_to"])
    ndist = {}
    for r in te:
        for n in (r["node_from"], r["node_to"]):
            ndist.setdefault(n, r["district"])
    srcs = []
    for r in csv.DictReader(open(sources_csv)):
        m = re.search(r"(?:^|,|\s)([^,]*?[А-Яа-яё][^,]*?)[, ]\s*(?:д\.\s*)?(\d+)\s*-?\s*([а-яА-Я])?\b", r["name"])
        if m:
            srcs.append((words(m.group(1)), m.group(2) + (m.group(3) or "").upper(), r))
    comps, stat = {}, {}
    for r in te:
        c = stat.setdefault(root(r["node_from"]), {"km": 0.0, "edges": 0, "per_km": 0.0, "dmax": 0, "district": {}})
        c["km"] += float(r["length_m"]) / 1000
        c["edges"] += 1
        c["dmax"] = max(c["dmax"], int(float(r["diameter"])))
        c["per_km"] += float(r["length_m"]) / 1000 if r["kind"].startswith("перемычка") else 0
        c["district"][r["district"]] = c["district"].get(r["district"], 0) + 1
    feats, res = [], {}
    for n, d in ndist.items():
        comp = root(n)
        s_ = comps.setdefault(comp, {"consumers": 0, "anchored": 0, "sources": []})
        if n == "Сормовская ТЭЦ":
            s_["sources"].append("Сормовская ТЭЦ (696)")
            continue
        if STD.match(n):
            continue
        toks, h = node_addr(n)
        is_cons = n.startswith("ПТ")
        s_["consumers"] += is_cons
        src = None if is_cons or not toks else next(
            (x for x in srcs if x[1] == h and all(any(w.startswith(tk[:5]) for w in x[0]) for tk in toks[-2:])), None)
        if src:
            s_["sources"].append(f'{src[2]["name"]} ({float(src[2]["capacity_gcal_h"] or 0):g})')
        if not toks:
            res["no_address"] = res.get("no_address", 0) + 1
            continue
        hit, how = locate(toks, h, d)
        res[how] = res.get(how, 0) + 1
        if hit:
            s_["anchored"] += is_cons
            role = "source" if src else "consumer" if is_cons else "substation" if n.startswith(("ЦТП", "КП")) else "other"
            feats.append({"type": "Feature", "geometry": {"type": "Point", "coordinates": [round(hit[1], 6), round(hit[2], 6)]},
                          "properties": {"node": n, "role": role, "_component": comp, "_district": d, "_addr": hit[3],
                                         "_match": how, "_source": "restored",
                                         "_ref": "Схема теплоснабжения Н. Новгорода, актуализация 2026, Гл. 1 Прил. 2"}})
    order = sorted(stat, key=lambda c: -stat[c]["km"])
    cid = {c: i + 1 for i, c in enumerate(order)}
    for f in feats:
        f["properties"]["_component"] = cid[f["properties"]["_component"]]
    json.dump({"type": "FeatureCollection", "features": feats}, open(out_dir / "scheme_anchors.geojson", "w"),
              ensure_ascii=False, separators=(",", ":"))
    with open(out_dir / "scheme_components.csv", "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["component", "km_route", "edges", "max_dn", "peremychki_km", "consumers", "consumers_anchored",
                    "districts", "root_node", "sources"])
        for c in order:
            st, cc = stat[c], comps[c]
            w.writerow([cid[c], round(st["km"], 2), st["edges"], st["dmax"], round(st["per_km"], 2), cc["consumers"],
                        cc["anchored"], ";".join(k for k, _ in sorted(st["district"].items(), key=lambda x: -x[1])[:3]),
                        c, " | ".join(sorted(set(cc["sources"])))])
    cons = sum(v["consumers"] for v in comps.values())
    anch = sum(v["anchored"] for v in comps.values())
    print(f"компонент {len(order)}; потребителей {cons}, привязано к зданиям OSM {anch} ({anch / cons:.0%}); "
          f"узлы с адресом: {res}", file=sys.stderr)


if __name__ == "__main__":
    assert [house("9-а"), house("4А"), house("100 к23"), house("15 ")] == ["9А", "4А", "100к23", "15"]
    assert node_addr("ПТ-Кр.Зорь,11а гвс") == (["кр", "зорь"], "11А") and node_addr("ПТ-Моск.ш.139")[1] == "139"
    if sys.argv[1] in ("sources", "graph"):
        {"sources": sources, "graph": graph}[sys.argv[1]](*sys.argv[2:6])
        sys.exit()
    assert [dn(70), dn(32), dn(350)] == [65, 50, 300] and [dn_outer(x) for x in (720, 426, 89, 159)] == [700, 400, 80, 150]
    main(*sys.argv[1:3])
