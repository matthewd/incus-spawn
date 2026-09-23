package dev.incusspawn.proxy;

import dev.incusspawn.Environment;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.Platform;
import dev.incusspawn.config.WorkerPoolSelection;
import dev.incusspawn.vm.VmManager;
import dev.incusspawn.vm.VmNetwork;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

public final class ProxyService {

    private static final String SERVICE_NAME = Environment.PROXY_SERVICE_NAME;

    /**
     * Exit code for a fatal misconfiguration — a condition no amount of retrying can fix,
     * because it needs a human to change something (run init, fill in a config field).
     * {@code isx proxy start} returns it; the systemd unit below names it in
     * {@code RestartPreventExitStatus} so such a failure stops immediately instead of
     * crash-looping with the reason buried in the journal. Transient failures — Incus or the VM
     * not up yet — must keep returning 1 so the restart loop can do its job.
     * <p>
     * Value is {@code EX_CONFIG} from sysexits.h; it does not collide with the 1 and 2 returned
     * by other {@code isx proxy} subcommands.
     */
    public static final int EXIT_CONFIG = 78;

    /** The unit directive that makes {@link #EXIT_CONFIG} non-restartable. */
    static final String RESTART_PREVENT_LINE = "RestartPreventExitStatus=" + EXIT_CONFIG;

    private ProxyService() {}

    // --- Lifecycle lock ---

