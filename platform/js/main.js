// Платформа инженера: загрузка данных, выбор объектов, панели, инструменты. Сцена — viewer.js, навигация — controls.js.
import * as THREE from 'three';
import {Viewer, PALETTE, buildingHeight, dnColor} from './viewer.js';
import {CameraRig} from './controls.js';
import {ViewCube} from './viewcube.js';
import {Profile} from './profile.js';
import {buildScene, routeTo, branchEnd, pointAt, UTILITIES, SURFACES, NODE_TYPES, BUILDING_KEYS, DIAMETERS} from './model.js';
import {$, $$, fmt, rub, meters, esc, clamp, store, toast, plural} from './util.js';

const D = Math.PI / 180;
const S = {
  model: null, v: null, sel: null, route: null, ds: null, raw: null,
  xray: false, clip: null, measure: null, fly: null, server: false, cxAll: false, cxType: '', oksF: 'all',
};

// ---------- сцена ----------
const viewer = new Viewer($('#view'));
let cube = null, booted = false;
const rig = new CameraRig(viewer.camera, viewer.renderer.domElement, {
  pickPoint: (x, y) => { const h = pickAt(x, y); return h ? h.point : null; },
  onChange: () => { viewer.redraw(); if (booted) { cube.update(); scheduleHash(); updateScale(); } },
  onPivot: P => viewer.marker('pivot', P, {ring: true, color: PALETTE.selection}),
  onClick: (x, y, e) => clickAt(x, y, e),
  onDblClick: (x, y) => dblAt(x, y),
  onContext: (x, y, e) => showCtx(x, y, e),
  onHover: (x, y, e, dragging) => hoverAt(x, y, dragging),
  onSpeed: k => status(`Скорость полёта ×${fmt(k, 2)}`),
  onFly: () => { if (S.fly) stopFly(); },
});
viewer.onRender = () => { if (booted) cube.update(); };
cube = new ViewCube($('#cube'), viewer.camera, {
  orient: (dir, name) => orient(dir, name),
  orbit: (dx, dy) => { const P = rig.center(); rig.rotateAround(P, dx * 0.012, -dy * 0.01); },
  heading: yaw => { const P = rig.center(); rig.lookAtFrom(P, yaw, rig.pitch, rig.pos.distanceTo(P)); },
});
const profile = new Profile($('#pc'), {
  hover: p => { viewer.marker('pcur', p ? new THREE.Vector3(p.x, p.y, viewer.z(p.d + p.seg.row.h / 2)) : null, {color: PALETTE.selection, r: 0.8}); },
  click: ({crossing, at}) => {
    if (crossing) { const c = S.v.crossings.find(q => q.seg.id === crossing.seg.id && Math.abs(q.s - crossing.s) < 1e-6 && q.type === crossing.type) || crossing; selectCrossing(c); }
    else if (at != null) { const p = pointAt(S.route, at); lookAtPipe(p, 18); }
  },
});

function pickAt(x, y) {
  if (!S.model) return null;
  return viewer.pick(x, y, rig.ray(x, y));
}

// ---------- загрузка ----------
function progress(k, text) {
  $('#loading').hidden = false;
  $('#loadBar').style.width = Math.round(k * 100) + '%';
  if (text) $('#loadT').textContent = text;
}
async function fetchJson(url, what) {
  progress(0.02, `Загружаю ${what}…`);
  const r = await fetch(url);
  if (!r.ok) throw new Error(`${what}: ${r.status} ${r.statusText}`);
  const text = await r.text();
  return {json: JSON.parse(text), text};
}
async function load({input, output, outText = null, ds}) {
  try {
    progress(0.05, 'Строю сцену…');
    const model = await buildScene(input, output, {onProgress: (k, t) => progress(0.05 + k * 0.75, t)});
    progress(0.85, 'Рисую 3D…');
    await new Promise(r => setTimeout(r, 0));
    S.model = model; S.raw = outText; S.ds = ds; S.sel = null; S.route = null; S.ghost = null;
    stopFly(); setClip(null); setMeasure(false);
    viewer.variant = null;
    viewer.setModel(model);
    rig.bounds = model.bbox;
    $('#dsName').textContent = ds.title;
    document.title = `${ds.title} — Теплосеть 3D`;
    renderVariants();
    setVariant(model.variants[0]?.id, {quiet: true});
    renderLayers();
    // адрес с видом и выбором применяем только при первом открытии; новый набор — с исходного вида
    const fromLink = !S.opened && applyHash();
    S.opened = true;
    if (!fromLink) { history.replaceState(null, '', location.pathname + location.search); home(0); }
    $('#loading').hidden = true;
    const v = S.v;
    toast(`Открыт набор «${esc(ds.title)}»: ${fmt(model.stats.input)} объектов${v ? `, ${model.variants.length} ${plural(model.variants.length, 'вариант', 'варианта', 'вариантов')}` : ''}`, 'ok');
    if (!model.hasOutput) toast('Результата расчёта нет — показаны только исходные данные. Откройте результат или отправьте расчёт на сервер.', 'info', 6000);
    store.set('ds', ds.name || null);
  } catch (e) {
    console.error(e);
    $('#loading').hidden = true;
    toast(`Не удалось открыть данные: ${esc(e.message)}`, 'error', 8000);
  }
}
let demos = [];
async function loadDemo(d) {
  try {
    const [i, o] = await Promise.all([fetchJson('demo/' + d.input, 'вход'), d.output ? fetchJson('demo/' + d.output, 'результат') : null]);
    await load({input: i.json, output: o?.json, outText: o?.text, ds: {name: d.name, title: d.title}});
  } catch (e) { $('#loading').hidden = true; toast(`Не удалось загрузить демо-набор: ${esc(e.message)}`, 'error', 8000); }
}
function isOutput(fc) { return (fc.features || []).some(f => f.properties && (f.properties.object_type === 'variant_summary' || f.properties.variant_id != null)); }
async function loadFiles(files) {
  try {
    const parsed = await Promise.all([...files].map(async f => ({f, text: await f.text()})));
    let input = null, output = null, outText = null, name = [];
    for (const {f, text} of parsed) {
      const j = JSON.parse(text);
      if (!j || !Array.isArray(j.features)) throw new Error(`${f.name}: это не GeoJSON FeatureCollection`);
      if (isOutput(j)) { output = j; outText = text; } else input = j;
      name.push(f.name.replace(/\.(geo)?json$/i, ''));
    }
    if (!input && output) { input = {type: 'FeatureCollection', features: []}; toast('Открыт только результат — без входа не видно зданий и коммуникаций', 'info', 6000); }
    if (!input) throw new Error('нет входного файла');
    await load({input, output, outText, ds: {name: null, title: name.join(' + ')}});
  } catch (e) { $('#loading').hidden = true; toast(`Не удалось открыть файлы: ${esc(e.message)}`, 'error', 8000); }
}

// ---------- варианты ----------
function renderVariants() {
  const vs = S.model.variants, best = Math.min(...vs.map(v => v.score ?? Infinity));
  $('#variants').innerHTML = vs.map(v => `<button role="tab" data-v="${esc(v.id)}" title="Вариант ${esc(v.id)}: S ${fmt(v.score, 2)}, ${rub(v.summary.calculated_cost)}">
    <span><span class="vl">Вариант </span>${esc(v.id)}${vs.length > 1 && v.score === best ? ' ★' : ''}</span><small>S ${fmt(v.score, 2)} · ${rub(v.summary.calculated_cost)}</small></button>`).join('');
  $('#variants').hidden = !vs.length;
}
$('#variants').onclick = e => { const b = e.target.closest('button'); if (b) setVariant(b.dataset.v); };
function setVariant(id, {quiet = false} = {}) {
  const v = S.model.variants.find(x => x.id === id) || null;
  S.v = v;
  $$('#variants button').forEach(b => { b.classList.toggle('on', b.dataset.v === id); b.setAttribute('aria-selected', b.dataset.v === id); });
  const keep = S.sel;
  clearSel();
  viewer.setVariant(v);
  if (S.ghost === id) { S.ghost = null; viewer.setGhost(null); }
  renderSummary(); renderOks(); renderCx();
  if (keep && keep.kind === 'oks') select(keep, {fly: false});
  if (!quiet && v) toast(`Вариант ${esc(v.id)}: S ${fmt(v.score, 2)}`, 'info', 1800);
  scheduleHash();
}

