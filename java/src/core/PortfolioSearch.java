package core;

/**
 * Runs several independently-seeded {@link ScanSolver} workers at once, one
 * per thread, and reports whichever finds the best board -- the cheap
 * multiplier docs/SOLVER.md calls out: the scan engine is fast enough
 * (~150 KB of state, no shared mutable data) that idle cores are otherwise
 * wasted on every attempt.
 *
 * Each worker gets its own full node budget, so this trades cores for depth
 * within the same wall-clock time rather than splitting one budget between
 * them. Only {@link ScanSolver} is supported: {@link MrvSolver} keeps its own
 * restart machinery and is not validated for concurrent instances.
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
 * A worker that draws level with the champion takes over only if its index is
 * lower.  Without that, a tie would leave the crown with whichever worker got
 * there first in wall-clock time.  With it, whatever order the workers reach a
 * shared best in, the champion ends as the lowest-indexed of them: the same
 * seed and worker count report the same board, and it is the last board the
 * live listener was shown.  A solution is the exception, and needs none --
 * the first one ends the attempt.
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
            ScanSolver w = new ScanSolver(inst, wc);
            w.setListener(new WorkerListener(i));
            workers[i] = w;
        }
    }

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
     * Promote {@code idx} to champion if it now leads on (placed, edges), or
     * draws level with a champion of higher index.
     */
    private void maybePromote(int idx) {
        if (idx == championIndex) return;
        synchronized (championLock) {
            ScanSolver cand = workers[idx];
            ScanSolver champ = workers[championIndex];
            int byDepth = Integer.compare(cand.bestPlaced, champ.bestPlaced);
            int byEdges = Integer.compare(cand.bestMatchedEdges, champ.bestMatchedEdges);
            if (byDepth > 0 || (byDepth == 0 && (byEdges > 0
                                                 || (byEdges == 0 && idx < championIndex)))) {
                championIndex = idx;
            }
        }
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
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
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
