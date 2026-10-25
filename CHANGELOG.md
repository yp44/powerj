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

### Fixed (Phase 1, targeted fixes — in progress)
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
  stress test: 343,385 failures in 472,431 copies of the environment over two seconds). Variables are in a concurrent map, the state written by the command
  thread is `volatile`, the environment is a synchronized map, and a native command starts from an immutable
  snapshot of it. The automatic variables (`$?`, `$exit`, `$last`…) are an `enum`, not a `switch` on strings.

- **A cmdlet's per-run state belongs to the run, and is released when the run ends** (C-01, step 1.3). A cmdlet is one
  object shared by every command that uses it, so 23 of them kept their state in tables keyed by the context or by the
  (value-compared) parameters: a lock for every object, a leak when the command was cancelled, a collision between two
  `where` stages with equal parameters. `CmdletContext.state(Class, Supplier)` (additive, `default`) gives each run
  its own state; the runner closes the states that are `AutoCloseable` when the run ends, normally or not (Ctrl+C
  included). The 23 tables are gone. `grid` closes the window of a command that was cancelled before it handed it over.

### Examined, not changed
- **The seven `Messages` classes stay** (C-10, step 1.4). Replacing them with one helper in `powerj-api` was tried on a
  two-module experiment: `ResourceBundle.getBundle(…, module)` called from another module fails unless the owner of the
  bundle opens its package to it, which every module (third-party ones included) would have to declare. The per-module
  class is the minimal form that works with JPMS, and the model `examples/greet` gives to module authors.

### Planned (review §8)
- **Phase 1 (continued).** Ctrl+C is no longer swallowed by `to-json`; `sort` uses a total order; destructive
  commands resolve symbolic links and no longer ignore case on case-sensitive file systems.
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
