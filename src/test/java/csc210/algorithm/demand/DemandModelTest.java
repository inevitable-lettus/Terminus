package csc210.algorithm.demand;

import csc210.algorithm.Fixtures;
import csc210.algorithm.client.EdgeDetail;
import csc210.algorithm.client.SnapshotData;
import csc210.algorithm.client.TopologyData;
import csc210.algorithm.graph.Graph;
import csc210.algorithm.model.Node;
import csc210.algorithm.model.NodeState;
import csc210.algorithm.model.Position;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step 4.1. The proof the build order asks for: print the demand distribution
 * at a fixed seed and virtual time, and check the busiest stops really do sit
 * near buildings that are active at that hour.
 */
class DemandModelTest {

    private static TopologyData topology;
    private static Graph graph;
    private static SnapshotData snapshot;
    private static DemandModel demand;

    @BeforeAll
    static void load() {
        topology = Fixtures.topology();
        snapshot = Fixtures.snapshotAt0830();
        graph = Graph.of(topology.nodes(), topology.edges());
        demand = DemandModel.of(graph, snapshot);
    }

    @Test
    void scoresEveryStopTheSandboxPlaced() {
        assertEquals(8, demand.stopCount(), "DSTNS places 8 stops on this map");
        for (int stop : demand.stopNodes()) {
            assertTrue(graph.node(stop).busStop());
        }
        assertTrue(demand.totalDemand() > 0.0);
    }

    /**
     * The reason the model does not simply read {@code building_effect} at the
     * stop. If this ever stops being true the simpler model becomes available,
     * so it is asserted rather than left as a comment.
     */
    @Test
    void buildingEffectAtAStopRanksNothing() {
        Map<Double, Integer> counts = new HashMap<>();
        for (int stop : demand.stopNodes()) {
            double effect = snapshot.nodes().get(stop).buildingEffect();
            counts.merge(effect, 1, Integer::sum);
        }
        assertEquals(1, counts.size(),
                "every stop reports the same building_effect, so it cannot rank them: " + counts);
        assertEquals(8, counts.values().iterator().next());
    }

    @Test
    void theBusiestStopsSitNearBuildingsActiveAtThisHour() {
        int[] ranked = demand.stopsByDemandDescending();

        // Demand must be non-increasing down the ranking.
        for (int i = 1; i < ranked.length; i++) {
            assertTrue(demand.demandAt(ranked[i - 1]) >= demand.demandAt(ranked[i]));
        }

        // Every stop with demand has at least one active building in range. At
        // 08:30 on this map those are a school and three offices; stores and
        // malls are still shut, which is worth knowing rather than assuming.
        int top = Math.min(10, ranked.length);
        for (int i = 0; i < top; i++) {
            List<String> nearbyTypes = activeBuildingTypesNear(ranked[i]);
            assertTrue(!nearbyTypes.isEmpty(),
                    "stop " + ranked[i] + " scores " + demand.demandAt(ranked[i]) + " with nothing near it");
        }

        System.out.println("demand at 08:30, seed 0x2026 — top " + top + " stops of "
                + demand.stopCount() + ":");
        for (int i = 0; i < top; i++) {
            System.out.printf("  stop %3d  demand %.3f  near %s%n",
                    ranked[i], demand.demandAt(ranked[i]), activeBuildingTypesNear(ranked[i]));
        }
        System.out.printf("  total demand %.3f, stops with no demand: %d%n",
                demand.totalDemand(), countStopsWithNoDemand());
    }

    /**
     * The half-hourly sweep of the whole virtual day (recorded in the build
     * order) says 18:00 is the busiest hour, not 08:30. The route builder
     * designs for that slice, so check it here rather than trusting the note.
     */
    @Test
    void eighteenHundredIsBusierThanTheMorningPeak() {
        DemandModel evening = DemandModel.of(graph, Fixtures.snapshotAt1800());

        assertTrue(evening.totalDemand() > demand.totalDemand(),
                "18:00 demand " + evening.totalDemand() + " should exceed 08:30's " + demand.totalDemand());
        for (int stop : evening.stopNodes()) {
            assertTrue(evening.demandAt(stop) > 0.0,
                    "every stop should have demand at the true peak; stop " + stop + " has none");
        }
        System.out.printf("total demand: 08:30 %.2f, 18:00 %.2f%n",
                demand.totalDemand(), evening.totalDemand());
    }

    /**
     * Which building types are awake at 08:30, asserted rather than assumed
     * because the whole demand model rests on it.
     *
     * <p>This replaces an earlier check that schools and stores were the two
     * active types and offices were shut. That was true of the synthetic map
     * DSTNS used to serve; on the Berlin extract the mix is different — a
     * school and three offices are open at 08:30, and the two stores are not.
     * The morning peak here is a commute, not the school-and-errands pattern
     * the old map produced.
     */
    @Test
    void schoolsAndOfficesAreTheMorningActivity() {
        Map<String, Integer> activeByType = new HashMap<>();
        for (Node node : topology.nodes()) {
            if (node.building() != null && snapshot.nodes().get(node.id()).buildingEffect() > 0.0) {
                activeByType.merge(node.building(), 1, Integer::sum);
            }
        }
        System.out.println("active buildings at 08:30 by type: " + activeByType);

        assertTrue(activeByType.containsKey("school"));
        assertTrue(activeByType.containsKey("office"));
        assertTrue(!activeByType.containsKey("store"),
                "stores are shut at 08:30 on this map; if that changed, the demand model's "
                        + "morning peak means something different now");
        assertTrue(!activeByType.containsKey("mall"));
    }

