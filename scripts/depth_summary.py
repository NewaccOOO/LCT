import json
import sys
from collections import Counter

summaries, special, tech, deep = [], Counter(), Counter(), Counter()
with open(sys.argv[1], encoding="utf-8") as f:
    for line in f:
        line = line.strip().strip(",")
        if line.startswith('{"type":"FeatureCollection"'):
            line = line[line.index("[") + 1:]
        if line.endswith("]}"):
            line = line[:-2]
        if '"object_type"' not in line or not line.startswith("{"):
            continue
        try:
            p = json.loads(line)["properties"]
        except (ValueError, KeyError):
            continue
        t, v = p.get("object_type"), p.get("variant_id")
        if t == "variant_summary":
            summaries.append(p)
        elif t == "heat_network":
            special[v] += p.get("laying_method") == "special"
            ds, de = p.get("depth_start"), p.get("depth_end")
            deep[v] += ds is not None and (ds != 3 or de != 3)
        elif t == "technical_node":
            tech[v] += 1
parts = []
for s in sorted(summaries, key=lambda s: int(s["variant_id"])):
    v = s["variant_id"]
    parts.append(f"v{v} S={s['score']} C={s['calculated_cost'] / 1e6:.2f}млн L={s['new_network_length']} "
                 f"неподкл={len(s['unconnected_oks_ids'])} спец={special[v]} профиль≠3м={deep[v]} техузлы={tech[v]}")
print("; ".join(parts) or "нет variant_summary")
