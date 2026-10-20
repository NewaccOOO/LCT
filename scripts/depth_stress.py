import json
import sys

from pyproj import Transformer
from shapely.geometry import mapping, shape
from shapely.ops import transform

TO_M = Transformer.from_crs("EPSG:4326", "EPSG:32637", always_xy=True).transform
TO_WGS = Transformer.from_crs("EPSG:32637", "EPSG:4326", always_xy=True).transform


def main(path, scale, mode, out, half=5.0):
    data = json.load(open(path, encoding="utf-8"))
    next_id = max(int(f["properties"]["id"]) for f in data["features"]) + 1
    added = []
    for f in data["features"]:
        p = f["properties"]
        if p.get("object_type") == "oks_connection_point":
            p["flow_tph"] = round(p["flow_tph"] * float(scale), 3)
        elif p.get("restriction_type") in ("gas_pipeline", "power_cable"):
            kind = "tram_tracks" if mode == "tram" or p["restriction_type"] == "power_cable" else "road"
            strip = transform(TO_WGS, transform(TO_M, shape(f["geometry"])).buffer(half, cap_style=2))
            added.append({"type": "Feature", "properties": {"id": next_id, "object_type": "restriction",
                          "restriction_type": kind, "_source": "synthetic"}, "geometry": mapping(strip)})
            next_id += 1
    data["features"] += added
    json.dump(data, open(out, "w", encoding="utf-8"), ensure_ascii=False)
    print(f"{out}: расходы ×{scale}, полос {len(added)} ({mode})")


if __name__ == "__main__":
    main(*sys.argv[1:5])
