/* Eternity II Lab - application controller.
 *
 * The central idea: LIVE and REPLAY are two independent views over the same
 * board widget.
 *
 *   LIVE    the board mirrors whatever the solver is doing right now, fed by
 *           Server-Sent Events. The scrub bar is a read-only progress meter.
 *   REPLAY  the board is driven entirely from a finished attempt's placement
 *           list, fetched once over HTTP and scrubbed locally.
 *
 * Because replay data is fetched and stepped through in the browser, opening a
 * replay never pauses, slows or disturbs the live search. Live events keep
 * arriving and are remembered, so "Back to live" snaps straight to the current
 * board.
 */

import { api, openStream } from './api.js';
import { BoardView } from './board.js';
import { SettingsPanel, formatNumber } from './settings.js';
import { HistoryPanel, fmtDuration } from './history.js';
import { InsightsPanel } from './insights.js';
import { LessonsPanel } from './lessons.js';
import { PALETTE } from './palette.js';

const $ = (id) => document.getElementById(id);

const ui = {
  board: $('board'),
  boardEmpty: $('boardEmpty'),
  modePill: $('modePill'),
  modeDetail: $('modeDetail'),
  btnGoLive: $('btnGoLive'),
  chipAttempt: $('chipAttempt'),
  chipDepth: $('chipDepth'),
  chipNodes: $('chipNodes'),
  chipSpeed: $('chipSpeed'),
  chipRecord: $('chipRecord'),
  chipRecordWrap: $('chipRecordWrap'),
  btnPause: $('btnPause'),
  pauseIcon: $('pauseIcon'),
  pauseText: $('pauseText'),
  btnSkip: $('btnSkip'),
  conn: $('conn'),
  scrub: $('scrub'),
  scrubOut: $('scrubOut'),
  scrubTicks: $('scrubTicks'),
  btnPlay: $('btnPlay'),
  btnStepBack: $('btnStepBack'),
  btnStepFwd: $('btnStepFwd'),
  speed: $('speed'),
  spark: $('spark'),
  sparkCaption: $('sparkCaption'),
  settingsGroups: $('settingsGroups'),
  cfgBadge: $('cfgBadge'),
  cfgBadgeText: $('cfgBadgeText'),
  btnApply: $('btnApply'),
  btnOptimal: $('btnOptimal'),
  historyList: $('historyList'),
  histCount: $('histCount'),
  histStats: $('histStats'),
  btnMore: $('btnMore'),
  btnClear: $('btnClear'),
  btnAnalyze: $('btnAnalyze'),
  insightsStats: $('insightsStats'),
  insightsFindings: $('insightsFindings'),
  insightsEmpty: $('insightsEmpty'),
  lessonsBody: $('lessonsBody'),
  lessonCount: $('lessonCount'),
  legend: $('legend'),
  toast: $('toast'),
  confetti: $('confetti'),
};

const state = {
  mode: 'live',             // 'live' | 'replay'
  meta: null,
  live: {
    attemptId: null,
    board: null,
    placed: 0,
    best: 0,
    nodes: 0,
    nps: 0,
    ms: 0,
    paused: false,
    config: null,
    userDefined: false,
    source: 'tuner',
  },
  liveSamples: [],          // [{ms, best}] for the sparkline
  replay: {
    attempt: null,
    placements: [],
    index: 0,
    playing: false,
    timer: null,
  },
  stats: {},
  recordAttemptId: null,
  loadedAttempts: 0,
  pendingCustom: false,
  userEdited: false,
};

const boardView = new BoardView(ui.board);
const settings = new SettingsPanel(ui.settingsGroups, { onChange: onSettingsChanged });
const history = new HistoryPanel(ui.historyList, {
  onSelect: openReplay,
  countEl: ui.histCount,
  statsEl: ui.histStats,
  moreBtn: ui.btnMore,
  onLoadMore: loadMoreAttempts,
});
const insights = new InsightsPanel({
  root: ui.insightsFindings,
  stats: ui.insightsStats,
  button: ui.btnAnalyze,
  empty: ui.insightsEmpty,
  onOpenAttempt: (id) => openReplay(id),
  onAnalyze: runAnalyze,
});
const lessons = new LessonsPanel({
  root: ui.lessonsBody,
  badge: ui.lessonCount,
  onDismiss: dismissLesson,
  onRestore: restoreLesson,
});

