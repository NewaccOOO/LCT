#!/usr/bin/env python3
"""Офлайн-карта результата для защиты: один HTML без интернета и зависимостей.

Запуск из корня:
    python3 scripts/visualize.py IN.geojson OUT.geojson [--criteria OUT.criteria.json] [--html OUT.html]

Показывает вход (здания и ограничения, существующую сеть, камеры, источник, точки подключения) и каждый вариант
результата: новые участки с диаметром и расходом, спецпереходы, врезки, камеры, технические узлы, реконструкцию.
Справа — сводка варианта, дополнительные критерии из файла CLI и свойства объекта, по которому щёлкнули.
Колёсико — масштаб, перетаскивание — сдвиг. Файл читается целиком, поэтому скрипт для наборов размером с район,
а не для файла на 3 ГБ.
"""
import argparse
import html
import json
import math
from pathlib import Path

EARTH_M = 6371008.8


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)["features"]


def points(geometry):
    kind, coords = geometry["type"], geometry["coordinates"]
    if kind == "Point":
        return [[coords]]
    if kind in ("LineString", "MultiPoint"):
        return [coords]
    if kind in ("Polygon", "MultiLineString"):
        return coords
    if kind == "MultiPolygon":
        return [ring for polygon in coords for ring in polygon]
    return []


def project(features_in, features_out):
    """Равнопромежуточная проекция вокруг центра набора: для карты района точности хватает."""
    lons, lats = [], []
    for f in features_in + features_out:
        if f.get("geometry"):
            for part in points(f["geometry"]):
                for lon, lat, *_ in part:
                    lons.append(lon)
                    lats.append(lat)
    lon0, lat0 = sum(lons) / len(lons), sum(lats) / len(lats)
    k = math.cos(math.radians(lat0))

    def xy(lon, lat):
        return round(math.radians(lon - lon0) * EARTH_M * k, 2), round(-math.radians(lat - lat0) * EARTH_M, 2)

    def convert(f):
        g = f.get("geometry")
        parts = [[xy(p[0], p[1]) for p in part] for part in points(g)] if g else []
        return {"type": g["type"] if g else None, "parts": parts, "props": f.get("properties", {})}

    return [convert(f) for f in features_in], [convert(f) for f in features_out]


def main():
    parser = argparse.ArgumentParser(description="Офлайн HTML-карта входа и результата трассировки")
    parser.add_argument("input")
    parser.add_argument("output")
    parser.add_argument("--criteria", help="файл дополнительных критериев от CLI; по умолчанию ищется рядом с выходом")
    parser.add_argument("--html", help="куда записать карту; по умолчанию рядом с выходом, расширение .html")
    args = parser.parse_args()

    out_path = Path(args.output)
    stem = out_path.name[:-len(".geojson")] if out_path.name.endswith(".geojson") else out_path.name
    criteria_path = Path(args.criteria) if args.criteria else out_path.with_name(stem + ".criteria.json")
    criteria = json.loads(criteria_path.read_text(encoding="utf-8")) if criteria_path.exists() else []
    html_path = Path(args.html) if args.html else out_path.with_name(stem + ".html")

    inp, out = project(load(args.input), load(args.output))
    data = {"input": inp, "output": out, "criteria": criteria,
            "title": f"{Path(args.input).name} → {out_path.name}"}
    page = TEMPLATE.replace("__TITLE__", html.escape(data["title"])).replace(
        "__DATA__", json.dumps(data, ensure_ascii=False).replace("</", "<\\/"))
    html_path.write_text(page, encoding="utf-8")
    print(html_path)


