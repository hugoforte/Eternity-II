package core;

/**
 * The colour quota: {@link ScanSolver} refusing to descend unless the board has
 * already consumed enough sides of three tracked colours.
 *
 * The gate is DELIBERATELY INCOMPLETE -- it abandons subtrees that may hold
 * solutions -- so the oracle {@link CrossValidationTest} uses is the wrong one
 * here and stays where it is, exercising the default-off engine.  What is
 * checked instead:
 *
 *   - the published ramp, reproduced at 16x16 and scaled elsewhere;
 *   - the tracked colours really are offered first, which is what makes
 *     abandoning a run at the first candidate that falls short sound;
 *   - the floor is honoured at every depth of the board the engine returns,
 *     read off that board rather than taken from the solver's counters;
 *   - the gate only ever REMOVES solutions: every board a gated exhaustive run
 *     reports is one the ungated run reports too;
 *   - a colour the instance cannot count is refused, with the colour in the
 *     message.
 */
public final class ColourQuotaTest {

    /** Blackwood's ramp: by this placement, that many tracked sides. */
    private static final int[] RAMP_DEPTHS = { 16, 26, 56, 76, 102, 160 };
    private static final int[] RAMP_SIDES  = {  0, 28, 71, 89, 106, 119 };
    /** The slopes he published between those breakpoints. */
    private static final double[] RAMP_SLOPES =
        { 2.8, 1.43333, 0.9, 0.6538, 1.0 / 4.4615 };

    public static void run() {
        T.section("ColourQuotaTest: the colour quota in ScanSolver");

        itIsOffUnlessAskedFor();
        itTracksBlackwoodsOwnThreeColours();
        itReproducesThePublishedRamp();
        itOffersTheTrackedColoursFirst();
        itHonoursTheFloorOnTheBoardItReturns();
        itOnlyEverRemovesSolutions();
        itIsRepeatable();
        itRefusesColoursTheInstanceCannotCount();

        T.endSection();
    }

    // ------------------------------------------------------------------- off

    private static void itIsOffUnlessAskedFor() {
        Instance inst = Instance.eternity2();
        T.eq("the default config asks for no quota",
             SolverConfig.QUOTA_NONE, new SolverConfig().quotaSchedule);

        ScanSolver plain = new ScanSolver(inst);
        T.check("so the engine is not gated", !plain.quotaGated());
        T.eq("and it tracks no colours", 0, plain.quotaColours().length);

        int[] floors = plain.quotaFloors();
        int highest = 0;
        for (int d = 0; d < floors.length; d++) if (floors[d] > highest) highest = floors[d];
        T.eq("no depth is asked for a single side", 0, highest);

        // The gate groups the candidate index by colour count, which is extra
        // entries and a different order.  Off, it must be the index it always
        // was -- otherwise "no slower with the gate off" would be a fiction.
        SolverConfig off = new SolverConfig();
        off.quotaColours = "14,22,5";
        ScanSolver asked = new ScanSolver(inst, off);
        T.eq("naming colours without a schedule changes nothing about the index",
             plain.candidateEntryCount(), asked.candidateEntryCount());

        SolverConfig on = off.copy();
        on.quotaSchedule = SolverConfig.QUOTA_BLACKWOOD;
        ScanSolver gated = new ScanSolver(inst, on);
        T.check("asking for the schedule does change it",
                gated.candidateEntryCount() > plain.candidateEntryCount(),
                "gated " + gated.candidateEntryCount()
                + " vs plain " + plain.candidateEntryCount());
    }

    // --------------------------------------------------------------- colours

    /**
     * The three colours are Blackwood's own, carried across from his piece
     * table into this one's numbering.  He writes them {@code 13, 16, 10}; his
     * table numbers the border colours 1, 5, 9, 13, 17 where this one numbers
     * them 1, 2, 3, 13, 14, and under the relabelling that maps one table onto
     * the other his three are {@code 14, 22, 5}.
     *
     * Reading his numbers as ours instead gives a triple of the same SHAPE --
     * one border colour, two interior, 122 sides -- which is why the mistake
     * survived.  What it does not give is the property he chose them for, and
     * remarked on in his source: "there is a lot of overlap between these
     * sides".  Three pieces carry all three of his colours.  No piece carries
     * all three of the ones read as ours.  That overlap is the fingerprint, so
     * it is what this checks.
     */
    private static void itTracksBlackwoodsOwnThreeColours() {
        Instance inst = Instance.eternity2();
        int[] colours = new ScanSolver(inst, blackwood()).quotaColours();
        T.eqIntArray("the quota tracks Blackwood's three colours in our numbering",
                     new int[] { 5, 14, 22 }, sorted(colours));

        int[] occurrences = sidesPerColour(inst);
        int border = 0;
        for (int i = 0; i < colours.length; i++) if (occurrences[colours[i]] == 24) border++;
        T.eq("one of the three is a border colour, as he describes", 1, border);

        int[] per = sidesPerPiece(inst, colours);
        int sides = 0;
        int carryingAllThree = 0;
        for (int id = 0; id < per.length; id++) {
            sides += per[id];
            if (per[id] == 3) carryingAllThree++;
        }
        T.eq("they are carried on 122 sides of the piece set", 122, sides);
        T.eq("and three pieces carry all three of them -- his overlap",
             3, carryingAllThree);
    }

