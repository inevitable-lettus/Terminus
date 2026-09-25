package csc210.algorithm.journey;

import csc210.algorithm.transit.TransitLink;

import java.util.ArrayList;
import java.util.List;

/**
 * A journey expressed the way a passenger would read it, rather than as the
 * array of graph vertices the search actually returns.
 *
 * <p><strong>Why this is separate from {@link csc210.algorithm.search.TransitPath}.</strong>
 * The search's answer is a sequence of every vertex it passed through, which is
 * the right thing for proving the search correct and the wrong thing for telling
 * somebody how to get to work. Riding six stops along one line is six vertices
 * and six edges to the algorithm, but it is <em>one instruction</em> to a
 * rider: "stay on R2 for six stops". Collapsing that is presentation, not
 * routing, so it lives here and the search stays untouched.
 *
 * @param legs the instructions in order, already collapsed
 * @param totalMillis door-to-door cost; equal to the sum of the legs
 * @param transfers how many times the passenger changes line
 * @param verticesExpanded work the search did, carried through for Phase 6
 * @param found false when no journey exists, in which case {@code legs} is empty
 */
public record Itinerary(
        List<Leg> legs,
        long totalMillis,
        int transfers,
        int verticesExpanded,
        boolean found
) {

    /**
     * One instruction.
     *
     * @param kind what the passenger does
     * @param fromNode road node the leg starts at
     * @param toNode road node it ends at
     * @param routeId the line ridden, or {@link csc210.algorithm.transit.TransitGraph#ON_FOOT}
     *        when this leg is on foot or is a wait
     * @param stopsRidden how many stops are covered, for a {@code RIDE}; zero otherwise
     * @param millis what this leg costs
     */
    public record Leg(TransitLink.Kind kind, int fromNode, int toNode, int routeId, int stopsRidden, long millis) {}

    /** The answer when there is no journey at all. */
    public static Itinerary notFound(int verticesExpanded) {
        return new Itinerary(List.of(), Long.MAX_VALUE, 0, verticesExpanded, false);
    }

    /** The answer when the passenger is already where they wanted to be. */
    public static Itinerary alreadyThere() {
        return new Itinerary(List.of(), 0L, 0, 0, true);
    }

    /**
     * Collapses a searched path into legs. Consecutive rides on one line become
     * a single leg; every other move is its own.
     *
     * @param links the moves the search made, in order
     * @param roadNodeOf maps a transit vertex to the road node it sits at
     */
    static Itinerary of(List<TransitLink> links, long totalMillis, int verticesExpanded,
                        java.util.function.IntUnaryOperator roadNodeOf) {
        List<Leg> legs = new ArrayList<>();
        int transfers = 0;

        for (int i = 0; i < links.size(); ) {
            TransitLink link = links.get(i);
            if (link.isLineChange()) {
                transfers++;
            }
            if (link.kind() != TransitLink.Kind.RIDE) {
                legs.add(new Leg(link.kind(), roadNodeOf.applyAsInt(link.from()), roadNodeOf.applyAsInt(link.to()),
                        link.routeId(), 0, link.millis()));
                i++;
                continue;
            }
            // One ride leg runs as far as the line does without the passenger
            // getting off, however many stops that is.
            int route = link.routeId();
            int fromNode = roadNodeOf.applyAsInt(link.from());
            long millis = 0L;
            int stops = 0;
            int last = link.to();
            while (i < links.size()
                    && links.get(i).kind() == TransitLink.Kind.RIDE
                    && links.get(i).routeId() == route) {
                millis += links.get(i).millis();
                last = links.get(i).to();
                stops++;
                i++;
            }
            legs.add(new Leg(TransitLink.Kind.RIDE, fromNode, roadNodeOf.applyAsInt(last), route, stops, millis));
        }
        return new Itinerary(List.copyOf(legs), totalMillis, transfers, verticesExpanded, true);
    }

    /**
     * The same journey with the boarding wait shown as its own leg. The search
     * priced it into the walk to the first stop; a rider reads "walk, then wait"
     * as two things, so the first access leg is split in two and the total is
     * unchanged.
     */
    Itinerary withBoardWaitSplitOut(long waitMillis) {
        List<Leg> split = new ArrayList<>();
        boolean done = false;
        for (Leg leg : legs) {
            if (!done && leg.kind() == TransitLink.Kind.ACCESS) {
                split.add(new Leg(TransitLink.Kind.ACCESS, leg.fromNode(), leg.toNode(), leg.routeId(), 0,
                        leg.millis() - waitMillis));
                split.add(new Leg(TransitLink.Kind.WAIT, leg.toNode(), leg.toNode(), leg.routeId(), 0, waitMillis));
                done = true;
            } else {
                split.add(leg);
            }
        }
        return new Itinerary(List.copyOf(split), totalMillis, transfers, verticesExpanded, found);
    }

    /** Minutes, rounded to one decimal — how every printed figure in this module is phrased. */
    public double totalMinutes() {
        return totalMillis / 60_000.0;
    }

    /** A plain-language rendering, one line per leg, for the command line. */
    public String describe() {
        if (!found) {
            return "No journey found.";
        }
        if (legs.isEmpty()) {
            return "You are already there.";
        }
        StringBuilder text = new StringBuilder();
        for (Leg leg : legs) {
            double minutes = leg.millis() / 60_000.0;
            String line = switch (leg.kind()) {
                case ACCESS -> String.format("Walk from junction %d to stop %d (%.1f min)",
                        leg.fromNode(), leg.toNode(), minutes);
                case RIDE -> String.format("Ride R%d from stop %d to stop %d, %d stop%s (%.1f min)",
                        leg.routeId(), leg.fromNode(), leg.toNode(), leg.stopsRidden(),
                        leg.stopsRidden() == 1 ? "" : "s", minutes);
                case TRANSFER -> String.format("Change line at stop %d (%.1f min wait)",
                        leg.fromNode(), minutes);
                case WALK -> String.format("Walk from stop %d to stop %d and wait (%.1f min)",
                        leg.fromNode(), leg.toNode(), minutes);
                case EGRESS -> String.format("Walk from stop %d to junction %d (%.1f min)",
                        leg.fromNode(), leg.toNode(), minutes);
                case WALK_DIRECT -> String.format("Walk the whole way, junction %d to junction %d (%.1f min)",
                        leg.fromNode(), leg.toNode(), minutes);
                case WAIT -> String.format("Wait at stop %d for the first bus (%.1f min)", leg.fromNode(), minutes);
            };
            text.append(line).append('\n');
        }
        text.append(String.format("Total %.1f min, %d transfer%s",
                totalMinutes(), transfers, transfers == 1 ? "" : "s"));
        return text.toString();
    }
}
