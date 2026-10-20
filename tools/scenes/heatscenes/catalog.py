import math
import random
from collections.abc import Callable, Iterator
from dataclasses import dataclass
from typing import Any

import shapely
from heatscenes.finders import Candidate, candidates
from heatscenes.reference import CELL_M, bound, shortest_path
from heatscenes.scene import (
    CLIP_PAD_M,
    TIE_CHAMBER_M,
    Scene,
    crossed,
    cut,
    dn_for,
    free_chambers,
    free_spot,
    nearest_pipe,
    obstacles,
    orientation,
    rect,
    rules,
    segments_at,
)
from heatscenes.world import BUILDINGS, CITY_NAMES, FORBID, Item, to_wgs, world
from shapely.geometry import LineString, Point, Polygon, box
from shapely.ops import nearest_points

HOUSE_L, HOUSE_D = 48.0, 16.0
EXTEND_M = 7000.0
SPECIAL_KINDS = ("road", "tram_tracks", "gas_pipeline", "power_cable", "water_supply", "sewer", "heat_network")
TABLE2_FORBID = ("park", "social_area", "prohibited_site", "water", "railway")
TABLE2 = ("road", "tram_tracks", "gas_pipeline", "power_cable", "heat_network")
DEPTH_NOTE = {
    "gas_pipeline": "в режиме глубины выше или ниже газопровода (верх на 2,8 м, габарит 0,4 м), просвет не меньше 0,2 м",
    "power_cable": "в режиме глубины выше или ниже кабеля (верх на 2,7 м, габарит 0,2 м), просвет не меньше 0,5 м",
    "heat_network": "в режиме глубины выше или ниже существующей трубы (верх на 3,0 м), просвет не меньше 0,5 м",
}


@dataclass
class Ctx:
    seed: int

    def rng(self, key: str) -> random.Random:
        return random.Random(f"{self.seed}:{key}")

    def shuffled(self, key: str, items: list[Any]) -> list[Any]:
        items = list(items)
        self.rng(key).shuffle(items)
        return items


class Skip(Exception):
    pass


def new_scene(
    sid: str,
    family: str,
    title: str,
    city: str | None,
    center: tuple[float, float],
    half: float,
    rule_ids: list[str],
    engineer: str,
    what: str,
    futures: str | set[Any] = "none",
    half_h: float | None = None,
) -> Scene:
    area, items = (
        cut(world(city), center, half, half_h, futures)
        if city
        else (box(center[0] - half, center[1] - (half_h or half), center[0] + half, center[1] + (half_h or half)), [])
    )
    w, h = round(2 * half), round(2 * (half_h or half))
    where = f"{CITY_NAMES[city]}, окно {w}×{h} м" if city else f"синтетическое поле {w}×{h} м"
    return Scene(sid, family, title, f"{what} {where}.", rule_ids, {}, {}, engineer, city, area, items)


def mid(a: Point, b: Point) -> tuple[float, float]:
    return (a.x + b.x) / 2, (a.y + b.y) / 2


def unit(dx: float, dy: float) -> tuple[float, float]:
    n = math.hypot(dx, dy) or 1.0
    return dx / n, dy / n


def halfplane(origin: tuple[float, float], direction: tuple[float, float], side: int, size: float = 5000.0) -> Polygon:
    ux, uy = unit(*direction)
    nx, ny = -uy * side, ux * side
    ox, oy = origin
    return Polygon(
        [
            (ox - ux * size, oy - uy * size),
            (ox + ux * size, oy + uy * size),
            (ox + ux * size + nx * size, oy + uy * size + ny * size),
            (ox - ux * size + nx * size, oy - uy * size + ny * size),
        ]
    )


def long_axis(geom: Any) -> tuple[tuple[float, float], tuple[float, float]]:
    a = math.radians(orientation(geom))
    c = geom.centroid
    return (c.x, c.y), (math.cos(a), math.sin(a))


def drop_network_except(scene: Scene, zone: Any, keep: set[Any], note: str) -> None:
    pipes = {i.id for i in scene.of("heat_network") if i.geom.intersects(zone) and i.id not in keep}
    chambers = {
        i.id
        for i in scene.of("heat_chamber")
        if i.geom.intersects(zone)
        and not any(p.geom.distance(i.geom) <= 0.5 for p in scene.of("heat_network") if p.id in keep)
    }
    scene.items = [i for i in scene.items if i.id not in pipes | chambers]
    scene.prune_chambers()
    scene.forget_routes()
    scene.edits.append(f"{note}: убрано участков сети {len(pipes)}")


def cp_item(scene: Scene, cp_id: str) -> Item:
    return scene.get(cp_id)


def house_of(scene: Scene, cp_id: str) -> Item:
    cp = scene.get(cp_id)
    return next(h for h in scene.houses() if h.id == cp.props.get("oks_id"))


def reference_length(scene: Scene, cp_id: Any, radius: float | None = None) -> float | None:
    key = str(cp_id)
    if key not in scene.reference:
        length, path = shortest_path(scene, cp_id, radius=radius)
        scene.reference[key] = {
            "shortest_m": None if length is None else round(length, 1),
            "dn_by_flow": dn_for(scene.flow(scene.get(cp_id))),
        }
        if path:
            line = to_wgs(LineString(path).simplify(CELL_M / 2)) if len(path) > 1 else None
            if line is not None:
                scene.reference[key]["path_wgs84"] = [[round(x, 7), round(y, 7)] for x, y in line.coords]
    return scene.reference[key]["shortest_m"]


def require_connected(scene: Scene, cp_ids: list[Any]) -> None:
    scene.must.setdefault("connected", []).extend(cp_ids)


def route_bound(scene: Scene, cp_id: Any) -> int:
    length = reference_length(scene, cp_id)
    if length is None:
        raise Skip(scene.id)
    return bound(length)


def straight(scene: Scene, cp_id: str) -> tuple[LineString, Item, Point]:
    cp = scene.get(cp_id).geom
    pipe, _, _ = nearest_pipe(scene, cp)
    b = nearest_points(cp, pipe.geom)[1]
    return LineString([cp, b]), pipe, b


def expect_basic(scene: Scene, cp_ids: list[str] | None = None, connected: bool = True) -> None:
    cp_ids = cp_ids or [cp.id for cp in scene.points()]
    bounds, ties = {}, {}
    for cp_id in cp_ids:
        line, pipe, tie = straight(scene, cp_id)
        if connected:
            bounds[cp_id] = route_bound(scene, cp_id)
        near = free_chambers(scene, tie)
        ties[cp_id] = (
            {"kind": "existing_chamber", "chambers": [c.id for _, c in near]}
            if near
            else {"kind": "new_chamber", "pipes": [pipe.id]}
        )
    if connected:
        require_connected(scene, cp_ids)
        scene.should.setdefault("max_route_length_m", {}).update(bounds)
        scene.should.setdefault("tie_in", {}).update(ties)
    scene.must["dn_by_flow_and_length"] = True


def expect_tree(scene: Scene, radius: float | None = None) -> None:
    lengths = [reference_length(scene, cp.id, radius) for cp in scene.points()]
    reachable = [cp.id for cp, length in zip(scene.points(), lengths, strict=True) if length is not None]
    require_connected(scene, reachable)
    blocked = [cp.id for cp, length in zip(scene.points(), lengths, strict=True) if length is None]
    if blocked:
        scene.should["unconnected"] = {cp: ["no_route", "enclosed", "inside_forbidden_zone"] for cp in blocked}
    scene.must["dn_by_flow_and_length"] = True
    scene.should["total_length_max_m"] = math.ceil(1.1 * sum(x for x in lengths if x is not None) + 20)


def place_near_pipe(
    scene: Scene,
    pipe: LineString,
    at: Point,
    offset: float,
    blocked: Any = None,
    length: float = HOUSE_L,
    depth: float = HOUSE_D,
) -> tuple[Polygon, tuple[float, float]] | None:
    s = pipe.project(at)
    p1, p2 = pipe.interpolate(max(0.0, s - 2)), pipe.interpolate(min(pipe.length, s + 2))
    ux, uy = unit(p2.x - p1.x, p2.y - p1.y)
    angle = math.degrees(math.atan2(uy, ux))
    blocked = obstacles(scene) if blocked is None else blocked
    for off in (offset, offset + 6, offset + 12):
        for side in (1, -1):
            cx, cy = at.x - uy * side * (off + depth / 2), at.y + ux * side * (off + depth / 2)
            poly = rect(cx, cy, length, depth, angle)
            if scene.area.buffer(-5).contains(poly) and not poly.intersects(blocked):
                return poly, (at.x - uy * side * (off + 1), at.y + ux * side * (off + 1))
    return None


def chambers_with(city: str, count: int) -> list[Item]:
    w = world(city)
    result = []
    for ch in w.of("heat_chamber"):
        n = 0
        for p in w.near(ch.geom, "heat_network", distance=0.5):
            ends = (Point(p.geom.coords[0]), Point(p.geom.coords[-1]))
            n += 1 if min(e.distance(ch.geom) for e in ends) <= 1.0 else 2
        if n == count:
            result.append(ch)
    return result


def tie_scene(
    ctx: Ctx,
    sid: str,
    title: str,
    city: str,
    slots: int,
    along: float,
    offset: float,
    rule_ids: list[str],
    engineer: str,
    what: str,
    check: Callable[[Scene, str, Point, Item], bool],
    pipe_ok: Callable[[Item], bool] | None = None,
    allow_roads: bool = False,
    chambers: list[Item] | None = None,
) -> Scene:
    pool = chambers if chambers is not None else chambers_with(city, slots)
    for ch in ctx.shuffled(sid, pool)[:400]:
        w = world(city)
        pipes = [p for p in w.near(ch.geom, "heat_network", distance=0.5)]
        pipe = max(pipes, key=lambda p: (p.geom.length, str(p.id)))
        if pipe.geom.length < along + 30 or (pipe_ok and not pipe_ok(pipe)):
            continue
        s = pipe.geom.project(ch.geom)
        at = pipe.geom.interpolate(s + along if s + along < pipe.geom.length else s - along)
        scene = new_scene(sid, "TIE", title, city, (at.x, at.y), 160.0, rule_ids, engineer, what)
        keep = {p.id for p in pipes}
        blocked = obstacles(scene)
        placed = place_near_pipe(scene, pipe.geom, at, offset, blocked)
        if placed is None:
            continue
        poly, cp_xy = placed
        drop_network_except(scene, poly.buffer(offset + 25), keep, "сеть у нового дома, кроме участков камеры, убрана")
        if not any(i.id == ch.id for i in scene.of("heat_chamber")):
            continue
        cp_id = scene.add_house(poly, 14, cp=cp_xy)
        line, near_pipe, tie = straight(scene, cp_id)
        if near_pipe.id not in keep or crossed(scene, line, FORBID + (() if allow_roads else ("road", "tram_tracks"))):
            continue
        if not check(scene, cp_id, tie, scene.get(ch.id)):
            continue
        return scene
    raise Skip(sid)


def tie_family(ctx: Ctx) -> Iterator[tuple[str, Callable[[], Scene]]]:
    def near(sid: str, city: str, along: float, title: str) -> Callable[[], Scene]:
        def build() -> Scene:
            def check(scene: Scene, cp_id: str, tie: Point, ch: Item) -> bool:
                return abs(tie.distance(ch.geom) - along) < 0.15 and segments_at(scene, ch) < 4

            scene = tie_scene(
                ctx,
                sid,
                title,
                city,
                3,
                along,
                18.0,
                ["ТП-2.4.камера10", "Р11", "ПР-8", "ТП-3.2", "ТП-2.2", "Р3"],
                "Инженер присоединит дом к существующей камере: место врезки на трубе в нескольких метрах "
                "от неё, а правило 10 м прямо требует использовать камеру. Новую камеру рядом не строит. "
                "Ввод от ближней к трубе стены дома, трасса одна прямая или с одним поворотом.",
                f"Новый дом в 18 м от трубы, проекция точки на трубу в {along:.0f} м от камеры с свободным местом.",
                check,
            )
            expect_basic(scene)
            tie = straight(scene, scene.points()[0].id)[2]
            ch = min(scene.of("heat_chamber"), key=lambda c: (c.geom.distance(tie), str(c.id)))
            scene.must["no_new_chamber_within_m"] = {"chambers": [ch.id], "radius_m": TIE_CHAMBER_M}
            scene.must["tie_in"] = {scene.points()[0].id: {"kind": "existing_chamber"}}
            scene.should["tie_in"] = {scene.points()[0].id: {"kind": "existing_chamber", "chambers": [ch.id]}}
            scene.should["existing_chamber_tie_in_count"] = 1
            return scene

        return build

    yield "TIE-01", near("TIE-01", "moscow", 4.0, "Камера со свободным местом в 4 м от проекции")
    yield "TIE-02", near("TIE-02", "spb", 3.0, "Камера рядом, Купчино")
    yield "TIE-03", near("TIE-03", "moscow", 9.0, "Камера в 9 м от проекции")

    def far(sid: str, city: str, along: float, title: str, edge: bool) -> Callable[[], Scene]:
        def build() -> Scene:
            def check(scene: Scene, cp_id: str, tie: Point, ch: Item) -> bool:
                near = min((c.geom.distance(tie) for c in scene.of("heat_chamber")), default=1e9)
                return along - 1.0 <= near <= along + 1.5

            scene = tie_scene(
                ctx,
                sid,
                title,
                city,
                3,
                along,
                18.0,
                ["ТП-2.4.новая", "Р11", "ПР-8", "ТП-3.2", "Р13"],
                (
                    "Проекция дома на трубу чуть дальше 10 м от камеры. Инженер поставит новую камеру "
                    "на трубе напротив дома: она дешевле врезки в существующую (3 млн против 5 млн) и трасса "
                    "короче. Сдвигать место врезки к камере ради правила 10 м нет смысла, но и это не "
                    "нарушение."
                    if edge
                    else "Ближайшая камера далеко. Инженер ставит новую камеру на трубе напротив дома и ведёт "
                    "короткую прямую трассу, к дальней камере не тянет."
                ),
                f"Новый дом у трубы, ближайшая камера в {along:.0f} м от проекции точки.",
                check,
            )
            expect_basic(scene)
            cp = scene.points()[0].id
            scene.should["tie_in"] = {cp: {"kind": "new_chamber", "pipes": scene.should["tie_in"][cp].get("pipes", [])}}
            scene.must["no_new_chamber_within_m"] = {
                "chambers": [c.id for c in scene.of("heat_chamber")],
                "radius_m": TIE_CHAMBER_M,
            }
            return scene

        return build

    yield "TIE-04", far("TIE-04", "moscow", 13.0, "Камера в 13 м: чуть дальше нормы", True)
    yield "TIE-05", far("TIE-05", "moscow", 45.0, "Камера дальше 10 м", False)
    yield "TIE-06", far("TIE-06", "spb", 60.0, "Камера дальше 10 м, Купчино", False)

    def no_chambers() -> Scene:
        sid = "TIE-07"
        c = next(
            c
            for c in ctx.shuffled(sid, candidates("moscow"))
            if c.origin == "future" and 15 < c.dist["all"] < 40 and not c.crosses["all"]
        )
        scene = from_candidate(
            ctx,
            sid,
            "TIE",
            "Камер в окне нет: врезка в трубу",
            c,
            "all",
            ["ТП-2.4.новая", "Р13", "ТП-3.2", "ТП-7"],
            "Камер поблизости нет. Инженер ставит новую камеру прямо на трубе в месте присоединения, "
            "её Ду — по наибольшему из примыканий, включая разрезанную трубу.",
            "Новый дом у трубы, все камеры в окне убраны.",
            half=180.0,
        )
        ids = {i.id for i in scene.of("heat_chamber")}
        scene.drop(ids)
        scene.edits.append(f"убраны все камеры окна ({len(ids)})")
        expect_basic(scene)
        cp = scene.points()[0].id
        scene.must["tie_in"] = {cp: {"kind": "new_chamber"}}
        return scene

    yield "TIE-07", no_chambers

    def full(sid: str, city: str) -> Callable[[], Scene]:
        def build() -> Scene:
            def check(scene: Scene, cp_id: str, tie: Point, ch: Item) -> bool:
                others = [c for c in scene.of("heat_chamber") if c.id != ch.id and c.geom.distance(tie) <= 25]
                return tie.distance(ch.geom) <= 5 and segments_at(scene, ch) == 4 and not others

            scene = tie_scene(
                ctx,
                sid,
                "Камера рядом занята: четыре примыкания",
                city,
                4,
                4.0,
                18.0,
                ["ТП-2.1.камеры", "ТП-2.4.новая", "ТП-2.4.камера10", "Р12"],
                "К камере уже примыкают четыре участка (транзитная труба через камеру считается за два). "
                "Пятое примыкание запрещено, поэтому инженер ставит новую камеру на трубе рядом, даже ближе "
                "10 м от занятой.",
                "Новый дом у трубы, ближайшая камера в 4 м, но у неё четыре примыкания.",
                check,
            )
            ch = next(
                c
                for c in scene.of("heat_chamber")
                if segments_at(scene, c) == 4 and c.geom.distance(straight(scene, scene.points()[0].id)[2]) <= 5
            )
            expect_basic(scene)
            cp = scene.points()[0].id
            scene.must["not_tie_in"] = [ch.id]
            if (
                any(
                    min(Point(p.geom.coords[0]).distance(ch.geom), Point(p.geom.coords[-1]).distance(ch.geom)) > 0.5
                    for p in scene.of("heat_network")
                    if p.geom.distance(ch.geom) <= 0.5
                )
                and "ТП-2.1.транзит" not in scene.rules
            ):
                scene.rules.append("ТП-2.1.транзит")
            scene.should["tie_in"] = {cp: {"kind": "new_chamber"}}
            return scene

        return build

    yield "TIE-08", full("TIE-08", "moscow")
    yield "TIE-10", tie_zone_road(ctx)
    yield "TIE-11", tie_zone_park(ctx)
    yield "TIE-12", tie_equal(ctx)
    yield "TIE-13", tie_dn_mix(ctx)
    yield "TIE-14", tie_two_slots(ctx)


