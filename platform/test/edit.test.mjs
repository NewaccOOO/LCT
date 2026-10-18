// node --test platform/test — ручная правка трассы: проверки плана, привязка, расстояния до объектов, выгрузка
import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {buildScene} from '../js/model.js';
import {planIssues, jointIssue, extraBends, snapOrtho, surroundings, patchOutput, relen, score, buildingGap, turnAngle, utm, utmZone, toDxf} from '../js/edit.js';

const raw = n => readFileSync(new URL(`../demo/${n}`, import.meta.url), 'utf8');
const box = (x0, y0, x1, y1) => [[x0, y0], [x1, y0], [x1, y1], [x0, y1], [x0, y0]];
// сцена-заглушка: всё рядом со всеми — поиск по сетке здесь не проверяем
const stub = ({zones = [], utils = []} = {}) => ({zonesNear: () => zones, utilsNear: () => utils});
const seg = (pts, dn = 150, extra = {}) => ({pts, p: {diameter: dn}, ...extra});

test('отступ от здания по Ду и угол поворота', () => {
  assert.equal(buildingGap(150), 5);
  assert.equal(buildingGap(400), 5);
  assert.equal(buildingGap(500), 7);
  assert.equal(buildingGap(1000), 9);
  assert.equal(Math.round(turnAngle([0, 0], [10, 0], [10, 10])), 90);
  assert.equal(Math.round(turnAngle([0, 0], [10, 0], [20, 0])), 0);
});

test('planIssues: здание, зона запрета, дорога, поворот', () => {
  // отступы — от стенки канала: у Ду 150 канал 0,65 м, от оси это норма + 0,325 м
  const house = {type: 'oks_existing', ring: box(10, -5, 20, 5), item: {}};
  const sc = stub({zones: [house]});
  // в 10 м от здания — чисто
  assert.deepEqual(planIssues(sc, seg([[0, -20], [0, 20]])), []);
  // ось в 5,2 м от здания: стенка в 4,875 м при норме 5 м
  const near = planIssues(sc, seg([[4.8, -20], [4.8, 20]]));
  assert.equal(near.length, 1);
  assert.equal(near[0].level, 'bad');
  assert.match(near[0].text, /ближе 5 м к зданию: 4,8 м/);
  // ось в 5,4 м — стенка в 5,075 м, норма соблюдена
  assert.deepEqual(planIssues(sc, seg([[4.6, -20], [4.6, 20]])), []);
  // насквозь
  assert.match(planIssues(sc, seg([[15, -20], [15, 20]]))[0].text, /проходит через здание/);
  // своё здание: участок входит в него только последним звеном у ввода
  assert.deepEqual(planIssues(sc, seg([[15, -20], [15, -5]]), house.item), []);
  assert.match(planIssues(sc, seg([[15, -20], [15, 0], [15, 3]]), house.item)[0].text, /проходит через своё здание до ввода/);
  assert.match(planIssues(sc, seg([[15, -20], [15, 4], [12, 4.9]]), house.item).map(i => i.text).join(), /своё здание/);
  // Ду 500 — норма 7 м
  assert.match(planIssues(sc, seg([[3, -20], [3, 20]], 500))[0].text, /ближе 7 м к зданию/);
  // запретная зона: отступ 1 м
  const park = {type: 'park', ring: box(10, -5, 20, 5)};
  assert.match(planIssues(stub({zones: [park]}), seg([[9.5, -20], [9.5, 20]]))[0].text, /ближе 1 м к парку: 0,17 м/);
  // дорога: под 30° — нарушение всегда; без спецперехода — нарушение только у правленого участка (lines)
  const road = {type: 'road', ring: box(-50, -3, 50, 3)};
  const r30 = planIssues(stub({zones: [road]}), seg([[-17.3, -10], [17.3, 10]]));
  assert.equal(r30.length, 1);
  assert.match(r30[0].text, /под углом 30°, нужно не меньше 45°/);
  assert.deepEqual(planIssues(stub({zones: [road]}), seg([[0, -10], [0, 10]])), []);
  const r90 = planIssues(stub({zones: [road]}), seg([[0, -10], [0, 10]]), null, {lines: true});
  assert.equal(r90[0].level, 'bad');
  assert.match(r90[0].text, /обычной прокладкой: нужен спецпереход/);
  assert.deepEqual(planIssues(stub({zones: [road]}), seg([[0, -10], [0, 10]], 150, {special: true}), null, {lines: true}), []);
  // вдоль дороги ближе 1,5 м от стенки — нарушение вне спецперехода; у конца спецперехода — допустимо
  const along = seg([[-20, 4.5], [20, 4.5]]);
  assert.match(planIssues(stub({zones: [road]}), along, null, {lines: true})[0].text, /ближе 1,5 м к дороге вне спецперехода: 1,1 м/);
  assert.deepEqual(planIssues(stub({zones: [road]}), seg([[0, 4.5], [0, 20]]), null, {lines: true, ends: [[0, 4.5]]}), []);
  // газопровод: пересечение обычной прокладкой и сближение
  const gas = {type: 'gas_pipeline', id: 'g', a: [-30, 0], b: [30, 0]};
  assert.match(planIssues(stub({utils: [gas]}), seg([[0, -10], [0, 10]]), null, {lines: true})[0].text, /пересекает газопровод обычной прокладкой/);
  assert.match(planIssues(stub({utils: [gas]}), seg([[-20, 2], [20, 2]]), null, {lines: true})[0].text, /ближе 2 м к газопроводу вне спецперехода: 1,4 м/);
  // поворот 153° — круче 90°
  const turn = planIssues(stub(), seg([[0, 0], [10, 0], [0, 5]]));
  assert.equal(turn.length, 1);
  assert.match(turn[0].text, /угол поворота 153° — больше 90°/);
  assert.deepEqual(planIssues(stub(), seg([[0, 0], [10, 0], [10, 10]])), []);
});

