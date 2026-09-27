"""Сводка прогона: вход + выход → числа для сравнения город / синтетика / датасет. Запуск: python3 analyze.py name in out [log]"""
import json
import re
import sys
from collections import Counter


def main():
    name, src, out = sys.argv[1:4]
    log = sys.argv[4] if len(sys.argv) > 4 else None
    fin = json.load(open(src, encoding="utf-8"))["features"]
    kinds = Counter(f["properties"]["object_type"] for f in fin)
    rtypes = Counter(f["properties"].get("restriction_type") for f in fin if f["properties"]["object_type"] == "restriction")
    cps = {str(f["properties"]["id"]): f["properties"].get("flow_tph") for f in fin if f["properties"]["object_type"] == "oks_connection_point"}
    fut = {str(f["properties"]["id"]): f["properties"].get("flow_tph") for f in fin if f["properties"]["object_type"] == "oks_future"}
    total_flow = sum(v or 0 for v in (fut.values() if fut else cps.values()))
    fout = json.load(open(out, encoding="utf-8"))["features"]
    summ = sorted((f["properties"] for f in fout if f["properties"]["object_type"] == "variant_summary"), key=lambda s: s["rank"])
    v1 = [f["properties"] for f in fout if f["properties"].get("variant_id") in ("1", 1)]
    segs = [p for p in v1 if p["object_type"] == "heat_network"]
    dn = Counter(p["diameter"] for p in segs)
    special = sum(p["length"] for p in segs if p.get("laying_method", p.get("method")) not in (None, "normal", "standard"))
    s = summ[0]
    n_oks = len(fut) or len(cps)
    conn = n_oks - len(s["unconnected_oks_ids"])
    row = {
        "name": name,
        "features_in": len(fin),
        "kinds": dict(kinds),
        "restrictions": dict(rtypes),
        "oks": n_oks,
        "connected": conn,
        "total_flow_tph": round(total_flow, 1),
        "variants": len(summ),
        "S": s["score"],
        "calculated_cost_mln": round(s["calculated_cost"] / 1e6, 1),
        "construction_cost_mln": round(s["construction_cost"] / 1e6, 1),
        "chamber_cost_mln": round(s["chamber_construction_cost"] / 1e6, 1),
        "tie_in_count": s["existing_chamber_tie_in_count"],
        "penalty_mln": round(s["unconnected_penalty"] / 1e6, 1),
        "length_m": s["new_network_length"],
        "segments": len(segs),
        "cost_per_oks_mln": round(s["construction_cost"] / 1e6 / max(conn, 1), 2),
        "length_per_oks_m": round(s["new_network_length"] / max(conn, 1), 1),
        "cost_per_m_rub": round(s["construction_cost"] / max(s["new_network_length"], 1)),
        "dn_hist": dict(sorted(dn.items())),
        "segment_keys": sorted(segs[0].keys()) if segs else [],
        "all_S": [x["score"] for x in summ],
    }
    if log:
        text = open(log, encoding="utf-8", errors="replace").read()
        m = re.search(r"PIPELINE DONE variants=(\d+) elapsed=([\d.]+)s", text)
        if m:
            row["pipeline_s"] = float(m.group(2))
        row["warnings"] = [l for l in text.splitlines() if "ПРЕДУПРЕЖДЕНИЕ" in l or " WARN " in l or "ERROR" in l][:10]
    print(json.dumps(row, ensure_ascii=False))


if __name__ == "__main__":
    main()
