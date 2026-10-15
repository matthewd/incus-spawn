package dev.incusspawn.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.incusspawn.Environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Static worker-pool declarations stored in {@code ~/.config/incus-spawn/worker-pools.yaml}: a
 * top-level map from pool name to its {@link WorkerPoolConfig}.
 *
 * <p>Pool layout lives apart from {@code config.yaml} so that tools provisioning pools never
 * need to read or rewrite the file holding proxy credentials.
 */
public final class WorkerPoolsFile {

    public static final String FILE_NAME = "worker-pools.yaml";

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private WorkerPoolsFile() {}

    public static Path defaultPath() {
        return Environment.configDir().resolve(FILE_NAME);
    }

    /**
     * Load and validate every declared pool, preserving declaration order. A missing or empty
     * file declares no pools.
     */
    public static Map<String, WorkerPoolConfig> loadStrict(Path file) {
        if (!Files.exists(file)) return Map.of();
        LinkedHashMap<String, WorkerPoolConfig> pools;
        try {
            var tree = YAML.readTree(file.toFile());
            if (tree == null || tree.isMissingNode() || tree.isNull()) return Map.of();
            if (!tree.isObject()) {
                throw new IllegalStateException(
                        FILE_NAME + " must map worker pool names to their definitions");
            }
            pools = YAML.convertValue(tree, YAML.getTypeFactory().constructMapType(
                    LinkedHashMap.class, String.class, WorkerPoolConfig.class));
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException(YamlErrors.friendly(FILE_NAME, e), e);
        }
        for (var entry : pools.entrySet()) {
            if (entry.getValue() == null) {
                throw new IllegalStateException("invalid " + FILE_NAME + ": worker pool '"
                        + entry.getKey() + "' definition is required");
            }
            try {
                entry.getValue().validateAndFreeze(entry.getKey(), Environment.home());
            } catch (IllegalStateException e) {
                throw new IllegalStateException(
                        "invalid " + FILE_NAME + ": " + e.getMessage(), e);
            }
        }
        return Collections.unmodifiableMap(pools);
    }
}
