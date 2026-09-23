package dev.incusspawn.proxy;

import dev.incusspawn.DerEncoder;
import dev.incusspawn.Environment;

import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.dns.AddressResolverOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.SocketAddress;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandCredentialProxyTest {

    private static final String HOST = "credential.example.test";
    private static final String CREDENTIAL_ONE = "token_abcdefghijklmnop";
    private static final String CREDENTIAL_TWO = "token_qrstuvwxyz123456";

    @TempDir
    static Path tempHome;

    private static String originalHome;
    private static Vertx vertx;
    private static HttpServer upstream;
    private static HttpClient client;
    private static MitmProxy proxy;
    private static CommandCredentialConfig.Rule rule;
    private static int mitmPort;
    private static int healthPort;
    private static Path debugDir;

    private static final ConcurrentLinkedQueue<Integer> responseStatuses =
            new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Capture> captures =
            new ConcurrentLinkedQueue<>();
    private static final AtomicInteger upstreamWebSockets = new AtomicInteger();

    record Capture(String authorization, String apiKey, String usage,
                   String affinity, String requestContext, byte[] body) {}

    record Response(int status, MultiMap headers, String body) {}

    @BeforeAll
    static void startProxy() throws Exception {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        Files.createDirectories(tempHome.resolve(".config/incus-spawn"));
        var baseRule = CommandCredentialBrokerTest.rule();
        rule = new CommandCredentialConfig.Rule(
                baseRule.id(), baseRule.host(), baseRule.argv(), baseRule.validationRegex(),
                baseRule.validationPattern(), baseRule.carriers(), baseRule.timeoutSeconds(),
                baseRule.maxOutputBytes(), 64, baseRule.cacheTtlSeconds(),
                baseRule.failureTtlSeconds(), baseRule.label(), baseRule.remediation());

        var ca = CertificateAuthority.loadOrCreate();
        var leaf = ca.generateDomainCert(HOST);
        var keyCert = new PemKeyCertOptions()
                .setKeyValue(Buffer.buffer(DerEncoder.toPem(
                        "PRIVATE KEY", leaf.key().getEncoded())))
                .setCertValue(Buffer.buffer(DerEncoder.toPem(
                        "CERTIFICATE", leaf.cert().getEncoded())));
        var resolver = new AddressResolverOptions().setHostsValue(Buffer.buffer(
                "127.0.0.1 " + HOST + "\n"));
        vertx = Vertx.vertx(new VertxOptions().setAddressResolverOptions(resolver));

        upstream = vertx.createHttpServer(new HttpServerOptions()
                        .setSsl(true).setKeyCertOptions(keyCert))
                .requestHandler(request -> request.body().onSuccess(body -> {
                    captures.add(new Capture(
                            request.getHeader("Authorization"),
                            request.getHeader("x-api-key"),
                            request.getHeader("X-Usage-Tag"),
                            request.getHeader("X-Session-Affinity"),
                            request.getHeader("X-Request-Context"),
                            body.getBytes()));
                    var status = responseStatuses.poll();
                    request.response().setStatusCode(status != null ? status : 200)
                            .putHeader("Content-Type", "application/json")
                            .end("{\"upstream\":true}");
                }))
                .webSocketHandler(socket -> {
                    upstreamWebSockets.incrementAndGet();
                    socket.close();
                });
        var upstreamPort = upstream.listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture()
                .get(5, TimeUnit.SECONDS).actualPort();

        mitmPort = freePort();
        healthPort = freePort();
        proxy = new MitmProxy(vertx, "127.0.0.1", mitmPort, healthPort,
                "127.0.0.1", new ProxyCredentials("", "", false, "", "", List.of()),
                new CommandCredentialConfig(List.of(rule)));
        proxy.upstreamApiPort = upstreamPort;
        proxy.upstreamApiSsl = true;
        proxy.upstreamTrustAll = true;
        proxy.overrideDns(HOST, "127.0.0.1");
        debugDir = tempHome.resolve("api-debug");
        proxy.setDebugLog(new ApiTrafficLog(debugDir));
        proxy.setCommandCredentialBroker(rule.id(), brokerReturning(CREDENTIAL_ONE));

        var ready = new CountDownLatch(1);
        var proxyThread = new Thread(() -> {
            try {
                proxy.start(ready::countDown);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "command-credential-proxy-test");
        proxyThread.setDaemon(true);
        proxyThread.start();
        assertTrue(ready.await(15, TimeUnit.SECONDS), "Proxy did not start in time");

        client = vertx.createHttpClient(new HttpClientOptions()
                .setSsl(true).setTrustAll(true).setVerifyHost(false));
    }

    @AfterAll
    static void stopProxy() throws Exception {
        try {
            if (client != null) client.close()
                    .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
            if (proxy != null) proxy.stop();
            if (upstream != null) upstream.close()
                    .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
            if (vertx != null) vertx.close()
                    .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @BeforeEach
    void reset() throws Exception {
        captures.clear();
        responseStatuses.clear();
        upstreamWebSockets.set(0);
        proxy.clearAuthError();
        proxy.clearCommandCredentialProblem(rule.id());
        proxy.setCommandCredentialBroker(rule.id(), brokerReturning(CREDENTIAL_ONE));
        try (var entries = Files.list(debugDir)) {
            for (var entry : entries.toList()) Files.delete(entry);
        }
    }

    @Test
    void replacesBothExactCarrierPlaceholdersAndPreservesOtherHeaders() throws Exception {
        var body = "{\"model\":\"example-test\"}";
        var headers = MultiMap.caseInsensitiveMultiMap()
                .add("Authorization", "Bearer container-placeholder")
                .add("x-api-key", "container-placeholder")
                .add("X-Usage-Tag", "usage-value")
                .add("X-Session-Affinity", "affinity-value")
                .add("X-Request-Context", "request-context-value");

        var response = request(HttpMethod.POST, "/v1/messages", headers, body);

        assertEquals(200, response.status());
        var capture = captures.remove();
        assertEquals("Bearer " + CREDENTIAL_ONE, capture.authorization());
        assertEquals(CREDENTIAL_ONE, capture.apiKey());
        assertEquals("usage-value", capture.usage());
        assertEquals("affinity-value", capture.affinity());
        assertEquals("request-context-value", capture.requestContext());
        assertEquals(body, new String(capture.body()));
        assertNoDebugCaptureOrCredential(CREDENTIAL_ONE);
    }

    @Test
    void preservesWhicheverConfiguredCarrierWasPresent() throws Exception {
        request(HttpMethod.GET, "/authorization", MultiMap.caseInsensitiveMultiMap()
                .add("Authorization", "Bearer container-placeholder"), null);
        request(HttpMethod.GET, "/api-key", MultiMap.caseInsensitiveMultiMap()
                .add("x-api-key", "container-placeholder"), null);

        var captured = new ArrayList<>(captures);
        assertEquals("Bearer " + CREDENTIAL_ONE, captured.get(0).authorization());
        assertNull(captured.get(0).apiKey());
        assertNull(captured.get(1).authorization());
        assertEquals(CREDENTIAL_ONE, captured.get(1).apiKey());
    }

    @Test
    void rejectsMissingOrNonExactCarriersWithoutBrokerOrUpstream() throws Exception {
        var brokerCalls = new AtomicInteger();
        proxy.setCommandCredentialBroker(rule.id(), new CommandCredentialBroker(vertx, rule,
                System::currentTimeMillis, () -> {
                    brokerCalls.incrementAndGet();
                    return CREDENTIAL_ONE;
                }));

        var missing = request(HttpMethod.GET, "/missing",
                MultiMap.caseInsensitiveMultiMap(), null);
        var basic = request(HttpMethod.GET, "/basic",
                MultiMap.caseInsensitiveMultiMap().add("Authorization", "Basic abc"), null);
        var wrongPlaceholder = request(HttpMethod.GET, "/wrong",
                MultiMap.caseInsensitiveMultiMap()
                        .add("Authorization", "Bearer another-placeholder"), null);

        assertEquals(400, missing.status());
        assertEquals("no-store", missing.headers().get("Cache-Control"));
        assertEquals(400, basic.status());
        assertEquals(400, wrongPlaceholder.status());
        assertEquals(0, brokerCalls.get());
        assertTrue(captures.isEmpty());
    }

    @Test
    void declaredAndObservedBodyLimitsFailClosed() throws Exception {
        var tooLarge = request(HttpMethod.POST, "/too-large",
                MultiMap.caseInsensitiveMultiMap()
                        .add("Authorization", "Bearer container-placeholder"),
                "x".repeat(rule.bodyLimitBytes() + 1));

        assertEquals(413, tooLarge.status());
        assertEquals("no-store", tooLarge.headers().get("Cache-Control"));
        assertTrue(captures.isEmpty());
    }

    @Test
    void first401ConditionallyRefreshesAndRetriesOriginalBodyOnce() throws Exception {
        var brokerCalls = new AtomicInteger();
        proxy.setCommandCredentialBroker(rule.id(), new CommandCredentialBroker(vertx, rule,
                System::currentTimeMillis,
                () -> brokerCalls.incrementAndGet() == 1
                        ? CREDENTIAL_ONE : CREDENTIAL_TWO));
        responseStatuses.add(401);
        responseStatuses.add(200);
        var body = "{\"same\":\"body\"}";

        var response = request(HttpMethod.POST, "/retry",
                MultiMap.caseInsensitiveMultiMap()
                        .add("Authorization", "Bearer container-placeholder"), body);

        assertEquals(200, response.status());
        assertEquals(2, brokerCalls.get());
        var attempts = new ArrayList<>(captures);
        assertEquals(2, attempts.size());
        assertEquals("Bearer " + CREDENTIAL_ONE, attempts.get(0).authorization());
        assertEquals("Bearer " + CREDENTIAL_TWO, attempts.get(1).authorization());
        assertEquals(body, new String(attempts.get(0).body()));
        assertEquals(body, new String(attempts.get(1).body()));
        assertFalse(health().contains("commandCredentials"));
    }

    @Test
    void second401InvalidatesAndRelaysWithoutLooping() throws Exception {
        var brokerCalls = new AtomicInteger();
        var broker = new CommandCredentialBroker(vertx, rule, System::currentTimeMillis,
                () -> brokerCalls.incrementAndGet() == 1
                        ? CREDENTIAL_ONE : CREDENTIAL_TWO);
        proxy.setCommandCredentialBroker(rule.id(), broker);
        responseStatuses.add(401);
        responseStatuses.add(401);

        var response = request(HttpMethod.GET, "/twice",
                MultiMap.caseInsensitiveMultiMap()
                        .add("x-api-key", "container-placeholder"), null);

        assertEquals(401, response.status());
        assertEquals(2, captures.size());
        assertEquals(2, brokerCalls.get());
        assertNull(broker.cachedLease());
        var health = health();
        assertTrue(health.contains("HTTP 401"), health);
        assertTrue(health.contains("Example credential gateway"), health);
        assertTrue(health.contains("Repair host credential access"), health);
    }

    @Test
    void brokerFailureReturnsSafeNoStore502AndGenericHealthProblem() throws Exception {
        proxy.setCommandCredentialBroker(rule.id(), new CommandCredentialBroker(vertx, rule,
                System::currentTimeMillis,
                () -> { throw new IllegalStateException("credential command failed (exit 1)"); }));

        var response = request(HttpMethod.GET, "/broker-failure",
                MultiMap.caseInsensitiveMultiMap()
                        .add("Authorization", "Bearer container-placeholder"), null);

        assertEquals(502, response.status());
        assertEquals("no-store", response.headers().get("Cache-Control"));
        assertEquals("", response.body());
        assertTrue(captures.isEmpty());

        var health = health();
        assertTrue(health.contains("\"commandCredentials\""), health);
        assertTrue(health.contains("Example credential gateway"), health);
        assertTrue(health.contains("Repair host credential access"), health);
        assertFalse(health.contains("credential-helper"), health);
        assertFalse(health.contains(CREDENTIAL_ONE), health);
    }

    @Test
    void acceptedResponseClearsOnlyItsOwnProblem() throws Exception {
        proxy.setCommandCredentialProblem(rule, "previous failure");
        proxy.setAuthError("Claude OAuth token rejected", "isx init");
        proxy.clearAuthError();
        assertTrue(health().contains("previous failure"),
                "success from another auth source must not clear command state");

        proxy.setAuthError("Claude OAuth token rejected", "isx init");
        var response = request(HttpMethod.GET, "/accepted",
                MultiMap.caseInsensitiveMultiMap()
                        .add("x-api-key", "container-placeholder"), null);

        assertEquals(200, response.status());
        var health = health();
        assertFalse(health.contains("commandCredentials"), health);
        assertEquals("Claude OAuth token rejected", proxy.authError,
                "command credential success must not clear another source's problem");
    }

    @Test
    void ordinaryReloadPreservesStartupLoadedRules() throws Exception {
        proxy.reload();

        var response = request(HttpMethod.GET, "/after-reload",
                MultiMap.caseInsensitiveMultiMap()
                        .add("Authorization", "Bearer container-placeholder"), null);

        assertEquals(200, response.status());
        assertEquals("Bearer " + CREDENTIAL_ONE, captures.remove().authorization());
    }

    @Test
    void rejectsWebSocketBeforeOpeningUpstream() {
        var options = new WebSocketConnectOptions()
                .setHost("127.0.0.1")
                .setPort(mitmPort)
                .setSsl(true)
                .setURI("/v1/realtime")
                .addHeader("Host", HOST)
                .addHeader("Authorization", "Bearer container-placeholder");

        var failure = assertThrows(ExecutionException.class, () -> client.webSocket(options)
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertTrue(failure.getCause().getMessage().contains("501"),
                failure.getCause().toString());
        assertEquals(0, upstreamWebSockets.get());
    }

    private static CommandCredentialBroker brokerReturning(String credential) {
        return new CommandCredentialBroker(vertx, rule,
                System::currentTimeMillis, () -> credential);
    }

    private static Response request(HttpMethod method, String uri, MultiMap headers, String body)
            throws Exception {
        var options = new RequestOptions()
                .setMethod(method)
                .setHost(HOST)
                .setPort(mitmPort)
                .setSsl(true)
                .setServer(SocketAddress.inetSocketAddress(mitmPort, "127.0.0.1"))
                .setURI(uri)
                .setHeaders(headers);
        var request = client.request(options).toCompletionStage().toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        var response = (body == null ? request.send() : request.send(body))
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        var responseBody = response.body().toCompletionStage().toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        var responseHeaders = MultiMap.caseInsensitiveMultiMap().setAll(response.headers());
        return new Response(response.statusCode(), responseHeaders, responseBody.toString());
    }

    private static String health() throws Exception {
        var response = client.request(new RequestOptions()
                        .setMethod(HttpMethod.GET)
                        .setHost("127.0.0.1")
                        .setPort(healthPort)
                        .setSsl(false)
                        .setURI("/health"))
                .compose(request -> request.send())
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        return response.body().toCompletionStage().toCompletableFuture()
                .get(5, TimeUnit.SECONDS).toString();
    }

    private static void assertNoDebugCaptureOrCredential(String credential) throws Exception {
        try (var files = Files.list(debugDir)) {
            assertEquals(0, files.count(),
                    "command credential traffic must bypass API debug capture");
        }
        var lifecycleLog = Environment.proxyLifecycleLogFile();
        if (Files.exists(lifecycleLog)) {
            assertFalse(Files.readString(lifecycleLog).contains(credential));
        }
    }

    private static int freePort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
