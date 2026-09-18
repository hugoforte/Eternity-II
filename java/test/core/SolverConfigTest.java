package core;

/**
 * Tests for the newly configurable solver options.
 *
 * Two invariants do most of the work here:
 *
 *   - Forward checking only prunes branches that cannot contain a solution, so
 *     changing cfg.forwardCheck must NOT change the number of solutions found
 *     when enumerating exhaustively.
 *   - "No grey in the interior" is a derived consequence of the rules on any
 *     instance with an exact grey budget, so toggling that pruning must not
 *     change the solution count either.
 *
 * Those two catch almost any mistake in the new option plumbing.
 */
public final class SolverConfigTest {

    public static void run() {
        T.section("SolverConfigTest: parsing and serialisation");
        parsingTest();
        T.endSection();

        T.section("SolverConfigTest: every option still solves correctly");
        optionsSolveTest();
        T.endSection();

        T.section("SolverConfigTest: option invariants (solution counts)");
        invariantTest();
        T.endSection();

        T.section("SolverConfigTest: budgets, restarts, caps, determinism");
        budgetsAndRestartsTest();
        T.endSection();
    }

    // ------------------------------------------------------------------ parsing

    private static void parsingTest() {
        SolverConfig c = new SolverConfig();

        // defaults must reproduce the previously hard-coded behaviour
        T.eq("default cellOrder is MRV", SolverConfig.CELL_MRV, c.cellOrder);
        T.eq("default tieBreak is mostNeighbours",
             SolverConfig.TIE_MOST_NEIGHBOURS, c.tieBreak);
        T.eq("default valueOrder is natural", SolverConfig.VALUE_NATURAL, c.valueOrder);
        T.eq("default forwardCheck is fullBoard", SolverConfig.FC_FULL_BOARD, c.forwardCheck);
        T.eq("default restartPolicy is none", SolverConfig.RESTART_NONE, c.restartPolicy);
        T.eq("default shuffleStrength is 0", 0, c.shuffleStrength);
        T.check("default greyInteriorPruning is on", c.greyInteriorPruning);
        T.eq("default startCell is auto", SolverConfig.START_AUTO, c.startCell);

        // enum name round trips
        String[] cellNames = { "mrv", "hybrid", "rowMajor" };
        boolean cellOk = true;
        for (int i = 0; i < cellNames.length; i++) {
            if (!SolverConfig.cellOrderName(SolverConfig.parseCellOrder(cellNames[i]))
                    .equals(cellNames[i])) cellOk = false;
        }
        T.check("cellOrder names round-trip", cellOk);

        String[] tieNames = { "mostNeighbours", "fewestNeighbours", "lowestIndex",
                              "nearestFixed", "nearestCentre" };
        boolean tieOk = true;
        for (int i = 0; i < tieNames.length; i++) {
            if (!SolverConfig.tieBreakName(SolverConfig.parseTieBreak(tieNames[i]))
                    .equals(tieNames[i])) tieOk = false;
        }
        T.check("tieBreak names round-trip", tieOk);

        String[] valNames = { "natural", "reverse", "random", "rarestColour" };
        boolean valOk = true;
        for (int i = 0; i < valNames.length; i++) {
            if (!SolverConfig.valueOrderName(SolverConfig.parseValueOrder(valNames[i]))
                    .equals(valNames[i])) valOk = false;
        }
        T.check("valueOrder names round-trip", valOk);

        String[] fcNames = { "none", "neighbours", "fullBoard" };
        boolean fcOk = true;
        for (int i = 0; i < fcNames.length; i++) {
            if (!SolverConfig.forwardCheckName(SolverConfig.parseForwardCheck(fcNames[i]))
                    .equals(fcNames[i])) fcOk = false;
        }
        T.check("forwardCheck names round-trip", fcOk);

        String[] rsNames = { "none", "fixed", "geometric", "luby" };
        boolean rsOk = true;
        for (int i = 0; i < rsNames.length; i++) {
            if (!SolverConfig.restartPolicyName(SolverConfig.parseRestartPolicy(rsNames[i]))
                    .equals(rsNames[i])) rsOk = false;
        }
        T.check("restartPolicy names round-trip", rsOk);

        String[] scNames = { "auto", "topLeft", "topRight", "bottomLeft",
                             "bottomRight", "centre" };
        boolean scOk = true;
        for (int i = 0; i < scNames.length; i++) {
            if (!SolverConfig.startCellName(SolverConfig.parseStartCell(scNames[i]))
                    .equals(scNames[i])) scOk = false;
        }
        T.check("startCell names round-trip", scOk);

        // unknown names fall back to the default rather than throwing
        T.eq("unknown cellOrder falls back to MRV",
             SolverConfig.CELL_MRV, SolverConfig.parseCellOrder("nonsense"));
        T.eq("null cellOrder falls back to MRV",
             SolverConfig.CELL_MRV, SolverConfig.parseCellOrder(null));

        // applyArg
        SolverConfig d = new SolverConfig();
        d.applyArg("--cellOrder=rowMajor");
        d.applyArg("--tieBreak=lowestIndex");
        d.applyArg("--valueOrder=random");
        d.applyArg("--shuffleStrength=55");
        d.applyArg("--randomSeed=999");
        d.applyArg("--forwardCheck=neighbours");
        d.applyArg("--greyInteriorPruning=false");
        d.applyArg("--candidateCap=7");
        d.applyArg("--restartPolicy=luby");
        d.applyArg("--restartBase=4242");
        d.applyArg("--restartMultiplier=250");
        d.applyArg("--nodeBudget=123456");
        d.applyArg("--startCell=centre");
        d.applyArg("--hybridThreshold=9");
        T.eq("applyArg cellOrder", SolverConfig.CELL_ROW_MAJOR, d.cellOrder);
        T.eq("applyArg tieBreak", SolverConfig.TIE_LOWEST_INDEX, d.tieBreak);
        T.eq("applyArg valueOrder", SolverConfig.VALUE_RANDOM, d.valueOrder);
        T.eq("applyArg shuffleStrength", 55, d.shuffleStrength);
        T.eq("applyArg randomSeed", 999, d.randomSeed);
        T.eq("applyArg forwardCheck", SolverConfig.FC_NEIGHBOURS, d.forwardCheck);
        T.check("applyArg greyInteriorPruning", !d.greyInteriorPruning);
        T.eq("applyArg candidateCap", 7, d.candidateCap);
        T.eq("applyArg restartPolicy", SolverConfig.RESTART_LUBY, d.restartPolicy);
        T.eq("applyArg restartBase", 4242, d.restartBase);
        T.eq("applyArg restartMultiplier", 250, d.restartMultiplier);
        T.eq("applyArg nodeBudget", 123456, d.nodeBudget);
        T.eq("applyArg startCell", SolverConfig.START_CENTRE, d.startCell);
        T.eq("applyArg hybridThreshold", 9, d.hybridThreshold);

        // clamping and junk tolerance
        SolverConfig e = new SolverConfig();
        e.applyArg("--shuffleStrength=9999");
        T.eq("shuffleStrength clamps to 100", 100, e.shuffleStrength);
        e.applyArg("--shuffleStrength=-5");
        T.eq("shuffleStrength clamps to 0", 0, e.shuffleStrength);
        e.applyArg("--candidateCap=abc");
        T.eq("non-numeric value leaves the field unchanged", 1024, e.candidateCap);
        e.applyArg("--totallyUnknownKey=1");
        T.ok("unknown keys are ignored");
        e.applyArg("garbage-without-equals");
        T.ok("malformed arguments are ignored");

        // JSON contains every setting
        String json = d.toJson();
        String[] keys = { "cellOrder", "hybridThreshold", "tieBreak", "valueOrder",
                          "shuffleStrength", "randomSeed", "forwardCheck",
                          "greyInteriorPruning", "candidateCap", "restartPolicy",
                          "restartBase", "restartMultiplier", "nodeBudget", "startCell" };
        boolean allKeys = true;
        String missing = null;
        for (int i = 0; i < keys.length; i++) {
            if (json.indexOf("\"" + keys[i] + "\"") < 0) { allKeys = false; missing = keys[i]; }
        }
        T.check("toJson includes every setting", allKeys,
                missing == null ? null : "missing " + missing);
        T.check("toJson is a JSON object",
                json.startsWith("{") && json.endsWith("}"));

        // copy()
        SolverConfig f = d.copy();
        T.eq("copy preserves every field", d.toJson(), f.toJson());
        f.candidateCap = 3;
        T.check("copy is independent", d.candidateCap != f.candidateCap);
    }

