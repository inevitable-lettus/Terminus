package csc210.algorithm.world;

import csc210.algorithm.client.EdgeDetail;
import csc210.algorithm.client.Place;
import csc210.algorithm.client.SandboxClient;
import csc210.algorithm.client.SnapshotData;
import csc210.algorithm.client.TopologyData;
import csc210.algorithm.cost.CostModel;
import csc210.algorithm.demand.DemandModel;
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
        TopologyData topology = client.topology();
        SnapshotData snapshot = client.snapshot();
        Graph graph = Graph.of(topology.nodes(), topology.edges());

        // DSTNS 2.0 says which edges are fabricated reverses on the topology
        // itself. Older servers say it only on /view/edges, so ask there — but
        // only then, since that is a paged request per thousand edges.
        boolean fromTopology = graph.oneWayKnown();
        Set<Integer> oneWay = fromTopology
                ? graph.syntheticReverseIds()
                : EdgeDetail.syntheticReverseIds(client.edges());
        CostModel costModel = CostModel.of(graph, snapshot, oneWay);

        StopSet stops = StopSet.of(graph);

        // The simulator's own demand is preferred but never required: an older
        // server has no such endpoint, and a failed read must not stop the
        // network being designed. The reason is kept so the result can say so.
        List<Place> places = null;
        String placesNote = null;
        try {
            places = client.places("bus_stop");
        } catch (IOException unavailable) {
            placesNote = "places unavailable (" + firstLine(unavailable) + ")";
        }

        DemandModel demand = DemandModel.of(graph, snapshot, stops, places);
        RouteBudget budget = RouteBudget.forStops(graph, demand.stopNodes());
        return new World(topology, snapshot, graph, costModel, stops, demand,
                oneWay.size(), fromTopology, placesNote, budget);
    }

    private static String firstLine(Exception failure) {
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        int newline = message.indexOf('\n');
        String line = newline < 0 ? message : message.substring(0, newline);
        return line.length() > 120 ? line.substring(0, 117) + "..." : line;
    }
}
