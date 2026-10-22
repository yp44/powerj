# Plan — perf mode (Alt+P) and `perf` cmdlet (mini-iteration 25, FR-76)

## Context
Users cannot see where a pipeline spends its time. Stages run in parallel (virtual threads, bounded queues), so the duration of a stage alone means nothing: a fast stage looks slow when it waits for its neighbours. Only `$last.duration` exists, for the last native command.

Decisions taken with the user:
- a **mode**, entered with **Alt+P**, which prints statistics **at the end of each pipeline**;
- a **`perf` cmdlet** that gives **more detail afterwards** and returns **many rows** that a pipeline can process (`perf | sort active -d | first 3`).

The iteration has two parts, in two commits series: **part A** (counters, mode, cmdlet) and **part B** (JFR, section "Part B — JFR" below). Part A works alone; part B adds the answer to "why is this stage slow".

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
- `<n>`: the n-th measured line back, `1` by default. `--all`: every kept line; each row carries its `line` number.
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
  - `PerfRecorder`: one per measured line; owns the stage probes and the phase timers, and builds the `PerfReport`. `Session` keeps a ring of the last 50 reports and exposes them (`perfReports()`).
  - `Pipe`: when a probe is attached, `put` and `next` try `offer`/`poll` first and time only the call that has to block; they also count objects. Without a probe the code is unchanged.
  - `Interpreter`: times `parse`, `resolve` (`segments()`, arguments and subexpressions included), `execute`; wraps the sink of the last stage to time the `display`; creates one probe per segment and takes the wall time of each (`segment()`); covers the single-stage fast path (no pipes: wall time, objects out through the sink). Native groups: one `NativeStat` per process from `NativeRun`.
  - Blocks: `CompiledBlock` counts calls and times them when a probe is bound. The probe of the current stage is carried by a `ScopedValue` propagated to the stage threads, like `PreviewGuard.propagate`.
  - JVM: snapshot before and after the line from `GarbageCollectorMXBean`, `CompilationMXBean`, `ClassLoadingMXBean`; heap peak from the heap `MemoryPoolMXBean`s after `resetPeakUsage()`. Needs `requires java.management;` in `module-info.java` (in `java.se`, so no change to the jlink image).
  - Automatic variable `$perf`: the last `PerfReport` (empty before any measure).
  - `Supervisor`/`Outcome`: unchanged. `Cancelled` still returns the report built so far.
- **`powerj-cmdlets`**: `Perf.java` (`@CmdletInfo(name = "perf", category = "System", preview = true)`), registered in `BuiltinCmdlets`; messages `perf.summary`, `perf.option.*`, `perf.none` in `messages_en`/`_fr`.
- **`powerj-shell`**
  - `Perfer` (like `Previewer`): Alt+P widget, state, footer rendering with `OutputFormatter` from `Row`s.
  - `Repl.prompt()`: `PJ%` in perf mode; the footer is printed after the output, before the prompt.
  - Alt+P is bound by JLine's emacs keymap to `history-search-backward` (to check): it is rebound, as ↑/↓ already are in `ShellReader`. A test pins the binding.
  - Alt+V and Alt+P switch each other off.
- **Completion**: `--view` values, and `perf` rows' properties after the `perf` stage (the records are known types).
- Docs and README in the implementation commits: FR-76 (this commit), `docs/recettes/perf.md`, §11 row 25, scope sentence (~l.408), README (feature section after Preview mode, cmdlets table).

## Part B — JFR
Counters say *which* stage is slow; Java Flight Recorder says *why* (hot methods, allocations, GC pauses), for about 1–2% overhead.

- **Recording**: entering perf mode starts one in-memory `jdk.jfr.Recording` (no disk, bounded by age and size) with the events `jdk.ExecutionSample`, `jdk.GarbageCollection`, `jdk.ObjectAllocationSample`, `jdk.ThreadCPULoad`; leaving the mode stops and discards it. Each measured line keeps its start and end instants, which select its events afterwards. Off the mode, JFR is not running.
- **Stage attribution**: the stage threads are named after their command (`powerj-etape-2:where` instead of `powerj-etape-2`), so samples are grouped per stage. To verify at implementation: that samples of a mounted virtual thread carry its name. If not, stages run on platform threads while the mode is on (at most about ten threads); the footer then notes it.
- **New views** of `perf`, built from the events of the line (read with `jdk.jfr.consumer.RecordingFile` on a dump of the window):

| View | Record | One row per |
|---|---|---|
| `hot` | `HotMethod(line, stage, method, samples, percent)` | method, most sampled first |
| `gc` | `GcStat(line, name, cause, pause)` | collection |
| `alloc` | `AllocStat(line, stage, type, bytes)` | allocated type (`@Bytes`) |

- **Export**: `perf --save [<file>]` writes the events of the line as a `.jfr` file, by default in `~/.powerj/perf/`, to open in JDK Mission Control or with `jfr print`.
- **Footer**: with JFR available, one more line names the three hottest methods of the bottleneck stage.
- **Image**: `powerj-dist/pom.xml` goes from `--add-modules java.se,jdk.unsupported` to `java.se,jdk.unsupported,jdk.jfr,jdk.management`. Measure the size of the image before and after, and give it in the PR.
- **Optional dependency**: `requires static jdk.jfr` in `powerj-core`. Where the module is absent (a custom runtime), the mode and part A work, and the JFR views give a non-blocking message.
- **JDK 25 options, to verify before use**: CPU-time profiling (JEP 509, experimental, Linux only) and method timing/tracing (JEP 520) could refine `hot`; they are not required.
- **Tests**: aggregation of `hot`/`gc`/`alloc` from a synthetic recording; the window of a line selects only its events; absent module; `--save` writes a readable file; shell test that the recording starts and stops with the mode. The CI launcher smoke test checks `bin/java --list-modules` contains `jdk.jfr`.

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
7. **JFR always on in perf mode** (proposed, to have the data when `perf --view hot` is asked after the fact), or only with an option such as `perf.jfr=off` in `config.properties` for those who want the lightest mode?
