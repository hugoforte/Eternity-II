/* The attempt history list.
 *
 * Each card summarises one finished attempt and is clickable to replay it. The
 * CUSTOM pill marks attempts that ran with settings the user pinned, so it is
 * always obvious which results came from hand-tuning rather than the learner.
 */

import { escapeHtml, formatNumber } from './settings.js';

const STATUS_TEXT = {
  budget: 'reached its step budget',
  solved: 'SOLVED THE PUZZLE',
  exhausted: 'exhausted the search space',
  aborted: 'stopped early (not used for learning)',
  interrupted: 'interrupted',
  error: 'engine error',
};

export class HistoryPanel {
  constructor(listEl, { onSelect, countEl, statsEl, moreBtn, onLoadMore }) {
    this.listEl = listEl;
    this.countEl = countEl;
    this.statsEl = statsEl;
    this.moreBtn = moreBtn;
    this.onSelect = onSelect;
    this.attempts = [];
    this.total = 0;
    this.activeId = null;
    this.settingsMeta = [];
    this.recordDepth = 0;
    this.recordEdges = 0;

    if (this.moreBtn && onLoadMore) {
      this.moreBtn.addEventListener('click', () => onLoadMore());
    }
  }

  setSettingsMeta(settings) {
    this.settingsMeta = settings || [];
  }

  setStats(stats) {
    if (!stats) return;
    this.recordDepth = stats.bestDepth || 0;
    this.recordEdges = stats.bestMatchedEdges || 0;
    if (this.statsEl) {
      this.statsEl.innerHTML = `
        <div>attempts<b>${stats.attempts ?? 0}</b></div>
        <div>record<b>${stats.bestDepth ?? 0}<small>/256, no wrongs</small></b></div>
        <div>best edges<b>${stats.bestMatchedEdges ?? 0}<small>/480</small></b></div>
        <div>average<b>${stats.avgDepth ?? 0}</b></div>
        <div>total steps<b>${formatNumber(stats.totalNodes ?? 0)}</b></div>
      `;
    }
  }

  replaceAll(attempts, total) {
    this.attempts = attempts || [];
    this.total = total ?? this.attempts.length;
    this.render();
  }

  append(attempts) {
    this.attempts = this.attempts.concat(attempts || []);
    this.render();
  }

  /** Add a just-finished attempt at the top with a highlight animation. */
  prepend(attempt) {
    if (!attempt) return;
    this.attempts = [attempt, ...this.attempts.filter((a) => a.id !== attempt.id)];
    this.total += 1;
    // A slipped attempt must never bump the pieces record, even optimistically
    // here -- see setStats, which is the authoritative source and always runs
    // first on a real attempt_finished event.
    if (attempt.breaks === 0 && attempt.bestDepth > this.recordDepth) {
      this.recordDepth = attempt.bestDepth;
    }
    if (attempt.matchedEdges != null && attempt.matchedEdges > this.recordEdges) {
      this.recordEdges = attempt.matchedEdges;
    }
    this.render(attempt.id);
  }

  setActive(id) {
    this.activeId = id;
    for (const card of this.listEl.querySelectorAll('.hcard')) {
      card.classList.toggle('active', Number(card.dataset.id) === Number(id));
    }
  }

  render(freshId = null) {
    if (this.countEl) this.countEl.textContent = String(this.total);
    if (this.moreBtn) {
      this.moreBtn.hidden = this.attempts.length >= this.total;
    }

    if (!this.attempts.length) {
      this.listEl.innerHTML =
        '<div class="empty-note">No finished attempts yet.<br>' +
        'The first one will appear here within a few seconds, ready to replay.</div>';
      return;
    }

    this.listEl.innerHTML = '';
    for (const attempt of this.attempts) {
      this.listEl.appendChild(this.card(attempt, attempt.id === freshId));
    }
    this.setActive(this.activeId);
  }

