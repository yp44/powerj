# Plan — 10 phases of cmdlets to make PowerJ truly usable

## Context
PowerJ ships 7 cmdlets (`ls`, `grep`, `cat`, `where`, `map`, `collect`, `env`). Sorting, projection, aggregation, file operations, export and processes are missing, so users fall back to native commands and lose objects. The backlog is listed in `docs/SPECIFICATION.md` §12.3. It is delivered in **10 phases**, each following the cycle used for `cat` (mini-iteration 13). All artifacts (specs, plans, code, comments, commits, PRs) are written **in English**.

Target pipeline at the end (§12.3):
`find src --name *.java --since 7d | where { $_.size > 2kb } | group { $_.path.parent } | sort count --desc | first 5 | to-json report.json`

## Per-phase workflow
1. **Branch**: `claude/cmdlet-<name>`, created from an up-to-date `main` (after the previous phase is merged).
2. **Specification first** (first commit of the branch, reviewed before any code):
   - `docs/plans/<name>.md`: the implementation specification, in the format of `docs/plans/cat.md`, with these sections:
     - *Context*: the need, and the decisions taken with the user.
     - *Syntax*: full command line, an option table (short/long name, type, default, description), positional arguments.
     - *Input / output*: accepted input types, output records (fields, `@Display` columns), unrolling behaviour.
     - *Behaviour*: streaming vs. accumulation, ordering, `null` handling, blocking and non-blocking errors (exact messages), cancellation, preview eligibility.
     - *Implementation*: the classes to add or change and their responsibilities, per-execution state, existing helpers reused (with file paths), API or core changes and their compatibility impact.
     - *Tests*: the list of test cases per class.
     - *Verification*: build commands and manual REPL commands.
     - *Open questions*: answered with the user before coding.
   - `docs/SPECIFICATION.md`: new functional requirement section (FR-67, FR-68…) with syntax, options, records and acceptance criteria (CA), written in the same commit so the spec and the plan agree.
3. **Implementation + tests + docs** (following commits).
4. **PR to `main`**, green CI, merge. The next phase starts from the merged `main`.

## Common checklist (every phase)
- Code in `powerj-cmdlets/src/main/java/io/powerj/cmdlets/`: class with `@CmdletInfo(name, category, summary, examples, preview)` implementing `Cmdlet<Params, I, O>`, a nested `record Params` with `@Option`, and output records with `@Display` and `Objects.requireNonNull` in compact constructors.
- Per-execution state in a synchronized map keyed by context/params (pattern of `Collect.received` and `Where.conditions`): the same instance can run in several pipelines at once.
- Registration in `BuiltinCmdlets.cmdlets()`.
- i18n: `<name>.summary`, `<name>.option.*` and the error keys in both `messages_en.properties` and `messages_fr.properties`.
- Errors: `IllegalArgumentException` for wrong usage (blocking; `CmdletRunner` turns it into a `PjException`), `context.error(...)` for non-blocking errors, reusing `pipeline.object.skipped` and `where.property.invalid`.
- `preview = true` only for side-effect-free cmdlets; otherwise `previewable(params)` returns `false` when the options change something.
- Cancellation: rethrow `CancellationException`, and check `Thread.interrupted()` in long loops (as in `grep` and `cat`).
- Tests: `powerj-cmdlets/src/test/java/io/powerj/cmdlets/<Name>Test.java` with `TestContext` (simulated compiler) and `@TempDir`; REPL tests in `powerj-shell` when core code changes.
- Docs: §12.3 row struck through as "done", a row in the §11 mini-iteration table (after 14), the scope sentence (~l.406), an acceptance checklist `docs/recettes/<name>.md` (format of `grep.md`), the README Cmdlets table, and README "Status and roadmap".
- Verification: `./mvnw -pl powerj-cmdlets -am test`, then `./mvnw verify`, then the manual checklist with `powerj -c "…"` and `help <name>`.

---

