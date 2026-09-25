package csc210.algorithm.routing;

import java.util.ArrayList;
import java.util.List;

/**
 * The builder's decisions, recorded in order: a {@link BuildListener} that
 * keeps everything, for replaying the construction step by step.
 */
public final class BuildTrace implements BuildListener {

    /**
     * One decision.
     *
     * @param kind {@code "hub"}, {@code "extend"}, {@code "accept"} or {@code "exclude"}
     * @param routeId the line it concerns, or -1 for an excluded hub
     * @param stop the hub or the extension's target
     * @param path junctions driven for an extension; empty otherwise
     * @param gain demand newly reached by an extension; 0 otherwise
     */
    public record Step(String kind, int routeId, int stop, int[] path, double gain) {}

    private final List<Step> steps = new ArrayList<>();

    @Override
    public void onHub(int routeId, int hub) {
        steps.add(new Step("hub", routeId, hub, new int[0], 0.0));
    }

    @Override
    public void onExtension(int routeId, int target, int[] pathNodes, double gain) {
        steps.add(new Step("extend", routeId, target, pathNodes.clone(), gain));
    }

    @Override
    public void onRouteAccepted(BusRoute route) {
        steps.add(new Step("accept", route.id(), route.stops()[route.stops().length - 1], new int[0],
                route.demandCovered()));
    }

    @Override
    public void onHubExcluded(int hub) {
        steps.add(new Step("exclude", -1, hub, new int[0], 0.0));
    }

    public List<Step> steps() {
        return List.copyOf(steps);
    }
}
