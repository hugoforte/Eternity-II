package core;

import java.util.Arrays;
import java.util.Random;

/**
 * A very large neighbourhood repair stage: improve a finished board directly,
 * instead of searching deeper for a better one.
 *
 * The search engines in this package are depth-first.  When one runs out of
 * budget the board is simply whatever the descent left behind, and the only
 * way to do better is to descend again.  This class does something else: it
 * takes a board that is already down and rearranges pieces on it.
 *
 * <h2>The neighbourhood (Schaus and Deville, JFPC 2008)</h2>
 *
 * Pick <i>k</i> placed cells that are <b>pairwise non-adjacent</b>, lift their
 * pieces off, and put those same <i>k</i> pieces back into those same <i>k</i>
 * holes in whatever assignment and rotation scores best.  The neighbourhood
 * has {@code k! * 4^k} members, but it is searched <b>exactly</b> in
 * O(k^3) time, and non-adjacency is the whole reason why:
 *
 * <ul>
 *   <li>Board score = edges between two kept cells + edges between a hole and
 *       a kept cell.  With no two holes adjacent there is no third term.</li>
 *   <li>The first term does not move, so maximising the score means maximising
 *       the sum over holes of each hole's own contribution.</li>
 *   <li>A hole's contribution depends only on the pieces that stayed put, so
 *       the {@code (piece, hole)} contributions form a fixed
 *       {@code k x k} table and the best arrangement is a
 *       <b>maximum-weight bipartite matching</b> on it.</li>
 * </ul>
 *
 * A checkerboard colouring supplies non-adjacency for free: every cell with
 * {@code (row + col)} even is non-adjacent to every other such cell.
 *
 * <h2>Why the score can never go down</h2>
 *
 * Leaving every piece exactly where it was is itself a perfect matching, with
 * weight equal to the board's current contribution from those holes.  An
 * optimal matching is therefore never worse, so {@link #repairCells} returns a
 * gain of zero or more and the board's matched-edge count is monotone
 * non-decreasing.  That is a property of the algorithm, not a hope, and
 * {@link RepairTest} checks it anyway.
 *
 * <h2>Data layout</h2>
 *
 * Boards are the same {@code int[]} every solver and {@link Validator} uses:
 * {@code board[cell] = (pieceId << 2) | rot}, or -1 for an empty cell, with
 * {@code cell = row * n + col}.  Directions are indexed by {@link Sides}:
 * 0 left, 1 top, 2 right, 3 bottom, so the facing side of the neighbour in
 * direction {@code d} is direction {@code (d + 2) & 3}.
 *
 * <h2>What a repair may not do</h2>
 *
 * <ul>
 *   <li><b>Fixed placements never move.</b>  A fixed cell is never offered as
 *       a hole, so its piece is never lifted and never enters the pool.</li>
 *   <li><b>Grey belongs on the rim and nowhere else.</b>  A side facing off
 *       the board must be grey; a side facing a cell on the board must not be,
 *       whenever the instance's grey budget is exact (which is what makes that
 *       rule true -- see {@link Instance#greyBudgetIsExact()}).</li>
 *   <li><b>Every piece is used at most once.</b>  A repair is a permutation of
 *       pieces already on the board, so the multiset of placed pieces is
 *       unchanged.</li>
 *   <li><b>A repaired board is never a solution.</b>  Nothing here sets or
 *       reports {@code solved}; that word keeps meaning a validated
 *       256-piece board scoring 480.</li>
 * </ul>
 *
 * Scoring is not taken on trust either: {@link #run} keeps its own running
 * total from the matching weights and compares it against
 * {@link Validator#matchedEdges} on every iteration, failing loudly if the two
 * ever disagree.
 */
public final class Repair {

    public static final int GREY = Sides.GREY;

    /** Weight given to a (piece, hole) pair that has no legal rotation at all. */
    private static final int INFEASIBLE = Integer.MIN_VALUE / 4;

    public final Instance inst;

    private final int n;
    private final int cells;

    /** True for every cell named by a fixed placement; those are never holes. */
    private final boolean[] fixedCell;

    /**
     * True when the instance's grey budget is exact, which is what makes "no
     * grey on an interior side" a rule rather than a guess.  Generated
     * instances all satisfy it and so does Eternity II; an instance that did
     * not would simply have that check skipped.
     */
    private final boolean greyOnlyOnRim;

