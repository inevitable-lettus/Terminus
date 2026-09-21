package csc210.algorithm.demand;

import csc210.algorithm.graph.Graph;
import csc210.algorithm.model.Node;
import csc210.algorithm.model.Position;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Stop merging, on small graphs whose answers can be read off by eye. */
class StopSetTest {

    /** A graph with no edges; stops sit at the given x positions on a line, flagged where {@code stop[i]}. */
    static Graph line(double[] xs, boolean[] stop) {
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < xs.length; i++) {
            nodes.add(new Node(i, 100L + i, new Position(0, 0, xs[i], 0), 0, stop[i],
                    0, false, 0, 0, 0, null, 0, 0));
        }
        return Graph.of(nodes, List.of());
    }

    static boolean[] all(int n) {
        boolean[] flags = new boolean[n];
        java.util.Arrays.fill(flags, true);
        return flags;
    }

    @Test
    void stopsWithinTheMergeRadiusBecomeOneStopStoodForByTheLowestId() {
        StopSet stops = StopSet.of(line(new double[]{0, 10, 500}, all(3)));

        assertArrayEquals(new int[]{0, 2}, stops.representatives());
        assertEquals(3, stops.rawCount());
        assertEquals(0, stops.representativeOf(1), "the merged-away junction remembers where it went");
        assertEquals(2, stops.representativeOf(2));
    }

    @Test
    void theRadiusIsInclusiveAtExactlySixtyMetres() {
        assertEquals(1, StopSet.of(line(new double[]{0, 60.0}, all(2))).count());
        assertEquals(2, StopSet.of(line(new double[]{0, 60.5}, all(2))).count());
    }

    @Test
    void aChainOfCloseStopsIsNotCollapsedIntoOneLongStop() {
        // 0, 50, 100, 150: each is 50 m from the next, but only 0 and 50 are within
        // 60 m of the first representative. 100 starts its own group and absorbs 150.
        StopSet stops = StopSet.of(line(new double[]{0, 50, 100, 150}, all(4)));

        assertArrayEquals(new int[]{0, 2}, stops.representatives());
        assertEquals(2, stops.representativeOf(3));
    }

    @Test
    void junctionsThatAreNotFlaggedAreNeverStops() {
        StopSet stops = StopSet.of(line(new double[]{0, 5, 500}, new boolean[]{true, false, true}));

        assertArrayEquals(new int[]{0, 2}, stops.representatives());
        assertEquals(-1, stops.representativeOf(1));
        assertEquals(2, stops.rawCount());
    }

    @Test
    void outOfRangeNodeIdsAreNotStopsRatherThanAnError() {
        StopSet stops = StopSet.of(line(new double[]{0}, all(1)));

        assertEquals(-1, stops.representativeOf(-1));
        assertEquals(-1, stops.representativeOf(99));
    }

    @Test
    void unclusteredKeepsEveryFlaggedJunctionAsItsOwnStop() {
        StopSet stops = StopSet.unclustered(line(new double[]{0, 1, 2}, all(3)));

        assertArrayEquals(new int[]{0, 1, 2}, stops.representatives());
    }

    @Test
    void theSameMapAlwaysMergesTheSameWay() {
        Graph graph = line(new double[]{0, 40, 80, 120, 160, 200}, all(6));

        assertArrayEquals(StopSet.of(graph).representatives(), StopSet.of(graph).representatives());
    }

    @Test
    void aMapWithNoStopsGivesAnEmptySet() {
        StopSet stops = StopSet.of(line(new double[]{0, 100}, new boolean[]{false, false}));

        assertEquals(0, stops.count());
        assertEquals(0, stops.rawCount());
    }
}
