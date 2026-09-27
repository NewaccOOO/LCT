"""Распределение зазоров между зданиями входа: у каждого здания — расстояние до ближайшего соседа.
Запуск: p2_gaps.py вход..."""
import json, sys
from shapely.geometry import shape
from shapely.ops import transform
from shapely.strtree import STRtree
from pyproj import Transformer

T = Transformer.from_crs(4326, 32637, always_xy=True).transform
for src in sys.argv[1:]:
    fs = json.load(open(src))["features"]
    B = [transform(T, shape(f["geometry"])) for f in fs if f["geometry"] and (
        f["properties"]["object_type"] in ("oks_existing", "oks_future") or f["properties"].get("restriction_type") == "oks")]
    tree = STRtree(B)
    gaps = []
    for i, g in enumerate(B):
        near = [B[j].distance(g) for j in tree.query(g.buffer(60)) if j != i]
        gaps.append(min(near) if near else 1e9)
    n = len(B)
    share = lambda t: f"{sum(x < t for x in gaps)} ({100 * sum(x < t for x in gaps) / n:.0f} %)"
    print(f"{src.split('/data/cities/')[-1]}: зданий {n}; ближайший сосед < 0,5 м: {share(0.5)}, < 5 м: {share(5)}, "
          f"< 10,6 м (щель уже 2×(5+0,3)): {share(10.6)}, мин. {min(gaps):.2f} м")