    /** The default config, with his ramp switched on. */
    private static SolverConfig blackwood() {
        SolverConfig cfg = new SolverConfig();
        cfg.quotaSchedule = SolverConfig.QUOTA_BLACKWOOD;
        return cfg;
    }

    // ------------------------------------------------------------------ ramp

    private static void itReproducesThePublishedRamp() {
        SolverConfig cfg = new SolverConfig();
        cfg.quotaSchedule = SolverConfig.QUOTA_BLACKWOOD;
        ScanSolver s = new ScanSolver(Instance.eternity2(), cfg);
        T.check("the schedule gates the real board", s.quotaGated());
        int[] floors = s.quotaFloors();

        for (int k = 0; k < RAMP_DEPTHS.length; k++) {
            T.eq("by placement " + RAMP_DEPTHS[k] + " the ramp asks for "
                 + RAMP_SIDES[k] + " sides", RAMP_SIDES[k], floors[RAMP_DEPTHS[k]]);
        }
        T.eq("nothing is asked of the first sixteen placements", 0, floors[16]);
        T.eq("and the ramp stops rising after 160", 119, floors[256]);

        // The published slopes are the rises over the runs above, rounded for
        // print; the engine interpolates the exact ones.
        for (int k = 1; k < RAMP_DEPTHS.length; k++) {
            double rise = RAMP_SIDES[k] - RAMP_SIDES[k - 1];
            double run = RAMP_DEPTHS[k] - RAMP_DEPTHS[k - 1];
            T.check("the published slope from " + (RAMP_DEPTHS[k - 1] + 1) + " to "
                    + RAMP_DEPTHS[k] + " is that segment's rise over its run",
                    Math.abs(rise / run - RAMP_SLOPES[k - 1]) < 0.0005,
                    "rise/run = " + (rise / run) + " vs " + RAMP_SLOPES[k - 1]);
        }
        T.eqIntArray("every depth is that interpolation, truncated",
                     interpolatedRamp(), floors);

        boolean monotone = true;
        for (int d = 1; d < floors.length; d++) if (floors[d] < floors[d - 1]) monotone = false;
        T.check("the floor never falls as the board fills", monotone);

        // Every other board size gets the same shape, scaled, exactly as the
        // slip schedules and the fill-order phases are.
        SolverConfig small = cfg.copy();
        small.quotaColours = "1,2,3";
        ScanSolver four = new ScanSolver(Generator.generate(4, 3, 91L, true, false), small);
        T.check("a smaller board still gets a ramp", four.quotaGated());
        int[] smallFloors = four.quotaFloors();
        T.eq("scaled to 16 cells nothing is asked of the first placement",
             0, smallFloors[1]);
        T.eq("and the finished board must have consumed 119 sides' worth, scaled",
             119 * 16 / 256, smallFloors[16]);
    }

    /** The ramp as published: linear between the breakpoints, then flat. */
    private static int[] interpolatedRamp() {
        int[] out = new int[257];
        for (int d = 1; d <= 256; d++) {
            if (d <= RAMP_DEPTHS[0]) continue;
            out[d] = RAMP_SIDES[RAMP_SIDES.length - 1];
            for (int k = 1; k < RAMP_DEPTHS.length; k++) {
                if (d <= RAMP_DEPTHS[k]) {
                    int rise = RAMP_SIDES[k] - RAMP_SIDES[k - 1];
                    int run = RAMP_DEPTHS[k] - RAMP_DEPTHS[k - 1];
                    out[d] = RAMP_SIDES[k - 1] + (d - RAMP_DEPTHS[k - 1]) * rise / run;
                    break;
                }
            }
        }
        return out;
    }

    // -------------------------------------------------- tracked colours first

