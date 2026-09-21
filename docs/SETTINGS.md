# Settings reference

Every strategic decision the solver used to make by hard-coding is exposed here
as a control you can change while it runs. Each attempt stores the exact values
it used, so the History tab always tells you what produced a given result.

**This file is generated from `server/schema.py` by `server/gen_docs.py`.**
Do not edit it by hand; change the schema and regenerate.

## How to read this

* **Default** is the cold-start value, chosen from the benchmarking described in
  `docs/SOLVER.md`. Before you touch anything, the UI shows these, and the
  "Use optimal" button returns to whatever the learner currently believes is
  best.
* **Extremes** describe what happens at each end of a slider. Both ends are
  reachable on purpose: part of the fun is watching a setting fail.
* **Learned** says whether the built-in learner is allowed to tune the value.
  The random seed is excluded, because it is noise rather than strategy.
* **Only has an effect when ...** marks a setting that depends on another one.
  Attempts where the condition did not hold are not counted as evidence about
  it, so what the Insights tab says about it is based on the runs it could
  really have changed.

## A note on safety

No setting can break the rules of the puzzle. Board size, the piece set and the
mandatory hint piece (139 at row 8, column 7) are fixed, and every placement is
still checked for matching edges. The worst a setting can do is make the search
slow, or make it incomplete so it can never find a full solution --
`Choices per square = 1` is the clearest example, and it is labelled as such.

---


## Search strategy

### Cell order

`cellOrder` &middot; choice &middot; learned automatically

How the solver picks which empty square to fill next.

| Choice | What it does |
|---|---|
| **Most constrained** | Always fill the square with the fewest legal pieces. Strongest pruning. |
| **Hybrid** | Most-constrained while choices are few, then sweep in order. |
| **Row by row** | Simple left-to-right, top-to-bottom sweep. Cheapest per step. |

Default: **Most constrained** (`mrv`)

### Hybrid switch point

`hybridThreshold` &middot; slider &middot; learned automatically

Hybrid mode only: use most-constrained while the best square has at most this many choices.

Only has an effect when Cell order is **Hybrid**.

Range: `1` to `64` in steps of `1`

| End of the slider | What happens |
|---|---|
| lowest | 1 = almost always sweep in order |
| highest | 64 = almost always most-constrained |

Default: `4`

### Tie breaker

`tieBreak` &middot; choice &middot; learned automatically

Which square wins when several are equally constrained.

Only has an effect when Cell order is **Most constrained** or **Hybrid**.

| Choice | What it does |
|---|---|
| **Most neighbours** | Prefer squares already surrounded. Keeps the filled area compact. |
| **Fewest neighbours** | Prefer isolated squares. Spreads the search out. |
| **Top-left first** | Plain reading order. |
| **Near the fixed piece** | Grow outwards from the mandatory piece 139. |
| **Near the centre** | Grow outwards from the middle of the board. |

Default: **Most neighbours** (`mostNeighbours`)

### Opening move

`startCell` &middot; choice &middot; learned automatically

Which square the solver is forced to fill first.

| Choice | What it does |
|---|---|
| **Let it choose** | No constraint on the opening move. |
| **Top-left corner** |  |
| **Top-right corner** |  |
| **Bottom-left corner** |  |
| **Bottom-right corner** |  |
| **Centre** |  |

Default: **Let it choose** (`auto`)

## Piece choice

### Piece order

`valueOrder` &middot; choice &middot; learned automatically

The order in which candidate pieces are tried in a square.

| Choice | What it does |
|---|---|
| **Natural** | Piece number order. Deterministic and cache friendly. |
| **Reversed** | Highest piece number first. |
| **Shuffled** | Random order from the seed. Pairs well with restarts. |
| **Rarest colours first** | Try pieces whose colours are scarce, to spend rare pieces early. |

Default: **Natural** (`natural`)

### Shuffle strength

`shuffleStrength` &middot; slider &middot; learned automatically

How much randomness is mixed into the piece order.

Range: `0` to `100` in steps of `5`

| End of the slider | What happens |
|---|---|
| lowest | 0 = keep the chosen order exactly |
| highest | 100 = fully scrambled every time |

Default: `0`

### Choices per square

`candidateCap` &middot; stepped slider &middot; learned automatically

Maximum number of pieces tried in any one square before giving up on it.

