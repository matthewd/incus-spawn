package dev.incusspawn.config;

import dev.incusspawn.Environment;
import dev.incusspawn.RuntimeConstants;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Immutable process-wide worker-pool selection.
 *
 * <p>{@code ISX_POOL} is read exactly once by {@link RuntimeConstants}. An unset variable selects
 * the legacy layout and resource detection. Any set value is validated against a strict config
 * load; an invalid selection is retained as a closed state and fails before command dispatch.
 */
public final class WorkerPoolSelection {

    public static final String ENVIRONMENT_VARIABLE = "ISX_POOL";

    private final String name;
    private final WorkerPoolConfig.Selected pool;
    private final String error;

    private WorkerPoolSelection(String name, WorkerPoolConfig.Selected pool, String error) {
        this.name = name;
        this.pool = pool;
        this.error = error;
    }

    public static WorkerPoolSelection legacy() {
        return new WorkerPoolSelection(null, null, null);
    }

    /** Strict, testable selection from an explicit environment value and config file. */
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
        return new WorkerPoolSelection(requestedName, frozen, null);
    }

    /** Called only from the run-time-initialized {@link RuntimeConstants} holder. */
    public static WorkerPoolSelection fromEnvironment() {
        var requested = System.getenv(ENVIRONMENT_VARIABLE);
        if (requested == null) return legacy();
        try {
            return select(requested, Environment.configDir().resolve("config.yaml"));
        } catch (IllegalStateException e) {
            // Static-initializer exceptions are wrapped in ExceptionInInitializerError and lose the
            // actionable command-line diagnostic. Keep an immutable invalid state and fail when the
            // entry point (or any pool-aware path/resource accessor) calls requireValid().
            return new WorkerPoolSelection(requested, null, e.getMessage());
        }
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
        return name == null;
    }

    public Optional<String> name() {
        requireValid();
        return Optional.ofNullable(name);
    }

    public Optional<WorkerPoolConfig.Selected> pool() {
        requireValid();
        return Optional.ofNullable(pool);
    }

    public Path vmStateDir(Path globalStateDir) {
        requireValid();
        return isLegacy() ? globalStateDir : globalStateDir.resolve("pools").resolve(name);
    }

    public Path instanceLockDir(Path globalStateDir, Path legacyLockDir) {
        requireValid();
        return isLegacy() ? legacyLockDir : vmStateDir(globalStateDir).resolve("locks");
    }
}
