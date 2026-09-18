/* Thin wrapper around the REST endpoints plus the Server-Sent Events stream. */

async function getJson(url) {
  const res = await fetch(url, { headers: { Accept: 'application/json' } });
  if (!res.ok) throw new Error(`${res.status} ${res.statusText} for ${url}`);
  return res.json();
}

async function postJson(url, body) {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body || {}),
  });
  if (!res.ok) throw new Error(`${res.status} ${res.statusText} for ${url}`);
  return res.json();
}

export const api = {
  bootstrap: () => getJson('/api/bootstrap'),
  attempts: (limit = 80, offset = 0) =>
    getJson(`/api/attempts?limit=${limit}&offset=${offset}`),
  attempt: (id) => getJson(`/api/attempts/${id}`),
  insights: () => getJson('/api/insights'),
  findings: () => getJson('/api/findings'),
  lessons: () => getJson('/api/lessons'),
  status: () => getJson('/api/status'),
  setConfig: (config) => postJson('/api/config', { config }),
  useOptimal: () => postJson('/api/config/optimal', {}),
  control: (action) => postJson('/api/control', { action }),
  clearHistory: () => postJson('/api/history/clear', {}),
  analyze: () => postJson('/api/analyze', {}),
  dismissLesson: (id) => postJson(`/api/lessons/${encodeURIComponent(id)}/dismiss`, {}),
  restoreLesson: (id) => postJson(`/api/lessons/${encodeURIComponent(id)}/restore`, {}),
};

/**
 * Subscribe to the live event stream.
 *
 * EventSource reconnects on its own, so the only thing we add is a connection
 * state callback so the UI can show a red dot while the server is away.
 */
export function openStream(handlers) {
  let source = null;
  let closed = false;

  function connect() {
    source = new EventSource('/api/stream');

    source.addEventListener('open', () => handlers.onOpen && handlers.onOpen());
    source.addEventListener('error', () => {
      handlers.onError && handlers.onError();
      // EventSource retries automatically; if the server is gone for good the
      // browser keeps trying quietly, which is what we want.
    });

    for (const name of ['hello', 'meta', 'live', 'state', 'attempt_started',
                        'attempt_finished', 'attempt_aborted', 'analysis_complete',
                        'error']) {
      source.addEventListener(name, (ev) => {
        if (closed) return;
        let data = null;
        try { data = JSON.parse(ev.data); } catch { data = null; }
        const fn = handlers[name];
        if (fn) fn(data);
      });
    }
  }

  connect();

  return {
    close() {
      closed = true;
      if (source) source.close();
    },
  };
}
