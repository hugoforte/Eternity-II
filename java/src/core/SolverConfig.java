package core;

/**
 * Every decision the solver used to make by hard-coding, exposed as a value.
 *
 * Int constants are used instead of enums so the whole project keeps compiling
 * on very plain Java toolchains, and so the hot loop compares ints.
 *
 * The defaults below reproduce EXACTLY the behaviour of the solver before it
 * became configurable, which is what lets the existing test suite keep passing
 * unchanged.
 */
public final class SolverConfig {

    // ----------------------------------------------------------------- engine

    /** {@link MrvSolver}: dynamic most-constrained-variable ordering. */
    public static final int ENGINE_MRV  = 0;
    /** {@link ScanSolver}: fixed fill order, two-colour candidate index. */
    public static final int ENGINE_SCAN = 1;

    // ------------------------------------------------------------- fill order

    /** Row scan, narrowed scan, column sweep, nested L-shapes. */
    public static final int FILL_BANDED    = 0;
    /** Plain left-to-right, top-to-bottom sweep. */
    public static final int FILL_ROW_MAJOR = 1;

    // ---------------------------------------------------------- edge slipping

    /** Every placed edge must match; the search is exact. */
    public static final int SLIP_NONE      = 0;
    /** Blackwood's published ceiling: one more break every few depths from 201. */
    public static final int SLIP_BLACKWOOD = 1;
    /** Verhaard's ceiling: starts sooner at 193 and rises to 12 by depth 240. */
    public static final int SLIP_VERHAARD  = 2;

    // ---------------------------------------------------------- cell ordering

    /** Always take the empty cell with the fewest candidates. */
    public static final int CELL_MRV       = 0;
    /** MRV while the minimum is small, else lowest index (keeps the frontier compact). */
    public static final int CELL_HYBRID    = 1;
    /** Always take the lowest-index empty cell (row-major). */
    public static final int CELL_ROW_MAJOR = 2;

    // ------------------------------------------------------------- tie breaks

    public static final int TIE_MOST_NEIGHBOURS   = 0;
    public static final int TIE_FEWEST_NEIGHBOURS = 1;
    public static final int TIE_LOWEST_INDEX      = 2;
    public static final int TIE_NEAREST_FIXED     = 3;
    public static final int TIE_NEAREST_CENTRE    = 4;

    // ------------------------------------------------------------ value order

    public static final int VALUE_NATURAL       = 0;
    public static final int VALUE_REVERSE       = 1;
    public static final int VALUE_RANDOM        = 2;
    public static final int VALUE_RAREST_COLOUR = 3;

    // ----------------------------------------------------------- forward check

    public static final int FC_NONE       = 0;
    public static final int FC_NEIGHBOURS = 1;
    public static final int FC_FULL_BOARD = 2;

    // --------------------------------------------------------------- restarts

    public static final int RESTART_NONE      = 0;
    public static final int RESTART_FIXED     = 1;
    public static final int RESTART_GEOMETRIC = 2;
    public static final int RESTART_LUBY      = 3;

    // -------------------------------------------------------------- start cell

    public static final int START_AUTO         = 0;
    public static final int START_TOP_LEFT     = 1;
    public static final int START_TOP_RIGHT    = 2;
    public static final int START_BOTTOM_LEFT  = 3;
    public static final int START_BOTTOM_RIGHT = 4;
    public static final int START_CENTRE       = 5;

    // ------------------------------------------------------------------ values

    public int     engine              = ENGINE_MRV;
    public int     fillOrder           = FILL_BANDED;
    /** How many mismatched edges {@link ScanSolver} may leave, by depth. */
    public int     slipSchedule        = SLIP_NONE;
    public int     cellOrder           = CELL_MRV;
    /** Hybrid switches to lowest-index once the MRV minimum exceeds this. */
    public int     hybridThreshold     = 4;
    public int     tieBreak            = TIE_MOST_NEIGHBOURS;
    public int     valueOrder          = VALUE_NATURAL;
    /** 0 = untouched order, 100 = fully shuffled. */
    public int     shuffleStrength     = 0;
    public long    randomSeed          = 12345L;
    public int     forwardCheck        = FC_FULL_BOARD;
    /** Prune interior candidates that would put grey on an inner edge. */
    public boolean greyInteriorPruning = true;
    /** Try at most this many candidates per cell.  Below the true maximum this
     *  makes the search incomplete but very fast -- a deliberate extreme. */
    public int     candidateCap        = 1024;
    public int     restartPolicy       = RESTART_NONE;
    public long    restartBase         = 100000L;
    /** Geometric growth factor, scaled by 100 (150 == 1.5x). */
    public int     restartMultiplier   = 150;
    /** Node budget for the whole attempt. */
    public long    nodeBudget          = Long.MAX_VALUE;
    public int     startCell           = START_AUTO;

    // ------------------------------------------------------------------ helpers

