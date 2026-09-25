package csc210.algorithm.routing;

/**
 * Watches the greedy builder make its choices, one at a time. Every method has
 * an empty default, so a listener implements only what it cares about, and the
 * builder with no listener does exactly what it did before.
 *
 * <p>This is what makes the greedy construction something you can watch rather
 * than take on trust: the dashboard replays it line by line, hub, extension,
 * extension, accepted.
 */
public interface BuildListener {

    /** The builder picked {@code hub}, the busiest uncovered stop, to grow line {@code routeId} from. */
    default void onHub(int routeId, int hub) {}

    /**
     * Line {@code routeId} was extended to {@code target} along {@code pathNodes},
     * newly reaching {@code gain} demand.
     */
    default void onExtension(int routeId, int target, int[] pathNodes, double gain) {}

    /** {@code route} was accepted into the network. */
    default void onRouteAccepted(BusRoute route) {}

    /** Nothing could be built from {@code hub}; it is left uncovered and not tried again. */
    default void onHubExcluded(int hub) {}
}
