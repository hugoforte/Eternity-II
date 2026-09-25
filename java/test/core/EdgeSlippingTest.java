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
        itLetsTheTailSpendMore();
        itKeepsTheBestScoringBoardToo();

        T.endSection();
    }

    // --------------------------------------------------- best-scoring board

    /**
     * The record board is the deepest one, and edges only break ties at equal
     * depth.  A shallower board that spent far fewer breaks can outscore it,
     * so the search keeps a second record: the best-scoring node it visited.
     * Its score is computed on the hot path from the fill order and the break
     * count, so the Validator has to agree with it on the board itself.
     */
    private static void itKeepsTheBestScoringBoardToo() {
        Instance inst = Instance.eternity2();
        SolverConfig exact = new SolverConfig();
        ScanSolver clean = new ScanSolver(inst, exact);
        clean.maxNodes = 500000L;
        clean.solve();
        T.eq("with no breaks the deepest board is also the best-scoring one",
             clean.bestMatchedEdges, clean.bestScore);
        T.eq("and it is the same depth", clean.bestPlaced, clean.bestScorePlaced);

        SolverConfig cfg = new SolverConfig();
        cfg.slipSchedule = SolverConfig.SLIP_VERHAARD;
        ScanSolver s = new ScanSolver(inst, cfg);
        s.maxNodes = 20000000L;
        s.solve();

        T.check("the best-scoring board scores at least what the deepest one does",
                s.bestScore >= s.bestMatchedEdges,
                "score=" + s.bestScore + " deepest=" + s.bestMatchedEdges);
        T.eq("its score is the Validator's count of its own matched edges",
             Validator.matchedEdges(inst, s.bestScoreBoard), s.bestScore);
        T.eq("its breaks are exactly the edges the Validator finds mismatched",
             s.bestScoreBreaks,
             Validator.mismatchedEdges(inst, s.bestScoreBoard).length / 2);
        int placed = 0;
        for (int cell = 0; cell < inst.cells; cell++) {
            if (s.bestScoreBoard[cell] >= 0) placed++;
        }
        T.eq("and its depth is the number of pieces on it", placed, s.bestScorePlaced);
        T.isNull("and it is legal for the breaks it spent",
                 Validator.validatePartial(inst, s.bestScoreBoard, false,
                                           s.bestScoreBreaks));
    }

    // ------------------------------------------------------- tail allowance

    /**
     * The published schedules are cumulative ceilings, so a board that cannot
     * finish its last cells perfectly cannot finish at all -- and past the
     * depth where every cell joins two edges and can break at most one, a
     * further placement is worth net +1 edge or better.  The tail allowance is
     * the dial that lets those cells break; this pins its shape and the one
     * result it was built for.
     */
    private static void itLetsTheTailSpendMore() {
        SolverConfig off = new SolverConfig();
        off.slipSchedule = SolverConfig.SLIP_VERHAARD;
        int[] plain = new ScanSolver(Instance.eternity2(), off).breakCeilings();

        SolverConfig on = new SolverConfig();
        on.slipSchedule = SolverConfig.SLIP_VERHAARD;
        on.tailFromDepth = 244;
        on.tailBreakBonus = 4;
        int[] raised = new ScanSolver(Instance.eternity2(), on).breakCeilings();

        T.eq("below the tail depth the ceiling is untouched", plain[243], raised[243]);
        T.eq("at the tail depth it is four higher", plain[244] + 4, raised[244]);
        T.eq("and it stays four higher to the last cell", plain[256] + 4, raised[256]);

        boolean monotone = true;
        for (int d = 1; d < raised.length; d++) {
            if (raised[d] < raised[d - 1]) monotone = false;
        }
        T.check("the raised ceiling still never falls", monotone);

        SolverConfig zero = new SolverConfig();
        zero.slipSchedule = SolverConfig.SLIP_VERHAARD;
        zero.tailFromDepth = 244;
        zero.tailBreakBonus = 0;
        T.eqIntArray("a zero bonus leaves the schedule exactly as published",
                     plain, new ScanSolver(Instance.eternity2(), zero).breakCeilings());

        // The result the dial was built for: the engine could not finish a
        // board under a twelve-break ceiling, and finishing is worth more than
        // the breaks it costs.  A hole costs two edges, a break costs one.
        SolverConfig full = new SolverConfig();
        full.engine = SolverConfig.ENGINE_SCAN;
        full.slipSchedule = SolverConfig.SLIP_VERHAARD;
        full.quotaColours = "14,22,5";
        full.quotaSchedule = SolverConfig.QUOTA_BLACKWOOD;
        full.tailFromDepth = 244;
        full.tailBreakBonus = 6;
        ScanSolver s = new ScanSolver(Instance.eternity2(), full);
        s.maxNodes = 100000000L;
        s.solve();

        T.check("a hundred million nodes now finishes the board",
                s.bestPlaced == 256, "placed=" + s.bestPlaced);
        T.check("and beats the best score reachable under the plain ceiling",
                s.bestMatchedEdges > 456,
                "score=" + s.bestMatchedEdges + " breaks=" + s.bestBreaks);
        T.eq("the score is exactly 480 less the edges it broke",
             480 - s.bestBreaks, s.bestMatchedEdges);
        T.isNull("and the finished board is legal for the breaks it spent",
                 Validator.validatePartial(Instance.eternity2(), s.bestBoard,
                                           false, s.bestBreaks));
        T.notNull("but it is not a solution",
                  Validator.validateComplete(Instance.eternity2(), s.bestBoard));
        T.eq("the validator finds exactly those broken edges",
             s.bestBreaks,
             Validator.mismatchedEdges(Instance.eternity2(), s.bestBoard).length / 2);

        // A solved board has every one of its cells error-free, so the running
        // maximum has to survive the completion return rather than stopping one
        // short of it.
        Instance small = Generator.generate(4, 3, 1234L, true, false);
        ScanSolver solved = new ScanSolver(small, new SolverConfig());
        solved.setStopAtFirstSolution(true);
        solved.maxNodes = 2000000L;
        solved.solve();
        T.notNull("the 4x4 generated instance is solved", solved.solutionBoard());
        T.eq("and every cell of a solved board counts as error-free",
             small.cells, solved.deepestErrorFree);

        // A tail depth at or past the last cell cannot let anything slip, so
        // the engine must not build slipped tables for it.
        SolverConfig inert = new SolverConfig();
        inert.slipSchedule = SolverConfig.SLIP_NONE;
        inert.tailFromDepth = 256;
        inert.tailBreakBonus = 4;
        ScanSolver none = new ScanSolver(Instance.eternity2(), inert);
        T.eq("a tail starting past the last cell leaves the index perfect",
             none.candidateEntryCount(), none.perfectEntryCount());

        // The published depths scale with the board, and so must this one, or
        // the dial is silently dead on every size but 16x16.
        SolverConfig scaled = new SolverConfig();
        scaled.slipSchedule = SolverConfig.SLIP_NONE;
        scaled.tailFromDepth = 128;
        scaled.tailBreakBonus = 1;
        int[] ceil = new ScanSolver(small, scaled).breakCeilings();
        T.eq("the tail depth scales to the board, so 128 of 256 is half of 16",
             0, ceil[7]);
        T.eq("and the allowance is live from there on", 1, ceil[8]);
    }

    // -------------------------------------------------- error-free prefix

    /**
     * Tiles placed is not a score once slipping is on -- breaks buy depth, so
     * a board can be filled by paying for it.  Two different measures answer
     * "how far without a mistake", and conflating them is easy: the record
     * board's own error-free prefix, and the deepest the search ever got with
     * nothing mismatched.  For a slipped arm they are far apart, because the
     * board on record is the deepest one and it carries breaks.
     */
    private static void itReportsTheDeepestErrorFreePrefix() {
        SolverConfig exact = new SolverConfig();
        ScanSolver clean = new ScanSolver(Instance.eternity2(), exact);
        clean.maxNodes = 500000L;
        clean.solve();
        T.eq("with no schedule every tile placed is error-free",
             clean.bestPlaced, clean.bestPerfectTiles);
        T.eq("and the two measures agree, because the record board has no breaks",
             clean.bestPlaced, clean.deepestErrorFree);

        SolverConfig cfg = new SolverConfig();
        cfg.slipSchedule = SolverConfig.SLIP_VERHAARD;
        ScanSolver s = new ScanSolver(Instance.eternity2(), cfg);
        s.maxNodes = 5000000L;
        s.solve();

        T.check("a slipped board's own error-free prefix stops short of its depth",
                s.bestBreaks == 0
                    ? s.bestPerfectTiles == s.bestPlaced
                    : s.bestPerfectTiles < s.bestPlaced,
                "placed=" + s.bestPlaced + " prefix=" + s.bestPerfectTiles
                    + " breaks=" + s.bestBreaks);

        // The search reached depths the record board cannot testify to, so the
        // running maximum is the larger of the two and the one worth quoting.
        T.check("the search got at least as far error-free as its record board did",
                s.deepestErrorFree >= s.bestPerfectTiles,
                "deepest=" + s.deepestErrorFree + " prefix=" + s.bestPerfectTiles);

        // The exact search is the honest reference for the same budget: a
        // slipped arm tries every perfect candidate before any slipped one, so
        // it cannot reach less far error-free than the exact search does.
        ScanSolver reference = new ScanSolver(Instance.eternity2(), new SolverConfig());
        reference.maxNodes = 5000000L;
        reference.solve();
        T.check("and it is not beaten error-free by the exact search at equal nodes",
                s.deepestErrorFree >= reference.deepestErrorFree,
                "slipped=" + s.deepestErrorFree + " exact=" + reference.deepestErrorFree);

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
