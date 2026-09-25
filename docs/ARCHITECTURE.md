# Architecture

## The shape of it

```
 browser ──HTTP──► Python server ──spawn──► Java engine (one per attempt)
    ▲                   │   ▲                      │
    └────SSE────────────┘   └──── JSONL on stdout ──┘
                            │
                            ▼
                    data/eternity2.sqlite
```

Three processes, three jobs, chosen so each part does only what it is good at:

| Part | Language | Responsibility |
|---|---|---|
| **Engine** | Java | The search. Fast, single-purpose, no I/O beyond a line of JSON now and then. |
| **Server** | Python (stdlib) | Orchestration, persistence, learning, HTTP/SSE. |
| **Interface** | Vanilla JS | Rendering, replay scrubbing, controls. |

There are no third-party dependencies anywhere. The Java side compiles with a
bare `javac`; the Python side imports only the standard library; the browser side
uses plain ES modules with no build step.

### Why the solver is a separate process

* An attempt is a natural process boundary: it starts, it runs to its budget, it
  dies. Killing a process is a reliable way to abandon an attempt, and a crash
  can never take the web app down with it.
* The search loop stays a tight Java loop with no web framework anywhere near it.
* The protocol (below) is tiny and text-based, so the engine can be run by hand
  for debugging: `java -cp java/classes app.Engine --nodeBudget=300000`.

---

## Directory layout

```
eternity2-lab/
├── java/
│   ├── src/core/        the solver, unchanged in substance from the standalone version
│   │   ├── Pieces.java        the 256 pieces (board design — never modified)
│   │   ├── Sides.java         packed side colours + rotation arithmetic
│   │   ├── Instance.java      a puzzle instance (board, pieces, fixed placements)
│   │   ├── SolverConfig.java  every tunable decision, with the old hard-coded values as defaults
│   │   ├── MrvSolver.java     engine 1: MRV ordering over a bitset piece pool
│   │   ├── ScanSolver.java    engine 2: fixed fill order + two-colour candidate index
│   │   ├── FillOrder.java     the fixed cell orders, and the measures that judge them
│   │   ├── Search.java        what both engines expose to app.Engine and the lab
│   │   ├── Solver.java        the earlier row-major solver, kept as a baseline
│   │   ├── RefSolver.java     deliberately naive solver, used only to cross-check the fast one
│   │   ├── Validator.java     independent board checker
│   │   ├── Generator.java     random solvable instances, for the tests
│   │   ├── Bench.java         benchmarks
│   │   └── Puzzle.java        the original frame-by-frame prototype (historical reference)
│   ├── src/app/Engine.java    the JSONL streaming wrapper
│   ├── test/core/             the solver test suite (1368 checks)
│   └── classes/               build output
├── server/
│   ├── app.py           HTTP + SSE server
│   ├── supervisor.py    runs attempts back to back, owns the live state
│   ├── db.py            SQLite persistence
│   ├── tuner.py         scoring, the bandit, insights
│   ├── schema.py        the single source of truth for all settings
│   ├── gen_docs.py      regenerates docs/SETTINGS.md from schema.py
│   └── test_server.py   the server test suite (37 tests)
├── web/
│   ├── index.html
│   ├── css/style.css
│   └── js/              app, board renderer, settings, history, insights, api, palette
├── data/                eternity2.sqlite lives here
└── docs/
```

---

## The engine protocol

One attempt = one `app.Engine` process. Configuration arrives as
`--key=value` arguments; results leave as one JSON object per line on stdout.

| Event | When | Payload |
|---|---|---|
| `meta` | once, at startup | `n`, `cells`, `variants`, `colours`, `pieces` (all 256 edge tuples), `fixed`, `config` |
| `frame` | ~9×/second | `ms`, `nodes`, `nps`, `placed`, `best`, `restarts`, `board` |
| `best` | whenever the record improves | `ms`, `nodes`, `placed`, `edges`, `breaks`, `board` |
| `restart` | on each restart | `ms`, `nodes`, `index` |
| `end` | once, at exit | `ms`, `nodes`, `nps`, `best`, `edges`, `breaks`, `solved`, `valid`, `status`, `restarts`, `workers`, `order`, `samples`, `board` |

`board` is 256 integers, one per cell: `-1` for empty, otherwise
`(pieceId << 2) | rotation`. The UI unpacks that and rotates the piece's edge
tuple to draw it.

