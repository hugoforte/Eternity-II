package core;

/**
 * Behaviour of the fixed-order engine on its own.  What it must agree with
 * {@link MrvSolver} and {@link RefSolver} about lives in
 * {@link CrossValidationTest}; here we check the things only this engine can
 * get wrong -- the order it actually places in, the hint piece, the node
 * budget, and that a second run of the same solver gives the same answer.
 */
public final class ScanSolverTest {

    public static void run() {
        T.section("ScanSolverTest: fixed fill order, two-colour candidate index");

        itFollowsItsFillOrder();
        itSolvesGeneratedInstances();
        itHonoursTheHintPiece();
        itRejectsAnImpossibleFixedPlacement();
        itStopsAtItsNodeBudget();
        itIsRepeatable();
        itRunsOnTheRealPuzzle();

        T.endSection();
    }

    // -------------------------------------------------------------- ordering

    private static void itFollowsItsFillOrder() {
        Instance inst = Generator.generate(5, 4, 77L, true, false);
        ScanSolver s = new ScanSolver(inst);
        s.maxNodes = 200000L;
        s.solve();

        int[] order = s.fillOrder();
        T.isNull("the solver's fill order satisfies the north-and-west invariant",
                 FillOrder.validate(order, inst.n));

        boolean matches = s.bestOrderLength > 0;
        for (int d = 0; d < s.bestOrderLength; d++) {
            if (s.bestOrderCells[d] != order[d]) { matches = false; break; }
        }
        T.check("pieces are placed in exactly the fill order, depth by depth", matches,
                "bestOrderLength=" + s.bestOrderLength);

        SolverConfig rowMajor = new SolverConfig();
        rowMajor.fillOrder = SolverConfig.FILL_ROW_MAJOR;
        ScanSolver plain = new ScanSolver(inst, rowMajor);
        T.eqIntArray("fillOrder=rowMajor gives the plain sweep",
                     FillOrder.rowMajor(inst.n), plain.fillOrder());

        plain.stopAtFirstSolution = false;
        plain.maxNodes = 20000000L;
        long plainCount = plain.solve();
        ScanSolver banded = new ScanSolver(inst);
        banded.stopAtFirstSolution = false;
        banded.maxNodes = 20000000L;
        long bandedCount = banded.solve();
        T.eq("both fill orders enumerate the same number of solutions",
             plainCount, bandedCount);
    }

    // --------------------------------------------------------------- solving

    private static void itSolvesGeneratedInstances() {
        int[] ns      = { 4, 5, 6, 8 };
        int[] colours = { 3, 4, 6, 12 };
        long[] seeds  = { 101L, 102L, 103L, 104L };

        for (int i = 0; i < ns.length; i++) {
            Instance inst = Generator.generate(ns[i], colours[i], seeds[i], true, true);
            String label = ns[i] + "x" + ns[i] + " colours=" + colours[i];

            ScanSolver s = new ScanSolver(inst);
            s.maxNodes = 20000000L;
            long found = s.solve();

            T.check("ScanSolver finds a solution (" + label + ")", found >= 1,
                    "aborted=" + s.aborted + " nodes=" + s.nodes);
            if (found >= 1) {
                T.isNull("that solution validates (" + label + ")",
                         Validator.validateComplete(inst, s.solutionBoard));
                T.eq("a complete board matches every internal edge (" + label + ")",
                     Validator.internalEdgeTotal(inst),
                     Validator.matchedEdges(inst, s.solutionBoard));
                T.eq("bestPlaced reaches the whole board on a solution (" + label + ")",
                     inst.cells, s.bestPlaced);
            }
        }
    }

    // ------------------------------------------------------------ hint piece

