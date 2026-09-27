"""Путь в выходе от точки подключения до врезки: участки, длины, Ду. p2_path.py выход cp-id[,cp-id]"""
import json, sys
from collections import defaultdict

fs = json.load(open(sys.argv[1]))["features"]
segs = [f["properties"] for f in fs if f["properties"]["object_type"] == "heat_network" and str(f["properties"].get("variant_id")) == "1"]
by_node = defaultdict(list)
for s in segs:
    by_node[s["start_node_id"]].append(s)
    by_node[s["end_node_id"]].append(s)
for cp in sys.argv[2].split(","):
    node, seen, total, path = cp, set(), 0.0, []
    while True:
        nxt = [s for s in by_node[node] if s["id"] not in seen]
        # к врезке — по участку, у которого узел на стороне точки ниже по потоку (end), поток идёт от врезки
        up = [s for s in nxt if s["end_node_id"] == node]
        if not up:
            break
        s = up[0]
        seen.add(s["id"])
        total += s["length"]
        path.append(f"{s['id']}(DN{s['diameter']},{s['length']}м,{s['laying_method']})")
        node = s["start_node_id"]
    print(cp, f"до {node}: {total:.1f} м;", " ← ".join(path) if path else "нет участков")
