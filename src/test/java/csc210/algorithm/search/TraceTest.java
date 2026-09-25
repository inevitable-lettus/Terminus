package csc210.algorithm.search;

import csc210.algorithm.Fixtures;
import csc210.algorithm.journey.Itinerary;
import csc210.algorithm.journey.JourneyPlanner;
import csc210.algorithm.routing.BuildTrace;
import csc210.algorithm.routing.RouteNetwork;
import csc210.algorithm.routing.RouteNetworkBuilder;
import csc210.algorithm.transit.TransitGraph;
import csc210.algorithm.world.World;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The hooks that make the searches and the greedy builder watchable, and prove watching changes nothing. */
class TraceTest {

    private static final World WORLD = Fixtures.v2World1800();

    private static RouteNetwork build() {
        return new RouteNetworkBuilder(WORLD.graph(), WORLD.costModel(), WORLD.demandModel(), WORLD.budget()).build();
    }

    @Test
    void watchingTheBuilderChangesNothingAndRecordsEveryLineItAccepted() {
        BuildTrace trace = new BuildTrace();
        RouteNetwork watched = new RouteNetworkBuilder(WORLD.graph(), WORLD.costModel(), WORLD.demandModel(),
                WORLD.budget()).build(trace);
        RouteNetwork unwatched = build();

        assertEquals(unwatched.routeCount(), watched.routeCount());
        for (int i = 0; i < watched.routeCount(); i++) {
            assertArrayEquals(unwatched.routes().get(i).nodes(), watched.routes().get(i).nodes());
        }
        assertEquals(watched.routeCount(), trace.steps().stream().filter(s -> s.kind().equals("accept")).count());
        assertEquals("hub", trace.steps().get(0).kind(), "the construction starts by choosing a hub");
        for (BuildTrace.Step step : trace.steps()) {
            if (step.kind().equals("extend")) {
                assertTrue(step.path().length >= 2 && step.path()[step.path().length - 1] == step.stop());
            }
        }
    }

    @Test
    void theRecordedOrderIsTheSettleOrderAndBothSearchesFindTheSameCost() {
        TransitGraph transit = TransitGraph.of(WORLD.graph(), build(), WORLD.costModel());
        JourneyPlanner planner = JourneyPlanner.of(WORLD.graph(), transit);
        int checked = 0;
        for (int origin = 5; origin < WORLD.graph().nodeCount(); origin += 211) {
            for (int destination = 17; destination < WORLD.graph().nodeCount(); destination += 223) {
                ExpansionRecorder aimed = new ExpansionRecorder(100_000);
                ExpansionRecorder blind = new ExpansionRecorder(100_000);
                Itinerary a = planner.plan(origin, destination, true, aimed);
                Itinerary d = planner.plan(origin, destination, false, blind);
                assertEquals(a.found(), d.found());
                if (!a.found() || a.legs().stream().allMatch(l -> l.kind().name().equals("WALK_DIRECT"))) {
                    continue;
                }
                assertEquals(a.totalMillis(), d.totalMillis(), origin + " -> " + destination);
                assertEquals(a.verticesExpanded(), aimed.settledCount());
                assertEquals(d.verticesExpanded(), blind.settledCount());
                assertTrue(aimed.settledCount() <= blind.settledCount(), "A* never settles more than Dijkstra here");

                Set<Integer> distinct = new HashSet<>();
                long[] costs = blind.costs();
                for (int i = 0; i < costs.length; i++) {
                    assertTrue(distinct.add(blind.vertices()[i]), "a vertex is settled once");
                    if (i > 0) {
                        assertTrue(costs[i] >= costs[i - 1], "Dijkstra settles in cost order");
                    }
                }
                checked++;
            }
        }
        assertTrue(checked > 10, "only " + checked + " bus journeys in the sample");
    }

    @Test
    void theRecorderStopsAtItsCapButKeepsCounting() {
        ExpansionRecorder small = new ExpansionRecorder(3);
        for (int v = 0; v < 10; v++) {
            small.onSettle(v, v * 10L, 0L);
        }
        assertArrayEquals(new int[] {0, 1, 2}, small.vertices());
        assertEquals(10, small.settledCount());
        assertTrue(small.truncated());
        assertFalse(new ExpansionRecorder(5).truncated());
    }
}
