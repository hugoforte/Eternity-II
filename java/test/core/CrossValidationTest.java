package core;

/**
 * The strongest correctness evidence in the suite: the optimised
 * {@link MrvSolver} is made to enumerate EVERY solution of small instances and
 * the totals are compared against the deliberately naive {@link RefSolver}.
 *
 * If MRV ordering, the bitset pruning, the "no grey in the interior"
 * assumption or the rotation de-duplication were wrong in any way, the counts
 * would diverge.
 */
public final class CrossValidationTest {

    public static void run() {
        T.section("CrossValidationTest: MrvSolver vs naive RefSolver (exhaustive counts)");

        // 3x3 and 4x4 boards over a few colour counts and seeds.  Small colour
        // counts deliberately produce instances with MANY solutions, which
        // exercises the enumeration path hard.
        int[] ns        = { 3, 3, 3, 4, 4, 4, 4, 5, 5 };
        int[] colours   = { 1, 2, 3, 2, 3, 3, 4, 4, 5 };
        long[] seeds    = { 1L, 2L, 3L, 11L, 12L, 13L, 14L, 21L, 22L };
        boolean[] fixed = { false, false, true, false, false, true, false, false, true };

        for (int i = 0; i < ns.length; i++) {
            Instance inst = Generator.generate(ns[i], colours[i], seeds[i], true, fixed[i]);

            String label = ns[i] + "x" + ns[i] + " colours=" + colours[i]
                         + " seed=" + seeds[i] + (fixed[i] ? " +fixed" : "");

            // the generator must produce instances that satisfy the grey budget
            T.check("generated instance has an exact grey budget (" + label + ")",
                    inst.greyBudgetIsExact(),
                    "greySides=" + inst.greySideCount() + " 4n=" + (4 * inst.n));

            RefSolver ref = new RefSolver(inst);
            ref.stopAtFirstSolution = false;
            ref.maxNodes = 60000000L;
            long refCount = ref.solve();

            MrvSolver mrv = new MrvSolver(inst);
            mrv.stopAtFirstSolution = false;
            mrv.maxNodes = 60000000L;
            long mrvCount = mrv.solve();

            boolean neitherAborted = !ref.aborted && !mrv.aborted;
            T.check("neither solver aborted (" + label + ")", neitherAborted,
                    "refAborted=" + ref.aborted + " mrvAborted=" + mrv.aborted);

            T.check("exhaustive solution counts agree (" + label + ")",
                    refCount == mrvCount,
                    "ref=" + refCount + " mrv=" + mrvCount);

            T.check("at least one solution exists (" + label + ")", refCount >= 1,
                    "ref=" + refCount);

            System.out.println("         " + label
                               + ": solutions=" + refCount
                               + "  refNodes=" + ref.nodes
                               + "  mrvNodes=" + mrv.nodes);

            // both solvers' reported solutions must validate
            if (refCount >= 1) {
                T.isNull("RefSolver solution validates (" + label + ")",
                         Validator.validateComplete(inst, ref.solutionBoard));
                T.isNull("MrvSolver solution validates (" + label + ")",
                         Validator.validateComplete(inst, mrv.solutionBoard));
            }
        }

        // --- unsatisfiable instances: both must report zero -------------------
        Instance base = Generator.generate(4, 3, 555L, true, false);
        int[][] broken = base.piecesCopy();
        broken[5][0] = 0; broken[5][1] = 0; broken[5][2] = 0; broken[5][3] = 0;
        Instance bad = new Instance(4, broken, null, null, null);

        RefSolver rb = new RefSolver(bad);
        rb.stopAtFirstSolution = false;
        rb.maxNodes = 60000000L;
        long rbc = rb.solve();

        MrvSolver mb = new MrvSolver(bad);
        mb.stopAtFirstSolution = false;
        mb.maxNodes = 60000000L;
        long mbc = mb.solve();

        T.eq("RefSolver reports 0 solutions for the broken instance", 0, rbc);
        T.eq("MrvSolver reports 0 solutions for the broken instance", 0, mbc);
        T.check("neither solver aborted on the broken instance", !rb.aborted && !mb.aborted);

        // --- MRV should be dramatically cheaper than the naive solver ---------
        long refTotal = 0, mrvTotal = 0;
        for (int seed = 1; seed <= 3; seed++) {
            Instance inst = Generator.generate(5, 4, 3000L + seed, true, false);
            RefSolver r = new RefSolver(inst);
            r.stopAtFirstSolution = true;
            r.maxNodes = 60000000L;
            r.solve();
            MrvSolver m = new MrvSolver(inst);
            m.stopAtFirstSolution = true;
            m.maxNodes = 60000000L;
            m.solve();
            refTotal += r.nodes;
            mrvTotal += m.nodes;
        }
        System.out.println("         nodes to first solution over 3 5x5 instances:"
                           + " ref=" + refTotal + " mrv=" + mrvTotal);
        T.check("MrvSolver needs fewer nodes than the naive reference solver",
                mrvTotal <= refTotal, "ref=" + refTotal + " mrv=" + mrvTotal);

        T.endSection();
    }
}
