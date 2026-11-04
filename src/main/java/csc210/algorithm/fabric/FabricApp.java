package csc210.algorithm.fabric;

import com.fasterxml.jackson.databind.JsonNode;
import csc210.algorithm.client.SandboxHttpException;

import java.io.IOException;
import java.io.PrintStream;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Terminus as a DSTNS Fabric application, the way the DSTNS launcher expects one to behave:
 * <ol>
 *   <li>register, keeping the token so a restart keeps the same id;</li>
 *   <li>say {@code active} straight away, because the launcher only starts the run after that;</li>
 *   <li>wait for the run to start before reading the map, which does not exist until then;</li>
 *   <li>heartbeat and read events every 2 seconds, and pass each event on;</li>
 *   <li>unload on the way out, so the next start can register again.</li>
 * </ol>
 */
public final class FabricApp implements AutoCloseable {

    public static final long POLL_SECONDS = 2;
    public static final int LEASE_SECONDS = 30;
    public static final List<String> TOPICS = List.of("lifecycle", "world", "news.incident");
    private static final Set<String> HAS_WORLD = Set.of("READY", "RUNNING", "PAUSED");

    private final FabricClient fabric;
    private final TokenStore tokens;
    private final PrintStream notes;
    private final CountDownLatch worldReady = new CountDownLatch(1);
    private final ScheduledExecutorService poller;
    private volatile Consumer<JsonNode> listener = event -> { };
    private long since = 0;
    private boolean failing = false;

    private FabricApp(FabricClient fabric, TokenStore tokens, PrintStream notes) {
        this.fabric = fabric;
        this.tokens = tokens;
        this.notes = notes;
        this.poller = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "fabric");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** What DSTNS shows for Terminus. Only automatic capabilities: Terminus never changes the simulation. */
    public static Map<String, Object> manifest(String appId) {
        return Map.of(
                "id", appId,
                "name", "Terminus",
                "version", "1.0.0",
                "description", "Bus routes, timetables and journey planning on the live city.",
                "capabilities", List.of("ui.panels", "ui.overlays", "ui.status", "ui.map-controls", "sim.read"),
                "lease_s", LEASE_SECONDS);
    }

    /**
     * Registers, says {@code active}, and starts the heartbeat.
     *
     * @param lifecycle the simulator's lifecycle now, so a run that is already going is not waited for
     */
    public static FabricApp join(FabricClient fabric, TokenStore tokens, String lifecycle, PrintStream notes)
            throws IOException, InterruptedException {
        FabricApp app = new FabricApp(fabric, tokens, notes);
        fabric.useToken(tokens.load());
        try {
            fabric.register(manifest(fabric.appId()));
        } catch (SandboxHttpException refused) {
            if ("APP_ID_CONFLICT".equals(refused.code())) {
                throw new IOException("DSTNS already has an application '" + fabric.appId() + "' and "
                        + tokens.file() + " does not hold its token. Stop the other copy, or wait about "
                        + (5 * LEASE_SECONDS) + " s for DSTNS to unload it, then try again.", refused);
            }
            throw refused;
        }
        tokens.save(fabric.token());
        fabric.subscribe(TOPICS);
        fabric.setState("active", "");
        if (lifecycle != null && HAS_WORLD.contains(lifecycle.toUpperCase(Locale.ROOT))) {
            app.worldReady.countDown();
        }
        app.poller.scheduleWithFixedDelay(app::poll, 0, POLL_SECONDS, TimeUnit.SECONDS);
        notes.println("Joined DSTNS as Fabric application '" + fabric.appId() + "'.");
        return app;
    }

    public FabricClient client() {
        return fabric;
    }

    /** Called on the Fabric thread for every event, after this app has looked at it. */
    public void onEvent(Consumer<JsonNode> handler) {
        listener = handler == null ? event -> { } : handler;
    }

    /** Blocks until the simulator has a world to read. */
    public void awaitWorld(Duration timeout) throws IOException, InterruptedException {
        if (!worldReady.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new IOException("no simulation started within " + timeout.toSeconds()
                    + " s; start one from the DSTNS launcher");
        }
    }

    /** Whether a lifecycle event says the world is there to read. */
    static boolean bringsWorld(JsonNode event) {
        return "lifecycle.changed".equals(event.path("type").asText())
                && HAS_WORLD.contains(event.path("data").path("to").asText().toUpperCase(Locale.ROOT));
    }

    private void poll() {
        try {
            fabric.heartbeat();
            FabricClient.Events page = fabric.events(since);
            since = page.sequence();
            for (JsonNode event : page.items()) {
                if (bringsWorld(event)) {
                    worldReady.countDown();
                }
                listener.accept(event);
            }
            if (failing) {
                notes.println("Fabric is answering again.");
            }
            failing = false;
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } catch (Exception problem) {
            // Caught so one failed poll does not cancel every later one.
            if (!failing) {
                notes.println("Fabric poll failed, will retry: " + problem.getMessage());
            }
            failing = true;
        }
    }

    @Override
    public void close() {
        poller.shutdownNow();
        try {
            fabric.unload();
            tokens.clear();
        } catch (IOException | InterruptedException leaving) {
            notes.println("Could not unload from Fabric: " + leaving.getMessage());
        }
    }
}
