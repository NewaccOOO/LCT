from shapely.geometry import (
    LineString,
    box,
)

from heatcheck.network import overlaps_zone


def test_edges_touching_two_zones_do_not_overlap():
    line = LineString([(0, 0), (10, 0)])
    zones = box(-1, -1, 0.06, 1).union(box(9.94, -1, 11, 1))
    assert not overlaps_zone(line, zones)
    assert overlaps_zone(line, box(2, -1, 4, 1))


def test_overlap_survives_extra_touch_point():
    line = LineString([(0, 0), (10, 0), (10, 5)])
    assert overlaps_zone(line, box(2, -1, 4, 1).union(box(10, 5, 11, 6)))
