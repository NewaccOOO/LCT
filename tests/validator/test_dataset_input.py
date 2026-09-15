import json
from pathlib import Path

from pyproj import Transformer

from heatcheck.model import (
    default_existing_flow,
    load_input,
)

ROOT = Path(__file__).resolve().parents[2]
RULES = json.loads((ROOT / "rules" / "rules.json").read_text(encoding="utf-8"))
TO_LONLAT = Transformer.from_crs("EPSG:32637", "EPSG:4326", always_xy=True)
ORIGIN = (410_000.0, 6_170_000.0)


def at(x: float, y: float) -> list[float]:
    return list(TO_LONLAT.transform(ORIGIN[0] + x, ORIGIN[1] + y))


def feature(geometry: dict, **props) -> dict:
    return {"type": "Feature", "geometry": geometry, "properties": props}


def point(x: float, y: float) -> dict:
    return {"type": "Point", "coordinates": at(x, y)}


def line(*xy: tuple[float, float]) -> dict:
    return {"type": "LineString", "coordinates": [at(x, y) for x, y in xy]}


def square(x0: float, y0: float, x1: float, y1: float) -> dict:
    return {"type": "Polygon", "coordinates": [[at(x0, y0), at(x1, y0), at(x1, y1), at(x0, y1), at(x0, y0)]]}


def dataset() -> dict:
    """Сеть датасета: источник 1, участки 10 → камера 20 → 11 → 12 (второй конец задом наперёд), здания 40–42."""
    return {"type": "FeatureCollection", "features": [
        feature(point(0, 0), id=1, object_type="source"),
        feature(line((0, 0), (100, 0)), id=10, object_type="heat_network", diameter=300),
        feature(point(100.2, 0), id=20, object_type="heat_chamber"),
        feature(line((100, 0), (200, 0)), id=11, object_type="heat_network", diameter=250),
        feature(line((300, 0), (200.3, 0)), id=12, object_type="heat_network", diameter=200),
        feature(square(40, 20, 80, 60), id=40, object_type="restriction", restriction_type="oks"),
        feature(square(140, 20, 180, 60), id=41, object_type="restriction", restriction_type="oks"),
        feature(square(240, 20, 280, 60), id=42, object_type="restriction", restriction_type="oks"),
        feature(point(60, 40), id=30, object_type="oks_connection_point", flow_tph=15.0),
        feature(point(150, 40), id=31, object_type="oks_connection_point", flow_tph=5.0),
        feature(point(170, 40), id=32, object_type="oks_connection_point", flow_tph=7.5),
        feature(point(300, 90), id=50, object_type="restriction", restriction_type="railway"),
    ]}


def test_dataset_format_is_normalized():
    inp = load_input(dataset(), RULES)

    assert [inp.by_id[i].props["upstream_object_id"] for i in ("10", "11", "12")] == ["1", "20", "11"]
    assert inp.upstream_first == {"10": True, "11": True, "12": False}
    assert inp.by_id["10"].props["flow_tph"] == default_existing_flow(RULES, 300) == 274.9
    assert inp.by_id["20"].props["diameter"] == 300
    assert inp.by_id["20"].props["upstream_object_id"] == "10"
    assert sorted(inp.oks) == ["30", "31", "32"]
    assert inp.oks["31"].geom.equals(inp.oks["32"].geom)
    assert inp.by_id["30"].object_type == "oks_connection_point" and inp.by_id["30"].props["oks_id"] == "30"
    assert [f.id for f in inp.of_type("oks_existing")] == ["42"]
    assert [o.feature.id for o in inp.forbid] == ["42", "50"]
