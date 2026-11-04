package csc210.algorithm.fabric;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import csc210.algorithm.client.SandboxHttpException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FabricClientTest {

    private HttpServer server;
    private final List<String> seen = new CopyOnWriteArrayList<>();
    private String url;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/fabric/apps", this::answer);
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private void answer(HttpExchange exchange) throws IOException {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        String path = exchange.getRequestURI().toString();
        seen.add(exchange.getRequestMethod() + " " + path + " " + (auth == null ? "-" : auth));
        String body;
        int status = 200;
        if (path.equals("/api/v1/fabric/apps") && auth == null && seen.size() > 1) {
            status = 409;
            body = "{\"ok\":false,\"error\":{\"code\":\"APP_ID_CONFLICT\",\"message\":\"present its token\"}}";
        } else if (path.equals("/api/v1/fabric/apps")) {
            body = "{\"ok\":true,\"data\":{\"app\":{\"id\":\"org.test\",\"state\":\"registered\"},\"token\":\"tok-1\"}}";
        } else if (path.contains("/events")) {
            body = "{\"ok\":true,\"data\":{\"items\":[{\"type\":\"lifecycle.changed\",\"sequence\":4,"
                    + "\"data\":{\"to\":\"RUNNING\"}}],\"sequence\":4,\"dropped\":0}}";
        } else {
            body = "{\"ok\":true,\"data\":{}}";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void registerKeepsTheTokenAndSendsItAfterwards() throws Exception {
        FabricClient fabric = new FabricClient(url, "org.test");
        fabric.register(Map.of("id", "org.test", "name", "Test", "version", "1.0.0"));
        fabric.setState("active", "");

        assertEquals("tok-1", fabric.token());
        assertEquals("POST /api/v1/fabric/apps -", seen.get(0));
        assertEquals("PUT /api/v1/fabric/apps/org.test/state Bearer tok-1", seen.get(1));
        assertFalse(fabric.toString().contains("tok-1"), "the token must never be printed");
    }

    @Test
    void eventsCarryTheNextSequence() throws Exception {
        FabricClient fabric = new FabricClient(url, "org.test");
        FabricClient.Events page = fabric.events(0);

        assertEquals(4, page.sequence());
        assertEquals("RUNNING", page.items().get(0).path("data").path("to").asText());
    }

    @Test
    void registeringALiveIdWithoutTheTokenIsAConflict() throws Exception {
        new FabricClient(url, "org.test").register(Map.of("id", "org.test"));
        FabricClient second = new FabricClient(url, "org.test");

        SandboxHttpException refused = assertThrows(SandboxHttpException.class,
                () -> second.register(Map.of("id", "org.test")));
        assertEquals(409, refused.status());
        assertEquals("APP_ID_CONFLICT", refused.code());
    }

    @Test
    void tokenStoreKeepsTheTokenPrivate(@TempDir Path dir) throws Exception {
        TokenStore store = new TokenStore(dir.resolve(".fabric").resolve("org.test.token"));
        assertNull(store.load());

        store.save("tok-1");

        assertEquals("tok-1", store.load());
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(store.file())));
        store.clear();
        assertTrue(Files.notExists(store.file()));
    }
}
