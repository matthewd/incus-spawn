package dev.incusspawn.proxy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiTrafficLogTest {

    @TempDir
    Path tempDir;

    @Test
    void redactsAuthenticationHeadersFromEveryDump() throws Exception {
        var secret = "token_test_credential_must_never_reach_logs";
        var log = new ApiTrafficLog(tempDir);

        log.logExchange("POST /v1 HTTP/1.1\nAuthorization: Bearer " + secret
                        + "\nx-api-key: " + secret + "\n",
                null,
                "POST /v1 HTTP/1.1\nX-API-Key: " + secret + "\n",
                null,
                "HTTP/1.1 200 OK\nX-Access-Token: " + secret + "\n",
                null);

        try (var files = Files.list(tempDir)) {
            var content = Files.readString(files.findFirst().orElseThrow());
            assertFalse(content.contains(secret), content);
            assertTrue(content.contains("Authorization: [REDACTED]"), content);
            assertTrue(content.contains("x-api-key: [REDACTED]"), content);
            assertTrue(content.contains("X-Access-Token: [REDACTED]"), content);
        }
    }
}
