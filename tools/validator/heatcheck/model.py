from dataclasses import (
    dataclass,
    field,
)
from typing import (
    Any,
    NamedTuple,
)

import numpy as np
import shapely
from pyproj import Transformer
from shapely import STRtree
from shapely.geometry import (
    Point,
    shape,
)
from shapely.geometry.base import BaseGeometry

NODE_TOL_M = 0.05
EXISTING_TOL_M = 0.5
LENGTH_TOL_M = 0.05
COST_TOL_RUB = 1.0
FLOW_TOL_TPH = 0.001
SCORE_TOL = 0.001
DIST_EPS_M = 0.001
TOUCH_TOL_M = 0.001
MIN_TURN_DEG = 3.0
MIN_SUBSEGMENT_M = 1.0
VARIANT_TIE_DIST_M = 20.0
JOINT_M = 0.5
BUILDING = "oks"

TO_UTM = Transformer.from_crs("EPSG:4326", "EPSG:32637", always_xy=True)


class Violation(NamedTuple):
    variant_id: str
    object_id: str
    message: str


class RuleResult(NamedTuple):
    violations: list[Violation]
    checked: int


@dataclass
class Feature:
    id: str
    object_type: str
    props: dict[str, Any]
    geom: BaseGeometry | None


class Obstacle(NamedTuple):
    feature: Feature
    restriction_type: str
    params: dict[str, Any]


@dataclass
class Input:
    by_id: dict[str, Feature]
    by_type: dict[str, list[Feature]]
    forbid: list[Obstacle]
    special: list[Obstacle]
    upstream_first: dict[str, bool]
    chamber_links: dict[str, list[Feature]]
    oks: dict[str, Feature]

    def of_type(self, object_type: str) -> list[Feature]:
        return self.by_type.get(object_type, [])


@dataclass
class Variant:
    id: str
    segments: list[Feature] = field(default_factory=list)
    tie_ins: list[Feature] = field(default_factory=list)
    recons: list[Feature] = field(default_factory=list)
    chambers: list[Feature] = field(default_factory=list)
    chamber_recons: list[Feature] = field(default_factory=list)
    nodes: list[Feature] = field(default_factory=list)
    summaries: list[Feature] = field(default_factory=list)


@dataclass
class Output:
    raw: Any
    features: list[Feature]
    variants: dict[str, Variant]


VARIANT_LISTS = {
    "heat_network": "segments",
    "tie_in": "tie_ins",
    "heat_network_reconstruction": "recons",
    "heat_chamber": "chambers",
    "heat_chamber_reconstruction": "chamber_recons",
    "technical_node": "nodes",
    "variant_summary": "summaries",
}


def to_utm(geom: BaseGeometry) -> BaseGeometry:
    return shapely.transform(geom, lambda xy: np.column_stack(TO_UTM.transform(xy[:, 0], xy[:, 1])))


def parse_feature(raw: Any) -> Feature:
    raw = raw if isinstance(raw, dict) else {}
    props = raw.get("properties")
    props = props if isinstance(props, dict) else {}
    geom = None
    if raw.get("geometry") is not None:
        try:
            geom = to_utm(shape(raw["geometry"]))
        except Exception:  # геометрия приходит извне и может быть любой; её разбирает правило schema
            geom = None
    return Feature(str(props.get("id")), str(props.get("object_type")), props, geom)


def raw_features(data: Any) -> list[Any]:
    features = data.get("features") if isinstance(data, dict) else None
    return features if isinstance(features, list) else []


def default_existing_flow(rules: dict[str, Any], dn: Any) -> float | None:
    """Текущий расход участка без flow_tph: cap(Ду-1) + share × (cap(Ду) − cap(Ду-1)), docs/interpretation.md."""
    share = rules.get("existing_flow", {}).get("share", 0.0)
    previous = 0.0
    for row in rules["diameters"]:
        if row["dn"] == dn:
            return previous + share * (row["capacity_tph"] - previous)
        previous = row["capacity_tph"]
    return None


def endpoint(network: Feature, k: int) -> Point:
    return Point(network.geom.coords[0 if k == 0 else -1])


