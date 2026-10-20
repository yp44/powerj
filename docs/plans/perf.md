# Plan — perf mode (mini-iteration 25, FR-76 and FR-76b)

## Context
Requested by the user: a mode next to preview (Alt+V) that shows the **runtime plan** of a line while it is edited and, once it has run, **performance figures** for every executed command and for the whole line. Decisions taken with the user:
- name **Perf mode**, key **Alt+P**, prompt `PJ⏱` (alternatives considered: Trace, Explain, Insight);
- the plan is the **resolved stages** (what each command resolves to, where it runs, how statements are chained): static, nothing is run or evaluated, so it can be shown on every key;
- the figures are **wall-clock time per stage, per statement and for the line**, plus objects in / out per stage (and time to the first value, which exposes stages that wait for their whole input);
- shown **below the prompt** as a persistent mode, like preview; the report is printed after the output of the command.
Defaults chosen: perf and preview exclude each other (they share the area under the line); the word "profile" is avoided (already `profile.pj`); no CPU or memory in the report (those are the JVM tools, see `docs/profiling.md`).

Added at the user's request: the project must work well with Java profiling and benchmarking tools — mini-iteration 26, see `profiling.md`.

## Design
- `powerj-core`:
  - `PerfPlan` (`Empty`, `Incomplete`, `Statements` of `Statement` of `Stage(text, kind, placement)`); `Interpreter.plan(line)` parses and resolves command **names** (`commandKind`, as highlighting does), never arguments (evaluating an argument could run code). Consecutive native commands form one stage, as `segments()` groups them.
  - `PerfRecorder` / `PerfReport`: installed with `Interpreter.record(recorder)` for one line. `execute` creates a `StatementTimer` per statement; `pipeline` takes it (only the statement's own pipeline is timed, not pipelines nested in an argument or a preview) and creates one `StageTimer` per segment, checked against the plan (if the counts differ the statement is not timed rather than wrong). `segment()` starts/ends the timer and counts objects through a wrapping `Source` (in) and `Consumer` (out). Cost without a recorder: one null test per segment.
  - `Profiling` / `JfrSpans`: Flight Recorder events `powerj.Command` and `powerj.Stage` (FR-76b), reusing the same timers (a non-retaining recorder counts the objects when a recording asks for the events). `jdk.jfr` is `requires static`: without the module, `Profiling` selects no-op spans and `JfrSpans` is never loaded. Events carry command names only.
- `powerj-shell`:
  - `PerfMode`: Alt+P widget, plan rendering in the `post` area through `PreviewLineReader` (which now accepts several line listeners), report formatting; `Previewer.onEnable/off` and `PerfMode.onEnable/off` give the mutual exclusion; `Repl` installs a recorder before each line when the mode is on and prints the report after the outcome (success, failure, Ctrl+C).
  - Messages `perf.*` in `messages_en/fr.properties`.

## Limits (documented)
- Stage durations overlap; bytes between native commands are not counted; a failing statement is marked; a line interrupted by Ctrl+C reports the statements that had started.
- Interactive editor only (`-c` and scripts print no report).

## Tests
- `PerfTest` (core): plan of a pipeline (kinds, placements), connectors/assignment/unknown command, empty and broken lines, objects in/out per stage, statements skipped by `&&`, no output change without recorder, JFR events read back from a real recording (names only, no arguments).
- `PerfModeTest` (shell, in-memory terminal): Alt+P prompt, plan while typing, report after Enter, no report outside the mode, exclusion with Alt+V.
- Acceptance checklist: `docs/recettes/perf.md`.
