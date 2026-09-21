package csc210.algorithm;

import csc210.algorithm.client.EdgeDetail;
import csc210.algorithm.client.SandboxClient;
import csc210.algorithm.client.SnapshotData;
import csc210.algorithm.client.TopologyData;
import csc210.algorithm.cost.CostModel;
import csc210.algorithm.demand.DemandModel;
import csc210.algorithm.graph.Graph;
import csc210.algorithm.journey.Itinerary;
import csc210.algorithm.journey.JourneyPlanner;
import csc210.algorithm.export.RouteExporter;
import csc210.algorithm.routing.BusRoute;
import csc210.algorithm.routing.RouteBudget;
import csc210.algorithm.routing.RouteNetwork;
import csc210.algorithm.routing.RouteNetworkBuilder;
import csc210.algorithm.transit.TransitGraph;

import java.io.IOException;
import java.util.List;

/**
 * The command line. Three things this module can do, each runnable with one
 * command and no code:
 *
 * <pre>
 *   network            build the bus network and print it
 *   journey FROM TO    plan one passenger journey between two junctions
 *   publish            write the network to out/ (the simulator no longer accepts routes)
 * </pre>
 *
 * <p>Options: {@code --time HH:MM:SS} (default 18:00:00, the demand peak the
 * network is designed for), {@code --seed 0xNNNN} (default 0x2026, the seed
 * every figure in the docs was measured at), {@code --sandbox URL}, and
 * {@code --attach}.
 *
 * <p><strong>Starting a run versus attaching to one.</strong> By default each
 * command starts its own run at a known seed, pauses it and seeks to the time
 * asked for, so the answer is reproducible from the command line alone. That
 * also resets the simulator out from under anyone watching its UI, and DSTNS
 * refuses a second start with {@code 409} while a run is live, so the default
 * stops the previous run first. {@code --attach} does the opposite: it touches
 * the clock not at all and reads whatever run is already going, which is what
 * makes it possible to drive the simulator by hand and then ask this module
 * about exactly the state on screen.
 *
 * <p>All three modes talk to a running DSTNS. That is deliberate — the point of
 * an entry point is to run against the real thing — and it means none of them
 * work with the simulator stopped. The test suite is where the offline proofs
 * live.
 */
public final class Main {

    private static final String DEFAULT_SANDBOX = "http://127.0.0.1:8090";
    private static final String DEFAULT_TIME = "18:00:00";
    private static final String DEFAULT_SEED = "0x2026";

    private Main() {}

    public static void main(String[] args) {
        try {
            System.out.println(run(args));
        } catch (IllegalArgumentException badUsage) {
            System.err.println(badUsage.getMessage());
            System.err.println();
            System.err.println(usage());
            System.exit(2);
        } catch (IOException | InterruptedException failed) {
            // A refused connection arrives as a ConnectException carrying no
            // message at all, so falling back to the type keeps the line useful.
            String reason = failed.getMessage() == null
                    ? failed.getClass().getSimpleName()
                    : failed.getMessage();
            System.err.println("Could not reach the sandbox: " + reason);
            System.err.println("Is it running? Try: docker start dstns_engine_5f75fa5");
            System.exit(1);
        }
    }