def from_candidate(
    ctx: Ctx,
    sid: str,
    family: str,
    title: str,
    c: Candidate,
    mode: str,
    rule_ids: list[str],
    engineer: str,
    what: str,
    half: float | None = None,
) -> Scene:
    center = mid(c.house.centroid, c.tie[mode])
    half = half or min(500.0, max(150.0, c.dist[mode] / 2 + 150.0))
    futures = {c.id} if c.origin == "future" else "none"
    scene = new_scene(sid, family, title, c.city, center, half, rule_ids, engineer, what, futures)
    if c.origin == "garage":
        scene.drop(set(c.replaces))
        scene.edits.append(f"гаражи {', '.join(map(str, c.replaces))} снесены, на их месте новый дом")
        scene.add_house(c.house, c.levels, house_id=f"oks-{c.id}")
    if mode == "restored":
        keep = {p.id for p in scene.of("heat_network") if p.props.get("_source") in ("restored", "real")}
        scene.keep_network(keep, "оставлена магистральная сеть по схеме, вводы к существующим домам убраны")
    return scene


def tie_zone_road(ctx: Ctx) -> Callable[[], Scene]:
    def build() -> Scene:
        sid = "TIE-10"
        w = world("moscow")
        for road in ctx.shuffled(
            sid, [r for r in w.of("road") if r.props.get("highway") in ("secondary", "tertiary") and r.geom.area > 1500]
        ):
            pipes = [p for p in w.near(road.geom, "heat_network") if p.geom.intersection(road.geom).length > 60]
            if not pipes:
                continue
            pipe = pipes[0]
            inside = pipe.geom.intersection(road.geom)
            at = inside.interpolate(0.5, normalized=True)
            scene = new_scene(
                sid,
                "TIE",
                "Место врезки под проезжей частью",
                "moscow",
                (at.x, at.y),
                180.0,
                ["ТП-2.4.новая", "Р11", "ТП-4.road", "ТП-4.спецпроход", "Р6", "Р29-13"],
                "Труба идёт под проезжей частью, ближайшая к дому точка — посреди дороги. По разъяснению "
                "29.09 (п. 13) новая камера внутри полигона дороги допустима; тогда участок от камеры до "
                "выхода из дороги — один прямой спецпроход, он продолжается на 3 м за границей со стороны "
                "выхода, а со стороны камеры дополнительные 3 м не нужны. Инженер может и сдвинуть врезку "
                "вдоль трубы за край дороги, если так дешевле.",
                "Магистраль под улицей, новый дом у края улицы.",
            )
            placed = place_near_pipe(scene, pipe.geom, at, road.geom.distance(at) + 14 + 9)
            if placed is None:
                continue
            house, cp_xy = placed
            drop_network_except(scene, house.buffer(60), {pipe.id}, "вводы у нового дома убраны")
            cp = scene.add_house(house, 14, cp=cp_xy)
            line, near_pipe, tie = straight(scene, cp)
            if near_pipe.id != pipe.id or not road.geom.buffer(3).contains(tie):
                continue
            expect_basic(scene)
            scene.must["no_turn_in_special"] = True
            scene.must["crossing_angle_min_deg"] = {"road": 45}
            return scene
        raise Skip(sid)

    return build


def tie_zone_park(ctx: Ctx) -> Callable[[], Scene]:
    def build() -> Scene:
        sid = "TIE-11"
        w = world("moscow")
        for park in ctx.shuffled(sid, [p for p in w.of("park") if 3000 < p.geom.area < 80000]):
            pipes = [p for p in w.near(park.geom, "heat_network") if p.geom.intersection(park.geom).length > 50]
            if not pipes:
                continue
            pipe = pipes[0]
            inside = pipe.geom.intersection(park.geom)
            at = inside.interpolate(0.5, normalized=True)
            edge = park.geom.boundary.interpolate(park.geom.boundary.project(at))
            scene = new_scene(
                sid,
                "TIE",
                "Ближайшая точка трубы внутри парка",
                "moscow",
                (at.x, at.y),
                200.0,
                ["ТП-4.park", "ТП-2.4.новая", "Р11", "ТП-2.5"],
                "Труба проходит через парк, и кратчайший путь ведёт к ней через парк. Пересекать парк "
                "нельзя, поэтому инженер врезается в трубу там, где она выходит из парка, или в другую "
                "трубу, и ведёт трассу по краю парка с отступом не меньше 1 м.",
                "Труба пересекает парк, новый дом за границей парка.",
            )
            dx, dy = unit(edge.x - at.x, edge.y - at.y)
            target = (edge.x + dx * 25, edge.y + dy * 25)
            house = free_spot(scene, target, HOUSE_L, HOUSE_D, math.degrees(math.atan2(dy, dx)) + 90, radius=40)
            if house is None:
                continue
            drop_network_except(scene, house.buffer(70).difference(park.geom), {pipe.id}, "вводы у нового дома убраны")
            cp = scene.add_house(house, 14)
            line, near_pipe, tie = straight(scene, cp)
            if near_pipe.id != pipe.id or not park.geom.contains(tie):
                continue
            expect_basic(scene)
            scene.must["no_cross"] = [park.id]
            scene.should["tie_outside_zone"] = {"restrictions": [park.id], "margin_m": 1.0}
            return scene
        raise Skip(sid)

    return build


def tie_equal(ctx: Ctx) -> Callable[[], Scene]:
    def build() -> Scene:
        sid = "TIE-12"

        def check(scene: Scene, cp_id: str, tie: Point, ch: Item) -> bool:
            return tie.distance(ch.geom) <= 7

        scene = tie_scene(
            ctx,
            sid,
            "Две камеры на равном расстоянии",
            "moscow",
            3,
            6.0,
            18.0,
            ["ТП-2.4.выбор", "Р11", "ПР-8", "ТП-2.4.камера10"],
            "Две свободные камеры в 6 м от места врезки по разные стороны. Правила разрешают выбрать "
            "любую. Инженер возьмёт одну, новую камеру между ними строить не будет.",
            "Новый дом напротив середины между двумя камерами в 12 м друг от друга.",
            check,
        )
        cp = scene.points()[0].id
        line, pipe, tie = straight(scene, cp)
        first = min(scene.of("heat_chamber"), key=lambda c: c.geom.distance(tie))
        s = pipe.geom.project(first.geom)
        t = pipe.geom.project(tie)
        other = pipe.geom.interpolate(2 * t - s)
        new_id = scene.add("heat_chamber", other, f"hc-{sid.lower()}-twin")
        scene.edits.append(f"на трубе {pipe.id} добавлена камера {new_id} симметрично существующей {first.id}")
        expect_basic(scene)
        scene.must["no_new_chamber_within_m"] = {"chambers": [first.id, new_id], "radius_m": TIE_CHAMBER_M}
        scene.should["tie_in"] = {cp: {"kind": "existing_chamber", "chambers": [first.id, new_id]}}
        return scene

    return build


def tie_dn_mix(ctx: Ctx) -> Callable[[], Scene]:
    def build() -> Scene:
        sid = "TIE-13"
        w = world("moscow")
        bigs = [p for p in w.of("heat_network") if p.props["diameter"] >= 500 and p.geom.length > 80]
        for big in ctx.shuffled(sid, bigs)[:300]:
            smalls = [
                p
                for p in w.near(big.geom, "heat_network", distance=30)
                if p.props["diameter"] <= 80 and p.geom.length > 40 and p.geom.distance(big.geom) > 8
            ]
            if not smalls:
                continue
            small = smalls[0]
            at = small.geom.interpolate(small.geom.project(big.geom.centroid))
            scene = new_scene(
                sid,
                "TIE",
                "Рядом трубы Ду 50–80 и Ду 500+",
                "moscow",
                (at.x, at.y),
                180.0,
                ["ТП-3.2", "ТП-2.4.новая", "ТП-2.4.без_реконструкции", "Р11", "Р14"],
                "Рядом с домом две трубы: магистраль Ду "
                + str(big.props["diameter"])
                + " и ввод Ду "
                + str(small.props["diameter"])
                + ". Резерв существующей сети не считается, присоединиться "
                "можно к любой. Новая камера на магистрали дороже (по наибольшему Ду примыканий), поэтому "
                "при близких расстояниях инженер выберет малую трубу.",
                "Новый дом между магистралью и вводом малого диаметра.",
            )
            blocked = obstacles(scene)
            for off in (14.0, 20.0):
                placed = place_near_pipe(scene, small.geom, at, off, blocked)
                if placed is None:
                    continue
                house, cp_xy = placed
                if house.distance(big.geom) > house.distance(small.geom) + 25:
                    continue
                if crossed(scene, LineString(nearest_points(house, small.geom)), FORBID + ("road",)):
                    continue
                cp = scene.add_house(house, 9, cp=cp_xy)
                expect_basic(scene)
                scene.should["tie_in"] = {cp: {"kind": "new_chamber", "pipes": [small.id]}}
                return scene
        raise Skip(sid)

    return build


def tie_two_slots(ctx: Ctx) -> Callable[[], Scene]:
    def build() -> Scene:
        from heatscenes.extra import house_at, pipe, synth, xy

        scene = synth(
            "TIE-14",
            "TIE",
            "Камера с двумя свободными местами и три дома",
            300,
            160,
            ["ТП-2.1.камеры", "ТП-2.1.транзит", "Р12", "ПР-8", "ТП-3.2", "ТП-2.1.дерево"],
            "Труба проходит через камеру насквозь: это два примыкания, свободных мест два. У трёх домов проекция "
            "ввода на трубу не дальше 4 м от камеры, и по правилу 10 м каждый просится в неё. Каждый новый участок, "
            "кончающийся в камере, — отдельная врезка за 5 млн, а мест только два. Инженер заведёт в камеру не "
            "больше двух линий: одну с ветвлением в новой камере у домов либо две, а третий дом подключит новой "
            "камерой на трубе дальше 10 м или через ветку.",
            "Синтетика: труба Ду 150 с транзитной камерой, два дома с одной стороны, один с другой.",
        )
        pipe(scene, "hn-1", 150, (-150, 0), (150, 0))
        ch = scene.add("heat_chamber", Point(*xy(0, 0)), "hc-1")
        cps = [
            house_at(scene, -22, 12, -2, 24, (-4, 13), 10.0),
            house_at(scene, 2, 12, 22, 24, (4, 13), 10.0),
            house_at(scene, -10, -24, 10, -12, (0, -13), 10.0),
        ]
        scene.must.update({"connected": cps, "max_segments_at": {ch: 4}, "dn_by_flow_and_length": True})
        scene.should["existing_chamber_tie_in_count_max"] = 2
        return scene

    return build


def in_forbid(city: str, geom: Any) -> bool:
    return bool(world(city).near(geom.buffer(2.0), *FORBID))


def pick(ctx: Ctx, key: str, pool: list[Candidate], pred: Callable[[Candidate], bool]) -> Candidate:
    found = [c for c in ctx.shuffled(key, pool) if pred(c) and not in_forbid(c.city, c.house)]
    if not found:
        raise Skip(key)
    return found[0]


def single(
    ctx: Ctx,
    sid: str,
    family: str,
    title: str,
    city: str,
    pred: Callable[[Candidate], bool],
    rule_ids: list[str],
    engineer: str,
    what: str,
    mode: str = "all",
    half: float | None = None,
) -> Scene:
    c = pick(ctx, sid, candidates(city), pred)
    scene = from_candidate(ctx, sid, family, title, c, mode, rule_ids, engineer, what, half)
    expect_basic(scene)
    return scene


def site_houses(city: str) -> dict[str, list[Item]]:
    sites: dict[str, list[Item]] = {}
    for f in world(city).of("oks_future"):
        sites.setdefault(f.props.get("_site") or f.id, []).append(f)
    return sites


