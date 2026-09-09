package csc210.algorithm.graph;

import csc210.algorithm.model.Edge;
import csc210.algorithm.model.Node;

import java.util.Arrays;
import java.util.List;

/**
 * The DSTNS road network as a static, CSR-style (compressed sparse row)
 * adjacency structure: one flat {@code int[]} of edge ids, grouped by
 * source node, plus one row-offset array indexed by node id giving each
 * node's slice into it. Built once from a fetched topology and never
 * mutated afterward, so there is no reason to pay for per-node list
 * objects or per-edge boxing the way {@code List<Integer>[]} would.
 *
 * <p>Relies on DSTNS ids being dense and 0-indexed (a system invariant,
 * not assumed silently) — {@link #of} validates this and rejects a
 * topology that violates it rather than building a corrupted graph.
 */
public final class Graph {

    private final Node[] nodes;
    private final Edge[] edges;
    private final int[] adjacencyStart;
    private final int[] adjacencyEdges;

    private Graph(Node[] nodes, Edge[] edges, int[] adjacencyStart, int[] adjacencyEdges) {
        this.nodes = nodes;
        this.edges = edges;
        this.adjacencyStart = adjacencyStart;
        this.adjacencyEdges = adjacencyEdges;
    }

    public static Graph of(List<Node> nodeList, List<Edge> edgeList) {
        int n = nodeList.size();
        int m = edgeList.size();

        Node[] nodes = new Node[n];
        for (Node node : nodeList) {
            requireInRange(node.id(), n, "node id");
            if (nodes[node.id()] != null) {
                throw new IllegalArgumentException("duplicate node id " + node.id());
            }
            nodes[node.id()] = node;
        }

        Edge[] edges = new Edge[m];
        for (Edge edge : edgeList) {
            requireInRange(edge.id(), m, "edge id");
            if (edges[edge.id()] != null) {
                throw new IllegalArgumentException("duplicate edge id " + edge.id());
            }
            requireInRange(edge.from(), n, "edge.from()");
            requireInRange(edge.to(), n, "edge.to()");
            edges[edge.id()] = edge;
        }

        // Counting sort edges by `from`: one pass to count per-node out-degree,
        // a prefix sum to turn counts into row offsets, one more pass to place
        // each edge id into its node's slice. O(V + E), no comparison sort.
        int[] adjacencyStart = new int[n + 1];
        for (Edge edge : edges) {
            adjacencyStart[edge.from() + 1]++;
        }
        for (int i = 0; i < n; i++) {
            adjacencyStart[i + 1] += adjacencyStart[i];
        }

        int[] cursor = Arrays.copyOf(adjacencyStart, n);
        int[] adjacencyEdges = new int[m];
        for (Edge edge : edges) {
            adjacencyEdges[cursor[edge.from()]++] = edge.id();
        }

        return new Graph(nodes, edges, adjacencyStart, adjacencyEdges);
    }

    private static void requireInRange(int id, int size, String label) {
        if (id < 0 || id >= size) {
            throw new IllegalArgumentException(
                    label + " " + id + " is out of the expected dense range [0, " + size + ")");
        }
    }

    public int nodeCount() {
        return nodes.length;
    }

    public int edgeCount() {
        return edges.length;
    }

    public Node node(int id) {
        return nodes[id];
    }

    public Edge edge(int id) {
        return edges[id];
    }

    /** Ids of every edge leaving {@code nodeId}, in {@code O(degree)}. */
    public int[] outgoingEdges(int nodeId) {
        int start = adjacencyStart[nodeId];
        int end = adjacencyStart[nodeId + 1];
        return Arrays.copyOfRange(adjacencyEdges, start, end);
    }
}
