# Plan — profiling and benchmarking readiness (mini-iteration 26, FR-77)

## Context
Requested by the user together with perf mode: make the project compatible with Java benchmarking / profiling tools, explain in the documentation how to check **accurate CPU and memory usage**, and check whether the code needed changes to make that easy.

## Review findings (before the change)
- The `-Pdist` image was built with `--add-modules java.se,jdk.unsupported`: no `jdk.jfr`, `jdk.management*`, `jdk.jcmd`, `jdk.attach` (no Flight Recorder, no attach); jlink stripped the Java debug attributes (no line numbers in profiles); no documented way to pass JVM options to the launcher.
- Thread names: French and English mixed (`powerj-commande`, `-apercu`, `-etape-N`); unnamed threads: the preview deadline watcher, the shutdown hook, the parallel file-walk workers (`ls -r -p`, `cp -r -p`…).
- No custom JFR events, no JMH, no startup measurement, no profiling documentation.
- No JIT or assertion flags anywhere; `maven-compiler-plugin` keeps `-g` (line numbers) by default.

## Changes
- `powerj-dist/pom.xml`: properties `dist.add.modules` (adds the six observability modules), `dist.jlink.options` (debug attributes kept), `dist.extra.args` (extra jpackage args, e.g. JVM options).
- Thread names, all `powerj-*` in English: `powerj-command`, `powerj-stage-N`, `powerj-preview`, `powerj-preview-deadline`, `powerj-walk-N`, `powerj-shutdown` (the test that checks no stage thread leaks follows the new name).
- JFR events (FR-76b) in `powerj-core`, `requires static jdk.jfr`.
- `powerj-bench` (JMH 1.37, shaded `benchmarks.jar`), profile `bench` of the parent pom: lexer, parser, planner, `ls -r | count`, a full pipeline; forks with `-XX:+DebugNonSafepoints`.
- `docs/profiling.md`: the guide (tool choice, JVM options for the packaged launcher, `DebugNonSafepoints`, JFR and async-profiler for CPU, wall vs CPU with virtual threads, heap / allocation / NMT / RSS for memory, JMH, startup, pitfalls); README section; FR-77 and NFR-08 in the specification.
- CI: smoke step on the packaged image (modules present, a JFR recording of a `-c` run contains `powerj.Stage`).

## Left out
- AppCDS / AOT cache for the image (already NFR-01), JMX MBeans, Micrometer, startup-phase events.

## Verification
- `./mvnw verify` (tests, 80 % coverage); `PerfTest` reads a real recording.
- `./mvnw -Pbench -DskipTests package` builds `benchmarks.jar`; a short run was done.
- `./mvnw -Pdist verify` and the CI smoke step (not runnable in the authoring sandbox: its JDK has no `jmods`).
- Acceptance checklist: `docs/recettes/profiling.md`.
