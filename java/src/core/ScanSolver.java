package core;

/**
 * Edge-matching solver built around a fixed cell order, a two-colour candidate
 * index and a bitset piece pool.  Where {@link MrvSolver} spends work choosing
 * a good cell and pruning hard, this one spends almost nothing per node and
 * relies on the order to surface failures early.
 *
 * ------------------------------------------------------------- the invariant
 *
 * Every order in {@link FillOrder} delivers a cell with exactly its NORTH and
 * WEST neighbours placed and its EAST and SOUTH neighbours empty.  That single
 * property is what the rest of this class is made of:
 *
 *   - a cell is constrained by exactly two known colours, so there is one
 *     candidate key and one code path for the whole board;
 *   - the two unknown sides face empty cells, so nothing has to be undone on a
 *     neighbour when a piece goes down or comes back up.
 *
 * The constructor rejects any order that breaks it, with the offending cell in
 * the message.
 *
 * ---------------------------------------------------- two-colour index (key)
 *
 * The west neighbour's RIGHT colour is this cell's required LEFT colour, and
 * the north neighbour's BOTTOM colour is its required TOP colour; off the
 * board both are GREY.  Two colours in [0, numColours) therefore identify the
 * constraint, and the radix comes from the instance:
 *
 *     colourKey = top * numColours + left
 *
 * The other two sides are not free either, but what they must be depends only
 * on where the cell is, never on the search.  A cell in the last column must
 * show GREY on the right, one anywhere else must not (the piece set has
 * exactly 4n grey sides and the border needs all of them -- see
 * {@link Instance#greyBudgetIsExact}); the same holds for the last row and the
 * bottom.  Cells north or west of a fixed placement are pinned to one exact
 * colour instead.  Those combinations are deduplicated into a handful of
 * "classes", and the class contributes a block offset:
 *
 *     key = classBase[depth] + top * numColours + left
 *
 * so getting a cell's candidates is one add and two array reads, with no mask
 * intersection and no colour comparison anywhere in the search.
 *
 * A candidate entry stores only the two sides the cell leaves exposed, as
 * {@code sideR[v]} and {@code sideBScaled[v] = bottom * numColours}; the other
 * two are implicit in the key it was filed under.  Pre-scaling the bottom
 * colour means the next cell's key needs no multiply.
 *
 * -------------------------------------------------- the used-piece test, gone
 *
 * Measurement on fast engines puts ~59% of all candidate examinations on
 * pieces that are already on the board, and the unpredictable branch that
 * rejects them is the single most expensive thing in the loop.  So there is no
 * such test here.  Variants are numbered v = pieceId * 4 + rot exactly as in
 * {@link MrvSolver}, which puts all four variants of a piece in one 64-bit
 * word at bit offset (id & 15) * 4, and a key's candidates are stored as the
 * (word, mask) pairs they occupy.  Intersecting a pair with the live word of
 * {@code avail} yields only unused candidates:
 *
 *     bits = keyMask[i] & avail[keyWord[i]]
 *
 * Used pieces are never examined, so they cannot be mispredicted.  Placing is
 * one AND that clears a whole nibble, un-placing is one store of the word that
 * was saved in a register on the way in.
 *
 * -------------------------------------------------------- fixed placements
 *
 * The hint piece is not pre-placed -- with a fixed order it cannot be, because
 * its neighbours are not down yet.  Instead it is removed from every ordinary
 * class, so no other cell can take it, and its own cell gets a private class
 * holding that one variant.  The cells north and west of it get classes that
 * pin the facing colour, which is what makes a wrong choice there fail at its
 * own depth rather than sixteen placements later.  The effect is exactly the
 * pre-placement {@link MrvSolver} does, expressed as table data.
 *
 * ---------------------------------------------------------- edge slipping
 *
 * An exact search cannot score above whatever perfect prefix it reaches: once
 * no piece fits the next cell it can only back up.  With {@code slipSchedule}
 * set, the search may instead place a piece that deliberately mismatches ONE
 * of its two known sides -- a "break" -- and carry the cost.  Four rules, all
 * taken from the published engines:
 *
 *   - at most one break per placed piece, so a candidate that mismatches both
 *     its left and its top is never offered;
 *   - never a break involving a border colour, so every side facing off the
 *     board stays GREY and the board stays legal apart from its interior
 *     mismatches;
 *   - a cumulative ceiling by depth, see {@link #breakCeilings}: the first
 *     ~80% of the board must be perfect and the breaks are spent in the tail;
 *   - perfect candidates first, so a cell that may not break this turn never
 *     looks at a slipped candidate.
 *
 * The candidate index carries the slipped sets, so offering them is still two
 * array reads.  A variant has exactly one left colour, so the buckets of one
 * (class, top) row are disjoint and "any other non-grey left" is the whole row
 * minus the exact bucket; reading down a column gives the same for the top.
 * Each key's three runs -- perfect, left-broken, top-broken -- are stored back
 * to back in {@link #keyWord} / {@link #keyMask}, delimited by
 * {@code keyStart}, {@code keyPerfectEnd} and {@code keyLeftBreakEnd}.
 *
 * Slipping changes what a finished board means, and the engine is careful
 * about it: a board completed with breaks is recorded as the best seen and the
 * search carries on.  Only a board with no breaks is reported as a solution.
 *
 * ------------------------------------------------------------------- config
 *
 * This engine reads {@code fillOrder}, {@code slipSchedule},
 * {@code greyInteriorPruning} and {@code nodeBudget} from its
 * {@link SolverConfig} and nothing else: it has no cell heuristic to tune, no
 * value order and no randomness, so the remaining settings belong to
 * {@link MrvSolver}.
 */
