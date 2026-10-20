import {segX, inRing, BUILDING_KEYS, dnRow} from './model.js';

export const buildingGap = dn => dn <= 400 ? 5 : dn <= 800 ? 7 : 9;
const FORBID = {park: 1, social_area: 1, prohibited_site: 1, water: 1, metro: 10, railway: 1};
const SPECIAL = {road: [1.5, 0], tram_tracks: [1.5, 0], gas_pipeline: [2.0, 0.2], power_cable: [2.0, 0.1], heat_network: [1.0, null], water_supply: [1.5, 0], sewer: [1.0, 0]};
const NAME = {bld: ['здание', 'зданию', 'здание'], park: ['парк', 'парку', 'парк'], social_area: ['школа, больница', 'территории школы или больницы', 'территорию школы или больницы'],
  prohibited_site: ['запретная зона', 'запретной зоне', 'запретную зону'], water: ['водоём', 'водоёму', 'водоём'], metro: ['метро', 'охранной зоне метро', 'охранную зону метро'],
  railway: ['ж/д', 'железной дороге', 'железную дорогу'], road: ['дорога', 'дороге', 'дорогу'], tram_tracks: ['трамвай', 'трамвайным путям', 'трамвайные пути'],
  gas_pipeline: ['газопровод', 'газопроводу', 'газопровод'], power_cable: ['электрокабель', 'электрокабелю', 'электрокабель'],
  heat_network: ['существующая теплосеть', 'существующей теплосети', 'существующую теплосеть'], water_supply: ['водопровод', 'водопроводу', 'водопровод'], sewer: ['канализация', 'канализации', 'канализацию']};
export const score = (cost, len) => 0.7 * cost / 25e6 + 0.3 * len / 100;
const m1 = d => (d < 1 ? Math.floor(d * 100 + 1e-6) / 100 : Math.floor(d * 10 + 1e-6) / 10).toString().replace('.', ',');

const dist = (a, b) => Math.hypot(b[0] - a[0], b[1] - a[1]);
function ptSeg(p, a, b) {
  const dx = b[0] - a[0], dy = b[1] - a[1], l2 = dx * dx + dy * dy;
  const t = l2 ? Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / l2)) : 0;
  return Math.hypot(p[0] - a[0] - t * dx, p[1] - a[1] - t * dy);
}
export const turnAngle = (p, q, r) => {
  const u = [q[0] - p[0], q[1] - p[1]], v = [r[0] - q[0], r[1] - q[1]];
  const c = (u[0] * v[0] + u[1] * v[1]) / (Math.hypot(...u) * Math.hypot(...v) || 1);
  return Math.acos(Math.max(-1, Math.min(1, c))) * 180 / Math.PI;
};

export function closestSeg(a, b, c, d) {
  const x = segX(a, b, c, d);
  if (x) return {d: 0, p: [x[1], x[2]], q: [x[1], x[2]]};
  const proj = (p, s, e) => { const dx = e[0] - s[0], dy = e[1] - s[1], l2 = dx * dx + dy * dy, t = l2 ? Math.max(0, Math.min(1, ((p[0] - s[0]) * dx + (p[1] - s[1]) * dy) / l2)) : 0; return [s[0] + t * dx, s[1] + t * dy]; };
  let best = null;
  for (const [p, q] of [[a, proj(a, c, d)], [b, proj(b, c, d)], [proj(c, a, b), c], [proj(d, a, b), d]]) {
    const dd = Math.hypot(p[0] - q[0], p[1] - q[1]);
    if (!best || dd < best.d) best = {d: dd, p, q};
  }
  return best;
}
const centroid = r => [r.reduce((t, q) => t + q[0], 0) / r.length, r.reduce((t, q) => t + q[1], 0) / r.length];
export function sameBuilding(z, item) {
  if (!item || !z || !BUILDING_KEYS.has(z.type)) return false;
  if (z.item === item) return true;
  const r = item.rings?.[0]; if (!r) return false;
  const a = centroid(z.ring), b = centroid(r);
  return inRing(a[0], a[1], r) || inRing(b[0], b[1], z.ring);
}

