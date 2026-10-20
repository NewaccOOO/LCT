import json
import sys
from collections import defaultdict

from pyproj import Transformer
from shapely.geometry import shape
from shapely.ops import transform

TO_M = Transformer.from_crs("EPSG:4326", "EPSG:32637", always_xy=True).transform


def main(path):
    data = json.load(open(path, encoding="utf-8"))
    worst, bad, nodes, n = (0.0, None), [], defaultdict(set), 0
    for f in data["features"]:
        p = f["properties"]
        if p["object_type"] != "heat_network":
            continue
        n += 1
        d0, d1 = p["depth_start"], p["depth_end"]
        length = transform(TO_M, shape(f["geometry"])).length
        slope = abs(d1 - d0) / length if length > 0 else (0 if d0 == d1 else float("inf"))
        if slope > worst[0]:
            worst = (slope, p["id"])
        if slope > 0.10 + 1e-12:
            bad.append(f"уклон {p['id']} {slope:.5f} на {length:.3f} м")
        if min(d0, d1) < 0.7:
            bad.append(f"мельче 0,7: {p['id']} {min(d0, d1)}")
        if (d0 - 3.0) * (d1 - 3.0) < 0:
            bad.append(f"проходит 3,0 м: {p['id']} {d0}→{d1}")
        nodes[(p["variant_id"], str(p["start_node_id"]))].add(d0)
        nodes[(p["variant_id"], str(p["end_node_id"]))].add(d1)
    bad += [f"узел {k[1]}: глубины {sorted(v)}" for k, v in nodes.items() if len(v) > 1]
    print(f"участков {n}, наибольший уклон {worst[0]:.5f} ({worst[1]}), нарушений {len(bad)}")
    for line in bad[:10]:
        print("  ", line)
    print("STRICT OK" if not bad else "STRICT VIOLATIONS")


if __name__ == "__main__":
    main(sys.argv[1])