public final class ScanSolver implements Search {

    public static final int GREY = Sides.GREY;

    /** A side that may be any colour at all. */
    private static final int SPEC_ANY = -1;
    /** A side that may be any colour except GREY. */
    private static final int SPEC_NON_GREY = -2;

    /**
     * Blackwood's published break ceiling, given as the depth by which each
     * successive break becomes permissible: one by depth 201, two by 206, and
     * so on to ten by 239.  Exactly ten entries, and copied verbatim -- a
     * leave-one-out sweep of his engine made depth-248 runs about seventy
     * times rarer for the loss of any single number, so this is not a place to
     * tune.  His source contains no rule against two breaks being adjacent,
     * despite the claim being widely repeated.
     */
    private static final int[] SCHEDULE_BLACKWOOD =
        { 201, 206, 211, 216, 221, 225, 229, 233, 237, 239 };

    /**
     * Verhaard's ceiling, arrived at independently in a record-setting solver:
     * the first 193 placements must be perfect and twelve breaks are spent
     * over the last sixty cells.  The same shape, slightly more generous.
     */
    private static final int[] SCHEDULE_VERHAARD =
        { 193, 202, 209, 214, 218, 222, 226, 229, 232, 235, 238, 240 };

    /** The board the published schedules are quoted for. */
    private static final int SCHEDULE_CELLS = 256;

    // ------------------------------------------------------------------ inputs

    public final Instance inst;
    public final SolverConfig cfg;
    private final int n, cells, numPieces, numVariants, words, numColours;
    private final long nodeBudget;

    /** Stop after the first solution (default) or enumerate all of them. */
    public boolean stopAtFirstSolution = true;
    /** Abort once this many nodes have been expanded. */
    public long maxNodes = Long.MAX_VALUE;
    /** Print progress to stdout. */
    public boolean verbose = false;

    /** Optional observer; see {@link SolveListener}. */
    public SolveListener listener;
    /** How often {@link SolveListener#onSample} fires. */
    public long sampleEveryNodes = 250000L;

    // ------------------------------------------------------------- static tables

    /** Depth -> cell, the fixed fill order. */
    private final int[] order;
    /** Depth -> depth of the cell above it, or {@code cells} when off the board. */
    private final int[] northSlot;
    /** Depth -> depth of the cell left of it, or {@code cells} when off the board. */
    private final int[] westSlot;
    /** Depth -> the candidate block its cell's class occupies. */
    private final int[] classBase;

    /** CSR over (class, colourKey): where that key's (word, mask) pairs start. */
    private final int[] keyStart;
    /** Where a key's perfect run ends and its left-broken run begins. */
    private final int[] keyPerfectEnd;
    /** Where a key's left-broken run ends and its top-broken run begins. */
    private final int[] keyLeftBreakEnd;
    private final int[] keyWord;
    private final long[] keyMask;
    private final int numClasses;

    /** Depth -> the most broken edges the board may carry by then. */
    private final int[] breakCeiling;
    /** Depth -> internal edges joined up by the first {@code depth} placements. */
    private final int[] checksBefore;
    /** Whether the configured schedule lets this board slip at all. */
    private final boolean slipping;

    /** Variant -> its exposed right colour; index numVariants is the GREY sentinel. */
    private final int[] sideR;
    /** Variant -> its exposed bottom colour times numColours; same sentinel. */
    private final int[] sideBScaled;
    private final int[] variantSides;
    private final long[] exist;

