package csc210.algorithm.live;

import csc210.algorithm.journey.Itinerary;
import csc210.algorithm.journey.JourneyPlanner;
import csc210.algorithm.journey.StopAccess;
import csc210.algorithm.journey.StopAccessIndex;
import csc210.algorithm.publish.RoutePublisher;
import csc210.algorithm.routing.BusRoute;
import csc210.algorithm.routing.RouteNetwork;
import csc210.algorithm.routing.RouteNetworkBuilder;
import csc210.algorithm.transit.TransitGraph;
import csc210.algorithm.transit.TransitLink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phases B and D against a running simulator: door-to-door passenger journeys
 * on live data, publishing the generated lines back, and the ways a query is
 * allowed to fail.
 *
 * <p>Like the rest of the live suite these assert properties rather than exact
 * durations. A journey's cost depends on the road speeds at the instant it is
 * planned, and those are not the fixture's.
 */
@Tag("live")
class LiveJourneyTest {

    /** Everything from one live read, through to a planner ready to answer queries. */
    private record LiveWorld(LiveSandbox.World world, RouteNetwork network,
                             TransitGraph transit, JourneyPlanner planner) {}

    private static LiveWorld plannerAtDesignTime() throws IOException, InterruptedException {
        LiveSandbox.World world = LiveSandbox.at(LiveSandbox.DESIGN_TIME);
        RouteNetwork network = new RouteNetworkBuilder(world.graph(), world.costModel(),
                world.demandModel(), world.budget()).build();
        TransitGraph transit = TransitGraph.of(world.graph(), network, world.costModel());
        return new LiveWorld(world, network, transit, JourneyPlanner.of(world.graph(), transit));
    }

    @Test
    @DisplayName("a passenger query between two junctions is answered on live data")
    void doorToDoorOnLiveData() throws IOException, InterruptedException {
        LiveWorld live = plannerAtDesignTime();
        int lastJunction = live.world().graph().nodeCount() - 1;

        Itinerary itinerary = live.planner().plan(0, lastJunction);

        assertTrue(itinerary.found(), "junction 0 to " + lastJunction + " should be reachable on this network");
        assertFalse(itinerary.legs().isEmpty());

        long summed = itinerary.legs().stream().mapToLong(Itinerary.Leg::millis).sum();
        assertEquals(itinerary.totalMillis(), summed, "the legs must account for the whole journey");

        // Printed so a live run leaves evidence a person can read, not just a
        // green tick. The build order asks for the real output, not a summary.
        System.out.println("Live journey, junction 0 to " + lastJunction + ":");
        System.out.println(itinerary.describe());
    }

    @Test
    @DisplayName("every junction on the live map can reach a stop on foot")
    void liveAccessIndexCoversTheMap() throws IOException, InterruptedException {
        LiveWorld live = plannerAtDesignTime();
        StopAccessIndex index = StopAccessIndex.of(live.world().graph(), live.transit());

        for (int node = 0; node < live.world().graph().nodeCount(); node++) {
            java.util.List<StopAccess> near = index.near(node);
            assertFalse(near.isEmpty(), "junction " + node + " has no stop within " + index.radiusM() + " m");
            for (StopAccess stop : near) {
                assertTrue(stop.metres() <= index.radiusM());
                assertEquals(StopAccessIndex.walkMillis(stop.metres()), stop.walkMillis());
            }
        }
    }

    @Test
    @DisplayName("a door-to-door journey never costs more than walking, when walking is possible")
    void walkingIsAFloorOnLiveData() throws IOException, InterruptedException {
        LiveWorld live = plannerAtDesignTime();
        var graph = live.world().graph();
        double radius = live.transit().walkingRadiusM();

        for (int origin = 0; origin < graph.nodeCount(); origin += 11) {
            for (int destination = 0; destination < graph.nodeCount(); destination += 13) {
                if (origin == destination) {
                    continue;
                }
                double metres = TransitGraph.straightLineMetres(
                        graph.node(origin).position(), graph.node(destination).position());
                if (metres > radius) {
                    continue;
                }
                Itinerary itinerary = live.planner().plan(origin, destination);
                assertTrue(itinerary.found());
                assertTrue(itinerary.totalMillis() <= StopAccessIndex.walkMillis(metres),
                        origin + " -> " + destination + " came back worse than walking it");
            }
        }
    }

