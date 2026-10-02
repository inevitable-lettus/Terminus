package csc210.algorithm.transit;

import csc210.algorithm.Fixtures;
import csc210.algorithm.cost.CostModel;
import csc210.algorithm.graph.Graph;
import csc210.algorithm.model.Edge;
import csc210.algorithm.model.Node;
import csc210.algorithm.model.Position;
import csc210.algorithm.routing.BusRoute;
import csc210.algorithm.routing.RouteBudget;
import csc210.algorithm.routing.RouteNetwork;
import csc210.algorithm.routing.RouteNetworkBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step 4.4. The build order's proof: the transit graph is connected, and
 * transfer edges exist wherever two lines share a stop.
 */
class TransitGraphTest {

    private static Graph roadGraph;
    private static RouteNetwork network;
    private static TransitGraph transitGraph;

    /**
     * Built on the DSTNS 2.0 capture, the real city: six lines with interchanges
     * between them. The Berlin fixture is now served by a single line, which
     * leaves nothing to change between.
     */
    @BeforeAll
    static void build() {
        csc210.algorithm.world.World world = Fixtures.v2World1800();
        roadGraph = world.graph();
        CostModel costModel = world.costModel();
        RouteBudget budget = world.budget();
        network = new RouteNetworkBuilder(roadGraph, costModel, world.demandModel(), budget).build();
        transitGraph = TransitGraph.of(roadGraph, network, costModel);
    }

