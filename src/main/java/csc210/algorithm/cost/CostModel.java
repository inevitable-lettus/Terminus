package csc210.algorithm.cost;

import csc210.algorithm.client.SnapshotData;
import csc210.algorithm.graph.Graph;
import csc210.algorithm.model.EdgeState;

/**
 * What one edge costs to drive, in integer milliseconds:
 *
 * <pre>cost_ms(e) = round( length_m(e) / max(0.1, effective_speed_mps(e)) * 1000 )</pre>
 *
 * <p>A {@code CostModel} is a frozen view of one snapshot, not a live feed.
 * DSTNS re-derives every edge's speed each tick — signal phase alone makes a
 * weight oscillate second to second — so a search running against a moving
 * weight would be measuring the weather, not the graph. Costs are computed
 * once at construction, in {@code O(E)}, and never change afterward.
 *
 * <p>Cost lives on edges rather than nodes because that is where the physical
 * quantity lives: a road has a length and a speed, a junction does not. The
 * multipliers DSTNS applies upstream are multiplicative for the same reason —
 * congestion slows a road in proportion to how long it is, so an additive
 * penalty would punish a 50 m street exactly as hard as a 5 km motorway.
 *
 * <p>A closed edge is not expensive, it is absent: {@link #isTraversable}
 * returns false and search must skip it. Modelling closure as a large finite
 * weight would let a search route through a closed road whenever every
 * alternative happened to be worse, which is not a slower answer but a wrong one.
 *
 * <p><strong>Two different reasons an edge is untraversable, one mechanism.</strong>
 * A <em>closed</em> road exists but is shut right now — a crash, a flood, a
 * manual closure — and may reopen on the next snapshot. A <em>synthetic
 * reverse</em> never existed at all: DSTNS emits a reverse twin for every
 * one-way street on {@code /view/topology} so the graph looks symmetric, and
 * only {@code /view/edges} marks it with {@code source.synthetic_reverse}.
 * Driving one is not slow, it is illegal. Both end up excluded here, because
 * "search must not use this edge" is the same instruction either way, but they
 * are not the same fact and the caller decides which ids fall into which.
 */
public final class CostModel {

    /**
     * Speed floor, in metres per second. DSTNS can drive effective speed to
     * zero on a fully-blocked segment; dividing by that is infinity, so the
     * traversal time of a jammed edge is capped at "180 m at 0.1 m/s", about
     * half an hour. Named after the same clamp in DSTNS's own RoutePlanner so
     * the two agree edge for edge.
     */
    public static final double MIN_SPEED_MPS = 0.1;

    /** Sentinel stored in {@link #costMillis} for an edge no path may use. */
    private static final long CLOSED = -1L;

    private final long[] costMillis;

    private CostModel(long[] costMillis) {
        this.costMillis = costMillis;
    }

    /**
     * Builds the cost of every edge in {@code graph} from one snapshot, with
     * every edge the snapshot does not close treated as drivable.
     *
     * @throws IllegalArgumentException if the snapshot does not carry exactly
     *         one state per edge, or refers to an edge the graph does not have
     */
    public static CostModel of(Graph graph, SnapshotData snapshot) {
        return of(graph, snapshot, java.util.Set.of());
    }

    /**
     * As {@link #of(Graph, SnapshotData)}, additionally excluding edges that do
     * not exist as drivable roads at all — in practice the ids DSTNS flags as
     * {@code source.synthetic_reverse}, the fabricated reverse direction of a
     * one-way street.
     *
     * <p>These cannot be detected from the snapshot: a synthetic reverse
     * reports a perfectly healthy speed and {@code closed: false}, exactly like
     * any other road. They have to be supplied by the caller, which is why this
     * is a parameter rather than something derived here.
     *
     * @param nonTraversableEdges edge ids no path may use, whatever the snapshot says
     * @throws IllegalArgumentException if the snapshot does not carry exactly
     *         one state per edge, refers to an edge the graph does not have, or
     *         an excluded id is not an edge of {@code graph}
     */
    public static CostModel of(Graph graph, SnapshotData snapshot,
                               java.util.Set<Integer> nonTraversableEdges) {
        int edgeCount = graph.edgeCount();
        if (snapshot.edges().size() != edgeCount) {
            throw new IllegalArgumentException(
                    "snapshot has " + snapshot.edges().size() + " edge states but the graph has "
                            + edgeCount + " edges — snapshot and topology are out of step");
        }

        long[] costMillis = new long[edgeCount];
        // Nothing guarantees the snapshot lists edges in id order, so index by
        // id rather than trusting position. -2 marks "not yet seen" and is
        // distinguishable from CLOSED (-1).
        java.util.Arrays.fill(costMillis, -2L);

        for (EdgeState state : snapshot.edges()) {
            int id = state.id();
            if (id < 0 || id >= edgeCount) {
                throw new IllegalArgumentException("snapshot refers to unknown edge id " + id);
            }
            if (costMillis[id] != -2L) {
                throw new IllegalArgumentException("snapshot lists edge id " + id + " twice");
            }
            costMillis[id] = state.closed()
                    ? CLOSED
                    : travelTimeMillis(graph.edge(id).lengthM(), state.effectiveSpeedMps());
        }

        for (int id = 0; id < edgeCount; id++) {
            if (costMillis[id] == -2L) {
                throw new IllegalArgumentException("snapshot has no state for edge id " + id);
            }
        }

        // Applied after the snapshot pass, not during it: a road that does not
        // exist stays excluded no matter what state was reported for it.
        for (int id : nonTraversableEdges) {
            if (id < 0 || id >= edgeCount) {
                throw new IllegalArgumentException(
                        "asked to exclude edge id " + id + ", which is not an edge of this graph");
            }
            costMillis[id] = CLOSED;
        }
        return new CostModel(costMillis);
    }

    /**
     * Builds a cost model from costs given directly, for hand-built graphs in
     * tests where there is no sandbox snapshot to read. A negative entry means
     * the edge is closed.
     */
    public static CostModel ofCosts(long[] costMillis) {
        return new CostModel(costMillis.clone());
    }

    /** The formula, isolated so a test can check the arithmetic on its own. */
    public static long travelTimeMillis(double lengthM, double effectiveSpeedMps) {
        return Math.round(lengthM / Math.max(MIN_SPEED_MPS, effectiveSpeedMps) * 1000.0);
    }

    /** False if no path may use this edge, because it is closed to traffic. */
    public boolean isTraversable(int edgeId) {
        return costMillis[edgeId] >= 0;
    }

    /**
     * Milliseconds to drive {@code edgeId} end to end.
     *
     * @throws IllegalStateException if the edge is closed — callers must test
     *         {@link #isTraversable} first, so that a closed edge can never
     *         silently contribute a number to a path cost
     */
    public long costMillis(int edgeId) {
        long cost = costMillis[edgeId];
        if (cost < 0) {
            throw new IllegalStateException("edge " + edgeId + " is closed and has no traversal cost");
        }
        return cost;
    }

    /**
     * Milliseconds for a bus to drive {@code edgeId} on its fixed line. A line is
     * planned once per run, so a road on it can close later; the bus cannot
     * re-route, so a closed road is priced as gridlock (the 0.1 m/s floor)
     * rather than refused. A bus that has to wait out a closure is very late,
     * not gone.
     */
    public long busMillis(int edgeId, double lengthM) {
        return isTraversable(edgeId) ? costMillis[edgeId] : travelTimeMillis(lengthM, MIN_SPEED_MPS);
    }

    public int edgeCount() {
        return costMillis.length;
    }
}
