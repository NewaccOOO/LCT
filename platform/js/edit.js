// Ручная правка трассы: геометрия участков меняется в браузере, длина, стоимость и S пересчитываются сразу,
// правила плана (зоны запрета и отступы, повороты, переход дороги и сетей) и пересечения по высоте проверяются на лету.
// Источник истины — по-прежнему сервис и check18: выход с правками скачивается в его же формате.
// Без three.js, чтобы проверялось в node.
import {segX, inRing, BUILDING_KEYS, dnRow} from './model.js';

// Отступы, м, — от стенки канала, как в check18: от оси трубы это отступ плюс полширины канала по Ду (табл. 1),
// у линий — ещё полширины самой линии (rules/rules.json, раздел restrictions: clearance_m, half_width_m).
// Здания — по Ду; зоны запрета — не заходить ближе; дороги и сети — ближе отступа только спецпереходом.
export const buildingGap = dn => dn <= 400 ? 5 : dn <= 800 ? 7 : 9;
const FORBID = {park: 1, social_area: 1, prohibited_site: 1, water: 1, metro: 10, railway: 1};
const SPECIAL = {road: [1.5, 0], tram_tracks: [1.5, 0], gas_pipeline: [2.0, 0.2], power_cable: [2.0, 0.1], heat_network: [1.0, null], water_supply: [1.5, 0], sewer: [1.0, 0]};
// названия: кто/что, к чему, через что
const NAME = {bld: ['здание', 'зданию', 'здание'], park: ['парк', 'парку', 'парк'], social_area: ['школа, больница', 'территории школы или больницы', 'территорию школы или больницы'],
  prohibited_site: ['запретная зона', 'запретной зоне', 'запретную зону'], water: ['водоём', 'водоёму', 'водоём'], metro: ['метро', 'охранной зоне метро', 'охранную зону метро'],
  railway: ['ж/д', 'железной дороге', 'железную дорогу'], road: ['дорога', 'дороге', 'дорогу'], tram_tracks: ['трамвай', 'трамвайным путям', 'трамвайные пути'],
  gas_pipeline: ['газопровод', 'газопроводу', 'газопровод'], power_cable: ['электрокабель', 'электрокабелю', 'электрокабель'],
  heat_network: ['существующая теплосеть', 'существующей теплосети', 'существующую теплосеть'], water_supply: ['водопровод', 'водопроводу', 'водопровод'], sewer: ['канализация', 'канализации', 'канализацию']};
// S по п. 7.2 ТЗ (rules.json, раздел score)
export const score = (cost, len) => 0.7 * cost / 25e6 + 0.3 * len / 100;
const m1 = d => (d < 1 ? Math.floor(d * 100 + 1e-6) / 100 : Math.floor(d * 10 + 1e-6) / 10).toString().replace('.', ',');

const dist = (a, b) => Math.hypot(b[0] - a[0], b[1] - a[1]);
function ptSeg(p, a, b) {
  const dx = b[0] - a[0], dy = b[1] - a[1], l2 = dx * dx + dy * dy;
  const t = l2 ? Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / l2)) : 0;
  return Math.hypot(p[0] - a[0] - t * dx, p[1] - a[1] - t * dy);
}
function segSeg(a, b, c, d) {
  if (segX(a, b, c, d)) return 0;
  return Math.min(ptSeg(a, c, d), ptSeg(b, c, d), ptSeg(c, a, b), ptSeg(d, a, b));
}
/** Расстояние от отрезка до полигона (0 — заходит внутрь) и ближайшая к нарушению точка. */
function segRing(a, b, ring) {
  if (inRing(a[0], a[1], ring)) return {d: 0, at: a};
  if (inRing(b[0], b[1], ring)) return {d: 0, at: b};
  let best = {d: Infinity, at: a};
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const x = segX(a, b, ring[j], ring[i]);
    if (x) return {d: 0, at: [x[1], x[2]]};
    const d = segSeg(a, b, ring[j], ring[i]);
    if (d < best.d) best = {d, at: ptSeg(a, ring[j], ring[i]) < ptSeg(b, ring[j], ring[i]) ? a : b};
  }
  return best;
}
/** Угол поворота трассы в вершине q, градусы: 0 — прямо, 90 — под прямым углом. */
export const turnAngle = (p, q, r) => {
  const u = [q[0] - p[0], q[1] - p[1]], v = [r[0] - q[0], r[1] - q[1]];
  const c = (u[0] * v[0] + u[1] * v[1]) / (Math.hypot(...u) * Math.hypot(...v) || 1);
  return Math.acos(Math.max(-1, Math.min(1, c))) * 180 / Math.PI;
};

