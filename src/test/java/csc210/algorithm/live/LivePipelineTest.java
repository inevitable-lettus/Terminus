package csc210.algorithm.live;

import csc210.algorithm.routing.BusRoute;
import csc210.algorithm.routing.RouteBudget;
import csc210.algorithm.routing.RouteNetwork;
import csc210.algorithm.routing.RouteNetworkBuilder;
import csc210.algorithm.search.AStar;
import csc210.algorithm.search.Dijkstra;
import csc210.algorithm.search.ShortestPathTree;
import csc210.algorithm.search.TransitHeuristic;
import csc210.algorithm.search.TransitPath;
import csc210.algorithm.transit.TransitGraph;
import csc210.algorithm.transit.TransitLink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the whole module against live data, start to finish: fetch, build the
 * graph, price it, design a route network, build the passenger network, answer
 * journeys. No fixtures anywhere.
 *
 * <p>These deliberately assert <em>properties</em> rather than exact numbers.
 * Live data is not the fixture — the simulator's rate-limited speeds settle
 * differently depending on how it got to 18:00 — so demanding 94.8% coverage to
 * the decimal would produce a test that fails for no reason. What must hold is
 * that routes are continuous, budgets are respected, coverage is high, and A*
 * and Dijkstra agree.
 */
@Tag("live")
class LivePipelineTest {
    /**
     * The distance measure the planner would really choose for {@code graph}.
     *
     * <p>These tests used to name Manhattan outright, which held only while
     * DSTNS served an axis-aligned lattice; on real roads the heuristic refuses
     * to build and the test reported the guard working as a failure. Asking the
     * geometry keeps them true on either map.
     */
    private static TransitHeuristic.Distance measureFor(csc210.algorithm.graph.Graph graph) {
        return TransitHeuristic.isManhattanAdmissibleOn(graph)
                ? TransitHeuristic.Distance.MANHATTAN
                : TransitHeuristic.Distance.EUCLIDEAN;
    }


    @Test
    @DisplayName("the whole pipeline runs on live data and produces a usable network")
    void endToEndOnLiveData() throws IOException, InterruptedException {
        LiveSandbox.World world = LiveSandbox.at(LiveSandbox.DESIGN_TIME);
        RouteBudget budget = RouteBudget.defaults();

        RouteNetwork network = new RouteNetworkBuilder(
                world.graph(), world.costModel(), world.demandModel(), budget).build();

        assertTrue(network.routeCount() > 0, "no routes were built from live data");
        assertTrue(network.coverage() > 0.80,
                "live coverage was only " + network.coverage());

        System.out.printf("live network at %s: %d routes, %.1f%% of demand %.2f%n",
                LiveSandbox.DESIGN_TIME, network.routeCount(),
                100 * network.coverage(), network.totalDemand());
        for (BusRoute route : network.routes()) {
            System.out.printf("  %s: %2d stops, %3d junctions, %4.1f min%n",
                    route.label(), route.stopCount(), route.nodes().length,
                    route.travelMillis() / 60000.0);
        }

        for (BusRoute route : network.routes()) {
            assertTrue(route.travelMillis() <= budget.maxRouteMillis(),
                    route.label() + " exceeds its time budget");
            assertEquals(route.nodes().length - 1, route.edges().length);
            for (int i = 0; i < route.edges().length; i++) {
                int edgeId = route.edges()[i];
                assertTrue(world.costModel().isTraversable(edgeId),
                        route.label() + " traverses closed edge " + edgeId);
                assertEquals(route.nodes()[i], world.graph().edge(edgeId).from(),
                        route.label() + " is not continuous at position " + i);
                assertEquals(route.nodes()[i + 1], world.graph().edge(edgeId).to(),
                        route.label() + " is not continuous at position " + i);
            }
        }

        TransitGraph transit = TransitGraph.of(world.graph(), network, world.costModel());
        assertTrue(transit.vertexCount() > 0);
        assertEquals(transit.vertexCount(), reachableFromFirstVertex(transit),
                "the live passenger network is not fully connected");

        System.out.printf("live passenger network: %d vertices, %d links over %d stops%n",
                transit.vertexCount(), transit.linkCount(), transit.servedStops().length);
    }

    @Test
    @DisplayName("A* and Dijkstra agree on every live journey, with A* doing less work")
    void aStarMatchesDijkstraOnLiveData() throws IOException, InterruptedException {
        LiveSandbox.World world = LiveSandbox.at(LiveSandbox.DESIGN_TIME);
        RouteNetwork network = new RouteNetworkBuilder(world.graph(), world.costModel(),
                world.demandModel(), RouteBudget.defaults()).build();
        TransitGraph transit = TransitGraph.of(world.graph(), network, world.costModel());
        AStar aStar = new AStar(transit);

        long dijkstraExpansions = 0;
        long aStarExpansions = 0;
        int journeys = 0;

        for (int source = 0; source < transit.vertexCount(); source++) {
            for (int goal = 0; goal < transit.vertexCount(); goal++) {
                if (source == goal) {
                    continue;
                }
                TransitPath plain = aStar.find(source, goal, AStar.zeroHeuristic());
                TransitPath aimed = aStar.find(source, goal,
                        TransitHeuristic.towards(transit, goal,
                                measureFor(world.graph()), world.graph())::estimateMillis);

                assertEquals(plain.found(), aimed.found());
                if (plain.found()) {
                    assertEquals(plain.totalMillis(), aimed.totalMillis(),
                            "A* and Dijkstra disagree on live journey " + source + " -> " + goal);
                }
                dijkstraExpansions += plain.verticesExpanded();
                aStarExpansions += aimed.verticesExpanded();
                journeys++;
            }
        }

        assertTrue(aStarExpansions < dijkstraExpansions, "aiming the search did not reduce work");
        System.out.printf("live: %d journeys, Dijkstra %d expansions, A* %d — %.1f%% fewer%n",
                journeys, dijkstraExpansions, aStarExpansions,
                100.0 * (dijkstraExpansions - aStarExpansions) / dijkstraExpansions);
    }

