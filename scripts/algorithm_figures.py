#!/usr/bin/env python3
"""Картинки угловых случаев для docs/algorithm.md из настоящих выходов сервиса.

Запуск из корня после сборки (source scripts/env.sh && mvn -q -B -DskipTests package):
    uv run --project tools python scripts/algorithm_figures.py [старый.jar]
Считает каждый вход docs/assets/algorithm/inputs/*.geojson сервисом target/heatnet.jar и старым jar для картинок
«до» (по умолчанию data/out/goal/v0.8.1.jar), кладёт выходы в data/out/algorithm/, печатает итог строгого check18
по каждому выходу и пишет SVG в docs/assets/algorithm/. Датасет организаторов здесь не используется.
"""
import glob
import html
import json
import math
import os
import re
import subprocess
import sys

import shapely.ops
from pyproj import Transformer
from shapely.geometry import LineString, Point, box, shape

from docs_assets import (
    AMBER,
    BLUE,
    GREEN,
    MUTED,
    ORANGE,
    ROSE,
    TEXT,
    Panel,
    document,
    save,
)
from readme_assets import (
    FILL,
    LINE,
)

TO_UTM = Transformer.from_crs("EPSG:4326", "EPSG:32637", always_xy=True).transform
INPUTS = "docs/assets/algorithm/inputs"
RUNS = "data/out/algorithm"
NEW_JAR = "target/heatnet.jar"
RULES = json.load(open("rules/rules.json", encoding="utf-8"))
DN = {d["dn"]: d for d in RULES["diameters"]}
PW, PH = 560, 440


def run(jar, name):
    """Считает вход jar-ом, возвращает путь выхода или None, если сервис вход не принял."""
    out = f"{RUNS}/{name}.{os.path.basename(jar)}.geojson"
    res = subprocess.run(["bash", "-c", f"source scripts/env.sh >/dev/null && java -jar {jar} --cli {INPUTS}/{name}.geojson {out}"],
                         capture_output=True, text=True)
    return out if res.returncode == 0 else None


def check(name, out):
    """Строгий check18: последняя строка и число нарушений варианта 1 по категориям."""
    res = subprocess.run([sys.executable, "tools/validator/check18.py", f"{INPUTS}/{name}.geojson", out], capture_output=True, text=True)
    counts = {}
    first = res.stdout.split("== вариант 2")[0]
    for cat, n in re.findall(r"^  ([A-E]\d+) [^:]*: (\d+)", first, re.MULTILINE):
        counts[cat] = counts.get(cat, 0) + int(n)
    return res.stdout.strip().splitlines()[-1], counts


def load(path):
    feats = []
    for f in json.load(open(path, encoding="utf-8"))["features"]:
        geom = shapely.ops.transform(TO_UTM, shape(f["geometry"])) if f["geometry"] else None
        feats.append((f["properties"], geom))
    return feats


def variant(out, vid="1"):
    return [(pr, g) for pr, g in out if str(pr.get("variant_id")) == vid]


def summary(out):
    return next(pr for pr, _ in out if pr["object_type"] == "variant_summary")


def segments(out):
    return [(pr, g) for pr, g in out if pr["object_type"] == "heat_network"]


def num(x, digits=2):
    return f"{x:.{digits}f}".replace(".", ",")


def note(p, x, y, text, color=TEXT, anchor="start", dx=0, dy=0, size=12):
    a, b = p.p(x, y)
    p.parts.append(f'<text x="{a + dx:.1f}" y="{b + dy:.1f}" text-anchor="{anchor}" style="font:600 {size}px -apple-system,Segoe UI,'
                   f'Roboto,Helvetica,Arial,sans-serif;fill:{color}">{html.escape(text)}</text>')


def lines_of(g):
    if g.is_empty:
        return []
    if g.geom_type == "LineString":
        return [list(g.coords)]
    if g.geom_type == "Polygon":
        return [list(g.exterior.coords)]
    return [c for part in getattr(g, "geoms", []) for c in lines_of(part)]


def polys_of(g):
    if g.geom_type == "Polygon":
        return [] if g.is_empty else [g]
    return [q for part in getattr(g, "geoms", []) for q in polys_of(part)]


