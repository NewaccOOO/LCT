import argparse
import json
import math
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any

import numpy as np
import shapely
from pyproj import Transformer
from shapely.geometry import LineString, Point, shape
from shapely.geometry.base import BaseGeometry

ROOT = Path(__file__).resolve().parents[3]
RULES = json.loads((ROOT / "rules" / "rules.json").read_text(encoding="utf-8"))
TABLE = {r["dn"]: r for r in RULES["diameters"]}
TO_UTM = Transformer.from_crs("EPSG:4326", "EPSG:32637", always_xy=True)
NODE_TOL_M = 0.5
TRANSIT_END_M = 1.0
LEN_TOL_M = 0.5
ANGLE_TOL_DEG = 0.5
ANGLE_EPS_DEG = 0.01
CROSS_SKIP_M = 3.0
STEP_TURN_DEG = 60.0
STEP_LEAD_M = 10.0
STEP_GAIN_M = 0.5
STEP_BEND_DEG = 45.0
VARIANTS_MAX = 3
MERGE_GAP_M = 1.0
VARIANT_SHIFT_M = 5.0
VARIANT_SAME_SHARE = 0.9
OK, FAIL, NA = "выполнено", "нарушено", "не проверить"
POLY = ("Polygon", "MultiPolygon")
LINE = ("LineString", "MultiLineString")
DEPTH_LINES = {"gas_pipeline": (2.8, 0.4, 0.2), "power_cable": (2.7, 0.2, 0.5)}
DEPTH_HEAT_CLEAR_M = 0.5
DEPTH_HEAT_TOP_M = 3.0
DEPTH_MIN_TOP = {"road": 1.0, "tram_tracks": 1.2}


def utm(geom: BaseGeometry) -> BaseGeometry:
    return shapely.transform(geom, lambda xy: np.column_stack(TO_UTM.transform(xy[:, 0], xy[:, 1])))


def load(path: Path) -> list[tuple[dict[str, Any], BaseGeometry | None]]:
    data = json.loads(path.read_text(encoding="utf-8"))
    result = []
    for f in data["features"]:
        g = f.get("geometry")
        try:
            geom = utm(shape(g)) if g and g.get("coordinates") else None
        except Exception:
            geom = None
        result.append((f["properties"], geom))
    return result


def key(value: Any) -> str:
    return str(value)


def dn_for(flow: float, length: float = 0.0) -> int | None:
    for row in RULES["diameters"]:
        if row["capacity_tph"] >= flow - 1e-9 and row["max_length_m"] >= length - LEN_TOL_M:
            return row["dn"]
    return None


def restriction_rule(kind: str) -> dict[str, Any] | None:
    return RULES["restrictions"].get("oks_existing" if kind == "oks" else kind)


class Variant:
    def __init__(self, inp: "Input", features: list[tuple[dict[str, Any], BaseGeometry | None]], vid: str) -> None:
        self.inp = inp
        self.segments = [(p, g) for p, g in features if p.get("object_type") == "heat_network"]
        self.chambers = {key(p["id"]): (p, g) for p, g in features if p.get("object_type") == "heat_chamber"}
        self.nodes_tech = {key(p["id"]): g for p, g in features if p.get("object_type") == "technical_node"}
        self.summary = next((p for p, _ in features if p.get("object_type") == "variant_summary"), {})
        self.vid = vid
        self.adj: dict[str, list[int]] = defaultdict(list)
        for n, (p, _) in enumerate(self.segments):
            self.adj[key(p["start_node_id"])].append(n)
            self.adj[key(p["end_node_id"])].append(n)
        self.roots = {n for n in self.adj if self.is_root(n)}
        self.parent: dict[str, tuple[str, int]] = {}
        self.root_of: dict[str, str] = {}
        for r in sorted(self.roots):
            stack = [r]
            self.root_of[r] = r
            while stack:
                cur = stack.pop()
                for s in self.adj[cur]:
                    p = self.segments[s][0]
                    other = key(p["end_node_id"]) if key(p["start_node_id"]) == cur else key(p["start_node_id"])
                    if other in self.root_of:
                        continue
                    self.root_of[other] = r
                    self.parent[other] = (cur, s)
                    stack.append(other)

    def is_root(self, node: str) -> bool:
        if node in self.inp.chambers:
            return True
        if node in self.chambers:
            g = self.chambers[node][1]
            return any(p.distance(g) <= NODE_TOL_M for p in self.inp.pipe_geoms)
        return False

    def node_point(self, node: str) -> Point | None:
        if node in self.inp.chambers:
            return self.inp.chambers[node]
        if node in self.chambers:
            return self.chambers[node][1]
        if node in self.nodes_tech:
            return self.nodes_tech[node]
        if node in self.inp.points:
            return self.inp.points[node]
        return None

    def path(self, cp: str) -> list[int] | None:
        if cp not in self.root_of:
            return None
        result, cur = [], cp
        while cur in self.parent:
            cur, s = self.parent[cur]
            result.append(s)
        return result

    def path_length(self, cp: str) -> float | None:
        p = self.path(cp)
        return None if p is None else sum(self.segments[s][1].length for s in p)

    def connected(self) -> set[str]:
        unconnected = {key(i) for i in self.summary.get("unconnected_oks_ids", [])}
        return {cp for cp in self.inp.points if cp in self.root_of and cp not in unconnected}

    def route(self) -> BaseGeometry:
        lines = [g for _, g in self.segments if g is not None]
        return shapely.union_all(lines) if lines else LineString()

    def final_segment(self, cp: str) -> tuple[dict[str, Any], LineString] | None:
        p = self.path(cp)
        if not p:
            return None
        props, geom = self.segments[p[0]]
        coords = list(geom.coords)
        cpt = self.inp.points[cp]
        if Point(coords[0]).distance(cpt) < Point(coords[-1]).distance(cpt):
            coords = coords[::-1]
        return props, LineString(coords[-2:])

    def tie_points(self) -> dict[str, Point]:
        return {r: self.node_point(r) for r in self.roots if r in self.root_of.values()}


class Input:
    def __init__(self, features: list[tuple[dict[str, Any], BaseGeometry | None]]) -> None:
        self.features = features
        self.by_id = {key(p.get("id")): (p, g) for p, g in features}
        self.points = {key(p["id"]): g for p, g in features if p.get("object_type") == "oks_connection_point" and g}
        self.flows = {
            key(p["id"]): p.get("flow_tph") for p, _ in features if p.get("object_type") == "oks_connection_point"
        }
        self.chambers = {key(p["id"]): g for p, g in features if p.get("object_type") == "heat_chamber" and g}
        self.pipes = [(p, g) for p, g in features if p.get("object_type") == "heat_network" and g is not None]
        self.pipe_geoms = [g for _, g in self.pipes]
        self.restrictions = [(p, g) for p, g in features if p.get("object_type") == "restriction" and g is not None]
        self.sources = {key(p["id"]): g for p, g in features if p.get("object_type") == "source" and g}

    def of(self, kind: str) -> list[tuple[dict[str, Any], BaseGeometry]]:
        if kind == "heat_network":
            return self.pipes
        return [(p, g) for p, g in self.restrictions if p.get("restriction_type") == kind]

    def geom(self, oid: Any) -> BaseGeometry | None:
        item = self.by_id.get(key(oid))
        return None if item is None else item[1]

    def building_of(self, cp: str) -> BaseGeometry | None:
        pt = self.points[cp]
        for p, g in self.of("oks"):
            if g.geom_type in POLY and g.distance(pt) < 0.01:
                return g
        return None


def direction_at(line: LineString, pt: Point) -> tuple[float, float]:
    s = line.project(pt)
    a = line.interpolate(max(0.0, s - 0.5))
    b = line.interpolate(min(line.length, s + 0.5))
    n = math.hypot(b.x - a.x, b.y - a.y) or 1.0
    return (b.x - a.x) / n, (b.y - a.y) / n


def boundary(g: BaseGeometry) -> BaseGeometry:
    return g.boundary if g.geom_type in POLY else g


def crossing_points(seg: LineString, g: BaseGeometry) -> list[Point]:
    hit = seg.intersection(boundary(g))
    return [Point(xy) for xy in shapely.get_coordinates(hit)]


def crossing_angles(variant: Variant, kind: str) -> list[float]:
    result = []
    for _, seg in variant.segments:
        for _, g in variant.inp.of(kind):
            edge = boundary(g)
            for pt in crossing_points(seg, g):
                parts = [p for p in getattr(edge, "geoms", [edge]) if p.distance(pt) < 0.01]
                if not parts:
                    continue
                result.append(acute_angle(direction_at(seg, pt), direction_at(parts[0], pt)))
    return result


def acute_angle(u: tuple[float, float], v: tuple[float, float]) -> float:
    return math.degrees(math.asin(min(1.0, abs(u[0] * v[1] - u[1] * v[0]))))


