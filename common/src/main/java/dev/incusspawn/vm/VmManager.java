package dev.incusspawn.vm;

import dev.incusspawn.BuildInfo;
import dev.incusspawn.Environment;
import dev.incusspawn.config.WorkerPoolSelection;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.ResourceLimits;
import dev.incusspawn.tool.DownloadCache;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.CpuInfo;
import dev.incusspawn.Platform;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.IntPredicate;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Manages the incus-spawn VM appliance lifecycle.
 * Ports appliance/vm.sh to Java for use from isx commands.
 */
public final class VmManager {

    private VmManager() {}

    public enum Backend { VFKIT, QEMU }

    /** Where forwarder streams are leaking, inferred from host vs in-guest connection counts. */
    public enum LeakLayer {
        FORWARDER("forwarder is lingering children (link 3) — the in-VM forwarder-restart clears it"),
        VFKIT("vfkit is not reaping host fds (link 2) — a VM restart is required");
        public final String description;
        LeakLayer(String description) { this.description = description; }
    }

    public static LeakLayer leakLayer(int hostCount, int guestCount) {
        if (guestCount <= 0) return LeakLayer.FORWARDER;
        return guestCount * 2 <= hostCount ? LeakLayer.VFKIT : LeakLayer.FORWARDER;
    }

    /**
     * Determine which tunnel layer is responsible for a leak using the host-side vfkit fd count
     * and the in-guest forwarder socat count. Returns empty if either count is unavailable.
     */
    public static Optional<LeakLayer> detectLeakLayer() {
        int host = vsockForwarderConnectionCount();
        if (host < 0) return Optional.empty();
        var guest = VmAgentClient.socatCount();
        if (guest.isEmpty()) return Optional.empty();
        return Optional.of(leakLayer(host, guest.getAsInt()));
    }

    /** Overall tunnel health, for proactive detection of wedged tunnels. */
    public enum TunnelHealth {
        HEALTHY,
        VFKIT_WEDGED,
        FORWARDER_ISSUE,
        UNKNOWN
    }

    /**
     * Probe the vsock tunnel's health. Intended for periodic checks from the TUI or during
     * long-running operations (builds). When the Incus tunnel is dead but the agent lane answers,
     * the host-side tunnel (vfkit) is wedged and only {@code isx vm restart} can fix it.
     */
    public static TunnelHealth probeTunnelHealth() {
        if (!Platform.isMacOS()) return TunnelHealth.HEALTHY;
        if (!isRunning()) return TunnelHealth.UNKNOWN;
        if (IncusClient.isReachable()) return TunnelHealth.HEALTHY;
        // API is unreachable — try to determine which layer is at fault.
        // detectLeakLayer() talks to the agent (socatCount), so a successful result
        // also confirms the agent lane is healthy — no separate ping() needed.
        var layer = detectLeakLayer();
        if (layer.isPresent()) {
            return switch (layer.get()) {
                case VFKIT -> TunnelHealth.VFKIT_WEDGED;
                case FORWARDER -> TunnelHealth.FORWARDER_ISSUE;
            };
        }
        // Layer detection unavailable (lsof or socatCount failed); a bare ping only proves
        // the agent lane is alive — it cannot distinguish a vfkit wedge from a stopped Incus
        // daemon or a crashed forwarder, so we cannot safely claim VFKIT_WEDGED here.
        return TunnelHealth.UNKNOWN;
    }

    private static final String DEFAULT_GATEWAY = "10.166.11.1";
    private static final String DEFAULT_MITM_PORT = "18443";
    private static final String DEFAULT_DISK_SIZE = "60G";
    private static final String DEFAULT_SWAP_SIZE = "12G";
    private static final int GA_VSOCK_PORT = 1024;
    private static final int AGENT_VSOCK_PORT = 1025;
    private static final int INCUS_VSOCK_PORT = 8443;

    private static final String LATEST_KNOWN_RELEASE = "0.2.2";
    private static volatile String resolvedApplianceVersion;

    // --- Resource detection ---

    public static int detectCpus() {
        return detectCpus(WorkerPoolSelection.current(), System.getenv("ISX_VM_CPUS"), () -> {
            if (Platform.isMacOS()) {
                int pcores = CpuInfo.performanceCores();
                if (pcores > 0) return pcores;
            }
            return Math.max(1, ResourceLimits.hostProcessorCount() - 2);
        });
    }

    static int detectCpus(WorkerPoolSelection selection, String legacyOverride,
                          IntSupplier legacyDefault) {
        var pool = selection.pool();
        if (pool.isPresent()) return pool.get().cpus();
        if (legacyOverride != null && !legacyOverride.isBlank()) {
            try {
                int val = Integer.parseInt(legacyOverride);
                if (val < 1) {
                    System.err.println("Warning: ISX_VM_CPUS=" + legacyOverride + " is invalid, using 1");
                    return 1;
                }
                return val;
            } catch (NumberFormatException e) {
                System.err.println("Warning: ISX_VM_CPUS=" + legacyOverride + " is not a number, ignoring");
            }
        }
        return legacyDefault.getAsInt();
    }

    public static int detectMemoryMiB() {
        return detectMemoryMiB(WorkerPoolSelection.current(), System.getenv("ISX_VM_MEMORY"), () -> {
            long totalBytes = ResourceLimits.totalMemoryBytes();
            if (totalBytes <= 0) return 4096;
            int pct = Platform.isMacOS() ? 40 : 60;
            long limitMiB = totalBytes * pct / 100 / (1024 * 1024);
            return (int) Math.max(2048, limitMiB);
        });
    }

    static int detectMemoryMiB(WorkerPoolSelection selection, String legacyOverride,
                               IntSupplier legacyDefault) {
        var pool = selection.pool();
        if (pool.isPresent()) return pool.get().memoryMib();
        if (legacyOverride != null && !legacyOverride.isBlank()) {
            try {
                int val = Integer.parseInt(legacyOverride);
                if (val < 2048) {
                    System.err.println("Warning: ISX_VM_MEMORY=" + legacyOverride
                            + " is below minimum, using 2048 MiB");
                    return 2048;
                }
                return val;
            } catch (NumberFormatException e) {
                System.err.println("Warning: ISX_VM_MEMORY=" + legacyOverride
                        + " is not a number, ignoring");
            }
        }
        return legacyDefault.getAsInt();
    }

    public static String diskSize() {
        var env = System.getenv("ISX_VM_DISK");
        if (env != null && !env.isBlank()) {
            return env;
        }
        return DEFAULT_DISK_SIZE;
    }

    static String rootDiskSize() {
        return "4G";
    }

    public static String swapSize() {
        return swapSize(WorkerPoolSelection.current(), System.getenv("ISX_VM_SWAP"));
    }

