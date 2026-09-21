package core;

/**
 * Benchmarks for the solvers.
 *
 *   java -cp out core.Bench             # every benchmark
 *   java -cp out core.Bench solvable    # time-to-solution on solvable instances
 *   java -cp out core.Bench eternity 15 # 15s each on the real puzzle
 *   java -cp out core.Bench engines 20  # MrvSolver vs ScanSolver, 20s each
 *   java -cp out core.Bench budget 20000000  # the same, at equal node count
 *   java -cp out core.Bench order       # fill-order frontiers, no search
 *
 * Several different questions are measured, because they have different
 * answers:
 *
 *  1. "solvable": wall-clock time to find a solution on generated instances
 *     that really do have one.  This is the decisive comparison, since it
 *     folds search-tree quality and per-node cost into one number.
 *
 *  2. "eternity": raw throughput and depth reached on the real 16x16 puzzle,
 *     which nobody can finish, so only progress can be compared.
 *
 *  3. "engines" and "budget": MrvSolver against ScanSolver, once per second of
 *     wall clock and once per node.  Both are needed -- the engines differ by
 *     more than an order of magnitude in throughput AND in nodes per answer,
 *     and only the node-budget run is stable on a loaded machine.
 *
 *  4. "order": the structural measures from {@link FillOrder}, which predict
 *     order quality without running a search at all.
 */
public final class Bench {

    public static void main(String[] args) {
        String which = (args.length > 0) ? args[0] : "all";
        long seconds = 10;
        if (args.length > 1) {
            try { seconds = Long.parseLong(args[1]); } catch (Throwable e) { }
        }
        if (which.equals("all") || which.equals("order")) orderBenchmark();
        if (which.equals("all") || which.equals("solvable")) solvableBenchmark();
        if (which.equals("all") || which.equals("eternity")) eternityBenchmark(seconds);
        if (which.equals("all") || which.equals("engines")) engineBenchmark(seconds);
        if (which.equals("budget")) {
            long budget = (args.length > 1) ? parseLong(args[1], 20000000L) : 20000000L;
            budgetBenchmark(budget);
        } else if (which.equals("all")) {
            budgetBenchmark(20000000L);
        }
    }

    private static long parseLong(String s, long dflt) {
        try { return Long.parseLong(s); } catch (Throwable e) { return dflt; }
    }

    // ---------------------------------------------------------------- orders

    /**
     * What the two fill orders look like structurally.  Peak open frontier is
     * how much board is held open at once; tail lag is how long a mistake
     * survives once the search is deep, which is where the tree is expensive.
     */
    private static void orderBenchmark() {
        System.out.println("=================================================================");
        System.out.println(" Fill orders: peak open frontier and detection lag");
        System.out.println("=================================================================");
        System.out.println(" board  order      peak open   worst lag   tail lag   tail mean lag");

        int[] ns = { 8, 12, 16, 20 };
        for (int i = 0; i < ns.length; i++) {
            int n = ns[i];
            int tail = (n * n * 4) / 5;
            reportOrder(n, "rowMajor", FillOrder.rowMajor(n), tail);
            reportOrder(n, "banded", FillOrder.bandedScan(n), tail);
        }
        System.out.println();
        System.out.println(" The banded order holds MORE of the board open, and that is the");
        System.out.println(" price of it: what it buys is a much shorter lag in the tail.");
        System.out.println();
    }

    private static void reportOrder(int n, String name, int[] order, int tail) {
        int meanX100 = FillOrder.meanDetectionLagX100(order, n, tail);
        System.out.println(" " + pad(n + "x" + n, 6)
            + " " + pad(name, 10)
            + " " + pad("" + FillOrder.peakOpenFrontier(order, n), 11)
            + " " + pad("" + FillOrder.peakDetectionLag(order, n, 0), 11)
            + " " + pad("" + FillOrder.peakDetectionLag(order, n, tail), 10)
            + " " + (meanX100 / 100) + "." + pad2(meanX100 % 100));
    }

    // --------------------------------------------------------------- engines

