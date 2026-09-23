package core;

/**
 * The seed, and what it is allowed to change.
 *
 * {@link ScanSolver} used to run one descent and repeat it for ever, so a
 * feature that made it vary would look exactly like a feature that did nothing
 * in every other test in the suite.  Both halves are therefore asserted here
 * and neither is enough on its own:
 *
 *   - a seed must be worth something: different seeds must reach different
 *     boards, and a restart must actually re-randomise rather than start the
 *     same descent again;
 *   - a seed must be worth nothing to the answer: the same seed must reproduce
 *     a run exactly, and an exhaustive enumeration must find the same
 *     solutions -- indeed visit the same number of nodes -- whatever the seed.
 *     Reordering a search may never change what exists.
 *
 * {@link CrossValidationTest} proves the unseeded engine agrees with
 * {@link RefSolver}; what is added here is that the seed cannot break that
 * agreement.
 */
public final class ScanVariationTest {

    public static void run() {
        T.section("ScanVariationTest: seeded candidate order");
        itIgnoresTheSeedUnlessAsked();
        itIsExactlyReproducible();
        itSendsDifferentSeedsDownDifferentTrees();
        itNeverChangesWhatExists();
        itReportsWhatTheRootOffers();
        T.endSection();

        T.section("ScanVariationTest: restarts");
        itRestartsOnItsSchedule();
        itKeepsTheBestBoardAcrossRestarts();
        itReRandomisesOnEveryRestart();
        itDoesNotRestartWithNothingToRandomise();
        itDoesNotRestartWhileEnumerating();
        T.endSection();
    }

    // -------------------------------------------------------------- off by default

    private static void itIgnoresTheSeedUnlessAsked() {
        ScanSolver plain = new ScanSolver(Instance.eternity2());
        T.check("the default engine is not seeded", !plain.seeded());

        SolverConfig a = eternity(0, 0);
        a.randomSeed = 7L;
        SolverConfig b = eternity(0, 0);
        b.randomSeed = 999999L;
        ScanSolver first = run(a, 300000L);
        ScanSolver second = run(b, 300000L);

        T.check("neither reports itself seeded with valueOrder and shuffleStrength left alone",
                !first.seeded() && !second.seeded());

        // This assertion used to read "the seed alone changes nothing", which was
        // true of this side alone.  Upstream's shuffleRuns makes the seed alone
        // reorder each key's candidate run, and that is deliberate: it is what
        // gives every PortfolioSearch worker a different descent from the same
        // configuration.  So two seeds must now differ, and the reproducibility
        // this file also asserts is anchored on a fixed seed rather than on the
        // seed being ignored.
        T.check("the seed alone now changes the descent, which is what a portfolio needs",
                !java.util.Arrays.equals(first.bestBoard, second.bestBoard)
                        || first.nodes != second.nodes);
    }

    // ------------------------------------------------------------------- the root

    /**
     * What the first square offers, which is the one place a seed provably
     * cannot reach: the seed permutes whole entries and a run of one entry has
     * nothing to permute.  The engine has to be able to say how many it holds,
     * because otherwise the claim can only be argued.
     *
     * The count is tied back to the search rather than left free-standing --
     * whatever the accessor reports, the variant the engine actually placed
     * first has to be one of the ones it offered.
     */
    private static void itReportsWhatTheRootOffers() {
        ScanSolver s = new ScanSolver(Instance.eternity2());
        s.maxNodes = 2000000L;
        s.solve();

        int[] root = s.rootCandidates();
        T.check("the first square offers at least one candidate", root.length > 0,
                "rootCandidates was empty");
        T.check("its entries never outnumber the variants they hold",
                s.rootEntryCount() <= root.length,
                "entries=" + s.rootEntryCount() + " variants=" + root.length);
        T.check("a run holding candidates is a run with entries in it",
                s.rootEntryCount() > 0, "entries=" + s.rootEntryCount());

        boolean offered = false;
        for (int i = 0; i < root.length; i++) {
            if (root[i] == s.bestOrderVariants[0]) offered = true;
        }
        T.check("the variant the search placed first is one the root offered",
                offered, "placed " + s.bestOrderVariants[0]
                       + ", root offered " + root.length + " variants");
    }

