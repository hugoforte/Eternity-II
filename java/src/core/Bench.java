package core;

/**
 * Benchmarks for the solvers.
 *
 *   java -cp out core.Bench             # every benchmark
 *   java -cp out core.Bench solvable    # time-to-solution on solvable instances
 *   java -cp out core.Bench eternity 15 # 15s each on the real puzzle
 *   java -cp out core.Bench engines 20  # MrvSolver vs ScanSolver, 20s each
 *   java -cp out core.Bench budget 20000000  # the same, at equal node count
 *   java -cp out core.Bench slip        # edge slipping off vs on, equal nodes
 *   java -cp out core.Bench order       # fill-order frontiers, no search
 *   java -cp out core.Bench seeds 20 100000000   # 20 seeds at an equal budget
 *   java -cp out core.Bench quota       # the colour quota off vs on, equal nodes
 *   java -cp out core.Bench colours     # the quota's colour triples, equal nodes
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
 *
 *  5. "slip": ScanSolver against itself with edge slipping off and on, at
 *     equal node counts.  Slipping buys depth at some cost in throughput, and
 *     both halves of that trade have to be shown for the result to mean
 *     anything, so nodes/sec is reported alongside the score.
 *
 *  6. "seeds": the same ScanSolver configuration run under many seeds at one
 *     node budget, reported as a DISTRIBUTION rather than a number.  Every
 *     other benchmark here reports one run, which is all a deterministic
 *     engine can give; the question this one answers is how much the seed is
 *     worth, and it has three parts.  How far apart the seeds land decides
 *     whether sampling is worth a core at all.  Where the unseeded run sits in
 *     that spread says whether the natural candidate order was good luck or
 *     good design.  And the best of N against the single run is the number a
 *     lab with N cores actually gets.
 *
 *  7. "quota": ScanSolver against itself with the colour-quota gate off and
 *     on, at equal node counts, on top of the configuration that is otherwise
 *     best.  The gate is deliberately incomplete, so a wall-clock comparison
 *     would say nothing; equal nodes is the only honest one.  It ends with a
 *     diagnostic that needs no search at all -- the ramp's demand against the
 *     most any set of pieces could possibly carry -- because when those two
 *     are within a few sides of each other the gate is not a heuristic but
 *     very nearly a contradiction, and the search result alone would not say
 *     which.
 *
 *  8. "colours": which three colours the quota should track.  Every (one
 *     border, two interior) triple is ranked by that same arithmetic headroom,
 *     and the best, the worst and Blackwood's are then actually run, so the
 *     table shows both what the piece set allows and what the search does with
 *     it.
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
        if (which.equals("slip")) {
            if (args.length > 1) slipBenchmark(new long[] { parseLong(args[1], 20000000L) });
            else slipBenchmark(new long[] { 5000000L, 20000000L, 100000000L });
        } else if (which.equals("all")) {
            slipBenchmark(new long[] { 20000000L });
        }
        if (which.equals("budget")) {
            long budget = (args.length > 1) ? parseLong(args[1], 20000000L) : 20000000L;
            budgetBenchmark(budget);
        } else if (which.equals("all")) {
            budgetBenchmark(20000000L);
        }
        if (which.equals("quota")) {
            if (args.length > 1) quotaBenchmark(new long[] { parseLong(args[1], 100000000L) });
            else quotaBenchmark(new long[] { 100000000L, 1000000000L });
        } else if (which.equals("all")) {
            quotaBenchmark(new long[] { 20000000L });
        }
        if (which.equals("colours")) {
            colourBenchmark((args.length > 1) ? parseLong(args[1], 100000000L) : 100000000L);
        }
        if (which.equals("seeds")) {
            int count = (args.length > 1) ? (int) parseLong(args[1], 20L) : 20;
            long budget = (args.length > 2) ? parseLong(args[2], 20000000L) : 20000000L;
            int strength = (args.length > 3) ? (int) parseLong(args[3], 25L) : 25;
            seedBenchmark(count, budget, strength);
        } else if (which.equals("all")) {
            seedBenchmark(8, 5000000L, 25);
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

    // ---------------------------------------------------------- edge slipping

    /**
     * What edge slipping is worth on the real puzzle.  An exact search cannot
     * score above the best perfect prefix it reaches, so the question is not
     * whether slipping goes further -- it must -- but whether it goes far
     * enough to pay for the throughput it costs.
     */
    private static void slipBenchmark(long[] budgets) {
        System.out.println("=================================================================");
        System.out.println(" ScanSolver on Eternity II: edge slipping off vs on, equal nodes");
        System.out.println("=================================================================");
        System.out.println(" budget      schedule    ms      nodes/sec    placed   edges  breaks");

        int[] schedules = { SolverConfig.SLIP_NONE, SolverConfig.SLIP_BLACKWOOD,
                            SolverConfig.SLIP_VERHAARD };
        for (int b = 0; b < budgets.length; b++) {
            for (int i = 0; i < schedules.length; i++) {
                SolverConfig cfg = new SolverConfig();
                cfg.slipSchedule = schedules[i];
                ScanSolver s = new ScanSolver(Instance.eternity2(), cfg);
                s.maxNodes = budgets[b];
                long t0 = System.nanoTime();
                s.solve();
                long ms = (System.nanoTime() - t0) / 1000000L;
                System.out.println(" " + pad("" + budgets[b], 11)
                    + " " + pad(SolverConfig.slipScheduleName(schedules[i]), 11)
                    + " " + pad("" + ms, 7)
                    + " " + pad("" + (ms == 0 ? 0 : s.nodes * 1000L / ms), 12)
                    + " " + pad(s.bestPlaced + "/256", 8)
                    + " " + pad(s.bestMatchedEdges + "/480", 7)
                    + " " + s.bestBreaks);
                String err = Validator.validatePartial(Instance.eternity2(), s.bestBoard,
                                                       false, s.bestBreaks);
                if (err != null) System.out.println("   INVALID BOARD: " + err);
            }
        }
        System.out.println();
    }

    // ------------------------------------------------------------ colour quota

    /** Blackwood's three colours, read as indices into our own piece table. */
    private static final String BLACKWOOD_COLOURS = "13,16,10";

    /**
     * The colour-quota gate off against on, at equal node budgets, on top of
     * the configuration that is otherwise best here (banded order, Verhaard's
     * slip schedule).  The gate abandons subtrees that could hold solutions, so
     * it can only be judged on what it reaches per node, never on speed.
     */
    private static void quotaBenchmark(long[] budgets) {
        System.out.println("=================================================================");
        System.out.println(" ScanSolver on Eternity II: colour quota off vs on, equal nodes");
        System.out.println(" fillOrder=banded  slipSchedule=verhaard  quotaColours="
                           + BLACKWOOD_COLOURS);
        System.out.println("=================================================================");
        System.out.println(" budget      quota   ms      nodes/sec    placed   edges  breaks");

        ScanSolver ungated = null;
        for (int b = 0; b < budgets.length; b++) {
            for (int on = 0; on < 2; on++) {
                ScanSolver s = runQuota(budgets[b], on == 1, BLACKWOOD_COLOURS);
                if (on == 0) ungated = s;
            }
        }
        System.out.println();
        reportQuotaHeadroom(BLACKWOOD_COLOURS, ungated);
    }

    /** One run at one budget, reported as a row. */
    private static ScanSolver runQuota(long budget, boolean gated, String colours) {
        SolverConfig cfg = new SolverConfig();
        cfg.engine = SolverConfig.ENGINE_SCAN;
        cfg.slipSchedule = SolverConfig.SLIP_VERHAARD;
        cfg.quotaColours = colours;
        if (gated) cfg.quotaSchedule = SolverConfig.QUOTA_BLACKWOOD;
        ScanSolver s = new ScanSolver(Instance.eternity2(), cfg);
        s.maxNodes = budget;
        long t0 = System.nanoTime();
        s.solve();
        long ms = (System.nanoTime() - t0) / 1000000L;
        System.out.println(" " + pad("" + budget, 11)
            + " " + pad(gated ? "on" : "off", 7)
            + " " + pad("" + ms, 7)
            + " " + pad("" + (ms == 0 ? 0 : s.nodes * 1000L / ms), 12)
            + " " + pad(s.bestPlaced + "/256", 8)
            + " " + pad(s.bestMatchedEdges + "/480", 7)
            + " " + s.bestBreaks);
        String err = Validator.validatePartial(Instance.eternity2(), s.bestBoard,
                                               false, s.bestBreaks);
        if (err != null) System.out.println("   INVALID BOARD: " + err);
        return s;
    }

    /**
     * Why the gate lands where it does, without running a search.
     *
     * The ramp demands R(d) quota-colour sides by placement d.  No d pieces of
     * this set can carry more than the sum of the d largest per-piece counts,
     * so that difference bounds the room the search has before edge matching
     * takes any of it away.  The ungated board's own curve is printed beside
     * it: that is what the engine reaches when nothing is forcing it.
     */
    private static void reportQuotaHeadroom(String colours, ScanSolver ungated) {
        Instance inst = Instance.eternity2();
        int[] floor = quotaFloor(colours);
        int[] per = quotaCounts(inst, colourList(colours));
        int[] most = bestPossible(per);
        int[] natural = (ungated == null) ? null : boardCurve(inst, per, ungated);

        System.out.println(" the ramp against what the piece set could possibly supply");
        System.out.println(" depth   ramp asks   most any pieces hold   ungated board reaches");
        int[] pts = { 16, 32, 64, 96, 128, 160 };
        for (int i = 0; i < pts.length; i++) {
            int d = pts[i];
            String reached = (natural == null || d >= natural.length) ? "-" : ("" + natural[d]);
            System.out.println(" " + pad("" + d, 7)
                + " " + pad("" + floor[d], 11)
                + " " + pad("" + most[d], 22)
                + " " + reached);
        }
        System.out.println();
    }

    /**
     * Every (one border, two interior) triple, ranked by the tightest the ramp
     * ever gets on it, and then the extremes of that ranking actually run.
     * Which colours to track is the one part of the technique that was brute
     * forced in the source material at a hundred minutes a combination, so the
     * cheap arithmetic ranking is what makes a handful of runs worth anything.
     */
    private static void colourBenchmark(long budget) {
        System.out.println("=================================================================");
        System.out.println(" ScanSolver on Eternity II: which three colours the quota tracks");
        System.out.println(" " + budget + " nodes each, fillOrder=banded, slipSchedule=verhaard");
        System.out.println("=================================================================");

        Instance inst = Instance.eternity2();
        int[] border = { 1, 2, 3, 13, 14 };
        int[] interior = { 4, 5, 6, 7, 8, 9, 10, 11, 12, 15, 16, 17, 18, 19, 20, 21, 22 };
        int[] floor = quotaFloor(BLACKWOOD_COLOURS);
        String[] spec = new String[border.length * interior.length * interior.length];
        int[] slack = new int[spec.length];
        int count = 0;
        for (int b = 0; b < border.length; b++) {
            for (int i = 0; i < interior.length; i++) {
                for (int j = i + 1; j < interior.length; j++) {
                    spec[count] = border[b] + "," + interior[i] + "," + interior[j];
                    slack[count] = worstSlack(quotaCounts(inst, colourList(spec[count])), floor);
                    count++;
                }
            }
        }
        // Insertion sort, descending by slack: a few hundred triples, and the
        // suite may not assume a JDK with anything fancier in it.
        for (int i = 1; i < count; i++) {
            String sp = spec[i];
            int sl = slack[i];
            int j = i - 1;
            while (j >= 0 && slack[j] < sl) { spec[j + 1] = spec[j]; slack[j + 1] = slack[j]; j--; }
            spec[j + 1] = sp;
            slack[j + 1] = sl;
        }
        System.out.println(" " + count + " triples ranked by the least room the ramp ever"
                           + " leaves them;");
        System.out.println(" a negative number means no arrangement of pieces could satisfy"
                           + " it at all.");
        System.out.println();
        System.out.println(" colours     total sides   worst slack   ms      placed   edges  breaks");

        String[] chosen = { spec[0], spec[1], spec[2], BLACKWOOD_COLOURS, spec[count - 1] };
        for (int k = 0; k < chosen.length; k++) {
            int[] per = quotaCounts(inst, colourList(chosen[k]));
            int total = 0;
            for (int i = 0; i < per.length; i++) total += per[i];
            SolverConfig cfg = new SolverConfig();
            cfg.engine = SolverConfig.ENGINE_SCAN;
            cfg.slipSchedule = SolverConfig.SLIP_VERHAARD;
            cfg.quotaSchedule = SolverConfig.QUOTA_BLACKWOOD;
            cfg.quotaColours = chosen[k];
            ScanSolver s = new ScanSolver(inst, cfg);
            s.maxNodes = budget;
            long t0 = System.nanoTime();
            s.solve();
            long ms = (System.nanoTime() - t0) / 1000000L;
            System.out.println(" " + pad(chosen[k], 11)
                + " " + pad("" + total, 13)
                + " " + pad("" + worstSlack(per, floor), 13)
                + " " + pad("" + ms, 7)
                + " " + pad(s.bestPlaced + "/256", 8)
                + " " + pad(s.bestMatchedEdges + "/480", 7)
                + " " + s.bestBreaks);
            String err = Validator.validatePartial(inst, s.bestBoard, false, s.bestBreaks);
            if (err != null) System.out.println("   INVALID BOARD: " + err);
        }
        System.out.println();
    }

    /** The published ramp, read off an engine built with it. */
    private static int[] quotaFloor(String colours) {
        SolverConfig cfg = new SolverConfig();
        cfg.quotaSchedule = SolverConfig.QUOTA_BLACKWOOD;
        cfg.quotaColours = colours;
        return new ScanSolver(Instance.eternity2(), cfg).quotaFloors();
    }

    private static int[] colourList(String colours) {
        return SolverConfig.parseColourList(colours);
    }

    /** Piece -> how many of its four sides carry one of these colours. */
    private static int[] quotaCounts(Instance inst, int[] colours) {
        boolean[] counted = new boolean[inst.numColours];
        for (int i = 0; i < colours.length; i++) counted[colours[i]] = true;
        int[] per = new int[inst.numPieces];
        for (int id = 0; id < inst.numPieces; id++) {
            int p = inst.packedSides[id];
            int c = 0;
            if (counted[Sides.left(p)]) c++;
            if (counted[Sides.top(p)]) c++;
            if (counted[Sides.right(p)]) c++;
            if (counted[Sides.bottom(p)]) c++;
            per[id] = c;
        }
        return per;
    }

    /** Depth -> the most quota-colour sides any that many pieces could carry. */
    private static int[] bestPossible(int[] per) {
        int[] sorted = new int[per.length];
        System.arraycopy(per, 0, sorted, 0, per.length);
        java.util.Arrays.sort(sorted);
        int[] out = new int[per.length + 1];
        for (int d = 1; d <= per.length; d++) out[d] = out[d - 1] + sorted[per.length - d];
        return out;
    }

    /** The tightest the ramp ever gets, over the depths it constrains. */
    private static int worstSlack(int[] per, int[] floor) {
        int[] most = bestPossible(per);
        int worst = Integer.MAX_VALUE;
        for (int d = 1; d < floor.length && d < most.length; d++) {
            int slack = most[d] - floor[d];
            if (slack < worst) worst = slack;
        }
        return worst;
    }

    /** Depth -> quota-colour sides consumed by the board a run actually found. */
    private static int[] boardCurve(Instance inst, int[] per, ScanSolver s) {
        int[] variants = s.bestOrderVariants();
        int len = s.bestOrderLength();
        int[] out = new int[len + 1];
        for (int d = 0; d < len; d++) out[d + 1] = out[d] + per[variants[d] >>> 2];
        return out;
    }

    // ------------------------------------------------------------- seeds

    /**
     * How much the seed is worth, as a distribution.
     *
     * {@code count} seeds run the same configuration at the same node budget,
     * so the only difference between them is the candidate order, and the
     * unseeded run is measured alongside as the reference point.  Everything
     * is reported: the whole sample, its spread, where the unseeded run falls
     * inside it, and the best of N.  An engine whose result barely moves with
     * the seed would show up here as a narrow spread, and that would be worth
     * knowing -- it is the reason to sample or the reason not to.
     */
    private static void seedBenchmark(int count, long budget, int strength) {
        System.out.println("=================================================================");
        System.out.println(" ScanSolver on Eternity II: " + count + " seeds at "
                           + budget + " nodes each");
        System.out.println(" slipSchedule=verhaard  shuffleStrength=" + strength);
        System.out.println("=================================================================");
        System.out.println(" seed    ms      nodes/sec    placed   edges   breaks");

        int[] placed = new int[count];
        int[] edges = new int[count];
        long totalMs = 0, totalNodes = 0;
        for (int i = 0; i < count; i++) {
            SolverConfig cfg = seedConfig(strength, i + 1);
            ScanSolver s = new ScanSolver(Instance.eternity2(), cfg);
            s.maxNodes = budget;
            long t0 = System.nanoTime();
            s.solve();
            long ms = (System.nanoTime() - t0) / 1000000L;
            placed[i] = s.bestPlaced;
            edges[i] = s.bestMatchedEdges;
            totalMs += ms;
            totalNodes += s.nodes;
            System.out.println(" " + pad("" + (i + 1), 7)
                + " " + pad("" + ms, 7)
                + " " + pad("" + (ms == 0 ? 0 : s.nodes * 1000L / ms), 12)
                + " " + pad(s.bestPlaced + "/256", 8)
                + " " + pad(s.bestMatchedEdges + "/480", 7)
                + " " + s.bestBreaks);
            String err = Validator.validatePartial(Instance.eternity2(), s.bestBoard,
                                                   false, s.bestBreaks);
            if (err != null) System.out.println("   INVALID BOARD: " + err);
        }

        SolverConfig plain = seedConfig(0, 0);
        ScanSolver fixed = new ScanSolver(Instance.eternity2(), plain);
        fixed.maxNodes = budget;
        long t0 = System.nanoTime();
        fixed.solve();
        long fixedMs = (System.nanoTime() - t0) / 1000000L;

        System.out.println();
        reportSpread("pieces placed", placed, fixed.bestPlaced);
        reportSpread("matched edges", edges, fixed.bestMatchedEdges);
        System.out.println();
        System.out.println(" unseeded run  : " + fixed.bestPlaced + "/256 pieces, "
            + fixed.bestMatchedEdges + "/480 edges, "
            + (fixedMs == 0 ? 0 : fixed.nodes * 1000L / fixedMs) + " nodes/sec");
        System.out.println(" seeded runs   : "
            + (totalMs == 0 ? 0 : totalNodes * 1000L / totalMs) + " nodes/sec on average");
        System.out.println(" best of " + count + "  : " + max(placed) + "/256 pieces, "
            + max(edges) + "/480 edges");
        System.out.println();
    }

    /** One seed's configuration: strongest schedule, everything else default. */
    private static SolverConfig seedConfig(int strength, long seed) {
        SolverConfig cfg = new SolverConfig();
        cfg.engine = SolverConfig.ENGINE_SCAN;
        cfg.slipSchedule = SolverConfig.SLIP_VERHAARD;
        cfg.shuffleStrength = strength;
        cfg.randomSeed = seed;
        return cfg;
    }

    /** min / median / max of a sample, and where one reference run falls in it. */
    private static void reportSpread(String label, int[] sample, int reference) {
        int[] sorted = new int[sample.length];
        System.arraycopy(sample, 0, sorted, 0, sample.length);
        java.util.Arrays.sort(sorted);
        int below = 0;
        for (int i = 0; i < sorted.length; i++) if (sorted[i] < reference) below++;
        System.out.println(" " + pad(label, 14)
            + " min " + pad("" + sorted[0], 6)
            + " median " + pad("" + sorted[sorted.length / 2], 6)
            + " max " + pad("" + sorted[sorted.length - 1], 6)
            + " spread " + pad("" + (sorted[sorted.length - 1] - sorted[0]), 6)
            + " unseeded beats " + below + "/" + sorted.length);
    }

    private static int max(int[] v) {
        int m = v[0];
        for (int i = 1; i < v.length; i++) if (v[i] > m) m = v[i];
        return m;
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
