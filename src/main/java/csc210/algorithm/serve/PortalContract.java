package csc210.algorithm.serve;

import csc210.algorithm.cli.UsageException;
import csc210.algorithm.graph.Graph;
import csc210.algorithm.model.Node;

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
 * <p><strong>What the contract asks that this module cannot honestly answer.</strong>
 * {@code busId} is {@code null}: there is no fleet model. {@code departureTime}
 * is accepted and ignored: the network is planned for one moment. {@code cost}
 * is in minutes, since the contract names no unit and minutes are what every
 * other figure here is printed in.
 */
public final class PortalContract {

    private PortalContract() {}

    public record Query(String sourceStopId, String destStopId, String departureTime) {}

    /**
     * One leg between stops. A ride has a {@code routeId}; a walk between two
     * stops to change line has {@code routeId} {@code null}.
     */
    public record Segment(String fromStopId, String toStopId, String routeId, String busId, double cost,
                          double distanceMeters) {}

    public record Route(List<String> path, List<Segment> segments, double totalCost, double totalDistanceMeters,
                        long estimatedDurationSeconds) {}

    public record Answer(Route data) {}

    public record StopInfo(String stopId, int node, double lat, double lng, boolean served, List<String> routeIds) {}

    public record Stops(List<StopInfo> data) {}

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
        List<StopInfo> all = new ArrayList<>();
        for (int stop : city.world().stops().representatives()) {
            Node node = graph.node(stop);
            List<String> lines = linesAt.getOrDefault(stop, List.of());
            all.add(new StopInfo("stop_" + stop, stop, node.position().lat(), node.position().lon(),
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

        Views.Journey journey = city.journeyView(from, to, 0L);
        if (!journey.found()) {
            throw new NoRoute("no journey joins stop_" + from + " to stop_" + to + " on this network");
        }

        List<String> path = new ArrayList<>();
        path.add("stop_" + from);
        List<Segment> segments = new ArrayList<>();
        double metres = 0.0;
        for (Views.Leg leg : journey.legs()) {
            double legMetres = metresAlong(city.world().graph(), leg.path());
            metres += legMetres;
            boolean betweenStops = leg.kind().equals("RIDE") || leg.kind().equals("WALK");
            if (!betweenStops) {
                continue;
            }
            String routeId = leg.kind().equals("RIDE") ? "route_" + leg.line().substring(1) : null;
            segments.add(new Segment("stop_" + leg.fromNode(), "stop_" + leg.toNode(), routeId, null,
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
