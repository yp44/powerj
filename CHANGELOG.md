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
- `Language.set(Locale)` and `Language.resolve(...)` are removed from `io.powerj.api`: a cmdlet reads the language
  (`Language.current()`, `Language.text(...)`) and can no longer change it. The language is written by the shell only, once,
  at startup, through `io.powerj.api.internal.LanguageSetting`, a package exported to the built-in modules and to no other
  (#79). No built-in cmdlet and no example called them; only the tests of the built-in modules did.

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

### Added and changed (wildcards, [#51](https://github.com/yp44/powerj/issues/51))
- **Wildcards in the arguments of a native program** (FR-40, no longer marked partial). On Linux and macOS, a word written
  without quotes that contains `*`, `?` or `[a-c]` is replaced by the names of the files it matches before the program
  starts: `^echo *.txt` prints the names. Every element of the path may hold a wildcard (`src/*/pom.xml`), the names are
  sorted, a word without match is passed as written, a hidden file needs a dot in the pattern, a name that starts with
  `-` is passed as `./-name`, and a quoted text, a variable, a group or a block is never expanded. On Windows the program
  expands its own wildcards. The expansion is not contextual, as in a Unix shell; the cmdlets keep expanding their own
  path arguments, whatever the quoting, and a wildcard that is not a path (`ps java*`, `ls --filter *.java`,
  `import java.util.*`) stays theirs. The analysis and the options that were not taken are in
  [docs/plans/glob-expansion.md](docs/plans/glob-expansion.md).
- **Wildcards keep case by default, in the cmdlets as well as in the native programs** (behaviour change; review C-22).
  `ls *.txt`, `ls --filter`, `cat` and `grep` used to ignore case and no longer match `NOTES.TXT`; `rm`, `cp`, `mv` and
  `touch` were already case-sensitive off Windows. The environment variable **`POWERJ_GLOB_IGNORE_CASE`** (`true` or `1`)
  makes every wildcard ignore case, read at each use, so `env --set POWERJ_GLOB_IGNORE_CASE=true` applies at once; any
  other value is an error where a wildcard is expanded. `ps` always ignores case (FR-74). On Windows the matcher of the
  platform ignores case and the variable has no effect.
- `powerj-fs`: `GlobExpander` (the expansion), `Globs.hasWildcard` (a shell also sees `[a-c]`, which `Globs.isPattern`
  does not), `Globs.ignoreCase` and `Globs.IGNORE_CASE_VARIABLE`; **`Globs.matcher(pattern)` now keeps case**, ask for
  `Globs.matcher(pattern, true)` to ignore it. `powerj-core` now requires `io.powerj.fs`.
- Tests: the expansion (match, no match, quoted word, variable, hidden files, case on Linux, a name with a space, a name
  that starts with `-`, a folder in the pattern, the current folder of the session), the variable and its wrong values in
  the native commands and in `ls`, `grep` and `rm`; `ps` unchanged. A smoke test of the packaged launcher
  (`ci/smoke/wildcards.sh`, in the CI and release workflows) checks that the module is in the image. Acceptance sheet:
  [docs/acceptance/wildcards.md](docs/acceptance/wildcards.md).

### Added and changed (compound words, [#73](https://github.com/yp44/powerj/issues/73))
- **Pieces written without a blank are one word** (new requirement FR-32c; amends FR-32b, FR-40, FR-62 and the grammar).
  A piece is plain text, a text in double quotes or a variable: `"my docs"/*`, `$dir/*.txt`, `--message="a b"`, `x"y"z`
  used to be two or three arguments, silently, and are one now. A word of one piece is as before (a lone variable keeps its
  value and its type). A variable is a piece at the start of a word or right after a quoted text or a variable; in the
  middle of plain text `$` stays a plain character (`grep foo$`). A blank, `;`, `|`, `>`, `&&`, `&`, a group, a block and a
  Java call end the word; the name of a command is never glued; a line that starts with a text is still an expression.
- **Only the plain-text pieces are wildcards**, so `^rm -r "my docs"/*` acts inside `my docs`, and `^echo "x"/*` never
  lists `/`: the second word `/*` was expanded against the root folder since #51 (the hazard found while documenting
  that change). `"a*b"*` matches the names that start with the text `a*b`. The cmdlets receive the one argument
  (`ls "my docs"/*.txt` works) and still expand what they receive. `~` is the home folder at the start of an unquoted first
  piece followed by a slash (`~/"x"`). The target of a redirection is such a word too (`"x" > "my docs"/out.txt`).
