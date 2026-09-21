package core;

/**
 * Edge slipping: {@link ScanSolver} placing a piece that deliberately
 * mismatches one of its two known sides, so the search can carry on past a
 * point where nothing fits.
 *
 * Slipping changes what a finished board means, which makes the usual oracles
 * the wrong ones.  {@link CrossValidationTest} compares solution SETS against
 * {@link RefSolver}, and that equivalence is broken by design here, so it
 * stays where it is, exercising the default-off engine.  What is checked
 * instead is the four rules, read back off the board the engine produced:
 *
 *   - at most one break per placed piece;
 *   - never a break involving a border colour;
 *   - the cumulative ceiling by depth;
 *   - a board with any break is never a solution.
 *
 * Every reading below walks the recorded placement order and counts the
 * mismatches itself; the solver's own {@code bestBreaks} is then compared
 * against that count rather than trusted.
 */
public final class EdgeSlippingTest {

    /** Blackwood's published ceiling, as depths at which each break unlocks. */
    private static final int[] BLACKWOOD =
        { 201, 206, 211, 216, 221, 225, 229, 233, 237, 239 };
    /** Verhaard's, arrived at independently, for the same 256-cell board. */
    private static final int[] VERHAARD =
        { 193, 202, 209, 214, 218, 222, 226, 229, 232, 235, 238, 240 };

    public static void run() {
        T.section("EdgeSlippingTest: bounded mismatches in ScanSolver");

        itIsOffUnlessAskedFor();
        itReproducesThePublishedSchedules();
        itObeysEveryRuleOnTheRealPuzzle();
        itNeverReportsASlippedBoardAsSolved();
        itDoesNotChangeHowManySolutionsExist();
        itReachesFurtherThanTheExactSearch();
        itIsRepeatable();
        itReportsTheDeepestErrorFreePrefix();

        T.endSection();
    }

    // -------------------------------------------------- error-free prefix

    /**
     * Tiles placed is not a score once slipping is on -- breaks buy depth, so
     * a board can be filled by paying for it.  The deepest error-free prefix
     * is the companion measure, and its two ends are what pin it down: with no
     * schedule it is the whole board, and with one it cannot end before the
     * schedule's first unlock, because nothing may break above that depth.
     */
    private static void itReportsTheDeepestErrorFreePrefix() {
        SolverConfig exact = new SolverConfig();
        ScanSolver clean = new ScanSolver(Instance.eternity2(), exact);
        clean.maxNodes = 500000L;
        clean.solve();
        T.eq("with no schedule every tile placed is error-free",
             clean.bestPlaced, clean.bestPerfectTiles);

        SolverConfig cfg = new SolverConfig();
        cfg.slipSchedule = SolverConfig.SLIP_VERHAARD;
        ScanSolver s = new ScanSolver(Instance.eternity2(), cfg);
        s.maxNodes = 5000000L;
        s.solve();

        int[] ceilings = s.breakCeilings();
        int firstUnlock = ceilings.length;
        for (int d = 0; d < ceilings.length; d++) {
            if (ceilings[d] > 0) { firstUnlock = d; break; }
        }

        T.check("a slipped board's error-free prefix stops short of its depth",
                s.bestBreaks == 0
                    ? s.bestPerfectTiles == s.bestPlaced
                    : s.bestPerfectTiles < s.bestPlaced,
                "placed=" + s.bestPlaced + " perfect=" + s.bestPerfectTiles
                    + " breaks=" + s.bestBreaks);
        T.check("and it cannot end before the schedule's first unlock",
                s.bestBreaks == 0 || s.bestPerfectTiles >= firstUnlock,
                "perfect=" + s.bestPerfectTiles + " firstUnlock=" + firstUnlock);

        // The prefix is a real board, so it has to validate as one with no
        // break allowance at all.
        int[] prefix = new int[Instance.eternity2().cells];
        for (int cell = 0; cell < prefix.length; cell++) prefix[cell] = -1;
        for (int d = 0; d < s.bestPerfectTiles; d++) {
            prefix[s.bestOrderCells()[d]] = s.bestOrderVariants()[d];
        }
        T.isNull("the prefix validates with no break allowance",
                 Validator.validatePartial(Instance.eternity2(), prefix, false, 0));
    }

    // ------------------------------------------------------------------- off

