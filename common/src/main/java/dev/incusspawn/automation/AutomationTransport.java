package dev.incusspawn.automation;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Backend boundary for the non-interactive automation API.
 *
 * <p>The service owns lifecycle and ownership semantics; implementations only expose the
 * transport operations needed to realize them. This keeps the protocol independent of aesh and
 * makes lifecycle reconciliation testable without an Incus daemon.</p>
 */
public interface AutomationTransport {

    record Instance(
            String name,
            String status,
            String instanceType,
            String isxType,
            String automationKey,
            String automationTemplate) {
    }

    enum MountAccess {
        READ_ONLY("read-only"),
        READ_WRITE("read-write");

        private final String wireValue;

        MountAccess(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    record MountRequest(
            String instance,
            String key,
            String device,
            String source,
            String target,
            MountAccess access) {
    }

    /** Classification of an instance-owned device against one complete mount request. */
    enum MountState {
        ABSENT,
        EXACT,
        CONFLICTING,
        MALFORMED
    }

    enum Termination {
        EXITED,
        TIMEOUT,
        CANCELLED
    }

    record CancellationAttempt(boolean attempted, boolean accepted, String detail) {
        public static CancellationAttempt notAttempted() {
            return new CancellationAttempt(false, false, "");
        }
    }

    record ExecOutcome(int exitCode, Termination termination,
                       CancellationAttempt operationCancellation) {
        public static ExecOutcome exited(int exitCode) {
            return new ExecOutcome(exitCode, Termination.EXITED,
                    CancellationAttempt.notAttempted());
        }
    }

    record ExecRequest(
            String instance,
            List<String> argv,
            Map<String, String> environment,
            int uid,
            int gid,
            String cwd,
            long timeoutMillis) {
    }

    /**
     * Process-external cancellation signal. A transport may register one best-effort action while
     * an operation is active; requesting cancellation runs it immediately and remains observable
     * by the operation's wait loop.
     */
    final class Cancellation {
        private final AtomicBoolean requested = new AtomicBoolean();
        private final AtomicReference<Runnable> action = new AtomicReference<>();

        public boolean isRequested() {
            return requested.get();
        }

        public void request() {
            requested.set(true);
            runRegisteredAction();
        }

        public void register(Runnable cancellationAction) {
            if (!action.compareAndSet(null, cancellationAction)) {
                throw new IllegalStateException("A cancellation action is already registered");
            }
            if (requested.get()) runRegisteredAction();
        }

        public void unregister(Runnable cancellationAction) {
            action.compareAndSet(cancellationAction, null);
        }

        private void runRegisteredAction() {
            var registered = action.getAndSet(null);
            if (registered == null) return;
            try {
                registered.run();
            } catch (RuntimeException ignored) {
                // Cancellation is best effort; the transport reports its own structured outcome.
            }
        }
    }

    Optional<Instance> find(String name);

    List<Instance> findByAutomationKey(String key);

    /** Create a stopped CoW copy and include every supplied config key in the copy request. */
    void copyStopped(String template, String name, Map<String, String> atomicConfig);

    void start(String name);

    /** Wait until every readiness contract carried by the instance template succeeds. */
    void awaitReady(String name);

    void stop(String name);

    void delete(String name);

    /** Inspect only the instance's unexpanded own device with the requested name. */
    MountState inspectMount(MountRequest request);

    /** Attach only if the instance-owned device is still absent. */
    void mount(MountRequest request);

    /** Remove only if every instance-owned device field still exactly matches the request. */
    void unmount(MountRequest request);

    ExecOutcome exec(ExecRequest request, InputStream stdin, OutputStream stdout,
                     OutputStream stderr, Cancellation cancellation);
}
