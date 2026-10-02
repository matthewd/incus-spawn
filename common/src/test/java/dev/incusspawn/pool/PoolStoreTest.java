package dev.incusspawn.pool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incusspawn.config.WorkerPoolSelection;
import dev.incusspawn.util.BoundedFileLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PoolStoreTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path home;

    private Path state;
    private Path direct;
    private Path applianceKernel;
    private Path applianceVersionFile;
    private Path config;
    private FakeOperations operations;
    private WorkerPoolSelection seedSelection;

    @BeforeEach
    void setUp() throws Exception {
        state = home.resolve(".local/state/incus-spawn");
        direct = Files.createDirectory(home.resolve("project"));
        var runtime = Files.createDirectory(home.resolve("runtime"));
        var workspace = Files.createDirectory(home.resolve("seed-workspace"));
        var reference = Files.createDirectory(home.resolve("reference"));
        config = home.resolve("config.yaml");
        Files.writeString(config, """
                worker-pools:
                  seed:
                    cpus: 3
                    memory-mib: 4096
                    swap: 8M
                    runtime-root: %s
                    workspace-root: %s
                    reference-roots:
                      source: %s
                """.formatted(runtime, workspace, reference));
        seedSelection = WorkerPoolSelection.select("seed", config);

        var seedState = seedSelection.vmStateDir(state);
        Files.createDirectories(seedState);
        createBtrfsImage(seedState.resolve(PoolState.ROOT_DISK_FILE), 4L * 1024 * 1024 * 1024);
        createBtrfsImage(seedState.resolve(PoolState.DATA_DISK_FILE), 60L * 1024 * 1024 * 1024);
        Files.writeString(seedState.resolve(PoolState.DISK_VERSION_FILE), "1.2.3\n");
        applianceKernel = home.resolve("appliance/vmlinuz");
        applianceVersionFile = applianceKernel.resolveSibling("version");
        Files.createDirectories(applianceKernel.getParent());
        Files.writeString(applianceKernel, "kernel-content");
        Files.writeString(applianceVersionFile, "1.2.3\n");
        operations = new FakeOperations();
    }

    @Test
    void inspectReportsStoppedUnsealedAndThenValidatedLatestSeal() {
        var store = store();

        var unsealed = store.inspect();
        assertEquals("seed", unsealed.seedPool());
        assertFalse(unsealed.running());
        assertFalse(unsealed.sealed());
        assertNull(unsealed.generation());
        assertNull(unsealed.identity());

        var seal = store.seal();
        var sealed = store.inspect();
        assertFalse(sealed.running());
        assertTrue(sealed.sealed());
        assertEquals(seal.generation(), sealed.generation());
        assertEquals(seal.identity(), sealed.identity());
    }

    @Test
    void inspectReportsRunningWithoutClaimingTheSeedIsReady() {
        operations.running = true;

        var inspection = store().inspect();

        assertTrue(inspection.running());
        assertFalse(inspection.sealed());
    }

    @Test
    void sealAndMaterializeExactGenerationWithApfsCloneBoundary() throws Exception {
        var store = store();

        var seal = assertTimeout(Duration.ofSeconds(5), store::seal,
                "sealing sparse 4 GiB and 60 GiB images must not read their logical contents");
        assertTrue(seal.changed());
        assertEquals("seed", seal.seedPool());
        assertTrue(seal.generation().matches("g-[0-9a-f]{32}"));
        assertTrue(seal.identity().matches("[0-9a-f]{64}"));
        assertEquals(3, operations.cloneCount,
                "root, data, and kernel must each be cloned into the sealed generation");

        var materialized = store.materialize(
                "job-one", direct.toString(), seal.generation(), seal.identity());
        assertTrue(materialized.changed());
        assertEquals(6, operations.cloneCount,
                "root, data, and kernel must each be cloned into the materialized pool");
        assertTrue(materialized.identity().matches("[0-9a-f]{64}"));

        var poolState = PoolState.materializedPool(state, "job-one");
        assertEquals(4L * 1024 * 1024 * 1024,
                Files.size(poolState.resolve(PoolState.ROOT_DISK_FILE)));
        assertEquals(60L * 1024 * 1024 * 1024,
                Files.size(poolState.resolve(PoolState.DATA_DISK_FILE)));
        assertDoesNotThrow(() -> PoolState.inspectDiskImage(
                poolState.resolve(PoolState.ROOT_DISK_FILE)));
        assertDoesNotThrow(() -> PoolState.inspectDiskImage(
                poolState.resolve(PoolState.DATA_DISK_FILE)));
        assertEquals("kernel-content",
                Files.readString(poolState.resolve(PoolState.KERNEL_FILE)));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ),
                Files.getPosixFilePermissions(poolState.resolve(PoolState.KERNEL_FILE)));
        assertEquals(8L * 1024 * 1024, Files.size(poolState.resolve(PoolState.SWAP_FILE)));
        assertNotEquals(poolState.resolve(PoolState.SWAP_FILE), operations.lastCloneDestination,
                "swap must be freshly sparse-created, never cloned");

        var selection = WorkerPoolSelection.selectMaterialized(
                "job-one", materialized.identity(), state, home);
        assertTrue(selection.isMaterialized());
        assertEquals("materialized:job-one:" + materialized.identity(),
                selection.networkIdentity().orElseThrow());
        assertEquals(poolState, selection.vmStateDir(state));
        assertEquals(state.resolve("mi-" + materialized.identity().substring(0, 32) + ".sock"),
                selection.vmVsockSocket(state));
        assertEquals(state.resolve("ma-" + materialized.identity().substring(0, 32) + ".sock"),
                selection.vmAgentSocket(state));
        assertFalse(selection.vmVsockSocket(state).startsWith(poolState));
        assertNull(selection.poolWithoutDirectRoot().directRoot());
        assertFalse(selection.vmLockFile(PoolStore.lockRoot(state)).startsWith(poolState));
        assertEquals(poolState.resolve(PoolState.KERNEL_FILE),
                selection.vmKernelImage(state, applianceKernel));
        var selected = selection.pool().orElseThrow();
        assertEquals(3, selected.cpus());
        assertEquals(4096, selected.memoryMib());
        assertEquals("8M", selected.swap());
        assertEquals(poolState.resolve("workspace").toRealPath().toString(),
                selected.workspaceRoot().path());
        assertEquals(direct.toRealPath().toString(), selected.directRoot().path());
        assertEquals(home.resolve("runtime").toRealPath().toString(),
                selected.runtimeRoot().path());
        assertEquals(home.resolve("reference").toRealPath().toString(),
                selected.referenceRoots().get("source").path());

        var retry = store.materialize(
                "job-one", direct.toString(), seal.generation(), seal.identity());
        assertFalse(retry.changed());
        assertEquals(materialized.identity(), retry.identity());
        assertEquals(6, operations.cloneCount, "an exact retry must not clone again");
    }

    @Test
    void materializedCpuAndMemoryFollowCurrentSeedConfigAndDirectRootOverride() throws Exception {
        var store = store();
        var seal = store.seal();
        Files.writeString(config, """
                worker-pools:
                  seed:
                    cpus: 5
                    memory-mib: 8192
                    swap: 16M
                    runtime-root: %s
                    workspace-root: %s
                    reference-roots:
                      source: %s
                    direct-resources:
                      %s:
                        cpus: 9
                        memory-mib: 16384
                """.formatted(home.resolve("runtime"), home.resolve("seed-workspace"),
                        home.resolve("reference"), direct));
        var currentSeed = WorkerPoolSelection.select("seed", config);
        var currentStore = new PoolStore(
                state, home, applianceKernel, applianceVersionFile, "1.2.3",
                currentSeed, operations, new SecureRandom());

        var materialized = currentStore.materialize(
                "job-current", direct.toString(), seal.generation(), seal.identity());
        var frozen = WorkerPoolSelection.selectMaterialized(
                "job-current", materialized.identity(), state, home).pool().orElseThrow();
        assertEquals(9, frozen.cpus());
        assertEquals(16384, frozen.memoryMib());
        assertEquals("8M", frozen.swap(), "swap remains bound to the sealed generation");

        Files.writeString(config, """
                worker-pools:
                  seed:
                    cpus: 6
                    memory-mib: 12288
                    swap: 32M
                    runtime-root: %s
                    workspace-root: %s
                    reference-roots:
                      current: %s
                    direct-resources:
                      %s:
                        memory-mib: 24576
                """.formatted(home.resolve("current-runtime"),
                        home.resolve("current-workspace"), home.resolve("current-reference"),
                        direct));
        var refreshed = WorkerPoolSelection.selectMaterialized(
                "job-current", materialized.identity(), state, home, config)
                .pool().orElseThrow();

        assertEquals(6, refreshed.cpus());
        assertEquals(24576, refreshed.memoryMib());
        assertEquals("8M", refreshed.swap());
        assertEquals(frozen.runtimeRoot(), refreshed.runtimeRoot());
        assertEquals(frozen.workspaceRoot(), refreshed.workspaceRoot());
        assertEquals(frozen.referenceRoots(), refreshed.referenceRoots());
        assertEquals(frozen.directRoot(), refreshed.directRoot());
    }

    @Test
    void materializedCurrentResourcesFailClosedForMissingOrInvalidSeedConfig() {
        var store = store();
        var seal = store.seal();
        var materialized = store.materialize(
                "job-current", direct.toString(), seal.generation(), seal.identity());

        assertDoesNotThrow(() -> Files.delete(config));
        assertThrows(IllegalStateException.class, () -> WorkerPoolSelection.selectMaterialized(
                "job-current", materialized.identity(), state, home, config));

        assertDoesNotThrow(() -> Files.writeString(config, "worker-pools: [invalid]\n"));
        assertThrows(IllegalStateException.class, () -> WorkerPoolSelection.selectMaterialized(
                "job-current", materialized.identity(), state, home, config));

        assertDoesNotThrow(() -> Files.writeString(config, """
                worker-pools:
                  other:
                    cpus: 3
                    memory-mib: 4096
                    swap: 8M
                    runtime-root: %s
                    workspace-root: %s
                    reference-roots: {}
                """.formatted(home.resolve("runtime"), home.resolve("seed-workspace"))));
        var missingSeed = assertThrows(IllegalStateException.class,
                () -> WorkerPoolSelection.selectMaterialized(
                        "job-current", materialized.identity(), state, home, config));
        assertTrue(missingSeed.getMessage().contains("seed"));

        assertDoesNotThrow(() -> Files.writeString(config, """
                worker-pools:
                  seed:
                    cpus: 3
                    memory-mib: 4096
                    swap: 8M
                    runtime-root: %s
                    workspace-root: %s
                    reference-roots: {}
                    direct-resources:
                      %s: {}
                """.formatted(home.resolve("runtime"), home.resolve("seed-workspace"), direct)));
        assertThrows(IllegalStateException.class, () -> WorkerPoolSelection.selectMaterialized(
                "job-current", materialized.identity(), state, home, config));
    }

    @Test
    void latestRetryReturnsAlreadyPublishedPool() {
        var store = store();
        store.seal();
        var first = store.materialize("job-one", direct.toString(), null, null);
        store.seal();

        var retry = store.materialize("job-one", direct.toString(), null, null);

        assertFalse(retry.changed());
        assertEquals(first.identity(), retry.identity(),
                "a lost-response retry must not silently advance to a newer seed");
    }

    @Test
    void sealRequiresStoppedSeedAndPublishesNothingOnFailure() {
        operations.running = true;

        var failure = assertThrows(PoolStore.PoolException.class, () -> store().seal());

        assertEquals("seed_running", failure.code());
        assertFalse(Files.exists(PoolState.seedRoot(state, "seed").resolve("latest.json")));
        assertEquals(0, operations.cloneCount);
    }

    @Test
    void sealRequiresTheKernelUsedByStaticPools() throws Exception {
        Files.delete(applianceKernel);

        var failure = assertThrows(PoolStore.PoolException.class, () -> store().seal());

        assertEquals("seed_incomplete", failure.code());
        assertEquals(0, operations.cloneCount);
    }

    @Test
    void sealRejectsASeedThatHasNotAppliedTheCurrentAppliance() throws Exception {
        var seedVersion = seedSelection.vmStateDir(state).resolve(PoolState.DISK_VERSION_FILE);
        Files.writeString(seedVersion, "1.2.2\n");

        var staleRoot = assertThrows(PoolStore.PoolException.class, () -> store().seal());
        assertEquals("seed_outdated", staleRoot.code());
        assertEquals(0, operations.cloneCount);

        Files.writeString(seedVersion, "1.2.3\n");
        Files.writeString(applianceVersionFile, "1.2.2\n");
        var staleKernel = assertThrows(PoolStore.PoolException.class, () -> store().seal());
        assertEquals("seed_outdated", staleKernel.code());
        assertEquals(0, operations.cloneCount);
    }

    @Test
    void sealValidatesSourceDiskImagesBeforeCloning() throws Exception {
        var root = seedSelection.vmStateDir(state).resolve(PoolState.ROOT_DISK_FILE);
        try (var image = new RandomAccessFile(root.toFile(), "rw")) {
            image.seek(0x10040);
            image.write(new byte[8]);
        }

        var failure = assertThrows(PoolStore.PoolException.class, () -> store().seal());

        assertEquals("seed_incomplete", failure.code());
        assertEquals(0, operations.cloneCount);
        assertFalse(Files.exists(PoolState.seedRoot(state, "seed").resolve("latest.json")));
    }

    @Test
    void sealValidatesClonedDiskImagesBeforePublication() {
        operations.corruptAtClone = 1;

        var failure = assertThrows(PoolStore.PoolException.class, () -> store().seal());

        assertEquals("seal_failed", failure.code());
        assertEquals(3, operations.cloneCount,
                "all clone operations may finish before destination validation begins");
        assertFalse(Files.exists(PoolState.seedRoot(state, "seed").resolve("latest.json")));
    }

    @Test
    void sealedManifestRecordsDiskShapeAndOnlyHashesTheKernel() throws Exception {
        var seal = store().seal();
        var manifestFile = PoolState.seedRoot(state, "seed").resolve("generations")
                .resolve(seal.generation()).resolve("manifest.json");
        var manifest = JSON.readTree(Files.readAllBytes(manifestFile));

        assertEquals(4L * 1024 * 1024 * 1024,
                manifest.path("rootDisk").path("logicalSize").asLong());
        assertEquals("btrfs", manifest.path("rootDisk").path("filesystem").asText());
        assertFalse(manifest.path("rootDisk").has("sha256"));
        assertEquals(60L * 1024 * 1024 * 1024,
                manifest.path("dataDisk").path("logicalSize").asLong());
        assertFalse(manifest.path("dataDisk").has("sha256"));
        assertTrue(manifest.path("kernel").path("sha256").asText().matches("[0-9a-f]{64}"));
    }

    @Test
    void materializationRejectsTamperedSealedKernel() throws Exception {
        var store = store();
        var seal = store.seal();
        var kernel = PoolState.seedRoot(state, "seed").resolve("generations")
                .resolve(seal.generation()).resolve(PoolState.KERNEL_FILE);
        PoolState.setMutableFile(kernel);
        Files.writeString(kernel, "different-kernel");
        PoolState.setReadOnlyFile(kernel);

        var failure = assertThrows(PoolStore.PoolException.class, () -> store.materialize(
                "job-one", direct.toString(), seal.generation(), seal.identity()));

        assertEquals("seed_tampered", failure.code());
        assertFalse(Files.exists(PoolState.materializedPool(state, "job-one")));
        assertEquals(3, operations.cloneCount,
                "seed verification must finish before materialized clones begin");
    }

    @Test
    void cloneFailureCleansMaterializationStaging() throws Exception {
        var store = store();
        var seal = store.seal();
        operations.failAtClone = 4;

        var failure = assertThrows(PoolStore.PoolException.class, () -> store.materialize(
                "job-one", direct.toString(), seal.generation(), seal.identity()));

        assertEquals("materialize_failed", failure.code());
        assertFalse(Files.exists(PoolState.materializedPool(state, "job-one")));
        try (var children = Files.list(PoolState.materializedRoot(state))) {
            assertTrue(children.noneMatch(path -> path.getFileName().toString().startsWith(".staging-")));
        }
    }

    @Test
    void materializedSelectionRejectsWrongIdentitySymlinkAndUnsafePermissions() throws Exception {
        var store = store();
        var seal = store.seal();
        var result = store.materialize(
                "job-one", direct.toString(), seal.generation(), seal.identity());
        var wrong = "0".repeat(64);
        assertThrows(IllegalStateException.class, () -> WorkerPoolSelection.selectMaterialized(
                "job-one", wrong, state, home));

        var descriptor = PoolState.materializedPool(state, "job-one")
                .resolve(PoolState.DESCRIPTOR_FILE);
        Files.setPosixFilePermissions(descriptor, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ));
        var permissions = assertThrows(IllegalStateException.class,
                () -> WorkerPoolSelection.selectMaterialized(
                        "job-one", result.identity(), state, home));
        assertTrue(permissions.getMessage().contains("unsafe permissions"));

        Files.setPosixFilePermissions(descriptor, Set.of(PosixFilePermission.OWNER_READ));
        var saved = home.resolve("saved-descriptor");
        Files.move(descriptor, saved);
        Files.createSymbolicLink(descriptor, saved);
        var symlink = assertThrows(IllegalStateException.class,
                () -> WorkerPoolSelection.selectMaterialized(
                        "job-one", result.identity(), state, home));
        assertTrue(symlink.getMessage().contains("must be physical"));
    }

    @Test
    void malformedDescriptorFailsAsAClosedSelectionError() throws Exception {
        var store = store();
        var seal = store.seal();
        var result = store.materialize(
                "job-one", direct.toString(), seal.generation(), seal.identity());
        var descriptor = PoolState.materializedPool(state, "job-one")
                .resolve(PoolState.DESCRIPTOR_FILE);
        var json = JSON.readTree(Files.readAllBytes(descriptor));
        ((com.fasterxml.jackson.databind.node.ObjectNode) json).putNull("config");
        PoolState.setMutableFile(descriptor);
        Files.write(descriptor, JSON.writeValueAsBytes(json));
        PoolState.setReadOnlyFile(descriptor);

        var failure = assertThrows(IllegalStateException.class,
                () -> WorkerPoolSelection.selectMaterialized(
                        "job-one", result.identity(), state, home));

        assertTrue(failure.getMessage().contains("incomplete frozen configuration"));
    }

    @Test
    void directRootReplacesAnEqualOrNestedSeedReference() throws Exception {
        var store = store();
        var seal = store.seal();
        var reference = home.resolve("reference");

        var exact = store.materialize(
                "reference-project", reference.toString(), seal.generation(), seal.identity());
        var exactSelection = WorkerPoolSelection.selectMaterialized(
                "reference-project", exact.identity(), state, home).pool().orElseThrow();
        assertEquals(reference.toRealPath().toString(), exactSelection.directRoot().path());
        assertFalse(exactSelection.referenceRoots().containsKey("source"));

        var parentProject = Files.createDirectory(home.resolve("parent-project"));
        var nestedReference = Files.createDirectory(parentProject.resolve("reference"));
        var nestedConfig = home.resolve("nested-reference-config.yaml");
        Files.writeString(nestedConfig, """
                worker-pools:
                  nested-seed:
                    cpus: 3
                    memory-mib: 4096
                    swap: 8M
                    runtime-root: %s
                    workspace-root: %s
                    reference-roots:
                      nested: %s
                """.formatted(home.resolve("runtime"), home.resolve("seed-workspace"), nestedReference));
        var nestedSelection = WorkerPoolSelection.select("nested-seed", nestedConfig);
        var nestedState = nestedSelection.vmStateDir(state);
        Files.createDirectories(nestedState);
        createBtrfsImage(nestedState.resolve(PoolState.ROOT_DISK_FILE), 4L * 1024 * 1024 * 1024);
        createBtrfsImage(nestedState.resolve(PoolState.DATA_DISK_FILE), 60L * 1024 * 1024 * 1024);
        Files.writeString(nestedState.resolve(PoolState.DISK_VERSION_FILE), "1.2.3\n");
        var nestedStore = new PoolStore(
                state, home, applianceKernel, applianceVersionFile, "1.2.3",
                nestedSelection, operations, new SecureRandom());
        var nestedSeal = nestedStore.seal();
        var nested = nestedStore.materialize(
                "parent-project", parentProject.toString(),
                nestedSeal.generation(), nestedSeal.identity());
        var nestedPool = WorkerPoolSelection.selectMaterialized(
                "parent-project", nested.identity(), state, home).pool().orElseThrow();
        assertTrue(nestedPool.referenceRoots().isEmpty());
    }

    @Test
    void directRootNestedBeneathABroadSeedReferenceIsRejected() {
        var store = store();
        var seal = store.seal();
        var nested = assertDoesNotThrow(() -> Files.createDirectory(home.resolve("reference/project")));

        var failure = assertThrows(PoolStore.PoolException.class, () -> store.materialize(
                "nested-project", nested.toString(), seal.generation(), seal.identity()));

        assertEquals("invalid_request", failure.code());
        assertTrue(failure.getMessage().contains("nested beneath seed reference"));
    }

    @Test
    void directRootCannotOverlapProtectedIsxRootsInEitherDirection() throws Exception {
        var protectedChildren = java.util.List.of(
                Files.createDirectories(home.resolve(".config/incus-spawn/direct-child")),
                Files.createDirectories(state.resolve("direct-child")),
                Files.createDirectories(PoolStore.lockRoot(state).resolve("direct-child")),
                Files.createDirectories(home.resolve(".cache/incus-spawn/direct-child")),
                Files.createDirectories(home.resolve(".local/share/incus-spawn/direct-child")),
                Files.createDirectories(applianceKernel.getParent().resolve("direct-child")),
                home.resolve("seed-workspace"),
                Files.createDirectories(PoolState.root(state).resolve("direct-child")),
                Files.createDirectories(PoolState.seedRoot(state, "seed").resolve("direct-child")),
                Files.createDirectories(PoolState.materializedRoot(state).resolve("direct-child")),
                Files.createDirectories(PoolState.materializedPool(state, "future")
                        .resolve("workspace")));

        int index = 0;
        for (var candidate : protectedChildren) {
            var rejectedName = "rejected-" + index++;
            var failure = assertThrows(PoolStore.PoolException.class, () -> store().materialize(
                    rejectedName, candidate.toString(), null, null));
            assertEquals("invalid_request", failure.code());
        }

        var containsProtectedRoots = assertThrows(PoolStore.PoolException.class,
                () -> store().materialize("rejected-parent", home.toString(), null, null));
        assertEquals("invalid_request", containsProtectedRoots.code());
        assertEquals(0, operations.cloneCount);
    }

    @Test
    void directRootCannotOverlapTildeExpandedSeedWorkspace() throws Exception {
        var tildeWorkspace = Files.createDirectories(home.resolve("tilde-workspace"));
        var nested = Files.createDirectory(tildeWorkspace.resolve("project"));
        var config = home.resolve("tilde-config.yaml");
        Files.writeString(config, """
                worker-pools:
                  tilde-seed:
                    cpus: 3
                    memory-mib: 4096
                    swap: 8M
                    runtime-root: ~/runtime
                    workspace-root: ~/tilde-workspace
                    reference-roots: {}
                """);
        var tildeSelection = WorkerPoolSelection.select("tilde-seed", config);
        var tildeStore = new PoolStore(
                state, home, applianceKernel, applianceVersionFile, "1.2.3",
                tildeSelection, operations, new SecureRandom());

        var failure = assertThrows(PoolStore.PoolException.class, () -> tildeStore.materialize(
                "job-one", nested.toString(), null, null));

        assertEquals("invalid_request", failure.code());
        assertTrue(failure.getMessage().contains("seed workspace"));
        assertEquals(0, operations.cloneCount);
    }

    @Test
    void directRootMustBeRepresentableInVfkitArgumentsBeforePublication() throws Exception {
        var unsafe = Files.createDirectory(home.resolve("unsafe,direct"));

        var failure = assertThrows(PoolStore.PoolException.class,
                () -> store().materialize("job-one", unsafe.toString(), null, null));

        assertEquals("invalid_request", failure.code());
        assertTrue(failure.getMessage().contains("comma-separated device syntax"));
        assertFalse(Files.exists(PoolState.materializedPool(state, "job-one")));
    }

    @Test
    void materializedSelectionDoesNotInspectDirectRootButLaunchDoes() throws Exception {
        var store = store();
        var seal = store.seal();
        var result = store.materialize(
                "job-one", direct.toString(), seal.generation(), seal.identity());
        Files.delete(direct);

        var selection = assertDoesNotThrow(() -> WorkerPoolSelection.selectMaterialized(
                "job-one", result.identity(), state, home));
        assertDoesNotThrow(() -> selection.validateMaterializedForLaunch(
                state, home, false));
        var failure = assertThrows(IllegalStateException.class,
                () -> selection.validateMaterializedForLaunch(state, home));

        assertTrue(failure.getMessage().contains("direct root"));
    }

    @Test
    void materializedLaunchRevalidatesKernelDigest() throws Exception {
        var store = store();
        var seal = store.seal();
        var result = store.materialize(
                "job-one", direct.toString(), seal.generation(), seal.identity());
        var selection = WorkerPoolSelection.selectMaterialized(
                "job-one", result.identity(), state, home);
        var kernel = PoolState.materializedPool(state, "job-one").resolve(PoolState.KERNEL_FILE);
        PoolState.setMutableFile(kernel);
        Files.writeString(kernel, "changed-kernel");
        PoolState.setReadOnlyFile(kernel);

        var failure = assertThrows(IllegalStateException.class,
                () -> selection.validateMaterializedForLaunch(state, home));

        assertTrue(failure.getMessage().contains("kernel digest"));
    }

    @Test
    void materializedLaunchRejectsNonPhysicalRootDisk() throws Exception {
        var store = store();
        var seal = store.seal();
        var result = store.materialize(
                "job-one", direct.toString(), seal.generation(), seal.identity());
        var selection = WorkerPoolSelection.selectMaterialized(
                "job-one", result.identity(), state, home);
        var root = PoolState.materializedPool(state, "job-one").resolve(PoolState.ROOT_DISK_FILE);
        var saved = root.resolveSibling("saved-root.img");
        Files.move(root, saved);
        Files.createSymbolicLink(root, saved);

        var failure = assertThrows(IllegalStateException.class,
                () -> selection.validateMaterializedForLaunch(state, home));

        assertTrue(failure.getMessage().contains("must be physical"));
    }

    @Test
    void materializedLaunchRejectsCorruptBtrfsAndNonPhysicalSwap() throws Exception {
        var store = store();
        var seal = store.seal();
        var result = store.materialize(
                "job-one", direct.toString(), seal.generation(), seal.identity());
        var selection = WorkerPoolSelection.selectMaterialized(
                "job-one", result.identity(), state, home);
        var pool = PoolState.materializedPool(state, "job-one");
        var dataImage = pool.resolve(PoolState.DATA_DISK_FILE);
        try (var data = new RandomAccessFile(dataImage.toFile(), "rw")) {
            data.seek(0x10040);
            data.write(new byte[8]);
        }

        var magicFailure = assertThrows(IllegalStateException.class,
                () -> selection.validateMaterializedForLaunch(state, home));
        assertTrue(magicFailure.getMessage().contains("btrfs superblock"));

        try (var data = new RandomAccessFile(dataImage.toFile(), "rw")) {
            data.seek(0x10040);
            data.write("_BHRfS_M".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
        var swap = pool.resolve(PoolState.SWAP_FILE);
        var savedSwap = swap.resolveSibling("saved-swap.img");
        Files.move(swap, savedSwap);
        Files.createSymbolicLink(swap, savedSwap);

        var physicalFailure = assertThrows(IllegalStateException.class,
                () -> selection.validateMaterializedForLaunch(state, home));
        assertTrue(physicalFailure.getMessage().contains("must be physical"));
    }

    @Test
    void materializedLaunchRejectsShrunkDataAndWrongSwapSize() throws Exception {
        var store = store();
        var seal = store.seal();
        var result = store.materialize(
                "job-one", direct.toString(), seal.generation(), seal.identity());
        var selection = WorkerPoolSelection.selectMaterialized(
                "job-one", result.identity(), state, home);
        var pool = PoolState.materializedPool(state, "job-one");
        try (var data = new RandomAccessFile(
                pool.resolve(PoolState.DATA_DISK_FILE).toFile(), "rw")) {
            data.setLength(59L * 1024 * 1024 * 1024);
        }

        var dataFailure = assertThrows(IllegalStateException.class,
                () -> selection.validateMaterializedForLaunch(state, home));
        assertTrue(dataFailure.getMessage().contains("data disk metadata"));

        try (var data = new RandomAccessFile(
                pool.resolve(PoolState.DATA_DISK_FILE).toFile(), "rw")) {
            data.setLength(60L * 1024 * 1024 * 1024);
        }
        try (var swap = new RandomAccessFile(
                pool.resolve(PoolState.SWAP_FILE).toFile(), "rw")) {
            swap.setLength(8L * 1024 * 1024 + 1);
        }
        var swapFailure = assertThrows(IllegalStateException.class,
                () -> selection.validateMaterializedForLaunch(state, home));
        assertTrue(swapFailure.getMessage().contains("swap logical size"));
    }

    @Test
    void firstUseRegistryCreationIsRaceSafeAndManagementLockIsExternal() throws Exception {
        var store = store();
        var ready = new java.util.concurrent.CountDownLatch(8);
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    store.ensureRegistry();
                    return null;
                }));
            }
            ready.await();
            start.countDown();
            for (var future : futures) future.get();
        } finally {
            executor.shutdownNow();
        }

        var registry = PoolState.root(state);
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE),
                Files.getPosixFilePermissions(registry));
        assertFalse(Files.exists(registry.resolve("management.lock")));

        store.seal();
        var managementLock = PoolStore.managementLockFile(state);
        assertTrue(Files.isRegularFile(managementLock));
        assertFalse(managementLock.startsWith(state),
                "management lock must survive recursive pool-state deletion");
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(managementLock));
    }

    @Test
    void sealWaitsForTheSameExternalVmLockAsLifecycleOperations() throws Exception {
        var vmLock = seedSelection.vmLockFile(PoolStore.lockRoot(state));
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        BoundedFileLock held = BoundedFileLock.acquire(vmLock, Duration.ofSeconds(1),
                null, "test lock timed out");
        try {
            var sealing = executor.submit(() -> store().seal());
            Thread.sleep(100);
            assertFalse(sealing.isDone(), "seal must wait while the seed lifecycle lock is held");
            held.close();
            held = null;
            assertTrue(sealing.get(5, java.util.concurrent.TimeUnit.SECONDS).changed());
        } finally {
            if (held != null) held.close();
            executor.shutdownNow();
        }
        assertTrue(Files.exists(vmLock), "VM lock inode must not be unlinked after use");
    }

    @Test
    void trailingJsonTokenInvalidatesMaterializedSelection() throws Exception {
        var store = store();
        var seal = store.seal();
        var materialized = store.materialize(
                "job-one", direct.toString(), seal.generation(), seal.identity());
        var descriptor = PoolState.materializedPool(state, "job-one")
                .resolve(PoolState.DESCRIPTOR_FILE);
        PoolState.setMutableFile(descriptor);
        Files.writeString(descriptor, "\n{}\n", StandardOpenOption.APPEND);
        PoolState.setReadOnlyFile(descriptor);

        var failure = assertThrows(IllegalStateException.class,
                () -> WorkerPoolSelection.selectMaterialized(
                        "job-one", materialized.identity(), state, home));

        assertTrue(failure.getMessage().contains("Trailing token"));
    }

    @Test
    void latestPointerFailureRetainsAlreadyPublishedGeneration() throws Exception {
        var store = store();
        store.ensureRegistry();
        var seedRoot = PoolState.seedRoot(state, "seed");
        PoolState.ensureProtectedDirectory(seedRoot, home);
        PoolState.createProtectedDirectory(seedRoot.resolve("latest.json"));

        var failure = assertThrows(PoolStore.PoolException.class, store::seal);

        assertEquals("seal_failed", failure.code());
        var generations = seedRoot.resolve("generations");
        try (var entries = Files.list(generations)) {
            var published = entries.filter(path -> path.getFileName().toString().startsWith("g-"))
                    .toList();
            assertEquals(1, published.size());
            assertTrue(Files.isRegularFile(published.getFirst().resolve("manifest.json")));
        }
        try (var entries = Files.list(generations)) {
            assertTrue(entries.noneMatch(path -> path.getFileName().toString()
                    .startsWith(".staging-")));
        }
    }

    @Test
    void publishedNameRejectsDifferentExactRetry() {
        var store = store();
        var first = store.seal();
        store.materialize("job-one", direct.toString(), first.generation(), first.identity());
        var second = store.seal();

        var failure = assertThrows(PoolStore.PoolException.class, () -> store.materialize(
                "job-one", direct.toString(), second.generation(), second.identity()));

        assertEquals("pool_conflict", failure.code());
    }

    private PoolStore store() {
        return new PoolStore(state, home, applianceKernel, applianceVersionFile, "1.2.3",
                seedSelection, operations, new SecureRandom());
    }

    private static void createBtrfsImage(Path path, long logicalSize) throws IOException {
        try (var image = new RandomAccessFile(path.toFile(), "rw")) {
            image.setLength(logicalSize);
            image.seek(0x10040);
            image.write("_BHRfS_M".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
    }

    private static final class FakeOperations implements PoolStore.Operations {
        int cloneCount;
        int failAtClone = -1;
        int corruptAtClone = -1;
        boolean running;
        Path lastCloneDestination;

        @Override
        public void cloneApfs(Path source, Path destination) throws IOException {
            cloneCount++;
            if (cloneCount == failAtClone) throw new IOException("simulated cp -c failure");
            if (source.getFileName().toString().endsWith(".img")) {
                try (var input = new RandomAccessFile(source.toFile(), "r");
                     var output = new RandomAccessFile(destination.toFile(), "rw")) {
                    output.setLength(input.length());
                    var magic = new byte[8];
                    input.seek(0x10040);
                    input.readFully(magic);
                    output.seek(0x10040);
                    output.write(magic);
                }
            } else {
                Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
            }
            if (cloneCount == corruptAtClone) {
                try (var output = new RandomAccessFile(destination.toFile(), "rw")) {
                    output.seek(0x10040);
                    output.write(new byte[8]);
                }
            }
            lastCloneDestination = destination;
        }

        @Override
        public void createSparse(Path destination, long size) throws IOException {
            try (var file = new RandomAccessFile(destination.toFile(), "rw")) {
                file.setLength(size);
            }
        }

        @Override
        public boolean vmRunning(Path vmStateDirectory) {
            return running;
        }
    }
}
