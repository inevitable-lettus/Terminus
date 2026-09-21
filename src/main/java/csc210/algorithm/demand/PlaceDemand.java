package csc210.algorithm.demand;

import csc210.algorithm.client.Place;

import java.util.List;

/**
 * Per-stop demand read from the simulator's own places (DSTNS 2.0).
 *
 * <p>The simulator already models a stop as following the generators around it —
 * schools, offices, shops — through a coupling it derives itself, and reports the
 * result as {@code demand.multiplier}: 1.0 for an unmodulated stop, up to a cap of
 * 3.0. Reading that is preferable to re-deriving it, because it is the number the
 * simulation actually uses. The catch is honest and worth stating: it is a
 * <em>relative intensity</em>, not a passenger count, so a stop's demand is
 * "how much busier than baseline", and every stop starts from 1.0.
 *
 * <p>A stop's demand is the sum of the multipliers of the {@code bus_stop} places
 * anchored to it or to any junction merged into it, so three kerb-side entries for
 * one real stop add up rather than being lost. A stop with no place attached
 * falls back to {@link #BASELINE}.
 */
public final class PlaceDemand {

    /** What an unmodulated stop draws in the simulator's own model. */
    public static final double BASELINE = 1.0;

    private final double[] demandAtNode;
    private final int matchedStops;

    private PlaceDemand(double[] demandAtNode, int matchedStops) {
        this.demandAtNode = demandAtNode;
        this.matchedStops = matchedStops;
    }

    public static PlaceDemand of(int nodeCount, StopSet stops, List<Place> places) {
        double[] sum = new double[nodeCount];
        boolean[] matched = new boolean[nodeCount];
        for (Place place : places) {
            if (!"bus_stop".equals(place.kind()) || place.demand() == null) {
                continue;
            }
            double multiplier = place.demand().multiplier();
            if (!Double.isFinite(multiplier) || multiplier < 0.0) {
                continue;
            }
            int stop = stops.representativeOf(place.anchorNode());
            if (stop < 0) {
                continue;
            }
            sum[stop] += multiplier;
            matched[stop] = true;
        }

        double[] demandAtNode = new double[nodeCount];
        int matchedStops = 0;
        for (int stop : stops.representatives()) {
            demandAtNode[stop] = matched[stop] ? sum[stop] : BASELINE;
            if (matched[stop]) {
                matchedStops++;
            }
        }
        return new PlaceDemand(demandAtNode, matchedStops);
    }

    /** Demand at a stop's representative junction; 0 for anything that is not a stop. */
    public double demandAt(int stop) {
        return demandAtNode[stop];
    }

    /** How many stops had at least one place attached — zero means this source said nothing. */
    public int matchedStops() {
        return matchedStops;
    }
}
