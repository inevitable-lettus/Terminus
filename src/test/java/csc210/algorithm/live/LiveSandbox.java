package csc210.algorithm.live;

import csc210.algorithm.client.SandboxClient;
import csc210.algorithm.client.SnapshotData;
import csc210.algorithm.client.TopologyData;
import csc210.algorithm.cost.CostModel;
import csc210.algorithm.demand.DemandModel;
import csc210.algorithm.graph.Graph;

import java.io.IOException;

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
 * <p>If no sandbox is reachable the tests <em>skip</em> rather than fail. A test
 * that cannot run has not found a defect, and reporting it as one would train
 * everybody to ignore red builds.
 */
final class LiveSandbox {

    /** Same seed the fixtures were captured at, so live and saved data are comparable. */
    static final String SEED = "0x2026";

    /** The demand peak the route network is designed for. */
    static final String DESIGN_TIME = "18:00:00";

    private LiveSandbox() {}

    /** Everything downstream of one live read, built exactly as production code would. */
    record World(
            SandboxClient client,
            TopologyData topology,
            SnapshotData snapshot,
            Graph graph,
            CostModel costModel,
            DemandModel demandModel
    ) {}

    static SandboxClient client() {
        return new SandboxClient(System.getProperty("sandbox.url", "http://127.0.0.1:8090"));
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

    /**
     * Parks the simulator at {@link #DESIGN_TIME} on seed {@link #SEED} and reads
     * the world from it.
     *
     * <p>A run may already be active from an earlier test or an earlier session,
     * and starting one then answers HTTP 409. Rather than guess, the existing run
     * is stopped first and a fresh one started, so the seed is known rather than
     * inherited.
     */
    static World at(String virtualTime) throws IOException, InterruptedException {
        SandboxClient client = requireReachableSandbox();

        try {
            client.stopPlayback();
        } catch (IOException alreadyStopped) {
            // Nothing was running. Not a failure — that is the state we wanted.
        }
        client.startPlayback(SEED, "00:00:00", 600);
        client.pausePlayback();
        client.seek(virtualTime);

        TopologyData topology = client.topology();
        SnapshotData snapshot = client.snapshot();
        Graph graph = Graph.of(topology.nodes(), topology.edges());

        return new World(client, topology, snapshot, graph,
                CostModel.of(graph, snapshot), DemandModel.of(graph, snapshot));
    }

    /** Re-reads live state without touching the clock, for before-and-after comparisons. */
    static World reread(SandboxClient client, TopologyData topology) throws IOException, InterruptedException {
        SnapshotData snapshot = client.snapshot();
        Graph graph = Graph.of(topology.nodes(), topology.edges());
        return new World(client, topology, snapshot, graph,
                CostModel.of(graph, snapshot), DemandModel.of(graph, snapshot));
    }
}
