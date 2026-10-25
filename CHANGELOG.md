# Changelog

All notable changes to PowerJ are recorded here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
the versions follow [Semantic Versioning](https://semver.org/). While the major version is 0, a minor version may change
the extension API (`powerj-api`); such a change is announced as *deprecated* one minor version before it is removed.

## [0.2.0] — in development (`0.2.0-SNAPSHOT`)

**Theme: make the foundations safe to change.** 0.1.0 delivered the whole v1 feature set; 0.2.0 does not add commands, it
removes the structural debt found by the [architecture review](docs/REVUE-ARCHITECTURE.md) so that v2 (control
structures, functions) can be built on it. The work is organised in phases (review, §8).

### Added (Phase 0, the safety net — done)
- **Tests that pin today's behaviour** before the code is moved: 77 lines of `Interpreter.execute` (output, errors, `$?`,
  exit request), direct tests of `Pipe`, `CmdletRunner`, `PreviewGuard`/`PreviewPolicy`, `Jobs`, and the first tests of
  `powerj-api` (23), among them a snapshot of its public surface that fails when the extension API changes.
- **Architecture rules** (ArchUnit, module `powerj-architecture`): module dependencies, no cycles, only `Main` ends the
  JVM, every cmdlet declared and registered. Violations that exist today are frozen and may only disappear.
- **Per-module coverage floors** next to the aggregate threshold; a default 60 s timeout per test.
- **Static analysis**: `-Xlint:all`, Spotless, stricter Maven Enforcer rules, an opt-in Error Prone profile
  (`-Pquality`); the baselines are in `docs/quality/`.
- **CI**: the smoke tests are scripts (`ci/smoke/*.sh`) that run on a developer's machine too; actions pinned by commit,
  timeouts, concurrency, Dependabot, CodeQL; reproducible archive timestamps; the Maven wrapper checks its download.
- Nine tests that demonstrate defects of the review, committed red and `@Disabled` with the phase that fixes each.