    /** The whole program as one call, so a test can drive it without a process. */
    static String run(String[] args) throws IOException, InterruptedException {
        Options options = Options.parse(args);
        SandboxClient client = new SandboxClient(options.sandboxUrl);

        if (options.attach) {
            adoptLiveRun(client, options);
        } else {
            // DSTNS answers 409 rather than restarting when a run is already
            // active, and one usually is — the previous invocation left it that
            // way. Stop first, so the seed is this command's rather than
            // whatever happened to be inherited.
            try {
                client.stopPlayback();
            } catch (IOException nothingWasRunning) {
                // That is the state we wanted anyway.
            }
            client.startPlayback(options.seed, "00:00:00", 600);
            client.pausePlayback();
            client.seek(options.time);
        }

        TopologyData topology = client.topology();
        SnapshotData snapshot = client.snapshot();
        Graph roadGraph = Graph.of(topology.nodes(), topology.edges());
        // One-way streets: /view/topology cannot express them, /view/edges can.
        // Without this exclusion the search drives the wrong way down 139 of
        // this map's 612 edges, and the simulator rejects the routes it builds.
        CostModel costModel = CostModel.of(roadGraph, snapshot,
                EdgeDetail.syntheticReverseIds(client.edges()));
        DemandModel demandModel = DemandModel.of(roadGraph, snapshot);
        RouteNetwork network = new RouteNetworkBuilder(roadGraph, costModel, demandModel,
                RouteBudget.defaults()).build();

        return switch (options.mode) {
            case NETWORK -> describeNetwork(network, options);
            case JOURNEY -> describeJourney(roadGraph, costModel, network, options);
            case PUBLISH -> export(roadGraph, costModel, network, topology, options, demandModel.stopCount());
        };
    }

    static String describeNetwork(RouteNetwork network, Options options) {
        StringBuilder text = new StringBuilder();
        text.append(String.format("Network at %s, seed %s%s%n",
                options.time, options.seed, options.attachedTo()));
        for (BusRoute route : network.routes()) {
            text.append(String.format("  %-4s %3d stops, %5.1f min end to end, demand %.2f%n",
                    route.label(), route.stopCount(), route.travelMillis() / 60_000.0, route.demandCovered()));
        }
        text.append(String.format("%d routes, covering %.1f%% of %.2f total demand",
                network.routeCount(), network.coverage() * 100.0, network.totalDemand()));
        return text.toString();
    }

    static String describeJourney(Graph roadGraph, CostModel costModel, RouteNetwork network, Options options) {
        requireJunction(roadGraph, options.from, "FROM");
        requireJunction(roadGraph, options.to, "TO");

        TransitGraph transit = TransitGraph.of(roadGraph, network, costModel);
        JourneyPlanner planner = JourneyPlanner.of(roadGraph, transit);
        Itinerary itinerary = planner.plan(options.from, options.to);

        return String.format("Junction %d to junction %d at %s%s%n%s",
                options.from, options.to, options.time, options.attachedTo(), itinerary.describe());
    }

    private static String export(Graph roadGraph, CostModel costModel, RouteNetwork network,
                                 TopologyData topology, Options options, int stops) throws IOException {
        RouteExporter exporter = new RouteExporter(roadGraph, costModel);
        RouteExporter.Written written = exporter.write(network,
                new RouteExporter.Meta(options.seed, options.time, topology.graphHash(), null,
                        roadGraph.nodeCount(), roadGraph.edgeCount(), stops),
                java.nio.file.Path.of("out"));
        String oneWay = written.oneWayLines().isEmpty()
                ? ""
                : " " + String.join(", ", written.oneWayLines()) + " cannot be driven back (one-way streets).";
        return String.format("Wrote %d routes to %s and %s.%s",
                written.routes(), written.json(), written.geojson(), oneWay);
    }

    /**
     * Reads whatever run is already live, for {@code --attach}, and takes its
     * clock and seed as this command's own.
     *
     * <p>Attaching means the caller is not choosing the time or the seed — the
     * running simulation already did. Overwriting {@code options} with what was
     * actually found keeps the printed header truthful rather than echoing
     * defaults the command never applied.
     *
     * <p>Attaching to nothing would otherwise fail further down with a
     * confusing error about an empty topology, so the lifecycle is checked
     * here, where the cause is still obvious.
     */
    private static void adoptLiveRun(SandboxClient client, Options options)
            throws IOException, InterruptedException {
        String lifecycle = client.health().lifecycle();
        if (lifecycle == null || "IDLE".equalsIgnoreCase(lifecycle)) {
            throw new IllegalArgumentException(
                    "--attach needs a run that is already going, but the sandbox is "
                            + (lifecycle == null ? "not reporting a lifecycle" : lifecycle)
                            + ". Start one in the UI, or drop --attach to have this command start its own.");
        }

        var status = client.playbackStatus();
        options.time = status.path("clock").path("simulated_current_time").asText(options.time);
        options.seed = status.path("global_seed").asText(options.seed);
        options.runId = status.path("run_id").asText(null);
        if (!"PAUSED".equalsIgnoreCase(lifecycle)) {
            System.err.println("Warning: the run is " + lifecycle + ", not PAUSED — the clock is moving "
                    + "while this reads it, so the answer describes a moment that has already passed.");
        }
    }