def site_scene(
    ctx: Ctx,
    sid: str,
    title: str,
    city: str,
    size: tuple[int, int],
    rule_ids: list[str],
    engineer: str,
    what: str,
    nth: int = 0,
) -> Scene:
    sites = sorted(
        ((k, v) for k, v in site_houses(city).items() if size[0] <= len(v) <= size[1]),
        key=lambda kv: (-len(kv[1]), kv[0]),
    )
    if len(sites) <= nth:
        raise Skip(sid)
    _, houses = sites[nth]
    zone = shapely.union_all([h.geom for h in houses])
    minx, miny, maxx, maxy = zone.bounds
    half = min(500.0, max(150.0, (maxx - minx) / 2 + 120, (maxy - miny) / 2 + 120))
    c = zone.centroid
    scene = new_scene(
        sid, "SCALE", title, city, (c.x, c.y), half, rule_ids, engineer, what, futures={h.id for h in houses}
    )
    scene.title = title.replace("{n}", str(len(scene.points())))
    expect_tree(scene)
    return scene


def scale_family(ctx: Ctx) -> Iterator[tuple[str, Callable[[], Scene]]]:
    eng_one = (
        "Один дом у сети. Инженер ведёт короткий ввод от ближней к сети стены к ближайшей трубе или камере, "
        "без лишних поворотов, по оси застройки."
    )
    yield (
        "SCALE-01",
        lambda: single(
            ctx,
            "SCALE-01",
            "SCALE",
            "Один новый дом",
            "moscow",
            lambda c: c.origin == "future" and 8 < c.dist["all"] < 35 and not c.crosses["all"],
            ["ТП-1", "ТП-1.1", "ТП-1.2", "ТП-2.1.пара", "ТП-2.2", "ТП-2.3.ду", "ТП-6.стоимость", "ТП-6.S", "ТП-7"],
            eng_one,
            "Новый дом на реальной стройке у существующей сети.",
        ),
    )
    yield (
        "SCALE-02",
        lambda: single(
            ctx,
            "SCALE-02",
            "SCALE",
            "Один новый дом, Купчино",
            "spb",
            lambda c: 8 < c.dist["all"] < 60 and c.kinds("all") <= {"road", "power_cable"},
            ["ТП-1", "ТП-1.2", "ТП-2.2", "ТП-7"],
            eng_one,
            "Новый дом у существующей сети.",
        ),
    )

    def numeric() -> Scene:
        scene = single(
            ctx,
            "SCALE-03",
            "SCALE",
            "Числовые id, как в датасете организаторов",
            "moscow",
            lambda c: c.origin == "garage" and c.dist["all"] < 60,
            ["ТП-1.id", "ТП-1", "ТП-7"],
            "Тот же расчёт, что и со строковыми id. Ссылки в выходе (start_node_id, end_node_id, "
            "unconnected_oks_ids) сохраняют числовой тип.",
            "Все id входа заменены целыми числами.",
        )
        scene.numeric_ids = True
        scene.edits.append("все id заменены порядковыми числами")
        scene.must["ids_numeric_in_output"] = True
        return scene

    yield "SCALE-03", numeric

    def pair(sid: str, city: str) -> Callable[[], Scene]:
        def build() -> Scene:
            houses = [h for hs in site_houses(city).values() for h in hs]
            pairs = []
            for a in ctx.shuffled(sid, houses):
                near = [b for b in houses if b.id != a.id and 10 < a.geom.distance(b.geom) < 40]
                if near:
                    pairs.append((a, near[0]))
            for a, b in pairs[:20]:
                scene = pair_scene(sid, city, a, b)
                lengths = [reference_length(scene, cp.id) for cp in scene.points()]
                straight_m = [straight(scene, cp.id)[0].length for cp in scene.points()]
                if all(x is not None and x <= 2 * d + 60 for x, d in zip(lengths, straight_m, strict=True)):
                    expect_basic(scene)
                    return scene
            raise Skip(sid)

        return build

    def pair_scene(sid: str, city: str, a: Item, b: Item) -> Scene:
        c = shapely.union_all([a.geom, b.geom]).centroid
        return new_scene(
            sid,
            "SCALE",
            "Пара соседних домов" + (", Купчино" if city == "spb" else ""),
            city,
            (c.x, c.y),
            200.0,
            ["ТП-2.1.дерево", "ТП-2.1.камеры", "ТП-2.3.расход", "ТП-3.2"],
            "Два дома рядом. Инженер сравнит общий ввод с камерой ветвления у домов и два отдельных "
            "ввода и выберет дешёвый; при близкой сети разумны оба. Ветвление только в новой камере, "
            "расход общего участка равен сумме двух домов.",
            "Два новых дома в 10–40 м друг от друга.",
            futures={a.id, b.id},
        )

    yield "SCALE-04", pair("SCALE-04", "moscow")
    yield "SCALE-05", pair("SCALE-05", "spb")
    eng_q = (
        "Квартал новых домов. Инженер строит одно-два дерева: магистраль по проезду внутри квартала, ветвления "
        "в новых камерах у групп домов, Ду растёт к месту присоединения. Трассы вдоль осей застройки, без "
        "пересечений между собой."
    )
    rq = [
        "ТП-2.1.дерево",
        "ТП-2.1.камеры",
        "ТП-2.1.пересечения",
        "ТП-2.3.расход",
        "ТП-2.3.неубывание",
        "ТП-2.3.длина",
        "Р2",
        "ТП-6.варианты",
    ]
    yield (
        "SCALE-06",
        lambda: site_scene(
            ctx,
            "SCALE-06",
            "Квартал из 5–9 домов",
            "moscow",
            (5, 9),
            rq,
            eng_q,
            "Застройка участка: 5–9 новых домов.",
            0,
        ),
    )
    yield (
        "SCALE-07",
        lambda: site_scene(
            ctx,
            "SCALE-07",
            "Квартал из 15–20 домов",
            "moscow",
            (15, 20),
            rq,
            eng_q,
            "Застройка участка: 15–20 новых домов.",
            0,
        ),
    )
    yield (
        "SCALE-08",
        lambda: site_scene(
            ctx,
            "SCALE-08",
            "Квартал из 17–19 домов, второй участок",
            "moscow",
            (15, 20),
            rq,
            eng_q,
            "Застройка участка: 15–20 новых домов.",
            2,
        ),
    )
    yield (
        "SCALE-09",
        lambda: site_scene(
            ctx,
            "SCALE-09",
            "Квартал из {n} домов, Купчино",
            "spb",
            (5, 12),
            rq,
            eng_q,
            "Застройка участка новыми домами.",
            0,
        ),
    )
    yield (
        "SCALE-10",
        lambda: site_scene(
            ctx, "SCALE-10", "Микрорайон из 35 домов, Купчино", "spb", (30, 40), rq, eng_q, "Застройка микрорайона.", 0
        ),
    )
    yield (
        "SCALE-11",
        lambda: site_scene(
            ctx,
            "SCALE-11",
            "Район из 74 домов",
            "moscow",
            (50, 300),
            rq + ["ПР-4"],
            eng_q,
            "Большая площадка застройки, 74 дома.",
            0,
        ),
    )
    yield "SCALE-12", lambda: renovation(ctx, "SCALE-12", 450.0, 60, (5,))
    yield "SCALE-13", lambda: renovation(ctx, "SCALE-13", 1500.0, 300, (5, 9, 12, 14, 16, 17), big=True)
    yield "SCALE-14", lambda: sides_of_pipe(ctx)
    yield "SCALE-15", lambda: sides_of_street(ctx, "SCALE-15", "moscow")
    yield "SCALE-16", lambda: sides_of_street(ctx, "SCALE-16", "spb")


def renovation(ctx: Ctx, sid: str, half: float, target: int, levels: tuple[int, ...], big: bool = False) -> Scene:
    w = world("moscow")
    old = [
        i
        for i in w.of("oks_existing")
        if i.props.get("building") in ("apartments", "residential")
        and i.props.get("levels") in levels
        and i.geom.geom_type == "Polygon"
        and i.geom.area > 300
    ]
    tree = shapely.STRtree([i.geom.centroid for i in old])
    best = None
    for o in ctx.shuffled(sid, old)[:300]:
        n = len(
            tree.query(
                box(
                    o.geom.centroid.x - half,
                    o.geom.centroid.y - half,
                    o.geom.centroid.x + half,
                    o.geom.centroid.y + half,
                )
            )
        )
        if best is None or n > best[0]:
            best = (n, o)
    n, o = best
    if n < target:
        raise Skip(sid)
    scene = new_scene(
        sid,
        "SCALE",
        "Реновация: больше 500 домов, городской режим" if big else "Реновация: 50–300 домов",
        "moscow",
        (o.geom.centroid.x, o.geom.centroid.y),
        half,
        ["ПР-4", "ТП-2.1.дерево", "ТП-2.3.длина", "ТП-2.1.пересечения", "ТП-6.варианты", "Р17"],
        "Пятиэтажки квартала заменены новыми домами той же формы, как в программе реновации. Инженер "
        "подключает все дома к существующей сети района, группирует соседние дома в деревья по "
        "проездам и не оставляет без сети дома, к которым есть проход. "
        + ("Сцена больше 500 точек: сервис уходит в городской режим." if big else ""),
        "Старые дома окна заменены новыми (реновация).",
        futures="all" if big else "none",
    )
    chosen = [
        i
        for i in scene.of("oks_existing")
        if i.props.get("building") in ("apartments", "residential")
        and i.props.get("levels") in levels
        and i.geom.geom_type == "Polygon"
        and i.geom.area > 300
        and scene.area.contains(i.geom.centroid)
    ]
    scene.drop({i.id for i in chosen})
    for i in chosen:
        scene.add_house(i.geom, 14, house_id=f"oks-ren-{i.id}")
    scene.edits.append(f"{len(chosen)} домов в {levels} этажей заменены новыми 14-этажными на том же месте")
    expect_tree(scene, radius=350.0 if big else None)
    if big:
        scene.engineer += (
            " Эталонный путь ищется в квадрате 700 м вокруг каждой точки; точки без пути в нём стоят в "
            "should.unconnected."
        )
    if len(scene.points()) < (501 if big else 50):
        raise Skip(sid)
    return scene


def sides_of_pipe(ctx: Ctx) -> Scene:
    sid = "SCALE-14"
    w = world("moscow")
    for pipe in ctx.shuffled(
        sid, [p for p in w.of("heat_network") if p.props["diameter"] >= 150 and p.geom.length > 120]
    ):
        at = pipe.geom.interpolate(0.5, normalized=True)
        scene = new_scene(
            sid,
            "SCALE",
            "Дома по разные стороны трубы",
            "moscow",
            (at.x, at.y),
            180.0,
            ["ТП-2.1.камеры", "ТП-4.heat_network", "Р10", "ТП-2.4.новая", "ТП-3.2"],
            "Труба проходит между двумя новыми домами. Инженер поставит одну новую камеру на трубе "
            "между ними и выведет из неё две ветки в разные стороны (четыре примыкания, предел), "
            "либо две отдельные камеры. Пересекать трубу ради общего дерева незачем.",
            "Два новых дома по разные стороны магистрали.",
        )
        blocked = obstacles(scene)
        s = pipe.geom.project(at)
        p1, p2 = pipe.geom.interpolate(s - 2), pipe.geom.interpolate(s + 2)
        ux, uy = unit(p2.x - p1.x, p2.y - p1.y)
        angle = math.degrees(math.atan2(uy, ux))
        placed = []
        for side in (1, -1):
            for off in (16, 24, 32):
                cx, cy = at.x - uy * side * (off + 8), at.y + ux * side * (off + 8)
                poly = rect(cx, cy, 40, 14, angle)
                if scene.area.buffer(-5).contains(poly) and not poly.intersects(blocked):
                    placed.append((poly, (at.x - uy * side * (off + 1), at.y + ux * side * (off + 1))))
                    break
        if len(placed) < 2:
            continue
        drop_network_except(
            scene, shapely.union_all([p.buffer(40) for p, _ in placed]), {pipe.id}, "вводы у новых домов убраны"
        )
        cps = [scene.add_house(poly, 12, cp=cp) for poly, cp in placed]
        if any(straight(scene, c)[1].id != pipe.id for c in cps):
            continue
        expect_basic(scene)
        scene.should["crossings_max"] = {"heat_network": 0}
        scene.should["new_chambers_on_pipe_max"] = {pipe.id: 1}
        return scene
    raise Skip(sid)


def sides_of_street(ctx: Ctx, sid: str, city: str) -> Scene:
    w = world(city)
    for road in ctx.shuffled(
        sid,
        [
            r
            for r in w.of("road")
            if r.props.get("highway") in ("residential", "tertiary", "secondary") and r.geom.area > 1500
        ],
    ):
        origin, u = long_axis(road.geom)
        o = Point(origin)
        half_w = road.geom.boundary.distance(o) if road.geom.contains(o) else None
        if half_w is None:
            continue
        scene = new_scene(
            sid,
            "SCALE",
            "Дома по разные стороны улицы" + (", Купчино" if city == "spb" else ""),
            city,
            origin,
            220.0,
            ["ТП-4.road", "ТП-4.угол", "ТП-4.спецпроход", "ТП-2.1.дерево", "Р6"],
            "Сеть есть только на одной стороне улицы. Дом на этой стороне подключается напрямую, дом "
            "напротив — одним переходом улицы под углом, близким к прямому. Инженер не тянет общий "
            "ввод через улицу дважды.",
            "Новые дома напротив друг друга через улицу, сеть только с одной стороны.",
        )
        blocked = obstacles(scene)
        houses = []
        for side in (1, -1):
            nx, ny = -u[1] * side, u[0] * side
            target = (o.x + nx * (half_w + 30), o.y + ny * (half_w + 30))
            poly = free_spot(
                scene,
                target,
                40,
                14,
                math.degrees(math.atan2(u[1], u[0])),
                blocked=blocked,
                radius=30,
                allow=halfplane(origin, u, side),
            )
            if poly is None:
                break
            houses.append((side, poly))
        if len(houses) < 2:
            continue
        drop_network_except(
            scene, halfplane(origin, u, -1).intersection(scene.area), set(), "сеть на одной стороне улицы убрана"
        )
        if not [p for p in scene.of("heat_network") if p.geom.intersects(halfplane(origin, u, 1))]:
            continue
        for _, poly in houses:
            scene.add_house(poly, 12)
        expect_basic(scene)
        scene.should["crossings_max"] = {"road": 2}
        scene.must["crossing_angle_min_deg"] = {"road": 45}
        return scene
    raise Skip(sid)


def splits(scene: Scene, kinds: tuple[str, ...], a: Point, b: Point) -> bool:
    walls = [
        i.geom
        for i in scene.items
        if i.kind in kinds and i.geom is not None and i.geom.geom_type in ("Polygon", "MultiPolygon")
    ]
    if not walls:
        return False
    free = scene.area.buffer(CLIP_PAD_M + 1).difference(shapely.union_all(walls).buffer(0.5))
    parts = list(getattr(free, "geoms", [free]))
    pa = next((p for p in parts if p.distance(a) < 1.0), None)
    pb = next((p for p in parts if p.distance(b) < 1.0), None)
    return pa is not None and pb is not None and pa is not pb