def draw(p, inp, out, world):
    """Вход и вариант выхода в рамке world: здания, ограничения, существующая и новая сеть."""
    clip = box(*world)
    for pr, g in inp:
        kind = pr.get("restriction_type") or pr.get("object_type")
        if g is not None and g.geom_type in ("Polygon", "MultiPolygon") and kind in FILL or kind in ("oks_future", "oks_existing"):
            for q in polys_of(g.intersection(clip)):
                p.poly(q, FILL.get(kind, FILL["oks"]), "#64748b", 0.8)
    for pr, g in inp:
        kind = pr.get("restriction_type")
        if g is not None and "Line" in g.geom_type and pr.get("object_type") == "restriction":
            for c in lines_of(g.intersection(clip)):
                if kind in ("road", "tram_tracks"):
                    p.line(c, "#475569", 7, opacity=0.9)
                else:
                    p.line(c, LINE.get(kind, "#64748b"), 2.2, dash="7 5")
    for pr, g in inp:
        if pr["object_type"] == "heat_network":
            for c in lines_of(g.intersection(clip)):
                p.line(c, AMBER, 4)
    for pr, g in inp:
        if pr["object_type"] == "heat_chamber" and clip.contains(g):
            p.square(g.x, g.y, 11, AMBER)
    for pr, g in out:
        if pr["object_type"] == "heat_network":
            color = ORANGE if pr["laying_method"] == "special" else BLUE
            for c in lines_of(g.intersection(clip)):
                p.line(c, color, 3 + pr["diameter"] / 60, glow=True)
    for pr, g in out:
        if pr["object_type"] == "heat_chamber" and clip.contains(g):
            p.square(g.x, g.y, 10, "#e0f2fe")
        if pr["object_type"] == "technical_node" and clip.contains(g):
            p.dot(g.x, g.y, 3.5, "#0f172a", TEXT, 1.5)
    for pr, g in inp:
        if pr["object_type"] == "oks_connection_point" and clip.contains(g):
            p.dot(g.x, g.y, 5, "#f8fafc", "#0b1220")


LEGEND = [(BLUE, "новая сеть"), (ORANGE, "спецпроход"), (AMBER, "существующая сеть"), ("#f8fafc", "точка подключения"),
          ("#e0f2fe", "новая камера"), (ROSE, "нарушение"), (GREEN, "норма соблюдена")]


def figure(name, panels, label, width=None):
    width = width or len(panels) * (PW + 20) + 20
    height = max(p.y + p.h for p in panels) + 44
    items, x = [], 24
    for color, text in LEGEND:
        items.append(f'<rect x="{x}" y="{height - 30}" width="14" height="6" rx="3" fill="{color}"/>'
                     f'<text x="{x + 20}" y="{height - 23}" class="m">{html.escape(text)}</text>')
        x += 34 + 7 * len(text)
    save(f"algorithm/{name}.svg", document(max(width, x), height, panels, label).replace("</svg>", "".join(items) + "</svg>"))


def panel(i, world, title, sub, w=PW, h=PH):
    return Panel(20 + i * (w + 20), 20, w, h, world, title, sub=sub)


def around(g, pad):
    x0, y0, x1, y1 = g.bounds
    return x0 - pad, y0 - pad, x1 + pad, y1 + pad


def deflection(a, b, c):
    """Поворот в b: угол между продолжением a→b и направлением b→c, градусы."""
    h1 = math.atan2(b[1] - a[1], b[0] - a[0])
    h2 = math.atan2(c[1] - b[1], c[0] - b[0])
    return abs((math.degrees(h2 - h1) + 180) % 360 - 180)


def cross_angle(seg, edge):
    """Острый угол между отрезком трассы и отрезком стороны или оси, градусы."""
    (ax, ay), (bx, by) = seg
    (cx, cy), (dx, dy) = edge
    d = abs(math.degrees(math.atan2(by - ay, bx - ax) - math.atan2(dy - cy, dx - cx))) % 180
    return min(d, 180 - d)


