import json
import math
from collections.abc import Callable, Iterator
from typing import Any

import shapely
from shapely.geometry import LineString, Point, Polygon, box
from shapely.ops import nearest_points

from heatscenes.catalog import (
    Ctx,
    Skip,
    across,
    courtyards,
    drop_network_except,
    expect_basic,
    extend_barrier,
    expect_tree,
    from_candidate,
    halfplane,
    house_of,
    in_forbid,
    long_axis,
    reference_length,
    require_connected,
    route_bound,
    segments_at,
    straight,
    chambers_with,
    tie_scene,
    unit,
)
from heatscenes.finders import candidates
from heatscenes.reference import bound, shortest_path, width_of
from heatscenes.scene import ROOT, TIE_CHAMBER_M, Scene, dn_for, free_spot, obstacles, orientation, rect, rules
from heatscenes.world import BUILDINGS, FORBID, TO_UTM, Item, world

SYN = (413000.0, 6180000.0)
PRICE = {r["dn"]: r["new_rub_m"] for r in rules()["diameters"]}
CHAMBER_RUB = 3_000_000
TIE_RUB = 5_000_000
REAL_BEST_S = 12.6962
GAS = rules()["restrictions"]["gas_pipeline"]


def xy(dx: float, dy: float) -> tuple[float, float]:
    return SYN[0] + dx, SYN[1] + dy


def rbox(x0: float, y0: float, x1: float, y1: float) -> Polygon:
    return box(SYN[0] + x0, SYN[1] + y0, SYN[0] + x1, SYN[1] + y1)


def line(*pts: tuple[float, float]) -> LineString:
    return LineString([xy(*p) for p in pts])


def utm_point(lat: float, lon: float) -> tuple[float, float]:
    return TO_UTM.transform(lon, lat)


def score(cost: float, length: float) -> float:
    return 0.7 * cost / 25e6 + 0.3 * length / 100


def synth(
    sid: str,
    family: str,
    title: str,
    w: float,
    h: float,
    rule_ids: list[str],
    eng: str,
    what: str,
    center: tuple[float, float] = (0.0, 0.0),
) -> Scene:
    from heatscenes.catalog import new_scene

    scene = new_scene(sid, family, title, None, xy(*center), w / 2, rule_ids, eng, what, half_h=h / 2)
    scene.synthetic = True
    return scene


def house_at(
    scene: Scene,
    x0: float,
    y0: float,
    x1: float,
    y1: float,
    cp: tuple[float, float],
    flow: float,
    house_id: str | None = None,
) -> str:
    return scene.add_house(rbox(x0, y0, x1, y1), 9, flow=flow, cp=xy(*cp), house_id=house_id)


def pipe(scene: Scene, pid: str, dn: int, *pts: tuple[float, float]) -> str:
    return scene.add("heat_network", line(*pts), pid, diameter=dn)


def side_of(origin: Point, direction: tuple[float, float], p: Point) -> int:
    return 1 if direction[0] * (p.y - origin.y) - direction[1] * (p.x - origin.x) > 0 else -1


def nearby(city: str, lat: float, lon: float, radius: float, kinds: tuple[str, ...]) -> list[Item]:
    x, y = utm_point(lat, lon)
    return world(city).near(Point(x, y).buffer(radius), *kinds)