    // --------------------------------------------------------------- reproducible

    private static void itIsExactlyReproducible() {
        ScanSolver first = run(eternity(50, 4242L), 400000L);
        ScanSolver second = run(eternity(50, 4242L), 400000L);

        T.check("asking for a shuffle makes the engine seeded", first.seeded());
        T.eq("the same seed expands exactly the same number of nodes",
             first.nodes, second.nodes);
        T.eq("and reaches the same depth", first.bestPlaced, second.bestPlaced);
        T.eq("and the same score", first.bestMatchedEdges, second.bestMatchedEdges);
        T.eqIntArray("and the same board", first.bestBoard, second.bestBoard);
        T.check("the candidate index is laid out identically too",
                candidateSignature(first).equals(candidateSignature(second)));

        // reset() has to rebuild the order from scratch, not shuffle a
        // shuffled index, or a second run of one solver would drift.
        first.reset();
        first.solve();
        T.eqIntArray("re-running one solver gives the same board again",
                     second.bestBoard, first.bestBoard);
        T.eq("and the same node count", second.nodes, first.nodes);
    }

    // ------------------------------------------------------------------ variation

    private static void itSendsDifferentSeedsDownDifferentTrees() {
        int seeds = 6;
        String[] boards = new String[seeds];
        String[] orders = new String[seeds];
        for (int i = 0; i < seeds; i++) {
            ScanSolver s = run(eternity(50, 100L + i), 400000L);
            boards[i] = describe(s.bestBoard);
            orders[i] = candidateSignature(s);
        }

        T.eq("every seed lays the candidate index out differently",
             seeds, distinct(orders));
        T.eq("and every seed reaches a different board", seeds, distinct(boards));

        ScanSolver unseeded = run(eternity(0, 0), 400000L);
        boolean allDiffer = true;
        for (int i = 0; i < seeds; i++) {
            if (boards[i].equals(describe(unseeded.bestBoard))) allDiffer = false;
        }
        T.check("and none of them repeats the unseeded descent", allDiffer);
    }

    // --------------------------------------------------- what exists is not changed

    private static void itNeverChangesWhatExists() {
        int[] ns      = {  4,  4,  5 };
        int[] colours = {  2,  3,  4 };
        long[] gen    = { 11L, 12L, 21L };

        for (int i = 0; i < ns.length; i++) {
            Instance inst = Generator.generate(ns[i], colours[i], gen[i], true, false);
            String label = ns[i] + "x" + ns[i] + " colours=" + colours[i];

            SolutionCollector plainBoards = new SolutionCollector();
            ScanSolver plain = enumerate(inst, new SolverConfig(), plainBoards);
            long plainCount = plain.solutions;

            for (long seed = 1; seed <= 3; seed++) {
                SolverConfig cfg = new SolverConfig();
                cfg.valueOrder = SolverConfig.VALUE_RANDOM;
                cfg.shuffleStrength = 100;
                cfg.randomSeed = seed;
                SolutionCollector seededBoards = new SolutionCollector();
                ScanSolver seeded = enumerate(inst, cfg, seededBoards);

                T.eq("a seed cannot change how many solutions exist ("
                     + label + " seed=" + seed + ")", plainCount, seeded.solutions);
                T.check("nor which boards they are (" + label + " seed=" + seed + ")",
                        plainBoards.signature().equals(seededBoards.signature()),
                        "plain kept " + plainBoards.count()
                        + ", seeded kept " + seededBoards.count());
                // Enumerating everything visits the whole tree, and reordering
                // a tree does not resize it: an unequal node count would mean
                // the seed had pruned or invented a branch.
                T.eq("nor how big the search tree is (" + label + " seed=" + seed + ")",
                     plain.nodes, seeded.nodes);
            }

            SolverConfig reversed = new SolverConfig();
            reversed.valueOrder = SolverConfig.VALUE_REVERSE;
            SolutionCollector reversedBoards = new SolutionCollector();
            ScanSolver backwards = enumerate(inst, reversed, reversedBoards);
            T.check("and neither does trying the candidates backwards ("
                    + label + ")",
                    plainBoards.signature().equals(reversedBoards.signature())
                    && plain.nodes == backwards.nodes);
        }
    }

