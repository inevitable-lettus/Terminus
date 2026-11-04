package csc210.algorithm.journey;

import csc210.algorithm.demand.DemandModel;
import csc210.algorithm.graph.Graph;

/**
 * How much of the map a passenger can walk onto the bus network from: the share
 * of road junctions with a served stop within the walking radius.
 *
 * <p>This is the number behind "most trips have no bus stop near them". Route
 * coverage counts <em>stops</em> a line reaches, which says nothing about the
 * junctions between them; a trip starting at one of those junctions needs a
 * served stop within {@link StopAccessIndex}'s radius or it cannot board at all.
 * The test is the one {@link StopAccessIndex#near} applies to a journey, so the
 * figure predicts what journeys will do.
 *
 * @param junctions the junctions counted: every one on the map unless the caller narrowed it
 * @param reached junctions with a served stop within {@code radiusM}
 * @param farthestMetres straight-line distance from the worst-placed junction to its nearest
 *        served stop; infinite when no stop is served
 * @param radiusM the walking radius the count was made against
 */
public record AccessCoverage(int junctions, int reached, double farthestMetres, double radiusM) {

    /** Counts the junctions of {@code graph} within {@code radiusM} of any of {@code servedStops}. */
    public static AccessCoverage of(Graph graph, int[] servedStops, double radiusM) {
        return of(graph, servedStops, radiusM, node -> true);
    }

    /**
     * As {@link #of(Graph, int[], double)}, counting only the junctions {@code counted} accepts — to
     * leave out the ones no bus could ever serve, such as those in one-way pockets.
     */
    public static AccessCoverage of(Graph graph, int[] servedStops, double radiusM,
                                    java.util.function.IntPredicate counted) {
        if (!Double.isFinite(radiusM) || radiusM < 0.0) {
            throw new IllegalArgumentException("radiusM must be finite and non-negative, got " + radiusM);
        }
        int junctions = 0;
        int reached = 0;
        double farthest = 0.0;
        for (int node = 0; node < graph.nodeCount(); node++) {
            if (!counted.test(node)) {
                continue;
            }
            junctions++;
            double nearest = Double.POSITIVE_INFINITY;
            for (int stop : servedStops) {
                nearest = Math.min(nearest, DemandModel.straightLineMetres(
                        graph.node(node).position(), graph.node(stop).position()));
            }
            if (nearest <= radiusM) {
                reached++;
            }
            farthest = Math.max(farthest, nearest);
        }
        return new AccessCoverage(junctions, reached, farthest, radiusM);
    }

    /** Fraction of junctions reached, in {@code 0..1}; 1 for an empty map. */
    public double share() {
        return junctions == 0 ? 1.0 : (double) reached / junctions;
    }
}
