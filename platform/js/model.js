// Модель сцены из входа и выхода сервиса — без three.js, чтобы проверялась в node.
// Геометрия переводится в метры вокруг центра набора (x — восток, y — север). У новой сети по вершинам хранится
// глубина верха габарита d (depth_start/depth_end, между ними линейно; без них — 3,0 м). Пересечения с коммуникациями
// и проверки глубины — раздел 14 architecture/CONSTRAINTS.md, та же логика, что в scripts/ui3d/prep.py.

export const NORMAL_DEPTH = 3.0;
export const MIN_DEPTH = 0.7;
export const MAX_SLOPE = 0.10;
const EARTH_M = 6371008.8;
const CELL = 50;

// Таблица 1 rules/rules.json: Ду, высота и ширина габарита, пропускная способность, предельная длина
export const DIAMETERS = [[50, 0.125, 0.4, 3.5, 181], [65, 0.14, 0.43, 8.3, 245], [80, 0.16, 0.47, 13.2, 327], [100, 0.18, 0.51, 22.3, 419],
  [125, 0.225, 0.6, 40.2, 554], [150, 0.25, 0.65, 65.1, 696], [200, 0.315, 0.88, 152.3, 1042], [250, 0.4, 1.05, 274.9, 1379],
  [300, 0.45, 1.15, 437.4, 1718], [400, 0.56, 1.37, 943.1, 2477], [500, 0.71, 1.67, 1663.4, 3245], [600, 0.8, 1.85, 2627.7, 4037],
  [700, 0.9, 2.05, 3735.1, 4775], [800, 1.0, 2.25, 5296.8, 5644], [900, 1.1, 2.45, 7165.0, 6518], [1000, 1.2, 2.65, 9391.8, 7419],
  [1200, 1.425, 3.1, 15012.8, 9288], [1400, 1.6, 3.45, 22501.9, 11276]]
  .map(([dn, h, w, cap, maxLen]) => ({dn, h, w, cap, maxLen}));

// Таблица 2 и раздел 14: глубина верха, высота габарита, нужный вертикальный зазор с новой сетью.
// Водопровода и канализации в правилах по глубине нет — показываем условно и зазор не проверяем.
export const UTILITIES = {
  gas_pipeline: {top: 2.8, h: 0.40, clear: 0.2, name: 'Газопровод', short: 'газ', color: '#facc15'},
  power_cable: {top: 2.7, h: 0.20, clear: 0.5, name: 'Электрокабель', short: 'кабель', color: '#c084fc'},
  heat_network: {top: 3.0, h: null, clear: 0.5, name: 'Существующая теплосеть', short: 'теплосеть', color: '#ef4444'},
  water_supply: {top: 2.5, h: 0.30, clear: null, name: 'Водопровод', short: 'вода', color: '#3b82f6', assumed: true},
  sewer: {top: 3.5, h: 0.40, clear: null, name: 'Канализация', short: 'канализ.', color: '#a16207', assumed: true},
};
// Верх новой сети под полигоном не мельче, м (зона — сам полигон, без буфера 3 м)
export const UNDER = {road: 1.0, tram_tracks: 1.2};

export const SURFACES = {
  road: {name: 'Дороги', color: '#4b5563'},
  tram_tracks: {name: 'Трамвайные пути', color: '#6b7280'},
  railway: {name: 'Железная дорога', color: '#78716c'},
  metro: {name: 'Метро (охранная зона)', color: '#be185d'},
  park: {name: 'Парки и скверы', color: '#15803d'},
  social_area: {name: 'Школы, больницы', color: '#9a5b3c'},
  water: {name: 'Водоёмы', color: '#1d4ed8'},
  prohibited_site: {name: 'Запретные зоны', color: '#7f2a2a'},
  power_line_support: {name: 'Опоры ЛЭП', color: '#e879f9'},
  oks_existing: {name: 'Здания', color: '#94a3b8'},
  oks_future: {name: 'Новые здания (подключаем)', color: '#22d3ee'},
};
export const BUILDING_KEYS = new Set(['oks_existing', 'oks_future', 'oks']);
// типы объектов входа (раздел 12 CONSTRAINTS.md и редакция 18.09)
const KNOWN = new Set([...Object.keys(UTILITIES), ...Object.keys(SURFACES), ...BUILDING_KEYS, 'heat_chamber', 'oks_connection_point', 'source']);

