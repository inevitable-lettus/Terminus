package csc210.algorithm.search;

import csc210.algorithm.cost.CostModel;
import csc210.algorithm.ds.IndexedMinHeap;
import csc210.algorithm.graph.Graph;
import csc210.algorithm.model.Edge;

/**
 * Single-source shortest paths over the road graph, driven by our own
 * {@link IndexedMinHeap}.
 *
 * <p>The algorithm is one idea repeated: <em>relaxation</em>. For an edge
 * {@code u -> v}, ask whether reaching {@code v} through {@code u} is cheaper
 * than the best route to {@code v} found so far; if it is, write down the
 * cheaper cost and remember that the route arrived via that edge. Every node
 * starts at "unreachable" and only ever gets cheaper.
 *
 * <p>The <em>greedy invariant</em> is what makes it fast rather than just
 * correct: the moment a node is popped from the queue, its cost is final and
 * it is never revisited. That holds because the queue always yields the
 * cheapest unsettled node, and every remaining route to it would have to leave
 * through some other unsettled node that already costs at least as much, then
 * pay for more edges on top. <strong>This is precisely where non-negative
 * weights are load-bearing</strong> — "pay for more edges on top" only makes a
 * route worse if edges cannot be negative. With a negative edge, a longer
 * detour could undercut a settled node and the whole invariant collapses. Our
 * costs are travel times, so they are non-negative by construction, and the
 * clamp in {@link CostModel} keeps them strictly positive.
 *
 * <p>Complexity, derived rather than quoted: every node is inserted at most
 * once and popped at most once, and each of those is {@code O(log V)} — that is
 * {@code O(V log V)}. Every edge is examined exactly once, from its source
 * node, and may trigger one {@code decreaseKey} at {@code O(log V)} — that is
 * {@code O(E log V)}. Together, {@code O((V + E) log V)}. On a road network
 * {@code E} is a small multiple of {@code V} (here 436 edges over 120 nodes,
 * average degree under 4), so in practice it behaves like {@code O(V log V)}.
 */
public final class Dijkstra {

    private final Graph graph;
    private final CostModel costModel;

    public Dijkstra(Graph graph, CostModel costModel) {
        this.graph = graph;
        this.costModel = costModel;
    }

    /** Cheapest route from {@code source} to every node it can reach. */
    public ShortestPathTree from(int source) {
        return from(source, -1);
    }

    /**
     * As {@link #from(int)}, but stops as soon as {@code target} is settled.
     * Correct for the same reason the invariant is: once the target pops, no
     * later pop can improve it.
     *
     * @param target node to stop at, or {@code -1} to settle the whole graph
     */
    public ShortestPathTree from(int source, int target) {
        int nodeCount = graph.nodeCount();
        long[] costMillis = ShortestPathTree.freshCostArray(nodeCount);
        int[] predecessorEdge = ShortestPathTree.freshPredecessorArray(nodeCount);
        int[] predecessorNode = ShortestPathTree.freshPredecessorArray(nodeCount);
        boolean[] settled = new boolean[nodeCount];

        IndexedMinHeap queue = new IndexedMinHeap(nodeCount);
        costMillis[source] = 0L;
        queue.insert(source, 0L);

        int nodesExpanded = 0;
        while (!queue.isEmpty()) {
            int u = queue.extractMin();
            settled[u] = true;
            nodesExpanded++;
            if (u == target) {
                break;
            }

            for (int edgeId : graph.outgoingEdges(u)) {
                // A closed road is not a slow road: it is skipped entirely, so
                // no path can ever be built through one.
                if (!costModel.isTraversable(edgeId)) {
                    continue;
                }
                Edge edge = graph.edge(edgeId);
                int v = edge.to();
                if (settled[v]) {
                    continue;
                }

                long throughU = costMillis[u] + costModel.costMillis(edgeId);
                if (throughU < costMillis[v]) {
                    costMillis[v] = throughU;
                    predecessorEdge[v] = edgeId;
                    predecessorNode[v] = u;
                    // One queue entry per node for the whole run: inserted the
                    // first time v is seen, cheapened in place after that.
                    queue.insertOrDecrease(v, throughU);
                }
            }
        }

        return new ShortestPathTree(source, costMillis, predecessorEdge, predecessorNode, nodesExpanded);
    }
}
