# Plan — Flight Recorder in perf mode (mini-iteration 27, FR-78)

## Context
Request: in perf mode, attach JFR automatically to each execution and analyse the file at the end to give relevant information, command by command. Decisions taken with the user:
- **one recording per line**, started right before it runs, analysed right after;
- extract **CPU hot methods, allocations, GC and memory, waiting/blocking**;
- retention driven by an **environment variable** (0 delete after analysis, 1 keep the last, N keep the last N) so that it can be set in `profile.pj` with `env --set`; named `POWERJ_PERF_KEEP`;
- a **summary under the report** plus a **`perf` command** returning objects for the detail.

No attach is needed: the Flight Recorder is driven in-process (`jdk.jfr.Recording`) and read back with `jdk.jfr.consumer.RecordingFile`.

## Design
- `powerj-core`:
  - `PerfProfiler` (facade without any `jdk.jfr` type: loads without the module) → `JfrProfiler` (recording + analysis). Events enabled in code, no `.jfc`: `ExecutionSample` (period `POWERJ_PERF_PERIOD`, default 1 ms), `ObjectAllocationSample`, `GarbageCollection`, `GCHeapSummary`, `ThreadPark`, `ThreadSleep`, `JavaMonitorEnter/Wait`, `FileRead/Write` (≥ 1 ms), `VirtualThreadPinned`, `powerj.Command`, `powerj.Stage`.
  - Attribution: each `powerj.Stage` event carries its thread (and now its statement number and position, so the stages come out in pipeline order, not start order); any other event is attributed to the stage with the same thread whose window contains it. **Checked by `PerfProfileTest`: virtual-thread stages are attributed correctly** (the risk identified when planning).
  - `PerfProfile` records (`@Display` columns) for the line, the stages, the hot methods, allocation sites, waits, GC.
  - `PerfFiles`: file names that sort by date, retention, parsing of `POWERJ_PERF_KEEP`.
- `powerj-shell`: `Repl` starts the recording before the line when perf mode is on, finishes it after the report (failures only produce a gray note), keeps the last profile; `PerfMode.profile` formats the summary; built-in `perf` (`--stages`, `--allocations`, `--waits`, `--gc`, `--file`); `perf.profile.*` messages (en/fr).
- Dist: `-XX:+DebugNonSafepoints` in the default `dist.extra.args`.

## Limits
- CPU is a statistical estimate (samples × period; period from `POWERJ_PERF_PERIOD`, default 1 ms), flagged when fewer than 20 samples, per line and per stage. Allocation is sampled.
- Added after the first real runs: hidden JVM classes are skipped in hot-method frames; the line's waiting counts only the stages' threads (the others are the `(other)` row of `perf --waits`); the stage line no longer loses the separator before the hot methods (a `.properties` value loses its leading spaces).
- A line is recorded, then analysed, before the next prompt: ~50–200 ms of extra latency in perf mode only.
- Samples outside any stage (parsing, display) count in the line only.

## Tests
`PerfProfileTest` (core): CPU to the burning stage with its hot method and line, allocations and sleep waits to their stages, GC counted, short line flagged, retention for 0/1/N, parsing of the variable. `PerfModeTest` (shell): summary after the report, `perf --stages` / `--gc` / unknown option, files kept follow `env --set POWERJ_PERF_KEEP`, nothing recorded outside perf mode.
