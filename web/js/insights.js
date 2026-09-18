/* The Insights tab.
 *
 * Summary strip + "Run analysis" button + one card per finding.  Each card has
 * a title, a paragraph, a canvas visual and a strength label.  The whole panel
 * is repopulated on each rescan; findings that stopped showing up simply
 * disappear, because the server no longer returns them.
 */

import { renderChart } from './charts.js';
import { fmtDuration } from './history.js';

const LEVEL_CUTOFFS = [
  { min: 12, cls: 'lvl-strong',   label: 'strong'   },
  { min: 6,  cls: 'lvl-moderate', label: 'moderate' },
  { min: 0,  cls: 'lvl-mild',     label: 'mild'     },
];

function levelOf(strength) {
  for (const level of LEVEL_CUTOFFS) {
    if (strength >= level.min) return level;
  }
  return LEVEL_CUTOFFS[LEVEL_CUTOFFS.length - 1];
}

const GROUP_LABEL = {
  settings: 'Settings',
  board:    'Board',
  dynamics: 'Run dynamics',
};

export class InsightsPanel {
  constructor({ root, stats, button, empty, onOpenAttempt, onAnalyze }) {
    this.root = root;
    this.stats = stats;
    this.button = button;
    this.empty = empty;
    this.onOpenAttempt = onOpenAttempt || (() => {});
    this.onAnalyze = onAnalyze;

    if (this.button) {
      this.button.addEventListener('click', () => this.handleAnalyze());
    }
  }

  render({ findings, analysis, stats }) {
    this.renderStats(stats);
    this.renderFindings(findings || [], analysis);
  }

  renderStats(stats) {
    if (!this.stats) return;
    const s = stats || {};
    this.stats.innerHTML = `
      <div><span>attempts</span><b>${s.attempts || 0}</b></div>
      <div><span>record</span><b>${s.bestDepth || 0}<small>/256</small></b></div>
      <div><span>average depth</span><b>${(s.avgDepth || 0).toFixed ? s.avgDepth.toFixed(1) : s.avgDepth}</b></div>
      <div><span>solved</span><b>${s.solvedCount || 0}</b></div>
    `;
  }

  renderFindings(findings, analysis) {
    if (!this.root) return;
    this.root.innerHTML = '';

    if (!findings.length) {
      if (this.empty) {
        this.empty.hidden = false;
        this.empty.innerHTML = analysis
          ? `<p>The last analysis ran over <b>${analysis.attempt_count || analysis.attempts || 0}</b>
                attempts but found no patterns strong enough to report yet.
                Let a few more attempts finish and try again.</p>`
          : `<p>Click <b>Run analysis</b> above to look for patterns in the data
                collected so far. You will see one card per significant finding.</p>`;
      }
      return;
    }
    if (this.empty) this.empty.hidden = true;

    // group the cards, but keep the order of strongest-first within each group
    const byGroup = new Map();
    for (const f of findings) {
      if (!byGroup.has(f.group)) byGroup.set(f.group, []);
      byGroup.get(f.group).push(f);
    }

    const order = ['settings', 'board', 'dynamics'];
    for (const groupKey of order) {
      if (!byGroup.has(groupKey)) continue;
      const section = document.createElement('section');
      section.className = 'findings-group';
      section.innerHTML = `<h3>${GROUP_LABEL[groupKey] || groupKey}</h3>`;
      for (const f of byGroup.get(groupKey)) {
        section.appendChild(this.card(f));
      }
      this.root.appendChild(section);
    }

    if (analysis) {
      const note = document.createElement('p');
      note.className = 'analysis-note';
      const at = analysis.run_at ? new Date(analysis.run_at * 1000) : null;
      note.innerHTML =
        `<b>${findings.length}</b> finding${findings.length === 1 ? '' : 's'} ` +
        `from <b>${analysis.attempt_count || 0}</b> attempts` +
        (at ? ` · analysed ${timeAgo(at)}` : '') +
        ` · took ${analysis.duration_ms || 0} ms`;
      this.root.appendChild(note);
    }
  }

  card(finding) {
    const el = document.createElement('article');
    el.className = 'finding';
    el.dataset.id = finding.id;
    const level = levelOf(finding.strength);
    const streak = finding.rescanStreak ? (finding.rescanStreak > 1
      ? ` · seen in ${finding.rescanStreak} analyses in a row`
      : '') : '';

    el.innerHTML = `
      <header class="finding-head">
        <div class="finding-title">
          <h4>${escapeHtml(finding.title)}</h4>
          <span class="finding-meta">${escapeHtml(GROUP_LABEL[finding.group] || finding.group)}${streak}</span>
        </div>
        <div class="finding-strength ${level.cls}">
          <span class="strength-label">${level.label}</span>
          <span class="strength-value">z&nbsp;${finding.strength.toFixed(1)}</span>
          <span class="strength-support">n ${finding.support}</span>
        </div>
      </header>
      <p class="finding-paragraph">${escapeHtml(finding.paragraph)}</p>
      <div class="finding-chart"></div>
    `;
    const chart = el.querySelector('.finding-chart');
    const visual = finding.visual || {};
    renderChart(visual.kind, chart, visual);

    // wire outlier chips to open attempts in the history tab
    for (const chip of el.querySelectorAll('.outlier-chip[data-attempt]')) {
      chip.addEventListener('click', () => {
        this.onOpenAttempt(Number(chip.dataset.attempt));
      });
    }
    return el;
  }

  async handleAnalyze() {
    if (!this.onAnalyze || !this.button) return;
    this.button.disabled = true;
    const textEl = this.button.querySelector('.btn-text');
    const originalText = textEl ? textEl.textContent : '';
    if (textEl) textEl.textContent = 'Analysing…';
    try {
      await this.onAnalyze();
    } finally {
      if (textEl) textEl.textContent = originalText;
      this.button.disabled = false;
    }
  }
}

function escapeHtml(str) {
  return String(str).replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}

function timeAgo(date) {
  const ms = Date.now() - date.getTime();
  const s = Math.round(ms / 1000);
  if (s < 60) return `${s}s ago`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.round(m / 60);
  if (h < 48) return `${h}h ago`;
  return date.toLocaleString();
}
