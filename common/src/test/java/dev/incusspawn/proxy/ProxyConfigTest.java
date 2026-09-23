package dev.incusspawn.proxy;

import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.IncusClient;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProxyConfigTest {

    @Test
    void builtinDomainsIncludeBbGatewayOnlyAtItsExactName() {
        assertTrue(ProxyConfig.builtinInterceptedDomains()
                .contains(ProxyConfig.BB_GATEWAY_DOMAIN));
        assertTrue(ProxyConfig.isBbGatewayDomain("bb.isx.internal"));
        assertTrue(ProxyConfig.isBbGatewayDomain("BB.ISX.INTERNAL"));
        assertFalse(ProxyConfig.isBbGatewayDomain("api.bb.isx.internal"));
    }

    @Test
    void resolvedDomainsIncludeConfiguredToolProxyDomains() {
        var config = new SpawnConfig();
        config.getGithub().setToken("ghp_test");

        var domains = ProxyConfig.resolvedInterceptedDomains(config);

        assertTrue(domains.containsAll(ProxyConfig.builtinInterceptedDomains()));
        assertTrue(domains.contains("github.com"));
        assertTrue(domains.contains("githubusercontent.com"),
                "wildcard tool domains must contribute their DNS base domain");
    }

    @Test
    void bridgeDnsCompletenessRequiresExactGatewayAndIpv6Suppression() {
        var incus = mock(IncusClient.class);
        when(incus.networkConfigGet("incusbr0", "ipv4.address"))
                .thenReturn("10.0.0.1/24");
        when(incus.networkConfigGet("incusbr0", "raw.dnsmasq")).thenReturn("""
                address=/api.example.com/10.0.0.1
                address=/api.example.com/::
                """);

        assertTrue(ProxyConfig.isBridgeDnsComplete(incus, Set.of("api.example.com")));
    }

    @Test
    void bridgeDnsCompletenessRejectsEmptyStaleAndPartialConfiguration() {
        var incus = mock(IncusClient.class);
        when(incus.networkConfigGet("incusbr0", "ipv4.address"))
                .thenReturn("10.0.0.1/24");

        when(incus.networkConfigGet("incusbr0", "raw.dnsmasq")).thenReturn("");
        assertFalse(ProxyConfig.isBridgeDnsComplete(incus, Set.of("api.example.com")));

        when(incus.networkConfigGet("incusbr0", "raw.dnsmasq")).thenReturn("""
                address=/api.example.com/10.0.0.2
                address=/api.example.com/::
                """);
        assertFalse(ProxyConfig.isBridgeDnsComplete(incus, Set.of("api.example.com")));

        when(incus.networkConfigGet("incusbr0", "raw.dnsmasq"))
                .thenReturn("address=/api.example.com/10.0.0.1");
        assertFalse(ProxyConfig.isBridgeDnsComplete(incus, Set.of("api.example.com")));
    }

    @Test
    void writeBridgeDnsUsesCompleteResolvedSetAndPreservesNonAddressLines() {
        var incus = mock(IncusClient.class);
        when(incus.networkConfigGet("incusbr0", "ipv4.address"))
                .thenReturn("10.0.0.1/24");
        when(incus.networkConfigGet("incusbr0", "raw.dnsmasq"))
                .thenReturn("server=/internal.example/10.0.0.53\naddress=/stale.example/10.0.0.1");

        ProxyConfig.writeBridgeDns(incus, Set.of("github.com", "api.example.com"));

        verify(incus).networkConfigSet("incusbr0", "raw.dnsmasq", """
                server=/internal.example/10.0.0.53
                address=/api.example.com/10.0.0.1
                address=/api.example.com/::
                address=/github.com/10.0.0.1
                address=/github.com/::""");
    }
}
