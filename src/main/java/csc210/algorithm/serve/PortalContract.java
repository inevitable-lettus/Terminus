package csc210.algorithm.serve;

import csc210.algorithm.cli.UsageException;
import csc210.algorithm.graph.Graph;
import csc210.algorithm.model.Node;
import csc210.algorithm.routing.BusRoute;
import csc210.algorithm.schedule.LiveTimes;
import csc210.algorithm.schedule.SimTime;
import csc210.algorithm.schedule.Trip;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The ticketing portal's frozen contract, {@code POST /api/v1/routes/query},
 * answered from a {@link PlannedCity}. The shapes and names are the portal
 * team's (camelCase, a {@code data} wrapper), not this module's.
 *
 * <p><strong>Ids.</strong> A stop is {@code stop_<junction>}, the junction being
 * the stop's merged representative — the ids {@code GET /api/v1/stops} lists,
 * for the portal to map onto its own {@code stop} table. A line is
 * {@code route_<n>}, printed elsewhere as {@code R<n>}. Any junction the
 * simulator flagged as a stop is accepted and resolved to its representative.
 *
 * <p><strong>Times and buses.</strong> {@code departureTime} is simulated time,
 * seconds of the day or {@code HH:MM:SS}, defaulting to the simulator's current
 * time (a real-world date-time, the contract's first form, also means "now"). Each ride names the first timetabled trip ({@code tripId}, and
 * {@code busId}, the same id) that reaches the boarding stop at or after the
 * passenger does. {@code cost} is in minutes, since the contract names no unit
 * and minutes are what every other figure here is printed in.
 *
 * <p>Besides the journey query it serves what the portal copies once per run
 * ({@code /api/v1/routes}, {@code /api/v1/trips}) and polls every 2 seconds
 * ({@code /api/v1/live}).
 */
public final class PortalContract {

    private PortalContract() {}

    public record Query(String sourceStopId, String destStopId, String departureTime) {}

    /**
     * One leg between stops. A ride has a {@code routeId}; a walk between two
     * stops to change line has {@code routeId} {@code null}.
     */
    public record Segment(String fromStopId, String toStopId, String routeId, String busId, String tripId,
                          double cost, double distanceMeters) {}

    public record Route(List<String> path, List<Segment> segments, double totalCost, double totalDistanceMeters,
                        long estimatedDurationSeconds) {}

    public record Answer(Route data) {}

    public record StopInfo(String stopId, String name, int node, double lat, double lng, boolean served,
                           List<String> routeIds) {}

    public record Stops(List<StopInfo> data) {}

    public record LiveBus(String tripId, String routeId, double lat, double lng, String nextStopId) {}

    public record LiveData(String runId, Integer simTime, List<LiveBus> buses) {}

    public record Live(LiveData data) {}

    public record RouteInfo(String routeId, List<String> stops, List<String> returnStops) {}

    public record Routes(List<RouteInfo> data) {}

    public record StopTime(String stopId, int etaSimS) {}

    public record TripInfo(String tripId, String routeId, boolean outbound, int departSimS, int seats,
                           List<StopTime> stopTimes) {}

    public record Trips(List<TripInfo> data) {}

    /** A stop id that names no stop on this map: 404. */
    public static final class UnknownStop extends RuntimeException {
        UnknownStop(String message) {
            super(message);
        }
    }

    /** Both stops exist, and nothing joins them: 422. */
    public static final class NoRoute extends RuntimeException {
        NoRoute(String message) {
            super(message);
        }
    }

    /** Every stop after merging, with the lines calling there. */
    public static Stops stops(PlannedCity city) {
        Graph graph = city.world().graph();
        Map<Integer, List<String>> linesAt = new TreeMap<>();
        for (Views.Line line : city.networkView().lines()) {
            List<Integer> calls = new ArrayList<>(line.outbound().stops());
            if (line.back() != null) {
                calls.addAll(line.back().stops());
            }
            for (int stop : calls) {
                List<String> here = linesAt.computeIfAbsent(stop, ignored -> new ArrayList<>());
                String id = "route_" + line.id();
                if (!here.contains(id)) {
                    here.add(id);
                }
            }
        }
        // DSTNS names a stop through the junction it is anchored to; merged stops take the first name found.
        Map<Integer, String> names = new TreeMap<>();
        for (Map.Entry<Integer, String> place : city.stopNames().entrySet()) {
            int anchor = place.getKey();
            int representative = anchor >= 0 && anchor < graph.nodeCount()
                    ? city.world().stops().representativeOf(anchor) : -1;
            if (representative >= 0) {
                names.putIfAbsent(representative, place.getValue());
            }
        }
        List<StopInfo> all = new ArrayList<>();
        for (int stop : city.world().stops().representatives()) {
            Node node = graph.node(stop);
            List<String> lines = linesAt.getOrDefault(stop, List.of());
            all.add(new StopInfo("stop_" + stop, names.get(stop), stop, node.position().lat(), node.position().lon(),
                    !lines.isEmpty(), lines));
        }
        return new Stops(all);
    }

