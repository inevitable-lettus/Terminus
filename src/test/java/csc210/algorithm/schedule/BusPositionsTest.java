package csc210.algorithm.schedule;

import csc210.algorithm.Fixtures;
import csc210.algorithm.cost.CostModel;
import csc210.algorithm.graph.Graph;
import csc210.algorithm.routing.RouteNetwork;
import csc210.algorithm.routing.RouteNetworkBuilder;
import csc210.algorithm.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Item 3: a bus's position comes from its departure and the live travel times, nothing simulated. */
class BusPositionsTest {

    private static World world;
    private static RouteNetwork network;
    private static Timetable timetable;

    @BeforeAll
    static void build() {
        world = Fixtures.v2World1800();
        network = new RouteNetworkBuilder(world.graph(), world.costModel(), world.demandModel(), world.budget()).build();
        timetable = Timetable.of(network);
    }

    /** Every road at the same speed, 10 m/s, so time along the line is distance along it. */
    private static CostModel fixedSpeed(Graph graph) {
        long[] costs = new long[graph.edgeCount()];
        for (int e = 0; e < costs.length; e++) {
            costs[e] = CostModel.travelTimeMillis(graph.edge(e).lengthM(), 10.0);
        }
        return CostModel.ofCosts(costs);
    }

    @Test
    void atAFixedSpeedTheBusIsHalfwayAtHalfItsTravelTime() {
        Graph graph = world.graph();
        LiveTimes times = new LiveTimes(graph, fixedSpeed(graph));
        LiveTimes.Direction line = LiveTimes.direction(network.routes().get(0), true);
        long total = times.millisTo(line, line.edges().length);
        double lineMetres = 0.0;
        for (int edge : line.edges()) {
            lineMetres += graph.edge(edge).lengthM();
        }

        BusPositions.BusPosition bus = BusPositions.place(timetable.trips().get(0), line, total / 2, times, graph);

        assertNotNull(bus);
        double metres = graph.edge(line.edges()[bus.edgeIndex()]).lengthM() * bus.fraction();
        for (int e = 0; e < bus.edgeIndex(); e++) {
            metres += graph.edge(line.edges()[e]).lengthM();
        }
        assertEquals(lineMetres / 2, metres, 1.0, "within a metre of halfway (costs are whole milliseconds)");
    }

    @Test
    void aBusMovesForwardWhenTheClockAdvances() {
        int now = 7 * 3600 + 5 * 60;
        BusPositions.BusPosition earlier = find(BusPositions.at(now, timetable, network, world.graph(),
                world.costModel()), "route_0-out-0700");
        BusPositions.BusPosition later = find(BusPositions.at(now + 60, timetable, network, world.graph(),
                world.costModel()), "route_0-out-0700");
        assertTrue(later.edgeIndex() > earlier.edgeIndex()
                || (later.edgeIndex() == earlier.edgeIndex() && later.fraction() > earlier.fraction()));
    }

    @Test
    void onlyBusesThatHaveLeftAndNotFinishedAreOnTheRoad() {
        int now = 7 * 3600;
        List<BusPositions.BusPosition> buses = BusPositions.at(now, timetable, network, world.graph(),
                world.costModel());
        LiveTimes times = new LiveTimes(world.graph(), world.costModel());
        for (BusPositions.BusPosition bus : buses) {
            Trip trip = bus.trip();
            LiveTimes.Direction line = LiveTimes.direction(network.routes().get(trip.routeId()), trip.outbound());
            assertTrue(trip.departSimS() <= now);
            assertTrue((now - trip.departSimS()) * 1000L < times.millisTo(line, line.edges().length));
        }
        assertTrue(buses.stream().anyMatch(b -> b.trip().id().equals("route_0-out-0700")), "the 07:00 is just leaving");
        assertTrue(buses.stream().noneMatch(b -> b.trip().id().equals("route_0-out-0715")), "the 07:15 has not left");
        assertTrue(BusPositions.at(5 * 3600, timetable, network, world.graph(), world.costModel()).isEmpty());
    }

    @Test
    void theNextStopIsAhead() {
        for (BusPositions.BusPosition bus : BusPositions.at(18 * 3600, timetable, network, world.graph(),
                world.costModel())) {
            LiveTimes.Direction line = LiveTimes.direction(network.routes().get(bus.trip().routeId()),
                    bus.trip().outbound());
            int ahead = -1;
            for (int i = 0; i < line.stopOccurrences().length; i++) {
                if (line.stopOccurrencePositions()[i] > bus.edgeIndex()) {
                    ahead = line.stopOccurrences()[i];
                    break;
                }
            }
            assertEquals(ahead, bus.nextStop());
        }
    }

    @Test
    void aFinishedBusIsNotPlaced() {
        LiveTimes times = new LiveTimes(world.graph(), world.costModel());
        LiveTimes.Direction line = LiveTimes.direction(network.routes().get(0), true);
        long total = times.millisTo(line, line.edges().length);
        assertNull(BusPositions.place(timetable.trips().get(0), line, total, times, world.graph()));
    }

    private static BusPositions.BusPosition find(List<BusPositions.BusPosition> buses, String tripId) {
        return buses.stream().filter(b -> b.trip().id().equals(tripId)).findFirst()
                .orElseThrow(() -> new AssertionError(tripId + " is not on the road"));
    }
}