def edge_at(g, pt):
    """Отрезок контура или линии g, ближайший к точке."""
    best = None
    for c in lines_of(g.boundary if g.geom_type in ("Polygon", "MultiPolygon") else g):
        for a, b in zip(c, c[1:]):
            d = LineString([a, b]).distance(pt)
            if best is None or d < best[0]:
                best = (d, (a, b))
    return best[1]


def restriction(inp, kind):
    return next(g for pr, g in inp if pr.get("restriction_type") == kind)


def point(inp):
    return next(g for pr, g in inp if pr["object_type"] == "oks_connection_point")


def norm_to_line(kind, dn):
    rule = RULES["restrictions"][kind]
    return rule["clearance_m"] + DN[dn]["width_m"] / 2 + rule.get("half_width_m", 0)


def wall_pipe(runs):
    inp, old, new = runs["wall-pipe"]
    cp = point(inp)
    world = (cp.x - 55, cp.y - 99, cp.x + 55, cp.y + 23)
    so, sn = summary(old), summary(new)
    left = panel(0, world, "v0.8.1: точка без сети", f"штраф {num(so['unconnected_penalty'] / 1e6, 0)} млн руб., S {num(so['score'], 3)}")
    draw(left, inp, old, world)
    note(left, cp.x, cp.y, "ветка по перпендикуляру отброшена", ROSE, "middle", dy=60)
    seg_pr, seg = segments(new)[0]
    right = panel(1, world, "v0.9.0: врезка у перпендикуляра", f"S {num(sn['score'], 4)}, штрафа нет")
    draw(right, inp, new, world)
    a, b = seg.coords[0], seg.coords[1]
    pipe = next(g for pr, g in inp if pr["object_type"] == "heat_network")
    note(right, a[0], a[1], f"угол к трубе {num(cross_angle((a, b), edge_at(pipe, Point(a))), 1)}°", GREEN, dx=8, dy=-8)
    note(right, *seg.interpolate(0.45, normalized=True).coords[0], f"{num(seg_pr['length'])} м, Ду {seg_pr['diameter']}", TEXT, dx=10)
    note(right, cp.x, cp.y, "финальный участок от ближайшей стены", TEXT, "middle", dy=-40)
    figure("wall-pipe", [left, right], "Здание стеной вдоль трубы: до и после")


def chamber_10m(runs):
    inp, _, new = runs["chamber-10m"]
    cp = point(inp)
    pipe = shapely.ops.linemerge([g for pr, g in inp if pr["object_type"] == "heat_network"])
    proj = pipe.interpolate(pipe.project(cp))
    ch = next(g for pr, g in inp if pr["object_type"] == "heat_chamber" and g.distance(proj) <= RULES["chamber_rule"]["max_dist_m"])
    world = (cp.x - 30, cp.y - 100, cp.x + 30, cp.y + 22)
    sn = summary(new)
    p = panel(0, world, "Существующая камера ближе 10 м", f"врезка в неё: {sn['existing_chamber_tie_in_count']} шт., "
              f"{num(sn['existing_chamber_tie_in_cost'] / 1e6, 0)} млн руб., S {num(sn['score'], 4)}", w=700, h=520)
    p.poly(ch.buffer(RULES["chamber_rule"]["max_dist_m"]), "none", MUTED, 1.2, dash="5 4")
    draw(p, inp, new, world)
    p.ring(proj.x, proj.y, 6, ROSE, 2)
    note(p, proj.x, proj.y, f"проекция точки: {num(proj.distance(ch), 1)} м до камеры ≤ 10 м", TEXT, "end", dx=-10, dy=24)
    note(p, ch.x, ch.y, "участок кончается в существующей камере", GREEN, dx=10, dy=-10)
    figure("chamber-10m", [p], "Врезка в существующую камеру ближе 10 м", width=740)