/* ============================================================== bootstrap */

(async function start() {
  buildLegend();
  wireTabs();
  wireControls();

  try {
    const boot = await api.bootstrap();
    state.meta = boot.meta;
    state.stats = boot.stats || {};
    if (boot.meta) boardView.setMeta(boot.meta);

    settings.init({
      settings: boot.settings,
      groupOrder: boot.groupOrder,
      optimal: boot.optimal,
      optimalDetails: boot.optimalDetails,
      current: (boot.live && boot.live.config) || boot.optimal,
    });
    history.setSettingsMeta(boot.settings);

    history.replaceAll(boot.attempts, boot.attemptCount);
    history.setStats(boot.stats);
    state.loadedAttempts = (boot.attempts || []).length;

    insights.render({
      findings: boot.findings || [],
      analysis: boot.analysis,
      stats: boot.stats,
    });
    lessons.render(boot.lessons || []);

    applyLiveStatus(boot.live);
    updateRecord(boot.stats ? boot.stats.bestDepth : 0, boot.stats ? boot.stats.bestAttemptId : null);
  } catch (err) {
    toast(`Could not reach the server: ${err.message}`);
  }

  openStream({
    onOpen: () => ui.conn.classList.remove('down'),
    onError: () => ui.conn.classList.add('down'),
    hello: (data) => {
      if (data && data.meta && !state.meta) {
        state.meta = data.meta;
        boardView.setMeta(data.meta);
      }
      if (data && data.status) applyLiveStatus(data.status);
    },
    meta: (data) => {
      state.meta = data;
      boardView.setMeta(data);
    },
    live: onLiveFrame,
    state: applyLiveStatus,
    attempt_started: onAttemptStarted,
    attempt_finished: onAttemptFinished,
    attempt_aborted: () => {},
    analysis_complete: async () => {
      // another browser tab (or the server) kicked off an analyze; keep in sync
      try {
        const res = await api.findings();
        insights.render(res);
        const lessonsData = await api.lessons();
        lessons.render(lessonsData.lessons || []);
      } catch (err) { /* the panel just keeps its previous state */ }
    },
    error: (data) => toast(data && data.message ? data.message : 'engine error'),
  });
})();

/* ================================================================ analyze */

async function runAnalyze() {
  try {
    const res = await api.analyze();
    insights.render({
      findings: res.findings || [],
      analysis: res.analysis,
      stats: state.stats,
    });
    lessons.render(res.lessons || []);
    const changes = res.lessonChanges || {};
    const added = (changes.added || []).length;
    const retired = (changes.retired || []).length;
    const parts = [`${(res.findings || []).length} findings`];
    if (added) parts.push(`${added} new lesson${added === 1 ? '' : 's'}`);
    if (retired) parts.push(`${retired} retired`);
    toast('Analysis complete — ' + parts.join(', '),
          added > 0);
  } catch (err) {
    toast('Analysis failed: ' + err.message);
  }
}

async function dismissLesson(id) {
  try {
    const res = await api.dismissLesson(id);
    lessons.render(res.lessons || []);
    toast('Lesson dismissed — tuner will ignore it');
  } catch (err) {
    toast('Could not dismiss: ' + err.message);
  }
}

async function restoreLesson(id) {
  try {
    const res = await api.restoreLesson(id);
    lessons.render(res.lessons || []);
    toast('Lesson restored — it will be re-evaluated on the next analysis');
  } catch (err) {
    toast('Could not restore: ' + err.message);
  }
}

/* ================================================================== live */

function applyLiveStatus(status) {
  if (!status) return;
  Object.assign(state.live, {
    attemptId: status.attemptId ?? state.live.attemptId,
    placed: status.placed ?? state.live.placed,
    best: status.best ?? state.live.best,
    nodes: status.nodes ?? state.live.nodes,
    nps: status.nps ?? state.live.nps,
    ms: status.ms ?? state.live.ms,
    paused: !!status.paused,
    config: status.config || state.live.config,
    userDefined: !!status.userDefined,
    source: status.source || state.live.source,
  });
  if (status.board) state.live.board = status.board;
  if (state.mode === 'live' && status.board) {
    boardView.setBoard(status.board);
    ui.boardEmpty.hidden = true;
  }
  updatePauseButton();
  updateBadge();
  if (state.mode === 'live') renderLiveChips();
}

