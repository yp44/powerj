# Acceptance test — Mini-iteration 27: Flight Recorder in perf mode (`perf`)

**Goal:** check that in perf mode each line is recorded and analysed automatically, that the summary gives CPU, allocation, GC and waiting for the line and for each stage, that the `perf` command returns the detail as objects, and that `POWERJ_PERF_KEEP` controls the files kept.

> Messages are shown in the system language; set `language=en` (or `POWERJ_LANG=en`) for the English texts quoted here. Needs a JDK 25 runtime with `jdk.jfr` (the packaged image has it).

## Preparation

In a folder with many files (e.g. a source tree of a few thousand files), `cd` into it. Check that `~/.powerj/perf/` is empty or absent.

## Scenario

| # | Action | Expected result |
|---|---|---|
| 1 | Press **Alt+P**, run `ls -r \| where { f -> f.size > 1kb } \| sort size \| count` | The result, the `── Perf · total …` report, then `jfr · cpu … ms (… %) · alloc … · gc … · wait …` and one line per stage (`ls`, `where`, `sort`, `count`) with `cpu`, `alloc`, `wait` and, for stages that compute, `hot <Class.method:line> <n> %`. |
| 2 | Look at the stages | The stage that computes (e.g. `where` with its block, `sort`) has the most `cpu`; a stage that mostly waits for its upstream stage shows a large `wait` and little `cpu`. |
| 3 | Run `1 + 1` | `only n sample(s): the figures are indicative` appears (short command). |
| 4 | Run `perf` | A table of hot methods: `stage`, `method`, `line`, `samples`, `percent`. |
| 5 | `perf --stages \| sort cpu` | One row per stage of the last line, sorted. |
| 6 | `perf --allocations`, `perf --waits`, `perf --gc` | Tables of allocation sites, waiting by cause (`park`, `sleep`…), collections. |
| 7 | `perf --bogus` | `perf: unknown option '--bogus' …`. |
| 8 | List `~/.powerj/perf/` | Empty: `POWERJ_PERF_KEEP` is not set, each file is deleted after its analysis. |
| 9 | `env --set POWERJ_PERF_KEEP=2`, then three lines in perf mode | Exactly 2 `perf-….jfr` files, the most recent ones. The summary of the last line ends with `recording <path>`; `perf --file` gives the same path. |
| 10 | `env --set POWERJ_PERF_KEEP=0`, run a line | The directory is emptied; `perf --file` says the recording was deleted. |
| 11 | Put `env --set POWERJ_PERF_KEEP=3` in `~/.powerj/profile.pj`, restart | The variable is set at startup; 3 files are kept. |
| 12 | Open a kept file: `jfr view hot-methods <file>` or JDK Mission Control | The same hot methods as the summary; `jfr print --events powerj.Stage <file>` shows the stages with `statement` and `position`. |
| 13 | Run `sh -c "sleep 2"` then Ctrl+C during it | `^C`, the report, and a summary (the recording is stopped and analysed in this case too). |
| 14 | Alt+P to leave the mode, run a line | No report, no summary, no file written; `perf` still answers with the last profile. |
| 15 | `env --set POWERJ_PERF_PERIOD=50`, run the same line of step 1, then `env --set POWERJ_PERF_PERIOD=1` and run it again | The low-samples note (when shown) says `at 50.0 ms` then `at 1.0 ms`; at 1 ms there are clearly more samples and the `cpu` figures are of the same order. A value such as `oops` or `0` behaves like `1`. |
| 16 | In the summary, look at a stage line with hot methods | The figures and `· hot …` are separated (no `µshot`); a stage with fewer than 20 samples shows `1/12`-style counts instead of percentages; no `0x…` class names; `wait` of the line never exceeds what the stages show. |
| 17 | Start with a runtime without `jdk.jfr` (slim image, see `docs/profiling.md`) | One note `jfr: Flight Recorder is not part of this runtime, nothing recorded`; the plan and the wall-clock report still work. |

## Notes

- Short commands have few samples (one per `POWERJ_PERF_PERIOD` ms, 1 by default): repeat them, use more data, or lower the period. The line and column of a hot method are exact only with `-XX:+DebugNonSafepoints`, set in the packaged launcher.
- The recording and analysis add some tens to a few hundred milliseconds before the next prompt, in perf mode only.
