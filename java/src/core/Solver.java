package core;

import java.util.Arrays;

/**
 * Efficient back-tracking solver for Eternity II.
 *
 * Key design choices:
 *
 *  - Pieces are loaded once via {@link Pieces#SetupPieces(int[][])}.
 *  - All four rotations of every piece are pre-computed and packed into
 *    a single int per rotation: 8 bits per side (l,t,r,b) -> rotPacked[id][r].
 *    Rotations are NEVER mutated during search; the only per-piece state
 *    is "used / not used".
 *  - Cells are visited in row-major order.  At cell (r,c) the left and
 *    top neighbours are already placed, so the required left colour and
 *    top colour are known.  A precomputed table
 *        cand[leftColour][topColour] = int[] of packed (pieceId<<2 | rot)
 *    lists every (piece, rotation) that matches in O(1).
 *  - A secondary "merged" table, candByTop[topColour] = int[], is used
 *    by the forward check when the left colour of the below-neighbour
 *    is not yet known.  (Looking at 23 separate lists would be wasteful.)
 *  - Border constraints fall out of the same lookup (required colour =
 *    GREY for off-board sides), plus a cheap O(1) check for right /
 *    bottom borders.
 *  - Piece 139 is a hard fixed constraint at (row=8, col=7) with
 *    orientation {l=8,t=6,r=16,b=16}.  The constraint is ALSO
 *    pre-propagated to the cell's left and top neighbours so the search
 *    kills impossible branches one ply earlier.
 *  - One-step forward checking: after placing a piece we peek at the
 *    immediate right and down neighbours.  If they would have zero legal
 *    candidates, we backtrack now.
 *  - No allocation in the hot loop; everything is primitive arrays.
 *  - Deepest-reached depth is tracked and the partial board is printed
 *    whenever a new best is found, giving useful progress output on a
 *    puzzle that in practice never terminates.
 *
 * Failure retry behaviour (see answer to Q1 in SOLVER_NOTES.md):
 *  - Failed candidates at the current cell: the search simply advances
 *    to the next entry in the per-(l,t) candidate list, never re-scans.
 *  - Failed candidates at an earlier cell: on backtracking, used[] is
 *    restored automatically, so the piece is back in the pool and is
 *    naturally considered again anywhere it still matches (l,t).
 *    No nogood / transposition-table bookkeeping is needed.
 */
public final class Solver {

    // ------------------------------------------------------------------ constants

    static final int N          = 16;          // board width / height
    static final int CELLS      = N * N;       // 256
    static final int NUM_PIECES = 256;
    static final int GREY       = 0;           // border / "edge" colour
    static final int MAX_COLOUR = 23;          // colours observed: 0..22

    /** Fixed-piece constraint: piece id 139 at (row=8, col=7) with the
     *  orientation {left=8, top=6, right=16, bottom=16}.
     *  Coordinate convention: row index first, column index second. */
    static final int FIXED_PIECE_ID0 = 138;    // 0-based id
    static final int FIXED_ROW       = 8;
    static final int FIXED_COL       = 7;
    static final int FIXED_LEFT      = 8;
    static final int FIXED_TOP       = 6;
    static final int FIXED_RIGHT     = 16;
    static final int FIXED_BOTTOM    = 16;
    static final int FIXED_DEPTH     = FIXED_ROW * N + FIXED_COL;   // 135
    static final int FIXED_LEFT_DEPTH  = FIXED_DEPTH - 1;            // (8,6) right side must == 8
    static final int FIXED_TOP_DEPTH   = FIXED_DEPTH - N;            // (7,7) bottom side must == 6

    // Side index convention within a packed rotation int:
    //   bits  0.. 7 : left
    //   bits  8..15 : top
    //   bits 16..23 : right
    //   bits 24..31 : bottom
    static final int SH_L = 0, SH_T = 8, SH_R = 16, SH_B = 24;
    static final int MASK = 0xFF;

    static int pack(int l, int t, int r, int b) {
        return (l & MASK) | ((t & MASK) << SH_T) | ((r & MASK) << SH_R) | ((b & MASK) << SH_B);
    }
    static int left  (int p) { return  p        & MASK; }
    static int top   (int p) { return (p >>> SH_T) & MASK; }
    static int right (int p) { return (p >>> SH_R) & MASK; }
    static int bottom(int p) { return (p >>> SH_B) & MASK; }

    // ------------------------------------------------------------------ data

    /** rotPacked[id][r] = packed {l,t,r,b} for piece id at rotation r
     *  (r=0 is the piece as loaded; each +1 is a 90° clockwise rotation). */
    final int[][] rotPacked = new int[NUM_PIECES][4];

