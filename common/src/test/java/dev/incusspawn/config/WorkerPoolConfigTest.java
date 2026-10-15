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

        var pool = WorkerPoolsFile.loadStrict(config).get("compile");
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
    void directResourcesOverrideCurrentPoolDefaultsByCanonicalRoot() throws Exception {
        var largeProject = tempDir.resolve("large-project");
        var cpuProject = tempDir.resolve("cpu-project");
        var tildeProject = tempDir.resolve("tilde-project");
        var config = writeConfig("""
                compile:
                  cpus: 4
                  memory-mib: 4096
                  swap: 8G
                  runtime-root: /runtime
                  workspace-root: /workspace
                  reference-roots: {}
                  direct-resources:
                    %s:
                      cpus: 10
                      memory-mib: 24576
                    %s:
                      cpus: 6
                    ~/tilde-project:
                      memory-mib: 12288
                """.formatted(largeProject, cpuProject));

        var pool = WorkerPoolsFile.loadStrict(config).get("compile")
                .validateAndFreeze("compile", tempDir);

        assertEquals(new WorkerPoolConfig.LaunchResources(10, 24576),
                pool.resourcesForDirectRoot(largeProject.toString()));
        assertEquals(new WorkerPoolConfig.LaunchResources(6, 4096),
                pool.resourcesForDirectRoot(cpuProject.toString()));
        assertEquals(new WorkerPoolConfig.LaunchResources(4, 12288),
                pool.resourcesForDirectRoot(tildeProject.toString()));
        assertEquals(new WorkerPoolConfig.LaunchResources(4, 4096),
                pool.resourcesForDirectRoot(tempDir.resolve("ordinary-project").toString()));
    }

    @Test
    void directResourcesRejectInvalidOrEmptyOverrides() throws Exception {
        var empty = writeConfig("""
                compile:
                  cpus: 4
                  memory-mib: 4096
                  swap: 8G
                  runtime-root: /runtime
                  workspace-root: /workspace
                  reference-roots: {}
                  direct-resources:
                    /project: {}
                """);
        assertTrue(assertThrows(IllegalStateException.class,
                () -> WorkerPoolsFile.loadStrict(empty)).getMessage().contains("must set"));

        var invalid = writeConfig("""
                compile:
                  cpus: 4
                  memory-mib: 4096
                  swap: 8G
                  runtime-root: /runtime
                  workspace-root: /workspace
                  reference-roots: {}
                  direct-resources:
                    /project:
                      cpus: 0
                      memory-mib: 1024
                """);
        assertTrue(assertThrows(IllegalStateException.class,
                () -> WorkerPoolsFile.loadStrict(invalid)).getMessage().contains("cpus"));

        var unknown = writeConfig("""
                compile:
                  cpus: 4
                  memory-mib: 4096
                  swap: 8G
                  runtime-root: /runtime
                  workspace-root: /workspace
                  reference-roots: {}
                  direct-resources:
                    /project:
                      cpus: 2
                      typo-memory: 8192
                """);
        assertTrue(assertThrows(IllegalStateException.class,
                () -> WorkerPoolsFile.loadStrict(unknown)).getMessage().contains("typo-memory"));

        var project = tempDir.resolve("project");
        var duplicate = writeConfig("""
                compile:
                  cpus: 4
                  memory-mib: 4096
                  swap: 8G
                  runtime-root: /runtime
                  workspace-root: /workspace
                  reference-roots: {}
                  direct-resources:
                    %s:
                      cpus: 2
                    %s/.:
                      memory-mib: 8192
                """.formatted(project, project));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> WorkerPoolsFile.loadStrict(duplicate)).getMessage()
                .contains("duplicate roots"));
    }

    @Test
    void strictLoadRejectsUnknownPoolFields() throws Exception {
        var config = writeConfig("""
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
                () -> WorkerPoolsFile.loadStrict(config));
        assertTrue(error.getMessage().contains("typo-memory"));
    }

    @Test
    void strictLoadRejectsMissingAndOutOfRangeResources() throws Exception {
        var missing = writeConfig("""
                compile:
                  cpus: 4
                  swap: 8G
                  runtime-root: /runtime
                  workspace-root: /workspace
                  reference-roots: {}
                """);
        assertTrue(assertThrows(IllegalStateException.class,
                () -> WorkerPoolsFile.loadStrict(missing)).getMessage().contains("memory-mib"));

        var tooSmall = writeConfig("""
                compile:
                  cpus: 0
                  memory-mib: 1024
                  swap: eight
                  runtime-root: /runtime
                  workspace-root: /workspace
                  reference-roots: {}
                """);
        assertTrue(assertThrows(IllegalStateException.class,
                () -> WorkerPoolsFile.loadStrict(tooSmall)).getMessage().contains("cpus"));

        var lowMemory = writeConfig("""
                compile:
                  cpus: 2
                  memory-mib: 1024
                  swap: 8G
                  runtime-root: /runtime
                  workspace-root: /workspace
                  reference-roots: {}
                """);
        assertTrue(assertThrows(IllegalStateException.class,
                () -> WorkerPoolsFile.loadStrict(lowMemory)).getMessage().contains("memory-mib"));

        var invalidSwap = writeConfig("""
                compile:
                  cpus: 2
                  memory-mib: 2048
                  swap: 8GiB
                  runtime-root: /runtime
                  workspace-root: /workspace
                  reference-roots: {}
                """);
        assertTrue(assertThrows(IllegalStateException.class,
                () -> WorkerPoolsFile.loadStrict(invalidSwap)).getMessage().contains("swap"));
    }

    @Test
    void strictLoadRejectsUnsafeNamesAndRelativeRoots() throws Exception {
        var unsafePool = writeConfig("""
                ../escape:
                  cpus: 4
                  memory-mib: 4096
                  swap: 8G
                  runtime-root: /runtime
                  workspace-root: /workspace
                  reference-roots: {}
                """);
        assertTrue(assertThrows(IllegalStateException.class,
                () -> WorkerPoolsFile.loadStrict(unsafePool)).getMessage().contains("unsafe worker pool name"));

        var relativeRoot = writeConfig("""
                compile:
                  cpus: 4
                  memory-mib: 4096
                  swap: 8G
                  runtime-root: relative/path
                  workspace-root: /workspace
                  reference-roots: {}
                """);
        var rootError = assertThrows(IllegalStateException.class,
                () -> WorkerPoolsFile.loadStrict(relativeRoot));
        assertTrue(rootError.getMessage().contains("runtime-root"));

        var unsafeExport = writeConfig("""
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
                () -> WorkerPoolsFile.loadStrict(unsafeExport));
        assertTrue(exportError.getMessage().contains("unsafe reference export name"));

        var oversizedExport = writeConfig("""
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
                () -> WorkerPoolsFile.loadStrict(oversizedExport));
        assertTrue(oversizedError.getMessage().contains("at most 22"));
    }

    @Test
    void strictLoadRejectsDuplicateAndOverlappingNormalizedRoots() throws Exception {
        var config = writeConfig("""
                compile:
                  cpus: 4
                  memory-mib: 4096
                  swap: 8G
                  runtime-root: ~/roots/runtime/../shared
                  workspace-root: ~/roots/shared/work
                  reference-roots: {}
                """);

        var error = assertThrows(IllegalStateException.class,
                () -> WorkerPoolsFile.loadStrict(config));
        assertTrue(error.getMessage().contains("duplicate or overlap"));
    }

    @Test
    void selectedPoolFailsClosedForUnknownPoolAndMalformedConfig() throws Exception {
        var valid = writeConfig("""
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

        var malformed = writeConfig("[not-a-map]\n");
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