  card(a, fresh) {
    const node = document.createElement('div');
    node.className = 'hcard' + (fresh ? ' fresh' : '');
    node.dataset.id = a.id;
    node.setAttribute('role', 'button');
    node.tabIndex = 0;

    const pct = Math.round(((a.bestDepth || 0) / 256) * 100);
    const pills = [];
    if (a.solved) pills.push('<span class="pill solved">solved</span>');
    // "record" means the pieces record, and that is only ever held by a
    // clean attempt -- one with breaks itself can't be crowned just because
    // its depth number happens to match the record's, even coincidentally.
    if (a.breaks === 0 && a.bestDepth >= this.recordDepth && this.recordDepth > 0) {
      pills.push('<span class="pill record">record</span>');
    }
    // The edges record has no such restriction -- see history.js's setStats.
    if (a.matchedEdges != null && this.recordEdges > 0 && a.matchedEdges >= this.recordEdges) {
      pills.push('<span class="pill edges-record">edges record</span>');
    }
    if (a.userDefined) pills.push('<span class="pill custom">custom</span>');
    else if (a.source === 'explore') pills.push('<span class="pill explore">explore</span>');
    else pills.push('<span class="pill optimal">optimal</span>');

    // Edge slipping can place every piece while some of them don't actually
    // fit their neighbour, so that count rides along as a small caveat next
    // to the piece count rather than a value of its own.
    const breaks = a.breaks
      ? `<span class="hcard-breaks">${escapeHtml('−')}${a.breaks} wrong</span>` : '';
    const edges = a.matchedEdges != null
      ? `<span class="hcard-edges">${a.matchedEdges}<i>/480 edges</i></span>` : '';

    node.innerHTML = `
      <div class="hcard-top">
        <span class="hcard-id">#${a.id}</span>
        ${pills.join('')}
        <span class="hcard-depth">${a.bestDepth}<i>/256</i></span>
        ${edges}
      </div>
      <div class="hbar"><i style="width:${pct}%"></i></div>
      <div class="hcard-meta">
        <span>${formatNumber(a.nodes || 0)} steps</span>
        <span>${fmtDuration(a.durationMs || 0)}</span>
        <span>${formatNumber(a.nodesPerSec || 0)}/s</span>
        ${a.restarts ? `<span>${a.restarts} restarts</span>` : ''}
        ${breaks}
        <span>${escapeHtml(STATUS_TEXT[a.status] || a.status || '')}</span>
      </div>
      <div class="hcard-cfg">${this.configSummary(a.config)}</div>
    `;

    const activate = () => this.onSelect && this.onSelect(a.id);
    node.addEventListener('click', activate);
    node.addEventListener('keydown', (ev) => {
      if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); activate(); }
    });
    return node;
  }

  /** Short, readable description of the settings an attempt used. */
  configSummary(config) {
    if (!config) return '';
    const parts = [];
    const pick = (key) => {
      const meta = this.settingsMeta.find((s) => s.key === key);
      if (!meta) return null;
      const value = config[key];
      if (value === undefined) return null;
      let shown = value;
      if (meta.kind === 'enum') {
        const option = meta.options.find((o) => String(o.value) === String(value));
        shown = option ? option.label : value;
      } else if (meta.kind === 'bool') {
        shown = value ? 'on' : 'off';
      } else {
        shown = formatNumber(value, meta);
      }
      return `<b>${escapeHtml(meta.label)}</b> ${escapeHtml(String(shown))}`;
    };
    // Most of these belong to one engine or the other (see schema.py's
    // activeWhen), so showing both sets regardless of which ran would make
    // half the summary describe settings that never reached the search.
    const keys = config.engine === 'scan'
      ? ['engine', 'fillOrder', 'slipSchedule', 'nodeBudget']
      : ['engine', 'cellOrder', 'valueOrder', 'forwardCheck', 'candidateCap',
         'restartPolicy', 'nodeBudget'];
    for (const key of keys) {
      const text = pick(key);
      if (text) parts.push(text);
    }
    return parts.join(' &middot; ');
  }
}

export function fmtDuration(ms) {
  if (ms < 1000) return `${ms}ms`;
  const s = ms / 1000;
  if (s < 60) return `${s.toFixed(1)}s`;
  const m = Math.floor(s / 60);
  const rest = Math.round(s - m * 60);
  if (m < 60) return `${m}m ${rest}s`;
  const h = Math.floor(m / 60);
  return `${h}h ${m - h * 60}m`;
}
