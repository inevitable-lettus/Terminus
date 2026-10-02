package csc210.algorithm.world;

import csc210.algorithm.client.EdgeDetail;
import csc210.algorithm.client.SandboxClient;
import csc210.algorithm.client.SnapshotData;
import csc210.algorithm.client.TopologyData;
import csc210.algorithm.cost.CostModel;
import csc210.algorithm.demand.DemandModel;
import csc210.algorithm.demand.DemandSource;
import csc210.algorithm.demand.StopSet;
import csc210.algorithm.graph.Graph;
import csc210.algorithm.routing.RouteBudget;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * The one place a {@link World} is built from a live simulator.
 *
 * <p>The command line and the live test suite both need exactly this pipeline,
 * and they used to each carry a copy of it. Two copies drift: a fix applied to
 * one — the one-way exclusion was the case that showed it — leaves the other
 * testing a differently built world than the one users get, which defeats the
 * point of a live suite. There is one copy now.
 */
public final class WorldLoader {

    private WorldLoader() {}

    /** Reads the simulator's current state and builds the world from it. */
    public static World load(SandboxClient client) throws IOException, InterruptedException {
        return assemble(client.topology(), client.snapshot(), client::edges);
    }

    /** Where the one-way flags come from when the topology does not carry them. */
    @FunctionalInterface
    public interface EdgeDetails {
        List<EdgeDetail> fetch() throws IOException, InterruptedException;
    }

    /**
     * Builds the world from a topology and snapshot already in hand — the whole
     * pipeline minus the reads, so saved fixtures go through exactly the code a
     * live run does.
     *
     * @param edgeDetails asked only if the topology does not flag its own one-way streets
     */
    public static World assemble(TopologyData topology, SnapshotData snapshot, EdgeDetails edgeDetails)
            throws IOException, InterruptedException {
        Graph graph = Graph.of(topology.nodes(), topology.edges());

        // DSTNS 2.0 says which edges are fabricated reverses on the topology
        // itself. Older servers say it only on /view/edges, so ask there — but
        // only then, since that is a paged request per thousand edges.
        boolean fromTopology = graph.oneWayKnown();
        Set<Integer> oneWay = fromTopology
                ? graph.syntheticReverseIds()
                : EdgeDetail.syntheticReverseIds(edgeDetails.fetch());
        CostModel costModel = CostModel.of(graph, snapshot, oneWay);

        StopSet stops = StopSet.of(graph);
        DemandModel demand = DemandModel.of(graph, snapshot, stops, topology.features());
        RouteBudget budget = RouteBudget.forStops(graph, demand.stopNodes());
        return new World(topology, snapshot, graph, costModel, stops, demand,
                oneWay.size(), fromTopology, demandNote(topology, snapshot, demand), budget);
    }

    /**
     * Why the generator catchment was not used, when it was not: the server is
     * older and sends no generators, or it sent some and none was near a stop.
     */
    private static String demandNote(TopologyData topology, SnapshotData snapshot, DemandModel demand) {
        if (demand.source() == DemandSource.CATCHMENT) {
            return null;
        }
        if (topology.features() == null || snapshot.demand() == null) {
            return "no trip generators on this server (DSTNS before 2.0)";
        }
        return "the simulator's trip generators were all out of reach of every stop";
    }
}
