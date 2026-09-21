package core;

/**
 * What a solver looks like from outside: start it, watch it, ask what it
 * found.
 *
 * Two engines implement this -- {@link MrvSolver}, which picks its own cell
 * order, and {@link ScanSolver}, which follows a fixed one -- so the head-less
 * {@code app.Engine} and the lab behind it can run either against the same
 * puzzle without knowing which is which.  The accessors mirror the public
 * fields the solvers already keep, so implementing it costs an engine nothing
 * at run time.
 *
 * Everything here may be read from a {@link SolveListener} callback, which
 * runs on the search thread mid-descent, so "best" means "best so far".
 */
public interface Search {

    // --------------------------------------------------------- configuration

    /** Attach an observer.  Must be set before {@link #solve}. */
    void setListener(SolveListener listener);

    /** Roughly how many nodes between {@link SolveListener#onSample} calls. */
    void setSampleEveryNodes(long nodes);

    /** Stop at the first solution (the default) or enumerate all of them. */
    void setStopAtFirstSolution(boolean stop);

    // ---------------------------------------------------------------- running

    /** Run the search and return the number of solutions found. */
    long solve();

    /**
     * Ask a running search to wind down at its next node.  Called from another
     * thread; the search still unwinds cleanly and its results stay readable.
     */
    void requestStop();

    // -------------------------------------------------------------- progress

    long nodes();
    /** Pieces on the board right now. */
    int placedCount();
    /** Pieces on the deepest board seen so far. */
    int bestPlaced();
    /** Matched internal edges of {@link #bestBoard}, out of 480 on Eternity II. */
    int bestMatchedEdges();
    /**
     * Deliberately mismatched edges of {@link #bestBoard}.  Only
     * {@link ScanSolver} with edge slipping ever returns more than 0, and a
     * board with any is never reported as a solution.
     */
    int bestBreaks();
    int restarts();
    /** True if the search stopped because it ran out of node budget. */
    boolean aborted();

    // ---------------------------------------------------------------- results

    /** The board as it stands, {@code variant} per cell or -1 for empty. */
    int[] boardSnapshot();
    /** The deepest board seen, or null if the search has not started. */
    int[] bestBoard();
    /** The last complete solution, or null if there was none. */
    int[] solutionBoard();

    /** Cells of {@link #bestBoard}, in the order they were placed. */
    int[] bestOrderCells();
    /** Variants of {@link #bestBoard}, parallel to {@link #bestOrderCells}. */
    int[] bestOrderVariants();
    /** How much of {@link #bestOrderCells} is filled in. */
    int bestOrderLength();
}
