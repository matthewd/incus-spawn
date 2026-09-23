package dev.incusspawn.vm;

import dev.incusspawn.config.WorkerPoolConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Resolved host exports for one named worker-pool VM.
 *
 * <p>The planner has no process-global inputs: callers provide the frozen pool configuration and
 * host home directory. Runtime and workspace roots are the only roots it may create. Reference
 * roots must already be directories. Every path exposed to vfkit is canonical, and translation
 * canonicalizes the requested path before selecting the longest containing export.
 */
public final class VmHostExports {

    public static final String RUNTIME_TAG = "isx-runtime";
    public static final String WORKSPACE_TAG = "isx-workspace";
    public static final String REFERENCE_TAG_PREFIX = "isx-reference-";
    public static final Path RUNTIME_GUEST_PATH = Path.of("/host/runtime");
    public static final Path WORKSPACE_GUEST_PATH = Path.of("/host/workspace");
    public static final Path REFERENCES_GUEST_PATH = Path.of("/host/references");
    private static final int MAX_REFERENCE_PARAMETER_LENGTH = 1024;

    /** One canonical host export and its deterministic guest identity. */
    public record Export(
            String name,
            Path hostPath,
            String mountTag,
            Path guestPath,
            WorkerPoolConfig.AccessMode accessMode) {

        public boolean readOnly() {
            return accessMode == WorkerPoolConfig.AccessMode.READ_ONLY;
        }
    }

    /** Appliance path plus both the requested and enclosing-export access modes. */
    public record Translation(
            String appliancePath,
            WorkerPoolConfig.AccessMode requestedAccess,
            WorkerPoolConfig.AccessMode exportAccess) {
    }

    private final String poolName;
    private final List<Export> exports;
    private final String fingerprint;

    private VmHostExports(String poolName, List<Export> exports) {
        this.poolName = poolName;
        this.exports = List.copyOf(exports);
        this.fingerprint = fingerprint(exports);
    }

    /** Resolve, validate, and prepare the export roots for a named pool. */
    public static VmHostExports create(
            String poolName, WorkerPoolConfig.Selected pool, Path home) {
        if (!WorkerPoolConfig.isSafeName(poolName)) {
            throw new IllegalStateException("unsafe worker pool name '" + poolName + "'");
        }

        var specs = new ArrayList<ExportSpec>();
        specs.add(new ExportSpec(
                "runtime-root", pool.runtimeRoot().path(), RUNTIME_TAG, RUNTIME_GUEST_PATH,
                pool.runtimeRoot().accessMode(), true));
        specs.add(new ExportSpec(
                "workspace-root", pool.workspaceRoot().path(), WORKSPACE_TAG, WORKSPACE_GUEST_PATH,
                pool.workspaceRoot().accessMode(), true));
        pool.referenceRoots().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> {
                    if (!WorkerPoolConfig.isSafeReferenceName(entry.getKey())) {
                        throw new IllegalStateException("unsafe reference export name '" + entry.getKey()
                                + "' in worker pool '" + poolName + "'");
                    }
                    specs.add(new ExportSpec(
                            entry.getKey(), entry.getValue().path(), REFERENCE_TAG_PREFIX + entry.getKey(),
                            REFERENCES_GUEST_PATH.resolve(entry.getKey()), entry.getValue().accessMode(), false));
                });
        var referenceParameter = specs.stream().skip(2).map(ExportSpec::name)
                .collect(java.util.stream.Collectors.joining(","));
        if (referenceParameter.length() > MAX_REFERENCE_PARAMETER_LENGTH) {
            throw new IllegalStateException("worker pool '" + poolName
                    + "' reference export names exceed the safe kernel-parameter length");
        }

        var resolved = specs.stream().map(spec -> resolve(poolName, spec, home)).toList();
        rejectOverlaps(poolName, resolved);

        // Do not create anything until every declaration, including every non-controlled
        // reference root, has passed its initial validation.
        for (var root : resolved) {
            if (!root.spec.controlled) continue;
            try {
                Files.createDirectories(root.configuredPath);
            } catch (IOException e) {
                throw invalid(poolName, root.spec.name,
                        "could not create directory " + root.configuredPath + ": " + e.getMessage());
            }
        }

