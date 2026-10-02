package dev.incusspawn.config;

import dev.incusspawn.Environment;
import dev.incusspawn.RuntimeConstants;
import dev.incusspawn.pool.PoolState;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Immutable process-wide worker-pool selection.
 *
 * <p>{@code ISX_POOL} retains its exact static named-pool behavior. Materialized pools use the
 * separate {@code ISX_MATERIALIZED_POOL} and {@code ISX_MATERIALIZED_POOL_IDENTITY} pair so a
 * missing, partial, or corrupt dynamic selection can never fall back to static configuration.
 * An internal maintenance marker scopes no-Direct launch and API access to cleanup commands.
 */
public final class WorkerPoolSelection {

    public static final String ENVIRONMENT_VARIABLE = "ISX_POOL";
    public static final String MATERIALIZED_ENVIRONMENT_VARIABLE = "ISX_MATERIALIZED_POOL";
    public static final String MATERIALIZED_IDENTITY_ENVIRONMENT_VARIABLE =
            "ISX_MATERIALIZED_POOL_IDENTITY";
    public static final String MATERIALIZED_MAINTENANCE_ENVIRONMENT_VARIABLE =
            "ISX_MATERIALIZED_POOL_MAINTENANCE";

    private enum Kind { LEGACY, STATIC, MATERIALIZED, INVALID }

    private final Kind kind;
    private final String name;
    private final WorkerPoolConfig.Selected pool;
    private final String identity;
    private final boolean maintenance;
    private final String error;

    private WorkerPoolSelection(
            Kind kind, String name, WorkerPoolConfig.Selected pool, String identity,
            boolean maintenance, String error) {
        this.kind = kind;
        this.name = name;
        this.pool = pool;
        this.identity = identity;
        this.maintenance = maintenance;
        this.error = error;
    }

    public static WorkerPoolSelection legacy() {
        return new WorkerPoolSelection(Kind.LEGACY, null, null, null, false, null);
    }

    /** Strict, testable static selection from an explicit environment value and config file. */
    public static WorkerPoolSelection select(String requestedName, Path configFile) {
        if (requestedName == null) return legacy();
        if (!WorkerPoolConfig.isSafeName(requestedName)) {
            throw new IllegalStateException(ENVIRONMENT_VARIABLE + " has unsafe worker pool name '"
                    + requestedName
                    + "' (use lowercase letters, digits, and hyphens; start with a letter)");
        }

        var config = SpawnConfig.loadStrict(configFile);
        var selected = config.getWorkerPools().get(requestedName);
        if (selected == null) {
            throw new IllegalStateException("worker pool '" + requestedName
                    + "' selected by " + ENVIRONMENT_VARIABLE + " is not defined in " + configFile);
        }
        // loadStrict validated every pool. Freeze again only to make this method safe if that
        // implementation changes; it also gives the selection its own immutable map.
        var frozen = selected.validateAndFreeze(requestedName, Environment.home());
        return new WorkerPoolSelection(
                Kind.STATIC, requestedName, frozen, null, false, null);
    }

    /** Strict, testable materialized selection from protected state alone. */
    public static WorkerPoolSelection selectMaterialized(
            String requestedName, String expectedIdentity, Path globalStateDir, Path home) {
        var descriptor = loadMaterializedDescriptor(
                requestedName, expectedIdentity, globalStateDir, home);
        return materializedSelection(requestedName, descriptor,
                descriptor.selectedWithoutRootValidation(), false);
    }

    /** Resolve current seed CPU/memory without changing the descriptor-bound export plan. */
    public static WorkerPoolSelection selectMaterialized(
            String requestedName, String expectedIdentity, Path globalStateDir, Path home,
            Path configFile) {
        return selectMaterialized(
                requestedName, expectedIdentity, globalStateDir, home, configFile, false);
    }

    static WorkerPoolSelection selectMaterialized(
            String requestedName, String expectedIdentity, Path globalStateDir, Path home,
            Path configFile, boolean maintenance) {
        var descriptor = loadMaterializedDescriptor(
                requestedName, expectedIdentity, globalStateDir, home);
        var config = SpawnConfig.loadStrict(configFile);
        var seed = config.getWorkerPools().get(descriptor.seedPool());
        if (seed == null) {
            throw new IllegalStateException("worker pool '" + descriptor.seedPool()
                    + "' used by materialized pool '" + requestedName
                    + "' is not defined in " + configFile);
        }
        var selectedSeed = seed.validateAndFreeze(descriptor.seedPool(), home);
        var resources = selectedSeed.resourcesForDirectRoot(descriptor.directRoot());
        return materializedSelection(requestedName, descriptor,
                descriptor.selectedWithoutRootValidation(
                        resources.cpus(), resources.memoryMib()), maintenance);
    }

