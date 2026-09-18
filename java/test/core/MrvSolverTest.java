package core;

import java.util.Random;

/**
 * Tests for the bitset machinery and MRV cell selection inside
 * {@link MrvSolver}.
 *
 * The most valuable tests here are:
 *   - bitset tables agree with an independent recomputation from the pieces;
 *   - the cached per-cell constraint masks agree with a brute-force
 *     enumeration of the rules, in many different board states;
 *   - place() followed by unplace() restores every byte of solver state,
 *     which is the single most common source of bugs in a backtracking
 *     search.
 */
public final class MrvSolverTest {

    public static void run() {
        T.section("MrvSolverTest: bitset tables");
        tablesTest();
        T.endSection();

        T.section("MrvSolverTest: cached cell masks vs brute force");
        cellMaskTest();
        T.endSection();

        T.section("MrvSolverTest: incremental counts match full recomputation");
        incrementalCountTest();
        T.endSection();

        T.section("MrvSolverTest: place/unplace is a perfect inverse");
        placeUnplaceTest();
        T.endSection();

        T.section("MrvSolverTest: MRV cell selection");
        selectionTest();
        T.endSection();

        T.section("MrvSolverTest: solving generated instances");
        solveGeneratedTest();
        T.endSection();

        T.section("MrvSolverTest: hard instances near the phase transition");
        hardnessPeakTest();
        T.endSection();

        T.section("MrvSolverTest: Eternity II under a node budget");
        eternityBudgetTest();
        T.endSection();
    }

    // ------------------------------------------------------------------ tables

    private static void tablesTest() {
        Instance e2 = Instance.eternity2();
        MrvSolver s = new MrvSolver(e2);

        T.eq("exist bitset size equals canonical variant count",
             e2.countCanonicalVariants(), s.existCount());

        // every existing variant's sides come back correctly
        boolean sidesOk = true;
        String detail = null;
        for (int id = 0; id < e2.numPieces && sidesOk; id++) {
            for (int r = 0; r < 4; r++) {
                int v = (id << 2) | r;
                boolean shouldExist = e2.isCanonicalRotation(id, r);
                if (s.variantExists(v) != shouldExist) {
                    sidesOk = false;
                    detail = "variant " + v + " exists=" + s.variantExists(v)
                           + " expected " + shouldExist;
                    break;
                }
                if (shouldExist && s.sidesOfVariant(v) != e2.variantSides(id, r)) {
                    sidesOk = false;
                    detail = "variant " + v + " sides mismatch";
                    break;
                }
            }
        }
        T.check("exist bitset and variantSides agree with the instance", sidesOk, detail);

        // colour masks: summing over all colours must recover every variant,
        // for each of the four sides
        boolean maskSumsOk = true;
        for (int side = 0; side < 4; side++) {
            int total = 0;
            for (int c = 0; c < e2.numColours; c++) total += s.colourMaskCount(side, c);
            if (total != s.existCount()) {
                maskSumsOk = false;
                T.fail("colour masks partition the variants for side " + side,
                       "sum=" + total + " exist=" + s.existCount());
            }
        }
        if (maskSumsOk) T.ok("colour masks partition the variants on all four sides");

        // spot check: count of variants with grey on the left must equal the
        // number of canonical variants whose left side is grey
        int expectGreyLeft = 0;
        for (int id = 0; id < e2.numPieces; id++) {
            for (int r = 0; r < 4; r++) {
                if (!e2.isCanonicalRotation(id, r)) continue;
                if (Sides.left(e2.variantSides(id, r)) == Sides.GREY) expectGreyLeft++;
            }
        }
        T.eq("maskL[GREY] population is correct",
             expectGreyLeft, s.colourMaskCount(Sides.LEFT, Sides.GREY));

        // after reset the only unavailable piece is the fixed one
        int expectedAvail = s.existCount();
        for (int r = 0; r < 4; r++) {
            if (e2.isCanonicalRotation(138, r)) expectedAvail--;
        }
        T.eq("avail excludes exactly the fixed piece's variants",
             expectedAvail, s.availCount());
        T.check("the fixed piece is marked used", s.isUsed(138));
        T.eq("one piece placed after reset", 1, s.placedCount());
        T.eq("the fixed piece sits on its cell", 138, s.pieceAt(8 * 16 + 7));
        T.eq("the fixed piece has rotation 1", 1, s.rotAt(8 * 16 + 7));
    }

