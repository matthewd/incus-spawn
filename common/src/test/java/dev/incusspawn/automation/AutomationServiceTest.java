package dev.incusspawn.automation;

import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AutomationServiceTest {

    @Test
    void createCopiesStoppedTemplateWithAtomicOwnershipAndIsIdempotent() {
        var transport = new FakeTransport();
        transport.put(instance("tpl-bb", "Stopped", Metadata.TYPE_BASE, "", ""));
        var service = new AutomationService(transport);

        var created = service.create("worker-1", "tpl-bb", "allocation-17");

        assertTrue(created.changed());
        assertEquals("stopped", created.instance().state());
        assertEquals(1, transport.copyCount);
        assertEquals("allocation-17", transport.lastAtomicConfig.get(Metadata.AUTOMATION_KEY));
        assertEquals("tpl-bb", transport.lastAtomicConfig.get(Metadata.AUTOMATION_TEMPLATE));
        assertEquals(Metadata.TYPE_CLONE, transport.lastAtomicConfig.get(Metadata.TYPE));
        assertEquals("tpl-bb", transport.lastAtomicConfig.get(Metadata.PARENT));

        var repeated = service.create("worker-1", "tpl-bb", "allocation-17");
        assertFalse(repeated.changed());
        assertEquals(1, transport.copyCount);
    }

    @Test
    void createRejectsNameCollisionAndKeyReuse() {
        var transport = new FakeTransport();
        transport.put(instance("tpl-bb", "Stopped", Metadata.TYPE_BASE, "", ""));
        transport.put(instance("worker-1", "Stopped", Metadata.TYPE_CLONE,
                "somebody-else", "tpl-bb"));
        var service = new AutomationService(transport);

        var collision = assertThrows(AutomationService.AutomationException.class,
                () -> service.create("worker-1", "tpl-bb", "allocation-17"));
        assertEquals("ownership_mismatch", collision.code());

        transport.instances.remove("worker-1");
        transport.put(instance("old-worker", "Stopped", Metadata.TYPE_CLONE,
                "allocation-17", "tpl-bb"));
        var reused = assertThrows(AutomationService.AutomationException.class,
                () -> service.create("worker-1", "tpl-bb", "allocation-17"));
        assertEquals("ambiguous_ownership", reused.code());
    }

    @Test
    void createReconcilesLostCopyResponseByAtomicMetadata() {
        var transport = new FakeTransport();
        transport.put(instance("tpl-bb", "Stopped", Metadata.TYPE_BASE, "", ""));
        transport.failAfterCopy = true;
        var service = new AutomationService(transport);

        var result = service.create("worker-1", "tpl-bb", "allocation-17");

        assertFalse(result.changed(), "lost response is reconciled, not replayed");
        assertEquals("worker-1", result.instance().name());
        assertEquals(1, transport.copyCount);
    }

    @Test
    void ownershipDigestAuthorizesWithoutExposingTheAutomationKey() throws Exception {
        var transport = new FakeTransport();
        transport.put(instance("worker-1", "Running", Metadata.TYPE_CLONE,
                "allocation-17", "tpl-bb"));
        var service = new AutomationService(transport);
        var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("allocation-17".getBytes(StandardCharsets.UTF_8)));

        assertEquals("worker-1",
                service.inspectByOwnershipDigest("worker-1", digest).instance().name());

        var mismatch = assertThrows(AutomationService.AutomationException.class,
                () -> service.inspectByOwnershipDigest("worker-1", "0".repeat(64)));
        assertEquals("ownership_mismatch", mismatch.code());
        var malformed = assertThrows(AutomationService.AutomationException.class,
                () -> service.inspectByOwnershipDigest("worker-1", "not-a-digest"));
        assertEquals("invalid_ownership_digest", malformed.code());
    }

    @Test
    void startAndStopAreOwnedAndIdempotent() {
        var transport = new FakeTransport();
        transport.put(instance("worker-1", "Stopped", Metadata.TYPE_CLONE,
                "allocation-17", "tpl-bb"));
        var service = new AutomationService(transport);

        assertTrue(service.start("worker-1", "allocation-17").changed());
        assertEquals(1, transport.startCount);
        assertFalse(service.start("worker-1", "allocation-17").changed());
        assertEquals(1, transport.startCount);
        assertEquals(2, transport.readyCount);

        assertTrue(service.stop("worker-1", "allocation-17").changed());
        assertEquals(1, transport.stopCount);
        assertFalse(service.stop("worker-1", "allocation-17").changed());
        assertEquals(1, transport.stopCount);

        var mismatch = assertThrows(AutomationService.AutomationException.class,
                () -> service.start("worker-1", "wrong-key"));
        assertEquals("ownership_mismatch", mismatch.code());
    }

    @Test
    void runningMachinesMustStillSatisfyReadiness() {
        var transport = new FakeTransport();
        transport.put(instance("worker-1", "Running", Metadata.TYPE_CLONE,
                "allocation-17", "tpl-bb"));
        transport.failReadiness = true;
        var service = new AutomationService(transport);

        var failure = assertThrows(AutomationService.AutomationException.class,
                () -> service.start("worker-1", "allocation-17"));
        assertEquals("tool_not_ready", failure.code());
        assertEquals(1, transport.readyCount);
        assertEquals(0, transport.startCount);
    }

    @Test
    void keyOnlyDeleteReconcilesUncertainAllocationAndIsIdempotent() {
        var transport = new FakeTransport();
        transport.put(instance("unknown-generated-name", "Running", Metadata.TYPE_CLONE,
                "allocation-17", "tpl-bb"));
        var service = new AutomationService(transport);

        var deleted = service.delete(null, "allocation-17", "tpl-bb");
        assertTrue(deleted.changed());
        assertFalse(deleted.exists());
        assertEquals("unknown-generated-name", transport.deletedName);

        var repeated = service.delete(null, "allocation-17", "tpl-bb");
        assertFalse(repeated.changed());
        assertFalse(repeated.exists());
    }

    @Test
    void keyOnlyDeleteRequiresTheExpectedTemplate() {
        var transport = new FakeTransport();
        transport.put(instance("worker-1", "Stopped", Metadata.TYPE_CLONE,
                "allocation-17", "tpl-default"));
        var service = new AutomationService(transport);

        var missing = assertThrows(AutomationService.AutomationException.class,
                () -> service.delete(null, "allocation-17"));
        assertEquals("invalid_template", missing.code());
        var mismatch = assertThrows(AutomationService.AutomationException.class,
                () -> service.delete(null, "allocation-17", "tpl-app"));
        assertEquals("ownership_mismatch", mismatch.code());
        assertNull(transport.deletedName);

        assertTrue(service.delete(null, "allocation-17", "tpl-default").changed());
        assertEquals("worker-1", transport.deletedName);
    }

    @Test
    void keyOnlyDeleteFailsClosedWhenOwnershipIsAmbiguous() {
        var transport = new FakeTransport();
        transport.put(instance("worker-1", "Stopped", Metadata.TYPE_CLONE,
                "duplicate", "tpl-bb"));
        transport.put(instance("worker-2", "Stopped", Metadata.TYPE_CLONE,
                "duplicate", "tpl-bb"));

        var failure = assertThrows(AutomationService.AutomationException.class,
                () -> new AutomationService(transport).delete(null, "duplicate", "tpl-bb"));
        assertEquals("ambiguous_ownership", failure.code());
        assertNull(transport.deletedName);
    }

    @Test
    void mountAndUnmountAreOwnedExactAndIdempotentInRunningOrStoppedStates() {
        var transport = new FakeTransport();
        transport.put(instance("worker-1", "Stopped", Metadata.TYPE_CLONE,
                "allocation-17", "tpl-bb"));
        var service = new AutomationService(transport);

        var mounted = service.mount("worker-1", "allocation-17", "bb-workspace",
                "/srv/workspaces/job-17", "/home/agentuser/work", "read-only");

        assertTrue(mounted.changed());
        assertEquals(1, transport.mountCount);
        assertEquals(AutomationTransport.MountAccess.READ_ONLY,
                transport.lastMountRequest.access());
        assertFalse(service.mount("worker-1", "allocation-17", "bb-workspace",
                "/srv/workspaces/job-17", "/home/agentuser/work", "read-only").changed());
        assertEquals(1, transport.mountCount);

        transport.updateStatus("worker-1", "Running");
        assertTrue(service.unmount("worker-1", "allocation-17", "bb-workspace",
                "/srv/workspaces/job-17", "/home/agentuser/work", "read-only").changed());
        assertEquals(1, transport.unmountCount);
        assertFalse(service.unmount("worker-1", "allocation-17", "bb-workspace",
                "/srv/workspaces/job-17", "/home/agentuser/work", "read-only").changed());
        assertEquals(1, transport.unmountCount);
    }

    @Test
    void mountAndUnmountFailClosedOnEveryExactFieldAndMalformedDevices() {
        var transport = new FakeTransport();
        transport.put(instance("worker-1", "Running", Metadata.TYPE_CLONE,
                "allocation-17", "tpl-bb"));
        var service = new AutomationService(transport);
        service.mount("worker-1", "allocation-17", "bb-workspace",
                "/srv/workspaces/job-17", "/home/agentuser/work", "read-only");

        for (var conflicting : List.of(
                new String[]{"/srv/workspaces/job-18", "/home/agentuser/work", "read-only"},
                new String[]{"/srv/workspaces/job-17", "/home/agentuser/other", "read-only"},
                new String[]{"/srv/workspaces/job-17", "/home/agentuser/work", "read-write"})) {
            var failure = assertThrows(AutomationService.AutomationException.class,
                    () -> service.unmount("worker-1", "allocation-17", "bb-workspace",
                            conflicting[0], conflicting[1], conflicting[2]));
            assertEquals("device_conflict", failure.code());
        }
        assertEquals(0, transport.unmountCount,
                "unmount must not remove a device selected only by name");

        transport.malformedDevices.add("broken-device");
        var malformed = assertThrows(AutomationService.AutomationException.class,
                () -> service.mount("worker-1", "allocation-17", "broken-device",
                        "/srv/workspaces/job-17", "/home/agentuser/work", "read-only"));
        assertEquals("malformed_device", malformed.code());
        assertEquals(1, transport.mountCount);
    }

    @Test
    void concurrentMutationConflictRetainsStableConflictSemantics() {
        var transport = new FakeTransport();
        transport.put(instance("worker-1", "Running", Metadata.TYPE_CLONE,
                "allocation-17", "tpl-bb"));
        transport.conflictWhileMounting = true;
        var service = new AutomationService(transport);

        var conflict = assertThrows(AutomationService.AutomationException.class,
                () -> service.mount("worker-1", "allocation-17", "workspace",
                        "/srv/work", "/home/agentuser/work", "read-only"));

        assertEquals("device_conflict", conflict.code());
    }

    @Test
    void mountValidationAndOwnershipRunBeforeDeviceMutation() {
        var transport = new FakeTransport();
        transport.put(instance("worker-1", "Running", Metadata.TYPE_CLONE,
                "allocation-17", "tpl-bb"));
        var service = new AutomationService(transport);

        assertMountFailure(service, "invalid_device", "Bad_Name",
                "/srv/work", "/home/agentuser/work", "read-only", "allocation-17");
        assertMountFailure(service, "invalid_source", "workspace",
                "relative/work", "/home/agentuser/work", "read-only", "allocation-17");
        assertMountFailure(service, "invalid_source", "workspace",
                "/srv/../secret", "/home/agentuser/work", "read-only", "allocation-17");
        assertMountFailure(service, "invalid_source", "workspace",
                "/srv/work\nsecret", "/home/agentuser/work", "read-only", "allocation-17");
        assertMountFailure(service, "invalid_source", "workspace",
                "/srv/work\0secret", "/home/agentuser/work", "read-only", "allocation-17");
        assertMountFailure(service, "invalid_target", "workspace",
                "/srv/work", "/", "read-only", "allocation-17");
        assertMountFailure(service, "invalid_target", "workspace",
                "/srv/work", "/home/../root", "read-only", "allocation-17");
        assertMountFailure(service, "invalid_access", "workspace",
                "/srv/work", "/home/agentuser/work", "writable", "allocation-17");
        assertMountFailure(service, "invalid_key", "workspace",
                "/srv/work", "/home/agentuser/work", "read-only", "allocation\n17");
        assertMountFailure(service, "ownership_mismatch", "workspace",
                "/srv/work", "/home/agentuser/work", "read-only", "other-key");
        assertEquals(0, transport.mountCount);
        assertEquals(0, transport.unmountCount);

        transport.updateStatus("worker-1", "Error");
        var state = assertThrows(AutomationService.AutomationException.class,
                () -> service.mount("worker-1", "allocation-17", "workspace",
                        "/srv/work", "/home/agentuser/work", "read-only"));
        assertEquals("invalid_state", state.code());
        assertEquals(0, transport.inspectMountCount,
                "transient/error instances must be rejected before device inspection");
    }

    private static void assertMountFailure(
            AutomationService service, String code, String device, String source,
            String target, String access, String key) {
        var failure = assertThrows(AutomationService.AutomationException.class,
                () -> service.mount("worker-1", key, device, source, target, access));
        assertEquals(code, failure.code());
    }

    @Test
    void execPreservesRawStdinAndAppliesDocumentedDefaults() {
        var transport = new FakeTransport();
        transport.put(instance("worker-1", "Running", Metadata.TYPE_CLONE,
                "allocation-17", "tpl-bb"));
        var service = new AutomationService(transport);
        var stdinBytes = new byte[]{0, 1, '\n', (byte) 0xff};

        var outcome = service.exec("worker-1", "allocation-17",
                List.of("printf", "", "  ", "-n", "line\narg"),
                Map.of("EMPTY", ""), null, null, null, 0,
                new ByteArrayInputStream(stdinBytes), new ByteArrayOutputStream(),
                new ByteArrayOutputStream(), new AutomationTransport.Cancellation());

        assertEquals(AutomationTransport.Termination.EXITED, outcome.termination());
        assertEquals(List.of("printf", "", "  ", "-n", "line\narg"),
                transport.execRequest.argv());
        assertEquals(1000, transport.execRequest.uid());
        assertEquals(1000, transport.execRequest.gid());
        assertEquals("/home/agentuser", transport.execRequest.cwd());
        assertEquals("/home/agentuser", transport.execRequest.environment().get("HOME"));
        assertEquals("", transport.execRequest.environment().get("EMPTY"));
        assertArrayEquals(stdinBytes, transport.stdin);
    }

    private static AutomationTransport.Instance instance(String name, String status,
                                                          String isxType, String key,
                                                          String template) {
        return new AutomationTransport.Instance(name, status, "container", isxType, key, template);
    }

    private static final class FakeTransport implements AutomationTransport {
        private final Map<String, Instance> instances = new LinkedHashMap<>();
        private int copyCount;
        private int startCount;
        private int readyCount;
        private int stopCount;
        private String deletedName;
        private Map<String, String> lastAtomicConfig;
        private boolean failAfterCopy;
        private boolean failReadiness;
        private final Map<String, MountRequest> mounts = new LinkedHashMap<>();
        private final java.util.Set<String> malformedDevices = new java.util.HashSet<>();
        private int inspectMountCount;
        private int mountCount;
        private int unmountCount;
        private MountRequest lastMountRequest;
        private boolean conflictWhileMounting;
        private ExecRequest execRequest;
        private byte[] stdin;

        void put(Instance instance) {
            instances.put(instance.name(), instance);
        }

        @Override
        public Optional<Instance> find(String name) {
            return Optional.ofNullable(instances.get(name));
        }

        @Override
        public List<Instance> findByAutomationKey(String key) {
            return instances.values().stream()
                    .filter(instance -> key.equals(instance.automationKey()))
                    .toList();
        }

        @Override
        public void copyStopped(String template, String name, Map<String, String> atomicConfig) {
            copyCount++;
            lastAtomicConfig = Map.copyOf(atomicConfig);
            instances.put(name, instance(name, "Stopped", atomicConfig.get(Metadata.TYPE),
                    atomicConfig.get(Metadata.AUTOMATION_KEY),
                    atomicConfig.get(Metadata.AUTOMATION_TEMPLATE)));
            if (failAfterCopy) throw new IllegalStateException("response lost");
        }

        @Override
        public void start(String name) {
            startCount++;
            updateStatus(name, "Running");
        }

        @Override
        public void awaitReady(String name) {
            readyCount++;
            if (failReadiness) {
                throw new AutomationService.AutomationException(
                        "tool_not_ready", "tool did not become ready");
            }
        }

        @Override
        public void stop(String name) {
            stopCount++;
            updateStatus(name, "Stopped");
        }

        @Override
        public void delete(String name) {
            deletedName = name;
            instances.remove(name);
        }

        @Override
        public MountState inspectMount(MountRequest request) {
            inspectMountCount++;
            if (malformedDevices.contains(request.device())) return MountState.MALFORMED;
            var existing = mounts.get(request.device());
            if (existing == null) return MountState.ABSENT;
            return existing.equals(request) ? MountState.EXACT : MountState.CONFLICTING;
        }

        @Override
        public void mount(MountRequest request) {
            mountCount++;
            lastMountRequest = request;
            if (conflictWhileMounting) {
                mounts.put(request.device(), new MountRequest(
                        request.instance(), request.key(), request.device(), request.source(),
                        request.target() + "-other", request.access()));
                throw new IllegalStateException("If-Match precondition failed");
            }
            mounts.put(request.device(), request);
        }

        @Override
        public void unmount(MountRequest request) {
            unmountCount++;
            if (!request.equals(mounts.get(request.device()))) {
                throw new IllegalStateException("attempted non-exact removal");
            }
            mounts.remove(request.device());
        }

        @Override
        public ExecOutcome exec(ExecRequest request, java.io.InputStream input,
                                java.io.OutputStream stdout, java.io.OutputStream stderr,
                                Cancellation cancellation) {
            execRequest = request;
            try {
                stdin = input.readAllBytes();
                stdout.write("out".getBytes(StandardCharsets.UTF_8));
                stderr.write("err".getBytes(StandardCharsets.UTF_8));
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
            return ExecOutcome.exited(0);
        }

        private void updateStatus(String name, String status) {
            var current = instances.get(name);
            instances.put(name, new Instance(current.name(), status, current.instanceType(),
                    current.isxType(), current.automationKey(), current.automationTemplate()));
        }
    }
}
