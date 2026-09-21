package core;

/**
 * Edge-matching solver using MRV (most-constrained-variable) cell ordering on
 * top of a bitset representation of the piece pool.
 *
 * Every strategic decision is driven by a {@link SolverConfig}; the defaults
 * reproduce the original hard-coded behaviour exactly.
 *
 * ---------------------------------------------------------------- bitsets
 *
 * A "variant" is a (piece, rotation) pair, numbered v = pieceId * 4 + rot.
 * Because 4 divides 64, all four variants of a piece live in a single 64-bit
 * word, at word index (id >>> 4) and bit offset ((id & 15) << 2).  Marking a
 * piece used or unused is therefore a single AND / OR.  That property is also
 * what makes the incremental candidate counting below possible.
 *
 * Colour constraints are bitsets: maskL/T/R/B[colour] hold the variants whose
 * left/top/right/bottom side is that colour, and notGreyL/T/R/B hold those
 * whose side is not grey (used for interior edges).
 *
 * Each empty cell caches a constraint mask -- the intersection of the four side
 * masks implied by its borders and placed neighbours -- so its candidates are
 * cellMask[cell] & avail.
 *
 * --------------------------------------------------- incremental MRV counts
 *
 * cellCount[cell] = popcount(cellMask[cell] & avail) is kept up to date:
 *   - when a neighbour changes, that cell's mask and count are rebuilt (at most
 *     four cells per move);
 *   - when piece `id` is used, avail loses exactly existPieceBits[id], all in
 *     one word, so every other empty cell's count drops by one popcount of one
 *     word -- rather than `words` of them.
 *
 * Masks are stored word-major (cellMask[w * cells + cell]) so the per-placement
 * sweep over cells for a single word is contiguous.
 */
public final class MrvSolver implements Search {

    public static final int GREY = Sides.GREY;

    // ------------------------------------------------------------------ inputs

    public final Instance inst;
    public final SolverConfig cfg;
    private final int n, cells, numPieces, numVariants, words, numColours;

    /** Stop after the first solution (default) or enumerate all of them. */
    public boolean stopAtFirstSolution = true;
    /** Abort once this many nodes have been expanded. */
    public long maxNodes = Long.MAX_VALUE;
    /** MRV cell ordering (true) or lowest-index cell ordering (false). */
    public boolean useMrv = true;
    /** Print progress to stdout. */
    public boolean verbose = false;

    /** Optional observer; see {@link SolveListener}. */
    public SolveListener listener;
    /** How often {@link SolveListener#onSample} fires. */
    public long sampleEveryNodes = 250000L;

    // ------------------------------------------------------------- static tables

    private final int[] variantSides;
    private final long[] exist;
    private final long[][] maskL, maskT, maskR, maskB;
    private final long[] notGreyL, notGreyT, notGreyR, notGreyB;
    /** Side masks used for an edge facing an empty on-board neighbour.  Points
     *  at notGrey* normally, or at `exist` when grey-interior pruning is off. */
    private final long[] freeL, freeT, freeR, freeB;
    private final long[] existPieceBits;
    private final int[] pieceWordOf;
    /** Sum of global colour frequencies of a variant's four sides. */
    private final int[] variantRarity;

    // ------------------------------------------------------------- search state

    private final long[] avail;
    private final long[] cellMask;
    private final int[] cellCount;
    private final int[] boardVariant;
    private final boolean[] usedPiece;
    private final long[] scratch;
    private final long[] saveMask;
    private final int[] saveCount;
    /** Cells in the order they were placed; index 0..placed-1. */
    private final int[] stackCell;
    /** Optional per-depth candidate buffer, used for non-natural value orders. */
    private int[] candBuf;
    private int candBufStride;

    private int lastPlacedCell = -1;
    private int initialPlaced = 0;
    private long rngState;
    private long sampleCountdown;
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
    public int[] solutionBoard;
    /** Cells of the deepest board, in the order they were placed. */
    public int[] bestOrderCells;
    /** Variants of the deepest board, parallel to {@link #bestOrderCells}. */
    public int[] bestOrderVariants;
    public int bestOrderLength;

    private long startNanos;

    // ------------------------------------------------------------------ build

    public MrvSolver(Instance inst) {
        this(inst, new SolverConfig());
    }

