package dev.incusspawn.automation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incusspawn.Platform;
import dev.incusspawn.automation.AutomationService.AutomationException;
import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.config.WorkerPoolConfig;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.InstanceLifecycle;
import dev.incusspawn.ssh.SshKeyManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Incus-backed implementation of the automation transport boundary. */
public final class IncusAutomationTransport implements AutomationTransport {

    private static final ObjectMapper JSON = new ObjectMapper();

    @FunctionalInterface
    interface SourceTranslator {
        String translate(String source, MountAccess access);
    }

    private final IncusClient incus;
    private final SourceTranslator sourceTranslator;

    public IncusAutomationTransport(IncusClient incus) {
        this(incus, IncusAutomationTransport::translateSource);
    }

    IncusAutomationTransport(IncusClient incus, SourceTranslator sourceTranslator) {
        this.incus = incus;
        this.sourceTranslator = sourceTranslator;
    }

    @Override
    public Optional<Instance> find(String name) {
        return incus.findInstanceMetadata(name).map(IncusAutomationTransport::toInstance);
    }

    @Override
    public List<Instance> findByAutomationKey(String key) {
        try {
            var nodes = JSON.readTree(incus.listJsonConfig());
            var result = new ArrayList<Instance>();
            for (var node : nodes) {
                var instance = toInstance(node);
                if (key.equals(instance.automationKey())) result.add(instance);
            }
            return List.copyOf(result);
        } catch (JsonProcessingException e) {
            throw new AutomationException("backend_error",
                    "Incus returned malformed instance metadata", e);
        }
    }

    @Override
    public void copyStopped(String template, String name, Map<String, String> atomicConfig) {
        var plan = incus.planCopy(template);
        if (!plan.cow()) {
            throw new AutomationException("cow_required",
                    "Automation requires a same-pool CoW copy; " + plan.fullCopyReason());
        }
        incus.copy(template, name, plan, atomicConfig);
    }

    @Override
    public void start(String name) {
        var resources = HostResourceSetup.deserialize(
                incus.configGet(name, Metadata.HOST_RESOURCES));
        if (!resources.isEmpty()) {
            HostResourceSetup.applyForInstanceQuietly(incus, name, resources, incus.isVm(name));
        }
        incus.start(name);
        incus.waitForReady(name);
        InstanceLifecycle.prepareAutomationSsh(
                incus, name, incus.configGet(name, Metadata.AUTOMATION_KEY));
    }

    @Override
    public void awaitReady(String name) {
        var unreadyTool = InstanceLifecycle.awaitToolReadinessQuietly(
                incus, name, incus.configGet(name, Metadata.BUILD_SOURCE));
        if (unreadyTool != null) {
            throw new AutomationException("tool_not_ready",
                    "Instance tool '" + unreadyTool + "' did not become ready");
        }
    }

    @Override
    public void stop(String name) {
        incus.stop(name);
    }

    @Override
    public void delete(String name) {
        incus.delete(name, true);
        SshKeyManager.cleanupInstance(name);
    }

    @Override
    public MountState inspectMount(MountRequest request) {
        var prepared = prepare(request);
        return inspectPrepared(prepared);
    }

    @Override
    public void mount(MountRequest request) {
        var prepared = prepare(request);
        var state = inspectPrepared(prepared);
        if (state == MountState.EXACT) return;
        requireState(request, state, MountState.ABSENT, "mount");
        var properties = new ArrayList<String>();
        properties.add("source=" + prepared.translatedSource());
        properties.add("path=" + request.target());
        if (request.access() == MountAccess.READ_ONLY) properties.add("readonly=true");
        try {
            incus.deviceAddIfAbsent(request.instance(), request.key(), request.device(), "disk",
                    properties.toArray(String[]::new));
        } catch (RuntimeException failure) {
            throw backendFailure("attach workspace device", failure);
        }
    }

    @Override
    public void unmount(MountRequest request) {
        var prepared = prepare(request);
        var state = inspectPrepared(prepared);
        if (state == MountState.ABSENT) return;
        requireState(request, state, MountState.EXACT, "unmount");
        try {
            incus.deviceRemoveExact(request.instance(), request.key(), request.device(),
                    prepared.expected());
        } catch (RuntimeException failure) {
            throw backendFailure("detach workspace device", failure);
        }
    }

