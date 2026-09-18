package core;

/**
 * Tests for the existing row-major {@link Solver} -- the pre-existing
 * functionality that must keep working after the MRV/bitset work landed.
 *
 * These reach into the solver's package-private tables on purpose: the
 * precomputed rotation table and candidate lookup are the parts most likely to
 * be broken by a refactor, and they are exactly what the search depends on.
 */
public final class SolverTest {

    public static void run() {
        T.section("SolverTest: row-major solver tables");
        tablesTest();
        T.endSection();

        T.section("SolverTest: row-major solver fixed-piece constants");
        constantsTest();
        T.endSection();

        T.section("SolverTest: row-major solver bounded run");
        boundedRunTest();
        T.endSection();
    }

    // ------------------------------------------------------------------ tables

    private static void tablesTest() {
        Solver s = new Solver();

        int[][] raw = new int[256][4];
        Pieces.SetupPieces(raw);

        // rotation 0 must be the stored orientation
        boolean rot0Ok = true;
        for (int id = 0; id < 256; id++) {
            if (s.rotPacked[id][0] != Sides.pack(raw[id])) { rot0Ok = false; break; }
        }
        T.check("rotPacked[id][0] equals the stored piece orientation", rot0Ok);

        // each further rotation is one clockwise quarter turn
        boolean rotStepOk = true;
        String detail = null;
        for (int id = 0; id < 256 && rotStepOk; id++) {
            for (int r = 1; r < 4; r++) {
                int expect = Sides.rotateCW(s.rotPacked[id][r - 1]);
                if (s.rotPacked[id][r] != expect) {
                    rotStepOk = false;
                    detail = "piece " + (id + 1) + " rot " + r;
                    break;
                }
            }
        }
        T.check("each rotPacked step is one clockwise quarter turn", rotStepOk, detail);

        // four rotations return to the start
        boolean cycleOk = true;
        for (int id = 0; id < 256; id++) {
            if (Sides.rotateCW(s.rotPacked[id][3]) != s.rotPacked[id][0]) { cycleOk = false; break; }
        }
        T.check("rotPacked wraps around after four rotations", cycleOk);

        // candidate table: every entry filed under (l,t) really has that l and t
        int totalEntries = 0;
        boolean keysOk = true;
        String keyDetail = null;
        for (int l = 0; l < Solver.MAX_COLOUR && keysOk; l++) {
            for (int t = 0; t < Solver.MAX_COLOUR && keysOk; t++) {
                int[] list = s.cand[l][t];
                T.checkSilent(list != null);
                totalEntries += list.length;
                for (int i = 0; i < list.length; i++) {
                    int packed = list[i];
                    int id = packed >>> 2;
                    int r = packed & 3;
                    int rp = s.rotPacked[id][r];
                    if (Sides.left(rp) != l || Sides.top(rp) != t) {
                        keysOk = false;
                        keyDetail = "cand[" + l + "][" + t + "] holds " + Sides.toString(rp);
                        break;
                    }
                }
            }
        }
        T.check("every cand[l][t] entry really has left=l and top=t", keysOk, keyDetail);

        Instance e2 = Instance.eternity2();
        T.eq("cand table holds exactly the canonical variants",
             e2.countCanonicalVariants(), totalEntries);

        // no duplicate (piece, rotation) entries anywhere in the table
        boolean[] seen = new boolean[1024];
        boolean noDupes = true;
        for (int l = 0; l < Solver.MAX_COLOUR && noDupes; l++) {
            for (int t = 0; t < Solver.MAX_COLOUR && noDupes; t++) {
                int[] list = s.cand[l][t];
                for (int i = 0; i < list.length; i++) {
                    if (seen[list[i]]) { noDupes = false; break; }
                    seen[list[i]] = true;
                }
            }
        }
        T.check("no (piece, rotation) appears twice in the candidate table", noDupes);

        // de-duplication: a variant that duplicates an earlier rotation of the
        // same piece must NOT be in the table
        boolean dedupOk = true;
        for (int id = 0; id < 256 && dedupOk; id++) {
            for (int r = 0; r < 4; r++) {
                boolean canonical = e2.isCanonicalRotation(id, r);
                boolean present = seen[(id << 2) | r];
                if (canonical != present) { dedupOk = false; break; }
            }
        }
        T.check("the candidate table contains exactly the canonical rotations", dedupOk);

        // candByTop must be the union over all left colours
        boolean byTopOk = true;
        String byTopDetail = null;
        for (int t = 0; t < Solver.MAX_COLOUR; t++) {
            int expect = 0;
            for (int l = 0; l < Solver.MAX_COLOUR; l++) expect += s.cand[l][t].length;
            if (s.candByTop[t].length != expect) {
                byTopOk = false;
                byTopDetail = "candByTop[" + t + "] has " + s.candByTop[t].length
                            + " entries, expected " + expect;
                break;
            }
            for (int i = 0; i < s.candByTop[t].length; i++) {
                int packed = s.candByTop[t][i];
                if (Sides.top(s.rotPacked[packed >>> 2][packed & 3]) != t) {
                    byTopOk = false;
                    byTopDetail = "candByTop[" + t + "] holds a variant whose top is not " + t;
                    break;
                }
            }
            if (!byTopOk) break;
        }
        T.check("candByTop[t] is exactly the union of cand[*][t]", byTopOk, byTopDetail);
    }

