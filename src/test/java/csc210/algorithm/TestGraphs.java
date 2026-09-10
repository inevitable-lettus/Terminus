package csc210.algorithm;

import csc210.algorithm.cost.CostModel;
import csc210.algorithm.graph.Graph;
import csc210.algorithm.model.Edge;
import csc210.algorithm.model.Node;
import csc210.algorithm.model.Position;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds small hand-specified graphs so the search tests can check an
 * algorithm against numbers worked out on paper, without a sandbox fixture in
 * the way. Edges are given as {@code {from, to, costMillis}}; a negative cost
 * means the edge is closed.
 */
public final class TestGraphs {

    private TestGraphs() {}

    /**
     * A three-node graph with a genuinely diagonal edge, for checking that the
     * Manhattan heuristic refuses a map it would overestimate on.
     */
    public static Graph buildSkewed() {
        List<Node> nodes = List.of(
                node(0, 0.0, 0.0),
                node(1, 100.0, 100.0),
                node(2, 200.0, 100.0));
        List<Edge> edges = List.of(
                new Edge(0, 0, 1, 0, "synthetic", 1, 141.4, 10.0),
                new Edge(1, 1, 2, 1, "synthetic", 1, 100.0, 10.0));
        return Graph.of(nodes, edges);
    }

    private static Node node(int id, double xM, double yM) {
        return new Node(id, 1_000_000L + id, new Position(0.0, 0.0, xM, yM),
                0, false, 0.0, false, 0, 0, 0, null, 0.0, 0.0);
    }

    public record Fixture(Graph graph, CostModel costModel) {}

    public static Fixture build(int nodeCount, int[][] edgeSpecs) {
        List<Node> nodes = new ArrayList<>(nodeCount);
        for (int id = 0; id < nodeCount; id++) {
            // Laid out along a line; only the search tests that use geometry care.
            nodes.add(new Node(id, 1_000_000L + id, new Position(0.0, 0.0, id * 100.0, 0.0),
                    0, false, 0.0, false, 0, 0, 0, null, 0.0, 0.0));
        }

        List<Edge> edges = new ArrayList<>(edgeSpecs.length);
        long[] costs = new long[edgeSpecs.length];
        for (int id = 0; id < edgeSpecs.length; id++) {
            int[] spec = edgeSpecs[id];
            edges.add(new Edge(id, spec[0], spec[1], id, "synthetic", 1, 100.0, 10.0));
            costs[id] = spec[2];
        }

        return new Fixture(Graph.of(nodes, edges), CostModel.ofCosts(costs));
    }
}