    // ------------------------------------------------------- every option solves

    private static void optionsSolveTest() {
        // A tightly-constrained generated instance so every configuration can
        // finish quickly, then validate whatever it produces.
        Instance inst = Generator.generate(8, 20, 5150L, true, true);

        String[] cellOrders = { "mrv", "hybrid", "rowMajor" };
        for (int i = 0; i < cellOrders.length; i++) {
            SolverConfig cfg = new SolverConfig();
            cfg.cellOrder = SolverConfig.parseCellOrder(cellOrders[i]);
            runAndValidate(inst, cfg, "cellOrder=" + cellOrders[i]);
        }

        String[] ties = { "mostNeighbours", "fewestNeighbours", "lowestIndex",
                          "nearestFixed", "nearestCentre" };
        for (int i = 0; i < ties.length; i++) {
            SolverConfig cfg = new SolverConfig();
            cfg.tieBreak = SolverConfig.parseTieBreak(ties[i]);
            runAndValidate(inst, cfg, "tieBreak=" + ties[i]);
        }

        String[] values = { "natural", "reverse", "random", "rarestColour" };
        for (int i = 0; i < values.length; i++) {
            SolverConfig cfg = new SolverConfig();
            cfg.valueOrder = SolverConfig.parseValueOrder(values[i]);
            runAndValidate(inst, cfg, "valueOrder=" + values[i]);
        }

        String[] fcs = { "none", "neighbours", "fullBoard" };
        for (int i = 0; i < fcs.length; i++) {
            SolverConfig cfg = new SolverConfig();
            cfg.forwardCheck = SolverConfig.parseForwardCheck(fcs[i]);
            runAndValidate(inst, cfg, "forwardCheck=" + fcs[i]);
        }

        String[] starts = { "auto", "topLeft", "topRight", "bottomLeft",
                            "bottomRight", "centre" };
        for (int i = 0; i < starts.length; i++) {
            SolverConfig cfg = new SolverConfig();
            cfg.startCell = SolverConfig.parseStartCell(starts[i]);
            runAndValidate(inst, cfg, "startCell=" + starts[i]);
        }

        // grey pruning off, and shuffled orders
        SolverConfig g = new SolverConfig();
        g.greyInteriorPruning = false;
        runAndValidate(inst, g, "greyInteriorPruning=off");

        SolverConfig h = new SolverConfig();
        h.shuffleStrength = 100;
        h.randomSeed = 77L;
        runAndValidate(inst, h, "shuffleStrength=100");

        SolverConfig k = new SolverConfig();
        k.cellOrder = SolverConfig.CELL_HYBRID;
        k.hybridThreshold = 1;
        runAndValidate(inst, k, "hybrid threshold at minimum");

        SolverConfig l = new SolverConfig();
        l.cellOrder = SolverConfig.CELL_HYBRID;
        l.hybridThreshold = 4096;
        runAndValidate(inst, l, "hybrid threshold at maximum");
    }

