/* Builds the settings controls from the schema the server publishes.
 *
 * Every knob shows:
 *   - its current value,
 *   - a one-line explanation,
 *   - what happens at each extreme of the slider,
 *   - where the learner's optimal value sits, with a one-click "snap to it".
 *
 * Nothing is hard-coded here: add a setting in server/schema.py and a control
 * appears automatically.
 */

export class SettingsPanel {
  constructor(root, { onChange }) {
    this.root = root;
    this.onChange = onChange;
    this.settings = [];
    this.groupOrder = [];
    this.optimal = {};
    this.optimalDetails = {};
    this.values = {};
    this.fields = new Map();
    // True only when the person at the keyboard changed a control, as opposed
    // to the panel being refreshed to mirror the running attempt.
    this.userEdited = false;
  }

  init({ settings, groupOrder, optimal, optimalDetails, current }) {
    this.userEdited = false;
    this.settings = settings || [];
    this.groupOrder = groupOrder || [];
    this.optimal = optimal || {};
    this.optimalDetails = optimalDetails || {};
    this.values = { ...(current || optimal || {}) };
    this.render();
  }

  /** Current values shown in the UI. */
  getValues() {
    return { ...this.values };
  }

  /** True when the user has deviated from the learner's optimal settings. */
  isCustom() {
    return this.settings.some((s) => {
      if (s.tunable === false) return false;
      return !eq(this.values[s.key], this.optimal[s.key]);
    });
  }

  setOptimal(optimal, optimalDetails) {
    if (optimal) this.optimal = optimal;
    if (optimalDetails) this.optimalDetails = optimalDetails;
    this.refreshMarkers();
  }

  /** Replace the shown values (e.g. after "use optimal" or a new attempt). */
  setValues(values) {
    if (!values) return;
    this.values = { ...values };
    this.userEdited = false;
    for (const s of this.settings) this.syncField(s);
    this.onChange && this.onChange(this.values, this.isCustom(), false);
  }

  render() {
    this.root.innerHTML = '';
    this.fields.clear();

    const groups = new Map();
    for (const s of this.settings) {
      if (!groups.has(s.group)) groups.set(s.group, []);
      groups.get(s.group).push(s);
    }
    const order = this.groupOrder.filter((g) => groups.has(g))
      .concat([...groups.keys()].filter((g) => !this.groupOrder.includes(g)));

    for (const groupName of order) {
      const wrap = el('div', 'sgroup');
      wrap.appendChild(el('h3', null, groupName));
      const body = el('div', 'sgroup-body');
      for (const s of groups.get(groupName)) body.appendChild(this.buildField(s));
      wrap.appendChild(body);
      this.root.appendChild(wrap);
    }
  }

  buildField(s) {
    const field = el('div', 'field');
    field.dataset.key = s.key;

    const head = el('div', 'field-head');
    const label = el('label', null, s.label);
    label.setAttribute('for', `set-${s.key}`);
    const val = el('span', 'field-val');
    head.append(label, val);
    field.appendChild(head);

    if (s.blurb) field.appendChild(el('p', 'field-blurb', s.blurb));

    let control;
    if (s.kind === 'enum') control = this.buildEnum(s);
    else if (s.kind === 'bool') control = this.buildBool(s);
    else control = this.buildRange(s);
    field.appendChild(control.node);

    if (s.low || s.high) {
      const ex = el('div', 'field-extremes');
      ex.append(el('span', null, s.low || ''), el('span', null, s.high || ''));
      field.appendChild(ex);
    }

    const opt = el('div', 'field-optimal');
    field.appendChild(opt);

    this.fields.set(s.key, { setting: s, node: field, valueEl: val, control, optEl: opt });
    this.syncField(s);
    return field;
  }

  buildEnum(s) {
    const wrap = el('div', 'segmented');
    const buttons = new Map();
    for (const option of s.options) {
      const b = el('button', null);
      b.type = 'button';
      b.title = option.blurb || '';
      b.innerHTML = `<span>${escapeHtml(option.label)}</span>`;
      b.addEventListener('click', () => this.setValue(s.key, option.value));
      buttons.set(String(option.value), b);
      wrap.appendChild(b);
    }
    return {
      node: wrap,
      sync: (value) => {
        for (const [key, b] of buttons) {
          b.classList.toggle('on', key === String(value));
          const isOptimal = key === String(this.optimal[s.key]);
          const star = b.querySelector('.opt-star');
          if (isOptimal && !star) {
            b.insertAdjacentHTML('beforeend', ' <span class="opt-star" title="learned optimum">★</span>');
          } else if (!isOptimal && star) {
            star.remove();
          }
        }
      },
      display: (value) => {
        const option = s.options.find((o) => String(o.value) === String(value));
        return option ? option.label : String(value);
      },
    };
  }

