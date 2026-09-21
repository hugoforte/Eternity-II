package core;

/**
 * What a screened root is, and what it is not.
 *
 * A root is only useful if it is a legal board fragment and if the engine
 * actually starts there, so both are asserted against something other than
 * {@link RootScreen} itself:
 *
 *   - legality is judged by {@link Validator}, which knows nothing about how
 *     roots are built, and by {@link ScanSolver}'s own fixed-placement check;
 *   - the count is checked against a brute-force sweep of every assignment of
 *     distinct pieces to the block on a small generated instance, the same way
 *     {@link CrossValidationTest} checks the engines against {@link RefSolver};
 *   - the corner is checked against {@link FillOrder} rather than assumed,
 *     because screening a corner the search fills last would measure nothing.
 *
 * Ranking is deliberately not asserted to be *good* here.  Whether a probe
 * predicts anything is a measurement, not a unit test; what is asserted is
 * that the ranking is reproducible and correctly ordered.
 */
public final class RootScreenTest {

    /** Enumerating the real puzzle's roots costs about a tenth of a second; do it once. */
    private static RootScreen.Catalogue e2Sample;

    public static void run() {
        T.section("RootScreen: the corner the search starts from");
        itScreensTheCornerTheFillOrderReachesFirst();
        itRefusesABlockThatDoesNotFitTheBoard();
        T.endSection();

        T.section("RootScreen: enumerating legal corners");
        itCountsExactlyTheLegalCorners();
        itOnlyProducesCornersTheValidatorAccepts();
        itNeverSpendsAPieceTheInstanceHasAlreadyFixed();
        itRefusesToEnumerateOverAFixedPlacement();
        itSamplesTheSameRootsForTheSameSeed();
        itSamplesDifferentRootsForDifferentSeeds();
        T.endSection();

        T.section("RootScreen: probing and ranking");
        itPinsTheRootIntoTheBoardItProbes();
        itLeavesThePuzzleAloneForTheEmptyRoot();
        itKeepsTheProbeWithinItsBudget();
        itProbesReproducibly();
        itRanksTheBestProbeFirst();
        T.endSection();
    }

    // ------------------------------------------------------------- the corner

    private static void itScreensTheCornerTheFillOrderReachesFirst() {
        int[] banded = FillOrder.bandedScan(16);
        T.eq("the banded order starts at the top-left corner", 0, banded[0]);
        T.eq("and so does the engine built on it", 0,
             RootScreen.firstFilledCell(Instance.eternity2(), new SolverConfig()));

        int[] block = RootScreen.cornerCells(16, 3);
        T.eqIntArray("a 3x3 root covers the nine cells of that corner",
                     new int[] { 0, 1, 2, 16, 17, 18, 32, 33, 34 }, block);
        T.eq("and its first cell is the one the order reaches first", banded[0], block[0]);
    }

    private static void itRefusesABlockThatDoesNotFitTheBoard() {
        boolean threwOnZero = false;
        try { RootScreen.cornerCells(16, 0); } catch (IllegalArgumentException e) { threwOnZero = true; }
        T.threw("a block of no cells is refused", threwOnZero);

        boolean threwOnOversize = false;
        try { RootScreen.cornerCells(16, 17); } catch (IllegalArgumentException e) { threwOnOversize = true; }
        T.threw("a block wider than the board is refused", threwOnOversize);
    }

    // --------------------------------------------------------- what is legal

    /**
     * Every legal corner and no others, counted independently: try every way
     * of putting distinct pieces in the block and keep the ones the Validator
     * accepts with no grey side facing inwards.
     */
    private static void itCountsExactlyTheLegalCorners() {
        Instance small = Generator.generate(3, 2, 20260920L);
        RootScreen.Catalogue cat =
            RootScreen.enumerate(small, new SolverConfig(), 2, 8, 1L);
        T.eq("a 3x3 board offers as many 2x2 corners as brute force finds",
             bruteForceCount(small, 2), cat.total);
        T.check("and the sample is no larger than the space", cat.sample.length <= cat.total);
    }

    private static void itOnlyProducesCornersTheValidatorAccepts() {
        Instance base = Instance.eternity2();
        RootScreen.Catalogue cat = e2Sample();
        T.check("the real puzzle has legal 3x3 corners to screen", cat.total > 0);

        int bad = 0;
        for (int i = 0; i < cat.sample.length; i++) {
            int[] board = cat.sample[i].board(base);
            if (Validator.validatePartial(base, board, false, 0) != null) bad++;
            if (greyFacesInwards(base, board)) bad++;
        }
        T.eq("every sampled corner is a legal partial board", 0, bad);

        // The engine has its own opinion about a fixed placement; it must agree.
        int rejected = 0;
        for (int i = 0; i < cat.sample.length; i++) {
            try {
                new ScanSolver(RootScreen.instanceFor(base, cat.sample[i]));
            } catch (RuntimeException e) {
                rejected++;
            }
        }
        T.eq("and the engine accepts every one of them as fixed placements", 0, rejected);
    }

