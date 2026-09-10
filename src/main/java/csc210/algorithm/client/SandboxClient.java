package csc210.algorithm.client;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Thin GET-only wrapper over DSTNS's HTTP API. One instance is reused across
 * every call — {@link HttpClient} is safe to share and pools connections.
 *
 * <p>{@code /health} lives at the root and is unwrapped; every {@code /view/*}
 * endpoint lives under {@code /api/v1} and returns an {@link Envelope} whose
 * {@code data} is parsed into the endpoint-specific type.
 */
public final class SandboxClient {

    private final URI baseUri;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public SandboxClient(String baseUrl) {
        this(baseUrl, HttpClient.newHttpClient(), SandboxJson.mapper());
    }

    public SandboxClient(String baseUrl, HttpClient httpClient, ObjectMapper objectMapper) {
        this.baseUri = URI.create(baseUrl);
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
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
            if (page.items().isEmpty() || offset >= page.total()) {
                return all;
            }
        }
    }

    private <T> T get(String path, Class<T> responseType) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("GET " + path + " returned HTTP " + response.statusCode() + ": " + response.body());
        }
        return objectMapper.readValue(response.body(), responseType);
    }
}
