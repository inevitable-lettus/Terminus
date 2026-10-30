package csc210.algorithm.schedule;

import csc210.algorithm.cost.CostModel;
import csc210.algorithm.graph.Graph;
import csc210.algorithm.routing.BusRoute;

import java.util.List;

/**
 * How long a bus takes to reach each point of its line, at the current speeds.
 * A line direction is a list of junctions ({@code nodes}) and the roads between
 * them ({@code edges}); the time to junction number {@code k} is the sum of the
 * first {@code k} roads.
 */
public final class LiveTimes {

    private final Graph graph;
    private final CostModel costs;

    public LiveTimes(Graph graph, CostModel costs) {
        this.graph = graph;
        this.costs = costs;
    }

    /** One direction of one line: its junctions, roads and stop calls. */
    public record Direction(boolean outbound, int[] nodes, int[] edges, int[] stopOccurrences,
                            int[] stopOccurrencePositions) {}

    public static Direction direction(BusRoute route, boolean outbound) {
        if (outbound) {
            return new Direction(true, route.nodes(), route.edges(), route.stopOccurrences(),
                    route.stopOccurrencePositions());
        }
        BusRoute.Return back = route.back();
        return new Direction(false, back.nodes(), back.edges(), back.stopOccurrences(), back.stopOccurrencePositions());
    }

    /** Milliseconds from the first junction of {@code direction} to junction number {@code position}. */
    public long millisTo(Direction direction, int position) {
        long total = 0L;
        for (int e = 0; e < position; e++) {
            total += roadMillis(direction, e);
        }
        return total;
    }

    /** Milliseconds to drive road number {@code index} of {@code direction}. */
    public long roadMillis(Direction direction, int index) {
        int edge = direction.edges()[index];
        return costs.busMillis(edge, graph.edge(edge).lengthM());
    }

    /**
     * Where a drawn ride ({@code path}, junction by junction) sits on {@code route}:
     * the direction it runs in and the position of its first junction there, or
     * {@code null} if the path is not a stretch of either direction.
     */
    public static Placement place(BusRoute route, List<Integer> path) {
        Placement out = placeIn(direction(route, true), path);
        if (out != null || !route.runsBothWays()) {
            return out;
        }
        return placeIn(direction(route, false), path);
    }

    public record Placement(Direction direction, int start) {}

    private static Placement placeIn(Direction direction, List<Integer> path) {
        int[] nodes = direction.nodes();
        for (int start = 0; start + path.size() <= nodes.length; start++) {
            boolean matches = true;
            for (int i = 0; i < path.size() && matches; i++) {
                matches = nodes[start + i] == path.get(i);
            }
            if (matches) {
                return new Placement(direction, start);
            }
        }
        return null;
    }
}
