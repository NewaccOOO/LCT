#!/usr/bin/env python3
"""GIF для README: как строится граф видимости и маршрут, шаг за шагом в темпе для человека.

Запуск из корня: uv run --project tools python scripts/graph_gif.py
Сцена и расчёт — graph_scene из docs_assets.py (та же, что на graph-steps.svg). Пишет docs/assets/graph-build.gif.
"""
from PIL import Image, ImageDraw, ImageFont
from shapely.geometry import Polygon

from docs_assets import graph_scene

W, H, SS = 1000, 660, 2  # кадр и коэффициент сглаживания (рисуем крупнее, уменьшаем)
# сцена крупнее, чем на graph-steps.svg: три ряда зданий, точка подключения в одном углу, труба в другом
BUILDINGS = [Polygon(p) for p in [
    [(20, 20), (70, 20), (70, 60), (20, 60)],
    [(90, 15), (150, 15), (150, 45), (125, 45), (125, 75), (90, 75)],
    [(175, 30), (215, 25), (220, 65), (180, 70)],
    [(250, 20), (300, 20), (300, 55), (250, 55)],
    [(330, 35), (380, 30), (385, 75), (335, 80)],
    [(25, 100), (75, 100), (75, 150), (25, 150)],
    [(100, 110), (160, 105), (165, 150), (105, 155)],
    [(190, 95), (230, 95), (230, 140), (190, 140)],
    [(345, 105), (400, 105), (400, 150), (345, 150)],
    [(30, 180), (80, 175), (85, 225), (35, 230)],
    [(110, 190), (170, 190), (170, 235), (110, 235)],
    [(200, 170), (240, 165), (245, 215), (205, 220)],
    [(275, 185), (330, 185), (330, 230), (275, 230)],
    [(355, 175), (405, 175), (405, 225), (355, 225)],
]]
PARKS = [Polygon([(255, 90), (320, 95), (315, 150), (250, 145)])]
START, TARGET, PIPE = (10, 8), (360, 258), [(290, 258), (420, 258)]
BG = (11, 18, 32)
TEXT, MUTED = (226, 232, 240), (148, 163, 184)
BLUE, GRAY, AMBER, ROSE, NODE = (56, 189, 248), (100, 116, 139), (180, 83, 9), (244, 63, 94), (224, 242, 254)
BUILDING, PARK = (51, 65, 85), (20, 83, 45)
WORLD = (0, 0, 425, 268)
TOP, PAD = 78, 24
FONT = "/System/Library/Fonts/Helvetica.ttc"


def font(size, bold=False):
    try:
        return ImageFont.truetype(FONT, size * SS, index=1 if bold else 0)
    except OSError:
        return ImageFont.load_default(size * SS)


class Frame:
    def __init__(self, title, caption):
        self.im = Image.new("RGB", (W * SS, H * SS), BG)
        self.d = ImageDraw.Draw(self.im, "RGBA")
        minx, miny, maxx, maxy = WORLD
        self.s = min((W - 2 * PAD) / (maxx - minx), (H - TOP - PAD) / (maxy - miny)) * SS
        self.ox = (W * SS - (maxx - minx) * self.s) / 2
        self.oy = TOP * SS + (H * SS - TOP * SS - PAD * SS - (maxy - miny) * self.s) / 2 + maxy * self.s
        self.d.text((PAD * SS, 22 * SS), title, TEXT, font(17, True))
        self.d.text((PAD * SS, 48 * SS), caption, MUTED, font(13))

    def p(self, x, y):
        return self.ox + x * self.s, self.oy - y * self.s

    def poly(self, geom, fill=None, outline=None, width=1):
        for g in getattr(geom, "geoms", [geom]):
            pts = [self.p(*c) for c in g.exterior.coords]
            self.d.polygon(pts, fill=fill, outline=outline, width=width * SS)

    def line(self, coords, color, width=2):
        self.d.line([self.p(*c) for c in coords], fill=color, width=width * SS, joint="curve")

    def dot(self, x, y, r, fill, outline=None):
        a, b = self.p(x, y)
        self.d.ellipse([a - r * SS, b - r * SS, a + r * SS, b + r * SS], fill=fill, outline=outline, width=SS)

    def label(self, x, y, text, dx=0, dy=0, anchor="la"):
        a, b = self.p(x, y)
        self.d.text((a + dx * SS, b + dy * SS), text, TEXT, font(12, True), anchor=anchor)

    def done(self):
        return self.im.resize((W, H), Image.LANCZOS)


