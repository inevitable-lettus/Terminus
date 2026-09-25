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
 * @param back the return service, or {@code null} if no road leads back
 *        through the line's stops — see {@link Return}
 */
public record BusRoute(
        int id,
        int[] stops,
        int[] nodes,
        int[] edges,
        long travelMillis,
        double demandCovered,
        int[] stopOccurrences,
        int[] stopOccurrencePositions,
        Return back
) {

    /** A line with no return service. */
    public BusRoute(int id, int[] stops, int[] nodes, int[] edges, long travelMillis, double demandCovered,
                    int[] stopOccurrences, int[] stopOccurrencePositions) {
        this(id, stops, nodes, edges, travelMillis, demandCovered, stopOccurrences, stopOccurrencePositions, null);
    }

    /**
     * The same line driven back: from the last outbound stop to the first, on
     * its own shortest road path, calling at the stops it passes.
     *
     * <p><strong>Not the outbound path read backwards.</strong> That was the
     * first model, and it fails on real maps: a dual carriageway is two one-way
     * ways a few metres apart, so a line that follows one side can never retrace
     * itself, yet a bus comes back on the other side every day. Routed on its own,
     * the return takes whatever roads actually lead back, and calls at the stops
     * on that side.
     *
     * @param nodes node-by-node traversal from the last outbound stop to the first
     * @param edges edge ids traversed, one shorter than {@code nodes}
     * @param travelMillis time to drive the return end to end
     * @param stopOccurrences every stop visit in return travel order, repeats included
     * @param stopOccurrencePositions index into {@code nodes} of each visit
     */
    public record Return(int[] nodes, int[] edges, long travelMillis, int[] stopOccurrences,
                         int[] stopOccurrencePositions) {}

    public int stopCount() {
        return stops.length;
    }

    /** Whether a bus runs this line in both directions. */
    public boolean runsBothWays() {
        return back != null;
    }

    /** Label for {@code POST /control/transit/route} and for printing. */
    public String label() {
        return "R" + id;
    }
}