    // --------------------------------------------------------------- cell masks

    /**
     * Independent, slow computation of the legal variants at a cell, applying
     * exactly the rules MrvSolver claims to apply:
     *   - off-board side must be grey
     *   - side facing a placed neighbour must equal that neighbour's side
     *   - side facing an empty on-board neighbour must NOT be grey
     *   - the variant's piece must be unused
     */
    private static int bruteForceCandidateCount(Instance inst, int[] board,
                                                boolean[] used, int cell) {
        int n = inst.n;
        int r = cell / n, c = cell - r * n;
        int count = 0;
        for (int id = 0; id < inst.numPieces; id++) {
            if (used[id]) continue;
            for (int rot = 0; rot < 4; rot++) {
                if (!inst.isCanonicalRotation(id, rot)) continue;
                int p = inst.variantSides(id, rot);

                // left
                if (c == 0) { if (Sides.left(p) != Sides.GREY) continue; }
                else {
                    int nb = board[cell - 1];
                    if (nb >= 0) {
                        if (Sides.left(p) != Sides.right(inst.variantSides(nb >>> 2, nb & 3))) continue;
                    } else {
                        if (Sides.left(p) == Sides.GREY) continue;
                    }
                }
                // top
                if (r == 0) { if (Sides.top(p) != Sides.GREY) continue; }
                else {
                    int nb = board[cell - n];
                    if (nb >= 0) {
                        if (Sides.top(p) != Sides.bottom(inst.variantSides(nb >>> 2, nb & 3))) continue;
                    } else {
                        if (Sides.top(p) == Sides.GREY) continue;
                    }
                }
                // right
                if (c == n - 1) { if (Sides.right(p) != Sides.GREY) continue; }
                else {
                    int nb = board[cell + 1];
                    if (nb >= 0) {
                        if (Sides.right(p) != Sides.left(inst.variantSides(nb >>> 2, nb & 3))) continue;
                    } else {
                        if (Sides.right(p) == Sides.GREY) continue;
                    }
                }
                // bottom
                if (r == n - 1) { if (Sides.bottom(p) != Sides.GREY) continue; }
                else {
                    int nb = board[cell + n];
                    if (nb >= 0) {
                        if (Sides.bottom(p) != Sides.top(inst.variantSides(nb >>> 2, nb & 3))) continue;
                    } else {
                        if (Sides.bottom(p) == Sides.GREY) continue;
                    }
                }
                count++;
            }
        }
        return count;
    }