// ---------- обзор ----------
function renderSummary() {
  const M = S.model, v = S.v, el = $('#t-sum');
  if (!v) {
    el.innerHTML = `<h2>Исходные данные</h2>${dataInfo()}<div class="note info">Результата расчёта нет. Откройте результат (<b>Файл → Открыть данные</b>)${S.server ? ' или отправьте вход на расчёт' : ''}.</div>`;
    return;
  }
  const sm = v.summary, bad = v.crossings.filter(c => c.ok === false).length, unc = M.oks.length - v.connected.size;
  const chambers = v.nodes.filter(n => n.type === 'heat_chamber').length, special = v.segs.filter(s => s.special).length;
  const len = sm.new_network_length ?? v.segs.reduce((a, s) => a + s.len, 0);
  el.innerHTML = `<h2>Вариант ${esc(v.id)}${v.rank ? ` · место ${v.rank}` : ''}</h2>
    <div class="kpi">
      <div><b>${fmt(sm.score, 2)}</b><span>оценка S, меньше — лучше</span></div>
      <div><b>${rub(sm.calculated_cost)}</b><span>полная стоимость</span></div>
      <div><b>${meters(len)}</b><span>новых труб</span></div>
      <div class="${unc ? 'bad' : 'ok'}"><b>${unc} из ${M.oks.length}</b><span>зданий не подключено</span></div>
      <div class="${bad ? 'bad' : 'ok'}"><b>${bad}</b><span>${plural(bad, 'проблема', 'проблемы', 'проблем')} по высоте</span></div>
      <div><b>${special}</b><span>${plural(special, 'спецпереход', 'спецперехода', 'спецпереходов')}</span></div>
    </div>
    <h2>Из чего стоимость</h2>
    <table>
      <tr><td>Трубы и спецпереходы</td><td>${rub((sm.construction_cost ?? 0) - (sm.chamber_construction_cost ?? 0))}</td></tr>
      <tr><td>Новые камеры (${chambers})</td><td>${rub(sm.chamber_construction_cost)}</td></tr>
      <tr><td>Врезки в существующие камеры (${sm.existing_chamber_tie_in_count ?? 0})</td><td>${rub(sm.existing_chamber_tie_in_cost)}</td></tr>
      <tr><td>Штраф за неподключённые</td><td>${rub(sm.unconnected_penalty)}</td></tr>
      <tr><th>Итого</th><th>${rub(sm.calculated_cost)}</th></tr>
    </table>
    ${M.variants.length > 1 ? `<h2>Сравнение вариантов</h2><table><tr><th>Вариант</th><th>S</th><th>Стоимость</th><th>Длина</th><th>Не подкл.</th></tr>
      ${M.variants.map(x => `<tr class="click ${x === v ? 'cur' : ''}" data-v="${esc(x.id)}" title="Щёлкните — открыть вариант ${esc(x.id)}"><td>${x === v ? '▶ ' : ''}${esc(x.id)}${x.rank === 1 ? ' ★' : ''}</td><td>${fmt(x.score, 2)}</td><td>${rub(x.summary.calculated_cost)}</td>
      <td>${meters(x.summary.new_network_length)}</td><td>${M.oks.length - x.connected.size}</td></tr>`).join('')}</table>
      <div class="filters" style="margin-top:8px">${M.variants.filter(x => x !== v).map(x => `<button data-ghost="${esc(x.id)}" class="${S.ghost === x.id ? 'on' : ''}" title="Показать трассы варианта ${esc(x.id)} поверх текущего">${S.ghost === x.id ? '✓ ' : ''}Вариант ${esc(x.id)} поверх <i class="sw" style="background:#f472b6"></i></button>`).join('')}</div>` : ''}
    <h2>С чего начать</h2>
    <div class="actions">
      <button data-go="oks">🏢 Найти здание и его трассу</button>
      <button data-go="cx">⚠️ Разобрать проблемы по высоте${bad ? ` (${bad})` : ''}</button>
      <button data-go="tour">▶ Пролететь по самой длинной трассе</button>
      <button data-go="help">⌨️ Как управлять сценой</button>
    </div>
    <h2>Данные</h2>${dataInfo()}`;
  el.onclick = e => {
    const tr = e.target.closest('tr[data-v]');
    if (tr) return setVariant(tr.dataset.v);
    const gb = e.target.closest('button[data-ghost]');
    if (gb) { S.ghost = S.ghost === gb.dataset.ghost ? null : gb.dataset.ghost; viewer.setGhost(S.model.variants.find(x => x.id === S.ghost) || null);
      if (S.ghost) toast(`Вариант ${esc(S.ghost)} показан розовым пунктиром поверх текущего`, 'info', 2500); renderSummary(); return; }
    const b = e.target.closest('button[data-go]');
    if (!b) return;
    if (b.dataset.go === 'help') $('#helpDlg').showModal();
    else if (b.dataset.go === 'tour') longestTour();
    else openTab(b.dataset.go);
  };
}
function dataInfo() {
  const M = S.model, u = Object.entries(M.utilities).map(([k, l]) => `${UTILITIES[k].name.toLowerCase()} ${fmt(l.length)}`).join(', ');
  return `<table><tr><td>Объектов во входе</td><td>${fmt(M.stats.input)}</td></tr>
    <tr><td>Зданий к подключению</td><td>${fmt(M.oks.length)}</td></tr>
    <tr><td>Коммуникации</td><td style="white-space:normal">${u || '—'}</td></tr>
    <tr><td>Глубина в результате</td><td>${M.hasDepth ? 'есть (режим с учётом глубины)' : M.hasOutput ? 'нет — трубы на обычных 3,0 м' : '—'}</td></tr></table>`;
}
function longestTour() {
  if (!S.v) return;
  let best = null;
  for (const o of S.model.oks) { if (!S.v.connected.has(o.id)) continue; const r = routeTo(S.v, o.id); if (r && (!best || r.len > best.r.len)) best = {o, r}; }
  if (!best) return toast('В варианте нет подключённых зданий', 'info');
  select({kind: 'oks', ref: best.o}, {fly: false});
  startFly();
}

// ---------- здания ----------
function renderOks() {
  const M = S.model, v = S.v, el = $('#t-oks');
  const rows = M.oks.map(o => ({o, conn: v ? v.connected.has(o.id) : null}));
  const nc = rows.filter(r => r.conn).length;
  $('#cntOks').textContent = M.oks.length ? (v ? `${nc}/${rows.length}` : rows.length) : '';
  el.innerHTML = `<h2>Здания к подключению</h2>
    ${v ? `<div class="filters" id="oksF"><button data-f="all">Все ${rows.length}</button><button data-f="conn">Подключены ${nc}</button><button data-f="no">Не подключены ${rows.length - nc}</button></div>` : ''}
    <input type="search" id="oksQ" placeholder="Номер или адрес здания" aria-label="Фильтр зданий">
    <div class="list" id="oksL"></div>`;
  const draw = () => {
    const q = ($('#oksQ').value || '').toLowerCase();
    $$('#oksF button').forEach(b => b.classList.toggle('on', b.dataset.f === S.oksF));
    const list = rows.filter(r => (S.oksF === 'all' || !v || (S.oksF === 'conn') === r.conn) &&
      (!q || String(r.o.id).toLowerCase().includes(q) || String(r.o.oksId || '').toLowerCase().includes(q) || String(r.o.name || '').toLowerCase().includes(q)));
    list.sort((a, b) => (a.conn === b.conn ? 0 : a.conn ? 1 : -1) || String(a.o.id).localeCompare(String(b.o.id), 'ru', {numeric: true}));
    $('#oksL').innerHTML = list.slice(0, 1500).map(({o, conn}) => {
      const r = conn ? routeTo(v, o.id) : null;
      return `<button class="item ${S.sel?.ref === o ? 'sel' : ''}" data-id="${esc(o.id)}"><div class="t"><span>${esc(o.name || 'Здание ' + (o.oksId || o.id))}</span>
        ${v ? `<span class="badge ${conn ? 'b-ok' : 'b-bad'}">${conn ? 'подключено' : 'не подключено'}</span>` : ''}</div>
        <div class="m">${o.name ? esc(o.oksId || o.id) + ' · ' : ''}${o.flow != null ? `нагрузка ${fmt(o.flow, 1)} т/ч` : ''}${r ? ` · трасса ${meters(r.len)}${r.bad ? ` · <span style="color:#fda4af">⚠ ${r.bad}</span>` : ''}` : v ? ' · маршрут не найден' : ''}</div></button>`;
    }).join('') || '<div class="item m">Ничего не найдено</div>';
  };
  draw();
  $('#oksF') && ($('#oksF').onclick = e => { const b = e.target.closest('button'); if (b) { S.oksF = b.dataset.f; draw(); } });
  $('#oksQ').oninput = draw;
  $('#oksL').onclick = e => { const b = e.target.closest('.item[data-id]'); if (!b) return; const o = M.oks.find(x => String(x.id) === b.dataset.id); select({kind: 'oks', ref: o}, {fly: true}); draw(); };
}

// ---------- проблемы ----------
function cxTitle(c) { return c.type === 'segment' ? `Участок ${c.eid}: нарушение` : `${UTILITIES[c.type].name}: пересечение`; }
function renderCx() {
  const v = S.v, el = $('#t-cx');
  if (!v) { el.innerHTML = '<div class="note info">Нет результата расчёта.</div>'; $('#cntCx').textContent = ''; return; }
  const all = v.crossings, bad = all.filter(c => c.ok === false);
  $('#cntCx').textContent = bad.length || '';
  $('#cntCx').className = 'cnt' + (bad.length ? ' bad' : '');
  const base = S.cxAll ? all : bad;
  const types = [...new Set(base.map(c => c.type))];
  el.innerHTML = `<h2>${S.cxAll ? 'Все пересечения' : 'Проблемы по высоте'}</h2>
    <div class="mut" style="font-size:13px">${S.cxAll ? 'Каждое место, где новая труба проходит над или под другой коммуникацией.' : 'Новая труба проходит слишком близко по высоте к другой коммуникации или нарушает уклон и глубину. Щёлкните — покажу место сбоку и как обойти.'}</div>
    <div class="filters" id="cxF"><button data-t="">Все ${base.length}</button>${types.map(t => `<button data-t="${t}">${t === 'segment' ? 'Уклон и глубина' : UTILITIES[t].name} ${base.filter(c => c.type === t).length}</button>`).join('')}</div>
    <label class="tog"><input type="checkbox" id="cxAll" ${S.cxAll ? 'checked' : ''}> Показать и пересечения без проблем (${all.length})</label>
    <div class="list" id="cxL" style="margin-top:8px"></div>`;
  const draw = () => {
    $$('#cxF button').forEach(b => b.classList.toggle('on', b.dataset.t === S.cxType));
    const list = base.filter(c => !S.cxType || c.type === S.cxType);
    $('#cxL').innerHTML = list.slice(0, 2000).map(c => `<button class="item ${S.sel?.ref === c ? 'sel' : ''}" data-k="${all.indexOf(c)}"><div class="t"><span>${esc(cxTitle(c))}</span>
      <span class="badge ${c.ok === false ? 'b-bad' : c.ok ? 'b-ok' : 'b-mut'}">${c.ok === false ? 'проблема' : c.ok ? 'норма' : 'не проверяется'}</span></div>
      <div class="m">${c.type === 'segment' ? esc(c.issues.join('; ')) : `зазор ${fmt(c.gap, 2)} м${c.need != null ? ` из ${fmt(c.need, 2)}` : ''} · труба ${esc(c.seg.id)} · ${fmt(c.s)} м от начала участка`}</div></button>`).join('')
      || `<div class="item m">${S.cxAll ? 'Пересечений нет' : 'Проблем нет 🎉'}</div>`;
  };
  draw();
  $('#cxF').onclick = e => { const b = e.target.closest('button'); if (b) { S.cxType = b.dataset.t; draw(); } };
  $('#cxAll').onchange = e => { S.cxAll = e.target.checked; S.cxType = ''; renderCx(); };
  $('#cxL').onclick = e => { const b = e.target.closest('.item[data-k]'); if (b) selectCrossing(all[+b.dataset.k]); };
}