    /**
     * Abandoning a run at the first candidate that cannot meet the floor is
     * only sound if no candidate behind it could have.  That is a claim about
     * the order the engine offers candidates in, so it is read back out of the
     * engine rather than assumed.
     */
    private static void itOffersTheTrackedColoursFirst() {
        Instance inst = Instance.eternity2();
        SolverConfig cfg = new SolverConfig();
        cfg.quotaSchedule = SolverConfig.QUOTA_BLACKWOOD;
        ScanSolver s = new ScanSolver(inst, cfg);
        int[] per = sidesPerPiece(inst, s.quotaColours());

        int[] depths = { 0, 1, 17, 64, 130, 200, 255 };
        int descending = 0, withAChoice = 0, keys = 0;
        for (int i = 0; i < depths.length; i++) {
            for (int top = 0; top < inst.numColours; top++) {
                for (int left = 0; left < inst.numColours; left++) {
                    int[] order = s.candidateOrderAtDepth(depths[i], left, top);
                    if (order.length == 0) continue;
                    keys++;
                    boolean sorted = true;
                    boolean choice = false;
                    for (int k = 1; k < order.length; k++) {
                        int before = per[order[k - 1] >>> 2];
                        int after = per[order[k] >>> 2];
                        if (after > before) sorted = false;
                        if (after != before) choice = true;
                    }
                    if (sorted) descending++;
                    if (choice) withAChoice++;
                }
            }
        }
        T.check("every key offers something at these depths", keys > 0, "keys=" + keys);
        T.eq("every key's candidates arrive in non-increasing colour count",
             keys, descending);
        T.check("and the ordering is not vacuous -- keys really do mix counts",
                withAChoice > 0, "keys mixing counts = " + withAChoice);

        ScanSolver ungated = new ScanSolver(inst);
        int mixedUngated = 0;
        for (int top = 0; top < inst.numColours; top++) {
            for (int left = 0; left < inst.numColours; left++) {
                int[] order = ungated.candidateOrderAtDepth(64, left, top);
                for (int k = 1; k < order.length; k++) {
                    if (per[order[k] >>> 2] > per[order[k - 1] >>> 2]) { mixedUngated++; break; }
                }
            }
        }
        T.check("without the gate the order is left alone, not sorted",
                mixedUngated > 0, "keys out of count order = " + mixedUngated);
    }

    // ------------------------------------------------------------ the floor

    private static void itHonoursTheFloorOnTheBoardItReturns() {
        Instance inst = Instance.eternity2();
        SolverConfig cfg = new SolverConfig();
        cfg.slipSchedule = SolverConfig.SLIP_VERHAARD;
        cfg.quotaSchedule = SolverConfig.QUOTA_BLACKWOOD;
        ScanSolver s = new ScanSolver(inst, cfg);
        s.maxNodes = 5000000L;
        s.solve();

        int[] per = sidesPerPiece(inst, s.quotaColours());
        int[] floors = s.quotaFloors();
        int[] variants = s.bestOrderVariants();
        int worstDepth = -1;
        int cumulative = 0;
        for (int d = 0; d < s.bestOrderLength(); d++) {
            cumulative += per[variants[d] >>> 2];
            if (cumulative < floors[d + 1] && worstDepth < 0) worstDepth = d + 1;
        }
        T.check("the board it returns is not empty", s.bestPlaced > 0,
                "placed=" + s.bestPlaced);
        T.eq("every depth of that board meets its floor", -1, worstDepth);
        T.check("and the ramp was really binding, not idle",
                floors[s.bestPlaced] > 0, "floor at depth " + s.bestPlaced);

        T.isNull("the gated board is still a legal board",
                 Validator.validatePartial(inst, s.bestBoard, false, s.bestBreaks));
        T.eq("and it scores what it claims to score",
             Validator.matchedEdges(inst, s.bestBoard), s.bestMatchedEdges);

        System.out.println("         Eternity II, 5M nodes, quota on: placed="
            + s.bestPlaced + "/256 edges=" + s.bestMatchedEdges + "/480"
            + " nodes/s=" + (s.elapsedMs() == 0 ? 0 : s.nodes * 1000L / s.elapsedMs()));
    }

    // ------------------------------------------------------- removes only

