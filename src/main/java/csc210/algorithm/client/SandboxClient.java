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

    /** The operator credential DSTNS 2.0 requires to start a run; null when not supplied. */
    private final String operatorToken;

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
        this(baseUrl, httpClient, objectMapper, requestTimeout, null);
    }

    private SandboxClient(String baseUrl, HttpClient httpClient, ObjectMapper objectMapper,
                          Duration requestTimeout, String operatorToken) {
        this.baseUri = URI.create(baseUrl);
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.requestTimeout = requestTimeout;
        this.operatorToken = operatorToken == null || operatorToken.isBlank() ? null : operatorToken.strip();
    }

    /**
     * A client that presents {@code token} as the operator credential when it
     * starts a run. DSTNS 2.0 only honours a start that carries it, and only
     * the start: no other request is sent the header.
     */
    public SandboxClient withOperatorToken(String token) {
        return new SandboxClient(baseUri.toString(), httpClient, objectMapper, requestTimeout, token);
    }

    /** Whether this client holds an operator credential, so a caller can check before it stops anything. */
    public boolean hasOperatorToken() {
        return operatorToken != null;
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
     * Waits until the simulator has a world to read: {@code READY}, {@code RUNNING}
     * or {@code PAUSED}. A start is acknowledged with 202 before the world exists —
     * on DSTNS 2.0 it may first have to download a city — so reading straight after
     * it races the preparation.
     *
     * <p>{@code IDLE} and {@code STOPPED} are waited through rather than accepted:
     * right after a start they can be the state that was there <em>before</em> the
     * request took effect, and a world is only worth reading once it is prepared.
     * The timeout is what stops that becoming an endless wait.
     *
     * @throws IOException if the run enters {@code ERROR} or {@code COMPLETED}, or
     *         nothing usable appears within {@code timeout}
     */
    public void awaitReady(Duration timeout, Duration pollEvery) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        String last = "unknown";
        while (true) {
            String lifecycle = health().lifecycle();
            last = lifecycle == null ? "unknown" : lifecycle;
            switch (last.toUpperCase(java.util.Locale.ROOT)) {
                case "READY", "RUNNING", "PAUSED" -> {
                    return;
                }
                case "ERROR", "COMPLETED", "TERMINATING" ->
                    throw new IOException("the simulator is " + last + ", so there is no world to read");
                default -> { /* still preparing, or not yet started: keep waiting */ }
            }
            if (System.nanoTime() >= deadline) {
                throw new IOException("the simulator was still " + last + " after "
                        + timeout.toSeconds() + " s; it may be downloading a map");
            }
            Thread.sleep(pollEvery.toMillis());
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
        return send("POST", "/api/v1/playback/start", body, true);
    }

    /** Stops the active run, so a fresh {@link #startPlayback} can begin. */
    public JsonNode stopPlayback() throws IOException, InterruptedException {
        return send("POST", "/api/v1/playback/stop", "{}");
    }

    /**
     * The live run's identity and clock, raw.
     *
     * <p>Returned unwrapped because the parts wanted here — {@code run_id},
     * {@code global_seed} and the {@code clock} block — sit beside {@code data}
     * in the envelope rather than inside it. Used by {@code --attach} to report
     * which run was read, so a run driven from the UI and a run read from the
     * command line can be shown to be the same one.
     */
    public JsonNode playbackStatus() throws IOException, InterruptedException {
        return get("/api/v1/playback/status", JsonNode.class);
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

    private JsonNode send(String method, String path, String body)
            throws IOException, InterruptedException {
        return send(method, path, body, false);
    }

    /**
     * @param startsRun whether this request begins a run, and so carries the
     *        operator credential when one is held — and no other request does
     */
    private JsonNode send(String method, String path, String body, boolean startsRun)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(path))
                .header("Content-Type", "application/json")
                .timeout(requestTimeout)
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        if (startsRun && operatorToken != null) {
            builder.header("X-DSTNS-Operator", operatorToken);
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 403 && response.body().contains("CLI_START_REQUIRED")) {
            throw new RunStartRefusedException(operatorToken == null
                    ? "the simulator only starts runs for its operator, and no operator token was given"
                    : "the simulator refused the operator token it was given");
        }
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
