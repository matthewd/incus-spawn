package dev.incusspawn.proxy;

import dev.incusspawn.tool.ToolDef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** No sockets, subprocesses, live config loads, or token resolution. */
class CommandCredentialRouteReloadTest {
    private static final String HOST = "api.openai.com";

    @Test
    void newlyEnabledUncredentialedDeclarationRejectsReloadAndPreservesState() throws Exception {
        var original = credentials(List.of(tool("old.example.test")));
        var proxy = proxy(original);
        var domains = proxy.allInterceptedDomains();
        var candidate = credentials(List.of(tool("new.example.test")));

        assertThrows(IllegalStateException.class, () -> proxy.applyReloadedCredentials(candidate,
                new ToolProxyResolver.DeclaredRoutes(Set.of(HOST), List.of())));

        assertEquals(domains, proxy.allInterceptedDomains());
        assertNotNull(proxy.findToolProxy("old.example.test"));
        assertNull(proxy.findToolProxy("new.example.test"));
        assertSame(original, currentCredentials(proxy));
    }

    @Test
    void newlyEnabledWildcardDeclarationAlsoRejectsReload() throws Exception {
        var original = credentials(List.of());
        var proxy = proxy(original);
        assertThrows(IllegalStateException.class, () -> proxy.applyReloadedCredentials(
                credentials(List.of()),
                new ToolProxyResolver.DeclaredRoutes(Set.of(), List.of(".openai.com"))));
        assertSame(original, currentCredentials(proxy));
        assertTrue(proxy.allInterceptedDomains().contains(HOST));
    }

    @Test
    void resolvedRouteCollisionStillFailsBeforePublishingAnyRouting() throws Exception {
        var original = credentials(List.of(tool("old.example.test")));
        var proxy = proxy(original);
        var domains = proxy.allInterceptedDomains();
        assertThrows(IllegalStateException.class, () -> proxy.applyReloadedCredentials(
                credentials(List.of(tool("new.example.test"), tool(HOST))),
                new ToolProxyResolver.DeclaredRoutes(Set.of(), List.of())));
        assertEquals(domains, proxy.allInterceptedDomains());
        assertSame(original, currentCredentials(proxy));
    }

    @Test
    void unrelatedReloadSucceedsAndRetainsCommandRoute() throws Exception {
        var proxy = proxy(credentials(List.of()));
        var candidate = credentials(List.of(tool("new.example.test")));
        proxy.applyReloadedCredentials(candidate,
                new ToolProxyResolver.DeclaredRoutes(Set.of("new.example.test"), List.of()));
        assertSame(candidate, currentCredentials(proxy));
        assertNotNull(proxy.findToolProxy("new.example.test"));
        assertTrue(proxy.allInterceptedDomains().contains(HOST));
    }

    private static MitmProxy proxy(ProxyCredentials credentials) {
        var regex = "token_[A-Za-z0-9]{16}";
        var rule = new CommandCredentialConfig.Rule(
                "openai-test", HOST, List.of("/not-executed/helper"), regex, Pattern.compile(regex),
                new CommandCredentialConfig.Carriers(
                        new CommandCredentialConfig.BearerCarrier("inert-test-placeholder"), null),
                15, 8192, 16777216, 60, 5, "Test provider", "Check test setup");
        return new MitmProxy(null, "127.0.0.1", 0, 0, "127.0.0.1", credentials,
                new CommandCredentialConfig(List.of(rule)));
    }

    private static ProxyCredentials credentials(List<ResolvedToolProxy> tools) {
        return new ProxyCredentials("", "", false, "", "", tools);
    }

    private static ResolvedToolProxy tool(String domain) {
        var auth = new ToolDef.AuthDef();
        auth.setType("bearer");
        auth.setToken("${token}");
        return new ResolvedToolProxy("synthetic-tool", domain, auth, Map.of("token", "synthetic-token"));
    }

    private static ProxyCredentials currentCredentials(MitmProxy proxy) throws Exception {
        var field = MitmProxy.class.getDeclaredField("credentials");
        field.setAccessible(true);
        return (ProxyCredentials) field.get(proxy);
    }
}
