// Ручная правка трассы: геометрия участков меняется в браузере, длина, стоимость и S пересчитываются сразу,
// правила плана (зоны запрета и отступы, повороты, переход дороги) и пересечения по высоте проверяются на лету.
// Источник истины — по-прежнему сервис и check18: выход с правками скачивается в его же формате.
// Без three.js, чтобы проверялось в node.
import {segX, inRing, BUILDING_KEYS} from './model.js';

// Отступы от зон запрета, м (rules/rules.json, раздел restrictions); у зданий — по Ду
const FORBID = {park: 1, social_area: 1, prohibited_site: 1, water: 1, metro: 10, railway: 1};
const ZONE_NAME = {park: 'парк', social_area: 'территорию школы или больницы', prohibited_site: 'запретную зону', water: 'водоём',
  metro: 'охранную зону метро', railway: 'железную дорогу', oks_existing: 'здание', oks_future: 'здание', oks: 'здание'};
const NEAR_NAME = {park: 'парку', social_area: 'территории школы или больницы', prohibited_site: 'запретной зоне', water: 'водоёму',
  metro: 'охранной зоне метро', railway: 'железной дороге'};
const ROAD = {road: 'дорогу', tram_tracks: 'трамвайные пути'};
export const buildingGap = dn => dn <= 400 ? 5 : dn <= 800 ? 7 : 9;
// S по п. 7.2 ТЗ (rules.json, раздел score)
export const score = (cost, len) => 0.7 * cost / 25e6 + 0.3 * len / 100;

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
const turnAngle = (p, q, r) => {
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
// существующие сети: зона, в которой новая труба кладётся как спецпереход (rules.json, restrictions … clearance_m)
const UTIL_ZONE = {gas_pipeline: [2.0, 'газопровод'], power_cable: [2.0, 'электрокабель'], heat_network: [1.0, 'существующая теплосеть'], water_supply: [1.5, 'водопровод'], sewer: [1.0, 'канализация']};
const OBJ_NAME = {park: 'парк', social_area: 'школа, больница', prohibited_site: 'запретная зона', water: 'водоём', metro: 'метро', railway: 'ж/д', road: 'дорога', tram_tracks: 'трамвай'};

/**
 * Размеры вокруг правки, как в CAD: от звеньев pieces ([[a, b], …]) до ближайших объектов в радиусе R —
 * [{name, d, need, level: 'ok'|'near'|'bad'|'info', p (на трубе), q (на объекте), note}]. Самое близкое из каждого вида.
 */
export function surroundings(scene, pieces, dn, own = null, R = 15) {
  const best = new Map();
  const keep = (key, item) => { const o = best.get(key); if (!o || item.d < o.d) best.set(key, item); };
  const lvl = (d, need) => need == null ? 'info' : d < need - 0.05 ? 'bad' : d < need + 1 ? 'near' : 'ok';
  for (const [a, b] of pieces) {
    if (Math.hypot(b[0] - a[0], b[1] - a[1]) < 0.01) continue;
    for (const z of scene.zonesNear(a, b, R)) {
      const bld = BUILDING_KEYS.has(z.type);
      if (bld && z.item === own) continue;
      if (!bld && !FORBID[z.type] && !ROAD[z.type]) continue;
      let c = null;
      const ring = z.ring;
      for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) { const r = closestSeg(a, b, ring[j], ring[i]); if (!c || r.d < c.d) c = r; }
      if (!c) continue;
      if (inRing(a[0], a[1], ring) || inRing(b[0], b[1], ring)) c = {d: 0, p: c.p, q: c.q};
      if (c.d > R) continue;
      const need = bld ? buildingGap(dn) : FORBID[z.type] ?? null;
      const road = !!ROAD[z.type];
      keep(bld ? 'здание' : z.type, {name: bld ? 'здание' : OBJ_NAME[z.type], d: c.d, need, p: c.p, q: c.q,
        level: road ? 'info' : lvl(c.d, need), note: road ? (c.d === 0 ? 'переход — спецпереход под углом ≥ 45°' : null) : null});
    }
    for (const e of scene.utilsNear(a, b, R)) {
      const u = UTIL_ZONE[e.type]; if (!u) continue;
      const c = closestSeg(a, b, e.a, e.b);
      if (c.d > R) continue;
      keep('u:' + e.type, {name: u[1], d: c.d, need: null, zone: u[0], p: c.p, q: c.q,
        level: c.d === 0 ? 'info' : c.d < u[0] ? 'near' : 'info', note: c.d === 0 ? 'пересечение — проверьте зазор по высоте' : c.d < u[0] ? `в зоне ${String(u[0]).replace('.', ',')} м — прокладка как спецпереход` : null});
    }
  }
  const rank = {bad: 0, near: 1, info: 2, ok: 3};
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
 * Проблемы плана у участка s: [{level: 'bad'|'warn', text, x, y, seg}].
 * own — здание, в которое входит участок: финальный участок заходит в свой контур.
 */
export function planIssues(scene, s, own = null) {
  const out = [], dn = s.p.diameter || 50, seen = new Set();
  const add = (level, text, p) => { if (seen.has(text)) return; seen.add(text); out.push({level, text, x: p[0], y: p[1], seg: s}); };
  for (let i = 0; i + 1 < s.pts.length; i++) {
    const a = s.pts[i], b = s.pts[i + 1];
    if (dist(a, b) < 0.01) continue;
    for (const z of scene.zonesNear(a, b, 12)) {
      const bld = BUILDING_KEYS.has(z.type);
      if (bld && z.item === own) continue;
      const need = bld ? buildingGap(dn) : FORBID[z.type];
      if (need != null) {
        const r = segRing(a, b, z.ring);
        if (r.d < need - 0.05) add('bad', r.d === 0 ? `проходит через ${ZONE_NAME[z.type]}` : `ближе ${need} м к ${bld ? 'зданию' : NEAR_NAME[z.type]}: ${r.d.toFixed(1).replace('.', ',')} м`, r.at);
      }
      if (ROAD[z.type]) {
        // переход дороги — одним прямым участком под углом не меньше 45° к её краю
        const ring = z.ring;
        for (let k = 0, j = ring.length - 1; k < ring.length; j = k++) {
          const x = segX(a, b, ring[j], ring[k]);
          if (!x) continue;
          const e = [ring[k][0] - ring[j][0], ring[k][1] - ring[j][1]], u = [b[0] - a[0], b[1] - a[1]];
          let ang = Math.acos(Math.min(1, Math.abs(u[0] * e[0] + u[1] * e[1]) / (Math.hypot(...u) * Math.hypot(...e) || 1))) * 180 / Math.PI;
          if (ang < 45 - 0.5) add('bad', `пересекает ${ROAD[z.type]} под углом ${Math.round(ang)}°, нужно не меньше 45°`, [x[1], x[2]]);
          else if (!s.special) add('warn', `пересекает ${ROAD[z.type]} обычной прокладкой: нужен спецпереход в футляре, его ставит пересчёт на сервере`, [x[1], x[2]]);
        }
      }
    }
  }
  for (let i = 1; i + 1 < s.pts.length; i++) {
    const t = turnAngle(s.pts[i - 1], s.pts[i], s.pts[i + 1]);
    if (t > 90.5) add('bad', `поворот ${Math.round(t)}° — круче 90°`, s.pts[i]);
  }
  return out;
}

/** Поворот на стыке двух участков подряд (технический узел): не круче 90°. */
export function jointIssue(a, b) {
  const p = a.pts[a.pts.length - 2], q = a.pts[a.pts.length - 1], r = b.pts[1];
  if (!p || !r || dist(p, q) < 0.01 || dist(q, r) < 0.01) return null;
  const t = turnAngle(p, q, r);
  return t > 90.5 ? {level: 'bad', text: `поворот ${Math.round(t)}° в узле — круче 90°`, x: q[0], y: q[1], seg: b} : null;
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
  for (const f of fc.features) {
    const p = f.properties || {};
    if (String(p.variant_id ?? '1') !== String(variant.id)) continue;
    if (p.object_type === 'heat_network' && edited.segs.has(String(p.id))) {
      const s = segById.get(String(p.id));
      f.geometry = {type: 'LineString', coordinates: s.pts.map(q => proj.ll(q[0], q[1]).map(v => +v.toFixed(7)))};
      dCost += s.p.cost - p.cost; dLen += s.len - (p.length ?? 0);
      p.length = +s.len.toFixed(2); p.cost = +s.p.cost.toFixed(2); p.manual_edit = true;
    } else if (f.geometry?.type === 'Point' && edited.nodes.has(String(p.id))) {
      const n = nodeById.get(String(p.id));
      f.geometry = {type: 'Point', coordinates: proj.ll(n.xy[0], n.xy[1]).map(v => +v.toFixed(7))};
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