    public MrvSolver(Instance inst, SolverConfig config) {
        this.inst = inst;
        this.cfg = (config == null) ? new SolverConfig() : config;
        this.n = inst.n;
        this.cells = inst.cells;
        this.numPieces = inst.numPieces;
        this.numVariants = inst.numVariants;
        this.words = inst.words;
        this.numColours = inst.numColours;

        if (cfg.cellOrder == SolverConfig.CELL_ROW_MAJOR) useMrv = false;

        this.variantSides = new int[numVariants];
        this.variantRarity = new int[numVariants];
        this.exist = new long[words];
        this.maskL = new long[numColours][words];
        this.maskT = new long[numColours][words];
        this.maskR = new long[numColours][words];
        this.maskB = new long[numColours][words];
        this.notGreyL = new long[words];
        this.notGreyT = new long[words];
        this.notGreyR = new long[words];
        this.notGreyB = new long[words];
        this.existPieceBits = new long[numPieces];
        this.pieceWordOf = new int[numPieces];

        // global colour frequency, for the "rarest colour first" value order
        int[] colourFreq = new int[numColours];
        for (int id = 0; id < numPieces; id++) {
            int p = inst.packedSides[id];
            colourFreq[Sides.left(p)]++;
            colourFreq[Sides.top(p)]++;
            colourFreq[Sides.right(p)]++;
            colourFreq[Sides.bottom(p)]++;
        }

        for (int id = 0; id < numPieces; id++) {
            for (int r = 0; r < 4; r++) {
                if (!inst.isCanonicalRotation(id, r)) continue;
                int v = (id << 2) | r;
                int p = inst.variantSides(id, r);
                variantSides[v] = p;
                variantRarity[v] = colourFreq[Sides.left(p)] + colourFreq[Sides.top(p)]
                                 + colourFreq[Sides.right(p)] + colourFreq[Sides.bottom(p)];
                setBit(exist, v);
                setBit(maskL[Sides.left(p)], v);
                setBit(maskT[Sides.top(p)], v);
                setBit(maskR[Sides.right(p)], v);
                setBit(maskB[Sides.bottom(p)], v);
            }
        }
        for (int w = 0; w < words; w++) {
            notGreyL[w] = exist[w] & ~maskL[GREY][w];
            notGreyT[w] = exist[w] & ~maskT[GREY][w];
            notGreyR[w] = exist[w] & ~maskR[GREY][w];
            notGreyB[w] = exist[w] & ~maskB[GREY][w];
        }
        if (cfg.greyInteriorPruning) {
            freeL = notGreyL; freeT = notGreyT; freeR = notGreyR; freeB = notGreyB;
        } else {
            freeL = exist; freeT = exist; freeR = exist; freeB = exist;
        }
        for (int id = 0; id < numPieces; id++) {
            pieceWordOf[id] = id >>> 4;
            existPieceBits[id] = exist[pieceWordOf[id]] & pieceBits(id);
        }

        this.avail = new long[words];
        this.cellMask = new long[words * cells];
        this.cellCount = new int[cells];
        this.boardVariant = new int[cells];
        this.usedPiece = new boolean[numPieces];
        this.scratch = new long[(cells + 1) * words];
        this.saveMask = new long[(cells + 1) * 4 * words];
        this.saveCount = new int[(cells + 1) * 5];
        this.stackCell = new int[cells + 1];

        // The candidate buffer is only needed when the natural bit order is not
        // used, so the common case allocates nothing extra.
        if (needsCandidateBuffer()) {
            candBufStride = numVariants;
            candBuf = new int[(cells + 1) * candBufStride];
        }

        reset();
    }

    private boolean needsCandidateBuffer() {
        return cfg.valueOrder != SolverConfig.VALUE_NATURAL
            || cfg.shuffleStrength > 0
            || cfg.candidateCap < numVariants;
    }

    /** Restore the solver to its initial state (fixed pieces placed). */
    public void reset() {
        nodes = 0;
        solutions = 0;
        bestPlaced = 0;
        bestMatchedEdges = 0;
        aborted = false;
        restarts = 0;
        bestBoard = null;
        solutionBoard = null;
        bestOrderCells = null;
        bestOrderVariants = null;
        bestOrderLength = 0;
        restartHit = false;
        restartNodeCap = Long.MAX_VALUE;
        rngState = cfg.randomSeed == 0 ? 0x9E3779B97F4A7C15L : cfg.randomSeed;
        sampleCountdown = sampleEveryNodes;
        clearBoard();
        bestPlaced = placed;
        initialPlaced = placed;
    }

