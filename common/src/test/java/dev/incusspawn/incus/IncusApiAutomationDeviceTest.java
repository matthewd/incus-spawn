package dev.incusspawn.incus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IncusApiAutomationDeviceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> READ_ONLY_DEVICE = Map.of(
            "type", "disk",
            "source", "/host/workspace/job-17",
            "path", "/home/agentuser/work",
            "readonly", "true");

    @Test
    void absentAddSendsOnlyTheExactDiskFields() {
        var transport = new DeviceTransport("{}");
        var api = new IncusApi(transport);

        var response = api.addDeviceIfAbsent(
                "worker-1", "allocation-17", "workspace", READ_ONLY_DEVICE);

        assertTrue(response.isSuccess());
        assertEquals(0, transport.patchCount);
        assertEquals(1, transport.putCount);
        assertEquals("\"revision-17\"", transport.mutationHeaders.get("If-Match"));
        assertEquals(JSON.valueToTree(READ_ONLY_DEVICE),
                transport.mutationBody.path("devices").path("workspace"));
        assertEquals("x86_64", transport.mutationBody.path("architecture").asText());
        assertEquals("allocation-17", transport.mutationBody.path("config")
                .path("user.incus-spawn.automation-key").asText());
    }

    @Test
    void addFailsClosedWhenOwnDeviceAppears() {
        var transport = new DeviceTransport("""
                {"workspace":{"type":"disk","source":"/other","path":"/mnt/other"}}
                """);
        var api = new IncusApi(transport);

        assertThrows(IncusException.class,
                () -> api.addDeviceIfAbsent(
                        "worker-1", "allocation-17", "workspace", READ_ONLY_DEVICE));
        assertEquals(0, transport.patchCount);
        assertEquals(0, transport.putCount);
    }

    @Test
    void mutationSideGetRechecksOwnershipAndRunningOrStoppedState() {
        for (var transport : java.util.List.of(
                new DeviceTransport("{}", "other-key", "Running"),
                new DeviceTransport("{}", "allocation-17", "Error"))) {
            var api = new IncusApi(transport);

            assertThrows(IncusException.class,
                    () -> api.addDeviceIfAbsent(
                            "worker-1", "allocation-17", "workspace", READ_ONLY_DEVICE));
            assertEquals(0, transport.patchCount);
            assertEquals(0, transport.putCount);
        }
    }

    @Test
    void exactRemoveChecksAllFieldsInTheRemovalReadModifyWrite() {
        var transport = new DeviceTransport("""
                {
                  "keep":{"type":"disk","source":"/keep","path":"/mnt/keep"},
                  "workspace":{"type":"disk","source":"/host/workspace/job-17",
                    "path":"/home/agentuser/work","readonly":"true"}
                }
                """);
        var api = new IncusApi(transport);

        var response = api.removeDeviceExact(
                "worker-1", "allocation-17", "workspace", READ_ONLY_DEVICE);

        assertTrue(response.isSuccess());
        assertEquals(1, transport.putCount);
        assertEquals("\"revision-17\"", transport.mutationHeaders.get("If-Match"));
        assertTrue(transport.mutationBody.path("devices").has("keep"));
        assertFalse(transport.mutationBody.path("devices").has("workspace"));
    }

    @Test
    void exactRemoveRejectsAccessOrExtraFieldConflictsWithoutPutting() {
        for (var devices : java.util.List.of(
                """
                {"workspace":{"type":"disk","source":"/host/workspace/job-17",
                  "path":"/home/agentuser/work"}}
                """,
                """
                {"workspace":{"type":"disk","source":"/host/workspace/job-17",
                  "path":"/home/agentuser/work","readonly":"true","shift":"true"}}
                """)) {
            var transport = new DeviceTransport(devices);
            var api = new IncusApi(transport);

            assertThrows(IncusException.class,
                    () -> api.removeDeviceExact(
                            "worker-1", "allocation-17", "workspace", READ_ONLY_DEVICE));
            assertEquals(0, transport.putCount);
        }
    }

    private static final class DeviceTransport implements IncusTransport {
        private final String devices;
        private final String automationKey;
        private final String status;
        private int patchCount;
        private int putCount;
        private JsonNode mutationBody;
        private Map<String, String> mutationHeaders;

        private DeviceTransport(String devices) {
            this(devices, "allocation-17", "Running");
        }

        private DeviceTransport(String devices, String automationKey, String status) {
            this.devices = devices;
            this.automationKey = automationKey;
            this.status = status;
        }

        @Override
        public RawResponse request(String method, String path, String contentType,
                                   Map<String, String> extraHeaders, byte[] body) throws IOException {
            if ("GET".equals(method) && path.equals("/1.0/instances/worker-1")) {
                return json(200, """
                        {"type":"sync","metadata":{
                          "architecture":"x86_64",
                          "config":{"user.incus-spawn.automation-key":"%s"},
                          "status":"%s",
                          "description":"",
                          "devices":%s,
                          "ephemeral":false,
                          "profiles":["default"],
                          "stateful":false
                        }}
                        """.formatted(automationKey, status, devices),
                        Map.of("etag", "\"revision-17\""));
            }
            if ("PATCH".equals(method) && path.equals("/1.0/instances/worker-1")) {
                patchCount++;
                mutationBody = JSON.readTree(body);
                mutationHeaders = Map.copyOf(extraHeaders);
                return json(200, "{\"type\":\"sync\",\"metadata\":{}}");
            }
            if ("PUT".equals(method) && path.equals("/1.0/instances/worker-1")) {
                putCount++;
                mutationBody = JSON.readTree(body);
                mutationHeaders = Map.copyOf(extraHeaders);
                return json(200, "{\"type\":\"sync\",\"metadata\":{}}");
            }
            throw new IOException("Unexpected request: " + method + " " + path);
        }

        @Override
        public RawResponse request(String method, String path, String contentType,
                                   Map<String, String> extraHeaders, Path bodyFile) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WsConnection openWebSocket(String wsPath) {
            throw new UnsupportedOperationException();
        }

        private static RawResponse json(int status, String body) {
            return json(status, body, Map.of());
        }

        private static RawResponse json(
                int status, String body, Map<String, String> headers) {
            return new RawResponse(status, body.getBytes(StandardCharsets.UTF_8), headers);
        }
    }
}
