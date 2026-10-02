---
paths:
  - "common/src/main/java/dev/incusspawn/config/**"
  - "common/src/main/java/dev/incusspawn/lifecycle/**"
  - "common/src/main/resources/images/**"
---

# Configuration Loading

- `SpawnConfig`: global config from `~/.config/incus-spawn/config.yaml`
- `SpawnConfig.SshConfig`: `ssh.include-into` selects the absolute-or-tilde user SSH file that incus-spawn may modify; it defaults to `~/.ssh/config`, while inclusion of any alternate fragment remains user-managed
- `ImageDef.loadAll()`: discovers all image definitions across resolution layers, rejects unknown image/security fields, and resolves each inheritable security field after all overrides are known
- `ToolDefLoader`: discovers tools across resolution layers
- `ProjectConfig`: per-project config from `incus-spawn.yaml` or `.incus-spawn/incus-spawn.yaml`

**Worker pools are strict:** `worker-pools` is a named map in global config. Every static entry requires `cpus`, `memory-mib`, `swap`, `runtime-root` (typed read-only), `workspace-root` (typed read-write), and `reference-roots` (named read-only paths, possibly empty); `direct-root` is not a YAML field. `WorkerPoolConfig` validates safe names, resources, absolute-or-tilde paths, and normalized overlap. `VmHostExports` performs launch-time canonical/physical validation and is the only host-to-guest translator. A process with `ISX_POOL` must use `SpawnConfig.loadStrict()` and never the forgiving fallback. A materialized process instead requires both `ISX_MATERIALIZED_POOL` and `ISX_MATERIALIZED_POOL_IDENTITY`, forbids `ISX_POOL`, and loads its frozen resources, roots, and direct export only from strict protected `PoolState`; unknown/duplicate fields and trailing JSON tokens are rejected, and partial/corrupt selection cannot fall through. `RuntimeConstants.WORKER_POOL` owns the immutable selection, with no switch API.

Resolution order for both images and tools (later overrides earlier): built-in -> user (`~/.config/incus-spawn/`) -> search paths -> project-local (`.incus-spawn/`). Image `security` fields (`sudo`, `nested-containers`, `permissive-capabilities`) inherit independently; omitted root values are false. The loaded definitions contain effective policies, and those values participate in image fingerprints.

**Name conflicts vs. overrides**: Overriding a definition from a *later* layer is intentional and supported. Two files declaring the same `name:` *within a single directory* (usually a copy-paste that forgot to update `name:`) is always a mistake and is reported as a conflict. `ImageDef.loadAllWithConflicts()` / `ToolDefLoader.conflicts()` return these same-directory collisions (all colliding files, so 3+ are listed together) plus the intentional cross-layer overrides. `isx build` aborts with a message naming the colliding files and refuses to build until you disambiguate; the TUI stays resilient (surfaces the conflict as a status warning but still lists templates); `isx doctor` reports both conflicts (warnings) and cross-layer overrides (informational -- this explains the "built image doesn't match the file I'm editing" confusion). Plain `ImageDef.loadAll()` still returns the resolved map (last-writer-wins) and emits a one-line conflict warning via its warnings consumer, so existing callers are unaffected.
