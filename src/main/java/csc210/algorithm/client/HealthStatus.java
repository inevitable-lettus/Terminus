package csc210.algorithm.client;

/**
 * Response body of {@code GET /health}. Unlike every other endpoint, this one
 * is not wrapped in an {@link Envelope} — it predates the run even starting.
 *
 * @param ok whether the process is up and answering requests
 * @param product what the service calls itself: a short name before DSTNS 2.0, a full title after
 * @param lifecycle the sandbox's current lifecycle state (e.g. "idle", "running")
 */
public record HealthStatus(boolean ok, String product, String lifecycle) {}
