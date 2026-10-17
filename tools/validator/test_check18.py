import os
import sys

from shapely.geometry import Point, Polygon

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
