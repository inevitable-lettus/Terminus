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

    // --- coveringMap: stops added where the simulator flagged none ----------------

    private static boolean[] flags(int n, int... stops) {
        boolean[] flags = new boolean[n];
        for (int stop : stops) flags[stop] = true;
        return flags;
    }

    @Test
    void stopsAreAddedUntilNoJunctionIsFartherThanTheGap() {
        // A junction every 100 m from 0 to 1000 and one flagged stop at 0.
        double[] xs = new double[11];
        for (int i = 0; i < xs.length; i++) xs[i] = i * 100.0;
        csc210.algorithm.graph.Graph graph = line(xs, flags(11, 0));

        StopSet stops = StopSet.coveringMap(graph, 300.0, node -> true);

        assertEquals(1, stops.rawCount());
        assertEquals(stops.count() - 1, stops.fillInCount());
        for (int node = 0; node < xs.length; node++) {
            double nearest = Double.POSITIVE_INFINITY;
            for (int stop : stops.representatives()) {
                nearest = Math.min(nearest, Math.abs(xs[node] - xs[stop]));
            }
            org.junit.jupiter.api.Assertions.assertTrue(nearest <= 300.0, "junction " + node + " is " + nearest + " m from a stop");
        }
        org.junit.jupiter.api.Assertions.assertFalse(stops.isFillIn(0), "the flagged stop is not a fill-in");
        org.junit.jupiter.api.Assertions.assertTrue(stops.isFillIn(stops.representatives()[1]));
    }

    @Test
    void theFarthestJunctionIsFilledFirstAndTiesGoToTheLowerId() {
        // Flagged stop at 0; junctions at 100, 500 and -500 (ids 1, 2, 3): 500 and -500 tie.
        csc210.algorithm.graph.Graph graph = line(new double[]{0, 100, 500, -500}, flags(4, 0));

        StopSet stops = StopSet.coveringMap(graph, 450.0, node -> true);

        assertArrayEquals(new int[]{0, 2, 3}, stops.representatives());
        assertEquals(2, stops.fillInCount());
    }

    @Test
    void sameMapFillsTheSameWay() {
        csc210.algorithm.graph.Graph graph = line(new double[]{0, 700, 1400, 2100}, flags(4, 0));

        assertArrayEquals(StopSet.coveringMap(graph, 300.0, node -> true).representatives(),
                StopSet.coveringMap(graph, 300.0, node -> true).representatives());
    }

    @Test
    void aGapOfZeroAddsNothing() {
        csc210.algorithm.graph.Graph graph = line(new double[]{0, 5000}, flags(2, 0));

        StopSet stops = StopSet.coveringMap(graph, 0.0, node -> true);

        assertArrayEquals(new int[]{0}, stops.representatives());
        assertEquals(0, stops.fillInCount());
    }

    @Test
    void aMapThatIsAlreadyCloseEnoughGainsNoStops() {
        csc210.algorithm.graph.Graph graph = line(new double[]{0, 200, 400}, flags(3, 0, 2));

        assertEquals(0, StopSet.coveringMap(graph, 325.0, node -> true).fillInCount());
    }

    @Test
    void junctionsNoLineCanCallAtAreNeverChosenAndFlaggedStopsAmongThemCloseNoGap() {
        // Flagged stop at 1000 is in a pocket (cannot be called at): it must not stop junction 0 from
        // getting a stop of its own, and the pocket junction 3 must not become a stop itself.
        csc210.algorithm.graph.Graph graph = line(new double[]{0, 1000, 1010, 20}, flags(4, 1));

        StopSet stops = StopSet.coveringMap(graph, 325.0, node -> node == 0 || node == 2);

        org.junit.jupiter.api.Assertions.assertTrue(stops.representativeOf(0) == 0, "junction 0 is now a stop");
        org.junit.jupiter.api.Assertions.assertTrue(stops.representativeOf(3) < 0, "pocket junction 3 is not");
        org.junit.jupiter.api.Assertions.assertEquals(1, stops.rawCount());
    }

    @Test
    void anInvalidGapIsRejected() {
        csc210.algorithm.graph.Graph graph = line(new double[]{0, 100}, flags(2, 0));

        for (double bad : new double[]{-1.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> StopSet.coveringMap(graph, bad, node -> true), "gap " + bad);
        }
    }

    @Test
    void aMapWithNoFlaggedStopsIsLeftAlone() {
        // Nothing to measure a gap against, and nothing to say where a city's stops should go.
        csc210.algorithm.graph.Graph graph = line(new double[]{0, 5000}, flags(2));

        assertEquals(0, StopSet.coveringMap(graph, 325.0, node -> true).count());
    }
}