// ---------- слои и вид ----------
function renderLayers() {
  const M = S.model, L = viewer.opts.layers, el = $('#t-lay');
  const tog = (k, name, color, n, dot) => `<label class="tog"><input type="checkbox" data-k="${k}" ${L[k] !== false ? 'checked' : ''}><i class="${dot ? 'dot' : 'sw'}" style="background:${color}"></i><span class="nm">${name}</span>${n != null ? `<span class="n">${fmt(n)}</span>` : ''}</label>`;
  const surf = Object.keys(M.surfaces).filter(k => !BUILDING_KEYS.has(k));
  const nb = [...BUILDING_KEYS].reduce((a, k) => a + (M.surfaces[k]?.length || 0), 0);
  const o = viewer.opts;
  const grad = [50, 100, 200, 400, 800, 1400].map(d => '#' + dnColor(d).getHexString());
  el.innerHTML = `<h2>Отображение</h2>
    <div class="fld"><label for="ex">Глубина ×<b id="exV">${viewer.ex}</b></label><input type="range" id="ex" min="1" max="15" step="1" value="${viewer.ex}">
      <small>Растягивает только вертикаль — как вертикальный масштаб профиля в Civil 3D. Диаметры остаются настоящими.</small></div>
    <div class="fld"><label for="gop">Земля непрозрачна на <b id="gopV">${Math.round(o.groundOpacity * 100)}%</b></label><input type="range" id="gop" min="0" max="95" value="${Math.round(o.groundOpacity * 100)}">
      <small>Меньше — лучше видно трубы сквозь землю. Рентген (X) делает землю и здания прозрачными сразу.</small></div>
    <div class="fld"><label>Здания</label><div class="seg" id="bld"><button data-b="solid">Объёмом</button><button data-b="flat">Контуром</button><button data-b="hidden">Скрыть</button></div></div>
    <div class="fld"><label>Цвет новой сети</label><div class="seg" id="cby"><button data-c="dn">По диаметру</button><button data-c="status">Проблемы</button><button data-c="depth">Глубина</button></div>
      <div id="cbyLeg" class="mut" style="font-size:12px;margin-top:6px"></div></div>
    <h2>Новая сеть</h2>
    ${tog('trace', 'Проекция трассы на землю (пунктир)', '#67e8f9')}
    ${tog('oks', 'Здания к подключению (булавки)', PALETTE.connected, M.oks.length, true)}
    <h2>Под землёй</h2>
    ${Object.entries(M.utilities).map(([k, l]) => tog('u:' + k, UTILITIES[k].name + (UTILITIES[k].assumed ? ' <span class="mut">(глубина условно)</span>' : ''), UTILITIES[k].color, l.length)).join('')}
    ${M.points.heat_chamber?.length ? tog('n:heat_chamber', 'Существующие камеры', PALETTE.chamberOld, M.points.heat_chamber.length, true) : ''}
    <h2>На поверхности</h2>
    ${tog('ground', 'Земля с сеткой 10/100 м', '#334155')}
    ${nb ? tog('buildings', 'Здания', '#8b9ab0', nb) : ''}
    ${surf.map(k => tog('p:' + k, (SURFACES[k] || {name: k}).name, (SURFACES[k] || {}).color || '#64748b', M.surfaces[k].length)).join('')}
    <h2>Глубины коммуникаций</h2>
    <table>${Object.entries(UTILITIES).filter(([k]) => M.utilities[k]).map(([k, u]) => `<tr><td><i class="sw" style="background:${u.color}"></i> ${u.name}</td><td>верх ${fmt(u.top, 1)} м${u.clear != null ? `, зазор ≥ ${fmt(u.clear, 1)}` : ' (условно)'}</td></tr>`).join('')}</table>`;
  const legend = () => {
    $('#cbyLeg').innerHTML = o.colorBy === 'dn' ? `<div style="height:8px;border-radius:4px;background:linear-gradient(90deg,${grad.join(',')})"></div><div style="display:flex;justify-content:space-between"><span>Ду 50</span><span>Ду 400</span><span>Ду 1400</span></div><div><i class="sw" style="background:${PALETTE.special}"></i> спецпереход</div>`
      : o.colorBy === 'status' ? `<i class="sw" style="background:${PALETTE.ok}"></i> без проблем <i class="sw" style="background:${PALETTE.problem};margin-left:10px"></i> есть проблема`
        : '<div style="height:8px;border-radius:4px;background:linear-gradient(90deg,#22d3ee,#a3e635,#facc15,#f97316)"></div><div style="display:flex;justify-content:space-between"><span>1 м</span><span>3 м</span><span>5 м и глубже</span></div>';
  };
  const mark = () => { $$('#bld button').forEach(b => b.classList.toggle('on', b.dataset.b === o.buildings)); $$('#cby button').forEach(b => b.classList.toggle('on', b.dataset.c === o.colorBy)); legend(); };
  mark();
  $('#ex').oninput = e => { $('#exV').textContent = e.target.value; };
  $('#ex').onchange = e => setEx(+e.target.value);
  $('#gop').oninput = e => { o.groundOpacity = e.target.value / 100; $('#gopV').textContent = e.target.value + '%'; viewer.applyOpts(); store.set('gop', o.groundOpacity); };
  $('#bld').onclick = e => { const b = e.target.closest('button'); if (!b) return; o.buildings = b.dataset.b; mark(); viewer.applyOpts(); store.set('bld', o.buildings); };
  $('#cby').onclick = e => { const b = e.target.closest('button'); if (!b) return; o.colorBy = b.dataset.c; mark(); viewer.buildNetwork(); refreshHighlight(); };
  el.onchange = e => { const k = e.target.dataset?.k; if (!k) return; L[k] = e.target.checked; viewer.applyOpts(); store.set('layers', L); };
}
function setEx(ex) {
  const P = rig.center(), oldEx = viewer.ex;
  viewer.setExaggeration(ex);
  // камера остаётся у той же точки сцены: пересчитываем её высоту под новый масштаб, если она под землёй
  if (rig.pos.z < 0) { rig.pos.z *= ex / oldEx; rig.apply(); }
  else if (P.z < 0) { const d = rig.pos.clone().sub(P); P.z *= ex / oldEx; rig.pos.copy(P).add(d); rig.apply(); }
  refreshHighlight();
  if ($('#ex')) { $('#ex').value = ex; $('#exV').textContent = ex; }
  store.set('ex', ex);
}

// ---------- выбор ----------
function clearSel() {
  S.sel = null; S.route = null;
  viewer.highlight(null);
  viewer.marker('selpt', null);
  $('#props').hidden = true;
  closeProfile();
  $$('.item.sel').forEach(x => x.classList.remove('sel'));
  scheduleHash();
}
function refreshHighlight() {
  if (S.route) { viewer.highlight(S.route, S.sel?.kind === 'seg' ? S.sel.ref : null); }
  else if (S.sel) highlightOther(S.sel);
  if (S.sel?.kind === 'crossing') { const c = S.sel.ref; viewer.marker('selpt', new THREE.Vector3(c.x, c.y, viewer.z(c.hn + c.H / 2)), {r: 0.9, color: c.ok === false ? PALETTE.problem : PALETTE.ok}); }
}
function routeFor(sel) {
  const v = S.v;
  if (!v) return null;
  if (sel.kind === 'seg') return routeTo(v, branchEnd(v, sel.ref));
  if (sel.kind === 'oks') return v.connected.has(sel.ref.id) ? routeTo(v, sel.ref.id) : null;
  if (sel.kind === 'node') return routeTo(v, sel.ref.id) || (v.segs.find(s => s.from === sel.ref.id) ? routeTo(v, branchEnd(v, v.segs.find(s => s.from === sel.ref.id))) : null);
  if (sel.kind === 'crossing') return routeTo(v, branchEnd(v, sel.ref.seg));
  return null;
}
function select(sel, {fly = false} = {}) {
  stopFly();
  S.sel = sel;
  S.route = routeFor(sel);
  if (S.route) {
    const o = S.model.oks.find(x => x.id === S.route.end);
    S.route.endName = o ? (o.name || 'Здание ' + (o.oksId || o.id)) : null;
  }
  viewer.highlight(S.route, sel.kind === 'seg' ? sel.ref : null);
  if (!S.route) highlightOther(sel);
  viewer.marker('selpt', null);
  showProps(sel);
  if (S.route) openProfile(); else closeProfile();
  if (fly) frameSel();
  $$('.item.sel').forEach(x => x.classList.remove('sel'));
  scheduleHash();
}
function highlightOther(sel) {
  const ex = viewer.ex;
  viewer.highlight(null);
  const segs = (pts, z) => { const out = []; for (let i = 0; i + 1 < pts.length; i++) out.push(pts[i][0], pts[i][1], z, pts[i + 1][0], pts[i + 1][1], z); return out; };
  if (sel.kind === 'utility') { const ln = sel.ref; viewer.overlayLines(segs(ln.pts, -(ln.top + (ln.h || 0.3) / 2) * ex), {width: 5}); }
  if (sel.kind === 'surface') { const it = sel.ref, r = it.rings[0]; const H = BUILDING_KEYS.has(sel.type) && viewer.opts.buildings === 'solid' ? buildingHeight(it) + 0.3 : 0.4; viewer.overlayLines(segs([...r, r[0]], H), {width: 3}); }
  if (sel.kind === 'node' || sel.kind === 'chamber') { const p = sel.ref.xy; viewer.marker('selpt', new THREE.Vector3(p[0], p[1], viewer.z(sel.ref.depth ?? 3.2)), {r: 1.5}); }
  if (sel.kind === 'oks') { const o = sel.ref; viewer.marker('selpt', new THREE.Vector3(o.xy[0], o.xy[1], (o.building ? buildingHeight(o.building) : 12) + 10.2), {r: 3.2}); }
}
function selectCrossing(c) {
  const r0 = routeFor({kind: 'crossing', ref: c});
  S.focusAt = r0 && c.type !== 'segment' ? (r0.segs.find(q => q === c.seg)?.s0 ?? 0) + c.s : null;
  select({kind: 'crossing', ref: c});
  viewer.marker('selpt', new THREE.Vector3(c.x, c.y, viewer.z(c.type === 'segment' ? c.seg.d0 : c.hn + c.H / 2)), {r: 0.9, color: c.ok === false ? PALETTE.problem : PALETTE.ok});
  // смотрим сбоку, поперёк новой трубы, чуть сверху — видно, кто над кем
  const s = c.seg;
  let dir = [1, 0];
  for (let i = 1; i < s.pts.length; i++) if (s.lens[i] >= c.s - 1e-6) { const a = s.pts[i - 1], b = s.pts[i], l = Math.hypot(b[0] - a[0], b[1] - a[1]) || 1; dir = [(b[0] - a[0]) / l, (b[1] - a[1]) / l]; break; }
  const yaw = Math.atan2(dir[0], dir[1]) + Math.PI / 2;
  const depth = c.type === 'segment' ? s.d0 : c.hn + c.H / 2;
  rig.lookAtFrom(new THREE.Vector3(c.x, c.y, viewer.z(depth)), yaw, -14 * D, 16 + 2 * viewer.ex);
}
function lookAtPipe(p, dist = 20) {
  const yaw = Math.atan2(p.dir[0], p.dir[1]) + Math.PI / 2;
  rig.lookAtFrom(new THREE.Vector3(p.x, p.y, viewer.z(p.d + p.seg.row.h / 2)), yaw, -18 * D, dist);
}
function selBox(sel = S.sel) {
  if (!sel) return null;
  const ex = viewer.ex, box = [Infinity, Infinity, Infinity, -Infinity, -Infinity, -Infinity];
  const add = (x, y, z) => { box[0] = Math.min(box[0], x); box[1] = Math.min(box[1], y); box[2] = Math.min(box[2], z); box[3] = Math.max(box[3], x); box[4] = Math.max(box[4], y); box[5] = Math.max(box[5], z); };
  if (S.route) for (const s of S.route.segs) s.pts.forEach((p, i) => add(p[0], p[1], -(s.depth[i] + s.row.h) * ex));
  if (sel.kind === 'utility') sel.ref.pts.forEach(p => add(p[0], p[1], -sel.ref.top * ex));
  if (sel.kind === 'surface') sel.ref.rings[0].forEach(p => add(p[0], p[1], BUILDING_KEYS.has(sel.type) ? buildingHeight(sel.ref) : 0));
  if (sel.kind === 'oks') { const o = sel.ref; add(o.xy[0], o.xy[1], 0); add(o.xy[0] + 15, o.xy[1] + 15, o.building ? buildingHeight(o.building) : 12); add(o.xy[0] - 15, o.xy[1] - 15, 0); }
  if (sel.kind === 'node' || sel.kind === 'chamber' || sel.kind === 'source') { const p = sel.ref.xy; add(p[0] - 8, p[1] - 8, -4 * ex); add(p[0] + 8, p[1] + 8, 2); }
  if (sel.kind === 'crossing') { const c = sel.ref; add(c.x - 6, c.y - 6, viewer.z(c.top ?? 3) - 2); add(c.x + 6, c.y + 6, 0); }
  if (!Number.isFinite(box[0])) return null;
  add(box[0], box[1], 0);
  return box;
}
function frameSel() {
  if (S.sel?.kind === 'crossing') return selectCrossing(S.sel.ref);
  const b = selBox();
  if (b) rig.frame(b, {pitch: Math.min(rig.pitch, -35 * D) < -80 * D ? -60 * D : Math.min(rig.pitch, -35 * D)}); else home();
}

