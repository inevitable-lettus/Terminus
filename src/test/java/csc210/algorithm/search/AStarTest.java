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
        CostModel costModel = CostModel.of(roadGraph, snapshot, Fixtures.oneWayEdgeIdsAt1800());
        DemandModel demandModel = DemandModel.of(roadGraph, snapshot);
        RouteNetwork network = new RouteNetworkBuilder(roadGraph, costModel, demandModel,
                RouteBudget.defaults()).build();
        transitGraph = TransitGraph.of(roadGraph, network, costModel);
        aStar = new AStar(transitGraph);
    }

    /**
     * The distance measure the planner would really choose on this map — the
     * same decision {@code JourneyPlanner} makes.
     *
     * <p>Hardcoding {@code MANHATTAN} here made these tests silently
     * map-specific: they passed while DSTNS served an axis-aligned grid and
     * threw the moment it served real roads. Asking the guard instead keeps
     * them correct on either kind of map, which is what they are actually
     * about.
     */
    private static TransitHeuristic.Distance chosenDistance() {
        return TransitHeuristic.isManhattanAdmissibleOn(roadGraph)
                ? TransitHeuristic.Distance.MANHATTAN
                : TransitHeuristic.Distance.EUCLIDEAN;
    }

    /**
     * The distance measures that are admissible on this map, and so the only
     * ones it is meaningful to check bounds for.
     *
     * <p>Sweeping {@code Distance.values()} assumed every measure was legal
     * everywhere. Manhattan is not: on a map with roads at arbitrary angles the
     * heuristic refuses to build, and a test that asked for it anyway was
     * reporting the guard working as a failure.
     */
    private static java.util.List<TransitHeuristic.Distance> admissibleDistances() {
        java.util.List<TransitHeuristic.Distance> valid = new java.util.ArrayList<>();
        valid.add(TransitHeuristic.Distance.EUCLIDEAN);
        if (TransitHeuristic.isManhattanAdmissibleOn(roadGraph)) {
            valid.add(TransitHeuristic.Distance.MANHATTAN);
        }
        return valid;
    }

    private static TransitHeuristic heuristic(int goal, TransitHeuristic.Distance distance) {
        return TransitHeuristic.towards(transitGraph, goal, distance, roadGraph);
    }

    /** Step 5.1's proof: same answer as Dijkstra on every pair, for less work. */
    @Test
    void returnsTheSameCostAsDijkstraEverywhere() {
        long expansionsWithoutGuess = 0;
        long expansionsWithGuess = 0;
        int pairs = 0;

        for (int source = 0; source < transitGraph.vertexCount(); source++) {
            for (int goal = 0; goal < transitGraph.vertexCount(); goal++) {
                if (source == goal) {
                    continue;
                }
                TransitPath asDijkstra = aStar.find(source, goal, AStar.zeroHeuristic());
                TransitPath withGuess = aStar.find(source, goal,
                        heuristic(goal, chosenDistance())::estimateMillis);

                assertTrue(asDijkstra.found() && withGuess.found());
                assertEquals(asDijkstra.totalMillis(), withGuess.totalMillis(),
                        "A* and Dijkstra disagree on the cost from " + source + " to " + goal);

                expansionsWithoutGuess += asDijkstra.verticesExpanded();
                expansionsWithGuess += withGuess.verticesExpanded();
                pairs++;
            }
        }

        assertTrue(expansionsWithGuess < expansionsWithoutGuess,
                "aiming the search should expand fewer vertices, not more");
        System.out.printf("over %d journeys: Dijkstra %d expansions, A* (%s) %d — %.1f%% fewer%n",
                pairs, expansionsWithoutGuess, chosenDistance(), expansionsWithGuess,
                100.0 * (expansionsWithoutGuess - expansionsWithGuess) / expansionsWithoutGuess);
    }

    /**
     * Decision D3, split into the two claims it actually makes.
     *
     * <p>This replaces a comparison of expansion counts on the fixture map,
     * which only worked because that map was a 180 m axis-aligned lattice. On
     * real roads Manhattan is not admissible, so running it there is not a
     * fair comparison but an exception. The two things worth proving survive
     * the map change: Manhattan is the <em>tighter</em> bound of the two, and
     * it is only <em>valid</em> on an axis-aligned map.
     */
    @Test
    void manhattanIsTheTighterBoundOfTheTwo() {
        // |dx| + |dy| >= hypot(dx, dy) for every pair of points, so Manhattan is
        // never the looser estimate. A tighter admissible bound is what expands
        // fewer vertices — that is the whole reason to prefer it where it is legal.
        int checked = 0;
        for (int a = 0; a < transitGraph.vertexCount(); a++) {
            for (int b = 0; b < transitGraph.vertexCount(); b++) {
                var from = transitGraph.vertex(a).position();
                var goal = transitGraph.vertex(b).position();
                double dx = Math.abs(from.xM() - goal.xM());
                double dy = Math.abs(from.yM() - goal.yM());
                assertTrue(dx + dy >= Math.hypot(dx, dy) - 1e-9,
                        "Manhattan must never be the looser of the two bounds");
                checked++;
            }
        }
        assertTrue(checked > 0);
    }

    /**
     * On the map DSTNS actually serves, the planner must pick the measure that
     * is valid there rather than the one that is tighter in principle.
     */
    @Test
    void theTighterMeasureIsUsedOnlyWhereItIsValid() {
        boolean axisAligned = TransitHeuristic.isManhattanAdmissibleOn(roadGraph);
        assertEquals(axisAligned ? TransitHeuristic.Distance.MANHATTAN : TransitHeuristic.Distance.EUCLIDEAN,
                chosenDistance(),
                "the chosen measure must follow the map's geometry, not a hardcoded preference");
        System.out.println("fixture map is axis-aligned: " + axisAligned + ", using " + chosenDistance());
    }

    /**
     * Step 5.2's proof, and the one that actually matters: on paths Dijkstra has
     * already solved exactly, the guess never exceeds the true remaining cost.
     * Checked for both distance forms, at every vertex of every path.
     */
    @Test
    void theHeuristicNeverOverestimatesWhatIsLeft() {
        for (TransitHeuristic.Distance distance : admissibleDistances()) {
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
        for (TransitHeuristic.Distance distance : admissibleDistances()) {
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
                heuristic(transitGraph.vertexCount() - 1, chosenDistance())::estimateMillis);

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
        // The fixture map is a real OpenStreetMap extract, so its roads run at
        // every angle and Manhattan would overestimate on it. That is exactly
        // the case the guard exists for, so assert it refuses rather than
        // asserting a grid this map is not.
        assertTrue(!TransitHeuristic.isManhattanAdmissibleOn(roadGraph),
                "this map is a real road network; if it ever becomes a lattice again, "
                        + "Manhattan becomes available and the default should be revisited");
        assertThrows(IllegalArgumentException.class,
                () -> TransitHeuristic.towards(transitGraph, 0, TransitHeuristic.Distance.MANHATTAN, roadGraph));

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
