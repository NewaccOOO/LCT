import argparse
import json
import shutil
import sys
import time
from pathlib import Path

import shapely

from heatscenes import extra
from heatscenes.catalog import Ctx, Skip, families, reference_length
from heatscenes.catalog_md import write_catalog
from heatscenes.scene import OUT, Scene
from heatscenes.world import to_utm
from shapely.geometry import LineString, Point


def stale_references(scene: Scene) -> list[str]:
    pipes = shapely.union_all([p.geom for p in scene.of("heat_network")])
    bounds = scene.should.get("max_route_length_m", {}) | scene.full_must().get("route_length_max_m", {})
    points = {str(p.id): p.geom for p in scene.points()}
    problems = []
    for cp, row in scene.reference.items():
        if not isinstance(row, dict) or "path_wgs84" not in row or cp not in points:
            continue
        point = points[cp]
        path = to_utm(LineString(row["path_wgs84"]))
        if Point(path.coords[0]).distance(point) > 0.05:
            problems.append(f"эталон {cp} начинается в {Point(path.coords[0]).distance(point):.1f} м от точки")
        if Point(path.coords[-1]).distance(pipes) > 0.6:
            problems.append(f"эталон {cp} кончается в {Point(path.coords[-1]).distance(pipes):.1f} м от трубы")
    for cp, limit in bounds.items():
        if str(cp) not in points:
            continue
        gap = points[str(cp)].distance(pipes)
        if limit < gap:
            problems.append(f"граница длины {limit} для {cp} короче расстояния до трубы {gap:.0f} м")
    return problems


def main() -> None:
    parser = argparse.ArgumentParser(description="Тестовые сцены трассировки на застройке OSM")
    parser.add_argument("--seed", type=int, default=1809)
    parser.add_argument("--only", nargs="*", help="id сцен или префиксы семейств")
    parser.add_argument("--root", type=Path, default=OUT, help="папка набора, по умолчанию data/scenes-v5")
    parser.add_argument("--clean", action="store_true", help="удалить папку набора перед генерацией")
    args = parser.parse_args()
    root = args.root.resolve()
    if args.clean and root.exists():
        shutil.rmtree(root)
    ctx = Ctx(args.seed)
    written, skipped = [], []
    for sid, build in [*families(ctx), *extra.families(ctx)]:
        if args.only and not any(sid == o or sid.startswith(o) for o in args.only):
            continue
        started = time.time()
        try:
            scene = build()
        except Skip:
            skipped.append(sid)
            print(f"{sid}: место не найдено, пропущена", file=sys.stderr)
            continue
        if scene.items and not scene.raw_input:
            for cp in scene.must.get("connected", []):
                if reference_length(scene, cp) is None:
                    print(f"{sid}: эталон пути для {cp} не найден", file=sys.stderr)
            for problem in stale_references(scene):
                print(f"{sid}: {problem}", file=sys.stderr)
        scene.write(root)
        written.append(sid)
        print(f"{sid}: {scene.title} ({len(scene.items)} объектов, {time.time() - started:.1f} с)", file=sys.stderr)
    write_catalog(root)
    print(json.dumps({"written": len(written), "skipped": skipped}, ensure_ascii=False))


if __name__ == "__main__":
    main()
