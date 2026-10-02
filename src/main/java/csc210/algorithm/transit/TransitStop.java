package csc210.algorithm.transit;

import csc210.algorithm.model.Position;

/**
 * One vertex of the {@link TransitGraph}: a passenger standing at a particular
 * stop, on a particular line.
 *
 * @param index dense 0-based id within the transit graph
 * @param roadNode the road-network node this stop sits at
 * @param routeId the bus line the passenger is on here
 * @param position the stop's location, carried through so the A* heuristic in
 *        step 5.2 can measure distance without reaching back into the road graph
 */
public record TransitStop(int index, int roadNode, int routeId, Position position) {}
