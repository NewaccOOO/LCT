// Платформа инженера: загрузка данных, выбор объектов, панели, инструменты. Сцена — viewer.js, навигация — controls.js.
import * as THREE from 'three';
import {Viewer, PALETTE, buildingHeight, dnColor} from './viewer.js';
import {CameraRig} from './controls.js';
import {ViewCube} from './viewcube.js';
import {Profile} from './profile.js';
import {buildScene, routeTo, branchEnd, pointAt, segName, nodeName, UTILITIES, SURFACES, NODE_TYPES, BUILDING_KEYS} from './model.js';
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
  onChange: () => { viewer.redraw(); if (booted) { cube.update(); scheduleHash(); updateScale(); if (!rig.anim && !S.fly) noteRest(); } },
  onPivot: P => viewer.marker('pivot', P, {ring: true, color: PALETTE.selection}),
  onClick: (x, y, e) => clickAt(x, y, e),
  onDblClick: (x, y) => dblAt(x, y),
  onContext: (x, y, e) => showCtx(x, y, e),
  onHover: (x, y, e, dragging) => hoverAt(x, y, dragging),
  onSpeed: k => status(`Скорость полёта ×${fmt(k, 2)}`),
  onFly: () => { if (S.fly) stopFly(false); },   // взялся за W A S D — управляет сам, назад не возвращаем
  onFit: () => (S.sel ? frameSel() : home()),
  orbitPivot: (x, y) => { const h = pickAt(x, y); if (h) return h.point; const b = selBox(); return b ? new THREE.Vector3((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2) : null; },
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
    if (crossing) selectCrossing(crossing);
    else if (at != null) { const p = pointAt(S.route, at); lookAtPipe(p, 18); }
  },
  nodeName: id => nodeLabel(id),
});

// «Предыдущий вид», как Zoom Previous в AutoCAD и «Назад» в браузере: каждый перелёт запоминает, откуда летели
const viewStack = [];
{
  const anim = rig.animateTo.bind(rig);
  rig.animateTo = (target, ms) => {
    if (!target.back) { viewStack.push(rig.state); if (viewStack.length > 30) viewStack.shift(); }
    anim(target, ms);
  };
}
let restView = null, restT = 0;
function noteRest() {
  clearTimeout(restT);
  restT = setTimeout(() => {
    const s = rig.state;
    if (restView && Math.hypot(s.pos[0] - restView.pos[0], s.pos[1] - restView.pos[1], s.pos[2] - restView.pos[2]) > 2 + Math.abs(s.pos[2]) * 0.05) {
      const top = viewStack[viewStack.length - 1], now = performance.now();
      // колесо и наклон шагами — одна запись, если с прошлой остановки прошло меньше 4 с
      if (!top || (top.pos.join() !== restView.pos.join() && now - (noteRest.last || 0) > 4000)) { viewStack.push(restView); noteRest.last = now; }
      if (viewStack.length > 30) viewStack.shift();
    }
    restView = s;
  }, 900);
}
function backView() {
  const s = viewStack.pop();
  if (!s) return toast('Предыдущих видов нет', 'info', 1500);
  restView = s;
  rig.animateTo({pos: new THREE.Vector3(...s.pos), yaw: s.yaw * D, pitch: s.pitch * D, back: true, done: () => { restView = rig.state; }}, 600);
}

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
  progress(0.02, `Загрузка: ${what}…`);
  const r = await fetch(url);
  if (!r.ok) throw new Error(`${what}: ${r.status} ${r.statusText}`);
  const text = await r.text();
  return {json: JSON.parse(text), text};
}
async function load({input, output, outText = null, ds}) {
  try {
    progress(0.05, 'Строю сцену…');
    const model = await buildScene(input, output, {onProgress: (k, t) => progress(0.05 + k * 0.75, t)});
    if (!model.stats.utilities && !model.stats.surfaces && !model.stats.points && !model.hasOutput) {
      $('#loading').hidden = true;
      $('#errH').textContent = 'Файл не распознан';
      const n = model.skipped.reduce((a, [, k]) => a + k, 0);
      $('#errBody').innerHTML = `<p>В «${esc(ds.title)}» нет ни одного объекта в формате сервиса: ${fmt(n)} ${plural(n, 'объект', 'объекта', 'объектов')} пропущено.</p>
        <ul>${model.skipped.slice(0, 5).map(([w, k]) => `<li>${esc(w)} — ${fmt(k)}</li>`).join('')}</ul>
        <p class="mut">У каждого объекта должно быть свойство <code>object_type</code>, у ограничений ещё <code>restriction_type</code>. Требования к файлу: «Файл → Открыть данные → Свои файлы → Какой GeoJSON подходит».</p>`;
      $('#errDlg').showModal();
      return false;
    }
    progress(0.85, 'Рисую 3D…');
    await new Promise(r => setTimeout(r, 0));
    S.model = model; S.raw = outText; S.ds = ds; S.sel = null; S.route = null; S.ghost = null;
    searchIdx = null;
    stopFly(); setClip(null); setMeasure(false);
    viewer.variant = null;
    viewer.setModel(model);
    rig.bounds = model.bbox;
    setMinZ();
    $('#dsName').textContent = ds.title;
    $('#dsBtnM').textContent = 'Набор: ' + ds.title.slice(0, 18) + (ds.title.length > 18 ? '…' : '');
    document.title = `${ds.title} — Теплосеть 3D`;
    renderVariants();
    setVariant(model.variants[0]?.id, {quiet: true});
    renderLayers();
    renderLegend();
    // адрес с видом и выбором применяем только при первом открытии; новый набор — с исходного вида
    const fromLink = !S.opened && applyHash();
    S.opened = true;
    if (!fromLink) { history.replaceState(null, '', location.pathname + location.search); home(0); }
    $('#loading').hidden = true;
    const v = S.v;
    if (!model.hasOutput) toast(`Результата трассировки нет, на сцене только исходные данные. Откройте файл результата${S.server ? ' или запустите расчёт: «Файл → Новый расчёт на сервере»' : ''}.`, 'info', 6000);
    if (!ds.local) store.set('ds', ds.name || null);
    // отчёт импорта: что из исходных данных не попало в сцену и почему
    if (model.skipped.length) {
      const n = model.skipped.reduce((a, [, k]) => a + k, 0);
      toast(`Пропущено ${fmt(n)} ${plural(n, 'объект', 'объекта', 'объектов')}: ${model.skipped.slice(0, 3).map(([w, k]) => `${esc(w)} — ${fmt(k)}`).join('; ')}. Подробности во вкладке «Обзор», раздел «Данные».`, 'info', 9000);
    }

  } catch (e) {
    console.error(e);
    $('#loading').hidden = true;
    toast(`Не удалось открыть данные: ${esc(e.message)}`, 'error', 8000);
  }
}
let demos = [];
async function loadDemo(d) {
  try {
    const [i, o] = await Promise.all([fetchJson('demo/' + d.input, 'исходные данные'), d.output ? fetchJson('demo/' + d.output, 'результат') : null]);
    await load({input: i.json, output: o?.json, outText: o?.text, ds: {name: d.name, title: d.title}});
  } catch (e) { $('#loading').hidden = true; toast(`Не удалось загрузить демо-набор: ${esc(e.message)}`, 'error', 8000); }
}
function isOutput(fc) { return (fc.features || []).some(f => f.properties && (f.properties.object_type === 'variant_summary' || f.properties.variant_id != null)); }
// JSON с понятной ошибкой: имя файла, строка и столбец
function parseJson(text, fname) {
  try { return JSON.parse(text); } catch (e) {
    const m = /position (\d+)/.exec(e.message), pos = m ? +m[1] : null;
    let where = '';
    if (pos != null) { const before = text.slice(0, pos), line = before.split('\n').length, col = pos - before.lastIndexOf('\n'); where = `: ошибка в строке ${line}, столбце ${col}`; }
    throw new Error(`Файл «${fname}» не читается как JSON${where}. Проверьте, что это GeoJSON, а не Shape или KML.`);
  }
}
async function loadFiles(files, {fromCache = false} = {}) {
  try {
    const parsed = await Promise.all([...files].map(async f => ({name: f.name, text: f.text ? await f.text() : f.text_})));
    let input = null, output = null, outText = null, inText = null;
    const name = [];
    for (const {name: fname, text} of parsed) {
      const j = parseJson(text, fname);
      if (!j || !Array.isArray(j.features)) throw new Error(`В файле «${fname}» нет списка features, это не GeoJSON FeatureCollection`);
      if (isOutput(j)) { output = j; outText = text; } else { input = j; inText = text; }
      name.push(fname.replace(/\.(geo)?json$/i, ''));
    }
    if (!input && output) { input = {type: 'FeatureCollection', features: []}; toast('Открыт только результат трассировки. Без файла исходных данных на сцене не будет зданий и коммуникаций.', 'info', 6000); }
    if (!input) throw new Error('не выбран файл исходных данных');
    if (await load({input, output, outText, ds: {name: 'local', title: name.join(' + '), local: true}}) === false) return;
    // свои файлы живут только в этом браузере: адрес ?ds=local откроет их после F5, но не у коллеги
    history.replaceState(null, '', location.pathname + '?ds=local');
    if (!fromCache) {
      cacheFiles(parsed.map(p => ({name: p.name, text: p.text})));
      toast('Файлы открыты и сохранены в этом браузере: после обновления страницы они откроются снова. Коллега по ссылке на вид их не увидит, файлы есть только у вас.', 'info', 7000);
    }
  } catch (e) { $('#loading').hidden = true; $('#errH').textContent = 'Файл не открылся'; $('#errBody').innerHTML = `<p>${esc(e.message)}</p>`; $('#errDlg').showModal(); }
}
// последние свои файлы — в IndexedDB этого браузера
function idb() { return new Promise((res, rej) => { const r = indexedDB.open('hn3d', 1); r.onupgradeneeded = () => r.result.createObjectStore('files'); r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error); }); }
async function cacheFiles(list) { try { const db = await idb(); db.transaction('files', 'readwrite').objectStore('files').put(list, 'last'); } catch (e) { /* хранилище недоступно — просто не запомним */ } }
async function cachedFiles() { try { const db = await idb(); return await new Promise(res => { const r = db.transaction('files').objectStore('files').get('last'); r.onsuccess = () => res(r.result || null); r.onerror = () => res(null); }); } catch (e) { return null; } }

// ---------- варианты ----------
function renderVariants() {
  const vs = S.model.variants, best = Math.min(...vs.map(v => v.score ?? Infinity));
  $('#variants').innerHTML = vs.map(v => `<button role="tab" data-v="${esc(v.id)}" title="Вариант ${esc(v.id)}: S ${fmt(v.score, 2)}, ${rub(v.summary.calculated_cost)}">
    <span><span class="vl">Вариант </span><span class="vs">Вар. </span>${esc(v.id)}</span><small>${vs.length > 1 && v.score === best ? 'лучший · ' : ''}S ${fmt(v.score, 2)} · ${rub(v.summary.calculated_cost)}</small></button>`).join('') +
    // на телефоне — один выпадающий список, чтобы поиску хватало места
    `<select id="varSel" aria-label="Вариант решения">${vs.map(v => `<option value="${esc(v.id)}">Вар. ${esc(v.id)}${vs.length > 1 && v.score === best ? ', лучший' : ''}</option>`).join('')}</select>`;
  $('#variants').hidden = vs.length < 2;
  $('#varSel').onchange = e => setVariant(e.target.value);
}
$('#variants').onclick = e => { const b = e.target.closest('button'); if (b) setVariant(b.dataset.v); };
function setVariant(id, {quiet = false} = {}) {
  const v = S.model.variants.find(x => x.id === id) || null;
  S.v = v;
  $$('#variants button').forEach(b => { b.classList.toggle('on', b.dataset.v === id); b.setAttribute('aria-selected', b.dataset.v === id); });
  if ($('#varSel')) $('#varSel').value = id;
  const keep = S.sel;
  clearSel();
  viewer.setVariant(v);
  if (S.ghost === id) { S.ghost = null; viewer.setGhost(null); }
  if (booted && S.model) renderLegend();
  renderSummary(); renderOks(); renderCx();
  if (keep && keep.kind === 'oks') select(keep, {fly: false});
  if (!quiet && v) toast(`Вариант ${esc(v.id)}: S ${fmt(v.score, 2)}`, 'info', 1800);
  scheduleHash();
}

