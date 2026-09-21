package core;

import java.util.Random;

/**
 * Tests for the validator.  The validator is what judges every solver's
 * output, so it has to reject broken boards as reliably as it accepts good
 * ones -- a validator that always returns null would make the rest of the
 * suite meaningless.  It also counts matched edges, which is the score every
 * published Eternity II result is quoted in.
 */
public final class ValidatorTest {

    public static void run() {
        T.section("ValidatorTest: the board checker rejects what it should");

        // Build a known-good solution by generating an instance without
        // shuffling, so piece i belongs in cell i at rotation 0.
        Instance inst = Generator.generate(4, 3, 1234L, false, false);
        int[] good = new int[inst.cells];
        for (int cell = 0; cell < inst.cells; cell++) good[cell] = (cell << 2);

        T.isNull("an unshuffled generated layout validates as a solution",
                 Validator.validateComplete(inst, good));

        // --- null / wrong size ---------------------------------------------
        T.notNull("a null board is rejected", Validator.validateComplete(inst, null));
        T.notNull("a wrong-size board is rejected",
                  Validator.validateComplete(inst, new int[inst.cells - 1]));

        // --- an empty cell is not a complete solution ----------------------
        int[] withHole = copy(good);
        withHole[5] = -1;
        T.notNull("a board with an empty cell is not a complete solution",
                  Validator.validateComplete(inst, withHole));
        T.isNull("but it is accepted as a partial board",
                 Validator.validatePartial(inst, withHole, false));
        T.notNull("and it is rejected when all pieces are required",
                  Validator.validatePartial(inst, withHole, true));

        // --- a duplicated piece ---------------------------------------------
        int[] dup = copy(good);
        dup[1] = dup[0];
        T.notNull("a duplicated piece is rejected", Validator.validateComplete(inst, dup));

        // --- a rotated piece that breaks a match ----------------------------
        int[] rotated = copy(good);
        rotated[5] = (5 << 2) | 1;      // rotate an interior piece
        T.notNull("rotating one interior piece breaks the board",
                  Validator.validateComplete(inst, rotated));

        // --- swapping two pieces --------------------------------------------
        int[] swapped = copy(good);
        int tmp = swapped[5]; swapped[5] = swapped[6]; swapped[6] = tmp;
        T.notNull("swapping two interior pieces breaks the board",
                  Validator.validateComplete(inst, swapped));

        // --- a non-grey border side -----------------------------------------
        // Build a tiny instance by hand so we can violate the border rule.
        int[][] pieces = new int[4][4];
        // 2x2 board: every piece is a corner with two grey sides
        //   cell0 (top-left): left/top grey, right=1, bottom=2
        //   cell1 (top-right): top/right grey, left=1, bottom=3
        //   cell2 (bottom-left): left/bottom grey, top=2, right=3
        //   cell3 (bottom-right): right/bottom grey, left=3, top=3
        pieces[0] = new int[] { 0, 0, 1, 2 };
        pieces[1] = new int[] { 1, 0, 0, 3 };
        pieces[2] = new int[] { 0, 2, 3, 0 };
        pieces[3] = new int[] { 3, 3, 0, 0 };
        Instance tiny = new Instance(2, pieces, null, null, null);
        int[] tinyBoard = new int[] { 0 << 2, 1 << 2, 2 << 2, 3 << 2 };
        T.isNull("the handmade 2x2 solution validates",
                 Validator.validateComplete(tiny, tinyBoard));

        // now rotate cell 0 so a coloured side faces off-board
        int[] tinyBad = new int[] { (0 << 2) | 1, 1 << 2, 2 << 2, 3 << 2 };
        T.notNull("a coloured side facing off-board is rejected",
                  Validator.validateComplete(tiny, tinyBad));

        // --- bad piece id ----------------------------------------------------
        int[] badId = copy(good);
        badId[0] = (inst.numPieces << 2);
        T.notNull("an out-of-range piece id is rejected",
                  Validator.validateComplete(inst, badId));

        // --- fixed piece enforcement -----------------------------------------
        Instance fx = Generator.generate(4, 3, 4321L, false, true);
        int[] fixedGood = new int[fx.cells];
        for (int cell = 0; cell < fx.cells; cell++) fixedGood[cell] = (cell << 2);
        T.isNull("the unshuffled layout of a fixed-piece instance validates",
                 Validator.validateComplete(fx, fixedGood));

        // move the fixed piece somewhere else by swapping it with a neighbour
        int fc = fx.fixedCell[0];
        int[] movedFixed = copy(fixedGood);
        int other = (fc + 1 < fx.cells) ? fc + 1 : fc - 1;
        int t2 = movedFixed[fc]; movedFixed[fc] = movedFixed[other]; movedFixed[other] = t2;
        T.notNull("moving the fixed piece off its cell is rejected",
                  Validator.validateComplete(fx, movedFixed));

        // --- counting matched edges ------------------------------------------
        T.eq("a 4x4 board has 24 internal edges", 24, Validator.internalEdgeTotal(inst));
        T.eq("a complete solution matches every internal edge",
             24, Validator.matchedEdges(inst, good));
        T.eq("an empty board matches nothing",
             0, Validator.matchedEdges(inst, emptyBoard(inst)));

        int[] onePair = emptyBoard(inst);
        onePair[0] = good[0];
        onePair[1] = good[1];
        T.eq("two neighbours that fit together are one matched edge",
             1, Validator.matchedEdges(inst, onePair));

        int[] diagonal = emptyBoard(inst);
        diagonal[0] = good[0];
        diagonal[inst.n + 1] = good[inst.n + 1];
        T.eq("two pieces that do not touch match nothing",
             0, Validator.matchedEdges(inst, diagonal));

        T.check("swapping two interior pieces costs matched edges",
                Validator.matchedEdges(inst, swapped) < 24,
                "swapped board still scored " + Validator.matchedEdges(inst, swapped));

        // the 2x2 board is small enough to count by hand: four internal edges,
        // all matching, and rotating the top-left piece breaks both of its own
        T.eq("the handmade 2x2 board has 4 internal edges",
             4, Validator.internalEdgeTotal(tiny));
        T.eq("the handmade 2x2 solution matches all four",
             4, Validator.matchedEdges(tiny, tinyBoard));
        T.eq("rotating the top-left piece of the 2x2 leaves two matches",
             2, Validator.matchedEdges(tiny, tinyBad));

        boolean threwOnNull = false;
        try { Validator.matchedEdges(inst, null); }
        catch (IllegalArgumentException e) { threwOnNull = true; }
        T.threw("counting the edges of a null board fails loudly", threwOnNull);

        boolean threwOnSize = false;
        try { Validator.matchedEdges(inst, new int[inst.cells - 1]); }
        catch (IllegalArgumentException e) { threwOnSize = true; }
        T.threw("counting the edges of a wrong-size board fails loudly", threwOnSize);

        mismatchParityOnEternity2();

        T.endSection();
    }

