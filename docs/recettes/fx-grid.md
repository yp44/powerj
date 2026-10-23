# Acceptance test — Mini-iteration 26: `powerj-fx` module and `grid` cmdlet

**Goal:** verify that the optional JavaFX module installs by unpacking a zip into `~/.powerj/modules/`, that its `grid` cmdlet opens a window from a pipeline, and that nothing breaks when there is no display. The checklist grows with each phase of `docs/plans/fx-grid.md`; this version covers **phase 1** (the window only says how many objects it received).

> Messages are shown in the system language; set `language=en` in config.properties (or `POWERJ_LANG=en`) to get the English texts quoted here.
> Needs a graphical session. On a machine without a screen, use `xvfb-run -a powerj` (and `import -window root shot.png` to look at the result).

## Preparation

```bash
./mvnw -Pdist -pl powerj-dist,powerj-fx -am package       # launcher + powerj-fx/target/powerj-fx-<version>-<platform>.zip
unzip powerj-fx/target/powerj-fx-*.zip -d ~/.powerj/modules/      # creates ~/.powerj/modules/fx/
```

`~/.powerj/modules/fx/` must hold `powerj-fx.jar`, `javafx-base-*`, `javafx-graphics-*` and `javafx-controls-*` (jars of the platform), and **no** `powerj-api` jar and no 300-byte `javafx-*.jar` without a platform suffix.

## Scenario

| # | Action | Expected result |
|---|---|---|
| 1 | Start `powerj`, then `mod-list` | A line `io.powerj.fx  0.1.0-SNAPSHOT  [grid]` with the folder as its source. No warning at startup. |
| 2 | `help grid` | `grid — Shows the objects in a window`, the example `ls -r \| grid`, `Module: io.powerj.fx`. |
| 3 | `ls \| grid` | A window titled `PowerJ grid` opens with a table (columns of the terminal table of `ls`); **the prompt is back at once**. See phase 2 below for the details of the table. |
| 4 | `ls \| grid ; ls / \| grid` | Two windows, the prompt is back. Closing one leaves the other open. |
| 5 | `ls somefile \| grid` (a single file) | A table with one row and `1 row` in the status bar (singular). |
| 6 | `grid` (nothing before it) | Error `grid: grid expects objects from a pipeline (ls \| grid)`; the shell goes on. |
| 7 | `ls \| where { $_.size > 1tb } \| grid` | A window with `No result` and `0 rows`, not an error. |
| 8 | `exit` while a window is open | The process ends, the windows disappear. |
| 9 | With the language set to French (`POWERJ_LANG=fr`), `ls \| grid` | The summary in `help grid` and the status bar are in French (`3 lignes`). |
| 10 | Without a display (`env -u DISPLAY powerj`, or over SSH), `ls \| grid` | Error `grid: the graphical toolkit cannot start (no graphical display?): Unable to open DISPLAY`; the shell keeps working. |
| 11 | Same session, `ls \| grid` again | At once (no hang), error `… failed to start earlier in this session and cannot be restarted: restart PowerJ (…)`. |
| 12 | Start `powerj` **without** the module in `~/.powerj/modules`, then `mod-load ~/.powerj/modules/fx` and `ls \| grid` | The module loads without restarting, `grid` works. |
| 13 | `mod-load ~/.powerj/modules/fx` a second time | `fx: module io.powerj.fx already loaded` then `mod-load: fx not loaded`; nothing else changes. |

## Consent to load a module that uses native code

`powerj-fx.jar` has `Enable-Native-Access: true` in its manifest (specification §4.4). Start from a home without any answer: `rm -f ~/.powerj/config.properties` (or a fresh `POWERJ_HOME`), with the module unpacked in `modules/`.

| # | Action | Expected result |
|---|---|---|
| 14 | Start `powerj` in a real terminal | Before the banner: `The module "io.powerj.fx" uses native code (modules: io.powerj.fx, javafx.base, javafx.controls, javafx.graphics). Load it? [y/N]`. |
| 15 | Answer `y` | `answer recorded: modules.nativeAccess.io.powerj.fx=true in …/config.properties`, then the banner. `mod-list` shows `io.powerj.fx`. |
| 16 | `ls \| grid` | The window opens and **no** `WARNING: A restricted method …` is printed. |
| 17 | Quit and start again | No question. |
| 18 | Edit `config.properties`: `modules.nativeAccess.io.powerj.fx=false`, start again | No question; `fx: not loaded, native access refused for module io.powerj.fx (…)`; `grid` is an unknown command. Comments and other settings of the file are unchanged. |
| 19 | Remove the line, start with `POWERJ_LANG=fr`, answer `non` | The question is in French (`[o/N]`); `modules.nativeAccess.io.powerj.fx=false` is recorded; the module is not loaded. |
| 20 | Remove the line, answer with Ctrl+C at the question | Nothing is recorded, the module is not loaded, the shell starts. |
| 21 | Remove the line, then `printf 'mod-list\nexit\n' \| powerj` (input is not a terminal), and `powerj -c mod-list` | No question; `fx: not loaded, module io.powerj.fx uses native code and no answer is recorded (set modules.nativeAccess.io.powerj.fx=true in config.properties to allow it)`; `config.properties` is not created. |
| 22 | Remove the line, move `~/.powerj/modules/fx` away, start `powerj`, move it back, then `mod-load ~/.powerj/modules/fx` and answer `y` | The question is asked by `mod-load` too; the answer is recorded and the module loads without restarting (`ls \| grid` works). |

