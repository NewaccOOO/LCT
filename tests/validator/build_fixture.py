"""Строит fixtures/input.geojson и fixtures/output.geojson: геометрия задана в метрах EPSG:32637, числа считаются из неё и rules/rules.json.

Запуск из корня репозитория: uv run --project tools python tests/validator/build_fixture.py
"""

import json
from pathlib import Path

from pyproj import Transformer
from shapely.geometry import LineString

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = Path(__file__).resolve().parent / "fixtures"
RULES = json.loads((ROOT / "rules" / "rules.json").read_text(encoding="utf-8"))
ORIGIN_E, ORIGIN_N = 413000.0, 6180000.0
TO_WGS = Transformer.from_crs("EPSG:32637", "EPSG:4326", always_xy=True)
TO_UTM = Transformer.from_crs("EPSG:4326", "EPSG:32637", always_xy=True)

OKS_FLOW_TPH = 5.0
NETWORKS = {
    "net_1": {"coords": [(0, 0), (200, 0)], "diameter": 100, "flow_tph": 15.0, "upstream_object_id": "src"},
    # Координаты net_2 записаны от конца, дальнего от источника: валидатор не должен полагаться на ориентацию (A-9).
    "net_2": {"coords": [(400, 0), (200, 0)], "diameter": 80, "flow_tph": 12.0, "upstream_object_id": "ch_1"},
}
CHAMBERS = {
    "ch_1": {"xy": (200, 0), "diameter": 100, "upstream_object_id": "net_1"},
    "ch_2": {"xy": (400, 0), "diameter": 80, "upstream_object_id": "net_2"},
}
PARK = [(245, 45), (285, 45), (285, 95), (245, 95)]
ROAD = [(330, 60), (480, 60), (480, 72), (330, 72)]
CONNECTION_POINT = (320, 150)


def wgs(coords: list[tuple[float, float]]) -> list[list[float]]:
    result = []
    for x, y in coords:
        lon, lat = TO_WGS.transform(ORIGIN_E + x, ORIGIN_N + y)
        result.append([round(lon, 9), round(lat, 9)])
    return result


def point(xy: tuple[float, float]) -> dict:
    return {"type": "Point", "coordinates": wgs([xy])[0]}


def line(coords: list[tuple[float, float]]) -> dict:
    return {"type": "LineString", "coordinates": wgs(coords)}


def polygon(coords: list[tuple[float, float]]) -> dict:
    return {"type": "Polygon", "coordinates": [wgs(coords + coords[:1])]}


def length_m(geometry: dict) -> float:
    return round(LineString([TO_UTM.transform(lon, lat) for lon, lat in geometry["coordinates"]]).length, 2)


def feature(geometry: dict | None, **props) -> dict:
    return {"type": "Feature", "geometry": geometry, "properties": props}


def row(dn: int) -> dict:
    return next(d for d in RULES["diameters"] if d["dn"] == dn)


def diameter_for(flow: float) -> int:
    return next(d["dn"] for d in RULES["diameters"] if d["capacity_tph"] >= flow)


def chamber_cost(dn: int) -> float:
    return next(c["cost"] for c in RULES["chamber_cost"] if c["dn_min"] <= dn <= c["dn_max"])


def build_input() -> dict:
    features = [feature(point((0, 0)), id="src", object_type="source")]
    for network_id, net in NETWORKS.items():
        props = {k: v for k, v in net.items() if k != "coords"}
        features.append(feature(line(net["coords"]), id=network_id, object_type="heat_network", **props))
    for chamber_id, chamber in CHAMBERS.items():
        props = {k: v for k, v in chamber.items() if k != "xy"}
        features.append(feature(point(chamber["xy"]), id=chamber_id, object_type="heat_chamber", **props))
    oks_ring = [(300, 150), (340, 150), (340, 190), (300, 190)]
    features += [
        feature(polygon(oks_ring), id="oks_1", object_type="oks_future", flow_tph=OKS_FLOW_TPH, heat_load=3.1),
        feature(point(CONNECTION_POINT), id="cp_1", object_type="oks_connection_point", oks_id="oks_1"),
        feature(polygon(PARK), id="park_1", object_type="restriction", restriction_type="park"),
        feature(polygon(ROAD), id="road_1", object_type="restriction", restriction_type="road"),
    ]
    return {"type": "FeatureCollection", "features": features}


def segment(start: str, end: str, coords: list[tuple[float, float]], laying_method: str = "base", k: float = 1.0) -> dict:
    geometry = line(coords)
    dn = diameter_for(OKS_FLOW_TPH)
    length = length_m(geometry)
    return feature(
        geometry, object_type="heat_network", start_node_id=start, end_node_id=end, flow_tph=OKS_FLOW_TPH,
        diameter=dn, length=length, laying_method=laying_method, depth_start=None, depth_end=None,
        cost=round(length * row(dn)["new_rub_m"] * k, 2),
    )


def tie_in(chamber_id: str, required_diameter: int) -> dict:
    # required_diameter — диаметр нового участка от врезки (ТП §10.2), а не камеры после подключения.
    chamber = CHAMBERS[chamber_id]
    return feature(
        point(chamber["xy"]), object_type="tie_in", existing_object_id=chamber_id, existing_object_type="heat_chamber",
        existing_diameter=chamber["diameter"], required_diameter=required_diameter, cost=RULES["tie_in_cost"],
    )


