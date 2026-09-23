package dev.incusspawn.command;

import dev.incusspawn.incus.IncusClient;
import org.aesh.command.CommandDefinition;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProxyCommandTest {

    @Test
    void proxyCommandRegistersConfigureDnsOnBothPlatformCommandTrees() {
        var commands = Arrays.asList(ProxyCommand.class
                .getAnnotation(CommandDefinition.class).groupCommands());

        assertTrue(commands.contains(ProxyCommand.ConfigureDns.class));
        assertTrue(commands.contains(ProxyCommand.GitHubToken.class));
        assertEquals("configure-dns", ProxyCommand.ConfigureDns.class
                .getAnnotation(CommandDefinition.class).name());
        assertEquals("github-token", ProxyCommand.GitHubToken.class
                .getAnnotation(CommandDefinition.class).name());
    }

    @Test
    void githubTokenReadsOnlyTheNamedEnvironmentVariable() {
        var token = "github_pat_readonly_abcdefghijklmnopqrstuvwxyz";

        assertEquals(token, ProxyCommand.GitHubToken.tokenFromEnvironment(
                "GITHUB_TOKEN_READONLY",
                name -> "GITHUB_TOKEN_READONLY".equals(name) ? token : null));
    }

    @Test
    void githubTokenRejectsMissingMalformedAndArgumentLikeSources() {
        assertThrows(IllegalArgumentException.class, () ->
                ProxyCommand.GitHubToken.tokenFromEnvironment(null, name -> "token"));
        assertThrows(IllegalArgumentException.class, () ->
                ProxyCommand.GitHubToken.tokenFromEnvironment("TOKEN=value", name -> "token"));
        assertThrows(IllegalArgumentException.class, () ->
                ProxyCommand.GitHubToken.tokenFromEnvironment("TOKEN", name -> null));
        assertThrows(IllegalArgumentException.class, () ->
                ProxyCommand.GitHubToken.tokenFromEnvironment("TOKEN", name -> "short"));
        assertThrows(IllegalArgumentException.class, () ->
                ProxyCommand.GitHubToken.tokenFromEnvironment(
                        "TOKEN", name -> "github_pat_value_with_newline\nother"));
    }

    @Test
    void configureDnsWritesAndVerifiesEveryResolvedDomain() {
        var incus = mock(IncusClient.class);
        var domains = Set.of("api.example.com", "github.com");
        var expected = """
                address=/api.example.com/10.0.0.1
                address=/api.example.com/::
                address=/github.com/10.0.0.1
                address=/github.com/::""";
        when(incus.networkConfigGet("incusbr0", "ipv4.address"))
                .thenReturn("10.0.0.1/24");
        when(incus.networkConfigGet("incusbr0", "raw.dnsmasq"))
                .thenReturn("", expected);

        assertTrue(ProxyCommand.ConfigureDns.configureDns(incus, domains));
        verify(incus).networkConfigSet("incusbr0", "raw.dnsmasq", expected);
    }

    @Test
    void configureDnsFailsWhenSelectedBridgeDoesNotRetainOverrides() {
        var incus = mock(IncusClient.class);
        when(incus.networkConfigGet("incusbr0", "ipv4.address"))
                .thenReturn("10.0.0.1/24");
        when(incus.networkConfigGet("incusbr0", "raw.dnsmasq"))
                .thenReturn("", "");

        assertFalse(ProxyCommand.ConfigureDns.configureDns(
                incus, Set.of("api.example.com")));
    }
}
