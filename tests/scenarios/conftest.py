import json
import time
from concurrent.futures import ThreadPoolExecutor

import pytest

from heatscen.runner import (
    CACHE,
    run,
)

WORKERS = 4
RESULTS = CACHE / "results.json"
started = time.monotonic()
outcomes: dict[str, dict[str, object]] = {}


def warm(item: pytest.Item) -> None:
    sc = item.callspec.params["sc"]
    try:
        run(sc.build()[0], sc.id)
    except Exception:  # сломанный сценарий упадёт в самом тесте с понятным трейсом
        pass


def pytest_collection_finish(session: pytest.Session) -> None:
    # CLI-прогоны выбранных сценариев идут параллельно до тестов; тесты потом читают кэш.
    with ThreadPoolExecutor(WORKERS) as pool:
        list(pool.map(warm, session.items))


def pytest_runtest_logreport(report: pytest.TestReport) -> None:
    scenario_id = report.nodeid.split("[", 1)[-1].split("-", 2)
    key = "-".join(scenario_id[:2])
    entry = outcomes.setdefault(key, {"nodeid": report.nodeid, "status": "passed", "elapsed": 0.0})
    entry["elapsed"] = round(float(entry["elapsed"]) + report.duration, 3)
    if hasattr(report, "wasxfail"):
        entry["status"] = "xfailed" if report.skipped else "xpassed"
    elif report.failed:
        entry["status"] = "failed" if report.when == "call" else "error"
    elif report.skipped:
        entry["status"] = "skipped"


def pytest_sessionfinish(session: pytest.Session, exitstatus: int) -> None:
    CACHE.mkdir(parents=True, exist_ok=True)
    data = {"exitstatus": int(exitstatus), "elapsed": round(time.monotonic() - started, 1), "scenarios": dict(sorted(outcomes.items()))}
    RESULTS.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
