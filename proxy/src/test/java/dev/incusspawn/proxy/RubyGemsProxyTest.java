package dev.incusspawn.proxy;

import dev.incusspawn.DerEncoder;

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
import io.vertx.core.net.PemKeyCertOptions;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RubyGemsProxyTest {

    private static final String GEM_HOST = "rubygems.org";
    private static final String INDEX_HOST = "index.rubygems.org";

    @TempDir
    static Path tempHome;

    private static String originalHome;
    private static Vertx vertx;
    private static HttpServer upstream;
    private static HttpClient client;
    private static MitmProxy proxy;
    private static int mitmPort;

    private static final Map<String, byte[]> payloads = new ConcurrentHashMap<>();
    private static final Map<String, String> advertisedDigests = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();

    record Response(int status, MultiMap headers, byte[] body) {}

    @BeforeAll
    static void startProxy() throws Exception {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        Files.createDirectories(tempHome.resolve(".config/incus-spawn"));

        var ca = CertificateAuthority.loadOrCreate();
        var leaf = ca.generateDomainCert(GEM_HOST);
        var keyCert = new PemKeyCertOptions()
                .setKeyValue(Buffer.buffer(DerEncoder.toPem(
                        "PRIVATE KEY", leaf.key().getEncoded())))
                .setCertValue(Buffer.buffer(DerEncoder.toPem(
                        "CERTIFICATE", leaf.cert().getEncoded())));
        var resolver = new AddressResolverOptions().setHostsValue(Buffer.buffer(
                "127.0.0.1 " + GEM_HOST + "\n127.0.0.1 " + INDEX_HOST + "\n"));
        vertx = Vertx.vertx(new VertxOptions().setAddressResolverOptions(resolver));

        upstream = vertx.createHttpServer(new HttpServerOptions()
                        .setSsl(true).setKeyCertOptions(keyCert))
                .requestHandler(RubyGemsProxyTest::handleUpstream);
        var upstreamPort = upstream.listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture()
                .get(5, TimeUnit.SECONDS).actualPort();

        mitmPort = freePort();
        var healthPort = freePort();
        proxy = new MitmProxy(vertx, "127.0.0.1", mitmPort, healthPort,
                "127.0.0.1", new ProxyCredentials("", "", false, "", "", List.of()));
        proxy.upstreamRubyGemsPort = upstreamPort;
        proxy.upstreamRubyGemsSsl = true;
        proxy.upstreamTrustAll = true;
        proxy.overrideDns(GEM_HOST, "127.0.0.1");
        proxy.overrideDns(INDEX_HOST, "127.0.0.1");

        var ready = new CountDownLatch(1);
        var thread = new Thread(() -> {
            try {
                proxy.start(ready::countDown);
            } catch (Exception error) {
                error.printStackTrace();
            }
        }, "rubygems-proxy-test");
        thread.setDaemon(true);
        thread.start();
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

    @Test
    void relaysCompactIndexBodyAndValidatorsUnchanged() throws Exception {
        register("headergem", "metadata payload".getBytes(), null);

        var response = request(INDEX_HOST, "/info/headergem", MultiMap.caseInsensitiveMultiMap());

        assertEquals(200, response.status());
        assertEquals("\"headergem-etag\"", response.headers().get("ETag"));
        assertEquals("sha-256=\"opaque-representation-digest\"",
                response.headers().get("Repr-Digest"));
        assertTrue(new String(response.body()).startsWith("---\n1.0.0 |checksum:"));
    }

    @Test
    void verifiesStoresAndReusesGemPayload() throws Exception {
        var payload = "cachegem payload".getBytes();
        register("cachegem", payload, null);
        request(INDEX_HOST, "/info/cachegem", MultiMap.caseInsensitiveMultiMap());

        var first = request(GEM_HOST, "/gems/cachegem-1.0.0.gem",
                MultiMap.caseInsensitiveMultiMap());
        var second = request(GEM_HOST, "/gems/cachegem-1.0.0.gem",
                MultiMap.caseInsensitiveMultiMap());

        assertEquals(200, first.status());
        assertEquals(200, second.status());
        assertEquals(new String(payload), new String(first.body()));
        assertEquals(new String(payload), new String(second.body()));
        assertEquals(1, requestCount("/gems/cachegem-1.0.0.gem"));

        var digest = sha256(payload);
        assertTrue(Files.isRegularFile(RubyGemsCache.objectPath(
                tempHome.resolve(".cache/incus-spawn/rubygems"), digest)));
    }

    @Test
    void coalescesConcurrentColdMisses() throws Exception {
        var payload = "parallelgem payload".repeat(10_000).getBytes();
        register("parallelgem", payload, null);
        request(INDEX_HOST, "/info/parallelgem", MultiMap.caseInsensitiveMultiMap());

        var futures = new ArrayList<CompletableFuture<Response>>();
        for (int i = 0; i < 24; i++) {
            futures.add(requestAsync(GEM_HOST, "/gems/parallelgem-1.0.0.gem",
                    MultiMap.caseInsensitiveMultiMap()));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .get(10, TimeUnit.SECONDS);

        for (var future : futures) {
            var response = future.get();
            assertEquals(200, response.status());
            assertEquals(new String(payload), new String(response.body()));
        }
        assertEquals(1, requestCount("/gems/parallelgem-1.0.0.gem"));
    }

    @Test
    void rechecksMetadataAuthorizationAfterAColdFill() throws Exception {
        var payload = "invalidation payload".repeat(10_000).getBytes();
        register("invalidationgem", payload, null);
        request(INDEX_HOST, "/info/invalidationgem", MultiMap.caseInsensitiveMultiMap());

        var download = requestAsync(GEM_HOST, "/gems/invalidationgem-1.0.0.gem",
                MultiMap.caseInsensitiveMultiMap());
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (requestCount("/gems/invalidationgem-1.0.0.gem") == 0
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        request(INDEX_HOST, "/info/invalidationgem",
                MultiMap.caseInsensitiveMultiMap().add("Range", "bytes=4-"));

        assertEquals(200, download.get(10, TimeUnit.SECONDS).status());
        assertEquals(2, requestCount("/gems/invalidationgem-1.0.0.gem"),
                "a superseded authorization must relay instead of serving the filled object");
    }

    @Test
    void rejectsChecksumMismatchWithoutPublishingObject() throws Exception {
        var payload = "not the advertised content".getBytes();
        var advertised = "0".repeat(64);
        register("brokengem", payload, advertised);
        request(INDEX_HOST, "/info/brokengem", MultiMap.caseInsensitiveMultiMap());

        var response = request(GEM_HOST, "/gems/brokengem-1.0.0.gem",
                MultiMap.caseInsensitiveMultiMap());

        assertEquals(502, response.status());
        assertFalse(Files.exists(RubyGemsCache.objectPath(
                tempHome.resolve(".cache/incus-spawn/rubygems"), advertised)));
    }

    @Test
    void bypassesWarmCacheForRangeConditionalAndQueryRequests() throws Exception {
        var payload = "semantics payload".getBytes();
        register("semanticsgem", payload, null);
        request(INDEX_HOST, "/info/semanticsgem", MultiMap.caseInsensitiveMultiMap());
        request(GEM_HOST, "/gems/semanticsgem-1.0.0.gem",
                MultiMap.caseInsensitiveMultiMap());
        assertEquals(1, requestCount("/gems/semanticsgem-1.0.0.gem"));

        var range = request(GEM_HOST, "/gems/semanticsgem-1.0.0.gem",
                MultiMap.caseInsensitiveMultiMap().add("Range", "bytes=0-3"));
        assertEquals(206, range.status());
        assertEquals("bytes 0-3/" + payload.length, range.headers().get("Content-Range"));
        assertEquals("sema", new String(range.body()));

        var conditional = request(GEM_HOST, "/gems/semanticsgem-1.0.0.gem",
                MultiMap.caseInsensitiveMultiMap().add("If-None-Match", "\"gem-etag\""));
        assertEquals(304, conditional.status());

        var query = request(GEM_HOST, "/gems/semanticsgem-1.0.0.gem?download=1",
                MultiMap.caseInsensitiveMultiMap());
        assertEquals(200, query.status());
        assertEquals(4, requestCount("/gems/semanticsgem-1.0.0.gem"));
    }

    @Test
    void relaysCompactIndexRangeWithoutPublishingPartialMetadata() throws Exception {
        register("rangeinfogem", "range info payload".getBytes(), null);

        var ranged = request(INDEX_HOST, "/info/rangeinfogem",
                MultiMap.caseInsensitiveMultiMap().add("Range", "bytes=4-"));
        var gem = request(GEM_HOST, "/gems/rangeinfogem-1.0.0.gem",
                MultiMap.caseInsensitiveMultiMap());

        assertEquals(206, ranged.status());
        assertEquals("bytes 4-7/8", ranged.headers().get("Content-Range"));
        assertEquals("tail", new String(ranged.body()));
        assertEquals(200, gem.status());
        assertEquals(1, requestCount("/gems/rangeinfogem-1.0.0.gem"),
                "checksum-free payload should be relayed, not cached");
    }

    private static void register(String gem, byte[] payload, String advertisedDigest) {
        var filename = gem + "-1.0.0.gem";
        payloads.put(filename, payload);
        advertisedDigests.put(gem,
                advertisedDigest != null ? advertisedDigest : sha256(payload));
        requests.remove("/info/" + gem);
        requests.remove("/gems/" + filename);
    }

    private static void handleUpstream(io.vertx.core.http.HttpServerRequest request) {
        requests.computeIfAbsent(request.path(), ignored -> new AtomicInteger()).incrementAndGet();

        if (request.path().startsWith("/info/")) {
            var gem = request.path().substring("/info/".length());
            if (request.getHeader("Range") != null) {
                request.response().setStatusCode(206)
                        .putHeader("Content-Range", "bytes 4-7/8")
                        .putHeader("ETag", "\"" + gem + "-range-etag\"")
                        .end("tail");
                return;
            }
            var digest = advertisedDigests.get(gem);
            request.response()
                    .putHeader("Content-Type", "text/plain; charset=utf-8")
                    .putHeader("ETag", "\"" + gem + "-etag\"")
                    .putHeader("Repr-Digest", "sha-256=\"opaque-representation-digest\"")
                    .putHeader("Accept-Ranges", "bytes")
                    .end("---\n1.0.0 |checksum:" + digest
                            + ",created_at:2026-09-23T00:00:00Z\n");
            return;
        }

        if (request.path().startsWith("/gems/")) {
            var filename = request.path().substring("/gems/".length());
            var payload = payloads.get(filename);
            if (request.getHeader("If-None-Match") != null) {
                request.response().setStatusCode(304).putHeader("ETag", "\"gem-etag\"").end();
                return;
            }
            if (request.getHeader("Range") != null) {
                request.response().setStatusCode(206)
                        .putHeader("Content-Range", "bytes 0-3/" + payload.length)
                        .end(Buffer.buffer().appendBytes(payload, 0, 4));
                return;
            }
            var send = (Runnable) () -> request.response()
                    .putHeader("Content-Type", "application/octet-stream")
                    .putHeader("ETag", "\"gem-etag\"")
                    .end(Buffer.buffer(payload));
            if (filename.startsWith("parallelgem-")
                    || filename.startsWith("invalidationgem-")) {
                vertx.setTimer(100, ignored -> send.run());
            } else {
                send.run();
            }
            return;
        }

        request.response().setStatusCode(404).end();
    }

    private static int requestCount(String path) {
        var count = requests.get(path);
        return count == null ? 0 : count.get();
    }

    private static Response request(String host, String uri, MultiMap headers) throws Exception {
        return requestAsync(host, uri, headers).get(10, TimeUnit.SECONDS);
    }

    private static CompletableFuture<Response> requestAsync(
            String host, String uri, MultiMap headers) {
        var options = new RequestOptions()
                .setMethod(HttpMethod.GET)
                .setHost("127.0.0.1")
                .setPort(mitmPort)
                .setSsl(true)
                .setURI(uri);
        var future = new CompletableFuture<Response>();
        client.request(options).onSuccess(request -> {
            request.putHeader("Host", host);
            request.headers().addAll(headers);
            request.send().onSuccess(response -> response.body().onSuccess(body ->
                    future.complete(new Response(
                            response.statusCode(), response.headers(), body.getBytes()))
            ).onFailure(future::completeExceptionally)).onFailure(future::completeExceptionally);
        }).onFailure(future::completeExceptionally);
        return future;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    private static int freePort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
