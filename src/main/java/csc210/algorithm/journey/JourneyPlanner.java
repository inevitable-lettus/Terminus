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

    public JourneyPlanner(Graph roadGraph, TransitGraph transit, StopAccessIndex access,
                          TransitHeuristic.Distance distance) {
        this.roadGraph = roadGraph;
        this.transit = transit;
        this.access = access;
        this.distance = distance;
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
        // it as though it were a transit option, which it is not. Bounded by the
        // same radius as every other walk in the module: beyond that a passenger
        // is assumed to want a bus, which is also what keeps "unreachable" a
        // meaningful answer instead of every query returning a two-hour trudge.
        double directMetres = TransitGraph.straightLineMetres(origin, destination);
        Long directWalkMillis = directMetres <= access.radiusM()
                ? StopAccessIndex.walkMillis(directMetres)
                : null;

        long[] boardMillis = costsFrom(originNode);
        long[] alightMillis = costsFrom(destinationNode);

        if (!anyReachable(boardMillis) || !anyReachable(alightMillis)) {
            return directWalkMillis == null
                    ? Itinerary.notFound(0)
                    : walkTheWholeWay(originNode, destinationNode, directWalkMillis, 0);
        }

        TransitGraph.Extended extended = transit.withEndpoints(
                originNode, origin, boardMillis,
                destinationNode, destination, alightMillis);

        TransitHeuristic heuristic = TransitHeuristic.towards(
                extended.graph(), extended.targetVertex(), distance, roadGraph);
        TransitPath path = new AStar(extended.graph())
                .find(extended.sourceVertex(), extended.targetVertex(), heuristic::estimateMillis);

        if (!path.found()) {
            return directWalkMillis == null
                    ? Itinerary.notFound(path.verticesExpanded())
                    : walkTheWholeWay(originNode, destinationNode, directWalkMillis, path.verticesExpanded());
        }
        if (directWalkMillis != null && directWalkMillis < path.totalMillis()) {
            return walkTheWholeWay(originNode, destinationNode, directWalkMillis, path.verticesExpanded());
        }
        return Itinerary.of(path.links(), path.totalMillis(), path.verticesExpanded(),
                vertex -> extended.graph().vertex(vertex).roadNode());
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