    /** Empty the board and re-apply the instance's fixed placements. */
    private void clearBoard() {
        for (int w = 0; w < words; w++) avail[w] = exist[w];
        for (int c = 0; c < cells; c++) boardVariant[c] = -1;
        for (int i = 0; i < numPieces; i++) usedPiece[i] = false;
        placed = 0;
        lastPlacedCell = -1;
        for (int c = 0; c < cells; c++) recomputeCellMaskAndCount(c);

        for (int i = 0; i < inst.fixedCell.length; i++) {
            int cell = inst.fixedCell[i];
            int v = (inst.fixedPiece[i] << 2) | inst.fixedRot[i];
            if (!testBit(exist, v)) {
                v = canonicalVariantWithSameSides(inst.fixedPiece[i], inst.fixedRot[i]);
            }
            if (boardVariant[cell] >= 0) {
                throw new IllegalStateException("two fixed pieces on cell " + cell);
            }
            if (!isLegalHere(cell, v)) {
                throw new IllegalStateException(
                    "fixed placement is illegal: cell " + cell + " variant " + v);
            }
            place(cell, v);
        }
    }

    private int canonicalVariantWithSameSides(int id, int rot) {
        int want = inst.variantSides(id, rot);
        for (int r = 0; r < 4; r++) {
            int v = (id << 2) | r;
            if (testBit(exist, v) && variantSides[v] == want) return v;
        }
        throw new IllegalStateException("no canonical variant for piece " + id + " rot " + rot);
    }

    // ------------------------------------------------------------ bitset helpers

    private static void setBit(long[] bs, int i) { bs[i >>> 6] |= (1L << (i & 63)); }
    private static boolean testBit(long[] bs, int i) {
        return (bs[i >>> 6] & (1L << (i & 63))) != 0L;
    }
    private static long pieceBits(int id) { return 0xFL << ((id & 15) << 2); }

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

    // --------------------------------------------------------------- cell masks

    private void recomputeCellMaskAndCount(int cell) {
        int r = cell / n, c = cell - r * n;

        long[] mL;
        if (c == 0) mL = maskL[GREY];
        else {
            int nb = cell - 1;
            mL = (boardVariant[nb] >= 0) ? maskL[Sides.right(variantSides[boardVariant[nb]])]
                                         : freeL;
        }
        long[] mT;
        if (r == 0) mT = maskT[GREY];
        else {
            int nb = cell - n;
            mT = (boardVariant[nb] >= 0) ? maskT[Sides.bottom(variantSides[boardVariant[nb]])]
                                         : freeT;
        }
        long[] mR;
        if (c == n - 1) mR = maskR[GREY];
        else {
            int nb = cell + 1;
            mR = (boardVariant[nb] >= 0) ? maskR[Sides.left(variantSides[boardVariant[nb]])]
                                         : freeR;
        }
        long[] mB;
        if (r == n - 1) mB = maskB[GREY];
        else {
            int nb = cell + n;
            mB = (boardVariant[nb] >= 0) ? maskB[Sides.top(variantSides[boardVariant[nb]])]
                                         : freeB;
        }

        int cnt = 0;
        for (int w = 0; w < words; w++) {
            long m = exist[w] & mL[w] & mT[w] & mR[w] & mB[w];
            cellMask[w * cells + cell] = m;
            cnt += Long.bitCount(m & avail[w]);
        }
        cellCount[cell] = cnt;
    }

    /** Authoritative (non-incremental) candidate count for a cell. */
    public int candidateCount(int cell) {
        int cnt = 0;
        for (int w = 0; w < words; w++) {
            cnt += Long.bitCount(cellMask[w * cells + cell] & avail[w]);
        }
        return cnt;
    }

    /** The incrementally-maintained candidate count for an empty cell. */
    public int cachedCandidateCount(int cell) { return cellCount[cell]; }

    public boolean isLegalHere(int cell, int v) {
        if (boardVariant[cell] >= 0) return false;
        if (!testBit(exist, v)) return false;
        if (usedPiece[v >>> 2]) return false;
        return (cellMask[(v >>> 6) * cells + cell] & (1L << (v & 63))) != 0L;
    }

    private int placedNeighbours(int cell) {
        int r = cell / n, c = cell - r * n;
        int k = 0;
        if (c > 0     && boardVariant[cell - 1] >= 0) k++;
        if (c < n - 1 && boardVariant[cell + 1] >= 0) k++;
        if (r > 0     && boardVariant[cell - n] >= 0) k++;
        if (r < n - 1 && boardVariant[cell + n] >= 0) k++;
        return k;
    }

    // ------------------------------------------------------------ place / unplace

