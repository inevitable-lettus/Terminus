package csc210.algorithm.demand;

import csc210.algorithm.client.DemandEntry;
import csc210.algorithm.client.Feature;
import csc210.algorithm.graph.Graph;
import csc210.algorithm.model.Position;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Demand at each stop from the trip generators around it — the DSTNS 2.0 source.
 *
 * <p>DSTNS 2.0 models demand on its mapped features: every school, office,
 * shop and similar place carries a live {@code multiplier} and a {@code radius_m}
 * over which it pulls ({@code demand} on the snapshot; positions come from the
 * topology's {@code features}). A stop's demand is the pull of the generators
 * that would use it:
 *
 * <pre>demand(stop) = Σ multiplier(g) over active generators g whose nearest stop is this one,
 *                 within radius(g)</pre>
 *
 * <p><strong>Nearest stop, not every stop in range.</strong> A generator 300 m
 * from three stops is one generator; crediting all three would count its
 * passengers three times and make clustered stops look busier than they are.
 * Giving it to its nearest stop is the catchment a passenger walking to the
 * closest stop would draw, and it keeps total demand equal to the pull that is
 * actually within reach of the network.
 *
 * <p><strong>Why not the simulator's own {@code bus_stop} places.</strong>
 * That was the first 2.0 reading, from source. A live run showed only 11 such
 * places against 81 flagged stops, with one of the 11 anchored on a flagged
 * junction — so 71 of 72 stops scored the same baseline, and the network was
 * designed on no signal at all. Those places are the simulator's own view of
 * stations, not a demand field over the stops this module designs for.
 *
 * <p>Stops themselves ({@code kind "bus_stop"}) are skipped: a stop is where
 * trips are served, not where they are generated.
 */
public final class CatchmentDemand {

    private final double[] demandAtNode;
    private final int activeGenerators;
    private final int assignedGenerators;

    private CatchmentDemand(double[] demandAtNode, int activeGenerators, int assignedGenerators) {
        this.demandAtNode = demandAtNode;
        this.activeGenerators = activeGenerators;
        this.assignedGenerators = assignedGenerators;
    }

    /**
     * Assigns every active generator to its nearest stop within the generator's
     * own radius.
     *
     * @param features the topology's features, which say where each generator is
     * @param demand the snapshot's live demand entries
     */
    public static CatchmentDemand of(Graph graph, StopSet stops, List<Feature> features, List<DemandEntry> demand) {
        Map<String, Position> whereIs = new HashMap<>();
        for (Feature feature : features) {
            if (feature.id() != null && feature.position() != null) {
                whereIs.put(feature.id(), feature.position());
            }
        }

        int[] stopNodes = stops.representatives();
        double[] demandAtNode = new double[graph.nodeCount()];
        int active = 0;
        int assigned = 0;
        for (DemandEntry entry : demand) {
            if (!entry.active() || "bus_stop".equals(entry.kind())) {
                continue;
            }
            double multiplier = entry.multiplier();
            double radius = entry.radiusM();
            if (!Double.isFinite(multiplier) || multiplier <= 0.0 || !Double.isFinite(radius) || radius <= 0.0) {
                continue;
            }
            Position at = whereIs.get(entry.featureId());
            if (at == null) {
                continue;
            }
            active++;

            // Ascending ids with a strict comparison: equidistant stops go to
            // the lower id, so the same snapshot always gives the same answer.
            int nearest = -1;
            double nearestMetres = Double.POSITIVE_INFINITY;
            for (int stop : stopNodes) {
                double metres = DemandModel.straightLineMetres(at, graph.node(stop).position());
                if (metres < nearestMetres) {
                    nearest = stop;
                    nearestMetres = metres;
                }
            }
            if (nearest >= 0 && nearestMetres <= radius) {
                demandAtNode[nearest] += multiplier;
                assigned++;
            }
        }
        return new CatchmentDemand(demandAtNode, active, assigned);
    }

    /** Demand at a stop's representative junction; 0 for anything that is not a stop. */
    public double demandAt(int node) {
        return demandAtNode[node];
    }

    /** Active generators the snapshot and topology could place on the map. */
    public int activeGenerators() {
        return activeGenerators;
    }

    /** How many of them were within reach of some stop, and so counted. */
    public int assignedGenerators() {
        return assignedGenerators;
    }
}
