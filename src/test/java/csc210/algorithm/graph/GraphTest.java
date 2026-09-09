package csc210.algorithm.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import csc210.algorithm.client.Envelope;
import csc210.algorithm.client.SandboxJson;
import csc210.algorithm.client.TopologyData;
import csc210.algorithm.model.Edge;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Builds a {@link Graph} from the 1.4 fixture (no live sandbox needed) and
 * checks the invariants step 1.5 requires.
 */
class GraphTest {

    private static TopologyData topology;
    private static Graph graph;

    @BeforeAll
    static void loadFixtureAndBuildGraph() throws IOException {
        ObjectMapper mapper = SandboxJson.mapper();
        try (InputStream in = GraphTest.class.getResourceAsStream("/fixtures/topology.json")) {
            assertNotNull(in, "fixtures/topology.json must be on the test classpath");
            Envelope envelope = mapper.readValue(in, Envelope.class);
            topology = mapper.treeToValue(envelope.data(), TopologyData.class);
        }
        graph = Graph.of(topology.nodes(), topology.edges());
    }

    @Test
    void countsMatchTheFixture() {
        assertEquals(topology.nodes().size(), graph.nodeCount());
        assertEquals(topology.edges().size(), graph.edgeCount());
    }

    @Test
    void everyNodeIsStoredAtItsOwnId() {
        for (int i = 0; i < graph.nodeCount(); i++) {
            assertEquals(i, graph.node(i).id());
        }
    }

    @Test
    void everyEdgeIsStoredAtItsOwnId() {
        for (int i = 0; i < graph.edgeCount(); i++) {
            assertEquals(i, graph.edge(i).id());
        }
    }

    @Test
    void reverseTwinInvariantHoldsForEveryEdge() {
        for (int i = 0; i < graph.edgeCount(); i++) {
            int twin = graph.edge(i).reverseTwin();
            assertEquals(i, graph.edge(twin).reverseTwin(),
                    "edge " + i + "'s reverse twin " + twin + " does not point back to it");
        }
    }

    @Test
    void outgoingEdgesMatchExactlyTheEdgesLeavingThatNode() {
        for (int nodeId = 0; nodeId < graph.nodeCount(); nodeId++) {
            int currentNodeId = nodeId;
            int[] fromFixture = topology.edges().stream()
                    .filter(e -> e.from() == currentNodeId)
                    .mapToInt(Edge::id)
                    .sorted()
                    .toArray();

            int[] fromGraph = graph.outgoingEdges(nodeId).clone();
            java.util.Arrays.sort(fromGraph);

            assertArrayEquals(fromFixture, fromGraph, "mismatch for node " + nodeId);
        }
    }

    @Test
    void rejectsATopologyWithNonDenseNodeIds() {
        assertThrows(IllegalArgumentException.class, () ->
                Graph.of(java.util.List.of(new csc210.algorithm.model.Node(
                        5, 1L, new csc210.algorithm.model.Position(0, 0, 0, 0),
                        0, false, 0, false, 0, 0, 0, null, 0, 0)),
                        java.util.List.of()));
    }
}
