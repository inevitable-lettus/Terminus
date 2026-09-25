package csc210.algorithm.cli;

import csc210.algorithm.Fixtures;
import csc210.algorithm.client.SnapshotData;
import csc210.algorithm.client.TopologyData;
import csc210.algorithm.cost.CostModel;
import csc210.algorithm.demand.DemandModel;
import csc210.algorithm.demand.StopSet;
import csc210.algorithm.export.RouteExporter;
import csc210.algorithm.graph.Graph;
import csc210.algorithm.model.Node;
import csc210.algorithm.model.Position;
import csc210.algorithm.routing.BusRoute;
import csc210.algorithm.routing.RouteBudget;
import csc210.algorithm.routing.RouteNetwork;
import csc210.algorithm.routing.RouteNetworkBuilder;
import csc210.algorithm.transit.TransitGraph;
import csc210.algorithm.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The wording of every report, on the Berlin fixtures and on hand-built edge cases. */
class ReportTest {

    private static World world;
    private static RouteBudget budget;
    private static RouteNetwork network;
    private static final RunInfo RUN = new RunInfo("run_x", "0x2026", "18:00:00", true);

    @BeforeAll
    static void build() {
        world = Fixtures.world1800();
        budget = world.budget();
        network = new RouteNetworkBuilder(world.graph(), world.costModel(), world.demandModel(), budget).build();
    }

    private static RouteNetwork build(RouteBudget with) {
        return new RouteNetworkBuilder(world.graph(), world.costModel(), world.demandModel(), with).build();
    }

    // --- header ---------------------------------------------------------------

    @Test
    void theHeaderSaysWhatTheResultStandsOn() {
        String text = Report.header(RUN, world, budget, Options.parse(new String[]{"network"}));

        assertTrue(text.contains("run_x") && text.contains("seed 0x2026") && text.contains("time 18:00:00"), text);
        assertTrue(text.contains("279 junctions, 612 roads"), text);
        assertTrue(text.contains("139 roads excluded (read from /view/edges)"), text);
        assertTrue(text.contains("flagged, ") && text.contains("after merging"), text);
        assertTrue(text.contains("BUILDINGS"), text);
        assertTrue(text.contains("walk") && text.contains("(derived)"), text);
        assertTrue(text.contains("attached; clock untouched"), text);
    }

    @Test
    void anOverriddenBudgetFieldIsLabelledAsSetNotDerived() {
        Options options = Options.parse(new String[]{"network", "--walk", "300", "--routes", "2", "--minutes", "5"});
        String text = Report.header(RUN, world, Main2.overrides(budget, options), options);

        assertTrue(text.contains("walk 300 m (set)"), text);
        assertTrue(text.contains("up to 2 lines (set)"), text);
        assertTrue(text.contains("5 min per line (set)"), text);
    }

    @Test
    void aWalkShorterThanTheMergeRadiusIsFlagged() {
        String text = Report.header(RUN, world, budget.withWalkingRadius(20.0), Options.parse(new String[]{"network"}));

        assertTrue(text.contains("merged-away stops counted as uncovered"), text);
    }

    @Test
    void aRunWeStartedIsNotDescribedAsAttached() {
        String text = Report.header(new RunInfo(null, "8230", "18:00:00", false), world, budget,
                Options.parse(new String[]{"network"}));

        assertTrue(text.contains("(unnamed)") && text.contains("started by this command"), text);
    }

    // --- network --------------------------------------------------------------

    @Test
    void theNetworkListsEveryLineAndSummarisesHowItEnded() {
        String text = Report.network(world, network, budget);

        for (BusRoute route : network.routes()) {
            assertTrue(text.contains(route.label()), route.label() + " missing from:\n" + text);
        }
        assertTrue(text.contains("cover " + String.format("%.1f", network.coverage() * 100.0) + "% of demand"), text);
        assertTrue(text.contains("stopped"), text);
        assertTrue(text.contains("interchanges:"), text);
        assertTrue(text.contains("passenger network:"), text);
    }

