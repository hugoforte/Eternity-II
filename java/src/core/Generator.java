package core;

import java.util.Random;

/**
 * Builds random, guaranteed-solvable edge-matching instances for the tests.
 *
 * The construction is the reverse of solving: first invent the colours of
 * every interior edge, then read off the piece that must sit in each cell.
 * Because the instance is generated from a concrete layout, at least one
 * solution is known to exist, which lets the test suite assert that a solver
 * actually finds one.
 *
 * Interior edge colours are always >= 1, and border sides are always GREY, so
 * the generated instances satisfy {@link Instance#greyBudgetIsExact()} exactly
 * like the real Eternity II piece set.
 */
public final class Generator {

    public static final int GREY = Sides.GREY;

    private Generator() { }

    /**
     * @param n             board dimension
     * @param interiorCols  number of distinct non-grey colours (>= 1)
     * @param seed          RNG seed
     * @param shuffle       shuffle + randomly rotate the piece list
     * @param withFixed     fix the centre piece (mimics Eternity II's hint)
     */
    public static Instance generate(int n, int interiorCols, long seed,
                                    boolean shuffle, boolean withFixed) {
        if (n < 2) throw new IllegalArgumentException("n must be >= 2");
        if (interiorCols < 1) throw new IllegalArgumentException("need >= 1 interior colour");
        Random rnd = new Random(seed);

        // hEdge[r][c] = colour on the edge between (r,c) and (r,c+1)
        int[][] hEdge = new int[n][n - 1 < 0 ? 0 : n - 1];
        int[][] vEdge = new int[n - 1 < 0 ? 0 : n - 1][n];
        for (int r = 0; r < n; r++)
            for (int c = 0; c < n - 1; c++)
                hEdge[r][c] = 1 + rnd.nextInt(interiorCols);
        for (int r = 0; r < n - 1; r++)
            for (int c = 0; c < n; c++)
                vEdge[r][c] = 1 + rnd.nextInt(interiorCols);

        // Read the solved layout off the edge colours.
        int cells = n * n;
        int[][] layout = new int[cells][4];
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                int cell = r * n + c;
                layout[cell][Sides.LEFT]   = (c == 0)     ? GREY : hEdge[r][c - 1];
                layout[cell][Sides.TOP]    = (r == 0)     ? GREY : vEdge[r - 1][c];
                layout[cell][Sides.RIGHT]  = (c == n - 1) ? GREY : hEdge[r][c];
                layout[cell][Sides.BOTTOM] = (r == n - 1) ? GREY : vEdge[r][c];
            }
        }

        // Shuffle the piece order and randomly rotate each piece so the
        // solver cannot exploit the generation order.
        int[] order = new int[cells];
        for (int i = 0; i < cells; i++) order[i] = i;
        if (shuffle) {
            for (int i = cells - 1; i > 0; i--) {
                int j = rnd.nextInt(i + 1);
                int t = order[i]; order[i] = order[j]; order[j] = t;
            }
        }

        int[][] pieces = new int[cells][4];
        int[] pieceOfCell = new int[cells];   // solved layout cell -> piece index
        for (int i = 0; i < cells; i++) {
            int cell = order[i];
            pieceOfCell[cell] = i;
            int packed = Sides.pack(layout[cell]);
            int rot = shuffle ? rnd.nextInt(4) : 0;
            int rotated = Sides.rotateCW(packed, rot);
            pieces[i][Sides.LEFT]   = Sides.left(rotated);
            pieces[i][Sides.TOP]    = Sides.top(rotated);
            pieces[i][Sides.RIGHT]  = Sides.right(rotated);
            pieces[i][Sides.BOTTOM] = Sides.bottom(rotated);
        }

        if (!withFixed) {
            return new Instance(n, pieces, null, null, null);
        }

        // Fix the piece that belongs in the centre cell, in the rotation that
        // reproduces the generated layout.
        int centre = (n / 2) * n + (n / 2);
        int id = pieceOfCell[centre];
        int want = Sides.pack(layout[centre]);
        int rot = -1;
        int base = Sides.pack(pieces[id]);
        for (int r = 0; r < 4; r++) {
            if (Sides.rotateCW(base, r) == want) { rot = r; break; }
        }
        if (rot < 0) throw new IllegalStateException("generator could not recover fixed rotation");
        return new Instance(n, pieces, new int[] { centre }, new int[] { id }, new int[] { rot });
    }

    public static Instance generate(int n, int interiorCols, long seed) {
        return generate(n, interiorCols, seed, true, false);
    }
}
