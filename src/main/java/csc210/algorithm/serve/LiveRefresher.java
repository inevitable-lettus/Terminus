package csc210.algorithm.serve;

import csc210.algorithm.cli.RunInfo;
import csc210.algorithm.world.World;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Objects;

/**
 * Keeps the served city in step with the simulator, one tick every 2 seconds
 * ({@code ./bus serve} schedules it). Each tick asks which run is going:
 * <ul>
 *   <li>the same run: re-read the speeds and re-price the same network;</li>
 *   <li>a different run: the map may be different too, so plan from scratch.</li>
 * </ul>
 * A failed tick keeps the last good city and says so once, not every 2 seconds.
 */
public final class LiveRefresher implements Runnable {

    public static final long PERIOD_SECONDS = 2;

    /** The reads a tick needs, so a test can stand in for the simulator. */
    public interface Simulator {
        RunInfo currentRun() throws IOException, InterruptedException;

        /** A newer snapshot of {@code previous}'s map, built into a world. */
        World reread(World previous) throws IOException, InterruptedException;

        /** Everything read again and Algorithm 1 run again, for a new run. */
        PlannedCity planFresh(RunInfo run) throws IOException, InterruptedException;
    }

    private final ApiServer server;
    private final Simulator simulator;
    private final PrintStream notes;
    private boolean failing = false;

    public LiveRefresher(ApiServer server, Simulator simulator, PrintStream notes) {
        this.server = server;
        this.simulator = simulator;
        this.notes = notes;
    }

    @Override
    public void run() {
        try {
            PlannedCity city = server.city();
            RunInfo run = simulator.currentRun();
            if (Objects.equals(run.runId(), city.run().runId())) {
                server.replace(city.withLiveCosts(simulator.reread(city.world()), run));
            } else {
                server.replace(simulator.planFresh(run));
                notes.println("New simulator run " + run.runId() + ": re-planned the network.");
            }
            if (failing) {
                notes.println("Live updates are working again.");
            }
            failing = false;
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } catch (Exception problem) {
            // Caught here because one exception would otherwise cancel every later tick.
            if (!failing) {
                notes.println("Live update failed, keeping the last speeds: " + problem.getMessage());
            }
            failing = true;
        }
    }
}