    @Test
    @DisplayName("the heuristic never overestimates on live costs either")
    void heuristicStaysAdmissibleOnLiveData() throws IOException, InterruptedException {
        LiveSandbox.World world = LiveSandbox.at(LiveSandbox.DESIGN_TIME);
        RouteNetwork network = new RouteNetworkBuilder(world.graph(), world.costModel(),
                world.demandModel(), RouteBudget.defaults()).build();
        TransitGraph transit = TransitGraph.of(world.graph(), network, world.costModel());
        AStar aStar = new AStar(transit);

        // Admissibility is a property of the map's geometry and the speed ceiling,
        // not of the fixture, so it must hold against live costs unchanged.
        for (int goal = 0; goal < transit.vertexCount(); goal++) {
            TransitHeuristic guess = TransitHeuristic.towards(transit, goal,
                    measureFor(world.graph()), world.graph());
            for (int source = 0; source < transit.vertexCount(); source++) {
                if (source == goal) {
                    continue;
                }
                TransitPath truth = aStar.find(source, goal, AStar.zeroHeuristic());
                if (truth.found()) {
                    assertTrue(guess.estimateMillis(source) <= truth.totalMillis(),
                            "heuristic overestimates live cost from " + source + " to " + goal);
                }
            }
        }
    }

    @Test
    @DisplayName("closing a road live removes it from the search and reroutes around it")
    void aLiveClosureIsHonoured() throws IOException, InterruptedException {
        LiveSandbox.World world = LiveSandbox.at(LiveSandbox.DESIGN_TIME);

        // Pick an edge that the shortest path from 0 to 60 actually uses, so the
        // closure has something to change. A road nothing routes through would
        // make this test pass without proving anything.
        Dijkstra dijkstra = new Dijkstra(world.graph(), world.costModel());
        ShortestPathTree before = dijkstra.from(0);
        int[] pathEdges = before.edgePathTo(60);
        assertTrue(pathEdges.length > 0, "no path from 0 to 60 to disrupt");
        int edgeToClose = pathEdges[pathEdges.length / 2];
        long costBefore = before.costTo(60);

        try {
            world.client().setEdgeClosed(edgeToClose, true);
            LiveSandbox.World closed = LiveSandbox.reread(world.client(), world.topology());

            assertTrue(!closed.costModel().isTraversable(edgeToClose),
                    "edge " + edgeToClose + " was closed but the cost model still allows it");

            ShortestPathTree after = new Dijkstra(closed.graph(), closed.costModel()).from(0);
            assertTrue(after.isReachable(60), "closing one road should not strand the destination on a grid");
            for (int edgeId : after.edgePathTo(60)) {
                assertTrue(edgeId != edgeToClose, "the new path still uses the closed road");
            }
            assertTrue(after.costTo(60) >= costBefore,
                    "rerouting around a closure cannot be cheaper than the original path");

            System.out.printf("closed edge %d: journey 0->60 went from %d ms to %d ms%n",
                    edgeToClose, costBefore, after.costTo(60));
        } finally {
            // Leave the simulator as we found it, whatever happened above.
            world.client().setEdgeClosed(edgeToClose, false);
        }
    }

    @Test
    @DisplayName("the same seed and time twice give the same network")
    void livePipelineIsReproducible() throws IOException, InterruptedException {
        RouteNetwork first = buildOnce();
        RouteNetwork second = buildOnce();

        assertEquals(first.routeCount(), second.routeCount(),
                "two runs at the same seed and virtual time produced different route counts");
        for (int i = 0; i < first.routeCount(); i++) {
            org.junit.jupiter.api.Assertions.assertArrayEquals(
                    first.routes().get(i).nodes(), second.routes().get(i).nodes(),
                    "route " + i + " differs between two identical runs");
        }
    }

    private static RouteNetwork buildOnce() throws IOException, InterruptedException {
        LiveSandbox.World world = LiveSandbox.at(LiveSandbox.DESIGN_TIME);
        return new RouteNetworkBuilder(world.graph(), world.costModel(),
                world.demandModel(), RouteBudget.defaults()).build();
    }

    private static int reachableFromFirstVertex(TransitGraph graph) {
        boolean[] seen = new boolean[graph.vertexCount()];
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(0);
        seen[0] = true;
        int reached = 1;
        while (!queue.isEmpty()) {
            for (TransitLink link : graph.outgoing(queue.poll())) {
                if (!seen[link.to()]) {
                    seen[link.to()] = true;
                    reached++;
                    queue.add(link.to());
                }
            }
        }
        return reached;
    }
}
