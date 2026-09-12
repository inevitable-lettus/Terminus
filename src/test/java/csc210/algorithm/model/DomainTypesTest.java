package csc210.algorithm.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import csc210.algorithm.client.SandboxJson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomainTypesTest {

    @Test
    void positionHoldsAllFourCoordinates() {
        Position p = new Position(23.0185, 72.5682, 0.0, 0.0);

        assertEquals(23.0185, p.lat());
        assertEquals(72.5682, p.lon());
        assertEquals(0.0, p.xM());
        assertEquals(0.0, p.yM());
    }

    @Test
    void nodeHoldsAllFields() {
        Position position = new Position(23.0185, 72.5682, 0.0, 0.0);
        Node node = new Node(0, 9000000000L, position, 3, false, 0.0, true, 90, 45, 10, "school", 0.8, 150.0);

        assertEquals(0, node.id());
        assertEquals(9000000000L, node.osmNodeId());
        assertEquals(position, node.position());
        assertEquals(3, node.degree());
        assertFalse(node.busStop());
        assertEquals(0.0, node.busStopRadiusM());
        assertTrue(node.signal());
        assertEquals(90, node.signalCycleS());
        assertEquals(45, node.signalGreenS());
        assertEquals(10, node.signalOffsetS());
        assertEquals("school", node.building());
        assertEquals(0.8, node.buildingImpact());
        assertEquals(150.0, node.buildingRadiusM());
    }

    @Test
    void edgeConfigurationHoldsAllFields() {
        EdgeConfiguration config = new EdgeConfiguration(false, 1.0, 1.0);

        assertFalse(config.closed());
        assertEquals(1.0, config.manualSpeedMultiplier());
        assertEquals(1.0, config.manualCapacityMultiplier());
    }

    @Test
    void edgeHoldsAllFields() {
        Edge edge = new Edge(0, 0, 1, 1, "primary", 2, 142.8, 16.67);

        assertEquals(0, edge.id());
        assertEquals(0, edge.from());
        assertEquals(1, edge.to());
        assertEquals(1, edge.reverseTwin());
        assertEquals("primary", edge.roadClass());
        assertEquals(2, edge.lanes());
        assertEquals(142.8, edge.lengthM());
        assertEquals(16.67, edge.freeSpeedMps());
    }

    @Test
    void edgeStateHoldsAllFields() {
        EdgeState state = new EdgeState(0, false, 0.4, 12.0, 0.0, 0.0, 3, 1);

        assertEquals(0, state.id());
        assertFalse(state.closed());
        assertEquals(0.4, state.congestion());
        assertEquals(12.0, state.effectiveSpeedMps());
        assertEquals(3, state.vehicleCount());
        assertEquals(1, state.haltingCount());
    }

    /**
     * Regression: a missing {@code length_m}/{@code free_speed_mps} used to
     * deserialize as {@code 0}, and {@code CostModel} then priced the road as
     * a free 0ms edge instead of failing at ingestion.
     */
    @Test
    void edgeRejectsNonFiniteOrNegativeLength() {
        assertThrows(IllegalArgumentException.class,
                () -> new Edge(0, 0, 1, 1, "primary", 2, Double.NaN, 16.67));
        assertThrows(IllegalArgumentException.class,
                () -> new Edge(0, 0, 1, 1, "primary", 2, -1.0, 16.67));
    }

    @Test
    void edgeRejectsNonFiniteOrNegativeFreeSpeed() {
        assertThrows(IllegalArgumentException.class,
                () -> new Edge(0, 0, 1, 1, "primary", 2, 142.8, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,
                () -> new Edge(0, 0, 1, 1, "primary", 2, 142.8, -1.0));
    }

    @Test
    void edgeStateRejectsNonFiniteOrNegativeEffectiveSpeed() {
        assertThrows(IllegalArgumentException.class,
                () -> new EdgeState(0, false, 0.4, Double.NaN, 0.0, 0.0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new EdgeState(0, false, 0.4, -1.0, 0.0, 0.0, 0, 0));
    }

    /** A stopped-but-not-jammed edge legitimately has zero speed; that must still work. */
    @Test
    void edgeStateAllowsZeroEffectiveSpeed() {
        EdgeState state = new EdgeState(0, false, 1.0, 0.0, 0.0, 0.0, 0, 0);
        assertEquals(0.0, state.effectiveSpeedMps());
    }

    /**
     * The exact JSON shape from the audit's reproduction: an edge missing
     * every field but {@code id}/{@code from}/{@code to} used to deserialize
     * successfully with {@code lengthM=0}. Marking the consumed fields
     * required at the Jackson boundary turns that into a clear failure.
     */
    @Test
    void deserializingAnEdgeMissingRequiredFieldsFails() {
        ObjectMapper mapper = SandboxJson.mapper();
        assertThrows(MismatchedInputException.class,
                () -> mapper.readValue("{\"id\":0,\"from\":0,\"to\":1}", Edge.class));
    }

    @Test
    void deserializingAnEdgeStateMissingRequiredFieldsFails() {
        ObjectMapper mapper = SandboxJson.mapper();
        assertThrows(MismatchedInputException.class,
                () -> mapper.readValue("{\"id\":0}", EdgeState.class));
    }

    /** A quoted "NaN"/"Infinity" speed used to be coerced and then silently accepted. */
    @Test
    void deserializingAQuotedNonFiniteSpeedFails() {
        ObjectMapper mapper = SandboxJson.mapper();
        assertThrows(Exception.class, () -> mapper.readValue(
                "{\"id\":0,\"from\":0,\"to\":1,\"length_m\":180.0,\"free_speed_mps\":\"NaN\"}", Edge.class));
    }

    @Test
    void completeEdgeAndEdgeStateJsonStillDeserializes() throws Exception {
        ObjectMapper mapper = SandboxJson.mapper();
        Edge edge = mapper.readValue(
                "{\"id\":0,\"from\":0,\"to\":1,\"reverse_twin\":1,\"road_class\":\"primary\","
                        + "\"lanes\":2,\"length_m\":142.8,\"free_speed_mps\":16.67}", Edge.class);
        EdgeState state = mapper.readValue(
                "{\"id\":0,\"closed\":false,\"congestion\":0.4,\"effective_speed_mps\":12.0,"
                        + "\"rainfall\":0.0,\"flood\":0.0,\"vehicle_count\":3,\"halting_count\":1}", EdgeState.class);

        assertEquals(142.8, edge.lengthM());
        assertEquals(12.0, state.effectiveSpeedMps());
    }
}