    /**
     * Place variant v at cell, updating availability, neighbour masks and all
     * candidate counts.
     *
     * A neighbour's constraint on the facing side changes from "must not be
     * grey" to "must be exactly this colour", and maskX[c] is a subset of
     * notGreyX for non-grey c, so tightening is a plain AND.  Intersection is
     * not invertible, so the old masks are saved for {@link #unplace}.
     */
    public void place(int cell, int v) {
        int id = v >>> 2;
        int depth = placed;
        int r = cell / n, c = cell - r * n;

        int nb0 = (c > 0)     ? cell - 1 : -1;
        int nb1 = (c < n - 1) ? cell + 1 : -1;
        int nb2 = (r > 0)     ? cell - n : -1;
        int nb3 = (r < n - 1) ? cell + n : -1;

        int smBase = depth * 4 * words;
        int scBase = depth * 5;
        saveCount[scBase + 4] = cellCount[cell];
        saveNeighbour(0, nb0, smBase, scBase);
        saveNeighbour(1, nb1, smBase, scBase);
        saveNeighbour(2, nb2, smBase, scBase);
        saveNeighbour(3, nb3, smBase, scBase);

        boardVariant[cell] = v;
        usedPiece[id] = true;
        int pw = pieceWordOf[id];
        long gone = existPieceBits[id];
        avail[pw] &= ~gone;
        stackCell[placed] = cell;
        placed++;
        lastPlacedCell = cell;

        int base = pw * cells;
        for (int c2 = 0; c2 < cells; c2++) {
            if (boardVariant[c2] >= 0) continue;
            long hit = cellMask[base + c2] & gone;
            if (hit != 0L) cellCount[c2] -= Long.bitCount(hit);
        }

        int p = variantSides[v];
        tighten(nb0, maskR, Sides.left(p));     // its RIGHT faces our LEFT
        tighten(nb1, maskL, Sides.right(p));    // its LEFT  faces our RIGHT
        tighten(nb2, maskB, Sides.top(p));      // its BOTTOM faces our TOP
        tighten(nb3, maskT, Sides.bottom(p));   // its TOP   faces our BOTTOM
    }

    /** Exact inverse of {@link #place}. */
    public void unplace(int cell) {
        int v = boardVariant[cell];
        if (v < 0) throw new IllegalStateException("cell " + cell + " is already empty");
        int id = v >>> 2;
        placed--;
        int depth = placed;
        int r = cell / n, c = cell - r * n;

        int nb0 = (c > 0)     ? cell - 1 : -1;
        int nb1 = (c < n - 1) ? cell + 1 : -1;
        int nb2 = (r > 0)     ? cell - n : -1;
        int nb3 = (r < n - 1) ? cell + n : -1;

        boardVariant[cell] = -1;
        usedPiece[id] = false;
        int pw = pieceWordOf[id];
        long back = existPieceBits[id];
        avail[pw] |= back;

        int smBase = depth * 4 * words;
        int scBase = depth * 5;
        restoreNeighbour(0, nb0, smBase, scBase);
        restoreNeighbour(1, nb1, smBase, scBase);
        restoreNeighbour(2, nb2, smBase, scBase);
        restoreNeighbour(3, nb3, smBase, scBase);

        int base = pw * cells;
        for (int c2 = 0; c2 < cells; c2++) {
            if (boardVariant[c2] >= 0) continue;
            if (c2 == cell || c2 == nb0 || c2 == nb1 || c2 == nb2 || c2 == nb3) continue;
            long hit = cellMask[base + c2] & back;
            if (hit != 0L) cellCount[c2] += Long.bitCount(hit);
        }
        cellCount[cell] = saveCount[scBase + 4];
        lastPlacedCell = (placed > 0) ? stackCell[placed - 1] : -1;
    }

    private void tighten(int nb, long[][] table, int colour) {
        if (nb < 0 || boardVariant[nb] >= 0) return;
        if (colour == GREY) {
            // The subset argument only holds for non-grey colours; this cannot
            // happen when grey-interior pruning is on, but stay correct anyway.
            recomputeCellMaskAndCount(nb);
            return;
        }
        long[] m = table[colour];
        int cnt = 0;
        for (int w = 0; w < words; w++) {
            int idx = w * cells + nb;
            long x = cellMask[idx] & m[w];
            cellMask[idx] = x;
            cnt += Long.bitCount(x & avail[w]);
        }
        cellCount[nb] = cnt;
    }

    private void saveNeighbour(int slot, int nb, int smBase, int scBase) {
        if (nb < 0 || boardVariant[nb] >= 0) return;
        int off = smBase + slot * words;
        for (int w = 0; w < words; w++) saveMask[off + w] = cellMask[w * cells + nb];
        saveCount[scBase + slot] = cellCount[nb];
    }

    private void restoreNeighbour(int slot, int nb, int smBase, int scBase) {
        if (nb < 0 || boardVariant[nb] >= 0) return;
        int off = smBase + slot * words;
        for (int w = 0; w < words; w++) cellMask[w * cells + nb] = saveMask[off + w];
        cellCount[nb] = saveCount[scBase + slot];
    }