    @Test
    void whenTheCapIsHitTheReasonSaysSoAndNamesTheFlagThatRaisesIt() {
        // A full-coverage target, so one line cannot meet it and the cap is what stops the builder.
        RouteBudget capped = new RouteBudget(budget.maxRouteMillis(), 1, 1.0, budget.walkingRadiusM());
        RouteNetwork one = build(capped);

        String reason = Report.stopReason(one, capped, world);

        assertEquals(1, one.routeCount());
        assertTrue(reason.contains("cap of 1 lines") && reason.contains("--routes"), reason);
    }

    @Test
    void whenTheTargetIsMetTheReasonSaysSo() {
        RouteBudget easy = new RouteBudget(budget.maxRouteMillis(), budget.maxRoutes(), 0.01, budget.walkingRadiusM());

        String reason = Report.stopReason(build(easy), easy, world);

        assertTrue(reason.contains("coverage target was met"), reason);
    }

    @Test
    void whenNothingIsReachableTheReasonNamesTheTimeBudget() {
        RouteBudget tiny = new RouteBudget(1_000L, 50, 0.9, budget.walkingRadiusM());
        RouteNetwork none = build(tiny);

        String text = Report.network(world, none, tiny);

        assertEquals(0, none.routeCount());
        assertTrue(text.startsWith("No line could be built."), text);
        assertTrue(text.contains("--minutes"), text);
    }

    @Test
    void aWorldWithNoStopsSaysSoInsteadOfPrintingAnEmptyTable() {
        World empty = worldWithNoStops();

        assertEquals("No bus stops in this world, so there is nothing to design a network around.\n",
                Report.network(empty, new RouteNetwork(List.of(), new int[0], 0, 0), RouteBudget.defaults()));
        assertEquals("No bus stops in this world.\n",
                Report.stops(empty, new RouteNetwork(List.of(), new int[0], 0, 0), 5));
    }

    @Test
    void interchangeStopsAreExactlyThoseServedByMoreThanOneLine() {
        java.util.Map<Integer, Integer> lines = new java.util.HashMap<>();
        for (BusRoute route : network.routes()) {
            for (int stop : route.stops()) {
                lines.merge(stop, 1, Integer::sum);
            }
        }
        for (int stop : Report.interchangeStops(network)) {
            assertTrue(lines.get(stop) > 1, "stop " + stop);
        }
        long expected = lines.values().stream().filter(count -> count > 1).count();
        assertEquals(expected, Report.interchangeStops(network).size());
    }

    @Test
    void piecesCountsSeparateChunksOfThePassengerNetwork() {
        TransitGraph joined = TransitGraph.of(world.graph(), network, world.costModel());
        TransitGraph apart = TransitGraph.of(world.graph(), network, world.costModel(), 0.0);

        assertTrue(Report.pieces(joined) >= 1);
        assertTrue(Report.pieces(apart) >= Report.pieces(joined),
                "removing every walk link cannot make the network more connected");
        assertEquals(0, Report.pieces(TransitGraph.of(world.graph(), build(new RouteBudget(1_000L, 5, 0.9, 100)),
                world.costModel())));
    }

    // --- stops ----------------------------------------------------------------

    @Test
    void stopsAreListedBusiestFirstWithTheLinesThatServeThem() {
        String text = Report.stops(world, network, 3);
        String[] lines = text.split("\n");

        assertTrue(text.startsWith("Busiest 3 of "), text);
        assertEquals(5, lines.length, "title, column heads, three stops:\n" + text);
        int busiest = world.demandModel().stopsByDemandDescending()[0];
        assertTrue(lines[2].contains(String.valueOf(busiest)), lines[2]);
    }

    @Test
    void aStopNoLineReachesIsMarkedUnserved() {
        RouteNetwork none = build(new RouteBudget(1_000L, 5, 0.9, 100));

        assertTrue(Report.stops(world, none, 5).contains("unserved"));
    }

