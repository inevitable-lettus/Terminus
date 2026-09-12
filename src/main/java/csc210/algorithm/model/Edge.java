package csc210.algorithm.model;

import com.fasterxml.jackson.annotation.JsonProperty;

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
        @JsonProperty(required = true) int id,
        @JsonProperty(required = true) int from,
        @JsonProperty(required = true) int to,
        int reverseTwin,
        String roadClass,
        int lanes,
        @JsonProperty(required = true) double lengthM,
        @JsonProperty(required = true) double freeSpeedMps
) {
    public Edge {
        if (!Double.isFinite(lengthM) || lengthM < 0.0) {
            throw new IllegalArgumentException("lengthM must be finite and non-negative, got " + lengthM);
        }
        if (!Double.isFinite(freeSpeedMps) || freeSpeedMps < 0.0) {
            throw new IllegalArgumentException("freeSpeedMps must be finite and non-negative, got " + freeSpeedMps);
        }
    }
}