`edges` is what the board actually scores: the internal adjacencies whose two
sides agree, out of the 480 the 16x16 board has (the grey rim is not scored).
`Validator.matchedEdges` counts it, and the solver only does so when it records
a new best board, so the search loop never pays for it.

`breaks` is how many interior edges the engine mismatched on purpose. Only the fixed-scan engine's
edge slipping ever reports more than zero, and a board with any is never `solved`: that word keeps
meaning a validated 256-piece board scoring 480. The supervisor refuses a `solved` claim that
arrives with breaks and writes the refusal to stderr, so the two layers have to agree.

`order` is the payload that makes replay possible: the cells of the deepest board
**in the order they were placed**, as `[cell, piece, rotation]` triples. The
solver maintains a placement stack, so capturing the order costs a single array
copy whenever the record improves.

`status` is one of `solved`, `budget` (hit its step budget), `exhausted`
(searched everything it could reach), `stopped` (a controller asked it to stop)
or `error: …`.

With `--watchStdin=1` the engine stops cleanly when it reads `stop` on stdin, or
when stdin closes. The server passes this flag and holds the pipe open, so an
engine is never left orphaned if the server dies — and because the engine still
emits its `end` record on the way out, no attempt's results are ever lost.

With `engine=scan`, the process runs a `core.PortfolioSearch` of one
independently-seeded `ScanSolver` per available core instead of a single one,
sharing the attempt's `nodeBudget` between them, and reports whichever finds
the best board — see `docs/SOLVER.md`. The one-process-per-attempt model above
is unchanged: `--workers` defaults to
`Runtime.getRuntime().availableProcessors()`, and every event in the table is
emitted exactly the same, just possibly describing a different worker's board
than the previous one. `nodes` and `nps` in every event count the whole
attempt, not the reporting worker, so they never jump at the end. The `end`
record's `workers` says how many shared the budget, and the database keeps it
in `attempts.workers`, because a recorded seed reproduces only at the same
count; it is NULL on attempts recorded before the engine reported it.
`--workers=1` forces the plain single-descent path; `engine=mrv` attempts are
never parallelised this way and report `workers` as 1.

### Sampling, not streaming every step

The solver runs at roughly 1–16 million steps per second. Reporting each one
would be absurd, so the engine reports:

* a `best` event each time the record improves (at most 256 per attempt), and
* a `frame` roughly every 110 ms, showing the board as it actually is at that
  instant — including mid-backtrack, which is what makes the live view feel
  alive.

Progress samples for the chart are kept in a fixed-size buffer that halves its
own resolution when it fills, so an attempt of any length produces at most ~1200
points.

---

## The server

### Supervisor

`supervisor.py` owns the live state and the attempt loop:

1. Ask the tuner for a configuration, or use one the user pinned.
2. Insert a `running` row in `attempts`.
3. Spawn the engine, read its JSONL, keep the newest board in memory, fan every
   event out to connected browsers.
4. On `end`: write the results, placement order and samples; ask the tuner to
   update itself; publish `attempt_finished`; loop.

Pausing, skipping and changing settings all work by asking the current engine to
stop and letting the loop start the next attempt.

### Event fan-out

A small pub/sub broker holds one bounded queue per connected browser. If a client
is too slow to keep up, its oldest frame is dropped rather than blocking the
solver or growing memory without limit.

### HTTP API

| Method | Path | Purpose |
|---|---|---|
| GET | `/` plus `/css/*`, `/js/*` | the interface |
| GET | `/api/bootstrap` | everything the page needs on first load |
| GET | `/api/stream` | Server-Sent Events |
| GET | `/api/attempts?limit&offset` | paged history |
| GET | `/api/attempts/<id>` | one attempt including its placement order |
| GET | `/api/insights` | lessons, per-setting breakdown, learned optimum |
| GET | `/api/status` | the live snapshot |
| POST | `/api/config` | pin a user configuration and restart |
| POST | `/api/config/optimal` | return control to the learner |
| POST | `/api/control` | `{"action": "pause" \| "resume" \| "skip"}` |
| POST | `/api/history/clear` | delete all stored attempts and lessons |

SSE events: `hello`, `meta`, `live`, `state`, `attempt_started`,
`attempt_finished`, `attempt_aborted`, `error`.

