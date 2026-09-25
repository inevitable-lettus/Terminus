package csc210.algorithm;

import com.fasterxml.jackson.databind.JsonNode;
import csc210.algorithm.cli.Options;
import csc210.algorithm.cli.Report;
import csc210.algorithm.cli.RunInfo;
import csc210.algorithm.cli.UsageException;
import csc210.algorithm.client.RunStartRefusedException;
import csc210.algorithm.client.RunStateException;
import csc210.algorithm.client.SandboxClient;
import csc210.algorithm.client.SandboxHttpException;
import csc210.algorithm.export.RouteExporter;
import csc210.algorithm.routing.RouteBudget;
import csc210.algorithm.routing.RouteNetwork;
import csc210.algorithm.routing.RouteNetworkBuilder;
import csc210.algorithm.serve.ApiServer;
import csc210.algorithm.serve.PlannedCity;
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

    /** Exit statuses, so a script can tell what went wrong without reading the text. */
    static final int OK = 0;
    static final int INTERNAL_ERROR = 1;
    static final int BAD_USAGE = 2;
    static final int SIMULATOR_UNAVAILABLE = 3;
    static final int SIMULATOR_DATA_INVALID = 4;
    static final int RUN_STATE = 5;

    public static void main(String[] args) {
        try {
            System.out.print(run(args, System.err));
        } catch (Throwable failure) {
            int status = exitCodeFor(failure);
            System.err.println(messageFor(failure, args));
            if (status == BAD_USAGE) {
                System.err.println();
                System.err.println("Try: ./bus help");
            } else if (status == INTERNAL_ERROR) {
                if (java.util.Arrays.asList(args).contains("--debug")) {
                    failure.printStackTrace();
                } else {
                    System.err.println("(run again with --debug for the stack trace)");
                }
            }
            System.exit(status);
        }
    }

    /**
     * Which of the five kinds of failure {@code failure} is. Order matters: the
     * specific types are subclasses of the general ones below them.
     */
    static int exitCodeFor(Throwable failure) {
        if (failure instanceof UsageException) {
            return BAD_USAGE;
        }
        if (failure instanceof RunStartRefusedException || failure instanceof RunStateException) {
            return RUN_STATE;
        }
        if (failure instanceof SandboxHttpException answered) {
            return answered.isLifecycleConflict() ? RUN_STATE : SIMULATOR_UNAVAILABLE;
        }
        // Jackson's parse and mapping failures, including a record constructor
        // rejecting a value (a NaN length, say), are IOExceptions too — but the
        // simulator answered, and what it sent is the problem.
        if (failure instanceof com.fasterxml.jackson.core.JsonProcessingException) {
            return SIMULATOR_DATA_INVALID;
        }
        if (failure instanceof IOException) {
            return SIMULATOR_UNAVAILABLE;
        }
        // Graph, CostModel and DemandModel reject inconsistent simulator data
        // with IllegalArgumentException; bad usage was caught above.
        if (failure instanceof IllegalArgumentException) {
            return SIMULATOR_DATA_INVALID;
        }
        return INTERNAL_ERROR;
    }

    /** One line, in words that say what to do next. */
    static String messageFor(Throwable failure, String[] args) {
        String detail = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return switch (exitCodeFor(failure)) {
            case BAD_USAGE, RUN_STATE -> detail;
            case SIMULATOR_UNAVAILABLE -> failure instanceof SandboxHttpException
                    ? "The simulator answered with an error: " + detail
                    : "Could not reach the simulator at " + sandboxUrl(args) + ": " + detail
                            + "\nIs it running? Start it with ./launcher in the sandbox repo.";
            case SIMULATOR_DATA_INVALID -> "The simulator sent data this module cannot use: " + firstLine(detail);
            default -> "Internal error: " + failure.getClass().getSimpleName() + ": " + detail;
        };
    }

    private static String sandboxUrl(String[] args) {
        try {
            return Options.parse(args).sandboxUrl;
        } catch (RuntimeException unparseable) {
            return Options.DEFAULT_SANDBOX;
        }
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline);
    }

    /** The whole program as one call, so a test can drive it without a process. */
    static String run(String[] args, PrintStream notes) throws IOException, InterruptedException {
        Options options = Options.parse(args);
        if (options.mode == Options.Mode.HELP) {
            return help(options);
        }
        if (options.mode == Options.Mode.SERVE) {
            ApiServer server = startServing(options, notes);
            Runtime.getRuntime().addShutdownHook(new Thread(server::close));
            server.awaitClose();
            return "";
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
            if (options.bundle) {
                Path bundle = csc210.algorithm.serve.Bundle.write(PlannedCity.plan(world, run, budget, "bundle"),
                        Path.of(options.outDirectory).resolve("dashboard"));
                notes.println("Dashboard bundle written to " + bundle
                        + " — copy it into dashboard/public/bundle to view it offline.");
            }
        }
        if (options.json && options.mode != Options.Mode.EXPORT) {
            return json(options, PlannedCity.plan(world, run, budget, "live"));
        }
        return render(options, run, world, budget, network, written);
    }

    /**
     * Reads the simulator, plans it, and starts serving — everything
     * {@code ./bus serve} does short of waiting, so a test can drive it and stop it.
     */
    static ApiServer startServing(Options options, PrintStream notes) throws IOException, InterruptedException {
        SandboxClient client = new SandboxClient(options.sandboxUrl).withOperatorToken(resolveToken(options));
        RunInfo run = options.start ? startOwnRun(client, options) : attachToRun(client, options, notes);
        World world = WorldLoader.load(client);
        RouteBudget budget = applyOverrides(world.budget(), options);
        notes.print(Report.header(run, world, budget, options));

        ApiServer.Reloader reloader = time -> {
            if (time != null) {
                client.pausePlayback();
                client.seek(time);
            }
            World fresh = WorldLoader.load(client);
            return PlannedCity.plan(fresh, describeRun(client, true, options.seed),
                    applyOverrides(fresh.budget(), options), "live");
        };
        Path dashboard = Path.of(options.dashboard);
        ApiServer server = ApiServer.start(options.host, options.port,
                PlannedCity.plan(world, run, budget, "live"), reloader, dashboard, options.sandboxUrl);
        String base = "http://" + options.host + ":" + server.port();
        notes.println();
        notes.println("Serving on " + base + "  (Ctrl-C to stop)");
        notes.println(Files.isDirectory(dashboard)
                ? "  dashboard  " + base + "/"
                : "  dashboard  not built — `npm install && npm run dev` in dashboard/, then open http://localhost:5174");
        notes.println("  API        " + base + "/api/network, /api/journey?from=&to=, /api/v1/routes/query");
        return server;
    }

    /** {@code --json}: the command's answer in the serve API's own shapes. */
    private static String json(Options options, PlannedCity city) throws IOException {
        Object answer = switch (options.mode) {
            case NETWORK -> city.networkView();
            case STOPS -> city.worldView().stops();
            case JOURNEY -> city.journeyView(options.from, options.to,
                    Math.round(options.boardWaitMinutes * 60_000.0));
            default -> throw new IllegalStateException("no JSON form for " + options.mode);
        };
        return csc210.algorithm.client.SandboxJson.mapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(answer) + "\n";
    }

    /** Everything after the reads: the text for one command. No I/O, so tests can call it. */
    static String render(Options options, RunInfo run, World world, RouteBudget budget,
                         RouteNetwork network, RouteExporter.Written written) {
        String header = Report.header(run, world, budget, options);
        return switch (options.mode) {
            case NETWORK -> header + "\n" + Report.network(world, network, budget);
            case STOPS -> header + "\n" + Report.stops(world, network, options.top);
            case JOURNEY -> header + "\n" + Report.journey(world, network, options.from, options.to, run.time(),
                    options.boardWaitMinutes);
            case EXPORT -> header + "\n" + Report.exported(written) + "\n" + Report.network(world, network, budget);
            case SERVE, HELP -> Options.usage();
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
                throw new UsageException("cannot read the token file '" + options.tokenFile + "': "
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
            throw new RunStateException(noRun(options, lifecycle));
        }
        if (upper.equals("ERROR") || upper.equals("TERMINATING")) {
            throw new RunStateException("The simulator's run is " + lifecycle + ", so there is no world to read.\n"
                    + "Start a new run in the simulator's own CLI or UI, or pass --start with the operator token.");
        }
        if (upper.equals("STOPPING")) {
            String settled = client.awaitLeaving("STOPPING", ATTACH_TIMEOUT, POLL);
            upper = settled.toUpperCase(Locale.ROOT);
        }
        if (upper.equals("PREPARING") || upper.equals("SEEKING")) {
            client.awaitReady(ATTACH_TIMEOUT, POLL);
            String settled = client.health().lifecycle();
            upper = settled == null ? "" : settled.toUpperCase(Locale.ROOT);
        }

        if (options.timeGiven) {
            // Explicitly asked to look at another moment: pause so the read holds
            // still, then move the clock. Both are open to any client, and a seek
            // brings a STOPPED run back to PAUSED at that moment.
            client.pausePlayback();
            client.seek(options.time);
        } else if (upper.equals("RUNNING")) {
            notes.println("Warning: the run is RUNNING, not PAUSED — its clock is moving while this reads it, "
                    + "so the answer describes a moment that has already passed. Pause it, or pass --time.");
        } else if (upper.equals("STOPPED") || upper.equals("COMPLETED")) {
            notes.println("Note: the run is " + upper + "; this reads the state it was left in. "
                    + "Pass --time to read it at a chosen moment instead.");
        }
        return describeRun(client, true, options.seed);
    }

    /** Starts a run of our own. The token is checked before the current run is touched. */
    private static RunInfo startOwnRun(SandboxClient client, Options options)
            throws IOException, InterruptedException {
        if (!client.hasOperatorToken()) {
            throw new RunStateException(
                    "--start needs the simulator's operator token, and none was given. Set DSTNS_OPERATOR_TOKEN "
                            + "or pass --token-file (the simulator writes its token to logs/operator.token). "
                            + "Nothing has been stopped.");
        }
        // The simulator answers a second start with 409, so a run in progress has
        // to go first. That is exactly why the token is checked above.
        try {
            client.stopPlayback();
        } catch (SandboxHttpException answered) {
            // The simulator answered and objected — typically there was nothing
            // to stop. A failure to reach it at all is not caught: nothing after
            // this could work either.
        }
        client.awaitLeaving("STOPPING", ATTACH_TIMEOUT, POLL);
        try {
            client.startPlayback(options.seed, "00:00:00", 600);
        } catch (RunStartRefusedException refused) {
            throw new RunStartRefusedException(refused.getMessage()
                    + ". The previous run had already been stopped, so start one in the simulator's own CLI or UI.");
        } catch (SandboxHttpException answered) {
            if (answered.isLifecycleConflict()) {
                throw new RunStateException("The simulator would not start a new run (" + answered.getMessage()
                        + "). Another run may still be active; stop it in the simulator's own CLI or UI.");
            }
            throw answered;
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
