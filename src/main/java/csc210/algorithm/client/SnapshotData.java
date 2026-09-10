package csc210.algorithm.client;

import csc210.algorithm.model.EdgeState;
import csc210.algorithm.model.NodeState;

import java.util.List;

/**
 * The {@code data} payload of {@code GET /view/snapshot} — every node's and
 * every edge's live state at one instant of virtual time.
 *
 * <p>Only the fields the cost model and the demand read actually consume are
 * modeled; the envelope also carries incident, surge, weather-event and bus
 * lists, which stay unparsed because nothing in this module reads them.
 *
 * @param nodes live state of every junction
 * @param edges live state of every directed road segment
 * @param topologyRevision the topology this state belongs to — must match the
 *        topology the {@link csc210.algorithm.graph.Graph} was built from
 */
public record SnapshotData(
        List<NodeState> nodes,
        List<EdgeState> edges,
        long topologyRevision
) {}
