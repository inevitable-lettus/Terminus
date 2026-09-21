package csc210.algorithm;

import com.fasterxml.jackson.databind.JsonNode;
import csc210.algorithm.cli.Options;
import csc210.algorithm.cli.Report;
import csc210.algorithm.cli.RunInfo;
import csc210.algorithm.client.RunStartRefusedException;
import csc210.algorithm.client.SandboxClient;
import csc210.algorithm.export.RouteExporter;
import csc210.algorithm.routing.RouteBudget;
import csc210.algorithm.routing.RouteNetwork;
import csc210.algorithm.routing.RouteNetworkBuilder;
import csc210.algorithm.world.World;
import csc210.algorithm.world.WorldLoader;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

/**
 * The command line. Run it through {@code ./bus}:
 *
 * <pre>
 *   ./bus network            the bus lines and how well they cover demand
 *   ./bus stops              the busiest stops, and which lines serve them
 *   ./bus journey FROM TO    one passenger journey between two junctions
 *   ./bus export             write the network to out/ as JSON and GeoJSON
 * </pre>
 *
 * <p><strong>It reads the run that is already going.</strong> Since DSTNS 2.0
 * only the simulator's operator may start a run, so the ordinary case is that
 * somebody has started one in the simulator's own CLI or UI and this command is
 * asked what the algorithm makes of it. That leaves the run's clock exactly where
 * it was, which is what makes it possible to set a scene in the simulator and ask
 * about exactly that state. {@code --time} pauses the run and reads it at that
 * moment; {@code --start} (with the operator token) starts a fresh one.
 *
 * <p><strong>Nothing is stopped that cannot be restarted.</strong> Starting our
 * own run means stopping the current one first, because the simulator answers a
 * second start with 409. So the operator token is checked <em>before</em>
 * anything is stopped: without it a refused start would already have killed the
 * run somebody was watching.
 */
public final class Main {

    private static final Duration START_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration ATTACH_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration POLL = Duration.ofSeconds(1);

    private Main() {}

    public static void main(String[] args) {
        try {
            System.out.print(run(args, System.err));
        } catch (IllegalArgumentException badUsage) {
            System.err.println(badUsage.getMessage());
            System.err.println();
            System.err.println("Try: ./bus help");
            System.exit(2);
        } catch (IllegalStateException | RunStartRefusedException cannotProceed) {
            // Not a fault and not bad usage: the situation is one the message explains.
            System.err.println(cannotProceed.getMessage());
            System.exit(1);
        } catch (IOException | InterruptedException failed) {
            // A refused connection arrives as a ConnectException carrying no
            // message at all, so falling back to the type keeps the line useful.
            String reason = failed.getMessage() == null
                    ? failed.getClass().getSimpleName()
                    : failed.getMessage();
            System.err.println("Could not read the simulator: " + reason);
            System.err.println("Is it running? Start it with ./launcher in the sandbox repo.");
            System.exit(1);
        }
    }

    /** The whole program as one call, so a test can drive it without a process. */
    static String run(String[] args, PrintStream notes) throws IOException, InterruptedException {
        Options options = Options.parse(args);
        if (options.mode == Options.Mode.HELP) {
            return help(options);
        }
        if (options.usedPublishAlias) {
            notes.println("Note: `publish` is now `export` — the simulator no longer accepts routes, "
                    + "so the network is written to files instead.");
        }

        SandboxClient client = new SandboxClient(options.sandboxUrl).withOperatorToken(resolveToken(options));
        RunInfo run = options.start ? startOwnRun(client, options) : attachToRun(client, options, notes);

        World world = WorldLoader.load(client);
        RouteBudget budget = applyOverrides(world.budget(), options);
        RouteNetwork network = new RouteNetworkBuilder(world.graph(), world.costModel(),
                world.demandModel(), budget).build();

        if (options.mode == Options.Mode.JOURNEY) {
            // Checked here, before anything is printed, so a bad id is one clear line.
            Report.requireJunction(world.graph(), options.from, "FROM");
            Report.requireJunction(world.graph(), options.to, "TO");
        }
        RouteExporter.Written written = null;
        if (options.mode == Options.Mode.EXPORT) {
            written = new RouteExporter(world.graph(), world.costModel()).write(network,
                    new RouteExporter.Meta(run.seed(), run.time(), world.topology().graphHash(),
                            world.demandModel().source().name(), world.graph().nodeCount(),
                            world.graph().edgeCount(), world.stops().count()),
                    Path.of(options.outDirectory));
        }
        return render(options, run, world, budget, network, written);
    }

    /** Everything after the reads: the text for one command. No I/O, so tests can call it. */
    static String render(Options options, RunInfo run, World world, RouteBudget budget,
                         RouteNetwork network, RouteExporter.Written written) {
        String header = Report.header(run, world, budget, options);
        return switch (options.mode) {
            case NETWORK -> header + "\n" + Report.network(world, network, budget);
            case STOPS -> header + "\n" + Report.stops(world, network, options.top);
            case JOURNEY -> header + "\n" + Report.journey(world, network, options.from, options.to, run.time());
            case EXPORT -> header + "\n" + Report.exported(written) + "\n" + Report.network(world, network, budget);
            case HELP -> Options.usage();
        };
    }

