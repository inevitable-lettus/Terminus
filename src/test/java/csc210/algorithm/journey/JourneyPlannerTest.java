package csc210.algorithm.journey;

import csc210.algorithm.Fixtures;
import csc210.algorithm.client.SnapshotData;
import csc210.algorithm.client.TopologyData;
import csc210.algorithm.cost.CostModel;
import csc210.algorithm.demand.DemandModel;
import csc210.algorithm.graph.Graph;
import csc210.algorithm.routing.RouteBudget;
import csc210.algorithm.routing.RouteNetwork;
import csc210.algorithm.routing.RouteNetworkBuilder;
import csc210.algorithm.search.AStar;
import csc210.algorithm.search.TransitHeuristic;
import csc210.algorithm.search.TransitPath;
import csc210.algorithm.transit.TransitGraph;
import csc210.algorithm.transit.TransitLink;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase A. A passenger query posed between two ordinary road junctions, which
 * is the only form of the question a rider would ever ask.
 *
 * <p>Three things are under test and they are worth separating: the stop
 * lookup (A.1) is pure geometry, the endpoint splicing (A.2) must leave A*'s
 * optimality guarantee intact, and the itinerary (A.3) must describe the same
 * journey the search actually found rather than a prettier one.
 */
class JourneyPlannerTest {

    private static Graph roadGraph;
    private static TransitGraph transitGraph;
    private static StopAccessIndex accessIndex;
    private static JourneyPlanner planner;

    @BeforeAll
    static void build() {
        TopologyData topology = Fixtures.topology();
        SnapshotData snapshot = Fixtures.snapshotAt1800();
        roadGraph = Graph.of(topology.nodes(), topology.edges());
        CostModel costModel = CostModel.of(roadGraph, snapshot, Fixtures.oneWayEdgeIdsAt1800());
        DemandModel demandModel = DemandModel.of(roadGraph, snapshot);
        RouteNetwork network = new RouteNetworkBuilder(roadGraph, costModel, demandModel,
                RouteBudget.defaults()).build();
        transitGraph = TransitGraph.of(roadGraph, network, costModel);
        accessIndex = StopAccessIndex.of(roadGraph, transitGraph);
        planner = JourneyPlanner.of(roadGraph, transitGraph);
    }

    // ---- A.1, the stop access index -------------------------------------

    @Test
    void everyJunctionCanReachSomeStopOnFoot() {
        for (int node = 0; node < roadGraph.nodeCount(); node++) {
            assertFalse(accessIndex.near(node).isEmpty(),
                    "junction " + node + " has no served stop within " + accessIndex.radiusM() + " m, "
                            + "so a passenger starting there could never board anything");
        }
    }

    @Test
    void walkingTimeIsDistanceOverWalkingSpeed() {
        // 180 m is this map's grid pitch, so it is the shortest walk between
        // two distinct junctions: 180 / 1.4 = 128.57 s.
        assertEquals(128_571L, StopAccessIndex.walkMillis(180.0));
        assertEquals(0L, StopAccessIndex.walkMillis(0.0));

        for (int node = 0; node < roadGraph.nodeCount(); node++) {
            for (StopAccess stop : accessIndex.near(node)) {
                assertEquals(StopAccessIndex.walkMillis(stop.metres()), stop.walkMillis());
            }
        }
    }

    @Test
    void onlyStopsInsideTheRadiusComeBackAndTheyComeBackNearestFirst() {
        for (int node = 0; node < roadGraph.nodeCount(); node++) {
            List<StopAccess> near = accessIndex.near(node);
            long previous = -1L;
            for (StopAccess stop : near) {
                assertTrue(stop.metres() <= accessIndex.radiusM(),
                        "stop " + stop.stopNode() + " is " + stop.metres() + " m from junction " + node
                                + ", beyond the " + accessIndex.radiusM() + " m radius");
                assertTrue(stop.walkMillis() >= previous, "stops must come back nearest first");
                previous = stop.walkMillis();
            }
        }
    }

