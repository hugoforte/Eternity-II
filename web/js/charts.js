/* Canvas chart primitives for the Insights page.
 *
 * Each finding carries its own ``visual`` object of the shape
 *
 *     { kind: 'diverging_bars' | 'histogram' | 'line' | ... , ...data }
 *
 * ``renderChart(kind, container, data)`` here dispatches to the right drawing
 * function.  Everything is canvas-based so it fits a single finding card
 * without any DOM-rendering overhead, and reuses the colour language of the
 * board view for continuity.
 */

import { PALETTE } from './palette.js';

const CSS = getComputedStyle(document.documentElement);
const INK       = CSS.getPropertyValue('--ink').trim()        || '#f2efff';
const INK_DIM   = CSS.getPropertyValue('--ink-dim').trim()    || '#b5aee0';
const INK_MUTE  = CSS.getPropertyValue('--ink-mute').trim()   || '#8b83bb';
const GOLD      = CSS.getPropertyValue('--gold').trim()       || '#ffd93d';
const PINK      = CSS.getPropertyValue('--pink').trim()       || '#ff6fa5';
const SKY       = CSS.getPropertyValue('--sky').trim()        || '#43b8ff';
const GREEN     = CSS.getPropertyValue('--green').trim()      || '#4ddb8b';
const VIOLET    = CSS.getPropertyValue('--violet').trim()     || '#9b6bff';
const RED       = CSS.getPropertyValue('--red').trim()        || '#ff5d5d';
const GRID      = 'rgba(150,130,255,0.18)';

export function renderChart(kind, container, data) {
  container.innerHTML = '';
  const fn = DISPATCH[kind];
  if (!fn) {
    const note = document.createElement('p');
    note.className = 'chart-note';
    note.textContent = `(no renderer for "${kind}")`;
    container.appendChild(note);
    return;
  }
  try {
    fn(container, data || {});
  } catch (err) {
    const note = document.createElement('p');
    note.className = 'chart-note error';
    note.textContent = `chart error: ${err.message}`;
    container.appendChild(note);
  }
}

function makeCanvas(container, height = 200) {
  const wrap = document.createElement('div');
  wrap.className = 'chart-wrap';
  container.appendChild(wrap);
  const canvas = document.createElement('canvas');
  const dpr = Math.min(window.devicePixelRatio || 1, 2);
  const width = container.clientWidth || 360;
  canvas.width = Math.floor(width * dpr);
  canvas.height = Math.floor(height * dpr);
  canvas.style.width = '100%';
  canvas.style.height = `${height}px`;
  wrap.appendChild(canvas);
  return { canvas, ctx: canvas.getContext('2d'), dpr };
}

function padding(width, dpr) {
  return {
    left: 50 * dpr, right: 14 * dpr,
    top: 18 * dpr, bottom: 26 * dpr, w: width,
  };
}

function axes(ctx, box) {
  ctx.strokeStyle = GRID;
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.moveTo(box.left, box.top);
  ctx.lineTo(box.left, box.bottom);
  ctx.lineTo(box.right, box.bottom);
  ctx.stroke();
}

/* ------------------------------------------------------ diverging_bars */