    /**
     * Answers one query.
     *
     * @throws UsageException if a stop id is missing or not of the form {@code stop_<n>}
     * @throws UnknownStop if a stop id names no stop
     * @throws NoRoute if no journey joins them
     */
    public static Answer answer(PlannedCity city, Query query) {
        if (query == null) {
            throw new UsageException("the body must be JSON with sourceStopId and destStopId");
        }
        int from = resolve(city, query.sourceStopId(), "sourceStopId");
        int to = resolve(city, query.destStopId(), "destStopId");

        Integer departS = query.departureTime() == null ? currentSimTime(city) : departure(city, query.departureTime());

        Views.Journey journey = city.journeyView(from, to, 0L);
        if (!journey.found()) {
            throw new NoRoute("no journey joins stop_" + from + " to stop_" + to + " on this network");
        }
        LiveTimes times = new LiveTimes(city.world().graph(), city.world().costModel());
        // Where the passenger is in simulated time, in ms since midnight, as the legs are walked through.
        Long clockMs = departS == null ? null : departS * 1000L;

        List<String> path = new ArrayList<>();
        path.add("stop_" + from);
        List<Segment> segments = new ArrayList<>();
        double metres = 0.0;
        for (Views.Leg leg : journey.legs()) {
            double legMetres = metresAlong(city.world().graph(), leg.path());
            metres += legMetres;
            Trip trip = null;
            if (leg.kind().equals("RIDE") && clockMs != null) {
                BusRoute route = city.network().routes().get(Integer.parseInt(leg.line().substring(1)));
                LiveTimes.Placement placed = LiveTimes.place(route, leg.path());
                if (placed != null) {
                    long boardMs = times.millisTo(placed.direction(), placed.start());
                    long alightMs = times.millisTo(placed.direction(), placed.start() + leg.path().size() - 1);
                    trip = city.timetable().firstReaching(route.id(), placed.direction().outbound(),
                            (int) Math.round(boardMs / 1000.0), (int) Math.ceil(clockMs / 1000.0));
                    if (trip != null) {
                        clockMs = trip.departSimS() * 1000L + alightMs;
                    }
                }
            }
            if (clockMs != null && trip == null) {
                clockMs += leg.ms();
            }
            boolean betweenStops = leg.kind().equals("RIDE") || leg.kind().equals("WALK");
            if (!betweenStops) {
                continue;
            }
            String routeId = leg.kind().equals("RIDE") ? "route_" + leg.line().substring(1) : null;
            String tripId = trip == null ? null : trip.id();
            segments.add(new Segment("stop_" + leg.fromNode(), "stop_" + leg.toNode(), routeId, tripId, tripId,
                    round2(leg.ms() / 60_000.0), round2(legMetres)));
            if (!path.get(path.size() - 1).equals("stop_" + leg.toNode())) {
                path.add("stop_" + leg.toNode());
            }
        }
        if (!path.get(path.size() - 1).equals("stop_" + to)) {
            path.add("stop_" + to);
        }
        return new Answer(new Route(path, segments, round2(journey.totalMs() / 60_000.0), round2(metres),
                Math.round(journey.totalMs() / 1000.0)));
    }

    /** {@code GET /api/v1/live}: the run, its clock, and the buses on the road. */
    public static Live live(PlannedCity city) {
        return new Live(new LiveData(city.run().runId(), currentSimTime(city), List.of()));
    }