    @Test
    void aStopSittingOnTheJunctionIsAZeroLengthWalk() {
        int stop = transitGraph.servedStops()[0];
        StopAccess first = accessIndex.near(stop).get(0);
        assertEquals(stop, first.stopNode());
        assertEquals(0L, first.walkMillis(), "a passenger already at a stop still boards there, at no walking cost");
    }

    // ---- A.2, the spliced endpoints -------------------------------------

    @Test
    void theHeuristicNeverOverestimatesWithTheEndpointsPresent() {
        int origin = 0;
        int destination = roadGraph.nodeCount() - 1;
        TransitGraph.Extended extended = extend(origin, destination);
        TransitGraph graph = extended.graph();
        int target = extended.targetVertex();

        TransitHeuristic heuristic = TransitHeuristic.towards(graph, target, planner.distance(), roadGraph);
        AStar search = new AStar(graph);

        for (int vertex = 0; vertex < graph.vertexCount(); vertex++) {
            if (vertex == target) {
                continue;
            }
            // Dijkstra's answer is the true remaining cost, computed exactly.
            TransitPath truth = search.find(vertex, target, AStar.zeroHeuristic());
            if (!truth.found()) {
                continue;
            }
            assertTrue(heuristic.estimateMillis(vertex) <= truth.totalMillis(),
                    "h(" + vertex + ") = " + heuristic.estimateMillis(vertex)
                            + " exceeds the true remaining cost " + truth.totalMillis()
                            + ", which would cost A* its optimality guarantee");
        }
    }

    @Test
    void splicingEndpointsLeavesTheOriginalGraphAlone() {
        int vertices = transitGraph.vertexCount();
        int links = transitGraph.linkCount();

        TransitGraph.Extended extended = extend(0, roadGraph.nodeCount() - 1);

        assertEquals(vertices, transitGraph.vertexCount(), "the query must not mutate the shared graph");
        assertEquals(links, transitGraph.linkCount());
        assertEquals(vertices + 2, extended.graph().vertexCount());
        assertEquals(vertices, extended.sourceVertex());
        assertEquals(vertices + 1, extended.targetVertex());
        assertEquals(0, extended.graph().outgoing(extended.targetVertex()).length,
                "the target is where a journey ends; nothing leaves it");
    }

    @Test
    void aJourneyBetweenTwoStopsCostsTheSameAsTheStopToStopQueryPlusNoWalking() {
        int[] stops = transitGraph.servedStops();
        int originStop = stops[0];
        int destinationStop = stops[stops.length - 1];

        Itinerary itinerary = planner.plan(originStop, destinationStop);
        assertTrue(itinerary.found());

        // The same question asked the old way: cheapest over every (stop, line)
        // vertex at one end to every one at the other.
        AStar search = new AStar(transitGraph);
        long best = Long.MAX_VALUE;
        for (int from : transitGraph.verticesAt(originStop)) {
            for (int to : transitGraph.verticesAt(destinationStop)) {
                TransitPath path = search.find(from, to, AStar.zeroHeuristic());
                if (path.found()) {
                    best = Math.min(best, path.totalMillis());
                }
            }
        }
        assertEquals(best, itinerary.totalMillis(),
                "both endpoints are stops, so the access and egress walks are free and the "
                        + "door-to-door cost must equal the stop-to-stop cost");
    }

    @Test
    void aShortHopIsNeverSoldAsAWorseBusJourney() {
        // Whenever the destination is inside walking distance, the answer must
        // not cost more than simply walking there. Note it is often *less*: this
        // module does not model the wait for the first bus, so a one-block ride
        // can legitimately beat a one-block walk. That is a stated simplification
        // in TransitGraph, and this is where it shows up.
        for (int origin = 0; origin < roadGraph.nodeCount(); origin += 5) {
            for (int destination = 0; destination < roadGraph.nodeCount(); destination += 5) {
                if (origin == destination) {
                    continue;
                }
                double metres = TransitGraph.straightLineMetres(
                        roadGraph.node(origin).position(), roadGraph.node(destination).position());
                if (metres > accessIndex.radiusM()) {
                    continue;
                }
                Itinerary itinerary = planner.plan(origin, destination);
                assertTrue(itinerary.found());
                assertTrue(itinerary.totalMillis() <= StopAccessIndex.walkMillis(metres),
                        "junction " + origin + " -> " + destination + " is " + metres
                                + " m apart, so nothing worse than walking should ever be returned");
            }
        }
    }

