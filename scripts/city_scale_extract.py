#!/usr/bin/env python3
"""Потоково вырезать из файла «город» n точек подключения, ближайших к точке, вместе с их зданиями.

Сеть, камеры, источник и ограничения, кроме зданий, остаются целиком. Здания остальных точек выбрасываются:
в полном городе они перспективные ОКС, а не препятствия, и в срезе не должны становиться препятствиями.
Генератор пишет здание строкой прямо перед его точкой подключения, по этому порядку они и сопоставляются.
Несколько -n за один проход: выход <out>-<n>.geojson.
"""
from __future__ import annotations

import argparse
import heapq
import math
import re
from pathlib import Path

CP = '"object_type":"oks_connection_point"'
BUILDING = '"restriction_type":"oks"'
POINT = re.compile(r'"coordinates":\[([-0-9.]+),([-0-9.]+)\]')


def nearest_lines(path: Path, lon: float, lat: float, n: int) -> list[int]:
    """Номера строк n ближайших к (lon, lat) точек подключения от ближней к дальней, в локальной проекции."""
    kx = math.cos(math.radians(lat))
    heap: list[tuple[float, int]] = []
    with path.open(encoding="utf-8") as f:
        for number, line in enumerate(f):
            if CP not in line:
                continue
            match = POINT.search(line)
            distance = math.hypot((float(match.group(1)) - lon) * kx, float(match.group(2)) - lat)
            if len(heap) < n:
                heapq.heappush(heap, (-distance, number))
            elif -heap[0][0] > distance:
                heapq.heapreplace(heap, (-distance, number))
    return [number for _, number in sorted(heap, reverse=True)]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("out", type=Path, help="префикс выхода: <out>-<n>.geojson")
    parser.add_argument("-n", "--oks", type=int, nargs="+", required=True)
    parser.add_argument("--at", type=float, nargs=2, default=(37.789238808, 55.851365335), metavar=("LON", "LAT"),
                        help="центр среза, по умолчанию источник файла city-1")
    args = parser.parse_args()
    sizes = sorted(args.oks)
    # срез размера k — первые k по расстоянию; порядок строк в файле сохраняется
    distance_rank = {number: i for i, number in enumerate(nearest_lines(args.input, *args.at, sizes[-1]))}
    outs = {k: args.out.with_name(f"{args.out.name}-{k}.geojson").open("w", encoding="utf-8") for k in sizes}
    first = {k: True for k in sizes}
    pending: str | None = None
    with args.input.open(encoding="utf-8") as f:
        for number, line in enumerate(f):
            body = line.strip().rstrip(",")
            if not body.startswith('{"type":"Feature"'):
                continue
            if BUILDING in body:
                pending = body
                continue
            if CP in body:
                building, pending = pending, None
                position = distance_rank.get(number)
                if position is None:
                    continue
                for k in sizes:
                    if position < k:
                        _write(outs[k], building, first, k)
                        _write(outs[k], body, first, k)
                continue
            for k in sizes:
                _write(outs[k], body, first, k)
    for k, out in outs.items():
        out.write("\n]}\n")
        out.close()
        print(f"{k} -> {out.name}")
    return 0


def _write(out, body: str, first: dict[int, bool], k: int) -> None:
    if first[k]:
        out.write('{"type":"FeatureCollection","features":[\n')
        first[k] = False
    else:
        out.write(",\n")
    out.write(body)


if __name__ == "__main__":
    raise SystemExit(main())
