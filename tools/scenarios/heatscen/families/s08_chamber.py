from heatcheck.model import (
    chamber_cost,
    diameter_for,
)
from heatscen import (
    Expect,
    Scene,
    rules,
    scenario,
)
from heatscen.families.s07_reconstruction import (
    armor,
    chain,
    chamber_gate_half,
    chamber_recon,
    chamber_tie,
    gate_half,
    pipe_tie,
    recon_part,
)


def scale_scene(dn: int, flow: float) -> tuple[Scene, Expect]:
    """Врезка в камеру hc-1 Ду dn новым участком на расход flow: камера реконструируется по шкале TZ-52."""
    sc = Scene()
    existing_flow = 10
    ends = chain(sc, [(60, dn, existing_flow), (150, dn, existing_flow / 2)])
    sc.oks("oks-1", cp=(ends[0], 50), flow=flow, away=(0, 1))
    gate = [(ends[0], chamber_gate_half())]
    armor(sc, -20, 195, top=gate, bottom=gate)
    need = max(diameter_for(rules(), flow), diameter_for(rules(), existing_flow + flow))
    return sc, Expect(
        tie_ins=[chamber_tie("hc-1", dn)],
        new_chambers=0,
        chamber_recon=[chamber_recon("hc-1", dn, need)],
        recon=[recon_part("hn-1", dn, existing_flow, flow, 60.0)],
    )


@scenario("S08-01", tz=["TZ-6", "TZ-47", "TZ-52", "TZ-69"], title="камера DN 150 и новый участок DN 200: реконструкция камеры за 3 млн")
def chamber_recon_150_to_200() -> tuple[Scene, Expect]:
    return scale_scene(150, 70)


@scenario("S08-02", tz=["TZ-47", "TZ-52", "TZ-69"], title="граница шкалы камер снизу: требуемый DN 250 стоит 5 млн")
def chamber_scale_250() -> tuple[Scene, Expect]:
    return scale_scene(150, 160)


@scenario("S08-03", tz=["TZ-47", "TZ-52", "TZ-69"], title="граница шкалы камер: требуемый DN 500 ещё 5 млн")
def chamber_scale_500() -> tuple[Scene, Expect]:
    return scale_scene(400, 1000)


@scenario("S08-04", tz=["TZ-47", "TZ-52", "TZ-69"], title="граница шкалы камер: требуемый DN 600 уже 8 млн")
def chamber_scale_600() -> tuple[Scene, Expect]:
    return scale_scene(400, 1800)


@scenario("S08-05", tz=["TZ-47", "TZ-52", "TZ-69"], title="граница шкалы камер: требуемый DN 1000 ещё 8 млн")
def chamber_scale_1000() -> tuple[Scene, Expect]:
    return scale_scene(900, 9000)


@scenario("S08-06", tz=["TZ-47", "TZ-52", "TZ-69"], title="граница шкалы камер: требуемый DN 1200 уже 12 млн")
def chamber_scale_1200() -> tuple[Scene, Expect]:
    return scale_scene(900, 12000)


@scenario("S08-07", tz=["TZ-48", "TZ-69"], title="два ОКС по разные стороны подключены в одну камеру: её реконструкция считается один раз")
def chamber_recon_once_opposite() -> tuple[Scene, Expect]:
    sc = Scene()
    ends = chain(sc, [(60, 100, 10), (150, 80, 5)])
    sc.oks("oks-a", cp=(ends[0], 50), flow=8, away=(0, 1))
    sc.oks("oks-b", cp=(ends[0], -50), flow=8, away=(0, -1))
    gate = [(ends[0], chamber_gate_half())]
    armor(sc, -20, 195, top=gate, bottom=gate)
    need = diameter_for(rules(), 26)
    return sc, Expect(
        chamber_recon=[chamber_recon("hc-1", 100, need)],
        recon=[recon_part("hn-1", 100, 10, 16, 60.0)],
        summary={"chamber_reconstruction_cost": chamber_cost(rules(), need)},
    )


@scenario("S08-08", tz=["TZ-48", "TZ-69"], title="два ОКС с одной стороны подключены в одну камеру: её реконструкция считается один раз")
def chamber_recon_once_same_side() -> tuple[Scene, Expect]:
    sc = Scene()
    ends = chain(sc, [(80, 100, 12), (200, 80, 5)])
    sc.oks("oks-a", cp=(ends[0] - 25, 50), flow=7, away=(0, 1))
    sc.oks("oks-b", cp=(ends[0] + 25, 50), flow=6, away=(0, 1))
    gate = [(ends[0], chamber_gate_half())]
    armor(sc, -20, 265, top=gate, bottom=gate)
    need = diameter_for(rules(), 25)
    return sc, Expect(
        chamber_recon=[chamber_recon("hc-1", 100, need)],
        recon=[recon_part("hn-1", 100, 12, 13, 80.0)],
        summary={"chamber_reconstruction_cost": chamber_cost(rules(), need)},
    )


