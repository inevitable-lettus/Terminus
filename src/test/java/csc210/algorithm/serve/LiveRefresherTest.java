package csc210.algorithm.serve;

import csc210.algorithm.Fixtures;
import csc210.algorithm.cli.RunInfo;
import csc210.algorithm.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/** One tick of the 2-second loop, with a stand-in simulator. */
class LiveRefresherTest {

    private ApiServer server;
    private PlannedCity first;
    private final ByteArrayOutputStream printed = new ByteArrayOutputStream();

    /** A simulator whose run, snapshot and failures the test sets. */
    private static final class FakeSimulator implements LiveRefresher.Simulator {
        RunInfo run;
        World snapshotWorld = Fixtures.v2World0830();
        boolean broken = false;
        int fullPlans = 0;

        @Override
        public RunInfo currentRun() throws IOException {
            if (broken) {
                throw new IOException("simulator down");
            }
            return run;
        }

        @Override
        public World reread(World previous) {
            return snapshotWorld;
        }

        @Override
        public PlannedCity planFresh(RunInfo newRun) {
            fullPlans++;
            World world = Fixtures.v2World0830();
            return PlannedCity.plan(world, newRun, world.budget(), "live");
        }
    }

    @BeforeEach
    void start() throws IOException {
        World world = Fixtures.v2World1800();
        first = PlannedCity.plan(world, new RunInfo("run_a", "0x2026", "18:00:00", true), world.budget(), "live");
        server = ApiServer.start("127.0.0.1", 0, first, null, null, "http://sandbox.test");
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private LiveRefresher refresher(FakeSimulator simulator) {
        return new LiveRefresher(server, simulator, new PrintStream(printed, true));
    }

    @Test
    void sameRunKeepsTheNetworkAndTakesTheNewSpeeds() {
        FakeSimulator simulator = new FakeSimulator();
        simulator.run = new RunInfo("run_a", "0x2026", "18:00:02", true);
        refresher(simulator).run();

        assertSame(first.network(), server.city().network());
        assertSame(simulator.snapshotWorld, server.city().world());
        assertEquals("18:00:02", server.city().run().time());
        assertEquals(0, simulator.fullPlans);
    }

    @Test
    void aNewRunIsPlannedFromScratch() {
        FakeSimulator simulator = new FakeSimulator();
        simulator.run = new RunInfo("run_b", "0x2027", "06:00:00", true);
        refresher(simulator).run();

        assertEquals(1, simulator.fullPlans);
        assertNotSame(first.network(), server.city().network());
        assertEquals("run_b", server.city().run().runId());
    }

    @Test
    void aFailedTickKeepsTheLastCityAndSaysSoOnce() {
        FakeSimulator simulator = new FakeSimulator();
        simulator.broken = true;
        LiveRefresher refresher = refresher(simulator);
        refresher.run();
        refresher.run();

        assertSame(first, server.city());
        assertEquals("Live update failed, keeping the last speeds: simulator down" + System.lineSeparator(),
                printed.toString());

        simulator.broken = false;
        simulator.run = new RunInfo("run_a", "0x2026", "18:00:04", true);
        refresher.run();
        assertEquals("18:00:04", server.city().run().time());
    }
}
