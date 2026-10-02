package csc210.algorithm.client;

import java.util.List;

/**
 * One page of {@code GET /view/edges?offset&limit}. At 612 edges a single
 * request with {@code limit=1000} returns everything, but {@code limit} caps
 * at 1000 on the server, so callers page anyway rather than assuming today's
 * map size.
 */
public record EdgePage(List<EdgeDetail> items, int offset, int limit, int total) {}
