package core;

/**
 * Fixed cell orders for {@link ScanSolver}, and the cheap structural measures
 * used to judge them.
 *
 * ----------------------------------------------------------------- invariant
 *
 * Every order built here has the property the scan solver is designed around:
 * when a cell is reached, its NORTH and WEST neighbours are already placed and
 * its EAST and SOUTH neighbours are not.  Exactly two known colours therefore
 * constrain every cell, so the solver needs one candidate key and one code
 * path for the whole board.  {@link #validate} states the property in full and
 * is what the solver's constructor checks against.
 *
 * --------------------------------------------------------------- banded scan
 *
 * {@link #bandedScan} reproduces, at n = 16, the order used by the fastest
 * published Eternity II engine.  Four phases, with the depths they occupy on
 * the real puzzle:
 *
 *   0..175    left-to-right row scan of the first 11 rows
 *   176..200  the scan continues, narrowed to columns 0..4
 *   201..235  the rest of the 5-row bottom band, column by column, 5 at a time
 *   236..255  the bottom-right 5x4 corner, peeled as four nested L-shapes
 *
 * The phases exist to shrink the *active sweep* as the tree gets expensive.  A
 * full-width row scan commits a cell's south colour 16 placements before
 * anything looks at it, so a bad choice can survive 16 further placements; in
 * the column sweep that lag is 5 and in the L-shapes at most 8, averaging
 * under 5.  {@link #detectionLag} measures exactly this.
 *
 * The three phase sizes are n/3 (band height), n/3 (narrow columns) and n/4
 * (L-shape depth).  At n = 16 they are 5, 5 and 4, which is the published
 * order; every other size gets the same shape rather than a special case.
 *
 * ------------------------------------------------------------------ measures
 *
 * Two different things are worth knowing about an order, and they disagree, so
 * both are provided rather than one being presented as the answer:
 *
 *   {@link #peakOpenFrontier} counts, at its worst depth, the placed cells
 *   that still touch an empty one -- how much of the board is held open at
 *   once.  A banded order holds *more* open than a plain row scan, because it
 *   deliberately leaves a bottom band unfilled.
 *
 *   {@link #detectionLag} measures, per cell, how many placements happen
 *   between it and the last neighbour that can contradict it -- how long a
 *   mistake survives.  This is where the banded order wins, and it wins in the
 *   deep tail, which is where the tree is most expensive.
 */
public final class FillOrder {

    private FillOrder() { }

    // ------------------------------------------------------------------ orders

    /** Plain left-to-right, top-to-bottom sweep of the whole board. */
    public static int[] rowMajor(int n) {
        requireSize(n);
        int[] order = new int[n * n];
        for (int i = 0; i < order.length; i++) order[i] = i;
        return order;
    }

    /**
     * Row scan, narrowed scan, column sweep, nested L-shapes -- see the class
     * comment for what each phase is for and why the sizes are what they are.
     */
    public static int[] bandedScan(int n) {
        requireSize(n);
        int band   = Math.max(1, n / 3);   // rows held back for the bottom band
        int narrow = Math.max(1, n / 3);   // columns the narrowed scan covers
        int lShape = Math.max(1, n / 4);   // columns peeled as nested L-shapes

        int topRows  = n - band;
        int sweepEnd = n - lShape;         // first column of the L-shape corner

        int[] order = new int[n * n];
        int d = 0;

        for (int r = 0; r < topRows; r++) {
            for (int c = 0; c < n; c++) order[d++] = r * n + c;
        }
        for (int r = topRows; r < n; r++) {
            for (int c = 0; c < narrow; c++) order[d++] = r * n + c;
        }
        for (int c = narrow; c < sweepEnd; c++) {
            for (int r = topRows; r < n; r++) order[d++] = r * n + c;
        }
        for (int k = 0; k < lShape; k++) {
            int r = topRows + k;
            for (int c = sweepEnd + k; c < n; c++) order[d++] = r * n + c;
            for (int rr = r + 1; rr < n; rr++) order[d++] = rr * n + sweepEnd + k;
        }

        if (d != n * n) {
            throw new IllegalStateException(
                "banded scan for n=" + n + " produced " + d + " cells, expected " + (n * n));
        }
        return order;
    }

    private static void requireSize(int n) {
        if (n < 2) throw new IllegalArgumentException("n must be >= 2, got " + n);
    }

    // -------------------------------------------------------------- validation

