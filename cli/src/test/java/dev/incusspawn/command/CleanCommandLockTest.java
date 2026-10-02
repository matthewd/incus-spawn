package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.config.WorkerPoolSelection;
import dev.incusspawn.util.BoundedFileLock;
import dev.incusspawn.vm.VmManager;
import org.aesh.command.CommandResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class CleanCommandLockTest {

    @TempDir Path home;
    private String originalHome;

    @BeforeEach
    void isolateHome() {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
    }

    @AfterEach
    void restoreHome() {
        System.setProperty("user.home", originalHome);
    }

    @Test
    void legacyStateCleanupChecksEveryVmStateNamespace() {
        var selectedProbeCalled = new AtomicBoolean();

        assertTrue(CleanCommand.stateCleanupHasRunningVm(
                WorkerPoolSelection.legacy(),
                () -> {
                    selectedProbeCalled.set(true);
                    return false;
                },
                () -> true));
        assertFalse(selectedProbeCalled.get(),
                "legacy cleanup must use the all-pool probe while holding the global lock");
    }

    @Test
    void namedStateCleanupRetainsSelectedPoolBehavior() throws Exception {
        var runtime = Files.createDirectory(home.resolve("runtime"));
        var workspace = Files.createDirectory(home.resolve("workspace"));
        var config = home.resolve("config.yaml");
        Files.writeString(config, """
                worker-pools:
                  compile:
                    cpus: 2
                    memory-mib: 4096
                    swap: 8G
                    runtime-root: %s
                    workspace-root: %s
                    reference-roots: {}
                """.formatted(runtime, workspace));
        var anyProbeCalled = new AtomicBoolean();

        assertFalse(CleanCommand.stateCleanupHasRunningVm(
                WorkerPoolSelection.select("compile", config),
                () -> false,
                () -> {
                    anyProbeCalled.set(true);
                    return true;
                }));
        assertFalse(anyProbeCalled.get(),
                "named cleanup must not be blocked by an unrelated pool");
    }

    @Test
    void requiredPoolCleanupAcceptsOnlyAnEmptyRescan() {
        assertTrue(CleanCommand.poolCleanupComplete(
                java.util.List.of(), java.util.List.of(), false));
        assertFalse(CleanCommand.poolCleanupComplete(
                java.util.List.of("failed-build"), java.util.List.of(), false));
        assertFalse(CleanCommand.poolCleanupComplete(
                java.util.List.of(), java.util.List.of(), true));
    }

    @Test
    void stateCleanupWaitsForVmLifecycleOperations() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var lifecycle = executor.submit(() -> VmManager.withLifecycleLock(() -> {
                entered.countDown();
                release.await();
                return null;
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            var cleanup = executor.submit(() -> CleanCommand.withStateCleanupLocks(
                    () -> CommandResult.SUCCESS));
            Thread.sleep(100);
            assertFalse(cleanup.isDone());

            release.countDown();
            lifecycle.get(5, TimeUnit.SECONDS);
            assertEquals(CommandResult.SUCCESS, cleanup.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertFalse(Environment.vmLockFile().startsWith(Environment.vmStateDir()));
        assertTrue(java.nio.file.Files.exists(Environment.vmLockFile()));
    }

    @Test
    void stateCleanupWaitsForPoolPublication() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        BoundedFileLock management = BoundedFileLock.acquire(
                Environment.poolManagementLockFile(), Duration.ofSeconds(1),
                null, "test lock timed out");
        try {
            var cleanup = executor.submit(() -> CleanCommand.withStateCleanupLocks(
                    () -> CommandResult.SUCCESS));
            Thread.sleep(100);
            assertFalse(cleanup.isDone());

            management.close();
            management = null;
            assertEquals(CommandResult.SUCCESS, cleanup.get(5, TimeUnit.SECONDS));
        } finally {
            if (management != null) management.close();
            executor.shutdownNow();
        }
        assertFalse(Environment.poolManagementLockFile().startsWith(Environment.stateDir()));
        assertTrue(java.nio.file.Files.exists(Environment.poolManagementLockFile()));
    }
}
