package csc210.algorithm.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import csc210.algorithm.model.Edge;
import csc210.algorithm.model.Node;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Deserializes a saved {@code /view/topology} response (fixture captured
 * 2026-09-09 from a live DSTNS run) with no sandbox running — step 1.4.
 * The known values asserted below were read directly off that fixture.
 */
class TopologyDataTest {

    private static final ObjectMapper MAPPER = SandboxJson.mapper();

    private TopologyData loadFixture() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/topology.json")) {
            assertNotNull(in, "fixtures/topology.json must be on the test classpath");
            Envelope envelope = MAPPER.readValue(in, Envelope.class);
            return MAPPER.treeToValue(envelope.data(), TopologyData.class);
        }
    }

    @Test
    void deserializesEveryNodeAndEdge() throws IOException {
        TopologyData topology = loadFixture();

        assertEquals(120, topology.nodes().size());
        assertEquals(436, topology.edges().size());
    }

    @Test
    void knownNodeMapsCorrectly() throws IOException {
        TopologyData topology = loadFixture();

        Node node = topology.nodes().stream()
                .filter(n -> n.id() == 4)
                .findFirst()
                .orElseThrow();

        assertEquals(9000000004L, node.osmNodeId());
        assertEquals(48.866616910829585, node.position().lat());
        assertEquals(2.366763309735022, node.position().lon());
        assertEquals("store", node.building());
    }

    @Test
    void knownEdgeMapsCorrectly() throws IOException {
        TopologyData topology = loadFixture();

        Edge edge = topology.edges().stream()
                .filter(e -> e.id() == 0)
                .findFirst()
                .orElseThrow();

        assertEquals(1, edge.from());
        assertEquals(0, edge.to());
        assertEquals(1, edge.reverseTwin());
        assertEquals("primary", edge.roadClass());
        assertEquals(180.0, edge.lengthM());
    }
}