    @Override
    public ExecOutcome exec(ExecRequest request, InputStream stdin, OutputStream stdout,
                            OutputStream stderr, Cancellation cancellation) {
        return incus.execExact(request.instance(), request.argv(), request.uid(), request.gid(),
                request.cwd(), request.environment(), stdin, stdout, stderr,
                request.timeoutMillis(), cancellation);
    }

    static MountState classifyOwnDevice(
            JsonNode metadata, String deviceName, Map<String, String> expected) {
        var devices = metadata.get("devices");
        if (devices == null || !devices.isObject()) return MountState.MALFORMED;
        var device = devices.get(deviceName);
        if (device == null) return MountState.ABSENT;
        if (!device.isObject()) return MountState.MALFORMED;
        for (var required : List.of("type", "source", "path")) {
            if (!device.has(required) || !device.get(required).isTextual()) {
                return MountState.MALFORMED;
            }
        }
        var fields = device.fields();
        int count = 0;
        while (fields.hasNext()) {
            var field = fields.next();
            count++;
            if (!field.getValue().isTextual()) return MountState.MALFORMED;
            if (!field.getValue().textValue().equals(expected.get(field.getKey()))) {
                return MountState.CONFLICTING;
            }
        }
        return count == expected.size() ? MountState.EXACT : MountState.CONFLICTING;
    }

    private PreparedMount prepare(MountRequest request) {
        final String translated;
        try {
            translated = sourceTranslator.translate(request.source(), request.access());
            if (isExportRoot(translated)) {
                throw new AutomationException("source_not_exported",
                        "source must be a leaf beneath a declared host export");
            }
        } catch (AutomationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new AutomationException("source_not_exported",
                    "source is not available with the requested access through host exports", e);
        }
        var expected = new LinkedHashMap<String, String>();
        expected.put("type", "disk");
        expected.put("source", translated);
        expected.put("path", request.target());
        if (request.access() == MountAccess.READ_ONLY) expected.put("readonly", "true");
        return new PreparedMount(request, translated, Map.copyOf(expected));
    }

    private MountState inspectPrepared(PreparedMount prepared) {
        try {
            var metadata = incus.findInstanceMetadata(prepared.request().instance())
                    .orElseThrow(() -> new AutomationException("instance_not_found",
                            "Instance disappeared while inspecting its workspace device"));
            return classifyOwnDevice(metadata, prepared.request().device(), prepared.expected());
        } catch (AutomationException e) {
            throw e;
        } catch (RuntimeException failure) {
            throw backendFailure("inspect workspace device", failure);
        }
    }

    private static boolean isExportRoot(String translated) {
        return translated.equals("/host/runtime")
                || translated.equals("/host/workspace")
                || translated.matches("^/host/references/[a-z][a-z0-9-]{0,21}$");
    }

    private static String translateSource(String source, MountAccess access) {
        var requested = access == MountAccess.READ_ONLY
                ? WorkerPoolConfig.AccessMode.READ_ONLY
                : WorkerPoolConfig.AccessMode.READ_WRITE;
        if (!Platform.isMacOS()) {
            try {
                return Path.of(source).toRealPath().toString();
            } catch (IOException e) {
                throw new IllegalStateException("source must identify an existing physical path", e);
            }
        }
        return HostResourceSetup.translateForVm(source, requested).appliancePath();
    }

    private static void requireState(MountRequest request, MountState actual,
                                     MountState expected, String operation) {
        if (actual == expected) return;
        var code = actual == MountState.MALFORMED ? "malformed_device" : "device_conflict";
        throw new AutomationException(code,
                "Device '" + request.device() + "' on instance '" + request.instance()
                        + "' cannot be used for automation " + operation);
    }

    private static AutomationException backendFailure(String action, RuntimeException cause) {
        return new AutomationException("backend_error",
                "Incus could not " + action, cause);
    }

    private record PreparedMount(MountRequest request, String translatedSource,
                                 Map<String, String> expected) {
    }

    private static Instance toInstance(JsonNode metadata) {
        var config = metadata.path("config");
        return new Instance(
                metadata.path("name").asText(""),
                metadata.path("status").asText(""),
                metadata.path("type").asText(""),
                config.path(Metadata.TYPE).asText(""),
                config.path(Metadata.AUTOMATION_KEY).asText(""),
                config.path(Metadata.AUTOMATION_TEMPLATE).asText(""));
    }
}