def cross_family(ctx: Ctx) -> Iterator[tuple[str, Callable[[], Scene]]]:
    def gas_across() -> Scene:
        sid = "CROSS-14"
        for c in ctx.shuffled(sid, candidates("moscow")):
            if c.origin != "future" or not 25 < c.dist["all"] < 70 or c.crosses["all"] or in_forbid("moscow", c.house):
                continue
            scene = from_candidate(
                ctx,
                sid,
                "CROSS",
                "Газопровод поперёк пути к сети",
                c,
                "all",
                ["ТП-4.gas_pipeline", "ТП-4.спецпроход", "ТП-3.1", "Р7", "ТП-2.1.узел", "Р29-14"],
                "Между домом и сетью газопровод, обойти его в пределах данных нельзя. Инженер "
                "пересекает газ одним прямым участком, спецпроход по 2 м в обе стороны от точки "
                "пересечения вдоль оси новой сети, без поворотов внутри, Kспец 1,25.",
                "Новый дом, поперёк пути к сети проложен газопровод, сеть на стороне дома убрана.",
            )
            cp = scene.points()[0].id
            path, _, _ = straight(scene, cp)
            m = path.interpolate(0.5, normalized=True)
            d = unit(path.coords[-1][0] - path.coords[0][0], path.coords[-1][1] - path.coords[0][1])
            g = (-d[1], d[0])
            gid = scene.add(
                "gas_pipeline",
                LineString([(m.x - g[0] * 2000, m.y - g[1] * 2000), (m.x + g[0] * 2000, m.y + g[1] * 2000)]),
                "gas-cross-14",
            )
            side = side_of(m, g, scene.get(cp).geom)
            drop_network_except(
                scene,
                halfplane((m.x, m.y), g, side).intersection(scene.area.buffer(60)),
                set(),
                "сеть на стороне дома от газопровода убрана",
            )
            scene.edits.append(f"газопровод {gid} проложен поперёк пути от дома к трубе, длиной 4 км")
            if not scene.of("heat_network") or reference_length(scene, cp) is None:
                continue
            expect_basic(scene)
            scene.must.update(
                {
                    "crossings_min": {"gas_pipeline": 1},
                    "special_kspec": {"gas_pipeline": 1.25},
                    "no_turn_in_special": True,
                }
            )
            scene.should["crossings_max"] = {"gas_pipeline": 1}
            return scene
        raise Skip(sid)

    yield "CROSS-14", gas_across

    def along_gas(sid: str, delta: float) -> Callable[[], Scene]:
        def build() -> Scene:
            for c in ctx.shuffled(sid, candidates("moscow")):
                if c.origin != "future" or c.crosses["all"] or not 50 < c.dist["all"] < 120:
                    continue
                if in_forbid("moscow", c.house):
                    continue
                scene = from_candidate(
                    ctx, sid, "CROSS", "", c, "all", ["ТП-4.gas_pipeline", "ТП-4.отступ", "ТП-3.1", "Р7"], "", ""
                )
                cp = scene.points()[0].id
                path, _, _ = straight(scene, cp)
                dn = dn_for(scene.flow(scene.get(cp)))
                need = GAS["clearance_m"] + GAS["half_width_m"] + width_of(dn) / 2
                offset = need + delta
                part = LineString([path.interpolate(3.0), path.interpolate(path.length + 40)])
                gas = part.offset_curve(offset)
                gid = scene.add("gas_pipeline", gas, f"gas-{sid.lower()}")
                scene.edits.append(f"добавлен газопровод {gid} вдоль прямой от дома к трубе в {offset:.2f} м от неё")
                if reference_length(scene, cp) is None:
                    continue
                expect_basic(scene)
                scene.must["clearance_m"] = {gid: GAS["clearance_m"]}
                scene.should["crossings_max"] = {"gas_pipeline": 0}
                verdict = (
                    "недопустима: трасса обязана отойти от газа" if delta < 0 else "допустима: лишний запас не нужен"
                )
                scene.title = f"Вдоль газопровода в {offset:.2f} м от прямой"
                scene.description = (
                    f"Газопровод параллельно прямой от дома к трубе в {offset:.2f} м от неё; "
                    f"нужно {need:.3f} м для Ду {dn}. " + scene.description
                )
                scene.engineer = (
                    f"Отступ считается от внешней границы габарита газа (0,4 м) до внешней границы "
                    f"пары Ду {dn}: 2 + 0,2 + {width_of(dn) / 2:.3f} = {need:.3f} м между осями. "
                    f"Прямая в {offset:.2f} м {verdict}."
                )
                if delta > 0:
                    scene.should["max_route_length_m"] = {cp: math.ceil(1.05 * path.length + 8)}
                return scene
            raise Skip(sid)

        return build

    yield "CROSS-20A", along_gas("CROSS-20A", -0.25)
    yield "CROSS-20B", along_gas("CROSS-20B", 0.15)

    def through_main() -> Scene:
        scene = synth(
            "CROSS-22",
            "CROSS",
            "Перейти магистраль Ду 1000 к малой трубе",
            400,
            200,
            ["ТП-4.heat_network", "Р10", "Р29-14", "ТП-3.2", "ТП-2.4.новая", "ТП-6.S"],
            "",
            "Магистраль Ду 1000 в 15 м от дома, за ней в 15 м труба Ду 80.",
            center=(0, -20),
        )
        cp = house_at(scene, -24, 0, 24, 16, (0, 1), 10.0)
        pipe(scene, "hn-main", 1000, (-200, -15), (200, -15))
        pipe(scene, "hn-small", 80, (-200, -30), (200, -30))
        c80 = PRICE[80]
        s_main = score(16 * c80 + 8_000_000, 16)
        s_small = score(27 * c80 + 4 * c80 * 1.05 + CHAMBER_RUB, 31)
        scene.engineer = (
            f"Врезка в магистраль: 16 м от ввода и новая камера на Ду 1000 за 8 млн (по трактовке команды Ду камеры по "
            f"наибольшему из примыканий, включая разрезанную трубу), S ≈ {s_main:.3f}. Переход магистрали к трубе Ду "
            f"80: 31 м, из них 4 м спецпроход с Kспец 1,05, камера 3 млн, S ≈ {s_small:.3f}. Инженер переходит "
            "магистраль. Конфликт правил: за зоной ±2 м отступ до габарита Ду 1000 должен быть 1 + 1,325 + 0,235 = "
            "2,56 м между осями, а при пересечении под прямым углом граница зоны стоит в 2 м от оси магистрали. "
            "Принято: отступ у самой пересекаемой трубы проверяется вне круга 3 м вокруг точки пересечения."
        )
        scene.must.update({"connected": [cp], "dn_by_flow_and_length": True, "special_kspec": {"heat_network": 1.05}})
        scene.should.update(
            {
                "tie_in": {cp: {"kind": "new_chamber", "pipes": ["hn-small"]}},
                "crossings_min": {"heat_network": 1},
                "crossings_max": {"heat_network": 1},
                "score_max": round(s_small + 0.02, 4),
            }
        )
        return scene

    yield "CROSS-22", through_main

    def oblique_gas() -> Scene:
        scene = synth(
            "CROSS-23",
            "CROSS",
            "Газопровод под углом 25° к пути",
            400,
            200,
            ["ТП-4.gas_pipeline", "ТП-4.угол", "ТП-4.отступ", "Р29-14", "Р7"],
            "Газопровод пересекает путь от дома к трубе под углом 25°. Угол для газа в таблице 2 не задан, "
            "но на границе зоны ±2 м до оси газа остаётся 2·sin 25° ≈ 0,85 м, а нужно не меньше 2,4 м. "
            "Инженер переходит газ почти под прямым углом, а дальше держит отступ. Даже при 90° граница "
            "зоны в 2 м ближе нормы отступа; это вопрос к организаторам. Принято: отступ проверяется вне "
            "круга 3 м вокруг точки пересечения.",
            "Дом, в 40 м труба Ду 100, между ними газопровод под углом 25° к прямой.",
            center=(0, -20),
        )
        cp = house_at(scene, -24, 0, 24, 16, (0, 1), 10.0)
        pipe(scene, "hn-1", 100, (-200, -40), (200, -40))
        a = math.radians(25)
        g = (math.sin(a), math.cos(a))
        gid = scene.add(
            "gas_pipeline", line((-g[0] * 400, -20 - g[1] * 400), (g[0] * 400, -20 + g[1] * 400)), "gas-oblique"
        )
        scene.must.update(
            {
                "connected": [cp],
                "crossings_min": {"gas_pipeline": 1},
                "special_kspec": {"gas_pipeline": 1.25},
                "clearance_m": {gid: 2.0},
                "no_turn_in_special": True,
                "dn_by_flow_and_length": True,
            }
        )
        scene.should.update(
            {"crossing_angle_min_deg": {"gas_pipeline": 60}, "max_route_length_m": {cp: route_bound(scene, cp)}}
        )
        return scene

    yield "CROSS-23", oblique_gas

    def corridor(sid: str, strip: float) -> Callable[[], Scene]:
        def build() -> Scene:
            title = (
                "Улица во всю ширину между парком и школой"
                if strip == 0
                else (f"Улица между парком и школой, у забора полоса {strip:g} м")
            )
            scene = synth(
                sid,
                "CROSS",
                title,
                400,
                200,
                ["ТП-4.road", "ТП-4.спецпроход", "Р6", "ТП-4.park", "ТП-4.social_area", "ТП-2.5", "ТП-2.3.длина"],
                "",
                "Улица шириной 20 м между парком и территорией школы, все три тянутся на 7 км в обе стороны. Дом "
                "и труба стоят в карманах, вырезанных в парке у длинной стороны дороги, в 350 м друг от друга.",
            )
            pockets = shapely.union_all([rbox(-200, 9, -150, 40), rbox(150, 9, 200, 40)])
            scene.add("park", rbox(-7000, 10, 7000, 100).difference(pockets), "park-north")
            scene.add("social_area", rbox(-7000, -100, 7000, -10), "school-south")
            scene.add("road", rbox(-7000, -10, 7000, 10 - strip), "road-lane", highway="residential")
            cp = house_at(scene, -190, 18, -160, 30, (-175, 19), 8.0)
            pipe(scene, "hn-1", 150, (175, 14), (175, 36))
            if strip == 0:
                scene.engineer = (
                    "Проезжая часть занимает улицу от забора до забора, торцов у неё в пределах 7 км нет. "
                    "Спецпроход — один прямой участок, который входит в полигон дороги под углом не меньше 45° к "
                    "границе и выходит с другой стороны; из кармана в карман прямой участок идёт почти вдоль "
                    "длинной стороны дороги, и угол входа меньше 45°. Обход парка и школы длиннее 14 км, это "
                    "больше предельной длины Ду 1400 (11 276 м). Допустимой трассы нет, дом без сети."
                )
                scene.must["unconnected"] = {cp: ["no_route", "enclosed"]}
                scene.should.update(
                    {
                        "special_entry_angle_min_deg": 45,
                        "special_length_max_m": {"road-lane": round(20 / math.sin(math.radians(45)) + 6, 1)},
                    }
                )
            else:
                scene.engineer = (
                    f"У забора парка свободная полоса {strip:g} м: ось пары встаёт в 1,5 м плюс "
                    "полуширина от дороги и в 1 м плюс полуширина от парка. Инженер ведёт трассу по "
                    "полосе без спецпроходов."
                )
                scene.must.update({"connected": [cp], "dn_by_flow_and_length": True})
                scene.should.update({"max_route_length_m": {cp: route_bound(scene, cp)}, "crossings_max": {"road": 0}})
            return scene

        return build

    yield "CROSS-24A", corridor("CROSS-24A", 0)
    yield "CROSS-24B", corridor("CROSS-24B", 6.0)

    def twice() -> Scene:
        scene = synth(
            "CROSS-25",
            "CROSS",
            "Одна дорога пересечена дважды",
            300,
            200,
            ["ТП-4.road", "ТП-4.спецпроход", "ТП-2.1.узел", "ТП-4.угол"],
            "Дорога — один полигон из двух параллельных проездов, соединённых далеко за пределами данных. "
            "Дом с одной стороны, труба с другой. Инженер делает два отдельных спецпрохода по одной "
            "дороге, у каждого свои технические узлы на границах зоны.",
            "Проезд петлёй: два параллельных полотна одного полигона, дом и труба по разные стороны.",
        )
        road = shapely.union_all([rbox(-40, -2000, -34, 1506), rbox(34, -2000, 40, 1506), rbox(-40, 1500, 40, 1506)])
        scene.add("road", road, "road-loop", highway="service")
        cp = house_at(scene, -100, -8, -70, 8, (-71, 0), 10.0)
        pipe(scene, "hn-1", 100, (80, -100), (80, 100))
        scene.must.update(
            {
                "connected": [cp],
                "crossings_min": {"road": 2},
                "crossing_angle_min_deg": {"road": 45},
                "special_kspec": {"road": 1.6},
                "dn_by_flow_and_length": True,
            }
        )
        scene.should.update({"crossings_max": {"road": 2}, "max_route_length_m": {cp: route_bound(scene, cp)}})
        return scene

    yield "CROSS-25", twice

    def detour_or_cross(sid: str, garages: bool) -> Callable[[], Scene]:
        def build() -> Scene:
            scene = synth(
                sid,
                "CROSS",
                "Обход гаражей или переход улицы" + (" (обход длинный)" if garages else " (обход короткий)"),
                300,
                200,
                ["ТП-4.road", "ТП-4.oks", "ТП-6.S", "ТП-6.стоимость"],
                "",
                "Своя труба за рядом гаражей, чужая — за улицей шириной 20 м."
                if garages
                else "Своя труба в 25 м по свободной земле, чужая — за улицей шириной 20 м.",
            )
            cp = house_at(scene, 0, 0, 16, 12, (1, 1), 10.0)
            scene.add("road", rbox(-2000, -26, 2000, -6), "road-wide", highway="secondary")
            pipe(scene, "hn-own", 80, (-25, -2), (-25, 60))
            pipe(scene, "hn-across", 80, (-100, -30), (100, -30))
            if garages:
                scene.add("oks_existing", rbox(-14, -3, -9, 22), "garages-1")
            own = reference_length_to(scene, cp, "hn-own")
            c80 = PRICE[80]
            s_own = score(own * c80 + CHAMBER_RUB, own)
            s_cross = score(5 * c80 + 26 * c80 * 1.6 + CHAMBER_RUB, 31)
            best = "hn-own" if s_own < s_cross else "hn-across"
            scene.engineer = (
                f"Обход до своей трубы ≈{own:.0f} м, S ≈ {s_own:.3f}; переход улицы 31 м, из них 26 м "
                f"спецпроход с Kспец 1,6, S ≈ {s_cross:.3f}. Граница выгоды около 37 м обхода. "
                f"Инженер выбирает {'обход' if best == 'hn-own' else 'переход'}."
            )
            scene.must.update({"connected": [cp], "dn_by_flow_and_length": True})
            scene.should.update(
                {
                    "tie_in": {cp: {"kind": "new_chamber", "pipes": [best]}},
                    "score_max": round(min(s_own, s_cross) + 0.02, 4),
                }
            )
            return scene

        return build

    yield "CROSS-26A", detour_or_cross("CROSS-26A", False)
    yield "CROSS-26B", detour_or_cross("CROSS-26B", True)

    def rail_full() -> Scene:
        sid = "CROSS-27A"
        pool = [r for r in world("moscow").of("railway") if r.geom.area > 3000]
        for attempt in range(12):
            try:
                scene, ob, cp = across(
                    ctx,
                    sid,
                    "CROSS",
                    "Ж/д на всю ширину данных",
                    "moscow",
                    pool,
                    ["ТП-4.railway", "Р29-3", "ТП-2.5", "ТП-2.1.граница", "ТП-6.штраф"],
                    "Сеть только за железной дорогой, проездов под путями в окне нет, полотно продлено "
                    "прямыми полосами на 7 км за окно. Пересекать ж/д нельзя (разъяснение 29.09, п. 3), а "
                    "обход длиннее 14 км и больше предельной длины Ду 1400 (11 276 м). Дом без сети со "
                    "штрафом.",
                    "Новый дом у железной дороги, сеть за путями.",
                    half=260.0,
                    need_route=False,
                    key=f"{sid}:{attempt}",
                )
            except Skip:
                continue
            if reference_length(scene, cp) is not None:
                continue
            scene.id = sid
            extend_barrier(scene, "railway")
            scene.must.update(
                {"unconnected": {cp: ["no_route", "enclosed"]}, "no_cross": [i.id for i in scene.of("railway")]}
            )
            return scene
        raise Skip(sid)

    yield "CROSS-27A", rail_full

    def rail_stub() -> Scene:
        sid = "CROSS-27B"
        w = world("moscow")
        pool = []
        for r in w.of("railway"):
            if not 800 < r.geom.area < 15000:
                continue
            group = shapely.union_all([g.geom for g in w.near(r.geom.buffer(3), "railway")])
            minx, miny, maxx, maxy = group.bounds
            if max(maxx - minx, maxy - miny) < 220:
                pool.append(r)
        scene, ob, cp = across(
            ctx,
            sid,
            "CROSS",
            "Ж/д тупик в пределах данных",
            "moscow",
            pool,
            ["ТП-4.railway", "Р29-3", "ТП-2.5"],
            "Подъездной путь кончается тупиком в пределах района. Пересекать его нельзя, но "
            "обойти вокруг тупика можно: инженер ведёт трассу вокруг с отступом 1 м.",
            "Новый дом у подъездного пути, сеть за ним.",
            half=240.0,
        )
        scene.must.update({"no_cross": [i.id for i in scene.of("railway")], "dn_by_flow_and_length": True})
        require_connected(scene, [cp])
        scene.should["max_route_length_m"] = {cp: route_bound(scene, cp)}
        return scene

    yield "CROSS-27B", rail_stub


