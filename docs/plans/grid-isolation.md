# Plan — `grid` must not take the shell down with it (window in its own process)

## Context
Reported: when the JavaFX window of `grid` freezes or crashes, the PowerJ shell stops too.

Cause (read from the code, **not reproduced yet**, see phase 0): the window lives **in the JVM of the shell**.
`GridWindowOpener` starts the toolkit with `Platform.startup` in the same process as the interpreter, the prompt and the
terminal (`FxRuntime`, `JavaFxPlatform`). Everything that can go wrong with a window then reaches the shell:

| Failure | What happens today |
|---|---|
| Crash in native code (GTK / Prism / D3D / driver, `SIGSEGV`, `hs_err_pid*.log`) | The JVM dies: shell gone. Nothing in Java can catch it. |
| The window freezes (blocked toolkit thread) and the user kills it with the system ("not responding", Task Manager, `kill`) | The window belongs to the process of the shell (`powerj.exe`): the shell is killed with it. |
| The toolkit thread is stuck | Every later `grid` times out after 30 s (`FxRuntime.runAndWait`), and the pipeline of the current `grid` waits for ever on the full queue (`LiveTable.offer`) or on `--wait` / `--pass-thru` (`awaitClosed`). |
| `OutOfMemoryError` / `StackOverflowError` on the toolkit thread | Same heap as the shell: the shell is hit too. |

Hardening inside the process (uncaught-exception handler, watchdog, timeouts) fixes the last two rows at best. **The first
two can only be fixed by moving the window out of the shell's process.** That is the decision of this plan.

Branch: `ccr-7ee3b778-nn3h1k`, from `main` @ 25679de. One PR, opened once this plan is validated.

## Target
```text
 shell JVM (powerj.exe)                               host JVM (java -m io.powerj.fx/…GridHostMain)
 ┌──────────────────────────────┐   stdin/stdout      ┌───────────────────────────────┐
 │ GridCmdlet ─ ObjectReader     │  framed messages    │ HostMain ─ GridView (as today) │
 │   LiveTable ─ WindowSession ──┼────────────────────►│   LiveTable ─ GridTable        │
 │   (keeps the objects, only    │◄────────────────────┼── selection, closed, ready     │
 │    for --pass-thru)           │                     │ JavaFX toolkit, GTK, Prism     │
 └──────────────────────────────┘                     └───────────────────────────────┘
```
- The shell reads the pipeline and the properties (script access to the objects stays there) and sends **plain values** to the
  window process. The window process knows nothing about the shell's objects.
- If the host process crashes, freezes or is killed, the shell sees the pipe close (or Ctrl+C) and ends the `grid` command
  with a short error. The prompt is back, the next `grid` starts a new host.
- Everything in `GridView`, `GridTable`, `LiveTable` (window side), filters, sort and settings stays as it is: it just runs
  in the other JVM.

## Decisions to validate
1. **One host process per `grid` command** (recommended) rather than one shared, long-lived host. A crash then affects
   one window only, and a frozen window never blocks the next `grid`. Cost: about one JVM + toolkit start per command,
   the same work as the first `grid` of a session today.
2. **No silent fallback to the in-process window.** If the host cannot be started (no `java` in the runtime, no display), `grid`
   fails with a clear message; falling back would bring the risk back.
3. **`--pass-thru` keeps the objects in the shell.** The window returns the *positions* of the ticked rows (in the
   order of the window), the shell emits the original objects. The objects never cross the process boundary.
4. **A frozen window is not killed automatically.** The shell stays usable (non-blocking `grid`), Ctrl+C ends a waiting
   `grid` and kills the host, and closing/killing the frozen window with the system now only ends the host. A heartbeat
   with a timeout is left out for now (a long sort on 100 000 rows must not be mistaken for a freeze).