def road_cross(runs):
    inp, _, new = runs["road-cross"]
    road = restriction(inp, "road")
    spec_pr, spec = next((pr, g) for pr, g in segments(new) if pr["laying_method"] == "special")
    world = around(spec, 20)
    left = panel(0, world, "Дорога под острым углом к прямой", f"спецпроход {num(spec_pr['length'])} м одним прямым участком, "
                 f"S {num(summary(new)['score'], 4)}")
    route = shapely.ops.linemerge([g for pr, g in segments(new)])
    straight = LineString([route.coords[0], route.coords[-1]])
    hit = straight.intersection(road.boundary)
    hit = hit.geoms[0] if hasattr(hit, "geoms") else hit
    for c in lines_of(straight.intersection(box(*world))):
        left.line(c, MUTED, 1.5, dash="4 4")
    draw(left, inp, new, world)
    note(left, hit.x, hit.y, f"напрямую {num(cross_angle(list(straight.coords), edge_at(road, hit)), 0)}° < 45°", ROSE, "end", dx=-10, dy=70)
    entry = spec.intersection(road.boundary)
    entry = entry.geoms[0] if hasattr(entry, "geoms") else entry
    note(left, entry.x, entry.y, f"угол {num(cross_angle(list(spec.coords), edge_at(road, entry)), 1)}° ≥ 45°", GREEN, dx=14, dy=-14)
    for piece in getattr(spec.difference(road), "geoms", []):
        m = piece.interpolate(0.5, normalized=True)
        side = "end" if m.x < spec.centroid.x else "start"
        note(left, m.x, m.y, f"{num(piece.length)} м за дорогой", ORANGE, side, dx=-14 if side == "end" else 14, dy=18 if side == "end" else -6)

    inp2, _, new2 = runs["road-oblique"]
    road2 = restriction(inp2, "road")
    cp = point(inp2)
    pipe = next(g for pr, g in inp2 if pr["object_type"] == "heat_network")
    proj = pipe.interpolate(pipe.project(cp))
    ch = next(g for pr, g in new2 if pr["object_type"] == "heat_chamber")
    world2 = (proj.x - 32, proj.y - 22, proj.x + 32, proj.y + 34)
    right = panel(1, world2, "Проекция точки в дороге", f"камера врезки вне дороги и полосы 3 м, S {num(summary(new2)['score'], 4)}")
    right.poly(road2.buffer(RULES["restrictions"]["road"]["margin_m"]).intersection(box(*world2)), "none", ORANGE, 1.2, dash="5 4")
    draw(right, inp2, new2, world2)
    right.ring(proj.x, proj.y, 6, ROSE, 2)
    note(right, proj.x, proj.y, "проекция в дороге", ROSE, dx=10, dy=22)
    note(right, ch.x, ch.y, f"камера: {num(ch.distance(road2))} м до дороги ≥ 3 м", GREEN, "end", dx=-4, dy=24)
    note(right, proj.x + 18, proj.y + 22, "полоса 3 м", ORANGE)
    figure("road-cross", [left, right], "Пересечение дороги: угол, спецучасток и камера врезки вне дороги")


def road_line(runs):
    inp, old, new = runs["road-line"]
    axis = restriction(inp, "road")
    spec_pr, spec = next((pr, g) for pr, g in segments(new) if pr["laying_method"] == "special")
    c = spec.centroid
    world = (c.x - 30, c.y - 42, c.x + 30, c.y + 52)
    left = panel(0, world, "v0.8.1: точка без сети", f"дорога линией, S {num(summary(old)['score'], 3)}")
    draw(left, inp, old, world)
    right = panel(1, world, "v0.9.0: ось дороги — полигон нулевой ширины", f"S {num(summary(new)['score'], 4)}")
    draw(right, inp, new, world)
    cut = spec.intersection(axis)
    note(right, cut.x, cut.y, f"угол к оси {num(cross_angle(list(spec.coords), edge_at(axis, cut)), 1)}°", GREEN, dx=10, dy=-10)
    halves = " + ".join(num(q.length) for q in spec.difference(axis.buffer(0.001)).geoms)
    note(right, cut.x, cut.y, f"спецучасток {num(spec_pr['length'])} м = {halves} м", ORANGE, "end", dx=-12, dy=24)
    figure("road-line", [left, right], "Дорога, заданная линией: до и после")