    // scratch, sized once, reused by every iteration of run()
    private final int[] holeWant = new int[4];      // facing colour, or -1 for none
    private final boolean[] holeRim = new boolean[4];

    public Repair(Instance inst) {
        if (inst == null) throw new IllegalArgumentException("instance is null");
        this.inst = inst;
        this.n = inst.n;
        this.cells = inst.cells;
        this.fixedCell = new boolean[cells];
        for (int i = 0; i < inst.fixedCell.length; i++) fixedCell[inst.fixedCell[i]] = true;
        this.greyOnlyOnRim = inst.greyBudgetIsExact();
    }

    // ------------------------------------------------------------------ result

    /** What a {@link Repair#run} call did, in the terms the puzzle is scored in. */
    public static final class Result {

        /** The repaired board.  The caller's array is not touched. */
        public final int[] board;

        /** Matched internal edges before any repair, from {@link Validator}. */
        public final int edgesBefore;

        /** Matched internal edges afterwards, from {@link Validator}. */
        public final int edgesAfter;

        /** Neighbourhoods actually built and solved. */
        public final int iterations;

        /** How many of those raised the score. */
        public final int improvements;

        /** Wall-clock time spent inside {@code run}. */
        public final long ms;

        Result(int[] board, int edgesBefore, int edgesAfter,
               int iterations, int improvements, long ms) {
            this.board = board;
            this.edgesBefore = edgesBefore;
            this.edgesAfter = edgesAfter;
            this.iterations = iterations;
            this.improvements = improvements;
            this.ms = ms;
        }

        public int gain() { return edgesAfter - edgesBefore; }

        @Override public String toString() {
            return "edges " + edgesBefore + " -> " + edgesAfter
                 + " (+" + gain() + ") in " + iterations + " neighbourhoods, "
                 + improvements + " improving, " + ms + " ms";
        }
    }

    // --------------------------------------------------------------- the loop

    /**
     * Repeatedly repair random neighbourhoods of size {@code k}, keeping every
     * result.  Because an optimal matching is never worse than leaving the
     * pieces alone, "keeping every result" is exactly the improving-or-equal
     * acceptance rule: improving moves raise the score and equal ones shuffle
     * pieces about, which is the only diversification this loop has.
     *
     * @param board      a legal board; not modified
     * @param k          holes per neighbourhood
     * @param iterations how many neighbourhoods to try
     * @param seed       seeds the cell choice, so a run is reproducible
     */
    public Result run(int[] board, int k, int iterations, long seed) {
        String bad = describeIfIllegal(board);
        if (bad != null) throw new IllegalArgumentException("board to repair is illegal: " + bad);
        if (k < 1) throw new IllegalArgumentException("k must be >= 1, got " + k);
        if (iterations < 0) {
            throw new IllegalArgumentException("iterations must be >= 0, got " + iterations);
        }

        long startNanos = System.nanoTime();
        int[] work = new int[cells];
        System.arraycopy(board, 0, work, 0, cells);

        int before = Validator.matchedEdges(inst, work);
        int edges = before;
        int improvements = 0;
        int done = 0;

        Random rnd = new Random(seed);
        int[] holes = new int[k];

        for (int it = 0; it < iterations; it++) {
            int picked = chooseHoles(work, k, rnd, holes);
            if (picked == 0) break;          // nothing left that a repair could improve

            int gain = repairCells(work, holes, picked);
            edges += gain;
            if (gain > 0) improvements++;
            done++;

            int measured = Validator.matchedEdges(inst, work);
            if (measured != edges) {
                throw new IllegalStateException(
                    "repair arithmetic disagrees with Validator at iteration " + it
                    + ": counted " + edges + " matched edges, Validator says " + measured
                    + " (last gain " + gain + ", " + picked + " holes)");
            }
        }

        String after = describeIfIllegal(work);
        if (after != null) {
            throw new IllegalStateException("repair produced an illegal board: " + after);
        }

        long ms = (System.nanoTime() - startNanos) / 1000000L;
        return new Result(work, before, edges, done, improvements, ms);
    }

    // ---------------------------------------------------------- one neighbourhood