- Inside: `Token.Compound`, `Ast.CompoundArgument` with `TextPiece` and `ExpressionPiece`, `Evaluator.Word` (the value, and
  the wildcard pattern), `Globs.escape` and `Globs.unescape`; `Globs.hasWildcard` and `GlobExpander` know the backslash
  escape of a pattern (`GlobExpander.expand(pattern, text, …)`). In an unquoted word the backslash is still a plain
  character (FR-32b).
- Not done: Tab completion after a text in quotes that is closed (`"my docs"/su<Tab>` proposes nothing, as before; inside the
  quotes it works). Tests: lexer, parser, native commands, `Globs`, `GlobExpander`; acceptance rows 19 to 29 of
  [docs/acceptance/wildcards.md](docs/acceptance/wildcards.md); `ci/smoke/wildcards.sh`.

### Fixed and changed (preview of `sort` and `group`, [#74](https://github.com/yp44/powerj/issues/74))
- **`ls -r | sort size` and `ls -r | group size` showed `no result` in preview mode** when the walk took longer than the
  deadline of 400 ms: a stage that emits only when its input has ended had nothing to show when the time limit stopped
  it, and the empty list was displayed as "no result". Measured on 20 000 files, the same on `b64c4b6` and on `main`: the
  code had not regressed, the tree the preview ran in had grown. Fixed in two ways (FR-66):
  - **Each stage but the last sends at most 50 objects to the next one in a preview** (`StageLimit`), so the blocking
    stage ends its input and emits; `ls -r | group size` answers at once on a tree of any size. The banner says
    `partial · 50 objects max per stage` as soon as a stage was cut, since a count or a sort made of the first objects is
    not the real one. The last stage is not limited, and executing the line (Enter) is not limited.
  - **A preview stopped by the time limit before any value says so** (`PreviewResult.TimedOut`) instead of `no result`,
    and offers the two settings: `── timed out after 400 ms · [duration 400 ms]  max/stage 50 · Alt+◄► choose, Alt+▲▼
    change ───`. Alt+◄► choose a setting, Alt+▲▼ change it by a step (200 to 5000 ms, 10 to 5000 objects), and the line is
    previewed again, without leaving the preview mode. The same menu is available under the banner of values, partial or
    not: the first Alt+arrow shows it and changes nothing, the next ones change the settings; typing closes it. The keys
    act while values or a timeout are shown, otherwise they keep the action they had; ▲ and ▼ alone are still the history.
- **Two settings of the session environment**, read at each evaluation: `POWERJ_PREVIEW_DEADLINE_MS` (400 by default,
  5000 at most) and `POWERJ_PREVIEW_STAGE_LIMIT` (50 by default). `LatestWinsRunner` asks its deadline at the start of each
  evaluation.
- **An interruption that the pipeline hands back instead of throwing** (it joins its stages without letting itself be
  interrupted) is a timeout too; it used to give an empty result.
- Tests: `PreviewSettingsTest`, `PreviewModeTest` (the limit, a stage that sends exactly the limit, the last stage, the
  limit read at each preview, no limit outside a preview, the timeout), `LatestWinsRunnerTest` (a deadline that changes
  between requests), `PreviewRenderTest`, `PreviewControlTest` and `PreviewControlKeysTest` (keys in a terminal,
  the history with ▲, the keys that keep their action). Acceptance rows 18 to 26 of
  [docs/acceptance/preview.md](docs/acceptance/preview.md).

### Fixed (completion of names that start with a dot, [#82](https://github.com/yp44/powerj/issues/82))
- **Tab completion offers hidden files and folders when the name is not quoted.** `ls .gi`, `cat .gi`, `ls ./.h` and
  `ls ~/.h` offered nothing, while the same names inside quotes worked. For a word that follows a dot, completion asks
  whether the text before the dot names a Java class; for a name that starts with a dot that text is empty (or a dot), and
  `JavaClasses.find` threw an index out of bounds, which completion swallowed. `find` now answers "no such class" for a text
  that is empty, made of dots, or has an empty piece (`a..b`, `java.`).