    /**
     * The parity oracle on the real puzzle: 479 out of 480 is unreachable.
     *
     * Every colour occurs an even number of times across the 256 pieces and
     * the grey budget is exact, so on a full board with a grey rim every
     * non-grey side faces another piece.  A matched pair spends two half-edges
     * of one colour, so each colour is left with an even number of mismatched
     * half-edges.  A single mismatched edge would leave two colours with one
     * each, which cannot happen: a full board scores 480, or 478 at best.
     *
     * The number of mismatched *edges* is not itself forced to be even -- three
     * mismatches can form a triangle of colours whose degrees are all even, and
     * the random boards below do land on odd counts.
     */
    private static void mismatchParityOnEternity2() {
        Instance e2 = Instance.eternity2();
        Random rnd = new Random(20240818L);
        int total = Validator.internalEdgeTotal(e2);
        T.eq("Eternity II has 480 internal edges", 480, total);

        int oddColours = 0;
        int nearPerfect = 0;
        for (int trial = 0; trial < 25; trial++) {
            int[] board = randomFullBoard(e2, rnd);
            if (Validator.matchedEdges(e2, board) == total - 1) nearPerfect++;
            int[] mismatchedHalfEdges = new int[e2.numColours];
            countMismatchedHalfEdges(e2, board, mismatchedHalfEdges);
            for (int colour = 0; colour < e2.numColours; colour++) {
                if (mismatchedHalfEdges[colour] % 2 != 0) oddColours++;
            }
        }
        T.eq("no colour is ever left with an odd number of mismatched sides",
             0, oddColours);
        T.eq("no full board ever scores 479 out of 480", 0, nearPerfect);
    }

