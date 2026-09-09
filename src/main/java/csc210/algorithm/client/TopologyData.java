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
 */
public record TopologyData(
        List<Node> nodes,
        List<Edge> edges,
        Bounds bounds,
        String graphHash,
        int rootNode,
        long topologyRevision
) {}