- Completion still never breaks the input, but what it swallows is now logged at `FINE` with the line, so that "nothing to
  offer" can be told from "failed".
- Not changed: `cd ..` and `ls ..` still offer nothing (the completer does not propose `..` as `../`). That is a missing
  feature, not an error.

### Fixed (a test that counted profiler samples, [#101](https://github.com/yp44/powerj/issues/101))
- **The macOS job no longer fails at random on `PerfTest.aPeriodUnderTheProfilersMinimumBecomesOneMillisecond`.** The test
  asked for more than 40 samples of the profiler in 300 ms of work, which needs one sample per 7.5 ms; on the macOS runners
  the profiler gave about one every 11 ms (22 to 32 samples, 60 at best), so the test passed or failed by chance, on `main`
  too. That a period under the profiler's minimum is raised to 1 ms is checked where it is decided, with a fake profiler
  (`PerfModeTest.aPeriodUnderTheMinimumBecomesTheMinimum`); the test with the real profiler now checks that the user is told
  and that the profiler still samples. Only a test changed.

### Fixed (a walk that meets a folder it cannot list to the end, [#83](https://github.com/yp44/powerj/issues/83))
- **`FileWalker` no longer stops the whole walk when the listing of a folder fails in the middle.** Reading a folder can
  fail after some entries were read (a network file system that drops, an entry that goes bad); the Java API then throws
  the unchecked `DirectoryIteratorException`, which `FileWalker` did not catch, although its Javadoc says that errors
  go to the error consumer and the walk goes on. A sequential walk threw it to the caller, a parallel walk stopped
  everything and threw it back, so `ls -r` and the other tree walks (`cat`, `grep`) failed outright. The folder is now
  reported as a non-blocking error (`WalkError`, kind `IO`), the entries read before the failure are kept, and the
  rest of the tree is walked. `FileTrees` (`rm -r`, `cp -r`) already behaved so.
- Tests: `FaultyFileSystem`, a wrapper of the file system of the machine whose listing of chosen folders fails after N
  entries, drives `FileWalkerListingFailureTest` (sequential and parallel, failure before the first entry and in the
  middle) and `FileTreesListingFailureTest`.

### Fixed and changed (javac warnings must not go up, [#84](https://github.com/yp44/powerj/issues/84))
- **The Linux job of the CI fails when a module has more javac warnings than the repository allows.** `-Xlint:all`
  reports its warnings without failing the build, and `docs/quality/lint-baseline.md` said that their number could only
  go down; nothing checked it, and it had gone from 29 to 30 with the preview work (`StageLimit.Full`, no
  `serialVersionUID`). `ci/check-javac-warnings.sh` now counts the warnings of the log of the clean build by module and
  by source set (`main`, `test`) and compares them with `docs/quality/javac-warnings.txt`. A count above its line fails,
  with the warnings listed; a count below it is reported so that the line is lowered; a log without any compilation is
  refused. You can run it on your machine (see the baseline document).
- **The seven `[serial]` warnings are gone** (30 to 23): `serialVersionUID` on `PreviewGuard.Denied`, `PreviewMode.PreviewFull`,
  `StageLimit.Full`, `JsonParser.JsonException` and `Properties.Unreadable`; `PjException` no longer holds a `PjError` (not
  serializable, so a `PjException` could not be serialized) and rebuilds `error()` from its message and its cause, with
  the same value.

### Fixed (`ConfigFile.set` escapes what it writes, [#93](https://github.com/yp44/powerj/issues/93))
- **A setting recorded in `config.properties` is read back as it was written.** `ConfigFile.set` wrote `key=value` as it
  was, but the file is read as a properties file, where a backslash starts an escape and a line break ends the entry.
  A value with a Windows path (`C:\users\yves`) made the file unreadable (`Malformed \uxxxx encoding`, the failure of
  review finding C-11), a value with a line break added a line, and a character that cannot be encoded made the write
  fail. No caller writes such a value today (the consent of a module is `sha256:<hex>` or `false`), so nothing was
  broken yet; the next setting that holds a path would have been. The value (and the key) is now escaped as
  `Properties.store` does it: backslash, line breaks, tab, form feed, a leading space, control characters, a surrogate
  that is not part of a pair; a key also escapes its spaces and `=`, `:`, `#`, `!`. Letters of any language are written
  as they are (the file is UTF-8). The lines written today are unchanged byte for byte.
