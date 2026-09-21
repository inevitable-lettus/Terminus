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
    void anOlderServerNeedsTheEdgesEndpointForOneWayStreetsAndHasNoPlaces() throws Exception {
        AtomicInteger edgeCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/view/topology", e -> reply(e, 200, resource("topology.json")));
        server.createContext("/api/v1/view/snapshot", e -> reply(e, 200, resource("snapshot_1800.json")));
        server.createContext("/api/v1/view/edges", e -> {
            edgeCalls.incrementAndGet();
            reply(e, 200, resource("edges_1800.json"));
        });
        server.createContext("/api/v1/view/places", e -> reply(e, 404, "{}".getBytes(StandardCharsets.UTF_8)));
        server.start();
        try {
            World world = WorldLoader.load(new SandboxClient("http://127.0.0.1:" + server.getAddress().getPort()));

            assertEquals(279, world.graph().nodeCount());
            assertEquals(612, world.graph().edgeCount());
            assertFalse(world.oneWayFromTopology());
            assertEquals(139, world.oneWayEdges());
            assertTrue(edgeCalls.get() > 0, "the flag was absent from the topology, so /view/edges had to be asked");
            assertNotNull(world.placesNote(), "a missing places endpoint must be reported, not hidden");
            assertTrue(world.placesNote().contains("404"), world.placesNote());
            assertEquals(DemandSource.BUILDINGS, world.demandModel().source());
            assertTrue(world.stops().count() <= world.stops().rawCount());
            assertEquals(world.stops().count(), world.demandModel().stopCount());
            assertNotNull(world.budget());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aTwoPointZeroServerIsNotAskedForEdgesAndItsPlacesAreUsed() throws Exception {
        int stopNode = -1;
        for (var node : Fixtures.topology().nodes()) {
            if (node.busStop()) {
                stopNode = node.id();
                break;
            }
        }
        final int anchored = stopNode;
        String places = "{\"api_version\":\"1.0\",\"data\":{\"items\":[{\"id\":\"node/1\",\"name\":\"\",\"kind\":\"bus_stop\","
                + "\"position\":{\"lat\":52.5,\"lon\":13.4,\"x_m\":0,\"y_m\":0},\"anchor_node\":" + anchored
                + ",\"demand\":{\"multiplier\":2.5,\"baseline\":1.0,\"factors\":[],\"radius_m\":400.0}}],"
                + "\"total\":1,\"offset\":0,\"limit\":2000,\"kind\":\"bus_stop\"}}";

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/view/topology", e -> reply(e, 200, topologyWithFlags()));
        server.createContext("/api/v1/view/snapshot", e -> reply(e, 200, resource("snapshot_1800.json")));
        server.createContext("/api/v1/view/edges", e -> reply(e, 500, "must not be called".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/api/v1/view/places", e -> reply(e, 200, places.getBytes(StandardCharsets.UTF_8)));
        server.start();
        try {
            World world = WorldLoader.load(new SandboxClient("http://127.0.0.1:" + server.getAddress().getPort()));

            assertTrue(world.oneWayFromTopology());
            assertEquals(139, world.oneWayEdges());
            assertEquals(null, world.placesNote());
            assertEquals(DemandSource.PLACES, world.demandModel().source());
            assertEquals(2.5, world.demandModel().demandAt(world.stops().representativeOf(anchored)), 1e-9);
        } finally {
            server.stop(0);
        }
    }
}