def across(
    ctx: Ctx,
    sid: str,
    family: str,
    title: str,
    city: str,
    pool: list[Item],
    rule_ids: list[str],
    engineer: str,
    what: str,
    gap: float = 25.0,
    half: float = 220.0,
    size: tuple[float, float] = (40, 14),
    need_cross: bool = True,
    need_route: bool = True,
    max_detour: float | None = None,
    key: str | None = None,
) -> tuple[Scene, Item, str]:
    for ob in ctx.shuffled(key or sid, pool)[:120]:
        origin, u = long_axis(ob.geom)
        o = Point(origin)
        for side in (1, -1):
            nx, ny = -u[1] * side, u[0] * side
            ray = LineString([o, (o.x + nx * 400, o.y + ny * 400)])
            hit = ray.intersection(ob.geom.boundary)
            if hit.is_empty:
                continue
            e = min((Point(xy) for xy in shapely.get_coordinates(hit)), key=o.distance)
            target = (e.x + nx * (gap + size[1] / 2), e.y + ny * (gap + size[1] / 2))
            scene = new_scene(sid, family, title, city, origin, half, rule_ids, engineer, what)
            house = free_spot(
                scene,
                target,
                size[0],
                size[1],
                math.degrees(math.atan2(u[1], u[0])),
                radius=30,
                allow=halfplane(origin, u, side),
            )
            if house is None:
                continue
            drop_network_except(
                scene,
                halfplane(origin, u, side).intersection(scene.area),
                set(),
                f"сеть на стороне нового дома убрана, остаётся за объектом {ob.id}",
            )
            if not scene.of("heat_network"):
                continue
            cp = scene.add_house(house, 12)
            line, pipe, tie = straight(scene, cp)
            if need_cross and not line.intersects(ob.geom):
                continue
            if need_route or max_detour:
                length = reference_length(scene, cp)
                if length is None or (max_detour and length > max_detour * line.length + 40):
                    continue
            return scene, ob, cp
    raise Skip(sid)


def special_expect(scene: Scene, cp: str, kind: str, ob: Item) -> None:
    line, _, tie = straight(scene, cp)
    n = len([i for i in crossed(scene, line, (kind,))])
    scene.must["connected"] = [cp]
    if kind in ("road", "tram_tracks"):
        scene.must["crossing_angle_min_deg"] = {kind: 45}
    scene.must["special_kspec"] = {kind: rules()["restrictions"][kind]["k_special"]}
    if splits(scene, (kind,), scene.get(cp).geom, tie):
        scene.should["crossings_min"] = {kind: 1}
    scene.should["crossings_max"] = {kind: max(1, n)}
    scene.should["max_route_length_m"] = {cp: route_bound(scene, cp)}
    scene.must["dn_by_flow_and_length"] = True
    scene.engineer += facts(scene, cp, ob)


def forbid_expect(scene: Scene, cp: str, kind: str, strict: bool = True) -> None:
    blocking = [i.id for i in scene.items if i.kind == kind and i.geom is not None]
    (scene.must if strict else scene.should)["no_cross"] = blocking
    if reference_length(scene, cp) is None:
        extend_barrier(scene, kind)
        scene.must["unconnected"] = {cp: ["no_route", "enclosed"]}
    else:
        require_connected(scene, [cp])
        scene.should["max_route_length_m"] = {cp: route_bound(scene, cp)}
        scene.must["dn_by_flow_and_length"] = True
    scene.engineer += facts(scene, cp, None)


def facts(scene: Scene, cp: str, ob: Item | None) -> str:
    line, _, _ = straight(scene, cp)
    ref = reference_length(scene, cp)
    parts = [f" Расход {scene.flow(scene.get(cp)):g} т/ч, до ближайшей трубы по прямой {line.length:.0f} м"]
    if ob is not None and ob.geom.geom_type in ("Polygon", "MultiPolygon"):
        width = 2 * ob.geom.area / max(ob.geom.length, 1.0)
        tag = ob.props.get("highway")
        parts.append(f"объект {ob.id}" + (f" ({tag})" if tag else "") + f" средней ширины {width:.0f} м")
    parts.append(
        f"кратчайший допустимый путь {ref:.0f} м." if ref is not None else "допустимого пути в пределах данных нет."
    )
    return ", ".join(parts)


def extend_barrier(scene: Scene, kind: str) -> None:
    frame = scene.area.buffer(CLIP_PAD_M, join_style="mitre")
    geoms = [i.geom for i in scene.of(kind) if i.geom is not None and i.geom.geom_type in ("Polygon", "MultiPolygon")]
    if not geoms:
        return
    g = shapely.union_all(geoms)
    c = frame.centroid
    ring = list(frame.exterior.coords)
    added = 0
    for a, b in zip(ring, ring[1:], strict=False):
        edge = LineString([a, b])
        touch = g.intersection(edge.buffer(0.05))
        for part in getattr(touch, "geoms", [touch]):
            if part.is_empty or part.area < 0.01:
                continue
            ts = [edge.project(Point(xy)) for xy in shapely.get_coordinates(part)]
            p0, p1 = edge.interpolate(min(ts)), edge.interpolate(max(ts))
            if p0.distance(p1) < 1.0:
                continue
            d = unit(b[0] - a[0], b[1] - a[1])
            n = (-d[1], d[0])
            mid = edge.interpolate(0.5, normalized=True)
            if (mid.x - c.x) * n[0] + (mid.y - c.y) * n[1] < 0:
                n = (-n[0], -n[1])
            strip = Polygon(
                [
                    (p0.x, p0.y),
                    (p1.x, p1.y),
                    (p1.x + n[0] * EXTEND_M, p1.y + n[1] * EXTEND_M),
                    (p0.x + n[0] * EXTEND_M, p0.y + n[1] * EXTEND_M),
                ]
            )
            added += 1
            scene.add(kind, strip, f"{kind}-ext-{scene.id.lower()}-{added}")
    if added:
        scene.edits.append(
            f"{kind}: {added} продолжений прямыми полосами на {EXTEND_M / 1000:g} км за охват, "
            "чтобы обход превышал предельную длину Ду 1400"
        )


def roads_of(city: str, classes: tuple[str, ...], wmin: float, wmax: float) -> list[Item]:
    return [
        r
        for r in world(city).of("road")
        if r.props.get("highway") in classes
        and r.geom.area > 1200
        and wmin <= 2 * r.geom.area / max(r.geom.length, 1) <= wmax
    ]


def cross_family(ctx: Ctx) -> Iterator[tuple[str, Callable[[], Scene]]]:
    road_rules = [
        "ТП-4.road",
        "ТП-4.угол",
        "ТП-4.спецпроход",
        "ТП-4.отступ",
        "ТП-2.1.узел",
        "Р6",
        "Р7",
        "ПР-5",
        "ТП-6.стоимость",
    ]
    eng_road = (
        "Сеть только за улицей. Инженер пересекает улицу один раз, коротким прямым участком почти под прямым "
        "углом (не меньше 45°), с техническими узлами на границах зоны (полигон плюс 3 м), и врезается в "
        "ближайшую трубу на той стороне. Вдоль проезжей части в зоне отступа 1,5 м не идёт."
    )

    def road(
        sid: str, city: str, classes: tuple[str, ...], wmin: float, wmax: float, title: str
    ) -> Callable[[], Scene]:
        def build() -> Scene:
            scene, ob, cp = across(
                ctx,
                sid,
                "CROSS",
                title,
                city,
                roads_of(city, classes, wmin, wmax),
                road_rules,
                eng_road,
                f"Новый дом через улицу ({'/'.join(classes)}) от сети.",
                max_detour=1.6,
            )
            special_expect(scene, cp, "road", ob)
            return scene

        return build

    yield "CROSS-01", road("CROSS-01", "moscow", ("service",), 3, 6.5, "Переход узкого проезда")
    yield "CROSS-02", road("CROSS-02", "moscow", ("residential", "unclassified"), 6, 9, "Переход жилой улицы")
    yield "CROSS-05", road("CROSS-05", "moscow", ("primary", "trunk"), 16, 60, "Переход многополосного проспекта")

    def oblique() -> Scene:
        sid = "CROSS-08"
        scene, ob, cp = across(
            ctx,
            sid,
            "CROSS",
            "Сеть за улицей наискосок",
            "moscow",
            roads_of("moscow", ("tertiary", "secondary", "residential"), 7, 20),
            road_rules + ["ТП-2.1.повороты"],
            "Ближайшая труба за улицей сдвинута вдоль улицы, прямая к ней пересекает дорогу под "
            "острым углом. Инженер переходит улицу под прямым углом (не меньше 45°) и дальше идёт "
            "вдоль улицы по другой стороне, либо идёт вдоль своей стороны и переходит напротив "
            "трубы.",
            "Новый дом через улицу, сеть напротив дома убрана, ближайшая — наискосок.",
        )
        origin, u = long_axis(ob.geom)
        house = house_of(scene, cp).geom.centroid
        corridor = LineString(
            [(house.x - u[1] * 300 - u[0] * 0, house.y + u[0] * 300), (house.x + u[1] * 300, house.y - u[0] * 300)]
        ).buffer(70)
        drop_network_except(scene, corridor, set(), "сеть напротив дома убрана")
        if not scene.of("heat_network"):
            raise Skip(sid)
        special_expect(scene, cp, "road", ob)
        return scene

    yield "CROSS-08", oblique
    yield "CROSS-09", lambda: junction(ctx)

    def tram(sid: str, city: str) -> Callable[[], Scene]:
        def build() -> Scene:
            pool = [t for t in world(city).of("tram_tracks") if t.geom.area > 400]
            scene, ob, cp = across(
                ctx,
                sid,
                "CROSS",
                "Переход трамвайных путей" + (", Купчино" if city == "spb" else ""),
                city,
                pool,
                ["ТП-4.tram_tracks", "ТП-4.угол", "ТП-4.наложение", "Р8", "ТП-4.road", "ТП-4.спецпроход", "ПР-10"],
                "Сеть за трамвайными путями, обычно вместе с проезжей частью. Инженер пересекает "
                "пути и дорогу одним прямым участком под углом не меньше 45°; там, где зоны "
                "дороги и трамвая накладываются, берётся наибольший коэффициент 1,75, а на "
                "границах смены набора объектов ставятся технические узлы.",
                "Новый дом через трамвайную линию от сети.",
            )
            special_expect(scene, cp, "tram_tracks", ob)
            return scene

        return build

    yield "CROSS-10", tram("CROSS-10", "moscow")
    yield "CROSS-11", tram("CROSS-11", "spb")

    def rail(sid: str, city: str, title: str) -> Callable[[], Scene]:
        def build() -> Scene:
            pool = [r for r in world(city).of("railway") if r.geom.area > 3000]
            scene, ob, cp = across(
                ctx,
                sid,
                "CROSS",
                title,
                city,
                pool,
                ["ТП-4.railway", "ТП-2.5", "ТП-2.1.граница", "Р15", "ТП-6.штраф"],
                "Железную дорогу пересекать нельзя (таблица 2), а сеть только за ней. Если путь "
                "в обход полотна в пределах района есть, инженер обходит его с отступом 1 м; если "
                "полотно перекрывает район, оно продлено на 7 км за окно, обход длиннее предельной длины "
                "Ду 1400, и дом остаётся без сети со штрафом.",
                "Новый дом за железной дорогой от сети.",
                half=260.0,
                need_route=False,
            )
            forbid_expect(scene, cp, "railway")
            scene.rules.append("Р29-3")
            if "unconnected" not in scene.must:
                scene.title += ": обход по краю данных"
                scene.engineer += (
                    " Полигоны ж/д и застройка обрезаны по краю окна, и допустимый путь идёт в обход по краю данных. "
                    "Сцена проверяет, что сервис этот путь находит."
                )
            return scene

        return build

    yield "CROSS-12", rail("CROSS-12", "moscow", "Сеть за железной дорогой")
    yield "CROSS-13", rail("CROSS-13", "spb", "Сеть за железной дорогой, Купчино")

    def utility(sid: str, kinds: set[str], kind: str, title: str, rule_ids: list[str], eng: str) -> Callable[[], Scene]:
        def build() -> Scene:
            scene = single(
                ctx,
                sid,
                "CROSS",
                title,
                "moscow",
                lambda c: c.kinds("all") == kinds and 8 < c.dist["all"] < 80,
                rule_ids,
                eng,
                "Новый дом, между ним и трубой подземная линия.",
            )
            table2 = [k for k in sorted(kinds) if k in TABLE2]
            extra = [k for k in sorted(kinds) if k not in TABLE2]
            scene.must["special_kspec"] = {k: rules()["restrictions"][k]["k_special"] for k in table2}
            if extra:
                scene.should["special_kspec"] = {k: rules()["restrictions"][k]["k_special"] for k in extra}
            scene.should["crossings_max"] = {k: 1 for k in sorted(kinds)}
            scene.must["no_turn_in_special"] = True
            notes = [DEPTH_NOTE[k] for k in sorted(kinds) if k in DEPTH_NOTE]
            if notes:
                scene.engineer += " В режиме глубины: " + "; ".join(notes) + "."
            return scene

        return build

    eng_line = (
        "Между домом и трубой {what}. Инженер пересекает линию один раз прямым участком (спецпроход по 2 м в "
        "обе стороны от точки пересечения, без поворотов внутри), а рядом с линией держит отступ от внешних "
        "границ габаритов. Вдоль линии ближе нормы не идёт."
    )
    yield (
        "CROSS-15",
        utility(
            "CROSS-15",
            {"power_cable"},
            "power_cable",
            "Пересечение силового кабеля",
            ["ТП-4.power_cable", "ТП-4.спецпроход", "ТП-3.1", "Р7", "ТП-5"],
            eng_line.format(what="силовой кабель (габарит 0,2 м, Kспец 1,15)"),
        ),
    )
    yield (
        "CROSS-16",
        utility(
            "CROSS-16",
            {"gas_pipeline", "sewer"},
            "sewer",
            "Газопровод и канализация подряд",
            ["ТП-4.gas_pipeline", "ТП-4.доп_типы", "Р9", "ТП-4.наложение", "Р8"],
            eng_line.format(what="газопровод и канализация (канализация — дополнительный тип)"),
        ),
    )
    yield (
        "CROSS-17",
        utility(
            "CROSS-17",
            {"gas_pipeline", "sewer", "water_supply"},
            "water_supply",
            "Газопровод, канализация и водопровод",
            ["ТП-4.gas_pipeline", "ТП-4.доп_типы", "ТП-4.наложение", "Р8"],
            eng_line.format(what="три линии подряд (газ, канализация, водопровод)"),
        ),
    )
    yield (
        "CROSS-19",
        utility(
            "CROSS-19",
            {"gas_pipeline", "power_cable", "road", "sewer", "water_supply"},
            "road",
            "Полный профиль улицы: дорога и четыре линии",
            [
                "ТП-4.road",
                "ТП-4.gas_pipeline",
                "ТП-4.power_cable",
                "ТП-4.наложение",
                "Р8",
                "ТП-4.спецпроход",
                "ТП-2.1.узел",
            ],
            "Переход улицы со всеми коммуникациями: кабель, водопровод, канализация, газ и дорога. "
            "Зоны частично накладываются. Инженер делает один прямой переход под углом не меньше "
            "45°, на каждом общем фрагменте берёт наибольший Kспец, участки делит на границах "
            "смены набора объектов.",
        ),
    )
    yield "CROSS-21", lambda: dual_road(ctx)


