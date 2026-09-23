package core;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

/**
 * What a verbose attempt writes to its progress log.
 *
 * A detached attempt leaves nothing behind but this log, and two improvements
 * used to be missing from it.  A board filled with breaks was recorded by
 * {@link ScanSolver#complete} and never printed -- and because that sets
 * {@code bestPlaced} to the cell count, the print in {@code dfs} could never
 * fire again either, so the log fell silent for the rest of the attempt.  A
 * rise in the error-free reach was raised in {@code dfs} with no print of its
 * own, so it reached the log only as a field on some later board line.
 *
 * Every check below reads the log the engine actually wrote, because the
 * behaviour under test is what an operator can see.
 */
public final class ProgressLogTest {

    public static void run() {
        T.section("ProgressLogTest: what a verbose attempt writes down");

        itPrintsABoardFilledWithBreaks();
        itPrintsTheErrorFreeReachItReached();
        itWritesNothingWhenNotVerbose();

        T.endSection();
    }

    // ------------------------------------------------------- filled board

    /**
     * The board that goes silent: every cell filled, breaks spent to do it.
     * Before the fix the engine recorded it and said nothing.
     */
    private static void itPrintsABoardFilledWithBreaks() {
        Instance inst = fillableOnlyBySlipping();
        ScanSolver s = slippingSolver(inst);
        String log = logOf(s);

        T.eq("the search filled the board", inst.cells, s.bestPlaced);
        T.check("and spent breaks doing so", s.bestBreaks > 0,
                "breaks=" + s.bestBreaks);

        String expected = "placed=" + inst.cells + "/" + inst.cells;
        T.check("the filled board appears in the log",
                progressIn(log).contains(expected),
                "no progress line said " + expected + "; log was:\n" + log);
    }

    // --------------------------------------------------- error-free reach

    /**
     * The reach is a property of the search rather than of any one board, so
     * the check is tied to the field the engine ends up holding, not to a
     * number written here.
     */
    private static void itPrintsTheErrorFreeReachItReached() {
        Instance inst = fillableOnlyBySlipping();
        ScanSolver s = slippingSolver(inst);
        String log = logOf(s);

        T.check("the search got somewhere without breaking anything",
                s.deepestErrorFree > 0, "errorFree=" + s.deepestErrorFree);

        String expected = "errorFree=" + s.deepestErrorFree + "/" + inst.cells;
        T.check("the reach it ended on appears in the log",
                progressIn(log).contains(expected),
                "no progress line said " + expected + "; log was:\n" + log);
    }

    // ------------------------------------------------------------- quiet

    /** Printing is opt-in, and a fix to one branch must not change that. */
    private static void itWritesNothingWhenNotVerbose() {
        Instance inst = fillableOnlyBySlipping();
        ScanSolver s = slippingSolver(inst);
        s.verbose = false;
        String log = logOf(s);

        T.eq("a quiet attempt writes nothing at all", "", log.trim());
    }

    // ----------------------------------------------------------- fixtures

    /**
     * A 4x4 no perfect board can satisfy, so an exact search cannot fill it
     * and a slipping one can.  Built the same way as
     * {@link EdgeSlippingTest}'s, which needs a board with the same property.
     */
    private static Instance fillableOnlyBySlipping() {
        Instance base = Generator.generate(4, 3, 500L, true, false);
        int[][] pieces = base.piecesCopy();
        pieces[0][0] = (pieces[0][0] % 2) + 1;
        return new Instance(4, pieces, null, null, null);
    }

    private static ScanSolver slippingSolver(Instance inst) {
        SolverConfig cfg = new SolverConfig();
        cfg.slipSchedule = SolverConfig.SLIP_BLACKWOOD;
        ScanSolver s = new ScanSolver(inst, cfg);
        s.stopAtFirstSolution = false;
        s.maxNodes = 20000000L;
        s.verbose = true;
        return s;
    }

    /**
     * Everything written before the end-of-attempt summary.
     *
     * That summary reports the same figures at the end, so an assertion made
     * against the whole log would pass on a run that printed nothing while it
     * was working -- which is the exact fault these checks are here for.
     *
     * A missing marker is raised rather than shrugged off: returning the whole
     * log would leave every caller asserting against the summary line and
     * quietly passing, which is the failure this helper exists to prevent.
     */
    private static String progressIn(String log) {
        int end = log.indexOf("ScanSolver done");
        if (end < 0) {
            throw new IllegalStateException("no end-of-attempt summary in the log, so "
                + "progress lines cannot be told apart from it; log was:\n" + log);
        }
        return log.substring(0, end);
    }

    /** Run the solver with stdout captured, and hand back everything it wrote. */
    private static String logOf(ScanSolver s) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(buffer, true));
        try {
            s.solve();
        } finally {
            System.setOut(original);
        }
        return buffer.toString();
    }
}