`hello` fires once per connection, including every reconnect -- after a
server restart, or any dropped connection -- and is a full resync, not just
the live attempt: `status`, `meta`, `stats` and `optimalDetails`, the same
fields `attempt_finished` sends. Without `stats`/`optimalDetails` here, a
client that reconnected without yet seeing a fresh `attempt_finished` would
keep showing the record and the settings panel's "optimal" figures from
before the restart indefinitely.

---

## Database schema

SQLite in WAL mode, one file, all access serialised through a single guarded
connection. Write volume is a few hundred rows per attempt, so this is simpler
and safer than a pool.

```sql
attempts(id, started_at, finished_at, status, solved, valid, best_depth,
         matched_edges, breaks, nodes, duration_ms, nodes_per_sec, restarts,
         user_defined, source, score, config_json)

placements(attempt_id, seq, cell, piece, rot)      -- the replay timeline
samples(attempt_id, seq, ms, nodes, best)          -- the progress chart
optimal(setting, value_json, mean_score, support, updated_at, reason)
insights(id, created_at, setting, headline, detail, best_value, support, lift)
meta(key, value)                                   -- schema version, cached board meta
```

`config_json` stores the **complete** configuration of every attempt, which is
what lets the History tab tell you exactly what produced a result months later.

Two housekeeping rules keep the history honest:

* Runs that never searched (`nodes = 0`) are deleted rather than displayed.
* Runs the user cut short are stored with status `aborted` and excluded from
  learning, because their settings never got a fair budget.

`matched_edges` is NULL on attempts recorded before the engine reported it. A
depth cannot be converted into an edge count after the fact, so those rows are
left out of the tuner's statistics rather than counted as a board that matched
nothing. A file from an older version gains the column on open.

`breaks` is how many of the best board's edges edge slipping deliberately
mismatched (0 on every attempt that never slips), stored the same way and with
the same NULL-on-upgrade convention as `matched_edges`.

The UI deliberately keeps `best_depth` (pieces placed, out of 256) as the
headline number everywhere -- the header, History, the all-time record --
because that is what someone watching the board actually understands; an edge
count means little without already knowing how the puzzle is scored. `breaks`
rides along next to the piece count as a small "N wrong" caveat instead, since
slipping can let a board place every piece while some of them don't really fit
their neighbour. `matched_edges` and `Db.stats()`'s `bestMatchedEdges` /
`bestEdgesAttemptId` stay in the data model and the API for anyone who wants
the puzzle's own scoring measure, but nothing in the UI is built around them.

---

## The interface

No framework, no bundler. `index.html` loads one ES module, which composes five
others.

| Module | Responsibility |
|---|---|
| `app.js` | state machine for LIVE vs REPLAY, wiring, chips, sparkline, confetti |
| `board.js` | canvas renderer: four triangles per tile, pop-in animation, rings, broken-edge seams |
| `settings.js` | builds every control from the server's schema |
| `history.js` | attempt cards and their pills |
| `insights.js` | lessons and diverging bar charts |
| `api.js` | fetch wrappers and the `EventSource` subscription |
| `palette.js` | the 23 edge colours and the rotation helper |

### Why LIVE and REPLAY are independent

Replay is a pure client-side function of a placement list:

```js
board = new Array(256).fill(-1);
for (let i = 0; i < index; i++) {
  const [cell, piece, rot] = placements[i];
  board[cell] = (piece << 2) | rot;
}
```

So scrubbing costs nothing on the server, needs no round trips, and cannot
interfere with the search. Live events keep arriving while you replay and the
newest board is remembered, which is why **Back to live** is instant.

### Adding a setting

One place:

1. Add an entry to `SETTINGS` in `server/schema.py`. If the setting only
   reaches the search under some other setting's value, declare that with
   `activeWhen` so the learner ignores attempts it could not have changed.
2. Handle the key in `SolverConfig.apply()` and use it in the engine it
   belongs to. If only one engine reads it, give it
   `activeWhen: {"key": "engine", ...}` so the other engine's attempts are not
   counted as evidence about it.
3. `python3 server/gen_docs.py`.

The control appears in the UI, the learner starts tuning it, it is stored with
every attempt, and it gets documented — no other changes needed.

---

## The Analyzer

Triggered explicitly by the Insights tab's **Run analysis** button. Lives in
`server/analytics.py`.  Every analysis function gets the attempts on file and
returns zero or more `Finding` objects.  The runner filters by strength and
support, deduplicates by stable id, and returns the strongest-first list.