## Design
### Protocol (package `io.powerj.fx.wire`, no JavaFX, fully unit-tested)
Binary frames on the child's stdin (shell → host) and stdout (host → shell), `DataOutputStream` style, version number in
the first frame. In the host, `System.out` is redirected to `System.err` at start so that nothing printed by a library
corrupts the channel; the shell drains the host's stderr and keeps the last lines for the error message.

| Direction | Message | Content |
|---|---|---|
| shell → host | `OPEN` | protocol version, title, column choice, settings file, `passThru`, `single`, locale |
| shell → host | `ROWS` | batch of readings: new columns (name, kind), rows (position, typed cells), errors are reported by the shell |
| shell → host | `FINISH` / `CLOSE` | input ended (`truncated` or not) / close the window |
| host → shell | `READY` | the window is shown |
| host → shell | `ACCEPTED` | positions of the ticked rows (OK); absent on Cancel |
| host → shell | `CLOSED` | the window was closed by the user (normal end) |

Back-pressure needs no message: the host's `LiveTable` is bounded, its reader thread blocks when it is full, the pipe fills,
the shell's sender blocks, the shell's own `LiveTable` fills and the pipeline waits. The existing "the window sets the pace"
and "Pause" behaviours are kept as they are.

Typed cells (`ValueCodec`): `null`, `String`, `Boolean`, `Character`, integral numbers, floating point, `BigInteger`,
`BigDecimal`, `Instant`, `Duration`, other `Temporal` and `Date` (sent as an `Instant` or ISO text, whichever keeps the sort
and the display of today), everything else as `String.valueOf` (what `CellFormatter` shows today). Phase 1 starts by listing
the types that `ObjectReader` / `ValueOrder` / `CellFormatter` really see, so that sort order, filters and display do not change.

### Shell side
- `WindowOpener` stays the interface of `GridCmdlet` (its tests need no JavaFX). New implementation `HostWindowOpener` replaces
  `GridWindowOpener`: it starts the process, runs two daemon threads (sender: drains `LiveTable` into `ROWS` frames; receiver:
  `READY` / `ACCEPTED` / `CLOSED`) and drains stderr.
- `LiveTable` gets the small addition the sender needs (take a batch of readings to a consumer instead of into a `GridTable`);
  `accept(...)` is fed from the positions that come back.
- Starting the host: `<java.home>/bin/java` (the runtime of the jlink image, not `powerj.exe`), module path = the shell's
  `jdk.module.path` plus the folder of the `io.powerj.fx` layer, `--enable-native-access` for the fx modules (the user's consent
  for the module, already asked at load time, is what allows it), `-m io.powerj.fx/io.powerj.fx.host.GridHostMain`.
- End of the host, for any reason, without `CLOSED`: `LiveTable.close()` (the pipeline stops reading, `--wait` returns), one
  error line `the grid window ended unexpectedly (exit code N)` + the last lines of its stderr. With `CLOSED`: nothing to say.
- Ctrl+C, an error in the pipeline, `exit` of the shell: the host is destroyed (`Execution.close()` already exists for that;
  a `ProcessHandle` shutdown hook covers a shell that dies). The host also exits by itself when stdin reaches end of file
  (shell gone), so no orphan window is left.

### Host side (`io.powerj.fx.host`)
- `GridHostMain`: reads `OPEN`, starts the toolkit (what `JavaFxPlatform` does now, in a process of its own: a failed start
  simply ends the process with an exit code and a message, so the "cannot start twice in a JVM" workaround of `FxRuntime`
  disappears), builds `GridView`, shows the window, then feeds a `LiveTable` from `ROWS`.
