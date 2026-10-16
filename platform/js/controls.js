// Навигация камеры. Ось Z вверх, x — восток, y — север. Камера задаётся положением, курсом (yaw, 0 — на север,
// по часовой) и наклоном (pitch, 0 — горизонт, −90° — строго вниз, +90° — строго вверх, это вид снизу).
// Привычки пользователя (закон Якоба):
//   Карты:   левая — тащить, правая — вращать и наклонять, колесо — масштаб к курсору, двойной щелчок — ближе
//   Autodesk: средняя — тащить, Shift + средняя — вращать, вращение вокруг объекта под курсором
//   Unity/Unreal: W A S D — лететь, Q/E — вниз/вверх, Shift — быстрее, F — показать выбранное
//   Телефон: один палец — тащить, щипок — масштаб, поворот двумя — вращать, два пальца вверх-вниз — наклон
import * as THREE from 'three';
import {clamp} from './util.js';

const D = Math.PI / 180;
const Z = new THREE.Vector3(0, 0, 1);

export class CameraRig {
  constructor(camera, dom, hooks) {
    this.camera = camera;
    this.dom = dom;
    this.hooks = hooks;           // {pickPoint(x, y) → Vector3|null, onChange(), onClick(x, y, e), onContext(x, y, e), onDblClick(x,y), onPivot(P|null)}
    this.pos = new THREE.Vector3(0, -500, 400);
    this.yaw = 0;
    this.pitch = -45 * D;
    this.mode = 'orbit';          // orbit — мышь как в Картах; fly — мышь осматривается, как в играх
    this.speedK = 1;              // множитель скорости полёта (колесо при зажатой правой в режиме полёта)
    this.anim = null;
    this.held = new Set();
    this.enabled = true;
    this.bounds = null;           // [x0, y0, x1, y1] — чтобы не улететь в бесконечность
    this.minZ = -60;              // глубже сетей камере делать нечего: упор, как у поверхности в Google Earth
    this.apply();
    this.bind();
  }

