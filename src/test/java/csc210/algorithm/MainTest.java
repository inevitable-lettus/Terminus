package csc210.algorithm;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import csc210.algorithm.cli.Options;
import csc210.algorithm.routing.RouteBudget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The command line's whole flow — attach or start, read, render — driven against a
 * stub that serves the saved Berlin fixtures and records every request it gets.
 * The recording is the point: several of these tests are about what was
 * <em>not</em> sent.
 */
class MainTest {

    /** A simulator that answers from fixtures and writes down every request. */
    private static final class Stub implements AutoCloseable {
        final HttpServer server;
        final List<String> requests = new CopyOnWriteArrayList<>();
        /** What {@code POST /playback/start} answers; a 202 unless a test says otherwise. */
        volatile int startStatus = 202;
        volatile String startBody = "{\"ok\":true}";

        /**
         * @param lifecycles what {@code /health} reports on successive polls; the
         *        last one repeats, so a single value is a steady state
         */
        Stub(String... lifecycles) throws IOException {
            java.util.concurrent.atomic.AtomicInteger poll = new java.util.concurrent.atomic.AtomicInteger();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/health", e -> reply(e, 200,
                    "{\"ok\":true,\"product\":\"DSTNS\",\"lifecycle\":\""
                            + lifecycles[Math.min(poll.getAndIncrement(), lifecycles.length - 1)] + "\"}"));
            server.createContext("/api/v1/playback/status", e -> reply(e, 200,
                    "{\"run_id\":\"run_test\",\"global_seed\":\"0x2026\",\"clock\":{\"simulated_current_time\":\"18:00:00\"}}"));
            server.createContext("/api/v1/view/topology", e -> reply(e, 200, resource("topology.json")));
            server.createContext("/api/v1/view/snapshot", e -> reply(e, 200, resource("snapshot_1800.json")));
            server.createContext("/api/v1/view/edges", e -> reply(e, 200, resource("edges_1800.json")));
            server.createContext("/api/v1/view/places", e -> reply(e, 404, "{}"));
            for (String path : new String[]{"pause", "seek", "stop", "start"}) {
                server.createContext("/api/v1/playback/" + path, e -> {
                    requests.add("POST /playback/" + path);
                    if (path.equals("start")) {
                        reply(e, startStatus, startBody);
                    } else {
                        reply(e, 200, "{\"ok\":true}");
                    }
                });
            }
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        boolean sent(String request) {
            return requests.contains(request);
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private static void reply(HttpExchange exchange, int status, String body) throws IOException {
            reply(exchange, status, body.getBytes(StandardCharsets.UTF_8));
        }

        private static void reply(HttpExchange exchange, int status, byte[] body) throws IOException {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }

        private static byte[] resource(String name) throws IOException {
            try (InputStream in = MainTest.class.getResourceAsStream("/fixtures/" + name)) {
                assertNotNull(in, name);
                return in.readAllBytes();
            }
        }
    }

    private static String run(Stub stub, ByteArrayOutputStream notes, String... args) throws Exception {
        String[] full = new String[args.length + 2];
        System.arraycopy(args, 0, full, 0, args.length);
        full[args.length] = "--sandbox";
        full[args.length + 1] = stub.url();
        return Main.run(full, new PrintStream(notes, true, StandardCharsets.UTF_8));
    }

    // --- attaching is the default, and leaves the run alone -----------------