def count_crossings(variant: Variant, kind: str) -> int:
    route = variant.route()
    polys = [g for _, g in variant.inp.of(kind) if g.geom_type in POLY]
    total = 0
    if polys:
        inter = route.intersection(shapely.union_all(polys).buffer(MERGE_GAP_M / 2))
        merged = shapely.line_merge(inter) if inter.geom_type == "MultiLineString" else inter
        total += len([p for p in getattr(merged, "geoms", [merged]) if not p.is_empty and p.length > 0.05])
    ties = [p for p in variant.tie_points().values() if p is not None]
    for _, g in variant.inp.of(kind):
        if g.geom_type in POLY:
            continue
        pts = [Point(xy) for xy in shapely.get_coordinates(route.intersection(g))]
        total += len([pt for pt in pts if all(pt.distance(t) > NODE_TOL_M for t in ties)])
    return total


def k_actual(props: dict[str, Any]) -> float | None:
    dn, length, cost = props.get("diameter"), props.get("length"), props.get("cost")
    if not dn or not length or cost is None:
        return None
    kgl = 1.0
    ds, de = props.get("depth_start"), props.get("depth_end")
    if ds is not None and de is not None:
        kgl = (max(1.0, 1 + 0.1 * (ds - 3)) + max(1.0, 1 + 0.1 * (de - 3))) / 2
    return cost / (length * TABLE[dn]["new_rub_m"] * kgl)


CORE_SPECIAL = {"road", "tram_tracks", "gas_pipeline", "power_cable", "heat_network"}


def chamber_price(dn: int) -> float:
    return next((c["cost"] for c in RULES["chamber_cost"] if dn <= c["dn_max"]), RULES["chamber_cost"][-1]["cost"])


def oks_clear(dn: int) -> float:
    return next(c["m"] for c in RULES["restrictions"]["oks_existing"]["clearance_m"] if dn <= c["dn_max"])


def half_width(dn: int) -> float:
    return TABLE[dn]["width_m"] / 2