    private static void cellMaskTest() {
        // small instance so the brute force is cheap, plus the real E2 board
        Instance[] instances = new Instance[3];
        instances[0] = Generator.generate(4, 3, 12345L, true, false);
        instances[1] = Generator.generate(5, 4, 777L, true, true);
        instances[2] = Instance.eternity2();

        String[] names = { "generated 4x4", "generated 5x5 with fixed piece", "Eternity II 16x16" };

        for (int k = 0; k < instances.length; k++) {
            Instance inst = instances[k];
            MrvSolver s = new MrvSolver(inst);
            Random rnd = new Random(99L + k);

            boolean allMatch = true;
            String detail = null;

            // check the initial state, then after each of several random legal
            // placements, so masks are verified as the board fills up
            for (int step = 0; step < 12 && allMatch; step++) {
                int[] board = s.boardSnapshot();
                boolean[] used = s.usedSnapshot();
                for (int cell = 0; cell < inst.cells; cell++) {
                    if (board[cell] >= 0) continue;
                    int got = s.candidateCount(cell);
                    int want = bruteForceCandidateCount(inst, board, used, cell);
                    if (got != want) {
                        allMatch = false;
                        detail = names[k] + ": step " + step + " cell " + cell
                               + " cached=" + got + " bruteForce=" + want;
                        break;
                    }
                }
                if (!allMatch) break;

                // make a random legal placement to advance the state
                int chosen = -1, chosenVariant = -1;
                for (int attempt = 0; attempt < 200 && chosen < 0; attempt++) {
                    int cell = rnd.nextInt(inst.cells);
                    if (s.variantAt(cell) >= 0) continue;
                    int cnt = s.candidateCount(cell);
                    if (cnt == 0) continue;
                    // pick the m-th legal variant
                    int m = rnd.nextInt(cnt);
                    int seen = 0;
                    for (int v = 0; v < inst.numVariants; v++) {
                        if (!s.isLegalHere(cell, v)) continue;
                        if (seen == m) { chosen = cell; chosenVariant = v; break; }
                        seen++;
                    }
                }
                if (chosen < 0) break;     // board is stuck; fine, test ends early
                s.place(chosen, chosenVariant);
            }
            T.check("cached cell masks match brute force (" + names[k] + ")", allMatch, detail);
        }

        // isLegalHere must agree with candidateCount
        Instance inst = Generator.generate(4, 3, 5150L, true, false);
        MrvSolver s = new MrvSolver(inst);
        boolean agree = true;
        for (int cell = 0; cell < inst.cells && agree; cell++) {
            int manual = 0;
            for (int v = 0; v < inst.numVariants; v++) if (s.isLegalHere(cell, v)) manual++;
            if (manual != s.candidateCount(cell)) agree = false;
        }
        T.check("isLegalHere agrees with candidateCount for every cell", agree);
    }

    // ------------------------------------------------------- incremental counts

    /**
     * cellCount[] is maintained incrementally: a placement decrements every
     * other empty cell by the number of the used piece's variants that cell
     * could have taken, and neighbours are rebuilt from scratch.  That is a
     * classic source of silent drift, so here it is checked against the
     * authoritative recomputation after every single place and unplace, over
     * deep random walks on several instances.
     */
    private static void incrementalCountTest() {
        Instance[] instances = new Instance[4];
        instances[0] = Instance.eternity2();
        instances[1] = Generator.generate(5, 4, 10101L, true, true);
        instances[2] = Generator.generate(8, 12, 20202L, true, false);
        instances[3] = Generator.generate(4, 2, 30303L, true, false);
        String[] names = { "Eternity II", "generated 5x5", "generated 8x8", "generated 4x4" };

        for (int k = 0; k < instances.length; k++) {
            Instance inst = instances[k];
            MrvSolver s = new MrvSolver(inst);
            Random rnd = new Random(555L + k);

            boolean ok = true;
            String detail = null;
            int[] stack = new int[inst.cells];
            int depth = 0;

            // walk down, checking after every placement
            for (int step = 0; step < 60 && ok; step++) {
                ok = countsAgree(s, inst);
                if (!ok) { detail = names[k] + ": drift after " + depth + " placements"; break; }

                int chosen = -1, chosenVariant = -1;
                for (int attempt = 0; attempt < 400 && chosen < 0; attempt++) {
                    int c = rnd.nextInt(inst.cells);
                    if (s.variantAt(c) >= 0) continue;
                    int cnt = s.candidateCount(c);
                    if (cnt == 0) continue;
                    int m = rnd.nextInt(cnt);
                    int seen = 0;
                    for (int v = 0; v < inst.numVariants; v++) {
                        if (!s.isLegalHere(c, v)) continue;
                        if (seen == m) { chosen = c; chosenVariant = v; break; }
                        seen++;
                    }
                }
                if (chosen < 0) break;
                s.place(chosen, chosenVariant);
                stack[depth++] = chosen;
            }

            // walk back up, checking after every removal
            while (depth > 0 && ok) {
                s.unplace(stack[--depth]);
                ok = countsAgree(s, inst);
                if (!ok) detail = names[k] + ": drift while unwinding at depth " + depth;
            }

            T.check("incremental counts never drift (" + names[k] + ")", ok, detail);
        }

        // also check it through a real search: run a bounded solve, then verify
        // the state is still coherent afterwards
        Instance e2 = Instance.eternity2();
        MrvSolver r = new MrvSolver(e2);
        r.maxNodes = 50000L;
        r.solve();
        // after an aborted search the stack has been unwound back to the fixed
        // piece, so counts must match a freshly reset solver
        MrvSolver fresh = new MrvSolver(Instance.eternity2());
        T.eqIntArray("counts after an aborted search match a fresh solver",
                     fresh.cellCountSnapshot(), r.cellCountSnapshot());
        T.eqLongArray("masks after an aborted search match a fresh solver",
                      fresh.cellMaskSnapshot(), r.cellMaskSnapshot());
        T.eqLongArray("avail after an aborted search matches a fresh solver",
                      fresh.availSnapshot(), r.availSnapshot());
    }