function drawDivergingBars(container, data) {
  const rows = (data.rows || []).slice();
  if (!rows.length) return;

  const table = document.createElement('div');
  table.className = 'brow-table';
  container.appendChild(table);

  const valueOf = (r) => Number(r.value ?? 0);
  const centered = data.centered !== false;
  let lo, hi;
  if (centered) {
    const maxAbs = Math.max(0.5, ...rows.map((r) => Math.abs(valueOf(r))));
    lo = -maxAbs; hi = maxAbs;
  } else {
    lo = Math.min(...rows.map(valueOf));
    hi = Math.max(...rows.map(valueOf));
    if (hi - lo < 0.1) hi = lo + 1;
  }
  const range = hi - lo;

  for (const row of rows) {
    const v = valueOf(row);
    const isBest = !!row.best;
    const track = document.createElement('div');
    track.className = 'brow diverging' + (isBest ? ' is-best' : '');
    let bar;
    if (centered) {
      const half = (Math.abs(v) / Math.max(1, hi)) * 50;
      bar = v >= 0
        ? `<span class="brow-pos" style="width:${half}%"></span>`
        : `<span class="brow-neg" style="width:${half}%"></span>`;
    } else {
      const pct = Math.max(2, ((v - lo) / range) * 100);
      bar = `<span class="brow-pos" style="left:0;width:${pct}%"></span>`;
    }
    const shown = centered
      ? (v >= 0 ? `+${v.toFixed(1)}` : v.toFixed(1))
      : v.toFixed(1);
    track.innerHTML = `
      <span class="brow-label" title="${escapeHtml(row.label)}">${escapeHtml(row.label)}${isBest ? ' ★' : ''}</span>
      <span class="brow-track ${centered ? 'diverging' : ''}">${bar}</span>
      <span class="brow-num">${shown}<i> n${row.n}</i></span>
    `;
    table.appendChild(track);
  }
  if (data.xlabel) {
    const cap = document.createElement('div');
    cap.className = 'chart-axis-caption';
    cap.textContent = data.xlabel;
    container.appendChild(cap);
  }
}

/* --------------------------------------------------------- histogram */

function drawHistogram(container, data) {
  const bins = data.bins || [];
  if (!bins.length) return;
  const { canvas, ctx, dpr } = makeCanvas(container, 170);
  const box = padding(canvas.width, dpr);
  box.right = canvas.width - box.right;
  box.bottom = canvas.height - box.bottom;
  box.usableW = box.right - box.left;
  box.usableH = box.bottom - box.top;

  const maxCount = Math.max(...bins.map((b) => b.count));
  const barW = box.usableW / bins.length;
  axes(ctx, box);

  for (let i = 0; i < bins.length; i++) {
    const b = bins[i];
    const h = (b.count / maxCount) * box.usableH;
    const x = box.left + i * barW;
    const y = box.bottom - h;
    const isPeak = data.peak !== undefined && b.x0 === data.peak;
    const grad = ctx.createLinearGradient(0, y, 0, box.bottom);
    if (isPeak) {
      grad.addColorStop(0, GOLD);
      grad.addColorStop(1, 'rgba(255,217,61,0.3)');
    } else {
      grad.addColorStop(0, SKY);
      grad.addColorStop(1, 'rgba(67,184,255,0.18)');
    }
    ctx.fillStyle = grad;
    ctx.fillRect(x + 2, y, Math.max(2, barW - 4), h);
    if (isPeak) {
      ctx.fillStyle = GOLD;
      ctx.font = `${11 * dpr}px ui-rounded, system-ui, sans-serif`;
      ctx.textAlign = 'center';
      ctx.fillText(`${b.count}`, x + barW / 2, y - 4 * dpr);
    }
  }

  // x axis labels: first, last, and the peak
  ctx.fillStyle = INK_MUTE;
  ctx.font = `${10 * dpr}px ui-rounded, system-ui`;
  ctx.textAlign = 'center';
  const firstBin = bins[0], lastBin = bins[bins.length - 1];
  ctx.fillText(firstBin.x0.toString(), box.left + barW / 2, box.bottom + 16 * dpr);
  ctx.fillText(`${lastBin.x1}`, box.right - barW / 2, box.bottom + 16 * dpr);
  if (data.peak !== undefined) {
    const idx = bins.findIndex((b) => b.x0 === data.peak);
    if (idx >= 0) {
      ctx.fillStyle = GOLD;
      ctx.fillText(`${data.peak}-${data.peak + (bins[0].x1 - bins[0].x0)}`,
                   box.left + (idx + 0.5) * barW,
                   box.bottom + 16 * dpr);
    }
  }
  if (data.xlabel) {
    const cap = document.createElement('div');
    cap.className = 'chart-axis-caption';
    cap.textContent = data.xlabel;
    container.appendChild(cap);
  }
}