    private static void runAndValidate(Instance inst, SolverConfig cfg, String label) {
        MrvSolver s = new MrvSolver(inst, cfg);
        s.maxNodes = 4000000L;
        long found = s.solve();
        if (found >= 1) {
            T.isNull("solution valid with " + label,
                     Validator.validateComplete(inst, s.solutionBoard));
        } else {
            // not every extreme configuration has to succeed, but whatever it
            // reached must still be a legal partial board
            T.isNull("partial board valid with " + label,
                     Validator.validatePartial(inst, s.bestBoard, false));
        }
    }

    // --------------------------------------------------------------- invariants

    private static void invariantTest() {
        // Small instance so we can enumerate every solution for each setting.
        Instance inst = Generator.generate(4, 3, 2027L, true, false);

        long baseline = countAll(inst, new SolverConfig());
        T.check("baseline enumeration found solutions", baseline >= 1,
                "baseline=" + baseline);

        // forward checking must not change the solution count
        String[] fcs = { "none", "neighbours", "fullBoard" };
        for (int i = 0; i < fcs.length; i++) {
            SolverConfig cfg = new SolverConfig();
            cfg.forwardCheck = SolverConfig.parseForwardCheck(fcs[i]);
            T.eq("forwardCheck=" + fcs[i] + " finds the same solution count",
                 baseline, countAll(inst, cfg));
        }

        // cell ordering must not change the solution count
        String[] cellOrders = { "mrv", "hybrid", "rowMajor" };
        for (int i = 0; i < cellOrders.length; i++) {
            SolverConfig cfg = new SolverConfig();
            cfg.cellOrder = SolverConfig.parseCellOrder(cellOrders[i]);
            T.eq("cellOrder=" + cellOrders[i] + " finds the same solution count",
                 baseline, countAll(inst, cfg));
        }

        // tie breaks must not change the solution count
        String[] ties = { "mostNeighbours", "fewestNeighbours", "lowestIndex",
                          "nearestFixed", "nearestCentre" };
        for (int i = 0; i < ties.length; i++) {
            SolverConfig cfg = new SolverConfig();
            cfg.tieBreak = SolverConfig.parseTieBreak(ties[i]);
            T.eq("tieBreak=" + ties[i] + " finds the same solution count",
                 baseline, countAll(inst, cfg));
        }

        // value ordering must not change the solution count
        String[] values = { "natural", "reverse", "random", "rarestColour" };
        for (int i = 0; i < values.length; i++) {
            SolverConfig cfg = new SolverConfig();
            cfg.valueOrder = SolverConfig.parseValueOrder(values[i]);
            cfg.randomSeed = 4242L;
            T.eq("valueOrder=" + values[i] + " finds the same solution count",
                 baseline, countAll(inst, cfg));
        }

        // shuffling must not change the solution count either
        SolverConfig sh = new SolverConfig();
        sh.shuffleStrength = 100;
        sh.randomSeed = 31337L;
        T.eq("shuffleStrength=100 finds the same solution count",
             baseline, countAll(inst, sh));

        // grey-interior pruning is derived, so it must not change the count
        T.check("the instance has an exact grey budget", inst.greyBudgetIsExact());
        SolverConfig gp = new SolverConfig();
        gp.greyInteriorPruning = false;
        T.eq("greyInteriorPruning=off finds the same solution count",
             baseline, countAll(inst, gp));

        // Restarts are deliberately ignored when enumerating every solution,
        // because a restarted search cannot know what it already counted.
        SolverConfig rs = new SolverConfig();
        rs.restartPolicy = SolverConfig.RESTART_LUBY;
        rs.restartBase = 50L;
        T.eq("restarts are ignored during exhaustive enumeration",
             baseline, countAll(inst, rs));
        MrvSolver rsSolver = new MrvSolver(inst, rs);
        rsSolver.stopAtFirstSolution = false;
        rsSolver.maxNodes = 40000000L;
        rsSolver.solve();
        T.eq("no restarts happen in exhaustive mode", 0, rsSolver.restarts);
    }