    private static void requireJunction(Graph roadGraph, int node, String which) {
        if (node < 0 || node >= roadGraph.nodeCount()) {
            throw new IllegalArgumentException(which + " must be a junction id in [0, "
                    + roadGraph.nodeCount() + "), got " + node);
        }
    }

    enum Mode { NETWORK, JOURNEY, PUBLISH }

    /** Parsed command line. Package-private so the parsing can be tested on its own. */
    static final class Options {
        Mode mode;
        String sandboxUrl = DEFAULT_SANDBOX;
        String time = DEFAULT_TIME;
        String seed = DEFAULT_SEED;
        int from = -1;
        int to = -1;
        /** Read the run that is already going instead of starting a fresh one. */
        boolean attach = false;
        /** The attached run's id, for the printed header. Null unless attached. */
        String runId = null;

        /** {@code " (attached to run_xxx)"}, or nothing when this command started its own run. */
        String attachedTo() {
            return runId == null ? "" : " (attached to " + runId + ")";
        }

        static Options parse(String[] args) {
            if (args.length == 0) {
                throw new IllegalArgumentException("no mode given");
            }
            Options options = new Options();
            options.mode = switch (args[0]) {
                case "network" -> Mode.NETWORK;
                case "journey" -> Mode.JOURNEY;
                case "publish" -> Mode.PUBLISH;
                default -> throw new IllegalArgumentException("unknown mode '" + args[0] + "'");
            };

            int next = 1;
            if (options.mode == Mode.JOURNEY) {
                if (args.length < 3) {
                    throw new IllegalArgumentException("journey needs a FROM and a TO junction id");
                }
                options.from = parseJunction(args[1]);
                options.to = parseJunction(args[2]);
                next = 3;
            }

            // Not a fixed stride: --attach is a flag and takes no value, so the
            // step depends on which option was just read.
            boolean explicitTime = false;
            boolean explicitSeed = false;
            int i = next;
            while (i < args.length) {
                String flag = args[i];
                if ("--attach".equals(flag)) {
                    options.attach = true;
                    i += 1;
                    continue;
                }
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("option " + flag + " needs a value");
                }
                String value = args[i + 1];
                switch (flag) {
                    case "--time" -> { options.time = value; explicitTime = true; }
                    case "--seed" -> { options.seed = value; explicitSeed = true; }
                    case "--sandbox" -> options.sandboxUrl = value;
                    default -> throw new IllegalArgumentException("unknown option '" + flag + "'");
                }
                i += 2;
            }
            if (options.attach && (explicitTime || explicitSeed)) {
                throw new IllegalArgumentException(
                        "--attach reads the running simulation's own time and seed, so it cannot be "
                                + "combined with --time or --seed. Drop --attach to choose them.");
            }
            return options;
        }

        private static int parseJunction(String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException notANumber) {
                throw new IllegalArgumentException("'" + text + "' is not a junction id");
            }
        }
    }

    static String usage() {
        return """
                Usage:
                  network            build the bus network and print it
                  journey FROM TO    plan one journey between two junction ids
                  publish            write the network to out/ as JSON and GeoJSON

                Options:
                  --time HH:MM:SS    virtual time to design for (default 18:00:00)
                  --seed 0xNNNN      simulator seed (default 0x2026)
                  --sandbox URL      where DSTNS is (default http://127.0.0.1:8090)
                  --attach           read the run already going instead of starting
                                     a fresh one; takes its time and seed, and
                                     leaves the simulator's clock where it is""";
    }
}