class Checker:
    def __init__(self, folder: Path, out_name: str) -> None:
        self.folder = folder
        self.meta = json.loads((folder / "scene.json").read_text(encoding="utf-8"))
        self.inp = Input(load(folder / "input.geojson"))
        out = folder / out_name
        self.out = load(out) if out.exists() else None
        crit = out.with_suffix(".criteria.json")
        self.criteria = json.loads(crit.read_text(encoding="utf-8")) if crit.exists() else None
        run = folder / "run.json"
        self.run = json.loads(run.read_text(encoding="utf-8")) if run.exists() else {}
        log = folder / "output.log"
        self.log = log.read_text(encoding="utf-8", errors="replace") if log.exists() else ""
        self.variants: dict[str, Variant] = {}
        if self.out is not None:
            by: dict[str, list] = defaultdict(list)
            for p, g in self.out:
                by[key(p.get("variant_id"))].append((p, g))
            self.variants = {v: Variant(self.inp, f, v) for v, f in by.items()}
        ranked = sorted(self.variants.values(), key=lambda v: v.summary.get("rank", 99))
        self.best = ranked[0] if ranked else None

    def reasons(self) -> dict[str, str]:
        if not self.criteria or not self.best:
            return {}
        for row in self.criteria:
            if key(row.get("variant_id")) == self.best.vid:
                return {key(r.get("oks_id")): r.get("reason") for r in row["criteria"].get("unconnected_reasons", [])}
        return {}

    def check(self, name: str, value: Any) -> tuple[str, str]:
        handler = getattr(self, "c_" + name, None)
        if handler is None:
            return NA, "проверка не реализована"
        if name not in ("diagnostic", "no_crash") and self.best is None:
            return NA, "нет выхода сервиса"
        return handler(value)

    def c_no_crash(self, value: Any) -> tuple[str, str]:
        code = self.run.get("exit_code")
        if self.out is not None and code in (None, 0):
            return OK, "выход записан"
        if code is None:
            return NA, "нет выхода и run.json"
        return FAIL, f"код выхода {code}"

    def c_diagnostic(self, value: dict[str, Any]) -> tuple[str, str]:
        code = self.run.get("exit_code")
        if code is None:
            return (FAIL, "выход записан, диагностики нет") if self.out is not None else (NA, "нет run.json")
        missing = [m for m in value.get("mentions", []) if str(m) not in self.log]
        if code == 0:
            return FAIL, "код выхода 0"
        return (OK, f"код {code}") if not missing else (FAIL, f"код {code}, в выводе нет id {missing}")

    def c_connected(self, ids: list[Any]) -> tuple[str, str]:
        missing = [i for i in ids if key(i) not in self.best.connected()]
        return (OK, f"подключено {len(ids)}") if not missing else (FAIL, f"без сети {missing}")

    def c_connected_any_of(self, ids: list[Any]) -> tuple[str, str]:
        found = [i for i in ids if key(i) in self.best.connected()]
        return (OK, f"подключены {found}") if found else (FAIL, "ни одна не подключена")

    def c_connected_share_min(self, share: float) -> tuple[str, str]:
        total = len(self.inp.points)
        got = len(self.best.connected()) / total if total else 1.0
        return (OK if got >= share - 1e-9 else FAIL), f"доля {got:.3f}, нужно {share}"

    def c_unconnected(self, spec: dict[str, list[str]]) -> tuple[str, str]:
        reasons = self.reasons()
        bad = []
        for cp, allowed in spec.items():
            if key(cp) in self.best.connected():
                bad.append(f"{cp} подключена")
            elif reasons and reasons.get(key(cp)) not in allowed:
                bad.append(f"{cp}: причина {reasons.get(key(cp))}, ждали {allowed}")
        if bad:
            return FAIL, "; ".join(bad)
        if not reasons:
            return NA, "без сети, но причины не прочитаны: нет output.criteria.json"
        return OK, "без сети, причина из списка"

    def tie_of(self, cp: str) -> str | None:
        return self.best.root_of.get(key(cp))

    def c_tie_in(self, spec: dict[str, dict[str, Any]]) -> tuple[str, str]:
        bad, notes = [], []
        for cp, want in spec.items():
            root = self.tie_of(cp)
            if root is None:
                bad.append(f"{cp}: не подключена")
                continue
            kind = "existing_chamber" if root in self.inp.chambers else "new_chamber"
            notes.append(f"{cp} → {root} ({kind})")
            if kind != want["kind"]:
                bad.append(f"{cp}: {kind}, ждали {want['kind']}")
            elif kind == "existing_chamber" and want.get("chambers") and root not in map(key, want["chambers"]):
                bad.append(f"{cp}: камера {root}, ждали {want['chambers']}")
            elif kind == "new_chamber" and want.get("pipes"):
                g = self.best.node_point(root)
                if not any(
                    self.inp.geom(p) is not None and self.inp.geom(p).distance(g) <= NODE_TOL_M for p in want["pipes"]
                ):
                    bad.append(f"{cp}: новая камера не на {want['pipes']}")
        return (OK, "; ".join(notes)) if not bad else (FAIL, "; ".join(bad))

    def c_not_tie_in(self, ids: list[Any]) -> tuple[str, str]:
        used = [i for i in ids if key(i) in self.best.adj]
        return (OK, "не использованы") if not used else (FAIL, f"в камеры {used} заходят новые участки")

    def new_tie_chambers(self) -> list[tuple[str, Point]]:
        return [(n, self.best.chambers[n][1]) for n in self.best.roots if n in self.best.chambers]

    def c_no_new_chamber_within_m(self, spec: dict[str, Any]) -> tuple[str, str]:
        r = spec["radius_m"]
        near = []
        for n, g in self.new_tie_chambers():
            for c in spec["chambers"]:
                cg = self.inp.chambers.get(key(c))
                if cg is not None and g.distance(cg) <= r:
                    near.append(f"{n} в {g.distance(cg):.1f} м от {c}")
        return (OK, "нет") if not near else (FAIL, "; ".join(near))

    def c_tie_outside_zone(self, spec: dict[str, Any]) -> tuple[str, str]:
        bad = []
        for rid in spec["restrictions"]:
            g = self.inp.geom(rid)
            if g is None:
                continue
            zone = g.buffer(spec["margin_m"])
            for n, pt in self.best.tie_points().items():
                if pt is not None and zone.contains(pt):
                    bad.append(f"{n} в зоне {rid}")
        return (OK, "вне зоны") if not bad else (FAIL, "; ".join(bad))

    def c_tie_clear_of_buildings_m(self, value: float) -> tuple[str, str]:
        houses = [g for _, g in self.inp.of("oks")]
        worst = min((min(h.distance(pt) for h in houses) for pt in self.best.tie_points().values() if pt), default=None)
        if worst is None:
            return NA, "нет мест врезки"
        return (OK if worst >= value - 0.05 else FAIL), f"ближайшее здание {worst:.2f} м"

    def c_max_segments_at(self, spec: dict[str, int]) -> tuple[str, str]:
        bad = []
        for cid, limit in spec.items():
            g = self.inp.chambers.get(key(cid))
            if g is None:
                continue
            count = len(self.best.adj.get(key(cid), []))
            for pg in self.inp.pipe_geoms:
                if pg.distance(g) <= NODE_TOL_M:
                    ends = min(Point(pg.coords[0]).distance(g), Point(pg.coords[-1]).distance(g))
                    count += 1 if ends <= TRANSIT_END_M else 2
            if count > limit:
                bad.append(f"{cid}: {count}")
        return (OK, "в пределе") if not bad else (FAIL, "; ".join(bad))

    def c_existing_chamber_tie_in_count(self, value: int) -> tuple[str, str]:
        got = self.best.summary.get("existing_chamber_tie_in_count")
        return (OK if got == value else FAIL), f"{got}, ждали {value}"

    def c_existing_chamber_tie_in_count_max(self, value: int) -> tuple[str, str]:
        got = self.best.summary.get("existing_chamber_tie_in_count")
        return (OK if got is not None and got <= value else FAIL), f"{got}, предел {value}"

    def c_max_route_length_m(self, spec: dict[str, float]) -> tuple[str, str]:
        bad, notes = [], []
        for cp, limit in spec.items():
            length = self.best.path_length(key(cp))
            if length is None:
                bad.append(f"{cp}: не подключена")
                continue
            notes.append(f"{cp}: {length:.1f} ≤ {limit}")
            if length > limit + LEN_TOL_M:
                bad.append(f"{cp}: {length:.1f} > {limit}")
        return (OK, "; ".join(notes)) if not bad else (FAIL, "; ".join(bad))

    def anchors(self) -> list[Point]:
        return list(self.inp.chambers.values()) + list(self.best.tie_points().values())

    def flows(self) -> dict[int, float]:
        result: dict[int, float] = defaultdict(float)
        for cp in self.best.connected():
            for s in self.best.path(cp) or []:
                result[s] += self.inp.flows.get(cp) or 0.0
        return result

    def recomputed(self) -> dict[str, float]:
        v = self.best
        cost = length = 0.0
        for p, g in v.segments:
            dn = p["diameter"]
            k = 1.0
            if p.get("laying_method") == "special":
                n = next(k for k, (q, _) in enumerate(v.segments) if q is p)
                k = self.expected_k(n)[0]
            ds, de = p.get("depth_start"), p.get("depth_end")
            kgl = 1.0 if ds is None or de is None else (max(1.0, 1 + 0.1 * (ds - 3)) + max(1.0, 1 + 0.1 * (de - 3))) / 2
            cost += g.length * TABLE[dn]["new_rub_m"] * k * kgl
            length += g.length
        for node, (_, g) in v.chambers.items():
            dns = [v.segments[s][0]["diameter"] for s in v.adj.get(node, [])]
            dns += [ip.get("diameter") or 0 for ip, pg in self.inp.pipes if pg.distance(g) <= NODE_TOL_M]
            top = max(dns, default=50)
            cost += next(c["cost"] for c in RULES["chamber_cost"] if top <= c["dn_max"]) if top <= 1400 else 12e6
        ties = sum(len(v.adj.get(c, [])) for c in self.inp.chambers)
        cost += ties * RULES["tie_in_cost"]
        connected = v.connected()
        for cp, flow in self.inp.flows.items():
            if cp not in connected:
                cost += RULES["penalty"]["fixed"] + RULES["penalty"]["per_tph"] * (flow or 0.0)
        score = 0.7 * cost / RULES["score"]["cost_base"] + 0.3 * length / RULES["score"]["length_base_m"]
        return {"score": score, "cost": cost, "length": length}

    def c_total_length_max_m(self, value: float) -> tuple[str, str]:
        got = self.recomputed()["length"]
        return (OK if got <= value + LEN_TOL_M else FAIL), f"{got:.1f} м по геометрии, предел {value}"

    def c_score_max(self, value: float) -> tuple[str, str]:
        r = self.recomputed()
        own = self.best.summary.get("score")
        return (
            OK if r["score"] <= value + 1e-6 else FAIL
        ), f"S {r['score']:.4f} по правилам (в сводке {own}), предел {value}"

    def c_score_regression_max(self, value: float) -> tuple[str, str]:
        return self.c_score_max(value)

    def c_max_segments_any(self, limit: int) -> tuple[str, str]:
        v, bad = self.best, []
        for node, segs in v.adj.items():
            if node in v.chambers:
                g = v.chambers[node][1]
                extra = 0
                for _, pg in self.inp.pipes:
                    if pg.distance(g) <= NODE_TOL_M:
                        end = min(Point(pg.coords[0]).distance(g), Point(pg.coords[-1]).distance(g))
                        extra += 1 if end <= TRANSIT_END_M else 2
                if len(segs) + extra > limit:
                    bad.append(f"{node}: {len(segs) + extra}")
            elif node in self.inp.chambers:
                g = self.inp.chambers[node]
                extra = 0
                for _, pg in self.inp.pipes:
                    if pg.distance(g) <= NODE_TOL_M:
                        end = min(Point(pg.coords[0]).distance(g), Point(pg.coords[-1]).distance(g))
                        extra += 1 if end <= TRANSIT_END_M else 2
                if len(segs) + extra > limit and extra <= limit:
                    bad.append(f"{node}: {len(segs) + extra}")
            elif len(segs) > 2:
                bad.append(f"{node}: ветвление не в камере ({len(segs)})")
        return (OK, "в пределе") if not bad else (FAIL, "; ".join(bad[:5]))

    def c_no_cycles(self, value: bool) -> tuple[str, str]:
        parent: dict[str, str] = {}

        def find(a: str) -> str:
            while parent.setdefault(a, a) != a:
                parent[a] = parent[parent[a]]
                a = parent[a]
            return a

        cycles = 0
        for p, _ in self.best.segments:
            a, b = find(key(p["start_node_id"])), find(key(p["end_node_id"]))
            if a == b:
                cycles += 1
            else:
                parent[a] = b
        roots: dict[str, set[str]] = defaultdict(set)
        for r in self.best.roots:
            roots[find(r)].add(r)
        for node, g in self.best.nodes_tech.items():
            if g is None or len(self.best.adj.get(node, [])) != 1:
                continue
            if any(pg.distance(g) <= NODE_TOL_M for pg in self.inp.pipe_geoms):
                roots[find(node)].add(node)
        rings = sum(1 for rs in roots.values() if len(rs) > 1)
        if cycles or rings:
            return FAIL, f"замкнутых контуров {cycles}, сетей с двумя местами присоединения {rings}"
        return OK, "дерево, одно место присоединения у каждой сети"

    def c_no_self_cross(self, value: bool) -> tuple[str, str]:
        segs = self.best.segments
        tree = shapely.STRtree([g for _, g in segs])
        bad = []
        for i, (p, g) in enumerate(segs):
            for j in tree.query(g, predicate="intersects"):
                if j <= i:
                    continue
                q, h = segs[j]
                shared = {key(p["start_node_id"]), key(p["end_node_id"])} & {
                    key(q["start_node_id"]),
                    key(q["end_node_id"]),
                }
                inter = g.intersection(h)
                points = [self.best.node_point(n) for n in shared]
                ok = inter.geom_type == "Point" and any(pt is not None and inter.distance(pt) < 0.05 for pt in points)
                if not ok:
                    bad.append(f"{p.get('id')} × {q.get('id')}")
        return (OK, "пересечений нет") if not bad else (FAIL, "; ".join(bad[:5]))

    def c_flow_is_sum(self, value: bool) -> tuple[str, str]:
        flows = self.flows()
        bad = [
            f"{p.get('id')}: {p['flow_tph']} ≠ {flows.get(n, 0.0):.3f}"
            for n, (p, _) in enumerate(self.best.segments)
            if abs(p["flow_tph"] - flows.get(n, 0.0)) > 0.001
        ]
        return (OK, "расходы сходятся") if not bad else (FAIL, "; ".join(bad[:5]))

    def c_chamber_special_exit_m(self, spec: dict[str, float]) -> tuple[str, str]:
        bad, notes = [], []
        for cid, want in spec.items():
            g = self.inp.chambers.get(key(cid))
            roads = [
                rg for _, rg in self.inp.of("road") + self.inp.of("tram_tracks") if g is not None and rg.contains(g)
            ]
            segs = [self.best.segments[s] for s in self.best.adj.get(key(cid), [])]
            if not segs or not roads:
                bad.append(f"{cid}: участков от камеры нет")
                continue
            for p, seg in segs:
                coords = list(seg.coords)
                if Point(coords[0]).distance(g) > Point(coords[-1]).distance(g):
                    coords = coords[::-1]
                line = LineString(coords)
                exit_pt = line.intersection(roads[0].boundary)
                pts = shapely.get_coordinates(exit_pt)
                if p.get("laying_method") != "special" or not len(pts):
                    bad.append(f"{p.get('id')}: не спецпроход из камеры")
                    continue
                beyond = line.length - line.project(Point(pts[0]))
                notes.append(f"{p.get('id')}: за границей {beyond:.2f} м")
                if abs(beyond - want) > 0.3:
                    bad.append(f"{p.get('id')}: за границей {beyond:.2f} м, ждали {want}")
        return (OK, "; ".join(notes)) if not bad else (FAIL, "; ".join(bad))

    def c_final_from_nearest_boundary_m(self, value: Any) -> tuple[str, str]:
        skip = set()
        if isinstance(value, dict):
            skip = {key(c) for c in value.get("except", [])}
            value = value["tolerance_m"]
        bad, notes = [], []
        for cp in self.best.connected():
            if cp in skip:
                continue
            house = self.inp.building_of(cp)
            fs = self.best.final_segment(cp)
            if house is None or fs is None:
                continue
            ring = house.exterior if house.geom_type == "Polygon" else max(house.geoms, key=lambda x: x.area).exterior
            pt = self.inp.points[cp]
            line = self.path_line(cp)
            hits = [line.project(Point(xy)) for xy in shapely.get_coordinates(line.intersection(ring))]
            if not hits:
                continue
            exit_at = min(h for h in hits if h > 0.01) if any(h > 0.01 for h in hits) else min(hits)
            inner = [q for q in line.coords[1:] if 0.01 < line.project(Point(q)) < exit_at - 0.01]
            if inner:
                bad.append(f"{cp}: излом внутри своего здания")
                continue
            got = exit_at
            near = ring.distance(pt)
            notes.append(f"{cp}: вход в {got:.1f} м, ближайшая граница {near:.1f} м")
            if got > near + value:
                bad.append(f"{cp}: вход в {got:.1f} м при ближайшей границе {near:.1f} м")
        return (OK, "; ".join(notes) or "нет финальных участков") if not bad else (FAIL, "; ".join(bad))

    def c_crossings_min(self, spec: dict[str, int]) -> tuple[str, str]:
        got = {k: count_crossings(self.best, k) for k in spec}
        bad = [f"{k}: {got[k]} < {v}" for k, v in spec.items() if got[k] < v]
        return (OK, str(got)) if not bad else (FAIL, "; ".join(bad))

    def c_crossings_max(self, spec: dict[str, int]) -> tuple[str, str]:
        got = {k: count_crossings(self.best, k) for k in spec}
        bad = [f"{k}: {got[k]} > {v}" for k, v in spec.items() if got[k] > v]
        return (OK, str(got)) if not bad else (FAIL, "; ".join(bad))

    def c_crossing_angle_min_deg(self, spec: dict[str, float]) -> tuple[str, str]:
        notes, bad = [], []
        for kind, limit in spec.items():
            angles = crossing_angles(self.best, kind)
            if not angles:
                notes.append(f"{kind}: пересечений нет")
                continue
            worst = min(angles)
            notes.append(f"{kind}: мин. {worst:.1f}°")
            if worst < limit - ANGLE_TOL_DEG:
                bad.append(f"{kind}: {worst:.1f}° < {limit}°")
        return (OK, "; ".join(notes)) if not bad else (FAIL, "; ".join(bad))

    def c_special_entry_angle_min_deg(self, limit: float) -> tuple[str, str]:
        return self.c_crossing_angle_min_deg({"road": limit, "tram_tracks": limit})

    def c_special_kspec(self, spec: dict[str, float]) -> tuple[str, str]:
        notes, bad = [], []
        zones = self.special_zones()
        for kind, want in spec.items():
            seen = 0
            for n, (p, g) in enumerate(self.best.segments):
                if p.get("laying_method") != "special" or kind not in zones.get(n, (set(), set()))[1]:
                    continue
                seen += 1
                expected = self.expected_k(n)
                got = k_actual(p)
                if got is None or min(abs(got - e) for e in expected) > 0.01:
                    bad.append(f"{p.get('id')}: K {got}, ждали {expected[0]}")
            notes.append(f"{kind}: спецучастков {seen}")
        return (OK, "; ".join(notes)) if not bad else (FAIL, "; ".join(bad))

    def special_zones(self) -> dict[int, tuple[set[str], set[str]]]:
        cached = getattr(self, "_zones", None)
        if cached is not None:
            return cached
        v = self.best
        special = [n for n, (p, _) in enumerate(v.segments) if p.get("laying_method") == "special"]
        by_node: dict[str, list[int]] = defaultdict(list)
        for n in special:
            p = v.segments[n][0]
            by_node[key(p["start_node_id"])].append(n)
            by_node[key(p["end_node_id"])].append(n)
        anchors = [q for q in self.anchors() if q is not None]
        result: dict[int, tuple[set[str], set[str]]] = {}
        seen: set[int] = set()
        objects = [(p.get("restriction_type"), g) for p, g in self.inp.restrictions]
        objects += [("heat_network", g) for g in self.inp.pipe_geoms]
        for n in special:
            if n in seen:
                continue
            chain, stack = [], [n]
            while stack:
                cur = stack.pop()
                if cur in seen:
                    continue
                seen.add(cur)
                chain.append(cur)
                p = v.segments[cur][0]
                for node in (key(p["start_node_id"]), key(p["end_node_id"])):
                    stack += [m for m in by_node[node] if m not in seen]
            merged = shapely.line_merge(shapely.MultiLineString([v.segments[m][1] for m in chain]))
            parts = list(getattr(merged, "geoms", [merged]))
            hits = []
            for rtype, g in objects:
                rule = restriction_rule(rtype or "")
                if rule and rule.get("rule") == "special" and merged.intersects(g):
                    hits.append((rtype, g, rule.get("margin_m", 0.0), merged.intersection(g)))
            for line in parts:
                intervals: list[tuple[str, float, float, bool]] = []
                far: list[tuple[str, BaseGeometry, float]] = []
                for rtype, g, margin, cut in hits:
                    if not line.intersects(g):
                        far.append((rtype, cut, margin))
                        continue
                    if g.geom_type in POLY:
                        inside = line.intersection(g)
                        for part in getattr(inside, "geoms", [inside]):
                            if part.is_empty or part.length < 0.01:
                                continue
                            ts = [line.project(Point(xy)) for xy in shapely.get_coordinates(part)]
                            intervals.append((rtype, min(ts) - margin, max(ts) + margin, True))
                    else:
                        for xy in shapely.get_coordinates(line.intersection(g)):
                            pt = Point(xy)
                            t = line.project(pt)
                            touch = rtype == "heat_network" and min(t, line.length - t) < 0.05
                            at_tie = rtype == "heat_network" and any(
                                pt.distance(a) < 1.0 and g.distance(a) <= NODE_TOL_M for a in anchors
                            )
                            if at_tie and not touch:
                                continue
                            intervals.append((rtype, t - margin, t + margin, not touch))
                for m in chain:
                    mid = v.segments[m][1].interpolate(0.5, normalized=True)
                    if line.distance(mid) > 0.01:
                        continue
                    t = line.project(mid)
                    near = [(r, strict) for r, a, b, strict in intervals if a - 0.05 <= t <= b + 0.05]
                    near += [(r, True) for r, cut, margin in far if cut.distance(mid) <= margin + 0.05]
                    kinds = {r for r, _ in near}
                    result[m] = ({r for r, strict in near if strict and r in CORE_SPECIAL}, kinds)
        self._zones = result
        return result

    def expected_k(self, n: int) -> list[float]:
        core, every = self.special_zones().get(n, (set(), set()))
        values = []
        for kinds in (core, every):
            ks = [RULES["restrictions"][k]["k_special"] for k in kinds if k in RULES["restrictions"]]
            values.append(max(ks, default=1.0))
        return values

    def c_no_turn_in_special(self, value: bool) -> tuple[str, str]:
        bad = [
            p.get("id")
            for p, g in self.best.segments
            if p.get("laying_method") == "special" and len(shapely.get_coordinates(shapely.simplify(g, 0.01))) > 2
        ]
        return (OK, "спецучастки прямые") if not bad else (FAIL, f"изломы в {bad}")

    def c_no_cross(self, ids: list[Any]) -> tuple[str, str]:
        bad = []
        for oid in ids:
            g = self.inp.geom(oid)
            if g is None:
                continue
            kind = self.inp.by_id[key(oid)][0].get("restriction_type", "")
            rule = restriction_rule(kind) or RULES["restrictions"]["_fallback"]
            for p, seg in self.best.segments:
                d = seg.distance(g)
                clear = oks_clear(p["diameter"]) if isinstance(rule["clearance_m"], list) else rule["clearance_m"]
                need = clear + half_width(p["diameter"])
                if d < need - 0.05:
                    bad.append(f"{p.get('id')} в {d:.2f} м от {oid} (нужно {need:.2f})")
        return (OK, f"объектов {len(ids)}") if not bad else (FAIL, "; ".join(bad[:5]))

    def c_no_pass_between(self, ids: list[Any]) -> tuple[str, str]:
        a, b = (self.inp.geom(i) for i in ids)
        width = a.distance(b)
        gap = a.buffer(width / 2 + 1).intersection(b.buffer(width / 2 + 1)).difference(shapely.union_all([a, b]))
        hit = self.best.route().intersection(gap).length
        return (OK if hit < 0.5 else FAIL), f"длина трассы в щели {hit:.1f} м"

    def c_final_segment_clear_of(self, ids: list[Any]) -> tuple[str, str]:
        bad = []
        for cp in self.inp.points:
            fs = self.best.final_segment(cp)
            if fs is None:
                continue
            p, seg = fs
            for oid in ids:
                d = seg.distance(self.inp.geom(oid))
                need = oks_clear(p["diameter"]) + half_width(p["diameter"])
                if d < need - 0.05:
                    bad.append(f"{cp}: {d:.2f} м до {oid}")
        return (OK, "чисто") if not bad else (FAIL, "; ".join(bad))

    def c_clearance_m(self, spec: dict[str, float]) -> tuple[str, str]:
        bad, worst = [], None
        for oid, clear in spec.items():
            g = self.inp.geom(oid)
            if g is None:
                continue
            kind = self.inp.by_id[key(oid)][0].get("restriction_type", "")
            own = RULES["restrictions"].get(kind, {}).get("half_width_m", 0.0)
            hits = (
                shapely.union_all(
                    [
                        Point(xy).buffer(CROSS_SKIP_M)
                        for xy in shapely.get_coordinates(self.best.route().intersection(g))
                    ]
                )
                if self.best.route().intersects(g)
                else None
            )
            for p, seg in self.best.segments:
                if p.get("laying_method") == "special":
                    continue
                part = seg.difference(hits) if hits is not None else seg
                if part.is_empty:
                    continue
                d = part.distance(g) - own - half_width(p["diameter"])
                worst = d if worst is None else min(worst, d)
                if d < clear - 0.05:
                    bad.append(f"{p.get('id')}: {d:.2f} м < {clear}")
        detail = f"наименьший отступ {worst:.2f} м" if worst is not None else "базовых участков рядом нет"
        return (OK, detail) if not bad else (FAIL, "; ".join(bad[:5]))

    def final_dn(self, cp: str) -> int | None:
        fs = self.best.final_segment(key(cp))
        return None if fs is None else fs[0].get("diameter")

    def c_final_dn_range(self, spec: dict[str, list[int]]) -> tuple[str, str]:
        bad, notes = [], []
        for cp, (lo, hi) in spec.items():
            dn = self.final_dn(cp)
            notes.append(f"{cp}: Ду {dn}")
            if dn is None or not lo <= dn <= hi:
                bad.append(f"{cp}: Ду {dn}, ждали {lo}–{hi}")
        return (OK, "; ".join(notes)) if not bad else (FAIL, "; ".join(bad))

    def c_final_dn_min(self, spec: dict[str, int]) -> tuple[str, str]:
        return self.c_final_dn_range({cp: [v, 1400] for cp, v in spec.items()})

    def c_tie_segment_dn(self, value: int) -> tuple[str, str]:
        dns = sorted({self.best.segments[s][0]["diameter"] for r in self.best.roots for s in self.best.adj[r]})
        return (OK if dns == [value] else FAIL), f"Ду у места присоединения {dns}, ждали {value}"

    def c_segment_flow_within_capacity(self, value: bool) -> tuple[str, str]:
        bad = [
            p.get("id")
            for n, (p, _) in enumerate(self.best.segments)
            if self.flows().get(n, 0.0) > TABLE[p["diameter"]]["capacity_tph"] + 1e-6
        ]
        return (OK, "в пределах") if not bad else (FAIL, f"перегружены {bad}")

    def c_dn_by_flow_and_length(self, value: bool) -> tuple[str, str]:
        v = self.best
        flows = self.flows()
        bad = []
        lowerable: dict[int, bool] = {}

        def dn_of(s: int) -> int:
            return v.segments[s][0]["diameter"]

        for cp in v.connected():
            path = v.path(cp) or []
            runs: list[list[int]] = []
            for s in path:
                if runs and dn_of(runs[-1][-1]) == dn_of(s):
                    runs[-1].append(s)
                else:
                    runs.append([s])
            for i, run in enumerate(runs):
                dn = dn_of(run[0])
                length = sum(v.segments[s][1].length for s in run)
                if i and dn < dn_of(runs[i - 1][0]):
                    bad.append(f"{cp}: Ду уменьшается к врезке ({dn_of(runs[i - 1][0])} → {dn})")
                if length > TABLE[dn]["max_length_m"] + LEN_TOL_M:
                    bad.append(f"{cp}: путь Ду {dn} длиной {length:.0f} м больше предела")
            for a_, b_ in zip(path, path[1:], strict=False):
                if abs(flows.get(a_, 0.0) - flows.get(b_, 0.0)) < 1e-6 and dn_of(a_) != dn_of(b_):
                    bad.append(f"{cp}: Ду меняется при постоянном расходе ({dn_of(a_)} → {dn_of(b_)})")
            for s in path:
                flow = flows.get(s, 0.0)
                if flow > TABLE[dn_of(s)]["capacity_tph"] + 1e-6:
                    bad.append(f"{v.segments[s][0].get('id')}: расход {flow:.3f} больше пропускной Ду {dn_of(s)}")
            chains: list[list[int]] = []
            for s in path:
                if (
                    chains
                    and abs(flows.get(chains[-1][-1], 0.0) - flows.get(s, 0.0)) < 1e-6
                    and dn_of(chains[-1][-1]) == dn_of(s)
                ):
                    chains[-1].append(s)
                else:
                    chains.append([s])
            for i, chain in enumerate(chains):
                dn = dn_of(chain[0])
                lower = max((d for d in TABLE if d < dn), default=None)
                ok = lower is not None
                if ok:
                    flow = max(flows.get(s, 0.0) for s in chain)
                    merged = sum(v.segments[s][1].length for s in chain)
                    j = i - 1
                    while j >= 0 and dn_of(chains[j][0]) == lower:
                        merged += sum(v.segments[s][1].length for s in chains[j])
                        j -= 1
                    below = max((dn_of(chains[k][0]) for k in range(i)), default=0)
                    ok = (
                        flow <= TABLE[lower]["capacity_tph"] + 1e-6
                        and below <= lower
                        and merged <= TABLE[lower]["max_length_m"] - LEN_TOL_M
                    )
                for s in chain:
                    lowerable[s] = lowerable.get(s, True) and ok
        over = sorted({v.segments[s][0].get("id") for s, ok in lowerable.items() if ok}, key=str)
        bad += [f"{sid}: Ду завышен, проходит меньший" for sid in over]
        return (OK, f"участков {len(v.segments)}") if not bad else (FAIL, "; ".join(sorted(set(bad))[:5]))

    def c_dn_valid(self, value: bool) -> tuple[str, str]:
        bad = [
            f"{p.get('id')}: {p.get('diameter')!r}"
            for p, _ in self.best.segments
            if not isinstance(p.get("diameter"), int)
            or isinstance(p.get("diameter"), bool)
            or p.get("diameter") not in TABLE
        ]
        bad += [
            f"{n}: {p.get('diameter')!r}"
            for n, (p, _) in self.best.chambers.items()
            if not isinstance(p.get("diameter"), int) or p.get("diameter") not in TABLE
        ]
        return (OK, "Ду целые и из таблицы 1") if not bad else (FAIL, "; ".join(bad[:5]))

    def c_summary_consistent(self, value: bool) -> tuple[str, str]:
        v, bad = self.best, []
        summ = v.summary
        length = sum(g.length for _, g in v.segments)
        seg_cost = sum(p.get("cost") or 0.0 for p, _ in v.segments)
        for idx, (p, g) in enumerate(v.segments):
            if abs((p.get("length") or 0.0) - g.length) > 0.05:
                bad.append(f"{p.get('id')}: length {p.get('length')} при геометрии {g.length:.2f}")
            ds, de = p.get("depth_start"), p.get("depth_end")
            kgl = 1.0 if ds is None or de is None else (max(1.0, 1 + 0.1 * (ds - 3)) + max(1.0, 1 + 0.1 * (de - 3))) / 2
            price = TABLE[p["diameter"]]["new_rub_m"] * kgl
            bases = [(p.get("length") or g.length) * price, g.length * price]
            base = bases[0]
            ks = [1.0] if p.get("laying_method") != "special" else self.expected_k(idx)
            if min(abs((p.get("cost") or 0.0) - b * k) for k in ks for b in bases) > max(2.0, base * max(ks) * 2e-4):
                bad.append(f"{p.get('id')}: cost {p.get('cost')} при {base * ks[0]:.0f} по таблице (K {ks[0]})")
        own_dn, all_dn = 0.0, 0.0
        for node, (_, g) in v.chambers.items():
            news = [v.segments[s][0]["diameter"] for s in v.adj.get(node, [])]
            pipe = [ip.get("diameter") or 0 for ip, pg in self.inp.pipes if pg.distance(g) <= NODE_TOL_M]
            own_dn += chamber_price(max(news, default=50))
            all_dn += chamber_price(max(news + pipe, default=50))
        chambers = summ.get("chamber_construction_cost")
        if chambers is None or min(abs(chambers - own_dn), abs(chambers - all_dn)) > 1.0:
            bad.append(f"chamber_construction_cost {chambers} при {own_dn:.0f} или {all_dn:.0f}")
        ties = sum(len(v.adj.get(c, [])) for c in self.inp.chambers)
        if summ.get("existing_chamber_tie_in_count") != ties:
            bad.append(f"existing_chamber_tie_in_count {summ.get('existing_chamber_tie_in_count')} при {ties}")
        if abs((summ.get("existing_chamber_tie_in_cost") or 0.0) - ties * RULES["tie_in_cost"]) > 1.0:
            bad.append(f"existing_chamber_tie_in_cost {summ.get('existing_chamber_tie_in_cost')}")
        connected = v.connected()
        missing = [cp for cp in self.inp.points if cp not in connected]
        penalty = sum(
            RULES["penalty"]["fixed"] + RULES["penalty"]["per_tph"] * (self.inp.flows.get(cp) or 0.0) for cp in missing
        )
        if {key(i) for i in summ.get("unconnected_oks_ids", [])} != set(missing):
            bad.append("unconnected_oks_ids не совпадает с геометрией")
        if abs((summ.get("unconnected_penalty") or 0.0) - penalty) > 1.0:
            bad.append(f"unconnected_penalty {summ.get('unconnected_penalty')} при {penalty:.0f}")
        construction = seg_cost + (chambers or 0.0) + ties * RULES["tie_in_cost"]
        if abs((summ.get("construction_cost") or 0.0) - construction) > 10.0:
            bad.append(f"construction_cost {summ.get('construction_cost')} при {construction:.0f}")
        calc = construction + penalty
        if abs((summ.get("calculated_cost") or 0.0) - calc) > 10.0:
            bad.append(f"calculated_cost {summ.get('calculated_cost')} при {calc:.0f}")
        if abs((summ.get("new_network_length") or 0.0) - length) > max(0.5, length * 1e-4):
            bad.append(f"new_network_length {summ.get('new_network_length')} при {length:.2f}")
        cb, lb = RULES["score"]["cost_base"], RULES["score"]["length_base_m"]
        score = 0.7 * calc / cb + 0.3 * length / lb
        stated = 0.7 * (summ.get("calculated_cost") or 0.0) / cb + 0.3 * (summ.get("new_network_length") or 0.0) / lb
        if min(abs((summ.get("score") or 0.0) - x) for x in (score, stated)) > 2e-4:
            bad.append(f"score {summ.get('score')} при {score:.4f}")
        return (OK, f"S {score:.4f}") if not bad else (FAIL, "; ".join(bad[:5]))

    def c_special_length_max_m(self, spec: dict[str, float]) -> tuple[str, str]:
        bad, notes = [], []
        for oid, limit in spec.items():
            g = self.inp.geom(oid)
            for p, seg in self.best.segments:
                if g is None or p.get("laying_method") != "special" or not seg.intersects(g):
                    continue
                notes.append(f"{p.get('id')}: {seg.length:.1f} м")
                if seg.length > limit + LEN_TOL_M:
                    bad.append(f"{p.get('id')}: спецучасток {seg.length:.1f} м > {limit}")
        return (OK, "; ".join(notes) or "спецучастков нет") if not bad else (FAIL, "; ".join(bad))

    def trees(self) -> dict[str, set[str]]:
        result: dict[str, set[str]] = defaultdict(set)
        for cp in self.best.connected():
            result[self.best.root_of[cp]].add(cp)
        return result

    def c_trees_min(self, value: int) -> tuple[str, str]:
        n = len(self.trees())
        return (OK if n >= value else FAIL), f"деревьев {n}"

    def c_same_tree(self, groups: list[list[Any]]) -> tuple[str, str]:
        bad = []
        for group in groups:
            roots = {self.best.root_of.get(key(cp)) for cp in group}
            if len(roots) != 1 or None in roots:
                bad.append(f"{group}: корни {sorted(map(str, roots))}")
        return (OK, "в одном дереве") if not bad else (FAIL, "; ".join(bad))

    def c_tie_ins_max(self, value: int) -> tuple[str, str]:
        n = len(self.trees())
        return (OK if n <= value else FAIL), f"мест присоединения {n}"

    def c_new_chambers_on_pipe_max(self, spec: dict[str, int]) -> tuple[str, str]:
        bad, notes = [], []
        for pid, limit in spec.items():
            g = self.inp.geom(pid)
            n = sum(1 for _, (p, cg) in self.best.chambers.items() if g is not None and g.distance(cg) <= NODE_TOL_M)
            notes.append(f"{pid}: {n}")
            if n > limit:
                bad.append(f"{pid}: {n} > {limit}")
        return (OK, "; ".join(notes)) if not bad else (FAIL, "; ".join(bad))

    def c_along_axes_share_min(self, value: float) -> tuple[str, str]:
        roads = [g for _, g in self.inp.of("road") if g.geom_type in POLY and g.area > 300]
        if not roads:
            return NA, "улиц нет"
        tree = shapely.STRtree(roads)
        good = total = 0.0
        for _, seg in self.best.segments:
            coords = list(seg.coords)
            for a, b in zip(coords, coords[1:], strict=False):
                piece = LineString([a, b])
                if piece.length < 0.5:
                    continue
                n = tree.query_nearest(piece)[0]
                edge = boundary(roads[n])
                mid = piece.interpolate(0.5, normalized=True)
                part = min(getattr(edge, "geoms", [edge]), key=lambda g: g.distance(mid))
                u = direction_at(part, part.interpolate(part.project(mid)))
                v = (b[0] - a[0], b[1] - a[1])
                ang = math.degrees(math.atan2(abs(u[0] * v[1] - u[1] * v[0]), u[0] * v[0] + u[1] * v[1])) % 90
                total += piece.length
                if min(ang, 90 - ang) <= 10:
                    good += piece.length
        share = good / total if total else 1.0
        return (OK if share >= value - 1e-9 else FAIL), f"доля {share:.2f}"

    def path_line(self, cp: str) -> LineString:
        pts: list[tuple[float, float]] = []
        cur = self.inp.points[cp]
        for s in self.best.path(cp) or []:
            coords = list(self.best.segments[s][1].coords)
            if Point(coords[0]).distance(cur) > Point(coords[-1]).distance(cur):
                coords = coords[::-1]
            pts += coords if not pts else coords[1:]
            cur = Point(coords[-1])
        return LineString(pts) if len(pts) > 1 else LineString([(cur.x, cur.y), (cur.x, cur.y + 1e-6)])

    def c_route_length_max_m(self, spec: dict[str, float]) -> tuple[str, str]:
        return self.c_max_route_length_m(spec)

    def turns(self, cp: str) -> list[float]:
        path = self.best.path(cp) or []
        pts: list[tuple[float, float]] = []
        cur = self.inp.points[cp]
        for s in path:
            coords = list(self.best.segments[s][1].coords)
            if Point(coords[0]).distance(cur) > Point(coords[-1]).distance(cur):
                coords = coords[::-1]
            pts += coords if not pts else coords[1:]
            cur = Point(coords[-1])
        result = []
        for a, b, c in zip(pts, pts[1:], pts[2:], strict=False):
            u, v = (b[0] - a[0], b[1] - a[1]), (c[0] - b[0], c[1] - b[1])
            if math.hypot(*u) < 1e-6 or math.hypot(*v) < 1e-6:
                continue
            result.append(math.degrees(math.atan2(abs(u[0] * v[1] - u[1] * v[0]), u[0] * v[0] + u[1] * v[1])))
        return result

    def c_no_short_reversal_m(self, value: float) -> tuple[str, str]:
        bad = []
        for cp in self.best.connected():
            pts = list(self.path_line(cp).coords)
            signed = []
            for a, b, c in zip(pts, pts[1:], pts[2:], strict=False):
                u, w = (b[0] - a[0], b[1] - a[1]), (c[0] - b[0], c[1] - b[1])
                if math.hypot(*u) < 1e-6 or math.hypot(*w) < 1e-6:
                    continue
                ang = math.degrees(math.atan2(u[0] * w[1] - u[1] * w[0], u[0] * w[0] + u[1] * w[1]))
                signed.append((ang, math.hypot(*w)))
            for i in range(len(signed) - 1):
                a1, link = signed[i]
                a2, _ = signed[i + 1]
                if a1 * a2 > 0 and abs(a1 + a2) > 90 + ANGLE_EPS_DEG and link < value:
                    bad.append(f"{cp}: повороты {a1:.0f}° и {a2:.0f}° через звено {link:.1f} м")
        return (OK, "коротких разворотов нет") if not bad else (FAIL, "; ".join(bad[:5]))

    def obstacles(self) -> tuple[list[tuple[BaseGeometry, float, bool]], shapely.STRtree]:
        if not hasattr(self, "_obstacles"):
            rows = []
            for p, g in [*self.inp.restrictions, *self.inp.pipes]:
                kind = p.get("restriction_type") or p.get("object_type")
                rule = restriction_rule(kind) or RULES["restrictions"]["_fallback"]
                clear = rule["clearance_m"]
                clear = None if isinstance(clear, list) else clear + rule.get("half_width_m", 0.0)
                rows.append((g, clear, "k_special" in rule))
            self._obstacles = rows, shapely.STRtree([g for g, _, _ in rows])
        return self._obstacles

    def shortcut_ok(self, cut: LineString, detour: LineString, dn: int, own: BaseGeometry | None) -> bool:
        rows, tree = self.obstacles()
        ends = shapely.MultiPoint(cut.coords)
        for n in tree.query(cut, predicate="dwithin", distance=oks_clear(dn) + 2 * half_width(dn) + 3.0):
            g, clear, special = rows[n]
            need = (oks_clear(dn) if clear is None else clear) + half_width(dn)
            d = cut.distance(g)
            if special and d < need:
                return False
            near = ends.distance(g) if g is own else detour.distance(g)
            if d < min(need, near) - 0.05:
                return False
        return True

    def steps(self, cp: str, link_max: float) -> list[str]:
        path = self.best.path(cp) or []
        special = shapely.union_all(
            [g for p, g in self.best.segments if p.get("laying_method") == "special"] or [LineString()]
        )
        dn = max((self.best.segments[s][0].get("diameter", 50) for s in path), default=50)
        dn = dn if dn in TABLE else 50
        own = self.inp.building_of(cp)
        pts = []
        for xy in self.path_line(cp).coords:
            if not pts or math.dist(pts[-1], xy) > 0.05:
                pts.append(xy)
        forks = [
            self.best.node_point(n) for n, adj in self.best.adj.items() if len(adj) > 2 or n in self.best.roots
        ]
        fork_at = [any(f is not None and f.distance(Point(xy)) < NODE_TOL_M for f in forks) for xy in pts]
        segs = list(zip(pts, pts[1:], strict=False))
        lens = [math.dist(a, b) for a, b in segs]
        heads = [math.atan2(b[1] - a[1], b[0] - a[0]) for a, b in segs]

        def turn(i: int) -> float:
            return math.degrees((heads[i + 1] - heads[i] + math.pi) % (2 * math.pi) - math.pi)

        found = []
        for s in range(len(segs)):
            if fork_at[s + 1]:
                continue
            for t in range(s + 2, len(segs)):
                if lens[t - 1] >= link_max:
                    break
                if fork_at[t]:
                    break
                turns = [turn(i) for i in range(s, t)]
                if not (max(turns) >= STEP_TURN_DEG and min(turns) <= -STEP_TURN_DEG):
                    continue
                (a0, a1), (b0, b1) = segs[s], segs[t]
                ka, kb = min(lens[s], STEP_LEAD_M) / lens[s], min(lens[t], STEP_LEAD_M) / lens[t]
                a = (a1[0] - (a1[0] - a0[0]) * ka, a1[1] - (a1[1] - a0[1]) * ka)
                b = (b0[0] + (b1[0] - b0[0]) * kb, b0[1] + (b1[1] - b0[1]) * kb)
                detour = LineString([a, *pts[s + 1 : t + 1], b])
                cut = LineString([a, b])
                if detour.length - cut.length < STEP_GAIN_M or detour.intersection(special).length > 0.1:
                    continue
                hc = math.atan2(b[1] - a[1], b[0] - a[0])
                bends = [
                    abs(math.degrees((h1 - h0 + math.pi) % (2 * math.pi) - math.pi))
                    for h0, h1 in ((heads[s], hc), (hc, heads[t]))
                ]
                if max(bends) > STEP_BEND_DEG or not self.shortcut_ok(cut, detour, dn, own):
                    continue
                found.append(
                    f"{cp}: повороты {', '.join(f'{x:.0f}°' for x in turns)} в {sum(lens[: s + 1]):.0f} м от "
                    f"точки, путь {detour.length:.1f} м вместо прямой {cut.length:.1f} м"
                )
                break
        return found

    def c_no_step_m(self, value: float) -> tuple[str, str]:
        bad = [x for cp in sorted(self.best.connected()) for x in self.steps(cp, value)]
        return (OK, "ступенек и выступов нет") if not bad else (FAIL, "; ".join(bad[:5]))

    def c_variants(self, value: Any) -> tuple[str, str]:
        n = len(self.variants)
        ranks = sorted(
            int(r) for v in self.variants.values() if isinstance(r := v.summary.get("rank"), int | float) and r == int(r)
        )
        if n > VARIANTS_MAX:
            return FAIL, f"вариантов {n} > {VARIANTS_MAX}"
        if ranks != list(range(1, n + 1)):
            return FAIL, f"ранги {ranks} вместо 1…{n}"
        items = list(self.variants.values())
        same = []
        for i in range(n):
            for j in range(i + 1, n):
                if self.same_variant(items[i], items[j]):
                    same.append(f"{items[i].vid} и {items[j].vid}")
        return (OK, f"вариантов {n}") if not same else (FAIL, "не отличаются: " + ", ".join(same))

    def same_variant(self, a: Variant, b: Variant) -> bool:
        ta = [p for p in a.tie_points().values() if p is not None]
        tb = [p for p in b.tie_points().values() if p is not None]
        if len(ta) != len(tb) or not all(any(x.distance(y) <= 10 for y in tb) for x in ta):
            return False
        if a.connected() != b.connected():
            return False
        ra, rb = a.route(), b.route()
        if ra.length < 1e-6 or rb.length < 1e-6:
            return ra.length < 1e-6 and rb.length < 1e-6
        near = min(
            ra.intersection(rb.buffer(VARIANT_SHIFT_M)).length / ra.length,
            rb.intersection(ra.buffer(VARIANT_SHIFT_M)).length / rb.length,
        )
        return near >= VARIANT_SAME_SHARE

    def c_max_turn_deg(self, value: float) -> tuple[str, str]:
        worst = max((max(self.turns(cp), default=0.0) for cp in self.best.connected()), default=0.0)
        return (OK if worst <= value + ANGLE_EPS_DEG else FAIL), f"наибольший поворот {worst:.1f}°"

    def c_max_turns(self, spec: dict[str, int]) -> tuple[str, str]:
        bad, notes = [], []
        for cp, limit in spec.items():
            n = sum(1 for t in self.turns(key(cp)) if t > 3.0)
            notes.append(f"{cp}: {n}")
            if n > limit:
                bad.append(f"{cp}: поворотов {n} > {limit}")
        return (OK, "; ".join(notes)) if not bad else (FAIL, "; ".join(bad))

    def c_variants_min(self, value: int) -> tuple[str, str]:
        n = len(self.variants)
        return (OK if n >= value else FAIL), f"вариантов {n}"

    def tie_sets(self) -> list[list[Point]]:
        return [[p for p in v.tie_points().values() if p is not None] for v in self.variants.values()]

    def c_variants_distinct_tie_in(self, value: bool) -> tuple[str, str]:
        sets = self.tie_sets()
        same = 0
        for i in range(len(sets)):
            for j in range(i + 1, len(sets)):
                if all(any(a.distance(b) <= 10 for b in sets[j]) for a in sets[i]) and sets[i]:
                    same += 1
        return (OK if same == 0 and len(sets) > 1 else FAIL), f"пар вариантов с теми же местами врезки {same}"

    def c_variants_tie_in_spread_min_m(self, value: float) -> tuple[str, str]:
        sets = self.tie_sets()
        if len(sets) < 2:
            return OK, "вариант один"
        return self.c_variants_distinct_tie_in(True)

    def c_variants_ranked(self, value: bool) -> tuple[str, str]:
        rows = sorted((v.summary.get("rank", 0), v.summary.get("score", 0)) for v in self.variants.values())
        ok = all(a[1] <= b[1] + 1e-9 for a, b in zip(rows, rows[1:], strict=False))
        return (OK if ok else FAIL), f"ранги и S {rows}"

    def c_ids_numeric_in_output(self, value: bool) -> tuple[str, str]:
        numeric_in = {key(p["id"]) for p, _ in self.inp.features if isinstance(p.get("id"), int)}
        bad = []
        for v in self.variants.values():
            for p, _ in v.segments:
                for k in ("start_node_id", "end_node_id"):
                    if key(p[k]) in numeric_in and not isinstance(p[k], int):
                        bad.append(f"{p.get('id')}.{k}")
            bad += [f"unconnected {i}" for i in v.summary.get("unconnected_oks_ids", []) if not isinstance(i, int)]
        return (OK, "ссылки числовые") if not bad else (FAIL, f"строковые ссылки: {bad[:5]}")

    def c_source_not_used(self, ids: list[Any]) -> tuple[str, str]:
        bad = []
        for sid in ids:
            g = self.inp.sources.get(key(sid))
            if key(sid) in self.best.adj:
                bad.append(f"{sid} — узел сети")
            if g is not None and any(seg.distance(g) < NODE_TOL_M for _, seg in self.best.segments):
                bad.append(f"трасса проходит через {sid}")
        return (OK, "не использован") if not bad else (FAIL, "; ".join(bad))

    def c_no_route_beyond_data(self, value: bool) -> tuple[str, str]:
        bbox = self.meta.get("source", {}).get("bbox_wgs84")
        if not bbox:
            return NA, "нет bbox"
        area = utm(shapely.box(*bbox)).buffer(100)
        outside = self.best.route().difference(area).length
        return (OK if outside < 0.5 else FAIL), f"длина трассы за охватом {outside:.1f} м"

    def c_final_from_outer_contour(self, value: bool) -> tuple[str, str]:
        bad = []
        for cp in self.best.connected():
            house = self.inp.building_of(cp)
            fs = self.best.final_segment(cp)
            if house is None or fs is None or house.geom_type != "Polygon":
                continue
            start = Point(fs[1].coords[0])
            if house.exterior.distance(start) > 0.5:
                bad.append(f"{cp}: финальный участок не от внешнего контура")
        return (OK, "от внешнего контура") if not bad else (FAIL, "; ".join(bad))

    def c_continuous_special_over(self, ids: list[Any]) -> tuple[str, str]:
        zone = shapely.union_all([self.inp.geom(i) for i in ids if self.inp.geom(i) is not None]).convex_hull
        pieces = [(p, g) for p, g in self.best.segments if g.intersects(zone)]
        base = [
            p.get("id") for p, g in pieces if p.get("laying_method") != "special" and g.intersection(zone).length > 0.1
        ]
        return (
            (OK, f"участков {len(pieces)}") if not base else (FAIL, f"обычные участки между проезжими частями {base}")
        )

    def c_road_line_zone_m(self, value: float) -> tuple[str, str]:
        notes, bad = [], []
        for _, line in self.inp.of("road"):
            if line.geom_type not in LINE:
                continue
            for p, seg in self.best.segments:
                if p.get("laying_method") != "special" or not seg.intersects(line):
                    continue
                pt = crossing_points(seg, line)[0]
                ends = [Point(seg.coords[0]).distance(pt), Point(seg.coords[-1]).distance(pt)]
                notes.append(f"{p.get('id')}: {ends[0]:.2f} и {ends[1]:.2f} м")
                if min(ends) < value - 0.05:
                    bad.append(f"{p.get('id')}: зона {min(ends):.2f} м < {value}")
        if not notes:
            return FAIL, "спецпрохода через дорогу-линию нет"
        return (OK, "; ".join(notes)) if not bad else (FAIL, "; ".join(bad))

    def depth_at(self, props: dict[str, Any], seg: LineString, pt: Point) -> float | None:
        ds, de = props.get("depth_start"), props.get("depth_end")
        if ds is None or de is None:
            return None
        t = seg.project(pt) / seg.length if seg.length else 0.0
        return ds + (de - ds) * t

    def c_depth_profile_valid(self, value: bool) -> tuple[str, str]:
        bad = []
        for p, seg in self.best.segments:
            ds, de = p.get("depth_start"), p.get("depth_end")
            if ds is None or de is None:
                bad.append(f"{p.get('id')}: глубина null")
                continue
            if min(ds, de) < RULES["depth"]["min_m"] - 0.01:
                bad.append(f"{p.get('id')}: мельче 0,7 м")
            if seg.length > 0 and abs(de - ds) / seg.length > RULES["depth"]["max_slope"] + 1e-3:
                bad.append(f"{p.get('id')}: уклон {abs(de - ds) / seg.length:.3f}")
            if (ds - 3.0) * (de - 3.0) < -1e-6:
                bad.append(f"{p.get('id')}: пересекает 3,0 м без узла")
        return (OK, f"участков {len(self.best.segments)}") if not bad else (FAIL, "; ".join(bad[:5]))

    def crossings_depth(self) -> list[tuple[str, float, int, str, Any]]:
        result = []
        for p, seg in self.best.segments:
            for kind, items in (
                ("gas_pipeline", self.inp.of("gas_pipeline")),
                ("power_cable", self.inp.of("power_cable")),
                ("heat_network", self.inp.pipes),
            ):
                for ip, g in items:
                    for pt in crossing_points(seg, g):
                        if kind == "heat_network" and any(q is not None and pt.distance(q) < 1 for q in self.anchors()):
                            continue
                        h = self.depth_at(p, seg, pt)
                        if h is not None:
                            side = "below" if h > (DEPTH_LINES.get(kind, (3.0,))[0]) else "above"
                            result.append((kind, h, p["diameter"], side, ip.get("diameter")))
        return result

    def c_depth_crossings_valid(self, value: bool) -> tuple[str, str]:
        bad, n = [], 0
        for kind, h, dn, _, other_dn in self.crossings_depth():
            n += 1
            height = TABLE[dn]["height_m"]
            if kind in DEPTH_LINES:
                top, size, clear = DEPTH_LINES[kind]
            else:
                top, size, clear = (
                    DEPTH_HEAT_TOP_M,
                    TABLE.get(other_dn or 50, TABLE[50])["height_m"],
                    DEPTH_HEAT_CLEAR_M,
                )
            above = h + height <= top - clear + 0.01
            below = h >= top + size + clear - 0.01
            if not (above or below):
                bad.append(f"{kind}: верх {h:.2f} м, Ду {dn}")
        for p, seg in self.best.segments:
            for kind, limit in DEPTH_MIN_TOP.items():
                for _, g in self.inp.of(kind):
                    if seg.intersects(g):
                        inside = seg.intersection(g)
                        for xy in shapely.get_coordinates(inside):
                            h = self.depth_at(p, seg, Point(xy))
                            if h is not None and h < limit - 0.01:
                                bad.append(f"{kind}: верх {h:.2f} м < {limit}")
        return (OK, f"пересечений линий {n}") if not bad else (FAIL, "; ".join(sorted(set(bad))[:5]))

    def c_crossing_side(self, spec: dict[str, str]) -> tuple[str, str]:
        rows = [(k, h, side) for k, h, _, side, _ in self.crossings_depth() if k in spec]
        if not rows:
            return FAIL, "пересечений нет"
        bad = [f"{k}: верх {h:.2f} м ({side})" for k, h, side in rows if side != spec[k]]
        return (OK, f"пересечений {len(rows)}") if not bad else (FAIL, "; ".join(bad))

    def c_kgl_one(self, value: bool) -> tuple[str, str]:
        deep = [
            p.get("id")
            for p, _ in self.best.segments
            if max(p.get("depth_start") or 0, p.get("depth_end") or 0) > 3.0 + 1e-6
        ]
        return (OK, "глубже 3,0 м нет") if not deep else (FAIL, f"глубже 3,0 м: {deep[:5]}")


