package dev.incusspawn.pool;

import dev.incusspawn.Environment;
import dev.incusspawn.config.WorkerPoolConfig;
import dev.incusspawn.config.WorkerPoolSelection;
import dev.incusspawn.util.BoundedFileLock;
import dev.incusspawn.vm.VmHostExports;
import dev.incusspawn.vm.VmManager;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Filesystem-backed sealing and APFS-clone materialization for direct worker pools. */
public final class PoolStore {

    public interface Operations {
        void cloneApfs(Path source, Path destination) throws IOException;
        void createSparse(Path destination, long size) throws IOException;
        boolean vmRunning(Path vmStateDirectory);
    }

    public record InspectResult(
            String seedPool, boolean running, boolean sealed,
            String generation, String identity) {}

    public record SealResult(
            boolean changed, String seedPool, String generation, String identity) {}

    public record MaterializeResult(
            boolean changed, String name, String identity, String seedPool,
            String generation, String generationIdentity) {}

    public static final class PoolException extends RuntimeException {
        private final String code;

        public PoolException(String code, String message) {
            super(message);
            this.code = code;
        }

        public PoolException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private final Path stateDirectory;
    private final Path home;
    private final Path applianceKernel;
    private final Path applianceVersionFile;
    private final String requiredApplianceVersion;
    private final WorkerPoolSelection selection;
    private final Operations operations;
    private final SecureRandom random;

    public PoolStore(
            Path stateDirectory, Path home, Path applianceKernel, Path applianceVersionFile,
            String requiredApplianceVersion, WorkerPoolSelection selection,
            Operations operations, SecureRandom random) {
        this.stateDirectory = stateDirectory;
        this.home = home;
        this.applianceKernel = applianceKernel;
        this.applianceVersionFile = applianceVersionFile;
        this.requiredApplianceVersion = requiredApplianceVersion;
        this.selection = selection;
        this.operations = operations;
        this.random = random;
    }

    public static PoolStore system() {
        return new PoolStore(Environment.stateDir(), Environment.home(),
                Environment.applianceKernel(), Environment.applianceDir().resolve("version"),
                VmManager.applianceVersion(), WorkerPoolSelection.current(),
                new SystemOperations(), new SecureRandom());
    }

    /** Inspect whether the selected static named pool is stopped and has a valid latest seal. */
    public InspectResult inspect() {
        var seedName = requireStaticSeed();
        try (var registry = acquireManagementLock(); var vm = acquireVmLock()) {
            var running = operations.vmRunning(selection.vmStateDir(stateDirectory));
            var latestFile = PoolState.seedRoot(stateDirectory, seedName).resolve("latest.json");
            if (!Files.exists(latestFile, LinkOption.NOFOLLOW_LINKS)) {
                return new InspectResult(seedName, running, false, null, null);
            }
            var latest = PoolState.loadLatest(stateDirectory, home, seedName);
            var seed = PoolState.loadSeed(
                    stateDirectory, home, seedName, latest.generation(), latest.identity());
            var seedDirectory = PoolState.seedRoot(stateDirectory, seedName)
                    .resolve("generations").resolve(latest.generation());
            verifyDiskImage(seedDirectory.resolve(PoolState.ROOT_DISK_FILE),
                    seed.rootDisk(), "sealed root disk");
            verifyDiskImage(seedDirectory.resolve(PoolState.DATA_DISK_FILE),
                    seed.dataDisk(), "sealed data disk");
            verifyKernel(seedDirectory.resolve(PoolState.KERNEL_FILE),
                    seed.kernel(), "sealed kernel");
            return new InspectResult(
                    seedName, running, true, latest.generation(), latest.identity());
        } catch (PoolException e) {
            throw e;
        } catch (Exception e) {
            throw new PoolException("inspect_failed", "could not inspect seed worker pool '"
                    + seedName + "': " + e.getMessage(), e);
        }
    }