    /** {@code GET /api/v1/routes}: each line's stops, outbound and return, in calling order. */
    public static Routes routes(PlannedCity city) {
        List<RouteInfo> all = new ArrayList<>();
        for (BusRoute route : city.network().routes()) {
            List<String> back = route.runsBothWays() ? stopIds(distinct(route.back().stopOccurrences())) : List.of();
            all.add(new RouteInfo("route_" + route.id(), stopIds(route.stops()), back));
        }
        return new Routes(all);
    }

    /** {@code GET /api/v1/trips}: every timetabled trip, with its ETA at each stop at the current speeds. */
    public static Trips trips(PlannedCity city) {
        LiveTimes times = new LiveTimes(city.world().graph(), city.world().costModel());
        List<TripInfo> all = new ArrayList<>();
        for (Trip trip : city.timetable().trips()) {
            LiveTimes.Direction direction = LiveTimes.direction(city.network().routes().get(trip.routeId()),
                    trip.outbound());
            List<StopTime> stopTimes = new ArrayList<>();
            for (int i = 0; i < direction.stopOccurrences().length; i++) {
                long offsetMs = times.millisTo(direction, direction.stopOccurrencePositions()[i]);
                stopTimes.add(new StopTime("stop_" + direction.stopOccurrences()[i],
                        trip.departSimS() + (int) Math.round(offsetMs / 1000.0)));
            }
            all.add(new TripInfo(trip.id(), "route_" + trip.routeId(), trip.outbound(), trip.departSimS(),
                    trip.seats(), stopTimes));
        }
        return new Trips(all);
    }

    /** The simulator's clock as seconds of the day, or {@code null} if it did not say. */
    static Integer currentSimTime(PlannedCity city) {
        try {
            return SimTime.parse(city.run().time());
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    /**
     * The passenger's start in simulated seconds. The portal's first contract
     * sent a real-world date and time ({@code 2026-09-10T09:00:00Z}); that says
     * nothing about the simulated day, so it is still accepted and means "now",
     * as it always did.
     */
    private static Integer departure(PlannedCity city, String text) {
        if (text.matches("\\d{4}-\\d{2}-\\d{2}T.*")) {
            return currentSimTime(city);
        }
        try {
            return SimTime.parse(text);
        } catch (IllegalArgumentException bad) {
            throw new UsageException("departureTime: " + bad.getMessage());
        }
    }

    private static List<String> stopIds(int[] stops) {
        List<String> ids = new ArrayList<>(stops.length);
        for (int stop : stops) {
            ids.add("stop_" + stop);
        }
        return ids;
    }

    private static int[] distinct(int[] values) {
        return java.util.Arrays.stream(values).distinct().toArray();
    }

    private static int resolve(PlannedCity city, String stopId, String field) {
        if (stopId == null || !stopId.startsWith("stop_")) {
            throw new UsageException(field + " must look like stop_<number>, got " + (stopId == null ? "nothing" : "'" + stopId + "'"));
        }
        int node;
        try {
            node = Integer.parseInt(stopId.substring("stop_".length()));
        } catch (NumberFormatException notANumber) {
            throw new UsageException(field + " must look like stop_<number>, got '" + stopId + "'");
        }
        if (node < 0 || node >= city.world().graph().nodeCount()) {
            throw new UnknownStop(stopId + " is not on this map");
        }
        int representative = city.world().stops().representativeOf(node);
        if (representative < 0) {
            throw new UnknownStop(stopId + " is a junction but not a stop; GET /api/v1/stops lists the stops");
        }
        return representative;
    }

    /**
     * Length of a drawn path: along the roads between consecutive junctions
     * where one road joins them, straight-line otherwise (a walk).
     */
    private static double metresAlong(Graph graph, List<Integer> path) {
        double metres = 0.0;
        for (int i = 0; i + 1 < path.size(); i++) {
            int a = path.get(i);
            int b = path.get(i + 1);
            double road = -1.0;
            for (int edgeId : graph.outgoingEdges(a)) {
                if (graph.edge(edgeId).to() == b) {
                    road = graph.edge(edgeId).lengthM();
                    break;
                }
            }
            if (road < 0.0) {
                double dx = graph.node(a).position().xM() - graph.node(b).position().xM();
                double dy = graph.node(a).position().yM() - graph.node(b).position().yM();
                road = Math.hypot(dx, dy);
            }
            metres += road;
        }
        return metres;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
