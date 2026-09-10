package csc210.algorithm.search;

import csc210.algorithm.Fixtures;
import csc210.algorithm.TestGraphs;
import csc210.algorithm.client.TopologyData;
import csc210.algorithm.cost.CostModel;
import csc210.algorithm.graph.Graph;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Steps 3.1 and 3.2. Three kinds of proof: a five-node graph whose answers were
 * worked out by hand, a brute-force all-pairs oracle on random graphs, and the
 * reconstruction check — every rebuilt path must cost exactly what the search
 * said it would.
 */
class DijkstraTest {

    /**
     * Worked out on paper:
     * <pre>
     *   0 -> 1 (4)   0 -> 2 (1)   2 -> 1 (2)
     *   1 -> 3 (5)   2 -> 3 (8)   3 -> 4 (3)
     * </pre>
     * so from 0: node 2 costs 1, node 1 costs 3 via 2 (not 4 direct), node 3
     * costs 8 via 2 then 1 (not 9 via 2 direct), node 4 costs 11.
     */
    private static final int[][] HAND_GRAPH = {
            {0, 1, 4}, {0, 2, 1}, {2, 1, 2}, {1, 3, 5}, {2, 3, 8}, {3, 4, 3}
    };

    private static TopologyData topology;
    private static Graph roadGraph;
    private static CostModel roadCosts;

    @BeforeAll
    static void loadRoadNetwork() {
        topology = Fixtures.topology();
        roadGraph = Graph.of(topology.nodes(), topology.edges());
        roadCosts = CostModel.of(roadGraph, Fixtures.snapshotAt0830());
    }

    @Test
    void matchesHandComputedDistances() {
        TestGraphs.Fixture fixture = TestGraphs.build(5, HAND_GRAPH);
        ShortestPathTree tree = new Dijkstra(fixture.graph(), fixture.costModel()).from(0);

        assertEquals(0, tree.costTo(0));
        assertEquals(3, tree.costTo(1));
        assertEquals(1, tree.costTo(2));
        assertEquals(8, tree.costTo(3));
        assertEquals(11, tree.costTo(4));
    }

    @Test
    void reconstructsTheHandComputedPaths() {
        TestGraphs.Fixture fixture = TestGraphs.build(5, HAND_GRAPH);
        ShortestPathTree tree = new Dijkstra(fixture.graph(), fixture.costModel()).from(0);

        assertArrayEquals(new int[]{0, 2, 1, 3, 4}, tree.pathTo(4));
        assertArrayEquals(new int[]{0, 2}, tree.pathTo(2));
        assertArrayEquals(new int[]{0}, tree.pathTo(0));
        // Edge ids, in travel order: 0->2 is edge 1, 2->1 is edge 2, 1->3 is 3, 3->4 is 5.
        assertArrayEquals(new int[]{1, 2, 3, 5}, tree.edgePathTo(4));
    }

    @Test
    void agreesWithBruteForceOnRandomGraphs() {
        Random random = new Random(20260910L);

        for (int trial = 0; trial < 200; trial++) {
            int nodeCount = 4 + random.nextInt(9);
            int[][] edgeSpecs = randomEdges(nodeCount, random);
            TestGraphs.Fixture fixture = TestGraphs.build(nodeCount, edgeSpecs);

            long[][] bruteForce = floydWarshall(nodeCount, edgeSpecs);
            Dijkstra dijkstra = new Dijkstra(fixture.graph(), fixture.costModel());

            for (int source = 0; source < nodeCount; source++) {
                ShortestPathTree tree = dijkstra.from(source);
                for (int target = 0; target < nodeCount; target++) {
                    assertEquals(bruteForce[source][target], tree.costTo(target),
                            "trial " + trial + ": cost from " + source + " to " + target);
                }
            }
        }
    }

