# Plan — cmdlet `select` (mini-iteration 17, FR-69)

## Context
Phase 3 of the cmdlet roadmap (`docs/plans/cmdlet-roadmap.md`). The backlog (§12.3) lists `select` as "Attribute projection", with output `Row`.

Today, choosing the displayed columns or computing a column needs `map` and a hand-built object. The display then falls back to the plain text of that object:

```text
ls | map { f -> f.name + " " + f.size / 1kb }
```

With `select`, the result keeps the table display and the properties stay usable downstream:

```text
ls | select name kb={ $_.size / 1kb } | where { $_.kb > 10 }
```

Decisions taken with the user:
1. A computed or renamed column is written **`name={ … }`** or **`name=property`**, next to its value: `select name kb={ $_.size / 1kb }`, `select file=name size`.
2. A property missing on an object gives an **empty cell (`null`) and one non-blocking error per column**, so a typo (`select nmae`) is visible but the pipeline goes on.
3. **No `--expand` in v1**: the PowerShell form duplicates `map { $_.prop }`, and the "one row per element" form has few uses yet. It can come back with `group` (phase 5).
4. **Tab completion** after `select` proposes the selected columns: `ls | select name size | where { $_.<Tab>` suggests `name` and `size`.

Branch: `claude/cmdlet-select`, from `main` @ 064745f.

## Syntax
```text
select <column>...
```

| Column form | Example | Column name | Value |
|---|---|---|---|
| Property | `name` | `name` | `$_.name` |
| Property path | `path.fileName` | `fileName` (last segment) | `$_.path.fileName` |
| Renamed property | `file=name` | `file` | `$_.name` |
| Computed column | `kb={ $_.size / 1kb }` | `kb` | the block's result |
| Unnamed block | `{ $_.name.length() }` | `column1`, `column2`… (in order of the unnamed blocks) | the block's result |

- At least one column is required; `select` with no column is a blocking error.
- A column name must be an identifier (`[\p{L}_][\p{L}\p{N}_]*`), so that `$_.<name>` works downstream. An invalid name is a blocking error.
- Two columns with the same name is a blocking error naming the duplicate. Example: `select path.fileName name=fileName`.
- Parsing needs no change: the lexer cuts the word `kb=` before `{`, so `select` receives the word `kb=` followed by the block, and `file=name` arrives as one word.

## Input / output
- **Input:** any object, whether records, `Map`s, `String` lines or Java objects. Properties are resolved by the shell's usual rule (`PropertyAccess`, FR-28): record component, getter, public field, then `Map` key.
- **Output:** one **`io.powerj.api.Row`** per object received. It is a new API type:
  - an **immutable, ordered `Map<String, Object>`** (`extends AbstractMap`), with column name → value;
  - since it is a `Map`, the rest already works: `$_.kb` and `row.kb` are read through the existing `Map`-key rule; `sort kb`, `where`, `range --property` and `first` work unchanged; the pipeline never unrolls it, because a `Map` is not unrolled (FR-30b);
  - it also carries the set of **size columns** (`Set<String> sizeColumns()`): a column taken directly from a `@Bytes` record component (`select name size` on `ls`) is still displayed `14.2 KB`;
  - a computed column, such as `kb={ … }`, is displayed as a plain number.

## Behaviour
- **Streaming:** each object gives its `Row` immediately, with nothing kept in memory.
- **Missing property or evaluation error:**
  - the cell is `null`, shown as an empty cell, and the `Row` is still emitted;
  - the first failure of each column reports one non-blocking error: `select: column nmae: Item has no property 'nmae' (properties: …)`;
  - later failures of that column are silent, so the output stays readable on mixed inputs.
- **Display (`OutputFormatter`)**, for consecutive `Row`s with the same columns:
  - shown as one **table**, whatever the number of columns, because the user chose them; the table is cut to the terminal width as today;
  - numbers are right-aligned; size columns are shown as sizes;
  - a `Row` with different columns starts a new table.
- **Preview:** `preview = true`, since `select` changes nothing.
- **Cancellation:** standard. A block that throws `CancellationException` rethrows it.

## Implementation
**New**
- `powerj-api/src/main/java/io/powerj/api/Row.java`:
  - `public final class Row extends AbstractMap<String, Object>`, backed by a `LinkedHashMap` copy;
  - `static Row of(Map<String, ?> values)` and `static Row of(Map<String, ?> values, Set<String> sizeColumns)`;
  - `entrySet()` is unmodifiable and the order is kept;
  - `sizeColumns()`;
  - `equals`/`hashCode` come from `AbstractMap`, and `toString` gives `{name=…, size=…}`.
