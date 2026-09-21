package core;

import java.util.Random;

/**
 * {@link Repair}: lifting k pairwise non-adjacent pieces off a finished board
 * and putting them back by maximum-weight bipartite matching.
 *
 * Two things could be wrong here and each needs its own oracle.
 *
 * The <b>matching</b> could return something that is not optimal, and no
 * amount of board scoring would show it, because a suboptimal matching still
 * produces a legal board.  So {@link #theMatchingIsTheTrueOptimum()} compares
 * it against brute force: every permutation, enumerated, on matrices small
 * enough to do that exhaustively.
 *
 * The <b>repair</b> could produce a board that scores well and is not legal --
 * a piece used twice, a grey side pushed into the interior, the hint piece
 * moved.  Those are read back off the board that came out, by this file's own
 * counting, and also handed to {@link Validator}, which was written without
 * reference to any of this.
 *
 * Every board used below is produced by a seeded search or built by hand, and
 * every neighbourhood is seeded, so the whole file is deterministic.
 */
public final class RepairTest {

    public static void run() {
        T.section("RepairTest: very large neighbourhood repair");

        theMatchingIsTheTrueOptimum();
        aSwapIsUndoneExactly();
        itRefusesAnAdjacentNeighbourhood();
        itRefusesToLiftAFixedPiece();
        itNeverLowersTheScore();
        itPutsBackWhatRandomSwapsTookAway();
        itLeavesTheHintPieceWhereItIs();
        itKeepsGreyOnTheRimAndNowhereElse();
        itUsesEveryPieceAtMostOnce();
        itIsRepeatable();

        T.endSection();
    }

    // ------------------------------------------------------- Hungarian vs brute force

    /**
     * The matching against exhaustive enumeration, for every size up to 6
     * (720 permutations).  Both the total weight and the shape of the answer
     * are checked: ties are common with weights this small, so the permutation
     * itself is only required to be a permutation, while its value must equal
     * the true optimum exactly.
     */
    private static void theMatchingIsTheTrueOptimum() {
        Random rnd = new Random(20080601L);      // Schaus and Deville, JFPC 2008
        int cases = 0, agreed = 0;

        for (int size = 1; size <= 6; size++) {
            for (int trial = 0; trial < 60; trial++) {
                // 0..4 is the range a real repair produces; the wider range and
                // the occasional huge negative exercise ties and infeasibility.
                int span = (trial % 3 == 0) ? 5 : 40;
                int[][] w = new int[size][size];
                for (int i = 0; i < size; i++) {
                    for (int j = 0; j < size; j++) {
                        w[i][j] = (trial % 5 == 4 && rnd.nextInt(4) == 0)
                                ? -1000000
                                : rnd.nextInt(span) - (trial % 2);
                    }
                }
                int[] got = Repair.maxWeightAssignment(w);
                cases++;
                if (isPermutation(got, size) && value(w, got) == bruteForceOptimum(w)) agreed++;
            }
        }
        T.eq("every matching equals the brute-force optimum, sizes 1..6", cases, agreed);
        T.eq("that is 360 matrices checked against every permutation", 360, cases);

        // A case whose answer can be read off by eye: the diagonal is worthless
        // and the anti-diagonal is worth 4 each, so no greedy row-by-row choice
        // can find it but the optimum is 12.
        int[][] antiDiagonal = {
            { 0, 1, 4 },
            { 1, 4, 1 },
            { 4, 1, 0 },
        };
        int[] a = Repair.maxWeightAssignment(antiDiagonal);
        T.eq("a matrix whose optimum is the anti-diagonal scores 12",
             12, value(antiDiagonal, a));

        boolean threw = false;
        try { Repair.maxWeightAssignment(new int[][] { { 1, 2 }, { 3 } }); }
        catch (IllegalArgumentException e) { threw = e.getMessage().contains("square"); }
        T.threw("a non-square weight matrix is rejected, saying so", threw);
    }