    static String swapSize(WorkerPoolSelection selection, String legacyOverride) {
        var pool = selection.pool();
        if (pool.isPresent()) return pool.get().swap();
        if (legacyOverride != null && !legacyOverride.isBlank()) return legacyOverride;
        return DEFAULT_SWAP_SIZE;
    }

    public static String gatewayIp() {
        var env = System.getenv("ISX_GATEWAY");
        return (env != null && !env.isBlank()) ? env : DEFAULT_GATEWAY;
    }

    static String mitmPort() {
        var env = System.getenv("ISX_MITM_PORT");
        return (env != null && !env.isBlank()) ? env : DEFAULT_MITM_PORT;
    }

    // --- Appliance version resolution ---

    public static String applianceVersion() {
        var cached = resolvedApplianceVersion;
        if (cached != null) return cached;

        var override = Environment.strippedEnv("ISX_APPLIANCE_VERSION");
        if (!override.isBlank()) {
            if (!isSafeApplianceVersion(override)) {
                throw new VmException("ISX_APPLIANCE_VERSION contains unsupported characters");
            }
            resolvedApplianceVersion = override;
            return override;
        }

        var build = BuildInfo.instance();
        String result;
        if (!build.isDev()) {
            result = build.version();
        } else {
            var latest = queryLatestGitHubRelease();
            if (latest != null) {
                result = latest;
            } else {
                System.err.println("Warning: could not query latest release from GitHub, "
                        + "using fallback version " + LATEST_KNOWN_RELEASE);
                result = LATEST_KNOWN_RELEASE;
            }
        }
        resolvedApplianceVersion = result;
        return result;
    }

