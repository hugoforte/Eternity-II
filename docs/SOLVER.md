# The solver

How the search works, why it is built this way, and the measurements behind
the default settings. For the options exposed in the UI see
[SETTINGS.md](SETTINGS.md).

## Running the solver on its own

The solver is a plain Java program; the web app is only a front end for it.

```sh
sh build.sh                                   # compile into java/classes
sh test.sh                                    # 1069 checks, a few seconds

java -cp java/classes core.MrvSolver          # MRV solver on Eternity II
java -cp java/classes core.MrvSolver 50000000 # stop after 50M steps
java -cp java/classes core.ScanSolver         # fixed-scan solver on Eternity II
java -cp java/classes core.Solver             # the older row-major solver
java -cp java/classes core.Bench              # benchmarks
java -cp java/classes core.Bench engines 20   # the two engines, 20s each
java -cp java/classes core.Bench order        # fill-order frontiers, no search
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
| `java/src/core/ScanSolver.java` | **Engine 2**: fixed fill order + two-colour candidate index. ~37x the throughput. |
| `java/src/core/FillOrder.java` | The fixed cell orders, and the frontier measures used to judge them. |
| `java/src/core/Search.java` | What both engines expose to `app.Engine`, so an attempt can run either. |
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
make up the difference. It is still a long way from the 248 pieces / 454 edges a published reference
engine reaches in 60 s on one core, and the gap is edge slipping, which this engine does not do yet.

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

`java -cp out core.AllTests` → **1069 checks, 0 failures, ~7 s.** No JUnit dependency; `T.java` is a
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
| `CrossValidationTest` | **The strongest evidence:** exhaustive solution counts vs the naive reference solver, for both fast engines. |

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
* **Parallel search** — the remaining cheap multiplier. Split on the top-left corner piece × rotation and
  give each worker its own `MrvSolver`; state is only ~150 KB per worker. This is the obvious next step.
* **Randomised restarts.** Backtracking runtimes here are heavy-tailed (see the 8×8/7-colour row, where
  both orderings fail). Randomised value ordering plus restarts is the standard cure and would likely
  help more than any further micro-optimisation — at the cost of the determinism the tests rely on.
* **Edge slipping in `ScanSolver`.** The engine is exact: it only ever places a piece whose two known
  sides match. Most of the remaining score in the published results comes from accepting a mismatched
  edge and carrying the cost, which is a different search and is deliberately left for later.
* **A varying scan attempt.** `ScanSolver` has no randomness and no restart policy, so every attempt with
  the same node budget produces the same board. That is why `engine` still defaults to `mrv`: the lab
  learns nothing from repeating one deterministic run. It becomes the better default once the engine has
  something to vary.