    private static void itHonoursTheHintPiece() {
        Instance inst = Generator.generate(5, 4, 205L, true, true);
        ScanSolver s = new ScanSolver(inst);
        s.stopAtFirstSolution = false;
        s.maxNodes = 20000000L;
        SolutionCollector seen = new SolutionCollector();
        s.setSampleEveryNodes(Long.MAX_VALUE);
        s.setListener(seen);
        long found = s.solve();

        T.check("the fixed-piece instance has solutions to check", found >= 1, "found=" + found);
        boolean everySolutionHonoursIt = found >= 1;
        int cell = inst.fixedCell[0];
        int want = inst.variantSides(inst.fixedPiece[0], inst.fixedRot[0]);
        for (int i = 0; i < seen.count(); i++) {
            int[] board = seen.board(i);
            int v = board[cell];
            if (v < 0 || (v >>> 2) != inst.fixedPiece[0]
                    || inst.variantSides(v >>> 2, v & 3) != want) {
                everySolutionHonoursIt = false;
                break;
            }
        }
        T.check("every enumerated solution keeps the hint piece where and how it belongs",
                everySolutionHonoursIt, "solutions=" + seen.count());

        // The hint piece is withheld from every other cell's candidate list, so
        // it can never turn up somewhere else even on a partial board.
        boolean usedOnlyThere = true;
        for (int i = 0; i < seen.count(); i++) {
            int[] board = seen.board(i);
            for (int c = 0; c < inst.cells; c++) {
                if (c == cell) continue;
                if (board[c] >= 0 && (board[c] >>> 2) == inst.fixedPiece[0]) {
                    usedOnlyThere = false;
                    break;
                }
            }
        }
        T.check("the hint piece never appears anywhere else", usedOnlyThere);
    }

    private static void itRejectsAnImpossibleFixedPlacement() {
        // Pin a piece with no grey side into the top-left corner, where two
        // grey sides are required.
        Instance base = Generator.generate(4, 3, 301L, true, false);
        int[][] pieces = base.piecesCopy();
        int interior = -1;
        for (int id = 0; id < pieces.length; id++) {
            if (Sides.greyCount(Sides.pack(pieces[id])) == 0) { interior = id; break; }
        }
        T.check("the generated instance has an interior piece to misuse", interior >= 0);
        if (interior < 0) return;

        Instance bad = new Instance(4, pieces, new int[] { 0 },
                                    new int[] { interior }, new int[] { 0 });
        boolean threw = false;
        String message = null;
        try {
            new ScanSolver(bad);
        } catch (IllegalStateException e) {
            threw = true;
            message = e.getMessage();
        }
        T.threw("an impossible fixed placement is rejected at construction", threw);
        T.check("the rejection says which cell and why",
                message != null && message.indexOf("cell 0") >= 0
                                && message.indexOf("grey") >= 0,
                "message was: " + message);
    }

    // ---------------------------------------------------------------- budget

    private static void itStopsAtItsNodeBudget() {
        ScanSolver s = new ScanSolver(Instance.eternity2());
        s.maxNodes = 50000L;
        s.solve();
        T.check("the search aborts once the node budget runs out", s.aborted);
        T.check("it does not overshoot the budget", s.nodes <= 50000L, "nodes=" + s.nodes);

        SolverConfig capped = new SolverConfig();
        capped.nodeBudget = 30000L;
        ScanSolver c = new ScanSolver(Instance.eternity2(), capped);
        c.solve();
        T.check("nodeBudget from the config caps the search too",
                c.aborted && c.nodes <= 30000L, "nodes=" + c.nodes);
    }

    private static void itIsRepeatable() {
        Instance inst = Generator.generate(6, 6, 401L, true, true);
        ScanSolver s = new ScanSolver(inst);
        s.maxNodes = 20000000L;
        long first = s.solve();
        long firstNodes = s.nodes;
        int[] firstBoard = s.solutionBoard;

        s.reset();
        long second = s.solve();

        T.eq("re-running the same solver finds the same number of solutions", first, second);
        T.eq("and expands exactly the same number of nodes", firstNodes, s.nodes);
        T.eqIntArray("and returns the same board", firstBoard, s.solutionBoard);
    }

    // ------------------------------------------------------------- real puzzle

    private static void itRunsOnTheRealPuzzle() {
        Instance inst = Instance.eternity2();
        ScanSolver s = new ScanSolver(inst);
        s.maxNodes = 400000L;
        s.solve();

        T.check("the candidate index is built for the real puzzle",
                s.candidateEntryCount() > 0 && s.classCount() >= 4,
                "entries=" + s.candidateEntryCount() + " classes=" + s.classCount());
        T.check("the hint piece and its two placed neighbours each get a class",
                s.classCount() == 7, "classes=" + s.classCount());
        T.notNull("a partial board was recorded", s.bestBoard);
        T.isNull("the deepest partial board obeys every puzzle rule",
                 Validator.validatePartial(inst, s.bestBoard, false));
        T.check("it joined up some edges", s.bestMatchedEdges > 0,
                "edges=" + s.bestMatchedEdges);
        T.eq("a complete Eternity II board would score 480",
             480, Validator.internalEdgeTotal(inst));
    }
}
