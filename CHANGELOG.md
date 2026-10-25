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

### Changed (Phase 2, the `Interpreter` split)
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
  owns the mode, the profiler settings and the report. The profiler (Java Flight
  Recorder) is found through a `ServiceLoader`, so the shell names no `jdk.jfr` type outside it.
- **The profiler's recordings are removed by one hook at exit** instead of a `deleteOnExit` per measured line, which
  made the JVM remember every line of a long session.
- **Preview mode and the shell's own commands are out of the interpreter** (step 2.6): `PreviewMode` runs the line
  through the same executor as execution; `which`, `import`, `mod-load` and `mod-list` join the other built-in
  commands in `Builtins`. `Interpreter` is down to 250 lines (it was 1,129).
- **`Session` is made of three parts** (step 2.7): `SessionState` (directories and environment), `Variables` (what the
  user defines) and `ShellStatistics` (last result, recent errors, measured lines). Its API is unchanged; the values of
  the automatic variables live in `AutomaticVariable`.
- The frozen ArchUnit rules no longer fail when a refactoring only changes the signature of a method already in a cycle, the
  tally of its report, or which of the (many) `exec` classes that use the syntax tree the report happens to list. The
  dependency that closes the `exec`/`lang` cycle (`lang` reaching into `exec`) is still compared line by line.

### Changed (Phase 3, packages, modules and API)
- **`powerj-core` is split in packages by layer** (step 3.1): `error`, `i18n`, `lang`, `format`, `java`, `nativeexec`,
  `command`, `runtime`, `perf`, `assist` replace `core.exec`. Nothing changes for the user. `powerj-core` exports its
  packages to the shell only, `powerj-shell` exports nothing, `powerj-cmdlets` exports to the shell only (until the
  shell stops using `ProtectedPaths`): no package of the product is visible to a third-party module any more.
- The architecture rules check the layers of the core (a lower layer never reaches into a higher one) instead of
  freezing a report of package cycles; the debt that exists is frozen line by line.
- **The language depends on nothing else of the product** (step 3.2): `SyntaxException` is no longer a `PjException`
  (the interpreter converts it), `Ast` no longer knows `Token`, and what the reader needs besides the text is a typed
  `ParseContext` instead of a `Predicate<String>` that was opened with `instanceof`.
- **The layers of the core have no debt left** (step 3.2): the rule that checks them, and the one that forbids cycles
  between the packages, run unfrozen. The interpreter receives the perf mode through `LineMeasuring` instead of
  building it; `Help` is a command; the engine of cmdlets and of native commands is part of the runtime.
- **Tab completion no longer calls the getters of your objects** (step 3.3): typing `$x.property.` read the property of
  the real value through its getter, so a getter with a side effect (a lazy loader, `getInputStream()`) ran while you
  typed. It now reads only what cannot do anything: map keys, record components, public fields and the getters that
  the preview policy knows to be pure; every other getter is typed by its signature.
- **A string with an interpolation of strings of its own is one string** for the parser, the highlighting and the
  completion (step 3.3): in `git commit -m "on $(f("y")) done"` the highlighting cut the string at the inner quote, and a
  `}`, `|` or `;` inside the inner string closed a block or started a stage for the completion.
- **`Completions` is split** (1,006 → 173 lines) into the code that decides what the cursor is on and six classes that
  suggest (commands, arguments, paths, names, members, packages); the inference of types has its own class. Completion and
  highlighting read the commands through a `CommandCatalog`, no longer through the concrete `Interpreter`, and the
  architecture rule that says so is no longer frozen. The preview policy is an ordered list of rules (`PreviewRule`),
  tested one by one.
- **API for cmdlet authors** (step 3.4):
  - `ScriptBlock.tryInvoke` and `tryTest` return an `Attempt` (a value, or the error that the object caused) and still
    throw a cancellation: a cmdlet no longer has to rethrow Ctrl+C by hand to keep it from being taken for an error.
  - `UsageException` (an `IllegalArgumentException`) says that a cmdlet was used incorrectly; `CmdletContext.property`
    gives the block that reads a property; `CmdletContext.service(Class)` offers what only a few cmdlets need.
  - The optional abilities of a cmdlet are interfaces, `Previewable` and `ColumnProvider`; the matching default methods
    of `Cmdlet` (`previewable`, `columns`) are deprecated for removal and still honoured.
  - The perf types move from `io.powerj.api` to `io.powerj.api.perf`.
- Compiler warnings: 72 → 32.

### Breaking changes of the API (for code written against the 0.1.0-SNAPSHOT, which was never released)

- `CmdletContext.environment()` is a read-only copy; use `setEnvironment` and `unsetEnvironment` to change the
  environment of the session (a cmdlet that wrote to the map now gets an `UnsupportedOperationException`).
- `CmdletContext.perfReports()` is removed: `context.service(PerfReports.class)`. `PerfReport`, `StageStat`,
  `BlockStat`, `NativeStat`, `PhaseStat`, `JvmStat`, `HotMethod`, `GcStat`, `AllocStat` and `LineStat` are in
  `io.powerj.api.perf`.

### Examined, not changed
- **The seven `Messages` classes stay** (C-10, step 1.4). Replacing them with one helper in `powerj-api` was tried on a
  two-module experiment: `ResourceBundle.getBundle(…, module)` called from another module fails unless the owner of the
  bundle opens its package to it, which every module (third-party ones included) would have to declare. The per-module
  class is the minimal form that works with JPMS, and the model `examples/greet` gives to module authors.

### Planned (review §8)
Phases 1 to 3 are done (reports in the review, §12 to §14). The API members deprecated in 0.2 (`Cmdlet.previewable`,
`Cmdlet.columns`) are removed no sooner than 0.3.0.
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