TEMPLATE = r"""<!doctype html>
<html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Трассировка — __TITLE__</title>
<style>
:root{--bg:#0b1220;--card:#0f172a;--line:#1e293b;--text:#e2e8f0;--muted:#94a3b8;--blue:#38bdf8;--orange:#fb923c;--rose:#f43f5e;--amber:#b45309}
*{box-sizing:border-box}body{margin:0;font:14px -apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;background:var(--bg);color:var(--text);height:100vh;display:flex;flex-direction:column}
header{padding:10px 16px;display:flex;gap:10px;align-items:center;flex-wrap:wrap;border-bottom:1px solid var(--line)}
header h1{font-size:15px;margin:0 12px 0 0;font-weight:600}
.tab{background:var(--card);color:var(--text);border:1px solid var(--line);border-radius:8px;padding:6px 12px;cursor:pointer;font:inherit}
.tab.on{border-color:var(--blue);box-shadow:inset 0 0 0 1px var(--blue)}
.tab small{color:var(--muted);margin-left:6px}
main{flex:1;display:flex;min-height:0}
#map{flex:1;min-width:0;position:relative}
svg{width:100%;height:100%;display:block;cursor:grab;background:var(--bg)}
svg.drag{cursor:grabbing}
aside{width:380px;max-width:45vw;border-left:1px solid var(--line);overflow:auto;padding:12px 16px}
h2{font-size:13px;text-transform:uppercase;letter-spacing:.04em;color:var(--muted);margin:16px 0 8px}
table{width:100%;border-collapse:collapse;font-size:13px}td{padding:3px 0;border-bottom:1px solid var(--line);vertical-align:top}td:last-child{text-align:right;white-space:nowrap;padding-left:12px;font-variant-numeric:tabular-nums}
.layers label{display:inline-flex;gap:6px;align-items:center;margin:0 12px 6px 0;font-size:13px;color:var(--muted)}
.legend{position:absolute;left:12px;bottom:12px;background:rgba(15,23,42,.92);border:1px solid var(--line);border-radius:10px;padding:8px 12px;font-size:12px;color:var(--muted);display:flex;flex-wrap:wrap;gap:6px 14px;max-width:calc(100% - 24px)}
.legend i{display:inline-block;width:18px;height:4px;border-radius:2px;margin-right:6px;vertical-align:middle}
.hint{color:var(--muted);font-size:12px}
.sel{outline:none}
</style></head><body>
<header><h1>Трассировка тепловых сетей</h1><span class="hint">__TITLE__</span><span id="tabs"></span></header>
<main><div id="map"><svg id="svg"></svg>
<div class="legend">
<span><i style="background:#38bdf8"></i>новая сеть, толщина по Ду</span>
<span><i style="background:#fb923c"></i>спецпереход</span>
<span><i style="background:#f43f5e;height:8px;opacity:.6"></i>реконструкция</span>
<span><i style="background:#94a3b8"></i>существующая сеть</span>
<span>○ врезка · ■ камера · ◦ техузел · ● точка подключения · ◉ источник</span>
</div></div>
<aside>
<div class="layers">
<label><input type="checkbox" data-layer="restrictions" checked>ограничения</label>
<label><input type="checkbox" data-layer="existing" checked>существующая сеть</label>
<label><input type="checkbox" data-layer="recon" checked>реконструкция</label>
<label><input type="checkbox" data-layer="labels">подписи Ду и расхода</label>
</div>
<h2>Сводка варианта</h2><table id="summary"></table>
<h2>Дополнительные критерии</h2><table id="criteria"></table>
<h2>Выбранный объект</h2><div id="details" class="hint">Щёлкните по участку, врезке, камере или ограничению.</div>
</aside></main>
<script>
const DATA = __DATA__;
const NS = "http://www.w3.org/2000/svg";
const svg = document.getElementById("svg");
const FILL = {oks:"#334155", oks_existing:"#334155", park:"#14532d", social_area:"#4c1d95", prohibited_site:"#7f1d1d",
  water:"#1e3a8a", road:"#1f2937", tram_tracks:"#3f2d1a", railway:"#3f2d1a", metro:"#3b0764"};
const LINE = {gas_pipeline:"#eab308", power_cable:"#a855f7", water_supply:"#06b6d4", sewer:"#78716c"};
const NAMES = {oks_existing:"существующий ОКС", park:"парк", social_area:"социальный объект", prohibited_site:"запретная территория",
  water:"водный объект", road:"дорога", tram_tracks:"трамвайные пути", gas_pipeline:"газопровод", power_cable:"силовой кабель",
  heat_network:"существующая теплосеть", metro:"метро", railway:"железная дорога", water_supply:"водопровод", sewer:"канализация",
  power_line_support:"опора ЛЭП", source:"источник", heat_chamber:"тепловая камера", oks_future:"перспективный ОКС",
  oks_connection_point:"точка подключения", restriction:"ограничение"};
const name = k => NAMES[k] || k;
const SUMMARY = [["calculated_cost","Итоговая стоимость, руб."],["construction_cost","Новые участки"],
  ["chamber_construction_cost","Новые камеры"],["tie_in_cost","Врезки"],["reconstruction_cost","Реконструкция участков"],
  ["chamber_reconstruction_cost","Реконструкция камер"],["unconnected_penalty","Штраф за неподключённые"],
  ["new_network_length","Новая сеть, м"],["reconstruction_length","Реконструкция, м"],["length","Длина всего, м"],
  ["score","S, меньше — лучше"],["unconnected_oks_ids","Неподключённые ОКС"]];
const CRITERIA = [["connected_oks","Подключено ОКС"],["connected_flow_tph","Подключённый расход, т/ч"],["tie_ins","Врезок"],
  ["new_chambers","Новых камер"],["technical_nodes","Технических узлов"],["chamber_reconstructions","Реконструкций камер"],
  ["special_segments","Спецучастков"],["special_length_m","Длина спецпереходов, м"],["crossed_objects","Пересечено объектов"],
  ["turns","Поворотов"],["nonstandard_turns","Нестандартных углов"],["surcharge_cost","Надбавка за спецпереходы и углы, руб."],
  ["reconstruction_share","Доля реконструкции в длине"],["cost_per_oks","Стоимость на один ОКС, руб."],
  ["cost_per_tph","Стоимость на 1 т/ч, руб."]];
const fmt = v => Array.isArray(v) ? (v.length ? v.join(", ") : "нет")
  : (v && typeof v === "object") ? (Object.keys(v).length ? Object.entries(v).map(([k,n]) => name(k) + ": " + n).join("<br>") : "нет")
  : typeof v === "number" ? v.toLocaleString("ru-RU", {maximumFractionDigits: 3}) : (v === null || v === undefined ? "—" : String(v));
const el = (name, attrs, parent) => { const e = document.createElementNS(NS, name); for (const k in attrs) e.setAttribute(k, attrs[k]); if (parent) parent.appendChild(e); return e; };
const d = parts => parts.map(p => "M" + p.map(q => q[0] + "," + q[1]).join("L")).join("");
const layers = {}; ["restrictions","existing","points","variants","labels"].forEach(n => layers[n] = el("g", {}, svg));

// границы и вьюбокс
let minX=Infinity,minY=Infinity,maxX=-Infinity,maxY=-Infinity;
for (const f of DATA.input.concat(DATA.output)) for (const p of f.parts) for (const [x,y] of p) { minX=Math.min(minX,x);minY=Math.min(minY,y);maxX=Math.max(maxX,x);maxY=Math.max(maxY,y); }
const pad = Math.max(maxX-minX, maxY-minY) * .05 + 20;
let vb = [minX-pad, minY-pad, maxX-minX+2*pad, maxY-minY+2*pad];
const scale = () => Math.max(vb[2] / svg.clientWidth, vb[3] / svg.clientHeight);
function applyVb(){ svg.setAttribute("viewBox", vb.join(" ")); const s = scale();
  svg.querySelectorAll("[data-w]").forEach(e => e.setAttribute("stroke-width", e.dataset.w * s));
  svg.querySelectorAll("[data-r]").forEach(e => e.setAttribute("r", e.dataset.r * s));
  svg.querySelectorAll("[data-size]").forEach(e => { const z = e.dataset.size * s; e.setAttribute("width", z); e.setAttribute("height", z); e.setAttribute("x", e.dataset.cx - z/2); e.setAttribute("y", e.dataset.cy - z/2); });
  // подпись видна, только если участок на экране длиннее подписи, иначе при общем виде они сливаются
  svg.querySelectorAll("text").forEach(e => { e.setAttribute("font-size", 11 * s); e.setAttribute("stroke-width", 3 * s);
    e.style.display = e.dataset.len && e.dataset.len / s < 110 ? "none" : ""; }); }

function select(props){ const box = document.getElementById("details"); box.className = "";
  box.innerHTML = "<table>" + Object.entries(props).map(([k,v]) => "<tr><td>" + k + "</td><td>" + (k.endsWith("_type") ? name(v) : fmt(v)) + "</td></tr>").join("") + "</table>"; }

// вход
for (const f of DATA.input) {
  const t = f.props.object_type, r = f.props.restriction_type;
  if (t === "restriction" || t === "oks_existing" || t === "oks_future") {
    if (f.type && f.type.includes("Polygon")) { const e = el("path", {d: d(f.parts) + "Z", fill: FILL[r] || FILL[t] || "#334155", stroke: "#0b1220", "data-w": .6, "fill-rule": "evenodd"}, layers.restrictions); e.onclick = () => select(f.props); }
    else if (f.type && f.type.includes("Line")) { const e = el("path", {d: d(f.parts), fill: "none", stroke: LINE[r] || "#64748b", "data-w": 2, "stroke-dasharray": "6 4"}, layers.restrictions); e.onclick = () => select(f.props); }
  } else if (t === "heat_network") { const e = el("path", {d: d(f.parts), fill: "none", stroke: "#94a3b8", "data-w": 3.5, "stroke-linecap": "round", opacity: .85}, layers.existing); e.onclick = () => select(f.props); }
  else if (t === "heat_chamber") { const [x,y] = f.parts[0][0]; const e = el("rect", {"data-cx": x, "data-cy": y, "data-size": 8, fill: "#0b1220", stroke: "#94a3b8", "data-w": 2}, layers.existing); e.onclick = () => select(f.props); }
  else if (t === "source") { const [x,y] = f.parts[0][0]; const e = el("circle", {cx: x, cy: y, "data-r": 9, fill: "#b45309", stroke: "#fde68a", "data-w": 2}, layers.points); e.onclick = () => select(f.props); }
  else if (t === "oks_connection_point") { const [x,y] = f.parts[0][0]; const e = el("circle", {cx: x, cy: y, "data-r": 5, fill: "#f8fafc", stroke: "#0b1220", "data-w": 1.5}, layers.points); e.onclick = () => select(f.props); }
}

// варианты
const variants = [...new Set(DATA.output.map(f => f.props.variant_id))].sort();
const groups = {};
for (const v of variants) {
  const g = el("g", {}, layers.variants), labels = el("g", {}, layers.labels); groups[v] = [g, labels];
  const recon = el("g", {"class": "recon"}, g), net = el("g", {}, g), nodes = el("g", {}, g);
  for (const f of DATA.output.filter(f => f.props.variant_id === v)) {
    const p = f.props, t = p.object_type;
    if (t === "heat_network_reconstruction") { const e = el("path", {d: d(f.parts), fill: "none", stroke: "#f43f5e", "data-w": 9, opacity: .55, "stroke-linecap": "round"}, recon); e.onclick = () => select(p); }
    else if (t === "heat_network") {
      const e = el("path", {d: d(f.parts), fill: "none", stroke: p.laying_method === "special" ? "#fb923c" : "#38bdf8", "data-w": 2 + Math.min(p.diameter, 500) / 120, "stroke-linecap": "round", "stroke-linejoin": "round"}, net); e.onclick = () => select(p);
      const pts = f.parts[0], mid = pts[Math.floor((pts.length - 1) / 2)], nxt = pts[Math.floor((pts.length - 1) / 2) + 1] || mid;
      const label = el("text", {x: (mid[0] + nxt[0]) / 2, y: (mid[1] + nxt[1]) / 2, fill: "#e2e8f0", "text-anchor": "middle", "paint-order": "stroke", stroke: "#0b1220", "data-len": p.length}, labels);
      label.textContent = "Ду " + p.diameter + " · " + fmt(p.flow_tph) + " т/ч";
    }
    else if (t === "tie_in") { const [x,y] = f.parts[0][0]; const e = el("circle", {cx: x, cy: y, "data-r": 8, fill: "none", stroke: "#38bdf8", "data-w": 3}, nodes); e.onclick = () => select(p); }
    else if (t === "heat_chamber" || t === "heat_chamber_reconstruction") { const [x,y] = f.parts[0][0]; const e = el("rect", {"data-cx": x, "data-cy": y, "data-size": t === "heat_chamber" ? 8 : 14, fill: t === "heat_chamber" ? "#e0f2fe" : "none", stroke: t === "heat_chamber" ? "none" : "#f43f5e", "data-w": 2}, nodes); e.onclick = () => select(p); }
    else if (t === "technical_node") { const [x,y] = f.parts[0][0]; const e = el("circle", {cx: x, cy: y, "data-r": 3.5, fill: "#0b1220", stroke: "#38bdf8", "data-w": 1.5}, nodes); e.onclick = () => select(p); }
  }
}
// невидимая полоса 14 px вокруг каждой линии, чтобы по тонкому участку было легко попасть щелчком
svg.querySelectorAll('path[fill="none"]').forEach(e => { const h = el("path", {d: e.getAttribute("d"), fill: "none",
  stroke: "transparent", "data-w": 14, "pointer-events": "stroke"}); e.after(h); h.onclick = e.onclick; });
layers.variants.parentNode.appendChild(layers.points);
layers.labels.parentNode.appendChild(layers.labels);

function show(v){
  for (const k in groups) { groups[k][0].style.display = k === v ? "" : "none"; groups[k][1].style.display = k === v && document.querySelector("[data-layer=labels]").checked ? "" : "none"; }
  document.querySelectorAll(".tab").forEach(b => b.classList.toggle("on", b.dataset.v === v));
  const s = (DATA.output.find(f => f.props.object_type === "variant_summary" && f.props.variant_id === v) || {props: {}}).props;
  document.getElementById("summary").innerHTML = SUMMARY.map(([k, n]) => "<tr><td>" + n + "</td><td>" + fmt(s[k]) + "</td></tr>").join("");
  const c = (DATA.criteria.find(r => String(r.variant_id) === v) || {}).criteria;
  document.getElementById("criteria").innerHTML = c ? CRITERIA.map(([k, n]) => "<tr><td>" + n + "</td><td>" + fmt(c[k]) + "</td></tr>").join("")
    : "<tr><td class=hint>Нет файла *.criteria.json рядом с выходом: его пишет CLI.</td></tr>";
  document.querySelectorAll("[data-layer=recon]").forEach(b => svg.querySelectorAll(".recon").forEach(g => g.style.display = b.checked ? "" : "none"));
}
const tabs = document.getElementById("tabs");
for (const v of variants) {
  const s = (DATA.output.find(f => f.props.object_type === "variant_summary" && f.props.variant_id === v) || {props: {}}).props;
  const b = document.createElement("button"); b.className = "tab"; b.dataset.v = v;
  b.innerHTML = "Вариант " + v + "<small>S " + fmt(s.score) + " · " + fmt(Math.round((s.calculated_cost || 0) / 1e5) / 10) + " млн</small>";
  b.onclick = () => show(v); tabs.appendChild(b);
}
document.querySelectorAll("[data-layer]").forEach(box => box.onchange = () => {
  const name = box.dataset.layer, on = box.checked;
  if (name === "recon" || name === "labels") show(document.querySelector(".tab.on").dataset.v);
  else layers[name].style.display = on ? "" : "none";
});

// масштаб и сдвиг
svg.addEventListener("wheel", e => { e.preventDefault(); const r = svg.getBoundingClientRect(), k = e.deltaY > 0 ? 1.15 : 1 / 1.15;
  const mx = vb[0] + (e.clientX - r.left) / r.width * vb[2], my = vb[1] + (e.clientY - r.top) / r.height * vb[3];
  vb = [mx - (mx - vb[0]) * k, my - (my - vb[1]) * k, vb[2] * k, vb[3] * k]; applyVb(); }, {passive: false});
let drag = null;
svg.addEventListener("mousedown", e => { drag = [e.clientX, e.clientY, vb[0], vb[1]]; svg.classList.add("drag"); });
window.addEventListener("mouseup", () => { drag = null; svg.classList.remove("drag"); });
window.addEventListener("mousemove", e => { if (!drag) return; const r = svg.getBoundingClientRect();
  vb[0] = drag[2] - (e.clientX - drag[0]) / r.width * vb[2]; vb[1] = drag[3] - (e.clientY - drag[1]) / r.height * vb[3]; applyVb(); });
window.addEventListener("resize", applyVb);
applyVb(); show(variants[0]);
</script></body></html>
"""

if __name__ == "__main__":
    main()