    // ------------------------------------------------------------- search state

    private final long[] avail;
    /** Depth -> the variant placed there; index cells holds the off-board sentinel. */
    private final int[] chosen;

    private long sampleCountdown;
    private long startNanos;

    // --------------------------------------------------------------- statistics

    public long nodes;
    public long solutions;
    public int placed;
    public int bestPlaced;
    public boolean aborted;
    public int[] bestBoard;
    /** Matched internal edges of {@link #bestBoard}, out of 480 on Eternity II. */
    public int bestMatchedEdges;
    /** Deliberately mismatched edges of {@link #bestBoard}; 0 without slipping. */
    public int bestBreaks;
    public int[] solutionBoard;
    /** Cells of the deepest board, in the order they were placed. */
    public int[] bestOrderCells;
    /** Variants of the deepest board, parallel to {@link #bestOrderCells}. */
    public int[] bestOrderVariants;
    public int bestOrderLength;

    // ------------------------------------------------------------------ build

    public ScanSolver(Instance inst) {
        this(inst, new SolverConfig());
    }

    public ScanSolver(Instance inst, SolverConfig config) {
        this.inst = inst;
        this.cfg = (config == null) ? new SolverConfig() : config;
        this.n = inst.n;
        this.cells = inst.cells;
        this.numPieces = inst.numPieces;
        this.numVariants = inst.numVariants;
        this.words = inst.words;
        this.numColours = inst.numColours;
        this.nodeBudget = cfg.nodeBudget;

        this.breakCeiling = breakCeilings(cfg.slipSchedule, cells);
        this.slipping = breakCeiling[cells] > 0;

        this.order = (cfg.fillOrder == SolverConfig.FILL_ROW_MAJOR)
                   ? FillOrder.rowMajor(n) : FillOrder.bandedScan(n);
        String orderProblem = FillOrder.validate(order, n);
        if (orderProblem != null) {
            throw new IllegalStateException("fill order is unusable: " + orderProblem);
        }

        this.variantSides = new int[numVariants];
        this.sideR = new int[numVariants + 1];
        this.sideBScaled = new int[numVariants + 1];
        this.exist = new long[words];
        for (int id = 0; id < numPieces; id++) {
            for (int r = 0; r < 4; r++) {
                if (!inst.isCanonicalRotation(id, r)) continue;
                int v = (id << 2) | r;
                int p = inst.variantSides(id, r);
                variantSides[v] = p;
                sideR[v] = Sides.right(p);
                sideBScaled[v] = Sides.bottom(p) * numColours;
                exist[v >>> 6] |= 1L << (v & 63);
            }
        }
        // The sentinel slot stands for "off the board", which is GREY on both
        // sides, and GREY is colour 0, so both entries are already 0.

        int[] fixedVariant = canonicalFixedVariants();
        boolean[] pieceIsFixed = new boolean[numPieces];
        for (int i = 0; i < fixedVariant.length; i++) {
            pieceIsFixed[inst.fixedPiece[i]] = true;
        }
        checkFixedPlacements(fixedVariant);

        // --- one class per distinct (right spec, bottom spec), plus a private
        //     class per fixed cell holding only that placement ----------------
        int interior = (cfg.greyInteriorPruning && inst.greyBudgetIsExact())
                     ? SPEC_NON_GREY : SPEC_ANY;
        int[] rightSpec = new int[cells];
        int[] bottomSpec = new int[cells];
        for (int cell = 0; cell < cells; cell++) {
            int r = cell / n, c = cell - r * n;
            rightSpec[cell]  = (c == n - 1) ? GREY : interior;
            bottomSpec[cell] = (r == n - 1) ? GREY : interior;
        }
        int[] fixedAtCell = new int[cells];
        for (int cell = 0; cell < cells; cell++) fixedAtCell[cell] = -1;
        for (int i = 0; i < fixedVariant.length; i++) fixedAtCell[inst.fixedCell[i]] = i;
        for (int i = 0; i < fixedVariant.length; i++) {
            int cell = inst.fixedCell[i];
            int p = variantSides[fixedVariant[i]];
            int r = cell / n, c = cell - r * n;
            if (r > 0 && fixedAtCell[cell - n] < 0) bottomSpec[cell - n] = Sides.top(p);
            if (c > 0 && fixedAtCell[cell - 1] < 0) rightSpec[cell - 1] = Sides.left(p);
        }

        // Four border combinations, plus at most a private class and two
        // pinned-neighbour classes for each fixed placement.
        int maxClasses = 4 + 3 * fixedVariant.length;
        int[] classRight = new int[maxClasses];
        int[] classBottom = new int[maxClasses];
        int[] classVariant = new int[maxClasses];
        int[] classOfCell = new int[cells];
        int numClasses = 0;
        for (int cell = 0; cell < cells; cell++) {
            int fixedIndex = fixedAtCell[cell];
            int want = (fixedIndex < 0) ? -1 : fixedVariant[fixedIndex];
            int found = -1;
            if (want < 0) {
                for (int k = 0; k < numClasses; k++) {
                    if (classVariant[k] < 0 && classRight[k] == rightSpec[cell]
                            && classBottom[k] == bottomSpec[cell]) { found = k; break; }
                }
            }
            if (found < 0) {
                found = numClasses++;
                classRight[found] = rightSpec[cell];
                classBottom[found] = bottomSpec[cell];
                classVariant[found] = want;
            }
            classOfCell[cell] = found;
        }
        this.numClasses = numClasses;

        // --- candidate tables ------------------------------------------------
        int pairs = numColours * numColours;
        int buckets = numClasses * pairs;
        long[] bits = new long[buckets * words];
        for (int v = 0; v < numVariants; v++) {
            if ((exist[v >>> 6] & (1L << (v & 63))) == 0L) continue;
            int p = variantSides[v];
            int colourKey = Sides.top(p) * numColours + Sides.left(p);
            boolean fixedPiece = pieceIsFixed[v >>> 2];
            for (int k = 0; k < numClasses; k++) {
                if (classVariant[k] >= 0) {
                    if (classVariant[k] != v) continue;
                } else if (fixedPiece) {
                    continue;
                }
                if (!allows(classRight[k], Sides.right(p))) continue;
                if (!allows(classBottom[k], Sides.bottom(p))) continue;
                bits[(k * pairs + colourKey) * words + (v >>> 6)] |= 1L << (v & 63);
            }
        }

        // --- slipped candidate tables ----------------------------------------
        // Break on the left: keep the required top colour, take any other
        // non-grey left colour.  A variant has exactly one left colour, so the
        // buckets along a (class, top) row are disjoint and that set is the
        // whole row minus the exact bucket.  Break on the top is the same read
        // down a (class, left) column.  A candidate that mismatches both sides
        // is in neither set, which is what limits a piece to one break.
        long[] leftBits = new long[buckets * words];
        long[] topBits = new long[buckets * words];
        if (slipping) {
            long[] row = new long[numClasses * numColours * words];
            long[] col = new long[numClasses * numColours * words];
            for (int k = 0; k < numClasses; k++) {
                for (int t = 0; t < numColours; t++) {
                    for (int l = 0; l < numColours; l++) {
                        int b = (k * pairs + t * numColours + l) * words;
                        if (l != GREY) {
                            int r = (k * numColours + t) * words;
                            for (int w = 0; w < words; w++) row[r + w] |= bits[b + w];
                        }
                        if (t != GREY) {
                            int c = (k * numColours + l) * words;
                            for (int w = 0; w < words; w++) col[c + w] |= bits[b + w];
                        }
                    }
                }
            }
            for (int k = 0; k < numClasses; k++) {
                for (int t = 0; t < numColours; t++) {
                    for (int l = 0; l < numColours; l++) {
                        int b = (k * pairs + t * numColours + l) * words;
                        // A break never involves a border colour, so a key
                        // that requires GREY offers nothing to slip: the
                        // colour being abandoned faces off the board, and so
                        // would any grey colour taken in its place.
                        if (l != GREY) {
                            int r = (k * numColours + t) * words;
                            for (int w = 0; w < words; w++) {
                                leftBits[b + w] = row[r + w] & ~bits[b + w];
                            }
                        }
                        if (t != GREY) {
                            int c = (k * numColours + l) * words;
                            for (int w = 0; w < words; w++) {
                                topBits[b + w] = col[c + w] & ~bits[b + w];
                            }
                        }
                    }
                }
            }
        }

        // --- one CSR, three runs per key, perfect candidates first -----------
        this.keyStart = new int[buckets + 1];
        this.keyPerfectEnd = new int[buckets];
        this.keyLeftBreakEnd = new int[buckets];
        int entries = 0;
        for (int b = 0; b < buckets; b++) {
            keyStart[b] = entries;
            entries += liveWords(bits, b, words);
            keyPerfectEnd[b] = entries;
            entries += liveWords(leftBits, b, words);
            keyLeftBreakEnd[b] = entries;
            entries += liveWords(topBits, b, words);
        }
        keyStart[buckets] = entries;
        this.keyWord = new int[entries];
        this.keyMask = new long[entries];
        int at = 0;
        for (int b = 0; b < buckets; b++) {
            at = appendRun(bits, b, words, at);
            at = appendRun(leftBits, b, words, at);
            at = appendRun(topBits, b, words, at);
        }

        // --- per-depth lookups ----------------------------------------------
        int[] depthOf = new int[cells];
        for (int d = 0; d < cells; d++) depthOf[order[d]] = d;
        this.northSlot = new int[cells];
        this.westSlot = new int[cells];
        this.classBase = new int[cells];
        for (int d = 0; d < cells; d++) {
            int cell = order[d];
            int r = cell / n, c = cell - r * n;
            northSlot[d] = (r == 0) ? cells : depthOf[cell - n];
            westSlot[d] = (c == 0) ? cells : depthOf[cell - 1];
            classBase[d] = classOfCell[cell] * pairs;
        }

        // Every internal edge is checked exactly once, when the cell south or
        // east of it goes down, so how many are joined up is a constant per
        // depth and a board's score is checksBefore[depth] - breaks.
        this.checksBefore = new int[cells + 1];
        for (int d = 0; d < cells; d++) {
            checksBefore[d + 1] = checksBefore[d]
                + ((northSlot[d] < cells) ? 1 : 0)
                + ((westSlot[d] < cells) ? 1 : 0);
        }

        this.avail = new long[words];
        this.chosen = new int[cells + 1];
        reset();
    }

