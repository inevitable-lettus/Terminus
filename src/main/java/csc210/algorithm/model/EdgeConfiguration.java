package csc210.algorithm.model;

/**
 * The operator-settable state of one edge: whether it is closed, and any
 * manual speed/capacity multipliers layered on top of the simulation.
 * Request body shape for {@code PUT /control/edges/{id}} — unlike {@link Node}
 * and {@link Edge}, not yet checked against a live response, since it is
 * written, not read, and no step has exercised that endpoint yet.
 *
 * @param closed true if the edge is closed to traffic entirely
 * @param manualSpeedMultiplier operator override multiplied onto free-flow speed
 * @param manualCapacityMultiplier operator override multiplied onto base capacity
 */
public record EdgeConfiguration(
        boolean closed,
        double manualSpeedMultiplier,
        double manualCapacityMultiplier
) {}