export function objectsNear(scene, a, b, dn, {own = null, tied = null, R = 15} = {}) {
  const out = [], w2 = dnRow(dn).w / 2;
  if (dist(a, b) < 0.01) return out;
  for (const z of scene.zonesNear(a, b, R)) {
    const bld = BUILDING_KEYS.has(z.type), mine = bld && own != null && sameBuilding(z, own);
    const need = bld ? buildingGap(dn) : FORBID[z.type] ?? SPECIAL[z.type]?.[0];
    if (need == null) continue;
    const ring = z.ring;
    let c = null, ang = null;
    for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
      const r = closestSeg(a, b, ring[j], ring[i]);
      if (!c || r.d < c.d) c = r;
      if (r.d === 0 && SPECIAL[z.type]) {
        const e = [ring[i][0] - ring[j][0], ring[i][1] - ring[j][1]], u = [b[0] - a[0], b[1] - a[1]];
        const t = Math.acos(Math.min(1, Math.abs(u[0] * e[0] + u[1] * e[1]) / (Math.hypot(...u) * Math.hypot(...e) || 1))) * 180 / Math.PI;
        if (ang == null || t < ang) ang = t;
      }
    }
    if (!c) continue;
    const inside = inRing(a[0], a[1], ring) || inRing(b[0], b[1], ring);
    const axis = inside ? 0 : c.d;
    if (axis > R) continue;
    out.push({key: mine ? 'own' : bld ? 'bld' : z.type, obj: z, type: z.type, kind: bld ? 'bld' : FORBID[z.type] ? 'forbid' : 'line', mine, axis, d: Math.max(0, axis - w2), need,
      cross: axis === 0, ang, p: c.p, q: c.q});
  }
  for (const e of scene.utilsNear(a, b, R)) {
    const sp = SPECIAL[e.type]; if (!sp || (e.type === 'heat_network' && tied?.has(e.id))) continue;
    const c = closestSeg(a, b, e.a, e.b);
    if (c.d > R) continue;
    const extra = sp[1] ?? dnRow(e.dn).w / 2;
    out.push({key: e.type, obj: e, type: e.type, kind: 'line', axis: c.d, d: Math.max(0, c.d - w2 - extra), ext: w2 + extra, need: sp[0], cross: c.d === 0, ang: null, p: c.p, q: c.q});
  }
  return out;
}
function crossPoints(pts, obj) {
  const out = [];
  for (let i = 0; i + 1 < pts.length; i++) {
    if (obj.a) { const x = segX(pts[i], pts[i + 1], obj.a, obj.b); if (x) out.push([x[1], x[2]]); continue; }
    const r = obj.ring; if (!r) continue;
    for (let k = 0, j = r.length - 1; k < r.length; j = k++) { const x = segX(pts[i], pts[i + 1], r[j], r[k]); if (x) out.push([x[1], x[2]]); }
  }
  return out;
}
const nearSpecial = (o, specials, dn) => (specials || []).some(sp => crossPoints(sp, o.obj).some(x => dist(x, o.p) < o.need + (o.ext ?? dnRow(dn).w / 2) + 0.1 + 0.2));
function crossAngle(a, b, e) {
  const u = [b[0] - a[0], b[1] - a[1]], v = [e.b[0] - e.a[0], e.b[1] - e.a[1]];
  return Math.acos(Math.min(1, Math.abs(u[0] * v[0] + u[1] * v[1]) / (Math.hypot(...u) * Math.hypot(...v) || 1))) * 180 / Math.PI;
}