    /**
     * Lift the pieces off {@code holes} and put them back optimally, in place.
     *
     * @param board the board to rearrange; modified
     * @param holes cells to rebuild -- all occupied, none fixed, pairwise
     *              non-adjacent, no repeats
     * @param count how much of {@code holes} to use
     * @return matched edges gained, always zero or more
     */
    public int repairCells(int[] board, int[] holes, int count) {
        checkHoles(board, holes, count);
        if (count == 0) return 0;

        int[] tile = new int[count];                 // piece id lifted from each hole
        int[] hadRot = new int[count];
        for (int i = 0; i < count; i++) {
            tile[i] = board[holes[i]] >>> 2;
            hadRot[i] = board[holes[i]] & 3;
        }
        for (int i = 0; i < count; i++) board[holes[i]] = -1;

        int[][] weight = new int[count][count];      // rows: holes, columns: tiles
        int[][] rot = new int[count][count];
        int before = 0;                              // what those holes score right now
        for (int h = 0; h < count; h++) {
            describeHole(board, holes[h]);
            int had = gainOfFit(inst.variantSides(tile[h], hadRot[h]));
            if (had == INFEASIBLE) {
                throw new IllegalStateException(
                    "cell " + holes[h] + " illegally holds piece " + (tile[h] + 1)
                    + " at rotation " + hadRot[h] + "; the board was not legal to begin with");
            }
            before += had;
            for (int t = 0; t < count; t++) {
                int best = INFEASIBLE, bestRot = -1;
                for (int r = 0; r < 4; r++) {
                    if (!inst.isCanonicalRotation(tile[t], r)) continue;
                    int g = gainOfFit(inst.variantSides(tile[t], r));
                    if (g > best) { best = g; bestRot = r; }
                }
                weight[h][t] = best;
                rot[h][t] = bestRot;
            }
        }

        // Leaving every piece in its own hole is itself a perfect matching, so
        // the optimum is both feasible and no worse.  Both facts are
        // re-derived below rather than assumed.
        int stayed = 0;
        for (int i = 0; i < count; i++) stayed += weight[i][i];

        int[] assigned = maxWeightAssignment(weight);
        int total = 0;
        for (int h = 0; h < count; h++) {
            int t = assigned[h];
            if (weight[h][t] == INFEASIBLE) {
                throw new IllegalStateException(
                    "matching chose an illegal placement: piece " + (tile[t] + 1)
                    + " in cell " + holes[h]);
            }
            total += weight[h][t];
        }
        if (total < stayed) {
            throw new IllegalStateException(
                "matching scored " + total + ", worse than the " + stayed
                + " the board already had; the assignment is not optimal");
        }

        for (int h = 0; h < count; h++) {
            int t = assigned[h];
            board[holes[h]] = (tile[t] << 2) | rot[h][t];
        }
        return total - before;
    }

    /**
     * Load {@link #holeWant} and {@link #holeRim} for one hole: what each of
     * its four sides faces, now that the hole itself is empty.
     *
     * {@code holeRim[d]} means direction {@code d} leaves the board, so that
     * side must be grey and is never scored.  {@code holeWant[d]} is the
     * colour a side must equal to score, or -1 when the neighbour is empty --
     * that side then scores nothing whatever the piece, but still may not be
     * grey.
     */
    private void describeHole(int[] board, int cell) {
        int r = cell / n, c = cell - r * n;
        for (int d = 0; d < 4; d++) {
            boolean rim;
            int nb;
            switch (d) {
                case Sides.LEFT:   rim = (c == 0);     nb = cell - 1; break;
                case Sides.TOP:    rim = (r == 0);     nb = cell - n; break;
                case Sides.RIGHT:  rim = (c == n - 1); nb = cell + 1; break;
                default:           rim = (r == n - 1); nb = cell + n; break;
            }
            holeRim[d] = rim;
            if (rim || board[nb] < 0) {
                holeWant[d] = -1;
            } else {
                int q = inst.variantSides(board[nb] >>> 2, board[nb] & 3);
                holeWant[d] = Sides.side(q, (d + 2) & 3);
            }
        }
    }

    /**
     * How many edges the given oriented piece would match in the hole last
     * passed to {@link #describeHole}, or {@link #INFEASIBLE} if it may not go
     * there at all.
     */
    private int gainOfFit(int packed) {
        int gain = 0;
        for (int d = 0; d < 4; d++) {
            int side = Sides.side(packed, d);
            if (holeRim[d]) {
                if (side != GREY) return INFEASIBLE;
            } else {
                if (greyOnlyOnRim && side == GREY) return INFEASIBLE;
                if (holeWant[d] >= 0 && side == holeWant[d]) gain++;
            }
        }
        return gain;
    }

    // ------------------------------------------------------------ choosing cells

