package csc210.algorithm.export;

import csc210.algorithm.Fixtures;
import csc210.algorithm.client.SnapshotData;
import csc210.algorithm.client.TopologyData;
import csc210.algorithm.cost.CostModel;
import csc210.algorithm.demand.DemandModel;
import csc210.algorithm.graph.Graph;
import csc210.algorithm.routing.BusRoute;
import csc210.algorithm.routing.RouteBudget;
import csc210.algorithm.routing.RouteNetwork;
import csc210.algorithm.routing.RouteNetworkBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The export checks, without a sandbox. A drivable route is one whose every
 * consecutive pair of nodes is joined by one directed edge — the rule the
 * simulator's retired route endpoint used to enforce, and still a true statement
 * about a route. These tests check the routes satisfy it before anything is
 * written, that the return leg is real rather than assumed, and that the files
 * say what the network says.
 */
class RouteExporterTest {

    private static Graph roadGraph;
    private static RouteNetwork network;
    private static RouteExporter exporter;
    /** As {@link #exporter} but aware of one-way streets, which is how production builds it. */
    private static RouteExporter checked;
    private static final RouteExporter.Meta META =
            new RouteExporter.Meta("8230", "18:00:00", "sha256:abc", "BUILDINGS", 279, 612, 8);

    @BeforeAll
    static void build() {
        TopologyData topology = Fixtures.topology();
        SnapshotData snapshot = Fixtures.snapshotAt1800();
        roadGraph = Graph.of(topology.nodes(), topology.edges());
        CostModel costModel = CostModel.of(roadGraph, snapshot, Fixtures.oneWayEdgeIdsAt1800());
        DemandModel demandModel = DemandModel.of(roadGraph, snapshot);
        network = new RouteNetworkBuilder(roadGraph, costModel, demandModel, RouteBudget.defaults()).build();
        exporter = new RouteExporter(roadGraph);
        checked = new RouteExporter(roadGraph, costModel);
    }

    @Test
    void everyGeneratedRouteIsAContinuousTraversal() {
        for (BusRoute route : network.routes()) {
            assertTrue(exporter.isContinuous(route.nodes()),
                    route.label() + " has a gap, so it is not drivable");
        }
    }

    @Test
    void everyReturnLegIsAlsoAContinuousTraversalOfRoadsTrafficMayUse() {
        for (BusRoute route : network.routes()) {
            if (route.runsBothWays()) {
                assertTrue(checked.isContinuous(route.back().nodes()),
                        route.label() + "'s return has a gap or uses a road no bus may drive");
            }
        }
    }

    @Test
    void theReturnRunsFromTheLastStopBackToTheFirst() {
        for (BusRoute route : network.routes()) {
            if (!route.runsBothWays()) {
                continue;
            }
            int[] forward = route.nodes();
            int[] back = route.back().nodes();
            assertEquals(forward[forward.length - 1], back[0], "the return starts where the outbound ended");
            assertEquals(forward[0], back[back.length - 1], "the return must finish where the line started");
        }
    }

