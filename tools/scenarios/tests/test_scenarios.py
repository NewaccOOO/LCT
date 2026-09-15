import pytest

from heatscen import rules
from heatscen.expect import check
from heatscen.registry import (
    Scenario,
    load_all,
)
from heatscen.runner import run


@pytest.mark.parametrize("sc", load_all(), ids=lambda s: f"{s.id}-{s.slug}")
def test_scenario(sc: Scenario) -> None:
    scene, expect = sc.build()
    messages = check(run(scene, sc.id), expect, rules())
    assert not messages, "\n".join(messages)
