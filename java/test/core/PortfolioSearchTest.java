package core;

/**
 * {@link PortfolioSearch} runs several seeded {@link ScanSolver} workers at
 * once and reports whichever does best. What matters here is the aggregation
 * itself -- that the reported board is never worse than running just one of
 * its own workers, that node counts are genuinely summed, and that a solve
 * still validates -- not the search itself, which {@link ScanSolverTest} and
 * {@link CrossValidationTest} already cover.
 */
public final class PortfolioSearchTest {

    public static void run() {
        T.section("PortfolioSearchTest: several seeded workers, best result wins");

        itNeverDoesWorseThanItsOwnFirstWorker();
        itSumsNodesAcrossEveryWorker();
        itSolvesGeneratedInstances();
        itIsRepeatablePerSeed();

        T.endSection();
    }

    // ------------------------------------------------------- result quality

    private static void itNeverDoesWorseThanItsOwnFirstWorker() {
        // Worker 0 of an N-worker portfolio is built from exactly the same
        // seed as the lone worker of a 1-worker portfolio, so the N-worker
        // result -- the best across a strict superset of that computation --
        // can never score lower.
        Instance inst = Instance.eternity2();
        SolverConfig cfg = new SolverConfig();
        cfg.engine = SolverConfig.ENGINE_SCAN;
        cfg.nodeBudget = 500000L;

        PortfolioSearch solo = new PortfolioSearch(inst, cfg, 1);
        solo.solve();

        PortfolioSearch team = new PortfolioSearch(inst, cfg, 4);
        team.solve();

        // The same (placed, edges) lexicographic order ScanSolver.recordBest
        // uses internally to decide "is this a new best".
        boolean atLeastAsGood = team.bestPlaced() > solo.bestPlaced()
            || (team.bestPlaced() == solo.bestPlaced()
                && team.bestMatchedEdges() >= solo.bestMatchedEdges());
        T.check("four workers never report a worse board than one",
                atLeastAsGood,
                "solo=" + solo.bestPlaced() + "/" + solo.bestMatchedEdges()
                    + " team=" + team.bestPlaced() + "/" + team.bestMatchedEdges());
    }

    // -------------------------------------------------------------- nodes

    private static void itSumsNodesAcrossEveryWorker() {
        // A budget far below what it takes to place every piece on the real
        // puzzle guarantees every worker runs to its own cap rather than
        // solving early, so the total is exactly workers * nodeBudget.
        Instance inst = Instance.eternity2();
        SolverConfig cfg = new SolverConfig();
        cfg.engine = SolverConfig.ENGINE_SCAN;
        cfg.nodeBudget = 20000L;

        PortfolioSearch team = new PortfolioSearch(inst, cfg, 5);
        team.solve();

        T.eq("total nodes is exactly workers * nodeBudget when nobody solves",
             5L * 20000L, team.nodes());
        T.check("the portfolio reports itself as budget-bound", team.aborted());
    }

    // --------------------------------------------------------------- solving

    private static void itSolvesGeneratedInstances() {
        Instance inst = Generator.generate(6, 6, 501L, true, true);
        SolverConfig cfg = new SolverConfig();
        cfg.engine = SolverConfig.ENGINE_SCAN;
        cfg.nodeBudget = 20000000L;

        PortfolioSearch team = new PortfolioSearch(inst, cfg, 4);
        team.setStopAtFirstSolution(true);
        long found = team.solve();

        T.check("a multi-worker portfolio finds a solution on a solvable instance",
                found >= 1, "aborted=" + team.aborted());
        if (found >= 1) {
            T.isNull("that solution validates",
                     Validator.validateComplete(inst, team.solutionBoard()));
            T.check("the reported result carries no breaks", team.bestBreaks() == 0);
            T.check("the portfolio does not report itself as budget-bound",
                    !team.aborted());
        }
    }

    // ----------------------------------------------------------- repeatability

    private static void itIsRepeatablePerSeed() {
        // Same base seed, same worker count -> the same per-worker seeds
        // (see PortfolioSearch.mixSeed), and ScanSolver is itself deterministic
        // per seed, so two runs must agree exactly.
        Instance inst = Instance.eternity2();
        SolverConfig cfg = new SolverConfig();
        cfg.engine = SolverConfig.ENGINE_SCAN;
        cfg.nodeBudget = 300000L;
        cfg.randomSeed = 777L;

        PortfolioSearch a = new PortfolioSearch(inst, cfg, 3);
        a.solve();
        PortfolioSearch b = new PortfolioSearch(inst, cfg, 3);
        b.solve();

        T.eq("the same seed and worker count reproduce the same node total",
             a.nodes(), b.nodes());
        T.eq("...and the same best board", a.bestPlaced(), b.bestPlaced());
        T.eqIntArray("...exactly", a.bestBoard(), b.bestBoard());
    }
}
