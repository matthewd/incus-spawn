package dev.incusspawn.pool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** Version-one one-line JSON protocol for inspecting, sealing, and materializing worker pools. */
public final class PoolProtocol {

    public static final String PROTOCOL = "isx-pool";
    public static final int VERSION = 1;
    private static final ObjectMapper JSON = new ObjectMapper();

    private PoolProtocol() {}

    public static String inspectLine(PoolStore.InspectResult result) {
        var response = envelope("inspect", true);
        response.put("seed_pool", result.seedPool());
        response.put("running", result.running());
        response.put("sealed", result.sealed());
        response.put("generation", result.generation());
        response.put("generation_identity", result.identity());
        return json(response);
    }

    public static String sealLine(PoolStore.SealResult result) {
        var response = envelope("seal", true);
        response.put("changed", result.changed());
        response.put("seed_pool", result.seedPool());
        response.put("generation", result.generation());
        response.put("generation_identity", result.identity());
        return json(response);
    }

    public static String materializeLine(PoolStore.MaterializeResult result) {
        var response = envelope("materialize", true);
        response.put("changed", result.changed());
        response.put("pool", result.name());
        response.put("identity", result.identity());
        response.put("seed_pool", result.seedPool());
        response.put("generation", result.generation());
        response.put("generation_identity", result.generationIdentity());
        return json(response);
    }

    public static String errorLine(String operation, Throwable failure) {
        var response = envelope(operation, false);
        var error = new LinkedHashMap<String, Object>();
        if (failure instanceof PoolStore.PoolException known) {
            error.put("code", known.code());
            error.put("message", known.getMessage());
        } else {
            error.put("code", "backend_error");
            error.put("message", failure.getMessage() == null || failure.getMessage().isBlank()
                    ? "Pool management operation failed" : failure.getMessage());
        }
        response.put("error", error);
        return json(response);
    }

    private static LinkedHashMap<String, Object> envelope(String operation, boolean ok) {
        var response = new LinkedHashMap<String, Object>();
        response.put("protocol", PROTOCOL);
        response.put("version", VERSION);
        response.put("ok", ok);
        response.put("operation", operation);
        return response;
    }

    private static String json(Map<String, Object> value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not encode pool response", e);
        }
    }
}