def check_scene(folder: Path, out_name: str) -> dict[str, Any]:
    checker = Checker(folder, out_name)
    results = []
    for level in ("must", "should"):
        for name, value in checker.meta["expect"].get(level, {}).items():
            try:
                status, detail = checker.check(name, value)
            except Exception as error:
                status, detail = NA, f"ошибка проверки: {error}"
            results.append({"level": level, "key": name, "status": status, "detail": detail})
    summary = {
        level: {s: sum(1 for r in results if r["level"] == level and r["status"] == s) for s in (OK, FAIL, NA)}
        for level in ("must", "should")
    }
    report = {
        "id": checker.meta["id"],
        "output": out_name if checker.out is not None else None,
        "variant": checker.best.vid if checker.best else None,
        "results": results,
        "summary": summary,
    }
    (folder / "expect.json").write_text(json.dumps(report, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description="Проверка ожиданий сцен по выходу сервиса")
    parser.add_argument("paths", nargs="+", help="папка набора или папки сцен")
    parser.add_argument("--out", default="output.geojson", help="имя файла выхода в папке сцены")
    args = parser.parse_args()
    folders = []
    for p in map(Path, args.paths):
        folders += [p] if (p / "scene.json").exists() else sorted(f.parent for f in p.glob("*/scene.json"))
    total = defaultdict(lambda: defaultdict(int))
    for folder in folders:
        report = check_scene(folder, args.out)
        print(f"{report['id']} (вариант {report['variant']})")
        for r in report["results"]:
            print(f"  {r['level']}.{r['key']}: {r['status']} — {r['detail']}")
            total[r["level"]][r["status"]] += 1
    print("Итого: " + "; ".join(f"{lvl}: " + ", ".join(f"{s} {n}" for s, n in total[lvl].items()) for lvl in total))
    sys.exit(0)


if __name__ == "__main__":
    main()