    /** cand[leftColour][topColour] = packed (pieceId<<2)|rotation values. */
    final int[][][] cand = new int[MAX_COLOUR][MAX_COLOUR][];

    /** candByTop[topColour] = merged list of every (piece, rotation) with
     *  that top colour, across all left colours.  Used by the forward
     *  check when the below-neighbour's left is not yet known. */
    final int[][] candByTop = new int[MAX_COLOUR][];

    /** board[cellIdx] == -1 if empty, else packed (pieceId<<2)|rotation. */
    final int[] board = new int[CELLS];

    /** True iff that piece id has been placed. */
    final boolean[] used = new boolean[NUM_PIECES];

    // progress
    long   nodes      = 0;
    int    bestDepth  = 0;
    long   startNanos = 0;

    /** Abort the search once this many nodes have been expanded.  Added so
     *  the test suite can exercise the solver under a bounded budget; the
     *  default keeps the original unbounded behaviour. */
    long    maxNodes = Long.MAX_VALUE;
    /** Set to false to silence progress output (used by the tests). */
    boolean verbose  = true;
    /** True when the search stopped because maxNodes was reached. */
    boolean aborted  = false;
    /** Deepest board seen, captured whenever bestDepth improves. */
    int[]   bestBoard = null;

    // ------------------------------------------------------------------ build

    public Solver() {
        int[][] rawPieces = new int[NUM_PIECES][4];
        Pieces.SetupPieces(rawPieces);

        // 1) Pre-compute the 4 packed rotations of every piece.
        //    Rotating CW once takes {l,t,r,b} -> {b,l,t,r}.
        for (int id = 0; id < NUM_PIECES; id++) {
            int l = rawPieces[id][0], t = rawPieces[id][1];
            int r = rawPieces[id][2], b = rawPieces[id][3];
            for (int rr = 0; rr < 4; rr++) {
                rotPacked[id][rr] = pack(l, t, r, b);
                int nl = b, nt = l, nr = t, nb = r;
                l = nl; t = nt; r = nr; b = nb;
            }
        }

        // 2) Build cand[l][t] and candByTop[t] with a two-pass count-then-fill,
        //    skipping duplicate orientations of rotationally-symmetric pieces
        //    so the same physical placement isn't enumerated multiple times.
        //    Two passes over 1024 variants costs nothing and keeps the tables
        //    free of boxing / collection overhead.
        int[][] countLT = new int[MAX_COLOUR][MAX_COLOUR];
        int[]   countT  = new int[MAX_COLOUR];
        for (int id = 0; id < NUM_PIECES; id++) {
            for (int rr = 0; rr < 4; rr++) {
                if (!isCanonicalRotation(id, rr)) continue;
                int packed = rotPacked[id][rr];
                countLT[left(packed)][top(packed)]++;
                countT[top(packed)]++;
            }
        }
        for (int l = 0; l < MAX_COLOUR; l++) {
            for (int t = 0; t < MAX_COLOUR; t++) cand[l][t] = new int[countLT[l][t]];
        }
        for (int t = 0; t < MAX_COLOUR; t++) candByTop[t] = new int[countT[t]];

        int[][] fillLT = new int[MAX_COLOUR][MAX_COLOUR];
        int[]   fillT  = new int[MAX_COLOUR];
        for (int id = 0; id < NUM_PIECES; id++) {
            for (int rr = 0; rr < 4; rr++) {
                if (!isCanonicalRotation(id, rr)) continue;
                int packed = rotPacked[id][rr];
                int l = left(packed), t = top(packed);
                cand[l][t][fillLT[l][t]++] = (id << 2) | rr;
                candByTop[t][fillT[t]++]   = (id << 2) | rr;
            }
        }

        Arrays.fill(board, -1);
    }