    /** Every empty cell's cached count must equal the recomputed count. */
    private static boolean countsAgree(MrvSolver s, Instance inst) {
        for (int cell = 0; cell < inst.cells; cell++) {
            if (s.variantAt(cell) >= 0) continue;
            if (s.cachedCandidateCount(cell) != s.candidateCount(cell)) return false;
        }
        return true;
    }

    // ------------------------------------------------------------ place/unplace

    private static void placeUnplaceTest() {
        // Single place/unplace on the real instance
        Instance e2 = Instance.eternity2();
        MrvSolver s = new MrvSolver(e2);

        long[] avail0 = s.availSnapshot();
        long[] masks0 = s.cellMaskSnapshot();
        int[] counts0 = s.cellCountSnapshot();
        int[] board0 = s.boardSnapshot();
        boolean[] used0 = s.usedSnapshot();
        int placed0 = s.placedCount();

        // find a legal placement on cell 0 (a corner)
        int cell = 0, variant = -1;
        for (int v = 0; v < e2.numVariants; v++) {
            if (s.isLegalHere(cell, v)) { variant = v; break; }
        }
        T.check("found a legal variant for the top-left corner", variant >= 0);

        s.place(cell, variant);
        T.eq("placed count increases", placed0 + 1, s.placedCount());
        T.check("the piece is now marked used", s.isUsed(variant >>> 2));
        T.check("avail shrank", s.availCount() < countBits(avail0));

        s.unplace(cell);
        T.eqLongArray("unplace restores avail exactly", avail0, s.availSnapshot());
        T.eqLongArray("unplace restores all cell masks exactly", masks0, s.cellMaskSnapshot());
        T.eqIntArray("unplace restores all cell counts exactly", counts0, s.cellCountSnapshot());
        T.eqIntArray("unplace restores the board exactly", board0, s.boardSnapshot());
        T.eqBoolArray("unplace restores used[] exactly", used0, s.usedSnapshot());
        T.eq("unplace restores the placed count", placed0, s.placedCount());

        // Deep random walk: place many pieces, then unwind, on several instances
        Instance[] instances = new Instance[3];
        instances[0] = Instance.eternity2();
        instances[1] = Generator.generate(6, 5, 31337L, true, true);
        instances[2] = Generator.generate(4, 2, 4242L, true, false);
        String[] names = { "Eternity II", "generated 6x6", "generated 4x4" };

        for (int k = 0; k < instances.length; k++) {
            Instance inst = instances[k];
            MrvSolver w = new MrvSolver(inst);
            long[] a0 = w.availSnapshot();
            long[] m0 = w.cellMaskSnapshot();
            int[] n0 = w.cellCountSnapshot();
            int[] b0 = w.boardSnapshot();
            boolean[] u0 = w.usedSnapshot();
            int p0 = w.placedCount();

            Random rnd = new Random(2024L + k);
            int[] stack = new int[inst.cells];
            int depth = 0;
            for (int step = 0; step < 40; step++) {
                int chosen = -1, chosenVariant = -1;
                for (int attempt = 0; attempt < 400 && chosen < 0; attempt++) {
                    int c = rnd.nextInt(inst.cells);
                    if (w.variantAt(c) >= 0) continue;
                    int cnt = w.candidateCount(c);
                    if (cnt == 0) continue;
                    int m = rnd.nextInt(cnt);
                    int seen = 0;
                    for (int v = 0; v < inst.numVariants; v++) {
                        if (!w.isLegalHere(c, v)) continue;
                        if (seen == m) { chosen = c; chosenVariant = v; break; }
                        seen++;
                    }
                }
                if (chosen < 0) break;
                w.place(chosen, chosenVariant);
                stack[depth++] = chosen;
            }
            System.out.println("         (" + names[k] + ") random walk reached depth " + depth);
            while (depth > 0) w.unplace(stack[--depth]);

            T.eqLongArray("random walk then unwind restores avail (" + names[k] + ")",
                          a0, w.availSnapshot());
            T.eqLongArray("random walk then unwind restores cell masks (" + names[k] + ")",
                          m0, w.cellMaskSnapshot());
            T.eqIntArray("random walk then unwind restores cell counts (" + names[k] + ")",
                         n0, w.cellCountSnapshot());
            T.eqIntArray("random walk then unwind restores board (" + names[k] + ")",
                         b0, w.boardSnapshot());
            T.eqBoolArray("random walk then unwind restores used[] (" + names[k] + ")",
                          u0, w.usedSnapshot());
            T.eq("random walk then unwind restores placed count (" + names[k] + ")",
                 p0, w.placedCount());
        }

        // unplacing an empty cell must be rejected
        MrvSolver z = new MrvSolver(Generator.generate(3, 2, 1L, true, false));
        boolean threw = false;
        try { z.unplace(0); } catch (Throwable ex) { threw = true; }
        T.threw("unplacing an empty cell throws", threw);

        // reset() must return to a pristine state
        Instance g = Generator.generate(5, 4, 808L, true, true);
        MrvSolver rs = new MrvSolver(g);
        long[] ra = rs.availSnapshot();
        long[] rm = rs.cellMaskSnapshot();
        int[] rn = rs.cellCountSnapshot();
        int[] rb = rs.boardSnapshot();
        for (int v = 0; v < g.numVariants; v++) {
            if (rs.isLegalHere(7, v)) { rs.place(7, v); break; }
        }
        rs.reset();
        T.eqLongArray("reset restores avail", ra, rs.availSnapshot());
        T.eqLongArray("reset restores cell masks", rm, rs.cellMaskSnapshot());
        T.eqIntArray("reset restores cell counts", rn, rs.cellCountSnapshot());
        T.eqIntArray("reset restores board", rb, rs.boardSnapshot());
    }