// ---------- карточка свойств ----------
const ND = n => n == null ? '—' : fmt(n, 2) + ' м';
function describe(sel) {
  const M = S.model, v = S.v, r = sel.ref;
  switch (sel.kind) {
    case 'seg': {
      const s = r, bad = s.cx.filter(c => c.ok === false);
      return {kind: 'Новая труба', title: `Участок ${s.id}`, rows: [['Диаметр', `Ду ${s.p.diameter}`], ['Длина', meters(s.p.length ?? s.len)], ['Прокладка', s.special ? 'спецпереход (футляр)' : 'обычная, в канале'],
        ['Глубина верха', s.d0 === s.d1 ? ND(s.d0) : `${ND(s.d0)} → ${ND(s.d1)}`], ['Уклон', fmt(Math.abs(s.d1 - s.d0) / s.len, 3)], ['Расход', s.p.flow_tph != null ? fmt(s.p.flow_tph, 2) + ' т/ч' : '—'],
        ['Стоимость', rub(s.p.cost)], ['Откуда → куда', `${s.from} → ${s.to}`], ['Пересечений', s.cx.length]],
        problem: s.issues.length || bad.length ? [...s.issues, ...bad.map(c => `${UTILITIES[c.type].name.toLowerCase()}: зазор ${fmt(c.gap, 2)} м, нужно ${fmt(c.need, 2)} м`)].join('<br>') : null};
    }
    case 'oks': {
      const conn = v ? v.connected.has(r.id) : null, R = S.route;
      const rows = [['Номер', r.oksId || r.id], ['Точка подключения', r.id], ['Нагрузка', r.flow != null ? fmt(r.flow, 2) + ' т/ч' : '—']];
      if (r.building) rows.push(['Этажей', r.building.p.levels ?? '—']);
      if (v) rows.push(['Подключено', conn ? 'да' : 'нет']);
      if (R) rows.push(['Трасса от врезки', meters(R.len)], ['Участков', R.segs.length], ['Стоимость трассы', rub(R.cost)], ['Ду на вводе', `Ду ${R.segs[R.segs.length - 1].p.diameter}`], ['Врезка', R.start]);
      return {kind: 'Здание к подключению', title: r.name || `Здание ${r.oksId || r.id}`, rows,
        problem: v && !conn ? 'Здание не подключено в этом варианте — за него начисляется штраф. Посмотрите другие варианты.' : R?.bad ? `На трассе ${R.bad} ${plural(R.bad, 'проблема', 'проблемы', 'проблем')} по высоте — см. профиль внизу.` : null,
        ok: R && !R.bad ? 'Трасса без проблем по высоте' : null};
    }
    case 'crossing': {
      const c = r;
      if (c.type === 'segment') return {kind: 'Нарушение на участке', title: `Участок ${c.eid}`, rows: [['Глубина', `${ND(c.seg.d0)} → ${ND(c.seg.d1)}`], ['Длина', meters(c.seg.len)]], problem: c.issues.join('<br>')};
      const u = UTILITIES[c.type];
      const rows = [['Пересекает', `${u.name} № ${c.eid}`], ['Существующая лежит', `${fmt(c.top, 2)}–${fmt(c.top + c.eh, 2)} м`], ['Новая труба лежит', `${fmt(c.hn, 2)}–${fmt(c.hn + c.H, 2)} м`],
        ['Новая проходит', c.side], ['Зазор сейчас', fmt(c.gap, 2) + ' м'], ['Нужно не меньше', c.need == null ? 'правила нет' : fmt(c.need, 2) + ' м'], ['Участок', c.seg.id]];
      let problem = null, fix = null;
      if (c.ok === false) {
        problem = `Новая труба проходит слишком близко к коммуникации «${u.name.toLowerCase()}»: зазор ${fmt(c.gap, 2)} м, а нужно ${fmt(c.need, 2)} м.`;
        fix = [c.above != null ? `<b>Поднять</b> трубу: верх не глубже ${fmt(c.above, 2)} м — без доплаты.` : 'Поднять нельзя: выйдет мельче 0,7 м.',
          `<b>Опустить</b> трубу: верх не мельче ${fmt(c.below, 2)} м — ${c.kBelow > 1 ? `дороже на ${fmt((c.kBelow - 1) * 100, 1)} % (Kгл ${fmt(c.kBelow, 3)})` : 'без доплаты'}.`].join('<br>');
      }
      return {kind: 'Пересечение', title: u.name, rows, problem, fix, ok: c.ok === true ? 'Зазор достаточный' : c.ok == null ? 'Для этой коммуникации зазор правилами не задан, глубина показана условно' : null};
    }
    case 'node': {
      const n = r, t = n.type === 'existing_chamber' ? 'Существующая камера (врезка)' : n.type === 'oks_entry' ? 'Ввод в здание' : (NODE_TYPES[n.type] || {name: n.type}).name;
      const segs = v ? v.segs.filter(s => s.from === n.id || s.to === n.id) : [];
      return {kind: t, title: String(n.id), rows: [['Глубина низа трубы', ND(n.depth)], ['Ду', n.dn || n.p.diameter || '—'], ['Участков в узле', segs.length], ...(n.p.cost ? [['Стоимость', rub(n.p.cost)]] : []),
        ...(n.p.existing_object_id ? [['На существующем объекте', n.p.existing_object_id]] : [])]};
    }
    case 'utility': {
      const u = UTILITIES[sel.type], ln = r;
      return {kind: 'Существующая коммуникация', title: `${u.name} № ${ln.id}`, rows: [['Глубина верха', fmt(ln.top, 1) + ' м' + (u.assumed ? ' (условно)' : '')], ['Высота габарита', ND(ln.h)], ...(ln.dn ? [['Ду', ln.dn]] : []),
        ['Нужный зазор с новой трубой', u.clear == null ? 'правилами не задан' : fmt(u.clear, 1) + ' м'], ['Длина', meters(ln.pts.reduce((a, p, i) => i ? a + Math.hypot(p[0] - ln.pts[i - 1][0], p[1] - ln.pts[i - 1][1]) : 0, 0))]]};
    }
    case 'chamber': return {kind: 'Существующая камера', title: `Камера ${r.id}`, rows: [['Номер', r.id], ...Object.entries(r.p).filter(([k, x]) => !k.startsWith('_') && !['id', 'object_type'].includes(k) && typeof x !== 'object').map(([k, x]) => [k, String(x)])]};
    case 'source': return {kind: 'Источник тепла', title: r.p.name || `Источник ${r.id}`, rows: [['Номер', r.id]]};
    case 'surface': {
      const it = r, name = (SURFACES[sel.type] || {name: sel.type}).name, b = BUILDING_KEYS.has(sel.type);
      const rows = [['Номер', it.id]];
      if (it.p.address) rows.push(['Адрес', it.p.address]);
      if (b) rows.push(['Этажей', it.p.levels ?? '—'], ['Высота в 3D', meters(buildingHeight(it))]);
      if (it.p.name) rows.push(['Название', it.p.name]);
      if (sel.type === 'road' || sel.type === 'tram_tracks') rows.push(['Под ним верх трубы', `не мельче ${sel.type === 'road' ? '1,0' : '1,2'} м, переход спецпрокладкой`]);
      return {kind: b ? (it.isFuture ? 'Новое здание' : 'Здание') : 'Поверхность', title: b ? (it.p.address || `${name}: ${it.id}`) : name, rows};
    }
  }
  return {kind: '', title: '', rows: []};
}
function showProps(sel) {
  const d = describe(sel), el = $('#props');
  el.hidden = false;
  const hasRoute = !!S.route;
  el.innerHTML = `<div class="kind">${esc(d.kind)}</div>
    <div class="h"><b>${esc(d.title)}</b><button class="icon" id="pxX" title="Закрыть (Esc)" aria-label="Закрыть">×</button></div>
    <div class="acts">
      <button id="pxGo" class="primary" title="Навести камеру (F)">🎯 Показать</button>
      <button id="pxClip" title="Срез вокруг (B)">✂️ Срез вокруг</button>
      ${hasRoute ? '<button id="pxFly" class="wide" title="Пролёт камерой вдоль трассы (P)">▶ Пролёт по трассе до здания</button>' : ''}
      <button id="pxLink" class="wide">🔗 Скопировать ссылку</button>
    </div>
    <table>${d.rows.map(r => `<tr><td>${esc(r[0])}</td><td>${esc(r[1])}</td></tr>`).join('')}</table>
    ${d.problem ? `<div class="note prob">⚠️ ${d.problem}</div>` : ''}${d.fix ? `<div class="note fix"><b>Как исправить</b><br>${d.fix}</div>` : ''}${d.ok ? `<div class="note fix">✓ ${esc(d.ok)}</div>` : ''}`;
  $('#pxX').onclick = () => clearSel();
  $('#pxGo').onclick = () => frameSel();
  $('#pxClip').onclick = () => toggleClip(true);
  if (hasRoute) $('#pxFly').onclick = () => startFly();
  $('#pxLink').onclick = e => copyLink(e.target);
}