    /** Seal the stopped VM disks of the currently selected static named pool. */
    public SealResult seal() {
        var seedName = requireStaticSeed();
        try (var registry = acquireManagementLock()) {
            ensureRegistry();
            try (var vm = acquireVmLock()) {
                var sourceState = selection.vmStateDir(stateDirectory);
                if (operations.vmRunning(sourceState)) {
                    throw new PoolException("seed_running",
                            "seed worker pool '" + seedName + "' must be stopped before sealing");
                }
                var sourceRoot = requirePhysicalFile(sourceState.resolve(PoolState.ROOT_DISK_FILE),
                        "seed root disk");
                var sourceData = requirePhysicalFile(sourceState.resolve(PoolState.DATA_DISK_FILE),
                        "seed Incus data disk");
                var sourceKernel = requirePhysicalFile(applianceKernel, "seed appliance kernel");
                var sourceVersion = requirePhysicalFile(sourceState.resolve(PoolState.DISK_VERSION_FILE),
                        "seed disk version");
                var sourceKernelVersion = requirePhysicalFile(
                        applianceVersionFile, "seed appliance kernel version");
                var diskVersion = readDiskVersion(sourceVersion);
                var kernelVersion = readDiskVersion(sourceKernelVersion);
                if (!diskVersion.equals(requiredApplianceVersion)
                        || !kernelVersion.equals(requiredApplianceVersion)) {
                    throw new PoolException("seed_outdated", "seed worker pool '" + seedName
                            + "' and its appliance kernel must both be version "
                            + requiredApplianceVersion
                            + "; start and stop the seed with the current isx before sealing");
                }
                var rootMetadata = inspectSeedDisk(sourceRoot, "seed root disk");
                var dataMetadata = inspectSeedDisk(sourceData, "seed Incus data disk");
                var kernelDigest = digestSeedKernel(sourceKernel);
                var frozen = freezeSelectedConfig(seedName);

                var seedRoot = PoolState.seedRoot(stateDirectory, seedName);
                ensureProtectedPath(PoolState.root(stateDirectory).resolve(PoolState.SEED_DIRECTORY));
                ensureProtectedPath(seedRoot);
                var generations = seedRoot.resolve("generations");
                ensureProtectedPath(generations);
                cleanStaging(generations, ".staging-");

                String generation;
                Path published;
                do {
                    generation = randomGeneration();
                    published = generations.resolve(generation);
                } while (Files.exists(published, LinkOption.NOFOLLOW_LINKS));
                var staging = generations.resolve(".staging-" + generation);
                PoolState.createProtectedDirectory(staging);
                var generationMoved = false;
                try {
                    var rootClone = staging.resolve(PoolState.ROOT_DISK_FILE);
                    var dataClone = staging.resolve(PoolState.DATA_DISK_FILE);
                    var kernelClone = staging.resolve(PoolState.KERNEL_FILE);
                    operations.cloneApfs(sourceRoot, rootClone);
                    operations.cloneApfs(sourceData, dataClone);
                    operations.cloneApfs(sourceKernel, kernelClone);
                    verifyDiskImage(rootClone, rootMetadata, "cloned root disk");
                    verifyDiskImage(dataClone, dataMetadata, "cloned data disk");
                    verifyKernel(kernelClone, kernelDigest, "cloned kernel");
                    PoolState.setReadOnlyFile(rootClone);
                    PoolState.setReadOnlyFile(dataClone);
                    PoolState.setReadOnlyFile(kernelClone);
                    var versionClone = staging.resolve(PoolState.DISK_VERSION_FILE);
                    Files.copy(sourceVersion, versionClone);
                    PoolState.setReadOnlyFile(versionClone);

                    var manifest = PoolState.SeedManifest.create(seedName, generation, frozen,
                            rootMetadata, dataMetadata, kernelDigest, diskVersion);
                    var manifestFile = staging.resolve("manifest.json");
                    PoolState.writeProtectedFile(manifestFile, PoolState.jsonBytes(manifest),
                            PoolState.readOnlyFilePermissions());
                    moveDirectory(staging, published);
                    generationMoved = true;

                    var latest = new PoolState.LatestGeneration(
                            PoolState.VERSION, generation, manifest.identity());
                    writeJsonAtomically(seedRoot.resolve("latest.json"), latest);
                    return new SealResult(true, seedName, generation, manifest.identity());
                } catch (Exception failure) {
                    if (!generationMoved) cleanupAfterFailure(staging, failure);
                    if (failure instanceof PoolException known) throw known;
                    throw new PoolException("seal_failed",
                            "could not seal worker pool '" + seedName + "': "
                                    + failure.getMessage(), failure);
                }
            }
        } catch (PoolException e) {
            throw e;
        } catch (Exception e) {
            throw new PoolException("seal_failed", "could not seal worker pool '"
                    + seedName + "': " + e.getMessage(), e);
        }
    }

