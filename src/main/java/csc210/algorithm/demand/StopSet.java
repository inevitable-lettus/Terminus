package csc210.algorithm.demand;

import csc210.algorithm.graph.Graph;
import csc210.algorithm.model.Node;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The bus stops the algorithm designs around: the junctions the simulator flags
 * as stops, with near-duplicates merged into one.
 *
 * <p><strong>Why merge.</strong> OpenStreetMap records a stop per kerb, per
 * platform and per operator, so one place on the ground can arrive as several
 * flagged junctions a few metres apart. Left alone they inflate total demand,
 * and a route would spend real driving time to "serve" both sides of one street.
 * Stops within {@link #MERGE_RADIUS_M} of each other are therefore one stop, the
 * lowest-numbered junction standing for the group. The figure is the simulator's
 * own: DSTNS thins stops on parallel streets only when they are closer than 60 m,
 * because beyond that they are two stops serving two corridors.
 *
 * <p><strong>Greedy and non-transitive, on purpose.</strong> Junctions are taken
 * in ascending id order; each unassigned one becomes a representative and absorbs
 * every still-unassigned flagged junction within the radius <em>of it</em>. A
 * chain of stops each 50 m from the next is therefore not collapsed into one
 * stop hundreds of metres long. The same map always merges the same way.
 *
 * <p>The merged-away junctions are remembered, because the simulator's own
 * per-stop figures are keyed by whichever junction it anchored a stop to, and
 * that may be a duplicate rather than the representative.
 */
public final class StopSet {

    /** Stops closer than this are one stop. */
    public static final double MERGE_RADIUS_M = 60.0;

    private final int[] representatives;
    private final int[] representativeOf;
    private final int rawCount;

    private StopSet(int[] representatives, int[] representativeOf, int rawCount) {
        this.representatives = representatives;
        this.representativeOf = representativeOf;
        this.rawCount = rawCount;
    }

    /** Every flagged junction its own stop, exactly as before merging existed. */
    public static StopSet unclustered(Graph graph) {
        return of(graph, 0.0);
    }

    /** Flagged junctions merged within {@link #MERGE_RADIUS_M}. */
    public static StopSet of(Graph graph) {
        return of(graph, MERGE_RADIUS_M);
    }

    static StopSet of(Graph graph, double mergeRadiusM) {
        int n = graph.nodeCount();
        int[] representativeOf = new int[n];
        Arrays.fill(representativeOf, -1);
        List<Integer> flagged = new ArrayList<>();
        for (int id = 0; id < n; id++) {
            if (graph.node(id).busStop()) {
                flagged.add(id);
            }
        }

        List<Integer> representatives = new ArrayList<>();
        for (int candidate : flagged) {
            if (representativeOf[candidate] >= 0) {
                continue;
            }
            representatives.add(candidate);
            representativeOf[candidate] = candidate;
            Node centre = graph.node(candidate);
            for (int other : flagged) {
                if (representativeOf[other] >= 0) {
                    continue;
                }
                if (DemandModel.straightLineMetres(centre.position(), graph.node(other).position()) <= mergeRadiusM) {
                    representativeOf[other] = candidate;
                }
            }
        }
        int[] reps = new int[representatives.size()];
        for (int i = 0; i < reps.length; i++) {
            reps[i] = representatives.get(i);
        }
        return new StopSet(reps, representativeOf, flagged.size());
    }

    /** The stops, one junction id each, ascending. */
    public int[] representatives() {
        return representatives.clone();
    }

    public int count() {
        return representatives.length;
    }

    /** How many junctions the simulator flagged before merging. */
    public int rawCount() {
        return rawCount;
    }

    /** The stop {@code node} belongs to — itself if it represents one — or -1 if it is not a stop. */
    public int representativeOf(int node) {
        return node < 0 || node >= representativeOf.length ? -1 : representativeOf[node];
    }
}