    private static class ProxyLockHolder implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        ProxyLockHolder(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }
        @Override public void close() {
            try { lock.release(); } catch (IOException ignored) {}
            try { channel.close(); } catch (IOException ignored) {}
        }
    }

    private static final int PROXY_LOCK_TIMEOUT_SECONDS = 30;

    private static ProxyLockHolder acquireProxyLock() {
        try {
            Files.createDirectories(Environment.configDir());
            var path = Environment.configDir().resolve("proxy.lock");
            var channel = FileChannel.open(path,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            var lock = channel.tryLock();
            if (lock != null) return new ProxyLockHolder(channel, lock);

            System.err.println("Another isx process is managing the proxy — waiting...");
            long deadline = System.nanoTime() + PROXY_LOCK_TIMEOUT_SECONDS * 1_000_000_000L;
            while (System.nanoTime() < deadline) {
                try { Thread.sleep(500); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                lock = channel.tryLock();
                if (lock != null) return new ProxyLockHolder(channel, lock);
            }
            channel.close();
            throw new RuntimeException("Timed out waiting for another isx process to finish managing the proxy.");
        } catch (IOException e) {
            throw new RuntimeException("Failed to acquire proxy lock: " + e.getMessage());
        }
    }

    /** The systemd unit written by {@link #install()}. Package-private so tests can assert on it. */
    static String serviceUnitContent() {
        return """
                [Unit]
                Description=incus-spawn MITM authentication proxy
                After=incus.service

                [Service]
                Type=simple
                %s
                Restart=on-failure
                %s
                RestartSec=5

                [Install]
                WantedBy=default.target
                """.formatted(execStartLine(), RESTART_PREVENT_LINE);
    }

    public static boolean isInstalled() {
        if (Platform.isMacOS()) return isMacOsServiceInstalled();
        return Files.exists(Environment.proxyServiceFile());
    }

    /**
     * True when the service gave up because of a misconfiguration rather than a transient
     * failure — it exited {@link #EXIT_CONFIG} and systemd declined to restart it. Callers use
     * this to report the actual cause instead of a generic "did not become healthy", which
     * points at the network and hides the real problem.
     * <p>
     * Always false on macOS: launchd's {@code KeepAlive} cannot express a per-exit-code restart
     * policy, so the condition this detects does not arise there.
     */
    public static boolean failedWithConfigError() {
        if (Platform.isMacOS()) return false;
        // Both properties in one invocation. Without --value the output is self-describing
        // ("ActiveState=failed\nExecMainStatus=78"), so neither value is read positionally.
        var shown = showProperties("ActiveState", "ExecMainStatus");
        return shown != null
                && shown.contains("ActiveState=failed")
                && shown.contains("ExecMainStatus=" + EXIT_CONFIG);
    }

    /** Read systemd unit properties as {@code key=value} lines, or null if unavailable. */
    private static String showProperties(String... properties) {
        var command = new ArrayList<>(List.of("systemctl", "--user", "show", SERVICE_NAME));
        for (var property : properties) {
            command.add("-p");
            command.add(property);
        }
        try {
            var pb = new ProcessBuilder(command);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes()).strip();
            return process.waitFor() == 0 ? output : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isActive() {
        if (Platform.isMacOS()) return isMacOsServiceActive();
        try {
            var pb = new ProcessBuilder("systemctl", "--user", "is-active", SERVICE_NAME);
            pb.redirectErrorStream(true);
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes()).strip();
            return process.waitFor() == 0 && "active".equals(output);
        } catch (Exception e) {
            return false;
        }
    }

    private static final int REQUIRED_JAVA_MAJOR = 25;

    public static boolean install() {
        try (var ignored = acquireProxyLock()) {
            return installLocked();
        }
    }

    private static boolean installLocked() {
        if (Platform.isMacOS()) {
            return installMacOs();
        }

        var isxPath = resolveIsxPath();
        if (isxPath == null) {
            System.err.println("Could not find 'isx' in PATH.");
            return false;
        }

        var javaIssue = checkJvmWrapper(isxPath);
        if (javaIssue != null) {
            System.err.println(javaIssue);
            System.err.println("The proxy requires a native binary. Reinstall isx using your package manager, or: curl -fsSL https://isx.run | sh");
            System.err.println("Then run: isx init");
            return false;
        }

        var serviceContent = serviceUnitContent();

        try {
            writeProxyStartScript(proxyStartScript(), isxPath);
            Files.createDirectories(Environment.proxyServiceFile().getParent());
            Files.writeString(Environment.proxyServiceFile(), serviceContent);
        } catch (IOException e) {
            System.err.println("Failed to write service file: " + e.getMessage());
            return false;
        }

        System.out.println("Service file written to " + Environment.proxyServiceFile());
        System.out.println("Enabling and starting proxy service...");
        runQuiet("systemctl", "--user", "daemon-reload");
        runQuiet("systemctl", "--user", "enable", "--now", SERVICE_NAME);

        System.out.println("Enabling lingering for user (sudo required)...");
        runQuiet("sudo", "loginctl", "enable-linger", System.getProperty("user.name"));

        if (isActive()) {
            if (ProxyHealthCheck.awaitHealthy(5)) {
                System.out.println("Proxy service is running.");
                return true;
            }
            System.err.println("Warning: service started but proxy is not responding.");
            printServiceLogs();
            return false;
        } else {
            System.err.println("Warning: service did not start.");
            printServiceLogs();
            return false;
        }
    }

    public static boolean uninstall() {
        try (var ignored = acquireProxyLock()) {
            return uninstallLocked();
        }
    }

    private static boolean uninstallLocked() {
        if (Platform.isMacOS()) {
            uninstallMacOs();
            return true;
        }
        if (!isInstalled()) {
            System.err.println("Proxy service is not installed.");
            return false;
        }

        System.out.println("Stopping and disabling proxy service...");
        runQuiet("systemctl", "--user", "stop", SERVICE_NAME);
        runQuiet("systemctl", "--user", "disable", SERVICE_NAME);

        try {
            Files.deleteIfExists(Environment.proxyServiceFile());
            Files.deleteIfExists(proxyStartScript());
        } catch (IOException e) {
            System.err.println("Failed to remove service files: " + e.getMessage());
            return false;
        }

        runQuiet("systemctl", "--user", "daemon-reload");
        System.out.println("Proxy service uninstalled.");
        return true;
    }

    public static boolean restart() {
        try (var ignored = acquireProxyLock()) {
            return restartLocked();
        }
    }

    public static boolean restart(java.util.function.Consumer<String> log) {
        try (var ignored = acquireProxyLock()) {
            return restartLocked(log);
        }
    }

    private static boolean restartLocked() {
        return restartLocked(System.err::println);
    }

    private static boolean restartLocked(java.util.function.Consumer<String> log) {
        ProxyLog.info("Service restarting");
        log.accept("Restarting proxy service...");
        if (Platform.isMacOS()) {
            var uid = getUid();
            runQuiet("launchctl", "bootout", "gui/" + uid + "/" + PROXY_LABEL);
            waitForProxyExit();
            runQuiet("launchctl", "bootstrap", "gui/" + uid, proxyPlistFile().toString());
        } else {
            // A unit halted by RestartPreventExitStatus sits in 'failed' state, and repeated
            // restart attempts can trip systemd's start rate limit — reset makes recovery
            // unconditional once the user has fixed the config. No-op when the unit is healthy.
            runQuiet("systemctl", "--user", "reset-failed", SERVICE_NAME);
            runQuiet("systemctl", "--user", "restart", SERVICE_NAME);
        }
        if (isActive()) {
            log.accept("Proxy service restarted.");
            return true;
        }
        log.accept("Warning: proxy service did not restart.");
        return false;
    }

    /**
     * Start the proxy through the service manager when it is installed but not
     * currently active. Returns true if the service is running afterward.
     */
    public static boolean startService() {
        if (!isInstalled()) return false;
        if (isActive()) return true;
        try (var ignored = acquireProxyLock()) {
            if (isActive()) return true;
            if (Platform.isMacOS()) {
                var uid = getUid();
                runQuiet("launchctl", "bootstrap", "gui/" + uid, proxyPlistFile().toString());
                runQuiet("launchctl", "kickstart", "gui/" + uid + "/" + PROXY_LABEL);
            } else {
                runQuiet("systemctl", "--user", "reset-failed", SERVICE_NAME);
                runQuiet("systemctl", "--user", "start", SERVICE_NAME);
            }
            return isActive();
        }
    }

    public static void stop() {
        try (var ignored = acquireProxyLock()) {
            stopLocked();
        }
    }

    private static void stopLocked() {
        if (isActive()) {
            System.out.println("Stopping proxy service...");
            if (Platform.isMacOS()) {
                runQuiet("launchctl", "bootout", "gui/" + getUid() + "/" + PROXY_LABEL);
                System.out.println("Proxy service stopped (re-enable with: isx proxy install).");
            } else {
                runQuiet("systemctl", "--user", "stop", SERVICE_NAME);
                System.out.println("Proxy service stopped.");
            }
            return;
        }

        var pid = findProxyPid();
        if (pid != -1) {
            System.out.println("Stopping proxy (PID " + pid + ")...");
            runQuiet("kill", String.valueOf(pid));
            System.out.println("Proxy stopped.");
            return;
        }

        System.out.println("Proxy is not running.");
    }

    /**
     * Check whether the installed service needs updating (binary path or version)
     * and restart if so. Returns true if a restart was performed.
     */
    public static boolean reinstallIfChanged(IncusClient incus) {
        try (var ignored = acquireProxyLock()) {
            boolean needsReinstall;
            if (Platform.isMacOS()) {
                needsReinstall = needsMacOsPlistUpdate();
            } else {
                needsReinstall = regenerateServiceFiles();
            }

            if (!needsReinstall) {
                var info = ProxyHealthCheck.fetchProxyInfo(ProxyHealthCheck.healthAddress(incus));
                needsReinstall = !ProxyHealthCheck.checkDrift(info).isEmpty();
            }

            if (needsReinstall) {
                if (Platform.isMacOS() && !updateMacOsProxyPlist()) {
                    return false;
                }
                return restartLocked();
            }
            return false;
        }
    }

    /**
     * Bring both on-disk service files — the systemd unit and the start script it execs — into
     * line with what this build would write, rewriting whichever differs. Returns true if either
     * was rewritten, meaning the caller must restart the service.
     * <p>
     * The two files are checked independently because <b>they do not carry the same information</b>.
     * {@link #execStartLine()} names the start script, at a fixed path, so the unit text is
     * identical for every installation on every machine; the path to the {@code isx} binary
     * appears only inside the start script. Comparing the unit alone therefore cannot detect a
     * binary that moved, which is exactly what happens on an upgrade that changes where {@code isx}
     * lives (a distro package landing in {@code /usr/bin} over a previous {@code ~/.local/bin}
     * install, or the reverse). Before this checked the script too, the unit always compared equal,
     * the script was never refreshed, and the service went on exec'ing a binary from the previous
     * installation indefinitely — while version-drift detection dutifully restarted that same stale
     * binary and reported success.
     * <p>
     * Regenerating rather than patching means each file picks up every future change to its
     * template for free. Patching individual directives instead meant a new helper per directive,
     * in each of the places that knew the unit's shape, which is how {@code RestartPreventExitStatus}
     * came to be missing from this path.
     * <p>
     * Safe to overwrite: {@link #install()} already writes both files wholesale, and systemd's
     * supported customization mechanism is a drop-in ({@code <unit>.d/override.conf}), a separate
     * file this never touches.
     */
    private static boolean regenerateServiceFiles() {
        if (Platform.isMacOS() || !Files.exists(Environment.proxyServiceFile())) return false;
        var isxPath = resolveIsxPath();
        if (isxPath == null) return false;
        try {
            var changed = false;

            var script = proxyStartScript();
            if (startScriptIsStale(script, isxPath)) {
                writeProxyStartScript(script, isxPath);
                changed = true;
            }

            var expectedUnit = serviceUnitContent();
            if (!expectedUnit.equals(Files.readString(Environment.proxyServiceFile()))) {
                Files.writeString(Environment.proxyServiceFile(), expectedUnit);
                // Only the unit is systemd's to parse; a start script change needs a restart
                // (handled by the caller) but not a reload.
                runQuiet("systemctl", "--user", "daemon-reload");
                changed = true;
            }

            return changed;
        } catch (IOException e) {
            System.err.println("Warning: could not update proxy service files: " + e.getMessage());
            return false;
        }
    }

    /**
     * True when the start script is missing or does not exec {@code isxPath} — i.e. when the
     * service would otherwise keep running a binary from a previous installation.
     * <p>
     * Only the exec'd binary matters for staleness. PATH changes across sessions must not trigger
     * restarts — users with conda/nvm/sdkman get different PATHs per session, and each would
     * cause a spurious proxy restart that interrupts in-flight proxied requests. PATH is refreshed
     * whenever the script is rewritten for binary-path reasons or on explicit reinstall.
     */
    static boolean startScriptIsStale(Path script, String isxPath) throws IOException {
        if (!Files.exists(script)) return true;
        var content = Files.readString(script);
        if (!content.contains("export PATH=")) return true;
        if (!content.contains(execCommand(isxPath))) return true;
        if (Platform.isLinux() && !content.contains("command -v sg")) return true;
        return false;
    }

    public static boolean upgradeIfNeeded() {
        try (var ignored = acquireProxyLock()) {
            if (Platform.isMacOS()) {
                if (!WorkerPoolSelection.current().isLegacy()
                        && !removeLegacyVmLaunchAgent(getUid())) {
                    return false;
                }
                // An explicit install/upgrade refreshes global state from the running selection;
                // when no appliance is reachable this validates the persisted fallback.
                if (resolveMacOsGatewayIp() == null) return false;
                if (needsMacOsPlistUpdate()) {
                    if (!updateMacOsProxyPlist()) return false;
                    return restartLocked();
                }
                return true;
            }
            if (regenerateServiceFiles()) {
                System.out.println("Updated proxy service configuration.");
                runQuiet("systemctl", "--user", "restart", SERVICE_NAME);
            }
            return true;
        }
    }

    private static void waitForProxyExit() {
        for (int i = 0; i < 30; i++) {
            if (!ProxyHealthCheck.isHealthy("127.0.0.1")) return;
            try { Thread.sleep(200); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static long findProxyPid() {
        try {
            var pb = new ProcessBuilder("fuser", ProxyConfig.DEFAULT_HEALTH_PORT + "/tcp");
            pb.redirectErrorStream(false);
            var process = pb.start();
            // fuser sends port label to stderr, PIDs to stdout
            var stdout = new String(process.getInputStream().readAllBytes()).strip();
            process.getErrorStream().readAllBytes();
            if (process.waitFor() == 0 && !stdout.isBlank()) {
                return Long.parseLong(stdout.split("\\s+")[0]);
            }
        } catch (Exception ignored) {}
        return -1;
    }

    /**
     * If isx is a JVM wrapper script, validate that the embedded Java binary exists
     * and is the required version. Returns a diagnostic message on failure, null if OK.
     */
    static String checkJvmWrapper(String isxPath) {
        try {
            var content = Files.readString(Path.of(isxPath));
            if (!content.startsWith("#!/bin/bash")) return null; // native binary
            var matcher = java.util.regex.Pattern.compile("exec\\s+\"?([^\"\\s]+)\"?\\s+.*-jar")
                    .matcher(content);
            if (!matcher.find()) return null; // not a java wrapper
            var javaBin = matcher.group(1);

            if (!Files.isExecutable(Path.of(javaBin))) {
                return "Java binary not found: " + javaBin + "\n"
                        + "The installed 'isx' is a JVM wrapper that requires Java " + REQUIRED_JAVA_MAJOR + "+.";
            }

            var pb = new ProcessBuilder(javaBin, "-version");
            pb.redirectErrorStream(true);
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes()).strip();
            if (process.waitFor() != 0) {
                return "Could not determine Java version for " + javaBin;
            }
            var vpattern = java.util.regex.Pattern.compile("\"(\\d+)(?:\\.(\\d+))?");
            var vmatch = output.lines()
                    .map(vpattern::matcher)
                    .filter(java.util.regex.Matcher::find)
                    .findFirst();
            if (vmatch.isEmpty()) {
                return "Could not determine Java version for " + javaBin;
            }
            var vmatcher = vmatch.get();
            int major = Integer.parseInt(vmatcher.group(1));
            if (major == 1 && vmatcher.group(2) != null) {
                major = Integer.parseInt(vmatcher.group(2));
            }
            if (major < REQUIRED_JAVA_MAJOR) {
                return "Java " + REQUIRED_JAVA_MAJOR + "+ is required, but " + javaBin
                        + " is version " + major + ".";
            }
            return null;
        } catch (Exception e) {
            return null; // if we can't check, let it proceed and fail naturally
        }
    }

    private static void printServiceLogs() {
        try {
            var pb = new ProcessBuilder(
                    "journalctl", "--user", "-u", SERVICE_NAME, "--no-pager", "-n", "10");
            pb.redirectErrorStream(true);
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes()).strip();
            process.waitFor();
            if (!output.isBlank()) {
                System.err.println("Recent logs:");
                System.err.println(output);
                if (output.contains("status=127")) {
                    System.err.println();
                    System.err.println("Exit code 127 usually means a binary was not found.");
                    try {
                        var svc = Files.readString(Environment.proxyServiceFile());
                        var m = java.util.regex.Pattern.compile("ExecStart=(.*)").matcher(svc);
                        if (m.find()) System.err.println("ExecStart: " + m.group(1));
                    } catch (Exception ignored) {}
                    System.err.println("If isx was installed as a JVM wrapper, ensure Java "
                            + REQUIRED_JAVA_MAJOR + "+ is available at the path embedded in the wrapper.");
                    System.err.println("Alternatively, reinstall isx using your package manager, or: curl -fsSL https://isx.run | sh");
                }
            } else {
                System.err.println("Check logs with: journalctl --user -u " + SERVICE_NAME);
            }
        } catch (Exception ignored) {
            System.err.println("Check logs with: journalctl --user -u " + SERVICE_NAME);
        }
    }

    static String resolveIsxPath() {
        try {
            var pb = new ProcessBuilder("which", "isx");
            pb.redirectErrorStream(true);
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes()).strip();
            if (process.waitFor() == 0 && !output.isBlank()) {
                return output;
            }
        } catch (Exception ignored) {}
        var fallback = Environment.localBinIsx();
        if (java.nio.file.Files.isExecutable(fallback)) {
            return fallback.toString();
        }
        return null;
    }

    public static String resolveProxyBinaryPath() {
        var isxPath = resolveIsxPath();
        if (isxPath != null) {
            var proxyPath = Path.of(isxPath).getParent().resolve("isx-proxy");
            if (Files.isExecutable(proxyPath)) return proxyPath.toString();
        }
        var fallback = Environment.localBinIsx().getParent().resolve("isx-proxy");
        if (Files.isExecutable(fallback)) return fallback.toString();
        return null;
    }

    private static Path proxyStartScript() {
        return Environment.configDir().resolve("proxy-start.sh");
    }

    private static final String LINUX_PATH_FALLBACK =
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";

    /** The exec command the start script should contain for this isx binary. */
    private static String execCommand(String isxPath) {
        var proxyBin = Path.of(isxPath).getParent().resolve("isx-proxy");
        if (Files.isExecutable(proxyBin)) {
            return "exec " + Container.shellQuote(proxyBin.toString());
        }
        return "exec " + Container.shellQuote(isxPath) + " proxy start";
    }

    /** Single source of truth for the start script, so writing and staleness-checking cannot drift. */
    static String proxyStartScriptContent(String isxPath) {
        return proxyStartScriptContent(isxPath, System.getenv("PATH"));
    }

    static String proxyStartScriptContent(String isxPath, String path) {
        var sb = new StringBuilder("#!/bin/bash\n");
        var effectivePath = (path != null && !path.isBlank()) ? path : LINUX_PATH_FALLBACK;
        sb.append("export PATH=").append(Container.shellQuote(effectivePath)).append('\n');
        var cmd = execCommand(isxPath);
        if (Platform.isLinux()) {
            sb.append(sgFallbackBlock(cmd));
        } else {
            sb.append(cmd).append('\n');
        }
        return sb.toString();
    }

    /**
     * Shell block that ensures incus-admin group membership before exec'ing the proxy.
     * Three cases:
     * <ol>
     *   <li>Group already active in this process → exec directly</li>
     *   <li>Group configured in /etc/group but not active (e.g. systemd user manager
     *       started before the user was added) → use {@code sg} to activate it, or
     *       exit 78 if {@code sg} is unavailable (Arch-family distros removed it)</li>
     *   <li>User not in the group at all → exit 78 with actionable guidance</li>
     * </ol>
     * {@code id -nG} (no argument) reports the current process's active groups;
     * {@code id -nG "$user"} reads configured membership from /etc/group.
     */
    static String sgFallbackBlock(String execCmd) {
        return """
                if id -nG | tr ' ' '\\n' | grep -qx incus-admin; then
                    %1$s
                fi
                if id -nG "$(id -un)" | tr ' ' '\\n' | grep -qx incus-admin; then
                    if command -v sg >/dev/null 2>&1; then
                        exec sg incus-admin -c %2$s
                    fi
                    echo "Cannot start proxy: your 'incus-admin' group membership is not active" >&2
                    echo "in this session, and 'sg' is not available to activate it." >&2
                    echo "" >&2
                    echo "Fix: log out and log back in to activate the group for all sessions." >&2
                    exit 78
                fi
                echo "Cannot start proxy: your user is not in the 'incus-admin' group." >&2
                echo "" >&2
                echo "Fix: run 'isx init' to set up Incus and add your user to the group," >&2
                echo "     then log out and log back in." >&2
                exit 78
                """.formatted(execCmd, Container.shellQuote(execCmd));
    }

    static void writeProxyStartScript(Path script, String isxPath) throws IOException {
        Files.createDirectories(script.getParent());
        Files.writeString(script, proxyStartScriptContent(isxPath));
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private static String execStartLine() {
        return "ExecStart=" + Container.shellQuote(proxyStartScript().toString());
    }

    // --- macOS launchd support ---

    private static final String VM_LABEL = "dev.incusspawn.vm";
    private static final String PROXY_LABEL = "dev.incusspawn.proxy";

    private static Path launchAgentsDir() {
        return Path.of(System.getProperty("user.home"), "Library", "LaunchAgents");
    }

    private static Path vmPlistFile() {
        return launchAgentsDir().resolve(VM_LABEL + ".plist");
    }

    private static Path proxyPlistFile() {
        return launchAgentsDir().resolve(PROXY_LABEL + ".plist");
    }

    public static boolean isMacOsServiceInstalled() {
        return Files.exists(proxyPlistFile());
    }

    public static boolean isMacOsServiceActive() {
        try {
            var pb = new ProcessBuilder("launchctl", "print", "gui/" + getUid() + "/" + PROXY_LABEL);
            pb.redirectErrorStream(true);
            var process = pb.start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    static String generateProxyPlist(String isxPath, String gatewayIp) {
        if (!isSafeGatewayIp(gatewayIp)) {
            throw new IllegalArgumentException("Unsafe macOS proxy gateway IP: " + gatewayIp);
        }
        var path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            path = "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin";
        }
        var serviceLog = Environment.proxyServiceLogFile();
        var proxyBin = Path.of(isxPath).getParent().resolve("isx-proxy");
        String programArgs;
        if (Files.isExecutable(proxyBin)) {
            programArgs = "        <string>" + proxyBin + "</string>";
        } else {
            programArgs = "        <string>" + isxPath + "</string>\n"
                    + "            <string>proxy</string>\n"
                    + "            <string>start</string>";
        }
        programArgs += "\n            <string>--gateway-ip</string>\n"
                + "            <string>" + gatewayIp + "</string>";
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                    <key>Label</key><string>%s</string>
                    <key>ProgramArguments</key>
                    <array>
                %s
                    </array>
                    <key>RunAtLoad</key><true/>
                    <key>KeepAlive</key><true/>
                    <key>ThrottleInterval</key><integer>10</integer>
                    <key>EnvironmentVariables</key>
                    <dict>
                        <key>PATH</key><string>%s</string>
                    </dict>
                    <key>StandardOutPath</key><string>%s</string>
                    <key>StandardErrorPath</key><string>%s</string>
                </dict>
                </plist>
                """.formatted(PROXY_LABEL, programArgs, path, serviceLog, serviceLog);
    }

    static String generateVmPlist(String isxPath, String path, Path logDir) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                    <key>Label</key><string>%s</string>
                    <key>ProgramArguments</key>
                    <array>
                        <string>%s</string>
                        <string>vm</string>
                        <string>start</string>
                    </array>
                    <key>RunAtLoad</key><true/>
                    <key>EnvironmentVariables</key>
                    <dict>
                        <key>PATH</key><string>%s</string>
                    </dict>
                    <key>StandardOutPath</key><string>%s/vm-service.log</string>
                    <key>StandardErrorPath</key><string>%s/vm-service.log</string>
                </dict>
                </plist>
                """.formatted(VM_LABEL, isxPath, path, logDir, logDir);
    }

    /** A launchd listener must be a private, non-network/broadcast IPv4 host address. */
    static boolean isSafeGatewayIp(String value) {
        if (value == null) return false;
        var parts = value.split("\\.", -1);
        if (parts.length != 4) return false;
        var octets = new int[4];
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty() || !parts[i].chars().allMatch(Character::isDigit)) return false;
            try {
                octets[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return false;
            }
            if (octets[i] < 0 || octets[i] > 255) return false;
        }
        if (octets[3] == 0 || octets[3] == 255) return false;
        return octets[0] == 10
                || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                || (octets[0] == 192 && octets[1] == 168);
    }

    /** Atomically replace the global gateway state read while generating the launchd plist. */
    static void persistGatewayIp(Path stateFile, String gatewayIp) throws IOException {
        if (!isSafeGatewayIp(gatewayIp)) {
            throw new IllegalArgumentException("Unsafe macOS proxy gateway IP: " + gatewayIp);
        }
        Files.createDirectories(stateFile.getParent());
        var temporary = Files.createTempFile(
                stateFile.getParent(), stateFile.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, gatewayIp + "\n");
            Files.move(temporary, stateFile,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static String readPersistedGatewayIp(Path stateFile) {
        try {
            var gatewayIp = Files.readString(stateFile).strip();
            return isSafeGatewayIp(gatewayIp) ? gatewayIp : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Prefer a live selected-pool discovery, falling back only to valid global state. */
    static String selectMacOsGatewayIp(String discovered, Path stateFile) throws IOException {
        if (isSafeGatewayIp(discovered)) {
            persistGatewayIp(stateFile, discovered);
            return discovered;
        }
        return readPersistedGatewayIp(stateFile);
    }

    private static String resolveMacOsGatewayIp() {
        var selection = WorkerPoolSelection.current();
        var discovered = (selection.isLegacy() || VmManager.isRunning())
                ? VmNetwork.discoverHostBridgeIp() : null;
        if (discovered != null && !isSafeGatewayIp(discovered)) {
            System.err.println("  Warning: ignoring unsafe VM-facing bridge address: " + discovered);
        }
        try {
            var gatewayIp = selectMacOsGatewayIp(discovered, Environment.proxyGatewayFile());
            if (gatewayIp != null) return gatewayIp;
        } catch (IOException e) {
            System.err.println("  Could not persist the macOS proxy gateway: " + e.getMessage());
            return null;
        }
        System.err.println("  Could not discover a safe VM-facing macOS bridge IP, and no valid persisted gateway exists.");
        System.err.println("  Start the selected VM, then retry: isx proxy install");
        return null;
    }

    private static boolean needsMacOsPlistUpdate() {
        if (!Files.exists(proxyPlistFile())) return true;
        var isxPath = resolveIsxPath();
        if (isxPath == null) return false;
        var gatewayIp = readPersistedGatewayIp(Environment.proxyGatewayFile());
        if (gatewayIp == null) return true;
        try {
            var content = Files.readString(proxyPlistFile());
            return !content.equals(generateProxyPlist(isxPath, gatewayIp));
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean updateMacOsProxyPlist() {
        var isxPath = resolveIsxPath();
        if (isxPath == null) return false;
        var gatewayIp = resolveMacOsGatewayIp();
        if (gatewayIp == null) return false;
        try {
            Files.createDirectories(proxyPlistFile().getParent());
            Files.writeString(proxyPlistFile(), generateProxyPlist(isxPath, gatewayIp));
            return true;
        } catch (IOException e) {
            System.err.println("Warning: could not update proxy plist: " + e.getMessage());
            return false;
        }
    }

    /** Write or remove the historical login VM plist according to the immutable selection. */
    static void reconcileMacOsVmPlist(Path plist, boolean legacySelection,
                                      String isxPath, String path, Path logDir) throws IOException {
        if (legacySelection) {
            Files.writeString(plist, generateVmPlist(isxPath, path, logDir));
        } else {
            Files.deleteIfExists(plist);
        }
    }

    private static boolean removeLegacyVmLaunchAgent(String uid) {
        runQuiet("launchctl", "bootout", "gui/" + uid + "/" + VM_LABEL);
        runQuiet("launchctl", "bootout", "gui/" + uid, vmPlistFile().toString());
        try {
            Files.deleteIfExists(vmPlistFile());
            return true;
        } catch (IOException e) {
            System.err.println("  Could not remove legacy VM LaunchAgent: " + e.getMessage());
            return false;
        }
    }

    public static boolean installMacOs() {
        var legacySelection = WorkerPoolSelection.current().isLegacy();
        var uid = getUid();
        if (!legacySelection && !removeLegacyVmLaunchAgent(uid)) {
            return false;
        }

        var isxPath = resolveIsxPath();
        if (isxPath == null) {
            System.err.println("Could not find 'isx' in PATH.");
            return false;
        }

        var logDir = Environment.stateDir();
        var path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            path = "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin";
        }
        try {
            Files.createDirectories(launchAgentsDir());
            Files.createDirectories(logDir);
            reconcileMacOsVmPlist(vmPlistFile(), legacySelection, isxPath, path, logDir);
        } catch (IOException e) {
            System.err.println("Failed to prepare launchd plist: " + e.getMessage());
            return false;
        }

        if (legacySelection) {
            // Preserve the historical whole-home VM's login-start behavior exactly.
            System.out.println("  Installing VM service...");
            runQuiet("launchctl", "bootout", "gui/" + uid, vmPlistFile().toString());
            runQuiet("launchctl", "bootstrap", "gui/" + uid, vmPlistFile().toString());
        }

        var gatewayIp = resolveMacOsGatewayIp();
        if (gatewayIp == null) return false;
        try {
            Files.writeString(proxyPlistFile(), generateProxyPlist(isxPath, gatewayIp));
        } catch (IOException e) {
            System.err.println("Failed to write proxy launchd plist: " + e.getMessage());
            return false;
        }

        // Configure the currently selected appliance from the terminal. The launchd service has
        // an explicit gateway and deliberately carries no pool selection or Incus dependency.
        System.out.println("  Configuring bridge DNS...");
        try {
            var incus = new IncusClient();
            var domains = ProxyConfig.currentInterceptedDomains();
            ProxyConfig.configureBridgeDns(incus, domains);
            if (!ProxyConfig.isBridgeDnsComplete(incus, domains)) {
                throw new IllegalStateException("bridge did not retain the complete override set");
            }
        } catch (Exception e) {
            System.err.println("  Warning: could not configure bridge DNS: " + e.getMessage());
            System.err.println("  When the selected VM is running, run: isx proxy configure-dns");
        }

        System.out.println("  Installing proxy service...");
        runQuiet("launchctl", "bootout", "gui/" + uid + "/" + PROXY_LABEL);
        runQuiet("launchctl", "bootout", "gui/" + uid, proxyPlistFile().toString());
        waitForProxyExit();
        runQuiet("launchctl", "bootstrap", "gui/" + uid, proxyPlistFile().toString());

        if (isActive()) {
            if (ProxyHealthCheck.awaitHealthy(5)) {
                ProxyLog.info("Service installed and running");
                System.out.println(legacySelection
                        ? "  Services installed and running."
                        : "  Proxy service installed and running; named pools remain demand-started.");
                return true;
            }
            ProxyLog.info("Service installed but not healthy");
            System.err.println("  Proxy service is installed but not responding.");
            System.err.println("  Check logs with: isx proxy logs");
            return false;
        } else if (legacySelection) {
            ProxyLog.info("Service installed (waiting for VM)");
            System.out.println("  Services installed (proxy will start when VM is ready).");
            return true;
        } else {
            ProxyLog.info("Service installed but not active");
            System.err.println("  Proxy service was installed but launchd did not start it.");
            System.err.println("  Check logs with: isx proxy logs");
            return false;
        }
    }

    public static void uninstallMacOs() {
        var uid = getUid();
        runQuiet("launchctl", "bootout", "gui/" + uid + "/" + PROXY_LABEL);
        runQuiet("launchctl", "bootout", "gui/" + uid, proxyPlistFile().toString());
        removeLegacyVmLaunchAgent(uid);
        try { Files.deleteIfExists(proxyPlistFile()); } catch (IOException ignored) {}
        try { Files.deleteIfExists(Environment.proxyGatewayFile()); } catch (IOException ignored) {}
        System.out.println("  macOS services uninstalled.");
    }

    private static String getUid() {
        try {
            var pb = new ProcessBuilder("id", "-u");
            pb.redirectErrorStream(true);
            var process = pb.start();
            var uid = new String(process.getInputStream().readAllBytes()).strip();
            if (uid.isEmpty() || !uid.chars().allMatch(Character::isDigit)) {
                throw new RuntimeException("unexpected id -u output: " + uid);
            }
            return uid;
        } catch (Exception e) {
            throw new RuntimeException("Cannot determine current UID — launchd service install/uninstall requires a valid UID", e);
        }
    }

    private static void runQuiet(String... command) {
        try {
            var pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            var process = pb.start();
            process.getInputStream().readAllBytes();
            process.waitFor();
        } catch (Exception ignored) {}
    }
}