// ---------- профиль ----------
function openProfile() {
  const R = S.route;
  $('#profile').hidden = false;
  const o = S.model.oks.find(x => x.id === R.end);
  $('#pTitle').innerHTML = `Профиль: врезка ${esc(R.start)} → ${esc(o ? (o.name || 'здание ' + (o.oksId || o.id)) : R.end)} · ${meters(R.len)} · ${rub(R.cost)} · ` +
    (R.bad ? `<span style="color:#fda4af">${R.bad} ${plural(R.bad, 'проблема', 'проблемы', 'проблем')}</span>` : '<span style="color:#86efac">без проблем</span>');
  const at = S.focusAt;
  S.focusAt = null;
  requestAnimationFrame(() => { profile.show(R, S.model); if (at != null) { profile.zoomTo(at, Math.min(60, R.len)); profile.setCursor(at); } });
}
function closeProfile() { $('#profile').hidden = true; profile.show(null); viewer.marker('pcur', null); }
$('#pClose').onclick = () => closeProfile();
$('#pFly').onclick = () => startFly();
{
  let drag = null;
  $('#pGrip').addEventListener('pointerdown', e => { drag = {y: e.clientY, h: $('#profile').offsetHeight}; e.target.setPointerCapture(e.pointerId); });
  $('#pGrip').addEventListener('pointermove', e => { if (drag) $('#profile').style.height = clamp(drag.h - (e.clientY - drag.y), 140, innerHeight * 0.7) + 'px'; });
  $('#pGrip').addEventListener('pointerup', () => { drag = null; profile.draw(); });
}

// ---------- щелчки и наведение ----------
function hitToSel(h) {
  if (!h) return null;
  if (h.kind === 'seg') return {kind: 'seg', ref: h.ref};
  if (h.kind === 'node') return {kind: 'node', ref: h.ref};
  if (h.kind === 'utility') return {kind: 'utility', type: h.type, ref: h.ref};
  if (h.kind === 'oks') return {kind: 'oks', ref: h.ref};
  if (h.kind === 'chamber') return {kind: 'chamber', ref: h.ref};
  if (h.kind === 'source') return {kind: 'source', ref: h.ref};
  if (h.kind === 'surface') {
    // щелчок по зданию к подключению — как по булавке
    const o = S.model.oks.find(x => x.building === h.ref);
    if (o) return {kind: 'oks', ref: o};
    return {kind: 'surface', type: h.type, ref: h.ref};
  }
  return null;
}
function clickAt(x, y) {
  hideCtx();
  if (S.measure) return measureClick(x, y);
  const h = pickAt(x, y), sel = hitToSel(h);
  if (!sel) { clearSel(); return; }
  select(sel);
}
function dblAt(x, y) {
  const P = rig.pivotAt(x, y);
  rig.animateTo({pos: P.clone().add(rig.pos.clone().sub(P).multiplyScalar(0.4)), yaw: rig.yaw, pitch: rig.pitch}, 450);
}
let hoverRaf = 0, hoverXY = null;
function hoverAt(x, y, dragging) {
  hoverXY = dragging ? null : [x, y];
  if (dragging) { $('#tip').hidden = true; return; }
  if (hoverRaf) return;
  hoverRaf = requestAnimationFrame(() => {
    hoverRaf = 0;
    if (!hoverXY || !S.model) return;
    const [hx, hy] = hoverXY, h = pickAt(hx, hy);
    const P = h?.point || rig.onPlane(hx, hy, 0, 1e6);
    if (P) { const [lon, lat] = S.model.proj.ll(P.x, P.y); $('#stXY').textContent = `${lat.toFixed(6)}, ${lon.toFixed(6)}`; }
    const t = h ? hoverText(h) : null;
    $('#stHover').innerHTML = t ? t.status : '';
    const tip = $('#tip'), r = $('#view').getBoundingClientRect();
    if (!t || S.measure) { tip.hidden = true; viewer.renderer.domElement.style.cursor = S.measure ? 'crosshair' : ''; return; }
    tip.hidden = false;
    tip.innerHTML = `<b>${t.title}</b>${t.lines.map(l => `<div>${l}</div>`).join('')}<div class="m">щёлкните, чтобы выбрать</div>`;
    const tx = r.left + hx + 16, ty = r.top + hy + 16;
    tip.style.left = Math.min(tx, innerWidth - tip.offsetWidth - 8) + 'px';
    tip.style.top = Math.min(ty, innerHeight - tip.offsetHeight - 8) + 'px';
    viewer.renderer.domElement.style.cursor = 'pointer';
  });
}
$('#view').addEventListener('pointerleave', () => { hoverXY = null; $('#tip').hidden = true; });
function hoverText(h) {
  const r = h.ref;
  if (h.kind === 'seg') {
    const d = (h.point.z / -viewer.ex) - r.row.h / 2;
    return {title: `Новая труба Ду ${r.p.diameter}`, lines: [`участок ${esc(r.id)} · ${meters(r.len)}`, `верх на глубине ${fmt(Math.max(0, d), 2)} м${r.special ? ' · спецпереход' : ''}`, r.bad ? '<span style="color:#fda4af">⚠ есть проблема</span>' : ''].filter(Boolean),
      status: `<b>Новая труба</b> ${esc(r.id)} · Ду ${r.p.diameter} · верх ${fmt(Math.max(0, d), 2)} м`};
  }
  if (h.kind === 'utility') { const u = UTILITIES[h.type]; return {title: u.name, lines: [`№ ${esc(r.id)} · верх ${fmt(r.top, 1)} м${u.assumed ? ' (условно)' : ''}`], status: `<b>${u.name}</b> ${esc(r.id)} · верх ${fmt(r.top, 1)} м`}; }
  if (h.kind === 'oks') { const c = S.v ? S.v.connected.has(r.id) : null; return {title: esc(r.name || 'Здание ' + (r.oksId || r.id)), lines: [`нагрузка ${fmt(r.flow, 1)} т/ч`, c == null ? '' : c ? '<span style="color:#86efac">подключено</span>' : '<span style="color:#fda4af">не подключено</span>'].filter(Boolean), status: `<b>Здание</b> ${esc(r.oksId || r.id)}`}; }
  if (h.kind === 'node') { const t = r.type === 'existing_chamber' ? 'Врезка в существующую камеру' : r.type === 'oks_entry' ? 'Ввод в здание' : (NODE_TYPES[r.type] || {name: r.type}).name; return {title: t, lines: [`${esc(r.id)} · низ ${ND(r.depth)}`], status: `<b>${t}</b> ${esc(r.id)}`}; }
  if (h.kind === 'chamber') return {title: 'Существующая камера', lines: [esc(r.id)], status: `<b>Камера</b> ${esc(r.id)}`};
  if (h.kind === 'source') return {title: 'Источник тепла', lines: [esc(r.p.name || r.id)], status: `<b>Источник</b> ${esc(r.p.name || r.id)}`};
  if (h.kind === 'surface') {
    const n = (SURFACES[h.type] || {name: h.type}).name;
    if (BUILDING_KEYS.has(h.type)) return {title: esc(r.p.address || (r.isFuture ? 'Новое здание' : 'Здание')), lines: [`${r.p.levels ? r.p.levels + ' эт. · ' : ''}${esc(r.id)}`], status: `<b>Здание</b> ${esc(r.id)}`};
    return {title: n, lines: [h.type === 'road' ? 'трубу под дорогой — не мельче 1,0 м' : h.type === 'tram_tracks' ? 'под трамваем — не мельче 1,2 м' : esc(r.id)], status: n};
  }
  return null;
}

// ---------- контекстное меню (как правый щелчок в Картах) ----------
function showCtx(x, y) {
  if (!S.model) return;
  const h = pickAt(x, y), P = h?.point || rig.pivotAt(x, y), [lon, lat] = S.model.proj.ll(P.x, P.y);
  const sel = hitToSel(h), menu = $('#ctx');
  const coords = `${lat.toFixed(6)}, ${lon.toFixed(6)}`;
  menu.innerHTML = `${sel ? `<button data-c="sel">🔎 Что здесь: ${esc(describe(sel).title).slice(0, 40)}</button>` : ''}
    <button data-c="zoom">➕ Приблизить сюда</button>
    <button data-c="top">⬇ Смотреть сверху сюда</button>
    <button data-c="side">↔ Смотреть сбоку на эту точку</button>
    <button data-c="clip">✂️ Срез вокруг этой точки</button>
    <button data-c="measure">📏 Измерить расстояние отсюда</button>
    <hr><button data-c="copy" title="Скопировать координаты">📋 ${coords}</button>
    <div class="lbl">${P.z < -0.05 ? `глубина ${fmt(-P.z / viewer.ex, 2)} м` : 'на поверхности'}</div>`;
  const r = $('#view').getBoundingClientRect();
  menu.hidden = false;
  menu.style.left = Math.min(x, r.width - menu.offsetWidth - 8) + 'px';
  menu.style.top = Math.min(y, r.height - menu.offsetHeight - 8) + 'px';
  menu.onclick = e => {
    const b = e.target.closest('button');
    if (!b) return;
    hideCtx();
    const c = b.dataset.c, dist = rig.pos.distanceTo(P);
    if (c === 'sel') select(sel, {fly: false});
    if (c === 'zoom') rig.animateTo({pos: P.clone().add(rig.pos.clone().sub(P).multiplyScalar(0.35)), yaw: rig.yaw, pitch: rig.pitch}, 500);
    if (c === 'top') rig.lookAtFrom(P, rig.yaw, -90 * D, Math.max(60, dist * 0.7));
    if (c === 'side') rig.lookAtFrom(P, rig.yaw, -8 * D, Math.max(18, Math.min(dist, 80)));
    if (c === 'clip') setClip(boxAround(P, 40));
    if (c === 'measure') { setMeasure(true); measureAdd(P); }
    if (c === 'copy') { navigator.clipboard?.writeText(coords).then(() => toast('Координаты скопированы', 'ok', 1500)).catch(() => toast(coords)); }
  };
}
function hideCtx() { $('#ctx').hidden = true; }
addEventListener('pointerdown', e => { if (!e.target.closest('#ctx')) hideCtx(); if (!e.target.closest('#fileDd')) closeMenu(); if (!e.target.closest('.search')) $('#sugg').hidden = true; }, true);

