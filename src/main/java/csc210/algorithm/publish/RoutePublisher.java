package csc210.algorithm.publish;

import csc210.algorithm.client.SandboxClient;
import csc210.algorithm.cost.CostModel;
import csc210.algorithm.graph.Graph;
import csc210.algorithm.model.Edge;
import csc210.algorithm.routing.BusRoute;
import csc210.algorithm.routing.RouteNetwork;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Sends the generated lines back to DSTNS so they appear in its own view.
 *
 * <p><strong>Designing to somebody else's validator.</strong>
 * {@code POST /control/transit/route} checks that every consecutive pair of
 * nodes is joined by a single directed edge, and it does <em>not</em> fill in
 * the path between stops. A route described as "stop 4, stop 19, stop 37" is
 * rejected at the first gap. That constraint is the reason
 * {@link BusRoute#nodes()} has carried the full junction-by-junction traversal
 * since step 4.2 instead of just the stops — this class is where that decision
 * is either paid off or exposed.
 *
 * <p>The same check runs locally first, in {@link #requireContinuous}. Being
 * told "invalid route" by a server is far less useful than being told which
 * pair of nodes has no edge between them, and a local failure costs no round
 * trip.
 */
public final class RoutePublisher {

    private final Graph roadGraph;
    private final CostModel costModel;
    private final List<String> oneWayLines = new ArrayList<>();

    /**
     * A publisher that trusts every reverse twin to be drivable — correct only
     * on a map with no one-way streets. Prefer
     * {@link #RoutePublisher(Graph, CostModel)} against live data.
     */
    public RoutePublisher(Graph roadGraph) {
        this(roadGraph, null);
    }

    /**
     * A publisher that checks the return direction against {@code costModel}
     * before claiming a line can be driven back.
     *
     * @param costModel the same model the routes were planned with, so a road
     *        excluded there is excluded here too; {@code null} trusts every
     *        reverse twin
     */
    public RoutePublisher(Graph roadGraph, CostModel costModel) {
        this.roadGraph = roadGraph;
        this.costModel = costModel;
    }

    /**
     * Publishes every line — both directions where the line can be driven back,
     * outbound only where it cannot.
     *
     * <p>Each direction is its own vehicle on the server: {@code "R0"} outbound
     * and {@code "R0-R"} back. The server keys buses by {@code bus_id}, so
     * publishing both directions under one id would leave only the second.
     *
     * <p><strong>A one-way street makes the return leg impossible, and that is
     * an answer rather than an error.</strong> On a real map some lines simply
     * cannot retrace their own path; refusing to publish anything in that case
     * would lose the outbound line too, which is worse and less true. Those
     * lines are published one way and counted in {@link #oneWayLines()}.
     *
     * @return how many directed routes the server accepted
     * @throws IOException if the server rejects any of them, naming which
     */
    public int publishAll(SandboxClient client, RouteNetwork network)
            throws IOException, InterruptedException {
        int published = 0;
        oneWayLines.clear();
        for (BusRoute route : network.routes()) {
            int[] outbound = route.nodes();
            requireContinuous(outbound, route.label() + " outbound");
            client.publishRoute(route.label(), route.label(), outbound);
            published++;

            if (!canBeDrivenBack(route)) {
                oneWayLines.add(route.label());
                continue;
            }
            int[] back = returnNodes(route);
            requireContinuous(back, route.label() + " return");
            client.publishRoute(route.label() + "-R", route.label() + " return", back);
            published++;
        }
        return published;
    }

    /** Labels of the lines from the last {@link #publishAll} that could only go one way. */
    public List<String> oneWayLines() {
        return List.copyOf(oneWayLines);
    }

    /**
     * Whether every segment of {@code route} has a reverse twin that traffic may
     * actually use.
     *
     * <p>The twin's <em>existence</em> is not enough. DSTNS fabricates a reverse
     * for every one-way street, so the id is always there; only the cost model
     * knows whether it is a road or a fiction.
     */
    public boolean canBeDrivenBack(BusRoute route) {
        for (int edgeId : route.edges()) {
            int twin = roadGraph.edge(edgeId).reverseTwin();
            if (twin < 0 || twin >= roadGraph.edgeCount()) {
                return false;
            }
            if (costModel != null && !costModel.isTraversable(twin)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The same line driven the other way.
     *
     * <p>A bus line runs in both directions; the builder emits one, so the
     * return path is the node list read backwards. Whether that path can be
     * driven at all is a property of the map rather than a law, so it is
     * checked rather than assumed — twice over, because DSTNS gives a one-way
     * street a reverse twin that exists as an id but not as a road. A line that
     * cannot retrace itself is a real answer, not a bug.
     *
     * <p>Use {@link #canBeDrivenBack} to ask without provoking an exception.
     *
     * @throws IllegalStateException if any segment has no usable reverse twin
     */
    public int[] returnNodes(BusRoute route) {
        int[] forward = route.nodes();
        for (int edgeId : route.edges()) {
            int twin = roadGraph.edge(edgeId).reverseTwin();
            if (twin < 0 || twin >= roadGraph.edgeCount()) {
                throw new IllegalStateException("edge " + edgeId + " on " + route.label()
                        + " has no reverse twin, so this line cannot be driven back along its own path");
            }
            if (costModel != null && !costModel.isTraversable(twin)) {
                throw new IllegalStateException("edge " + edgeId + " on " + route.label()
                        + " is one-way — its reverse twin " + twin + " is not a road traffic may use, "
                        + "so this line cannot be driven back along its own path");
            }
        }
        int[] back = new int[forward.length];
        for (int i = 0; i < forward.length; i++) {
            back[i] = forward[forward.length - 1 - i];
        }
        return back;
    }

    /**
     * Whether every consecutive pair of {@code nodes} is joined by a single
     * directed edge — the server's rule, checked here first.
     */
    public boolean isContinuous(int[] nodes) {
        return firstGap(nodes) < 0;
    }

    /**
     * As {@link #isContinuous}, but throws naming the offending pair.
     *
     * @throws IllegalStateException if the traversal has a gap
     */
    public void requireContinuous(int[] nodes, String what) {
        int gap = firstGap(nodes);
        if (gap >= 0) {
            throw new IllegalStateException(what + " is not a continuous traversal: no directed edge joins node "
                    + nodes[gap] + " to node " + nodes[gap + 1] + " (position " + gap + ")");
        }
    }

    /** Index of the first node with no edge to its successor, or -1 if there is none. */
    private int firstGap(int[] nodes) {
        for (int i = 0; i + 1 < nodes.length; i++) {
            if (!joined(nodes[i], nodes[i + 1])) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Whether traffic may actually get from {@code from} to {@code to} in one
     * hop.
     *
     * <p>An edge the cost model excludes does not count, because the server's
     * validator will not count it either — a fabricated reverse is exactly the
     * case where the graph says "joined" and the road network says otherwise.
     * Ignoring that here would make the local pre-check agree with itself and
     * disagree with the server, which is the one thing it exists to prevent.
     */
    private boolean joined(int from, int to) {
        for (int edgeId : roadGraph.outgoingEdges(from)) {
            if (roadGraph.edge(edgeId).to() != to) {
                continue;
            }
            if (costModel == null || costModel.isTraversable(edgeId)) {
                return true;
            }
        }
        return false;
    }

    /** Every edge on this map that has no reverse twin, for reporting. */
    public List<Edge> edgesWithoutReverseTwin() {
        List<Edge> orphans = new ArrayList<>();
        for (int id = 0; id < roadGraph.edgeCount(); id++) {
            Edge edge = roadGraph.edge(id);
            if (edge.reverseTwin() < 0 || edge.reverseTwin() >= roadGraph.edgeCount()) {
                orphans.add(edge);
            }
        }
        return orphans;
    }
}
