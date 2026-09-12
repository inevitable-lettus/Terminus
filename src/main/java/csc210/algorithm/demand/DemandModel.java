package csc210.algorithm.demand;

import csc210.algorithm.client.SnapshotData;
import csc210.algorithm.graph.Graph;
import csc210.algorithm.model.Node;
import csc210.algorithm.model.NodeState;
import csc210.algorithm.model.Position;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * How much travel demand sits at each bus stop at one instant of virtual time.
 *
 * <p><strong>Demand here is a proxy, and a synthetic one.</strong> Nobody
 * counted passengers. DSTNS gives each building a static
 * {@code building_impact} (how much traffic it generates when busy), a
 * {@code building_radius_m} (how far that is felt) and a live
 * {@code building_effect} in {@code 0..1} that rises and falls with hardcoded
 * rush-hour windows for its type. A stop's demand is the sum of
 * {@code impact × effect} over every building close enough to reach it:
 *
 * <pre>demand(stop) = Σ over buildings b with distance(stop, b) ≤ radius(b) of impact(b) × effect(b)</pre>
 *
 * <p>That is one step further from reality than counting real points of
 * interest would be, and it should be said out loud rather than dressed up.
 *
 * <p><strong>Why it is not read straight off {@code building_effect} at the
 * stop node, which would be simpler.</strong> Because that number does not
 * describe buildings near the stop. Checked against the 08:30 fixture: all 60
 * stops report {@code building_effect} of exactly 0.4 — the activity level of
 * the *bus-stop role itself*, identical everywhere, which ranks nothing. The
 * per-node field is per-role activity, not a spatial demand field, and using it
 * would have produced 60 equal stops and a route builder picking arbitrarily.
 *
 * <p>Distance is straight-line in projected metres, not driving distance,
 * because it stands in for a passenger walking to a stop rather than a vehicle
 * driving to it.
 */
public final class DemandModel {

    private final int[] stopNodes;
    private final double[] demandAtNode;
    private final double totalDemand;

    private DemandModel(int[] stopNodes, double[] demandAtNode, double totalDemand) {
        this.stopNodes = stopNodes;
        this.demandAtNode = demandAtNode;
        this.totalDemand = totalDemand;
    }

    /**
     * Scores every bus stop DSTNS placed, from the building activity in one snapshot.
     *
     * @throws IllegalArgumentException if the snapshot does not cover every node
     */
    public static DemandModel of(Graph graph, SnapshotData snapshot) {
        int nodeCount = graph.nodeCount();
        if (snapshot.nodes().size() != nodeCount) {
            throw new IllegalArgumentException(
                    "snapshot has " + snapshot.nodes().size() + " node states but the graph has "
                            + nodeCount + " nodes");
        }

        double[] buildingEffect = new double[nodeCount];
        // Matching list length does not establish full id coverage — a
        // duplicate and a missing id can both satisfy it. Validate bounds
        // and uniqueness explicitly, the same dense-id pattern CostModel.of
        // uses for edge states.
        boolean[] seen = new boolean[nodeCount];
        for (NodeState state : snapshot.nodes()) {
            int id = state.id();
            if (id < 0 || id >= nodeCount) {
                throw new IllegalArgumentException("snapshot refers to unknown node id " + id);
            }
            if (seen[id]) {
                throw new IllegalArgumentException("snapshot lists node id " + id + " twice");
            }
            seen[id] = true;
            buildingEffect[id] = state.buildingEffect();
        }

        List<Node> activeBuildings = new ArrayList<>();
        for (int id = 0; id < nodeCount; id++) {
            Node node = graph.node(id);
            if (node.building() != null && buildingEffect[id] > 0.0) {
                activeBuildings.add(node);
            }
        }

        double[] demandAtNode = new double[nodeCount];
        List<Integer> stops = new ArrayList<>();
        double totalDemand = 0.0;

        for (int id = 0; id < nodeCount; id++) {
            Node stop = graph.node(id);
            if (!stop.busStop()) {
                continue;
            }
            stops.add(id);
            double demand = 0.0;
            for (Node building : activeBuildings) {
                // The building's own radius, not the stop's: the sandbox states
                // how far each building's pull reaches, so use its number.
                if (straightLineMetres(stop.position(), building.position()) <= building.buildingRadiusM()) {
                    demand += building.buildingImpact() * buildingEffect[building.id()];
                }
            }
            demandAtNode[id] = demand;
            totalDemand += demand;
        }

        int[] stopNodes = new int[stops.size()];
        for (int i = 0; i < stopNodes.length; i++) {
            stopNodes[i] = stops.get(i);
        }
        return new DemandModel(stopNodes, demandAtNode, totalDemand);
    }

    /** Ids of every node DSTNS flagged as a bus stop, ascending. */
    public int[] stopNodes() {
        return stopNodes.clone();
    }

    public int stopCount() {
        return stopNodes.length;
    }

    /** Demand at {@code nodeId}; zero for anything that is not a bus stop. */
    public double demandAt(int nodeId) {
        return demandAtNode[nodeId];
    }

    /** Demand summed over every stop — the denominator for a coverage percentage. */
    public double totalDemand() {
        return totalDemand;
    }

    /** Stop node ids ordered by demand, busiest first, ties broken by id for determinism. */
    public int[] stopsByDemandDescending() {
        Integer[] boxed = new Integer[stopNodes.length];
        for (int i = 0; i < stopNodes.length; i++) {
            boxed[i] = stopNodes[i];
        }
        Arrays.sort(boxed, (left, right) -> {
            int byDemand = Double.compare(demandAtNode[right], demandAtNode[left]);
            return byDemand != 0 ? byDemand : Integer.compare(left, right);
        });
        int[] ordered = new int[boxed.length];
        for (int i = 0; i < boxed.length; i++) {
            ordered[i] = boxed[i];
        }
        return ordered;
    }

    /** Straight-line distance in metres, in the sandbox's projected coordinates. */
    public static double straightLineMetres(Position from, Position to) {
        double dx = from.xM() - to.xM();
        double dy = from.yM() - to.yM();
        return Math.hypot(dx, dy);
    }
}
