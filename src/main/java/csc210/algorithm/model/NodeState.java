package csc210.algorithm.model;

/**
 * The live, per-tick state of one node, as returned by
 * {@code GET /view/snapshot}.
 *
 * @param id the node this state belongs to, matching {@link Node#id()}
 * @param buildingEffect 0..1 activity level of this node's building at the
 *        current virtual time — 0 outside its rush-hour window, rising to 1
 *        at peak. Multiplied by {@link Node#buildingImpact()} to get the
 *        node's actual demand contribution.
 * @param rainfall rainfall intensity at this junction
 * @param flood flood depth at this junction
 */
public record NodeState(
        int id,
        double buildingEffect,
        double rainfall,
        double flood
) {}