def junction(ctx: Ctx) -> Scene:
    sid = "CROSS-09"
    pool = roads_of("moscow", ("residential", "tertiary", "secondary", "unclassified"), 6, 20)
    w = world("moscow")
    for a in ctx.shuffled(sid, pool)[:400]:
        for b in w.near(a.geom, "road"):
            if b.id == a.id or b not in pool:
                continue
            da, db = orientation(a.geom), orientation(b.geom)
            diff = abs((da - db + 90) % 180 - 90)
            if not 25 <= diff <= 60:
                continue
            p = a.geom.intersection(b.geom).centroid
            if p.is_empty:
                continue
            ua = (math.cos(math.radians(da)), math.sin(math.radians(da)))
            ub = (math.cos(math.radians(db)), math.sin(math.radians(db)))
            scene = new_scene(
                sid,
                "CROSS",
                "Косой перекрёсток: сеть в противоположном углу",
                "moscow",
                (p.x, p.y),
                220.0,
                ["ТП-4.road", "ТП-4.угол", "ТП-4.спецпроход", "Р6", "ПР-5", "ТП-2.1.повороты"],
                f"Улицы сходятся под углом около {diff:.0f}°. Дом в остром углу, сеть только в "
                "противоположном. Прямая через перекрёсток пересекает дорогу наискосок. Инженер переходит "
                "каждую улицу отдельно под углом не меньше 45° к её краю либо одним прямым переходом, "
                "если угол входа в полигон дороги не меньше 45°; трассу через середину перекрёстка вдоль "
                "проезжей части не ведёт.",
                "Новый дом у косого перекрёстка.",
            )
            sa = 1 if ((ub[0] * -ua[1] + ub[1] * ua[0]) > 0) else -1
            sb = 1 if ((ua[0] * -ub[1] + ua[1] * ub[0]) > 0) else -1
            quad = halfplane((p.x, p.y), ua, sa).intersection(halfplane((p.x, p.y), ub, sb))
            target_dir = quad.centroid
            dx, dy = unit(target_dir.x - p.x, target_dir.y - p.y)
            house = free_spot(scene, (p.x + dx * 55, p.y + dy * 55), 36, 14, da, radius=30, allow=quad)
            if house is None:
                continue
            keep_zone = halfplane((p.x, p.y), ua, -sa).intersection(halfplane((p.x, p.y), ub, -sb))
            drop_network_except(
                scene,
                scene.area.difference(keep_zone),
                set(),
                "сеть оставлена только в противоположном углу перекрёстка",
            )
            if not scene.of("heat_network"):
                continue
            cp = scene.add_house(house, 12)
            special_expect(scene, cp, "road", a)
            scene.should["crossings_max"] = {"road": 3}
            return scene
    raise Skip(sid)


def dual_road(ctx: Ctx) -> Scene:
    sid = "CROSS-21"
    pool = roads_of("moscow", ("secondary", "primary", "tertiary"), 7, 20)
    w = world("moscow")
    for a in ctx.shuffled(sid, pool)[:400]:
        twins = [
            b
            for b in w.near(a.geom, "road", distance=6.0)
            if b.id != a.id
            and b in pool
            and 0.8 < a.geom.distance(b.geom) < 6.0
            and abs((orientation(a.geom) - orientation(b.geom) + 90) % 180 - 90) < 8
        ]
        if not twins:
            continue
        both = Item(dict(a.props), shapely.union_all([a.geom, twins[0].geom]).convex_hull)
        try:
            scene, ob, cp = across(
                ctx,
                sid,
                "CROSS",
                "Две проезжие части с узким разделителем",
                "moscow",
                [both],
                ["ТП-4.road", "ТП-4.наложение", "Р8", "ТП-4.спецпроход", "ТП-2.1.узел"],
                "Две проезжие части с разделителем в несколько метров: зоны дорог (полигон плюс "
                "3 м) накладываются. Инженер переходит обе одним прямым участком, на общем "
                "фрагменте коэффициент 1,60 один раз, не сумма; поворот на разделителе не делает.",
                "Новый дом через улицу с двумя проезжими частями от сети.",
                gap=20.0,
                key=sid + a.id,
            )
        except Skip:
            continue
        scene.id, scene.title = sid, "Две проезжие части с узким разделителем"
        special_expect(scene, cp, "road", a)
        line, _, _ = straight(scene, cp)
        gap = (
            line.intersection(a.geom.union(twins[0].geom).convex_hull).length
            - line.intersection(a.geom.union(twins[0].geom)).length
        )
        if gap > 5.5:
            continue
        scene.should["crossings_max"] = {"road": 2}
        scene.must["continuous_special_over"] = [a.id, twins[0].id]
        scene.must["special_kspec"] = {"road": 1.6}
        scene.engineer += f" Разделитель на прямой {gap:.1f} м, зоны по 3 м перекрываются."
        return scene
    raise Skip(sid)


def social_kind(item: Item, city: str) -> str | None:
    tags = {b.props.get("building") for b in world(city).near(item.geom, *BUILDINGS)}
    for kind in ("school", "kindergarten", "hospital"):
        if kind in tags:
            return kind
    return None


def area_family(ctx: Ctx) -> Iterator[tuple[str, Callable[[], Scene]]]:
    def around(
        sid: str,
        city: str,
        kind: str,
        amin: float,
        amax: float,
        title: str,
        rule_ids: list[str],
        eng: str,
        sub: str | None = None,
        half: float = 260.0,
        need_route: bool = True,
    ) -> Callable[[], Scene]:
        def build() -> Scene:
            pool = [i for i in world(city).of(kind) if amin <= i.geom.area <= amax]
            if sub:
                pool = [i for i in ctx.shuffled(sid + "sub", pool)[:200] if social_kind(i, city) == sub]
            scene, ob, cp = across(
                ctx,
                sid,
                "AREA",
                title,
                city,
                pool,
                rule_ids,
                eng,
                f"Новый дом, между ним и сетью {title.lower()}.",
                half=half,
                need_route=need_route,
            )
            strict = kind in TABLE2_FORBID
            forbid_expect(scene, cp, kind, strict=strict)
            if kind == "water" and "unconnected" not in scene.must and amin >= 200000:
                scene.title = "Река: обход по краю данных"
                scene.engineer += (
                    " Река в данных обрезана по краю окна, и путь есть только вокруг этого края. Сцена проверяет, "
                    "что сервис находит обход по краю данных, а не реку как непреодолимую преграду."
                )
            target = scene.must if strict else scene.should
            target["no_cross"] = [ob.id] + [i for i in target.get("no_cross", []) if i != ob.id]
            return scene

        return build

    eng = (
        "Между домом и сетью {what}, пересекать её нельзя (таблица 2, отступ {m} м). Инженер обходит её по "
        "проездам вокруг или врезается в другую трубу со своей стороны, не срезая угол через территорию."
    )
    yield (
        "AREA-01",
        around(
            "AREA-01",
            "moscow",
            "park",
            50000,
            400000,
            "Большой парк",
            ["ТП-4.park", "ТП-2.5"],
            eng.format(what="большой парк", m=1),
            half=450.0,
        ),
    )
    yield (
        "AREA-02",
        around("AREA-02", "moscow", "park", 1500, 9000, "Сквер", ["ТП-4.park"], eng.format(what="сквер", m=1)),
    )
    yield (
        "AREA-03",
        around("AREA-03", "spb", "park", 3000, 60000, "Парк, Купчино", ["ТП-4.park"], eng.format(what="парк", m=1)),
    )
    yield (
        "AREA-04",
        around(
            "AREA-04",
            "moscow",
            "social_area",
            3000,
            40000,
            "Территория школы",
            ["ТП-4.social_area", "ПР-10"],
            eng.format(what="территория школы", m=1),
            sub="school",
        ),
    )
    yield (
        "AREA-06",
        around(
            "AREA-06",
            "moscow",
            "social_area",
            3000,
            220000,
            "Территория больницы",
            ["ТП-4.social_area"],
            eng.format(what="территория больницы", m=1),
            sub="hospital",
        ),
    )
    yield (
        "AREA-08",
        around(
            "AREA-08",
            "moscow",
            "water",
            200000,
            2000000,
            "Река",
            ["ТП-4.water", "ТП-2.5", "ТП-2.1.граница", "ТП-6.штраф"],
            "Сеть только на другом берегу реки. Пересекать водный объект нельзя. Если в пределах района есть "
            "обход по суше, инженер ведёт трассу в обход с отступом 1 м. Если река перекрывает район целиком, "
            "река продлена на 7 км за окно, обход длиннее предельной длины Ду 1400, и допустимой трассы нет: в "
            "жизни искали бы мост или дюкер, но по правилам кейса дом остаётся без сети со штрафом.",
            half=400.0,
            need_route=False,
        ),
    )
    yield (
        "AREA-10",
        around(
            "AREA-10",
            "moscow",
            "prohibited_site",
            3000,
            300000,
            "Запретная территория",
            ["ТП-4.prohibited_site"],
            eng.format(what="запретная территория", m=1),
        ),
    )
    yield (
        "AREA-11",
        around(
            "AREA-11",
            "spb",
            "metro",
            300,
            8000,
            "Объект метро",
            ["ТП-4.доп_типы", "Р9", "ПР-10"],
            "Между домом и сетью сооружение метро. В таблице 2 его нет, это дополнительный тип; "
            "сервис может считать его запретом с отступом. Инженер обойдёт сооружение метро и не "
            "станет прокладывать над ним теплосеть.",
        ),
    )

    def industrial() -> Scene:
        sid = "AREA-12"
        pool = [
            b
            for b in world("moscow").of("oks_existing")
            if b.props.get("building") in ("industrial", "warehouse", "hangar") and b.geom.area > 3000
        ]
        scene, ob, cp = across(
            ctx,
            sid,
            "AREA",
            "Промзона между домом и сетью",
            "moscow",
            pool,
            ["ТП-4.oks", "ТП-2.2", "ТП-4.отступ"],
            "Между домом и сетью цеха и склады промзоны. Сама промзона не запретна, но корпуса — "
            "препятствия с отступом 5 м. Инженер ведёт трассу по проездам промзоны или вокруг неё.",
            "Новый дом у промзоны, сеть за цехами.",
            half=260.0,
        )
        expect_basic(scene)
        scene.must["no_cross"] = [ob.id]
        return scene

    yield "AREA-12", industrial
    yield (
        "AREA-17",
        around(
            "AREA-17",
            "spb",
            "prohibited_site",
            3000,
            300000,
            "Запретная территория, Купчино",
            ["ТП-4.prohibited_site"],
            eng.format(what="запретная территория", m=1),
            half=350.0,
        ),
    )

    def inside(sid: str, city: str, kind: str, title: str, amin: float, eng: str) -> Callable[[], Scene]:
        def build() -> Scene:
            for ob in ctx.shuffled(sid, [i for i in world(city).of(kind) if i.geom.area > amin]):
                c = ob.geom.representative_point()
                scene = new_scene(
                    sid,
                    "AREA",
                    title,
                    city,
                    (c.x, c.y),
                    200.0,
                    [f"ТП-4.{kind}", "ТП-2.5", "ТП-2.2", "Р3", "ТП-6.штраф", "ПР-9"],
                    eng,
                    "Новый корпус целиком внутри территории.",
                )
                house = free_spot(
                    scene,
                    (c.x, c.y),
                    30,
                    14,
                    orientation(ob.geom),
                    radius=60,
                    allow=ob.geom.buffer(-6),
                    blocked=obstacles(scene, skip=frozenset({ob.id})),
                )
                if house is None:
                    continue
                drop_network_except(scene, ob.geom.buffer(10), set(), "сеть внутри территории убрана")
                if not scene.of("heat_network"):
                    continue
                cp = scene.add_house(house, 4, kind="public")
                if reference_length(scene, cp) is not None:
                    continue
                scene.must["unconnected"] = {cp: ["inside_forbidden_zone", "enclosed", "no_route"]}
                scene.must["no_cross"] = [ob.id]
                return scene
            raise Skip(sid)

        return build

    yield (
        "AREA-13",
        inside(
            "AREA-13",
            "moscow",
            "social_area",
            "Новый корпус на территории школы",
            8000,
            "Новый корпус стоит внутри территории социального объекта, а сеть снаружи. Выйти с "
            "территории нельзя: пересечение запрещено, исключение действует только для своего "
            "полигона ОКС. Инженер в жизни подвёл бы сеть по территории школы, но по правилам кейса "
            "допустимой трассы нет: точка без сети со штрафом и понятной причиной.",
        ),
    )
    yield (
        "AREA-14",
        inside(
            "AREA-14",
            "moscow",
            "park",
            "Павильон внутри парка",
            20000,
            "Новое здание внутри парка, сеть снаружи. Пересекать парк нельзя, поэтому по правилам "
            "кейса точку подключить нельзя: без сети со штрафом и причиной.",
        ),
    )
    yield (
        "AREA-15",
        inside(
            "AREA-15",
            "moscow",
            "prohibited_site",
            "Здание внутри запретной территории",
            8000,
            "Здание внутри запретной территории. Трасса не может её пересечь, точка остаётся без "
            "сети с причиной «внутри запретной зоны».",
        ),
    )


def courtyards(city: str, center: Point, radius: float = 1500.0) -> dict[str, list[Polygon]]:
    w = world(city)
    zone = center.buffer(radius)
    houses = [b.geom for b in w.near(zone, *BUILDINGS)]
    raw = shapely.union_all(houses)
    clear = shapely.union_all([h.buffer(5.4, quad_segs=2) for h in houses])
    closed = shapely.union_all([h.buffer(15, quad_segs=2) for h in houses]).buffer(-15)

    def holes(geom: Any) -> list[Polygon]:
        return [Polygon(r) for p in getattr(geom, "geoms", [geom]) if p.geom_type == "Polygon" for r in p.interiors]

    raw_holes = [h for h in holes(raw) if 800 < h.area < 30000]
    clear_holes = [h for h in holes(clear) if 400 < h.area < 30000]
    open_holes = [h for h in holes(closed) if 1500 < h.area < 30000]
    narrow = [h for h in clear_holes if not any(r.contains(h.representative_point()) for r in raw_holes)]
    opened = [
        h
        for h in open_holes
        if not any(c.contains(h.representative_point()) for c in clear_holes)
        and not any(r.contains(h.representative_point()) for r in raw_holes)
    ]
    return {"closed": raw_holes, "narrow": narrow, "open": opened}


