package dev.incusspawn.proxy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandCredentialConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void absentFileAndExplicitEmptyRulesDisableCommandCredentials() throws Exception {
        assertTrue(CommandCredentialConfig.loadStrict(tempDir.resolve("missing.yaml"),
                Set.of(), List.of()).rules().isEmpty());

        var file = writeSecure("rules: []\n");
        assertTrue(CommandCredentialConfig.loadStrict(file, Set.of(), List.of())
                .rules().isEmpty());
    }

    @Test
    void loadsACompleteStrictRule() throws Exception {
        var file = writeSecure(validRule());

        var config = CommandCredentialConfig.loadStrict(file, Set.of(), List.of());
        var rule = config.rules().getFirst();

        assertEquals("example-gateway", rule.id());
        assertEquals("credential.example.test", rule.host());
        assertEquals(List.of("/usr/local/bin/credential-helper", "print"), rule.argv());
        assertEquals("token_[A-Za-z0-9]{16,}", rule.validationRegex());
        assertEquals("container-placeholder", rule.carriers().bearer().placeholder());
        assertEquals("x-api-key", rule.carriers().header().name());
        assertEquals(15, rule.timeoutSeconds());
        assertEquals(8192, rule.maxOutputBytes());
        assertEquals(16 * 1024 * 1024, rule.bodyLimitBytes());
        assertEquals(300, rule.cacheTtlSeconds());
        assertEquals(5, rule.failureTtlSeconds());
        assertEquals("Example credential gateway", rule.label());
        assertEquals("Repair host credential access", rule.remediation());
    }

    @Test
    void presentEmptyMalformedUnknownAndDuplicateYamlFailClosed() throws Exception {
        var empty = writeSecure("");
        assertConfigError(empty, "empty");

        var missingRules = writeSecure("{}\n");
        assertConfigError(missingRules, "rules");

        var malformed = writeSecure("rules:\n  - id\n");
        assertThrows(IllegalStateException.class,
                () -> CommandCredentialConfig.loadStrict(malformed, Set.of(), List.of()));

        var unknown = writeSecure("rules: []\nunknown: true\n");
        assertConfigError(unknown, "unknown");

        var duplicate = writeSecure("rules: []\nrules: []\n");
        assertConfigError(duplicate, "duplicate");
    }

    @Test
    void wrongScalarTypesFailClosedInsteadOfBeingCoerced() throws Exception {
        assertInvalidRule(
                validRule().replace("timeout-seconds: 15", "timeout-seconds: '15'"),
                "cannot coerce");
        assertInvalidRule(
                validRule().replace("label: Example credential gateway", "label: 123"),
                "cannot coerce");
    }

    @Test
    void insecurePermissionsFailClosed() throws Exception {
        var file = writeSecure("rules: []\n");
        Files.setPosixFilePermissions(file, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ));

        assertConfigError(file, "permissions");
    }

    @Test
    void rejectsUnsafeRuleFieldsAndRouteCollisions() throws Exception {
        assertInvalidRule(validRule().replace("example-gateway", "UPPER"), "id");
        assertInvalidRule(validRule().replace("credential.example.test", "Credential.example.test"), "host");
        assertInvalidRule(validRule().replace("/usr/local/bin/credential-helper", "credential-helper"), "absolute");
        assertInvalidRule(validRule().replace("token_[A-Za-z0-9]{16,}", "["), "validation-regex");
        assertInvalidRule(validRule().replace("container-placeholder", "token_abcdefghijklmnop"), "placeholder");
        assertInvalidRule(validRule().replace("timeout-seconds: 15", "timeout-seconds: 0"), "timeout-seconds");
        assertInvalidRule(validRule().replace("max-output-bytes: 8192", "max-output-bytes: 65537"), "max-output-bytes");
        assertInvalidRule(validRule().replace("body-limit-bytes: 16777216", "body-limit-bytes: 0"), "body-limit-bytes");

        var exact = writeSecure(validRule());
        assertConfigError(exact, Set.of("credential.example.test"), List.of(), "collides");
        assertConfigError(exact, Set.of(), List.of(".example.test"), "collides");
    }

    @Test
    void rejectsDuplicateIdsHostsAndRulesWithoutACarrier() throws Exception {
        assertInvalidRule("rules:\n" + validRule().substring("rules:\n".length())
                + validRule().substring("rules:\n".length()), "duplicate id");
        assertInvalidRule(validRule().replace("    carriers:\n"
                + "      bearer:\n"
                + "        placeholder: container-placeholder\n"
                + "      header:\n"
                + "        name: x-api-key\n"
                + "        placeholder: container-placeholder\n", "    carriers: {}\n"), "carrier");
    }

    private void assertInvalidRule(String yaml, String expected) throws Exception {
        assertConfigError(writeSecure(yaml), expected);
    }

    private void assertConfigError(Path file, String expected) {
        assertConfigError(file, Set.of(), List.of(), expected);
    }

    private void assertConfigError(Path file, Set<String> exact, List<String> wildcards,
                                   String expected) {
        var error = assertThrows(IllegalStateException.class,
                () -> CommandCredentialConfig.loadStrict(file, exact, wildcards));
        assertTrue(error.getMessage().toLowerCase().contains(expected.toLowerCase()),
                error.getMessage());
    }

    private Path writeSecure(String content) throws Exception {
        var file = tempDir.resolve("command-credentials-" + System.nanoTime() + ".yaml");
        Files.writeString(file, content);
        Files.setPosixFilePermissions(file, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE));
        return file;
    }

    private static String validRule() {
        return """
                rules:
                  - id: example-gateway
                    host: credential.example.test
                    argv:
                      - /usr/local/bin/credential-helper
                      - print
                    validation-regex: 'token_[A-Za-z0-9]{16,}'
                    carriers:
                      bearer:
                        placeholder: container-placeholder
                      header:
                        name: x-api-key
                        placeholder: container-placeholder
                    timeout-seconds: 15
                    max-output-bytes: 8192
                    body-limit-bytes: 16777216
                    cache-ttl-seconds: 300
                    failure-ttl-seconds: 5
                    label: Example credential gateway
                    remediation: Repair host credential access
                """;
    }
}
