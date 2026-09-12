package csc210.algorithm.routing;

/**
 * One generated bus line.
 *
 * @param id 0-based index in the order the builder produced routes
 * @param stops the bus stops this line serves, in travel order
 * @param nodes the <em>full</em> node-by-node traversal from the first stop to
 *        the last, every intermediate junction included. Not a convenience:
 *        {@code POST /control/transit/route} validates that every consecutive
 *        pair of nodes is a single directed edge and does not path-fill
 *        between stops, so a route listing only its stops is rejected at the
 *        first gap.
 * @param edges the edge ids traversed, one shorter than {@code nodes}
 * @param travelMillis time to drive the whole line end to end
 * @param demandCovered demand of the stops this line was built to reach,
 *        counting each stop once across the whole network
 * @param stopOccurrences every stop visit in travel order, <em>including
 *        repeats</em> when the line revisits a stop — unlike {@code stops},
 *        which is deduplicated to first occurrence. This is what a ride
 *        segment must actually be priced against.
 * @param stopOccurrencePositions index into {@code nodes} (and, one less,
 *        into {@code edges}) of each entry in {@code stopOccurrences}, so
 *        the exact edge slice between two consecutive visits can be summed
 *        without searching for it
 */
public record BusRoute(
        int id,
        int[] stops,
        int[] nodes,
        int[] edges,
        long travelMillis,
        double demandCovered,
        int[] stopOccurrences,
        int[] stopOccurrencePositions
) {

    public int stopCount() {
        return stops.length;
    }

    /** Label for {@code POST /control/transit/route} and for printing. */
    public String label() {
        return "R" + id;
    }
}