/* -------------------------------------------------------------- line */

function drawLine(container, data) {
  const points = (data.points || []).slice();
  if (points.length < 2) return;
  const { canvas, ctx, dpr } = makeCanvas(container, 180);
  const box = padding(canvas.width, dpr);
  box.right = canvas.width - box.right;
  box.bottom = canvas.height - box.bottom;
  box.usableW = box.right - box.left;
  box.usableH = box.bottom - box.top;

  const xs = points.map((p) => p.x);
  const ys = points.map((p) => p.y);
  const logx = !!data.logx;
  const logy = !!data.logy;
  const xTx = (x) => logx ? Math.log10(Math.max(1, x)) : x;
  const yTx = (y) => logy ? Math.log10(Math.max(1, y)) : y;
  const xVals = xs.map(xTx); const yVals = ys.map(yTx);
  const xMin = Math.min(...xVals), xMax = Math.max(...xVals);
  const yMin = Math.min(...yVals), yMax = Math.max(...yVals);
  const xSpan = Math.max(1e-6, xMax - xMin);
  const ySpan = Math.max(1e-6, yMax - yMin);
  const pad = ySpan * 0.1;
  const yLo = yMin - pad, yHi = yMax + pad;

  axes(ctx, box);

  // grid lines
  ctx.strokeStyle = GRID;
  ctx.setLineDash([2, 4]);
  for (let i = 1; i <= 3; i++) {
    const y = box.top + (box.usableH * i) / 4;
    ctx.beginPath();
    ctx.moveTo(box.left, y);
    ctx.lineTo(box.right, y);
    ctx.stroke();
  }
  ctx.setLineDash([]);

  // filled area under the line
  ctx.beginPath();
  ctx.moveTo(box.left, box.bottom);
  for (let i = 0; i < points.length; i++) {
    const x = box.left + ((xVals[i] - xMin) / xSpan) * box.usableW;
    const y = box.bottom - ((yVals[i] - yLo) / (yHi - yLo)) * box.usableH;
    ctx.lineTo(x, y);
  }
  ctx.lineTo(box.right, box.bottom);
  ctx.closePath();
  const fill = ctx.createLinearGradient(0, box.top, 0, box.bottom);
  fill.addColorStop(0, 'rgba(67,184,255,0.35)');
  fill.addColorStop(1, 'rgba(67,184,255,0.02)');
  ctx.fillStyle = fill;
  ctx.fill();

  // the line itself
  ctx.beginPath();
  const stroke = ctx.createLinearGradient(box.left, 0, box.right, 0);
  stroke.addColorStop(0, VIOLET);
  stroke.addColorStop(1, GREEN);
  ctx.strokeStyle = stroke;
  ctx.lineWidth = 2 * dpr;
  ctx.lineJoin = 'round';
  for (let i = 0; i < points.length; i++) {
    const x = box.left + ((xVals[i] - xMin) / xSpan) * box.usableW;
    const y = box.bottom - ((yVals[i] - yLo) / (yHi - yLo)) * box.usableH;
    if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
  }
  ctx.stroke();

  // knee marker
  if (data.knee !== undefined && points[data.knee]) {
    const p = points[data.knee];
    const x = box.left + ((xTx(p.x) - xMin) / xSpan) * box.usableW;
    const y = box.bottom - ((yTx(p.y) - yLo) / (yHi - yLo)) * box.usableH;
    ctx.fillStyle = GOLD;
    ctx.beginPath();
    ctx.arc(x, y, 5 * dpr, 0, Math.PI * 2);
    ctx.fill();
    ctx.strokeStyle = GOLD;
    ctx.lineWidth = 1.5 * dpr;
    ctx.setLineDash([3, 3]);
    ctx.beginPath();
    ctx.moveTo(x, y);
    ctx.lineTo(x, box.bottom);
    ctx.stroke();
    ctx.setLineDash([]);
    ctx.fillStyle = GOLD;
    ctx.font = `${10 * dpr}px ui-rounded, system-ui`;
    ctx.textAlign = 'left';
    ctx.fillText(`knee at ${formatNumber(p.x)}`, x + 7 * dpr, y - 4 * dpr);
  }

  // axis labels
  ctx.fillStyle = INK_MUTE;
  ctx.font = `${10 * dpr}px ui-rounded, system-ui`;
  ctx.textAlign = 'left';
  ctx.fillText(formatNumber(xs[0]), box.left, box.bottom + 14 * dpr);
  ctx.textAlign = 'right';
  ctx.fillText(formatNumber(xs[xs.length - 1]),
               box.right, box.bottom + 14 * dpr);
  ctx.textAlign = 'right';
  ctx.fillText(formatNumber(Math.max(...ys)), box.left - 6 * dpr,
               box.top + 4 * dpr);
  ctx.fillText(formatNumber(Math.min(...ys)), box.left - 6 * dpr,
               box.bottom);
  if (data.xlabel || data.ylabel) {
    const cap = document.createElement('div');
    cap.className = 'chart-axis-caption';
    cap.textContent = `${data.ylabel || ''} ${data.xlabel ? '· ' + data.xlabel : ''}`.trim();
    container.appendChild(cap);
  }
}

