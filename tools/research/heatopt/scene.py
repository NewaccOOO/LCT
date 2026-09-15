"""Разобранный вход сцены в метрах EPSG:32637 и генерация сцен классов S, M, L через heatsynth."""
import hashlib
import json
import random
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from heatcheck.model import (
    Feature,
    Input,
    load_input,
)
from heatsynth.__main__ import (
    Preset,
    generate as synth_generate,
    write_collection,
)
from shapely.geometry import (
    LineString,
    Point,
)

RULES_PATH = Path("rules/rules.json")
SCENES_DIR = Path("data/research/scenes")
# C-5: (ОКС от, ОКС до, ограничений, участков сети)
CLASSES = {
    "S": (3, 5, 20, 30),
    "M": (6, 8, 50, 80),
    "L": (20, 20, 200, 300),
}


def load_rules(path: Path = RULES_PATH) -> dict[str, Any]:
    return json.loads(Path(path).read_text(encoding="utf-8"))


@dataclass(frozen=True)
class Terminal:
    cp_id: str
    oks_id: str
    point: Point
    flow: float


@dataclass
class Scene:
    path: Path
    raw: Any
    inp: Input
    rules: dict[str, Any]
    source: Point
    pipes: dict[str, Feature]
    chambers: dict[str, Feature]
    oks: dict[str, Feature]
    terminals: list[Terminal]

    @classmethod
    def load(cls, path: Path, rules: dict[str, Any] | None = None) -> "Scene":
        path = Path(path)
        rules = rules or load_rules()
        raw = json.loads(path.read_text(encoding="utf-8"))
        inp = load_input(raw, rules)
        sources = [f for f in inp.of_type("source") if isinstance(f.geom, Point)]
        pipes = {f.id: f for f in inp.of_type("heat_network") if isinstance(f.geom, LineString)}
        chambers = {f.id: f for f in inp.of_type("heat_chamber") if isinstance(f.geom, Point)}
        oks = {f.id: f for f in inp.of_type("oks_future")}
        terminals = []
        seen = set()
        for cp in inp.of_type("oks_connection_point"):
            oks_id = str(cp.props.get("oks_id"))
            if oks_id in oks and oks_id not in seen and isinstance(cp.geom, Point):
                seen.add(oks_id)
                terminals.append(Terminal(cp.id, oks_id, cp.geom, float(oks[oks_id].props.get("flow_tph", 0))))
        return cls(path, raw, inp, rules, sources[0], pipes, chambers, oks, terminals)

    def scene_hash(self) -> str:
        return file_hash(self.path)

    def total_flow(self) -> float:
        return sum(t.flow for t in self.terminals)


def file_hash(path: Path) -> str:
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def preset_for(cls: str, seed: int) -> Preset:
    low, high, restrictions, segments = CLASSES[cls]
    return Preset(random.Random(seed).randint(low, high), restrictions, segments)


def generate(cls: str, seed: int, out_dir: Path = SCENES_DIR, rules: dict[str, Any] | None = None) -> Path:
    """Сцена класса cls с сидом seed; повторный вызов не перезаписывает готовый файл."""
    out = Path(out_dir) / f"{cls}-{seed}.geojson"
    if not out.exists():
        features, bounds = synth_generate(preset_for(cls, seed), seed, rules or load_rules())
        tmp = out.with_suffix(".tmp.geojson")
        write_collection(tmp, features, bounds, 0.0)
        tmp.replace(out)
    return out


load = Scene.load
