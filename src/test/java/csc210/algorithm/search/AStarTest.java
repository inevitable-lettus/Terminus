package csc210.algorithm.search;

import csc210.algorithm.Fixtures;
import csc210.algorithm.client.SnapshotData;
import csc210.algorithm.client.TopologyData;
import csc210.algorithm.cost.CostModel;
import csc210.algorithm.demand.DemandModel;
import csc210.algorithm.graph.Graph;
import csc210.algorithm.routing.RouteBudget;
import csc210.algorithm.routing.RouteNetwork;
import csc210.algorithm.routing.RouteNetworkBuilder;
import csc210.algorithm.transit.TransitGraph;
import csc210.algorithm.transit.TransitLink;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Steps 5.1, 5.2 and 5.3. A* must return exactly the same costs as Dijkstra —
 * same optimum, less work — and its heuristic must be provably admissible on
 * paths Dijkstra has already solved exactly.
 */
class AStarTest {

    private static Graph roadGraph;
    private static TransitGraph transitGraph;
    private static AStar aStar;

    @BeforeAll
    static void build() {
        TopologyData topology = Fixtures.topology();
        SnapshotData snapshot = Fixtures.snapshotAt1800();
        roadGraph = Graph.of(topology.nodes(), topology.edges());
        CostModel costModel = CostModel.of(roadGraph, snapshot);
        DemandModel demandModel = DemandModel.of(roadGraph, snapshot);
        RouteNetwork network = new RouteNetworkBuilder(roadGraph, costModel, demandModel,
                RouteBudget.defaults()).build();
        transitGraph = TransitGraph.of(roadGraph, network, costModel);
        aStar = new AStar(transitGraph);
    }

    private static TransitHeuristic heuristic(int goal, TransitHeuristic.Distance distance) {
        return TransitHeuristic.towards(transitGraph, goal, distance, roadGraph);
    }

    /** Step 5.1's proof: same answer as Dijkstra on every pair, for less work. */
    @Test
    void returnsTheSameCostAsDijkstraEverywhere() {
        long expansionsWithoutGuess = 0;
        long expansionsWithManhattan = 0;
        int pairs = 0;

        for (int source = 0; source < transitGraph.vertexCount(); source++) {
            for (int goal = 0; goal < transitGraph.vertexCount(); goal++) {
                if (source == goal) {
                    continue;
                }
                TransitPath asDijkstra = aStar.find(source, goal, AStar.zeroHeuristic());
                TransitPath withGuess = aStar.find(source, goal,
                        heuristic(goal, TransitHeuristic.Distance.MANHATTAN)::estimateMillis);

                assertTrue(asDijkstra.found() && withGuess.found());
                assertEquals(asDijkstra.totalMillis(), withGuess.totalMillis(),
                        "A* and Dijkstra disagree on the cost from " + source + " to " + goal);

                expansionsWithoutGuess += asDijkstra.verticesExpanded();
                expansionsWithManhattan += withGuess.verticesExpanded();
                pairs++;
            }
        }

        assertTrue(expansionsWithManhattan < expansionsWithoutGuess,
                "aiming the search should expand fewer vertices, not more");
        System.out.printf("over %d journeys: Dijkstra %d expansions, A* (Manhattan) %d — %.1f%% fewer%n",
                pairs, expansionsWithoutGuess, expansionsWithManhattan,
                100.0 * (expansionsWithoutGuess - expansionsWithManhattan) / expansionsWithoutGuess);
    }

    /** Decision D3: Manhattan is the tighter of the two on an axis-aligned map. */
    @Test
    void manhattanExpandsFewerVerticesThanEuclidean() {
        long euclidean = 0;
        long manhattan = 0;

        for (int source = 0; source < transitGraph.vertexCount(); source += 3) {
            for (int goal = 0; goal < transitGraph.vertexCount(); goal += 3) {
                if (source == goal) {
                    continue;
                }
                euclidean += aStar.find(source, goal,
                        heuristic(goal, TransitHeuristic.Distance.EUCLIDEAN)::estimateMillis).verticesExpanded();
                manhattan += aStar.find(source, goal,
                        heuristic(goal, TransitHeuristic.Distance.MANHATTAN)::estimateMillis).verticesExpanded();
            }
        }

        assertTrue(manhattan <= euclidean,
                "Manhattan is the tighter bound here and should not expand more: "
                        + manhattan + " vs " + euclidean);
        System.out.printf("expansions on a sampled grid: Euclidean %d, Manhattan %d%n", euclidean, manhattan);
    }

