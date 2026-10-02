package csc210.algorithm.client;

import csc210.algorithm.model.Edge;
import csc210.algorithm.model.Node;

import java.util.List;

/**
 * The {@code data} payload of {@code GET /view/topology}'s {@link Envelope} —
 * the whole road network in one call.
 *
 * @param nodes every junction
 * @param edges every directed road segment
 * @param bounds the network's lat/lon bounding box
 * @param graphHash content hash of the topology, e.g. {@code "sha256:..."} —
 *        used by step 6.1's determinism check to confirm two runs saw the
 *        same graph
 * @param rootNode id of the node DSTNS treats as the network's root
 * @param topologyRevision increments only if the topology itself changes,
 *        which it does not during a run (topology is immutable per run)
 * @param features the mapped features demand is anchored to (DSTNS 2.0), or
 *        {@code null} from an older server
 * @param location the real city the world was cut from (DSTNS 2.0), or {@code null}
 */
public record TopologyData(
        List<Node> nodes,
        List<Edge> edges,
        Bounds bounds,
        String graphHash,
        int rootNode,
        long topologyRevision,
        List<Feature> features,
        Location location
) {

    /** A topology with no features or location, as an older server sends it. */
    public TopologyData(List<Node> nodes, List<Edge> edges, Bounds bounds, String graphHash,
                        int rootNode, long topologyRevision) {
        this(nodes, edges, bounds, graphHash, rootNode, topologyRevision, null, null);
    }
}
