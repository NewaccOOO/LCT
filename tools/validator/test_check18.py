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