def bld_family(ctx: Ctx) -> Iterator[tuple[str, Callable[[], Scene]]]:
    def yard(sid: str, city: str, kind: str, title: str, eng: str) -> Callable[[], Scene]:
        def build() -> Scene:
            centers = sorted({f.geom.centroid.coords[0] for f in world(city).of("oks_future")})[::25]
            for cx, cy in ctx.shuffled(sid, centers)[:6]:
                for hole in ctx.shuffled(sid, courtyards(city, Point(cx, cy))[kind]):
                    c = hole.representative_point()
                    scene = new_scene(
                        sid,
                        "BLD",
                        title,
                        city,
                        (c.x, c.y),
                        180.0,
                        ["ТП-2.2", "ТП-4.oks", "ТП-2.5", "ТП-4.отступ", "Р15"],
                        eng,
                        "Новый дом во дворе, образованном существующими зданиями.",
                    )
                    house = free_spot(scene, (c.x, c.y), 24, 12, orientation(hole), radius=30, allow=hole.buffer(-6))
                    if house is None:
                        continue
                    drop_network_except(scene, hole.buffer(2), set(), "сеть внутри двора убрана")
                    if not scene.of("heat_network"):
                        continue
                    cp = scene.add_house(house, 9)
                    if kind == "open":
                        length = reference_length(scene, cp)
                        if length is None or length > 300:
                            continue
                        expect_basic(scene)
                    else:
                        if reference_length(scene, cp) is not None:
                            continue
                        scene.must["unconnected"] = {cp: ["enclosed", "no_route"]}
                    return scene
            raise Skip(sid)

        return build

    yield (
        "BLD-01",
        yard(
            "BLD-01",
            "moscow",
            "closed",
            "Двор, замкнутый зданиями, без въезда",
            "Двор со всех сторон закрыт зданиями (в OSM арки нет — здание сплошное). Здания "
            "непроходимы, а сети во дворе нет. В жизни инженер прошёл бы аркой, но по правилам кейса "
            "допустимой трассы нет: дом без сети со штрафом и причиной «окружён препятствиями».",
        ),
    )
    yield (
        "BLD-02",
        yard(
            "BLD-02",
            "moscow",
            "narrow",
            "Двор с въездом уже нормы",
            "Во двор ведёт проезд уже 10,4 м: с отступом 5 м от каждого здания и шириной пары труб "
            "пройти нельзя. Инженер по правилам кейса трассы не найдёт, дом остаётся без сети с причиной; "
            "протаскивать трассу через проезд с нарушением отступа нельзя.",
        ),
    )
    yield (
        "BLD-03",
        yard(
            "BLD-03",
            "moscow",
            "open",
            "Двор со въездом",
            "Во двор ведёт проезд. Если щель между корпусами шире нормы, инженер ведёт трассу по ней с "
            "отступом от углов зданий. Если щель занята проездом и места для обычного участка нет, по "
            "принятой трактовке допустим продольный спецпроход по полигону проезда при входе и выходе через "
            "его границу под углом не меньше 45° (обычно через торец).",
        ),
    )
    yield (
        "BLD-05",
        yard(
            "BLD-05",
            "spb",
            "open",
            "Двор со въездом, Купчино",
            "Двор открыт с одной стороны. Инженер ведёт трассу через въезд к ближайшей трубе.",
        ),
    )

    def narrow_gap() -> Scene:
        sid = "BLD-06"
        w = world("moscow")
        for a in ctx.shuffled(sid, [b for b in w.of("oks_existing") if 300 < b.geom.area < 4000])[:3000]:
            for b in w.near(a.geom, "oks_existing", distance=9.0):
                if b.id == a.id or not 4.0 <= a.geom.distance(b.geom) <= 9.0 or b.geom.area < 300:
                    continue
                pa, pb = nearest_points(a.geom, b.geom)
                m = Point((pa.x + pb.x) / 2, (pa.y + pb.y) / 2)
                u = unit(pb.x - pa.x, pb.y - pa.y)
                for side in (1, -1):
                    nx, ny = -u[1] * side, u[0] * side
                    scene = new_scene(
                        sid,
                        "BLD",
                        "Узкий проход между зданиями",
                        "moscow",
                        (m.x, m.y),
                        200.0,
                        ["ТП-4.oks", "ТП-3.1", "ТП-4.отступ", "ТП-2.2"],
                        f"Кратчайший путь к сети идёт через щель {a.geom.distance(b.geom):.1f} м между "
                        "двумя зданиями. С отступом 5 м от каждого и шириной пары труб нужно не меньше "
                        "10,4 м, поэтому инженер обходит здания, а не протискивается между ними.",
                        "Новый дом за щелью между двумя зданиями, сеть с другой стороны.",
                    )
                    house = free_spot(
                        scene,
                        (m.x + nx * 40, m.y + ny * 40),
                        36,
                        14,
                        math.degrees(math.atan2(u[1], u[0])),
                        radius=20,
                        allow=halfplane((m.x, m.y), u, side),
                    )
                    if house is None:
                        continue
                    drop_network_except(
                        scene,
                        halfplane((m.x, m.y), u, side).intersection(scene.area),
                        set(),
                        "сеть со стороны нового дома убрана",
                    )
                    if not scene.of("heat_network"):
                        continue
                    cp = scene.add_house(house, 9, cp=None)
                    line, _, _ = straight(scene, cp)
                    if line.distance(m) > 4:
                        continue
                    expect_basic(scene)
                    scene.must["no_pass_between"] = [a.id, b.id]
                    return scene
        raise Skip(sid)

    yield "BLD-06", narrow_gap

    def shaped(sid: str, lo: float, hi: float, title: str, eng: str) -> Callable[[], Scene]:
        def build() -> Scene:
            w = world("moscow")
            pool = [
                b
                for b in w.of("oks_existing")
                if b.geom.geom_type == "Polygon"
                and 1500 < b.geom.area < 9000
                and lo <= b.geom.area / b.geom.convex_hull.area <= hi
                and not b.geom.interiors
                and not in_forbid("moscow", b.geom)
            ]
            for b in ctx.shuffled(sid, pool)[:200]:
                hull_gap = polygonal_parts(b.geom.convex_hull.difference(b.geom))
                if not hull_gap:
                    continue
                yard_part = max(hull_gap, key=lambda g: g.area)
                c = b.geom.centroid
                scene = new_scene(
                    sid,
                    "BLD",
                    title,
                    "moscow",
                    (c.x, c.y),
                    200.0,
                    ["ТП-2.2", "Р3", "Р29-15", "Р29-16", "Р29-17", "ТП-4.oks", "ТП-2.1.повороты"],
                    eng,
                    "Существующее здание сложной формы заменено новым той же формы, ввод со стороны внутреннего двора.",
                )
                scene.drop({i.id for i in scene.of("oks_existing") if i.geom.intersection(b.geom).area > 1.0})
                inner = b.geom.buffer(-1.0)
                cp_pt = nearest_points(inner.boundary, yard_part.centroid)[0]
                scene.add_house(b.geom, 12, cp=(cp_pt.x, cp_pt.y), house_id=f"oks-new-{b.id}")
                scene.edits.append(f"здание {b.id} заменено новым домом той же формы")
                if not scene.of("heat_network"):
                    continue
                expect_basic(scene)
                return scene
            raise Skip(sid)

        return build

    yield (
        "BLD-07",
        shaped(
            "BLD-07",
            0.35,
            0.62,
            "П-образный дом, ввод со двора",
            "Ввод в П-образный дом стоит внутри «двора» буквы П. Финальный участок начинается у "
            "ближайшей к точке стены; если луч оттуда снова упирается в своё здание или зону отступа "
            "соседа, допустима другая ближайшая подходящая точка контура (разъяснение 29.09, п. 15). "
            "Инженер выводит трассу из двора П прямо наружу, без зигзагов.",
        ),
    )
    yield (
        "BLD-08",
        shaped(
            "BLD-08",
            0.62,
            0.8,
            "Г-образный дом, ввод во внутреннем углу",
            "Ввод в Г-образный дом у внутреннего угла. Инженер ведёт финальный участок от ближайшей "
            "стены, не задевая второе крыло дома, и дальше по оси застройки.",
        ),
    )

    def corner() -> Scene:
        sid = "BLD-09"
        c = pick(
            ctx,
            sid,
            candidates("moscow"),
            lambda c: c.origin == "future" and 15 < c.dist["all"] < 50 and not c.crosses["all"],
        )
        scene = from_candidate(
            ctx,
            sid,
            "BLD",
            "Ввод у угла здания",
            c,
            "all",
            ["ТП-2.2", "Р3", "ТП-2.1.повороты"],
            "Точка подключения в метре от угла дома. Финальный участок идёт от ближайшей к точке "
            "границы; инженер выбирает ту стену угла, от которой путь к сети короче и без лишнего "
            "поворота, и не ведёт трассу вдоль стены в зоне отступа.",
            "Новый дом, точка подключения у угла.",
        )
        house = scene.houses()[0]
        xy = list(house.geom.exterior.coords)[:-1]
        _, _, tie = straight(scene, scene.points()[0].id)
        far_corner = max(xy, key=lambda p: Point(p).distance(tie))
        cx, cy = house.geom.centroid.x, house.geom.centroid.y
        dx, dy = unit(cx - far_corner[0], cy - far_corner[1])
        cp = next(i for i in scene.points())
        cp.geom = Point(far_corner[0] + dx * 1.4, far_corner[1] + dy * 1.4)
        scene.edits.append("точка подключения перенесена к дальнему от сети углу дома")
        scene.forget_routes()
        expect_basic(scene)
        return scene

    yield "BLD-09", corner

    def long_building() -> Scene:
        sid = "BLD-10"
        pool = []
        for b in world("moscow").of("oks_existing"):
            rr = b.geom.minimum_rotated_rectangle
            xy = list(rr.exterior.coords)
            if max(math.dist(xy[0], xy[1]), math.dist(xy[1], xy[2])) > 150 and b.geom.area < 12000:
                pool.append(b)
        scene, ob, cp = across(
            ctx,
            sid,
            "BLD",
            "Длинное здание поперёк пути",
            "moscow",
            pool,
            ["ТП-4.oks", "ТП-2.2", "ТП-4.отступ"],
            "Между домом и сетью длинное здание. Инженер обходит его вокруг торца с отступом 5 м "
            "и не пытается пройти сквозь него; если с другой стороны сети нет, а вокруг здания "
            "есть проход, трасса длиннее, но допустима.",
            "Новый дом за длинным зданием от сети.",
            half=260.0,
        )
        expect_basic(scene)
        scene.must["no_cross"] = [ob.id]
        return scene

    yield "BLD-10", long_building

    def multi_point() -> Scene:
        sid = "BLD-11"
        c = pick(
            ctx,
            sid,
            candidates("moscow"),
            lambda c: c.origin == "future" and 15 < c.dist["all"] < 60 and not c.crosses["all"],
        )
        scene = from_candidate(
            ctx,
            sid,
            "BLD",
            "Три ввода в одном доме",
            c,
            "all",
            ["ТП-2.2.несколько", "Р4", "ТП-2.3.расход", "ТП-2.1.камеры"],
            "В одном доме три ввода, у каждого свой расход. Каждая точка — отдельная цель. "
            "Инженер подключит их одним деревом вдоль фасада с камерами ветвления, а не тремя "
            "независимыми врезками.",
            "Новый дом с тремя точками подключения.",
        )
        house = scene.houses()[0]
        old = scene.points()[0]
        flow = house.props["flow_tph"]
        scene.items = [i for i in scene.items if i is not old]
        inner = house.geom.buffer(-1.0).exterior
        _, _, tie = straight_from(scene, house.geom.centroid)
        start = inner.project(tie)
        for k, frac in enumerate((-0.18, 0.0, 0.18)):
            p = inner.interpolate((start + frac * inner.length) % inner.length)
            scene.add_point(f"cp-{sid.lower()}-{k + 1}", (p.x, p.y), round(flow / 3, 3), house.id)
        scene.edits.append("точка подключения дома заменена тремя вводами с расходом по трети")
        expect_basic(scene)
        scene.should["same_tree"] = [[cp.id for cp in scene.points()]]
        return scene

    yield "BLD-11", multi_point

    def neighbour() -> Scene:
        sid = "BLD-12"
        w = world("moscow")
        for b in ctx.shuffled(
            sid,
            [
                b
                for b in w.of("oks_existing")
                if b.props.get("building") in ("apartments", "residential") and 600 < b.geom.area < 3000
            ],
        )[:300]:
            origin, u = long_axis(b.geom)
            c = b.geom.centroid
            scene = new_scene(
                sid,
                "BLD",
                "Новый дом вплотную к соседнему",
                "moscow",
                (c.x, c.y),
                180.0,
                ["ТП-2.2", "Р3", "ТП-4.oks", "ТП-2.5"],
                "Новый дом пристроен к существующему с зазором 3 м, меньше нормы 5 м. Финальный "
                "участок не должен идти через зону отступа соседа. Инженер выводит ввод через другую "
                "стену нового дома; если со всех сторон выход упирается в зону соседа, точка остаётся "
                "без сети с причиной.",
                "Новый дом в 3 м от существующего здания.",
            )
            rr = b.geom.minimum_rotated_rectangle
            xy = list(rr.exterior.coords)
            depth = min(math.dist(xy[0], xy[1]), math.dist(xy[1], xy[2]))
            for side in (1, -1):
                nx, ny = -u[1] * side, u[0] * side
                poly = rect(
                    c.x + nx * (depth / 2 + 3 + 7),
                    c.y + ny * (depth / 2 + 3 + 7),
                    30,
                    14,
                    math.degrees(math.atan2(u[1], u[0])),
                )
                others = [
                    i
                    for i in scene.items
                    if i.geom is not None
                    and i.id != b.id
                    and i.kind not in ("oks_connection_point",)
                    and i.geom.distance(poly) < 4
                ]
                if others or poly.distance(b.geom) < 2.5 or not scene.area.contains(poly):
                    continue
                cp_pt = nearest_points(poly.buffer(-1).exterior, b.geom)[0]
                cp = scene.add_house(poly, 9, cp=(cp_pt.x, cp_pt.y))
                if not scene.of("heat_network"):
                    break
                if reference_length(scene, cp) is None:
                    expect_basic(scene, connected=False)
                    scene.should["unconnected"] = {cp: ["no_route", "enclosed", "inside_forbidden_zone"]}
                    scene.engineer += (
                        " Эталон пути не находит: ввод стоит в 3 м от соседа, и даже финальный участок внутри "
                        "своего здания лежит в зоне отступа соседнего здания. Это трактовка команды (отступ к чужому "
                        "зданию действует на всём финальном участке), поэтому неподключение — в should."
                    )
                else:
                    expect_basic(scene)
                scene.must["final_segment_clear_of"] = [b.id]
                return scene
        raise Skip(sid)

    yield "BLD-12", neighbour