- `Platform.setImplicitExit(true)` is now correct: closing the window ends the process. `System.exit` is allowed here (the
  guard-rail of FR-58 protects the shell's JVM, not this one: check `ExitGuardTest` / ArchUnit rules so that they still pass).
- Window settings (position, size) are read and written by the host with the same `WindowSettings` file.

## Phases
### Phase 0 — Reproduce and decide (small)
Under `xvfb-run` with a throw-away harness (not committed): a blocked toolkit thread, an `Error` on the toolkit thread, a
`kill -SEGV` / `kill -9` of the window's process, and a force-quit of a stopped (`SIGSTOP`) window. Note what happens to the
shell in each case and add it to this plan. Ask the reporter (see "Questions") for their platform and the exact symptom.
Record the decision as ADR 0011, "the grid window runs in its own process".

### Phase 1 — Protocol and codec (no process yet)
`io.powerj.fx.wire`: frames, the messages above, `ValueCodec`. Round-trip tests for every type, truncated and corrupted
frames, big batches, unknown protocol version. Lists the value types first (see "Typed cells").

### Phase 2 — Host and launcher
`GridHostMain`, `HostWindowOpener`, the `LiveTable` addition, positions → objects for `--pass-thru`. `GridCmdlet` is not
changed except the construction of its default opener. Remove `FxRuntime`, `FxPlatform`, `JavaFxPlatform`, `GridWindowOpener`
and their tests once nothing uses them. Tests: sender and receiver over in-memory pipes with a fake host; a real child JVM
that plays a fake host (as `ExitGuardTest` does), covering ready / accepted / closed.

### Phase 3 — Failures and life cycle
Host crash (exit code ≠ 0, stream cut in the middle of a frame), host killed, host that never answers `READY` (start timeout
as today, 30 s), no display (message and exit code from the host, same text as today's `fx.unavailable`), Ctrl+C with and
without `--wait` / `--pass-thru`, shell `exit` with windows open, host stderr in the message, protocol error. A test for each,
asserting that the shell's thread gets an ordinary error and not an exception, and that no thread or process is left.

### Phase 4 — Checks, docs, PR
- `ci/smoke/fx.sh` (xvfb): open a grid, `kill -9` the host, check the shell is alive and a new `grid` opens; `SIGSTOP` the host,
  check the prompt works and that `grid --wait` ends on Ctrl+C.
- UI tests of `GridView` (`-Pfx-ui`) keep running; coverage floors of `powerj-fx` (0.99 / 0.97 outside `view`) kept: the host
  main class is excluded like `view`, the protocol and the shell side are not.
- Docs: specification FR-77 (the window is a separate process: consequences for `--pass-thru`, start time), `docs/plans/fx-grid.md`
  (R-risks and the "Threads" paragraph), `docs/ARCHITECTURE.md`, ADR 0011, `CHANGELOG.md`.
- Open the PR (the template of the repository is used), then watch it.

## Risks
| # | Risk | Mitigation |
|---|---|---|
| 1 | `bin/java` missing from the jlink image, or not the right module path from a jpackage launcher. | Phase 0/2 check on the real `-Pdist` image (`ci/smoke/launcher.sh`); otherwise add the launcher option to `powerj-dist`. |
| 2 | Slower first window (a JVM per `grid`). | Measured in phase 2; if too slow, a warm host prepared in the background at first use (design left open, not needed to fix the bug). |
| 3 | Sort/filter/display differ for a type that does not survive the wire. | Phase 1 inventory + round-trip tests; the rest is sent as text, which is what the cell shows. |
| 4 | Memory: rows exist in both processes. | Only the cell values are duplicated (already read into `GridRow`); the shell does not keep the objects unless `--pass-thru`. |
| 5 | Windows: `ProcessBuilder` and `System.out` redirection details; macOS not tried (as before). | Windows CI job builds and unit-tests; manual check on Windows before merge. |
| 6 | Behaviour change for users who relied on one process (e.g. a debugger). | Documented in FR-77 and the changelog. |

## Questions for you
1. Which platform and what exactly did you see (hard crash with a message / `hs_err_pid` file, frozen window then you closed
   it, shell closing without a message)? It does not change the design, but phase 0 will target it.
2. Decisions 1–4 above: OK as written, or do you prefer a different choice (shared host process, heartbeat that closes a frozen
   window after N seconds)?
