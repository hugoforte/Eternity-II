/* The Lessons tab.
 *
 * Lessons are the findings that keep showing up in successive analyses.  They
 * live in their own tab because they are *conclusions* rather than passing
 * observations: they persist across sessions, they bias the automatic tuner,
 * and the user can dismiss or restore any of them by hand.
 */

const STATUS_BLURB = {
  active:
    'Active. The automatic tuner is being biased toward this choice. ' +
    'Exploration of other values is softened but not blocked.',
  watching:
    'Watching. Seen in recent analyses but not yet consistent enough ' +
    'to guide the tuner.',
  retired:
    'Retired. Once active, but the underlying pattern stopped holding ' +
    'in later analyses.',
};

export class LessonsPanel {
  constructor({ root, badge, onDismiss, onRestore }) {
    this.root = root;
    this.badge = badge;
    this.onDismiss = onDismiss;
    this.onRestore = onRestore;
  }

  render(lessons) {
    if (this.badge) {
      const activeCount = (lessons || []).filter(
        (l) => l.status === 'active' && !l.dismissed).length;
      this.badge.textContent = activeCount || '';
      this.badge.hidden = !activeCount;
    }

    if (!this.root) return;
    if (!lessons || !lessons.length) {
      this.root.innerHTML = `
        <div class="empty-note">
          No lessons yet. A finding becomes a lesson once it stays strong
          across several consecutive analyses. Active lessons appear here and
          are used to bias future attempts.
        </div>`;
      return;
    }

    const byStatus = groupBy(lessons, (l) => l.status || 'watching');
    const order = ['active', 'watching', 'retired'];
    const parts = [`
      <div class="lessons-intro">
        <h3>How this works</h3>
        <p>Patterns that keep winning across analyses graduate from the Insights
           tab to this page. Active lessons softly bias the automatic tuner
           toward the choice they endorse — it still explores, but less often,
           so the solver can converge faster on what has proven to work.</p>
        <p>Dismiss a lesson to make the tuner ignore it. It stays on file
           (greyed out) and can be restored later.</p>
      </div>
    `];

    for (const status of order) {
      const group = byStatus.get(status) || [];
      if (!group.length) continue;
      parts.push(`
        <section class="lesson-group status-${status}">
          <h3 class="lesson-group-title">
            <span class="dot"></span>
            ${labelFor(status)}
            <em>${group.length}</em>
          </h3>
          <div class="lesson-list">
            ${group.map((l) => this.card(l)).join('')}
          </div>
        </section>
      `);
    }

    this.root.innerHTML = parts.join('');
    for (const btn of this.root.querySelectorAll('.lesson-dismiss')) {
      btn.addEventListener('click', () => {
        const id = btn.dataset.id;
        if (id) this.onDismiss && this.onDismiss(id);
      });
    }
    for (const btn of this.root.querySelectorAll('.lesson-restore')) {
      btn.addEventListener('click', () => {
        const id = btn.dataset.id;
        if (id) this.onRestore && this.onRestore(id);
      });
    }
  }

  card(lesson) {
    const status = lesson.status || 'watching';
    const dismissed = !!lesson.dismissed;
    const rule = lesson.rule || {};
    const ruleText = rule.kind === 'prefer_value'
      ? `Prefer <b>${escapeHtml(friendlySetting(rule.setting))}</b> = <b>${escapeHtml(String(rule.value))}</b>`
      : escapeHtml(JSON.stringify(rule));
    const confidencePct = rule.confidence
      ? `${(rule.confidence * 100).toFixed(0)}%` : '—';
    const action = (dismissed || status === 'retired')
      ? `<button class="btn btn-ghost btn-small lesson-restore" data-id="${lesson.id}">
           ${dismissed ? 'Un-dismiss' : 'Revisit'}
         </button>`
      : `<button class="btn btn-ghost btn-small lesson-dismiss" data-id="${lesson.id}">
           Dismiss
         </button>`;
    const dismissedTag = dismissed
      ? '<span class="pill retired">dismissed by user</span>' : '';
    return `
      <article class="lesson-card ${dismissed ? 'is-dismissed' : ''}">
        <header class="lesson-head">
          <h4>${escapeHtml(lesson.title)}</h4>
          <div class="lesson-actions">${action}</div>
        </header>
        <p class="lesson-paragraph">${escapeHtml(lesson.paragraph)}</p>
        <div class="lesson-rule">${ruleText}
          <span class="lesson-sep">&middot;</span>
          confidence <b>${confidencePct}</b>
        </div>
        <div class="lesson-meta">
          <span>streak <b>${lesson.streak || 0}</b></span>
          ${lesson.misses ? `<span>misses <b>${lesson.misses}</b></span>` : ''}
          <span>evidence <b>${Math.round(lesson.strength || 0)}σ</b> over <b>${lesson.support || 0}</b> attempts</span>
          ${dismissedTag}
        </div>
        <p class="lesson-status">${STATUS_BLURB[status] || ''}</p>
      </article>
    `;
  }
}

function labelFor(status) {
  if (status === 'active')   return 'Active &middot; guiding future attempts';
  if (status === 'watching') return 'Watching &middot; not yet consistent';
  if (status === 'retired')  return 'Retired &middot; no longer applies';
  return status;
}

function friendlySetting(key) {
  // small hand map so lesson text reads naturally
  const LABELS = {
    cellOrder: 'Cell order',
    tieBreak: 'Tie breaker',
    valueOrder: 'Piece order',
    forwardCheck: 'Look ahead',
    restartPolicy: 'Restart policy',
    startCell: 'Opening move',
    greyInteriorPruning: 'Grey edge pruning',
  };
  return LABELS[key] || key;
}

function groupBy(items, fn) {
  const out = new Map();
  for (const it of items) {
    const key = fn(it);
    if (!out.has(key)) out.set(key, []);
    out.get(key).push(it);
  }
  return out;
}

function escapeHtml(str) {
  return String(str).replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}
