package csc210.algorithm;

import com.fasterxml.jackson.databind.ObjectMapper;
import csc210.algorithm.client.Envelope;
import csc210.algorithm.client.SandboxJson;
import csc210.algorithm.client.SnapshotData;
import csc210.algorithm.client.TopologyData;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.zip.GZIPInputStream;

/**
 * Loads the saved DSTNS responses in {@code src/test/resources/fixtures/} so
 * tests never need the sandbox running.
 *
 * <p><strong>Recaptured 2026-09-14</strong>, at seed {@code 0x2026} with the
 * clock paused, after DSTNS replaced its synthetic 120-node Paris-coordinate
 * grid with a real OpenStreetMap extract of Berlin Mitte — 279 junctions, 612
 * directed segments, 8 bus stops. Every figure asserted against these files
 * changed with them; none of the earlier numbers describe this map.
 *
 * <p>{@code snapshot_0830.json} / {@code edges_0830.json} and
 * {@code snapshot_1800.json} / {@code edges_1800.json} are the two slices the
 * demand model is compared across. {@code topology.json} is time-independent.
 * {@code snapshot.json} was taken with no run active; the clock had simply
 * been left where the previous run stopped it, so it is a quiet moment rather
 * than a true free-flow reading, and nothing currently asserts against it.
 *
 * <p><strong>DSTNS 2.0 captures</strong> live in {@code fixtures/v2/}, gzipped,
 * taken 2026-09-25 from {@code origin/dstns-v2} {@code bc6e517} at seed
 * {@code 0x2026} with the clock paused: Dar es Salaam, 3000 junctions, 6330
 * directed segments (566 of them fabricated reverses of one-way streets), 81
 * flagged stops. They were trimmed only where nothing reads them — edge
 * {@code tags}, per-edge {@code demand_causes}, the news stack, and every
 * topology feature no demand entry refers to — so the shapes are the server's
 * own. The Berlin files above stay as the pre-2.0 regression set.
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

    /** Live edge state with no run active. Not asserted against; see the class note. */
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

    /**
     * Ids of the fabricated reverse directions of one-way streets, from the
     * 18:00 edge capture — what {@code Main} excludes from every search.
     *
     * <p>Fixture-based tests build their cost model with this so they model the
     * same road network production does. Without it they would be testing a map
     * where 139 of 612 edges are drivable in a direction no vehicle may take.
     */
    public static java.util.Set<Integer> oneWayEdgeIdsAt1800() {
        return csc210.algorithm.client.EdgeDetail.syntheticReverseIds(edgeDetailsAt1800());
    }

    /** As {@link #oneWayEdgeIdsAt1800}, from the 08:30 capture. The set is time-independent. */
    public static java.util.Set<Integer> oneWayEdgeIdsAt0830() {
        return csc210.algorithm.client.EdgeDetail.syntheticReverseIds(edgeDetailsAt0830());
    }

    /**
     * The world as {@link csc210.algorithm.world.WorldLoader} builds it from an
     * older server, at 18:00, from the saved fixtures: every step the loader takes
     * except the HTTP.
     */
    public static csc210.algorithm.world.World world1800() {
        return assembled(topology(), snapshotAt1800(), Fixtures::edgeDetailsAt1800);
    }

    /** The DSTNS 2.0 topology: a real city, with one-way flags and demand features. */
    public static TopologyData v2Topology() {
        return read("/fixtures/v2/topology.json.gz", TopologyData.class);
    }

    /** DSTNS 2.0 live state at 18:00, demand entries included. */
    public static SnapshotData v2SnapshotAt1800() {
        return read("/fixtures/v2/snapshot_1800.json.gz", SnapshotData.class);
    }

    /** DSTNS 2.0 live state at 08:30 — fewer generators active than at 18:00. */
    public static SnapshotData v2SnapshotAt0830() {
        return read("/fixtures/v2/snapshot_0830.json.gz", SnapshotData.class);
    }

    /**
     * The 2.0 world at 18:00, built exactly as {@link csc210.algorithm.world.WorldLoader}
     * builds it from a live server, minus the HTTP.
     */
    public static csc210.algorithm.world.World v2World1800() {
        return assembled(v2Topology(), v2SnapshotAt1800(), Fixtures::noEdgeDetails);
    }

    /** As {@link #v2World1800}, at 08:30. */
    public static csc210.algorithm.world.World v2World0830() {
        return assembled(v2Topology(), v2SnapshotAt0830(), Fixtures::noEdgeDetails);
    }

    private static java.util.List<csc210.algorithm.client.EdgeDetail> noEdgeDetails() {
        throw new IllegalStateException("a 2.0 topology flags its own one-way streets");
    }

    private static csc210.algorithm.world.World assembled(TopologyData topology, SnapshotData snapshot,
                                                          csc210.algorithm.world.WorldLoader.EdgeDetails details) {
        try {
            return csc210.algorithm.world.WorldLoader.assemble(topology, snapshot, details);
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The raw bytes of a fixture, decompressed if it is gzipped — what a stub
     * server hands back when it plays the simulator.
     */
    public static byte[] bytes(String name) {
        String resource = "/fixtures/" + name;
        try (InputStream in = open(resource)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("could not read fixture " + resource, e);
        }
    }

    private static InputStream open(String resource) throws IOException {
        InputStream in = Fixtures.class.getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalStateException(resource + " must be on the test classpath");
        }
        return resource.endsWith(".gz") ? new GZIPInputStream(in) : in;
    }

    private static <T> T read(String resource, Class<T> payloadType) {
        try (InputStream in = open(resource)) {
            Envelope envelope = MAPPER.readValue(in, Envelope.class);
            return MAPPER.treeToValue(envelope.data(), payloadType);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read fixture " + resource, e);
        }
    }
}
