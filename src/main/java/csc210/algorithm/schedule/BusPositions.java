package csc210.algorithm.schedule;

import csc210.algorithm.cost.CostModel;
import csc210.algorithm.graph.Graph;
import csc210.algorithm.model.Position;
import csc210.algorithm.routing.RouteNetwork;

import java.util.ArrayList;
import java.util.List;

/**
 * Where each bus is right now, worked out from the timetable and the live
 * speeds; nothing is simulated. A bus that left {@code t} seconds ago has
 * driven its line for {@code t} seconds: walk the line's roads, adding up their
 * live travel times, until the total passes {@code t}. The bus is on that road,
 * placed in proportion to how much of the road's time it has used.
 */
public final class BusPositions {

    /**
     * One bus on the road.
     *
     * @param edgeIndex which road of its line it is on, counted from the first stop
     * @param fraction how far along that road, 0 to 1
     * @param nextStop the next stop it calls at (a junction id)
     */
    public record BusPosition(Trip trip, int edgeIndex, double fraction, double lat, double lng, int nextStop) {}

    private BusPositions() {}

    /** Every trip that has left and not yet reached its last stop at {@code nowS}. */
    public static List<BusPosition> at(int nowS, Timetable timetable, RouteNetwork network, Graph graph,
                                       CostModel costs) {
        LiveTimes times = new LiveTimes(graph, costs);
        List<BusPosition> buses = new ArrayList<>();
        for (Trip trip : timetable.trips()) {
            if (trip.departSimS() > nowS) {
                continue;
            }
            LiveTimes.Direction direction = LiveTimes.direction(network.routes().get(trip.routeId()), trip.outbound());
            BusPosition bus = place(trip, direction, (nowS - trip.departSimS()) * 1000L, times, graph);
            if (bus != null) {
                buses.add(bus);
            }
        }
        return buses;
    }

    /** The bus {@code travelledMs} into its run, or {@code null} if it has finished. */
    static BusPosition place(Trip trip, LiveTimes.Direction direction, long travelledMs, LiveTimes times,
                             Graph graph) {
        long before = 0L;
        for (int e = 0; e < direction.edges().length; e++) {
            long road = times.roadMillis(direction, e);
            if (before + road > travelledMs) {
                double fraction = road == 0 ? 0.0 : (double) (travelledMs - before) / road;
                Position from = graph.node(direction.nodes()[e]).position();
                Position to = graph.node(direction.nodes()[e + 1]).position();
                double lat = from.lat() + (to.lat() - from.lat()) * fraction;
                double lng = from.lon() + (to.lon() - from.lon()) * fraction;
                return new BusPosition(trip, e, fraction, lat, lng, nextStop(direction, e));
            }
            before += road;
        }
        return null;
    }

    /** The first stop call after road {@code edgeIndex}, i.e. at junction {@code edgeIndex + 1} or later. */
    private static int nextStop(LiveTimes.Direction direction, int edgeIndex) {
        int[] positions = direction.stopOccurrencePositions();
        for (int i = 0; i < positions.length; i++) {
            if (positions[i] > edgeIndex) {
                return direction.stopOccurrences()[i];
            }
        }
        return direction.stopOccurrences()[positions.length - 1];
    }
}
