package csc210.algorithm.journey;

import csc210.algorithm.graph.Graph;
import csc210.algorithm.model.Node;
import csc210.algorithm.model.Position;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The share of the map a passenger can walk onto the network from, on junctions placed on a line. */
class AccessCoverageTest {

    private static Graph line(double... xs) {
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < xs.length; i++) {
            nodes.add(new Node(i, 100L + i, new Position(0, 0, xs[i], 0), 0, false, 0, false, 0, 0, 0, null, 0, 0));
        }
        return Graph.of(nodes, List.of());
    }

    @Test
    void countsJunctionsWithinTheRadiusOfAServedStopAndReportsTheWorst() {
        // Served stop at junction 0; junctions at 0, 100, 400 and 900 m.
        AccessCoverage access = AccessCoverage.of(line(0, 100, 400, 900), new int[]{0}, 400.0);

        assertEquals(4, access.junctions());
        assertEquals(3, access.reached(), "400 m is inclusive");
        assertEquals(0.75, access.share(), 1e-9);
        assertEquals(900.0, access.farthestMetres(), 1e-9);
    }

    @Test
    void theNearestOfSeveralStopsDecides() {
        AccessCoverage access = AccessCoverage.of(line(0, 500, 1000), new int[]{0, 2}, 400.0);

        assertEquals(2, access.reached());
        assertEquals(500.0, access.farthestMetres(), 1e-9);
    }

    @Test
    void withNoServedStopNothingIsReached() {
        AccessCoverage access = AccessCoverage.of(line(0, 100), new int[0], 400.0);

        assertEquals(0, access.reached());
        assertEquals(0.0, access.share());
        assertTrue(Double.isInfinite(access.farthestMetres()));
    }

    @Test
    void anEmptyMapIsFullyReached() {
        assertEquals(1.0, AccessCoverage.of(line(), new int[0], 400.0).share());
    }

    @Test
    void anInvalidRadiusIsRejected() {
        for (double bad : new double[]{-1.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> AccessCoverage.of(line(0), new int[]{0}, bad));
        }
    }
}
