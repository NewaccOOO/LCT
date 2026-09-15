"""Геометрическая нижняя граница: свободные врезки, все подмножества расходов.
Сертификат не зависит от VisGraph. Формула и доказательство — в итоговом отчёте.
"""
import hashlib, json, math, time
from pathlib import Path
import numpy as np
import shapely
from heatopt.scene import Scene
from heatopt.exact import Milp, status_of
from heatopt import model

OUT=Path('data/research/real/out')

def solve_lp(cost, matrix, upper, name):
    m=Milp(); cols=m.add_cols(-np.asarray(cost),0,np.inf)
    rows=m.add_rows(len(upper),-np.inf,0); m.row_upper[-1]=np.asarray(upper)
    r,c=np.nonzero(matrix); m.add(r,c,matrix[r,c])
    h=m.run(OUT/(name+'.log'),120,None)
    assert str(status_of(h))=='optimal',h.getModelStatus()
    x=np.maximum(np.array(h.getSolution().col_value),0)
    loads=matrix@x
    # Сертификат — допустимый вектор; оптимальность солвера для границы не нужна.
    scale=min(1.0,float(np.min(np.divide(upper,loads,out=np.ones_like(loads),where=loads>0))))
    x*=max(0,scale-1e-9)
    assert np.max(matrix@x-upper)<=1e-10
    return x

def main():
    started=time.perf_counter(); scene=Scene.load('data/research/real/dataset.geojson'); rules=scene.rules
    n=len(scene.terminals); assert n<=20
    root=shapely.union_all([f.geom for f in scene.pipes.values()]+[f.geom for f in scene.chambers.values()])
    xy=np.array([(t.point.x,t.point.y) for t in scene.terminals])
    distances=np.array([max(0,t.point.distance(root)-0.05) for t in scene.terminals])
    flow=np.array([t.flow for t in scene.terminals]); masks=np.arange(1,1<<n,dtype=np.uint32)
    subsets=((masks[:,None]>>np.arange(n,dtype=np.uint32))&1).astype(float)
    total=subsets@flow
    a=rules['score']['w_cost']/rules['score']['cost_base']; b=rules['score']['w_length']/rules['score']['length_base_m']
    rates=np.array([a*next(r['new_rub_m'] for r in rules['diameters'] if r['capacity_tph']+1e-9>=g)+b for g in total])
    # Непересекающиеся диски, каждый не касается существующей сети.
    pairs=[(i,j) for i in range(n) for j in range(i+1,n)]
    mat=np.zeros((n+len(pairs),n)); mat[:n]=np.eye(n); caps=list(distances)
    for k,(i,j) in enumerate(pairs):
        mat[n+k,i]=mat[n+k,j]=1; caps.append(max(0,float(np.linalg.norm(xy[i]-xy[j]))-0.1))
    radius=solve_lp(np.ones(n),mat,np.array(caps),'bound-disks')
    # y_i оплачивает путь терминала до сети; z — длину выхода из дисков.
    # Любое ребро с набором потомков A: sum(y_i,i in A)+z <= rate(flow(A)).
    penalty=a*np.array([rules['penalty']['fixed']+rules['penalty']['per_tph']*t.flow for t in scene.terminals])
    matrix=np.vstack([np.column_stack([subsets,np.ones(len(subsets))]),np.column_stack([np.diag(distances),radius])])
    upper=np.concatenate([rates,penalty]); objective=np.append(distances,radius.sum())
    weights=solve_lp(objective,matrix,upper,'bound-all-subsets')
    terminal_credit=weights[:-1]*distances+weights[-1]*radius
    tie=a*rules['tie_in_cost']
    bound=min(float(terminal_credit.sum()+tie),float(penalty.sum()))
    # Отдельный сертификат для S с округлением длин: диски вне всех
    # специальных зон и ещё 1.1 м вокруг них. Подметровые исключения
    # возможны только внутри этой полосы, поэтому все пересекающие диски
    # участки имеют длину >= 0.999 м. round(L,2) >= (1-.005/.999)*L.
    safe=distances.copy()
    for obs in scene.inp.special:
        for i,t in enumerate(scene.terminals):
            safe[i]=min(safe[i],max(0,t.point.distance(obs.feature.geom)-obs.params['margin_m']-1.1))
    safe=np.maximum(0,safe-.1)
    leaf_rates=np.array([a*next(row['new_rub_m'] for row in rules['diameters'] if row['capacity_tph']+1e-9>=t.flow)+b for t in scene.terminals])
    rounded_rates=(1-.005/.999)*leaf_rates-a*.005/.999
    safe_caps=np.array(caps); safe_caps[:n]=np.minimum(safe,penalty/rounded_rates)
    safe_radius=solve_lp(rounded_rates,mat,safe_caps,'bound-protected-disks')
    rounded_credit=rounded_rates*safe_radius
    rounded_bound=min(float(rounded_credit.sum()+tie),float(penalty.sum()))-.0005
    assert np.all(rounded_credit<=penalty+1e-10)
    rec={'protected_disk_radius_m':safe_radius.tolist(),'protected_disk_max_radius_m':safe.tolist(),'rounded_rate':rounded_rates.tolist(),'rounded_terminal_credit':rounded_credit.tolist(),'bound_serialized_S':rounded_bound,'bound_serialized_down_3':math.floor(rounded_bound*1000)/1000,'protected_disk_pair_slack':float(np.min(safe_caps-mat@safe_radius)),'kind':'continuous_geometric_lower_bound','dataset_sha256':scene.scene_hash(),'rules_sha256':hashlib.sha256(Path('rules/rules.json').read_bytes()).hexdigest(),'terminals':[t.cp_id for t in scene.terminals],'flow_tph':flow.tolist(),'distance_m':distances.tolist(),'radius_m':radius.tolist(),'y_score_per_m':weights[:-1].tolist(),'z_score_per_m':weights[-1],'terminal_credit':terminal_credit.tolist(),'penalty_score':penalty.tolist(),'subset_count':len(subsets),'minimum_slack':float(np.min(upper-matrix@weights)),'tie_score':tie,'bound_unrounded_S':bound,'bound_rounded_down_3':math.floor(bound*1000)/1000,'elapsed_s':time.perf_counter()-started,'scope':'Геометрические длины до округления участков до 0.01 м; для сериализованного S вычесть не более 0.005*sum(rate_diameter*K + length_weight) по участкам и 0.0005 финального округления. Реконструкция и камеры отброшены.'}
    (OUT/'lower-bound.json').write_text(json.dumps(rec,ensure_ascii=False,indent=2)); print(json.dumps(rec,ensure_ascii=False))
if __name__=='__main__': main()
