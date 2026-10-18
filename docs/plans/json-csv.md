# Plan — cmdlets `to-json`, `from-json`, `to-csv`, `from-csv` (mini-iteration 22, FR-73)

## Context
Phase 9 of the cmdlet roadmap (`docs/plans/cmdlet-roadmap.md`). Today, objects can leave PowerJ only in their displayed form (`> file` writes the table), and PowerJ has no way to read structured data. JSON and CSV are the two formats people exchange with other tools: REST APIs, `package.json`, `kubectl -o json`, Excel, databases.

Constraints:
- **No dependency.** The project has none beyond JLine (and JUnit for tests) and ships a modular jlink runtime. The JSON writer and parser and the CSV reader and writer are written in the project (a few hundred lines). The JDK has no JSON API.
- **Pure cmdlets.** None of the four changes anything on disk, so they all run in preview (FR-66). Writing a file is done by the existing redirection `>`, which writes a `String` as is, in UTF-8 (checked: `"é" > z.txt` gives `C3 A9 0A`).

Decisions taken with the user:
1. **Redirection, no file name.** `to-json` and `to-csv` take no output file: `> file` writes their text in UTF-8, and `>>` appends.
2. **Pipeline only.** `from-json` and `from-csv` read no file. They receive the JSON or CSV text from the pipeline (`cat data.json | from-json`). `--property <name>` names the property of the objects received that holds the text (`cat data.json -c | from-json --property content`).
3. **Always an array.** `to-json` emits a JSON array, even for a single object.
4. **Strings only.** `from-csv` gives every field as a `String`, with no conversion.
5. **`Row`.** `from-json` gives a `Row` for each JSON object, and `from-csv` one per record.

Branch: `claude/cmdlet-json-csv`, from `main` @ 9d9804e.

## Syntax
```text
to-json [-c|--compact] [-l|--lines] [--depth <n>]
from-json [--property <name>]
to-csv [-d|--delimiter <char>] [--no-header]
from-csv [-d|--delimiter <char>] [--no-header] [--property <name>]
```

