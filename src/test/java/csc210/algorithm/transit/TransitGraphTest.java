package csc210.algorithm.transit;

import csc210.algorithm.Fixtures;
import csc210.algorithm.client.SnapshotData;
import csc210.algorithm.client.TopologyData;
import csc210.algorithm.cost.CostModel;
import csc210.algorithm.demand.DemandModel;
import csc210.algorithm.graph.Graph;
import csc210.algorithm.routing.RouteBudget;
import csc210.algorithm.routing.RouteNetwork;
import csc210.algorithm.routing.RouteNetworkBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step 4.4. The build order's proof: the transit graph is connected, and
 * transfer edges exist wherever two lines share a stop.
 */
class TransitGraphTest {

    private static Graph roadGraph;
    private static RouteNetwork network;
    private static TransitGraph transitGraph;
    private static TransitGraph narrowWalkGraph;

    @BeforeAll
    static void build() {
        TopologyData topology = Fixtures.topology();
        SnapshotData snapshot = Fixtures.snapshotAt1800();
        roadGraph = Graph.of(topology.nodes(), topology.edges());
        CostModel costModel = CostModel.of(roadGraph, snapshot);
        DemandModel demandModel = DemandModel.of(roadGraph, snapshot);
        RouteBudget budget = RouteBudget.defaults();
        network = new RouteNetworkBuilder(roadGraph, costModel, demandModel, budget).build();
        transitGraph = TransitGraph.of(roadGraph, network, costModel);
        narrowWalkGraph = TransitGraph.of(roadGraph, network, costModel, budget.walkingRadiusM());
    }

    @Test
    void oneVertexPerStopPerLine() {
        int expected = 0;
        for (var route : network.routes()) {
            expected += route.stopCount();
        }
        assertEquals(expected, transitGraph.vertexCount());

        for (int i = 0; i < transitGraph.vertexCount(); i++) {
            TransitStop vertex = transitGraph.vertex(i);
            assertTrue(roadGraph.node(vertex.roadNode()).busStop());
            assertEquals(roadGraph.node(vertex.roadNode()).position(), vertex.position());
        }
        System.out.printf("transit graph: %d vertices, %d links, over %d road stops%n",
                transitGraph.vertexCount(), transitGraph.linkCount(), transitGraph.servedStops().length);
    }

    @Test
    void everyStopServedByTwoLinesHasTransferEdges() {
        int sharedStops = 0;
        for (int stop : transitGraph.servedStops()) {
            int[] here = transitGraph.verticesAt(stop);
            if (here.length < 2) {
                continue;
            }
            sharedStops++;
            for (int from : here) {
                Set<Integer> reachedByTransfer = new HashSet<>();
                for (TransitLink link : transitGraph.outgoing(from)) {
                    if (link.kind() == TransitLink.Kind.TRANSFER) {
                        reachedByTransfer.add(link.to());
                        assertEquals(TransitGraph.TRANSFER_WAIT_MILLIS, link.millis());
                    }
                }
                assertEquals(here.length - 1, reachedByTransfer.size(),
                        "vertex " + from + " at shared stop " + stop + " cannot reach every other line here");
            }
        }
        assertTrue(sharedStops > 0, "no stop is served by two lines, so no transfer is ever possible");
        System.out.println("stops served by more than one line: " + sharedStops);
    }

    @Test
    void ridingCostsWhatDrivingThatStretchCosts() {
        for (int i = 0; i < transitGraph.vertexCount(); i++) {
            for (TransitLink link : transitGraph.outgoing(i)) {
                assertTrue(link.millis() > 0, "a move must cost something: " + link);
                if (link.kind() == TransitLink.Kind.RIDE) {
                    assertEquals(transitGraph.vertex(link.from()).routeId(),
                            transitGraph.vertex(link.to()).routeId(),
                            "a ride link must stay on one line");
                    assertEquals(link.routeId(), transitGraph.vertex(link.from()).routeId());
                }
            }
        }
    }

    @Test
    void aWalkCostsTheWalkPlusTheWait() {
        int walks = 0;
        for (int i = 0; i < transitGraph.vertexCount(); i++) {
            for (TransitLink link : transitGraph.outgoing(i)) {
                if (link.kind() != TransitLink.Kind.WALK) {
                    continue;
                }
                walks++;
                double metres = TransitGraph.straightLineMetres(
                        transitGraph.vertex(link.from()).position(),
                        transitGraph.vertex(link.to()).position());
                assertTrue(metres <= transitGraph.walkingRadiusM());
                long expected = Math.round(metres / TransitGraph.WALK_SPEED_MPS * 1000.0)
                        + TransitGraph.TRANSFER_WAIT_MILLIS;
                assertEquals(expected, link.millis());
            }
        }
        System.out.println("walk links: " + walks);
    }

    /**
     * Why the interchange walk radius is 400 m and not the route builder's
     * 250 m. At 250 m line R1 shares no stop with any other line and 16 of the
     * 61 vertices are cut off — a passenger boarding R1 could never leave it.
     * Asserted so the reason for the two separate radii stays visible.
     */
    @Test
    void aNarrowInterchangeWalkDisconnectsTheNetwork() {
        assertTrue(reachableFromFirstVertex(narrowWalkGraph) < narrowWalkGraph.vertexCount(),
                "the 250 m graph is expected to be split; if this passes the two radii could be merged");
        System.out.printf("at a %.0f m interchange walk only %d of %d vertices are reachable%n",
                narrowWalkGraph.walkingRadiusM(), reachableFromFirstVertex(narrowWalkGraph),
                narrowWalkGraph.vertexCount());
    }

    /** A passenger must be able to get from anywhere on the network to anywhere else. */
    @Test
    void theWholeTransitGraphIsConnected() {
        int reached = reachableFromFirstVertex(transitGraph);

        assertEquals(transitGraph.vertexCount(), reached,
                "only " + reached + " of " + transitGraph.vertexCount() + " vertices are reachable");
    }

    private static int reachableFromFirstVertex(TransitGraph graph) {
        boolean[] seen = new boolean[graph.vertexCount()];
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(0);
        seen[0] = true;
        int reached = 1;
        while (!queue.isEmpty()) {
            for (TransitLink link : graph.outgoing(queue.poll())) {
                if (!seen[link.to()]) {
                    seen[link.to()] = true;
                    reached++;
                    queue.add(link.to());
                }
            }
        }
        return reached;
    }
}
