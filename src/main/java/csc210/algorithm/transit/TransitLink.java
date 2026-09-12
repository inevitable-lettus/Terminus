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
        WALK,
        /** Walking from where the journey starts to the first stop boarded. */
        ACCESS,
        /** Walking from the last stop alighted at to where the journey ends. */
        EGRESS,
        /** Walking the entire way, because no bus journey beats it. */
        WALK_DIRECT
    }

    /**
     * Whether this move changes which line the passenger is on, which is what
     * "a transfer" means to a rider. Access and egress walks are excluded:
     * walking from your front door to the first stop is not a change of bus.
     */
    public boolean isLineChange() {
        return kind == Kind.TRANSFER || kind == Kind.WALK;
    }
}
