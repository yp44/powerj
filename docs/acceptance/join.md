# Acceptance test — Mini-iteration 27: `join` cmdlet

**Goal:** verify that `join` gives one `Row` per pair of a left object and a right object with the same key, with the columns of both; that the keys can differ and be blocks; that `-o` keeps the left objects without a match; and that `join` can start a pipeline with `--left`.

> Messages are shown in the system language; set `language=en` in config.properties (or `POWERJ_LANG=en`) to get the English texts quoted here.

## Getting the deliverables

As in the previous steps: **Actions** tab, latest run of the **CI** workflow, artifact `powerj-windows-x64-installer` or `powerj-windows-x64-portable`.

## Preparation

In a test folder (e.g. `C:\tmp\j`), create:
- `countries.csv` with the lines `statezip,country`, `FR,France`, `IT,Italy`, `ES,Spain`;
- `zip.csv` with the lines `zipcode,city`, `fr,Paris`, `fr,Lyon`, `it,Rome`;
- `owners.csv` with the lines `name,owner`, `countries.csv,alice`.

Then `cd C:\tmp\j`.

## Scenario

| # | Action | Expected result |
|---|---|---|
| 1 | `cat countries.csv \| from-csv \| join -r { (cat zip.csv \| from-csv) } --left-key statezip --right-key { $_.zipcode.toUpperCase() }` | Table `statezip country zipcode city`: `France` twice (`Paris`, `Lyon`), then `Italy` / `Rome`; no `Spain`. |
| 2 | The same line with `-o` | A fourth row `ES Spain` with empty `zipcode` and `city`. |
| 3 | `join -l { (cat countries.csv \| from-csv) } -r { (cat zip.csv \| from-csv) } --left-key statezip --right-key { $_.zipcode.toUpperCase() } \| count` | `3`. |
| 4 | `ls \| join -r { (cat owners.csv \| from-csv) } -k name` | One row for `countries.csv`: the columns of `ls` (`size` in readable form), then `owner` `alice`; a single `name` column. |
| 5 | `ls \| join -r { (cat owners.csv \| from-csv) } --left-key name` | The same row, with a column `right_name`. |
| 6 | Line 1 followed by `\| select country city \| to-csv` | CSV `country,city` with the three pairs. |
| 7 | `List.of(1, 2) \| join -r { List.of(1L, 1L) } --left-key { $_ } \| count` | `2` (`1` and `1L` match; `2` has no match). |
| 8 | `ls \| join -k name` | Error `join: right side expected: …`. |
| 9 | `ls \| join -r { (cat owners.csv \| from-csv) }` | Error `join: key expected: …`. |
| 10 | `ls \| join -l { (ls) } -r { (ls) } -k name` | Error `join: --left is only for the head of a pipeline; …`. |
| 11 | `join -l { (ls) } -r { (cat missing.csv \| from-csv) } -k name` | The error of `cat` (`cat: not found: …missing.csv`); the right side is empty, so nothing is emitted. |
| 12 | Alt+V, then type line 1 | The preview shows the joined rows. |
| 13 | `help join` | Help with `-r, --right`, `-l, --left`, `-k, --key`, `--left-key`, `--right-key`, `-o, --outer` and the examples. |
| 14 | `POWERJ_LANG=fr`, then `ls \| join -k name` | `join : côté droit attendu : …`. |

## Known limitations

- The right side is kept in memory: put the smaller source on the right.
- Only equality joins: a condition between the two objects (`<`, a range) is not supported.
- No right or full outer join; swap the sides and use `-o` for a right outer join.
- The columns of the rows are known only when `join` runs, so Tab does not complete them after `join`.
