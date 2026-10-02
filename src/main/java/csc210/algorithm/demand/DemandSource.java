package csc210.algorithm.demand;

/**
 * Where a demand figure came from, so a printed result can say which of three
 * quite different things it is standing on.
 */
public enum DemandSource {

    /**
     * The simulator's trip generators (DSTNS 2.0): every active school, office,
     * shop and so on, credited to its nearest stop within its own radius. See
     * {@link CatchmentDemand}.
     */
    CATCHMENT,

    /**
     * Our own sum over building junctions near each stop — the only source before
     * DSTNS 2.0. It needs the map to tag buildings on road junctions, which a real
     * city extract often does not.
     */
    BUILDINGS,

    /**
     * Every stop counts the same. The last resort when neither source produced
     * anything, so the network is designed purely for reach rather than for
     * nothing; results built on it say nothing about where passengers are.
     */
    UNIFORM
}
