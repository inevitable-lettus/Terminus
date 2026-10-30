package csc210.algorithm.serve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import csc210.algorithm.Fixtures;
import csc210.algorithm.cli.RunInfo;
import csc210.algorithm.client.SandboxJson;
import csc210.algorithm.graph.Graph;
import csc210.algorithm.world.World;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The local API over the DSTNS 2.0 capture, through real HTTP: every endpoint,
 * the portal's contract, and the error envelope. The strongest checks are the
 * ones that tie a drawn path back to what the search priced.
 */
class ApiServerTest {

    private static final ObjectMapper MAPPER = SandboxJson.mapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static World world;
    private static PlannedCity city;
    private static ApiServer server;

    @BeforeAll
    static void start() throws IOException {
        world = Fixtures.v2World1800();
        city = PlannedCity.plan(world, new RunInfo("run_test", "0x2026", "18:00:00", true), world.budget(), "live");
        server = ApiServer.start("127.0.0.1", 0, city, null, null, "http://sandbox.test");
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthSaysWhichRunItHolds() throws Exception {
        JsonNode health = MAPPER.readTree(get("/api/health").body());
        assertTrue(health.path("ok").asBoolean());
        assertEquals("live", health.path("mode").asText());
        assertEquals("run_test", health.path("run_id").asText());
    }

    @Test
    void theWorldLeavesOutFabricatedReversesAndCarriesItsProvenance() throws Exception {
        HttpResponse<String> response = get("/api/world");
        assertEquals(200, response.statusCode());
        assertEquals("*", response.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
        JsonNode body = MAPPER.readTree(response.body());

        assertEquals(3000, body.path("junctions").size());
        assertEquals(6330 - 566, body.path("roads").size(), "the 566 fabricated reverses are not roads");
        assertEquals(72, body.path("stops").size());
        assertEquals("Dar es Salaam", body.at("/provenance/city").asText());
        assertEquals("CATCHMENT", body.at("/provenance/demand_source").asText());
        assertTrue(body.at("/bounds/max_x").asDouble() > body.at("/bounds/min_x").asDouble());
    }

    @Test
    void everyLineIsListedWithItsReturn() throws Exception {
        JsonNode network = MAPPER.readTree(get("/api/network").body());
        assertEquals(city.network().routeCount(), network.path("lines").size());
        for (JsonNode line : network.path("lines")) {
            assertFalse(line.path("back").isNull(), line.path("label").asText() + " has no return");
        }
        assertTrue(network.path("connected").asBoolean());
    }

    /**
     * The drawn path of every ride is a continuous road path the bus may drive,
     * and its road costs sum to exactly what the leg was priced at. If the
     * drawing and the search disagreed, the dashboard would show a journey the
     * algorithm did not choose.
     */
    @Test
    void everyRideIsDrawnAlongTheRoadsItWasPricedOn() throws Exception {
        Graph graph = world.graph();
        int rides = 0;
        for (int from = 3; from < graph.nodeCount(); from += 173) {
            for (int to = 11; to < graph.nodeCount(); to += 181) {
                JsonNode journey = MAPPER.readTree(get("/api/journey?from=" + from + "&to=" + to).body());
                long legSum = 0;
                for (JsonNode leg : journey.path("legs")) {
                    legSum += leg.path("ms").asLong();
                    if (!leg.path("kind").asText().equals("RIDE")) {
                        continue;
                    }
                    rides++;
                    JsonNode path = leg.path("path");
                    assertEquals(leg.path("from_node").asInt(), path.get(0).asInt());
                    assertEquals(leg.path("to_node").asInt(), path.get(path.size() - 1).asInt());
                    long driven = 0;
                    for (int i = 0; i + 1 < path.size(); i++) {
                        driven += cheapestEdge(graph, path.get(i).asInt(), path.get(i + 1).asInt());
                    }
                    assertEquals(leg.path("ms").asLong(), driven,
                            from + " -> " + to + ": " + leg.path("text").asText());
                }
                if (journey.path("found").asBoolean()) {
                    assertEquals(journey.path("total_ms").asLong(), legSum);
                    assertTrue(journey.path("a_star_expanded").asInt() > 0);
                }
            }
        }
        assertTrue(rides > 20, "only " + rides + " rides in the sample");
    }

    private static long cheapestEdge(Graph graph, int a, int b) {
        long best = -1;
        for (int edge : graph.outgoingEdges(a)) {
            if (graph.edge(edge).to() == b && world.costModel().isTraversable(edge)) {
                long cost = world.costModel().costMillis(edge);
                best = best < 0 ? cost : Math.min(best, cost);
            }
        }
        assertTrue(best >= 0, "no drivable road from " + a + " to " + b);
        return best;
    }

    @Test
    void searchTracesStartWhereThePassengerStandsAndDijkstraSettlesAtLeastAsMuch() throws Exception {
        JsonNode aimed = MAPPER.readTree(get("/api/trace/search?from=1645&to=210&algo=astar").body());
        JsonNode blind = MAPPER.readTree(get("/api/trace/search?from=1645&to=210&algo=dijkstra").body());
        assertEquals(1645, aimed.path("order").get(0).asInt());
        assertTrue(aimed.path("settled").asInt() <= blind.path("settled").asInt());
    }

    @Test
    void theBuildTraceEndsWithEveryAcceptedLine() throws Exception {
        JsonNode steps = MAPPER.readTree(get("/api/trace/build").body()).path("steps");
        long accepted = 0;
        for (JsonNode step : steps) {
            if (step.path("kind").asText().equals("accept")) {
                accepted++;
            }
        }
        assertEquals(city.network().routeCount(), accepted);
    }

    @Test
    void badInputIsA400InTheSimulatorsErrorShapeAndAReloadWithoutARunIsRefused() throws Exception {
        HttpResponse<String> bad = get("/api/journey?from=abc&to=1");
        assertEquals(400, bad.statusCode());
        assertEquals("BAD_REQUEST", MAPPER.readTree(bad.body()).at("/error/code").asText());
        assertEquals(400, get("/api/journey?from=999999&to=1").statusCode());
        assertEquals(405, post("/api/world", "{}").statusCode());
        assertEquals(409, post("/api/reload", "{}").statusCode(), "a server with no reloader holds no live run");
    }

    @Test
    void aReloadMovesTheSharedClockOnlyWhenSeekIsAskedFor() throws Exception {
        java.util.List<String> asked = new java.util.ArrayList<>();
        try (ApiServer reloading = ApiServer.start("127.0.0.1", 0, city, time -> {
            asked.add(String.valueOf(time));
            return city;
        }, null, "http://sandbox.test")) {
            String base = "http://127.0.0.1:" + reloading.port();
            for (String body : new String[] {"{}", "{\"time\":\"08:30:00\"}", "{\"time\":\"08:30:00\",\"seek\":true}"}) {
                HttpResponse<String> answer = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/reload"))
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, answer.statusCode());
            }
        }
        assertEquals(java.util.List.of("null", "null", "08:30:00"), asked);
    }

    // ---- the ticketing portal's contract ------------------------------------

    @Test
    void thePortalQueryAnswersInTheFrozenShape() throws Exception {
        JsonNode stops = MAPPER.readTree(get("/api/v1/stops").body()).path("data");
        String first = null;
        String second = null;
        for (JsonNode stop : stops) {
            if (stop.path("served").asBoolean()) {
                if (first == null) {
                    first = stop.path("stopId").asText();
                } else if (second == null && !stop.path("routeIds").equals(stops.get(0).path("routeIds"))) {
                    second = stop.path("stopId").asText();
                }
            }
        }
        assertNotNull(first);
        assertNotNull(second);

        HttpResponse<String> response = post("/api/v1/routes/query",
                "{\"sourceStopId\":\"" + first + "\",\"destStopId\":\"" + second
                        + "\",\"departureTime\":\"2026-09-10T09:00:00Z\"}");
        assertEquals(200, response.statusCode(), response.body());
        JsonNode data = MAPPER.readTree(response.body()).path("data");
        assertEquals(first, data.path("path").get(0).asText());
        assertEquals(second, data.path("path").get(data.path("path").size() - 1).asText());
        assertTrue(data.has("segments") && data.has("totalCost") && data.has("totalDistanceMeters")
                && data.has("estimatedDurationSeconds"), response.body());
        for (JsonNode segment : data.path("segments")) {
            assertTrue(segment.has("fromStopId") && segment.has("toStopId") && segment.has("routeId")
                    && segment.has("busId") && segment.has("cost") && segment.has("distanceMeters"));
            assertTrue(segment.path("busId").isNull(), "there is no fleet model");
        }
        // A bus does not average 100 m/s: distance and time must describe the same trip.
        double metresPerSecond = data.path("totalDistanceMeters").asDouble()
                / Math.max(1, data.path("estimatedDurationSeconds").asLong());
        assertTrue(metresPerSecond < 35.0, "an impossible speed: " + metresPerSecond + " m/s");
    }

    @Test
    void thePortalHearsWhichStopIdWasWrong() throws Exception {
        HttpResponse<String> notAStop = post("/api/v1/routes/query", "{\"sourceStopId\":\"stop_5\",\"destStopId\":\"stop_201\"}");
        assertEquals(404, notAStop.statusCode());
        assertEquals("STOP_NOT_FOUND", MAPPER.readTree(notAStop.body()).at("/error/code").asText());

        HttpResponse<String> malformed = post("/api/v1/routes/query", "{\"sourceStopId\":\"bus 5\"}");
        assertEquals(400, malformed.statusCode());
    }

    // ---- the static bundle --------------------------------------------------

    @Test
    void theBundleIsTheSameJsonTheServerSends(@TempDir Path dir) throws Exception {
        PlannedCity bundled = PlannedCity.plan(world, city.run(), world.budget(), "bundle");
        Bundle.write(bundled, dir);

        assertEquals(MAPPER.readTree(get("/api/network").body()), MAPPER.readTree(Files.readString(dir.resolve("network.json"))));
        assertEquals(MAPPER.readTree(get("/api/trace/build").body()), MAPPER.readTree(Files.readString(dir.resolve("build.json"))));
        JsonNode samples = MAPPER.readTree(Files.readString(dir.resolve("journeys.json")));
        assertTrue(samples.size() > 0, "the bundle carries sample journeys");
        assertEquals("bundle", MAPPER.readTree(Files.readString(dir.resolve("world.json"))).at("/provenance/source").asText());
    }
}
