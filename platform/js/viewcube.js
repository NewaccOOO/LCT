import * as THREE from 'three';

const FACES = [
  {n: [0, 0, 1], up: [0, 1, 0], t: 'ВЕРХ'}, {n: [0, 0, -1], up: [0, -1, 0], t: 'НИЗ'},
  {n: [0, 1, 0], up: [0, 0, 1], t: 'СЕВЕР'}, {n: [0, -1, 0], up: [0, 0, 1], t: 'ЮГ'},
  {n: [1, 0, 0], up: [0, 0, 1], t: 'ВОСТОК'}, {n: [-1, 0, 0], up: [0, 0, 1], t: 'ЗАПАД'},
];
const NAMES = {'0,0,1': 'Сверху', '0,0,-1': 'Снизу', '0,1,0': 'С севера', '0,-1,0': 'С юга', '1,0,0': 'С востока', '-1,0,0': 'С запада'};

function faceTexture(text, hover = false) {
  const c = document.createElement('canvas');
  c.width = c.height = 256;
  const g = c.getContext('2d');
  g.fillStyle = hover ? '#196dff' : '#ffffff';
  g.fillRect(0, 0, 256, 256);
  g.strokeStyle = hover ? '#4183ff' : '#d9d9d9';
  g.lineWidth = 10;
  g.strokeRect(5, 5, 246, 246);
  g.fillStyle = hover ? '#ffffff' : '#4d4d4d';
  g.font = `500 ${text.length > 5 ? 46 : 58}px "Helvetica Neue", Arial, sans-serif`;
  g.textAlign = 'center';
  g.textBaseline = 'middle';
  g.fillText(text, 128, 132);
  const t = new THREE.CanvasTexture(c);
  t.colorSpace = THREE.SRGBColorSpace;
  t.anisotropy = 4;
  return t;
}
function letterTexture(ch, color) {
  const c = document.createElement('canvas');
  c.width = c.height = 128;
  const g = c.getContext('2d');
  g.fillStyle = color;
  g.font = 'bold 92px system-ui, sans-serif';
  g.textAlign = 'center'; g.textBaseline = 'middle';
  g.fillText(ch, 64, 70);
  const t = new THREE.CanvasTexture(c);
  t.colorSpace = THREE.SRGBColorSpace;
  return t;
}

export class ViewCube {
  constructor(host, mainCamera, hooks) {
    this.main = mainCamera;
    this.hooks = hooks;
    const size = 128;
    this.renderer = new THREE.WebGLRenderer({antialias: true, alpha: true});
    this.renderer.setPixelRatio(Math.min(devicePixelRatio || 1, 2));
    this.renderer.setSize(size, size);
    this.renderer.domElement.className = 'viewcube-canvas';
    this.renderer.domElement.setAttribute('role', 'img');
    this.renderer.domElement.setAttribute('aria-label', 'Куб видов: щёлкните грань, ребро или угол, чтобы посмотреть с этой стороны');
    host.appendChild(this.renderer.domElement);
    this.scene = new THREE.Scene();
    this.camera = new THREE.PerspectiveCamera(30, 1, 0.1, 100);
    this.scene.add(new THREE.AmbientLight(0xffffff, 2.2));
    this.faces = [];
    for (const f of FACES) {
      const n = new THREE.Vector3(...f.n), up = new THREE.Vector3(...f.up), right = up.clone().cross(n);
      const mat = new THREE.MeshBasicMaterial({map: faceTexture(f.t)});
      const m = new THREE.Mesh(new THREE.PlaneGeometry(2, 2), mat);
      m.matrixAutoUpdate = false;
      m.matrix.makeBasis(right, up, n).setPosition(n);
      m.userData = {f, base: mat.map, hover: null};
      this.faces.push(m);
      this.scene.add(m);
    }
    const edges = new THREE.LineSegments(new THREE.EdgesGeometry(new THREE.BoxGeometry(2.002, 2.002, 2.002)), new THREE.LineBasicMaterial({color: 0xbdbdbd}));
    this.scene.add(edges);
    const ring = new THREE.Mesh(new THREE.RingGeometry(1.7, 1.95, 64), new THREE.MeshBasicMaterial({color: 0x9e9e9e, transparent: true, opacity: 0.85, side: THREE.DoubleSide}));
    ring.position.z = -1.1;
    this.scene.add(ring);
    this.ring = ring;
    this.letters = [];
    for (const [ch, x, y, color] of [['С', 0, 1, '#ff4433'], ['В', 1, 0, '#878787'], ['Ю', 0, -1, '#878787'], ['З', -1, 0, '#878787']]) {
      const m = new THREE.Mesh(new THREE.PlaneGeometry(0.62, 0.62), new THREE.MeshBasicMaterial({map: letterTexture(ch, color), transparent: true, side: THREE.DoubleSide, depthTest: false}));
      m.position.set(x * 1.82, y * 1.82, -1.1);
      m.userData.dir = [x, y];
      m.renderOrder = 5;
      this.letters.push(m);
      this.scene.add(m);
    }
    this.hot = new THREE.Mesh(new THREE.SphereGeometry(0.2, 16, 12), new THREE.MeshBasicMaterial({color: 0x196dff}));
    this.hot.visible = false;
    this.scene.add(this.hot);
    this.bind();
    this.update();
  }

