# Plan — cmdlets `to-json`, `from-json`, `to-csv`, `from-csv` (mini-iteration 22, FR-73)

## Context
Phase 9 of the cmdlet roadmap (`docs/plans/cmdlet-roadmap.md`). Today, objects can leave PowerJ only in their displayed form (`> file` writes the table), and PowerJ has no way to read structured data. JSON and CSV are the two formats people exchange with other tools: REST APIs, `package.json`, `kubectl -o json`, Excel, databases.

Constraints:
- **No dependency.** The project has none beyond JLine (and JUnit for tests) and ships a modular jlink runtime. The JSON writer and parser and the CSV reader and writer are written in the project (a few hundred lines). The JDK has no JSON API.
- **Pure cmdlets.** None of the four changes anything on disk, so they all run in preview (FR-66). Writing a file is done by the existing redirection `>`, which writes a `String` as is, in UTF-8 (checked: `"é" > z.txt` gives `C3 A9 0A`).

Decisions taken with the user: see "Open questions" (to answer before coding).

Branch: `claude/cmdlet-json-csv`, from `main` @ 9d9804e.

## Syntax
```text
to-json [-c|--compact] [-l|--lines] [--depth <n>]
from-json [file...] [--encoding <charset>]
to-csv [-d|--delimiter <char>] [--no-header]
from-csv [file...] [-d|--delimiter <char>] [--no-header] [-t|--typed] [--encoding <charset>]
```

