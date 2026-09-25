package csc210.algorithm.routing;

import csc210.algorithm.Fixtures;
import csc210.algorithm.client.SnapshotData;
import csc210.algorithm.client.TopologyData;
import csc210.algorithm.cost.CostModel;
import csc210.algorithm.demand.DemandModel;
import csc210.algorithm.graph.Graph;
import csc210.algorithm.model.Edge;
import csc210.algorithm.model.Node;
import csc210.algorithm.model.NodeState;
import csc210.algorithm.model.Position;
import csc210.algorithm.search.AStar;
import csc210.algorithm.transit.TransitGraph;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Steps 4.2 and 4.3. Checks what the build order asks for: routes are actually
 * produced, coverage is reported, no stop is claimed twice, every route is a
 * genuine node-by-node traversal of real edges, and sweeping the budget moves
 * the route count monotonically.
 */
class RouteNetworkBuilderTest {

    private static Graph graph;
    private static CostModel costModel;
    private static DemandModel demandModel;

    @BeforeAll
    static void load() {
        TopologyData topology = Fixtures.topology();
        SnapshotData snapshot = Fixtures.snapshotAt1800();
        graph = Graph.of(topology.nodes(), topology.edges());
        costModel = CostModel.of(graph, snapshot, Fixtures.oneWayEdgeIdsAt1800());
        demandModel = DemandModel.of(graph, snapshot);
    }

    private static RouteNetwork buildWith(RouteBudget budget) {
        return new RouteNetworkBuilder(graph, costModel, demandModel, budget).build();
    }

    @Test
    void producesRoutesAndReportsCoverage() {
        RouteNetwork network = buildWith(RouteBudget.defaults());

        assertTrue(network.routeCount() > 0, "no routes were built at all");
        assertTrue(network.coverage() > 0.5,
                "coverage was only " + network.coverage() + " of total demand");

        System.out.printf("network at 18:00: %d routes, coverage %.1f%% of demand %.2f%n",
                network.routeCount(), 100 * network.coverage(), network.totalDemand());
        for (BusRoute route : network.routes()) {
            System.out.printf("  %s: %2d stops, %3d nodes, %5.1f min, demand %.2f%n",
                    route.label(), route.stopCount(), route.nodes().length,
                    route.travelMillis() / 60000.0, route.demandCovered());
        }
    }

    /**
     * The build order says "no node covered twice". That means no stop's demand
     * is counted twice toward coverage — it does <em>not</em> mean two lines may
     * not share a stop. They must be able to: a shared stop is exactly where a
     * passenger changes buses, and without one the transfer edges in step 4.4
     * would have nowhere to attach.
     */
    @Test
    void demandIsNeverCountedTwice() {
        RouteNetwork network = buildWith(RouteBudget.defaults());

        double summedOverCoveredStops = 0.0;
        Set<Integer> seen = new HashSet<>();
        for (int stop : network.coveredStops()) {
            assertTrue(seen.add(stop), "stop " + stop + " appears twice in the covered set");
            summedOverCoveredStops += demandModel.demandAt(stop);
        }

        assertEquals(summedOverCoveredStops, network.demandCovered(), 1e-9,
                "reported coverage does not match the demand of the stops actually covered");
        assertTrue(network.demandCovered() <= network.totalDemand() + 1e-9);
    }

    /**
     * The constraint {@code POST /control/transit/route} enforces: consecutive
     * nodes must be joined by a single directed edge, because the server does
     * not path-fill between stops. If this fails, publishing the route back to
     * DSTNS is rejected at the first gap.
     */
    @Test
    void everyRouteIsAContinuousTraversalOfRealEdges() {
        RouteNetwork network = buildWith(RouteBudget.defaults());

        for (BusRoute route : network.routes()) {
            assertEquals(route.nodes().length - 1, route.edges().length);
            for (int i = 0; i < route.edges().length; i++) {
                int edgeId = route.edges()[i];
                assertTrue(costModel.isTraversable(edgeId),
                        route.label() + " traverses closed edge " + edgeId);
                assertEquals(route.nodes()[i], graph.edge(edgeId).from());
                assertEquals(route.nodes()[i + 1], graph.edge(edgeId).to());
            }
        }
    }

    @Test
    void routeStopsAppearInTraversalOrder() {
        RouteNetwork network = buildWith(RouteBudget.defaults());

        for (BusRoute route : network.routes()) {
            assertTrue(route.stopCount() >= 2, route.label() + " is not a route, it is one stop");
            int searchFrom = 0;
            for (int stop : route.stops()) {
                int index = indexOf(route.nodes(), stop, searchFrom);
                assertTrue(index >= 0, "stop " + stop + " is not on " + route.label() + "'s traversal");
                searchFrom = index;
            }
        }
    }

