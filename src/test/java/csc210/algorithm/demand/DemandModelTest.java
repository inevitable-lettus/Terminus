package csc210.algorithm.demand;

import csc210.algorithm.Fixtures;
import csc210.algorithm.client.EdgeDetail;
import csc210.algorithm.client.SnapshotData;
import csc210.algorithm.client.TopologyData;
import csc210.algorithm.graph.Graph;
import csc210.algorithm.model.Node;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals(60, demand.stopCount(), "DSTNS places 60 stops on this map");
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
        assertEquals(60, counts.values().iterator().next());
    }

    @Test
    void theBusiestStopsSitNearBuildingsActiveAtThisHour() {
        int[] ranked = demand.stopsByDemandDescending();

        // Demand must be non-increasing down the ranking.
        for (int i = 1; i < ranked.length; i++) {
            assertTrue(demand.demandAt(ranked[i - 1]) >= demand.demandAt(ranked[i]));
        }

        // Every stop with demand has at least one active building in range, and
        // at 08:30 those are schools and stores — offices and malls are still
        // shut, which is worth knowing rather than assuming.
        for (int i = 0; i < 10; i++) {
            List<String> nearbyTypes = activeBuildingTypesNear(ranked[i]);
            assertTrue(!nearbyTypes.isEmpty(),
                    "stop " + ranked[i] + " scores " + demand.demandAt(ranked[i]) + " with nothing near it");
        }

        System.out.println("demand at 08:30, seed 0x2026 — top 10 stops of " + demand.stopCount() + ":");
        for (int i = 0; i < 10; i++) {
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

    @Test
    void onlySchoolsAndStoresAreActiveAt0830() {
        Map<String, Integer> activeByType = new HashMap<>();
        for (Node node : topology.nodes()) {
            if (node.building() != null && snapshot.nodes().get(node.id()).buildingEffect() > 0.0) {
                activeByType.merge(node.building(), 1, Integer::sum);
            }
        }
        System.out.println("active buildings at 08:30 by type: " + activeByType);

        assertTrue(activeByType.containsKey("school"));
        assertTrue(activeByType.containsKey("store"));
        assertTrue(!activeByType.containsKey("office"),
                "offices are shut at 08:30 in this run — the execution plan assumed otherwise");
        assertTrue(!activeByType.containsKey("mall"));
    }

    /** The other demand signal the build order asks for: {@code demand_vph} on edges. */
    @Test
    void edgeDemandIsPresentAndVaries() {
        List<EdgeDetail> edges = Fixtures.edgeDetailsAt0830();
        assertEquals(436, edges.size());

        double lowest = Double.MAX_VALUE;
        double highest = 0.0;
        for (EdgeDetail edge : edges) {
            double demandVph = edge.traffic().demandVph();
            assertTrue(demandVph > 0.0, "edge " + edge.id() + " reports no demand at the morning peak");
            lowest = Math.min(lowest, demandVph);
            highest = Math.max(highest, demandVph);
        }
        assertTrue(highest > 2 * lowest, "demand should differentiate roads, not sit flat");
        System.out.printf("edge demand_vph at 08:30 spans %.1f to %.1f%n", lowest, highest);
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
