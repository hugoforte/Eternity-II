# The solver

How the search works, why it is built this way, and the measurements behind
the default settings. For the options exposed in the UI see
[SETTINGS.md](SETTINGS.md).

## Running the solver on its own

The solver is a plain Java program; the web app is only a front end for it.

```sh
sh build.sh                                   # compile into java/classes
sh test.sh                                    # 1368 checks, a few seconds

java -cp java/classes core.MrvSolver          # MRV solver on Eternity II
java -cp java/classes core.MrvSolver 50000000 # stop after 50M steps
java -cp java/classes core.ScanSolver         # fixed-scan solver on Eternity II
java -cp java/classes core.ScanSolver 50000000 --slipSchedule=blackwood
java -cp java/classes core.ScanSolver 50000000 --shuffleStrength=5 --randomSeed=7
java -cp java/classes core.ScanSolver 50000000 --quotaSchedule=blackwood
# the best board this code reaches -- 250/256 pieces, 456/480 edges, ~5 min on one core
java -cp java/classes core.ScanSolver 8000000000 --slipSchedule=verhaard --quotaSchedule=blackwood
java -cp java/classes app.Engine --engine=scan --workers=8   # one ScanSolver per worker, best wins
java -cp java/classes core.Solver             # the older row-major solver
java -cp java/classes core.Bench              # benchmarks
java -cp java/classes core.Bench engines 20   # the two engines, 20s each
java -cp java/classes core.Bench slip         # edge slipping off vs on, equal nodes
java -cp java/classes core.Bench order        # fill-order frontiers, no search
java -cp java/classes core.Bench candidates   # what the root offers a seed, no search
java -cp java/classes core.Bench seeds 20 100000000   # 20 seeds at one budget
java -cp java/classes core.Bench quota         # the colour quota off vs on, equal nodes
java -cp java/classes core.Bench colours      # which three colours the quota tracks
java -cp java/classes core.AllTests           # the test suite
java -cp java/classes app.Engine --nodeBudget=300000   # the JSONL engine, by hand
```

## File map

| File | Role |
|---|---|
| `java/src/core/Pieces.java` | The 256 Eternity II pieces. **Unchanged** — this is the board design. |
| `java/src/core/Sides.java` | Packed side representation (8 bits per side) + rotation arithmetic. |
| `java/src/core/Instance.java` | A puzzle instance: n×n board, n·n pieces, fixed placements. `Instance.eternity2()` is the real puzzle. |
| `java/src/core/MrvSolver.java` | **Engine 1**: MRV cell ordering + bitset piece pool. |
| `java/src/core/ScanSolver.java` | **Engine 2**: fixed fill order + two-colour candidate index + edge slipping. ~37x the throughput. |
| `java/src/core/FillOrder.java` | The fixed cell orders, and the frontier measures used to judge them. |
| `java/src/core/Search.java` | What both engines expose to `app.Engine`, so an attempt can run either. |
| `java/src/core/PortfolioSearch.java` | Runs several seeded `ScanSolver`s at once (one per core) and reports the best. |
| `java/src/core/Solver.java` | Row-major solver with a one-step forward check (the previous version, kept as a baseline). |
| `java/src/core/RefSolver.java` | Deliberately naive solver used only to cross-validate the fast one. |
| `java/src/core/Validator.java` | Independent board checker. |
| `java/src/core/Generator.java` | Builds random, guaranteed-solvable instances for the tests. |
| `java/src/core/Bench.java` | Benchmarks. |
| `java/src/core/Puzzle.java`, `java/src/core/Coordinates.java` | The original random/concentric-frame attempt, kept for reference. |
| `java/test/core/*.java` | The test suite (see below). |

---

## What was implemented

### 1. Bitset piece pool

A *variant* is a (piece, rotation) pair, numbered `v = pieceId * 4 + rot`. Because 4 divides 64,
**all four variants of a piece live in one 64-bit word**, at word `id >>> 4`, bit offset `(id & 15) << 2`.
Using or releasing a piece is therefore a single machine instruction:

```java
avail[id >>> 4] &= ~pieceBits(id);        // use
avail[id >>> 4] |=  existPieceBits[id];   // release
```

Colour constraints are bitsets too: `maskL[c]`, `maskT[c]`, `maskR[c]`, `maskB[c]` hold the variants whose
left/top/right/bottom side is colour `c`, plus `notGreyL/T/R/B` for interior edges.

Each empty cell caches a **constraint mask** = the intersection of the four side masks implied by its
borders and its currently-placed neighbours. A cell's legal candidates are `cellMask[cell] & avail`, and
iterating them is `Long.numberOfTrailingZeros` in a loop.

Rotationally-symmetric pieces have their duplicate orientations folded away, so the search never
enumerates the same physical board twice — this is what makes exact solution **counting** possible.

### 2. MRV (most-constrained-variable) cell ordering

At each node the solver takes the empty cell with the fewest candidates. Two things fall out for free:

* **Whole-board forward checking.** If *any* empty cell has zero candidates we backtrack immediately.
  The old `Solver` only checked the two cells next to the one just filled.
* **Free forced moves.** Cells with exactly one candidate are always taken first, so chains of forced
  placements cost almost nothing.

Selection is fully deterministic (fewest candidates → most placed neighbours → lowest index), because the
tests compare exact node and solution counts.

`useMrv = false` switches to lowest-index ordering while keeping all the bitset machinery. That isolates
the heuristic for benchmarking, and the test suite uses it to prove that cell ordering does not change the
*set* of solutions.

### 3. Making MRV affordable

A naive MRV implementation popcounts every word of every empty cell at every node. Three things fix that:

1. **Incremental counts.** `cellCount[cell]` is kept up to date. When piece `id` is used, `avail` loses
   exactly `existPieceBits[id]`, all in one word, so every other empty cell's count drops by
   `popcount(cellMask[cell][thatWord] & thatMask)` — **one** word access and **one** popcount per cell
   instead of 16 of each.
2. **Subset tightening.** When a neighbour gains a placed neighbour, its constraint on the facing side
   goes from "not grey" to "exactly colour c", and `maskX[c] ⊆ notGreyX` for non-grey `c`. So the new mask
   is just `oldMask & maskX[c]` — a plain AND, not a four-way intersection. (The facing colour is
   guaranteed non-grey because the cell's own mask forbade grey on sides facing an empty on-board
   neighbour. There is a documented fallback if an instance ever violates that.)
3. **Saved-copy undo.** Intersection is not invertible, so `place()` saves the ≤4 touched neighbour masks
   and `unplace()` restores them instead of recomputing.

Measured effect of (1) alone on Eternity II: **0.77M → 1.16M nodes/sec**.
The mask array is stored word-major, which measured ~4% faster than cell-major.

### 4. A second engine: fixed fill order (`ScanSolver`)

Published measurement on this puzzle says the MRV design is the wrong trade: a controlled grid measured
MRV + forward checking at 7–8k nodes/sec scoring 320/480, against a fixed row-major scan at 31.5M
nodes/sec scoring 376.6/480. `ScanSolver` is that design, built alongside `MrvSolver` rather than
replacing it so the two can be compared directly.

Three decisions, and they only work together:

**A fixed fill order where north and west are always already placed.** `FillOrder.bandedScan` reproduces,
at 16×16, the order used by the fastest published engine: a row scan of the first 11 rows, then the same
scan narrowed to columns 0–4, then the rest of the bottom band column by column 5 cells at a time, then
the bottom-right 5×4 corner peeled as four nested L-shapes. Because every cell arrives with exactly two
placed neighbours, there is one candidate key and one code path for the whole board, and nothing has to be
undone on a neighbour when a piece goes down or comes back up. The order generalises: the phase sizes are
n/3, n/3 and n/4, which are 5, 5 and 4 at n = 16, so the real puzzle gets the published order and every
other board size gets the same shape. `FillOrder.validate` states the invariant, and the solver's
constructor refuses any order that breaks it.

