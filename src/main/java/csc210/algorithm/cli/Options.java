package csc210.algorithm.cli;

import java.util.regex.Pattern;

/**
 * The parsed command line: one command and its options, checked as far as it can
 * be without a simulator. Everything here is testable on its own.
 *
 * <p>The commands are deliberately few and take almost nothing, because the common
 * case is "a run is already going, tell me what the algorithm makes of it":
 *
 * <pre>
 *   network            the bus lines and how well they cover demand
 *   stops              the busiest stops, and which lines serve them
 *   journey FROM TO    one passenger journey between two junctions
 *   export             write the network to out/ as JSON and GeoJSON
 * </pre>
 */
public final class Options {

    public enum Mode { NETWORK, STOPS, JOURNEY, EXPORT, SERVE, HELP }

    public static final String DEFAULT_SANDBOX = "http://127.0.0.1:8090";
    public static final String DEFAULT_TIME = "18:00:00";
    public static final String DEFAULT_SEED = "0x2026";

    private static final Pattern CLOCK = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d");

    public final Mode mode;
    public final String sandboxUrl;
    /** The virtual time to read at. Meaningful when {@link #timeGiven}; otherwise the run's own clock is used. */
    public final String time;
    public final boolean timeGiven;
    public final String seed;
    /** Start a run of our own rather than reading the one already going. */
    public final boolean start;
    public final int from;
    public final int to;
    public final String tokenFile;
    /** Budget overrides; {@code null} means "derive it from the map". */
    public final Integer minutes;
    public final Integer routes;
    public final Double walkMetres;
    /** journey: minutes a passenger waits for the first bus; 0 unless asked, since DSTNS publishes no headways. */
    public final double boardWaitMinutes;
    public final String outDirectory;
    public final int top;
    /** True when the deprecated {@code publish} spelling was used. */
    public final boolean usedPublishAlias;
    /** Print the stack trace of an internal error, not just its one-line summary. */
    public final boolean debug;
    /** network, stops, journey: print JSON — the same shapes the serve API sends — instead of text. */
    public final boolean json;
    /** export: also write the dashboard's static bundle to {@code OUT/dashboard/}. */
    public final boolean bundle;
    /** serve: where to listen. */
    public final String host;
    public final int port;
    /** serve: the built dashboard to serve at {@code /}. */
    public final String dashboard;

    private Options(Builder b) {
        this.mode = b.mode;
        this.sandboxUrl = b.sandboxUrl;
        this.time = b.time;
        this.timeGiven = b.timeGiven;
        this.seed = b.seed;
        this.start = b.start;
        this.from = b.from;
        this.to = b.to;
        this.tokenFile = b.tokenFile;
        this.minutes = b.minutes;
        this.routes = b.routes;
        this.walkMetres = b.walkMetres;
        this.boardWaitMinutes = b.boardWaitMinutes;
        this.outDirectory = b.outDirectory;
        this.top = b.top;
        this.usedPublishAlias = b.usedPublishAlias;
        this.debug = b.debug;
        this.json = b.json;
        this.bundle = b.bundle;
        this.host = b.host;
        this.port = b.port;
        this.dashboard = b.dashboard;
    }

    private static final class Builder {
        Mode mode;
        String sandboxUrl = DEFAULT_SANDBOX;
        String time = DEFAULT_TIME;
        boolean timeGiven;
        String seed = DEFAULT_SEED;
        boolean start;
        boolean debug;
        boolean json;
        boolean bundle;
        String host = "127.0.0.1";
        int port = 8080;
        String dashboard = "dashboard/dist";
        int from = -1;
        int to = -1;
        String tokenFile;
        Integer minutes;
        Integer routes;
        Double walkMetres;
        double boardWaitMinutes;
        String outDirectory = "out";
        int top = 15;
        boolean usedPublishAlias;
    }

