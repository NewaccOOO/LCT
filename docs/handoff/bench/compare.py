import json
import sys

S_TOL = 0.0001
TIME_RATIO = 1.2


def load(path):
    return json.load(open(path, encoding="utf-8"))["sets"]


def violations(entry, vid):
    return entry.get("check", {}).get("variants", {}).get(vid, {}).get("violations", {})


def main():
    base, cand = load(sys.argv[1]), load(sys.argv[2])
    flags = []
    print("| набор | вар. | S было → стало | Δ S | без сети | длина, м | поворотов | на км | доля ≥80° | <30° | пары | нарушения check18 | время, с | выход тот же |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for name, c in cand.items():
        b = base.get(name)
        if not b or "variants" not in b or "variants" not in c:
            print(f"| {name} | — | нет базы или расчёт упал (код {c.get('exit')}) | | | | | | | | | | | |")
            flags.append(f"{name}: нет базы или расчёт упал")
            continue
        same = "да" if b.get("md5") == c.get("md5") else "нет"
        ratio = c["seconds"] / b["seconds"] if b["seconds"] else 1
        if ratio > TIME_RATIO:
            flags.append(f"{name}: время {b['seconds']:.1f} → {c['seconds']:.1f} с, перемерить A/B")
        for vid in sorted(set(b["variants"]) | set(c["variants"])):
            bv, cv = b["variants"].get(vid), c["variants"].get(vid)
            if not bv or not cv:
                print(f"| {name} | {vid} | вариант {'появился' if cv else 'пропал'} | | | | | | | | | | | |")
                continue
            ds = cv["score"] - bv["score"]
            bvi, cvi = violations(b, vid), violations(c, vid)
            new = sorted(k for k in cvi if cvi[k] > bvi.get(k, 0))
            if ds > S_TOL:
                flags.append(f"{name} вар. {vid}: S хуже на {ds:.4f} ({100 * ds / bv['score']:.2f} %)")
            if cv["unconnected"] > bv["unconnected"]:
                flags.append(f"{name} вар. {vid}: без сети {bv['unconnected']} → {cv['unconnected']}")
            if new:
                flags.append(f"{name} вар. {vid}: новые нарушения {new}")
            print(f"| {name} | {vid} | {bv['score']} → {cv['score']} | {ds:+.4f} | {bv['unconnected']} → {cv['unconnected']} "
                  f"| {bv['length']:.0f} → {cv['length']:.0f} | {bv['turns']} → {cv['turns']} "
                  f"| {bv['turns_per_km']} → {cv['turns_per_km']} | {bv['square_share']} → {cv['square_share']} "
                  f"| {bv['small_turns']} → {cv['small_turns']} | {bv['pairs']} → {cv['pairs']} "
                  f"| {sum(bvi.values())} → {sum(cvi.values())} | {b['seconds']:.1f} → {c['seconds']:.1f} | {same} |")
    print()
    print("Флаги:" if flags else "Флагов нет.")
    for f in flags:
        print(f"- {f}")


if __name__ == "__main__":
    main()