| Option | Cmdlets | Description |
|---|---|---|
| `-c`, `--compact` | `to-json` | No indentation and no line breaks. Default: indented by 2 spaces. |
| `-l`, `--lines` | `to-json` | [JSON Lines](https://jsonlines.org): one compact `String` per object, emitted as each object arrives (streaming), instead of one array at the end. |
| `--depth` | `to-json` | Maximum nesting depth, `int` ≥ 1, default `10`. Deeper values are written as their text (`toString()`), with one non-blocking error. This also stops cycles. |
| `--property` | `from-json`, `from-csv` | Property of the objects received that holds the text (a `String` or any `CharSequence`). Without it, the objects received must be `String`s. |
| `-d`, `--delimiter` | `to-csv`, `from-csv` | Field separator: one character, or `tab`. Default `,`. Use `;` for Excel in French. |
| `--no-header` | `to-csv`, `from-csv` | `to-csv` writes no header line. With `from-csv`, the first line is data and the columns are named `c1`, `c2`… |

## Input / output
### `to-json`
Input: any object. Output, by default: **one `String`** at the end of the input, holding a JSON array of all the objects received, even when there is only one (decision 3): `[ {…} ]`. With `-l`, one `String` per object (JSON Lines, a different format that has no array).

Mapping from Java to JSON:

| Java | JSON |
|---|---|
| `null` | `null` |
| `Boolean` | `true` / `false` |
| `Integer`, `Long`, `Short`, `Byte`, `BigInteger`, `BigDecimal` | number, as is |
| `Double`, `Float` | number; `NaN` and infinities become `null` (JSON has none) |
| `String`, `Character`, enum (`name()`), `Path`, `File`, `URI`, `UUID`, `java.time.*` (ISO-8601, for example `2026-10-18T19:23:00Z`), `FileTime` | string |
| record | object of its components, in declaration order. A `@Bytes` component stays a number of bytes. All components are written, not only the `@Display` columns: `select` chooses them (`ls \| select name size \| to-json`). |
| `Row`, `Map` | object; the keys are written with `String.valueOf` |
| `Collection`, `Iterable`, array, `Stream`, `Iterator` | array |
| `Optional` | its value, or `null` |
| any other object | string of its `toString()`. Getters are not called: they could be slow or have side effects. |

Strings are escaped as in RFC 8259: `"`, `\`, control characters as `\n`, `\t` or `\u00XX`. Other characters are written as is, since the text is UTF-8.

### `from-json`
Input, from the pipeline only (decision 2):
- **`String`** objects are JSON **text**: `cat data.json | from-json`, `curl -s … | from-json`, `"{\"a\": 1}" | from-json`. The strings received are joined with line breaks, so `cat` without `-t` works too. A `String` is never a file name: `"data.json" | from-json` is invalid JSON.
- with **`--property <name>`**, that property of each object holds the text: `cat data.json -c | from-json --property content`. A property that is `null` skips the object without error; one that is not a `CharSequence` gives the non-blocking error `property content is not a text: …`.
- other objects, without `--property`: non-blocking error `not a JSON text: … (use --property to name the property that holds it)`, and the object is skipped.

The text may hold **several JSON values** one after the other, as in JSON Lines (`{"a":1}\n{"a":2}`). Each top-level value is emitted as soon as it is complete, so a JSON Lines stream is read in streaming.

Output:
- a top-level array is **unrolled**: its elements are emitted one by one, as PowerShell does (`[1, 2]` gives `1`, then `2`). To keep it as one object, use `collect`;
- an object gives a **`Row`** (decision 5): an immutable map that keeps the order of the keys, is shown as a table, and whose keys are read like properties (`from-json | where { $_.age > 30 }`). A duplicate key keeps the last value;
- an array inside a value gives an unmodifiable `List`;
- integers give `Long` (`BigInteger` beyond its range), decimals and exponents give `Double`, and `true`/`false`/`null` give `Boolean`/`null`.

### `to-csv`
Input: any objects. Output: **one `String` per line**, emitted as the objects arrive (streaming): the header first, then one line per object. So `ls | select name size | to-csv > files.csv` writes a CSV file.

Columns and fields:
- **Columns** are those of the **first object**: record components, `Row` or `Map` keys, or a single column `value` for a scalar (`String`, number…). Later objects give these columns; a missing column is an empty field and an extra one is ignored. This is the behaviour of PowerShell's `ConvertTo-Csv`.
- **Fields**:
  - `null` gives an empty field;
  - numbers are written with `.` whatever the locale, without exponent (`BigDecimal.toPlainString`);
  - dates are written in ISO-8601, and other scalars as their JSON string without quotes;
  - a nested value (collection, map, record) is written as compact JSON.
- **Quoting** (RFC 4180): a field is quoted when it contains the delimiter, a `"`, a CR or an LF, or starts or ends with a space; a `"` is doubled.

### `from-csv`
Input: `String` lines from the pipeline (`cat data.csv | from-csv`), or the property named by `--property`, as for `from-json`. A quoted field may span several lines. A `String` that contains line breaks (`cat -t`) is split. Output: one **`Row`** per record, emitted as each record completes (streaming).

Rules:
- **Header**: the first record gives the column names. An empty name becomes `c<n>`, and a repeated name gets `_2`, `_3`…
- **Lines**: an empty line is skipped. A UTF-8 byte order mark at the start of the text is removed (files saved by Excel have one).
- **Field count**:
  - a record with fewer fields gives `null` for the missing columns;
  - a record with more fields gives the non-blocking error `line 12: 5 fields for 4 columns (extra fields ignored)`, and the row is still emitted with the known columns.
- **Values**: always `String` (decision 4), as PowerShell's `Import-Csv` does. An empty field is `""`; a missing column (record too short) is `null`. To compare a number, convert it: `where { Long.parseLong($_.size) > 1000 }`.

## Behaviour
- **Accumulation**:
  - `to-json` without `-l` keeps the objects until the end of the input. It writes them as they come into a `StringBuilder` (no list of objects), then emits the text;
  - `to-csv`, `to-json -l`, `from-csv` and `from-json` stream.
- **Errors**:
  - invalid JSON gives a non-blocking error with its position, `from-json: invalid JSON at line 3, column 7: expected ':' after a key`. The values already emitted stay, and reading resumes at the next top-level value;
  - text that ends inside a value gives `unexpected end of the JSON text`;
  - an unclosed quote at the end of a CSV text gives `line 8: quoted field not closed`.
- **Blocking errors** (bad usage, raised as `IllegalArgumentException`):
  - `--depth 0`;
  - a `--delimiter` of more than one character (other than `tab`), or one that is `"`, CR or LF;
  - `from-json`/`from-csv` without pipeline input: `JSON text expected: … | from-json` (and `CSV text expected: … | from-csv`).
- **Ctrl+C**: the loops of the parsers and writers check `Thread.interrupted()` every 1,024 values, as `cat` does.
- **Preview**: `preview = true` for all four. They read nothing but their input.

## Implementation
All the classes are package-private in `io.powerj.cmdlets`, next to the cmdlets, so they share `Messages` (no new exported package):

| Class | Role |
|---|---|
| `JsonWriter` | `write(Object value, StringBuilder out, int indent)`: the mapping table above, with a switch and pattern matching. `isContainer` tells the values written as objects or arrays (a `Path`, though `Iterable`, is a scalar). `truncated()` tells that `--depth` was reached. |
| `Properties` | Named values of an object: record components (read with `RecordComponent.getAccessor()`), map entries, or a single column `value`. Used by `JsonWriter` for records and by `to-csv` for its columns. |
| `JsonParser` | Recursive-descent parser of one value over a `CharSequence`, with line and column for errors (`JsonException`), at most 1,000 levels of nesting. Produces `Row`, `List`, `Long`/`BigInteger`, `Double`, `String`, `Boolean`, `null`. |
| `JsonSplitter` | Linear scanner that cuts a growing text into top-level values: it counts `{[`/`]}` outside strings (a top-level number or literal ends at the next space or line break), and keeps its state (depth, in a string, after `\`) between the strings received. Each value is then parsed once by `JsonParser`, which keeps `from-json` linear for a large document and streaming for JSON Lines. Only the value being read stays in its buffer. |
| `CsvWriter` | `line(List<?> values)`: formatting and quoting of the fields; `delimiter(String)` checks `--delimiter`. |
| `CsvReader` | State machine over characters (in quotes, quote seen), fed piece by piece; returns the completed records with their line. |
| `PipelineText` | Text of an object received by `from-*`: the `String` itself, or its `--property` (compiled with `Keys.block`). |
| `ToJson`, `FromJson`, `ToCsv`, `FromCsv` | The cmdlets, with per-execution state in `Collections.synchronizedMap(new WeakHashMap<>())` keyed by context: the `StringBuilder` and count of `to-json`; the columns of `to-csv`; the splitter or the reader of `from-*`. |

Reused:
- `Keys` / `Where.PROPERTY` for `--property` (phase 1);
- `Row.of` (phase 3).

Other changes:
- registration in `BuiltinCmdlets`, category `Data`;
- i18n: `to-json.*`, `from-json.*`, `to-csv.*`, `from-csv.*`, `json.*`, `csv.*`, in English and French;
- no API or core change.

Docs:
- FR-73 in the specification (with §11 row 22, and §12.3 struck through);
- `docs/recettes/json-csv.md`;
- README;
- roadmap (FR number 75 → 73).

## Tests
Implemented as `JsonTest` (writer, parser, splitter), `CsvTest` (writer, reader), `JsonCsvCmdletsTest` (the four cmdlets) and `BuiltinCmdletsTest.jsonAndCsv` (in the shell, with `>`):
- `JsonWriterTest`:
  - every row of the mapping table;
  - escapes (`"`, `\`, `\n`, `\u0001`, `é` as is);
  - indentation and `--compact`;
  - `NaN` gives `null`;
  - depth and a cycle (a list that contains itself) give text and one error;
  - a record with `@Bytes`.
- `JsonParserTest`:
  - literals, numbers (`-0`, `1e3`, `1.5E-2`, a long beyond its range gives `BigInteger`);
  - strings with `\uXXXX` and surrogate pairs;
  - key order in the `Row`, a duplicate key;
  - errors with line and column (missing `:`, trailing comma, `01`, unclosed string, garbage after a value).
- `JsonSplitterTest`:
  - JSON Lines;
  - one document cut over many strings;
  - braces inside strings and escaped quotes;
  - recovery after an invalid value.
- `CsvTest`:
  - quoting (delimiter, quotes, line breaks, edge spaces);
  - numbers without exponent;
  - `null`;
  - nested values as JSON.
- `CsvReaderTest`:
  - RFC 4180 cases (quoted field with `""`, CRLF, a line break inside quotes across two strings);
  - empty lines;
  - BOM;
  - fewer or more fields;
  - `--no-header`, duplicate and empty names;
  - values stay strings (`007`, `1.5`, `TRUE`), an empty field is `""`;
  - delimiter `;` and `tab`.
- `ToJsonTest`, `FromJsonTest`, `ToCsvTest`, `FromCsvTest`, with `TestContext`:
  - streaming (`-l`, `to-csv`);
  - an array at the end;
  - unrolling;
  - `--property` (a `CatContent`, a `null` property, a property that is not a text) and an object that is not a `String`;
  - errors;
  - round trips (`ls | select name size | to-json | from-json` gives the same names and sizes; with CSV, the same names and the sizes as strings).
- `BuiltinCmdletsTest`: the four cmdlets are registered.

## Verification
- `./mvnw -Djava.release=25 verify`
- Manually:
```text
ls | select name size | to-json
ls | to-json -l
ls | select name size | to-json > files.json
cat files.json | from-json | where { $_.size > 1kb }
cat files.json -c | from-json --property content | sort size --desc | first 3
"[{\"a\": 1, \"b\": [1, 2]}, {\"a\": 2}]" | from-json
ls | select name size modified | to-csv -d ";" > files.csv
cat files.csv | from-csv -d ";" | measure { Long.parseLong($_.size) }
"{\"a\": 1,}" | from-json
```

## Open questions
All answered (see the decisions in Context).