  update() {
    this.camera.quaternion.copy(this.main.quaternion);
    this.camera.position.set(0, 0, 10.2).applyQuaternion(this.main.quaternion);
    this.camera.updateMatrixWorld();
    for (const l of this.letters) l.quaternion.copy(this.camera.quaternion);
    this.renderer.render(this.scene, this.camera);
  }

  region(e) {
    const r = this.renderer.domElement.getBoundingClientRect();
    const ndc = new THREE.Vector2((e.clientX - r.left) / r.width * 2 - 1, -((e.clientY - r.top) / r.height) * 2 + 1);
    const rc = new THREE.Raycaster();
    rc.setFromCamera(ndc, this.camera);
    const hl = rc.intersectObjects(this.letters)[0];
    if (hl) return {letter: hl.object};
    const hit = rc.intersectObjects(this.faces)[0];
    if (!hit) {
      const hr = rc.intersectObject(this.ring)[0];
      return hr ? {ring: true} : null;
    }
    const p = hit.point, d = [0, 0, 0];
    for (let i = 0; i < 3; i++) { const v = p.getComponent(i); if (Math.abs(v) > 0.55) d[i] = Math.sign(v); }
    return {dir: d};
  }

  bind() {
    const el = this.renderer.domElement;
    let drag = null;
    const setHover = reg => {
      for (const f of this.faces) if (f.userData.hover) { f.material.map = f.userData.base; f.material.needsUpdate = true; }
      this.hot.visible = false;
      el.title = '';
      el.style.cursor = reg ? 'pointer' : 'default';
      if (!reg) { this.update(); return; }
      if (reg.letter) el.title = reg.letter.userData.dir[1] === 1 ? 'Повернуть на север' : 'Смотреть в эту сторону';
      if (reg.ring) el.title = 'Тащите кольцо, чтобы повернуть';
      if (reg.dir) {
        const k = reg.dir.join(','), nz = reg.dir.filter(Boolean).length;
        el.title = nz === 1 ? NAMES[k] : 'Вид под углом 45°';
        if (nz === 1) {
          const f = this.faces.find(f => f.userData.f.n.join(',') === k);
          f.userData.hover ||= faceTexture(f.userData.f.t, true);
          f.material.map = f.userData.hover; f.material.needsUpdate = true;
        } else { this.hot.position.set(...reg.dir); this.hot.visible = true; }
      }
      this.update();
    };
    el.addEventListener('pointermove', e => {
      if (drag) {
        const dx = e.clientX - drag.x, dy = e.clientY - drag.y;
        if (Math.hypot(e.clientX - drag.x0, e.clientY - drag.y0) > 3) drag.moved = true;
        if (drag.moved) this.hooks.orbit(dx, dy);
        drag.x = e.clientX; drag.y = e.clientY;
        return;
      }
      setHover(this.region(e));
    });
    el.addEventListener('pointerleave', () => { if (!drag) setHover(null); });
    el.addEventListener('pointerdown', e => { el.setPointerCapture(e.pointerId); drag = {x: e.clientX, y: e.clientY, x0: e.clientX, y0: e.clientY, moved: false}; });
    el.addEventListener('pointerup', e => {
      const d = drag;
      drag = null;
      if (!d || d.moved) return;
      const reg = this.region(e);
      if (!reg) return;
      if (reg.letter) { const [x, y] = reg.letter.userData.dir; this.hooks.heading(Math.atan2(x, y)); return; }
      if (reg.dir) { const nz = reg.dir.filter(Boolean).length; this.hooks.orient(new THREE.Vector3(...reg.dir).normalize(), nz === 1 ? NAMES[reg.dir.join(',')] : 'Вид под углом'); }
    });
    el.addEventListener('contextmenu', e => e.preventDefault());
  }
}
