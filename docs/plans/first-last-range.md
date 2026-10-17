# Plan — cmdlets `first`, `last`, `range` (mini-iteration 16, FR-68)

## Context
Phase 2 of the cmdlet roadmap (`docs/plans/cmdlet-roadmap.md`). The backlog (§12.3) lists `first`/`last`. Taking a slice of a pipeline currently needs `collect` and Java (`ls | collect | map { l -> l.subList(0, 5) }`): it is verbose, and it reads the whole input even when only the first objects are wanted.

Decisions taken with the user:
- `skip` is dropped: `range --from n` covers it.
- `range` is added (`--property` has no short form, as in `grep` and `cat`, where `-p` means `--parallel`). Its `--from`/`--to` bounds work **on positions** without `--property`, and **on the value of the property** with `--property`, e.g. `range --from Paul --to Peter --property name`.
- `range --head`/`--tail` are not added: `first`/`last` do it.
- A block bound (`--from { … }`) is always a condition on the object. This is how text lines, which have no property, get value bounds: `cat app.log | range --from { $_.contains("BEGIN") } --to { $_.contains("END") }`.

Core of the phase: a cmdlet must be able to **stop the previous stage**, so that `ls -r / | first 3` or `cat huge.log | range --to 10` answer at once.

Branch: `claude/cmdlet-first`, from `main` @ 3d496e8.

## Syntax
```text
first [<n>]
last [<n>]
range [--from <bound>] [--to <bound>] [--property <name>] [-i|--ignore-case] [-x|--exclusive] [--repeat]
```

### `first`, `last`
| Option | Type | Default | Description |
|---|---|---|---|
| `n` (position 0) | `int` ≥ 0 | 1 | Number of objects kept |

- `first n` emits the first `n` objects as they arrive, then stops the previous stage.
- `last n` emits the last `n` objects, in their order, once the input has ended.
- `n` < 0 is a blocking error. `first 0` and `last 0` emit nothing (`first 0` stops the previous stage at once).

### `range`
| Option | Short | Type | Description |
|---|---|---|---|
| `--from` | — | bound | First object kept. Absent: from the first object. |
| `--to` | — | bound | Last object kept. Absent: up to the end. |
| `--property` | — | property name or path | `--from`/`--to` are then text patterns searched in this property. |
| `--ignore-case` | `-i` | boolean | Patterns ignore case (with `--property`). |
| `--exclusive` | `-x` | boolean | The objects matching `--from` and `--to` are not emitted (only those between them). |
| `--repeat` | — | boolean | After `--to`, waits for the next `--from` match and emits a new range (like `sed -n '/a/,/b/p'`). Needs a value or condition `--from`. |

A **bound** is read as follows:

| Bound | Without `--property` | With `--property` |
|---|---|---|
| Integer (`5`) | **Position**, 1-based, inclusive: `--from 5` starts at the 5th object, `--to 10` ends at the 10th. | Pattern `5` searched in the property's text. |
| Text (`Paul`) | Blocking error: `range: --from Paul is not a position; add --property <name> or use a block { … }`. | **Pattern** (Java regex, searched like `grep`): `Paul` = "contains Paul", `^Peter$` = "equals Peter". |
| Block (`{ $_.age > 30 }`) | **Condition** on the object (must return a boolean, like `where`). | Same: a block ignores `--property`. |

The two bounds are independent: `range --from 3 --to { $_.done }` (position, then condition) is allowed.

## Input / output
- Input: any object (`Cmdlet<Params, Object, Object>`), including `String` lines of native commands and of `cat`.
- Output: the same objects, unchanged.

## Behaviour
### Ranges
- **Positions** count every received object from 1 (also the objects skipped by an evaluation error).
- **Start:** with no `--from`, the range is open from the first object. Otherwise it opens on the first object that reaches the position, or matches the pattern or condition.
- **End:**
  - The `--to` test starts **with the object after the opening one**, as in `sed`. With `--from Paul --to Paul`, the range runs from one `Paul` to the next.
  - A position `--to` is absolute: if `--to` is lower than `--from`, nothing is emitted.
  - With no `--to`, the range stays open until the input ends.
- **Bounds:** included by default. `-x` excludes the opening and closing objects.
- **Early stop:** once a range is closed, `range` calls `stopInput()`, unless `--repeat` is given. With `--repeat` it waits for the next opening, and the input is read to the end. `--repeat` with a position `--from` is a blocking error.
- **Pattern matching:** the property value is converted with `String.valueOf` and searched with `Matcher.find()`. A `null` value never matches. An invalid regex is a blocking error with its position (same message as `grep`).
- **Errors:** a property evaluation error, or a block that throws or does not return a boolean, gives a non-blocking error (`pipeline.object.skipped`). The object is then neither emitted nor used as a bound.

### Stopping the previous stage — API and core change
- `powerj-api/.../CmdletContext.java` gets a new method:
  ```java
  /** The cmdlet will read no more objects: the previous stage is stopped, {@link Cmdlet#process} is no longer
   *  called, and {@link Cmdlet#end} is called. No effect at the head of a pipeline. */
  default void stopInput() {}
  ```
  The default does nothing, so existing contexts (tests, third-party code) still compile.