**A two-colour candidate index.** The west neighbour's right colour is this cell's left; the north
neighbour's bottom is its top; off the board both are grey. So `top * numColours + left` identifies the
constraint completely, with the radix taken from the instance. The other two sides are pinned by where the
cell is, not by the search — grey on the border, non-grey inside — so those combinations are deduplicated
into a handful of *classes* and the class contributes a block offset. Getting a cell's candidates is one
add and two array reads. Eternity II needs **7 classes and 793 index entries**.

**No used-piece test at all.** Instrumented measurement on fast engines puts ~59% of candidate
examinations on pieces already placed, and the unpredictable branch that rejects them is the most
expensive thing in the loop. Rather than make that branch cheaper, the index stores each key's candidates
as the (word, mask) pairs they occupy in the same variant bitset `MrvSolver` uses — four rotations of a
piece in one 64-bit word — so `keyMask[i] & avail[keyWord[i]]` yields *only* unused candidates. A used
piece is never examined, so it cannot be mispredicted. Placing is one AND that clears a nibble;
un-placing is one store of the word held in a register on the way in.

The hint piece cannot be pre-placed under a fixed order, because its neighbours are not down yet. Instead
it is withheld from every ordinary class, so no other cell can take it, and its own cell gets a private
class holding that one variant; the cells north and west of it get classes pinning the facing colour.
That reproduces exactly what pre-placement achieves in `MrvSolver`, as table data.

### 5. Edge slipping

The engine as described above is **exact**: every placed edge must match. Such a search cannot score
above whatever perfect prefix it reaches, because when nothing fits the next cell it can only back
up. Edge slipping lifts that cap by letting the search place a piece that deliberately mismatches
one of its two known sides — a **break** — and carry the cost.

Four rules, taken from the published engines and copied rather than invented:

* **At most one break per placed piece.** A candidate that mismatches both its left and its top is
  never offered.
* **Never a break involving a border colour.** Every side facing off the board stays grey, so a
  slipped board is still legal in every respect except its interior mismatches.
* **A cumulative ceiling by depth.** `slipSchedule=blackwood` is Blackwood's published
  `{201, 206, 211, 216, 221, 225, 229, 233, 237, 239}`: one break permitted by depth 201, two by
  206, ten by 239. `slipSchedule=verhaard` is the array from Verhaard's separately developed
  record-setting solver, `{193, 202, 209, 214, 218, 222, 226, 229, 232, 235, 238, 240}`. Both say
  the same thing — the first three quarters of the board must be perfect and every mismatch is
  spent in the tail — and that they were arrived at independently is the main reason to trust the
  shape. The depths are quoted for 256 cells and are scaled by `cells / 256`, exactly as
  `FillOrder` scales its phase sizes, so the real puzzle gets each schedule verbatim and every other
  board size gets the same shape.
* **Perfect candidates first**, so a cell that may not break this turn never looks at a slipped one.

Two things widely repeated about Blackwood's engine are **not** in it: there is no rule forbidding
two breaks from being adjacent, and his schedule has ten entries ending at 239, not the twelve
ending at 256 that a Rust reimplementation carries. The ten numbers are load-bearing — a
leave-one-out sweep of the original made depth-248 runs roughly seventy times rarer for the loss of
any single one — so they are copied verbatim and are not a tuning surface. Slipping is folded
**into** the search rather than run as a repair pass afterwards; two-phase repair measured worse.

**Offering the slipped candidates is still two array reads.** A variant has exactly one left colour,
so the buckets along one (class, top) row of the index are disjoint, and "the required top with any
other non-grey left" is that whole row minus the exact bucket; reading down a (class, left) column
gives the same for the top. Both sets are precomputed at construction and stored back to back with
the perfect set in the same `(word, mask)` array, so a key's three runs are contiguous and the scan
simply stops at the end of the perfect run whenever the ceiling has been reached.

**A slipped board is never a solution.** `solutions`, `solutionBoard` and the engine's `solved` flag
keep meaning a perfect 256-piece board scoring 480. A board that reaches the last cell carrying
breaks is recorded as the best seen and the search carries on looking for a perfect one.
`Validator.validateComplete` rejects it, the `end` record carries `breaks` alongside `edges`, and
the supervisor refuses a `solved` claim that arrives with any breaks and says so on stderr.

**The score stays honest.** Under a north-and-west fill order every internal edge is judged exactly
once — 2n(n-1) of them, 480 at 16×16 — so a finished board with k breaks scores exactly `480 - k`,
and a partial one scores the edges the order has joined up so far, less its breaks. None of that is
used for the reported score: `Validator.matchedEdges` counts the board independently, and the tests
compare the two.

### 6. A seed, and restarts

Everything above is decided by the instance, so the engine as described runs **one descent and
repeats it for ever**: the same configuration produces the same board at ten seconds and at ten
minutes. A ten-minute run measured on this engine found its best board at 70 seconds and produced
nothing in the remaining 530, and eight cores gave a best-of-four, because only four configurations
existed to give it. An engine that cannot produce a second opinion cannot be sampled, and a lab that
cannot sample has no distribution to learn from.

**The seed reorders the candidate index, and nothing else.** A key's candidates are already a
contiguous run of `(word, mask)` pairs, so the run is simply permuted at construction. The search
reads the same two arrays through the same two indices either way, so **the inner loop is untouched**
— 47.2M nodes/sec before the change against 47.4M after, best of three runs at a 100M budget with
slipping off, reaching the identical board.
Reordering a run cannot add or remove a candidate, so no seed can change which boards exist; the test
suite enumerates small instances under three seeds and compares the solution sets, and the node
counts, against the unseeded engine.

A permutation was chosen over the other cheap option, a seeded starting offset into each run. Both
are built once and neither is visible from the hot loop, so the permutation is free: a run of length
L has L rotations against L! permutations.

**What a run permutation cannot reach is the order inside one `(word, mask)` pair.** A word holds
sixteen pieces and a piece's four rotations share a nibble, so candidates that land in the same word
keep their relative order. On Eternity II, 467 keys offer anything at all, 263 offer a real choice of
two candidates or more, and 203 of those spread the choice over more than one entry — so about four
keys in five are reorderable and the rest are fixed. Rotating the mask inside the loop would reach
the remainder, at the cost of two instructions on every candidate examined; it was not worth it,
because the seeds already differ by a lot (below). One visible consequence: the four corner pieces
are the four lowest variants and share a word, so **the opening move is the same for every seed**.

`core.Bench candidates` prints that consequence rather than leaving it asserted. The first square
holds **one entry carrying four variants across four distinct pieces**, under the default, under the
quota gate, and under both — so the root offers four genuine openings and a seed can reorder none of
them. The quota gate is the one thing that could have split that entry, since it lays a bucket out
as one entry per colour count; it does not, because all four corners carry the same count.

**`shuffleStrength` means something different here than in `MrvSolver`.** There it buys transpositions
in proportion to the length of one cell's candidate list, which is long. A key here holds one to six
entries, so the same formula rounds to zero swaps below a strength of 17 — measurably nothing. What
is a useful dial is how much of the natural order survives, so a strength of s per cent fully
scrambles s per cent of the keys and leaves the rest exactly as they were. The distinction matters:
the natural order turns out to be a *good* order, and scrambling all of it is worse than scrambling a
twentieth of it.