    @Test
    void noRouteExceedsItsTimeBudget() {
        RouteBudget budget = RouteBudget.defaults();
        RouteNetwork network = buildWith(budget);

        for (BusRoute route : network.routes()) {
            assertTrue(route.travelMillis() <= budget.maxRouteMillis(),
                    route.label() + " runs " + route.travelMillis() + " ms over a budget of "
                            + budget.maxRouteMillis());
        }
    }

    /** Step 4.3's proof: a tighter budget cannot need fewer routes to cover the same demand. */
    @Test
    void tighteningTheBudgetDoesNotReduceTheRouteCount() {
        long[] budgetsMinutes = {4, 5, 6, 8, 10, 15, 25};
        int previousCount = 0;

        System.out.println("budget sweep at 18:00:");
        for (int i = budgetsMinutes.length - 1; i >= 0; i--) {
            RouteBudget budget = new RouteBudget(budgetsMinutes[i] * 60_000L, 20, 0.90,
                    RouteBudget.defaults().walkingRadiusM());
            RouteNetwork network = buildWith(budget);
            System.out.printf("  %2d min per route -> %d routes, coverage %.1f%%%n",
                    budgetsMinutes[i], network.routeCount(), 100 * network.coverage());
            assertTrue(network.routeCount() >= previousCount,
                    "cutting the budget to " + budgetsMinutes[i] + " min reduced the route count from "
                            + previousCount + " to " + network.routeCount());
            previousCount = network.routeCount();
        }
    }

    @Test
    void theRouteCeilingIsRespected() {
        RouteNetwork network = buildWith(new RouteBudget(10 * 60_000L, 2, 1.0, 400.0));

        assertTrue(network.routeCount() <= 2);
    }

    @Test
    void theSameSnapshotAlwaysProducesTheSameNetwork() {
        RouteNetwork first = buildWith(RouteBudget.defaults());
        RouteNetwork second = buildWith(RouteBudget.defaults());

        assertEquals(first.routeCount(), second.routeCount());
        for (int i = 0; i < first.routeCount(); i++) {
            org.junit.jupiter.api.Assertions.assertArrayEquals(
                    first.routes().get(i).nodes(), second.routes().get(i).nodes());
        }
        assertEquals(first.coverage(), second.coverage());
    }

    /**
     * Regression: a route that revisits a stop (A→B→A→C) used to collapse to
     * its unique stops, and the transit graph then priced the A→C ride as the
     * long way round through B instead of the real direct segment. On this
     * hand-built graph the direct A→C road costs 1000ms; the wrong answer was
     * 3000ms (A→B→A→C driven in full).
     */
    @Test
    void revisitedStopKeepsItsOwnRideSegment() {
        Graph g = smallGraph(new double[] {0, 10, -10}, new double[] {3, 2, 1},
                new int[][] {{0, 1}, {1, 0}, {0, 2}, {2, 0}});
        CostModel c = CostModel.ofCosts(new long[] {1000, 1000, 1000, 1000});
        RouteNetwork network = new RouteNetworkBuilder(g, c, DemandModel.of(g, snapshotOf(g)),
                new RouteBudget(10_000L, 1, 1.0, 0.0)).build();
        BusRoute route = network.routes().getFirst();
        assertTrue(hasRepeatedStop(route), "test fixture no longer produces a revisit — repro invalid");

        TransitGraph transit = TransitGraph.of(g, network, c, 0);
        long aToC = new AStar(transit).find(transit.verticesAt(0)[0], transit.verticesAt(2)[0],
                AStar.zeroHeuristic()).totalMillis();

        assertEquals(1000L, aToC, "A->C must be the direct segment, not the full revisited traversal");
    }

    /**
     * Regression: growing a route used to mark its hub (and walk-neighbours)
     * covered before the route was known to succeed, and that pre-marking
     * also made those very neighbours ineligible as extension targets. A
     * tight budget between two connected, mutually-walkable stops used to
     * report zero routes with both stops falsely marked covered.
     */
    @Test
    void failedRouteAttemptLeavesNothingMarkedCovered() {
        Graph g = smallGraph(new double[] {0, 100}, new double[] {3, 2}, new int[][] {{0, 1}, {1, 0}});
        CostModel c = CostModel.ofCosts(new long[] {1000, 1000});
        DemandModel demand = DemandModel.of(g, snapshotOf(g));

        RouteNetwork tooTight = new RouteNetworkBuilder(g, c, demand,
                new RouteBudget(500L, 8, 1.0, 0.0)).build();
        assertEquals(0, tooTight.routeCount());
        assertEquals(0, tooTight.coveredStops().length,
                "a route that could not be built must not leave stops marked covered");
        assertEquals(0.0, tooTight.demandCovered());
    }

