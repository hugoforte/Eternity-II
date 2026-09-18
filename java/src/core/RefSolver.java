package core;

/**
 * Deliberately naive reference solver, used only by the test suite.
 *
 * It fills cells in row-major order and, at each cell, tries every piece in
 * every canonical rotation, checking the puzzle rules directly against the
 * board.  There is no precomputation, no bitset, no forward checking and no
 * heuristic -- the point is that it is simple enough to be obviously correct,
 * so the fast {@link MrvSolver} can be cross-validated against it.
 *
 * Only the two primitive rules are enforced:
 *   - a side facing off the board must be GREY
 *   - two sides shared by adjacent pieces must be equal
 *
 * Note that it does NOT assume "no grey in the interior".  On instances where
 * {@link Instance#greyBudgetIsExact()} holds, that assumption is implied by
 * the rules, so the two solvers must agree exactly; the test suite asserts
 * both the budget property and the agreement.
 */
public final class RefSolver {

    public static final int GREY = Sides.GREY;

    public final Instance inst;
    private final int n, cells, numPieces;
    private final int[] boardVariant;
    private final boolean[] usedPiece;
    private final boolean[] frozen;

    public boolean stopAtFirstSolution = true;
    public long maxNodes = Long.MAX_VALUE;

    public long nodes;
    public long solutions;
    public boolean aborted;
    public int[] solutionBoard;

    public RefSolver(Instance inst) {
        this.inst = inst;
        this.n = inst.n;
        this.cells = inst.cells;
        this.numPieces = inst.numPieces;
        this.boardVariant = new int[cells];
        this.usedPiece = new boolean[numPieces];
        this.frozen = new boolean[cells];
        reset();
    }

    public void reset() {
        for (int c = 0; c < cells; c++) { boardVariant[c] = -1; frozen[c] = false; }
        for (int i = 0; i < numPieces; i++) usedPiece[i] = false;
        nodes = 0;
        solutions = 0;
        aborted = false;
        solutionBoard = null;
        for (int i = 0; i < inst.fixedCell.length; i++) {
            int cell = inst.fixedCell[i];
            int id = inst.fixedPiece[i];
            int rot = inst.fixedRot[i];
            // fold onto the canonical rotation with the same sides
            int want = inst.variantSides(id, rot);
            int v = -1;
            for (int r = 0; r < 4; r++) {
                if (inst.isCanonicalRotation(id, r) && inst.variantSides(id, r) == want) {
                    v = (id << 2) | r;
                    break;
                }
            }
            if (v < 0) throw new IllegalStateException("bad fixed placement");
            boardVariant[cell] = v;
            usedPiece[id] = true;
            frozen[cell] = true;
        }
    }

    public long solve() {
        dfs(0);
        return solutions;
    }

    private boolean dfs(int cell) {
        if (cell == cells) {
            solutions++;
            if (solutionBoard == null) solutionBoard = new int[cells];
            System.arraycopy(boardVariant, 0, solutionBoard, 0, cells);
            return stopAtFirstSolution;
        }
        if (boardVariant[cell] >= 0) {
            // pre-fixed cell: verify it is consistent with what is already down
            if (!fits(cell, boardVariant[cell])) return false;
            return dfs(cell + 1);
        }
        nodes++;
        if (nodes >= maxNodes) { aborted = true; return true; }

        for (int id = 0; id < numPieces; id++) {
            if (usedPiece[id]) continue;
            for (int r = 0; r < 4; r++) {
                if (!inst.isCanonicalRotation(id, r)) continue;
                int v = (id << 2) | r;
                if (!fits(cell, v)) continue;
                boardVariant[cell] = v;
                usedPiece[id] = true;
                boolean stop = dfs(cell + 1);
                boardVariant[cell] = -1;
                usedPiece[id] = false;
                if (stop) return true;
            }
        }
        return false;
    }

    /** Check the two primitive rules for putting variant v at cell. */
    private boolean fits(int cell, int v) {
        int p = inst.variantSides(v >>> 2, v & 3);
        int r = cell / n, c = cell - r * n;

        // left
        if (c == 0) { if (Sides.left(p) != GREY) return false; }
        else {
            int nb = boardVariant[cell - 1];
            if (nb >= 0) {
                if (Sides.left(p) != Sides.right(inst.variantSides(nb >>> 2, nb & 3))) return false;
            }
        }
        // top
        if (r == 0) { if (Sides.top(p) != GREY) return false; }
        else {
            int nb = boardVariant[cell - n];
            if (nb >= 0) {
                if (Sides.top(p) != Sides.bottom(inst.variantSides(nb >>> 2, nb & 3))) return false;
            }
        }
        // right
        if (c == n - 1) { if (Sides.right(p) != GREY) return false; }
        else {
            int nb = boardVariant[cell + 1];
            if (nb >= 0) {
                if (Sides.right(p) != Sides.left(inst.variantSides(nb >>> 2, nb & 3))) return false;
            }
        }
        // bottom
        if (r == n - 1) { if (Sides.bottom(p) != GREY) return false; }
        else {
            int nb = boardVariant[cell + n];
            if (nb >= 0) {
                if (Sides.bottom(p) != Sides.top(inst.variantSides(nb >>> 2, nb & 3))) return false;
            }
        }
        return true;
    }

    public int[] boardSnapshot() {
        int[] out = new int[cells];
        System.arraycopy(boardVariant, 0, out, 0, cells);
        return out;
    }
}
