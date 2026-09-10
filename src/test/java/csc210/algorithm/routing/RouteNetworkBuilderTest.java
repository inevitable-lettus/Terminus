package csc210.algorithm.routing;

import csc210.algorithm.Fixtures;
import csc210.algorithm.client.SnapshotData;
import csc210.algorithm.client.TopologyData;
import csc210.algorithm.cost.CostModel;
import csc210.algorithm.demand.DemandModel;
import csc210.algorithm.graph.Graph;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
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
        costModel = CostModel.of(graph, snapshot);
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

    private static int indexOf(int[] values, int wanted, int from) {
        for (int i = from; i < values.length; i++) {
            if (values[i] == wanted) {
                return i;
            }
        }
        return -1;
    }
}
