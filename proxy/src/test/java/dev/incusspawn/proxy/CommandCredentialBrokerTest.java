package dev.incusspawn.proxy;

import io.vertx.core.Future;
import io.vertx.core.Vertx;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandCredentialBrokerTest {

    private static final String CREDENTIAL_ONE = "token_abcdefghijklmnop";
    private static final String CREDENTIAL_TWO = "token_qrstuvwxyz123456";
    private static Vertx vertx;

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stopVertx() throws Exception {
        if (vertx != null) {
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void commandUsesExactAbsoluteArgvAndClearedMinimalEnvironment() {
        var rule = rule();
        var processBuilder = CommandCredentialBroker.credentialProcessBuilder(rule);

        assertEquals(rule.argv(), processBuilder.command());
        assertEquals(Set.of("HOME", "USER", "LOGNAME", "TMPDIR", "PATH"),
                processBuilder.environment().keySet());
        assertEquals(System.getProperty("user.home"), processBuilder.environment().get("HOME"));
        assertEquals(System.getProperty("user.name"), processBuilder.environment().get("USER"));
        assertEquals(System.getProperty("user.name"), processBuilder.environment().get("LOGNAME"));
        assertEquals(CommandCredentialBroker.SAFE_PATH, processBuilder.environment().get("PATH"));
        assertEquals("/tmp", processBuilder.environment().get("TMPDIR"));
        assertEquals(ProcessBuilder.Redirect.DISCARD, processBuilder.redirectError());
    }

    @Test
    void validatesOneConfiguredCredentialLineAndRejectsMalformedOutput() {
        var rule = rule();
        assertEquals(CREDENTIAL_ONE, CommandCredentialBroker.validateOutput(
                rule, ("  " + CREDENTIAL_ONE + "\n").getBytes(StandardCharsets.UTF_8)));

        assertThrows(IllegalArgumentException.class,
                () -> CommandCredentialBroker.validateOutput(rule, new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> CommandCredentialBroker.validateOutput(rule, "wrong-shape\n"
                        .getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                () -> CommandCredentialBroker.validateOutput(rule,
                        (CREDENTIAL_ONE + "\n" + CREDENTIAL_TWO + "\n")
                                .getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                () -> CommandCredentialBroker.validateOutput(rule,
                        (CREDENTIAL_ONE + "\n\n").getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                () -> CommandCredentialBroker.validateOutput(rule,
                        new byte[]{(byte) 0xc3, (byte) 0x28}));
        assertThrows(IllegalArgumentException.class,
                () -> CommandCredentialBroker.validateOutput(rule,
                        new byte[rule.maxOutputBytes() + 1]));
    }

    @Test
    void cachesForConfiguredTtlThenRefreshes() throws Exception {
        var now = new AtomicLong(1_000);
        var calls = new AtomicInteger();
        var rule = rule();
        var broker = new CommandCredentialBroker(vertx, rule, now::get,
                () -> calls.incrementAndGet() == 1 ? CREDENTIAL_ONE : CREDENTIAL_TWO);

        var first = await(broker.acquire());
        var cached = await(broker.acquire());
        assertSame(first, cached);
        assertEquals(1, calls.get());

        now.addAndGet(TimeUnit.SECONDS.toMillis(rule.cacheTtlSeconds()) - 1);
        assertSame(first, await(broker.acquire()));
        assertEquals(1, calls.get());

        now.incrementAndGet();
        var refreshed = await(broker.acquire());
        assertEquals(CREDENTIAL_TWO, refreshed.credential());
        assertTrue(refreshed.generation() > first.generation());
        assertEquals(2, calls.get());
    }

    @Test
    void concurrentColdCallersShareOneCommand() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var broker = new CommandCredentialBroker(vertx, rule(), System::currentTimeMillis, () -> {
            calls.incrementAndGet();
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return CREDENTIAL_ONE;
        });

        var futures = new ArrayList<Future<CommandCredentialBroker.Lease>>();
        for (int i = 0; i < 32; i++) futures.add(broker.acquire());
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertEquals(1, calls.get());
        release.countDown();

        var leases = new ArrayList<CommandCredentialBroker.Lease>();
        for (var future : futures) leases.add(await(future));
        assertEquals(1, calls.get());
        assertTrue(leases.stream().allMatch(lease -> lease.generation()
                == leases.getFirst().generation()));
    }

    @Test
    void failuresUseConfiguredNegativeCache() {
        var now = new AtomicLong(1_000);
        var calls = new AtomicInteger();
        var rule = rule();
        var broker = new CommandCredentialBroker(vertx, rule, now::get, () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("safe broker failure");
        });

        assertThrows(Exception.class, () -> await(broker.acquire()));
        assertThrows(Exception.class, () -> await(broker.acquire()));
        assertEquals(1, calls.get());

        now.addAndGet(TimeUnit.SECONDS.toMillis(rule.failureTtlSeconds()));
        assertThrows(Exception.class, () -> await(broker.acquire()));
        assertEquals(2, calls.get());
    }

    @Test
    void staleLeaseCannotInvalidateNewGeneration() throws Exception {
        var now = new AtomicLong(1_000);
        var calls = new AtomicInteger();
        var rule = rule();
        var broker = new CommandCredentialBroker(vertx, rule, now::get,
                () -> calls.incrementAndGet() == 1 ? CREDENTIAL_ONE : CREDENTIAL_TWO);

        var oldLease = await(broker.acquire());
        now.addAndGet(TimeUnit.SECONDS.toMillis(rule.cacheTtlSeconds()));
        var newLease = await(broker.acquire());

        assertFalse(broker.invalidate(oldLease));
        assertSame(newLease, broker.cachedLease());
        assertTrue(broker.invalidate(newLease));
        assertNull(broker.cachedLease());
    }

    static CommandCredentialConfig.Rule rule() {
        var regex = "token_[A-Za-z0-9]{16}";
        return new CommandCredentialConfig.Rule(
                "example-gateway",
                "credential.example.test",
                List.of("/usr/local/bin/credential-helper", "print"),
                regex,
                Pattern.compile(regex),
                new CommandCredentialConfig.Carriers(
                        new CommandCredentialConfig.BearerCarrier("container-placeholder"),
                        new CommandCredentialConfig.HeaderCarrier("x-api-key", "container-placeholder")),
                15,
                8192,
                16 * 1024 * 1024,
                300,
                5,
                "Example credential gateway",
                "Repair host credential access");
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
}