/** Ближайшие точки двух отрезков: {d, p (на ab), q (на cd)}. */
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
/**
 * Объекты у звена ab в радиусе R: [{key, obj, type, kind: 'bld'|'forbid'|'line', d (от стенки канала, ≥ 0), axis (от оси),
 * need (норма), cross (звено пересекает объект), ang (угол перехода дороги к её краю), p (на трубе), q (на объекте)}].
 * own — своё здание (в него участок входит), tied — id существующих труб, в которые участок врезается.
 */
export function objectsNear(scene, a, b, dn, {own = null, tied = null, R = 15} = {}) {
  const out = [], w2 = dnRow(dn).w / 2;
  if (dist(a, b) < 0.01) return out;
  for (const z of scene.zonesNear(a, b, R)) {
    const bld = BUILDING_KEYS.has(z.type);
    if (bld && z.item === own) continue;
    const need = bld ? buildingGap(dn) : FORBID[z.type] ?? SPECIAL[z.type]?.[0];
    if (need == null) continue;
    const ring = z.ring;
    let c = null, ang = null;
    for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
      const r = closestSeg(a, b, ring[j], ring[i]);
      if (!c || r.d < c.d) c = r;
      if (r.d === 0 && SPECIAL[z.type]) {
        // угол перехода — между звеном и краем дороги, острый
        const e = [ring[i][0] - ring[j][0], ring[i][1] - ring[j][1]], u = [b[0] - a[0], b[1] - a[1]];
        const t = Math.acos(Math.min(1, Math.abs(u[0] * e[0] + u[1] * e[1]) / (Math.hypot(...u) * Math.hypot(...e) || 1))) * 180 / Math.PI;
        if (ang == null || t < ang) ang = t;
      }
    }
    if (!c) continue;
    const inside = inRing(a[0], a[1], ring) || inRing(b[0], b[1], ring);
    const axis = inside ? 0 : c.d;
    if (axis > R) continue;
    out.push({key: bld ? 'bld' : z.type, obj: z, type: z.type, kind: bld ? 'bld' : FORBID[z.type] ? 'forbid' : 'line', axis, d: Math.max(0, axis - w2), need,
      cross: axis === 0, ang, p: c.p, q: c.q});
  }
  for (const e of scene.utilsNear(a, b, R)) {
    const sp = SPECIAL[e.type]; if (!sp || (e.type === 'heat_network' && tied?.has(e.id))) continue;
    const c = closestSeg(a, b, e.a, e.b);
    if (c.d > R) continue;
    const extra = sp[1] ?? dnRow(e.dn).w / 2;
    out.push({key: e.type, obj: e, type: e.type, kind: 'line', axis: c.d, d: Math.max(0, c.d - w2 - extra), need: sp[0], cross: c.d === 0, ang: null, p: c.p, q: c.q});
  }
  return out;
}
// часть трассы у спецперехода может подойти к его объекту ближе нормы — в круге «норма + 0,1 м» у его конца (check18, B10)
const nearSpecial = (o, ends, dn) => ends?.some(e => dist(e, o.p) < o.need + dnRow(dn).w / 2 + 0.1 + 0.5);

/**
 * Размеры вокруг правки, как в CAD: от звеньев pieces ([[a, b], …]) до ближайших объектов в радиусе R —
 * [{key, name, d, need, level: 'ok'|'near'|'bad'|'info', p (на трубе), q (на объекте), note}]. Самое близкое из каждого вида.
 * d и need — от стенки канала. special — участок уже спецпереход; ends — его концы у спецпереходов (см. nearSpecial).
 */
