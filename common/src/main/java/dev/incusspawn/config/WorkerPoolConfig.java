package dev.incusspawn.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Strict configuration for one named worker pool.
 *
 * <p>Export roots are typed by access mode so runtime/reference data cannot accidentally be
 * attached read-write while the workspace remains the single read-write export.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = false)
public final class WorkerPoolConfig {

    private static final Pattern SAFE_NAME = Pattern.compile("[a-z][a-z0-9-]{0,62}");
    // VZVirtioFileSystemDeviceConfiguration limits tags to 36 bytes;
    // "isx-reference-" leaves 22 ASCII bytes for the configured name.
    private static final Pattern SAFE_REFERENCE_NAME = Pattern.compile("[a-z][a-z0-9-]{0,21}");
    private static final Pattern SIZE = Pattern.compile("[0-9]+[GMTgmt]?");

    private Integer cpus;
    private Integer memoryMib;
    private String swap;
    private ReadOnlyExport runtimeRoot;
    private ReadWriteExport workspaceRoot;
    private Map<String, ReadOnlyExport> referenceRoots;

    public Integer getCpus() { return cpus; }
    public void setCpus(Integer cpus) { this.cpus = cpus; }

    @JsonProperty("memory-mib")
    public Integer getMemoryMib() { return memoryMib; }
    @JsonProperty("memory-mib")
    public void setMemoryMib(Integer memoryMib) { this.memoryMib = memoryMib; }

    public String getSwap() { return swap; }
    public void setSwap(String swap) { this.swap = swap; }

    @JsonProperty("runtime-root")
    public ReadOnlyExport getRuntimeRoot() { return runtimeRoot; }
    @JsonProperty("runtime-root")
    public void setRuntimeRoot(ReadOnlyExport runtimeRoot) { this.runtimeRoot = runtimeRoot; }

    @JsonProperty("workspace-root")
    public ReadWriteExport getWorkspaceRoot() { return workspaceRoot; }
    @JsonProperty("workspace-root")
    public void setWorkspaceRoot(ReadWriteExport workspaceRoot) { this.workspaceRoot = workspaceRoot; }

    @JsonProperty("reference-roots")
    public Map<String, ReadOnlyExport> getReferenceRoots() { return referenceRoots; }
    @JsonProperty("reference-roots")
    public void setReferenceRoots(Map<String, ReadOnlyExport> referenceRoots) {
        this.referenceRoots = referenceRoots;
    }

    public enum AccessMode { READ_ONLY, READ_WRITE }

    public sealed interface HostExport permits ReadOnlyExport, ReadWriteExport {
        String path();
        AccessMode accessMode();
    }

    /** A host export whose consumer must only attach it read-only. */
    @RegisterForReflection
    public record ReadOnlyExport(String path) implements HostExport {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public ReadOnlyExport {
            // Preserve the exact value. VmHostExports must reject delimiters (including newlines)
            // that vfkit's comma-separated device syntax cannot encode.
        }

        @Override
        public AccessMode accessMode() { return AccessMode.READ_ONLY; }

        @JsonValue
        public String yamlValue() { return path; }
    }

    /** A host export whose consumer may attach it read-write. */
    @RegisterForReflection
    public record ReadWriteExport(String path) implements HostExport {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public ReadWriteExport {
            // Preserve the exact value; see ReadOnlyExport.
        }

        @Override
        public AccessMode accessMode() { return AccessMode.READ_WRITE; }

        @JsonValue
        public String yamlValue() { return path; }
    }

    /** Immutable copy retained by the process-wide selection. */
    public record Selected(
            int cpus,
            int memoryMib,
            String swap,
            ReadOnlyExport runtimeRoot,
            ReadWriteExport workspaceRoot,
            Map<String, ReadOnlyExport> referenceRoots) {
        public Selected {
            referenceRoots = Map.copyOf(referenceRoots);
        }
    }