    // --- journey --------------------------------------------------------------

    @Test
    void aJourneyIsNumberedStepsWithARunningClockAndAWalkingComparison() {
        String text = Report.journey(world, network, 0, 119, "18:00:00");

        assertTrue(text.startsWith("Journey  junction 0 -> junction 119  at 18:00:00"), text);
        assertTrue(text.contains("straight line"), text);
        if (text.contains("No journey found")) {
            return;   // nothing to number; the unreachable wording is tested below
        }
        assertTrue(text.contains("   1  "), text);
        assertTrue(text.contains("Total ") && text.contains("Walking straight there would take about"), text);
        assertTrue(text.contains("of " + TransitGraph.of(world.graph(), network, world.costModel()).vertexCount()
                + " transit vertices"), text);
    }

    @Test
    void sameJunctionIsAlreadyThere() {
        assertTrue(Report.journey(world, network, 5, 5, "18:00:00").contains("You are already there."));
    }

    @Test
    void noJourneyExplainsWhetherAStopIsNearEitherEnd() {
        RouteNetwork none = build(new RouteBudget(1_000L, 5, 0.9, 100));

        // Two junctions far enough apart that walking the whole way is not on offer.
        int west = 0, east = 0;
        for (int id = 0; id < world.graph().nodeCount(); id++) {
            double x = world.graph().node(id).position().xM();
            if (x < world.graph().node(west).position().xM()) west = id;
            if (x > world.graph().node(east).position().xM()) east = id;
        }

        String text = Report.journey(world, none, west, east, "18:00:00");

        assertTrue(text.contains("No journey found."), text);
        assertTrue(text.contains("No served stop is within"), text);
    }

    @Test
    void outOfRangeJunctionsNameTheRangeAndPointAtTheStopsCommand() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> Report.journey(world, network, -1, 5, "18:00:00"));

        assertTrue(thrown.getMessage().startsWith("FROM must be a junction id in [0, 279)"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("./bus stops"), thrown.getMessage());
    }

    // --- export ---------------------------------------------------------------

    @Test
    void exportSaysWhatWasWrittenAndWhichLinesAreOneWay() {
        String text = Report.exported(new RouteExporter.Written(Path.of("out/routes.json"), Path.of("out/routes.geojson"),
                3, List.of("R1")));

        assertTrue(text.contains("Wrote 3 lines"), text);
        assertTrue(text.contains("R1 has no road back through its stops"), text);
        assertTrue(text.contains("same bytes"), text);
        assertFalse(Report.exported(new RouteExporter.Written(Path.of("a"), Path.of("b"), 1, List.of()))
                .contains("cannot be driven back"));
    }

    // --- helpers --------------------------------------------------------------

    /** The same override rule the command line applies, without depending on package-private Main. */
    private static final class Main2 {
        static RouteBudget overrides(RouteBudget base, Options options) {
            RouteBudget out = base;
            if (options.minutes != null) out = out.withMaxRouteMillis(options.minutes * 60_000L);
            if (options.routes != null) out = out.withMaxRoutes(options.routes);
            if (options.walkMetres != null) out = out.withWalkingRadius(options.walkMetres);
            return out;
        }
    }

    private static World worldWithNoStops() {
        Node only = new Node(0, 1L, new Position(0, 0, 0, 0), 0, false, 0, false, 0, 0, 0, null, 0, 0);
        Graph graph = Graph.of(List.of(only), List.of());
        StopSet stops = StopSet.of(graph);
        SnapshotData snapshot = new SnapshotData(List.of(new csc210.algorithm.model.NodeState(0, 0, 0, 0)), List.of(), 0);
        return new World(new TopologyData(List.of(only), List.of(), null, "sha256:abc", 0, 0), snapshot, graph,
                CostModel.ofCosts(new long[0]), stops, DemandModel.of(graph, snapshot, stops, null), 0, true, null,
                RouteBudget.defaults());
    }
}