    /** How many words of one bucket hold any candidate at all. */
    private static int liveWords(long[] bits, int bucket, int words) {
        int count = 0;
        for (int w = 0; w < words; w++) if (bits[bucket * words + w] != 0L) count++;
        return count;
    }

    /** Copy one bucket's live (word, mask) pairs into the index. */
    private int appendRun(long[] bits, int bucket, int words, int at) {
        for (int w = 0; w < words; w++) {
            long m = bits[bucket * words + w];
            if (m == 0L) continue;
            keyWord[at] = w;
            keyMask[at] = m;
            at++;
        }
        return at;
    }

    /**
     * Depth -> the most broken edges a board may carry by that depth.
     *
     * A schedule lists the depth at which each successive break becomes
     * permissible, so the ceiling is how many of its entries that depth has
     * passed.  The published depths are quoted for a 256-cell board and are
     * scaled by {@code cells / 256} the same way {@link FillOrder} scales its
     * phase sizes: the real puzzle gets the schedule verbatim, and every other
     * board size gets the same shape.
     */
    private static int[] breakCeilings(int slipSchedule, int cells) {
        int[] out = new int[cells + 1];
        int[] schedule = scheduleFor(slipSchedule);
        for (int i = 0; i < schedule.length; i++) {
            int depth = (int) ((long) schedule[i] * cells / SCHEDULE_CELLS);
            if (depth > cells) continue;
            for (int d = depth; d <= cells; d++) out[d] = i + 1;
        }
        return out;
    }