    private static int countBits(long[] bs) {
        int c = 0;
        for (int i = 0; i < bs.length; i++) c += Long.bitCount(bs[i]);
        return c;
    }

    // ---------------------------------------------------------------- selection

    private static void selectionTest() {
        Instance inst = Generator.generate(5, 4, 606L, true, false);
        MrvSolver s = new MrvSolver(inst);

        // MRV must return the cell with the globally minimum candidate count
        s.useMrv = true;
        int chosen = s.selectCell();
        T.check("selectCell returns a valid empty cell",
                chosen >= 0 && chosen < inst.cells && s.variantAt(chosen) < 0);
        int chosenCount = s.candidateCount(chosen);
        int trueMin = Integer.MAX_VALUE;
        for (int cell = 0; cell < inst.cells; cell++) {
            if (s.variantAt(cell) >= 0) continue;
            int c = s.candidateCount(cell);
            if (c < trueMin) trueMin = c;
        }
        T.eq("MRV selects a cell with the minimum candidate count", trueMin, chosenCount);

        // with MRV off, the lowest-index empty cell is returned
        s.useMrv = false;
        int firstEmpty = -1;
        for (int cell = 0; cell < inst.cells; cell++) {
            if (s.variantAt(cell) < 0) { firstEmpty = cell; break; }
        }
        T.eq("with useMrv=false the lowest-index empty cell is chosen",
             firstEmpty, s.selectCell());
        s.useMrv = true;

        // after filling the board into a state with a forced cell, MRV must
        // prefer the forced (single candidate) cell
        Random rnd = new Random(11L);
        boolean sawForced = false;
        for (int step = 0; step < 60 && !sawForced; step++) {
            int cell = s.selectCell();
            if (cell < 0) break;
            // does any cell have exactly one candidate?
            int forcedCell = -1;
            for (int c = 0; c < inst.cells; c++) {
                if (s.variantAt(c) >= 0) continue;
                if (s.candidateCount(c) == 1) { forcedCell = c; break; }
            }
            if (forcedCell >= 0) {
                T.eq("MRV picks a forced cell when one exists", 1, s.candidateCount(cell));
                sawForced = true;
                break;
            }
            // advance with a random legal placement
            int cnt = s.candidateCount(cell);
            if (cnt == 0) break;
            int m = rnd.nextInt(cnt);
            int seen = 0, pick = -1;
            for (int v = 0; v < inst.numVariants; v++) {
                if (!s.isLegalHere(cell, v)) continue;
                if (seen == m) { pick = v; break; }
                seen++;
            }
            if (pick < 0) break;
            s.place(cell, pick);
        }
        T.check("a forced-cell situation was reached and handled", sawForced,
                sawForced ? null : "never observed a single-candidate cell (heuristic untested)");

        // dead-end detection: selectCell returns -1 when a cell has 0 candidates
        MrvSolver d = new MrvSolver(Generator.generate(4, 2, 31L, true, false));
        boolean sawDeadEnd = false;
        Random r2 = new Random(5L);
        for (int trial = 0; trial < 400 && !sawDeadEnd; trial++) {
            d.reset();
            for (int step = 0; step < 16; step++) {
                int cell = -1;
                for (int c = 0; c < d.inst.cells; c++) {
                    if (d.variantAt(c) < 0) { cell = c; break; }
                }
                if (cell < 0) break;
                int cnt = d.candidateCount(cell);
                if (cnt == 0) break;
                int m = r2.nextInt(cnt);
                int seen = 0, pick = -1;
                for (int v = 0; v < d.inst.numVariants; v++) {
                    if (!d.isLegalHere(cell, v)) continue;
                    if (seen == m) { pick = v; break; }
                    seen++;
                }
                if (pick < 0) break;
                d.place(cell, pick);
                if (d.selectCell() < 0) { sawDeadEnd = true; break; }
            }
        }
        T.check("selectCell returns -1 on a dead board (full-board forward check)",
                sawDeadEnd,
                sawDeadEnd ? null : "could not construct a dead end in 400 random trials");
    }

