# Plan — perf mode (Alt+P) and `perf` cmdlet (mini-iteration 25, FR-76)

## Context
Users cannot see where a pipeline spends its time. Stages run in parallel (virtual threads, bounded queues), so the duration of a stage alone means nothing: a fast stage looks slow when it waits for its neighbours. Only `$last.duration` exists, for the last native command.

Decisions taken with the user:
- a **mode**, entered with **Alt+P**, which prints statistics **at the end of each pipeline**;
- a **`perf` cmdlet** that gives **more detail afterwards** and returns **many rows** that a pipeline can process (`perf | sort active -d | first 3`).

The iteration has two parts: **part A** (counters, mode, cmdlet; merged in #41) and **part B** (JFR, section "Part B — JFR" below; its own branch and PR). Part A works alone; part B adds the answer to "why is this stage slow".

Out of scope here (possible later iterations): `bench` (repeat, warmup, statistics), CPU time and allocated bytes per stage through `ThreadMXBean` (it returns -1 on virtual threads; JFR covers it), CPU and memory of native processes.

Branch: `ccr-63acb789-rkagf5`, from `main` @ d07b11c.

## The model: three times per stage
For each stage of a pipeline:
- **starved**: time waiting for input (`Pipe.next` had to block);
- **blocked**: time waiting for the next stage (`Pipe.put` had to block: backpressure);
- **active**: wall time of the stage minus starved minus blocked. It includes the time in `process` and in `{ }` blocks, and the I/O the cmdlet does itself.

The **bottleneck** is the stage with the largest `active`: the stages before it are blocked, the ones after it are starved.

Also counted: objects **in** (taken from the pipe) and **out** (put in the next pipe, or sent to the display). `where` in 412k, out 1.2k is its selectivity.

## Behaviour of the mode
- **Auto-exit**: the variable `POWERJ_PERF_AUTOEXIT` of the session environment, when it is `1`, ends the mode after the next measured line (`Interpreter.printPerf`, once the summary is printed; the shell prompt shows the mode at each read, so it goes back to `PJ`). Any other value leaves the mode on. Asked by the user, after proposing five names; kept `POWERJ_PERF_AUTOEXIT`. Lines that are not measured do not use it up; a cancelled line does.
- **Alt+P** switches between normal and perf mode, keeping the line being typed (same mechanism as Alt+V, FR-66). The prompt shows it: `PJ% <dir>> `.
- Modes are exclusive, as in vi: Alt+P in preview mode goes to perf mode, and the other way round.
- In perf mode, each executed line is measured. After its output, a footer is printed to the console (gray, never captured by a redirection or an assignment):

```text
── perf · 2.84 s ──────────────────────────────────────────────
stage         in     out    active   starved  blocked
ls -r /       -      412k   1.9 s    -        0.8 s    ← bottleneck
where {…}     412k   1.2k   0.6 s    1.9 s    -
sort size -d  1.2k   1.2k   10 ms    2.8 s    -
first 5       1.2k   5      1 ms     2.8 s    -
display       5      -      20 ms
JVM: GC 3 (41 ms) · heap peak 210 MB · classes +112 · JIT 310 ms
```
- A native stage has a wall time only (`git log` → `active`); no `in`/`out`.
- **Ctrl+C**: the report of what ran is printed, marked `cancelled`.
- Not measured: preview (`PreviewGuard`), background `&`, lines whose first command is `perf` (so that `perf` always describes the line before it).
- Interactive only. `-c`, script and standard-input modes never measure.
- **Cost**: nothing when the mode is off. When on, there are no per-object timings except for `{ }` blocks and the display; the stage counters are written only by the thread that owns them.

## The `perf` cmdlet
```text
perf [<n>] [--view stages|blocks|natives|phases|jvm|lines] [-a|--all]
```
- Reads the reports kept by the session (the last 50 measured lines). It produces objects and reads none.
- `<n>`: the number of a measured line, as in the `line` column of the rows (`perf 1` is the first line measured, as long as it is kept); the last line by default. It was first planned as "n-th back" (1 = last), which surprised the user: `perf 1` did not show line 1 of `perf --view lines`. `--all`: every kept line; each row carries its `line` number.
- `--view` chooses the record:

| View | Record | One row per |
|---|---|---|
| `stages` (default) | `StageStat(line, index, command, in, out, active, starved, blocked, wall, bottleneck)` | stage |
| `blocks` | `BlockStat(line, stage, source, calls, total, average, max, first)` | `{ }` block |
| `natives` | `NativeStat(line, command, pid, wall, exitCode)` | native process |
| `phases` | `PhaseStat(line, name, time)` with `parse`, `resolve`, `execute`, `display` | phase |
| `jvm` | `JvmStat(line, gcCount, gcTime, heapPeak, classesLoaded, jitTime)` | line |
| `lines` | `LineStat(line, text, total, bottleneck, cancelled)` | measured line |

- `first` in `BlockStat` is the first call alone: it shows the cold start (class loading, reflection, JIT) against `average`.
- Durations are `Duration`s (readable in tables, `where { $_.active > 500ms }` works with the existing unit literals, `sort active -d` works since a `Duration` is `Comparable`). Sizes use `@Bytes`.
- Examples: `perf | sort active -d | first 1`, `perf --view blocks | sort total -d`, `perf --all | group command | sort count -d`, `perf --view lines --all | to-json perf.json`.
- No measured line yet: a non-blocking message says to press Alt+P and run a line.
- `preview = true` (reads state only).

## Implementation
- **`powerj-api`**
  - The six records above and `PerfReport(line, text, total, cancelled, phases, stages, blocks, natives, jvm)`, with `Objects.requireNonNull` in compact constructors.
  - `CmdletContext.perfReports()`: `default List<PerfReport>`, empty. Compatible with third-party cmdlets.
- **`powerj-core`**
  - `PerfRecorder`: one per measured line; owns the stage probes and the phase timers, and builds the `PerfReport`. `StageProbe` measures one stage. `Session` keeps a ring of the last 50 reports and exposes them (`perfReports()`).
  - `PerfFooter`: renders the summary as plain aligned text (gray with ANSI when the terminal has colors). It lives in the core, so the mode is testable without a terminal: `Interpreter.perfMode(boolean)` switches it, and the summary is printed on `ShellIo.out()` after the line.
  - `Pipe`: when a probe is attached, `put` and `next` try `offer`/`poll` first and time only the call that has to block; they also count objects. Without a probe the code is unchanged.
  - `Interpreter`: times `parse`, `resolve` (`segments()`, arguments and subexpressions included), `execute`; wraps the sink of the last stage to time the `display`; creates one probe per segment and takes the wall time of each (`segment()`); covers the single-stage fast path (no pipes: wall time, objects out through the sink). Native groups: one `NativeStat` per process from `NativeRun`.
  - Blocks: `CompiledBlock` counts calls and times them when a probe is bound. The probe of the current stage is carried by a `ScopedValue` propagated to the stage threads, like `PreviewGuard.propagate`.
  - JVM: snapshot before and after the line from `GarbageCollectorMXBean`, `CompilationMXBean`, `ClassLoadingMXBean`; heap peak from the heap `MemoryPoolMXBean`s after `resetPeakUsage()`. Needs `requires java.management;` in `module-info.java` (in `java.se`, so no change to the jlink image).
  - Automatic variable `$perf`: the last `PerfReport` (empty before any measure).
  - `Supervisor`/`Outcome`: unchanged. `Cancelled` still returns the report built so far.
- **`powerj-cmdlets`**: `Perf.java` (`@CmdletInfo(name = "perf", category = "System", preview = true)`), registered in `BuiltinCmdlets`; messages `perf.summary`, `perf.option.*`, `perf.none` in `messages_en`/`_fr`.
- **`powerj-shell`**
  - `Perfer` (like `Previewer`): the Alt+P widget; it switches `Interpreter.perfMode`. JLine reads `%` as a pattern in `setPrompt`, so the widget escapes it.
  - `Repl.prompt()`: `PJ%` in perf mode.
  - Alt+P is bound by JLine's emacs keymap to `history-search-backward` (to check): it is rebound, as ↑/↓ already are in `ShellReader`. A test pins the binding.
  - Alt+V and Alt+P switch each other off.
- **Completion**: `--view` values come from the enum option. The properties of the rows after a `perf` stage are not proposed (the cmdlet emits several record types).
- **`Values.duration`**: durations under a millisecond are shown in microseconds or nanoseconds (`1.4 µs`), so the blocks are readable; the other durations are unchanged.
- Docs and README in the implementation commits: FR-76 (the first commit), `docs/recettes/perf.md`, §11 row 25, scope sentence (~l.408), README (feature section after Preview mode, cmdlets table).

## Part B — JFR (iteration 26)
Counters say *which* stage is slow; Java Flight Recorder says *why* (hot methods, allocations, GC pauses), for about 1–2% overhead. Decisions taken with the user: JFR is **always on** in perf mode (no switch to turn it off); delivered in its own branch and PR after part A was merged.

- **One recording per measured line** (instead of one recording for the whole mode, as first planned). An in-memory recording cannot be dumped once stopped; a recording with a destination file gives the file of the line at once, with its exact window and a bounded cost. Measured on JDK 25: start 18 ms, stop and write 26 ms, read 46 ms, plus about 200 ms once for the first recording and the first read (done when the mode is switched on). The start is excluded from the line (`PerfRecorder` begins after it), and the end is taken (`PerfRecorder.stop()`) before the recording is read, so the profiler's own work counts in neither the time nor the JVM counters.
- **Sampling period**: 10 ms by default, set by the variable `POWERJ_PERF_JFR_SAMPLEPERIOD` of the **session** environment (so `env --set` in `profile.pj` works), read at the start of each measured line. A number with an optional unit (`2ms`, `500us`, `0.5s`; no unit: ms). JDK 25 on Linux does not sample faster than 1 ms (100 µs gives the same samples as 1 ms), so a shorter value becomes 1 ms with a message; an invalid value is reported once per value and the default is used (`PerfJfr.parseSamplePeriod`, `Interpreter.samplePeriod`). Decided with the user, instead of a `config.properties` key.
- **Allocation limit**: the allocations have no period; the profiler limits them by a rate (`throttle`). Variable `POWERJ_PERF_JFR_ALLOCTHROTTLE` of the session environment, read like the period: `N/unit` with `ns`, `us`, `ms`, `s`, `m`, `h`, `d` (spaces tolerated, written back without them), or `off`; unset means the profiler's default. The profiler does **not** reject a wrong value (checked on JDK 25: `abc`, `10/min`, `OFF`, an empty value all remove the limit, one with a warning printed on the console), so `PerfJfr.parseAllocThrottle` checks it, and a value that is not a rate is reported once and ignored. Measured on JDK 25: `150/s` gave 129 events in a second, `off` 2622, `1/s` 1. A period setting on the other two events would do nothing: `jdk.GarbageCollection` has only `threshold` and `enabled`, so every collection is recorded.
- **Events**: `jdk.ExecutionSample`, `jdk.GarbageCollection`, `jdk.ObjectAllocationSample`. `jdk.ThreadCPULoad` was dropped: nothing uses it.
- **Stage attribution**: the stage threads are named `powerj-etape-<probe index>` (JFR samples virtual threads with their name: checked on JDK 25). The last stage runs in the calling thread, which also runs the shell, so its samples are attributed by time, with the `Instant` window of each stage (`StageProbe`); other samples of that thread are stage 0. Threads that are not stage threads (editor, completion) are ignored.
- **New views** of `perf`, aggregated when the line ends (`JfrCapture`), so they stay available for the kept lines:

| View | Record | One row per |
|---|---|---|
| `hot` | `HotMethod(line, stage, method, samples, percent)` | method on top of the stack, per stage, most sampled first, 20 per stage |
| `gc` | `GcStat(line, name, cause, pause)` | collection, longest pause first |
| `alloc` | `AllocStat(line, stage, type, bytes)` | allocated type per stage (`@Bytes`), 20 per stage; `[B` is shown `byte[]` |

- **Export**: `perf [<n>] --save <path>` copies the file of the line to `<path>`, or to `perf-<line>.jfr` if it is a folder (not `~/.powerj/perf/` by default, as first planned: an option with an optional value does not exist, and a path is clearer). It writes a file, so `previewable` is `false` for it; it excludes `--view` and `--all`.
- **Files**: temporary (`powerj-perf-*` folder, `deleteOnExit`), kept in `PerfReport.recording`; `Session` deletes the file of a report it drops (more than 50 lines).
- **Footer**: one more line names the three hottest methods of the bottleneck stage (else of the stage with the most samples).
- **Image**: `powerj-dist/pom.xml`: `--add-modules java.se,jdk.unsupported,jdk.jfr,jdk.management` for jlink (the image is 97 MB with them), **and** `--java-options --add-modules=jdk.jfr,jdk.management` for the launcher. The second is needed: the core requires `jdk.jfr` statically, and a `requires static` module is not resolved at run time unless asked. Found by driving the built launcher in a pseudo-terminal, where the unit tests (classpath) had passed.
- **Optional dependency**: `requires static jdk.jfr` in `powerj-core`; `PerfJfr.available()` (no type of the module) tells whether it is resolved. Where it is not, or when a recording cannot start, the line is measured with the counters only, and the three views and `--save` give a non-blocking message. `Interpreter.perfProfiler(false)` (package-private) turns it off, for the tests of that path.
- **JDK 25 options**, not used: CPU-time profiling (JEP 509, experimental, Linux only) and method timing/tracing (JEP 520) could refine `hot` later.
- **Tests**: `PerfTest` (core): a busy stage is found hot in its own thread and in the calling thread, footer line, allocations per type, garbage collections, recording file readable, the line's time does not include the profiler, no profiler, deletion of the file of a dropped report; `PerfTest` (cmdlets): the views, `--save` to a file and a folder, errors, preview. CI: the launcher's runtime lists `jdk.jfr` and `jdk.management` and its configuration holds the `--add-modules` option.

## Tests
- **core**:
  - stage counters: `in`/`out` on `ls | where | first`; `starved`/`blocked` against a deliberately slow consumer and a slow producer; `active` = wall − starved − blocked within a tolerance;
  - the bottleneck is the slow stage; single-stage line; native stage and native group; cancelled line;
  - blocks: calls, total, first call;
  - phases and JVM deltas present; no probe means the unchanged behaviour (existing tests);
  - preview and `&` are not measured; `$perf`.
- **cmdlets** (`PerfTest`, simulated context): each view, `<n>`, `--all`, no report, `preview`.
- **shell**: Alt+P toggles the mode and the prompt, Alt+V/Alt+P exclusion, footer after a line, no footer for `perf` itself, redirection leaves the file clean, Ctrl+C footer.
- Keep coverage ≥ 80% (NFR-05).

## Verification
- `./mvnw -pl powerj-core,powerj-cmdlets,powerj-shell -am test`, then `./mvnw verify` (JDK 25).
- Manual: Alt+P, then `ls -r / | where { $_.size > 1mb } | sort size -d | first 5`; `perf`; `perf --view blocks`; `perf --view lines --all`; `git log | first 3`; `help perf`.

## Open questions
1. **Always-on stage counters?** The counters are cheap (no per-object timing), so `perf` could work after the fact on any line, without the mode. Proposed: no, measure only in perf mode (nothing runs otherwise); easy to change.
2. **Prompt marker** `PJ%`: acceptable?
3. **History size**: 50 lines, key `perf.history` in `config.properties`?
4. **Scripts and CI**: a `--perf-report out.json` option for `powerj -c` / script mode in a later iteration?
5. **Display of the report** to the console only (proposed), or also to `$errors`-like stream so it can be redirected?
6. **Part B in this iteration** (as written) or as iteration 26 right after part A? Splitting keeps the first delivery small.
7. ~~JFR always on in perf mode, or switchable?~~ Decided with the user: always on.