test('jointIssue: излом на пути точки через узел или камеру', () => {
  const a = seg([[0, 0], [10, 0]]), b = seg([[10, 0], [0, 1]]);
  assert.match(jointIssue(a, b).text, /в узле — больше 90°/);
  assert.match(jointIssue(a, b, 'камере 20').text, /угол поворота 174° в камере 20 — больше 90°/);
  assert.equal(jointIssue(a, seg([[10, 0], [10, 10]])), null);
});

test('extraBends: поворот без причины — лишний, обход здания — нет', () => {
  const house = {type: 'oks_existing', ring: box(-5, 2, 5, 12), item: {}};
  // обход на 1 м там, где ничего нет
  const free = extraBends(stub(), seg([[-20, 0], [0, -1], [20, 0]]));
  assert.equal(free.length, 1);
  assert.match(free[0].text, /лишний/);
  // прямая прошла бы в 2 м от здания (норма 5 м) — поворот нужен
  assert.deepEqual(extraBends(stub({zones: [house]}), seg([[-20, 0], [0, -4], [20, 0]])), []);
  // вершины, которые были у сервиса, не трогаем
  assert.deepEqual(extraBends(stub(), seg([[-20, 0], [0, -1], [20, 0]]), null, [[0, -1]]), []);
});

test('snapOrtho: прямой угол к соседнему звену и к двум соседям', () => {
  // вдоль звена соседа
  const one = snapOrtho([10, 0.3], [{at: [0, 0], dir: [1, 0]}], 1);
  assert.deepEqual(one.p.map(v => +v.toFixed(6)), [10, 0]);
  assert.equal(one.guide.length, 1);
  // прямой угол с обеих сторон — в пересечение прямых
  const two = snapOrtho([9.7, 0.4], [{at: [0, 0], dir: [1, 0]}, {at: [10, 10], dir: [0, 1]}], 1);
  assert.deepEqual(two.p.map(v => +v.toFixed(6)), [10, 0]);
  assert.equal(two.guide.length, 2);
  // далеко от прямых — точка как есть
  const far = snapOrtho([5, 4], [{at: [0, 0], dir: [1, 0]}], 1);
  assert.deepEqual(far, {p: [5, 4], guide: []});
  // на соседа не схлопывается
  assert.deepEqual(snapOrtho([0.2, 0.2], [{at: [0, 0], dir: [1, 0]}], 1).p, [0.2, 0.2]);
});