export function surroundings(scene, pieces, dn, {own = null, R = 15, tied = null, special = false, specials = null} = {}) {
  const best = new Map(), rank = {bad: 0, near: 1, info: 2, ok: 3};
  for (const [a, b] of pieces) for (const o of objectsNear(scene, a, b, dn, {own, tied, R})) {
    if (o.mine) continue;
    let level, note = null;
    if (o.kind !== 'line') level = o.d < o.need - 0.05 ? 'bad' : o.d < o.need + 1 ? 'near' : 'ok';
    else if (special) {
      const along = o.obj?.a ? alongZone(a, b, o.obj, o.need + o.ext) : 0, cap = 2 * (o.need + (o.ext || 0)) / Math.sin(Math.PI / 4) + 1;
      const ang = o.cross ? (o.ang ?? (o.obj?.a ? crossAngle(a, b, o.obj) : null)) : null;
      level = (ang != null && ang < 44.5) || along > cap ? 'bad' : 'info';
      note = along > cap ? `спецпереход вдоль сети ${m1(along)} м` : ang != null ? `спецпереход под углом ${Math.round(ang)}°${ang < 44.5 ? ', нужно ≥ 45°' : ' — допустимо'}` : 'спецпереход, в футляре — допустимо';
    }
    else if (o.cross) { level = 'bad'; note = o.ang != null ? `под углом ${Math.round(o.ang)}°, нужен спецпереход` : 'нужен спецпереход'; }
    else if (o.d < o.need - 0.05) { if (nearSpecial(o, specials, dn)) { level = 'info'; note = 'у спецперехода — допустимо'; } else { level = 'bad'; note = 'нужен спецпереход'; } }
    else level = o.d < o.need + 0.5 ? 'near' : 'ok';
    const it = {key: o.key, obj: o.obj, name: NAME[o.key]?.[0] || o.key, d: o.d, need: o.need, cross: o.cross, line: o.kind === 'line', p: o.p, q: o.q, level, note};
    const was = best.get(o.key);
    if (!was || rank[level] < rank[was.level] || (rank[level] === rank[was.level] && o.d < was.d)) best.set(o.key, it);
  }
  return [...best.values()].sort((x, y) => rank[x.level] - rank[y.level] || x.d - y.d).slice(0, 6);
}

function alongZone(a, b, e, zone) {
  const L = dist(a, b), n = Math.max(1, Math.ceil(L / 0.25));
  let k = 0;
  for (let i = 0; i <= n; i++) { const t = i / n, q = [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t]; if (ptSeg(q, e.a, e.b) < zone) k++; }
  return L * k / (n + 1);
}

export function relen(s) {
  s.lens = [0];
  for (let i = 1; i < s.pts.length; i++) s.lens.push(s.lens[i - 1] + dist(s.pts[i - 1], s.pts[i]));
  s.len = s.lens[s.lens.length - 1] || 1e-9;
  s.depth = s.lens.map(t => s.d0 + (s.d1 - s.d0) * t / s.len);
}

export function planIssues(scene, s, own = null, {lines = false, tied = null, specials = null} = {}) {
  const out = [], dn = s.p.diameter || 50, seen = new Set();
  const add = (level, text, p, o) => { if (seen.has(text)) return; seen.add(text); out.push({level, text, x: p[0], y: p[1], seg: s, obj: o?.obj, d: o?.d}); };
  for (let i = 0; i + 1 < s.pts.length; i++) {
    const a = s.pts[i], b = s.pts[i + 1];
    for (const o of objectsNear(scene, a, b, dn, {own, tied, R: 12})) {
      const [name, to, thr] = NAME[o.key === 'own' ? 'bld' : o.key] || [o.key, o.key, o.key];
      if (o.mine) {
        const last = i === s.pts.length - 2, L = dist(a, b);
        const end = last ? (L > 0.6 ? [b[0] + (a[0] - b[0]) * 0.5 / L, b[1] + (a[1] - b[1]) * 0.5 / L] : null) : b;
        if (end && objectsNear(scene, a, end, dn, {R: 0.5}).some(q => q.obj === o.obj && q.cross)) add('bad', last ? 'заходит в своё здание не у ввода' : 'проходит через своё здание до ввода', o.p, o);
        continue;
      }
      if (o.kind !== 'line') {
        if (o.d < o.need - 0.05) add('bad', o.cross ? `проходит через ${thr}` : o.d < 0.01 ? `задевает ${thr}: 0 м от стенки канала, нужно ≥ ${o.need} м` : `ближе ${o.need} м к ${to}: ${m1(o.d)} м`, o.p, o);
        continue;
      }
      if (o.cross && o.ang != null && o.ang < 45 - 0.5) add('bad', `пересекает ${thr} под углом ${Math.round(o.ang)}°, нужно не меньше 45°`, o.p, o);
      let sharp = false;
      if (o.cross && o.obj?.a && s.special && lines) { const ang = crossAngle(a, b, o.obj); if (ang < 45 - 0.5) { sharp = true; add('bad', `пересекает ${thr} под углом ${Math.round(ang)}°, нужно не меньше 45°`, o.p, o); } }
      if (s.special && lines && o.obj?.a && !sharp) {
        const zone = o.need + o.ext, inside = alongZone(a, b, o.obj, zone), cap = 2 * zone / Math.sin(Math.PI / 4) + 1;
        if (inside > cap) add('bad', `спецпереход идёт ${m1(inside)} м в зоне сети (${name}): пересекать её нужно поперёк, не острее 45°`, o.p, o);
      }
      if (s.special || !lines) continue;
      if (o.cross) add('bad', `пересекает ${thr} обычной прокладкой: уведите трассу или пересеките одним прямым звеном под 45–90° — футляр спецперехода ставит пересчёт на сервере`, o.p, o);
      else if (o.d < o.need - 0.05 && !nearSpecial(o, specials, dn)) add('bad', `ближе ${String(o.need).replace('.', ',')} м к ${to} вне спецперехода: ${m1(o.d)} м`, o.p, o);
    }
  }
  for (let i = 1; i + 1 < s.pts.length; i++) {
    const t = turnAngle(s.pts[i - 1], s.pts[i], s.pts[i + 1]);
    if (t > 90.5) add('bad', `угол поворота ${Math.round(t)}° — больше 90°`, s.pts[i]);
  }
  return out;
}

