package csc210.algorithm.model;

/**
 * A junction in the DSTNS road network, as returned by {@code GET /view/topology}.
 *
 * @param id dense 0-indexed id assigned by DSTNS after topological sort
 * @param osmNodeId the original OpenStreetMap node id this was compiled from
 * @param position location, in both lat/lon and projected metres
 * @param degree number of edges incident to this node
 * @param busStop whether DSTNS placed a bus stop here
 * @param signal whether this junction has a traffic signal
 * @param buildingType the building type at this node ("school", "office",
 *        "mall", "store"), or {@code null} if there is none
 */
public record Node(
        int id,
        long osmNodeId,
        Position position,
        int degree,
        boolean busStop,
        boolean signal,
        String buildingType
) {}