    // ------------------------------------------------------------------ solving

    private static void solveGeneratedTest() {
        // A generated instance is solvable by construction, so the solver must
        // find a solution and the independent validator must accept it.
        //
        // Colour counts are chosen on the constrained side of the random-CSP
        // phase transition so these cases are reliably fast.  The hard region
        // in the middle is exercised separately by hardnessPeakTest(), which
        // only asserts progress rather than completion.  The 16x16 and 20x20
        // cases prove the solver works at and beyond the real board size.
        int[] sizes   = { 3, 4, 5, 6, 8, 10, 16, 20 };
        int[] colours = { 2, 3, 4, 5, 12, 24, 64, 96 };
        long[] seeds  = { 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L };

        for (int i = 0; i < sizes.length; i++) {
            Instance inst = Generator.generate(sizes[i], colours[i], seeds[i], true, false);
            MrvSolver s = new MrvSolver(inst);
            s.stopAtFirstSolution = true;
            s.maxNodes = 20000000L;
            long found = s.solve();
            String label = sizes[i] + "x" + sizes[i] + " (" + colours[i] + " colours)";
            T.check("solver finds a solution for generated " + label, found >= 1,
                    "aborted=" + s.aborted + " nodes=" + s.nodes);
            if (found >= 1) {
                String err = Validator.validateComplete(inst, s.solutionBoard);
                T.isNull("solution for generated " + label + " passes validation", err);
            }
        }

        // with a fixed piece, the solution must honour it
        Instance fixedInst = Generator.generate(6, 5, 909L, true, true);
        MrvSolver fs = new MrvSolver(fixedInst);
        fs.maxNodes = 20000000L;
        long found = fs.solve();
        T.check("solver finds a solution with a fixed piece", found >= 1);
        if (found >= 1) {
            T.isNull("solution with a fixed piece passes validation",
                     Validator.validateComplete(fixedInst, fs.solutionBoard));
            int fc = fixedInst.fixedCell[0];
            T.eq("the fixed piece stayed on its cell",
                 fixedInst.fixedPiece[0], fs.solutionBoard[fc] >>> 2);
            T.eq("the fixed piece kept its orientation",
                 fixedInst.variantSides(fixedInst.fixedPiece[0], fixedInst.fixedRot[0]),
                 fixedInst.variantSides(fs.solutionBoard[fc] >>> 2, fs.solutionBoard[fc] & 3));
        }

        // an unsatisfiable instance must report zero solutions
        Instance base = Generator.generate(4, 3, 77L, true, false);
        int[][] broken = base.piecesCopy();
        broken[0][0] = 0; broken[0][1] = 0; broken[0][2] = 0; broken[0][3] = 0;  // all grey
        Instance bad = new Instance(4, broken, null, null, null);
        MrvSolver bs = new MrvSolver(bad);
        bs.stopAtFirstSolution = false;
        bs.maxNodes = 5000000L;
        long n = bs.solve();
        T.eq("an all-grey piece makes the instance unsatisfiable", 0, n);
        T.check("the unsatisfiable search completed without aborting", !bs.aborted);

        // useMrv=false must find the same number of solutions as useMrv=true
        for (int seed = 1; seed <= 3; seed++) {
            Instance inst = Generator.generate(4, 3, 500L + seed, true, false);
            MrvSolver a = new MrvSolver(inst);
            a.stopAtFirstSolution = false;
            a.useMrv = true;
            a.maxNodes = 20000000L;
            long ca = a.solve();

            MrvSolver b = new MrvSolver(inst);
            b.stopAtFirstSolution = false;
            b.useMrv = false;
            b.maxNodes = 20000000L;
            long cb = b.solve();

            T.check("MRV and row-major enumerate the same solution count (seed "
                    + (500 + seed) + ")", ca == cb && !a.aborted && !b.aborted,
                    "mrv=" + ca + " rowMajor=" + cb
                    + " abortedA=" + a.aborted + " abortedB=" + b.aborted);
            System.out.println("         seed " + (500 + seed) + ": solutions=" + ca
                               + "  nodes mrv=" + a.nodes + " rowMajor=" + b.nodes);
        }

        // MRV should need fewer nodes overall than lowest-index ordering
        long mrvNodes = 0, rowNodes = 0;
        for (int seed = 1; seed <= 4; seed++) {
            Instance inst = Generator.generate(6, 5, 900L + seed, true, false);
            MrvSolver a = new MrvSolver(inst);
            a.useMrv = true; a.maxNodes = 20000000L; a.solve();
            MrvSolver b = new MrvSolver(inst);
            b.useMrv = false; b.maxNodes = 20000000L; b.solve();
            mrvNodes += a.nodes;
            rowNodes += b.nodes;
        }
        System.out.println("         total nodes to first solution over 4 6x6 instances:"
                           + " MRV=" + mrvNodes + "  rowMajor=" + rowNodes);
        T.check("MRV expands no more nodes than lowest-index ordering in aggregate",
                mrvNodes <= rowNodes,
                "MRV=" + mrvNodes + " rowMajor=" + rowNodes);
    }

