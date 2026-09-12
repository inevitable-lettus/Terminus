package csc210.algorithm.live;

import csc210.algorithm.Fixtures;
import csc210.algorithm.client.SandboxClient;
import csc210.algorithm.client.SnapshotData;
import csc210.algorithm.client.TopologyData;
import csc210.algorithm.model.Edge;
import csc210.algorithm.model.EdgeState;
import csc210.algorithm.model.Node;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Does the live sandbox still look the way this module assumes it does?
 *
 * <p>Everything else in the project is tested against saved responses, which is
 * right — but it means the tests keep passing even if the simulator changes
 * underneath. These checks exist to catch exactly that, and they earn their keep:
 * the domain types were wrong in ten places the first time they met the real API,
 * and nothing errored, because unknown fields are tolerated.
 */
@Tag("live")
class LiveContractTest {

    @Test
    @DisplayName("the sandbox is up and identifies itself as DSTNS")
    void sandboxAnswers() throws IOException, InterruptedException {
        SandboxClient client = LiveSandbox.requireReachableSandbox();

        var health = client.health();
        assertTrue(health.ok());
        System.out.println("sandbox lifecycle: " + health.lifecycle());
    }

    @Test
    @DisplayName("the live topology still matches the committed fixture")
    void liveTopologyMatchesTheFixture() throws IOException, InterruptedException {
        SandboxClient client = LiveSandbox.requireReachableSandbox();

        TopologyData live = client.topology();
        TopologyData saved = Fixtures.topology();

        // If these diverge, every fixture-based test in the project is measuring a
        // map that no longer exists, and the fixtures need recapturing.
        assertEquals(saved.nodes().size(), live.nodes().size(), "node count changed since the fixture was captured");
        assertEquals(saved.edges().size(), live.edges().size(), "edge count changed since the fixture was captured");
        assertEquals(saved.graphHash(), live.graphHash(),
                "graph_hash changed — the sandbox is serving a different map than the fixtures were captured from");

        System.out.printf("live map: %d nodes, %d edges, hash %s%n",
                live.nodes().size(), live.edges().size(), live.graphHash());
    }

    @Test
    @DisplayName("every field the graph depends on is present and sane in live data")
    void liveTopologySatisfiesTheAssumptionsTheGraphMakes() throws IOException, InterruptedException {
        SandboxClient client = LiveSandbox.requireReachableSandbox();
        TopologyData live = client.topology();

        // Dense 0-indexed ids. Graph.of throws on violation, but a clear message
        // here beats an exception three layers down.
        boolean[] seenNode = new boolean[live.nodes().size()];
        for (Node node : live.nodes()) {
            assertTrue(node.id() >= 0 && node.id() < seenNode.length, "node id out of dense range: " + node.id());
            assertTrue(!seenNode[node.id()], "duplicate node id " + node.id());
            seenNode[node.id()] = true;
            assertTrue(node.position().xM() != 0.0 || node.position().yM() != 0.0 || node.id() == 0,
                    "node " + node.id() + " has no projected coordinates — the A* heuristic needs them");
        }

        Map<Integer, Edge> byId = new HashMap<>();
        for (Edge edge : live.edges()) {
            assertTrue(edge.lengthM() > 0, "edge " + edge.id() + " has no length");
            assertTrue(edge.freeSpeedMps() > 0, "edge " + edge.id() + " has no free-flow speed");
            byId.put(edge.id(), edge);
        }

        // The reverse-twin invariant, live rather than from the fixture.
        for (Edge edge : live.edges()) {
            Edge twin = byId.get(edge.reverseTwin());
            assertTrue(twin != null, "edge " + edge.id() + " names a reverse twin that does not exist");
            assertEquals(edge.id(), twin.reverseTwin(),
                    "reverse-twin invariant broken at edge " + edge.id());
        }
    }

    @Test
    @DisplayName("live snapshot covers every edge and node exactly once")
    void liveSnapshotLinesUpWithTheTopology() throws IOException, InterruptedException {
        LiveSandbox.World world = LiveSandbox.at(LiveSandbox.DESIGN_TIME);

        assertEquals(world.topology().edges().size(), world.snapshot().edges().size());
        assertEquals(world.topology().nodes().size(), world.snapshot().nodes().size());

        boolean[] seen = new boolean[world.topology().edges().size()];
        for (EdgeState state : world.snapshot().edges()) {
            assertTrue(!seen[state.id()], "snapshot lists edge " + state.id() + " twice");
            seen[state.id()] = true;
        }
    }

    @Test
    @DisplayName("no road moves faster than its own speed limit, live")
    void liveSpeedsRespectTheirLimits() throws IOException, InterruptedException {
        LiveSandbox.World world = LiveSandbox.at(LiveSandbox.DESIGN_TIME);

        double slowest = Double.MAX_VALUE;
        double fastest = 0.0;
        for (EdgeState state : world.snapshot().edges()) {
            Edge edge = world.graph().edge(state.id());
            assertTrue(state.effectiveSpeedMps() <= edge.freeSpeedMps() + 1e-9,
                    "edge " + state.id() + " reports " + state.effectiveSpeedMps()
                            + " m/s against a limit of " + edge.freeSpeedMps());
            slowest = Math.min(slowest, state.effectiveSpeedMps());
            fastest = Math.max(fastest, state.effectiveSpeedMps());
        }
        System.out.printf("live effective speeds span %.2f to %.2f m/s%n", slowest, fastest);
    }

    @Test
    @DisplayName("the clock lands exactly where it is told to, once paused")
    void pauseThenSeekPutsTheClockWhereAsked() throws IOException, InterruptedException {
        SandboxClient client = LiveSandbox.requireReachableSandbox();

        try {
            client.stopPlayback();
        } catch (IOException nothingRunning) {
            // Fine — nothing to stop.
        }
        client.startPlayback(LiveSandbox.SEED, "00:00:00", 600);
        client.pausePlayback();
        var seekResponse = client.seek("18:00:00");

        // 18:00:00 is 64800 seconds into the virtual day.
        assertEquals(64_800, seekResponse.get("simulated_seconds").asInt(),
                "the clock did not land on the requested time");

        // And it stays there: a paused clock must not drift while we read from it.
        SnapshotData first = client.snapshot();
        SnapshotData second = client.snapshot();
        for (int i = 0; i < first.edges().size(); i++) {
            assertEquals(first.edges().get(i).effectiveSpeedMps(), second.edges().get(i).effectiveSpeedMps(),
                    "edge state moved between two reads of a paused clock");
        }
    }
}
