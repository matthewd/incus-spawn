package dev.incusspawn.pool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PoolProtocolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void inspectResponseDescribesStoppedSealedSeed() throws Exception {
        var identity = "a".repeat(64);
        var generation = "g-" + "b".repeat(32);
        var line = PoolProtocol.inspectLine(new PoolStore.InspectResult(
                "seed", false, true, generation, identity));

        var json = JSON.readTree(line);
        assertEquals("isx-pool", json.path("protocol").asText());
        assertEquals(1, json.path("version").asInt());
        assertEquals("inspect", json.path("operation").asText());
        assertTrue(json.path("ok").asBoolean());
        assertEquals("seed", json.path("seed_pool").asText());
        assertFalse(json.path("running").asBoolean());
        assertTrue(json.path("sealed").asBoolean());
        assertEquals(generation, json.path("generation").asText());
        assertEquals(identity, json.path("generation_identity").asText());
    }

    @Test
    void materializeResponseCarriesFullSelectionIdentity() throws Exception {
        var identity = "a".repeat(64);
        var generationIdentity = "b".repeat(64);
        var line = PoolProtocol.materializeLine(new PoolStore.MaterializeResult(
                true, "job-one", identity, "seed", "g-" + "c".repeat(32),
                generationIdentity));

        var json = JSON.readTree(line);
        assertEquals("isx-pool", json.path("protocol").asText());
        assertEquals(1, json.path("version").asInt());
        assertTrue(json.path("ok").asBoolean());
        assertEquals(identity, json.path("identity").asText());
        assertEquals(generationIdentity, json.path("generation_identity").asText());
        assertFalse(line.contains("direct"), "host paths are not protocol resources");
    }

    @Test
    void knownFailureRetainsStableCode() throws Exception {
        var line = PoolProtocol.errorLine("seal",
                new PoolStore.PoolException("seed_running", "seed must be stopped"));

        var json = JSON.readTree(line);
        assertFalse(json.path("ok").asBoolean());
        assertEquals("seed_running", json.path("error").path("code").asText());
    }
}
