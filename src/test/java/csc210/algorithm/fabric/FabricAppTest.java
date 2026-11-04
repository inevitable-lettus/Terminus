package csc210.algorithm.fabric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FabricAppTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode event(String json) throws IOException {
        return JSON.readTree(json);
    }

    @Test
    void onlyAStartedRunBringsAWorld() throws Exception {
        assertTrue(FabricApp.bringsWorld(event("{\"type\":\"lifecycle.changed\",\"data\":{\"to\":\"RUNNING\"}}")));
        assertTrue(FabricApp.bringsWorld(event("{\"type\":\"lifecycle.changed\",\"data\":{\"to\":\"PAUSED\"}}")));
        assertFalse(FabricApp.bringsWorld(event("{\"type\":\"lifecycle.changed\",\"data\":{\"to\":\"PREPARING\"}}")));
        assertFalse(FabricApp.bringsWorld(event("{\"type\":\"world.changed\",\"data\":{}}")));
    }

    @Test
    void manifestAsksOnlyForCapabilitiesGrantedAutomatically() {
        @SuppressWarnings("unchecked")
        List<String> capabilities = (List<String>) FabricApp.manifest("terminus").get("capabilities");

        assertTrue(capabilities.contains("sim.read"));
        assertFalse(capabilities.stream().anyMatch(c -> c.startsWith("control.")),
                "Terminus reacts to the simulation; it never changes it");
    }

    /** Registers, saves the token, says active, then waits until the run starts. */
    @Test
    void joinSaysActiveAndWaitsForTheRun(@TempDir Path dir) throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        AtomicReference<String> lifecycle = new AtomicReference<>("PREPARING");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/fabric/apps", exchange -> {
            String path = exchange.getRequestURI().getPath();
            calls.add(exchange.getRequestMethod() + " " + path);
            String body = path.endsWith("/apps")
                    ? "{\"ok\":true,\"data\":{\"app\":{},\"token\":\"tok-9\"}}"
                    : path.endsWith("/events")
                    ? "{\"ok\":true,\"data\":{\"items\":[{\"type\":\"lifecycle.changed\",\"data\":{\"to\":\""
                            + lifecycle.get() + "\"}}],\"sequence\":1}}"
                    : "{\"ok\":true,\"data\":{}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        PrintStream notes = new PrintStream(new ByteArrayOutputStream());
        TokenStore tokens = new TokenStore(dir.resolve("terminus.token"));
        try (FabricApp app = FabricApp.join(
                new FabricClient("http://127.0.0.1:" + server.getAddress().getPort(), "terminus"),
                tokens, "PREPARING", notes)) {
            assertEquals("tok-9", tokens.load());
            assertTrue(calls.contains("PUT /api/v1/fabric/apps/terminus/state"));
            assertThrows(IOException.class, () -> app.awaitWorld(Duration.ofMillis(300)));

            lifecycle.set("RUNNING");
            app.awaitWorld(Duration.ofSeconds(10));
        } finally {
            server.stop(0);
        }
        assertTrue(calls.contains("DELETE /api/v1/fabric/apps/terminus"), "closing unloads the app");
        assertEquals(null, tokens.load(), "and forgets the token it no longer needs");
    }
}
