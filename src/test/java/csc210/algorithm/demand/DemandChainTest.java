package csc210.algorithm.demand;

import csc210.algorithm.client.Place;
import csc210.algorithm.client.SnapshotData;
import csc210.algorithm.graph.Graph;
import csc210.algorithm.model.Node;
import csc210.algorithm.model.NodeState;
import csc210.algorithm.model.Position;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fallback chain of {@link DemandModel#of(Graph, SnapshotData, StopSet, List)}:
 * places, then buildings, then uniform. Each rung is proven on a graph small enough
 * to check by hand.
 */
class DemandChainTest {

    /**
     * Junctions 0 and 1 are stops 500 m apart; 2 is a stop 20 m from 0 (a kerb
     * duplicate); 3 is a school 100 m from stop 1.
     */
    private static Graph graph(boolean withSchool) {
        List<Node> nodes = new ArrayList<>();
        nodes.add(node(0, 0, true, null));
        nodes.add(node(1, 500, true, null));
        nodes.add(node(2, 20, true, null));
        nodes.add(withSchool
                ? new Node(3, 103L, new Position(0, 0, 600, 0), 0, false, 0, false, 0, 0, 0, "school", 0.5, 250)
                : node(3, 600, false, null));
        return Graph.of(nodes, List.of());
    }

    private static Node node(int id, double x, boolean stop, String building) {
        return new Node(id, 100L + id, new Position(0, 0, x, 0), 0, stop, 0, false, 0, 0, 0, building, 0, 0);
    }

    private static SnapshotData snapshot(double schoolEffect) {
        List<NodeState> states = new ArrayList<>();
        for (int id = 0; id < 4; id++) {
            states.add(new NodeState(id, id == 3 ? schoolEffect : 0.0, 0, 0));
        }
        return new SnapshotData(states, List.of(), 0);
    }

    private static Place stopPlace(int anchor, double multiplier) {
        return new Place("node/" + anchor, "", "bus_stop", new Position(0, 0, 0, 0), anchor,
                new Place.Demand(multiplier, 1.0, 400.0));
    }

    @Test
    void placesWinWhenAnyStopHasOneAndTheirMultipliersAreSummedOverMergedJunctions() {
        Graph graph = graph(true);
        StopSet stops = StopSet.of(graph);
        // Two places anchor to the merged pair {0, 2}; one to stop 1.
        List<Place> places = List.of(stopPlace(0, 1.5), stopPlace(2, 1.25), stopPlace(1, 2.0));

        DemandModel model = DemandModel.of(graph, snapshot(1.0), stops, places);

        assertEquals(DemandSource.PLACES, model.source());
        assertEquals(2, model.stopCount(), "the kerb duplicate is merged away");
        assertEquals(2.75, model.demandAt(0), 1e-9);
        assertEquals(2.0, model.demandAt(1), 1e-9);
        assertEquals(4.75, model.totalDemand(), 1e-9);
    }

    @Test
    void aStopWithNoPlaceFallsBackToTheBaselineWhileOthersUsePlaces() {
        Graph graph = graph(false);

        DemandModel model = DemandModel.of(graph, snapshot(0.0), StopSet.of(graph), List.of(stopPlace(1, 2.0)));

        assertEquals(DemandSource.PLACES, model.source());
        assertEquals(PlaceDemand.BASELINE, model.demandAt(0), 1e-9);
        assertEquals(2.0, model.demandAt(1), 1e-9);
    }

    @Test
    void placesThatAttachToNoStopAreIgnoredAndBuildingsTakeOver() {
        Graph graph = graph(true);
        // Anchored at junction 3, which is not a stop; and one of the wrong kind.
        Place wrongKind = new Place("x", "", "school", new Position(0, 0, 0, 0), 1, new Place.Demand(3.0, 1.0, 400));

        DemandModel model = DemandModel.of(graph, snapshot(1.0), StopSet.of(graph), List.of(stopPlace(3, 2.0), wrongKind));

        assertEquals(DemandSource.BUILDINGS, model.source());
        assertEquals(0.5, model.demandAt(1), 1e-9, "the school is 100 m from stop 1 and inside its 250 m radius");
        assertEquals(0.0, model.demandAt(0), 1e-9);
    }

    @Test
    void nonFiniteOrNegativeMultipliersAreDiscardedRatherThanPoisoningTheTotal() {
        Graph graph = graph(false);

        DemandModel model = DemandModel.of(graph, snapshot(0.0), StopSet.of(graph),
                List.of(stopPlace(0, Double.NaN), stopPlace(1, -1.0)));

        assertEquals(DemandSource.UNIFORM, model.source());
        assertTrue(Double.isFinite(model.totalDemand()));
    }

    @Test
    void noPlacesAtAllUsesBuildingsWhenTheyFindAnything() {
        Graph graph = graph(true);

        assertEquals(DemandSource.BUILDINGS, DemandModel.of(graph, snapshot(1.0), StopSet.of(graph), null).source());
        assertEquals(DemandSource.BUILDINGS, DemandModel.of(graph, snapshot(1.0), StopSet.of(graph), List.of()).source());
    }

    @Test
    void whenNothingFindsAnyDemandEveryStopCountsTheSameSoARouteCanStillBeDesigned() {
        Graph graph = graph(false);

        DemandModel model = DemandModel.of(graph, snapshot(0.0), StopSet.of(graph), null);

        assertEquals(DemandSource.UNIFORM, model.source());
        assertEquals(1.0, model.demandAt(0));
        assertEquals(1.0, model.demandAt(1));
        assertEquals(2.0, model.totalDemand());
    }

    @Test
    void theLegacyFactoryNeverSubstitutesAndKeepsEveryFlaggedJunction() {
        Graph graph = graph(false);

        DemandModel model = DemandModel.of(graph, snapshot(0.0));

        assertEquals(DemandSource.BUILDINGS, model.source());
        assertEquals(3, model.stopCount());
        assertEquals(0.0, model.totalDemand());
        assertFalse(model.totalDemand() > 0);
    }

    @Test
    void aWorldWithNoStopsHasZeroTotalDemandAndNoFailure() {
        Graph graph = Graph.of(List.of(node(0, 0, false, null)), List.of());
        SnapshotData empty = new SnapshotData(List.of(new NodeState(0, 0, 0, 0)), List.of(), 0);

        DemandModel model = DemandModel.of(graph, empty, StopSet.of(graph), null);

        assertEquals(0, model.stopCount());
        assertEquals(0.0, model.totalDemand());
    }
}
