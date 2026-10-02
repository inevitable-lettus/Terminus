package csc210.algorithm.journey;

/**
 * One bus stop a passenger could walk to from where they are standing.
 *
 * @param stopNode the road node the stop sits at
 * @param metres straight-line distance to it, in projected metres
 * @param walkMillis how long that walk takes at {@link StopAccessIndex#WALK_SPEED_MPS}
 */
public record StopAccess(int stopNode, double metres, long walkMillis) {}
