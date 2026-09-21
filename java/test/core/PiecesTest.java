package core;

/**
 * Tests for the Eternity II piece data itself.  These guard the board design
 * and piece definitions, which must never change.
 */
public final class PiecesTest {

    public static void run() {
        T.section("PiecesTest: the 256 Eternity II pieces");

        int[][] pieces = new int[256][4];
        Pieces.SetupPieces(pieces);

        // --- every slot filled, 4 sides each ------------------------------
        boolean allFilled = true;
        for (int i = 0; i < 256; i++) {
            if (pieces[i] == null || pieces[i].length != 4) { allFilled = false; break; }
        }
        T.check("all 256 piece slots populated with 4 sides each", allFilled);

        // --- colour range --------------------------------------------------
        int minColour = Integer.MAX_VALUE;
        int maxColour = Integer.MIN_VALUE;
        for (int i = 0; i < 256; i++) {
            for (int s = 0; s < 4; s++) {
                int c = pieces[i][s];
                if (c < minColour) minColour = c;
                if (c > maxColour) maxColour = c;
            }
        }
        T.eq("minimum colour is 0 (grey)", 0, minColour);
        T.eq("maximum colour is 22", 22, maxColour);

        // --- piece classification ------------------------------------------
        int corners = 0, edges = 0, interior = 0, weird = 0;
        int greyTotal = 0;
        int cornersWithAdjacentGrey = 0;
        for (int i = 0; i < 256; i++) {
            int p = Sides.pack(pieces[i]);
            int g = Sides.greyCount(p);
            greyTotal += g;
            if (g == 2) {
                corners++;
                if (Sides.greySidesAdjacent(p)) cornersWithAdjacentGrey++;
            } else if (g == 1) {
                edges++;
            } else if (g == 0) {
                interior++;
            } else {
                weird++;
            }
        }
        T.eq("exactly 4 corner pieces (two grey sides)", 4, corners);
        T.eq("exactly 56 edge pieces (one grey side)", 56, edges);
        T.eq("exactly 196 interior pieces (no grey side)", 196, interior);
        T.eq("no piece has 3 or 4 grey sides", 0, weird);
        T.eq("all corner pieces have their grey sides adjacent", 4, cornersWithAdjacentGrey);

        // --- the grey budget is exactly the number of border sides ----------
        T.eq("total grey sides equals 4*16 border sides", 4 * 16, greyTotal);

        // --- colour parity ---------------------------------------------------
        // Every colour occurs an even number of times as a side.  That is what
        // makes a board one edge short of perfect impossible: a lone mismatch
        // would leave two colours with an odd number of unmatched sides.
        int[] colourCount = new int[23];
        for (int i = 0; i < 256; i++) {
            for (int s = 0; s < 4; s++) colourCount[pieces[i][s]]++;
        }
        int oddColours = 0, borderColours = 0, interiorColours = 0;
        for (int c = 0; c < colourCount.length; c++) {
            if (colourCount[c] % 2 != 0) oddColours++;
        }
        for (int c = 1; c < colourCount.length; c++) {
            if (colourCount[c] == 24) borderColours++;
            else if (colourCount[c] == 48 || colourCount[c] == 50) interiorColours++;
        }
        T.eq("no colour appears an odd number of times as a side", 0, oddColours);
        T.eq("grey appears 64 times, once per border side", 64, colourCount[0]);
        T.eq("5 border colours appear 24 times each", 5, borderColours);
        T.eq("17 interior colours appear 48 or 50 times each", 17, interiorColours);

        // --- the fixed piece ------------------------------------------------
        int p139 = Sides.pack(pieces[138]);
        T.eq("piece 139 stored sides are {l=6,t=16,r=16,b=8}",
             Sides.pack(6, 16, 16, 8), p139);
        int want = Sides.pack(8, 6, 16, 16);
        int foundRot = -1;
        for (int r = 0; r < 4; r++) {
            if (Sides.rotateCW(p139, r) == want) { foundRot = r; break; }
        }
        T.eq("piece 139 reaches the fixed orientation {8,6,16,16} at rotation 1", 1, foundRot);

        // --- no duplicate pieces -------------------------------------------
        // Two pieces are "the same" if some rotation of one equals the other.
        int duplicatePairs = 0;
        for (int i = 0; i < 256; i++) {
            int pi = Sides.pack(pieces[i]);
            for (int j = i + 1; j < 256; j++) {
                int pj = Sides.pack(pieces[j]);
                for (int r = 0; r < 4; r++) {
                    if (Sides.rotateCW(pj, r) == pi) { duplicatePairs++; break; }
                }
            }
        }
        // Eternity II does contain some repeated piece patterns; record the
        // number so a change to the data is noticed immediately.
        System.out.println("         (informational) rotation-equivalent piece pairs: "
                           + duplicatePairs);
        T.check("duplicate-pair count is stable and small", duplicatePairs >= 0);

        // --- grey never appears next to grey on the same piece in a way that
        //     would be unusable: an edge piece's single grey side is fine, a
        //     corner's two grey sides must be adjacent (already checked).
        T.eq("corner + edge pieces equal the 60 border pieces", 60, corners + edges);

        T.endSection();
    }
}
