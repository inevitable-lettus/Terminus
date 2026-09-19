package csc210.algorithm.publish;

import csc210.algorithm.Fixtures;
import csc210.algorithm.client.SnapshotData;
import csc210.algorithm.client.TopologyData;
import csc210.algorithm.cost.CostModel;
import csc210.algorithm.demand.DemandModel;
import csc210.algorithm.graph.Graph;
import csc210.algorithm.routing.BusRoute;
import csc210.algorithm.routing.RouteBudget;
import csc210.algorithm.routing.RouteNetwork;
import csc210.algorithm.routing.RouteNetworkBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step B.3, without a sandbox. The server's rule for
 * {@code POST /control/transit/route} is that every consecutive pair of nodes
 * must be joined by one directed edge; these tests check the routes satisfy it
 * before anything is sent, and that the return leg is real rather than assumed.
 */
class RoutePublisherTest {

    private static Graph roadGraph;
    private static RouteNetwork network;
    private static RoutePublisher publisher;

    @BeforeAll
    static void build() {
        TopologyData topology = Fixtures.topology();
        SnapshotData snapshot = Fixtures.snapshotAt1800();
        roadGraph = Graph.of(topology.nodes(), topology.edges());
        CostModel costModel = CostModel.of(roadGraph, snapshot, Fixtures.oneWayEdgeIdsAt1800());
        DemandModel demandModel = DemandModel.of(roadGraph, snapshot);
        network = new RouteNetworkBuilder(roadGraph, costModel, demandModel, RouteBudget.defaults()).build();
        publisher = new RoutePublisher(roadGraph);
    }

    @Test
    void everyGeneratedRouteIsAContinuousTraversal() {
        for (BusRoute route : network.routes()) {
            assertTrue(publisher.isContinuous(route.nodes()),
                    route.label() + " has a gap, so the server would reject it");
        }
    }

    @Test
    void everyReturnLegIsAlsoAContinuousTraversal() {
        for (BusRoute route : network.routes()) {
            assertTrue(publisher.isContinuous(publisher.returnNodes(route)),
                    route.label() + " cannot be driven back along its own path");
        }
    }

    @Test
    void theReturnLegIsTheOutboundPathReadBackwards() {
        for (BusRoute route : network.routes()) {
            int[] forward = route.nodes();
            int[] back = publisher.returnNodes(route);
            assertEquals(forward.length, back.length);
            for (int i = 0; i < forward.length; i++) {
                assertEquals(forward[forward.length - 1 - i], back[i]);
            }
            assertEquals(forward[0], back[back.length - 1], "the return leg must finish where the line started");
        }
    }

    @Test
    void aGappedRouteIsRejectedBeforeItIsEverSent() {
        // Two junctions on opposite corners of the map, with nothing between
        // them: exactly the shape a stops-only route would have.
        int[] gapped = {0, roadGraph.nodeCount() - 1};
        assertFalse(publisher.isContinuous(gapped));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> publisher.requireContinuous(gapped, "a stops-only route"));
        assertTrue(thrown.getMessage().contains("not a continuous traversal"), thrown.getMessage());
    }

    @Test
    void droppingOneJunctionFromARealRouteBreaksIt() {
        // The sharper version of the same check: take a route the server would
        // accept and remove a single intermediate junction. If this still passed,
        // the continuity check would be doing nothing.
        BusRoute route = network.routes().get(0);
        int[] full = route.nodes();
        assertTrue(full.length > 3, "need a route long enough to remove a node from");

        int[] punctured = new int[full.length - 1];
        System.arraycopy(full, 0, punctured, 0, 2);
        System.arraycopy(full, 3, punctured, 2, full.length - 3);

        assertFalse(publisher.isContinuous(punctured),
                "a route with a junction missing must not be accepted as continuous");
    }

    @Test
    void everyRoadOnThisMapHasAReverseTwin() {
        // The assumption the return leg rests on, stated as a check. If a future
        // map has one-way streets this fails here rather than at the server.
        assertEquals(java.util.List.of(), publisher.edgesWithoutReverseTwin());
    }
}
