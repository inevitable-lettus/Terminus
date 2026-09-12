package csc210.algorithm.publish;

import csc210.algorithm.client.SandboxClient;
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

    public RoutePublisher(Graph roadGraph) {
        this.roadGraph = roadGraph;
    }

    /**
     * Publishes every line, in both directions.
     *
     * <p>Bus ids are assigned as {@code 2 * routeId} outbound and
     * {@code 2 * routeId + 1} back, so the two directions of one line are
     * distinct vehicles on the server and neither overwrites the other.
     *
     * @return how many directed routes the server accepted
     * @throws IOException if the server rejects any of them, naming which
     */
    public int publishAll(SandboxClient client, RouteNetwork network)
            throws IOException, InterruptedException {
        int published = 0;
        for (BusRoute route : network.routes()) {
            int[] outbound = route.nodes();
            requireContinuous(outbound, route.label() + " outbound");
            client.publishRoute(2 * route.id(), route.label(), outbound);
            published++;

            int[] back = returnNodes(route);
            requireContinuous(back, route.label() + " return");
            client.publishRoute(2 * route.id() + 1, route.label() + "R", back);
            published++;
        }
        return published;
    }

    /**
     * The same line driven the other way.
     *
     * <p>A bus line runs in both directions; the builder emits one. On this map
     * every road has a reverse twin, so the return path is the node list read
     * backwards — but "every road has a reverse twin" is a property of this
     * particular map, not a law, so it is checked rather than assumed. A road
     * with no twin means the line genuinely cannot be driven back the way it
     * came, and that is a real answer worth failing loudly on.
     *
     * @throws IllegalStateException if any segment has no reverse twin
     */
    public int[] returnNodes(BusRoute route) {
        int[] forward = route.nodes();
        for (int edgeId : route.edges()) {
            int twin = roadGraph.edge(edgeId).reverseTwin();
            if (twin < 0 || twin >= roadGraph.edgeCount()) {
                throw new IllegalStateException("edge " + edgeId + " on " + route.label()
                        + " has no reverse twin, so this line cannot be driven back along its own path");
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

    private boolean joined(int from, int to) {
        for (int edgeId : roadGraph.outgoingEdges(from)) {
            if (roadGraph.edge(edgeId).to() == to) {
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
