# Eternity II Lab

A web application that solves — or rather, relentlessly *tries* to solve — the
Eternity II puzzle in front of you, remembers every attempt, lets you replay any
of them, lets you tinker with the algorithm's decisions, and learns from its own
results to tune itself.

Eternity II is a 16×16 edge-matching puzzle with 256 pieces and one mandatory
hint piece (139 at row 8, column 7). It carried a $2,000,000 prize and has
**never been solved**. This app is therefore a laboratory, not a victory lap:
the interesting part is watching how different strategies behave.

| | |
|---|---|
| **Replay** — scrub through the exact order the pieces went down | **Insights** — one card per significant pattern the Analyzer finds |
| **Lessons** — patterns strong enough to guide future attempts | **Settings** — 14 live-tunable controls with learned defaults |

---

## 1. Requirements

| | |
|---|---|
| **Java** | JDK 8 or newer (17+ recommended). `javac` must be on `PATH`, or set `JAVA_HOME`. |
| **Python** | 3.8 or newer. |
| **Packages** | **None.** No pip install, no npm, no virtualenv. Standard libraries only. |
| **Browser** | Any current Chrome, Firefox, Edge or Safari. |

Check quickly:

```sh
javac -version     # e.g. javac 17.0.10
python3 --version  # e.g. Python 3.11.4
```

## 2. Run it

```sh
unzip eternity2-lab.zip
cd eternity2-lab

sh run.sh                     # compiles on first use, then starts the server
```

Windows:

```bat
run.bat
```

Then open **<http://localhost:8420>**.

The solver starts immediately. Within a few seconds the board begins filling and
the first attempt appears in the History tab.

Useful variations:

```sh
sh build.sh                          # compile only
sh test.sh                           # run the Java solver test suite (1119 checks)
python3 server/test_server.py        # run the server test suite (45 tests)

python3 server/app.py --port 9000    # different port
python3 server/app.py --host 0.0.0.0 # expose on the LAN (see Security below)
python3 server/app.py --no-solve     # serve the UI without starting the solver
```

Stop with `Ctrl-C`. Everything is already saved.

## 3. What you are looking at

### The board

Each tile is drawn the way the printed pieces look: a square cut into four
triangles by its diagonals, one colour per edge. Two tiles may only sit next to
each other if the colours on the edge they share are identical. **Grey** means
"this edge faces off the board", so grey may only appear around the rim.

The gold ring marks the mandatory hint piece. In a replay, the white ring marks
the piece that was placed most recently.

### LIVE vs REPLAY

These are two independent views of the same board widget.

* **LIVE** mirrors the solver as it works. The scrub bar is a read-only progress
  meter. When an attempt hits its step budget the board clears and the next
  attempt begins.
* **REPLAY** is driven entirely from a finished attempt's stored placement list.
  Drag the scrub bar to move forwards and backwards through the exact order the
  pieces went down, or press play to watch it.

Opening a replay **never disturbs the live search.** The replay data is fetched
once and stepped through in your browser, while the solver keeps running on the
server. Press **Back to live** (or the `L` key) to snap to the current board.

### Keyboard

| Key | Action |
|---|---|
| `Space` | play / pause the replay |
| `←` `→` | step one piece back / forward |
| `L` | back to live |

### Header chips

| Chip | Meaning |
|---|---|
| **Attempt** | which attempt is running or being replayed |
| **Depth** | most pieces placed at once in this attempt, out of 256 |
| **Nodes** | search steps explored (a step = choosing a piece for a square) |
| **Speed** | search steps per second |
| **Record** | best depth ever reached across all attempts |

## 4. The three tabs

### ⚙ Settings

14 controls covering everything the solver used to decide for itself: how it
picks the next square, how it orders candidate pieces, how hard it looks ahead
for dead ends, whether and when it restarts, and how long an attempt lasts.

Every control shows its current value, a plain-English explanation, what happens
at each extreme, and where the learner's optimum currently sits (★, plus a
**snap back** link).

