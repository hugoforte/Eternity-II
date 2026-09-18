package core;

/**
 * Packing / rotation helpers for a piece's four side colours.
 *
 * A piece's sides are stored in a single int, 8 bits per side:
 *   bits  0.. 7 : left
 *   bits  8..15 : top
 *   bits 16..23 : right
 *   bits 24..31 : bottom
 *
 * This keeps the hot loop free of multi-dimensional array dereferences.
 */
public final class Sides {

    public static final int GREY = 0;

    public static final int SH_L = 0;
    public static final int SH_T = 8;
    public static final int SH_R = 16;
    public static final int SH_B = 24;
    public static final int MASK = 0xFF;

    /** Side indices, matching the order used in Pieces.java. */
    public static final int LEFT = 0;
    public static final int TOP = 1;
    public static final int RIGHT = 2;
    public static final int BOTTOM = 3;

    private Sides() { }

    public static int pack(int l, int t, int r, int b) {
        return (l & MASK) | ((t & MASK) << SH_T) | ((r & MASK) << SH_R) | ((b & MASK) << SH_B);
    }

    public static int pack(int[] ltrb) {
        return pack(ltrb[0], ltrb[1], ltrb[2], ltrb[3]);
    }

    public static int left(int p)   { return (p >>> SH_L) & MASK; }
    public static int top(int p)    { return (p >>> SH_T) & MASK; }
    public static int right(int p)  { return (p >>> SH_R) & MASK; }
    public static int bottom(int p) { return (p >>> SH_B) & MASK; }

    /** Colour of the given side (0=left,1=top,2=right,3=bottom). */
    public static int side(int p, int which) {
        return (p >>> (which << 3)) & MASK;
    }

    /**
     * Rotate 90 degrees clockwise.  The side that was on the left moves to
     * the top, top -> right, right -> bottom, bottom -> left, so the new
     * tuple is {old bottom, old left, old top, old right}.
     */
    public static int rotateCW(int p) {
        return pack(bottom(p), left(p), top(p), right(p));
    }

    /** Rotate clockwise {@code times} quarter turns (times may be 0..3). */
    public static int rotateCW(int p, int times) {
        int out = p;
        for (int i = 0; i < (times & 3); i++) out = rotateCW(out);
        return out;
    }

    /** Number of sides equal to GREY. */
    public static int greyCount(int p) {
        int c = 0;
        if (left(p) == GREY) c++;
        if (top(p) == GREY) c++;
        if (right(p) == GREY) c++;
        if (bottom(p) == GREY) c++;
        return c;
    }

    /** True if the two grey sides of a 2-grey piece are adjacent (a corner). */
    public static boolean greySidesAdjacent(int p) {
        boolean l = left(p) == GREY;
        boolean t = top(p) == GREY;
        boolean r = right(p) == GREY;
        boolean b = bottom(p) == GREY;
        // adjacent pairs: (l,t) (t,r) (r,b) (b,l)
        return (l && t) || (t && r) || (r && b) || (b && l);
    }

    public static String toString(int p) {
        return "{l=" + left(p) + ",t=" + top(p) + ",r=" + right(p) + ",b=" + bottom(p) + "}";
    }
}
