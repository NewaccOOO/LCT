import math
from dataclasses import dataclass, field
from functools import cache
from typing import Any

import shapely
from heatscenes.scene import house_flow, orientation, rect
from heatscenes.world import BUILDINGS, FORBID, LINES, SPECIAL_AREAS, Item, world
from shapely.geometry import LineString, Point, Polygon
from shapely.ops import nearest_points

GARAGES = {"garages", "garage"}
MODERN_LEVELS = 17
MIN_GARAGE_M2 = 400.0
CROSS_KINDS = FORBID + SPECIAL_AREAS + LINES + BUILDINGS + ("heat_network",)


@dataclass
class Candidate:
    city: str
    id: str
    house: Polygon
    levels: int
    origin: str
    replaces: list[Any] = field(default_factory=list)
    flow: float = 0.0
    cp: Point | None = None
    pipe: dict[str, Item] = field(default_factory=dict)
    dist: dict[str, float] = field(default_factory=dict)
    tie: dict[str, Point] = field(default_factory=dict)
    crosses: dict[str, list[Item]] = field(default_factory=dict)

    def kinds(self, mode: str) -> set[str]:
        return {i.kind for i in self.crosses[mode]}

    def count(self, mode: str, kind: str) -> int:
        return sum(1 for i in self.crosses[mode] if i.kind == kind)


def pipes_of(city: str, mode: str) -> list[Item]:
    pipes = world(city).of("heat_network")
    return pipes if mode == "all" else [p for p in pipes if p.props.get("_source") in ("restored", "real")]


@cache
def pipe_tree(city: str, mode: str) -> tuple[shapely.STRtree, list[Item]]:
    pipes = pipes_of(city, mode)
    return shapely.STRtree([p.geom for p in pipes]), pipes


def garage_house(item: Item) -> Polygon | None:
    rr = item.geom.minimum_rotated_rectangle
    xy = list(rr.exterior.coords)
    sides = sorted(math.dist(xy[i], xy[i + 1]) for i in range(2))
    depth, length = min(sides[0], 18.0), min(sides[1], 80.0)
    if depth < 12.0 or length < 24.0:
        return None
    c = item.geom.centroid
    return rect(c.x, c.y, length, depth, orientation(item.geom))


@cache
def candidates(city: str) -> list[Candidate]:
    w = world(city)
    result = []
    for f in w.of("oks_future"):
        if f.geom.geom_type != "Polygon":
            continue
        result.append(Candidate(city, f.id, f.geom, f.props.get("levels", 14), "future", flow=f.props["flow_tph"]))
    for g in w.of("oks_existing"):
        if g.props.get("building") not in GARAGES or g.geom.area < MIN_GARAGE_M2:
            continue
        house = garage_house(g)
        if house is None:
            continue
        others = [i for i in w.near(house, *BUILDINGS, distance=6.0) if i.id != g.id]
        if others:
            continue
        result.append(
            Candidate(
                city,
                f"g-{g.id}",
                house,
                MODERN_LEVELS,
                "garage",
                [g.id],
                flow=house_flow(house.area, MODERN_LEVELS, city=city),
            )
        )
    for c in result:
        analyse(c)
    return sorted(result, key=lambda c: c.id)


def analyse(c: Candidate) -> None:
    w = world(c.city)
    for mode in ("all", "restored"):
        tree, pipes = pipe_tree(c.city, mode)
        if not pipes:
            continue
        n = int(tree.query_nearest(c.house)[0])
        pipe = pipes[n]
        a, b = nearest_points(c.house, pipe.geom)
        c.pipe[mode], c.dist[mode], c.tie[mode] = pipe, a.distance(b), b
        line = LineString([a, b])
        c.crosses[mode] = [
            i
            for i in w.near(line, *CROSS_KINDS)
            if i.id not in c.replaces
            and not (i.kind == "heat_network" and i.id == pipe.id)
            and not (i.kind in BUILDINGS and i.geom.intersects(c.house))
        ]