/* ------------------------------------------------------------- scatter */

function drawScatter(container, data) {
  const points = (data.points || []).slice();
  if (points.length < 2) return;
  const { canvas, ctx, dpr } = makeCanvas(container, 200);
  const box = padding(canvas.width, dpr);
  box.right = canvas.width - box.right;
  box.bottom = canvas.height - box.bottom;
  box.usableW = box.right - box.left;
  box.usableH = box.bottom - box.top;

  const xs = points.map((p) => p.x);
  const ys = points.map((p) => p.y);
  const xMin = Math.min(...xs), xMax = Math.max(...xs);
  const yMin = Math.min(...ys), yMax = Math.max(...ys);
  const xSpan = Math.max(1e-6, xMax - xMin);
  const ySpan = Math.max(1e-6, yMax - yMin);
  axes(ctx, box);

  // zero line on y
  if (yMin < 0 && yMax > 0) {
    const y0 = box.bottom - ((0 - yMin) / ySpan) * box.usableH;
    ctx.strokeStyle = GRID;
    ctx.setLineDash([2, 4]);
    ctx.beginPath(); ctx.moveTo(box.left, y0); ctx.lineTo(box.right, y0); ctx.stroke();
    ctx.setLineDash([]);
  }

  for (const p of points) {
    const x = box.left + ((p.x - xMin) / xSpan) * box.usableW;
    const y = box.bottom - ((p.y - yMin) / ySpan) * box.usableH;
    ctx.fillStyle = p.y >= 0 ? 'rgba(67,184,255,0.85)' : 'rgba(255,111,165,0.85)';
    ctx.beginPath();
    ctx.arc(x, y, 3 * dpr, 0, Math.PI * 2);
    ctx.fill();
  }

  // axis numbers
  ctx.fillStyle = INK_MUTE;
  ctx.font = `${10 * dpr}px ui-rounded, system-ui`;
  ctx.textAlign = 'left';
  ctx.fillText(xMin.toString(), box.left, box.bottom + 14 * dpr);
  ctx.textAlign = 'right';
  ctx.fillText(xMax.toString(), box.right, box.bottom + 14 * dpr);
  ctx.textAlign = 'right';
  ctx.fillText(yMax.toFixed(0), box.left - 6 * dpr, box.top + 4 * dpr);
  ctx.fillText(yMin.toFixed(0), box.left - 6 * dpr, box.bottom);

  if (data.xlabel || data.ylabel) {
    const cap = document.createElement('div');
    cap.className = 'chart-axis-caption';
    cap.textContent = `${data.ylabel || ''} ${data.xlabel ? '· ' + data.xlabel : ''}`.trim();
    container.appendChild(cap);
  }
}

