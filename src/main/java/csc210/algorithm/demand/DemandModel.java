package csc210.algorithm.demand;

import csc210.algorithm.client.Feature;
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
 * describe buildings near the stop. Checked against the 08:30 fixture: all 8
 * stops report {@code building_effect} of exactly 0.4 — the activity level of
 * the *bus-stop role itself*, identical everywhere, which ranks nothing. The
 * per-node field is per-role activity, not a spatial demand field, and using it
 * would have produced 8 equal stops and a route builder picking arbitrarily.
 * (The same field read 0.4 uniformly across all 60 stops of the map this
 * replaced, so it is a property of the field, not of one map.)
 *
 * <p>Distance is straight-line in projected metres, not driving distance,
 * because it stands in for a passenger walking to a stop rather than a vehicle
 * driving to it.
 */
public final class DemandModel {

    private final int[] stopNodes;
    private final double[] demandAtNode;
    private final double totalDemand;
    private final DemandSource source;
    private final int generatorsAssigned;
    private final int generatorsActive;

    private DemandModel(int[] stopNodes, double[] demandAtNode, double totalDemand, DemandSource source,
                        int generatorsAssigned, int generatorsActive) {
        this.stopNodes = stopNodes;
        this.demandAtNode = demandAtNode;
        this.totalDemand = totalDemand;
        this.source = source;
        this.generatorsAssigned = generatorsAssigned;
        this.generatorsActive = generatorsActive;
    }

    /**
     * Scores every bus stop DSTNS flagged, from the building activity in one
     * snapshot, with no merging and no fallback: the model as it was before
     * DSTNS 2.0, kept for the fixtures that pin it. Prefer
     * {@link #of(Graph, SnapshotData, StopSet, List)} against a live server.
     *
     * @throws IllegalArgumentException if the snapshot does not cover every node
     */
    public static DemandModel of(Graph graph, SnapshotData snapshot) {
        StopSet stops = StopSet.unclustered(graph);
        double[] buildings = buildingDemand(graph, snapshot, stops);
        return assemble(graph, stops, buildings, DemandSource.BUILDINGS);
    }

    /**
     * Scores the given stops from the best source available, in this order:
     *
     * <ol>
     *   <li>{@link DemandSource#CATCHMENT} — the simulator's trip generators,
     *       each credited to its nearest stop, if any was within reach;</li>
     *   <li>{@link DemandSource#BUILDINGS} — our sum over building junctions, if it
     *       found anything at all;</li>
     *   <li>{@link DemandSource#UNIFORM} — every stop equal, so the builder still
     *       has something to design for.</li>
     * </ol>
     *
     * <p>The chain exists because the second source can be silent. It needs
     * buildings tagged on <em>road junctions</em>, and a real city extract puts most
     * shops and schools on separate features, so every stop can score zero. A
     * builder handed all zeros skips every hub and returns no network at all.
     *
     * @param features the topology's features, or {@code null} from a server that
     *        sends none — together with the snapshot's {@code demand} list they
     *        are the catchment source
     * @throws IllegalArgumentException if the snapshot does not cover every node
     */
    public static DemandModel of(Graph graph, SnapshotData snapshot, StopSet stops, List<Feature> features) {
        if (features != null && snapshot.demand() != null) {
            CatchmentDemand catchment = CatchmentDemand.of(graph, stops, features, snapshot.demand());
            double[] scores = new double[graph.nodeCount()];
            for (int stop : stops.representatives()) {
                scores[stop] = catchment.demandAt(stop);
            }
            DemandModel fromCatchment = assemble(graph, stops, scores, DemandSource.CATCHMENT,
                    catchment.assignedGenerators(), catchment.activeGenerators());
            if (fromCatchment.totalDemand() > 0.0) {
                return fromCatchment;
            }
        }
        double[] buildings = buildingDemand(graph, snapshot, stops);
        DemandModel fromBuildings = assemble(graph, stops, buildings, DemandSource.BUILDINGS);
        if (fromBuildings.totalDemand() > 0.0) {
            return fromBuildings;
        }
        double[] uniform = new double[graph.nodeCount()];
        for (int stop : stops.representatives()) {
            uniform[stop] = 1.0;
        }
        return assemble(graph, stops, uniform, DemandSource.UNIFORM);
    }

    private static DemandModel assemble(Graph graph, StopSet stops, double[] demandAtNode, DemandSource source) {
        return assemble(graph, stops, demandAtNode, source, 0, 0);
    }

    private static DemandModel assemble(Graph graph, StopSet stops, double[] demandAtNode, DemandSource source,
                                        int generatorsAssigned, int generatorsActive) {
        int[] stopNodes = stops.representatives();
        double total = 0.0;
        for (int stop : stopNodes) {
            total += demandAtNode[stop];
        }
        return new DemandModel(stopNodes, demandAtNode, total, source, generatorsAssigned, generatorsActive);
    }

    /** Where the demand figures came from. */
    public DemandSource source() {
        return source;
    }

    /** For {@link DemandSource#CATCHMENT}: generators within reach of a stop, and so counted. */
    public int generatorsAssigned() {
        return generatorsAssigned;
    }

    /** For {@link DemandSource#CATCHMENT}: active generators the map could place. */
    public int generatorsActive() {
        return generatorsActive;
    }

    private static double[] buildingDemand(Graph graph, SnapshotData snapshot, StopSet stops) {
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
        for (int id : stops.representatives()) {
            Node stop = graph.node(id);
            double demand = 0.0;
            for (Node building : activeBuildings) {
                // The building's own radius, not the stop's: the sandbox states
                // how far each building's pull reaches, so use its number.
                if (straightLineMetres(stop.position(), building.position()) <= building.buildingRadiusM()) {
                    demand += building.buildingImpact() * buildingEffect[building.id()];
                }
            }
            demandAtNode[id] = demand;
        }
        return demandAtNode;
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
