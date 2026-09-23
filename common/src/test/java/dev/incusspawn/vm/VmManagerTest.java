package dev.incusspawn.vm;

import dev.incusspawn.Environment;
import dev.incusspawn.Platform;
import dev.incusspawn.config.WorkerPoolSelection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class VmManagerTest {

    @TempDir Path tempHome;
    private String originalHome;

    @BeforeEach
    void isolateEnvironment() {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
    }

    @AfterEach
    void restoreEnvironment() {
        System.setProperty("user.home", originalHome);
    }

    @Test
    void detectCpusReturnsAtLeastOne() {
        assertTrue(VmManager.detectCpus() >= 1);
    }

    @Test
    void namedPoolResourcesTakePrecedenceOverLegacyEnvironmentOverrides() throws Exception {
        var selection = namedPool("compile", 7, 6144, "9G");

        assertEquals(7, VmManager.detectCpus(selection, "99", () -> 101));
        assertEquals(6144, VmManager.detectMemoryMiB(selection, "32768", () -> 65536));
        assertEquals("9G", VmManager.swapSize(selection, "40G"));
    }

    @Test
    void legacySelectionRetainsEnvironmentAndDetectionBehavior() {
        var legacy = WorkerPoolSelection.legacy();

        assertEquals(5, VmManager.detectCpus(legacy, "5", () -> 11));
        assertEquals(8192, VmManager.detectMemoryMiB(legacy, "8192", () -> 16384));
        assertEquals("20G", VmManager.swapSize(legacy, "20G"));
        assertEquals(11, VmManager.detectCpus(legacy, null, () -> 11));
        assertEquals(16384, VmManager.detectMemoryMiB(legacy, null, () -> 16384));
        assertEquals("12G", VmManager.swapSize(legacy, null));
    }

    @Test
    void detectMemoryReturnsAtLeast2048() {
        assertTrue(VmManager.detectMemoryMiB() >= 2048);
    }

    @Test
    void diskSizeDefaultIs60G() {
        if (System.getenv("ISX_VM_DISK") == null) {
            assertEquals("60G", VmManager.diskSize());
        }
    }

    @Test
    void isRunningReturnsFalseWhenNoPidFile() {
        assertFalse(VmManager.isRunning());
    }

    @Test
    void awaitProcessExitReportsTimeoutUntilProcessActuallyExits() throws Exception {
        var process = new ProcessBuilder("sleep", "30").start();
        try {
            assertFalse(VmManager.awaitProcessExit(process.toHandle(), Duration.ofMillis(10)));
            process.destroyForcibly();
            assertTrue(VmManager.awaitProcessExit(process.toHandle(), Duration.ofSeconds(5)));
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void detectBackendReturnsValueOnCurrentOs() {
        if (Platform.isLinux()) {
            try {
                var backend = VmManager.detectBackend();
                assertEquals(VmManager.Backend.QEMU, backend);
            } catch (VmException e) {
                assertTrue(e.getMessage().contains("qemu-system"));
            }
        } else if (Platform.isMacOS()) {
            try {
                var backend = VmManager.detectBackend();
                assertEquals(VmManager.Backend.VFKIT, backend);
            } catch (VmException e) {
                assertTrue(e.getMessage().contains("vfkit"));
            }
        }
    }

    @Test
    void applianceVersionOverridesAcceptOnlyOpaqueVersionNames() {
        assertTrue(VmManager.isSafeApplianceVersion("bb-worker-34ec5a1"));
        assertTrue(VmManager.isSafeApplianceVersion("0.3.7+local.1"));
        assertFalse(VmManager.isSafeApplianceVersion(""));
        assertFalse(VmManager.isSafeApplianceVersion("../appliance"));
        assertFalse(VmManager.isSafeApplianceVersion("version with spaces"));
        assertFalse(VmManager.isSafeApplianceVersion("version\nother"));
    }

    @Test
    void gatewayIpDefaultIs10_166_11_1() {
        if (System.getenv("ISX_GATEWAY") == null) {
            assertEquals("10.166.11.1", VmManager.gatewayIp());
        }
    }

    @Test
    void mitmPortDefaultIs18443() {
        if (System.getenv("ISX_MITM_PORT") == null) {
            assertEquals("18443", VmManager.mitmPort());
        }
    }

    @Test
    void checkArtifactsThrowsWhenNoKernel() {
        var ex = assertThrows(VmException.class, VmManager::checkArtifacts);
        assertTrue(ex.getMessage().contains("vmlinuz"));
    }

    @Test
    void statusReportsNotRunningWhenVmIsStopped() {
        assertEquals("VM not running", VmManager.status());
    }

    @Test
    void parseDiskSizeGigabytes() {
        assertEquals(60L * 1024 * 1024 * 1024, VmManager.parseDiskSize("60G"));
    }

    @Test
    void parseDiskSizeMegabytes() {
        assertEquals(512L * 1024 * 1024, VmManager.parseDiskSize("512M"));
    }

    @Test
    void parseDiskSizeTerabytes() {
        assertEquals(1024L * 1024 * 1024 * 1024, VmManager.parseDiskSize("1T"));
    }

    @Test
    void parseDiskSizeCaseInsensitive() {
        assertEquals(60L * 1024 * 1024 * 1024, VmManager.parseDiskSize("60g"));
    }

    @Test
    void ensureDiskThrowsWhenNoCompressedImage() {
        var ex = assertThrows(VmException.class, VmManager::ensureDisk);
        assertTrue(ex.getMessage().contains("disk.img.gz"));
    }

    @Test
    void swapSizeDefaultIs12G() {
        if (System.getenv("ISX_VM_SWAP") == null) {
            assertEquals("12G", VmManager.swapSize());
        }
    }

    @Test
    void ensureSwapCreatesSparseFile() {
        VmManager.ensureSwap();

        var swapImage = Environment.vmSwapImage();
        assertTrue(Files.exists(swapImage));
        assertEquals(12L * 1024 * 1024 * 1024, swapImage.toFile().length());
    }

    @Test
    void ensureSwapIsIdempotent() throws Exception {
        VmManager.ensureSwap();
        var swapImage = Environment.vmSwapImage();
        var modifiedFirst = Files.getLastModifiedTime(swapImage);

        Thread.sleep(50);
        VmManager.ensureSwap();
        var modifiedSecond = Files.getLastModifiedTime(swapImage);

        assertEquals(modifiedFirst, modifiedSecond);
    }

    // --- data disk resize (grow-only) ---

    @Test
    void dataDiskSizeBytesIsMinusOneWhenAbsent() {
        assertEquals(-1, VmManager.dataDiskSizeBytes());
    }

    @Test
    void dataDiskSizeBytesReflectsCreatedDisk() {
        VmManager.ensureDataDisk();
        assertEquals(VmManager.parseDiskSize(VmManager.diskSize()), VmManager.dataDiskSizeBytes());
    }

    @Test
    void resizeDataDiskGrowsTheImage() {
        VmManager.ensureDataDisk();
        long current = VmManager.dataDiskSizeBytes();
        long target = current * 2;
        long targetGiB = target / (1024L * 1024 * 1024);
        long result = VmManager.resizeDataDisk(targetGiB + "G");
        assertEquals(targetGiB * 1024 * 1024 * 1024, result);
        assertEquals(result, VmManager.dataDiskSizeBytes());
    }

    @Test
    void resizeDataDiskThrowsWhenAbsent() {
        var ex = assertThrows(VmException.class, () -> VmManager.resizeDataDisk("100G"));
        assertTrue(ex.getMessage().contains("No data disk"));
    }

    @Test
    void resizeDataDiskRejectsShrink() {
        VmManager.ensureDataDisk();
        long currentGiB = VmManager.dataDiskSizeBytes() / (1024L * 1024 * 1024);
        long smaller = Math.max(1, currentGiB / 2);
        if (smaller >= currentGiB) return; // disk too small to express a smaller whole-GiB size
        var ex = assertThrows(VmException.class, () -> VmManager.resizeDataDisk(smaller + "G"));
        assertTrue(ex.getMessage().contains("larger"));
    }

    @Test
    void resizeDataDiskRejectsEqualSize() {
        VmManager.ensureDataDisk();
        var ex = assertThrows(VmException.class, () -> VmManager.resizeDataDisk(VmManager.diskSize()));
        assertTrue(ex.getMessage().contains("larger"));
    }

    // --- vfkit command construction ---

    @Test
    void legacyVfkitCommandRetainsTheSingleWholeHomeExport() {
        var command = VmManager.vfkitCommand(
                "/usr/local/bin/vfkit", 4, 4096, 12345, null, 1000);

        assertTrue(command.contains("virtio-fs,sharedDir=" + tempHome + ",mountTag=hostfs"));
        assertEquals(1, command.stream().filter(arg -> arg.startsWith("virtio-fs,")).count());
        var cmdline = command.get(command.indexOf("--kernel-cmdline") + 1);
        assertTrue(cmdline.contains(" isx.shared=/host"));
        assertFalse(cmdline.contains("isx.host_exports="));
        assertFalse(cmdline.contains("isx.reference_names="));
    }

    @Test
    void namedVfkitCommandEmitsEveryTypedExportAndDeterministicReferences() throws Exception {
        var maven = Files.createDirectories(tempHome.resolve("references/maven"));
        var sources = Files.createDirectories(tempHome.resolve("references/sources"));
        var references = new java.util.LinkedHashMap<String, dev.incusspawn.config.WorkerPoolConfig.ReadOnlyExport>();
        references.put("sources", new dev.incusspawn.config.WorkerPoolConfig.ReadOnlyExport(sources.toString()));
        references.put("maven", new dev.incusspawn.config.WorkerPoolConfig.ReadOnlyExport(maven.toString()));
        var selected = new dev.incusspawn.config.WorkerPoolConfig.Selected(
                4, 4096, "8G",
                new dev.incusspawn.config.WorkerPoolConfig.ReadOnlyExport(
                        tempHome.resolve("runtime").toString()),
                new dev.incusspawn.config.WorkerPoolConfig.ReadWriteExport(
                        tempHome.resolve("workspace").toString()),
                references);
        var exports = VmHostExports.create("compile", selected, tempHome);

        var command = VmManager.vfkitCommand(
                "/usr/local/bin/vfkit", 4, 4096, 12345, exports, 1000);
        var devices = command.stream().filter(arg -> arg.startsWith("virtio-")).toList();

        assertEquals(java.util.List.of(
                "virtio-blk,path=" + Environment.vmDiskImage(),
                "virtio-blk,path=" + Environment.vmSwapImage(),
                "virtio-blk,path=" + Environment.vmDataImage(),
                "virtio-net,nat,mac=" + VmNetwork.selectedMac(),
                "virtio-serial,logFilePath=" + Environment.vmLogFile(),
                "virtio-fs,sharedDir=" + exports.runtime().hostPath()
                        + ",mountTag=isx-runtime,readonly",
                "virtio-fs,sharedDir=" + exports.workspace().hostPath()
                        + ",mountTag=isx-workspace",
                "virtio-fs,sharedDir=" + maven.toRealPath()
                        + ",mountTag=isx-reference-maven,readonly",
                "virtio-fs,sharedDir=" + sources.toRealPath()
                        + ",mountTag=isx-reference-sources,readonly",
                "virtio-vsock,port=8443,socketURL=" + Environment.vmVsockSocket() + ",connect",
                "virtio-vsock,port=1025,socketURL=" + Environment.vmAgentSocket() + ",connect"
        ), devices);
        assertFalse(command.contains("virtio-fs,sharedDir=" + tempHome + ",mountTag=hostfs"));
        var cmdline = command.get(command.indexOf("--kernel-cmdline") + 1);
        assertTrue(cmdline.contains(" isx.shared=/host/runtime"));
        assertTrue(cmdline.contains(" isx.host_exports=named"));
        assertTrue(cmdline.contains(" isx.reference_names=maven,sources"));
    }

    @Test
    void vfkitCommandRejectsUnencodableStatePathsBeforeReturningArguments() {
        System.setProperty("user.home", tempHome + "/unsafe,home");

        var error = assertThrows(IllegalStateException.class, () -> VmManager.vfkitCommand(
                "/usr/local/bin/vfkit", 4, 4096, 12345, null, 1000));

        assertTrue(error.getMessage().contains("comma-separated device syntax"));
    }

    // --- btrfs superblock validation ---

    @Test
    void validateBtrfsMagicAcceptsValidImage() throws Exception {
        var img = tempHome.resolve("valid.img");
        try (var raf = new RandomAccessFile(img.toFile(), "rw")) {
            raf.setLength(0x10048);
            raf.seek(0x10040);
            raf.write("_BHRfS_M".getBytes(StandardCharsets.US_ASCII));
        }
        assertDoesNotThrow(() -> VmManager.validateBtrfsMagic(img));
    }

    @Test
    void validateBtrfsMagicRejectsTruncatedImage() throws Exception {
        var img = tempHome.resolve("tiny.img");
        try (var raf = new RandomAccessFile(img.toFile(), "rw")) {
            raf.setLength(1024);
        }
        var ex = assertThrows(IOException.class, () -> VmManager.validateBtrfsMagic(img));
        assertTrue(ex.getMessage().contains("too small"));
    }

    @Test
    void validateBtrfsMagicRejectsZeroedImage() throws Exception {
        var img = tempHome.resolve("zeroed.img");
        try (var raf = new RandomAccessFile(img.toFile(), "rw")) {
            raf.setLength(0x10048);
        }
        var ex = assertThrows(IOException.class, () -> VmManager.validateBtrfsMagic(img));
        assertTrue(ex.getMessage().contains("no valid btrfs superblock"));
    }

    // --- recoverReachability orchestration (grace -> layer check -> forwarder restart -> backstop) ---

    @Test
    void recoverReachabilityReturnsEarlyWhenGraceClears() {
        var restarts = new java.util.concurrent.atomic.AtomicInteger();
        boolean ok = VmManager.recoverReachability(
                secs -> true,                                       // reachable on the grace probe
                () -> { restarts.incrementAndGet(); return true; },
                java.util.Optional::empty);
        assertTrue(ok);
        assertEquals(0, restarts.get(), "must not restart the forwarder if the grace probe succeeds");
    }

    @Test
    void recoverReachabilityRestartsForwarderThenRecovers() {
        var probes = new java.util.ArrayDeque<>(java.util.List.of(false, true)); // grace fails, post-restart succeeds
        var restarts = new java.util.concurrent.atomic.AtomicInteger();
        boolean ok = VmManager.recoverReachability(
                secs -> probes.poll(),
                () -> { restarts.incrementAndGet(); return true; },
                java.util.Optional::empty);                         // unknown layer — attempt restart
        assertTrue(ok);
        assertEquals(1, restarts.get());
        assertTrue(probes.isEmpty(), "must not fall through to the backstop probe once recovery succeeds");
    }

    @Test
    void recoverReachabilityRestartsWhenLayerIsForwarder() {
        var probes = new java.util.ArrayDeque<>(java.util.List.of(false, true)); // grace fails, post-restart succeeds
        var restarts = new java.util.concurrent.atomic.AtomicInteger();
        boolean ok = VmManager.recoverReachability(
                secs -> probes.poll(),
                () -> { restarts.incrementAndGet(); return true; },
                () -> java.util.Optional.of(VmManager.LeakLayer.FORWARDER));
        assertTrue(ok);
        assertEquals(1, restarts.get(), "forwarder-layer wedge should attempt a restart");
    }

    @Test
    void recoverReachabilityFailsFastWhenLayerIsVfkit() {
        var restarts = new java.util.concurrent.atomic.AtomicInteger();
        boolean ok = VmManager.recoverReachability(
                secs -> false,                                      // grace fails
                () -> { restarts.incrementAndGet(); return true; },
                () -> java.util.Optional.of(VmManager.LeakLayer.VFKIT));
        assertFalse(ok, "vfkit wedge cannot be fixed by a forwarder restart — must fail fast");
        assertEquals(0, restarts.get(), "must not attempt a forwarder restart for a vfkit wedge");
    }

    @Test
    void recoverReachabilityFallsBackToBackstopWhenAgentDoesNotConfirm() {
        var probes = new java.util.ArrayDeque<>(java.util.List.of(false, true)); // grace fails, backstop succeeds
        boolean ok = VmManager.recoverReachability(
                secs -> probes.poll(),
                () -> false,                                        // agent unreachable or unconfirmed
                java.util.Optional::empty);
        assertTrue(ok);
        assertTrue(probes.isEmpty(), "must probe the backstop after the restart cannot be confirmed");
    }

    @Test
    void recoverReachabilityGivesUpWhenNothingRecovers() {
        boolean ok = VmManager.recoverReachability(secs -> false, () -> false, java.util.Optional::empty);
        assertFalse(ok, "must return false so the caller can surface an actionable error");
    }

    // --- leakLayer (moved from DoctorCommandTest — canonical location is VmManager) ---

    @Test
    void leakLayerLocatesVfkitWhenGuestCountStaysLow() {
        assertEquals(VmManager.LeakLayer.VFKIT, VmManager.leakLayer(300, 5));
        assertEquals(VmManager.LeakLayer.VFKIT, VmManager.leakLayer(100, 50), "boundary: guest*2 == host");
    }

    @Test
    void leakLayerLocatesForwarderWhenBothCountsClimb() {
        assertEquals(VmManager.LeakLayer.FORWARDER, VmManager.leakLayer(300, 280));
        assertEquals(VmManager.LeakLayer.FORWARDER, VmManager.leakLayer(100, 51));
    }

    @Test
    void leakLayerTreatsZeroGuestAsForwarderNotRunning() {
        assertEquals(VmManager.LeakLayer.FORWARDER, VmManager.leakLayer(0, 0),
                "both zero: forwarder not running, not a vfkit wedge");
        assertEquals(VmManager.LeakLayer.FORWARDER, VmManager.leakLayer(5, 0),
                "host fds leaked but forwarder gone: restarting it is the correct fix");
    }

    private WorkerPoolSelection namedPool(String name, int cpus, int memoryMib, String swap)
            throws Exception {
        var config = tempHome.resolve("config-" + name + ".yaml");
        Files.writeString(config, """
                worker-pools:
                  %s:
                    cpus: %d
                    memory-mib: %d
                    swap: %s
                    runtime-root: /opt/isx/runtime
                    workspace-root: /opt/isx/workspace
                    reference-roots: {}
                """.formatted(name, cpus, memoryMib, swap));
        return WorkerPoolSelection.select(name, config);
    }
}
