package csc210.algorithm.search;

import csc210.algorithm.cost.CostModel;
import csc210.algorithm.graph.Graph;
import csc210.algorithm.model.Edge;
import csc210.algorithm.model.Node;
import csc210.algorithm.model.Position;
import csc210.algorithm.routing.RouteNetwork;
import csc210.algorithm.transit.TransitGraph;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The heuristic divides distance by the fastest speed on the map. If a road is
 * faster than the configured 16.67 m/s, dividing by the constant would
 * overestimate and quietly break A*'s guarantee — so the divisor must follow the map.
 */
class HeuristicSpeedTest {

    private static Graph roads(double freeSpeed) {
        List<Node> nodes = List.of(node(0, 0), node(1, 1000));
        List<Edge> edges = List.of(
                new Edge(0, 0, 1, 1, "x", 1, 1000.0, freeSpeed),
                new Edge(1, 1, 0, 0, "x", 1, 1000.0, freeSpeed));
        return Graph.of(nodes, edges);
    }

    private static Node node(int id, double x) {
        return new Node(id, 100L + id, new Position(0, 0, x, 0), 0, false, 0, false, 0, 0, 0, null, 0, 0);
    }

    /** Estimate from a spliced-in source to a target 1000 m away, on a graph with no bus lines at all. */
    private static long estimateAcross(Graph road) {
        TransitGraph transit = TransitGraph.of(road, new RouteNetwork(List.of(), new int[0], 0, 0),
                CostModel.ofCosts(new long[]{1, 1}));
        TransitGraph.Extended extended = transit.withEndpoints(
                0, road.node(0).position(), new long[0], 1, road.node(1).position(), new long[0]);
        return TransitHeuristic.towards(extended.graph(), extended.targetVertex(),
                TransitHeuristic.Distance.EUCLIDEAN, road).estimateMillis(extended.sourceVertex());
    }

    @Test
    void anOrdinaryMapUsesTheConfiguredFloor() {
        assertEquals((long) Math.floor(1000 / TransitHeuristic.MAX_SPEED_MPS * 1000.0), estimateAcross(roads(10.0)));
    }

    @Test
    void aFasterRoadShrinksTheEstimateSoItCannotOverestimate() {
        long slowMap = estimateAcross(roads(16.67));
        long fastMap = estimateAcross(roads(33.33));

        assertTrue(fastMap < slowMap, fastMap + " should be below " + slowMap);
        // 1000 m at 33.33 m/s is 30.003 s: the most any vehicle on that map could do it in.
        assertEquals(30_003L, fastMap);
    }
}