### Changed
- `powerj-cmdlets` declares `requires transitive io.powerj.api` (its public types expose the API's).
- Tests wait for a state or a signal instead of a duration; the thread-leak check of the Ctrl+C pipeline test, which
  could not fail, now can.

### Fixed (Phase 1, targeted fixes — done)
- **`System.exit` can no longer end the shell by another road** (review C-12, step 1.1). The guard used to be written for
  the direct call only; `[0] | map System::exit` or `Method.invoke` ended the JVM. It is now a `CallPolicy` consulted by
  `JavaInvoker`, the one place every Java call goes through. `System.exit("x")` now reports the usual "no overload"
  error instead of a dedicated message.
- **A malformed `config.properties` no longer prevents startup** (C-11, step 1.2). A Windows path written with single
  backslashes (`C:\users`) made `Properties.load` throw, and the shell died with a stack trace. The file is now ignored
  with a one-line warning that names it and says why; the same for a file that is not UTF-8. A malformed file no longer
  stops the consent for native modules from being asked.
- **`Session` can be read by the stages of a pipeline while it changes** (C-13, step 1.2). Copying the environment or
  listing the variables while another thread changed them threw `ConcurrentModificationException` (reproduced by a
  stress test: 343,385 failures in 472,431 copies of the environment over two seconds). Variables are in a concurrent
  map, the state written by the command thread is `volatile`, the environment is a synchronized map, and a native
  command starts from an immutable snapshot of it. The automatic variables (`$?`, `$exit`, `$last`…) are an `enum`, not a `switch` on strings.
- **A cmdlet's per-run state belongs to the run, and is released when the run ends** (C-01, step 1.3). A cmdlet is one
  object shared by every command that uses it, so 23 of them kept their state in tables keyed by the context or by the
  (value-compared) parameters: a lock for every object, a leak when the command was cancelled, a collision between two
  `where` stages with equal parameters. `CmdletContext.state(Class, Supplier)` (additive, `default`) gives each run
  its own state; the runner closes the states that are `AutoCloseable` when the run ends, normally or not (Ctrl+C
  included). The 23 tables are gone. `grid` closes the window of a command that was cancelled before it handed it over.
- **A stream unrolled by `*.` is closed** (C-07, step 1.5): `Files.lines(p)*.length()` leaked a file descriptor. The rule
  for turning a value into the objects it stands for was written four times; it is now `ValueUnroller`, with one
  explicit mode for each place that differs (between stages, display, targets of `*.`).
- **`import` can no longer block a lookup of a class name** (C-14, step 1.5). A name lookup and `import` took two locks
  in opposite orders: a stress test deadlocked every time. The imports are now an immutable snapshot with its own cache.
- **Ctrl+C is no longer taken for an error by `to-json`** (C-15, step 1.6). `CancellationException` is an
  `IllegalStateException`, and `to-json` caught the latter around code that checks for cancellation: the command went
  on. It now catches a dedicated `Unreadable` type, so a cancellation cannot be swallowed there; `grid` and
  `to-csv` got the same treatment, and the contract is written in the Javadoc of `emit` and `ScriptBlock.invoke`.
- **`sort` has a total order** (C-16, step 1.6). Mixed values were compared through their text, which is not
  transitive (`3 < 20`, `20 < "25"`, `"25" < 3`), and `TimSort` could fail with "Comparison method violates its general
  contract". Values are now ordered by kind (null, numbers, strings, other comparables by class, the rest by text).
  Visible change: the error for mixed types says they are sorted by type.
- **Ctrl+C stops `grep` and `range` on a regular expression that backtracks for ever** (C-18, step 1.6, partly): the
  matcher reads its input through a `CharSequence` that checks the interrupt flag. The `/regex/` filter of `grid` is not
  covered yet (Phase 5).
- **Protected paths are compared as the file system sees them** (C-21, step 1.7). `alias/..`, an alias of `/home`, a
  protected entry that is a link: `Path.normalize` knew none of that. Links and `..` of the folders above a target are
  resolved before comparing, case is ignored on macOS too, and copying a folder into itself is detected through a link.
  `mv` without `-f` no longer uses an atomic move, which replaces the destination on Unix, and `perf --save` no longer
  replaces a file that exists. The guarantee is documented for what it is: a guard against a mistake, not an access
  control.
- **Wildcards of `rm`, `cp`, `mv` and `touch` respect case** except on Windows (C-22, step 1.7): `rm README*` no longer
  removes `readme.md` on Linux and macOS. `ls readme* | rm` gives the other rule. `ls`, `grep` and `cat` are unchanged.

### Changed (Phase 1, hygiene)
- Thread names are English: `powerj-command`, `powerj-preview`, `powerj-stage-N` (the profiler reads the stage number from
  the last one, through a shared constant now).
- Fully qualified names written inline are imports (94 places), `cmdlets` reads `os.name` in one place, `ls`, `cat` and
  `grep` share their report of walk errors and `ls`, `grep` the check of `--parallel`.
- A new test fails when `Messages.get("key")` names a key that no bundle of the module defines.

### Changed (Phase 2, the `Interpreter` split — in progress)
- **One place decides what a command name stands for** (step 2.1): built-in command, then cmdlet, then program on the
  `PATH`, `^` skipping the first two. Execution, `which`, highlighting and completion all read `CommandResolution`;
  completion no longer treats a cmdlet as such when a built-in command of the same name runs first. `help` lists the
  built-in commands the interpreter has, not a second list kept in `Help`.

- **The parameters that always travelled together are one object** (step 2.2): `ExecutionContext` and `Redirections`.
  No method of the execution has more than five parameters any more (there were eleven).
- **The pipeline is run by its own class** (step 2.3): `PipelineExecutor` plans and runs the stages, `ErrorReporter` says
  where the errors go and whether the line hit a blocking one. `Interpreter` goes from 1,129 to 623 lines. Nothing
  changes for the user: the 77 characterization cases are untouched.
- **The output of a statement and its redirections have their own classes** (step 2.4): `StatementOutput` writes the
  values to the terminal or to the file of `>`, `Redirections.target` evaluates the file a redirection names.
  `Interpreter` is down to 545 lines.
- **Perf mode no longer runs through the execution code** (step 2.5): the execution reports to an `ExecutionObserver`
  (a neutral one when nothing is measured), `Pipe` has no measuring branch (`MeasuredPipe` counts), and `PerfMode`
  owns the mode, the profiler settings and the report. `Interpreter` is down to 396 lines.
- **The profiler's recordings are removed by one hook at exit** instead of a `deleteOnExit` per measured line, which
  made the JVM remember every line of a long session.
- The frozen ArchUnit rules no longer fail when a refactoring only changes the signature of a method already in a cycle, the
  tally of its report, or which of the (many) `exec` classes that use the syntax tree the report happens to list. The
  dependency that closes the `exec`/`lang` cycle (`lang` reaching into `exec`) is still compared line by line.

### Examined, not changed
- **The seven `Messages` classes stay** (C-10, step 1.4). Replacing them with one helper in `powerj-api` was tried on a
  two-module experiment: `ResourceBundle.getBundle(…, module)` called from another module fails unless the owner of the
  bundle opens its package to it, which every module (third-party ones included) would have to declare. The per-module
  class is the minimal form that works with JPMS, and the model `examples/greet` gives to module authors.

### Planned (review §8)
Phase 1 is done (report in the review, §12).
- **Phase 2 — `Interpreter` split** into a command resolution, a pipeline executor, redirections, and
  observer-based perf and preview modes.
- **Phase 3 — packages, modules and API.** `core.exec` is split in eight packages with qualified exports, the
  package cycles are removed, performance types leave the public API. Deprecated API members are removed no sooner than
  0.3.0.
- **Phase 4 — composition root.** `ShellBootstrap`, `--version`/`--help`, a smaller `Repl`.
- **Phase 5 — industrialisation.** Release workflow, SBOM, mutation tests, benchmarks, documentation (ADR, C4).

## [0.1.0] — never released (baseline)

The functional baseline that 0.2.0 starts from; it was only ever built as `0.1.0-SNAPSHOT`.

- Interactive shell on JLine: history, Ctrl+R, Tab completion, syntax highlighting, continuation prompt; scripts
  (`powerj file.pj`, `-c`), profile, `~` expansion, English/French messages.
- Java as the expression language: `{ … }` blocks, lambdas, method references, direct access to the JRE API.
- Object pipeline, one virtual thread per stage with bounded queues; native commands and background jobs.
- 28 cmdlets: `ls`, `grep`, `cat`, `where`, `map`, `sort`, `first`, `last`, `range`, `select`, `count`, `measure`,
  `group`, `join`, `mkdir`, `touch`, `cp`, `mv`, `rm`, `to-json`, `from-json`, `to-csv`, `from-csv`, `ps`, `kill`,
  `collect`, `env`, `perf`.
- Preview mode (Alt+V) and perf mode (Alt+P) with the Java Flight Recorder.
- Third-party modules loaded from jars, one `ModuleLayer` each; consent for native access.
- Optional `powerj-fx` module with the `grid` cmdlet.
- Distribution as a self-contained image (jlink + jpackage), Windows installer, CI smoke tests on the packaged launcher.
