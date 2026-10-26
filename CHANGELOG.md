# Changelog

All notable changes to PowerJ are recorded here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
the versions follow [Semantic Versioning](https://semver.org/). While the major version is 0, a minor version may change
the extension API (`powerj-api`). Nothing has been published yet, so until the first release a change of the API is listed
under "Breaking changes of the API" without a deprecation first; from the first release on, such a change is announced as
*deprecated* one minor version before it is removed.

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
  timeouts, concurrency, Dependabot; reproducible archive timestamps; the Maven wrapper checks its download.
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
    of `Cmdlet` (`previewable`, `columns`) are removed (see the breaking changes below).
  - The perf types move from `io.powerj.api` to `io.powerj.api.perf`.
- Compiler warnings: 72 → 29 (the shell no longer calls the deprecated `Terminal.getWidth()` and `Size(int, int)` of JLine).

### Breaking changes of the API (for code written against the 0.1.0-SNAPSHOT, which was never released)

- `CmdletContext.environment()` is a read-only copy; use `setEnvironment` and `unsetEnvironment` to change the
  environment of the session (a cmdlet that wrote to the map now gets an `UnsupportedOperationException`).
- `Cmdlet.previewable(P)` and `Cmdlet.columns(String)` are removed: a cmdlet that must refuse the options that change
  something implements `Previewable` (`canPreview`), one that emits `Row`s and names their columns implements
  `ColumnProvider` (`columnsOf`). They were deprecated in this same development cycle and kept for a moment; the shell
  no longer asks them, and a cmdlet that overrides them no longer compiles (`@Override` on a method that is gone). No
  built-in cmdlet and no example did.
- `CmdletContext.perfReports()` is removed: `context.service(PerfReports.class)`. `PerfReport`, `StageStat`,
  `BlockStat`, `NativeStat`, `PhaseStat`, `JvmStat`, `HotMethod`, `GcStat`, `AllocStat` and `LineStat` are in
  `io.powerj.api.perf`.

### Added, changed and fixed (Phase 4, composition root and shell)

- **`powerj --version` and `powerj --help`** answer without starting the shell and without creating the home folder.
- **A script run with `powerj file.pj` reads multi-line commands** like the interactive shell and `profile.pj` do: a
  pipeline that continues on the next line, or a block over several lines, was run one line at a time and failed (C-20).
- **The shell starts in one place** (step 4.1): `ShellBootstrap` reads the command line, finds the home folder once,
  installs the log, reads the settings, sets the language and builds the one session. `Main` is a call. What the shell
  reads from the process is gathered in `Environment`, and an architecture rule says nothing else reads it, so a shell
  can be started in a test with a folder and a few streams.
- **The modes are a State** (step 4.2): `ShellMode` and `ModeController` say that the preview and the perf modes exclude
  each other; they no longer know each other.
- **`Repl` is a read loop** (steps 4.3 and 4.4): the assembly (interpreter, completion, modes, prompt) is
  `InteractiveShell`, the prompt, the error lines and the profile have their own classes, the
  "latest request wins" monitor of the preview is `LatestWinsRunner`. A terminal that fails while a line is read ends the
  shell with code 1 instead of escaping, and the Ctrl+C handler is put back when the loop ends.
- **`config.properties` has one reader and one writer** (step 4.3): two settings recorded at the same time are both kept
  (a lock in the process; another process writing at the same moment is still not protected).
- **The shell no longer uses the classes of the cmdlets** (step 4.5): `CmdletProvider.initialize(configDirectory)` lets a
  module create its default files when the shell starts (the built-in cmdlets write the list of protected paths that way),
  and `powerj-cmdlets` exports its package to the core only.

### Added and changed (Phase 5, industrialisation)

- **Documents for contributors and users**: [CONTRIBUTING.md](CONTRIBUTING.md) (build, gates, habits, the language policy,
  how to add a cmdlet), [SECURITY.md](SECURITY.md) (what is a guard-rail and what is trusted, how to report),
  [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) (modules, layers, how a line runs, which test keeps each rule) and
  [docs/adr/](docs/adr/) (the decisions behind the structure).
- **The specification says what the code does** (C-35): the cmdlet count, the module list, JLine 4, the log file name, the
  CI jobs, the Java features that are used and those that are not, the installer (it does not add PowerJ to the `PATH`),
  the grammar and the synopses in English. **FR-16 (`native.prefer`) and `colors.cmdlet` are withdrawn**: they were
  specified, cited as escape hatches, and never built; `^name` is the way to a native program. **FR-40 is marked
  partial**: wildcards in the arguments of a native program are not expanded.
- **The consent to native access is tied to the content of the modules** (C-24): a yes is recorded as
  `modules.nativeAccess.<module>=sha256:<fingerprint>`, the SHA-256 of every module that would be loaded together, so a
  jar replaced by another one with the same module name, or a changed dependency, asks again; a `true` recorded by an
  earlier build is no longer an answer; the message that says why a module was not loaded gives the line to record
  (`NativeAccessPolicy.consent` takes the fingerprint: an internal interface). [ADR 0006](docs/adr/0006-consent-to-native-access-is-tied-to-the-content.md).
  Also fixed: the French message of that refusal showed `l''autoriser` (doubled apostrophe: the formatter does not
  unescape).
- **Reading a property of a record is 185 times faster** (C-26, measured with JMH): `$_.size` in a `where`, `map` or `sort`
  block cost 7 µs per object because `Class.getRecordComponents()` rebuilds its objects at every call; the accessors are now
  found once per class (`RecordShape`, in the core and in the cmdlets). `to-csv` reads the 8 columns of a row in 0.4 µs instead
  of 61 µs, `to-json` a record in 0.2 µs instead of 18 µs, and a static Java call takes 0.8 µs instead of 3.9 µs (the static
  methods of a class are found once per class). `powerj-bench` (`-Pbench`) holds the benchmarks, including the first Java
  call (28 ms, NFR-07) and one Tab (20 to 350 µs, NFR-02); the numbers and how to run them are in
  [docs/quality/performance.md](docs/quality/performance.md). Two tests give a million reads a budget that the uncached code
  cannot meet.
- **JSON and CSV are checked against the standards** (C-25): the 316 small files of the JSONTestSuite (all valid ones
  accepted, all invalid ones rejected with a position, the 35 that the RFC leaves open pinned one by one), RFC 4180 section by
  section, and random values written then read back. They found and fixed: a number that does not fit a double was read as
  `Infinity` (now an error); a `float` was written as the double nearest to it (`0.1f` gave `0.10000000149011612`); a lone
  surrogate was written raw and lost in the UTF-8 encoding (now a `\uXXXX` escape); a CSV record of one empty field was
  written as an empty line, which `from-csv` skips (now `""`). An object that is never closed no longer absorbs the whole
  input: a value of more than 64 Mi characters stops the reading with one error. The choices that the standards leave open
  are in the specification (FR-73) and in [ADR 0009](docs/adr/0009-json-and-csv-are-written-in-house.md); `to-json` still
  builds its array in memory (`--lines` streams). The byte order marks in the sources are written `\uFEFF`.
- **`SpecificationTraceabilityTest`**: every requirement of the specification is cited by the code, a test or a smoke
  script (unless it is marked withdrawn), nothing cites a requirement that does not exist, and the specification only
  mentions requirements it defines. Twelve requirements that nothing cited now are, `FR-04e` (a name the specification
  never had) became FR-34, and `CmdletNamesTest` checks the naming rule FR-12.
- **Mutation testing** (`-Pmutation`, PIT, outside the default build): `lang` and `runtime` of the core are killed at 90 %
  and 85 % (1 418 of 1 626 mutations), against the 60 % that the review asked for. The first run found two assertions that
  were missing and now exist: the ends of a character literal in the group scanner, and the environment of a native
  command (the child process must get the environment of the session and nothing of the JVM's). How to run it, the scores
  and how to read a survivor: [docs/quality/mutation.md](docs/quality/mutation.md).
- **`grid` at 100 000 rows** (C-29, NFR-04), measured on the model first (`GridScaleTest`, [numbers](docs/quality/performance.md)):
  a row is an array of values and a `RowShape` shared by the rows of one type instead of a map of its own, and the columns of a
  type are worked out once. Reading 100 000 objects takes a tenth of what it did, a row retains 387 bytes instead of 827 (155
  instead of 595 with plain values), and a row keeps its object only with `--pass-thru`. The sort, the filters and "tick all"
  take 0.03 to 0.9 s on the toolkit thread for 100 000 rows, as before.
- **A filter `/regex/` in `grid` cannot freeze the window any more** (C-18): the filter runs on the toolkit thread, which no
  Ctrl+C reaches. Each cell now has a budget of characters read; a cell that exhausts it does not match, after ten such cells
  the others are not tried, and the status bar says how many cells were not examined. (The JDK 25 matcher already answers
  the textbook `(a+)+$` at once; a pattern such as `(.*?,){11}P` on forty commas still takes minutes.)
- **The window of `grid` is tested, and usable without seeing colours** (C-29): `GridView` had no test at all. `GridViewUiTest`
  builds the real window under a virtual display (`xvfb-run -a ./mvnw -Pfx-ui -pl powerj-fx -am test`, a step of the CI; the
  ordinary build leaves these tests out) and checks that rows arrive, that Esc closes, that every field has an accessible name
  (`Filter of the column size`, `Search in all columns`…) and that an invalid filter is named in the status bar and in the help
  text of the field, not only by turning pink. It found a defect on its first run: an invalid expression keeps the last good
  filter, the rows do not change, and so the status bar was never refreshed; it is now. The decisions that were in `GridView`
  (the text of the status bar and the state of the pause button, what Esc does, when a column is rebuilt, how wide it is) moved
  to the model with tests (`GridStatus`, `EscapeAction`, `ColumnKind.sameLook`, `ColumnWidths.pixels`): 565 lines became 531,
  not the 300 aimed at, because what remains is JavaFX wiring.
- **The graphical module is no longer on the critical path of the build** (C-33): `./mvnw -DskipFx verify` builds and checks
  everything else (the reactor, the coverage and the architecture rules follow the switch; the two ArchUnit rules about
  `powerj-fx` accept to find nothing then). A platform without JavaFX artifacts, or a machine that only wants the shell, no
  longer has to build it. The archive of the distribution is named after the machine that builds it (`linux-x64`,
  `linux-aarch64`, `macos-x64`, `macos-aarch64`, `windows-x64`, `windows-aarch64`; the choice was checked by simulating each
  `os.name`/`os.arch`, only Linux x64 was really run).
- **A software bill of materials and checksums** (C-34): `./mvnw -Psbom -DskipTests package` writes `target/bom.json`
  (CycloneDX 1.6: the shell, its modules, JLine, and the graphical module with JavaFX); the distribution zip and the zip of
  the graphical module each get a `.sha256` file that `sha256sum -c` accepts.
- **The CI runs its checks side by side, and on macOS** (C-34): the build of Linux keeps the tests, the image and the smoke
  tests; the tests of the real window under a virtual display, and the build without the graphical module, are jobs of their
  own; a macOS job (Apple silicon) builds everything, runs the 2,142 tests, makes the image (`powerj.app`, which the archive
  step of `powerj-dist` now expects) and runs five of the smoke tests. Its first runs found seven things that were Linux
  only or depended on the load of the machine, all fixed: a test that took `cd` for a program that does not exist (macOS has
  a `/usr/bin/cd`), two that looked for a path in a table cut to its width (long temporary folders), one that created
  `readme.txt` and `README.txt` (the same file there), a race in the background Ctrl+C test, `jpackage` refusing a version
  that starts with 0 (the bundle says 1.x while the project is 0.x), and a smoke test that expected `root` on the first line
  of `/etc/passwd`. Not run on macOS: the profiler smoke test, the window of `grid`, the installer, Intel Macs. **The release
  workflow is new and unproven**: [`release.yml`](.github/workflows/release.yml) builds the three systems on a
  tag `vX.Y.Z`, checks that the tag is the version of the project and not a SNAPSHOT, and leaves a **draft** release with the
  archives, the installer, the SBOM and `SHA256SUMS`; it has never run, nothing is signed, and the procedure is in
  [docs/RELEASING.md](docs/RELEASING.md) (`versions:set` was tried on a copy).
- **Leftovers of the review, done** (step 5.10): `Where.PROPERTY` and `Where.abbreviate`, used from ten other classes, are
  `Texts.isPropertyPath` and `Texts.abbreviate`, and the rule of `-n` that `last` took from `first` is `Counts.orOne` (with
  tests, mutants killed). **The shell no longer `requires` the cmdlets module**: the image build adds it (`--add-modules`),
  the service loader finds it, and the seven smoke tests pass on the image. (`--bind-services` was tried and is not the way:
  it adds 28 modules, among them the compiler, `jlink`, `jpackage` and `jshell`, and takes the runtime from 97 to
  163 MB.) The message of the toolkit start-up timeout of the graphical module is in the bundles.
- **English names** for the acceptance sheets: `docs/recettes/etape-N.md` is now `docs/acceptance/step-N.md`; the failure
  messages of the CI and the description of the project are in English too (C-36).

### Examined, not changed
- **Left as they are in step 5.10, with the reason**: a `Platform` that tests can inject (the Windows job of the CI runs the
  whole suite on a real Windows, which tests the Windows branches better than a simulated platform would, and nine classes
  would change for it); the home folder found in two places (12 lines in the core and in the cmdlets, which do not know each
  other; sharing them needs a type in the API for a helper); `Option.min` (the two validations that repeat are one function
  each, `FileOps.parallelism` and `Counts.orOne`; an annotation member would change the API and remove no code);
  `FileOps.report(WalkError)` next to `FileArguments.report` (the first says what could not be modified, the second what
  could not be read: two wordings); the lock of `config.properties` between processes (a case of two shells writing the same
  consent at the same moment); the English exception messages of `HistoryExpander`, `SortSpec` and `ColumnChoice` (they
  never reach the user: the line is left as it is, the callers wrap them in a bundled message).
- **The `jpackage` version of a SNAPSHOT build** (C-33): jpackage only accepts digits, so a `0.2.0-SNAPSHOT` image carries
  `0.2.0`. What tells the builds apart is `powerj --version` (`PowerJ 0.2.0-SNAPSHOT (Java 25)`) and the name of the archive;
  an installer built from a snapshot is not meant to be distributed.
- **A toolchain for the runtime image** (C-33): the image is made with the `jlink` and `jpackage` of the JDK that runs
  Maven, which the Enforcer already requires to be 25 or newer; a second JDK to select adds a configuration to maintain and
  no guarantee. The release workflow pins the JDK (step 5.9).
- **Reproducible Ant archives** (C-33): the two zip files made by Ant (distribution, graphical module) keep the date of their
  files; `project.build.outputTimestamp` covers the archives of the Maven plugins only. Not done.
- **The seven `Messages` classes stay** (C-10, step 1.4). Replacing them with one helper in `powerj-api` was tried on a
  two-module experiment: `ResourceBundle.getBundle(…, module)` called from another module fails unless the owner of the
  bundle opens its package to it, which every module (third-party ones included) would have to declare. The per-module
  class is the minimal form that works with JPMS, and the model `examples/greet` gives to module authors.

### Planned (review §8)
**The backlog is kept in [GitHub issues](https://github.com/yp44/powerj/issues)** (see [CONTRIBUTING.md](CONTRIBUTING.md#the-backlog));
this section only says where things stood when 0.2.0 was prepared. Phases 1 to 5 are done (reports in the review, §12 to
§16). What is open, and who decides it, is listed in §16.4 of the review and has one issue each:
- **The first release** ([#47](https://github.com/yp44/powerj/issues/47)) **and how to sign it**
  ([#48](https://github.com/yp44/powerj/issues/48)). The release workflow is written ([docs/RELEASING.md](docs/RELEASING.md))
  and has never run; nothing is signed. Tagging, and the way to sign, are the maintainer's decisions.
- **Code scanning** ([#50](https://github.com/yp44/powerj/issues/50)). A CodeQL workflow was added in Phase 0 and taken out
  again on the maintainer's request, to be looked at later (it is in the history, commit `cf487bb`).
- **Wildcards in the arguments of a native program** ([#51](https://github.com/yp44/powerj/issues/51); FR-40, marked partial).
- **Private vulnerability reporting** ([#49](https://github.com/yp44/powerj/issues/49)) has to be switched on in the
  settings of the repository (`SECURITY.md` relies on it).
- **Phase 6**: control structures and functions ([#52](https://github.com/yp44/powerj/issues/52)), on the foundations of
  the phases above.
- Smaller items, each with its issue: `select --expand` ([#53](https://github.com/yp44/powerj/issues/53)), `to-json` in
  memory ([#54](https://github.com/yp44/powerj/issues/54)), the grid ([#55](https://github.com/yp44/powerj/issues/55)),
  `ExpressionParser` ([#56](https://github.com/yp44/powerj/issues/56)), japicmp
  ([#57](https://github.com/yp44/powerj/issues/57)), reproducible Ant archives
  ([#58](https://github.com/yp44/powerj/issues/58)), mutation testing ([#59](https://github.com/yp44/powerj/issues/59)),
  CI and platforms ([#60](https://github.com/yp44/powerj/issues/60)), a screen reader on the grid
  ([#61](https://github.com/yp44/powerj/issues/61)), the status of each requirement
  ([#62](https://github.com/yp44/powerj/issues/62)), small leftovers ([#63](https://github.com/yp44/powerj/issues/63)).

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
