package csc210.algorithm.fabric;

import csc210.algorithm.routing.BusRoute;
import csc210.algorithm.routing.RouteNetwork;
import csc210.algorithm.schedule.BusPositions;
import csc210.algorithm.schedule.LiveTimes;
import csc210.algorithm.schedule.SimTime;
import csc210.algorithm.serve.PlannedCity;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Draws Terminus in the DSTNS observer: each bus line along its roads, its stops, the
 * buses on the road gliding between reports, and a panel of the next arrivals.
 *
 * <p>Lines and stops are sent again only when the network changes (a new run). Buses,
 * the panel and the status line are sent on every {@link #update}, in one batch, so the
 * observer never shows half an update.
 */
public final class ObserverDrawing {

    /** Most rows in the arrivals table. */
    public static final int ARRIVALS_SHOWN = 12;
    /** DSTNS offers numbered colour series 0 to 7, for things like bus lines. */
    private static final int SERIES = 8;

    private final FabricClient fabric;
    private final BooleanSupplier stale;
    private RouteNetwork drawn;

    /** @param stale whether the speeds are out of date because re-reading the simulator is failing */
    public ObserverDrawing(FabricClient fabric, BooleanSupplier stale) {
        this.fabric = fabric;
        this.stale = stale;
    }

    /** Sends whatever changed since the last call. */
    public void update(PlannedCity city) throws IOException, InterruptedException {
        List<Map<String, Object>> elements = new ArrayList<>();
        if (city.network() != drawn) {
            elements.add(lines(city));
            elements.add(openButton());
            fabric.putStops(stopNodes(city.network()));
        }
        Integer now = simTime(city);
        List<Bus> buses = buses(city, now);
        elements.add(busOverlay(buses));
        elements.add(arrivalsPanel(city, now, buses));
        elements.add(status(city, buses, stale.getAsBoolean()));
        fabric.batchUi(elements, List.of());
        drawn = city.network();
    }

    /** One bus on the road, with how long until its next stop. */
    record Bus(BusPositions.BusPosition position, int edge, long millisToNextStop) {
    }

    static List<Bus> buses(PlannedCity city, Integer now) {
        if (now == null) {
            return List.of();
        }
        LiveTimes times = new LiveTimes(city.world().graph(), city.world().costModel());
        List<Bus> buses = new ArrayList<>();
        for (BusPositions.BusPosition bus : BusPositions.at(now, city.timetable(), city.network(),
                city.world().graph(), city.world().costModel())) {
            BusRoute route = city.network().routes().get(bus.trip().routeId());
            LiveTimes.Direction direction = LiveTimes.direction(route, bus.trip().outbound());
            int e = bus.edgeIndex();
            long here = times.millisTo(direction, e) + Math.round(bus.fraction() * times.roadMillis(direction, e));
            long stop = times.millisTo(direction, nextStopPosition(direction, e));
            buses.add(new Bus(bus, direction.edges()[e], Math.max(0L, stop - here)));
        }
        return buses;
    }

    private static int nextStopPosition(LiveTimes.Direction direction, int edgeIndex) {
        int[] positions = direction.stopOccurrencePositions();
        for (int position : positions) {
            if (position > edgeIndex) {
                return position;
            }
        }
        return positions[positions.length - 1];
    }

    static Map<String, Object> lines(PlannedCity city) {
        List<Map<String, Object>> features = new ArrayList<>();
        for (BusRoute route : city.network().routes()) {
            features.add(Map.of("type", "route", "nodes", list(route.nodes()), "series", series(route.id()),
                    "width", 4));
            if (route.runsBothWays()) {
                features.add(Map.of("type", "route", "nodes", list(route.back().nodes()),
                        "series", series(route.id()), "width", 2, "dash", true));
            }
        }
        for (int stop : stopNodes(city.network())) {
            features.add(Map.of("type", "marker", "node", stop, "shape", "stop", "label", stopName(city, stop)));
        }
        return element("lines", "overlay", Map.of("features", features));
    }

    static Map<String, Object> busOverlay(List<Bus> buses) {
        List<Map<String, Object>> features = new ArrayList<>();
        for (Bus bus : buses) {
            features.add(Map.of("type", "entity", "id", bus.position().trip().id(), "edge", bus.edge(),
                    "at", round(bus.position().fraction()), "shape", "bus",
                    "series", series(bus.position().trip().routeId()),
                    "label", "R" + bus.position().trip().routeId()));
        }
        return element("buses", "overlay", Map.of("features", features));
    }

    static Map<String, Object> arrivalsPanel(PlannedCity city, Integer now, List<Bus> buses) {
        List<List<String>> rows = new ArrayList<>();
        buses.stream()
                .sorted(Comparator.comparingLong(Bus::millisToNextStop))
                .limit(ARRIVALS_SHOWN)
                .forEach(bus -> rows.add(List.of(
                        "R" + bus.position().trip().routeId(),
                        bus.position().trip().id(),
                        stopName(city, bus.position().nextStop()),
                        minutes(bus.millisToNextStop()))));
        List<Object> widgets = new ArrayList<>();
        widgets.add(Map.of("type", "stats", "items", List.of(
                Map.of("label", "Lines", "value", String.valueOf(city.network().routes().size())),
                Map.of("label", "Buses on the road", "value", String.valueOf(buses.size())),
                Map.of("label", "Simulated time", "value", now == null ? "unknown" : clock(now)))));
        if (rows.isEmpty()) {
            widgets.add(Map.of("type", "note", "text", now == null
                    ? "The simulated time is not known yet."
                    : "No bus is on the road now. Buses run every 15 minutes from 06:00 to 21:45."));
        } else {
            widgets.add(Map.of("type", "table",
                    "columns", List.of(Map.of("label", "Line"), Map.of("label", "Trip"),
                            Map.of("label", "Next stop"), Map.of("label", "Arrives in", "align", "right")),
                    "cells", rows));
        }
        return element("arrivals", "panel", Map.of("title", "Next arrivals", "widgets", widgets));
    }

    static Map<String, Object> status(PlannedCity city, List<Bus> buses, boolean stale) {
        if (stale) {
            return element("status", "status", Map.of("label", "Terminus",
                    "value", "road speeds out of date: cannot re-read DSTNS", "tone", "warn"));
        }
        return element("status", "status", Map.of("label", "Terminus",
                "value", city.network().routes().size() + " lines, " + buses.size() + " buses",
                "tone", buses.isEmpty() ? "neutral" : "ok"));
    }

    private static Map<String, Object> openButton() {
        return element("open", "map_control", Map.of("label", "Terminus", "icon", "pin", "opens", "arrivals"));
    }

    static List<Integer> stopNodes(RouteNetwork network) {
        List<Integer> stops = new ArrayList<>();
        for (BusRoute route : network.routes()) {
            for (int stop : route.stops()) {
                if (!stops.contains(stop)) {
                    stops.add(stop);
                }
            }
        }
        return stops;
    }

    private static Map<String, Object> element(String id, String slot, Map<String, Object> fields) {
        Map<String, Object> element = new LinkedHashMap<>();
        element.put("id", id);
        element.put("slot", slot);
        element.putAll(fields);
        return element;
    }

    private static String stopName(PlannedCity city, int stop) {
        return city.stopNames().getOrDefault(stop, "Stop " + stop);
    }

    private static Integer simTime(PlannedCity city) {
        try {
            return SimTime.parse(city.run().time());
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private static String clock(int seconds) {
        return String.format("%02d:%02d", seconds / 3600, seconds % 3600 / 60);
    }

    private static String minutes(long millis) {
        long minutes = Math.round(millis / 60_000.0);
        return minutes < 1 ? "due" : minutes + " min";
    }

    private static int series(int routeId) {
        return Math.floorMod(routeId, SERIES);
    }

    private static double round(double fraction) {
        return Math.round(fraction * 1000.0) / 1000.0;
    }

    private static List<Integer> list(int[] values) {
        List<Integer> out = new ArrayList<>(values.length);
        for (int value : values) {
            out.add(value);
        }
        return out;
    }
}