  buildBool(s) {
    const wrap = el('label', 'switch');
    const input = el('input');
    input.type = 'checkbox';
    input.id = `set-${s.key}`;
    const text = el('span');
    wrap.append(input, text);
    input.addEventListener('change', () => this.setValue(s.key, input.checked));
    return {
      node: wrap,
      sync: (value) => { input.checked = !!value; text.textContent = value ? 'enabled' : 'disabled'; },
      display: (value) => (value ? 'on' : 'off'),
    };
  }

  buildRange(s) {
    const holder = el('div', 'slider-holder');
    const input = el('input');
    input.type = 'range';
    input.id = `set-${s.key}`;
    const isScale = s.kind === 'scale';
    const values = isScale ? s.values : null;

    if (isScale) {
      input.min = '0';
      input.max = String(values.length - 1);
      input.step = '1';
    } else {
      input.min = String(s.min);
      input.max = String(s.max);
      input.step = String(s.step || 1);
    }
    holder.appendChild(input);

    const apply = () => {
      const raw = Number(input.value);
      const value = isScale ? values[raw] : raw;
      this.setValue(s.key, value);
    };
    input.addEventListener('input', apply);

    return {
      node: holder,
      sync: (value) => {
        if (isScale) {
          let idx = values.indexOf(Number(value));
          if (idx < 0) {
            // snap to nearest declared step
            idx = values.reduce((best, v, i) =>
              Math.abs(v - value) < Math.abs(values[best] - value) ? i : best, 0);
          }
          input.value = String(idx);
          setFill(input, idx, 0, values.length - 1);
        } else {
          input.value = String(value);
          setFill(input, Number(value), s.min, s.max);
        }
      },
      display: (value) => formatNumber(value, s),
    };
  }

  setValue(key, value) {
    this.values[key] = value;
    this.userEdited = true;
    const entry = this.fields.get(key);
    if (entry) this.syncField(entry.setting);
    this.onChange && this.onChange(this.values, this.isCustom(), true);
  }

  syncField(s) {
    const entry = this.fields.get(s.key);
    if (!entry) return;
    const value = this.values[s.key];
    entry.control.sync(value);
    entry.valueEl.textContent = entry.control.display(value);
    const differs = s.tunable !== false && !eq(value, this.optimal[s.key]);
    entry.valueEl.classList.toggle('changed', differs);
    this.renderOptimalHint(entry, s, value);
  }

  renderOptimalHint(entry, s, value) {
    const target = entry.optEl;
    target.innerHTML = '';
    if (s.tunable === false) {
      target.append(document.createTextNode('not tuned by the learner'));
      return;
    }
    const detail = this.optimalDetails[s.key] || {};
    const optimalValue = this.optimal[s.key];
    const label = entry.control.display(optimalValue);
    const support = detail.support || 0;

    const text = document.createElement('span');
    text.innerHTML = `optimal: <b>${escapeHtml(String(label))}</b>` +
      (support ? ` &middot; from ${support} attempt${support === 1 ? '' : 's'}` : ' &middot; measured default');
    target.appendChild(text);
    if (detail.reason) text.title = detail.reason;

    if (!eq(value, optimalValue)) {
      const snap = el('button', null, 'snap back');
      snap.type = 'button';
      snap.addEventListener('click', () => this.setValue(s.key, optimalValue));
      target.appendChild(snap);
    }
  }

  refreshMarkers() {
    for (const s of this.settings) this.syncField(s);
  }
}

/* ------------------------------------------------------------------ helpers */

function el(tag, cls, text) {
  const node = document.createElement(tag);
  if (cls) node.className = cls;
  if (text !== undefined && text !== null) node.textContent = text;
  return node;
}

function setFill(input, value, min, max) {
  const span = max - min;
  const pct = span > 0 ? ((value - min) / span) * 100 : 0;
  input.style.setProperty('--fill', `${pct}%`);
}

function eq(a, b) {
  if (typeof a === 'boolean' || typeof b === 'boolean') return !!a === !!b;
  return String(a) === String(b);
}

export function formatNumber(value, setting) {
  const n = Number(value);
  if (!Number.isFinite(n)) return String(value);
  if (setting && setting.key === 'restartMultiplier') return `${(n / 100).toFixed(2)}×`;
  if (setting && setting.key === 'candidateCap' && n >= 1024) return 'unlimited';
  if (n >= 1000000) return `${trim(n / 1000000)}M`;
  if (n >= 1000) return `${trim(n / 1000)}k`;
  return String(n);
}

function trim(x) {
  return String(Math.round(x * 100) / 100);
}

export function escapeHtml(str) {
  return String(str).replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}