    private static int[] scheduleFor(int slipSchedule) {
        if (slipSchedule == SolverConfig.SLIP_BLACKWOOD) return SCHEDULE_BLACKWOOD;
        if (slipSchedule == SolverConfig.SLIP_VERHAARD) return SCHEDULE_VERHAARD;
        return new int[0];
    }

    /** Fold each fixed placement onto the canonical rotation with the same sides. */
    private int[] canonicalFixedVariants() {
        int[] out = new int[inst.fixedCell.length];
        for (int i = 0; i < out.length; i++) {
            int id = inst.fixedPiece[i];
            int want = inst.variantSides(id, inst.fixedRot[i]);
            int v = -1;
            for (int r = 0; r < 4; r++) {
                if (inst.isCanonicalRotation(id, r) && inst.variantSides(id, r) == want) {
                    v = (id << 2) | r;
                    break;
                }
            }
            if (v < 0) {
                throw new IllegalStateException("no canonical variant for fixed piece "
                    + (id + 1) + " rotation " + inst.fixedRot[i]);
            }
            out[i] = v;
        }
        return out;
    }

    /**
     * Fail immediately, and with the reason, on a fixed placement the search
     * could never satisfy: two on one cell, a non-grey side facing off the
     * board, a grey side in the interior, or two that contradict each other.
     */
    private void checkFixedPlacements(int[] fixedVariant) {
        boolean pruneGrey = cfg.greyInteriorPruning && inst.greyBudgetIsExact();
        for (int i = 0; i < fixedVariant.length; i++) {
            int cell = inst.fixedCell[i];
            for (int j = 0; j < i; j++) {
                if (inst.fixedCell[j] == cell) {
                    throw new IllegalStateException("two fixed pieces on cell " + cell);
                }
            }
            int p = variantSides[fixedVariant[i]];
            int r = cell / n, c = cell - r * n;
            String bad = null;
            if (c == 0 && Sides.left(p) != GREY) bad = "left side must be grey";
            else if (r == 0 && Sides.top(p) != GREY) bad = "top side must be grey";
            else if (c == n - 1 && Sides.right(p) != GREY) bad = "right side must be grey";
            else if (r == n - 1 && Sides.bottom(p) != GREY) bad = "bottom side must be grey";
            else if (c != 0 && Sides.left(p) == GREY && pruneGrey) bad = "left side may not be grey";
            else if (r != 0 && Sides.top(p) == GREY && pruneGrey) bad = "top side may not be grey";
            else if (c != n - 1 && Sides.right(p) == GREY && pruneGrey) bad = "right side may not be grey";
            else if (r != n - 1 && Sides.bottom(p) == GREY && pruneGrey) bad = "bottom side may not be grey";
            if (bad != null) {
                throw new IllegalStateException("fixed placement is illegal: cell " + cell
                    + " (r" + r + ",c" + c + ") " + Sides.toString(p) + " -- " + bad);
            }
            for (int j = 0; j < i; j++) {
                int other = inst.fixedCell[j];
                int q = variantSides[fixedVariant[j]];
                if (other == cell - 1 && Sides.right(q) != Sides.left(p)) {
                    throw new IllegalStateException("fixed placements on cells " + other
                        + " and " + cell + " do not match across their shared side");
                }
                if (other == cell - n && Sides.bottom(q) != Sides.top(p)) {
                    throw new IllegalStateException("fixed placements on cells " + other
                        + " and " + cell + " do not match across their shared side");
                }
            }
        }
    }

