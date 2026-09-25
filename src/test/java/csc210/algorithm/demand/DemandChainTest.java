package csc210.algorithm.demand;

import csc210.algorithm.client.DemandEntry;
import csc210.algorithm.client.Feature;
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
 * generator catchment, then buildings, then uniform. Each rung is proven on a graph small enough
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
        return snapshot(schoolEffect, null);
    }

    private static SnapshotData snapshot(double schoolEffect, List<DemandEntry> demand) {
        List<NodeState> states = new ArrayList<>();
        for (int id = 0; id < 4; id++) {
            states.add(new NodeState(id, id == 3 ? schoolEffect : 0.0, 0, 0));
        }
        return new SnapshotData(states, List.of(), 0, demand);
    }

    /** A mapped office {@code x} metres along the same line as the stops. */
    private static Feature feature(String id, double x) {
        return new Feature(id, "", "office", new Position(0, 0, x, 0), null, "office");
    }

    private static DemandEntry generator(String id, double multiplier) {
        return new DemandEntry(id, "office", multiplier, 1.0, 400.0, true);
    }

    @Test
    void eachGeneratorIsCreditedToItsNearestStopOnlySoNothingIsCountedTwice() {
        Graph graph = graph(true);
        StopSet stops = StopSet.of(graph);
        // 100 m from stop 0; 50 m from stop 1; 260 m from stop 0 but 240 m from stop 1.
        List<Feature> features = List.of(feature("a", 100), feature("b", 450), feature("c", 260));
        List<DemandEntry> demand = List.of(generator("a", 1.5), generator("b", 2.0), generator("c", 1.25));

        DemandModel model = DemandModel.of(graph, snapshot(1.0, demand), stops, features);

        assertEquals(DemandSource.CATCHMENT, model.source());
        assertEquals(2, model.stopCount(), "the kerb duplicate is merged away");
        assertEquals(1.5, model.demandAt(0), 1e-9);
        assertEquals(3.25, model.demandAt(1), 1e-9);
        assertEquals(4.75, model.totalDemand(), 1e-9, "the total is every generator once");
        assertEquals(3, model.generatorsAssigned());
        assertEquals(3, model.generatorsActive());
    }

    @Test
    void aGeneratorExactlyBetweenTwoStopsGoesToTheLowerIdEveryTime() {
        Graph graph = graph(false);

        DemandModel model = DemandModel.of(graph, snapshot(0.0, List.of(generator("mid", 2.0))),
                StopSet.of(graph), List.of(feature("mid", 250)));

        assertEquals(2.0, model.demandAt(0), 1e-9);
        assertEquals(0.0, model.demandAt(1), 1e-9);
    }

    @Test
    void closedStopLikeUnplacedAndOutOfReachGeneratorsAreLeftOut() {
        Graph graph = graph(false);
        List<Feature> features = List.of(feature("counted", 480), feature("closed", 10), feature("stop", 10),
                feature("far", 1200), feature("nan", 10));
        List<DemandEntry> demand = List.of(
                generator("counted", 1.5),
                new DemandEntry("closed", "office", 3.0, 1.0, 400.0, false),
                new DemandEntry("stop", "bus_stop", 3.0, 1.0, 400.0, true),
                generator("far", 3.0),
                generator("nan", Double.NaN),
                generator("nowhere", 3.0));

        DemandModel model = DemandModel.of(graph, snapshot(0.0, demand), StopSet.of(graph), features);

        assertEquals(DemandSource.CATCHMENT, model.source());
        assertEquals(1.5, model.totalDemand(), 1e-9);
        assertEquals(1, model.generatorsAssigned());
        assertEquals(2, model.generatorsActive(), "counted and far were placeable and active; far was out of reach");
    }

    @Test
    void generatorsNoStopCanReachHandOverToBuildings() {
        Graph graph = graph(true);

        DemandModel model = DemandModel.of(graph, snapshot(1.0, List.of(generator("far", 2.0))),
                StopSet.of(graph), List.of(feature("far", 5000)));

        assertEquals(DemandSource.BUILDINGS, model.source());
        assertEquals(0.5, model.demandAt(1), 1e-9, "the school is 100 m from stop 1 and inside its 250 m radius");
        assertEquals(0.0, model.demandAt(0), 1e-9);
    }

    @Test
    void anOlderServerWithNoGeneratorsUsesBuildingsWhenTheyFindAnything() {
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
