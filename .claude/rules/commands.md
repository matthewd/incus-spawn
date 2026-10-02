---
paths:
  - "cli/src/main/java/dev/incusspawn/IncusSpawn*.java"
  - "cli/src/main/java/dev/incusspawn/command/**"
  - "cli/src/main/java/dev/incusspawn/tui/**"
  - "common/src/main/java/dev/incusspawn/automation/**"
---

# Entry Point and Command Structure

`IncusSpawn.java` is the aesh `@CommandDefinition` top command. Before help, version, TUI, or subcommand dispatch, it resolves `WorkerPoolSelection.current()` so an invalid static `ISX_POOL` or invalid/partial materialized selector pair fails closed before services or state paths are used. Selection is immutable for the process; do not add a command option or in-process pool switch. With no subcommand, it launches the TUI (`ListCommand`). Each subcommand in `command/` is an aesh `@CommandDefinition` with Quarkus DI.

**Platform-specific command tree**: aesh bakes `groupCommands` into the annotation at compile time, so macOS-only commands can't exist on one platform without a second top command. `IncusSpawn` defines two variants -- `IncusSpawnCommand` (macOS, includes the `vm` and `pool` groups) and `IncusSpawnLinuxCommand` (identical minus both) -- and picks one at runtime via `Platform.isMacOS()`. On Linux both are unknown and absent from help. `CompletionCommand.stripMacApplianceCommands()` keeps generated completions in step by removing both appliance groups while leaving the unrelated `build --type vm` value untouched. When adding a shared command, add it to both command lists; `IncusSpawnCommandTreeTest` fails if they drift. When adding another macOS-only command, add it only to the macOS tree and extend the completion strip.

# Init and Versioned Completion

`InitCommand` runs first-time setup (dependencies, Incus, firewall, CA, proxy service, etc.). On Linux it also installs a tightly-scoped NOPASSWD sudoers rule (`configureBtrfsUsageAccess` -> `/etc/sudoers.d/incus-spawn-btrfs`) permitting only read-only `btrfs qgroup show`/`subvolume list` plus `btrfs quota rescan` (rebuilds accounting counters, never touches data — the auto-repair for inconsistent qgroups, see `BtrfsUsage`) against the CoW pool mount, so the non-root TUI/build can read referenced sizes for per-template disk accounting and repair them when btrfs flags them stale (validated with `visudo -cf` before install). Commands that need a working environment call `InitCommand.requireInit()`, which auto-launches init if it hasn't completed. Completion is tracked by a sentinel file `~/.config/incus-spawn/.init-complete` containing `INIT_VERSION` -- a version integer defined in `InitCommand`. Every init step is idempotent, so re-running is safe.

**When to bump `INIT_VERSION`**: increment it when adding a new infrastructure step to init that existing installations need (new dependency, firewall rule, systemd service, config field). A sentinel *older* than `INIT_VERSION` causes `hasBeenInitialized()` to return false, triggering a re-run on the next command. Do NOT bump it for changes that don't affect host configuration (new template features, TUI changes, proxy logic changes).

The comparison is `>=`, not equality: the sentinel is a monotonic floor, so a binary that finds a *newer* sentinel treats itself as initialized rather than concluding init never ran. This matters when two isx builds are installed at once (e.g. `/usr/bin/isx` and `~/.local/bin/isx`) -- under equality the older one both hard-failed the proxy service and re-ran init to write the sentinel back down, ping-ponging with the newer binary. Never renumber `INIT_VERSION` downward.

# Pool Commands

`PoolCommand` is a macOS-only, one-line versioned JSON API. `inspect` verifies whether the selected static seed is stopped and has a valid latest sealed generation; `seal` requires that stopped seed and publishes a new generation; `materialize` requires the same seed selection plus name/direct-root and optionally an exact generation/identity pair. Keep filesystem/identity semantics in common's `PoolStore`/`PoolState`, not aesh fields. Materialized runtime selection is separate from the management invocation and requires `ISX_MATERIALIZED_POOL` plus `ISX_MATERIALIZED_POOL_IDENTITY`.

# Automation Commands

`AutomationCommand` is shared by the macOS and Linux trees. It never invokes interactive init, prompts, or `BuildOutput`. Keep aesh limited to option decoding and stream selection: ownership, idempotency, key-only deletion, exact mount/unmount semantics, reconciliation, and defaults belong in transport-neutral `AutomationService`; one-line lifecycle JSON and synchronized base64 NDJSON exec frames belong in `AutomationProtocol`. Every lifecycle mutation requires a caller key. Mount/unmount must validate all fields, accept only Running/Stopped instances, inspect unexpanded own devices, recheck ownership/state under an ETag-conditional mutation, and never remove by device name alone; lifecycle resources and errors must not disclose attachment paths or raw backend details. Exec must pass the parsed JSON argv directly, forward stdin without logging, and register a shutdown hook against the external cancellation signal.
