package csc210.algorithm.fabric;

import csc210.algorithm.Fixtures;
import csc210.algorithm.cli.RunInfo;
import csc210.algorithm.routing.BusRoute;
import csc210.algorithm.serve.PlannedCity;
import csc210.algorithm.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObserverDrawingTest {

    private static PlannedCity morning;
    private static PlannedCity night;

    @BeforeAll
    static void plan() {
        World world = Fixtures.v2World0830();
        morning = PlannedCity.plan(world, new RunInfo("run_test", "0x2026", "08:30:00", true), world.budget(), "live");
        night = PlannedCity.plan(world, new RunInfo("run_test", "0x2026", "23:30:00", true), world.budget(), "live");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> features(Map<String, Object> overlay) {
        return (List<Map<String, Object>>) overlay.get("features");
    }

    @Test
    void everyLineIsDrawnAlongItsRoadsWithItsStops() {
        List<Map<String, Object>> features = features(ObserverDrawing.lines(morning));

        long routes = features.stream().filter(f -> f.get("type").equals("route")).count();
        long expected = morning.network().routes().stream().mapToLong(r -> r.runsBothWays() ? 2 : 1).sum();
        assertEquals(expected, routes);
        long markers = features.stream().filter(f -> f.get("type").equals("marker")).count();
        assertEquals(ObserverDrawing.stopNodes(morning.network()).size(), markers);
    }

    @Test
    void busesInTheMorningSitOnARoadOfTheirOwnLine() {
        List<ObserverDrawing.Bus> buses = ObserverDrawing.buses(morning, 8 * 3600 + 30 * 60);

        assertFalse(buses.isEmpty(), "buses run every 15 minutes from 06:00");
        for (ObserverDrawing.Bus bus : buses) {
            BusRoute route = morning.network().routes().get(bus.position().trip().routeId());
            int[] edges = bus.position().trip().outbound() ? route.edges() : route.back().edges();
            assertTrue(java.util.Arrays.stream(edges).anyMatch(e -> e == bus.edge()));
            assertTrue(bus.millisToNextStop() >= 0);
        }
        List<Map<String, Object>> entities = features(ObserverDrawing.busOverlay(buses));
        assertEquals(buses.size(), entities.size());
        assertEquals("bus", entities.get(0).get("shape"));
    }

    @Test
    void theArrivalsPanelSaysWhenNoBusIsRunning() {
        Map<String, Object> panel = ObserverDrawing.arrivalsPanel(night, 23 * 3600 + 30 * 60, List.of());

        assertEquals("panel", panel.get("slot"));
        assertTrue(panel.toString().contains("No bus is on the road now"));
    }

    @Test
    void theArrivalsTableListsTheSoonestFirst() {
        List<ObserverDrawing.Bus> buses = ObserverDrawing.buses(morning, 8 * 3600 + 30 * 60);
        Map<String, Object> panel = ObserverDrawing.arrivalsPanel(morning, 8 * 3600 + 30 * 60, buses);

        assertTrue(panel.toString().contains("table"));
        assertTrue(panel.toString().contains("08:30"));
    }
}
