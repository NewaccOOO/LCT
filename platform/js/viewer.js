// 3D-сцена на three.js: земля с сеткой, поверхность (дороги, парки, вода), здания объёмом, подземные коммуникации
// и новая сеть трубами настоящего диаметра, колодцы камер, булавки зданий к подключению.
// Вертикаль растягивается множителем ex («Глубина ×N») — только положения, диаметры остаются настоящими.
import * as THREE from 'three';
import {LineSegments2} from 'three/addons/lines/LineSegments2.js';
import {LineSegmentsGeometry} from 'three/addons/lines/LineSegmentsGeometry.js';
import {LineMaterial} from 'three/addons/lines/LineMaterial.js';
import {CSS2DRenderer, CSS2DObject} from 'three/addons/renderers/CSS2DRenderer.js';
import {UTILITIES, SURFACES, BUILDING_KEYS, NODE_TYPES, nodeName} from './model.js';

THREE.Object3D.DEFAULT_UP.set(0, 0, 1);

const YAX = new THREE.Vector3(0, 1, 0);
const col = h => new THREE.Color(h);
export const PALETTE = {
  // проблема — малиновая: красный уже занят существующей теплосетью, один цвет — одно значение
  selection: '#fde047', problem: '#ff2fd1', ok: '#22c55e', special: '#fb923c',
  connected: '#22c55e', unconnected: '#f43f5e', future: '#e8dcc0', chamberOld: '#64748b', chamberNew: '#38bdf8',
};
// Новая сеть — свой бирюзовый цвет, которого нет ни у одной существующей сети (вода синяя, кабель фиолетовый,
// теплосеть красная). Ду меняет только светлоту: тонкие — светлее, магистрали — насыщеннее; толщина — настоящая
export function dnColor(dn) {
  const t = Math.min(1, Math.max(0, Math.log2((dn || 50) / 50) / Math.log2(28)));
  return new THREE.Color().setHSL(0.49, 0.95, 0.72 - 0.26 * t);
}

// ---------- геометрия ----------
// Трубы: цилиндр на каждый прямой кусок полилинии, экземплярами. Ось цилиндра three — Y.
function tubeMesh(pieces, {radial = 10, material, overlap = true}) {
  const geo = new THREE.CylinderGeometry(1, 1, 1, radial, 1, true);
  const mesh = new THREE.InstancedMesh(geo, material, Math.max(1, pieces.length));
  mesh.count = pieces.length;
  const m = new THREE.Matrix4(), q = new THREE.Quaternion(), s = new THREE.Vector3(), p = new THREE.Vector3(), d = new THREE.Vector3();
  const c = new THREE.Color();
  pieces.forEach((pc, i) => {
    d.set(pc.b[0] - pc.a[0], pc.b[1] - pc.a[1], pc.b[2] - pc.a[2]);
    const len = d.length() || 1e-3;
    q.setFromUnitVectors(YAX, d.divideScalar(len));
    p.set((pc.a[0] + pc.b[0]) / 2, (pc.a[1] + pc.b[1]) / 2, (pc.a[2] + pc.b[2]) / 2);
    s.set(pc.r, len + (overlap ? pc.r * 1.6 : 0), pc.r);   // перекрытие закрывает щели на изломах
    mesh.setMatrixAt(i, m.compose(p, q, s));
    mesh.setColorAt(i, c.set(pc.color));
  });
  mesh.instanceMatrix.needsUpdate = true;
  if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
  mesh.computeBoundingSphere();
  mesh.frustumCulled = false;
  return mesh;
}
function sphereMesh(items, material, seg = 12) {
  const mesh = new THREE.InstancedMesh(new THREE.SphereGeometry(1, seg, Math.max(6, seg * 0.6)), material, Math.max(1, items.length));
  mesh.count = items.length;
  const m = new THREE.Matrix4(), c = new THREE.Color();
  items.forEach((it, i) => { mesh.setMatrixAt(i, m.makeScale(it.r, it.r, it.r).setPosition(it.p[0], it.p[1], it.p[2])); mesh.setColorAt(i, c.set(it.color)); });
  mesh.instanceMatrix.needsUpdate = true;
  if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
  mesh.frustumCulled = false;
  return mesh;
}
function boxMesh(items, material) {
  const mesh = new THREE.InstancedMesh(new THREE.BoxGeometry(1, 1, 1), material, Math.max(1, items.length));
  mesh.count = items.length;
  const m = new THREE.Matrix4(), c = new THREE.Color(), q = new THREE.Quaternion();
  items.forEach((it, i) => { mesh.setMatrixAt(i, m.compose(new THREE.Vector3(...it.p), q, new THREE.Vector3(...it.s))); mesh.setColorAt(i, c.set(it.color)); });
  mesh.instanceMatrix.needsUpdate = true;
  if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
  mesh.frustumCulled = false;
  return mesh;
}
// Линии постоянной толщины в пикселях — чтобы сеть была видна на обзоре района, где трубы тоньше пикселя
function fatLines(segments, {width = 2, color = '#ffffff', opacity = 1, colors = null, dashed = false, depthTest = true}) {
  const geo = new LineSegmentsGeometry();
  geo.setPositions(segments);
  if (colors) geo.setColors(colors);
  const mat = new LineMaterial({color: colors ? 0xffffff : color, linewidth: width, vertexColors: !!colors, transparent: opacity < 1, opacity,
    dashed, dashSize: 6, gapSize: 4, depthTest, worldUnits: false});
  mat.fog = true;
  const ln = new LineSegments2(geo, mat);
  if (dashed) ln.computeLineDistances();
  ln.frustumCulled = false;
  return ln;
}

