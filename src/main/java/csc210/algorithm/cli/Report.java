package csc210.algorithm.cli;

import csc210.algorithm.demand.DemandModel;
import csc210.algorithm.demand.StopSet;
import csc210.algorithm.export.RouteExporter;
import csc210.algorithm.graph.Graph;
import csc210.algorithm.journey.Itinerary;
import csc210.algorithm.journey.JourneyPlanner;
import csc210.algorithm.journey.StopAccess;
import csc210.algorithm.journey.StopAccessIndex;
import csc210.algorithm.model.Node;
import csc210.algorithm.routing.BusRoute;
import csc210.algorithm.routing.RouteBudget;
import csc210.algorithm.routing.RouteNetwork;
import csc210.algorithm.transit.TransitGraph;
import csc210.algorithm.transit.TransitLink;
import csc210.algorithm.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Everything the command line prints, as pure functions from results to text, so
 * the wording is tested without a process or a simulator.
 *
 * <p>Two principles run through it. <strong>Say what the result stands on:</strong>
 * every report opens with the map, the stop count, where the demand came from and
 * the budget in force, because a coverage figure means nothing without them.
 * <strong>Say why, not just what:</strong> a network that stopped at three lines
 * says whether that was the target being met, the line cap being hit or nothing
 * reachable being left, since each of those calls for a different response.
 */
public final class Report {

    private Report() {}

    // ---- header ------------------------------------------------------------

    /** The block that opens every report: which world, which stops, which demand, which budget. */
    public static String header(RunInfo run, World world, RouteBudget budget, Options options) {
        StringBuilder text = new StringBuilder();
        text.append("DSTNS world\n");
        text.append(String.format(Locale.ROOT, "  run      %s  seed %s  time %s%s%n",
                run.runId() == null ? "(unnamed)" : run.runId(), run.seed(), run.time(),
                run.attached() ? "  (attached; clock untouched)" : "  (started by this command)"));
        text.append(String.format(Locale.ROOT, "  map      %d junctions, %d roads, graph %s%n",
                world.graph().nodeCount(), world.graph().edgeCount(), shortHash(world.topology().graphHash())));
        text.append(String.format(Locale.ROOT, "  one-way  %d roads excluded (%s)%n", world.oneWayEdges(),
                world.oneWayFromTopology() ? "flagged by the topology" : "read from /view/edges"));
        text.append(String.format(Locale.ROOT, "  stops    %d flagged, %d after merging those within %.0f m%n",
                world.stops().rawCount(), world.stops().count(), StopSet.MERGE_RADIUS_M));
        int stuck = 0;
        csc210.algorithm.graph.StrongComponents both =
                csc210.algorithm.graph.StrongComponents.of(world.graph(), world.costModel());
        for (int stop : world.stops().representatives()) {
            if (!both.inLargest(stop)) {
                stuck++;
            }
        }
        if (stuck > 0) {
            text.append(String.format(Locale.ROOT,
                    "           %d of them in one-way pockets off the main road network, "
                            + "where no line can call and come back%n", stuck));
        }
        text.append(String.format(Locale.ROOT, "  demand   %s%n", describe(world.demandModel())));
        if (world.demandNote() != null) {
            text.append("           ").append(world.demandNote()).append('\n');
        }
        text.append(String.format(Locale.ROOT, "  budget   %d min per line (%s), walk %.0f m (%s), up to %d lines (%s), target %.0f%%%n",
                budget.maxRouteMillis() / 60_000L, options.minutes != null ? "set" : "default",
                budget.walkingRadiusM(), options.walkMetres != null ? "set" : "derived",
                budget.maxRoutes(), options.routes != null ? "set" : "derived",
                budget.targetCoverage() * 100.0));
        if (budget.walkingRadiusM() < StopSet.MERGE_RADIUS_M) {
            text.append(String.format(Locale.ROOT,
                    "           note: a walk under %.0f m can leave merged-away stops counted as uncovered%n",
                    StopSet.MERGE_RADIUS_M));
        }
        return text.toString();
    }

    private static String describe(DemandModel demand) {
        return switch (demand.source()) {
            case CATCHMENT -> String.format(Locale.ROOT,
                    "CATCHMENT — %d of %d active trip generators, each credited to its nearest stop",
                    demand.generatorsAssigned(), demand.generatorsActive());
            case BUILDINGS -> "BUILDINGS — summed from nearby building junctions";
            case UNIFORM -> "UNIFORM — no demand data found, so every stop counts the same";
        };
    }

