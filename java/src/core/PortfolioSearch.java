package core;

/**
 * Runs several independently-seeded {@link ScanSolver} workers at once, one
 * per thread, and reports whichever finds the best board -- the cheap
 * multiplier docs/SOLVER.md calls out: the scan engine is fast enough
 * (~150 KB of state, no shared mutable data) that idle cores are otherwise
 * wasted on every attempt.
 *
 * The config's node budget is one budget for the whole attempt, split as
 * evenly as it divides across the workers, so an attempt does the same amount
 * of search on any core count and the lab can compare it with a single
 * descent at equal nodes. An unbounded budget stays unbounded for every
 * worker. Only {@link ScanSolver} is supported: {@link MrvSolver} keeps its
 * own restart machinery and is not validated for concurrent instances.
 *
 * ----------------------------------------------------------------- races
 *
 * Workers never share mutable state -- each builds its own candidate index --
 * so the only coordination needed is deciding, as boards come in, which
 * worker is currently "the" result. {@link #championIndex} is volatile, and
 * every read of a *different* worker's progress fields happens inside
 * {@code synchronized(championLock)}. That is safe even though those fields
 * themselves are plain: each worker calls {@link ScanSolver#recordBest}
 * (unsynchronized) strictly before its own {@code onNewBest} callback enters
 * the monitor, and the JMM guarantees a monitor release happens-before every
 * later acquire of the same monitor -- so by the time any worker reaches its
 * own true final best and compares itself to the incumbent, it is comparing
 * against an up-to-date value, and if it is genuinely the best it always
 * promotes itself. The accessors used for the final report are only ever
 * read after {@link #solve} has joined every thread, which is unconditionally
 * safe.
 *
 * The live champion is for the live display, and is only as good as the
 * interleaving that produced it.  The result is settled again once every
 * worker has finished, on the same order -- deeper, then more matched edges,
 * then the lower index -- so the same seed and worker count always report the
 * same board, and a solution, which ranks above everything, is reported by
 * the lowest-indexed worker that found one.  A board shown live can therefore
 * be replaced at the end by a different board of the same score.
 */
public final class PortfolioSearch implements Search {

    private final ScanSolver[] workers;
    private final Object championLock = new Object();
    private volatile int championIndex = 0;
    private volatile SolveListener outerListener;

    public PortfolioSearch(Instance inst, SolverConfig cfg, int workerCount) {
        int n = Math.max(1, workerCount);
        this.workers = new ScanSolver[n];
        for (int i = 0; i < n; i++) {
            SolverConfig wc = cfg.copy();
            wc.randomSeed = mixSeed(cfg.randomSeed, i);
            wc.nodeBudget = share(cfg.nodeBudget, n, i);
            ScanSolver w = new ScanSolver(inst, wc);
            w.setListener(new WorkerListener(i));
            workers[i] = w;
        }
    }

    /**
     * Worker {@code i}'s share of a budget split {@code n} ways: the shares
     * differ by at most one and sum to the budget exactly, and an unbounded
     * budget is left unbounded.
     */
    static long share(long budget, int n, int i) {
        if (budget == Long.MAX_VALUE) return budget;
        return budget / n + ((i < budget % n) ? 1 : 0);
    }

    /** The node budget worker {@code i} was built with. */
    long workerBudget(int i) { return workers[i].nodeBudget(); }

    /** How many workers a config's node budget and this many cores would use. */
    public static int workerCountFor(int availableCores) {
        return Math.max(1, availableCores);
    }

    /** The seed worker {@code i} runs under; package-visible so tests can rebuild a worker. */
    static long mixSeed(long base, int i) {
        long x = base + (long) i * 0x9E3779B97F4A7C15L;
        x ^= (x >>> 30); x *= 0xBF58476D1CE4E5B9L;
        x ^= (x >>> 27); x *= 0x94D049BB133111EBL;
        x ^= (x >>> 31);
        return x;
    }

    /**
     * Promote {@code idx} to champion if it now ranks ahead of the champion.
     *
     * Every new best comes through here, the champion's own included: a
     * champion that skipped the lock would publish nothing, so a rival could
     * read a stale score and take a crown it had not earned.
     */
    private void maybePromote(int idx) {
        synchronized (championLock) {
            if (ahead(idx, championIndex)) championIndex = idx;
        }
    }

