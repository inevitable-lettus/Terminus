package csc210.algorithm.journey;

import csc210.algorithm.graph.Graph;
import csc210.algorithm.model.Position;
import csc210.algorithm.transit.TransitGraph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Answers one question: standing at road junction X, which bus stops could a
 * passenger reasonably walk to, and how long would each walk take?
 *
 * <p><strong>Why this is a separate concern from the graph.</strong> The
 * {@link TransitGraph} knows how a passenger moves once they are already on the
 * network — ride, transfer, walk between stops. It has nothing to say about
 * getting onto the network in the first place, because that is not a graph
 * question at all: it is a question about geometry, "what is near this point".
 * Mixing the two would mean the transit graph had to carry a vertex for every
 * junction on the map, most of which no bus ever visits.
 *
 * <p><strong>A linear scan is the right structure here, and that is a
 * decision.</strong> Each lookup walks all served stops and keeps the close
 * ones, which is {@code O(S)} for {@code S} stops. On this map {@code S} is
 * about sixty, so a query is sixty distance computations — microseconds. The
 * textbook answer at scale is a k-d tree or a uniform grid, which would turn
 * this into roughly {@code O(log S)} or {@code O(1)}; both are worth building at
 * thousands of stops and neither is worth building at sixty. A spatial index
 * over sixty points would be a data structure with no defensible reason to
 * exist, and this project already dropped one of those in step 1.5 for the same
 * reason. If the map ever grows, this is the class to change and nothing else.
 *
 * <p>Distance is straight-line in the sandbox's projected metres, not road
 * distance. A passenger walking 200 m does not follow the carriageway, and
 * computing a road-network walking path to every candidate stop would cost far
 * more than the answer is worth at this radius.
 */
public final class StopAccessIndex {

    /** Walking speed, in metres per second: 1.4, about 5 km/h. Matches {@link TransitGraph#WALK_SPEED_MPS}. */
    public static final double WALK_SPEED_MPS = TransitGraph.WALK_SPEED_MPS;

    private final Graph roadGraph;
    private final int[] stopNodes;
    private final double radiusM;

    private StopAccessIndex(Graph roadGraph, int[] stopNodes, double radiusM) {
        this.roadGraph = roadGraph;
        this.stopNodes = stopNodes;
        this.radiusM = radiusM;
    }

    /**
     * Indexes the stops {@code transit} actually serves, using that graph's own
     * interchange walking radius so a passenger will walk exactly as far to
     * reach their first bus as they would to change onto another one.
     */
    public static StopAccessIndex of(Graph roadGraph, TransitGraph transit) {
        return of(roadGraph, transit, transit.walkingRadiusM());
    }

    /** As {@link #of(Graph, TransitGraph)}, with the walking radius given. */
    public static StopAccessIndex of(Graph roadGraph, TransitGraph transit, double radiusM) {
        if (!Double.isFinite(radiusM) || radiusM < 0.0) {
            throw new IllegalArgumentException("radiusM must be finite and non-negative, got " + radiusM);
        }
        return new StopAccessIndex(roadGraph, transit.servedStops(), radiusM);
    }

    /**
     * Every served stop within the walking radius of {@code roadNode},
     * cheapest walk first. A stop sitting exactly on {@code roadNode} comes
     * back with a zero-length walk rather than being skipped — a passenger who
     * is already at a stop still has to board there.
     *
     * @return stops in ascending order of walking time, possibly empty
     */
    public List<StopAccess> near(int roadNode) {
        Position from = roadGraph.node(roadNode).position();
        List<StopAccess> found = new ArrayList<>();
        for (int stopNode : stopNodes) {
            double metres = TransitGraph.straightLineMetres(from, roadGraph.node(stopNode).position());
            if (metres <= radiusM) {
                found.add(new StopAccess(stopNode, metres, walkMillis(metres)));
            }
        }
        found.sort(Comparator.comparingLong(StopAccess::walkMillis).thenComparingInt(StopAccess::stopNode));
        return found;
    }

    /** How long it takes to walk {@code metres} on foot, in milliseconds. */
    public static long walkMillis(double metres) {
        return Math.round(metres / WALK_SPEED_MPS * 1000.0);
    }

    /** How many stops are indexed. */
    public int stopCount() {
        return stopNodes.length;
    }

    public double radiusM() {
        return radiusM;
    }
}
