package csc210.algorithm.search;

import csc210.algorithm.model.Position;
import csc210.algorithm.transit.TransitGraph;

/**
 * The optimistic guess A* uses — step 5.2.
 *
 * <pre>h(vertex) = distance(vertex, goal) / V_MAX</pre>
 *
 * <p>Read it as: "even in the best imaginable case, you still have to cover
 * this much ground, and nothing on this map moves faster than {@code V_MAX},
 * so you cannot possibly arrive sooner than this." Distance is in the sandbox's
 * projected metres ({@code x_m}, {@code y_m}), not latitude and longitude,
 * because degrees are not a length and comparing them to a speed is meaningless.
 *
 * <p><strong>Admissible</strong> means the guess never overestimates the real
 * remaining cost. That is the property A* needs to be guaranteed to return the
 * cheapest path: if the guess were ever too high, A* could dismiss the route
 * that was actually best. Here it holds because the numerator is the shortest
 * distance any route could possibly cover — a straight line — and the
 * denominator is the fastest anything on this map travels, so the quotient is
 * a lower bound on travel time by construction.
 *
 * <p><strong>Consistent</strong> is the stronger, edge-by-edge version: moving
 * along any one edge never lets the guess drop by more than that edge cost.
 * It follows from the triangle inequality on straight-line distance, and it is
 * what lets A* settle each vertex once and never revisit it, exactly as
 * Dijkstra does.
 *
 * <p><strong>The invariant that must not be broken: live conditions live only
 * in {@code g}, never in {@code h}.</strong> Traffic, rain and flooding all make
 * the real remaining cost <em>larger</em>. Folding them into the guess would
 * raise it above the true remaining cost on some vertex, admissibility would be
 * gone, and A* could return a path that is simply not the cheapest. So the
 * heuristic is deliberately built from geometry alone, which never changes
 * during a run.
 *
 * <p><strong>Manhattan or Euclidean.</strong> Manhattan distance —
 * {@code |dx| + |dy|} — is a <em>tighter</em> lower bound than the straight line
 * whenever every edge runs due north-south or east-west, because a route made
 * only of axis-aligned moves must cover the full {@code |dx| + |dy|} and cannot
 * cut the corner. A tighter admissible heuristic expands fewer vertices, so
 * Manhattan is preferred wherever it is legal. <strong>It is admissible only on
 * a map whose edges really are axis-aligned</strong> — true of every edge on the
 * synthetic grid DSTNS used to serve, and of none of the 612 on the Berlin
 * extract it serves now — and it can overestimate on a map with diagonal roads,
 * where it would break A*'s guarantee. On the current map the planner therefore
 * runs Euclidean. {@link #isManhattanAdmissibleOn} makes that precondition an
 * explicit runtime check rather than a silent assumption.
 *
 * <p>One case needs checking separately: a walk link cuts diagonally between two
 * stops, covering only the straight-line distance, so Manhattan overestimates
 * the <em>distance</em> there. It still cannot overestimate the <em>time</em>,
 * which is what the heuristic returns. Manhattan is at most {@code sqrt(2) x} the
 * straight line, so the guess is at most {@code d / 11.8} seconds, while walking
 * that same {@code d} at 1.4 m/s takes {@code d / 1.4} seconds and then adds a
 * wait on top. The guess stays well under the truth.
 */
public final class TransitHeuristic {

    /**
     * Fastest speed on this map, in metres per second: 16.67, the primary-road
     * free-flow limit. Worth noting for the writeup — DSTNS's own A* hardcodes
     * 33.33 m/s, roughly twice too loose. Ours is tighter and still admissible,
     * so it expands fewer vertices than theirs would on the same graph.
     */
    public static final double MAX_SPEED_MPS = 16.67;

    /** Tolerance, in metres, for calling an edge axis-aligned. */
    private static final double AXIS_TOLERANCE_M = 1e-6;

    public enum Distance {
        /** Straight line. Always admissible. */
        EUCLIDEAN,
        /** {@code |dx| + |dy|}. Tighter, but admissible only on an axis-aligned map. */
        MANHATTAN
    }

    private final TransitGraph graph;
    private final Distance distance;
    private final Position goal;

    private TransitHeuristic(TransitGraph graph, Distance distance, Position goal) {
        this.graph = graph;
        this.distance = distance;
        this.goal = goal;
    }

    /**
     * A heuristic aimed at {@code goalVertex}.
     *
     * @throws IllegalArgumentException if Manhattan is asked for on a graph
     *         where it would not be admissible
     */
    public static TransitHeuristic towards(TransitGraph graph, int goalVertex, Distance distance,
                                           csc210.algorithm.graph.Graph roadGraph) {
        if (distance == Distance.MANHATTAN && !isManhattanAdmissibleOn(roadGraph)) {
            throw new IllegalArgumentException(
                    "this map has edges that are not axis-aligned, so Manhattan distance can "
                            + "overestimate and would break A*'s optimality guarantee");
        }
        return new TransitHeuristic(graph, distance, graph.vertex(goalVertex).position());
    }

    /** Milliseconds A* should assume are still to come, at best, from {@code vertex}. */
    public long estimateMillis(int vertex) {
        Position from = graph.vertex(vertex).position();
        double metres = switch (distance) {
            case EUCLIDEAN -> Math.hypot(from.xM() - goal.xM(), from.yM() - goal.yM());
            case MANHATTAN -> Math.abs(from.xM() - goal.xM()) + Math.abs(from.yM() - goal.yM());
        };
        return (long) Math.floor(metres / MAX_SPEED_MPS * 1000.0);
    }

    /**
     * Whether every edge of {@code roadGraph} runs due north-south or east-west,
     * which is what makes Manhattan distance a lower bound rather than an
     * overestimate.
     */
    public static boolean isManhattanAdmissibleOn(csc210.algorithm.graph.Graph roadGraph) {
        for (int id = 0; id < roadGraph.edgeCount(); id++) {
            Position from = roadGraph.node(roadGraph.edge(id).from()).position();
            Position to = roadGraph.node(roadGraph.edge(id).to()).position();
            boolean verticalEdge = Math.abs(from.xM() - to.xM()) < AXIS_TOLERANCE_M;
            boolean horizontalEdge = Math.abs(from.yM() - to.yM()) < AXIS_TOLERANCE_M;
            if (!verticalEdge && !horizontalEdge) {
                return false;
            }
        }
        return true;
    }
}
