package csc210.algorithm.client;

/**
 * One trip generator's live demand, from the {@code demand} list of
 * {@code GET /view/snapshot} (DSTNS 2.0 and later): a school, office, shop or
 * similar feature, and how hard it is pulling at this instant of virtual time.
 *
 * <p>The entry says nothing about <em>where</em> the feature is; that comes
 * from the topology's {@link Feature} with the same id.
 *
 * @param featureId the feature this entry describes, e.g. {@code "way/123"}
 * @param kind the classifier's kind, e.g. {@code "office"} or {@code "bus_stop"}
 * @param multiplier what the feature is drawing now, relative to 1.0
 * @param baseline what its own schedule alone asks for, before couplings
 * @param radiusM how far its pull reaches, in metres
 * @param active whether its schedule has it open at this moment
 */
public record DemandEntry(
        String featureId,
        String kind,
        double multiplier,
        double baseline,
        double radiusM,
        boolean active
) {}
