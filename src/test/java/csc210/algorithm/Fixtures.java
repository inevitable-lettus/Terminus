package csc210.algorithm;

import com.fasterxml.jackson.databind.ObjectMapper;
import csc210.algorithm.client.Envelope;
import csc210.algorithm.client.SandboxJson;
import csc210.algorithm.client.SnapshotData;
import csc210.algorithm.client.TopologyData;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Loads the saved DSTNS responses in {@code src/test/resources/fixtures/} so
 * tests never need the sandbox running.
 *
 * <p>{@code topology.json} and {@code snapshot.json} were captured 2026-09-09
 * with the clock IDLE, so every edge sits at free-flow speed.
 * {@code snapshot_0830.json} and {@code edges_0830.json} were captured
 * 2026-09-10 from a run at seed {@code 0x2026}, paused at virtual time
 * 08:30:00 — the morning peak the route builder optimises for.
 */
public final class Fixtures {

    private static final ObjectMapper MAPPER = SandboxJson.mapper();

    private Fixtures() {}

    public static TopologyData topology() {
        return read("/fixtures/topology.json", TopologyData.class);
    }

    /** Live edge state at 08:30, the slice every cost in this module is built from. */
    public static SnapshotData snapshotAt0830() {
        return read("/fixtures/snapshot_0830.json", SnapshotData.class);
    }

    /**
     * Live edge state at 18:00, the busiest hour in the simulated day —
     * measured, not assumed: see the half-hourly sweep recorded against step
     * 4.1 in the build order. This is the slice the route builder designs for.
     */
    public static SnapshotData snapshotAt1800() {
        return read("/fixtures/snapshot_1800.json", SnapshotData.class);
    }

    /** Live edge state with the clock IDLE — every edge at free-flow speed. */
    public static SnapshotData snapshotIdle() {
        return read("/fixtures/snapshot.json", SnapshotData.class);
    }

    /** The nested {@code /view/edges} page captured at 08:30 — the only source of {@code demand_vph}. */
    public static java.util.List<csc210.algorithm.client.EdgeDetail> edgeDetailsAt0830() {
        return read("/fixtures/edges_0830.json", csc210.algorithm.client.EdgePage.class).items();
    }

    /** The same nested edge page captured at 18:00. */
    public static java.util.List<csc210.algorithm.client.EdgeDetail> edgeDetailsAt1800() {
        return read("/fixtures/edges_1800.json", csc210.algorithm.client.EdgePage.class).items();
    }

    private static <T> T read(String resource, Class<T> payloadType) {
        try (InputStream in = Fixtures.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " must be on the test classpath");
            }
            Envelope envelope = MAPPER.readValue(in, Envelope.class);
            return MAPPER.treeToValue(envelope.data(), payloadType);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read fixture " + resource, e);
        }
    }
}
