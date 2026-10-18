# Acceptance test — Mini-iteration 22: `to-json`, `from-json`, `to-csv`, `from-csv`

**Goal:** verify that objects can be exported as JSON or CSV text and written to a file with `>`, and that JSON or CSV text received from the pipeline is read back into `Row`s that can be filtered, sorted and displayed.

> Messages are shown in the system language; set `language=en` in config.properties (or `POWERJ_LANG=en`) to get the English texts quoted here.

## Getting the deliverables

As in the previous steps: **Actions** tab, latest run of the **CI** workflow, artifact `powerj-windows-x64-installer` or `powerj-windows-x64-portable`.

## Preparation

In a test folder (e.g. `C:\tmp\j`), create:
- `a.txt` (a few characters) and `big.txt` (more than 1 KB);
- a folder `sub`.

Then `cd C:\tmp\j`.

## Scenario

| # | Action | Expected result |
|---|---|---|
| 1 | `ls \| select name size \| to-json` | An indented JSON array, one object `{"name": …, "size": …}` per entry; the sizes are numbers of bytes. |
| 2 | `ls a.txt \| select name size \| to-json` | Still an array: `[`, one object, `]`. |
| 3 | `ls \| to-json -c`, then `ls \| to-json -l` | One line holding the whole array; then one compact object per line (JSON Lines), with every component of `FileEntry` (`modified` in ISO-8601, `path`, `dir`, `ext`). |
| 4 | `ls \| select name size \| to-json > files.json`, then `cat files.json` | The file holds the array of row 1 (UTF-8). |
| 5 | `cat files.json \| from-json` | A table `name size`, one row per entry. |
| 6 | `cat files.json \| from-json \| where { $_.size > 1kb }` | Only `big.txt`. |
| 7 | `cat files.json -c \| from-json --property content \| sort size --desc \| first 2` | `big.txt`, then `a.txt`. |
| 8 | `"[{\"a\": 1, \"b\": [1, 2]}, {\"a\": 2}]" \| from-json` | Two rows: `a 1, b [1, 2]`, then `a 2` (the top-level array is unrolled). |
| 9 | `ls \| to-json -l > files.jsonl`, then `cat files.jsonl \| from-json \| count` | The number of entries of the folder. |
| 10 | `"{\"a\": 1,}" \| from-json` | `from-json: invalid JSON at line 1, column 9: expected a key in quotes`. |
| 11 | `"files.json" \| from-json` | `from-json: invalid JSON at line 1, column 1: unexpected text: files.json` (a `String` is text, never a file name). |
| 12 | `ls \| from-json` | One error per entry: `from-json: not a JSON text: … (use --property to name the property that holds it)`. |
| 13 | `ls \| select name size modified \| to-csv -d ";" > files.csv`, then open `files.csv` in Excel | Columns `name`, `size`, `modified`, one line per entry. |
| 14 | `cat files.csv \| from-csv -d ";"` | A table `name size modified`; the values are strings. |
| 15 | `cat files.csv \| from-csv -d ";" \| measure { Long.parseLong($_.size) }` | Count, sum, average, min and max of the sizes. |
| 16 | Save from Excel a CSV with a cell holding a `;`, a `"` and a line break (Alt+Enter), then `cat file.csv \| from-csv -d ";"` | The cell is read whole, with its line break; the byte order mark written by Excel is not part of the first column name. |
| 17 | `cat files.csv \| from-csv -d ";" --no-header` | Columns `c1`, `c2`, `c3`; the header line is the first row. |
| 18 | `ls \| to-json --depth 0`, then `ls \| to-csv -d ";;"` | `to-json: --depth expects a depth of at least 1, got 0`; then `to-csv: --delimiter expects one character or tab, got ;;`. |
| 19 | `from-json` | `from-json: JSON text expected: ... \| from-json (for example cat data.json \| from-json)`. |
| 20 | Alt+V, then type `ls \| select name size \| to-json -c` | The preview shows the JSON line. |
| 21 | `help to-json`, `help from-csv` | Their help, with the options and examples. |
| 22 | `POWERJ_LANG=fr`, then `42 \| from-json` | `from-json : pas un texte JSON : 42 (utiliser --property …)`. |

## Known limitations

- `to-json` without `-l` keeps its text in memory until the input ends; use `-l` for a long stream.
- Other objects than records, maps and collections are written as their text (`toString()`): their getters are not called.
- `from-csv` gives strings only: convert them to compare numbers (`Long.parseLong($_.size)`).
- Numbers are written to CSV with a `.`: Excel in French may read `1.5` as text.
- A top-level JSON array is read whole before its elements are emitted.
