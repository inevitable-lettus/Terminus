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

    // --- one-way status straight from the topology (DSTNS 2.0) -------------

    private static csc210.algorithm.model.Node plainNode(int id) {
        return new csc210.algorithm.model.Node(id, 1L, new csc210.algorithm.model.Position(0, 0, id * 50.0, 0),
                0, false, 0, false, 0, 0, 0, null, 0, 0);
    }

    @Test
    void oldFixtureDoesNotSayWhichEdgesAreOneWay() {
        // The saved topology predates the flag. "Absent" must not read as
        // "every road is legal": the caller has to go and ask /view/edges.
        assertEquals(false, graph.oneWayKnown());
        assertEquals(0, graph.syntheticReverseIds().size());
    }

    @Test
    void topologyThatCarriesTheFlagIsTrustedAndReportsTheFabricatedEdges() throws IOException {
        String json = """
                {"id":0,"from":0,"to":1,"reverse_twin":1,"length_m":50,"free_speed_mps":10,"synthetic_reverse":false}""";
        String twin = """
                {"id":1,"from":1,"to":0,"reverse_twin":0,"length_m":50,"free_speed_mps":10,"synthetic_reverse":true}""";
        ObjectMapper mapper = SandboxJson.mapper();
        Graph flagged = Graph.of(
                java.util.List.of(plainNode(0), plainNode(1)),
                java.util.List.of(mapper.readValue(json, Edge.class), mapper.readValue(twin, Edge.class)));

        assertEquals(true, flagged.oneWayKnown());
        assertEquals(java.util.Set.of(1), flagged.syntheticReverseIds());
    }

    @Test
    void oneMissingFlagIsEnoughToMakeTheAnswerUnknown() throws IOException {
        Edge said = SandboxJson.mapper().readValue(
                "{\"id\":0,\"from\":0,\"to\":1,\"reverse_twin\":1,\"length_m\":50,\"free_speed_mps\":10,\"synthetic_reverse\":false}",
                Edge.class);
        Edge silent = new Edge(1, 1, 0, 0, "residential", 1, 50.0, 10.0);
        Graph mixed = Graph.of(java.util.List.of(plainNode(0), plainNode(1)), java.util.List.of(said, silent));

        assertEquals(false, mixed.oneWayKnown());
    }

    @Test
    void fastestFreeFlowSpeedIsReadOffTheEdges() {
        Graph two = Graph.of(java.util.List.of(plainNode(0), plainNode(1)), java.util.List.of(
                new Edge(0, 0, 1, 1, "primary", 1, 50.0, 16.67),
                new Edge(1, 1, 0, 0, "motorway", 1, 50.0, 27.78)));

        assertEquals(27.78, two.maxFreeSpeedMps());
        assertEquals(0.0, Graph.of(java.util.List.of(plainNode(0)), java.util.List.of()).maxFreeSpeedMps());
    }
}