    @Test
    void networkReadsARunThatIsAlreadyPausedAndTouchesNothing() throws Exception {
        try (Stub stub = new Stub("PAUSED")) {
            ByteArrayOutputStream notes = new ByteArrayOutputStream();
            String text = run(stub, notes, "network");

            assertTrue(text.startsWith("DSTNS world"), text);
            assertTrue(text.contains("run_test") && text.contains("attached"), text);
            assertTrue(text.contains("Lines") && text.contains("Summary"), text);
            assertEquals(List.of(), stub.requests, "attaching must not stop, start, pause or seek anything");
            assertEquals("", notes.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void aRunningClockIsReadButWarnedAbout() throws Exception {
        try (Stub stub = new Stub("RUNNING")) {
            ByteArrayOutputStream notes = new ByteArrayOutputStream();
            run(stub, notes, "network");

            assertTrue(notes.toString(StandardCharsets.UTF_8).contains("RUNNING"), notes.toString(StandardCharsets.UTF_8));
            assertEquals(List.of(), stub.requests);
        }
    }

    @Test
    void timeAsksToPauseAndSeekAndNothingMore() throws Exception {
        try (Stub stub = new Stub("RUNNING")) {
            run(stub, new ByteArrayOutputStream(), "network", "--time", "08:30:00");

            assertEquals(List.of("POST /playback/pause", "POST /playback/seek"), stub.requests);
        }
    }

    @Test
    void withNoRunTheCommandSaysWhatToDoInsteadOfFailingObscurely() throws Exception {
        try (Stub stub = new Stub("IDLE")) {
            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> run(stub, new ByteArrayOutputStream(), "network"));

            assertTrue(thrown.getMessage().contains("No run is going"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("--start"), thrown.getMessage());
            assertEquals(List.of(), stub.requests);
        }
    }

    // --- starting a run: the token is checked before anything is stopped -----

    @Test
    void startWithoutATokenRefusesBeforeItStopsTheRunSomebodyIsWatching() throws Exception {
        try (Stub stub = new Stub("RUNNING")) {
            // Guard against the environment supplying a token and hiding the case.
            org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("DSTNS_OPERATOR_TOKEN") == null);

            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> run(stub, new ByteArrayOutputStream(), "network", "--start"));

            assertTrue(thrown.getMessage().contains("Nothing has been stopped"), thrown.getMessage());
            assertEquals(List.of(), stub.requests, "a start that cannot succeed must not stop the current run first");
        }
    }

    @Test
    void aSeedAloneAlsoCountsAsAskingToStart() throws Exception {
        try (Stub stub = new Stub("RUNNING")) {
            org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("DSTNS_OPERATOR_TOKEN") == null);

            assertThrows(IllegalStateException.class,
                    () -> run(stub, new ByteArrayOutputStream(), "network", "--seed", "8230"));
            assertEquals(List.of(), stub.requests);
        }
    }

    @Test
    void startWithATokenFileStopsStartsWaitsThenPausesAndSeeks(@TempDir Path tmp) throws Exception {
        Path token = tmp.resolve("operator.token");
        Files.writeString(token, "s3cret\n");
        try (Stub stub = new Stub("PAUSED")) {
            run(stub, new ByteArrayOutputStream(), "network", "--start", "--token-file", token.toString());

            assertEquals(List.of("POST /playback/stop", "POST /playback/start",
                    "POST /playback/pause", "POST /playback/seek"), stub.requests);
        }
    }

