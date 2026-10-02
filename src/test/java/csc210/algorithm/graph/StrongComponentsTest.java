package csc210.algorithm.graph;

import csc210.algorithm.Fixtures;
import csc210.algorithm.cost.CostModel;
import csc210.algorithm.model.Edge;
import csc210.algorithm.model.Node;
import csc210.algorithm.model.Position;
import csc210.algorithm.world.World;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrongComponentsTest {

    private static Node node(int id) {
        return new Node(id, id, new Position(0, 0, id, 0), 0, false, 0, false, 0, 0, 0, null, 0, 0);
    }

    private static Graph graph(int nodes, int[][] arcs) {
        List<Node> ns = new ArrayList<>();
        for (int i = 0; i < nodes; i++) {
            ns.add(node(i));
        }
        List<Edge> es = new ArrayList<>();
        for (int i = 0; i < arcs.length; i++) {
            es.add(new Edge(i, arcs[i][0], arcs[i][1], -1, "residential", 1, 1.0, 1.0));
        }
        return Graph.of(ns, es);
    }

    @Test
    void aTwoWayBlockAndAOneWayPocketAreSeparateComponents() {
        // 0<->1 two-way; 1->2 one-way into a pocket 2<->3 that has no way out.
        Graph g = graph(4, new int[][] {{0, 1}, {1, 0}, {1, 2}, {2, 3}, {3, 2}});
        StrongComponents c = StrongComponents.of(g, CostModel.ofCosts(new long[] {1, 1, 1, 1, 1}));

        assertEquals(2, c.count());
        assertTrue(c.mutuallyReachable(0, 1));
        assertTrue(c.mutuallyReachable(2, 3));
        assertFalse(c.mutuallyReachable(1, 2), "a bus reaches 2 from 1 but can never come back");
        assertEquals(2, c.largestSize());
    }

    @Test
    void aClosedRoadIsNotARoad() {
        // A ring 0->1->2->0; closing 2->0 breaks it into three singletons.
        Graph g = graph(3, new int[][] {{0, 1}, {1, 2}, {2, 0}});
        assertEquals(1, StrongComponents.of(g, CostModel.ofCosts(new long[] {1, 1, 1})).count());
        assertEquals(3, StrongComponents.of(g, CostModel.ofCosts(new long[] {1, 1, -1})).count());
    }

    @Test
    void agreesWithBruteForceReachabilityOnRandomGraphs() {
        Random random = new Random(210);
        for (int trial = 0; trial < 200; trial++) {
            int n = 2 + random.nextInt(12);
            int m = random.nextInt(n * 3);
            int[][] arcs = new int[m][];
            long[] costs = new long[m];
            for (int i = 0; i < m; i++) {
                arcs[i] = new int[] {random.nextInt(n), random.nextInt(n)};
                costs[i] = random.nextInt(5) == 0 ? -1 : 1;
            }
            Graph g = graph(n, arcs);
            StrongComponents c = StrongComponents.of(g, CostModel.ofCosts(costs));

            boolean[][] reach = new boolean[n][n];
            for (int i = 0; i < n; i++) {
                reach[i][i] = true;
            }
            for (int i = 0; i < m; i++) {
                if (costs[i] >= 0) {
                    reach[arcs[i][0]][arcs[i][1]] = true;
                }
            }
            for (int k = 0; k < n; k++) {
                for (int i = 0; i < n; i++) {
                    for (int j = 0; j < n; j++) {
                        reach[i][j] |= reach[i][k] && reach[k][j];
                    }
                }
            }
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    assertEquals(reach[i][j] && reach[j][i], c.mutuallyReachable(i, j),
                            "trial " + trial + ": " + i + " and " + j);
                }
            }
        }
    }

    @Test
    void theRealCityHasOneMainNetworkAndPocketsAtItsEdge() {
        World world = Fixtures.v2World1800();
        StrongComponents c = StrongComponents.of(world.graph(), world.costModel());

        assertEquals(2826, c.largestSize());
        int stopsInMain = 0;
        int flagged = 0;
        for (int id = 0; id < world.graph().nodeCount(); id++) {
            if (world.graph().node(id).busStop()) {
                flagged++;
                if (c.inLargest(id)) {
                    stopsInMain++;
                }
            }
        }
        assertEquals(81, flagged);
        assertEquals(68, stopsInMain);
    }
}
