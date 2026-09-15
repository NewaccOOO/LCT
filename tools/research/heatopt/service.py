"""Запуск сервиса через CLI jar и разбор варианта с rank = 1 в Solution (C-3)."""
import json
import os
import re
import shutil
import subprocess
import time
from pathlib import Path
from typing import Any

from heatcheck.model import load_output

from heatopt.model import (
    Solution,
    TieIn,
)
from heatopt.scene import Scene

JAR = Path("target/heatnet.jar")
OUTPUTS_DIR = Path("data/research/outputs")
DONE = re.compile(r"PIPELINE DONE variants=(\d+) elapsed=([\d.]+)s")


def java_command(jar: Path, input_path: Path, output_path: Path) -> list[str]:
    java_home = os.environ.get("JAVA_HOME")
    if not java_home:
        brew = shutil.which("brew")
        if brew:
            java_home = subprocess.run([brew, "--prefix", "openjdk@11"], capture_output=True, text=True, check=True).stdout.strip()
    java = str(Path(java_home) / "bin" / "java") if java_home else "java"
    return [java, "-jar", str(jar), "--cli", str(input_path), str(output_path)]


def solve(scene: Scene, jar: Path = JAR, out_dir: Path = OUTPUTS_DIR, timeout_s: float = 1200) -> Solution:
    out_dir = Path(out_dir) / scene.path.stem
    out_dir.mkdir(parents=True, exist_ok=True)
    output = out_dir / "service.geojson"
    started = time.perf_counter()
    run = subprocess.run(java_command(jar, scene.path, output), capture_output=True, text=True, timeout=timeout_s)
    elapsed = time.perf_counter() - started
    if run.returncode != 0:
        raise RuntimeError(f"сервис завершился с кодом {run.returncode}: {run.stdout[-500:]} {run.stderr[-500:]}")
    solution = from_output(scene, output)
    solution.elapsed = elapsed
    match = DONE.search(run.stdout)
    if match:
        solution.meta["pipeline_elapsed"] = float(match.group(2))
    return solution


def from_output(scene: Scene, output: Path) -> Solution:
    data = json.loads(Path(output).read_text(encoding="utf-8"))
    return from_data(scene, data, output)


def from_data(scene: Scene, data: Any, output: Path | None = None) -> Solution:
    out = load_output(data)
    best = min(out.variants.values(), key=lambda v: v.summaries[0].props["rank"])
    edges, dns = [], {}
    for seg in best.segments:
        dns[len(edges)] = seg.props["diameter"]
        edges.append([(x, y) for x, y in seg.geom.coords])
    ties = [
        TieIn(str(t.props["existing_object_id"]), str(t.props["existing_object_type"]), (t.geom.x, t.geom.y))
        for t in best.tie_ins
    ]
    summary = best.summaries[0].props
    meta = {
        "output": str(output) if output else None,
        "declared": {k: summary[k] for k in ("calculated_cost", "length", "score")},
        "variant_scores": sorted(v.summaries[0].props["score"] for v in out.variants.values()),
    }
    return Solution(edges, ties, dn=dns, meta=meta)
