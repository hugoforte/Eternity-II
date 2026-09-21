package core;

/**
 * A {@link SolveListener} that keeps every complete board a search reports.
 *
 * Comparing two engines by solution *count* would miss two engines that find
 * the same number of different boards, so the cross-validation compares the
 * boards themselves and needs somewhere to put them.
 */
public final class SolutionCollector implements SolveListener {

    private int[][] boards = new int[16][];
    private int count;

    public void onNewBest(Search s) { }
    public void onSample(Search s) { }
    public void onRestart(Search s, int restartIndex) { }

    public void onSolution(Search s) {
        if (count == boards.length) {
            int[][] bigger = new int[count * 2][];
            System.arraycopy(boards, 0, bigger, 0, count);
            boards = bigger;
        }
        boards[count++] = s.boardSnapshot();
    }

    public int count() { return count; }

    public int[] board(int i) {
        if (i < 0 || i >= count) {
            throw new IndexOutOfBoundsException("solution " + i + " of " + count);
        }
        return boards[i];
    }

    /**
     * The collected boards as sorted text, one line per board, so two
     * collectors can be compared for equality without caring about the order
     * the searches happened to find them in.
     */
    public String signature() {
        String[] lines = new String[count];
        for (int i = 0; i < count; i++) {
            StringBuilder sb = new StringBuilder(boards[i].length * 4);
            for (int c = 0; c < boards[i].length; c++) {
                if (c > 0) sb.append(',');
                sb.append(boards[i][c]);
            }
            lines[i] = sb.toString();
        }
        java.util.Arrays.sort(lines);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) out.append(lines[i]).append('\n');
        return out.toString();
    }
}
