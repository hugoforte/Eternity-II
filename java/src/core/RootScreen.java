package core;

import java.util.Arrays;

/**
 * Root pre-screening: rank the corners the search could start from, cheaply,
 * then spend the real compute only on the ones that probed well.
 *
 * ------------------------------------------------------------------ a root
 *
 * A root is a legal {@code size x size} block of pieces in the corner the fill
 * order reaches first, and nothing more.  It is expressed the only way the
 * engine already understands one: as extra entries in an {@link Instance}'s
 * {@code fixedCell} / {@code fixedPiece} / {@code fixedRot} arrays, exactly
 * how Eternity II's mandatory hint piece is pinned.  {@link ScanSolver} is
 * therefore used unmodified -- it sees a puzzle with ten fixed placements
 * instead of one, gives each a private candidate class, and pins the colours
 * of their neighbours the same way it always has.
 *
 * ----------------------------------------------------------- which corner
 *
 * The block is anchored at {@code FillOrder}'s first cell, which for both the
 * banded and the row-major order is the TOP-LEFT corner, cell 0.  This is
 * checked rather than assumed: the banded order holds back the *bottom* band
 * and peels the bottom-right corner last, so screening a bottom corner would
 * rank configurations the search only reaches after 200 other placements, and
 * would measure nothing at all.  {@link #enumerate} fails with the offending
 * cell if the configured order starts anywhere else.
 *
 * -------------------------------------------------------------- legality
 *
 * The block is filled row by row, so every cell in it arrives with its west
 * and north neighbours already placed -- the same invariant {@link ScanSolver}
 * is built on -- and a candidate must:
 *
 *   1. be a canonical rotation of a piece no other block cell has taken, and
 *      that the instance has not already fixed elsewhere;
 *   2. show the west neighbour's right colour on its left, and the north
 *      neighbour's bottom colour on its top (GREY off the board);
 *   3. show GREY on a side facing off the board and, when the instance's grey
 *      budget is exact, never show GREY on a side facing another cell.
 *
 * On the real puzzle that leaves 2,582,369 legal 3x3 corners, walked in about
 * 200 ms.  Far too many to probe, so {@link #enumerate} draws a uniform sample
 * by reservoir while it counts, in one pass.
 *
 * ------------------------------------------------------------- the honest
 *                                                                comparison
 *
 * A ranking that merely agrees with roots already known to be productive
 * proves nothing -- that is circular.  The only thing worth reporting is an
 * equal-compute comparison: N nodes spread over screened roots against the
 * same N nodes spent the ordinary way, with the probing charged to the
 * screened side.  {@link #main} runs exactly that, with a third arm that
 * spreads the same N over *unscreened* roots so that any gain from simply
 * diversifying is not credited to the screen.
 */
public final class RootScreen {

    public static final int GREY = Sides.GREY;

    private RootScreen() { }

    // ------------------------------------------------------------------ a root

    /**
     * One corner configuration: parallel arrays in block row-major order,
     * holding board cells, piece ids and canonical rotations.
     */
    public static final class Root {

        public final int[] cell;
        public final int[] piece;
        public final int[] rot;

        Root(int[] cell, int[] variant) {
            this.cell = new int[cell.length];
            this.piece = new int[variant.length];
            this.rot = new int[variant.length];
            for (int i = 0; i < variant.length; i++) {
                this.cell[i] = cell[i];
                this.piece[i] = variant[i] >>> 2;
                this.rot[i] = variant[i] & 3;
            }
        }

        /**
         * The empty root: nothing extra pinned, so the puzzle exactly as it
         * stands.  This is what the control arm searches from, which keeps the
         * comparison down one code path instead of two.
         */
        public static Root none() {
            return new Root(new int[0], new int[0]);
        }

        /** A board holding this root and nothing else, for {@link Validator}. */
        public int[] board(Instance inst) {
            int[] board = new int[inst.cells];
            for (int c = 0; c < inst.cells; c++) board[c] = -1;
            for (int i = 0; i < cell.length; i++) board[cell[i]] = (piece[i] << 2) | rot[i];
            return board;
        }