    // ------------------------------------------------------------------ search

    /**
     * Run the search.  Returns the number of solutions found.  Check
     * {@link #aborted} to see whether the node budget was exhausted first.
     */
    public long solve() {
        startNanos = System.nanoTime();
        long cap = effectiveNodeCap();

        // Restarts are only meaningful when hunting for one solution: after a
        // restart the solver has no record of which solutions it already saw, so
        // an exhaustive enumeration would double-count.  Exhaustive mode
        // therefore runs a single complete search regardless of the policy.
        boolean useRestarts = (cfg.restartPolicy != SolverConfig.RESTART_NONE)
                              && stopAtFirstSolution;

        if (!useRestarts) {
            restartNodeCap = Long.MAX_VALUE;
            dfs();
        } else {
            int k = 0;
            while (true) {
                long budget = restartBudget(k);
                restartNodeCap = nodes + budget;
                if (restartNodeCap > cap) restartNodeCap = cap;
                restartHit = false;
                dfs();
                if (solutions > 0 && stopAtFirstSolution) break;
                if (aborted || nodes >= cap) break;
                if (!restartHit) break;          // whole space explored
                // start over with a different random stream
                k++;
                restarts = k;
                rngState = rngState * 6364136223846793005L + 1442695040888963407L;
                if (rngState == 0) rngState = 0x9E3779B97F4A7C15L;
                clearBoard();
                if (listener != null) listener.onRestart(this, k);
            }
        }

        if (verbose) {
            long ms = (System.nanoTime() - startNanos) / 1000000L;
            System.out.println("MrvSolver done: nodes=" + nodes
                + " solutions=" + solutions
                + " bestPlaced=" + bestPlaced + "/" + cells
                + " restarts=" + restarts
                + " aborted=" + aborted
                + " ms=" + ms
                + " nodes/s=" + (ms == 0 ? 0 : (nodes * 1000L / ms)));
        }
        return solutions;
    }

    private long effectiveNodeCap() {
        long cap = maxNodes;
        if (cfg.nodeBudget < cap) cap = cfg.nodeBudget;
        return cap;
    }

    private long restartBudget(int k) {
        if (cfg.restartPolicy == SolverConfig.RESTART_FIXED) return cfg.restartBase;
        if (cfg.restartPolicy == SolverConfig.RESTART_GEOMETRIC) {
            double f = Math.pow(cfg.restartMultiplier / 100.0, k);
            double v = cfg.restartBase * f;
            if (v > 1e15) return (long) 1e15;
            return (long) v;
        }
        if (cfg.restartPolicy == SolverConfig.RESTART_LUBY) {
            return cfg.restartBase * luby(k + 1);
        }
        return Long.MAX_VALUE;
    }

    /** Classic Luby sequence: 1,1,2,1,1,2,4,1,... */
    private static long luby(int i) {
        int k = 1;
        while (true) {
            int pow = (1 << k) - 1;
            if (pow == i) return 1L << (k - 1);
            if (pow > i) break;
            k++;
        }
        k = 1;
        while (((1 << k) - 1) < i) k++;
        return luby(i - (1 << (k - 1)) + 1);
    }

    /** @return true when the caller should stop descending. */
    private boolean dfs() {
        if (placed == cells) {
            solutions++;
            if (solutionBoard == null) solutionBoard = new int[cells];
            System.arraycopy(boardVariant, 0, solutionBoard, 0, cells);
            recordBest();
            if (listener != null) listener.onSolution(this);
            if (verbose) System.out.println("solution #" + solutions + " at node " + nodes);
            return stopAtFirstSolution;
        }

        nodes++;
        long cap = effectiveNodeCap();
        if (nodes >= cap) { aborted = true; return true; }
        if (nodes >= restartNodeCap) { restartHit = true; return true; }

        if (listener != null) {
            if (--sampleCountdown <= 0) {
                sampleCountdown = sampleEveryNodes;
                listener.onSample(this);
            }
        }

        if (placed > bestPlaced) {
            bestPlaced = placed;
            recordBest();
            if (listener != null) listener.onNewBest(this);
            if (verbose && (bestPlaced % 16 == 0 || bestPlaced > cells - 40)) {
                long ms = (System.nanoTime() - startNanos) / 1000000L;
                System.out.println("  placed=" + bestPlaced + "/" + cells
                    + " nodes=" + nodes + " ms=" + ms);
            }
        }

        int cell = selectCell();
        if (cell < 0) return false;

        // --- natural order fast path: iterate the bits directly --------------
        if (candBuf == null) {
            int sbase = placed * words;
            for (int w = 0; w < words; w++) {
                scratch[sbase + w] = cellMask[w * cells + cell] & avail[w];
            }
            for (int w = 0; w < words; w++) {
                long bits = scratch[sbase + w];
                while (bits != 0L) {
                    int v = (w << 6) + Long.numberOfTrailingZeros(bits);
                    bits &= bits - 1;
                    place(cell, v);
                    boolean stop = dfs();
                    unplace(cell);
                    if (stop) return true;
                }
            }
            return false;
        }

        // --- general path: materialise, order, optionally cap ----------------
        int bbase = placed * candBufStride;
        int count = 0;
        for (int w = 0; w < words; w++) {
            long bits = cellMask[w * cells + cell] & avail[w];
            while (bits != 0L) {
                candBuf[bbase + count] = (w << 6) + Long.numberOfTrailingZeros(bits);
                count++;
                bits &= bits - 1;
            }
        }
        if (count == 0) return false;
        orderCandidates(bbase, count);
        int limit = (cfg.candidateCap < count) ? cfg.candidateCap : count;
        for (int i = 0; i < limit; i++) {
            int v = candBuf[bbase + i];
            place(cell, v);
            boolean stop = dfs();
            unplace(cell);
            if (stop) return true;
        }
        return false;
    }

