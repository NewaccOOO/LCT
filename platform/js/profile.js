import {UTILITIES, UNDER, NORMAL_DEPTH, MIN_DEPTH, pointAt} from './model.js';
import {fmt} from './util.js';

const FONT = '"Helvetica Neue", Arial, sans-serif';
const ROWS = ['Ду', 'Уклон, ‰', 'Глубина верха, м', 'Длина, м'];
const BAND = ROWS.length * 17 + 4;
export function pk(s) {
  s = Math.round(s * 10) / 10;
  const k = Math.floor(s / 100 + 1e-9), m = Math.max(0, s - k * 100), t = (Math.round(m * 10) / 10).toFixed(1).split('.');
  return `ПК${k}+${t[0].padStart(2, '0')}${t[1] !== '0' ? ',' + t[1] : ''}`;
}

export class Profile {
  constructor(canvas, hooks) {
    this.cv = canvas;
    this.hooks = hooks;
    this.route = null;
    this.cursor = null;
    this.mouse = null;
    this.win = null;
    let drag = null;
    canvas.addEventListener('mousemove', e => {
      this.mouse = [e.offsetX, e.offsetY];
      if (drag && this.win) {
        const L = this.layout(), k = (this.win[1] - this.win[0]) / L.W, d = (drag.x - e.offsetX) * k;
        if (Math.abs(e.offsetX - drag.x0) > 3) drag.moved = true;
        this.setWin(this.win[0] + d, this.win[1] + d);
        drag.x = e.offsetX;
      } else if (drag && Math.abs(e.offsetX - drag.x0) > 3) drag.moved = true;
      this.draw(); this.emitHover();
    });
    canvas.addEventListener('mousedown', e => { drag = {x: e.offsetX, x0: e.offsetX, moved: false}; });
    addEventListener('mouseup', () => { setTimeout(() => { drag = null; }, 0); });
    canvas.addEventListener('mouseleave', () => { this.mouse = null; this.draw(); this.hooks.hover(null); });
    canvas.addEventListener('wheel', e => {
      if (!this.route) return;
      e.preventDefault();
      const at = this.atX(e.offsetX) ?? this.route.len / 2, [a, b] = this.win || [0, this.route.len];
      const k = Math.exp((e.deltaY || e.deltaX) * 0.002);
      this.setWin(at - (at - a) * k, at + (b - at) * k);
      this.draw();
    }, {passive: false});
    canvas.addEventListener('dblclick', () => { this.win = null; this.draw(); });
    const tp = new Map();
    let tz = null;
    canvas.addEventListener('pointerdown', e => { if (e.pointerType !== 'touch' || !this.route) return; canvas.setPointerCapture(e.pointerId); tp.set(e.pointerId, e.offsetX); tz = null; });
    canvas.addEventListener('pointermove', e => {
      if (e.pointerType !== 'touch' || !tp.has(e.pointerId) || !this.route) return;
      const prev = tp.get(e.pointerId); tp.set(e.pointerId, e.offsetX);
      const L = this.layout(), [a, b] = this.win || [0, this.route.len];
      if (tp.size === 1) { const d = (prev - e.offsetX) * (b - a) / L.W; if (this.win) this.setWin(a + d, b + d); }
      else if (tp.size === 2) {
        const xs = [...tp.values()], span = Math.abs(xs[0] - xs[1]);
        if (tz) { const at = this.atX((xs[0] + xs[1]) / 2) ?? (a + b) / 2, k = tz / Math.max(span, 8); this.setWin(at - (at - a) * k, at + (b - at) * k); }
        tz = span;
      }
      this.draw();
    });
    const tend = e => { tp.delete(e.pointerId); tz = null; };
    canvas.addEventListener('pointerup', tend);
    canvas.addEventListener('pointercancel', tend);
    canvas.addEventListener('click', e => {
      if (drag?.moved) return;
      const n = this.nearCx(e.offsetX);
      if (n) this.hooks.click({crossing: n.ref});
      else { const at = this.atX(e.offsetX); if (at != null) this.hooks.click({at}); }
    });
    new ResizeObserver(() => this.draw()).observe(canvas);
  }