    /**
     * @return null if {@code order} is a permutation of the cells in which
     *         every cell arrives with exactly its north and west neighbours
     *         placed, otherwise a description of the first problem found.
     */
    public static String validate(int[] order, int n) {
        if (order == null) return "order is null";
        int cells = n * n;
        if (order.length != cells) {
            return "order has " + order.length + " entries, expected " + cells;
        }
        int[] depthOf = new int[cells];
        for (int i = 0; i < cells; i++) depthOf[i] = -1;
        for (int d = 0; d < cells; d++) {
            int cell = order[d];
            if (cell < 0 || cell >= cells) return "depth " + d + " holds cell " + cell;
            if (depthOf[cell] >= 0) {
                return "cell " + cell + " appears at depths " + depthOf[cell] + " and " + d;
            }
            depthOf[cell] = d;
        }
        for (int d = 0; d < cells; d++) {
            int cell = order[d];
            int r = cell / n, c = cell - r * n;
            if (r > 0 && depthOf[cell - n] > d) {
                return "cell " + cell + " (r" + r + ",c" + c + ") at depth " + d
                     + " has its north neighbour at depth " + depthOf[cell - n];
            }
            if (c > 0 && depthOf[cell - 1] > d) {
                return "cell " + cell + " (r" + r + ",c" + c + ") at depth " + d
                     + " has its west neighbour at depth " + depthOf[cell - 1];
            }
            if (r < n - 1 && depthOf[cell + n] < d) {
                return "cell " + cell + " (r" + r + ",c" + c + ") at depth " + d
                     + " has its south neighbour already placed at depth " + depthOf[cell + n];
            }
            if (c < n - 1 && depthOf[cell + 1] < d) {
                return "cell " + cell + " (r" + r + ",c" + c + ") at depth " + d
                     + " has its east neighbour already placed at depth " + depthOf[cell + 1];
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- measures

    /**
     * The largest number of placed cells that simultaneously still touch an
     * empty cell, over every depth of the order.
     */
    public static int peakOpenFrontier(int[] order, int n) {
        int cells = n * n;
        boolean[] placed = new boolean[cells];
        int[] emptyNeighbours = new int[cells];
        for (int cell = 0; cell < cells; cell++) emptyNeighbours[cell] = degree(cell, n);

        int open = 0, peak = 0;
        for (int d = 0; d < cells; d++) {
            int cell = order[d];
            placed[cell] = true;
            int r = cell / n, c = cell - r * n;
            if (r > 0)     open -= close(cell - n, placed, emptyNeighbours);
            if (r < n - 1) open -= close(cell + n, placed, emptyNeighbours);
            if (c > 0)     open -= close(cell - 1, placed, emptyNeighbours);
            if (c < n - 1) open -= close(cell + 1, placed, emptyNeighbours);
            if (emptyNeighbours[cell] > 0) open++;
            if (open > peak) peak = open;
        }
        return peak;
    }

    /** Drop one empty neighbour from a cell; returns 1 if that closed it. */
    private static int close(int neighbour, boolean[] placed, int[] emptyNeighbours) {
        emptyNeighbours[neighbour]--;
        if (placed[neighbour] && emptyNeighbours[neighbour] == 0) return 1;
        return 0;
    }

    private static int degree(int cell, int n) {
        int r = cell / n, c = cell - r * n;
        int k = 0;
        if (r > 0) k++;
        if (r < n - 1) k++;
        if (c > 0) k++;
        if (c < n - 1) k++;
        return k;
    }

    /**
     * lag[d] = how many further placements happen before the last neighbour
     * that could contradict the cell placed at depth {@code d} is itself
     * placed.  0 means the cell is fully checked the moment it goes down.
     */
    public static int[] detectionLag(int[] order, int n) {
        int cells = n * n;
        int[] depthOf = new int[cells];
        for (int d = 0; d < cells; d++) depthOf[order[d]] = d;

        int[] lag = new int[cells];
        for (int d = 0; d < cells; d++) {
            int cell = order[d];
            int r = cell / n, c = cell - r * n;
            int worst = 0;
            if (r > 0)     worst = later(worst, depthOf[cell - n], d);
            if (r < n - 1) worst = later(worst, depthOf[cell + n], d);
            if (c > 0)     worst = later(worst, depthOf[cell - 1], d);
            if (c < n - 1) worst = later(worst, depthOf[cell + 1], d);
            lag[d] = worst;
        }
        return lag;
    }

    private static int later(int worst, int neighbourDepth, int d) {
        int gap = neighbourDepth - d;
        return (gap > worst) ? gap : worst;
    }

    /** Worst detection lag over depths {@code fromDepth} and later. */
    public static int peakDetectionLag(int[] order, int n, int fromDepth) {
        int[] lag = detectionLag(order, n);
        int peak = 0;
        for (int d = Math.max(0, fromDepth); d < lag.length; d++) {
            if (lag[d] > peak) peak = lag[d];
        }
        return peak;
    }

    /** Mean detection lag over depths {@code fromDepth} and later, times 100. */
    public static int meanDetectionLagX100(int[] order, int n, int fromDepth) {
        int[] lag = detectionLag(order, n);
        int from = Math.max(0, fromDepth);
        long total = 0;
        int count = 0;
        for (int d = from; d < lag.length; d++) { total += lag[d]; count++; }
        if (count == 0) return 0;
        return (int) ((total * 100L) / count);
    }
}