    /** Whether worker {@code a}'s best ranks above {@code b}'s: deeper, better scored, lower index. */
    private boolean ahead(int a, int b) {
        int byDepth = Integer.compare(workers[a].bestPlaced, workers[b].bestPlaced);
        if (byDepth != 0) return byDepth > 0;
        int byEdges = Integer.compare(workers[a].bestMatchedEdges, workers[b].bestMatchedEdges);
        if (byEdges != 0) return byEdges > 0;
        return a < b;
    }

    private final class WorkerListener implements SolveListener {
        private final int index;
        WorkerListener(int index) { this.index = index; }

        public void onNewBest(Search s) {
            maybePromote(index);
            if (outerListener != null && championIndex == index) outerListener.onNewBest(s);
        }

        public void onSample(Search s) {
            // Only the leader's heartbeat is worth showing live -- forwarding
            // every worker's would make the reported node count and board
            // jump between unrelated descents.
            if (outerListener != null && championIndex == index) outerListener.onSample(s);
        }

        public void onRestart(Search s, int restartIndex) {
            if (outerListener != null && championIndex == index) outerListener.onRestart(s, restartIndex);
        }

        public void onSolution(Search s) {
            // A perfect board ends the whole attempt: nothing left in the
            // portfolio can beat it, so every other worker is told to unwind.
            for (int i = 0; i < workers.length; i++) {
                if (i != index) workers[i].requestStop();
            }
            synchronized (championLock) { championIndex = index; }
            if (outerListener != null) outerListener.onSolution(s);
        }
    }

    // ------------------------------------------------------------------ Search

    public void setListener(SolveListener listener) { this.outerListener = listener; }

    public void setSampleEveryNodes(long nodes) {
        for (ScanSolver w : workers) w.setSampleEveryNodes(nodes);
    }

    public void setStopAtFirstSolution(boolean stop) {
        for (ScanSolver w : workers) w.setStopAtFirstSolution(stop);
    }

    public long solve() {
        Thread[] threads = new Thread[workers.length];
        for (int i = 0; i < workers.length; i++) {
            final ScanSolver w = workers[i];
            threads[i] = new Thread(w::solve, "scan-worker-" + i);
            threads[i].start();
        }
        boolean finished = true;
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (t.isAlive()) finished = false;
        }
        // Settled only when every worker has stopped: the joins are what make
        // their final fields safe to read, and an interrupted join leaves some
        // still running, when the live champion is all there is.
        if (finished) {
            int best = 0;
            for (int i = 1; i < workers.length; i++) {
                if (ahead(i, best)) best = i;
            }
            championIndex = best;
        }
        long solutions = 0;
        for (ScanSolver w : workers) solutions += w.solutions;
        return solutions;
    }

    public void requestStop() {
        for (ScanSolver w : workers) w.requestStop();
    }

    // Called only mid-search (from a worker's own thread, about itself) or
    // after solve() has joined every thread -- see the class doc.
    private ScanSolver champion() { return workers[championIndex]; }

    public long nodes() {
        long total = 0;
        for (ScanSolver w : workers) total += w.nodes;
        return total;
    }
    public int placedCount() { return champion().placedCount(); }
    public int bestPlaced() { return champion().bestPlaced(); }
    public int bestMatchedEdges() { return champion().bestMatchedEdges(); }
    public int bestBreaks() { return champion().bestBreaks(); }
    /** Scan workers never restart themselves; see {@link ScanSolver#restarts}. */
    public int restarts() { return 0; }
    /**
     * True unless some worker stopped for a reason other than its own node
     * budget -- in practice, that a worker found a perfect solution (see
     * {@link ScanSolver#complete}, which never sets its own {@code aborted}
     * on a solve). Exhausting the search space without solving never happens
     * on a board this size, so that case is not distinguished here either.
     */
    public boolean aborted() {
        for (ScanSolver w : workers) if (!w.aborted()) return false;
        return true;
    }

    public int[] boardSnapshot() { return champion().boardSnapshot(); }
    public int[] bestBoard() { return champion().bestBoard(); }
    public int[] solutionBoard() {
        for (ScanSolver w : workers) if (w.solutionBoard() != null) return w.solutionBoard();
        return null;
    }

    public int[] bestOrderCells() { return champion().bestOrderCells(); }
    public int[] bestOrderVariants() { return champion().bestOrderVariants(); }
    public int bestOrderLength() { return champion().bestOrderLength(); }

    /** How many workers this portfolio is running. */
    public int workerCount() { return workers.length; }
}