    static RouteBudget applyOverrides(RouteBudget derived, Options options) {
        RouteBudget budget = derived;
        if (options.minutes != null) {
            budget = budget.withMaxRouteMillis(options.minutes * 60_000L);
        }
        if (options.routes != null) {
            budget = budget.withMaxRoutes(options.routes);
        }
        if (options.walkMetres != null) {
            budget = budget.withWalkingRadius(options.walkMetres);
        }
        return budget;
    }

    private static String help(Options options) {
        StringBuilder text = new StringBuilder(Options.usage()).append("\n\n");
        try {
            String lifecycle = new SandboxClient(options.sandboxUrl).health().lifecycle();
            text.append(String.format(Locale.ROOT, "Simulator at %s: %s%n", options.sandboxUrl,
                    lifecycle == null ? "running (lifecycle not reported)" : lifecycle));
        } catch (IOException unreachable) {
            text.append(String.format(Locale.ROOT, "Simulator at %s: not reachable — is it running?%n", options.sandboxUrl));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return text.toString();
    }

    /** {@code --token-file} if given, else the {@code DSTNS_OPERATOR_TOKEN} environment variable, else none. */
    private static String resolveToken(Options options) {
        if (options.tokenFile != null) {
            try {
                return Files.readString(Path.of(options.tokenFile)).strip();
            } catch (IOException unreadable) {
                throw new IllegalArgumentException("cannot read the token file '" + options.tokenFile + "': "
                        + unreadable.getMessage());
            }
        }
        return System.getenv("DSTNS_OPERATOR_TOKEN");
    }

    /**
     * Reads the run that is already going, for the default mode.
     *
     * <p>Attaching to nothing would otherwise fail further down with a confusing
     * error about an empty topology, so the lifecycle is checked here, where the
     * cause is still obvious. The run's own clock and seed are adopted rather than
     * defaults echoed back, so the printed header stays truthful.
     */
    private static RunInfo attachToRun(SandboxClient client, Options options, PrintStream notes)
            throws IOException, InterruptedException {
        String lifecycle = client.health().lifecycle();
        String upper = lifecycle == null ? "" : lifecycle.toUpperCase(Locale.ROOT);
        if (upper.isEmpty() || upper.equals("IDLE") || upper.equals("BOOTING")) {
            throw new IllegalStateException(noRun(options, lifecycle));
        }
        if (upper.equals("PREPARING") || upper.equals("SEEKING")) {
            client.awaitReady(ATTACH_TIMEOUT, POLL);
            String settled = client.health().lifecycle();
            upper = settled == null ? "" : settled.toUpperCase(Locale.ROOT);
        }

        if (options.timeGiven) {
            // Explicitly asked to look at another moment: pause so the read holds
            // still, then move the clock. Both are open to any client.
            client.pausePlayback();
            client.seek(options.time);
        } else if (upper.equals("RUNNING")) {
            notes.println("Warning: the run is RUNNING, not PAUSED — its clock is moving while this reads it, "
                    + "so the answer describes a moment that has already passed. Pause it, or pass --time.");
        }
        return describeRun(client, true, options.seed);
    }

    /** Starts a run of our own. The token is checked before the current run is touched. */
    private static RunInfo startOwnRun(SandboxClient client, Options options)
            throws IOException, InterruptedException {
        if (!client.hasOperatorToken()) {
            throw new IllegalStateException(
                    "--start needs the simulator's operator token, and none was given. Set DSTNS_OPERATOR_TOKEN "
                            + "or pass --token-file (the simulator writes its token to logs/operator.token). "
                            + "Nothing has been stopped.");
        }
        // The simulator answers a second start with 409, so a run in progress has
        // to go first. That is exactly why the token is checked above.
        try {
            client.stopPlayback();
        } catch (IOException nothingWasRunning) {
            // That is the state we wanted anyway.
        }
        try {
            client.startPlayback(options.seed, "00:00:00", 600);
        } catch (RunStartRefusedException refused) {
            throw new RunStartRefusedException(refused.getMessage()
                    + ". The previous run had already been stopped, so start one in the simulator's own CLI or UI.");
        }
        client.awaitReady(START_TIMEOUT, POLL);
        client.pausePlayback();
        client.seek(options.time);
        return describeRun(client, false, options.seed);
    }

    private static RunInfo describeRun(SandboxClient client, boolean attached, String requestedSeed)
            throws IOException, InterruptedException {
        JsonNode status = client.playbackStatus();
        String time = status.path("clock").path("simulated_current_time").asText("unknown");
        String seed = status.path("global_seed").asText(requestedSeed);
        String runId = status.path("run_id").asText(null);
        return new RunInfo(runId, seed, time, attached);
    }

    private static String noRun(Options options, String lifecycle) {
        return "No run is going on " + options.sandboxUrl + " (the simulator is "
                + (lifecycle == null ? "not reporting a lifecycle" : lifecycle) + ").\n"
                + "Start one in the simulator's own CLI or UI, then run this again.\n"
                + "To start one from here: --start, with the operator token in DSTNS_OPERATOR_TOKEN.";
    }
}
