package csc210.algorithm.client;

import java.util.List;

/**
 * One page of {@code GET /view/places}. {@code total} counts what matched the
 * {@code kind} filter, not the whole world, and the server silently clamps
 * {@code limit} to 2000 — so callers page rather than trusting the size they
 * asked for.
 */
public record PlacePage(List<Place> items, int total, int offset, int limit) {}
