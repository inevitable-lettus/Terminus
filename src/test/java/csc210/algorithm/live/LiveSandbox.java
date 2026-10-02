package csc210.algorithm.live;

import csc210.algorithm.client.SandboxClient;
import csc210.algorithm.client.SnapshotData;
import csc210.algorithm.client.TopologyData;
import csc210.algorithm.cost.CostModel;
import csc210.algorithm.demand.DemandModel;
import csc210.algorithm.graph.Graph;
import csc210.algorithm.routing.RouteBudget;
import csc210.algorithm.world.WorldLoader;

import java.io.IOException;
import java.time.Duration;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Shared setup for the live suite: puts a running DSTNS into one known,
 * reproducible state and hands back everything built from it.
 *
 * <p>The state is pinned three ways. The <strong>seed</strong> fixes the random
 * draws the simulator makes. The <strong>virtual time</strong> fixes where in the
 * simulated day we are looking. And the clock is <strong>paused before seeking</strong>,
 * because {@code play: false} on a seek does not stop a clock that is already
 * running — without the explicit pause the simulation carries on from wherever it
 * was put and the state read back is a few minutes past the time asked for.
 *
 * <p><strong>Two ways in, decided by the operator token.</strong> DSTNS 2.0 only
 * starts a run for its operator. With {@code DSTNS_OPERATOR_TOKEN} set in the
 * environment this class starts a run of its own at {@link #SEED}, as before. Without
 * it, it <em>attaches</em> to whatever run is already going — pausing and seeking
 * it, which are not gated — and skips if there is none. It never stops a run it
 * could not restart. In attach mode the seed is whatever the operator chose, so a
 * test that depends on a particular map should say so.
 *
 * <p>If no sandbox is reachable the tests <em>skip</em> rather than fail. A test
 * that cannot run has not found a defect, and reporting it as one would train
 * everybody to ignore red builds.
 */
final class LiveSandbox {

    /** Same seed the fixtures were captured at, so live and saved data are comparable. */
    static final String SEED = "0x2026";

    /** The demand peak the route network is designed for. */
    static final String DESIGN_TIME = "18:00:00";

    private static final Duration READY_TIMEOUT = Duration.ofMinutes(3);

    private LiveSandbox() {}

    /** Everything downstream of one live read, built exactly as production code would. */
    record World(
            SandboxClient client,
            TopologyData topology,
            SnapshotData snapshot,
            Graph graph,
            CostModel costModel,
            DemandModel demandModel,
            RouteBudget budget
    ) {
        static World of(SandboxClient client, csc210.algorithm.world.World built) {
            return new World(client, built.topology(), built.snapshot(), built.graph(),
                    built.costModel(), built.demandModel(), built.budget());
        }
    }

    static SandboxClient client() {
        return new SandboxClient(System.getProperty("sandbox.url", "http://127.0.0.1:8090"))
                .withOperatorToken(System.getenv("DSTNS_OPERATOR_TOKEN"));
    }

    /** Skips the whole test unless a sandbox answers on the configured URL. */
    static SandboxClient requireReachableSandbox() {
        SandboxClient client = client();
        boolean up;
        try {
            up = client.health().ok();
        } catch (IOException | InterruptedException e) {
            up = false;
        }
        assumeTrue(up, "no DSTNS sandbox reachable at "
                + System.getProperty("sandbox.url", "http://127.0.0.1:8090") + " — skipping live tests");
        return client;
    }

    /** Whether this JVM has already started its own run; one per suite is enough. */
    private static boolean startedOurRun;

    /**
     * Parks the simulator at {@code virtualTime} and reads the world from it.
     *
     * <p>With an operator token a run may already be active, and starting one then
     * answers HTTP 409, so the existing run is stopped first and a fresh one
     * started at {@link #SEED} — once per suite, not once per test: every test
     * pauses and seeks before it reads, so a shared run gives each the same
     * world, and restarting a real city costs tens of seconds each time.
     * Without a token the existing run is adopted.
     */
    static synchronized World at(String virtualTime) throws IOException, InterruptedException {
        SandboxClient client = requireReachableSandbox();

        if (client.hasOperatorToken() && !startedOurRun) {
            try {
                client.stopPlayback();
            } catch (csc210.algorithm.client.SandboxHttpException nothingToStop) {
                // The simulator answered; nothing it owns is in our way.
            }
            client.awaitLeaving("STOPPING", READY_TIMEOUT, Duration.ofSeconds(1));
            client.startPlayback(SEED, "00:00:00", 600);
            client.awaitReady(READY_TIMEOUT, Duration.ofSeconds(1));
            startedOurRun = true;
        } else if (!client.hasOperatorToken()) {
            String lifecycle = client.health().lifecycle();
            assumeTrue(lifecycle != null && !"IDLE".equalsIgnoreCase(lifecycle),
                    "DSTNS 2.0 only starts runs for its operator: set DSTNS_OPERATOR_TOKEN, "
                            + "or start a run in the operator CLI and rerun");
        }
        client.pausePlayback();
        client.seek(virtualTime);

        return World.of(client, WorldLoader.load(client));
    }

    /** Re-reads live state without touching the clock, for before-and-after comparisons. */
    static World reread(SandboxClient client, TopologyData topology) throws IOException, InterruptedException {
        return World.of(client, WorldLoader.load(client));
    }
}
