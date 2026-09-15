import importlib
import pkgutil
import re
from collections.abc import Callable
from dataclasses import dataclass

from heatscen import families
from heatscen.expect import Expect
from heatscen.scene import Scene

ID_PATTERN = re.compile(r"S\d{2}-\d{2,}")
TZ_PATTERN = re.compile(r"TZ-\d+")

Build = Callable[[], tuple[Scene, Expect]]


@dataclass
class Scenario:
    id: str
    tz: list[str]
    title: str
    build: Build

    @property
    def slug(self) -> str:
        return self.build.__name__


SCENARIOS: dict[str, Scenario] = {}


def scenario(id: str, tz: list[str], title: str) -> Callable[[Build], Build]:
    if not ID_PATTERN.fullmatch(id):
        raise ValueError(f"ID сценария {id!r} не вида S<NN>-<номер>")
    if not tz or not all(TZ_PATTERN.fullmatch(t) for t in tz):
        raise ValueError(f"у сценария {id} нет ссылок на условия ТЗ вида TZ-n: {tz!r}")

    def register(build: Build) -> Build:
        if id in SCENARIOS:
            raise ValueError(f"ID сценария {id} уже занят функцией {SCENARIOS[id].slug}")
        SCENARIOS[id] = Scenario(id, list(tz), title, build)
        return build

    return register


def load_all() -> list[Scenario]:
    for module in pkgutil.iter_modules(families.__path__):
        importlib.import_module(f"{families.__name__}.{module.name}")
    return sorted(SCENARIOS.values(), key=lambda s: s.id)