| Option | Cmdlets | Description |
|---|---|---|
| `-c`, `--compact` | `to-json` | No indentation and no line breaks. Default: indented by 2 spaces. |
| `-l`, `--lines` | `to-json` | [JSON Lines](https://jsonlines.org): one compact `String` per object, emitted as each object arrives (streaming), instead of one array at the end. |
| `--depth` | `to-json` | Maximum nesting depth, `int` ≥ 1, default `10`. Deeper values are written as their text (`toString()`), with one non-blocking error. This also stops cycles. |
| `file` (positional, multiple) | `from-json`, `from-csv` | Files to read (wildcards on the last element), as `cat` reads them (open question 1). |
| `--encoding` | `from-json`, `from-csv` | Charset of the files, default UTF-8, as for `cat`. |
| `-d`, `--delimiter` | `to-csv`, `from-csv` | Field separator: one character, or `tab`. Default `,`. Use `;` for Excel in French. |
| `--no-header` | `to-csv`, `from-csv` | `to-csv` writes no header line. With `from-csv`, the first line is data and the columns are named `c1`, `c2`… |
| `-t`, `--typed` | `from-csv` | Converts the fields that look like numbers or booleans (open question 3). |

## Input / output
### `to-json`
Input: any object. Output, by default: **one `String`** at the end of the input, holding a JSON array of all the objects received (open question 2). With `-l`, one `String` per object.

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
Input:
- **`String`** objects from the pipeline are JSON **text**: `cat data.json | from-json`, `curl -s … | from-json`, `"{\"a\": 1}" | from-json`. The strings received are joined with line breaks, so `cat` without `-t` works too.
- **files** are given as arguments (open question 1).
- other objects: non-blocking error `not a JSON text: …`, and the object is skipped.

The text may hold **several JSON values** one after the other, as in JSON Lines (`{"a":1}\n{"a":2}`). Each top-level value is emitted as soon as it is complete, so a JSON Lines stream is read in streaming.

Output:
- a top-level array is **unrolled**: its elements are emitted one by one, as PowerShell does (`[1, 2]` gives `1`, then `2`). To keep it as one object, use `collect`;
- an object gives a **`Row`** (open question 4): an immutable map that keeps the order of the keys, is shown as a table, and whose keys are read like properties (`from-json | where { $_.age > 30 }`). A duplicate key keeps the last value;
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
Input: `String` lines from the pipeline (`cat data.csv | from-csv`), or files given as arguments. A quoted field may span several lines. A `String` that contains line breaks (`cat -t`) is split. Output: one **`Row`** per record, emitted as each record completes (streaming).

Rules:
- **Header**: the first record gives the column names. An empty name becomes `c<n>`, and a repeated name gets `_2`, `_3`…
- **Lines**: an empty line is skipped. A UTF-8 byte order mark at the start of the text is removed (files saved by Excel have one).
- **Field count**:
  - a record with fewer fields gives `null` for the missing columns;
  - a record with more fields gives the non-blocking error `line 12: 5 fields for 4 columns (extra fields ignored)`, and the row is still emitted with the known columns.
- **Values**: strings by default. With `-t`:
  - an integer gives `Long`, and a decimal number with `.` gives `Double`;
  - `true`/`false` give `Boolean`, whatever the case;
  - an empty field gives `null`;
  - a number with a leading zero (`007`, a postal code) stays a string.

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
  - `from-json`/`from-csv` without files and without pipeline input: `JSON text expected: from-json <file>... or ... | from-json`.
- **Ctrl+C**: the loops of the parsers and writers check `Thread.interrupted()` every 1,024 values, as `cat` does.
- **Preview**: `preview = true` for all four. They read nothing but their input and, for `from-*`, the files they are given, which `cat` already does in preview.

## Implementation
New package `io.powerj.cmdlets.json` (not exported), plus the cmdlets in `io.powerj.cmdlets`:

| Class | Role |
|---|---|
| `json/JsonWriter` | `write(Object value, StringBuilder out, boolean pretty, int depth)`: the mapping table above, with a switch and pattern matching. Records are read with `RecordComponent.getAccessor()`; an accessor that cannot be called (record of a closed module) gives the `toString()`. |
| `json/JsonParser` | Recursive-descent parser of one value over a `CharSequence`, with line and column for errors (`JsonException`). Produces `Row`, `List`, `Long`/`BigInteger`, `Double`, `String`, `Boolean`, `null`. |
| `json/JsonSplitter` | Linear scanner that cuts a growing text into top-level values: it counts `{[`/`]}` outside strings (a top-level number or literal ends at the next space or line break), and keeps its state (depth, in a string, after `\`) between the strings received. Each value is then parsed once by `JsonParser`, which keeps `from-json` linear for a large document and streaming for JSON Lines. |
| `json/Csv` | `field(Object)` (formatting and quoting), `line(List<?> fields, char delimiter)`. |
| `json/CsvReader` | State machine over characters (in a field, in quotes, quote seen), fed line by line; returns the completed records. Keeps the line number for errors. |
| `Properties` | Ordered columns of an object, `LinkedHashMap<String, Object>`, for `to-csv`: record components, `Row`/`Map` entries, or `value`. |
| `ToJson`, `FromJson`, `ToCsv`, `FromCsv` | The cmdlets, with per-execution state in `Collections.synchronizedMap(new WeakHashMap<>())` keyed by context: the `StringBuilder` and count of `to-json`; the columns of `to-csv`; the splitter or the reader of `from-*`. |

Reused:
- the reading of files by `cat` (`Cat.java`: encoding, errors `not found`, `not readable as UTF-8 text`) is extracted into a small helper, `TextFiles.read(Path, Charset, context)`, so that `from-json`/`from-csv` read files as `cat` does;
- `FileArguments.expand` for the wildcards;
- `Row.of` (phase 3).

Other changes:
- registration in `BuiltinCmdlets`;
- i18n: `to-json.*`, `from-json.*`, `to-csv.*`, `from-csv.*`, `json.*`, `csv.*`, in English and French;
- no API or core change.

Docs:
- FR-73 in the specification (with §11 row 22, and §12.3 struck through);
- `docs/recettes/json-csv.md`;
- README;
- roadmap (FR number 75 → 73).

## Tests
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
  - `-t` (`007`, `1.5`, `TRUE`, empty);
  - delimiter `;` and `tab`.
- `ToJsonTest`, `FromJsonTest`, `ToCsvTest`, `FromCsvTest`, with `TestContext`:
  - streaming (`-l`, `to-csv`);
  - an array at the end;
  - unrolling;
  - files as arguments;
  - errors;
  - round trips (`ls | select name size | to-json | from-json` gives the same names and sizes; the same with CSV and `-t`).
- `BuiltinCmdletsTest`: the four cmdlets are registered.

## Verification
- `./mvnw -Djava.release=25 verify`
- Manually:
```text
ls | select name size | to-json
ls | to-json -l
ls | select name size | to-json > files.json
cat files.json | from-json | where { $_.size > 1kb }
from-json files.json | sort size --desc | first 3
"[{\"a\": 1, \"b\": [1, 2]}, {\"a\": 2}]" | from-json
ls | select name size modified | to-csv -d ";" > files.csv
from-csv files.csv -d ";" -t | measure size --sum
"{\"a\": 1,}" | from-json
```

## Open questions
1. **Files**:
   - `to-json` and `to-csv` take **no file argument**: `> file` already writes their text in UTF-8, and `>>` appends;
   - `from-json` and `from-csv` **accept files as arguments** (`from-csv data.csv`), read as `cat` reads them, besides the text received from the pipeline.
   Proposal: yes for both.
2. **`to-json` with one object**: always an array (`[ {…} ]`), or an object alone when a single object is received, as PowerShell's `ConvertTo-Json` does? Proposal: **always an array**. The output then has the same shape whatever the number of objects, and `-l` gives one object per line.
3. **`from-csv` values**: CSV has no types. Proposal: **strings by default**, as PowerShell's `Import-Csv` does, and `-t/--typed` to convert numbers and booleans (a leading zero stays a string). Otherwise, with types by default, `"007"` would become `7` without the user noticing.
4. **`from-json` objects**: `Row` (table display, properties, the same type as `select`) or a plain `LinkedHashMap`? Proposal: **`Row`**. It is immutable, keeps the order of the keys and is shown as a table.
