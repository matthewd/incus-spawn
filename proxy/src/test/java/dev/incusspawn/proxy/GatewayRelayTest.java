package dev.incusspawn.proxy;

import dev.incusspawn.Environment;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.WebSocketConnectOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayRelayTest {

    @TempDir
    static Path tempHome;

    static String originalHome;
    static Vertx vertx;
    static HttpServer gateway;
    static MitmProxy proxy;
    static int mitmPort;
    static Path debugDir;
    static final ConcurrentHashMap<String, Capture> captures = new ConcurrentHashMap<>();
    static final AtomicReference<WebSocketCapture> webSocketCapture = new AtomicReference<>();

    record Capture(String uri, String host, String authorization,
                   AtomicLong bytes, AtomicBoolean ended) {}
    record WebSocketCapture(String uri, String host, String authorization,
                            String requestedSubProtocol, Set<String> headerNames) {}

    @BeforeAll
    static void startRelay() throws Exception {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        Files.createDirectories(tempHome.resolve(".config/incus-spawn"));

        vertx = Vertx.vertx();
        gateway = vertx.createHttpServer(new HttpServerOptions()
                        .setWebSocketSubProtocols(List.of(ProxyConfig.BB_GATEWAY_SUBPROTOCOL)))
                .requestHandler(req -> {
                    var capture = new Capture(req.uri(), req.getHeader("Host"),
                            req.getHeader("Authorization"), new AtomicLong(), new AtomicBoolean());
                    captures.put(req.path(), capture);
                    req.handler(chunk -> capture.bytes().addAndGet(chunk.length()));
                    req.endHandler(ignored -> {
                        capture.ended().set(true);
                        req.response().end("gateway-ok");
                    });
                    req.exceptionHandler(ignored -> { });
                })
                .webSocketHandler(ws -> {
                    webSocketCapture.set(new WebSocketCapture(ws.uri(), ws.headers().get("Host"),
                            ws.headers().get("Authorization"),
                            ws.headers().get("Sec-WebSocket-Protocol"),
                            ws.headers().names().stream()
                                    .map(name -> name.toLowerCase(java.util.Locale.ROOT))
                                    .collect(java.util.stream.Collectors.toSet())));
                    ws.textMessageHandler(message -> ws.writeTextMessage("echo:" + message));
                });
        gateway.listen(ProxyConfig.BB_GATEWAY_PORT, ProxyConfig.BB_GATEWAY_HOST)
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        mitmPort = findFreePort();
        var healthPort = findFreePort();
        proxy = new MitmProxy(vertx, "127.0.0.1", mitmPort, healthPort,
                "127.0.0.1", new ProxyCredentials("", "", false, "", "", List.of()));
        debugDir = tempHome.resolve("debug");
        proxy.setDebugLog(new ApiTrafficLog(debugDir));

        var ready = new CountDownLatch(1);
        var thread = new Thread(() -> {
            try {
                proxy.start(ready::countDown);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "gateway-relay-test-proxy");
        thread.setDaemon(true);
        thread.start();
        assertTrue(ready.await(15, TimeUnit.SECONDS), "Proxy did not start in time");
    }

    @AfterAll
    static void stopRelay() throws Exception {
        try {
            if (proxy != null) proxy.stop();
            if (gateway != null) gateway.close()
                    .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
            if (vertx != null) vertx.close()
                    .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @BeforeEach
    void clearCaptures() throws Exception {
        captures.clear();
        webSocketCapture.set(null);
        if (Files.isDirectory(debugDir)) {
            try (var entries = Files.list(debugDir)) {
                for (var entry : entries.toList()) Files.delete(entry);
            }
        }
    }

    @Test
    void relaysSmallBodiesWithoutInjectionOrDebugCapture() throws Exception {
        var response = request(socket -> {
            writeAscii(socket, "POST /rpc?capability=caller-secret HTTP/1.1\r\n"
                    + "Host: bb.isx.internal\r\n"
                    + "Authorization: Bearer caller-capability\r\n"
                    + "Content-Length: 5\r\n"
                    + "Connection: close\r\n\r\n"
                    + "hello");
            socket.flush();
        });

        assertTrue(response.startsWith("HTTP/1.1 200"), response);
        var capture = awaitCapture("/rpc");
        assertEquals("/rpc?capability=caller-secret", capture.uri());
        assertEquals("bb.isx.internal", capture.host());
        assertEquals("Bearer caller-capability", capture.authorization());
        assertEquals(5, capture.bytes().get());
        assertTrue(capture.ended().get());
        try (var files = Files.list(debugDir)) {
            assertEquals(0, files.count(), "bb gateway traffic must bypass API debug capture");
        }
    }

    @Test
    void webSocketFramesRemainOrderedAndDebugLogOmitsGatewayUri() throws Exception {
        ProxyLog.setDebugEnabled(true);
        var client = vertx.createHttpClient(new HttpClientOptions()
                .setSsl(true).setTrustAll(true).setVerifyHost(false));
        try {
            var options = new WebSocketConnectOptions()
                    .setHost("127.0.0.1")
                    .setPort(mitmPort)
                    .setSsl(true)
                    .setAllowOriginHeader(false)
                    .setURI("/socket?capability=must-not-log")
                    .addHeader("Host", ProxyConfig.BB_GATEWAY_DOMAIN)
                    .addHeader("Authorization", "Bearer caller-capability")
                    .addSubProtocol(ProxyConfig.BB_GATEWAY_SUBPROTOCOL);
            var socket = client.webSocket(options)
                    .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            var replies = new CopyOnWriteArrayList<String>();
            var received = new CountDownLatch(64);
            socket.textMessageHandler(message -> {
                replies.add(message);
                received.countDown();
            });
            for (int i = 0; i < 64; i++) socket.writeTextMessage("message-" + i);

            assertTrue(received.await(5, TimeUnit.SECONDS), "Timed out waiting for relayed frames");
            for (int i = 0; i < 64; i++) {
                assertEquals("echo:message-" + i, replies.get(i));
            }
            var capture = webSocketCapture.get();
            assertNotNull(capture);
            assertEquals("/socket?capability=must-not-log", capture.uri());
            assertEquals(ProxyConfig.BB_GATEWAY_DOMAIN, capture.host());
            assertEquals("Bearer caller-capability", capture.authorization());
            assertEquals(ProxyConfig.BB_GATEWAY_SUBPROTOCOL, capture.requestedSubProtocol());
            assertTrue(Set.of(
                    "authorization", "connection", "host", "sec-websocket-key",
                    "sec-websocket-protocol", "sec-websocket-version", "upgrade",
                    "user-agent").containsAll(capture.headerNames()), capture.headerNames().toString());
            assertFalse(capture.headerNames().contains("origin"), capture.headerNames().toString());

            var lifecycleLog = Files.readString(Environment.proxyLifecycleLogFile());
            assertTrue(lifecycleLog.contains("WebSocket connected: bb.isx.internal"));
            assertFalse(lifecycleLog.contains("capability=must-not-log"), lifecycleLog);
            socket.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        } finally {
            ProxyLog.setDebugEnabled(false);
            client.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void rejectsDeclaredOversizeBeforeOpeningGatewayRequest() throws Exception {
        var response = request(socket -> {
            writeAscii(socket, "POST /declared?capability=must-not-log HTTP/1.1\r\n"
                    + "Host: bb.isx.internal\r\n"
                    + "Content-Length: " + (MitmProxy.BB_GATEWAY_MAX_BODY_BYTES + 1) + "\r\n"
                    + "Connection: close\r\n\r\n");
            socket.flush();
        });

        assertTrue(response.startsWith("HTTP/1.1 413"), response);
        Thread.sleep(200);
        assertFalse(captures.containsKey("/declared"));
    }

    @Test
    void rejectsDeclaredOversizeEventBatchWithPermanentBbError() throws Exception {
        var response = requestAll(socket -> {
            writeAscii(socket, "POST /internal/session/events HTTP/1.1\r\n"
                    + "Host: bb.isx.internal\r\n"
                    + "Content-Length: " + (MitmProxy.BB_GATEWAY_MAX_BODY_BYTES + 1) + "\r\n"
                    + "Connection: close\r\n\r\n");
            socket.flush();
        });

        assertTrue(response.startsWith("HTTP/1.1 400"), response);
        assertTrue(response.contains("Content-Type: application/json"), response);
        assertTrue(response.contains("\"code\":\"invalid_request\""), response);
        assertFalse(captures.containsKey("/internal/session/events"));
    }

    @Test
    void rejectsConflictingFramingWithoutLoggingRequestTarget() throws Exception {
        var response = request(socket -> {
            writeAscii(socket, "POST /conflicting?capability=conflicting-secret HTTP/1.1\r\n"
                    + "Host: bb.isx.internal\r\n"
                    + "Content-Length: 4\r\n"
                    + "Transfer-Encoding: chunked\r\n"
                    + "Connection: close\r\n\r\n"
                    + "0\r\n\r\n");
            socket.flush();
        });

        assertTrue(response.startsWith("HTTP/1.1 400"), response);
        Thread.sleep(200);
        assertFalse(captures.containsKey("/conflicting"));
        var lifecycleLog = Files.readString(Environment.proxyLifecycleLogFile());
        assertFalse(lifecycleLog.contains("conflicting-secret"), lifecycleLog);
    }

    @Test
    void rejectsChunkedEventBatchAsSoonAsStreamExceedsLimit() throws Exception {
        var response = requestAll(socket -> {
            writeAscii(socket, "POST /internal/session/events HTTP/1.1\r\n"
                    + "Host: bb.isx.internal\r\n"
                    + "Transfer-Encoding: chunked\r\n"
                    + "Connection: close\r\n\r\n");
            var chunk = new byte[1024 * 1024];
            for (int i = 0; i < 16; i++) writeChunk(socket, chunk);
            writeChunk(socket, new byte[]{1});
            socket.flush();
        });

        assertTrue(response.startsWith("HTTP/1.1 400"), response);
        assertTrue(response.contains("\"code\":\"invalid_request\""), response);
        var capture = awaitCapture("/internal/session/events");
        Thread.sleep(200);
        assertTrue(capture.bytes().get() <= MitmProxy.BB_GATEWAY_MAX_BODY_BYTES,
                "gateway received " + capture.bytes().get() + " bytes");
        assertFalse(capture.ended().get(), "oversize upload must abort the gateway request");
    }

    private static Capture awaitCapture(String path) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            var capture = captures.get(path);
            if (capture != null) return capture;
            Thread.sleep(20);
        }
        var capture = captures.get(path);
        assertNotNull(capture, "Gateway did not receive " + path);
        return capture;
    }

    private static String request(IoConsumer<OutputStream> body) throws Exception {
        try (var socket = tlsSocket()) {
            body.accept(socket.getOutputStream());
            return readLine(socket.getInputStream());
        }
    }

    private static String requestAll(IoConsumer<OutputStream> body) throws Exception {
        try (var socket = tlsSocket()) {
            body.accept(socket.getOutputStream());
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static SSLSocket tlsSocket() throws Exception {
        var trustAll = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        var context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{trustAll}, new SecureRandom());
        var socket = (SSLSocket) context.getSocketFactory()
                .createSocket("127.0.0.1", mitmPort);
        socket.setSoTimeout(15_000);
        var parameters = socket.getSSLParameters();
        parameters.setServerNames(List.of(new SNIHostName(ProxyConfig.BB_GATEWAY_DOMAIN)));
        socket.setSSLParameters(parameters);
        socket.startHandshake();
        return socket;
    }

    private static void writeChunk(OutputStream out, byte[] bytes) throws IOException {
        writeAscii(out, Integer.toHexString(bytes.length) + "\r\n");
        out.write(bytes);
        writeAscii(out, "\r\n");
    }

    private static void writeAscii(OutputStream out, String value) throws IOException {
        out.write(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static String readLine(InputStream in) throws IOException {
        var line = new StringBuilder();
        while (true) {
            var value = in.read();
            if (value == -1 || value == '\n') break;
            if (value != '\r') line.append((char) value);
        }
        return line.toString();
    }

    private static int findFreePort() throws IOException {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @FunctionalInterface
    private interface IoConsumer<T> {
        void accept(T value) throws Exception;
    }
}