    private static boolean allows(int spec, int colour) {
        if (spec == SPEC_ANY) return true;
        if (spec == SPEC_NON_GREY) return colour != GREY;
        return colour == spec;
    }

    /** Restore the solver to its initial state: empty board, whole piece pool. */
    public void reset() {
        nodes = 0;
        solutions = 0;
        placed = 0;
        bestPlaced = 0;
        bestMatchedEdges = 0;
        bestBreaks = 0;
        aborted = false;
        bestBoard = null;
        solutionBoard = null;
        bestOrderCells = null;
        bestOrderVariants = null;
        bestOrderLength = 0;
        sampleCountdown = sampleEveryNodes;
        for (int w = 0; w < words; w++) avail[w] = exist[w];
        for (int d = 0; d <= cells; d++) chosen[d] = numVariants;
    }

    // ------------------------------------------------------------------ search

    /**
     * Run the search.  Returns the number of solutions found.  Check
     * {@link #aborted} to see whether the node budget was exhausted first.
     */
    public long solve() {
        startNanos = System.nanoTime();
        dfs(0, 0);
        if (verbose) {
            long ms = elapsedMs();
            System.out.println("ScanSolver done: nodes=" + nodes
                + " solutions=" + solutions
                + " bestPlaced=" + bestPlaced + "/" + cells
                + " bestBreaks=" + bestBreaks
                + " aborted=" + aborted
                + " ms=" + ms
                + " nodes/s=" + (ms == 0 ? 0 : (nodes * 1000L / ms)));
        }
        return solutions;
    }

    /**
     * Fill the cell at {@code depth}, given that the board so far carries
     * {@code breaks} deliberately mismatched edges.
     *
     * @return true when the caller should stop descending.
     */
    private boolean dfs(int depth, int breaks) {
        if (depth == cells) return complete(breaks);

        nodes++;
        placed = depth;
        long cap = (maxNodes < nodeBudget) ? maxNodes : nodeBudget;
        if (nodes >= cap) { aborted = true; return true; }

        if (listener != null) {
            if (--sampleCountdown <= 0) {
                sampleCountdown = sampleEveryNodes;
                listener.onSample(this);
            }
        }

        // Deeper is the headline, but two boards of the same depth are told
        // apart by their score, so a later one that spent fewer breaks
        // getting here replaces the one on record.
        if (depth > bestPlaced
                || (depth == bestPlaced && checksBefore[depth] - breaks > bestMatchedEdges)) {
            bestPlaced = depth;
            recordBest(depth, breaks);
            if (listener != null) listener.onNewBest(this);
            if (verbose && (bestPlaced % 16 == 0 || bestPlaced > cells - 40)) {
                System.out.println("  placed=" + bestPlaced + "/" + cells
                    + " breaks=" + breaks
                    + " nodes=" + nodes + " ms=" + elapsedMs());
            }
        }

        int topScaled = sideBScaled[chosen[northSlot[depth]]];
        int left = sideR[chosen[westSlot[depth]]];
        int key = classBase[depth] + topScaled + left;

        // Perfect candidates first, so a cell that may not break this turn
        // never looks at a slipped one.
        if (descend(depth, breaks, keyStart[key], keyPerfectEnd[key])) return true;
        if (breaks >= breakCeiling[depth]) return false;
        // A side facing off the board requires GREY, and a break may not
        // involve a border colour, so those two keys offer nothing to slip.
        if (left != GREY
                && descend(depth, breaks + 1, keyPerfectEnd[key], keyLeftBreakEnd[key])) {
            return true;
        }
        if (topScaled != 0
                && descend(depth, breaks + 1, keyLeftBreakEnd[key], keyStart[key + 1])) {
            return true;
        }
        return false;
    }

