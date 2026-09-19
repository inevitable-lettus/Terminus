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
     * routes, 90% of demand covered, 150 m walk.
     *
     * <p>The walking radius deserves its justification, because the obvious
     * number is wrong here. The planning rule of thumb is 400 m — roughly five
     * minutes on foot — and that is what a real city would use. This map does
     * not support it. DSTNS serves a real OpenStreetMap extract measuring
     * 901 m by 947 m with 8 bus stops on it; the closest pair of stops is
     * 176 m apart and every stop has a neighbour within 273 m. At 400 m, or
     * even at 250 m, a stop counts as covered by a bus that never goes there,
     * one route sweeps the whole network, and the algorithm has nothing to
     * show — the entire "city" is a ten-minute walk end to end and does not
     * need buses at all.
     *
     * <p>150 m sits below the closest stop spacing on this map, so a stop is
     * covered only when a bus actually serves it. That is a decision forced by
     * the sandbox's map being small, and it is stated rather than buried: at
     * 400 m the coverage numbers look better and mean less.
     *
     * <p>Every figure here was re-derived when the sandbox replaced its
     * synthetic 1980 m by 1620 m grid — 60 stops on a 180 m lattice, nearest
     * pair 255 m apart — with this extract. The reasoning survived unchanged;
     * not one of its numbers did, which is why the argument is written out
     * rather than left as a bare constant.
     */
    public static RouteBudget defaults() {
        return new RouteBudget(8 * 60 * 1000L, 8, 0.90, 150.0);
    }

    public RouteBudget withMaxRouteMillis(long millis) {
        return new RouteBudget(millis, maxRoutes, targetCoverage, walkingRadiusM);
    }
}