export const NODE_TYPES = {
  heat_chamber: {name: 'Новая камера', color: '#38bdf8'},
  tie_in: {name: 'Врезка в существующую сеть', color: '#22c55e'},
  technical_node: {name: 'Поворот трассы', color: '#94a3b8'},
  heat_chamber_reconstruction: {name: 'Камера под реконструкцию', color: '#f43f5e'},
};

const KIND = {residential: 'Жилой дом', apartments: 'Жилой дом', school: 'Школа', kindergarten: 'Детский сад', hospital: 'Больница',
  clinic: 'Поликлиника', office: 'Офисное здание', commercial: 'Торговое здание', retail: 'Торговое здание', public: 'Общественное здание'};

// Человеческие имена вместо служебных номеров: «Участок 6», «Камера 19», «Поворот 3»
export function segName(s) { const m = /_seg_(\d+)$/.exec(s.id); return m ? `Участок ${m[1]}` : `Участок ${s.id}`; }
export function nodeName(nd, sc) {
  if (!nd) return '—';
  const id = String(nd.id);
  let m;
  if (nd.type === 'oks_entry') { const o = sc?.oks.find(x => x.id === id); return o ? `ввод: ${o.label}` : `ввод ${id}`; }
  if (nd.type === 'existing_chamber') return `сущ. камера ${id.replace(/^hc-/, '')}`;
  if ((m = /_ch_(\d+)$/.exec(id))) return `камера ${m[1]}`;
  if ((m = /_node_(\d+)$/.exec(id))) return `поворот ${m[1]}`;
  return id;
}

export function dnRow(dn) {
  if (dn == null) return DIAMETERS[0];
  for (const d of DIAMETERS) if (d.dn >= dn) return d;
  return DIAMETERS[DIAMETERS.length - 1];
}
export const kDepth = h => h > NORMAL_DEPTH ? 1 + 0.1 * (h - NORMAL_DEPTH) : 1;

