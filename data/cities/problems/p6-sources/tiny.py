"""Проба orphan-net + второй источник у сети 31 + третья сеть без источника; у каждой сети своя точка ОКС."""
import json
W = "/Users/a1111/HACK/LCT/LCT/.claude/worktrees/agent-ad18cee7f2c72a4ef/docs/assets/algorithm/inputs/orphan-net.geojson"
OUT = "/Users/a1111/HACK/LCT/LCT/.claude/worktrees/geojson-heat-network-moscow-spb-ea030e/data/cities/problems/p6-sources/tiny-3src.geojson"
d = json.load(open(W))
def pt(i, t, xy, **p):
    return {"type": "Feature", "properties": {"id": i, "object_type": t, **p}, "geometry": {"type": "Point", "coordinates": xy}}
d["features"] += [
    pt(40, "source", [37.597080725, 55.775637598], name="котельная"),
    pt(41, "oks_connection_point", [37.5979, 55.7760], flow_tph=5.0),
    {"type": "Feature", "properties": {"id": 50, "object_type": "heat_network", "diameter": 150},
     "geometry": {"type": "LineString", "coordinates": [[37.62, 55.77], [37.622, 55.77]]}},
    pt(51, "oks_connection_point", [37.621, 55.7704], flow_tph=7.0),
]
json.dump(d, open(OUT, "w"), ensure_ascii=False)
print(len(d["features"]))
