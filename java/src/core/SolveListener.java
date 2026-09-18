package core;

/**
 * Hook for observing a running search.  Implementations must be cheap: the
 * solver calls {@link #onSample} only every N nodes and {@link #onNewBest} only
 * when the record improves, so neither is in the innermost loop, but both are
 * still called from the search thread.
 */
public interface SolveListener {

    /** A new deepest board was reached. */
    void onNewBest(MrvSolver s);

    /** Periodic heartbeat, roughly every {@code sampleEveryNodes} nodes. */
    void onSample(MrvSolver s);

    /** The search restarted (only fires when a restart policy is active). */
    void onRestart(MrvSolver s, int restartIndex);
}
