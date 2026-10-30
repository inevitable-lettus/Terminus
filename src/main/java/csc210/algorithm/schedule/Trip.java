package csc210.algorithm.schedule;

/**
 * One scheduled bus run: one bus driving one direction of one line, once.
 * The bus id is the trip id, so a trip and its bus are the same thing.
 *
 * @param id for example {@code route_2-out-0615}: line, direction, departure as HHMM
 * @param routeId the line, as {@link csc210.algorithm.routing.BusRoute#id()}
 * @param outbound true for the line's own direction, false for its return
 * @param departSimS departure from the first stop, in seconds of the simulated day
 * @param seats how many passengers the bus carries
 */
public record Trip(String id, int routeId, boolean outbound, int departSimS, int seats) {

    public static String idFor(int routeId, boolean outbound, int departSimS) {
        int hours = departSimS / 3600;
        int minutes = departSimS % 3600 / 60;
        return String.format("route_%d-%s-%02d%02d", routeId, outbound ? "out" : "back", hours, minutes);
    }
}