test('surroundings: расстояния от стенки канала, нормы и уровни', () => {
  const house = {type: 'oks_existing', ring: box(10, -5, 20, 5), item: {}};
  const gas = {type: 'gas_pipeline', id: 'g1', a: [-3, -30], b: [-3, 30]};
  const hn = {type: 'heat_network', id: 'h1', a: [-0.8, -30], b: [-0.8, 30], dn: 100};
  const sc = stub({zones: [house], utils: [gas, hn]});
  const pipe = [[[0, -10], [0, 10]]];
  const at = x => surroundings(stub({zones: [house]}), [[[x, -10], [x, 10]]], 150);
  // здание: ось в 10 м, стенка в 9,675 м — норма с запасом
  const ok = at(0).find(i => i.key === 'bld');
  assert.equal(ok.level, 'ok');
  assert.equal(ok.need, 5);
  assert.ok(Math.abs(ok.d - 9.675) < 1e-9);
  assert.equal(ok.p[0], 0);
  assert.equal(ok.q[0], 10);
  assert.equal(ok.p[1], ok.q[1]);
  // запас меньше 1 м — на грани; ближе нормы — нарушение
  assert.equal(at(4.5).find(i => i.key === 'bld').level, 'near');
  assert.equal(at(5).find(i => i.key === 'bld').level, 'bad');
  // газопровод: ось в 3 м, стенка в 3 − 0,325 − 0,2 = 2,475 м при норме 2 м — на грани
  const all = surroundings(sc, pipe, 150);
  const g = all.find(i => i.key === 'gas_pipeline');
  assert.equal(g.level, 'near');
  assert.equal(g.need, 2);
  // теплосеть Ду 100 (канал 0,51 м) в 0,8 м от оси — ближе нормы 1 м: нарушение, нужен спецпереход
  const h = all.find(i => i.key === 'heat_network');
  assert.equal(h.level, 'bad');
  assert.match(h.note, /спецпереход/);
  assert.equal(all[0].level, 'bad');
  // спецпереход поперёк сети — справочно; вдоль сети в её зоне — нарушение
  assert.equal(surroundings(sc, [[[-5, 0], [5, 0]]], 150, {special: true}).find(i => i.key === 'heat_network').level, 'info');
  const along = surroundings(sc, pipe, 150, {special: true}).find(i => i.key === 'heat_network');
  assert.equal(along.level, 'bad');
  assert.match(along.note, /вдоль сети/);
  assert.match(planIssues(stub({utils: [hn]}), seg([[0, -10], [0, 10]], 150, {special: true}), null, {lines: true})[0].text, /спецпереход идёт .* в зоне сети/);
  // теплосеть врезки — не считается
  assert.ok(!surroundings(sc, pipe, 150, {tied: new Set(['h1'])}).some(i => i.key === 'heat_network'));
  // дальше радиуса — не показываем
  assert.deepEqual(surroundings(stub({zones: [house]}), [[[-30, -10], [-30, 10]]], 150, {R: 15}), []);
});

test('surroundings на Купчино: у трассы есть здания и сети, расстояния конечные', async () => {
  const sc = await buildScene(JSON.parse(raw('spb-kupchino.in.geojson')), JSON.parse(raw('spb-kupchino.out.geojson')));
  const v = sc.variants[0], s = v.segs.find(x => x.id === 'v1_seg_230');
  const items = surroundings(sc, [[s.pts[0], s.pts[s.pts.length - 1]]], s.p.diameter, {tied: sc.tiedOf(s)});
  assert.ok(items.length > 0);
  for (const i of items) { assert.ok(Number.isFinite(i.d) && i.d >= 0 && i.d <= 15); assert.ok(['ok', 'near', 'bad', 'info'].includes(i.level)); }
});

