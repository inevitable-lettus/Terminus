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
 * Deserializes a saved {@code /view/topology} response (fixture recaptured
 * 2026-09-14 from a live DSTNS run on the Berlin Mitte extract) with no sandbox
 * running — step 1.4. The known values asserted below were read directly off
 * that fixture.
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

        assertEquals(279, topology.nodes().size());
        assertEquals(612, topology.edges().size());
    }

    @Test
    void knownNodeMapsCorrectly() throws IOException {
        TopologyData topology = loadFixture();

        Node node = topology.nodes().stream()
                .filter(n -> n.id() == 16)
                .findFirst()
                .orElseThrow();

        // A real OpenStreetMap id now, not a synthesised 9000000000-series one:
        // this map is compiled from an actual extract rather than generated.
        assertEquals(25664657L, node.osmNodeId());
        assertEquals(52.5181506, node.position().lat());
        assertEquals(13.390521, node.position().lon());
        assertEquals("store", node.building());
    }

    @Test
    void knownEdgeMapsCorrectly() throws IOException {
        TopologyData topology = loadFixture();

        Edge edge = topology.edges().stream()
                .filter(e -> e.id() == 0)
                .findFirst()
                .orElseThrow();

        assertEquals(7, edge.from());
        assertEquals(272, edge.to());
        assertEquals(1, edge.reverseTwin());
        assertEquals("primary", edge.roadClass());
        // Real road geometry: lengths vary per segment rather than sitting on a
        // uniform grid pitch, so this is the measured length of one way, not a
        // constant shared by every edge.
        assertEquals(15.784139959364941, edge.lengthM());
    }
}