    private static void itNeverSpendsAPieceTheInstanceHasAlreadyFixed() {
        RootScreen.Catalogue cat = e2Sample();
        int clashes = 0;
        for (int i = 0; i < cat.sample.length; i++) {
            RootScreen.Root root = cat.sample[i];
            for (int k = 0; k < root.piece.length; k++) {
                if (root.piece[k] == 138) clashes++;
            }
        }
        T.eq("no root spends the mandatory hint piece", 0, clashes);
    }

    private static void itRefusesToEnumerateOverAFixedPlacement() {
        // The generator fixes the centre cell, which a 3x3 corner of a 5x5
        // board covers; screening it would silently drop the hint.
        Instance withCentreFixed = Generator.generate(5, 3, 7L, true, true);
        boolean threw = false;
        try {
            RootScreen.enumerate(withCentreFixed, new SolverConfig(), 3, 4, 1L);
        } catch (IllegalStateException e) {
            threw = true;
        }
        T.threw("a block that swallows an existing fixed placement is refused", threw);
    }

    private static void itSamplesTheSameRootsForTheSameSeed() {
        RootScreen.Catalogue a =
            RootScreen.enumerate(Instance.eternity2(), new SolverConfig(), 3, 12, 99L);
        RootScreen.Catalogue b =
            RootScreen.enumerate(Instance.eternity2(), new SolverConfig(), 3, 12, 99L);
        T.eq("the same seed draws the same number of roots", a.sample.length, b.sample.length);
        T.eq("and the same roots", signature(a), signature(b));
    }

    private static void itSamplesDifferentRootsForDifferentSeeds() {
        RootScreen.Catalogue a =
            RootScreen.enumerate(Instance.eternity2(), new SolverConfig(), 3, 12, 99L);
        RootScreen.Catalogue c =
            RootScreen.enumerate(Instance.eternity2(), new SolverConfig(), 3, 12, 12345L);
        T.eq("a different seed does not change how many roots exist", a.total, c.total);
        T.check("but it draws a different sample", !signature(a).equals(signature(c)));
    }

    // ------------------------------------------------------------- the probe

    private static void itPinsTheRootIntoTheBoardItProbes() {
        Instance base = Instance.eternity2();
        RootScreen.Root root = e2Sample().sample[0];
        Instance rooted = RootScreen.instanceFor(base, root);
        T.eq("the rooted instance carries the base placements and the root",
             base.fixedCell.length + root.cell.length, rooted.fixedCell.length);

        RootScreen.Probe probe = RootScreen.probe(base, new SolverConfig(), root, 200000L);
        T.check("the probe reached past the root", probe.depth > root.cell.length);

        int wrong = 0;
        for (int k = 0; k < root.cell.length; k++) {
            int v = probe.board[root.cell[k]];
            if (v < 0 || (v >>> 2) != root.piece[k]
                    || base.variantSides(v >>> 2, v & 3)
                       != base.variantSides(root.piece[k], root.rot[k])) {
                wrong++;
            }
        }
        T.eq("and every cell of the root still holds the root's piece", 0, wrong);
    }

    /**
     * The control arm searches from the empty root, so it has to be the plain
     * puzzle and not merely something near it.
     */
    private static void itLeavesThePuzzleAloneForTheEmptyRoot() {
        Instance base = Instance.eternity2();
        Instance same = RootScreen.instanceFor(base, RootScreen.Root.none());
        T.eqIntArray("the empty root fixes exactly what the puzzle fixed",
                     base.fixedCell, same.fixedCell);
        T.eqIntArray("with the same pieces", base.fixedPiece, same.fixedPiece);
        T.eqIntArray("in the same rotations", base.fixedRot, same.fixedRot);

        RootScreen.Probe control = RootScreen.probe(base, new SolverConfig(),
                                                    RootScreen.Root.none(), 300000L);
        ScanSolver direct = new ScanSolver(base, new SolverConfig());
        direct.maxNodes = 300000L;
        direct.solve();
        T.eq("and searching it reaches the depth the engine reaches unaided",
             direct.bestPlaced, control.depth);
        T.eq("having spent the same nodes", direct.nodes, control.nodes);
    }

