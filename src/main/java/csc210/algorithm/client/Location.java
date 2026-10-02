package csc210.algorithm.client;

/**
 * Which real place a DSTNS 2.0 world was cut from ({@code location} on
 * {@code GET /view/topology}). Older servers send nothing, and this is then
 * {@code null}.
 *
 * @param city the city the seed chose
 * @param country its country
 * @param anchorLat latitude of the district anchor
 * @param anchorLon longitude of the district anchor
 * @param cityExtentM side of the square extract the district was grown in, in metres
 */
public record Location(
        String city,
        String country,
        double anchorLat,
        double anchorLon,
        double cityExtentM
) {}