        /** Piece numbers are 1-based here, as everywhere a human reads them. */
        public String toString() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < piece.length; i++) {
                if (i > 0) sb.append(' ');
                sb.append(piece[i] + 1).append('/').append(rot[i]);
            }
            return sb.toString();
        }
    }

    /** What {@link #enumerate} found: how many roots exist, and a sample of them. */
    public static final class Catalogue {

        public final int size;
        /** Every legal corner of this size, counted exactly. */
        public final long total;
        /** A uniform sample of them, or all of them when there are few enough. */
        public final Root[] sample;
        /** Wall-clock milliseconds the walk took. */
        public final long millis;

        Catalogue(int size, long total, Root[] sample, long millis) {
            this.size = size;
            this.total = total;
            this.sample = sample;
            this.millis = millis;
        }
    }

    /**
     * What one capped run from one root achieved.  A short probe and a long
     * run differ only in their node budget, so both are reported as this.
     */
    public static final class Probe implements Comparable<Probe> {

        public final Root root;
        /** Pieces on the deepest board reached. */
        public final int depth;
        /** Matched internal edges of that board. */
        public final int matchedEdges;
        /** Nodes actually spent, which may be less than the budget. */
        public final long nodes;
        public final int[] board;

        Probe(Root root, int depth, int matchedEdges, long nodes, int[] board) {
            this.root = root;
            this.depth = depth;
            this.matchedEdges = matchedEdges;
            this.nodes = nodes;
            this.board = board;
        }

        /** Orders best first: deepest, and best-scoring within a depth. */
        public int compareTo(Probe other) {
            if (depth != other.depth) return other.depth - depth;
            return other.matchedEdges - matchedEdges;
        }

        public String toString() {
            return depth + " pieces, " + matchedEdges + " edges, " + nodes + " nodes";
        }
    }

    // ------------------------------------------------------------- the corner

    /**
     * The {@code size x size} block at the top-left corner, in the row-by-row
     * order the enumeration fills it.
     */
    public static int[] cornerCells(int n, int size) {
        if (n < 2) throw new IllegalArgumentException("n must be >= 2, got " + n);
        if (size < 1 || size > n) {
            throw new IllegalArgumentException(
                "root block size must be in 1.." + n + ", got " + size);
        }
        int[] cells = new int[size * size];
        int at = 0;
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) cells[at++] = r * n + c;
        }
        return cells;
    }

    /**
     * The cell the engine places first, asked of the engine rather than
     * worked out again here.  Everything this class does rests on the answer,
     * so it comes from the same object that will do the searching.
     */
    public static int firstFilledCell(Instance base, SolverConfig cfg) {
        return new ScanSolver(base, cfg).fillOrder()[0];
    }

    // --------------------------------------------------------- enumeration

    /**
     * Walk every legal corner of this size, counting them all and keeping a
     * uniform sample of at most {@code maxSample} of them.
     *
     * The sample is drawn by reservoir, so the walk is single-pass and the
     * same seed always draws the same roots however many there turn out to be.
     *
     * @param base       the puzzle, with whatever it already fixes
     * @param cfg        the configuration the roots will be searched under;
     *                   only its fill order and grey pruning matter here
     * @param size       block width, so 3 for the published 3x3 recipe
     * @param maxSample  how many roots to keep; 0 counts without keeping any
     * @param seed       fixes which roots the sample holds
     */
    public static Catalogue enumerate(Instance base, SolverConfig cfg,
                                      int size, int maxSample, long seed) {
        if (base == null) throw new IllegalArgumentException("instance is null");
        if (maxSample < 0) {
            throw new IllegalArgumentException("maxSample must be >= 0, got " + maxSample);
        }
        SolverConfig config = (cfg == null) ? new SolverConfig() : cfg;
        int[] block = cornerCells(base.n, size);

        for (int i = 0; i < base.fixedCell.length; i++) {
            for (int k = 0; k < block.length; k++) {
                if (base.fixedCell[i] == block[k]) {
                    throw new IllegalStateException(
                        "cell " + block[k] + " is inside the " + size + "x" + size
                        + " root block but the instance already fixes piece "
                        + (base.fixedPiece[i] + 1) + " there");
                }
            }
        }
        int first = firstFilledCell(base, config);
        if (first != block[0]) {
            throw new IllegalStateException(
                "the " + SolverConfig.fillOrderName(config.fillOrder) + " order starts at cell "
                + first + ", not at cell " + block[0] + ", so screening that corner would rank"
                + " configurations the search does not start from");
        }

        long start = System.nanoTime();
        Walk walk = new Walk(base, config, block, size, maxSample, seed);
        walk.descend(0);
        long millis = (System.nanoTime() - start) / 1000000L;
        return new Catalogue(size, walk.total, walk.kept(), millis);
    }

    /**
     * The enumeration's working state.  Candidates are indexed once by the
     * two colours that constrain them, the same {@code top * numColours +
     * left} key {@link ScanSolver} uses, so the inner loop only has to reject
     * pieces already spent and sides that face the wrong way.
     */
    private static final class Walk {

        private final Instance inst;
        private final int n, numColours;
        private final int[] block;
        private final int size;
        private final boolean pruneGrey;
        private final int[][] byKey;
        private final boolean[] used;
        private final int[] chosen;

        private final Root[] reservoir;
        private long rngState;
        long total;

        Walk(Instance inst, SolverConfig cfg, int[] block, int size, int maxSample, long seed) {
            this.inst = inst;
            this.n = inst.n;
            this.numColours = inst.numColours;
            this.block = block;
            this.size = size;
            this.pruneGrey = cfg.greyInteriorPruning && inst.greyBudgetIsExact();
            this.used = new boolean[inst.numPieces];
            this.chosen = new int[block.length];
            this.reservoir = new Root[maxSample];
            this.rngState = (seed == 0) ? 0x9E3779B97F4A7C15L : seed;

            // A piece the instance has already fixed is spent, so it is marked
            // used before the walk starts and never offered to a block cell.
            for (int i = 0; i < inst.fixedPiece.length; i++) used[inst.fixedPiece[i]] = true;

            int pairs = numColours * numColours;
            int[] count = new int[pairs];
            for (int id = 0; id < inst.numPieces; id++) {
                if (used[id]) continue;
                for (int r = 0; r < 4; r++) {
                    if (!inst.isCanonicalRotation(id, r)) continue;
                    int p = inst.variantSides(id, r);
                    count[Sides.top(p) * numColours + Sides.left(p)]++;
                }
            }
            this.byKey = new int[pairs][];
            for (int k = 0; k < pairs; k++) byKey[k] = new int[count[k]];
            int[] at = new int[pairs];
            for (int id = 0; id < inst.numPieces; id++) {
                if (used[id]) continue;
                for (int r = 0; r < 4; r++) {
                    if (!inst.isCanonicalRotation(id, r)) continue;
                    int p = inst.variantSides(id, r);
                    int key = Sides.top(p) * numColours + Sides.left(p);
                    byKey[key][at[key]++] = (id << 2) | r;
                }
            }
        }

        void descend(int at) {
            if (at == block.length) {
                total++;
                offer();
                return;
            }
            int cell = block[at];
            int r = at / size, c = at - r * size;
            int left = (c == 0) ? GREY : Sides.right(sidesOf(chosen[at - 1]));
            int top = (r == 0) ? GREY : Sides.bottom(sidesOf(chosen[at - size]));
            int boardRow = cell / n, boardCol = cell - boardRow * n;

            int[] candidates = byKey[top * numColours + left];
            for (int i = 0; i < candidates.length; i++) {
                int v = candidates[i];
                if (used[v >>> 2]) continue;
                int p = sidesOf(v);
                if (!sideFits(Sides.right(p), boardCol == n - 1)) continue;
                if (!sideFits(Sides.bottom(p), boardRow == n - 1)) continue;
                chosen[at] = v;
                used[v >>> 2] = true;
                descend(at + 1);
                used[v >>> 2] = false;
            }
        }

        private int sidesOf(int variant) {
            return inst.variantSides(variant >>> 2, variant & 3);
        }

        /** A side facing off the board must be grey, and one facing inland must not. */
        private boolean sideFits(int colour, boolean facesOffBoard) {
            if (facesOffBoard) return colour == GREY;
            return !pruneGrey || colour != GREY;
        }

        /**
         * Algorithm R: the first k roots are kept, and the t-th thereafter
         * replaces a random one with probability k/t.  A root is only built
         * when it is kept, so counting millions of them costs no allocation.
         */
        private void offer() {
            int k = reservoir.length;
            if (k == 0) return;
            if (total <= k) {
                reservoir[(int) total - 1] = new Root(block, chosen);
                return;
            }
            long slot = nextBounded(total);
            if (slot < k) reservoir[(int) slot] = new Root(block, chosen);
        }

        Root[] kept() {
            int held = (total < reservoir.length) ? (int) total : reservoir.length;
            Root[] out = new Root[held];
            System.arraycopy(reservoir, 0, out, 0, held);
            return out;
        }

        /** xorshift64, the same generator the solvers use. */
        private long nextBounded(long bound) {
            long x = rngState;
            x ^= (x << 13);
            x ^= (x >>> 7);
            x ^= (x << 17);
            rngState = x;
            return (x >>> 1) % bound;
        }
    }

    // ------------------------------------------------------------- searching

    /** The puzzle with the root pinned on top of whatever it already fixed. */
    public static Instance instanceFor(Instance base, Root root) {
        if (base == null) throw new IllegalArgumentException("instance is null");
        if (root == null) throw new IllegalArgumentException("root is null");
        int have = base.fixedCell.length;
        int total = have + root.cell.length;
        int[] cell = new int[total];
        int[] piece = new int[total];
        int[] rot = new int[total];
        System.arraycopy(base.fixedCell, 0, cell, 0, have);
        System.arraycopy(base.fixedPiece, 0, piece, 0, have);
        System.arraycopy(base.fixedRot, 0, rot, 0, have);
        System.arraycopy(root.cell, 0, cell, have, root.cell.length);
        System.arraycopy(root.piece, 0, piece, have, root.piece.length);
        System.arraycopy(root.rot, 0, rot, have, root.rot.length);
        return new Instance(base.n, base.piecesCopy(), cell, piece, rot);
    }

    /**
     * Run {@link ScanSolver} from one root for at most {@code nodeBudget}
     * nodes and report how far it got.  This is both the short screening probe
     * and the long run afterwards; only the budget differs.
     */
    public static Probe probe(Instance base, SolverConfig cfg, Root root, long nodeBudget) {
        if (nodeBudget < 1) {
            throw new IllegalArgumentException("a probe needs at least one node, got " + nodeBudget);
        }
        ScanSolver solver = new ScanSolver(instanceFor(base, root), cfg);
        solver.maxNodes = nodeBudget;
        solver.stopAtFirstSolution = true;
        solver.solve();
        return new Probe(root, solver.bestPlaced, solver.bestMatchedEdges,
                         solver.nodes, solver.bestBoard);
    }

    /** Probe every root for the same budget and rank them best first. */
    public static Probe[] screen(Instance base, SolverConfig cfg, Root[] roots, long probeNodes) {
        if (roots == null) throw new IllegalArgumentException("roots is null");
        Probe[] probes = new Probe[roots.length];
        for (int i = 0; i < roots.length; i++) probes[i] = probe(base, cfg, roots[i], probeNodes);
        Arrays.sort(probes);
        return probes;
    }

    // ------------------------------------------------------------------ main

    /**
     * The equal-compute comparison, and nothing weaker.
     *
     *   java -cp java/classes core.RootScreen --budget=1000000000 \
     *        --fillOrder=banded --slipSchedule=verhaard
     *
     * Three arms, each allowed the same total node budget:
     *
     *   plain     one descent from the puzzle as it stands;
     *   screened  half the budget probing {@code --roots} roots, the other
     *             half split equally over the best tenth of them;
     *   spread    the whole budget split equally over the same number of
     *             roots, chosen at random and never probed.
     *
     * The third arm is the control that matters: without it a screened arm
     * that beat the plain one would only show that starting somewhere else
     * helps, not that the ranking does.
     */
    public static void main(String[] args) {
        SolverConfig cfg = new SolverConfig();
        cfg.engine = SolverConfig.ENGINE_SCAN;
        long budget = 1000000000L;
        long probeNodes = 1000000L;
        int size = 3;
        int roots = 0;
        int keep = 0;
        long seed = 20260920L;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--budget=")) budget = Long.parseLong(value(a));
            else if (a.startsWith("--probe=")) probeNodes = Long.parseLong(value(a));
            else if (a.startsWith("--roots=")) roots = Integer.parseInt(value(a));
            else if (a.startsWith("--keep=")) keep = Integer.parseInt(value(a));
            else if (a.startsWith("--size=")) size = Integer.parseInt(value(a));
            else if (a.startsWith("--seed=")) seed = Long.parseLong(value(a));
            else cfg.applyArg(a);
        }
        if (roots <= 0) roots = (int) Math.max(1L, (budget / 2L) / probeNodes);
        if (keep <= 0) keep = Math.max(1, roots / 10);
        if (keep > roots) keep = roots;

        Instance base = Instance.eternity2();
        System.out.println("budget " + budget + " nodes per arm, " + SolverConfig.fillOrderName(cfg.fillOrder)
            + " order, slip " + SolverConfig.slipScheduleName(cfg.slipSchedule));

        Catalogue cat = enumerate(base, cfg, size, roots, seed);
        System.out.println("legal " + size + "x" + size + " corners at cell "
            + cornerCells(base.n, size)[0] + ": " + cat.total
            + " (enumerated in " + cat.millis + " ms, sampled " + cat.sample.length + ")");

        // --- screened: probe everything, spend what is left on the best -----
        long screenStart = System.nanoTime();
        Probe[] ranked = screen(base, cfg, cat.sample, probeNodes);
        long probeSpent = 0;
        for (int i = 0; i < ranked.length; i++) probeSpent += ranked[i].nodes;
        printDistribution(ranked);

        long perRoot = Math.max(1L, (budget - probeSpent) / keep);
        Probe[] screened = new Probe[keep];
        for (int i = 0; i < keep; i++) screened[i] = probe(base, cfg, ranked[i].root, perRoot);
        long screenedMs = (System.nanoTime() - screenStart) / 1000000L;

        // --- spread: the same budget over roots nobody ranked ---------------
        long spreadStart = System.nanoTime();
        long spreadPerRoot = Math.max(1L, budget / keep);
        Probe[] spread = new Probe[keep];
        for (int i = 0; i < keep; i++) spread[i] = probe(base, cfg, cat.sample[i], spreadPerRoot);
        long spreadMs = (System.nanoTime() - spreadStart) / 1000000L;

        // --- plain: one descent, from the empty root ------------------------
        long plainStart = System.nanoTime();
        Probe[] plain = { probe(base, cfg, Root.none(), budget) };
        long plainMs = (System.nanoTime() - plainStart) / 1000000L;

        int edgeTotal = Validator.internalEdgeTotal(base);
        System.out.println();
        System.out.println("mean probe depth: " + meanDepth(ranked, 0, ranked.length)
            + " over all " + ranked.length + " roots, "
            + meanDepth(ranked, 0, keep) + " over the " + keep + " kept");
        System.out.println();
        System.out.println("arm       roots  nodes/root   nodes spent  best pieces  best edges  mean pieces  ms");
        report("plain", plain, budget, 0L, edgeTotal, plainMs);
        report("screened", screened, perRoot, probeSpent, edgeTotal, screenedMs);
        report("spread", spread, spreadPerRoot, 0L, edgeTotal, spreadMs);
    }

    private static String value(String arg) {
        return arg.substring(arg.indexOf('=') + 1);
    }

    /**
     * One arm's line.  The best board is the headline, but with several runs
     * per arm a maximum is a noisy statistic, so the mean depth is reported
     * beside it; {@code extraNodes} is what the arm spent before its runs,
     * which is how the screening probes are charged to the arm that used them.
     */
    private static void report(String arm, Probe[] runs, long perRoot, long extraNodes,
                               int edgeTotal, long ms) {
        long spent = extraNodes;
        int bestDepth = 0, bestEdges = 0;
        for (int i = 0; i < runs.length; i++) {
            spent += runs[i].nodes;
            if (runs[i].depth > bestDepth
                    || (runs[i].depth == bestDepth && runs[i].matchedEdges > bestEdges)) {
                bestDepth = runs[i].depth;
                bestEdges = runs[i].matchedEdges;
            }
        }
        System.out.println(pad(arm, 10) + pad("" + runs.length, 7) + pad("" + perRoot, 13)
            + pad("" + spent, 13) + pad(bestDepth + "/256", 13)
            + pad(bestEdges + "/" + edgeTotal, 12)
            + pad(meanDepth(runs, 0, runs.length), 13) + ms);
    }

    /** Mean pieces placed over a slice of runs, to one decimal place. */
    private static String meanDepth(Probe[] runs, int from, int to) {
        int count = to - from;
        if (count <= 0) return "-";
        long sum = 0;
        for (int i = from; i < to; i++) sum += runs[i].depth;
        long tenths = (sum * 10L + count / 2) / count;
        return (tenths / 10) + "." + (tenths % 10);
    }

    private static String pad(String s, int width) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < width) sb.append(' ');
        return sb.toString();
    }

    /**
     * How the probes came out.  If every root probes to the same depth the
     * ranking has nothing to rank and the screen cannot help, which is worth
     * knowing before any compute is spent on it.
     */
    private static void printDistribution(Probe[] ranked) {
        if (ranked.length == 0) return;
        System.out.println();
        System.out.println("probe distribution (" + ranked.length + " roots):");
        int at = 0;
        while (at < ranked.length) {
            int depth = ranked[at].depth;
            int end = at;
            int bestEdges = ranked[at].matchedEdges;
            int worstEdges = ranked[at].matchedEdges;
            while (end < ranked.length && ranked[end].depth == depth) {
                if (ranked[end].matchedEdges > bestEdges) bestEdges = ranked[end].matchedEdges;
                if (ranked[end].matchedEdges < worstEdges) worstEdges = ranked[end].matchedEdges;
                end++;
            }
            System.out.println("  " + pad(depth + " pieces", 12) + pad((end - at) + " roots", 12)
                + "edges " + worstEdges + ".." + bestEdges);
            at = end;
        }
    }
}
