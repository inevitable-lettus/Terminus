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
        TopologyData topology = topology();
        SnapshotData snapshot = snapshotAt1800();
        csc210.algorithm.graph.Graph graph = csc210.algorithm.graph.Graph.of(topology.nodes(), topology.edges());
        java.util.Set<Integer> oneWay = oneWayEdgeIdsAt1800();
        csc210.algorithm.demand.StopSet stops = csc210.algorithm.demand.StopSet.of(graph);
        csc210.algorithm.demand.DemandModel demand =
                csc210.algorithm.demand.DemandModel.of(graph, snapshot, stops, null);
        return new csc210.algorithm.world.World(topology, snapshot, graph,
                csc210.algorithm.cost.CostModel.of(graph, snapshot, oneWay), stops, demand,
                oneWay.size(), false, null,
                csc210.algorithm.routing.RouteBudget.forStops(graph, demand.stopNodes()));
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
