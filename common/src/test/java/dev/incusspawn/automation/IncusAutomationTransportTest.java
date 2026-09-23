package dev.incusspawn.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.incusspawn.automation.AutomationService.AutomationException;
import dev.incusspawn.config.BuildSource;
import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.tool.ToolDef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class IncusAutomationTransportTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void readOnlyMountUsesTranslatedLeafAndOnlyExactIncusFields() {
        var incus = new FakeIncusClient();
        var transport = new IncusAutomationTransport(incus,
                (source, access) -> "/host/workspace/job-17");
        var request = request(AutomationTransport.MountAccess.READ_ONLY);

        assertEquals(AutomationTransport.MountState.ABSENT, transport.inspectMount(request));
        transport.mount(request);

        assertEquals(Map.of(
                "type", "disk",
                "source", "/host/workspace/job-17",
                "path", "/home/agentuser/work",
                "readonly", "true"), incus.lastAdded);
        assertFalse(incus.lastAdded.get("source").equals("/host/workspace"),
                "a leaf request must not broaden to the workspace export root");
        assertEquals(AutomationTransport.MountState.EXACT, transport.inspectMount(request));
        transport.mount(request);
        assertEquals(1, incus.addCount);
        assertEquals(0, incus.nameOnlyAddCount);
    }

    @Test
    void readWriteMountOmitsReadonlyAndAccessChangesConflict() {
        var incus = new FakeIncusClient();
        var transport = new IncusAutomationTransport(incus,
                (source, access) -> "/host/workspace/job-17");
        var writable = request(AutomationTransport.MountAccess.READ_WRITE);

        transport.mount(writable);

        assertEquals(Map.of(
                "type", "disk",
                "source", "/host/workspace/job-17",
                "path", "/home/agentuser/work"), incus.lastAdded);
        assertEquals(AutomationTransport.MountState.CONFLICTING,
                transport.inspectMount(request(AutomationTransport.MountAccess.READ_ONLY)));
        assertThrows(AutomationException.class,
                () -> transport.mount(request(AutomationTransport.MountAccess.READ_ONLY)));
        assertEquals(1, incus.addCount);
    }

    @Test
    void inspectionUsesUnexpandedOwnDevicesAndDistinguishesMalformed() {
        var incus = new FakeIncusClient();
        var transport = new IncusAutomationTransport(incus,
                (source, access) -> "/host/workspace/job-17");
        var request = request(AutomationTransport.MountAccess.READ_ONLY);
        var inherited = incus.metadata.putObject("expanded_devices").putObject("workspace");
        inherited.put("type", "disk");
        inherited.put("source", "/host/workspace/job-17");
        inherited.put("path", "/home/agentuser/work");
        inherited.put("readonly", "true");

        assertEquals(AutomationTransport.MountState.ABSENT, transport.inspectMount(request),
                "profile-expanded devices must not be treated as instance-owned");

        incus.metadata.withObject("devices").put("workspace", "broken");
        assertEquals(AutomationTransport.MountState.MALFORMED, transport.inspectMount(request));

        var conflicting = incus.metadata.withObject("devices").putObject("workspace");
        conflicting.put("type", "disk");
        conflicting.put("source", "/host/workspace/job-17");
        conflicting.put("path", "/home/agentuser/work");
        conflicting.put("readonly", "true");
        conflicting.put("shift", "true");
        assertEquals(AutomationTransport.MountState.CONFLICTING,
                transport.inspectMount(request), "extra fields are not an exact match");
    }

    @Test
    void unmountPassesEveryExpectedFieldToExactRemoval() {
        var incus = new FakeIncusClient();
        var transport = new IncusAutomationTransport(incus,
                (source, access) -> "/host/workspace/job-17");
        var request = request(AutomationTransport.MountAccess.READ_ONLY);
        transport.mount(request);

        transport.unmount(request);

        assertEquals(Map.of(
                "type", "disk",
                "source", "/host/workspace/job-17",
                "path", "/home/agentuser/work",
                "readonly", "true"), incus.lastRemovalExpectation);
        assertEquals(1, incus.exactRemoveCount);
        assertEquals(0, incus.nameOnlyRemoveCount);
        assertEquals(AutomationTransport.MountState.ABSENT, transport.inspectMount(request));
    }

    @Test
    void startReattachesDeclaredHostResourcesBeforeStarting() {
        var incus = new FakeIncusClient();
        incus.allowHostResourceDevices = true;
        incus.hostResourcesJson = HostResourceSetup.serialize(List.of(
                new ImageDef.HostResource(
                        tempDir.toString(), "/home/agentuser/.common", "readonly")));
        var readyTool = new ToolDef();
        readyTool.setName("app-services");
        readyTool.setReady("pg_isready -q");
        incus.buildSourceJson = new BuildSource(
                Map.of(), Map.of("app-services", readyTool), Map.of(), Map.of()).toJson();
        var transport = new IncusAutomationTransport(incus,
                (source, access) -> "/host/references/common");

        var output = new ByteArrayOutputStream();
        var original = System.out;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            transport.start("worker-1");
            transport.awaitReady("worker-1");
        } finally {
            System.setOut(original);
        }

        assertEquals("", output.toString(StandardCharsets.UTF_8));
        assertTrue(incus.started);
        assertTrue(incus.waitedUntilReady);
        assertArrayEquals(
                new String[]{"sh", "-c", "pg_isready -q"},
                incus.lastReadinessCommand);
        assertEquals(tempDir.toString(), incus.lastAdded.get("source"));
        assertEquals("/home/agentuser/.common", incus.lastAdded.get("path"));
        assertEquals("true", incus.lastAdded.get("readonly"));
    }

    @Test
    void automationReadinessFailsWithOnlyTheTrustedToolName() {
        var incus = new FakeIncusClient();
        var readyTool = new ToolDef();
        readyTool.setName("app-services");
        readyTool.setReady("secret-command --token hidden");
        incus.buildSourceJson = new BuildSource(
                Map.of(), Map.of("app-services", readyTool), Map.of(), Map.of()).toJson();
        incus.toolReady = false;
        var transport = new IncusAutomationTransport(incus,
                (source, access) -> "/host/references/common");

        var failure = assertThrows(AutomationException.class,
                () -> transport.awaitReady("worker-1"));

        assertEquals("tool_not_ready", failure.code());
        assertTrue(failure.getMessage().contains("app-services"));
        assertFalse(failure.getMessage().contains("secret-command"));
        assertFalse(failure.getMessage().contains("hidden"));
    }

    @Test
    void refusesToAttachAnEntireDeclaredExport() {
        var incus = new FakeIncusClient();
        for (var root : java.util.List.of(
                "/host/runtime", "/host/workspace", "/host/references/git")) {
            var transport = new IncusAutomationTransport(incus, (source, access) -> root);
            var error = assertThrows(AutomationException.class,
                    () -> transport.inspectMount(request(AutomationTransport.MountAccess.READ_ONLY)));
            assertEquals("source_not_exported", error.code());
        }
    }

    @Test
    void translationAndBackendFailuresDoNotEchoPathsOrBackendDetail() {
        var incus = new FakeIncusClient();
        var translationFailure = new IncusAutomationTransport(incus,
                (source, access) -> { throw new IllegalStateException(source + ": secret"); });
        var translated = assertThrows(AutomationException.class,
                () -> translationFailure.inspectMount(request(AutomationTransport.MountAccess.READ_ONLY)));
        assertEquals("source_not_exported", translated.code());
        assertFalse(translated.getMessage().contains("job-17"));
        assertFalse(translated.getMessage().contains("secret"));

        incus.failAdd = true;
        var backendFailure = new IncusAutomationTransport(incus,
                (source, access) -> "/host/workspace/job-17");
        var backend = assertThrows(AutomationException.class,
                () -> backendFailure.mount(request(AutomationTransport.MountAccess.READ_ONLY)));
        assertEquals("backend_error", backend.code());
        assertFalse(backend.getMessage().contains("file contents"));
        assertFalse(backend.getMessage().contains("job-17"));
    }

    private static AutomationTransport.MountRequest request(AutomationTransport.MountAccess access) {
        return new AutomationTransport.MountRequest(
                "worker-1", "allocation-17", "workspace", "/srv/workspaces/job-17",
                "/home/agentuser/work", access);
    }

    private static final class FakeIncusClient extends IncusClient {
        private final ObjectNode metadata = JSON.createObjectNode();
        private Map<String, String> lastAdded;
        private Map<String, String> lastRemovalExpectation;
        private int addCount;
        private int nameOnlyAddCount;
        private int exactRemoveCount;
        private int nameOnlyRemoveCount;
        private boolean failAdd;
        private boolean allowHostResourceDevices;
        private boolean started;
        private boolean waitedUntilReady;
        private String hostResourcesJson = "";
        private String buildSourceJson = "";
        private boolean toolReady = true;
        private String[] lastReadinessCommand;

        private FakeIncusClient() {
            metadata.put("name", "worker-1");
            metadata.put("status", "Running");
            metadata.put("type", "container");
            metadata.putObject("config");
            metadata.putObject("devices");
        }

        @Override
        public Optional<com.fasterxml.jackson.databind.JsonNode> findInstanceMetadata(String name) {
            return Optional.of(metadata.deepCopy());
        }

        @Override
        public void deviceAdd(String container, String deviceName, String type, String... props) {
            nameOnlyAddCount++;
            if (!allowHostResourceDevices) {
                throw new AssertionError("automation must not overwrite a device by name");
            }
            var fields = new LinkedHashMap<String, String>();
            fields.put("type", type);
            for (var prop : props) {
                int separator = prop.indexOf('=');
                fields.put(prop.substring(0, separator), prop.substring(separator + 1));
            }
            lastAdded = Map.copyOf(fields);
        }

        @Override
        public void deviceAddIfAbsent(
                String container, String automationKey, String deviceName,
                String type, String... props) {
            if (failAdd) throw new IllegalStateException("backend leaked file contents");
            if (metadata.withObject("devices").has(deviceName)) {
                throw new IllegalStateException("device appeared before addition");
            }
            addCount++;
            var fields = new LinkedHashMap<String, String>();
            fields.put("type", type);
            for (var prop : props) {
                int separator = prop.indexOf('=');
                fields.put(prop.substring(0, separator), prop.substring(separator + 1));
            }
            lastAdded = Map.copyOf(fields);
            var device = metadata.withObject("devices").putObject(deviceName);
            fields.forEach(device::put);
        }

        @Override
        public void deviceRemove(String container, String deviceName) {
            nameOnlyRemoveCount++;
            if (!allowHostResourceDevices) {
                throw new AssertionError("automation must not use name-only device removal");
            }
        }

        @Override
        public String configGet(String name, String key) {
            if (Metadata.HOST_RESOURCES.equals(key)) return hostResourcesJson;
            if (Metadata.BUILD_SOURCE.equals(key)) return buildSourceJson;
            return "";
        }

        @Override
        public boolean pollUntilReady(String name, int timeoutSeconds, String... command) {
            lastReadinessCommand = command;
            return toolReady;
        }

        @Override
        public boolean isVm(String name) {
            return false;
        }

        @Override
        public void start(String name) {
            started = true;
        }

        @Override
        public void waitForReady(String name) {
            waitedUntilReady = true;
        }

        @Override
        public void deviceRemoveExact(
                String container, String automationKey, String deviceName,
                Map<String, String> expected) {
            exactRemoveCount++;
            lastRemovalExpectation = Map.copyOf(expected);
            var state = IncusAutomationTransport.classifyOwnDevice(metadata, deviceName, expected);
            if (state != AutomationTransport.MountState.EXACT) {
                throw new IllegalStateException("device changed before removal");
            }
            metadata.withObject("devices").remove(deviceName);
        }
    }
}
