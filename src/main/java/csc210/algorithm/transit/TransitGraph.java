package csc210.algorithm.transit;

import csc210.algorithm.graph.Graph;
import csc210.algorithm.model.Position;
import csc210.algorithm.routing.BusRoute;
import csc210.algorithm.routing.RouteNetwork;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The network a <em>passenger</em> moves through, built from the bus lines
 * Algorithm 1 produced. Algorithm 2 searches this, not the road graph.
 *
 * <p><strong>Why it cannot just be the road graph.</strong> A shortest path
 * over roads answers "how do I drive there". A passenger cannot drive; they can
 * only ride a line that happens to run, and get off where it stops. More
 * importantly, changing buses is expensive — you wait for the next one — and a
 * road graph has nowhere to put that cost. A search over roads would happily
 * "transfer" ten times for free and report a journey nobody could make.
 *
 * <p><strong>The trick that makes transfers cost something.</strong> A vertex
 * here is not a stop, it is a <em>(stop, route)</em> pair: "standing at stop 27
 * on line R2". Riding one stop further along the same line is one edge. Getting
 * off R2 at stop 27 and onto R0 at stop 27 is a <em>different</em> edge, priced
 * at the wait, and it is the only way to move between lines. Because the cost
 * sits on a real edge, the search cannot avoid paying it, and a two-transfer
 * journey is automatically compared against a slower one-seat ride on its
 * merits rather than being ruled in or out by hand.
 *
 * <p>Walk edges join (stop, route) pairs at <em>different</em> stops close
 * enough to walk between, so a passenger can cross the street to a better line.
 *
 * <p><strong>Both directions, only where a bus runs both ways.</strong> A
 * line's outbound stops are joined by ride links priced from the outbound
 * road edges. Return links exist only if the line has a return service
 * ({@link BusRoute#runsBothWays()}), and they are priced from the return's own
 * road path, which on a dual carriageway is a different road from the
 * outbound one. A passenger is never put on a bus that does not run.
 *
 * <p>Both directions of one line share a vertex per stop, so a rider can stay
 * on "R0" through a terminus without paying a transfer. That is never cheaper
 * than getting off earlier, so it does not distort a shortest journey.
 *
 * <p><strong>Honest simplifications.</strong> The transfer wait is a
 * flat five minutes rather than derived from a timetable, because DSTNS does
 * not publish headways. And boarding the first bus is free — the initial wait
 * is not modelled — so a journey's cost is the in-vehicle and transfer time,
 * not door-to-door time.
 */
public final class TransitGraph {

    /** How long a passenger waits, on average, to change onto another line. */
    public static final long TRANSFER_WAIT_MILLIS = 5 * 60 * 1000L;

    /** Walking speed used to price a walk between nearby stops: 1.4 m/s, about 5 km/h. */
    public static final double WALK_SPEED_MPS = 1.4;

    /**
     * How far a passenger will walk between stops <em>mid-journey</em>, in metres.
     *
     * <p>This is deliberately not the same number as
     * {@link csc210.algorithm.routing.RouteBudget#walkingRadiusM()}, and the two
     * answer different questions. The route builder's radius asks "is this stop
     * close enough to count as served?", and is pulled down to 150 m — below
     * the closest pair of stops on this map — so that coverage is not
     * trivially satisfied. This one asks "will
     * a passenger already out of the house walk to a different stop to change
     * line?", and 400 m — about five minutes — is the standard planning figure.
     *
     * <p>The distinction is load-bearing, not cosmetic. On the synthetic grid
     * this was measured directly: at that map's 250 m coverage radius the
     * transit graph split in two and a passenger on one line could never leave
     * it, while at 300 m and above it was fully connected. On the current map
     * the builder's lines share no stop at all, so walking between nearby stops
     * is the <em>only</em> way to change line — use the coverage radius here and
     * that way disappears.
     */
    public static final double DEFAULT_INTERCHANGE_WALK_M = 400.0;

    /** How far a passenger will walk between stops mid-journey. */
    private final double walkingRadiusM;

    private final TransitStop[] vertices;
    private final int[] adjacencyStart;
    private final TransitLink[] links;

    /** vertexOf[roadNode] = (stop, route) vertices at that road node. */
    private final Map<Integer, int[]> verticesAtStop;

    private TransitGraph(TransitStop[] vertices, int[] adjacencyStart, TransitLink[] links,
                         Map<Integer, int[]> verticesAtStop, double walkingRadiusM) {
        this.vertices = vertices;
        this.adjacencyStart = adjacencyStart;
        this.links = links;
        this.verticesAtStop = verticesAtStop;
        this.walkingRadiusM = walkingRadiusM;
    }

    /**
     * Builds the passenger network.
     *
     * @param roadGraph the road network the routes were built on, for stop positions
     * @param network the lines Algorithm 1 produced
     * @param rideMillis cost of riding each edge of the road network, so a bus
     *        segment costs what driving it actually costs at this time of day
     * @param walkingRadiusM how far a passenger will walk between stops
     */
    public static TransitGraph of(Graph roadGraph, RouteNetwork network,
                                  csc210.algorithm.cost.CostModel rideMillis) {
        return of(roadGraph, network, rideMillis, DEFAULT_INTERCHANGE_WALK_M);
    }

    /** As {@link #of(Graph, RouteNetwork, csc210.algorithm.cost.CostModel)}, with the walk radius given. */
    public static TransitGraph of(Graph roadGraph, RouteNetwork network,
                                  csc210.algorithm.cost.CostModel rideMillis, double walkingRadiusM) {
        List<TransitStop> vertices = new ArrayList<>();
        Map<Long, Integer> vertexIndex = new HashMap<>();
        Map<Integer, List<Integer>> atStop = new HashMap<>();

        for (BusRoute route : network.routes()) {
            for (int stop : callsOf(route)) {
                long key = key(stop, route.id());
                if (vertexIndex.containsKey(key)) {
                    continue;
                }
                int index = vertices.size();
                vertices.add(new TransitStop(index, stop, route.id(), roadGraph.node(stop).position()));
                vertexIndex.put(key, index);
                atStop.computeIfAbsent(stop, ignored -> new ArrayList<>()).add(index);
            }
        }

        List<List<TransitLink>> outgoing = new ArrayList<>(vertices.size());
        for (int i = 0; i < vertices.size(); i++) {
            outgoing.add(new ArrayList<>());
        }

        // Ride edges: consecutive stop *occurrences* along one line (repeats
        // included), each priced against the exact slice of road edges the
        // bus drives between them. The return, where there is one, is priced
        // from its own edges, never from the outbound ones reversed.
        for (BusRoute route : network.routes()) {
            addRides(outgoing, vertexIndex, route.id(), route.edges(), route.stopOccurrences(),
                    route.stopOccurrencePositions(), rideMillis);
            if (route.runsBothWays()) {
                BusRoute.Return back = route.back();
                addRides(outgoing, vertexIndex, route.id(), back.edges(), back.stopOccurrences(),
                        back.stopOccurrencePositions(), rideMillis);
            }
        }

        // Transfer edges: same stop, different line, priced at the wait.
        for (Map.Entry<Integer, List<Integer>> entry : atStop.entrySet()) {
            List<Integer> here = entry.getValue();
            for (int from : here) {
                for (int to : here) {
                    if (from != to) {
                        outgoing.get(from).add(
                                new TransitLink(from, to, TRANSFER_WAIT_MILLIS, TransitLink.Kind.TRANSFER, -1));
                    }
                }
            }
        }

        // Walk edges: different stops close enough to walk, then wait for the line there.
        for (int from = 0; from < vertices.size(); from++) {
            for (int to = 0; to < vertices.size(); to++) {
                if (from == to || vertices.get(from).roadNode() == vertices.get(to).roadNode()) {
                    continue;
                }
                double metres = straightLineMetres(vertices.get(from).position(), vertices.get(to).position());
                if (metres > walkingRadiusM) {
                    continue;
                }
                long millis = Math.round(metres / WALK_SPEED_MPS * 1000.0) + TRANSFER_WAIT_MILLIS;
                outgoing.get(from).add(new TransitLink(from, to, millis, TransitLink.Kind.WALK, -1));
            }
        }

        // Flatten to CSR, the same layout the road graph uses.
        int[] adjacencyStart = new int[vertices.size() + 1];
        for (int i = 0; i < vertices.size(); i++) {
            adjacencyStart[i + 1] = adjacencyStart[i] + outgoing.get(i).size();
        }
        TransitLink[] links = new TransitLink[adjacencyStart[vertices.size()]];
        int cursor = 0;
        for (List<TransitLink> fromOne : outgoing) {
            for (TransitLink link : fromOne) {
                links[cursor++] = link;
            }
        }

        Map<Integer, int[]> verticesAtStop = new HashMap<>();
        for (Map.Entry<Integer, List<Integer>> entry : atStop.entrySet()) {
            int[] indices = new int[entry.getValue().size()];
            for (int i = 0; i < indices.length; i++) {
                indices[i] = entry.getValue().get(i);
            }
            verticesAtStop.put(entry.getKey(), indices);
        }

        return new TransitGraph(vertices.toArray(new TransitStop[0]), adjacencyStart, links,
                verticesAtStop, walkingRadiusM);
    }

    /** Every stop a line calls at, outbound first, then any the return alone passes. */
    private static int[] callsOf(BusRoute route) {
        if (!route.runsBothWays()) {
            return route.stops();
        }
        java.util.LinkedHashSet<Integer> calls = new java.util.LinkedHashSet<>();
        for (int stop : route.stops()) {
            calls.add(stop);
        }
        for (int stop : route.back().stopOccurrences()) {
            calls.add(stop);
        }
        return calls.stream().mapToInt(Integer::intValue).toArray();
    }

    /** One ride link per pair of consecutive stop visits along one direction of one line. */
    private static void addRides(List<List<TransitLink>> outgoing, Map<Long, Integer> vertexIndex, int routeId,
                                 int[] edges, int[] occurrences, int[] positions,
                                 csc210.algorithm.cost.CostModel rideMillis) {
        for (int i = 0; i + 1 < occurrences.length; i++) {
            int from = vertexIndex.get(key(occurrences[i], routeId));
            int to = vertexIndex.get(key(occurrences[i + 1], routeId));
            if (from == to) {
                continue;
            }
            long millis = 0L;
            for (int e = positions[i]; e < positions[i + 1]; e++) {
                millis += rideMillis.costMillis(edges[e]);
            }
            outgoing.get(from).add(new TransitLink(from, to, millis, TransitLink.Kind.RIDE, routeId));
        }
    }

    /**
     * The {@code routeId} carried by the two temporary endpoint vertices: the
     * passenger is on foot there, not on any line.
     */
    public static final int ON_FOOT = -1;

    /**
     * A graph with endpoint vertices spliced in, and where they ended up.
     *
     * @param graph the extended graph, two vertices larger than the original
     * @param sourceVertex where the journey starts, on foot
     * @param targetVertex where it ends, on foot
     */
    public record Extended(TransitGraph graph, int sourceVertex, int targetVertex) {}

    /**
     * This graph plus a temporary <em>source</em> and <em>target</em> vertex, so
     * that a query posed between two arbitrary road junctions can be answered by
     * an ordinary single-source, single-target search.
     *
     * <p><strong>Why two extra vertices rather than a cleverer search.</strong>
     * A passenger standing at a junction can board at any of several nearby
     * stops, on any line serving them, each at its own walking cost. That is a
     * <em>many-source</em> problem. The obvious way to handle it is to seed A*'s
     * queue with all those vertices at once, each pre-loaded with its walk cost.
     * That works, but it changes the search itself, and every property already
     * proved about A* — admissibility, settling each vertex once, the first pop
     * of the goal being final — would have to be re-proved against the modified
     * version. Adding a source vertex joined to each candidate by a real edge
     * reduces the same problem to the plain single-source case A* already
     * handles, and the walk gets <em>paid</em> rather than assumed away, exactly
     * as the transfer wait does.
     *
     * <p>The target vertex sits at the destination's own coordinates, which is
     * what keeps {@link csc210.algorithm.search.TransitHeuristic} admissible:
     * a straight line to the destination is still a lower bound on the distance
     * any journey must cover, and the egress walk is far slower than the speed
     * the heuristic divides by.
     *
     * <p>Both are temporary by construction — this returns a new graph and
     * leaves the original untouched, so one query cannot leak into the next.
     *
     * @param originNode road node the passenger starts at
     * @param originPosition its coordinates
     * @param boardMillis per existing vertex, the cost of walking from the
     *        origin and boarding there, or a negative value for "not reachable";
     *        must be one entry per vertex of this graph
     * @param destinationNode road node the passenger is heading for
     * @param destinationPosition its coordinates
     * @param alightMillis per existing vertex, the cost of alighting there and
     *        walking to the destination, or a negative value for "no use"
     */
    public Extended withEndpoints(int originNode, Position originPosition, long[] boardMillis,
                                  int destinationNode, Position destinationPosition, long[] alightMillis) {
        int existing = vertices.length;
        if (boardMillis.length != existing || alightMillis.length != existing) {
            throw new IllegalArgumentException("boardMillis and alightMillis must have one entry per vertex ("
                    + existing + "), got " + boardMillis.length + " and " + alightMillis.length);
        }
        int source = existing;
        int target = existing + 1;
        int total = existing + 2;

        TransitStop[] extendedVertices = Arrays.copyOf(vertices, total);
        extendedVertices[source] = new TransitStop(source, originNode, ON_FOOT, originPosition);
        extendedVertices[target] = new TransitStop(target, destinationNode, ON_FOOT, destinationPosition);

        int boardable = 0;
        for (long millis : boardMillis) {
            if (millis >= 0) {
                boardable++;
            }
        }

        // Each real vertex keeps its own links and gains at most one egress link;
        // the source owns every access link; the target is a sink and has none.
        int[] extendedStart = new int[total + 1];
        for (int i = 0; i < existing; i++) {
            int degree = adjacencyStart[i + 1] - adjacencyStart[i] + (alightMillis[i] >= 0 ? 1 : 0);
            extendedStart[i + 1] = extendedStart[i] + degree;
        }
        extendedStart[source + 1] = extendedStart[source] + boardable;
        extendedStart[target + 1] = extendedStart[target];

        TransitLink[] extendedLinks = new TransitLink[extendedStart[total]];
        int cursor = 0;
        for (int i = 0; i < existing; i++) {
            for (int j = adjacencyStart[i]; j < adjacencyStart[i + 1]; j++) {
                extendedLinks[cursor++] = links[j];
            }
            if (alightMillis[i] >= 0) {
                extendedLinks[cursor++] =
                        new TransitLink(i, target, alightMillis[i], TransitLink.Kind.EGRESS, ON_FOOT);
            }
        }
        for (int i = 0; i < existing; i++) {
            if (boardMillis[i] >= 0) {
                extendedLinks[cursor++] =
                        new TransitLink(source, i, boardMillis[i], TransitLink.Kind.ACCESS, ON_FOOT);
            }
        }

        TransitGraph extended = new TransitGraph(extendedVertices, extendedStart, extendedLinks,
                verticesAtStop, walkingRadiusM);
        return new Extended(extended, source, target);
    }

    public int vertexCount() {
        return vertices.length;
    }

    public int linkCount() {
        return links.length;
    }

    public TransitStop vertex(int index) {
        return vertices[index];
    }

    public double walkingRadiusM() {
        return walkingRadiusM;
    }

    /** Every link leaving {@code vertex}. */
    public TransitLink[] outgoing(int vertex) {
        return Arrays.copyOfRange(links, adjacencyStart[vertex], adjacencyStart[vertex + 1]);
    }

    /** Every (stop, route) vertex sitting at road node {@code roadNode}. */
    public int[] verticesAt(int roadNode) {
        int[] found = verticesAtStop.get(roadNode);
        return found == null ? new int[0] : found.clone();
    }

    /** Road nodes that carry at least one vertex, ascending. */
    public int[] servedStops() {
        int[] stops = verticesAtStop.keySet().stream().mapToInt(Integer::intValue).toArray();
        Arrays.sort(stops);
        return stops;
    }

    public static double straightLineMetres(Position from, Position to) {
        return Math.hypot(from.xM() - to.xM(), from.yM() - to.yM());
    }

    private static long key(int stop, int routeId) {
        return ((long) stop << 32) | (routeId & 0xffffffffL);
    }
}