function onLiveFrame(data) {
  if (!data) return;
  state.live.attemptId = data.attemptId;
  state.live.placed = data.placed || 0;
  state.live.best = data.best || 0;
  state.live.nodes = data.nodes || 0;
  state.live.nps = data.nps || 0;
  state.live.ms = data.ms || 0;
  if (data.board) state.live.board = data.board;

  // keep a light trail for the live sparkline
  const last = state.liveSamples[state.liveSamples.length - 1];
  if (!last || data.ms - last.ms > 180) {
    state.liveSamples.push({ ms: data.ms || 0, best: data.best || 0 });
    if (state.liveSamples.length > 600) state.liveSamples.splice(0, 300);
  }

  if (state.mode !== 'live') return;
  ui.boardEmpty.hidden = true;
  if (data.board) boardView.setBoard(data.board);
  renderLiveChips();
  drawSpark(state.liveSamples.map((s) => s.best), 'depth over time (live)');
}

function onAttemptStarted(data) {
  if (!data) return;
  state.live.attemptId = data.attemptId;
  state.live.config = data.config;
  state.live.userDefined = !!data.userDefined;
  state.live.source = data.source;
  state.live.best = 0;
  state.live.placed = 0;
  state.live.nodes = 0;
  state.liveSamples = [];

  // The previous attempt hit its ceiling: wipe the board for the new one.
  if (state.mode === 'live') {
    boardView.clear();
    renderLiveChips();
    drawSpark([], 'depth over time (live)');
  }
  updateBadge();
  if (!state.pendingCustom) settings.setValues(data.config);
  state.pendingCustom = false;
}

function onAttemptFinished(data) {
  if (!data) return;
  if (data.stats) {
    state.stats = data.stats;
    history.setStats(data.stats);
    updateRecord(data.stats.bestDepth, data.stats.bestAttemptId);
  }
  if (data.attempt) {
    history.prepend(data.attempt);
    if (data.attempt.solved) celebrate();
  }
  if (data.optimal) {
    settings.setOptimal(undefined, data.optimal);
  }
  refreshLessons();
}

function renderLiveChips() {
  ui.chipAttempt.textContent = state.live.attemptId ? `#${state.live.attemptId}` : '—';
  setChip(ui.chipDepth, `${state.live.best}`, '/256');
  ui.chipNodes.textContent = formatNumber(state.live.nodes);
  setChip(ui.chipSpeed, formatNumber(state.live.nps), '/s');

  const total = state.meta ? state.meta.cells : 256;
  ui.scrub.max = String(total);
  ui.scrub.value = String(state.live.placed);
  setSliderFill(ui.scrub);
  ui.scrubOut.textContent = `${state.live.placed} / ${total}`;
  ui.modeDetail.textContent = state.live.paused
    ? 'solver paused'
    : `attempt ${state.live.attemptId ?? '—'} · ${fmtDuration(state.live.ms || 0)} elapsed`;
}

function setChip(node, main, suffix) {
  const next = suffix ? `${main}<i>${suffix}</i>` : main;
  if (node.innerHTML !== next) {
    node.innerHTML = next;
    node.classList.remove('bump');
    void node.offsetWidth;
    node.classList.add('bump');
  }
}

function updateRecord(depth, attemptId) {
  if (depth === undefined || depth === null) return;
  ui.chipRecord.textContent = String(depth);
  state.recordAttemptId = attemptId ?? null;
  ui.chipRecordWrap.title = state.recordAttemptId
    ? `All-time best across every attempt — click to replay attempt #${state.recordAttemptId}`
    : 'All-time best across every attempt';
}

/* ================================================================ replay */

