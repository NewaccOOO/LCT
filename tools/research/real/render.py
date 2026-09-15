"""python render.py <input.geojson> <out.svg> <label:output.geojson> ... — карта входа и вариантов rank 1 каждого выхода."""
import json, sys
from shapely.geometry import shape
from shapely.ops import transform
from pyproj import Transformer
t = Transformer.from_crs(4326, 32637, always_xy=True)
def g(f): return transform(t.transform, shape(f['geometry']))
inp = json.load(open(sys.argv[1]))['features']
items = []
col = {'oks': '#c9c9c9', 'railway': '#8b4513', 'water': '#7fbfff', 'road': '#f0d9a0'}
for f in inp:
    p = f['properties']; G = g(f)
    if p['object_type'] == 'restriction': items.append(('poly', G, col.get(p['restriction_type'], '#ffb0b0')))
    elif p['object_type'] == 'heat_network': items.append(('line', G, '#d00000', 3))
    elif p['object_type'] == 'heat_chamber': items.append(('pt', G, '#d00000', 5, None))
    elif p['object_type'] == 'source': items.append(('pt', G, '#000', 9, 'ТЭЦ'))
    elif p['object_type'] == 'oks_connection_point': items.append(('pt', G, '#0000ff', 5, str(p['id'])))
palette = ['#0033cc', '#00aa44', '#aa00aa', '#008888']
legend = []
for k, arg in enumerate(sys.argv[3:]):
    label, path = arg.split(':', 1); c = palette[k % 4]; legend.append(f'<tspan fill="{c}">{label}</tspan>')
    for f in json.load(open(path))['features']:
        p = f['properties']
        if f['geometry'] is None or str(p.get('rank', 1)) != '1' and str(p['variant_id']) != '1': continue
        G = g(f)
        if p['object_type'] == 'heat_network': items.append(('line', G, c, 1.5 if p.get('laying_method') == 'base' else 3))
        elif p['object_type'] == 'heat_network_reconstruction': items.append(('line', G, '#ff8800', 5))
        elif p['object_type'] == 'tie_in': items.append(('pt', G, c, 6, None))
        elif p['object_type'] == 'heat_chamber': items.append(('pt', G, c, 3, None))
xs, ys = [], []
for it in items:
    b = it[1].bounds; xs += [b[0], b[2]]; ys += [b[1], b[3]]
x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys); W = 1600; s = W / (x1 - x0); H = int((y1 - y0) * s)
X = lambda x: (x - x0) * s
Y = lambda y: H - (y - y0) * s
def path(G):
    parts = []
    for gg in getattr(G, 'geoms', [G]):
        for r in ([gg.exterior] if gg.geom_type == 'Polygon' else [gg]):
            parts.append('M ' + ' L '.join(f'{X(x):.1f},{Y(y):.1f}' for x, y in r.coords) + (' Z' if gg.geom_type == 'Polygon' else ''))
    return ' '.join(parts)
svg = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="100%" style="background:#fff">']
for it in items:
    if it[0] == 'poly': svg.append(f'<path d="{path(it[1])}" fill="{it[2]}" fill-opacity="0.7" stroke="#888" stroke-width="0.5"/>')
for it in items:
    if it[0] == 'line': svg.append(f'<path d="{path(it[1])}" fill="none" stroke="{it[2]}" stroke-width="{it[3]}"/>')
for it in items:
    if it[0] == 'pt':
        x, y = it[1].x, it[1].y; svg.append(f'<circle cx="{X(x):.1f}" cy="{Y(y):.1f}" r="{it[3]}" fill="{it[2]}"/>')
        if it[4]: svg.append(f'<text x="{X(x)+7:.1f}" y="{Y(y)-5:.1f}" font-size="16">{it[4]}</text>')
svg.append(f'<text x="10" y="25" font-size="18">{" | ".join(legend)} | оранжевое: реконструкция</text></svg>')
open(sys.argv[2], 'w').write('\n'.join(svg)); print('ok')
