package csc210.algorithm.client;

/**
 * The lat/lon bounding box of the whole road network, as returned inside
 * {@code GET /view/topology}.
 */
public record Bounds(double minLat, double maxLat, double minLon, double maxLon) {}
