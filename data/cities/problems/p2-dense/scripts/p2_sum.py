"""Сводка прогона: подключено, S, стоимость, длина, время, неподключённые с причинами. p2_sum.py вход выход [лог]"""
import json, re, sys

src, out = sys.argv[1], sys.argv[2]
log = sys.argv[3] if len(sys.argv) > 3 else None
fin = json.load(open(src))["features"]
n = sum(f["properties"]["object_type"] == "oks_connection_point" for f in fin)
summ = sorted((f["properties"] for f in json.load(open(out))["features"] if f["properties"]["object_type"] == "variant_summary"),
              key=lambda s: s["rank"])
s = summ[0]
t = None
if log:
    m = re.search(r"PIPELINE DONE variants=(\d+) elapsed=([\d.]+)s", open(log, errors="replace").read())
    t = m and float(m.group(2))
crit = out.replace(".geojson", ".criteria.json")
reasons = []
try:
    reasons = [f"{r['oks_id']}:{r['reason']}" for r in json.load(open(crit))[0]["criteria"]["unconnected_reasons"]]
except (OSError, KeyError, IndexError):
    pass
print(f"подключено {n - len(s['unconnected_oks_ids'])}/{n} S={s['score']} C={s['calculated_cost'] / 1e6:.1f} млн "
      f"L={s['new_network_length']} вариантов {len(summ)} S всех {[x['score'] for x in summ]} время {t} с")
print("  без сети:", reasons or s["unconnected_oks_ids"])
