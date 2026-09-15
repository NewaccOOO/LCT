import json
import os
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from heatscen.scene import (
    ROOT,
    Scene,
)

CACHE = ROOT / "data" / "scenarios"
CLI_TIMEOUT_S = 300
TIMEOUT_EXIT_CODE = -1


@dataclass
class Run:
    exit_code: int
    stdout: str
    stderr: str
    output: dict[str, Any] | None
    elapsed: float
    input: dict[str, Any]


def jar_path() -> Path:
    return ROOT / os.environ.get("HEATNET_JAR", "target/heatnet.jar")


def java() -> str:
    home = os.environ.get("JAVA_HOME")
    return str(Path(home) / "bin" / "java") if home else "java"


def cached(scenario_id: str) -> Run | None:
    folder = CACHE / scenario_id
    meta_path = folder / "run.json"
    if not meta_path.exists():
        return None
    meta = json.loads(meta_path.read_text(encoding="utf-8"))
    output_path = folder / "output.geojson"
    output = json.loads(output_path.read_text(encoding="utf-8")) if output_path.exists() else None
    data = json.loads((folder / "input.geojson").read_text(encoding="utf-8"))
    return Run(meta["exit_code"], meta["stdout"], meta["stderr"], output, meta["elapsed"], data)


def run(scene: Scene, scenario_id: str) -> Run:
    """Прогоняет сцену через CLI сервиса; кэш в data/scenarios/<id>/ живёт, пока не изменились сцена и jar."""
    folder = CACHE / scenario_id
    meta_path = folder / "run.json"
    scene_hash, jar_mtime = scene.scene_hash(), jar_path().stat().st_mtime_ns
    if meta_path.exists():
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        if meta["scene_hash"] == scene_hash and meta["jar_mtime"] == jar_mtime:
            return cached(scenario_id)

    folder.mkdir(parents=True, exist_ok=True)
    input_path, output_path = folder / "input.geojson", folder / "output.geojson"
    input_path.write_text(json.dumps(scene.to_geojson(), ensure_ascii=False), encoding="utf-8")
    output_path.unlink(missing_ok=True)
    command = [java(), "-jar", str(jar_path()), "--cli", str(input_path), str(output_path)]
    started = time.monotonic()
    try:
        done = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=CLI_TIMEOUT_S)
        code, stdout, stderr = done.returncode, done.stdout, done.stderr
    except subprocess.TimeoutExpired:
        code, stdout, stderr = TIMEOUT_EXIT_CODE, "", f"CLI не завершился за {CLI_TIMEOUT_S} с"
    elapsed = round(time.monotonic() - started, 3)
    meta = {"exit_code": code, "stdout": stdout, "stderr": stderr, "elapsed": elapsed, "scene_hash": scene_hash, "jar_mtime": jar_mtime}
    meta_path.write_text(json.dumps(meta, ensure_ascii=False, indent=1), encoding="utf-8")
    return cached(scenario_id)
