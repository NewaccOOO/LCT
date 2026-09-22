#!/usr/bin/env python3
"""Картинки для README: трассировка на синтетической сцене и динамика качества по версиям.

Запуск из корня: uv run --project tools python scripts/readme_assets.py [IN.geojson OUT.geojson]
Пишет docs/assets/versions.svg (таблица «Версии и динамика качества» из README, версии с правил 18.09), docs/assets/city.svg
(таблица «Город» там же) и, если заданы IN/OUT,
docs/assets/hero.svg (вариант 1 расчёта на сцене). Для README берётся синтетическая сцена: датасет организаторов
наружу не показывается.
"""
import html
import json
import re
import sys

from pyproj import Transformer

TO_UTM = Transformer.from_crs("EPSG:4326", "EPSG:32637", always_xy=True)
W, H = 1280, 640
PAD = 28
BG, CARD = "#0b1220", "#0f172a"
FILL = {"oks": "#334155", "park": "#14532d", "social_area": "#4c1d95", "prohibited_site": "#7f1d1d", "water": "#1e3a8a",
        "road": "#1f2937", "tram_tracks": "#3f2d1a", "railway": "#3f2d1a"}
LINE = {"gas_pipeline": "#eab308", "power_cable": "#a855f7"}
# первая версия по техническому приложению от 18.09.2026: на графике только она и следующие
FIRST_1809 = (0, 5, 0)


def coords(geom):
    t = geom["type"]
    c = geom["coordinates"]
    if t == "Point":
        return [[TO_UTM.transform(*c)]]
    if t == "LineString":
        return [[TO_UTM.transform(*p) for p in c]]
    if t == "MultiLineString":
        return [[TO_UTM.transform(*p) for p in part] for part in c]
    if t == "Polygon":
        return [[TO_UTM.transform(*p) for p in ring] for ring in c]
    if t == "MultiPolygon":
        return [[TO_UTM.transform(*p) for p in ring] for poly in c for ring in poly]
    return []


