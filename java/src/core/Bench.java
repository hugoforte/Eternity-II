package core;

/**
 * Benchmarks for the two solvers.
 *
 *   java -cp out core.Bench            # both benchmarks
 *   java -cp out core.Bench solvable   # time-to-solution on solvable instances
 *   java -cp out core.Bench eternity 15  # 15s each on the real puzzle
 *
 * Two different questions are measured, because they have different answers:
 *
 *  1. "solvable": wall-clock time to find a solution on generated instances
 *     that really do have one.  This is the decisive comparison, since it
 *     folds search-tree quality and per-node cost into one number.
 *
 *  2. "eternity": raw throughput and depth reached on the real 16x16 puzzle,
 *     which nobody can finish, so only progress can be compared.
 */
public final class Bench {

    public static void main(String[] args) {
        String which = (args.length > 0) ? args[0] : "all";
        long seconds = 10;
        if (args.length > 1) {
            try { seconds = Long.parseLong(args[1]); } catch (Throwable e) { }
        }
        if (which.equals("all") || which.equals("solvable")) solvableBenchmark();
        if (which.equals("all") || which.equals("eternity")) eternityBenchmark(seconds);
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
        try { t.join(10000L); } catch (InterruptedException e) { }
        r.elapsedMs = (System.nanoTime() - start) / 1000000L;
    }

    static final class Runner implements Runnable {
        Solver rowMajor;
        MrvSolver mrv;
        long elapsedMs;
        public void run() {
            if (rowMajor != null) rowMajor.solve();
            if (mrv != null) mrv.solve();
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