    private static long countAll(Instance inst, SolverConfig cfg) {
        MrvSolver s = new MrvSolver(inst, cfg);
        s.stopAtFirstSolution = false;
        s.maxNodes = 40000000L;
        long c = s.solve();
        if (s.aborted) return -1;
        return c;
    }

    // ------------------------------------------------- budgets / restarts / caps

    private static void budgetsAndRestartsTest() {
        Instance e2 = Instance.eternity2();

        // nodeBudget in the config caps the run just like maxNodes
        SolverConfig b = new SolverConfig();
        b.nodeBudget = 50000L;
        MrvSolver s = new MrvSolver(e2, b);
        s.solve();
        T.check("cfg.nodeBudget caps the search", s.nodes <= 50000L, "nodes=" + s.nodes);
        T.check("hitting cfg.nodeBudget marks the run aborted", s.aborted);

        // the smaller of maxNodes and cfg.nodeBudget wins
        SolverConfig b2 = new SolverConfig();
        b2.nodeBudget = 500000L;
        MrvSolver s2 = new MrvSolver(e2, b2);
        s2.maxNodes = 20000L;
        s2.solve();
        T.check("the tighter of maxNodes / nodeBudget applies",
                s2.nodes <= 20000L, "nodes=" + s2.nodes);

        // restart policies all terminate and respect the overall budget
        String[] policies = { "fixed", "geometric", "luby" };
        for (int i = 0; i < policies.length; i++) {
            SolverConfig cfg = new SolverConfig();
            cfg.restartPolicy = SolverConfig.parseRestartPolicy(policies[i]);
            cfg.restartBase = 5000L;
            cfg.restartMultiplier = 200;
            cfg.valueOrder = SolverConfig.VALUE_RANDOM;
            cfg.nodeBudget = 120000L;
            MrvSolver r = new MrvSolver(e2, cfg);
            r.solve();
            T.check("restartPolicy=" + policies[i] + " respects the node budget",
                    r.nodes <= 120000L, "nodes=" + r.nodes);
            T.check("restartPolicy=" + policies[i] + " actually restarted",
                    r.restarts >= 1, "restarts=" + r.restarts);
            T.isNull("restartPolicy=" + policies[i] + " leaves a valid partial board",
                     Validator.validatePartial(e2, r.bestBoard, false));
        }

        // candidateCap=1 is greedy: very cheap, and still legal
        SolverConfig cap = new SolverConfig();
        cap.candidateCap = 1;
        cap.nodeBudget = 2000000L;
        MrvSolver c1 = new MrvSolver(e2, cap);
        c1.solve();
        T.check("candidateCap=1 terminates quickly", c1.nodes < 2000000L,
                "nodes=" + c1.nodes);
        T.isNull("candidateCap=1 leaves a valid partial board",
                 Validator.validatePartial(e2, c1.bestBoard, false));
        System.out.println("         candidateCap=1: nodes=" + c1.nodes
                           + " bestPlaced=" + c1.bestPlaced + "/256");

        // determinism: the same seed gives the same search
        SolverConfig d1 = new SolverConfig();
        d1.valueOrder = SolverConfig.VALUE_RANDOM;
        d1.randomSeed = 2024L;
        d1.nodeBudget = 200000L;
        MrvSolver m1 = new MrvSolver(e2, d1);
        m1.solve();
        MrvSolver m2 = new MrvSolver(e2, d1.copy());
        m2.solve();
        T.eq("the same seed reproduces the node count", m1.nodes, m2.nodes);
        T.eq("the same seed reproduces the best depth", m1.bestPlaced, m2.bestPlaced);

        // a different seed should explore differently
        SolverConfig d2 = d1.copy();
        d2.randomSeed = 777L;
        MrvSolver m3 = new MrvSolver(e2, d2);
        m3.solve();
        T.check("a different seed changes the search",
                m3.bestPlaced != m1.bestPlaced || m3.nodes != m1.nodes,
                "seeds produced identical results");

        // startCell really forces the opening move
        int[] starts = { SolverConfig.START_TOP_LEFT, SolverConfig.START_TOP_RIGHT,
                         SolverConfig.START_BOTTOM_LEFT, SolverConfig.START_BOTTOM_RIGHT };
        int[] expect = { 0, 15, 240, 255 };
        for (int i = 0; i < starts.length; i++) {
            SolverConfig cfg = new SolverConfig();
            cfg.startCell = starts[i];
            MrvSolver ss = new MrvSolver(e2, cfg);
            T.eq("startCell=" + SolverConfig.startCellName(starts[i])
                 + " forces the opening cell", expect[i], ss.selectCell());
        }

        // the placement order of the best board is captured for replay
        SolverConfig rec = new SolverConfig();
        rec.nodeBudget = 80000L;
        MrvSolver rs = new MrvSolver(e2, rec);
        rs.solve();
        T.eq("the recorded order has one entry per placed piece",
             rs.bestPlaced, rs.bestOrderLength);
        T.notNull("the recorded order exists", rs.bestOrderCells);
        if (rs.bestOrderCells != null) {
            // replaying the order must rebuild exactly the best board
            int[] rebuilt = new int[e2.cells];
            for (int i = 0; i < e2.cells; i++) rebuilt[i] = -1;
            boolean dup = false;
            for (int i = 0; i < rs.bestOrderLength; i++) {
                int cell = rs.bestOrderCells[i];
                if (rebuilt[cell] >= 0) dup = true;
                rebuilt[cell] = rs.bestOrderVariants[i];
            }
            T.check("the recorded order never reuses a cell", !dup);
            T.eqIntArray("replaying the recorded order rebuilds the best board",
                         rs.bestBoard, rebuilt);
            T.isNull("every prefix of the recorded order is a legal board",
                     firstIllegalPrefix(e2, rs));
        }
    }

    /** Check that each prefix of the recorded placement order is legal. */
    private static String firstIllegalPrefix(Instance inst, MrvSolver s) {
        int[] board = new int[inst.cells];
        for (int i = 0; i < inst.cells; i++) board[i] = -1;
        for (int i = 0; i < s.bestOrderLength; i++) {
            board[s.bestOrderCells[i]] = s.bestOrderVariants[i];
            String err = Validator.validatePartial(inst, board, false);
            if (err != null) return "prefix " + (i + 1) + ": " + err;
        }
        return null;
    }
}
