import argparse
import json
import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import shapely
from matplotlib.lines import Line2D
from matplotlib.patches import Patch
from pyproj import Transformer
from shapely.geometry import shape

TO_UTM = Transformer.from_crs("EPSG:4326", "EPSG:32637", always_xy=True)
FILL = {
    "oks": ("#b9b4ad", "#8a847c"),
    "road": ("#e9e6df", "#c9c3b6"),
    "tram_tracks": ("#d9b8e8", "#a77fbf"),
    "railway": ("#9e9e9e", "#555555"),
    "water": ("#bfe0f5", "#6aa8d6"),
    "park": ("#cfe8c3", "#8fbf7c"),
    "social_area": ("#f6e3b8", "#d4a94a"),
    "prohibited_site": ("#f2c2c2", "#c46060"),
    "metro": ("#d6d0f0", "#7a6ec2"),
}
LINES = {
    "gas_pipeline": ("#e3a400", 1.0, (0, (4, 2))),
    "power_cable": ("#c0392b", 0.8, (0, (1, 1.5))),
    "water_supply": ("#2e86de", 0.8, (0, (6, 2))),
    "sewer": ("#7d5a3c", 0.8, (0, (2, 2))),
}
LABELS = {
    "oks": "здания",
    "road": "дорога",
    "tram_tracks": "трамвай",
    "railway": "ж/д",
    "water": "вода",
    "park": "парк",
    "social_area": "соцобъект",
    "prohibited_site": "запретная территория",
    "metro": "метро",
    "gas_pipeline": "газопровод",
    "power_cable": "кабель",
    "water_supply": "водопровод",
    "sewer": "канализация",
}
NEW_HOUSE = ("#f7b267", "#c4621d")
VARIANT_COLORS = ("#d62728", "#1f77b4", "#2ca02c")
FOCUS = ("oks_connection_point", "heat_network", "heat_chamber")


def utm(geom):
    return shapely.transform(geom, lambda xy: np.column_stack(TO_UTM.transform(xy[:, 0], xy[:, 1])))


def load(path):
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    result = []
    for f in data["features"]:
        g = f.get("geometry")
        try:
            geom = utm(shape(g)) if g and g.get("coordinates") else None
        except Exception:
            geom = None
        result.append((f["properties"], geom))
    return result


def polygons(geom):
    for part in getattr(geom, "geoms", [geom]):
        if part.geom_type == "Polygon":
            yield part


def draw_poly(ax, geom, face, edge, z, lw=0.4):
    for p in polygons(geom):
        ax.fill(*p.exterior.xy, facecolor=face, edgecolor=edge, linewidth=lw, zorder=z)
        for hole in p.interiors:
            ax.fill(*hole.xy, facecolor="white", edgecolor=edge, linewidth=lw, zorder=z)


def draw_line(ax, geom, **kw):
    for part in getattr(geom, "geoms", [geom]):
        if part.geom_type == "LineString":
            ax.plot(*part.xy, **kw)