    private static PoolState.MaterializedDescriptor loadMaterializedDescriptor(
            String requestedName, String expectedIdentity, Path globalStateDir, Path home) {
        if (!WorkerPoolConfig.isSafeName(requestedName)) {
            throw new IllegalStateException(MATERIALIZED_ENVIRONMENT_VARIABLE
                    + " has unsafe worker pool name '" + requestedName + "'");
        }
        return PoolState.loadMaterialized(
                globalStateDir, home, requestedName, expectedIdentity);
    }

    private static WorkerPoolSelection materializedSelection(
            String requestedName, PoolState.MaterializedDescriptor descriptor,
            WorkerPoolConfig.Selected pool, boolean maintenance) {
        return new WorkerPoolSelection(Kind.MATERIALIZED, requestedName,
                pool, descriptor.identity(), maintenance, null);
    }

    /** Called only from the run-time-initialized {@link RuntimeConstants} holder. */
    public static WorkerPoolSelection fromEnvironment() {
        var requestedStatic = System.getenv(ENVIRONMENT_VARIABLE);
        var requestedMaterialized = System.getenv(MATERIALIZED_ENVIRONMENT_VARIABLE);
        var expectedIdentity = System.getenv(MATERIALIZED_IDENTITY_ENVIRONMENT_VARIABLE);
        var requestedMaintenance = System.getenv(
                MATERIALIZED_MAINTENANCE_ENVIRONMENT_VARIABLE);
        try {
            return selectEnvironment(
                    requestedStatic, requestedMaterialized, expectedIdentity,
                    requestedMaintenance, Environment.configDir().resolve("config.yaml"),
                    Environment.stateDir(), Environment.home());
        } catch (IllegalStateException e) {
            // Static-initializer exceptions are wrapped in ExceptionInInitializerError and lose the
            // actionable command-line diagnostic. Keep an immutable invalid state and fail when the
            // entry point (or any pool-aware path/resource accessor) calls requireValid().
            var requested = requestedMaterialized != null ? requestedMaterialized : requestedStatic;
            return new WorkerPoolSelection(
                    Kind.INVALID, requested, null, expectedIdentity, false, e.getMessage());
        }
    }

    static WorkerPoolSelection selectEnvironment(
            String requestedStatic, String requestedMaterialized, String expectedIdentity,
            Path configFile, Path globalStateDir, Path home) {
        return selectEnvironment(
                requestedStatic, requestedMaterialized, expectedIdentity, null,
                configFile, globalStateDir, home);
    }

    static WorkerPoolSelection selectEnvironment(
            String requestedStatic, String requestedMaterialized, String expectedIdentity,
            String requestedMaintenance, Path configFile, Path globalStateDir, Path home) {
        if (requestedMaintenance != null && !"1".equals(requestedMaintenance)) {
            throw new IllegalStateException(MATERIALIZED_MAINTENANCE_ENVIRONMENT_VARIABLE
                    + " must be exactly '1' when set");
        }
        if (requestedMaterialized == null && expectedIdentity == null) {
            if (requestedMaintenance != null) {
                throw new IllegalStateException(MATERIALIZED_MAINTENANCE_ENVIRONMENT_VARIABLE
                        + " requires a materialized pool selection");
            }
            return requestedStatic == null ? legacy() : select(requestedStatic, configFile);
        }
        if (requestedMaterialized == null || expectedIdentity == null) {
            throw new IllegalStateException(MATERIALIZED_ENVIRONMENT_VARIABLE + " and "
                    + MATERIALIZED_IDENTITY_ENVIRONMENT_VARIABLE + " must be set together");
        }
        if (requestedStatic != null) {
            throw new IllegalStateException(ENVIRONMENT_VARIABLE + " cannot be combined with "
                    + MATERIALIZED_ENVIRONMENT_VARIABLE);
        }
        return selectMaterialized(
                requestedMaterialized, expectedIdentity, globalStateDir, home,
                configFile, requestedMaintenance != null);
    }

    /** The immutable process selection. There is intentionally no setter or reset hook. */
    public static WorkerPoolSelection current() {
        var selection = RuntimeConstants.WORKER_POOL;
        selection.requireValid();
        return selection;
    }

