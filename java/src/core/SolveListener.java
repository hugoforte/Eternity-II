package core;

/**
 * Hook for observing a running {@link Search}.  Implementations must be cheap:
 * the solver calls {@link #onSample} only every N nodes and the other three
 * only when something notable happens, so none is in the innermost loop, but
 * all are called from the search thread.
 */
public interface SolveListener {

    /** A new deepest board was reached. */
    void onNewBest(Search s);

    /** Periodic heartbeat, roughly every {@code sampleEveryNodes} nodes. */
    void onSample(Search s);

    /** The search restarted (only fires when a restart policy is active). */
    void onRestart(Search s, int restartIndex);

    /**
     * A complete board was found.  {@link Search#boardSnapshot} holds it; the
     * search has not backtracked out of it yet.
     */
    void onSolution(Search s);
}
