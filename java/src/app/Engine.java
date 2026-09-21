package app;

import core.Instance;
import core.MrvSolver;
import core.ScanSolver;
import core.Search;
import core.SolveListener;
import core.SolverConfig;
import core.Sides;
import core.Validator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;

/**
 * Head-less solver process for the web application.
 *
 * Reads its configuration from {@code --key=value} arguments and streams
 * newline-delimited JSON ("JSONL") events on stdout.  One process = one attempt.
 *
 * Protocol, one JSON object per line:
 *
 *   {"type":"meta",  n, cells, variants, colours, pieces:[[l,t,r,b],..],
 *                    fixed:[[cell,piece,rot],..], config:{..}}
 *   {"type":"frame", ms, nodes, nps, placed, best, board:[variant|-1,..]}
 *   {"type":"best",  ms, nodes, placed, edges, board:[..]}
 *   {"type":"restart", ms, nodes, index}
 *   {"type":"end",   ms, nodes, nps, best, edges, solved, status, restarts,
 *                    order:[[cell,piece,rot],..], samples:[[ms,nodes,best],..],
 *                    board:[..], valid:bool}
 *
 * "best" is the deepest board's piece count and "edges" its matched internal
 * edges, out of 480 -- the measure Eternity II results are quoted in.
 *
 * With {@code --watchStdin=1}, writing "stop" to stdin (or closing it) makes the
 * engine wind down cleanly and still emit its "end" record, so the server never
 * loses an attempt's results even when it kills the run early.
 */
public final class Engine implements SolveListener {

    private Search solver;
    private Instance inst;
    private PrintWriter out;

    private long frameIntervalNanos = 100000000L;   // 100 ms
    private long lastFrameNanos = 0;
    private long startNanos = 0;

    // progress samples, downsampled on the fly so memory stays bounded
    private static final int MAX_SAMPLES = 1200;
    private int[] sMs = new int[MAX_SAMPLES];
    private long[] sNodes = new long[MAX_SAMPLES];
    private int[] sBest = new int[MAX_SAMPLES];
    private int sampleCount = 0;
    private int sampleStride = 1;
    private int sampleSkip = 0;

    private volatile boolean stopRequested = false;

    // ------------------------------------------------------------------ main

