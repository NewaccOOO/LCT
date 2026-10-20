import json
import sys
from collections import defaultdict

NORMAL, K_PER_M = 3.0, 0.1


def k(h):
    return 1 + K_PER_M * (h - NORMAL) if h > NORMAL else 1.0


excess, segs, deep, under_special = defaultdict(float), defaultdict(int), defaultdict(int), defaultdict(int)
with open(sys.argv[1], encoding="utf-8") as f:
    for line in f:
        line = line.strip().strip(",")
        if line.startswith('{"type":"FeatureCollection"'):
            line = line[line.index("[") + 1:]
        if line.endswith("]}"):
            line = line[:-2]
        if '"heat_network"' not in line or not line.startswith("{"):
            continue
        try:
            p = json.loads(line)["properties"]
        except (ValueError, KeyError):
            continue
        d0, d1, v = p.get("depth_start"), p.get("depth_end"), p.get("variant_id")
        if d0 is None:
            continue
        kk = (k(d0) + k(d1)) / 2
        excess[v] += p["cost"] - p["cost"] / kk
        segs[v] += 1
        deep[v] += kk > 1
        under_special[v] += kk > 1 and p["laying_method"] == "special"
for v in sorted(excess, key=lambda x: int(x)):
    print(f"v{v} надбавка Kгл={excess[v] / 1e6:.4f} млн ₽ (ΔS={0.7 * excess[v] / 25e6:.4f}) участков={segs[v]} "
          f"с Kгл>1={deep[v]} из них спец={under_special[v]}")
