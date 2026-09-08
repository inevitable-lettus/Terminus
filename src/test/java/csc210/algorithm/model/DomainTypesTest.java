package csc210.algorithm.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        Node node = new Node(0, 9000000000L, position, 3, false, true, "school");

        assertEquals(0, node.id());
        assertEquals(9000000000L, node.osmNodeId());
        assertEquals(position, node.position());
        assertEquals(3, node.degree());
        assertFalse(node.busStop());
        assertTrue(node.signal());
        assertEquals("school", node.buildingType());
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
        EdgeConfiguration control = new EdgeConfiguration(false, 1.0, 1.0);
        Edge edge = new Edge(0, 0, 1, 1, 8000000000L, false, "primary", 2, 142.8, 16.67, 3200.0, control);

        assertEquals(0, edge.id());
        assertEquals(0, edge.from());
        assertEquals(1, edge.to());
        assertEquals(1, edge.reverseTwin());
        assertEquals(8000000000L, edge.osmWayId());
        assertFalse(edge.syntheticReverse());
        assertEquals("primary", edge.roadClass());
        assertEquals(2, edge.lanes());
        assertEquals(142.8, edge.lengthM());
        assertEquals(16.67, edge.freeSpeedMps());
        assertEquals(3200.0, edge.baseCapacityVph());
        assertEquals(control, edge.control());
    }
}