    /**
     * The gate cuts subtrees, so it may lose solutions -- but it may never
     * invent one.  Both engines enumerate a small instance exhaustively and the
     * gated set has to be contained in the ungated one.
     */
    private static void itOnlyEverRemovesSolutions() {
        Instance inst = Generator.generate(4, 3, 91L, true, false);

        SolutionCollector all = new SolutionCollector();
        ScanSolver ungated = new ScanSolver(inst);
        ungated.stopAtFirstSolution = false;
        ungated.listener = all;
        long ungatedCount = ungated.solve();
        T.check("the instance has solutions to lose", ungatedCount > 0,
                "found " + ungatedCount);

        SolverConfig cfg = new SolverConfig();
        cfg.quotaSchedule = SolverConfig.QUOTA_BLACKWOOD;
        cfg.quotaColours = "2,3";
        SolutionCollector kept = new SolutionCollector();
        ScanSolver gated = new ScanSolver(inst, cfg);
        gated.stopAtFirstSolution = false;
        gated.listener = kept;
        long gatedCount = gated.solve();

        T.check("the gate never finds more than the complete search",
                gatedCount <= ungatedCount,
                gatedCount + " gated vs " + ungatedCount + " ungated");
        T.check("and it really does cut some away", gatedCount < ungatedCount,
                gatedCount + " gated vs " + ungatedCount + " ungated");

        String haystack = all.signature();
        int missing = 0;
        for (int i = 0; i < kept.count(); i++) {
            if (haystack.indexOf(line(kept.board(i))) < 0) missing++;
        }
        T.eq("every board the gate kept is one the complete search found", 0, missing);

        for (int i = 0; i < kept.count(); i++) {
            T.checkSilent(Validator.validateComplete(inst, kept.board(i)) == null);
        }
    }

    private static String line(int[] board) {
        StringBuilder sb = new StringBuilder(board.length * 4);
        for (int c = 0; c < board.length; c++) {
            if (c > 0) sb.append(',');
            sb.append(board[c]);
        }
        sb.append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------ repeatable

    private static void itIsRepeatable() {
        SolverConfig cfg = new SolverConfig();
        cfg.slipSchedule = SolverConfig.SLIP_VERHAARD;
        cfg.quotaSchedule = SolverConfig.QUOTA_BLACKWOOD;

        ScanSolver a = new ScanSolver(Instance.eternity2(), cfg);
        a.maxNodes = 2000000L;
        a.solve();
        ScanSolver b = new ScanSolver(Instance.eternity2(), cfg.copy());
        b.maxNodes = 2000000L;
        b.solve();

        T.eq("two gated runs expand the same nodes", a.nodes, b.nodes);
        T.eq("and reach the same depth", a.bestPlaced, b.bestPlaced);
        T.eqIntArray("on the same board", a.bestBoard, b.bestBoard);
    }

    // --------------------------------------------------------------- refusal

    private static void itRefusesColoursTheInstanceCannotCount() {
        SolverConfig cfg = new SolverConfig();
        cfg.quotaSchedule = SolverConfig.QUOTA_BLACKWOOD;
        Instance small = Generator.generate(4, 3, 91L, true, false);

        cfg.quotaColours = "14,22,5";
        String message = refusal(small, cfg);
        T.notNull("a colour the instance does not have is refused", message);
        T.check("and the message says which colour and what the range is",
                message != null && message.indexOf("14") >= 0
                && message.indexOf("0..3") >= 0, message);

        cfg.quotaColours = "0,1,2";
        String grey = refusal(small, cfg);
        T.notNull("grey is refused", grey);
        T.check("because it is the border colour, and the message says so",
                grey != null && grey.indexOf("border") >= 0, grey);

        cfg.quotaColours = "";
        T.notNull("an empty list is refused", refusal(small, cfg));

        // A malformed list never reaches the engine: the config keeps the value
        // it had rather than guessing at one.
        SolverConfig parsed = new SolverConfig();
        parsed.applyArg("--quotaColours=1,two,3");
        T.eq("a list that is not numbers leaves the setting alone",
             "14,22,5", parsed.quotaColours);
        parsed.applyArg("--quotaColours=4,4");
        T.eq("and nor does a list that repeats a colour", "14,22,5", parsed.quotaColours);
        parsed.applyArg("--quotaColours=1, 2 ,3");
        T.eq("a well-formed list is taken, spaces and all", "1, 2 ,3", parsed.quotaColours);
    }

    /** The message a bad colour list is refused with, or null if it was not. */
    private static String refusal(Instance inst, SolverConfig cfg) {
        try {
            new ScanSolver(inst, cfg.copy());
            return null;
        } catch (IllegalStateException e) {
            return e.getMessage();
        }
    }

    // --------------------------------------------------------------- helpers

    /** Colour -> how many of the piece set's half-edges carry it. */
    private static int[] sidesPerColour(Instance inst) {
        int[] occurrences = new int[inst.numColours];
        for (int id = 0; id < inst.numPieces; id++) {
            int p = inst.packedSides[id];
            occurrences[Sides.left(p)]++;
            occurrences[Sides.top(p)]++;
            occurrences[Sides.right(p)]++;
            occurrences[Sides.bottom(p)]++;
        }
        return occurrences;
    }

    /** A copy in ascending order, so a colour list compares by set. */
    private static int[] sorted(int[] values) {
        int[] out = values.clone();
        java.util.Arrays.sort(out);
        return out;
    }

    /** Piece -> how many of its four sides carry one of the tracked colours. */
    private static int[] sidesPerPiece(Instance inst, int[] colours) {
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
}
