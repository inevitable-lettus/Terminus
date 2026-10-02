package csc210.algorithm.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import csc210.algorithm.Fixtures;
import csc210.algorithm.client.SandboxClient;
import csc210.algorithm.client.SandboxJson;
import csc210.algorithm.demand.DemandSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole world-building pipeline, run over the saved Berlin fixtures served by
 * a stub — so the same code path the command line takes is exercised with no
 * simulator running.
 */
class WorldLoaderTest {

    private static final ObjectMapper MAPPER = SandboxJson.mapper();

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = WorldLoaderTest.class.getResourceAsStream("/fixtures/" + name)) {
            assertNotNull(in, name);
            return in.readAllBytes();
        }
    }

    private static void reply(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    /** The topology fixture with {@code synthetic_reverse} added to every edge, as DSTNS 2.0 sends it. */
    private static byte[] topologyWithFlags() throws IOException {
        ObjectNode root = (ObjectNode) MAPPER.readTree(resource("topology.json"));
        var oneWay = Fixtures.oneWayEdgeIdsAt1800();
        for (JsonNode edge : root.path("data").path("edges")) {
            ((ObjectNode) edge).put("synthetic_reverse", oneWay.contains(edge.path("id").asInt()));
        }
        return MAPPER.writeValueAsBytes(root);
    }

    @Test
    void anOlderServerNeedsTheEdgesEndpointForOneWayStreetsAndHasNoGenerators() throws Exception {
        AtomicInteger edgeCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/view/topology", e -> reply(e, 200, resource("topology.json")));
        server.createContext("/api/v1/view/snapshot", e -> reply(e, 200, resource("snapshot_1800.json")));
        server.createContext("/api/v1/view/edges", e -> {
            edgeCalls.incrementAndGet();
            reply(e, 200, resource("edges_1800.json"));
        });
        server.start();
        try {
            World world = WorldLoader.load(new SandboxClient("http://127.0.0.1:" + server.getAddress().getPort()));

            assertEquals(279, world.graph().nodeCount());
            assertEquals(612, world.graph().edgeCount());
            assertFalse(world.oneWayFromTopology());
            assertEquals(139, world.oneWayEdges());
            assertTrue(edgeCalls.get() > 0, "the flag was absent from the topology, so /view/edges had to be asked");
            assertNotNull(world.demandNote(), "falling back must be reported, not hidden");
            assertTrue(world.demandNote().contains("before 2.0"), world.demandNote());
            assertEquals(DemandSource.BUILDINGS, world.demandModel().source());
            assertTrue(world.stops().count() <= world.stops().rawCount());
            assertEquals(world.stops().count(), world.demandModel().stopCount());
            assertNotNull(world.budget());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aLiveTwoPointZeroCaptureIsNotAskedForEdgesAndScoresStopsFromItsGenerators() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/view/topology", e -> reply(e, 200, Fixtures.bytes("v2/topology.json.gz")));
        server.createContext("/api/v1/view/snapshot", e -> reply(e, 200, Fixtures.bytes("v2/snapshot_1800.json.gz")));
        server.createContext("/api/v1/view/edges", e -> reply(e, 500, "must not be called".getBytes(StandardCharsets.UTF_8)));
        server.start();
        try {
            World world = WorldLoader.load(new SandboxClient("http://127.0.0.1:" + server.getAddress().getPort()));

            assertEquals(3000, world.graph().nodeCount());
            assertEquals(6330, world.graph().edgeCount());
            assertTrue(world.oneWayFromTopology());
            assertEquals(566, world.oneWayEdges());
            assertEquals(81, world.stops().rawCount());
            assertEquals("Dar es Salaam", world.topology().location().city());

            // The regression: reading the simulator's bus_stop places left 71 of 72
            // stops on one baseline figure. The generators must actually rank them.
            assertEquals(DemandSource.CATCHMENT, world.demandModel().source());
            assertEquals(null, world.demandNote());
            java.util.Set<Double> distinct = new java.util.HashSet<>();
            int scored = 0;
            for (int stop : world.demandModel().stopNodes()) {
                double demand = world.demandModel().demandAt(stop);
                distinct.add(demand);
                if (demand > 0.0) {
                    scored++;
                }
            }
            assertTrue(distinct.size() > world.stops().count() / 2,
                    "demand must separate the stops, not give most of them one figure: " + distinct.size());
            assertTrue(scored > world.stops().count() / 2, "most stops have a generator nearby: " + scored);
            assertTrue(world.demandModel().generatorsAssigned() <= world.demandModel().generatorsActive());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void demandFollowsTheClockBecauseTheGeneratorsDo() {
        World morning = Fixtures.v2World0830();
        World evening = Fixtures.v2World1800();

        assertEquals(DemandSource.CATCHMENT, morning.demandModel().source());
        assertTrue(morning.demandModel().generatorsActive() < evening.demandModel().generatorsActive(),
                "fewer generators are open at 08:30 than at 18:00 in this capture");
        assertTrue(morning.demandModel().totalDemand() != evening.demandModel().totalDemand());
    }
}