export function jointIssue(a, b, where = 'узле') {
  const p = a.pts[a.pts.length - 2], q = a.pts[a.pts.length - 1], r = b.pts[1];
  if (!p || !r || dist(p, q) < 0.01 || dist(q, r) < 0.01) return null;
  const t = turnAngle(p, q, r);
  return t > 90.5 ? {level: 'bad', text: `угол поворота ${Math.round(t)}° в ${where} — больше 90°`, x: q[0], y: q[1], seg: b} : null;
}

export function extraBends(scene, s, own = null, keep = [], opts = {}) {
  const out = [];
  for (let i = 1; i + 1 < s.pts.length; i++) {
    const q = s.pts[i];
    if (keep.some(k => dist(k, q) < 1e-3)) continue;
    const t = turnAngle(s.pts[i - 1], q, s.pts[i + 1]);
    if (t > 90.5) continue;
    if (t < 1) { out.push({level: 'warn', text: 'точка без поворота лишняя: сдвиньте её или удалите', x: q[0], y: q[1], seg: s, i}); continue; }
    if (planIssues(scene, {pts: [s.pts[i - 1], s.pts[i + 1]], p: s.p, special: s.special}, own, {lines: true, ...opts}).length) continue;
    out.push({level: 'warn', text: `поворот ${Math.round(t)}° лишний: напрямую между соседними точками нарушений нет — уберите его`, x: q[0], y: q[1], seg: s, i});
  }
  return out;
}

export function snapOrtho(p, neighbours, tol) {
  const lines = [];
  neighbours.forEach(({at, dir}, k) => {
    if (!at || !dir) return;
    const l = Math.hypot(dir[0], dir[1]);
    if (l < 1e-6) return;
    const u = [dir[0] / l, dir[1] / l];
    lines.push({at, u, k}, {at, u: [-u[1], u[0]], k});
  });
  const foot = L => { const t = (p[0] - L.at[0]) * L.u[0] + (p[1] - L.at[1]) * L.u[1]; return [L.at[0] + L.u[0] * t, L.at[1] + L.u[1] * t]; };
  const cand = lines.map(L => { const q = foot(L); return {L, q, d: Math.hypot(p[0] - q[0], p[1] - q[1])}; }).filter(c => c.d < tol).sort((a, b) => a.d - b.d);
  if (!cand.length) return {p, guide: []};
  const first = cand[0];
  let q = first.q;
  const second = cand.find(c => c.L.k !== first.L.k && Math.abs(c.L.u[0] * first.L.u[1] - c.L.u[1] * first.L.u[0]) > 0.5);
  if (second) {
    const A = first.L, B = second.L, den = A.u[0] * B.u[1] - A.u[1] * B.u[0];
    const t = ((B.at[0] - A.at[0]) * B.u[1] - (B.at[1] - A.at[1]) * B.u[0]) / den;
    q = [A.at[0] + A.u[0] * t, A.at[1] + A.u[1] * t];
  }
  if (neighbours.some(n => n.at && Math.hypot(q[0] - n.at[0], q[1] - n.at[1]) < 0.5)) return {p, guide: []};
  return {p: q, guide: [first, second].filter(Boolean).map(c => [c.L.at, q])};
}

