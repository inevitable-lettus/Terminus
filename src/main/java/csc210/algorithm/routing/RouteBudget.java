package csc210.algorithm.routing;

/**
 * The stopping conditions for the greedy route builder (step 4.3).
 *
 * <p>Without a budget the greedy loop degenerates: at every step the cheapest
 * way to cover one more stop is to extend the route you already have, so it
 * keeps extending, and you end up with a single absurd line that visits the
 * whole city and takes three hours end to end. The budget is what makes the
 * output look like a bus network instead — a real line has a maximum sensible
 * length, set by how long a driver's shift is and how much delay riders will
 * tolerate near the end of it.
 *
 * @param maxRouteMillis longest a single route may take to drive end to end
 * @param maxRoutes hard ceiling on how many routes to build, so a map that
 *        cannot be covered still terminates
 * @param targetCoverage fraction of total demand to cover before stopping,
 *        in {@code 0..1} — the loop also stops early if no uncovered demand
 *        is left within reach
 * @param walkingRadiusM how far a passenger will walk to reach a served stop;
 *        stops within this distance of a route's stops count as covered
 */
public record RouteBudget(
        long maxRouteMillis,
        int maxRoutes,
        double targetCoverage,
        double walkingRadiusM
) {

    public RouteBudget {
        if (maxRouteMillis <= 0) {
            throw new IllegalArgumentException("maxRouteMillis must be positive, got " + maxRouteMillis);
        }
        if (maxRoutes < 0) {
            throw new IllegalArgumentException("maxRoutes must not be negative, got " + maxRoutes);
        }
        if (!Double.isFinite(targetCoverage) || targetCoverage < 0.0 || targetCoverage > 1.0) {
            throw new IllegalArgumentException("targetCoverage must be finite and in [0,1], got " + targetCoverage);
        }
        if (!Double.isFinite(walkingRadiusM) || walkingRadiusM < 0.0) {
            throw new IllegalArgumentException("walkingRadiusM must be finite and non-negative, got " + walkingRadiusM);
        }
    }

    /** Stops merged closer than this count as one, so a walk shorter than this must always cover the merged-away ones. */
    static final double MIN_WALKING_RADIUS_M = 75.0;

    /** Never plan around a walk longer than this — the five-minute planning rule of thumb, rounded up. */
    static final double MAX_WALKING_RADIUS_M = 400.0;

    /** Typical stops on one line, read off the first network built against the saved Berlin map. */
    private static final int STOPS_PER_ROUTE = 12;

    /**
     * The budget the saved Berlin fixtures were tuned against: 8 minutes of
     * driving per route, at most 8 routes, 90% of demand covered, 150 m walk.
     * It is <em>not</em> a sensible budget for an arbitrary world — use
     * {@link #forStops} for that. It stays because tests pin numbers to it.
     *
     * <p>The 150 m in particular is an argument, not a constant. The planning rule
     * of thumb for how far a passenger walks is 400 m, and that is what a real
     * city would use, but on a small map it makes every stop cover its neighbours,
     * one route sweeps the whole network, and the algorithm has nothing to show.
     * The radius therefore has to sit <em>below</em> the spacing between stops, so
     * that a stop counts as covered only when a bus really serves it.
     */
    public static RouteBudget defaults() {
        return new RouteBudget(8 * 60 * 1000L, 8, 0.90, 150.0);
    }

    /**
     * A budget scaled to the stops actually on the map, so the same code gives a
     * sensible network on a compact district and on a sprawling one.
     *
     * <ul>
     *   <li><strong>Walking radius</strong> is half the median distance from a
     *       stop to its nearest neighbour — the "below the spacing" argument of
     *       {@link #defaults()}, applied to this map's spacing instead of Berlin's
     *       — clamped to {@code [75 m, 400 m]}. The floor sits above the 60 m at
     *       which stops are merged, so a merged-away stop is always within reach
     *       of the one that stands for it and still counts as covered.</li>
     *   <li><strong>Route ceiling</strong> is one route per twelve stops, clamped
     *       to {@code [3, 20]}, so a map with hundreds of stops is not asked to
     *       reach 90% coverage with eight lines.</li>
     *   <li>Route length and coverage target keep their {@link #defaults()}.</li>
     * </ul>
     *
     * <p>With fewer than two stops there is no spacing to measure and no route to
     * build, and the defaults are returned rather than failing.
     */
    public static RouteBudget forStops(csc210.algorithm.graph.Graph graph, int[] stopNodes) {
        RouteBudget base = defaults();
        if (stopNodes.length < 2) {
            return base;
        }
        double[] nearest = new double[stopNodes.length];
        for (int i = 0; i < stopNodes.length; i++) {
            double best = Double.POSITIVE_INFINITY;
            for (int j = 0; j < stopNodes.length; j++) {
                if (i != j) {
                    best = Math.min(best, csc210.algorithm.demand.DemandModel.straightLineMetres(
                            graph.node(stopNodes[i]).position(), graph.node(stopNodes[j]).position()));
                }
            }
            nearest[i] = best;
        }
        java.util.Arrays.sort(nearest);
        double median = nearest[nearest.length / 2];

        double radius = Math.min(MAX_WALKING_RADIUS_M, Math.max(MIN_WALKING_RADIUS_M, median / 2.0));
        int routes = Math.min(20, Math.max(3, (stopNodes.length + STOPS_PER_ROUTE - 1) / STOPS_PER_ROUTE));
        return new RouteBudget(base.maxRouteMillis(), routes, base.targetCoverage(), radius);
    }

    public RouteBudget withWalkingRadius(double metres) {
        return new RouteBudget(maxRouteMillis, maxRoutes, targetCoverage, metres);
    }

    public RouteBudget withMaxRoutes(int routes) {
        return new RouteBudget(maxRouteMillis, routes, targetCoverage, walkingRadiusM);
    }

    public RouteBudget withMaxRouteMillis(long millis) {
        return new RouteBudget(millis, maxRoutes, targetCoverage, walkingRadiusM);
    }
}
