# Acceptance test — Mini-iteration 25: perf mode (Alt+P) and `perf`

**Goal:** check that Alt+P switches the perf mode, that a gray summary follows each measured line (stages split into active, starved and blocked, objects in and out, display, JVM), that the bottleneck is marked, and that `perf` gives the same measures afterwards as records that a pipeline can process.

> Messages are shown in the system language; set `language=en` in config.properties (or `POWERJ_LANG=en`) to get the English texts quoted here. Under macOS, Alt+P needs the terminal's option "Use Option as Meta key".

## Getting the deliverables

As in the previous steps: **Actions** tab, latest run of the **CI** workflow, artifact `powerj-windows-x64-installer` or `powerj-windows-x64-portable`.

## Preparation

A folder with a few hundred files, for example the PowerJ sources: `cd C:\dev\powerj`.

## Scenario

| # | Action | Expected result |
|---|---|---|
| 1 | `ls -r \| count` | The count only: the mode is off, nothing is measured. |
| 2 | `perf` | `nothing measured yet: press Alt+P, then run a line` (not an error that stops anything). |
| 3 | Alt+P | The prompt becomes `PJ% <dir>> `. |
| 4 | `ls -r \| where { $_.size > 10kb } \| count` | The result, then a gray summary: a `── perf · … s` title, one row per stage (`ls -r`, `where {…}`, `count`) with `in`, `out`, `active`, `starved`, `blocked`, a `display` row, and a `JVM:` line. `where` has an `in` greater than its `out`. |
| 5 | Look at the summary of 4 | The `ls -r` stage is marked `← bottleneck` (or the stage that really has the largest `active`). |
| 6 | `ls > files.txt`, then `cat files.txt \| first 3` | `files.txt` contains no `perf` line: the summary goes to the console only. |
| 7 | `$n = ls \| count` then `$n` | The count; the summary was printed but is not in `$n`. |
| 8 | `git status` (or any native command) | The summary has one row for the program, with `-` for `in` and `out`. |
| 9 | `perf` | One row per stage of the line before (here `git status`); the `perf` line itself is not measured. |
| 10 | `ls -r \| where { $_.size > 10kb } \| count`, then `perf --view blocks` | One row for the block `$_.size > 10kb`: `calls`, `total`, `average`, `max`, and `first` (the first call alone, longer than the average on a cold JVM). |
| 11 | `perf \| sort active -d \| first 1` | The slowest stage of the last line. |
| 12 | `perf \| where { $_.active > 100ms }` | Only the stages above 100 ms (empty if none). |
| 13 | `perf --view phases`, `perf --view jvm`, `perf --view natives` | The phases (`parse`, `resolve`, `execute`, `display`), the JVM row (`gcCount`, `gcTime`, `heapPeak` as a size, `classesLoaded`, `jitTime`), the native processes. |
| 14 | `perf --view lines` | One row per measured line since Alt+P, oldest first, with the `bottleneck` of each. |
| 15 | `perf --all \| group command \| sort count -d` | Stages grouped over all the kept lines. |
| 16 | `perf 2`, then `perf 99` | The stages of the line before the last; then `line 99: only N measured line(s) are kept`. |
| 17 | `perf 0`, then `perf 1 --all` | Two blocking errors (`expects a line number of at least 1`, `a line number and --all cannot be used together`). |
| 18 | `ls -r / \| count`, press Ctrl+C after a second | `^C`, then a summary marked `cancelled` for what ran. |
| 19 | `sleep 2 &` (Unix) | No summary: background commands are not measured. |
| 20 | `$perf` | The last report (`line`, `text`, `total`). |
| 21 | Alt+V | The prompt becomes `PJ? <dir>> `: the preview mode replaced the perf mode. Alt+P goes back to `PJ%`. |
| 22 | Alt+P, then a few lines, then `history` | The history holds your lines, never the summaries. |
| 23 | `powerj -c "ls \| count"` from a shell | No summary: the non-interactive modes are never measured. |
| 24 | `help perf` | The synopsis, the options (`--view`, `--all`) and the examples. |

## Known limitations

- The stages of a pipeline that run in virtual threads started by a cmdlet itself (`ls -p`) are counted in the stage that started them, but their blocks are not timed.
- `heapPeak` is the sum of the peaks of the heap memory pools: an upper bound.
- `perf` emits objects of several types, so Tab completion does not propose the properties of its rows in the next stage.
- Not yet: CPU time and memory per stage, JFR recording (planned in part B of the plan).
