# Plan — cmdlets `count` and `measure` (mini-iteration 18, FR-70)

## Context
Phase 4 of the cmdlet roadmap (`docs/plans/cmdlet-roadmap.md`). The backlog (§12.3) lists "count, sum, avg, min, max".

Java streams already compute these values:

```text
(ls -r | collect).stream().mapToLong(f -> f.size).summaryStatistics()
```

That line has three drawbacks:
- it is long;
- it shows raw numbers (`sum=4521984312`);
- `collect` keeps every object in memory first.

Decisions taken with the user:
- Both cmdlets are kept: `count` for counting, and `measure` for statistics.
- `measure` needs no `--sum`/`--avg`/`--min`/`--max` options. It always computes everything, which costs nothing, and the result is one object read like any other (`(ls -r | measure size).sum`).

Branch: `claude/cmdlet-measure`, from `main` @ b11dc07.

## Syntax
```text
count
measure [<key>]
```

- **`count`** emits one `Long`: the number of objects received (a `null` object counts). At the head of a pipeline, or with no input, it emits `0`.
- **`measure`** emits one `Row` with the columns `count sum average min max`.
  - Without a key, it measures the objects themselves (`List.of(3, 1, 2) | measure`).
  - A key is a property, a property path or a block, read with `Keys` as for `sort`: `ls -r | measure size`, `ls | measure { $_.name.length() }`.

## Behaviour of `measure`
- **Streaming:** values are folded as they arrive, in constant memory. The `Row` is emitted when the input ends.
- **`count`:** the number of values measured. A `null` value is left out, like PowerShell's `Measure-Object`.
- **`sum` / `average`:**
  - computed when every value is a `Number`;
  - integral values are summed exactly, as a `Long`, or as a `BigInteger` beyond the `long` range;
  - as soon as one value is not integral, `sum` is a `Double`;
  - `average` is a `Double`;
  - if one value is not a number (`String`, date…), `sum` and `average` are empty, with no error. `min`/`max` still work, which is what `measure name` or `measure modified` need.
- **`min` / `max`:** compared with the `sort` rules (`SortComparator`): numbers by value, strings, other `Comparable`s.
  - For values that cannot be compared with each other, the text is compared and one non-blocking error is reported, as with `sort`.
- **No value:** count `0`, the other columns empty.
- **Display as sizes:** when the key is a simple property that is a `@Bytes` component (`measure size` after `ls`), `sum`, `average`, `min` and `max` are size columns of the `Row` (`4.2 GB`). The `Row` from `select` uses the same mechanism.
- **Key evaluation error:** non-blocking error (`pipeline.object.skipped`); the object is not measured.
- **Preview:** `preview = true` for both, since they change nothing.

## Implementation
- **`powerj-cmdlets/.../Count.java`**
  - `@CmdletInfo(name = "count", category = "Filters", preview = true)`, `Cmdlet<Count.Params, Object, Long>`.
  - A per-execution counter (`WeakHashMap` by context), emitted in `end`.
- **`powerj-cmdlets/.../Measure.java`**
  - `Cmdlet<Measure.Params, Object, Row>`, with `Params(@Option(position = 0) Object key)`.
  - State per execution: count, exact sum (`BigDecimal`, plus an "all integral" flag), a "numeric" flag, min, max, a `SortComparator`, and whether the key is a size.
  - `end` builds the `Row` with `Row.of(values, sizeColumns)`.
  - The size check is the same as `Select`'s (`@Bytes` component of the first record). It is moved into a small shared helper, `Sizes.isSizeProperty(Object first, String property)`, used by both cmdlets.
- **Messages** (`messages_en`/`_fr`): `count.summary`, `measure.summary`, `measure.option.key`, `measure.key.invalid`.
- **`BuiltinCmdlets`:** register both cmdlets.
- **No change in `powerj-core`:** `Row` tables, property access and completion of a `Row` held in a variable already exist (phase 3).

## Tests
- **`CountTest`**: objects counted, including `null`; no input → `0`.
- **`MeasureTest`**:
  - integers → `Long` sum, exact average, min, max;
  - mixed `Integer`/`Long`/`Double` → `Double` sum;
  - `long` overflow → `BigInteger`;
  - `null` values left out;
  - strings → empty sum and average, but min and max;
  - dates → min and max;
  - property key and block key;
  - `@Bytes` key → size columns, while a block key gives none;
  - key that throws → error, object skipped;
  - no value → count `0`;
  - mixed non-comparable values → one error.
- **`BuiltinCmdletsTest`**:
  - `ls | count`;
  - `ls --files | measure size`, table with sizes;
  - `(ls --files | measure size).sum`;
  - `List.of(1, 2, 3) | measure`.

## Verification
- `./mvnw verify` (locally `-Djava.release=25`; CI uses JDK 27).
- Manual:
  - `ls -r | count`
  - `ls -r | measure size`
  - `ls | measure modified`
  - `env | measure { $_.value.length() }`
  - `(ls -r | measure size).average`
  - `help measure`