    // ------------------------------------------------------------------- restarts

    private static void itRestartsOnItsSchedule() {
        SolverConfig cfg = eternity(50, 31L);
        cfg.restartPolicy = SolverConfig.RESTART_FIXED;
        cfg.restartBase = 200000L;
        ScanSolver s = run(cfg, 1000000L);

        T.eq("a fixed policy restarts once per interval up to the budget", 4, s.restarts());
        T.check("and still stops dead on the budget", s.aborted && s.nodes <= 1000000L,
                "nodes=" + s.nodes);

        // Luby multiplies the same interval by 1,1,2,1,..., so 1M nodes is
        // spent as 200k + 200k + 400k and the fourth run hits the budget:
        // three restarts where the fixed policy managed four.
        SolverConfig lubyCfg = eternity(50, 31L);
        lubyCfg.restartPolicy = SolverConfig.RESTART_LUBY;
        lubyCfg.restartBase = 200000L;
        ScanSolver luby = run(lubyCfg, 1000000L);
        T.eq("the Luby schedule spends the same budget in longer and longer runs",
             3, luby.restarts());
    }

    private static void itKeepsTheBestBoardAcrossRestarts() {
        SolverConfig once = eternity(50, 31L);
        ScanSolver first = run(once, 200000L);

        SolverConfig restarting = eternity(50, 31L);
        restarting.restartPolicy = SolverConfig.RESTART_FIXED;
        restarting.restartBase = 200000L;
        ScanSolver many = run(restarting, 1000000L);

        T.check("a restart never loses the best board found before it",
                many.bestPlaced > first.bestPlaced
                || (many.bestPlaced == first.bestPlaced
                    && many.bestMatchedEdges >= first.bestMatchedEdges),
                "first run reached " + first.bestPlaced + "/" + first.bestMatchedEdges
                + ", after restarts " + many.bestPlaced + "/" + many.bestMatchedEdges);
        T.isNull("and the board it ends up holding is still a legal one",
                 Validator.validatePartial(Instance.eternity2(), many.bestBoard,
                                           false, many.bestBreaks));
    }

    private static void itReRandomisesOnEveryRestart() {
        SolverConfig cfg = eternity(50, 77L);
        cfg.restartPolicy = SolverConfig.RESTART_FIXED;
        cfg.restartBase = 200000L;
        ScanSolver s = new ScanSolver(Instance.eternity2(), cfg);
        s.maxNodes = 1000000L;
        OrderWatcher watcher = new OrderWatcher();
        s.setSampleEveryNodes(Long.MAX_VALUE);
        s.setListener(watcher);
        String before = candidateSignature(s);
        s.solve();

        T.eq("every restart was observed", s.restarts(), watcher.count);
        T.check("and each one laid the candidate index out afresh",
                watcher.count > 0 && watcher.distinct() == watcher.count
                              && !watcher.sawAgain(before),
                "restarts=" + watcher.count + " distinct orders=" + watcher.distinct());
    }

    private static void itDoesNotRestartWithNothingToRandomise() {
        SolverConfig cfg = eternity(0, 0);
        cfg.restartPolicy = SolverConfig.RESTART_FIXED;
        cfg.restartBase = 200000L;
        ScanSolver s = run(cfg, 1000000L);

        T.eq("an unseeded engine does not restart, having nothing to change",
             0, s.restarts());
        T.eqIntArray("it just runs once, like the plain engine",
                     run(eternity(0, 0), 1000000L).bestBoard, s.bestBoard);
    }