async function openReplay(attemptId) {
  try {
    const detail = await api.attempt(attemptId);
    if (!detail.placements || !detail.placements.length) {
      toast(`Attempt #${attemptId} has no recorded placements to replay`);
      return;
    }
    stopPlayback();
    state.mode = 'replay';
    state.replay.attempt = detail;
    state.replay.placements = detail.placements || [];
    state.replay.index = state.replay.placements.length;

    history.setActive(attemptId);
    ui.btnGoLive.hidden = false;
    ui.modePill.className = 'mode-pill replay';
    ui.modePill.innerHTML = `<span class="rec"></span>REPLAY #${attemptId}`;

    const custom = detail.userDefined ? ' · custom settings' : '';
    ui.modeDetail.textContent =
      `${detail.bestDepth}/256 pieces · ${formatNumber(detail.nodes)} steps · ` +
      `${fmtDuration(detail.durationMs)}${custom}`;

    ui.chipAttempt.textContent = `#${attemptId}`;
    setChip(ui.chipDepth, String(detail.bestDepth), '/256');
    ui.chipNodes.textContent = formatNumber(detail.nodes);
    setChip(ui.chipSpeed, formatNumber(detail.nodesPerSec), '/s');

    settings.setValues(detail.config);
    state.pendingCustom = true;   // don't let the next attempt overwrite the view

    enableReplayControls(true);
    ui.scrub.max = String(state.replay.placements.length);
    ui.scrub.value = String(state.replay.index);
    renderReplayFrame();
    drawSpark((detail.samples || []).map((s) => s[2]), `depth over time · attempt #${attemptId}`);
    buildTicks(detail);
    toast(`Replaying attempt #${attemptId} — the solver keeps running`);
  } catch (err) {
    toast(`Could not load attempt: ${err.message}`);
  }
}

function goLive() {
  stopPlayback();
  state.mode = 'live';
  state.replay.attempt = null;
  state.replay.placements = [];
  history.setActive(null);
  ui.btnGoLive.hidden = true;
  ui.modePill.className = 'mode-pill live';
  ui.modePill.innerHTML = '<span class="rec"></span>LIVE';
  enableReplayControls(false);
  boardView.setHighlight(-1);
  ui.scrubTicks.innerHTML = '';
  if (state.live.board) boardView.setBoard(state.live.board, { animate: false });
  else boardView.clear();
  renderLiveChips();
  drawSpark(state.liveSamples.map((s) => s.best), 'depth over time (live)');
  settings.setValues(state.live.config || settings.optimal);
  state.pendingCustom = false;
}

function renderReplayFrame() {
  const total = state.meta ? state.meta.cells : 256;
  const board = new Array(total).fill(-1);
  const { placements, index } = state.replay;
  for (let i = 0; i < index; i++) {
    const [cell, piece, rot] = placements[i];
    board[cell] = (piece << 2) | rot;
  }
  boardView.setHighlight(index > 0 ? placements[index - 1][0] : -1);
  boardView.setBoard(board, { animate: index > 0 });
  ui.boardEmpty.hidden = true;
  ui.scrub.value = String(index);
  setSliderFill(ui.scrub);
  ui.scrubOut.textContent = `${index} / ${placements.length}`;
}

function setReplayIndex(next) {
  const { placements } = state.replay;
  state.replay.index = Math.max(0, Math.min(placements.length, next));
  renderReplayFrame();
}

function togglePlayback() {
  if (state.replay.playing) stopPlayback();
  else startPlayback();
}

function startPlayback() {
  if (state.mode !== 'replay' || !state.replay.placements.length) return;
  if (state.replay.index >= state.replay.placements.length) state.replay.index = 0;
  state.replay.playing = true;
  ui.btnPlay.textContent = '❙❙';
  const tick = () => {
    if (!state.replay.playing) return;
    if (state.replay.index >= state.replay.placements.length) { stopPlayback(); return; }
    setReplayIndex(state.replay.index + 1);
    state.replay.timer = setTimeout(tick, Number(ui.speed.value));
  };
  state.replay.timer = setTimeout(tick, Number(ui.speed.value));
}

function stopPlayback() {
  state.replay.playing = false;
  if (state.replay.timer) clearTimeout(state.replay.timer);
  state.replay.timer = null;
  ui.btnPlay.textContent = '▶';
}