def edge_normal(poly: Polygon, at: Point) -> tuple[float, float] | None:
    ring = poly.exterior
    s = ring.project(at)
    a, b = ring.interpolate(max(0.0, s - 3)), ring.interpolate(min(ring.length, s + 3))
    d = unit(b.x - a.x, b.y - a.y)
    for n in ((-d[1], d[0]), (d[1], -d[0])):
        if not poly.contains(Point(at.x + n[0] * 5, at.y + n[1] * 5)) and poly.contains(
            Point(at.x - n[0] * 5, at.y - n[1] * 5)
        ):
            return n
    return None


def big_chamber(ch: Item) -> bool:
    return any(p.props["diameter"] >= 600 for p in world("moscow").near(ch.geom, "heat_network", distance=0.5))


def transit_busy(ch: Item) -> bool:
    through = ends = 0
    for p in world("moscow").near(ch.geom, "heat_network", distance=0.5):
        end = min(Point(p.geom.coords[0]).distance(ch.geom), Point(p.geom.coords[-1]).distance(ch.geom))
        if end > 1.0:
            through += 1
        elif end <= 0.5:
            ends += 1
        else:
            return False
    return through == 1 and ends == 2


def reference_length_to(scene: Scene, cp: str, pid: str) -> float:
    from heatscenes.reference import shortest

    saved = scene.items
    scene.items = [i for i in saved if i.kind != "heat_network" or i.id == pid]
    try:
        length = shortest(scene, cp, block=("road", "tram_tracks"))
    finally:
        scene.items = saved
    if length is None:
        raise Skip(scene.id)
    return length


def gap_flow(ctx: Ctx, sid: str, key: str, flow: float, probe: float, gap_w: float, title: str, eng: str) -> Scene:
    for c in ctx.shuffled(key, candidates("moscow")):
        if c.origin != "future" or not 45 < c.dist["all"] < 90 or c.crosses["all"] or in_forbid("moscow", c.house):
            continue
        scene = from_candidate(
            ctx,
            sid,
            "FLOW",
            title,
            c,
            "all",
            ["ТП-2.3.ду", "ТП-4.oks", "ТП-3.1", "ТП-3.2"],
            eng,
            f"Новый дом с расходом {flow:g} т/ч, на пути к трубе щель {gap_w:g} м между двумя зданиями.",
        )
        cp = scene.points()[0].id
        scene.houses()[0].props["flow_tph"] = probe
        path, _, _ = straight(scene, cp)
        m = path.interpolate(0.5, normalized=True)
        d = unit(path.coords[-1][0] - path.coords[0][0], path.coords[-1][1] - path.coords[0][1])
        n = (-d[1], d[0])
        blocks = []
        for sign in (1, -1):
            off = gap_w / 2 + 20
            cx, cy = m.x + n[0] * off * sign, m.y + n[1] * off * sign
            blocks.append(rect(cx, cy, 40, 10, math.degrees(math.atan2(n[1], n[0]))))
        if any(b.intersects(scene.houses()[0].geom.buffer(3)) for b in blocks):
            continue
        ids = [scene.add("oks_existing", b, f"garages-{sid.lower()}-{k}") for k, b in enumerate(blocks, 1)]
        scene.edits.append(f"расход дома задан {flow:g} т/ч; на пути поставлены два здания со щелью {gap_w:g} м")
        big = reference_length(scene, cp)
        scene.reference.clear()
        scene.houses()[0].props["flow_tph"] = 20.0
        small = reference_length(scene, cp)
        scene.reference.clear()
        if big is None or small is None or small > 1.3 * path.length + 10 or big < small + 15:
            continue
        scene.houses()[0].props["flow_tph"] = flow
        expect_basic(scene)
        dn = dn_for(flow, reference_length(scene, cp))
        scene.must["final_dn_range"] = {cp: [dn, dn]}
        scene.must["no_cross"] = ids
        scene.reference["gap_m"] = gap_w
        return scene
    raise Skip(sid)


def flow_family(ctx: Ctx) -> Iterator[tuple[str, Callable[[], Scene]]]:
    def pair(sid: str, key: str, flow: float, gap_w: float, title: str, eng: str) -> Callable[[], Scene]:
        probe = 1500.0 if key == "FLOW-01" else 8000.0
        return lambda: gap_flow(ctx, sid, key, flow, probe, gap_w, title, eng)

    yield (
        "FLOW-01",
        pair(
            "FLOW-01",
            "FLOW-01",
            1500.0,
            13.7,
            "Ду 500: щель 13,7 м уже нормы 7 м",
            "Расход 1 500 т/ч требует Ду 500: отступ от зданий 7 м, ширина пары 1,67 м, нужна щель не "
            "меньше 2 × 7 + 1,67 = 15,7 м. Щель 13,7 м уже, поэтому инженер обходит здания.",
        ),
    )
    yield (
        "FLOW-06",
        pair(
            "FLOW-06",
            "FLOW-01",
            20.0,
            13.7,
            "Та же щель 13,7 м при Ду 100",
            "Та же геометрия, что в FLOW-01, но расход 20 т/ч: Ду 100, отступ 5 м, нужно 2 × 5 + 0,51 = "
            "10,5 м. Щель 13,7 м проходима, инженер идёт напрямую.",
        ),
    )
    yield (
        "FLOW-02",
        pair(
            "FLOW-02",
            "FLOW-02",
            8000.0,
            18.7,
            "Ду 1000: щель 18,7 м уже нормы 9 м",
            "Расход 8 000 т/ч требует Ду 1000: отступ 9 м, ширина пары 2,65 м, нужна щель не меньше "
            "2 × 9 + 2,65 = 20,65 м. Щель 18,7 м уже, поэтому инженер обходит здания.",
        ),
    )
    yield (
        "FLOW-07",
        pair(
            "FLOW-07",
            "FLOW-02",
            20.0,
            18.7,
            "Та же щель 18,7 м при Ду 100",
            "Та же геометрия, что в FLOW-02, но расход 20 т/ч: щель проходима, инженер идёт напрямую.",
        ),
    )


def len_family(ctx: Ctx) -> Iterator[tuple[str, Callable[[], Scene]]]:
    def tree_limit() -> Scene:
        scene = synth(
            "LEN-08",
            "LEN",
            "Предельная длина на разветвлённой сети",
            360,
            260,
            ["ТП-2.3.длина", "Р2", "ТП-2.3.ду", "Р1", "Р29-20", "ТП-2.3.неубывание"],
            "Коридор Т-образный, остальное — парк. Путь каждого дома около 217 м: больше предела Ду 50 "
            "(181 м). Правильно: ветки Ду 50 до камеры ветвления, общий участок Ду 65 (расход 3,0 т/ч). На "
            "камере меняется расход, значит Ду может смениться и отсчёт длины начинается заново. Ошибки, "
            "которые ловит сцена: сложение длин параллельных ветвей, проверка предела по одному участку "
            "вместо пути, Ду 65 на всех участках.",
            "Синтетика: труба Ду 200, Т-образный коридор в парке, два дома по 1,5 т/ч на концах.",
            center=(0, 100),
        )
        park = rbox(-400, 1, 400, 300).difference(
            shapely.union_all(
                [rbox(-6, 1, 6, 106), rbox(-106, 94, 106, 106), rbox(-130, 88, -106, 112), rbox(106, 88, 130, 112)]
            )
        )
        scene.add("park", park, "park-1")
        pipe(scene, "hn-1", 200, (-300, -8), (300, -8))
        a = house_at(scene, 110, 92, 126, 108, (111, 100), 1.5)
        b = house_at(scene, -126, 92, -110, 108, (-111, 100), 1.5)
        scene.must.update({"connected": [a, b], "dn_by_flow_and_length": True})
        scene.should.update({"final_dn_range": {a: [50, 50], b: [50, 50]}, "tie_segment_dn": 65, "same_tree": [[a, b]]})
        return scene

    yield "LEN-08", tree_limit

    def monotone() -> Scene:
        scene = synth(
            "LEN-09",
            "LEN",
            "Неубывание Ду поднимает магистраль",
            1000,
            200,
            ["ТП-2.3.неубывание", "ТП-2.3.длина", "Р1", "Р29-20", "ТП-2.3.ду"],
            "Ветка A около 410 м при 1 т/ч проходит только Ду 100 (предел 419 м). Общему участку около 38 м "
            "по расходу хватает Ду 50, но Ду к врезке не уменьшается, а при Ду 100 непрерывный путь A около "
            "450 м больше 419 м. Поэтому общий участок Ду 125, ветка A Ду 100, ветка B Ду 50.",
            "Синтетика: труба Ду 200, коридоры в парке, дом A в 410 м от камеры ветвления, дом B в 30 м.",
            center=(200, 30),
        )
        park = rbox(-500, 1, 900, 300).difference(
            shapely.union_all(
                [
                    rbox(-6, 1, 6, 36),
                    rbox(-6, 24, 406, 36),
                    rbox(-26, 24, 6, 36),
                    rbox(406, 14, 430, 46),
                    rbox(-50, 14, -26, 46),
                ]
            )
        )
        scene.add("park", park, "park-1")
        pipe(scene, "hn-1", 200, (-300, -8), (800, -8))
        a = house_at(scene, 410, 22, 426, 38, (411, 30), 1.0)
        b = house_at(scene, -46, 22, -30, 38, (-31, 30), 1.0)
        scene.must.update({"connected": [a, b], "dn_by_flow_and_length": True})
        scene.should.update(
            {"final_dn_range": {a: [100, 100], b: [50, 50]}, "tie_segment_dn": 125, "same_tree": [[a, b]]}
        )
        return scene

    yield "LEN-09", monotone