export function patchOutput(raw, scene, variant, edited, proj) {
  const fc = JSON.parse(raw), segById = new Map(variant.segs.map(s => [s.id, s])), nodeById = new Map(variant.nodes.map(n => [n.id, n]));
  let dCost = 0, dLen = 0;
  const ll = q => proj.ll(q[0], q[1]).map(v => +v.toFixed(9));
  for (const f of fc.features) {
    const p = f.properties || {};
    if (String(p.variant_id ?? '1') !== String(variant.id)) continue;
    if (p.object_type === 'heat_network' && edited.segs.has(String(p.id))) {
      const s = segById.get(String(p.id)), old = f.geometry.coordinates, n = s.pts.length;
      const L0 = old.slice(1).reduce((t, c, i) => t + dist(proj.xy(old[i]), proj.xy(c)), 0), len = s.len * (L0 > 0.01 && p.length ? p.length / L0 : 1);
      const keep = i => (i === 0 && !edited.nodes.has(String(p.start_node_id))) || (i === n - 1 && !edited.nodes.has(String(p.end_node_id)));
      f.geometry = {type: 'LineString', coordinates: s.pts.map((q, i) => keep(i) ? old[i === 0 ? 0 : old.length - 1] : ll(q))};
      dCost += s.p.cost - p.cost; dLen += len - (p.length ?? 0);
      p.length = +len.toFixed(2); p.cost = +s.p.cost.toFixed(2); p.manual_edit = true;
      const iss = edited.issues?.get(String(p.id));
      if (iss?.length) p.manual_issues = iss; else delete p.manual_issues;
    } else if (f.geometry?.type === 'Point' && edited.nodes.has(String(p.id))) {
      const n = nodeById.get(String(p.id));
      f.geometry = {type: 'Point', coordinates: ll(n.xy)};
      p.manual_edit = true;
    }
  }
  const sm = fc.features.find(f => f.properties?.object_type === 'variant_summary' && String(f.properties.variant_id) === String(variant.id))?.properties;
  if (sm) {
    sm.construction_cost = +(sm.construction_cost + dCost).toFixed(2);
    sm.calculated_cost = +(sm.calculated_cost + dCost).toFixed(2);
    sm.new_network_length = +(sm.new_network_length + dLen).toFixed(2);
    sm.score = +score(sm.calculated_cost, sm.new_network_length).toFixed(4);
    sm.manual_edits = edited.segs.size;
    sm.manual_issues = edited.issues ? [...edited.issues.values()].reduce((t, l) => t + l.length, 0) : 0;
  }
  return JSON.stringify(fc);
}

export function utm(lon, lat, zone = 37) {
  const a = 6378137, f = 1 / 298.257223563, k0 = 0.9996, e2 = f * (2 - f), ep2 = e2 / (1 - e2), D = Math.PI / 180;
  const phi = lat * D, lam = lon * D - (zone * 6 - 183) * D, s = Math.sin(phi), c = Math.cos(phi), t = Math.tan(phi);
  const N = a / Math.sqrt(1 - e2 * s * s), T = t * t, C = ep2 * c * c, A = c * lam;
  const M = a * ((1 - e2 / 4 - 3 * e2 ** 2 / 64 - 5 * e2 ** 3 / 256) * phi - (3 * e2 / 8 + 3 * e2 ** 2 / 32 + 45 * e2 ** 3 / 1024) * Math.sin(2 * phi)
    + (15 * e2 ** 2 / 256 + 45 * e2 ** 3 / 1024) * Math.sin(4 * phi) - (35 * e2 ** 3 / 3072) * Math.sin(6 * phi));
  const x = k0 * N * (A + (1 - T + C) * A ** 3 / 6 + (5 - 18 * T + T * T + 72 * C - 58 * ep2) * A ** 5 / 120) + 500000;
  const y = k0 * (M + N * t * (A * A / 2 + (5 - T + 9 * C + 4 * C * C) * A ** 4 / 24 + (61 - 58 * T + T * T + 600 * C - 330 * ep2) * A ** 6 / 720));
  return [x, y];
}

