---
paths:
  - "common/src/main/java/dev/incusspawn/util/BuildOutput.java"
  - "common/src/main/java/dev/incusspawn/util/TerminalProgress.java"
  - "common/src/main/java/dev/incusspawn/automation/AutomationProtocol.java"
  - "cli/src/main/java/dev/incusspawn/command/AutomationCommand.java"
---

# Terminal Output

All multi-step human-facing lifecycle commands use `BuildOutput` (`common/.../util/BuildOutput.java`) for terminal output formatting -- not just build/branch but also `vm` (start/stop/resize), `destroy`, `update-all`, `update-base`, `project`, and the shared `VmManager`. This centralizes ANSI constants and step patterns -- individual commands should not define their own. Key helpers: `section()` for introducing a block of work at column 0 (e.g. "Refreshing 8 host repos:", "Updating 6 template(s)."), `header()` (generic bold bullet header), `buildHeader()`/`branchHeader()` (build/branch-specific), `step()` for complete lines, `stepWithList()` for a header followed by a comma-separated list that wraps at the terminal width with a hanging indent (used for packages, tools, skills), `stepStart()`/`stepDone()`/`stepDone(detail)` for inline completion on slow operations (never leave a `Doing X...` line dangling with its result on a separate line), `note()` for dim informational messages (blank messages are silently skipped), `warn()` for yellow inline warnings, `warnBanner()` for yellow-bordered stderr warning banners (subnet conflicts, CA mismatches, firewall issues), `success()` for green checkmark lines. Headers live in command classes; shared helpers like `VmManager` emit only steps so they nest correctly under whichever header the caller printed. `isx init` keeps its own interactive style. `isx automation` is a separate machine-protocol exception: it must never use `BuildOutput` or place decorative text on stdout. See DESIGN.md "Terminal Output Visual Language" for the full spec.
