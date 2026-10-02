package csc210.algorithm.serve;

import com.fasterxml.jackson.databind.ObjectMapper;
import csc210.algorithm.client.SandboxJson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The dashboard's offline data: the same JSON {@link ApiServer} sends, written
 * to files, so the dashboard can be opened with no simulator and no server —
 * on a laptop in a lecture hall, say. {@code ./bus export --bundle} writes it.
 */
public final class Bundle {

    /** How many worked journeys, with both searches' traces, the bundle carries. */
    public static final int SAMPLE_JOURNEYS = 8;

    private Bundle() {}

    /** Writes {@code world.json}, {@code network.json}, {@code build.json} and {@code journeys.json}. */
    public static Path write(PlannedCity city, Path directory) throws IOException {
        ObjectMapper mapper = SandboxJson.mapper();
        Files.createDirectories(directory);
        mapper.writeValue(directory.resolve("world.json").toFile(), city.worldView());
        mapper.writeValue(directory.resolve("network.json").toFile(), city.networkView());
        mapper.writeValue(directory.resolve("build.json").toFile(), city.buildTraceView());
        mapper.writeValue(directory.resolve("journeys.json").toFile(), city.sampleJourneys(SAMPLE_JOURNEYS));
        return directory;
    }
}