def draw_input(ax, features):
    cps = [(p, g) for p, g in features if p.get("object_type") == "oks_connection_point" and g is not None]
    hosts = {
        i
        for i, (p, g) in enumerate(features)
        if p.get("restriction_type") == "oks"
        and g is not None
        and (p.get("_new") or any(g.buffer(0.05).contains(c) for _, c in cps if g.distance(c) < 1))
    }
    for i, (p, g) in enumerate(features):
        if g is None:
            continue
        rtype = p.get("restriction_type")
        kind = p.get("object_type")
        if rtype in FILL:
            face, edge = NEW_HOUSE if i in hosts else FILL[rtype]
            z = {"road": 1, "tram_tracks": 2, "railway": 2, "oks": 4}.get(rtype, 1.5)
            if g.geom_type in ("Polygon", "MultiPolygon"):
                draw_poly(ax, g, face, edge, z)
            else:
                draw_line(ax, g, color=edge, linewidth=2, zorder=z)
        elif rtype in LINES:
            color, lw, ls = LINES[rtype]
            draw_line(ax, g, color=color, linewidth=lw, linestyle=ls, zorder=3)
        elif rtype:
            if g.geom_type == "Point":
                ax.plot(g.x, g.y, marker="x", color="#444", markersize=4, zorder=3)
            elif g.geom_type in ("Polygon", "MultiPolygon"):
                draw_poly(ax, g, "#eeeeee", "#777777", 1.5)
            else:
                draw_line(ax, g, color="#777777", linewidth=0.8, zorder=3)
        elif kind == "heat_network":
            dn = p.get("diameter") or 50
            draw_line(
                ax,
                g,
                color="#ff7f0e",
                linewidth=0.8 + 2.2 * min(dn, 1000) / 1000,
                alpha=0.75,
                solid_capstyle="butt",
                zorder=5,
            )
        elif kind == "heat_chamber":
            ax.plot(g.x, g.y, marker="s", markersize=4, markerfacecolor="white", markeredgecolor="#ff7f0e", zorder=6)
        elif kind == "source":
            ax.plot(g.x, g.y, marker="*", markersize=10, color="#d62728", zorder=6)
    for p, g in cps:
        ax.plot(
            g.x,
            g.y,
            marker="o",
            markersize=4.5,
            color="#c4621d",
            markeredgecolor="black",
            markeredgewidth=0.5,
            zorder=9,
        )
        ax.annotate(
            str(p["id"]),
            (g.x, g.y),
            xytext=(4, 4),
            textcoords="offset points",
            fontsize=6.5,
            zorder=10,
            bbox=dict(boxstyle="round,pad=0.15", facecolor="white", edgecolor="none", alpha=0.8),
        )


def draw_output(ax, features, variant, color):
    for p, g in features:
        if str(p.get("variant_id")) != variant or g is None:
            continue
        kind = p.get("object_type")
        if kind == "heat_network":
            special = p.get("laying_method") == "special"
            draw_line(
                ax,
                g,
                color=color,
                linewidth=2.6 if special else 1.6,
                linestyle="-" if not special else (0, (3, 1.2)),
                zorder=7,
                solid_capstyle="round",
            )
        elif kind == "heat_chamber":
            ax.plot(
                g.x,
                g.y,
                marker="s",
                markersize=5.5,
                color=color,
                markeredgecolor="black",
                markeredgewidth=0.5,
                zorder=8,
            )
        elif kind == "technical_node":
            ax.plot(g.x, g.y, marker="D", markersize=3, color="white", markeredgecolor=color, zorder=8)


def scale_bar(ax):
    x0, x1 = ax.get_xlim()
    y0, y1 = ax.get_ylim()
    width = x1 - x0
    nice = [5, 10, 20, 25, 50, 100, 200, 250, 500, 1000, 2000, 5000]
    length = max(n for n in nice if n <= width / 4) if width / 4 >= 5 else 5
    bx, by = x0 + 0.04 * width, y0 + 0.04 * (y1 - y0)
    ax.plot([bx, bx + length], [by, by], color="black", linewidth=3, zorder=11, solid_capstyle="butt")
    ax.text(bx + length / 2, by + 0.015 * (y1 - y0), f"{length} м", ha="center", va="bottom", fontsize=7, zorder=11)


def legend(ax, features, variants, reference=False):
    present = {p.get("restriction_type") for p, _ in features} | {p.get("object_type") for p, _ in features}
    handles = [Patch(facecolor=NEW_HOUSE[0], edgecolor=NEW_HOUSE[1], label="новый дом (ОКС)")]
    handles += [Patch(facecolor=FILL[k][0], edgecolor=FILL[k][1], label=LABELS[k]) for k in FILL if k in present]
    handles += [
        Line2D([], [], color=LINES[k][0], linestyle=LINES[k][2], label=LABELS[k]) for k in LINES if k in present
    ]
    handles += [
        Line2D([], [], color="#ff7f0e", linewidth=2, label="теплосеть (толщина — Ду)"),
        Line2D([], [], marker="s", linestyle="", markerfacecolor="white", markeredgecolor="#ff7f0e", label="камера"),
        Line2D([], [], marker="o", linestyle="", color="#c4621d", markeredgecolor="black", label="точка подключения"),
    ]
    if reference:
        handles.append(Line2D([], [], color="#5b6b8c", linestyle=(0, (2, 2)), label="эталонный путь"))
    for v, color in variants:
        handles += [
            Line2D([], [], color=color, linewidth=1.8, label=f"новая сеть, вариант {v}"),
            Line2D([], [], color=color, linewidth=2.6, linestyle=(0, (3, 1.2)), label="спецпроход"),
        ]
    ax.legend(handles=handles, loc="upper left", bbox_to_anchor=(1.01, 1.0), fontsize=7, frameon=False)


