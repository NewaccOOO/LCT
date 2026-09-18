#!/usr/bin/env python3
"""Потоково собрать GeoJSON с первыми N точками подключения (остальная инфраструктура без изменений)."""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("-n", "--oks", type=int, required=True, help="число oks_connection_point")
    args = parser.parse_args()
    kept_cp = 0
    features: list[dict] = []
    with args.input.open(encoding="utf-8") as f:
        data = json.load(f)
    for feature in data["features"]:
        props = feature.get("properties") or {}
        if props.get("object_type") == "oks_connection_point":
            if kept_cp >= args.oks:
                continue
            kept_cp += 1
        features.append(feature)
    if kept_cp < args.oks:
        print(f"city_scale_extract: только {kept_cp} точек подключения", file=sys.stderr)
        return 1
    out = {"type": "FeatureCollection", "features": features}
    if "name" in data:
        out["name"] = data["name"]
    if "crs" in data:
        out["crs"] = data["crs"]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False)
    print(f"extracted {kept_cp} connection points -> {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
