package csc210.algorithm.routing;

import csc210.algorithm.cost.CostModel;
import csc210.algorithm.demand.DemandModel;
import csc210.algorithm.graph.Graph;
import csc210.algorithm.search.Dijkstra;
import csc210.algorithm.search.ShortestPathTree;

import java.util.ArrayList;
import java.util.List;

/**
 * <strong>Algorithm 1.</strong> Builds a set of bus lines that between them
 * reach as much of the demand as the budget allows.
 *
 * <p><strong>What problem this is, and why the method is greedy.</strong>
 * Choosing routes to cover demand is the Bus Transit Route Network Design
 * Problem, and it is NP-hard — there is no known way to find the provably best
 * answer without, in the worst case, trying essentially all of them, and the
 * number of candidate networks explodes far faster than the map does. The
 * literature attacks it with genetic algorithms, simulated annealing and tabu
 * search (Fan &amp; Machemehl, 2004). <em>None of those are in this course's
 * toolkit</em>, and reaching for one would mean submitting a black box neither
 * of us could derive. So this is a greedy heuristic built entirely out of the
 * graph, the heap and Dijkstra: at every decision it takes whatever looks best
 * right now and never reconsiders. It is <em>labelled</em> a heuristic and
 * makes no optimality claim — there is no bound here saying the result is
 * within any factor of the best possible network.
 *
 * <p><strong>The shape underneath is set cover.</strong> Each candidate route
 * "covers" a set of stops; we want few enough routes to cover most of the
 * demand. The classic greedy rule for set cover is to repeatedly take the set
 * with the best ratio of newly covered weight to cost, and that is exactly the
 * rule used to extend a route here: demand newly reached, divided by the extra
 * driving time to reach it.
 *
 * <p>The loop, in full:
 * <ol>
 *   <li>Pick the uncovered stop with the highest demand. That is the hub — a
 *       route has to start somewhere, and starting where the passengers are is
 *       the one choice needing no further justification.</li>
 *   <li>Run Dijkstra from the current end of the route. Score every reachable,
 *       still-uncovered stop by (demand newly covered) / (extra driving time),
 *       and extend the route to the best one. Repeat until nothing fits in the
 *       time budget or nothing new would be covered.</li>
 *   <li>Mark every stop the finished route serves as covered, plus every stop
 *       within walking distance of one of them.</li>
 *   <li>Stop when the demand target is met, the route ceiling is hit, or no
 *       uncovered demand is reachable.</li>
 * </ol>
 */
public final class RouteNetworkBuilder {

    private final Graph graph;
    private final CostModel costModel;
    private final DemandModel demandModel;
    private final RouteBudget budget;
    private final Dijkstra dijkstra;

    /** walkNeighbours[stop] = stops within the walking radius of it, itself included. */
    private final int[][] walkNeighbours;

    private final boolean[] isStop;
    private final boolean[] covered;

    public RouteNetworkBuilder(Graph graph, CostModel costModel, DemandModel demandModel, RouteBudget budget) {
        this.graph = graph;
        this.costModel = costModel;
        this.demandModel = demandModel;
        this.budget = budget;
        this.dijkstra = new Dijkstra(graph, costModel);
        this.isStop = new boolean[graph.nodeCount()];
        this.covered = new boolean[graph.nodeCount()];
        for (int stop : demandModel.stopNodes()) {
            isStop[stop] = true;
        }
        this.walkNeighbours = buildWalkNeighbours();
    }

    /** Runs the greedy loop and returns the finished network. */
    public RouteNetwork build() {
        java.util.Arrays.fill(covered, false);
        List<BusRoute> routes = new ArrayList<>();
        double demandCovered = 0.0;
        double totalDemand = demandModel.totalDemand();

        while (routes.size() < budget.maxRoutes()
                && (totalDemand == 0.0 || demandCovered / totalDemand < budget.targetCoverage())) {
            int hub = highestDemandUncoveredStop();
            if (hub < 0) {
                break;
            }

            BusRoute route = growRouteFrom(hub, routes.size());
            if (route == null) {
                // A stop nothing can be built from — an isolated hub, or one
                // whose neighbours all overrun the time budget. Cover it in
                // place rather than looping on it forever.
                demandCovered += coverStopAndNeighbours(hub);
                continue;
            }
            routes.add(route);
            demandCovered += route.demandCovered();
        }

        List<Integer> coveredStops = new ArrayList<>();
        for (int stop : demandModel.stopNodes()) {
            if (covered[stop]) {
                coveredStops.add(stop);
            }
        }
        int[] coveredStopIds = new int[coveredStops.size()];
        for (int i = 0; i < coveredStopIds.length; i++) {
            coveredStopIds[i] = coveredStops.get(i);
        }
        return new RouteNetwork(List.copyOf(routes), coveredStopIds, demandCovered, totalDemand);
    }