def summary_text(out, variant):
    for p, _ in out:
        if p.get("object_type") == "variant_summary" and str(p.get("variant_id")) == variant:
            return (
                f"вариант {variant}, место {p.get('rank')}: S {p.get('score')}, "
                f"{p.get('calculated_cost', 0) / 1e6:.1f} млн руб., {p.get('new_network_length')} м, "
                f"без сети: {len(p.get('unconnected_oks_ids') or [])}"
            )
    return f"вариант {variant}"


def render(scene_dir, out_path=None, png=None, variants=None):
    scene_dir = Path(scene_dir)
    features = load(scene_dir / "input.geojson")
    meta = (
        json.loads((scene_dir / "scene.json").read_text(encoding="utf-8"))
        if (scene_dir / "scene.json").exists()
        else {"id": scene_dir.name, "title": ""}
    )
    out = load(out_path) if out_path else []
    found = sorted({str(p.get("variant_id")) for p, _ in out if p.get("object_type") == "variant_summary"})
    chosen = variants or found[:3]
    panels = chosen if out else [None]
    fig, axes = plt.subplots(1, len(panels), figsize=(8.5 * len(panels), 7.5), squeeze=False)
    focus = [g for p, g in features if g is not None and p.get("object_type") in FOCUS]
    geoms = focus or [g for _, g in features if g is not None]
    minx, miny, maxx, maxy = shapely.total_bounds(geoms) if geoms else (0, 0, 1, 1)
    bbox = meta.get("source", {}).get("bbox_wgs84")
    if bbox and meta.get("family") not in ("REAL", "DEPTH"):
        bx0, by0, bx1, by1 = utm(shapely.box(*bbox)).bounds
        minx, miny, maxx, maxy = min(minx, bx0), min(miny, by0), max(maxx, bx1), max(maxy, by1)
        minx, miny, maxx, maxy = max(minx, bx0 - 60), max(miny, by0 - 60), min(maxx, bx1 + 60), min(maxy, by1 + 60)
    paths = [
        utm(shapely.LineString(r["path_wgs84"]))
        for r in meta.get("reference", {}).values()
        if isinstance(r, dict) and len(r.get("path_wgs84") or []) > 1
    ]
    for ax, variant in zip(axes[0], panels):
        draw_input(ax, features)
        for line in paths:
            ax.plot(*line.xy, color="#5b6b8c", linewidth=1.2, linestyle=(0, (2, 2)), zorder=6.5)
        title = f"{meta['id']}: {meta.get('title', '')}"
        if variant is not None:
            draw_output(ax, out, variant, VARIANT_COLORS[0])
            title += "\n" + summary_text(out, variant)
        ax.set_title(title, fontsize=9, loc="left")
        ax.set_aspect("equal")
        ax.set_xlim(minx - 10, maxx + 10)
        ax.set_ylim(miny - 10, maxy + 10)
        ax.set_xticks([])
        ax.set_yticks([])
        scale_bar(ax)
        legend(ax, features, [(variant, VARIANT_COLORS[0])] if variant else [], bool(paths))
    fig.tight_layout()
    png = Path(png) if png else scene_dir / ("result.png" if out else "input.png")
    fig.savefig(png, dpi=130)
    plt.close(fig)
    return png


def main():
    parser = argparse.ArgumentParser(description="Картинка сцены: вход или вход с результатом сервиса поверх")
    parser.add_argument("scenes", nargs="+", help="папки data/scenes/<ID>")
    parser.add_argument("--out", help="выход сервиса (GeoJSON); только для одной сцены")
    parser.add_argument("--png", help="куда писать PNG; по умолчанию input.png или result.png в папке сцены")
    parser.add_argument("--variant", nargs="*", help="какие варианты рисовать, по умолчанию все до трёх")
    args = parser.parse_args()
    if args.out and len(args.scenes) > 1:
        sys.exit("--out задаётся для одной сцены")
    for folder in args.scenes:
        print(render(folder, args.out, args.png, args.variant))


if __name__ == "__main__":
    main()