- `powerj-cmdlets/.../Select.java`: `@CmdletInfo(name = "select", category = "Filters", preview = true)`, `Cmdlet<Select.Params, Object, Row>`, `Params(@Option(position = 0, mandatory = true) List<Object> columns)`.
  - `begin` parses the arguments into `List<Column>`, where `record Column(String name, ScriptBlock value, String property)`:
    - a word `x=`, then a block → named block column;
    - a word `a=b` → renamed property;
    - a word `p` or `p.q` → property;
    - a block alone → `columnN`.
  - Property values reuse `Keys.block(...)`, from phase 1.
  - **Size columns:** for a single-segment property, `Select` checks on the first record received whether that component has `@Bytes`.
  - Per-execution state lives in the usual `WeakHashMap` keyed by context: the columns, the set of columns already reported, and the size columns found.
- `messages_en` / `messages_fr`:
  - `select.summary`, `select.option.columns`;
  - `select.column.expected`, `select.column.invalid`, `select.column.duplicate`;
  - `select.column.failed`: `column {0}: {1}`.

**Changed**
- `powerj-cmdlets/.../BuiltinCmdlets.java`: register `new Select()`.
- `powerj-core/.../OutputFormatter.java`:
  - a `case Row row -> row(row)` placed **before** `case Map`;
  - the private `Column` record is generalized: it reads a value through a `Function<Object, Object>` instead of a `Method`, so record columns and `Row` columns share the table code (sample, widths, truncation, highlight);
  - right alignment for a `Row` column is decided on the values of the sample (all `Number`s), because there is no declared type;
  - the table type key becomes the record class or the `Row` column list.
- `powerj-core/.../Completions.java`:
  - `upstreamType` recognizes a `select` stage and returns the column names written in it (parsed with the same rules as `Select`, from the text of the stage);
  - `$_.` and the lambda parameters then complete those names;
  - the change stays inside `Completions`: a `Scope` gains an optional list of known property names.
- Docs:
  - `docs/SPECIFICATION.md`:
    - **FR-69** after FR-68;
    - `Row` in the extension API (§4.2), and in the object model (§3.7) next to `Collected`;
    - §12.3 row struck through as done, with `--expand` noted as not done;
    - row 17 of the §11 table and the scope sentence.
  - `docs/recettes/select.md`.
  - `README.md` (Cmdlets table, module row, roadmap).
  - `docs/plans/cmdlet-roadmap.md` (phase 3 without `--expand`).

## Tests
- `Row` (tested in `powerj-cmdlets` `SelectTest`, because `powerj-api` has no test setup):
  - order is kept;
  - immutable (`put` throws);
  - `get` of a missing column gives `null`;
  - `equals` with a `LinkedHashMap`;
  - `sizeColumns`.
- `powerj-cmdlets` `SelectTest`, with `TestContext` and its simulated compiler:
  - property;
  - path, named by its last segment;
  - rename `file=name`;
  - computed `kb=` + block;
  - unnamed blocks → `column1`, `column2`;
  - missing property → `null` and **one** error for several objects;
  - block that throws → same;
  - no column, invalid name, duplicate name → `IllegalArgumentException`;
  - `size` from a `FileEntry` is a size column, a computed column is not;
  - one `Row` emitted per object, in order.
- `powerj-core` `OutputFormatterTest`:
  - `Row`s are shown as a table with the columns in order;
  - numbers right-aligned;
  - size column shown as `14.2 KB`;
  - more than 5 columns still a table;
  - a change of columns starts a new table;
  - a plain `Map` keeps its key/value display.
- `powerj-shell` `CompletionsTest`:
  - `ls | select name size | where { $_.` proposes `name` and `size`;
  - `kb={…}` proposes `kb`.
- `powerj-shell` `BuiltinCmdletsTest`, end to end:
  - `ls | select name size`, header and size display;
  - `ls | select name kb={ $_.size / 1kb } | where { $_.kb >= 0 } | sort kb -d | first`;
  - `ls | select nmae` → empty cells and one error.

## Verification
- `./mvnw verify` (locally `-Djava.release=25`; CI uses JDK 27).
- Manual:
  - `ls | select name size`
  - `ls | select file=name kb={ $_.size / 1kb } modified`
  - `ls -r | select path.fileName size | sort size -d | first 5`
  - `grep TODO -r | select path.fileName line`
  - `env | select name len={ $_.value.length() } | sort len -d | first 3`
  - `ls | select nmae`
  - Tab after `ls | select name size | where { $_.`
  - preview of `ls | select name size` (Alt+V)
  - `help select`

## Open questions
None left: the four questions were answered (see Context).