- `ConfigFile.keyOf`, which finds the line of a key to replace it, reads `\n`, `\t`, `\r`, `\f` and `\uXXXX` in a key,
  as `Properties.load` does, so that a key written escaped is found again and replaced, not repeated.

### Fixed (the log no longer holds the lines you type, [#91](https://github.com/yp44/powerj/issues/91))
- **The diagnostic log keeps the name of a command, never its line.** `Supervisor` wrote the whole line when a command was
  abandoned (second Ctrl+C, WARNING) or failed with an internal error (SEVERE), and `Completions` the line being typed when
  completion failed (`FINE`, added with #82). A secret typed as an argument (`curl -u user:password …`) ended up in a file
  that is meant to be attached to bug reports, and a line that starts with a space, which the history does not keep on
  purpose (FR-10), was then in the log and nowhere else. The log now says `curl [truncated...]`, and `[truncated...]` alone
  for a line that does not start with a command name (an assignment, a string, a Java expression, a number, a path), at
  every level. `LoggedLine` decides; the message shown on the terminal when a command is abandoned is unchanged.
  The limit is written in `SECURITY.md`: a word typed alone is a name whatever it is.
- FR-60, the README and `SECURITY.md` say what the log and the history hold.

### Decided (the shared output helpers go to a new module, `powerj-text`, [#78](https://github.com/yp44/powerj/issues/78))
- **A decision, no code yet.** The output contract (`@Display`, `@Bytes`, `@Highlight`) is read again in `core`, `cmdlets` and
  `fx`, a size and a duration are formatted in several places that already give different texts, the units of a size are
  parsed twice and two wrappers guard a regular expression. The helpers go to a **new module `powerj-text`**
  (`io.powerj.text`, depends on `powerj-api` and the JDK only), not to `powerj-api`: the public surface of the API does not
  grow, so this is not tied to the first release. The reasons and what is not retained are in
  [ADR 0010](docs/adr/0010-shared-output-helpers-live-in-powerj-text.md), which amends ADR 0003.
- The work is split in five issues: the module itself ([#107](https://github.com/yp44/powerj/issues/107)), then `RecordView`
  ([#108](https://github.com/yp44/powerj/issues/108)), the formatting of sizes, dates and durations
  ([#109](https://github.com/yp44/powerj/issues/109), which also removes [#97](https://github.com/yp44/powerj/issues/97)),
  the parser of the units of a size ([#110](https://github.com/yp44/powerj/issues/110)) and `GuardedText`
  ([#111](https://github.com/yp44/powerj/issues/111)).

### Changed (a cmdlet cannot change the language, [#79](https://github.com/yp44/powerj/issues/79))
- **`Language.set` and `Language.resolve` leave the public API.** Any third-party cmdlet could call
  `Language.set(Locale.FRENCH)` and change the language of the whole shell. The state and the policy move to
  `io.powerj.api.internal.LanguageSetting`, in a package that `module-info.java` exports to `io.powerj.core`,
  `io.powerj.cmdlets`, `io.powerj.fx` and `io.powerj.shell` and to no one else. The order of resolution is unchanged
  (`-Dpowerj.language`, `POWERJ_LANG`, `language=` of `config.properties`, then the language of the system), and so is
  `Language.current()`.
- Tests: `LanguageSurfaceTest` fails if `Language` gets a public `set` or `resolve` again, or if the package of the setter
  is exported to a module outside that list; the snapshot of `ApiSurfaceTest` loses the two methods, and the internal
  package is not in it. The tests of the built-in modules that try both languages call `LanguageSetting.set`.
- The static field `current` is still not `final` (the one frozen entry of the ArchUnit rule): it is now
  `LanguageSetting.current`, and a `Locale` per `Session` stays an item of [#63](https://github.com/yp44/powerj/issues/63).
- `io.powerj.fx` is loaded from `~/.powerj/modules` in its own layer: it is named in the list for its tests, which run in
  one layer with the others. At run time it only reads the language, which needs no export.

### Changed (an integer that overflows is an error, [#80](https://github.com/yp44/powerj/issues/80))
- **`100000 * 100000` no longer prints `1410065408`.** The operators of a block (`+ - * / %`) followed Java, which keeps the
  low 32 bits when an `int` does not hold the result (and the low 64 for a `long`): `2147483647 + 1` was negative, silently,
  in a shell that is used as a calculator for sizes and counts. A result that does not fit is now an error that names the
  operation and the way out: `calculation failed: 100000 * 100000 does not fit in an int (write 100000L * 100000 to compute
  with long)`, and `… does not fit in a long` for a `long`. It covers `+`, `-`, `*`, the one division that can overflow
  (`Integer.MIN_VALUE / -1`) and the unary minus of the smallest number; a division by zero keeps its own message.
- **Behavior change**, so a line that relied on the wrap now fails. What does not change: a result that fits, a `long`
  operand (`100000L * 100000`, a literal too big for an `int`, the unit literals `1gb * 4`), decimals, `BigInteger` and
  `BigDecimal`. `measure` already summed exactly (`BigDecimal`); no other cmdlet adds numbers by itself.
- The rule is in FR-33. It is done with `Math.addExact` and its siblings in a helper of `Operators`, which also shortens
  `arithmetic`; the rest of its complexity stays with [#94](https://github.com/yp44/powerj/issues/94).

### Changed (the preview says when a line reads files, [#81](https://github.com/yp44/powerj/issues/81))
- **The policy of the preview judges `Files.readString`, `readAllBytes`, `readAllLines`, `lines`, `list`, `walk` and `find` by
  their name, whatever the path.** The line runs while it is typed, so `Files.readAllBytes(Path.of("/dev/zero"))` can exhaust
  the memory and a FIFO can block until the deadline. A rule on the argument is not made (decision of #81: document the
  limit, warn); FR-66 ("Reading files") and `SECURITY.md` now state the limit.
- **The banner warns.** As soon as the line has used one of those seven calls, even from a lambda or a stage in another
  thread, the banner of values and the banner of the timeout add `⚠ reads files as you type` (`⚠ lit des fichiers pendant la
  frappe`). `PreviewPolicy.readsFiles` says which calls, `PreviewGuard` remembers it, and `PreviewResult.Values` and
  `TimedOut` carry it to the shell. A line that only looks at files (`exists`, `size`, attributes) is not warned.
- Not done on purpose: the warning does not follow `next`, `forEach` and `stream` on iterators and collections, which the
  policy also allows on any `Iterator` or `Iterable`: `List.of(…).stream()` is everywhere and a warning on it would be read
  by no one. The limit is written in FR-66. A rule on the argument stays a possible later issue.

### Changed and fixed (the operating system is read in one place, [#86](https://github.com/yp44/powerj/issues/86))
- **`io.powerj.fs.Host` is the one class that looks at `os.name`.** `core.nativeexec.Platform` (lower-cased name,
  `startsWith("windows")`) and `cmdlets.HostOs` (`startsWith("Windows")`) said the same thing twice and disagreed about
  macOS; both are gone. `Host.windows()`, `Host.mac()`, `Host.namesIgnoreCase()` (Windows and macOS: yes, Linux: no) and
  `Host.home(environment, fallback)`, which `SessionState.home()` and `ProtectedPaths` now share instead of each reading
  `HOME`, then `USERPROFILE`, then the fallback. The name of the system is a parameter of package-private variants, so a test
  tries Windows, macOS, Linux and others whatever the machine. This reopens, for new reasons, a point of "Examined, not
  changed" (the home folder in two places); the other decision there, no injectable `Platform`, is kept.
- **Fixed: Tab completion of a path ignores case on macOS.** `doc` + Tab offers `Docs/` on Windows and macOS (it did on Windows
  only), as the protected paths of `rm` and `mv` were already compared without regard to case on both: the file systems
  of those two systems do not tell the two names apart. On Linux case counts, as before. FR-25 states it.
- Not changed: the wildcards (`*.TXT`, FR-40) keep their own rule (case counts unless `POWERJ_GLOB_IGNORE_CASE` says
  otherwise). The folder of `~/.powerj` is still found from the JVM's `user.home` (`shell.Environment`), not from `HOME`:
  taking `HOME` there would move the configuration of whoever sets it to something else.

### Added (the module `powerj-text`, [#107](https://github.com/yp44/powerj/issues/107))
- **A new module, `powerj-text` (`io.powerj.text`), empty on purpose.** It depends on `powerj-api` and the JDK only, and is
  where `core`, `cmdlets` and `fx` will share what they each wrote a copy of (decision of #78, ADR 0010):
  [#108](https://github.com/yp44/powerj/issues/108) the reading of a record,
  [#109](https://github.com/yp44/powerj/issues/109) the formatting of sizes, dates and durations,
  [#110](https://github.com/yp44/powerj/issues/110) the units of a size,
  [#111](https://github.com/yp44/powerj/issues/111) the guard of a regular expression. Each moves in its own pull request.
  It has its own message bundle (English and French) and a module-local `Messages`, ready for the texts of #109.
- **Wired everywhere before it has content:** in the build and in the coverage report (a floor from the start); in the
  runtime image of `powerj-dist`, which a new smoke test (`ci/smoke/modules.sh`, and a step of the Windows job) checks on
  the three systems, so that `powerj-fx` and any module loaded later find it in the boot layer; in the architecture tests
  (`textDependsOnlyOnTheJdkAndTheApi`, and `fxDependsOnlyOnTheApi` becomes `fxDependsOnlyOnTheApiAndText`).
- **Not API.** Its package is exported to every module, since `powerj-fx` is loaded in a layer where a qualified export
  would not reach it, so a third-party module can read it; nothing in it is promised to stay. ADR 0010 says so.

### Fixed (Tab completes the paths of `rm`, `cp`, `mv`, `mkdir` and `touch`, [#118](https://github.com/yp44/powerj/issues/118))
- **`rm f` + Tab offered nothing, where `ls f` offered `files/` and `fichier.txt`.** The five cmdlets declare their argument
  as `List<Object>`, because it takes a path or a block `{ … }`, and the completer only knew `Path`, `String` and lists of
  them. It cannot take every `Object`: `sort`, `select`, `kill`, `group`, `where`, `map` and `measure` also have one, and
  it is a key, a column or a condition.
- **`@Option` gets a member, `path`** (`false` by default): "Tab completes file paths for this argument". The five cmdlets set
  it on their first positional argument, and `ArgumentCompleter` reads it. A third-party cmdlet can use it too. This adds a
  member to the public API, compatible: the snapshot of `ApiSurfaceTest` has one line more.
- Not changed: `cd f` + Tab still offers files as well as folders (another cause: `cd` is a built-in command that gets the
  generic completion of paths).

### Fixed (one text for a size, a date and a duration, [#109](https://github.com/yp44/powerj/issues/109), [#97](https://github.com/yp44/powerj/issues/97))
- **The grid shows a `Duration` as the terminal does.** `fx.model.CellFormatter` said `1:30` and `0.000 s` for 2 µs where the
  terminal said `1 min 30 s` and `2.0 µs`, and it wrote `1,500 s` in French where the terminal wrote `1.500 s`. The sizes
  (`humanBytes`) and the dates were copied word for word in `core` and in `fx`.
- **One class, `io.powerj.text.Formats`** (`bytes`, `duration`, `dateTime`), the first helper to move into `powerj-text`. `core`
  (`Values`, `OutputFormatter`) and `fx` (`CellFormatter`) call it and lose their copies; the `duration.*` texts move to the bundle of
  the module. `core` and `fx` now require `io.powerj.text`; in the image `powerj-fx` finds it in the boot layer.
- **Behavior change, in the grid only:** `1:30` → `1 min 30 s`, `0.000 s` → `2.0 µs` for a micro-duration, `1,500 s` → `1.500 s` in
  French. The terminal is unchanged. A duration keeps its decimal point in every language, on purpose: its text is also what a script
  interpolates or passes as an argument. A size keeps the separator of the language, as before.
- Tests: the same table of durations is checked in `powerj-text` (`FormatsTest`, both languages), in `core` (`ValuesTest`) and in `fx`
  (`CellFormatterTest`): three modules, one text.

### Changed (one reading of `@Display`, `@Bytes` and `@Highlight`, [#108](https://github.com/yp44/powerj/issues/108))
- **`io.powerj.text.RecordView` reads the output contract of a record once per class** (cached by `ClassValue`): the components, the
  columns that `@Display` names (`displayed()`, `declaresDisplay()`), the sizes that `@Bytes` marks and the `List<Span>` component
  that `@Highlight` points at. The terminal (`OutputFormatter`), the grid (`ObjectReader`, `ColumnKind`) and the exports and
  properties of the cmdlets (`cmdlets.RecordShape`) used to interpret the annotations each on their own; they now ask the view.
  What each does with it stays its own: the terminal shows a table of at most five components or a list, the grid adds the
  other components after the ones of `@Display`.
- **The view holds structure, not accessors.** Making an accessor accessible is allowed to the module that asks, and a record of a
  third-party module is opened to `io.powerj.core` only (`ModuleLoader`): a view that made the accessors accessible from
  `powerj-text` would read none of them. So `core`, `cmdlets` and `fx` keep their own accessors. That is why `core.java.RecordShape`
  (case-insensitive access by name, for properties) stays as it is: it holds accessors only, and reads no annotation.
- No visible change. `cmdlets` now requires `io.powerj.text`.

### Changed (one definition of the units of a size, [#110](https://github.com/yp44/powerj/issues/110))
- **`io.powerj.text.SizeUnits` says what `b kb mb gb tb` are worth** (multiples of 1024, any case): `power`, `factor`, `bytes` and the
  alternation `PATTERN` to build a regular expression. The unit literals of the language (`core.lang.Units`: `2.5mb`) and the filter of
  a column of the grid (`fx.model.FilterParser`: `>2,5 mb`) used to know the list of units and the powers of 1024 each on their own.
- **What stays different on purpose:** the syntax. A literal is `digits[.digits]unit`, with the unit required, and is rounded to the byte
  as a `long`; a filter also takes a sign, a comma, a space before the unit and no unit at all, and compares the exact value. Only the
  value of a unit is shared.
- The architecture rule on `core.lang` (the language needs nothing of the engine) now also allows `powerj-text`, a leaf that depends
  on the API and the JDK only: `langDependsOnlyOnTheJdkTheApiAndText`.
- No visible change. The same table of factors (`b` 1 … `tb` 1099511627776, any case, `pb` and `kib` refused) is checked in
  `powerj-text` (`SizeUnitsTest`), in `core` (`UnitsTest`) and in `fx` (`FilterParserTest`).

### Changed (one guarded text for regular expressions, [#111](https://github.com/yp44/powerj/issues/111))
- **`io.powerj.text.GuardedText` is the one `CharSequence` that lets a regular expression be stopped.** The matcher reads its input
  through `charAt`; the text asks a guard (`LongPredicate`, given the number of reads so far) whether to go on, and throws
  `GuardedText.Stopped` when it says no. `cmdlets.InterruptibleText` (the interrupt flag, looked at every 1,024 reads) and
  `fx.model.RegexFilter.Budgeted` (a budget of reads) were two copies of it; they are now `GuardedText.interruptible(text)` and
  `GuardedText.withBudget(text, steps)`, and a third-party cmdlet that runs a user's regular expression can use the same two.
- `Stopped` is a `CancellationException` without a stack trace. `grep` and `range` still end with a cancellation on Ctrl+C (the
  tests pass unchanged); the filter `/regex/` of the grid still gives up on a cell that spends its budget and tells the status bar.
- No visible change. The same pattern, `(.*a){30}`, is stopped by the interrupt flag and by the budget in `powerj-text`
  (`GuardedTextTest`), and still stops `grep` and `range` (`CtrlCStopsRegexTest`, formerly `InterruptibleTextTest`).

### Changed (one test context and one driver for the tests of the cmdlets, [#98](https://github.com/yp44/powerj/issues/98))
- **New module `powerj-testing`, for tests only.** `io.powerj.testing.TestContext` is the in-memory `CmdletContext` (emitted objects,
  errors, environment, current directory, state, input, perf reports, compilation), and `CmdletDriver.run(cmdlet, params, context,
  inputs…)` calls `begin`, `process` for each object and `end`, then closes the state, as the shell does: it sends no more objects once
  the cmdlet has stopped its input (`TestContext.objectsSent` says how many were sent). It depends on `powerj-api` only, is used with
  `test` scope, and is neither installed, deployed, shipped in the image nor counted in the coverage (as `powerj-architecture`).
- **The two copies of `TestContext` (`powerj-cmdlets`, `powerj-fx`) and sixteen local drivers are gone**: `Measure`, `Cancellation`,
  `PsKill`, `Range`, `FirstLast`, `Grep` (two), `KnownDefects`, `Join`, `Select`, `Sort`, `FileOps`, `Group`, `Cat`, `WhereMapCollect`,
  `JsonCsvCmdlets` and `GridCmdlet` each wrote the same loop. The grid test only keeps what is its own: a context with input and a
  `$_.name` read by reflection. The tests are the same (2,565 before, 2,579 after: the 14 of the new module).
- Left alone, on purpose: `FakeCmdlets` in `powerj-core` (test cmdlets, not a context) and the long duplicated blocks inside
  `InterpreterCharacterizationTest`, `PerfTest` of the core, `CmdletContextTest` and `PerfModeTest` / `PreviewControlKeysTest` /
  `PreviewModeTest`: they are not drivers of a cmdlet.

### Changed (`NativeRunner` sets a process up in one place, [#87](https://github.com/yp44/powerj/issues/87))
- **`NativeRunner.newBuilder(Command, Session)` is the one place where a child process gets its command line, the directory of
  the session and its environment.** `run`, `runGroup` and `startBackground` each wrote the three lines; the rule that must not
  drift, *a child process never inherits the environment of PowerJ* (`environment().clear()` then the snapshot of the session),
  is now written once. The background command is the same `Command` run by `sh` (`shielded`), not a longer command line built
  apart. The error `native.startFailed` of a group is built by one `startFailed(commands, e)` (it was written in `startGroup`
  and in `startBackground`).
- No visible change. The redirections stay where they are: they differ on purpose between the three ways to start. A new test
  of `InterpreterTest` checks the environment rule for a group of natives and for a background command, where only a single
  command was checked; removing the `clear()` makes it fail.

### Changed (background commands in their own process group, [#128](https://github.com/yp44/powerj/issues/128))
- **A command started with `&` now leads a process group of its own, so the Ctrl+C of the terminal never reaches it, on
  Linux, macOS and Windows.** `NativeRunner.startBackground` hands the job to `BackgroundLauncher`, which starts it through the
  FFM API (already used, already enabled in the launcher): `posix_spawn` with `POSIX_SPAWN_SETPGROUP` on Linux (glibc 2.34
  or later) and macOS, `CreateProcessW` with `CREATE_NEW_PROCESS_GROUP` on Windows. There is no `sh` in between and the pid is
  the program's. On Windows a console program in the background no longer dies of the Ctrl+C of the console: that was the known
  limit of FR-75. A pipeline is one process group on Unix; each stage has its own on Windows.
- **The `sh` wrapper (`trap '' INT QUIT; exec`) stays as a fallback only**, for a Unix system that cannot use `posix_spawn` that
  way (glibc older than 2.34, musl), and only when the session variable `POWERJ_BACKGROUND_SH_FALLBACK` is `true`. Without it,
  a background command on such a system is refused with a message that names the variable. A Windows batch file (`.bat`, `.cmd`)
  is still started by `ProcessBuilder`, without protection: `cmd.exe` reads its command line by other rules.
- **Behaviour that changes on Unix, as in a shell with job control**: a background command that reads the terminal (`/dev/tty`:
  `ssh`, `sudo`) or, after `stty tostop`, writes to it, is stopped by the terminal (a stopped process keeps `SIGTERM` pending, so
  `kill` now sends `SIGCONT` after it, and the job ends); `kill -INT <pid>` sent straight to a job now kills it, where the `sh` wrapper ignored it; and a signal
  sent to the process group of the shell no longer reaches the jobs (the `SIGHUP` of a closed terminal, the signal by which a CI
  job or `timeout` aborts a script by its group): a job outlives the terminal or the script that started it, as with `nohup`. The test
  `InterpreterTest.backgroundCommandGivesControlBackAtOnce` checks the group, no longer the ignored signal.
- **On Windows the arguments of a background command are quoted correctly**: an argument that holds a `"` reaches the program
  as written, where `ProcessBuilder` splits it. Measured on the CI: 12 tricky arguments out of 12 against 4. A start is also
  faster (7 ms against 16 ms for `cmd /c exit 0`).
- Measures and decision: [docs/plans/background-process-group.md](docs/plans/background-process-group.md); checklist:
  [docs/acceptance/background.md](docs/acceptance/background.md).

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
