package csc210.algorithm.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Wrapper over DSTNS's HTTP API. One instance is reused across every call —
 * {@link HttpClient} is safe to share and pools connections.
 *
 * <p>{@code /health} lives at the root and is unwrapped; every {@code /view/*}
 * endpoint lives under {@code /api/v1} and returns an {@link Envelope} whose
 * {@code data} is parsed into the endpoint-specific type.
 *
 * <p>The reads are safe to repeat. The writes below are not: they change the
 * simulator's state, so a failure is reported rather than retried, and the
 * caller decides what to do about it.
 */
public final class SandboxClient {

    /** How long to wait for the TCP handshake before giving up. */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** How long to wait for one request to finish once sent, headers through body. */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final URI baseUri;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration requestTimeout;

    public SandboxClient(String baseUrl) {
        this(baseUrl,
                HttpClient.newBuilder().connectTimeout(DEFAULT_CONNECT_TIMEOUT).build(),
                SandboxJson.mapper(),
                DEFAULT_REQUEST_TIMEOUT);
    }

    public SandboxClient(String baseUrl, HttpClient httpClient, ObjectMapper objectMapper) {
        this(baseUrl, httpClient, objectMapper, DEFAULT_REQUEST_TIMEOUT);
    }

    /**
     * As the other constructors, with the per-request deadline given
     * explicitly — every SandboxClient operation waits at most this long for
     * a response before failing with an {@link IOException}, so a stalled
     * dependency cannot block the caller forever.
     */
    public SandboxClient(String baseUrl, HttpClient httpClient, ObjectMapper objectMapper, Duration requestTimeout) {
        this.baseUri = URI.create(baseUrl);
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.requestTimeout = requestTimeout;
    }

    public HealthStatus health() throws IOException, InterruptedException {
        return get("/health", HealthStatus.class);
    }

    public TopologyData topology() throws IOException, InterruptedException {
        Envelope envelope = get("/api/v1/view/topology", Envelope.class);
        return objectMapper.treeToValue(envelope.data(), TopologyData.class);
    }

    /** Live edge/node state at the current instant of virtual time. */
    public SnapshotData snapshot() throws IOException, InterruptedException {
        Envelope envelope = get("/api/v1/view/snapshot", Envelope.class);
        return objectMapper.treeToValue(envelope.data(), SnapshotData.class);
    }

    /**
     * Every edge in the nested {@code /view/edges} shape, paged until the
     * server says it has sent them all. This is the only endpoint carrying
     * {@code demand_vph}, which step 4.1 needs.
     */
    public java.util.List<EdgeDetail> edges() throws IOException, InterruptedException {
        java.util.List<EdgeDetail> all = new java.util.ArrayList<>();
        int offset = 0;
        while (true) {
            Envelope envelope = get("/api/v1/view/edges?offset=" + offset + "&limit=1000", Envelope.class);
            EdgePage page = objectMapper.treeToValue(envelope.data(), EdgePage.class);
            all.addAll(page.items());
            offset += page.items().size();
            if (offset >= page.total()) {
                return all;
            }
            if (page.items().isEmpty()) {
                // Nothing came back, but the server's own total says more
                // edges remain — that is not "done", it is a stalled/
                // inconsistent pagination sequence.  Fail loudly rather than
                // silently truncating the dataset.
                throw new IOException("edge pagination stalled: got an empty page at offset " + offset
                        + " but the server's declared total is " + page.total());
            }
        }
    }

    /**
     * Starts a run at a fixed seed, so that every later read is reproducible.
     *
     * @param seed hex seed string, e.g. {@code "0x2026"}
     * @param startVirtualTime clock position to begin at, {@code "HH:MM:SS"}
     * @param playbackDurationSeconds wall-clock seconds the simulated day is compressed into
     * @throws IOException if a run is already active — DSTNS answers HTTP 409
     *         rather than silently restarting, and that is surfaced, not swallowed
     */
    public JsonNode startPlayback(String seed, String startVirtualTime, int playbackDurationSeconds)
            throws IOException, InterruptedException {
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "seed", seed,
                "start_virtual_time", startVirtualTime,
                "playback_duration_seconds", playbackDurationSeconds,
                "tick_rate", 1));
        return send("POST", "/api/v1/playback/start", body);
    }

    /** Stops the active run, so a fresh {@link #startPlayback} can begin. */
    public JsonNode stopPlayback() throws IOException, InterruptedException {
        return send("POST", "/api/v1/playback/stop", "{}");
    }

    /**
     * Freezes the clock. Must be called before {@link #seek} if the state read
     * afterwards is meant to match the time asked for: {@code play: false} on a
     * seek does <em>not</em> pause a running clock, so the simulation carries on
     * from wherever it was put and the snapshot drifts.
     */
    public JsonNode pausePlayback() throws IOException, InterruptedException {
        return send("POST", "/api/v1/playback/pause", "{}");
    }

    /**
     * Moves the clock to {@code targetTime} ({@code "HH:MM:SS"}) without resuming it.
     * Pause first — see {@link #pausePlayback()}.
     */
    public JsonNode seek(String targetTime) throws IOException, InterruptedException {
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "target_time", targetTime,
                "play", false));
        return send("POST", "/api/v1/playback/seek", body);
    }

    /** Closes an edge to traffic, or reopens it. */
    public JsonNode setEdgeClosed(int edgeId, boolean closed) throws IOException, InterruptedException {
        String body = objectMapper.writeValueAsString(java.util.Map.of("closed", closed));
        return send("PUT", "/api/v1/control/edges/" + edgeId, body);
    }

    /**
     * Publishes one bus line back to the simulator so it renders in the UI.
     *
     * <p>The server validates that <em>every consecutive pair of nodes is joined
     * by a single directed edge</em> and does not path-fill between stops, so
     * {@code nodes} must be the full junction-by-junction traversal. A route
     * listing only the stops it serves is rejected at the first gap — which is
     * why {@link csc210.algorithm.routing.BusRoute#nodes()} has carried the whole
     * traversal since it was first built rather than having it retrofitted here.
     *
     * <p>{@code bus_id} is a <strong>string</strong> on this endpoint, not a
     * number. Sending an integer is answered with
     * {@code 400 INVALID_FIELD_TYPE}; the field names were recorded when the
     * API was first surveyed but their types were not, and a live run is what
     * caught it.
     *
     * @param busId the line's id on the server, e.g. {@code "R2"}
     * @param label what to call it in the UI, e.g. {@code "R2"}
     * @param nodes the full traversal, in travel order
     * @throws IOException if the server rejects the route, carrying its reason
     */
    public JsonNode publishRoute(String busId, String label, int[] nodes)
            throws IOException, InterruptedException {
        if (nodes.length < 2) {
            throw new IllegalArgumentException("a route needs at least two nodes, got " + nodes.length);
        }
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "bus_id", busId,
                "label", label,
                "nodes", nodes));
        JsonNode response = send("POST", "/api/v1/control/transit/route", body);
        // A discontinuous route is normally a 400, which send() has already
        // turned into an exception. The endpoint's contract also allows it to
        // answer 200 carrying valid:false, and a route the server considers
        // invalid must not be reported as published either way.
        if (response.hasNonNull("valid") && !response.get("valid").asBoolean()) {
            throw new IOException("the simulator rejected route " + busId + ": "
                    + response.path("message").asText("no reason given"));
        }
        return response;
    }

    private JsonNode send(String method, String path, String body)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                .header("Content-Type", "application/json")
                .timeout(requestTimeout)
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        // Any 2xx counts as success. These are not all 200: starting a run answers
        // 202 Accepted, because the simulator acknowledges the request and prepares
        // the scenario rather than finishing the work inside the response.
        if (response.statusCode() / 100 != 2) {
            throw new IOException(method + " " + path + " returned HTTP " + response.statusCode()
                    + ": " + response.body());
        }
        return objectMapper.readTree(response.body());
    }

    private <T> T get(String path, Class<T> responseType) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(requestTimeout)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("GET " + path + " returned HTTP " + response.statusCode() + ": " + response.body());
        }
        return objectMapper.readValue(response.body(), responseType);
    }
}