* **Apply & restart** — adopt your values and start a fresh attempt with them.
  From then on every attempt uses your settings and is tagged **CUSTOM** in the
  history, until you press…
* **✨ Use optimal** — hand control back to the learner, which returns to the
  best settings it has measured and resumes exploring around them.

Full reference: **[docs/SETTINGS.md](docs/SETTINGS.md)**.

### 🕘 History

One card per finished attempt, newest first, each clickable to replay. Cards
show depth reached, steps used, duration, speed, how the attempt ended, and a
summary of the settings that produced it. The pills tell you where the settings
came from:

| Pill | Meaning |
|---|---|
| **CUSTOM** | you pinned these settings by hand |
| **EXPLORE** | the learner deliberately varied something to gather evidence |
| **OPTIMAL** | the learner's current best-known settings |
| **RECORD** | this attempt holds the all-time depth record |
| **SOLVED** | a complete, validated 256-piece solution (not yet observed by anyone) |

**Clear history** wipes every attempt, insight and learned value, and the
learner starts from the measured defaults again.

### 💡 Insights

The Insights tab is a small analytics engine over every attempt on file. The
summary strip shows the totals (attempts, record, average depth, solve count),
and the **Run analysis** button kicks off a sweep that reports *every* pattern
it can find. Each finding gets its own card with a title, a visual and an
explanatory paragraph.

The engine runs eleven analyses across three families:

* **Settings** — which value of each setting wins (adjusted so attempt length
  cannot distort it), interactions between pairs of settings, and the single
  configuration outperforming the field.
* **Board** — a 16×16 heatmap of which cells the deepest attempts agree on,
  and whether the deepest runs build the border first.
* **Run dynamics** — where the attempts stall (the "wall" histogram), whether
  longer attempts still pay off, whether restarts actually help, whether the
  tuner has been improving over time, how quickly each successive piece gets
  harder, and individual attempts that sit far from the pack.

Every finding has a **strength** (a z-like score reported as "mild",
"moderate" or "strong") and a **support** count. Re-running the analysis
refreshes every card: findings that stopped holding up are simply removed.

### 📖 Lessons

Patterns that keep showing up across successive analyses graduate from
Insights to Lessons. A lesson is a rule the app has concluded to be durable
enough to **guide future attempts**: the automatic tuner adds a soft score
bonus to any arm that matches an active lesson, so exploration is gently
focused on what has proven to work without being forced into a dead end.

The Lessons tab has three groups:

* **Active** — guiding future attempts right now.
* **Watching** — seen recently, not yet consistent enough to promote.
* **Retired** — once active but no longer supported by the data.

You can dismiss any lesson to make the tuner ignore it, or restore a
dismissed one later. Dismissal is persistent. All lesson state lives in the
SQLite file, so the learner picks up where it left off after a restart.

*Comparisons are fair on purpose.* Attempt length is itself a setting, so a
long run reaches deeper than a short one no matter what else is set.
Comparing raw depths across budgets would make any setting paired with long
attempts look brilliant. The analyser therefore compares each setting **only
against attempts of the same length**, and the charts show that adjusted
figure: *pieces better or worse than a typical run of the same length*.

Settings that only apply in some modes are judged the same way. Restart growth
is read by the geometric policy alone, so attempts under any other policy are
not counted as evidence about it, and the support figure beside a finding is
the number of attempts the setting could really have changed.

## 5. How the learning works

Two layers, kept deliberately separate:

**The tuner** runs in the background after every attempt. It treats each
setting as an independent multi-armed bandit over its discrete choices,
scores attempts by **matched edges out of 480** — the measure Eternity II
results are quoted in — with a mild efficiency bonus and a large bonus for a
real solve, and uses **UCB1** to pick the value for the next
automatic attempt — so untried values get tried and promising ones get
repeated. An occasional random nudge stops it settling too early.
**Optimal settings** (the button and the UI defaults) is the greedy view of
the same data: the best mean score with at least 3 attempts of support,
otherwise the measured cold-start default.