    private static String shortHash(String hash) {
        if (hash == null || hash.isBlank()) {
            return "(none)";
        }
        String bare = hash.startsWith("sha256:") ? hash.substring("sha256:".length()) : hash;
        return bare.length() > 12 ? bare.substring(0, 12) : bare;
    }

    // ---- network -----------------------------------------------------------

    /** The lines, then a summary that says how it ended and whether riders can move between them. */
    public static String network(World world, RouteNetwork network, RouteBudget budget) {
        StringBuilder text = new StringBuilder();
        if (world.stops().count() == 0) {
            return "No bus stops in this world, so there is nothing to design a network around.\n";
        }
        if (network.routeCount() == 0) {
            text.append("No line could be built.\n");
            text.append(stopReason(network, budget, world)).append('\n');
            return text.toString();
        }

        text.append("Lines\n");
        text.append("  line  stops  minutes   demand  share  first -> last   back\n");
        for (BusRoute route : network.routes()) {
            int[] stops = route.stops();
            text.append(String.format(Locale.ROOT, "  %-5s %5d %8.1f %8.2f %5.1f%%  %5d -> %-5d  %s%n",
                    route.label(), route.stopCount(), route.travelMillis() / 60_000.0, route.demandCovered(),
                    share(route.demandCovered(), network.totalDemand()), stops[0], stops[stops.length - 1],
                    route.runsBothWays()
                            ? String.format(Locale.ROOT, "%.1f min", route.back().travelMillis() / 60_000.0)
                            : "none (no road leads back)"));
        }

        int stopsTotal = world.demandModel().stopCount();
        int covered = network.coveredStops().length;
        text.append('\n').append("Summary\n");
        text.append(String.format(Locale.ROOT, "  %d line%s cover %.1f%% of demand (%d of %d stops within reach)%n",
                network.routeCount(), network.routeCount() == 1 ? "" : "s", network.coverage() * 100.0,
                covered, stopsTotal));
        text.append("  ").append(stopReason(network, budget, world)).append('\n');

        List<Integer> interchanges = interchangeStops(network);
        text.append(interchanges.isEmpty()
                ? "  interchanges: none — no stop is served by more than one line\n"
                : String.format(Locale.ROOT, "  interchanges: %d stop%s served by more than one line (%s)%n",
                        interchanges.size(), interchanges.size() == 1 ? "" : "s", join(interchanges, 8)));

        TransitGraph transit = TransitGraph.of(world.graph(), network, world.costModel());
        int pieces = pieces(transit);
        text.append(pieces <= 1
                ? "  passenger network: connected — a rider can reach any served stop from any other\n"
                : String.format(Locale.ROOT,
                        "  passenger network: %d separate pieces — a rider cannot get between them "
                                + "(interchange walk %.0f m)%n", pieces, transit.walkingRadiusM()));
        return text.toString();
    }

    /** Why the builder stopped, in the words that say what to change. */
    public static String stopReason(RouteNetwork network, RouteBudget budget, World world) {
        double target = budget.targetCoverage();
        if (network.totalDemand() > 0.0 && network.coverage() >= target) {
            return String.format(Locale.ROOT, "stopped because the %.0f%% coverage target was met", target * 100.0);
        }
        if (network.routeCount() >= budget.maxRoutes()) {
            return String.format(Locale.ROOT,
                    "stopped at the cap of %d lines with %.1f%% covered; --routes N allows more",
                    budget.maxRoutes(), network.coverage() * 100.0);
        }
        return String.format(Locale.ROOT,
                "stopped with %.1f%% covered because no uncovered stop was reachable within %d minutes "
                        + "of a line's end; --minutes N allows longer lines",
                network.coverage() * 100.0, budget.maxRouteMillis() / 60_000L);
    }

    private static double share(double part, double whole) {
        return whole == 0.0 ? 0.0 : part / whole * 100.0;
    }

    /** Stop node ids served by more than one line, ascending. */
    public static List<Integer> interchangeStops(RouteNetwork network) {
        Map<Integer, Integer> lines = new TreeMap<>();
        for (BusRoute route : network.routes()) {
            for (int stop : route.stops()) {
                lines.merge(stop, 1, Integer::sum);
            }
        }
        List<Integer> shared = new ArrayList<>();
        lines.forEach((stop, count) -> {
            if (count > 1) {
                shared.add(stop);
            }
        });
        return shared;
    }

