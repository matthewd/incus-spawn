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
    void listResponseCarriesEachPoolsSharedRoots() throws Exception {
        var line = PoolProtocol.listLine(java.util.List.of(
                new PoolStore.ListedPool("routine", java.nio.file.Path.of("/Users/me/bb"),
                        new java.util.TreeMap<>(java.util.Map.of(
                                "git", java.nio.file.Path.of("/Users/me/references/git"),
                                "common", java.nio.file.Path.of("/Users/me/.common")))),
                new PoolStore.ListedPool("seed", java.nio.file.Path.of("/Users/me/seed"),
                        java.util.Map.of())));

        var json = JSON.readTree(line);
        assertEquals("isx-pool", json.path("protocol").asText());
        assertEquals(1, json.path("version").asInt());
        assertEquals("list", json.path("operation").asText());
        assertTrue(json.path("ok").asBoolean());
        var pools = json.path("pools");
        assertEquals(2, pools.size());
        assertEquals("routine", pools.get(0).path("name").asText());
        assertEquals("/Users/me/bb", pools.get(0).path("workspace_root").asText());
        assertEquals("/Users/me/references/git",
                pools.get(0).path("reference_roots").path("git").asText());
        assertEquals(java.util.List.of("common", "git"), iterable(
                pools.get(0).path("reference_roots").fieldNames()));
        assertEquals("seed", pools.get(1).path("name").asText());
        assertTrue(pools.get(1).path("reference_roots").isObject());
        assertEquals(0, pools.get(1).path("reference_roots").size());
    }

    @Test
    void listMaterializedResponseIdentifiesEachPoolAndItsSeal() throws Exception {
        var identity = "a".repeat(64);
        var generationIdentity = "b".repeat(64);
        var line = PoolProtocol.listMaterializedLine(java.util.List.of(
                new PoolStore.ListedMaterializedPool("job-one", identity, "seed",
                        "g-" + "c".repeat(32), generationIdentity, true)));

        var json = JSON.readTree(line);
        assertEquals("isx-pool", json.path("protocol").asText());
        assertEquals(1, json.path("version").asInt());
        assertEquals("list-materialized", json.path("operation").asText());
        assertTrue(json.path("ok").asBoolean());
        var pool = json.path("pools").get(0);
        assertEquals("job-one", pool.path("name").asText());
        assertEquals(identity, pool.path("identity").asText());
        assertEquals("seed", pool.path("seed_pool").asText());
        assertEquals("g-" + "c".repeat(32), pool.path("generation").asText());
        assertEquals(generationIdentity, pool.path("generation_identity").asText());
        assertTrue(pool.path("running").asBoolean());
        assertFalse(line.contains("direct"), "host paths are not protocol resources");
    }

    @Test
    void deleteResponseCarriesTheDeletedSelection() throws Exception {
        var identity = "a".repeat(64);
        var line = PoolProtocol.deleteLine(
                new PoolStore.DeleteResult(true, "job-one", identity, "seed"));

        var json = JSON.readTree(line);
        assertEquals("delete", json.path("operation").asText());
        assertTrue(json.path("ok").asBoolean());
        assertTrue(json.path("changed").asBoolean());
        assertEquals("job-one", json.path("pool").asText());
        assertEquals(identity, json.path("identity").asText());
        assertEquals("seed", json.path("seed_pool").asText());
    }

    private static java.util.List<String> iterable(java.util.Iterator<String> names) {
        var list = new java.util.ArrayList<String>();
        names.forEachRemaining(list::add);
        return list;
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
