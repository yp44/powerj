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
  Every module's floor is 80 % of lines and branches (`powerj-api` now measures 100 % with tests of the `perf` records and
  of the default methods of `Cmdlet` and `CmdletProvider`).
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
- **A program whose name starts with a digit can be run** (`7z archive.zip`). In command position, any word starting with a digit
  was read as a number and refused (`invalid number: 7z`). Now only a word that has the shape of a number or of a unit literal
  (`42`, `7d`, `10kb`, and `5S` or `2MB`, which are still reported as badly written) is an expression; `7z`, `7z.exe` and `2to3` are
  looked up as programs. `Units.looksLikeLiteral` carries the rule, shared by the parser, the highlighting and the completion.
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
  way (glibc older than 2.34, musl) or that is not supported (anything but Linux, macOS and Windows: FreeBSD…, because the constants and
  the handling of descriptors are written per system and none of them has been run there), and only when the session variable `POWERJ_BACKGROUND_SH_FALLBACK` is `true`. Without it,
  a background command on such a system is refused with a message that names the variable. A Windows batch file (`.bat`, `.cmd`)
  is still started by `ProcessBuilder`, without protection: `cmd.exe` reads its command line by other rules.
- **Behaviour that changes on Unix, as in a shell with job control**: a background command that reads the terminal (`/dev/tty`:
  `ssh`, `sudo`) or, after `stty tostop`, writes to it, is stopped by the terminal (a stopped process keeps `SIGTERM` pending, so
  `kill` now sends `SIGCONT` after it, and the job ends); `kill -INT <pid>` sent straight to a job now kills it, where the `sh` wrapper ignored it; and a signal
  sent to the process group of the shell no longer reaches the jobs (the `SIGHUP` of a closed terminal, the signal by which a CI
  job or `timeout` aborts a script by its group): a job outlives the terminal or the script that started it, as with `nohup`. The test
  `InterpreterTest.backgroundCommandGivesControlBackAtOnce` checks the group, no longer the ignored signal.
