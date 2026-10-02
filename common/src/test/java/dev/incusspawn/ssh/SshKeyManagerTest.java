package dev.incusspawn.ssh;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SshKeyManagerTest {

    @TempDir
    Path tempDir;

    private String originalHome;

    @BeforeEach
    void setUp() {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", originalHome);
    }

    @Test
    void existsReturnsFalseWhenNoKeys() {
        assertFalse(SshKeyManager.exists());
    }

    @Test
    void ensureKeyPairCreatesFiles() {
        SshKeyManager.ensureKeyPairExists();

        var keyFile = tempDir.resolve(".config/incus-spawn/ssh/id_ed25519");
        var pubFile = tempDir.resolve(".config/incus-spawn/ssh/id_ed25519.pub");
        assertTrue(Files.exists(keyFile), "Private key should be created");
        assertTrue(Files.exists(pubFile), "Public key should be created");
        assertTrue(SshKeyManager.exists());
    }

    @Test
    void ensureKeyPairIsIdempotent() throws IOException {
        SshKeyManager.ensureKeyPairExists();
        var keyFile = tempDir.resolve(".config/incus-spawn/ssh/id_ed25519");
        var firstContent = Files.readString(keyFile);

        SshKeyManager.ensureKeyPairExists();
        var secondContent = Files.readString(keyFile);

        assertEquals(firstContent, secondContent, "Key should not be regenerated");
    }

    @Test
    void publicKeyContentReturnsKeyData() {
        SshKeyManager.ensureKeyPairExists();
        var content = SshKeyManager.publicKeyContent();
        assertTrue(content.startsWith("ssh-ed25519 "), "Should be an ed25519 public key");
        assertTrue(content.contains("incus-spawn managed key"), "Should contain the comment");
    }

    @Test
    void addHostEntryCreatesConfigFile() {
        SshKeyManager.addHostEntry("test-instance");

        var configFile = tempDir.resolve(".config/incus-spawn/ssh/config");
        assertTrue(Files.exists(configFile));
        var content = assertDoesNotThrow(() -> Files.readString(configFile));
        assertTrue(content.contains("Host test-instance"));
        assertTrue(content.contains("ProxyCommand"));
        assertTrue(content.contains("ssh-proxy 'test-instance'"));
        assertTrue(content.contains("User agentuser"));
        assertTrue(content.contains("IdentityFile ~/.config/incus-spawn/ssh/id_ed25519"));
        assertTrue(content.contains("IdentitiesOnly yes"));
        assertTrue(content.contains("ForwardAgent no"));
        assertTrue(content.contains("ClearAllForwardings yes"));
        assertTrue(content.contains("StrictHostKeyChecking no"));
        assertTrue(content.contains("UserKnownHostsFile /dev/null"));
    }

    @Test
    void ownedHostEntryPinsTheProxyToItsAutomationKey() {
        SshKeyManager.addOwnedHostEntry("test-instance", "thread/key-17");

        var content = assertDoesNotThrow(
                () -> Files.readString(tempDir.resolve(".config/incus-spawn/ssh/config")));
        assertTrue(content.contains("ssh-proxy 'test-instance' --key 'thread/key-17'"));
    }

    @Test
    void proxyCommandsClearForeignPoolSelectorsBeforePinningTheirOwn() {
        var staticCommand = SshKeyManager.proxyCommand(
                "/opt/isx", "static-instance", null,
                "compile", null, null);
        assertTrue(staticCommand.startsWith(
                "env -u ISX_POOL -u ISX_MATERIALIZED_POOL "
                        + "-u ISX_MATERIALIZED_POOL_IDENTITY "
                        + "-u ISX_MATERIALIZED_POOL_MAINTENANCE ISX_POOL='compile' "));
        assertFalse(staticCommand.contains("ISX_MATERIALIZED_POOL='"));

        var materializedCommand = SshKeyManager.proxyCommand(
                "/opt/isx", "direct-instance", "thread/key",
                null, "direct-one", "a".repeat(64));
        assertTrue(materializedCommand.startsWith(
                "env -u ISX_POOL -u ISX_MATERIALIZED_POOL "
                        + "-u ISX_MATERIALIZED_POOL_IDENTITY "
                        + "-u ISX_MATERIALIZED_POOL_MAINTENANCE "
                        + "ISX_MATERIALIZED_POOL='direct-one' "
                        + "ISX_MATERIALIZED_POOL_IDENTITY='" + "a".repeat(64) + "' "));
        assertTrue(materializedCommand.endsWith(
                "'/opt/isx' ssh-proxy 'direct-instance' --key 'thread/key'"));
    }

    @Test
    void addHostEntryWithHostname() {
        SshKeyManager.addHostEntry("test-instance", "10.0.0.42");

        var content = assertDoesNotThrow(
                () -> Files.readString(tempDir.resolve(".config/incus-spawn/ssh/config")));
        assertTrue(content.contains("Host test-instance"));
        assertTrue(content.contains("Hostname 10.0.0.42"));
        assertTrue(content.contains("ProxyCommand"));
    }

    @Test
    void addHostEntryWithNullHostnameOmitsDirective() {
        SshKeyManager.addHostEntry("test-instance", null);

        var content = assertDoesNotThrow(
                () -> Files.readString(tempDir.resolve(".config/incus-spawn/ssh/config")));
        assertTrue(content.contains("Host test-instance"));
        assertFalse(content.contains("Hostname"));
    }

    @Test
    void addHostEntryReplacesExistingEntry() {
        SshKeyManager.addHostEntry("test-instance");
        SshKeyManager.addHostEntry("test-instance");

        var content = assertDoesNotThrow(
                () -> Files.readString(tempDir.resolve(".config/incus-spawn/ssh/config")));
        assertEquals(1, content.lines().filter(l -> l.strip().startsWith("Host ")).count(),
                "Should have exactly one Host block");
    }

    @Test
    void addMultipleHostEntries() {
        SshKeyManager.addHostEntry("instance-a");
        SshKeyManager.addHostEntry("instance-b");

        var content = assertDoesNotThrow(
                () -> Files.readString(tempDir.resolve(".config/incus-spawn/ssh/config")));
        assertTrue(content.contains("Host instance-a"));
        assertTrue(content.contains("Host instance-b"));
        assertEquals(2, content.lines().filter(l -> l.strip().startsWith("Host ")).count());
    }

    @Test
    void removeHostEntry() {
        SshKeyManager.addHostEntry("keep-me");
        SshKeyManager.addHostEntry("remove-me");
        SshKeyManager.removeHostEntry("remove-me");

        var content = assertDoesNotThrow(
                () -> Files.readString(tempDir.resolve(".config/incus-spawn/ssh/config")));
        assertTrue(content.contains("Host keep-me"));
        assertFalse(content.contains("Host remove-me"));
    }

    @Test
    void removeHostEntryNonexistent() {
        SshKeyManager.addHostEntry("keep-me");
        SshKeyManager.removeHostEntry("nonexistent");

        var content = assertDoesNotThrow(
                () -> Files.readString(tempDir.resolve(".config/incus-spawn/ssh/config")));
        assertTrue(content.contains("Host keep-me"));
    }

    @Test
    void ensureSshConfigIncludeCreatesConfigIfMissing() {
        assertTrue(SshKeyManager.ensureSshConfigInclude());

        var sshConfig = tempDir.resolve(".ssh/config");
        assertTrue(Files.exists(sshConfig));
        var content = assertDoesNotThrow(() -> Files.readString(sshConfig));
        assertTrue(content.contains("Include ~/.config/incus-spawn/ssh/config"));
    }

    @Test
    void ensureSshConfigIncludePrependsToExistingConfig() throws IOException {
        var sshDir = tempDir.resolve(".ssh");
        Files.createDirectories(sshDir);
        var sshConfig = sshDir.resolve("config");
        Files.writeString(sshConfig, "Host existing\n    HostName 1.2.3.4\n");

        assertTrue(SshKeyManager.ensureSshConfigInclude());

        var content = Files.readString(sshConfig);
        var lines = content.lines().toList();
        assertEquals("Include ~/.config/incus-spawn/ssh/config", lines.get(0),
                "Include should be the first line");
        assertTrue(content.contains("Host existing"), "Existing content should be preserved");
    }

    @Test
    void ensureSshConfigIncludeIsIdempotent() throws IOException {
        SshKeyManager.ensureSshConfigInclude();
        var sshConfig = tempDir.resolve(".ssh/config");
        var firstContent = Files.readString(sshConfig);

        SshKeyManager.ensureSshConfigInclude();
        var secondContent = Files.readString(sshConfig);

        assertEquals(firstContent, secondContent, "Should not duplicate the Include line");
    }

    @Test
    void ensureSshConfigIncludeUsesConfiguredLocalFragment() throws IOException {
        var sshDir = tempDir.resolve(".ssh");
        Files.createDirectories(sshDir);
        var primaryConfig = sshDir.resolve("config");
        var primaryContent = "Include config.local\n\nHost existing\n    HostName 1.2.3.4\n";
        Files.writeString(primaryConfig, primaryContent);

        var configDir = tempDir.resolve(".config/incus-spawn");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("config.yaml"), """
                ssh:
                  include-into: ~/.ssh/config.local
                """);

        assertTrue(SshKeyManager.ensureSshConfigInclude());
        assertTrue(SshKeyManager.ensureSshConfigInclude());

        assertEquals(primaryContent, Files.readString(primaryConfig),
                "Primary SSH config should not be changed");
        var localContent = Files.readString(sshDir.resolve("config.local"));
        assertEquals(1, localContent.lines().filter(l ->
                l.equals("Include ~/.config/incus-spawn/ssh/config")).count());
    }

    @Test
    void invalidConfiguredIncludeTargetDoesNotFallBackToPrimaryConfig() throws IOException {
        var configDir = tempDir.resolve(".config/incus-spawn");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("config.yaml"), """
                ssh:
                  include-into: config.local
                """);

        assertFalse(SshKeyManager.ensureSshConfigInclude());
        assertFalse(Files.exists(tempDir.resolve(".ssh/config")));
    }

    @Test
    void fullCleanupFlow() {
        SshKeyManager.addHostEntry("my-instance");
        SshKeyManager.addHostEntry("other-instance");

        SshKeyManager.cleanupInstance("my-instance");

        var config = assertDoesNotThrow(
                () -> Files.readString(tempDir.resolve(".config/incus-spawn/ssh/config")));
        assertFalse(config.contains("Host my-instance"), "Host block should be removed");
        assertTrue(config.contains("Host other-instance"), "Other host should remain");
    }

    @Test
    void cleanupNonexistentInstanceIsNoOp() {
        assertDoesNotThrow(() -> SshKeyManager.cleanupInstance("nonexistent"));
    }

}