export const utmZone = lon => Math.floor((lon + 180) / 6) + 1;
export function toDxf(variant, proj, {zone = utmZone(proj.lon0), scene = null, issues = []} = {}) {
  const out = ['0', 'SECTION', '2', 'HEADER', '9', '$ACADVER', '1', 'AC1009', '9', '$DWGCODEPAGE', '3', 'ANSI_1251', '9', '$INSUNITS', '70', '6', '0', 'ENDSEC', '0', 'SECTION', '2', 'ENTITIES'];
  const P = q => utm(...proj.ll(q[0], q[1]), zone);
  const n3 = v => v.toFixed(3);
  for (const s of variant.segs) {
    const layer = s.edited ? 'HEAT_EDITED' : s.special ? 'HEAT_SPECIAL' : 'HEAT_NEW';
    out.push('0', 'POLYLINE', '8', layer, '66', '1', '10', '0.0', '20', '0.0', '30', '0.0', '70', '8');
    s.pts.forEach((q, i) => { const [x, y] = P(q); out.push('0', 'VERTEX', '8', layer, '10', n3(x), '20', n3(y), '30', n3(-(s.depth?.[i] ?? 0)), '70', '32'); });
    out.push('0', 'SEQEND', '8', layer);
    const m = s.pts[Math.floor((s.pts.length - 1) / 2)], m2 = s.pts[Math.floor((s.pts.length - 1) / 2) + 1] || m, [x, y] = P([(m[0] + m2[0]) / 2, (m[1] + m2[1]) / 2]);
    const id = (/_seg_(\d+)$/.exec(s.id) || [, s.id])[1];
    out.push('0', 'TEXT', '8', 'LABELS', '10', n3(x), '20', n3(y), '30', '0.0', '40', '0.5', '1', `S${id} DN${s.p.diameter} L=${(s.p.length ?? s.len).toFixed(2)}${s.edited ? ' EDIT' : ''}`);
  }
  if (scene) {
    const seen = new Set(), add = (layer, pts) => { out.push('0', 'POLYLINE', '8', layer, '66', '1', '70', '0'); for (const q of pts) { const [x, y] = P(q); out.push('0', 'VERTEX', '8', layer, '10', n3(x), '20', n3(y), '30', '0.0'); } out.push('0', 'SEQEND', '8', layer); };
    for (const s of variant.segs) for (let i = 0; i + 1 < s.pts.length; i++) {
      for (const e of scene.utilsNear(s.pts[i], s.pts[i + 1], 30)) if (!seen.has(e)) { seen.add(e); add('EXIST_' + e.type.toUpperCase(), [e.a, e.b]); }
      for (const z of scene.zonesNear(s.pts[i], s.pts[i + 1], 30)) if (!seen.has(z) && BUILDING_KEYS.has(z.type)) { seen.add(z); add('BUILDINGS', [...z.ring, z.ring[0]]); }
    }
  }
  for (const n of variant.nodes) {
    if (n.type === 'technical_node' || n.type === 'oks_entry' || n.type === 'endpoint') continue;
    const [x, y] = P(n.xy);
    out.push('0', 'CIRCLE', '8', 'CHAMBERS', '10', n3(x), '20', n3(y), '30', '0.0', '40', '1.0');
    const m = /_ch_(\d+)$/.exec(n.id);
    out.push('0', 'TEXT', '8', 'CHAMBERS', '10', n3(x + 1.2), '20', n3(y + 1.2), '30', '0.0', '40', '0.7', '1', m ? `K${m[1]}` : String(n.id).replace(/[^\x20-\x7e]/g, '_'));
  }
  for (const i of issues) {
    const [x, y] = P([i.x, i.y]);
    out.push('0', 'CIRCLE', '8', 'ISSUES', '62', '1', '10', n3(x), '20', n3(y), '30', '0.0', '40', '1.5');
    out.push('0', 'TEXT', '8', 'ISSUES', '62', '1', '10', n3(x + 1.8), '20', n3(y), '30', '0.0', '40', '0.6', '1', i.text.replace(/≥/g, '>=').replace(/[\r\n]/g, ' '));
  }
  out.push('0', 'ENDSEC', '0', 'EOF');
  return out.join('\r\n') + '\r\n';
}

export function cp1251(str) {
  const extra = {'Ё': 0xA8, 'ё': 0xB8, '№': 0xB9, '°': 0xB0, '«': 0xAB, '»': 0xBB, '—': 0x97, '–': 0x96, '·': 0xB7, '×': 0xD7};
  const out = new Uint8Array(str.length);
  for (let i = 0; i < str.length; i++) {
    const c = str[i], k = c.charCodeAt(0);
    out[i] = k < 0x80 ? k : k >= 0x410 && k <= 0x44F ? k - 0x350 : extra[c] ?? 0x3F;
  }
  return out;
}
