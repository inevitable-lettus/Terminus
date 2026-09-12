package csc210.algorithm;

import csc210.algorithm.client.SandboxClient;
import csc210.algorithm.client.SnapshotData;
import csc210.algorithm.client.TopologyData;
import csc210.algorithm.cost.CostModel;
import csc210.algorithm.demand.DemandModel;
import csc210.algorithm.graph.Graph;
import csc210.algorithm.journey.Itinerary;
import csc210.algorithm.journey.JourneyPlanner;
import csc210.algorithm.publish.RoutePublisher;
import csc210.algorithm.routing.BusRoute;
import csc210.algorithm.routing.RouteBudget;
import csc210.algorithm.routing.RouteNetwork;
import csc210.algorithm.routing.RouteNetworkBuilder;
import csc210.algorithm.transit.TransitGraph;

import java.io.IOException;

/**
 * The command line. Three things this module can do, each runnable with one
 * command and no code:
 *
 * <pre>
 *   network            build the bus network and print it
 *   journey FROM TO    plan one passenger journey between two junctions
 *   publish            send the network to the simulator so it renders there
 * </pre>
 *
 * <p>Options: {@code --time HH:MM:SS} (default 18:00:00, the demand peak the
 * network is designed for), {@code --seed 0xNNNN} (default 0x2026, the seed
 * every figure in the docs was measured at), and {@code --sandbox URL}.
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
            System.err.println("Is it running? Try: docker start dstns_simulation_engine");
            System.exit(1);
        }
    }

    /** The whole program as one call, so a test can drive it without a process. */
    static String run(String[] args) throws IOException, InterruptedException {
        Options options = Options.parse(args);
        SandboxClient client = new SandboxClient(options.sandboxUrl);

        client.startPlayback(options.seed, "00:00:00", 600);
        client.pausePlayback();
        client.seek(options.time);

        TopologyData topology = client.topology();
        SnapshotData snapshot = client.snapshot();
        Graph roadGraph = Graph.of(topology.nodes(), topology.edges());
        CostModel costModel = CostModel.of(roadGraph, snapshot);
        DemandModel demandModel = DemandModel.of(roadGraph, snapshot);
        RouteNetwork network = new RouteNetworkBuilder(roadGraph, costModel, demandModel,
                RouteBudget.defaults()).build();

        return switch (options.mode) {
            case NETWORK -> describeNetwork(network, options);
            case JOURNEY -> describeJourney(roadGraph, costModel, network, options);
            case PUBLISH -> publish(client, roadGraph, network);
        };
    }

    static String describeNetwork(RouteNetwork network, Options options) {
        StringBuilder text = new StringBuilder();
        text.append(String.format("Network at %s, seed %s%n", options.time, options.seed));
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

        return String.format("Junction %d to junction %d at %s%n%s",
                options.from, options.to, options.time, itinerary.describe());
    }

    private static String publish(SandboxClient client, Graph roadGraph, RouteNetwork network)
            throws IOException, InterruptedException {
        int count = new RoutePublisher(roadGraph).publishAll(client, network);
        return String.format("Published %d directed routes (%d lines, both directions).",
                count, network.routeCount());
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

            for (int i = next; i < args.length; i += 2) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("option " + args[i] + " needs a value");
                }
                String value = args[i + 1];
                switch (args[i]) {
                    case "--time" -> options.time = value;
                    case "--seed" -> options.seed = value;
                    case "--sandbox" -> options.sandboxUrl = value;
                    default -> throw new IllegalArgumentException("unknown option '" + args[i] + "'");
                }
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
                  publish            send the network to the simulator

                Options:
                  --time HH:MM:SS    virtual time to design for (default 18:00:00)
                  --seed 0xNNNN      simulator seed (default 0x2026)
                  --sandbox URL      where DSTNS is (default http://127.0.0.1:8090)""";
    }
}