    // ---------------------------------------------------------- hardness peak

    /**
     * Random edge-matching instances show the usual CSP phase transition: with
     * few colours there are many solutions and search is easy, with many
     * colours the instance is tightly constrained and search is easy again,
     * and in between lies a hard region where backtracking search blows up.
     *
     * Real Eternity II (16x16 with 22 colours) sits squarely in that hard
     * region, which is exactly why it is unsolved.  This test pins the
     * behaviour down so a future change that accidentally makes the easy
     * regimes hard (or breaks the budget handling) is caught.
     */
    private static void hardnessPeakTest() {
        // Easy side: very few colours -> huge solution count.
        Instance easyLow = Generator.generate(8, 3, 41L, true, false);
        MrvSolver a = new MrvSolver(easyLow);
        a.maxNodes = 2000000L;
        long fa = a.solve();
        T.check("a lightly-constrained 8x8 instance is solved quickly", fa >= 1,
                "nodes=" + a.nodes + " aborted=" + a.aborted);

        // Easy side: many colours -> tightly constrained.
        Instance easyHigh = Generator.generate(8, 24, 41L, true, false);
        MrvSolver b = new MrvSolver(easyHigh);
        b.maxNodes = 2000000L;
        long fb = b.solve();
        T.check("a tightly-constrained 8x8 instance is solved quickly", fb >= 1,
                "nodes=" + b.nodes + " aborted=" + b.aborted);
        System.out.println("         8x8 easy regimes: lowColour nodes=" + a.nodes
                           + ", highColour nodes=" + b.nodes);

        // Hard region: bounded run, we only require correct bookkeeping and a
        // consistent partial board -- NOT a solution.
        Instance hard = Generator.generate(12, 12, 43L, true, false);
        MrvSolver h = new MrvSolver(hard);
        h.maxNodes = 400000L;
        long fh = h.solve();
        T.check("a phase-transition instance respects the node budget",
                h.nodes <= h.maxNodes, "nodes=" + h.nodes);
        T.check("a phase-transition instance either solves or aborts cleanly",
                fh >= 1 || h.aborted);
        T.notNull("a deepest-board snapshot exists for the hard instance", h.bestBoard);
        if (h.bestBoard != null) {
            T.isNull("the hard instance's deepest partial board is consistent",
                     Validator.validatePartial(hard, h.bestBoard, false));
        }
        System.out.println("         12x12 hard-region run: nodes=" + h.nodes
                           + " bestPlaced=" + h.bestPlaced + "/" + hard.cells
                           + " solved=" + (fh >= 1));
    }

