package csc210.algorithm.fabric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import csc210.algorithm.client.SandboxHttpException;
import csc210.algorithm.client.SandboxJson;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Talks to DSTNS as a Fabric application ({@code /api/v1/fabric/apps/{id}/...}).
 *
 * <p>Registering returns a token that proves the app id is ours. DSTNS refuses a second
 * registration of a live id without it ({@code 409 APP_ID_CONFLICT}), so the caller keeps
 * it (see {@link TokenStore}) and hands it back here after a restart. The token is never
 * printed: {@link #toString()} leaves it out.
 */
public final class FabricClient {

    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** One page of events: the items, and the sequence to ask from next time. */
    public record Events(List<JsonNode> items, long sequence, long dropped) {
    }

    private final URI base;
    private final String appId;
    private final HttpClient http;
    private final ObjectMapper json;
    private volatile String token;

    public FabricClient(String sandboxUrl, String appId) {
        this(sandboxUrl, appId, HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build());
    }

    public FabricClient(String sandboxUrl, String appId, HttpClient http) {
        this.base = URI.create(sandboxUrl.endsWith("/") ? sandboxUrl : sandboxUrl + "/");
        this.appId = appId;
        this.http = http;
        this.json = SandboxJson.mapper();
    }

    public String appId() {
        return appId;
    }

    public String token() {
        return token;
    }

    /** Use a token kept from an earlier run, so registering again keeps the same identity. */
    public void useToken(String saved) {
        token = saved == null || saved.isBlank() ? null : saved.strip();
    }

    /**
     * Registers (or re-registers, if a token is held) from {@code manifest}, and keeps the
     * token DSTNS answers with.
     */
    public JsonNode register(Map<String, Object> manifest) throws IOException, InterruptedException {
        JsonNode data = send("POST", "api/v1/fabric/apps", manifest);
        String issued = data.path("token").asText("");
        if (!issued.isEmpty()) {
            token = issued;
        }
        return data.path("app");
    }

    public void heartbeat() throws IOException, InterruptedException {
        send("POST", app("heartbeat"), Map.of());
    }

    /** {@code initialized}, {@code active} or {@code failed}. The launcher waits for {@code active}. */
    public void setState(String state, String detail) throws IOException, InterruptedException {
        send("PUT", app("state"), Map.of("state", state, "detail", detail == null ? "" : detail));
    }

    public void subscribe(List<String> topics) throws IOException, InterruptedException {
        send("PUT", app("subscriptions"), Map.of("topics", topics));
    }

    public Events events(long since) throws IOException, InterruptedException {
        JsonNode data = send("GET", app("events?since=" + since), null);
        List<JsonNode> items = new ArrayList<>();
        data.path("items").forEach(items::add);
        return new Events(items, data.path("sequence").asLong(since), data.path("dropped").asLong(0));
    }

    /** Replaces every UI element this app shows in the observer. */
    public void putUi(List<Map<String, Object>> elements) throws IOException, InterruptedException {
        send("PUT", app("ui"), elements);
    }

    /** Upserts and removes elements in one step, so the observer never shows half an update. */
    public void batchUi(List<Map<String, Object>> upsert, List<String> remove) throws IOException, InterruptedException {
        send("POST", app("ui/batch"), Map.of("upsert", upsert, "remove", remove));
    }

    /** Our bus stops, as junction ids. DSTNS shows them; the simulation does not read them. */
    public void putStops(List<Integer> nodes) throws IOException, InterruptedException {
        send("PUT", app("stops"), Map.of("nodes", nodes));
    }

    /** Leaves cleanly: the id is free again, so the next start can register without a token. */
    public void unload() throws IOException, InterruptedException {
        send("DELETE", "api/v1/fabric/apps/" + appId, null);
    }

    private String app(String rest) {
        return "api/v1/fabric/apps/" + appId + "/" + rest;
    }

    private JsonNode send(String method, String path, Object body) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path)).timeout(REQUEST_TIMEOUT);
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        }
        String held = token;
        if (held != null) {
            request.header("Authorization", "Bearer " + held);
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode answer = response.body().isEmpty() ? json.createObjectNode() : json.readTree(response.body());
        if (response.statusCode() / 100 != 2) {
            JsonNode error = answer.path("error");
            throw new SandboxHttpException(method, "/" + path, response.statusCode(),
                    error.path("code").asText(null), error.path("message").asText(response.body()));
        }
        return answer.path("data");
    }

    @Override
    public String toString() {
        return "FabricClient[" + appId + " at " + base + (token == null ? ", no token" : ", token held") + "]";
    }
}
