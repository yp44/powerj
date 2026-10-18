# Acceptance test — Mini-iteration 19: `group` cmdlet

**Goal:** verify that `group` emits one `Group(key, count, items)` per distinct key, in the order the keys first appear. The key can be a property, a property path, a block, several keys (list key) or the objects themselves. Also check that `-n` keeps only the counts, and that the groups can be sorted, filtered and read downstream.

> Messages are shown in the system language; set `language=en` in config.properties (or `POWERJ_LANG=en`) to get the English texts quoted here.

## Getting the deliverables

As in the previous steps: **Actions** tab, latest run of the **CI** workflow, artifact `powerj-windows-x64-installer` or `powerj-windows-x64-portable`.

## Preparation

In a test folder (e.g. `C:\tmp\g`), create:
- `a.txt`, `b.txt`, `c.txt` and `notes.md` (a few characters each, `c.txt` larger than 10 bytes);
- a folder `docs`;
- `lines.txt` with the lines `error`, `ok`, `error`, `warn`, `error`.

Then `cd C:\tmp\g`.

## Scenario

| # | Action | Expected result |
|---|---|---|
| 1 | `ls \| group ext` | Table `key count`: the folder (empty key) 1, `md` 1, `txt` 3 (in their order of appearance in `ls`). |
| 2 | `ls \| group ext \| sort count -d \| first` | The group `txt`, count 3. |
| 3 | `ls \| group ext dir` | Keys `[, true]`, `[md, false]`, `[txt, false]`, … |
| 4 | `ls \| group ext -n`, then `ls \| group ext -n \| map { $_.items.size() }` | The same counts; then only `0`s. |
| 5 | `(ls \| group ext)[0].items` | The objects of the first group (the folder `docs`). |
| 6 | `ls \| group ext \| where { $_.key == "txt" } \| map { $_.items } \| map { f -> f.name }` | `a.txt`, `b.txt`, `c.txt`. |
| 7 | `cat lines.txt \| group \| where { $_.count > 1 }` | `error`, count 3. |
| 8 | `ls \| group { $_.size > 10 }` | Two groups `false` and `true`. |
| 9 | `ls \| group ext \| select key count size={ $_.items.stream().mapToLong(f -> f.size).sum() }` | Columns `key count size`, the total size of each group. |
| 10 | `List.of(1, 1L, 2) \| group` | Two groups: `1` (count 2) and `2`. |
| 11 | Type `ls \| group ext \| where { $_.` then Tab | `key`, `count` and `items` are proposed. |
| 12 | `ls \| group bad!` | Error `group: invalid property name: bad!`. |
| 13 | `ls \| group { $_.name.substring(5) }` | One error per name shorter than 5 characters (`object skipped`); the others are grouped. |
| 14 | Alt+V, then type `ls \| group ext` | The preview shows the groups. |
| 15 | `help group` | Help with `<keys>`, `-n, --no-items` and the examples. |
| 16 | `POWERJ_LANG=fr`, then `ls \| group bad!` | `group : nom de propriété invalide : bad!`. |

## Known limitations

- `group` keeps every object in memory until the input ends, except with `-n`.
- Text keys are case-sensitive (`JPG` and `jpg` are two groups); use a block (`group { $_.ext.toLowerCase() }`) to ignore case.
- The groups come out in the order their key first appears; use `| sort key` or `| sort count -d` to order them.