def frames():
    sc = graph_scene(START, BUILDINGS, PARKS, TARGET, PIPE)
    nodes, edges = sc["nodes"], sc["edges"]
    start, target = sc["start"], sc["target"]

    def base(title, caption, zones=0, node_set=(), edge_set=(), settled=(), tree=(), path=None, octo=None, extra=None):
        f = Frame(title, caption)
        f.line(sc["pipe"], AMBER, 4)
        for b in sc["buildings"]:
            f.poly(b, BUILDING)
        for park in sc["parks"]:
            f.poly(park, PARK)
        for z in sc["zone_list"][:zones]:
            f.poly(z, outline=GRAY, width=1)
        for i, j in edge_set:
            f.line([nodes[i], nodes[j]], BLUE + (150,), 1)
        for i, j in tree:
            f.line([nodes[i], nodes[j]], (251, 146, 60), 2)
        if extra:
            extra(f)
        if path:
            f.line(path, MUTED, 2)
        if octo:
            f.line(octo, BLUE, 4)
        for i in node_set:
            f.dot(*nodes[i], 3.2, NODE)
        for i in settled:
            f.dot(*nodes[i], 4.5, (251, 146, 60))
        f.dot(*start, 5, (248, 250, 252), BG)
        f.dot(*target, 6, None, BLUE)
        return f.done()

    out = []  # (кадр, длительность мс)
    t1 = "1. Препятствия, точка подключения и врезка"
    f = base(t1, "здания и парк — препятствия; трасса идёт от точки подключения к врезке в трубу",
             extra=lambda f: (f.label(*start, "точка подключения", 10, -6), f.label(*target, "врезка", -14, 10, "ra"),
                              f.label(45, 40, "здание", anchor="mm"), f.label(285, 120, "парк", anchor="mm")))
    out.append((f, 2200))

    t2 = "2. Зоны отступа и узлы графа"
    zone_nodes = []
    for k, z in enumerate(sc["zone_list"]):
        zone_nodes.append([i for i in range(2, len(nodes)) if nodes[i] in set(z.exterior.coords)])
    shown = []
    for k in range(len(sc["zone_list"])):
        shown = shown + zone_nodes[k]
        out.append((base(t2, "каждое препятствие раздувается на отступ; узлы — только выпуклые вершины зон", k + 1, shown), 300))
    out.append((base(t2, f"{len(nodes)} узлов: точка, врезка и {len(nodes) - 2} выпуклых вершин", len(sc["zone_list"]), shown), 1200))

    t3 = "3. Рёбра графа видимости"
    all_nodes = list(range(len(nodes)))
    total_zones = len(sc["zone_list"])
    processed, kept = [0, 1], []
    total_pairs = len(nodes) * (len(nodes) - 1) // 2
    cap = "красным — все пары с узлами новой зоны, остаются видимые и касательные к зонам в обоих концах"
    for group in zone_nodes:
        cand = [(i, j) for i in group for j in processed] + [(i, j) for i in group for j in group if i > j]

        def flash(f, cand=cand):
            for a, b in cand:
                f.line([nodes[a], nodes[b]], ROSE + (150,), 1)
        out.append((base(t3, cap, total_zones, all_nodes, kept, extra=flash), 240))
        processed = processed + group
        kept = [e for e in edges if e[0] in processed and e[1] in processed]
        out.append((base(t3, cap, total_zones, all_nodes, kept), 240))
    out.append((base(t3, f"{len(edges)} рёбер из {total_pairs} пар: остальные режут зону или входят в узел не по касательной",
                     total_zones, all_nodes, kept), 1600))

    t4 = "4. Дейкстра от точки подключения"
    tree = []
    settled = []
    cap = "узлы закрываются по возрастанию расстояния; оранжевым — дерево кратчайших путей"
    for n, v in enumerate(sc["settled"]):
        settled.append(v)
        if v in sc["pred"]:
            tree.append((sc["pred"][v], v))
        if n % 2 == 1 or v == 1:
            out.append((base(t4, cap, total_zones, all_nodes, edges, settled, tree), 180))
        if v == 1:
            break
    out.append((base(t4, "врезка закрыта — кратчайший путь найден", total_zones, all_nodes, edges, settled, tree), 900))

    t5 = "5. Маршрут и раскладка по 45°"
    out.append((base(t5, "серым — кратчайший путь по графу", total_zones, all_nodes, edges, path=sc["path"]), 1400))
    out.append((base(t5, "голубым — тот же путь после раскладки по направлениям через 45°, как Router.octilinearize",
                     total_zones, all_nodes, edges, path=sc["path"], octo=sc["octo"]), 3000))
    return out


def main():
    seq = frames()
    images = [f.quantize(colors=64, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE) for f, _ in seq]
    images[0].save("docs/assets/graph-build.gif", save_all=True, append_images=images[1:],
                   duration=[d for _, d in seq], loop=0, optimize=True)
    print(f"docs/assets/graph-build.gif: {len(seq)} кадров, {sum(d for _, d in seq) / 1000:.1f} с")


if __name__ == "__main__":
    main()
