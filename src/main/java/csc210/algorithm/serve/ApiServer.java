package csc210.algorithm.serve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import csc210.algorithm.cli.UsageException;
import csc210.algorithm.client.RunStateException;
import csc210.algorithm.client.SandboxJson;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.zip.GZIPOutputStream;

/**
 * {@code ./bus serve}: the planned city over HTTP, for the dashboard and for
 * the ticketing portal. Built on the JDK's own {@code HttpServer}, so it adds no
 * dependency.
 *
 * <pre>
 *   GET  /api/health                          is it up, and which run it holds
 *   GET  /api/world                           junctions, roads, stops, provenance
 *   GET  /api/network                         the lines, both directions, and coverage
 *   GET  /api/journey?from=&amp;to=&amp;board_wait=   one journey, A* and Dijkstra's work
 *   GET  /api/trace/search?from=&amp;to=&amp;algo=    the order a search settled junctions
 *   GET  /api/trace/build                     the greedy builder's decisions
 *   POST /api/reload        {"time":"HH:MM:SS"}  re-read the simulator and re-plan
 *   POST /api/v1/routes/query                 the ticketing portal's contract
 *   GET  /api/v1/stops                        stop ids for the portal to map
 *   GET  /                                    the dashboard, if it has been built
 * </pre>
 *
 * <p>Every answer comes from one immutable {@link PlannedCity}. A reload plans a
 * new one and swaps it in whole, so no request ever sees half of each.
 */
public final class ApiServer implements AutoCloseable {

    /** Re-reads the simulator, at {@code time} if given, and plans the result. */
    @FunctionalInterface
    public interface Reloader {
        PlannedCity reload(String time) throws IOException, InterruptedException;
    }

    private final HttpServer server;
    private final ObjectMapper api = SandboxJson.mapper();
    /** The portal's contract is camelCase, not this module's snake_case. */
    private final ObjectMapper portal = new ObjectMapper();
    private final Reloader reloader;
    private final Path staticRoot;
    private final String sandboxUrl;
    private final CountDownLatch closed = new CountDownLatch(1);
    private volatile PlannedCity city;

    private ApiServer(HttpServer server, PlannedCity city, Reloader reloader, Path staticRoot, String sandboxUrl) {
        this.server = server;
        this.city = city;
        this.reloader = reloader;
        this.staticRoot = staticRoot;
        this.sandboxUrl = sandboxUrl;
    }

