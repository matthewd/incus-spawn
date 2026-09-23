package dev.incusspawn.proxy;

import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpMethod;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Integrity metadata and filesystem layout for the transparent RubyGems payload cache.
 *
 * <p>The authoritative checksums are learned only from complete Compact Index
 * {@code /info/<gem>} responses observed by this proxy process. Metadata is never
 * persisted as a trust root. Persisted content-addressed objects are re-verified
 * once per process before they are served.</p>
 */
final class RubyGemsCache {

    static final long MAX_INFO_BYTES = 16L * 1024 * 1024;
    static final long MAX_GEM_BYTES = 512L * 1024 * 1024;
    static final long INFO_AUTHORITY_TTL_NANOS = 10L * 60 * 1_000_000_000;
    private static final int MAX_INFO_SNAPSHOTS = 2_048;
    private static final int MAX_INFO_ENTRIES = 100_000;
    private static final int MAX_INFO_ENTRIES_PER_GEM = 100_000;
    private static final int MAX_VERIFIED_OBJECTS = 10_000;

    private static final Pattern GEM_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+\\-]*");
    private static final Pattern GEM_PATH = Pattern.compile(
            "/gems/([A-Za-z0-9][A-Za-z0-9._+\\-]*\\.gem)");
    private static final Pattern INFO_PATH = Pattern.compile(
            "/info/([A-Za-z0-9][A-Za-z0-9._+\\-]*)");
    private static final Pattern SHA256 = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern VERSION_PLATFORM = Pattern.compile(
            "[0-9][0-9A-Za-z]*(?:\\.[0-9A-Za-z]+)*(?:-[A-Za-z0-9][A-Za-z0-9._+\\-]*)?");

    record InfoKey(String origin, String gemName) {}
    record Observation(InfoKey key, long generation, InfoSnapshot previous) {}
    record InfoSnapshot(String etag, Map<String, String> digests, long observedAtNanos) {}
    record CacheEntry(Path path, long size) {}

    private final Map<InfoKey, InfoSnapshot> snapshots = new HashMap<>();
    private final Map<InfoKey, Long> activeObservations = new HashMap<>();
    private final AtomicLong nextObservationGeneration = new AtomicLong();
    private final LongSupplier monotonicNanos;
    private volatile Map<String, String> digestsByFilename = Map.of();
    private final Set<String> verifiedObjects = ConcurrentHashMap.newKeySet();

    RubyGemsCache() {
        this(System::nanoTime);
    }

    RubyGemsCache(LongSupplier monotonicNanos) {
        this.monotonicNanos = monotonicNanos;
    }

    static String infoGemName(String path) {
        if (path == null) return null;
        var matcher = INFO_PATH.matcher(path);
        return matcher.matches() ? matcher.group(1) : null;
    }

    static String gemFilename(String path) {
        if (path == null) return null;
        var matcher = GEM_PATH.matcher(path);
        return matcher.matches() ? matcher.group(1) : null;
    }

    static boolean isCacheEligible(HttpMethod method, String path, String query,
                                   MultiMap headers) {
        if (method != HttpMethod.GET || gemFilename(path) == null) return false;
        if (query != null) return false;

        for (var value : headers.getAll("Content-Length")) {
            for (var item : value.split(",", -1)) {
                var normalized = item.strip();
                if (normalized.isEmpty()
                        || !normalized.chars().allMatch(c -> c >= '0' && c <= '9')
                        || normalized.chars().anyMatch(c -> c != '0')) {
                    return false;
                }
            }
        }
        if (headers.contains("Transfer-Encoding")) return false;

        for (var name : new String[]{
                "Range", "If-Range", "If-None-Match", "If-Match",
                "If-Modified-Since", "If-Unmodified-Since",
                "Authorization", "Proxy-Authorization", "Cookie", "X-Gem-Api-Key"
        }) {
            if (headers.contains(name)) return false;
        }

        if (headers.contains("Cache-Control") || headers.contains("Pragma")) return false;

        if (!acceptsMediaType(headers.getAll("Accept"), "application/octet-stream")) {
            return false;
        }
        return acceptsIdentityEncoding(headers.getAll("Accept-Encoding"));
    }

    private static boolean acceptsMediaType(java.util.List<String> values, String mediaType) {
        if (values.isEmpty() || values.stream().allMatch(String::isBlank)) return true;
        var slash = mediaType.indexOf('/');
        var typeWildcard = mediaType.substring(0, slash) + "/*";
        Double exact = null;
        Double type = null;
        Double wildcard = null;
        for (var item : String.join(",", values).split(",")) {
            var parts = item.trim().split(";", -1);
            var range = parts[0].strip();
            var quality = quality(parts);
            if (range.equalsIgnoreCase(mediaType)) exact = quality;
            else if (range.equalsIgnoreCase(typeWildcard)) type = quality;
            else if (range.equals("*/*")) wildcard = quality;
        }
        if (exact != null) return exact > 0;
        if (type != null) return type > 0;
        return wildcard != null && wildcard > 0;
    }

    static boolean acceptsIdentityEncoding(java.util.List<String> values) {
        if (values.isEmpty() || values.stream().allMatch(String::isBlank)) return true;
        Double identityQuality = null;
        Double wildcardQuality = null;
        for (var item : String.join(",", values).split(",")) {
            var parts = item.trim().split(";", -1);
            var coding = parts[0].trim();
            var quality = quality(parts);
            if ("identity".equalsIgnoreCase(coding)) identityQuality = quality;
            if ("*".equals(coding)) wildcardQuality = quality;
        }
        if (identityQuality != null) return identityQuality > 0;
        return wildcardQuality == null || wildcardQuality > 0;
    }

    private static double quality(String[] parameters) {
        double quality = 1.0;
        for (int i = 1; i < parameters.length; i++) {
            var parameter = parameters[i].trim();
            var equals = parameter.indexOf('=');
            if (equals < 0 || !"q".equalsIgnoreCase(parameter.substring(0, equals).trim())) {
                continue;
            }
            try {
                quality = Double.parseDouble(parameter.substring(equals + 1).trim());
                if (quality < 0 || quality > 1) return 0;
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return quality;
    }

    synchronized Observation beginObservation(String origin, String gemName) {
        removeExpiredSnapshots();
        var key = new InfoKey(origin, gemName);
        var generation = nextObservationGeneration.incrementAndGet();
        activeObservations.put(key, generation);
        return new Observation(key, generation, snapshots.get(key));
    }

    boolean publish(Observation observation, String etag, byte[] body) {
        var gemName = observation.key().gemName();
        var parsed = GEM_NAME.matcher(gemName).matches() ? parseInfo(gemName, body) : null;
        synchronized (this) {
            if (!completeCurrent(observation)) return false;
            if (parsed == null) {
                if (snapshots.remove(observation.key()) != null) rebuildFilenameIndex();
                return false;
            }
            snapshots.put(observation.key(),
                    new InfoSnapshot(etag, parsed, monotonicNanos.getAsLong()));
            rebuildFilenameIndex();
            return true;
        }
    }

    synchronized void observeNonFullResponse(Observation observation,
                                              int statusCode, String etag) {
        if (!completeCurrent(observation)) return;
        if (statusCode == 304) {
            var previous = observation.previous();
            if (previous != null && etag != null && etag.equals(previous.etag())) {
                snapshots.put(observation.key(), new InfoSnapshot(
                        previous.etag(), previous.digests(), monotonicNanos.getAsLong()));
                rebuildFilenameIndex();
                return;
            }
        }
        if (snapshots.remove(observation.key()) != null) rebuildFilenameIndex();
    }

    synchronized void invalidate(Observation observation) {
        if (completeCurrent(observation)
                && snapshots.remove(observation.key()) != null) {
            rebuildFilenameIndex();
        }
    }

    synchronized String digestFor(String filename) {
        removeExpiredSnapshots();
        return digestsByFilename.get(filename);
    }

    synchronized boolean isAuthorized(String filename, String digest) {
        removeExpiredSnapshots();
        return digest.equals(digestsByFilename.get(filename));
    }

    synchronized int activeObservationCount() {
        return activeObservations.size();
    }

    private boolean completeCurrent(Observation observation) {
        return activeObservations.remove(observation.key(), observation.generation());
    }

    private void removeExpiredSnapshots() {
        var cutoff = monotonicNanos.getAsLong() - INFO_AUTHORITY_TTL_NANOS;
        if (snapshots.entrySet().removeIf(
                entry -> entry.getValue().observedAtNanos() < cutoff)) {
            rebuildFilenameIndex();
        }
    }

    private void rebuildFilenameIndex() {
        while (snapshots.size() > MAX_INFO_SNAPSHOTS
                || snapshots.values().stream().mapToInt(s -> s.digests().size()).sum()
                        > MAX_INFO_ENTRIES) {
            var oldest = snapshots.entrySet().stream()
                    .min(java.util.Comparator.comparingLong(
                            entry -> entry.getValue().observedAtNanos()))
                    .orElseThrow();
            snapshots.remove(oldest.getKey());
        }

        var rebuilt = new LinkedHashMap<String, String>();
        var conflicts = new java.util.HashSet<String>();
        for (var snapshot : snapshots.values()) {
            for (var entry : snapshot.digests().entrySet()) {
                var previous = rebuilt.putIfAbsent(entry.getKey(), entry.getValue());
                if (previous != null && !previous.equals(entry.getValue())) {
                    conflicts.add(entry.getKey());
                }
            }
        }
        conflicts.forEach(rebuilt::remove);
        digestsByFilename = Map.copyOf(rebuilt);
    }

    static Map<String, String> parseInfo(String gemName, byte[] body) {
        if (!GEM_NAME.matcher(gemName).matches()) return null;

        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }

        var result = new LinkedHashMap<String, String>();
        boolean metadataEnded = false;
        for (var rawLine : text.split("\\n", -1)) {
            var line = rawLine.endsWith("\r")
                    ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            if (!metadataEnded) {
                if ("---".equals(line)) metadataEnded = true;
                continue;
            }
            if (line.isEmpty()) continue;

            var firstSpace = line.indexOf(' ');
            var pipe = line.indexOf('|');
            if (firstSpace <= 0 || pipe <= firstSpace || pipe != line.lastIndexOf('|')) {
                return null;
            }

            var versionPlatform = line.substring(0, firstSpace);
            if (!isSafeVersionPlatform(versionPlatform)) return null;

            String checksum = null;
            for (var requirement : line.substring(pipe + 1).split(",", -1)) {
                if (!requirement.startsWith("checksum:")) continue;
                var candidate = requirement.substring("checksum:".length());
                if (checksum != null || !SHA256.matcher(candidate).matches()) return null;
                checksum = candidate;
            }
            if (checksum == null) return null;

            var filename = gemName + "-" + versionPlatform + ".gem";
            var previous = result.putIfAbsent(filename, checksum);
            if (result.size() > MAX_INFO_ENTRIES_PER_GEM) return null;
            if (previous != null && !previous.equals(checksum)) return null;
        }
        return metadataEnded && !result.isEmpty() ? Map.copyOf(result) : null;
    }

    private static boolean isSafeVersionPlatform(String value) {
        return VERSION_PLATFORM.matcher(value).matches();
    }

    static Path objectPath(Path cacheDir, String digest) {
        if (!SHA256.matcher(digest).matches()) {
            throw new IllegalArgumentException("Invalid RubyGems SHA-256 digest");
        }
        return cacheDir.resolve("objects/sha256")
                .resolve(digest.substring(0, 2))
                .resolve(digest + ".gem");
    }

    CacheEntry verifyExisting(Path cacheDir, String digest) throws Exception {
        var object = objectPath(cacheDir, digest);
        if (!Files.isRegularFile(object, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return null;

        var size = Files.size(object);
        if (size > MAX_GEM_BYTES || (!verifiedObjects.contains(digest)
                && !verifySha256(object, digest))) {
            Files.deleteIfExists(object);
            verifiedObjects.remove(digest);
            return null;
        }
        markVerified(digest);
        return new CacheEntry(object, size);
    }

    void markVerified(String digest) {
        if (verifiedObjects.size() >= MAX_VERIFIED_OBJECTS) verifiedObjects.clear();
        verifiedObjects.add(digest);
    }

    static boolean verifySha256(Path file, String expected) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            var buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return java.util.HexFormat.of().formatHex(digest.digest()).equals(expected);
    }

    static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}