    /**
     * A generous budget does not change the false-coverage-leak behaviour
     * when the walking radius already claims both stops on the hub's own
     * account: with nothing left to gain by also driving there, the greedy
     * scorer correctly builds no route — the regression is specifically that
     * this must stay a real, consistent zero rather than "zero routes, stops
     * marked covered anyway".
     */
    @Test
    void stopsAlreadyMutuallyWalkableStayConsistentUnderAGenerousBudget() {
        Graph g = smallGraph(new double[] {0, 100}, new double[] {3, 2}, new int[][] {{0, 1}, {1, 0}});
        CostModel c = CostModel.ofCosts(new long[] {1000, 1000});
        DemandModel demand = DemandModel.of(g, snapshotOf(g));

        RouteNetwork network = new RouteNetworkBuilder(g, c, demand,
                new RouteBudget(10_000L, 8, 1.0, 250.0)).build();

        assertEquals(network.routeCount() == 0, network.coveredStops().length == 0,
                "a stop may only be covered if some accepted route reaches it directly or by walking");
        assertTrue(network.demandCovered() <= network.totalDemand() + 1e-9);
    }

    /**
     * The companion "normal" case: once the walking radius no longer claims
     * the second stop for free (radius 0 here), driving to it is genuine new
     * demand, and the greedy scorer builds the route a sufficient budget
     * allows.
     */
    @Test
    void aNormalFeasibleTwoStopLineIsBuilt() {
        Graph g = smallGraph(new double[] {0, 100}, new double[] {3, 2}, new int[][] {{0, 1}, {1, 0}});
        CostModel c = CostModel.ofCosts(new long[] {1000, 1000});
        DemandModel demand = DemandModel.of(g, snapshotOf(g));

        RouteNetwork network = new RouteNetworkBuilder(g, c, demand,
                new RouteBudget(10_000L, 8, 1.0, 0.0)).build();

        assertEquals(1, network.routeCount());
        assertEquals(2, network.coveredStops().length);
        assertEquals(5.0, network.demandCovered(), 1e-9);
    }

    // Every node in these hand-built fixtures is a bus stop, so a repeated
    // node id in the traversal is always a repeated stop.
    private static boolean hasRepeatedStop(BusRoute route) {
        Set<Integer> seen = new HashSet<>();
        for (int node : route.nodes()) {
            if (!seen.add(node)) {
                return true;
            }
        }
        return false;
    }

    /** Builds a small graph in the shape the audit's minimal reproductions use. */
    private static Graph smallGraph(double[] xs, double[] buildingImpact, int[][] pairs) {
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        for (int i = 0; i < xs.length; i++) {
            nodes.add(new Node(i, 1_000_000L + i, new Position(0.0, 0.0, xs[i], 0.0),
                    0, true, 0.0, false, 0, 0, 0, "store", buildingImpact[i], 0.0));
        }
        for (int i = 0; i < pairs.length; i++) {
            edges.add(new Edge(i, pairs[i][0], pairs[i][1], i ^ 1, "residential", 1,
                    Math.abs(xs[pairs[i][0]] - xs[pairs[i][1]]), 16.67));
        }
        return Graph.of(nodes, edges);
    }

    private static SnapshotData snapshotOf(Graph g) {
        List<NodeState> states = new ArrayList<>();
        for (int i = 0; i < g.nodeCount(); i++) {
            states.add(new NodeState(i, 1, 0, 0));
        }
        return new SnapshotData(states, List.of(), 1);
    }

    private static int indexOf(int[] values, int wanted, int from) {
        for (int i = from; i < values.length; i++) {
            if (values[i] == wanted) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Regression, from the first live run against DSTNS 2.0: with the return
     * modelled as the outbound path read backwards, all 6 lines on the real
     * city came out one-way, because they follow dual carriageways — two
     * one-way ways a few metres apart. Routed on its own, the return uses the
     * other carriageway; and a line only reaches for stops in its hub's strongly
     * connected component, so none ends in a pocket it cannot drive out of.
     */
    @Test
    void onARealCityWithDualCarriagewaysLinesStillGetAReturnService() {
        csc210.algorithm.world.World world = Fixtures.v2World1800();
        RouteNetwork network = new RouteNetworkBuilder(world.graph(), world.costModel(), world.demandModel(),
                world.budget()).build();

        assertTrue(network.routeCount() > 0);
        int bothWays = 0;
        for (BusRoute route : network.routes()) {
            if (route.runsBothWays()) {
                bothWays++;
                int[] back = route.back().nodes();
                assertEquals(route.nodes()[route.nodes().length - 1], back[0]);
                assertEquals(route.nodes()[0], back[back.length - 1]);
                for (int edge : route.back().edges()) {
                    assertTrue(world.costModel().isTraversable(edge),
                            route.label() + "'s return drives edge " + edge + ", which no bus may use");
                }
            }
        }
        assertEquals(network.routeCount(), bothWays,
                "a line only reaches for stops it can come back from, so every line runs both ways");
    }
}