    /** Snapshot the current board, its score, and the order it was placed in. */
    private void recordBest() {
        if (bestBoard == null) bestBoard = new int[cells];
        System.arraycopy(boardVariant, 0, bestBoard, 0, cells);
        // A board is recorded at most once per depth, so counting its edges
        // here keeps the score out of the search loop entirely.
        bestMatchedEdges = Validator.matchedEdges(inst, bestBoard);
        if (bestOrderCells == null) {
            bestOrderCells = new int[cells];
            bestOrderVariants = new int[cells];
        }
        for (int i = 0; i < placed; i++) {
            int c = stackCell[i];
            bestOrderCells[i] = c;
            bestOrderVariants[i] = boardVariant[c];
        }
        bestOrderLength = placed;
    }

    // ------------------------------------------------------------ value ordering

    private void orderCandidates(int bbase, int count) {
        int vo = cfg.valueOrder;
        if (vo == SolverConfig.VALUE_REVERSE) {
            for (int i = 0, j = count - 1; i < j; i++, j--) {
                int t = candBuf[bbase + i];
                candBuf[bbase + i] = candBuf[bbase + j];
                candBuf[bbase + j] = t;
            }
        } else if (vo == SolverConfig.VALUE_RANDOM) {
            for (int i = count - 1; i > 0; i--) {
                int j = nextInt(i + 1);
                int t = candBuf[bbase + i];
                candBuf[bbase + i] = candBuf[bbase + j];
                candBuf[bbase + j] = t;
            }
        } else if (vo == SolverConfig.VALUE_RAREST_COLOUR) {
            // insertion sort ascending by rarity score (rarest colours first)
            for (int i = 1; i < count; i++) {
                int v = candBuf[bbase + i];
                int key = variantRarity[v];
                int j = i - 1;
                while (j >= 0 && variantRarity[candBuf[bbase + j]] > key) {
                    candBuf[bbase + j + 1] = candBuf[bbase + j];
                    j--;
                }
                candBuf[bbase + j + 1] = v;
            }
        }
        // partial shuffle on top of whatever order was chosen
        if (cfg.shuffleStrength > 0 && count > 1) {
            int swaps = (count * cfg.shuffleStrength) / 100;
            for (int s = 0; s < swaps; s++) {
                int i = nextInt(count);
                int j = nextInt(count);
                int t = candBuf[bbase + i];
                candBuf[bbase + i] = candBuf[bbase + j];
                candBuf[bbase + j] = t;
            }
        }
    }

    // ------------------------------------------------------------ cell selection