def hero(inp_path, out_path):
    inp = json.load(open(inp_path, encoding="utf-8"))["features"]
    out = [f for f in json.load(open(out_path, encoding="utf-8"))["features"] if f["properties"].get("variant_id") == "1"]
    xs, ys = [], []
    for f in out:
        if f["geometry"]:
            for part in coords(f["geometry"]):
                for x, y in part:
                    xs.append(x)
                    ys.append(y)
    margin = 220
    minx, maxx, miny, maxy = min(xs) - margin, max(xs) + margin, min(ys) - margin, max(ys) + margin
    # вписать в холст с сохранением пропорций
    s = min((W - 2 * PAD) / (maxx - minx), (H - 2 * PAD - 44) / (maxy - miny))
    cx, cy = (minx + maxx) / 2, (miny + maxy) / 2

    def p(x, y):
        return f"{W / 2 + (x - cx) * s:.1f},{(H - 44) / 2 + (cy - y) * s:.1f}"

    def path(part, close=False):
        return "M" + " L".join(p(x, y) for x, y in part) + (" Z" if close else "")

    g = []
    for f in inp:
        pr, geom = f["properties"], f["geometry"]
        if pr.get("object_type") == "restriction" and geom and geom["type"] in ("Polygon", "MultiPolygon"):
            color = FILL.get(pr.get("restriction_type"), "#334155")
            d = " ".join(path(r, True) for r in coords(geom))
            g.append(f'<path d="{d}" fill="{color}" fill-rule="evenodd" stroke="#0b1220" stroke-width="0.6"/>')
    for f in inp:
        pr, geom = f["properties"], f["geometry"]
        if pr.get("object_type") == "restriction" and geom and "Line" in geom["type"]:
            color = LINE.get(pr.get("restriction_type"), "#64748b")
            for part in coords(geom):
                g.append(f'<path d="{path(part)}" fill="none" stroke="{color}" stroke-width="1.4" stroke-dasharray="5 4" opacity="0.8"/>')
    for f in inp:
        pr, geom = f["properties"], f["geometry"]
        if pr.get("object_type") == "heat_network":
            for part in coords(geom):
                g.append(f'<path d="{path(part)}" fill="none" stroke="#b45309" stroke-width="2.6" stroke-linecap="round" opacity="0.9"/>')
    for f in out:
        pr, geom = f["properties"], f["geometry"]
        if pr["object_type"] == "heat_network":
            w = 2.2 + min(pr["diameter"], 400) / 110
            color = "#fb923c" if pr["laying_method"] == "special" else "#38bdf8"
            for part in coords(geom):
                g.append(f'<path d="{path(part)}" fill="none" stroke="{color}" stroke-width="{w:.1f}" stroke-linecap="round" stroke-linejoin="round" filter="url(#glow)"/>')
    for f in inp:
        pr, geom = f["properties"], f["geometry"]
        if pr.get("object_type") == "source":
            x, y = map(float, p(*coords(geom)[0][0]).split(","))
            g.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="9" fill="#b45309" stroke="#fde68a" stroke-width="2"/>')
        if pr.get("object_type") == "oks_connection_point":
            x, y = map(float, p(*coords(geom)[0][0]).split(","))
            g.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="4.5" fill="#f8fafc" stroke="#0b1220" stroke-width="1.5"/>')
    for f in out:
        pr, geom = f["properties"], f["geometry"]
        if pr["object_type"] == "heat_chamber" and geom:
            x, y = map(float, p(*coords(geom)[0][0]).split(","))
            g.append(f'<rect x="{x - 3.5:.1f}" y="{y - 3.5:.1f}" width="7" height="7" rx="1.5" fill="#e0f2fe"/>')
    summary = next(f["properties"] for f in out if f["properties"]["object_type"] == "variant_summary")
    legend = [("#38bdf8", "новая сеть, толщина по Ду", "line"), ("#fb923c", "спецпереход", "line"),
              ("#b45309", "существующая сеть", "line"),
              ("#f8fafc", "точка подключения", "dot"), ("#e0f2fe", "камера", "sq")]
    lx, ly = PAD + 6, H - 24
    items = []
    for color, label, kind in legend:
        if kind == "line":
            items.append(f'<line x1="{lx}" y1="{ly}" x2="{lx + 22}" y2="{ly}" stroke="{color}" stroke-width="4" stroke-linecap="round"/>')
            lx += 30
        elif kind == "wide":
            items.append(f'<line x1="{lx}" y1="{ly}" x2="{lx + 22}" y2="{ly}" stroke="{color}" stroke-width="8" opacity="0.55" stroke-linecap="round"/>')
            lx += 30
        elif kind == "dot":
            items.append(f'<circle cx="{lx + 6}" cy="{ly}" r="4.5" fill="{color}"/>')
            lx += 16
        elif kind == "ring":
            items.append(f'<circle cx="{lx + 7}" cy="{ly}" r="6" fill="none" stroke="{color}" stroke-width="2.5"/>')
            lx += 18
        else:
            items.append(f'<rect x="{lx + 2}" y="{ly - 4}" width="8" height="8" rx="1.5" fill="{color}"/>')
            lx += 16
        items.append(f'<text x="{lx}" y="{ly + 4}" class="t">{html.escape(label)}</text>')
        lx += 22 + 7.4 * len(label)
    points = sum(1 for f in inp if f["properties"].get("object_type") == "oks_connection_point")
    badge = f"синтетическая сцена · {points} ОКС · {summary['new_network_length'] / 1000:.1f} км новой сети".replace(".", ",")
    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}" role="img" aria-label="Пример трассировки на синтетической сцене">
