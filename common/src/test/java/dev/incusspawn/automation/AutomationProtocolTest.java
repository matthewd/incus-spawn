package dev.incusspawn.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AutomationProtocolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void parsesExactArgvWithoutShellJoiningOrNormalization() {
        var argv = AutomationProtocol.parseArgv(
                "[\"command\",\"\",\"   \",\"-dash\",\"line\\narg\",\"quote'\\\"\"]");

        assertEquals(List.of("command", "", "   ", "-dash", "line\narg", "quote'\""), argv);
    }

    @Test
    void rejectsNonStringArgvAndEnvironmentValues() {
        var argvFailure = assertThrows(AutomationService.AutomationException.class,
                () -> AutomationProtocol.parseArgv("[\"ok\",1]"));
        assertEquals("invalid_argv", argvFailure.code());

        var envFailure = assertThrows(AutomationService.AutomationException.class,
                () -> AutomationProtocol.parseEnvironment("{\"COUNT\":1}"));
        assertEquals("invalid_environment", envFailure.code());
    }

    @Test
    void lifecycleResponseIsOneLineVersionedAndWhitelisted() throws Exception {
        var result = new AutomationService.LifecycleResult("inspect", false, true,
                new AutomationService.InstanceView("worker-1", "running", "container", "tpl-bb"));

        var line = AutomationProtocol.lifecycleLine(result);
        var json = JSON.readTree(line);

        assertFalse(line.contains("\n"));
        assertEquals("isx-automation", json.path("protocol").asText());
        assertEquals(1, json.path("version").asInt());
        assertEquals("inspect", json.path("operation").asText());
        assertEquals("worker-1", json.path("instance").path("name").asText());
        assertEquals("tpl-bb", json.path("instance").path("template").asText());
        assertEquals(4, json.path("instance").size(),
                "inspect must not expose arbitrary Incus config or ownership keys");
    }

    @Test
    void mountLifecycleResponseDoesNotExposeAttachmentPaths() throws Exception {
        var result = new AutomationService.LifecycleResult("mount", true, true,
                new AutomationService.InstanceView("worker-1", "running", "container", "tpl-bb"));

        var line = AutomationProtocol.lifecycleLine(result);
        var json = JSON.readTree(line);

        assertFalse(line.contains("/srv/workspaces"));
        assertFalse(line.contains("/home/agentuser/work"));
        assertFalse(json.path("instance").has("source"));
        assertFalse(json.path("instance").has("target"));
        assertEquals(4, json.path("instance").size());
    }

    @Test
    void rawBackendErrorsDoNotExposeBackendDetailOrFileContent() throws Exception {
        var line = AutomationProtocol.lifecycleErrorLine("mount",
                new IllegalStateException("secret file contents from /srv/workspaces/job-17"));
        var json = JSON.readTree(line);

        assertEquals("backend_error", json.path("error").path("code").asText());
        assertEquals("Automation backend operation failed",
                json.path("error").path("message").asText());
        assertFalse(line.contains("secret"));
        assertFalse(line.contains("/srv/workspaces"));
    }

    @Test
    void framesBinaryStreamsAsBase64AndEndsWithResult() throws Exception {
        var output = new ByteArrayOutputStream();
        var frames = new AutomationProtocol.FrameWriter(output);
        var stdout = new byte[]{0, '\n', (byte) 0xff};
        var stderr = "problem\n".getBytes(StandardCharsets.UTF_8);

        frames.stdout().write(stdout);
        frames.stderr().write(stderr);
        frames.result(AutomationTransport.ExecOutcome.exited(7));

        var lines = output.toString(StandardCharsets.UTF_8).lines().toList();
        assertEquals(3, lines.size());
        var outFrame = JSON.readTree(lines.get(0));
        var errFrame = JSON.readTree(lines.get(1));
        var result = JSON.readTree(lines.get(2));
        assertEquals("stdout", outFrame.path("type").asText());
        assertArrayEquals(stdout, Base64.getDecoder().decode(outFrame.path("data").asText()));
        assertEquals("stderr", errFrame.path("type").asText());
        assertArrayEquals(stderr, Base64.getDecoder().decode(errFrame.path("data").asText()));
        assertEquals("result", result.path("type").asText());
        assertEquals(7, result.path("exit_code").asInt());
    }

    @Test
    void concurrentFrameWritesRemainWholeNdjsonLines() throws Exception {
        var output = new ByteArrayOutputStream();
        var frames = new AutomationProtocol.FrameWriter(output);
        var start = new java.util.concurrent.CountDownLatch(1);
        var stdout = Thread.ofPlatform().start(() -> writeFrames(frames.stdout(), start, 'o'));
        var stderr = Thread.ofPlatform().start(() -> writeFrames(frames.stderr(), start, 'e'));

        start.countDown();
        stdout.join();
        stderr.join();
        frames.result(AutomationTransport.ExecOutcome.exited(0));

        var lines = output.toString(StandardCharsets.UTF_8).lines().toList();
        assertEquals(201, lines.size());
        for (var line : lines) {
            var frame = JSON.readTree(line);
            assertEquals(1, frame.path("version").asInt());
            assertTrue(frame.path("type").asText().matches("stdout|stderr|result"));
        }
    }

    @Test
    void timeoutFrameReportsOperationDeletionWithoutClaimingTermination() throws Exception {
        var output = new ByteArrayOutputStream();
        var frames = new AutomationProtocol.FrameWriter(output);
        frames.result(new AutomationTransport.ExecOutcome(-1,
                AutomationTransport.Termination.TIMEOUT,
                new AutomationTransport.CancellationAttempt(true, true, "")));

        var frame = JSON.readTree(output.toString(StandardCharsets.UTF_8).strip());
        assertEquals("error", frame.path("type").asText());
        assertEquals("timeout", frame.path("termination").asText());
        assertTrue(frame.path("operation_delete").path("attempted").asBoolean());
        assertTrue(frame.path("operation_delete").path("accepted").asBoolean());
        assertFalse(frame.has("process_tree_killed"));
    }

    private static void writeFrames(java.io.OutputStream stream,
                                    java.util.concurrent.CountDownLatch start, char value) {
        try {
            start.await();
            for (int i = 0; i < 100; i++) stream.write(value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