**Restarts reuse `MrvSolver`'s schedule, moved to `SolverConfig.restartBudget` so there is one copy
of it.** `fixed`, `geometric` and `luby` with `restartBase` and `restartMultiplier` mean exactly what
they meant before. On restart the engine re-shuffles the candidate index from the advanced random
stream rather than merely emptying the board — the published guidance is that the cutoff schedule
barely matters and that re-randomising on restart is what does the work, and a restart that only
emptied the board would walk the identical tree again. The deepest board survives a restart: it is
only ever replaced by a better one.

Restarts are skipped in two cases, both deliberate and both asserted by the tests rather than left to
be discovered. Without randomness there is nothing to re-draw, so `restartPolicy` alone does nothing
and `restarts()` stays 0. And an exhaustive enumeration never restarts, because after a restart the
search has no record of the solutions it already reported and would count them twice.

### 7. A colour quota

Blackwood's engine picks **three colours** -- one border colour and two interior ones -- and uses
them as a progress gate. The pieces carrying them go to the front of every candidate list; a running
count says how many sides of those colours the placed pieces have consumed; and at each depth that
count is compared against a published floor. If the best remaining candidate cannot lift the board to
that depth's floor, the search **abandons the scan at that depth** rather than merely rejecting the
candidate. That last part is what makes it a pruning rule instead of an ordering tweak, and it is the
largest technique in the source material -- measured there at ~2x on top of everything else he had.

`quotaSchedule=blackwood` is his ramp, stored as the breakpoints of a piecewise-linear floor on a
256-cell board:

| by placement | sides of the three colours consumed |
|---|---|
| 16 | 0 |
| 26 | 28 |
| 56 | 71 |
| 76 | 89 |
| 102 | 106 |
| 160 | 119 |

Interpolating between them reproduces his quoted slopes exactly -- 2.8 a placement out to 26, then
1.43333, 0.9, 0.6538 and finally 1/4.4615 out to 160, after which he constrains nothing. Between
breakpoints the floor is fractional and the count it is compared against is an integer, so a demand
of 2.8 sides is either "at least 2" or "at least 3" depending on what his array held; **the looser
reading is taken**, so nothing measured below can be blamed on a gate made stricter than the
published one. Both axes scale by `cells / 256` exactly as the slip schedules and the fill-order
phases do.

**"Sorted to the front" is table data here, not a sort.** A variant's count is fixed by its piece, so
the candidate index is simply built in descending-count order: one group of (word, mask) pairs per
count, highest first, with a parallel `quotaCount` entry saying which. The first entry that falls
short means every entry behind it does too, so a run is cut off with one compare per entry and no
colour arithmetic in the loop. With the gate off there is a single group holding every variant, so
the index comes out with exactly the 793 entries it always had; on, it needs 881. `descend` is the
loop it always was, plus one branch on a final field.

**The seed still works, and still cannot break the gate.** A key's run is permuted within each
stretch of equal count rather than across the whole run, because moving an entry across counts would
abandon candidates that could still have met the floor -- which is also why the counts themselves
never have to move.

**The gate is deliberately incomplete.** It abandons subtrees that may contain solutions, so it is
off by default and `CrossValidationTest` does not know it exists. `ColourQuotaTest` covers it
separately, including the one cross-run invariant that does survive: on a small instance, every board
a gated exhaustive run reports is one the ungated run reports too. The gate may lose a solution; it
can never invent one.

**Our colours are not his colours, and it turns out to be the whole of it.** Our piece table has five
border colours -- 1, 2, 3, 13 and 14 -- each appearing on exactly 24 sides, which is the 12 frame
pairs his description quotes; the other seventeen are interior, on 48 or 50 sides each. His table
numbers its border colours 1, 5, 9, 13 and 17. Both files are transcriptions of the same physical
puzzle under different labelling conventions, so his three colour numbers are not ours and cannot be
read as ours.

The relabelling is recoverable, and forced. Refine each colour by the multiset of how often it sits
cyclically adjacent to, and opposite, every other colour on a piece; on both tables all 23 signature
classes come out as singletons, so there is exactly one bijection to try. Applying it to his 256
pieces and canonicalising each under rotation reproduces our piece multiset exactly, which confirms
both that the two files are the same puzzle and that the mapping is the right one. Under it,
**his `{13, 16, 10}` is our `{14, 22, 5}`**, and that is what `quotaColours` now defaults to.

The reason the substitution went unnoticed for a whole round of measurement is that it preserves
every property anyone would check. Reading his numbers as ours gives one border colour and two
interior ones -- the shape he describes -- and every (one border, two interior) triple carries 120 to
124 sides, so the totals agree too. What it does not preserve is the property he picked them for, and
noted in his source: *"there is a lot of overlap between these sides"*. Three pieces carry all three
of his colours and 22 carry two. **No piece carries all three of the ones read as ours.**

His piece table is worth knowing about for its own sake. `Util.cs` hardcodes all 256 pieces, so a
record-holder's repository contains a primary-source transcription of the real piece set -- one that
passes the parity check, where the widely-linked `david3x3x3` copy does not. The full decoding, with
the two corrections it forces on the research notes, is in
`~\ai-notes\notes\hugoforte\Eternity-II\blackwood-source-decoded.md`.

---

## Measured results

### Eternity II, 20 seconds each

| Solver | nodes/sec | pieces placed |
|---|---|---|
| `Solver` (row-major + one-step forward check) | **16.2 M** | 191 / 256 |
| `MrvSolver` (MRV + bitsets + whole-board check) | 1.24 M | **198 / 256** |

MRV does ~13× more work per node but explores a far better tree.

### The two engines, 20 seconds each, single-threaded

| Engine | nodes/sec | pieces placed | matched edges |
|---|---|---|---|
| `MrvSolver` | 1.35 M | 198 / 256 | 354 / 480 |
| `ScanSolver` (banded order) | **50.0 M** | **211 / 256** | **390 / 480** |

At an equal budget of 20M nodes, so that the machine's other load cannot flatter either engine:

| Engine | elapsed | nodes/sec | pieces placed | matched edges |
|---|---|---|---|---|
| `MrvSolver` | 27.7 s | 0.72 M | 198 / 256 | 354 / 480 |
| `ScanSolver` | 0.61 s | 32.8 M | **204 / 256** | **376 / 480** |

So the scan engine wins on throughput by ~37×, and it also wins per node: MRV's much better tree does not
make up the difference. That was still a long way from the 248 pieces / 454 edges a published reference
engine reaches in 60 s on one core, and the gap was edge slipping, which the next section adds.

### Edge slipping, Eternity II, equal node budgets, single-threaded

| budget | schedule | nodes/sec | pieces placed | matched edges | error-free | breaks |
|---|---|---|---|---|---|---|
| 5M | none | 44.2 M | 204 / 256 | 376 / 480 | 204 | 0 |
| 5M | `blackwood` | 43.9 M | 226 / 256 | 414 / 480 | 204 | 6 |
| 5M | `verhaard` | 37.9 M | 244 / 256 | 444 / 480 | 204 | 12 |
| 20M | none | 48.3 M | 204 / 256 | 376 / 480 | 204 | 0 |
| 20M | `blackwood` | 45.8 M | 226 / 256 | 414 / 480 | 204 | 6 |
| 20M | `verhaard` | 43.0 M | 245 / 256 | 446 / 480 | 204 | 12 |
| 100M | none | 48.1 M | 206 / 256 | 380 / 480 | 206 | 0 |
| 100M | `blackwood` | 47.5 M | 235 / 256 | 430 / 480 | 206 | 8 |
| 100M | `verhaard` | 45.4 M | 245 / 256 | 446 / 480 | 205 | 12 |
| 1B | none | 48.2 M | 211 / 256 | 390 / 480 | 211 | 0 |
| 1B | `blackwood` | 46.4 M | 241 / 256 | 440 / 480 | 208 | 10 |
| 1B | `verhaard` | 44.4 M | **247 / 256** | **450 / 480** | 207 | 12 |
| 10B | none | 46.1 M | 213 / 256 | 394 / 480 | 213 | 0 |
| 10B | `blackwood` | 44.3 M | 246 / 256 | 451 / 480 | 213 | 9 |
| 10B | `verhaard` | 36.9 M | **249 / 256** | **454 / 480** | 211 | 12 |

