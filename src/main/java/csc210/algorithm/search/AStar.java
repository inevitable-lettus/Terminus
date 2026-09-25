package csc210.algorithm.search;

import csc210.algorithm.ds.IndexedMinHeap;
import csc210.algorithm.transit.TransitGraph;
import csc210.algorithm.transit.TransitLink;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * <strong>Algorithm 2.</strong> Finds the cheapest journey for one passenger
 * between two points on the {@link TransitGraph}.
 *
 * <p>A* is Dijkstra plus one idea. Dijkstra always explores whatever is cheapest
 * <em>so far</em>, which means it spreads outward in every direction equally,
 * including directly away from where you are going. A* orders the queue by
 * <pre>f = g + h</pre>
 * where {@code g} is what the journey has genuinely cost so far and {@code h} is
 * an optimistic guess of what is still to come. Read plainly: "money already
 * spent, plus the best case for the rest." Vertices pointing toward the
 * destination get a small {@code h} and are examined first; vertices pointing
 * away get a large one and wait.
 *
 * <p>Set {@code h = 0} everywhere and the ordering becomes {@code f = g} —
 * A* <em>is</em> Dijkstra, exactly, not merely similar to it. That is why the
 * two return identical costs: A* is Dijkstra with a better queue order, and the
 * guess only ever changes what gets looked at first, never what is optimal. It
 * is used here rather than Dijkstra because a passenger query has a single
 * destination, while Phase 4's route builder needs distances to everywhere at
 * once and would gain nothing from aiming.
 *
 * <p>Correctness depends entirely on the guess never overestimating — see
 * {@link TransitHeuristic}. With an admissible and consistent heuristic, the
 * first time the goal is popped its cost is final, for the same reason it is in
 * Dijkstra.
 */
public final class AStar {

    private final TransitGraph graph;

    public AStar(TransitGraph graph) {
        this.graph = graph;
    }

    /**
     * Cheapest journey from {@code sourceVertex} to {@code goalVertex}.
     *
     * @param heuristic the guess to aim with; pass {@link #zeroHeuristic()} to
     *        run as plain Dijkstra
     */
    public TransitPath find(int sourceVertex, int goalVertex, Heuristic heuristic) {
        return find(sourceVertex, goalVertex, heuristic, null);
    }

    /**
     * As {@link #find(int, int, Heuristic)}, telling {@code listener} about each
     * vertex as it is settled.
     *
     * @param listener told the settle order, or {@code null} for none
     */
    public TransitPath find(int sourceVertex, int goalVertex, Heuristic heuristic, SearchListener listener) {
        int vertexCount = graph.vertexCount();
        long[] costSoFar = new long[vertexCount];
        Arrays.fill(costSoFar, Long.MAX_VALUE);
        int[] cameFrom = new int[vertexCount];
        Arrays.fill(cameFrom, -1);
        TransitLink[] arrivedBy = new TransitLink[vertexCount];
        boolean[] settled = new boolean[vertexCount];

        IndexedMinHeap open = new IndexedMinHeap(vertexCount);
        costSoFar[sourceVertex] = 0L;
        open.insert(sourceVertex, heuristic.estimateMillis(sourceVertex));

        int verticesExpanded = 0;
        while (!open.isEmpty()) {
            int current = open.extractMin();
            settled[current] = true;
            verticesExpanded++;
            if (listener != null) {
                listener.onSettle(current, costSoFar[current], heuristic.estimateMillis(current));
            }

            if (current == goalVertex) {
                return reconstruct(sourceVertex, goalVertex, costSoFar, cameFrom, arrivedBy, verticesExpanded);
            }

            for (TransitLink link : graph.outgoing(current)) {
                int next = link.to();
                if (settled[next]) {
                    continue;
                }
                long throughCurrent = costSoFar[current] + link.millis();
                if (throughCurrent < costSoFar[next]) {
                    costSoFar[next] = throughCurrent;
                    cameFrom[next] = current;
                    arrivedBy[next] = link;
                    // The queue is ordered by f = g + h, but the answer reported
                    // is g: the guess steers the search and never enters the cost.
                    open.insertOrDecrease(next, throughCurrent + heuristic.estimateMillis(next));
                }
            }
        }
        return TransitPath.notFound(verticesExpanded);
    }

    private TransitPath reconstruct(int source, int goal, long[] costSoFar, int[] cameFrom,
                                    TransitLink[] arrivedBy, int verticesExpanded) {
        List<Integer> vertices = new ArrayList<>();
        List<TransitLink> links = new ArrayList<>();
        for (int vertex = goal; vertex != source; vertex = cameFrom[vertex]) {
            vertices.add(vertex);
            links.add(arrivedBy[vertex]);
        }
        vertices.add(source);
        Collections.reverse(vertices);
        Collections.reverse(links);

        int[] vertexArray = new int[vertices.size()];
        for (int i = 0; i < vertexArray.length; i++) {
            vertexArray[i] = vertices.get(i);
        }
        return new TransitPath(vertexArray, List.copyOf(links), costSoFar[goal], verticesExpanded, true);
    }

    /** What A* asks of a heuristic: milliseconds still to come, at best. */
    @FunctionalInterface
    public interface Heuristic {
        long estimateMillis(int vertex);
    }

    /** The guess that guesses nothing, turning A* back into Dijkstra. */
    public static Heuristic zeroHeuristic() {
        return vertex -> 0L;
    }
}
