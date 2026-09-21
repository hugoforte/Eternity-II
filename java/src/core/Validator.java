package core;

/**
 * Independent checker and scorer for complete and partial boards.  Written
 * without reference to any solver's internal state so it can be trusted to
 * judge solver output.
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
        return validatePartial(inst, boardVariant, requireAllPiecesUsed, 0);
    }

    /**
     * Validate a possibly-incomplete board that is allowed to carry a known
     * number of deliberately mismatched interior edges.
     *
     * {@link ScanSolver}'s edge slipping produces boards that break the
     * matching rule on purpose but must still obey every other rule, and the
     * engine promises exactly how many breaks it left.  Passing that promise
     * in turns this into a check of the promise: a board with more mismatches
     * than the caller expected is reported as an error, with both counts.
     * Everything else -- piece reuse, border colours, fixed placements -- is
     * judged exactly as strictly as before.
     *
     * @param allowedMismatches how many mismatched interior edges the caller
     *                          expects; 0 demands a perfectly matched board.
     */
    public static String validatePartial(Instance inst, int[] boardVariant,
                                         boolean requireAllPiecesUsed,
                                         int allowedMismatches) {
        if (boardVariant == null) return "board is null";
        if (boardVariant.length != inst.cells) {
            return "board has " + boardVariant.length + " cells, expected " + inst.cells;
        }
        int n = inst.n;
        int[] useCount = new int[inst.numPieces];
        int mismatches = 0;
        String firstMismatch = null;

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
                        mismatches++;
                        if (firstMismatch == null) {
                            firstMismatch = "cell " + cell + " right " + Sides.right(p)
                                          + " != cell " + (cell + 1) + " left " + Sides.left(q);
                        }
                    }
                }
            }
            // match against the bottom neighbour
            if (r < n - 1) {
                int nb = boardVariant[cell + n];
                if (nb >= 0) {
                    int q = inst.variantSides(nb >>> 2, nb & 3);
                    if (Sides.bottom(p) != Sides.top(q)) {
                        mismatches++;
                        if (firstMismatch == null) {
                            firstMismatch = "cell " + cell + " bottom " + Sides.bottom(p)
                                          + " != cell " + (cell + n) + " top " + Sides.top(q);
                        }
                    }
                }
            }
        }

        if (mismatches > allowedMismatches) {
            return mismatches + " mismatched edges, at most " + allowedMismatches
                 + " allowed; first was " + firstMismatch;
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

    /**
     * How many internal edges of the board are matched.
     *
     * The board has {@link #internalEdgeTotal} adjacent pairs of cells; the
     * sides facing off the board are not scored, which is the measure every
     * published Eternity II result is quoted in (480 for the 16x16 puzzle).
     * A pair only counts when both of its cells hold a piece, so a partial
     * board scores exactly what it has joined up so far.
     *
     * This says nothing about whether the board is legal; ask
     * {@link #validatePartial} for that.
     */
    public static int matchedEdges(Instance inst, int[] boardVariant) {
        if (boardVariant == null) throw new IllegalArgumentException("board is null");
        if (boardVariant.length != inst.cells) {
            throw new IllegalArgumentException("board has " + boardVariant.length
                + " cells, expected " + inst.cells);
        }
        int n = inst.n;
        int matched = 0;
        for (int cell = 0; cell < inst.cells; cell++) {
            int v = boardVariant[cell];
            if (v < 0) continue;
            int p = inst.variantSides(v >>> 2, v & 3);
            int r = cell / n, c = cell - r * n;
            if (c < n - 1) {
                int nb = boardVariant[cell + 1];
                if (nb >= 0) {
                    int q = inst.variantSides(nb >>> 2, nb & 3);
                    if (Sides.right(p) == Sides.left(q)) matched++;
                }
            }
            if (r < n - 1) {
                int nb = boardVariant[cell + n];
                if (nb >= 0) {
                    int q = inst.variantSides(nb >>> 2, nb & 3);
                    if (Sides.bottom(p) == Sides.top(q)) matched++;
                }
            }
        }
        return matched;
    }

    /**
     * The deepest error-free prefix of a placement order: the largest k for
     * which the first k placements carry no mismatched edge between them.
     *
     * Tiles placed stops being a score once edge slipping is on, because a
     * board can be filled to every cell by paying enough breaks.  This is the
     * companion measure that cannot be bought that way -- how far a search got
     * before it had to mismatch anything.  Only mismatched edges end a prefix;
     * a border side that is not grey is a different kind of broken board and
     * {@link #validatePartial} is what judges it.
     *
     * @param orderCells    cells in the order they were placed
     * @param orderVariants variants parallel to {@code orderCells}
     * @param length        how many of those placements to consider
     * @return the number of leading placements that are mutually consistent
     */
    public static int perfectTiles(Instance inst, int[] orderCells,
                                   int[] orderVariants, int length) {
        if (orderCells == null || orderVariants == null) {
            throw new IllegalArgumentException("placement order is null");
        }
        if (length < 0 || length > inst.cells) {
            throw new IllegalArgumentException("length " + length
                + " is outside 0.." + inst.cells);
        }
        if (orderCells.length < length || orderVariants.length < length) {
            throw new IllegalArgumentException("placement order is shorter than "
                + length);
        }
        int n = inst.n;
        int[] board = new int[inst.cells];
        for (int cell = 0; cell < inst.cells; cell++) board[cell] = -1;
        for (int d = 0; d < length; d++) {
            int cell = orderCells[d];
            int v = orderVariants[d];
            int p = inst.variantSides(v >>> 2, v & 3);
            int r = cell / n, c = cell - r * n;
            if (c > 0 && board[cell - 1] >= 0
                    && Sides.right(sidesAt(inst, board, cell - 1)) != Sides.left(p)) {
                return d;
            }
            if (c < n - 1 && board[cell + 1] >= 0
                    && Sides.right(p) != Sides.left(sidesAt(inst, board, cell + 1))) {
                return d;
            }
            if (r > 0 && board[cell - n] >= 0
                    && Sides.bottom(sidesAt(inst, board, cell - n)) != Sides.top(p)) {
                return d;
            }
            if (r < n - 1 && board[cell + n] >= 0
                    && Sides.bottom(p) != Sides.top(sidesAt(inst, board, cell + n))) {
                return d;
            }
            board[cell] = v;
        }
        return length;
    }

    private static int sidesAt(Instance inst, int[] board, int cell) {
        int v = board[cell];
        return inst.variantSides(v >>> 2, v & 3);
    }

    /** Internal edges of an n x n board: 2*n*(n-1), so 480 for Eternity II. */
    public static int internalEdgeTotal(Instance inst) {
        return 2 * inst.n * (inst.n - 1);
    }
}