    @Test
    void walkingWinsWhenNoUsableBusIsInReach() {
        // On a network cut down to one line, most of the map has no boardable
        // stop nearby. Two junctions close to each other and far from that line
        // can only be joined on foot, and that is the answer that must come back.
        TransitGraph isolated = oneLineOnly();
        JourneyPlanner narrow = JourneyPlanner.of(roadGraph, isolated);

        Itinerary walked = null;
        for (int origin = 0; origin < roadGraph.nodeCount() && walked == null; origin++) {
            for (int destination = 0; destination < roadGraph.nodeCount(); destination++) {
                if (origin == destination) {
                    continue;
                }
                double metres = TransitGraph.straightLineMetres(
                        roadGraph.node(origin).position(), roadGraph.node(destination).position());
                if (metres > isolated.walkingRadiusM()) {
                    continue;
                }
                Itinerary itinerary = narrow.plan(origin, destination);
                if (itinerary.found() && itinerary.legs().size() == 1
                        && itinerary.legs().get(0).kind() == TransitLink.Kind.WALK_DIRECT) {
                    walked = itinerary;
                    break;
                }
            }
        }
        assertNotNull(walked, "expected at least one pair of junctions this one-line network cannot serve");
        assertEquals(0, walked.transfers());
    }

    @Test
    void askingForTheJunctionYouAreStandingOnIsFree() {
        Itinerary itinerary = planner.plan(7, 7);
        assertTrue(itinerary.found());
        assertEquals(0L, itinerary.totalMillis());
        assertTrue(itinerary.legs().isEmpty());
    }

    @Test
    void anUnreachableDestinationIsReportedRatherThanThrown() {
        // A passenger who will not walk any distance at all, starting somewhere
        // no line calls. There is no journey, and "there is no journey" has to
        // come back as an answer rather than an exception or a wrong route.
        StopAccessIndex willNotWalk = StopAccessIndex.of(roadGraph, transitGraph, 1.0);
        JourneyPlanner stranded = new JourneyPlanner(roadGraph, transitGraph, willNotWalk, planner.distance());

        int nonStop = firstJunctionThatIsNotAStop();
        int servedStop = transitGraph.servedStops()[0];

        Itinerary itinerary = stranded.plan(nonStop, servedStop);
        assertFalse(itinerary.found(), "junction " + nonStop + " has no stop on it and this passenger will not walk");
        assertTrue(itinerary.legs().isEmpty());
        assertEquals(Long.MAX_VALUE, itinerary.totalMillis());
    }

    @Test
    void aDestinationNoLineReachesIsReportedRatherThanThrown() {
        // The same answer from the other direction: the origin is fine, the
        // destination is the one nothing serves.
        StopAccessIndex willNotWalk = StopAccessIndex.of(roadGraph, transitGraph, 1.0);
        JourneyPlanner stranded = new JourneyPlanner(roadGraph, transitGraph, willNotWalk, planner.distance());

        Itinerary itinerary = stranded.plan(transitGraph.servedStops()[0], firstJunctionThatIsNotAStop());
        assertFalse(itinerary.found());
        assertTrue(itinerary.legs().isEmpty());
    }

    // ---- A.3, the itinerary ---------------------------------------------

    @Test
    void legDurationsSumToTheJourneyCost() {
        for (int origin = 0; origin < roadGraph.nodeCount(); origin += 7) {
            for (int destination = 3; destination < roadGraph.nodeCount(); destination += 11) {
                Itinerary itinerary = planner.plan(origin, destination);
                if (!itinerary.found() || itinerary.legs().isEmpty()) {
                    continue;
                }
                long summed = itinerary.legs().stream().mapToLong(Itinerary.Leg::millis).sum();
                assertEquals(itinerary.totalMillis(), summed,
                        "the parts of the journey " + origin + " -> " + destination
                                + " must add up to the whole");
            }
        }
    }