- **An executable text file without a `#!` line no longer starts in the background**: the former `sh` wrapper ran it as a shell
  script, `posix_spawn` does not (`Exec format error`). Give it to its interpreter: `sh script &`. (Decided in the review of #129.)
- **On Windows the arguments of a background command are quoted correctly**: an argument that holds a `"` reaches the program
  as written, where `ProcessBuilder` splits it. Measured on the CI: 12 tricky arguments out of 12 against 4. A start is also
  faster (7 ms against 16 ms for `cmd /c exit 0`).
- Measures and decision: [docs/plans/background-process-group.md](docs/plans/background-process-group.md); checklist:
  [docs/acceptance/background.md](docs/acceptance/background.md).

### Fixed (the built-in cmdlets read the objects that come from other modules, [#130](https://github.com/yp44/powerj/issues/130))
- **`jobs | kill`, `jobs | to-json`, `jobs | to-csv`, and `to-json`, `to-csv` and `join` on the records of a module loaded by
  `mod-load` no longer fail** with `cannot read …: IllegalAccessException … cannot access class …Job` (`kill: not a process: Job[…]`).
  A module can read by reflection only the records of packages exported or opened to it, and these cmdlets reflected from
  `io.powerj.cmdlets`. They had seemed to work once the values had been displayed: the table of the terminal made the `Method` of
  a component accessible, and `RecordView` hands the same `Method` to every module (and only for the columns that had been shown:
  `jobs | to-json` still failed on `start`).
- **One way to read a property, in the engine.** `CmdletContext.readProperty(target, name)` (abstract) and `tryReadProperty`
  (`default`): the value of a property by **one literal name** (not a path: the key `first name` of a `Row` is read as it is),
  found as `$_.name` finds it (record component, getter, public field, key of a `Map`). The built-in cmdlets (`Properties`,
  `Kill`), the table of the terminal and the grid read through it, `io.powerj.cmdlets.RecordShape` is removed, and the names and
  the sizes (`@Bytes`) come from `RecordView`. A third-party cmdlet reads the objects of the built-in ones, and the built-in ones read
  those of a third-party cmdlet, the same way. [ADR 0011](docs/adr/0011-every-command-reads-the-properties-of-an-object-through-the-engine.md)
  replaces the sentence of [0010](docs/adr/0010-shared-output-helpers-live-in-powerj-text.md) on accessors.
- **The case of a name matters, everywhere** (a change of the language, FR-28): `$_.name`, `where size > 1mb`, and the names given
  as arguments to the cmdlets (`sort size`, `--property path`, `select name`, `grid --columns name,size`). `$_.Size` is an error
  that says what exists: `FileEntry has no property 'Size' (did you mean 'size'?)`. A getter is found by the name of its
  property, the one that the completion lists (`getName()` is `name` and not `Name`, `getURL()` is `URL`). The rule is written once, in `io.powerj.text.BeanProperties`, for the engine that reads
  the property and for the grid that shows it as a column. Scripts that wrote another case have to change. The options, the
  units and the other names follow the same rule: see the next entry.
- **Breaking changes of the API** (before the first release): `CmdletContext.property(path)` is removed (it was
  `compile("$_." + path)`; the cmdlets read the names that a user types with `PropertyPath`, one name after the other through
  `readProperty`); `readProperty` is abstract, so an implementation of `CmdletContext` has to give it. `OutputFormatter` is given a
  `ComponentReader` (`PropertyAccess::property`) by `StatementOutput`, `PreviewMode` and `NativeRunner`. A getter that is interrupted
  by Ctrl+C now ends the command, in `$_.name` as in the table, instead of being reported as an unreadable property.
- **Checked where the tests of a module cannot see it:** `ci/smoke/properties.sh` runs `jobs | to-json`, `jobs | to-csv`,
  `jobs | kill`, `greet | to-json` and `greet | to-csv` on the packaged launcher, in fresh sessions (it fails on `main`), and
  `ArchitectureTest.onlyTheEngineReadsAnObjectByReflection` forbids `Method.invoke`, `Field.get`/`set` and `setAccessible` outside
  `io.powerj.core` (and the test context of `powerj-testing`).
- **A getter is found in a table per class** (`Members.getter`, `Members.field`) instead of by a scan of the public methods at each
  read: `$_.path.fileName` takes 24 ns instead of 1.1 µs, which `sort path.fileName` pays for each file.
- **Cost:** the 8 columns of a record read by `to-csv` take 0.46 µs instead of 0.28 µs, a record turned into a map by `to-json` 0.29 µs
  instead of 0.16 µs; `$_.name` is faster (23 ns instead of 28, a hash table by exact name instead of a case-insensitive tree)
  ([docs/quality/performance.md](docs/quality/performance.md)).

### Changed (the whole shell is case-sensitive, as Java is, [#130](https://github.com/yp44/powerj/issues/130))
- **What a user writes is compared exactly** ([ADR 0012](docs/adr/0012-the-shell-is-case-sensitive.md), FR-12): the name of an
  option and its abbreviation (`--Property` is refused with `did you mean --property?`, `--REC` is refused), the value of an enum option in
  **lower case** (`perf --view stages`, as the help and the completion always showed; `--view STAGES` is refused), the booleans
  (`true`, `yes`, `oui`, `1`; not `TRUE`), the units (`2mb`; `2MB` is an error that lists the units), the literals of `where`
  (`where dir == true`), `--delimiter tab`, the constants of a Java enum (`RoundingMode.HALF_UP`, not `half_up`), and the values of the
  settings (`POWERJ_LANG=fr`, `POWERJ_GLOB_IGNORE_CASE=true`, `POWERJ_BACKGROUND_SH_FALLBACK=true`, `POWERJ_NATIVE_ENCODING=auto`,
  `true`/`false` in the consent of `config.properties`). The commands, the variables and the classes already were. Scripts that wrote
  another case have to change; the refusal gives the spelling that is expected where there is one (a hint, or the list of the values).
- **`ps` and `env` follow the system.** The name of a process, its user and the name of a variable of the environment ignore case on
  Windows, where the system does, and are exact on Linux and macOS (`ps JAVA*` finds nothing there). `ps` used to ignore case everywhere.
- **Four exceptions, all written down**: the rules of the system (files on Windows and macOS, the environment and the processes of
  Windows), what the user asks for (`sort -i`, `grep -i`, `range -i`, `POWERJ_GLOB_IGNORE_CASE`, the filter box and the order of the texts
  of the grid window), the digits of a hexadecimal fingerprint and the other rules of the JDK (a charset name, `Enable-Native-Access`), and
  the `Y` that answers a question.
- **Checked by `CaseSensitivityTest`** (module `powerj-architecture`): it fails when `equalsIgnoreCase`, `compareToIgnoreCase`,
  `Pattern.CASE_INSENSITIVE`, `Pattern.UNICODE_CASE` or `regionMatches(true, …)` appears in a class of production that is not in its list
  of exceptions, each with its reason, and when a class of the list no longer needs to be there.
- **Breaking change of the API** (before the first release): `SizeUnits` reads lower-case units only; `Globs.ignoreCase` reads the values
  `true`, `1`, `false`, `0` only.

### Changed (an option is written as the help shows it, [#88](https://github.com/yp44/powerj/issues/88))
- **A long option is written whole.** `--rec` is no longer `--recurse`: it is refused with `ls: unknown option --rec, did you mean
  --recurse?` (the error gives the name when exactly one name begins with the text typed; when several begin with it, it picks none; when none
  does, it gives the one within two edits, if there is one).
  The reason: a script that wrote `--rec` broke the day a cmdlet added `--recover`, so adding an option was a breaking change for
  the scripts and for the authors of modules. Tab still completes the beginning of a name. Hyphens were already part of the name
  (`--maxdepth` is not `--max-depth`); the case has been exact since [#130](https://github.com/yp44/powerj/issues/130).
- **A `boolean` is `true` or `false` and nothing else.** `--recurse=yes`, `=no`, `=oui`, `=non`, `=1`, `=0` are refused with the
  message that already said it: `--recurse expects true or false, not 'yes'`. This amends [ADR 0012](docs/adr/0012-the-shell-is-case-sensitive.md),
  which had kept them. The prompt that asks to load a module still takes `y`, `yes`, `o`, `oui`: it is an answer to a question, not
  a value, and `POWERJ_GLOB_IGNORE_CASE` keeps its own words.
- **`OptionBinder` is shorter and does less work per command.** The two regular expressions (a negative number, the cut of a
  camel-case name) are constants; the options of a parameter record are read once (`ClassValue`) and shared by the binder, the help and
  the completion; `bind()` (90 lines) is a cursor over the arguments and three small methods (`longOption`, `shortOptions`,
  `positional`). The message `option.ambiguous` is gone with the abbreviations.
- `Option.longName` (Javadoc of the API): the default is the kebab case of the component name (`ignoreCase` gives `--ignore-case`),
  not the component name as the text said. No signature changes.
- Breaking, allowed before 1.0 ([#47](https://github.com/yp44/powerj/issues/47)): `--rec`, `--des`, `--max 2`, `--flag=yes`,
  `--flag=1`. The acceptance sheets of `sort`, `ls` and the case rule are corrected.

### Fixed (a test of the background jobs failed at random, [#128](https://github.com/yp44/powerj/issues/128))
- `PosixSpawnTest.check05_theChildInheritsNoOtherDescriptorThanTheStandardOnes` failed once on the CI (a descriptor `3` in the
  child) and passed on the re-run, with the same code. **Nothing leaked**: right after `exec`, the dynamic loader opens the C library on
  descriptor 3 and closes it a few hundred microseconds later, and the test listed `/proc/<pid>/fd` in that instant (2 reads of
  1,000 measured on Linux; 395 of 400 when the read is immediate in a tight loop). The test now reads again, every 5 ms, until
  the list is the one expected (`ChildFds`, five seconds at most) and fails only with the list that is still wrong then. A real
  leak does not go away: with the child started without `addclosefrom_np` the test still fails. Test only; no change to the code of PowerJ.

### Added (a start-up option to choose the directory the shell opens in, [#121](https://github.com/yp44/powerj/issues/121))
- **`powerj --cd <dir>` (or `--cd=<dir>`)** sets the initial current directory of the session (FR-04d, FR-04b), for a terminal
  profile, a shortcut or an alias that cannot choose it: `powerj --cd ~/dev/myproject`, `powerj --cd /var/log -c "ls | first 5"`,
  `powerj --cd build script.pj`. It works in the interactive shell and with `-c`, a `.pj` file or standard input; it is an option of
  the shell (like `--debug` and `--no-profile`), not an argument of the script. The value is absolute or relative to where `powerj`
  was started, and a leading `~` is expanded (FR-62). The prompt, `pwd`, `$pwd`, relative paths, redirections and native commands
  agree; the JVM's own working directory is not touched, and the `.pj` file is still looked up where `powerj` was started.
- **Applied before `profile.pj`**, so a `cd` in the profile still wins; `--no-profile` is independent.
- **No silent fallback**: a directory that does not exist, or is a file, prints an error with the path (English and French,
  `startDirectory.notFound` / `startDirectory.notDirectory`) and exits with code 1 without starting the shell. `--cd` without a
  value, given twice, or with a value that starts with `-` is a usage error (code 2): one `--cd` only, and its value is a path. `--help` and the usage line list the option.
- `Session.expandTilde(word, environment, startupHome)` expands `~` before a session exists, with the same rules as inside it.
- Tests: `ShellBootstrapTest` (parsing, absolute / relative / `~`, `-c`, `.pj`, interactive prompt, profile, errors in both
  languages), `SessionStateTest`; smoke scripts `non-interactive.sh` and `launcher.sh`; acceptance rows 21a–21f of `step-4.md`.

### Decided (audit v2 of 2026-11-14: analysis and resolution plan, [#141](https://github.com/yp44/powerj/issues/141))
- **A third review, no code change yet.** `main` at `c82cd5c` was read on five axes (robustness, clarity, best practices,
  stability, user experience of the shell): the whole of `powerj-shell` and the main classes of `powerj-fx` directly, the
  core, the API and the cmdlets file by file with a `file:line` proof for each finding, plus the build, the workflows, the
  smoke scripts and the last 40 CI runs. Nothing was compiled or run. The report is
  [docs/AUDIT-v2-2026-11-14.md](docs/AUDIT-v2-2026-11-14.md) (in French, like the architecture review): 14 things worth
  keeping, 85 findings, the critical ones, and six phases of work.
- **The plan is the epic [#141](https://github.com/yp44/powerj/issues/141) and its 46 sub-issues** `[AUDITv2] …`
  ([#142](https://github.com/yp44/powerj/issues/142) to [#186](https://github.com/yp44/powerj/issues/186), and [#190](https://github.com/yp44/powerj/issues/190) added on 2026-11-15), with the rules of
  #77: a red test first, one pull request per issue, one window for what touches `powerj-api`. The most serious findings:
  Ctrl+C is lost when a Java call is interrupted ([#165](https://github.com/yp44/powerj/issues/165)); a non-deterministic
  `PerfTest` turned `main` red ([#142](https://github.com/yp44/powerj/issues/142)); eight Dependabot pull requests are blocked
  by two tests that depend on the module name of the test cmdlets ([#143](https://github.com/yp44/powerj/issues/143),
  [#144](https://github.com/yp44/powerj/issues/144)); a character literal `'{'` leaves the user at the `>>` prompt
  ([#148](https://github.com/yp44/powerj/issues/148)); `mv -f x link` replaces the link, `rm "x?.txt"` removes more than asked,
  `cp -r` fails on a read-only tree ([#155](https://github.com/yp44/powerj/issues/155) to
  [#157](https://github.com/yp44/powerj/issues/157)); `cat` can end the session with an `OutOfMemoryError`
  ([#158](https://github.com/yp44/powerj/issues/158)); a `float` option breaks a third-party cmdlet on every call
  ([#166](https://github.com/yp44/powerj/issues/166)); the native-access consent is not the gate its Javadoc describes
  ([#170](https://github.com/yp44/powerj/issues/170)); `ps | kill` has no safety net
  ([#161](https://github.com/yp44/powerj/issues/161)).

### Fixed (`PerfTest` no longer depends on the speed of the machine, [#142](https://github.com/yp44/powerj/issues/142))
- **`main` no longer fails at random on `PerfTest`.** On 2026-11-10 the macOS job of `main` failed on
  `PerfTest.aBusyStageIsFoundHotInItsOwnThread` ("5 > 5 expected") and passed on the next run with no change: the class
  asserted durations of the stages and the number of samples the real profiler happens to give (audit v2, S-01). It now
  asserts no duration and no number of samples of the real run. The real profiler has to find the stage that spins and not
  credit the spin to the one that does not; a valid sampling period is accepted and the profiler still samples; the
  allocation limit keeps one weak relative bound (1 event against 1627 without the limit), the only check that the profiler
  reads the rate as written.
- **The rules those assertions stood for are checked where they are decided, with given times**
  (`ExecutionObserverTest`, `PerfModeTest`): the slowest stage is the bottleneck, active time is wall time without the waits,
  the phases make up the line, and the work of the profiler after the line is not counted in it. Each test was seen failing
  when the matching line of `PerfRecorder`, `StageProbe` or `PerfMode` was broken on purpose. Only tests changed.
- **The tests that read the real clock are listed** in [docs/quality/clock-dependent-tests.md](docs/quality/clock-dependent-tests.md)
  (25 test sources of four modules, with what each needs from the clock and its margin), and
  [CONTRIBUTING.md](CONTRIBUTING.md) says that a test does not assert what the machine happens to deliver.
- **A manual run of the CI can repeat the tests of the core on macOS**: `workflow_dispatch` with `repeat-core-tests=20`. The
  20 extra runs of this change passed (run 43728102905); the other runs of the CI are unchanged.
- Found on the way, not part of this change: the smoke test of the graphical module failed once on a Linux runner and passed
  on the same commit ([#190](https://github.com/yp44/powerj/issues/190)).

### Fixed (tests that depended on how Surefire lays out the test classes, [#143](https://github.com/yp44/powerj/issues/143))
- **The update of the Maven plugins ([#133](https://github.com/yp44/powerj/pull/133)) is no longer blocked by the tests.**
  Surefire 3.5.3 runs the tests of a module from the class path; Surefire 3.6.1 runs them on the module path, with the test
  classes patched into the module under test. Four tests described that detail of the test runtime and not a behaviour of
  the product (audit v2, B-03): `CommandResolutionTest.whichSaysWhatExecutionWouldRun` and two rows of
  `InterpreterCharacterizationTest` (`which items`, `help items`) expected the module of the test cmdlet to be
  `(no module)`, where Surefire 3.6.1 makes the registry report `io.powerj.core`; and `PropertiesTest` made the test context
  call `setAccessible` on a record of a test package, which is only allowed when the package is open to the reader (the
  issue did not list it: the build of #133 stopped at `powerj-core` before reaching `powerj-cmdlets`).
- **They now pass with both versions.** `FakeCmdlets.module()` says which module the registry reports for the test cmdlets
  (from the module of the class itself) and the three assertions use it; `PropertiesTest` reads its hidden record as the
  owner of the class. A full `./mvnw verify`, with the graphical module and the application image, is green with Surefire
  3.5.3 and with the plugin versions of #133 (Surefire 3.6.1 among them). Each assertion was seen failing when the code it
  checks was broken on purpose. Only tests changed.
- **The update of the Maven plugins is applied** (the 13 updates of [#133](https://github.com/yp44/powerj/pull/133), Surefire
  3.6.1 and the JaCoCo, Spotless, compiler, jar, resources, dependency, antrun, install, deploy, site, enforcer and
  build-helper plugins), by a commit of the phase 0 branch rather than by the Dependabot pull request, which targets `main`
  and is then superseded.

### Fixed (Surefire's "Corrupted channel" warning, [#145](https://github.com/yp44/powerj/issues/145))
- **No build of `powerj-core` ends with "Corrupted channel by directly writing to native stream" any more** (audit v2, B-06).
  The bytes Surefire had kept in its `.dumpstream` file were `x` and `a`: `ConsoleInputTest` runs `printf a | cat` and
  `"x" | cat` in an interactive shell, where the last native program writes to the terminal, which is the stdout of the
  forked JVM, the stream Surefire talks through. The two lines now redirect the output of `cat` to a file of the test (the
  console is still checked to be paused around a program that reads it). The writers named in the issue (`PosixSpawnTest`,
  `BackgroundJobsTest`, `NativeRunnerTest`, `Environment.system()`) were not the ones.
- **The CI looks for it.** `ci/check-surefire-channel.sh` fails when a `target/surefire-reports/*.dumpstream` file exists,
  after the build of the Linux and macOS jobs (also after a failing build, `if: always()`); it fails too when no
  `target/surefire-reports` folder exists, since it would then have examined nothing. It was seen failing on
  `ConsoleInputTest` before the fix. **Not on Windows**: tried there, it reported the `.dumpstream` files of
  `powerj-architecture` and `powerj-testing`, which hold a message of the forked JVM of Surefire on Windows ("Boot Manifest-JAR
  contains absolute paths in classpath", hint `-Djdk.net.URLClassPath.disableClassPathURLCheck=true`) and no output of a test.
  Only a test, a script and the workflow changed.

### Fixed (the smoke test of the graphical module no longer fails at random, [#190](https://github.com/yp44/powerj/issues/190))
- **`ci/smoke/fx.sh` failed in about one run out of seven with "window: unexpected message"**, on a commit that had passed
  before and passed again on a re-run (twice in the first runs of the CI that were looked at). The output held a
  `NullPointerException` thrown by JavaFX itself on its own thread, `GraphicsPipeline.getPipeline()` being null in the
  picking of the scene (a pointer event). It was reproduced on a development machine under xvfb (6 runs out of 40): `-c`
  ends the shell, and the JVM, as soon as the window is shown, so JavaFX is torn down while it still handles an event of the
  pointer. Nothing of PowerJ or of the JDK is in that message and no check of the smoke test depends on it.
- **That exception is now ignored, and nothing else.** `unexpected_window_messages` (in `ci/smoke/common.sh`) ignores it only
  when the stack that follows is that of the picking of the scene (`PickResultChooser.processOffer`) and holds no `io.powerj`
  frame, **or when it has no stack at all** (a run of the CI on `main` printed the line alone, which the JVM does when an
  exception is thrown very often: the stricter filter of the first version failed the job for noise); any other exception, a
  restricted-method warning, a toolkit that cannot start, the exception with another stack or with a frame of PowerJ, or
  worded differently by another JDK is reported. `ci/smoke/window-filter-test.sh` tests nine cases and runs in the Linux job. It was checked on a real failing output (ignored), without the picking frame and with an
  `io.powerj` frame added (reported), with another exception added (reported) and on a clean output. 60 runs in a row of the window step then gave 5
  runs with the line and 0 failures. Tried and dropped: a pause of 2 s after the window opens (3 failures out of 40, the
  race is at the exit), and `Platform.exit()` in a shutdown hook of the JVM (the JVM then does not exit).
- **What is not fixed**: the same race can print that exception when a user exits the shell while a `grid` window is open.
  It is recorded in a separate issue, because closing the windows in an orderly way needs a hook at exit that the extension
  API does not have yet.

### Changed (zero javac warnings, and a warning fails the build, [#146](https://github.com/yp44/powerj/issues/146))
- **The 23 warnings that `docs/quality/javac-warnings.txt` tolerated are fixed** (audit v2, B-05), and `-Xlint:all` is now
  `-Werror` for the sources and the tests of every module. The issue named raw types and inexact varargs; a clean build also
  held 7 `[restricted]` (FFM calls) and 4 `[try]` warnings. Raw `Collected` became `Collected<Object>` in `Collect` and its
  test (the cmdlet declares its output type with the argument, which the registry reads as before); the two `args(String[])`
  calls of `FileOpsTest` are cast to `Object[]`; the FFM methods carry `@SuppressWarnings("restricted")` as `PosixSpawn` does;
  `CmdletRunner.Context` and the test states of `CmdletRunnerTest` suppress `[try]` with the reason (the
  `InterruptedException` of a state is rethrown, not lost).
- **The check of the number is gone**: `ci/check-javac-warnings.sh`, `docs/quality/javac-warnings.txt` and the step of the CI
  that ran them are removed; [docs/quality/lint-baseline.md](docs/quality/lint-baseline.md) now says how the rule works.
  A raw type added on purpose to a main source and to a test source each failed the build with "warnings found and -Werror
  specified". `-Dpowerj.failOnWarning=false` turns it off on a JDK newer than the CI's, and the `quality` profile (Error
  Prone) does not fail on its findings.

### Fixed (French left in two poms, and a test for the build files, [#147](https://github.com/yp44/powerj/issues/147))
- **The two French texts of the build are in English** (audit v2, C-08): the description of `powerj-dist` (printed by Maven)
  and the name of `examples/greet`, which the reactor summary of every build showed as "PowerJ Exemple : module greet".
- **`NoHardcodedFrenchTest` now reads the build files too**: every `pom.xml`, the scripts of `ci/` and the workflows, comments
  included (the repository is in English, CONTRIBUTING "Language"). The two texts above have no accent, so the accents the
  test looked for in the Java literals were not enough: it also looks for words that are French and not English (`avec`,
  `lanceur`, `installeur`, `exemple`, `l'application`…), and it was seen failing on the two poms before the translation and
  nowhere else. The French that is test data stays allowed, only on the lines that hold it: `ci/smoke/languages.sh` (it checks
  the French messages of the launcher) and the same check inline in the Windows job of `ci.yml`, so French written
  elsewhere in those files is still found. `pour` (an English word) is not in the list and `l'`/`d'` do not match after a
  letter or a hyphen (`tr -d'x'`); the walk skips `.git` and the `target` folders. Only the test and two build texts changed.

### Changed (Dependabot: one group for the actions, and the rule for the queue, [#144](https://github.com/yp44/powerj/issues/144))
- **The minor and patch updates of the GitHub Actions come in one pull request a week** (a `github-actions` group in
  `.github/dependabot.yml`, as the Maven plugins and the test libraries already do), so that they do not wait a month each
  (audit v2, B-02). A major version of an action stays a pull request of its own, because it can change the inputs or the
  Node runtime and one red check must not hold back the others. JLine and JavaFX stay alone: they ship in the application.
- **The rule is in CONTRIBUTING, "Dependency updates"**: a Dependabot pull request is merged, or closed with its reason,
  within two weeks; the queue never holds more than three; what to check before merging a runtime dependency; what a red
  update means (a test of the layout, a flaky test, or a real break); what is not wanted is closed with the reason.
- **Not done here, and left to the maintainer**: merging or closing the eight pull requests that were open
  (#65, #66, #68, #69, #70, #71, #132, #133). They target `main`; #133 is superseded by the update of the plugins applied in
  the phase 0 branch (#143).

### Changed (the low-risk Dependabot updates, in one batch, [#144](https://github.com/yp44/powerj/issues/144))
- **Eight Dependabot pull requests are taken together in one branch and verified at once**, instead of one at a time (each
  merge makes Dependabot rebase the others and restart their CI): the test libraries (#68), pitest 1.30.2 (#202), the Maven
  wrapper 3.9.11 → 3.10.1 (#201), three GitHub Actions: `upload-artifact` 5 → 7.0.2 (#66), `setup-java` 5.7 → 6.3.0 (#200)
  and `checkout` 5.1 → 7.0.1 (#65, whose branch was 111 commits behind and is replayed on the workflows as they are), and the
  two libraries that ship: JLine 4.4.7 → 4.7.0 (#70, the only runtime dependency) and JavaFX 25.0.5 → 27.0.1 (#69, optional
  module `powerj-fx`).
- **What was run on them, and what was not**: the full `verify` (0 javac warnings), the `fx-ui` tests under Xvfb, the `dist`
  build and every script of `ci/smoke`. Not run: JLine on a real terminal (the CI has none), and JavaFX rendering on macOS
  (Metal; the macOS job opens no window). Both are to look at by hand before a release.
- **JavaFX 27 and the window smoke test**: the known race at the exit of the JVM (#194) shows itself with the same frequency
  (5 runs in 30 print an exception line, as before). One more shape of it appeared, the header
  `Exception in thread "JavaFX Application Thread"` cut by the exit with nothing after it (1 run in 30): the filter of
  `ci/smoke/common.sh` ignores it, only as the very last line of the output, with a test for each side.
- `mvnw.cmd` of #201 is not taken: it differs by line endings alone (the file is CRLF by `.gitattributes`), only
  `maven-wrapper.properties` changes. The Maven version named by `README.md` and `CONTRIBUTING.md` follows the wrapper.
- **The Dependabot queue is empty**: #65, #66, #68, #70 and #200 were closed by Dependabot once their changes were on `main`;
  #69, #201 and #202 were closed by hand, each with its reason (the version is on `main`; for #201, `mvnw.cmd` differs by line
  endings alone). This is what the first condition of #144 asked for, and the phase 0 of the audit is done.

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

### Fixed (one reading of "where a quoted text ends" for the continuation prompt, [#148](https://github.com/yp44/powerj/issues/148))
- **A character literal or a text block no longer leaves the user at the `>> ` prompt for ever** (audit v2, U-01, C-01).
  `InputCompleteness` knew `"…"` and brackets and nothing else, so `where { $_.name.charAt(0) == '{' }` counted two open braces
  and one closed and waited for a continuation line; a `"""` text block opened a string, closed it and opened it again. It
  now reads quoted text as the `Lexer` does: strings (with their `$( … )` interpolations), text blocks and character
  literals. A lone `'` (`echo it's`) is an apostrophe, not a literal that more input could close.
- **One function, `Scanning.quotedEnd`, for the three readers** (`io.powerj.core.lang`, already exported to the shell):
  the continuation prompt, `PreviewCut` (its own copy of the rule is gone; an apostrophe in a word no longer hides a `|`
  from it) and `Scanning.matching` (which now skips a text block as a whole). Tests: `InputCompletenessTest`,
  `ScanningTest`, `PreviewCutTest`, and `ReplTest` for the whole road (no `>> ` on the screen). Only the shell and the scanner changed.
### Fixed and added (the command line: unknown options, a BOM in a script, `$args`, [#149](https://github.com/yp44/powerj/issues/149))
- **An unknown option is named** (audit v2, U-08). `powerj --foo` used to print the generic usage line and give 2 without saying
  what was wrong: any word was taken for the script. A word that starts with `-` and is no option of the shell is now
  refused with `unknown option: --foo`, then the usage, and the code is still 2 (`--help` and `--version` still win). `-h` is
  now in the usage, which it was accepted without.
- **A `.pj` script written by Notepad runs its first command** (R-19). `ScriptRun` read the file with `Files.readAllLines`, which
  keeps the BOM, so the first command was `\uFEFFls`. The script is now read by `Profile.lines`, the function the profile
  uses (UTF-8, BOM removed): one way to read a file of commands.
- **A script has arguments** (decision of the issue: yes). `powerj script.pj a b` and `powerj -c "line" a b` set the list `$args`
  to `["a", "b"]` (empty without arguments). The options of the shell stop at the script word (or at the line after `-c`),
  as for `python script.py --help`: `powerj deploy.pj --help` gives the script `--help` and prints no usage; `--` before the script
  word is for a script whose name starts with `-`. A word that starts with `-` before the script and is no option of the shell
  is refused before anything is created or read, and before `--help` or `--version` is answered (review of the PR). `powerj a.pj b.pj` used to be a usage error and now runs `a.pj` with
  `$args` = `[b.pj]`. FR-04d, the README table, `step-4.md` (21g to 21i) and `ci/smoke/non-interactive.sh` say so.
  Left out: named options for scripts.

### Added ("unknown command: grpe, did you mean grep?", [#150](https://github.com/yp44/powerj/issues/150))
- **A mistyped command gets the hint a mistyped option already had** (audit v2, U-02). `grpe`, `whre` or `fisrt` printed
  `unknown command: …` and nothing else. The error now adds `, did you mean grep?` when one built-in command, cmdlet
  (qualified names included) or program is the obvious name. Programs are the names of the `PATH` that completion has already
  cached (`NativeCommands.cachedNames`): the error never waits for a scan of the `PATH`, and a non-interactive run, where
  nothing completes, suggests built-in commands and cmdlets only.
- **One rule for options and commands**: `OptionBinder`'s private helpers became `Suggestions.forName`. It keeps the rule
  (the only name that begins with the text; else the nearest within two edits, the case apart) and tightens it where it
  guessed: **two names equally near give no hint** (the first used to win), an exchange of two letters counts for one
  edit, so `grpe` is found, and **the distance allowed grows with the length of the word** (one edit per three letters, at most
  two: `xyz` or `qq` are not typos of `ls` or `cp`; review of the PR). `CommandCatalog.nativeCommands()` lets the interpreter and completion share one cache.
  Tests: `SuggestionsTest`, `UnknownCommandTest`, and the option tests as they were.
### Fixed and changed (an invalid `history.size` is announced; review of the messages of `powerj-shell`, [#152](https://github.com/yp44/powerj/issues/152))
- **An invalid `history.size` is shown on the terminal, not only logged** (audit v2, U-13). `history.size=abc` (or `-3`, `0`,
  empty) was written to the log at `WARNING` and the default size applied without a word, while a file that cannot be
  read at all is announced (`config.unusable`). Both now go to the same `warn` line: *PowerJ: invalid value for
  history.size: abc (a positive whole number is expected); the default, 10000, applies.* (`config.invalidValue`, en and fr). The warnings of the settings are worded once the language is known, so `language=fr` and a
  bad `history.size` in the same file give the warning in French; the log keeps the line at `FINE` only (the console
  carries it), after the review of the PR.
- **French: the command is `history`, in every message.** `history.noMatch` said `historique :` and
  `history.unknownOption` said `history :`; a command is a name and is not translated. Both say `history :`.
- **Review of the 35 keys of the bundle** (en and fr, against: tone, terminology, what to do next). Kept as they are, with the
  reason: the startup messages and `config.unusable` already say what happens next (the default settings apply, a backslash is
  written twice); `history.unknownOption` names the option that exists; the preview banners are symbols and short words on
  purpose (a line of the screen); the `[y/N]` / `[o/N]` answer letters are the ones `NativeAccessConsent` accepts (`y`, `yes`, `o`, `oui`). The literal `^C`
  of `Repl` is the echo every terminal shell prints, not a message. Nothing else needed a change.
### Fixed and documented (Alt+arrow in every form terminals send it, Escape then a letter, "Option as Meta", [#153](https://github.com/yp44/powerj/issues/153))
- **The Alt+arrow keys of the preview control work on more terminals** (audit v2, U-12). They were bound to one escape
  sequence each, the one of xterm (`ESC [ 1 ; 3 X`); a terminal that sends `ESC ESC [ X` (tmux by default, many others) or
  `ESC [ 1 ; 9 X` (the macOS Terminal with Option as Meta) could not change the settings of a timed-out preview from the
  keyboard, though the banner said it could. `PreviewControl.Key` now lists the three forms and `Previewer` binds each of them,
  keeping what the editor had on that sequence for a line that shows no values. There is no terminfo capability for a
  modified arrow (JLine reads none), so no form is read from the terminal. With the preview off the keys keep what the editor
  had on them: on the real key map `ESC ESC [ C` and `ESC ESC [ D` are the movement by word and still are, the other new forms
  are bound to nothing and insert nothing (a test on the real key map, from the review of the PR). `PreviewControlKeysTest` sends each form to a
  `TestTerminal` and watches the setting change.
- **The README says that Escape then the letter is Alt+V / Alt+P**, which works on every terminal, and how to turn on
  "Use Option as Meta key" in macOS Terminal and iTerm2 (Option+V sends `√` without it). `docs/acceptance/preview.md` has rows 27 and 28.
  Left out, as the issue says: configurable key bindings.
### Fixed (the prompt escapes `%` in one place, [#151](https://github.com/yp44/powerj/issues/151))
- **A current directory that holds `%` is shown as it is named** (audit v2, U-13). JLine reads `%N`, `%M`, `%P`, `%{`… in a
  prompt as patterns. Only the prompt of the perf mode doubled the `%` (and only when it was reset after Alt+P); the first
  prompt of every line and the one reset by Alt+V went through unescaped, so a folder named like `100%N-%M` was
  rewritten on the screen. `PromptRenderer.forJLine()` is now the one place that knows the rule, used by the read loop,
  the preview mode and the perf mode; `Perfer` no longer escapes. `ReplTest` types the three modes in a folder named
  `100%N-%M-%%x` (red before the change); `PromptRendererTest` pins the escaped text.

### Fixed (four failures of the shell that nobody saw, [#154](https://github.com/yp44/powerj/issues/154))
- **The log works in a folder whose name holds `%`** (audit v2, R-20). The path of the logs folder was handed to
  `FileHandler` as a pattern, which reads `%t`, `%h`, `%u`, `%g`: a home folder named `100%` or `a%hb` gave a wrong file name or
  an exception (and no log). The `%` of the folder is now doubled. `DiagnosticLogTest` creates a log in folders named `100%` and `a%hb%g`.
- **The log no longer resets the logging of the whole JVM** (review C-20, still there). `LogManager.reset()` closed the
  handlers and reset the levels of every logger, a library's included. `DiagnosticLog.install` now removes the handlers of
  the root logger only (the console handler of the JDK).
- **A highlighting failure is in the log.** `ShellCompletion` returned the plain line for any `RuntimeException` and said
  nothing, so a bug of the highlighting stayed invisible for ever; completion, in the same case, logs at `FINE` (#82). It now
  logs at `FINE` with `LoggedLine.of(buffer)`, never the line.
- **Recording a consent keeps the permissions of `config.properties`.** `ConfigFile` wrote through `Files.createTempFile`
  (mode `600` on POSIX) and moved the file over the user's: the first answer to "load this module?" made the file private.
  The permissions of the existing file are copied onto the temporary one before the move, best effort (if they cannot be read, the
  answer is recorded all the same, with private permissions); a new file stays private.
- **The preview words an `Error` as the shell does for a running line.** An `OutOfMemoryError` showed as
  `java.lang.OutOfMemoryError: Java heap space` under the line. `Previewer.failed` uses `Supervisor.messageOf` (new, shared
  with the supervisor: `out of memory: filter the data earlier…`, `recursion too deep`, `internal error: … (details in the log)`)
  and logs the failure at `WARNING`; an interruption (the runner interrupts an evaluation that a newer line overtook or
  that passed its deadline) is no failure: it gives the time-out result and logs nothing. Tests: `ShellHygieneTest`, `DiagnosticLogTest`, `ConfigFileTest`.

### Fixed (`cp` and `mv` into a link to a folder, [#155](https://github.com/yp44/powerj/issues/155))
- **A destination that is a symbolic link to a folder is a folder** (audit v2, R-08). `Transfer` asked `FileOps.isDirectory`,
  which does not follow links, about the destination too: `cp a.txt alias/` failed with "destination is not a folder",
  `cp a.txt alias` with "already exists", and `mv -f a.txt alias` **replaced the link** with the file. On macOS `/tmp`, `/var`
  and `/etc` are links, so `cp x /tmp/` failed there. Links are now followed for the destination and its parent
  (`FileOps.isFolderOrLinkToFolder`); a source is still never followed and `rm` still removes the link. Tests:
  `FileOpsTest` (a link as destination for `cp` and `mv -f`, a link as a source still copied as a link).
### Fixed (quotes stop wildcards for the file cmdlets, [#156](https://github.com/yp44/powerj/issues/156))
- **`rm "report?.txt"` removes only the file named `report?.txt`** (audit v2, R-09). Since #73 the evaluator knew which
  wildcards were quoted, but only native programs read it: the cmdlets received the text and expanded any `*` or `?` in
  it, so `rm "report?.txt"` also removed `report1.txt`, and a file named with `*` or `?` (legal on Linux and macOS) could not
  be targeted at all. The shell now hands a path option typed `Object` (or a `List` of `Object`) a `GlobArgument` (module
  `powerj-fs`: the text and the glob pattern, quoted text escaped) when the quotes or a variable made a wildcard literal;
  anywhere else, and for a third-party cmdlet whose option is typed `String`, the word is the text, as before. `rm`, `cp`,
  `mv`, `touch`, `mkdir`, and now `ls`, `cat` and `grep` (their paths are typed `Object` and marked `path = true`) take
  `"a?.txt"` as a name, a variable never expands, and in `"d?"/*.txt` only the `*` does. **Behaviour change**: `ls "*.txt"`
  used to list the `.txt` files and now says `not found: *.txt`, as a Unix shell does. Tests: `QuotedWildcardsTest`
  (the real cmdlets through the interpreter, on a file called `a?.txt` next to `ab.txt` and `a1.txt`), `OptionBinderTest`,
  `WordForCmdletTest`.

### Fixed (`cp -r` of a read-only folder, and the dates of the folders it copies, [#157](https://github.com/yp44/powerj/issues/157))
- **`cp -r` copies a tree of read-only folders** (audit v2, R-10). `FileTrees` created a folder with its final permissions
  before its children, so with `r-x` (a Go module cache, a Nix store, an archive after `chmod -R a-w`) every child failed
  with `AccessDeniedException`; the children also rewrote the date of their folder, against "the attributes are kept". A
  folder is now created writable and gets the owner (best effort), group, permissions and dates of its source when its last
  child is done: after the content in the sequential walk, by the "last child done" counter the parallel removal already uses
  in the parallel one. A folder that was merged (`-f`) is left as it was. Tests: `FileTreesTest` (a tree of two read-only
  levels with two dates, at parallelism 1 and 8; a merged folder), `FileOpsTest`; they need a user other than root.
- **A move across file systems that stops half way says so**: `mv` keeps the source, and now adds `the move is incomplete:
  <source> is kept, and the copy in <destination> holds only part of it`. It does not remove the copy: with `-f` the
  destination may be a folder that was there before. Test: `FileOpsTest` on a second file system (`/dev/shm`) when there is one.
### Fixed (`cat` no longer ends the session on a device or a line without end, [#158](https://github.com/yp44/powerj/issues/158))
- **`cat /dev/zero`, or a 2 GB minified file, no longer ends the session with an `OutOfMemoryError`** (audit v2, S-04).
  `cat` refused a folder and nothing else, then `BufferedReader.readLine` grew one `StringBuilder` until the line ended.
  Now `cat` reads regular files only (a link to one is followed): a device, a FIFO or a socket is refused at once with
  `not a regular file: …` and is not opened (a FIFO blocked at `open`); `grep` gives the same answer for a file it is given by
  name (inside a walk it still skips them silently). Lines come from `BoundedLines`: a line is read up to 64 Mi characters, a
  longer one is reported, dropped without being kept and the next one is read (its number counts the skipped line), and the
  interrupt flag is read at every refill of the buffer instead of every 4 096 lines, so Ctrl+C is seen inside a long line.
  `BoundedLines` is the reader `grep` will use too (#160). Tests: `BoundedLinesTest` (terminators across buffers, the bound,
  the flag, each seen failing when the code was broken on purpose), `CatTest`, `GrepTest`.

### Fixed (Ctrl+C during a file read is a cancellation, not "cannot read x: null", [#159](https://github.com/yp44/powerj/issues/159))
- **Ctrl+C inside a read stops `cat`, `grep` and the walk** (audit v2, R-11). `Files.newInputStream` is backed by an
  interruptible channel: an interrupt that lands inside a read makes it throw `ClosedByInterruptException`, an
  `IOException` with no message, which `cat`, `grep` (through the `WalkError` of `FileWalker`) reported as `cannot read
  /x: null` before going on with the next file. `Interruption.rethrow` (module `powerj-fs`) turns it, and
  `InterruptedIOException`, into a `CancellationException` with the flag of the thread set again; `Cat` and both modes of
  `FileWalker` use it, so a `ClosedByInterruptException` is never a `WalkError`. In the parallel walk the worker only stops
  (it must still signal its end, which a thread with its flag set cannot). An ordinary read failure is still reported.
  Tests: `InterruptionTest`, `FileWalkerTest` (sequential and parallel, and an `IOException("disk on fire")` still an
  error), `CatTest` (a stream that throws it, in lines and text modes; the test fails when the line of `Cat` is removed).

### Fixed and changed (`grep` emits as it reads and keeps the matches before a decoding error, [#160](https://github.com/yp44/powerj/issues/160))
- **`grep -v DEBUG app.log` on a multi-gigabyte log no longer holds the file in memory** (audit v2, S-05). `grep` gathered the
  `GrepMatch` of a whole file before emitting any, so the memory grew with the file, nothing appeared before the end, and
  `grep x big.log | first 1` read everything. With a heap of 128 MB, `grep -v zzz` on a 308 MB file used to end in an
  `OutOfMemoryError` and now emits its 3.5 million lines in under a second
  ([docs/quality/performance.md](docs/quality/performance.md)). `FileWalker.walkStreaming` (new, `powerj-fs`) takes a visitor
  that gives its results one by one: a sequential walk calls the consumer before the visitor goes on, a parallel one hands
  results over by the bounded queue. `grep` emits each match as the line is read (sequential) or by chunks of 1 000 (`-p`: the
  lines of a file stay in order, and together up to a chunk), and a consumer that stops stops the reading.
- **The matches before a decoding error are kept** (R-12). `InputStreamReader` throws at once and loses what it decoded in the
  same call, so every match of the last buffer of text was lost with the bad bytes; and `grep` used to drop the whole list.
  `DecodingReader` (new) returns what it decoded and throws on the next call; the error is now
  `not readable as UTF-8 text from line <n>`, and `cat` uses the reader too. A line longer than 64 Mi characters ends the
  search of its file (reported, with the matches before it) instead of filling the memory, as for `cat` (#158).
  Tests: `GrepTest` (a counting stream, a consumer that stops, 5 000 matches before a bad byte at line 5001 in both walks,
  2 500 matches in order across chunks), `DecodingReaderTest`, `FileWalkerTest`; each seen failing when the code was broken.

### Added and fixed (`kill` has a safety net, [#161](https://github.com/yp44/powerj/issues/161))
- **`ps | where … | kill` can no longer stop the terminal and the shell that typed it** (audit v2, U-07). `rm` has its
  protected paths and `--allow-protected`; `kill` refused the current process only. It now refuses the **parents of PowerJ**
  (the terminal, the IDE, the shell it was started from) and the **init process** (pid 1; the System and Idle processes on
  Windows) with `refusing to stop <pid> (<name>): … (stop it anyway with --allow-protected)`, before `-n` too, so a dry run
  shows what the real one would do. `--allow-protected` lifts it; stopping PowerJ itself is still the JDK's refusal.
- **A stored `ProcessEntry` stops the process it described, or none** (R-18, kill part). `$p = ps java`, then `$p | kill`
  used the pid alone, and a reused pid stopped another process; when the entry carries a start time that is not the one of the
  process now at that pid, the error is `no process <pid>`.
- **`kill -w` has an end** (S-09, kill part). It waited without limit for a process that ignores a normal stop. `--timeout
  <duration>` (default 10 s) ends the wait: `process <pid> (<name>) is still running after 10s: use -f to force it`; the
  process is not reported as stopped and the other targets go on. A timeout that is not above zero is a usage error.
  Tests: `PsKillTest` (the parents and init with `-n`, so that a failing test stops nothing; a stale entry on a real child; a
  child whose shutdown hook never returns), seen failing before the change.
### Fixed and added (`ls` of a broken link, and `FileEntry.link` and `hidden`, [#162](https://github.com/yp44/powerj/issues/162))
- **`ls broken` lists the broken link instead of printing nothing** (audit v2, U-06). `FileEntries.of` followed the link,
  swallowed the failure (`vanished`) and returned nothing, while listing the parent showed the link. It now falls back to the
  link's own attributes, as `FileWalker` does.
- **`FileEntry` tells a link from a folder.** It gains `link` (a symbolic link: its other attributes are its target's, so a
  link to a folder is still `dir`) and `hidden` (a leading dot, or the hidden attribute on Windows), outside the default
  columns, so the table is unchanged and `(ls)*.link`, `ls | where { $_.link }`, `to-json` and `to-csv` have them. The
  six-argument constructor stays. `FileWalker` hands the visitor the target's attributes through `FollowedLink`, which still
  answers `isSymbolicLink()` with `true`: no system call is added per entry. Tests: `LsTest` (a broken link named and listed in
  its parent, a link to a folder, a wildcard, hidden entries, the default columns).
### Fixed (six gaps of the file operations, [#163](https://github.com/yp44/powerj/issues/163))
- **`rm -f *.tmp` is quiet when nothing matches**, like POSIX `rm -f`: scripts and `--on-error stop` no longer break on it
  (`FileOps.expand` takes `quiet`; `FileArguments.expand` says nothing for a `null` key). Without `-f` it still says
  `no file matches`.
- **`--dry-run` makes the checks of the real run** (U-09). `mkdir -n existing` said it would succeed and the real run failed;
  now it gives the same `already exists` / `parent folder missing`. `touch -n` checks the parent and says `create` or `update`
  (`FileOperation` action). The granularity of `-n` per command is written in the help and FR-72: `rm -r -n` lists every entry,
  `cp -r -n` and `mv -n` each source once.
- **`touch` and links** (R-18). It tested existence without following links, then `setLastModifiedTime` followed them, and a
  broken link got "parent folder missing". It now follows a link as POSIX `touch` does (the target is updated, the link stays,
  the entry emitted is the link's), reports a broken link as `broken link: …`, and says "parent folder missing" only when the
  parent is missing.
- **`cp -f` and `mv -f` do not replace a protected path.** Only the source was checked, and `cp` never loaded the list: replacing a
  protected file the user had added (`~/.ssh/id_ed25519`) is removing it. The existing target is now checked for both; `cp` gets
  `--allow-protected` (its source is not removed, so it is never checked).
- **A relative line of `protected-paths` protects nothing and is reported once per session** (R-18): it was resolved against the
  folder PowerJ was started in, so it protected a different folder each time, silently (`ProtectedPaths.ignoredLines`,
  reported by `rm`, `cp` and `mv`).
- **The removal of a folder that could not be listed to the end is reported once** (`FileTrees`, sequential and parallel): the
  folder is kept, instead of being tried and reported a second time as `directory not empty`.
  Tests: `FileOpsTest`, `ProtectedPathsTest`, `FileTreesListingFailureTest`, one or more for each point.
### Fixed (four small defects of the data cmdlets, [#164](https://github.com/yp44/powerj/issues/164))
- **`to-csv` says which columns it drops** (audit v2, U-09). The columns come from the first object, and those of a later
  object that the header does not have were cut without a word (`from-csv` warns of the symmetric case). Each such column is now
  named once, when it first appears, by a non-blocking error.
- **`join` skips an object with an accessor that throws** (R-18) instead of ending the whole join with an `Unreadable`, as
  `to-json` and `to-csv` do (left or right side, `--outer` included).
- **`measure` and `sort` agree on numbers** (C-07). The two copies of the number helpers had parted: `BigInteger` was integral for
  one and not the other, an adder holding 1.5 added up as 1 in `measure` and compared as 1.5 in `sort`, and a `NaN` adder was a
  `NumberFormatException`. `io.powerj.text.Numbers` (`isIntegral`, `fitsLong`, `isFinite`, `decimal`, next to `SizeUnits`) is the
  one helper; `sort` only takes the `long` shortcut for a number that fits one, so two big integers that differ beyond the
  range of a long are no longer equal.
- **`env --append` and `--prepend` do not create a variable from a path** (U-09). `env PATH --append /x` bound `/x` to the
  option and set a variable called `/x` to `PATH`. An argument that cannot be a variable name (`/`, `\` or `=` in it), or two
  values, is now a usage error, and an entry that is already in the list is not added again.
  Tests: `NumbersTest`, `MeasureTest`, `SortTest`, `JoinTest`, `JsonCsvCmdletsTest`, `EnvTest`.

### Fixed (review of the pull request of phase 2 of the audit v2, [#141](https://github.com/yp44/powerj/issues/141))
- **A relative line of `protected-paths` no longer fails the command** (#163): it was reported with `context.error`, so `rm old.txt
  && echo done` did not say done, and `--on-error stop` stopped before touching anything. It is logged at `WARNING`, once per
  session (the API has no channel for a warning).
- **`cat` and `grep` report a name the system cannot spell** (#156) (`invalid path …`) instead of ending in an internal error;
  `FileArguments.expandOrReport` is shared.
- **`env --prepend NAME dir` moves a `dir` that is already in the list to the head** instead of doing nothing (#164): that is how
  a folder is given priority. `--append` still leaves an entry already there where it is.
- **`grep` skips a line that is too long and goes on** (#160), as `cat` does, instead of giving up on the rest of the file; the
  skipped lines are reported after its matches.
- **`kill -w --timeout`** (#161) says `even after a forcible stop` when `-f` was given, and a timeout beyond a year is a year
  instead of an `ArithmeticException`.
- **The hidden attribute of a link followed on Windows** (#162): `FollowedLink` kept only the basic attributes, so a link to a
  hidden file was listed as visible. `FollowedDosLink` keeps the DOS attributes of the target.
- Examined, not changed: the notice of `to-csv` on dropped columns stays a non-blocking error, like `from-csv`'s `csv.extra.fields`
  (the output is incomplete); the chunks of a parallel `grep` interleave between files above 1 000 matches, which FR-64 and the
  help of `-p` now say.

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
