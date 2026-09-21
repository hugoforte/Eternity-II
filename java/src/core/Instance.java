package core;

/**
 * An edge-matching puzzle instance: an n x n board and n*n pieces, plus any
 * number of pre-fixed placements (Eternity II fixes piece 139).
 *
 * Making the solver work on an arbitrary Instance is what allows the test
 * suite to cross-validate it against a naive reference solver on small,
 * provably-solvable boards.  The real Eternity II puzzle is just
 * {@link #eternity2()}.
 *
 * Board cells are numbered row-major: cell = row * n + col.
 *
 * Rules enforced by every solver in this package:
 *   1. Every piece is used exactly once, in one of its four rotations.
 *   2. Sides shared by two adjacent pieces must have equal colour.
 *   3. Sides facing off the board must be GREY (0).
 *   4. Consequently (and this is checked by {@link #greyBudgetIsExact()})
 *      no GREY side may appear in the interior, because the number of grey
 *      sides in the piece set exactly equals the number of border sides.
 */
public final class Instance {

    public static final int GREY = Sides.GREY;

    public final int n;
    public final int cells;
    public final int numPieces;
    public final int numColours;      // max colour + 1
    public final int numVariants;     // numPieces * 4
    public final int words;           // 64-bit words needed for a variant bitset

    /** packedSides[id] = Sides.pack(l,t,r,b) in the piece's stored orientation. */
    public final int[] packedSides;

    /** Fixed placements (parallel arrays, may be length 0). */
    public final int[] fixedCell;
    public final int[] fixedPiece;
    public final int[] fixedRot;

    public Instance(int n, int[][] pieces, int[] fixedCell, int[] fixedPiece, int[] fixedRot) {
        if (n < 2) throw new IllegalArgumentException("n must be >= 2, got " + n);
        if (pieces == null) throw new IllegalArgumentException("pieces is null");
        this.n = n;
        this.cells = n * n;
        this.numPieces = pieces.length;
        if (this.numPieces != this.cells) {
            throw new IllegalArgumentException(
                "piece count " + this.numPieces + " != cell count " + this.cells);
        }
        this.packedSides = new int[numPieces];
        int maxColour = 0;
        for (int i = 0; i < numPieces; i++) {
            if (pieces[i] == null || pieces[i].length != 4) {
                throw new IllegalArgumentException("piece " + i + " must have 4 sides");
            }
            for (int s = 0; s < 4; s++) {
                int c = pieces[i][s];
                if (c < 0 || c > 255) {
                    throw new IllegalArgumentException(
                        "piece " + i + " side " + s + " colour out of range: " + c);
                }
                if (c > maxColour) maxColour = c;
            }
            this.packedSides[i] = Sides.pack(pieces[i]);
        }
        this.numColours = maxColour + 1;
        this.numVariants = numPieces * 4;
        this.words = (numVariants + 63) / 64;

        this.fixedCell  = (fixedCell  == null) ? new int[0] : copy(fixedCell);
        this.fixedPiece = (fixedPiece == null) ? new int[0] : copy(fixedPiece);
        this.fixedRot   = (fixedRot   == null) ? new int[0] : copy(fixedRot);
        if (this.fixedCell.length != this.fixedPiece.length
                || this.fixedCell.length != this.fixedRot.length) {
            throw new IllegalArgumentException("fixed* arrays must have equal length");
        }
        for (int i = 0; i < this.fixedCell.length; i++) {
            if (this.fixedCell[i] < 0 || this.fixedCell[i] >= cells) {
                throw new IllegalArgumentException("fixed cell out of range: " + this.fixedCell[i]);
            }
            if (this.fixedPiece[i] < 0 || this.fixedPiece[i] >= numPieces) {
                throw new IllegalArgumentException("fixed piece out of range: " + this.fixedPiece[i]);
            }
            if (this.fixedRot[i] < 0 || this.fixedRot[i] > 3) {
                throw new IllegalArgumentException("fixed rot out of range: " + this.fixedRot[i]);
            }
        }
    }

    private static int[] copy(int[] a) {
        int[] b = new int[a.length];
        System.arraycopy(a, 0, b, 0, a.length);
        return b;
    }

    public int row(int cell) { return cell / n; }
    public int col(int cell) { return cell % n; }

    /** Fresh copy of the piece table as [id][l,t,r,b]; handy for building variants. */
    public int[][] piecesCopy() {
        int[][] out = new int[numPieces][4];
        for (int i = 0; i < numPieces; i++) {
            out[i][0] = Sides.left(packedSides[i]);
            out[i][1] = Sides.top(packedSides[i]);
            out[i][2] = Sides.right(packedSides[i]);
            out[i][3] = Sides.bottom(packedSides[i]);
        }
        return out;
    }

    /** Packed sides of piece {@code id} after {@code rot} clockwise quarter turns. */
    public int variantSides(int id, int rot) {
        return Sides.rotateCW(packedSides[id], rot);
    }

