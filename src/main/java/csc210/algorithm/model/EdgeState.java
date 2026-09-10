package csc210.algorithm.model;

/**
 * The live, per-tick state of one edge, as returned by
 * {@code GET /view/snapshot}. Separate from {@link Edge} on purpose:
 * {@link Edge} is structural and fixed for the whole run, this changes every
 * tick as traffic, signals and weather move.
 *
 * @param id the edge this state belongs to, matching {@link Edge#id()}
 * @param closed true if the edge is closed to traffic entirely
 * @param congestion 0..1 measure of how saturated the segment is
 * @param effectiveSpeedMps the speed a vehicle actually achieves right now,
 *        in metres per second — free-flow speed after signal, rain, flood and
 *        manual multipliers, rate-limited so it eases toward its target
 * @param rainfall rainfall intensity over this segment
 * @param flood flood depth over this segment
 * @param vehicleCount vehicles currently on the segment
 * @param haltingCount of those, how many are stopped
 */
public record EdgeState(
        int id,
        boolean closed,
        double congestion,
        double effectiveSpeedMps,
        double rainfall,
        double flood,
        int vehicleCount,
        int haltingCount
) {}