/* ------------------------------------------------------------- heatmap */

function drawHeatmap(container, data) {
  const values = data.values || [];
  const n = data.n || 16;
  if (values.length !== n * n) return;
  const { canvas, ctx, dpr } = makeCanvas(container, 280);
  ctx.fillStyle = 'rgba(6,5,20,0.8)';
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  const pad = 8 * dpr;
  const sizeCss = Math.min(canvas.height - pad * 2, canvas.width - pad * 2);
  const offX = (canvas.width - sizeCss) / 2;
  const offY = (canvas.height - sizeCss) / 2;
  const tile = sizeCss / n;
  const metric = data.metric || 'top';

  for (const v of values) {
    const cell = v.cell;
    const r = Math.floor(cell / n);
    const c = cell - r * n;
    const val = Number(v[metric] ?? 0);  // 0..1
    const x = offX + c * tile;
    const y = offY + r * tile;
    ctx.fillStyle = heatColour(val);
    ctx.fillRect(x + 1, y + 1, tile - 2, tile - 2);
  }
  // legend strip
  ctx.fillStyle = INK_MUTE;
  ctx.font = `${10 * dpr}px ui-rounded, system-ui`;
  ctx.textAlign = 'left';
  ctx.fillText(`each tile = fraction of deepest attempts that filled it`,
               offX, offY + sizeCss + 14 * dpr);
}

function heatColour(v) {
  const clamp = Math.max(0, Math.min(1, v));
  // cold = deep violet, hot = bright gold (same palette as the app)
  const c1 = hexToRgb('#2a1b5e');
  const c2 = hexToRgb(SKY);
  const c3 = hexToRgb(GOLD);
  const lerp = (a, b, t) => Math.round(a + (b - a) * t);
  let rgb;
  if (clamp < 0.5) {
    const t = clamp / 0.5;
    rgb = [lerp(c1[0], c2[0], t), lerp(c1[1], c2[1], t), lerp(c1[2], c2[2], t)];
  } else {
    const t = (clamp - 0.5) / 0.5;
    rgb = [lerp(c2[0], c3[0], t), lerp(c2[1], c3[1], t), lerp(c2[2], c3[2], t)];
  }
  return `rgb(${rgb[0]}, ${rgb[1]}, ${rgb[2]})`;
}

function hexToRgb(hex) {
  const h = hex.replace('#', '');
  return [parseInt(h.slice(0, 2), 16),
          parseInt(h.slice(2, 4), 16),
          parseInt(h.slice(4, 6), 16)];
}

/* --------------------------------------------------------------- bars */

function drawBars(container, data) {
  const items = data.items || [];
  if (!items.length) return;
  const table = document.createElement('div');
  table.className = 'brow-table';
  container.appendChild(table);
  const unit = data.unit || '';
  const max = Math.max(1e-6, ...items.map((i) => Math.abs(i.value)));
  for (const item of items) {
    const pct = (Math.abs(item.value) / max) * 100;
    const row = document.createElement('div');
    row.className = 'brow' + (item.best ? ' is-best' : '');
    const label = String(item.value * 100).slice(0, 5);
    row.innerHTML = `
      <span class="brow-label">${escapeHtml(item.label)}${item.best ? ' ★' : ''}</span>
      <span class="brow-track"><span class="brow-pos" style="left:0;width:${pct}%"></span></span>
      <span class="brow-num">${unit === 'fraction'
        ? `${(item.value * 100).toFixed(0)}%`
        : item.value}<i> n${item.n}</i></span>
    `;
    table.appendChild(row);
  }
  if (data.xlabel) {
    const cap = document.createElement('div');
    cap.className = 'chart-axis-caption';
    cap.textContent = data.xlabel;
    container.appendChild(cap);
  }
}