@scenario("S08-09", tz=["TZ-49"], title="врезка в трубу: камера на цепочке к источнику не реконструируется, хотя её участки реконструируются")
def chamber_on_chain_pipe_tie() -> tuple[Scene, Expect]:
    sc = Scene()
    chain(sc, [(80, 150, 60), (160, 150, 58), (150, 100, 20)])
    sc.oks("oks-1", cp=(160, 40), flow=10, away=(0, 1))
    armor(sc, -20, 225, top=[(160, gate_half(10))], bottom=[(160, gate_half(10))])
    return sc, Expect(
        tie_ins=[pipe_tie("hn-2", 150)],
        chamber_recon=[],
        recon=[recon_part("hn-2", 150, 58, 10, 80.0), recon_part("hn-1", 150, 60, 10, 80.0)],
        summary={"chamber_reconstruction_cost": 0},
    )


@scenario("S08-10", tz=["TZ-47", "TZ-49", "TZ-69"], title="врезка в дальнюю камеру: реконструируется только она, камера выше по цепочке — нет")
def chamber_on_chain_chamber_tie() -> tuple[Scene, Expect]:
    sc = Scene()
    ends = chain(sc, [(80, 150, 60), (80, 150, 58), (150, 100, 20)])
    sc.oks("oks-1", cp=(ends[1], 50), flow=10, away=(0, 1))
    gate = [(ends[1], chamber_gate_half())]
    armor(sc, -20, 295, top=gate, bottom=gate)
    return sc, Expect(
        tie_ins=[chamber_tie("hc-2", 150)],
        chamber_recon=[chamber_recon("hc-2", 150, diameter_for(rules(), 68))],
        recon=[recon_part("hn-2", 150, 58, 10, 80.0), recon_part("hn-1", 150, 60, 10, 80.0)],
    )


@scenario("S08-11", tz=["TZ-51", "TZ-68"], title="новая камера на трубе DN 300: diameter по трубе, стоимость 5 млн")
def new_chamber_by_existing_pipe() -> tuple[Scene, Expect]:
    sc = Scene()
    chain(sc, [(400, 300, 100), (200, 250, 50)])
    sc.oks("oks-1", cp=(150, 40), flow=10, away=(0, 1))
    return sc, Expect(
        tie_ins=[pipe_tie("hn-1", 300)],
        new_chambers=[300],
        no_recon=True,
        summary={"chamber_construction_cost": chamber_cost(rules(), 300)},
    )


@scenario("S08-12", tz=["TZ-51", "TZ-68"], title="новая камера на реконструируемой трубе: diameter по Ду после реконструкции")
def new_chamber_by_reconstructed_pipe() -> tuple[Scene, Expect]:
    sc = Scene()
    chain(sc, [(200, 200, 150), (150, 150, 50)])
    sc.oks("oks-1", cp=(90, 40), flow=10, away=(0, 1))
    armor(sc, -20, 185, top=[(90, gate_half(10))], bottom=[(90, gate_half(10))])
    need = diameter_for(rules(), 160)
    return sc, Expect(
        tie_ins=[pipe_tie("hn-1", 200)],
        new_chambers=[need],
        recon=[recon_part("hn-1", 200, 150, 10, 90.0)],
        summary={"chamber_construction_cost": chamber_cost(rules(), need)},
    )


@scenario("S08-13", tz=["TZ-16", "TZ-51", "TZ-68"], title="камера ветвления: diameter по стволу, камера врезки — по трубе")
def new_branch_chamber_diameter() -> tuple[Scene, Expect]:
    sc = Scene()
    chain(sc, [(400, 400, 500), (200, 300, 100)])
    sc.oks("oks-a", cp=(130, 200), flow=100, away=(0, 1))
    sc.oks("oks-b", cp=(170, 200), flow=100, away=(0, 1))
    trunk = diameter_for(rules(), 200)
    return sc, Expect(
        tie_ins=[pipe_tie("hn-1", 400)],
        new_chambers=[trunk, 400],
        no_recon=True,
        summary={"chamber_construction_cost": chamber_cost(rules(), trunk) + chamber_cost(rules(), 400)},
    )


@scenario("S08-14", tz=["TZ-6", "TZ-47", "TZ-69"], title="камера на стыке DN 200 и DN 100: входной diameter 200 не меньше нового участка, реконструкции нет")
def chamber_input_diameter_no_recon() -> tuple[Scene, Expect]:
    sc = Scene()
    ends = chain(sc, [(100, 200, 90), (150, 100, 20)])
    sc.oks("oks-1", cp=(ends[0], 50), flow=50, away=(0, 1))
    return sc, Expect(
        tie_ins=[chamber_tie("hc-1", 200)],
        chamber_recon=[],
        no_recon=True,
    )