    /**
     * @throws IllegalArgumentException with a one-line reason the caller can print as it stands
     */
    public static Options parse(String[] args) {
        Builder b = new Builder();
        if (args.length == 0) {
            b.mode = Mode.HELP;
            return new Options(b);
        }

        // Help still reads the options that follow, so `help --sandbox URL` can
        // report on the simulator the user actually means.
        b.mode = isHelp(args[0]) ? Mode.HELP : switch (args[0]) {
            case "network" -> Mode.NETWORK;
            case "stops" -> Mode.STOPS;
            case "journey" -> Mode.JOURNEY;
            case "export" -> Mode.EXPORT;
            case "serve" -> Mode.SERVE;
            case "publish" -> {
                b.usedPublishAlias = true;
                yield Mode.EXPORT;
            }
            default -> throw new UsageException("unknown command '" + args[0] + "'");
        };

        int next = 1;
        if (b.mode == Mode.JOURNEY) {
            if (args.length < 3) {
                throw new UsageException("journey needs a FROM and a TO junction id");
            }
            b.from = junction(args[1]);
            b.to = junction(args[2]);
            next = 3;
        }

        // Not a fixed stride: --attach and --start are flags and take no value.
        for (int i = next; i < args.length; ) {
            String flag = args[i];
            if ("--attach".equals(flag)) {
                i += 1;      // accepted and ignored: attaching is what happens by default
                continue;
            }
            if ("--start".equals(flag)) {
                b.start = true;
                i += 1;
                continue;
            }
            if ("--debug".equals(flag)) {
                b.debug = true;
                i += 1;
                continue;
            }
            if ("--json".equals(flag)) {
                b.json = true;
                i += 1;
                continue;
            }
            if ("--bundle".equals(flag)) {
                b.bundle = true;
                i += 1;
                continue;
            }
            if (i + 1 >= args.length) {
                throw new UsageException("option " + flag + " needs a value");
            }
            String value = args[i + 1];
            switch (flag) {
                case "--time" -> {
                    if (!CLOCK.matcher(value).matches()) {
                        throw new UsageException("--time must look like HH:MM:SS, got '" + value + "'");
                    }
                    b.time = value;
                    b.timeGiven = true;
                }
                case "--seed" -> {
                    if (value.isBlank()) {
                        throw new UsageException("--seed needs a value");
                    }
                    b.seed = value;
                    b.start = true;      // a seed only means something to a run we start
                }
                case "--sandbox" -> b.sandboxUrl = value;
                case "--token-file" -> b.tokenFile = value;
                case "--minutes" -> b.minutes = positiveInt(flag, value);
                case "--routes" -> b.routes = positiveInt(flag, value);
                case "--walk" -> b.walkMetres = nonNegativeDouble(flag, value);
                case "--board-wait" -> b.boardWaitMinutes = nonNegativeDouble(flag, value, "minutes");
                case "--port" -> b.port = port(value);
                case "--host" -> b.host = value;
                case "--dashboard" -> b.dashboard = value;
                case "--out" -> b.outDirectory = value;
                case "--top" -> b.top = positiveInt(flag, value);
                default -> throw new UsageException("unknown option '" + flag + "'");
            }
            i += 2;
        }
        return new Options(b);
    }

    private static boolean isHelp(String word) {
        return "help".equals(word) || "--help".equals(word) || "-h".equals(word);
    }

    private static int junction(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException notANumber) {
            throw new UsageException("'" + text + "' is not a junction id");
        }
    }

    private static int positiveInt(String flag, String text) {
        try {
            int value = Integer.parseInt(text);
            if (value > 0) {
                return value;
            }
        } catch (NumberFormatException notANumber) {
            // fall through to the message below
        }
        throw new UsageException(flag + " must be a whole number above zero, got '" + text + "'");
    }

    private static double nonNegativeDouble(String flag, String text) {
        return nonNegativeDouble(flag, text, "metres");
    }

    private static int port(String text) {
        try {
            int port = Integer.parseInt(text);
            if (port >= 0 && port <= 65535) {
                return port;
            }
        } catch (NumberFormatException notANumber) {
            // fall through
        }
        throw new UsageException("--port must be 0 to 65535, got '" + text + "'");
    }

    private static double nonNegativeDouble(String flag, String text, String unit) {
        try {
            double value = Double.parseDouble(text);
            if (Double.isFinite(value) && value >= 0.0) {
                return value;
            }
        } catch (NumberFormatException notANumber) {
            // fall through to the message below
        }
        throw new UsageException(flag + " must be a number of " + unit + ", zero or more, got '" + text + "'");
    }

    public static String usage() {
        return """
                Usage: ./bus COMMAND [OPTIONS]

                Commands:
                  network            the bus lines, and how well they cover demand
                  stops              the busiest stops, and which lines serve them
                  journey FROM TO    one passenger journey between two junction ids
                  export             write the network to out/ as JSON and GeoJSON
                  serve              the planned city over HTTP, and the dashboard
                  help               this text

                Each command reads the simulation that is already running and leaves
                its clock alone. Start a run in the simulator's own CLI or UI first.

                Options:
                  --time HH:MM:SS    pause the run and read it at this virtual time
                                     (default: read it wherever it is)
                  --sandbox URL      where DSTNS is (default http://127.0.0.1:8090)
                  --minutes N        longest a single line may take, in minutes
                  --routes N         most lines to build
                  --walk METRES      how far a passenger will walk to a stop
                                     (these three default to values derived from the map)
                  --top N            stops: how many to list (default 15)
                  --board-wait MIN   journey: minutes to wait for the first bus
                                     (default 0 — the simulator publishes no timetable)
                  --json             network, stops, journey: print JSON instead of text
                  --bundle           export: also write the dashboard's offline data
                  --port N           serve: where to listen (default 8080)
                  --host ADDR        serve: which address (default 127.0.0.1)
                  --dashboard DIR    serve: the built dashboard (default dashboard/dist)
                  --out DIR          export: where to write (default ./out)

                Starting a run from here (needs the simulator's operator token):
                  --start            stop the current run and start one of our own
                  --seed N           the seed to start with (implies --start)
                  --token-file PATH  read the token from a file; otherwise the
                                     DSTNS_OPERATOR_TOKEN environment variable

                Exit status:
                  0 done   1 internal error (--debug prints the trace)   2 bad usage
                  3 simulator unreachable or failing   4 simulator data unreadable
                  5 the run is in a state this cannot work with""";
    }
}