    /**
     * How many separate pieces the passenger network falls into, treating every
     * link as usable in either direction. One piece means a rider can get from any
     * served stop to any other; more means some pairs have no journey at all.
     */
    public static int pieces(TransitGraph transit) {
        int n = transit.vertexCount();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (int v = 0; v < n; v++) {
            for (TransitLink link : transit.outgoing(v)) {
                int a = find(parent, v);
                int b = find(parent, link.to());
                if (a != b) {
                    parent[a] = b;
                }
            }
        }
        int roots = 0;
        for (int i = 0; i < n; i++) {
            if (find(parent, i) == i) {
                roots++;
            }
        }
        return roots;
    }

    private static int find(int[] parent, int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]];
            x = parent[x];
        }
        return x;
    }

    private static String join(List<Integer> ids, int limit) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < ids.size() && i < limit; i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(ids.get(i));
        }
        if (ids.size() > limit) {
            text.append(", ...");
        }
        return text.toString();
    }

    // ---- stops -------------------------------------------------------------

    /** The busiest stops, so a user has real junction ids to hand to {@code journey}. */
    public static String stops(World world, RouteNetwork network, int top) {
        if (world.stops().count() == 0) {
            return "No bus stops in this world.\n";
        }
        Map<Integer, List<String>> linesAt = new TreeMap<>();
        for (BusRoute route : network.routes()) {
            for (int stop : route.stops()) {
                linesAt.computeIfAbsent(stop, ignored -> new ArrayList<>()).add(route.label());
            }
        }
        int[] ordered = world.demandModel().stopsByDemandDescending();
        double total = world.demandModel().totalDemand();

        StringBuilder text = new StringBuilder();
        text.append(String.format(Locale.ROOT, "Busiest %d of %d stops%n", Math.min(top, ordered.length), ordered.length));
        text.append("  junction        lat        lon   demand  share  served by\n");
        for (int i = 0; i < ordered.length && i < top; i++) {
            int id = ordered[i];
            Node node = world.graph().node(id);
            List<String> lines = linesAt.get(id);
            text.append(String.format(Locale.ROOT, "  %8d  %9.5f  %9.5f  %7.2f  %4.1f%%  %s%n",
                    id, node.position().lat(), node.position().lon(), world.demandModel().demandAt(id),
                    share(world.demandModel().demandAt(id), total),
                    lines == null ? "unserved" : String.join(", ", lines)));
        }
        return text.toString();
    }

    // ---- journey -----------------------------------------------------------

    /**
     * One journey, as numbered instructions with a running clock, set beside the
     * cost of simply walking and the work the search did.
     *
     * @throws IllegalArgumentException if either junction is not on the map
     */
    public static String journey(World world, RouteNetwork network, int from, int to, String time) {
        return journey(world, network, from, to, time, 0.0);
    }

    /** As above, with {@code boardWaitMinutes} of waiting for the first bus. */
    public static String journey(World world, RouteNetwork network, int from, int to, String time,
                                 double boardWaitMinutes) {
        Graph roads = world.graph();
        requireJunction(roads, from, "FROM");
        requireJunction(roads, to, "TO");

        TransitGraph transit = TransitGraph.of(roads, network, world.costModel());
        Itinerary itinerary = JourneyPlanner.of(roads, transit)
                .withBoardWait(Math.round(boardWaitMinutes * 60_000.0)).plan(from, to);

        double straight = TransitGraph.straightLineMetres(roads.node(from).position(), roads.node(to).position());
        StringBuilder text = new StringBuilder();
        text.append(String.format(Locale.ROOT, "Journey  junction %d -> junction %d  at %s%n", from, to, time));
        text.append(String.format(Locale.ROOT, "  from %s   to %s   straight line %.0f m%n",
                where(roads.node(from)), where(roads.node(to)), straight));
        text.append('\n');

        if (!itinerary.found()) {
            text.append("  No journey found.\n");
            text.append("  ").append(whyNoJourney(roads, transit, from, to)).append('\n');
            return text.toString();
        }
        if (itinerary.legs().isEmpty()) {
            text.append("  You are already there.\n");
            return text.toString();
        }

        long elapsed = 0;
        int step = 1;
        for (Itinerary.Leg leg : itinerary.legs()) {
            elapsed += leg.millis();
            text.append(String.format(Locale.ROOT, "  %2d  %-50s %5.1f min   at %5.1f%n",
                    step++, instruction(leg), leg.millis() / 60_000.0, elapsed / 60_000.0));
        }
        long walkMillis = StopAccessIndex.walkMillis(straight);
        text.append('\n');
        text.append(String.format(Locale.ROOT, "  Total %.1f min, %d transfer%s. Walking straight there would take about %.1f min.%n",
                itinerary.totalMinutes(), itinerary.transfers(), itinerary.transfers() == 1 ? "" : "s",
                walkMillis / 60_000.0));
        if (itinerary.verticesExpanded() > 0) {
            text.append(String.format(Locale.ROOT, "  The search looked at %d of %d transit vertices.%n",
                    itinerary.verticesExpanded(), transit.vertexCount()));
        }
        return text.toString();
    }

    public static String instruction(Itinerary.Leg leg) {
        return switch (leg.kind()) {
            case ACCESS -> String.format(Locale.ROOT, "Walk from junction %d to stop %d", leg.fromNode(), leg.toNode());
            case RIDE -> String.format(Locale.ROOT, "Ride R%d, stop %d -> stop %d (%d stop%s)", leg.routeId(),
                    leg.fromNode(), leg.toNode(), leg.stopsRidden(), leg.stopsRidden() == 1 ? "" : "s");
            case TRANSFER -> String.format(Locale.ROOT, "Change line at stop %d (wait)", leg.fromNode());
            case WALK -> String.format(Locale.ROOT, "Walk from stop %d to stop %d and wait", leg.fromNode(), leg.toNode());
            case EGRESS -> String.format(Locale.ROOT, "Walk from stop %d to junction %d", leg.fromNode(), leg.toNode());
            case WALK_DIRECT -> String.format(Locale.ROOT, "Walk the whole way, junction %d to %d", leg.fromNode(), leg.toNode());
            case WAIT -> String.format(Locale.ROOT, "Wait at stop %d for the first bus", leg.fromNode());
        };
    }

    private static String whyNoJourney(Graph roads, TransitGraph transit, int from, int to) {
        StopAccessIndex access = StopAccessIndex.of(roads, transit);
        List<StopAccess> near = access.near(from);
        List<StopAccess> nearTo = access.near(to);
        if (near.isEmpty() && nearTo.isEmpty()) {
            return String.format(Locale.ROOT, "No served stop is within %.0f m of either junction.", access.radiusM());
        }
        if (near.isEmpty()) {
            return String.format(Locale.ROOT, "No served stop is within %.0f m of junction %d.", access.radiusM(), from);
        }
        if (nearTo.isEmpty()) {
            return String.format(Locale.ROOT, "No served stop is within %.0f m of junction %d.", access.radiusM(), to);
        }
        return "Both ends have a stop nearby, but no chain of lines and walks joins them.";
    }

    private static String where(Node node) {
        return String.format(Locale.ROOT, "%.5f, %.5f", node.position().lat(), node.position().lon());
    }

    /** @throws UsageException naming the valid range */
    public static void requireJunction(Graph roads, int node, String which) {
        if (node < 0 || node >= roads.nodeCount()) {
            throw new UsageException(which + " must be a junction id in [0, "
                    + roads.nodeCount() + "), got " + node + ". `./bus stops` lists real ones.");
        }
    }

    // ---- export ------------------------------------------------------------

    public static String exported(RouteExporter.Written written) {
        StringBuilder text = new StringBuilder();
        text.append(String.format(Locale.ROOT, "Wrote %d line%s to:%n  %s%n  %s%n", written.routes(),
                written.routes() == 1 ? "" : "s", written.json(), written.geojson()));
        if (!written.oneWayLines().isEmpty()) {
            text.append(String.format(Locale.ROOT, "  %s %s no road back through %s stops, "
                            + "so %s written outbound only.%n",
                    String.join(", ", written.oneWayLines()),
                    written.oneWayLines().size() == 1 ? "has" : "have",
                    written.oneWayLines().size() == 1 ? "its" : "their",
                    written.oneWayLines().size() == 1 ? "it is" : "they are"));
        }
        text.append("  The files carry no timestamps: the same run always writes the same bytes.\n");
        return text.toString();
    }
}
