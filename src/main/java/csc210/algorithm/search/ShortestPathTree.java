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
 * {@code O(V²)}: 279 nodes here, but the paths through them share almost all
 * of their length, and writing each one out separately would copy that shared
 * structure over and over.
 *
 * <p><strong>Early-stop results are partial, and say so.</strong> When
 * {@link Dijkstra#from(int, int)} stops at a target before the queue empties
 * on its own, every node's cost/predecessor entry other than the source and
 * that target is a snapshot of relaxation-in-progress, not a settled answer
 * — a cheaper route through a node popped later would never get the chance
 * to improve it. Rather than expose that silently, this tree tracks whether
 * the search actually ran to completion and which nodes were settled before
 * it stopped; querying anything else on an incomplete tree throws instead of
 * returning a value that looks final but might not be.
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
    private final boolean[] settled;
    private final boolean completed;
    private final int earlyStopTarget;

    ShortestPathTree(int source, long[] costMillis, int[] predecessorEdge, int[] predecessorNode,
                     int nodesExpanded, boolean[] settled, boolean completed, int earlyStopTarget) {
        this.source = source;
        this.costMillis = costMillis;
        this.predecessorEdge = predecessorEdge;
        this.predecessorNode = predecessorNode;
        this.nodesExpanded = nodesExpanded;
        this.settled = settled;
        this.completed = completed;
        this.earlyStopTarget = earlyStopTarget;
    }

    /**
     * Whether this search ran to natural completion (the whole reachable
     * graph settled) rather than stopping early once its target was found.
     * Every full run ({@link Dijkstra#from(int)}) is complete; a call to
     * {@link Dijkstra#from(int, int)} is complete only if the target
     * happened to be the last node the queue would have yielded anyway.
     */
    public boolean isComplete() {
        return completed;
    }

    /**
     * True once {@code node} has its final cost — always true for a
     * {@link #isComplete() complete} tree's reachable nodes, and for the
     * source and the requested target of an early-stop search.
     */
    public boolean isSettled(int node) {
        return completed || node == source || settled[node];
    }

    private void requireSettled(int node) {
        if (!isSettled(node)) {
            throw new IllegalStateException(
                    "node " + node + " was not settled by this early-stop search (source=" + source
                            + ", stopped at target=" + earlyStopTarget + "); this tree only guarantees "
                            + "answers for the source and that target — call Dijkstra.from(source) for "
                            + "a complete tree");
        }
    }

    private void requireCompleted() {
        if (!completed) {
            throw new IllegalStateException(
                    "this tree is from an early-stop search (source=" + source + ", stopped at target="
                            + earlyStopTarget + ") and cannot answer a whole-graph query; call "
                            + "Dijkstra.from(source) for a complete tree");
        }
    }

    public int source() {
        return source;
    }

    /** Nodes actually popped from the queue — the machine-independent work metric for Phase 6. */
    public int nodesExpanded() {
        return nodesExpanded;
    }

    public boolean isReachable(int node) {
        requireSettled(node);
        return costMillis[node] != UNREACHABLE;
    }

    /** Cheapest cost from the source to {@code node}, or {@link #UNREACHABLE}. */
    public long costTo(int node) {
        requireSettled(node);
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
        requireSettled(node);
        return predecessorEdge[node];
    }

    public int reachableCount() {
        requireCompleted();
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