    /**
     * Choose the next cell to fill, or -1 if the board is already dead.
     *
     * Honours cfg.cellOrder, cfg.tieBreak, cfg.forwardCheck and cfg.startCell.
     */
    int selectCell() {
        // forced opening move
        if (placed == initialPlaced && cfg.startCell != SolverConfig.START_AUTO) {
            int forced = configuredStartCell();
            if (forced >= 0 && boardVariant[forced] < 0) {
                if (cfg.forwardCheck == SolverConfig.FC_FULL_BOARD && anyEmptyCellIsDead()) return -1;
                return forced;
            }
        }

        boolean mrv = useMrv && cfg.cellOrder != SolverConfig.CELL_ROW_MAJOR;

        if (!mrv) {
            int first = -1;
            for (int cell = 0; cell < cells; cell++) {
                if (boardVariant[cell] < 0) { first = cell; break; }
            }
            if (first < 0) return -1;
            if (cfg.forwardCheck == SolverConfig.FC_FULL_BOARD) {
                if (anyEmptyCellIsDead()) return -1;
            } else if (cfg.forwardCheck == SolverConfig.FC_NEIGHBOURS) {
                if (cellCount[first] == 0) return -1;
                if (neighbourOfLastIsDead()) return -1;
            }
            return first;
        }

        int bestCell = -1;
        int bestCnt = Integer.MAX_VALUE;
        int bestTie = Integer.MIN_VALUE;
        for (int cell = 0; cell < cells; cell++) {
            if (boardVariant[cell] >= 0) continue;
            int cnt = cellCount[cell];
            if (cnt == 0 && cfg.forwardCheck != SolverConfig.FC_NONE) return -1;
            if (cnt < bestCnt) {
                bestCnt = cnt;
                bestCell = cell;
                bestTie = tieScore(cell);
                if (bestCnt <= 1 && cfg.forwardCheck == SolverConfig.FC_NONE) break;
                if (bestCnt == 1) break;
            } else if (cnt == bestCnt) {
                int t = tieScore(cell);
                if (t > bestTie) { bestCell = cell; bestTie = t; }
            }
        }

        // hybrid: if even the best cell is loosely constrained, prefer locality
        if (bestCell >= 0 && cfg.cellOrder == SolverConfig.CELL_HYBRID
                && bestCnt > cfg.hybridThreshold) {
            for (int cell = 0; cell < cells; cell++) {
                if (boardVariant[cell] < 0) return cell;
            }
        }
        return bestCell;
    }

    /** Higher is preferred when candidate counts tie. */
    private int tieScore(int cell) {
        int tb = cfg.tieBreak;
        if (tb == SolverConfig.TIE_MOST_NEIGHBOURS) return placedNeighbours(cell);
        if (tb == SolverConfig.TIE_FEWEST_NEIGHBOURS) return -placedNeighbours(cell);
        if (tb == SolverConfig.TIE_LOWEST_INDEX) return -cell;
        if (tb == SolverConfig.TIE_NEAREST_FIXED) {
            if (inst.fixedCell.length == 0) return placedNeighbours(cell);
            int f = inst.fixedCell[0];
            int fr = f / n, fc = f - fr * n;
            int r = cell / n, c = cell - r * n;
            return -(Math.abs(fr - r) + Math.abs(fc - c));
        }
        if (tb == SolverConfig.TIE_NEAREST_CENTRE) {
            int r = cell / n, c = cell - r * n;
            int mid = n / 2;
            return -(Math.abs(mid - r) + Math.abs(mid - c));
        }
        return placedNeighbours(cell);
    }

    private boolean anyEmptyCellIsDead() {
        for (int cell = 0; cell < cells; cell++) {
            if (boardVariant[cell] >= 0) continue;
            if (cellCount[cell] == 0) return true;
        }
        return false;
    }

    private boolean neighbourOfLastIsDead() {
        int cell = lastPlacedCell;
        if (cell < 0) return false;
        int r = cell / n, c = cell - r * n;
        if (c > 0     && boardVariant[cell - 1] < 0 && cellCount[cell - 1] == 0) return true;
        if (c < n - 1 && boardVariant[cell + 1] < 0 && cellCount[cell + 1] == 0) return true;
        if (r > 0     && boardVariant[cell - n] < 0 && cellCount[cell - n] == 0) return true;
        if (r < n - 1 && boardVariant[cell + n] < 0 && cellCount[cell + n] == 0) return true;
        return false;
    }

    private int configuredStartCell() {
        int sc = cfg.startCell;
        if (sc == SolverConfig.START_TOP_LEFT) return 0;
        if (sc == SolverConfig.START_TOP_RIGHT) return n - 1;
        if (sc == SolverConfig.START_BOTTOM_LEFT) return (n - 1) * n;
        if (sc == SolverConfig.START_BOTTOM_RIGHT) return cells - 1;
        if (sc == SolverConfig.START_CENTRE) return (n / 2) * n + (n / 2);
        return -1;
    }

    // ------------------------------------------------------------------ accessors

    // --- Search -------------------------------------------------------------

    public void setListener(SolveListener l) { this.listener = l; }
    public void setSampleEveryNodes(long n) { this.sampleEveryNodes = n; }
    public void setStopAtFirstSolution(boolean stop) { this.stopAtFirstSolution = stop; }
    /** Bring the search to a halt at its next node; see {@link Search#requestStop}. */
    public void requestStop() { this.maxNodes = 1; }
    public long nodes() { return nodes; }
    public int bestPlaced() { return bestPlaced; }
    public int bestMatchedEdges() { return bestMatchedEdges; }
    public int restarts() { return restarts; }
    public boolean aborted() { return aborted; }
    public int[] bestBoard() { return bestBoard; }
    public int[] solutionBoard() { return solutionBoard; }
    public int[] bestOrderCells() { return bestOrderCells; }
    public int[] bestOrderVariants() { return bestOrderVariants; }
    public int bestOrderLength() { return bestOrderLength; }