    @Test
    void everyReconstructedPathCostsWhatTheSearchSaid() {
        ShortestPathTree tree = new Dijkstra(roadGraph, roadCosts).from(0);

        for (int target = 0; target < roadGraph.nodeCount(); target++) {
            if (!tree.isReachable(target)) {
                continue;
            }
            int[] nodes = tree.pathTo(target);
            int[] edges = tree.edgePathTo(target);
            assertEquals(nodes.length - 1, edges.length);

            long summed = 0L;
            for (int i = 0; i < edges.length; i++) {
                assertEquals(nodes[i], roadGraph.edge(edges[i]).from());
                assertEquals(nodes[i + 1], roadGraph.edge(edges[i]).to());
                summed += roadCosts.costMillis(edges[i]);
            }
            assertEquals(tree.costTo(target), summed, "path to " + target + " does not add up");
        }
    }

    @Test
    void theWholeRoadNetworkIsReachableFromAnyNode() {
        ShortestPathTree tree = new Dijkstra(roadGraph, roadCosts).from(0);

        assertEquals(roadGraph.nodeCount(), tree.reachableCount());
        assertEquals(roadGraph.nodeCount(), tree.nodesExpanded(),
                "a connected graph should settle every node exactly once");
    }

    @Test
    void stoppingEarlyGivesTheSameAnswerForLessWork() {
        Dijkstra dijkstra = new Dijkstra(roadGraph, roadCosts);
        int target = 97;

        ShortestPathTree full = dijkstra.from(0);
        ShortestPathTree early = dijkstra.from(0, target);

        assertEquals(full.costTo(target), early.costTo(target));
        assertArrayEquals(full.pathTo(target), early.pathTo(target));
        assertTrue(early.nodesExpanded() <= full.nodesExpanded());
    }

    @Test
    void closedEdgesCannotBeUsed() {
        // A single chain 0 -> 1 -> 2 with the middle edge closed strands node 2.
        TestGraphs.Fixture fixture = TestGraphs.build(3, new int[][]{{0, 1, 5}, {1, 2, -1}});
        ShortestPathTree tree = new Dijkstra(fixture.graph(), fixture.costModel()).from(0);

        assertEquals(5, tree.costTo(1));
        assertFalse(tree.isReachable(2));
        assertArrayEquals(new int[0], tree.pathTo(2));
    }

    private static int[][] randomEdges(int nodeCount, Random random) {
        int edgeCount = nodeCount + random.nextInt(3 * nodeCount);
        int[][] specs = new int[edgeCount][];
        for (int i = 0; i < edgeCount; i++) {
            specs[i] = new int[]{
                    random.nextInt(nodeCount),
                    random.nextInt(nodeCount),
                    1 + random.nextInt(50)
            };
        }
        return specs;
    }

    /**
     * The brute-force oracle: consider every node as a possible waypoint and keep
     * whatever is cheaper. Cubic and obviously correct, which is the point — it
     * shares no structure with Dijkstra, so agreeing with it is real evidence.
     */
    private static long[][] floydWarshall(int nodeCount, int[][] edgeSpecs) {
        long[][] best = new long[nodeCount][nodeCount];
        for (long[] row : best) {
            java.util.Arrays.fill(row, ShortestPathTree.UNREACHABLE);
        }
        for (int i = 0; i < nodeCount; i++) {
            best[i][i] = 0L;
        }
        for (int[] spec : edgeSpecs) {
            best[spec[0]][spec[1]] = Math.min(best[spec[0]][spec[1]], spec[2]);
        }
        for (int k = 0; k < nodeCount; k++) {
            for (int i = 0; i < nodeCount; i++) {
                if (best[i][k] == ShortestPathTree.UNREACHABLE) {
                    continue;
                }
                for (int j = 0; j < nodeCount; j++) {
                    if (best[k][j] == ShortestPathTree.UNREACHABLE) {
                        continue;
                    }
                    best[i][j] = Math.min(best[i][j], best[i][k] + best[k][j]);
                }
            }
        }
        return best;
    }
}
