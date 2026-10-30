package csc210.algorithm.schedule;

import csc210.algorithm.routing.BusRoute;
import csc210.algorithm.routing.RouteNetwork;

import java.util.ArrayList;
import java.util.List;

/**
 * A fixed-headway timetable: on every line, in each direction it runs, a bus
 * leaves the first stop every 15 simulated minutes from 06:00, the last at
 * 21:45. Every bus has 40 seats. Built from the network alone, so it stays the
 * same for as long as the network does.
 */
public final class Timetable {

    public static final int FIRST_DEPARTURE_S = 6 * 3600;
    /** No bus leaves at or after this time. */
    public static final int SERVICE_END_S = 22 * 3600;
    public static final int HEADWAY_S = 15 * 60;
    public static final int SEATS = 40;

    private final List<Trip> trips;

    private Timetable(List<Trip> trips) {
        this.trips = trips;
    }

    public static Timetable of(RouteNetwork network) {
        List<Trip> trips = new ArrayList<>();
        for (BusRoute route : network.routes()) {
            addDepartures(trips, route.id(), true);
            if (route.runsBothWays()) {
                addDepartures(trips, route.id(), false);
            }
        }
        return new Timetable(List.copyOf(trips));
    }

    private static void addDepartures(List<Trip> trips, int routeId, boolean outbound) {
        for (int depart = FIRST_DEPARTURE_S; depart < SERVICE_END_S; depart += HEADWAY_S) {
            trips.add(new Trip(Trip.idFor(routeId, outbound, depart), routeId, outbound, depart, SEATS));
        }
    }

    /** Every trip, line by line, outbound before return, in departure order. */
    public List<Trip> trips() {
        return trips;
    }
}