  // ---------- состояние ----------
  forward(v = new THREE.Vector3()) { const c = Math.cos(this.pitch); return v.set(Math.sin(this.yaw) * c, Math.cos(this.yaw) * c, Math.sin(this.pitch)); }
  right(v = new THREE.Vector3()) { return v.set(Math.cos(this.yaw), -Math.sin(this.yaw), 0); }
  apply() {
    const q = new THREE.Quaternion().setFromAxisAngle(Z, -this.yaw)
      .multiply(new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(1, 0, 0), Math.PI / 2 + this.pitch));
    if (this.bounds) {
      const [x0, y0, x1, y1] = this.bounds, m = Math.max(x1 - x0, y1 - y0, 200) * 2;
      this.pos.x = clamp(this.pos.x, x0 - m, x1 + m); this.pos.y = clamp(this.pos.y, y0 - m, y1 + m);
      this.pos.z = clamp(this.pos.z, -m / 2, m * 2);
    }
    if (this.pos.z < this.minZ) this.pos.z = this.minZ;
    this.camera.position.copy(this.pos);
    this.camera.quaternion.copy(q);
    this.camera.updateMatrixWorld();
    this.hooks.onChange?.();
  }
  get state() { return {pos: this.pos.toArray().map(v => +v.toFixed(2)), yaw: +(this.yaw / D).toFixed(2), pitch: +(this.pitch / D).toFixed(2)}; }
  set state(s) { this.pos.fromArray(s.pos); this.yaw = s.yaw * D; this.pitch = s.pitch * D; this.apply(); }

  ray(x, y) {
    const r = this.dom.getBoundingClientRect();
    const ndc = new THREE.Vector2(x / r.width * 2 - 1, -(y / r.height) * 2 + 1);
    const rc = new THREE.Raycaster();
    rc.setFromCamera(ndc, this.camera);
    return rc.ray;
  }
  // Точка луча на плоскости z = h (если луч к ней идёт и не слишком далеко)
  onPlane(x, y, h = 0, maxDist = Infinity) {
    const ray = this.ray(x, y), dz = ray.direction.z;
    if (Math.abs(dz) < 1e-6) return null;
    const t = (h - ray.origin.z) / dz;
    if (t <= 0 || t > maxDist) return null;
    return ray.at(t, new THREE.Vector3());
  }
  // Опорная точка: объект под курсором, иначе земля, иначе точка впереди
  pivotAt(x, y) {
    const P = this.hooks.pickPoint?.(x, y);
    if (P) return P;
    const far = Math.max(2000, Math.abs(this.pos.z) * 20);
    return this.onPlane(x, y, 0, far) || this.ray(x, y).at(Math.max(50, Math.abs(this.pos.z) * 2), new THREE.Vector3());
  }
  center() { const r = this.dom.getBoundingClientRect(); return this.pivotAt(r.width / 2, r.height / 2); }

  // ---------- операции ----------
  stop() { this.anim = null; }
  rotateAround(P, dYaw, dPitch) {
    // вокруг точки на поверхности — не ниже горизонта (как в Google Earth); под землю — только вокруг трубы или в рентгене
    const maxP = P.z > -0.3 && !this.allowUnder ? -3 * D : 85 * D;
    const p2 = clamp(this.pitch + dPitch, -90 * D, Math.max(maxP, Math.min(this.pitch, 85 * D)));
    dPitch = p2 - this.pitch;
    const v = this.pos.clone().sub(P);
    v.applyAxisAngle(Z, -dYaw);
    this.yaw += dYaw;
    const r = this.right();
    v.applyAxisAngle(r, dPitch);
    this.pitch = p2;
    this.pos.copy(P).add(v);
    this.apply();
  }
  look(dYaw, dPitch) { this.yaw += dYaw; this.pitch = clamp(this.pitch + dPitch, -90 * D, 90 * D); this.apply(); }
  // Масштаб к точке Q: камера едет к ней (factor < 1 — ближе)
  zoomTo(Q, factor) {
    const v = this.pos.clone().sub(Q);
    const d = v.length();
    const nd = clamp(d * factor, 0.6, 60000);
    this.pos.copy(Q).add(v.multiplyScalar(nd / Math.max(d, 1e-6)));
    this.apply();
  }
  translate(v) { this.pos.add(v); this.apply(); }
  // Скорость полёта зависит от высоты над землёй: у труб медленно, над районом быстро
  flySpeed() { return (Math.abs(this.pos.z) * 1.1 + 14) * this.speedK; }

  animateTo(target, ms = 700) {
    const from = {pos: this.pos.clone(), yaw: this.yaw, pitch: this.pitch};
    let dy = (target.yaw ?? this.yaw) - from.yaw;
    dy = Math.atan2(Math.sin(dy), Math.cos(dy));
    const to = {pos: target.pos.clone(), yaw: from.yaw + dy, pitch: target.pitch ?? this.pitch};
    // дуга вверх при дальнем перелёте — как в Google Earth, чтобы было видно, куда летим
    const dist = from.pos.distanceTo(to.pos), lift = dist > 400 ? Math.min(dist * 0.25, 3000) : 0;
    const t0 = performance.now();
    const step = now => {
      if (this.anim !== step) return;
      const k = clamp((now - t0) / ms, 0, 1), e = k < 0.5 ? 4 * k * k * k : 1 - Math.pow(-2 * k + 2, 3) / 2;
      this.pos.lerpVectors(from.pos, to.pos, e);
      this.pos.z += lift * Math.sin(Math.PI * e);
      this.yaw = from.yaw + (to.yaw - from.yaw) * e;
      this.pitch = from.pitch + (to.pitch - from.pitch) * e;
      this.apply();
      if (k < 1) requestAnimationFrame(step); else { this.anim = null; target.done?.(); }
    };
    this.anim = step;
    requestAnimationFrame(step);
  }
  // Смотреть на точку P с направления (yaw, pitch) с расстояния dist
  lookAtFrom(P, yaw, pitch, dist, ms = 700) {
    const c = Math.cos(pitch), f = new THREE.Vector3(Math.sin(yaw) * c, Math.cos(yaw) * c, Math.sin(pitch));
    const pos = P.clone().addScaledVector(f, -dist);
    if (ms) this.animateTo({pos, yaw, pitch}, ms); else { this.pos.copy(pos); this.yaw = yaw; this.pitch = pitch; this.apply(); }
  }
  // Вписать охват [x0,y0,z0,x1,y1,z1] в кадр, сохранив курс; наклон — не круче pitch
  frame(box, {pitch = null, yaw = null, ms = 700, pad = 1.25} = {}) {
    const [x0, y0, z0, x1, y1, z1] = box;
    const C = new THREE.Vector3((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2);
    const R = Math.max(Math.hypot(x1 - x0, y1 - y0, z1 - z0) / 2, 6);
    const r = this.dom.getBoundingClientRect(), aspect = r.width / Math.max(r.height, 1);
    const vf = this.camera.fov * D / 2, hf = Math.atan(Math.tan(vf) * aspect);
    const dist = R * pad / Math.sin(Math.min(vf, hf));
    const p = pitch ?? (this.pitch > -20 * D ? -40 * D : this.pitch);
    this.lookAtFrom(C, yaw ?? this.yaw, p, dist, ms);
  }

  // ---------- ввод ----------
  bind() {
    const el = this.dom;
    const local = e => { const r = el.getBoundingClientRect(); return [e.clientX - r.left, e.clientY - r.top]; };
    const ptrs = new Map();   // pointerId → {x, y}
    let g = null;             // жест мыши/одного пальца
    let t2 = null;            // жест двумя пальцами
    let lastTap = 0;
    el.addEventListener('contextmenu', e => e.preventDefault());

    const mouseMode = (buttons, e) => {
      if (this.mode === 'fly') return buttons & 4 ? 'pan' : 'look';
      if (buttons & 2) return 'orbit';
      if (buttons & 4) return e.shiftKey ? 'orbit' : 'pan';
      return e.shiftKey || e.ctrlKey || e.altKey ? 'orbit' : 'pan';
    };
    const startMode = (m, x, y) => {
      g.mode = m;
      if (m === 'orbit') { g.P = this.hooks.orbitPivot?.(x, y) || this.pivotAt(x, y); this.hooks.onPivot?.(g.P); }
      if (m === 'pan') {
        const P = this.pivotAt(x, y);
        g.h = P.z;   // тащим ту плоскость, за которую взялись: трубу на глубине — за её глубину
        g.P0 = P;
      }
    };

    el.addEventListener('pointerdown', e => {
      if (!this.enabled) return;
      el.focus?.({preventScroll: true});
      this.stop();
      const [x, y] = local(e);
      ptrs.set(e.pointerId, {x, y});
      el.setPointerCapture?.(e.pointerId);
      if (ptrs.size === 2) {
        g = null;
        const [a, b] = [...ptrs.values()];
        t2 = {a: {...a}, b: {...b}, a0: {...a}, b0: {...b}, mode: null, P: this.pivotAt((a.x + b.x) / 2, (a.y + b.y) / 2)};
        clearTimeout(this._lp);
        this.hooks.onPivot?.(t2.P);
        return;
      }
      if (ptrs.size > 2) return;
      const buttons = e.pointerType === 'mouse' ? e.buttons : 1;
      g = {x, y, x0: x, y0: y, on: false, buttons, touch: e.pointerType !== 'mouse', btn: e.button, shift: e.shiftKey};
      if (g.touch) {
        const gg = g;
        clearTimeout(this._lp);
        this._lp = setTimeout(() => { if (g === gg && !gg.on && !this.noContext) { gg.long = true; this.hooks.onContext?.(gg.x0, gg.y0, e); } }, 550);
      }
    });

    el.addEventListener('pointermove', e => {
      const [x, y] = local(e);
      if (ptrs.has(e.pointerId)) ptrs.set(e.pointerId, {x, y});
      this.hooks.onHover?.(x, y, e, !!(g?.on || t2));
      if (t2 && ptrs.size === 2) { twoFinger(); return; }
      if (!g) return;
      if (!g.on) {
        if (Math.hypot(x - g.x0, y - g.y0) < (g.touch ? 6 : 3)) return;
        g.on = true;
        startMode(g.touch ? (this.mode === 'fly' ? 'look' : 'pan') : mouseMode(g.buttons, e), g.x0, g.y0);
        g.x = g.x0; g.y = g.y0;
      }
      // Shift/Ctrl можно нажать и отпустить посреди перетаскивания — режим переключается сразу
      if (!g.touch) {
        const m = mouseMode(e.buttons || g.buttons, e);
        if (m !== g.mode) { if (g.mode === 'orbit') this.hooks.onPivot?.(null); startMode(m, x, y); g.x = x; g.y = y; }
      }
      const r = el.getBoundingClientRect(), dx = x - g.x, dy = y - g.y;
      if (g.mode === 'orbit') this.rotateAround(g.P, dx / r.width * Math.PI * 1.2, -dy / r.height * Math.PI * 0.9);
      else if (g.mode === 'look') this.look(dx / r.width * Math.PI * 0.9, -dy / r.height * Math.PI * 0.7);
      else if (g.mode === 'pan') panGrab(x, y);
      g.x = x; g.y = y;
    });

    // Сдвиг «за точку»: точка, за которую взялись, остаётся под курсором (как в Картах)
    const panGrab = (x, y) => {
      const P1 = this.onPlane(x, y, g.h, 1e6);
      if (!P1) return;
      const d = g.P0.clone().sub(P1);
      d.z = 0;
      const lim = Math.max(30, this.pos.distanceTo(g.P0) * 0.5);   // у горизонта луч уходит далеко — не улетаем
      if (d.length() > lim) d.setLength(lim);
      this.translate(d);
    };

    const twoFinger = () => {
      const [a, b] = [...ptrs.values()], r = el.getBoundingClientRect();
      const d0 = Math.hypot(t2.b.x - t2.a.x, t2.b.y - t2.a.y), d1 = Math.hypot(b.x - a.x, b.y - a.y);
      const ang0 = Math.atan2(t2.b.y - t2.a.y, t2.b.x - t2.a.x), ang1 = Math.atan2(b.y - a.y, b.x - a.x);
      const my0 = (t2.a.y + t2.b.y) / 2, my1 = (a.y + b.y) / 2;
      let dAng = ang1 - ang0; dAng = Math.atan2(Math.sin(dAng), Math.cos(dAng));
      const zoom = d0 / Math.max(d1, 1), dy = my1 - my0;
      // что это за жест, решаем по сдвигу каждого пальца от начала: пальцы приходят отдельными событиями,
      // поэтому сравнивать надо накопленный путь, а не последнее событие
      if (!t2.mode) {
        const da = a.y - t2.a0.y, db = b.y - t2.b0.y;
        const dd = Math.hypot(b.x - a.x, b.y - a.y) - Math.hypot(t2.b0.x - t2.a0.x, t2.b0.y - t2.a0.y);
        let ang = Math.atan2(b.y - a.y, b.x - a.x) - Math.atan2(t2.b0.y - t2.a0.y, t2.b0.x - t2.a0.x); ang = Math.atan2(Math.sin(ang), Math.cos(ang));
        if (Math.abs(da) > 6 && Math.abs(db) > 6 && da * db > 0 && Math.abs(dd) < Math.min(Math.abs(da), Math.abs(db)) * 0.8) t2.mode = 'tilt';
        else if (Math.abs(dd) > 10 || Math.abs(ang) > 0.08) t2.mode = 'pinch';
        if (!t2.mode) return;
        t2.a = {...t2.a0}; t2.b = {...t2.b0};
        return twoFinger();
      }
      if (t2.mode === 'tilt') this.rotateAround(t2.P, 0, -dy / r.height * Math.PI * 0.45);
      else {
        if (Math.abs(zoom - 1) > 0.002) this.zoomTo(t2.P, zoom);
        if (Math.abs(dAng) > 0.002) this.rotateAround(t2.P, -dAng, 0);
      }
      t2.a = {...a}; t2.b = {...b};
    };

    const end = e => {
      const [x, y] = local(e);
      const wasG = g;
      ptrs.delete(e.pointerId);
      if (ptrs.size < 2 && t2) { t2 = null; this.hooks.onPivot?.(null); g = null; return; }
      g = null;
      clearTimeout(this._lp);
      if (!wasG) return;
      if (wasG.mode === 'orbit') this.hooks.onPivot?.(null);
      if (!wasG.on && !wasG.long && e.type === 'pointerup') {
        // щелчок без перетаскивания; одиночный ждёт 250 мс — вдруг это двойной (тогда только приблизить, как в Картах)
        const now = performance.now();
        if (wasG.btn === 2) this.hooks.onContext?.(x, y, e);
        else if (wasG.btn === 1) {
          // двойной щелчок средней — показать всё, как в AutoCAD
          if (now - (this._mid || 0) < 400) { this.hooks.onFit?.(); this._mid = 0; } else this._mid = now;
        } else if (wasG.btn === 0) {
          clearTimeout(this._click);
          if (now - lastTap < 320 && Math.hypot(x - this._tapXY[0], y - this._tapXY[1]) < 30) { lastTap = 0; this.hooks.onDblClick?.(x, y, e); }
          else { lastTap = now; this._tapXY = [x, y]; this._click = setTimeout(() => this.hooks.onClick?.(x, y, e), 250); }
        }
      }
    };
    el.addEventListener('pointerup', end);
    el.addEventListener('pointercancel', end);
    this._tapXY = [0, 0];

    // Колесо: мышь — масштаб к курсору; тачпад — два пальца двигают, щипок — масштаб, ⌥ + два пальца — вращать
    let wheelP = null, wheelT = 0;
    el.addEventListener('wheel', e => {
      if (!this.enabled) return;
      e.preventDefault();
      this.stop();
      const [x, y] = local(e);
      const k = e.deltaMode === 1 ? 16 : e.deltaMode === 2 ? 400 : 1, dx = e.deltaX * k, dy = e.deltaY * k;
      const now = performance.now();
      if (!wheelP || now - wheelT > 250 || Math.hypot(x - wheelP.x, y - wheelP.y) > 8) wheelP = {x, y, P: this.pivotAt(x, y)};
      wheelT = now;
      const mouse = e.deltaMode === 1 || (e.deltaX === 0 && Math.abs(dy) >= 50 && Number.isInteger(dy));
      if (this.mode === 'fly' && (e.buttons & 2)) { this.speedK = clamp(this.speedK * (dy > 0 ? 0.8 : 1.25), 0.05, 50); this.hooks.onSpeed?.(this.speedK); return; }
      if (e.ctrlKey || e.metaKey) this.zoomTo(wheelP.P, Math.exp(dy * 0.01));
      else if (e.altKey) { this.rotateAround(wheelP.P, dx * 0.004, -dy * 0.003); this.hooks.onPivot?.(wheelP.P); clearTimeout(this._pt); this._pt = setTimeout(() => this.hooks.onPivot?.(null), 300); }
      else if (mouse || e.shiftKey === false && Math.abs(dx) < 0.01 && Math.abs(dy) > 40) this.zoomTo(wheelP.P, Math.exp(dy * 0.0016));
      else {
        // сдвиг тачпадом на dx,dy пикселей экрана
        const r = el.getBoundingClientRect(), cx = r.width / 2, cy = r.height / 2;
        const P = this.pivotAt(cx, cy), A = this.onPlane(cx, cy, P.z, 1e6), B = this.onPlane(cx + dx, cy + dy, P.z, 1e6);
        if (A && B) { const d = B.sub(A); d.z = 0; const lim = Math.max(30, this.pos.distanceTo(A) * 0.3); if (d.length() > lim) d.setLength(lim); this.translate(d); }
      }
    }, {passive: false});

    // Safari: щипок и поворот на тачпаде
    let gs = null;
    el.addEventListener('gesturestart', e => { e.preventDefault(); const r = el.getBoundingClientRect(); gs = {s: 1, r: 0, P: this.pivotAt(r.width / 2, r.height / 2)}; });
    el.addEventListener('gesturechange', e => { e.preventDefault(); if (!gs) return; this.zoomTo(gs.P, gs.s / e.scale); this.rotateAround(gs.P, (e.rotation - gs.r) * D, 0); gs.s = e.scale; gs.r = e.rotation; });
    el.addEventListener('gestureend', e => { e.preventDefault(); gs = null; });

    // Клавиши полёта
    const MOVE = new Set(['KeyW', 'KeyA', 'KeyS', 'KeyD', 'KeyQ', 'KeyE', 'ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'PageUp', 'PageDown']);
    const typing = e => /INPUT|SELECT|TEXTAREA/.test(e.target.tagName) || e.target.isContentEditable;
    let running = false;
    addEventListener('keydown', e => {
      if (!this.enabled || typing(e) || e.metaKey || e.ctrlKey || e.altKey || !MOVE.has(e.code)) return;
      e.preventDefault();
      this.stop();
      this.held.add(e.code);
      this.fast = e.shiftKey;
      if (running) return;
      running = true;
      let prev = performance.now() - 33;   // короткое нажатие тоже сдвигает камеру хотя бы на кадр
      const step = now => {
        if (!this.held.size) { running = false; this.hooks.onFlyEnd?.(); return; }
        const dt = Math.max(0, Math.min(0.05, (now - prev) / 1000)), h = c => this.held.has(c);
        prev = now;
        const v = this.flySpeed() * (this.fast ? 4 : 1) * dt;
        const fw = (h('KeyW') || h('ArrowUp') ? 1 : 0) - (h('KeyS') || h('ArrowDown') ? 1 : 0);
        const sd = (h('KeyD') || h('ArrowRight') ? 1 : 0) - (h('KeyA') || h('ArrowLeft') ? 1 : 0);
        const up = (h('KeyE') || h('PageUp') ? 1 : 0) - (h('KeyQ') || h('PageDown') ? 1 : 0);
        const f = this.forward(), rgt = this.right();
        // в режиме «Обзор» стрелки и WASD ведут по горизонтали (как по карте), в «Полёте» — туда, куда смотрим
        if (this.mode === 'orbit') { f.z = 0; if (f.lengthSq() < 1e-6) f.set(Math.sin(this.yaw), Math.cos(this.yaw), 0); f.normalize(); }
        const mv = new THREE.Vector3().addScaledVector(f, fw * v).addScaledVector(rgt, sd * v).addScaledVector(Z, up * v);
        if (mv.lengthSq()) this.translate(mv);
        this.hooks.onFly?.();
        requestAnimationFrame(step);
      };
      requestAnimationFrame(step);
    });
    addEventListener('keyup', e => { this.held.delete(e.code); this.fast = e.shiftKey; });
    addEventListener('blur', () => this.held.clear());
  }
}
