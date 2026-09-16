#!/usr/bin/env python3
"""Иллюстрации к architecture/ARCHITECTURE.md: как строится граф, растёт дерево, считаются спецпереход,
реконструкция и ходы локального поиска.

Запуск из корня: uv run --project tools python scripts/docs_assets.py
Пишет SVG в docs/assets/. Сцены учебные, в метрах, без датасета организаторов; граф, касательные рёбра,
кратчайший путь и раскладка по 45° считаются здесь тем же способом, что в ObstacleSet и Router.
"""
import heapq
import html
import math

from shapely.geometry import LineString, MultiPoint, Point, Polygon
from shapely.geometry.polygon import orient
from shapely.ops import unary_union

BG, CARD, GRID = "#0b1220", "#0f172a", "#1e293b"
TEXT, MUTED = "#e2e8f0", "#94a3b8"
BLUE, ORANGE, ROSE, AMBER, GREEN = "#38bdf8", "#fb923c", "#f43f5e", "#b45309", "#22c55e"
FONT = "-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif"
STYLE = (f"<style>.h{{font:600 16px {FONT};fill:{TEXT}}}.t{{font:13px {FONT};fill:{MUTED}}}"
         f".s{{font:600 12px {FONT};fill:{TEXT}}}.m{{font:12px {FONT};fill:{MUTED}}}</style>")


class Panel:
    """Прямоугольник холста с мировыми координатами в метрах, ось y вверх."""

    def __init__(self, x, y, w, h, world, title, pad=26, sub=None):
        self.x, self.y, self.w, self.h, self.title, self.sub = x, y, w, h, title, sub
        minx, miny, maxx, maxy = world
        top = 70 if sub else 44
        self.s = min((w - 2 * pad) / (maxx - minx), (h - top - pad) / (maxy - miny))
        self.ox = x + (w - (maxx - minx) * self.s) / 2 - minx * self.s
        self.oy = y + top + (h - top - pad - (maxy - miny) * self.s) / 2 + maxy * self.s
        self.parts = []

    def p(self, px, py):
        return self.ox + px * self.s, self.oy - py * self.s

    def xy(self, px, py):
        a, b = self.p(px, py)
        return f"{a:.1f},{b:.1f}"

    def line(self, coords, color, width=2.0, dash=None, opacity=1.0, glow=False, cap="round"):
        d = "M" + " L".join(self.xy(*c) for c in coords)
        extra = f' stroke-dasharray="{dash}"' if dash else ""
        extra += ' filter="url(#glow)"' if glow else ""
        self.parts.append(f'<path d="{d}" fill="none" stroke="{color}" stroke-width="{width}" stroke-linecap="{cap}" '
                          f'stroke-linejoin="round" opacity="{opacity}"{extra}/>')

    def poly(self, geom, fill, stroke="none", width=1.0, opacity=1.0, dash=None):
        geoms = getattr(geom, "geoms", [geom])
        for g in geoms:
            rings = [g.exterior] + list(g.interiors)
            d = " ".join("M" + " L".join(self.xy(*c) for c in r.coords) + " Z" for r in rings)
            extra = f' stroke-dasharray="{dash}"' if dash else ""
            self.parts.append(f'<path d="{d}" fill="{fill}" fill-rule="evenodd" stroke="{stroke}" stroke-width="{width}" '
                              f'opacity="{opacity}"{extra}/>')

    def dot(self, px, py, r, fill, stroke="none", width=1.5):
        a, b = self.p(px, py)
        self.parts.append(f'<circle cx="{a:.1f}" cy="{b:.1f}" r="{r}" fill="{fill}" stroke="{stroke}" stroke-width="{width}"/>')

    def ring(self, px, py, r, color, width=2.5):
        self.dot(px, py, r, "none", color, width)

    def square(self, px, py, size, fill):
        a, b = self.p(px, py)
        self.parts.append(f'<rect x="{a - size / 2:.1f}" y="{b - size / 2:.1f}" width="{size}" height="{size}" rx="1.5" fill="{fill}"/>')

    def text(self, px, py, label, cls="m", anchor="start", dx=0, dy=0):
        a, b = self.p(px, py)
        self.parts.append(f'<text x="{a + dx:.1f}" y="{b + dy:.1f}" class="{cls}" text-anchor="{anchor}">{html.escape(label)}</text>')

    def svg(self):
        return (f'<rect x="{self.x}" y="{self.y}" width="{self.w}" height="{self.h}" rx="14" fill="{CARD}" stroke="{GRID}"/>'
                f'<text x="{self.x + 20}" y="{self.y + 30}" class="h">{html.escape(self.title)}</text>'
                + (f'<text x="{self.x + 20}" y="{self.y + 52}" class="t">{html.escape(self.sub)}</text>' if self.sub else "")
                + "".join(self.parts))