def polygonal_parts(geom: Any) -> list[Polygon]:
    return [g for g in getattr(geom, "geoms", [geom]) if g.geom_type == "Polygon" and g.area > 50]


def straight_from(scene: Scene, point: Point) -> tuple[LineString, Item, Point]:
    pipe, _, _ = nearest_pipe(scene, point)
    b = nearest_points(point, pipe.geom)[1]
    return LineString([point, b]), pipe, b


SYNTH_ORIGIN = (413000.0, 6180000.0)


def field(sid: str, family: str, title: str, distance: float, flow: float, rule_ids: list[str], eng: str) -> Scene:
    ox, oy = SYNTH_ORIGIN
    half = distance / 2 + 150
    scene = new_scene(
        sid,
        family,
        title,
        None,
        (ox + distance / 2, oy),
        half,
        rule_ids,
        eng,
        f"Труба Ду 500 и один дом в {distance:.0f} м от неё, между ними пусто.",
        half_h=200.0,
    )
    scene.synthetic = True
    scene.add("heat_network", LineString([(ox, oy - 150), (ox, oy + 150)]), f"hn-{sid.lower()}", diameter=500)
    scene.add("heat_chamber", Point(ox, oy - 150), f"hc-{sid.lower()}")
    house = rect(ox + distance + 8, oy, 16, 30, 0.0)
    return scene_with_house(scene, house, flow, (ox + distance + 1, oy))


def scene_with_house(scene: Scene, house: Polygon, flow: float, cp: tuple[float, float]) -> Scene:
    scene.add_house(house, 14, flow=flow, cp=cp)
    return scene


def far_osm(ctx: Ctx, sid: str, radius: float, flow: float | None, title: str, eng: str, rule_ids: list[str]) -> Scene:
    c = pick(ctx, sid, candidates("moscow"), lambda c: c.origin == "future" and c.dist["all"] < 40)
    scene = from_candidate(
        ctx,
        sid,
        "LEN",
        title,
        c,
        "all",
        rule_ids,
        eng,
        f"Новый дом, сеть ближе {radius:.0f} м убрана.",
        half=radius + 150,
    )
    scene.drop_network(c.house.buffer(radius), f"сеть ближе {radius:.0f} м от дома убрана")
    if not scene.of("heat_network"):
        raise Skip(sid)
    if flow is not None:
        scene.houses()[0].props["flow_tph"] = flow
    expect_basic(scene)
    cp = scene.points()[0].id
    f = scene.flow(scene.get(cp))
    ref = reference_length(scene, cp)
    lo, hi = dn_for(f, ref / 1.08), dn_for(f, scene.should["max_route_length_m"][cp])
    scene.should["final_dn_range"] = {cp: [lo, hi]}
    scene.engineer += (
        f" Эталонный путь по сетке {ref:.0f} м (сетка удлиняет путь до 8 %), допустимый Ду у точки {lo}–{hi}."
    )
    scene.must["dn_by_flow_and_length"] = True
    return scene


def far_osm_spb(ctx: Ctx) -> Scene:
    sid = "LEN-07"
    c = pick(ctx, sid, candidates("spb"), lambda c: c.dist["all"] < 60)
    scene = from_candidate(
        ctx,
        sid,
        "LEN",
        "Сеть в 350 м, Купчино",
        c,
        "all",
        ["ТП-2.3.ду", "ТП-2.3.длина", "Р1"],
        "Ближайшая сеть в 350 м. Инженер ведёт трассу по проездам квартала, Ду — минимальный по "
        "расходу и предельной длине пути.",
        "Новый дом, сеть ближе 350 м убрана.",
        half=480.0,
    )
    scene.drop_network(c.house.buffer(350.0), "сеть ближе 350 м от дома убрана")
    if not scene.of("heat_network"):
        raise Skip(sid)
    expect_basic(scene)
    scene.must["dn_by_flow_and_length"] = True
    return scene


def len_family(ctx: Ctx) -> Iterator[tuple[str, Callable[[], Scene]]]:
    rl = ["ТП-2.3.ду", "ТП-2.3.длина", "Р1", "ТП-6.стоимость"]
    yield (
        "LEN-01",
        lambda: far_osm(
            ctx,
            "LEN-01",
            300.0,
            None,
            "Сеть в 300 м",
            (
                "Ближайшая сеть в 300 м. Инженер ведёт одну трассу по проездам. Ду по расходу дома может не пройти по "
                "предельной длине; тогда берётся следующий наименьший, который проходит и по расходу, и по длине."
            ),
            rl,
        ),
    )
    yield (
        "LEN-02",
        lambda: far_osm(
            ctx,
            "LEN-02",
            450.0,
            3.0,
            "Малый расход, сеть дальше 450 м по радиусу",
            (
                "Расход 3 т/ч хватает Ду 50, но его предельная длина 181 м. Путь длиннее, поэтому нужен "
                "минимальный Ду, который проходит по длине всего пути. Завышать дальше нельзя, дробить Ду ради "
                "нового отсчёта тоже."
            ),
            rl,
        ),
    )

    def synth(
        sid: str, distance: float, flow: float, title: str, eng: str, extra: dict[str, Any], rule_ids: list[str]
    ) -> Callable[[], Scene]:
        def build() -> Scene:
            scene = field(sid, "LEN", title, distance, flow, rule_ids, eng)
            cp = scene.points()[0].id
            scene.must.update(extra.get("must", {}))
            scene.should.update(extra.get("should", {}))
            if "connected" in extra:
                scene.must["connected"] = [cp]
                dn = dn_for(flow, distance)
                scene.must["final_dn_range"] = {cp: [dn, dn_for(flow, distance * 1.02 + 20)]}
                scene.should["max_route_length_m"] = {cp: round(distance * 1.02 + 20)}
            if "unconnected" in extra:
                scene.must["unconnected"] = {cp: extra["unconnected"]}
            return scene

        return build

    yield (
        "LEN-03",
        synth(
            "LEN-03",
            2000.0,
            11.4,
            "Дом в 2 км: стройка дороже штрафа",
            (
                "До сети 2 км: Ду по длине около 400, стройка дороже штрафа за неподключение (100 млн + 0,5 млн × G). "
                "Но маршрут допустим, поэтому инженер обязан подключить дом: отказ ради лучшего S запрещён."
            ),
            {"connected": True},
            rl + ["ТП-2.5", "Р15", "ТП-6.штраф"],
        ),
    )
    yield (
        "LEN-04",
        synth(
            "LEN-04",
            5000.0,
            30.0,
            "Дом в 5 км",
            (
                "Пять километров прямого поля. Инженер ведёт одну прямую, Ду — минимальный, укладывающий 5 км в "
                "предельную длину (Ду 700, предел 4 775 м, не проходит; нужен Ду 800)."
            ),
            {"connected": True},
            rl + ["Р15"],
        ),
    )
    yield (
        "LEN-05",
        synth(
            "LEN-05",
            10800.0,
            20000.0,
            "10,8 км и расход под Ду 1400",
            (
                "Расход 20 000 т/ч требует Ду 1400 (пропускная способность 22 501,9 т/ч), путь 10,8 км укладывается в его "
                "предельную длину 11 276 м. Трасса прямая, без лишних изломов, иначе выйдет за предел."
            ),
            {"connected": True},
            rl + ["Р15"],
        ),
    )
    yield (
        "LEN-06",
        synth(
            "LEN-06",
            11700.0,
            11.4,
            "Дом дальше предела самой толстой трубы",
            (
                "До сети 11,7 км, больше предельной длины Ду 1400 (11 276 м). Расход один на всём пути, поэтому сменить "
                "Ду по дороге нельзя, и допустимой трассы нет. Дом остаётся без сети со штрафом."
            ),
            {"unconnected": ["no_route"]},
            rl + ["ТП-2.5", "ТП-6.штраф"],
        ),
    )

    def flow_osm(
        sid: str, flow: float, title: str, eng: str, rule_ids: list[str], unconnected: list[str] | None = None
    ) -> Callable[[], Scene]:
        def build() -> Scene:
            scene = single(
                ctx,
                sid,
                "FLOW",
                title,
                "moscow",
                lambda c: c.origin == "future" and 15 < c.dist["all"] < 60 and not c.crosses["all"],
                rule_ids,
                eng,
                f"Новый дом с расходом {flow:g} т/ч.",
            )
            scene.houses()[0].props["flow_tph"] = flow
            scene.edits.append(f"расход дома задан {flow:g} т/ч вместо расчётного")
            cp = scene.points()[0].id
            scene.reference.clear()
            scene.must.clear()
            scene.should.clear()
            if not unconnected:
                expect_basic(scene)
            if unconnected:
                scene.must = {"unconnected": {cp: unconnected}}
                scene.should = {}
            else:
                lo = dn_for(flow, reference_length(scene, cp) or 0)
                scene.must["final_dn_range"] = {cp: [lo, lo]}
                scene.must["dn_by_flow_and_length"] = True
            return scene

        return build

    yield (
        "FLOW-03",
        flow_osm(
            "FLOW-03",
            25000.0,
            "Расход больше пропускной способности Ду 1400",
            (
                "Расход 25 000 т/ч больше пропускной способности самой толстой трубы (22 501,9 т/ч). Подключить нельзя "
                "ни одним диаметром. Точка без сети со штрафом и причиной «расход больше пропускной способности»."
            ),
            ["ТП-2.3.ду", "ТП-2.5", "ТП-6.штраф"],
            unconnected=["flow_exceeds_capacity"],
        ),
    )
    yield (
        "FLOW-04",
        flow_osm(
            "FLOW-04",
            0.05,
            "Крошечный расход",
            ("Расход 0,05 т/ч: Ду 50 с запасом. Инженер ставит Ду 50, если путь короче 181 м."),
            ["ТП-2.3.ду"],
        ),
    )

    def group() -> Scene:
        sid = "FLOW-05"
        scene = site_scene(
            ctx,
            sid,
            "Три соседа вместе больше Ду 1400",
            "moscow",
            (3, 3),
            ["ТП-2.3.ду", "ТП-2.3.расход", "ТП-2.1.дерево", "ТП-2.4.новая"],
            "Каждый из трёх домов по 9 000 т/ч проходит в Ду 1000, но вместе 27 000 т/ч больше "
            "пропускной способности Ду 1400. Общий участок на всех троих невозможен. Инженер делает "
            "минимум две отдельные врезки или дерево, где на каждом участке расход не больше "
            "пропускной способности.",
            "Три соседних дома с расходом по 9 000 т/ч.",
        )
        scene.family = "FLOW"
        for h in scene.houses():
            h.props["flow_tph"] = 9000.0
        scene.edits.append("расход каждого дома задан 9 000 т/ч")
        scene.reference.clear()
        scene.must.clear()
        scene.should.clear()
        expect_tree(scene)
        scene.must["segment_flow_within_capacity"] = True
        scene.should["trees_min"] = 2
        return scene

    yield "FLOW-05", group
    yield "LEN-07", lambda: far_osm_spb(ctx)


def street_angles(scene: Scene) -> list[tuple[float, float]]:
    return [(orientation(r.geom) % 90, r.geom.area) for r in scene.of("road") if r.geom.area > 300]


def grid_family(ctx: Ctx) -> Iterator[tuple[str, Callable[[], Scene]]]:
    def grid(sid: str, city: str, kind: str, title: str, eng: str) -> Callable[[], Scene]:
        def build() -> Scene:
            sites = sorted(((k, v) for k, v in site_houses(city).items() if 1 <= len(v) <= 6), key=lambda kv: kv[0])
            for _, houses in ctx.shuffled(sid, sites):
                zone = shapely.union_all([h.geom for h in houses])
                c = zone.centroid
                scene = new_scene(
                    sid,
                    "GRID",
                    title,
                    city,
                    (c.x, c.y),
                    250.0,
                    ["ТП-2.1.повороты", "Р5", "ТП-2.1.пересечения", "ТП-6.варианты"],
                    eng,
                    "Новые дома в квартале с характерной сеткой улиц.",
                    futures={h.id for h in houses},
                )
                angles = street_angles(scene)
                total = sum(a for _, a in angles) or 1
                rot = sum(min(t, 90 - t) * a for t, a in angles) / total
                fill = [
                    r.geom.area / r.geom.minimum_rotated_rectangle.area for r in scene.of("road") if r.geom.area > 1500
                ]
                curved = fill and sum(1 for f in fill if f < 0.45) / len(fill) > 0.3
                ok = {
                    "orth": rot < 8 and not curved,
                    "diag": 30 < (sum(t * a for t, a in angles) / total) < 60 and rot > 25,
                    "rotated": 12 < rot < 45 and not curved,
                    "curved": curved,
                }[kind]
                if not ok:
                    continue
                expect_tree(scene)
                scene.should["along_axes_share_min"] = 0.8
                scene.should["max_turn_deg"] = 90
                return scene
            raise Skip(sid)

        return build

    eng = (
        "Инженер кладёт трассы вдоль осей застройки и улиц, переходы — под прямым углом, поворот один на 90°, "
        "а не два по 45°; ступенек и мелких изломов нет."
    )
    yield "GRID-01", grid("GRID-01", "moscow", "orth", "Сетка улиц по сторонам света", eng)
    yield "GRID-02", grid("GRID-02", "spb", "rotated", "Сетка улиц под углом к меридиану, Купчино", eng)


def raw(props: dict[str, Any], geom: Any) -> dict[str, Any]:
    from heatscenes.scene import rounded

    return {"type": "Feature", "properties": props, "geometry": None if geom is None else rounded(geom)}