    public SolverConfig copy() {
        SolverConfig c = new SolverConfig();
        c.engine = engine;
        c.fillOrder = fillOrder;
        c.slipSchedule = slipSchedule;
        c.cellOrder = cellOrder;
        c.hybridThreshold = hybridThreshold;
        c.tieBreak = tieBreak;
        c.valueOrder = valueOrder;
        c.shuffleStrength = shuffleStrength;
        c.randomSeed = randomSeed;
        c.forwardCheck = forwardCheck;
        c.greyInteriorPruning = greyInteriorPruning;
        c.candidateCap = candidateCap;
        c.restartPolicy = restartPolicy;
        c.restartBase = restartBase;
        c.restartMultiplier = restartMultiplier;
        c.nodeBudget = nodeBudget;
        c.startCell = startCell;
        return c;
    }

    // -------------------------------------------------- string <-> int mapping

    public static int parseEngine(String s) {
        if (s == null) return ENGINE_MRV;
        if (s.equals("scan")) return ENGINE_SCAN;
        return ENGINE_MRV;
    }
    public static String engineName(int v) {
        if (v == ENGINE_SCAN) return "scan";
        return "mrv";
    }

    public static int parseFillOrder(String s) {
        if (s == null) return FILL_BANDED;
        if (s.equals("rowMajor")) return FILL_ROW_MAJOR;
        return FILL_BANDED;
    }
    public static String fillOrderName(int v) {
        if (v == FILL_ROW_MAJOR) return "rowMajor";
        return "banded";
    }

    public static int parseSlipSchedule(String s) {
        if (s == null) return SLIP_NONE;
        if (s.equals("blackwood")) return SLIP_BLACKWOOD;
        if (s.equals("verhaard")) return SLIP_VERHAARD;
        return SLIP_NONE;
    }
    public static String slipScheduleName(int v) {
        if (v == SLIP_BLACKWOOD) return "blackwood";
        if (v == SLIP_VERHAARD) return "verhaard";
        return "none";
    }

    public static int parseCellOrder(String s) {
        if (s == null) return CELL_MRV;
        if (s.equals("hybrid")) return CELL_HYBRID;
        if (s.equals("rowMajor")) return CELL_ROW_MAJOR;
        return CELL_MRV;
    }
    public static String cellOrderName(int v) {
        if (v == CELL_HYBRID) return "hybrid";
        if (v == CELL_ROW_MAJOR) return "rowMajor";
        return "mrv";
    }

    public static int parseTieBreak(String s) {
        if (s == null) return TIE_MOST_NEIGHBOURS;
        if (s.equals("fewestNeighbours")) return TIE_FEWEST_NEIGHBOURS;
        if (s.equals("lowestIndex")) return TIE_LOWEST_INDEX;
        if (s.equals("nearestFixed")) return TIE_NEAREST_FIXED;
        if (s.equals("nearestCentre")) return TIE_NEAREST_CENTRE;
        return TIE_MOST_NEIGHBOURS;
    }
    public static String tieBreakName(int v) {
        if (v == TIE_FEWEST_NEIGHBOURS) return "fewestNeighbours";
        if (v == TIE_LOWEST_INDEX) return "lowestIndex";
        if (v == TIE_NEAREST_FIXED) return "nearestFixed";
        if (v == TIE_NEAREST_CENTRE) return "nearestCentre";
        return "mostNeighbours";
    }

    public static int parseValueOrder(String s) {
        if (s == null) return VALUE_NATURAL;
        if (s.equals("reverse")) return VALUE_REVERSE;
        if (s.equals("random")) return VALUE_RANDOM;
        if (s.equals("rarestColour")) return VALUE_RAREST_COLOUR;
        return VALUE_NATURAL;
    }
    public static String valueOrderName(int v) {
        if (v == VALUE_REVERSE) return "reverse";
        if (v == VALUE_RANDOM) return "random";
        if (v == VALUE_RAREST_COLOUR) return "rarestColour";
        return "natural";
    }

    public static int parseForwardCheck(String s) {
        if (s == null) return FC_FULL_BOARD;
        if (s.equals("none")) return FC_NONE;
        if (s.equals("neighbours")) return FC_NEIGHBOURS;
        return FC_FULL_BOARD;
    }
    public static String forwardCheckName(int v) {
        if (v == FC_NONE) return "none";
        if (v == FC_NEIGHBOURS) return "neighbours";
        return "fullBoard";
    }

    public static int parseRestartPolicy(String s) {
        if (s == null) return RESTART_NONE;
        if (s.equals("fixed")) return RESTART_FIXED;
        if (s.equals("geometric")) return RESTART_GEOMETRIC;
        if (s.equals("luby")) return RESTART_LUBY;
        return RESTART_NONE;
    }
    public static String restartPolicyName(int v) {
        if (v == RESTART_FIXED) return "fixed";
        if (v == RESTART_GEOMETRIC) return "geometric";
        if (v == RESTART_LUBY) return "luby";
        return "none";
    }