    public Selected validateAndFreeze(String poolName, Path home) {
        if (!isSafeName(poolName)) {
            throw new IllegalStateException("unsafe worker pool name '" + poolName
                    + "' (use lowercase letters, digits, and hyphens; start with a letter)");
        }
        if (cpus == null) {
            throw missing(poolName, "cpus");
        }
        if (cpus < 1) {
            throw invalid(poolName, "cpus", "must be at least 1");
        }
        if (memoryMib == null) {
            throw missing(poolName, "memory-mib");
        }
        if (memoryMib < 2048) {
            throw invalid(poolName, "memory-mib", "must be at least 2048");
        }
        if (swap == null || swap.isBlank()) {
            throw missing(poolName, "swap");
        }
        swap = swap.strip();
        validateSize(poolName, swap);
        if (runtimeRoot == null) {
            throw missing(poolName, "runtime-root");
        }
        if (workspaceRoot == null) {
            throw missing(poolName, "workspace-root");
        }
        if (referenceRoots == null) {
            throw missing(poolName, "reference-roots");
        }

        var roots = new ArrayList<NamedRoot>();
        roots.add(validateRoot(poolName, "runtime-root", runtimeRoot.path(), home));
        roots.add(validateRoot(poolName, "workspace-root", workspaceRoot.path(), home));

        var references = new LinkedHashMap<String, ReadOnlyExport>();
        for (var entry : referenceRoots.entrySet()) {
            var name = entry.getKey();
            if (!isSafeReferenceName(name)) {
                throw new IllegalStateException("unsafe reference export name '" + name
                        + "' in worker pool '" + poolName
                        + "' (use at most 22 lowercase letters, digits, and hyphens; start with a letter)");
            }
            if (entry.getValue() == null) {
                throw invalid(poolName, "reference-roots." + name, "path is required");
            }
            roots.add(validateRoot(poolName, "reference-roots." + name,
                    entry.getValue().path(), home));
            references.put(name, entry.getValue());
        }
        rejectOverlaps(poolName, roots);

        return new Selected(cpus, memoryMib, swap, runtimeRoot, workspaceRoot, references);
    }

    public static boolean isSafeName(String name) {
        return name != null && SAFE_NAME.matcher(name).matches();
    }

    public static boolean isSafeReferenceName(String name) {
        return name != null && SAFE_REFERENCE_NAME.matcher(name).matches();
    }

    private static void validateSize(String poolName, String size) {
        if (!SIZE.matcher(size).matches()) {
            throw invalid(poolName, "swap",
                    "must be a number with an optional G, M, or T suffix (for example 12G)");
        }
        var number = size.substring(0, Character.isLetter(size.charAt(size.length() - 1))
                ? size.length() - 1 : size.length());
        try {
            long amount = Long.parseLong(number);
            if (amount < 1) throw invalid(poolName, "swap", "must be greater than zero");
            long multiplier = switch (Character.toUpperCase(size.charAt(size.length() - 1))) {
                case 'M' -> 1024L * 1024;
                case 'G' -> 1024L * 1024 * 1024;
                case 'T' -> 1024L * 1024 * 1024 * 1024;
                default -> 1L;
            };
            Math.multiplyExact(amount, multiplier);
        } catch (NumberFormatException | ArithmeticException e) {
            throw invalid(poolName, "swap", "is too large");
        }
    }

    private static NamedRoot validateRoot(String poolName, String exportName, String value, Path home) {
        if (value == null || value.isBlank()) {
            throw invalid(poolName, exportName, "path is required");
        }
        var path = value.strip();
        Path expanded;
        try {
            if (path.equals("~")) {
                expanded = home;
            } else if (path.startsWith("~/")) {
                expanded = home.resolve(path.substring(2));
            } else {
                expanded = Path.of(path);
                if (!expanded.isAbsolute()) {
                    throw invalid(poolName, exportName,
                            "must be an absolute path or start with '~/'.");
                }
            }
        } catch (InvalidPathException e) {
            throw invalid(poolName, exportName, "is not a valid host path");
        }
        return new NamedRoot(exportName, normalizeExistingPrefix(expanded.normalize()));
    }

    /**
     * Resolve symlinks for the longest existing prefix. This detects overlap through an existing
     * symlink while still accepting roots whose final components will be created later.
     */
    private static Path normalizeExistingPrefix(Path path) {
        var missing = new ArrayList<Path>();
        var existing = path;
        while (existing != null && !Files.exists(existing)) {
            missing.add(existing.getFileName());
            existing = existing.getParent();
        }
        if (existing == null) return path;
        try {
            var resolved = existing.toRealPath();
            for (int i = missing.size() - 1; i >= 0; i--) {
                resolved = resolved.resolve(missing.get(i));
            }
            return resolved.normalize();
        } catch (IOException e) {
            return path;
        }
    }

    private static void rejectOverlaps(String poolName, List<NamedRoot> roots) {
        for (int i = 0; i < roots.size(); i++) {
            for (int j = i + 1; j < roots.size(); j++) {
                var left = roots.get(i);
                var right = roots.get(j);
                if (left.path.startsWith(right.path) || right.path.startsWith(left.path)) {
                    throw new IllegalStateException("worker pool '" + poolName + "' export roots '"
                            + left.name + "' and '" + right.name
                            + "' duplicate or overlap after normalization (" + left.path
                            + " and " + right.path + ")");
                }
            }
        }
    }

    private static IllegalStateException missing(String poolName, String field) {
        return invalid(poolName, field, "is required");
    }

    private static IllegalStateException invalid(String poolName, String field, String reason) {
        return new IllegalStateException("worker pool '" + poolName + "' field '" + field + "' " + reason);
    }

    private record NamedRoot(String name, Path path) {}
}
