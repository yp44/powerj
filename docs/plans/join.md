# Plan — cmdlet `join` (mini-iteration 27, FR-78)

## Context
Crossing two sources of objects (countries × zip codes, files × metadata) needed `collect` and Java loops. `join` multiplies the pipeline objects by those of a second source that have the same key, and gives merged `Row`s that `select`, `sort`, `to-csv` and `grid` use as they are:

```text
cat countries.csv | from-csv | join -r { (cat zip.csv | from-csv) } --left-key statezip --right-key { $_.zipcode.toUpperCase() }
```

Decisions taken with the user:
1. The output is a **merged `Row`**: the columns of the left object, then those of the right object.
2. The **pipeline is the left side** and `--right { … }` the right side; at the head of a pipeline, `--left { … }` gives the left side. The form `select a={…} b={…} | join …` is not kept.
3. **Inner** join by default, **left outer** with `-o`/`--outer`.
4. Keys use **`$_` in each block**, with the rules of `sort` and `group` (`Keys`); no `$left_`/`$right_` variables, so the language is unchanged.

## Syntax
```text
join --right <block> [--left <block>] (--key <key> | --left-key <key> [--right-key <key>]) [-o|--outer]
```

| Option | Short | Type | Description |
|---|---|---|---|
| `--right` (required) | `-r` | block | Right side, evaluated once with no current object; its result is unrolled. |
| `--left` | `-l` | block | Left side at the head of a pipeline; an error after a `\|`. |
| `--key` | `-k` | property, path or block | Key of both sides. |
| `--left-key` | — | idem | Key of the left objects. |
| `--right-key` | — | idem | Key of the right objects; the left key by default. |
| `--outer` | `-o` | boolean | Keeps the left objects without a match, with empty right columns. |

## Behaviour
- **Hash join:** the right side is read in `begin` and indexed `key → objects` (input order kept); the left side is streamed.
- **Keys:** `equals`, integral numbers as `Long` (`Keys.normalized`, shared with `group`); a `null` key matches nothing.
- **Columns:** `Properties` (FR-73); a taken name becomes `right_<name>`; with `--key <property>`, the right key column is left out. Size columns are kept (`Row.sizeColumns`, `@Bytes` components).
- **Outer without match:** the right columns are those of the first right object, all `null`; none if the right side is empty.
- **Errors:** blocking for a missing side or key, `--key` with `--left-key`/`--right-key`, `--left` after a `|`, an invalid property, a failing side block; non-blocking (`pipeline.object.skipped`) for a failing key.
- **Preview:** `preview = true`. Category `Filters`.

## Implementation
- `powerj-cmdlets/.../Join.java`: the cmdlet, state per context in a synchronized `WeakHashMap`, as for `select` and `group`.
- `Keys.normalized`: moved from `GroupCmdlet`, used by both.
- `BuiltinCmdlets`: `new Join()`.
- Messages `join.*` in `messages_en.properties` and `messages_fr.properties`.
- Specification: FR-78, the cmdlet list of §3.9, §11 and §12.3; README.

## Tests
- `JoinTest`: inner join and multiplication, different keys and blocks, default right key, `-o`, `-o` with an empty right side, `null` keys and `1`/`1L`, `right_` prefix, records and plain values with sizes, head of a pipeline, key errors, blocking errors.
- `BuiltinCmdletsTest.joinMultipliesTheObjectsByKey`: the real shell with two CSV files.
- Manual acceptance test: `docs/recettes/join.md`.
