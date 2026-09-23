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
        itLaysEveryOtherKeyOutAsMainDoes();
        T.endSection();

        T.section("ScanVariationTest: restarts");
        itRestartsOnItsSchedule();
        itKeepsTheBestBoardAcrossRestarts();
        itReRandomisesOnEveryRestart();
        itReopensOnARestart();
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
     * The four openings share a word, so a seed reaches them only because a
     * seeded engine holds the root one entry per variant -- and under the quota
     * gate a seed on its own reaches nothing else.  Checked on the configuration the long runs
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
     * Every key the search reads is laid out exactly as main's engine lays it
     * out, pinned by a hash of every perfect run the engine reports.  A seeded
     * engine hashes from depth 1, because depth 0 reads the private root class
     * a seed exists to reorder; an unseeded one hashes from depth 0, because
     * nothing about it may differ from main at all.  The both-grey key is
     * included at every depth: below the root it is a real key, the corners of
     * the interior class, and the search reads it when grey pruning is off.
     *
     * Eternity II's root is one word; the generated 6x6's spans several, which
     * is the case where holding the root differently could reach the reverse,
     * random and shuffleStrength orders even without a seed.  The constants
     * were captured from main's engine -- changing one means every measurement
     * made before stops reproducing, so it takes a reason.
     */
    private static void itLaysEveryOtherKeyOutAsMainDoes() {
        Instance e2 = Instance.eternity2();
        Instance six = Generator.generate(6, 4, 11L, true, false);
        int natural = SolverConfig.VALUE_NATURAL;
        int random = SolverConfig.VALUE_RANDOM;
        int reverse = SolverConfig.VALUE_REVERSE;

        T.eq("Eternity II, shuffleStrength under the 1,7,10 quota, seed 0",
             0x820d6f590be8d0deL, layout(e2, seeded("1,7,10", natural, 50, 0L), 1));
        T.eq("Eternity II, shuffleStrength under the 1,7,10 quota, seed 3",
             0x140ff43c45cd772aL, layout(e2, seeded("1,7,10", natural, 50, 3L), 1));
        T.eq("Eternity II, valueOrder=random under the 14,22,5 quota, seed 3",
             0x8c23655b84ec02e4L, layout(e2, seeded("14,22,5", random, 0, 3L), 1));
        T.eq("Eternity II, no quota, a seed alone",
             0x7eb9110ebefb65faL, layout(e2, seeded(null, natural, 0, 5L), 1));
        T.eq("a multi-word root, valueOrder=reverse, no seed, depth 0 included",
             0xfa8df3589f941bafL, layout(six, seeded(null, reverse, 0, 0L), 0));
        T.eq("a multi-word root, valueOrder=random, no seed, depth 0 included",
             0x530860105b64655bL, layout(six, seeded(null, random, 0, 0L), 0));
        T.eq("a multi-word root, shuffleStrength, no seed, depth 0 included",
             0x0f5705db10c04d63L, layout(six, seeded(null, natural, 50, 0L), 0));
        T.eq("a multi-word root, shuffleStrength, seed 3",
             0xe73badfd8030ae4fL, layout(six, seeded(null, natural, 50, 3L), 1));
        T.eq("a multi-word root, valueOrder=random, seed 3",
             0x55ade413569296a9L, layout(six, seeded(null, random, 0, 3L), 1));
        T.eq("a multi-word root, a seed alone",
             0xe894e7b5ff210d1dL, layout(six, seeded(null, natural, 0, 3L), 1));

        // The slipped runs have no accessor, so they are pinned by what the
        // search does with them: an unseeded run that spends breaks.
        ScanSolver s = new ScanSolver(e2, seeded(null, natural, 50, 0L));
        s.maxNodes = 3000000L;
        s.solve();
        T.check("the pinned run reaches the slipped runs", s.bestBreaks > 0,
                "breaks=" + s.bestBreaks);
        T.eq("and places exactly the board main's engine places",
             0xa4c18c75L, java.util.Arrays.hashCode(s.bestBoard) & 0xffffffffL);
    }

    /**
     * A restart lays the index out afresh, and a seeded root is part of that:
     * the root has to be laid out anew.  Everything else has to be laid out
     * exactly as main lays it out at the same restart -- the root's private
     * class takes no draws from the stream the real keys share, so nothing it
     * does can reach the restart after it.  Pinned across all nine restarts.
     */
    private static void itReopensOnARestart() {
        SolverConfig cfg = eternity(50, 77L);
        cfg.restartPolicy = SolverConfig.RESTART_FIXED;
        cfg.restartBase = 200000L;
        ScanSolver s = new ScanSolver(Instance.eternity2(), cfg);
        s.maxNodes = 2000000L;
        s.setSampleEveryNodes(Long.MAX_VALUE);
        OpeningWatcher watcher = new OpeningWatcher(s.rootCandidates());
        s.setListener(watcher);
        s.solve();

        T.check("the engine restarted several times", s.restarts() >= 4,
                "restarts=" + s.restarts());
        T.check("and the root was laid out afresh on at least one of them",
                watcher.changed, "every restart kept the opening order it started with");
        T.eq("while every other key was laid out as main lays it out, restart by restart",
             0xe17dae47d1b73d81L, watcher.layouts);
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

    private static SolverConfig seeded(String quotaColours, int valueOrder,
                                       int strength, long seed) {
        SolverConfig cfg = new SolverConfig();
        cfg.engine = SolverConfig.ENGINE_SCAN;
        cfg.slipSchedule = SolverConfig.SLIP_VERHAARD;
        if (quotaColours != null) {
            cfg.quotaSchedule = SolverConfig.QUOTA_BLACKWOOD;
            cfg.quotaColours = quotaColours;
        }
        cfg.valueOrder = valueOrder;
        cfg.shuffleStrength = strength;
        cfg.randomSeed = seed;
        return cfg;
    }

    /**
     * An FNV-1a hash of every perfect run the engine reports from
     * {@code fromDepth} on, at every colour pair, in the order the search
     * reads it.
     */
    private static long layout(Instance inst, SolverConfig cfg, int fromDepth) {
        return layoutOf(new ScanSolver(inst, cfg), inst.numColours, fromDepth);
    }

    private static long layoutOf(ScanSolver s, int colours, int fromDepth) {
        long h = 0xcbf29ce484222325L;
        for (int depth = fromDepth; depth < s.cellTotal(); depth++) {
            for (int top = 0; top < colours; top++) {
                for (int left = 0; left < colours; left++) {
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

    /**
     * Whether any restart laid the root out in an order other than the first,
     * and a hash of how every other key was laid out at each restart.
     */
    private static final class OpeningWatcher implements SolveListener {
        private final int[] first;
        boolean changed;
        long layouts = 0xcbf29ce484222325L;

        OpeningWatcher(int[] first) { this.first = first; }

        public void onNewBest(Search s) { }
        public void onSample(Search s) { }
        public void onSolution(Search s) { }

        public void onRestart(Search s, int restartIndex) {
            ScanSolver solver = (ScanSolver) s;
            if (!java.util.Arrays.equals(first, solver.rootCandidates())) changed = true;
            layouts = (layouts ^ layoutOf(solver, Instance.eternity2().numColours, 1))
                    * 0x100000001b3L;
        }
    }
}
