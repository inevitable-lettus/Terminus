package csc210.algorithm.model;

/**
 * A directed road segment in the DSTNS road network, as returned by
 * {@code GET /view/topology}.
 *
 * @param id dense 0-indexed id assigned by DSTNS
 * @param from source node id
 * @param to destination node id
 * @param reverseTwin id of the opposing directed edge over the same physical segment
 * @param osmWayId the OpenStreetMap way id this was compiled from
 * @param syntheticReverse true if DSTNS generated this edge as the reverse
 *        direction of a one-way street
 * @param roadClass DSTNS road classification ("primary", "secondary",
 *        "tertiary", "residential", "service")
 * @param lanes number of lanes
 * @param lengthM physical length of the segment, in metres
 * @param freeSpeedMps speed limit with no traffic, in metres per second
 * @param baseCapacityVph maximum throughput with no congestion, in vehicles per hour
 * @param control operator-settable overrides for this edge
 */
public record Edge(
        int id,
        int from,
        int to,
        int reverseTwin,
        long osmWayId,
        boolean syntheticReverse,
        String roadClass,
        int lanes,
        double lengthM,
        double freeSpeedMps,
        double baseCapacityVph,
        EdgeConfiguration control
) {}
