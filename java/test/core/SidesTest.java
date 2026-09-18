package core;

/** Tests for the packed side representation and rotation arithmetic. */
public final class SidesTest {

    public static void run() {
        T.section("SidesTest: packing and rotation");

        // --- pack / unpack round trip -------------------------------------
        boolean allRoundTrip = true;
        String firstBad = null;
        for (int l = 0; l < 23 && allRoundTrip; l++) {
            for (int t = 0; t < 23 && allRoundTrip; t++) {
                for (int r = 0; r < 23 && allRoundTrip; r++) {
                    for (int b = 0; b < 23 && allRoundTrip; b++) {
                        int p = Sides.pack(l, t, r, b);
                        if (Sides.left(p) != l || Sides.top(p) != t
                                || Sides.right(p) != r || Sides.bottom(p) != b) {
                            allRoundTrip = false;
                            firstBad = "pack(" + l + "," + t + "," + r + "," + b + ") -> "
                                     + Sides.toString(p);
                        }
                    }
                }
            }
        }
        T.check("pack/unpack round-trips for all colour combinations 0..22", allRoundTrip, firstBad);

        // --- side() agrees with the named accessors ------------------------
        int p = Sides.pack(3, 5, 7, 11);
        T.eq("side(LEFT)", 3, Sides.side(p, Sides.LEFT));
        T.eq("side(TOP)", 5, Sides.side(p, Sides.TOP));
        T.eq("side(RIGHT)", 7, Sides.side(p, Sides.RIGHT));
        T.eq("side(BOTTOM)", 11, Sides.side(p, Sides.BOTTOM));

        // --- rotateCW is {l,t,r,b} -> {b,l,t,r} ---------------------------
        int once = Sides.rotateCW(p);
        T.eq("rotateCW moves bottom to left", 11, Sides.left(once));
        T.eq("rotateCW moves left to top", 3, Sides.top(once));
        T.eq("rotateCW moves top to right", 5, Sides.right(once));
        T.eq("rotateCW moves right to bottom", 7, Sides.bottom(once));

        // --- four rotations return to the start ---------------------------
        boolean cyclesBack = true;
        boolean kConsistent = true;
        for (int l = 0; l < 7; l++) {
            for (int t = 0; t < 7; t++) {
                for (int r = 0; r < 7; r++) {
                    for (int b = 0; b < 7; b++) {
                        int q = Sides.pack(l, t, r, b);
                        int acc = q;
                        for (int k = 0; k < 4; k++) acc = Sides.rotateCW(acc);
                        if (acc != q) cyclesBack = false;
                        // rotateCW(q,k) must equal k applications
                        int step = q;
                        for (int k = 0; k < 4; k++) {
                            if (Sides.rotateCW(q, k) != step) kConsistent = false;
                            step = Sides.rotateCW(step);
                        }
                    }
                }
            }
        }
        T.check("four clockwise rotations are the identity", cyclesBack);
        T.check("rotateCW(p,k) equals k single rotations", kConsistent);
        T.eq("rotateCW(p,0) is identity", p, Sides.rotateCW(p, 0));
        T.eq("rotateCW(p,4) is identity (masked)", p, Sides.rotateCW(p, 4));

        // --- grey counting -------------------------------------------------
        T.eq("greyCount of all-grey", 4, Sides.greyCount(Sides.pack(0, 0, 0, 0)));
        T.eq("greyCount of corner-like", 2, Sides.greyCount(Sides.pack(0, 0, 5, 6)));
        T.eq("greyCount of edge-like", 1, Sides.greyCount(Sides.pack(0, 4, 5, 6)));
        T.eq("greyCount of interior", 0, Sides.greyCount(Sides.pack(1, 4, 5, 6)));

        // --- adjacency of grey sides ---------------------------------------
        T.check("grey left+top counts as adjacent",
                Sides.greySidesAdjacent(Sides.pack(0, 0, 5, 6)));
        T.check("grey top+right counts as adjacent",
                Sides.greySidesAdjacent(Sides.pack(5, 0, 0, 6)));
        T.check("grey right+bottom counts as adjacent",
                Sides.greySidesAdjacent(Sides.pack(5, 6, 0, 0)));
        T.check("grey bottom+left counts as adjacent",
                Sides.greySidesAdjacent(Sides.pack(0, 6, 5, 0)));
        T.check("grey left+right is NOT adjacent",
                !Sides.greySidesAdjacent(Sides.pack(0, 6, 0, 5)));
        T.check("grey top+bottom is NOT adjacent",
                !Sides.greySidesAdjacent(Sides.pack(6, 0, 5, 0)));

        // --- pack(int[]) matches pack(int,int,int,int) ----------------------
        int[] arr = { 9, 8, 7, 6 };
        T.eq("pack(int[]) matches pack(4 ints)", Sides.pack(9, 8, 7, 6), Sides.pack(arr));

        T.endSection();
    }
}
