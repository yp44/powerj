# Plan — cmdlet `sort` (mini-iteration 15, FR-67)

## Context
Phase 1 of the cmdlet roadmap (`docs/plans/cmdlet-roadmap.md`). `sort` is in the backlog (SPECIFICATION §12.3: "Sorting (`--desc`)", output unchanged). Today sorting a pipeline needs `collect` and a Java comparator (`ls -r | collect | map { l -> l.stream().sorted((a, b) -> Long.compare(b.size, a.size)).toList() }`); `sort size --desc` replaces it.

This phase also introduces the `Keys` helper (property name or block → `ScriptBlock`), reused by `select`, `measure`, `group` and `uniq` in the next phases.

Branch: `claude/cmdlet-sort`, from `main` @ fe0efac.

## Syntax
```text
sort [<key>...] [-d|--desc] [-i|--ignore-case]
```

| Option | Short | Type | Default | Description |
|---|---|---|---|---|
| `keys` (position 0, multiple) | — | property name, property path or block | none: the objects themselves | Sort keys, by priority: ties on the first key are broken by the second, and so on |
| `--desc` | `-d` | boolean | `false` | Descending order (applies to all keys) |
| `--ignore-case` | `-i` | boolean | `false` | Compares `String` keys ignoring case (`String.CASE_INSENSITIVE_ORDER`) |

A key is:
- a **property name**: `sort size`, equivalent to `sort { $_.size }`;
- a **property path**: `sort path.fileName`, equivalent to `sort { $_.path.fileName }`;
- a **block**, `$_` or lambda: `sort { $_.name.length() }`, `sort { f -> f.modified }`;
- a **method reference**: `sort FileEntry::size` (it reaches the cmdlet as a `ScriptBlock`, like for `map`).

Property names are validated with `Where.PROPERTY` (`powerj-cmdlets/.../Where.java`); an invalid name is a blocking error (`where.property.invalid`). An argument that is neither a `String` nor a `ScriptBlock` (e.g. a number `sort 3`) is a blocking error `sort.key.invalid`.

`-d` was preferred to Unix `-r` because `-r` means "recursive" everywhere else in PowerJ (`ls`, `grep`); `-i` is the same letter as `grep -i`.

## Input / output
- Input: any object (`Cmdlet<Params, Object, Object>`), including `String` lines of a native command (`git branch | sort`).
- Output: the same objects, reordered. Nothing is wrapped: the table display of records stays as it was.
- A `Collected` list received from `collect` is one object, like everywhere else (FR-36d).