    @Test
    void anUnreadableTokenFileIsAUsageErrorNotAStackTrace() throws Exception {
        try (Stub stub = new Stub("PAUSED")) {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> run(stub, new ByteArrayOutputStream(), "network", "--token-file", "/no/such/file"));

            assertTrue(thrown.getMessage().contains("cannot read the token file"), thrown.getMessage());
        }
    }

    // --- the commands ---------------------------------------------------------

    @Test
    void stopsListsRealJunctionIdsToUseWithJourney() throws Exception {
        try (Stub stub = new Stub("PAUSED")) {
            String text = run(stub, new ByteArrayOutputStream(), "stops", "--top", "3");

            assertTrue(text.contains("Busiest 3 of"), text);
            assertTrue(text.contains("served by"), text);
        }
    }

    @Test
    void journeyPrintsTheHeaderTheLegsAndATotal() throws Exception {
        try (Stub stub = new Stub("PAUSED")) {
            String text = run(stub, new ByteArrayOutputStream(), "journey", "0", "119");

            assertTrue(text.contains("DSTNS world"), text);
            assertTrue(text.contains("Journey  junction 0 -> junction 119"), text);
            assertTrue(text.contains("Total ") || text.contains("No journey found"), text);
        }
    }

    @Test
    void aJunctionOffTheMapIsRejectedBeforeAnythingIsPrinted() throws Exception {
        try (Stub stub = new Stub("PAUSED")) {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> run(stub, new ByteArrayOutputStream(), "journey", "0", "9999"));

            assertTrue(thrown.getMessage().contains("TO must be a junction id"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("./bus stops"), thrown.getMessage());
        }
    }

    @Test
    void exportWritesBothFilesAndSaysWhereAndPublishStillWorksWithANote(@TempDir Path tmp) throws Exception {
        try (Stub stub = new Stub("PAUSED")) {
            ByteArrayOutputStream notes = new ByteArrayOutputStream();
            String text = run(stub, notes, "publish", "--out", tmp.resolve("o").toString());

            assertTrue(Files.isRegularFile(tmp.resolve("o/routes.json")));
            assertTrue(Files.isRegularFile(tmp.resolve("o/routes.geojson")));
            assertTrue(text.contains("Wrote "), text);
            assertTrue(notes.toString(StandardCharsets.UTF_8).contains("`publish` is now `export`"));
        }
    }

    @Test
    void helpNeverNeedsARunAndReportsWhetherTheSimulatorIsThere() throws Exception {
        try (Stub stub = new Stub("IDLE")) {
            String text = run(stub, new ByteArrayOutputStream(), "help");

            assertTrue(text.contains("Usage: ./bus"), text);
            assertTrue(text.contains("Simulator at " + stub.url() + ": IDLE"), text);
        }
    }

    // --- budget overrides ------------------------------------------------------

    @Test
    void overridesReplaceTheDerivedBudgetFieldByField() {
        RouteBudget derived = new RouteBudget(8 * 60_000L, 8, 0.9, 150.0);

        RouteBudget all = Main.applyOverrides(derived, Options.parse(new String[]{
                "network", "--minutes", "12", "--routes", "5", "--walk", "300"}));
        assertEquals(12 * 60_000L, all.maxRouteMillis());
        assertEquals(5, all.maxRoutes());
        assertEquals(300.0, all.walkingRadiusM());
        assertEquals(0.9, all.targetCoverage());

        assertEquals(derived, Main.applyOverrides(derived, Options.parse(new String[]{"network"})));
    }

    @Test
    void publishedFilesFromTheStubMatchWhatTheNetworkCommandReported(@TempDir Path tmp) throws Exception {
        try (Stub stub = new Stub("PAUSED")) {
            String network = run(stub, new ByteArrayOutputStream(), "network");
            run(stub, new ByteArrayOutputStream(), "export", "--out", tmp.toString());
            String json = Files.readString(tmp.resolve("routes.json"));

            assertFalse(json.isBlank());
            int lines = network.split("\n").length;
            assertTrue(lines > 5, network);
        }
    }

    // --- run states the command cannot or should not read silently ---------

    @Test
    void aRunInErrorIsRefusedWithWhatToDoAndNothingIsRead() throws Exception {
        try (Stub stub = new Stub("ERROR")) {
            csc210.algorithm.client.RunStateException thrown = assertThrows(
                    csc210.algorithm.client.RunStateException.class,
                    () -> run(stub, new ByteArrayOutputStream(), "network"));

            assertTrue(thrown.getMessage().contains("ERROR") && thrown.getMessage().contains("--start"),
                    thrown.getMessage());
            assertEquals(Main.RUN_STATE, Main.exitCodeFor(thrown));
            assertEquals(List.of(), stub.requests);
        }
    }

    @Test
    void aStoppedRunIsReadWithANoteSayingSo() throws Exception {
        try (Stub stub = new Stub("STOPPED")) {
            ByteArrayOutputStream notes = new ByteArrayOutputStream();
            String text = run(stub, notes, "network");

            assertTrue(text.contains("Lines"), text);
            assertTrue(notes.toString(StandardCharsets.UTF_8).contains("STOPPED"), notes.toString(StandardCharsets.UTF_8));
            assertEquals(List.of(), stub.requests);
        }
    }

    @Test
    void aRunThatIsStillStoppingIsWaitedOutRatherThanReadHalfway() throws Exception {
        try (Stub stub = new Stub("STOPPING", "STOPPING", "PAUSED")) {
            String text = run(stub, new ByteArrayOutputStream(), "network");

            assertTrue(text.contains("Lines"), text);
        }
    }

    @Test
    void aStartTheSimulatorRefusesWith409SaysAnotherRunIsActive(@TempDir Path dir) throws Exception {
        Path token = Files.writeString(dir.resolve("operator.token"), "secret\n");
        try (Stub stub = new Stub("PAUSED")) {
            stub.startStatus = 409;
            stub.startBody = "{\"ok\":false,\"api_version\":\"1.0\",\"error\":{\"code\":\"LIFECYCLE_CONFLICT\","
                    + "\"message\":\"a simulation is already active\"}}";

            csc210.algorithm.client.RunStateException thrown = assertThrows(
                    csc210.algorithm.client.RunStateException.class,
                    () -> run(stub, new ByteArrayOutputStream(), "network", "--start", "--token-file", token.toString()));

            assertTrue(thrown.getMessage().contains("LIFECYCLE_CONFLICT")
                    && thrown.getMessage().contains("already active"), thrown.getMessage());
            assertEquals(Main.RUN_STATE, Main.exitCodeFor(thrown));
        }
    }

    // --- exit statuses ------------------------------------------------------

    @Test
    void exitStatusesSeparateTheFiveKindsOfFailure() {
        assertEquals(Main.BAD_USAGE, Main.exitCodeFor(new csc210.algorithm.cli.UsageException("x")));
        assertEquals(Main.RUN_STATE, Main.exitCodeFor(new csc210.algorithm.client.RunStateException("x")));
        assertEquals(Main.RUN_STATE, Main.exitCodeFor(new csc210.algorithm.client.RunStartRefusedException("x")));
        assertEquals(Main.RUN_STATE, Main.exitCodeFor(
                new csc210.algorithm.client.SandboxHttpException("POST", "/p", 409, "LIFECYCLE_CONFLICT", "x")));
        assertEquals(Main.SIMULATOR_UNAVAILABLE, Main.exitCodeFor(
                new csc210.algorithm.client.SandboxHttpException("GET", "/p", 503, "MAP_FETCH_FAILED", "x")));
        assertEquals(Main.SIMULATOR_UNAVAILABLE, Main.exitCodeFor(new java.net.ConnectException()));
        assertEquals(Main.SIMULATOR_DATA_INVALID, Main.exitCodeFor(
                new com.fasterxml.jackson.databind.JsonMappingException(null, "lengthM must be finite")));
        assertEquals(Main.SIMULATOR_DATA_INVALID, Main.exitCodeFor(
                new IllegalArgumentException("snapshot and topology are out of step")));
        assertEquals(Main.INTERNAL_ERROR, Main.exitCodeFor(new NullPointerException()));
        assertEquals(Main.INTERNAL_ERROR, Main.exitCodeFor(new IllegalStateException("R0 is not continuous")));
    }

    @Test
    void badArgumentsAreUsageAndInconsistentSimulatorDataIsNot() {
        IllegalArgumentException usage = assertThrows(IllegalArgumentException.class,
                () -> Options.parse(new String[] {"network", "--minutes", "zero"}));
        assertEquals(Main.BAD_USAGE, Main.exitCodeFor(usage));

        IllegalArgumentException data = assertThrows(IllegalArgumentException.class,
                () -> csc210.algorithm.graph.Graph.of(List.of(), List.of(
                        new csc210.algorithm.model.Edge(0, 0, 1, -1, "residential", 1, 1.0, 1.0))));
        assertEquals(Main.SIMULATOR_DATA_INVALID, Main.exitCodeFor(data));
    }

    @Test
    void anUnreachableSimulatorIsNamedWithItsAddress() {
        String message = Main.messageFor(new java.net.ConnectException(),
                new String[] {"network", "--sandbox", "http://10.0.0.9:8090"});

        assertTrue(message.contains("http://10.0.0.9:8090") && message.contains("./launcher"), message);
    }
}
