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
}