    /**
     * Materialize a new fixed pool descriptor and cloned disks. Supplying neither generation
     * argument selects the current latest generation. Supplying either requires both.
     */
    public MaterializeResult materialize(
            String name, String directRoot, String generation, String generationIdentity) {
        var seedName = requireStaticSeed();
        if (!WorkerPoolConfig.isSafeName(name)) {
            throw new PoolException("invalid_request", "materialized pool name is unsafe");
        }
        if ((generation == null) != (generationIdentity == null)) {
            throw new PoolException("invalid_request",
                    "generation and generation identity must be supplied together");
        }
        if (generation != null) {
            if (!PoolState.isSafeGeneration(generation)) {
                throw new PoolException("invalid_request", "sealed generation is unsafe");
            }
            try {
                PoolState.requireIdentity(generationIdentity, "sealed generation identity");
            } catch (IllegalStateException e) {
                throw new PoolException("invalid_request", e.getMessage());
            }
        }
        var canonicalDirect = canonicalDirectRoot(name, seedName, directRoot);

        try (var registry = acquireManagementLock()) {
            ensureRegistry();
            var poolsRoot = PoolState.materializedRoot(stateDirectory);
            var target = PoolState.materializedPool(stateDirectory, name);
            cleanStaging(poolsRoot, ".staging-" + name + "-");
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                var existing = PoolState.loadMaterializedUncheckedIdentity(
                        stateDirectory, home, name);
                requireExactRetry(existing, seedName, canonicalDirect, generation,
                        generationIdentity);
                return result(false, existing);
            }

            String selectedGeneration = generation;
            String selectedIdentity = generationIdentity;
            if (selectedGeneration == null) {
                var latest = PoolState.loadLatest(stateDirectory, home, seedName);
                selectedGeneration = latest.generation();
                selectedIdentity = latest.identity();
            }
            var seed = PoolState.loadSeed(stateDirectory, home, seedName,
                    selectedGeneration, selectedIdentity);
            var seedDirectory = PoolState.seedRoot(stateDirectory, seedName)
                    .resolve("generations").resolve(selectedGeneration);
            verifyDiskImage(seedDirectory.resolve(PoolState.ROOT_DISK_FILE),
                    seed.rootDisk(), "sealed root disk");
            verifyDiskImage(seedDirectory.resolve(PoolState.DATA_DISK_FILE),
                    seed.dataDisk(), "sealed data disk");
            verifyKernel(seedDirectory.resolve(PoolState.KERNEL_FILE),
                    seed.kernel(), "sealed kernel");

            var staging = poolsRoot.resolve(".staging-" + name + "-" + randomSuffix());
            PoolState.createProtectedDirectory(staging);
            var poolMoved = false;
            try {
                var workspace = staging.resolve("workspace");
                PoolState.createProtectedDirectory(workspace);
                var finalWorkspace = target.resolve("workspace").toAbsolutePath().normalize();
                var references = referencesOutsideDirectRoot(
                        seed.config().referenceRoots(), canonicalDirect);
                var config = new PoolState.FrozenConfig(
                        seed.config().cpus(), seed.config().memoryMib(), seed.config().swap(),
                        seed.config().runtimeRoot(), finalWorkspace.toString(), references);
                WorkerPoolConfig.validateMaterialized(name, config.cpus(), config.memoryMib(),
                        config.swap(), config.runtimeRoot(), config.workspaceRoot(),
                        config.referenceRoots(), canonicalDirect.toString(), home);

                var rootClone = staging.resolve(PoolState.ROOT_DISK_FILE);
                var dataClone = staging.resolve(PoolState.DATA_DISK_FILE);
                var kernelClone = staging.resolve(PoolState.KERNEL_FILE);
                operations.cloneApfs(seedDirectory.resolve(PoolState.ROOT_DISK_FILE), rootClone);
                operations.cloneApfs(seedDirectory.resolve(PoolState.DATA_DISK_FILE), dataClone);
                operations.cloneApfs(seedDirectory.resolve(PoolState.KERNEL_FILE), kernelClone);
                PoolState.setMutableFile(rootClone);
                PoolState.setMutableFile(dataClone);
                PoolState.setReadOnlyFile(kernelClone);
                verifyDiskImage(rootClone, seed.rootDisk(), "materialized root disk");
                verifyDiskImage(dataClone, seed.dataDisk(), "materialized data disk");
                verifyKernel(kernelClone, seed.kernel(), "materialized kernel");

                var versionFile = staging.resolve(PoolState.DISK_VERSION_FILE);
                Files.copy(seedDirectory.resolve(PoolState.DISK_VERSION_FILE), versionFile);
                PoolState.setReadOnlyFile(versionFile);
                var swapFile = staging.resolve(PoolState.SWAP_FILE);
                operations.createSparse(swapFile, VmManager.parseDiskSize(config.swap()));
                PoolState.setMutableFile(swapFile);

                var descriptor = PoolState.MaterializedDescriptor.create(
                        name, seedName, selectedGeneration, selectedIdentity, config,
                        canonicalDirect.toString(), seed.rootDisk(), seed.dataDisk(),
                        seed.kernel(), seed.diskVersion());
                var descriptorFile = staging.resolve(PoolState.DESCRIPTOR_FILE);
                PoolState.writeProtectedFile(descriptorFile, PoolState.jsonBytes(descriptor),
                        PoolState.readOnlyFilePermissions());
                moveDirectory(staging, target);
                poolMoved = true;
                PoolState.loadMaterialized(stateDirectory, home, name, descriptor.identity());
                return result(true, descriptor);
            } catch (Exception failure) {
                cleanupAfterFailure(poolMoved ? target : staging, failure);
                if (failure instanceof PoolException known) throw known;
                throw new PoolException("materialize_failed",
                        "could not materialize pool '" + name + "': "
                                + failure.getMessage(), failure);
            }
        } catch (PoolException e) {
            throw e;
        } catch (Exception e) {
            throw new PoolException("materialize_failed", "could not materialize pool '"
                    + name + "': " + e.getMessage(), e);
        }
    }