export function surroundings(scene, pieces, dn, {own = null, R = 15, tied = null, special = false, ends = null} = {}) {
  const best = new Map(), rank = {bad: 0, near: 1, info: 2, ok: 3};
  for (const [a, b] of pieces) for (const o of objectsNear(scene, a, b, dn, {own, tied, R})) {
    let level, note = null;
    if (o.kind !== 'line') level = o.d < o.need - 0.05 ? 'bad' : o.d < o.need + 1 ? 'near' : 'ok';
    else if (special) { level = 'info'; note = 'спецпереход'; }
    else if (o.cross) { level = 'bad'; note = 'нужен спецпереход'; }
    else if (o.d < o.need - 0.05) { if (nearSpecial(o, ends, dn)) { level = 'info'; note = 'у спецперехода'; } else { level = 'bad'; note = 'нужен спецпереход'; } }
    else level = o.d < o.need + 0.5 ? 'near' : 'ok';
    const it = {key: o.key, name: NAME[o.key]?.[0] || o.key, d: o.d, need: o.need, cross: o.cross, line: o.kind === 'line', p: o.p, q: o.q, level, note};
    const was = best.get(o.key);
    if (!was || rank[level] < rank[was.level] || (rank[level] === rank[was.level] && o.d < was.d)) best.set(o.key, it);
  }
  return [...best.values()].sort((x, y) => rank[x.level] - rank[y.level] || x.d - y.d).slice(0, 6);
}

/** Длины по вершинам и глубина: концы участка держат свою глубину, между ними — линейно. */
export function relen(s) {
  s.lens = [0];
  for (let i = 1; i < s.pts.length; i++) s.lens.push(s.lens[i - 1] + dist(s.pts[i - 1], s.pts[i]));
  s.len = s.lens[s.lens.length - 1] || 1e-9;
  s.depth = s.lens.map(t => s.d0 + (s.d1 - s.d0) * t / s.len);
}

/**
 * Проблемы плана у участка s: [{level: 'bad'|'warn', text, x, y, seg, obj, d}].
 * own — здание, в которое входит участок; lines — проверять отступы от дорог и сетей вне спецперехода (у участков,
 * которые правил человек: исходную трассу сервис уже проложил спецпереходами); tied, ends — как у surroundings.
 */
export function planIssues(scene, s, own = null, {lines = false, tied = null, ends = null} = {}) {
  const out = [], dn = s.p.diameter || 50, seen = new Set();
  const add = (level, text, p, o) => { if (seen.has(text)) return; seen.add(text); out.push({level, text, x: p[0], y: p[1], seg: s, obj: o?.obj, d: o?.d}); };
  for (let i = 0; i + 1 < s.pts.length; i++) {
    const a = s.pts[i], b = s.pts[i + 1];
    for (const o of objectsNear(scene, a, b, dn, {own, tied, R: 12})) {
      const [name, to, thr] = NAME[o.key] || [o.key, o.key, o.key];
      if (o.kind !== 'line') {
        if (o.d < o.need - 0.05) add('bad', o.cross ? `проходит через ${thr}` : `ближе ${o.need} м к ${to}: ${m1(o.d)} м`, o.p, o);
        continue;
      }
      // переход дороги — одним прямым участком под углом не меньше 45° к её краю
      if (o.cross && o.ang != null && o.ang < 45 - 0.5) add('bad', `пересекает ${thr} под углом ${Math.round(o.ang)}°, нужно не меньше 45°`, o.p, o);
      if (s.special || !lines) continue;
      if (o.cross) add('bad', `пересекает ${thr} обычной прокладкой: нужен спецпереход, его ставит пересчёт на сервере`, o.p, o);
      else if (o.d < o.need - 0.05 && !nearSpecial(o, ends, dn)) add('bad', `ближе ${String(o.need).replace('.', ',')} м к ${to} вне спецперехода: ${m1(o.d)} м`, o.p, o);
    }
  }
  for (let i = 1; i + 1 < s.pts.length; i++) {
    const t = turnAngle(s.pts[i - 1], s.pts[i], s.pts[i + 1]);
    if (t > 90.5) add('bad', `поворот ${Math.round(t)}° — круче 90°`, s.pts[i]);
  }
  return out;
}