    private static boolean isPermutation(int[] rowToCol, int size) {
        if (rowToCol.length != size) return false;
        boolean[] seen = new boolean[size];
        for (int i = 0; i < size; i++) {
            int c = rowToCol[i];
            if (c < 0 || c >= size || seen[c]) return false;
            seen[c] = true;
        }
        return true;
    }

    private static long value(int[][] w, int[] rowToCol) {
        long total = 0;
        for (int i = 0; i < rowToCol.length; i++) total += w[i][rowToCol[i]];
        return total;
    }

    /** Every permutation of 0..size-1, scored; the best wins.  Only usable to ~8. */
    private static long bruteForceOptimum(int[][] w) {
        int size = w.length;
        int[] perm = new int[size];
        for (int i = 0; i < size; i++) perm[i] = i;
        long best = Long.MIN_VALUE;
        do {
            long total = 0;
            for (int i = 0; i < size; i++) total += w[i][perm[i]];
            if (total > best) best = total;
        } while (nextPermutation(perm));
        return best;
    }

    /** Lexicographic successor in place; false when the array is the last one. */
    private static boolean nextPermutation(int[] a) {
        int i = a.length - 2;
        while (i >= 0 && a[i] >= a[i + 1]) i--;
        if (i < 0) return false;
        int j = a.length - 1;
        while (a[j] <= a[i]) j--;
        int t = a[i]; a[i] = a[j]; a[j] = t;
        for (int l = i + 1, r = a.length - 1; l < r; l++, r--) {
            t = a[l]; a[l] = a[r]; a[r] = t;
        }
        return true;
    }

    // ---------------------------------------------------------------- by hand

    /**
     * The smallest case there is, worked out on paper.
     *
     * A 2x2 board of four corner pieces has one perfect arrangement and four
     * internal edges.  Swapping the two diagonal cells -- which are exactly the
     * one non-adjacent pair on this board -- leaves a board that is still legal
     * in every respect and matches nothing at all.  Repairing that pair must
     * put both pieces back and score 4, and there is no other way to get there.
     */
    private static void aSwapIsUndoneExactly() {
        // (l,t,r,b), grey = 0; colours 1..4 name the four internal edges.
        int[][] pieces = {
            { 0, 0, 1, 2 },   // piece 0, top-left
            { 1, 0, 0, 3 },   // piece 1, top-right
            { 0, 2, 4, 0 },   // piece 2, bottom-left
            { 4, 3, 0, 0 },   // piece 3, bottom-right
        };
        Instance inst = new Instance(2, pieces, null, null, null);
        T.check("the hand-built 2x2 spends its whole grey budget on the rim",
                inst.greyBudgetIsExact());

        int[] solved = { (0 << 2) | 0, (1 << 2) | 0, (2 << 2) | 0, (3 << 2) | 0 };
        T.isNull("the intended arrangement really is a solution",
                 Validator.validateComplete(inst, solved));
        T.eq("and scores every one of the four internal edges",
             4, Validator.matchedEdges(inst, solved));

        // Swap the diagonal: piece 3 turned twice fits the top-left corner and
        // piece 0 turned twice fits the bottom-right, so the damage is legal.
        int[] swapped = { (3 << 2) | 2, (1 << 2) | 0, (2 << 2) | 0, (0 << 2) | 2 };
        T.isNull("the swapped board breaks no rule but the matching one",
                 Validator.validatePartial(inst, swapped, true, 4));
        T.eq("and it matches nothing at all", 0, Validator.matchedEdges(inst, swapped));

        Repair repair = new Repair(inst);
        int[] board = copy(swapped);
        int gain = repair.repairCells(board, new int[] { 0, 3 }, 2);

        T.eq("repairing the swapped diagonal gains all four edges", 4, gain);
        T.eq("and Validator agrees about the score",
             4, Validator.matchedEdges(inst, board));
        T.eqIntArray("the board is the solution again", solved, board);

        T.eq("repairing an already-optimal neighbourhood gains nothing",
             0, repair.repairCells(board, new int[] { 0, 3 }, 2));
        T.eqIntArray("and leaves the board alone", solved, board);
    }