function enableReplayControls(on) {
  for (const node of [ui.scrub, ui.btnPlay, ui.btnStepBack, ui.btnStepFwd]) {
    node.disabled = !on;
  }
}

/** Mark the moments in the placement order where the board got deeper. */
function buildTicks(detail) {
  ui.scrubTicks.innerHTML = '';
  const total = (detail.placements || []).length;
  if (!total) return;
  const marks = [0.25, 0.5, 0.75].map((f) => Math.round(total * f));
  for (const m of marks) {
    const i = document.createElement('i');
    i.style.left = `calc(${(m / total) * 100}% - 1px)`;
    ui.scrubTicks.appendChild(i);
  }
}

/* ============================================================== settings */

function onSettingsChanged(values, isCustom, userEdited) {
  // Only the person at the keyboard can arm the Apply button. The panel also
  // mirrors whatever the running attempt is using, and that must not look like
  // an unsaved edit.
  state.userEdited = !!userEdited;
  ui.btnApply.disabled = !userEdited;
  if (userEdited) {
    ui.cfgBadge.className = 'badge custom';
    ui.cfgBadge.textContent = 'unsaved';
    ui.cfgBadgeText.textContent = isCustom
      ? 'Unsaved changes. Press “Apply & restart” to try them on a new attempt.'
      : 'Back to the optimal values. Press “Apply & restart” to run them now.';
  } else {
    updateBadge();
  }
}

function updateBadge() {
  if (state.userEdited) return;          // keep the "unsaved" hint visible
  const live = state.live;
  if (live.userDefined) {
    ui.cfgBadge.className = 'badge custom';
    ui.cfgBadge.textContent = 'custom';
    ui.cfgBadgeText.textContent =
      'The live attempt is using settings you pinned. Press “Use optimal” to hand control back to the learner.';
  } else if (live.source === 'explore') {
    ui.cfgBadge.className = 'badge explore';
    ui.cfgBadge.textContent = 'exploring';
    ui.cfgBadgeText.textContent =
      'The learner is deliberately trying a variation to gather evidence.';
  } else {
    ui.cfgBadge.className = 'badge optimal';
    ui.cfgBadge.textContent = 'optimal';
    ui.cfgBadgeText.textContent = 'Running the best settings learned so far.';
  }
}

async function applySettings() {
  try {
    state.pendingCustom = false;
    const res = await api.setConfig(settings.getValues());
    state.userEdited = false;
    ui.btnApply.disabled = true;
    settings.setValues(res.config);
    if (state.mode === 'replay') goLive();
    toast('Custom settings applied — new attempt starting');
  } catch (err) {
    toast(`Could not apply settings: ${err.message}`);
  }
}

async function useOptimal() {
  try {
    const res = await api.useOptimal();
    settings.setOptimal(res.config);
    settings.setValues(res.config);
    state.userEdited = false;
    ui.btnApply.disabled = true;
    if (state.mode === 'replay') goLive();
    toast('Back to the learner’s optimal settings', true);
  } catch (err) {
    toast(`Could not switch: ${err.message}`);
  }
}

/* =============================================================== panels */

function wireTabs() {
  for (const tab of document.querySelectorAll('.tab')) {
    tab.addEventListener('click', () => {
      for (const t of document.querySelectorAll('.tab')) t.classList.remove('active');
      for (const b of document.querySelectorAll('.tab-body')) b.classList.remove('active');
      tab.classList.add('active');
      const body = document.getElementById(`tab-${tab.dataset.tab}`);
      if (body) body.classList.add('active');
      // Findings stay as they were until the user asks for a new analysis;
      // the Lessons tab auto-refreshes on switch in case the tuner promoted
      // or retired anything behind the scenes.
      if (tab.dataset.tab === 'lessons') refreshLessons();
    });
  }
}

