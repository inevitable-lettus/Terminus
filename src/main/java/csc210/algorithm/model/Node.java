package csc210.algorithm.model;

/**
 * A junction in the DSTNS road network, as returned by {@code GET /view/topology}.
 *
 * @param id dense 0-indexed id assigned by DSTNS after topological sort
 * @param osmNodeId the original OpenStreetMap node id this was compiled from
 * @param position location, in both lat/lon and projected metres
 * @param degree number of edges incident to this node
 * @param busStop whether DSTNS placed a bus stop here
 * @param busStopRadiusM catchment radius of the bus stop, in metres ({@code 0} if none)
 * @param signal whether this junction has a traffic signal
 * @param signalCycleS full signal cycle length, in seconds ({@code 0} if no signal)
 * @param signalGreenS green-phase length within the cycle, in seconds
 * @param signalOffsetS phase offset from the simulation's global clock, in seconds
 * @param building the building type at this node ("school", "office",
 *        "mall", "store"), or {@code null} if there is none
 * @param buildingImpact static magnitude of this building's demand contribution
 * @param buildingRadiusM radius over which the building's demand is felt, in metres
 */
public record Node(
        int id,
        long osmNodeId,
        Position position,
        int degree,
        boolean busStop,
        double busStopRadiusM,
        boolean signal,
        int signalCycleS,
        int signalGreenS,
        int signalOffsetS,
        String building,
        double buildingImpact,
        double buildingRadiusM
) {}
