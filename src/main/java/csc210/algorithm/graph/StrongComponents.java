package csc210.algorithm.graph;

import csc210.algorithm.cost.CostModel;
import csc210.algorithm.model.Edge;

/**
 * Which junctions a vehicle can drive between <em>in both directions</em>: the
 * strongly connected components of the drivable road graph.
 *
 * <p>Two junctions are in the same component exactly when each can be reached
 * from the other. That is the question a bus line has to answer before it
 * picks a stop — not "can the bus get there" but "can it get there and come
 * back". A real district is cut out of a larger city, so one-way streets run
 * off its edge: on the DSTNS 2.0 capture, 2826 of 3000 junctions form one
 * component and 13 of 81 stops sit in pockets a bus can enter and never leave,
 * or leave and never re-enter. A line that ends in one has no return service.
 *
 * <p><strong>How: Kosaraju's algorithm, twice over the graph.</strong>
 * <ol>
 *   <li>A depth-first search over the drivable edges records each junction when
 *       it <em>finishes</em> — after everything reachable from it is done.</li>
 *   <li>A second search over the edges <em>reversed</em>, starting from the
 *       junction that finished last, collects everything that can reach it.
 *       Everything collected is one component; repeat from the next unvisited
 *       junction in reverse finishing order.</li>
 * </ol>
 * Why it works: the last junction to finish lies in a component nothing else
 * can leave into, from the reversed graph's point of view, so the reversed
 * search from it cannot escape that component. {@code O(V + E)}.
 *
 * <p>Both searches are written with an explicit stack rather than recursion: a
 * 3000-junction road network is a long thin graph, and recursion that deep can
 * overflow the Java call stack.
 */
public final class StrongComponents {

    private final int[] componentOf;
    private final int[] sizes;
    private final int largest;

    private StrongComponents(int[] componentOf, int[] sizes, int largest) {
        this.componentOf = componentOf;
        this.sizes = sizes;
        this.largest = largest;
    }

    /**
     * The components of the roads {@code costModel} lets traffic use — closed
     * roads and the fabricated reverses of one-way streets are not roads.
     */
    public static StrongComponents of(Graph graph, CostModel costModel) {
        int n = graph.nodeCount();

        // Incoming lists for the second pass, drivable edges only, in CSR form.
        int[] inStart = new int[n + 1];
        for (int id = 0; id < graph.edgeCount(); id++) {
            if (costModel.isTraversable(id)) {
                inStart[graph.edge(id).to() + 1]++;
            }
        }
        for (int i = 0; i < n; i++) {
            inStart[i + 1] += inStart[i];
        }
        int[] inFrom = new int[inStart[n]];
        int[] fill = inStart.clone();
        for (int id = 0; id < graph.edgeCount(); id++) {
            if (costModel.isTraversable(id)) {
                Edge edge = graph.edge(id);
                inFrom[fill[edge.to()]++] = edge.from();
            }
        }

        // Pass 1: finishing order of an iterative depth-first search.
        int[] finished = new int[n];
        int finishedCount = 0;
        boolean[] visited = new boolean[n];
        int[] stack = new int[n];
        int[] nextEdge = new int[n];
        for (int root = 0; root < n; root++) {
            if (visited[root]) {
                continue;
            }
            int top = 0;
            stack[top] = root;
            visited[root] = true;
            nextEdge[root] = 0;
            while (top >= 0) {
                int u = stack[top];
                int[] out = graph.outgoingEdges(u);
                boolean descended = false;
                while (nextEdge[u] < out.length) {
                    int edgeId = out[nextEdge[u]++];
                    if (!costModel.isTraversable(edgeId)) {
                        continue;
                    }
                    int v = graph.edge(edgeId).to();
                    if (!visited[v]) {
                        visited[v] = true;
                        nextEdge[v] = 0;
                        stack[++top] = v;
                        descended = true;
                        break;
                    }
                }
                if (!descended) {
                    finished[finishedCount++] = u;
                    top--;
                }
            }
        }

        // Pass 2: reversed edges, latest finisher first.
        int[] componentOf = new int[n];
        java.util.Arrays.fill(componentOf, -1);
        java.util.List<Integer> sizes = new java.util.ArrayList<>();
        for (int i = n - 1; i >= 0; i--) {
            int root = finished[i];
            if (componentOf[root] >= 0) {
                continue;
            }
            int component = sizes.size();
            int size = 0;
            int top = 0;
            stack[top] = root;
            componentOf[root] = component;
            while (top >= 0) {
                int u = stack[top--];
                size++;
                for (int k = inStart[u]; k < inStart[u + 1]; k++) {
                    int v = inFrom[k];
                    if (componentOf[v] < 0) {
                        componentOf[v] = component;
                        stack[++top] = v;
                    }
                }
            }
            sizes.add(size);
        }

        int largest = -1;
        for (int c = 0; c < sizes.size(); c++) {
            if (largest < 0 || sizes.get(c) > sizes.get(largest)) {
                largest = c;
            }
        }
        return new StrongComponents(componentOf, sizes.stream().mapToInt(Integer::intValue).toArray(), largest);
    }

    /** The component {@code node} belongs to. */
    public int componentOf(int node) {
        return componentOf[node];
    }

    /** Whether a vehicle can drive from {@code a} to {@code b} and back again. */
    public boolean mutuallyReachable(int a, int b) {
        return componentOf[a] == componentOf[b];
    }

    public int count() {
        return sizes.length;
    }

    /** Junctions in the component {@code node} belongs to. */
    public int sizeOf(int node) {
        return sizes[componentOf[node]];
    }

    /** Junctions in the biggest component; 0 for an empty graph. */
    public int largestSize() {
        return largest < 0 ? 0 : sizes[largest];
    }

    /** Whether {@code node} is in the biggest component — the city's main road network. */
    public boolean inLargest(int node) {
        return componentOf[node] == largest;
    }
}