// Плоские полигоны (кольца в метрах) → треугольники на высоте z
function flatGeometry(items, z) {
  const pos = [];
  for (const it of items) {
    const rings = it.rings.map(r => { const a = r.slice(); if (a.length > 1 && a[0][0] === a[a.length - 1][0] && a[0][1] === a[a.length - 1][1]) a.pop(); return a; });
    if (rings[0].length < 3) continue;
    const contour = rings[0].map(p => new THREE.Vector2(p[0], p[1])), holes = rings.slice(1).filter(h => h.length >= 3).map(h => h.map(p => new THREE.Vector2(p[0], p[1])));
    let tris;
    try { tris = THREE.ShapeUtils.triangulateShape(contour, holes); } catch (e) { continue; }
    const all = contour.concat(...holes);
    for (const t of tris) for (const k of t) pos.push(all[k].x, all[k].y, z);
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  return g;
}
// Здания: стены и крыша одним буфером; цвет по вершинам
function buildingGeometry(items, heightOf, colorOf) {
  const pos = [], nrm = [], clr = [];
  const c = new THREE.Color();
  for (const it of items) {
    const ring = it.rings[0].slice();
    if (ring.length > 1 && ring[0][0] === ring[ring.length - 1][0] && ring[0][1] === ring[ring.length - 1][1]) ring.pop();
    if (ring.length < 3) continue;
    const H = heightOf(it);
    c.set(colorOf(it));
    const contour = ring.map(p => new THREE.Vector2(p[0], p[1]));
    let tris;
    try { tris = THREE.ShapeUtils.triangulateShape(contour, []); } catch (e) { continue; }
    for (const t of tris) for (const k of t) { pos.push(contour[k].x, contour[k].y, H); nrm.push(0, 0, 1); clr.push(c.r * 1.08, c.g * 1.08, c.b * 1.08); }
    for (let i = 0; i < ring.length; i++) {
      const a = ring[i], b = ring[(i + 1) % ring.length];
      const ex = b[0] - a[0], ey = b[1] - a[1], l = Math.hypot(ex, ey) || 1, nx = ey / l, ny = -ex / l;
      const quad = [[a[0], a[1], 0], [b[0], b[1], 0], [b[0], b[1], H], [a[0], a[1], 0], [b[0], b[1], H], [a[0], a[1], H]];
      for (const q of quad) { pos.push(...q); nrm.push(nx, ny, 0); const sh = 0.78 + 0.12 * q[2] / H; clr.push(c.r * sh, c.g * sh, c.b * sh); }
    }
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setAttribute('normal', new THREE.Float32BufferAttribute(nrm, 3));
  g.setAttribute('color', new THREE.Float32BufferAttribute(clr, 3));
  return g;
}
export const buildingHeight = it => {
  const lv = +(it.p.levels ?? it.p['building:levels'] ?? 0);
  return lv > 0 ? Math.min(lv * 3.2, 250) : it.p.height ? +it.p.height : 9;
};

// Земля: полупрозрачная плоскость с сеткой 10/100 м, которая гаснет вдали (шейдер с логарифмической глубиной)
function groundMaterial() {
  return new THREE.ShaderMaterial({
    transparent: true, depthWrite: false, side: THREE.DoubleSide,
    uniforms: {uColor: {value: col('#1f2a3d')}, uLine: {value: col('#64748b')}, uOpacity: {value: 0.6}, uCam: {value: new THREE.Vector3()}},
    vertexShader: `
      #include <common>
      #include <logdepthbuf_pars_vertex>
      varying vec3 vW;
      void main() { vec4 w = modelMatrix * vec4(position, 1.0); vW = w.xyz; gl_Position = projectionMatrix * viewMatrix * w;
        #include <logdepthbuf_vertex>
      }`,
    fragmentShader: `
      #include <logdepthbuf_pars_fragment>
      uniform vec3 uColor; uniform vec3 uLine; uniform float uOpacity; uniform vec3 uCam; varying vec3 vW;
      float grid(vec2 p, float s) { vec2 q = p / s; vec2 g = abs(fract(q - 0.5) - 0.5) / fwidth(q); return 1.0 - min(min(g.x, g.y), 1.0); }
      void main() {
        #include <logdepthbuf_fragment>
        float d = length(vW.xy - uCam.xy) + abs(uCam.z);
        float g10 = grid(vW.xy, 10.0) * (1.0 - smoothstep(150.0, 600.0, d));
        float g100 = grid(vW.xy, 100.0) * (1.0 - smoothstep(2500.0, 9000.0, d));
        float a = max(g10 * 0.45, g100 * 0.8);
        gl_FragColor = vec4(mix(uColor, uLine, a), clamp(uOpacity + a * 0.35, 0.0, 1.0));
      }`,
  });
}

export class Viewer {
  constructor(host) {
    this.host = host;
    this.renderer = new THREE.WebGLRenderer({antialias: true, logarithmicDepthBuffer: true, preserveDrawingBuffer: false, powerPreference: 'high-performance'});
    this.renderer.setPixelRatio(Math.min(devicePixelRatio || 1, 2));
    this.renderer.setClearColor(0x0d1526, 1);
    this.renderer.localClippingEnabled = true;
    this.renderer.domElement.tabIndex = 0;
    this.renderer.domElement.setAttribute('aria-label', '3D-сцена теплосети');
    host.appendChild(this.renderer.domElement);
    this.labels = new CSS2DRenderer();
    this.labels.domElement.className = 'labels';
    host.appendChild(this.labels.domElement);

    this.scene = new THREE.Scene();
    this.scene.fog = new THREE.Fog(0x0d1526, 1e5, 2e5);
    this.camera = new THREE.PerspectiveCamera(45, 1, 0.05, 2e5);
    this.scene.add(new THREE.HemisphereLight(0xdbeafe, 0x334155, 1.6));
    const sun = new THREE.DirectionalLight(0xffffff, 1.4);
    sun.position.set(0.5, -0.8, 1.2);
    this.scene.add(sun);
    const low = new THREE.DirectionalLight(0x93c5fd, 0.5);   // подсветка снизу — чтобы трубы снизу не были чёрными
    low.position.set(-0.4, 0.6, -1);
    this.scene.add(low);

    this.g = {};
    for (const k of ['surface', 'buildings', 'utilities', 'utilLines', 'existing', 'oks', 'network', 'netLines', 'overlay', 'tools', 'ghost']) {
      this.g[k] = new THREE.Group();
      this.g[k].name = k;
      this.scene.add(this.g[k]);
    }
    this.ex = 3;
    this.opts = {groundOpacity: 0.4, buildings: 'solid', colorBy: 'dn', xray: false, layers: {}};
    this.clipPlanes = [];
    this.lineMats = new Set();
    this.dirty = true;
    this.model = null;
    this.variant = null;
    this.pickItems = [];
    this.pickGrid = new Map();
    this.pickCell = 30;

    new ResizeObserver(() => this.resize()).observe(host);
    this.resize();
    const loop = () => { if (this.dirty) { this.dirty = false; this.render(); } requestAnimationFrame(loop); };
    requestAnimationFrame(loop);
  }

  resize() {
    const w = this.host.clientWidth, h = this.host.clientHeight;
    if (!w || !h) return;
    this.renderer.setSize(w, h, false);
    this.labels.setSize(w, h);
    this.camera.aspect = w / h;
    this.camera.updateProjectionMatrix();
    for (const m of this.lineMats) m.resolution.set(w, h);
    this.dirty = true;
  }
  // Уровень детализации по высоте камеры: над районом существующие сети гаснут, новая становится толще
  lod() {
    const h = Math.abs(this.camera.position.z), k = Math.min(1, Math.max(0, (h - 300) / 2500));
    // дальность видимости по высоте: у земли и под ней — 2,5–3 км с туманом у горизонта, сверху — без ограничений.
    // Так тайлы за горизонтом не рисуются, а обрыв сцены прячется в дымке
    const far = Math.min(2e5, Math.max(2500, h * 40 + 1500));
    if (Math.abs(far - this.camera.far) / this.camera.far > 0.05) {
      this.camera.far = far;
      this.camera.updateProjectionMatrix();
      this.scene.fog.near = far * 0.5; this.scene.fog.far = far * 0.97;
    }
    if (this._lodK != null && Math.abs(this._lodK - k) < 0.02) return;
    this._lodK = k;
    for (const l of this.g.utilLines.children) l.material.opacity = 0.55 - 0.35 * k;
    const under = this.camera.position.z < 0;
    if (under !== this._under) {
      this._under = under;
      this.renderer.setClearColor(under ? 0x1a140e : 0x0d1526, 1);
      this.scene.fog.color.set(under ? 0x1a140e : 0x0d1526);
      if (this.depthGrid) this.depthGrid.visible = under;
    }
    const net = this.g.netLines.getObjectByName('netLine');
    if (net) net.material.linewidth = 3.4 + 1.8 * k;
  }
  render() {
    this.lod();
    for (const m of this.g.tools.children) if (m.userData.marker) this.scaleMarker(m);
    if (this.groundMat) this.groundMat.uniforms.uCam.value.copy(this.camera.position);
    if (this.depthGridMat) { this.depthGridMat.uniforms.uCam.value.copy(this.camera.position); this.depthGrid.position.z = -3 * this.ex; }
    this.renderer.render(this.scene, this.camera);
    this.labels.render(this.scene, this.camera);
    this.declutter();
    this.onRender?.();
  }
  // Подписи не должны налезать друг на друга: важные (конец трассы, проблемы) остаются, остальные прячутся
  declutter() {
    const els = [];
    this.g.overlay.traverse(o => { if (o.isCSS2DObject && o.element.style.display !== 'none') els.push(o.element); });
    if (els.length < 2) return;
    const rank = el => el.classList.contains('end') ? 0 : el.classList.contains('bad') ? 1 : el.classList.contains('depth') ? 3 : el.classList.contains('node') ? 4 : 2;
    els.sort((a, b) => rank(a) - rank(b));
    const placed = [];
    for (const el of els) {
      el.style.visibility = '';
      const r = el.getBoundingClientRect();
      if (!r.width) continue;
      if (placed.some(p => r.left < p.right + 2 && r.right > p.left - 2 && r.top < p.bottom + 1 && r.bottom > p.top - 1)) el.style.visibility = 'hidden';
      else placed.push(r);
    }
  }
  redraw() { this.dirty = true; }
  trackLines(obj) {
    obj.traverse(o => { if (o.material?.isLineMaterial) { o.material.resolution.set(this.host.clientWidth, this.host.clientHeight); this.lineMats.add(o.material); } });
    return obj;
  }
  clear(group) {
    for (const o of [...group.children]) {
      o.traverse(x => {
        if (x.geometry) x.geometry.dispose();
        if (x.material) { (Array.isArray(x.material) ? x.material : [x.material]).forEach(m => { this.lineMats.delete(m); m.dispose(); }); }
        if (x.isCSS2DObject) x.element.remove();
      });
      group.remove(o);
    }
  }
  material(kind, opts = {}) {
    const m = kind === 'lambert' ? new THREE.MeshLambertMaterial(opts) : kind === 'basic' ? new THREE.MeshBasicMaterial(opts) : new THREE.MeshStandardMaterial(opts);
    m.clippingPlanes = this.clipPlanes;
    return m;
  }
  z(depth) { return -depth * this.ex; }

  // Разбивка по квадратам плана: до 12×12 тайлов на набор, не мельче 250 м
  tiles(items, xyOf) {
    const [x0, y0, x1, y1] = this.model.bbox, T = Math.max(250, Math.max(x1 - x0, y1 - y0) / 12), m = new Map();
    for (const it of items) { const [x, y] = xyOf(it), k = Math.floor(x / T) * 100003 + Math.floor(y / T); let l = m.get(k); if (!l) m.set(k, l = []); l.push(it); }
    return [...m.values()];
  }

  // ---------- исходные данные ----------
  setModel(model) {
    this.model = model;
    this.ghost = null;
    for (const k of Object.keys(this.g)) this.clear(this.g[k]);
    if (this.ground) { this.scene.remove(this.ground); this.ground.geometry.dispose(); }
    const [x0, y0, x1, y1] = model.bbox, W = Math.max(x1 - x0, y1 - y0, 300);
    this.groundMat = groundMaterial();
    this.groundMat.clippingPlanes = this.clipPlanes;
    this.ground = new THREE.Mesh(new THREE.PlaneGeometry(W * 3, W * 3), this.groundMat);
    this.ground.position.set((x0 + x1) / 2, (y0 + y1) / 2, 0);
    this.ground.renderOrder = 1;
    this.scene.add(this.ground);
    // под землёй — сетка на обычной глубине 3 м, чтобы было за что зацепиться глазом
    if (this.depthGrid) { this.scene.remove(this.depthGrid); this.depthGrid.geometry.dispose(); }
    const dg = groundMaterial();
    dg.uniforms.uColor.value.set('#3b2a18'); dg.uniforms.uLine.value.set('#a07850'); dg.uniforms.uOpacity.value = 0.08;
    this.depthGrid = new THREE.Mesh(new THREE.PlaneGeometry(W * 3, W * 3), dg);
    this.depthGrid.position.set((x0 + x1) / 2, (y0 + y1) / 2, -3 * this.ex);
    this.depthGrid.visible = false; this.depthGrid.renderOrder = 1;
    this.depthGridMat = dg;
    this.scene.add(this.depthGrid);
    this._under = null;
    this.buildSurface();
    this.buildBuildings();
    this.buildUnderground();
    this.applyOpts();
  }

  buildSurface() {
    this.clear(this.g.surface);
    const keys = Object.keys(this.model.surfaces).filter(k => !BUILDING_KEYS.has(k));
    keys.forEach((k, i) => {
      const geo = flatGeometry(this.model.surfaces[k], 0.03 + i * 0.01);
      const m = this.material('basic', {color: (SURFACES[k] || {}).color || '#475569', transparent: true, opacity: 0.55, depthWrite: false, side: THREE.DoubleSide});
      const mesh = new THREE.Mesh(geo, m);
      mesh.name = 'surf:' + k;
      mesh.userData.key = k;
      mesh.renderOrder = 2 + i;
      this.g.surface.add(mesh);
    });
    // точки входа на поверхности: опоры ЛЭП
    const sup = this.model.points.power_line_support || [];
    if (sup.length) {
      const m = sphereMesh(sup.map(p => ({p: [p.xy[0], p.xy[1], 0.5], r: 1.2, color: SURFACES.power_line_support.color})), this.material('lambert', {}));
      m.name = 'surf:power_line_support_pt'; m.userData.key = 'power_line_support';
      this.g.surface.add(m);
    }
  }

  buildBuildings() {
    this.clear(this.g.buildings);
    const items = [];
    for (const k of BUILDING_KEYS) for (const it of this.model.surfaces[k] || []) items.push(it);
    if (!items.length) return;
    this.bldMat = this.material('lambert', {vertexColors: true, transparent: true, opacity: 1, side: THREE.DoubleSide});
    const solid = new THREE.Group();
    solid.name = 'buildings';
    for (const part of this.tiles(items, it => it.rings[0][0])) {
      const mesh = new THREE.Mesh(buildingGeometry(part, buildingHeight, it => it.isFuture || it.p.object_type === 'oks_future' ? PALETTE.future : '#6b7a90'), this.bldMat);
      mesh.geometry.computeBoundingSphere();
      mesh.renderOrder = 10;
      solid.add(mesh);
    }
    this.g.buildings.add(solid);
    // контуры зданий на земле — для режима «контуром»
    const flat = flatGeometry(items, 0.02);
    const fm = new THREE.Mesh(flat, this.material('basic', {color: '#64748b', transparent: true, opacity: 0.7, depthWrite: false, side: THREE.DoubleSide}));
    fm.name = 'buildingsFlat';
    fm.renderOrder = 9;
    this.g.buildings.add(fm);
  }

  // Всё, что зависит от вертикального масштаба: коммуникации, камеры, булавки, новая сеть
  buildUnderground() {
    this.clear(this.g.utilities); this.clear(this.g.utilLines); this.clear(this.g.existing); this.clear(this.g.oks);
    const M = this.model, ex = this.ex;
    this.pickItems = [];
    // существующие коммуникации: трубы с настоящим габаритом на своей глубине + линии для дальнего плана
    for (const [type, lines] of Object.entries(M.utilities)) {
      const u = UTILITIES[type], pieces = [], seg = [];
      for (const ln of lines) {
        const r = Math.max((ln.h || 0.3) / 2, 0.08), zc = -(ln.top + (ln.h || 0.3) / 2) * ex;
        for (let i = 0; i + 1 < ln.pts.length; i++) {
          const a = [ln.pts[i][0], ln.pts[i][1], zc], b = [ln.pts[i + 1][0], ln.pts[i + 1][1], zc];
          pieces.push({a, b, r, color: u.color});
          seg.push(...a, ...b);
          this.pickItems.push({kind: 'utility', type, a, b, r, ref: ln});
        }
      }
      // трубы по тайлам: вблизи рисуется только то, что попало в кадр (важно для города и слабых видеокарт)
      const mat = this.material('standard', {color: 0xffffff, roughness: 0.55, metalness: 0.15});
      for (const part of this.tiles(pieces, pc => [(pc.a[0] + pc.b[0]) / 2, (pc.a[1] + pc.b[1]) / 2])) {
        const mesh = tubeMesh(part, {radial: 8, material: mat});
        mesh.frustumCulled = true;
        mesh.name = 'util:' + type; mesh.userData.key = 'u:' + type;
        this.g.utilities.add(mesh);
      }
      // существующие сети — фон: тоньше и тусклее новой, чтобы обзор района не превращался в паутину
      const ln = fatLines(seg, {width: 1.1, color: u.color, opacity: 0.5});
      ln.name = 'utilLine:' + type; ln.userData.key = 'u:' + type;
      this._lodK = null;
      this.g.utilLines.add(this.trackLines(ln));
    }
    // существующие камеры: колодец от крышки до камеры у трубы
    const ch = M.points.heat_chamber || [];
    if (ch.length) this.addWells(this.g.existing, ch.map(c => ({xy: c.xy, depth: UTILITIES.heat_network.top + 0.5, color: PALETTE.chamberOld, ref: c, kind: 'chamber'})));
    // источник: высокая труба ТЭЦ — ориентир, видно издалека
    for (const s of M.points.source || []) {
      const m = new THREE.Mesh(new THREE.CylinderGeometry(4, 7, 90, 20), this.material('lambert', {color: '#f43f5e'}));
      m.rotation.x = Math.PI / 2; m.position.set(s.xy[0], s.xy[1], 45);
      m.name = 'source'; m.userData.key = 'source';
      this.g.existing.add(m);
      const lb = this.label(`<b>Источник</b> ${s.p.name || s.id}`, 'lbl src');
      lb.position.set(s.xy[0], s.xy[1], 96);
      m.add(lb); lb.position.set(0, 50, 0);
      this.pickItems.push({kind: 'source', a: [s.xy[0], s.xy[1], 0], b: [s.xy[0], s.xy[1], 90], r: 7, ref: s});
    }
    this.buildOks();
    if (this.variant) this.buildNetwork();
    else this.rebuildPickGrid();
    if (this.ghost) this.setGhost(this.ghost);
  }

  addWells(group, wells) {
    const ex = this.ex, boxes = [], shafts = [], lids = [];
    for (const w of wells) {
      const top = Math.max(w.depth - 1.6, 0.6), bot = w.depth + 0.4;
      boxes.push({p: [w.xy[0], w.xy[1], -(top + bot) / 2 * ex], s: [2.2, 2.2, (bot - top) * ex], color: w.color});
      shafts.push({a: [w.xy[0], w.xy[1], 0], b: [w.xy[0], w.xy[1], -top * ex], r: 0.38, color: w.color});
      lids.push({a: [w.xy[0], w.xy[1], 0], b: [w.xy[0], w.xy[1], 0.12], r: 0.6, color: w.color});
      this.pickItems.push({kind: w.kind, a: [w.xy[0], w.xy[1], 0.1], b: [w.xy[0], w.xy[1], -bot * ex], r: 1.4, ref: w.ref});
    }
    const mat = this.material('standard', {color: 0xffffff, roughness: 0.8, metalness: 0});
    // камеры полупрозрачные: трубы внутри и за ними видны
    const bm = this.material('standard', {color: 0xffffff, roughness: 0.8, metalness: 0, transparent: true, opacity: 0.45, depthWrite: false});
    const b = boxMesh(boxes, bm); b.name = 'wells'; b.renderOrder = 12; group.add(b);
    const s = tubeMesh(shafts, {radial: 10, material: mat, overlap: false}); group.add(s);
    const l = tubeMesh(lids, {radial: 16, material: this.material('standard', {color: 0xffffff, roughness: 0.4, metalness: 0.6}), overlap: false}); group.add(l);
  }

  // Булавки зданий к подключению: как метки на картах; цвет — подключено ли в текущем варианте
  buildOks() {
    this.clear(this.g.oks);
    this.pickItems = this.pickItems.filter(p => p.kind !== 'oks');
    const M = this.model, v = this.variant;
    if (!M.oks.length) return;
    const pins = [], heads = [];
    for (const o of M.oks) {
      const H = (o.building ? buildingHeight(o.building) : 12) + 8;
      const c = !M.hasOutput ? PALETTE.future : v && v.connected.has(o.id) ? PALETTE.connected : PALETTE.unconnected;
      pins.push({a: [o.xy[0], o.xy[1], 0], b: [o.xy[0], o.xy[1], H], r: 0.35, color: c});
      heads.push({p: [o.xy[0], o.xy[1], H + 2.2], r: 2.6, color: c});
      this.pickItems.push({kind: 'oks', a: [o.xy[0], o.xy[1], 0], b: [o.xy[0], o.xy[1], H + 4], r: 3, ref: o});
    }
    this.g.oks.add(tubeMesh(pins, {radial: 6, material: this.material('lambert', {color: 0xffffff}), overlap: false}));
    const hm = sphereMesh(heads, this.material('lambert', {color: 0xffffff, emissive: 0x111111}), 14);
    hm.name = 'oksHeads';
    this.g.oks.add(hm);
  }

  // ---------- вариант решения ----------
  setVariant(v) { this.variant = v; this.buildOks(); this.buildNetwork(); }

  segColor(s) {
    // «Проблемы»: участки с проблемой — ярким цветом сети, остальные приглушены (как Clash Detective в Navisworks)
    if (this.opts.colorBy === 'status') return s.bad ? '#5eead4' : '#335c5a';
    if (this.opts.colorBy === 'depth') { const t = Math.min(1, Math.max(0, (Math.max(s.d0, s.d1) - 1) / 4)); return new THREE.Color().setHSL(0.5 - 0.08 * t, 0.9, 0.78 - 0.4 * t).getStyle(); }
    return '#' + dnColor(s.p.diameter).getHexString();
  }
  buildNetwork() {
    this.clear(this.g.network); this.clear(this.g.netLines);
    this.pickItems = this.pickItems.filter(p => p.kind !== 'seg' && p.kind !== 'node');
    const v = this.variant;
    if (!v) { this.rebuildPickGrid(); return; }
    const ex = this.ex, pieces = [], joints = [], seg = [], colors = [], trace = [];
    for (const s of v.segs) {
      const r = s.row.h / 2, color = this.segColor(s), c = new THREE.Color(color);
      const P = s.pts.map((p, i) => [p[0], p[1], -(s.depth[i] + s.row.h / 2) * ex]);
      for (let i = 0; i + 1 < P.length; i++) {
        pieces.push({a: P[i], b: P[i + 1], r, color});
        seg.push(...P[i], ...P[i + 1]);
        colors.push(c.r, c.g, c.b, c.r, c.g, c.b);
        trace.push(P[i][0], P[i][1], 0.15, P[i + 1][0], P[i + 1][1], 0.15);
        this.pickItems.push({kind: 'seg', a: P[i], b: P[i + 1], r, ref: s});
      }
      for (const p of P) joints.push({p, r, color});
    }
    // новая сеть рисуется после полупрозрачной земли (прозрачный проход, renderOrder выше): земля её не глушит,
    // а здания, которые пишут глубину, закрывают как положено
    const mat = this.material('standard', {color: 0xffffff, roughness: 0.35, metalness: 0.3, emissive: 0x0b2235, transparent: true, opacity: 1});
    const tubes = tubeMesh(pieces, {radial: 14, material: mat, overlap: false});
    tubes.name = 'net'; tubes.renderOrder = 15; this.g.network.add(tubes);
    const jm = sphereMesh(joints, mat, 12);
    jm.renderOrder = 15;
    this.g.network.add(jm);
    const ln = fatLines(seg, {width: 3.4, colors, opacity: 0.999});
    ln.renderOrder = 16;
    ln.name = 'netLine';
    this.g.netLines.add(this.trackLines(ln));
    this._lodK = null;
    // проекция трассы на землю — видно сверху сквозь непрозрачную землю (отвес «где копать»)
    const tr = fatLines(trace, {width: 1.2, color: '#67e8f9', opacity: 0.55, dashed: true});
    tr.name = 'trace';
    this.g.netLines.add(this.trackLines(tr));
    // камеры и врезки новой сети
    const wells = [];
    for (const nd of v.nodes) {
      if (nd.type === 'technical_node' || nd.type === 'oks_entry' || nd.type === 'endpoint') {
        this.pickItems.push({kind: 'node', a: [nd.xy[0], nd.xy[1], this.z((nd.depth ?? 3) - 0.1)], b: [nd.xy[0], nd.xy[1], this.z((nd.depth ?? 3) - 0.1)], r: 0.8, ref: nd});
        continue;
      }
      const color = nd.type === 'existing_chamber' ? PALETTE.ok : (NODE_TYPES[nd.type] || {}).color || PALETTE.chamberNew;
      wells.push({xy: nd.xy, depth: nd.depth ?? 3.3, color, ref: nd, kind: 'node'});
    }
    if (wells.length) this.addWells(this.g.network, wells);
    // футляры спецпереходов — прозрачная труба побольше вокруг рабочей
    const casings = [];
    for (const s of v.segs) if (s.special) {
      const P = s.pts.map((p, i) => [p[0], p[1], -(s.depth[i] + s.row.h / 2) * ex]);
      for (let i = 0; i + 1 < P.length; i++) casings.push({a: P[i], b: P[i + 1], r: s.row.h * 0.95 + 0.12, color: '#f8fafc'});
    }
    if (casings.length) {
      const cm = tubeMesh(casings, {radial: 14, material: this.material('standard', {color: 0xffffff, transparent: true, opacity: 0.22, depthWrite: false, roughness: 0.2}), overlap: true});
      cm.renderOrder = 18; cm.name = 'casing';
      this.g.network.add(cm);
    }
    // проблемы — красные метки в точке конфликта: шар, игла до земли и кольцо на земле, видны с любого масштаба
    const bad = v.crossings.filter(c => c.ok === false);
    if (bad.length) {
      const pts = bad.map(c => c.type === 'segment' ? [c.x, c.y, -(c.seg.d0 + c.seg.row.h / 2) * ex] : [c.x, c.y, -(c.hn + c.H / 2) * ex]);
      const sm = sphereMesh(pts.map(p => ({p: [p[0], p[1], p[2] + 1.2], r: 0.32, color: PALETTE.problem})), this.material('basic', {color: 0xffffff, transparent: true, opacity: 0.9, depthTest: false}), 14);
      sm.renderOrder = 40; sm.name = 'problems';
      this.g.network.add(sm);
      const needles = [];
      for (const p of pts) needles.push(p[0], p[1], p[2], p[0], p[1], 6);
      const nl = fatLines(needles, {width: 2.5, color: PALETTE.problem, opacity: 0.95});
      nl.renderOrder = 40; nl.name = 'problemNeedles';
      this.g.netLines.add(this.trackLines(nl));
      const rings = [];
      for (const p of pts) for (let k = 0; k < 24; k++) {
        const a0 = k / 24 * Math.PI * 2, a1 = (k + 1) / 24 * Math.PI * 2, R = 4;
        rings.push(p[0] + Math.cos(a0) * R, p[1] + Math.sin(a0) * R, 0.25, p[0] + Math.cos(a1) * R, p[1] + Math.sin(a1) * R, 0.25);
      }
      const rl = fatLines(rings, {width: 2, color: PALETTE.problem, opacity: 0.9});
      rl.name = 'problemRings';
      this.g.netLines.add(this.trackLines(rl));
    }
    this.rebuildPickGrid();
    this.applyOpts();
  }

  // «Что если»: обход пересечения — полупрозрачная зелёная труба на рекомендуемой глубине
  setDetour(pts, r) {
    let old = this.g.tools.getObjectByName('detour');
    if (old) { this.g.tools.remove(old); old.traverse(x => { x.geometry?.dispose(); x.material?.dispose?.(); if (x.isCSS2DObject) x.element.remove(); }); }
    if (!pts) { this.redraw(); return; }
    const ex = this.ex, P = pts.map(p => [p[0], p[1], -(p[2] + r) * ex]), pieces = [];
    for (let i = 0; i + 1 < P.length; i++) pieces.push({a: P[i], b: P[i + 1], r, color: '#4ade80'});
    const m = tubeMesh(pieces, {radial: 12, material: new THREE.MeshBasicMaterial({color: 0xffffff, transparent: true, opacity: 0.55, depthWrite: false}), overlap: true});
    m.name = 'detour'; m.renderOrder = 25;
    this.g.tools.add(m);
    this.redraw();
  }

  // Другой вариант поверх текущего — белым пунктиром, чтобы сравнить трассы на месте
  setGhost(v) {
    this.clear(this.g.ghost);
    this.ghost = v;
    if (!v) { this.redraw(); return; }
    const seg = [];
    for (const s of v.segs) {
      const P = s.pts.map((p, i) => [p[0], p[1], -(s.depth[i] + s.row.h / 2) * this.ex]);
      for (let i = 0; i + 1 < P.length; i++) seg.push(...P[i], ...P[i + 1]);
    }
    const ln = fatLines(seg, {width: 3, color: '#f1f5f9', opacity: 0.9, dashed: true});
    ln.renderOrder = 17;
    this.g.ghost.add(this.trackLines(ln));
    this.redraw();
  }

  setExaggeration(ex) {
    this.ex = ex;
    this.buildUnderground();
    this.onExChange?.();
  }

  // ---------- выделение трассы ----------
  highlight(route, focusSeg = null) {
    this.clear(this.g.overlay);
    this.route = route;
    if (!route) { this.redraw(); return; }
    const ex = this.ex, pieces = [], drops = [], segs = [];
    for (const s of route.segs) {
      const r = s.row.h / 2 * 1.9 + 0.08;
      const P = s.pts.map((p, i) => [p[0], p[1], -(s.depth[i] + s.row.h / 2) * ex]);
      for (let i = 0; i + 1 < P.length; i++) { pieces.push({a: P[i], b: P[i + 1], r, color: s === focusSeg ? '#ffffff' : PALETTE.selection}); segs.push(...P[i], ...P[i + 1]); }
    }
    const shell = tubeMesh(pieces, {radial: 14, material: this.material('basic', {color: 0xffffff, transparent: true, opacity: 0.38, depthWrite: false}), overlap: true});
    shell.renderOrder = 20;
    this.g.overlay.add(shell);
    const ln = fatLines(segs, {width: 4, color: PALETTE.selection, opacity: 0.95});
    this.g.overlay.add(this.trackLines(ln));
    // отвесы до земли через каждые ~25 м и на изломах; подписи глубины — в начале и там, где глубина меняется
    let next = 0, lastD = null, lastAt = -1e9, labels = 0;
    for (const s of route.segs) {
      for (let i = 0; i < s.pts.length; i++) {
        const at = s.s0 + s.lens[i], p = s.pts[i], z = -(s.depth[i]) * ex;
        if (at + 1e-6 >= next || i === 0 || i === s.pts.length - 1) { drops.push(p[0], p[1], 0.2, p[0], p[1], z); next = at + Math.max(25, route.len / 40); }
        const d = s.depth[i];
        if (labels < 40 && (lastD == null || Math.abs(d - lastD) >= 0.1) && at - lastAt > Math.max(15, route.len / 30)) {
          lastD = d; lastAt = at; labels++;
          const lb = this.label(`верх ${d.toFixed(2).replace('.', ',')} м`, 'lbl depth');
          lb.position.set(p[0], p[1], z / 2);
          this.g.overlay.add(lb);
        }
      }
    }
    const dl = fatLines(drops, {width: 1.2, color: '#fef08a', opacity: 0.8, dashed: true});
    this.g.overlay.add(this.trackLines(dl));
    // пересечения по трассе: кольца и подписи
    for (const s of route.segs) for (const c of s.cx) {
      const lb = this.label(`${UTILITIES[c.type]?.short || c.type}${c.ok === false ? ' ⚠' : ''} <small>${c.gap.toFixed(2).replace('.', ',')} м</small>`,
        'lbl cx ' + (c.ok === false ? 'bad' : c.ok ? 'ok' : 'mut'));
      lb.position.set(c.x, c.y, -(c.hn + c.H / 2) * ex);
      lb.userData.cx = c;
      this.g.overlay.add(lb);
    }
    // камеры на трассе — подписи с номерами, как на схеме сети
    for (const s of route.segs) {
      const nd = this.variant?.nodes.find(n => n.id === s.from && n.type !== 'technical_node');
      if (!nd) continue;
      const lb = this.label(nodeName(nd, this.model), 'lbl node');
      lb.position.set(nd.xy[0], nd.xy[1], 1.5);
      this.g.overlay.add(lb);
    }
    const end = route.segs[route.segs.length - 1], last = end.pts[end.pts.length - 1];
    const lb = this.label(`<b>${route.endName || nodeName(this.variant?.nodes.find(n => n.id === route.end) || {id: route.end}, this.model)}</b><small>${route.len.toFixed(0)} м от врезки</small>`, 'lbl end');
    lb.position.set(last[0], last[1], 4);
    this.g.overlay.add(lb);
    this.redraw();
  }
  overlayLines(seg, {color = PALETTE.selection, width = 4} = {}) {
    const l = fatLines(seg, {width, color, opacity: 0.95, depthTest: false});
    l.renderOrder = 30;
    this.g.overlay.add(this.trackLines(l));
    this.redraw();
  }
  label(html, cls = 'lbl') {
    const el = document.createElement('div');
    el.className = cls;
    el.innerHTML = html;
    const o = new CSS2DObject(el);
    o.center.set(0.5, 1.2);
    return o;
  }
  // Временные метки: точка на профиле, опорная точка вращения, линейка
  marker(name, pos, {color = PALETTE.selection, r = 1.2, ring = false} = {}) {
    let m = this.g.tools.getObjectByName(name);
    if (!pos) { if (m) { this.g.tools.remove(m); m.traverse(x => { x.geometry?.dispose(); x.material?.dispose(); }); } this.redraw(); return; }
    if (!m) {
      m = ring ? new THREE.Mesh(new THREE.RingGeometry(0.85, 1, 40), new THREE.MeshBasicMaterial({color, transparent: true, opacity: 0.9, depthTest: false, side: THREE.DoubleSide}))
        : new THREE.Mesh(new THREE.SphereGeometry(1, 16, 12), new THREE.MeshBasicMaterial({color, depthTest: false, transparent: true, opacity: 0.95}));
      m.name = name; m.renderOrder = 50;
      this.g.tools.add(m);
    }
    m.position.copy(pos);
    m.userData.marker = {r, ring};
    this.scaleMarker(m);
    this.redraw();
    return m;
  }
  // размер метки — по текущему расстоянию до камеры, пересчёт каждый кадр (иначе метка из ссылки остаётся огромной)
  scaleMarker(m) {
    const {r, ring} = m.userData.marker, dist = this.camera.position.distanceTo(m.position);
    m.scale.setScalar(ring ? dist * 0.03 : Math.min(Math.max(r * 0.35, dist * 0.008), r * 1.2));
    if (ring) m.quaternion.copy(this.camera.quaternion);
  }

  // ---------- отображение ----------
  applyOpts() {
    const o = this.opts, L = o.layers;
    const on = k => L[k] !== false;
    const gop = o.xray ? Math.min(o.groundOpacity, 0.15) : o.groundOpacity;
    if (this.groundMat) { this.groundMat.uniforms.uOpacity.value = gop; this.ground.visible = on('ground'); }
    for (const m of this.g.surface.children) { m.visible = on('p:' + m.userData.key); if (m.material.opacity !== undefined && m.material.transparent) m.material.opacity = Math.max(0.1, gop * 0.75); }
    const solid = this.g.buildings.getObjectByName('buildings'), flat = this.g.buildings.getObjectByName('buildingsFlat');
    if (solid) {
      solid.visible = on('buildings') && o.buildings === 'solid';
      // рентген — здания почти прозрачные; выбрана трасса — здания приглушены, чтобы трасса не пряталась за домом
      const op = o.xray ? 0.18 : o.dim ? 0.5 : 1;
      solid.traverse(x => { x.renderOrder = op < 1 ? 11 : 10; });
      this.bldMat.opacity = op;
      this.bldMat.transparent = op < 1;
      this.bldMat.depthWrite = op === 1;
      this.bldMat.needsUpdate = true;
    }
    if (flat) flat.visible = on('buildings') && o.buildings !== 'hidden';
    for (const m of [...this.g.utilities.children, ...this.g.utilLines.children]) m.visible = on(m.userData.key);
    this.g.existing.visible = on('n:heat_chamber');
    this.g.oks.visible = on('oks');
    this.g.netLines.getObjectByName('trace') && (this.g.netLines.getObjectByName('trace').visible = on('trace'));
    this.redraw();
  }

  // ---------- секущий бокс ----------
  setClipBox(box) {
    this.clipPlanes.length = 0;
    if (box) {
      const [x0, y0, z0, x1, y1, z1] = box;
      this.clipPlanes.push(new THREE.Plane(new THREE.Vector3(1, 0, 0), -x0), new THREE.Plane(new THREE.Vector3(-1, 0, 0), x1),
        new THREE.Plane(new THREE.Vector3(0, 1, 0), -y0), new THREE.Plane(new THREE.Vector3(0, -1, 0), y1),
        new THREE.Plane(new THREE.Vector3(0, 0, 1), -z0), new THREE.Plane(new THREE.Vector3(0, 0, -1), z1));
    }
    let box3 = this.scene.getObjectByName('clipBox');
    if (box3) { this.scene.remove(box3); box3.geometry.dispose(); box3.traverse(x => { if (x.isCSS2DObject) x.element.remove(); }); }
    if (box) {
      const [x0, y0, z0, x1, y1, z1] = box;
      const g = new THREE.EdgesGeometry(new THREE.BoxGeometry(x1 - x0, y1 - y0, z1 - z0));
      box3 = new THREE.LineSegments(g, new THREE.LineBasicMaterial({color: 0xfde047, transparent: true, opacity: 0.7}));
      box3.position.set((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2);
      box3.name = 'clipBox';
      // размер рамки подписан, как у Section Box: в плане и по глубине (в настоящих метрах)
      const lb = this.label(`срез ${Math.round(x1 - x0)} × ${Math.round(y1 - y0)} м · глубже ${(Math.max(0, -z0) / this.ex).toFixed(1).replace('.', ',')} м`, 'lbl clip');
      lb.position.set(0, 0, (z1 - z0) / 2);
      box3.add(lb);
      this.scene.add(box3);
    }
    this.scene.traverse(o => { if (o.material && !o.material.isLineMaterial && o.name !== 'clipBox') { o.material.clippingPlanes = this.clipPlanes; o.material.needsUpdate = true; } });
    this.redraw();
  }

  // ---------- выбор объектов ----------
  rebuildPickGrid() {
    const C = this.pickCell, grid = new Map();
    this.pickItems.forEach((it, i) => {
      const r = it.r + 2;
      const gx0 = Math.floor((Math.min(it.a[0], it.b[0]) - r) / C), gx1 = Math.floor((Math.max(it.a[0], it.b[0]) + r) / C);
      const gy0 = Math.floor((Math.min(it.a[1], it.b[1]) - r) / C), gy1 = Math.floor((Math.max(it.a[1], it.b[1]) + r) / C);
      for (let x = gx0; x <= gx1; x++) for (let y = gy0; y <= gy1; y++) { const k = x * 1000003 + y; let l = grid.get(k); if (!l) grid.set(k, l = []); l.push(i); }
    });
    this.pickGrid = grid;
    this.redraw();
  }
  visibleKind(it) {
    const L = this.opts.layers;
    if (it.kind === 'utility') return L['u:' + it.type] !== false;
    if (it.kind === 'chamber') return L['n:heat_chamber'] !== false;
    if (it.kind === 'oks') return L.oks !== false;
    return true;
  }
  inClip(p) {
    for (const pl of this.clipPlanes) if (pl.distanceToPoint(p) < 0) return false;
    return true;
  }
  /** Что под пикселем (x, y): {kind, ref, point, t} или null. Допуск — в пикселях, поэтому тонкие линии вдали тоже ловятся. */
  pick(x, y, ray, {px = 7} = {}) {
    const C = this.pickCell, h = this.host.clientHeight;
    const pxWorld = 2 * Math.tan(this.camera.fov * Math.PI / 360) / h;
    const o = ray.origin, d = ray.direction;
    const seen = new Set();
    let best = null;
    // над районом существующие сети не ловим: щелчок на обзоре должен попадать в новую сеть и здания, а не в случайную трубу
    const far = Math.abs(this.camera.position.z) > 700;
    const test = i => {
      if (seen.has(i)) return;
      seen.add(i);
      const it = this.pickItems[i];
      if (!this.visibleKind(it) || (far && it.kind === 'utility')) return;
      const r = closestRaySeg(o, d, it.a, it.b);
      if (!r || r.t <= 0) return;
      const tol = Math.max(it.r, px * pxWorld * r.t);
      if (r.dist > tol) return;
      const pt = new THREE.Vector3(o.x + d.x * r.t, o.y + d.y * r.t, o.z + d.z * r.t);
      if (this.clipPlanes.length && !this.inClip(pt)) return;
      // новая сеть важнее соседних коммуникаций на том же расстоянии
      const score = r.t * (it.kind === 'seg' ? 0.85 : it.kind === 'node' ? 0.8 : 1) + r.dist / Math.max(tol, 1e-6) * 0.5;
      if (!best || score < best.score) best = {kind: it.kind, ref: it.ref, type: it.type, point: pt, on: new THREE.Vector3(...r.on), t: r.t, score};
    };
    // идём по клеткам вдоль проекции луча на план
    const tMax = Math.min(60000, this.camera.far);
    const zMin = -40 * this.ex, zMax = 400;
    let t0 = 0, t1 = tMax;
    if (Math.abs(d.z) > 1e-9) {
      const ta = (zMin - o.z) / d.z, tb = (zMax - o.z) / d.z;
      t0 = Math.max(0, Math.min(ta, tb)); t1 = Math.min(tMax, Math.max(ta, tb));
    }
    if (t1 <= t0) return null;
    const lenXY = Math.hypot(d.x, d.y) * (t1 - t0);
    const steps = Math.min(4000, Math.ceil(lenXY / (C * 0.5)) + 1);
    for (let k = 0; k <= steps; k++) {
      const t = t0 + (t1 - t0) * k / steps;
      const gx = Math.floor((o.x + d.x * t) / C), gy = Math.floor((o.y + d.y * t) / C);
      for (let ix = -1; ix <= 1; ix++) for (let iy = -1; iy <= 1; iy++) for (const i of this.pickGrid.get((gx + ix) * 1000003 + gy + iy) || []) test(i);
      if (best && t > best.t * 1.3 + 30) break;
    }
    if (best) return best;
    // здания и поверхность: где луч входит в здание или касается земли
    const M = this.model;
    if (!M) return null;
    if (this.opts.buildings === 'solid' && this.opts.layers.buildings !== false) {
      for (const zz of [60, 40, 25, 15, 8, 3]) {
        if (Math.abs(d.z) < 1e-9) break;
        const t = (zz - o.z) / d.z;
        if (t <= 0) continue;
        const px_ = o.x + d.x * t, py = o.y + d.y * t;
        const b = M.zonesAt(px_, py).find(z => BUILDING_KEYS.has(z.type) && buildingHeight(z.item) >= zz);
        if (b) return {kind: 'surface', type: b.type, ref: b.item, point: new THREE.Vector3(px_, py, zz), t};
      }
    }
    if (Math.abs(d.z) > 1e-9) {
      const t = -o.z / d.z;
      if (t > 0) {
        const px_ = o.x + d.x * t, py = o.y + d.y * t, zs = M.zonesAt(px_, py).filter(z => this.opts.layers['p:' + z.type] !== false || BUILDING_KEYS.has(z.type));
        const order = ['oks_future', 'oks_existing', 'oks', 'road', 'tram_tracks', 'railway', 'metro', 'water', 'park', 'social_area', 'prohibited_site'];
        zs.sort((a, b) => order.indexOf(a.type) - order.indexOf(b.type));
        if (zs.length) return {kind: 'surface', type: zs[0].type, ref: zs[0].item, point: new THREE.Vector3(px_, py, 0), t};
      }
    }
    return null;
  }

  // Насколько точка p заходит внутрь трубы или колодца (0 — снаружи): чтобы камера из ссылки не встала внутри
  insideAt(p) {
    const C = this.pickCell, gx = Math.floor(p.x / C), gy = Math.floor(p.y / C);
    let worst = 0;
    for (let ix = -1; ix <= 1; ix++) for (let iy = -1; iy <= 1; iy++) for (const i of this.pickGrid.get((gx + ix) * 1000003 + gy + iy) || []) {
      const it = this.pickItems[i];
      if (it.kind === 'oks' || it.kind === 'source') continue;
      const ux = it.b[0] - it.a[0], uy = it.b[1] - it.a[1], uz = it.b[2] - it.a[2], L2 = ux * ux + uy * uy + uz * uz;
      const t = L2 > 0 ? Math.max(0, Math.min(1, ((p.x - it.a[0]) * ux + (p.y - it.a[1]) * uy + (p.z - it.a[2]) * uz) / L2)) : 0;
      const d = Math.hypot(p.x - it.a[0] - ux * t, p.y - it.a[1] - uy * t, p.z - it.a[2] - uz * t);
      worst = Math.max(worst, it.r + 0.4 - d);
    }
    return worst > 0 ? worst : 0;
  }

  async screenshot() {
    this.render();
    return new Promise(res => this.renderer.domElement.toBlob(res, 'image/png'));
  }
}

// Ближайшие точки луча o + t·d и отрезка ab: {t, dist}
function closestRaySeg(o, d, a, b) {
  const ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
  const wx = o.x - a[0], wy = o.y - a[1], wz = o.z - a[2];
  const A = d.x * d.x + d.y * d.y + d.z * d.z, B = d.x * ux + d.y * uy + d.z * uz, Cc = ux * ux + uy * uy + uz * uz;
  const Dd = d.x * wx + d.y * wy + d.z * wz, E = ux * wx + uy * wy + uz * wz;
  const den = A * Cc - B * B;
  let s, t;
  if (Cc < 1e-12) { s = 0; t = -Dd / A; }
  else if (den < 1e-12) { s = 0; t = -Dd / A; }
  else { t = (B * E - Cc * Dd) / den; s = (A * E - B * Dd) / den; }
  s = Math.max(0, Math.min(1, s));
  // после зажима s пересчитываем t
  const px = a[0] + ux * s, py = a[1] + uy * s, pz = a[2] + uz * s;
  t = ((px - o.x) * d.x + (py - o.y) * d.y + (pz - o.z) * d.z) / A;
  const qx = o.x + d.x * t - px, qy = o.y + d.y * t - py, qz = o.z + d.z * t - pz;
  return {t, dist: Math.sqrt(qx * qx + qy * qy + qz * qz), on: [px, py, pz]};
}