    @Test
    @DisplayName("the same junction twice is a free journey, not a search")
    void originEqualsDestinationOnLiveData() throws IOException, InterruptedException {
        LiveWorld live = plannerAtDesignTime();
        Itinerary itinerary = live.planner().plan(42, 42);
        assertTrue(itinerary.found());
        assertEquals(0L, itinerary.totalMillis());
        assertTrue(itinerary.legs().isEmpty());
    }

    @Test
    @DisplayName("the simulator accepts every generated line, in whichever directions it can be driven")
    void publishingTheNetworkIsAccepted() throws IOException, InterruptedException {
        LiveWorld live = plannerAtDesignTime();
        RoutePublisher publisher = new RoutePublisher(live.world().graph(), live.world().costModel());

        int published = publisher.publishAll(live.world().client(), live.network());

        // Not two per line any more. This map has one-way streets, and a line
        // that uses one cannot retrace its own path — DSTNS rejects the reverse
        // as "not directly adjacent in the road network", because the reverse
        // twin it publishes on /view/topology is a fabrication. Outbound is
        // always publishable; the return leg is only sometimes.
        int lines = live.network().routeCount();
        int oneWay = publisher.oneWayLines().size();
        assertEquals(2 * lines - oneWay, published,
                "every line publishes outbound, and back only where the road allows");
        assertTrue(published >= lines, "every line must at least publish outbound");
        System.out.println("Published " + published + " directed routes from " + lines
                + " lines; one-way lines: " + publisher.oneWayLines());
    }

    @Test
    @DisplayName("the server really does reject a route with a gap in it")
    void aGappedRouteIsRejectedByTheServer() throws IOException, InterruptedException {
        LiveWorld live = plannerAtDesignTime();
        BusRoute route = live.network().routes().get(0);
        int[] stopsOnly = route.stops();

        // This is the check that makes the full-traversal design necessary
        // rather than merely defensible. If the server accepted a stops-only
        // route, carrying every intermediate junction would be dead weight.
        assertTrue(stopsOnly.length >= 2);
        IOException rejected = assertThrows(IOException.class,
                () -> live.world().client().publishRoute("GAPPED", "gapped", stopsOnly),
                "a route listing only its stops must be rejected; if this passes, the "
                        + "server fills gaps after all and BusRoute.nodes() could be simplified");

        // It must be rejected for being discontinuous, not for some unrelated
        // reason. The first version of this test passed because bus_id was
        // being sent as a number, so the server never got as far as looking at
        // the nodes — green for the wrong reason is worse than red.
        assertFalse(rejected.getMessage().contains("INVALID_FIELD_TYPE"),
                "rejected on field types, so the gap was never actually tested: " + rejected.getMessage());
        assertTrue(rejected.getMessage().contains("adjacent")
                        || rejected.getMessage().contains("Discontinuous"),
                "expected a discontinuity complaint, got: " + rejected.getMessage());
    }

