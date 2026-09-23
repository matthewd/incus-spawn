package dev.incusspawn.incus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incusspawn.automation.AutomationTransport;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class IncusApiAutomationExecTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void controlledExecPostsExactArgvAndForwardsRawStdin() throws Exception {
        var transport = new FakeTransport(false, true);
        var api = new IncusApi(transport);
        var input = new byte[]{0, '\n', (byte) 0xff, 'x'};
        var argv = List.of("printf", "", "  ", "-n", "line\narg");

        var result = api.execControlled("worker-1", argv, 1000, 1000,
                "/home/agentuser", Map.of("HOME", "/home/agentuser", "EMPTY", ""),
                new ByteArrayInputStream(input), new ByteArrayOutputStream(),
                new ByteArrayOutputStream(), 0, new AutomationTransport.Cancellation());

        assertEquals(AutomationTransport.Termination.EXITED, result.termination());
        assertEquals(23, result.exitCode());
        assertEquals(argv, JSON.convertValue(transport.execBody.path("command"),
                JSON.getTypeFactory().constructCollectionType(List.class, String.class)));
        assertEquals("", transport.execBody.path("environment").path("EMPTY").asText());
        assertArrayEquals(input, transport.stdin.toByteArray());
        assertEquals(0, transport.deleteCount);
    }

    @Test
    void finiteTimeoutAttemptsOperationDeleteAndReturnsStructuredReason() {
        var transport = new FakeTransport(false, false);
        var api = new IncusApi(transport);

        var result = api.execControlled("worker-1", List.of("sleep", "100"), 1000, 1000,
                "/home/agentuser", Map.of("HOME", "/home/agentuser"),
                new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(),
                new ByteArrayOutputStream(), 1, new AutomationTransport.Cancellation());

        assertEquals(AutomationTransport.Termination.TIMEOUT, result.termination());
        assertTrue(result.operationCancellation().attempted());
        assertTrue(result.operationCancellation().accepted());
        assertEquals(1, transport.deleteCount);
    }

    @Test
    void externalCancellationDeletesOperationThroughFakeTransport() throws Exception {
        var transport = new FakeTransport(true, false);
        var api = new IncusApi(transport);
        var cancellation = new AutomationTransport.Cancellation();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> api.execControlled("worker-1",
                    List.of("sleep", "100"), 1000, 1000, "/home/agentuser",
                    Map.of("HOME", "/home/agentuser"),
                    new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(),
                    new ByteArrayOutputStream(), 0, cancellation));

            assertTrue(transport.waitStarted.await(2, TimeUnit.SECONDS));
            cancellation.request();
            var result = future.get(2, TimeUnit.SECONDS);

            assertEquals(AutomationTransport.Termination.CANCELLED, result.termination());
            assertTrue(result.operationCancellation().attempted());
            assertTrue(result.operationCancellation().accepted());
            assertEquals(1, transport.deleteCount);
        }
    }

    @Test
    void timeoutReportsRejectedOperationDeleteHonestly() {
        var transport = new FakeTransport(false, false);
        transport.deleteAccepted = false;
        var api = new IncusApi(transport);

        var result = api.execControlled("worker-1", List.of("sleep", "100"), 1000, 1000,
                "/home/agentuser", Map.of("HOME", "/home/agentuser"),
                new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(),
                new ByteArrayOutputStream(), 1, new AutomationTransport.Cancellation());

        assertEquals(AutomationTransport.Termination.TIMEOUT, result.termination());
        assertTrue(result.operationCancellation().attempted());
        assertFalse(result.operationCancellation().accepted());
        assertEquals("operation cannot be cancelled", result.operationCancellation().detail());
    }

    private static final class FakeTransport implements IncusTransport {
        private final boolean blockWaitUntilDelete;
        private final boolean complete;
        private final CountDownLatch waitStarted = new CountDownLatch(1);
        private final CountDownLatch deleted = new CountDownLatch(1);
        private final ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        private volatile JsonNode execBody;
        private volatile int deleteCount;
        private volatile boolean deleteAccepted = true;

        private FakeTransport(boolean blockWaitUntilDelete, boolean complete) {
            this.blockWaitUntilDelete = blockWaitUntilDelete;
            this.complete = complete;
        }

        @Override
        public RawResponse request(String method, String path, String contentType,
                                   Map<String, String> extraHeaders, byte[] body) throws IOException {
            if ("POST".equals(method) && path.endsWith("/exec")) {
                execBody = JSON.readTree(body);
                return json(202, """
                        {"type":"async","metadata":{"id":"op-1","metadata":{"fds":{
                          "0":"stdin-secret","1":"stdout-secret","2":"stderr-secret",
                          "control":"control-secret"}}}}
                        """);
            }
            if ("GET".equals(method) && path.contains("/wait?")) {
                waitStarted.countDown();
                if (blockWaitUntilDelete) {
                    try {
                        if (!deleted.await(2, TimeUnit.SECONDS)) {
                            throw new IOException("operation DELETE was not attempted");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException(e);
                    }
                }
                if (complete) {
                    return json(200, """
                            {"metadata":{"status":"Success","metadata":{"return":23}}}
                            """);
                }
                return json(200, """
                        {"metadata":{"status":"Running","metadata":{}}}
                        """);
            }
            if ("DELETE".equals(method) && path.equals("/1.0/operations/op-1")) {
                deleteCount++;
                deleted.countDown();
                return deleteAccepted
                        ? json(200, "{\"type\":\"sync\",\"metadata\":{}}")
                        : json(409, "{\"type\":\"error\",\"error\":\"operation cannot be cancelled\"}");
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
            boolean input = wsPath.contains("secret=stdin-secret");
            return new WsConnection() {
                @Override
                public byte[] readPayload() {
                    return null;
                }

                @Override
                public void sendData(byte[] data, int offset, int length) {
                    if (input) stdin.write(data, offset, length);
                }

                @Override
                public void sendPing() {
                }

                @Override
                public void sendClose() {
                }

                @Override
                public void close() {
                }
            };
        }

        private static RawResponse json(int status, String body) {
            return new RawResponse(status, body.getBytes(StandardCharsets.UTF_8));
        }
    }
}
