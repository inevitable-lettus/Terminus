package csc210.algorithm.model;

/**
 * A point in the DSTNS road network, carrying both raw GPS coordinates and
 * the sandbox's projected metre coordinates.
 *
 * @param lat latitude, in degrees
 * @param lon longitude, in degrees
 * @param xM projected x-coordinate, in metres from the map origin
 * @param yM projected y-coordinate, in metres from the map origin
 */
public record Position(double lat, double lon, double xM, double yM) {}