    /** Equal wall clock: what each engine gets done in the same second. */
    private static void engineBenchmark(long seconds) {
        System.out.println("=================================================================");
        System.out.println(" MrvSolver vs ScanSolver on Eternity II, " + seconds + "s each");
        System.out.println("=================================================================");

        MrvSolver mrv = new MrvSolver(Instance.eternity2());
        Runner r1 = new Runner();
        r1.mrv = mrv;
        runFor(r1, seconds);
        reportEngine("MrvSolver  (MRV + full-board forward check)",
                     mrv.nodes, r1.elapsedMs, mrv.bestPlaced, mrv.bestMatchedEdges);

        ScanSolver scan = new ScanSolver(Instance.eternity2());
        Runner r2 = new Runner();
        r2.scan = scan;
        runFor(r2, seconds);
        reportEngine("ScanSolver (banded fill order, two-colour index)",
                     scan.nodes, r2.elapsedMs, scan.bestPlaced, scan.bestMatchedEdges);
        System.out.println();
    }

    /** Equal node count: the comparison that survives a busy machine. */
    private static void budgetBenchmark(long budget) {
        System.out.println("=================================================================");
        System.out.println(" MrvSolver vs ScanSolver on Eternity II, " + budget + " nodes each");
        System.out.println("=================================================================");

        MrvSolver mrv = new MrvSolver(Instance.eternity2());
        mrv.maxNodes = budget;
        long t0 = System.nanoTime();
        mrv.solve();
        long ms0 = (System.nanoTime() - t0) / 1000000L;
        reportEngine("MrvSolver", mrv.nodes, ms0, mrv.bestPlaced, mrv.bestMatchedEdges);

        ScanSolver scan = new ScanSolver(Instance.eternity2());
        scan.maxNodes = budget;
        long t1 = System.nanoTime();
        scan.solve();
        long ms1 = (System.nanoTime() - t1) / 1000000L;
        reportEngine("ScanSolver", scan.nodes, ms1, scan.bestPlaced, scan.bestMatchedEdges);
        System.out.println();
    }

    private static void reportEngine(String name, long nodes, long ms, int best, int edges) {
        System.out.println();
        System.out.println(name);
        System.out.println("  nodes         : " + nodes);
        System.out.println("  elapsed ms    : " + ms);
        System.out.println("  nodes/sec     : " + (ms == 0 ? 0 : nodes * 1000L / ms));
        System.out.println("  pieces placed : " + best + " / 256");
        System.out.println("  matched edges : " + edges + " / 480");
    }

    // ------------------------------------------------------------- solvable

    /**
     * MRV vs lowest-index cell ordering inside the SAME bitset machinery, so
     * the only difference is the heuristic.  Instances are generated, hence
     * guaranteed solvable, and deliberately span easy and hard regimes.
     */
    private static void solvableBenchmark() {
        System.out.println("=================================================================");
        System.out.println(" Time to first solution, MRV vs lowest-index cell ordering");
        System.out.println(" (identical bitset machinery; only the cell heuristic differs)");
        System.out.println("=================================================================");
        System.out.println(" board  colours  seeds        MRV nodes     MRV ms"
                         + "    rowMajor nodes  rowMajor ms");

        int[] ns      = {  6,  8,  8, 10, 12 };
        int[] cols    = {  5, 12,  7, 24, 30 };
        int   seeds   = 5;

        long totalMrvNodes = 0, totalRowNodes = 0, totalMrvMs = 0, totalRowMs = 0;

        for (int i = 0; i < ns.length; i++) {
            long mrvNodes = 0, rowNodes = 0, mrvMs = 0, rowMs = 0;
            int mrvSolved = 0, rowSolved = 0;

            for (int seed = 1; seed <= seeds; seed++) {
                Instance inst = Generator.generate(ns[i], cols[i], 4000L + seed, true, true);

                MrvSolver a = new MrvSolver(inst);
                a.useMrv = true;
                a.maxNodes = 2000000L;
                long t0 = System.nanoTime();
                if (a.solve() >= 1) mrvSolved++;
                mrvMs += (System.nanoTime() - t0) / 1000000L;
                mrvNodes += a.nodes;

                MrvSolver b = new MrvSolver(inst);
                b.useMrv = false;
                b.maxNodes = 2000000L;
                long t1 = System.nanoTime();
                if (b.solve() >= 1) rowSolved++;
                rowMs += (System.nanoTime() - t1) / 1000000L;
                rowNodes += b.nodes;
            }

            System.out.println(" " + pad(ns[i] + "x" + ns[i], 6)
                + " " + pad("" + cols[i], 8)
                + " " + pad(mrvSolved + "/" + seeds + " vs " + rowSolved + "/" + seeds, 12)
                + " " + pad("" + mrvNodes, 13)
                + " " + pad("" + mrvMs, 9)
                + " " + pad("" + rowNodes, 15)
                + " " + rowMs);

            totalMrvNodes += mrvNodes; totalRowNodes += rowNodes;
            totalMrvMs += mrvMs;       totalRowMs += rowMs;
        }

        System.out.println();
        System.out.println(" totals: MRV " + totalMrvNodes + " nodes / " + totalMrvMs + " ms"
            + "   rowMajor " + totalRowNodes + " nodes / " + totalRowMs + " ms");
        if (totalMrvNodes > 0) {
            System.out.println(" node reduction from MRV : "
                + fmtRatio(totalRowNodes, totalMrvNodes) + "x");
        }
        if (totalMrvMs > 0) {
            System.out.println(" wall-clock speed-up     : "
                + fmtRatio(totalRowMs, totalMrvMs) + "x");
        }
        System.out.println();
    }