def infer_network(features: list[Feature], rules: dict[str, Any]) -> None:
    """Направление сети обходом от источника по стыкам, диаметры камер и текущий расход там, где их нет во входе."""
    source = next((f for f in features if f.object_type == "source" and f.geom is not None), None)
    pipes = [f for f in features if f.object_type == "heat_network" and f.geom is not None]
    chambers = [f for f in features if f.object_type == "heat_chamber" and f.geom is not None]
    ends = [(i, k, endpoint(pipe, k)) for i, pipe in enumerate(pipes) for k in (0, 1)]

    def near(at: BaseGeometry) -> list[tuple[int, int]]:
        return [(i, k) for i, k, end in ends if end.distance(at) <= JOINT_M]

    if source is not None and any(f.props.get("upstream_object_id") is None for f in pipes + chambers):
        # ponytail: перебор концов O(участков²), STRtree, если сеть во входе вырастет до тысяч участков
        reached: set[int] = set()
        queue: list[tuple[int, int]] = []

        def reach(i: int, k: int, upstream: str) -> None:
            if i in reached:
                return
            reached.add(i)
            if pipes[i].props.get("upstream_object_id") is None:
                pipes[i].props["upstream_object_id"] = upstream
            queue.append((i, k))

        for i, k in near(source.geom):
            reach(i, k, source.id)
        for i, k in queue:
            far = endpoint(pipes[i], 1 - k)
            chamber = next((c for c in chambers if c.geom.distance(far) <= JOINT_M), None)
            if chamber is not None and chamber.props.get("upstream_object_id") is None:
                chamber.props["upstream_object_id"] = pipes[i].id
            for j, kk in near(far):
                reach(j, kk, chamber.id if chamber is not None else pipes[i].id)
    for pipe in pipes:
        if pipe.props.get("flow_tph") is None:
            pipe.props["flow_tph"] = default_existing_flow(rules, pipe.props.get("diameter"))
    for chamber in chambers:
        if chamber.props.get("diameter") is None:
            dns = [pipes[i].props.get("diameter") for i, _ in near(chamber.geom)]
            chamber.props["diameter"] = max((dn for dn in dns if isinstance(dn, int)), default=None)


def infer_consumers(features: list[Feature]) -> list[Feature]:
    """Точки подключения датасета становятся перспективными ОКС с тем же id, здание с точкой внутри — их геометрией."""
    buildings = [f for f in features if f.object_type == "restriction" and f.props.get("restriction_type") == BUILDING and f.geom is not None]
    points = [f for f in features if f.object_type == "oks_connection_point" and f.props.get("oks_id") is None
              and f.props.get("flow_tph") is not None and f.geom is not None]
    tree = STRtree([b.geom for b in buildings])
    future: set[int] = set()
    oks = []
    for point in points:
        building = min((int(i) for i in tree.query(point.geom, predicate="covered_by")), default=None)
        geom = point.geom if building is None else buildings[building].geom
        if building is not None:
            future.add(building)
        oks.append(Feature(point.id, "oks_future", {"id": point.props["id"], "object_type": "oks_future", "flow_tph": point.props["flow_tph"]}, geom))
        point.props["oks_id"] = point.id
    for building in buildings:
        building.object_type = building.props["object_type"] = "oks_existing"
    taken = {id(buildings[i]) for i in future}
    return [f for f in features if id(f) not in taken] + oks


