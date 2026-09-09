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
}