    private static void itIsOffUnlessAskedFor() {
        Instance inst = Instance.eternity2();
        ScanSolver s = new ScanSolver(inst);
        T.check("slipping is off unless a schedule is chosen", !s.slipping());
        T.eq("the default config asks for no schedule",
             SolverConfig.SLIP_NONE, new SolverConfig().slipSchedule);

        int[] ceilings = s.breakCeilings();
        int highest = 0;
        for (int d = 0; d < ceilings.length; d++) {
            if (ceilings[d] > highest) highest = ceilings[d];
        }
        T.eq("with no schedule no depth may break an edge", 0, highest);
        T.eq("the candidate index then holds only perfect candidates",
             s.candidateEntryCount(), s.perfectEntryCount());

        s.maxNodes = 500000L;
        s.solve();
        T.eq("and the board it reaches has no broken edges", 0, s.bestBreaks);
        T.isNull("so it validates with no allowance at all",
                 Validator.validatePartial(inst, s.bestBoard, false, 0));
    }

    // -------------------------------------------------------------- schedule

    private static void itReproducesThePublishedSchedules() {
        SolverConfig blackwood = new SolverConfig();
        blackwood.slipSchedule = SolverConfig.SLIP_BLACKWOOD;
        int[] fromEngine = unlockDepths(new ScanSolver(Instance.eternity2(), blackwood));
        T.eqIntArray("Blackwood's ceiling is reproduced verbatim at 16x16",
                     BLACKWOOD, fromEngine);
        T.eq("it has exactly ten entries", 10, fromEngine.length);
        T.eq("and the last of them is 239", 239, fromEngine[fromEngine.length - 1]);

        SolverConfig verhaard = new SolverConfig();
        verhaard.slipSchedule = SolverConfig.SLIP_VERHAARD;
        int[] other = unlockDepths(new ScanSolver(Instance.eternity2(), verhaard));
        T.eqIntArray("Verhaard's ceiling is reproduced verbatim at 16x16",
                     VERHAARD, other);

        // The schedules only ever loosen, so a board can never be asked to
        // give a break back once it has spent one.
        int[] ceilings = new ScanSolver(Instance.eternity2(), blackwood).breakCeilings();
        boolean monotone = true;
        for (int d = 1; d < ceilings.length; d++) {
            if (ceilings[d] < ceilings[d - 1]) monotone = false;
        }
        T.check("the ceiling never falls as the board fills", monotone);
        T.eq("the first two thirds of the board must be perfect", 0, ceilings[200]);
        T.eq("and the whole board may carry ten breaks", 10, ceilings[256]);

        // Every other board size gets the same shape, scaled.
        SolverConfig small = blackwood.copy();
        ScanSolver five = new ScanSolver(Generator.generate(5, 4, 77L, true, false), small);
        T.check("a smaller board still gets a schedule", five.slipping());
        int[] smallCeilings = five.breakCeilings();
        T.eq("scaled to 25 cells the first 19 placements must still be perfect",
             0, smallCeilings[18]);
        T.eq("and the finished board may carry the same ten breaks",
             10, smallCeilings[25]);
    }

    /** Depth at which each successive break first becomes permissible. */
    private static int[] unlockDepths(ScanSolver s) {
        int[] ceilings = s.breakCeilings();
        int[] out = new int[ceilings[ceilings.length - 1]];
        int found = 0;
        for (int d = 0; d < ceilings.length; d++) {
            while (found < ceilings[d]) { out[found] = d; found++; }
        }
        return out;
    }

    // ----------------------------------------------------------- the rules

    private static void itObeysEveryRuleOnTheRealPuzzle() {
        Instance inst = Instance.eternity2();
        SolverConfig cfg = new SolverConfig();
        cfg.slipSchedule = SolverConfig.SLIP_BLACKWOOD;
        ScanSolver s = new ScanSolver(inst, cfg);
        s.maxNodes = 20000000L;
        s.solve();

        T.check("the search really did break some edges on Eternity II",
                s.bestBreaks > 0, "breaks=" + s.bestBreaks);

        Reading r = read(inst, s);
        T.check("no placement breaks more than one of its two known sides",
                r.mostInOnePiece <= 1,
                "worst placement broke " + r.mostInOnePiece);
        T.eq("no break involves a border colour", 0, r.greyBreaks);
        T.eq("the schedule is never exceeded", -1, r.ceilingBreached);
        T.eq("the solver's break count is what the board actually shows",
             r.breaks, s.bestBreaks);
        T.eq("score is the edges the fill order joined up, less the breaks",
             r.checks - r.breaks, s.bestMatchedEdges);

        T.isNull("the board is legal in every other respect",
                 Validator.validatePartial(inst, s.bestBoard, false, s.bestBreaks));
        T.notNull("and the allowance is a real check, not a waiver",
                  Validator.validatePartial(inst, s.bestBoard, false, s.bestBreaks - 1));

        System.out.println("         Eternity II, 20M nodes, blackwood: placed="
            + s.bestPlaced + "/256 edges=" + s.bestMatchedEdges + "/480"
            + " breaks=" + s.bestBreaks + " nodes/s="
            + (s.elapsedMs() == 0 ? 0 : s.nodes * 1000L / s.elapsedMs()));
    }

