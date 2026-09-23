package dev.incusspawn;

import dev.incusspawn.config.WorkerPoolSelection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerPoolSelectionTest {

    @TempDir Path tempDir;

    @Test
    void unsetSelectionPreservesEveryLegacyStateRoot() {
        var legacy = WorkerPoolSelection.legacy();
        var globalState = tempDir.resolve(".local/state/incus-spawn");
        var legacyLocks = tempDir.resolve(".cache/incus-spawn/locks");

        assertTrue(legacy.isLegacy());
        assertEquals(globalState, legacy.vmStateDir(globalState));
        assertEquals(legacyLocks, legacy.instanceLockDir(globalState, legacyLocks));

        // The public accessors retain the historical locations when ISX_POOL is unset.
        if (System.getenv(WorkerPoolSelection.ENVIRONMENT_VARIABLE) == null) {
            var originalHome = System.getProperty("user.home");
            try {
                System.setProperty("user.home", tempDir.toString());
                assertEquals(globalState, Environment.vmStateDir());
                assertEquals(globalState.resolve("vm.pid"), Environment.vmPidFile());
                assertEquals(globalState.resolve("vm.log"), Environment.vmLogFile());
                assertEquals(globalState.resolve("vm.rest-uri"), Environment.vmRestUriFile());
                assertEquals(globalState.resolve("vm.incus.sock"), Environment.vmVsockSocket());
                assertEquals(globalState.resolve("vm.agent.sock"), Environment.vmAgentSocket());
                assertEquals(globalState.resolve("disk.img"), Environment.vmDiskImage());
                assertEquals(globalState.resolve("data.img"), Environment.vmDataImage());
                assertEquals(globalState.resolve("swap.img"), Environment.vmSwapImage());
                assertEquals(globalState.resolve("incus-spawn-vm.app"), Environment.vfkitAppBundle());
                assertEquals(legacyLocks, Environment.lockDir());
                assertEquals(globalState.resolve("proxy.log"), Environment.proxyLogFile());
                assertEquals(globalState.resolve("proxy-service.log"), Environment.proxyServiceLogFile());
                assertEquals(globalState.resolve("proxy-gateway-ip"), Environment.proxyGatewayFile());
            } finally {
                System.setProperty("user.home", originalHome);
            }
        }
    }

    @Test
    void namedSelectionIsolatesVmStateAndInstanceLocks() throws Exception {
        var config = tempDir.resolve("config.yaml");
        Files.writeString(config, """
                worker-pools:
                  compile:
                    cpus: 8
                    memory-mib: 12288
                    swap: 16G
                    runtime-root: /opt/isx/runtime
                    workspace-root: /opt/isx/workspace
                    reference-roots: {}
                """);
        var selected = WorkerPoolSelection.select("compile", config);
        var globalState = tempDir.resolve(".local/state/incus-spawn");
        var legacyLocks = tempDir.resolve(".cache/incus-spawn/locks");
        var poolState = globalState.resolve("pools/compile");

        assertFalse(selected.isLegacy());
        assertEquals("compile", selected.name().orElseThrow());
        assertEquals(poolState, selected.vmStateDir(globalState));
        assertEquals(poolState.resolve("locks"),
                selected.instanceLockDir(globalState, legacyLocks));

        // Every VM-owned path is relative to vmStateDir(), so the files cannot collide with the
        // legacy VM or a sibling named pool. Global config, caches and proxy paths do not use it.
        assertEquals(poolState.resolve("vm.pid"), selected.vmStateDir(globalState).resolve("vm.pid"));
        assertEquals(poolState.resolve("disk.img"), selected.vmStateDir(globalState).resolve("disk.img"));
        assertEquals(poolState.resolve("vm.incus.sock"),
                selected.vmStateDir(globalState).resolve("vm.incus.sock"));
        assertEquals(poolState.resolve("incus-spawn-vm.app"),
                selected.vmStateDir(globalState).resolve("incus-spawn-vm.app"));
    }
}