    private String requireStaticSeed() {
        selection.requireValid();
        if (selection.isLegacy() || selection.isMaterialized()) {
            throw new PoolException("static_seed_required",
                    "pool management requires a static named seed selected with ISX_POOL");
        }
        return selection.name().orElseThrow();
    }

    private PoolState.FrozenConfig freezeSelectedConfig(String seedName) {
        var selected = selection.pool().orElseThrow();
        var exports = VmHostExports.create(seedName, selected, home);
        var references = new LinkedHashMap<String, String>();
        exports.exports().stream()
                .filter(export -> export.kind() == VmHostExports.ExportKind.REFERENCE)
                .forEach(export -> references.put(export.name(), export.hostPath().toString()));
        return new PoolState.FrozenConfig(selected.cpus(), selected.memoryMib(), selected.swap(),
                exports.runtime().hostPath().toString(), exports.workspace().hostPath().toString(),
                Map.copyOf(references));
    }

    private Map<String, String> referencesOutsideDirectRoot(
            Map<String, String> references, Path directRoot) {
        var retained = new LinkedHashMap<String, String>();
        for (var entry : references.entrySet()) {
            var reference = Path.of(entry.getValue()).toAbsolutePath().normalize();
            if (reference.equals(directRoot) || reference.startsWith(directRoot)) {
                continue;
            }
            if (directRoot.startsWith(reference)) {
                throw new PoolException("invalid_request",
                        "direct root is nested beneath seed reference '" + entry.getKey() + "'");
            }
            retained.put(entry.getKey(), entry.getValue());
        }
        return Map.copyOf(retained);
    }

    private void requireExactRetry(
            PoolState.MaterializedDescriptor existing, String seedName, Path directRoot,
            String generation, String generationIdentity) {
        var same = existing.seedPool().equals(seedName)
                && existing.directRoot().equals(directRoot.toString());
        if (generation != null) {
            same = same && existing.generation().equals(generation)
                    && existing.generationIdentity().equals(generationIdentity);
        }
        if (!same) {
            throw new PoolException("pool_conflict",
                    "materialized pool '" + existing.name()
                            + "' already exists with a different published identity");
        }
    }

    private static MaterializeResult result(
            boolean changed, PoolState.MaterializedDescriptor descriptor) {
        return new MaterializeResult(changed, descriptor.name(), descriptor.identity(),
                descriptor.seedPool(), descriptor.generation(), descriptor.generationIdentity());
    }

