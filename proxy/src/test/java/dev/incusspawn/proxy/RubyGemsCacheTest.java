package dev.incusspawn.proxy;

import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpMethod;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RubyGemsCacheTest {

    private static final String RAKE_SHA =
            "46cb38dae65d7d74b6020a4ac9d48afed8eb8149c040eccf0523bec91907059d";
    private static final String NATIVE_SHA =
            "1111111111111111111111111111111111111111111111111111111111111111";

    @Test
    void rubyGemsDomainsAreBuiltIn() {
        for (var domain : ProxyConfig.RUBYGEMS_DOMAINS) {
            assertTrue(ProxyConfig.builtinInterceptedDomains().contains(domain));
            assertTrue(ProxyConfig.isInterceptedDomain(
                    domain, java.util.Set.of(), java.util.List.of()));
        }
    }

    @Test
    void recognizesOnlyCanonicalInfoAndGemPaths() {
        assertEquals("rake", RubyGemsCache.infoGemName("/info/rake"));
        assertEquals("rake-13.2.1.gem",
                RubyGemsCache.gemFilename("/gems/rake-13.2.1.gem"));
        assertNull(RubyGemsCache.infoGemName("/info/rake/extra"));
        assertNull(RubyGemsCache.gemFilename("/gems/../rake-13.2.1.gem"));
        assertNull(RubyGemsCache.gemFilename("/gems/rake%2F13.2.1.gem"));
    }

    @Test
    void parsesRubyPlatformAndPrereleaseChecksums() {
        var body = ("""
                created_at: 2026-09-01T00:00:00Z
                ---
                13.2.1 |checksum:%s,ruby:>= 2.3,created_at:2024-04-05T06:28:16Z
                14.0.0.beta.1-arm64-darwin dep:>= 1|checksum:%s,created_at:2026-01-01T00:00:00Z
                """).formatted(RAKE_SHA, NATIVE_SHA).getBytes();

        var parsed = RubyGemsCache.parseInfo("rake", body);

        assertEquals(RAKE_SHA, parsed.get("rake-13.2.1.gem"));
        assertEquals(NATIVE_SHA,
                parsed.get("rake-14.0.0.beta.1-arm64-darwin.gem"));
        assertEquals(2, parsed.size());
    }

    @Test
    void rejectsIncompleteOrAmbiguousCompactIndexInfo() {
        assertNull(RubyGemsCache.parseInfo("rake",
                ("---\n13.2.1 |created_at:2024-04-05\n").getBytes()));
        assertNull(RubyGemsCache.parseInfo("rake",
                ("---\n13.2.1 |checksum:" + RAKE_SHA
                        + ",checksum:" + NATIVE_SHA + "\n").getBytes()));
        assertNull(RubyGemsCache.parseInfo("rake",
                ("---\n13.2.1 |checksum:not-a-digest\n").getBytes()));
        assertNull(RubyGemsCache.parseInfo("rake",
                ("---\n13.2.1/evil |checksum:" + RAKE_SHA + "\n").getBytes()));
        assertNull(RubyGemsCache.parseInfo("rake",
                ("---\n13.2.1 dep:>= 1|extra|checksum:" + RAKE_SHA + "\n").getBytes()));
        assertNull(RubyGemsCache.parseInfo("rake",
                ("---\nversion-1.0 |checksum:" + RAKE_SHA + "\n").getBytes()));
    }

    @Test
    void replacesSnapshotsAndInvalidatesNonFullResponses() {
        var cache = new RubyGemsCache();
        var first = cache.beginObservation("index.rubygems.org", "rake");
        assertTrue(cache.publish(first, "\"one\"",
                ("---\n13.2.1 |checksum:" + RAKE_SHA + "\n").getBytes()));
        assertEquals(RAKE_SHA, cache.digestFor("rake-13.2.1.gem"));

        var second = cache.beginObservation("index.rubygems.org", "rake");
        assertTrue(cache.publish(second, "\"two\"",
                ("---\n14.0.0 |checksum:" + NATIVE_SHA + "\n").getBytes()));
        assertNull(cache.digestFor("rake-13.2.1.gem"));
        assertEquals(NATIVE_SHA, cache.digestFor("rake-14.0.0.gem"));

        var conditional = cache.beginObservation("index.rubygems.org", "rake");
        cache.observeNonFullResponse(conditional, 304, "\"two\"");
        assertEquals(NATIVE_SHA, cache.digestFor("rake-14.0.0.gem"));
        var partial = cache.beginObservation("index.rubygems.org", "rake");
        cache.observeNonFullResponse(partial, 206, "\"three\"");
        assertNull(cache.digestFor("rake-14.0.0.gem"));
    }

    @Test
    void olderResponsesCannotOverwriteNewerObservations() {
        var cache = new RubyGemsCache();
        var older = cache.beginObservation("index.rubygems.org", "rake");
        var newer = cache.beginObservation("index.rubygems.org", "rake");

        assertTrue(cache.publish(newer, "\"new\"",
                ("---\n14.0.0 |checksum:" + NATIVE_SHA + "\n").getBytes()));
        assertFalse(cache.publish(older, "\"old\"",
                ("---\n13.2.1 |checksum:" + RAKE_SHA + "\n").getBytes()));

        assertEquals(NATIVE_SHA, cache.digestFor("rake-14.0.0.gem"));
        assertNull(cache.digestFor("rake-13.2.1.gem"));
    }

    @Test
    void observationsAreScopedByOrigin() {
        var cache = new RubyGemsCache();
        var primary = cache.beginObservation("index.rubygems.org", "rake");
        assertTrue(cache.publish(primary, "\"index\"",
                ("---\n13.2.1 |checksum:" + RAKE_SHA + "\n").getBytes()));

        var other = cache.beginObservation("rubygems.org", "rake");
        cache.observeNonFullResponse(other, 206, "\"other\"");

        assertEquals(RAKE_SHA, cache.digestFor("rake-13.2.1.gem"));
    }

    @Test
    void eligibilityIsNarrowAndPreservesHttpSemantics() {
        var plain = MultiMap.caseInsensitiveMultiMap();
        assertTrue(RubyGemsCache.isCacheEligible(
                HttpMethod.GET, "/gems/rake-13.2.1.gem", null, plain));

        for (var header : new String[]{
                "Range", "If-Range", "If-None-Match", "If-Match",
                "If-Modified-Since", "If-Unmodified-Since",
                "Authorization", "Proxy-Authorization", "Cookie", "X-Gem-Api-Key"
        }) {
            var headers = MultiMap.caseInsensitiveMultiMap().add(header, "value");
            assertFalse(RubyGemsCache.isCacheEligible(
                    HttpMethod.GET, "/gems/rake-13.2.1.gem", null, headers), header);
        }

        assertFalse(RubyGemsCache.isCacheEligible(
                HttpMethod.HEAD, "/gems/rake-13.2.1.gem", null, plain));
        assertFalse(RubyGemsCache.isCacheEligible(
                HttpMethod.GET, "/gems/rake-13.2.1.gem", "token=value", plain));
        assertFalse(RubyGemsCache.isCacheEligible(
                HttpMethod.GET, "/gems/rake-13.2.1.gem", "", plain));
        assertFalse(RubyGemsCache.isCacheEligible(
                HttpMethod.GET, "/gems/rake-13.2.1.gem", null,
                MultiMap.caseInsensitiveMultiMap().add("Cache-Control", "no-cache")));
        assertFalse(RubyGemsCache.isCacheEligible(
                HttpMethod.GET, "/gems/rake-13.2.1.gem", null,
                MultiMap.caseInsensitiveMultiMap().add("Accept", "application/json")));
        assertTrue(RubyGemsCache.isCacheEligible(
                HttpMethod.GET, "/gems/rake-13.2.1.gem", null,
                MultiMap.caseInsensitiveMultiMap().add("Accept", "application/octet-stream")));
        assertFalse(RubyGemsCache.isCacheEligible(
                HttpMethod.GET, "/gems/rake-13.2.1.gem", null,
                MultiMap.caseInsensitiveMultiMap()
                        .add("Accept", "application/octet-stream;q=0, */*;q=1")));
        assertFalse(RubyGemsCache.isCacheEligible(
                HttpMethod.GET, "/gems/rake-13.2.1.gem", null,
                MultiMap.caseInsensitiveMultiMap().add("Accept-Encoding", "identity;q=0")));
        assertFalse(RubyGemsCache.isCacheEligible(
                HttpMethod.GET, "/gems/rake-13.2.1.gem", null,
                MultiMap.caseInsensitiveMultiMap().add("Accept-Encoding", "gzip, *;q=0")));
        assertFalse(RubyGemsCache.isCacheEligible(
                HttpMethod.GET, "/gems/rake-13.2.1.gem", null,
                MultiMap.caseInsensitiveMultiMap().add("Content-Length", "1")));
        assertTrue(RubyGemsCache.isCacheEligible(
                HttpMethod.GET, "/gems/rake-13.2.1.gem", null,
                MultiMap.caseInsensitiveMultiMap().add("Content-Length", "00")));
    }

    @Test
    void completedObservationsDoNotAccumulateGenerationState() {
        var cache = new RubyGemsCache();
        for (int i = 0; i < 100; i++) {
            var observation = cache.beginObservation("index.rubygems.org", "gem" + i);
            cache.observeNonFullResponse(observation, 404, null);
        }
        assertEquals(0, cache.activeObservationCount());
    }

    @Test
    void checksumAuthorityExpiresOnMonotonicTime() {
        var now = new AtomicLong(1);
        var cache = new RubyGemsCache(now::get);
        var observation = cache.beginObservation("index.rubygems.org", "rake");
        assertTrue(cache.publish(observation, "\"one\"",
                ("---\n13.2.1 |checksum:" + RAKE_SHA + "\n").getBytes()));
        assertEquals(RAKE_SHA, cache.digestFor("rake-13.2.1.gem"));

        now.addAndGet(RubyGemsCache.INFO_AUTHORITY_TTL_NANOS
                + TimeUnit.MILLISECONDS.toNanos(1));

        assertNull(cache.digestFor("rake-13.2.1.gem"));
    }

    @Test
    void contentAddressedObjectsAreVerifiedOncePerProcess(@TempDir Path temp) throws Exception {
        var cache = new RubyGemsCache();
        var content = "gem payload".getBytes();
        var digest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content));
        var object = RubyGemsCache.objectPath(temp, digest);
        Files.createDirectories(object.getParent());
        Files.write(object, content);

        var entry = cache.verifyExisting(temp, digest);
        assertEquals(object, entry.path());
        assertEquals(content.length, entry.size());

        Files.writeString(object, "corrupted after first verification");
        assertEquals(object, cache.verifyExisting(temp, digest).path(),
                "a host-private object is hashed once per proxy process");

        var restarted = new RubyGemsCache();
        assertNull(restarted.verifyExisting(temp, digest));
        assertFalse(Files.exists(object));
    }
}