// ---------- вид ----------
function homeBox() {
  const M = S.model;
  if (S.v && S.v.segs.length) {
    const b = [Infinity, Infinity, -Infinity, -Infinity];
    for (const s of S.v.segs) for (const p of s.pts) { b[0] = Math.min(b[0], p[0]); b[1] = Math.min(b[1], p[1]); b[2] = Math.max(b[2], p[0]); b[3] = Math.max(b[3], p[1]); }
    for (const o of M.oks) { b[0] = Math.min(b[0], o.xy[0]); b[1] = Math.min(b[1], o.xy[1]); b[2] = Math.max(b[2], o.xy[0]); b[3] = Math.max(b[3], o.xy[1]); }
    return [b[0], b[1], -10, b[2], b[3], 20];
  }
  const [x0, y0, x1, y1] = M.bbox;
  return [x0, y0, -10, x1, y1, 20];
}
// Исходный вид: вся новая сеть с зданиями, на север, наклон 52° — как «Домой» у ViewCube
function home(ms = 800) {
  if (S.model) rig.frame(homeBox(), {yaw: 0, pitch: -52 * D, ms, pad: 1.05});
}
function orient(dir, name) {
  const P = rig.center(), dist = Math.max(20, rig.pos.distanceTo(P));
  const f = dir.clone().negate();
  const pitch = Math.asin(clamp(f.z, -1, 1));
  const yaw = Math.abs(f.z) > 0.999 ? rig.yaw : Math.atan2(f.x, f.y);
  rig.lookAtFrom(P, yaw, pitch, dist);
  status(name);
}
function toggle2d() {
  const P = rig.center(), dist = Math.max(30, rig.pos.distanceTo(P));
  if (rig.pitch < -80 * D) rig.lookAtFrom(P, rig.yaw, -45 * D, dist); else rig.lookAtFrom(P, rig.yaw, -90 * D, dist);
}
function updateModeUi() {
  const fly = rig.mode === 'fly';
  $('#modeBtn').classList.toggle('on', fly);
  $('#modeT').textContent = fly ? 'Полёт' : 'Обзор';
  $('#modeIco').innerHTML = fly ? '<path d="M2 12 22 3l-6 19-4-8z"/><path d="m12 14 4-4"/>' : '<path d="M21 12a9 9 0 1 1-3-6.7"/><path d="M21 4v5h-5"/>';
  status();
}
function setMode(m) { rig.mode = m; updateModeUi(); toast(m === 'fly' ? 'Режим «Полёт»: мышь — осмотреться, W A S D — лететь, Q/E — вниз/вверх' : 'Режим «Обзор»: левая — двигать, правая — вращать', 'info', 2600); }
function status(extra) {
  const fly = rig.mode === 'fly';
  $('#stHint').innerHTML = extra ? `<b>${esc(extra)}</b>` : S.measure ? '<b>Линейка:</b> щёлкните две точки · Esc — выйти'
    : fly ? '<span class="chip fly">Полёт</span> <b>мышь</b> осмотреться · <b>WASD</b> лететь · <b>Q/E</b> вниз/вверх · <b>Shift</b> быстрее'
      : '<span class="chip">Обзор</span> <b>левая</b> двигать · <b>правая</b> вращать · <b>колесо</b> масштаб · <b>WASD</b> лететь';
  if (extra) { clearTimeout(status.t); status.t = setTimeout(() => status(), 2200); }
}
function updateScale() {
  const r = $('#view').getBoundingClientRect();
  if (!r.width) return;
  const cam = rig.pos, P = rig.onPlane(r.width / 2, r.height / 2, 0, 1e6);
  const dist = P ? cam.distanceTo(P) : Math.abs(cam.z) + 10;
  const mpp = dist * 2 * Math.tan(viewer.camera.fov * D / 2) / r.height;
  const target = mpp * 110, mag = Math.pow(10, Math.floor(Math.log10(target)));
  const nice = [1, 2, 5, 10].map(k => k * mag).filter(v => v <= target).pop() || mag;
  $('#scale .bar').style.width = Math.round(nice / mpp) + 'px';
  $('#scale span').textContent = meters(nice);
  $('#stCam').textContent = cam.z >= 0 ? `камера ${meters(cam.z)} над землёй` : `камера под землёй, ${fmt(-cam.z / viewer.ex, 1)} м`;
  $('#v2dT').textContent = rig.pitch < -80 * D ? '3D' : '2D';
}

// ---------- рентген, срез, линейка ----------
function setXray(on) { S.xray = on; viewer.opts.xray = on; viewer.applyOpts(); $('#xrayBtn').classList.toggle('on', on); }
function boxAround(P, r) { return [P.x - r, P.y - r, Math.min(P.z, 0) - 12 * viewer.ex - 2, P.x + r, P.y + r, Math.max(P.z, 0) + 60]; }
function setClip(box) {
  S.clip = box;
  viewer.setClipBox(box);
  $('#clipBtn').classList.toggle('on', !!box);
  if (box) status('Срез включён — видно только то, что внутри рамки. B — выключить');
}
function toggleClip(force) {
  if (S.clip && !force) return setClip(null);
  let b = selBox();
  if (b) { const m = 15; b = [b[0] - m, b[1] - m, Math.min(b[2], 0) - 6 * viewer.ex, b[3] + m, b[4] + m, Math.max(b[5], 0) + 40]; }
  else b = boxAround(rig.center(), 60);
  setClip(b);
}
function setMeasure(on) {
  if (on) { S.measure = {pts: []}; $('#measure').hidden = false; $('#measure').innerHTML = '<span>📏 Щёлкните первую точку — трубу, здание или землю</span><button id="mX">Готово</button>'; $('#mX').onclick = () => setMeasure(false); }
  else { S.measure = null; $('#measure').hidden = true; viewer.marker('m0', null); viewer.marker('m1', null); const l = viewer.g.tools.getObjectByName('mline'); if (l) { viewer.g.tools.remove(l); l.traverse(x => { if (x.isCSS2DObject) x.element.remove(); }); } }
  $('#measureBtn').classList.toggle('on', on);
  status();
  viewer.redraw();
}
function measureClick(x, y) { measureAdd(pickAt(x, y)?.point || rig.pivotAt(x, y)); }
function measureAdd(P) {
  const m = S.measure;
  if (m.pts.length >= 2) { m.pts = []; viewer.marker('m1', null); }
  m.pts.push(P.clone());
  viewer.marker('m' + (m.pts.length - 1), P, {color: '#22d3ee', r: 0.7});
  const old = viewer.g.tools.getObjectByName('mline');
  if (old) { viewer.g.tools.remove(old); old.traverse(x => { if (x.isCSS2DObject) x.element.remove(); }); }
  if (m.pts.length === 1) { $('#measure').firstElementChild.textContent = '📏 Теперь вторую точку'; return; }
  const [a, b] = m.pts, ex = viewer.ex;
  const plan = Math.hypot(b.x - a.x, b.y - a.y), dz = (b.z - a.z) / (b.z <= 0 && a.z <= 0 ? ex : 1), d3 = Math.hypot(plan, dz);
  const geo = new THREE.BufferGeometry().setFromPoints([a, b]);
  const line = new THREE.Line(geo, new THREE.LineBasicMaterial({color: 0x22d3ee, depthTest: false}));
  line.name = 'mline'; line.renderOrder = 60;
  const lb = viewer.label(`${meters(plan)}`, 'lbl meas');
  lb.position.copy(a.clone().add(b).multiplyScalar(0.5));
  line.add(lb);
  viewer.g.tools.add(line);
  $('#measure').firstElementChild.innerHTML = `📏 В плане <b>${meters(plan)}</b> · перепад <b>${fmt(Math.abs(dz), 2)} м</b> · по прямой <b>${meters(d3)}</b>`;
  viewer.redraw();
}

// ---------- пролёт по трассе ----------
function startFly() {
  const R = S.route;
  if (!R) return toast('Сначала выберите здание или трубу — пролёт идёт по её трассе', 'info');
  setClip(null);
  S.fly = {at: 0, playing: true, speed: +$('#plSpeed').value, last: performance.now()};
  $('#player').hidden = false;
  $('#plPlay').textContent = '❚❚';
  setXray(true);
  const step = now => {
    const F = S.fly;
    if (!F) return;
    const dt = Math.min(0.1, (now - F.last) / 1000);
    F.last = now;
    if (F.playing) { F.at = Math.min(R.len, F.at + dt * 9 * F.speed); if (F.at >= R.len) { F.playing = false; $('#plPlay').textContent = '▶'; } }
    flyPose(F.at);
    requestAnimationFrame(step);
  };
  requestAnimationFrame(step);
  status('Пролёт: пробел — пауза, Esc — выйти');
}
function flyPose(at) {
  const R = S.route, ex = viewer.ex;
  const p = pointAt(R, at), q = pointAt(R, Math.min(R.len, at + 14)), b = pointAt(R, Math.max(0, at - 9));
  const zc = d => -(d + p.seg.row.h / 2) * ex;
  const dir = new THREE.Vector3(q.x - b.x, q.y - b.y, 0);
  if (dir.lengthSq() < 1e-6) dir.set(p.dir[0], p.dir[1], 0);
  dir.normalize();
  const target = new THREE.Vector3(q.x, q.y, zc(q.d));
  const pos = new THREE.Vector3(b.x, b.y, zc(b.d) + 2.4 + ex * 0.4);
  const f = target.clone().sub(pos).normalize();
  const yaw = Math.atan2(f.x, f.y), pitch = Math.asin(clamp(f.z, -1, 1));
  // сглаживание курса — без рывков на изломах
  let dy = yaw - rig.yaw; dy = Math.atan2(Math.sin(dy), Math.cos(dy));
  rig.yaw += dy * 0.12; rig.pitch += (pitch - rig.pitch) * 0.12;
  rig.pos.lerp(pos, 0.25);
  rig.apply();
  profile.setCursor(at);
  $('#plPos').value = Math.round(at / R.len * 1000);
  $('#plTxt').textContent = `${fmt(at)} из ${fmt(R.len)} м · верх ${fmt(p.d, 2)} м · Ду ${p.seg.p.diameter}`;
}
function stopFly() {
  if (!S.fly) return;
  S.fly = null;
  $('#player').hidden = true;
  setXray(false);
  viewer.marker('pcur', null);
  profile.setCursor(null);
  status();
}
$('#plPlay').onclick = () => { if (!S.fly) return; if (S.fly.at >= S.route.len) S.fly.at = 0; S.fly.playing = !S.fly.playing; $('#plPlay').textContent = S.fly.playing ? '❚❚' : '▶'; };
$('#plPos').oninput = e => { if (!S.fly) return; S.fly.playing = false; $('#plPlay').textContent = '▶'; S.fly.at = e.target.value / 1000 * S.route.len; };
$('#plSpeed').onchange = e => { if (S.fly) S.fly.speed = +e.target.value; };
$('#plStop').onclick = () => stopFly();
// любое ручное управление камерой во время пролёта — пауза, а не борьба с пользователем
viewer.renderer.domElement.addEventListener('pointerdown', () => { if (S.fly?.playing) { S.fly.playing = false; $('#plPlay').textContent = '▶'; } });