## Build checks

- `./mvnw -Pdist verify` is green, the coverage rule of `io.powerj.fx`, `io.powerj.fx.grid` and `io.powerj.fx.runtime` (90 %) holds, and `io.powerj.fx.view` is listed at its real value in `powerj-coverage/target/site/jacoco-aggregate/`.
- `powerj-dist` is unchanged in size by this module (JavaFX is not in the distribution).

## Phase 2 — the table

In a folder with a few files and a sub-folder (`cd ~/dev/powerj`, say), with the module installed and accepted.

| # | Action | Expected result |
|---|---|---|
| 23 | `ls \| grid` | Columns `name`, `size`, `modified`, `dir` (the columns of the terminal table). Sizes are readable (`7.6 KB`) and aligned to the right, dates are local (`2026-10-23 13:00`), `dir` is `true`/`false`. The status bar says `n rows`. |
| 24 | `ps \| grid` | Columns `pid`, `name`, `user`, `cpu`, `start`; `pid` aligned to the right; the same values as `ps` in the terminal. |
| 25 | `ls -r --filter *.xml \| select name kb={ $_.size / 1kb } \| grid` | The columns of `select`, in order: `name` (left) and `kb` (right). |
| 26 | `List.of("a", "b") \| grid` | One column `value` with the two strings. |
| 27 | `java.util.Map.of("a", 1) \| grid` | One row, one column per key of the map (`a`), not a key/value table. |
| 28 | `ls \| map { $_.path } \| grid` | One column `value` with the paths. |
| 29 | `greet --name Ada -c 3 \| grid` (module `greet` of the examples, copied into `modules/`) | The records of **another third-party module** are read too: columns `name`, `message`, `at`. |
| 30 | `ls \| where { $_.size > 1tb } \| grid` | `No result` in the middle of the table, `0 rows`. |
| 31 | `cat pom.xml \| grid` then widen the window | The columns of `cat` (path, line, text…); a very long line is cut with `…` and its full text shows in a tooltip when the mouse stays on the cell. |
| 32 | Press Esc in the window | The window closes; the shell and the other windows are not affected. |
| 33 | `ls -r ~ \| grid` (a large tree) | The window opens at the first object and fills while `ls` goes on (see phase 5); scrolling the rows is smooth. |
| 34 | A record whose property raises an error (none among the built-in cmdlets: only a test covers it) | One message `cannot read the property x: …` per column, the cell stays empty, the other cells are shown. |

Not done yet (later phases): copying, column menu, selection given back to the pipeline.

## Phase 3 — sorting

With `ls -r --filter *.java | grid` in a folder with Java files of different sizes.

| # | Action | Expected result |
|---|---|---|
| 35 | Click the header `size` | Arrow ▲ on the header; the smallest first: `99 B`, `120 B`, … and `9.0 KB` **before** `10.0 KB` (sorted by bytes, not by text). |
| 36 | Click `size` again | Arrow ▼; the largest first. |
| 37 | Click `size` a third time | No arrow; the rows are back in the order they arrived. |
| 38 | Click `name` | ▲; names without regard to case (`CellFormatter.java` before `ColumnKind.java`, `apple` before `Banana`). |
| 39 | Click `dir` (all `false`), then **Shift**+click `size` | Two arrows (the second with a mark for the order of the keys): the rows are sorted by `dir`, and by `size` inside each `dir`. |
| 40 | Shift+click `size` again | Only `size` flips to ▼; `dir` keeps ▲. |
| 41 | Click `modified` | Chronological order, **to the second**: two lines that show the same minute can look out of order for a second key; that is not a bug. |
| 42 | `List.of("b", 3, true, "a", 1.5, "C") \| grid`, click `value` | ▲: `1.5`, `3`, `true`, `a`, `b`, `C` (numbers, then booleans, then texts without regard to case); ▼ gives the exact opposite. |
| 43 | `ls -r --filter *.java \| select name ext \| grid` where some cell is empty (e.g. a file without extension), click that column in both directions | The empty cells are **last** in both directions. |
| 44 | Click `size` (▲), then click `dir`, then clear the sort; click `dir` again | Two files with the same size, or the same `dir`, are always in the order they arrived: the result of a sort does not depend on the sorts made before. |
| 45 | Press Esc after sorting | The window closes (checked with a real key press under Xvfb). |
| 46 | `ls -r ~ \| grid`, click `size` | A large table is sorted at once; the window stays responsive. |