    // ------------------------------------------------------------- refusals

    private static void itRefusesAnAdjacentNeighbourhood() {
        Instance inst = Instance.eternity2();
        int[] board = searchedBoard(300000L);
        Repair repair = new Repair(inst);

        String message = null;
        try { repair.repairCells(copy(board), new int[] { 100, 101 }, 2); }
        catch (IllegalArgumentException e) { message = e.getMessage(); }
        T.notNull("two side-by-side cells are refused as a neighbourhood", message);
        T.check("and the message names both cells and says why",
                message != null && message.contains("100") && message.contains("101")
                && message.contains("non-adjacent"), "message was: " + message);

        String twice = null;
        try { repair.repairCells(copy(board), new int[] { 100, 100 }, 2); }
        catch (IllegalArgumentException e) { twice = e.getMessage(); }
        T.check("the same cell twice is refused",
                twice != null && twice.contains("twice"), "message was: " + twice);
    }

    private static void itRefusesToLiftAFixedPiece() {
        Instance inst = Instance.eternity2();
        int fixed = inst.fixedCell[0];
        int[] board = searchedBoard(300000L);
        T.check("the search really did place the hint piece", board[fixed] >= 0);

        String message = null;
        try { new Repair(inst).repairCells(copy(board), new int[] { fixed }, 1); }
        catch (IllegalArgumentException e) { message = e.getMessage(); }
        T.check("a fixed cell may not be named as a hole",
                message != null && message.contains("fixed"), "message was: " + message);
    }

    // -------------------------------------------------------------- the loop

    /**
     * The guarantee the whole design rests on: an optimal matching is never
     * worse than leaving the pieces alone, so a repair can only raise the
     * score or leave it where it was.  Checked at several neighbourhood sizes,
     * against {@link Validator} rather than against the repair's own counting.
     */
    private static void itNeverLowersTheScore() {
        Instance inst = Instance.eternity2();
        int[] board = searchedBoard(2000000L);
        int before = Validator.matchedEdges(inst, board);
        Repair repair = new Repair(inst);

        for (int k = 2; k <= 16; k *= 2) {
            Repair.Result r = repair.run(board, k, 200, 4242L + k);
            T.eq("k=" + k + ": the reported starting score is the board's",
                 before, r.edgesBefore);
            T.check("k=" + k + ": the score never falls",
                    r.edgesAfter >= r.edgesBefore,
                    r.edgesBefore + " -> " + r.edgesAfter);
            T.eq("k=" + k + ": the reported score is what Validator counts",
                 r.edgesAfter, Validator.matchedEdges(inst, r.board));
            T.eq("k=" + k + ": the caller's board is not modified",
                 before, Validator.matchedEdges(inst, board));
            T.eq("k=" + k + ": the piece count is unchanged",
                 placedCount(board), placedCount(r.board));
        }
    }

    /**
     * That the score never falls is also true of doing nothing at all, so this
     * is the test that a repair actually repairs.
     *
     * A real board is scrambled by 30 random swaps between cells of the same
     * border class -- which keeps every rule except matching, and costs about
     * 160 of its 432 edges -- and the repair is then asked to put it back.
     * Recovering at least half of what the damage cost is a low bar for the
     * measured behaviour -- 67% to 70% across these four scrambles, in about
     * 40 ms each -- and a bar no no-op can clear.
     */
    private static void itPutsBackWhatRandomSwapsTookAway() {
        Instance inst = Instance.eternity2();
        int[] board = searchedBoard(2000000L);
        int intact = Validator.matchedEdges(inst, board);
        Repair repair = new Repair(inst);

        for (long seed = 1; seed <= 4; seed++) {
            int[] damaged = scramble(inst, board, 30, seed);
            int lost = intact - Validator.matchedEdges(inst, damaged);
            T.check("scramble " + seed + " really did damage the board",
                    lost > 50, "it cost only " + lost + " edges");

            Repair.Result r = repair.run(damaged, 16, 1000, 5L);
            T.check("scramble " + seed + ": the repair puts back over half of it",
                    r.gain() * 2 >= lost,
                    "lost " + lost + ", recovered " + r.gain());
        }
    }