    // ------------------------------------------------------ never "solved"

    private static void itNeverReportsASlippedBoardAsSolved() {
        Instance inst = unsatisfiable();

        ScanSolver exact = new ScanSolver(inst);
        exact.stopAtFirstSolution = false;
        exact.maxNodes = 20000000L;
        long exactCount = exact.solve();
        T.eq("the instance has no perfect board at all", 0, exactCount);
        T.check("and the exact search proved it rather than giving up",
                !exact.aborted);
        T.check("the exact search cannot even fill the board",
                exact.bestPlaced < inst.cells, "placed=" + exact.bestPlaced);

        SolverConfig cfg = new SolverConfig();
        cfg.slipSchedule = SolverConfig.SLIP_BLACKWOOD;
        ScanSolver slip = new ScanSolver(inst, cfg);
        slip.stopAtFirstSolution = false;
        slip.maxNodes = 20000000L;
        long slipCount = slip.solve();

        T.eq("slipping fills the board the exact search could not",
             inst.cells, slip.bestPlaced);
        T.check("and it spent breaks doing so", slip.bestBreaks > 0,
                "breaks=" + slip.bestBreaks);
        T.eq("but that board is not counted as a solution", 0, slipCount);
        T.isNull("and no solution board is offered", slip.solutionBoard);
        T.notNull("a full slipped board fails validateComplete",
                  Validator.validateComplete(inst, slip.bestBoard));

        int total = Validator.internalEdgeTotal(inst);
        T.eq("a full board with k breaks scores exactly total - k",
             total - slip.bestBreaks, Validator.matchedEdges(inst, slip.bestBoard));
        T.isNull("every piece is still used exactly once, and the border is legal",
                 Validator.validatePartial(inst, slip.bestBoard, true, slip.bestBreaks));

        Reading r = read(inst, slip);
        T.eq("the board shows exactly the breaks the solver claims",
             r.breaks, slip.bestBreaks);
        T.check("still at most one break per placed piece", r.mostInOnePiece <= 1,
                "worst placement broke " + r.mostInOnePiece);
    }

    /**
     * A 4x4 that no perfect board can satisfy, made by recolouring one side of
     * a generated instance.  Every piece stays placeable -- an instance broken
     * by an unusable piece would be unsatisfiable for a slipped search too,
     * and this test needs one that a slipped search can finish.
     */
    private static Instance unsatisfiable() {
        Instance base = Generator.generate(4, 3, 500L, true, false);
        int[][] pieces = base.piecesCopy();
        pieces[0][0] = (pieces[0][0] % 2) + 1;
        return new Instance(4, pieces, null, null, null);
    }

    // ------------------------------------------------- solution count intact

    private static void itDoesNotChangeHowManySolutionsExist() {
        // Breaking an edge is allowed to finish a board, never to claim one,
        // so however generous the schedule the set of perfect boards is the
        // same set the exact engine enumerates.
        int[] ns      = { 4, 4, 5 };
        int[] colours = { 3, 4, 5 };
        long[] seeds  = { 12L, 14L, 22L };
        boolean[] fixed = { false, false, true };

        for (int i = 0; i < ns.length; i++) {
            Instance inst = Generator.generate(ns[i], colours[i], seeds[i], true, fixed[i]);
            String label = ns[i] + "x" + ns[i] + " colours=" + colours[i];
            long baseline = countAll(inst, SolverConfig.SLIP_NONE);
            T.check("the exact engine enumerates something (" + label + ")",
                    baseline >= 1, "baseline=" + baseline);
            T.eq("slipSchedule=blackwood finds the same solutions (" + label + ")",
                 baseline, countAll(inst, SolverConfig.SLIP_BLACKWOOD));
            T.eq("slipSchedule=verhaard finds the same solutions (" + label + ")",
                 baseline, countAll(inst, SolverConfig.SLIP_VERHAARD));
        }
    }