### What a Finding is

```python
Finding(
    id='setting_cellOrder',          # stable across runs -> streak tracking
    group='settings',                # UI grouping: settings | board | dynamics
    title='Cell order: ...',
    paragraph='...plain-English explanation...',
    strength=4.7,                    # effect / stderr, clamped at 30 for display
    support=88,                      # attempts this finding is based on
    visual={'kind': 'diverging_bars', 'rows': [...]},
    related=[attempt_id, ...],       # optional back-link
    rule={'kind': 'prefer_value',    # if set, the finding can graduate to a lesson
          'setting': 'cellOrder',
          'value': 'mrv',
          'confidence': 0.8},
)
```

### The analyses

| # | Analysis | Group | Visual |
|---|---|---|---|
| 1 | **Setting effects** — best vs worst value of each setting at *equal attempt length* | settings | diverging bars |
| 2 | **Setting interactions** — pair of values that beats the sum of their marginals | settings | interaction table |
| 3 | **Winning profile** — the single configuration outperforming the field | settings | highlighted card |
| 4 | **Depth distribution** — histogram of best depths with mode detection (the "wall") | dynamics | histogram |
| 5 | **Budget returns** — average depth vs attempt length, with a diminishing-returns knee | dynamics | line |
| 6 | **Restart effect** — correlation of restart count with depth, within-budget | dynamics | scatter |
| 7 | **Learning trajectory** — rolling mean depth over attempt id | dynamics | line |
| 8 | **Time to depth** — median search steps to first reach 50/100/150/200 pieces | dynamics | log-y line |
| 9 | **Cell hotness** — 16×16 heatmap of cells in the deepest quartile vs shallowest | board | heatmap |
| 10 | **Placement signature** — do the deepest attempts place border tiles first? | board | two bars |
| 11 | **Outlier attempts** — individual runs >= 2σ from the mean at their budget | dynamics | attempt chips |

Strength is comparable across analyses.  All use the same unit ("effect size
divided by standard error"), clamped at 30 in the reported value so the UI's
three-band level label stays meaningful (`strong` / `moderate` / `mild`).

### Removing patterns that stopped holding

On every analyze run the server calls `db.record_analysis`, which **replaces**
the stored finding set with the new one.  Findings whose id is not in the new
set are deleted: the user sees them disappear next time the Insights tab
renders.  Findings whose id *is* in the new set keep their original
`discoveredAt` timestamp and have `rescanStreak` bumped, which is what the
Lesson layer reads.

---

## Lessons

A Lesson is a rule the application has concluded to be durable enough to let
it guide future attempts.  Lives in `server/lessons.py`.

### Lifecycle

```
   Finding with rule + strength >= LESSON_STRENGTH
                  │
                  ▼
        streak++                                retire
    watching -----------> ACTIVE <------> RETIRED
            LESSON_STREAK       RETIRE_STREAK
                                (misses in a row)
```

* A finding that carries a `rule` *and* clears the lesson strength threshold
  has its rule key counted toward a streak.  After `LESSON_STREAK=3`
  consecutive analyses the lesson is promoted to `active` and stored with an
  `accepted_at` timestamp.
* An active lesson that fails to appear strongly in `RETIRE_STREAK=2`
  consecutive analyses is retired.  It stays in the database with
  `retired_at` set, so the Lessons page can still show "we used to believe
  this, here is what changed".
* The user can dismiss an active lesson from the UI.  Dismissal persists
  across future rescans until the user restores it.

### How lessons guide future attempts

The tuner owns exploration.  It does **not** take orders.  Every time it
evaluates an arm for UCB1 selection it calls
`LessonManager.bonus_for(setting, arm)`, which adds up to
`BONUS_SCALE * confidence` edges' worth of score to arms matching any
active lesson.  That is meaningful (a typical effect size is only a few
edges) but not absolute, so:

* If the lesson is *also* supported by the live mean score, the tuner will
  pick the endorsed value.
* If reality changes and the opposing value starts scoring much better, the
  tuner will still find it, the finding will fade, and the lesson will
  retire itself.

### Configuration

Thresholds are tuneable (no pun intended) in `server/lessons.py`:
`LESSON_STRENGTH`, `LESSON_STREAK`, `RETIRE_STRENGTH`, `RETIRE_STREAK`,
`BONUS_SCALE`.  The tests drive each transition so changes are immediately
visible as pass/fail.