/** Поворот на пути точки через узел или камеру (участок a входит, b выходит): не круче 90°. */
export function jointIssue(a, b, where = 'узле') {
  const p = a.pts[a.pts.length - 2], q = a.pts[a.pts.length - 1], r = b.pts[1];
  if (!p || !r || dist(p, q) < 0.01 || dist(q, r) < 0.01) return null;
  const t = turnAngle(p, q, r);
  return t > 90.5 ? {level: 'bad', text: `поворот ${Math.round(t)}° в ${where} — круче 90°`, x: q[0], y: q[1], seg: b} : null;
}

/**
 * Лишние повороты участка: вершины not в списке keep, без которых прямая между соседями проходит без нарушений плана.
 * Такой поворот только удлиняет трассу — правила формы трассы его не допускают.
 */
export function extraBends(scene, s, own = null, keep = [], opts = {}) {
  const out = [];
  for (let i = 1; i + 1 < s.pts.length; i++) {
    const q = s.pts[i];
    if (keep.some(k => dist(k, q) < 1e-3)) continue;
    const t = turnAngle(s.pts[i - 1], q, s.pts[i + 1]);
    if (t < 1) continue;
    if (planIssues(scene, {pts: [s.pts[i - 1], s.pts[i + 1]], p: s.p, special: s.special}, own, {lines: true, ...opts}).length) continue;
    out.push({level: 'warn', text: `поворот ${Math.round(t)}° лишний: напрямую между соседними точками нарушений нет — уберите его`, x: q[0], y: q[1], seg: s, i});
  }
  return out;
}

/**
 * Привязка к прямым углам, как «Орто» в AutoCAD: точка встаёт на ближайшую прямую, идущую от соседней вершины
 * вдоль или поперёк соседнего звена, если до неё меньше tol метров; если рядом прямая и от другого соседа —
 * в их пересечение (прямой угол с обеих сторон). На соседа точка не схлопывается. Возвращает точку и подсказки.
 */
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

/**
 * Выход сервиса с правками: геометрия и длина изменённых участков, их стоимость пропорционально длине,
 * координаты узлов и сводка варианта (стоимость, длина сети, S). Изменённое помечено manual_edit.
 */
export function patchOutput(raw, scene, variant, edited, proj) {
  const fc = JSON.parse(raw), segById = new Map(variant.segs.map(s => [s.id, s])), nodeById = new Map(variant.nodes.map(n => [n.id, n]));
  let dCost = 0, dLen = 0;
  const ll = q => proj.ll(q[0], q[1]).map(v => +v.toFixed(9));
  for (const f of fc.features) {
    const p = f.properties || {};
    if (String(p.variant_id ?? '1') !== String(variant.id)) continue;
    if (p.object_type === 'heat_network' && edited.segs.has(String(p.id))) {
      const s = segById.get(String(p.id)), old = f.geometry.coordinates, n = s.pts.length;
      // длина — в метрах сервиса (его проекция): масштаб берём у исходной геометрии участка
      const L0 = old.slice(1).reduce((t, c, i) => t + dist(proj.xy(old[i]), proj.xy(c)), 0), len = s.len * (L0 > 0.01 && p.length ? p.length / L0 : 1);
      // концы на неподвинутых узлах — точно как в исходном файле, иначе конец разойдётся с узлом на округлении
      const keep = i => (i === 0 && !edited.nodes.has(String(p.start_node_id))) || (i === n - 1 && !edited.nodes.has(String(p.end_node_id)));
      f.geometry = {type: 'LineString', coordinates: s.pts.map((q, i) => keep(i) ? old[i === 0 ? 0 : old.length - 1] : ll(q))};
      dCost += s.p.cost - p.cost; dLen += len - (p.length ?? 0);
      p.length = +len.toFixed(2); p.cost = +s.p.cost.toFixed(2); p.manual_edit = true;
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
  }
  return JSON.stringify(fc);
}