    @Test
    void aGappedRouteIsRejectedBeforeItIsEverSent() {
        // Two junctions on opposite corners of the map, with nothing between
        // them: exactly the shape a stops-only route would have.
        int[] gapped = {0, roadGraph.nodeCount() - 1};
        assertFalse(exporter.isContinuous(gapped));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> exporter.requireContinuous(gapped, "a stops-only route"));
        assertTrue(thrown.getMessage().contains("not a continuous traversal"), thrown.getMessage());
    }

    @Test
    void droppingOneJunctionFromARealRouteBreaksIt() {
        // The sharper version of the same check: take a route that is
        // drivable and remove a single intermediate junction. If this still passed,
        // the continuity check would be doing nothing.
        BusRoute route = network.routes().get(0);
        int[] full = route.nodes();
        assertTrue(full.length > 3, "need a route long enough to remove a node from");

        int[] punctured = new int[full.length - 1];
        System.arraycopy(full, 0, punctured, 0, 2);
        System.arraycopy(full, 3, punctured, 2, full.length - 3);

        assertFalse(exporter.isContinuous(punctured),
                "a route with a junction missing must not be accepted as continuous");
    }

    @Test
    void everyRoadOnThisMapHasAReverseTwin() {
        // The assumption the return leg rests on, stated as a check. If a future
        // map has one-way streets this fails here rather than in a file nobody can drive.
        assertEquals(java.util.List.of(), exporter.edgesWithoutReverseTwin());
    }

    // --- the files -----------------------------------------------------------

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    @Test
    void jsonCarriesEveryRouteWithItsFullTraversalAndTheWorldItCameFrom() throws Exception {
        var root = MAPPER.readTree(checked.toJson(network, META));

        assertEquals("8230", root.at("/world/seed").asText());
        assertEquals("BUILDINGS", root.at("/world/demand_source").asText());
        assertEquals(network.routeCount(), root.at("/network/routes").asInt());
        assertEquals(network.routeCount(), root.get("routes").size());
        for (int i = 0; i < network.routeCount(); i++) {
            BusRoute route = network.routes().get(i);
            var written = root.get("routes").get(i);
            assertEquals(route.label(), written.get("label").asText());
            assertEquals(route.nodes().length, written.get("nodes").size());
            assertEquals(route.edges().length, written.get("edges").size());
            assertEquals(route.nodes()[route.nodes().length - 1],
                    written.get("nodes").get(route.nodes().length - 1).asInt());
            assertEquals(route.travelMillis(), written.get("travel_millis").asLong());
        }
    }

    @Test
    void aLineThatCannotBeDrivenBackIsWrittenAsOneWayWithNoReturnLeg() throws Exception {
        var root = MAPPER.readTree(checked.toJson(network, META));

        for (int i = 0; i < network.routeCount(); i++) {
            BusRoute route = network.routes().get(i);
            var written = root.get("routes").get(i);
            boolean back = route.runsBothWays();
            assertEquals(back, written.get("both_ways").asBoolean());
            assertEquals(back, !written.get("return_nodes").isNull());
        }
        assertEquals(checked.oneWayLines(network).size(),
                (int) java.util.stream.IntStream.range(0, network.routeCount())
                        .filter(i -> !root.get("routes").get(i).get("both_ways").asBoolean()).count());
    }

    @Test
    void geoJsonWritesLongitudeBeforeLatitudeAsTheFormatRequires() throws Exception {
        var root = MAPPER.readTree(checked.toGeoJson(network));
        BusRoute route = network.routes().get(0);
        var line = root.get("features").get(0);

        assertEquals("FeatureCollection", root.get("type").asText());
        assertEquals("LineString", line.at("/geometry/type").asText());
        assertEquals(route.nodes().length, line.at("/geometry/coordinates").size());
        var first = line.at("/geometry/coordinates/0");
        assertEquals(roadGraph.node(route.nodes()[0]).position().lon(), first.get(0).asDouble(), 1e-12);
        assertEquals(roadGraph.node(route.nodes()[0]).position().lat(), first.get(1).asDouble(), 1e-12);
    }

    @Test
    void geoJsonMarksEachServedStopWithTheLinesThatCallThere() throws Exception {
        var root = MAPPER.readTree(checked.toGeoJson(network));
        java.util.Set<Integer> served = new java.util.TreeSet<>();
        for (BusRoute route : network.routes()) {
            for (int stop : route.stops()) served.add(stop);
        }

        java.util.Set<Integer> pointed = new java.util.TreeSet<>();
        for (var feature : root.get("features")) {
            if ("stop".equals(feature.at("/properties/kind").asText())) {
                pointed.add(feature.at("/properties/node").asInt());
                assertEquals("Point", feature.at("/geometry/type").asText());
            }
        }
        assertEquals(served, pointed);
    }

    @Test
    void theSameNetworkAlwaysProducesByteIdenticalFiles() throws Exception {
        assertEquals(checked.toJson(network, META), checked.toJson(network, META));
        assertEquals(checked.toGeoJson(network), checked.toGeoJson(network));
    }

    @Test
    void writeCreatesTheDirectoryAndBothFiles(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        java.nio.file.Path out = tmp.resolve("nested/out");

        RouteExporter.Written written = checked.write(network, META, out);

        assertTrue(java.nio.file.Files.isRegularFile(written.json()));
        assertTrue(java.nio.file.Files.isRegularFile(written.geojson()));
        assertEquals(network.routeCount(), written.routes());
        assertEquals(checked.toJson(network, META), java.nio.file.Files.readString(written.json()));
    }

    @Test
    void aBrokenRouteWritesNothingAtAll(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) {
        BusRoute real = network.routes().get(0);
        BusRoute gapped = new BusRoute(0, real.stops(), new int[]{0, roadGraph.nodeCount() - 1}, new int[]{0},
                real.travelMillis(), real.demandCovered(), real.stopOccurrences(), real.stopOccurrencePositions());
        RouteNetwork broken = new RouteNetwork(java.util.List.of(gapped), new int[0], 1.0, 2.0);

        assertThrows(IllegalStateException.class, () -> checked.write(broken, META, tmp.resolve("out")));
        assertFalse(java.nio.file.Files.exists(tmp.resolve("out")), "a failed export must not leave a half-written directory");
    }
}