    /**
     * Swap pieces between randomly chosen cells that face off the board the
     * same number of times, so a corner piece only ever lands in a corner and
     * an edge piece on an edge.  The result is illegal in no way except that
     * it matches badly, which is exactly the damage a repair is meant to undo.
     */
    private static int[] scramble(Instance inst, int[] board, int swaps, long seed) {
        int[] out = copy(board);
        Random rnd = new Random(seed);
        boolean[] fixed = new boolean[inst.cells];
        for (int i = 0; i < inst.fixedCell.length; i++) fixed[inst.fixedCell[i]] = true;

        int done = 0;
        for (int attempt = 0; done < swaps && attempt < swaps * 1000; attempt++) {
            int a = rnd.nextInt(inst.cells), b = rnd.nextInt(inst.cells);
            if (a == b || out[a] < 0 || out[b] < 0 || fixed[a] || fixed[b]) continue;
            if (offBoardSides(inst, a) != offBoardSides(inst, b)) continue;
            int pa = out[a] >>> 2, pb = out[b] >>> 2;
            int ra = rimFittingRotation(inst, a, pb), rb = rimFittingRotation(inst, b, pa);
            if (ra < 0 || rb < 0) continue;
            out[a] = (pb << 2) | ra;
            out[b] = (pa << 2) | rb;
            done++;
        }
        if (done != swaps) {
            throw new IllegalStateException("only managed " + done + " of " + swaps + " swaps");
        }
        return out;
    }

    private static int offBoardSides(Instance inst, int cell) {
        int n = inst.n, r = cell / n, c = cell % n;
        return (c == 0 ? 1 : 0) + (r == 0 ? 1 : 0) + (c == n - 1 ? 1 : 0) + (r == n - 1 ? 1 : 0);
    }

    /** A canonical rotation putting grey on exactly this cell's off-board sides, or -1. */
    private static int rimFittingRotation(Instance inst, int cell, int piece) {
        int n = inst.n, row = cell / n, col = cell % n;
        boolean[] off = { col == 0, row == 0, col == n - 1, row == n - 1 };
        for (int rot = 0; rot < 4; rot++) {
            if (!inst.isCanonicalRotation(piece, rot)) continue;
            int p = inst.variantSides(piece, rot);
            boolean good = true;
            for (int d = 0; d < 4; d++) {
                int side = Sides.side(p, d);
                if (off[d] ? side != Sides.GREY : side == Sides.GREY) { good = false; break; }
            }
            if (good) return rot;
        }
        return -1;
    }

    private static void itLeavesTheHintPieceWhereItIs() {
        Instance inst = Instance.eternity2();
        int[] board = searchedBoard(2000000L);
        int fixed = inst.fixedCell[0];

        Repair.Result r = new Repair(inst).run(board, 16, 500, 99L);
        T.eq("the hint cell still holds the same piece in the same orientation",
             board[fixed], r.board[fixed]);
        T.isNull("and Validator still accepts the fixed placement",
                 Validator.validatePartial(inst, r.board, false, 480));

        // Nothing anywhere else may claim the hint piece either.
        int occurrences = 0;
        for (int cell = 0; cell < inst.cells; cell++) {
            if (r.board[cell] >= 0 && (r.board[cell] >>> 2) == inst.fixedPiece[0]) occurrences++;
        }
        T.eq("the hint piece appears exactly once on the whole board", 1, occurrences);
    }

