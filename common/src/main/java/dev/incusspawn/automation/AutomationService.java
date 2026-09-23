package dev.incusspawn.automation;

import dev.incusspawn.incus.Metadata;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Version-one automation lifecycle semantics, independent of command-line parsing and Incus I/O.
 */
public final class AutomationService {

    private static final Pattern INSTANCE_NAME =
            Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9.-]{0,61}[A-Za-z0-9])?$");
    private static final Pattern DEVICE_NAME = Pattern.compile("^[a-z][a-z0-9-]{0,62}$");
    private static final Pattern SHA256_DIGEST = Pattern.compile("^[a-f0-9]{64}$");

    private final AutomationTransport transport;

    public AutomationService(AutomationTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    public record InstanceView(String name, String state, String instanceType, String template) {
    }

    public record LifecycleResult(String operation, boolean changed, boolean exists,
                                  InstanceView instance) {
    }

    public static final class AutomationException extends RuntimeException {
        private final String code;

        public AutomationException(String code, String message) {
            super(message);
            this.code = code;
        }

        public AutomationException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public LifecycleResult create(String name, String template, String key) {
        requireName(name, "name");
        requireName(template, "template");
        requireKey(key);

        var target = transport.find(name);
        if (target.isPresent()) {
            requireOwnership(target.get(), key);
            if (!template.equals(target.get().automationTemplate())) {
                throw new AutomationException("ownership_mismatch",
                        "Instance '" + name + "' belongs to this key but was created from '"
                                + target.get().automationTemplate() + "', not '" + template + "'");
            }
            return result("create", false, target.get());
        }

        var sameKey = transport.findByAutomationKey(key);
        if (!sameKey.isEmpty()) {
            throw duplicateKey(key, sameKey);
        }

        var source = transport.find(template).orElseThrow(() ->
                new AutomationException("template_not_found",
                        "Template '" + template + "' does not exist"));
        if (!Metadata.TYPE_BASE.equals(source.isxType())
                && !Metadata.TYPE_PROJECT.equals(source.isxType())) {
            throw new AutomationException("not_a_template",
                    "Source '" + template + "' is not an isx template");
        }
        if (!isStopped(source.status())) {
            throw new AutomationException("template_not_stopped",
                    "Template '" + template + "' must be stopped before it can be copied");
        }

        var metadata = new LinkedHashMap<String, String>();
        metadata.put(Metadata.AUTOMATION_KEY, key);
        metadata.put(Metadata.AUTOMATION_TEMPLATE, template);
        metadata.put(Metadata.TYPE, Metadata.TYPE_CLONE);
        metadata.put(Metadata.PARENT, template);
        metadata.put(Metadata.CREATED, Metadata.now());

        try {
            transport.copyStopped(template, name, metadata);
        } catch (RuntimeException failure) {
            // A response can be lost after Incus committed the copy. Re-read the target and accept
            // it only when the ownership and template metadata prove this exact request won.
            var reconciled = transport.find(name);
            if (reconciled.isPresent()
                    && key.equals(reconciled.get().automationKey())
                    && template.equals(reconciled.get().automationTemplate())) {
                return result("create", false, reconciled.get());
            }
            throw failure;
        }

        var created = transport.find(name).orElseThrow(() ->
                new AutomationException("create_unconfirmed",
                        "Copy completed but instance '" + name + "' could not be inspected"));
        requireOwnership(created, key);
        if (!template.equals(created.automationTemplate())) {
            throw new AutomationException("create_unconfirmed",
                    "Created instance has unexpected template ownership metadata");
        }
        if (!isStopped(created.status())) {
            throw new AutomationException("create_unconfirmed",
                    "Created instance '" + name + "' is not stopped");
        }
        return result("create", true, created);
    }

    public LifecycleResult inspect(String name, String key) {
        requireName(name, "name");
        if (key != null) requireKey(key);

        var instance = transport.find(name);
        if (instance.isEmpty()) {
            return new LifecycleResult("inspect", false, false, null);
        }
        requireAutomationOwned(instance.get());
        if (key != null) requireOwnership(instance.get(), key);
        return result("inspect", false, instance.get());
    }

    public LifecycleResult inspectByOwnershipDigest(String name, String expectedDigest) {
        requireName(name, "name");
        if (expectedDigest == null || !SHA256_DIGEST.matcher(expectedDigest).matches()) {
            throw new AutomationException("invalid_ownership_digest",
                    "ownership digest must be 64 lowercase hexadecimal characters");
        }
        var instance = transport.find(name).orElseThrow(() ->
                new AutomationException("instance_not_found",
                        "Instance '" + name + "' does not exist"));
        requireAutomationOwned(instance);
        var expected = HexFormat.of().parseHex(expectedDigest);
        var actual = sha256(instance.automationKey());
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new AutomationException("ownership_mismatch",
                    "Instance '" + name + "' is owned by a different automation key");
        }
        return result("inspect", false, instance);
    }

    public LifecycleResult start(String name, String key) {
        requireName(name, "name");
        requireKey(key);
        var instance = ownedInstance(name, key);
        if (isRunning(instance.status())) {
            transport.awaitReady(name);
            return result("start", false, instance);
        }
        if (!isStopped(instance.status())) {
            throw new AutomationException("invalid_state",
                    "Instance '" + name + "' is " + normalizedState(instance.status())
                            + "; cannot start");
        }

        try {
            transport.start(name);
        } catch (RuntimeException failure) {
            var reconciled = transport.find(name);
            if (reconciled.isPresent()) {
                requireOwnership(reconciled.get(), key);
                if (isRunning(reconciled.get().status())) {
                    transport.awaitReady(name);
                    return result("start", false, reconciled.get());
                }
            }
            throw failure;
        }
        var changed = ownedInstance(name, key);
        if (!isRunning(changed.status())) {
            throw new AutomationException("start_unconfirmed",
                    "Instance '" + name + "' did not reach running");
        }
        transport.awaitReady(name);
        return result("start", true, changed);
    }

    public LifecycleResult stop(String name, String key) {
        return changeState("stop", name, key, "Stopped", transport::stop);
    }

    public LifecycleResult delete(String name, String key) {
        return delete(name, key, null);
    }

    public LifecycleResult delete(String name, String key, String expectedTemplate) {
        requireKey(key);
        boolean keyOnly = name == null || name.isBlank();
        if (keyOnly && expectedTemplate == null) {
            throw new AutomationException("invalid_template",
                    "template is required for key-only deletion");
        }
        if (expectedTemplate != null) requireName(expectedTemplate, "template");
        AutomationTransport.Instance instance;
        if (keyOnly) {
            var matches = transport.findByAutomationKey(key);
            if (matches.isEmpty()) {
                return new LifecycleResult("delete", false, false, null);
            }
            if (matches.size() > 1) throw duplicateKey(key, matches);
            instance = matches.getFirst();
        } else {
            requireName(name, "name");
            var found = transport.find(name);
            if (found.isEmpty()) {
                return new LifecycleResult("delete", false, false, null);
            }
            instance = found.get();
        }
        requireOwnership(instance, key);
        if (expectedTemplate != null
                && !expectedTemplate.equals(instance.automationTemplate())) {
            throw new AutomationException("ownership_mismatch",
                    "Instance '" + instance.name() + "' belongs to this key but was created from '"
                            + instance.automationTemplate() + "', not '" + expectedTemplate + "'");
        }

        try {
            transport.delete(instance.name());
        } catch (RuntimeException failure) {
            if (transport.find(instance.name()).isEmpty()) {
                return new LifecycleResult("delete", false, false, null);
            }
            throw failure;
        }
        if (transport.find(instance.name()).isPresent()) {
            throw new AutomationException("delete_unconfirmed",
                    "Delete completed but instance '" + instance.name() + "' still exists");
        }
        return new LifecycleResult("delete", true, false, null);
    }

    public LifecycleResult mount(String name, String key, String device, String source,
                                 String target, String access) {
        var request = mountRequest(name, key, device, source, target, access);
        return changeMount("mount", request, true, key);
    }

    public LifecycleResult unmount(String name, String key, String device, String source,
                                   String target, String access) {
        var request = mountRequest(name, key, device, source, target, access);
        return changeMount("unmount", request, false, key);
    }

    public AutomationTransport.ExecOutcome exec(
            String name,
            String key,
            List<String> argv,
            Map<String, String> environment,
            Integer uid,
            Integer gid,
            String cwd,
            long timeoutMillis,
            InputStream stdin,
            OutputStream stdout,
            OutputStream stderr,
            AutomationTransport.Cancellation cancellation) {
        requireName(name, "name");
        requireKey(key);
        if (argv == null || argv.isEmpty()) {
            throw new AutomationException("invalid_argv", "argv must contain at least one element");
        }
        if (environment == null) {
            throw new AutomationException("invalid_environment", "environment must not be null");
        }
        if (timeoutMillis < 0) {
            throw new AutomationException("invalid_timeout", "timeout-ms must be zero or positive");
        }
        var instance = ownedInstance(name, key);
        if (!isRunning(instance.status())) {
            throw new AutomationException("invalid_state",
                    "Instance '" + name + "' is " + normalizedState(instance.status())
                            + "; exec requires running");
        }

        int resolvedUid = uid != null ? uid : 1000;
        int resolvedGid = gid != null ? gid : 1000;
        if (resolvedUid < 0 || resolvedGid < 0) {
            throw new AutomationException("invalid_identity", "uid and gid must be non-negative");
        }
        String resolvedCwd = cwd != null ? cwd : "/home/agentuser";
        if (resolvedCwd.isBlank()) {
            throw new AutomationException("invalid_cwd", "cwd must be non-empty");
        }
        var resolvedEnv = new LinkedHashMap<String, String>();
        resolvedEnv.put("HOME", "/home/agentuser");
        resolvedEnv.putAll(environment);

        var request = new AutomationTransport.ExecRequest(name, List.copyOf(argv),
                Map.copyOf(resolvedEnv), resolvedUid, resolvedGid, resolvedCwd, timeoutMillis);
        return transport.exec(request, stdin, stdout, stderr, cancellation);
    }

    private LifecycleResult changeMount(String operation,
                                        AutomationTransport.MountRequest request,
                                        boolean attaching,
                                        String key) {
        var instance = ownedMountableInstance(request.instance(), key);
        var before = transport.inspectMount(request);
        if (attaching && before == AutomationTransport.MountState.EXACT) {
            return result(operation, false, instance);
        }
        if (!attaching && before == AutomationTransport.MountState.ABSENT) {
            return result(operation, false, instance);
        }
        requireExpectedMountState(operation, request, before,
                attaching ? AutomationTransport.MountState.ABSENT
                        : AutomationTransport.MountState.EXACT);

        try {
            if (attaching) {
                transport.mount(request);
            } else {
                transport.unmount(request);
            }
        } catch (RuntimeException failure) {
            var reconciled = ownedMountableInstance(request.instance(), key);
            var reconciledState = transport.inspectMount(request);
            var desired = attaching
                    ? AutomationTransport.MountState.EXACT
                    : AutomationTransport.MountState.ABSENT;
            if (reconciledState == desired) {
                return result(operation, false, reconciled);
            }
            if (reconciledState == AutomationTransport.MountState.CONFLICTING
                    || reconciledState == AutomationTransport.MountState.MALFORMED) {
                requireExpectedMountState(operation, request, reconciledState,
                        attaching ? AutomationTransport.MountState.ABSENT
                                : AutomationTransport.MountState.EXACT);
            }
            throw failure;
        }

        var changed = ownedMountableInstance(request.instance(), key);
        var after = transport.inspectMount(request);
        var desired = attaching
                ? AutomationTransport.MountState.EXACT
                : AutomationTransport.MountState.ABSENT;
        if (after != desired) {
            throw new AutomationException(operation + "_unconfirmed",
                    "Instance device did not reach the requested " + operation + " state");
        }
        return result(operation, true, changed);
    }

    private static void requireExpectedMountState(
            String operation,
            AutomationTransport.MountRequest request,
            AutomationTransport.MountState actual,
            AutomationTransport.MountState expected) {
        if (actual == expected) return;
        if (actual == AutomationTransport.MountState.MALFORMED) {
            throw new AutomationException("malformed_device",
                    "Device '" + request.device() + "' on instance '" + request.instance()
                            + "' has malformed Incus configuration");
        }
        throw new AutomationException("device_conflict",
                "Device '" + request.device() + "' on instance '" + request.instance()
                        + "' does not exactly match the requested " + operation);
    }

    private LifecycleResult changeState(String operation, String name, String key,
                                        String desiredState,
                                        java.util.function.Consumer<String> mutation) {
        requireName(name, "name");
        requireKey(key);
        var instance = ownedInstance(name, key);
        if (desiredState.equalsIgnoreCase(instance.status())) {
            return result(operation, false, instance);
        }
        var allowedCurrent = "Running".equals(desiredState) ? "Stopped" : "Running";
        if (!allowedCurrent.equalsIgnoreCase(instance.status())) {
            throw new AutomationException("invalid_state",
                    "Instance '" + name + "' is " + normalizedState(instance.status())
                            + "; cannot " + operation);
        }

        try {
            mutation.accept(name);
        } catch (RuntimeException failure) {
            var reconciled = transport.find(name);
            if (reconciled.isPresent()) {
                requireOwnership(reconciled.get(), key);
                if (desiredState.equalsIgnoreCase(reconciled.get().status())) {
                    return result(operation, false, reconciled.get());
                }
            }
            throw failure;
        }
        var changed = ownedInstance(name, key);
        if (!desiredState.equalsIgnoreCase(changed.status())) {
            throw new AutomationException(operation + "_unconfirmed",
                    "Instance '" + name + "' did not reach "
                            + desiredState.toLowerCase(Locale.ROOT));
        }
        return result(operation, true, changed);
    }

    private AutomationTransport.Instance ownedInstance(String name, String key) {
        var instance = transport.find(name).orElseThrow(() ->
                new AutomationException("instance_not_found",
                        "Instance '" + name + "' does not exist"));
        requireOwnership(instance, key);
        return instance;
    }

    private AutomationTransport.Instance ownedMountableInstance(String name, String key) {
        var instance = ownedInstance(name, key);
        if (!isRunning(instance.status()) && !isStopped(instance.status())) {
            throw new AutomationException("invalid_state",
                    "Instance '" + name + "' is " + normalizedState(instance.status())
                            + "; workspace devices require running or stopped");
        }
        return instance;
    }

    private static void requireAutomationOwned(AutomationTransport.Instance instance) {
        if (instance.automationKey() == null || instance.automationKey().isBlank()) {
            throw new AutomationException("name_collision",
                    "Instance '" + instance.name() + "' is not owned by automation");
        }
    }

    private static void requireOwnership(AutomationTransport.Instance instance, String key) {
        requireAutomationOwned(instance);
        if (!key.equals(instance.automationKey())) {
            throw new AutomationException("ownership_mismatch",
                    "Instance '" + instance.name() + "' is owned by a different automation key");
        }
    }

    private static AutomationException duplicateKey(String key,
                                                     List<AutomationTransport.Instance> matches) {
        var names = matches.stream().map(AutomationTransport.Instance::name).sorted().toList();
        return new AutomationException("ambiguous_ownership",
                "Automation key identifies multiple instances: " + String.join(", ", names));
    }

    private static LifecycleResult result(String operation, boolean changed,
                                          AutomationTransport.Instance instance) {
        return new LifecycleResult(operation, changed, true, view(instance));
    }

    private static InstanceView view(AutomationTransport.Instance instance) {
        return new InstanceView(instance.name(), normalizedState(instance.status()),
                normalizedInstanceType(instance.instanceType()), instance.automationTemplate());
    }

    private static String normalizedState(String state) {
        return state == null ? "unknown" : state.toLowerCase(Locale.ROOT);
    }

    private static String normalizedInstanceType(String instanceType) {
        return switch (instanceType == null ? "" : instanceType) {
            case "virtual-machine" -> "vm";
            case "container" -> "container";
            default -> "unknown";
        };
    }

    private static boolean isRunning(String status) {
        return "Running".equalsIgnoreCase(status);
    }

    private static boolean isStopped(String status) {
        return "Stopped".equalsIgnoreCase(status);
    }

    private static AutomationTransport.MountRequest mountRequest(
            String name, String key, String device, String source, String target, String access) {
        requireName(name, "name");
        requireKey(key);
        if (device == null || !DEVICE_NAME.matcher(device).matches()) {
            throw new AutomationException("invalid_device",
                    "device must start with a lowercase letter and contain at most 63 "
                            + "lowercase letters, digits, or hyphens");
        }
        var resolvedSource = requireAbsolutePath(source, "source", true);
        var resolvedTarget = requireAbsolutePath(target, "target", true);
        var resolvedAccess = switch (access == null ? "" : access) {
            case "read-only" -> AutomationTransport.MountAccess.READ_ONLY;
            case "read-write" -> AutomationTransport.MountAccess.READ_WRITE;
            default -> throw new AutomationException("invalid_access",
                    "access must be read-only or read-write");
        };
        return new AutomationTransport.MountRequest(name, key, device, resolvedSource,
                resolvedTarget, resolvedAccess);
    }

    private static String requireAbsolutePath(String value, String label, boolean rejectRoot) {
        if (value == null || value.isBlank() || containsLineDelimiter(value)) {
            throw new AutomationException("invalid_" + label,
                    label + " must be an absolute path without NUL or newline characters");
        }
        final Path path;
        try {
            path = Path.of(value);
        } catch (InvalidPathException e) {
            throw new AutomationException("invalid_" + label,
                    label + " must be a valid absolute path");
        }
        if (!path.isAbsolute()) {
            throw new AutomationException("invalid_" + label,
                    label + " must be an absolute path");
        }
        for (var element : path) {
            if ("..".equals(element.toString())) {
                throw new AutomationException("invalid_" + label,
                        label + " must not contain '..' path elements");
            }
        }
        var normalized = path.normalize();
        if (rejectRoot && normalized.equals(normalized.getRoot())) {
            throw new AutomationException("invalid_target",
                    "target must not be the container root");
        }
        return normalized.toString();
    }

    private static boolean containsLineDelimiter(String value) {
        return value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static void requireName(String value, String label) {
        if (value == null || !INSTANCE_NAME.matcher(value).matches()) {
            throw new AutomationException("invalid_" + label,
                    label + " must be a valid Incus instance name");
        }
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank() || containsLineDelimiter(key)) {
            throw new AutomationException("invalid_key",
                    "key must be non-empty and contain no NUL or newline characters");
        }
    }
}