## Phase 4 — filtering

With `ls -r --filter *.java | grid`. Under each header there is a `filter` field; above the table, a search box (`Search in every column (Ctrl+F)`) and a `Clear filters` button; the status bar says `shown / total rows`. The table is filtered 200 ms after the last key, so typing stays smooth.

| # | Action | Expected result |
|---|---|---|
| 47 | Click in the `name` field and type `sort` | Only the names that contain `sort`, without regard to case; the status bar says `4 / 50 rows`. Clicking in a field does **not** sort the column. |
| 48 | Type `>5kb` in `size` | Both filters apply: the rows must satisfy both (`1 / 50 rows`). Units: `b kb mb gb tb`, multiples of 1024; `>=`, `<`, `<=`, `=` also work. |
| 49 | In `size`, try `1kb..1mb`, `5kb..`, `..1kb` | A range, with the ends included; one end can be missing. |
| 50 | In `name`, type `=Parser.java`, then `!test`, then `/^Sort.*Test/` | The exact name; the names without `test`; a regular expression (without regard to case). |
| 51 | In `name`, type `/(/` (an invalid regular expression) | The field turns **pink** with the reason in a tooltip; the table keeps the last good filter instead of emptying. Fix the text: the pink goes away. |
| 52 | In `size`, type `>10xb` | Pink, tooltip `not a size (4096, 10kb, 1.5mb, 2gb…): 10xb`. |
| 53 | In `name`, type `>` or `!` alone, or `..` in `size` | Nothing is filtered (an operator alone is ignored), no pink. |
| 54 | In `modified`, type `>=2026-10-23 14:00` | The files modified from 14:00 on. Also try `2026`, `2026-10`, `=2026-10-23`, `2026-10-01..2026-10-23`, `<2026-10-23`: a date stands for the **whole** period it names, so `>2026-10` means after October and `..2026-10-23` includes that whole day. |
| 55 | Files of 228, 2280, 12288 and 3280 bytes; `ls \| select name size path \| grid`; in `size`, type `228` | **Only** the file of 228 bytes. A column of numbers, sizes or dates is typed: `228` means equal to 228, it does not look for the digits in the other values. |
| 55b | In `size`, type `2.2kb`, then `12kb`, then `=2.2kb`, `!2.2kb` | A size typed as it is shown finds what is shown like that (`2.2 KB`: the files from 2.15 to 2.25 KB); `!` is the opposite. With more decimals (`2.25kb`) or in bytes (`228b`) the match is exact. |
| 55c | In `size`, type `KB` or `abc` | Pink field with `not a size (4096, 10kb, 1.5mb, 2gb…): KB`; the table keeps the last good filter. A column of numbers says `not a number: …`, a column of dates `not a date (…)`. |
| 55d | In `size`, type `/^2\.2/` | A regular expression looks at the text **as shown** (`2.2 KB`); the search box also looks at the text as shown, in every column (`kb` finds the sizes, `14520` does not). |
| 56 | In a column with empty cells, type `=` | Only the rows whose cell is empty. |
| 57 | Press Ctrl+F, type `Table` in the search box | The focus goes to the search box; the rows that have `table` in **any** column (`2 / 50 rows`); `!sort` there gives the rows where no column has it, `/regex/` works too. The column filters and the search combine. |
| 58 | Press Esc while a field has text | The field is emptied (and the table refiltered); a second Esc, with the focus elsewhere or the field empty, closes the window. |
| 59 | Click `Clear filters` | Every field and the search box are emptied, `50 rows` again. |
| 60 | Sort a column (▲), then filter | The filtered rows stay in the sort order; removing the filter brings the others back in place. |
| 61 | Filter so that nothing is left | `No result` in the table and `0 / 50 rows`. |
| 62 | `ls -r ~ \| grid`, type in a filter | The table stays responsive on a large input. |

### Phase 4 — the list of comparisons (numbers and sizes)

