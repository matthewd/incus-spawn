package dev.incusspawn.proxy;

import dev.incusspawn.Environment;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/** Lazily obtains and briefly caches a credential from a configured host command. */
final class CommandCredentialBroker {

    static final String SAFE_PATH = "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin";

    record Lease(String credential, long generation, long expiresAt) {
        boolean isValid(long now) {
            return now < expiresAt;
        }
    }

    private record State(Lease lease, Future<Lease> inflight,
                         Throwable failure, long retryAt) {
        static State resolved(Lease lease) {
            return new State(lease, null, null, 0);
        }

        static State resolving(Future<Lease> inflight) {
            return new State(null, inflight, null, 0);
        }

        static State failed(Throwable failure, long retryAt) {
            return new State(null, null, failure, retryAt);
        }

        boolean isResolving() {
            return inflight != null;
        }
    }

    private final Vertx vertx;
    private final CommandCredentialConfig.Rule rule;
    private final LongSupplier clock;
    private final Callable<String> credentialCommand;
    private final AtomicLong generation = new AtomicLong();
    private final AtomicReference<State> state = new AtomicReference<>();

    CommandCredentialBroker(Vertx vertx, CommandCredentialConfig.Rule rule) {
        this(vertx, rule, System::currentTimeMillis,
                () -> executeCredentialCommand(rule));
    }

    CommandCredentialBroker(Vertx vertx, CommandCredentialConfig.Rule rule,
                            LongSupplier clock, Callable<String> credentialCommand) {
        this.vertx = vertx;
        this.rule = rule;
        this.clock = clock;
        this.credentialCommand = credentialCommand;
    }

    Future<Lease> acquire() {
        while (true) {
            var existing = state.get();
            var now = clock.getAsLong();
            if (existing != null && existing.lease() != null
                    && existing.lease().isValid(now)) {
                return Future.succeededFuture(existing.lease());
            }
            if (existing != null && existing.isResolving()) {
                return existing.inflight();
            }
            if (existing != null && existing.failure() != null && now < existing.retryAt()) {
                return Future.failedFuture(existing.failure());
            }

            var promise = Promise.<Lease>promise();
            var resolving = State.resolving(promise.future());
            if (!state.compareAndSet(existing, resolving)) continue;

            vertx.<String>executeBlocking(credentialCommand, false).onComplete(result -> {
                if (result.succeeded()) {
                    var lease = new Lease(result.result(), generation.incrementAndGet(),
                            clock.getAsLong() + TimeUnit.SECONDS.toMillis(rule.cacheTtlSeconds()));
                    state.compareAndSet(resolving, State.resolved(lease));
                    promise.complete(lease);
                } else {
                    state.compareAndSet(resolving, State.failed(result.cause(),
                            clock.getAsLong() + TimeUnit.SECONDS.toMillis(rule.failureTtlSeconds())));
                    promise.fail(result.cause());
                }
            });
            return promise.future();
        }
    }

    /** Invalidate only if the rejected lease is still current. */
    boolean invalidate(Lease rejected) {
        while (true) {
            var existing = state.get();
            if (existing == null || existing.lease() == null
                    || existing.lease().generation() != rejected.generation()) {
                return false;
            }
            if (state.compareAndSet(existing, null)) return true;
        }
    }

    Lease cachedLease() {
        var existing = state.get();
        return existing != null && existing.lease() != null ? existing.lease() : null;
    }

    static ProcessBuilder credentialProcessBuilder(CommandCredentialConfig.Rule rule) {
        var processBuilder = new ProcessBuilder(rule.argv())
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        var environment = processBuilder.environment();
        environment.clear();
        environment.putAll(runtimeEnvironment());
        return processBuilder;
    }

    static Map<String, String> runtimeEnvironment() {
        var home = Environment.home().toString();
        var user = System.getProperty("user.name");
        return Map.of(
                "HOME", home,
                "USER", user,
                "LOGNAME", user,
                "TMPDIR", "/tmp",
                "PATH", SAFE_PATH);
    }

    static String executeCredentialCommand(CommandCredentialConfig.Rule rule) {
        Process process = null;
        Thread stdoutReader = null;
        try {
            process = credentialProcessBuilder(rule).start();
            var runningProcess = process;
            var stdout = new AtomicReference<byte[]>();
            var stdoutFailure = new AtomicReference<IOException>();
            stdoutReader = Thread.ofPlatform().daemon().name("credential-command-stdout").start(() -> {
                try (var input = runningProcess.getInputStream()) {
                    stdout.set(input.readNBytes(rule.maxOutputBytes() + 1));
                } catch (IOException e) {
                    stdoutFailure.set(e);
                }
            });

            var timeout = rule.timeoutSeconds();
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
            var exited = process.waitFor(timeout, TimeUnit.SECONDS);
            var remainingNanos = deadline - System.nanoTime();
            if (exited && remainingNanos > 0) {
                stdoutReader.join(Math.max(1,
                        TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
            }
            if (!exited || stdoutReader.isAlive()) {
                process.destroyForcibly();
                process.getInputStream().close();
                stdoutReader.join(1_000);
                throw new IllegalStateException("credential command timed out");
            }
            if (stdoutFailure.get() != null) {
                throw new IllegalStateException("credential command output could not be read");
            }

            var output = stdout.get();
            if (output == null) output = new byte[0];
            if (output.length > rule.maxOutputBytes()) {
                throw new IllegalStateException("credential command output was too large");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("credential command failed (exit "
                        + process.exitValue() + ")");
            }
            return validateOutput(rule, output);
        } catch (IOException e) {
            throw new IllegalStateException("credential command could not be started", e);
        } catch (InterruptedException e) {
            if (process != null) process.destroyForcibly();
            if (stdoutReader != null) stdoutReader.interrupt();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("credential command was interrupted", e);
        }
    }

    static String validateOutput(CommandCredentialConfig.Rule rule, byte[] output) {
        if (output.length > rule.maxOutputBytes()) {
            throw new IllegalArgumentException("credential command output was too large");
        }

        final String decoded;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(output)).toString();
        } catch (Exception e) {
            throw new IllegalArgumentException("credential command returned malformed output");
        }

        var line = decoded;
        if (line.endsWith("\n")) {
            line = line.substring(0, line.length() - 1);
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
        }
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("credential command returned multiple lines");
        }
        var credential = line.strip();
        if (credential.isEmpty()) {
            throw new IllegalArgumentException("credential command returned no credential");
        }
        if (credential.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            throw new IllegalArgumentException("credential command returned invalid output");
        }
        if (!rule.validationPattern().matcher(credential).matches()) {
            throw new IllegalArgumentException("credential command returned invalid output");
        }
        return credential;
    }
}
