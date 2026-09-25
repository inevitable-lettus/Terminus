package csc210.algorithm.search;

import java.util.Arrays;

/**
 * Remembers the order a search settled vertices in, up to a cap. The cap is
 * there because a trace is for showing, not for keeping: past a few thousand
 * steps nobody can see the difference, and an unbounded trace of a large map is
 * a memory problem waiting for a big enough query.
 */
public final class ExpansionRecorder implements SearchListener {

    private final int cap;
    private int[] vertices = new int[64];
    private long[] costs = new long[64];
    private int size;
    private int seen;

    public ExpansionRecorder(int cap) {
        if (cap < 0) {
            throw new IllegalArgumentException("cap must be zero or more, got " + cap);
        }
        this.cap = cap;
    }

    @Override
    public void onSettle(int vertex, long costMillis, long estimateMillis) {
        seen++;
        if (size == cap) {
            return;
        }
        if (size == vertices.length) {
            int grown = Math.min(cap, vertices.length * 2);
            vertices = Arrays.copyOf(vertices, grown);
            costs = Arrays.copyOf(costs, grown);
        }
        vertices[size] = vertex;
        costs[size] = costMillis;
        size++;
    }

    /** Settled vertices, in order, up to the cap. */
    public int[] vertices() {
        return Arrays.copyOf(vertices, size);
    }

    /** The settled cost of each, parallel to {@link #vertices()}. */
    public long[] costs() {
        return Arrays.copyOf(costs, size);
    }

    /** Every vertex settled, recorded or not. */
    public int settledCount() {
        return seen;
    }

    /** Whether the cap cut the trace short. */
    public boolean truncated() {
        return seen > size;
    }
}
