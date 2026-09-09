package csc210.algorithm.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * One {@link ObjectMapper} configuration shared by {@link SandboxClient} and
 * anything deserializing a saved DSTNS fixture. DSTNS's JSON is
 * {@code snake_case}; ours is {@code camelCase}. Unknown fields are
 * tolerated rather than rejected — the contract belongs to DSTNS, and a
 * field it adds later should not break deserialization here.
 */
public final class SandboxJson {

    private SandboxJson() {}

    public static ObjectMapper mapper() {
        return new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
}
