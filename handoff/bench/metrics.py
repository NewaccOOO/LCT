import argparse
import hashlib
import json
import math
import re
import subprocess
import sys
from pathlib import Path

BENCH = Path(__file__).resolve().parent
HEAVY = str(BENCH / "heavy.sh")
EARTH_R = 6371008.8
MIN_TURN_DEG = 3
PAIR_SUM_DEG = (60, 120)
PAIR_LINK_M = 30
SQUARE_TURN_DEG = 80
SLICED = {"moscow-1809", "city-1"}
SMALL_TURN_DEG = 30

SETS = {
    "organizers": [("real", "data/real/dataset.geojson", []), ("corrected", "data/real/dataset-corrected.geojson", [])],
    "dense": [(f"dense-{n}", f"data/synth/dense-{n}.geojson", []) for n in (50, 100, 200)],
    "medium": [(f"medium-{n}", f"data/synth/medium-{n}.geojson", []) for n in (1, 2, 3)],
    "expert": [(f"e4-{n}", f"data/samples/expert/e4-{n}.geojson", []) for n in ("tram-polygon", "tram-line", "metro", "unreachable", "combined")],
    "moscow": [("moscow-1809", "data/cities/moscow/input_1809.geojson", ["-Xmx8g"])],
    "city": [("city-1", "data/synth/city-1.geojson", ["-Xmx12g", "-XX:MaxNewSize=512m"])],
}


def xy(lon, lat, lat0):
    return math.radians(lon) * EARTH_R * math.cos(math.radians(lat0)), math.radians(lat) * EARTH_R


def deflections(coords):
    lat0 = coords[0][1]
    pts = [xy(c[0], c[1], lat0) for c in coords]
    result = []
    for i in range(1, len(pts) - 1):
        a = math.atan2(pts[i][1] - pts[i - 1][1], pts[i][0] - pts[i - 1][0])
        b = math.atan2(pts[i + 1][1] - pts[i][1], pts[i + 1][0] - pts[i][0])
        dev = math.degrees((b - a + math.pi) % (2 * math.pi) - math.pi)
        if abs(dev) >= MIN_TURN_DEG:
            result.append((pts[i], dev))
    return result


def variant_metrics(features):
    summary = next(f["properties"] for f in features if f["properties"]["object_type"] == "variant_summary")
    segments = [f for f in features if f["properties"]["object_type"] == "heat_network"]
    turns, pairs = [], []
    for seg in segments:
        found = deflections(seg["geometry"]["coordinates"])
        turns += [abs(dev) for _, dev in found]
        for (p, a), (q, b) in zip(found, found[1:]):
            if a * b > 0 and PAIR_SUM_DEG[0] <= abs(a + b) <= PAIR_SUM_DEG[1] and math.dist(p, q) < PAIR_LINK_M:
                pairs.append(f"{seg['properties']['id']} {a:.1f}/{b:.1f} через {math.dist(p, q):.1f} м")
    length = summary["new_network_length"]
    return {
        "score": summary["score"],
        "cost": summary["calculated_cost"],
        "length": length,
        "unconnected": len(summary["unconnected_oks_ids"]),
        "segments": len(segments),
        "chambers": sum(1 for f in features if f["properties"]["object_type"] == "heat_chamber"),
        "turns": len(turns),
        "turns_per_km": round(1000 * len(turns) / length, 2) if length else 0,
        "square_share": round(sum(t >= SQUARE_TURN_DEG for t in turns) / len(turns), 3) if turns else 0,
        "small_turns": sum(t < SMALL_TURN_DEG for t in turns),
        "pairs": len(pairs),
        "pair_list": pairs,
    }


def output_metrics(path):
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    by_variant = {}
    for f in data["features"]:
        by_variant.setdefault(str(f["properties"].get("variant_id")), []).append(f)
    return {vid: variant_metrics(feats) for vid, feats in sorted(by_variant.items())}


def parse_check(text):
    result, current = {}, None
    for line in text.splitlines():
        head = re.match(r"== вариант (\S+):", line)
        if head:
            current = result.setdefault(head.group(1), {"violations": {}, "info": {}})
            continue
        item = re.match(r"  (.+?): (\d+)  ", line)
        if current is not None and item:
            key, count = item.group(1), int(item.group(2))
            current["info" if key.startswith("i ") else "violations"][key] = count
    return result


def run(cmd, cwd, log):
    with open(log, "w", encoding="utf-8") as out:
        return subprocess.run([HEAVY, *cmd], cwd=cwd, stdout=out, stderr=subprocess.STDOUT).returncode


def measure(repo, jar, out_dir, name, source, jvm, check):
    out = out_dir / f"{name}.out.geojson"
    timing = out_dir / f"{name}.time"
    code = run(["/usr/bin/time", "-o", str(timing), "-p", "java", *jvm, "-jar", jar, "--cli", source, str(out)],
               repo, out_dir / f"{name}.log")
    entry = {"input": source, "exit": code}
    if code != 0 or not out.exists():
        return entry
    entry["seconds"] = float(re.search(r"real (\S+)", timing.read_text()).group(1))
    entry["md5"] = hashlib.md5(out.read_bytes()).hexdigest()
    entry["variants"] = output_metrics(out)
    if check:
        check_in, check_out = source, str(out)
        if name in SLICED:
            check_in, check_out = str(out_dir / f"{name}.cut.geojson"), str(out_dir / f"{name}.cut.out.geojson")
            run(["python3", "scripts/city_cut.py", source, str(out), check_in, check_out], repo, out_dir / f"{name}.cut.log")
        log = out_dir / f"{name}.check18.txt"
        status = run(["uv", "run", "--project", "tools", "python", "tools/validator/check18.py", check_in, check_out], repo, log)
        text = log.read_text(encoding="utf-8")
        entry["check"] = {"ok": status == 0 and "CHECK18 OK" in text, "variants": parse_check(text)}
    return entry


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", required=True)
    parser.add_argument("--jar", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--sets", default="organizers,dense,medium,moscow")
    parser.add_argument("--extra", action="append", default=[], help="name=path")
    parser.add_argument("--no-check", action="store_true")
    args = parser.parse_args()
    repo, out_dir = Path(args.repo).resolve(), Path(args.out).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    jar = str(Path(args.jar).resolve())
    items = [item for key in args.sets.split(",") if key for item in SETS[key]]
    items += [(spec.split("=", 1)[0], str(Path(spec.split("=", 1)[1]).resolve()), ["-Xmx8g"]) for spec in args.extra]
    head = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=repo, capture_output=True, text=True).stdout.strip()
    dirty = bool(subprocess.run(["git", "status", "--porcelain", "--untracked-files=no"], cwd=repo,
                                capture_output=True, text=True).stdout.strip())
    result = {"repo": str(repo), "jar": jar, "head": head, "dirty": dirty, "sets": {}}
    for name, source, jvm in items:
        print(f"{name}…", file=sys.stderr, flush=True)
        result["sets"][name] = measure(repo, jar, out_dir, name, source, jvm, not args.no_check)
        (out_dir / "metrics.json").write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")
    print(out_dir / "metrics.json")


if __name__ == "__main__":
    main()
