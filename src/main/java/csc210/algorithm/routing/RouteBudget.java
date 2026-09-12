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

    /**
     * Defaults for the DSTNS map: 8 minutes of driving per route, at most 8
     * routes, 90% of demand covered, 250 m walk.
     *
     * <p>The walking radius deserves its justification, because the obvious
     * number is wrong here. The planning rule of thumb is 400 m — roughly five
     * minutes on foot — and that is what a real city would use. This map is
     * 1980 m by 1620 m, with the nearest pair of bus stops 255 m apart (180 m
     * grid spacing, so 180·√2 on the diagonal). At 400 m every stop covers its
     * diagonal neighbours, one route covers the entire network, and the
     * algorithm has nothing to show: the whole "city" is a twenty-minute walk
     * end to end and does not need buses at all. 250 m sits just below the
     * nearest-stop spacing, so a stop counts as covered only if a bus actually
     * serves it. That is a decision forced by the sandbox's map being small,
     * and it is stated rather than buried — at 400 m the numbers look better
     * and mean less.
     */
    public static RouteBudget defaults() {
        return new RouteBudget(8 * 60 * 1000L, 8, 0.90, 250.0);
    }

    public RouteBudget withMaxRouteMillis(long millis) {
        return new RouteBudget(millis, maxRoutes, targetCoverage, walkingRadiusM);
    }
}