    private Path canonicalDirectRoot(String poolName, String seedName, String value) {
        if (value == null || value.isBlank()) {
            throw new PoolException("invalid_request", "direct root is required");
        }
        try {
            var requested = Path.of(value);
            if (!requested.isAbsolute() || !Files.isDirectory(requested, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(requested)) {
                throw new PoolException("invalid_request",
                        "direct root must be a pre-existing physical directory");
            }
            var canonical = requested.toRealPath();
            if (!requested.normalize().equals(canonical)) {
                throw new PoolException("invalid_request", "direct root must be canonical");
            }
            VmHostExports.requireVfkitSafePath(canonical.toString());
            rejectProtectedDirectRoot(canonical, poolName, seedName);
            return canonical;
        } catch (PoolException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new PoolException("invalid_request",
                    "direct root must be a safe pre-existing canonical directory: "
                            + e.getMessage(), e);
        }
    }

    private void rejectProtectedDirectRoot(Path directRoot, String poolName, String seedName)
            throws IOException {
        var protectedRoots = new ArrayList<ProtectedRoot>();
        protectedRoots.add(new ProtectedRoot("ISX configuration", home.resolve(".config/incus-spawn")));
        protectedRoots.add(new ProtectedRoot("ISX state", stateDirectory));
        protectedRoots.add(new ProtectedRoot("ISX lifecycle locks", lockRoot(stateDirectory)));
        protectedRoots.add(new ProtectedRoot("ISX cache", home.resolve(".cache/incus-spawn")));
        protectedRoots.add(new ProtectedRoot(
                "ISX appliance data", home.resolve(".local/share/incus-spawn")));
        protectedRoots.add(new ProtectedRoot("selected appliance", applianceKernel.getParent()));
        selection.pool().ifPresent(pool -> protectedRoots.add(
                new ProtectedRoot("seed workspace", expandPoolPath(pool.workspaceRoot().path()))));
        protectedRoots.add(new ProtectedRoot("direct-pool registry", PoolState.root(stateDirectory)));
        protectedRoots.add(new ProtectedRoot(
                "sealed-seed registry", PoolState.seedRoot(stateDirectory, seedName)));
        protectedRoots.add(new ProtectedRoot(
                "materialized-pool registry", PoolState.materializedRoot(stateDirectory)));
        protectedRoots.add(new ProtectedRoot("materialized pool workspace",
                PoolState.materializedPool(stateDirectory, poolName).resolve("workspace")));
        for (var protectedRoot : protectedRoots) {
            if (protectedRoot.path() == null) continue;
            var canonicalProtected = canonicalizeExistingPrefix(protectedRoot.path());
            if (directRoot.startsWith(canonicalProtected)
                    || canonicalProtected.startsWith(directRoot)) {
                throw new PoolException("invalid_request", "direct root overlaps protected "
                        + protectedRoot.label() + " root " + canonicalProtected);
            }
        }
    }

    private Path expandPoolPath(String value) {
        if (value.equals("~")) return home;
        if (value.startsWith("~/")) return home.resolve(value.substring(2));
        return Path.of(value);
    }

    private static Path canonicalizeExistingPrefix(Path path) throws IOException {
        var normalized = path.toAbsolutePath().normalize();
        var suffix = new ArrayList<Path>();
        var existing = normalized;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            suffix.add(existing.getFileName());
            existing = existing.getParent();
        }
        if (existing == null) return normalized;
        var canonical = existing.toRealPath();
        for (int i = suffix.size() - 1; i >= 0; i--) {
            canonical = canonical.resolve(suffix.get(i));
        }
        return canonical.normalize();
    }

    void ensureRegistry() throws IOException {
        Files.createDirectories(stateDirectory);
        var root = PoolState.root(stateDirectory);
        ensureProtectedPath(root);
        ensureProtectedPath(root.resolve(PoolState.MATERIALIZED_DIRECTORY));
        ensureProtectedPath(root.resolve(PoolState.SEED_DIRECTORY));
    }

    static Path lockRoot(Path stateDirectory) {
        return stateDirectory.resolveSibling(stateDirectory.getFileName() + "-locks");
    }

    static Path managementLockFile(Path stateDirectory) {
        return lockRoot(stateDirectory).resolve("pool-management.lock");
    }

    private BoundedFileLock acquireManagementLock() throws IOException {
        return BoundedFileLock.acquire(managementLockFile(stateDirectory), Duration.ofSeconds(30),
                "Another isx process is managing worker pools — waiting...",
                "Timed out waiting for another isx process to finish managing worker pools.");
    }

