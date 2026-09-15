"""Изолированный эксперимент поиска; исходный кандидат ls не меняется."""
import argparse, collections, hashlib, json, math, random, time
from pathlib import Path
import numpy as np
from heatcheck.model import load_output
from heatopt import model, tm
from heatopt.scene import Scene
from heatopt.graph import VisGraph, Node
from heatopt.candidates import ls


def seed_graph(scene, path):
    data=json.loads(Path(path).read_text())
    variants=load_output(data).variants
    v=min(variants.values(),key=lambda v:v.summaries[0].props['rank'])
    extra=[]
    ties={t.id:t for t in v.tie_ins}
    for t in v.tie_ins:
        p=t.props; ref=str(p['existing_object_id']); chamber=p['existing_object_type']=='heat_chamber'
        ignored=frozenset(k for k,f in scene.pipes.items() if f.geom.distance(t.geom)<=0.5)
        cap=4-len(scene.inp.chamber_links.get(ref,[])) if chamber else 2
        extra.append(Node(t.geom.x,t.geom.y,'tie_chamber' if chamber else 'tie_pipe',ref,ignored,cap))
    for seg in v.segments:
        for x,y in seg.geom.coords:
            if not any(math.dist((x,y), n.xy)<0.05 for n in extra) and not any(t.point.distance(__import__('shapely').Point(x,y))<0.05 for t in scene.terminals):
                extra.append(Node(x,y,'steiner'))
    g=VisGraph.build(scene,tangent=True,extra_nodes=extra)
    xy=np.array([n.xy for n in g.nodes])
    def node_at(point):
        distances=np.hypot(xy[:,0]-point[0],xy[:,1]-point[1]); k=int(distances.argmin())
        assert distances[k]<0.05
        return k
    by_start=collections.defaultdict(list)
    for s in v.segments: by_start[str(s.props['start_node_id'])].append(s)
    trees=[]
    for tie in v.tie_ins:
        root=node_at((tie.geom.x,tie.geom.y)); p=tie.props
        tree=tm.Tree(root,model.TieIn(str(p['existing_object_id']),p['existing_object_type'],g.nodes[root].xy),g.nodes[root].capacity)
        tree.point_id(g.nodes[root].xy,root)
        stack=[tie.id]; seen=set()
        while stack:
            key=stack.pop(); assert key not in seen; seen.add(key)
            for s in by_start[key]:
                coords=list(s.geom.coords)
                for a,b in zip(coords,coords[1:]):
                    i=tree.point_id(a,node_at(a)); j=tree.point_id(b,node_at(b))
                    tree.segs.append(tm.TreeSeg(i,j)); tree.children[i]+=1
                end=str(s.props['end_node_id'])
                if end in {t.cp_id for t in scene.terminals}: tree.terminals[end]=j
                stack.append(end)
        assert tree.segs and ls.well_formed(tree)
        trees.append(tree)
    return g,trees

class Experiment(ls.Search):
    def __init__(self,*args,deadline=None,**kwargs):
        super().__init__(*args,**kwargs); self.deadline=deadline; self.stats=collections.defaultdict(lambda:{'calls':0,'seconds':0.0}); self.trace=[]; self.t0=time.perf_counter()
    def remember(self,p):
        old=self.best
        super().remember(p)
        if self.best is not old:
            self.trace.append({'s':round(time.perf_counter()-self.t0,3),'S':p.score,'strategy':p.strategy,'left':self.left})
    def moves(self,plan):
        for memo,fn in super().moves(plan):
            def measured(fn=fn,name=memo[0]):
                if self.deadline and time.perf_counter()>=self.deadline:
                    self.left=0; return None
                t=time.perf_counter()
                try: return fn()
                finally:
                    self.stats[name]['calls']+=1; self.stats[name]['seconds']+=time.perf_counter()-t
            yield memo,measured
    def warm_run(self,budget,trees):
        self.left=budget; base=self.plan(trees,'service'); current=base; pool=[]
        while self.left>0:
            self.starts+=1; current=self.descend(current)
            if all(sorted(ls.tree_key(t) for t in current.trees)!=sorted(ls.tree_key(t) for t in p.trees) for p in pool):
                pool=sorted(pool+[current],key=lambda p:p.rank)[:ls.ELITE_SIZE]
            if self.left<=0 or (self.deadline and time.perf_counter()>=self.deadline): break
            current=self.perturb(pool[self.rng.randrange(len(pool))])
        return self.best,base


def main():
    ap=argparse.ArgumentParser(); ap.add_argument('--budget',type=int,default=3000); ap.add_argument('--seconds',type=float); ap.add_argument('--seed',type=int,default=1); ap.add_argument('--profile',choices=['full','forest','merge'],default='full'); ap.add_argument('--dn',type=int); ap.add_argument('--start'); ap.add_argument('--tag',required=True)
    a=ap.parse_args(); start=time.perf_counter(); scene=Scene.load('data/research/real/dataset.geojson')
    if a.start: graph,trees=seed_graph(scene,a.start)
    else: graph=VisGraph.build(scene,tangent=True,dn_guess=a.dn); trees=None
    graph.adj; graph_end=time.perf_counter()
    if a.profile=='forest': ls.NEIGHBORHOODS=(ls.NEIGHBORHOODS[0],)
    if a.profile=='merge': ls.NEIGHBORHOODS=(('connect','merge','fuse','retie','split'),)
    search=Experiment(scene,graph,scene.rules,None,random.Random(a.seed),deadline=start+a.seconds if a.seconds else None)
    best,base=search.warm_run(a.budget,trees) if trees else search.run(a.budget)
    chosen=best if best is not None and best.rank<=base.rank else base
    end=time.perf_counter(); c=model.cost(tm.solution(chosen.trees),scene); violations=model.validate(scene,c)
    rec={'args':vars(a),'dataset_sha256':scene.scene_hash(),'rules_sha256':hashlib.sha256(Path('rules/rules.json').read_bytes()).hexdigest(),'graph_s':graph_end-start,'solve_s':end-graph_end,'total_s':end-start,'S':c.score_value,'cost_rub':c.total,'length_m':c.length,'unconnected':c.unconnected,'model_violations':c.violations,'validator':violations,'nodes':len(graph.nodes),'edges':len(graph.u),'starts':search.starts,'accepted':dict(search.accepted),'moves':dict(search.stats),'trace':search.trace,'base_S':base.score,'unused_budget':search.left}
    out=Path('data/research/real/out')
    (out/(a.tag+'.json')).write_text(json.dumps(rec,ensure_ascii=False,indent=2))
    (out/(a.tag+'.geojson')).write_text(json.dumps(model.to_geojson([c]),ensure_ascii=False))
    print(json.dumps({k:rec[k] for k in ['args','total_s','S','accepted','validator','base_S']},ensure_ascii=False),flush=True)
if __name__=='__main__': main()
