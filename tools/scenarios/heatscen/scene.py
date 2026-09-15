import hashlib
import json
import math
from functools import lru_cache
from pathlib import Path
from typing import Any

import numpy as np
import shapely
from pyproj import Transformer
from shapely.geometry import (
    MultiPolygon,
    Polygon,
    mapping,
)
from shapely.geometry.base import BaseGeometry

ROOT = Path(__file__).resolve().parents[3]
ORIGIN_E, ORIGIN_N = 413000.0, 6180000.0
COORD_DIGITS = 9
OKS_SIDE_M = 20.0
TO_WGS = Transformer.from_crs("EPSG:32637", "EPSG:4326", always_xy=True)

Ring = list[tuple[float, float]]


@lru_cache(maxsize=None)
def rules() -> dict[str, Any]:
    return json.loads((ROOT / "rules" / "rules.json").read_text(encoding="utf-8"))


def geojson(geom: BaseGeometry) -> dict[str, Any]:
    """Геометрия в локальных метрах сцены → GeoJSON в EPSG:4326 с девятью знаками."""
    def to_wgs(xy: np.ndarray) -> np.ndarray:
        lon, lat = TO_WGS.transform(xy[:, 0] + ORIGIN_E, xy[:, 1] + ORIGIN_N)
        return np.column_stack([np.round(lon, COORD_DIGITS), np.round(lat, COORD_DIGITS)])

    return mapping(shapely.transform(geom, to_wgs))


def polygon_geom(rings: list[Ring]) -> BaseGeometry:
    polygons = [Polygon(ring) for ring in rings]
    return polygons[0] if len(polygons) == 1 else MultiPolygon(polygons)


class Scene:
    """Сцена в метрах от локального начала (413000, 6180000) EPSG:32637."""

    def __init__(self) -> None:
        self.features: list[dict[str, Any]] = []
        self.source_xy: tuple[float, float] | None = None

    def add(self, geom: BaseGeometry | None, **props: Any) -> dict[str, Any]:
        feature = {"type": "Feature", "geometry": None if geom is None else geojson(geom), "properties": props}
        self.features.append(feature)
        return feature

    def source(self, id: str, x: float, y: float) -> None:
        self.source_xy = (x, y)
        self.add(shapely.Point(x, y), id=id, object_type="source")

    def pipe(self, id: str, points: Ring, dn: int, flow: float, upstream: str) -> None:
        self.add(shapely.LineString(points), id=id, object_type="heat_network", diameter=dn, flow_tph=flow, upstream_object_id=upstream)

    def chamber(self, id: str, x: float, y: float, dn: int, upstream: str) -> None:
        self.add(shapely.Point(x, y), id=id, object_type="heat_chamber", diameter=dn, upstream_object_id=upstream)

    def oks(
        self,
        id: str,
        cp: tuple[float, float],
        flow: float,
        polygon: list[Ring] | None = None,
        heat_load: float | None = None,
        away: tuple[float, float] | None = None,
    ) -> str:
        """Перспективный ОКС и его точка подключения; возвращает ID точки подключения `<id>-cp`.

        Без `polygon` строится квадрат 20 м: середина ближней стороны в `cp`, сам квадрат в направлении
        от источника сцены к `cp` или по `away`. Несколько колец в `polygon` дают MultiPolygon.
        """
        if polygon is None:
            if away is not None:
                dx, dy = away
            elif self.source_xy is not None:
                dx, dy = cp[0] - self.source_xy[0], cp[1] - self.source_xy[1]
            else:
                dx, dy = 0.0, 1.0
            norm = math.hypot(dx, dy) or 1.0
            ux, uy = dx / norm, dy / norm
            nx, ny, half = -uy, ux, OKS_SIDE_M / 2
            x, y = cp
            polygon = [[
                (x + nx * half, y + ny * half),
                (x - nx * half, y - ny * half),
                (x - nx * half + ux * OKS_SIDE_M, y - ny * half + uy * OKS_SIDE_M),
                (x + nx * half + ux * OKS_SIDE_M, y + ny * half + uy * OKS_SIDE_M),
            ]]
        props: dict[str, Any] = {"flow_tph": flow}
        if heat_load is not None:
            props["heat_load"] = heat_load
        self.add(polygon_geom(polygon), id=id, object_type="oks_future", **props)
        cp_id = f"{id}-cp"
        self.add(shapely.Point(cp), id=cp_id, object_type="oks_connection_point", oks_id=id)
        return cp_id

    def existing_oks(self, id: str, polygon: list[Ring]) -> None:
        self.add(polygon_geom(polygon), id=id, object_type="oks_existing")

    def restriction(self, id: str, type: str, geom: BaseGeometry) -> None:
        self.add(geom, id=id, object_type="restriction", restriction_type=type)

    def raw(self, feature: dict[str, Any]) -> None:
        self.features.append(feature)

    def to_geojson(self) -> dict[str, Any]:
        return {"type": "FeatureCollection", "features": self.features}

    def scene_hash(self) -> str:
        return hashlib.sha256(json.dumps(self.to_geojson(), sort_keys=True, ensure_ascii=False).encode()).hexdigest()
