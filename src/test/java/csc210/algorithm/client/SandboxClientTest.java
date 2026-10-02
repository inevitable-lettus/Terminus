package csc210.algorithm.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import csc210.algorithm.Fixtures;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two audit regressions against a local loopback stub, so neither depends on
 * a running DSTNS sandbox.
 */
class SandboxClientTest {

    private static final ObjectMapper MAPPER = SandboxJson.mapper();

    // --- #33: a stalled server must not hang the caller forever -----------

    /**
     * Regression: neither the shared {@link HttpClient} nor any individual
     * request carried a timeout, so a server that accepts a connection and
     * never answers hung the caller indefinitely. With a short request
     * timeout configured, the same stall now fails fast with an
     * {@link IOException} instead.
     */
    @Test
    void stalledServerFailsFastInsteadOfHangingForever() throws Exception {
        CountDownLatch arrived = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            arrived.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            SandboxClient client = new SandboxClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                    MAPPER,
                    Duration.ofMillis(500));

            Future<HealthStatus> future = pool.submit(client::health);
            assertTrue(arrived.await(2, TimeUnit.SECONDS), "stub was never contacted");

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> future.get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IOException,
                    "expected a request-timeout IOException, got " + failure.getCause());
        } finally {
            release.countDown();
            server.stop(0);
            pool.shutdownNow();
        }
    }

    // --- #34: pagination must not silently truncate ------------------------

    @Test
    void completePagesReturnEveryEdge() throws Exception {
        List<EdgeDetail> fixture = Fixtures.edgeDetailsAt1800().subList(0, 3);
        try (Stub stub = Stub.onePerCall(fixture)) {
            List<EdgeDetail> result = stub.client().edges();
            assertEquals(3, result.size());
            assertEquals(3, stub.calls());
        }
    }

    @Test
    void emptyDatasetWithZeroTotalReturnsNormally() throws Exception {
        try (Stub stub = Stub.fixedPage(List.of(), 0)) {
            assertEquals(List.of(), stub.client().edges());
            assertEquals(1, stub.calls());
        }
    }

    /**
     * Regression: a page arriving empty while the server's own {@code total}
     * says more edges remain used to be treated as "done" instead of a
     * pagination failure, silently truncating the result.
     */
    @Test
    void prematureEmptyPageAfterPartialDataFailsLoudly() throws Exception {
        List<EdgeDetail> fixture = Fixtures.edgeDetailsAt1800().subList(0, 1);
        try (Stub stub = Stub.prematureEmpty(fixture, 3)) {
            IOException failure = assertThrows(IOException.class, () -> stub.client().edges());
            assertTrue(failure.getMessage().contains("stalled"), failure.getMessage());
        }
    }

    @Test
    void emptyFirstPageWithPositiveTotalFailsLoudly() throws Exception {
        try (Stub stub = Stub.fixedPage(List.of(), 3)) {
            assertThrows(IOException.class, () -> stub.client().edges());
        }
    }

    /** A minimal, self-closing {@code GET /view/edges} stub over a loopback HTTP server. */
    private static final class Stub implements AutoCloseable {
        private final HttpServer server;
        private final SandboxClient client;
        private final AtomicInteger calls = new AtomicInteger();

        private Stub(HttpServer server) {
            this.server = server;
            this.client = new SandboxClient("http://127.0.0.1:" + server.getAddress().getPort());
        }

        SandboxClient client() {
            return client;
        }

        int calls() {
            return calls.get();
        }

        static Stub onePerCall(List<EdgeDetail> items) throws IOException {
            return start(exchange -> {
                int call = exchange.calls;
                List<EdgeDetail> page = call < items.size() ? List.of(items.get(call)) : List.of();
                return new EdgePage(page, call, 1, items.size());
            });
        }

        static Stub fixedPage(List<EdgeDetail> items, int total) throws IOException {
            return start(exchange -> new EdgePage(items, 0, 1000, total));
        }

        /** First call returns one item; every call after returns empty, total never reached. */
        static Stub prematureEmpty(List<EdgeDetail> firstPage, int declaredTotal) throws IOException {
            return start(exchange -> exchange.calls == 0
                    ? new EdgePage(firstPage, 0, 1000, declaredTotal)
                    : new EdgePage(List.of(), firstPage.size(), 1000, declaredTotal));
        }

        private interface PageSource {
            EdgePage page(Call call) throws IOException;
        }

        private record Call(int calls) {}

        private static Stub start(PageSource source) throws IOException {
            AtomicInteger counter = new AtomicInteger();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            Stub stub = new Stub(server);
            server.createContext("/api/v1/view/edges", exchange -> {
                int call = counter.getAndIncrement();
                stub.calls.set(call + 1);
                EdgePage page = source.page(new Call(call));
                Envelope envelope = new Envelope("1.0", "test-run", "0x2026", 1L, 1L, MAPPER.valueToTree(page));
                byte[] body = MAPPER.writeValueAsBytes(envelope);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return stub;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    // --- DSTNS 2.0: operator gate, async start, places ---------------------

    /** Serves fixed responses for a few paths and records the headers each one saw. */
    private static HttpServer server(java.util.function.Consumer<HttpServer> routes) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        routes.accept(server);
        server.start();
        return server;
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String base(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void startWithoutTheOperatorTokenIsReportedAsARefusalNotAnOutage() throws Exception {
        HttpServer server = server(s -> s.createContext("/api/v1/playback/start", exchange -> reply(exchange, 403,
                "{\"error\":{\"code\":\"CLI_START_REQUIRED\",\"message\":\"Start simulations through the operator CLI.\"}}")));
        try {
            SandboxClient client = new SandboxClient(base(server));
            assertTrue(!client.hasOperatorToken());
            RunStartRefusedException refused = assertThrows(RunStartRefusedException.class,
                    () -> client.startPlayback("1", "00:00:00", 600));
            assertTrue(refused.getMessage().contains("no operator token"), refused.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void operatorTokenTravelsOnTheStartAndOnNothingElse() throws Exception {
        java.util.List<String> startHeader = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.List<String> otherHeader = new java.util.concurrent.CopyOnWriteArrayList<>();
        HttpServer server = server(s -> {
            s.createContext("/api/v1/playback/start", exchange -> {
                startHeader.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-DSTNS-Operator")));
                reply(exchange, 202, "{\"ok\":true}");
            });
            s.createContext("/api/v1/playback/pause", exchange -> {
                otherHeader.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-DSTNS-Operator")));
                reply(exchange, 200, "{\"ok\":true}");
            });
        });
        try {
            SandboxClient client = new SandboxClient(base(server)).withOperatorToken("  secret\n");
            assertTrue(client.hasOperatorToken());
            client.startPlayback("1", "00:00:00", 600);
            client.pausePlayback();
            assertEquals(List.of("secret"), startHeader, "surrounding whitespace is stripped");
            assertEquals(List.of("null"), otherHeader, "the credential must not leak onto other requests");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aBlankTokenIsTreatedAsNoToken() {
        assertTrue(!new SandboxClient("http://127.0.0.1:1").withOperatorToken("  \n").hasOperatorToken());
    }

    @Test
    void awaitReadyWaitsThroughPreparationAndReturnsOnceThereIsAWorld() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String[] states = {"IDLE", "PREPARING", "PREPARING", "ready"};
        HttpServer server = server(s -> s.createContext("/health", exchange -> reply(exchange, 200,
                "{\"ok\":true,\"product\":\"x\",\"lifecycle\":\"" + states[Math.min(calls.getAndIncrement(), 3)] + "\"}")));
        try {
            new SandboxClient(base(server)).awaitReady(Duration.ofSeconds(5), Duration.ofMillis(10));
            assertEquals(4, calls.get(), "lifecycle case must not matter, and IDLE/PREPARING must be waited through");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void awaitReadyFailsFastWhenTheRunIsInError() throws Exception {
        HttpServer server = server(s -> s.createContext("/health", exchange -> reply(exchange, 200,
                "{\"ok\":true,\"product\":\"x\",\"lifecycle\":\"ERROR\"}")));
        try {
            RunStateException failure = assertThrows(RunStateException.class,
                    () -> new SandboxClient(base(server)).awaitReady(Duration.ofSeconds(5), Duration.ofMillis(10)));
            assertTrue(failure.getMessage().contains("ERROR"), failure.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void awaitReadyGivesUpWhenPreparationNeverFinishes() throws Exception {
        HttpServer server = server(s -> s.createContext("/health", exchange -> reply(exchange, 200,
                "{\"ok\":true,\"product\":\"x\",\"lifecycle\":\"PREPARING\"}")));
        try {
            IOException failure = assertThrows(IOException.class,
                    () -> new SandboxClient(base(server)).awaitReady(Duration.ofMillis(150), Duration.ofMillis(20)));
            assertTrue(failure.getMessage().contains("PREPARING"), failure.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aFailedRequestCarriesTheSimulatorsOwnErrorCode() throws Exception {
        HttpServer server = server(s -> s.createContext("/api/v1/playback/seek", exchange -> reply(exchange, 409,
                "{\"ok\":false,\"error\":{\"code\":\"LIFECYCLE_CONFLICT\",\"message\":\"stale run\"}}")));
        try {
            SandboxHttpException failure = assertThrows(SandboxHttpException.class,
                    () -> new SandboxClient(base(server)).seek("18:00:00"));
            assertEquals(409, failure.status());
            assertEquals("LIFECYCLE_CONFLICT", failure.code());
            assertTrue(failure.isLifecycleConflict());
            assertTrue(failure.getMessage().contains("stale run"), failure.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void awaitReadyRidesOutABusyServerInsteadOfGivingUpOnTheFirstFailure() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        HttpServer server = server(s -> s.createContext("/health", exchange -> {
            if (polls.getAndIncrement() < 2) {
                reply(exchange, 503, "{\"ok\":false,\"error\":{\"code\":\"MAP_FETCH_FAILED\",\"message\":\"busy\"}}");
            } else {
                reply(exchange, 200, "{\"ok\":true,\"product\":\"x\",\"lifecycle\":\"READY\"}");
            }
        }));
        try {
            new SandboxClient(base(server)).awaitReady(Duration.ofSeconds(5), Duration.ofMillis(10));
            assertTrue(polls.get() >= 3);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aBaseUrlBehindAPathPrefixKeepsItsPrefix() throws Exception {
        HttpServer server = server(s -> s.createContext("/dstns/health", exchange -> reply(exchange, 200,
                "{\"ok\":true,\"product\":\"x\",\"lifecycle\":\"PAUSED\"}")));
        try {
            assertEquals("PAUSED", new SandboxClient(base(server) + "/dstns").health().lifecycle());
        } finally {
            server.stop(0);
        }
    }
}