    private static long countAll(Instance inst, int slipSchedule) {
        SolverConfig cfg = new SolverConfig();
        cfg.slipSchedule = slipSchedule;
        ScanSolver s = new ScanSolver(inst, cfg);
        s.stopAtFirstSolution = false;
        s.maxNodes = 60000000L;
        long found = s.solve();
        if (s.aborted) return -1;
        return found;
    }

    // -------------------------------------------------------------- it pays

    private static void itReachesFurtherThanTheExactSearch() {
        Instance inst = Instance.eternity2();

        ScanSolver exact = new ScanSolver(inst);
        exact.maxNodes = 20000000L;
        exact.solve();

        SolverConfig cfg = new SolverConfig();
        cfg.slipSchedule = SolverConfig.SLIP_BLACKWOOD;
        ScanSolver slip = new ScanSolver(inst, cfg);
        slip.maxNodes = 20000000L;
        slip.solve();

        T.check("at an equal node budget slipping places more pieces",
                slip.bestPlaced > exact.bestPlaced,
                "exact=" + exact.bestPlaced + " slipped=" + slip.bestPlaced);
        T.check("and scores more matched edges, breaks included in the price",
                slip.bestMatchedEdges > exact.bestMatchedEdges,
                "exact=" + exact.bestMatchedEdges + " slipped=" + slip.bestMatchedEdges);
    }

    // --------------------------------------------------------- determinism

    private static void itIsRepeatable() {
        SolverConfig cfg = new SolverConfig();
        cfg.slipSchedule = SolverConfig.SLIP_BLACKWOOD;
        cfg.nodeBudget = 2000000L;
        ScanSolver a = new ScanSolver(Instance.eternity2(), cfg);
        a.solve();
        ScanSolver b = new ScanSolver(Instance.eternity2(), cfg.copy());
        b.solve();

        T.eq("a slipped run expands the same nodes every time", a.nodes, b.nodes);
        T.eq("and breaks the same number of edges", a.bestBreaks, b.bestBreaks);
        T.eqIntArray("and produces the same board", a.bestBoard, b.bestBoard);
    }

    // ------------------------------------------------------------- reading

    /** What a recorded board says about itself, counted without the solver. */
    private static final class Reading {
        /** Adjacent pairs of placed cells the fill order joined up. */
        int checks;
        /** How many of those mismatch. */
        int breaks;
        /** The most mismatches any single placement introduced. */
        int mostInOnePiece;
        /** Breaks where either colour is the border colour; must be none. */
        int greyBreaks;
        /** Depth at which the running break count passed its ceiling, or -1. */
        int ceilingBreached = -1;
    }

    /**
     * Replay the recorded placement order and count what actually happened at
     * each step.  Each cell arrives with its north and west neighbours already
     * down, so these are exactly the edges the search had to judge.
     */
    private static Reading read(Instance inst, ScanSolver s) {
        int n = inst.n;
        int[] ceilings = s.breakCeilings();
        int[] board = new int[inst.cells];
        for (int cell = 0; cell < inst.cells; cell++) board[cell] = -1;

        Reading r = new Reading();
        for (int d = 0; d < s.bestOrderLength; d++) {
            int cell = s.bestOrderCells[d];
            int v = s.bestOrderVariants[d];
            int p = inst.variantSides(v >>> 2, v & 3);
            int row = cell / n, col = cell - row * n;
            int here = 0;
            if (col > 0 && board[cell - 1] >= 0) {
                int q = inst.variantSides(board[cell - 1] >>> 2, board[cell - 1] & 3);
                r.checks++;
                if (Sides.right(q) != Sides.left(p)) {
                    here++;
                    if (Sides.right(q) == Sides.GREY || Sides.left(p) == Sides.GREY) {
                        r.greyBreaks++;
                    }
                }
            }
            if (row > 0 && board[cell - n] >= 0) {
                int q = inst.variantSides(board[cell - n] >>> 2, board[cell - n] & 3);
                r.checks++;
                if (Sides.bottom(q) != Sides.top(p)) {
                    here++;
                    if (Sides.bottom(q) == Sides.GREY || Sides.top(p) == Sides.GREY) {
                        r.greyBreaks++;
                    }
                }
            }
            r.breaks += here;
            if (here > r.mostInOnePiece) r.mostInOnePiece = here;
            if (r.ceilingBreached < 0 && r.breaks > ceilings[d]) r.ceilingBreached = d;
            board[cell] = v;
        }
        return r;
    }
}