    /**
     * Try every still-unused candidate in one run of (word, mask) pairs.
     * {@code breaks} is what the child board carries, so a slipped run is
     * passed one more than the perfect run is.
     *
     * @return true when the caller should stop descending.
     */
    private boolean descend(int depth, int breaks, int from, int to) {
        for (int i = from; i < to; i++) {
            int w = keyWord[i];
            long live = avail[w];
            long bits = keyMask[i] & live;
            while (bits != 0L) {
                int v = (w << 6) + Long.numberOfTrailingZeros(bits);
                bits &= bits - 1L;
                chosen[depth] = v;
                // One AND clears all four rotations of the piece at once.
                avail[w] = live & ~(0xFL << (v & 0x3C));
                boolean stop = dfs(depth + 1, breaks);
                avail[w] = live;
                if (stop) return true;
            }
        }
        return false;
    }

    /**
     * A board that reached the last cell.  Slipping may finish a board but it
     * may never claim one: only a board with no broken edges counts as a
     * solution, so a slipped board is recorded as the best seen and the search
     * carries on looking for a perfect one.
     */
    private boolean complete(int breaks) {
        placed = cells;
        if (breaks > 0) {
            if (cells > bestPlaced
                    || checksBefore[cells] - breaks > bestMatchedEdges) {
                bestPlaced = cells;
                recordBest(cells, breaks);
                if (listener != null) listener.onNewBest(this);
            }
            return false;
        }
        solutions++;
        if (solutionBoard == null) solutionBoard = new int[cells];
        for (int d = 0; d < cells; d++) solutionBoard[order[d]] = chosen[d];
        bestPlaced = cells;
        recordBest(cells, 0);
        if (listener != null) listener.onSolution(this);
        if (verbose) System.out.println("solution #" + solutions + " at node " + nodes);
        return stopAtFirstSolution;
    }

    /** Snapshot the board as it stands, its score, and the order it was built in. */
    private void recordBest(int depth, int breaks) {
        if (bestBoard == null) bestBoard = new int[cells];
        for (int cell = 0; cell < cells; cell++) bestBoard[cell] = -1;
        if (bestOrderCells == null) {
            bestOrderCells = new int[cells];
            bestOrderVariants = new int[cells];
        }
        for (int d = 0; d < depth; d++) {
            bestBoard[order[d]] = chosen[d];
            bestOrderCells[d] = order[d];
            bestOrderVariants[d] = chosen[d];
        }
        bestOrderLength = depth;
        bestBreaks = breaks;
        // A board is recorded rarely, so counting its edges here keeps the
        // score out of the search loop entirely -- and it is counted by the
        // independent Validator, not derived from the solver's own bookkeeping.
        bestMatchedEdges = Validator.matchedEdges(inst, bestBoard);
    }

    // ------------------------------------------------------------------ Search

    public void setListener(SolveListener l) { this.listener = l; }
    public void setSampleEveryNodes(long n) { this.sampleEveryNodes = n; }
    public void setStopAtFirstSolution(boolean stop) { this.stopAtFirstSolution = stop; }
    /** Bring the search to a halt at its next node; see {@link Search#requestStop}. */
    public void requestStop() { this.maxNodes = 1; }
    public long nodes() { return nodes; }
    public int placedCount() { return placed; }
    public int bestPlaced() { return bestPlaced; }
    public int bestMatchedEdges() { return bestMatchedEdges; }
    public int bestBreaks() { return bestBreaks; }
    /** Always 0: this engine has no randomness, so restarting it changes nothing. */
    public int restarts() { return 0; }
    public boolean aborted() { return aborted; }
    public int[] bestBoard() { return bestBoard; }
    public int[] solutionBoard() { return solutionBoard; }
    public int[] bestOrderCells() { return bestOrderCells; }
    public int[] bestOrderVariants() { return bestOrderVariants; }
    public int bestOrderLength() { return bestOrderLength; }