    // ------------------------------------------------------------ E2 with budget

    private static void eternityBudgetTest() {
        Instance e2 = Instance.eternity2();
        MrvSolver s = new MrvSolver(e2);
        s.maxNodes = 300000L;
        s.stopAtFirstSolution = true;
        s.verbose = false;
        long found = s.solve();

        T.check("the bounded Eternity II run respected the node budget",
                s.nodes <= s.maxNodes, "nodes=" + s.nodes);
        T.check("the bounded Eternity II run aborted rather than finishing",
                s.aborted || found >= 1);
        T.check("the solver made real progress on Eternity II",
                s.bestPlaced > 60, "bestPlaced=" + s.bestPlaced);
        System.out.println("         E2 bounded run: nodes=" + s.nodes
                           + " bestPlaced=" + s.bestPlaced + "/256");

        T.notNull("a deepest-board snapshot was captured", s.bestBoard);
        if (s.bestBoard != null) {
            String err = Validator.validatePartial(e2, s.bestBoard, false);
            T.isNull("the deepest partial Eternity II board is internally consistent", err);
            int filled = 0;
            for (int i = 0; i < s.bestBoard.length; i++) if (s.bestBoard[i] >= 0) filled++;
            T.eq("the snapshot has exactly bestPlaced pieces on it", s.bestPlaced, filled);
        }

        // the fixed piece must be present in the snapshot
        if (s.bestBoard != null) {
            T.eq("the snapshot keeps piece 139 on its fixed cell",
                 138, s.bestBoard[8 * 16 + 7] >>> 2);
        }

        // a second solver on the same instance must behave identically
        MrvSolver s2 = new MrvSolver(Instance.eternity2());
        s2.maxNodes = 300000L;
        s2.verbose = false;
        s2.solve();
        T.eq("the search is deterministic (same node count on a rerun)", s.nodes, s2.nodes);
        T.eq("the search is deterministic (same best depth on a rerun)",
             s.bestPlaced, s2.bestPlaced);
    }
}
