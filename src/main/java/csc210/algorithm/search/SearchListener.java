package csc210.algorithm.search;

/**
 * Watches a search settle vertices, in the order it settles them. Optional:
 * {@link AStar} takes {@code null} and then does no extra work at all.
 *
 * <p>This is how the expansion order becomes visible — the dashboard draws A*'s
 * wavefront against Dijkstra's on the same query, and the research sweep counts
 * what each did — without the search itself knowing anyone is watching.
 */
@FunctionalInterface
public interface SearchListener {

    /**
     * {@code vertex} was taken off the queue, its cost now final.
     *
     * @param costMillis the settled cost, {@code g}
     * @param estimateMillis the heuristic's guess from here, {@code h}; 0 for Dijkstra
     */
    void onSettle(int vertex, long costMillis, long estimateMillis);
}
