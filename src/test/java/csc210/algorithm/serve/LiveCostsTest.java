package csc210.algorithm.serve;

import csc210.algorithm.Fixtures;
import csc210.algorithm.cli.RunInfo;
import csc210.algorithm.client.SnapshotData;
import csc210.algorithm.model.EdgeState;
import csc210.algorithm.routing.BusRoute;
import csc210.algorithm.transit.TransitGraph;
import csc210.algorithm.transit.TransitLink;
import csc210.algorithm.world.World;
import csc210.algorithm.world.WorldLoader;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Item 1: a speed update re-prices the same network; it does not re-plan it. */
class LiveCostsTest {

    private static final RunInfo RUN = new RunInfo("run_test", "0x2026", "18:00:00", true);

    @Test
    void routeAndTripIdsStayTheSameAfterASpeedUpdate() {
        PlannedCity planned = PlannedCity.plan(Fixtures.v2World1800(), RUN, Fixtures.v2World1800().budget(), "live");
        PlannedCity updated = planned.withLiveCosts(Fixtures.v2World0830(),
                new RunInfo("run_test", "0x2026", "08:30:00", true));

        assertSame(planned.network(), updated.network());
        assertSame(planned.timetable(), updated.timetable());
        assertEquals("08:30:00", updated.run().time());
        assertNotEquals(totalRideMillis(planned), totalRideMillis(updated), "08:30 traffic is not 18:00 traffic");
    }

    @Test
    void aClosedRoadOnALineMakesTheRideSlowInsteadOfFailing() {
        World world = Fixtures.v2World1800();
        PlannedCity planned = PlannedCity.plan(world, RUN, world.budget(), "live");
        BusRoute line = planned.network().routes().get(0);
        int closedEdge = line.edges()[0];

        PlannedCity updated = planned.withLiveCosts(withClosed(world, closedEdge), RUN);

        long before = rideMillis(planned, line.id(), line.stopOccurrences()[0]);
        long after = rideMillis(updated, line.id(), line.stopOccurrences()[0]);
        double metres = world.graph().edge(closedEdge).lengthM();
        long gridlock = Math.round(metres / 0.1 * 1000.0);
        assertTrue(after > before);
        assertEquals(before - world.costModel().costMillis(closedEdge) + gridlock, after);
    }

    static World withClosed(World world, int edgeId) {
        SnapshotData old = world.snapshot();
        List<EdgeState> edges = new ArrayList<>();
        for (EdgeState e : old.edges()) {
            edges.add(e.id() != edgeId ? e : new EdgeState(e.id(), true, e.congestion(), e.effectiveSpeedMps(),
                    e.rainfall(), e.flood(), e.vehicleCount(), e.haltingCount()));
        }
        try {
            return WorldLoader.assemble(world.topology(),
                    new SnapshotData(old.nodes(), edges, old.topologyRevision(), old.demand()), () -> List.of());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The ride link leaving the first stop of a line, outbound. */
    private static long rideMillis(PlannedCity city, int routeId, int fromStop) {
        TransitGraph transit = transitOf(city);
        for (int vertex : transit.verticesAt(fromStop)) {
            if (transit.vertex(vertex).routeId() != routeId) {
                continue;
            }
            for (TransitLink link : transit.outgoing(vertex)) {
                if (link.kind() == TransitLink.Kind.RIDE) {
                    return link.millis();
                }
            }
        }
        throw new AssertionError("no ride from stop " + fromStop);
    }

    private static long totalRideMillis(PlannedCity city) {
        TransitGraph transit = transitOf(city);
        long total = 0;
        for (int v = 0; v < transit.vertexCount(); v++) {
            for (TransitLink link : transit.outgoing(v)) {
                if (link.kind() == TransitLink.Kind.RIDE) {
                    total += link.millis();
                }
            }
        }
        return total;
    }

    private static TransitGraph transitOf(PlannedCity city) {
        return TransitGraph.of(city.world().graph(), city.network(), city.world().costModel());
    }
}