    /** True if rotation rr of piece id is not a duplicate of an earlier one. */
    boolean isCanonicalRotation(int id, int rr) {
        int packed = rotPacked[id][rr];
        for (int qq = 0; qq < rr; qq++) {
            if (rotPacked[id][qq] == packed) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ search

    public void solve() {
        startNanos = System.nanoTime();

        // Determine which rotation of piece 139 matches the fixed sides.
        int fixedRot = -1;
        int fixedPacked = pack(FIXED_LEFT, FIXED_TOP, FIXED_RIGHT, FIXED_BOTTOM);
        for (int r = 0; r < 4; r++) {
            if (rotPacked[FIXED_PIECE_ID0][r] == fixedPacked) { fixedRot = r; break; }
        }
        if (fixedRot == -1) {
            throw new IllegalStateException(
                "Piece 139 does not have the fixed orientation; check piece data.");
        }
        if (verbose) System.out.println("Fixed piece 139 rotation = " + fixedRot);

        dfs(0, fixedRot, fixedPacked);

        long ms = (System.nanoTime() - startNanos) / 1_000_000L;
        if (verbose) {
            System.out.println();
            System.out.println("Search finished.");
            System.out.println("Nodes      : " + nodes);
            System.out.println("Elapsed ms : " + ms);
            System.out.println("Best depth : " + bestDepth + " / " + CELLS);
            System.out.println("Aborted    : " + aborted);
        }
    }

    /**
     * Recursive DFS.  {@code depth} is the index (0..255) of the next
     * cell to fill in row-major order.
     */
    private boolean dfs(int depth, int fixedRot, int fixedPacked) {
        if (depth == CELLS) {
            long ms = (System.nanoTime() - startNanos) / 1_000_000L;
            if (verbose) {
                System.out.println("SOLVED after " + nodes + " nodes in " + ms + " ms");
                printBoard();
            }
            return true;
        }

        nodes++;
        if (nodes >= maxNodes) { aborted = true; return true; }
        if (depth > bestDepth) {
            bestDepth = depth;
            if (bestBoard == null) bestBoard = new int[CELLS];
            System.arraycopy(board, 0, bestBoard, 0, CELLS);
            if (verbose && ((bestDepth & 0x0F) == 0 || bestDepth > 200)) {
                long ms = (System.nanoTime() - startNanos) / 1_000_000L;
                System.out.println("new best depth=" + bestDepth
                        + "  nodes=" + nodes
                        + "  ms=" + ms
                        + "  nodes/s=" + (ms == 0 ? 0 : nodes * 1000L / ms));
                if (bestDepth > 200) printBoard();
            }
        }

        int row = depth >>> 4;          // depth / 16
        int col = depth & 0x0F;         // depth % 16

        int reqLeft = (col == 0) ? GREY : right(rotPacked[board[depth - 1] >>> 2][board[depth - 1] & 3]);
        int reqTop  = (row == 0) ? GREY : bottom(rotPacked[board[depth - N] >>> 2][board[depth - N] & 3]);

        boolean onRight  = (col == N - 1);
        boolean onBottom = (row == N - 1);

        // Fixed-piece cell: only the one specific placement is legal.
        if (row == FIXED_ROW && col == FIXED_COL) {
            if (reqLeft != FIXED_LEFT || reqTop != FIXED_TOP) return false;
            if (used[FIXED_PIECE_ID0])                        return false;
            board[depth] = (FIXED_PIECE_ID0 << 2) | fixedRot;
            used[FIXED_PIECE_ID0] = true;
            boolean ok = forwardCheck(depth, FIXED_RIGHT, FIXED_BOTTOM, onRight, onBottom)
                      && dfs(depth + 1, fixedRot, fixedPacked);
            if (ok) return true;
            used[FIXED_PIECE_ID0] = false;
            board[depth] = -1;
            return false;
        }

        int[] list = cand[reqLeft][reqTop];
        for (int i = 0, n = list.length; i < n; i++) {
            int packed = list[i];
            int id     = packed >>> 2;
            if (used[id])                      continue;
            if (id == FIXED_PIECE_ID0)         continue;   // reserved
            int r  = packed & 3;
            int rp = rotPacked[id][r];
            int pr = right(rp);
            int pb = bottom(rp);
            if (onRight  && pr != GREY)                         continue;
            if (onBottom && pb != GREY)                         continue;
            if (depth == FIXED_LEFT_DEPTH && pr != FIXED_LEFT)  continue;
            if (depth == FIXED_TOP_DEPTH  && pb != FIXED_TOP)   continue;

            board[depth] = packed;
            used[id]     = true;

            if (forwardCheck(depth, pr, pb, onRight, onBottom)
                    && dfs(depth + 1, fixedRot, fixedPacked)) {
                return true;
            }
            used[id] = false;
        }
        board[depth] = -1;
        return false;
    }

    /**
     * One-step forward check after a placement at {@code depth} whose
     * right colour is {@code pr} and bottom colour is {@code pb}.
     * Returns false if the immediate right or immediate below neighbour
     * would have zero legal candidates.
     */
    private boolean forwardCheck(int depth, int pr, int pb,
                                 boolean onRight, boolean onBottom) {
        // Right neighbour (same row, col+1): top & left fully known.
        if (!onRight) {
            int nd = depth + 1;
            int row = nd >>> 4;
            int col = nd & 0x0F;
            int reqTop = (row == 0) ? GREY
                       : bottom(rotPacked[board[nd - N] >>> 2][board[nd - N] & 3]);
            // If nd itself is the fixed cell, we need piece 139 there.
            if (nd == FIXED_DEPTH) {
                if (pr != FIXED_LEFT || reqTop != FIXED_TOP) return false;
                if (used[FIXED_PIECE_ID0])                   return false;
            } else if (!hasAnyCandidate(pr, reqTop,
                                        col == N - 1,
                                        row == N - 1,
                                        nd == FIXED_LEFT_DEPTH,
                                        nd == FIXED_TOP_DEPTH)) {
                return false;
            }
        }
        // Below neighbour (row+1, same col): top is pb, left unknown
        // unless we are in column 0 (where left is the board border).
        if (!onBottom) {
            int nd = depth + N;
            int row = nd >>> 4;
            int col = nd & 0x0F;
            if (nd == FIXED_DEPTH) {
                if (pb != FIXED_TOP)           return false;
                if (used[FIXED_PIECE_ID0])     return false;
            } else if (col == 0) {
                if (!hasAnyCandidate(GREY, pb,
                                     col == N - 1,
                                     row == N - 1,
                                     nd == FIXED_LEFT_DEPTH,
                                     nd == FIXED_TOP_DEPTH)) return false;
            } else {
                if (!hasAnyCandidateByTop(pb,
                                          col == N - 1,
                                          row == N - 1,
                                          nd == FIXED_LEFT_DEPTH,
                                          nd == FIXED_TOP_DEPTH)) return false;
            }
        }
        return true;
    }

    /** Any unused piece whose (l,t) matches and whose right/bottom
     *  obeys the border and fixed-piece-neighbour constraints? */
    private boolean hasAnyCandidate(int l, int t,
                                    boolean onRight, boolean onBottom,
                                    boolean fixedLeftDepth, boolean fixedTopDepth) {
        int[] list = cand[l][t];
        for (int i = 0, n = list.length; i < n; i++) {
            int packed = list[i];
            int id     = packed >>> 2;
            if (used[id])                 continue;
            if (id == FIXED_PIECE_ID0)    continue;
            int rp = rotPacked[id][packed & 3];
            int pr = right(rp), pb = bottom(rp);
            if (onRight  && pr != GREY)                         continue;
            if (onBottom && pb != GREY)                         continue;
            if (fixedLeftDepth && pr != FIXED_LEFT)             continue;
            if (fixedTopDepth  && pb != FIXED_TOP)              continue;
            return true;
        }
        return false;
    }

    /** Looser variant used when the required left colour is not yet
     *  known.  Backed by the merged candByTop[t] list. */
    private boolean hasAnyCandidateByTop(int t,
                                         boolean onRight, boolean onBottom,
                                         boolean fixedLeftDepth, boolean fixedTopDepth) {
        int[] list = candByTop[t];
        for (int i = 0, n = list.length; i < n; i++) {
            int packed = list[i];
            int id     = packed >>> 2;
            if (used[id])                 continue;
            if (id == FIXED_PIECE_ID0)    continue;
            int rp = rotPacked[id][packed & 3];
            int pr = right(rp), pb = bottom(rp);
            if (onRight  && pr != GREY)                         continue;
            if (onBottom && pb != GREY)                         continue;
            if (fixedLeftDepth && pr != FIXED_LEFT)             continue;
            if (fixedTopDepth  && pb != FIXED_TOP)              continue;
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ output

    private void printBoard() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n   ");
        for (int c = 0; c < N; c++) sb.append(String.format("%4d", c));
        sb.append('\n');
        for (int r = 0; r < N; r++) {
            sb.append(String.format("%2d ", r));
            for (int c = 0; c < N; c++) {
                int packed = board[r * N + c];
                if (packed < 0) { sb.append("   ."); continue; }
                sb.append(String.format("%4d", (packed >>> 2) + 1));   // 1-based id
            }
            sb.append('\n');
        }
        System.out.println(sb);
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) {
        Solver s = new Solver();
        int total = 0;
        for (int l = 0; l < MAX_COLOUR; l++)
            for (int t = 0; t < MAX_COLOUR; t++) total += s.cand[l][t].length;
        System.out.println("total distinct (piece, rotation) entries = " + total
                           + "   (duplicate orientations removed)");
        s.solve();
    }
}