    /**
     * Extends one line from {@code hub} until the time budget is spent or
     * nothing worth reaching is left. Returns {@code null} if the line would
     * have been a single stop, which is not a bus route.
     */
    private BusRoute growRouteFrom(int hub, int routeId) {
        List<Integer> nodes = new ArrayList<>();
        List<Integer> edges = new ArrayList<>();
        nodes.add(hub);

        int current = hub;
        long elapsedMillis = 0L;
        double demandCovered = coverStopAndNeighbours(hub);

        while (true) {
            ShortestPathTree tree = dijkstra.from(current);
            int bestTarget = -1;
            double bestScore = 0.0;
            long bestCost = 0L;

            for (int candidate : demandModel.stopNodes()) {
                if (covered[candidate] || !tree.isReachable(candidate) || candidate == current) {
                    continue;
                }
                long extraMillis = tree.costTo(candidate);
                if (extraMillis <= 0 || elapsedMillis + extraMillis > budget.maxRouteMillis()) {
                    continue;
                }

                double gain = demandGainedAlong(tree.pathTo(candidate));
                if (gain <= 0.0) {
                    continue;
                }
                // Greedy set-cover ratio: demand newly reached per extra
                // millisecond of driving. Ties break on the lower stop id so
                // two runs on the same snapshot produce the same network.
                double score = gain / extraMillis;
                if (score > bestScore || (score == bestScore && bestTarget >= 0 && candidate < bestTarget)) {
                    bestScore = score;
                    bestTarget = candidate;
                    bestCost = extraMillis;
                }
            }

            if (bestTarget < 0) {
                break;
            }

            int[] pathNodes = tree.pathTo(bestTarget);
            int[] pathEdges = tree.edgePathTo(bestTarget);
            for (int i = 1; i < pathNodes.length; i++) {
                nodes.add(pathNodes[i]);
                edges.add(pathEdges[i - 1]);
            }
            for (int i = 1; i < pathNodes.length; i++) {
                if (isStop[pathNodes[i]]) {
                    demandCovered += coverStopAndNeighbours(pathNodes[i]);
                }
            }
            elapsedMillis += bestCost;
            current = bestTarget;
        }

        List<Integer> servedStops = new ArrayList<>();
        for (int node : nodes) {
            if (isStop[node] && !servedStops.contains(node)) {
                servedStops.add(node);
            }
        }
        if (servedStops.size() < 2) {
            return null;
        }

        return new BusRoute(routeId, toArray(servedStops), toArray(nodes), toArray(edges),
                elapsedMillis, demandCovered);
    }

    /** Demand that would become newly covered by driving this path, walk-radius included. */
    private double demandGainedAlong(int[] pathNodes) {
        double gain = 0.0;
        List<Integer> newlyCovered = new ArrayList<>();
        for (int i = 1; i < pathNodes.length; i++) {
            int node = pathNodes[i];
            if (!isStop[node] || covered[node]) {
                continue;
            }
            for (int neighbour : walkNeighbours[node]) {
                if (!covered[neighbour] && !newlyCovered.contains(neighbour)) {
                    newlyCovered.add(neighbour);
                    gain += demandModel.demandAt(neighbour);
                }
            }
        }
        return gain;
    }

    /** Marks a served stop and everything within walking distance of it; returns the demand claimed. */
    private double coverStopAndNeighbours(int stop) {
        double claimed = 0.0;
        for (int neighbour : walkNeighbours[stop]) {
            if (!covered[neighbour]) {
                covered[neighbour] = true;
                claimed += demandModel.demandAt(neighbour);
            }
        }
        return claimed;
    }

    private int highestDemandUncoveredStop() {
        int best = -1;
        double bestDemand = -1.0;
        for (int stop : demandModel.stopsByDemandDescending()) {
            if (!covered[stop]) {
                // stopsByDemandDescending is already sorted, so the first
                // uncovered stop is the answer; the comparison is kept only to
                // make that assumption explicit.
                if (demandModel.demandAt(stop) > bestDemand) {
                    best = stop;
                    bestDemand = demandModel.demandAt(stop);
                }
                break;
            }
        }
        return best;
    }

    private int[][] buildWalkNeighbours() {
        int[] stops = demandModel.stopNodes();
        int[][] neighbours = new int[graph.nodeCount()][];
        for (int stop : stops) {
            List<Integer> within = new ArrayList<>();
            for (int other : stops) {
                double metres = DemandModel.straightLineMetres(
                        graph.node(stop).position(), graph.node(other).position());
                if (metres <= budget.walkingRadiusM()) {
                    within.add(other);
                }
            }
            neighbours[stop] = toArray(within);
        }
        for (int id = 0; id < neighbours.length; id++) {
            if (neighbours[id] == null) {
                neighbours[id] = new int[0];
            }
        }
        return neighbours;
    }

    private static int[] toArray(List<Integer> values) {
        int[] array = new int[values.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = values.get(i);
        }
        return array;
    }
}