## Phase 1 — `sort` (mini-iteration 15, FR-67), `claude/cmdlet-sort`
- Spec covers: `sort [key...] [-d|--desc] [-i|--ignore-case]`. A key is a property (`sort size`), a path (`sort path.fileName`) or a block (`sort { $_.name.length() }`); with no key, natural order (`Comparable`). With several keys, ties on the first key are broken by the next one.
- Foundation for later phases: a package-private helper `Keys.java` that turns an argument (a String validated by `Where.PROPERTY`, or a `ScriptBlock`) into a `ScriptBlock` via `context.compile("$_." + prop)`. `select`, `group`, `uniq` and `measure` reuse it.
- Behaviour: accumulate in `process`, then sort and emit in `end`. The sort is stable. `null` sorts last. Keys that cannot be compared raise a non-blocking error and fall back to `toString`. `preview = true`.
- Tests: numbers, strings, `FileEntry` records, `--desc`, several keys, `null`, a block that throws (the object is skipped), an invalid key.

## Phase 2 — `first` / `last` / `range` (FR-68), `claude/cmdlet-first`
- Spec: `docs/plans/first-last-range.md`. `first [n=1]`, `last [n=1]`; `range [--from b] [--to b] [--property p] [-i] [--include|--exclude] [--repeat]`: a bound is a position without `--property`, a regex on the property's text with `--property`, and a block is always a condition. `skip` is dropped: `range --from n` replaces it.
- **API change**, the core of the phase: `CmdletContext.stopInput()`. `CmdletRunner` leaves its loop, calls `end()` and `input.abort()`, so the previous stage (cmdlet or native process) is stopped silently. `ls -r / | first 3` and `range --to 10` answer at once.
- `last` keeps a ring buffer (`ArrayDeque`) of size n. All three are `preview = true`.
- Tests: unit tests per cmdlet; core tests (an endless producer is stopped, `$?` stays true, also for a native producer); REPL tests.

## Phase 3 — `select` (FR-69), `claude/cmdlet-select`
- Spec: `docs/plans/select.md`. `select <column>...` with properties, paths (`path.fileName` → column `fileName`), renames (`file=name`) and computed columns (`kb={ $_.size / 1kb }`). No `--expand` in v1 (decided with the user).
- Output: a new API type `io.powerj.api.Row`, an immutable ordered `Map<String, Object>`, so `$_.kb`, `sort kb`, `where` work unchanged. Size columns taken from a `@Bytes` component keep their display.
- `OutputFormatter` displays `Row`s as a table (its `Column` is generalized to a value reader). `Completions` proposes the selected columns after a `select` stage.
- A missing property gives an empty cell and one non-blocking error per column.

## Phase 4 — `count` and `measure` (FR-70), `claude/cmdlet-measure`
- Spec: `docs/plans/count-measure.md`. `count` emits one `Long`. `measure [key]` emits one `Row` with `count sum average min max`, always all of them (no `--sum`/`--avg` options), computed in constant memory.
- `sum`/`average` when every value is a number (exact integral sum); `min`/`max` with the `sort` rules (dates, strings). Size keys (`@Bytes`) give size columns, through the `Sizes` helper shared with `select`.
- Kept although Java streams can do it: shorter, readable sizes, and no `collect` of the whole input in memory (decided with the user).

## Phase 5 — `group` (FR-71), `claude/cmdlet-group`
- Spec: `docs/plans/group.md`. `group [key...] [-n|--no-items]` emits one `record Group(Object key, long count, List<Object> items)` per key (`@Display(columns = {"key","count"})`), in first-seen order. Several keys give a list key; integral numbers are compared by value.
- Decided with the user: a fixed record (not a `Row` named after the key), several keys allowed, only `-n` as an option (no `-i`, no sorted output). `preview = true`; reuses `Keys`.

## Phase 6 — `uniq` — cancelled
- Cancelled by the user: `group` (phase 5) already covers `uniq -c` (`cat a.log | group`).

## Phase 7 — `find` — cancelled
- Cancelled by the user.

