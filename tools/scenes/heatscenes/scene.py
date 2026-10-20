import hashlib
import json
import math
from dataclasses import dataclass, field
from functools import cache
from pathlib import Path
from typing import Any

import numpy as np
import shapely
from heatscenes.world import BUILDINGS, CITY_NAMES, ROOT, Item, World, to_utm, to_wgs
from shapely.geometry import LineString, Point, Polygon, box, mapping, shape
from shapely.geometry.base import BaseGeometry

OUT = ROOT / "data" / "scenes-v5"
ORGANIZERS = ROOT / "data" / "real" / "dataset.geojson"
DISTRICTS = Path(__file__).with_name("districts.geojson")
COORD_DIGITS = 9
CLIP_PAD_M = 40.0
CHAMBER_ON_PIPE_M = 0.5
TRANSIT_END_M = 1.0
COMMON_MUST = {
    "max_turn_deg": 90,
    "max_segments_any": 4,
    "no_cycles": True,
    "no_self_cross": True,
    "flow_is_sum": True,
    "no_turn_in_special": True,
    "dn_valid": True,
    "summary_consistent": True,
    "no_short_reversal_m": 5.0,
    "no_step_m": 10.0,
    "variants": True,
    "final_from_nearest_boundary_m": 1.0,
    "no_crash": True,
}
HARD_FACTOR, HARD_PAD_M = 1.5, 30.0
MAX_POINTS_HARD = 1
TIE_CHAMBER_M = 10.0
MAX_SEGMENTS = 4
POLYGONAL = ("Polygon", "MultiPolygon")
LINEAR = ("LineString", "MultiLineString")

Q_O = {"1-2": (166, 173), "3-4": (91, 97), "5+": (73, 81)}
T_DESIGN = {"moscow": -25.0, "spb": -24.0, None: -25.0}
AREA_SHARE = 0.8
AREA_PER_PERSON_M2 = 30.0
Q_H_PERSON_W = 305.0
C_WATER = 4.187
DT_HEAT = 80.0
DT_HOT_WATER = 40.0
K2_PUBLIC = 0.6


@cache
def rules() -> dict[str, Any]:
    return json.loads((ROOT / "rules" / "rules.json").read_text(encoding="utf-8"))


def dn_for(flow: float, length: float = 0.0) -> int | None:
    for row in rules()["diameters"]:
        if row["capacity_tph"] >= flow and row["max_length_m"] >= length:
            return row["dn"]
    return None


def house_flow(floor_area_m2: float, levels: int, kind: str = "residential", city: str | None = "moscow") -> float:
    row = "1-2" if levels <= 2 else "3-4" if levels <= 4 else "5+"
    low, high = Q_O[row]
    q_o = (low + (high - low) * (-20.0 - T_DESIGN[city]) / 5.0) * floor_area_m2 * levels * AREA_SHARE
    if kind == "public":
        q_v, q_hw = K2_PUBLIC * q_o, 0.1 * q_o
    else:
        q_v, q_hw = 0.0, Q_H_PERSON_W * floor_area_m2 * levels * AREA_SHARE / AREA_PER_PERSON_M2
    g = 3.6 * (q_o + q_v) / (C_WATER * DT_HEAT) + 3.6 * q_hw / (C_WATER * DT_HOT_WATER)
    return round(g / 1000.0, 3)


def rect(cx: float, cy: float, length: float, depth: float, angle_deg: float = 0.0) -> Polygon:
    a = math.radians(angle_deg)
    ux, uy, vx, vy = math.cos(a), math.sin(a), -math.sin(a), math.cos(a)
    hl, hd = length / 2, depth / 2
    return Polygon(
        [
            (cx + sx * hl * ux + sy * hd * vx, cy + sx * hl * uy + sy * hd * vy)
            for sx, sy in ((-1, -1), (1, -1), (1, 1), (-1, 1))
        ]
    )


def polygonal(geom: BaseGeometry) -> BaseGeometry | None:
    parts = [g for g in getattr(geom, "geoms", [geom]) if g.geom_type in POLYGONAL and not g.is_empty]
    if not parts:
        return None
    return shapely.union_all(parts) if len(parts) > 1 else parts[0]


def linear_parts(geom: BaseGeometry) -> list[LineString]:
    merged = shapely.line_merge(geom) if geom.geom_type == "MultiLineString" else geom
    return [g for g in getattr(merged, "geoms", [merged]) if g.geom_type == "LineString" and g.length > 0.5]


