package core;

/**
 * Tests for the validator.  The validator is what judges every solver's
 * output, so it has to reject broken boards as reliably as it accepts good
 * ones -- a validator that always returns null would make the rest of the
 * suite meaningless.
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

        T.endSection();
    }

    private static int[] copy(int[] a) {
        int[] b = new int[a.length];
        System.arraycopy(a, 0, b, 0, a.length);
        return b;
    }
}
