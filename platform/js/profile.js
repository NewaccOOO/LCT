// Продольный профиль трассы, как Profile View в Civil 3D: по горизонтали — расстояние от врезки, по вертикали —
// глубина. Новая труба полосой габарита, пересекаемые коммуникации — сечениями с зоной нужного зазора, дороги и
// спецпереходы — фоном. Наведение показывает точку в 3D, щелчок по пересечению — летит к нему.
import {UTILITIES, SURFACES, UNDER, NORMAL_DEPTH, MIN_DEPTH, pointAt} from './model.js';
import {fmt} from './util.js';

export class Profile {
  constructor(canvas, hooks) {
    this.cv = canvas;
    this.hooks = hooks;   // {hover(pt|null), click(crossing|pt)}
    this.route = null;
    this.cursor = null;
    this.mouse = null;
    // масштаб по длине, как в окне профиля Civil 3D: колесо — к курсору, тащить — сдвиг, двойной щелчок — вся трасса
    this.win = null;
    let drag = null;
    canvas.addEventListener('mousemove', e => {
      this.mouse = [e.offsetX, e.offsetY];
      if (drag && this.win) {
        const L = this.layout(), k = (this.win[1] - this.win[0]) / L.W, d = (drag.x - e.offsetX) * k;
        if (Math.abs(e.offsetX - drag.x0) > 3) drag.moved = true;
        this.setWin(this.win[0] + d, this.win[1] + d);
        drag.x = e.offsetX;
      }
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
    canvas.addEventListener('click', e => {
      if (drag?.moved) return;
      const n = this.nearCx(e.offsetX);
      if (n) this.hooks.click({crossing: n});
      else { const at = this.atX(e.offsetX); if (at != null) this.hooks.click({at}); }
    });
    new ResizeObserver(() => this.draw()).observe(canvas);
  }

  show(route, model) {
    this.route = route;
    this.model = model;
    this.cursor = null;
    if (!route) return;
    // интервалы под дорогами и трамваем: проверяем трассу каждые 2 м
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
    this.win = null;
    this.cxs = route.segs.flatMap(s => s.cx.map(c => ({...c, S: s.s0 + c.s})));
    this.draw();
  }
  setCursor(at) {
    this.cursor = at;
    // при пролёте окно профиля едет за камерой
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
    const r = this.cv.getBoundingClientRect();
    const R = this.route;
    let maxD = 5;
    for (const s of R.segs) for (let i = 0; i < s.depth.length; i++) maxD = Math.max(maxD, s.depth[i] + s.row.h + 1);
    for (const c of this.cxs) maxD = Math.max(maxD, c.top + c.eh + (c.need || 0) + 0.8);
    const pl = 64, pr = 18, pt = 22, pb = 26;
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
    const L = this.layout(), {X, Y, pl, W, H, pt, maxD, a: wa, b: wb} = L;
    const css = getComputedStyle(document.documentElement);
    const muted = css.getPropertyValue('--muted').trim() || '#94a3b8', line = css.getPropertyValue('--line').trim() || '#253349';
    g.font = '12px system-ui, -apple-system, Segoe UI, Roboto, sans-serif';
    const clip = () => { g.save(); g.beginPath(); g.rect(pl, 0, W, r.height); g.clip(); };
    clip();
    // грунт
    g.fillStyle = 'rgba(146, 102, 41, 0.10)';
    g.fillRect(pl, Y(0), W, Y(maxD) - Y(0));
    // дороги и трамвай
    for (const z of this.zones) {
      g.fillStyle = z.type === 'road' ? 'rgba(148,163,184,.14)' : 'rgba(148,163,184,.22)';
      g.fillRect(X(z.a), Y(-0.6), Math.max(2, X(z.b) - X(z.a)), Y(maxD) - Y(-0.6));
      g.strokeStyle = 'rgba(203,213,225,.5)'; g.setLineDash([4, 4]);
      g.beginPath(); g.moveTo(X(z.a), Y(UNDER[z.type])); g.lineTo(X(z.b), Y(UNDER[z.type])); g.stroke(); g.setLineDash([]);
      if (X(z.b) - X(z.a) > 40) { g.fillStyle = muted; g.fillText(SURFACES[z.type].name.replace(/и.*$/, '').trim().toLowerCase(), Math.max(pl, X(z.a)) + 4, Y(-0.6) + 12); }
    }
    g.restore();
    // сетка глубин
    g.strokeStyle = line; g.fillStyle = muted; g.lineWidth = 1;
    const dstep = maxD > 12 ? 2 : 1;
    for (let d = 0; d <= maxD; d += dstep) {
      g.beginPath(); g.moveTo(pl, Y(d)); g.lineTo(pl + W, Y(d)); g.stroke();
      g.fillText(`${d} м`, 10, Y(d) + 4);
    }
    // шаг подписей по длине
    const raw = (wb - wa) / Math.max(1, W / 90), mag = Math.pow(10, Math.floor(Math.log10(raw || 1)));
    const step = [1, 2, 5, 10].map(k => k * mag).find(v => v >= raw) || mag * 10;
    for (let s = Math.ceil(wa / step) * step; s <= wb + 1e-6; s += step) { g.fillText(`${fmt(s)} м`, X(s) - 10, r.height - 7); g.beginPath(); g.moveTo(X(s), Y(maxD)); g.lineTo(X(s), Y(maxD) + 4); g.stroke(); }
    if (this.win) { g.fillStyle = '#fde047'; g.fillText(`участок ${fmt(wa)}–${fmt(wb)} м · двойной щелчок — вся трасса`, pl + 4, r.height - 7 - 14); }
    clip();
    // нормы
    const dash = (d, c, t) => { g.setLineDash([6, 5]); g.strokeStyle = c; g.beginPath(); g.moveTo(pl, Y(d)); g.lineTo(pl + W, Y(d)); g.stroke(); g.setLineDash([]); g.fillStyle = c; g.fillText(t, pl + W - g.measureText(t).width - 4, Y(d) - 4); };
    dash(NORMAL_DEPTH, 'rgba(56,189,248,.7)', 'обычная глубина 3 м');
    dash(MIN_DEPTH, 'rgba(148,163,184,.8)', 'мельче 0,7 м нельзя');
    // земля
    g.fillStyle = '#a16207'; g.fillRect(pl, Y(0) - 3, W, 3);
    g.fillStyle = '#d6a64a'; g.fillText('поверхность', pl + 4, Y(0) - 7);
    // спецпереходы
    for (const s of R.segs) if (s.special) { g.fillStyle = 'rgba(251,146,60,.15)'; g.fillRect(X(s.s0), Y(0), X(s.s0 + s.len) - X(s.s0), Y(maxD) - Y(0)); }
    // новая труба полосой габарита
    for (const s of R.segs) {
      g.fillStyle = s.bad ? '#f43f5e' : s.special ? '#fb923c' : '#38bdf8';
      g.beginPath();
      for (let i = 0; i < s.pts.length; i++) { const x = X(s.s0 + s.lens[i]); i ? g.lineTo(x, Y(s.depth[i])) : g.moveTo(x, Y(s.depth[i])); }
      for (let i = s.pts.length - 1; i >= 0; i--) g.lineTo(X(s.s0 + s.lens[i]), Math.max(Y(s.depth[i] + s.row.h), Y(s.depth[i]) + 2));
      g.closePath(); g.fill();
    }
    // узлы: камеры и повороты на стыках участков
    g.strokeStyle = 'rgba(226,232,240,.35)';
    for (const s of R.segs.slice(1)) { const x = X(s.s0); g.beginPath(); g.moveTo(x, Y(0)); g.lineTo(x, Y(s.d0 + s.row.h + 0.3)); g.stroke(); }
    // пересечения
    this.cxs.forEach((c, k) => {
      const x = X(c.S), color = UTILITIES[c.type]?.color || '#94a3b8';
      if (c.need != null) {
        g.strokeStyle = c.ok ? '#22c55e' : '#f43f5e'; g.setLineDash([3, 3]);
        g.strokeRect(x - 8, Y(c.top - c.need), 16, Y(c.top + c.eh + c.need) - Y(c.top - c.need)); g.setLineDash([]);
      }
      g.fillStyle = color;
      g.beginPath(); g.ellipse(x, (Y(c.top) + Y(c.top + c.eh)) / 2, 5, Math.max(3, (Y(c.top + c.eh) - Y(c.top)) / 2), 0, 0, Math.PI * 2); g.fill();
      const t = `${UTILITIES[c.type]?.short || c.type}${c.ok === false ? ' ⚠' : ''}`;
      g.fillStyle = c.ok === false ? '#fda4af' : muted;
      g.fillText(t, Math.min(x - 12, pl + W - g.measureText(t).width), Math.max(pt + 10, Y(c.top - (c.need || 0)) - 6 - (k % 3) * 13));
    });
    g.restore();
    // курсор: пролёт или мышь
    const cur = this.mouse ? this.atX(this.mouse[0]) : this.cursor;
    if (cur != null) {
      const p = pointAt(R, cur), x = X(cur);
      g.strokeStyle = '#fde047'; g.lineWidth = 1.5;
      if (x < pl - 1 || x > pl + W + 1) return;
      g.beginPath(); g.moveTo(x, Y(-0.6)); g.lineTo(x, Y(maxD)); g.stroke(); g.lineWidth = 1;
      g.fillStyle = '#fde047'; g.beginPath(); g.arc(x, Y(p.d), 4, 0, Math.PI * 2); g.fill();
      const n = this.mouse && this.nearCx(this.mouse[0]);
      const lines = [`${fmt(cur)} м от врезки`, `верх трубы ${fmt(p.d, 2)} м · Ду ${p.seg.p.diameter}`];
      if (p.seg.special) lines.push('спецпереход');
      if (n) lines.push(n.ok === false ? `⚠ ${UTILITIES[n.type].name.toLowerCase()}: зазор ${fmt(n.gap, 2)} из ${fmt(n.need, 2)} м` : `${UTILITIES[n.type].name.toLowerCase()}: зазор ${fmt(n.gap, 2)} м`, n ? 'щёлкните — показать в 3D' : '');
      const w = Math.max(...lines.map(t => g.measureText(t).width)) + 16, bx = x + 12 + w > r.width ? x - 12 - w : x + 12;
      g.fillStyle = 'rgba(5,10,22,.92)'; g.strokeStyle = line;
      g.beginPath(); g.roundRect(bx, pt, w, lines.filter(Boolean).length * 16 + 10, 6); g.fill(); g.stroke();
      g.fillStyle = '#e2e8f0';
      lines.filter(Boolean).forEach((t, i) => g.fillText(t, bx + 8, pt + 18 + i * 16));
    }
  }
}