    /**
     * True if rotation {@code rot} of piece {@code id} produces a side tuple
     * that no smaller rotation already produced.  Rotationally symmetric
     * pieces therefore contribute fewer than four variants, which keeps the
     * search from enumerating the same physical board twice (important for
     * exact solution counting).
     */
    public boolean isCanonicalRotation(int id, int rot) {
        int p = variantSides(id, rot);
        for (int q = 0; q < rot; q++) {
            if (variantSides(id, q) == p) return false;
        }
        return true;
    }

    /** Total number of canonical (piece, rotation) variants. */
    public int countCanonicalVariants() {
        int total = 0;
        for (int id = 0; id < numPieces; id++) {
            for (int r = 0; r < 4; r++) if (isCanonicalRotation(id, r)) total++;
        }
        return total;
    }

    /** Number of GREY sides across the whole piece set. */
    public int greySideCount() {
        int total = 0;
        for (int id = 0; id < numPieces; id++) total += Sides.greyCount(packedSides[id]);
        return total;
    }

    /**
     * The board has exactly 4*n sides facing off-board.  If the piece set has
     * exactly that many grey sides, every grey side must be used on the
     * border, which means no grey colour can appear on an interior edge.
     * The MRV solver relies on this to prune interior candidates.
     */
    public boolean greyBudgetIsExact() {
        return greySideCount() == 4 * n;
    }

    // ------------------------------------------------------------------ factory

    /**
     * The real Eternity II instance: 16x16, the 256 pieces from
     * {@link Pieces}, with piece 139 (0-based index 138) fixed at
     * (row 8, col 7) in the orientation {l=8,t=6,r=16,b=16}.
     */
    public static Instance eternity2() {
        int[][] pieces = new int[256][4];
        Pieces.SetupPieces(pieces);

        int fixedId = 138;
        int wantPacked = Sides.pack(8, 6, 16, 16);
        int rot = -1;
        int base = Sides.pack(pieces[fixedId]);
        for (int r = 0; r < 4; r++) {
            if (Sides.rotateCW(base, r) == wantPacked) { rot = r; break; }
        }
        if (rot < 0) {
            throw new IllegalStateException(
                "piece 139 cannot be oriented as " + Sides.toString(wantPacked)
                + "; stored sides are " + Sides.toString(base));
        }
        int fixedCellIdx = 8 * 16 + 7;   // row 8, col 7
        return new Instance(16, pieces,
                            new int[] { fixedCellIdx },
                            new int[] { fixedId },
                            new int[] { rot });
    }

    /**
     * The four optional clue-puzzle placements, on top of the mandatory piece.
     *
     * Tomy sold four smaller companion puzzles, each of which revealed one
     * further placement.  Respecting all five is the "strict canonical" track;
     * respecting only piece 139 is "canonical", which is what {@link
     * #eternity2()} builds and what the published records are set on.
     *
     * Published as piece 208 at C3 90 degrees, 255 at C14 90, 181 at N3 90 and
     * 249 at N14 180, with rows lettered A-P downwards and columns 1-16 across.
     * Those angles are quoted against whichever piece table the publisher used,
     * and a rotation index only means something relative to a stored
     * orientation, so {@code quarterTurnOffset} exists to reconcile ours with
     * theirs.  {@link #eternity2StrictCanonical()} passes the offset that was
     * calibrated by measurement; see Bench's {@code clues} mode.
     */
    public static Instance eternity2WithClues(int quarterTurnOffset) {
        int[][] pieces = new int[256][4];
        Pieces.SetupPieces(pieces);

        int[] clueCell  = { 8 * 16 + 7, 2 * 16 + 2, 2 * 16 + 13, 13 * 16 + 2, 13 * 16 + 13 };
        int[] cluePiece = { 138, 207, 254, 180, 248 };
        int[] clueRot   = { -1, 1, 1, 1, 2 };          // -1: derived, as in eternity2()

        int base = Sides.pack(pieces[138]);
        for (int r = 0; r < 4; r++) {
            if (Sides.rotateCW(base, r) == Sides.pack(8, 6, 16, 16)) { clueRot[0] = r; break; }
        }
        if (clueRot[0] < 0) {
            throw new IllegalStateException("piece 139 cannot be oriented as the hint requires");
        }
        for (int i = 1; i < clueRot.length; i++) {
            clueRot[i] = (clueRot[i] + quarterTurnOffset) & 3;
        }
        return new Instance(16, pieces, clueCell, cluePiece, clueRot);
    }

    /** All five clues, with the calibrated rotation offset. */
    public static Instance eternity2StrictCanonical() {
        return eternity2WithClues(CLUE_ROT_OFFSET);
    }

    /**
     * Reconciles the published clue angles with this piece table's stored
     * orientations.  Measured, not assumed: a wrong offset makes the four
     * interior clues mutually unsatisfiable and the search stalls at a tiny
     * depth, which is what Bench's {@code clues} mode reports.
     */
    public static final int CLUE_ROT_OFFSET = 0;

    /** Same pieces and board as {@link #eternity2()} but with no fixed piece. */
    public static Instance eternity2NoFixedPiece() {
        int[][] pieces = new int[256][4];
        Pieces.SetupPieces(pieces);
        return new Instance(16, pieces, null, null, null);
    }
}