def via_chamber_1() -> dict[str, list[dict]]:
    # Добавка идёт от ch_1 в net_1: 15 + 5 = 20 т/ч, Ду 100 хватает, реконструкции нет.
    net_1 = NETWORKS["net_1"]
    assert diameter_for(net_1["flow_tph"] + OKS_FLOW_TPH) <= net_1["diameter"]
    return {
        "tie": [tie_in("ch_1", diameter_for(OKS_FLOW_TPH))],
        # изломы 45° и 45°: протокол 16.09.2026 п. 9, нестандартный угол дороже в полтора раза
        "seg": [segment("tie_1", "cp_1", [(200, 0), (200, 60), (290, 150), CONNECTION_POINT])],
    }


def via_chamber_2() -> dict[str, list[dict]]:
    # Добавка идёт от ch_2 в net_2: 12 + 5 = 17 т/ч больше 13,2 у Ду 80, net_2 реконструируется до Ду 100.
    net_2 = NETWORKS["net_2"]
    added_dn = diameter_for(net_2["flow_tph"] + OKS_FLOW_TPH)
    assert added_dn > net_2["diameter"]
    assert diameter_for(NETWORKS["net_1"]["flow_tph"] + OKS_FLOW_TPH) <= NETWORKS["net_1"]["diameter"]
    road = RULES["restrictions"]["road"]
    low_y, high_y = ROAD[0][1] - road["margin_m"], ROAD[2][1] + road["margin_m"]
    recon_geometry = line(net_2["coords"])
    recon_length = length_m(recon_geometry)
    required = max(diameter_for(OKS_FLOW_TPH), added_dn)
    assert required > CHAMBERS["ch_2"]["diameter"]
    return {
        "tie": [tie_in("ch_2", diameter_for(OKS_FLOW_TPH))],
        "node": [feature(point((400, low_y)), object_type="technical_node"), feature(point((400, high_y)), object_type="technical_node")],
        "seg": [
            segment("tie_1", "node_1", [(400, 0), (400, low_y)]),
            segment("node_1", "node_2", [(400, low_y), (400, high_y)], "special", road["k_special"]),
            segment("node_2", "cp_1", [(400, high_y), (400, 110), (360, 150), CONNECTION_POINT]),  # изломы 45° и 45°
        ],
        "recon": [feature(
            recon_geometry, object_type="heat_network_reconstruction", existing_object_id="net_2",
            existing_flow_tph=net_2["flow_tph"], added_flow_tph=OKS_FLOW_TPH, calculated_flow_tph=net_2["flow_tph"] + OKS_FLOW_TPH,
            existing_diameter=net_2["diameter"], required_diameter=added_dn, length=recon_length,
            cost=round(recon_length * row(added_dn)["recon_rub_m"], 2),
        )],
        "chrecon": [feature(
            point(CHAMBERS["ch_2"]["xy"]), object_type="heat_chamber_reconstruction", existing_object_id="ch_2",
            existing_diameter=CHAMBERS["ch_2"]["diameter"], required_diameter=required, cost=chamber_cost(required),
        )],
    }


def summary(objects: dict[str, list[dict]]) -> dict:
    def total(kind: str, name: str) -> float:
        return round(sum(f["properties"][name] for f in objects.get(kind, [])), 2)

    props = {
        "construction_cost": total("seg", "cost"),
        "chamber_construction_cost": total("ch", "cost"),
        "tie_in_cost": total("tie", "cost"),
        "reconstruction_cost": total("recon", "cost"),
        "chamber_reconstruction_cost": total("chrecon", "cost"),
        "unconnected_penalty": 0.0,
    }
    props["calculated_cost"] = round(sum(props.values()), 2)
    props["new_network_length"] = total("seg", "length")
    props["reconstruction_length"] = total("recon", "length")
    props["length"] = round(props["new_network_length"] + props["reconstruction_length"], 2)
    score = RULES["score"]
    props["score"] = round(
        score["w_cost"] * props["calculated_cost"] / score["cost_base"] + score["w_length"] * props["length"] / score["length_base_m"], 3,
    )
    props["unconnected_oks_ids"] = []
    return props


def build_output() -> dict:
    candidates = sorted((via_chamber_1(), via_chamber_2()), key=lambda objects: summary(objects)["score"])
    features = []
    for rank, objects in enumerate(candidates, start=1):
        variant = str(rank)
        prefix = f"v{variant}_"
        for kind, items in objects.items():
            for n, item in enumerate(items, start=1):
                props = item["properties"]
                props.update(id=f"{prefix}{kind}_{n}", variant_id=variant)
                for key in ("start_node_id", "end_node_id"):
                    if key in props and not props[key].startswith("cp_"):
                        props[key] = prefix + props[key]
        head = {"object_type": "variant_summary", "variant_id": variant, "rank": rank}
        props = {"id": f"summary_{variant}"} | head | summary(objects)
        for items in objects.values():
            features += [feature(item["geometry"], **order(item["properties"])) for item in items]
        features.append(feature(None, **props))
    return {"type": "FeatureCollection", "features": features}


def order(props: dict) -> dict:
    head = {key: props[key] for key in ("id", "object_type", "variant_id")}
    return head | {k: v for k, v in props.items() if k not in head}


def main() -> None:
    FIXTURES.mkdir(exist_ok=True)
    for name, data in (("input.geojson", build_input()), ("output.geojson", build_output())):
        (FIXTURES / name).write_text(json.dumps(data, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