    private static void itDoesNotRestartWhileEnumerating() {
        Instance inst = Generator.generate(4, 3, 12L, true, false);
        SolverConfig cfg = new SolverConfig();
        cfg.valueOrder = SolverConfig.VALUE_RANDOM;
        cfg.randomSeed = 5L;
        cfg.restartPolicy = SolverConfig.RESTART_FIXED;
        cfg.restartBase = 100L;
        SolutionCollector boards = new SolutionCollector();
        ScanSolver s = enumerate(inst, cfg, boards);

        SolutionCollector reference = new SolutionCollector();
        ScanSolver plain = enumerate(inst, new SolverConfig(), reference);

        T.eq("an exhaustive run never restarts, which would double-count",
             0, s.restarts());
        T.eq("so it still finds every solution exactly once",
             plain.solutions, s.solutions);
        T.check("and finds the same ones",
                reference.signature().equals(boards.signature()));
    }

    // -------------------------------------------------------------------- helpers

    /** The real puzzle, slipping on, with the given shuffle strength and seed. */
    private static SolverConfig eternity(int strength, long seed) {
        SolverConfig cfg = new SolverConfig();
        cfg.engine = SolverConfig.ENGINE_SCAN;
        cfg.slipSchedule = SolverConfig.SLIP_VERHAARD;
        cfg.shuffleStrength = strength;
        cfg.randomSeed = seed;
        return cfg;
    }

    private static ScanSolver run(SolverConfig cfg, long budget) {
        ScanSolver s = new ScanSolver(Instance.eternity2(), cfg);
        s.maxNodes = budget;
        s.solve();
        return s;
    }

    private static ScanSolver enumerate(Instance inst, SolverConfig cfg,
                                        SolutionCollector into) {
        ScanSolver s = new ScanSolver(inst, cfg);
        s.stopAtFirstSolution = false;
        s.maxNodes = 60000000L;
        s.sampleEveryNodes = Long.MAX_VALUE;
        s.listener = into;
        s.solve();
        return s;
    }

    /**
     * The order the index will offer candidates in, sampled across the board.
     *
     * A key is (cell class, top colour, left colour), so walking a few depths
     * and a few colour pairs reads a spread of classes without printing all
     * 3703 buckets.
     */
    private static String candidateSignature(ScanSolver s) {
        StringBuilder sb = new StringBuilder();
        for (int depth = 0; depth < s.cellTotal(); depth += 7) {
            for (int top = 1; top < 22; top += 5) {
                for (int left = 1; left < 22; left += 5) {
                    int[] order = s.candidateOrderAtDepth(depth, left, top);
                    for (int i = 0; i < order.length; i++) sb.append(order[i]).append(',');
                    sb.append(';');
                }
            }
        }
        return sb.toString();
    }

    private static String describe(int[] board) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < board.length; i++) sb.append(board[i]).append(',');
        return sb.toString();
    }

    private static int distinct(String[] values) {
        int count = 0;
        for (int i = 0; i < values.length; i++) {
            boolean seen = false;
            for (int j = 0; j < i; j++) if (values[j].equals(values[i])) seen = true;
            if (!seen) count++;
        }
        return count;
    }

    /** Records the candidate order the engine starts each restart with. */
    private static final class OrderWatcher implements SolveListener {
        int count;
        String[] orders = new String[64];

        public void onNewBest(Search s) { }
        public void onSample(Search s) { }
        public void onSolution(Search s) { }

        public void onRestart(Search s, int restartIndex) {
            if (count < orders.length) orders[count] = candidateSignature((ScanSolver) s);
            count++;
        }

        int distinct() {
            String[] seen = new String[count];
            System.arraycopy(orders, 0, seen, 0, count);
            return ScanVariationTest.distinct(seen);
        }

        boolean sawAgain(String order) {
            for (int i = 0; i < count; i++) if (order.equals(orders[i])) return true;
            return false;
        }
    }
}
