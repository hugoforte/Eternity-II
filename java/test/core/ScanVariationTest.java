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
        itLetsTheSeedChooseTheOpening();
        itChangesNothingButTheOpening();
        itKeepsItsOpeningAcrossAReset();
        itLeavesEveryKeyBelowTheRootWhereMainHasIt();
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
     * What the first square offers, tied back to the search rather than left
     * free-standing: whatever the accessor reports, the variant the engine
     * actually placed first has to be one of the ones it offered.
     */
    private static void itReportsWhatTheRootOffers() {
        ScanSolver s = new ScanSolver(Instance.eternity2());
        s.maxNodes = 2000000L;
        s.solve();

        int[] root = s.rootCandidates();
        T.check("the first square offers at least one candidate", root.length > 0,
                "rootCandidates was empty");

        boolean offered = false;
        for (int i = 0; i < root.length; i++) {
            if (root[i] == s.bestOrderVariants[0]) offered = true;
        }
        T.check("the variant the search placed first is one the root offered",
                offered, "placed " + s.bestOrderVariants[0]
                       + ", root offered " + root.length + " variants");
    }

    /**
     * The four openings share a word, so a seed reaches them only because the
     * root is held one entry per variant -- and under the quota gate a seed on
     * its own reaches nothing else.  Checked on the configuration the long runs
     * actually use.
     */
    private static void itLetsTheSeedChooseTheOpening() {
        int[] natural = sorted(new ScanSolver(Instance.eternity2(), longRun(0L)).rootCandidates());

        boolean[] opened = new boolean[1024];
        int distinct = 0;
        boolean sameSet = true;
        for (long seed = 1L; seed <= 16L; seed++) {
            int[] root = new ScanSolver(Instance.eternity2(), longRun(seed)).rootCandidates();
            if (!opened[root[0]]) { opened[root[0]] = true; distinct++; }
            if (!java.util.Arrays.equals(sorted(root), natural)) sameSet = false;
        }

        T.check("under the quota gate, a seed on its own now reaches the opening move",
                distinct > 1, "sixteen seeds all opened with " + natural[0]);
        T.eq("and sixteen seeds between them use every opening the root offers",
             natural.length, distinct);
        T.check("while no seed adds or removes an opening", sameSet);
    }

    /**
     * The property that makes a seeded attempt a clean test of diversity at
     * the root: the seed moves the opening and nothing else.  A seed that
     * happens to keep the natural opening must then search exactly as the
     * unseeded engine does -- if any key below the root had been reordered,
     * the two boards would part company.
     */
    private static void itChangesNothingButTheOpening() {
        ScanSolver unseeded = run(longRun(0L), 300000L);
        int naturalOpening = unseeded.rootCandidates()[0];

        long keeps = 0L, moves = 0L;
        for (long seed = 1L; seed <= 64L && (keeps == 0L || moves == 0L); seed++) {
            int opening = new ScanSolver(Instance.eternity2(), longRun(seed)).rootCandidates()[0];
            if (opening == naturalOpening && keeps == 0L) keeps = seed;
            if (opening != naturalOpening && moves == 0L) moves = seed;
        }
        T.check("some seed in 64 keeps the natural opening", keeps != 0L);
        T.check("and some seed moves it", moves != 0L);
        if (keeps == 0L || moves == 0L) return;

        ScanSolver same = run(longRun(keeps), 300000L);
        T.eqIntArray("a seed that keeps the opening searches exactly as the unseeded engine does",
                     unseeded.bestBoard, same.bestBoard);
        T.eq("down to its error-free reach", unseeded.deepestErrorFree, same.deepestErrorFree);

        ScanSolver other = run(longRun(moves), 300000L);
        T.eq("an attempt opens with the candidate its root offers first",
             other.rootCandidates()[0], other.bestOrderVariants[0]);
        T.check("and a seed that moves the opening reaches a different board",
                !java.util.Arrays.equals(unseeded.bestBoard, other.bestBoard));
    }

    /**
     * The order of every key below the root is exactly the order main's engine
     * lays out for the same configuration, pinned by a hash of every perfect
     * run the engine reports at every depth and colour pair except the root's.
     *
     * This is the half of "a seed changes the opening and nothing else" that
     * the other checks cannot see.  orderRun and shuffleRuns share a random
     * stream across every key, and the root skips them, so a skipped root that
     * forgot to take its draws would shift every key after it -- which is
     * what happens under a triple whose counts split the corners, and why two
     * of the four cases use one.  Updating a constant here means every seeded
     * measurement made before stops reproducing, so it takes a reason.
     */
    private static void itLeavesEveryKeyBelowTheRootWhereMainHasIt() {
        T.eq("shuffleStrength under the 1,7,10 quota, seed 0",
             0x48417207b5d2cdafL, belowRoot(seeded("1,7,10", false, 50, 0L)));
        T.eq("shuffleStrength under the 1,7,10 quota, seed 3",
             0xa9334c7c0fa8446bL, belowRoot(seeded("1,7,10", false, 50, 3L)));
        T.eq("valueOrder=random under the 14,22,5 quota, seed 3",
             0x1c245a0e05f04619L, belowRoot(seeded("14,22,5", true, 0, 3L)));
        T.eq("no quota, a seed alone",
             0x54cad8e86a6205ffL, belowRoot(seeded(null, false, 0, 5L)));
    }

    /** reset() has to rebuild the opening order from scratch, not reshuffle it. */
    private static void itKeepsItsOpeningAcrossAReset() {
        ScanSolver s = new ScanSolver(Instance.eternity2(), longRun(7L));
        int[] before = s.rootCandidates();
        s.reset();
        T.eqIntArray("a reset leaves the seeded opening order exactly as it was",
                     before, s.rootCandidates());
        T.eqIntArray("and a second solver with the same seed lays it out the same way",
                     before, new ScanSolver(Instance.eternity2(), longRun(7L)).rootCandidates());
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

    /**
     * The configuration the long runs use: Verhaard's ceiling with a tail
     * allowance, under Blackwood's quota, and no value-order setting -- so a
     * seed on its own reorders the opening and nothing below it.
     */
    private static SolverConfig longRun(long seed) {
        SolverConfig cfg = new SolverConfig();
        cfg.engine = SolverConfig.ENGINE_SCAN;
        cfg.slipSchedule = SolverConfig.SLIP_VERHAARD;
        cfg.quotaSchedule = SolverConfig.QUOTA_BLACKWOOD;
        cfg.tailFromDepth = 244;
        cfg.tailBreakBonus = 1;
        cfg.randomSeed = seed;
        return cfg;
    }

    private static SolverConfig seeded(String quotaColours, boolean random,
                                       int strength, long seed) {
        SolverConfig cfg = new SolverConfig();
        cfg.engine = SolverConfig.ENGINE_SCAN;
        cfg.slipSchedule = SolverConfig.SLIP_VERHAARD;
        if (quotaColours != null) {
            cfg.quotaSchedule = SolverConfig.QUOTA_BLACKWOOD;
            cfg.quotaColours = quotaColours;
        }
        if (random) cfg.valueOrder = SolverConfig.VALUE_RANDOM;
        cfg.shuffleStrength = strength;
        cfg.randomSeed = seed;
        return cfg;
    }

    /**
     * An FNV-1a hash of every perfect run below the root, in the order the
     * search reads it.  The root is the one key with both sides grey.
     */
    private static long belowRoot(SolverConfig cfg) {
        Instance inst = Instance.eternity2();
        ScanSolver s = new ScanSolver(inst, cfg);
        long h = 0xcbf29ce484222325L;
        for (int depth = 0; depth < s.cellTotal(); depth++) {
            for (int top = 0; top < inst.numColours; top++) {
                for (int left = 0; left < inst.numColours; left++) {
                    if (top == 0 && left == 0) continue;
                    int[] order = s.candidateOrderAtDepth(depth, left, top);
                    for (int i = 0; i < order.length; i++) h = (h ^ order[i]) * 0x100000001b3L;
                    h = (h ^ -1L) * 0x100000001b3L;
                }
            }
        }
        return h;
    }

    private static int[] sorted(int[] values) {
        int[] copy = values.clone();
        java.util.Arrays.sort(copy);
        return copy;
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