def gas_acute(runs):
    inp, old, new = runs["gas-acute"]
    gas = restriction(inp, "gas_pipeline")
    panels = []
    for i, (tag, out) in enumerate((("v0.8.1", old), ("v0.9.0", new))):
        spec_pr, spec = next((pr, g) for pr, g in segments(out) if pr["laying_method"] == "special")
        dn = spec_pr["diameter"]
        norm = norm_to_line("gas_pipeline", dn)
        hit = spec.intersection(gas)
        world = (hit.x - 16, hit.y - 14, hit.x + 16, hit.y + 14)
        base = shapely.ops.unary_union([g for pr, g in segments(out) if pr["laying_method"] == "base"])
        close = base.intersection(gas.buffer(norm - 0.01)).difference(hit.buffer(norm + 0.1))
        angle = cross_angle(list(spec.coords), edge_at(gas, hit))
        sub = (f"пересечение под {num(angle, 1)}°, ближе нормы вне круга {num(close.length)} м" if close.length > 0.01
               else f"пересечение под {num(angle, 1)}°, вне круга трасса не ближе нормы")
        p = panel(i, world, f"{tag}: газопровод под острым углом к прямой", sub)
        p.poly(gas.buffer(norm).intersection(box(*world)), "#eab308", opacity=0.08)
        p.poly(hit.buffer(norm + 0.1), "none", GREEN if close.length <= 0.01 else MUTED, 1.4, dash="4 3")
        draw(p, inp, out, world)
        for c in lines_of(close):
            p.line(c, ROSE, 6)
        if close.length > 0.01:
            note(p, world[0], world[1], f"до газопровода {num(close.distance(gas))} м < нормы {num(norm)} м", ROSE, dx=14, dy=-40)
        else:
            note(p, world[0], world[1], f"круг «норма + 0,1 м», радиус {num(norm + 0.1)} м", GREEN, dx=14, dy=-40)
        rule = RULES["restrictions"]["gas_pipeline"]
        note(p, world[0], world[1], f"норма {num(norm)} м = {num(rule['clearance_m'], 1)} + {num(DN[dn]['width_m'] / 2, 3)} (половина "
             f"Ду {dn}) + {num(rule['half_width_m'], 1)} (газопровод)", TEXT, dx=14, dy=-22)
        panels.append(p)
    figure("gas-acute", panels, "Острое пересечение газопровода: до и после")


def zones_overlap(runs):
    inp, _, new = runs["gas-road"]
    road, gas = restriction(inp, "road"), restriction(inp, "gas_pipeline")
    specs = [(pr, g) for pr, g in segments(new) if pr["laying_method"] == "special"]
    zone = shapely.ops.unary_union([g for _, g in specs])
    c = zone.centroid
    world = (c.x - 22, c.y - 18, c.x + 22, c.y + 18)
    p = panel(0, world, "Газопровод в 2 м от дороги", f"участок делится на каждой смене набора зон, S {num(summary(new)['score'], 4)}",
              w=760, h=560)
    route = shapely.ops.unary_union([g for _, g in segments(new)])
    p.poly(gas.buffer(RULES["restrictions"]["gas_pipeline"]["margin_m"]).intersection(box(*world)), "#eab308", opacity=0.12)
    road_zone = route.intersection(road.buffer(RULES["restrictions"]["road"]["margin_m"] + 0.02)).buffer(3, cap_style=2)
    p.poly(road_zone, "none", ORANGE, 1.2, dash="5 4")
    draw(p, inp, new, world)
    for pr, g in specs:
        k = pr["cost"] / (pr["length"] * DN[pr["diameter"]]["new_rub_m"])
        mid = g.interpolate(0.5, normalized=True)
        names = [n for n, obj, kind in (("дорога", road, "road"), ("газопровод", gas, "gas_pipeline"))
                 if obj.distance(mid) <= RULES["restrictions"][kind]["margin_m"] + 0.05 or obj.contains(mid)]
        note(p, mid.x, mid.y, f"{' + '.join(names)} · Kспец {num(k)} · {num(pr['length'])} м", ORANGE, dx=16, dy=4)
    first = next(g for pr, g in new if pr["object_type"] == "technical_node")
    note(p, first.x, first.y, "технические узлы на каждой границе", MUTED, "end", dx=-40, dy=4, size=11)
    note(p, world[0], world[1], "пунктир — зона дороги: полигон и 3 м вдоль трассы; жёлтым — зона газопровода 2 м", MUTED, dx=14, dy=-12,
         size=11)
    figure("zones-overlap", [p], "Наложение зон дороги и газопровода", width=800)