    /**
     * Starts serving {@code city} on {@code host:port}; port 0 picks a free one.
     *
     * @param reloader how {@code POST /api/reload} gets a new city, or {@code null} to refuse reloads
     * @param staticRoot the built dashboard to serve at {@code /}, or {@code null}
     */
    public static ApiServer start(String host, int port, PlannedCity city, Reloader reloader, Path staticRoot,
                                  String sandboxUrl) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress(host, port), 0);
        ApiServer server = new ApiServer(http, city, reloader, staticRoot, sandboxUrl);
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        http.createContext("/api/health", server.guard("GET", exchange -> server.json(exchange, 200,
                new Views.Health(true, server.city.source(), sandboxUrl,
                        server.city.run().runId(), server.city.run().time()))));
        http.createContext("/api/world", server.guard("GET", exchange -> server.json(exchange, 200,
                server.city.worldView())));
        http.createContext("/api/network", server.guard("GET", exchange -> server.json(exchange, 200,
                server.city.networkView())));
        http.createContext("/api/journey", server.guard("GET", exchange -> {
            Map<String, String> query = query(exchange.getRequestURI());
            long waitMs = Math.round(number(query, "board_wait", 0.0) * 60_000.0);
            server.json(exchange, 200, server.city.journeyView(integer(query, "from"), integer(query, "to"), waitMs));
        }));
        http.createContext("/api/trace/search", server.guard("GET", exchange -> {
            Map<String, String> query = query(exchange.getRequestURI());
            server.json(exchange, 200, server.city.searchTraceView(integer(query, "from"), integer(query, "to"),
                    query.getOrDefault("algo", "astar")));
        }));
        http.createContext("/api/trace/build", server.guard("GET", exchange -> server.json(exchange, 200,
                server.city.buildTraceView())));
        http.createContext("/api/reload", server.guard("POST", exchange -> {
            if (server.reloader == null) {
                throw new RunStateException("this server holds a saved bundle, not a live run, so it cannot reload");
            }
            byte[] body = exchange.getRequestBody().readAllBytes();
            String time = body.length == 0 ? null : server.api.readTree(body).path("time").asText(null);
            if (time != null && !time.matches("([01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d")) {
                throw new UsageException("time must look like HH:MM:SS, got '" + time + "'");
            }
            server.city = server.reloader.reload(time);
            server.json(exchange, 200, server.city.worldView().provenance());
        }));
        http.createContext("/api/v1/routes/query", server.guard("POST", exchange -> {
            PortalContract.Query query = server.portal.readValue(exchange.getRequestBody().readAllBytes(),
                    PortalContract.Query.class);
            server.send(exchange, 200, server.portal.writeValueAsBytes(PortalContract.answer(server.city, query)));
        }));
        http.createContext("/api/v1/stops", server.guard("GET", exchange -> server.send(exchange, 200,
                server.portal.writeValueAsBytes(PortalContract.stops(server.city)))));
        http.createContext("/", server.guard("GET", server::serveStatic));
        http.start();
        return server;
    }

    /** The port actually bound, which matters when 0 was asked for. */
    public int port() {
        return server.getAddress().getPort();
    }

    /** Blocks until {@link #close()} is called, for the command line. */
    public void awaitClose() throws InterruptedException {
        closed.await();
    }

    @Override
    public void close() {
        server.stop(0);
        closed.countDown();
    }

    // ---- plumbing ----------------------------------------------------------

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws Exception;
    }

    /**
     * CORS on every answer, a 204 to a preflight, the method checked, and every
     * failure mapped to a status and the same error envelope DSTNS uses.
     */
    private HttpHandler guard(String method, Handler handler) {
        return exchange -> {
            try {
                exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
                exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                if (exchange.getRequestMethod().equals("OPTIONS")) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                if (!exchange.getRequestMethod().equals(method)) {
                    error(exchange, 405, "METHOD_NOT_ALLOWED", exchange.getRequestMethod() + " is not accepted here");
                    return;
                }
                handler.handle(exchange);
            } catch (UsageException | com.fasterxml.jackson.core.JsonProcessingException bad) {
                error(exchange, 400, "BAD_REQUEST", bad.getMessage());
            } catch (PortalContract.UnknownStop unknown) {
                error(exchange, 404, "STOP_NOT_FOUND", unknown.getMessage());
            } catch (PortalContract.NoRoute none) {
                error(exchange, 422, "NO_ROUTE", none.getMessage());
            } catch (RunStateException state) {
                error(exchange, 409, "RUN_STATE", state.getMessage());
            } catch (IOException simulator) {
                error(exchange, 502, "SIMULATOR_UNAVAILABLE", simulator.getMessage());
            } catch (Exception internal) {
                error(exchange, 500, "INTERNAL_ERROR", internal.getClass().getSimpleName() + ": " + internal.getMessage());
            } finally {
                // After the catches, not before: an error still needs the exchange open to be sent.
                exchange.close();
            }
        };
    }

    private void error(HttpExchange exchange, int status, String code, String message) {
        try {
            send(exchange, status, api.writeValueAsBytes(new Views.Error(code, message)));
        } catch (IOException | IllegalStateException alreadyAnswered) {
            // The headers were already sent, or the client has gone; nothing more to say.
        }
    }

    private void json(HttpExchange exchange, int status, Object body) throws IOException {
        send(exchange, status, api.writeValueAsBytes(body));
    }

    /** Sends JSON, gzipped when the client accepts it — a real city's world is a megabyte or two of text. */
    private void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        send(exchange, status, body, "application/json; charset=utf-8");
    }

    private void send(HttpExchange exchange, int status, byte[] body, String contentType) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        String accepts = exchange.getRequestHeaders().getFirst("Accept-Encoding");
        if (accepts != null && accepts.contains("gzip") && body.length > 1024) {
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(status, 0);
            try (OutputStream out = new GZIPOutputStream(exchange.getResponseBody())) {
                out.write(body);
            }
            return;
        }
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8", "js", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8", "json", "application/json; charset=utf-8",
            "svg", "image/svg+xml", "png", "image/png", "woff2", "font/woff2", "ico", "image/x-icon");

    /** The built dashboard, with any unknown path falling back to its index page. */
    private void serveStatic(HttpExchange exchange) throws IOException {
        if (staticRoot == null || !Files.isDirectory(staticRoot)) {
            byte[] text = ("The API is up. The dashboard has not been built: run `npm run build` in dashboard/, "
                    + "or `npm run dev` there to develop against this server.\n").getBytes(StandardCharsets.UTF_8);
            send(exchange, 200, text, "text/plain; charset=utf-8");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        Path root = staticRoot.toAbsolutePath().normalize();
        Path file = root.resolve(path.substring(1)).normalize();
        if (!file.startsWith(root) || Files.isDirectory(file) || !Files.exists(file)) {
            file = root.resolve("index.html");
        }
        String name = file.getFileName().toString();
        String extension = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        send(exchange, 200, Files.readAllBytes(file), TYPES.getOrDefault(extension, "application/octet-stream"));
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> values = new HashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null) {
            return values;
        }
        for (String pair : raw.split("&")) {
            int equals = pair.indexOf('=');
            String key = URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), StandardCharsets.UTF_8);
            String value = equals < 0 ? "" : URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            values.put(key, value);
        }
        return values;
    }

    private static int integer(Map<String, String> query, String name) {
        String text = query.get(name);
        if (text == null) {
            throw new UsageException(name + " is required");
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException notANumber) {
            throw new UsageException(name + " must be a junction id, got '" + text + "'");
        }
    }

    private static double number(Map<String, String> query, String name, double otherwise) {
        String text = query.get(name);
        if (text == null || text.isEmpty()) {
            return otherwise;
        }
        try {
            double value = Double.parseDouble(text);
            if (Double.isFinite(value) && value >= 0.0) {
                return value;
            }
        } catch (NumberFormatException notANumber) {
            // fall through
        }
        throw new UsageException(name + " must be a number of minutes, zero or more, got '" + text + "'");
    }
}