## Behaviour
- **Accumulation**: `process` evaluates the keys of the object **once** and stores `(keys, object)`; `end` sorts and emits. `sort` emits nothing before its input ends (inherent to sorting); memory grows with the input, which is documented in the spec.
- **Stability**: `List.sort` (TimSort) is stable; objects with equal keys keep their input order, also with `--desc` (descending is the reversed comparison, not a reversed list).
- **Comparison of two key values** (`SortComparator`, package-private):
  1. `null` is smaller than any value: first in ascending order, last with `--desc` (decision: same as PowerShell's `Sort-Object`);
  2. two `Number`s are compared by value, whatever their classes (`Integer` 3 < `Long` 10 < `Double` 10.5): through `BigDecimal` when one of them is a `BigDecimal`/`BigInteger` or a floating value, otherwise as `long`; `NaN` after every number;
  3. two `String`s: `compareTo`, or `CASE_INSENSITIVE_ORDER` with `-i`;
  4. two `Comparable`s of which one is an instance of the other's class (`LocalDate`, `Instant`, `Path`, `Boolean`, enums…): `compareTo`;
  5. otherwise (mixed types, non-comparable objects): their `toString()` values are compared, and a single non-blocking error `sort.not.comparable` is reported per execution, naming the two classes.
- **No key**: the objects themselves are compared with the same rules (`(ls)*.name | sort`, `1..` lists, lines of native commands).
- **Key evaluation error** (exception in a block, missing property): non-blocking error `pipeline.object.skipped`, the object is left out (same rule as `where`/`map`).
- **Cancellation**: `CancellationException` is rethrown; `emit` already throws when Ctrl+C is pressed. `List.sort` itself is not interruptible: a huge sort is stopped by the second Ctrl+C (abandonment, FR-48 §3.14).
- **Preview**: `preview = true`; `sort` changes nothing. With a stage before it, the preview shows the sorted values once its input ends; `ls -r / | sort` does not end within 400 ms and shows nothing, which is expected.
- **Native `sort`**: the cmdlet takes precedence (FR-13); `^sort data.txt` or `native.prefer=sort` (FR-16) run the native program.

## Implementation
New files in `powerj-cmdlets/src/main/java/io/powerj/cmdlets/`:
- `Sort.java`: `@CmdletInfo(name = "sort", category = "Filters", preview = true, summary, examples)`, `Cmdlet<Sort.Params, Object, Object>`.
  - `record Params(@Option(position = 0) List<Object> keys, @Option(shortName = 'd') boolean desc, @Option(shortName = 'i') boolean ignoreCase)`.
  - Per-execution state `Map<CmdletContext<?>, State>` (`Collections.synchronizedMap(new WeakHashMap<>())`, as in `Collect.received`), where `State(List<ScriptBlock> keys, List<Entry> entries, SortComparator comparator)` and `record Entry(Object[] keys, Object value)`.
  - `begin` builds the keys with `Keys.blocks(...)`; `process` evaluates them (or uses the value itself when there are none); `end` sorts and emits, then removes the state.
- `Keys.java`: package-private helper.
  - `static ScriptBlock block(Object argument, CmdletContext<?> context, String invalidKey)`: a `ScriptBlock` is kept as is; a `String` is validated with `Where.PROPERTY` and compiled with `context.compile("$_." + name)`; anything else throws `IllegalArgumentException(Messages.get(invalidKey, …))`.
  - `static List<ScriptBlock> blocks(List<Object> arguments, CmdletContext<?> context, String invalidKey)`.
- `SortComparator.java`: package-private `Comparator<Object>` implementing the rules above. It is built per execution (it carries `ignoreCase` and the "already reported" flag) and reports through the context.

Changed files:
- `BuiltinCmdlets.java`: add `new Sort()`.
- `messages_en.properties` / `messages_fr.properties`:
  - `sort.summary`, `sort.option.keys`, `sort.option.desc`, `sort.option.ignore-case`;
  - `sort.key.invalid`: `sort key expected: property name or block { … }, got {0}`;
  - `sort.not.comparable`: `cannot compare {0} with {1}: these values are sorted by their text`.

Docs:
- `docs/SPECIFICATION.md`:
  - new section **FR-67 — `sort`** after FR-65;
  - the §12.3 backlog row is struck through as done;
  - row 15 in the §11 mini-iteration table;
  - `sort` is mentioned in the scope sentence of §3.9.
- `docs/recettes/sort.md`: an acceptance checklist in the format of `grep.md`.
- `README.md`: a row in the Cmdlets table, and the "Status and roadmap" update.

## Tests
`powerj-cmdlets/src/test/java/io/powerj/cmdlets/SortTest.java` uses `TestContext` and its simulated `compiler`, which maps `$_.size` etc. to a block reading the record accessor, as in `GrepTest`.

- Ordering:
  - no key: strings, integers, mixed `Integer`/`Long`/`Double`;
  - `--desc`;
  - `-i` on strings that differ only by case;
  - stability: equal keys keep their input order, ascending and descending.
- Keys:
  - a property key on `FileEntry` records (`size`);
  - a property path;
  - a block key (a `ScriptBlock` stub);
  - two keys (`dir` then `name`).
- Edge values:
  - `null` values and `null` keys sort first ascending, last with `--desc`;
  - mixed types (`String` and `Integer`): sorted by text, one error reported, not one per comparison.
- Errors:
  - a block that throws: an error, and the object is left out;
  - an invalid property name, and a non-string, non-block key: `IllegalArgumentException`.
- Empty input: nothing is emitted.

`KeysTest.java` covers block passthrough, compilation of a property, and rejection of invalid names.

## Verification
- `./mvnw -pl powerj-cmdlets -am test`, then `./mvnw verify`.
- Manual checks (`powerj -c "…"` and the REPL):
  - `ls | sort size --desc`
  - `ls | sort dir name`
  - `ls -r | sort { $_.name.length() } -d`
  - `(ls)*.name | sort -i`
  - `git branch | sort`
  - `^sort a.txt` (native)
  - `help sort`
  - preview with Alt+V on `ls | sort size -d`
- Commit on `claude/cmdlet-sort` and push; a PR to `main` once the implementation is done.

## Decisions (open questions answered)
1. `-d`/`--desc` applies to all keys; no per-key direction in v1.
2. `null` sorts first in ascending order, as PowerShell's `Sort-Object` does (so last with `--desc`).
3. No `sort -u`: deduplication is left to `uniq` (phase 6).