    private BoundedFileLock acquireVmLock() throws IOException {
        return BoundedFileLock.acquire(selection.vmLockFile(lockRoot(stateDirectory)),
                Duration.ofSeconds(30),
                "Another isx process is managing the seed VM — waiting...",
                "Timed out waiting for another isx process to finish managing the seed VM.");
    }

    private void ensureProtectedPath(Path directory) throws IOException {
        PoolState.ensureProtectedDirectory(directory, home);
    }

    private static Path requirePhysicalFile(Path file, String label) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
            throw new PoolException("seed_incomplete", label + " is missing or is not a physical file");
        }
        return file;
    }

    private static String readDiskVersion(Path file) throws IOException {
        if (Files.size(file) > 256) throw new IOException("disk version file is too large");
        var version = Files.readString(file).strip();
        if (version.isEmpty()) throw new IOException("disk version is empty");
        return version;
    }

    private static PoolState.DiskImageMetadata inspectSeedDisk(Path file, String label) {
        try {
            return PoolState.inspectDiskImage(file);
        } catch (IOException e) {
            throw new PoolException("seed_incomplete", label
                    + " must be a physical btrfs disk image: " + e.getMessage(), e);
        }
    }

    private static PoolState.KernelDigest digestSeedKernel(Path file) {
        try {
            return PoolState.digestKernel(file);
        } catch (IOException e) {
            throw new PoolException("seed_incomplete",
                    "seed appliance kernel cannot be digested: " + e.getMessage(), e);
        }
    }

    private static void verifyDiskImage(
            Path file, PoolState.DiskImageMetadata expected, String label) throws IOException {
        var actual = PoolState.inspectDiskImage(file);
        if (!actual.equals(expected)) {
            throw new PoolException("seed_tampered",
                    label + " metadata does not match its sealed manifest");
        }
    }

    private static void verifyKernel(
            Path file, PoolState.KernelDigest expected, String label) throws IOException {
        var actual = PoolState.digestKernel(file);
        if (!actual.equals(expected)) {
            throw new PoolException("seed_tampered",
                    label + " digest does not match its sealed manifest");
        }
    }

    private void writeJsonAtomically(Path destination, Object value) throws IOException {
        var temporary = destination.resolveSibling(destination.getFileName() + ".tmp-" + randomSuffix());
        try {
            PoolState.writeProtectedFile(temporary, PoolState.jsonBytes(value),
                    PoolState.mutableFilePermissions());
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("pool state requires atomic rename support", e);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void moveDirectory(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            throw new IOException("pool state requires atomic directory rename support", e);
        }
    }

    private static void cleanStaging(Path parent, String prefix) throws IOException {
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) return;
        try (var entries = Files.list(parent)) {
            for (var entry : entries.filter(path -> path.getFileName().toString().startsWith(prefix)).toList()) {
                deleteTree(entry);
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void cleanupAfterFailure(Path path, Exception failure) {
        try {
            deleteTree(path);
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private String randomGeneration() {
        return "g-" + randomSuffix();
    }

    private String randomSuffix() {
        var bytes = new byte[16];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private record ProtectedRoot(String label, Path path) {}

    private static final class SystemOperations implements Operations {
        @Override
        public void cloneApfs(Path source, Path destination) throws IOException {
            var process = new ProcessBuilder("/bin/cp", "-c", source.toString(), destination.toString())
                    .redirectErrorStream(true)
                    .start();
            var output = new String(process.getInputStream().readAllBytes());
            try {
                if (process.waitFor() != 0) {
                    throw new IOException("/bin/cp -c failed: " + output.strip());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new IOException("interrupted while cloning APFS disk", e);
            }
        }

        @Override
        public void createSparse(Path destination, long size) throws IOException {
            try (var file = new RandomAccessFile(destination.toFile(), "rw")) {
                file.setLength(size);
            }
        }

        @Override
        public boolean vmRunning(Path vmStateDirectory) {
            var pidFile = vmStateDirectory.resolve("vm.pid");
            if (!Files.isRegularFile(pidFile, LinkOption.NOFOLLOW_LINKS)) return false;
            try {
                var pid = Long.parseLong(Files.readString(pidFile).strip());
                return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
            } catch (IOException | NumberFormatException e) {
                return false;
            }
        }
    }
}
