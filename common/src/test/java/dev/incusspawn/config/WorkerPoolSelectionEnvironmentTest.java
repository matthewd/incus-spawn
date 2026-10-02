package dev.incusspawn.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerPoolSelectionEnvironmentTest {

    @TempDir Path home;

    @Test
    void unsetSelectorsRemainLegacy() {
        var selection = WorkerPoolSelection.selectEnvironment(
                null, null, null, home.resolve("config.yaml"), home.resolve("state"), home);

        assertTrue(selection.isLegacy());
    }

    @Test
    void materializedNameAndIdentityAreAnIndivisiblePair() {
        var missingIdentity = assertThrows(IllegalStateException.class,
                () -> WorkerPoolSelection.selectEnvironment(
                        null, "job-one", null, home.resolve("config.yaml"),
                        home.resolve("state"), home));
        assertTrue(missingIdentity.getMessage().contains("must be set together"));

        var missingName = assertThrows(IllegalStateException.class,
                () -> WorkerPoolSelection.selectEnvironment(
                        null, null, "a".repeat(64), home.resolve("config.yaml"),
                        home.resolve("state"), home));
        assertTrue(missingName.getMessage().contains("must be set together"));
    }

    @Test
    void maintenanceRequiresAnExactMaterializedSelection() {
        var missingSelection = assertThrows(IllegalStateException.class,
                () -> WorkerPoolSelection.selectEnvironment(
                        null, null, null, "1", home.resolve("config.yaml"),
                        home.resolve("state"), home));
        assertTrue(missingSelection.getMessage().contains("requires a materialized"));

        var invalidValue = assertThrows(IllegalStateException.class,
                () -> WorkerPoolSelection.selectEnvironment(
                        null, "job-one", "a".repeat(64), "true",
                        home.resolve("config.yaml"), home.resolve("state"), home));
        assertTrue(invalidValue.getMessage().contains("must be exactly '1'"));
    }

    @Test
    void staticAndMaterializedSelectorsCannotBeCombined() {
        var failure = assertThrows(IllegalStateException.class,
                () -> WorkerPoolSelection.selectEnvironment(
                        "seed", "job-one", "a".repeat(64), home.resolve("config.yaml"),
                        home.resolve("state"), home));

        assertTrue(failure.getMessage().contains("cannot be combined"));
    }
}
