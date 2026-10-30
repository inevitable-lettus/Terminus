package csc210.algorithm.schedule;

import csc210.algorithm.Fixtures;
import csc210.algorithm.routing.BusRoute;
import csc210.algorithm.routing.RouteNetwork;
import csc210.algorithm.routing.RouteNetworkBuilder;
import csc210.algorithm.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimetableTest {

    private static RouteNetwork network;
    private static Timetable timetable;

    @BeforeAll
    static void build() {
        World world = Fixtures.v2World1800();
        network = new RouteNetworkBuilder(world.graph(), world.costModel(), world.demandModel(), world.budget()).build();
        timetable = Timetable.of(network);
    }

    @Test
    void sixtyFourDeparturesPerDirectionOfEveryLine() {
        int perDirection = (22 * 3600 - 6 * 3600) / (15 * 60);
        assertEquals(64, perDirection);
        int directions = 0;
        for (BusRoute route : network.routes()) {
            directions += route.runsBothWays() ? 2 : 1;
        }
        assertEquals(perDirection * directions, timetable.trips().size());
    }

    @Test
    void everyLineInTheCaptureRunsBothWays() {
        // The capture's 6 lines all have a return, so this is 6 x 2 x 64.
        assertTrue(network.routes().stream().allMatch(BusRoute::runsBothWays));
        assertEquals(network.routeCount() * 2 * 64, timetable.trips().size());
    }

    @Test
    void firstAndLastBusAndTheirIds() {
        List<Trip> trips = timetable.trips();
        Trip first = trips.get(0);
        assertEquals(new Trip("route_0-out-0600", 0, true, 6 * 3600, 40), first);
        Trip lastOutbound = trips.get(63);
        assertEquals("route_0-out-2145", lastOutbound.id());
        assertEquals("route_0-back-0600", trips.get(64).id());
        assertEquals("route_2-out-0615", Trip.idFor(2, true, 6 * 3600 + 15 * 60));
    }

    @Test
    void tripIdsAreUnique() {
        Set<String> seen = new HashSet<>();
        for (Trip trip : timetable.trips()) {
            assertTrue(seen.add(trip.id()), trip.id());
        }
    }
}
