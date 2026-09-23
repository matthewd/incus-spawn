package dev.incusspawn.vm;

import dev.incusspawn.config.WorkerPoolConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VmHostExportsTest {

    @TempDir Path home;

    @Test
    void resolvesTypedExportsWithDeterministicTagsAndGuestPaths() throws Exception {
        var maven = Files.createDirectories(home.resolve("references/maven"));
        var sources = Files.createDirectories(home.resolve("references/sources"));
        var references = new LinkedHashMap<String, WorkerPoolConfig.ReadOnlyExport>();
        references.put("sources", new WorkerPoolConfig.ReadOnlyExport(sources.toString()));
        references.put("maven", new WorkerPoolConfig.ReadOnlyExport(maven.toString()));

        var plan = VmHostExports.create("compile", selected(
                "~/runtime", "~/workspace", references), home);

        assertEquals(home.resolve("runtime").toRealPath(), plan.runtime().hostPath());
        assertEquals(VmHostExports.RUNTIME_TAG, plan.runtime().mountTag());
        assertEquals(Path.of("/host/runtime"), plan.runtime().guestPath());
        assertTrue(plan.runtime().readOnly());
        assertEquals(home.resolve("workspace").toRealPath(), plan.workspace().hostPath());
        assertEquals(VmHostExports.WORKSPACE_TAG, plan.workspace().mountTag());
        assertEquals(Path.of("/host/workspace"), plan.workspace().guestPath());
        assertFalse(plan.workspace().readOnly());
        assertEquals(java.util.List.of("maven", "sources"), plan.referenceNames());
        assertEquals("isx-reference-maven", plan.exports().get(2).mountTag());
        assertEquals(Path.of("/host/references/maven"), plan.exports().get(2).guestPath());
        assertTrue(plan.exports().get(2).readOnly());
    }

    @Test
    void createsOnlyControlledRoots() {
        var runtime = home.resolve("new/runtime");
        var workspace = home.resolve("new/workspace");
        var missingReference = home.resolve("new/reference");

        var error = assertThrows(IllegalStateException.class, () -> VmHostExports.create(
                "compile", selected(runtime.toString(), workspace.toString(),
                        Map.of("missing", new WorkerPoolConfig.ReadOnlyExport(
                                missingReference.toString()))), home));

        assertTrue(error.getMessage().contains("must already exist"));
        assertFalse(Files.exists(runtime));
        assertFalse(Files.exists(workspace));
        assertFalse(Files.exists(missingReference));
    }

    @Test
    void rejectsFilesAndCanonicalOverlaps() throws Exception {
        var runtimeFile = home.resolve("runtime-file");
        Files.writeString(runtimeFile, "not a directory");
        assertThrows(IllegalStateException.class, () -> VmHostExports.create(
                "compile", selected(runtimeFile.toString(), home.resolve("workspace").toString(), Map.of()), home));

        var shared = Files.createDirectories(home.resolve("shared"));
        var runtimeLink = home.resolve("runtime-link");
        Files.createSymbolicLink(runtimeLink, shared);
        var overlap = assertThrows(IllegalStateException.class, () -> VmHostExports.create(
                "compile", selected(runtimeLink.toString(), shared.resolve("child").toString(), Map.of()), home));
        assertTrue(overlap.getMessage().contains("overlap after canonicalization"));
    }

    @Test
    void translatesDeclaredPathsAndRejectsUndeclaredOrSymlinkEscapes() throws Exception {
        var outside = Files.createDirectories(home.resolve("outside"));
        Files.writeString(outside.resolve("secret"), "secret");
        var workspace = Files.createDirectories(home.resolve("workspace"));
        var project = Files.createDirectories(workspace.resolve("projects/demo"));
        var plan = VmHostExports.create("compile", selected(
                home.resolve("runtime").toString(), workspace.toString(), Map.of()), home);

        assertEquals("/host/workspace/projects/demo", plan.translate(project.toString(), home));
        assertEquals("/host/workspace/projects/future",
                plan.translate(workspace.resolve("projects/future").toString(), home));
        assertThrows(IllegalStateException.class, () -> plan.translate(outside.toString(), home));

        var escape = workspace.resolve("escape");
        Files.createSymbolicLink(escape, outside);
        var error = assertThrows(IllegalStateException.class,
                () -> plan.translate(escape.resolve("secret").toString(), home));
        assertTrue(error.getMessage().contains("not declared"));

        var dangling = workspace.resolve("dangling");
        Files.createSymbolicLink(dangling, home.resolve("absent-target"));
        var danglingError = assertThrows(IllegalStateException.class,
                () -> plan.translate(dangling.resolve("child").toString(), home));
        assertTrue(danglingError.getMessage().contains("canonicalize"));
    }

    @Test
    void accessAwareTranslationAllowsDowngradeButRejectsReadOnlyExportUpgrade() throws Exception {
        var reference = Files.createDirectories(home.resolve("reference"));
        var plan = VmHostExports.create("compile", selected(
                home.resolve("runtime").toString(), home.resolve("workspace").toString(),
                Map.of("source", new WorkerPoolConfig.ReadOnlyExport(reference.toString()))), home);
        var workspaceLeaf = Files.createDirectories(plan.workspace().hostPath().resolve("job-17"));
        var runtimeLeaf = Files.createDirectories(plan.runtime().hostPath().resolve("staging"));
        var referenceLeaf = Files.createDirectories(reference.resolve("repo"));

        var downgraded = plan.translate(workspaceLeaf.toString(), home,
                WorkerPoolConfig.AccessMode.READ_ONLY);
        assertEquals("/host/workspace/job-17", downgraded.appliancePath());
        assertEquals(WorkerPoolConfig.AccessMode.READ_ONLY, downgraded.requestedAccess());
        assertEquals(WorkerPoolConfig.AccessMode.READ_WRITE, downgraded.exportAccess());

        var writable = plan.translate(workspaceLeaf.toString(), home,
                WorkerPoolConfig.AccessMode.READ_WRITE);
        assertEquals("/host/workspace/job-17", writable.appliancePath());
        assertEquals(WorkerPoolConfig.AccessMode.READ_WRITE, writable.requestedAccess());

        assertEquals("/host/runtime/staging", plan.translate(runtimeLeaf.toString(), home,
                WorkerPoolConfig.AccessMode.READ_ONLY).appliancePath());
        assertEquals("/host/references/source/repo", plan.translate(referenceLeaf.toString(), home,
                WorkerPoolConfig.AccessMode.READ_ONLY).appliancePath());
        assertThrows(IllegalStateException.class, () -> plan.translate(runtimeLeaf.toString(), home,
                WorkerPoolConfig.AccessMode.READ_WRITE));
        assertThrows(IllegalStateException.class, () -> plan.translate(referenceLeaf.toString(), home,
                WorkerPoolConfig.AccessMode.READ_WRITE));
        assertThrows(IllegalStateException.class, () -> plan.translate(
                plan.workspace().hostPath().resolve("missing").toString(), home,
                WorkerPoolConfig.AccessMode.READ_ONLY));
    }

    @Test
    void leafAliasCannotBroadenTranslationToWholeWorkspaceRoot() throws Exception {
        var workspace = Files.createDirectories(home.resolve("workspace"));
        var plan = VmHostExports.create("compile", selected(
                home.resolve("runtime").toString(), workspace.toString(), Map.of()), home);
        var rootAlias = workspace.resolve("requested-leaf");
        Files.createSymbolicLink(rootAlias, workspace);

        var failure = assertThrows(IllegalStateException.class,
                () -> plan.translate(rootAlias.toString(), home,
                        WorkerPoolConfig.AccessMode.READ_ONLY));

        assertTrue(failure.getMessage().contains("resolves to the root"));
        assertEquals("/host/workspace", plan.translate(workspace.toRealPath().toString(), home,
                WorkerPoolConfig.AccessMode.READ_ONLY).appliancePath());
    }

    @Test
    void boundsReferenceTagsAndKernelParameter() throws Exception {
        var tooLong = "reference-name-longer-than-tag-limit";
        var reference = Files.createDirectories(home.resolve("reference"));
        assertThrows(IllegalStateException.class, () -> VmHostExports.create(
                "compile", selected(home.resolve("runtime").toString(),
                        home.resolve("workspace").toString(),
                        Map.of(tooLong, new WorkerPoolConfig.ReadOnlyExport(reference.toString()))), home));

        var manyReferences = new LinkedHashMap<String, WorkerPoolConfig.ReadOnlyExport>();
        for (int i = 0; i < 48; i++) {
            var name = "ref" + String.format("%019d", i);
            var path = Files.createDirectories(home.resolve("references/" + name));
            manyReferences.put(name, new WorkerPoolConfig.ReadOnlyExport(path.toString()));
        }
        var error = assertThrows(IllegalStateException.class, () -> VmHostExports.create(
                "compile", selected(home.resolve("runtime").toString(),
                        home.resolve("workspace").toString(), manyReferences), home));
        assertTrue(error.getMessage().contains("kernel-parameter length"));
    }

    @Test
    void rejectsVfkitDelimitersBeforeCreatingRoots() {
        for (var bad : java.util.List.of("comma,path", "line\npath", "nul\0path")) {
            var runtime = home + "/" + bad;
            var error = assertThrows(IllegalStateException.class, () -> VmHostExports.create(
                    "compile", selected(runtime, home.resolve("workspace").toString(), Map.of()), home));
            assertTrue(error.getMessage().contains("comma-separated device syntax"));
        }
    }

    @Test
    void fingerprintIsIndependentOfReferenceDeclarationOrderAndPersistsAtomically() throws Exception {
        var one = Files.createDirectories(home.resolve("one"));
        var two = Files.createDirectories(home.resolve("two"));
        var refsForward = new LinkedHashMap<String, WorkerPoolConfig.ReadOnlyExport>();
        refsForward.put("one", new WorkerPoolConfig.ReadOnlyExport(one.toString()));
        refsForward.put("two", new WorkerPoolConfig.ReadOnlyExport(two.toString()));
        var refsReverse = new LinkedHashMap<String, WorkerPoolConfig.ReadOnlyExport>();
        refsReverse.put("two", new WorkerPoolConfig.ReadOnlyExport(two.toString()));
        refsReverse.put("one", new WorkerPoolConfig.ReadOnlyExport(one.toString()));

        var forward = VmHostExports.create("compile", selected(
                home.resolve("runtime").toString(), home.resolve("workspace").toString(), refsForward), home);
        var reverse = VmHostExports.create("compile", selected(
                home.resolve("runtime").toString(), home.resolve("workspace").toString(), refsReverse), home);
        assertEquals(forward.fingerprint(), reverse.fingerprint());

        var stateFile = home.resolve("state/vm-exports.sha256");
        forward.persistFingerprint(stateFile);
        assertDoesNotThrow(() -> reverse.requirePersistedFingerprint(stateFile));
        Files.writeString(stateFile, "different\n");
        var error = assertThrows(IllegalStateException.class,
                () -> forward.requirePersistedFingerprint(stateFile));
        assertTrue(error.getMessage().contains("isx vm restart"));
    }

    @Test
    void runtimeStagingCannotBroadenThePlan() throws Exception {
        var plan = VmHostExports.create("compile", selected(
                home.resolve("runtime").toString(), home.resolve("workspace").toString(), Map.of()), home);

        var staging = plan.createRuntimeStagingDirectory("isx-download-");

        assertTrue(staging.startsWith(plan.runtime().hostPath()));
        assertTrue(Files.isDirectory(staging));
        assertTrue(plan.translate(staging.toString(), home).startsWith("/host/runtime/isx-download-"));
    }

    private static WorkerPoolConfig.Selected selected(
            String runtime,
            String workspace,
            Map<String, WorkerPoolConfig.ReadOnlyExport> references) {
        return new WorkerPoolConfig.Selected(
                4, 4096, "8G",
                new WorkerPoolConfig.ReadOnlyExport(runtime),
                new WorkerPoolConfig.ReadWriteExport(workspace),
                references);
    }
}