**This is by far the largest single improvement measured on this solver.** At an equal 20M nodes
slipping is worth +38 matched edges over the exact search and at 1B nodes +60; the whole of the
fixed-order engine was worth +22 over `MrvSolver` at the same budget. At 1B nodes — 22 seconds --
the engine reaches 247 pieces / 450 edges, against the 248 pieces / 454 edges a published reference
engine reaches in 60 seconds on one core.

**The `errorFree` column is the *error-free reach*: the most pieces the search ever held with no
break anywhere** ([CONTEXT.md](../CONTEXT.md)) — a running
maximum over every node entered carrying no breaks, not a property of the board on record. The
distinction is the whole point of the column. A slipped arm's record board is its *deepest* board
and it is full of breaks, so that board's own error-free prefix is short and says nothing about how
far the same search got cleanly earlier on. `Validator.perfectTiles` computes that prefix and the
tests use it as a cross-check, but it is not what is tabulated here, and an earlier draft of this
section tabulated it by mistake and drew the opposite conclusions from it.

**Edge slipping costs almost nothing in error-free reach, and buys 30 to 40 tiles of depth for it.**
At 5M and 20M all three arms reach exactly 204 — the schedules make no difference at all to how far
the search gets cleanly. By 100M they are 206 / 206 / 205, by 1B 211 / 208 / 207, and by 10B
213 / 213 / 211. Two or three tiles is the whole of the cost at any budget measured, and Verhaard's
schedule is 36 tiles deeper overall at the same one. Blackwood's is level with the exact search at
10B while placing 33 more tiles.

**The engine has never placed more than 213 tiles without a mistake, at any setting or budget tried
here.** That is the honest ceiling on this measure, and it moves slowly: 204 at 5M, 206 at 100M,
211 at 1B, 213 at 10B — 2,000x the budget for nine tiles. Pieces placed and matched edges both climb
far faster than that, because breaks are what buys their depth. This column is the one that cannot
be bought, and it is the one that has barely moved.

**Tracking the error-free depth costs about 2% of throughput.** It is one compare per node,
guarded on `depth >` so the `breaks == 0` test is seldom reached: 45.9M nodes/sec without it against
44.9M with, on the ungated arm at a 100M budget, best of six paired runs. Three of those six pairs
were badly contended — the machine was not quiet — and the best-of is the figure that reflects an
unimpeded core. Treat 2% as the order of magnitude rather than a measurement to two digits.

**Slipping costs about 6% of throughput even when it is switched off**, because the search now reads
a per-depth ceiling and compares it at every node: 50.4M nodes/sec before the change against 47.2M
after, both at a 100M budget on an idle machine, reaching the identical depth of 206. Switched on it
costs a further 2-8%, and the extra depth pays for that many times over.

**Verhaard's schedule beats Blackwood's on this engine at every budget measured**, by 10 to 30
matched edges. That is not what was expected — Blackwood's is the schedule behind the best
published result — and it is reported rather than acted on: the default stays at `blackwood`
because it is the published one, and the learner is free to move. The likely reason was that this
search had no restarts, so a schedule that unlocks its first break sooner gets more out of the one
deterministic descent it is given. Restarts now exist (section 6) and re-testing the two schedules
under them has not been done.

### What a seed is worth, Eternity II, 20 seeds at 100M nodes each

`slipSchedule=verhaard`, single-threaded, seeds 1–20, everything else at its default. The last
column counts how many of the twenty boards were distinct, because a variation feature that quietly
did nothing would look exactly like a good one in every other column.

| candidate order | pieces min / median / max | edges min / median / max | distinct boards |
|---|---|---|---|
| unseeded (`natural`) | — 245 — | — 446 — | 1 |
| `reverse` | — 243 — | — 442 — | 1 |
| `shuffleStrength=5` | 242 / 245 / **247** | 440 / 446 / **450** | 12 / 20 |
| `shuffleStrength=10` | 242 / 245 / **247** | 440 / 446 / **450** | 16 / 20 |
| `shuffleStrength=25` | **243** / 245 / **247** | **442** / 446 / **450** | **20 / 20** |
| `shuffleStrength=50` | 241 / 245 / 246 | 438 / 446 / 448 | 20 / 20 |
| `shuffleStrength=100` | 225 / 244 / **247** | 412 / 444 / **450** | 20 / 20 |
| `valueOrder=random` | 231 / 243 / 246 | 422 / 442 / 448 | 20 / 20 |

**The seed is worth a great deal, and the amount of it matters more than the seed does.** At a
strength of 25 every one of the twenty seeds found a different board, the worst of them was 243
pieces and the best 247 — so the engine that could only ever say 245 now samples a range, and the
best of twenty beats the single run by **+2 pieces and +4 edges**.

**The natural order is a good order, not an arbitrary one.** It scores 245 at this budget, which is
the *median* of a strength-25 sample and above the median of a fully scrambled one. Destroying all
of it costs 14 pieces off the bottom of the range. That is the reason `shuffleStrength` reorders
a share of the keys rather than a share of each key: the useful setting is a light touch.

**A cheap second opinion needs no randomness at all.** `valueOrder=reverse` reaches 243/442 — a
different descent for free, and a second deterministic configuration where there were four.

> **On reproducing these figures after the portfolio merge.** Upstream's `shuffleRuns` permutes each
> key's candidate run whenever `randomSeed` is non-zero, which is what gives each `PortfolioSearch`
> worker a different descent. `SolverConfig.randomSeed` therefore defaults to **0**, meaning "do not
> vary the order", so a bare CLI run and everything in `core.Bench` measure the plain descent and
> reproduce the numbers below exactly. The lab's own default is a real seed, because there every
> attempt should differ.

### The same seeds at bigger budgets, where sampling stops paying and then costs

`java -cp java/classes core.Bench seeds 20 1000000000 25` — about 22 seconds a seed, and about
3.6 minutes a seed at 10B.

| budget | seeds | unseeded | seeds min / median / max | spread | best of N | sampling is worth |
|---|---|---|---|---|---|---|
| 100M | 20 | 245 / 446 | 243 / 245 / 247 | 4 pieces | 247 / 450 | **+2 pieces, +4 edges** |
| 1B | 20 | 247 / 450 | 246 / 247 / 248 | 2 pieces | 248 / 452 | +1 piece, +2 edges |
| 1B | first 4 | 247 / 450 | 246 / 247 / 247 | 1 piece | 247 / 450 | nothing |
| 10B | 4 | **249 / 454** | 247 / 248 / 248 | 1 piece | 248 / 452 | **−1 piece, −2 edges** |

**The spread narrows as the budget grows, and the advantage of sampling goes with it — and then
turns negative.** Each tenfold increase in budget roughly halves the distance between the luckiest
seed and the unluckiest, so the seeds converge on the same place and a second opinion is worth less
the longer each opinion is allowed to think. At 100M the best of twenty beats the single run by two
pieces; at 1B by one; at 10B the single run is ahead of all four seeds.

