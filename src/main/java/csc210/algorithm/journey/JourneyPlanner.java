package csc210.algorithm.journey;

import csc210.algorithm.graph.Graph;
import csc210.algorithm.model.Position;
import csc210.algorithm.search.AStar;
import csc210.algorithm.search.TransitHeuristic;
import csc210.algorithm.search.TransitPath;
import csc210.algorithm.transit.TransitGraph;
import csc210.algorithm.transit.TransitLink;

import java.util.Arrays;
import java.util.List;

/**
 * Answers the question a passenger actually asks: <em>how do I get from here to
 * there?</em> — where "here" and "there" are any two road junctions, not bus
 * stops the network happens to serve.
 *
 * <p>Everything below {@link csc210.algorithm.search.AStar} works in
 * <em>transit vertices</em>: internal indices meaning "stop 27, on line R2".
 * That is the right currency for the search and useless as an interface. This
 * class is the translation layer in both directions — junctions in, readable
 * legs out — and it is deliberately the only place that knows about both.
 *
 * <p>The search itself is untouched. Access and egress are handled by splicing
 * two temporary vertices into the graph (see
 * {@link TransitGraph#withEndpoints}), so A* runs exactly as it was proved to.
 */
public final class JourneyPlanner {

    private final Graph roadGraph;
    private final TransitGraph transit;
    private final StopAccessIndex access;
    private final TransitHeuristic.Distance distance;
    private final long boardWaitMillis;

    public JourneyPlanner(Graph roadGraph, TransitGraph transit, StopAccessIndex access,
                          TransitHeuristic.Distance distance) {
        this(roadGraph, transit, access, distance, 0L);
    }

    private JourneyPlanner(Graph roadGraph, TransitGraph transit, StopAccessIndex access,
                           TransitHeuristic.Distance distance, long boardWaitMillis) {
        this.roadGraph = roadGraph;
        this.transit = transit;
        this.access = access;
        this.distance = distance;
        this.boardWaitMillis = boardWaitMillis;
    }

    /**
     * The same planner, with {@code millis} of waiting added before the first
     * bus. Zero by default: DSTNS publishes no headways, so any figure here is
     * a stand-in the user chooses, not something the simulator says. A wait
     * makes the bus dearer and never the walk, so it can tip a short journey
     * over to walking — which is exactly what a real wait does.
     */
    public JourneyPlanner withBoardWait(long millis) {
        if (millis < 0) {
            throw new IllegalArgumentException("a boarding wait cannot be negative, got " + millis);
        }
        return new JourneyPlanner(roadGraph, transit, access, distance, millis);
    }

    /**
     * A planner over {@code transit}, choosing the tightest heuristic this map
     * permits: Manhattan where every road is axis-aligned, Euclidean otherwise.
     *
     * <p>The check is not a formality. Manhattan is the better guess on a grid
     * and an <em>inadmissible</em> one on a map with diagonal roads, where it
     * would quietly cost A* its optimality guarantee. Asking the map rather
     * than assuming the answer is what lets the same code run on the sandbox's
     * grid and on a real road network without being edited.
     */
    public static JourneyPlanner of(Graph roadGraph, TransitGraph transit) {
        TransitHeuristic.Distance choice = TransitHeuristic.isManhattanAdmissibleOn(roadGraph)
                ? TransitHeuristic.Distance.MANHATTAN
                : TransitHeuristic.Distance.EUCLIDEAN;
        return new JourneyPlanner(roadGraph, transit, StopAccessIndex.of(roadGraph, transit), choice);
    }

    /** Which distance the heuristic is measuring with. */
    public TransitHeuristic.Distance distance() {
        return distance;
    }

