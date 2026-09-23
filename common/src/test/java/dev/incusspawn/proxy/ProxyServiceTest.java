package dev.incusspawn.proxy;

import dev.incusspawn.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ProxyServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void checkJvmWrapperReturnsNullForNativeBinary() throws IOException {
        var binary = tempDir.resolve("isx");
        Files.write(binary, new byte[]{0x7f, 'E', 'L', 'F'});
        assertNull(ProxyService.checkJvmWrapper(binary.toString()));
    }

    @Test
    void checkJvmWrapperReturnsNullForWorkingJavaUnquoted() throws IOException {
        var javaBin = ProcessHandle.current().info().command().orElse(null);
        if (javaBin == null) return;

        var wrapper = tempDir.resolve("isx");
        Files.writeString(wrapper, "#!/bin/bash\nexec " + javaBin + " -jar /some/app.jar \"$@\"\n");
        assertNull(ProxyService.checkJvmWrapper(wrapper.toString()));
    }

    @Test
    void checkJvmWrapperReturnsNullForWorkingJavaQuoted() throws IOException {
        var javaBin = ProcessHandle.current().info().command().orElse(null);
        if (javaBin == null) return;

        var wrapper = tempDir.resolve("isx");
        Files.writeString(wrapper, "#!/bin/bash\nexec \"" + javaBin + "\" -jar /some/app.jar \"$@\"\n");
        assertNull(ProxyService.checkJvmWrapper(wrapper.toString()));
    }

    @Test
    void checkJvmWrapperDetectsMissingJavaBinary() throws IOException {
        var wrapper = tempDir.resolve("isx");
        Files.writeString(wrapper, "#!/bin/bash\nexec \"/nonexistent/java\" -jar /some/app.jar \"$@\"\n");
        var result = ProxyService.checkJvmWrapper(wrapper.toString());
        assertNotNull(result);
        assertTrue(result.contains("/nonexistent/java"));
        assertTrue(result.contains("not found"));
    }

    @Test
    void checkJvmWrapperReturnsNullForNonJavaWrapper() throws IOException {
        var wrapper = tempDir.resolve("isx");
        Files.writeString(wrapper, "#!/bin/bash\nexec /usr/bin/python3 app.py \"$@\"\n");
        assertNull(ProxyService.checkJvmWrapper(wrapper.toString()));
    }

    @Test
    void writeProxyStartScriptCreatesExecutableScript() throws IOException {
        var script = tempDir.resolve("proxy-start.sh");
        ProxyService.writeProxyStartScript(script, "/home/user/.local/bin/isx");

        var content = Files.readString(script);
        assertTrue(content.startsWith("#!/bin/bash\n"));
        assertTrue(content.contains("/home/user/.local/bin/isx"));
        assertTrue(content.contains("proxy start"));
        assertTrue(Files.isExecutable(script));
    }

    @Test
    void startScriptIncludesPath() {
        var content = ProxyService.proxyStartScriptContent("/home/user/.local/bin/isx",
                "/usr/bin:/usr/local/bin:/opt/gcloud/bin");
        assertTrue(content.contains("export PATH='/usr/bin:/usr/local/bin:/opt/gcloud/bin'"));
    }

    @Test
    void startScriptUsesFallbackPathWhenNull() {
        var content = ProxyService.proxyStartScriptContent("/home/user/.local/bin/isx", null);
        assertTrue(content.contains("export PATH="), "should use fallback PATH, not omit it");
    }

    @Test
    void oldScriptWithoutPathIsStale() throws IOException {
        var script = tempDir.resolve("proxy-start.sh");
        Files.writeString(script, "#!/bin/bash\nexec '/home/user/.local/bin/isx' proxy start\n");
        assertTrue(ProxyService.startScriptIsStale(script, "/home/user/.local/bin/isx"),
                "scripts from before the PATH fix must be rewritten on upgrade");
    }

    @Test
    void pathDriftDoesNotCauseStaleness() throws IOException {
        var script = tempDir.resolve("proxy-start.sh");
        // Install with one PATH
        Files.writeString(script, ProxyService.proxyStartScriptContent(
                "/home/user/.local/bin/isx", "/usr/bin:/usr/local/bin"));
        // Check staleness with the same binary but a different PATH (conda activated)
        assertFalse(ProxyService.startScriptIsStale(script, "/home/user/.local/bin/isx"),
                "PATH differences alone must not trigger a restart");
    }

    @Test
    void writeProxyStartScriptEscapesSingleQuotes() throws IOException {
        var script = tempDir.resolve("proxy-start.sh");
        ProxyService.writeProxyStartScript(script, "/home/user/it's here/isx");

        var content = Files.readString(script);
        assertTrue(content.contains("it"));
        assertTrue(content.contains("s here"));
        assertFalse(content.contains("it's"), "unescaped single quote would break the shell script");
    }

    // --- start script staleness -------------------------------------------------
    //
    // The isx binary path lives only in the start script, never in the unit, so an upgrade that
    // moves the binary (a distro package landing in /usr/bin over a previous ~/.local/bin install,
    // or the reverse) is invisible to a unit-text comparison. Checking the unit alone left the
    // service exec'ing the previous installation's binary forever.

    @Test
    void startScriptIsStaleWhenMissing() throws IOException {
        var script = tempDir.resolve("proxy-start.sh");
        assertTrue(ProxyService.startScriptIsStale(script, "/home/user/.local/bin/isx"));
    }

    @Test
    void startScriptIsNotStaleWhenItMatches() throws IOException {
        var script = tempDir.resolve("proxy-start.sh");
        ProxyService.writeProxyStartScript(script, "/home/user/.local/bin/isx");
        assertFalse(ProxyService.startScriptIsStale(script, "/home/user/.local/bin/isx"));
    }

    @Test
    void startScriptIsStaleWhenBinaryMoved() throws IOException {
        var script = tempDir.resolve("proxy-start.sh");
        ProxyService.writeProxyStartScript(script, "/usr/bin/isx");
        assertTrue(ProxyService.startScriptIsStale(script, "/home/user/.local/bin/isx"),
                "an upgrade that relocates the binary must be detected");
    }

    @Test
    void startScriptWithoutSgFallbackIsStaleOnLinux() throws IOException {
        assumeTrue(Platform.isLinux());
        var script = tempDir.resolve("proxy-start.sh");
        // Old-format script: has PATH and exec command but no sg fallback block
        Files.writeString(script, "#!/bin/bash\nexport PATH='/usr/bin'\n"
                + "exec '/home/user/.local/bin/isx' proxy start\n");
        assertTrue(ProxyService.startScriptIsStale(script, "/home/user/.local/bin/isx"),
                "scripts from before the sg fallback must be rewritten on upgrade");
    }

    @Test
    void unitTextCarriesNoBinaryPath() {
        // Pins the reason the script must be checked separately: two installations pointing at
        // different isx binaries produce byte-identical units, so comparing units can never
        // notice the difference. If the unit ever does embed the binary path, this test fails
        // and the staleness logic above should be revisited.
        var unit = ProxyService.serviceUnitContent();
        assertFalse(unit.contains("/usr/bin/isx"));
        assertFalse(unit.contains(".local/bin/isx"));
        assertTrue(unit.contains("proxy-start.sh"),
                "the unit should exec the start script, which is what holds the binary path");
    }

    // --- systemd restart policy -------------------------------------------------
    //
    // A misconfiguration the user must fix by hand (init not run, Vertex fields blank) exits
    // EXIT_CONFIG. RestartPreventExitStatus stops systemd retrying it forever and burying the
    // reason in the journal. Transient failures still exit 1 and are still retried.

    @Test
    void generatedUnitPreventsRestartOnConfigError() {
        var unit = ProxyService.serviceUnitContent();
        assertTrue(unit.contains("Restart=on-failure"),
                "transient failures must still be retried");
        assertTrue(unit.contains(ProxyService.RESTART_PREVENT_LINE),
                "config errors must not crash-loop");
        // The directive is derived from EXIT_CONFIG, so this also pins the unit text to the
        // exit code the proxy actually returns — the two cannot drift apart.
        assertEquals("RestartPreventExitStatus=78", ProxyService.RESTART_PREVENT_LINE);
    }

    @Test
    void generatedUnitIsStructurallyWellFormed() {
        var unit = ProxyService.serviceUnitContent();
        assertTrue(unit.contains("[Unit]"));
        assertTrue(unit.contains("[Service]"));
        assertTrue(unit.contains("[Install]"));
        assertTrue(unit.contains("ExecStart="));
        assertFalse(unit.contains("%s"), "format placeholder left unsubstituted");
        // The restart policy lines belong together.
        assertTrue(unit.contains("Restart=on-failure\nRestartPreventExitStatus=78\nRestartSec=5"));
    }

    @Test
    void generatedUnitDoesNotRequireSg() {
        var unit = ProxyService.serviceUnitContent();
        assertFalse(unit.contains("sg incus-admin"),
                "sg belongs in the start script, not the systemd unit");
    }

    // --- sg fallback in start script ----------------------------------------------

    @Test
    void startScriptHasSgFallbackOnLinux() {
        assumeTrue(Platform.isLinux());
        var content = ProxyService.proxyStartScriptContent("/home/user/.local/bin/isx",
                "/usr/bin:/usr/local/bin");
        assertTrue(content.contains("command -v sg"),
                "start script should check for sg availability");
        assertTrue(content.contains("sg incus-admin"),
                "start script should use sg when available");
        assertTrue(content.contains("id -nG"),
                "start script should check group membership when sg is absent");
        assertTrue(content.contains("exit 78"),
                "start script should exit with EX_CONFIG when group is missing");
    }

    @Test
    void sgFallbackBlockContainsActionableErrors() {
        var block = ProxyService.sgFallbackBlock("exec '/usr/bin/isx' proxy start");
        assertTrue(block.contains("incus-admin"));
        assertTrue(block.contains("log out and log back in"),
                "both error paths should suggest re-login");
        assertTrue(block.contains("isx init"),
                "not-a-member path should suggest isx init");
        // Two distinct exit 78 paths: configured-but-inactive and not-a-member
        assertEquals(2, block.split("exit 78").length - 1,
                "should have two exit 78 paths");
    }

    @Test
    void sgFallbackBlockChecksActiveGroupThenConfiguredGroup() {
        var block = ProxyService.sgFallbackBlock("exec '/usr/bin/isx' proxy start");
        // Active-group check (id -nG, no argument) must come first for direct exec
        int activeCheck = block.indexOf("id -nG |");
        // Configured-group check (id -nG "$(id -un)") comes second for sg fallback
        int configuredCheck = block.indexOf("id -nG \"$(id -un)\"");
        assertTrue(activeCheck < configuredCheck,
                "active-group check must precede configured-group check");
        assertTrue(configuredCheck < block.indexOf("command -v sg"),
                "configured-group check must precede sg attempt");
        // sg branch: exec sg ... -c '<command>'
        assertTrue(block.contains("exec sg incus-admin -c"));
    }

    @Test
    void sgFallbackBlockDirectExecsWhenGroupActive() {
        var block = ProxyService.sgFallbackBlock("exec '/usr/bin/isx' proxy start");
        // The first branch (group already active) should exec the command directly
        int activeCheck = block.indexOf("id -nG |");
        int firstExecCmd = block.indexOf("exec '/usr/bin/isx' proxy start");
        int sgCheck = block.indexOf("command -v sg");
        assertTrue(firstExecCmd > activeCheck && firstExecCmd < sgCheck,
                "direct exec should be in the active-group branch, before sg");
    }

    @Test
    void sgFallbackBlockUsesExactGroupMatch() {
        var block = ProxyService.sgFallbackBlock("exec '/usr/bin/isx' proxy start");
        assertTrue(block.contains("grep -qx incus-admin"),
                "must use exact-line match to avoid false positives on incus-admin-testing");
        assertFalse(block.contains("grep -qw"),
                "word-boundary match would false-positive on hyphenated group names");
    }

    // --- macOS global proxy service --------------------------------------------

    @Test
    void macOsProxyPlistPinsPersistedGatewayWithoutPoolOrCredentials() {
        var plist = ProxyService.generateProxyPlist("/opt/homebrew/bin/isx", "192.168.64.1");

        assertTrue(plist.contains("<string>--gateway-ip</string>"));
        assertTrue(plist.contains("<string>192.168.64.1</string>"));
        assertFalse(plist.contains("ISX_POOL"), "the singleton proxy must not inherit a pool");
        assertFalse(plist.contains("ANTHROPIC_API_KEY"));
        assertFalse(plist.contains("GH_TOKEN"));
        assertFalse(plist.contains("OPENAI_API_KEY"));
    }

    @Test
    void macOsProxyPlistPinsGatewayForStandaloneBinary() throws IOException {
        var isx = tempDir.resolve("bin/isx");
        var proxy = tempDir.resolve("bin/isx-proxy");
        Files.createDirectories(isx.getParent());
        Files.writeString(proxy, "proxy");
        proxy.toFile().setExecutable(true);

        var plist = ProxyService.generateProxyPlist(isx.toString(), "10.20.30.1");

        assertTrue(plist.contains("<string>" + proxy + "</string>"));
        assertTrue(plist.contains("<string>--gateway-ip</string>"));
        assertTrue(plist.contains("<string>10.20.30.1</string>"));
    }

    @Test
    void gatewayValidationAcceptsOnlyPrivateHostAddresses() {
        assertTrue(ProxyService.isSafeGatewayIp("10.0.0.1"));
        assertTrue(ProxyService.isSafeGatewayIp("172.31.4.1"));
        assertTrue(ProxyService.isSafeGatewayIp("192.168.64.1"));
        assertFalse(ProxyService.isSafeGatewayIp("127.0.0.1"));
        assertFalse(ProxyService.isSafeGatewayIp("169.254.1.1"));
        assertFalse(ProxyService.isSafeGatewayIp("192.168.64.0"));
        assertFalse(ProxyService.isSafeGatewayIp("192.168.64.255"));
        assertFalse(ProxyService.isSafeGatewayIp("8.8.8.8"));
        assertFalse(ProxyService.isSafeGatewayIp("192.168.64.1</string>"));
    }

    @Test
    void discoveredGatewayAtomicallyReplacesGlobalState() throws IOException {
        var state = tempDir.resolve("state/proxy-gateway-ip");
        ProxyService.persistGatewayIp(state, "192.168.64.1");
        assertEquals("192.168.64.1", ProxyService.readPersistedGatewayIp(state));

        assertEquals("192.168.65.1",
                ProxyService.selectMacOsGatewayIp("192.168.65.1", state));
        assertEquals("192.168.65.1\n", Files.readString(state));
        try (var files = Files.list(state.getParent())) {
            assertEquals(1, files.count(), "atomic staging file must be removed");
        }
    }

    @Test
    void persistedGatewayIsFallbackWhenLiveDiscoveryIsUnavailable() throws IOException {
        var state = tempDir.resolve("state/proxy-gateway-ip");
        ProxyService.persistGatewayIp(state, "192.168.64.1");

        assertEquals("192.168.64.1", ProxyService.selectMacOsGatewayIp(null, state));
        assertEquals("192.168.64.1", ProxyService.selectMacOsGatewayIp("not-an-ip", state));
    }

    @Test
    void gatewaySelectionFailsClosedWithoutSafeLiveOrPersistedValue() throws IOException {
        var state = tempDir.resolve("state/proxy-gateway-ip");
        Files.createDirectories(state.getParent());
        Files.writeString(state, "--debug\n");

        assertNull(ProxyService.selectMacOsGatewayIp(null, state));
        assertNull(ProxyService.selectMacOsGatewayIp("8.8.8.8", state));
    }

    @Test
    void legacySelectionWritesVmPlistAndNamedSelectionRemovesIt() throws IOException {
        var plist = tempDir.resolve("Library/LaunchAgents/dev.incusspawn.vm.plist");
        Files.createDirectories(plist.getParent());

        ProxyService.reconcileMacOsVmPlist(plist, true, "/usr/local/bin/isx",
                "/usr/local/bin:/usr/bin", tempDir.resolve("state"));
        assertTrue(Files.exists(plist));
        assertTrue(Files.readString(plist).contains("<string>vm</string>"));
        assertTrue(Files.readString(plist).contains("<string>start</string>"));

        ProxyService.reconcileMacOsVmPlist(plist, false, "/usr/local/bin/isx",
                "/usr/local/bin:/usr/bin", tempDir.resolve("state"));
        assertFalse(Files.exists(plist),
                "a named-pool install must remove the whole-home login VM plist");
    }
}