    // ------------------------------------------------------------- eternity

    private static void eternityBenchmark(long seconds) {
        System.out.println("=================================================================");
        System.out.println(" Eternity II, " + seconds + "s per solver");
        System.out.println("=================================================================");

        Solver rowMajor = new Solver();
        rowMajor.verbose = false;
        Runner r1 = new Runner();
        r1.rowMajor = rowMajor;
        runFor(r1, seconds);
        System.out.println();
        System.out.println("row-major + one-step forward check  (Solver)");
        report(rowMajor.nodes, rowMajor.bestDepth, r1.elapsedMs);

        MrvSolver mrv = new MrvSolver(Instance.eternity2());
        mrv.verbose = false;
        Runner r2 = new Runner();
        r2.mrv = mrv;
        runFor(r2, seconds);
        System.out.println();
        System.out.println("MRV + bitsets + full-board forward check  (MrvSolver)");
        report(mrv.nodes, mrv.bestPlaced, r2.elapsedMs);

        System.out.println();
        System.out.println("Eternity II sits in the hard region of the random-CSP phase");
        System.out.println("transition, so neither solver is expected to finish.  MrvSolver");
        System.out.println("does far more work per node but explores a much better tree.");
    }

    private static void report(long nodes, int best, long ms) {
        System.out.println("  nodes         : " + nodes);
        System.out.println("  elapsed ms    : " + ms);
        System.out.println("  nodes/sec     : " + (ms == 0 ? 0 : nodes * 1000L / ms));
        System.out.println("  pieces placed : " + best + " / 256");
    }

    private static void runFor(Runner r, long seconds) {
        Thread t = new Thread(r);
        long start = System.nanoTime();
        t.start();
        try { t.join(seconds * 1000L); } catch (InterruptedException e) { }
        if (r.rowMajor != null) r.rowMajor.maxNodes = 1;   // ask it to stop
        if (r.mrv != null) r.mrv.maxNodes = 1;
        if (r.scan != null) r.scan.maxNodes = 1;
        try { t.join(10000L); } catch (InterruptedException e) { }
        r.elapsedMs = (System.nanoTime() - start) / 1000000L;
    }

    static final class Runner implements Runnable {
        Solver rowMajor;
        MrvSolver mrv;
        ScanSolver scan;
        long elapsedMs;
        public void run() {
            if (rowMajor != null) rowMajor.solve();
            if (mrv != null) mrv.solve();
            if (scan != null) scan.solve();
        }
    }

    // ------------------------------------------------------------- formatting

    private static String pad(String s, int w) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < w) sb.append(' ');
        return sb.toString();
    }

    private static String fmtRatio(long num, long den) {
        if (den == 0) return "n/a";
        long scaled = num * 100L / den;
        return (scaled / 100) + "." + pad2(scaled % 100);
    }

    private static String pad2(long v) {
        if (v < 10) return "0" + v;
        return "" + v;
    }
}
