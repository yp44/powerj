# Plan — test coverage of 80% (NFR-05)

## Context
NFR-05 asked for 80% coverage of `powerj-core`, and §7 of the specification says that `mvn verify` measures the coverage with JaCoCo. Nothing measured it, though: no module declared JaCoCo.

Decisions taken with the user:
- **Scope:** PowerJ as a whole, and the built-in cmdlets (`io.powerj.cmdlets`), each at **80% or more**. `examples/greet` is not part of PowerJ.
- **Counters:** the **lines and the branches**, both at 80%.
- **Every test counts for every module.** For example, `BuiltinCmdletsTest` (shell) runs `ls` and so covers `Ls`. The shell tests drive the real cmdlets through the interpreter on purpose.
- **Enforced by every `./mvnw verify`**, locally and in both CI jobs.
- **Linux and Windows:** every test passes on both, and the threshold holds on both.
- **Java 25:** the project builds with JDK 25, which the documentation now names everywhere instead of JDK 27.

Branch: `claude/charming-gauss-qrmh4e`, from `main` @ 13628d1.

## Measure
- **Parent POM:** `jacoco-maven-plugin` 0.8.15 (`prepare-agent`). Every module writes the execution data of its tests to `target/jacoco.exec`; the agent reaches Surefire through `argLine`.
- **`powerj-coverage` module** (packaging `pom`, after `powerj-shell`), which depends on the five PowerJ modules. In order:
  1. `package`: `unpack-dependencies` copies the PowerJ classes to its `target/classes`. `jacoco:check` only reads the classes of its own module, and the jars hold the very bytes the tests ran.
  2. `package`: `jacoco:merge` gathers the `powerj-*/target/jacoco.exec` files into `target/coverage/jacoco.exec`.
  3. `verify`: `report-aggregate` writes the HTML, XML and CSV report (per module and in total) to `target/site/jacoco-aggregate/`. It comes before the check, so the report is there when the threshold is not reached.
  4. `verify`: `jacoco:check` applies two rules, each with `LINE` and `BRANCH` at `COVEREDRATIO` ≥ 0.80:
     - `BUNDLE` for the whole;
     - `PACKAGE io.powerj.cmdlets` for the cmdlets.
- **`-DskipTests`:** no execution data, so JaCoCo skips the merge and the check, and the build passes.
- **`-Djacoco.skip=true`:** turns the whole measure off.
- **CI:** the Linux job publishes the report as the `coverage-report` artifact, even when the build fails.

## Tests added
Starting point (Linux): lines 85.8% and branches **77.1%** in total; the cmdlets at 89.8% and 85.2%. Only the branches of the whole failed.

The tests went first where the most branches were missed. They follow the conventions of the existing tests: JUnit 5 and AssertJ, `@TempDir`, English messages. Paths are built with `Path`, line ends are normalized, and a test that depends on the system is skipped where it cannot run.
- **Core:**
  - `OperatorsTest`: arithmetic, comparisons, dates and durations.
  - `JavaInvokerTest`: overloads, conversions, varargs, constructors.
  - `EvaluatorTest`, `FunctionalAdapterTest`, `PropertiesAndOptionsTest`, `BuiltinsAndJavaNamesTest` and `InterpreterEdgeCasesTest`.
  - `CompletionOptionValuesTest`: enum and `Path` option values.
  - More cases for the lexer, the parsers and the completion.
- **Cmdlets:**
  - `WhereMapCollectTest` drives `where`, `map` and `collect` directly.
  - `ValueFormatsTest` covers the sort order and the CSV/JSON text of values.
  - More cases in the existing tests: the errors of `cp`/`mv`, JSON parsing, `grep`, `kill`, `env`, `range`, `measure` and `from-csv`.
- **Shell and fs:**
  - `PreviewRenderTest` and `DiagnosticLogTest`;
  - the completion line of `ShellParser`, the profile and the module warnings of the REPL and of the script mode;
  - the walk errors of `FileWalker`.

Result on Linux: lines **93.0%**, branches **88.6%** in total; the cmdlets at 94.1% and 92.1%. The margin is kept for Windows, where the tests that need `sh` are skipped.

`Main` stays uncovered: it is the entry point (`System.exit`, real terminal and standard input). The smoke tests of the launcher exercise it in the CI.

## Found while testing (not changed here)
- **Completion of a word starting with a dot.** `ls .h`, a hidden file as an argument, proposes nothing: `JavaClasses.find("")` throws a `StringIndexOutOfBoundsException` (`qualified` reads the first character of an empty name), and the completion swallows it. Quoted, `ls ".h` works.
- **`mv -f` onto an existing non-empty folder.** On Linux it fails with "Directory not empty" instead of merging. The atomic move throws a plain `FileSystemException`, not the `DirectoryNotEmptyException` that `Transfer.move` catches to merge by copying.

## Verification
- `./mvnw verify` with JDK 25, twice: all the tests pass, and the check reports both rules met.
- With the threshold raised to 0.99 locally, the build fails and names the counters.
- `./mvnw verify -DskipTests` still passes.
- The CI workflow, run on the branch, is green on Linux and on Windows.