  show(route, model) {
    this.route = route;
    this.model = model;
    this.cursor = null;
    this.win = null;
    if (!route) { this.draw(); return; }
    const zones = [];
    let cur = null;
    for (let s = 0; s <= route.len; s += 2) {
      const p = pointAt(route, s), z = model.zonesAt(p.x, p.y).find(z => UNDER[z.type]);
      const t = z ? z.type : null;
      if (cur && cur.type === t) cur.b = s;
      else { if (cur && cur.type) zones.push(cur); cur = {type: t, a: s, b: s}; }
    }
    if (cur && cur.type) zones.push(cur);
    this.zones = zones;
    this.cxs = route.segs.flatMap(s => s.cx.map(c => ({...c, S: s.s0 + c.s, ref: c})));
    this.detour = null;
    this.draw();
  }
  setDetour(d) { this.detour = d; this.draw(); }
  setCursor(at) {
    this.cursor = at;
    if (at != null && this.win && (at < this.win[0] || at > this.win[1])) { const w = this.win[1] - this.win[0]; this.setWin(at - w * 0.2, at + w * 0.8); }
    this.draw();
  }
  setWin(a, b) {
    const L = this.route.len, w = Math.min(L, Math.max(4, b - a));
    if (w >= L - 1e-6) { this.win = null; return; }
    a = Math.max(0, Math.min(L - w, a));
    this.win = [a, a + w];
  }
  zoomTo(at, w = 40) { if (!this.route) return; this.setWin(at - w / 2, at + w / 2); this.draw(); }

  layout() {
    const r = this.cv.getBoundingClientRect(), R = this.route;
    let maxD = 5;
    for (const s of R.segs) for (let i = 0; i < s.depth.length; i++) maxD = Math.max(maxD, s.depth[i] + s.row.h + 1);
    for (const c of this.cxs) maxD = Math.max(maxD, c.top + c.eh + (c.need || 0) + 0.8);
    const pl = 96, pr = 18, pt = 40, pb = 34 + BAND;
    const W = Math.max(10, r.width - pl - pr), H = Math.max(10, r.height - pt - pb);
    const [a, b] = this.win || [0, Math.max(R.len, 1)];
    return {r, pl, pr, pt, pb, W, H, maxD, a, b, X: s => pl + (s - a) / (b - a) * W, Y: d => pt + (d + 0.6) / (maxD + 0.6) * H};
  }
  atX(x) { if (!this.route) return null; const L = this.layout(); const s = L.a + (x - L.pl) / L.W * (L.b - L.a); return s < L.a - 1e-6 || s > L.b + 1e-6 ? null : s; }
  nearCx(x) { if (!this.route) return null; const L = this.layout(); return this.cxs.find(c => Math.abs(L.X(c.S) - x) < 9) || null; }
  emitHover() {
    if (!this.mouse || !this.route) return;
    const at = this.atX(this.mouse[0]);
    this.hooks.hover(at == null ? null : pointAt(this.route, at));
  }