    /**
     * Fill {@code out} with up to {@code k} pairwise non-adjacent cells and
     * return how many were found.
     *
     * The cells all come from one randomly chosen colour of the checkerboard,
     * which is what makes them pairwise non-adjacent.  Within that colour,
     * cells that already touch a mismatched edge are taken first: a
     * neighbourhood containing no mismatch has nothing to gain, because every
     * one of its holes is already scoring everything it can, so choosing
     * uniformly would spend almost every iteration proving that.  A board with
     * no mismatch at all yields nothing and the loop stops.
     */
    private int chooseHoles(int[] board, int k, Random rnd, int[] out) {
        if (k <= 0) throw new IllegalArgumentException("k must be >= 1, got " + k);
        if (out.length < k) {
            throw new IllegalArgumentException("output array holds " + out.length + ", need " + k);
        }
        int parity = rnd.nextInt(2);

        int[] conflicted = new int[cells];
        int[] clean = new int[cells];
        int nConf = 0, nClean = 0;
        for (int cell = 0; cell < cells; cell++) {
            if (board[cell] < 0 || fixedCell[cell]) continue;
            int r = cell / n, c = cell - r * n;
            if (((r + c) & 1) != parity) continue;
            if (touchesMismatch(board, cell)) conflicted[nConf++] = cell;
            else clean[nClean++] = cell;
        }
        if (nConf == 0) return 0;

        int taken = Math.min(k, nConf);
        partialShuffle(conflicted, nConf, taken, rnd);
        System.arraycopy(conflicted, 0, out, 0, taken);
        if (taken < k) {
            int fill = Math.min(k - taken, nClean);
            partialShuffle(clean, nClean, fill, rnd);
            System.arraycopy(clean, 0, out, taken, fill);
            taken += fill;
        }
        return taken;
    }

    /** Fisher-Yates over the first {@code want} slots only; the rest is left alone. */
    private static void partialShuffle(int[] a, int size, int want, Random rnd) {
        for (int i = 0; i < want; i++) {
            int j = i + rnd.nextInt(size - i);
            int tmp = a[i]; a[i] = a[j]; a[j] = tmp;
        }
    }

    /** True if this placed cell has a placed neighbour whose facing colour differs. */
    private boolean touchesMismatch(int[] board, int cell) {
        int r = cell / n, c = cell - r * n;
        int p = inst.variantSides(board[cell] >>> 2, board[cell] & 3);
        for (int d = 0; d < 4; d++) {
            int nb;
            switch (d) {
                case Sides.LEFT:   if (c == 0)     continue; nb = cell - 1; break;
                case Sides.TOP:    if (r == 0)     continue; nb = cell - n; break;
                case Sides.RIGHT:  if (c == n - 1) continue; nb = cell + 1; break;
                default:           if (r == n - 1) continue; nb = cell + n; break;
            }
            if (board[nb] < 0) continue;
            int q = inst.variantSides(board[nb] >>> 2, board[nb] & 3);
            if (Sides.side(p, d) != Sides.side(q, (d + 2) & 3)) return true;
        }
        return false;
    }

    // -------------------------------------------------------- bipartite matching

