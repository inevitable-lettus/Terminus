package csc210.algorithm.transit;

/**
 * One directed move a passenger can make.
 *
 * @param from origin vertex
 * @param to destination vertex
 * @param millis what the move costs, in milliseconds
 * @param kind which of the three kinds of move this is
 * @param routeId the line being ridden, or {@code -1} for a transfer or walk
 */
public record TransitLink(int from, int to, long millis, Kind kind, int routeId) {

    public enum Kind {
        /** Staying on one bus to the next stop on its line. */
        RIDE,
        /** Changing lines at the same stop, paying the wait. */
        TRANSFER,
        /** Walking to a nearby stop, then waiting for a line there. */
        WALK
    }
}