## Phase 8 — `mkdir`, `touch`, `cp`, `mv`, `rm` (FR-72), `claude/cmdlet-fileops`
- Spec: `docs/plans/fileops.md`. Paths from arguments (wildcards) or from the pipeline (`String`/`Path`/`File`/`FileEntry`/`--property`); for `cp`/`mv`, the last argument is the destination.
- Decided with the user:
  - `mkdir`/`touch` emit the `FileEntry` created; `cp`/`mv`/`rm` are silent except with `-v`;
  - `cp`/`mv` refuse to overwrite without `-f`;
  - `rm` needs `-r` for a non-empty folder;
  - `-n`/`--dry-run` everywhere (emits `FileOperation`s);
  - `rm` and `mv` refuse protected paths, listed in `~/.powerj/protected-paths` (created at first launch with platform defaults), unless `--allow-protected` is given.
- API: `CmdletContext.configDirectory()`. Symbolic links never followed; `preview = false` for all five.
- Follow-up (FR-72b, `claude/cmdlet-fileops-parallel`, spec `docs/plans/fileops-parallel.md`): `rm -r` removes while walking (no list in memory); `cp -r` and `rm -r` get `-p/--parallel <n>` on virtual threads (`io.powerj.fs.FileTrees`).

## Phase 9 — `to-json`, `from-json`, `to-csv`, `from-csv` (FR-73), `claude/cmdlet-json-csv`
- Spec: `docs/plans/json-csv.md`.
- No external dependency: the project has none beyond JLine/JUnit and ships a modular jlink runtime. An in-house writer and recursive-descent parser go in `powerj-cmdlets/.../json/` (sealed token types, pattern matching).
- `to-json [file] [--compact] [--depth n]` handles records, `Row`, `Map`, collections and scalars (`Path`, ISO-8601 dates, enums as strings, sizes in bytes). With no file it emits a `String`. With a file it writes one JSON array; `previewable` is then `false`.
- `from-json [file]`, or pipeline `String` input (`cat x.json -t | from-json`), emits `LinkedHashMap`/`List`; a root array is unrolled.
- `to-csv [file] [--delimiter ,] [--no-header]` and `from-csv [file] [--delimiter] [--no-header]` follow RFC 4180 (quotes, line breaks inside fields). `from-csv` emits `Row` (phase 3).
- Tests: JSON round trip, Unicode escapes, numbers, invalid JSON (error with position), CSV quoting.

## Phase 10 — `ps` / `kill` (FR-76), `claude/cmdlet-ps`
- `ps [name|glob] [--user u]` uses `ProcessHandle.allProcesses()` and emits `record ProcessEntry(long pid, Long parent, String name, String command, String user, Instant start, Duration cpu)`, with `@Display(columns = {"pid","name","cpu","start"})`. Memory is not available from the JDK and is documented as a limitation. No native calls, so it works on every platform.
- `kill pid… [-f|--force]`, or pipeline `ProcessEntry`/`Long` input (`ps java | kill`), calls `destroy()` / `destroyForcibly()`. `preview = false`. Killing PowerJ's own PID is refused.
- Tests: `ps` sees `ProcessHandle.current()`, name filtering, killing a process started by the test, an unknown PID (non-blocking error).

## Wrap-up (in the phase 10 PR)
- An end-to-end acceptance checklist for the §12.3 target pipeline in `docs/recettes/`.
- README "Status and roadmap": the v1 backlog is done (`tee`, `http`, `open`, `table`/`tree` remain), and the next step is v2 control structures.

## Order and dependencies
1 `sort` (creates `Keys`) → 2 `first`/`last`/`range` (`stopInput` API) → 3 `select` (`Row` in the API) → 4 `measure` and 5 `group` (use `Keys`) → 6 `uniq` → 7 `find` (benefits from `first`) → 8 file ops → 9 JSON/CSV (`from-csv` uses `Row`) → 10 `ps`/`kill`.
Phases 1–3 come first; phases 4–6 and 10 are independent of each other and can be reordered. Phases 6 and 7 were cancelled.
