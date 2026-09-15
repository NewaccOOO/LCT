"""Случайный прогон: сиды генератора с чередованием подмножеств типов ограничений через CLI сервиса и валидатор."""
import argparse
import json
import subprocess
import sys
import time
from concurrent.futures import (
    ThreadPoolExecutor,
    as_completed,
)
from typing import Any

from heatcheck.validate import (
    examples,
    run_all,
)
from heatscen.runner import (
    CACHE,
    CLI_TIMEOUT_S,
    TIMEOUT_EXIT_CODE,
    jar_path,
    java,
)
from heatscen.scene import (
    ROOT,
    rules,
)
from heatsynth.__main__ import (
    PRESETS,
    generate,
    write_collection,
)

SWEEP_DIR = CACHE / "sweep"
SUMMARY_PATH = CACHE / "sweep.json"
MEDIUM_EVERY = 6
MAX_STDERR_LINES = 5
SUBSETS = {
    0: [
        "park", "social_area", "prohibited_site", "water", "road", "tram_tracks", "gas_pipeline", "power_cable",
        "metro", "power_line_support", "railway", "water_supply", "sewer",
    ],
    1: ["park", "social_area", "prohibited_site", "water", "road", "tram_tracks", "gas_pipeline", "power_cable"],
    2: ["park", "social_area", "prohibited_site", "water", "metro", "power_line_support"],
    3: ["road", "tram_tracks", "gas_pipeline", "power_cable", "railway", "water_supply", "sewer"],
}


def run_seed(seed: int) -> dict[str, Any]:
    """Генератор → CLI → все правила валидатора для одного сида; errors пуст, только если сид прошёл."""
    started = time.monotonic()
    subset = seed % len(SUBSETS)
    preset = "medium" if seed % MEDIUM_EVERY == 0 else "small"
    result = {
        "preset": preset, "subset": subset, "exit_code": None, "violations": {}, "special": 0, "recon": 0,
        "elapsed": 0.0, "types": None, "errors": [],
    }
    errors = result["errors"]
    folder = SWEEP_DIR / str(seed)
    input_path, output_path = folder / "input.geojson", folder / "output.geojson"
    try:
        output_path.unlink(missing_ok=True)
        features, bounds = generate(PRESETS[preset], seed, rules(), SUBSETS[subset])
        write_collection(input_path, features, bounds, 0.0, f"s{seed}-")
        data = json.loads(input_path.read_text(encoding="utf-8"))
        found = {f["properties"]["restriction_type"] for f in data["features"] if f["properties"]["object_type"] == "restriction"}
        result["types"] = sorted(found)
        if found != set(SUBSETS[subset]):
            errors.append(f"во входе типы {sorted(found)}, подмножество {subset} заказывает {sorted(SUBSETS[subset])}")

        command = [java(), "-jar", str(jar_path()), "--cli", str(input_path), str(output_path)]
        try:
            done = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=CLI_TIMEOUT_S)
        except subprocess.TimeoutExpired:
            result["exit_code"] = TIMEOUT_EXIT_CODE
            errors.append(f"CLI не завершился за {CLI_TIMEOUT_S} с")
            return result
        result["exit_code"] = done.returncode
        if done.returncode != 0:
            errors.append(f"CLI завершился кодом {done.returncode}")
            errors += done.stderr.strip().splitlines()[:MAX_STDERR_LINES]
            return result

        output = json.loads(output_path.read_text(encoding="utf-8"))
        checks = run_all(data, output, rules())
        result["violations"] = {rule: len(check.violations) for rule, check in checks.items()}
        for rule, check in checks.items():
            if check.violations:
                errors.append(f"правило {rule}: нарушений {len(check.violations)}")
                errors += examples(check)
        props = [f["properties"] for f in output["features"]]
        result["special"] = sum(p["object_type"] == "heat_network" and p.get("laying_method") == "special" for p in props)
        result["recon"] = sum(p["object_type"] == "heat_network_reconstruction" for p in props)
    except Exception as error:  # один сломанный сид не должен останавливать остальные
        errors.append(f"прогон сида упал: {type(error).__name__}: {error}")
    finally:
        result["elapsed"] = round(time.monotonic() - started, 3)
    return result


def main() -> None:
    parser = argparse.ArgumentParser(prog="heatscen.sweep", description="Случайный прогон сидов генератора через CLI и валидатор")
    parser.add_argument("--seeds", type=int, required=True, help="прогнать сиды от 1 до N")
    parser.add_argument("--workers", type=int, required=True, help="сколько сидов считать одновременно")
    args = parser.parse_args()
    if args.seeds < 1 or args.workers < 1:
        parser.error("--seeds и --workers должны быть больше нуля")

    started = time.monotonic()
    results = {}
    with ThreadPoolExecutor(args.workers) as pool:
        futures = {pool.submit(run_seed, seed): seed for seed in range(1, args.seeds + 1)}
        for future in as_completed(futures):
            seed, result = futures[future], future.result()
            results[seed] = result
            status = "FAILED" if result["errors"] else "OK"
            print(
                f"seed {seed} {result['preset']} subset={result['subset']}: {status} "
                f"special={result['special']} recon={result['recon']} {result['elapsed']:.1f}s",
                flush=True,
            )
            for error in result["errors"]:
                print(f"  {error}", flush=True)
    elapsed = round(time.monotonic() - started, 3)

    failed = sorted(seed for seed, result in results.items() if result["errors"])
    special = sum(result["special"] for result in results.values())
    recon = sum(result["recon"] for result in results.values())
    subsets = {}
    for subset, types in SUBSETS.items():
        mine = [result for result in results.values() if result["subset"] == subset]
        subsets[str(subset)] = {
            "types": types,
            "seeds": len(mine),
            "failed": sum(bool(result["errors"]) for result in mine),
            "special": sum(result["special"] for result in mine),
            "recon": sum(result["recon"] for result in mine),
        }
    summary = {
        "seeds": args.seeds, "failed": failed, "special": special, "recon": recon, "elapsed": elapsed,
        "subsets": subsets, "results": {str(seed): results[seed] for seed in sorted(results)},
    }
    SUMMARY_PATH.parent.mkdir(parents=True, exist_ok=True)
    SUMMARY_PATH.write_text(json.dumps(summary, ensure_ascii=False, indent=1), encoding="utf-8")

    verified = {result["subset"] for result in results.values() if result["types"] == sorted(SUBSETS[result["subset"]])}
    foreign = sorted(seed for seed, result in results.items() if result["types"] not in (None, sorted(SUBSETS[result["subset"]])))
    problems = []
    if len(verified) == len(SUBSETS) and not foreign:
        print(f"SWEEP TYPES OK subsets={len(verified)}")
    else:
        missing = sorted(set(SUBSETS) - verified)
        print(f"SWEEP TYPES FAILED: подмножества без сида с верным составом {missing}, сиды с чужим составом {foreign}")
        problems.append("состав типов")
    if failed:
        problems.append(f"упали сиды {failed}")
    if special == 0:
        problems.append("ни одного специального участка за прогон")
    if recon == 0:
        problems.append("ни одной реконструкции за прогон")
    if problems:
        print(f"SWEEP FAILED seeds={args.seeds} failed={len(failed)} special={special} recon={recon}: {'; '.join(problems)}")
        sys.exit(1)
    print(f"SWEEP OK seeds={args.seeds} failed=0 special={special} recon={recon}")


if __name__ == "__main__":
    main()