def orientation(geom: BaseGeometry) -> float:
    xy = list(geom.minimum_rotated_rectangle.exterior.coords)
    sides = [(math.dist(xy[i], xy[i + 1]), xy[i], xy[i + 1]) for i in range(2)]
    _, a, b = max(sides)
    return math.degrees(math.atan2(b[1] - a[1], b[0] - a[0]))


@dataclass
class Scene:
    id: str
    family: str
    title: str
    description: str
    rules: list[str]
    must: dict[str, Any]
    should: dict[str, Any]
    engineer: str
    city: str | None
    area: Polygon
    items: list[Item]
    edits: list[str] = field(default_factory=list)
    synthetic: bool = False
    raw_features: list[dict[str, Any]] = field(default_factory=list)
    seq: int = 0
    numeric_ids: bool = False
    run_args: list[str] = field(default_factory=list)
    reference: dict[str, Any] = field(default_factory=dict)
    raw_input: dict[str, Any] | None = None
    multipolygon: bool = False
    origin: str = ""
    reference_note: str = ""
    far_wall: set[str] = field(default_factory=set)
    id_map: dict[Any, int] = field(default_factory=dict)

    def of(self, *kinds: str) -> list[Item]:
        return [i for i in self.items if i.kind in kinds]

    def get(self, item_id: Any) -> Item:
        return next(i for i in self.items if i.id == item_id)

    def next_id(self, prefix: str) -> str:
        self.seq += 1
        return f"{prefix}-{self.id.lower()}-{self.seq}"

    def add_house(
        self,
        poly: Polygon,
        levels: int = 14,
        flow: float | None = None,
        kind: str = "residential",
        cp: tuple[float, float] | None = None,
        house_id: str | None = None,
        cp_id: str | None = None,
    ) -> str:
        house_id = house_id or self.next_id("oks")
        cp_id = cp_id or house_id.replace("oks-", "cp-", 1)
        if flow is None:
            flow = organizer_flow(house_id)
        under = [i for i in self.items if i.kind in ("gas_pipeline", "power_cable", "water_supply", "sewer") and i.geom.intersects(poly)]
        if under:
            gone = {id(i) for i in under}
            self.items = [i for i in self.items if id(i) not in gone]
            self.edits.append(f"линии под новым домом убраны: {', '.join(str(i.id) for i in under)}")
        point = Point(cp) if cp else entry_point(poly, self)
        self.items.append(Item({"id": house_id, "object_type": "oks_future", "flow_tph": flow, "levels": levels}, poly))
        self.items.append(Item({"id": cp_id, "object_type": "oks_connection_point", "oks_id": house_id}, point))
        return cp_id

    def add_point(self, cp_id: str, xy: tuple[float, float], flow: float, house_id: str | None = None) -> str:
        self.items.append(
            Item({"id": cp_id, "object_type": "oks_connection_point", "oks_id": house_id, "flow_tph": flow}, Point(xy))
        )
        return cp_id

    def add(self, kind: str, geom: BaseGeometry, item_id: str | None = None, **props: Any) -> str:
        item_id = item_id or self.next_id(kind[:4])
        if kind in ("heat_network", "heat_chamber"):
            base = {"id": item_id, "object_type": kind}
        elif kind in BUILDINGS:
            base = {"id": item_id, "object_type": "oks_existing"}
        else:
            base = {"id": item_id, "object_type": "restriction", "restriction_type": kind}
        self.items.append(Item(base | props, geom, kind=kind if kind in BUILDINGS else ""))
        return item_id

    def drop(self, ids: set[Any]) -> None:
        houses = {i.id for i in self.items if i.id in ids and i.kind == "oks_future"}
        self.items = [i for i in self.items if i.id not in ids and i.props.get("oks_id") not in houses]

    def drop_network(self, zone: BaseGeometry, note: str) -> int:
        pipes = {i.id for i in self.of("heat_network") if i.geom.intersects(zone)}
        chambers = {i.id for i in self.of("heat_chamber") if i.geom.intersects(zone)}
        self.items = [i for i in self.items if i.id not in pipes | chambers]
        self.prune_chambers()
        self.edits.append(f"{note}: убрано участков сети {len(pipes)}, камер {len(chambers)}")
        return len(pipes)

    def keep_network(self, keep: set[Any], note: str) -> None:
        pipes = {i.id for i in self.of("heat_network")} - keep
        self.items = [i for i in self.items if i.id not in pipes]
        self.prune_chambers()
        self.edits.append(f"{note}: убрано участков сети {len(pipes)}")

    def prune_chambers(self) -> None:
        pipes = [i.geom for i in self.of("heat_network")]
        tree = shapely.STRtree(pipes) if pipes else None
        self.items = [
            i
            for i in self.items
            if i.kind != "heat_chamber"
            or (tree is not None and len(tree.query(i.geom, predicate="dwithin", distance=CHAMBER_ON_PIPE_M)))
        ]

    def houses(self) -> list[Item]:
        return self.of("oks_future")

    def points(self) -> list[Item]:
        return self.of("oks_connection_point")

    def flow(self, cp: Item) -> float:
        if "flow_tph" in cp.props:
            return cp.props["flow_tph"]
        return next(h.props["flow_tph"] for h in self.houses() if h.id == cp.props["oks_id"])

    def features(self, numeric_ids: bool = False) -> list[dict[str, Any]]:
        flows = {h.id: h.props["flow_tph"] for h in self.houses()}
        result = []
        for item in self.items:
            p = item.props
            if item.kind in BUILDINGS:
                props = {"id": p["id"], "object_type": "restriction", "restriction_type": "oks"}
                if item.kind == "oks_future":
                    props["_new"] = True
            elif item.kind == "oks_connection_point":
                props = {
                    "id": p["id"],
                    "object_type": "oks_connection_point",
                    "flow_tph": p.get("flow_tph", flows.get(p.get("oks_id"))),
                }
            elif item.kind == "heat_network":
                props = {"id": p["id"], "object_type": "heat_network", "diameter": p["diameter"]}
            elif item.kind == "heat_chamber":
                props = {"id": p["id"], "object_type": "heat_chamber"}
            elif item.kind == "source":
                props = {"id": p["id"], "object_type": "source"}
            else:
                props = {"id": p["id"], "object_type": "restriction", "restriction_type": p["restriction_type"]}
                if "highway" in p:
                    props["highway"] = p["highway"]
            geom = None if item.geom is None else rounded(item.geom)
            if self.multipolygon and geom is not None and geom["type"] == "Polygon":
                geom = {"type": "MultiPolygon", "coordinates": [geom["coordinates"]]}
            result.append({"type": "Feature", "properties": props, "geometry": geom})
        result += self.raw_features
        if numeric_ids:
            self.id_map = renumber(result)
        return result

    def forget_routes(self) -> None:
        self.reference.clear()
        self.far_wall.clear()
        self.__dict__.pop("_tree", None)
        self.__dict__.pop("_specials", None)

    def full_must(self) -> dict[str, Any]:
        if "diagnostic" in self.must:
            return self.must
        must = COMMON_MUST | self.must
        if self.far_wall and must.get("final_from_nearest_boundary_m") == 1.0:
            must["final_from_nearest_boundary_m"] = {"tolerance_m": 1.0, "except": sorted(self.far_wall)}
        if not self.reference_note and len(must.get("connected", [])) <= MAX_POINTS_HARD:
            hard = {}
            for cp in must.get("connected", []):
                row = self.reference.get(str(cp))
                if isinstance(row, dict) and row.get("shortest_m") is not None:
                    hard[cp] = math.ceil(HARD_FACTOR * row["shortest_m"] + HARD_PAD_M)
            if hard:
                must["route_length_max_m"] = hard
        return must

    def source_info(self) -> dict[str, Any]:
        minx, miny, maxx, maxy = self.area.bounds
        w = to_wgs(box(minx, miny, maxx, maxy)).bounds
        if self.raw_input:
            geoms = [shape(f["geometry"]) for f in self.raw_input["features"] if f.get("geometry")]
            w = shapely.total_bounds(geoms)
            x0, y0, x1, y1 = to_utm(box(*w)).bounds
            minx, miny, maxx, maxy = x0, y0, x1, y1
        info = {
            "kind": self.origin or ("synthetic" if self.synthetic else ("osm+edit" if self.edits else "osm")),
            "bbox_wgs84": [round(v, 6) for v in w],
            "size_m": [round(maxx - minx), round(maxy - miny)],
        }
        if self.city:
            info["file"] = f"data/cities/{self.city}/input.geojson"
            info["city"] = district_of(self.city, self.area.centroid)
        if self.edits:
            info["edits"] = self.edits
        return info

    def write(self, root: Path = OUT) -> Path:
        folder = root / self.id
        folder.mkdir(parents=True, exist_ok=True)
        data = self.raw_input or {
            "type": "FeatureCollection",
            "name": self.id,
            "crs": {"type": "name", "properties": {"name": "urn:ogc:def:crs:OGC:1.3:CRS84"}},
            "features": self.features(self.numeric_ids),
        }
        (folder / "input.geojson").write_text(
            json.dumps(data, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8"
        )
        counts: dict[str, int] = {}
        for f in data["features"]:
            p = f["properties"]
            key = p.get("restriction_type") or p.get("object_type") or "?"
            counts[key] = counts.get(key, 0) + 1
        meta = {
            "id": self.id,
            "family": self.family,
            "title": self.title,
            "description": self.description,
            "source": self.source_info(),
            "rules": self.rules,
            "expect": remap({"must": self.full_must(), "should": self.should}, self.id_map)
            | {"engineer": self.engineer},
            "points": [
                {"id": f["properties"]["id"], "flow_tph": f["properties"].get("flow_tph")}
                for f in data["features"]
                if f["properties"].get("object_type") == "oks_connection_point"
            ],
            "reference": remap(self.reference, self.id_map),
            "run_args": self.run_args,
            "objects": dict(sorted(counts.items())),
        }
        (folder / "scene.json").write_text(json.dumps(meta, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        return folder


@cache
def organizer_flows() -> list[float]:
    data = json.loads(ORGANIZERS.read_text(encoding="utf-8"))["features"]
    return sorted(f["properties"]["flow_tph"] for f in data if f["properties"]["object_type"] == "oks_connection_point")


def organizer_flow(key: Any) -> float:
    flows = organizer_flows()
    return flows[int(hashlib.sha1(str(key).encode()).hexdigest(), 16) % len(flows)]


@cache
def districts() -> list[tuple[str, BaseGeometry]]:
    data = json.loads(DISTRICTS.read_text(encoding="utf-8"))["features"]
    return [(f["properties"]["name"], shape(f["geometry"])) for f in data]


def district_of(city: str, point: Point) -> str:
    p = to_wgs(point)
    town = "Москва" if city == "moscow" else "Санкт-Петербург"
    name = next((n for n, g in districts() if g.contains(p)), None)
    return f"{town}, {name}" if name else CITY_NAMES[city]


def rounded(geom: BaseGeometry) -> dict[str, Any]:
    return mapping(shapely.transform(to_wgs(geom), lambda xy: np.round(xy, COORD_DIGITS)))


def renumber(features: list[dict[str, Any]]) -> dict[Any, int]:
    ids: dict[Any, int] = {}
    for f in features:
        ids.setdefault(f["properties"]["id"], len(ids) + 1)
    for f in features:
        f["properties"]["id"] = ids[f["properties"]["id"]]
    return ids


def remap(value: Any, ids: dict[Any, int]) -> Any:
    if isinstance(value, dict):
        return {(str(ids[k]) if k in ids else k): remap(v, ids) for k, v in value.items()}
    if isinstance(value, list):
        return [remap(v, ids) for v in value]
    if isinstance(value, str) and value in ids:
        return ids[value]
    return value


def entry_point(poly: Polygon, scene: Scene) -> Point:
    pipes = [i.geom for i in scene.of("heat_network")]
    inner = poly.buffer(-1.0)
    if inner.is_empty:
        return poly.representative_point()
    if not pipes:
        return inner.representative_point()
    pipe = min(pipes, key=poly.distance)
    wall = shapely.ops.nearest_points(poly.boundary, pipe)[0]
    return shapely.ops.nearest_points(inner.boundary, wall)[0]


def cut(
    world: World,
    center: tuple[float, float],
    half_w: float,
    half_h: float | None = None,
    futures: str | set[Any] = "none",
) -> tuple[Polygon, list[Item]]:
    cx, cy = center
    half_h = half_h or half_w
    area = box(cx - half_w, cy - half_h, cx + half_w, cy + half_h)
    pad = area.buffer(CLIP_PAD_M, join_style="mitre")
    keep_future = (
        (lambda i: True)
        if futures == "all"
        else (lambda i: False)
        if futures == "none"
        else (lambda i: i.id in futures)
    )
    items: list[Item] = []
    houses = set()
    for item in world.near(pad):
        g, k = item.geom, item.kind
        if k == "oks_future":
            if keep_future(item) and g.intersects(area):
                items.append(Item(dict(item.props), g))
                houses.add(item.id)
        elif k == "oks_existing":
            if g.intersects(area):
                items.append(Item(dict(item.props), g))
        elif k == "oks_connection_point":
            continue
        elif g.geom_type in ("Point",):
            if pad.contains(g):
                items.append(Item(dict(item.props), g))
        elif g.geom_type in LINEAR:
            parts = linear_parts(g.intersection(pad))
            for n, part in enumerate(parts):
                props = dict(item.props)
                if len(parts) > 1:
                    props["id"] = f"{item.id}-{n + 1}"
                items.append(Item(props, part))
        else:
            clipped = polygonal(g.intersection(pad)) if not pad.contains(g) else g
            if clipped is not None and clipped.area > 1.0:
                items.append(Item(dict(item.props), clipped))
    for item in items:
        if item.kind == "oks_future":
            item.props["flow_tph"] = organizer_flow(item.id)
    for item in world.of("oks_connection_point"):
        if item.props.get("oks_id") in houses:
            items.append(Item(dict(item.props), item.geom))
    pipes = [i.geom for i in items if i.kind == "heat_network"]
    tree = shapely.STRtree(pipes) if pipes else None
    items = [
        i
        for i in items
        if i.kind != "heat_chamber"
        or (tree is not None and len(tree.query(i.geom, predicate="dwithin", distance=CHAMBER_ON_PIPE_M)))
    ]
    return area, items


def segments_at(scene: Scene, chamber: Item) -> int:
    count = 0
    for pipe in scene.of("heat_network"):
        if pipe.geom.distance(chamber.geom) > CHAMBER_ON_PIPE_M:
            continue
        ends = (Point(pipe.geom.coords[0]), Point(pipe.geom.coords[-1]))
        count += 1 if min(e.distance(chamber.geom) for e in ends) <= TRANSIT_END_M else 2
    return count


def nearest_pipe(scene: Scene, geom: BaseGeometry) -> tuple[Item, float, Point]:
    pipe = min(scene.of("heat_network"), key=lambda p: (p.geom.distance(geom), str(p.id)))
    proj = pipe.geom.interpolate(pipe.geom.project(geom.centroid if geom.geom_type != "Point" else geom))
    return pipe, pipe.geom.distance(geom), proj


def free_chambers(scene: Scene, point: Point, radius: float = TIE_CHAMBER_M) -> list[tuple[float, Item]]:
    found = [(c.geom.distance(point), c) for c in scene.of("heat_chamber") if c.geom.distance(point) <= radius]
    return sorted(
        [(d, c) for d, c in found if segments_at(scene, c) < MAX_SEGMENTS], key=lambda t: (t[0], str(t[1].id))
    )


def crossed(scene: Scene, line: BaseGeometry, kinds: tuple[str, ...]) -> list[Item]:
    return [i for i in scene.items if i.kind in kinds and i.geom is not None and i.geom.intersects(line)]


def obstacles(
    scene: Scene, house_gap: float = 8.0, other_gap: float = 3.0, skip: frozenset = frozenset()
) -> BaseGeometry:
    parts = []
    for i in scene.items:
        if i.geom is None or i.kind in ("oks_connection_point", "heat_chamber") or i.id in skip:
            continue
        if i.kind in BUILDINGS:
            parts.append(i.geom.buffer(house_gap, quad_segs=2))
        elif i.geom.geom_type in POLYGONAL:
            parts.append(i.geom.buffer(other_gap, quad_segs=2))
        elif i.geom.geom_type in LINEAR:
            parts.append(i.geom.buffer(other_gap, quad_segs=2))
    return shapely.union_all(parts) if parts else Polygon()


def free_spot(
    scene: Scene,
    target: tuple[float, float],
    length: float,
    depth: float,
    angle: float,
    blocked: BaseGeometry | None = None,
    radius: float = 120.0,
    step: float = 6.0,
    allow: BaseGeometry | None = None,
) -> Polygon | None:
    blocked = obstacles(scene) if blocked is None else blocked
    tx, ty = target
    best = None
    rings = int(radius / step)
    for r in range(rings + 1):
        for k in range(max(1, 8 * r)):
            a = 2 * math.pi * k / max(1, 8 * r)
            cx, cy = tx + r * step * math.cos(a), ty + r * step * math.sin(a)
            poly = rect(cx, cy, length, depth, angle)
            if not scene.area.buffer(-5).contains(poly):
                continue
            if allow is not None and not allow.contains(poly):
                continue
            if not poly.intersects(blocked):
                best = poly
                break
        if best is not None:
            return best
    return None