    /**
     * The cheapest way to get from {@code originNode} to {@code destinationNode}.
     *
     * <p>Three answers are possible and all three are legitimate: a bus journey,
     * a walk when no bus beats one, and no journey at all when the destination
     * is out of reach. The last is reported, not thrown — "you cannot get there
     * from here" is a real answer to a real question.
     */
    public Itinerary plan(int originNode, int destinationNode) {
        if (originNode == destinationNode) {
            return Itinerary.alreadyThere();
        }

        Position origin = roadGraph.node(originNode).position();
        Position destination = roadGraph.node(destinationNode).position();

        // Walking the whole way is compared as a candidate answer rather than
        // wired in as a graph edge. It needs no search — it is one number — and
        // adding an origin-to-destination edge would let the search "discover"
        // it as though it were a transit option, which it is not.
        //
        // Two rules, for two situations. Where a bus journey exists, walking
        // beats it at any distance if it is quicker — offering a 21-minute bus
        // ride over an 11-minute walk is just wrong. Where none exists, walking
        // is offered only within the walking radius, which is what keeps
        // "unreachable" a meaningful answer instead of a two-hour trudge.
        double directMetres = TransitGraph.straightLineMetres(origin, destination);
        long directWalkMillis = StopAccessIndex.walkMillis(directMetres);
        boolean walkableWithoutABus = directMetres <= access.radiusM();

        long[] boardMillis = costsFrom(originNode);
        long[] alightMillis = costsFrom(destinationNode);
        if (boardWaitMillis > 0) {
            for (int v = 0; v < boardMillis.length; v++) {
                if (boardMillis[v] >= 0) {
                    boardMillis[v] += boardWaitMillis;
                }
            }
        }

        if (!anyReachable(boardMillis) || !anyReachable(alightMillis)) {
            return walkableWithoutABus
                    ? walkTheWholeWay(originNode, destinationNode, directWalkMillis, 0)
                    : Itinerary.notFound(0);
        }

        TransitGraph.Extended extended = transit.withEndpoints(
                originNode, origin, boardMillis,
                destinationNode, destination, alightMillis);

        TransitHeuristic heuristic = TransitHeuristic.towards(
                extended.graph(), extended.targetVertex(), distance, roadGraph);
        TransitPath path = new AStar(extended.graph())
                .find(extended.sourceVertex(), extended.targetVertex(), heuristic::estimateMillis);

        if (!path.found()) {
            return walkableWithoutABus
                    ? walkTheWholeWay(originNode, destinationNode, directWalkMillis, path.verticesExpanded())
                    : Itinerary.notFound(path.verticesExpanded());
        }
        if (directWalkMillis < path.totalMillis()) {
            return walkTheWholeWay(originNode, destinationNode, directWalkMillis, path.verticesExpanded());
        }
        Itinerary byBus = Itinerary.of(path.links(), path.totalMillis(), path.verticesExpanded(),
                vertex -> extended.graph().vertex(vertex).roadNode());
        return boardWaitMillis > 0 ? byBus.withBoardWaitSplitOut(boardWaitMillis) : byBus;
    }

    /**
     * Cost of walking from {@code roadNode} to each transit vertex in reach, or
     * a negative value where there is none. Used for boarding and for alighting
     * alike: the walk to a stop and the walk from one are the same walk.
     */
    private long[] costsFrom(int roadNode) {
        long[] millis = new long[transit.vertexCount()];
        Arrays.fill(millis, -1L);
        for (StopAccess stop : access.near(roadNode)) {
            for (int vertex : transit.verticesAt(stop.stopNode())) {
                millis[vertex] = stop.walkMillis();
            }
        }
        return millis;
    }

    private static boolean anyReachable(long[] millis) {
        for (long one : millis) {
            if (one >= 0) {
                return true;
            }
        }
        return false;
    }

    private static Itinerary walkTheWholeWay(int originNode, int destinationNode, long millis,
                                             int verticesExpanded) {
        return new Itinerary(
                List.of(new Itinerary.Leg(TransitLink.Kind.WALK_DIRECT, originNode, destinationNode,
                        TransitGraph.ON_FOOT, 0, millis)),
                millis, 0, verticesExpanded, true);
    }
}
