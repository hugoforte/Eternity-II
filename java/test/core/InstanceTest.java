package core;

/** Tests for the puzzle-instance abstraction and its input validation. */
public final class InstanceTest {

    public static void run() {
        T.section("InstanceTest: instance construction and invariants");

        Instance e2 = Instance.eternity2();

        T.eq("board dimension", 16, e2.n);
        T.eq("cell count", 256, e2.cells);
        T.eq("piece count", 256, e2.numPieces);
        T.eq("variant count (pieces * 4)", 1024, e2.numVariants);
        T.eq("bitset words for 1024 variants", 16, e2.words);
        T.eq("colour count (max colour + 1)", 23, e2.numColours);

        T.eq("one fixed placement", 1, e2.fixedCell.length);
        T.eq("fixed cell is row 8 col 7 (index 135)", 8 * 16 + 7, e2.fixedCell[0]);
        T.eq("fixed piece is index 138 (piece 139)", 138, e2.fixedPiece[0]);
        T.eq("fixed rotation is 1", 1, e2.fixedRot[0]);
        T.eq("fixed variant sides are {8,6,16,16}",
             Sides.pack(8, 6, 16, 16), e2.variantSides(138, 1));

        T.check("grey budget is exact (grey sides == 4n)", e2.greyBudgetIsExact(),
                "greySideCount=" + e2.greySideCount() + " 4n=" + (4 * e2.n));

        T.eq("row(135)", 8, e2.row(135));
        T.eq("col(135)", 7, e2.col(135));
        T.eq("row(0)", 0, e2.row(0));
        T.eq("col(255)", 15, e2.col(255));

        // --- variantSides agrees with Sides.rotateCW ------------------------
        boolean variantsConsistent = true;
        for (int id = 0; id < e2.numPieces && variantsConsistent; id++) {
            int base = e2.packedSides[id];
            for (int r = 0; r < 4; r++) {
                if (e2.variantSides(id, r) != Sides.rotateCW(base, r)) {
                    variantsConsistent = false;
                    break;
                }
            }
        }
        T.check("variantSides(id,r) == rotateCW(stored,r) for all pieces", variantsConsistent);

        // --- canonical rotations -------------------------------------------
        int canonical = e2.countCanonicalVariants();
        System.out.println("         (informational) canonical variants for E2: " + canonical
                           + " of " + e2.numVariants);
        T.check("canonical variant count is between 1 and 1024",
                canonical >= 1 && canonical <= 1024);
        // rotation 0 is always canonical
        boolean rot0Canonical = true;
        for (int id = 0; id < e2.numPieces; id++) {
            if (!e2.isCanonicalRotation(id, 0)) { rot0Canonical = false; break; }
        }
        T.check("rotation 0 is canonical for every piece", rot0Canonical);
        // a rotation is non-canonical only if it duplicates an earlier one
        boolean nonCanonicalAreDuplicates = true;
        for (int id = 0; id < e2.numPieces && nonCanonicalAreDuplicates; id++) {
            for (int r = 1; r < 4; r++) {
                if (e2.isCanonicalRotation(id, r)) continue;
                boolean foundEarlier = false;
                for (int q = 0; q < r; q++) {
                    if (e2.variantSides(id, q) == e2.variantSides(id, r)) { foundEarlier = true; break; }
                }
                if (!foundEarlier) { nonCanonicalAreDuplicates = false; break; }
            }
        }
        T.check("non-canonical rotations always duplicate an earlier rotation",
                nonCanonicalAreDuplicates);

        // --- piecesCopy round trip ------------------------------------------
        int[][] copy = e2.piecesCopy();
        boolean copyMatches = true;
        for (int id = 0; id < e2.numPieces; id++) {
            if (Sides.pack(copy[id]) != e2.packedSides[id]) { copyMatches = false; break; }
        }
        T.check("piecesCopy round-trips through pack()", copyMatches);
        copy[0][0] = 99;
        T.check("piecesCopy returns an independent array",
                Sides.left(e2.packedSides[0]) != 99);

        // --- eternity2NoFixedPiece -----------------------------------------
        Instance bare = Instance.eternity2NoFixedPiece();
        T.eq("no-fixed-piece variant has no fixed placements", 0, bare.fixedCell.length);
        T.eq("no-fixed-piece variant has the same pieces", 256, bare.numPieces);

        // --- input validation -----------------------------------------------
        boolean threw;

        threw = false;
        try { new Instance(1, new int[1][4], null, null, null); }
        catch (Throwable ex) { threw = true; }
        T.threw("n < 2 is rejected", threw);

        threw = false;
        try { new Instance(3, new int[8][4], null, null, null); }
        catch (Throwable ex) { threw = true; }
        T.threw("piece count != n*n is rejected", threw);

        threw = false;
        try {
            int[][] bad = new int[9][4];
            bad[3] = new int[3];
            new Instance(3, bad, null, null, null);
        } catch (Throwable ex) { threw = true; }
        T.threw("a piece without 4 sides is rejected", threw);

        threw = false;
        try {
            int[][] bad = new int[9][4];
            bad[0][0] = -1;
            new Instance(3, bad, null, null, null);
        } catch (Throwable ex) { threw = true; }
        T.threw("a negative colour is rejected", threw);

        threw = false;
        try {
            int[][] bad = new int[9][4];
            bad[0][0] = 999;
            new Instance(3, bad, null, null, null);
        } catch (Throwable ex) { threw = true; }
        T.threw("a colour above 255 is rejected", threw);

        threw = false;
        try { new Instance(3, new int[9][4], new int[] { 99 }, new int[] { 0 }, new int[] { 0 }); }
        catch (Throwable ex) { threw = true; }
        T.threw("an out-of-range fixed cell is rejected", threw);

        threw = false;
        try { new Instance(3, new int[9][4], new int[] { 0 }, new int[] { 99 }, new int[] { 0 }); }
        catch (Throwable ex) { threw = true; }
        T.threw("an out-of-range fixed piece is rejected", threw);

        threw = false;
        try { new Instance(3, new int[9][4], new int[] { 0 }, new int[] { 0 }, new int[] { 7 }); }
        catch (Throwable ex) { threw = true; }
        T.threw("an out-of-range fixed rotation is rejected", threw);

        threw = false;
        try { new Instance(3, new int[9][4], new int[] { 0, 1 }, new int[] { 0 }, new int[] { 0 }); }
        catch (Throwable ex) { threw = true; }
        T.threw("mismatched fixed* array lengths are rejected", threw);

        T.endSection();
    }
}