    /**
     * Grey is the border colour and Eternity II's grey budget is exactly the
     * number of border sides, so a grey side anywhere but the rim -- or a
     * coloured side on the rim -- makes the board unbuildable, however well it
     * scores.  Counted here off the repaired board, side by side.
     */
    private static void itKeepsGreyOnTheRimAndNowhereElse() {
        Instance inst = Instance.eternity2();
        int[] board = searchedBoard(2000000L);
        Repair.Result r = new Repair(inst).run(board, 16, 500, 7L);

        int n = inst.n;
        int colouredOnRim = 0, greyInside = 0;
        for (int cell = 0; cell < inst.cells; cell++) {
            if (r.board[cell] < 0) continue;
            int p = inst.variantSides(r.board[cell] >>> 2, r.board[cell] & 3);
            int row = cell / n, col = cell % n;
            boolean[] offBoard = { col == 0, row == 0, col == n - 1, row == n - 1 };
            for (int d = 0; d < 4; d++) {
                int side = Sides.side(p, d);
                if (offBoard[d] && side != Sides.GREY) colouredOnRim++;
                if (!offBoard[d] && side == Sides.GREY) greyInside++;
            }
        }
        T.eq("no repaired piece shows a colour off the edge of the board", 0, colouredOnRim);
        T.eq("and none shows grey on a side facing into the board", 0, greyInside);
    }

    private static void itUsesEveryPieceAtMostOnce() {
        Instance inst = Instance.eternity2();
        int[] board = searchedBoard(2000000L);
        Repair.Result r = new Repair(inst).run(board, 16, 500, 31337L);

        int[] uses = new int[inst.numPieces];
        int worst = 0;
        for (int cell = 0; cell < inst.cells; cell++) {
            if (r.board[cell] < 0) continue;
            int count = ++uses[r.board[cell] >>> 2];
            if (count > worst) worst = count;
        }
        T.eq("no piece is on the repaired board twice", 1, worst);

        // A repair permutes; it must not change WHICH pieces are down either.
        int[] wasUsed = new int[inst.numPieces];
        for (int cell = 0; cell < inst.cells; cell++) {
            if (board[cell] >= 0) wasUsed[board[cell] >>> 2]++;
        }
        boolean sameSet = true;
        for (int id = 0; id < inst.numPieces; id++) if (uses[id] != wasUsed[id]) sameSet = false;
        T.check("and the set of pieces on the board is exactly the one it started with",
                sameSet);
    }

    private static void itIsRepeatable() {
        Instance inst = Instance.eternity2();
        int[] board = searchedBoard(2000000L);
        Repair.Result a = new Repair(inst).run(board, 8, 300, 555L);
        Repair.Result b = new Repair(inst).run(board, 8, 300, 555L);
        T.eqIntArray("the same seed repairs to exactly the same board", a.board, b.board);
        T.eq("and reports the same score", a.edgesAfter, b.edgesAfter);

        Repair.Result c = new Repair(inst).run(board, 8, 0, 555L);
        T.eq("zero iterations leaves the score alone", a.edgesBefore, c.edgesAfter);
    }

    // --------------------------------------------------------------- helpers

    /** A real, imperfect Eternity II board: the scan engine slipping, to a node budget. */
    private static int[] searchedBoard(long nodeBudget) {
        SolverConfig cfg = new SolverConfig();
        cfg.slipSchedule = SolverConfig.SLIP_VERHAARD;
        cfg.nodeBudget = nodeBudget;
        ScanSolver s = new ScanSolver(Instance.eternity2(), cfg);
        s.solve();
        return s.bestBoard();
    }

    private static int placedCount(int[] board) {
        int placed = 0;
        for (int i = 0; i < board.length; i++) if (board[i] >= 0) placed++;
        return placed;
    }

    private static int[] copy(int[] a) {
        int[] b = new int[a.length];
        System.arraycopy(a, 0, b, 0, a.length);
        return b;
    }
}