    public int variantAt(int cell) { return boardVariant[cell]; }
    public int pieceAt(int cell) { return boardVariant[cell] < 0 ? -1 : boardVariant[cell] >>> 2; }
    public int rotAt(int cell) { return boardVariant[cell] < 0 ? -1 : boardVariant[cell] & 3; }
    public boolean isUsed(int pieceId) { return usedPiece[pieceId]; }
    public int placedCount() { return placed; }
    public int sidesOfVariant(int v) { return variantSides[v]; }
    public boolean variantExists(int v) { return testBit(exist, v); }
    public int boardWidth() { return n; }
    public int cellTotal() { return cells; }
    public long elapsedMs() { return (System.nanoTime() - startNanos) / 1000000L; }

    public int existCount() {
        int c = 0;
        for (int w = 0; w < words; w++) c += Long.bitCount(exist[w]);
        return c;
    }
    public int availCount() {
        int c = 0;
        for (int w = 0; w < words; w++) c += Long.bitCount(avail[w]);
        return c;
    }
    public long[] availSnapshot() {
        long[] out = new long[words];
        System.arraycopy(avail, 0, out, 0, words);
        return out;
    }
    public long[] cellMaskSnapshot() {
        long[] out = new long[words * cells];
        System.arraycopy(cellMask, 0, out, 0, words * cells);
        return out;
    }
    public int[] cellCountSnapshot() {
        int[] out = new int[cells];
        System.arraycopy(cellCount, 0, out, 0, cells);
        return out;
    }
    public int[] boardSnapshot() {
        int[] out = new int[cells];
        System.arraycopy(boardVariant, 0, out, 0, cells);
        return out;
    }
    public boolean[] usedSnapshot() {
        boolean[] out = new boolean[numPieces];
        System.arraycopy(usedPiece, 0, out, 0, numPieces);
        return out;
    }
    public int colourMaskCount(int side, int colour) {
        long[] m;
        if (side == Sides.LEFT) m = maskL[colour];
        else if (side == Sides.TOP) m = maskT[colour];
        else if (side == Sides.RIGHT) m = maskR[colour];
        else m = maskB[colour];
        int c = 0;
        for (int w = 0; w < words; w++) c += Long.bitCount(m[w]);
        return c;
    }

    // ------------------------------------------------------------------ main

    /**
     * Run the MRV solver on the real Eternity II puzzle.
     *
     *   java -cp out core.MrvSolver            # run until stopped
     *   java -cp out core.MrvSolver 50000000   # stop after 50M nodes
     */
    public static void main(String[] args) {
        MrvSolver s = new MrvSolver(Instance.eternity2());
        s.verbose = true;
        s.stopAtFirstSolution = true;
        if (args.length > 0) {
            try { s.maxNodes = Long.parseLong(args[0]); } catch (Throwable e) { }
        }
        System.out.println("Eternity II: 16x16, " + s.existCount()
            + " canonical (piece,rotation) variants, "
            + s.inst.fixedCell.length + " fixed placement(s)");
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
                + Validator.internalEdgeTotal(s.inst) + " matched edges)");
            if (s.bestBoard != null) {
                StringBuilder sb = new StringBuilder();
                for (int r = 0; r < s.n; r++) {
                    for (int c = 0; c < s.n; c++) {
                        int v = s.bestBoard[r * s.n + c];
                        sb.append(v < 0 ? "   ." : String.format("%4d", (v >>> 2) + 1));
                    }
                    sb.append('\n');
                }
                System.out.println(sb.toString());
                String err = Validator.validatePartial(s.inst, s.bestBoard, false);
                System.out.println("partial board validation: " + (err == null ? "OK" : err));
            }
        }
    }

    /** Render the current board as piece numbers (1-based), '.' for empty. */
    public String boardToString() {
        StringBuilder sb = new StringBuilder();
        sb.append("    ");
        for (int c = 0; c < n; c++) sb.append(String.format("%4d", c));
        sb.append('\n');
        for (int r = 0; r < n; r++) {
            sb.append(String.format("%3d ", r));
            for (int c = 0; c < n; c++) {
                int v = boardVariant[r * n + c];
                if (v < 0) sb.append("   .");
                else sb.append(String.format("%4d", (v >>> 2) + 1));
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