// ---------- геометрия ----------
export function segX(a, b, c, d) {
  const rx = b[0] - a[0], ry = b[1] - a[1], sx = d[0] - c[0], sy = d[1] - c[1];
  const den = rx * sy - ry * sx;
  if (Math.abs(den) < 1e-12) return null;
  const qx = c[0] - a[0], qy = c[1] - a[1];
  const t = (qx * sy - qy * sx) / den, u = (qx * ry - qy * rx) / den;
  return t >= 0 && t <= 1 && u >= 0 && u <= 1 ? [t, a[0] + t * rx, a[1] + t * ry] : null;
}
export function inRing(x, y, ring) {
  let inside = false;
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const [xi, yi] = ring[i], [xj, yj] = ring[j];
    if ((yi > y) !== (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside;
  }
  return inside;
}
function* cells(a, b) {
  const x0 = Math.floor(Math.min(a[0], b[0]) / CELL), x1 = Math.floor(Math.max(a[0], b[0]) / CELL);
  const y0 = Math.floor(Math.min(a[1], b[1]) / CELL), y1 = Math.floor(Math.max(a[1], b[1]) / CELL);
  for (let i = x0; i <= x1; i++) for (let j = y0; j <= y1; j++) yield i * 100003 + j;
}
const linesOf = g => g.type === 'LineString' ? [g.coordinates] : g.type === 'MultiLineString' ? g.coordinates : [];
const polysOf = g => g.type === 'Polygon' ? [g.coordinates] : g.type === 'MultiPolygon' ? g.coordinates : [];
const sid = v => v == null ? null : String(v);

function* allCoords(g) {
  const t = g.type, c = g.coordinates;
  if (t === 'Point') yield c;
  else if (t === 'LineString' || t === 'MultiPoint') yield* c;
  else if (t === 'Polygon' || t === 'MultiLineString') for (const r of c) yield* r;
  else if (t === 'MultiPolygon') for (const p of c) for (const r of p) yield* r;
}

// Проекция вокруг центра: x = Δλ·R·cos φ0, y = Δφ·R. Обратная — для координат под курсором.
export function makeProjection(lon0, lat0) {
  const k = Math.cos(lat0 * Math.PI / 180), D = Math.PI / 180;
  return {
    lon0, lat0,
    xy: c => [(c[0] - lon0) * D * EARTH_M * k, (c[1] - lat0) * D * EARTH_M],
    ll: (x, y) => [lon0 + x / (D * EARTH_M * k), lat0 + y / (D * EARTH_M)],
  };
}

/**
 * Сцена: {proj, bbox, utilities, surfaces, points, oks, variants, segs, nodes, crossings, hasDepth, stats}.
 * input и output — разобранные FeatureCollection; output может отсутствовать (только исходные данные).
 * onProgress(доля, текст) вызывается между этапами; функция асинхронная, чтобы большой город не морозил вкладку.
 */
export async function buildScene(input, output, {onProgress = () => {}, yieldEvery = 20000} = {}) {
  const fin = input?.features || [], fout = output?.features || [];
  const wait = typeof setTimeout === 'function' ? () => new Promise(r => setTimeout(r, 0)) : async () => {};
  onProgress(0.02, 'Центр набора');
  // центр — по точкам входа, иначе по всем координатам
  let sx = 0, sy = 0, n = 0;
  for (const f of fin) if (f.geometry?.type === 'Point') { sx += f.geometry.coordinates[0]; sy += f.geometry.coordinates[1]; n++; }
  if (!n) for (const f of [...fin, ...fout]) if (f.geometry) for (const c of allCoords(f.geometry)) { sx += c[0]; sy += c[1]; n++; }
  if (!n) throw new Error('В файле нет объектов с координатами');
  const proj = makeProjection(sx / n, sy / n), xy = proj.xy;

  const utilities = {}, surfaces = {}, points = {};
  const grid = new Map();   // клетка → [{type, id, a, b, top, h}]
  const zones = new Map();  // клетка → [{type, bbox, ring}]
  let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
  const grow = p => { if (p[0] < x0) x0 = p[0]; if (p[0] > x1) x1 = p[0]; if (p[1] < y0) y0 = p[1]; if (p[1] > y1) y1 = p[1]; };
  const byInputId = new Map();
  const skipped = new Map();   // почему объект не попал в сцену → сколько таких
  const skip = why => skipped.set(why, (skipped.get(why) || 0) + 1);

  for (let fi = 0; fi < fin.length; fi++) {
    if (fi % yieldEvery === yieldEvery - 1) { onProgress(0.05 + 0.45 * fi / fin.length, `Исходные данные: ${fi} из ${fin.length}`); await wait(); }
    const f = fin[fi], p = f.properties || {}, g = f.geometry;
    if (!g) { skip('без геометрии'); continue; }
    const ot = p.object_type, rt = p.restriction_type || ot, id = sid(p.id ?? `#${fi + 1}`);
    if (!rt) { skip('нет свойства object_type'); continue; }
    if (!KNOWN.has(rt)) { skip(`неизвестный тип «${rt}»`); continue; }
    if (UTILITIES[rt] && (g.type === 'LineString' || g.type === 'MultiLineString')) {
      const u = UTILITIES[rt], dn = p.diameter ?? null;
      const h = rt === 'heat_network' ? dnRow(dn).h : u.h;
      const list = utilities[rt] ||= [];
      for (const line of linesOf(g)) {
        const pts = line.map(xy);
        pts.forEach(grow);
        const item = {id, pts, dn, top: u.top, h, p};
        list.push(item);
        byInputId.set(id, {kind: 'utility', type: rt, item});
        for (let i = 0; i + 1 < pts.length; i++) {
          const e = {type: rt, id, a: pts[i], b: pts[i + 1], top: u.top, h};
          for (const c of cells(pts[i], pts[i + 1])) { let l = grid.get(c); if (!l) grid.set(c, l = []); l.push(e); }
        }
      }
    } else if (g.type === 'Polygon' || g.type === 'MultiPolygon') {
      const key = rt === 'oks' ? 'oks_existing' : rt;
      const list = surfaces[key] ||= [];
      for (const poly of polysOf(g)) {
        const rings = poly.map(r => r.map(xy));
        if (!rings.length || rings[0].length < 3) continue;
        rings[0].forEach(grow);
        let bx0 = Infinity, by0 = Infinity, bx1 = -Infinity, by1 = -Infinity;
        for (const q of rings[0]) { if (q[0] < bx0) bx0 = q[0]; if (q[0] > bx1) bx1 = q[0]; if (q[1] < by0) by0 = q[1]; if (q[1] > by1) by1 = q[1]; }
        const item = {id, rings, p, bbox: [bx0, by0, bx1, by1]};
        list.push(item);
        byInputId.set(id, {kind: 'surface', type: key, item});
        {
          const z = {type: key, bbox: item.bbox, ring: rings[0], item};
          for (const c of cells([bx0, by0], [bx1, by1])) { let l = zones.get(c); if (!l) zones.set(c, l = []); l.push(z); }
        }
      }
    } else if (g.type === 'Point') {
      const key = rt;
      const item = {id, xy: xy(g.coordinates), p};
      grow(item.xy);
      (points[key] ||= []).push(item);
      byInputId.set(id, {kind: 'point', type: key, item});
    }
  }
  await wait();
  onProgress(0.5, 'Здания к подключению');

  const zonesAt = (x, y) => (zones.get(Math.floor(x / CELL) * 100003 + Math.floor(y / CELL)) || [])
    .filter(z => x >= z.bbox[0] && x <= z.bbox[2] && y >= z.bbox[1] && y <= z.bbox[3] && inRing(x, y, z.ring));

  // Здания к подключению: точка подключения + здание (по oks_id или полигон, в котором лежит точка)
  const oks = (points.oks_connection_point || []).map(cp => {
    let bld = null;
    const oid = sid(cp.p.oks_id);
    if (oid && byInputId.get(oid)?.kind === 'surface') bld = byInputId.get(oid).item;
    if (!bld) bld = zonesAt(cp.xy[0], cp.xy[1]).find(z => BUILDING_KEYS.has(z.type))?.item || null;
    if (bld) bld.isFuture = true;
    const flow = cp.p.flow_tph ?? bld?.p.flow_tph ?? null;
    const load = cp.p.heat_load ?? bld?.p.heat_load ?? null;
    const name = bld?.p.address || bld?.p.name || cp.p.address || cp.p.name || null;
    return {id: cp.id, oksId: oid || bld?.id || null, xy: cp.xy, flow, load, name, building: bld, p: cp.p};
  });
  // понятные имена: адрес, если есть, иначе «Жилой дом №14, 15 эт.» — номер по порядку во входе
  oks.forEach((o, i) => {
    o.no = i + 1;
    const lv = o.building?.p.levels;
    o.label = o.name || `${KIND[o.building?.p._kind] || 'Здание'} №${o.no}${lv ? `, ${lv} эт.` : ''}`;
  });

  // ---------- выход ----------
  onProgress(0.55, 'Новая сеть');
  const summaries = [], outById = new Map();
  for (const f of fout) {
    const p = f.properties || {};
    if (p.object_type === 'variant_summary') summaries.push(p);
    else if (p.id != null) outById.set(sid(p.id), {p, g: f.geometry});
  }
  const nodes = [], segs = [], recon = [];
  for (const f of fout) {
    const p = f.properties || {}, g = f.geometry;
    if (!g || p.object_type === 'variant_summary') continue;
    const v = sid(p.variant_id) || '1';
    if (g.type === 'Point') {
      nodes.push({id: sid(p.id), v, type: p.object_type, xy: xy(g.coordinates), p, depth: null});
      continue;
    }
    if (p.object_type !== 'heat_network') {
      for (const line of linesOf(g)) recon.push({id: sid(p.id), v, type: p.object_type, pts: line.map(xy), p});
      continue;
    }
    const row = dnRow(p.diameter);
    const pts = (g.type === 'LineString' ? g.coordinates : g.coordinates.flat()).map(xy);
    pts.forEach(grow);
    const d0 = p.depth_start ?? NORMAL_DEPTH, d1 = p.depth_end ?? NORMAL_DEPTH;
    const lens = [0];
    for (let i = 1; i < pts.length; i++) lens.push(lens[i - 1] + Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]));
    const len = lens[lens.length - 1] || 1e-9;
    const depth = lens.map(s => d0 + (d1 - d0) * s / len);
    segs.push({id: sid(p.id), v, p, row, pts, lens, len, depth, d0, d1, special: p.laying_method === 'special',
      from: sid(p.start_node_id), to: sid(p.end_node_id), cx: [], issues: [], bad: false});
  }

  // ---------- пересечения и проверки ----------
  onProgress(0.6, 'Пересечения с коммуникациями');
  const crossings = [];
  const tiedOf = s => new Set([s.from, s.to].map(id => outById.get(id)?.p.existing_object_id).filter(x => x != null).map(String));
  for (let si = 0; si < segs.length; si++) {
    if (si % 500 === 499) { onProgress(0.6 + 0.3 * si / segs.length, `Пересечения: участок ${si} из ${segs.length}`); await wait(); }
    const s = segs[si], {pts, lens, len, d0, d1, row} = s, tied = tiedOf(s), seen = new Set(), issues = [];
    const slope = Math.abs(d1 - d0) / len;
    // глубины в выходе округлены до мм: на коротком участке это даёт лишнюю тысячную уклона
    if (slope > MAX_SLOPE + 0.0011 / len + 1e-9) issues.push(`уклон ${slope.toFixed(3)} больше ${MAX_SLOPE}`);
    if (Math.min(d0, d1) < MIN_DEPTH - 1e-9) issues.push(`глубина ${Math.min(d0, d1).toFixed(2)} м мельче ${MIN_DEPTH} м`);
    for (let i = 0; i + 1 < pts.length; i++) {
      const a = pts[i], b = pts[i + 1], cand = new Set();
      for (const c of cells(a, b)) for (const e of grid.get(c) || []) cand.add(e);
      for (const e of cand) {
        if (e.type === 'heat_network' && tied.has(e.id)) continue;
        const hit = segX(a, b, e.a, e.b);
        if (!hit) continue;
        const key = `${e.type}|${e.id}|${hit[1].toFixed(1)}|${hit[2].toFixed(1)}`;
        if (seen.has(key)) continue;
        seen.add(key);
        const at = lens[i] + hit[0] * (lens[i + 1] - lens[i]);
        if (e.type === 'heat_network' && Math.min(at, len - at) <= 0.05) continue;  // камера врезки на трубе
        const hn = d0 + (d1 - d0) * at / len, u = UTILITIES[e.type];
        const over = e.top - (hn + row.h), under = hn - (e.top + e.h);
        const gap = Math.max(over, under);
        // без нормы зазора (вода, канализация) проверяем хотя бы физику: трубы не должны проходить друг через друга
        const ok = u.clear == null ? (gap < -1e-9 ? false : null) : gap >= u.clear - 1e-9;
        const cl = u.clear ?? 0;   // без нормы — хотя бы не насквозь
        const aboveMax = e.top - cl - row.h;
        const belowMin = e.top + e.h + cl;
        const c = {seg: s, v: s.v, x: hit[1], y: hit[2], s: at, type: e.type, eid: e.id, top: e.top, eh: e.h, hn, H: row.h,
          side: over >= under ? 'над' : 'под', gap, need: u.clear, ok,
          above: aboveMax != null && aboveMax >= MIN_DEPTH ? aboveMax : null, below: belowMin, kBelow: belowMin == null ? null : kDepth(belowMin)};
        s.cx.push(c);
        crossings.push(c);
      }
      // дороги и трамвай: верх не мельче 1,0/1,2 м — концы и середина отрезка
      for (const [q, sq] of [[a, lens[i]], [b, lens[i + 1]], [[(a[0] + b[0]) / 2, (a[1] + b[1]) / 2], (lens[i] + lens[i + 1]) / 2]]) {
        for (const z of zonesAt(q[0], q[1])) {
          if (!UNDER[z.type]) continue;
          const hq = d0 + (d1 - d0) * sq / len;
          if (hq < UNDER[z.type] - 1e-9) issues.push(`${SURFACES[z.type].name.toLowerCase()}: верх ${hq.toFixed(2)} м мельче ${UNDER[z.type]} м`);
          break;
        }
      }
    }
    s.cx.sort((p, q) => p.s - q.s);
    s.issues = [...new Set(issues)];
    if (s.issues.length) crossings.push({seg: s, v: s.v, x: pts[0][0], y: pts[0][1], s: 0, type: 'segment', eid: s.id, ok: false, issues: s.issues});
    s.bad = s.issues.length > 0 || s.cx.some(c => c.ok === false);
  }

  // ---------- узлы: глубина и связи ----------
  onProgress(0.92, 'Узлы и варианты');
  const nodeById = new Map(nodes.map(nd => [nd.v + '|' + nd.id, nd]));
  for (const s of segs) {
    for (const [id, d, end] of [[s.from, s.d0, s.pts[0]], [s.to, s.d1, s.pts[s.pts.length - 1]]]) {
      let nd = nodeById.get(s.v + '|' + id);
      if (!nd) {
        // конец участка в объекте входа: существующая камера, точка подключения, источник
        const src = byInputId.get(id);
        const type = src?.type === 'heat_chamber' ? 'existing_chamber' : src?.type === 'oks_connection_point' ? 'oks_entry' : 'endpoint';
        nd = {id, v: s.v, type, xy: end, p: src?.item.p || {id}, depth: null, virtual: true};
        nodeById.set(s.v + '|' + id, nd);
        nodes.push(nd);
      }
      nd.depth = Math.max(nd.depth ?? 0, d + s.row.h);
      nd.a0 = Math.min(nd.a0 ?? Infinity, d + s.row.h / 2);
      nd.a1 = Math.max(nd.a1 ?? -Infinity, d + s.row.h / 2);
      nd.dn = Math.max(nd.dn || 0, s.p.diameter || 0);
    }
  }

  const vids = [...new Set([...summaries.map(s => sid(s.variant_id)), ...segs.map(s => s.v)])].filter(Boolean);
  const variants = vids.map(v => {
    const summary = summaries.find(s => sid(s.variant_id) === v) || {};
    const vs = segs.filter(s => s.v === v);
    const ends = new Set(vs.map(s => s.to));
    const unconnected = new Set((summary.unconnected_oks_ids || []).map(String));
    const connected = new Set(oks.filter(o => ends.has(o.id) || (!unconnected.has(o.id) && !unconnected.has(String(o.oksId)) && ends.has(String(o.oksId)))).map(o => o.id));
    return {id: v, summary, rank: summary.rank ?? null, score: summary.score ?? null, segs: vs, connected,
      nodes: nodes.filter(nd => nd.v === v), crossings: crossings.filter(c => c.v === v), recon: recon.filter(r => r.v === v)};
  }).sort((a, b) => (a.rank ?? 99) - (b.rank ?? 99) || String(a.id).localeCompare(String(b.id)));

  onProgress(1, 'Готово');
  const count = o => Object.values(o).reduce((a, l) => a + l.length, 0);
  return {
    proj, bbox: Number.isFinite(x0) ? [x0, y0, x1, y1] : [-100, -100, 100, 100],
    utilities, surfaces, points, oks, variants, segs, nodes, crossings, recon, byInputId, zonesAt,
    hasOutput: fout.length > 0,
    hasAddresses: oks.some(o => o.name) || [...BUILDING_KEYS].some(k => (surfaces[k] || []).some(it => it.p.address)),
    skipped: [...skipped.entries()].sort((a, b) => b[1] - a[1]),
    hasDepth: segs.some(s => s.p.depth_start != null),
    stats: {input: fin.length, output: fout.length, utilities: count(utilities), surfaces: count(surfaces), points: count(points)},
  };
}

