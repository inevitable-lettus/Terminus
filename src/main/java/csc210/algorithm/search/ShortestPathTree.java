package csc210.algorithm.search;

import java.util.Arrays;

/**
 * The result of one Dijkstra run: for every node, the cost of the cheapest
 * route to it from the source, and the edge that route arrived on.
 *
 * <p>Step 3.2's idea lives here. Storing one predecessor edge per node is
 * enough to recover every path, because a shortest-path tree has exactly one
 * parent per node — if two routes into a node tied, the search kept whichever
 * it settled first, and either is equally optimal. Storing the predecessor
 * rather than the path itself keeps the result {@code O(V)} instead of
 * {@code O(V²)}: 120 nodes here, but the paths through them share almost all
 * of their length, and writing each one out separately would copy that shared
 * structure over and over.
 */
public final class ShortestPathTree {

    /** Cost of a node the search never reached. */
    public static final long UNREACHABLE = Long.MAX_VALUE;

    private static final int NO_EDGE = -1;

    private final int source;
    private final long[] costMillis;
    private final int[] predecessorEdge;
    private final int[] predecessorNode;
    private final int nodesExpanded;

    ShortestPathTree(int source, long[] costMillis, int[] predecessorEdge, int[] predecessorNode,
                     int nodesExpanded) {
        this.source = source;
        this.costMillis = costMillis;
        this.predecessorEdge = predecessorEdge;
        this.predecessorNode = predecessorNode;
        this.nodesExpanded = nodesExpanded;
    }

    public int source() {
        return source;
    }

    /** Nodes actually popped from the queue — the machine-independent work metric for Phase 6. */
    public int nodesExpanded() {
        return nodesExpanded;
    }

    public boolean isReachable(int node) {
        return costMillis[node] != UNREACHABLE;
    }

    /** Cheapest cost from the source to {@code node}, or {@link #UNREACHABLE}. */
    public long costTo(int node) {
        return costMillis[node];
    }

    /**
     * The nodes of the cheapest route from the source to {@code target}, source
     * first, target last. A node with no route back returns an empty array.
     *
     * <p>Built by walking predecessors backward from the target and reversing —
     * the path is only known from its end, because that is the direction the
     * predecessor links point.
     */
    public int[] pathTo(int target) {
        if (!isReachable(target)) {
            return new int[0];
        }
        int length = 1;
        for (int node = target; node != source; node = predecessorNode[node]) {
            length++;
        }
        int[] path = new int[length];
        int index = length - 1;
        for (int node = target; node != source; node = predecessorNode[node]) {
            path[index--] = node;
        }
        path[0] = source;
        return path;
    }

    /**
     * The edges of the cheapest route to {@code target}, in travel order. One
     * shorter than {@link #pathTo}, and the form Phase 4 needs when it has to
     * add up what a route segment actually costs.
     */
    public int[] edgePathTo(int target) {
        int[] nodes = pathTo(target);
        if (nodes.length == 0) {
            return new int[0];
        }
        int[] edges = new int[nodes.length - 1];
        int index = edges.length - 1;
        for (int node = target; node != source; node = predecessorNode[node]) {
            edges[index--] = predecessorEdge[node];
        }
        return edges;
    }

    /** Id of the edge the cheapest route arrived on, or {@code -1} at the source. */
    public int predecessorEdgeOf(int node) {
        return predecessorEdge[node];
    }

    public int reachableCount() {
        int count = 0;
        for (long cost : costMillis) {
            if (cost != UNREACHABLE) {
                count++;
            }
        }
        return count;
    }

    static long[] freshCostArray(int nodeCount) {
        long[] costs = new long[nodeCount];
        Arrays.fill(costs, UNREACHABLE);
        return costs;
    }

    static int[] freshPredecessorArray(int nodeCount) {
        int[] predecessors = new int[nodeCount];
        Arrays.fill(predecessors, NO_EDGE);
        return predecessors;
    }
}
