package csc210.algorithm.routing;

import csc210.algorithm.Fixtures;
import csc210.algorithm.graph.StrongComponents;
import csc210.algorithm.journey.AccessCoverage;
import csc210.algorithm.transit.TransitGraph;
import csc210.algorithm.world.World;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole map can be planned with stops: on the saved Berlin and Dar es Salaam
 * captures, and on the Dar es Salaam city with its flagged stops thinned out to a
 * quarter and an eighth to stand for a server whose map has few, every junction a
 * bus can reach has a served stop within the walk journeys allow.
 *
 * <p>Junctions in one-way pockets are left out of the assertion because no line
 * can drive to them or out of them; they are still counted in the printed figure.
 */
class MapCoverageTest {

    private static final double ACCESS_WALK_M = TransitGraph.DEFAULT_INTERCHANGE_WALK_M;

    /** Plans the world's network, prints how much of the map it reaches, and returns the drivable junctions' share. */
    private static double planAndMeasure(String name, World world) {
        RouteNetwork network = new RouteNetworkBuilder(world.graph(), world.costModel(), world.demandModel(),
                world.budget()).build();
        int[] served = TransitGraph.of(world.graph(), network, world.costModel()).servedStops();
        StrongComponents drivable = StrongComponents.of(world.graph(), world.costModel());

        AccessCoverage all = AccessCoverage.of(world.graph(), served, ACCESS_WALK_M);
        AccessCoverage reachable = AccessCoverage.of(world.graph(), served, ACCESS_WALK_M, drivable::inLargest);
        System.out.printf("map coverage %-22s stops %d (+%d added), lines %d, stop demand covered %.1f%%, "
                        + "junctions within %.0f m of a served stop: %.1f%% (%.1f%% of the %d drivable ones), farthest %.0f m%n",
                name, world.stops().count(), world.stops().fillInCount(), network.routeCount(),
                network.coverage() * 100.0, ACCESS_WALK_M, all.share() * 100.0, reachable.share() * 100.0,
                reachable.junctions(), all.farthestMetres());
        return reachable.share();
    }

    private static void assertEveryDrivableJunctionHasAServedStop(String name, World world) {
        assertEquals(1.0, planAndMeasure(name, world), 0.0, name + ": drivable junctions within " + ACCESS_WALK_M + " m of a served stop");
    }

    @Test
    void berlinFixture() {
        assertEveryDrivableJunctionHasAServedStop("berlin 18:00", Fixtures.world1800());
    }

    @Test
    void darEsSalaamFixtureAtBothHours() {
        assertEveryDrivableJunctionHasAServedStop("dar es salaam 18:00", Fixtures.v2World1800());
        assertEveryDrivableJunctionHasAServedStop("dar es salaam 08:30", Fixtures.v2World0830());
    }

    @Test
    void darEsSalaamWithAThirdOfItsStops() {
        for (int keepEvery : new int[]{2, 4, 8}) {
            assertEveryDrivableJunctionHasAServedStop("18:00 one stop in " + keepEvery,
                    Fixtures.v2WorldWithSparseStops1800(keepEvery));
            assertEveryDrivableJunctionHasAServedStop("08:30 one stop in " + keepEvery,
                    Fixtures.v2WorldWithSparseStops0830(keepEvery));
        }
    }

    @Test
    void withoutAddedStopsASparseMapLeavesDrivableJunctionsWithNoBusWithinReach() {
        // The failure this guards against: an eighth of the stops, none added, and the radius
        // alone cannot put a bus near the districts that have no stop.
        double share = planAndMeasure("18:00 one in 8, none added", Fixtures.v2WorldWithSparseStops1800(8, 0.0));

        assertTrue(share < 0.9, "share " + share);
    }

    @Test
    void addedStopsDoNotMoveWithTheTimeOfDayOrWhichRoadsAreClosed() {
        // The network is planned once and re-priced as speeds and closures change, so the stops
        // it was planned for must be the same at every reading of the same map.
        assertArrayEquals(Fixtures.v2WorldWithSparseStops1800(8).stops().representatives(),
                Fixtures.v2WorldWithSparseStops0830(8).stops().representatives());
    }

    @Test
    void sparseStopsGetStopsAddedAndFullStopsDoNot() {
        assertEquals(0, Fixtures.v2World1800().stops().fillInCount());
        assertEquals(0, Fixtures.world1800().stops().fillInCount());
        assertTrue(Fixtures.v2WorldWithSparseStops1800(8).stops().fillInCount() > 0);
    }

    @Test
    void theDerivedRadiusKeepsAStopWithinAccessWalkOfEveryJunctionItCovers() {
        for (World world : new World[]{Fixtures.v2World1800(), Fixtures.v2WorldWithSparseStops1800(8)}) {
            double gap = AccessCoverage.of(world.graph(), world.stops().representatives(), 0.0).farthestMetres();
            assertTrue(gap + world.budget().walkingRadiusM() <= ACCESS_WALK_M + 1e-9
                            || world.budget().walkingRadiusM() == RouteBudget.MIN_WALKING_RADIUS_M,
                    "gap " + gap + " + radius " + world.budget().walkingRadiusM());
        }
    }
}