    /** Mismatched half-edges per colour: the degrees the oracle talks about. */
    private static void countMismatchedHalfEdges(Instance inst, int[] board, int[] out) {
        int n = inst.n;
        for (int cell = 0; cell < inst.cells; cell++) {
            int p = inst.variantSides(board[cell] >>> 2, board[cell] & 3);
            int r = cell / n, c = cell - r * n;
            if (c < n - 1) {
                int q = inst.variantSides(board[cell + 1] >>> 2, board[cell + 1] & 3);
                if (Sides.right(p) != Sides.left(q)) {
                    out[Sides.right(p)]++;
                    out[Sides.left(q)]++;
                }
            }
            if (r < n - 1) {
                int q = inst.variantSides(board[cell + n] >>> 2, board[cell + n] & 3);
                if (Sides.bottom(p) != Sides.top(q)) {
                    out[Sides.bottom(p)]++;
                    out[Sides.top(q)]++;
                }
            }
        }
    }

    /**
     * A full board using every piece once with a correct grey rim, but
     * otherwise arbitrary: corners in the corners, edge pieces along the
     * sides, interior pieces inside, each rotated so its grey sides face
     * off-board.  Nearly every internal edge mismatches, which is exactly the
     * situation the parity oracle describes.
     */
    private static int[] randomFullBoard(Instance inst, Random rnd) {
        int n = inst.n;
        int[] corners = piecesWithGreyCount(inst, 2);
        int[] edges = piecesWithGreyCount(inst, 1);
        int[] interior = piecesWithGreyCount(inst, 0);
        shuffle(corners, rnd);
        shuffle(edges, rnd);
        shuffle(interior, rnd);

        int[] board = new int[inst.cells];
        int nextCorner = 0, nextEdge = 0, nextInterior = 0;
        for (int cell = 0; cell < inst.cells; cell++) {
            int r = cell / n, c = cell - r * n;
            int greySides = (r == 0 ? 1 : 0) + (r == n - 1 ? 1 : 0)
                          + (c == 0 ? 1 : 0) + (c == n - 1 ? 1 : 0);
            int id;
            if (greySides == 2) id = corners[nextCorner++];
            else if (greySides == 1) id = edges[nextEdge++];
            else id = interior[nextInterior++];
            board[cell] = (id << 2) | rotationFacingOut(inst, id, r, c);
        }
        return board;
    }

    /** The rotation that puts a piece's grey sides on the rim of the board. */
    private static int rotationFacingOut(Instance inst, int id, int r, int c) {
        int n = inst.n;
        for (int rot = 0; rot < 4; rot++) {
            int p = inst.variantSides(id, rot);
            if ((Sides.left(p) == Validator.GREY) != (c == 0)) continue;
            if ((Sides.top(p) == Validator.GREY) != (r == 0)) continue;
            if ((Sides.right(p) == Validator.GREY) != (c == n - 1)) continue;
            if ((Sides.bottom(p) == Validator.GREY) != (r == n - 1)) continue;
            return rot;
        }
        throw new IllegalStateException("piece " + (id + 1)
            + " cannot face its grey sides off-board at r" + r + ",c" + c);
    }

    private static int[] piecesWithGreyCount(Instance inst, int greyCount) {
        int found = 0;
        for (int id = 0; id < inst.numPieces; id++) {
            if (Sides.greyCount(inst.packedSides[id]) == greyCount) found++;
        }
        int[] out = new int[found];
        int w = 0;
        for (int id = 0; id < inst.numPieces; id++) {
            if (Sides.greyCount(inst.packedSides[id]) == greyCount) out[w++] = id;
        }
        return out;
    }

    private static void shuffle(int[] a, Random rnd) {
        for (int i = a.length - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            int t = a[i]; a[i] = a[j]; a[j] = t;
        }
    }

    private static int[] emptyBoard(Instance inst) {
        int[] board = new int[inst.cells];
        for (int cell = 0; cell < inst.cells; cell++) board[cell] = -1;
        return board;
    }

    private static int[] copy(int[] a) {
        int[] b = new int[a.length];
        System.arraycopy(a, 0, b, 0, a.length);
        return b;
    }
}
