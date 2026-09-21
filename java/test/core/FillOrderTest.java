package core;

/**
 * The fill orders are asserted structurally rather than by running a search:
 * the solver's whole design rests on "north and west are down, east and south
 * are not", and the frontier measures are a cheap proxy for how good an order
 * is, so both can be checked in microseconds.
 */
public final class FillOrderTest {

    public static void run() {
        T.section("FillOrderTest: fixed cell orders and their frontiers");

        invariantHoldsForEverySize();
        bandedScanMatchesThePublishedPhases();
        frontierMeasuresOnEternityII();
        validateRejectsABrokenOrder();
        tinyBoardsAreRejected();

        T.endSection();
    }

    // ------------------------------------------------------------- invariant

    private static void invariantHoldsForEverySize() {
        boolean allRowMajor = true, allBanded = true;
        String firstProblem = null;
        for (int n = 2; n <= 20; n++) {
            String a = FillOrder.validate(FillOrder.rowMajor(n), n);
            String b = FillOrder.validate(FillOrder.bandedScan(n), n);
            if (a != null) { allRowMajor = false; if (firstProblem == null) firstProblem = "n=" + n + ": " + a; }
            if (b != null) { allBanded = false; if (firstProblem == null) firstProblem = "n=" + n + ": " + b; }
        }
        T.check("row-major order delivers north and west first for n = 2..20",
                allRowMajor, firstProblem);
        T.check("banded scan delivers north and west first for n = 2..20",
                allBanded, firstProblem);
    }

    // ----------------------------------------------------------------- shape

    private static void bandedScanMatchesThePublishedPhases() {
        int n = 16;
        int[] order = FillOrder.bandedScan(n);

        T.eq("banded scan ends the 11-row scan at row 10, column 15",
             10 * 16 + 15, order[175]);
        T.eq("banded scan narrows to column 0 of row 11 at depth 176",
             11 * 16, order[176]);
        T.eq("the narrowed scan ends at row 15, column 4",
             15 * 16 + 4, order[200]);
        T.eq("the column sweep starts at row 11, column 5",
             11 * 16 + 5, order[201]);
        T.eq("the column sweep ends at row 15, column 11",
             15 * 16 + 11, order[235]);
        T.eq("the L-shapes start at row 11, column 12",
             11 * 16 + 12, order[236]);
        T.eq("the L-shapes end at the bottom-right corner",
             15 * 16 + 15, order[255]);

        // Each column of the sweep is taken top to bottom, five at a time.
        boolean fiveAtATime = true;
        for (int d = 201; d < 235; d++) {
            int here = order[d], next = order[d + 1];
            boolean downOne = (next == here + 16);
            boolean newColumn = ((d - 200) % 5 == 0);
            if (newColumn == downOne) { fiveAtATime = false; break; }
        }
        T.check("the bottom band is swept column by column, 5 cells at a time", fiveAtATime);
    }

    // -------------------------------------------------------------- measures

    private static void frontierMeasuresOnEternityII() {
        int n = 16;
        int[] rowMajor = FillOrder.rowMajor(n);
        int[] banded = FillOrder.bandedScan(n);

        T.eq("a 16-wide row scan holds 16 cells open at its peak",
             16, FillOrder.peakOpenFrontier(rowMajor, n));
        T.eq("the banded scan holds 19 open, because it leaves a bottom band empty",
             19, FillOrder.peakOpenFrontier(banded, n));

        T.eq("a row scan never detects a bad choice more than 16 placements later",
             16, FillOrder.peakDetectionLag(rowMajor, n, 0));
        T.eq("the banded scan carries its worst lag across the phase boundary",
             64, FillOrder.peakDetectionLag(banded, n, 0));

        // Depth 201 is where the column sweep starts: the deep, expensive tail.
        T.eq("a row scan still lags 16 in the deep tail",
             16, FillOrder.peakDetectionLag(rowMajor, n, 201));
        T.eq("the banded scan lags at most 8 in the deep tail",
             8, FillOrder.peakDetectionLag(banded, n, 201));

        int rowMajorMean = FillOrder.meanDetectionLagX100(rowMajor, n, 201);
        int bandedMean = FillOrder.meanDetectionLagX100(banded, n, 201);
        T.check("the banded scan more than halves the mean lag in the deep tail",
                bandedMean * 2 < rowMajorMean,
                "rowMajor=" + rowMajorMean + " banded=" + bandedMean + " (x100)");

        // Every size keeps the same trade: a wider open frontier bought with a
        // shorter lag where it matters.
        boolean alwaysShorterTail = true;
        for (int m = 6; m <= 20; m++) {
            int from = (m * m * 4) / 5;
            int r = FillOrder.peakDetectionLag(FillOrder.rowMajor(m), m, from);
            int b = FillOrder.peakDetectionLag(FillOrder.bandedScan(m), m, from);
            if (b >= r) { alwaysShorterTail = false; break; }
        }
        T.check("the banded scan has the shorter tail lag at every size from 6 to 20",
                alwaysShorterTail);
    }

    // ------------------------------------------------------------ validation

    private static void validateRejectsABrokenOrder() {
        int n = 4;
        int[] order = FillOrder.rowMajor(n);
        int t = order[1]; order[1] = order[5]; order[5] = t;
        T.notNull("validate rejects an order that fills a cell before its west neighbour",
                  FillOrder.validate(order, n));

        int[] repeated = FillOrder.rowMajor(n);
        repeated[3] = repeated[2];
        T.notNull("validate rejects an order that uses a cell twice",
                  FillOrder.validate(repeated, n));

        T.notNull("validate rejects an order of the wrong length",
                  FillOrder.validate(new int[5], n));
        T.notNull("validate rejects a null order", FillOrder.validate(null, n));
    }

    private static void tinyBoardsAreRejected() {
        boolean threw = false;
        try { FillOrder.bandedScan(1); } catch (IllegalArgumentException e) { threw = true; }
        T.threw("a 1x1 board is rejected, like every other Instance of n < 2", threw);
    }
}