def u_building(runs):
    inp, old, new = runs["u-building"]
    cp = point(inp)
    bld = next(g for pr, g in inp if pr.get("restriction_type") == "oks" and g.contains(cp))
    near = bld.exterior.interpolate(bld.exterior.project(cp))
    pipe = next(g for pr, g in inp if pr["object_type"] == "heat_network")
    world = (bld.bounds[0] - 22, pipe.bounds[1] - 14, bld.bounds[2] + 18, bld.bounds[3] + 12)
    left = panel(0, world, "v0.8.1: точка без сети", f"S {num(summary(old)['score'], 3)}")
    draw(left, inp, old, world)
    right = panel(1, world, "v0.9.0: вход у ближайшей допустимой точки", f"S {num(summary(new)['score'], 4)}")
    seg_pr, seg = segments(new)[-1]
    zone = bld.buffer(RULES["restrictions"]["oks_existing"]["clearance_m"][0]["m"] + DN[seg_pr["diameter"]]["width_m"] / 2)
    right.poly(zone, "none", MUTED, 1.0, dash="4 4")
    draw(right, inp, new, world)
    ray = LineString([cp, (cp.x + (near.x - cp.x) * 40, cp.y + (near.y - cp.y) * 40)])
    back = ray.intersection(bld)
    far = next(g for g in getattr(back, "geoms", [back]) if g.distance(cp) > 0.1)
    right.line([near.coords[0], far.coords[0]], ROSE, 2, dash="4 3")
    note(right, bld.centroid.x, bld.bounds[1], f"ближайшая {num(cp.distance(near), 1)} м: луч в другое крыло", ROSE, dx=-20, dy=40)
    entry = LineString([seg.coords[-2], seg.coords[-1]]).intersection(bld.exterior)
    note(right, entry.x, entry.y, f"вход {num(cp.distance(entry), 1)} м", GREEN, "end", dx=-8, dy=-12)
    figure("u-building", [left, right], "П-образное здание: вход у ближайшей допустимой точки контура")


def chamber_turns(out):
    """Повороты путей точек в камерах: (камера, первое звено ребра к точке, угол)."""
    segs = segments(out)
    parent = {pr["end_node_id"]: g for pr, g in segs}
    turns = []
    for pr, g in segs:
        up = parent.get(pr["start_node_id"])
        if up is not None:
            x = g.coords[0]
            turns.append((x, g.coords[1], deflection(g.coords[1], x, up.coords[-2])))
    return turns


def turn_chamber(runs):
    inp, old, new = runs["turn-chamber"]
    panels = []
    for i, (tag, out) in enumerate((("v0.8.1", old), ("v0.9.0", new))):
        turns = chamber_turns(out)
        worst = max(turns, key=lambda t: t[2])
        world = (worst[0][0] - 16, worst[0][1] - 14, worst[0][0] + 16, worst[0][1] + 14)
        p = panel(i, world, f"{tag}: повороты путей в камере", f"наибольший {num(worst[2])}°, S {num(summary(out)['score'], 4)}")
        draw(p, inp, out, world)
        for x, nxt, deg in turns:
            if deg < 3:
                continue
            d = math.dist(x, nxt)
            at = (x[0] + (nxt[0] - x[0]) * min(6, d) / d, x[1] + (nxt[1] - x[1]) * min(6, d) / d)
            note(p, at[0], at[1], f"{num(deg)}°", ROSE if deg > 90 else GREEN, dx=10, dy=4)
        panels.append(p)
    figure("turn-chamber", panels, "Поворот круче 90° в камере: до и после")


