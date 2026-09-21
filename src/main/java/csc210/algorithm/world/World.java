package csc210.algorithm.world;

import csc210.algorithm.client.SnapshotData;
import csc210.algorithm.client.TopologyData;
import csc210.algorithm.cost.CostModel;
import csc210.algorithm.demand.DemandModel;
import csc210.algorithm.demand.StopSet;
import csc210.algorithm.graph.Graph;
import csc210.algorithm.routing.RouteBudget;

/**
 * Everything the algorithm needs to know about the simulated city at one
 * instant, read once and frozen: the road graph, what each road costs, which
 * roads may not be driven at all, where the stops are and how much demand each
 * draws.
 *
 * @param topology the raw topology, kept for its graph hash and revision
 * @param snapshot the live state the costs and demand were computed from
 * @param graph the road network
 * @param costModel edge costs at this instant, one-way streets excluded
 * @param stops the bus stops, near-duplicates merged
 * @param demandModel demand at each stop, and where it came from
 * @param oneWayEdges how many edges were excluded as fabricated reverses of one-way streets
 * @param oneWayFromTopology whether the topology said so itself (DSTNS 2.0) rather than
 *        needing a second request
 * @param placesNote why the simulator's places were not used, or {@code null} if they were read
 * @param budget the route budget derived from this map's stops
 */
public record World(
        TopologyData topology,
        SnapshotData snapshot,
        Graph graph,
        CostModel costModel,
        StopSet stops,
        DemandModel demandModel,
        int oneWayEdges,
        boolean oneWayFromTopology,
        String placesNote,
        RouteBudget budget
) {}
