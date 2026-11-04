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

    /**
     * The farthest any junction may be from a stop before one is added there:
     * the 400 m access walk, less the 75 m smallest coverage radius. A junction
     * this close to a stop is within the access walk of a stop that is served,
     * or covered by walking to one that is.
     */
    public static final double DEFAULT_MAX_GAP_M = 325.0;

    private final int[] representatives;
    private final int[] representativeOf;
    private final int rawCount;
    private final boolean[] fillIn;
    private final int fillInCount;

    private StopSet(int[] representatives, int[] representativeOf, int rawCount, boolean[] fillIn, int fillInCount) {
        this.representatives = representatives;
        this.representativeOf = representativeOf;
        this.rawCount = rawCount;
        this.fillIn = fillIn;
        this.fillInCount = fillInCount;
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
        return new StopSet(reps, representativeOf, flagged.size(), new boolean[n], 0);
    }

    /**
     * The flagged stops, merged as in {@link #of(Graph)}, plus enough added
     * stops that no junction is more than {@code maxGapM} from one.
     *
     * <p><strong>Why add stops at all.</strong> DSTNS flags the stops of the real
     * city's bus network, and on a sparse extract whole districts have none.
     * No walking radius makes a junction 800 m from the nearest stop reachable
     * by bus: a trip that starts there cannot board, whatever the route builder
     * does. Planning the whole map therefore needs stops in those districts, and
     * the simulator will not supply them.
     *
     * <p><strong>How.</strong> Farthest-point insertion: repeatedly take the
     * junction farthest from every stop so far, and if it is more than
     * {@code maxGapM} away, make it a stop. Each pick is the worst gap left, so
     * the stops added are the fewest this rule gives; ties go to the lower
     * junction id, so the same map always fills the same way. Only junctions
     * {@code canCall} accepts are considered, as candidates and as existing stops
     * that close a gap — callers pass the ones a bus can reach and leave again,
     * since a stop in a one-way pocket would never be served. Straight-line
     * metres, like every other walking distance here.
     *
     * @param maxGapM the largest gap to leave; {@code 0} adds nothing
     * @param canCall whether a line could call at a junction and come back
     * @throws IllegalArgumentException if it is negative or not finite
     */
    public static StopSet coveringMap(Graph graph, double maxGapM, java.util.function.IntPredicate canCall) {
        if (!Double.isFinite(maxGapM) || maxGapM < 0.0) {
            throw new IllegalArgumentException("maxGapM must be finite and non-negative, got " + maxGapM);
        }
        StopSet merged = of(graph);
        if (maxGapM == 0.0 || merged.count() == 0) {
            return merged;
        }

        int n = graph.nodeCount();
        double[] gap = new double[n];
        Arrays.fill(gap, Double.POSITIVE_INFINITY);
        for (int stop : merged.representatives) {
            // A flagged stop in a one-way pocket is a stop on paper only: no line
            // can call there, so it does not close the gap around it.
            if (canCall.test(stop)) {
                narrow(graph, gap, stop);
            }
        }

        int[] representativeOf = merged.representativeOf.clone();
        List<Integer> stops = new ArrayList<>();
        for (int stop : merged.representatives) {
            stops.add(stop);
        }
        boolean[] fillIn = new boolean[n];
        int added = 0;
        while (true) {
            int worst = -1;
            for (int node = 0; node < n; node++) {
                if (representativeOf[node] < 0 && canCall.test(node)
                        && (worst < 0 || gap[node] > gap[worst])) {
                    worst = node;
                }
            }
            if (worst < 0 || !(gap[worst] > maxGapM)) {
                break;
            }
            representativeOf[worst] = worst;
            fillIn[worst] = true;
            stops.add(worst);
            added++;
            narrow(graph, gap, worst);
        }

        int[] reps = stops.stream().mapToInt(Integer::intValue).sorted().toArray();
        return new StopSet(reps, representativeOf, merged.rawCount, fillIn, added);
    }

    private static void narrow(Graph graph, double[] gap, int stop) {
        for (int node = 0; node < gap.length; node++) {
            gap[node] = Math.min(gap[node], DemandModel.straightLineMetres(
                    graph.node(node).position(), graph.node(stop).position()));
        }
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

    /** How many of the stops were added by {@link #coveringMap} rather than flagged by the simulator. */
    public int fillInCount() {
        return fillInCount;
    }

    /** Whether {@code node} is a stop that {@link #coveringMap} added. */
    public boolean isFillIn(int node) {
        return node >= 0 && node < fillIn.length && fillIn[node];
    }

    /** The stop {@code node} belongs to — itself if it represents one — or -1 if it is not a stop. */
    public int representativeOf(int node) {
        return node < 0 || node >= representativeOf.length ? -1 : representativeOf[node];
    }
}
