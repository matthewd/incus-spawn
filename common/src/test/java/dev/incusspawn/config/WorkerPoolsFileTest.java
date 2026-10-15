package dev.incusspawn.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkerPoolsFileTest {

    @TempDir Path tempDir;

    @Test
    void missingOrEmptyFileDeclaresNoPools() throws Exception {
        assertTrue(WorkerPoolsFile.loadStrict(tempDir.resolve("absent.yaml")).isEmpty());
        assertTrue(WorkerPoolsFile.loadStrict(write("")).isEmpty());
        assertTrue(WorkerPoolsFile.loadStrict(write("{}\n")).isEmpty());
    }

    @Test
    void poolsKeepDeclarationOrder() throws Exception {
        var pools = WorkerPoolsFile.loadStrict(write("""
                zeta:
                  cpus: 2
                  memory-mib: 2048
                  swap: 8G
                  runtime-root: /zeta/runtime
                  workspace-root: /zeta/workspace
                  reference-roots: {}
                alpha:
                  cpus: 2
                  memory-mib: 2048
                  swap: 8G
                  runtime-root: /alpha/runtime
                  workspace-root: /alpha/workspace
                  reference-roots: {}
                """));
        assertEquals(List.of("zeta", "alpha"), List.copyOf(pools.keySet()));
    }

    @Test
    void nonMapDocumentsAndNullPoolsAreRejected() throws Exception {
        var list = write("- compile\n");
        assertTrue(assertThrows(IllegalStateException.class,
                () -> WorkerPoolsFile.loadStrict(list)).getMessage()
                .contains("must map worker pool names"));

        var nullPool = write("compile:\n");
        assertTrue(assertThrows(IllegalStateException.class,
                () -> WorkerPoolsFile.loadStrict(nullPool)).getMessage()
                .contains("definition is required"));
    }

    @Test
    void duplicatePoolNamesAreRejected() throws Exception {
        var duplicate = write("""
                compile: {}
                compile: {}
                """);
        assertTrue(assertThrows(IllegalStateException.class,
                () -> WorkerPoolsFile.loadStrict(duplicate)).getMessage()
                .contains("duplicate key 'compile'"));
    }

    private Path write(String yaml) throws Exception {
        var path = tempDir.resolve("worker-pools-" + System.nanoTime() + ".yaml");
        Files.writeString(path, yaml);
        return path;
    }
}