def document(width, height, panels, label):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} {height}" width="{width}" height="{height}" '
            f'role="img" aria-label="{html.escape(label)}"><defs>{STYLE}'
            f'<filter id="glow" filterUnits="userSpaceOnUse" x="0" y="0" width="{width}" height="{height}"><feGaussianBlur stdDeviation="2" result="b"/>'
            '<feMerge><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge></filter></defs>'
            f'<rect width="{width}" height="{height}" rx="18" fill="{BG}"/>' + "".join(p.svg() for p in panels) + "</svg>\n")


def save(name, content):
    with open(f"docs/assets/{name}", "w", encoding="utf-8") as f:
        f.write(content)
    print(f"docs/assets/{name}")


# ---------- 1. Граф видимости ----------

def cross(o, a, b):
    return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])


def graph_steps(start=(52, 12), dry=False):
    buildings = [
        Polygon([(38, 28), (92, 28), (92, 52), (66, 52), (66, 72), (38, 72)]),
        Polygon([(118, 58), (156, 48), (166, 92), (128, 104)]),
        Polygon([(20, 96), (54, 92), (58, 124), (24, 128)]),
    ]
    park = Polygon([(78, 112), (122, 118), (116, 146), (82, 142)])
    obstacles = buildings + [park]
    clearance = 7.0
    zones = unary_union([o.buffer(clearance, join_style=2, mitre_limit=2.0) for o in obstacles])
    zone_list = [orient(z, 1.0) for z in getattr(zones, "geoms", [zones])]
    target = (190, 150)
    pipe = [(140, 150), (205, 150)]

    nodes, rings = [start, target], [None, None]
    for z in zone_list:
        coords = list(z.exterior.coords)[:-1]
        n = len(coords)
        for i, v in enumerate(coords):
            prev, nxt = coords[i - 1], coords[(i + 1) % n]
            if cross(prev, v, nxt) > 0:  # против часовой: левый поворот — выпуклая наружу вершина
                nodes.append(v)
                rings.append((prev, nxt))
    inner = zones.buffer(-0.05)

    def visible(a, b):
        return not LineString([a, b]).intersects(inner)

    def tangent(i, w):
        if rings[i] is None:
            return True
        v = nodes[i]
        prev, nxt = rings[i]
        return cross(v, w, prev) * cross(v, w, nxt) >= -1e-9

    edges = []
    for i in range(len(nodes)):
        for j in range(i):
            if visible(nodes[i], nodes[j]) and tangent(i, nodes[j]) and tangent(j, nodes[i]):
                edges.append((i, j))
    adj = {i: [] for i in range(len(nodes))}
    for i, j in edges:
        d = math.dist(nodes[i], nodes[j])
        adj[i].append((j, d))
        adj[j].append((i, d))
    dist, pred, heap = {0: 0.0}, {}, [(0.0, 0)]
    while heap:
        d, v = heapq.heappop(heap)
        if d > dist.get(v, math.inf):
            continue
        for w, wd in adj[v]:
            if d + wd < dist.get(w, math.inf):
                dist[w], pred[w] = d + wd, v
                heapq.heappush(heap, (d + wd, w))
    path, v = [], 1
    while v != 0:
        path.append(nodes[v])
        v = pred[v]
    path = [start] + list(reversed(path))

    # раскладка по направлениям через 45° от самого длинного отрезка, как Router.octilinearize
    longest = max(range(len(path) - 1), key=lambda k: math.dist(path[k], path[k + 1]))
    base = math.atan2(path[longest + 1][1] - path[longest][1], path[longest + 1][0] - path[longest][0])
    frame = [(math.cos(base + math.radians(45 * k)), math.sin(base + math.radians(45 * k))) for k in range(8)]
    def deflection(a, b, c):
        u, v = (b[0] - a[0], b[1] - a[1]), (c[0] - b[0], c[1] - b[1])
        nu, nv = math.hypot(*u), math.hypot(*v)
        if nu == 0 or nv == 0:
            return 0.0
        return math.degrees(math.acos(max(-1.0, min(1.0, (u[0] * v[0] + u[1] * v[1]) / (nu * nv)))))

    def score(poly):
        bends = [deflection(poly[i - 1], poly[i], poly[i + 1]) for i in range(1, len(poly) - 1)]
        bends = [d for d in bends if d >= 3]
        nonstandard = sum(1 for d in bends if min(abs(d - 45), abs(d - 90)) > 1)
        return nonstandard, len(bends)

    octo = list(path)
    i = 0
    while i + 1 < len(octo):
        a, b = octo[i], octo[i + 1]
        wx, wy = b[0] - a[0], b[1] - a[1]
        ang = (math.degrees(math.atan2(wy, wx) - base)) % 360
        if abs(ang - 45 * round(ang / 45)) < 0.5:
            i += 1
            continue
        k = int(ang // 45) % 8
        f1, f2 = frame[k], frame[(k + 1) % 8]
        det = f1[0] * f2[1] - f1[1] * f2[0]
        s1 = (wx * f2[1] - wy * f2[0]) / det
        s2 = (f1[0] * wy - f1[1] * wx) / det
        options = [(a[0] + s1 * f1[0], a[1] + s1 * f1[1]), (a[0] + s2 * f2[0], a[1] + s2 * f2[1])]
        trials = [octo[:i + 1] + [m] + octo[i + 1:] for m in options if visible(a, m) and visible(m, b)]
        if trials:
            octo = min(trials, key=score)
            i += 2
        else:
            i += 1

    if dry:
        deviation = max(LineString(path).distance(Point(v)) for v in octo)
        return deviation, len(octo)
    world = (0, 0, 205, 160)
    w, h, gap = 620, 470, 20
    titles = ["1. Препятствия, точка подключения и врезка", "2. Зоны отступа и выпуклые вершины",
              "3. Касательные рёбра графа видимости", "4. Кратчайший путь и раскладка по 45°"]
    subs = ["здания и парк, откуда трасса выходит и куда приходит",
            "отступ плюс половина ширины пары труб; узлы — выпуклые вершины зон",
            None,
            "серым кратчайший путь по графу, голубым после раскладки по сетке 45°"]
    panels = [Panel(gap + (w + gap) * (k % 2), gap + (h + gap) * (k // 2), w, h, world, titles[k], sub=subs[k]) for k in range(4)]

    for k, p in enumerate(panels):
        p.line(pipe, AMBER, 4)
        for b in buildings:
            p.poly(b, "#334155")
        p.poly(park, "#14532d")
        if k >= 1:
            p.poly(zones, "none", "#64748b", 1.2, dash="4 3")
        if k == 2:
            for i, j in edges:
                p.line([nodes[i], nodes[j]], BLUE, 1.0, opacity=0.35)
        if k == 3:
            p.line(path, MUTED, 2, dash="6 4")
            p.line(octo, BLUE, 3.5, glow=True)
        if k >= 1:
            for v in nodes[2:]:
                p.dot(*v, 3.2, "#e0f2fe")
        p.dot(*start, 5, "#f8fafc", BG)
        p.ring(*target, 7, BLUE)
    panels[0].text(*start, "точка подключения ОКС", dx=10, dy=4)
    panels[0].text(*target, "врезка в трубу", anchor="end", dx=-14, dy=18)
    panels[0].text(52, 40, "здание", "s", "middle")
    panels[0].text(100, 130, "парк", "s", "middle")
    panels[2].sub = f"{len(edges)} рёбер: только касательные к зонам в обоих концах"

    save("graph-steps.svg", document(2 * w + 3 * gap, 2 * h + 3 * gap, panels, "Как строится граф видимости и маршрут"))


# ---------- 2. Рост дерева ----------

def tree_growth():
    tie, p1, p2, p3 = (40, 0), (40, 150), (130, 110), (110, 40)
    j1, j2 = (40, 110), (40, 40)
    w, h, gap = 406, 420, 20
    world = (0, -20, 170, 170)
    titles = ["1. Первый ОКС: ветка от врезки", "2. Второй ОКС: камера на стволе", "3. Третий ОКС и диаметры по расходу"]
    panels = [Panel(gap + (w + gap) * k, gap, w, h, world, titles[k]) for k in range(3)]
    for k, p in enumerate(panels):
        p.line([(0, 0), (170, 0)], AMBER, 5)
        pts = [(p1, "ОКС 1 · 12 т/ч"), (p2, "ОКС 2 · 8 т/ч"), (p3, "ОКС 3 · 5 т/ч")]
        if k == 0:
            p.line([tie, p1], BLUE, 5.5, glow=True)
            p.line([p2, j1], MUTED, 1.5, dash="4 4", opacity=0.5)
        if k == 1:
            p.line([tie, j1], BLUE, 5.5, glow=True)
            p.line([j1, p1], BLUE, 4.5, glow=True)
            p.line([j1, p2], BLUE, 4.0, glow=True)
            p.square(*j1, 11, "#e0f2fe")
            p.line([p3, j2], MUTED, 1.5, dash="4 4", opacity=0.5)
            p.text(*j1, "камера ветвления", dx=-10, dy=-10, anchor="end")
        if k == 2:
            for a, b, width, label, pos, anchor in [
                (tie, j2, 7.0, "DN 125 · 25 т/ч", (40, 20), "end"),
                (j2, j1, 6.0, "DN 100 · 20 т/ч", (40, 75), "end"),
                (j1, p1, 5.0, "DN 80 · 12 т/ч", (40, 130), "end"),
                (j1, p2, 4.2, "DN 65 · 8 т/ч", (85, 110), "middle"),
                (j2, p3, 4.2, "DN 65 · 5 т/ч", (75, 40), "middle"),
            ]:
                p.line([a, b], BLUE, width, glow=True)
                p.text(*pos, label, "s", anchor, dx=-10 if anchor == "end" else 0, dy=-8 if anchor == "middle" else 4)
            p.square(*j1, 11, "#e0f2fe")
            p.square(*j2, 11, "#e0f2fe")
        for (x, y), label in pts:
            p.dot(x, y, 6, "#f8fafc", BG)
            if k < 2:
                p.text(x, y, label, dx=10, dy=4)
        p.ring(*tie, 9, BLUE, 3)
        p.text(*tie, "врезка", dx=12, dy=20)
    save("tree-growth.svg", document(3 * w + 4 * gap, h + 2 * gap, panels, "Рост дерева врезки эвристикой Такахаши–Мацуямы"))


# ---------- 3. Специальные переходы ----------

def special_crossing():
    w, h, gap = 620, 430, 20
    left = Panel(gap, gap, w, h, (0, 0, 120, 90), "Дорога: переход под углом ≥ 45°, зона +3 м")
    angle = math.radians(20)
    axis = LineString([(-40 * math.cos(angle) + 60 - 60 * math.cos(angle), 45 - 60 * math.sin(angle) - 10),
                       (60 + 80 * math.cos(angle), 45 + 80 * math.sin(angle) - 10)])
    road = axis.buffer(9, cap_style=2).intersection(Polygon([(0, 0), (120, 0), (120, 90), (0, 90)]))
    margin = axis.buffer(12, cap_style=2).intersection(Polygon([(0, 0), (120, 0), (120, 90), (0, 90)]))
    route = LineString([(44, 0), (74, 90)])
    special = route.intersection(margin)
    left.poly(margin, "none", ORANGE, 1.2, dash="5 4", opacity=0.9)
    left.poly(road, "#1f2937", "#475569", 1)
    left.line(list(axis.coords), MUTED, 1, dash="10 6", opacity=0.6)
    left.line(list(route.coords), BLUE, 5, glow=True)
    left.line(list(special.coords), ORANGE, 6, glow=True)
    for c in (special.coords[0], special.coords[-1]):
        left.dot(*c, 5, "#f8fafc", BG)
    left.text(2, 86, "полоса дороги", "s")
    left.text(2, 79, "пунктиром зона спецперехода: +3 м с каждой стороны")
    left.text(80, 18, "спецучасток · Kспец 1,60", "s")
    left.text(80, 11, "технические узлы на границах зоны")

    right = Panel(2 * gap + w, gap, w, h, (0, 0, 120, 90), "Газопровод: по 2 м от пересечения, отступ 2 м")
    gas = LineString([(0, 38), (120, 58)])
    clear = gas.buffer(8, cap_style=2)
    route2 = LineString([(62, 4), (58, 86)])
    hit = route2.intersection(gas)
    along = route2.project(hit)
    zone = LineString([route2.interpolate(along - 6).coords[0], route2.interpolate(along + 6).coords[0]])
    right.poly(clear, "#eab308", opacity=0.08)
    right.poly(clear, "none", "#eab308", 1, dash="4 4", opacity=0.5)
    right.line(list(gas.coords), "#eab308", 2.5, dash="8 5")
    right.line(list(route2.coords), BLUE, 5, glow=True)
    right.line(list(zone.coords), ORANGE, 6, glow=True)
    for c in zone.coords:
        right.dot(*c, 5, "#f8fafc", BG)
    right.text(4, 70, "газопровод", "s")
    right.text(4, 62, "жёлтая полоса: ближе отступа обычный участок не проходит")
    right.text(66, 24, "спецучасток · Kспец 1,25", "s")
    right.text(66, 17, "при наложении зон — наибольший Kспец")
    save("special-crossing.svg", document(2 * w + 3 * gap, h + 2 * gap, [left, right], "Специальные переходы дороги и газопровода"))


# ---------- 4. Реконструкция ----------

def reconstruction():
    w, h, gap = 1260, 430, 20
    p = Panel(gap, gap, w, h, (-20, -190, 520, 40), "Реконструкция: добавленный расход идёт к источнику")
    src, a, b, c, d = (0, 0), (150, 0), (300, 0), (470, 0), (300, -160)
    tie = (300, -90)
    oks = (440, -170)
    p.line([tie, b], ROSE, 14, opacity=0.5)
    p.line([b, a], ROSE, 14, opacity=0.5)
    for seg, label in [((src, a), "DN 400"), ((a, b), "DN 250"), ((b, c), "DN 200"), ((b, d), "DN 150")]:
        p.line(list(seg), AMBER, 5)
    p.text(75, 0, "DN 400 · хватает", "s", "middle", dy=-14)
    p.text(225, 0, "DN 250 → 300", "s", "middle", dy=-14)
    p.text(385, 0, "DN 200 · не затронут", "m", "middle", dy=-14)
    p.text(300, -45, "DN 150 → 200", "s", dx=14)
    p.line([tie, (380, -90), oks], BLUE, 5, glow=True)
    p.text(390, -90, "новая сеть · 40 т/ч", "s", dy=-10)
    for x in (270, 190, 110):
        p.text(x, -24, "←", "s", "middle")
    p.text(300, -118, "↑", "s", "middle", dx=-14)
    p.dot(*src, 10, AMBER, "#fde68a", 2)
    p.text(*src, "источник", dx=16, dy=24)
    for q in (a, b):
        p.square(*q, 12, "#cbd5e1")
    p.ring(*tie, 9, BLUE, 3)
    p.square(*tie, 8, "#e0f2fe")
    p.text(*tie, "врезка в трубу и новая камера", anchor="end", dx=-16, dy=4)
    p.dot(*oks, 6, "#f8fafc", BG)
    p.text(*oks, "ОКС", dx=10, dy=4)
    p.text(-10, -150, "Малиновым части, которым с добавкой 40 т/ч нужен больший диаметр.", "m")
    p.text(-10, -165, "Реконструируется только часть от врезки к источнику, по ставке реконструкции, без Kспец.", "m")
    save("reconstruction.svg", document(w + 2 * gap, h + 2 * gap, [p], "Реконструкция существующей сети"))


# ---------- 5. Ходы локального поиска ----------

def local_search():
    pts = [(10, 60), (22, 72), (18, 48), (58, 66), (66, 52), (54, 44)]
    ties = {"A": (16, 20), "B": (60, 20), "B2": (84, 30)}
    palette = {"A": BLUE, "B": GREEN, "C": ORANGE}
    moves = [
        ("Слить блоки", [["A", "A", "A", "B", "B", "B"], ["A"] * 6], [["A", "B"], ["A"]]),
        ("Перенести ОКС", [["A", "A", "A", "B", "B", "B"], ["A", "A", "B", "B", "B", "B"]], [["A", "B"], ["A", "B"]]),
        ("Выделить ОКС", [["A"] * 6, ["A", "A", "A", "A", "A", "C"]], [["A"], ["A", "C"]]),
        ("Разбить k-means", [["A"] * 6, ["A", "A", "A", "B", "B", "B"]], [["A"], ["A", "B"]]),
        ("Сменить врезку", [["A", "A", "A", "B", "B", "B"], ["A", "A", "A", "B", "B", "B"]], [["A", "B"], ["A", "B2"]]),
    ]
    w, h, gap = 240, 200, 16
    panels = []
    for k, (title, states, tie_sets) in enumerate(moves):
        p = Panel(gap + (w + gap) * k, gap, w, h, (0, 12, 190, 80), title, pad=10)
        for s, (labels, tie_keys) in enumerate(zip(states, tie_sets)):
            dx = 0 if s == 0 else 110
            groups = {}
            for (x, y), lab in zip(pts, labels):
                groups.setdefault(lab, []).append((x * 0.9 + dx, y))
            for key, tie_key in zip(sorted(groups), tie_keys):
                color = palette[key]
                tx, ty = ties[tie_key if tie_key != "C" else "B"]
                tx = tx * 0.9 + dx
                hull = MultiPoint(groups[key]).buffer(6).convex_hull
                p.poly(hull, color, opacity=0.15)
                for x, y in groups[key]:
                    p.line([(tx, ty), (x, y)], color, 1.2, opacity=0.6)
                    p.dot(x, y, 4.5, color)
                p.ring(tx, ty, 7, color, 2.5)
        p.text(100, 45, "→", "h", "middle", dx=-4)
        panels.append(p)
    save("local-search.svg", document(5 * w + 6 * gap, h + 2 * gap, panels, "Ходы локального поиска по разбиениям ОКС"))


if __name__ == "__main__":
    graph_steps()
    tree_growth()
    special_crossing()
    reconstruction()
    local_search()