    public void requireValid() {
        if (error != null) throw new IllegalStateException(error);
    }

    public boolean isLegacy() {
        requireValid();
        return kind == Kind.LEGACY;
    }

    public boolean isMaterialized() {
        requireValid();
        return kind == Kind.MATERIALIZED;
    }

    public boolean isStaticNamed() {
        requireValid();
        return kind == Kind.STATIC;
    }

    public boolean isMaterializedMaintenance() {
        requireValid();
        return kind == Kind.MATERIALIZED && maintenance;
    }

    public Optional<String> name() {
        requireValid();
        return Optional.ofNullable(name);
    }

    public Optional<String> identity() {
        requireValid();
        return Optional.ofNullable(identity);
    }

    public Optional<WorkerPoolConfig.Selected> pool() {
        requireValid();
        return Optional.ofNullable(pool);
    }

    /** Stable network identity; static named pools preserve their historical name-only identity. */
    public Optional<String> networkIdentity() {
        requireValid();
        if (kind == Kind.MATERIALIZED) {
            return Optional.of("materialized:" + name + ":" + identity);
        }
        return Optional.ofNullable(name);
    }

    public Path vmStateDir(Path globalStateDir) {
        requireValid();
        return switch (kind) {
            case LEGACY -> globalStateDir;
            case STATIC -> globalStateDir.resolve("pools").resolve(name);
            case MATERIALIZED -> PoolState.materializedPool(globalStateDir, name);
            case INVALID -> throw new IllegalStateException(error);
        };
    }

    public Path vmKernelImage(Path globalStateDir, Path applianceKernel) {
        requireValid();
        return isMaterialized()
                ? vmStateDir(globalStateDir).resolve(PoolState.KERNEL_FILE)
                : applianceKernel;
    }

    /**
     * Host-side Unix socket for the Incus vsock tunnel. Materialized pool names and identities
     * make their state paths too long for macOS sockaddr_un, so only their ephemeral sockets use
     * a compact identity-derived path at the global state root. The full descriptor identity is
     * still validated before selection and its first 128 bits safely isolate concurrent pools.
     */
    public Path vmVsockSocket(Path globalStateDir) {
        requireValid();
        return isMaterialized()
                ? globalStateDir.resolve("mi-" + identity.substring(0, 32) + ".sock")
                : vmStateDir(globalStateDir).resolve("vm.incus.sock");
    }

    /** Host-side Unix socket for the independent appliance control agent. */
    public Path vmAgentSocket(Path globalStateDir) {
        requireValid();
        return isMaterialized()
                ? globalStateDir.resolve("ma-" + identity.substring(0, 32) + ".sock")
                : vmStateDir(globalStateDir).resolve("vm.agent.sock");
    }

    /** Re-read and verify protected materialized state immediately before each VM launch. */
    public void validateMaterializedForLaunch(Path globalStateDir, Path home) {
        validateMaterializedForLaunch(globalStateDir, home, true);
    }

    public void validateMaterializedForLaunch(
            Path globalStateDir, Path home, boolean includeDirectRoot) {
        requireValid();
        if (isMaterialized()) {
            PoolState.loadMaterializedForLaunch(
                    globalStateDir, home, name, identity, includeDirectRoot);
        }
    }

    /** Remove only the Direct export from an already validated materialized pool configuration. */
    public WorkerPoolConfig.Selected poolWithoutDirectRoot() {
        requireValid();
        if (!isMaterialized()) {
            throw new IllegalStateException("only a materialized pool has a Direct export");
        }
        return new WorkerPoolConfig.Selected(
                pool.cpus(), pool.memoryMib(), pool.swap(), pool.runtimeRoot(),
                pool.workspaceRoot(), pool.referenceRoots());
    }

    /** Stable namespace for locks that must outlive deletion of the selected VM state. */
    public Path lifecycleLockDir(Path globalLockRoot) {
        requireValid();
        return switch (kind) {
            case LEGACY -> globalLockRoot.resolve("legacy");
            case STATIC -> globalLockRoot.resolve("static-" + name);
            case MATERIALIZED -> globalLockRoot.resolve("materialized-" + name + "-" + identity);
            case INVALID -> throw new IllegalStateException(error);
        };
    }

    public Path vmLockFile(Path globalLockRoot) {
        return lifecycleLockDir(globalLockRoot).resolve("vm.lock");
    }

    public Path instanceLockDir(Path globalLockRoot) {
        return lifecycleLockDir(globalLockRoot).resolve("instances");
    }
}