    @Test
    void consecutiveStopsOnOneLineCollapseIntoASingleRideLeg() {
        int found = 0;
        for (int origin = 0; origin < roadGraph.nodeCount() && found < 5; origin++) {
            for (int destination = 0; destination < roadGraph.nodeCount() && found < 5; destination++) {
                Itinerary itinerary = planner.plan(origin, destination);
                if (!itinerary.found() || itinerary.transfers() != 0) {
                    continue;
                }
                List<Itinerary.Leg> rides = itinerary.legs().stream()
                        .filter(leg -> leg.kind() == TransitLink.Kind.RIDE)
                        .toList();
                if (rides.isEmpty()) {
                    continue;
                }
                assertEquals(1, rides.size(),
                        "a journey with no change of line is one ride, however many stops long");
                assertTrue(rides.get(0).stopsRidden() >= 1);
                found++;
            }
        }
        assertTrue(found > 0, "expected at least one single-line journey on this network");
    }

    @Test
    void theLegCountAgreesWithTheTransferCount() {
        for (int origin = 0; origin < roadGraph.nodeCount(); origin += 13) {
            for (int destination = 5; destination < roadGraph.nodeCount(); destination += 17) {
                Itinerary itinerary = planner.plan(origin, destination);
                if (!itinerary.found()) {
                    continue;
                }
                long changes = itinerary.legs().stream()
                        .filter(leg -> leg.kind() == TransitLink.Kind.TRANSFER
                                || leg.kind() == TransitLink.Kind.WALK)
                        .count();
                assertEquals(itinerary.transfers(), changes,
                        "every change of line must show up as its own leg");
            }
        }
    }

    @Test
    void anItineraryReadsAsInstructions() {
        Itinerary itinerary = planner.plan(0, roadGraph.nodeCount() - 1);
        assertTrue(itinerary.found());
        String text = itinerary.describe();
        assertTrue(text.contains("Total "), text);
        assertNotEquals("No journey found.", text);
    }

    // ---- helpers ---------------------------------------------------------

    private static TransitGraph.Extended extend(int origin, int destination) {
        long[] board = new long[transitGraph.vertexCount()];
        long[] alight = new long[transitGraph.vertexCount()];
        java.util.Arrays.fill(board, -1L);
        java.util.Arrays.fill(alight, -1L);
        for (StopAccess stop : accessIndex.near(origin)) {
            for (int vertex : transitGraph.verticesAt(stop.stopNode())) {
                board[vertex] = stop.walkMillis();
            }
        }
        for (StopAccess stop : accessIndex.near(destination)) {
            for (int vertex : transitGraph.verticesAt(stop.stopNode())) {
                alight[vertex] = stop.walkMillis();
            }
        }
        return transitGraph.withEndpoints(
                origin, roadGraph.node(origin).position(), board,
                destination, roadGraph.node(destination).position(), alight);
    }

    /** A junction with no served stop sitting on it. */
    private static int firstJunctionThatIsNotAStop() {
        for (int node = 0; node < roadGraph.nodeCount(); node++) {
            if (transitGraph.verticesAt(node).length == 0) {
                return node;
            }
        }
        throw new IllegalStateException("every junction on this map carries a stop; the test needs rethinking");
    }

    /** A network cut down to its first line, so most of the map is genuinely unserved. */
    private static TransitGraph oneLineOnly() {
        TopologyData topology = Fixtures.topology();
        SnapshotData snapshot = Fixtures.snapshotAt1800();
        Graph graph = Graph.of(topology.nodes(), topology.edges());
        CostModel costModel = CostModel.of(graph, snapshot);
        DemandModel demandModel = DemandModel.of(graph, snapshot);
        RouteNetwork full = new RouteNetworkBuilder(graph, costModel, demandModel,
                RouteBudget.defaults()).build();
        RouteNetwork oneLine = new RouteNetwork(List.of(full.routes().get(0)),
                full.coveredStops(), full.demandCovered(), full.totalDemand());
        return TransitGraph.of(graph, oneLine, costModel);
    }