Range: 1, 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64, 96, 128, 256, 512, 1024

| End of the slider | What happens |
|---|---|
| lowest | 1 = greedy. Blisteringly fast, can never find a full solution |
| highest | 1024 = try everything. Complete search |

Default: `1024`

## Pruning

### Look ahead

`forwardCheck` &middot; choice &middot; learned automatically

How hard the solver looks for dead ends before committing.

| Choice | What it does |
|---|---|
| **None** | Never look ahead. Maximum speed per step, worst pruning. |
| **Neighbours** | Check the squares next to the piece just placed. |
| **Whole board** | Backtrack as soon as any empty square has no legal piece. |

Default: **Whole board** (`fullBoard`)

### Grey edge pruning

`greyInteriorPruning` &middot; on/off &middot; learned automatically

Forbid grey (border) edges in the middle of the board. Always safe: the piece set has exactly enough grey edges for the border.

| Value | Effect |
|---|---|
| on | on = fewer candidates, same solutions |
| off | off = more candidates to sift through |

Default: `on`

## Restarts

### Restart policy

`restartPolicy` &middot; choice &middot; learned automatically

Abandon and restart the search to escape an unlucky early choice.

| Choice | What it does |
|---|---|
| **Never** | One long search. |
| **Fixed** | Restart every N nodes. |
| **Geometric** | Each run is longer than the last by a fixed factor. |
| **Luby** | The classic 1,1,2,1,1,2,4,... schedule. Robust against heavy-tailed runtimes. |

Default: **Never** (`none`)

### Restart interval

`restartBase` &middot; stepped slider &middot; learned automatically

Nodes in the first run before the first restart.

Only has an effect when Restart policy is **Fixed**, **Geometric** or **Luby**.

Range: 1k, 5k, 10k, 50k, 100k, 500k, 1M, 5M

| End of the slider | What happens |
|---|---|
| lowest | 1k = restart constantly, never goes deep |
| highest | 5M = restarts almost never happen |

Default: `100k`

### Restart growth

`restartMultiplier` &middot; slider &middot; learned automatically

Geometric policy only: each run is this much longer than the last (percent).

Only has an effect when Restart policy is **Geometric**.

Range: `110` to `400` in steps of `10`

| End of the slider | What happens |
|---|---|
| lowest | 110% = barely grows, many short runs |
| highest | 400% = runs get long very quickly |

Default: `150` (1.50x)

## Attempt

### Attempt length

`nodeBudget` &middot; stepped slider &middot; learned automatically

How many search steps one attempt gets before the board resets and a new attempt begins.

Range: 100k, 250k, 500k, 1M, 2M, 5M, 10M, 25M, 50M, 100M

| End of the slider | What happens |
|---|---|
| lowest | 100k = very short attempts, lots of history |
| highest | 100M = long attempts that dig deep |

Default: `5M`

### Random seed

`randomSeed` &middot; slider &middot; **not** tuned by the learner

Pin this to reproduce a run exactly. The learner randomises it each attempt.

Range: `0` to `999999` in steps of `1`

| End of the slider | What happens |
|---|---|
| lowest | 0 |
| highest | 999999 |

Default: `12345`


---

## Combinations worth trying

| Goal | Try this |
|---|---|
| Deepest boards, slow and steady | Cell order **Most constrained**, Look ahead **Whole board**, Choices per square **unlimited**, Attempt length **25M+** |
| Maximum raw speed | Cell order **Row by row**, Look ahead **Neighbours**, Attempt length **500k** |
| Escape unlucky starts | Piece order **Shuffled**, Restart policy **Luby**, Restart interval **10k-100k** |
| See a search collapse | Choices per square **1** -- finishes in milliseconds and can never solve |
| See pruning matter | Look ahead **None** vs **Whole board**, everything else equal |
| Watch the cost of bad ordering | Tie breaker **Fewest neighbours** (scatters the search) vs **Most neighbours** |
| Reproduce a run exactly | Pin the same **Random seed** and all other values |

## Fair comparisons

Attempt length is itself a setting, and a 100k-step attempt obviously cannot
reach as deep as a 100M-step one. The learner therefore compares every other
setting only against attempts of the *same* length, and the Insights tab shows
that adjusted number ("pieces better / worse than a same-length run"). If you
want to compare two settings by hand, keep Attempt length fixed between them.
