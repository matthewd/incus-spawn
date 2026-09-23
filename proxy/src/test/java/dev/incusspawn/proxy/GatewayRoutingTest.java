package dev.incusspawn.proxy;

import io.vertx.core.MultiMap;
import io.vertx.core.http.WebSocketConnectOptions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayRoutingTest {

    @Test
    void httpGatewayUsesPlainLoopbackAndPreservesOriginalHost() {
        var target = MitmProxy.httpRelayTarget(ProxyConfig.BB_GATEWAY_DOMAIN);

        assertEquals("127.0.0.1", target.connectHost());
        assertEquals(18444, target.port());
        assertFalse(target.ssl());
        assertTrue(target.preserveOriginalHost());
        assertFalse(target.injectWebSocketCredentials());
    }

    @Test
    void webSocketGatewayUsesPlainLoopbackWithoutCredentialInjection() {
        var proxy = proxy();
        proxy.upstreamWsPort = 19443;
        proxy.upstreamWsSsl = true;

        var target = proxy.webSocketRelayTarget(ProxyConfig.BB_GATEWAY_DOMAIN);

        assertEquals("127.0.0.1", target.connectHost());
        assertEquals(18444, target.port());
        assertFalse(target.ssl());
        assertTrue(target.preserveOriginalHost());
        assertFalse(target.injectWebSocketCredentials());

        var headers = MultiMap.caseInsensitiveMultiMap()
                .add("Host", "bb.isx.internal")
                .add("Authorization", "Bearer caller-capability")
                .add("Sec-WebSocket-Protocol", "bb-host-daemon.v1");
        var options = new WebSocketConnectOptions();
        MitmProxy.copyWebSocketHandshake(headers, options, target);

        assertEquals("bb.isx.internal", options.getHeaders().get("Host"));
        assertEquals("Bearer caller-capability", options.getHeaders().get("Authorization"));
        assertEquals(List.of(ProxyConfig.BB_GATEWAY_SUBPROTOCOL),
                options.getSubProtocols());
        assertEquals(List.of(ProxyConfig.BB_GATEWAY_SUBPROTOCOL),
                MitmProxy.inboundWebSocketSubProtocols());
    }

    @Test
    void gatewayWebSocketRejectsBrowserOriginMarkers() {
        assertFalse(MitmProxy.hasBrowserWebSocketHeaders(
                MultiMap.caseInsensitiveMultiMap().add("Authorization", "Bearer bbdh_key")));
        assertTrue(MitmProxy.hasBrowserWebSocketHeaders(
                MultiMap.caseInsensitiveMultiMap().add("Origin", "https://attacker.example")));
        assertTrue(MitmProxy.hasBrowserWebSocketHeaders(
                MultiMap.caseInsensitiveMultiMap().add("Sec-Fetch-Site", "cross-site")));
    }

    @Test
    void gatewayFramingRejectsDeclaredOversizeBeforeStreaming() {
        var atLimit = MitmProxy.gatewayRequestFraming(MultiMap.caseInsensitiveMultiMap()
                .add("Content-Length", String.valueOf(MitmProxy.BB_GATEWAY_MAX_BODY_BYTES)));
        assertTrue(atLimit.accepted());
        assertEquals(MitmProxy.BB_GATEWAY_MAX_BODY_BYTES, atLimit.contentLength());

        var oversized = MitmProxy.gatewayRequestFraming(MultiMap.caseInsensitiveMultiMap()
                .add("Content-Length", String.valueOf(MitmProxy.BB_GATEWAY_MAX_BODY_BYTES + 1)));
        assertFalse(oversized.accepted());
        assertEquals(413, oversized.rejectionStatus());
    }

    @Test
    void gatewayFramingRejectsMalformedAndConflictingHeaders() {
        var contentLengthAndTransferEncoding = MultiMap.caseInsensitiveMultiMap()
                .add("Content-Length", "12")
                .add("Transfer-Encoding", "chunked");
        assertEquals(400, MitmProxy.gatewayRequestFraming(contentLengthAndTransferEncoding)
                .rejectionStatus());

        var conflictingLengths = MultiMap.caseInsensitiveMultiMap()
                .add("Content-Length", "12")
                .add("Content-Length", "13");
        assertEquals(400, MitmProxy.gatewayRequestFraming(conflictingLengths)
                .rejectionStatus());

        assertEquals(400, MitmProxy.gatewayRequestFraming(MultiMap.caseInsensitiveMultiMap()
                .add("Content-Length", "not-a-number")).rejectionStatus());
        assertEquals(400, MitmProxy.gatewayRequestFraming(MultiMap.caseInsensitiveMultiMap()
                .add("Transfer-Encoding", "gzip, chunked")).rejectionStatus());

        var chunked = MitmProxy.gatewayRequestFraming(MultiMap.caseInsensitiveMultiMap()
                .add("Transfer-Encoding", "chunked"));
        assertTrue(chunked.accepted());
        assertTrue(chunked.chunked());
    }

    @Test
    void gatewayStreamCounterRejectsBytesBeyondLimit() {
        var limit = new MitmProxy.GatewayBodyLimit();

        assertTrue(limit.tryAccept(8 * 1024 * 1024));
        assertTrue(limit.tryAccept(8 * 1024 * 1024));
        assertFalse(limit.tryAccept(1));
        assertEquals(MitmProxy.BB_GATEWAY_MAX_BODY_BYTES, limit.acceptedBytes());
    }

    @Test
    void gatewayLogTargetNeverContainsItsUriOrQuery() {
        var secretUri = "/rpc/session?capability=secret";

        assertEquals(ProxyConfig.BB_GATEWAY_DOMAIN,
                MitmProxy.requestLogTarget(ProxyConfig.BB_GATEWAY_DOMAIN, secretUri));
        assertEquals("api.example.com" + secretUri,
                MitmProxy.requestLogTarget("api.example.com", secretUri));
    }

    @Test
    void otherHttpAndWebSocketTargetsAreUnchanged() {
        var http = MitmProxy.httpRelayTarget("api.example.com");
        assertEquals("api.example.com", http.connectHost());
        assertEquals(443, http.port());
        assertTrue(http.ssl());
        assertFalse(http.preserveOriginalHost());

        var proxy = proxy();
        proxy.upstreamWsPort = 19443;
        proxy.upstreamWsSsl = false;
        var webSocket = proxy.webSocketRelayTarget("api.example.com");
        assertEquals("api.example.com", webSocket.connectHost());
        assertEquals(19443, webSocket.port());
        assertFalse(webSocket.ssl());
        assertFalse(webSocket.preserveOriginalHost());
        assertTrue(webSocket.injectWebSocketCredentials());
    }

    private static MitmProxy proxy() {
        var credentials = new ProxyCredentials("", "", false, "", "", List.of());
        return new MitmProxy(null, "127.0.0.1", 18443, 18080,
                "127.0.0.1", credentials);
    }
}