// ---------- обзор ----------
const DEPTH_GRAD = [1, 2, 3, 4, 5].map(d => { const t = Math.min(1, Math.max(0, (d - 1) / 4)); return new THREE.Color().setHSL(0.5 - 0.08 * t, 0.9, 0.78 - 0.4 * t).getStyle(); }).join(',');
const SLOPE_NOTE = 'СП 124.13330 требует уклон не меньше 0,002, чтобы трубы можно было опорожнить. Правила расчёта задают только максимальный уклон 0,10, поэтому минимальный при трассировке не подбирается и здесь проблемой не считается. Проверьте его при рабочем проектировании.';
// значения для сравнения; лучшее в столбце — зелёным
const CMP = [
  ['cost', x => x.summary.calculated_cost, v => rub(v)], ['len', x => x.summary.new_network_length, v => meters(v)],
  ['unc', (x, M) => M.oks.length - x.connected.size, v => fmt(v)], ['bad', x => x.crossings.filter(c => c.ok === false).length, v => fmt(v)],
  ['spec', x => x.segs.filter(s => s.special).length, v => fmt(v)],
];
// сравнение: строки — показатели, столбцы — варианты; так таблица помещается в узкую панель и на планшет
function cmpTable(M, v) {
  const rows = [['Показатель S', x => x.score ?? Infinity, q => fmt(q, 2)], ['Стоимость', ...CMP[0].slice(1)], ['Новых труб', ...CMP[1].slice(1)],
    ['Не подключено', ...CMP[2].slice(1)], ['Проблем по высоте', ...CMP[3].slice(1)], ['Спецпереходов', ...CMP[4].slice(1)]];
  const bestS = Math.min(...M.variants.map(x => x.score ?? Infinity));
  return `<table class="cmp"><tr><th></th>${M.variants.map(x => `<th class="click ${x === v ? 'cur' : ''}" data-v="${esc(x.id)}">Вар. ${esc(x.id)}${x.score === bestS ? '<small>лучший</small>' : ''}</th>`).join('')}</tr>
    ${rows.map(([name, f, t]) => { const vals = M.variants.map(x => f(x, M)), best = Math.min(...vals), differ = vals.some(q => q !== best);
      return `<tr><td${name === 'Показатель S' ? ` title="${S_FORMULA}"` : ''}>${name}</td>${vals.map((q, i) => `<td class="${q === best && differ ? 'best' : ''} ${M.variants[i] === v ? 'cur' : ''}">${t(q)}</td>`).join('')}</tr>`; }).join('')}</table>`;
}
// одна фраза: чем лучший вариант лучше следующего
function verdict(M) {
  const vs = [...M.variants].sort((a, b) => (a.score ?? 1e9) - (b.score ?? 1e9)), [b, n] = vs;
  if (!n) return '';
  const parts = [], dc = n.summary.calculated_cost - b.summary.calculated_cost, dl = n.summary.new_network_length - b.summary.new_network_length;
  if (Math.abs(dc) > 1) parts.push(dc > 0 ? `дешевле на ${rub(dc)}` : `дороже на ${rub(-dc)}`);
  if (Math.abs(dl) > 0.5) parts.push(dl > 0 ? `короче на ${meters(dl)}` : `длиннее на ${meters(-dl)}`);
  const du = (M.oks.length - n.connected.size) - (M.oks.length - b.connected.size);
  if (du) parts.push(du > 0 ? `подключает на ${du} ${plural(du, 'здание', 'здания', 'зданий')} больше` : `подключает на ${-du} ${plural(-du, 'здание', 'здания', 'зданий')} меньше`);
  return `<div class="verdict"><b>Вариант ${esc(b.id)}</b> лучше варианта ${esc(n.id)} на ${fmt(n.score - b.score, 2)} по S: ${parts.join(', ') || 'показатели близки'}.</div>`;
}
const S_FORMULA = 'S = 0,7 × стоимость / 25 млн ₽ + 0,3 × длина / 100 м — чем меньше, тем лучше';
function renderSummary() {
  const M = S.model, v = S.v, el = $('#t-sum');
  if (!v) {
    el.innerHTML = `<h2>Исходные данные</h2>${dataInfo()}<div class="note info">Результата трассировки нет. Откройте файл результата через <b>Файл → Открыть данные</b>${S.server ? ' или запустите расчёт: <b>Файл → Новый расчёт на сервере</b>' : ''}.</div>`;
    return;
  }
  const sm = v.summary, bad = v.crossings.filter(c => c.ok === false).length, unc = M.oks.length - v.connected.size;
  const chambers = v.nodes.filter(n => n.type === 'heat_chamber').length, special = v.segs.filter(s => s.special).length;
  const len = sm.new_network_length ?? v.segs.reduce((a, s) => a + s.len, 0);
  el.innerHTML = `<h2>Вариант ${esc(v.id)}${v.rank === 1 && M.variants.length > 1 ? ' · лучший' : v.rank && M.variants.length > 1 ? ` · ${v.rank}-й по показателю S` : ''}</h2>
    <div class="kpi">
      <div title="${S_FORMULA}"><b>${fmt(sm.score, 2)}</b><span>показатель S, меньше — лучше <span class="q">?</span></span></div>
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
    ${M.variants.length > 1 ? `<h2>Сравнение вариантов</h2>${verdict(M)}${cmpTable(M, v)}
      <div class="mut kbm" style="font-size:12.5px;margin-top:6px">Клавиши 1–${M.variants.length} — переключить вариант.</div>
      <div class="filters" style="margin-top:8px">${M.variants.filter(x => x !== v).map(x => `<button data-ghost="${esc(x.id)}" class="${S.ghost === x.id ? 'on' : ''}" title="Трассы варианта ${esc(x.id)} белым пунктиром поверх текущего"><i class="sw" style="background:none;border-top:2px dashed #f1f5f9;height:0"></i> ${S.ghost === x.id ? `Скрыть вариант ${esc(x.id)}` : `Показать вариант ${esc(x.id)} поверх`}</button>`).join('')}</div>` : ''}
    <h2>С чего начать</h2>
    <div class="actions">
      <button data-go="oks">Найти здание и его трассу</button>
      <button data-go="cx">Разобрать проблемы по высоте${bad ? ` (${bad})` : ''}</button>
      <button data-go="tour">Пролететь по самой длинной трассе</button>
      <button data-go="help">Как управлять сценой</button>
    </div>
    <h2>Данные</h2>${dataInfo()}`;
  el.onclick = e => {
    const tr = e.target.closest('[data-v]');
    if (tr) return setVariant(tr.dataset.v);
    const gb = e.target.closest('button[data-ghost]');
    if (gb) { S.ghost = S.ghost === gb.dataset.ghost ? null : gb.dataset.ghost; viewer.setGhost(S.model.variants.find(x => x.id === S.ghost) || null);
      if (S.ghost) toast(`Вариант ${esc(S.ghost)} показан белым пунктиром поверх текущего`, 'info', 2500); renderSummary(); renderLegend(); return; }
    const b = e.target.closest('button[data-go]');
    if (!b) return;
    if (b.dataset.go === 'help') $('#helpDlg').showModal();
    else if (b.dataset.go === 'tour') longestTour();
    else openTab(b.dataset.go);
  };
}
function dataInfo() {
  const M = S.model, u = Object.entries(M.utilities).map(([k, l]) => `${UTILITIES[k].name.toLowerCase()} ${fmt(l.length)}`).join(', ');
  return `<table class="wrap"><tr><td>Объектов в исходных данных</td><td>${fmt(M.stats.input)}</td></tr>
    <tr><td>Зданий к подключению</td><td>${fmt(M.oks.length)}</td></tr>
    <tr><td>Коммуникации</td><td style="white-space:normal">${u || '—'}</td></tr>
    <tr><td>Глубины новых труб</td><td>${M.hasDepth ? 'подобраны при трассировке' : M.hasOutput ? 'в результате нет, трубы показаны на глубине 3,0 м' : '—'}</td></tr>
    ${M.skipped.map(([w, k]) => `<tr><td>Пропущено: ${esc(w)}</td><td>${fmt(k)}</td></tr>`).join('')}</table>`;
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
    <input type="search" id="oksQ" placeholder="Адрес или номер дома, например «дом 14»" aria-label="Фильтр зданий">
    <div class="list" id="oksL"></div>`;
  const draw = () => {
    const q = ($('#oksQ').value || '').toLowerCase();
    $$('#oksF button').forEach(b => b.classList.toggle('on', b.dataset.f === S.oksF));
    const list = rows.filter(r => (S.oksF === 'all' || !v || (S.oksF === 'conn') === r.conn) &&
      (!q || matchWords(q, [r.o.id, r.o.oksId, r.o.label].join(' '))));
    const num = +((q.match(/\d+/) || [])[0] || NaN);
    list.sort((a, b) => (b.o.no === num) - (a.o.no === num) || (a.conn === b.conn ? 0 : a.conn ? 1 : -1) || a.o.no - b.o.no);
    $('#oksL').innerHTML = list.slice(0, 1500).map(({o, conn}) => {
      const r = conn ? routeTo(v, o.id) : null;
      return `<button class="item ${S.sel?.ref === o ? 'sel' : ''}" data-id="${esc(o.id)}"><div class="t"><span>${esc(o.label)}</span>
        ${v ? `<span class="badge ${conn ? 'b-ok' : 'b-bad'}">${conn ? 'подключено' : 'не подключено'}</span>` : ''}</div>
        <div class="m">${o.flow != null ? `расход ${fmt(o.flow, 1)} т/ч` : ''}${r ? ` · трасса ${meters(r.len)}${r.bad ? ` · <span style="color:#fda4af">${r.bad} ${plural(r.bad, 'проблема', 'проблемы', 'проблем')} по высоте</span>` : ''}` : v ? ' · маршрут не найден' : ''}</div></button>`;
    }).join('') || '<div class="item m">Ничего не найдено</div>';
  };
  draw();
  $('#oksF') && ($('#oksF').onclick = e => { const b = e.target.closest('button'); if (b) { S.oksF = b.dataset.f; draw(); } });
  $('#oksQ').oninput = draw;
  $('#oksL').onclick = e => { const b = e.target.closest('.item[data-id]'); if (!b) return; const o = M.oks.find(x => String(x.id) === b.dataset.id); select({kind: 'oks', ref: o}, {fly: true}); draw(); };
}

// ---------- проблемы ----------
function cxTitle(c) {
  if (c.type === 'segment') return `${segName(c.seg)}: уклон или глубина`;
  return c.gap < 0 ? `${UTILITIES[c.type].name}: трубы пересекаются` : `${UTILITIES[c.type].name}: зазор ${fmt(c.gap, 2)} из ${fmt(c.need, 2)} м`;
}
function endLabel(seg) { const v = S.v, end = branchEnd(v, seg), o = S.model.oks.find(x => x.id === end); return o ? o.label : nodeLabel(end); }
function nodeLabel(id) { const v = S.v; return nodeName(v?.nodes.find(n => n.id === id) || {id}, S.model); }
function renderCx() {
  const v = S.v, el = $('#t-cx');
  if (!v) { el.innerHTML = '<div class="note info">Результата трассировки нет, проверять нечего.</div>'; $('#cntCx').textContent = ''; return; }
  const all = v.crossings, bad = all.filter(c => c.ok === false);
  $('#cntCx').textContent = bad.length || '';
  $('#cntCx').className = 'cnt' + (bad.length ? ' bad' : '');
  // самые тяжёлые — первыми: по нехватке зазора до нормы
  const deficit = c => c.type === 'segment' ? 99 : (c.need ?? 0) - c.gap;
  const base = (S.cxAll ? all : bad).slice().sort((a, b) => deficit(b) - deficit(a));
  const types = [...new Set(base.map(c => c.type))];
  el.innerHTML = `<h2>${S.cxAll ? 'Все пересечения' : 'Проблемы по высоте'}</h2>
    <div class="filters" id="cxF"><button data-t="">Все ${base.length}</button>${types.map(t => `<button data-t="${t}">${t === 'segment' ? 'Уклон и глубина' : UTILITIES[t].name} ${base.filter(c => c.type === t).length}</button>`).join('')}</div>
    <label class="tog"><input type="checkbox" id="cxAll" ${S.cxAll ? 'checked' : ''}> Показать и пересечения без проблем (${all.length})</label>
    <div class="list" id="cxL" style="margin-top:8px"></div>`;
  const draw = () => {
    $$('#cxF button').forEach(b => b.classList.toggle('on', b.dataset.t === S.cxType));
    const list = base.filter(c => !S.cxType || c.type === S.cxType);
    $('#cxL').innerHTML = list.slice(0, 2000).map(c => `<button class="item ${S.sel?.ref === c ? 'sel' : ''}" data-k="${all.indexOf(c)}"><div class="t"><span>${esc(cxTitle(c))}</span>
      ${S.cxAll ? `<span class="badge ${c.ok === false ? 'b-bad' : c.ok ? 'b-ok' : 'b-mut'}">${c.ok === false ? 'проблема' : c.ok ? 'норма' : 'не проверяется'}</span>` : ''}</div>
      <div class="m">${c.type === 'segment' ? esc(c.issues.join('; ')) : `${segName(c.seg)}, ${fmt(c.s)} м от начала → ${esc(endLabel(c.seg))}`}</div></button>`).join('')
      || `<div class="item m">${S.cxAll ? 'Пересечений нет' : 'Проблем нет'}</div>`;
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
    <div class="fld"><label for="gop">Прозрачность земли <b id="gopV">${100 - Math.round(o.groundOpacity * 100)} %</b></label><input type="range" id="gop" min="5" max="100" value="${100 - Math.round(o.groundOpacity * 100)}">
      <small>Рентген (X) делает прозрачными и землю, и здания.</small></div>
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
    $('#cbyLeg').innerHTML = o.colorBy === 'dn' ? `<div style="height:8px;border-radius:4px;background:linear-gradient(90deg,${grad.join(',')})"></div><div style="display:flex;justify-content:space-between"><span>Ду 50</span><span>Ду 400</span><span>Ду 1400</span></div>`
      : o.colorBy === 'status' ? '<i class="sw" style="background:#5eead4"></i> участки с проблемой — ярко <i class="sw" style="background:#335c5a;margin-left:10px"></i> без проблем — приглушённо'
        : `<div style="height:8px;border-radius:4px;background:linear-gradient(90deg,${DEPTH_GRAD})"></div><div style="display:flex;justify-content:space-between"><span>1 м</span><span>3 м</span><span>5 м и глубже</span></div>`;
  };
  const mark = () => { $$('#bld button').forEach(b => b.classList.toggle('on', b.dataset.b === o.buildings)); $$('#cby button').forEach(b => b.classList.toggle('on', b.dataset.c === o.colorBy)); legend(); };
  mark();
  $('#ex').oninput = e => { $('#exV').textContent = e.target.value; };
  $('#ex').onchange = e => setEx(+e.target.value);
  $('#gop').oninput = e => { o.groundOpacity = 1 - e.target.value / 100; $('#gopV').textContent = e.target.value + ' %'; viewer.applyOpts(); store.set('gop', o.groundOpacity); };
  $('#bld').onclick = e => { const b = e.target.closest('button'); if (!b) return; o.buildings = b.dataset.b; mark(); viewer.applyOpts(); store.set('bld', o.buildings); };
  $('#cby').onclick = e => { const b = e.target.closest('button'); if (!b) return; o.colorBy = b.dataset.c; mark(); viewer.buildNetwork(); refreshHighlight(); renderLegend(); };
  el.onchange = e => { const k = e.target.dataset?.k; if (!k) return; L[k] = e.target.checked; viewer.applyOpts(); store.set('layers', L); };
}
// Камере незачем уходить глубже самых глубоких сетей: упор на глубине сетей + 6 м
function setMinZ() {
  const M = S.model;
  let d = 4;
  if (M) { for (const s of M.segs) d = Math.max(d, s.d0 + s.row.h, s.d1 + s.row.h); for (const k of Object.keys(M.utilities)) d = Math.max(d, UTILITIES[k].top + (UTILITIES[k].h || 0.5)); }
  rig.minZ = -(d + 6) * viewer.ex;
}

// Условные обозначения на сцене — как на чертеже и в ГИС; состав зависит от набора и режима цвета
function renderLegend() {
  const M = S.model, o = viewer.opts;
  if (!M) return;
  const sw = (c, dash) => `<i class="sw" style="${dash ? `background:none;border-top:2px dashed ${c};height:0` : `background:${c}`}"></i>`;
  const dot = c => `<i class="dot" style="background:${c};margin-left:5px"></i>`;
  const grad = [50, 150, 400, 1400].map(d => '#' + dnColor(d).getHexString()).join(',');
  const rows = ['<div class="h">Новая сеть</div>'];
  if (o.colorBy === 'status') rows.push(sw('#5eead4') + '<span>участок с проблемой — ярко</span>', sw('#335c5a') + '<span>участок без проблем — приглушён</span>');
  else if (o.colorBy === 'depth') rows.push(`<i class="sw" style="background:linear-gradient(90deg,${DEPTH_GRAD})"></i><span>глубина верха: мельче → глубже</span>`);
  else rows.push(`<i class="sw" style="background:linear-gradient(90deg,${grad})"></i><span>новая труба: светлее — тоньше, Ду 50 → 1400</span>`);
  rows.push('<i class="sw" style="background:rgba(248,250,252,.35);height:10px;border:1px solid rgba(248,250,252,.6)"></i><span>футляр спецперехода</span>',
    dot(PALETTE.problem) + '<span>проблема по высоте (метка с иглой)</span>');
  rows.push(sw(PALETTE.selection) + '<span>выбранная трасса</span>', sw('#67e8f9', true) + '<span>проекция трассы на поверхность</span>');
  if (S.ghost) rows.push(sw('#f1f5f9', true) + `<span>вариант ${esc(S.ghost)} для сравнения</span>`);
  rows.push(dot(PALETTE.chamberNew) + '<span>новая камера (колодец)</span>');
  if (M.oks.length) rows.push(dot(PALETTE.connected) + '<span>здание подключено</span>', dot(PALETTE.unconnected) + '<span>здание не подключено</span>');
  rows.push('<div class="h">Существующее под землёй</div>');
  for (const k of Object.keys(M.utilities)) rows.push(sw(UTILITIES[k].color) + `<span>${UTILITIES[k].name.toLowerCase()}, верх ${fmt(UTILITIES[k].top, 1)} м${UTILITIES[k].assumed ? ' (условно)' : ''}</span>`);
  if (M.points.heat_chamber?.length) rows.push(dot(PALETTE.chamberOld) + '<span>существующая камера</span>');
  $('#legBody').innerHTML = rows.join('');
}
$('#legBtn').onclick = () => { const b = $('#legBody'); b.hidden = !b.hidden; $('#legBtn').setAttribute('aria-expanded', String(!b.hidden)); $('#legBtn .caret').textContent = b.hidden ? '▸' : '▾'; store.set('legend', !b.hidden); };

function setEx(ex) {
  const P = rig.center(), oldEx = viewer.ex;
  viewer.setExaggeration(ex);
  setMinZ();
  // камера остаётся у той же точки сцены: пересчитываем её высоту под новый масштаб, если она под землёй
  if (rig.pos.z < 0) { rig.pos.z *= ex / oldEx; rig.apply(); }
  else if (P.z < 0) { const d = rig.pos.clone().sub(P); P.z *= ex / oldEx; rig.pos.copy(P).add(d); rig.apply(); }
  refreshHighlight();
  if ($('#ex')) { $('#ex').value = ex; $('#exV').textContent = ex; }
  store.set('ex', ex);
}

// ---------- выбор ----------
function clearSel() {
  stopFly();
  S.sel = null; S.route = null;
  viewer.highlight(null);
  viewer.marker('selpt', null);
  viewer.setDetour(null);
  viewer.opts.dim = false;
  viewer.applyOpts();
  $('#props').hidden = true;
  document.body.classList.remove('propsOpen', 'propsMin');
  if (document.activeElement !== $('#q')) $('#q').value = '';
  if (S.autoLeft) { document.body.classList.remove('leftHidden'); S.autoLeft = false; }
  closeProfile();
  layout();
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
function select(sel, {fly = false, room = false} = {}) {
  stopFly();
  // планшет и узкий ноутбук: объект выбран на карте или поиском — левая панель уступает место карточке,
  // после снятия выбора возвращается (как боковая панель в приложениях iPad)
  const b = document.body;
  if ((room || matchMedia('(pointer: coarse)').matches) && !isMobile() && innerWidth <= 1280 && !b.classList.contains('leftHidden')) { b.classList.add('leftHidden'); S.autoLeft = true; }
  S.sel = sel;
  S.route = routeFor(sel);
  if (S.route) {
    const o = S.model.oks.find(x => x.id === S.route.end);
    S.route.endName = o ? o.label : null;
  }
  viewer.highlight(S.route, sel.kind === 'seg' ? sel.ref : null);
  if (!S.route) highlightOther(sel);
  else if (sel.kind === 'oks') outlineBuilding(sel.ref);
  viewer.marker('selpt', null);
  viewer.setDetour(null);
  viewer.opts.dim = !!S.route;
  viewer.applyOpts();
  document.body.classList.remove('propsMin');
  mapOnly = null;
  showProps(sel);
  // на телефоне профиль — по кнопке «Профиль», чтобы не съедал половину экрана
  if (S.route && !isMobile()) openProfile(); else closeProfile();
  if (fly) frameSel();
  $$('.item.sel').forEach(x => x.classList.remove('sel'));
  scheduleHash();
}
// контур выбранного здания по крыше и по земле — видно, какое выбрано, даже если трасса за ним
function outlineBuilding(o) {
  const it = o.building;
  if (!it) return;
  const r = it.rings[0], H = viewer.opts.buildings === 'solid' ? buildingHeight(it) + 0.3 : 0.4, seg = [];
  for (const z of [H, 0.3]) for (let i = 0; i < r.length; i++) { const a = r[i], b = r[(i + 1) % r.length]; seg.push(a[0], a[1], z, b[0], b[1], z); }
  viewer.overlayLines(seg, {width: 3});
}
function highlightOther(sel) {
  const ex = viewer.ex;
  viewer.highlight(null);
  const segs = (pts, z) => { const out = []; for (let i = 0; i + 1 < pts.length; i++) out.push(pts[i][0], pts[i][1], z, pts[i + 1][0], pts[i + 1][1], z); return out; };
  if (sel.kind === 'utility') { const ln = sel.ref; viewer.overlayLines(segs(ln.pts, -(ln.top + (ln.h || 0.3) / 2) * ex), {width: 5}); }
  if (sel.kind === 'surface') { const it = sel.ref, r = it.rings[0]; const H = BUILDING_KEYS.has(sel.type) && viewer.opts.buildings === 'solid' ? buildingHeight(it) + 0.3 : 0.4; viewer.overlayLines(segs([...r, r[0]], H), {width: 3}); }
  if (sel.kind === 'node' || sel.kind === 'chamber') { const p = sel.ref.xy; viewer.marker('selpt', new THREE.Vector3(p[0], p[1], viewer.z(sel.ref.depth ?? 3.2)), {r: 1.5}); }
  if (sel.kind === 'oks') { const o = sel.ref; viewer.marker('selpt', new THREE.Vector3(o.xy[0], o.xy[1], (o.building ? buildingHeight(o.building) : 12) + 10.2), {r: 3.2}); outlineBuilding(o); }
}
function selectCrossing(c, {move = true} = {}) {
  const r0 = routeFor({kind: 'crossing', ref: c});
  S.focusAt = r0 && c.type !== 'segment' ? (r0.segs.find(q => q === c.seg)?.s0 ?? 0) + c.s : null;
  select({kind: 'crossing', ref: c});
  // участник конфликта подсвечен белым поверх своего цвета — пара «новая — существующая», как в Clash Detective
  if (c.type !== 'segment') {
    const ln = (S.model.utilities[c.type] || []).find(l => String(l.id) === String(c.eid));
    if (ln) { const z = -(ln.top + (ln.h || 0.3) / 2) * viewer.ex, seg = []; for (let i = 0; i + 1 < ln.pts.length; i++) seg.push(ln.pts[i][0], ln.pts[i][1], z, ln.pts[i + 1][0], ln.pts[i + 1][1], z); viewer.overlayLines(seg, {color: '#ffffff', width: 3}); }
  }
  if (c.type !== 'segment') {
    // вертикальная размерная линия между трубами с подписью зазора
    const z = d => viewer.z(d), newAbove = c.side === 'над';
    const a = newAbove ? c.hn + c.H : c.top + c.eh, b = newAbove ? c.top : c.hn;
    const t = 0.35;
    viewer.overlayLines([c.x, c.y, z(a), c.x, c.y, z(b), c.x - t, c.y, z(a), c.x + t, c.y, z(a), c.x - t, c.y, z(b), c.x + t, c.y, z(b)], {color: c.ok === false ? PALETTE.problem : '#86efac', width: 2.5});
    const lb = viewer.label(c.gap < 0 ? `наложение ${fmt(-c.gap, 2)} м` : `зазор ${fmt(c.gap, 2)} м${c.need != null ? ` · нужно ${fmt(c.need, 2)}` : ''}`, 'lbl dim ' + (c.ok === false ? 'bad' : 'ok'));
    lb.position.set(c.x, c.y, (z(a) + z(b)) / 2);
    lb.center.set(-0.08, 0.5);
    viewer.g.overlay.add(lb);
  } else viewer.marker('selpt', new THREE.Vector3(c.x, c.y, viewer.z(c.seg.d0)), {r: 0.9, color: PALETTE.problem, ring: true});
  if (move) crossingView(c);
}
// «Показать обход»: участок трассы поднимается (или опускается) до рекомендуемой глубины у пересечения
// с площадкой ±1,5 м и спусками с уклоном 0,10 — как сервис строит обход в режиме глубины
function showDetour(c, mode) {
  const R = S.route;
  if (!R || c.type === 'segment') return;
  const at = (R.segs.find(q => q === c.seg)?.s0 ?? 0) + c.s, here = pointAt(R, at).d;
  const target = mode === 'up' ? c.above : c.below, dd = target - here, ramp = Math.abs(dd) / 0.1, half = 1.5;
  const a = Math.max(0, at - half - ramp), b = Math.min(R.len, at + half + ramp), pts = [], prof = [];
  for (let s = a; s <= b + 1e-6; s += Math.max(0.25, (b - a) / 160)) {
    const p = pointAt(R, s), off = Math.abs(s - at), w = off <= half ? 1 : Math.max(0, 1 - (off - half) / ramp);
    const d = p.d + dd * w;
    pts.push([p.x, p.y, d]); prof.push({S: s, d});
  }
  viewer.setDetour(pts, c.H / 2);
  const label = mode === 'up' ? `обход сверху: верх на ${fmt(target, 2)} м` : `обход снизу: верх на ${fmt(target, 2)} м${c.kBelow > 1 ? `, стоимость +${fmt((c.kBelow - 1) * 100, 1)} %` : ''}`;
  profile.setDetour({pts: prof, h: c.H, label});
  if ($('#profile').hidden) openProfileKeep();
  toast(`${label[0].toUpperCase()}${label.slice(1)}. Форма примерная.`, 'info', 4000);
}
function openProfileKeep() { const d = profile.detour; openProfile(); requestAnimationFrame(() => requestAnimationFrame(() => profile.setDetour(d))); }

// Вид на пересечение сбоку, поперёк новой трубы, чуть сверху — видно, кто над кем
function crossingPose(c) {
  const s = c.seg;
  let dir = [1, 0];
  for (let i = 1; i < s.pts.length; i++) if (s.lens[i] >= c.s - 1e-6) { const a = s.pts[i - 1], b = s.pts[i], l = Math.hypot(b[0] - a[0], b[1] - a[1]) || 1; dir = [(b[0] - a[0]) / l, (b[1] - a[1]) / l]; break; }
  // направление существующей трубы в точке пересечения
  let other = null;
  const ln = c.type !== 'segment' && (S.model.utilities[c.type] || []).find(l => String(l.id) === String(c.eid));
  if (ln) {
    let best = Infinity;
    for (let i = 1; i < ln.pts.length; i++) {
      const a = ln.pts[i - 1], b = ln.pts[i], ux = b[0] - a[0], uy = b[1] - a[1], L = Math.hypot(ux, uy) || 1;
      const t = Math.max(0, Math.min(1, ((c.x - a[0]) * ux + (c.y - a[1]) * uy) / (L * L))), d = Math.hypot(a[0] + ux * t - c.x, a[1] + uy * t - c.y);
      if (d < best) { best = d; other = [ux / L, uy / L]; }
    }
  }
  // взгляд под углом к обеим трубам (биссектриса), а не вдоль существующей — иначе она закрывает место торцом
  let view = [-dir[1], dir[0]];
  if (other) {
    const cands = [[dir[0] + other[0], dir[1] + other[1]], [dir[0] - other[0], dir[1] - other[1]]].map(v => { const l = Math.hypot(v[0], v[1]) || 1; return [v[0] / l, v[1] / l]; });
    const side = v => Math.min(Math.abs(v[0] * dir[1] - v[1] * dir[0]), Math.abs(v[0] * other[1] - v[1] * other[0]));
    view = side(cands[0]) >= side(cands[1]) ? cands[0] : cands[1];
  }
  const yaw = Math.atan2(view[0], view[1]), pitch = -18 * D;
  const P = new THREE.Vector3(c.x, c.y, viewer.z(c.type === 'segment' ? s.d0 : c.hn + c.H / 2));
  const dist = 16 + 2 * viewer.ex, cp = Math.cos(pitch);
  const pos = P.clone().addScaledVector(new THREE.Vector3(Math.sin(yaw) * cp, Math.cos(yaw) * cp, Math.sin(pitch)), -dist);
  return {P, pos, yaw, pitch};
}
function crossingView(c) {
  const {pos, yaw, pitch} = crossingPose(c);
  rig.animateTo({pos, yaw, pitch, done: () => ensureOutside()}, 700);
}
// Камера не должна оказаться внутри трубы или колодца: отходим назад по взгляду, пока не выйдем
function ensureOutside() {
  const f = rig.forward();
  for (let k = 0; k < 12; k++) {
    const hit = viewer.insideAt(rig.pos);
    if (!hit) return;
    rig.pos.addScaledVector(f, -(hit + 1.5));
    rig.apply();
  }
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
  const nn = id => nodeName(v?.nodes.find(n => n.id === id) || {id, type: M.oks.some(o => o.id === id) ? 'oks_entry' : ''}, M);
  const gapText = c => c.gap < 0 ? `трубы пересекаются по высоте, наложение ${fmt(-c.gap, 2)} м` : `зазор ${fmt(c.gap, 2)} м`;
  const slopeNote = s => Math.abs(s.d1 - s.d0) / s.len < 1e-6 ? (M.hasDepth ? ' (горизонтально; минимальный уклон правилами расчёта не задан)' : ' (расчёт без глубины, уклон не задаётся)') : '';
  switch (sel.kind) {
    case 'seg': {
      const s = r, bad = s.cx.filter(c => c.ok === false);
      const flat = Math.abs(s.d1 - s.d0) / s.len < 0.002;
      return {kind: 'Новая труба', title: `${segName(s)}: ${nn(s.from)} → ${nn(s.to)}`, info: flat ? {t: 'Уклон меньше 0,002', more: SLOPE_NOTE} : null, rows: [['Диаметр', `Ду ${s.p.diameter}`], ['Длина', meters(s.p.length ?? s.len)], ['Прокладка', s.special ? 'спецпереход в футляре' : 'обычная, в канале'],
        ['Глубина верха', s.d0 === s.d1 ? ND(s.d0) : `${ND(s.d0)} → ${ND(s.d1)}`], ['Уклон', fmt(Math.abs(s.d1 - s.d0) / s.len, 3) + slopeNote(s)], ['Расход теплоносителя', s.p.flow_tph != null ? fmt(s.p.flow_tph, 2) + ' т/ч' : '—'],
        ['Стоимость', rub(s.p.cost)], ['Пересечений с сетями', s.cx.length]],
        problem: s.issues.length || bad.length ? [...s.issues, ...bad.map(c => `${UTILITIES[c.type].name.toLowerCase()}: ${gapText(c)}${c.need != null ? `, нужен зазор ${fmt(c.need, 2)} м` : ''}`)].join('<br>') : null};
    }
    case 'oks': {
      const conn = v ? v.connected.has(r.id) : null, R = S.route;
      const rows = [['Расход теплоносителя', r.flow != null ? fmt(r.flow, 2) + ' т/ч' : '—']];
      if (r.load != null) rows.push(['Тепловая нагрузка', fmt(r.load, 3) + ' Гкал/ч']);
      if (r.building) rows.push(['Этажей', r.building.p.levels ?? '—']);
      if (v) rows.push(['Подключено', conn ? 'да' : 'нет']);
      if (R) rows.push(['Трасса от врезки', meters(R.len)], ['Участков', R.segs.length], ['Стоимость трассы', rub(R.cost)], ['Ду на вводе', `Ду ${R.segs[R.segs.length - 1].p.diameter}`], ['Врезка', nn(R.start)]);
      const flatN = R ? R.segs.filter(s => Math.abs(s.d1 - s.d0) / s.len < 0.002).length : 0;
      return {kind: 'Здание к подключению', title: r.label, rows, info: flatN ? {t: `${flatN} из ${R.segs.length} ${plural(R.segs.length, 'участка', 'участков', 'участков')} с уклоном меньше 0,002`, more: SLOPE_NOTE} : null,
        problem: v && !conn ? `Не подключено в этом варианте, штраф ${rub(500000 * (r.flow || 0) + 1e8)}. Причину сервис не сообщает: проверьте запретные зоны и отступы рядом со зданием и предельную длину трассы для Ду.${M.variants.length > 1 ? ' Посмотрите другие варианты.' : ''}` : R?.bad ? `<div class="row between"><span>${R.bad} ${plural(R.bad, 'проблема', 'проблемы', 'проблем')} по высоте на трассе</span><button type="button" data-gocx>Показать</button></div>` : null,
        ok: R && !R.bad ? 'Трасса без проблем по высоте' : null};
    }
    case 'crossing': {
      const c = r;
      if (c.type === 'segment') return {kind: 'Нарушение на участке', title: segName(c.seg), sub: c.seg.id, rows: [['Глубина', `${ND(c.seg.d0)} → ${ND(c.seg.d1)}`], ['Длина', meters(c.seg.len)]], problem: c.issues.join('<br>')};
      const u = UTILITIES[c.type];
      const rows = [['Пересекает', u.name.toLowerCase()], ['Существующая: верх–низ', `${fmt(c.top, 2)}–${fmt(c.top + c.eh, 2)} м`], ['Новая: верх–низ', `${fmt(c.hn, 2)}–${fmt(c.hn + c.H, 2)} м`],
        ['Новая проходит', c.gap < 0 ? 'насквозь — трубы пересекаются' : c.side], ['Зазор по высоте', c.gap < 0 ? `наложение ${fmt(-c.gap, 2)} м` : fmt(c.gap, 2) + ' м'], ['Нужно не меньше', c.need == null ? 'правилами не задано' : fmt(c.need, 2) + ' м'], ['Участок', segName(c.seg)]];
      let problem = null, fix = null;
      if (c.ok === false) {
        problem = c.need == null
          ? 'Трубы перекрываются по высоте. Норма зазора для этой сети не задана, глубина сети условная: уточните её по изысканиям.'
          : c.gap < 0 ? 'Трубы перекрываются по высоте.' : 'Зазор меньше нормы.';
        fix = [c.above != null ? `<b>Поднять</b> трубу так, чтобы верх был не глубже ${fmt(c.above, 2)} м. Стоимость не изменится.` : 'Поднять нельзя: труба окажется мельче 0,7 м.',
          `<b>Опустить</b> трубу так, чтобы верх был не мельче ${fmt(c.below, 2)} м. ${c.kBelow > 1 ? `Стоимость вырастет на ${fmt((c.kBelow - 1) * 100, 1)} % (Kгл ${fmt(c.kBelow, 3)}).` : 'Стоимость не изменится.'}`].join('<br>') +
          `<div class="row" style="margin-top:8px;flex-wrap:wrap">${c.above != null ? '<button type="button" data-detour="up">Показать обход сверху</button>' : ''}<button type="button" data-detour="down">Показать обход снизу</button></div>`;
      }
      return {kind: 'Пересечение по высоте', title: `${u.name} × новая труба`, rows, problem, fix, ok: c.ok === true ? 'Зазор достаточный' : c.ok == null ? 'Трубы не пересекаются. Норма зазора для этой сети не задана, глубина условная.' : null};
    }
    case 'node': {
      const n = r, t = n.type === 'existing_chamber' ? 'Врезка в существующую камеру' : n.type === 'oks_entry' ? 'Ввод в здание' : (NODE_TYPES[n.type] || {name: n.type}).name;
      const segs = v ? v.segs.filter(s => s.from === n.id || s.to === n.id) : [];
      const nm = nodeName(n, M);
      return {kind: t, title: nm[0].toUpperCase() + nm.slice(1), rows: [['Глубина низа трубы', ND(n.depth)], ['Ду', n.dn || n.p.diameter || '—'], ['Участков в узле', segs.length], ...(n.p.cost ? [['Стоимость', rub(n.p.cost)]] : []),
        ...(n.p.existing_object_id ? [['На существующем объекте', n.p.existing_object_id]] : [])]};
    }
    case 'utility': {
      const u = UTILITIES[sel.type], ln = r;
      return {kind: 'Существующая сеть', title: u.name, sub: `№ ${ln.id}`, rows: [['Глубина верха', fmt(ln.top, 1) + ' м' + (u.assumed ? ' (условно)' : '')], ['Высота габарита', ND(ln.h)], ...(ln.dn ? [['Ду', ln.dn]] : []),
        ['Нужный зазор с новой трубой', u.clear == null ? 'правилами не задан' : fmt(u.clear, 1) + ' м'], ['Длина', meters(ln.pts.reduce((a, p, i) => i ? a + Math.hypot(p[0] - ln.pts[i - 1][0], p[1] - ln.pts[i - 1][1]) : 0, 0))]]};
    }
    case 'chamber': return {kind: 'Существующая камера', title: `Камера ${String(r.id).replace(/^hc-/, '')}`, sub: r.id, rows: [...(r.p.diameter ? [['Ду', r.p.diameter]] : []), ['Глубина', `около ${fmt(UTILITIES.heat_network.top, 1)} м`]]};
    case 'source': return {kind: 'Источник тепла', title: r.p.name || `Источник ${r.id}`, sub: r.id, rows: [...(r.p.operator ? [['Владелец', r.p.operator]] : [])]};
    case 'surface': {
      const it = r, name = (SURFACES[sel.type] || {name: sel.type}).name, b = BUILDING_KEYS.has(sel.type);
      const rows = [];
      if (it.p.address) rows.push(['Адрес', it.p.address]);
      if (b) rows.push(['Этажей', it.p.levels ?? '—'], ['Высота в 3D', meters(buildingHeight(it))]);
      if (it.p.name) rows.push(['Название', it.p.name]);
      if (sel.type === 'road' || sel.type === 'tram_tracks') rows.push(['Новая труба под ним', `верх не мельче ${sel.type === 'road' ? '1,0' : '1,2'} м, переход в футляре`]);
      return {kind: b ? (it.isFuture ? 'Новое здание' : 'Здание') : 'Поверхность', title: b ? (it.p.address || (it.isFuture ? 'Новое здание' : 'Здание')) : name, rows};
    }
  }
  return {kind: '', title: '', rows: []};
}
function showProps(sel) {
  const d = describe(sel), el = $('#props');
  el.hidden = false;
  document.body.classList.add('propsOpen');
  // на телефоне выбранное должно быть видно: панель слева прячем, лист со свойствами свёрнут, если есть профиль
  if (isMobile()) { document.body.classList.remove('leftOpen'); el.classList.toggle('min', !!S.route); }
  const hasRoute = !!S.route;
  el.innerHTML = `<div class="grab" id="pxGrab" title="Свернуть или развернуть"></div><div class="kind">${esc(d.kind)}</div>
    <div class="h"><b>${esc(d.title)}</b>${isMobile() ? '<button class="icon" id="pxMin" aria-label="Свернуть карточку" title="Свернуть карточку"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 9 6 6 6-6"/></svg></button>' : ''}<button class="icon" id="pxX" title="Закрыть (Esc)" aria-label="Закрыть">×</button></div>
    ${d.sub ? `<div class="sub">${esc(d.sub)}</div>` : ''}
    <div class="acts">
      <button id="pxGo" class="primary" title="Навести камеру на объект (F)">Показать</button>
      <button id="pxClip" title="Оставить на сцене только куб вокруг объекта (B)">${S.clip ? 'Убрать срез' : 'Срез вокруг'}</button>
      ${hasRoute ? '<button id="pxFly" title="Пролёт камерой вдоль трассы (P)">Пролёт</button><button id="pxProf" title="Продольный профиль трассы">Профиль</button>' : ''}
    </div>
    <table>${d.rows.filter(r => r[1] != null && r[1] !== '—').map(r => `<tr><td>${esc(r[0])}</td><td>${esc(r[1])}</td></tr>`).join('')}</table>
    ${d.problem ? `<div class="note prob">${d.problem}</div>` : ''}${d.info ? `<details class="note warn"><summary>${esc(d.info.t)}</summary>${esc(d.info.more)}</details>` : ''}${d.fix ? `<div class="note fix"><b>Как исправить</b><br>${d.fix}</div>` : ''}${d.ok ? `<div class="note fix">${esc(d.ok)}</div>` : ''}`;
  $('#pxX').onclick = () => clearSel();
  if ($('#pxMin')) $('#pxMin').onclick = () => toggleCard();
  layout();
  // лист на телефоне: касание или смахивание заголовка — свернуть/развернуть
  const head = [$('#pxGrab'), el.querySelector('.kind'), el.querySelector('.h b')];
  let y0 = null;
  for (const h of head) {
    h.onclick = () => { if (isMobile()) el.classList.toggle('min'); };
    h.onpointerdown = e => { y0 = e.clientY; };
    h.onpointerup = e => { if (y0 == null || !isMobile()) return; const dy = e.clientY - y0; if (dy > 25) el.classList.add('min'); if (dy < -25) el.classList.remove('min'); y0 = null; };
  }
  $('#pxGo').onclick = () => frameSel();
  $('#pxClip').onclick = () => toggleClip();
  if (hasRoute) {
    $('#pxFly').onclick = () => startFly();
    $('#pxProf').onclick = () => { if ($('#profile').hidden) { openProfile(); if (isMobile()) el.classList.add('min'); } else closeProfile(); };
  }
  // «Показать обход»: зелёная труба на рекомендуемой глубине в 3D и пунктир на профиле
  $$('#props [data-detour]').forEach(b => b.onclick = () => showDetour(sel.ref, b.dataset.detour));
  // «Показать» у проблем трассы — к первому месту, как переход к результату поиска
  const go = $('#props [data-gocx]');
  if (go) go.onclick = () => { const R = S.route, c = R?.segs.flatMap(s => s.cx).find(c => c.ok === false) || S.v?.crossings.find(c => c.type === 'segment' && R?.segs.includes(c.seg)); if (c) selectCrossing(c); };
}

// ---------- профиль ----------
function openProfile() {
  const R = S.route;
  $('#profile').hidden = false;
  const o = S.model.oks.find(x => x.id === R.end);
  $('#pTitle').innerHTML = `Профиль: ${esc(nodeLabel(R.start))} → ${esc(o ? o.label : nodeLabel(R.end))} · ${meters(R.len)} · ${rub(R.cost)} · ` +
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
  if (isMobile() && document.body.classList.contains('leftOpen')) { document.body.classList.remove('leftOpen'); layout(); return; }
  if (S.measure) return measureClick(x, y);
  const h = pickAt(x, y), sel = hitToSel(h);
  setXY(h?.point || rig.onPlane(x, y, 0, 1e6));
  if (!sel) { clearSel(); return; }
  select(sel, {room: true});
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
    setXY(P);
    const t = h ? hoverText(h) : null;
    $('#stHover').innerHTML = t ? t.status : '';
    const tip = $('#tip'), r = $('#view').getBoundingClientRect();
    if (!t || S.measure || !$('#ctx').hidden) { tip.hidden = true; viewer.renderer.domElement.style.cursor = S.measure ? 'crosshair' : ''; return; }
    tip.hidden = false;
    tip.innerHTML = `<b>${t.title}</b>${t.lines.map(l => `<div>${l}</div>`).join('')}<div class="m">щёлкните, чтобы выбрать</div>`;
    const tx = r.left + hx + 16, ty = r.top + hy + 16;
    tip.style.left = Math.min(tx, innerWidth - tip.offsetWidth - 8) + 'px';
    tip.style.top = Math.min(ty, innerHeight - tip.offsetHeight - 8) + 'px';
    viewer.renderer.domElement.style.cursor = 'pointer';
  });
}
// координаты в строке состояния: под курсором, а на планшете — точка последнего касания
function setXY(P) { if (!P || !S.model) return; const [lon, lat] = S.model.proj.ll(P.x, P.y); $('#stXY').textContent = `${lat.toFixed(6)}, ${lon.toFixed(6)} · X ${fmt(P.x)} Y ${fmt(P.y)} м от центра набора`; }
$('#view').addEventListener('pointerleave', () => { hoverXY = null; $('#tip').hidden = true; });
function hoverText(h) {
  const r = h.ref;
  if (h.kind === 'seg') {
    const d = ((h.on || h.point).z / -viewer.ex) - r.row.h / 2;
    return {title: `Новая труба Ду ${r.p.diameter}`, lines: [`${segName(r)} · ${meters(r.p.length ?? r.len)}`, `верх на глубине ${fmt(Math.max(0, d), 2)} м${r.special ? ' · спецпереход' : ''}`, r.bad ? '<span style="color:#f9a8d4">есть проблема по высоте</span>' : ''].filter(Boolean),
      status: `<b>Новая труба</b> ${segName(r)} · Ду ${r.p.diameter} · верх ${fmt(Math.max(0, d), 2)} м`};
  }
  if (h.kind === 'utility') { const u = UTILITIES[h.type]; return {title: u.name, lines: [`верх ${fmt(r.top, 1)} м${u.assumed ? ' (условно)' : ''}${r.dn ? ` · Ду ${r.dn}` : ''}`], status: `<b>${u.name}</b> · верх ${fmt(r.top, 1)} м`}; }
  if (h.kind === 'oks') { const c = S.v ? S.v.connected.has(r.id) : null; return {title: esc(r.label), lines: [`расход ${fmt(r.flow, 1)} т/ч`, c == null ? '' : c ? '<span style="color:#86efac">подключено</span>' : '<span style="color:#fda4af">не подключено</span>'].filter(Boolean), status: `<b>${esc(r.label)}</b>`}; }
  if (h.kind === 'node') { const t = nodeName(r, S.model); return {title: t[0].toUpperCase() + t.slice(1), lines: [`низ трубы ${ND(r.depth)}`], status: `<b>${esc(t)}</b>`}; }
  if (h.kind === 'chamber') { const no = esc(String(r.id).replace(/^hc-/, '')); return {title: `Существующая камера ${no}`, lines: [], status: `<b>Камера ${no}</b>`}; }
  if (h.kind === 'source') return {title: 'Источник тепла', lines: [esc(r.p.name || r.id)], status: `<b>Источник</b> ${esc(r.p.name || r.id)}`};
  if (h.kind === 'surface') {
    const n = (SURFACES[h.type] || {name: h.type}).name;
    if (BUILDING_KEYS.has(h.type)) return {title: esc(r.p.address || (r.isFuture ? 'Новое здание' : 'Здание')), lines: [r.p.levels ? `${r.p.levels} эт.` : 'этажность не задана'], status: `<b>${esc(r.p.address || 'Здание')}</b>${r.p.levels ? ` · ${r.p.levels} эт.` : ''}`};
    return {title: n, lines: [h.type === 'road' ? 'новая труба под дорогой: верх не мельче 1,0 м' : h.type === 'tram_tracks' ? 'новая труба под путями: верх не мельче 1,2 м' : esc(r.id)], status: n};
  }
  return null;
}

// ---------- контекстное меню (как правый щелчок в Картах) ----------
function showCtx(x, y) {
  if (!S.model) return;
  const h = pickAt(x, y), P = h?.point || rig.pivotAt(x, y), [lon, lat] = S.model.proj.ll(P.x, P.y);
  const sel = hitToSel(h), menu = $('#ctx');
  const coords = `${lat.toFixed(6)}, ${lon.toFixed(6)}`;
  menu.innerHTML = `${sel ? `<button data-c="sel">Что здесь: ${esc(describe(sel).title).slice(0, 40)}</button>` : ''}
    <button data-c="zoom">Приблизить сюда</button>
    ${viewStack.length ? '<button data-c="back">Предыдущий вид <kbd>Backspace</kbd></button>' : ''}
    <button data-c="top">Вид сверху на эту точку</button>
    <button data-c="side">Вид сбоку на эту точку</button>
    <button data-c="clip">Срез вокруг этой точки</button>
    <button data-c="measure">Измерить расстояние отсюда</button>
    <hr><button data-c="copy" title="Скопировать координаты">Скопировать ${coords}</button>
    <div class="lbl">${P.z < -0.05 ? `глубина ${fmt(-P.z / viewer.ex, 2)} м` : 'на поверхности'}</div>`;
  const r = $('#view').getBoundingClientRect();
  menu.hidden = false;
  $('#tip').hidden = true;
  menu.style.left = Math.min(x, r.width - menu.offsetWidth - 8) + 'px';
  menu.style.top = Math.min(y, r.height - menu.offsetHeight - 8) + 'px';
  menu.onclick = e => {
    const b = e.target.closest('button');
    if (!b) return;
    hideCtx();
    const c = b.dataset.c, dist = rig.pos.distanceTo(P);
    if (c === 'sel') select(sel, {fly: false});
    if (c === 'zoom') rig.animateTo({pos: P.clone().add(rig.pos.clone().sub(P).multiplyScalar(0.35)), yaw: rig.yaw, pitch: rig.pitch}, 500);
    if (c === 'back') backView();
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
    // новая сеть — главное на экране: кадр по ней, а не по всем зданиям района
    for (const s of S.v.segs) for (const p of s.pts) { b[0] = Math.min(b[0], p[0]); b[1] = Math.min(b[1], p[1]); b[2] = Math.max(b[2], p[0]); b[3] = Math.max(b[3], p[1]); }
    return [b[0], b[1], -10, b[2], b[3], 20];
  }
  const [x0, y0, x1, y1] = M.bbox;
  return [x0, y0, -10, x1, y1, 20];
}
// Исходный вид: вся новая сеть с зданиями, на север, наклон 52° — как «Домой» у ViewCube
function home(ms = 800) {
  if (rig.mode === 'fly') setMode('orbit', true);   // «Домой» возвращает и обычный режим мыши, как в Navisworks
  if (S.model) rig.frame(homeBox(), {yaw: 0, pitch: -52 * D, ms, pad: 1.05});
}
function orient(dir, name) {
  const f = dir.clone().negate();
  let pitch = Math.asin(clamp(f.z, -1, 1));
  const yaw = Math.abs(f.z) > 0.999 ? rig.yaw : Math.atan2(f.x, f.y);
  if (Math.abs(f.z) < 0.2) pitch = -6 * D;   // вид сбоку — чуть сверху, чтобы видеть и землю, и трубы под ней
  // как Zoom Extents: вписываем выбранное, иначе новую сеть
  rig.frame(selBox() || homeBox(), {yaw, pitch, pad: 1.1});
  status(name);
}
function toggle2d() {
  const P = rig.center(), dist = Math.max(30, rig.pos.distanceTo(P));
  if (rig.pitch < -80 * D) rig.lookAtFrom(P, rig.yaw, -45 * D, dist); else rig.lookAtFrom(P, rig.yaw, -90 * D, dist);
}
function updateModeUi() {
  const fly = rig.mode === 'fly';
  status();
}
function setMode(m, quiet = false) { rig.mode = m; updateModeUi(); if (!quiet) toast(m === 'fly' ? 'Режим «Полёт»: мышь поворачивает взгляд, W A S D — лететь, Q и E — вниз и вверх, Esc — выйти' : 'Режим «Орбита»: левая кнопка двигает сцену, правая вращает', 'info', 2600); }
function status(extra) {
  const fly = rig.mode === 'fly';
  $('#stHint').innerHTML = extra ? `<b>${esc(extra)}</b>` : S.measure ? '<b>Линейка:</b> щёлкните две точки · Esc — выйти'
    : fly ? '<b>Полёт:</b> <b>мышь</b> поворачивает взгляд · <b>WASD</b> лететь · <b>Q/E</b> вниз/вверх · <b>Shift</b> быстрее · <b>Esc</b> выйти'
      : '<b>левая</b> двигать · <b>правая</b> вращать · <b>колесо</b> масштаб · <b>WASD</b> лететь';
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
  // глубже 5 см — уже под землёй; на самой поверхности «под землёй, 0,0 м» только путает
  const above = cam.z > -0.05 * viewer.ex;
  $('#stCam').textContent = above ? (cam.z < 0.5 ? 'у поверхности земли' : `вид с высоты ${meters(cam.z)}`) : `под землёй, ${fmt(-cam.z / viewer.ex, 1)} м · E — вверх, H — общий вид`;
  $('#camChip').hidden = above;
  $('#camChip').innerHTML = `Под землёй, ${fmt(-cam.z / viewer.ex, 1)} м <b>Наверх</b>`;
  if ($('#exChip')) $('#exChip').innerHTML = viewer.ex === 1 ? 'Глубина <b>×1</b> как есть' : `Глубина растянута <b>×${viewer.ex}</b>`;
  const top = rig.pitch < -80 * D;
  $('#v2d').classList.toggle('is2d', top);
  $('#v2d').title = top ? 'Сейчас вид сверху (2D). Щёлкните — 3D-наклон (T)' : 'Сейчас 3D. Щёлкните — вид сверху (T)';
}

// ---------- рентген, срез, линейка ----------
function setXray(on) { S.xray = on; rig.allowUnder = on; viewer.opts.xray = on; viewer.applyOpts(); $('#xrayBtn').classList.toggle('on', on); }
function boxAround(P, r) { return [P.x - r, P.y - r, Math.min(P.z, 0) - 12 * viewer.ex - 2, P.x + r, P.y + r, Math.max(P.z, 0) + 60]; }
function setClip(box) {
  S.clip = box;
  viewer.setClipBox(box);
  if ($('#pxClip')) $('#pxClip').textContent = box ? 'Убрать срез' : 'Срез вокруг';
  if (box) status('Срез: видно только то, что внутри рамки · [ ] — меньше/больше · B — выключить');
}
function toggleClip(force) {
  if (S.clip && !force) return setClip(null);
  if (S.sel?.kind === 'crossing') {
    // вокруг места конфликта: рамка ±10 м, камера снаружи рамки — разрез, а не вид изнутри трубы
    const c = S.sel.ref, {P, pos, yaw, pitch} = crossingPose(c), deep = (c.top ?? 3) + (c.eh ?? 0.5) + 2.5;
    setClip([P.x - 10, P.y - 10, -deep * viewer.ex, P.x + 10, P.y + 10, 3]);
    const out = pos.clone().sub(P).setLength(Math.max(30, 16 * 1.8));
    rig.animateTo({pos: P.clone().add(out), yaw, pitch, done: () => ensureOutside()}, 600);
    return;
  }
  let b = selBox();
  if (b) { const m = 15; b = [b[0] - m, b[1] - m, Math.min(b[2], 0) - 6 * viewer.ex, b[3] + m, b[4] + m, Math.max(b[5], 0) + 40]; }
  else b = boxAround(rig.center(), 60);
  setClip(b);
}
function setMeasure(on) {
  if (on) { S.measure = {pts: []}; $('#measure').hidden = false; $('#measure').innerHTML = '<span>Линейка: укажите первую точку на трубе, здании или земле</span><button id="mX">Готово</button>'; $('#mX').onclick = () => setMeasure(false); }
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
  if (m.pts.length === 1) { $('#measure').firstElementChild.textContent = 'Линейка: укажите вторую точку'; return; }
  const [a, b] = m.pts, ex = viewer.ex;
  const plan = Math.hypot(b.x - a.x, b.y - a.y), dz = (b.z - a.z) / (b.z <= 0 && a.z <= 0 ? ex : 1), d3 = Math.hypot(plan, dz);
  const geo = new THREE.BufferGeometry().setFromPoints([a, b]);
  const line = new THREE.Line(geo, new THREE.LineBasicMaterial({color: 0x22d3ee, depthTest: false}));
  line.name = 'mline'; line.renderOrder = 60;
  const lb = viewer.label(`${meters(plan)}`, 'lbl meas');
  lb.position.copy(a.clone().add(b).multiplyScalar(0.5));
  line.add(lb);
  viewer.g.tools.add(line);
  $('#measure').firstElementChild.innerHTML = `В плане <b>${meters(plan)}</b> · перепад <b>${fmt(Math.abs(dz), 2)} м</b> · по прямой <b>${meters(d3)}</b>`;
  viewer.redraw();
}

// ---------- пролёт по трассе ----------
// кнопка плеера: пауза во время пролёта, «играть» на паузе, «повторить» в конце трассы
const PLAYER = {
  pause: ['Пауза (пробел)', '<path d="M9 5v14M15 5v14"/>'],
  play: ['Продолжить (пробел)', '<path d="M7 4.5v15l12-7.5z"/>'],
  again: ['Повторить', '<path d="M4 12a8 8 0 1 0 2.4-5.7"/><path d="M4 4v4.5h4.5"/>'],
};
function playBtn(state) { const [t, d] = PLAYER[state], b = $('#plPlay'); b.title = t; b.setAttribute('aria-label', t.replace(/ \(.*\)$/, '')); b.innerHTML = `<svg viewBox="0 0 24 24">${d}</svg>`; }
function startFly() {
  const R = S.route;
  if (!R) return toast('Сначала выберите здание или трубу: камера пролетит по трассе к ней', 'info');
  setClip(null);
  // после пролёта вернёмся туда, откуда начали; первый кадр — сразу у врезки, без перелёта через полрайона
  S.fly = {at: 0, playing: true, speed: +$('#plSpeed').value, last: performance.now(), prev: rig.state, jump: true};
  document.body.classList.add('flying');
  rig.noContext = true;
  $('#plEnd').hidden = true;
  $('#player').hidden = false;
  playBtn('pause');
  setXray(true);
  const step = now => {
    const F = S.fly;
    if (!F) return;
    const dt = Math.min(0.1, (now - F.last) / 1000);
    F.last = now;
    if (F.playing) { F.at = Math.min(R.len, F.at + dt * 9 * F.speed); if (F.at >= R.len) { F.playing = false; playBtn('again'); $('#plEnd').hidden = false; } }
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
  if (S.fly?.jump) { S.fly.jump = false; rig.yaw = yaw; rig.pitch = pitch; rig.pos.copy(pos); }
  else { rig.yaw += dy * 0.12; rig.pitch += (pitch - rig.pitch) * 0.12; rig.pos.lerp(pos, 0.25); }
  rig.apply();
  profile.setCursor(at);
  $('#plPos').value = Math.round(at / R.len * 1000);
  $('#plTxt').textContent = `${fmt(at)} из ${fmt(R.len)} м · верх ${fmt(p.d, 2)} м · Ду ${p.seg.p.diameter}`;
}
function stopFly(restore = true) {
  if (!S.fly) return;
  const prev = restore ? S.fly.prev : null;
  S.fly = null;
  document.body.classList.remove('flying');
  rig.noContext = false;
  if (prev) rig.animateTo({pos: new THREE.Vector3(...prev.pos), yaw: prev.yaw * D, pitch: prev.pitch * D, back: true}, 700);
  $('#player').hidden = true;
  setXray(false);
  viewer.marker('pcur', null);
  profile.setCursor(null);
  status();
}
$('#plPlay').onclick = () => { if (!S.fly) return; if (S.fly.at >= S.route.len) { S.fly.at = 0; S.fly.jump = true; $('#plEnd').hidden = true; } S.fly.playing = !S.fly.playing; playBtn(S.fly.playing ? 'pause' : 'play'); };
$('#plPos').oninput = e => { if (!S.fly) return; S.fly.playing = false; playBtn('play'); S.fly.at = e.target.value / 1000 * S.route.len; };
$('#plSpeed').onchange = e => { if (S.fly) S.fly.speed = +e.target.value; };
$('#plStop').onclick = () => stopFly();
$('#plAgain').onclick = () => { if (!S.fly) return; S.fly.at = 0; S.fly.playing = true; S.fly.jump = true; $('#plEnd').hidden = true; playBtn('pause'); };
$('#plToEnd').onclick = () => { stopFly(false); frameSel(); };
// любое ручное управление камерой во время пролёта — пауза, а не борьба с пользователем
viewer.renderer.domElement.addEventListener('pointerdown', () => { if (S.fly?.playing) { S.fly.playing = false; playBtn('play'); } });

// ---------- поиск ----------
let searchIdx = null;
// Поиск по словам в любом порядке, как в Картах: «щусева 3», «Шусева» (опечатка), «тэц», «камера 19», «участок 6»
const STOP = new Set(['ул', 'улица', 'д', 'дом', 'корп', 'корпус', 'к', 'стр', 'строение', 'пр', 'просп', 'проспект', 'пер', 'переулок', 'наб', 'набережная', 'ш', 'шоссе', 'г', 'город']);
const words = s => String(s ?? '').toLowerCase().replace(/ё/g, 'е').replace(/(\d+)\s*(?:к|корп|корпус|стр|строение)\.?\s*(\d+)/g, '$1 $2').replace(/[.,;:№#"«»()\-_/]+/g, ' ').split(/\s+/).filter(w => w && !STOP.has(w));
// одна правка (замена, вставка или удаление буквы)
function near1(a, b) {
  if (a === b) return true;
  if (Math.abs(a.length - b.length) > 1) return false;
  let i = 0, j = 0, e = 0;
  while (i < a.length && j < b.length) {
    if (a[i] === b[j]) { i++; j++; continue; }
    if (++e > 1) return false;
    if (a.length > b.length) i++; else if (b.length > a.length) j++; else { i++; j++; }
  }
  return e + (a.length - i) + (b.length - j) <= 1;
}
// 3 — слово целиком, 2 — начало слова, 1 — опечатка; 0 — не подходит
function wordScore(w, toks) {
  let best = 0;
  for (const t of toks) {
    if (t === w) return 3;
    if (t.startsWith(w)) best = Math.max(best, 2);
    else if (w.length >= 4 && (near1(w, t) || near1(w, t.slice(0, w.length)))) best = Math.max(best, 1);
  }
  return best;
}
function matchWords(q, text) { const qs = words(q), ts = words(text); return qs.length > 0 && qs.every(w => wordScore(w, ts) > 0); }
function buildIndex() {
  // ids — служебные номера: совпадение по ним ниже совпадения по имени и только по слову целиком
  const M = S.model, idx = [], add = (text, label, sub, extra = {}) => idx.push({...extra, toks: words(text), ids: words(extra.ids || ''), label, sub});
  for (const o of M.oks) add([o.label, 'здание'].join(' '), o.label, 'здание к подключению', {sel: {kind: 'oks', ref: o}, ids: [o.id, o.oksId].join(' ')});
  for (const s of M.points.source || []) add([s.p.name, s.id, 'источник тэц котельная'].join(' '), s.p.name || 'Источник ' + s.id, 'источник тепла', {sel: {kind: 'source', ref: s}});
  for (const v of M.variants) {
    for (const s of v.segs) add([segName(s), 'участок труба'].join(' '), `${segName(s)} · Ду ${s.p.diameter}`, `новая труба, вариант ${v.id}`, {sel: {kind: 'seg', ref: s}, v: v.id, ids: s.id});
    for (const n of v.nodes) if (!n.virtual && n.type !== 'technical_node') { const nm = nodeName(n, M); add([nm, 'камера'].join(' '), nm[0].toUpperCase() + nm.slice(1), `новая камера, вариант ${v.id}`, {sel: {kind: 'node', ref: n}, v: v.id, ids: n.id}); }
  }
  for (const c of M.points.heat_chamber || []) add(['камера', c.id].join(' '), `Камера ${String(c.id).replace(/^hc-/, '')}`, 'существующая камера', {sel: {kind: 'chamber', ref: c}});
  for (const [t, l] of Object.entries(M.utilities)) for (const ln of l) add([UTILITIES[t].name, ln.id].join(' '), `${UTILITIES[t].name} ${ln.id}`, 'существующая сеть', {sel: {kind: 'utility', type: t, ref: ln}, weak: true});
  const seen = new Set(M.oks.map(o => o.name).filter(Boolean));
  for (const k of BUILDING_KEYS) for (const it of M.surfaces[k] || []) if (it.p.address && !seen.has(it.p.address)) { seen.add(it.p.address); add([it.p.address, it.id].join(' '), it.p.address, 'здание', {sel: {kind: 'surface', type: k, ref: it}}); }
  return idx;
}
function searchFor(q) {
  q = q.trim();
  if (!q || !S.model) return [];
  const m = q.match(/^(-?\d{1,3}[.,]\d+)\s*[,; ]\s*(-?\d{1,3}[.,]\d+)$/);
  if (m) {
    let a = +m[1].replace(',', '.'), b = +m[2].replace(',', '.');
    if (!(a > 40 && a < 80) && b > 40 && b < 80) [a, b] = [b, a];   // широта, долгота — как в Яндекс Картах
    return [{label: `Координаты ${a}, ${b}`, sub: 'перелететь сюда', coords: [b, a]}];
  }
  searchIdx ||= buildIndex();
  const qs = words(q);
  if (!qs.length) return [];
  const hits = [];
  for (const e of searchIdx) {
    if (e.v && S.v && e.v !== S.v.id) continue;
    let sc = 0;
    for (const w of qs) { const k = wordScore(w, e.toks); if (!k) { sc = 0; break; } sc += k; }
    if (!sc && e.ids.length && qs.every(w => e.ids.includes(w) || (w.length >= 6 && e.ids.some(t => t.startsWith(w))))) sc = 0.6 * qs.length;
    if (sc) hits.push([sc - (e.weak ? 0.5 : 0), e]);
  }
  hits.sort((a, b) => b[0] - a[0]);
  const out = [], labels = new Set();
  for (const [, e] of hits) { if (labels.has(e.label + e.sub)) continue; labels.add(e.label + e.sub); out.push(e); if (out.length >= 12) break; }
  return out;
}
let suggs = [], sIdx = 0;
function renderSugg() {
  const box = $('#sugg');
  box.hidden = !suggs.length && !$('#q').value.trim();
  box.innerHTML = suggs.map((s, i) => `<button role="option" class="${i === sIdx ? 'act' : ''}" data-i="${i}"><span>${esc(s.label)}</span><small>${esc(s.sub)}</small></button>`).join('')
    || `<div class="mut" style="padding:8px 12px">Ничего не найдено. ${S.model?.hasAddresses ? 'Попробуйте адрес, ' : 'У зданий в этом наборе нет адресов, ищите по номеру: «дом 14», '}«участок 6», «камера 19», «ТЭЦ» или по координатам «59.83, 30.41».</div>`;
}
function goSugg(s) {
  if (!s) return;
  $('#sugg').hidden = true;
  $('#q').blur();
  if (s.coords) { const [x, y] = S.model.proj.xy(s.coords); rig.lookAtFrom(new THREE.Vector3(x, y, 0), rig.yaw, -55 * D, 220); return; }
  if (s.v && S.v?.id !== s.v) setVariant(s.v);
  select(s.sel, {fly: true, room: true});
  $('#q').value = s.label;   // как в Картах: в поле — имя выбранного
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
}
const isMobile = () => matchMedia('(max-width: 900px)').matches;
$('#left .mact').onclick = e => {
  const b = e.target.closest('button[data-act]');
  if (!b) return;
  document.body.classList.remove('leftOpen');
  const a = b.dataset.act;
  if (a === 'open') openDlg('files'); else if (a === 'ds') openDlg('demo'); else if (a === 'help') $('#helpDlg').showModal(); else menuAct(a);
};
$$('#left .tabs button').forEach(b => b.onclick = () => openTab(b.dataset.tab));

function closeMenu() { $('#fileDd .menu').hidden = true; $('#fileBtn').setAttribute('aria-expanded', 'false'); }
$('#fileBtn').onclick = () => { const m = $('#fileDd .menu'); m.querySelector('[data-act=geojson]').disabled = !S.raw; m.hidden = !m.hidden; $('#fileBtn').setAttribute('aria-expanded', String(!m.hidden)); };
$('#fileDd .menu').onclick = e => { const b = e.target.closest('button[data-act]'); if (!b) return; closeMenu(); menuAct(b.dataset.act); };
$('#dsBtn').onclick = () => openDlg('demo');
async function menuAct(a) {
  if (a === 'open') openDlg('demo');
  if (a === 'run' || a === 'jobs') openDlg('srv');
  if (a === 'png') { const blob = await snapshot(); download(blob, snapName()); }
  if (a === 'geojson') { if (!S.raw) return toast('Результата трассировки нет, скачивать нечего', 'info'); download(new Blob([S.raw], {type: 'application/geo+json'}), `${S.ds?.name || 'result'}.geojson`); }
  if (a === 'link') copyLink();
}
function snapName() {
  const d = new Date().toISOString().slice(0, 10), sel = S.sel ? describe(S.sel).title : 'вид';
  return `${(S.ds?.title || 'Теплосеть 3D').split(/[,(]/)[0].trim()}_вариант${S.v?.id || ''}_${sel}_${d}.png`.replace(/[\\/:*?"<>|]+/g, '').replace(/\s+/g, '_');
}
async function snapshot() {
  viewer.render();
  const src = viewer.renderer.domElement, w = src.width, h = src.height, k = w / src.clientWidth, top = 46 * k, bot = 40 * k;
  const c = document.createElement('canvas'); c.width = w; c.height = h + top + bot;
  const g = c.getContext('2d');
  g.fillStyle = '#0b1322'; g.fillRect(0, 0, c.width, c.height);
  g.drawImage(src, 0, top);
  g.fillStyle = '#e6edf7'; g.font = `600 ${16 * k}px system-ui, sans-serif`;
  const sm = S.v?.summary;
  g.fillText(`${S.ds?.title || ''}${S.v ? ` · вариант ${S.v.id}, S ${fmt(S.v.score, 2)}, ${rub(sm?.calculated_cost)}` : ''}`, 14 * k, 20 * k);
  g.font = `${13 * k}px system-ui, sans-serif`; g.fillStyle = '#94a3b8';
  g.fillText(`${S.sel ? describe(S.sel).title + ' · ' : ''}${new Date().toLocaleString('ru-RU')} · глубина ×${viewer.ex} · ${$('#scale span').textContent} = ${$('#scale .bar').style.width}`, 14 * k, 38 * k);
  let x = 14 * k;
  const y = h + top + 25 * k;
  const item = (color, text) => { g.fillStyle = color; g.fillRect(x, y - 8 * k, 18 * k, 5 * k); x += 24 * k; g.fillStyle = '#cbd5e1'; g.fillText(text, x, y); x += g.measureText(text).width + 16 * k; };
  item('#2ee6d0', 'новая сеть'); item(PALETTE.problem, 'проблема');
  for (const t of Object.keys(S.model?.utilities || {})) item(UTILITIES[t].color, UTILITIES[t].name.toLowerCase());
  return new Promise(res => c.toBlob(res, 'image/png'));
}
function download(blob, name) { const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = name; a.click(); setTimeout(() => URL.revokeObjectURL(a.href), 2000); }
function copyLink(btn) {
  writeHash();
  const done = () => { if (btn) { btn.textContent = 'Ссылка скопирована'; setTimeout(() => { btn.textContent = 'Скопировать ссылку'; }, 1600); } else toast('Ссылка на вид скопирована', 'ok', 1500); };
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
$('#fGo').onclick = () => { const f = [$('#fIn').files[0], $('#fOut').files[0]].filter(Boolean); if (!f.length) return; $('#openDlg').close(); loadFiles(f); };
for (const id of ['fIn', 'fOut', 'jIn']) $('#' + id).addEventListener('change', e => {
  const f = e.target.files[0], n = $('#' + id + 'N');
  n.textContent = f ? `${f.name} · ${fmt(f.size / 1e6, 1)} МБ` : 'не выбран';
  n.classList.toggle('set', !!f);
  $('#fGo').disabled = !($('#fIn').files[0] || $('#fOut').files[0]);
});

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
    const [i, o] = await Promise.all([fetchJson(`/api/v1/jobs/${id}/input`, 'исходные данные'), fetchJson(`/api/v1/jobs/${id}/result`, 'результат')]);
    history.replaceState(null, '', location.pathname + '?job=' + encodeURIComponent(id));
    await load({input: i.json, output: o.json, outText: o.text, ds: {name: null, title: `Расчёт ${id.slice(0, 8)}`, job: id}});
  } catch (e) { $('#loading').hidden = true; toast(`Не удалось открыть расчёт: ${esc(e.message)}`, 'error', 8000); }
}
$('#jGo').onclick = async () => {
  const f = $('#jIn').files[0];
  if (!f) return toast('Выберите файл исходных данных (GeoJSON)', 'info');
  const st = $('#jobState');
  try {
    st.textContent = 'Файл отправляется…';
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
  p.set('ex', viewer.ex);   // камера в ссылке посчитана под этот масштаб глубины
  history.replaceState(null, '', location.pathname + location.search + '#' + p.toString());
}
function applyHash() {
  const p = new URLSearchParams(location.hash.slice(1));
  if (p.get('ex')) setEx(+p.get('ex'));
  if (p.get('v') && S.model.variants.some(v => v.id === p.get('v'))) setVariant(p.get('v'), {quiet: true});
  const k = p.get('sel');
  if (k) {
    const sel = findSel(k);
    if (sel) { if (sel.kind === 'crossing') selectCrossing(sel.ref, {move: !p.get('cam')}); else select(sel, {fly: !p.get('cam')}); }
  }
  const cam = p.get('cam');
  if (cam) {
    const a = cam.split(',').map(Number);
    if (a.length === 5 && a.every(Number.isFinite)) { rig.stop(); rig.state = {pos: a.slice(0, 3), yaw: a[3], pitch: a[4]}; ensureOutside(); return true; }
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
  if ((e.ctrlKey || e.metaKey) && e.code === 'Backslash') { e.preventDefault(); setMapOnly(!mapOnly); return; }
  if (!typing && ((e.altKey && e.code === 'ArrowLeft') || ((e.ctrlKey || e.metaKey) && e.code === 'KeyZ'))) { e.preventDefault(); backView(); return; }
  if (typing || e.ctrlKey || e.metaKey || e.altKey) return;
  if (document.querySelector('dialog[open]')) return;
  if (e.key === '?') { $('#helpDlg').showModal(); return; }
  if (e.key === 'Escape') {
    if (!$('#ctx').hidden) return hideCtx();
    if (S.fly) return stopFly();
    if (rig.mode === 'fly') return setMode('orbit');
    if (S.measure) return setMeasure(false);
    if (S.clip) return setClip(null);
    if (S.sel) return clearSel();
    if (mapOnly) return setMapOnly(false);
    document.body.classList.remove('leftOpen');
    return;
  }
  if (e.code === 'Space' && S.fly) { e.preventDefault(); $('#plPlay').click(); return; }
  if (e.code === 'Backspace') { e.preventDefault(); backView(); return; }
  const k = e.code;
  if (k === 'KeyF') frameSel();
  else if (k === 'KeyH' || k === 'Home') home();
  else if (k === 'KeyT') toggle2d();
  else if (k === 'KeyV') setMode(rig.mode === 'fly' ? 'orbit' : 'fly');
  else if (k === 'KeyX') setXray(!S.xray);
  else if (k === 'KeyB') toggleClip();
  else if ((k === 'BracketLeft' || k === 'BracketRight') && S.clip) {
    // [ и ] — сжать или расширить рамку среза по плану на 20 %
    const b = S.clip, f = k === 'BracketRight' ? 1.2 : 1 / 1.2, cx = (b[0] + b[3]) / 2, cy = (b[1] + b[4]) / 2;
    const hx = Math.max(4, (b[3] - b[0]) / 2 * f), hy = Math.max(4, (b[4] - b[1]) / 2 * f);
    setClip([cx - hx, cy - hy, b[2], cx + hx, cy + hy, b[5]]);
  }
  else if (k === 'KeyM') setMeasure(!S.measure);
  else if (k === 'Equal' || k === 'NumpadAdd') zoomStep(0.6);
  else if (k === 'Minus' || k === 'NumpadSubtract') zoomStep(1.6);
  else if (/^Digit[1-9]$/.test(k) && S.model) { const v = S.model.variants[+k.slice(5) - 1]; if (v) setVariant(v.id); }
  else if (k === 'KeyP') S.fly ? stopFly() : startFly();
});

// ---------- кнопки на сцене ----------
$('#homeBtn').onclick = () => home();
$('#camChip').onclick = () => rig.lookAtFrom(new THREE.Vector3(rig.pos.x + Math.sin(rig.yaw) * 40, rig.pos.y + Math.cos(rig.yaw) * 40, 0), rig.yaw, -45 * D, 60);
function zoomStep(k) { const P = rig.center(); rig.animateTo({pos: P.clone().add(rig.pos.clone().sub(P).multiplyScalar(k)), yaw: rig.yaw, pitch: rig.pitch, back: true}, 300); }
$('#v2d').onclick = () => toggle2d();
$('#xrayBtn').onclick = () => setXray(!S.xray);
$('#measureBtn').onclick = () => setMeasure(!S.measure);
$('#helpBtn').onclick = () => $('#helpDlg').showModal();
$('#errOpen').onclick = () => { $('#errDlg').close(); openDlg('files'); };
// ссылка, вставленная в уже открытую вкладку, меняет только часть после # — откликаемся на неё
addEventListener('hashchange', () => { if (S.model) applyHash(); });
// ---------- панели: свернуть и вернуть кнопкой, как боковые панели в Claude, Google Картах и Notes на iPad ----------
// mapOnly — раскладка панелей до режима «только карта»; null — режим выключен
let mapOnly = null;
function layout() {
  const b = document.body, mob = isMobile(), sel = !!S.sel && b.classList.contains('propsOpen');
  const leftOn = mob ? b.classList.contains('leftOpen') : !b.classList.contains('leftHidden');
  const cardOn = sel && !b.classList.contains('propsMin');
  b.classList.toggle('propsShown', cardOn);
  const lb = $('#leftBtn'); lb.setAttribute('aria-pressed', leftOn); lb.title = leftOn ? 'Свернуть панель слева' : 'Показать панель слева';
  const pmin = $('#profile').classList.contains('min'), pm = $('#pMin');
  pm.title = pmin ? 'Развернуть профиль' : 'Свернуть профиль'; pm.setAttribute('aria-label', pm.title); pm.setAttribute('aria-expanded', !pmin);
  const px = $('#pxMin');
  if (px) { const t = mob ? ($('#props').classList.contains('min') ? 'Развернуть карточку' : 'Свернуть карточку') : 'Свернуть карточку'; px.title = t; px.setAttribute('aria-label', t); }
}
// ручная правка любой панели выключает «только карта»: дальше панели раскладывает пользователь
function toggleLeft() {
  mapOnly = null;
  S.autoLeft = false;
  if (isMobile()) document.body.classList.toggle('leftOpen');
  else { document.body.classList.toggle('leftHidden'); store.set('leftHidden', document.body.classList.contains('leftHidden')); }
  layout();
}
function toggleCard() {
  if (!S.sel) return;
  mapOnly = null;
  if (isMobile()) $('#props').classList.toggle('min'); else document.body.classList.toggle('propsMin');
  layout();
}
function toggleProfileMin() {
  const p = $('#profile'), min = !p.classList.contains('min');
  mapOnly = null;
  p.classList.toggle('min', min);
  store.set('profMin', min);
  layout();
}
function setMapOnly(on) {
  const b = document.body, p = $('#profile'), sheet = $('#props');
  if (on && !mapOnly) {
    mapOnly = {left: b.classList.contains('leftHidden'), card: b.classList.contains('propsMin'), prof: p.classList.contains('min'), sheet: sheet.classList.contains('min')};
    b.classList.add('leftHidden', 'propsMin'); b.classList.remove('leftOpen'); p.classList.add('min'); sheet.classList.add('min');
  } else if (!on && mapOnly) {
    const m = mapOnly;
    mapOnly = null;
    b.classList.toggle('leftHidden', m.left); b.classList.toggle('propsMin', m.card); p.classList.toggle('min', m.prof); sheet.classList.toggle('min', m.sheet);
  }
  layout();
}
$('#leftBtn').onclick = () => toggleLeft();
$('#pMin').onclick = () => toggleProfileMin();
$('#pTitle').onclick = () => toggleProfileMin();
// отметка вертикального масштаба прямо на сцене: щелчок — ×1 «как есть» или обратно растянуть
$('#exChip').onclick = () => { const last = store.get('exLast', 3); if (viewer.ex === 1) setEx(last > 1 ? last : 3); else { store.set('exLast', viewer.ex); setEx(1); } updateScale(); };
// подсказка управления уходит сама после первого действия в сцене
viewer.renderer.domElement.addEventListener('pointerdown', () => { if (!$('#coach').hidden) setTimeout(() => $('#coachOk').click(), 1500); }, {once: true});
$('#coachOk').onclick = () => { $('#coach').hidden = true; store.set('coach', 1); if (store.get('legend', false) && !isMobile() && $('#legBody').hidden) $('#legBtn').click(); };
// ползунки и флажки не держат фокус после щелчка — иначе W A S D и стрелки двигают ползунок, а не камеру
document.addEventListener('change', e => { if (e.target.matches?.('#left input[type=range], #left input[type=checkbox], #left select')) setTimeout(() => e.target.blur(), 0); });
document.addEventListener('pointerup', e => { if (e.target.matches?.('#left input[type=range]')) setTimeout(() => e.target.blur(), 0); });
$('#coachAll').onclick = () => { $('#coach').hidden = true; store.set('coach', 1); $('#helpDlg').showModal(); };

// ---------- старт ----------
async function start() {
  const o = viewer.opts;
  o.groundOpacity = store.get('gop', 0.4);
  o.buildings = store.get('bld', 'solid');
  Object.assign(o.layers, store.get('layers', {}));
  viewer.ex = store.get('ex', 3);
  updateModeUi();
  if (isMobile()) $('#q').placeholder = 'Поиск';
  if (store.get('leftHidden', false) && !isMobile()) document.body.classList.add('leftHidden');
  if (store.get('profMin', matchMedia('(pointer: coarse)').matches || innerWidth <= 1280)) $('#profile').classList.add('min');
  layout();
  if (matchMedia('(pointer: coarse)').matches) {
    // на телефоне и планшете — жесты, а не кнопки мыши
    $('#coachAll').textContent = 'Справка';
    $('#coach .cg').innerHTML = '<span class="k">Один палец</span><span>двигать</span><span class="k">Щипок</span><span>приблизить</span>' +
      '<span class="k">Повернуть двумя</span><span>вращать</span><span class="k">Два пальца вверх-вниз</span><span>наклонить</span><span class="k">Двойное касание</span><span>приблизить сюда</span><span class="k">Долгое нажатие</span><span>меню точки</span>';
  }
  if (!store.get('coach', 0)) $('#coach').hidden = false;
  // легенда раскрыта при первом знакомстве (после подсказки управления), дальше — как оставил пользователь
  if (store.get('legend', false) && !isMobile() && $('#coach').hidden) $('#legBtn').click();
  await detectServer();
  try { demos = await (await fetch('demo/index.json')).json(); } catch (e) { demos = []; }
  const q = new URLSearchParams(location.search);
  if (q.get('job') && S.server) return openJob(q.get('job'));
  if (q.get('ds') === 'local') {
    const files = await cachedFiles();
    if (files) return loadFiles(files.map(f => ({name: f.name, text_: f.text})), {fromCache: true});
    toast('Ваших файлов из прошлого раза в этом браузере нет, поэтому открыт демо-набор', 'info', 6000);
  }
  const want = q.get('ds') !== 'local' && q.get('ds') || store.get('ds', null);
  const d = demos.find(x => x.name === want) || demos[0];
  if (d) return loadDemo(d);
  $('#loading').hidden = true;
  openDlg(S.server ? 'srv' : 'files');
}
booted = true;
start();
// для автотестов и записи видео: точка сцены → пиксель окна
const toScreen = (x, y, z = 0) => { const v = new THREE.Vector3(x, y, z).project(viewer.camera), r = $('#view').getBoundingClientRect(); return [r.left + (v.x + 1) / 2 * r.width, r.top + (1 - v.y) / 2 * r.height]; };
window.__app = {S, viewer, rig, select, selectCrossing, setVariant, home, startFly, stopFly, setEx, setMode, toScreen};