- `powerj-core/.../CmdletRunner.java`:
  - `Context.stopInput()` sets a flag.
  - The `invoke` loop checks the flag after each `process`. When set, it leaves the loop, calls `input.abort()` and then `end()`.
  - `Pipe.abort()` already interrupts the writing thread. That thread already ends quietly on `CancellationException` (`Interpreter`, pipeline stages): no error, no change to `$?`.
  - When the previous stage is a native command, `NativeRunner` already stops the process through `onAbort`.
  - A non-zero exit code of that stopped process must not count: the spec requires `git log | first 1` to leave `$?` true, and the tests check it.
- Third-party cmdlets get the same ability (§4.2 updated).

### Other
- **Preview:** `preview = true` for all three. `ls -r / | first 3` shows its result in preview mode instead of running past 400 ms.
- **Cancellation:** Ctrl+C works as for every cmdlet (`emit` throws).
- **Memory:** `last n` keeps at most `n` objects (`ArrayDeque`). `first` and `range` keep none.

## Implementation
New in `powerj-cmdlets/src/main/java/io/powerj/cmdlets/`:
- **`First.java`**
  - `@CmdletInfo(name = "first", category = "Filters", preview = true)`; `Params(@Option(position = 0) Integer count)`.
  - Per-execution counter in `Collections.synchronizedMap(new WeakHashMap<>())`, keyed by context as in `Collect`.
  - Calls `context.stopInput()` once `n` objects are emitted; with `n = 0`, already in `begin`. It ignores any further `process` call, for contexts without `stopInput`.
- **`Last.java`**: ring buffer, emitted in `end`.
- **`Range.java`**
  - `Params(Object from, Object to, String property, boolean ignoreCase, boolean exclusive, boolean repeat)`.
  - `begin` turns each bound into a `Bound` (sealed interface): `Position(long n)`, `Pattern(ScriptBlock property, java.util.regex.Pattern regex)`, or `Condition(ScriptBlock block)`.
  - The property block comes from `Keys.block(property, …)` (phase 1).
  - The state (position, open/closed) is kept per execution.
- **Messages** (`messages_en`/`_fr`):
  - `first.summary`, `first.option.count`, `last.*`, `range.summary`, `range.option.*`;
  - `count.negative`: `expects a number of at least 0, got {0}`;
  - `range.not.position`, `range.repeat.position`;
  - `range.pattern.invalid` (reuse the wording of `grep.pattern.invalid`).
- **`BuiltinCmdlets`**: register the three cmdlets.

Changed in `powerj-api` / `powerj-core`: `CmdletContext.stopInput()`, `CmdletRunner` (`Context` and the `invoke` loop). Tests use `TestContext`, which records the `stopInput` call.

Docs:
- `docs/SPECIFICATION.md`: new section **FR-68** (after FR-67), `stopInput` in the extension API (§4.2), §12.3 rows (`first`/`last` done, `range` added), row 16 in the §11 table, the scope sentence.
- `docs/recettes/first-last-range.md`: acceptance checklist.
- `README.md`: Cmdlets table, roadmap.
- `docs/plans/cmdlet-roadmap.md`: phase 2 = `first`, `last`, `range`.

## Tests
- **`FirstTest`**:
  - `n` objects, then `stopInput` called;
  - default 1;
  - `0`;
  - fewer objects than `n`;
  - negative → error;
  - `process` after the stop is ignored.
- **`LastTest`**: last `n` in order; fewer objects than `n`; `0`.
- **`RangeTest`**:
  - positions: `--from`, `--to`, both, `--to` < `--from`;
  - pattern: `--from Paul --to Peter --property name`, contains vs `^…$`, `-i`;
  - block conditions: block not boolean → error, object skipped;
  - mixed position + condition;
  - `--to` tested from the next object (same pattern on both bounds);
  - `-x`;
  - `--repeat` gives several ranges and does not call `stopInput`;
  - `--repeat` with a position → error;
  - text `--from` without `--property` → error;
  - invalid regex;
  - `stopInput` called right after the closing object.
- **`powerj-core` `PipelineTest`** (fake cmdlets):
  - a stage calling `stopInput()` stops an endless producer (the test ends);
  - `end()` is still called;
  - `$?` stays true;
  - a native producer (`yes` on Unix) is stopped and `$?` stays true.
- **`powerj-shell` `BuiltinCmdletsTest`**:
  - `ls | first 1`, `ls | last 1`, `ls | sort name | range --from 2 --to 2`;
  - `List.of("a","Paul","b","Peter","c") | range --from Paul --to Peter`.

## Verification
- `./mvnw verify` (locally `-Djava.release=25`; CI uses JDK 27).
- Manual:
  - `ls -r / | first 3` (instant);
  - `cat big.log | range --to 10`;
  - `cat app.log | range --from { $_.contains("BEGIN") } --to { $_.contains("END") } --repeat`;
  - `env | range --from JAVA --to PATH --property name`;
  - `git log --oneline | first 5` then `$?`;
  - preview of `ls -r / | first 3`;
  - `help range`.

## Open questions
1. Should `--to` be tested from the object after the opening one (proposed, as in `sed`: `--from Paul --to Paul` runs from one Paul to the next), or on the opening object itself (then `--from Paul --to Paul` gives a single object)?
2. Should a pattern with `--property` be a regex searched in the text (proposed, consistent with `grep`: `Paul` = contains, `^Peter$` = equals), or plain "contains" text?