<defs>
<filter id="glow" x="-10%" y="-10%" width="120%" height="120%"><feGaussianBlur stdDeviation="2.2" result="b"/><feMerge><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge></filter>
<clipPath id="card"><rect x="0" y="0" width="{W}" height="{H}" rx="18"/></clipPath>
<style>.t{{font:13px -apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;fill:#cbd5e1}}.b{{font:600 14px -apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;fill:#e2e8f0}}</style>
</defs>
<g clip-path="url(#card)">
<rect width="{W}" height="{H}" fill="{BG}"/>
<rect y="{H - 44}" width="{W}" height="44" fill="{CARD}"/>
{''.join(g)}
<rect x="{W - 24 - 7.6 * len(badge)}" y="20" width="{7.6 * len(badge) + 6}" height="30" rx="15" fill="{CARD}" stroke="#1e293b"/>
<text x="{W - 21 - 7.6 * len(badge) / 2 - 2}" y="40" class="b" text-anchor="middle">{html.escape(badge)}</text>
{''.join(items)}
</g>
</svg>
'''
    open("docs/assets/hero.svg", "w", encoding="utf-8").write(svg)


def versions():
    text = open("README.md", encoding="utf-8").read()
    rows = re.findall(r"^\| (v[\d.]+) \| [^|]+ \| ([\d,]+) \| ([\d,]+) \| (\d+) \|", text, re.MULTILINE)
    # только версии по правилам 18.09: прежние S считались по другой модели стоимости и несравнимы
    rows = [r for r in reversed(rows) if tuple(int(x) for x in r[0][1:].split(".")) >= FIRST_1809]
    if not rows:
        sys.exit("в README нет таблицы версий")
    vals = [(v, float(s.replace(",", ".")), float(c.replace(",", ".")), int(t)) for v, s, c, t in rows]
    left, right, top, bottom = 56, 24, 48, 36
    w, h = max(760, left + right + 72 * len(vals)), 300
    top_s = 5 * (int(max(v[1] for v in vals) * 1.1 / 5) + 1)
    # один-два столбца не растягиваются на всю ширину: колонка не шире 120 px
    bw = min((w - left - right) / len(vals), 120)
    parts = []
    for i in range(5):
        y = top + (h - top - bottom) * i / 4
        val = top_s * (1 - i / 4)
        parts.append(f'<line x1="{left}" y1="{y:.1f}" x2="{w - right}" y2="{y:.1f}" stroke="#1e293b"/>')
        parts.append(f'<text x="{left - 8}" y="{y + 4:.1f}" class="a" text-anchor="end">{val:g}</text>')
    best = min(v[1] for v in vals)
    for i, (ver, s, cost, sec) in enumerate(vals):
        bh = (h - top - bottom) * s / top_s
        x = left + i * bw + bw * 0.1
        y = h - bottom - bh
        color, ink = ("#38bdf8", "#0b1220") if s == best else ("#334155", "#cbd5e1")
        cx = x + bw * 0.4
        parts.append(f'<rect x="{x:.1f}" y="{y:.1f}" width="{bw * 0.8:.1f}" height="{bh:.1f}" rx="6" fill="{color}"/>')
        parts.append(f'<text x="{cx:.1f}" y="{y - 8:.1f}" class="v" text-anchor="middle">{s:.2f}</text>')
        parts.append(f'<text x="{cx:.1f}" y="{h - bottom - 28:.1f}" class="i" fill="{ink}" text-anchor="middle">{cost:.0f} млн</text>')
        parts.append(f'<text x="{cx:.1f}" y="{h - bottom - 12:.1f}" class="i" fill="{ink}" text-anchor="middle">{sec} с</text>')
        parts.append(f'<text x="{cx:.1f}" y="{h - bottom + 22:.1f}" class="l" text-anchor="middle">{ver}</text>')
    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" width="{w}" height="{h}" role="img" aria-label="S варианта 1 на датасете организаторов по версиям с правил 18.09.2026">
<style>.a{{font:12px -apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;fill:#94a3b8}}.l{{font:600 13px -apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;fill:#e2e8f0}}.v{{font:600 13px -apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;fill:#e2e8f0}}.i{{font:600 11px -apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif}}.h{{font:600 15px -apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;fill:#e2e8f0}}</style>
<rect width="{w}" height="{h}" rx="16" fill="{BG}"/>
<text x="{left}" y="28" class="h">S варианта 1 на датасете организаторов по правилам 18.09.2026, меньше — лучше</text>
{''.join(parts)}
</svg>
'''
    open("docs/assets/versions.svg", "w", encoding="utf-8").write(svg)


def city():
    """Столбцы времени расчёта файла «город» по версиям из таблицы «Город» в README; подпись — подключено и память."""
    text = open("README.md", encoding="utf-8").read()
    rows = re.findall(r"^\| (v[\d.]+) \| ([\d ]+) \| [\d ]+ \| (\d+) \| ([\d,]+) \|", text, re.MULTILINE)
    if not rows:
        sys.exit("в README нет таблицы «Город»")
    vals = [(v, int(c.replace(" ", "")), int(t), m) for v, c, t, m in reversed(rows)]
    left, right, top, bottom = 56, 24, 48, 36
    w, h = 760, 300
    top_t = 500 * (int(max(v[2] for v in vals) * 1.1 / 500) + 1)
    bw = min((w - left - right) / len(vals), 120)
    parts = []
    for i in range(5):
        y = top + (h - top - bottom) * i / 4
        parts.append(f'<line x1="{left}" y1="{y:.1f}" x2="{w - right}" y2="{y:.1f}" stroke="#1e293b"/>')
        parts.append(f'<text x="{left - 8}" y="{y + 4:.1f}" class="a" text-anchor="end">{top_t * (1 - i / 4):g}</text>')
    best = min(v[2] for v in vals)
    for i, (ver, connected, sec, mem) in enumerate(vals):
        bh = (h - top - bottom) * sec / top_t
        x = left + i * bw + bw * 0.1
        y = h - bottom - bh
        color, ink = ("#38bdf8", "#0b1220") if sec == best else ("#334155", "#cbd5e1")
        cx = x + bw * 0.4
        parts.append(f'<rect x="{x:.1f}" y="{y:.1f}" width="{bw * 0.8:.1f}" height="{bh:.1f}" rx="6" fill="{color}"/>')
        parts.append(f'<text x="{cx:.1f}" y="{y - 8:.1f}" class="v" text-anchor="middle">{sec} с</text>')
        parts.append(f'<text x="{cx:.1f}" y="{h - bottom - 28:.1f}" class="i" fill="{ink}" text-anchor="middle">{connected:,}'.replace(",", " ") + '</text>')
        parts.append(f'<text x="{cx:.1f}" y="{h - bottom - 12:.1f}" class="i" fill="{ink}" text-anchor="middle">{mem} ГБ</text>')
        parts.append(f'<text x="{cx:.1f}" y="{h - bottom + 22:.1f}" class="l" text-anchor="middle">{ver}</text>')
    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" width="{w}" height="{h}" role="img" aria-label="Время расчёта файла «город» по версиям с правил 18.09.2026">
<style>.a{{font:12px -apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;fill:#94a3b8}}.l{{font:600 13px -apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;fill:#e2e8f0}}.v{{font:600 13px -apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;fill:#e2e8f0}}.i{{font:600 11px -apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif}}.h{{font:600 15px -apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;fill:#e2e8f0}}</style>
<rect width="{w}" height="{h}" rx="16" fill="{BG}"/>
<text x="{left}" y="28" class="h">Файл «город» 3,2 ГБ: время расчёта по версиям, меньше — лучше; в столбце подключено и пиковая память</text>
{''.join(parts)}
</svg>
'''
    open("docs/assets/city.svg", "w", encoding="utf-8").write(svg)


if __name__ == "__main__":
    if len(sys.argv) == 3:
        hero(sys.argv[1], sys.argv[2])
        print("docs/assets/hero.svg")
    versions()
    print("docs/assets/versions.svg")
    city()
    print("docs/assets/city.svg")
