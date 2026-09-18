package core;

/**
 * Independent checker for complete and partial boards.  Written without
 * reference to any solver's internal state so it can be trusted to judge
 * solver output.
 */
public final class Validator {

    public static final int GREY = Sides.GREY;

    private Validator() { }

    /**
     * Validate a complete board.  {@code boardVariant[cell]} must hold
     * (pieceId &lt;&lt; 2) | rot for every cell.
     *
     * @return null if the board is a valid solution, otherwise a description
     *         of the first problem found.
     */
    public static String validateComplete(Instance inst, int[] boardVariant) {
        if (boardVariant == null) return "board is null";
        if (boardVariant.length != inst.cells) {
            return "board has " + boardVariant.length + " cells, expected " + inst.cells;
        }
        for (int cell = 0; cell < inst.cells; cell++) {
            if (boardVariant[cell] < 0) return "cell " + cell + " is empty";
        }
        return validatePartial(inst, boardVariant, true);
    }

    /**
     * Validate a possibly-incomplete board: every placed piece must be used
     * at most once, border sides of placed pieces must be grey, and every
     * pair of adjacent placed pieces must match.
     *
     * @param requireAllPiecesUsed when true, also require that every piece
     *                             appears exactly once.
     * @return null if consistent, else a description of the first problem.
     */
    public static String validatePartial(Instance inst, int[] boardVariant,
                                         boolean requireAllPiecesUsed) {
        if (boardVariant == null) return "board is null";
        if (boardVariant.length != inst.cells) {
            return "board has " + boardVariant.length + " cells, expected " + inst.cells;
        }
        int n = inst.n;
        int[] useCount = new int[inst.numPieces];

        for (int cell = 0; cell < inst.cells; cell++) {
            int v = boardVariant[cell];
            if (v < 0) continue;
            int id = v >>> 2;
            int rot = v & 3;
            if (id < 0 || id >= inst.numPieces) return "cell " + cell + " has bad piece id " + id;
            useCount[id]++;
            if (useCount[id] > 1) {
                return "piece " + (id + 1) + " used " + useCount[id] + " times (cell " + cell + ")";
            }
            if (!inst.isCanonicalRotation(id, rot)) {
                return "cell " + cell + " uses non-canonical rotation " + rot
                     + " of piece " + (id + 1);
            }
            int p = inst.variantSides(id, rot);
            int r = cell / n, c = cell - r * n;

            if (c == 0 && Sides.left(p) != GREY) {
                return "cell " + cell + " (r" + r + ",c" + c + ") left side "
                     + Sides.left(p) + " must be grey";
            }
            if (r == 0 && Sides.top(p) != GREY) {
                return "cell " + cell + " (r" + r + ",c" + c + ") top side "
                     + Sides.top(p) + " must be grey";
            }
            if (c == n - 1 && Sides.right(p) != GREY) {
                return "cell " + cell + " (r" + r + ",c" + c + ") right side "
                     + Sides.right(p) + " must be grey";
            }
            if (r == n - 1 && Sides.bottom(p) != GREY) {
                return "cell " + cell + " (r" + r + ",c" + c + ") bottom side "
                     + Sides.bottom(p) + " must be grey";
            }

            // match against the right neighbour
            if (c < n - 1) {
                int nb = boardVariant[cell + 1];
                if (nb >= 0) {
                    int q = inst.variantSides(nb >>> 2, nb & 3);
                    if (Sides.right(p) != Sides.left(q)) {
                        return "cell " + cell + " right " + Sides.right(p)
                             + " != cell " + (cell + 1) + " left " + Sides.left(q);
                    }
                }
            }
            // match against the bottom neighbour
            if (r < n - 1) {
                int nb = boardVariant[cell + n];
                if (nb >= 0) {
                    int q = inst.variantSides(nb >>> 2, nb & 3);
                    if (Sides.bottom(p) != Sides.top(q)) {
                        return "cell " + cell + " bottom " + Sides.bottom(p)
                             + " != cell " + (cell + n) + " top " + Sides.top(q);
                    }
                }
            }
        }

        if (requireAllPiecesUsed) {
            for (int id = 0; id < inst.numPieces; id++) {
                if (useCount[id] != 1) {
                    return "piece " + (id + 1) + " used " + useCount[id] + " times, expected 1";
                }
            }
        }

        // honour fixed placements
        for (int i = 0; i < inst.fixedCell.length; i++) {
            int cell = inst.fixedCell[i];
            int v = boardVariant[cell];
            if (v < 0) continue;
            if ((v >>> 2) != inst.fixedPiece[i]) {
                return "fixed cell " + cell + " holds piece " + ((v >>> 2) + 1)
                     + ", expected " + (inst.fixedPiece[i] + 1);
            }
            int want = inst.variantSides(inst.fixedPiece[i], inst.fixedRot[i]);
            int got = inst.variantSides(v >>> 2, v & 3);
            if (want != got) {
                return "fixed cell " + cell + " orientation " + Sides.toString(got)
                     + ", expected " + Sides.toString(want);
            }
        }
        return null;
    }
}
