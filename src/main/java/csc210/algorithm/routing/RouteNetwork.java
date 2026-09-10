package csc210.algorithm.routing;

import java.util.List;

/**
 * What the route builder produced: the lines themselves, and how much of the
 * demand they reach.
 *
 * @param routes the generated bus lines, in the order they were built
 * @param coveredStops stop node ids reachable by some route, directly or by a
 *        walk shorter than the budget's walking radius
 * @param demandCovered demand summed over {@link #coveredStops}
 * @param totalDemand demand summed over every stop, covered or not
 */
public record RouteNetwork(
        List<BusRoute> routes,
        int[] coveredStops,
        double demandCovered,
        double totalDemand
) {

    /** Fraction of demand covered, in {@code 0..1}. */
    public double coverage() {
        return totalDemand == 0.0 ? 0.0 : demandCovered / totalDemand;
    }

    public int routeCount() {
        return routes.size();
    }
}
