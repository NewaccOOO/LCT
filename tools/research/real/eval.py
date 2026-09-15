"""Оценка алгоритмов на реальном датасете: python eval.py <job>; job = service|baseline|ls|dp|bend|exact_all|exact|lp."""
import importlib, json, sys, time, traceback
from pathlib import Path
from heatopt import model, tm, service, bench
from heatopt.scene import Scene
from heatopt.graph import VisGraph

job = sys.argv[1]; tag = sys.argv[3] if len(sys.argv) > 3 else job
budget = int(sys.argv[2]) if len(sys.argv) > 2 and sys.argv[2] != "-" else None
seed = int(sys.argv[4]) if len(sys.argv) > 4 else 1
scene = Scene.load('data/research/real/dataset.geojson')
out = Path('data/research/real/out')
started = time.perf_counter()
rec = {'job': job}
try:
    if job in ('exact_all', 'exact', 'lp'):
        graph = VisGraph.build(scene, dn_guess=bench.smallest_dn(scene, scene.rules), tie_dns=bench.bound_tie_dns(scene, scene.rules))
        graph.adj
        rec['graph'] = {'nodes': len(graph.nodes), 'edges': len(graph.u), 'build_s': round(time.perf_counter() - started, 1)}
        exact = importlib.import_module('heatopt.exact')
        limit = float(sys.argv[2]) if len(sys.argv) > 2 else 1200.0
        if job == 'lp':
            b = exact.lp_bound(scene, graph, scene.rules, limit)
        else:
            b = exact.solve(scene, graph, scene.rules, limit, connect_all=(job == 'exact_all'))
        rec.update(status=str(b.status), objective=b.objective, bound=b.bound, cost_rub=b.cost_rub, length_m=b.length_m, log=str(b.log))
        solution = b.solution
    else:
        graph = VisGraph.build(scene, tangent=True)
        graph.adj
        rec['graph'] = {'nodes': len(graph.nodes), 'edges': len(graph.u), 'build_s': round(time.perf_counter() - started, 1)}
        t0 = time.perf_counter()
        if job == 'service':
            solution = service.from_output(scene, Path('../LCT/data/out/real.geojson'))
        elif job == 'baseline':
            solution, _, _ = tm.baseline(scene, graph)
        else:
            module = importlib.import_module(f'heatopt.candidates.{job}')
            solution = module.solve(scene, graph, scene.rules, None, budget or module.DEFAULT_BUDGET, seed)
        rec['solve_s'] = round(time.perf_counter() - t0, 1)
    if solution is not None:
        cost = model.cost(solution, scene)
        rec.update(S=cost.score_value, cost_rub=cost.total, length_m=cost.length, unconnected=cost.unconnected,
                   tie_ins=len(solution.tie_ins), model_violations=cost.violations[:5],
                   validator=model.validate(scene, cost), shape=model.shape_metrics(scene, cost.variant),
                   meta={k: v for k, v in (solution.meta or {}).items() if isinstance(v, (int, float, str))})
        (out / f"{tag}.geojson").write_text(json.dumps(model.to_geojson([cost]), ensure_ascii=False), encoding='utf-8')
except Exception:
    rec['error'] = traceback.format_exc()[-3000:]
rec['elapsed_s'] = round(time.perf_counter() - started, 1)
(out / f"{tag}.json").write_text(json.dumps(rec, ensure_ascii=False, indent=1, default=str), encoding='utf-8')
print(json.dumps({k: v for k, v in rec.items() if k not in ('validator', 'shape', 'meta', 'model_violations')}, ensure_ascii=False, default=str))
