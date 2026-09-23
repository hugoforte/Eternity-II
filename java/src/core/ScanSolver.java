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
 * ------------------------------------------------------------- seeded variation
 *
 * With no randomness, this engine places the same board for the same config
 * every time, which gives the learner nothing to gain by repeating an
 * attempt.  {@code randomSeed} fixes that without touching the candidate
 * semantics, the bit layout or the board format: {@link #shuffleRuns} permutes
 * the (word, mask) entries within each key's perfect / left-broken /
 * top-broken run once at construction, seeded from {@code randomSeed}, and
 * never moves an entry across a run boundary.  A key whose candidates span
 * more than one word gets a seed-dependent trial order; the set of candidates
 * offered at that key is exactly the same either way.  Piece ids and
 * rotations are never touched, so everything downstream (the board,
 * {@link Validator}, JSON output) is none the wiser.
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
 * ------------------------------------------------------------ colour quota
 *
 * Slipping decides how much mismatch the tail may carry; the colour quota
 * decides what the tail is allowed to be made of.  Three colours are tracked --
 * {@code quotaColours}, one border colour and two interior ones -- and
 * {@code quotaSchedule} gives a floor, by depth, on how many sides of them the
 * board must already have consumed.  A candidate that cannot lift the board to
 * its depth's floor does not merely lose its turn: the whole run is abandoned,
 * which is what makes this a pruning rule rather than an ordering tweak.
 *
 * That is only sound because the candidates carrying the tracked colours come
 * first.  A variant's count is fixed by its piece, so the index is built in
 * descending-count order -- one group of (word, mask) pairs per count, highest
 * first -- and {@link #quotaCount} records the count of each entry.  The first
 * entry that falls short means every entry behind it does too.  With no quota
 * there is a single group holding everything, so the index is laid out exactly
 * as it was before the gate existed and {@link #descend} is the loop it always
 * was.
 *
 * The gate is DELIBERATELY INCOMPLETE: it abandons subtrees that may hold
 * solutions, so it is off by default and is kept out of every test that
 * compares solution sets.
 *
 * ------------------------------------------------- seeded candidate order
 *
 * Everything above is fixed by the instance, so without help this engine runs
 * one descent and repeats it for ever: one configuration, one board, however
 * long it is given.  {@code valueOrder} and {@code shuffleStrength} make the
 * descent depend on {@code randomSeed}, and they do it entirely in the tables.
 *
 * A key's entries are a contiguous run, so the run can simply be permuted --
 * {@link #orderCandidates} restores the natural order from {@link #baseWord} /
 * {@link #baseMask} and reorders each run in place.  The search reads the same
 * two arrays through the same two indices either way, so a seeded order costs
 * the inner loop nothing at all.
 *
 * A permutation was chosen over a seeded starting offset because it is free:
 * both are materialised at construction, so neither is visible from the hot
 * loop, but a run of length L has L rotations and L! permutations, and the
 * longer runs -- up to six entries on Eternity II -- get far more out of being
 * permuted than rotated.
 *
 * What this cannot reach is the order WITHIN one (word, mask) pair: a word
 * holds sixteen pieces and the four rotations of a piece share a nibble, so
 * candidates that land in the same word keep their relative order.  Eternity
 * II has 467 keys that offer anything at all, 263 of them offering a real
 * choice of two candidates or more, and 203 of those spread that choice over
 * more than one entry -- so a run permutation reorders about four keys in
 * five.  Rotating the mask inside the loop would reach the rest, and was
 * rejected: it puts two instructions on every candidate examined, and the
 * seeds already differ.
 *
 * The key where that matters most is the root.  On Eternity II the first
 * square's openings are the four corners, which share a word, so the root is a
 * single entry and no permutation of runs can choose how an attempt begins.
 * A seeded engine therefore gives the first cell a private copy of its class,
 * appended after every real one, and depth 0 reads the copy: its root bucket
 * is held one entry per variant and {@link #orderRoot} alone orders it, from
 * a stream of its own.  The real keys, the first cell's own class among them,
 * are built and ordered exactly as they are without a seed, so a seed changes
 * the opening and nothing else, and an unseeded engine has no copy at all.
 *
 * ------------------------------------------------------------------ restarts
 *
 * Restarts follow {@link SolverConfig#restartBudget}, the same schedule
 * {@link MrvSolver} uses.  A restart here re-shuffles the candidate index from
 * the advanced random stream before starting over, because a restart that only
 * emptied the board would walk the identical tree again; the published
 * guidance is that the cutoff schedule matters much less than re-randomising
 * on restart.  The deepest board seen survives across restarts -- it is only
 * ever replaced by a better one -- so a restart can never cost progress.
 *
 * Restarts are skipped when the candidate order is not seeded, and when
 * enumerating exhaustively: after a restart the search has no record of which
 * solutions it already reported, so it would count them twice.
 *
 * ------------------------------------------------------------------- config
 *
 * This engine reads {@code fillOrder}, {@code slipSchedule},
 * {@code quotaSchedule}, {@code quotaColours}, {@code greyInteriorPruning},
 * {@code nodeBudget}, {@code valueOrder}, {@code shuffleStrength},
 * {@code randomSeed} and the three {@code restart*} settings from its
 * {@link SolverConfig}.  It has no cell heuristic, so the
 * remaining settings belong to {@link MrvSolver}.  {@code valueOrder} has no
 * rarest-colour analogue here -- a key's candidates all carry the same two
 * known colours -- so that value orders the same way {@code natural} does.
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

    /**
     * Blackwood's published colour-quota ramp, as the breakpoints of a
     * piecewise-linear floor: by placement {@link #QUOTA_DEPTHS}[k] the board
     * must have consumed at least {@link #QUOTA_SIDES}[k] sides of the tracked
     * colours.  Interpolating between them reproduces his quoted slopes
     * exactly -- 2.8 a placement from 17 to 26, then 1.43333, 0.9, 0.6538 and
     * finally 1/4.4615 out to 160, after which he stops constraining.  A
     * randomised sweep found the hand-tuned curve near-optimal, so the numbers
     * are data and not a tuning surface.
     *
     * Between breakpoints the ramp is fractional and the count it is compared
     * against is an integer, so a demand of 2.8 sides is either "at least 2" or
     * "at least 3" depending on whether the published array held the truncated
     * value or the real one.  The looser reading is taken -- the floor is
     * truncated -- so that nothing measured here can be blamed on a gate made
     * stricter than the one published.
     */
    private static final int[] QUOTA_DEPTHS = { 16, 26, 56, 76, 102, 160 };
    private static final int[] QUOTA_SIDES  = {  0, 28, 71, 89, 106, 119 };

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
    /** The natural index order, kept only when the runs are reordered. */
    private final int[] baseWord;
    private final long[] baseMask;
    private final int numClasses;

    /** Depth -> the most broken edges the board may carry by then. */
    private final int[] breakCeiling;
    /** Depth -> the fewest quota-colour sides the board must have consumed. */
    private final int[] quotaFloor;
    /** Index entry -> how many quota-colour sides its candidates carry. */
    private final int[] quotaCount;
    /** The colours the quota tracks, empty when it is off. */
    private final int[] quotaColour;
    /** Whether the configured schedule gates this board at all. */
    private final boolean quota;
    /** Depth -> internal edges joined up by the first {@code depth} placements. */
    private final int[] checksBefore;
    /** Whether the configured schedule lets this board slip at all. */
    private final boolean slipping;
    /** Whether the candidate order depends on {@code cfg.randomSeed}. */
    private final boolean seeded;

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
    /** Depth -> quota-colour sides consumed by the pieces placed before it. */
    private final int[] quotaCumulative;

    private long sampleCountdown;
    private long startNanos;
    private long rngState;
    /**
     * The bucket a seeded engine reads at depth 0, in the private class it
     * gives the first cell, held one entry per variant; -1 when unseeded.
     * See {@link #orderRoot}.
     */
    private final int rootBucket;
    /** How many buckets orderRun and shuffleRuns order: every real key, never the root's class. */
    private final int orderedBuckets;
    /** The root's natural order, kept only when a seed is set. */
    private final int[] rootBaseWord;
    private final long[] rootBaseMask;
    /** The root's own random stream, apart from the one the rest of the index draws. */
    private long rootRngState;
    /** The node count this run stops at: the attempt's budget, or a restart cutoff. */
    private long nodeCap;
    private long restartNodeCap = Long.MAX_VALUE;
    private boolean restartHit = false;

    // --------------------------------------------------------------- statistics

    public long nodes;
    public long solutions;
    public int placed;
    public int bestPlaced;
    public boolean aborted;
    public int restarts;
    public int[] bestBoard;
    /** Matched internal edges of {@link #bestBoard}, out of 480 on Eternity II. */
    public int bestMatchedEdges;
    /** Deliberately mismatched edges of {@link #bestBoard}; 0 without slipping. */
    public int bestBreaks;
    /**
     * Tiles of {@link #bestBoard} placed before its first mismatched edge.
     * Equal to {@link #bestPlaced} whenever {@link #bestBreaks} is 0.
     */
    public int bestPerfectTiles;
    /**
     * The deepest node the search ever reached with nothing mismatched yet --
     * the most tiles it placed without buying any depth with a break.
     *
     * This is a maximum over the whole search, not a property of
     * {@link #bestBoard}: a slipped arm's record board is deep and carries
     * breaks, so {@link #bestPerfectTiles} describes that board and says
     * nothing about how far the same search got error-free earlier on. The two
     * coincide only when the record board has no breaks.
     */
    public int deepestErrorFree;
    public int[] solutionBoard;
    /** Cells of the deepest board, in the order they were placed. */
    public int[] bestOrderCells;
    /** Variants of the deepest board, parallel to {@link #bestOrderCells}. */
    public int[] bestOrderVariants;
    public int bestOrderLength;
    /**
     * The best-scoring node the search visited, which is not always the
     * deepest: {@link #bestBoard} is the deepest board and its edges only
     * break ties at equal depth, so a shallower board that spent far fewer
     * breaks can outscore it and go unrecorded there.  This one is scored on
     * the hot path as {@code checksBefore[depth] - breaks}, which the tests
     * hold against {@link Validator#matchedEdges} on the board itself.
     */
    public int[] bestScoreBoard;
    /** Matched edges of {@link #bestScoreBoard}. */
    public int bestScore;
    /** Pieces on {@link #bestScoreBoard}. */
    public int bestScorePlaced;
    /** Breaks {@link #bestScoreBoard} carries. */
    public int bestScoreBreaks;
    /** The shallowest depth whose checks alone exceed {@link #bestScore}. */
    private int scoreWatchDepth;

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

        this.breakCeiling = breakCeilings(cfg.slipSchedule, cells,
                                          cfg.tailFromDepth, cfg.tailBreakBonus);
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
        int maxClasses = 5 + 3 * fixedVariant.length;
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

        // A seed chooses the opening, so a seeded engine gives the first cell a
        // private copy of its own class, appended after every other.  Depth 0
        // reads the copy, whose root bucket is held one entry per variant and
        // ordered by orderRoot alone; every real key -- the first cell's own
        // class included, which later cells still read -- is laid out exactly
        // as it is without a seed.  An unseeded engine has no copy at all.
        int rootClass = -1;
        if (cfg.randomSeed != 0L) {
            int first = classOfCell[order[0]];
            rootClass = numClasses++;
            classRight[rootClass] = classRight[first];
            classBottom[rootClass] = classBottom[first];
            classVariant[rootClass] = classVariant[first];
        }

        // --- the quota's colours, and one candidate group per side count -----
        // The gate abandons a run at the first entry that cannot meet the
        // floor, so the entries have to arrive highest-count first.  A
        // variant's count is fixed by its piece, so grouping the index by count
        // at construction is all the ordering the search needs.  With the gate
        // off there is one group holding every variant, which lays the index
        // out exactly as it was before.
        this.quotaFloor = quotaFloors(cfg.quotaSchedule, cells);
        this.quota = quotaFloor[cells] > 0;
        this.quotaColour = quota ? checkedQuotaColours(cfg.quotaColours) : new int[0];
        long[][] groups;
        int[] groupCount;
        if (quota) {
            boolean[] counted = new boolean[numColours];
            for (int i = 0; i < quotaColour.length; i++) counted[quotaColour[i]] = true;
            groups = new long[5][words];
            groupCount = new int[] { 4, 3, 2, 1, 0 };
            for (int v = 0; v < numVariants; v++) {
                int p = variantSides[v];
                int c = 0;
                if (counted[Sides.left(p)]) c++;
                if (counted[Sides.top(p)]) c++;
                if (counted[Sides.right(p)]) c++;
                if (counted[Sides.bottom(p)]) c++;
                groups[4 - c][v >>> 6] |= 1L << (v & 63);
            }
        } else {
            groups = new long[1][words];
            groupCount = new int[] { 0 };
            for (int w = 0; w < words; w++) groups[0][w] = -1L;
        }

        // --- candidate tables ------------------------------------------------
        int pairs = numColours * numColours;
        int buckets = numClasses * pairs;
        // The copy's bucket for the empty board.  The fill order puts every
        // cell's north and west neighbours ahead of it, so the first cell has
        // neither, and both of its sides are read through the sentinel variant.
        this.rootBucket = (rootClass < 0) ? -1
                        : rootClass * pairs + sideBScaled[numVariants] + sideR[numVariants];
        this.orderedBuckets = (rootClass < 0) ? buckets : rootClass * pairs;
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
            entries += (b == rootBucket) ? liveVariants(bits, b, words, groups)
                                         : liveWords(bits, b, words, groups);
            keyPerfectEnd[b] = entries;
            entries += liveWords(leftBits, b, words, groups);
            keyLeftBreakEnd[b] = entries;
            entries += liveWords(topBits, b, words, groups);
        }
        keyStart[buckets] = entries;
        this.keyWord = new int[entries];
        this.keyMask = new long[entries];
        this.quotaCount = quota ? new int[entries] : null;
        int at = 0;
        for (int b = 0; b < buckets; b++) {
            at = (b == rootBucket) ? appendEachVariant(bits, b, words, groups, groupCount, at)
                                   : appendRun(bits, b, words, groups, groupCount, at);
            at = appendRun(leftBits, b, words, groups, groupCount, at);
            at = appendRun(topBits, b, words, groups, groupCount, at);
        }

        // The root's natural order, kept for orderRoot to rebuild from.
        if (rootBucket >= 0) {
            int rootLength = keyPerfectEnd[rootBucket] - keyStart[rootBucket];
            this.rootBaseWord = new int[rootLength];
            this.rootBaseMask = new long[rootLength];
            System.arraycopy(keyWord, keyStart[rootBucket], rootBaseWord, 0, rootLength);
            System.arraycopy(keyMask, keyStart[rootBucket], rootBaseMask, 0, rootLength);
        } else {
            this.rootBaseWord = null;
            this.rootBaseMask = null;
        }

        // Reordering the runs is destructive, so the natural order is kept to
        // rebuild from -- but only when something actually reorders them, so
        // the default engine carries no extra table at all.
        this.seeded = cfg.valueOrder == SolverConfig.VALUE_RANDOM
                   || cfg.shuffleStrength > 0;
        if (seeded || cfg.valueOrder == SolverConfig.VALUE_REVERSE) {
            this.baseWord = new int[entries];
            this.baseMask = new long[entries];
            System.arraycopy(keyWord, 0, baseWord, 0, entries);
            System.arraycopy(keyMask, 0, baseMask, 0, entries);
        } else {
            this.baseWord = null;
            this.baseMask = null;
        }
        // Both sides of this merge added a seeded shuffle, and both are kept.
        // They compose -- a permutation of a permutation is still one -- but
        // upstream's ran unconditionally, which would have made the seed-0
        // descent depend on the seed and silently invalidated every unseeded
        // measurement in docs/SOLVER.md.  Gating it on a non-zero seed keeps
        // upstream's behaviour wherever a seed is set (the supervisor now
        // always sets a fresh one) and keeps seed 0 reproducible.
        // Both sides of this merge added a seeded shuffle and both are kept.
        // Upstream's runs whenever a seed is set, which is what gives each
        // PortfolioSearch worker a different descent -- gating it on anything
        // narrower would make every worker identical.  It is skipped only when
        // the quota gate is on, because that gate needs each key's candidates
        // in descending colour-count order and shuffleRuns does not respect
        // the grouping; seeded variation still reaches that mode through
        // valueOrder/shuffleStrength, whose permute is group-aware, and the
        // opening through orderRoot, which is too.
        if (cfg.randomSeed != 0L && quotaCount == null) shuffleRuns(cfg.randomSeed);

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
        if (rootClass >= 0) classBase[0] = rootClass * pairs;

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
        this.quotaCumulative = quota ? new int[cells + 1] : null;
        reset();
    }

    /** How many (group, word) pairs of one bucket hold any candidate at all. */
    private static int liveWords(long[] bits, int bucket, int words, long[][] groups) {
        int count = 0;
        for (int g = 0; g < groups.length; g++) {
            for (int w = 0; w < words; w++) {
                if ((bits[bucket * words + w] & groups[g][w]) != 0L) count++;
            }
        }
        return count;
    }

    /** How many candidate variants one bucket holds, across every group. */
    private static int liveVariants(long[] bits, int bucket, int words, long[][] groups) {
        int count = 0;
        for (int g = 0; g < groups.length; g++) {
            for (int w = 0; w < words; w++) {
                count += Long.bitCount(bits[bucket * words + w] & groups[g][w]);
            }
        }
        return count;
    }

    /**
     * Copy one bucket into the index as one entry per variant rather than one
     * per (group, word) pair, which is what lets the root be reordered at all:
     * a seed moves whole entries and never the bits inside one.
     *
     * The entries come out in exactly the order {@link #appendRun} would have
     * yielded their bits -- group by group, word by word, lowest bit first --
     * and each carries its group's count, so an unseeded search reads the same
     * candidates in the same order and writes the same quota totals.
     */
    private int appendEachVariant(long[] bits, int bucket, int words, long[][] groups,
                                  int[] groupCount, int at) {
        for (int g = 0; g < groups.length; g++) {
            for (int w = 0; w < words; w++) {
                long m = bits[bucket * words + w] & groups[g][w];
                while (m != 0L) {
                    keyWord[at] = w;
                    keyMask[at] = m & -m;
                    if (quotaCount != null) quotaCount[at] = groupCount[g];
                    at++;
                    m &= m - 1L;
                }
            }
        }
        return at;
    }

    /**
     * Copy one bucket's live (word, mask) pairs into the index, group by group.
     * The groups arrive in the order the search will read them, so a bucket's
     * entries come out sorted by quota-colour count, highest first.
     */
    private int appendRun(long[] bits, int bucket, int words, long[][] groups,
                          int[] groupCount, int at) {
        for (int g = 0; g < groups.length; g++) {
            for (int w = 0; w < words; w++) {
                long m = bits[bucket * words + w] & groups[g][w];
                if (m == 0L) continue;
                keyWord[at] = w;
                keyMask[at] = m;
                if (quotaCount != null) quotaCount[at] = groupCount[g];
                at++;
            }
        }
        return at;
    }

    /**
     * Seed-driven diversity: permute the (word, mask) entries within each
     * key's perfect / left-broken / top-broken run, without ever moving an
     * entry across a run boundary.  A key whose candidates span more than one
     * word therefore gets a seed-dependent trial order; a run of 0 or 1
     * entries -- most of them, since Eternity II's whole index holds well
     * under a thousand entries -- is unaffected.  Every entry in a run is
     * still visited exactly once regardless of seed, so this changes only the
     * descent, never the set of boards reachable from a node.
     */
    private void shuffleRuns(long seed) {
        long state = seed ^ 0x9E3779B97F4A7C15L;
        if (state == 0L) state = 1L;
        // The root's private class is orderRoot's, and comes last.
        for (int b = 0; b < orderedBuckets; b++) {
            state = shuffleRange(keyStart[b], keyPerfectEnd[b], state);
            state = shuffleRange(keyPerfectEnd[b], keyLeftBreakEnd[b], state);
            state = shuffleRange(keyLeftBreakEnd[b], keyStart[b + 1], state);
        }
    }

    /** Fisher-Yates over keyWord/keyMask[from, to), returning the RNG's new state. */
    private long shuffleRange(int from, int to, long state) {
        for (int i = to - 1; i > from; i--) {
            state = xorshift64star(state);
            int span = i - from + 1;
            int j = from + (int) Long.remainderUnsigned(state >>> 1, span);
            int tw = keyWord[i]; keyWord[i] = keyWord[j]; keyWord[j] = tw;
            long tm = keyMask[i]; keyMask[i] = keyMask[j]; keyMask[j] = tm;
        }
        return state;
    }

    private static long xorshift64star(long state) {
        state ^= state >>> 12;
        state ^= state << 25;
        state ^= state >>> 27;
        return state * 0x2545F4914F6CDD1DL;
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
    private static int[] breakCeilings(int slipSchedule, int cells,
                                       int tailFromDepth, int tailBreakBonus) {
        int[] out = new int[cells + 1];
        int[] schedule = scheduleFor(slipSchedule);
        for (int i = 0; i < schedule.length; i++) {
            int depth = (int) ((long) schedule[i] * cells / SCHEDULE_CELLS);
            if (depth > cells) continue;
            for (int d = depth; d <= cells; d++) out[d] = i + 1;
        }
        // The tail allowance rides on top of whatever the schedule permits.
        // Added as a constant from one depth onward, so the ceiling still only
        // ever rises -- a board is never asked to give a break back.
        //
        // Scaled like the published depths above, so every board size gets the
        // same shape.  A depth at or past the last cell is dropped rather than
        // written into out[cells]: dfs never reads that entry, but `slipping`
        // does, and a search that can never slip must not build slipped tables.
        int from = (int) ((long) tailFromDepth * cells / SCHEDULE_CELLS);
        if (tailBreakBonus > 0 && from < cells) {
            for (int d = Math.max(0, from); d <= cells; d++) out[d] += tailBreakBonus;
        }
        return out;
    }

    /**
     * Depth -> the fewest quota-colour sides the board must have consumed by
     * then, which is Blackwood's ramp read off {@link #QUOTA_DEPTHS} and
     * {@link #QUOTA_SIDES}.  Both axes are scaled by {@code cells / 256} the
     * way the slip schedules and {@link FillOrder}'s phases are: the real
     * puzzle gets the published curve verbatim and every other board size gets
     * the same shape.  Past the last breakpoint the floor simply holds, which
     * constrains nothing -- the count only ever grows, so a board that met the
     * floor at 160 meets it at every depth after.
     */
    private static int[] quotaFloors(int quotaSchedule, int cells) {
        int[] out = new int[cells + 1];
        if (quotaSchedule != SolverConfig.QUOTA_BLACKWOOD) return out;
        for (int d = 1; d <= cells; d++) {
            int i = (int) ((long) d * SCHEDULE_CELLS / cells);
            out[d] = (int) ((long) quotaRamp(i) * cells / SCHEDULE_CELLS);
        }
        return out;
    }

    /** The ramp at one placement of a 256-cell board, linear between breakpoints. */
    private static int quotaRamp(int placement) {
        if (placement <= QUOTA_DEPTHS[0]) return 0;
        for (int k = 1; k < QUOTA_DEPTHS.length; k++) {
            if (placement <= QUOTA_DEPTHS[k]) {
                int run = QUOTA_DEPTHS[k] - QUOTA_DEPTHS[k - 1];
                int rise = QUOTA_SIDES[k] - QUOTA_SIDES[k - 1];
                return QUOTA_SIDES[k - 1] + (placement - QUOTA_DEPTHS[k - 1]) * rise / run;
            }
        }
        return QUOTA_SIDES[QUOTA_SIDES.length - 1];
    }

    /**
     * The colours the quota tracks, refusing anything this instance could not
     * count.  Grey is rejected outright: it is the border colour, every border
     * piece carries it and no interior side may, so a quota on it would say
     * nothing about what is left for the tail.
     */
    private int[] checkedQuotaColours(String spec) {
        int[] colours = SolverConfig.parseColourList(spec);
        if (colours == null || colours.length == 0) {
            throw new IllegalStateException(
                "quotaColours is not a list of distinct colour numbers: [" + spec + "]");
        }
        for (int i = 0; i < colours.length; i++) {
            if (colours[i] == GREY) {
                throw new IllegalStateException("quotaColours may not include grey ("
                    + GREY + "): it is the border colour, so every border piece carries"
                    + " it and no interior side may -- got [" + spec + "]");
            }
            if (colours[i] >= numColours) {
                throw new IllegalStateException("quotaColours names colour " + colours[i]
                    + ", but this instance only has colours 0.." + (numColours - 1)
                    + " -- got [" + spec + "]");
            }
        }
        return colours;
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
        bestPlaced = 0;
        bestMatchedEdges = 0;
        bestBreaks = 0;
        bestPerfectTiles = 0;
        deepestErrorFree = 0;
        bestScore = 0;
        bestScorePlaced = 0;
        bestScoreBreaks = 0;
        bestScoreBoard = null;
        scoreWatchDepth = 0;
        aborted = false;
        restarts = 0;
        bestBoard = null;
        solutionBoard = null;
        bestOrderCells = null;
        bestOrderVariants = null;
        bestOrderLength = 0;
        restartHit = false;
        restartNodeCap = Long.MAX_VALUE;
        nodeCap = nodeBudget;
        rngState = (cfg.randomSeed == 0) ? 0x9E3779B97F4A7C15L : cfg.randomSeed;
        rootRngState = cfg.randomSeed ^ 0xD1B54A32D192ED03L;
        if (rootRngState == 0L) rootRngState = 1L;
        sampleCountdown = sampleEveryNodes;
        clearBoard();
        orderCandidates();
    }

    /** Empty the board and hand every piece back to the pool. */
    private void clearBoard() {
        placed = 0;
        for (int w = 0; w < words; w++) avail[w] = exist[w];
        for (int d = 0; d <= cells; d++) chosen[d] = numVariants;
    }

    // ------------------------------------------------------- candidate order

    /**
     * Lay the candidate index out in the order the search will read it.
     *
     * Every key's three runs are permuted independently and in place, starting
     * from the natural order each time so that the same seed always produces
     * the same layout however many times this is called.  Reordering a run
     * changes only which candidate is tried first: the run still holds exactly
     * the same (word, mask) pairs, so no seed can add or remove a board.
     */
    private void orderCandidates() {
        if (baseWord != null) {
            System.arraycopy(baseWord, 0, keyWord, 0, keyWord.length);
            System.arraycopy(baseMask, 0, keyMask, 0, keyMask.length);
            // The root's private class is orderRoot's, and comes last.
            for (int b = 0; b < orderedBuckets; b++) {
                orderRun(keyStart[b], keyPerfectEnd[b]);
                orderRun(keyPerfectEnd[b], keyLeftBreakEnd[b]);
                orderRun(keyLeftBreakEnd[b], keyStart[b + 1]);
            }
        }
        orderRoot();
    }

    /**
     * Lay the root's entries out in this attempt's opening order.
     *
     * The root's private class is ordered here and nowhere else: it comes
     * after every real key, and {@link #shuffleRuns} and
     * {@link #orderCandidates} stop short of it, so every real key is laid out
     * exactly as it is without a seed.  A seed therefore changes the opening
     * and nothing else, which is what makes a seeded attempt a test of
     * diversity at the root rather than of the reordering further down.
     *
     * Any non-zero seed reaches it, whether or not {@link #seeded} is set and
     * under the quota gate too; {@code valueOrder=reverse} does not reverse
     * it.  It reorders only within a stretch of equal
     * quota count, like everything else, so a triple that gives the corners
     * different counts leaves a seed only the highest-count ones to choose
     * from.  It draws from a stream of its own, which reset() restores, so a
     * reset opens where it did before; a restart advances the stream and opens
     * somewhere new.
     */
    private void orderRoot() {
        if (rootBaseWord == null) return;
        int from = keyStart[rootBucket];
        int to = keyPerfectEnd[rootBucket];
        System.arraycopy(rootBaseWord, 0, keyWord, from, to - from);
        System.arraycopy(rootBaseMask, 0, keyMask, from, to - from);
        int start = from;
        while (start < to) {
            int end = stretchEnd(start, to);
            rootRngState = shuffleRange(start, end, rootRngState);
            start = end;
        }
    }

    /**
     * Reorder one key's run of (word, mask) pairs, honouring the config.
     *
     * {@code shuffleStrength} is the share of keys disturbed, not the share of
     * each key's run.  {@link MrvSolver} spends it as transpositions of one
     * cell's candidate list, which is long enough for that to be a dial; a key
     * here holds one to six entries, so the same formula rounds to no swaps at
     * all below a strength of 17.  Measurement said the useful dial is how
     * much of the natural order survives, so a strength of s per cent
     * scrambles s per cent of the keys and leaves the rest exactly as they
     * were.
     */
    private void orderRun(int from, int to) {
        if (to - from < 2) return;
        boolean scramble = cfg.valueOrder == SolverConfig.VALUE_RANDOM
                        || (cfg.shuffleStrength > 0 && nextInt(100) < cfg.shuffleStrength);
        int start = from;
        while (start < to) {
            int end = stretchEnd(start, to);
            permute(start, end, scramble);
            start = end;
        }
    }

    /**
     * Where the stretch of entries sharing {@code start}'s quota count ends --
     * the most any reordering may span.
     *
     * Under the quota a run is read highest-count first and abandoned at the
     * first entry that falls short, so moving an entry across counts would
     * throw away candidates that could still have met the floor.  Each stretch
     * of equal count is reordered on its own instead, which is also why the
     * counts themselves never have to move.  With no quota the whole run is one
     * stretch.
     */
    private int stretchEnd(int start, int to) {
        if (quotaCount == null) return to;
        int end = start + 1;
        while (end < to && quotaCount[end] == quotaCount[start]) end++;
        return end;
    }

    /** Reverse and/or shuffle one stretch of entries in place. */
    private void permute(int from, int to, boolean scramble) {
        int len = to - from;
        if (len < 2) return;
        if (cfg.valueOrder == SolverConfig.VALUE_REVERSE) {
            for (int i = 0; i < len / 2; i++) swapEntries(from + i, to - 1 - i);
        }
        if (!scramble) return;
        for (int i = len - 1; i > 0; i--) swapEntries(from + i, from + nextInt(i + 1));
    }

    private void swapEntries(int i, int j) {
        int w = keyWord[i];   keyWord[i] = keyWord[j];   keyWord[j] = w;
        long m = keyMask[i];  keyMask[i] = keyMask[j];   keyMask[j] = m;
    }

    /** xorshift64, the same generator {@link MrvSolver} uses. */
    private long nextRandom() {
        long x = rngState;
        x ^= (x << 13);
        x ^= (x >>> 7);
        x ^= (x << 17);
        rngState = x;
        return x;
    }

    private int nextInt(int bound) {
        if (bound <= 1) return 0;
        long r = nextRandom();
        if (r < 0) r = -r;
        return (int) (r % bound);
    }

    // ------------------------------------------------------------------ search

    /**
     * Run the search.  Returns the number of solutions found.  Check
     * {@link #aborted} to see whether the node budget was exhausted first.
     */
    public long solve() {
        startNanos = System.nanoTime();
        long cap = hardCap();

        // A restart is worth running only when there is something to
        // re-randomise, and only when hunting for one solution: an exhaustive
        // run that started over would report solutions it had already counted.
        boolean useRestarts = cfg.restartPolicy != SolverConfig.RESTART_NONE
                              && stopAtFirstSolution && seeded;

        if (!useRestarts) {
            restartNodeCap = Long.MAX_VALUE;
            nodeCap = nodeBudget;
            dfs(0, 0);
        } else {
            int k = 0;
            while (true) {
                restartNodeCap = nodes + cfg.restartBudget(k);
                if (restartNodeCap > cap) restartNodeCap = cap;
                nodeCap = (nodeBudget < restartNodeCap) ? nodeBudget : restartNodeCap;
                restartHit = false;
                dfs(0, 0);
                if (solutions > 0 && stopAtFirstSolution) break;
                if (aborted || nodes >= cap) break;
                if (!restartHit) break;          // whole space explored
                // Start over somewhere else.  The deepest board is untouched
                // by this: it is only ever replaced by a better one.
                k++;
                restarts = k;
                rngState = rngState * 6364136223846793005L + 1442695040888963407L;
                if (rngState == 0) rngState = 0x9E3779B97F4A7C15L;
                clearBoard();
                orderCandidates();
                if (listener != null) listener.onRestart(this, k);
            }
        }

        if (verbose) {
            long ms = elapsedMs();
            System.out.println("ScanSolver done: nodes=" + nodes
                + " solutions=" + solutions
                + " bestPlaced=" + bestPlaced + "/" + cells
                + " bestEdges=" + bestMatchedEdges
                + " errorFree=" + deepestErrorFree + "/" + cells
                + " bestBreaks=" + bestBreaks
                + " restarts=" + restarts
                + " aborted=" + aborted
                + " ms=" + ms
                + " nodes/s=" + (ms == 0 ? 0 : (nodes * 1000L / ms)));
        }
        return solutions;
    }

    /** The node count the whole attempt stops at, whatever the restart policy. */
    private long hardCap() {
        return (maxNodes < nodeBudget) ? maxNodes : nodeBudget;
    }

    /**
     * Fill the cell at {@code depth}, given that the board so far carries
     * {@code breaks} deliberately mismatched edges.
     *
     * @return true when the caller should stop descending.
     */
    private boolean dfs(int depth, int breaks) {
        // Ahead of the completion return, so a finished board with nothing
        // mismatched reports every one of its cells.  Guarded on depth first:
        // after warm-up that compare fails at nearly every node, so the breaks
        // test is seldom reached at all.
        if (depth > deepestErrorFree && breaks == 0) {
            deepestErrorFree = depth;
            reportErrorFreeReach();
        }
        if (depth == cells) return complete(breaks);

        nodes++;
        placed = depth;
        // nodeCap is the attempt's budget, lowered to the cutoff while a
        // restart policy is running, so the hot path still reads one field.
        long cap = (maxNodes < nodeCap) ? maxNodes : nodeCap;
        if (nodes >= cap) return outOfBudget();

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
            reportBest(breaks);
        }
        // A node can only beat the score record from the depth where the
        // checks alone exceed it, so nearly every node fails the first compare
        // and never pays for the subtract.
        if (depth >= scoreWatchDepth && checksBefore[depth] - breaks > bestScore) {
            recordBestScore(depth, breaks);
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
     * A budget ran out; say which one.  Reaching the attempt's own cap ends
     * the search, reaching a restart cutoff only ends this run.
     *
     * @return true, so that every frame unwinds.
     */
    private boolean outOfBudget() {
        if (nodes >= hardCap()) aborted = true;
        else restartHit = true;
        return true;
    }

    /**
     * Try every still-unused candidate in one run of (word, mask) pairs.
     * {@code breaks} is what the child board carries, so a slipped run is
     * passed one more than the perfect run is.
     *
     * @return true when the caller should stop descending.
     */
    private boolean descend(int depth, int breaks, int from, int to) {
        if (quota) return descendQuota(depth, breaks, from, to);
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
     * The same scan under the colour quota.
     *
     * Entries arrive highest-count first, so the first one that cannot lift the
     * board to this depth's floor means none of the ones behind it can either,
     * and the run is ABANDONED rather than filtered.  That is the whole of the
     * technique: rejecting the candidate alone would only reorder the search,
     * whereas cutting the run off removes the subtree.
     *
     * Every candidate in one entry shares its count, so the running total for
     * the next depth is written once per entry rather than once per placement.
     *
     * @return true when the caller should stop descending.
     */
    private boolean descendQuota(int depth, int breaks, int from, int to) {
        int carried = quotaCumulative[depth];
        int need = quotaFloor[depth + 1] - carried;
        for (int i = from; i < to; i++) {
            int have = quotaCount[i];
            if (have < need) return false;
            quotaCumulative[depth + 1] = carried + have;
            int w = keyWord[i];
            long live = avail[w];
            long bits = keyMask[i] & live;
            while (bits != 0L) {
                int v = (w << 6) + Long.numberOfTrailingZeros(bits);
                bits &= bits - 1L;
                chosen[depth] = v;
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
        if (checksBefore[cells] - breaks > bestScore) recordBestScore(cells, breaks);
        if (breaks > 0) {
            if (cells > bestPlaced
                    || checksBefore[cells] - breaks > bestMatchedEdges) {
                bestPlaced = cells;
                recordBest(cells, breaks);
                if (listener != null) listener.onNewBest(this);
                reportBest(breaks);
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
        bestPerfectTiles = Validator.perfectTiles(inst, bestOrderCells,
                                                  bestOrderVariants, depth);
    }

    /** Snapshot the board as it stands as the best-scoring one seen. */
    private void recordBestScore(int depth, int breaks) {
        if (bestScoreBoard == null) bestScoreBoard = new int[cells];
        for (int cell = 0; cell < cells; cell++) bestScoreBoard[cell] = -1;
        for (int d = 0; d < depth; d++) bestScoreBoard[order[d]] = chosen[d];
        bestScore = checksBefore[depth] - breaks;
        bestScorePlaced = depth;
        bestScoreBreaks = breaks;
        int d = 0;
        while (d < cells && checksBefore[d] <= bestScore) d++;
        scoreWatchDepth = d;
    }

    /**
     * Write the board just recorded to the progress log.
     *
     * Shallow boards are skipped because the first two hundred are noise, and
     * a filled board always clears the depth test -- which is what makes
     * {@link #complete}'s call to this the one that matters.  A board filled
     * with breaks is recorded there rather than in {@link #dfs}, and once it
     * sets {@link #bestPlaced} to the cell count no later board can satisfy
     * dfs's test either, so without this call the log falls silent for the
     * remainder of the attempt.
     */
    private void reportBest(int breaks) {
        if (!verbose) return;
        if (bestPlaced % 16 != 0 && bestPlaced <= cells - 40) return;
        System.out.println("  placed=" + bestPlaced + "/" + cells
            + " edges=" + bestMatchedEdges
            + " errorFree=" + deepestErrorFree
            + " breaks=" + breaks
            + " nodes=" + nodes + " ms=" + elapsedMs());
    }

    /**
     * Write a rise in the error-free reach to the progress log.
     *
     * The reach belongs to the search rather than to any one board, so no
     * board line is guaranteed to follow it -- an attempt that has already
     * recorded a deeper slipped board records nothing more, and the rise
     * would stay invisible until the attempt ended.
     *
     * Every rise is reported, with none of the depth filtering {@link
     * #reportBest} does.  The reach only ever increases, so an attempt can
     * emit at most one line per cell however long it runs, and any threshold
     * cheap enough to silence the opening burst also silences a late rise
     * that fell just under it -- which is the fault being fixed.
     */
    private void reportErrorFreeReach() {
        if (!verbose) return;
        System.out.println("  errorFree=" + deepestErrorFree + "/" + cells
            + " nodes=" + nodes + " ms=" + elapsedMs());
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
    public int bestPerfectTiles() { return bestPerfectTiles; }
    public int deepestErrorFree() { return deepestErrorFree; }
    /** How many times the search started over; 0 unless the order is seeded. */
    public int restarts() { return restarts; }
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

    /** Whether the configured schedule gates this board on colour at all. */
    public boolean quotaGated() { return quota; }

    /** Depth -> the fewest quota-colour sides the board must have consumed. */
    public int[] quotaFloors() {
        int[] out = new int[quotaFloor.length];
        System.arraycopy(quotaFloor, 0, out, 0, quotaFloor.length);
        return out;
    }

    /** The colours the quota tracks; empty when it is off. */
    public int[] quotaColours() {
        int[] out = new int[quotaColour.length];
        System.arraycopy(quotaColour, 0, out, 0, quotaColour.length);
        return out;
    }

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

    /**
     * The perfectly-matching candidates this cell class and colour pair
     * offers, as variant numbers in the order the search will try them.
     *
     * This is the one thing the seed changes, so it is worth being able to
     * read: two runs of the same seed must return the same order and two seeds
     * must not, whatever board either of them goes on to find.
     */
    public int[] candidateOrderAtDepth(int depth, int left, int top) {
        int key = classBase[depth] + top * numColours + left;
        int[] out = new int[candidateCountAtDepth(depth, left, top)];
        int at = 0;
        for (int i = keyStart[key]; i < keyPerfectEnd[key]; i++) {
            long bits = keyMask[i];
            while (bits != 0L) {
                out[at++] = (keyWord[i] << 6) + Long.numberOfTrailingZeros(bits);
                bits &= bits - 1L;
            }
        }
        int[] trimmed = new int[at];
        System.arraycopy(out, 0, trimmed, 0, at);
        return trimmed;
    }

    /**
     * The key the search reads at depth 0, formed exactly as {@link #dfs}
     * forms it.
     *
     * Both of the first square's neighbours are read through {@code chosen},
     * and the sentinel slot an off-board neighbour points at is only ever
     * read, never written, so this answers the same on a fresh solver as on
     * one that has already run.  On a seeded engine it is the private root
     * bucket.
     */
    private int rootKey() {
        int topScaled = sideBScaled[chosen[northSlot[0]]];
        int left = sideR[chosen[westSlot[0]]];
        return classBase[0] + topScaled + left;
    }

    /**
     * The variants the first square offers, in the order the search will try
     * them -- the opening move first.
     */
    public int[] rootCandidates() {
        int key = rootKey();
        int count = 0;
        for (int i = keyStart[key]; i < keyStart[key + 1]; i++) {
            count += Long.bitCount(keyMask[i]);
        }
        int[] out = new int[count];
        int at = 0;
        for (int i = keyStart[key]; i < keyStart[key + 1]; i++) {
            long bits = keyMask[i];
            while (bits != 0L) {
                out[at++] = (keyWord[i] << 6) + Long.numberOfTrailingZeros(bits);
                bits &= bits - 1L;
            }
        }
        return out;
    }

    /**
     * Whether the candidate order below the root depends on
     * {@code randomSeed} -- which takes {@code valueOrder} or
     * {@code shuffleStrength}.  The opening follows any non-zero seed on its
     * own; see {@link #orderRoot}.
     */
    public boolean seeded() { return seeded; }

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
                + s.deepestErrorFree + "/" + s.cells + " error-free, "
                + s.bestBreaks + " broken)");
            if (s.bestBoard != null) {
                String err = Validator.validatePartial(s.inst, s.bestBoard, false,
                                                       s.bestBreaks);
                System.out.println("partial board validation: " + (err == null ? "OK" : err));
            }
        }
    }
}
