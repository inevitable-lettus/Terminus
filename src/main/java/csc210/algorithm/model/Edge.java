package csc210.algorithm.model;

/**
 * A directed road segment in the DSTNS road network, as returned by
 * {@code GET /view/topology}.
 *
 * <p>{@code osm_way_id}, {@code synthetic_reverse}, {@code base_capacity_vph}
 * and per-edge control state do not appear on this endpoint — checked
 * against a live response 2026-09-09, not just the (stale) OpenAPI doc.
 * {@code geometry} (the road's polyline) is real but deliberately not
 * modeled here: the algorithm module only needs endpoints, not the shape
 * between them.
 *
 * @param id dense 0-indexed id assigned by DSTNS
 * @param from source node id
 * @param to destination node id
 * @param reverseTwin id of the opposing directed edge over the same physical segment
 * @param roadClass DSTNS road classification ("primary", "secondary",
 *        "tertiary", "residential", "service")
 * @param lanes number of lanes
 * @param lengthM physical length of the segment, in metres
 * @param freeSpeedMps speed limit with no traffic, in metres per second
 */
public record Edge(
        int id,
        int from,
        int to,
        int reverseTwin,
        String roadClass,
        int lanes,
        double lengthM,
        double freeSpeedMps
) {}