/* ------------------------------------------------------------- profile */

function drawProfile(container, data) {
  const rows = data.rows || [];
  if (!rows.length) return;
  const wrap = document.createElement('div');
  wrap.className = 'profile-card';
  wrap.innerHTML = `
    <div class="profile-hero">
      <div class="profile-num"><b>${(data.mean ?? 0).toFixed(1)}</b>
        <small>vs ${(data.others ?? 0).toFixed(1)} average depth</small>
      </div>
    </div>
    <div class="profile-rows">
      ${rows.map((r) => `
        <div class="profile-row">
          <span class="profile-k">${escapeHtml(r.label)}</span>
          <span class="profile-v">${escapeHtml(String(r.value))}</span>
        </div>
      `).join('')}
    </div>
  `;
  container.appendChild(wrap);
}

/* --------------------------------------------------- interaction_table */

function drawInteractionTable(container, data) {
  const rows = data.rows || [];
  if (!rows.length) return;
  const wrap = document.createElement('div');
  wrap.className = 'itable';
  wrap.innerHTML = `
    <table>
      <thead><tr>
        <th>${escapeHtml(data.labelA || '')}</th>
        <th>${escapeHtml(data.labelB || '')}</th>
        <th>observed</th>
        <th>predicted</th>
        <th>lift</th>
        <th>n</th>
      </tr></thead>
      <tbody>${rows.map((r) => {
        const cls = r.interaction >= 0 ? 'pos' : 'neg';
        const sign = r.interaction >= 0 ? '+' : '';
        return `<tr>
          <td>${escapeHtml(r.a)}</td>
          <td>${escapeHtml(r.b)}</td>
          <td>${fmt(r.observed)}</td>
          <td>${fmt(r.additive)}</td>
          <td class="${cls}">${sign}${fmt(r.interaction)}</td>
          <td class="n">${r.n}</td>
        </tr>`;
      }).join('')}</tbody>
    </table>
  `;
  container.appendChild(wrap);
}

/* ------------------------------------------------------------ outliers */

function drawOutliers(container, data) {
  const items = data.items || [];
  if (!items.length) return;
  const wrap = document.createElement('div');
  wrap.className = 'outlier-list';
  wrap.innerHTML = items.map((it) => {
    const direction = it.z >= 0 ? 'pos' : 'neg';
    return `<a class="outlier-chip ${direction}" data-attempt="${it.id}">
      <span class="outlier-id">#${it.id}</span>
      <span class="outlier-depth">${it.depth}<i>/256</i></span>
      <span class="outlier-z">${it.z >= 0 ? '+' : ''}${it.z.toFixed(1)}σ</span>
    </a>`;
  }).join('');
  container.appendChild(wrap);
  const note = document.createElement('p');
  note.className = 'chart-note';
  note.textContent = 'Click any chip to open that attempt in the History tab.';
  container.appendChild(note);
}

/* -------------------------------------------------------------- dispatch */

const DISPATCH = {
  diverging_bars: drawDivergingBars,
  histogram: drawHistogram,
  line: drawLine,
  scatter: drawScatter,
  heatmap: drawHeatmap,
  bars: drawBars,
  profile: drawProfile,
  interaction_table: drawInteractionTable,
  outliers: drawOutliers,
};

function escapeHtml(str) {
  return String(str).replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}

function fmt(v) {
  const n = Number(v);
  return Number.isFinite(n) ? n.toFixed(1) : String(v);
}

function formatNumber(value) {
  const n = Number(value);
  if (!Number.isFinite(n)) return String(value);
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1000) return `${(n / 1000).toFixed(1)}k`;
  return Math.round(n).toString();
}