    // --------------------------------------------------------------- constants

    private static void constantsTest() {
        T.eq("board is 16 wide", 16, Solver.N);
        T.eq("board has 256 cells", 256, Solver.CELLS);
        T.eq("there are 256 pieces", 256, Solver.NUM_PIECES);
        T.eq("grey is colour 0", 0, Solver.GREY);
        T.eq("colour table covers 0..22", 23, Solver.MAX_COLOUR);

        T.eq("fixed piece is index 138", 138, Solver.FIXED_PIECE_ID0);
        T.eq("fixed row is 8", 8, Solver.FIXED_ROW);
        T.eq("fixed col is 7", 7, Solver.FIXED_COL);
        T.eq("fixed depth is row*16+col = 135", 135, Solver.FIXED_DEPTH);
        T.eq("the cell left of the fixed cell is depth 134", 134, Solver.FIXED_LEFT_DEPTH);
        T.eq("the cell above the fixed cell is depth 119", 119, Solver.FIXED_TOP_DEPTH);
        T.eq("fixed left colour", 8, Solver.FIXED_LEFT);
        T.eq("fixed top colour", 6, Solver.FIXED_TOP);
        T.eq("fixed right colour", 16, Solver.FIXED_RIGHT);
        T.eq("fixed bottom colour", 16, Solver.FIXED_BOTTOM);

        // the neighbour-propagation depths must really be the fixed cell's
        // left and top neighbours on a 16-wide board
        T.eq("FIXED_LEFT_DEPTH is the same row as FIXED_DEPTH",
             Solver.FIXED_DEPTH / 16, Solver.FIXED_LEFT_DEPTH / 16);
        T.eq("FIXED_TOP_DEPTH is the same column as FIXED_DEPTH",
             Solver.FIXED_DEPTH % 16, Solver.FIXED_TOP_DEPTH % 16);

        // piece 139 must actually be orientable as the fixed constraint says
        Solver s = new Solver();
        int want = Sides.pack(Solver.FIXED_LEFT, Solver.FIXED_TOP,
                              Solver.FIXED_RIGHT, Solver.FIXED_BOTTOM);
        int rot = -1;
        for (int r = 0; r < 4; r++) {
            if (s.rotPacked[Solver.FIXED_PIECE_ID0][r] == want) { rot = r; break; }
        }
        T.eq("piece 139 is orientable as the fixed constraint requires (rotation 1)", 1, rot);
    }

    // ------------------------------------------------------------- bounded run

    private static void boundedRunTest() {
        Solver s = new Solver();
        s.verbose = false;
        s.maxNodes = 200000L;
        s.solve();

        T.check("the node budget was respected", s.nodes <= s.maxNodes, "nodes=" + s.nodes);
        T.check("the run reports that it aborted", s.aborted);
        T.check("the row-major solver made progress", s.bestDepth > 60,
                "bestDepth=" + s.bestDepth);
        System.out.println("         row-major bounded run: nodes=" + s.nodes
                           + " bestDepth=" + s.bestDepth + "/256");

        T.notNull("a deepest-board snapshot was captured", s.bestBoard);
        if (s.bestBoard != null) {
            Instance e2 = Instance.eternity2();
            T.isNull("the deepest row-major partial board is internally consistent",
                     Validator.validatePartial(e2, s.bestBoard, false));

            // row-major means the filled cells are exactly a prefix 0..bestDepth-1
            boolean prefixOk = true;
            for (int i = 0; i < 256; i++) {
                boolean filled = s.bestBoard[i] >= 0;
                boolean shouldBeFilled = i < s.bestDepth;
                if (filled != shouldBeFilled) { prefixOk = false; break; }
            }
            T.check("the row-major snapshot fills exactly a prefix of the cells", prefixOk);
        }

        // determinism
        Solver s2 = new Solver();
        s2.verbose = false;
        s2.maxNodes = 200000L;
        s2.solve();
        T.eq("the row-major search is deterministic (node count)", s.nodes, s2.nodes);
        T.eq("the row-major search is deterministic (best depth)", s.bestDepth, s2.bestDepth);

        // a tiny budget must stop almost immediately
        Solver s3 = new Solver();
        s3.verbose = false;
        s3.maxNodes = 10L;
        s3.solve();
        T.check("a 10-node budget stops the search at once", s3.nodes <= 10 && s3.aborted,
                "nodes=" + s3.nodes);
    }
}