**And the number at 10B is the one the lab has been chasing.** The unseeded run reaches exactly 249
pieces / 454 edges there, in 3.6 minutes on one core — the best this project has recorded, which was
assumed to need a ten-minute attempt. No seed matched it. So the honest answer to "does best-of-N
beat the deterministic 249/454" is **no, not at the budget where 249/454 happens**: sampling wins at
small budgets, ties around 1B, and loses at 10B.

Two things temper that. The 10B row is four seeds, not twenty, and the maximum of four draws is a
much weaker statistic than the maximum of twenty — the 1B row shows the same effect, where the first
four seeds only tie the single run that twenty seeds beat. And the whole table is single-threaded;
what the lab actually has is eight cores, where the choice is not "sample or run long" but "eight
seeds of 10B or one seed of 80B". That has since been measured — see
[the overnight eight-arm run](#the-overnight-eight-arm-run) below.

**The useful conclusion is not that variation was a mistake.** It is that the natural candidate order
is a strong order — strong enough to beat every seed once the search is given room — and that
sampling on this engine is a way to buy depth cheaply at short budgets, not a way to go deeper than
it can go. The lab now has the distribution and can price it.

### The overnight eight-arm run

Eight arms, six hours each, one core apiece: **640e9 nodes per arm, 5.2e12 in total**, at about
31M nodes/sec with eight of the machine's twelve cores busy. Two arms were deterministic controls
and six carried a seed at `shuffleStrength=25`, which is the comparison the section above left
open.

| arm | final | how it got there |
|---|---|---|
| seed5 | **250 / 456** | 454 at 4 min, **456 at 66 min** |
| control-verhaard | **250 / 456** | 454 at 2 min, **456 at 92 min** |
| seed1 | **250 / 456** | 454 at 99 min, **456 at 106 min** |
| seed4 | **250 / 456** | 454 at 33 min, **456 at 166 min** |
| seed6 | 249 / 454 | 454 at 234 min |
| seed3 | 249 / 454 | 454 at 41 min |
| seed2 | 249 / 454 | 454 at 23 min |
| control-blackwood | 246 / 451 | 451 at 1 min, then nothing for six hours |

**250 pieces / 456 matched edges is the best this project has recorded.** Four of the eight arms
reached it and none went past it. The last improvement anywhere was at 166 minutes, so the final
194 minutes across all eight cores produced nothing at all.

**Eight seeds against one long descent is a tie.** The deterministic control reached 456 at 92
minutes; the luckiest seed reached the same score at 66. Both stopped there, and five further hours
moved neither. Sampling is not better here and it is not worse — it is a different route to the
same place, which is what the converging spread in the table above predicts. That makes 456 a
property of the engine rather than a lucky draw: eight independent searches, two of them with no
randomness at all, agree on where the ceiling is.

Note also that `blackwood` stalled at 451 after one minute and never moved again, while `verhaard`
climbed for another hour and a half. The slip schedule decides how far the search can go long
before the budget does.

### Restarts, measured, are worth much less than the seed

Six seeds at 100M nodes, `slipSchedule=verhaard`, `shuffleStrength=25`, varying only the policy:

| restart policy | restarts over the six runs | pieces min / median / max |
|---|---|---|
| none | 0 | 243 / 245 / 247 |
| `fixed`, `restartBase=10000000` | 54 | 243 / 245 / 247 |
| `fixed`, `restartBase=25000000` | 18 | 244 / 245 / 247 |
| `luby`, `restartBase=5000000` | 72 | 244 / 245 / 247 |

Reproduce one cell with, for example:

```sh
java -cp java/classes core.ScanSolver 100000000 --slipSchedule=verhaard \
  --shuffleStrength=25 --randomSeed=1 --restartPolicy=luby --restartBase=5000000
```

**The median and the best do not move at all; the worst improves by one piece.** That is consistent
with the published guidance that the cutoff schedule barely matters, and it goes a step further: on
this engine, *having* restarts barely matters either. The reason looks structural. Restarts are the
standard cure for a heavy-tailed runtime, where a search occasionally buries itself in a subtree with
no solution in it and needs to be dragged out. A slipping descent is not that shape — it always
reaches about 245 and is never stuck — so there is no tail for a restart to cut. The policy is
exposed anyway, because a restart is the only way to re-draw a seed inside a single attempt, and
because the lab can now settle it with its own data instead of this paragraph.

### The colour quota, measured: what the right three colours are worth

`fillOrder=banded`, `slipSchedule=verhaard`, single-threaded. The off/on pair at any node budget is
`java -cp java/classes core.Bench quota <nodes>`; a single arm with any colours is
`java -cp java/classes core.ScanSolver <nodes> --quotaSchedule=blackwood --quotaColours=13,16,10`.
The 600 s rows were taken by running with the node budget removed and stopping the search at ten
minutes, which no single command does.

| budget | quota | nodes/sec | pieces placed | matched edges | error-free |
|---|---|---|---|---|---|
| 100M | off | 41.2 M | 245 / 256 | 446 / 480 | 205 |
| 100M | `14,22,5` | 31.8 M | **248 / 256** | **452 / 480** | 205 |
| 1B | off | 39.6 M | 247 / 256 | 450 / 480 | 207 |
| 1B | `14,22,5` | 27.6 M | **249 / 256** | **454 / 480** | 207 |
| 10B | off | 39.5 M | 249 / 256 | 454 / 480 | 211 |
| 10B | `14,22,5` | 27.1 M | **250 / 256** | **456 / 480** | 207 |
| 600 s | off | 42.4 M | 249 / 256 | 454 / 480 | -- |
| 600 s | `14,22,5` | 27.3 M | **250 / 256** | **456 / 480** | -- |

The two time-budgeted rows carry no error-free figure. They predate this column and were not
re-run for it.

**The gate is free on this measure until it is not.** Gated and ungated reach the *same* error-free
depth at 100M and at 1B — 205 and 207 — while the gate is a piece and two edges ahead on the board
it records. At 10B they separate for the first time: 211 ungated against 207 gated. So the quota
buys its two edges for nothing up to a billion nodes and for four tiles of clean depth at ten, and
a single budget would not have shown that.

**The gate is worth a piece and two edges at every budget measured, and it wins on wall-clock as well
as on nodes.** That second half is not free: the gate costs about a third of the throughput, 27M
nodes/sec against 40M, because it now reaches depths where a node is expensive. It wins the
equal-time row anyway, 250/456 against 249/454, on 16.4 billion nodes against 25.4 billion.

**The 10B row is the one that matters.** 250 pieces / 456 matched edges is the best board this
project has recorded. It was found by the overnight eight-arm run -- eight cores for six hours,
5.2e12 nodes, about 48 core-hours. The gated engine reaches the same board on **one core in six
minutes**, for 1.0e10 nodes. That is the same board for roughly **500 times less work**, and it is
the whole of what this technique was worth.

**The gate still costs the hot loop nothing when it is off**, which was the thing that had to stay
true: the index comes out with exactly the 793 entries it always had, and `descend` is the loop it
always was plus one branch.

#### Why the first attempt measured a wall instead

The first round of measurement ran `quotaColours=13,16,10` -- Blackwood's three numbers read as
indices into *our* piece table rather than his. The engine stalled around depth 70 at every budget,
and the conclusion drawn was that the ramp is unsatisfiable. It is not. Those were three different
colours. Section 7 above has the relabelling and how it was recovered; this is what it cost:

| budget | `14,22,5` (his three) | `13,16,10` (his numbers read as ours) |
|---|---|---|
| 100M | **248 / 452** | 55 / 90 |
| 1B | **249 / 454** | 70 / 119 |
| 10B | **250 / 456** | 73 / 125 |

The arithmetic behind the stall holds up, and is worth keeping, because it says something about the
ramp that is true for both triples. The ramp asks for 119 of the 122 available sides by placement
160. No search can beat the bound set by the pieces that exist and the cells the fill order has
actually reached by that depth -- 2 corner cells, 32 edge cells and 126 interior cells at 160 -- so
that bound is the honest ceiling:

| by placement | the ramp asks for | ceiling, his three | slack | ceiling, ours read as his | slack |
|---|---|---|---|---|---|
| 32 | 36 | 54 | 18 | 51 | 15 |
| 56 | 70 | 82 | **12** | 75 | **5** |
| 64 | 78 | 90 | **12** | 83 | **5** |
| 76 | 89 | 102 | **13** | 95 | **6** |
| 128 | 111 | 122 | 11 | 122 | 11 |
| 160 | 119 | 122 | 3 | 122 | 3 |

**Both triples are three sides clear of impossible at 160, so that depth is not what separates them.**
The difference is the band from 56 to 76, where his three leave 12 to 13 sides of room and the
substitutes leave 5 to 6. The stall was measured at depth 70 to 73 -- the middle of that band.

**Which three colours, measured.** `core.Bench colours` ranks all 680 (one border, two interior)
triples by the least room the ramp ever leaves them, then runs the extremes of that ranking. 1B nodes
each:

| colours | worst slack | pieces placed | matched edges |
|---|---|---|---|
| `1,7,8` | 5 | 246 / 256 | 448 / 480 |
| `1,7,9` | 5 | 58 / 256 | 96 / 480 |
| `1,7,10` | 5 | **249 / 256** | **454 / 480** |
| `14,22,5` (Blackwood's own, ranked 403rd) | 3 | **249 / 256** | **454 / 480** |
| `2,19,21` (worst in the table) | −4 | 46 / 256 | 73 / 480 |

**Slack rules things out and predicts nothing.** Twenty-three triples are negative, and for those the
ramp is unsatisfiable by any arrangement of pieces whatsoever -- that part of the ranking is sound and
worth keeping. Above that line it stops carrying information: three triples with *identical* slack of
5 land at 58, 246 and 249 pieces.

**And Blackwood's three are not uniquely good.** `1,7,10`, picked by the ranking rather than from his
source, matches them exactly at 249/454. That is worth knowing rather than disappointing: it is the
same thing Bucas reports from the other end, that the two boards which reached 470 used *different*
heuristic triples. The triple is a parameter to vary, not a secret to recover.

What reading the source did settle is the ramp itself. It is satisfiable, the previous round's
"unsatisfiable by any triple" conclusion was an artefact of three wrong colours, and the gate is worth
a piece and two edges once it is pointed at colours it can be satisfied by.

**The gate stays off by default**, and that has not changed. It abandons subtrees that may hold
solutions, so the default engine stays complete and `CrossValidationTest` keeps exercising it.
`ColourQuotaTest` covers the gate separately. Turn it on for a score-chasing run; leave it off when
the answer has to be exhaustive.

**What is still not done.** The ramp's five slopes have not been tuned on this engine, only copied.
The other 678 triples have not been run. Both are cheap, and the ranking machinery for them already
exists.

### The tail allowance, measured: what finishing the board is worth

The published slip schedules are cumulative *ceilings* on the whole board. Verhaard's permits twelve
mismatched edges and never a thirteenth, so a board that cannot finish its last cells perfectly
cannot finish at all. Every result in the tables above sits at exactly twelve breaks — **not one of
them ever spent fewer** — which is the signature of a search stopped by its allowance rather than by
the puzzle.

That allowance is costing score, and the arithmetic says so before any run does. Past depth 197
every cell joins exactly two edges and can break at most one, so an empty cell costs **two** edges
where a mismatch costs **one**. Any break that buys a placement is worth net +1 or better. A ceiling
that blocks completion there is not conservative, it is destructive.

`tailFromDepth` and `tailBreakBonus` add a flat allowance on top of the schedule from one depth
onward. `core.Bench endgame` sweeps it.

| budget | bonus | nodes/sec | score | tiles | perfect | breaks |
|---|---|---|---|---|---|---|
| 100M | +0 | 33.3 M | 452 / 480 | 248 / 256 | 205 | 12 |
| 100M | +2 | 28.6 M | 456 / 480 | 251 / 256 | 205 | 14 |
| 100M | +5 | 27.7 M | 461 / 480 | 255 / 256 | 205 | 17 |
| 100M | +6 | 28.6 M | **462 / 480** | **256 / 256** | 205 | 18 |
| 1B | +0 | 29.4 M | 454 / 480 | 249 / 256 | 207 | 12 |
| 1B | +2 | 29.7 M | 458 / 480 | 252 / 256 | 207 | 14 |
| 1B | +3 | 29.4 M | 459 / 480 | 253 / 256 | 207 | 15 |
| 1B | +4 | 28.2 M | **464 / 480** | **256 / 256** | 207 | 16 |
| 1B | +5 … +8 | — | 464 / 480 | 256 / 256 | 207 | 16 |
| 10B | +0 | 27.0 M | 456 / 480 | 250 / 256 | 207 | 12 |
| 10B | +2 | 26.8 M | 460 / 480 | 253 / 256 | 207 | 14 |
| 10B | +3 | 26.6 M | 461 / 480 | 254 / 256 | 207 | 15 |
| 10B | +4 | 21.6 M | **464 / 480** | **256 / 256** | 207 | 16 |
| 10B | +5 | 22.6 M | 464 / 480 | 256 / 256 | 207 | 16 |

**464 matched edges at a billion nodes, against 456 at ten billion without it.** Eight more edges
for a tenth of the compute, from a dial that did not exist. The engine had never once finished a
board; it now finishes at +4 and the score becomes 480 less whatever it broke.

**It saturates in the allowance, and the saturation is the point.** Beyond +4 nothing changes — the
board is already finished, so more allowance is simply unused. The dial is not a score knob to be
turned up; it is a constraint being removed, and once it is gone the search is bounded by something
else again.

**It saturates in the budget too, and that is the more interesting half.** 464 at a billion nodes;
**464 at ten billion.** Ten times the compute buys nothing once the ceiling is off. The unaided
engine climbs 454 → 456 over that same decade, so the dial does not merely accelerate the old curve
— it converts a ten-billion-node problem into a one-billion-node one and then stops paying
altogether. Whatever bounds the score at 464 is neither the ceiling nor compute at this scale, and
this document cannot yet say what it is.

The baseline row is the cross-check: 10B at +0 reproduces 250 pieces / 456 edges exactly, which is
the board the colour-quota section above records as this project's best before the dial existed.

**Tiles placed stops being a progress measure here, and the `perfect` column is why the tables carry
it.** At +6 the engine reports 256 / 256. That is a *filled* board, not a solved one: it carries 18
mismatched edges and `Validator.validateComplete` rejects it. Any board can be filled by paying
enough breaks. The error-free column does not move at all across the sweep — 205 at every rung at
100M, 207 at every rung at 1B — because the allowance buys depth in the tail and changes nothing
about how far the search gets cleanly.

**Where the allowance is spent matters as much as how much of it there is.** Four extra breaks, one
billion nodes, the same configuration, varying only the depth they become available:

| `tailFromDepth` | score | tiles | breaks |
|---|---|---|---|
| 224 | 450 / 480 | 249 / 256 | 16 |
| 232 | 455 / 480 | 251 / 256 | 15 |
| 240 | 458 / 480 | 253 / 256 | 16 |
| **244** | **464 / 480** | **256 / 256** | 16 |
| 248 | 458 / 480 | 253 / 256 | 16 |

**244 is a real optimum, and offering the allowance early is worse than not offering it at all** —
450 at depth 224 against 454 with no bonus whatever. Breaks spent before the tail buy depth the
search would have reached anyway, and then are not there when the last cells need them. The peak
sits at 256 - 12, the last twelve cells, which is the same window the 2026 fleet identified from the
other direction. Their claim that the score gap *lives* in those cells does not hold here, but their
claim that it is the window worth acting on does.

**Where the damage sits, from `core.Bench endgame`'s own report on the 464 board:**

| band | mismatched edges |
|---|---|
| 192-223 | 5 |
| 224-239 | 4 |
| 240-247 | 4 |
| 248-255 | 3 |

Twelve of the sixteen are the schedule's own, spread from 192; only the last four came from the tail
allowance. So the 2026 fleet's report that *"the entire score gap lives in these twelve cells"* does
**not** hold on this engine — a quarter of ours sits below depth 224.

**What this is not.** 464 is a score board with sixteen deliberately broken edges. It is not a
solution and not a step toward one. It does, however, move this engine into the same regime as the
record: a completing search is judged on how few edges it breaks, and the standing record of 470
completes with ten. Sixteen against ten is the honest distance, and `docs/TYING-THE-RECORD.md` costs
what closing it takes.

**The default stays at a zero bonus**, so every figure elsewhere in this document still describes the
engine a reader gets without asking for anything.

### Fill orders, measured without running a search

Two different things are worth knowing about a fill order, and they disagree, so `core.Bench order`
prints both. *Peak open frontier* is the most placed cells that simultaneously still touch an empty one —
how much board is held open at once. *Detection lag* is how many placements happen between a cell and the
last neighbour that can contradict it — how long a mistake survives.

| board | order | peak open frontier | worst lag | lag in the deep tail | mean tail lag |
|---|---|---|---|---|---|
| 16×16 | `rowMajor` | **16** | **16** | 16 | 11.36 |
| 16×16 | `banded` | 19 | 64 | **8** | **4.86** |

**The banded order is worse on peak open frontier, and that is the price of it.** Holding a bottom band
empty means more of the board is open at once, and the phase boundary leaves row 10's south sides
uncommitted for up to 64 placements. What it buys is the last column: once the search is past depth 201,
where the tree is most expensive, a mistake surfaces in at most 8 placements instead of 16, and on average
in under 5 instead of over 11. The same trade holds at every board size from 6 to 20, which the test suite
asserts.

**And the two orders have not yet been told apart by a search.** Run against Eternity II at an equal node
budget, the banded order is ahead at 20M nodes (204 pieces / 376 edges against 202 / 375) and level at
500M (207 / 382 against 207 / 385, the plain scan marginally ahead on edges). That is consistent with
the structural measure rather than against it: the banded order only differs from a plain scan past
depth 201, and 500M nodes gets to 207. Until the search routinely works deep in the bottom band there is
nothing for the phases to improve, so `fillOrder` is exposed as a setting and left for the lab to settle.

### Time to first solution on solvable instances

MRV vs lowest-index ordering **inside the same bitset machinery**, so only the heuristic differs.
5 seeds each, 2M node cap:

| board | colours | regime | MRV nodes | MRV ms | rowMajor nodes | rowMajor ms |
|---|---|---|---|---|---|---|
| 6×6 | 5 | **hard** | **264 841** | **125** | 2 861 620 | 557 |
| 8×8 | 12 | easy | 271 594 | 127 | **77 154** | **22** |
| 8×8 | 7 | too hard (0/5 both) | 10 000 000 | 4309 | 10 000 000 | 2860 |
| 10×10 | 24 | easy | 22 914 | 14 | **13 995** | **3** |
| 12×12 | 30 | easy | 42 198 | 32 | **37 973** | **16** |

**The honest conclusion: MRV is a big win in the hard regime and a mild loss in easy regimes.**
On the 6×6 hard-regime row it cuts nodes by **10.8×** and wall-clock by **4.5×**. On loosely-constrained
instances any ordering works, so the heuristic's overhead is wasted.

Eternity II (16×16, 22 colours) sits squarely in the hard regime, which is the case MRV is built for —
and is also exactly why the puzzle is unsolved.

### The phase transition

Generated instances show the classic random-CSP hardness peak. Worst case over several seeds, 2–3M node cap:

| board | colours | result |
|---|---|---|
| 8×8 | 4 | solved, ≤ 290k nodes |
| 8×8 | 5–8 | **often unsolved** (hard region) |
| 8×8 | 10–12 | solved, ≤ 190k nodes |
| 16×16 | 22 (E2-like) | unsolved |
| 16×16 | 64 | solved, 3.5k nodes |
| 20×20 | 96 | solved, 4.8k nodes |

So the solver scales to and beyond the real board size when the instance is not in the hard region.

---

## The test suite

`java -cp out core.AllTests` → **1368 checks, 0 failures, ~7 s.** No JUnit dependency; `T.java` is a
60-line assertion helper so the suite runs with nothing but a JDK. Exits 1 on failure for CI.

| Test file | What it covers |
|---|---|
| `SidesTest` | Pack/unpack round-trips for every colour combination; `rotateCW` is `{l,t,r,b}→{b,l,t,r}`; four rotations are the identity; grey counting and corner-adjacency. |
| `PiecesTest` | Guards the **board design**: 256 pieces, colours 0–22, exactly 4 corners / 56 edges / 196 interior, corner greys adjacent, grey total == 4·16, piece 139's sides and its rotation-1 orientation. |
| `InstanceTest` | Instance geometry and invariants, canonical-rotation logic, `piecesCopy` independence, and 9 input-validation rejections. |
| `GeneratorTest` | The generator produces well-formed, solvable instances: correct corner/edge/interior counts for every size, exact grey budget, no grey on interior edges, determinism per seed. |
| `ValidatorTest` | That the checker **rejects** what it should: duplicated pieces, a rotated piece, swapped pieces, a coloured side facing off-board, a moved fixed piece, bad ids, wrong sizes. A validator that always passed would invalidate the whole suite. |
| `SolverConfigTest` | The configurable options: parsing and clamping of all 14 settings, every value solving correctly, and the two invariants that catch almost any plumbing mistake — changing the look-ahead level or the cell order must never change how many solutions exist. |
| `SolverTest` | The **pre-existing** row-major solver: rotation table correctness, `cand[l][t]` keys, de-duplication, `candByTop` = union of `cand[*][t]`, all fixed-piece constants, bounded runs, determinism, and that its snapshot fills exactly a row-major prefix. |
| `MrvSolverTest` | The MRV machinery — see below. |
| `FillOrderTest` | The fixed orders, structurally: the north-and-west invariant at every size from 2 to 20, the exact phase boundaries of the banded order at 16×16, and both frontier measures. No search is run. |
| `ScanSolverTest` | What only the scan engine can get wrong: that it places in exactly its fill order, that the hint piece appears where it must and nowhere else, that an impossible fixed placement is rejected with the cell and reason in the message, that the node budget is not overshot, and that a second run of the same solver is identical. |
| `ProgressLogTest` | What a verbose attempt writes down: that a board filled with breaks reaches the log, that a rise in the error-free reach reaches it too, and that a quiet attempt still writes nothing. |
| `ScanVariationTest` | The seed: that it is ignored unless asked for, that one seed reproduces a run exactly, that different seeds reach different boards, that no seed changes the solution set *or the node count* of an exhaustive run, and that a restart re-shuffles the index instead of re-walking the same tree, and what the root offers a seed. |
| `ColourQuotaTest` | The colour quota: Blackwood's ramp reproduced at 16x16 and scaled elsewhere, that the tracked colours really are offered first (which is what makes abandoning a run sound), that the floor is met at every depth of the board the engine returns, that the gate only ever *removes* solutions from an exhaustive run, and that a colour the instance cannot count is refused with the colour in the message. |
| `EdgeSlippingTest` | The four slipping rules, read back off the board the engine produced instead of taken from its counters: at most one break per piece, never against a border colour, both published schedules reproduced verbatim at 16x16, the ceiling never exceeded, `total - k` scoring, and that a finished board with breaks is never reported as a solution. |
| `PortfolioSearchTest` | That several workers never do worse than one of them alone, that nodes are genuinely summed across workers, that a solve still validates, and that the same seed and worker count reproduce exactly. |
| `CrossValidationTest` | **The strongest evidence:** exhaustive solution counts vs the naive reference solver, for both fast engines, with slipping off. |

### The three tests that matter most

**1. Cached masks vs brute force.** An independent, slow enumeration of the rules recomputes the legal
candidate set for every empty cell, and it must match the cached bitset masks — checked on the initial
state and after each of 12 random placements, on a 4×4, a 5×5 with a fixed piece, and the real 16×16 board.

**2. `place()`/`unplace()` is a perfect inverse.** Deep random walks (40 placements) followed by full
unwinding must restore `avail`, every cell mask, every cell count, the board, `used[]` and the placed
count **byte for byte**. This is the single most common source of bugs in a backtracking search, and it is
what let me safely add the subset-tightening optimisation afterwards.

**3. Incremental counts never drift.** `cellCount[]` is checked against full recomputation after *every*
place and *every* unplace, on four instances including Eternity II. Incremental counters that silently
diverge are exactly the failure mode of optimisation (2) and (3) above.

### Cross-validation results

Both fast engines are made to enumerate **every** solution of small instances and the totals are compared
against the naive `RefSolver`. All nine cases agree exactly:

| instance | solutions | ref nodes | mrv nodes | scan nodes |
|---|---|---|---|---|
| 3×3, 1 colour | 576 | 8 529 | 1 026 | 1 797 |
| 3×3, 2 colours | 96 | 720 | 297 | 471 |
| 3×3, 3 colours, +fixed | 1 | 25 | 10 | 24 |
| 4×4, 2 colours | **5 184** | 1 211 960 | 30 808 | 117 820 |
| 4×4, 3 colours | 24 | 18 292 | 1 171 | 2 802 |
| 4×4, 3 colours, +fixed | 8 | 2 018 | 316 | 1 002 |
| 4×4, 4 colours | 8 | 1 649 | 340 | 528 |
| 5×5, 4 colours | 128 | 1 173 814 | 7 690 | 165 169 |
| 5×5, 5 colours, +fixed | 6 | 8 694 | 807 | 2 087 |

Counting alone would not be enough: two engines could find the same *number* of different boards. So the
two fast engines also collect every solution they report and their **sets of boards** are compared, which
is the check that a fixed order, a two-colour index or the withheld hint piece would break. The banded and
row-major fill orders are compared against each other the same way.

If MRV ordering, the bitset pruning, the rotation de-duplication or the "no grey in the interior"
assumption were wrong in any way, these counts would diverge. Unsatisfiable instances are also checked —
all three solvers must report exactly 0.

**Cross-validation runs with edge slipping off, and that is deliberate.** It compares solution
*sets*, and slipping breaks that equivalence by design, so weakening it to accommodate slipping
would throw away the suite's strongest evidence. `EdgeSlippingTest` covers slipping separately --
including the one cross-engine invariant that does survive: turning slipping on must not change how
many *perfect* solutions a small instance has, because a break can finish a board but never claim
one.

---

## Bugs and limitations found along the way

* The original `forwardCheck` in `Solver.java` had a dead branch: it probed the below-neighbour's left
  constraint using a cell that row-major order had not filled yet. Fixed.
* Janino (the compiler used to verify this in a JRE-only sandbox) does not implement generics, so
  `Solver`'s table building was rewritten as an allocation-free two-pass count-then-fill. That is faster
  anyway. `Puzzle.java` needed an explicit `(int[])` cast on `clone()` for the same reason.
* **MRV is not a universal win.** It loses to plain row-major ordering on loosely-constrained instances.
  If you ever want the best of both, a hybrid that uses MRV only while the minimum candidate count is
  small would be the thing to try.

## What was deliberately not done

* **Conflict-driven backjumping / nogood learning** — analysed earlier as a poor return on this problem
  class once MRV and forward checking are in place.
* **Parallel search for `MrvSolver`.** Splitting on the top-left corner piece × rotation and giving each
  worker its own `MrvSolver` (state is only ~150 KB per worker) is still open. `ScanSolver` got a
  differently-shaped version of this instead -- see below -- because its lack of any per-attempt state
  makes the split trivial; MRV's restart/tie-break machinery would need more thought to parallelise the
  same way.
* **A varying scan attempt** — done, twice and independently. `ScanSolver` permutes the (word, mask)
  entries within each key's candidate run from `randomSeed` at construction (`shuffleRuns`), instead of
  always trying the lowest-numbered piece first; the tuner randomises `randomSeed` on every automatic
  attempt, so repeating `engine=scan` explores a different descent each time. `valueOrder` and
  `shuffleStrength` expose the same idea as explicit dials with a reproducible unseeded baseline at
  `randomSeed=0`. Both mechanisms are live and compose.
* **Restarts for `ScanSolver`** — done, and worth much less than expected. A restart re-shuffles the
  candidate index from the advanced RNG stream rather than merely emptying the board. Measured across
  six seeds, median and maximum are unmoved and only the worst case improves by a piece: restarts cure
  heavy-tailed runtimes, and a slipping descent has no tail, because it always reaches ~245 and is
  never stuck.
* **Cross-attempt parallelism** — done, for `ScanSolver` only. `app.Engine` runs `engine=scan` as a
  `core.PortfolioSearch` of one independently-seeded `ScanSolver` per available core (each against the
  full `nodeBudget`, not a shared fraction of it) and reports whichever finds the best board; `--workers=N`
  overrides the auto-detected count, and `--workers=1` forces the plain single-descent path. This needed no
  change to the Python supervisor at all -- one subprocess, now internally multi-threaded, is still one
  subprocess from its point of view. Measured on the real puzzle at 20M nodes per worker on a 16-core
  machine: 247/256 pieces, 450/480 edges in ~1s wall-clock, against 241/256 and 440/480 in 22s for a single
  `ScanSolver` at 1B nodes (docs above) -- the same total node budget, spent across cores instead of one.
  `MrvSolver` attempts are not parallelised this way; see the first bullet above.
* **Reaching inside a `(word, mask)` pair.** The seeded permutation reorders a key's entries, which
  leaves the candidates that share a 64-bit word in their natural relative order — about one key in
  five on Eternity II, including the opening move. Rotating the mask in the loop would fix it for two
  instructions per candidate examined. Not done, because the seeds already spread without it, so the
  instructions would be spent to buy something that has not been shown to be missing.
* **Eight seeds or one long descent** — measured; see [the overnight eight-arm run](#the-overnight-eight-arm-run).
  It is a tie, and both routes stop at the same score.
