package dev.incusspawn.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorkerPoolConfigTest {

    @TempDir Path tempDir;

    @Test
    void strictLoadBindsTypedExportsAndRequiredResources() throws Exception {
        var config = writeConfig("""
                worker-pools:
                  compile:
                    cpus: 6
                    memory-mib: 8192
                    swap: 16G
                    runtime-root: ~/isx-runtime
                    workspace-root: /var/tmp/isx-workspaces
                    reference-roots:
                      maven: ~/.m2
                      sources: /opt/sources
                """);

        var pool = SpawnConfig.loadStrict(config).getWorkerPools().get("compile");
        assertEquals(6, pool.getCpus());
        assertEquals(8192, pool.getMemoryMib());
        assertEquals("16G", pool.getSwap());
        assertInstanceOf(WorkerPoolConfig.ReadOnlyExport.class, pool.getRuntimeRoot());
        assertEquals(WorkerPoolConfig.AccessMode.READ_ONLY, pool.getRuntimeRoot().accessMode());
        assertInstanceOf(WorkerPoolConfig.ReadWriteExport.class, pool.getWorkspaceRoot());
        assertEquals(WorkerPoolConfig.AccessMode.READ_WRITE, pool.getWorkspaceRoot().accessMode());
        assertInstanceOf(WorkerPoolConfig.ReadOnlyExport.class,
                pool.getReferenceRoots().get("maven"));
    }

    @Test
    void strictLoadRejectsUnknownPoolFields() throws Exception {
        var config = writeConfig("""
                worker-pools:
                  compile:
                    cpus: 4
                    memory-mib: 4096
                    swap: 8G
                    runtime-root: /runtime
                    workspace-root: /workspace
                    reference-roots: {}
                    typo-memory: 12
                """);

        var error = assertThrows(IllegalStateException.class,
                () -> SpawnConfig.loadStrict(config));
        assertTrue(error.getMessage().contains("typo-memory"));
    }

    @Test
    void strictLoadRejectsMissingAndOutOfRangeResources() throws Exception {
        var missing = writeConfig("""
                worker-pools:
                  compile:
                    cpus: 4
                    swap: 8G
                    runtime-root: /runtime
                    workspace-root: /workspace
                    reference-roots: {}
                """);
        assertTrue(assertThrows(IllegalStateException.class,
                () -> SpawnConfig.loadStrict(missing)).getMessage().contains("memory-mib"));

        var tooSmall = writeConfig("""
                worker-pools:
                  compile:
                    cpus: 0
                    memory-mib: 1024
                    swap: eight
                    runtime-root: /runtime
                    workspace-root: /workspace
                    reference-roots: {}
                """);
        assertTrue(assertThrows(IllegalStateException.class,
                () -> SpawnConfig.loadStrict(tooSmall)).getMessage().contains("cpus"));

        var lowMemory = writeConfig("""
                worker-pools:
                  compile:
                    cpus: 2
                    memory-mib: 1024
                    swap: 8G
                    runtime-root: /runtime
                    workspace-root: /workspace
                    reference-roots: {}
                """);
        assertTrue(assertThrows(IllegalStateException.class,
                () -> SpawnConfig.loadStrict(lowMemory)).getMessage().contains("memory-mib"));

        var invalidSwap = writeConfig("""
                worker-pools:
                  compile:
                    cpus: 2
                    memory-mib: 2048
                    swap: 8GiB
                    runtime-root: /runtime
                    workspace-root: /workspace
                    reference-roots: {}
                """);
        assertTrue(assertThrows(IllegalStateException.class,
                () -> SpawnConfig.loadStrict(invalidSwap)).getMessage().contains("swap"));
    }

    @Test
    void strictLoadRejectsUnsafeNamesAndRelativeRoots() throws Exception {
        var unsafePool = writeConfig("""
                worker-pools:
                  ../escape:
                    cpus: 4
                    memory-mib: 4096
                    swap: 8G
                    runtime-root: /runtime
                    workspace-root: /workspace
                    reference-roots: {}
                """);
        assertTrue(assertThrows(IllegalStateException.class,
                () -> SpawnConfig.loadStrict(unsafePool)).getMessage().contains("unsafe worker pool name"));

        var relativeRoot = writeConfig("""
                worker-pools:
                  compile:
                    cpus: 4
                    memory-mib: 4096
                    swap: 8G
                    runtime-root: relative/path
                    workspace-root: /workspace
                    reference-roots: {}
                """);
        var rootError = assertThrows(IllegalStateException.class,
                () -> SpawnConfig.loadStrict(relativeRoot));
        assertTrue(rootError.getMessage().contains("runtime-root"));

        var unsafeExport = writeConfig("""
                worker-pools:
                  compile:
                    cpus: 4
                    memory-mib: 4096
                    swap: 8G
                    runtime-root: /runtime
                    workspace-root: /workspace
                    reference-roots:
                      Bad_Name: /references
                """);
        var exportError = assertThrows(IllegalStateException.class,
                () -> SpawnConfig.loadStrict(unsafeExport));
        assertTrue(exportError.getMessage().contains("unsafe reference export name"));

        var oversizedExport = writeConfig("""
                worker-pools:
                  compile:
                    cpus: 4
                    memory-mib: 4096
                    swap: 8G
                    runtime-root: /runtime
                    workspace-root: /workspace
                    reference-roots:
                      reference-name-longer-than-tag-limit: /references
                """);
        var oversizedError = assertThrows(IllegalStateException.class,
                () -> SpawnConfig.loadStrict(oversizedExport));
        assertTrue(oversizedError.getMessage().contains("at most 22"));
    }

    @Test
    void strictLoadRejectsDuplicateAndOverlappingNormalizedRoots() throws Exception {
        var config = writeConfig("""
                worker-pools:
                  compile:
                    cpus: 4
                    memory-mib: 4096
                    swap: 8G
                    runtime-root: ~/roots/runtime/../shared
                    workspace-root: ~/roots/shared/work
                    reference-roots: {}
                """);

        var error = assertThrows(IllegalStateException.class,
                () -> SpawnConfig.loadStrict(config));
        assertTrue(error.getMessage().contains("duplicate or overlap"));
    }

    @Test
    void selectedPoolFailsClosedForUnknownPoolAndMalformedConfig() throws Exception {
        var valid = writeConfig("""
                worker-pools:
                  compile:
                    cpus: 4
                    memory-mib: 4096
                    swap: 8G
                    runtime-root: /runtime
                    workspace-root: /workspace
                    reference-roots: {}
                """);
        var unknown = assertThrows(IllegalStateException.class,
                () -> WorkerPoolSelection.select("other", valid));
        assertTrue(unknown.getMessage().contains("not defined"));

        var malformed = writeConfig("worker-pools: [not-a-map]\n");
        assertThrows(IllegalStateException.class,
                () -> WorkerPoolSelection.select("compile", malformed));
        assertThrows(IllegalStateException.class,
                () -> WorkerPoolSelection.select("../escape", valid));
    }

    private Path writeConfig(String yaml) throws Exception {
        var path = tempDir.resolve("config-" + System.nanoTime() + ".yaml");
        Files.writeString(path, yaml);
        return path;
    }
}
