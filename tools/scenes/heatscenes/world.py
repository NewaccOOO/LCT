import json
from dataclasses import dataclass, field
from functools import cache
from pathlib import Path
from typing import Any

import numpy as np
import shapely
from pyproj import Transformer
from shapely import STRtree
from shapely.geometry import shape
from shapely.geometry.base import BaseGeometry

ROOT = Path(__file__).resolve().parents[3]
TO_UTM = Transformer.from_crs("EPSG:4326", "EPSG:32637", always_xy=True)
TO_WGS = Transformer.from_crs("EPSG:32637", "EPSG:4326", always_xy=True)
CITY_FILES = {
    "moscow": ROOT / "data" / "cities" / "moscow" / "input.geojson",
    "spb": ROOT / "data" / "cities" / "spb" / "input.geojson",
}
CITY_NAMES = {"moscow": "Москва, Хорошёво-Мнёвники", "spb": "Санкт-Петербург, Купчино"}
BUILDINGS = ("oks_existing", "oks_future")
FORBID = ("park", "social_area", "prohibited_site", "water", "railway", "metro")
SPECIAL_AREAS = ("road", "tram_tracks")
LINES = ("gas_pipeline", "power_cable", "water_supply", "sewer")


def to_utm(geom: BaseGeometry) -> BaseGeometry:
    return shapely.transform(geom, lambda xy: np.column_stack(TO_UTM.transform(xy[:, 0], xy[:, 1])))


def to_wgs(geom: BaseGeometry) -> BaseGeometry:
    return shapely.transform(geom, lambda xy: np.column_stack(TO_WGS.transform(xy[:, 0], xy[:, 1])))


def kind_of(props: dict[str, Any]) -> str:
    return props.get("restriction_type") or props["object_type"]


@dataclass
class Item:
    props: dict[str, Any]
    geom: BaseGeometry | None
    kind: str = ""

    def __post_init__(self) -> None:
        self.kind = self.kind or kind_of(self.props)

    @property
    def id(self) -> Any:
        return self.props["id"]


@dataclass
class World:
    city: str
    items: list[Item]
    tree: STRtree = field(init=False)
    by_kind: dict[str, list[int]] = field(init=False)
    flows: dict[str, float] = field(init=False)

    def __post_init__(self) -> None:
        self.tree = STRtree([i.geom for i in self.items])
        self.by_kind = {}
        for n, item in enumerate(self.items):
            self.by_kind.setdefault(item.kind, []).append(n)
        self.flows = {i.props["id"]: i.props["flow_tph"] for i in self.items if i.kind == "oks_future"}

    def of(self, *kinds: str) -> list[Item]:
        return [self.items[n] for k in kinds for n in self.by_kind.get(k, [])]

    def near(self, geom: BaseGeometry, *kinds: str, distance: float = 0.0) -> list[Item]:
        found = (
            self.tree.query(geom, predicate="dwithin", distance=distance)
            if distance
            else self.tree.query(geom, predicate="intersects")
        )
        result = [self.items[n] for n in sorted(found)]
        return [i for i in result if not kinds or i.kind in kinds]


@cache
def world(city: str) -> World:
    raw = json.loads(CITY_FILES[city].read_text(encoding="utf-8"))["features"]
    geoms = to_utm(np.array([shape(f["geometry"]) for f in raw], dtype=object))
    return World(
        city,
        [
            Item(f["properties"], g)
            for f, g in zip(raw, geoms, strict=True)
            if f["properties"]["object_type"] != "source"
        ],
    )