    /** The other demand signal the build order asks for: {@code demand_vph} on edges. */
    @Test
    void edgeDemandIsPresentAndVaries() {
        List<EdgeDetail> edges = Fixtures.edgeDetailsAt0830();
        assertEquals(612, edges.size());

        double lowest = Double.MAX_VALUE;
        double highest = 0.0;
        int noDemand = 0;
        for (EdgeDetail edge : edges) {
            double demandVph = edge.traffic().demandVph();
            if (demandVph <= 0.0) {
                // The only roads carrying no traffic at all are the ones that
                // are not roads: DSTNS gives a fabricated one-way reverse no
                // demand, which is a third independent way of spotting them,
                // alongside the synthetic_reverse flag and the route validator
                // refusing them.
                assertTrue(edge.source().syntheticReverse(),
                        "edge " + edge.id() + " is a real road but reports no demand at the morning peak");
                noDemand++;
                continue;
            }
            lowest = Math.min(lowest, demandVph);
            highest = Math.max(highest, demandVph);
        }
        assertEquals(Fixtures.oneWayEdgeIdsAt0830().size(), noDemand,
                "every zero-demand edge should be a fabricated reverse, and every fabricated reverse zero");
        assertTrue(highest > 2 * lowest, "demand should differentiate roads, not sit flat");
        System.out.printf("edge demand_vph at 08:30 spans %.1f to %.1f across %d real roads; "
                + "%d fabricated reverses carry none%n", lowest, highest, edges.size() - noDemand, noDemand);
    }

    /**
     * Regression for the audit's duplicate-id defect: a snapshot with the
     * right length but a repeated node id used to overwrite one building's
     * demand while another silently kept its default zero, instead of being
     * rejected. Mirrors {@code CostModel.of}'s existing dense-id checks.
     */
    @Test
    void duplicateSnapshotNodeIdIsRejected() {
        Graph twoStops = twoStopGraph();
        SnapshotData duplicateFirst = new SnapshotData(
                List.of(new NodeState(0, 1, 0, 0), new NodeState(0, 0, 0, 0)), List.of(), 1);

        assertThrows(IllegalArgumentException.class, () -> DemandModel.of(twoStops, duplicateFirst));
    }

    @Test
    void outOfRangeSnapshotNodeIdIsRejected() {
        Graph twoStops = twoStopGraph();
        SnapshotData negativeId = new SnapshotData(
                List.of(new NodeState(-1, 1, 0, 0), new NodeState(1, 1, 0, 0)), List.of(), 1);
        SnapshotData tooHighId = new SnapshotData(
                List.of(new NodeState(0, 1, 0, 0), new NodeState(2, 1, 0, 0)), List.of(), 1);

        assertThrows(IllegalArgumentException.class, () -> DemandModel.of(twoStops, negativeId));
        assertThrows(IllegalArgumentException.class, () -> DemandModel.of(twoStops, tooHighId));
    }

    @Test
    void shuffledButCompleteSnapshotOrderMatchesInOrder() {
        Graph twoStops = twoStopGraph();
        SnapshotData inOrder = new SnapshotData(
                List.of(new NodeState(0, 1, 0, 0), new NodeState(1, 1, 0, 0)), List.of(), 1);
        SnapshotData shuffled = new SnapshotData(
                List.of(new NodeState(1, 1, 0, 0), new NodeState(0, 1, 0, 0)), List.of(), 1);

        assertEquals(DemandModel.of(twoStops, inOrder).totalDemand(),
                DemandModel.of(twoStops, shuffled).totalDemand());
    }

    /** Two bus-stop-and-building nodes, each only in range of its own building (radius 0). */
    private static Graph twoStopGraph() {
        List<Node> nodes = List.of(twoStopNode(0, 0.0, 3.0), twoStopNode(1, 100.0, 2.0));
        return Graph.of(nodes, List.of());
    }

    private static Node twoStopNode(int id, double xM, double buildingImpact) {
        return new Node(id, 1_000_000L + id, new Position(0.0, 0.0, xM, 0.0),
                0, true, 0.0, false, 0, 0, 0, "store", buildingImpact, 0.0);
    }

    private static List<String> activeBuildingTypesNear(int stopNode) {
        List<String> types = new ArrayList<>();
        for (Node building : topology.nodes()) {
            if (building.building() == null || snapshot.nodes().get(building.id()).buildingEffect() <= 0.0) {
                continue;
            }
            double metres = DemandModel.straightLineMetres(
                    graph.node(stopNode).position(), building.position());
            if (metres <= building.buildingRadiusM()) {
                types.add(building.building());
            }
        }
        return types;
    }

    private static int countStopsWithNoDemand() {
        int count = 0;
        for (int stop : demand.stopNodes()) {
            if (demand.demandAt(stop) == 0.0) {
                count++;
            }
        }
        return count;
    }
}
