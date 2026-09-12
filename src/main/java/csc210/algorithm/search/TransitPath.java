package csc210.algorithm.search;

import csc210.algorithm.transit.TransitLink;

import java.util.List;

/**
 * One passenger journey found by {@link AStar}.
 *
 * @param vertices the (stop, route) vertices passed through, in order
 * @param links the moves made between them, one fewer than {@code vertices}
 * @param totalMillis what the journey costs end to end
 * @param verticesExpanded how many vertices the search settled — the
 *        machine-independent measure of how much work it did, and the number
 *        Phase 6 compares between Dijkstra and A*
 * @param found false if no journey exists at all, in which case the other
 *        fields are empty
 */
public record TransitPath(
        int[] vertices,
        List<TransitLink> links,
        long totalMillis,
        int verticesExpanded,
        boolean found
) {

    /**
     * How many times the passenger changes line, whether by transfer or by
     * walking between stops. The access and egress walks at the two ends of a
     * door-to-door journey are not changes of bus and are not counted.
     */
    public int transfers() {
        int count = 0;
        for (TransitLink link : links) {
            if (link.isLineChange()) {
                count++;
            }
        }
        return count;
    }

    static TransitPath notFound(int verticesExpanded) {
        return new TransitPath(new int[0], List.of(), Long.MAX_VALUE, verticesExpanded, false);
    }
}
