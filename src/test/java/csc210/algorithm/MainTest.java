package csc210.algorithm;

import csc210.algorithm.client.SnapshotData;
import csc210.algorithm.client.TopologyData;
import csc210.algorithm.cost.CostModel;
import csc210.algorithm.demand.DemandModel;
import csc210.algorithm.graph.Graph;
import csc210.algorithm.routing.RouteBudget;
import csc210.algorithm.routing.RouteNetwork;
import csc210.algorithm.routing.RouteNetworkBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase C. The command line's argument handling and its rendering, tested
 * against fixtures — everything except the part that needs a live sandbox.
 */
class MainTest {

    private static Graph roadGraph;
    private static CostModel costModel;
    private static RouteNetwork network;

    @BeforeAll
    static void build() {
        TopologyData topology = Fixtures.topology();
        SnapshotData snapshot = Fixtures.snapshotAt1800();
        roadGraph = Graph.of(topology.nodes(), topology.edges());
        costModel = CostModel.of(roadGraph, snapshot, Fixtures.oneWayEdgeIdsAt1800());
        DemandModel demandModel = DemandModel.of(roadGraph, snapshot);
        network = new RouteNetworkBuilder(roadGraph, costModel, demandModel, RouteBudget.defaults()).build();
    }

    @Test
    void defaultsAreTheValuesTheDesignSettledOn() {
        Main.Options options = Main.Options.parse(new String[]{"network"});
        assertEquals(Main.Mode.NETWORK, options.mode);
        assertEquals("18:00:00", options.time);
        assertEquals("0x2026", options.seed);
        assertEquals("http://127.0.0.1:8090", options.sandboxUrl);
    }

    @Test
    void optionsOverrideTheDefaults() {
        Main.Options options = Main.Options.parse(new String[]{
                "network", "--time", "08:30:00", "--seed", "0x1", "--sandbox", "http://elsewhere:9000"});
        assertEquals("08:30:00", options.time);
        assertEquals("0x1", options.seed);
        assertEquals("http://elsewhere:9000", options.sandboxUrl);
    }

    @Test
    void aJourneyTakesTwoJunctionsAndStillAcceptsOptions() {
        Main.Options options = Main.Options.parse(new String[]{"journey", "0", "119", "--time", "08:30:00"});
        assertEquals(Main.Mode.JOURNEY, options.mode);
        assertEquals(0, options.from);
        assertEquals(119, options.to);
        assertEquals("08:30:00", options.time);
    }

    @Test
    void badUsageIsRejectedWithSomethingReadable() {
        assertThrows(IllegalArgumentException.class, () -> Main.Options.parse(new String[]{}));
        assertThrows(IllegalArgumentException.class, () -> Main.Options.parse(new String[]{"dance"}));
        assertThrows(IllegalArgumentException.class, () -> Main.Options.parse(new String[]{"journey", "0"}));
        assertThrows(IllegalArgumentException.class, () -> Main.Options.parse(new String[]{"journey", "a", "b"}));
        assertThrows(IllegalArgumentException.class,
                () -> Main.Options.parse(new String[]{"network", "--time"}));
        assertThrows(IllegalArgumentException.class,
                () -> Main.Options.parse(new String[]{"network", "--nonsense", "1"}));
    }

    @Test
    void theNetworkPrintsOneLinePerRoutePlusASummary() {
        Main.Options options = Main.Options.parse(new String[]{"network"});
        String text = Main.describeNetwork(network, options);

        assertTrue(text.startsWith("Network at 18:00:00, seed 0x2026"), text);
        for (var route : network.routes()) {
            assertTrue(text.contains(route.label()), "missing " + route.label() + " in:\n" + text);
        }
        assertTrue(text.contains(network.routeCount() + " routes"), text);
    }

    @Test
    void aJourneyPrintsItsLegsAndATotal() {
        Main.Options options = Main.Options.parse(new String[]{"journey", "0", "119"});
        String text = Main.describeJourney(roadGraph, costModel, network, options);

        assertTrue(text.startsWith("Junction 0 to junction 119"), text);
        assertTrue(text.contains("Total "), text);
    }

    @Test
    void aJunctionOffTheMapIsRejectedBeforeAnySearchRuns() {
        Main.Options options = Main.Options.parse(new String[]{"journey", "0", "9999"});
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> Main.describeJourney(roadGraph, costModel, network, options));
        assertTrue(thrown.getMessage().contains("TO must be a junction id"), thrown.getMessage());
    }

    @Test
    void usageNamesAllThreeModes() {
        String usage = Main.usage();
        assertTrue(usage.contains("network"));
        assertTrue(usage.contains("journey"));
        assertTrue(usage.contains("publish"));
    }
}