function wireControls() {
  ui.chipRecordWrap.addEventListener('click', () => {
    if (state.recordAttemptId) openReplay(state.recordAttemptId);
    else toast('No record attempt yet');
  });

  ui.btnGoLive.addEventListener('click', goLive);
  ui.btnPlay.addEventListener('click', togglePlayback);
  ui.btnStepBack.addEventListener('click', () => { stopPlayback(); setReplayIndex(state.replay.index - 1); });
  ui.btnStepFwd.addEventListener('click', () => { stopPlayback(); setReplayIndex(state.replay.index + 1); });

  ui.scrub.addEventListener('input', () => {
    if (state.mode !== 'replay') return;
    stopPlayback();
    setReplayIndex(Number(ui.scrub.value));
  });

  ui.btnApply.addEventListener('click', applySettings);
  ui.btnOptimal.addEventListener('click', useOptimal);

  ui.btnPause.addEventListener('click', async () => {
    try {
      const action = state.live.paused ? 'resume' : 'pause';
      const res = await api.control(action);
      applyLiveStatus(res.status);
      toast(action === 'pause' ? 'Solver paused' : 'Solver resumed');
    } catch (err) {
      toast(`Could not ${state.live.paused ? 'resume' : 'pause'}: ${err.message}`);
    }
  });

  ui.btnSkip.addEventListener('click', async () => {
    try {
      await api.control('skip');
      toast('Wrapping up this attempt…');
    } catch (err) {
      toast(`Could not skip: ${err.message}`);
    }
  });

  ui.btnClear.addEventListener('click', async () => {
    if (!window.confirm('Delete every stored attempt, insight and learned setting?')) return;
    try {
      await api.clearHistory();
      history.replaceAll([], 0);
      history.setStats({ attempts: 0, bestDepth: 0, avgDepth: 0, totalNodes: 0 });
      updateRecord(0, null);
      insights.render({ findings: [], analysis: null, stats: { attempts: 0, bestDepth: 0 } });
      lessons.render([]);
      if (state.mode === 'replay') goLive();
      toast('History cleared — learning starts over');
    } catch (err) {
      toast(`Could not clear: ${err.message}`);
    }
  });

  ui.speed.addEventListener('change', () => {
    if (state.replay.playing) { stopPlayback(); startPlayback(); }
  });

  document.addEventListener('keydown', (ev) => {
    if (ev.target.matches('input, select, textarea')) return;
    if (ev.key === ' ' && state.mode === 'replay') { ev.preventDefault(); togglePlayback(); }
    if (ev.key === 'ArrowLeft' && state.mode === 'replay') { stopPlayback(); setReplayIndex(state.replay.index - 1); }
    if (ev.key === 'ArrowRight' && state.mode === 'replay') { stopPlayback(); setReplayIndex(state.replay.index + 1); }
    if (ev.key === 'l' || ev.key === 'L') goLive();
  });
}

async function loadMoreAttempts() {
  try {
    const res = await api.attempts(60, state.loadedAttempts);
    history.append(res.attempts);
    state.loadedAttempts += (res.attempts || []).length;
    history.total = res.total;
    history.render();
  } catch (err) {
    toast(`Could not load more: ${err.message}`);
  }
}

async function refreshLessons() {
  try {
    const data = await api.lessons();
    lessons.render(data.lessons || []);
  } catch { /* keep the panel as it is */ }
}

function updatePauseButton() {
  const paused = state.live.paused;
  ui.pauseIcon.textContent = paused ? '▶' : '❙❙';
  ui.pauseText.textContent = paused ? 'Resume' : 'Pause';
  ui.btnPause.classList.toggle('btn-live', paused);
  if (state.mode === 'live') {
    ui.modePill.className = paused ? 'mode-pill paused' : 'mode-pill live';
    ui.modePill.innerHTML = `<span class="rec"></span>${paused ? 'PAUSED' : 'LIVE'}`;
  }
}

/* ============================================================== sparkline */