    public static void main(String[] args) {
        SolverConfig cfg = new SolverConfig();
        long sampleEvery = 50000L;
        int frameMs = 100;
        boolean watchStdin = false;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--frameMs=")) {
                frameMs = parseIntSafe(a.substring(10), 100);
            } else if (a.startsWith("--sampleEveryNodes=")) {
                sampleEvery = parseLongSafe(a.substring(19), 50000L);
            } else if (a.startsWith("--watchStdin=")) {
                watchStdin = a.substring(13).equals("1") || a.substring(13).equals("true");
            } else {
                cfg.applyArg(a);
            }
        }

        Engine e = new Engine();
        e.frameIntervalNanos = (long) frameMs * 1000000L;
        e.run(cfg, sampleEvery, watchStdin);
    }

    private static int parseIntSafe(String s, int dflt) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return dflt; }
    }
    private static long parseLongSafe(String s, long dflt) {
        try { return Long.parseLong(s.trim()); } catch (Throwable t) { return dflt; }
    }

    // ------------------------------------------------------------------ run

    private void run(SolverConfig cfg, long sampleEvery, boolean watchStdin) {
        out = new PrintWriter(new OutputStreamWriter(System.out), false);
        inst = Instance.eternity2();
        solver = (cfg.engine == SolverConfig.ENGINE_SCAN)
               ? (Search) new ScanSolver(inst, cfg)
               : (Search) new MrvSolver(inst, cfg);
        solver.setListener(this);
        solver.setSampleEveryNodes(sampleEvery);
        solver.setStopAtFirstSolution(true);

        emitMeta(cfg);

        // Only watch stdin when a controller asked us to.  The server passes
        // --watchStdin=1 and keeps the pipe open, so if the server dies the pipe
        // closes and this process winds itself down instead of being orphaned.
        if (watchStdin) {
            Thread watchdog = new Thread(new StdinWatcher(this));
            watchdog.setDaemon(true);
            watchdog.start();
        }

        startNanos = System.nanoTime();
        lastFrameNanos = startNanos;

        long found = 0;
        String status = "completed";
        try {
            found = solver.solve();
        } catch (Throwable t) {
            status = "error: " + t.getClass().getSimpleName() + " " + safe(t.getMessage());
        }

        if (stopRequested) status = "stopped";
        else if (found >= 1) status = "solved";
        else if (solver.aborted()) status = "budget";
        else status = "exhausted";

        emitEnd(status, found >= 1);
        out.flush();
    }

    /** Called from the stdin watcher thread. */
    void requestStop() {
        stopRequested = true;
        // Bring the search to a halt at its next node; it still unwinds cleanly
        // and the "end" record is emitted by run().
        solver.requestStop();
    }

    static final class StdinWatcher implements Runnable {
        private final Engine engine;
        StdinWatcher(Engine e) { this.engine = e; }
        public void run() {
            BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
            try {
                while (true) {
                    String line = r.readLine();
                    if (line == null) break;                 // stdin closed
                    if (line.trim().equals("stop")) break;
                }
            } catch (IOException e) {
                // fall through and stop
            }
            engine.requestStop();
        }
    }

    // ------------------------------------------------------------- listener

    public void onNewBest(Search s) {
        recordSample(s);
        StringBuilder sb = new StringBuilder(4096);
        sb.append("{\"type\":\"best\",\"ms\":").append(ms());
        sb.append(",\"nodes\":").append(s.nodes());
        sb.append(",\"placed\":").append(s.placedCount());
        sb.append(",\"edges\":").append(s.bestMatchedEdges());
        sb.append(",\"board\":");
        appendBoard(sb, s.boardSnapshot());
        sb.append('}');
        emit(sb.toString());
    }

    public void onSample(Search s) {
        recordSample(s);
        long now = System.nanoTime();
        if (now - lastFrameNanos < frameIntervalNanos) return;
        lastFrameNanos = now;

        long elapsed = ms();
        StringBuilder sb = new StringBuilder(4096);
        sb.append("{\"type\":\"frame\",\"ms\":").append(elapsed);
        sb.append(",\"nodes\":").append(s.nodes());
        sb.append(",\"nps\":").append(elapsed <= 0 ? 0 : (s.nodes() * 1000L / elapsed));
        sb.append(",\"placed\":").append(s.placedCount());
        sb.append(",\"best\":").append(s.bestPlaced());
        sb.append(",\"restarts\":").append(s.restarts());
        sb.append(",\"board\":");
        appendBoard(sb, s.boardSnapshot());
        sb.append('}');
        emit(sb.toString());
    }

    public void onRestart(Search s, int index) {
        emit("{\"type\":\"restart\",\"ms\":" + ms()
             + ",\"nodes\":" + s.nodes() + ",\"index\":" + index + "}");
    }

    /**
     * Nothing to emit: the search stops at its first solution, and the "end"
     * record carries the solved board and its validation a moment later.
     */
    public void onSolution(Search s) { }

    // ------------------------------------------------------------- emitters

    private void emitMeta(SolverConfig cfg) {
        StringBuilder sb = new StringBuilder(16384);
        sb.append("{\"type\":\"meta\",\"n\":").append(inst.n);
        sb.append(",\"cells\":").append(inst.cells);
        sb.append(",\"variants\":").append(inst.numVariants);
        sb.append(",\"colours\":").append(inst.numColours);
        sb.append(",\"pieces\":[");
        for (int id = 0; id < inst.numPieces; id++) {
            if (id > 0) sb.append(',');
            int p = inst.packedSides[id];
            sb.append('[').append(Sides.left(p)).append(',').append(Sides.top(p))
              .append(',').append(Sides.right(p)).append(',').append(Sides.bottom(p)).append(']');
        }
        sb.append("],\"fixed\":[");
        for (int i = 0; i < inst.fixedCell.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('[').append(inst.fixedCell[i]).append(',')
              .append(inst.fixedPiece[i]).append(',').append(inst.fixedRot[i]).append(']');
        }
        sb.append("],\"config\":").append(cfg.toJson()).append('}');
        emit(sb.toString());
    }

    private void emitEnd(String status, boolean solved) {
        int[] board = (solver.bestBoard() != null) ? solver.bestBoard() : solver.boardSnapshot();
        String valid;
        if (solved && solver.solutionBoard() != null) {
            String err = Validator.validateComplete(inst, solver.solutionBoard());
            valid = (err == null) ? "true" : "false";
            board = solver.solutionBoard();
        } else {
            String err = Validator.validatePartial(inst, board, false);
            valid = (err == null) ? "true" : "false";
        }

        long elapsed = ms();
        StringBuilder sb = new StringBuilder(32768);
        sb.append("{\"type\":\"end\",\"ms\":").append(elapsed);
        sb.append(",\"nodes\":").append(solver.nodes());
        sb.append(",\"nps\":").append(elapsed <= 0 ? 0 : (solver.nodes() * 1000L / elapsed));
        sb.append(",\"best\":").append(solver.bestPlaced());
        sb.append(",\"edges\":").append(Validator.matchedEdges(inst, board));
        sb.append(",\"solved\":").append(solved);
        sb.append(",\"valid\":").append(valid);
        sb.append(",\"restarts\":").append(solver.restarts());
        sb.append(",\"status\":\"").append(status).append('"');

        sb.append(",\"order\":[");
        int[] orderCells = solver.bestOrderCells();
        int[] orderVariants = solver.bestOrderVariants();
        for (int i = 0; i < solver.bestOrderLength(); i++) {
            if (i > 0) sb.append(',');
            int cell = orderCells[i];
            int v = orderVariants[i];
            sb.append('[').append(cell).append(',').append(v >>> 2)
              .append(',').append(v & 3).append(']');
        }
        sb.append(']');

        sb.append(",\"samples\":[");
        for (int i = 0; i < sampleCount; i++) {
            if (i > 0) sb.append(',');
            sb.append('[').append(sMs[i]).append(',').append(sNodes[i])
              .append(',').append(sBest[i]).append(']');
        }
        sb.append(']');

        sb.append(",\"board\":");
        appendBoard(sb, board);
        sb.append('}');
        emit(sb.toString());
    }

    private void appendBoard(StringBuilder sb, int[] board) {
        sb.append('[');
        for (int i = 0; i < board.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(board[i]);
        }
        sb.append(']');
    }

    private void emit(String line) {
        out.print(line);
        out.print('\n');
        out.flush();
    }

    private long ms() {
        return (System.nanoTime() - startNanos) / 1000000L;
    }

    /**
     * Keep at most MAX_SAMPLES progress points by halving the resolution each
     * time the buffer fills.
     */
    private void recordSample(Search s) {
        if (sampleSkip > 0) { sampleSkip--; return; }
        sampleSkip = sampleStride - 1;

        if (sampleCount == MAX_SAMPLES) {
            int w = 0;
            for (int i = 0; i < MAX_SAMPLES; i += 2) {
                sMs[w] = sMs[i]; sNodes[w] = sNodes[i]; sBest[w] = sBest[i];
                w++;
            }
            sampleCount = w;
            sampleStride *= 2;
            sampleSkip = sampleStride - 1;
        }
        sMs[sampleCount] = (int) ms();
        sNodes[sampleCount] = s.nodes();
        sBest[sampleCount] = s.bestPlaced();
        sampleCount++;
    }

    private static String safe(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"' || ch == '\\') sb.append(' ');
            else if (ch < 32) sb.append(' ');
            else sb.append(ch);
        }
        return sb.toString();
    }
}