    public static int parseStartCell(String s) {
        if (s == null) return START_AUTO;
        if (s.equals("topLeft")) return START_TOP_LEFT;
        if (s.equals("topRight")) return START_TOP_RIGHT;
        if (s.equals("bottomLeft")) return START_BOTTOM_LEFT;
        if (s.equals("bottomRight")) return START_BOTTOM_RIGHT;
        if (s.equals("centre")) return START_CENTRE;
        return START_AUTO;
    }
    public static String startCellName(int v) {
        if (v == START_TOP_LEFT) return "topLeft";
        if (v == START_TOP_RIGHT) return "topRight";
        if (v == START_BOTTOM_LEFT) return "bottomLeft";
        if (v == START_BOTTOM_RIGHT) return "bottomRight";
        if (v == START_CENTRE) return "centre";
        return "auto";
    }

    /** Render as JSON so the server can store it verbatim with the attempt. */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"engine\":\"").append(engineName(engine)).append("\",");
        sb.append("\"fillOrder\":\"").append(fillOrderName(fillOrder)).append("\",");
        sb.append("\"slipSchedule\":\"").append(slipScheduleName(slipSchedule)).append("\",");
        sb.append("\"cellOrder\":\"").append(cellOrderName(cellOrder)).append("\",");
        sb.append("\"hybridThreshold\":").append(hybridThreshold).append(',');
        sb.append("\"tieBreak\":\"").append(tieBreakName(tieBreak)).append("\",");
        sb.append("\"valueOrder\":\"").append(valueOrderName(valueOrder)).append("\",");
        sb.append("\"shuffleStrength\":").append(shuffleStrength).append(',');
        sb.append("\"randomSeed\":").append(randomSeed).append(',');
        sb.append("\"forwardCheck\":\"").append(forwardCheckName(forwardCheck)).append("\",");
        sb.append("\"greyInteriorPruning\":").append(greyInteriorPruning).append(',');
        sb.append("\"candidateCap\":").append(candidateCap).append(',');
        sb.append("\"restartPolicy\":\"").append(restartPolicyName(restartPolicy)).append("\",");
        sb.append("\"restartBase\":").append(restartBase).append(',');
        sb.append("\"restartMultiplier\":").append(restartMultiplier).append(',');
        sb.append("\"nodeBudget\":").append(nodeBudget).append(',');
        sb.append("\"startCell\":\"").append(startCellName(startCell)).append('"');
        sb.append('}');
        return sb.toString();
    }

    /**
     * Apply one {@code --key=value} command-line argument.
     * Unknown keys are ignored so the server and engine can evolve separately.
     */
    public void applyArg(String arg) {
        if (arg == null) return;
        String a = arg;
        while (a.startsWith("-")) a = a.substring(1);
        int eq = a.indexOf('=');
        if (eq <= 0) return;
        String k = a.substring(0, eq);
        String v = a.substring(eq + 1);
        apply(k, v);
    }

    public void apply(String k, String v) {
        if (k.equals("engine")) engine = parseEngine(v);
        else if (k.equals("fillOrder")) fillOrder = parseFillOrder(v);
        else if (k.equals("slipSchedule")) slipSchedule = parseSlipSchedule(v);
        else if (k.equals("cellOrder")) cellOrder = parseCellOrder(v);
        else if (k.equals("hybridThreshold")) hybridThreshold = clampInt(v, 1, 4096, hybridThreshold);
        else if (k.equals("tieBreak")) tieBreak = parseTieBreak(v);
        else if (k.equals("valueOrder")) valueOrder = parseValueOrder(v);
        else if (k.equals("shuffleStrength")) shuffleStrength = clampInt(v, 0, 100, shuffleStrength);
        else if (k.equals("randomSeed")) randomSeed = clampLong(v, 0L, 4000000000L, randomSeed);
        else if (k.equals("forwardCheck")) forwardCheck = parseForwardCheck(v);
        else if (k.equals("greyInteriorPruning")) greyInteriorPruning = parseBool(v, greyInteriorPruning);
        else if (k.equals("candidateCap")) candidateCap = clampInt(v, 1, 4096, candidateCap);
        else if (k.equals("restartPolicy")) restartPolicy = parseRestartPolicy(v);
        else if (k.equals("restartBase")) restartBase = clampLong(v, 100L, 1000000000L, restartBase);
        else if (k.equals("restartMultiplier")) restartMultiplier = clampInt(v, 101, 1000, restartMultiplier);
        else if (k.equals("nodeBudget")) nodeBudget = clampLong(v, 1000L, Long.MAX_VALUE, nodeBudget);
        else if (k.equals("startCell")) startCell = parseStartCell(v);
    }

    private static boolean parseBool(String v, boolean dflt) {
        if (v == null) return dflt;
        if (v.equals("1") || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes")) return true;
        if (v.equals("0") || v.equalsIgnoreCase("false") || v.equalsIgnoreCase("no")) return false;
        return dflt;
    }

    private static int clampInt(String v, int lo, int hi, int dflt) {
        try {
            int x = Integer.parseInt(v.trim());
            if (x < lo) return lo;
            if (x > hi) return hi;
            return x;
        } catch (Throwable e) { return dflt; }
    }

    private static long clampLong(String v, long lo, long hi, long dflt) {
        try {
            long x = Long.parseLong(v.trim());
            if (x < lo) return lo;
            if (x > hi) return hi;
            return x;
        } catch (Throwable e) { return dflt; }
    }
}