**The analyser** runs only when you ask (the Run analysis button on the
Insights tab). It applies eleven different analyses over every finished
attempt — not only per-setting effects but also pairwise interactions, the
winning configuration, the depth histogram, budget returns, restart
correlation, learning trajectory, time-to-depth, a cell-hotness heatmap,
placement-order signatures and outliers. Findings above a strength cutoff
each get a card with a visual.

**Lessons** bridge the two: a finding whose rule stays strong across
several analyses is promoted to a lesson, which adds a soft UCB bonus to
matching arms in the tuner. Exploration is preserved; the bonus is
proportional to confidence, and if reality changes the lesson's underlying
finding will fade, which retires the lesson automatically. You can dismiss
or restore any lesson by hand.

Attempts you cut short (by changing settings, skipping, or pausing) are
marked **stopped early** and deliberately excluded from learning, because
their settings never got a fair run.

Everything is a simple, explainable model on purpose. Every number in the
Insights tab comes straight from the stored attempts, so you can always see
why a value was chosen.

## 6. Where the data lives

Everything is in one SQLite file: **`data/eternity2.sqlite`**.

Stop the app and start it again and it picks up exactly where it left off, with
all attempts, placement orders, progress curves, insights and learned settings
intact. Delete the file to start completely fresh. Inspect it with any SQLite
tool:

```sh
sqlite3 data/eternity2.sqlite "SELECT id, best_depth, nodes, user_defined FROM attempts ORDER BY best_depth DESC LIMIT 5;"
```

Tables: `attempts`, `placements`, `samples`, `optimal`, `insights`, `meta`.
See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the schema.

## 7. Documentation

| File | Contents |
|---|---|
| [docs/SETTINGS.md](docs/SETTINGS.md) | every option, what it does, what the extremes do (generated from the schema) |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | how the parts fit together, the engine protocol, the database schema, the HTTP API |
| [docs/SOLVER.md](docs/SOLVER.md) | the solver algorithm, why it is built this way, and the benchmark numbers behind the defaults |
| [docs/TESTING.md](docs/TESTING.md) | what the two test suites cover and how to run them |

## 8. Troubleshooting

**"javac not found" / "No 'java' on PATH"**
Install a JDK (not just a JRE) and either add it to `PATH` or set `JAVA_HOME`.
A JRE alone can run the app only if `java/classes` is already compiled.

**"Compiled Java classes not found"**
Run `sh build.sh` (or `build.bat`) first. `run.sh` normally does this for you.

**The board never appears / the dot in the corner is red**
The browser cannot reach the event stream. Confirm the terminal still shows the
server running, and that nothing else is on port 8420 (`--port 9000` to move).

**Port already in use**
`python3 server/app.py --port 9000`.

**Attempts finish instantly with tiny depths**
Check the Settings tab: `Choices per square` near 1, or a very small
`Attempt length`, will do exactly that. Press **Use optimal** to recover.

**The solver seems slow**
Speed depends on the settings. `Look ahead = Whole board` with
`Cell order = Most constrained` does far more work per step but explores a much
better search tree. Both numbers are shown in the header.

**I want the CPU back**
Press **Pause** in the header. The UI and replays keep working.

## 9. Security note

The server binds to `127.0.0.1` by default, so only your own machine can reach
it. It has no authentication and is not hardened for hostile networks — if you
pass `--host 0.0.0.0` to share it, put it behind something that is.

## 10. Licence and credits

The Eternity II puzzle and its artwork are property of their respective owners;
this project contains only the numeric edge data needed to reason about the
puzzle, in `java/src/core/Pieces.java`. The board rules, the piece set and the
fixed hint piece are faithful to the original game and are never modified by any
setting.

Solver, server and interface written for this project. The original
frame-by-frame prototype is preserved in `java/src/core/Puzzle.java` for
reference; `docs/SOLVER.md` explains what replaced it and why.