        // Re-resolve after creation so the command and fingerprint only contain real paths. This
        // also closes a symlink/ancestor change between initial validation and directory creation.
        var exports = new ArrayList<Export>();
        for (var root : resolved) {
            Path canonical;
            try {
                canonical = root.configuredPath.toRealPath();
            } catch (IOException e) {
                throw invalid(poolName, root.spec.name,
                        "could not canonicalize directory " + root.configuredPath + ": " + e.getMessage());
            }
            if (!Files.isDirectory(canonical)) {
                throw invalid(poolName, root.spec.name, "is not a directory: " + canonical);
            }
            requireVfkitSafePath(canonical.toString());
            exports.add(new Export(root.spec.name, canonical, root.spec.mountTag,
                    root.spec.guestPath, root.spec.accessMode));
        }
        rejectExportOverlaps(poolName, exports);
        return new VmHostExports(poolName, exports);
    }

    public String poolName() {
        return poolName;
    }

    public List<Export> exports() {
        return exports;
    }

    public Export runtime() {
        return exports.get(0);
    }

    public Export workspace() {
        return exports.get(1);
    }

    public List<String> referenceNames() {
        return exports.stream().skip(2).map(Export::name).toList();
    }

    public String fingerprint() {
        return fingerprint;
    }

    /**
     * Translate a host path through the longest canonical export containing it.
     * Existing symlinks are resolved before containment is checked, so a child symlink escaping an
     * export is rejected rather than translated to a path the guest mount does not provide.
     */
    public String translate(String hostPath, Path home) {
        return translate(hostPath, home, WorkerPoolConfig.AccessMode.READ_ONLY, false)
                .appliancePath();
    }

    /**
     * Translate an existing physical host path while proving that its enclosing export permits the
     * requested access. Read-only requests may use any export. Read-write requests are restricted
     * to the workspace export even if a future configuration type marks another export writable.
     */
    public Translation translate(
            String hostPath, Path home, WorkerPoolConfig.AccessMode requestedAccess) {
        return translate(hostPath, home, requestedAccess, true);
    }

    private Translation translate(
            String hostPath, Path home, WorkerPoolConfig.AccessMode requestedAccess,
            boolean requireExistingLeaf) {
        if (requestedAccess == null) throw new IllegalArgumentException("requested access is required");
        requireVfkitSafePath(hostPath);
        var requested = expand(hostPath, home, "host path");
        final Path canonical;
        if (requireExistingLeaf) {
            try {
                canonical = requested.toRealPath();
            } catch (IOException e) {
                throw invalid(poolName, "host path",
                        "must identify an existing physical path: " + requested);
            }
        } else {
            canonical = canonicalizeExistingAncestor(requested, poolName, "host path");
        }
        var export = exports.stream()
                .filter(candidate -> canonical.startsWith(candidate.hostPath()))
                .max(Comparator.comparingInt(candidate -> candidate.hostPath().getNameCount()))
                .orElseThrow(() -> new IllegalStateException("host path '" + hostPath
                        + "' is not declared by worker pool '" + poolName + "' exports"));
        if (requestedAccess == WorkerPoolConfig.AccessMode.READ_WRITE
                && (export != workspace()
                || export.accessMode() != WorkerPoolConfig.AccessMode.READ_WRITE)) {
            throw new IllegalStateException("host path '" + hostPath + "' belongs to read-only export '"
                    + export.name() + "' in worker pool '" + poolName + "'");
        }
        if (requireExistingLeaf && canonical.equals(export.hostPath())
                && !requested.equals(export.hostPath())) {
            throw new IllegalStateException("host path '" + hostPath + "' resolves to the root of export '"
                    + export.name() + "' in worker pool '" + poolName + "'");
        }
        var appliancePath = export.guestPath()
                .resolve(export.hostPath().relativize(canonical)).normalize().toString();
        return new Translation(appliancePath, requestedAccess, export.accessMode());
    }

    /** Create VM-only staging under the controlled, read-only-to-the-guest runtime export. */
    public Path createRuntimeStagingDirectory(String prefix) throws IOException {
        return Files.createTempDirectory(runtime().hostPath(), prefix);
    }

    /** Atomically record this plan after its VM process has started successfully. */
    public void persistFingerprint(Path stateFile) throws IOException {
        Files.createDirectories(stateFile.getParent());
        var temporary = Files.createTempFile(stateFile.getParent(), stateFile.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, fingerprint + "\n", StandardCharsets.US_ASCII);
            Files.move(temporary, stateFile,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Fail if a running VM was launched with a different (or unrecorded) export plan. */
    public void requirePersistedFingerprint(Path stateFile) {
        String persisted;
        try {
            persisted = Files.readString(stateFile, StandardCharsets.US_ASCII).strip();
        } catch (IOException e) {
            throw changedPlan(stateFile);
        }
        if (!fingerprint.equals(persisted)) throw changedPlan(stateFile);
    }

    /**
     * vfkit encodes device fields as a comma-separated string and has no escaping mechanism.
     */
    public static void requireVfkitSafePath(String path) {
        if (path.indexOf(',') >= 0 || path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0
                || path.indexOf('\0') >= 0) {
            throw new IllegalStateException("host path cannot be represented safely in vfkit's "
                    + "comma-separated device syntax: " + printable(path));
        }
    }

    private IllegalStateException changedPlan(Path stateFile) {
        return new IllegalStateException("worker pool '" + poolName + "' export configuration differs "
                + "from the running VM (state: " + stateFile + "). Run 'isx vm restart' with "
                + "ISX_POOL=" + poolName + " to apply the current exports.");
    }

    private static ResolvedSpec resolve(String poolName, ExportSpec spec, Path home) {
        requireVfkitSafePath(spec.configuredPath);
        var configured = expand(spec.configuredPath, home, spec.name);
        var canonical = canonicalizeExistingAncestor(configured, poolName, spec.name);
        if (Files.exists(configured) && !Files.isDirectory(configured)) {
            throw invalid(poolName, spec.name, "is not a directory: " + configured);
        }
        if (!spec.controlled && (!Files.exists(configured) || !Files.isDirectory(configured))) {
            throw invalid(poolName, "reference-roots." + spec.name,
                    "must already exist as a directory: " + configured);
        }
        return new ResolvedSpec(spec, configured, canonical);
    }

    private static Path expand(String value, Path home, String field) {
        Path path;
        try {
            if ("~".equals(value)) {
                path = home;
            } else if (value.startsWith("~/")) {
                path = home.resolve(value.substring(2));
            } else {
                path = Path.of(value);
            }
        } catch (InvalidPathException e) {
            throw new IllegalStateException(field + " is not a valid host path");
        }
        if (!path.isAbsolute()) {
            throw new IllegalStateException(field + " must be an absolute path or start with '~/': " + value);
        }
        return path.normalize();
    }

    /** Canonicalize the longest existing ancestor and append any not-yet-existing suffix. */
    private static Path canonicalizeExistingAncestor(Path path, String poolName, String field) {
        var suffix = new ArrayList<Path>();
        var existing = path;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            suffix.add(existing.getFileName());
            existing = existing.getParent();
        }
        if (existing == null) {
            throw invalid(poolName, field, "has no existing ancestor: " + path);
        }
        try {
            var canonical = existing.toRealPath();
            for (int i = suffix.size() - 1; i >= 0; i--) {
                canonical = canonical.resolve(suffix.get(i));
            }
            return canonical.normalize();
        } catch (IOException e) {
            throw invalid(poolName, field,
                    "could not canonicalize existing ancestor of " + path + ": " + e.getMessage());
        }
    }

    private static void rejectOverlaps(String poolName, List<ResolvedSpec> roots) {
        for (int i = 0; i < roots.size(); i++) {
            for (int j = i + 1; j < roots.size(); j++) {
                rejectOverlap(poolName, roots.get(i).spec.name, roots.get(i).canonicalPath,
                        roots.get(j).spec.name, roots.get(j).canonicalPath);
            }
        }
    }

    private static void rejectExportOverlaps(String poolName, List<Export> roots) {
        for (int i = 0; i < roots.size(); i++) {
            for (int j = i + 1; j < roots.size(); j++) {
                rejectOverlap(poolName, roots.get(i).name, roots.get(i).hostPath,
                        roots.get(j).name, roots.get(j).hostPath);
            }
        }
    }

    private static void rejectOverlap(
            String poolName, String leftName, Path left, String rightName, Path right) {
        if (left.startsWith(right) || right.startsWith(left)) {
            throw new IllegalStateException("worker pool '" + poolName + "' export roots '"
                    + leftName + "' and '" + rightName
                    + "' duplicate or overlap after canonicalization (" + left + " and " + right + ")");
        }
    }

    private static String fingerprint(List<Export> exports) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update("isx-vm-host-exports-v1\n".getBytes(StandardCharsets.UTF_8));
            for (var export : exports) {
                update(digest, export.name);
                update(digest, export.hostPath.toString());
                update(digest, export.mountTag);
                update(digest, export.guestPath.toString());
                update(digest, export.accessMode.name());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static String printable(String value) {
        return value.replace("\0", "\\0").replace("\r", "\\r").replace("\n", "\\n");
    }

    private static IllegalStateException invalid(String poolName, String field, String reason) {
        return new IllegalStateException("worker pool '" + poolName + "' field '" + field + "' " + reason);
    }

    private record ExportSpec(
            String name,
            String configuredPath,
            String mountTag,
            Path guestPath,
            WorkerPoolConfig.AccessMode accessMode,
            boolean controlled) {}

    private record ResolvedSpec(ExportSpec spec, Path configuredPath, Path canonicalPath) {}
}
