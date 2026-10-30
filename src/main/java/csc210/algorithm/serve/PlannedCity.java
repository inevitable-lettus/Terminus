package csc210.algorithm.serve;

import csc210.algorithm.cli.Report;
import csc210.algorithm.cli.RunInfo;
import csc210.algorithm.cli.UsageException;
import csc210.algorithm.client.Location;
import csc210.algorithm.graph.Graph;
import csc210.algorithm.graph.StrongComponents;
import csc210.algorithm.journey.Itinerary;
import csc210.algorithm.journey.JourneyPlanner;
import csc210.algorithm.journey.StopAccessIndex;
import csc210.algorithm.model.Edge;
import csc210.algorithm.model.EdgeState;
import csc210.algorithm.model.Node;
import csc210.algorithm.routing.BuildTrace;
import csc210.algorithm.routing.BusRoute;
import csc210.algorithm.routing.RouteBudget;
import csc210.algorithm.routing.RouteNetwork;
import csc210.algorithm.routing.RouteNetworkBuilder;
import csc210.algorithm.schedule.Timetable;
import csc210.algorithm.search.ExpansionRecorder;
import csc210.algorithm.transit.TransitGraph;
import csc210.algorithm.transit.TransitLink;
import csc210.algorithm.world.World;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One planned city, held in memory: the world as read, the network Algorithm 1
 * built on it, and a journey planner for Algorithm 2 — everything the API
 * answers from, built once so a journey costs a search and not a reload.
 *
 * <p>Immutable. A reload builds a new one and swaps it in, so a request that
 * started on the old city finishes on the old city. A speed update
 * ({@link #withLiveCosts}) also builds a new one, but keeps the network and the
 * timetable, so route and trip ids stay the same for the whole run.
 */
public final class PlannedCity {

    /** How many settled junctions a search trace keeps. Enough to watch; bounded so a trace stays small. */
    public static final int TRACE_CAP = 20_000;

    private final World world;
    private final RunInfo run;
    private final RouteBudget budget;
    private final String source;
    private final RouteNetwork network;
    private final TransitGraph transit;
    private final JourneyPlanner planner;
    private final BuildTrace buildTrace;
    private final StrongComponents components;
    private final Timetable timetable;

    private PlannedCity(World world, RunInfo run, RouteBudget budget, String source, RouteNetwork network,
                        TransitGraph transit, JourneyPlanner planner, BuildTrace buildTrace,
                        StrongComponents components, Timetable timetable) {
        this.world = world;
        this.run = run;
        this.budget = budget;
        this.source = source;
        this.network = network;
        this.transit = transit;
        this.planner = planner;
        this.buildTrace = buildTrace;
        this.components = components;
        this.timetable = timetable;
    }

    /**
     * Plans {@code world}: builds the network, watching the build, then the
     * passenger network and planner.
     *
     * @param source {@code "live"} or {@code "bundle"}, carried into the provenance
     */
    public static PlannedCity plan(World world, RunInfo run, RouteBudget budget, String source) {
        BuildTrace trace = new BuildTrace();
        RouteNetwork network = new RouteNetworkBuilder(world.graph(), world.costModel(), world.demandModel(), budget)
                .build(trace);
        TransitGraph transit = TransitGraph.of(world.graph(), network, world.costModel());
        return new PlannedCity(world, run, budget, source, network, transit, JourneyPlanner.of(world.graph(), transit),
                trace, StrongComponents.of(world.graph(), world.costModel()), Timetable.of(network));
    }

    /**
     * The same network and timetable, re-priced on {@code fresh} (a newer
     * snapshot of the same map). Algorithm 1 does not run again; only the ride
     * costs, and so the journeys, follow the new speeds.
     */
    public PlannedCity withLiveCosts(World fresh, RunInfo now) {
        TransitGraph transit = TransitGraph.of(fresh.graph(), network, fresh.costModel());
        return new PlannedCity(fresh, now, budget, source, network, transit, JourneyPlanner.of(fresh.graph(), transit),
                buildTrace, StrongComponents.of(fresh.graph(), fresh.costModel()), timetable);
    }

    public World world() {
        return world;
    }

    public RunInfo run() {
        return run;
    }

    public RouteNetwork network() {
        return network;
    }

    public Timetable timetable() {
        return timetable;
    }

    /** {@code "live"} or {@code "bundle"}. */
    public String source() {
        return source;
    }

    // ---- the world ----------------------------------------------------------

    public Views.World worldView() {
        Graph graph = world.graph();
        List<Views.Junction> junctions = new ArrayList<>(graph.nodeCount());
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (int id = 0; id < graph.nodeCount(); id++) {
            Node node = graph.node(id);
            double x = node.position().xM();
            double y = node.position().yM();
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            junctions.add(new Views.Junction(id, x, y, node.position().lat(), node.position().lon(),
                    components.inLargest(id)));
        }

        EdgeState[] states = new EdgeState[graph.edgeCount()];
        for (EdgeState state : world.snapshot().edges()) {
            states[state.id()] = state;
        }
        List<Views.Road> roads = new ArrayList<>();
        for (int id = 0; id < graph.edgeCount(); id++) {
            if (isFabricated(id, states)) {
                continue;
            }
            Edge edge = graph.edge(id);
            int twin = edge.reverseTwin();
            boolean oneWay = twin < 0 || isFabricated(twin, states);
            boolean traversable = world.costModel().isTraversable(id);
            roads.add(new Views.Road(id, edge.from(), edge.to(), edge.roadClass(), oneWay, !traversable,
                    traversable ? world.costModel().costMillis(id) : null,
                    states[id] == null ? 0.0 : states[id].congestion()));
        }

        Map<Integer, List<String>> linesAt = linesAtStops();
        List<Views.Stop> stops = new ArrayList<>();
        for (int stop : world.stops().representatives()) {
            stops.add(new Views.Stop(stop, world.demandModel().demandAt(stop), components.inLargest(stop),
                    linesAt.getOrDefault(stop, List.of())));
        }

        Views.Bounds bounds = graph.nodeCount() == 0
                ? new Views.Bounds(0, 0, 0, 0)
                : new Views.Bounds(minX, minY, maxX, maxY);
        return new Views.World(provenance(), bounds, junctions, roads, stops);
    }

    /**
     * Whether {@code edgeId} is the fabricated reverse of a one-way street: not
     * drivable, yet not closed either. The cost model knows it only as "not
     * traversable", and the snapshot says whether it was closed; the difference
     * is the fabrication, whichever way the server reported it.
     */
    private boolean isFabricated(int edgeId, EdgeState[] states) {
        Boolean flagged = world.graph().edge(edgeId).syntheticReverse();
        if (flagged != null) {
            return flagged;
        }
        return !world.costModel().isTraversable(edgeId) && (states[edgeId] == null || !states[edgeId].closed());
    }

    private Views.Provenance provenance() {
        Location location = world.topology().location();
        int inPockets = 0;
        for (int stop : world.stops().representatives()) {
            if (!components.inLargest(stop)) {
                inPockets++;
            }
        }
        return new Views.Provenance(source, run.runId(), run.seed(), run.time(),
                location == null ? null : location.city(), location == null ? null : location.country(),
                world.topology().graphHash(), world.demandModel().source().name(),
                world.demandModel().generatorsAssigned(), world.demandModel().generatorsActive(),
                world.stops().rawCount(), world.stops().count(), inPockets,
                new Views.Budget(budget.maxRouteMillis() / 60_000.0, budget.maxRoutes(), budget.targetCoverage(),
                        budget.walkingRadiusM()));
    }

    /** Line labels calling at each stop, in either direction, in line order. */
    private Map<Integer, List<String>> linesAtStops() {
        Map<Integer, List<String>> at = new TreeMap<>();
        for (BusRoute route : network.routes()) {
            java.util.Set<Integer> calls = new java.util.LinkedHashSet<>();
            for (int stop : route.stops()) {
                calls.add(stop);
            }
            if (route.runsBothWays()) {
                for (int stop : route.back().stopOccurrences()) {
                    calls.add(stop);
                }
            }
            for (int stop : calls) {
                at.computeIfAbsent(stop, ignored -> new ArrayList<>()).add(route.label());
            }
        }
        return at;
    }

    // ---- the network --------------------------------------------------------

    public Views.Network networkView() {
        List<Views.Line> lines = new ArrayList<>();
        for (BusRoute route : network.routes()) {
            Views.Direction outbound = new Views.Direction(boxed(route.nodes()), boxed(route.stops()),
                    route.travelMillis());
            Views.Direction back = route.runsBothWays()
                    ? new Views.Direction(boxed(route.back().nodes()), boxed(distinct(route.back().stopOccurrences())),
                            route.back().travelMillis())
                    : null;
            double share = network.totalDemand() == 0.0 ? 0.0 : route.demandCovered() / network.totalDemand();
            lines.add(new Views.Line(route.id(), route.label(), outbound, back, route.demandCovered(), share));
        }
        return new Views.Network(network.coverage(), network.demandCovered(), network.totalDemand(),
                Report.stopReason(network, budget, world), Report.interchangeStops(network),
                Report.pieces(transit) <= 1, lines);
    }

    // ---- journeys -----------------------------------------------------------

    /**
     * The journey from junction {@code from} to junction {@code to}, answered by
     * A*, with Dijkstra run on the same question for the comparison.
     *
     * @throws UsageException if either is not a junction on this map
     */
    public Views.Journey journeyView(int from, int to, long boardWaitMillis) {
        requireJunction(from, "from");
        requireJunction(to, "to");
        JourneyPlanner waiting = planner.withBoardWait(boardWaitMillis);
        ExpansionRecorder aimed = new ExpansionRecorder(0);
        ExpansionRecorder blind = new ExpansionRecorder(0);
        Itinerary itinerary = waiting.plan(from, to, true, aimed);
        waiting.plan(from, to, false, blind);

        long walkMs = StopAccessIndex.walkMillis(TransitGraph.straightLineMetres(
                world.graph().node(from).position(), world.graph().node(to).position()));
        List<Views.Leg> legs = new ArrayList<>();
        for (Itinerary.Leg leg : itinerary.legs()) {
            String line = leg.routeId() >= 0 && leg.kind() == TransitLink.Kind.RIDE ? "R" + leg.routeId() : null;
            legs.add(new Views.Leg(leg.kind().name(), line, leg.fromNode(), leg.toNode(), leg.stopsRidden(),
                    leg.millis(), legPath(leg), Report.instruction(leg)));
        }
        return new Views.Journey(from, to, itinerary.found(), itinerary.found() ? itinerary.totalMillis() : -1L,
                itinerary.transfers(), walkMs, legs, aimed.settledCount(), blind.settledCount());
    }

    /** The order {@code algorithm} ({@code "astar"} or {@code "dijkstra"}) settles junctions for one journey. */
    public Views.SearchTrace searchTraceView(int from, int to, String algorithm) {
        requireJunction(from, "from");
        requireJunction(to, "to");
        boolean aimed = switch (algorithm) {
            case "astar" -> true;
            case "dijkstra" -> false;
            default -> throw new UsageException("algo must be astar or dijkstra, got '" + algorithm + "'");
        };
        ExpansionRecorder recorder = new ExpansionRecorder(TRACE_CAP);
        planner.plan(from, to, aimed, recorder);
        List<Long> costs = new ArrayList<>();
        for (long cost : recorder.costs()) {
            costs.add(cost);
        }
        return new Views.SearchTrace(algorithm, from, to, boxed(recorder.vertices()), costs,
                recorder.settledCount(), recorder.truncated());
    }

    public Views.BuildTrace buildTraceView() {
        List<Views.BuildStep> steps = new ArrayList<>();
        for (BuildTrace.Step step : buildTrace.steps()) {
            steps.add(new Views.BuildStep(step.kind(), step.routeId(), step.stop(), boxed(step.path()), step.gain()));
        }
        return new Views.BuildTrace(steps);
    }

    /**
     * The junctions a leg passes, for drawing. A ride follows its line's own
     * roads, stop by stop through the calls the search made; a walk is a
     * straight line, as it is priced.
     */
    private List<Integer> legPath(Itinerary.Leg leg) {
        if (leg.kind() != TransitLink.Kind.RIDE || leg.routeId() < 0 || leg.routeId() >= network.routeCount()) {
            return leg.fromNode() == leg.toNode() ? List.of(leg.fromNode()) : List.of(leg.fromNode(), leg.toNode());
        }
        BusRoute route = network.routes().get(leg.routeId());
        List<Integer> path = new ArrayList<>();
        path.add(leg.calls().get(0));
        for (int k = 0; k + 1 < leg.calls().size(); k++) {
            int[] hop = hop(route, leg.calls().get(k), leg.calls().get(k + 1));
            if (hop == null) {
                path.add(leg.calls().get(k + 1));
                continue;
            }
            for (int n = 1; n < hop.length; n++) {
                path.add(hop[n]);
            }
        }
        return List.copyOf(path);
    }

    /**
     * The road path of one hop between consecutive calls {@code a} then {@code b}
     * on {@code route}, in either direction. A line that revisits a stop can make
     * the same hop more than once, at different costs; the cheapest is the one the
     * search's ride link carries, so that is the one drawn.
     */
    private int[] hop(BusRoute route, int a, int b) {
        int[] best = null;
        long bestCost = Long.MAX_VALUE;
        int[][] nodeLists = route.runsBothWays()
                ? new int[][] {route.nodes(), route.back().nodes()}
                : new int[][] {route.nodes()};
        int[][] edgeLists = route.runsBothWays()
                ? new int[][] {route.edges(), route.back().edges()}
                : new int[][] {route.edges()};
        int[][] callLists = route.runsBothWays()
                ? new int[][] {route.stopOccurrences(), route.back().stopOccurrences()}
                : new int[][] {route.stopOccurrences()};
        int[][] positionLists = route.runsBothWays()
                ? new int[][] {route.stopOccurrencePositions(), route.back().stopOccurrencePositions()}
                : new int[][] {route.stopOccurrencePositions()};
        for (int d = 0; d < nodeLists.length; d++) {
            int[] calls = callLists[d];
            int[] positions = positionLists[d];
            for (int i = 0; i + 1 < calls.length; i++) {
                if (calls[i] != a || calls[i + 1] != b) {
                    continue;
                }
                long cost = 0L;
                for (int e = positions[i]; e < positions[i + 1]; e++) {
                    int edge = edgeLists[d][e];
                    cost += world.costModel().busMillis(edge, world.graph().edge(edge).lengthM());
                }
                if (cost < bestCost) {
                    bestCost = cost;
                    best = Arrays.copyOfRange(nodeLists[d], positions[i], positions[i + 1] + 1);
                }
            }
        }
        return best;
    }

    private void requireJunction(int node, String which) {
        if (node < 0 || node >= world.graph().nodeCount()) {
            throw new UsageException(which + " must be a junction id in [0, " + world.graph().nodeCount()
                    + "), got " + node);
        }
    }

    // ---- the static bundle --------------------------------------------------

    /**
     * A handful of journeys with both searches' traces, for the dashboard when
     * no server is running. Chosen deterministically — spread across the main
     * road network, and only ones a bus actually helps with — so the same city
     * always bundles the same examples.
     */
    public List<Views.SampleJourney> sampleJourneys(int wanted) {
        List<Integer> main = new ArrayList<>();
        for (int id = 0; id < world.graph().nodeCount(); id++) {
            if (components.inLargest(id)) {
                main.add(id);
            }
        }
        List<Views.SampleJourney> samples = new ArrayList<>();
        if (main.size() < 2) {
            return samples;
        }
        Map<String, Boolean> tried = new LinkedHashMap<>();
        int step = Math.max(1, main.size() / 37);
        for (int i = 0; i < main.size() && samples.size() < wanted; i += step) {
            int from = main.get(i);
            int to = main.get((i * 7 + main.size() / 2) % main.size());
            if (from == to || tried.put(from + ">" + to, true) != null) {
                continue;
            }
            Views.Journey journey = journeyView(from, to, 0L);
            boolean usesABus = journey.legs().stream().anyMatch(l -> l.kind().equals("RIDE"));
            if (!journey.found() || !usesABus) {
                continue;
            }
            samples.add(new Views.SampleJourney(journey, searchTraceView(from, to, "astar"),
                    searchTraceView(from, to, "dijkstra")));
        }
        return samples;
    }

    private static List<Integer> boxed(int[] values) {
        List<Integer> list = new ArrayList<>(values.length);
        for (int value : values) {
            list.add(value);
        }
        return list;
    }

    private static int[] distinct(int[] values) {
        return Arrays.stream(values).distinct().toArray();
    }
}