function drawSpark(values, caption) {
  const canvas = ui.spark;
  const dpr = Math.min(window.devicePixelRatio || 1, 2);
  const width = canvas.clientWidth || 600;
  canvas.width = Math.floor(width * dpr);
  canvas.height = Math.floor(54 * dpr);
  const ctx = canvas.getContext('2d');
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  ui.sparkCaption.textContent = caption || '';

  if (!values || values.length < 2) return;
  const max = Math.max(256, ...values);
  const stepX = canvas.width / (values.length - 1);

  const grad = ctx.createLinearGradient(0, 0, canvas.width, 0);
  grad.addColorStop(0, '#9b6bff');
  grad.addColorStop(0.6, '#43b8ff');
  grad.addColorStop(1, '#4ddb8b');

  ctx.beginPath();
  ctx.moveTo(0, canvas.height);
  values.forEach((v, i) => {
    const x = i * stepX;
    const y = canvas.height - (v / max) * (canvas.height - 4 * dpr) - 2 * dpr;
    ctx.lineTo(x, y);
  });
  ctx.lineTo(canvas.width, canvas.height);
  ctx.closePath();
  const fill = ctx.createLinearGradient(0, 0, 0, canvas.height);
  fill.addColorStop(0, 'rgba(67,184,255,0.34)');
  fill.addColorStop(1, 'rgba(67,184,255,0.01)');
  ctx.fillStyle = fill;
  ctx.fill();

  ctx.beginPath();
  values.forEach((v, i) => {
    const x = i * stepX;
    const y = canvas.height - (v / max) * (canvas.height - 4 * dpr) - 2 * dpr;
    if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
  });
  ctx.strokeStyle = grad;
  ctx.lineWidth = 2 * dpr;
  ctx.lineJoin = 'round';
  ctx.stroke();
}

/* ================================================================ chrome */

function buildLegend() {
  const frag = document.createDocumentFragment();
  const title = document.createElement('span');
  title.className = 'lg-title';
  title.textContent = 'edge colours';
  frag.appendChild(title);
  PALETTE.forEach((colour, index) => {
    const swatch = document.createElement('i');
    swatch.style.background = colour;
    swatch.title = index === 0 ? 'grey — outer border' : `colour ${index}`;
    frag.appendChild(swatch);
  });
  const note = document.createElement('span');
  note.textContent = 'grey = board border · tiles must match along every shared edge';
  frag.appendChild(note);
  ui.legend.appendChild(frag);
}

let toastTimer = null;
function toast(message, gold = false) {
  ui.toast.textContent = message;
  ui.toast.className = `toast show${gold ? ' gold' : ''}`;
  if (toastTimer) clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { ui.toast.className = 'toast'; }, 3600);
}

function setSliderFill(input) {
  const min = Number(input.min || 0);
  const max = Number(input.max || 100);
  const span = max - min;
  const pct = span > 0 ? ((Number(input.value) - min) / span) * 100 : 0;
  input.style.setProperty('--fill', `${pct}%`);
}

/* A small burst of puzzle-coloured confetti when an attempt actually solves. */
function celebrate() {
  const canvas = ui.confetti;
  const dpr = Math.min(window.devicePixelRatio || 1, 2);
  canvas.width = window.innerWidth * dpr;
  canvas.height = window.innerHeight * dpr;
  const ctx = canvas.getContext('2d');
  const bits = [];
  for (let i = 0; i < 220; i++) {
    bits.push({
      x: Math.random() * canvas.width,
      y: -Math.random() * canvas.height * 0.5,
      s: (5 + Math.random() * 8) * dpr,
      vy: (2.2 + Math.random() * 4) * dpr,
      vx: (Math.random() - 0.5) * 2.4 * dpr,
      rot: Math.random() * Math.PI,
      vr: (Math.random() - 0.5) * 0.24,
      c: PALETTE[1 + Math.floor(Math.random() * (PALETTE.length - 1))],
    });
  }
  const until = performance.now() + 6000;
  (function frame(now) {
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    for (const b of bits) {
      b.x += b.vx; b.y += b.vy; b.rot += b.vr;
      if (b.y > canvas.height + 40) b.y = -20;
      ctx.save();
      ctx.translate(b.x, b.y);
      ctx.rotate(b.rot);
      ctx.fillStyle = b.c;
      ctx.fillRect(-b.s / 2, -b.s / 2, b.s, b.s * 0.62);
      ctx.restore();
    }
    if (now < until) requestAnimationFrame(frame);
    else ctx.clearRect(0, 0, canvas.width, canvas.height);
  })(performance.now());
  toast('🎉 A complete solution was found!', true);
}
