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

### Planned (review §8)
- **Phase 1 — targeted fixes.** The `System.exit` guard covers method references and reflection; a malformed
  `config.properties` no longer prevents startup; cmdlets get an execution-scoped state in `CmdletContext`
  (`begin`/`process`/`end` always paired); Ctrl+C is no longer swallowed by `to-json`; `sort` uses a total order;
  destructive commands resolve symbolic links and no longer ignore case on case-sensitive file systems.
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