    static boolean isSafeApplianceVersion(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}");
    }

    private static String queryLatestGitHubRelease() {
        try {
            var client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            var request = HttpRequest.newBuilder()
                    .uri(URI.create("https://github.com/Sanne/incus-spawn/releases/latest"))
                    .timeout(Duration.ofSeconds(10))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.discarding());
            var location = response.headers().firstValue("location").orElse(null);
            if (location != null && location.contains("/tag/v")) {
                var tag = location.substring(location.lastIndexOf("/tag/v") + 6);
                if (!tag.isBlank()) return tag;
            }
        } catch (Exception ignored) {}
        return null;
    }

    // --- Backend detection ---

    public static Backend detectBackend() {
        if (Platform.isMacOS()) {
            if (!commandExists("vfkit")) {
                if (!WorkerPoolSelection.current().isLegacy()) {
                    throw new VmException("vfkit not found. Named worker pools require the companion "
                            + "incus-spawn vfkit fork with virtio-fs readonly support; do not install "
                            + "unsupported upstream vfkit for this pool.");
                }
                throw new VmException("vfkit not found. Install with: brew install vfkit");
            }
            return Backend.VFKIT;
        }
        var arch = normalizeArch();
        if (!commandExists("qemu-system-" + arch)) {
            throw new VmException("qemu-system-" + arch + " not found. Install QEMU.");
        }
        return Backend.QEMU;
    }

    // --- VM lifecycle lock ---

    private static class VmLockHolder implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        VmLockHolder(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }
        @Override public void close() {
            try { lock.release(); } catch (IOException ignored) {}
            try { channel.close(); } catch (IOException ignored) {}
        }
    }

    private static final int VM_LOCK_TIMEOUT_SECONDS = 30;

    private static VmLockHolder acquireVmLock() {
        try {
            Files.createDirectories(Environment.vmStateDir());
            var path = Environment.vmStateDir().resolve("vm.lock");
            var channel = FileChannel.open(path,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            var lock = channel.tryLock();
            if (lock != null) return new VmLockHolder(channel, lock);

            System.err.println("Another isx process is managing the VM — waiting...");
            long deadline = System.nanoTime() + VM_LOCK_TIMEOUT_SECONDS * 1_000_000_000L;
            while (System.nanoTime() < deadline) {
                try { Thread.sleep(500); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                lock = channel.tryLock();
                if (lock != null) return new VmLockHolder(channel, lock);
            }
            channel.close();
            throw new VmException("Timed out waiting for another isx process to finish managing the VM.");
        } catch (IOException e) {
            throw new VmException("Failed to acquire VM lock: " + e.getMessage());
        }
    }

    // --- Process lifecycle ---

    public static boolean isRunning() {
        var pidFile = Environment.vmPidFile();
        if (!Files.exists(pidFile)) return false;
        try {
            long pid = Long.parseLong(Files.readString(pidFile).strip());
            var handle = ProcessHandle.of(pid);
            if (handle.isEmpty() || !handle.get().isAlive()) {
                cleanupStaleFiles();
                return false;
            }
            var cmd = handle.get().info().command().orElse("");
            if (!cmd.contains("vfkit") && !cmd.contains("qemu")) {
                cleanupStaleFiles();
                return false;
            }
            return true;
        } catch (IOException | NumberFormatException e) {
            cleanupStaleFiles();
            return false;
        }
    }

    static long readPid() {
        try {
            return Long.parseLong(Files.readString(Environment.vmPidFile()).strip());
        } catch (IOException | NumberFormatException e) {
            return -1;
        }
    }

    public static boolean start() {
        try (var ignored = acquireVmLock()) {
            return startLocked();
        } catch (VmException e) {
            System.err.println("Error: " + e.getMessage());
            return false;
        }
    }

    private static boolean startLocked() {
        if (isRunning()) {
            requireRunningExportPlanCurrent();
            BuildOutput.note("VM already running (pid=" + readPid() + ").");
            return true;
        }
        try {
            checkArtifacts();
            ensureDisk();
            ensureDataDisk();
            ensureSwap();
        } catch (VmException e) {
            System.err.println("Error: " + e.getMessage());
            return false;
        }

        var backend = detectBackend();
        int cpus = detectCpus();
        int memoryMiB = detectMemoryMiB();
        VmHostExports hostExports = backend == Backend.VFKIT ? selectedHostExports() : null;

        if (backend == Backend.VFKIT && !Files.exists(Environment.vmLogFile())) {
            System.out.println();
            BuildOutput.note("macOS may show permission dialogs for host folders and");
            BuildOutput.note("local network connectivity. These are safe to approve:");
            if (hostExports == null) {
                BuildOutput.note("  - Your home directory is mounted read-only (nothing is modified)");
            } else {
                BuildOutput.note("  - Only the worker pool's declared roots are exported");
                BuildOutput.note("  - Runtime and reference roots are enforced read-only by vfkit");
            }
            BuildOutput.note("  - Agents run in sandboxed containers that only see paths you configure");
            BuildOutput.note("  - Network access enables connectivity for the Linux containers");
        }

        BuildOutput.stepStart("Launching VM (" + backend.name().toLowerCase()
                + ", cpus=" + cpus + ", memory=" + memoryMiB + "M)...");
        try {
            Files.createDirectories(Environment.vmStateDir());
            switch (backend) {
                case VFKIT -> startVfkit(cpus, memoryMiB, hostExports);
                case QEMU -> startQemu(cpus, memoryMiB);
            }
            return true;
        } catch (Exception e) {
            BuildOutput.stepFail("Failed to start VM: " + e.getMessage());
            return false;
        }
    }

    /**
     * Restart the VM, applying any pending appliance disk update via {@link #ensureDisk()}.
     */
    public static boolean restart() {
        try (var ignored = acquireVmLock()) {
            if (!isRunning()) {
                BuildOutput.note("VM not running — starting.");
            } else {
                stopLocked();
            }
            return startLocked();
        } catch (VmException e) {
            System.err.println("Error: " + e.getMessage());
            return false;
        }
    }

    public static void stop() {
        try (var ignored = acquireVmLock()) {
            stopLocked();
        } catch (VmException e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    private static void stopLocked() {
        if (!isRunning()) {
            BuildOutput.note("VM not running.");
            cleanupStaleFiles();
            return;
        }
        long pid = readPid();
        var handle = ProcessHandle.of(pid);
        if (handle.isEmpty()) {
            BuildOutput.note("VM not running.");
            cleanupStaleFiles();
            return;
        }

        BuildOutput.stepStart("Shutting down VM...");

        // Attempt graceful shutdown via REST API (vfkit only)
        var restUriFile = Environment.vmRestUriFile();
        if (Files.exists(restUriFile)) {
            try {
                var uri = Files.readString(restUriFile).strip();
                var client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(2))
                        .build();
                var request = HttpRequest.newBuilder()
                        .uri(URI.create(uri + "/vm/state"))
                        .timeout(Duration.ofSeconds(5))
                        .POST(HttpRequest.BodyPublishers.ofString("{\"state\":\"Stop\"}"))
                        .header("Content-Type", "application/json")
                        .build();
                client.send(request, HttpResponse.BodyHandlers.discarding());
                for (int i = 0; i < 10; i++) {
                    if (!handle.get().isAlive()) break;
                    Thread.sleep(500);
                }
            } catch (Exception ignored) {}
        }

        // SIGTERM. vfkit's signal handler makes its own bounded VZ stop request,
        // so let that handler finish before escalating.
        if (handle.get().isAlive()) {
            handle.get().destroy();
            if (!awaitProcessExit(handle.get(), Duration.ofSeconds(7))) {
                handle.get().destroyForcibly();
            }
        }
        if (!awaitProcessExit(handle.get(), Duration.ofSeconds(5))) {
            throw new VmException("VM process " + pid + " did not exit after forced termination");
        }

        // Process exit closes the image descriptors, but Virtualization.framework
        // may release the corresponding storage attachments asynchronously.
        if (Platform.isMacOS()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new VmException("Interrupted while waiting for VM storage release");
            }
        }

        cleanupStaleFiles();
        VmAgentClient.clearVersionCache();
        BuildOutput.stepDone();
    }

    static boolean awaitProcessExit(ProcessHandle handle, Duration timeout) {
        if (!handle.isAlive()) return true;
        try {
            handle.onExit().get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            return true;
        } catch (java.util.concurrent.TimeoutException e) {
            return !handle.isAlive();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VmException("Interrupted while waiting for VM process " + handle.pid() + " to exit");
        } catch (java.util.concurrent.ExecutionException e) {
            throw new VmException("Failed while waiting for VM process " + handle.pid() + " to exit: "
                    + e.getCause().getMessage());
        }
    }

    public static String status() {
        var selection = WorkerPoolSelection.current();
        if (isRunning()) {
            requireRunningExportPlanCurrent();
            long pid = readPid();
            var sb = new StringBuilder();
            sb.append("VM running (pid=").append(pid).append(")");
            appendPoolStatus(sb, selection);
            var restUriFile = Environment.vmRestUriFile();
            if (Files.exists(restUriFile)) {
                try {
                    sb.append("\n  REST API: ").append(Files.readString(restUriFile).strip());
                } catch (IOException ignored) {}
            }
            sb.append("\n  Serial log: ").append(Environment.vmLogFile());
            sb.append("\n  Launch log: ").append(Environment.vmLaunchLogFile());
            var running = runningApplianceVersion();
            if (running != null) {
                sb.append("\n  Appliance: ").append(running);
                var installed = applianceVersion();
                if (!running.equals(installed)) {
                    sb.append("  (installed: ").append(installed)
                            .append(" — restart to apply)");
                }
            }
            int vsockConns = vsockForwarderConnectionCount();
            if (vsockConns >= 0) {
                sb.append("\n  vsock forwarder connections: ").append(vsockConns);
                if (vsockConns > VSOCK_CONN_WARN_THRESHOLD) {
                    sb.append("  ⚠ high — the in-VM forwarder may be leaking streams; 'isx vm restart' clears them");
                }
            }
            return sb.toString();
        }
        cleanupStaleFiles();
        var sb = new StringBuilder("VM not running");
        appendPoolStatus(sb, selection);
        if (Files.exists(Environment.vmLaunchLogFile())) {
            sb.append("\n  Last launch log: ").append(Environment.vmLaunchLogFile());
        }
        return sb.toString();
    }

    private static void appendPoolStatus(StringBuilder sb, WorkerPoolSelection selection) {
        selection.name().ifPresent(name -> {
            var pool = selection.pool().orElseThrow();
            sb.append("\n  Worker pool: ").append(name);
            sb.append("\n  Resources: cpus=").append(pool.cpus())
                    .append(", memory=").append(pool.memoryMib()).append(" MiB")
                    .append(", swap=").append(pool.swap());
            sb.append("\n  State: ").append(Environment.vmStateDir());
        });
    }

    // Above this many held vsock connections, the appliance's socat forwarder is
    // likely leaking streams (it does not reap connections whose close never
    // propagates across the vsock boundary), which degrades new-connection latency.
    public static final int VSOCK_CONN_WARN_THRESHOLD = 64;

    /**
     * Count the host-side connections currently open on the vsock Unix socket
     * (macOS only). These are held by vfkit and are reclaimed when the in-VM
     * forwarder closes its end; a steadily climbing count is the signature of the
     * forwarder leak. Returns -1 if the count can't be determined (non-macOS, no
     * socket, or lsof unavailable).
     *
     * <p>The raw lsof count includes the LISTEN fd (vfkit's listener on the socket),
     * which is always present while the VM runs. We subtract 1 so the return value
     * reflects actual forwarded connections only — a healthy idle tunnel reads 0,
     * not 1.
     */
    public static int vsockForwarderConnectionCount() {
        var sock = Environment.vmVsockSocket();
        if (!Platform.isMacOS() || !Files.exists(sock)) return -1;
        try {
            var pb = new ProcessBuilder("lsof", "-nP", "-U");
            pb.redirectErrorStream(true);
            var proc = pb.start();
            var future = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try (var r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(proc.getInputStream()))) {
                    return (int) r.lines().filter(l -> l.contains(sock.toString())).count();
                } catch (IOException e) { return -1; }
            });
            if (!proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return -1;
            }
            if (proc.exitValue() != 0) return -1;
            int raw = future.get(1, java.util.concurrent.TimeUnit.SECONDS);
            if (raw < 0) return -1;
            return Math.max(0, raw - 1);
        } catch (IOException | InterruptedException | java.util.concurrent.ExecutionException
                 | java.util.concurrent.TimeoutException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return -1;
        }
    }

    /**
     * Wait for the Incus daemon inside the VM to become reachable.
     *
     * Bounded by a wall-clock deadline, not an iteration count: each isReachable()
     * probe can take up to the connect watchdog (seconds) when the vsock is stalled,
     * so a count-based loop would silently overrun its "maxWaitSeconds" budget several
     * fold in exactly the degraded state we need to bound.
     */
    public static boolean waitUntilReady(int maxWaitSeconds) {
        long deadline = System.nanoTime() + maxWaitSeconds * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (!isRunning()) return false;
            if (IncusClient.isReachable()) return true;
            try { Thread.sleep(1000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    // Recovery budget for a VM that is up but whose Incus tunnel is not answering. The primary
    // recovery is the agent-driven forwarder restart (~1 s); the grace and backstop are short
    // bounded probes around it. This is deliberately NOT sized to outwait the forwarder's own
    // `socat -T 180` self-heal: if the agent cannot restart the forwarder and it does not clear
    // within the budget, we give up so the caller can surface an actionable error rather than
    // blocking the command for minutes.
    private static final int REACHABILITY_GRACE_SECONDS = 10;
    private static final int FORWARDER_RECOVERY_WAIT_SECONDS = 15;
    private static final int REACHABILITY_BACKSTOP_SECONDS = 30;

    /**
     * Recover reachability when the VM is running but the Incus tunnel is not answering.
     *
     * Almost always this means the vsock forwarder is wedged with leaked connections: the vfkit
     * boundary does not reliably propagate connection close, so stranded streams pin host-side fds
     * and degrade new-connection latency until the forwarder's own {@code socat -T 180} inactivity
     * backstop reaps them. Rather than block a command for that long, we give a brief transient a
     * chance to clear, then proactively restart the forwarder via the control agent — an
     * independent vsock port that answers even when the Incus tunnel is wedged, so recovery is
     * ~1 s. Restarting only drops connections that are already stalled (nothing is getting through,
     * or we would not be here), so it is safe to trigger even for other isx processes.
     *
     * <p>Before attempting a forwarder restart, the recovery path now checks which tunnel layer is
     * wedged (see {@link LeakLayer}). A host-side (vfkit) wedge cannot be fixed by restarting the
     * guest forwarder — the recovery skips the restart and fails fast with an actionable message.
     */
    private static boolean recoverReachability() {
        return recoverReachability(VmManager::waitUntilReady, VmAgentClient::restartForwarder,
                VmManager::detectLeakLayer);
    }

    /**
     * Testable core of {@link #recoverReachability()}. {@code waitUntilReady} probes reachability
     * for a given number of seconds; {@code restartForwarder} asks the control agent to restart the
     * forwarder and returns whether it confirmed; {@code detectLayer} identifies whether the wedge
     * is on the host (vfkit) or guest (forwarder) side. Split out so the grace / restart / backstop
     * orchestration can be unit-tested without a live VM.
     */
    static boolean recoverReachability(IntPredicate waitUntilReady, BooleanSupplier restartForwarder,
                                       Supplier<Optional<LeakLayer>> detectLayer) {
        // A momentary blip (e.g. the daemon finishing a burst of work) may clear on its own.
        if (waitUntilReady.test(REACHABILITY_GRACE_SECONDS)) return true;

        // Determine which layer is wedged before attempting the wrong remedy.
        var layer = detectLayer.get();
        if (layer.isPresent() && layer.get() == LeakLayer.VFKIT) {
            System.err.println("The host-side tunnel (vfkit) is wedged — a forwarder restart cannot fix this.");
            System.err.println("Run 'isx vm restart' to restore connectivity.");
            return false;
        }

        // Forwarder-layer issue or unknown layer: restart the wedged forwarder in place.
        if (restartForwarder.getAsBoolean()) {
            System.err.println("Restarted the vsock forwarder; waiting for Incus...");
            if (waitUntilReady.test(FORWARDER_RECOVERY_WAIT_SECONDS)) return true;
        } else {
            System.err.println("The control agent did not confirm a forwarder restart.");
        }

        // Bounded final probe; if this fails, ensureRunning() returns false and the caller surfaces
        // an actionable connection error (rather than blocking for the forwarder's ~180 s self-heal).
        return waitUntilReady.test(REACHABILITY_BACKSTOP_SECONDS);
    }

    /**
     * Auto-start hook: ensure VM is running and Incus is reachable.
     * Holds the VM lifecycle lock for the full check-then-start window so a concurrent
     * restart cannot race between the isRunning() check and the start attempt.
     */
    public static boolean ensureRunning() {
        try (var ignored = acquireVmLock()) {
            return ensureRunningLocked();
        } catch (VmException e) {
            System.err.println("Error: " + e.getMessage());
            return false;
        }
    }

    private static boolean ensureRunningLocked() {
        if (isRunning()) {
            requireRunningExportPlanCurrent();
            if (IncusClient.isReachable()) {
                warnIfApplianceStale();
                return true;
            }
            System.err.println("VM is running but Incus is not reachable; attempting recovery...");
            return recoverReachability();
        }

        if (!Files.exists(Environment.applianceKernel())
                || (!Files.exists(Environment.applianceDiskImage())
                    && !Files.exists(Environment.vmDiskImage()))) {
            System.err.println("Downloading VM appliance artifacts...");
            try {
                downloadArtifacts();
            } catch (IOException e) {
                System.err.println("Failed to download appliance artifacts: " + e.getMessage());
                return false;
            }
        }

        System.err.print("Starting incus-spawn VM... ");
        if (!startLocked()) return false;

        System.err.println("Waiting for Incus daemon...");
        if (waitUntilReady(60)) {
            System.err.println("VM is ready.");
            return true;
        }
        System.err.println("Warning: VM started but Incus daemon did not become reachable within 60s.");
        System.err.println("Check 'isx vm console' for boot logs.");
        return false;
    }

    private static final long SKEW_WARN_INTERVAL_MS = 60_000;

    /**
     * The version of the appliance actually running in the VM. Prefers the live agent response
     * ({@code /etc/isx-version}); falls back to the host-side {@code disk.version} file.
     */
    public static String runningApplianceVersion() {
        var agentVer = VmAgentClient.applianceVersion();
        if (agentVer.isPresent()) return agentVer.get();
        var diskVer = readVersionFile();
        return diskVer.isEmpty() ? null : diskVer;
    }

    public static String skewMessage(String running, String installed) {
        return "VM is running appliance " + running + "; " + installed
                + " is installed — run 'isx vm restart' to apply it.";
    }

    private static void warnIfApplianceStale() {
        try {
            var running = runningApplianceVersion();
            if (running == null) return;
            var installed = applianceVersion();
            if (running.equals(installed)) return;

            var marker = Environment.vmStateDir().resolve(".appliance-skew-warned");
            if (Files.exists(marker)) {
                long age = System.currentTimeMillis() - Files.getLastModifiedTime(marker).toMillis();
                if (age < SKEW_WARN_INTERVAL_MS) return;
            }

            System.err.println(skewMessage(running, installed));
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "");
        } catch (Exception ignored) {
            // Best-effort; never block a command for a stale-version warning.
        }
    }

    // --- Internal: vfkit ---

    private static VmHostExports selectedHostExports() {
        var selection = WorkerPoolSelection.current();
        if (selection.isLegacy()) return null;
        try {
            return VmHostExports.create(selection.name().orElseThrow(),
                    selection.pool().orElseThrow(), Environment.home());
        } catch (IllegalStateException e) {
            throw new VmException("Invalid worker-pool host exports: " + e.getMessage());
        }
    }

    /** Enforce the named export generation before any Incus API operation. */
    public static void requireExportPlanCurrentIfRunning() {
        if (!Platform.isMacOS() || WorkerPoolSelection.current().isLegacy() || !isRunning()) return;
        requireRunningExportPlanCurrent();
    }

    private static void requireRunningExportPlanCurrent() {
        if (!Platform.isMacOS() || WorkerPoolSelection.current().isLegacy()) return;
        try {
            selectedHostExports().requirePersistedFingerprint(Environment.vmExportPlanFingerprint());
        } catch (IllegalStateException e) {
            throw new VmException(e.getMessage());
        } catch (VmException e) {
            var name = WorkerPoolSelection.current().name().orElseThrow();
            throw new VmException("Cannot resolve the exports configured for running worker pool '"
                    + name + "': " + e.getMessage() + " Fix the configuration, then run "
                    + "'ISX_POOL=" + name + " isx vm restart'.");
        }
    }

    /**
     * Create a macOS .app bundle wrapper around the vfkit binary. macOS uses the
     * bundle's Info.plist for permission dialog text (home folder access, local
     * network), giving users meaningful descriptions instead of a generic prompt.
     */
    private static String ensureVfkitAppBundle() throws IOException {
        var macosDir = Environment.vfkitAppBundle().resolve("Contents/MacOS");
        var plistFile = Environment.vfkitAppBundle().resolve("Contents/Info.plist");
        var linkedBin = macosDir.resolve("vfkit");

        var vfkitPath = resolveVfkitPath();

        if (Files.exists(linkedBin) && Files.exists(plistFile)) {
            if (Files.isSymbolicLink(linkedBin)) {
                var target = Files.readSymbolicLink(linkedBin);
                if (target.equals(Path.of(vfkitPath))) {
                    return linkedBin.toString();
                }
            }
            Files.delete(linkedBin);
        }

        Files.createDirectories(macosDir);
        Files.writeString(plistFile, """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" \
                "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                    <key>CFBundleIdentifier</key>
                    <string>dev.incusspawn.vm</string>
                    <key>CFBundleName</key>
                    <string>incus-spawn VM</string>
                    <key>CFBundleExecutable</key>
                    <string>vfkit</string>
                    <key>NSLocalNetworkUsageDescription</key>
                    <string>incus-spawn runs Linux containers inside a lightweight \
                virtual machine on your Mac. Local network access is required so \
                that containers can reach the network and communicate with the \
                host.</string>
                    <key>NSHomeDirectoryUsageDescription</key>
                    <string>incus-spawn runs Linux containers inside a lightweight \
                virtual machine on your Mac. Legacy mode shares your home directory \
                read-only; named worker pools share only their configured roots, \
                with runtime and references read-only. Agents run inside sandboxed \
                containers, and each container only receives access to the specific \
                paths you explicitly configure.</string>
                </dict>
                </plist>
                """);
        Files.createSymbolicLink(linkedBin, Path.of(vfkitPath));
        return linkedBin.toString();
    }

    private static String resolveVfkitPath() throws IOException {
        try {
            var pb = new ProcessBuilder("which", "vfkit");
            pb.redirectErrorStream(true);
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes()).strip();
            if (process.waitFor() == 0 && !output.isBlank()) {
                return Path.of(output).toRealPath().toString();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        throw new IOException("vfkit not found in PATH. Install it with: brew install vfkit");
    }

    private static void startVfkit(int cpus, int memoryMiB, VmHostExports hostExports)
            throws IOException {
        int restPort = findFreePort();
        ensureDummyInitrd();

        var vfkitBin = ensureVfkitAppBundle();
        var cmd = vfkitCommand(vfkitBin, cpus, memoryMiB, restPort, hostExports,
                System.currentTimeMillis() / 1000);

        var launchLog = Environment.vmLaunchLogFile();
        Files.writeString(
                launchLog,
                "\n=== vfkit launch " + Instant.now() + " ===\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        var pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(launchLog.toFile()));
        var process = pb.start();
        long pid = process.pid();

        try {
            if (hostExports != null) {
                // Stock upstream vfkit rejects the companion fork's `readonly` field and exits.
                // Do not record a plan for a process that failed immediately during launch.
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while checking vfkit launch", e);
                }
                if (!process.isAlive()) {
                    throw new IOException("vfkit exited during launch. Named worker pools require "
                            + "the incus-spawn vfkit fork with virtio-fs readonly support; upstream "
                            + "vfkit is intentionally unsupported.");
                }
            }
            // Record process ownership before ancillary launch state, so an interruption can still
            // identify and stop the vfkit process rather than leaving an untracked appliance.
            Files.writeString(Environment.vmPidFile(), String.valueOf(pid));
            Files.writeString(Environment.vmRestUriFile(), "http://localhost:" + restPort);
            if (hostExports != null) {
                hostExports.persistFingerprint(Environment.vmExportPlanFingerprint());
            }
        } catch (IOException | RuntimeException e) {
            process.destroyForcibly();
            cleanupStaleFiles();
            throw e;
        }
        BuildOutput.stepDone("pid=" + pid + ", rest=localhost:" + restPort);
    }

    /** Build the complete vfkit argv without requiring macOS, for launch-policy unit tests. */
    static List<String> vfkitCommand(
            String vfkitBin,
            int cpus,
            int memoryMiB,
            int restPort,
            VmHostExports hostExports,
            long epochSeconds) {
        validateVfkitDevicePaths(hostExports);

        var cmd = new ArrayList<>(List.of(
                vfkitBin,
                "--cpus", String.valueOf(cpus),
                "--memory", String.valueOf(memoryMiB),
                "--kernel", Environment.applianceKernel().toString(),
                "--initrd", Environment.vmDummyInitrd().toString(),
                "--kernel-cmdline", kernelCmdline("hvc0", hostExports, epochSeconds),
                "--device", "virtio-blk,path=" + Environment.vmDiskImage(),
                "--device", "virtio-blk,path=" + Environment.vmSwapImage(),
                "--device", "virtio-blk,path=" + Environment.vmDataImage(),
                "--device", "virtio-net,nat,mac=" + VmNetwork.selectedMac(),
                "--device", "virtio-serial,logFilePath=" + Environment.vmLogFile()
        ));
        if (hostExports == null) {
            cmd.addAll(List.of("--device", "virtio-fs,sharedDir="
                    + System.getProperty("user.home") + ",mountTag=hostfs"));
        } else {
            for (var export : hostExports.exports()) {
                cmd.add("--device");
                cmd.add("virtio-fs,sharedDir=" + export.hostPath()
                        + ",mountTag=" + export.mountTag()
                        + (export.readOnly() ? ",readonly" : ""));
            }
        }
        cmd.addAll(List.of(
                "--device", "virtio-vsock,port=" + INCUS_VSOCK_PORT
                        + ",socketURL=" + Environment.vmVsockSocket() + ",connect",
                "--device", "virtio-vsock,port=" + AGENT_VSOCK_PORT
                        + ",socketURL=" + Environment.vmAgentSocket() + ",connect",
                "--timesync", "vsockPort=" + GA_VSOCK_PORT,
                "--restful-uri", "tcp://localhost:" + restPort
        ));
        return List.copyOf(cmd);
    }

    private static void validateVfkitDevicePaths(VmHostExports hostExports) {
        VmHostExports.requireVfkitSafePath(Environment.vmDiskImage().toString());
        VmHostExports.requireVfkitSafePath(Environment.vmSwapImage().toString());
        VmHostExports.requireVfkitSafePath(Environment.vmDataImage().toString());
        VmHostExports.requireVfkitSafePath(Environment.vmLogFile().toString());
        VmHostExports.requireVfkitSafePath(Environment.vmVsockSocket().toString());
        VmHostExports.requireVfkitSafePath(Environment.vmAgentSocket().toString());
        if (hostExports == null) {
            VmHostExports.requireVfkitSafePath(System.getProperty("user.home"));
        } else {
            hostExports.exports().forEach(export ->
                    VmHostExports.requireVfkitSafePath(export.hostPath().toString()));
        }
    }

    // --- Internal: QEMU ---

    private static void startQemu(int cpus, int memoryMiB) throws IOException {
        var arch = normalizeArch();
        var qemuBin = "qemu-system-" + arch;
        String console;
        var machineArgs = new ArrayList<String>();

        switch (arch) {
            case "x86_64" -> {
                console = "ttyS0";
                if (Files.exists(Path.of("/dev/kvm"))) {
                    machineArgs.addAll(List.of("-machine", "pc", "-cpu", "host", "-enable-kvm"));
                } else {
                    machineArgs.addAll(List.of("-machine", "pc", "-cpu", "qemu64"));
                }
            }
            case "aarch64" -> {
                console = "ttyAMA0";
                if (Files.exists(Path.of("/dev/kvm"))) {
                    machineArgs.addAll(List.of("-machine", "virt", "-cpu", "host", "-enable-kvm"));
                } else {
                    machineArgs.addAll(List.of("-machine", "virt", "-cpu", "cortex-a57"));
                }
            }
            default -> throw new VmException("Unsupported architecture: " + arch);
        }

        var cmd = new ArrayList<String>();
        cmd.add(qemuBin);
        cmd.addAll(machineArgs);
        cmd.addAll(List.of(
                "-m", String.valueOf(memoryMiB),
                "-smp", String.valueOf(cpus),
                "-nographic",
                "-nodefaults",
                "-serial", "stdio",
                "-kernel", Environment.applianceKernel().toString(),
                "-drive", "id=root,file=" + Environment.vmDiskImage() + ",format=raw,if=virtio",
                "-drive", "id=swap,file=" + Environment.vmSwapImage() + ",format=raw,if=virtio",
                "-drive", "id=data,file=" + Environment.vmDataImage() + ",format=raw,if=virtio",
                "-netdev", "user,id=net0",
                "-device", "virtio-net-pci,netdev=net0",
                "-append", kernelCmdline(console)
        ));

        var pb = new ProcessBuilder(cmd);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(Environment.vmLogFile().toFile()));
        pb.redirectErrorStream(true);
        var process = pb.start();
        long pid = process.pid();

        Files.writeString(Environment.vmPidFile(), String.valueOf(pid));
        BuildOutput.stepDone("pid=" + pid);
    }

    // --- Internal: disk management ---

    static void checkArtifacts() {
        if (!Files.exists(Environment.applianceKernel())) {
            throw new VmException(Environment.applianceKernel() + " not found.\n"
                    + "Run 'isx init' to download appliance artifacts, or set ISX_APPLIANCE_DIR.");
        }
        boolean hasDiskVersion = Files.exists(Environment.vmDiskVersion());
        boolean needsReExtract = (hasDiskVersion && !applianceVersion()
                .equals(readVersionFile()))
                || (Files.exists(Environment.vmDiskImage()) && !hasDiskVersion);
        if (needsReExtract || (!Files.exists(Environment.vmDiskImage())
                && !Files.exists(Environment.applianceDiskImage()))) {
            if (!Files.exists(Environment.applianceDiskImage())) {
                throw new VmException("No disk image found.\n"
                        + "Run 'isx init' to download appliance artifacts, or set ISX_APPLIANCE_DIR.");
            }
        }
    }

    private static String readVersionFile() {
        try {
            return Files.readString(Environment.vmDiskVersion()).strip();
        } catch (IOException e) {
            return "";
        }
    }

    static void ensureDisk() {
        var currentVersion = applianceVersion();
        var versionFile = Environment.vmDiskVersion();
        var tmp = Environment.vmDiskImage().resolveSibling("disk.img.tmp");

        // Clean up incomplete extraction from a prior crash.
        try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}

        if (Files.exists(Environment.vmDiskImage())) {
            try {
                if (Files.exists(versionFile)) {
                    var diskVersion = Files.readString(versionFile).strip();
                    if (diskVersion.equals(currentVersion)) return;
                    BuildOutput.step("Replacing appliance root disk ("
                            + diskVersion + " → " + currentVersion + ")");
                } else {
                    BuildOutput.step("Replacing appliance root disk (untracked → "
                            + currentVersion + ")");
                }
                Files.delete(Environment.vmDiskImage());
                Files.deleteIfExists(versionFile);
            } catch (IOException e) {
                return;
            }
            try {
                downloadArtifacts();
            } catch (IOException e) {
                System.err.println("Warning: could not re-download appliance artifacts: "
                        + e.getMessage());
            }
        }

        var compressed = Environment.applianceDiskImage();
        if (!Files.exists(compressed)) {
            throw new VmException("disk.img.gz not found at " + compressed
                    + "\nRun 'isx init' to download appliance artifacts.");
        }

        BuildOutput.stepStart("Extracting root disk...");
        try {
            Files.createDirectories(Environment.vmStateDir());
            try (var gzIn = new GZIPInputStream(Files.newInputStream(compressed), 64 * 1024);
                 var out = Files.newOutputStream(tmp)) {
                gzIn.transferTo(out);
            }
            try (var raf = new RandomAccessFile(tmp.toFile(), "rw")) {
                raf.setLength(parseDiskSize(rootDiskSize()));
                raf.getFD().sync();
            }
            validateBtrfsMagic(tmp);
            Files.writeString(versionFile, currentVersion);
            Files.move(tmp, Environment.vmDiskImage(), StandardCopyOption.ATOMIC_MOVE);
            BuildOutput.stepDone(humanSize(Files.size(Environment.vmDiskImage()))
                    + ", " + rootDiskSize() + " virtual");
        } catch (IOException e) {
            BuildOutput.stepBreak();
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            throw new VmException("Failed to extract disk image: " + e.getMessage());
        }
    }

    private static final long BTRFS_SUPERBLOCK_MAGIC_OFFSET = 0x10040;
    private static final byte[] BTRFS_MAGIC = "_BHRfS_M".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    public static void validateBtrfsMagic(Path diskImage) throws IOException {
        var magic = new byte[BTRFS_MAGIC.length];
        try (var raf = new RandomAccessFile(diskImage.toFile(), "r")) {
            if (raf.length() < BTRFS_SUPERBLOCK_MAGIC_OFFSET + BTRFS_MAGIC.length) {
                throw new IOException("disk image too small for a btrfs superblock ("
                        + raf.length() + " bytes)");
            }
            raf.seek(BTRFS_SUPERBLOCK_MAGIC_OFFSET);
            raf.readFully(magic);
        }
        if (!java.util.Arrays.equals(magic, BTRFS_MAGIC)) {
            throw new IOException("disk image has no valid btrfs superblock");
        }
    }

    static void ensureDataDisk() {
        var dataImage = Environment.vmDataImage();
        if (Files.exists(dataImage)) return;

        BuildOutput.stepStart("Creating data disk (" + diskSize() + " sparse)...");
        try {
            Files.createDirectories(Environment.vmStateDir());
            try (var raf = new RandomAccessFile(dataImage.toFile(), "rw")) {
                raf.setLength(parseDiskSize(diskSize()));
            }
            BuildOutput.stepDone();
        } catch (IOException e) {
            BuildOutput.stepBreak();
            throw new VmException("Failed to create data disk: " + e.getMessage());
        }
    }

    /**
     * Current logical size of the data-disk image in bytes, or -1 if it does not exist.
     * An I/O error on an <em>existing</em> image is surfaced as a {@link VmException} rather than
     * folded into -1, so callers can distinguish "no disk yet" from "disk present but unreadable".
     */
    public static long dataDiskSizeBytes() {
        var dataImage = Environment.vmDataImage();
        if (!Files.exists(dataImage)) return -1;
        try {
            return Files.size(dataImage);
        } catch (IOException e) {
            throw new VmException("Could not read data disk size at " + dataImage + ": " + e.getMessage());
        }
    }

    /**
     * Grow the appliance data disk — the sparse raw image at {@link Environment#vmDataImage()}
     * that the guest exposes as {@code /dev/vdc} and mounts at {@code /var/lib/incus} to back the
     * btrfs {@code cow} storage pool. Grow-only: {@code newSize} must exceed the current size.
     *
     * This only extends the host-side image (a sparse {@code setLength}, so no host blocks are
     * allocated up front); the guest expands btrfs to fill the larger device on its next boot
     * (see the appliance rcS "btrfs filesystem resize max /var/lib/incus"). The VM must be
     * stopped first so it re-reads the device geometry on restart.
     *
     * @return the new size in bytes
     */
    public static long resizeDataDisk(String newSize) {
        var dataImage = Environment.vmDataImage();
        if (!Files.exists(dataImage)) {
            throw new VmException("No data disk found at " + dataImage
                    + "\nRun 'isx vm start' once to create it before resizing.");
        }
        if (isRunning()) {
            throw new VmException("VM is running. Stop it ('isx vm stop') before resizing the data disk.");
        }
        long target = parseDiskSize(newSize);
        long current;
        try {
            current = Files.size(dataImage);
        } catch (IOException e) {
            throw new VmException("Could not read current data disk size: " + e.getMessage());
        }
        if (target <= current) {
            throw new VmException("New size (" + humanSize(target) + ") must be larger than the current size ("
                    + humanSize(current) + "). Shrinking is not supported.");
        }
        try (var raf = new RandomAccessFile(dataImage.toFile(), "rw")) {
            raf.setLength(target);
        } catch (IOException e) {
            throw new VmException("Failed to resize data disk: " + e.getMessage());
        }
        return target;
    }

    static void ensureSwap() {
        var swapImage = Environment.vmSwapImage();
        if (Files.exists(swapImage)) return;

        try {
            Files.createDirectories(Environment.vmStateDir());
            try (var raf = new RandomAccessFile(swapImage.toFile(), "rw")) {
                raf.setLength(parseDiskSize(swapSize()));
            }
        } catch (IOException e) {
            throw new VmException("Failed to create swap image: " + e.getMessage());
        }
    }

    /**
     * Download vmlinuz and disk.img.gz from the GitHub release matching
     * the current isx version. Re-downloads when the version changes —
     * a stale disk.img.gz would produce a disk.img missing features
     * added in newer releases.
     */
    public static void downloadArtifacts() throws IOException {
        var version = applianceVersion();
        var arch = normalizeArch();
        var baseUrl = "https://github.com/Sanne/incus-spawn/releases/download/v" + version;

        Files.createDirectories(Environment.applianceDir());

        var versionFile = Environment.applianceDir().resolve("version");
        boolean versionMatch = false;
        if (Files.exists(versionFile)) {
            try {
                versionMatch = Files.readString(versionFile).strip().equals(version);
            } catch (IOException ignored) {}
        }
        if (!versionMatch) {
            Files.deleteIfExists(Environment.applianceKernel());
            Files.deleteIfExists(Environment.applianceDiskImage());
        }

        var cache = new DownloadCache();

        if (!Files.exists(Environment.applianceKernel())) {
            downloadStep(cache, "Downloading vmlinuz (" + arch + ")...",
                    baseUrl + "/vmlinuz-" + arch, Environment.applianceKernel());
        }

        if (!Files.exists(Environment.applianceDiskImage())) {
            downloadStep(cache, "Downloading disk image (" + arch + ")...",
                    baseUrl + "/disk-" + arch + ".img.gz", Environment.applianceDiskImage());
        }

        Files.writeString(versionFile, version);
    }

    /**
     * Run one download as a single terminal step, closing its own dangling step line on failure
     * before propagating. Because only the code that printed the {@code stepStart} knows a line is
     * outstanding, owning the {@code stepBreak} here lets callers report the error with a plain
     * message rather than each guessing whether a line needs terminating.
     */
    private static void downloadStep(DownloadCache cache, String label, String url, java.nio.file.Path dest)
            throws IOException {
        BuildOutput.stepStart(label);
        try {
            var cached = cache.download(url, null);
            Files.copy(cached, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            BuildOutput.stepBreak();
            throw e;
        }
        BuildOutput.stepDone();
    }

    public static long parseDiskSize(String size) {
        size = size.strip().toUpperCase();
        long multiplier = 1;
        if (size.endsWith("G")) {
            multiplier = 1024L * 1024 * 1024;
            size = size.substring(0, size.length() - 1);
        } else if (size.endsWith("M")) {
            multiplier = 1024L * 1024;
            size = size.substring(0, size.length() - 1);
        } else if (size.endsWith("T")) {
            multiplier = 1024L * 1024 * 1024 * 1024;
            size = size.substring(0, size.length() - 1);
        }
        try {
            return Long.parseLong(size) * multiplier;
        } catch (NumberFormatException e) {
            throw new VmException("Invalid disk size: '" + size
                    + "'. Expected a number with optional G, M, or T suffix (e.g., 60G, 512M).");
        }
    }

    // --- Internal: helpers ---

    private static String kernelCmdline(String console) {
        return kernelCmdline(console, null, System.currentTimeMillis() / 1000);
    }

    private static String kernelCmdline(
            String console, VmHostExports hostExports, long epochSeconds) {
        // mitigations=off is compiled in (CONFIG_CPU_MITIGATIONS=n), so it is
        // not repeated here. rootfstype=btrfs avoids the kernel probing fuseblk
        // (which rejects the 'commit' rootflag) before btrfs at root mount.
        var cmdline = "root=/dev/vda rootfstype=btrfs rw rootflags=commit=300 console=" + console
                + " isx.gateway=" + gatewayIp()
                + " isx.mitm_port=" + mitmPort()
                + " isx.time=" + epochSeconds
                + " isx.ga_vsock=" + GA_VSOCK_PORT
                + " isx.vsock_incus=" + INCUS_VSOCK_PORT
                + " isx.agent_vsock=" + AGENT_VSOCK_PORT
                + " isx.proxy=remote"
                + " isx.shared=" + (hostExports == null ? "/host" : "/host/runtime");
        if (hostExports != null) {
            cmdline += " isx.host_exports=named isx.reference_names="
                    + String.join(",", hostExports.referenceNames());
        }
        return cmdline;
    }

    private static void ensureDummyInitrd() throws IOException {
        var path = Environment.vmDummyInitrd();
        if (Files.exists(path)) return;
        Files.createDirectories(path.getParent());
        // Minimal empty cpio archive, gzipped (vfkit requires --initrd even if unused)
        var baos = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(baos)) {
            // Empty CPIO newc archive: just the trailer
            var trailer = "070701"                // magic
                    + "00000000"                   // ino
                    + "00000000"                   // mode
                    + "00000000"                   // uid
                    + "00000000"                   // gid
                    + "00000001"                   // nlink
                    + "00000000"                   // mtime
                    + "00000000"                   // filesize
                    + "00000000"                   // devmajor
                    + "00000000"                   // devminor
                    + "00000000"                   // rdevmajor
                    + "00000000"                   // rdevminor
                    + "0000000B"                   // namesize (11 = "TRAILER!!!\0")
                    + "00000000"                   // checksum
                    + "TRAILER!!!\0";              // name
            gzip.write(trailer.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
        Files.write(path, baos.toByteArray());
    }

    private static int findFreePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new VmException("Could not find a free port for vfkit REST API");
        }
    }

    private static String normalizeArch() {
        var arch = System.getProperty("os.arch");
        if ("amd64".equals(arch)) return "x86_64";
        if ("arm64".equals(arch)) return "aarch64";
        return arch;
    }

    private static boolean commandExists(String command) {
        try {
            var pb = new ProcessBuilder("which", command);
            pb.redirectErrorStream(true);
            return pb.start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void cleanupStaleFiles() {
        try { Files.deleteIfExists(Environment.vmPidFile()); } catch (IOException ignored) {}
        try { Files.deleteIfExists(Environment.vmRestUriFile()); } catch (IOException ignored) {}
        try { Files.deleteIfExists(Environment.vmVsockSocket()); } catch (IOException ignored) {}
        try { Files.deleteIfExists(Environment.vmAgentSocket()); } catch (IOException ignored) {}
        if (!WorkerPoolSelection.current().isLegacy()) {
            try { Files.deleteIfExists(Environment.vmExportPlanFingerprint()); } catch (IOException ignored) {}
        }
    }

    public static String humanSize(long bytes) {
        if (bytes >= 1024 * 1024 * 1024) {
            return String.format("%.1fG", bytes / (1024.0 * 1024 * 1024));
        }
        if (bytes >= 1024 * 1024) {
            return String.format("%.1fM", bytes / (1024.0 * 1024));
        }
        return bytes + "B";
    }
}
