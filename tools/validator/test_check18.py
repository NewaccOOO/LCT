import math
import os
import sys

import pytest
from shapely.geometry import LineString, Point, Polygon

sys.path.insert(0, os.path.dirname(__file__))
import check18


def test_entry_after_touching_own_wall_again_is_not_allowed():
    own = Polygon([(-5, -3), (9, -3), (8, 0), (7.8, -0.3), (7.8, 3), (-5, 3)])
    cg = Point(0, 0)
    dn = 100
    need = check18.oks_clearance(dn) + check18.DN[dn]["width_m"] / 2
    zone = own.buffer(need - check18.EPS, quad_segs=64)

    why, window = check18.entry_window(cg, Point(8, 0), own, zone, dn, None)

    assert window is None
    assert why == "луч в зоне отступа подходит к другой стене своего здания"


def test_entry_leaving_own_wall_once_is_allowed():
    own = Polygon([(-5, -3), (7.8, -3), (7.8, 3), (-5, 3)])
    cg = Point(0, 0)
    dn = 100
    need = check18.oks_clearance(dn) + check18.DN[dn]["width_m"] / 2
    zone = own.buffer(need - check18.EPS, quad_segs=64)

    why, window = check18.entry_window(cg, Point(7.8, 0), own, zone, dn, None)

    assert why is None
    assert window[2] > 7.8


def test_turn_with_same_side_turn_over_short_link_is_one_turn():
    def path(link):
        a = math.radians(80)
        b = (link * math.cos(a), link * math.sin(a))
        return [(-20.0, 0.0), (0.0, 0.0), b, (b[0] + 20 * math.cos(math.radians(120)), b[1] + 20 * math.sin(math.radians(120)))]

    assert check18.path_turn(path(1.5), 1)[0] == pytest.approx(120)
    assert check18.path_turn(path(2.5), 1)[0] == pytest.approx(80)


def test_corner_entry_only_at_nearest_point_of_adjacent_side():
    own = Polygon([(0, 0), (20, 0), (20, 10), (0, 10)])
    cg = Point(19, 0.5)
    shells = [own.exterior]

    assert check18.corner_entry(LineString([(26, 0.5), (19, 0.5)]), cg, own, shells, 0.5)
    assert not check18.corner_entry(LineString([(26, 3), (19, 0.5)]), cg, own, shells, 0.5)
    assert not check18.corner_entry(LineString([(23, -6), (19, 0.5)]), cg, own, shells, 0.5)


def test_core_types_moves_additional_type_violations_to_info(monkeypatch):
    monkeypatch.setattr(check18, "CORE_TYPES", True)
    monkeypatch.setattr(check18, "EXTRA_TOKENS", {"sewer", "sew-w100"})
    rep = check18.Report()
    rep.add("B10 отступ до линии нарушен", "v1_seg_1 sewer sew-w100 0.5 м")
    rep.add("B10 отступ до линии нарушен", "v1_seg_2 gas_pipeline gas-w7 0.5 м")
    keys = list(rep.count)
    assert any(k.startswith("i  дополнительный тип") for k in keys)
    assert rep.count["B10 отступ до линии нарушен"] == 1


def test_final_piece_through_far_wall_is_noted(tmp_path):
    from pyproj import Transformer

    back = Transformer.from_crs("EPSG:32637", "EPSG:4326", always_xy=True)
    x0, y0 = 410000.0, 6180000.0

    def wgs(*pts):
        return [list(back.transform(x0 + x, y0 + y)) for x, y in pts]

    house = wgs((0, 0), (20, 0), (20, 10), (0, 10), (0, 0))
    inp = {"type": "FeatureCollection", "features": [
        {"type": "Feature", "properties": {"id": "oks-1", "object_type": "restriction", "restriction_type": "oks"},
         "geometry": {"type": "Polygon", "coordinates": [house]}},
        {"type": "Feature", "properties": {"id": "cp-1", "object_type": "oks_connection_point", "flow_tph": 5.0},
         "geometry": {"type": "Point", "coordinates": wgs((10, 1))[0]}},
        {"type": "Feature", "properties": {"id": "hn-1", "object_type": "heat_network", "diameter": 200},
         "geometry": {"type": "LineString", "coordinates": wgs((-50, 40), (70, 40))}},
    ]}
    path = tmp_path / "input.geojson"
    path.write_text(check18.json.dumps(inp))
    loaded = check18.load_input(str(path))
    feats = [
        {"type": "Feature", "properties": {"id": "s1", "object_type": "heat_network", "variant_id": "1",
                                           "start_node_id": "ch-1", "end_node_id": "cp-1", "diameter": 80,
                                           "laying_method": "base", "length": 39.0, "cost": 1.0, "flow_tph": 5.0},
         "geometry": {"type": "LineString", "coordinates": wgs((10, 40), (10, 1))}},
        {"type": "Feature", "properties": {"id": "ch-1", "object_type": "heat_chamber", "variant_id": "1", "diameter": 200, "cost": 1.0},
         "geometry": {"type": "Point", "coordinates": wgs((10, 40))[0]}},
        {"type": "Feature", "properties": {"id": "vs-1", "object_type": "variant_summary", "variant_id": "1",
                                           **{k: 0 for k in check18.SUMMARY}, "rank": 1, "unconnected_oks_ids": []},
         "geometry": None},
    ]
    rep = check18.Report()
    check18.check_variant(loaded, check18.trees_for(loaded), "1", feats, rep)

    assert any("не от ближайшей стены" in key and key.startswith("i ") for key in rep.count)