test('patchOutput: правка участка и камеры на Купчино', async () => {
  const txt = raw('spb-kupchino.out.geojson');
  const sc = await buildScene(JSON.parse(raw('spb-kupchino.in.geojson')), JSON.parse(txt));
  const v = sc.variants[0];
  const s = v.segs.find(x => x.id === 'v1_seg_230');
  const o = {len: s.len, cost: s.p.cost};
  // обход: новая вершина в 3 м от середины звена
  const [a, b] = [s.pts[0], s.pts[1]], L = Math.hypot(b[0] - a[0], b[1] - a[1]);
  s.pts.splice(1, 0, [(a[0] + b[0]) / 2 - 3 * (b[1] - a[1]) / L, (a[1] + b[1]) / 2 + 3 * (b[0] - a[0]) / L]);
  relen(s);
  s.p.cost = o.cost * s.len / o.len;
  assert.ok(s.len > o.len);
  const out = JSON.parse(patchOutput(txt, sc, v, {segs: new Set([s.id]), nodes: new Set(), issues: new Map([[s.id, ['ближе 5 м к зданию: 4,2 м']]])}, sc.proj));
  const was = JSON.parse(txt);
  assert.equal(out.features.length, was.features.length);
  const f = out.features.find(x => x.properties.id === s.id);
  assert.equal(f.geometry.coordinates.length, 3);
  assert.equal(f.properties.manual_edit, true);
  assert.deepEqual(f.properties.manual_issues, ['ближе 5 м к зданию: 4,2 м']);
  // длина — в метрах сервиса: тот же масштаб, что у исходного участка
  const k = was.features.find(x => x.properties.id === s.id).properties.length / o.len;
  assert.ok(Math.abs(k - 1) < 0.01);
  assert.ok(Math.abs(f.properties.length - s.len * k) < 0.01);
  assert.ok(Math.abs(f.properties.cost - o.cost * s.len / o.len) < 0.01);
  // концы участка остались на узлах
  const fw = was.features.find(x => x.properties.id === s.id);
  assert.deepEqual(f.geometry.coordinates[0], fw.geometry.coordinates[0]);
  assert.deepEqual(f.geometry.coordinates[2], fw.geometry.coordinates[1]);
  // остальное не тронуто
  const other = out.features.find(x => x.properties.id === 'v1_seg_8');
  assert.deepEqual(other, was.features.find(x => x.properties.id === 'v1_seg_8'));
  // сводка варианта: длина, стоимость и S сдвинулись на правку
  const sm = out.features.find(x => x.properties.object_type === 'variant_summary').properties;
  const sw = was.features.find(x => x.properties.object_type === 'variant_summary').properties;
  assert.ok(Math.abs(sm.new_network_length - sw.new_network_length - (s.len * k - fw.properties.length)) < 0.02);
  assert.ok(Math.abs(sm.calculated_cost - sw.calculated_cost - (s.p.cost - fw.properties.cost)) < 0.02);
  assert.equal(sm.score, +score(sm.calculated_cost, sm.new_network_length).toFixed(4));
  assert.equal(sm.manual_edits, 1);
  assert.equal(sm.manual_issues, 1);

  // камера: точка и концы всех её участков
  const n = v.nodes.find(x => x.id === 'v1_ch_20');
  const segs = v.segs.filter(x => x.from === n.id || x.to === n.id);
  assert.ok(segs.length >= 2);
  n.xy = [n.xy[0] + 2, n.xy[1] - 1];
  for (const t of segs) { if (t.from === n.id) t.pts[0] = n.xy.slice(); if (t.to === n.id) t.pts[t.pts.length - 1] = n.xy.slice(); relen(t); }
  const out2 = JSON.parse(patchOutput(txt, sc, v, {segs: new Set(segs.map(x => x.id)), nodes: new Set([n.id])}, sc.proj));
  const pt = out2.features.find(x => x.properties.id === n.id);
  assert.equal(pt.properties.manual_edit, true);
  for (const t of segs) {
    const g = out2.features.find(x => x.properties.id === t.id).geometry.coordinates;
    const end = t.from === n.id ? g[0] : g[g.length - 1];
    assert.deepEqual(end, pt.geometry.coordinates);
  }
});

test('UTM 37N как у сервиса и DXF для AutoCAD', async () => {
  // pyproj EPSG:4326 → EPSG:32637 для этой точки Купчино: 22546.5816, 6664402.8752
  const [x, y] = utm(30.465510886, 59.839809322, 37);
  assert.ok(Math.abs(x - 22546.5816) < 0.01 && Math.abs(y - 6664402.8752) < 0.01);
  const sc = await buildScene(JSON.parse(raw('spb-kupchino.in.geojson')), JSON.parse(raw('spb-kupchino.out.geojson')));
  const v = sc.variants[0];
  v.segs[0].edited = true;
  const dxf = toDxf(v, sc.proj);
  assert.equal(utmZone(sc.proj.lon0), 36);
  assert.ok(dxf.includes('\r\nK20\r\n'));
  assert.ok(dxf.startsWith('0\r\nSECTION'));
  assert.ok(dxf.trimEnd().endsWith('EOF'));
  assert.equal(dxf.split('\r\nPOLYLINE\r\n').length - 1, v.segs.length);
  assert.ok(dxf.includes('\r\nHEAT_EDITED\r\n'));
  assert.equal(dxf.split('\r\nCIRCLE\r\n').length - 1, v.nodes.filter(n => !['technical_node', 'oks_entry', 'endpoint'].includes(n.type)).length);
});