    public int[] boardSnapshot() {
        int[] out = new int[cells];
        for (int cell = 0; cell < cells; cell++) out[cell] = -1;
        for (int d = 0; d < placed; d++) out[order[d]] = chosen[d];
        return out;
    }

    // --------------------------------------------------------------- accessors

    /** The fill order this solver was built with, depth -> cell. */
    public int[] fillOrder() {
        int[] out = new int[cells];
        System.arraycopy(order, 0, out, 0, cells);
        return out;
    }

    /** How many (word, mask) pairs the candidate index holds in total. */
    public int candidateEntryCount() { return keyWord.length; }

    /** How many of those pairs hold candidates that match on both sides. */
    public int perfectEntryCount() {
        int count = 0;
        for (int b = 0; b < keyPerfectEnd.length; b++) count += keyPerfectEnd[b] - keyStart[b];
        return count;
    }

    /** Whether the configured schedule lets this board slip at all. */
    public boolean slipping() { return slipping; }

    /** Depth -> the most broken edges the board may carry by then. */
    public int[] breakCeilings() {
        int[] out = new int[breakCeiling.length];
        System.arraycopy(breakCeiling, 0, out, 0, breakCeiling.length);
        return out;
    }

    /** How many distinct exposed-side classes the board needed. */
    public int classCount() { return numClasses; }

    /** Candidates this cell class and colour pair offers, ignoring the piece pool. */
    public int candidateCountAtDepth(int depth, int left, int top) {
        int key = classBase[depth] + top * numColours + left;
        int count = 0;
        for (int i = keyStart[key]; i < keyStart[key + 1]; i++) {
            count += Long.bitCount(keyMask[i]);
        }
        return count;
    }

    public int boardWidth() { return n; }
    public int cellTotal() { return cells; }
    public long elapsedMs() { return (System.nanoTime() - startNanos) / 1000000L; }

    /** Render the current board as piece numbers (1-based), '.' for empty. */
    public String boardToString() {
        int[] board = boardSnapshot();
        StringBuilder sb = new StringBuilder();
        sb.append("    ");
        for (int c = 0; c < n; c++) sb.append(String.format("%4d", c));
        sb.append('\n');
        for (int r = 0; r < n; r++) {
            sb.append(String.format("%3d ", r));
            for (int c = 0; c < n; c++) {
                int v = board[r * n + c];
                if (v < 0) sb.append("   .");
                else sb.append(String.format("%4d", (v >>> 2) + 1));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ main

    /**
     * Run the scan solver on the real Eternity II puzzle.
     *
     *   java -cp out core.ScanSolver            # run until stopped
     *   java -cp out core.ScanSolver 50000000   # stop after 50M nodes
     *   java -cp out core.ScanSolver 50000000 --slipSchedule=blackwood
     */
    public static void main(String[] args) {
        SolverConfig cfg = new SolverConfig();
        for (int i = 1; i < args.length; i++) cfg.applyArg(args[i]);
        ScanSolver s = new ScanSolver(Instance.eternity2(), cfg);
        s.verbose = true;
        s.stopAtFirstSolution = true;
        if (args.length > 0) {
            try { s.maxNodes = Long.parseLong(args[0]); } catch (Throwable e) { }
        }
        System.out.println("Eternity II: 16x16, " + s.candidateEntryCount()
            + " candidate index entries across " + s.classCount() + " cell classes");
        long found = s.solve();
        System.out.println();
        if (found >= 1) {
            System.out.println("SOLVED");
            System.out.println(s.boardToString());
            String err = Validator.validateComplete(s.inst, s.solutionBoard);
            System.out.println("validation: " + (err == null ? "OK" : err));
        } else {
            System.out.println("no solution found; deepest board reached ("
                + s.bestPlaced + "/" + s.cells + " pieces, "
                + s.bestMatchedEdges + "/"
                + Validator.internalEdgeTotal(s.inst) + " matched edges, "
                + s.bestBreaks + " broken)");
            if (s.bestBoard != null) {
                String err = Validator.validatePartial(s.inst, s.bestBoard, false,
                                                       s.bestBreaks);
                System.out.println("partial board validation: " + (err == null ? "OK" : err));
            }
        }
    }
}
