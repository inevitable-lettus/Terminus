package csc210.algorithm.client;

import csc210.algorithm.model.Position;

/**
 * One mapped feature from the {@code features} list of {@code GET /view/topology}
 * (DSTNS 2.0 and later) — a building, shop, school or stop taken from the real
 * city. Only what the demand model needs is read: where it is.
 *
 * @param id the feature id, matching {@link DemandEntry#featureId()}
 * @param name display name, possibly empty
 * @param category the raw OpenStreetMap category, e.g. {@code "school"}
 * @param position the feature's representative point
 * @param anchorNode the road junction it is attached to, or {@code null} if none
 * @param demandType the simulator's coarse demand class, or {@code null}
 */
public record Feature(
        String id,
        String name,
        String category,
        Position position,
        Integer anchorNode,
        String demandType
) {}