In front of the filter of a column of numbers or of sizes there is a list: `==`, `<`, `>`, `<=`, `>=` (text and date columns have none). With files of 228, 1228, 2252, 2280, 3280 and 12288 bytes and `ls | select name size path | grid`:

| # | Action | Expected result |
|---|---|---|
| 63 | Choose `>=` in the list of `size`, type `2kb` in its field | The sizes from 2 KB up: 12.0, 2.2, 2.2, 3.2 KB. Changing the list refilters at once (after the same 200 ms). |
| 64 | Choose `<` | The sizes under 2 KB: 1.2 KB, 228 B, 5 B. |
| 65 | With `<` chosen, type `>3kb` | The text has its own operator, so **the text wins**: 12.0 and 3.2 KB. Same for `=…`, `!…`, `a..b` and `/regex/`; the tooltip of the list says so. |
| 66 | With `>=` chosen, empty the field | No filter. |
| 67 | Click `Clear filters` | Fields emptied and every list back to `==`. |
| 68 | The `size` column, narrowed by hand | The header keeps the list and a few characters of field (the column starts at least 150 px wide). |

## Phase 5 — progressive loading and large inputs

A slow producer, `slow.sh`: `i=1; while [ $i -le 30 ]; do echo "line$i"; sleep 0.3; i=$((i+1)); done`.

| # | Action | Expected result |
|---|---|---|
| 69 | `sh slow.sh \| grid` | The window opens **at the first line** (not after 9 s) and the rows appear as they arrive; the status bar says `17 rows · loading…` and, at the end, `30 rows`. While there is no row yet the table says `Loading…`, not `No result`. |
| 70 | Same, then **Ctrl+C** in the terminal after a few seconds | The prompt comes back at once; the window **stays** with what arrived and says `20 rows · input interrupted`. |
| 71 | Same, then close the window (Esc) while it loads | The prompt comes back **at once** (the producer is stopped, it does not run its 9 s to the end). |
| 72 | `sh slow.sh \| grid`, click a header to sort, type a filter, while it loads | The rows that arrive are put in their place and filtered by the same sort and filter (`12 / 30 rows`). |
| 73 | `java.util.stream.IntStream.range(0, 400000).boxed().toList() \| grid --max-rows 5000` | `5000 rows · truncated at 5000 rows (--max-rows)`; the reading stopped at 5000 (it did not read 400000). Numbers are aligned to the right and have the list of comparisons. |
| 74 | The same without `--max-rows` | The default limit: `100000 rows · truncated at 100000 rows (--max-rows)`; the window stays responsive. |
| 75 | `ls --max-rows 3 \| grid` is wrong (the option is for `grid`): `ls \| grid --max-rows 3` | 3 rows, `truncated at 3 rows`. With **exactly** as many objects as the limit, nothing is said (not truncated). |
| 76 | `ls \| grid --max-rows 0` | `grid: --max-rows must be at least 1: 0`; no window. |
| 77 | `help grid` | The option `--max-rows <value>` with its description, in French too (`POWERJ_LANG=fr`). |
| 78 | `ls -r ~ \| grid` on a big tree | The window opens at once and fills; the shell is not blocked; sorting and filtering stay smooth. |
| 79 | Open and close twenty windows (`ls \| grid` then Esc, twenty times) | No slowdown; the processor is idle once the windows are closed (a closed window stops its timer and its pipeline). Not measured by the tests. |

### Phase 5 — the Pause button

At the bottom, before the number of rows, there is a button `Pause` (`Reprendre` once paused, in French).

| # | Action | Expected result |
|---|---|---|
| 80 | `sh slow.sh \| grid`, click `Pause` while it loads | The button says `Resume` and the status `20 rows · paused`; **no row is added any more**, even after the producer has finished (several seconds later the count is the same). |
| 81 | Click `Resume` | The rows that waited come in at once (`30 rows`), or the loading goes on if the input is not over; the button says `Pause` again. |
| 82 | Once everything has arrived | The button is **disabled** (nothing to pause). |
| 83 | On a big input (`IntStream.range(0, 400000).boxed().toList() \| grid --max-rows 300000`), pause | The pipeline **waits** too (the queue of 20 000 rows fills up, then the producer is blocked): pausing really suspends the loading, it does not hide it. Resume goes on. |
| 84 | Pause, then close the window (Esc) | The window closes and the pipeline is stopped (the prompt comes back); closing while paused does not leave the producer waiting. Ctrl+C in the terminal while paused ends the producer; the window says `input interrupted`. |
| 85 | Pause, sort or filter | Sorting and filtering still work on the rows already there. |
