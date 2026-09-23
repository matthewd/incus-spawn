package dev.incusspawn.automation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incusspawn.automation.AutomationService.AutomationException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stable JSON/NDJSON wire representation for {@link AutomationService}. */
public final class AutomationProtocol {

    public static final String PROTOCOL = "isx-automation";
    public static final int VERSION = 1;

    private static final ObjectMapper JSON = new ObjectMapper();

    private AutomationProtocol() {
    }

    public static List<String> parseArgv(String json) {
        var node = parse(json, "argv", "invalid_argv");
        if (!node.isArray() || node.isEmpty()) {
            throw new AutomationException("invalid_argv",
                    "argv-json must be a non-empty JSON array of strings");
        }
        var argv = new ArrayList<String>(node.size());
        for (var value : node) {
            if (!value.isTextual()) {
                throw new AutomationException("invalid_argv",
                        "argv-json must contain strings only");
            }
            argv.add(value.textValue());
        }
        return List.copyOf(argv);
    }

    public static Map<String, String> parseEnvironment(String json) {
        if (json == null) return Map.of();
        var node = parse(json, "environment", "invalid_environment");
        if (!node.isObject()) {
            throw new AutomationException("invalid_environment",
                    "env-json must be a JSON object with string values");
        }
        var environment = new LinkedHashMap<String, String>();
        node.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual()) {
                throw new AutomationException("invalid_environment",
                        "env-json value for '" + entry.getKey() + "' must be a string");
            }
            environment.put(entry.getKey(), entry.getValue().textValue());
        });
        return Map.copyOf(environment);
    }

    private static JsonNode parse(String json, String label, String code) {
        if (json == null) {
            throw new AutomationException(code, label + " JSON is required");
        }
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw new AutomationException(code, label + " JSON is invalid: "
                    + e.getOriginalMessage());
        }
    }

    public static String lifecycleLine(AutomationService.LifecycleResult result) {
        var response = envelope();
        response.put("ok", true);
        response.put("operation", result.operation());
        response.put("changed", result.changed());
        response.put("exists", result.exists());
        if (result.instance() != null) {
            var instance = new LinkedHashMap<String, Object>();
            instance.put("name", result.instance().name());
            instance.put("state", result.instance().state());
            instance.put("instance_type", result.instance().instanceType());
            instance.put("template", result.instance().template());
            response.put("instance", instance);
        }
        return json(response);
    }

    public static String lifecycleErrorLine(String operation, Throwable failure) {
        var response = envelope();
        response.put("ok", false);
        response.put("operation", operation);
        response.put("error", errorObject(failure));
        return json(response);
    }

    private static LinkedHashMap<String, Object> envelope() {
        var response = new LinkedHashMap<String, Object>();
        response.put("protocol", PROTOCOL);
        response.put("version", VERSION);
        return response;
    }

    private static Map<String, Object> errorObject(Throwable failure) {
        var error = new LinkedHashMap<String, Object>();
        var automation = failure instanceof AutomationException known ? known : null;
        error.put("code", automation != null ? automation.code() : "backend_error");
        var message = automation != null ? automation.getMessage() : null;
        error.put("message", message == null || message.isBlank()
                ? "Automation backend operation failed" : message);
        return error;
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not encode automation response", e);
        }
    }

    /**
     * Thread-safe NDJSON writer shared by concurrent stdout and stderr WebSocket readers.
     */
    public static final class FrameWriter {
        private final OutputStream destination;
        private final OutputStream stdout;
        private final OutputStream stderr;

        public FrameWriter(OutputStream destination) {
            this.destination = destination;
            this.stdout = new FrameOutputStream("stdout");
            this.stderr = new FrameOutputStream("stderr");
        }

        public OutputStream stdout() {
            return stdout;
        }

        public OutputStream stderr() {
            return stderr;
        }

        public synchronized void result(AutomationTransport.ExecOutcome outcome) {
            if (outcome.termination() == AutomationTransport.Termination.EXITED) {
                var frame = envelope();
                frame.put("type", "result");
                frame.put("exit_code", outcome.exitCode());
                frame.put("termination", "exited");
                writeLine(frame);
                return;
            }

            var frame = envelope();
            frame.put("type", "error");
            var code = outcome.termination() == AutomationTransport.Termination.TIMEOUT
                    ? "timeout" : "cancelled";
            frame.put("code", code);
            frame.put("message", outcome.termination() == AutomationTransport.Termination.TIMEOUT
                    ? "Exec exceeded its timeout" : "Exec was cancelled");
            frame.put("termination", code);
            frame.put("operation_delete", cancellationObject(outcome.operationCancellation()));
            writeLine(frame);
        }

        public synchronized void error(Throwable failure) {
            var frame = envelope();
            frame.put("type", "error");
            frame.putAll(errorObject(failure));
            writeLine(frame);
        }

        private static Map<String, Object> cancellationObject(
                AutomationTransport.CancellationAttempt attempt) {
            var value = new LinkedHashMap<String, Object>();
            value.put("attempted", attempt.attempted());
            value.put("accepted", attempt.accepted());
            if (attempt.detail() != null && !attempt.detail().isBlank()) {
                value.put("detail", attempt.detail());
            }
            return value;
        }

        private synchronized void data(String stream, byte[] bytes, int offset, int length) {
            if (length == 0) return;
            var frame = envelope();
            frame.put("type", stream);
            frame.put("data", Base64.getEncoder().encodeToString(
                    java.util.Arrays.copyOfRange(bytes, offset, offset + length)));
            writeLine(frame);
        }

        private void writeLine(Map<String, Object> frame) {
            try {
                destination.write(json(frame).getBytes(StandardCharsets.UTF_8));
                destination.write('\n');
                destination.flush();
            } catch (IOException e) {
                throw new AutomationException("output_error",
                        "Could not write automation protocol output", e);
            }
        }

        private final class FrameOutputStream extends OutputStream {
            private final String stream;

            private FrameOutputStream(String stream) {
                this.stream = stream;
            }

            @Override
            public void write(int value) {
                var one = new byte[]{(byte) value};
                data(stream, one, 0, 1);
            }

            @Override
            public void write(byte[] bytes, int offset, int length) {
                data(stream, bytes, offset, length);
            }
        }
    }
}