// ---------- трасса от врезки до здания ----------
/** Цепочка участков варианта от корня до узла end (по end_node_id вверх по дереву). */
export function routeTo(variant, end) {
  const byEnd = new Map(variant.segs.map(s => [s.to, s]));
  const path = [];
  for (let s = byEnd.get(String(end)), guard = 0; s && guard < 1e5; s = byEnd.get(s.from), guard++) path.unshift(s);
  if (!path.length) return null;
  let acc = 0;
  for (const s of path) { s.s0 = acc; acc += s.len; }
  const cost = path.reduce((a, s) => a + (s.p.cost || 0), 0);
  return {segs: path, end: String(end), start: path[0].from, len: acc, cost,
    bad: path.reduce((a, s) => a + s.cx.filter(c => c.ok === false).length + (s.issues.length ? 1 : 0), 0)};
}

/** Конец ветки для участка: идём вниз, пока ветка не ветвится, — так щелчок по трубе даёт трассу до здания. */
export function branchEnd(variant, seg) {
  const byStart = new Map();
  for (const s of variant.segs) { const l = byStart.get(s.from); if (l) l.push(s); else byStart.set(s.from, [s]); }
  let s = seg;
  for (let guard = 0; guard < 1e5; guard++) {
    const next = byStart.get(s.to);
    if (!next || next.length !== 1) break;
    s = next[0];
  }
  return s.to;
}

/** Точка трассы на расстоянии at от начала: [x, y, глубина верха], участок и направление. */
export function pointAt(route, at) {
  at = Math.max(0, Math.min(route.len, at));
  const s = route.segs.find(q => at <= q.s0 + q.len + 1e-9) || route.segs[route.segs.length - 1];
  let t = at - s.s0;
  for (let i = 1; i < s.pts.length; i++) {
    const l = s.lens[i] - s.lens[i - 1];
    if (t <= l || i === s.pts.length - 1) {
      const k = l > 0 ? Math.min(1, t / l) : 0, a = s.pts[i - 1], b = s.pts[i];
      const dir = l > 0 ? [(b[0] - a[0]) / l, (b[1] - a[1]) / l] : [1, 0];
      return {x: a[0] + (b[0] - a[0]) * k, y: a[1] + (b[1] - a[1]) * k, d: s.depth[i - 1] + (s.depth[i] - s.depth[i - 1]) * k, seg: s, dir};
    }
    t -= l;
  }
  const last = s.pts[s.pts.length - 1];
  return {x: last[0], y: last[1], d: s.d1, seg: s, dir: [1, 0]};
}
