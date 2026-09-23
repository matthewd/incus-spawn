---
paths:
  - "common/src/main/java/dev/incusspawn/config/**"
  - "common/src/main/java/dev/incusspawn/lifecycle/**"
  - "common/src/main/resources/images/**"
---

# Configuration Loading

- `SpawnConfig`: global config from `~/.config/incus-spawn/config.yaml`
- `ImageDef.loadAll()`: discovers all image definitions across resolution layers, rejects unknown image/security fields, and resolves each inheritable security field after all overrides are known
- `ToolDefLoader`: discovers tools across resolution layers
- `ProjectConfig`: per-project config from `incus-spawn.yaml` or `.incus-spawn/incus-spawn.yaml`

**Worker pools are strict:** `worker-pools` is a named map in the global config. Every entry requires `cpus`, `memory-mib`, `swap`, `runtime-root` (typed read-only), `workspace-root` (typed read-write), and `reference-roots` (named read-only paths, possibly empty). `WorkerPoolConfig` validates path-safe lowercase names (references are at most 22 characters so their generated tag fits virtio-fs), resource minima/syntax, absolute-or-tilde paths, and normalized overlap. `VmHostExports` performs launch-time filesystem validation: canonical overlap, existing-directory checks, controlled runtime/workspace creation, mandatory pre-existing references, and rejection of comma/newline/NUL delimiters. It is also the only named-pool host-to-guest path translator; undeclared and symlink-escaping paths are errors. Unknown fields are errors. A process with `ISX_POOL` set must select through `SpawnConfig.loadStrict()` and fail closed on any config or selection error; never use the forgiving `SpawnConfig.load()` fallback for this path. `RuntimeConstants.WORKER_POOL` owns the immutable process selection, and no in-process switching API should be added.

Resolution order for both images and tools (later overrides earlier): built-in -> user (`~/.config/incus-spawn/`) -> search paths -> project-local (`.incus-spawn/`). Image `security` fields (`sudo`, `nested-containers`, `permissive-capabilities`) inherit independently; omitted root values are false. The loaded definitions contain effective policies, and those values participate in image fingerprints.

**Name conflicts vs. overrides**: Overriding a definition from a *later* layer is intentional and supported. Two files declaring the same `name:` *within a single directory* (usually a copy-paste that forgot to update `name:`) is always a mistake and is reported as a conflict. `ImageDef.loadAllWithConflicts()` / `ToolDefLoader.conflicts()` return these same-directory collisions (all colliding files, so 3+ are listed together) plus the intentional cross-layer overrides. `isx build` aborts with a message naming the colliding files and refuses to build until you disambiguate; the TUI stays resilient (surfaces the conflict as a status warning but still lists templates); `isx doctor` reports both conflicts (warnings) and cross-layer overrides (informational -- this explains the "built image doesn't match the file I'm editing" confusion). Plain `ImageDef.loadAll()` still returns the resolved map (last-writer-wins) and emits a one-line conflict warning via its warnings consumer, so existing callers are unaffected.