    private static void itKeepsTheProbeWithinItsBudget() {
        RootScreen.Probe probe =
            RootScreen.probe(Instance.eternity2(), new SolverConfig(), e2Sample().sample[1], 50000L);
        T.check("a probe never overruns its node budget", probe.nodes <= 50000L,
                "spent " + probe.nodes);

        boolean threw = false;
        try {
            RootScreen.probe(Instance.eternity2(), new SolverConfig(), e2Sample().sample[1], 0L);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        T.threw("a probe with no budget at all is refused", threw);
    }

    private static void itProbesReproducibly() {
        Instance base = Instance.eternity2();
        RootScreen.Root root = e2Sample().sample[2];
        RootScreen.Probe first = RootScreen.probe(base, new SolverConfig(), root, 150000L);
        RootScreen.Probe again = RootScreen.probe(base, new SolverConfig(), root, 150000L);
        T.eq("the same root probes to the same depth", first.depth, again.depth);
        T.eq("with the same score", first.matchedEdges, again.matchedEdges);
        T.eq("having spent the same nodes", first.nodes, again.nodes);
    }

    private static void itRanksTheBestProbeFirst() {
        RootScreen.Catalogue cat = e2Sample();
        RootScreen.Root[] roots = new RootScreen.Root[6];
        for (int i = 0; i < roots.length; i++) roots[i] = cat.sample[i];

        RootScreen.Probe[] ranked =
            RootScreen.screen(Instance.eternity2(), new SolverConfig(), roots, 120000L);
        T.eq("screening reports one probe per root", roots.length, ranked.length);

        int outOfOrder = 0;
        for (int i = 1; i < ranked.length; i++) {
            RootScreen.Probe prev = ranked[i - 1], here = ranked[i];
            boolean ordered = prev.depth > here.depth
                           || (prev.depth == here.depth && prev.matchedEdges >= here.matchedEdges);
            if (!ordered) outOfOrder++;
        }
        T.eq("and ranks them deepest first, best-scoring within a depth", 0, outOfOrder);
    }

    // ------------------------------------------------------------------ tools

    private static RootScreen.Catalogue e2Sample() {
        if (e2Sample == null) {
            e2Sample = RootScreen.enumerate(Instance.eternity2(), new SolverConfig(), 3, 24, 2024L);
        }
        return e2Sample;
    }

    /** A stable description of a sample, for comparing two of them. */
    private static String signature(RootScreen.Catalogue cat) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cat.sample.length; i++) sb.append(cat.sample[i]).append('|');
        return sb.toString();
    }

    /**
     * Count the legal corners the slow way: every assignment of distinct
     * pieces and rotations to the block, kept when the Validator is happy and
     * no grey side faces another cell.
     */
    private static long bruteForceCount(Instance inst, int size) {
        int[] block = RootScreen.cornerCells(inst.n, size);
        int[] board = new int[inst.cells];
        for (int cell = 0; cell < inst.cells; cell++) board[cell] = -1;
        return bruteForce(inst, block, board, new boolean[inst.numPieces], 0);
    }

    private static long bruteForce(Instance inst, int[] block, int[] board,
                                   boolean[] used, int at) {
        if (at == block.length) {
            if (Validator.validatePartial(inst, board, false, 0) != null) return 0;
            return greyFacesInwards(inst, board) ? 0 : 1;
        }
        long found = 0;
        for (int id = 0; id < inst.numPieces; id++) {
            if (used[id]) continue;
            used[id] = true;
            for (int r = 0; r < 4; r++) {
                if (!inst.isCanonicalRotation(id, r)) continue;
                board[block[at]] = (id << 2) | r;
                found += bruteForce(inst, block, board, used, at + 1);
            }
            board[block[at]] = -1;
            used[id] = false;
        }
        return found;
    }

    /**
     * True if any placed piece shows grey on a side that faces another cell.
     * The piece set has exactly one grey side per border side, so a grey side
     * spent inland is a grey side the border can never get back.
     */
    private static boolean greyFacesInwards(Instance inst, int[] board) {
        int n = inst.n;
        for (int cell = 0; cell < inst.cells; cell++) {
            int v = board[cell];
            if (v < 0) continue;
            int p = inst.variantSides(v >>> 2, v & 3);
            int r = cell / n, c = cell - r * n;
            if (c != 0 && Sides.left(p) == Sides.GREY) return true;
            if (r != 0 && Sides.top(p) == Sides.GREY) return true;
            if (c != n - 1 && Sides.right(p) == Sides.GREY) return true;
            if (r != n - 1 && Sides.bottom(p) == Sides.GREY) return true;
        }
        return false;
    }
}
