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

    /**
     * Live edge/node state — traffic, weather, signals. Left untyped for now:
     * step 1.6 (cost model) is what actually needs this data, and typing it
     * before then would mean building ahead of the current step.
     */
    public Envelope snapshot() throws IOException, InterruptedException {
        return get("/api/v1/view/snapshot", Envelope.class);
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
