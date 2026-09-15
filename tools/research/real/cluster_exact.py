"""Точный оптимум exact_all на подсцене: python cluster_exact.py <tag> <id,id,...> [limit_s]. Остальные точки
подключения убираются, их здания остаются существующими ОКС."""
import json, pickle, sys, time, traceback
from pathlib import Path
from heatopt import bench, exact, model
from heatopt.scene import Scene
from heatopt.graph import VisGraph

tag, ids = sys.argv[1], set(sys.argv[2].split(','))
limit = float(sys.argv[3]) if len(sys.argv) > 3 else 1500.0
out = Path('data/research/real/out')
raw = json.load(open('data/research/real/dataset.geojson'))
raw['features'] = [f for f in raw['features'] if f['properties'].get('object_type') != 'oks_connection_point' or str(f['properties']['id']) in ids]
sub = out / f'{tag}.input.geojson'
sub.write_text(json.dumps(raw, ensure_ascii=False), encoding='utf-8')
started = time.perf_counter()
rec = {'tag': tag, 'ids': sorted(ids)}
try:
    scene = Scene.load(sub)
    graph = VisGraph.build(scene, dn_guess=bench.smallest_dn(scene, scene.rules), tie_dns=bench.bound_tie_dns(scene, scene.rules))
    graph.adj
    rec['graph'] = {'nodes': len(graph.nodes), 'edges': len(graph.u), 'build_s': round(time.perf_counter() - started, 1)}
    b = exact.solve(scene, graph, scene.rules, limit, connect_all=True)
    rec.update(status=str(b.status), objective=b.objective, bound=b.bound, cost_rub=b.cost_rub, length_m=b.length_m, log=str(b.log))
    if b.solution is not None:
        pickle.dump(b.solution, open(out / f'{tag}.solution.pkl', 'wb'))
        cost = model.cost(b.solution, scene)
        rec.update(S=cost.score_value, unconnected=cost.unconnected, tie_ins=len(b.solution.tie_ins), model_violations=cost.violations[:5])
        (out / f'{tag}.geojson').write_text(json.dumps(model.to_geojson([cost]), ensure_ascii=False), encoding='utf-8')
except Exception:
    rec['error'] = traceback.format_exc()[-3000:]
rec['elapsed_s'] = round(time.perf_counter() - started, 1)
(out / f'{tag}.json').write_text(json.dumps(rec, ensure_ascii=False, indent=1, default=str), encoding='utf-8')
print(json.dumps(rec, ensure_ascii=False, default=str))
