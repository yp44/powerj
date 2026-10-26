# Acceptance test — Mini-iteration 17: `select` cmdlet

**Goal:** verify that `select` turns each object into a `Row` made of the chosen columns, displayed as a table. A column is one of:
- a property;
- a property path, named by its last segment;
- a renamed property (`file=name`);
- a computed column (`kb={ … }`).

Also verify that:
- the columns stay usable as properties downstream (`where`, `sort`, `first`, `map`);
- Tab completes them;
- a missing property gives an empty column and a single error.

> Messages are shown in the system language; set `language=en` in config.properties (or `POWERJ_LANG=en`) to get the English texts quoted here.

## Getting the deliverables

As in the previous steps: **Actions** tab, latest run of the **CI** workflow, artifact `powerj-windows-x64-installer` or `powerj-windows-x64-portable`.

## Preparation

In a test folder (e.g. `C:\tmp\s`), create:
- `big.txt` (about 3000 characters);
- `notes.txt` (a few characters);
- `app.log` with a few lines, two of them containing `TODO`;
- a folder `docs`.

Then `cd C:\tmp\s`.

## Scenario

| # | Action | Expected result |
|---|---|---|
| 1 | `ls \| select name size` | Table `name  size`, sizes in readable form (`2.9 KB`). |
| 2 | `ls \| select file=name kb={ $_.size / 1kb } dir` | Table `file  kb  dir`; `kb` is a plain number (`2` for `big.txt`). |
| 3 | `ls \| select name kb={ $_.size / 1kb } \| sort kb -d \| first` | `big.txt` only. |
| 4 | `ls \| select name kb={ $_.size / 1kb } \| where { $_.kb >= 2 }` | `big.txt` only. |
| 5 | `ls -r \| select path.fileName size` | A column named `fileName`. |
| 6 | `ls \| select path.fileName { $_.name.length() }` | Columns `fileName` and `column1`. |
| 7 | `grep TODO app.log \| select path.fileName line` | The two matching lines, with the file name and the line number. |
| 8 | `env \| select name len={ $_.value.length() } \| sort len -d \| first 3` | The three longest variables. |
| 9 | `ls \| select a b c d e f={ 1 } name` | Still a table, even with more than 5 columns; `a` to `e` are empty, plus one error per missing column. |
| 10 | `ls \| select nmae size` | One error `select: column nmae: FileEntry has no property 'nmae' (properties: …)`, then an empty `nmae` column and the sizes. |
| 11 | `ls \| select` | Error `select: column expected: select name size, select kb={ $_.size / 1kb }`. |
| 12 | `ls \| select a name a=size` | Error `select: column a is given twice; rename one of them: other=a`. |
| 13 | `ls \| select my-col=name`, then `ls \| select kb=` | Errors `select: invalid column name: my-col …`, then `select: block expected after kb=: kb={ … }`. |
| 14 | `$r = (ls \| select name size \| first)`, then `$r.size`, `$r.get("name")`, `$r.keySet()` | The value of the column `size`, the name, then `name` and `size` (one per line). |
| 15 | `help members $r` | The columns `name` and `size` first, then the reading methods of `Map` (no `put`, `remove`…). |
| 16 | Type `ls \| select name size \| where { $_.` then Tab | `name` and `size` are proposed. |
| 17 | Type `ls \| select file=name kb={ $_.size / 1kb } \| sort { r -> r.` then Tab | `file` and `kb` are proposed. |
| 18 | Alt+V, then type `ls \| select name size` | The preview shows the table. |
| 19 | `help select` | Help with `<columns>`, the column forms, and the examples. |
| 20 | `POWERJ_LANG=fr`, then `ls \| select` | `select : colonne attendue : …`. |
| 21 | `select content={(cat notes.txt)} another={(cat app.log)}` (first stage of the pipeline) | One row with the columns `content` and `another`. |
| 22 | `select content={(cat notes.txt)} \| map { $_.content }` | The text of `notes.txt`. |

## Known limitations

- No `--expand` in this version (decided with the user): use `map { $_.list }` to emit the elements of a list property.
- A column is shown as a size only when it reads a size component (`@Bytes`) directly (`select size`, `bytes=size`); a computed column (`kb={ $_.size }`) is shown as a plain number.
- Numbers are right-aligned according to the first row of the table: if its value is empty (`null`), the column is left-aligned.
- Completion reads the column names written in the `select` stage of the line; a `Row` stored in a variable (`$r`) is completed from its actual columns.
