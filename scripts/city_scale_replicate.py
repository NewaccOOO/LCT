#!/usr/bin/env python3
"""Размножить точки подключения до N (для замера pipeline), сдвигая копии на решётке."""
from __future__ import annotations

import argparse
import copy
import json
import math
import sys
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("-n", "--oks", type=int, required=True)
    parser.add_argument("--step-deg", type=float, default=0.00005, help="шаг решётки для копий (~5 м)")
    args = parser.parse_args()
    data = json.loads(args.input.read_text(encoding="utf-8"))
    templates = [f for f in data["features"] if f["properties"].get("object_type") == "oks_connection_point"]
    if not templates:
        print("нет oks_connection_point", file=sys.stderr)
        return 1
    other = [f for f in data["features"] if f["properties"].get("object_type") != "oks_connection_point"]
    side = max(1, math.ceil(math.sqrt(args.oks / len(templates))))
    out_features = list(other)
    next_id = max((f["properties"].get("id", 0) for f in data["features"] if isinstance(f["properties"].get("id"), int)), default=0) + 1
    count = 0
    for t_idx, template in enumerate(templates):
        if count >= args.oks:
            break
        for i in range(side * side):
            if count >= args.oks:
                break
            row, col = divmod(i, side)
            feat = copy.deepcopy(template)
            props = feat["properties"]
            props["id"] = next_id
            next_id += 1
            x, y = feat["geometry"]["coordinates"]
            feat["geometry"]["coordinates"] = [
                x + col * args.step_deg,
                y + row * args.step_deg,
            ]
            out_features.append(feat)
            count += 1
    out = {"type": "FeatureCollection", "features": out_features}
    if "name" in data:
        out["name"] = data["name"]
    if "crs" in data:
        out["crs"] = data["crs"]
    args.output.write_text(json.dumps(out, ensure_ascii=False), encoding="utf-8")
    print(f"replicated {count} connection points -> {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