def tie_family(ctx: Ctx) -> Iterator[tuple[str, Callable[[], Scene]]]:
    def border(sid: str, along: float) -> Callable[[], Scene]:
        def build() -> Scene:
            def check(scene: Scene, cp_id: str, tie: Point, ch: Item) -> bool:
                if abs(tie.distance(ch.geom) - along) >= 0.15 or segments_at(scene, ch) != 3:
                    return False
                if not inside:
                    return True
                length, _ = shortest_path(scene, cp_id)
                near, _ = shortest_path(scene, cp_id, target=ch.geom.buffer(TIE_CHAMBER_M - 0.5))
                return (
                    near is not None
                    and str(cp_id) not in scene.far_wall
                    and near <= min(length + 15.0, bound(length))
                )

            inside = along < 10
            scene = tie_scene(
                ctx,
                sid,
                f"Проекция в {along:g} м от камеры",
                "moscow",
                3,
                along,
                18.0,
                ["ТП-2.4.камера10", "Р11", "Р29-9", "ТП-3.2"] + ([] if inside else ["ТП-2.4.новая"]),
                (
                    "Проекция точки на трубу в 9,8 м от камеры с одним свободным местом. Правило 10 м "
                    "требует присоединиться к этой камере."
                    if inside
                    else "Проекция в 10,3 м от камеры, правило 10 м не действует. Новая камера на трубе "
                    "напротив дома дешевле врезки в существующую на 2 млн, инженер ставит её."
                ),
                f"Новый дом у трубы Ду 50–100, проекция точки в {along:g} м от камеры.",
                check,
                pipe_ok=lambda p: p.props["diameter"] <= 100,
            )
            expect_basic(scene)
            cp = scene.points()[0].id
            ch = min(scene.of("heat_chamber"), key=lambda c: c.geom.distance(straight(scene, cp)[2]))
            scene.should.pop("tie_in", None)
            if inside:
                length, path = shortest_path(scene, cp)
                near, _ = shortest_path(scene, cp, target=ch.geom.buffer(TIE_CHAMBER_M - 0.5))
                scene.engineer += (
                    f" Кратчайший допустимый путь {length:.0f} м выходит на трубу в "
                    f"{Point(path[-1]).distance(ch.geom):.0f} м от камеры, путь к трубе ближе 10 м от неё — "
                    f"{near:.0f} м."
                )
                scene.must["tie_in"] = {cp: {"kind": "existing_chamber"}}
                scene.must["no_new_chamber_within_m"] = {"chambers": [ch.id], "radius_m": 10.0}
                scene.should["tie_in"] = {cp: {"kind": "existing_chamber", "chambers": [ch.id]}}
            else:
                scene.should["tie_in"] = {cp: {"kind": "new_chamber"}}
            return scene

        return build

    yield "TIE-16A", border("TIE-16A", 9.8)
    yield "TIE-16B", border("TIE-16B", 10.3)

    def far_chamber() -> Scene:
        sid = "TIE-17"

        def check(scene: Scene, cp_id: str, tie: Point, ch: Item) -> bool:
            return abs(tie.distance(ch.geom) - 25) < 2.5 and segments_at(scene, ch) < 4

        scene = None
        for slots in (3, 2, 1):
            try:
                scene = tie_scene(
                    ctx,
                    sid,
                    "Камера на магистрали в 25 м",
                    "moscow",
                    slots,
                    25.0,
                    20.0,
                    ["ТП-2.4.новая", "ТП-3.2", "Р29-9", "ТП-6.S"],
                    "Дом в 20 м от магистрали Ду 600 и больше, свободная камера в 25 м от проекции. Новая камера "
                    "на магистрали стоит 8 млн (по наибольшему Ду), врезка в существующую 5 млн, путь к ней "
                    "длиннее примерно на 10–15 м. Выигрыш врезки в камеру около 0,02–0,03 по S; компромисс "
                    "неочевиден.",
                    "Новый дом у магистрали, свободная камера в 25 м от проекции.",
                    check,
                    pipe_ok=lambda p: p.props["diameter"] >= 600,
                    allow_roads=True,
                    chambers=[c for c in chambers_with("moscow", slots) if big_chamber(c)],
                )
                break
            except Skip:
                continue
        if scene is None:
            raise Skip(sid)
        expect_basic(scene)
        cp = scene.points()[0].id
        ch = min(scene.of("heat_chamber"), key=lambda c: c.geom.distance(straight(scene, cp)[2]))
        scene.should["tie_in"] = {cp: {"kind": "existing_chamber", "chambers": [ch.id]}}
        return scene

    yield "TIE-17", far_chamber

    def broken() -> Scene:
        sid = "TIE-18"

        def check(scene: Scene, cp_id: str, tie: Point, ch: Item) -> bool:
            return tie.distance(ch.geom) <= 5 and segments_at(scene, ch) >= 5

        scene = tie_scene(
            ctx,
            sid,
            "Испорченные камеры во входе",
            "moscow",
            5,
            4.0,
            18.0,
            ["ТП-2.1.камеры", "Р12", "ТП-2.4.новая", "ТП-1.1"],
            "В 4 м от проекции камера, к которой во входе уже примыкают пять участков, и её дубль в той "
            "же координате. Обе камеры использовать нельзя: мест нет. Инженер ставит новую камеру на "
            "трубе и не падает на испорченном входе.",
            "Новый дом у трубы, рядом камера с пятью примыканиями и её дубль.",
            check,
        )
        cp = scene.points()[0].id
        ch = min(scene.of("heat_chamber"), key=lambda c: c.geom.distance(straight(scene, cp)[2]))
        dup = scene.add("heat_chamber", Point(ch.geom.x, ch.geom.y), f"{ch.id}-dup")
        scene.edits.append(f"добавлен дубль камеры {ch.id} в той же координате: {dup}")
        expect_basic(scene)
        scene.must.update({"no_crash": True, "not_tie_in": [ch.id, dup]})
        scene.should["tie_in"] = {cp: {"kind": "new_chamber"}}
        return scene

    yield "TIE-18", broken

    def busy_transit() -> Scene:
        sid = "TIE-15"

        def check(scene: Scene, cp_id: str, tie: Point, ch: Item) -> bool:
            through = ends = 0
            for p in scene.of("heat_network"):
                if p.geom.distance(ch.geom) > 0.5:
                    continue
                end = min(Point(p.geom.coords[0]).distance(ch.geom), Point(p.geom.coords[-1]).distance(ch.geom))
                if end > 1.0:
                    through += 1
                elif end <= 0.5:
                    ends += 1
                else:
                    return False
            return through == 1 and ends == 2 and 4.0 <= tie.distance(ch.geom) <= 8.0

        scene = tie_scene(
            ctx,
            sid,
            "Транзитная камера занята",
            "moscow",
            4,
            6.0,
            18.0,
            ["ТП-2.1.камеры", "ТП-2.1.транзит", "Р12", "ТП-2.4.новая", "ТП-2.4.камера10"],
            "Через камеру насквозь проходит труба (это два примыкания), и в ней кончаются ещё два отвода: всего "
            "четыре. Проекция дома в 6 м от камеры, но пятое примыкание запрещено, поэтому инженер ставит новую "
            "камеру на трубе.",
            "Новый дом у трубы, в 6 м от проекции транзитная камера с двумя отводами.",
            check,
            allow_roads=True,
            chambers=[c for c in chambers_with("moscow", 4) if transit_busy(c)],
        )
        cp = scene.points()[0].id
        ch = min(scene.of("heat_chamber"), key=lambda c: c.geom.distance(straight(scene, cp)[2]))
        expect_basic(scene)
        scene.must["not_tie_in"] = [ch.id]
        scene.should["tie_in"] = {cp: {"kind": "new_chamber"}}
        return scene

    yield "TIE-15", busy_transit