def input_family(ctx: Ctx) -> Iterator[tuple[str, Callable[[], Scene]]]:
    def base(sid: str, title: str, rule_ids: list[str], eng: str, what: str) -> Scene:
        scene = single(
            ctx,
            "IN-base",
            "INPUT",
            title,
            "moscow",
            lambda c: c.origin == "future" and 10 < c.dist["all"] < 40 and not c.crosses["all"],
            rule_ids,
            eng,
            what,
            half=150.0,
        )
        scene.id = sid
        return scene

    def error(scene: Scene, ids: list[Any]) -> Scene:
        scene.must = {"diagnostic": {"exit_code_nonzero": True, "mentions": ids}}
        scene.should = {}
        scene.reference.clear()
        return scene

    def coincident() -> Scene:
        scene = base(
            "IN-01",
            "Две точки подключения в одной координате",
            ["ТП-2.1.камеры", "ТП-2.2.несколько", "ТП-2.5", "Р4"],
            "Две точки подключения в одном месте одного дома. Разветвление возможно только в камере, а "
            "участки к двум совпавшим точкам легли бы друг на друга. Инженер подключит одну точку, вторую "
            "оставит без сети с причиной «совпадает с подключённой» либо сервис сообщит о дубле во входе.",
            "Вторая точка подключения положена в ту же координату, что первая.",
        )
        cp = scene.points()[0]
        twin = scene.add_point(f"{cp.id}-twin", (cp.geom.x, cp.geom.y), 5.0, cp.props.get("oks_id"))
        scene.edits.append(f"добавлена точка {twin} в координате точки {cp.id}")
        scene.must = {"connected_any_of": [cp.id, twin]}
        scene.should = {"unconnected": {twin: ["coincident_point"]}}
        return scene

    def on_chamber() -> Scene:
        scene = base(
            "IN-02",
            "Точка подключения в координате камеры",
            ["ТП-2.4.камера10", "ТП-2.2", "Р11"],
            "Точка подключения стоит ровно на существующей камере. Инженер подключит её к этой камере "
            "участком нулевой или почти нулевой длины, либо сервис сообщит о совпадении; падать нельзя.",
            "Точка подключения перенесена в координату существующей камеры.",
        )
        ch = min(scene.of("heat_chamber"), key=lambda c: c.geom.distance(scene.points()[0].geom))
        cp = scene.add_point(f"cp-on-{ch.id}", (ch.geom.x, ch.geom.y), 4.0)
        scene.edits.append(f"добавлена точка {cp} в координате камеры {ch.id}")
        scene.should["connected"] = [cp]
        return scene

    def duplicate_ids() -> Scene:
        scene = base(
            "IN-03",
            "Дублирующиеся id",
            ["ТП-1.id", "ПР-9", "ТП-7"],
            "Два разных здания во входе имеют один id. Связи по id становятся неоднозначными. Инженер "
            "вернул бы файл с понятной диагностикой: какой id повторяется.",
            "Двум зданиям присвоен одинаковый id.",
        )
        a, b = [i for i in scene.items if i.kind == "oks_existing"][:2]
        b.props = dict(b.props, id=a.id)
        scene.edits.append(f"зданию присвоен id {a.id}, уже занятый другим зданием")
        return error(scene, [a.id])

    def null_geometry() -> Scene:
        scene = base(
            "IN-04",
            "Ограничение без геометрии",
            ["ТП-1", "ПР-9"],
            "У ограничения geometry = null. Инженер вернул бы файл с диагностикой: ненулевой код выхода "
            "и id объекта, как требует п. 9 протокола 16.09.",
            "Добавлено ограничение без геометрии.",
        )
        scene.raw_features.append(
            raw({"id": "park-null", "object_type": "restriction", "restriction_type": "park"}, None)
        )
        scene.edits.append("добавлено ограничение park-null с geometry = null")
        return error(scene, ["park-null"])

    def empty_geometry() -> Scene:
        scene = base(
            "IN-05",
            "Пустая геометрия",
            ["ТП-1", "ПР-9"],
            "У здания пустой список координат. Диагностика с id объекта, без падения.",
            "Добавлено здание с пустыми координатами.",
        )
        scene.raw_features.append(
            {
                "type": "Feature",
                "properties": {"id": "oks-empty", "object_type": "restriction", "restriction_type": "oks"},
                "geometry": {"type": "Polygon", "coordinates": []},
            }
        )
        scene.edits.append("добавлено здание oks-empty с пустыми координатами")
        return error(scene, ["oks-empty"])

    def bowtie() -> Scene:
        scene = base(
            "IN-06",
            "Полигон с самопересечением",
            ["ПР-9", "ТП-1"],
            "Контур здания самопересекается («бабочка»). По протоколу 16.09 (п. 9) невалидная геометрия "
            "даёт диагностическую ошибку с указанием объекта.",
            "Добавлено здание с самопересечением.",
        )
        c = scene.points()[0].geom
        x, y = c.x + 60, c.y + 60
        poly = Polygon([(x, y), (x + 30, y + 20), (x + 30, y), (x, y + 20)])
        scene.raw_features.append(
            raw({"id": "oks-bowtie", "object_type": "restriction", "restriction_type": "oks"}, poly)
        )
        scene.edits.append("добавлено здание oks-bowtie с самопересекающимся контуром")
        return error(scene, ["oks-bowtie"])

    def on_pipe() -> Scene:
        scene = base(
            "IN-07",
            "Новый дом прямо на трубе",
            ["ТП-2.2", "Р3", "ТП-2.4.новая", "Р11"],
            "Существующая труба проходит под новым домом, точка подключения лежит на трубе. Инженер "
            "врежется в трубу прямо у точки или в ближайшем месте у стены; при реальном проекте трубу "
            "вынесли бы из-под дома, но реконструкция не рассчитывается.",
            "Новый дом сдвинут на трубу, точка подключения на оси трубы.",
        )
        cp = scene.points()[0]
        house = house_of(scene, cp.id)
        pipe, _, proj = nearest_pipe(scene, house.geom)
        c = house.geom.centroid
        dx, dy = proj.x - c.x, proj.y - c.y
        house.geom = shapely.affinity.translate(house.geom, dx, dy)
        cp.geom = proj
        scene.forget_routes()
        scene.edits.append(f"дом сдвинут на трубу {pipe.id}, точка подключения на её оси")
        scene.must = {"connected": [cp.id]}
        scene.should = {"max_route_length_m": {cp.id: 30}}
        return scene

    def outside_polygon() -> Scene:
        scene = base(
            "IN-08",
            "Точка подключения без своего здания",
            ["ТП-1.2", "ТП-2.2", "ПР-4"],
            "Точка подключения лежит на пустом месте, полигона ОКС вокруг нет. Связь точки с полигоном "
            "не задаётся, поэтому это обычная цель: инженер ведёт трассу прямо к точке, соблюдая отступы "
            "от всех зданий.",
            "Точка подключения без полигона ОКС.",
        )
        cp = scene.points()[0]
        house = house_of(scene, cp.id)
        scene.items = [i for i in scene.items if i is not house]
        cp.props = {"id": cp.id, "object_type": "oks_connection_point", "flow_tph": house.props["flow_tph"]}
        scene.edits.append("полигон нового дома убран, точка осталась")
        return scene

    def on_wall() -> Scene:
        scene = base(
            "IN-09",
            "Точка подключения на стене здания",
            ["ТП-2.2", "Р3"],
            "Точка лежит ровно на контуре своего здания. После перевода координат она может оказаться в "
            "долях миллиметра снаружи. Инженер считает здание своим и ведёт ввод от этой точки.",
            "Точка подключения перенесена на контур дома.",
        )
        cp = scene.points()[0]
        house = house_of(scene, cp.id)
        cp.geom = nearest_points(house.geom.exterior, cp.geom)[0]
        scene.edits.append("точка подключения на контуре своего полигона")
        scene.forget_routes()
        expect_basic(scene)
        return scene

    def unknown_restriction() -> Scene:
        scene = base(
            "IN-10",
            "Неизвестный тип ограничения",
            ["ТП-4.доп_типы", "Р9"],
            "Между домом и сетью полигон с типом cemetery, которого нет в таблице 2. Поддержка "
            "необязательна: сервис либо игнорирует его, либо считает запретом. Падать нельзя. Инженер "
            "обошёл бы кладбище.",
            "Добавлено ограничение неизвестного типа cemetery.",
        )
        line, _, _ = straight(scene, scene.points()[0].id)
        poly = line.interpolate(0.5, normalized=True).buffer(6, cap_style="square")
        scene.raw_features.append(
            raw({"id": "cem-1", "object_type": "restriction", "restriction_type": "cemetery"}, poly)
        )
        scene.edits.append("на прямой от дома к трубе добавлен квадрат 12 м типа cemetery")
        scene.must = {"no_crash": True, "connected": [scene.points()[0].id]}
        scene.should = {"no_cross": ["cem-1"]}
        return scene

    def unknown_object() -> Scene:
        scene = base(
            "IN-11",
            "Неизвестный object_type",
            ["ТП-1.1", "ТП-1"],
            "Во входе объект bus_stop. Сервис его не знает и должен пропустить с предупреждением, расчёт "
            "остальных объектов не меняется.",
            "Добавлен объект bus_stop.",
        )
        scene.raw_features.append(
            raw({"id": "stop-1", "object_type": "bus_stop"}, scene.points()[0].geom.buffer(40).exterior.interpolate(0))
        )
        scene.edits.append("добавлена точка с object_type = bus_stop")
        scene.must["no_crash"] = True
        return scene

    def multiline_pipe() -> Scene:
        scene = base(
            "IN-12",
            "Участок сети из несвязных частей",
            ["ТП-1.1", "ПР-9"],
            "Участок heat_network задан MultiLineString из двух несвязных кусков, хотя по приложению это "
            "LineString. Инженер вернул бы диагностику с id участка.",
            "Добавлен участок-MultiLineString.",
        )
        c = scene.points()[0].geom
        from shapely.geometry import MultiLineString

        geom = MultiLineString([[(c.x + 80, c.y - 40), (c.x + 80, c.y)], [(c.x + 90, c.y + 10), (c.x + 90, c.y + 50)]])
        scene.raw_features.append(raw({"id": "hn-multi", "object_type": "heat_network", "diameter": 100}, geom))
        scene.edits.append("добавлен участок hn-multi из двух несвязных частей")
        return error(scene, ["hn-multi"])

    def no_flow() -> Scene:
        scene = base(
            "IN-13",
            "Точка подключения без flow_tph",
            ["ТП-1.1", "ПР-9"],
            "У точки подключения нет обязательного атрибута flow_tph. Инженер вернул бы диагностику: без "
            "расхода нельзя выбрать диаметр.",
            "У точки подключения убран flow_tph.",
        )
        cp = scene.points()[0]
        house = house_of(scene, cp.id)
        scene.items = [i for i in scene.items if i is not cp]
        scene.raw_features.append(raw({"id": cp.id, "object_type": "oks_connection_point"}, cp.geom))
        scene.edits.append(f"у точки {cp.id} нет flow_tph")
        del house
        return error(scene, [cp.id])

    def beyond() -> Scene:
        scene = base(
            "IN-14",
            "Дом за пределами охвата данных",
            ["ТП-2.1.граница", "ТП-2.5"],
            "Новый дом в 120 м за краем всех остальных объектов, на пустом месте. Граница поиска не "
            "задана, поэтому инженер подключит его прямой трассой к ближайшей трубе; это не «избыточный "
            "уход», ведь дом сам стоит за краем.",
            "Дом вынесен за пределы окна данных.",
        )
        cp = scene.points()[0]
        house = house_of(scene, cp.id)
        minx, miny, maxx, maxy = shapely.total_bounds([i.geom for i in scene.items if i.geom is not None])
        dx = maxx + 120 - house.geom.bounds[0]
        house.geom = shapely.affinity.translate(house.geom, dx, 0)
        cp.geom = shapely.affinity.translate(cp.geom, dx, 0)
        scene.forget_routes()
        scene.edits.append("новый дом сдвинут на 120 м за восточный край данных")
        scene.must = {"connected": [cp.id]}
        scene.should = {}
        return scene

    def orphan_chamber() -> Scene:
        scene = base(
            "IN-15",
            "Камера не на трубе",
            ["ТП-1.1", "ТП-2.4.камера10"],
            "Камера во входе стоит в 6 м от трубы, рядом с местом врезки. Камера не на сети не может быть "
            "местом присоединения. Инженер её не использует и ставит новую камеру на трубе.",
            "Добавлена камера в 6 м от трубы напротив дома.",
        )
        line, pipe, tie = straight(scene, scene.points()[0].id)
        dx, dy = unit(line.coords[0][0] - tie.x, line.coords[0][1] - tie.y)
        ch = scene.add("heat_chamber", Point(tie.x + dx * 6, tie.y + dy * 6), "hc-orphan")
        scene.edits.append("добавлена камера hc-orphan в 6 м от трубы")
        scene.must["not_tie_in"] = [ch]
        return scene

    def odd_dn() -> Scene:
        scene = base(
            "IN-16",
            "Существующая труба с Ду не из таблицы",
            ["ТП-1.1", "ТП-2.4.без_реконструкции", "ТП-3.2"],
            "У ближайшей трубы Ду 350, в таблице 1 такого нет. Существующая сеть не реконструируется и не "
            "проверяется, поэтому подключение возможно. Ду новой камеры инженер определит по ближайшему "
            "табличному не меньше 350 либо только по новым участкам.",
            "Ду ближайшей трубы задан 350.",
        )
        _, pipe, _ = straight(scene, scene.points()[0].id)
        pipe.props["diameter"] = 350
        scene.edits.append(f"у трубы {pipe.id} Ду 350")
        scene.must["no_crash"] = True
        return scene

    for sid, fn in (
        ("IN-01", coincident),
        ("IN-02", on_chamber),
        ("IN-03", duplicate_ids),
        ("IN-04", null_geometry),
        ("IN-05", empty_geometry),
        ("IN-06", bowtie),
        ("IN-07", on_pipe),
        ("IN-08", outside_polygon),
        ("IN-09", on_wall),
        ("IN-10", unknown_restriction),
        ("IN-11", unknown_object),
        ("IN-12", multiline_pipe),
        ("IN-13", no_flow),
        ("IN-14", beyond),
        ("IN-15", orphan_chamber),
        ("IN-16", odd_dn),
    ):
        yield sid, fn


def var_family(ctx: Ctx) -> Iterator[tuple[str, Callable[[], Scene]]]:
    def between() -> Scene:
        sid = "VAR-01"
        w = world("moscow")
        for c in ctx.shuffled(sid, candidates("moscow")):
            if c.origin != "future" or not 20 < c.dist["all"] < 70 or c.crosses["all"]:
                continue
            h = c.house.centroid
            t1 = c.tie["all"]
            others = [
                p for p in w.near(c.house, "heat_network", distance=c.dist["all"] + 15) if p.id != c.pipe["all"].id
            ]
            for p in others:
                t2 = nearest_points(c.house, p.geom)[1]
                a1 = math.atan2(t1.y - h.y, t1.x - h.x)
                a2 = math.atan2(t2.y - h.y, t2.x - h.x)
                if abs((a1 - a2 + math.pi) % (2 * math.pi) - math.pi) > 2.4 and not crossed_world(w, h, t2):
                    scene = from_candidate(
                        ctx,
                        sid,
                        "VAR",
                        "Две трубы по разные стороны дома",
                        c,
                        "all",
                        ["ТП-6.варианты", "ТП-6.S", "Р11"],
                        "Две трубы на близком расстоянии по разные стороны дома. Инженер показал "
                        "бы два содержательно разных варианта — врезка в одну и в другую трубу — "
                        "и выбрал дешёвый по S.",
                        "Новый дом между двумя трубами.",
                    )
                    expect_basic(scene)
                    scene.should["variants_min"] = 2
                    scene.should["variants_distinct_tie_in"] = True
                    return scene
        raise Skip(sid)

    yield "VAR-01", between


def crossed_world(w: Any, a: Point, b: Point) -> bool:
    return bool(w.near(LineString([a, b]), *(FORBID + ("road", "tram_tracks"))))


def families(ctx: Ctx) -> Iterator[tuple[str, Callable[[], Scene]]]:
    yield from tie_family(ctx)
    yield from scale_family(ctx)
    yield from cross_family(ctx)
    yield from area_family(ctx)
    yield from bld_family(ctx)
    yield from len_family(ctx)
    yield from grid_family(ctx)
    yield from input_family(ctx)
    yield from var_family(ctx)