    @Test
    @DisplayName("a closed road is kept out of the journeys planned after it closes")
    void aClosedRoadChangesThePlannedJourney() throws IOException, InterruptedException {
        LiveWorld live = plannerAtDesignTime();
        int lastJunction = live.world().graph().nodeCount() - 1;

        Itinerary before = live.planner().plan(0, lastJunction);
        assertTrue(before.found());

        // Close a road the first line actually drives along, so the closure has
        // something to bite on. Closing one nothing uses would prove nothing.
        BusRoute route = live.network().routes().get(0);
        int closedEdge = route.edges()[route.edges().length / 2];

        try {
            live.world().client().setEdgeClosed(closedEdge, true);
            LiveSandbox.World after = LiveSandbox.reread(live.world().client(), live.world().topology());

            assertFalse(after.costModel().isTraversable(closedEdge),
                    "the simulator reported the road open again immediately after being told to close it");

            RouteNetwork rebuilt = new RouteNetworkBuilder(after.graph(), after.costModel(),
                    after.demandModel(), after.budget()).build();
            TransitGraph transit = TransitGraph.of(after.graph(), rebuilt, after.costModel());
            JourneyPlanner planner = JourneyPlanner.of(after.graph(), transit);
            RoutePublisher publisher = new RoutePublisher(after.graph());

            for (BusRoute rebuiltRoute : rebuilt.routes()) {
                for (int edge : rebuiltRoute.edges()) {
                    assertTrue(edge != closedEdge,
                            rebuiltRoute.label() + " still drives along the closed road " + closedEdge);
                }
                assertTrue(publisher.isContinuous(rebuiltRoute.nodes()),
                        rebuiltRoute.label() + " stopped being a continuous traversal after the closure");
            }

            Itinerary afterClosure = planner.plan(0, lastJunction);
            assertNotNull(afterClosure);
            if (afterClosure.found()) {
                for (Itinerary.Leg leg : afterClosure.legs()) {
                    assertTrue(leg.millis() >= 0);
                }
            }
        } finally {
            // Leave the simulator as it was found, pass or fail.
            live.world().client().setEdgeClosed(closedEdge, false);
        }
    }

    @Test
    @DisplayName("a passenger who will not walk cannot board, and is told so")
    void noWalkMeansNoJourneyOnLiveData() throws IOException, InterruptedException {
        LiveWorld live = plannerAtDesignTime();
        var graph = live.world().graph();

        int nonStop = -1;
        for (int node = 0; node < graph.nodeCount() && nonStop < 0; node++) {
            if (live.transit().verticesAt(node).length == 0) {
                nonStop = node;
            }
        }
        assertTrue(nonStop >= 0, "expected at least one junction with no stop on it");

        JourneyPlanner stranded = new JourneyPlanner(graph, live.transit(),
                StopAccessIndex.of(graph, live.transit(), 1.0), live.planner().distance());

        Itinerary itinerary = stranded.plan(nonStop, live.transit().servedStops()[0]);
        assertFalse(itinerary.found(), "there is no journey from a junction this passenger cannot leave");
        assertTrue(itinerary.legs().isEmpty());
    }

    @Test
    @DisplayName("the heuristic in use is the one the live map's geometry allows")
    void theHeuristicChoiceIsMadeFromTheLiveMap() throws IOException, InterruptedException {
        LiveWorld live = plannerAtDesignTime();
        // JourneyPlanner.of asks the map rather than assuming, and this asserts
        // that it asked — not what the answer happens to be today. The previous
        // version demanded Manhattan, which was right while DSTNS served an
        // axis-aligned lattice and wrong the morning it served real roads. The
        // portable claim is that the choice follows the geometry.
        var geometry = csc210.algorithm.search.TransitHeuristic.isManhattanAdmissibleOn(live.world().graph());
        assertEquals(geometry
                        ? csc210.algorithm.search.TransitHeuristic.Distance.MANHATTAN
                        : csc210.algorithm.search.TransitHeuristic.Distance.EUCLIDEAN,
                live.planner().distance(),
                "the planner must pick the measure this map's geometry permits");
        System.out.println("live map is axis-aligned: " + geometry
                + ", planner uses " + live.planner().distance());
    }

    @Test
    @DisplayName("an itinerary describes the same journey the search actually found")
    void itineraryMatchesTheSearchOnLiveData() throws IOException, InterruptedException {
        LiveWorld live = plannerAtDesignTime();
        var graph = live.world().graph();

        for (int destination = 5; destination < graph.nodeCount(); destination += 23) {
            Itinerary itinerary = live.planner().plan(0, destination);
            if (!itinerary.found() || itinerary.legs().isEmpty()) {
                continue;
            }
            long changes = itinerary.legs().stream()
                    .filter(leg -> leg.kind() == TransitLink.Kind.TRANSFER
                            || leg.kind() == TransitLink.Kind.WALK)
                    .count();
            assertEquals(itinerary.transfers(), changes);
            assertEquals(itinerary.totalMillis(),
                    itinerary.legs().stream().mapToLong(Itinerary.Leg::millis).sum());
        }
    }
}
