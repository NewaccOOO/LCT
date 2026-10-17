// Общие мелочи: форматирование чисел по-русски, экранирование, DOM

export const $ = (s, root = document) => root.querySelector(s);
export const $$ = (s, root = document) => [...root.querySelectorAll(s)];

export const fmt = (n, d = 0) => n == null || Number.isNaN(n) ? '—'
  : Number(n).toLocaleString('ru-RU', {maximumFractionDigits: d, minimumFractionDigits: d});
export const rub = n => n == null ? '—' : Math.abs(n) >= 1e9 ? fmt(n / 1e9, 2) + ' млрд ₽'
  : Math.abs(n) >= 1e6 ? fmt(n / 1e6, 1) + ' млн ₽' : fmt(n / 1e3) + ' тыс. ₽';
export const meters = m => m == null ? '—' : Math.abs(m) >= 1000 ? fmt(m / 1000, 2) + ' км' : fmt(m, m < 10 ? 2 : 0) + ' м';
export const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]));
export const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
export const hexRgb = h => [parseInt(h.slice(1, 3), 16) / 255, parseInt(h.slice(3, 5), 16) / 255, parseInt(h.slice(5, 7), 16) / 255];
export const tick = () => new Promise(r => setTimeout(r, 0));

export const store = {
  get(k, d) { try { const v = localStorage.getItem('hn3d.' + k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } },
  set(k, v) { try { localStorage.setItem('hn3d.' + k, JSON.stringify(v)); } catch (e) { /* приватный режим */ } },
};

// Склонение: plural(5, 'труба', 'трубы', 'труб')
export function plural(n, one, few, many) {
  const a = Math.abs(n) % 100, b = a % 10;
  if (a > 10 && a < 20) return many;
  if (b > 1 && b < 5) return few;
  if (b === 1) return one;
  return many;
}

export function toast(text, kind = 'info', ms = 3500) {
  const box = $('#toasts');
  if (!box) return;
  const el = document.createElement('div');
  el.className = 'toast ' + kind;
  el.setAttribute('role', kind === 'error' ? 'alert' : 'status');
  el.innerHTML = text;
  box.appendChild(el);
  setTimeout(() => { el.classList.add('out'); setTimeout(() => el.remove(), 300); }, ms);
}
