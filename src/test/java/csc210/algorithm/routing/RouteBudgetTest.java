package csc210.algorithm.routing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Regression for the audit's hang: a negative or NaN {@code walkingRadiusM}
 * made every stop's walk-neighbourhood (including itself) empty, so the
 * builder could never mark a hub covered and retried it forever. The fix is
 * to reject invalid budgets at construction instead of letting the builder
 * discover it the hard way.
 */
class RouteBudgetTest {

    @Test
    void defaultsAreValid() {
        RouteBudget.defaults();
    }

    @Test
    void negativeWalkingRadiusIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RouteBudget(10_000L, 1, 1.0, -1.0));
    }

    @Test
    void nanWalkingRadiusIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RouteBudget(10_000L, 1, 1.0, Double.NaN));
    }

    @Test
    void infiniteWalkingRadiusIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new RouteBudget(10_000L, 1, 1.0, Double.POSITIVE_INFINITY));
    }

    @Test
    void zeroWalkingRadiusIsAccepted() {
        new RouteBudget(10_000L, 1, 1.0, 0.0);
    }

    @Test
    void nonPositiveMaxRouteMillisIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RouteBudget(0L, 1, 1.0, 250.0));
        assertThrows(IllegalArgumentException.class, () -> new RouteBudget(-1L, 1, 1.0, 250.0));
    }

    @Test
    void negativeMaxRoutesIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RouteBudget(10_000L, -1, 1.0, 250.0));
    }

    @Test
    void zeroMaxRoutesIsAccepted() {
        new RouteBudget(10_000L, 0, 1.0, 250.0);
    }

    @Test
    void targetCoverageOutsideZeroOneIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RouteBudget(10_000L, 1, -0.1, 250.0));
        assertThrows(IllegalArgumentException.class, () -> new RouteBudget(10_000L, 1, 1.1, 250.0));
    }

    @Test
    void nonFiniteTargetCoverageIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RouteBudget(10_000L, 1, Double.NaN, 250.0));
    }

    // --- forStops: a budget scaled to the map -------------------------------

    private static csc210.algorithm.graph.Graph stopsAt(double... xs) {
        java.util.List<csc210.algorithm.model.Node> nodes = new java.util.ArrayList<>();
        for (int i = 0; i < xs.length; i++) {
            nodes.add(new csc210.algorithm.model.Node(i, 100L + i,
                    new csc210.algorithm.model.Position(0, 0, xs[i], 0), 0, true, 0, false, 0, 0, 0, null, 0, 0));
        }
        return csc210.algorithm.graph.Graph.of(nodes, java.util.List.of());
    }

    private static int[] ids(int n) {
        int[] ids = new int[n];
        for (int i = 0; i < n; i++) {
            ids[i] = i;
        }
        return ids;
    }

    @Test
    void walkingRadiusIsHalfTheMedianSpacingBetweenNeighbouringStops() {
        // Stops every 400 m: nearest neighbour is 400 m for all of them.
        RouteBudget budget = RouteBudget.forStops(stopsAt(0, 400, 800, 1200), ids(4));

        org.junit.jupiter.api.Assertions.assertEquals(200.0, budget.walkingRadiusM(), 1e-9);
    }

    @Test
    void walkingRadiusNeverFallsBelowTheMergeRadiusSoAbsorbedStopsStillCount() {
        // Stops 100 m apart would give 50 m, which would leave a merged-away stop
        // (up to 60 m from its representative) outside its own stop's reach.
        RouteBudget budget = RouteBudget.forStops(stopsAt(0, 100, 200, 300), ids(4));

        org.junit.jupiter.api.Assertions.assertTrue(
                budget.walkingRadiusM() > csc210.algorithm.demand.StopSet.MERGE_RADIUS_M,
                "radius " + budget.walkingRadiusM());
        org.junit.jupiter.api.Assertions.assertEquals(RouteBudget.MIN_WALKING_RADIUS_M, budget.walkingRadiusM());
    }

    @Test
    void walkingRadiusIsCappedAtTheFiveMinutePlanningFigure() {
        RouteBudget budget = RouteBudget.forStops(stopsAt(0, 5000, 10000), ids(3));

        org.junit.jupiter.api.Assertions.assertEquals(RouteBudget.MAX_WALKING_RADIUS_M, budget.walkingRadiusM());
    }

    @Test
    void routeCeilingGrowsWithTheNumberOfStopsWithinItsBounds() {
        double[] few = new double[5];
        double[] many = new double[100];
        double[] huge = new double[500];
        for (int i = 0; i < many.length; i++) many[i] = i * 300.0;
        for (int i = 0; i < huge.length; i++) huge[i] = i * 300.0;
        for (int i = 0; i < few.length; i++) few[i] = i * 300.0;

        org.junit.jupiter.api.Assertions.assertEquals(3, RouteBudget.forStops(stopsAt(few), ids(5)).maxRoutes());
        org.junit.jupiter.api.Assertions.assertEquals(9, RouteBudget.forStops(stopsAt(many), ids(100)).maxRoutes());
        org.junit.jupiter.api.Assertions.assertEquals(20, RouteBudget.forStops(stopsAt(huge), ids(500)).maxRoutes());
    }

    @Test
    void fewerThanTwoStopsGivesTheDefaultsInsteadOfFailing() {
        org.junit.jupiter.api.Assertions.assertEquals(RouteBudget.defaults(),
                RouteBudget.forStops(stopsAt(0), ids(1)));
        org.junit.jupiter.api.Assertions.assertEquals(RouteBudget.defaults(),
                RouteBudget.forStops(stopsAt(0), new int[0]));
    }

    @Test
    void overridesReplaceOneFieldAndLeaveTheRest() {
        RouteBudget base = RouteBudget.defaults();

        org.junit.jupiter.api.Assertions.assertEquals(321.0, base.withWalkingRadius(321.0).walkingRadiusM());
        org.junit.jupiter.api.Assertions.assertEquals(base.maxRoutes(), base.withWalkingRadius(321.0).maxRoutes());
        org.junit.jupiter.api.Assertions.assertEquals(2, base.withMaxRoutes(2).maxRoutes());
        org.junit.jupiter.api.Assertions.assertEquals(base.walkingRadiusM(), base.withMaxRoutes(2).walkingRadiusM());
    }
}
