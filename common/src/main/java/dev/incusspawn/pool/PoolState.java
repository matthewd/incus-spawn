package dev.incusspawn.pool;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incusspawn.config.WorkerPoolConfig;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict manifests and protected state for sealed and materialized worker pools. */
public final class PoolState {

    public static final int VERSION = 1;
    public static final String ROOT_DIRECTORY = "direct-pools";
    public static final String MATERIALIZED_DIRECTORY = "pools";
    public static final String SEED_DIRECTORY = "seeds";
    public static final String DESCRIPTOR_FILE = "descriptor.json";
    public static final String ROOT_DISK_FILE = "disk.img";
    public static final String DATA_DISK_FILE = "data.img";
    public static final String KERNEL_FILE = "vmlinuz";
    public static final String SWAP_FILE = "swap.img";
    public static final String DISK_VERSION_FILE = "disk.version";

    private static final int MAX_MANIFEST_BYTES = 64 * 1024;
    private static final Pattern IDENTITY = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern GENERATION = Pattern.compile("g-[0-9a-f]{32}");
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> MUTABLE_FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> READ_ONLY_FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ);
    private static final long BTRFS_MAGIC_OFFSET = 0x10040;
    private static final byte[] BTRFS_MAGIC = "_BHRfS_M".getBytes(StandardCharsets.US_ASCII);
    private static final String BTRFS_FILESYSTEM = "btrfs";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private PoolState() {}

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DiskImageMetadata(long logicalSize, String filesystem) {
        public DiskImageMetadata {
            if (logicalSize < BTRFS_MAGIC_OFFSET + BTRFS_MAGIC.length) {
                throw new IllegalStateException("disk image is too small for a btrfs superblock");
            }
            if (!BTRFS_FILESYSTEM.equals(filesystem)) {
                throw new IllegalStateException("disk image filesystem must be btrfs");
            }
        }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record KernelDigest(long size, String sha256) {
        public KernelDigest {
            if (size <= 0) throw new IllegalStateException("kernel size must be positive");
            requireIdentity(sha256, "kernel digest");
        }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record FrozenConfig(
            int cpus,
            int memoryMib,
            String swap,
            String runtimeRoot,
            String workspaceRoot,
            Map<String, String> referenceRoots) {
        public FrozenConfig {
            referenceRoots = referenceRoots == null ? null : Map.copyOf(referenceRoots);
        }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SeedManifest(
            int version,
            String seedPool,
            String generation,
            String identity,
            FrozenConfig config,
            DiskImageMetadata rootDisk,
            DiskImageMetadata dataDisk,
            KernelDigest kernel,
            String diskVersion) {

        public static SeedManifest create(
                String seedPool, String generation, FrozenConfig config,
                DiskImageMetadata rootDisk, DiskImageMetadata dataDisk, KernelDigest kernel,
                String diskVersion) {
            var incomplete = new SeedManifest(VERSION, seedPool, generation, "", config,
                    rootDisk, dataDisk, kernel, diskVersion);
            return new SeedManifest(VERSION, seedPool, generation, seedIdentity(incomplete), config,
                    rootDisk, dataDisk, kernel, diskVersion);
        }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record LatestGeneration(int version, String generation, String identity) {}

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record MaterializedDescriptor(
            int version,
            String name,
            String identity,
            String seedPool,
            String generation,
            String generationIdentity,
            FrozenConfig config,
            String directRoot,
            DiskImageMetadata initialRootDisk,
            DiskImageMetadata initialDataDisk,
            KernelDigest kernel,
            String diskVersion) {

        public static MaterializedDescriptor create(
                String name, String seedPool, String generation, String generationIdentity,
                FrozenConfig config, String directRoot, DiskImageMetadata rootDisk,
                DiskImageMetadata dataDisk, KernelDigest kernel, String diskVersion) {
            var incomplete = new MaterializedDescriptor(VERSION, name, "", seedPool, generation,
                    generationIdentity, config, directRoot, rootDisk, dataDisk, kernel, diskVersion);
            return new MaterializedDescriptor(VERSION, name, materializedIdentity(incomplete),
                    seedPool, generation, generationIdentity, config, directRoot,
                    rootDisk, dataDisk, kernel, diskVersion);
        }

        public WorkerPoolConfig.Selected selected(Path home) {
            return WorkerPoolConfig.validateMaterialized(
                    name, config.cpus(), config.memoryMib(), config.swap(),
                    config.runtimeRoot(), config.workspaceRoot(), config.referenceRoots(),
                    directRoot, home);
        }

        /** Descriptor-bound selection that deliberately performs no host-root filesystem access. */
        public WorkerPoolConfig.Selected selectedWithoutRootValidation() {
            var references = new LinkedHashMap<String, WorkerPoolConfig.ReadOnlyExport>();
            config.referenceRoots().forEach((referenceName, referencePath) ->
                    references.put(referenceName,
                            new WorkerPoolConfig.ReadOnlyExport(referencePath)));
            return new WorkerPoolConfig.Selected(
                    config.cpus(), config.memoryMib(), config.swap(),
                    new WorkerPoolConfig.ReadOnlyExport(config.runtimeRoot()),
                    new WorkerPoolConfig.ReadWriteExport(config.workspaceRoot()),
                    references,
                    new WorkerPoolConfig.ReadWriteExport(directRoot));
        }
    }

    public static Path root(Path globalStateDir) {
        return globalStateDir.resolve(ROOT_DIRECTORY);
    }

    public static Path materializedRoot(Path globalStateDir) {
        return root(globalStateDir).resolve(MATERIALIZED_DIRECTORY);
    }

    public static Path materializedPool(Path globalStateDir, String name) {
        return materializedRoot(globalStateDir).resolve(name);
    }

    public static Path seedRoot(Path globalStateDir, String name) {
        return root(globalStateDir).resolve(SEED_DIRECTORY).resolve(name);
    }

    /** Strictly load a materialized descriptor and its provider-owned state. */
    public static MaterializedDescriptor loadMaterialized(
            Path globalStateDir, Path home, String name, String expectedIdentity) {
        if (!WorkerPoolConfig.isSafeName(name)) {
            throw invalid("unsafe materialized pool name '" + name + "'");
        }
        requireIdentity(expectedIdentity, "expected materialized pool identity");
        var owner = ownerOf(home);
        var root = root(globalStateDir);
        var pools = materializedRoot(globalStateDir);
        var pool = materializedPool(globalStateDir, name);
        requireProtectedDirectory(root, owner);
        requireProtectedDirectory(pools, owner);
        requireProtectedDirectory(pool, owner);

        var descriptor = readJson(pool.resolve(DESCRIPTOR_FILE), MaterializedDescriptor.class,
                owner, READ_ONLY_FILE_PERMISSIONS);
        if (descriptor == null) throw invalid("materialized pool descriptor is empty");
        validateDescriptor(descriptor, name, expectedIdentity);
        var rootDisk = pool.resolve(ROOT_DISK_FILE);
        var dataDisk = pool.resolve(DATA_DISK_FILE);
        var swap = pool.resolve(SWAP_FILE);
        var kernel = pool.resolve(KERNEL_FILE);
        requireProtectedFile(rootDisk, owner, MUTABLE_FILE_PERMISSIONS);
        requireProtectedFile(dataDisk, owner, MUTABLE_FILE_PERMISSIONS);
        requireProtectedFile(swap, owner, MUTABLE_FILE_PERMISSIONS);
        requireProtectedFile(kernel, owner, READ_ONLY_FILE_PERMISSIONS);
        verifyDiskImage(rootDisk, descriptor.initialRootDisk(), false,
                "materialized pool root disk");
        verifyDiskImage(dataDisk, descriptor.initialDataDisk(), true,
                "materialized pool data disk");
        requireLogicalSize(swap, frozenSizeBytes(descriptor.config().swap()),
                "materialized pool swap");
        verifyKernel(kernel, descriptor.kernel(), "materialized pool kernel");
        var versionFile = pool.resolve(DISK_VERSION_FILE);
        requireProtectedFile(versionFile, owner, READ_ONLY_FILE_PERMISSIONS);
        requireDiskVersion(versionFile, descriptor.diskVersion(), "materialized pool");

        var workspace = pool.resolve("workspace");
        requireProtectedDirectory(workspace, owner);
        try {
            if (!workspace.toRealPath().toString().equals(descriptor.config().workspaceRoot())) {
                throw invalid("materialized pool '" + name + "' workspace identity does not match its state");
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof IllegalStateException known) throw known;
            throw invalid("cannot verify materialized pool '" + name + "' workspace: "
                    + e.getMessage());
        }
        return descriptor;
    }

    /** Revalidate every host export only when an operation will launch the appliance. */
    public static MaterializedDescriptor loadMaterializedForLaunch(
            Path globalStateDir, Path home, String name, String expectedIdentity,
            boolean includeDirectRoot) {
        var descriptor = loadMaterialized(globalStateDir, home, name, expectedIdentity);
        requireFrozenRoot(descriptor.config().runtimeRoot(), "runtime root");
        requireFrozenRoot(descriptor.config().workspaceRoot(), "workspace root");
        descriptor.config().referenceRoots().forEach((referenceName, referencePath) ->
                requireFrozenRoot(referencePath, "reference root '" + referenceName + "'"));
        if (includeDirectRoot) requireFrozenRoot(descriptor.directRoot(), "direct root");
        return descriptor;
    }

    static MaterializedDescriptor loadMaterializedUncheckedIdentity(
            Path globalStateDir, Path home, String name) {
        var owner = ownerOf(home);
        var root = root(globalStateDir);
        var pools = materializedRoot(globalStateDir);
        var pool = materializedPool(globalStateDir, name);
        requireProtectedDirectory(root, owner);
        requireProtectedDirectory(pools, owner);
        requireProtectedDirectory(pool, owner);
        var descriptor = readJson(pool.resolve(DESCRIPTOR_FILE), MaterializedDescriptor.class,
                owner, READ_ONLY_FILE_PERMISSIONS);
        if (descriptor == null) throw invalid("materialized pool descriptor is empty");
        validateDescriptor(descriptor, name, descriptor.identity());
        return loadMaterializedForLaunch(
                globalStateDir, home, name, descriptor.identity(), true);
    }

    static SeedManifest loadSeed(
            Path globalStateDir, Path home, String seedPool, String generation,
            String expectedIdentity) {
        if (!WorkerPoolConfig.isSafeName(seedPool)) throw invalid("unsafe seed pool name");
        if (!isSafeGeneration(generation)) throw invalid("unsafe sealed generation '" + generation + "'");
        requireIdentity(expectedIdentity, "expected sealed generation identity");
        var owner = ownerOf(home);
        var root = root(globalStateDir);
        var seeds = root.resolve(SEED_DIRECTORY);
        var seed = seeds.resolve(seedPool);
        var generations = seed.resolve("generations");
        var directory = generations.resolve(generation);
        requireProtectedDirectory(root, owner);
        requireProtectedDirectory(seeds, owner);
        requireProtectedDirectory(seed, owner);
        requireProtectedDirectory(generations, owner);
        requireProtectedDirectory(directory, owner);
        var manifest = readJson(directory.resolve("manifest.json"), SeedManifest.class,
                owner, READ_ONLY_FILE_PERMISSIONS);
        if (manifest == null) throw invalid("sealed generation manifest is empty");
        validateSeed(manifest, seedPool, generation, expectedIdentity);
        requireProtectedFile(directory.resolve(ROOT_DISK_FILE), owner, READ_ONLY_FILE_PERMISSIONS);
        requireProtectedFile(directory.resolve(DATA_DISK_FILE), owner, READ_ONLY_FILE_PERMISSIONS);
        requireProtectedFile(directory.resolve(KERNEL_FILE), owner, READ_ONLY_FILE_PERMISSIONS);
        var versionFile = directory.resolve(DISK_VERSION_FILE);
        requireProtectedFile(versionFile, owner, READ_ONLY_FILE_PERMISSIONS);
        requireDiskVersion(versionFile, manifest.diskVersion(), "sealed generation");
        return manifest;
    }

    static LatestGeneration loadLatest(Path globalStateDir, Path home, String seedPool) {
        var owner = ownerOf(home);
        var seed = seedRoot(globalStateDir, seedPool);
        requireProtectedDirectory(seed, owner);
        var latest = readJson(seed.resolve("latest.json"), LatestGeneration.class,
                owner, MUTABLE_FILE_PERMISSIONS);
        if (latest == null || latest.version() != VERSION
                || !isSafeGeneration(latest.generation())) {
            throw invalid("invalid latest sealed generation for seed pool '" + seedPool + "'");
        }
        requireIdentity(latest.identity(), "latest sealed generation identity");
        return latest;
    }

    static void validateSeed(
            SeedManifest manifest, String seedPool, String generation, String expectedIdentity) {
        if (manifest.version() != VERSION
                || !seedPool.equals(manifest.seedPool())
                || !generation.equals(manifest.generation())) {
            throw invalid("sealed generation manifest identity does not match its state path");
        }
        requireIdentity(manifest.identity(), "sealed generation identity");
        validateFrozenConfig(manifest.config());
        if (manifest.diskVersion() == null || manifest.diskVersion().isBlank()
                || manifest.diskVersion().length() > 256) {
            throw invalid("sealed generation disk version is invalid");
        }
        if (!manifest.identity().equals(seedIdentity(manifest))
                || !manifest.identity().equals(expectedIdentity)) {
            throw invalid("sealed generation manifest digest does not match the expected identity");
        }
    }

    static void validateDescriptor(
            MaterializedDescriptor descriptor, String name, String expectedIdentity) {
        if (descriptor.version() != VERSION || !name.equals(descriptor.name())) {
            throw invalid("materialized pool descriptor identity does not match its state path");
        }
        if (!WorkerPoolConfig.isSafeName(descriptor.seedPool())
                || !isSafeGeneration(descriptor.generation())) {
            throw invalid("materialized pool descriptor has an unsafe seed identity");
        }
        requireIdentity(descriptor.generationIdentity(), "sealed generation identity");
        requireIdentity(descriptor.identity(), "materialized pool identity");
        validateFrozenConfig(descriptor.config());
        if (descriptor.directRoot() == null || descriptor.directRoot().isBlank()) {
            throw invalid("materialized pool direct root is required");
        }
        if (descriptor.diskVersion() == null || descriptor.diskVersion().isBlank()
                || descriptor.diskVersion().length() > 256) {
            throw invalid("materialized pool disk version is invalid");
        }
        if (!descriptor.identity().equals(materializedIdentity(descriptor))
                || !descriptor.identity().equals(expectedIdentity)) {
            throw invalid("materialized pool descriptor digest does not match the expected identity");
        }
    }

    private static void validateFrozenConfig(FrozenConfig config) {
        if (config == null || config.swap() == null || config.runtimeRoot() == null
                || config.workspaceRoot() == null || config.referenceRoots() == null) {
            throw invalid("pool manifest has incomplete frozen configuration");
        }
    }

    private static void requireFrozenRoot(String value, String label) {
        try {
            var path = Path.of(value);
            if (!path.isAbsolute() || Files.isSymbolicLink(path)
                    || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                    || !path.toRealPath().equals(path)) {
                throw invalid("materialized pool " + label
                        + " no longer has its sealed canonical identity");
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof IllegalStateException known) throw known;
            throw invalid("cannot verify materialized pool " + label + ": " + e.getMessage());
        }
    }

    static DiskImageMetadata inspectDiskImage(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
            throw new IOException("disk image is not a physical regular file: " + file);
        }
        var logicalSize = Files.size(file);
        var magic = new byte[BTRFS_MAGIC.length];
        try (var image = new RandomAccessFile(file.toFile(), "r")) {
            if (logicalSize < BTRFS_MAGIC_OFFSET + BTRFS_MAGIC.length) {
                throw new IOException("disk image is too small for a btrfs superblock: " + file);
            }
            image.seek(BTRFS_MAGIC_OFFSET);
            image.readFully(magic);
        }
        if (!Arrays.equals(magic, BTRFS_MAGIC)) {
            throw new IOException("disk image has no valid btrfs superblock: " + file);
        }
        return new DiskImageMetadata(logicalSize, BTRFS_FILESYSTEM);
    }

    static KernelDigest digestKernel(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
            throw new IOException("kernel is not a physical regular file: " + file);
        }
        var digest = sha256();
        try (var input = Files.newInputStream(file)) {
            var buffer = new byte[1024 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return new KernelDigest(Files.size(file), HexFormat.of().formatHex(digest.digest()));
    }

    private static void verifyDiskImage(
            Path file, DiskImageMetadata expected, boolean allowGrowth, String label) {
        try {
            var actual = inspectDiskImage(file);
            var sizeMatches = allowGrowth
                    ? actual.logicalSize() >= expected.logicalSize()
                    : actual.logicalSize() == expected.logicalSize();
            if (!sizeMatches || !actual.filesystem().equals(expected.filesystem())) {
                throw invalid(label + " metadata does not match its descriptor");
            }
        } catch (IOException e) {
            throw invalid("cannot verify " + label + ": " + e.getMessage());
        }
    }

    private static void verifyKernel(Path file, KernelDigest expected, String label) {
        try {
            if (!digestKernel(file).equals(expected)) {
                throw invalid(label + " digest does not match its descriptor");
            }
        } catch (IOException e) {
            throw invalid("cannot verify " + label + ": " + e.getMessage());
        }
    }

    private static void requireLogicalSize(Path file, long expected, String label) {
        try {
            if (Files.size(file) != expected) {
                throw invalid(label + " logical size does not match its frozen configuration");
            }
        } catch (IOException e) {
            throw invalid("cannot verify " + label + " logical size: " + e.getMessage());
        }
    }

    private static long frozenSizeBytes(String size) {
        var normalized = size.strip().toUpperCase();
        var suffix = normalized.charAt(normalized.length() - 1);
        var multiplier = switch (suffix) {
            case 'M' -> 1024L * 1024;
            case 'G' -> 1024L * 1024 * 1024;
            case 'T' -> 1024L * 1024 * 1024 * 1024;
            default -> 1L;
        };
        var number = Character.isLetter(suffix)
                ? normalized.substring(0, normalized.length() - 1) : normalized;
        try {
            return Math.multiplyExact(Long.parseLong(number), multiplier);
        } catch (NumberFormatException | ArithmeticException e) {
            throw invalid("materialized pool swap size is invalid");
        }
    }

    private static void requireDiskVersion(Path file, String expected, String label) {
        try {
            if (Files.size(file) > 256 || !Files.readString(file).strip().equals(expected)) {
                throw invalid(label + " disk version does not match its manifest");
            }
        } catch (IOException e) {
            throw invalid("cannot verify " + label + " disk version: " + e.getMessage());
        }
    }

    static String seedIdentity(SeedManifest manifest) {
        var digest = sha256();
        update(digest, "isx-sealed-worker-pool-v1");
        update(digest, Integer.toString(manifest.version()));
        update(digest, manifest.seedPool());
        update(digest, manifest.generation());
        updateConfig(digest, manifest.config());
        updateDiskImage(digest, manifest.rootDisk());
        updateDiskImage(digest, manifest.dataDisk());
        updateKernel(digest, manifest.kernel());
        update(digest, manifest.diskVersion());
        return HexFormat.of().formatHex(digest.digest());
    }

    static String materializedIdentity(MaterializedDescriptor descriptor) {
        var digest = sha256();
        update(digest, "isx-materialized-worker-pool-v1");
        update(digest, Integer.toString(descriptor.version()));
        update(digest, descriptor.name());
        update(digest, descriptor.seedPool());
        update(digest, descriptor.generation());
        update(digest, descriptor.generationIdentity());
        updateConfig(digest, descriptor.config());
        update(digest, descriptor.directRoot());
        updateDiskImage(digest, descriptor.initialRootDisk());
        updateDiskImage(digest, descriptor.initialDataDisk());
        updateKernel(digest, descriptor.kernel());
        update(digest, descriptor.diskVersion());
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void updateConfig(MessageDigest digest, FrozenConfig config) {
        update(digest, Integer.toString(config.cpus()));
        update(digest, Integer.toString(config.memoryMib()));
        update(digest, config.swap());
        update(digest, config.runtimeRoot());
        update(digest, config.workspaceRoot());
        config.referenceRoots().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    update(digest, entry.getKey());
                    update(digest, entry.getValue());
                });
    }

    private static void updateDiskImage(MessageDigest digest, DiskImageMetadata disk) {
        if (disk == null) throw invalid("pool manifest disk-image metadata is required");
        update(digest, Long.toString(disk.logicalSize()));
        update(digest, disk.filesystem());
    }

    private static void updateKernel(MessageDigest digest, KernelDigest kernel) {
        if (kernel == null) throw invalid("pool manifest kernel digest is required");
        update(digest, Long.toString(kernel.size()));
        update(digest, kernel.sha256());
    }

    private static void update(MessageDigest digest, String value) {
        if (value == null) throw invalid("pool manifest identity field is required");
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    static byte[] jsonBytes(Object value) {
        try {
            return JSON.writeValueAsBytes(value);
        } catch (IOException e) {
            throw new IllegalStateException("could not encode pool state", e);
        }
    }

    private static <T> T readJson(
            Path file, Class<T> type, UserPrincipal owner,
            Set<PosixFilePermission> expectedPermissions) {
        requireProtectedFile(file, owner, expectedPermissions);
        try {
            var size = Files.size(file);
            if (size <= 0 || size > MAX_MANIFEST_BYTES) {
                throw invalid("pool state file has an invalid size: " + file);
            }
            return JSON.readValue(Files.readAllBytes(file), type);
        } catch (IOException e) {
            throw invalid("cannot read pool state " + file + ": " + e.getMessage());
        }
    }

    static void createProtectedDirectory(Path directory) throws IOException {
        Files.createDirectory(directory,
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
    }

    static void ensureProtectedDirectory(Path directory, Path home) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            try {
                createProtectedDirectory(directory);
                return;
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // A concurrent first user created it. Validate the exact state below.
            }
        }
        requireProtectedDirectory(directory, ownerOf(home));
    }

    static void writeProtectedFile(
            Path file, byte[] bytes, Set<PosixFilePermission> permissions) throws IOException {
        Files.write(file, bytes);
        Files.setPosixFilePermissions(file, permissions);
    }

    static void setMutableFile(Path file) throws IOException {
        Files.setPosixFilePermissions(file, MUTABLE_FILE_PERMISSIONS);
    }

    static void setReadOnlyFile(Path file) throws IOException {
        Files.setPosixFilePermissions(file, READ_ONLY_FILE_PERMISSIONS);
    }

    static Set<PosixFilePermission> mutableFilePermissions() {
        return MUTABLE_FILE_PERMISSIONS;
    }

    static Set<PosixFilePermission> readOnlyFilePermissions() {
        return READ_ONLY_FILE_PERMISSIONS;
    }

    static boolean isSafeGeneration(String generation) {
        return generation != null && GENERATION.matcher(generation).matches();
    }

    static void requireIdentity(String identity, String label) {
        if (identity == null || !IDENTITY.matcher(identity).matches()) {
            throw invalid(label + " must be a full lowercase SHA-256 digest");
        }
    }

    private static UserPrincipal ownerOf(Path home) {
        try {
            return Files.getOwner(home, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw invalid("cannot determine protected pool-state owner: " + e.getMessage());
        }
    }

    private static void requireProtectedDirectory(Path directory, UserPrincipal owner) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(directory)) {
            throw invalid("pool state directory must be physical: " + directory);
        }
        requireProtection(directory, owner, DIRECTORY_PERMISSIONS);
    }

    private static void requireProtectedFile(
            Path file, UserPrincipal owner, Set<PosixFilePermission> permissions) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
            throw invalid("pool state file must be physical: " + file);
        }
        requireProtection(file, owner, permissions);
    }

    private static void requireProtection(
            Path path, UserPrincipal owner, Set<PosixFilePermission> permissions) {
        try {
            if (!Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).equals(owner)) {
                throw invalid("pool state must be owned by the current home owner: " + path);
            }
            var view = Files.getFileAttributeView(path, PosixFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (view == null || !view.readAttributes().permissions().equals(permissions)) {
                throw invalid("pool state has unsafe permissions: " + path);
            }
        } catch (IOException e) {
            throw invalid("cannot verify pool state protection for " + path + ": " + e.getMessage());
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 is required by the Java platform", e);
        }
    }

    private static IllegalStateException invalid(String message) {
        return new IllegalStateException(message);
    }
}
