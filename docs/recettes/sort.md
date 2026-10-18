# Acceptance test — Mini-iteration 15: `sort` cmdlet

**Goal:** verify that `sort` reorders the pipeline objects by the objects themselves or by keys (a property, a property path or a block), in ascending or descending order (`-d`/`--desc`), optionally ignoring case (`-i`/`--ignore-case`). The sort must be stable, `null` must come first in ascending order, and numbers must be compared by value whatever their class.

> Messages are shown in the system language; set `language=en` in config.properties (or `POWERJ_LANG=en`) to get the English texts quoted here.

## Getting the deliverables

As in the previous steps: **Actions** tab, latest run of the **CI** workflow, artifact `powerj-windows-x64-installer` or `powerj-windows-x64-portable`.

## Preparation

In a test folder (e.g. `C:\tmp\s`), create:
- `big.txt` (about 100 characters);
- `notes.txt` (a few characters);
- `a.md` (empty);
- a folder `docs`.

Then `cd C:\tmp\s`.

## Scenario

| # | Action | Expected result |
|---|---|---|
| 1 | `ls \| sort size --desc` | `big.txt` first, then `notes.txt`; `a.md` and `docs` (size 0) last, in their `ls` order. |
| 2 | `ls \| sort size -d`, then `ls \| sort size --des` | Same as row 1 (short form, abbreviated long form). |
| 3 | `ls \| sort dir name` | The files `a.md`, `big.txt`, `notes.txt`, then the folder `docs`. |
| 4 | `ls \| sort { $_.name.length() } -d` | `notes.txt`, `big.txt`, then `a.md` and `docs` (4 characters each, in their `ls` order: stable sort). |
| 5 | `ls \| sort { f -> f.ext }` | `docs` first (empty extension), then `a.md`, then the two `.txt`. |
| 6 | `ls \| sort FileEntry::size` | Increasing sizes. |
| 7 | `ls \| sort path.fileName -d` | Names in reverse alphabetical order. |
| 8 | `List.of("b", "B", "a") \| sort`, then the same with `-i` | `B a b`, then `a b B`. |
| 9 | `List.of(10L, 3, 2.5, 1e3) \| sort` | `2.5`, `3`, `10`, `1000.0` (numbers by value). |
| 10 | `Arrays.asList("b", null, "a") \| sort`, then with `-d` | `null` first, then `a b`; with `-d`: `b a` then `null` last. |
| 11 | `List.of("b", 2, "a", 1) \| sort` | `1 2 a b`, and a single error `sort: cannot compare … with …: these values are sorted by their text`. |
| 12 | `ls \| sort { $_.name.substring(5) }` | Error `sort: … (object skipped: …)` for the names shorter than 5 characters; the other objects are sorted. |
| 13 | `ls \| sort bad!` | Error `sort: invalid property name: bad!`; nothing is displayed. |
| 14 | `ls \| sort 3` | Error `sort: invalid property name: 3`. |
| 15 | `git branch \| sort` (in a Git repository) | The branches sorted alphabetically. |
| 16 | `^sort notes.txt` | The native `sort` program runs. |
| 17 | Alt+V, then type `ls \| sort size -d` | The preview shows the files sorted by decreasing size. |
| 18 | `help sort` | Help with `keys`, `-d, --desc`, `-i, --ignore-case` and the examples. |
| 19 | `POWERJ_LANG=fr`, then `ls \| sort 3` | `sort : nom de propriété invalide : 3`. |

## Known limitations

- `sort` keeps every object in memory and emits nothing before its input ends: `ls -r C:\ | sort size` displays its results only once the whole walk is done.
- `--desc` applies to every key: there is no per-key direction (`sort dir name` with only `name` descending needs a block).
- The JDK sort is not interruptible: a very large sort is stopped by a second Ctrl+C (abandonment).
