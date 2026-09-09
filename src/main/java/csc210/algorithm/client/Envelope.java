package csc210.algorithm.client;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * DSTNS's response wrapper — every {@code /view/*} and {@code /playback/*}
 * endpoint returns one of these, with the actual payload under {@code data}.
 *
 * @param apiVersion always {@code "1.0"}
 * @param runId identifies the current simulation run
 * @param globalSeed the seed the run was started with
 * @param stateRevision increments whenever dynamic state (traffic, weather,
 *        signals) changes
 * @param configRevision increments whenever an operator control changes
 * @param data the endpoint-specific payload, left as a raw {@link JsonNode}
 *        since its shape varies per endpoint
 */
public record Envelope(
        String apiVersion,
        String runId,
        String globalSeed,
        long stateRevision,
        long configRevision,
        JsonNode data
) {}
