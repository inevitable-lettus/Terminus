package csc210.algorithm.cost;

import csc210.algorithm.Fixtures;
import csc210.algorithm.client.SnapshotData;
import csc210.algorithm.client.TopologyData;
import csc210.algorithm.graph.Graph;
import csc210.algorithm.model.Edge;
import csc210.algorithm.model.EdgeState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step 1.6. DSTNS stopped publishing a precomputed edge weight in the 2026-09-06
 * rewrite, so the old "match the server's number on every edge" check is
 * impossible and these proofs are genuinely weaker: an invariant that must hold,
 * arithmetic hand-checked on three edges, and the closed-edge rule.
 */
class CostModelTest {

    private static TopologyData topology;
    private static SnapshotData snapshot;
    private static Graph graph;
    private static CostModel costModel;

    @BeforeAll
    static void load() {
        topology = Fixtures.topology();
        snapshot = Fixtures.snapshotAt0830();
        graph = Graph.of(topology.nodes(), topology.edges());
        costModel = CostModel.of(graph, snapshot);
    }

    @Test
    void noEdgeMovesFasterThanItsSpeedLimit() {
        for (EdgeState state : snapshot.edges()) {
            Edge edge = graph.edge(state.id());
            assertTrue(state.effectiveSpeedMps() <= edge.freeSpeedMps(),
                    "edge " + state.id() + " reports " + state.effectiveSpeedMps()
                            + " m/s but its free-flow limit is " + edge.freeSpeedMps());
        }
    }

    @Test
    void costMatchesHandComputedValues() {
        // Read straight off snapshot_0830.json: the slowest edge in the fixture,
        // one mid-range edge and one at the map's top speed. All 436 edges on
        // this map are exactly 180 m long, so only the speed term varies.
        // 180 / 0.6663812935984038 * 1000 = 270115.6...  -> 270116
        assertEquals(270_116L, costModel.costMillis(239));
        // 180 / 11.11 * 1000 = 16201.6...                -> 16202
        assertEquals(16_202L, costModel.costMillis(110));
        // 180 / 16.67 * 1000 = 10797.8...                -> 10798
        assertEquals(10_798L, costModel.costMillis(425));
    }

    @Test
    void aJammedEdgeIsClampedRatherThanInfinite() {
        // Speed 0 would be a division by zero; the 0.1 m/s floor turns it into
        // a very slow edge instead, which is what a gridlocked road really is.
        assertEquals(1_800_000L, CostModel.travelTimeMillis(180.0, 0.0));
        assertEquals(1_800_000L, CostModel.travelTimeMillis(180.0, -5.0));
    }

    @Test
    void closedEdgesAreExcludedNotWeighted() {
        int closedEdgeId = 42;
        CostModel withClosure = CostModel.of(graph, withEdgeClosed(snapshot, closedEdgeId));

        assertFalse(withClosure.isTraversable(closedEdgeId));
        assertThrows(IllegalStateException.class, () -> withClosure.costMillis(closedEdgeId));

        // Every other edge is untouched — closing one road does not reprice the map.
        for (int id = 0; id < graph.edgeCount(); id++) {
            if (id != closedEdgeId) {
                assertTrue(withClosure.isTraversable(id));
                assertEquals(costModel.costMillis(id), withClosure.costMillis(id));
            }
        }
    }

    @Test
    void everyEdgeInTheFixtureIsOpenAndPriced() {
        for (int id = 0; id < graph.edgeCount(); id++) {
            assertTrue(costModel.isTraversable(id), "edge " + id + " unexpectedly closed");
            assertTrue(costModel.costMillis(id) > 0, "edge " + id + " has a non-positive cost");
        }
    }

    @Test
    void aSnapshotThatDoesNotMatchTheTopologyIsRejected() {
        List<EdgeState> tooFew = new ArrayList<>(snapshot.edges());
        tooFew.remove(0);
        SnapshotData mismatched = new SnapshotData(snapshot.nodes(), tooFew, snapshot.topologyRevision());

        assertThrows(IllegalArgumentException.class, () -> CostModel.of(graph, mismatched));
    }

    /** The same snapshot with one edge flipped to closed, as {@code PUT /control/edges/{id}} would. */
    private static SnapshotData withEdgeClosed(SnapshotData original, int edgeId) {
        List<EdgeState> edges = new ArrayList<>(original.edges().size());
        for (EdgeState state : original.edges()) {
            edges.add(state.id() == edgeId
                    ? new EdgeState(state.id(), true, state.congestion(), state.effectiveSpeedMps(),
                            state.rainfall(), state.flood(), state.vehicleCount(), state.haltingCount())
                    : state);
        }
        return new SnapshotData(original.nodes(), edges, original.topologyRevision());
    }
}
