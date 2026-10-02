package csc210.algorithm.serve;

import java.util.List;

/**
 * What the local API and the dashboard bundle send, as plain records. Written
 * with the same snake_case mapper as {@code routes.json}, so every JSON this
 * module produces reads the same way.
 *
 * <p>One set of records for both the live server and the files
 * {@code ./bus export --bundle} writes: the dashboard reads either without
 * knowing which, and the two cannot drift apart.
 */
public final class Views {

    private Views() {}

    /** Where a result came from, so a picture can be traced back to the run it shows. */
    public record Provenance(
            String source,
            String runId,
            String seed,
            String virtualTime,
            String city,
            String country,
            String graphHash,
            String demandSource,
            int generatorsAssigned,
            int generatorsActive,
            int stopsFlagged,
            int stops,
            int stopsInPockets,
            Budget budget
    ) {}

    /** The route budget, and which parts of it were derived from the map. */
    public record Budget(double maxLineMinutes, int maxLines, double targetCoverage, double walkMetres) {}

    /** Bounding box in projected metres, for fitting the map to a canvas. */
    public record Bounds(double minX, double minY, double maxX, double maxY) {}

    /**
     * One junction.
     *
     * @param main whether it is on the main road network, where a bus can arrive and leave
     */
    public record Junction(int id, double x, double y, double lat, double lon, boolean main) {}

    /**
     * One drivable road. The fabricated reverse of a one-way street is not a
     * road and is left out; {@code oneWay} says the reverse does not exist.
     *
     * @param costMs time to drive it now, or {@code null} if it is closed
     * @param congestion the simulator's congestion figure, 0 (free) to 1 (jammed)
     */
    public record Road(int id, int from, int to, String roadClass, boolean oneWay, boolean closed,
                       Long costMs, double congestion) {}

    /**
     * One stop after merging.
     *
     * @param lines labels of the lines calling here, in either direction
     */
    public record Stop(int node, double demand, boolean main, List<String> lines) {}

    public record World(Provenance provenance, Bounds bounds, List<Junction> junctions, List<Road> roads,
                        List<Stop> stops) {}

    /** One direction of one line. */
    public record Direction(List<Integer> nodes, List<Integer> stops, long travelMs) {}

    public record Line(int id, String label, Direction outbound, Direction back, double demand, double share) {}

    public record Network(double coverage, double demandCovered, double totalDemand, String stopReason,
                          List<Integer> interchanges, boolean connected, List<Line> lines) {}

    /**
     * One instruction of a journey.
     *
     * @param kind {@code ACCESS}, {@code WAIT}, {@code RIDE}, {@code TRANSFER},
     *        {@code WALK}, {@code EGRESS} or {@code WALK_DIRECT}
     * @param line the line ridden, or {@code null} on foot
     * @param path the junctions the leg passes, for drawing; the two ends for a walk
     */
    public record Leg(String kind, String line, int fromNode, int toNode, int stopsRidden, long ms,
                      List<Integer> path, String text) {}

    /**
     * @param walkMs walking straight there, for comparison
     * @param aStarExpanded vertices A* settled for this answer
     * @param dijkstraExpanded vertices Dijkstra settled for the same question
     */
    public record Journey(int from, int to, boolean found, long totalMs, int transfers, long walkMs,
                          List<Leg> legs, int aStarExpanded, int dijkstraExpanded) {}

    /** The order a search settled junctions in, for animating its wavefront. */
    public record SearchTrace(String algorithm, int from, int to, List<Integer> order, List<Long> costMs,
                              int settled, boolean truncated) {}

    public record BuildStep(String kind, int line, int stop, List<Integer> path, double gain) {}

    public record BuildTrace(List<BuildStep> steps) {}

    /** A journey with both searches' traces, as the static bundle stores it. */
    public record SampleJourney(Journey journey, SearchTrace aStar, SearchTrace dijkstra) {}

    public record Health(boolean ok, String mode, String sandbox, String runId, String virtualTime) {}

    /** The error envelope, the same shape DSTNS uses. */
    public record Error(boolean ok, ErrorBody error) {
        public Error(String code, String message) {
            this(false, new ErrorBody(code, message));
        }
    }

    public record ErrorBody(String code, String message) {}
}