  draw() {
    const cv = this.cv, R = this.route;
    const r = cv.getBoundingClientRect(), dpr = devicePixelRatio || 1;
    if (!r.width || !r.height) return;
    cv.width = Math.round(r.width * dpr); cv.height = Math.round(r.height * dpr);
    const g = cv.getContext('2d');
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.clearRect(0, 0, r.width, r.height);
    if (!R) return;
    const L = this.layout(), {X, Y, pl, W, pt, maxD, a: wa, b: wb} = L;
    const css = getComputedStyle(document.documentElement);
    const v = (n, d) => css.getPropertyValue(n).trim() || d;
    const muted = v('--muted', '#878787'), line = v('--line', '#ebebeb'), text = v('--text', '#000');
    const veil = v('--veil', 'rgba(0,0,0,.05)'), edge = v('--edge', 'rgba(0,0,0,.6)'), badT = v('--bad-t', '#e0301e'), warnT = v('--warn-t', '#c27c00'), okT = v('--ok-t', '#2f8f00');
    const pipe = '#2ee6d0', bad = '#ff2fd1';
    g.font = '12px ' + FONT;
    const clip = () => { g.save(); g.beginPath(); g.rect(pl, 0, W, r.height); g.clip(); };

    clip();
    g.fillStyle = 'rgba(146, 102, 41, 0.10)';
    g.fillRect(pl, Y(0), W, Y(maxD) - Y(0));
    for (const z of this.zones) {
      g.fillStyle = z.type === 'road' ? 'rgba(148,163,184,.14)' : 'rgba(148,163,184,.22)';
      g.fillRect(X(z.a), Y(-0.6), Math.max(2, X(z.b) - X(z.a)), Y(maxD) - Y(-0.6));
      g.strokeStyle = muted; g.setLineDash([4, 4]);
      g.beginPath(); g.moveTo(X(z.a), Y(UNDER[z.type])); g.lineTo(X(z.b), Y(UNDER[z.type])); g.stroke(); g.setLineDash([]);
      if (X(z.b) - X(z.a) > 70) { g.fillStyle = muted; g.fillText(z.type === 'road' ? 'под дорогой' : 'под трамваем', Math.max(pl, X(z.a)) + 4, Y(UNDER[z.type]) - 4); }
    }
    g.restore();
    g.strokeStyle = line; g.fillStyle = muted; g.lineWidth = 1;
    const dstep = maxD > 12 ? 2 : 1;
    for (let d = 0; d <= maxD; d += dstep) {
      g.beginPath(); g.moveTo(pl, Y(d)); g.lineTo(pl + W, Y(d)); g.stroke();
      g.fillText(`${d} м`, 22, Y(d) + 4);
    }
    g.save(); g.translate(13, Y(maxD / 2)); g.rotate(-Math.PI / 2); g.fillText('глубина', -22, 0); g.restore();

    clip();
    const dash = (d, c, t) => { g.setLineDash([6, 5]); g.strokeStyle = c; g.beginPath(); g.moveTo(pl, Y(d)); g.lineTo(pl + W, Y(d)); g.stroke(); g.setLineDash([]); g.fillStyle = c; g.fillText(t, pl + W - g.measureText(t).width - 4, Y(d) - 4); };
    dash(NORMAL_DEPTH, 'rgba(46,230,208,.6)', 'обычная глубина 3 м');
    dash(MIN_DEPTH, 'rgba(148,163,184,.8)', 'минимальная глубина 0,7 м');
    g.fillStyle = '#a16207'; g.fillRect(pl, Y(0) - 3, W, 3);
    g.fillStyle = '#b07a1f'; g.fillText('поверхность', Math.max(pl, X(0)) + 4, Y(0) - 6);
    for (const s of R.segs) if (s.special) {
      g.fillStyle = veil; g.fillRect(X(s.s0), Y(0), X(s.s0 + s.len) - X(s.s0), Y(maxD) - Y(0));
    }
    for (const s of R.segs) {
      g.fillStyle = s.issues.length ? bad : pipe;
      g.beginPath();
      for (let i = 0; i < s.pts.length; i++) { const x = X(s.s0 + s.lens[i]); i ? g.lineTo(x, Y(s.depth[i])) : g.moveTo(x, Y(s.depth[i])); }
      for (let i = s.pts.length - 1; i >= 0; i--) g.lineTo(X(s.s0 + s.lens[i]), Math.max(Y(s.depth[i] + s.row.h), Y(s.depth[i]) + 2));
      g.closePath(); g.fill();
      if (s.special) { g.strokeStyle = edge; g.strokeRect(X(s.s0), Y(Math.min(s.d0, s.d1)) - 3, X(s.s0 + s.len) - X(s.s0), Y(Math.max(s.d0, s.d1) + s.row.h) - Y(Math.min(s.d0, s.d1)) + 6); }
    }
    for (const c of this.cxs) if (c.ok === false) {
      const p = pointAt(R, c.S);
      g.fillStyle = bad;
      g.fillRect(X(c.S - 2), Y(p.d) - 1, Math.max(4, X(c.S + 2) - X(c.S - 2)), Y(p.d + c.H) - Y(p.d) + 2);
    }
    if (this.detour) {
      const D = this.detour;
      g.strokeStyle = '#4ade80'; g.lineWidth = 2; g.setLineDash([5, 4]);
      g.beginPath(); D.pts.forEach((p, i) => i ? g.lineTo(X(p.S), Y(p.d)) : g.moveTo(X(p.S), Y(p.d))); g.stroke();
      g.beginPath(); D.pts.forEach((p, i) => i ? g.lineTo(X(p.S), Y(p.d + D.h)) : g.moveTo(X(p.S), Y(p.d + D.h))); g.stroke();
      g.setLineDash([]); g.lineWidth = 1;
      g.fillStyle = 'rgba(74,222,128,.12)';
      g.fillRect(X(D.pts[0].S), Y(0), Math.max(6, X(D.pts[D.pts.length - 1].S) - X(D.pts[0].S)), Y(maxD) - Y(0));
      const mid = D.pts[Math.floor(D.pts.length / 2)];
      g.fillStyle = okT; g.fillText(D.label, Math.min(X(mid.S) + 8, pl + W - g.measureText(D.label).width - 4), Y(mid.d + D.h) + 14);
    }
    g.strokeStyle = line; g.fillStyle = muted;
    let lastX = -1e9;
    for (const s of R.segs) {
      const x = X(s.s0);
      g.beginPath(); g.moveTo(x, Y(0)); g.lineTo(x, Y(s.d0 + s.row.h + 0.3)); g.stroke();
      const nm = this.hooks.nodeName?.(s.from) || '';
      if (nm && !nm.startsWith('поворот') && x - lastX > g.measureText(nm).width + 10 && x >= pl && x <= pl + W) { g.fillText(nm, x + 3, Y(s.d0 + s.row.h + 0.3) + 13); lastX = x; }
    }
    const rows = [];
    for (const c of [...this.cxs].sort((p, q) => p.S - q.S)) {
      const x = X(c.S), color = UTILITIES[c.type]?.color || '#94a3b8';
      if (x < pl - 10 || x > pl + W + 10) continue;
      if (c.need != null) {
        g.strokeStyle = c.ok ? '#22c55e' : bad; g.setLineDash([3, 3]);
        g.strokeRect(x - 8, Y(c.top - c.need), 16, Y(c.top + c.eh + c.need) - Y(c.top - c.need)); g.setLineDash([]);
      }
      g.fillStyle = color;
      g.beginPath(); g.ellipse(x, (Y(c.top) + Y(c.top + c.eh)) / 2, 5, Math.max(3, (Y(c.top + c.eh) - Y(c.top)) / 2), 0, 0, Math.PI * 2); g.fill();
      g.font = `${c.ok === false ? '500 ' : ''}12px ${FONT}`;
      const t = UTILITIES[c.type]?.short || c.type, w = g.measureText(t).width;
      const lx = Math.max(pl + 2, Math.min(x - w / 2, pl + W - w - 2));
      let row = rows.findIndex(end => end < lx - 4);
      if (row < 0) { if (rows.length >= 3) continue; row = rows.length; rows.push(0); }
      rows[row] = lx + w;
      const ly = pt - 26 + row * 12;
      g.strokeStyle = c.ok === false ? 'rgba(255,31,75,.6)' : 'rgba(148,163,184,.35)';
      g.beginPath(); g.moveTo(x, ly + 3); g.lineTo(x, Y(c.top - (c.need || 0)) - 2); g.stroke();
      g.fillStyle = c.ok === false ? badT : muted;
      g.fillText(t, lx, ly);
    }
    g.restore();

    const raw = (wb - wa) / Math.max(1, W / 90), mag = Math.pow(10, Math.floor(Math.log10(raw || 1)));
    const step = [1, 2, 5, 10].map(k => k * mag).find(v => v >= raw) || mag * 10;
    g.fillStyle = muted; g.strokeStyle = line;
    for (let s = Math.ceil(wa / step) * step; s <= wb + 1e-6; s += step) { const t = pk(s); g.fillText(t, Math.max(pl, X(s) - g.measureText(t).width / 2), Y(maxD) + 14); g.beginPath(); g.moveTo(X(s), Y(maxD)); g.lineTo(X(s), Y(maxD) + 4); g.stroke(); }
    const by = Y(maxD) + 20;
    g.save(); g.font = '11px ' + FONT;
    ROWS.forEach((t, i) => { g.fillStyle = muted; g.fillText(t, 4, by + i * 17 + 12); g.strokeStyle = line; g.beginPath(); g.moveTo(pl, by + i * 17); g.lineTo(pl + W, by + i * 17); g.stroke(); });
    g.restore();
    g.beginPath(); g.moveTo(pl, by + ROWS.length * 17); g.lineTo(pl + W, by + ROWS.length * 17); g.stroke();
    clip();
    for (const s of R.segs) {
      const x0 = X(s.s0), x1 = X(s.s0 + s.len);
      g.strokeStyle = line; g.beginPath(); g.moveTo(x0, by); g.lineTo(x0, by + ROWS.length * 17); g.stroke();
      const v0 = Math.max(x0, pl), v1 = Math.min(x1, pl + W), w = v1 - v0;
      if (w <= 0) continue;
      const slope = Math.abs(s.d1 - s.d0) / s.len;
      const vals = [String(s.p.diameter), fmt(slope * 1000, 1), s.d0 === s.d1 ? fmt(s.d0, 2) : `${fmt(s.d0, 2)}→${fmt(s.d1, 2)}`, fmt(s.p.length ?? s.len, (s.p.length ?? s.len) < 10 ? 1 : 0)];
      vals.forEach((t, i) => {
        g.fillStyle = s.issues.length ? badT : i === 1 && slope < 0.002 ? warnT : text;
        const tw = g.measureText(t).width; if (tw + 4 < w) g.fillText(t, v0 + (w - tw) / 2, by + i * 17 + 12);
      });
    }
    g.restore();
    g.fillStyle = this.win ? '#ff8a1f' : muted;
    g.fillText(this.win ? `показан участок ${pk(wa)} – ${pk(wb)} · ${matchMedia('(pointer: coarse)').matches ? 'двойное касание' : 'двойной щелчок'} — вся трасса` : (matchMedia('(pointer: coarse)').matches ? 'два пальца — масштаб · палец — сдвиг' : 'колесо — масштаб · тащить — сдвиг') + ' · жёлтым — уклон меньше 0,002', pl + 4, r.height - 5);

    const cur = this.mouse ? this.atX(this.mouse[0]) : this.cursor;
    if (cur == null) return;
    const p = pointAt(R, cur), x = X(cur);
    if (x < pl - 1 || x > pl + W + 1) return;
    g.strokeStyle = '#ff8a1f'; g.lineWidth = 1.5;
    g.beginPath(); g.moveTo(x, Y(-0.6)); g.lineTo(x, Y(maxD)); g.stroke(); g.lineWidth = 1;
    g.fillStyle = '#ff8a1f'; g.beginPath(); g.arc(x, Y(p.d), 4, 0, Math.PI * 2); g.fill();
    const n = this.mouse && this.nearCx(this.mouse[0]);
    const lines = [`${pk(cur)} · ${fmt(cur)} м от врезки`, `верх трубы ${fmt(p.d, 2)} м · Ду ${p.seg.p.diameter}${p.seg.special ? ' · спецпереход' : ''}`];
    if (n) {
      const u = UTILITIES[n.type].name.toLowerCase();
      lines.push(n.gap < 0 ? `${u}: трубы перекрываются по высоте на ${fmt(-n.gap, 2)} м` : n.ok === false ? `${u}: зазор ${fmt(n.gap, 2)} м, нужно ${fmt(n.need, 2)} м` : `${u}: зазор ${fmt(n.gap, 2)} м`, 'щёлкните, чтобы показать в 3D');
    }
    const w = Math.max(...lines.map(t => g.measureText(t).width)) + 16, bx = x + 12 + w > r.width ? x - 12 - w : x + 12;
    g.fillStyle = 'rgba(51,51,51,.96)';
    g.beginPath(); g.roundRect(bx, pt, w, lines.length * 16 + 10, 8); g.fill();
    g.fillStyle = '#fff';
    lines.forEach((t, i) => g.fillText(t, bx + 8, pt + 18 + i * 16));
  }
}
