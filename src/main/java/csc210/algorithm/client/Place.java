package csc210.algorithm.client;

import csc210.algorithm.model.Position;

/**
 * One classified place from {@code GET /view/places} (DSTNS 2.0 and later): a
 * feature the simulator mapped from the real city — a school, a shop, a bus
 * stop — with the demand its model currently believes it draws.
 *
 * @param id the feature id, e.g. {@code "node/12108766525"}
 * @param name display name, possibly empty
 * @param kind the classifier's kind, e.g. {@code "bus_stop"}
 * @param position where the feature is, in lat/lon and projected metres
 * @param anchorNode the road junction the feature is attached to
 * @param demand what the demand model says about it right now
 */
public record Place(
        String id,
        String name,
        String kind,
        Position position,
        int anchorNode,
        Demand demand
) {

    /**
     * @param multiplier what the place is drawing now, relative to an
     *        unmodulated place at 1.0 and capped by the server at 3.0
     * @param baseline what its own schedule alone asked for, before couplings
     * @param radiusM how far another place's influence on it reaches
     */
    public record Demand(double multiplier, double baseline, double radiusM) {}
}