    // ---- quality of life, on the DSTNS 2.0 city ----------------------------

    private static JourneyPlanner v2Planner() {
        csc210.algorithm.world.World world = Fixtures.v2World1800();
        RouteNetwork network = new RouteNetworkBuilder(world.graph(), world.costModel(), world.demandModel(),
                world.budget()).build();
        return JourneyPlanner.of(world.graph(), TransitGraph.of(world.graph(), network, world.costModel()));
    }

    /**
     * Regression: walking the whole way used to be considered only within 400 m,
     * so beyond that a slow bus journey was offered even when walking was quicker
     * — junction 66 to 207 on the old map came back as 21.5 minutes by bus against
     * about 11 on foot. A bus answer must never cost more than walking straight
     * there, at any distance.
     */
    @Test
    void noJourneyIsEverSlowerThanWalkingStraightThereAtAnyDistance() {
        csc210.algorithm.world.World world = Fixtures.v2World1800();
        JourneyPlanner v2 = v2Planner();
        int beyondTheRadius = 0;
        for (int origin = 0; origin < world.graph().nodeCount(); origin += 97) {
            for (int destination = 0; destination < world.graph().nodeCount(); destination += 89) {
                Itinerary answer = v2.plan(origin, destination);
                if (!answer.found() || origin == destination) {
                    continue;
                }
                double metres = TransitGraph.straightLineMetres(world.graph().node(origin).position(),
                        world.graph().node(destination).position());
                if (metres > 400.0) {
                    beyondTheRadius++;
                }
                assertTrue(answer.totalMillis() <= StopAccessIndex.walkMillis(metres),
                        origin + " -> " + destination + ": " + answer.totalMinutes() + " min against a "
                                + StopAccessIndex.walkMillis(metres) / 60_000.0 + " min walk");
            }
        }
        assertTrue(beyondTheRadius > 100, "the sample must reach well past the walking radius");
    }

    @Test
    void aBoardingWaitIsItsOwnLegAndAddsExactlyItsLengthToTheJourney() {
        csc210.algorithm.world.World world = Fixtures.v2World1800();
        JourneyPlanner v2 = v2Planner();
        for (int origin = 0; origin < world.graph().nodeCount(); origin += 131) {
            for (int destination = 1; destination < world.graph().nodeCount(); destination += 127) {
                Itinerary noWait = v2.plan(origin, destination);
                if (!noWait.found() || noWait.legs().stream().noneMatch(l -> l.kind() == TransitLink.Kind.RIDE)) {
                    continue;
                }
                Itinerary withWait = v2.withBoardWait(60_000L).plan(origin, destination);
                if (withWait.legs().stream().noneMatch(l -> l.kind() == TransitLink.Kind.RIDE)) {
                    continue; // a minute's wait tipped it over to walking, which is allowed
                }
                assertEquals(1, withWait.legs().stream().filter(l -> l.kind() == TransitLink.Kind.WAIT).count());
                assertTrue(withWait.totalMillis() <= noWait.totalMillis() + 60_000L);
                assertEquals(withWait.totalMillis(), withWait.legs().stream().mapToLong(Itinerary.Leg::millis).sum(),
                        "the legs must still account for the whole journey");
                assertTrue(withWait.describe().contains("for the first bus"), withWait.describe());
                return;
            }
        }
        throw new AssertionError("no bus journey found to test the wait on");
    }

    @Test
    void anImpossiblyLongWaitMakesWalkingTheAnswerWheneverABusWasUsed() {
        csc210.algorithm.world.World world = Fixtures.v2World1800();
        JourneyPlanner patient = v2Planner().withBoardWait(24 * 3_600_000L);
        Itinerary answer = patient.plan(0, world.graph().nodeCount() - 1);
        if (answer.found()) {
            assertTrue(answer.legs().stream().noneMatch(l -> l.kind() == TransitLink.Kind.RIDE),
                    "nobody waits a day for a bus they could walk past: " + answer.describe());
        }
    }
}