def scale_family(ctx: Ctx) -> Iterator[tuple[str, Callable[[], Scene]]]:
    def share(sid: str, gap: float) -> Callable[[], Scene]:
        def build() -> Scene:
            scene = synth(
                sid,
                "SCALE",
                f"Два дома в 60 м друг от друга, труба в {gap:g} м",
                400,
                gap + 120,
                ["ТП-2.1.дерево", "ТП-2.1.камеры", "ТП-6.S", "ТП-3.2"],
                "",
                f"Два дома по 10 т/ч в 60 м друг от друга вдоль трубы Ду 150 в {gap:g} м.",
                center=(0, gap / 2),
            )
            pipe(scene, "hn-1", 150, (-200, 0), (200, 0))
            a = house_at(scene, -40, gap, -20, gap + 16, (-30, gap + 1), 10.0)
            b = house_at(scene, 20, gap, 40, gap + 16, (30, gap + 1), 10.0)
            c80, c100 = PRICE[80], PRICE[100]
            stub = 1 + 5 + width_of(80) / 2
            separate = score(2 * ((gap + 1) * c80 + CHAMBER_RUB), 2 * (gap + 1))
            shared = score((gap - stub) * c100 + 2 * (stub + 30) * c80 + 2 * CHAMBER_RUB, gap + stub + 60)
            scene.engineer = (
                f"Две отдельные врезки: S ≈ {separate:.3f}. Общий участок Ду 100 и камера ветвления: "
                f"S ≈ {shared:.3f}. Инженер выбирает "
                + ("отдельные врезки." if separate < shared else "общий участок.")
            )
            scene.must.update({"connected": [a, b], "dn_by_flow_and_length": True})
            if separate < shared:
                scene.should["trees_min"] = 2
            else:
                scene.should["same_tree"] = [[a, b]]
            scene.should["score_max"] = round(min(separate, shared) + 0.02, 4)
            return scene

        return build

    yield "SCALE-17A", share("SCALE-17A", 20.0)
    yield "SCALE-17B", share("SCALE-17B", 150.0)

    def khodynka() -> Scene:
        for lat, lon in ((55.7905, 37.5300), (55.7880, 37.5220), (55.7700, 37.4720), (55.7780, 37.4600)):
            scene = quarter_at(lat, lon)
            if scene is not None:
                return scene
        raise Skip("SCALE-18")

    def quarter_at(lat: float, lon: float) -> Scene | None:
        sid = "SCALE-18"
        from heatscenes.catalog import new_scene

        x, y = utm_point(lat, lon)
        scene = new_scene(
            sid,
            "SCALE",
            "Большой новый квартал у одной магистрали",
            "moscow",
            (x, y),
            400.0,
            ["ТП-2.1.дерево", "ТП-2.3.длина", "ТП-2.3.неубывание", "Р2", "ТП-6.варианты", "ПР-4"],
            "Квартал новых домов с расходами 4–76 т/ч, сеть — одна магистраль у края. Инженер строит "
            "одно-два дерева с магистралью по проездам, Ду по расходу и длине путей, врезок не больше "
            "двух.",
            "На свободной земле поставлены новые дома, оставлена одна магистраль.",
        )
        mains = sorted(scene.of("heat_network"), key=lambda p: (-p.props["diameter"], -p.geom.length, str(p.id)))
        if not mains:
            return None
        main = mains[0]
        scene.keep_network({main.id}, "оставлена одна магистраль")
        blocked = obstacles(scene)
        minx, miny, maxx, maxy = scene.area.bounds
        step = 26.0
        k = 0
        for gy in range(int((maxy - miny) // step)):
            for gx in range(int((maxx - minx) // step)):
                if len(scene.houses()) >= 30:
                    break
                cx, cy = minx + 40 + gx * step, miny + 40 + gy * step
                poly = rect(cx, cy, 44, 16, 0.0)
                if not scene.area.buffer(-5).contains(poly) or poly.intersects(blocked):
                    continue
                if poly.distance(main.geom) < 25:
                    continue
                k += 1
                scene.add_house(poly, 14, house_id=f"oks-khod-{k}")
                blocked = blocked.union(poly.buffer(10))
        if len(scene.houses()) < 20:
            return None
        scene.edits.append(f"на свободной земле поставлено {len(scene.houses())} новых домов")
        expect_tree(scene)
        scene.should["tie_ins_max"] = 2
        return scene

    yield "SCALE-18", khodynka


def bld_family(ctx: Ctx) -> Iterator[tuple[str, Callable[[], Scene]]]:
    presnya = utm_point(55.7640, 37.5770)

    def yard_with_pipe() -> Scene:
        sid = "BLD-13"
        from heatscenes.catalog import new_scene

        centers = [Point(presnya[0] + dx, presnya[1] + dy) for dx in (-600, 0, 600) for dy in (-400, 0, 400)]
        for center in centers:
            yards = courtyards("moscow", center, 700.0)
            for hole in yards["closed"] + yards["narrow"]:
                if hole.area < 1200:
                    continue
                c = hole.representative_point()
                scene = new_scene(
                    sid,
                    "BLD",
                    "Сеть внутри замкнутого двора",
                    "moscow",
                    (c.x, c.y),
                    180.0,
                    ["ТП-2.2", "ТП-2.4.новая", "ТП-4.oks", "Р11", "Р29-12"],
                    "Двор закрыт корпусами, существующая труба заходит во двор под корпусом. Инженер подключает "
                    "дом внутри двора и ставит камеру на трубе не ближе 5 м к корпусам; наружу не выходит.",
                    "Периметральный квартал старого центра, новый дом во дворе, во двор под корпусом заходит труба.",
                )
                pipes = scene.of("heat_network")
                if not pipes:
                    continue
                walls = shapely.union_all([i.geom.buffer(4.0) for i in scene.items if i.kind in BUILDINGS])
                house = free_spot(
                    scene, (c.x, c.y), 18, 10, orientation(hole), radius=20, allow=hole.buffer(-3), blocked=walls
                )
                if house is None:
                    continue
                outside = min(pipes, key=lambda p: (p.geom.distance(hole), str(p.id)))
                start = nearest_points(outside.geom, hole)[0]
                edge = nearest_points(hole.exterior, start)[0]
                d = unit(edge.x - start.x, edge.y - start.y)
                end = Point(edge.x + d[0] * 14, edge.y + d[1] * 14)
                if not hole.contains(end) or house.distance(LineString([start, end])) < 6:
                    continue
                pid = scene.add("heat_network", LineString([start, end]), "hn-yard", diameter=100)
                scene.edits.append(f"добавлен участок сети {pid} Ду 100 от трубы {outside.id} во двор под корпусом")
                cp = scene.add_house(house, 7)
                length = reference_length(scene, cp)
                if length is None or length > 60:
                    continue
                expect_basic(scene)
                scene.should["tie_clear_of_buildings_m"] = 5.0
                return scene
        raise Skip(sid)

    yield "BLD-13", yard_with_pipe

    def lane() -> Scene:
        sid = "BLD-14"
        from heatscenes.catalog import new_scene

        w = world("moscow")
        x, y = presnya
        roads = [
            r
            for r in w.near(Point(x, y).buffer(1500), "road")
            if r.props.get("highway") in ("residential", "service", "living_street", "unclassified", "tertiary")
            and 2 * r.geom.area / max(r.geom.length, 1) <= 9
        ]
        for r in ctx.shuffled(sid, roads):
            inner = [p for p in w.near(r.geom, "heat_network") if p.geom.intersection(r.geom).length > 30]
            if not inner:
                continue
            p = inner[0]
            sides = [b for b in w.near(r.geom, "oks_existing", distance=9) if b.geom.area > 300]
            if len(sides) < 2:
                continue
            target = min(sides, key=lambda b: (b.geom.distance(p.geom), str(b.id)))
            others = [b for b in sides if b.id != target.id and 9 <= b.geom.distance(target.geom) <= 17]
            if not others:
                continue
            c = target.geom.centroid
            scene = new_scene(
                sid,
                "BLD",
                "Переулок старого центра: труба под осью",
                "moscow",
                (c.x, c.y),
                150.0,
                ["ТП-2.2", "Р3", "Р29-13", "ТП-4.oks", "ТП-4.road", "ТП-2.4.новая"],
                "Переулок 12–16 м между фасадами, труба под проезжей частью. Инженер ведёт один "
                "прямой финальный участок поперёк переулка до новой камеры в проекции, без поворотов: "
                "любой продольный кусок нарушает отступ 5 м до противоположного фасада. Камера в "
                "полигоне дороги допустима (разъяснение 29.09, п. 13).",
                "Здание в переулке заменено новым домом той же формы, труба под осью переулка.",
            )
            scene.drop({i.id for i in scene.of("oks_existing") if i.geom.intersection(target.geom).area > 1.0})
            inner_ring = target.geom.buffer(-1.0)
            if inner_ring.is_empty or inner_ring.geom_type != "Polygon":
                continue
            cp_pt = nearest_points(inner_ring.exterior, p.geom)[0]
            cp = scene.add_house(target.geom, 6, cp=(cp_pt.x, cp_pt.y), house_id=f"oks-lane-{target.id}")
            scene.edits.append(f"здание {target.id} заменено новым домом той же формы")
            if reference_length(scene, cp) is None:
                continue
            expect_basic(scene)
            scene.should["max_turns"] = {cp: 0}
            return scene
        raise Skip(sid)

    yield "BLD-14", lane

    def ring_building() -> Scene:
        sid = "BLD-15"
        from heatscenes.catalog import new_scene

        pool = [
            b
            for b in world("moscow").of("oks_existing")
            if b.geom.geom_type == "Polygon"
            and b.geom.interiors
            and 2500 < b.geom.area < 30000
            and not in_forbid("moscow", b.geom)
        ]
        for b in ctx.shuffled(sid, pool):
            hole = max((Polygon(r) for r in b.geom.interiors), key=lambda h: h.area)
            if hole.area < 300:
                continue
            ring = hole.buffer(1.0).exterior
            cands = [ring.interpolate(t, normalized=True) for t in [i / 40 for i in range(40)]]
            cp_pt = max(cands, key=lambda q: (b.geom.exterior.distance(q), q.x))
            if b.geom.exterior.distance(cp_pt) < 12:
                continue
            c = b.geom.centroid
            scene = new_scene(
                sid,
                "BLD",
                "Ввод у стены внутреннего двора здания",
                "moscow",
                (c.x, c.y),
                200.0,
                ["ТП-2.2", "Р3", "Р29-15", "ТП-4.oks"],
                "Полигон здания с дыркой-двором, точка подключения в 1 м от стены двора и в "
                f"{b.geom.exterior.distance(cp_pt):.0f} м от наружной. По букве п. 2.2 ближайшая граница "
                "— стена двора, но двор замкнут. Принято, как у команды и по разъяснению 29.09 п. 15: "
                "финальный участок идёт от внешней границы. Трактовку стоит подтвердить у "
                "организаторов.",
                "Здание с внутренним двором заменено новым домом той же формы.",
            )
            scene.drop({i.id for i in scene.of("oks_existing") if i.geom.intersection(b.geom).area > 1.0})
            cp = scene.add_house(b.geom, 7, cp=(cp_pt.x, cp_pt.y), house_id=f"oks-ring-{b.id}")
            scene.edits.append(f"здание {b.id} заменено новым домом той же формы")
            if not scene.of("heat_network") or reference_length(scene, cp) is None:
                continue
            expect_basic(scene)
            scene.should["final_from_outer_contour"] = True
            return scene
        raise Skip(sid)

    yield "BLD-15", ring_building

    def facade_gas() -> Scene:
        sid = "BLD-16"
        for c in ctx.shuffled(sid, candidates("moscow")):
            if c.origin != "future" or c.crosses["all"] or not 20 < c.dist["all"] < 60 or in_forbid("moscow", c.house):
                continue
            scene = from_candidate(
                ctx,
                sid,
                "BLD",
                "Газопровод вдоль фасада",
                c,
                "all",
                ["ТП-2.2", "ТП-4.gas_pipeline", "ТП-4.спецпроход", "Р3", "Р29-14"],
                "Вдоль фасада, к которому ближе точка подключения, в 2,5 м идёт газопровод. "
                "Финальный участок обязан его пересечь: спецпроход с Kспец 1,25 и узлами в 2 м от "
                "пересечения, без поворотов внутри. Отступ к своему зданию на финальном участке "
                "не действует, к газу — действует.",
                "Новый дом, вдоль ближней к сети стены в 2,5 м проложен газопровод.",
            )
            cp = scene.points()[0].id
            house = house_of(scene, cp).geom
            wall_pt = nearest_points(house.exterior, scene.get(cp).geom)[0]
            ring = list(house.exterior.coords)
            edges = [LineString([ring[i], ring[i + 1]]) for i in range(len(ring) - 1)]
            edge = min(edges, key=lambda e: e.distance(wall_pt))
            d = unit(edge.coords[1][0] - edge.coords[0][0], edge.coords[1][1] - edge.coords[0][1])
            n = (-d[1], d[0])
            if house.contains(Point(wall_pt.x + n[0] * 1, wall_pt.y + n[1] * 1)):
                n = (-n[0], -n[1])
            o = Point(wall_pt.x + n[0] * 2.5, wall_pt.y + n[1] * 2.5)
            gid = scene.add(
                "gas_pipeline",
                LineString([(o.x - d[0] * 2000, o.y - d[1] * 2000), (o.x + d[0] * 2000, o.y + d[1] * 2000)]),
                "gas-facade",
            )
            drop_network_except(
                scene,
                halfplane((o.x, o.y), d, side_of(o, d, scene.get(cp).geom)).intersection(scene.area.buffer(60)),
                set(),
                "сеть на стороне дома от газопровода убрана",
            )
            scene.edits.append(f"газопровод {gid} проложен вдоль фасада в 2,5 м")
            if not scene.of("heat_network") or reference_length(scene, cp) is None:
                continue
            expect_basic(scene)
            scene.must.update(
                {
                    "crossings_min": {"gas_pipeline": 1},
                    "special_kspec": {"gas_pipeline": 1.25},
                    "no_turn_in_special": True,
                }
            )
            return scene
        raise Skip(sid)

    yield "BLD-16", facade_gas


def area_family(ctx: Ctx) -> Iterator[tuple[str, Callable[[], Scene]]]:
    def river() -> Scene:
        sid = "AREA-18"
        from heatscenes.catalog import new_scene

        rivers = [i for i in world("moscow").of("water") if i.geom.area > 200000]
        spots = []
        for r in rivers:
            edge = r.geom.exterior
            spots += [(r, edge.interpolate(t, normalized=True)) for t in [k / 60 for k in range(60)]]
        for r, e in ctx.shuffled(sid, spots)[:120]:
            inner = r.geom.buffer(-1)
            p1 = edge_normal(r.geom, e)
            if p1 is None:
                continue
            nx, ny = p1
            center = (e.x - nx * 150, e.y - ny * 150)
            scene = new_scene(
                sid,
                "AREA",
                "Москва-река на всю ширину данных",
                "moscow",
                center,
                350.0,
                ["ТП-4.water", "ТП-2.5", "ТП-2.1.граница", "ТП-6.штраф"],
                "Сеть только на другом берегу, река продлена прямыми полосами на 7 км за окно. Пересекать "
                "водный объект нельзя, а обход длиннее 14 км и больше предельной длины Ду 1400 (11 276 м). "
                "Дом остаётся без сети со штрафом.",
                "Новый дом на берегу Москвы-реки, сеть на другом берегу.",
            )
            house = free_spot(scene, (e.x + nx * 35, e.y + ny * 35), 36, 14, 0.0, radius=30)
            if house is None or inner.contains(house.centroid):
                continue
            water = shapely.union_all([i.geom for i in scene.of("water")])
            land = scene.area.buffer(60).difference(water.buffer(0.5))
            parts = list(getattr(land, "geoms", [land]))
            own = next((p for p in parts if p.intersects(house)), None)
            if own is None:
                continue
            drop_network_except(scene, own, set(), "сеть на берегу нового дома убрана")
            if not scene.of("heat_network"):
                continue
            cp = scene.add_house(house, 12)
            if reference_length(scene, cp) is not None:
                continue
            extend_barrier(scene, "water")
            scene.must.update(
                {"unconnected": {cp: ["no_route", "enclosed"]}, "no_cross": [i.id for i in scene.of("water")]}
            )
            return scene
        raise Skip(sid)

    yield "AREA-18", river

    def pond() -> Scene:
        sid = "AREA-19"
        pool = []
        for i in world("moscow").of("water"):
            minx, miny, maxx, maxy = i.geom.bounds
            if 2000 < i.geom.area < 60000 and max(maxx - minx, maxy - miny) < 260:
                if not world("moscow").near(i.geom.buffer(5), "park"):
                    pool.append(i)
        scene, ob, cp = across(
            ctx,
            sid,
            "AREA",
            "Пруд целиком в пределах данных",
            "moscow",
            pool,
            ["ТП-4.water", "ТП-2.5"],
            "Пруд лежит целиком в окне, сеть за ним. Инженер обходит пруд с отступом 1 м по короткой стороне.",
            "Новый дом у пруда, сеть за прудом.",
            half=300.0,
        )
        if not scene.area.contains(ob.geom):
            raise Skip(sid)
        scene.must.update({"no_cross": [ob.id], "dn_by_flow_and_length": True})
        require_connected(scene, [cp])
        scene.should["max_route_length_m"] = {cp: route_bound(scene, cp)}
        return scene

    yield "AREA-19", pond


def var_family(ctx: Ctx) -> Iterator[tuple[str, Callable[[], Scene]]]:
    def three() -> Scene:
        scene = synth(
            "VAR-02",
            "VAR",
            "Три близких по цене решения",
            300,
            200,
            ["ТП-6.варианты", "ТП-6.S", "Р29-8", "Р29-9", "Р29-1"],
            "",
            "Квадратный дом 16×16 м, ввод в центре, в 8 м от каждой стены. Труба на юге в 20 м от стены, на "
            "севере в 22 м, на востоке в 18 м со свободной камерой напротив ввода.",
            center=(10, 10),
        )
        cp = house_at(scene, -8, 0, 8, 16, (0, 8), 10.0)
        pipe(scene, "hn-south", 100, (-150, -20), (150, -20))
        pipe(scene, "hn-north", 100, (-150, 38), (150, 38))
        pipe(scene, "hn-east", 100, (26, -15), (26, 33))
        scene.add("heat_chamber", Point(*xy(26, 8)), "hc-east")
        c80 = PRICE[80]
        variants = {
            "юг": score(28 * c80 + CHAMBER_RUB, 28),
            "север": score(30 * c80 + CHAMBER_RUB, 30),
            "восток": score(26 * c80 + TIE_RUB, 26),
        }
        scene.engineer = (
            "Три разумных решения (длина считается от ввода, включая 8 м внутри дома): "
            + ", ".join(f"{k} S ≈ {v:.3f}" for k, v in variants.items())
            + ". Разница меньше 0,05. Инженер показал бы все три как варианты с разными местами "
            "врезки и упорядочил по S. Восточная труба — только через свободную камеру: проекция "
            "точки попадает в неё."
        )
        scene.must.update({"connected": [cp], "variants_ranked": True, "dn_by_flow_and_length": True})
        scene.should.update(
            {"variants_min": 3, "variants_distinct_tie_in": True, "score_max": round(min(variants.values()) + 0.02, 4)}
        )
        return scene

    yield "VAR-02", three

    def single_option() -> Scene:
        scene = synth(
            "VAR-03",
            "VAR",
            "Разумный вариант один",
            400,
            200,
            ["ТП-6.варианты", "Р29-8"],
            "Дом в 5 м от единственной трубы, других мест врезки в 200 м нет. Второй и третий варианты "
            "не нужны; если они есть, их место врезки должно отличаться больше чем на 10 м, иначе это "
            "смещение той же трассы.",
            "Дом в 5 м от единственной трубы.",
        )
        cp = house_at(scene, -24, 0, 24, 16, (0, 1), 10.0)
        pipe(scene, "hn-1", 150, (-200, -5), (200, -5))
        scene.must.update({"connected": [cp], "dn_by_flow_and_length": True})
        scene.should.update({"variants_tie_in_spread_min_m": 10, "max_route_length_m": {cp: 15}})
        return scene

    yield "VAR-03", single_option


def grid_family(ctx: Ctx) -> Iterator[tuple[str, Callable[[], Scene]]]:
    def along_street(sid: str, title: str, pool_fn: Callable[[], list[Item]], eng: str) -> Callable[[], Scene]:
        def build() -> Scene:
            from heatscenes.catalog import new_scene

            for r in ctx.shuffled(sid, pool_fn())[:200]:
                origin, u = long_axis(r.geom)
                rr = r.geom.minimum_rotated_rectangle
                xyr = list(rr.exterior.coords)
                length = max(math.dist(xyr[0], xyr[1]), math.dist(xyr[1], xyr[2]))
                end = Point(origin[0] - u[0] * length * 0.45, origin[1] - u[1] * length * 0.45)
                scene = new_scene(
                    sid,
                    "GRID",
                    title,
                    "moscow",
                    origin,
                    max(220.0, length / 2 + 80),
                    ["ТП-2.1.повороты", "Р5", "Р29-18", "Р29-19", "ТП-4.road"],
                    eng,
                    "Новый дом у одного конца улицы, сеть ближе 130 м убрана.",
                )
                house = None
                for side in (1, -1):
                    n = (-u[1] * side, u[0] * side)
                    edge = r.geom.boundary.distance(end) if r.geom.contains(end) else 0
                    target = (end.x + n[0] * (edge + 22), end.y + n[1] * (edge + 22))
                    house = free_spot(
                        scene,
                        target,
                        36,
                        14,
                        math.degrees(math.atan2(u[1], u[0])),
                        radius=25,
                        allow=halfplane((end.x, end.y), u, side),
                    )
                    if house is not None:
                        break
                if house is None:
                    continue
                scene.drop_network(house.buffer(130), "сеть ближе 130 м от дома убрана")
                if not scene.of("heat_network"):
                    continue
                cp = scene.add_house(house, 12)
                ref = reference_length(scene, cp)
                if ref is None or not 120 <= ref <= 300:
                    continue
                expect_basic(scene)
                scene.should.update({"along_axes_share_min": 0.8, "max_turn_deg": 90})
                return scene
            raise Skip(sid)

        return build

    def diagonal() -> list[Item]:
        roads = nearby("moscow", 55.7765, 37.5450, 1200, ("road",))
        result = []
        for r in roads:
            a = orientation(r.geom) % 90
            if (
                r.props.get("highway") in ("primary", "secondary", "tertiary", "trunk")
                and 30 < a < 60
                and r.geom.area > 2500
            ):
                result.append(r)
        return result

    def curved() -> list[Item]:
        result = []
        for r in world("moscow").of("road"):
            if r.props.get("highway") not in ("residential", "tertiary", "secondary", "unclassified"):
                continue
            if r.geom.area < 2500:
                continue
            if r.geom.area / r.geom.minimum_rotated_rectangle.area < 0.35:
                result.append(r)
        return result

    eng = (
        "Сеть есть дальше по улице. Инженер ведёт трассу вдоль улицы по её оси застройки, повороты не круче 90°, "
        "без ступенек; переходы, если нужны, под прямым углом."
    )
    yield "GRID-05", along_street("GRID-05", "Вдоль диагональной улицы", diagonal, eng)
    yield (
        "GRID-06",
        along_street(
            "GRID-06",
            "Вдоль кривой улицы",
            curved,
            eng + " На изгибе — несколько прямых с плавными поворотами, не срезая изгиб через квартал.",
        ),
    )


def input_family(ctx: Ctx) -> Iterator[tuple[str, Callable[[], Scene]]]:
    def organizers_like() -> Scene:
        sid = "IN-17"
        w = world("moscow")
        for c in ctx.shuffled(sid, candidates("moscow")):
            if c.origin != "future" or not 10 < c.dist["all"] < 40 or c.crosses["all"] or in_forbid("moscow", c.house):
                continue
            parks = [p for p in w.near(c.house.buffer(150), "park") if p.geom.area > 3000]
            if not parks:
                continue
            scene = from_candidate(
                ctx,
                sid,
                "INPUT",
                "Вход как у организаторов",
                c,
                "all",
                ["ТП-1", "ТП-1.id", "ТП-1.1", "ТП-1.2", "ТП-7", "ТП-2.5", "ТП-4.park"],
                "Вход в форме датасета организаторов: целые id, все здания MultiPolygon, есть "
                "source в 50 м от трубы. Одна точка в здании внутри парка не подключается. "
                "Инженер ждёт, что сервис не падает, ссылки остаются числами, id неподключённой "
                "точки в unconnected_oks_ids — число, source не место врезки и не препятствие.",
                "Окно 300 м, новый дом у сети и павильон в парке.",
                half=150.0,
            )
            park = next((p for p in scene.of("park") if p.geom.area > 3000), None)
            if park is None:
                continue
            spot = free_spot(
                scene,
                (park.geom.representative_point().x, park.geom.representative_point().y),
                20,
                12,
                0.0,
                radius=60,
                allow=park.geom.buffer(-6),
                blocked=obstacles(scene, skip=frozenset({park.id})),
            )
            if spot is None:
                continue
            first = scene.points()[0].id
            second = scene.add_house(spot, 2, house_id="oks-in17-park")
            _, pipe_item, tie = straight(scene, first)
            seg = pipe_item.geom
            s = seg.project(tie)
            p1, p2 = seg.interpolate(max(0, s - 1)), seg.interpolate(min(seg.length, s + 1))
            d = unit(p2.x - p1.x, p2.y - p1.y)
            src = None
            for sign in (1, -1):
                q = Point(tie.x - d[1] * 50 * sign, tie.y + d[0] * 50 * sign)
                if not any(i.geom.contains(q) for i in scene.items if i.kind in BUILDINGS + FORBID and i.geom):
                    src = q
                    break
            if src is None:
                continue
            scene.items.append(Item({"id": "src-in17", "object_type": "source"}, src))
            scene.numeric_ids = True
            scene.multipolygon = True
            scene.edits.append("id заменены целыми, здания записаны MultiPolygon, добавлен source, павильон в парке")
            scene.must.update(
                {
                    "no_crash": True,
                    "ids_numeric_in_output": True,
                    "unconnected": {second: ["inside_forbidden_zone", "enclosed", "no_route"]},
                    "source_not_used": ["src-in17"],
                }
            )
            require_connected(scene, [first])
            return scene
        raise Skip(sid)

    yield "IN-17", organizers_like

    def lines() -> Scene:
        scene = synth(
            "IN-18",
            "INPUT",
            "Ограничения линиями",
            300,
            300,
            [
                "ТП-1.1",
                "ТП-4.road",
                "ТП-4.tram_tracks",
                "ТП-4.railway",
                "ТП-4.water",
                "ТП-4.gas_pipeline",
                "ТП-4.угол",
                "Р6",
                "ПР-5",
            ],
            "Дорога и трамвай заданы осевыми LineString, ж/д и ручей — LineString, газопровод — "
            "MultiLineString из двух кусков. Для дороги-линии принята трактовка «нулевой полигон плюс 3 м»: "
            "зона спецпрохода — 3 м в обе стороны от линии, угол — к самой линии. Ж/д и ручей кончаются в "
            "окне, инженер их обходит; дорогу и трамвай пересекает под углом не меньше 45°.",
            "Синтетика 300×300 м: дом на западе, труба на востоке, между ними линии.",
        )
        cp = house_at(scene, -140, -8, -110, 8, (-111, 0), 10.0)
        pipe(scene, "hn-1", 150, (130, -140), (130, 140))
        scene.add("road", line((-70, -160), (-70, 160)), "road-axis")
        scene.add("tram_tracks", line((-40, -160), (-35, 160)), "tram-axis")
        scene.add("railway", line((0, -60), (0, 60)), "rail-line")
        scene.add("water", line((40, -50), (45, 70)), "stream-line")
        scene.add(
            "gas_pipeline", shapely.MultiLineString([[xy(80, -160), xy(80, -5)], [xy(82, 5), xy(82, 160)]]), "gas-multi"
        )
        scene.must.update(
            {
                "no_crash": True,
                "connected": [cp],
                "crossing_angle_min_deg": {"road": 45, "tram_tracks": 45},
                "no_cross": ["rail-line", "stream-line"],
                "dn_by_flow_and_length": True,
            }
        )
        scene.should.update({"road_line_zone_m": 3.0, "max_route_length_m": {cp: route_bound(scene, cp)}})
        return scene

    yield "IN-18", lines


def real_family(ctx: Ctx) -> Iterator[tuple[str, Callable[[], Scene]]]:
    def real(sid: str, name: str, title: str, note: str) -> Callable[[], Scene]:
        def build() -> Scene:
            data = json.loads((ROOT / "data" / "real" / name).read_text(encoding="utf-8"))
            scene = Scene(
                sid,
                "REAL",
                title,
                f"Файл data/real/{name} целиком: 17 точек 4–76 т/ч, магистрали Ду 300–500, здания "
                "MultiPolygon, source, целые id, CRS84. " + note,
                ["ТП-1", "ТП-1.id", "ТП-1.1", "ТП-1.2", "ТП-7", "ТП-6.S", "Р17"],
                {},
                {},
                "Инженер подключает все 17 точек: допустимые маршруты есть. Порог S — регрессионный, а не "
                "следствие правил: лучшее известное решение плюс 2 %. Лучшее известное решение — "
                f"версия сервиса v0.12.0 на скорректированной редакции: S {REAL_BEST_S}.",
                None,
                box(0, 0, 1, 1),
                [],
            )
            scene.raw_input = data
            scene.origin = "organizers"
            scene.reference = {"best_known_score": REAL_BEST_S, "source": "v0.12.0, data/samples/small-1.geojson"}
            cps = [
                f["properties"]["id"]
                for f in data["features"]
                if f["properties"]["object_type"] == "oks_connection_point"
            ]
            scene.must.update({"connected": cps, "ids_numeric_in_output": True, "dn_by_flow_and_length": True})
            scene.should["score_regression_max"] = round(REAL_BEST_S * 1.02, 4)
            return scene

        return build

    yield (
        "REAL-01",
        real(
            "REAL-01",
            "dataset.geojson",
            "Датасет организаторов",
            "Ду существующих труб в этой редакции другие, поэтому эталон S приблизительный.",
        ),
    )
    yield (
        "REAL-02",
        real(
            "REAL-02",
            "dataset-corrected.geojson",
            "Датасет организаторов, редакция 18.09",
            "Эталон S посчитан ровно на этой редакции.",
        ),
    )


DEPTH_SPECIFIC = {
    "05": {"must": {"crossing_side": {"power_cable": "below"}}},
    "08": {"must": {"crossing_side": {"power_cable": "below"}}},
    "01": {"should": {"kgl_one": True}},
    "17": {"should": {"kgl_one": True}},
    "19": {"should": {"crossings_max": {"heat_network": 0}}},
}


def depth_family(ctx: Ctx) -> Iterator[tuple[str, Callable[[], Scene]]]:
    folder = ROOT / "data" / "samples" / "depth"
    notes = {}
    for row in (folder / "README.md").read_text(encoding="utf-8").splitlines():
        if row.startswith("| `"):
            cells = [c.strip() for c in row.strip("|").split("|")]
            notes[cells[0].strip("`")] = cells[1]
    for path in sorted(folder.glob("*.geojson")):
        num = path.stem.split("_")[0]
        sid = f"DEPTH-{num}"

        def build(path: Any = path, num: str = num, sid: str = sid) -> Scene:
            data = json.loads(path.read_text(encoding="utf-8"))
            note = notes.get(path.stem, "")
            scene = Scene(
                sid,
                "DEPTH",
                note.split(":")[0][:80] or path.stem,
                f"Случай режима глубины из data/samples/depth/{path.name} (синтетика): {note}.",
                ["ТП-5", "Р16", "Р29-7", "ТП-4.gas_pipeline", "ТП-4.power_cable", "ТП-4.road", "ТП-4.tram_tracks"],
                {},
                {},
                "Расчёт с ключом --depth. Профиль: обычная глубина верха 3,0 м, не мельче 0,7 м, уклон не "
                "круче 0,10, узел на переходе через 3,0 м, Kгл = 1 + 0,1 (h − 3) глубже 3 м. Пересечения: "
                "под дорогой верх не мельче 1,0 м, под трамваем 1,2 м; газ выше или ниже с просветом 0,2 м "
                "(верх газа 2,8 м, габарит 0,4 м), кабель с просветом 0,5 м (верх 2,7 м, габарит 0,2 м), "
                f"существующая сеть с просветом 0,5 м (верх 3,0 м). Особенность случая: {note}.",
                None,
                box(0, 0, 1, 1),
                [],
            )
            scene.synthetic = True
            scene.raw_input = data
            scene.run_args = ["--depth"]
            cps = [
                f["properties"]["id"]
                for f in data["features"]
                if f["properties"]["object_type"] == "oks_connection_point"
            ]
            scene.must.update(
                {
                    "connected": cps,
                    "depth_profile_valid": True,
                    "depth_crossings_valid": True,
                    "dn_by_flow_and_length": True,
                }
            )
            extra = DEPTH_SPECIFIC.get(num, {})
            scene.must.update(extra.get("must", {}))
            scene.should.update(extra.get("should", {}))
            return scene

        yield sid, build


def ordered_family(ctx: Ctx) -> Iterator[tuple[str, Callable[[], Scene]]]:
    def five() -> Scene:
        scene = synth(
            "SCALE-19",
            "SCALE",
            "Пять домов вокруг одного места",
            300,
            220,
            ["ТП-2.1.камеры", "Р12", "ТП-2.1.дерево", "ТП-2.3.расход"],
            "Пять домов по кругу радиусом 25 м, труба в 70 м. Соблазн — одна камера в центре с пятью "
            "ветками, но к камере примыкает не больше четырёх участков. Инженер делает две камеры "
            "ветвления или ведёт часть домов отдельными ветками.",
            "Синтетика: пять домов 4–10 т/ч по кругу, труба Ду 200 в 70 м от центра.",
            center=(0, 50),
        )
        pipe(scene, "hn-1", 200, (-150, 0), (150, 0))
        cps = []
        for k, flow in enumerate((4.0, 6.0, 8.0, 10.0, 5.0)):
            a = math.radians(90 + 72 * k)
            cx, cy = 25 * math.cos(a), 70 + 25 * math.sin(a)
            poly = rect(*xy(cx + 7 * math.cos(a), cy + 7 * math.sin(a)), 12, 10, math.degrees(a) + 90)
            cps.append(scene.add_house(poly, 9, flow=flow, cp=xy(cx + 2.5 * math.cos(a), cy + 2.5 * math.sin(a))))
        scene.must.update({"connected": cps, "dn_by_flow_and_length": True})
        return scene

    yield "SCALE-19", five

    def cross_main() -> Scene:
        sid = "CROSS-28"
        w = world("moscow")
        for c in ctx.shuffled(sid, candidates("moscow")):
            if in_forbid("moscow", c.house):
                continue
            near = w.near(c.house, "heat_network", distance=60)
            bigs = [p for p in near if p.props["diameter"] >= 600]
            smalls = [p for p in near if p.props["diameter"] <= 100]
            if not bigs or not smalls:
                continue
            big = min(bigs, key=lambda p: p.geom.distance(c.house))
            for small in smalls:
                a, b = nearest_points(c.house, small.geom)
                line = LineString([a, b])
                if not line.crosses(big.geom) or line.length - c.house.distance(big.geom) > 25 or line.length > 55:
                    continue
                if w.near(line, "road", "tram_tracks", *FORBID) or [
                    o for o in w.near(line, *BUILDINGS) if not o.geom.intersects(c.house)
                ]:
                    continue
                scene = from_candidate(
                    ctx,
                    sid,
                    "CROSS",
                    "Перейти магистраль Ду 600+ к малой трубе",
                    c,
                    "all",
                    ["ТП-4.heat_network", "Р10", "ТП-3.2", "ТП-2.4.новая", "Р29-14"],
                    f"Ближе всего магистраль Ду {big.props['diameter']}, за ней в {line.length:.0f} м "
                    f"труба Ду {small.props['diameter']}. Новая камера на магистрали стоит 8 млн (по "
                    "трактовке команды), на малой трубе 3 млн. Инженер переходит магистраль "
                    "спецпроходом с Kспец 1,05 и врезается в малую трубу. Если трасса всё же "
                    "пересекает магистраль, участок прямой, с узлами в 2 м от пересечения.",
                    "Новый дом, между ним и трубой малого Ду — магистраль.",
                )
                cp = scene.points()[0].id
                keep = {big.id, small.id}
                drop_network_except(
                    scene,
                    scene.get(cp).geom.buffer(line.length + 40),
                    keep,
                    "оставлены только магистраль и малая труба у дома",
                )
                if reference_length(scene, cp) is None:
                    continue
                expect_basic(scene)
                scene.must["special_kspec"] = {"heat_network": 1.05}
                scene.should.update(
                    {"tie_in": {cp: {"kind": "new_chamber", "pipes": [small.id]}}, "crossings_min": {"heat_network": 1}}
                )
                return scene
        raise Skip(sid)

    yield "CROSS-28", cross_main

    def depth_cross() -> Scene:
        scene = synth(
            "DEPTH-21",
            "DEPTH",
            "Пересечение существующей сети без врезки в глубине",
            400,
            200,
            ["ТП-5", "Р16", "ТП-4.heat_network", "Р10", "Р29-14"],
            "Геометрия CROSS-22: магистраль Ду 1000 в 15 м от дома, за ней труба Ду 80. Расчёт с ключом "
            "--depth. Если трасса переходит магистраль, то выше или ниже неё с просветом 0,5 м (верх "
            "магистрали на 3,0 м, высота габарита 1,2 м): верх новой пары не глубже 2,5 м минус её "
            "высота или не мельче 4,7 м. На смене глубины — технические узлы, уклон не круче 0,10.",
            "Синтетика: магистраль Ду 1000 и малая труба за ней.",
            center=(0, -20),
        )
        cp = house_at(scene, -24, 0, 24, 16, (0, 1), 10.0)
        pipe(scene, "hn-main", 1000, (-200, -15), (200, -15))
        pipe(scene, "hn-small", 80, (-200, -30), (200, -30))
        scene.run_args = ["--depth"]
        scene.must.update(
            {
                "connected": [cp],
                "depth_profile_valid": True,
                "depth_crossings_valid": True,
                "dn_by_flow_and_length": True,
            }
        )
        scene.should["crossings_min"] = {"heat_network": 1}
        return scene

    yield "DEPTH-21", depth_cross

    def road_chamber() -> Scene:
        scene = synth(
            "TIE-19",
            "TIE",
            "Свободная камера под проезжей частью",
            300,
            160,
            ["Р29-13", "ТП-2.4.камера10", "ТП-4.road", "ТП-4.спецпроход", "Р6"],
            "Свободная камера стоит на трубе под проезжей частью в 5 м от проекции ввода. По правилу 10 м "
            "используется она (разъяснение 29.09, п. 9). По п. 13 участок от камеры до выхода из дороги — "
            "один прямой спецпроход, он продолжается на 3 м за границей со стороны выхода, со стороны "
            "камеры дополнительные 3 м не нужны.",
            "Синтетика: улица 20 м, труба под её осью, камера в полигоне дороги, дом у края.",
        )
        scene.add("road", rbox(-150, -10, 150, 10), "road-1", highway="secondary")
        pipe(scene, "hn-1", 200, (-150, 0), (150, 0))
        ch = scene.add("heat_chamber", Point(*xy(0, 0)), "hc-road")
        cp = house_at(scene, -19, 20, 29, 36, (5, 21), 10.0)
        scene.must.update(
            {
                "connected": [cp],
                "tie_in": {cp: {"kind": "existing_chamber", "chambers": [ch]}},
                "chamber_special_exit_m": {ch: 3.0},
                "crossing_angle_min_deg": {"road": 45},
                "dn_by_flow_and_length": True,
            }
        )
        return scene

    yield "TIE-19", road_chamber

    def nearest_wall() -> Scene:
        scene = synth(
            "BLD-17",
            "BLD",
            "Ввод у середины длинной стены",
            300,
            200,
            ["ТП-2.2", "Р3", "Р29-15", "Р29-16"],
            "Здание 80 × 30 м, ввод в 1 м от середины длинной стены, а торец, ближайший к трубе, в 40 м от "
            "ввода. Финальный участок через полздания до торца сократил бы внешнюю трассу, но разъяснение "
            "29.09 (п. 15) это запрещает: сначала ближайшая допустимая точка внешнего контура. Инженер "
            "выходит через длинную стену и обходит здание снаружи с отступом.",
            "Синтетика: длинное здание, труба за его торцом.",
            center=(20, 15),
        )
        cp = scene.add_house(rbox(-40, 0, 40, 30), 9, flow=10.0, cp=xy(0, 1))
        pipe(scene, "hn-1", 150, (60, -60), (60, 90))
        scene.must.update({"connected": [cp], "final_from_nearest_boundary_m": 1.0, "dn_by_flow_and_length": True})
        return scene

    yield "BLD-17", nearest_wall

    def u_turn() -> Scene:
        scene = synth(
            "BLD-18",
            "BLD",
            "Ввод с дальней от сети стороны, разворот",
            300,
            200,
            ["ТП-2.1.повороты", "Р5", "Р29-18", "Р29-19", "ТП-2.2", "Р29-15"],
            "Ввод у северной стены, труба на юге за забором, который охватывает дом с трёх сторон. Выход "
            "только на север, дальше трасса должна развернуться к югу. Поворот в одной вершине не круче "
            "90°; заменять недопустимый разворот двумя поворотами с коротким звеном нельзя "
            "(разъяснение 29.09, п. 18). Инженер выходит на север, обходит забор двумя поворотами по 90° "
            "и идёт к трубе.",
            "Синтетика: дом в П-образном заборе (запретная территория), открытом на север, труба в 30 м к югу.",
            center=(0, 0),
        )
        for k, b in enumerate([rbox(-31, -6, -30, 40), rbox(30, -6, 31, 40), rbox(-31, -6, 31, -5)], 1):
            scene.add("prohibited_site", b, f"fence-{k}")
        cp = house_at(scene, -20, 0, 20, 16, (0, 15), 10.0)
        pipe(scene, "hn-1", 150, (-150, -30), (150, -30))
        scene.must.update({"connected": [cp], "no_short_reversal_m": 5.0, "dn_by_flow_and_length": True})
        return scene

    yield "BLD-18", u_turn

    def two_pipes() -> Scene:
        scene = synth(
            "SCALE-20",
            "SCALE",
            "Дерево между двумя трубами",
            300,
            220,
            ["ТП-2.1.дерево", "ТП-2.4.новая", "ТП-6.S"],
            "Четыре дома в ряд между двумя трубами, ближе к южной, вводы смотрят на северную. Дерево, "
            "собирающее дома, проходит рядом с обеими трубами, и есть соблазн присоединить его к обеим. "
            "Это кольцо через существующую сеть: у связной новой сети должно быть одно место "
            "присоединения. Инженер делает одно или несколько отдельных деревьев, каждое со своей "
            "врезкой.",
            "Синтетика: две трубы Ду 200 в 120 м друг от друга, четыре дома по 10 т/ч.",
            center=(0, 60),
        )
        pipe(scene, "hn-south", 200, (-150, 0), (150, 0))
        pipe(scene, "hn-north", 200, (-150, 120), (150, 120))
        cps = [house_at(scene, x - 10, 30, x + 10, 46, (x, 45), 10.0) for x in (-60, -20, 20, 60)]
        scene.must.update({"connected": cps, "dn_by_flow_and_length": True})
        return scene

    yield "SCALE-20", two_pipes

    def driveway() -> Scene:
        scene = synth(
            "BLD-19",
            "BLD",
            "Двор, выезд только по проезду",
            300,
            200,
            ["ТП-4.road", "ТП-4.спецпроход", "Р6", "ТП-4.oks", "ТП-2.5"],
            "Двор замкнут корпусами, единственная щель 17,4 м занята проездом 5,5 м. Обычный участок в "
            "щели не помещается: нужно 5,3 м от корпусов и 1,8 м от проезда. По принятой трактовке "
            "продольный спецпроход по полигону проезда допустим, если он входит и выходит через границу "
            "полигона под углом не меньше 45° — здесь через торец проезда во дворе и через дальний край "
            "улицы. Это одна прямая, Kспец 1,60.",
            "Синтетика: двор из корпусов, проезд через щель к улице, труба за улицей.",
            center=(0, -5),
        )
        blocks = [
            rbox(-60, 40, 60, 52),
            rbox(-60, -12, -48, 52),
            rbox(48, -12, 60, 52),
            rbox(-60, -12, -8.7, 0),
            rbox(8.7, -12, 60, 0),
        ]
        for k, b in enumerate(blocks, 1):
            scene.add("oks_existing", b, f"block-{k}")
        scene.add("road", rbox(-2.75, -20, 2.75, 8), "road-driveway", highway="service")
        scene.add("road", rbox(-200, -40, 200, -20), "road-street", highway="residential")
        cp = house_at(scene, -20, 18, 20, 30, (0, 19), 10.0)
        pipe(scene, "hn-1", 150, (-150, -50), (150, -50))
        scene.must.update(
            {
                "connected": [cp],
                "special_kspec": {"road": 1.6},
                "crossing_angle_min_deg": {"road": 45},
                "dn_by_flow_and_length": True,
            }
        )
        scene.should["max_route_length_m"] = {cp: route_bound(scene, cp)}
        return scene

    yield "BLD-19", driveway


def families(ctx: Ctx) -> Iterator[tuple[str, Callable[[], Scene]]]:
    yield from tie_family(ctx)
    yield from scale_family(ctx)
    yield from cross_family(ctx)
    yield from area_family(ctx)
    yield from bld_family(ctx)
    yield from len_family(ctx)
    yield from flow_family(ctx)
    yield from ordered_family(ctx)
    yield from grid_family(ctx)
    yield from input_family(ctx)
    yield from var_family(ctx)
    yield from real_family(ctx)
    yield from depth_family(ctx)