def strict_shape(runs, counts):
    inp, old, new = runs["strict-shape"]
    panels = []
    for i, (tag, out) in enumerate((("v0.8.1", old), ("v0.9.0", new))):
        seg = segments(out)[0][1]
        cs = list(seg.coords)
        bends = [(cs[k], deflection(cs[k - 1], cs[k], cs[k + 1])) for k in range(1, len(cs) - 1)]
        if i == 0:
            world = around(LineString([b[0] for b in bends]), 12)
        found = ", ".join(f"{c} — {n}" for c, n in sorted(counts.items()) if c in ("B16", "B17", "B18")) if i == 0 else "нет"
        p = panel(i, world, f"{tag}: {len(bends)} {'вершина' if len(bends) == 1 else 'вершин'} у угла здания",
                  f"check18 B16–B18 в варианте 1: {found}, S {num(summary(out)['score'], 4)}")
        dn = segments(out)[0][0]["diameter"]
        for pr, g in inp:
            if pr["object_type"] == "oks_existing":
                p.poly(g.buffer(RULES["restrictions"]["oks_existing"]["clearance_m"][0]["m"] + DN[dn]["width_m"] / 2).intersection(box(*world)), "none", MUTED, 1.0, dash="4 4")
        draw(p, inp, out, world)
        for (x, y), deg in bends:
            p.dot(x, y, 3, ROSE if i == 0 else GREEN)
            note(p, x, y, f"{num(deg, 1)}°", ROSE if i == 0 else GREEN, dx=8, dy=4)
        panels.append(p)
    figure("strict-shape", panels, "Строгая форма трассы: дуга из мелких изломов и один поворот")


def dn_length(runs):
    inp, _, new = runs["dn-length"]
    seg_pr, seg = segments(new)[0]
    flow = seg_pr["flow_tph"]
    by_flow = min(d for d in DN if DN[d]["capacity_tph"] >= flow)
    world = around(seg, 18)
    p = panel(0, world, "Ступень Ду по предельной длине", f"расход {num(flow, 3)} т/ч: по расходу хватает Ду {by_flow}, "
              f"путь {num(seg_pr['length'])} м → Ду {seg_pr['diameter']}", w=700, h=640)
    draw(p, inp, new, world)
    for d in sorted(DN):
        limit = DN[d]["max_length_m"]
        if by_flow <= d < seg_pr["diameter"] and limit < seg.length:
            m = seg.interpolate(seg.length - limit)
            p.line([(m.x - 4, m.y), (m.x + 4, m.y)], ROSE, 3)
            note(p, m.x, m.y, f"{limit} м от точки — предел Ду {d}", ROSE, dx=14, dy=4)
    a = seg.coords[0]
    note(p, a[0], a[1], f"врезка: путь {num(seg.length)} м, Ду {seg_pr['diameter']} — предел {DN[seg_pr['diameter']]['max_length_m']} м",
         GREEN, "end", dx=-14, dy=22)
    figure("dn-length", [p], "Ду по предельной длине пути", width=740)


def main():
    old_jar = sys.argv[1] if len(sys.argv) > 1 else "data/out/goal/v0.8.1.jar"
    os.makedirs(RUNS, exist_ok=True)
    os.makedirs("docs/assets/algorithm", exist_ok=True)
    runs, old_found = {}, {}
    for path in sorted(glob.glob(f"{INPUTS}/*.geojson")):
        name = os.path.basename(path)[:-len(".geojson")]
        new = run(NEW_JAR, name)
        old = run(old_jar, name)
        verdict, _ = check(name, new)
        old_verdict, old_counts = check(name, old) if old else ("вход не принят", {})
        print(f"{name}: {verdict}; {os.path.basename(old_jar)}: {old_verdict}, вариант 1: {old_counts}")
        runs[name] = (load(path), variant(load(old)) if old else [], variant(load(new)))
        old_found[name] = old_counts
    wall_pipe(runs)
    chamber_10m(runs)
    road_cross(runs)
    road_line(runs)
    gas_acute(runs)
    zones_overlap(runs)
    u_building(runs)
    turn_chamber(runs)
    strict_shape(runs, old_found["strict-shape"])
    dn_length(runs)


if __name__ == "__main__":
    main()