// ---------- поиск ----------
let searchIdx = null;
function buildIndex() {
  const M = S.model, idx = [];
  for (const o of M.oks) idx.push({k: [o.id, o.oksId, o.name].filter(Boolean).join(' ').toLowerCase(), label: o.name || 'Здание ' + (o.oksId || o.id), sub: 'здание к подключению', sel: {kind: 'oks', ref: o}});
  for (const v of M.variants) {
    for (const s of v.segs) idx.push({k: s.id.toLowerCase(), label: `Участок ${s.id}`, sub: `труба Ду ${s.p.diameter}, вариант ${v.id}`, sel: {kind: 'seg', ref: s}, v: v.id});
    for (const n of v.nodes) if (!n.virtual) idx.push({k: String(n.id).toLowerCase(), label: String(n.id), sub: `${(NODE_TYPES[n.type] || {name: n.type}).name.toLowerCase()}, вариант ${v.id}`, sel: {kind: 'node', ref: n}, v: v.id});
  }
  for (const c of M.points.heat_chamber || []) idx.push({k: String(c.id).toLowerCase(), label: `Камера ${c.id}`, sub: 'существующая камера', sel: {kind: 'chamber', ref: c}});
  for (const [t, l] of Object.entries(M.utilities)) for (const ln of l) idx.push({k: String(ln.id).toLowerCase(), label: `${UTILITIES[t].name} ${ln.id}`, sub: 'существующая коммуникация', sel: {kind: 'utility', type: t, ref: ln}});
  for (const k of BUILDING_KEYS) for (const it of M.surfaces[k] || []) if (it.p.address) idx.push({k: (it.p.address + ' ' + it.id).toLowerCase(), label: it.p.address, sub: 'здание', sel: {kind: 'surface', type: k, ref: it}});
  return idx;
}
function searchFor(q) {
  q = q.trim().toLowerCase();
  if (!q || !S.model) return [];
  const m = q.match(/^(-?\d{1,3}[.,]\d+)\s*[,; ]\s*(-?\d{1,3}[.,]\d+)$/);
  if (m) {
    let a = +m[1].replace(',', '.'), b = +m[2].replace(',', '.');
    if (Math.abs(a) <= 90 && Math.abs(b) > 90 || (a > 40 && a < 80)) [a, b] = [a, b]; else [a, b] = [b, a];
    return [{label: `Координаты ${a}, ${b}`, sub: 'перелететь сюда', coords: [b, a]}];
  }
  searchIdx ||= buildIndex();
  const out = [], starts = [];
  for (const e of searchIdx) {
    const i = e.k.indexOf(q);
    if (i < 0) continue;
    if (e.v && S.v && e.v !== S.v.id) continue;
    (i === 0 || e.k.includes(' ' + q) ? starts : out).push(e);
    if (starts.length >= 12) break;
  }
  return starts.concat(out).slice(0, 12);
}
let suggs = [], sIdx = 0;
function renderSugg() {
  const box = $('#sugg');
  box.hidden = !suggs.length && !$('#q').value.trim();
  box.innerHTML = suggs.map((s, i) => `<button role="option" class="${i === sIdx ? 'act' : ''}" data-i="${i}"><span>${esc(s.label)}</span><small>${esc(s.sub)}</small></button>`).join('')
    || '<div class="mut" style="padding:8px 12px">Ничего не нашлось. Ищите по номеру здания, участка (v1_seg_…), камеры или по координатам «55.75, 37.62».</div>';
}
function goSugg(s) {
  if (!s) return;
  $('#sugg').hidden = true;
  $('#q').blur();
  if (s.coords) { const [x, y] = S.model.proj.xy(s.coords); rig.lookAtFrom(new THREE.Vector3(x, y, 0), rig.yaw, -55 * D, 220); return; }
  if (s.v && S.v?.id !== s.v) setVariant(s.v);
  select(s.sel, {fly: true});
  if (s.sel.kind === 'oks') openTab('oks');
}
$('#q').addEventListener('input', e => { suggs = searchFor(e.target.value); sIdx = 0; renderSugg(); });
$('#q').addEventListener('focus', e => { if (e.target.value) { suggs = searchFor(e.target.value); renderSugg(); } });
$('#q').addEventListener('keydown', e => {
  if (e.key === 'ArrowDown') { sIdx = Math.min(suggs.length - 1, sIdx + 1); renderSugg(); e.preventDefault(); }
  else if (e.key === 'ArrowUp') { sIdx = Math.max(0, sIdx - 1); renderSugg(); e.preventDefault(); }
  else if (e.key === 'Enter') { goSugg(suggs[sIdx]); }
  else if (e.key === 'Escape') { $('#sugg').hidden = true; e.target.blur(); }
});
$('#sugg').addEventListener('click', e => { const b = e.target.closest('button[data-i]'); if (b) goSugg(suggs[+b.dataset.i]); });

// ---------- вкладки и меню ----------
function openTab(t) {
  $$('#left .tabs button').forEach(b => { b.classList.toggle('on', b.dataset.tab === t); b.setAttribute('aria-selected', b.dataset.tab === t); });
  $$('#left .pane').forEach(p => { p.hidden = p.id !== 't-' + t; });
  document.body.classList.add('leftOpen');
}
$$('#left .tabs button').forEach(b => b.onclick = () => openTab(b.dataset.tab));
$('#leftBtn').onclick = () => document.body.classList.toggle('leftOpen');
function closeMenu() { $('#fileDd .menu').hidden = true; $('#fileBtn').setAttribute('aria-expanded', 'false'); }
$('#fileBtn').onclick = () => { const m = $('#fileDd .menu'); m.hidden = !m.hidden; $('#fileBtn').setAttribute('aria-expanded', String(!m.hidden)); };
$('#fileDd .menu').onclick = e => { const b = e.target.closest('button[data-act]'); if (!b) return; closeMenu(); menuAct(b.dataset.act); };
$('#dsBtn').onclick = () => openDlg('demo');
async function menuAct(a) {
  if (a === 'open') openDlg('demo');
  if (a === 'run' || a === 'jobs') openDlg('srv');
  if (a === 'png') { const blob = await viewer.screenshot(); download(blob, `теплосеть-3d-${S.ds?.name || 'вид'}.png`); }
  if (a === 'geojson') { if (!S.raw) return toast('Нет загруженного результата расчёта', 'info'); download(new Blob([S.raw], {type: 'application/geo+json'}), `${S.ds?.name || 'result'}.geojson`); }
  if (a === 'link') copyLink();
}
function download(blob, name) { const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = name; a.click(); setTimeout(() => URL.revokeObjectURL(a.href), 2000); }
function copyLink(btn) {
  writeHash();
  const done = () => { if (btn) { btn.textContent = '✓ Ссылка скопирована'; setTimeout(() => { btn.textContent = '🔗 Скопировать ссылку'; }, 1600); } else toast('Ссылка на вид скопирована', 'ok', 1500); };
  navigator.clipboard?.writeText(location.href).then(done).catch(() => toast(esc(location.href), 'info', 8000));
}

// ---------- диалог «Открыть данные» ----------
function openDlg(tab) {
  const dl = $('#openDlg');
  $('#demoList').innerHTML = demos.map(d => `<button type="button" class="item" data-n="${esc(d.name)}"><div class="t"><span>${esc(d.title)}</span>${S.ds?.name === d.name ? '<span class="badge b-ok">открыт</span>' : ''}</div><div class="m">${esc(d.input)}${d.output ? ' + ' + esc(d.output) : ''}</div></button>`).join('') || '<div class="item m">Демо-наборов нет</div>';
  setOpenTab(tab);
  if (!dl.open) dl.showModal();
  if (tab === 'srv') refreshJobs();
}
function setOpenTab(t) { $$('#openTabs button').forEach(b => b.classList.toggle('on', b.dataset.ot === t)); $$('#openDlg [data-op]').forEach(p => { p.hidden = p.dataset.op !== t; }); }
$('#openTabs').onclick = e => { const b = e.target.closest('button'); if (b) { setOpenTab(b.dataset.ot); if (b.dataset.ot === 'srv') refreshJobs(); } };
$('#demoList').onclick = e => { const b = e.target.closest('[data-n]'); if (!b) return; $('#openDlg').close(); history.replaceState(null, '', location.pathname + '?ds=' + encodeURIComponent(b.dataset.n)); loadDemo(demos.find(d => d.name === b.dataset.n)); };
$('#fGo').onclick = () => { const f = [$('#fIn').files[0], $('#fOut').files[0]].filter(Boolean); if (!f.length) return toast('Выберите хотя бы входной файл', 'info'); $('#openDlg').close(); loadFiles(f); };

