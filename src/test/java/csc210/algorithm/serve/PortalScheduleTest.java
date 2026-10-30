package csc210.algorithm.serve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import csc210.algorithm.Fixtures;
import csc210.algorithm.cli.RunInfo;
import csc210.algorithm.cli.UsageException;
import csc210.algorithm.client.SandboxClient;
import csc210.algorithm.routing.BusRoute;
import csc210.algorithm.world.World;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Items 4 and 5: the portal's new endpoints, and departureTime / tripId on the journey query. */
class PortalScheduleTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static PlannedCity city;
    private static ApiServer server;

    @BeforeAll
    static void start() throws Exception {
        World world = Fixtures.v2World1800();
        city = PlannedCity.plan(world, new RunInfo("run_test", "0x2026", "18:00:00", true), world.budget(), "live");
        server = ApiServer.start("127.0.0.1", 0, city, null, null, "http://sandbox.test");
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private static JsonNode get(String path) throws Exception {
        HttpResponse<String> answer = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + server.port() + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, answer.statusCode(), answer.body());
        return JSON.readTree(answer.body());
    }

    private static HttpResponse<String> query(String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.port() + "/api/v1/routes/query"))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void liveCarriesTheRunAndTheClockInSeconds() throws Exception {
        JsonNode live = get("/api/v1/live").path("data");
        assertEquals("run_test", live.path("runId").asText());
        assertEquals(18 * 3600, live.path("simTime").asInt());
        assertTrue(live.path("buses").isArray());
    }

    @Test
    void routesListEachLinesStopsBothWays() throws Exception {
        JsonNode routes = get("/api/v1/routes").path("data");
        assertEquals(city.network().routeCount(), routes.size());
        BusRoute first = city.network().routes().get(0);
        assertEquals("route_0", routes.get(0).path("routeId").asText());
        assertEquals("stop_" + first.stops()[0], routes.get(0).path("stops").get(0).asText());
        assertEquals("stop_" + first.back().stopOccurrences()[0], routes.get(0).path("returnStops").get(0).asText());
    }

    @Test
    void tripsCarryTheTimetableAndAnEtaAtEveryStop() throws Exception {
        JsonNode trips = get("/api/v1/trips").path("data");
        assertEquals(city.timetable().trips().size(), trips.size());
        JsonNode first = trips.get(0);
        assertEquals("route_0-out-0600", first.path("tripId").asText());
        assertEquals(40, first.path("seats").asInt());
        JsonNode stopTimes = first.path("stopTimes");
        assertEquals(city.network().routes().get(0).stopOccurrences().length, stopTimes.size());
        assertEquals(6 * 3600, stopTimes.get(0).path("etaSimS").asInt(), "the bus is at its first stop when it leaves");
        for (int i = 1; i < stopTimes.size(); i++) {
            assertTrue(stopTimes.get(i).path("etaSimS").asInt() >= stopTimes.get(i - 1).path("etaSimS").asInt());
        }
    }

    /** A line whose first two stops are joined by a direct ride, and the query for it. */
    private static String[] directRide() throws Exception {
        for (BusRoute route : city.network().routes()) {
            String from = "stop_" + route.stopOccurrences()[0];
            String to = "stop_" + route.stopOccurrences()[1];
            JsonNode segments = JSON.readTree(query("{\"sourceStopId\":\"" + from + "\",\"destStopId\":\"" + to
                    + "\",\"departureTime\":\"07:07:00\"}").body()).at("/data/segments");
            if (segments.size() == 1 && ("route_" + route.id()).equals(segments.get(0).path("routeId").asText())) {
                return new String[] {from, to, "route_" + route.id()};
            }
        }
        throw new AssertionError("no line in the capture has a direct first ride");
    }

    @Test
    void departureTimePicksTheNextBusFromTheFirstStop() throws Exception {
        String[] ride = directRide();
        String body = "{\"sourceStopId\":\"" + ride[0] + "\",\"destStopId\":\"" + ride[1] + "\",\"departureTime\":";
        JsonNode at0707 = JSON.readTree(query(body + "\"07:07:00\"}").body()).at("/data/segments/0");
        assertEquals(ride[2] + "-out-0715", at0707.path("tripId").asText());
        assertEquals(at0707.path("tripId").asText(), at0707.path("busId").asText());

        JsonNode asSeconds = JSON.readTree(query(body + "25620}").body()).at("/data/segments/0");
        assertEquals(ride[2] + "-out-0715", asSeconds.path("tripId").asText(), "seconds of the day work too");

        JsonNode onTheDot = JSON.readTree(query(body + "\"07:15:00\"}").body()).at("/data/segments/0");
        assertEquals(ride[2] + "-out-0715", onTheDot.path("tripId").asText(), "at or after, not strictly after");

        JsonNode afterLastBus = JSON.readTree(query(body + "\"21:50:00\"}").body()).at("/data/segments/0");
        assertTrue(afterLastBus.path("tripId").isNull());
    }

    @Test
    void noDepartureTimeMeansTheSimulatorsClockAndUnknownFieldsAreIgnored() throws Exception {
        String[] ride = directRide();
        HttpResponse<String> answer = query("{\"sourceStopId\":\"" + ride[0] + "\",\"destStopId\":\"" + ride[1]
                + "\",\"passengers\":2}");
        assertEquals(200, answer.statusCode(), answer.body());
        assertEquals(ride[2] + "-out-1800", JSON.readTree(answer.body()).at("/data/segments/0/tripId").asText());
    }

    @Test
    void aBadDepartureTimeIsA400() throws Exception {
        String[] ride = directRide();
        assertEquals(400, query("{\"sourceStopId\":\"" + ride[0] + "\",\"destStopId\":\"" + ride[1]
                + "\",\"departureTime\":\"teatime\"}").statusCode());
        assertThrows(UsageException.class, () -> PortalContract.answer(city,
                new PortalContract.Query(ride[0], ride[1], "25:00:00")));
    }

    @Test
    void stopsCarryDstnsNamesThroughTheirAnchorJunction() throws Exception {
        int named = city.world().stops().representatives()[0];
        PlannedCity withNames = city.withStopNames(Map.of(named, "Kariakoo Market"));
        List<PortalContract.StopInfo> stops = PortalContract.stops(withNames).data();
        assertEquals("Kariakoo Market", stops.get(0).name());
        assertNull(stops.get(1).name(), "a stop DSTNS does not name has no name; the portal falls back to its id");
        assertNotNull(get("/api/v1/stops").path("data").get(0).path("stopId").asText());
    }

    @Test
    void stopNamesAreReadFromEitherShapeOfTheReply() throws Exception {
        String places = "[{\"id\":1,\"name\":\"Kariakoo\",\"kind\":\"bus_stop\",\"anchor_node\":12},"
                + "{\"id\":2,\"name\":\"\",\"anchor_node\":13},{\"id\":3,\"name\":\"No anchor\"}]";
        Map<Integer, String> fromList = SandboxClient.stopNamesFrom(JSON.readTree(places));
        Map<Integer, String> fromItems = SandboxClient.stopNamesFrom(JSON.readTree("{\"items\":" + places + "}"));
        assertEquals(Map.of(12, "Kariakoo"), fromList);
        assertEquals(fromList, fromItems);
    }
}