    /**
     * Step 5.2's proof, and the one that actually matters: on paths Dijkstra has
     * already solved exactly, the guess never exceeds the true remaining cost.
     * Checked for both distance forms, at every vertex of every path.
     */
    @Test
    void theHeuristicNeverOverestimatesWhatIsLeft() {
        for (TransitHeuristic.Distance distance : TransitHeuristic.Distance.values()) {
            long worstSlack = Long.MAX_VALUE;

            for (int goal = 0; goal < transitGraph.vertexCount(); goal++) {
                TransitHeuristic guess = heuristic(goal, distance);
                for (int source = 0; source < transitGraph.vertexCount(); source++) {
                    if (source == goal) {
                        continue;
                    }
                    // Dijkstra's answer is the true remaining cost, by definition.
                    long trueRemaining = aStar.find(source, goal, AStar.zeroHeuristic()).totalMillis();
                    long estimate = guess.estimateMillis(source);

                    assertTrue(estimate <= trueRemaining,
                            distance + " overestimates from " + source + " to " + goal
                                    + ": guessed " + estimate + " but the truth is " + trueRemaining);
                    worstSlack = Math.min(worstSlack, trueRemaining - estimate);
                }
            }
            System.out.printf("%s: closest the guess ever came to the truth was %d ms%n", distance, worstSlack);
        }
    }

    /**
     * Consistency, the edge-by-edge form: crossing one link may not drop the
     * guess by more than that link costs. This is what lets A* settle a vertex
     * once and never look at it again.
     */
    @Test
    void theHeuristicIsConsistentAcrossEveryLink() {
        for (TransitHeuristic.Distance distance : TransitHeuristic.Distance.values()) {
            for (int goal = 0; goal < transitGraph.vertexCount(); goal++) {
                TransitHeuristic guess = heuristic(goal, distance);
                for (int from = 0; from < transitGraph.vertexCount(); from++) {
                    for (TransitLink link : transitGraph.outgoing(from)) {
                        assertTrue(guess.estimateMillis(from) <= link.millis() + guess.estimateMillis(link.to()),
                                distance + " is inconsistent across " + link + " toward goal " + goal);
                    }
                }
            }
        }
    }

    @Test
    void aJourneyIsAWalkableSequenceOfRealLinks() {
        TransitPath journey = aStar.find(0, transitGraph.vertexCount() - 1,
                heuristic(transitGraph.vertexCount() - 1, TransitHeuristic.Distance.MANHATTAN)::estimateMillis);

        assertTrue(journey.found());
        assertEquals(journey.vertices().length - 1, journey.links().size());

        long summed = 0;
        for (int i = 0; i < journey.links().size(); i++) {
            TransitLink link = journey.links().get(i);
            assertEquals(journey.vertices()[i], link.from());
            assertEquals(journey.vertices()[i + 1], link.to());
            summed += link.millis();
        }
        assertEquals(journey.totalMillis(), summed);

        System.out.printf("sample journey: %d vertices, %d changes, %.1f min%n",
                journey.vertices().length, journey.transfers(), journey.totalMillis() / 60000.0);
    }

    /** A* with no guess must be Dijkstra exactly, not merely close to it. */
    @Test
    void aZeroHeuristicMakesItDijkstra() {
        for (int goal = 1; goal < transitGraph.vertexCount(); goal += 7) {
            TransitPath zero = aStar.find(0, goal, AStar.zeroHeuristic());
            TransitPath alsoZero = aStar.find(0, goal, vertex -> 0L);

            assertEquals(zero.totalMillis(), alsoZero.totalMillis());
            assertEquals(zero.verticesExpanded(), alsoZero.verticesExpanded());
        }
    }

    /** The guard from decision D3: Manhattan must refuse a map it would be wrong on. */
    @Test
    void manhattanIsRefusedOnAMapThatIsNotAxisAligned() {
        assertTrue(TransitHeuristic.isManhattanAdmissibleOn(roadGraph),
                "this map is a 180 m axis-aligned lattice; if that changes, the default must change too");

        Graph diagonal = csc210.algorithm.TestGraphs.build(3, new int[][]{{0, 1, 10}, {1, 2, 10}}).graph();
        // TestGraphs lays nodes out along a line, which is axis-aligned; shift one
        // off the axis to make a diagonal edge.
        Graph skewed = csc210.algorithm.TestGraphs.buildSkewed();
        assertTrue(TransitHeuristic.isManhattanAdmissibleOn(diagonal));
        assertTrue(!TransitHeuristic.isManhattanAdmissibleOn(skewed));

        assertThrows(IllegalArgumentException.class,
                () -> TransitHeuristic.towards(transitGraph, 0, TransitHeuristic.Distance.MANHATTAN, skewed));
    }
}