    /**
     * Maximum-weight perfect matching on a complete square bipartite graph:
     * the Hungarian algorithm (Kuhn-Munkres), in its O(n^3)
     * shortest-augmenting-path form.
     *
     * {@code weight[i][j]} is what pairing row {@code i} with column {@code j}
     * is worth.  The return value maps each row to the column it was given, so
     * it is always a permutation of {@code 0..n-1}, and it maximises the sum of
     * the weights chosen.  Weights may be negative; {@link #INFEASIBLE} is only
     * a very negative weight, so a caller who cares must check whether the
     * result used one.
     *
     * Potentials are kept in {@code long} so that a matrix full of
     * {@code INFEASIBLE} cannot overflow the dual updates.
     */
    public static int[] maxWeightAssignment(int[][] weight) {
        if (weight == null) throw new IllegalArgumentException("weight matrix is null");
        int size = weight.length;
        for (int i = 0; i < size; i++) {
            if (weight[i] == null || weight[i].length != size) {
                throw new IllegalArgumentException(
                    "weight matrix must be square: row " + i + " has "
                    + (weight[i] == null ? "null" : String.valueOf(weight[i].length))
                    + " columns, expected " + size);
            }
        }
        int[] rowToCol = new int[size];
        if (size == 0) return rowToCol;

        final long INF = Long.MAX_VALUE / 4;
        long[] u = new long[size + 1];        // row potentials, 1-based
        long[] v = new long[size + 1];        // column potentials, 1-based
        int[] match = new int[size + 1];      // match[col] = row, 0 = unmatched
        int[] way = new int[size + 1];        // column that led to this one
        long[] minv = new long[size + 1];
        boolean[] used = new boolean[size + 1];

        for (int row = 1; row <= size; row++) {
            match[0] = row;
            int col = 0;
            Arrays.fill(minv, INF);
            Arrays.fill(used, false);
            do {
                used[col] = true;
                int from = match[col];
                long delta = INF;
                int next = -1;
                for (int j = 1; j <= size; j++) {
                    if (used[j]) continue;
                    long cur = -(long) weight[from - 1][j - 1] - u[from] - v[j];
                    if (cur < minv[j]) { minv[j] = cur; way[j] = col; }
                    if (minv[j] < delta) { delta = minv[j]; next = j; }
                }
                for (int j = 0; j <= size; j++) {
                    if (used[j]) { u[match[j]] += delta; v[j] -= delta; }
                    else minv[j] -= delta;
                }
                col = next;
            } while (match[col] != 0);
            do {
                int prev = way[col];
                match[col] = match[prev];
                col = prev;
            } while (col != 0);
        }

        for (int j = 1; j <= size; j++) rowToCol[match[j] - 1] = j - 1;
        return rowToCol;
    }

    // ------------------------------------------------------------------ checks

    private void checkHoles(int[] board, int[] holes, int count) {
        if (board == null) throw new IllegalArgumentException("board is null");
        if (board.length != cells) {
            throw new IllegalArgumentException(
                "board has " + board.length + " cells, expected " + cells);
        }
        if (holes == null) throw new IllegalArgumentException("holes is null");
        if (count < 0 || count > holes.length) {
            throw new IllegalArgumentException(
                "count " + count + " is outside holes[" + holes.length + "]");
        }
        boolean[] chosen = new boolean[cells];
        for (int i = 0; i < count; i++) {
            int cell = holes[i];
            if (cell < 0 || cell >= cells) {
                throw new IllegalArgumentException("hole " + i + " is cell " + cell
                    + ", outside 0.." + (cells - 1));
            }
            if (chosen[cell]) {
                throw new IllegalArgumentException("cell " + cell + " appears twice in holes");
            }
            if (board[cell] < 0) {
                throw new IllegalArgumentException("cell " + cell + " is empty, so there is"
                    + " no piece to lift out of it");
            }
            if (fixedCell[cell]) {
                throw new IllegalArgumentException("cell " + cell + " holds a fixed placement"
                    + " and may not be repaired");
            }
            chosen[cell] = true;
        }
        for (int i = 0; i < count; i++) {
            int cell = holes[i];
            int r = cell / n, c = cell - r * n;
            if (c > 0     && chosen[cell - 1]) throw adjacency(cell, cell - 1);
            if (r > 0     && chosen[cell - n]) throw adjacency(cell, cell - n);
            if (c < n - 1 && chosen[cell + 1]) throw adjacency(cell, cell + 1);
            if (r < n - 1 && chosen[cell + n]) throw adjacency(cell, cell + n);
        }
    }

    private static IllegalArgumentException adjacency(int a, int b) {
        return new IllegalArgumentException("cells " + a + " and " + b + " are adjacent;"
            + " a repair neighbourhood must be pairwise non-adjacent so that each hole"
            + " can be refilled independently");
    }

    /**
     * Ask {@link Validator} whether the board breaks any rule other than
     * matching, allowing exactly the mismatches it already has.  Returns null
     * when the board is legal, else the validator's complaint.
     */
    private String describeIfIllegal(int[] board) {
        if (board == null) return "board is null";
        if (board.length != cells) {
            return "board has " + board.length + " cells, expected " + cells;
        }
        return Validator.validatePartial(inst, board, false,
                                         placedPairs(board) - Validator.matchedEdges(inst, board));
    }

    /** Adjacent cell pairs where both cells hold a piece -- the edges that are scored. */
    private int placedPairs(int[] board) {
        int pairs = 0;
        for (int cell = 0; cell < cells; cell++) {
            if (board[cell] < 0) continue;
            int r = cell / n, c = cell - r * n;
            if (c < n - 1 && board[cell + 1] >= 0) pairs++;
            if (r < n - 1 && board[cell + n] >= 0) pairs++;
        }
        return pairs;
    }
}