def load_input(data: Any, rules: dict[str, Any]) -> Input:
    features = [parse_feature(raw) for raw in raw_features(data)]
    for feature in features:
        feature.props = dict(feature.props)
    infer_network(features, rules)
    features = infer_consumers(features)
    by_id = {}
    by_type: dict[str, list[Feature]] = {}
    for feature in features:
        # перспективный ОКС датасета делит id со своей точкой подключения, в by_id остаётся точка
        if feature.id not in by_id or feature.object_type != "oks_future":
            by_id[feature.id] = feature
        by_type.setdefault(feature.object_type, []).append(feature)

    restriction_rules = rules["restrictions"]
    forbid = [Obstacle(f, "oks_existing", restriction_rules["oks_existing"]) for f in by_type.get("oks_existing", [])]
    special = [Obstacle(f, "heat_network", restriction_rules["heat_network"]) for f in by_type.get("heat_network", [])]
    for feature in by_type.get("restriction", []):
        if feature.geom is None:
            continue
        restriction_type = str(feature.props.get("restriction_type"))
        params = restriction_rules.get(restriction_type, restriction_rules["_fallback"])
        # Точку нельзя пересечь специальным участком, её обходят с отступом правила типа.
        target = forbid if params["rule"] == "forbid" or feature.geom.geom_type == "Point" else special
        target.append(Obstacle(feature, restriction_type, params))

    networks = [f for f in by_type.get("heat_network", []) if f.geom is not None]
    upstream_first = {}
    for network in networks:
        upstream = by_id.get(str(network.props.get("upstream_object_id")))
        first, last = (Point(c) for c in (network.geom.coords[0], network.geom.coords[-1]))
        upstream_first[network.id] = upstream is None or upstream.geom is None or (
            first.distance(upstream.geom) <= last.distance(upstream.geom)
        )

    chamber_links: dict[str, list[Feature]] = {}
    tree = STRtree([n.geom for n in networks])
    for chamber in by_type.get("heat_chamber", []):
        if chamber.geom is None:
            continue
        links = []
        for index in tree.query(chamber.geom, predicate="dwithin", distance=EXISTING_TOL_M):
            network = networks[index]
            ends = (Point(network.geom.coords[0]), Point(network.geom.coords[-1]))
            if min(end.distance(chamber.geom) for end in ends) <= EXISTING_TOL_M:
                links.append(network)
        chamber_links[chamber.id] = links
    oks = {f.id: f for f in by_type.get("oks_future", [])}
    return Input(by_id, by_type, forbid, special, upstream_first, chamber_links, oks)


def load_output(data: Any) -> Output:
    features = [parse_feature(raw) for raw in raw_features(data)]
    variants: dict[str, Variant] = {}
    for feature in features:
        attr = VARIANT_LISTS.get(feature.object_type)
        if attr is None:
            continue
        variant_id = str(feature.props.get("variant_id"))
        variant = variants.setdefault(variant_id, Variant(variant_id))
        getattr(variant, attr).append(feature)
    return Output(data, features, dict(sorted(variants.items())))


def diameter_row(rules: dict[str, Any], dn: Any) -> dict[str, Any] | None:
    return next((d for d in rules["diameters"] if d["dn"] == dn), None)


def diameter_for(rules: dict[str, Any], flow: float) -> int | None:
    return next((d["dn"] for d in rules["diameters"] if d["capacity_tph"] >= flow), None)


def next_diameter(rules: dict[str, Any], dn: int) -> int | None:
    dns = [d["dn"] for d in rules["diameters"]]
    index = dns.index(dn) + 1
    return dns[index] if index < len(dns) else None


def chamber_cost(rules: dict[str, Any], dn: Any) -> float | None:
    if not isinstance(dn, int):
        return None
    return next((c["cost"] for c in rules["chamber_cost"] if c["dn_min"] <= dn <= c["dn_max"]), None)


def required_offset(rules: dict[str, Any], obstacle: Obstacle, dn: int) -> float:
    params = obstacle.params
    clearance = params["clearance_m"]
    if isinstance(clearance, list):
        clearance = next(tier["m"] for tier in clearance if dn <= tier["dn_max"])
    offset = clearance + diameter_row(rules, dn)["width_m"] / 2
    if obstacle.restriction_type == "heat_network":
        existing = diameter_row(rules, obstacle.feature.props.get("diameter"))
        return offset + (existing["width_m"] / 2 if existing else 0)
    return offset + params.get("half_width_m", 0)


def max_offset(rules: dict[str, Any]) -> float:
    widest = max(d["width_m"] for d in rules["diameters"])
    clearances = []
    for params in rules["restrictions"].values():
        value = params["clearance_m"]
        clearances += [t["m"] for t in value] if isinstance(value, list) else [value]
    return max(clearances) + widest + max(p.get("half_width_m", 0) for p in rules["restrictions"].values())
