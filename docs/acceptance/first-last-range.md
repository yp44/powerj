# Acceptance test — Mini-iteration 16: `first`, `last`, `range` cmdlets

**Goal:** verify that `first` and `last` keep the first or last objects of the pipeline, and that `range` keeps the objects between two bounds:
- **positions** without `--property`;
- **regular expressions** searched in a property with `--property`;
- **block conditions**.

Also check that `first` and a closed `range` **stop the previous stage** at once (FR-68b). This stop must be silent: no error, `$?` still true.

> Messages are shown in the system language; set `language=en` in config.properties (or `POWERJ_LANG=en`) to get the English texts quoted here.

## Getting the deliverables

As in the previous steps: **Actions** tab, latest run of the **CI** workflow, artifact `powerj-windows-x64-installer` or `powerj-windows-x64-portable`.

## Preparation

In a test folder (e.g. `C:\tmp\r`), create:
- `a.txt` (empty);
- `big.txt` (about 100 characters);
- `notes.txt` (a few characters);
- a folder `docs`;
- `app.log` with these lines:
  ```text
  start
  BEGIN
  one
  END
  noise
  BEGIN
  two
  END
  stop
  ```

Then `cd C:\tmp\r`.

## Scenario

| # | Action | Expected result |
|---|---|---|
| 1 | `ls \| first` | The first entry only (`docs`). |
| 2 | `ls \| sort size -d \| first 2` | `big.txt`, then `notes.txt`. |
| 3 | `ls -r C:\ \| first 3` | Three entries **at once**, no wait for the walk of the disk. |
| 4 | `ls \| first 0`, then `ls \| first -1` | Nothing; then error `first: expects a number of objects of at least 0, got -1`. |
| 5 | `ls \| sort size \| last 2` | The two largest files, smallest first. |
| 6 | `cat app.log \| range --from 3 --to 5` | `one`, `END`, `noise`. |
| 7 | `cat app.log \| range --from 7`, then `cat app.log \| range --to 2` | `two`, `END`, `stop`; then `start`, `BEGIN`. |
| 8 | `cat app.log \| range --from 5 --to 2` | Nothing. |
| 9 | `cat app.log \| range --from { $_ == "BEGIN" } --to { $_ == "END" }` | `BEGIN`, `one`, `END` (the first block only). |
| 10 | Same as row 9 with `--repeat` | Both blocks: `BEGIN one END BEGIN two END`. |
| 11 | Same as row 10 with `--exclude` | `one`, `two`. |
| 12 | `ls \| sort name \| range --from ^big --to ^docs$ --property name` | `big.txt`, `docs`. |
| 13 | `ls \| sort name \| range --from BIG --property name -i` | `big.txt`, `docs`, `notes.txt`. |
| 14 | `env \| range --from ^PATH$ --to ^PROCESSOR --property name` | From `PATH` up to the first variable starting with `PROCESSOR`. |
| 15 | `ls \| range --from Paul` | Error `range: --from Paul is not a position; add --property <name> to search it in a property, or use a block { … }`. |
| 16 | `ls \| range --from 2 --repeat` | Error `range: --repeat needs a --from that is a regular expression (with --property) or a block { … }`. |
| 17 | `ls \| range --from 1 --include --exclude` | Error `range: --include and --exclude cannot be used together`. |
| 18 | `ls \| range --from "(" --property name` | Error `range: invalid regular expression (: …`. |
| 19 | `git log --oneline \| first 3`, then `$?` | Three commits, then `true`. No error message. |
| 20 | Alt+V, then type `ls -r C:\ \| first 3` | The preview shows three entries without waiting. |
| 21 | `help range` | Help with `--from`, `--to`, `--property`, `-i, --ignore-case`, `--include`, `--exclude`, `--repeat`, and the examples. |
| 22 | `POWERJ_LANG=fr`, then `ls \| range --from Paul` | `range : --from Paul n'est pas une position ; …`. |

## Known limitations

- `last` reads the whole input before emitting anything; `last` on an endless input never answers (Ctrl+C stops it).
- `--to` is tested from the object after the opening one: `--from X --to X` runs from one `X` to the next, not a single object.
- With `--property`, a bound is always a regular expression: characters such as `.`, `(` or `$` must be escaped to be searched literally (`range --from "a\.txt" --property name`).