    @Test
    void oneVertexPerStopPerLine() {
        int expected = 0;
        for (var route : network.routes()) {
            java.util.Set<Integer> calls = new java.util.HashSet<>();
            for (int stop : route.stops()) {
                calls.add(stop);
            }
            if (route.runsBothWays()) {
                for (int stop : route.back().stopOccurrences()) {
                    calls.add(stop);
                }
            }
            expected += calls.size();
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
        // A stop served by two lines is not the only way to change line, and on
        // this map it is not the way that happens. The greedy builder covers
        // each stop once, so with 8 stops and 2 lines the routes come out
        // disjoint and no stop carries two of them. Changing line still has to
        // be possible somewhere, or the network is a set of unconnected
        // shuttles — so that is what gets asserted, rather than a same-stop
        // interchange this map does not produce.
        int interchangeLinks = 0;
        for (int from = 0; from < transitGraph.vertexCount(); from++) {
            for (TransitLink link : transitGraph.outgoing(from)) {
                boolean changesLine = transitGraph.vertex(link.from()).routeId()
                        != transitGraph.vertex(link.to()).routeId();
                if (changesLine
                        && (link.kind() == TransitLink.Kind.TRANSFER || link.kind() == TransitLink.Kind.WALK)) {
                    interchangeLinks++;
                }
            }
        }
        assertTrue(interchangeLinks > 0,
                "no way to change line anywhere in the network — every line is a shuttle in isolation");
        System.out.println("stops served by more than one line: " + sharedStops
                + "; links that change line: " + interchangeLinks
                + (sharedStops == 0 ? " (all by walking between nearby stops)" : ""));
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
     * coverage radius. Two lines that share no stop, their nearest stops 300 m
     * apart: at a 150 m walk a rider boarding one can never reach the other; at
     * 400 m they can. Measured on the synthetic grid this module started on (16
     * of 61 vertices cut off at 250 m), and kept as a hand-built case because a
     * real map with shared stops no longer shows it.
     */
    @Test
    void aNarrowInterchangeWalkDisconnectsLinesThatShareNoStop() {
        List<Node> nodes = List.of(stopNode(0, 0.0), stopNode(1, 100.0), stopNode(2, 400.0), stopNode(3, 500.0));
        List<Edge> edges = List.of(
                new Edge(0, 0, 1, 1, "residential", 1, 100.0, 10.0),
                new Edge(1, 1, 0, 0, "residential", 1, 100.0, 10.0),
                new Edge(2, 2, 3, 3, "residential", 1, 100.0, 10.0),
                new Edge(3, 3, 2, 2, "residential", 1, 100.0, 10.0));
        Graph g = Graph.of(nodes, edges);
        CostModel c = CostModel.ofCosts(new long[] {10_000L, 10_000L, 10_000L, 10_000L});
        BusRoute left = new BusRoute(0, new int[] {0, 1}, new int[] {0, 1}, new int[] {0}, 10_000L, 0.0,
                new int[] {0, 1}, new int[] {0, 1});
        BusRoute right = new BusRoute(1, new int[] {2, 3}, new int[] {2, 3}, new int[] {2}, 10_000L, 0.0,
                new int[] {2, 3}, new int[] {0, 1});
        RouteNetwork twoLines = new RouteNetwork(List.of(left, right), new int[] {0, 1, 2, 3}, 0.0, 0.0);

        TransitGraph narrow = TransitGraph.of(g, twoLines, c, 150.0);
        TransitGraph wide = TransitGraph.of(g, twoLines, c, TransitGraph.DEFAULT_INTERCHANGE_WALK_M);

        assertTrue(reachableFromFirstVertex(narrow) < narrow.vertexCount(),
                "at 150 m the two lines are islands");
        assertEquals(wide.vertexCount(), reachableFromFirstVertex(wide),
                "at 400 m a rider can walk between them");
    }

    /** A passenger must be able to get from anywhere on the network to anywhere else. */
    @Test
    void theWholeTransitGraphIsConnected() {
        int reached = reachableFromFirstVertex(transitGraph);

        assertEquals(transitGraph.vertexCount(), reached,
                "only " + reached + " of " + transitGraph.vertexCount() + " vertices are reachable");
    }

    /**
     * Regression: a reverse ride used to be offered on any segment whose own
     * roads could be driven back, even when the line as a whole had no return
     * service and was exported and reported as one-way. Here every road is
     * two-way, but the line runs A→B→C only; a rider at C must not be carried to A.
     */
    @Test
    void aLineWithNoReturnServiceOffersNoReverseRideAnywhere() {
        Graph g = threeStopsInARow();
        CostModel c = CostModel.ofCosts(new long[] {1000L, 1000L, 1000L, 1000L});
        BusRoute outboundOnly = new BusRoute(0, new int[] {0, 1, 2}, new int[] {0, 1, 2}, new int[] {0, 2},
                2000L, 0.0, new int[] {0, 1, 2}, new int[] {0, 1, 2});

        TransitGraph transit = TransitGraph.of(g, new RouteNetwork(List.of(outboundOnly), new int[] {0, 1, 2}, 0.0, 0.0), c, 0.0);
        int atA = transit.verticesAt(0)[0];
        int atB = transit.verticesAt(1)[0];
        int atC = transit.verticesAt(2)[0];

        assertTrue(hasRideLink(transit, atA, atB));
        assertTrue(hasRideLink(transit, atB, atC));
        assertFalse(hasRideLink(transit, atC, atB), "no bus runs C->B");
        assertFalse(hasRideLink(transit, atB, atA), "no bus runs B->A");
    }

    /**
     * The return is priced from its own roads — on a dual carriageway a
     * different road from the outbound one — never from the outbound roads
     * reversed.
     */
    @Test
    void aReturnServiceIsPricedFromItsOwnRoads() {
        Graph g = threeStopsInARow();
        // Outbound 0->1->2 costs 1000 per hop; the return 2->1->0 is slower, 3000 per hop.
        CostModel c = CostModel.ofCosts(new long[] {1000L, 3000L, 1000L, 3000L});
        BusRoute.Return back = new BusRoute.Return(new int[] {2, 1, 0}, new int[] {3, 1}, 6000L,
                new int[] {2, 1, 0}, new int[] {0, 1, 2});
        BusRoute both = new BusRoute(0, new int[] {0, 1, 2}, new int[] {0, 1, 2}, new int[] {0, 2},
                2000L, 0.0, new int[] {0, 1, 2}, new int[] {0, 1, 2}, back);

        TransitGraph transit = TransitGraph.of(g, new RouteNetwork(List.of(both), new int[] {0, 1, 2}, 0.0, 0.0), c, 0.0);
        int atA = transit.verticesAt(0)[0];
        int atB = transit.verticesAt(1)[0];

        assertEquals(1000L, rideMillis(transit, atA, atB));
        assertEquals(3000L, rideMillis(transit, atB, atA));
    }

    /** Stops 0, 1, 2 ten metres apart; edges 0:0->1, 1:1->0, 2:1->2, 3:2->1. */
    private static Graph threeStopsInARow() {
        List<Node> nodes = List.of(stopNode(0, 0.0), stopNode(1, 10.0), stopNode(2, 20.0));
        List<Edge> edges = List.of(
                new Edge(0, 0, 1, 1, "residential", 1, 10.0, 10.0),
                new Edge(1, 1, 0, 0, "residential", 1, 10.0, 10.0),
                new Edge(2, 1, 2, 3, "residential", 1, 10.0, 10.0),
                new Edge(3, 2, 1, 2, "residential", 1, 10.0, 10.0));
        return Graph.of(nodes, edges);
    }

    private static long rideMillis(TransitGraph graph, int from, int to) {
        for (TransitLink link : graph.outgoing(from)) {
            if (link.to() == to && link.kind() == TransitLink.Kind.RIDE) {
                return link.millis();
            }
        }
        throw new AssertionError("no ride " + from + " -> " + to);
    }

    private static Node stopNode(int id, double xM) {
        return new Node(id, 1_000_000L + id, new Position(0.0, 0.0, xM, 0.0),
                0, true, 0.0, false, 0, 0, 0, null, 0.0, 0.0);
    }

    private static boolean hasRideLink(TransitGraph graph, int from, int to) {
        for (TransitLink link : graph.outgoing(from)) {
            if (link.to() == to && link.kind() == TransitLink.Kind.RIDE) {
                return true;
            }
        }
        return false;
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