// ---------- сервер ----------
async function detectServer() {
  try {
    const r = await fetch('/api/v1/jobs', {headers: {Accept: 'application/json'}});
    S.server = r.ok && (r.headers.get('content-type') || '').includes('json');
  } catch (e) { S.server = false; }
  document.body.classList.toggle('server', S.server);
}
const ST = {QUEUED: 'в очереди', RUNNING: 'считается', DONE: 'готово', FAILED: 'ошибка'};
async function refreshJobs() {
  if (!S.server) return;
  try {
    const jobs = await (await fetch('/api/v1/jobs')).json();
    jobs.sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt)));
    $('#jobList').innerHTML = jobs.slice(0, 50).map(j => `<button type="button" class="item" data-j="${esc(j.id)}" ${j.status !== 'DONE' ? 'disabled' : ''}><div class="t"><span>${new Date(j.createdAt).toLocaleString('ru-RU')}</span>
      <span class="badge ${j.status === 'DONE' ? 'b-ok' : j.status === 'FAILED' ? 'b-bad' : 'b-warn'}">${ST[j.status] || j.status}</span></div><div class="m">${esc(j.id)}</div></button>`).join('') || '<div class="item m">Расчётов пока нет</div>';
  } catch (e) { $('#jobList').innerHTML = `<div class="item m">Не удалось получить список: ${esc(e.message)}</div>`; }
}
$('#jobList').onclick = e => { const b = e.target.closest('[data-j]'); if (b && !b.disabled) { $('#openDlg').close(); openJob(b.dataset.j); } };
async function openJob(id) {
  try {
    const [i, o] = await Promise.all([fetchJson(`/api/v1/jobs/${id}/input`, 'вход'), fetchJson(`/api/v1/jobs/${id}/result`, 'результат')]);
    history.replaceState(null, '', location.pathname + '?job=' + encodeURIComponent(id));
    await load({input: i.json, output: o.json, outText: o.text, ds: {name: null, title: `Расчёт ${id.slice(0, 8)}`, job: id}});
  } catch (e) { $('#loading').hidden = true; toast(`Не удалось открыть расчёт: ${esc(e.message)}`, 'error', 8000); }
}
$('#jGo').onclick = async () => {
  const f = $('#jIn').files[0];
  if (!f) return toast('Выберите входной GeoJSON', 'info');
  const st = $('#jobState');
  try {
    st.textContent = 'Отправляю файл…';
    const depth = $('#jDepth').checked;
    const r = await fetch('/api/v1/jobs' + (depth ? '?depth=true' : ''), {method: 'POST', headers: {'Content-Type': 'application/geo+json'}, body: f});
    const j = await r.json();
    if (!r.ok) throw new Error(j.message || r.statusText);
    const t0 = Date.now();
    for (;;) {
      await new Promise(res => setTimeout(res, 1500));
      const d = await (await fetch(`/api/v1/jobs/${j.id}`)).json();
      st.textContent = `Расчёт ${j.id.slice(0, 8)}: ${ST[d.status] || d.status}, ${Math.round((Date.now() - t0) / 1000)} с`;
      if (d.status === 'DONE') { $('#openDlg').close(); openJob(j.id); return; }
      if (d.status === 'FAILED') throw new Error(d.error?.message || 'расчёт завершился ошибкой');
    }
  } catch (e) { st.textContent = ''; toast(`Расчёт не удался: ${esc(e.message)}`, 'error', 9000); refreshJobs(); }
};

// ---------- перетаскивание файлов ----------
let dragN = 0;
addEventListener('dragenter', e => { if ([...(e.dataTransfer?.types || [])].includes('Files')) { dragN++; $('#drop').hidden = false; } });
addEventListener('dragleave', () => { if (--dragN <= 0) { dragN = 0; $('#drop').hidden = true; } });
addEventListener('dragover', e => e.preventDefault());
addEventListener('drop', e => { e.preventDefault(); dragN = 0; $('#drop').hidden = true; if (e.dataTransfer.files.length) loadFiles(e.dataTransfer.files); });

// ---------- адрес: набор, вариант, выбор и камера ----------
let hashT = 0;
function scheduleHash() { clearTimeout(hashT); hashT = setTimeout(writeHash, 500); }
function selKey(sel) {
  if (!sel) return null;
  const r = sel.ref;
  if (sel.kind === 'crossing') return `cx:${r.seg.id}@${r.s.toFixed(2)}@${r.type}`;
  if (sel.kind === 'utility') return `utility:${sel.type}:${r.id}`;
  if (sel.kind === 'surface') return `surface:${sel.type}:${r.id}`;
  return `${sel.kind}:${r.id}`;
}
function writeHash() {
  if (!S.model) return;
  const p = new URLSearchParams();
  if (S.v) p.set('v', S.v.id);
  const k = selKey(S.sel);
  if (k) p.set('sel', k);
  const s = rig.state;
  p.set('cam', [...s.pos, s.yaw, s.pitch].map(x => +x.toFixed(1)).join(','));
  if (viewer.ex !== 3) p.set('ex', viewer.ex);
  history.replaceState(null, '', location.pathname + location.search + '#' + p.toString());
}
function applyHash() {
  const p = new URLSearchParams(location.hash.slice(1));
  if (p.get('ex')) setEx(+p.get('ex'));
  if (p.get('v') && S.model.variants.some(v => v.id === p.get('v'))) setVariant(p.get('v'), {quiet: true});
  const k = p.get('sel');
  if (k) {
    const sel = findSel(k);
    if (sel) { if (sel.kind === 'crossing') selectCrossing(sel.ref); else select(sel, {fly: !p.get('cam')}); }
  }
  const cam = p.get('cam');
  if (cam) {
    const a = cam.split(',').map(Number);
    if (a.length === 5 && a.every(Number.isFinite)) { rig.state = {pos: a.slice(0, 3), yaw: a[3], pitch: a[4]}; return true; }
  }
  return !!k;
}
function findSel(k) {
  const M = S.model, v = S.v, [kind, ...rest] = k.split(':'), id = rest.join(':');
  if (kind === 'oks') { const o = M.oks.find(x => String(x.id) === id); return o && {kind, ref: o}; }
  if (kind === 'seg') { const s = v?.segs.find(x => x.id === id); return s && {kind, ref: s}; }
  if (kind === 'node') { const n = v?.nodes.find(x => String(x.id) === id); return n && {kind, ref: n}; }
  if (kind === 'chamber') { const c = (M.points.heat_chamber || []).find(x => String(x.id) === id); return c && {kind, ref: c}; }
  if (kind === 'source') { const c = (M.points.source || []).find(x => String(x.id) === id); return c && {kind, ref: c}; }
  if (kind === 'utility') { const [t, ...r] = id.split(':'); const ln = (M.utilities[t] || []).find(x => String(x.id) === r.join(':')); return ln && {kind, type: t, ref: ln}; }
  if (kind === 'surface') { const [t, ...r] = id.split(':'); const it = (M.surfaces[t] || []).find(x => String(x.id) === r.join(':')); return it && {kind, type: t, ref: it}; }
  if (kind === 'cx') { const [sid, s, type] = id.split('@'); const c = v?.crossings.find(x => x.seg.id === sid && Math.abs(x.s - +s) < 0.01 && x.type === type); return c && {kind: 'crossing', ref: c}; }
  return null;
}

// ---------- клавиши ----------
addEventListener('keydown', e => {
  const typing = /INPUT|SELECT|TEXTAREA/.test(e.target.tagName);
  if ((e.key === '/' && !typing) || ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k')) { e.preventDefault(); $('#q').focus(); $('#q').select(); return; }
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'o') { e.preventDefault(); openDlg('demo'); return; }
  if (typing || e.ctrlKey || e.metaKey || e.altKey) return;
  if (document.querySelector('dialog[open]')) return;
  if (e.key === '?') { $('#helpDlg').showModal(); return; }
  if (e.key === 'Escape') {
    if (!$('#ctx').hidden) return hideCtx();
    if (S.fly) return stopFly();
    if (S.measure) return setMeasure(false);
    if (S.clip) return setClip(null);
    if (S.sel) return clearSel();
    document.body.classList.remove('leftOpen');
    return;
  }
  if (e.code === 'Space' && S.fly) { e.preventDefault(); $('#plPlay').click(); return; }
  const k = e.code;
  if (k === 'KeyF') frameSel();
  else if (k === 'KeyH' || k === 'Home') home();
  else if (k === 'KeyT') toggle2d();
  else if (k === 'KeyV') setMode(rig.mode === 'fly' ? 'orbit' : 'fly');
  else if (k === 'KeyX') setXray(!S.xray);
  else if (k === 'KeyB') toggleClip();
  else if (k === 'KeyM') setMeasure(!S.measure);
  else if (k === 'KeyP') S.fly ? stopFly() : startFly();
});

// ---------- кнопки на сцене ----------
$('#homeBtn').onclick = () => home();
$('#zIn').onclick = () => { const P = rig.center(); rig.animateTo({pos: P.clone().add(rig.pos.clone().sub(P).multiplyScalar(0.5)), yaw: rig.yaw, pitch: rig.pitch}, 350); };
$('#zOut').onclick = () => { const P = rig.center(); rig.animateTo({pos: P.clone().add(rig.pos.clone().sub(P).multiplyScalar(2)), yaw: rig.yaw, pitch: rig.pitch}, 350); };
$('#v2d').onclick = () => toggle2d();
$('#modeBtn').onclick = () => setMode(rig.mode === 'fly' ? 'orbit' : 'fly');
$('#xrayBtn').onclick = () => setXray(!S.xray);
$('#clipBtn').onclick = () => toggleClip();
$('#measureBtn').onclick = () => setMeasure(!S.measure);
$('#helpBtn').onclick = () => $('#helpDlg').showModal();
$('#coachOk').onclick = () => { $('#coach').hidden = true; store.set('coach', 1); };
$('#coachAll').onclick = () => { $('#coach').hidden = true; store.set('coach', 1); $('#helpDlg').showModal(); };

// ---------- старт ----------
async function start() {
  const o = viewer.opts;
  o.groundOpacity = store.get('gop', 0.4);
  o.buildings = store.get('bld', 'solid');
  Object.assign(o.layers, store.get('layers', {}));
  viewer.ex = store.get('ex', 3);
  updateModeUi();
  if (matchMedia('(max-width: 900px)').matches) $('#q').placeholder = 'Поиск здания, трубы';
  if (matchMedia('(pointer: coarse)').matches) {
    // на телефоне и планшете — жесты, а не кнопки мыши
    $('#coach .cg').innerHTML = '<span class="k">Один палец</span><span>двигать</span><span class="k">Щипок</span><span>приблизить</span>' +
      '<span class="k">Повернуть двумя</span><span>вращать</span><span class="k">Два пальца вверх-вниз</span><span>наклонить</span><span class="k">Двойное касание</span><span>приблизить сюда</span>';
  }
  if (!store.get('coach', 0)) $('#coach').hidden = false;
  await detectServer();
  try { demos = await (await fetch('demo/index.json')).json(); } catch (e) { demos = []; }
  const q = new URLSearchParams(location.search);
  if (q.get('job') && S.server) return openJob(q.get('job'));
  const want = q.get('ds') || store.get('ds', null);
  const d = demos.find(x => x.name === want) || demos[0];
  if (d) return loadDemo(d);
  $('#loading').hidden = true;
  openDlg(S.server ? 'srv' : 'files');
}
booted = true;
start();
window.__app = {S, viewer, rig, select, selectCrossing, setVariant, home, startFly, stopFly, setEx, setMode};
