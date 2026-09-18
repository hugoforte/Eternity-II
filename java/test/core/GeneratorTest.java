package core;

/**
 * Tests for the test-instance generator.  The generator underpins the
 * cross-validation tests, so if it produced unsolvable or malformed instances
 * the whole suite would be built on sand.
 */
public final class GeneratorTest {

    public static void run() {
        T.section("GeneratorTest: generated instances are well formed and solvable");

        int[] sizes = { 2, 3, 4, 5, 6 };
        for (int i = 0; i < sizes.length; i++) {
            int n = sizes[i];
            Instance inst = Generator.generate(n, 3, 1000L + n, true, false);

            T.eq("generated " + n + "x" + n + " has n*n pieces", n * n, inst.numPieces);
            T.check("generated " + n + "x" + n + " has an exact grey budget",
                    inst.greyBudgetIsExact(),
                    "grey=" + inst.greySideCount() + " expected=" + (4 * n));

            // piece classification must match the geometry of an n x n board
            int corners = 0, edges = 0, interior = 0;
            for (int id = 0; id < inst.numPieces; id++) {
                int g = Sides.greyCount(inst.packedSides[id]);
                if (g == 2) corners++;
                else if (g == 1) edges++;
                else if (g == 0) interior++;
            }
            T.eq("generated " + n + "x" + n + " has 4 corner pieces", 4, corners);
            T.eq("generated " + n + "x" + n + " has 4(n-2) edge pieces",
                 4 * (n - 2), edges);
            T.eq("generated " + n + "x" + n + " has (n-2)^2 interior pieces",
                 (n - 2) * (n - 2), interior);

            boolean cornersAdjacent = true;
            for (int id = 0; id < inst.numPieces; id++) {
                if (Sides.greyCount(inst.packedSides[id]) == 2
                        && !Sides.greySidesAdjacent(inst.packedSides[id])) {
                    cornersAdjacent = false;
                    break;
                }
            }
            T.check("generated " + n + "x" + n + " corner pieces have adjacent grey sides",
                    cornersAdjacent);
        }

        // --- unshuffled generation puts piece i in cell i at rotation 0 -------
        Instance plain = Generator.generate(5, 4, 4242L, false, false);
        int[] layout = new int[plain.cells];
        for (int cell = 0; cell < plain.cells; cell++) layout[cell] = (cell << 2);
        T.isNull("unshuffled generation yields the identity solution",
                 Validator.validateComplete(plain, layout));

        // --- no grey appears on an interior edge --------------------------------
        // (this is what lets MrvSolver prune interior candidates)
        boolean noInteriorGrey = true;
        String detail = null;
        int n = plain.n;
        for (int cell = 0; cell < plain.cells && noInteriorGrey; cell++) {
            int p = plain.packedSides[cell];
            int r = cell / n, c = cell - r * n;
            if (c > 0 && Sides.left(p) == Sides.GREY) {
                noInteriorGrey = false; detail = "cell " + cell + " has grey on an interior left";
            }
            if (r > 0 && Sides.top(p) == Sides.GREY) {
                noInteriorGrey = false; detail = "cell " + cell + " has grey on an interior top";
            }
            if (c < n - 1 && Sides.right(p) == Sides.GREY) {
                noInteriorGrey = false; detail = "cell " + cell + " has grey on an interior right";
            }
            if (r < n - 1 && Sides.bottom(p) == Sides.GREY) {
                noInteriorGrey = false; detail = "cell " + cell + " has grey on an interior bottom";
            }
        }
        T.check("unshuffled generation never puts grey on an interior edge",
                noInteriorGrey, detail);

        // --- determinism: the same seed yields the same instance ----------------
        Instance a = Generator.generate(5, 4, 7777L, true, true);
        Instance b = Generator.generate(5, 4, 7777L, true, true);
        boolean identical = true;
        for (int id = 0; id < a.numPieces; id++) {
            if (a.packedSides[id] != b.packedSides[id]) { identical = false; break; }
        }
        T.check("the same seed reproduces the same piece set", identical);
        T.eq("the same seed reproduces the same fixed cell", a.fixedCell[0], b.fixedCell[0]);
        T.eq("the same seed reproduces the same fixed piece", a.fixedPiece[0], b.fixedPiece[0]);
        T.eq("the same seed reproduces the same fixed rotation", a.fixedRot[0], b.fixedRot[0]);

        // --- different seeds give different instances ---------------------------
        Instance c1 = Generator.generate(5, 4, 1L, true, false);
        Instance c2 = Generator.generate(5, 4, 2L, true, false);
        boolean differs = false;
        for (int id = 0; id < c1.numPieces; id++) {
            if (c1.packedSides[id] != c2.packedSides[id]) { differs = true; break; }
        }
        T.check("different seeds give different piece sets", differs);

        // --- the fixed placement the generator reports is actually legal --------
        Instance fx = Generator.generate(6, 5, 31415L, true, true);
        MrvSolver s = new MrvSolver(fx);   // constructor validates the fixed placement
        T.eq("the generated fixed placement is accepted by the solver", 1, s.placedCount());
        T.eq("the generated fixed piece is on the centre cell",
             fx.fixedPiece[0], s.pieceAt(fx.fixedCell[0]));

        // --- input validation ---------------------------------------------------
        boolean threw = false;
        try { Generator.generate(1, 3, 0L, true, false); }
        catch (Throwable ex) { threw = true; }
        T.threw("n < 2 is rejected by the generator", threw);

        threw = false;
        try { Generator.generate(4, 0, 0L, true, false); }
        catch (Throwable ex) { threw = true; }
        T.threw("zero interior colours is rejected by the generator", threw);

        T.endSection();
    }
}
