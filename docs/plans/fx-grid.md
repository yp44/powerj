# Plan — `powerj-fx`: JavaFX cmdlets as a third-party module, starting with `grid` (mini-iteration 26, FR-77)

## Context
Tables in the terminal are limited to what fits on the screen and cannot be re-sorted or filtered once printed. A window with a sortable, filterable table, fed by a pipeline (`ls -r | grid`), covers the same need as PowerShell's `Out-GridView`.

JavaFX is **not** added to the distribution (`powerj-dist` stays at ~92 MB; JavaFX 25 would add ~8-9 MB to the zip and ~32 MB to the installed folder). It is delivered as a **third-party module** (spec §4.4) that the user copies into `~/.powerj/modules/`. That makes `powerj-fx` the first real consumer of the module mechanism with dependencies, and the place for any later graphical cmdlet (`chart`, `form`…).

Decisions taken with the user:
1. A separate module, `powerj-fx`, loaded like any third-party module. Two small changes to the shell came out of phase 1: `jdk.unsupported` is a root module of the launcher, and a module that uses native code needs the user's consent (see the results of phase 1, R1 and R3).
2. First cmdlet: `grid`, a window with a table of the objects received from the pipe, **sortable and filterable by column**.
3. Every phase can be tested by hand, and unit-test coverage stays high even though JavaFX itself is hard to test (see "Testability and coverage").

Out of scope here (possible later iterations): charts, forms, a tree view, editing cells, a `grid` that reads from a file, packaging as an installer, signed distribution.

Branch: `claude/inspiring-cerf-2lllas`, from `main` @ 845eac0. One PR per phase (or per group of phases) as in `cmdlet-roadmap.md`.

## What the user gets

```text
PJ ~> ls -r --filter *.java | grid                  # opens the window, the prompt comes back at once
PJ ~> ps | grid --title "Processes" --wait          # blocks until the window is closed
PJ ~> ls -r | grid --pass-thru | rm                 # the rows selected in the window go on in the pipeline
```

Installation:

```bash
./mvnw -pl powerj-fx -am package                    # → powerj-fx/target/powerj-fx-<version>-<platform>.zip
unzip powerj-fx-0.1.0-linux-x64.zip -d ~/.powerj/modules/     # creates ~/.powerj/modules/fx/
```
`~/.powerj/modules/fx/` holds `powerj-fx.jar` and the JavaFX jars (`javafx-base`, `javafx-graphics`, `javafx-controls`, with the classifier of the platform): a subfolder is a single `ModuleLayer` (spec §4.4). `mod-load ~/.powerj/modules/fx` loads it without restarting.

## Architecture

```text
powerj-fx/                              (new Maven module, JPMS: io.powerj.fx)
└── io.powerj.fx
    ├── FxProvider                      CmdletProvider (messages en/fr)
    ├── grid.GridCmdlet                 the cmdlet: collects, then asks the runtime to open a window
    ├── grid.GridParams                 options record
    ├── model.*                         PURE JAVA, no JavaFX Toolkit: columns, cells, sorting, filtering
    ├── runtime.FxRuntime               start-up of the toolkit, run-on-FX-thread, window registry
    └── view.*                          JavaFX nodes only (TableView, filter fields, status bar); as thin as possible
```

- `requires io.powerj.api; requires javafx.controls;` (transitively `javafx.graphics`, `javafx.base`).
- **`model` has no dependency on the Toolkit.** It may use `javafx.base` (`ObservableList`, `FilteredList`, `SortedList`, properties), which runs without a display and can be unit tested: the sort/filter pipeline is `rows → FilteredList → SortedList → TableView`, so the `TableView` only displays what the model computed.
- `view` classes do nothing but wiring: column definitions are produced by the model (`ColumnSpec`), cell text by the model (`CellFormatter`), the comparator by the model (`SortSpec`), the predicate by the model (`FilterSpec`).
- The module depends **only on `powerj-api`**. The column logic (record components, `@Display`, `Row`, `Map`, `@Bytes`) is therefore re-implemented in `model` rather than shared with `powerj-core`. If duplication turns out to be annoying, a small public helper can be moved to `powerj-api` in a later phase (compatibility impact: additive).
- **Threads.** A cmdlet runs in a virtual thread; JavaFX needs its own thread. `FxRuntime.startup()` calls `Platform.startup` once per JVM (a second call throws `IllegalStateException`, which is caught), `Platform.setImplicitExit(false)` so that closing the last window does not stop the toolkit (`Platform.exit()` cannot be undone in the same JVM), and `FxRuntime.runAndWait(Runnable)` bridges to `Platform.runLater`. `Main` ends with `System.exit`, so the FX thread never keeps the JVM alive.
- **Build.** JavaFX artifacts have a per-platform classifier. The module declares a property `javafx.platform` set by OS profiles (`linux`, `win`, `mac`, `mac-aarch64`), like the `windows`/`mac` profiles of `powerj-dist`. Packaging copies the runtime dependencies and the module jar into `target/module/fx/`, then zips it.

## Testability and coverage
The root rule (80 % of lines and branches, `powerj-coverage`) must keep passing, and the new code should do clearly better than that.

| Part | How it is tested | Target |
|---|---|---|
| `model.*` (columns, cells, sort, filter, passthru bookkeeping) | plain JUnit + AssertJ, `javafx.base` only, no display | ≥ 95 % lines, ≥ 90 % branches |
| `grid.GridCmdlet`, `FxProvider`, params, i18n | JUnit with `TestContext` (same pattern as `powerj-cmdlets`) and a **fake `GridWindow`** injected through a small interface (`WindowOpener`) so that the cmdlet never touches JavaFX in tests | ≥ 90 % |
| `runtime.FxRuntime` | the logic around the toolkit (single start-up, error mapping for "no display" / "toolkit unavailable") behind an interface; the real call is covered only by the manual checklist | ≥ 70 % |
| `view.*` | `view` is excluded from the threshold **by a narrow, documented JaCoCo exclusion** (`io/powerj/fx/view/**`, in `powerj-coverage`; the report still shows it) and covered by the manual checklist. Monocle does not exist for JavaFX 25 (phase 1), so optional UI tests, if worth it, run the **real toolkit under `xvfb-run`** in a dedicated Maven profile `fx-ui`, enabled in CI on Linux | not counted |

- `powerj-coverage` gets `powerj-fx` as a dependency (same mechanism as the other modules) and a **new rule for the packages `io.powerj.fx.model` and `io.powerj.fx.grid`** at 90 % lines and branches, next to the existing rule for `io.powerj.cmdlets`. The exclusion for `view` is explicit in the same file so that nobody has to guess why it is missing.
- Rule of thumb for reviews: if a `view` class contains an `if`, the `if` belongs in `model`.

## Risks to settle first (phase 1 is a spike for these)
| # | Risk | What to check | Fallback |
|---|---|---|---|
| R1 | JavaFX inside a non-boot `ModuleLayer` loaded by `ModuleLoader` (`defineModulesWithOneLoader`, parent = system loader). Class loading, the `modena.css` resource and native libraries must work. | A window with a `TableView` opens from `mod-load`. | Load JavaFX in the shell's own module path through `powerj-dist` options (then JavaFX is in the dist, +32 MB) or add a "boot-layer module folder" to `ModuleLoader`. |
| R2 | Native libraries are extracted from the jars into `~/.openjfx/cache/<version>` at first use. | Works on a read-only home? Time of the first window. | `-Djavafx.cachedir`, or document. |
| R3 | JDK 24+ warns about restricted methods and `sun.misc.Unsafe` memory access used by JavaFX 24/25, and `--enable-native-access` only reaches modules of the boot layer. | Output of `mod-load` / first `grid` on a real JDK 25 terminal. | Tolerate the warning; else a small `ModuleLoader` change using `ModuleLayer.Controller.enableNativeAccess(Module)` for the modules of `~/.powerj/modules` (decision needed, since it widens what a third-party module may do). |
| R4 | No display (SSH, container, CI): `Platform.startup` fails. | Clear error, shell stays usable. | Message `fx.noDisplay`; nothing else. |
| R5 | Linux needs GTK 3 on the target; macOS needs the AppKit thread. | Test on Linux and Windows first. | macOS declared best effort. |
| R6 | Monocle for JavaFX 25 may not exist (versions lag). | Look for a compatible `openjfx-monocle`. | `view` excluded from the threshold, see above. **Settled in phase 1: no Monocle for 25, Xvfb instead.** |

## Results of phase 1 (risks)
Checked on Linux (Temurin 25, JavaFX 25.0.5, Xvfb, the real `jpackage` launcher), see the checklist `docs/acceptance/fx-grid.md`:

| # | Result |
|---|---|
| R1 | **Works.** JavaFX loaded from `~/.powerj/modules/fx/` (one `ModuleLayer`, by startup or `mod-load`) opens a window; the Modena CSS and the native libraries load. It needed **one change in the distribution**: `javafx.graphics` requires `jdk.unsupported`, which is in the jlink image but was not resolved at run time (nothing required it). `powerj-dist` now passes `--add-modules=jdk.jfr,jdk.management,jdk.unsupported` to the launcher. No size change (the module was already in the image). Not tried yet on Windows. |
| R2 | The natives are extracted from the jars on first use (no problem seen). Not tried on a read-only home. |
| R3 | **Settled: consent of the user (done).** JDK 25 prints, at the first window, `WARNING: A restricted method in java.lang.System has been called … javafx.graphics` and `Restricted methods will be blocked in a future release`; `--enable-native-access` does not reach a module of a non-boot layer. Decision taken with the user: a module declares `Enable-Native-Access: true` in its manifest; `ModuleLoader` asks a `NativeAccessPolicy` before loading it; the shell implements it with `config.properties` (`modules.nativeAccess.<module>=true|false`, one setting per module) and, on a real terminal, asks `[y/N]` and records the answer; without a terminal (`-c`, file, piped input) a module with no recorded answer is **not loaded** with an explicit error; a refusal does not load it either. When accepted, `ModuleLayer.Controller.enableNativeAccess` is called for every module of the layer, and the warning is gone (checked with the launcher in a pseudo-terminal). Specification §4.4, checklist 14-22. |
| R4 | **Handled.** Without a display, `grid` fails with `the graphical toolkit cannot start (no graphical display?): Unable to open DISPLAY` and the shell keeps running. Finding: after a failed start, a second `Platform.startup` **never returns** in the same JVM, so `FxRuntime` remembers the failure and answers `failed to start earlier in this session … restart PowerJ` without retrying. |
| R5 | Linux with GTK 3 works. macOS not tried. |
| R6 | **Monocle is not available for JavaFX 25** (`org.testfx:openjfx-monocle` stops at 21.0.2). Decision: `view` is excluded from the JaCoCo threshold (explicit exclusion in `powerj-coverage`), and the UI tests of the later phases will run the **real toolkit under Xvfb** (`xvfb-run`) in a Maven profile, instead of Monocle. |

Other numbers: the module zip is 7.6 MB (8.4 MB unpacked); the distribution measured with JDK 25 is 103 MB (not 92 MB: that figure came from a JDK 21 runtime).

## Phases
Each phase ends with a state that builds, passes `./mvnw verify`, and **can be tried by hand** with the steps given. The manual checklist grows into `docs/acceptance/fx-grid.md` as the phases land.

### Phase 1 — Skeleton, packaging, an empty window (answers R1-R4) — **done**
**Goal:** prove that the module installs by copying a folder, and that a JavaFX window opens from PowerJ.

- New Maven module `powerj-fx` in the parent `<modules>` and `dependencyManagement`; `module-info.java` (`io.powerj.fx`, `provides CmdletProvider with FxProvider`); OS profiles for `javafx.platform`; JavaFX `25.0.x` pinned in the parent.
- Packaging: `target/module/fx/` + `powerj-fx-<version>-<platform>.zip` (profile `package` or default `package` phase, like `powerj-dist`'s `dist` profile).
- `FxProvider` + `GridCmdlet` that ignores its input and opens a window titled "PowerJ grid" showing "n objects received" (the count, so the pipe is already proven).
- `FxRuntime` (single start-up, `runAndWait`, `setImplicitExit(false)`), error `fx.noDisplay` for a headless session.
- i18n en/fr for the summary and the errors.
- Unit tests: `FxProvider` (declares one cmdlet, bundle present in en and fr), `GridCmdlet` with a fake `WindowOpener` (receives the count, an exception from the opener becomes a clean error), `FxRuntime` error mapping.
- Decide R6 (Monocle) and R3 (native-access warning) and record the decisions in this plan.

**Manual test:**
```bash
./mvnw -pl powerj-fx -am package
unzip powerj-fx/target/powerj-fx-*.zip -d ~/.powerj/modules/
powerj                      # or: mod-load ~/.powerj/modules/fx
PJ ~> mod-list              # fx listed with its version and the cmdlet grid
PJ ~> ls | grid             # a window "n objects received"; the prompt is back at once
PJ ~> ls | grid ; ls | grid # two windows; closing one does not affect the other
PJ ~> exit                  # the process ends, windows included
# over SSH without a display: grid prints "no graphical display available", the shell keeps running
```

### Phase 2 — A read-only table (`model`: columns and cells) — **done**
**Goal:** `grid` displays the received objects as a table.

- `model.ColumnSpec` (name, Java type, value reader, numeric/size/date flags) and `model.ColumnExtractor`:
  - records → every component, those of `@Display(columns=…)` first in its order (changed after phase 8, at the user's request: the grid must show all the properties, not only the compact view of the terminal);
  - `Row` and `Map` → keys, in order;
  - scalars (`String`, numbers, `Path`, dates, enums…) → one column `value`;
  - other objects → public getters of the bean (`getX`/`isX`), as a last resort;
  - objects of **different types** in one stream: union of the columns in order of first appearance, empty cell where an object lacks the column.
- `model.CellFormatter`: `null` → empty, `@Bytes` and `Row.sizeColumns()` → readable size (`14.2 KB`), dates/`Path`/`Duration` → readable text, long text cut to one line with the full text as tooltip, line breaks shown as `↵`.
- `model.GridModel`: the `ObservableList<GridRow>` plus the column list; keeps the **original object** in each `GridRow` (needed for phase 7).
- `view.GridView`: a `TableView<GridRow>` with one `TableColumn` per `ColumnSpec`, right alignment for numbers/sizes, status bar "n rows". Window sized to the screen, closable with Esc.
- Unit tests (`model`): each input type above, `@Display` order, heterogeneous streams, `null` values, `Row` with size columns, getters fallback, a record with a `Path`, an empty input (an empty table with a "no result" message, not an error).

**Manual test:**
```text
PJ ~> ls | grid                       # name, size (readable), modified, dir, ext
PJ ~> ps | grid                       # a record with a pid
PJ ~> ls | select name size | grid    # Row: two columns
PJ ~> java.util.Map.of("a", 1) | grid # one row, one column per key
PJ ~> "a" | grid ; 1..100 | grid      # scalars, one `value` column
PJ ~> (ls ; ps) | grid                # two types: union of the columns
PJ ~> echo | grid                     # empty input: an empty table, not an error
```

**Decisions taken while building it:**
- The values of the properties are read with `context.compile("$_.name")`, as the shell does, and **not** by reflection in the module: the packages of a third-party module are opened to `powerj-core` only, so reading the records of another module (`greet | grid`) would fail from `io.powerj.fx`. The columns come from the metadata of the class (`getRecordComponents`, `getMethods`), which needs no access. Checked with the `greet` example.
- The objects are read **as they arrive** (in `process`), into a `GridRow` that keeps the object and its values; the window opens in `end`. This is the shape phase 5 (streaming) and phase 7 (`--pass-thru`) need.
- A `Map` gives one column per key, like `Row` (a single big map, such as `System.getenv()`, is one wide row; use `env` for the variables).
- The model has no JavaFX in it yet: plain records and lists. Phase 3 wraps the rows in `FilteredList`/`SortedList` of `javafx.base`.
- `io.powerj.fx.model` is under the 90 % rule of `powerj-coverage` (143/143 lines, 132/134 branches).

### Phase 3 — Sorting by column — **done**
**Goal:** click a header to sort; shift-click to add a secondary key.

- `model.SortSpec`: a list of (column, direction); builds one `Comparator<GridRow>`. Natural order by type: numbers numerically, sizes by bytes (not by text), dates chronologically, `Path` by text, strings case-insensitively with the original as tie-break; `null` always last; **stable** (as `sort`, FR-67).
- `view`: header click cycles ascending → descending → none; shift-click adds a key; the sort indicator and the order number of the keys are shown. The `SortedList` comparator is bound to a `SortSpec` that the view updates.
- Unit tests: every column type, ascending/descending, multi-key, stability, `null`s last, mixed types in one column (numbers and text: numbers first), the state cycle of a header click.

**Manual test:** `ls -r | grid`; sort by `size` (10 KB must come after 9 KB), by `modified`, by `name`; shift-click `ext` then `size` for a two-key sort; click again to reverse, once more to clear; sort a column that contains empty cells.

**Decisions taken while building it:**
- The **header click is the native one of `TableView`** (▲, ▼, then none; Shift adds a key, with the arrows and the order marks drawn by JavaFX); the table has a sort policy that turns `getSortOrder()` into a `SortSpec` and hands it to `GridTable`. Nothing is drawn by hand, and the cycle was checked with real clicks (`java.awt.Robot` under Xvfb), not assumed.
- `SortedList` of JavaFX re-sorts **from the current order**, not from the order of arrival: the same sort gave a different result depending on the clicks before. Each `GridRow` therefore has its rank of arrival (`position`, given by `GridModel.Builder`) and `SortSpec.comparator()` ends with it: equal rows always keep the order they arrived in (tested in `GridTableTest.theSameSortGivesTheSameOrderWhateverCameBefore`).
- Values are compared **raw**, not as displayed: a size by its bytes, a date to the second (a column that shows only the minute can look unsorted for a second key). Enums and `Duration` use their natural order (declaration order for an enum).
- Numbers: exact for integers and for `BigInteger`/`BigDecimal`; a `NaN` or an infinity does not fail the sort. A column with several kinds shows numbers, dates, booleans, then texts.
- Coverage: `io.powerj.fx.model` 235/236 lines and 224/234 branches.

### Phase 4 — Filtering by column — **done**
**Goal:** a filter field under each header, plus a global search box.

- `model.FilterSpec`: a global text plus one expression per column, combined with AND. Expression grammar (parsed by `model.FilterParser`, with exact messages for invalid input):
  - `text` → contains, case-insensitive;
  - `=text` → equals; `!text` → does not contain;
  - `/regex/` → regular expression (invalid regex → the field turns red, the table keeps its last good filter);
  - on numeric, size and date columns: `>10kb`, `>=3`, `<2026-01-01`, `a..b` (size suffixes `kb`, `mb`, `gb` as in PowerJ).
- A column of numbers, sizes or dates is **typed**: what is typed is a number or a date, compared with the value of the cell, never with its text (see the decisions of phase 4). Text columns and `/regex/` look at the text as shown.
- `view`: a filter row under the headers, a global search box (Ctrl+F focuses it), a "clear filters" button, status bar "n / total rows" (e.g. `127 / 4 312`).
- The filter is debounced (200 ms) so typing stays smooth on large tables.
- Unit tests: every operator, case-insensitivity, invalid input, a size/date column, combination of several columns, empty filter = everything, the debounce logic (with an injected clock/scheduler).

**Manual test:** `ls -r / | grid` (a large tree); type `java` in `name`; `>1mb` in `size`; `/^[A-Z]/` in `name`; an invalid regex like `/(/` (red field, table unchanged); `2026..` in `modified`; then clear the filters; check the status bar counts and that sort and filters combine.

**Decisions taken while building it:**
- `FilterParser` (pure) turns an expression into a `Parsed` (the condition, whether it is negated, or the reason it is invalid); `FilterEditor` keeps what is typed and the **last good filter of each field**; `FilterSpec` is the predicate; `GridTable` is now `source → FilteredList → SortedList`; `Debouncer` takes a `Scheduler` (the toolkit's `PauseTransition` in the window, a fake clock in the tests).
- A date names a **period** (`2026`, `2026-10`, `2026-10-05`, with a time down to the second), so `>2026-10` means after October and a range includes its last period; dates are typed in the zone of the table.
- An operator alone (`>`, `!`, `..`) filters nothing, so that typing it does not empty the table; `=` alone finds the empty cells.
- **A typed column is not searched as text** (found by the first user test: `228` on a size column also kept 2.2 KB, 12.0 KB, 3.2 KB, whose byte counts contain the digits 228). On a number, a size or a date, a plain `x` or `=x` means *equal to* `x`, `!x` *not equal*, with the operators `> >= < <=` and the ranges; something that is not a number or a date is an error (pink field), not a text search. A size typed with a unit above the byte and at most one decimal (`2.2kb`) is read as the table shows it (2.15 to 2.25 KB); more decimals or bytes are exact. The text conditions (`abc`, `=abc`, `!abc`, `/regex/`) and the search box look at the **text as shown** only, never at the raw value.
- The search box is the same grammar with the text rules, applied to every column; `!x` there means no column has `x`.
- The filter fields live in the **headers** (the graphic of each column), so they follow the columns when the table scrolls sideways; a click in a field does not sort. Esc empties the field that has the focus, then closes the window.
- **A list of comparisons** (`==`, `<`, `>`, `<=`, `>=`) sits before the filter of the columns of numbers and sizes (`FilterOperator`, applied by `FilterEditor.setColumn(column, operator, text)`): it goes with a **plain value**; a text with its own operator (`>5`, `=3`, `!7`, `1..9`, `/regex/`) is read as typed. Dates take the same operator in the model but have no list yet (one line in the view if wanted); `!=` is not in the list (use `!228`).
- Checked with real clicks, keys and pasted text under Xvfb (`java.awt.Robot`).
- Coverage: `io.powerj.fx.model` 477/478 lines, 407/420 branches (152 tests in the module).
- Not done, for a later phase: a `--filter` option to start with a filter typed in; the columns keep their width when the window is narrowed.

### Phase 5 — Streaming and large inputs — **done**
**Goal:** the window opens at the first object and fills while the pipeline runs; huge inputs do not freeze the shell.

- `GridCmdlet.process` hands each object to the window through a bounded buffer (`model.RowBuffer`) that the FX thread drains in batches (`Platform.runLater` at most every ~100 ms), so the FX queue is never flooded.
- Columns grow while new object types arrive. The status bar shows "loading…" until `end`.
- `--max-rows <n>` (default 100 000): beyond it, the cmdlet stops reading (`context.stopInput()`, FR-68b) and the status bar says "truncated at n rows".
- Ctrl+C in the shell while loading stops the feed; the window stays with what was received.
- Unit tests: `RowBuffer` (batching, ordering, overflow, truncation flag), column growth with late object types, `--max-rows` through `stopInput`, cancellation (`context.cancelled()`).

**Manual test:** `ls -r / | grid` (the window appears at once and fills); `1..2000000 | grid --max-rows 5000` (stops at 5 000 with the notice); Ctrl+C during a long `ls -r /`; scroll and sort while it is still loading; `ps | grid` repeated 20 times (no leak of windows or threads, `jobs`/task manager).

**Decisions taken while building it:**
- `LiveTable` (model, no display) is the link: the pipeline thread calls `offer` on a **bounded** queue (20 000 rows; beyond it the pipeline waits, so the window sets the pace); the window thread calls `drain` **every 100 ms** (a `Timeline`, so the toolkit queue is never flooded) for at most 20 000 rows at a time and gives them to `GridTable.append`. Closing the window calls `close()`: `offer` answers `false` and the cmdlet calls `stopInput()`.
- `GridTable` grows: rows first, then the new columns (announced on an observable list so the window can measure them), a column whose kind became clearer (it held only empty cells) is replaced where it is. `FilterEditor.columns(...)` follows, and the search box looks in the new columns too.
- The window opens **at the first object** (already in it), or at the end if nothing arrived.
- **`end` is not called when the pipeline is cancelled (Ctrl+C) or fails**, so the cmdlet cannot say "finished". `LiveTable.state()` watches the thread that fed it: gone without `finish` = `INTERRUPTED`, and the status bar says so instead of `loading…` for ever. (A listener on a `FXCollections.unmodifiableObservableList` is held weakly by the list behind it: `GridTable` keeps one view, found when the window showed no column.)
- `--max-rows` is an `Integer` (the default 100 000 is applied by `GridParams.limit()`), at least 1; one more object than the limit marks the table truncated and stops the input; exactly the limit does not.
- **A `Pause` button** in the status bar (before the number of rows) stops the table from taking the rows that wait (`LiveTable.setPaused`): the bounded queue fills up and the **pipeline waits in its turn**, so a slow command is really suspended, not just hidden; `Resume` takes them again. It is disabled once everything has arrived and been shown; a paused table says `paused` even if the input has ended with rows still waiting; closing the window or Ctrl+C still end the producer. Checked under Xvfb (paused count frozen for several seconds, then resumed).
- A column is sized from the rows there are when it appears: with a slow producer it can be narrow; it is resizable by hand.
- Checked with the real launcher under Xvfb: a producer that lasts 9 s, Ctrl+C, closing the window while it loads (the prompt came back in 0.2 s), 400 000 numbers with `--max-rows 5000` and with the default, sort and filter while loading.
- Coverage: `io.powerj.fx.model` 569/570 lines and 472/486 branches, `io.powerj.fx.grid` 59/59 (191 tests in the module).

### Phase 6 — Usability and polish — **done** (except `--no-focus`)
**Goal:** the window behaves like a tool people leave open.

- Copy: Ctrl+C copies the selected rows as TSV with the header (and Ctrl+Shift+C as JSON); "copy cell" in the context menu.
- Column menu: show/hide columns, "fit to content", reorder by drag (native to `TableView`).
- `--title`, `--columns a,b,c` (initial selection and order), `--wait` (block until closed; Ctrl+C in the shell closes the window), `--no-focus`.
- Window position/size remembered in `~/.powerj/fx-grid.properties` (read through `CmdletContext.configDirectory()`).
- A details pane (F3) showing the selected object's properties as `name : value`, useful for long text.
- i18n for every label (en/fr) with a test that both bundles have the same keys.
- Unit tests: TSV/JSON serialisation (`model.RowExporter`), `--columns` parsing and errors, settings read/write with a temp dir, the key-parity test of the bundles.

**Manual test:** each shortcut and menu on `ls -r | grid`; paste the clipboard into a spreadsheet; `grid --columns name,size`; `ls | grid --wait ; echo back` (`back` appears only after closing the window); `Ctrl+C` while waiting; reopen and check the remembered size; switch the language (`language=fr`) and check the labels.

**Decisions taken while building it:**
- `RowExporter` (model) makes the three texts: **tab-separated** (header + rows, cells as shown but whole), **JSON** (the raw values: numbers stay numbers, a size is its bytes, a date ISO 8601, `NaN` is `null`) and **details** (one object, `name : value`). Ctrl+C / Ctrl+Shift+C on the table, and the right-click menu of a cell (`Copy cell`, `Copy rows`, `Copy rows (JSON)`).
- The columns menu is our own `Columns ▾` button, not the native one of `TableView`: the native one lists the *text* of the columns, which is empty here (the header is a title and a filter field). A header right-click gives `Fit to content` (read on the first 2 000 rows, not the private resize of the skin) and `Hide column`.
- `--columns a,b` (`ColumnChoice`): shown first in this order, the others hidden; names without regard to case; a name that never arrives is reported once, at the end, as a non-blocking error; a list without a name is refused. A late column goes after the asked-for ones.
- `--wait`: `end` waits on `LiveTable.awaitClosed()`; **Ctrl+C closes the window** (the interrupt closes the `LiveTable`, whose listener closes the stage). `LiveTable.onClosed` is also what lets a closed-from-outside window go.
- The place and size of the window are kept in `fx-grid.properties` in the configuration folder (`CmdletContext.configDirectory()`), read before showing, written when it is hidden, and brought back on the screen (`WindowState.fit`). Whatever goes wrong with the file, the defaults apply.
- The **record component of `--wait` is `waitClose`** (`wait` is not a legal name: it clashes with `Object.wait()`), with `@Option(longName = "wait")`.
- **Not done: `--no-focus`.** JavaFX has no public way to show a window without activating it, and the machine used to check has no window manager, so nothing could be verified; `Stage.toBack()` would be a guess. To decide with the user.
- The detail pane is a `SplitPane` item toggled by F3 or the `Details` button.
- Checked with the real launcher under Xvfb and real clicks/keys: Ctrl+C and Ctrl+Shift+C (clipboard read back), both context menus and their actions, the columns menu (a hidden column leaves the copy), `--columns`, the unknown-column message, `--wait` (the prompt waits, Esc and Ctrl+C free it), the saved window (reopened at x=300 y=100 700×400, file created on first close).
- Coverage: `io.powerj.fx.model` and `grid` above 90 %; 240 tests in the module.

### Phase 7 — `--pass-thru`: selection goes back into the pipeline — **done**
**Goal:** `grid` as an interactive filter, like `Out-GridView -PassThru`.

- `--pass-thru` implies `--wait`. The window shows an **OK** and a **Cancel** button and multi-row selection. On OK, the **original objects** (kept by `GridRow`) of the selected rows are emitted in the **displayed order**; on Cancel or window close, nothing is emitted (and `$?` is `true`).
- `--single` allows only one row.
- Selection is by object identity, so sorting and filtering do not lose it; rows hidden by a filter are **not** emitted.
- Unit tests: emission order after sort and filter, cancel emits nothing, `--single`, the original object is the exact instance received (not a copy), the cmdlet is never `previewable` (it opens a window; `previewable` returns `false`).

**Manual test:** `ls | grid --pass-thru | map { $_.name }` (select 2 files, OK → two names); sort first, then select, check the order; Cancel → nothing printed; a filter that hides a selected row; `ps | grid --pass-thru --single | kill` on a throw-away process.

**Decisions taken while building it (with the user):**
- `--pass-thru` shows a **column of check boxes first**, a `Select all` and an `Unselect all` button that act on **the rows found by the filter**, `OK` and `Cancel`; `n selected` is in the status bar. It implies `--wait`.
- **The selection is cumulative and independent of the filter** (`RowSelection`, one tick per row, rows told apart by identity): `Select all`/`Unselect all` only touch the rows the filter shows, and **`OK` gives back every ticked row, the hidden ones too**. This differs from the first plan ("rows hidden by a filter are not emitted"): with those two buttons, a tick that vanishes silently would be a trap. The rows come out in the order of the sort, or of arrival.
- The objects given back are **the objects received**, not copies (`GridRow.source()`); the cmdlet's output type is now `Object` and it is declared `passThrough` so that completion after `grid --pass-thru |` knows the type of `$_`.
- `LiveTable.accept(rows)` keeps the answer and closes; any other close (Cancel, the cross, Esc, Ctrl+C) gives nothing.
- `--single`: ticking a row unticks the other, `Select all` is hidden. `--single` without `--pass-thru` is an error.
- Found by the real window, not by the tests: the check boxes of a `TableView` are **disabled unless the table is editable**, and an editable table **keeps Esc for itself** (cancelling an edit), so Esc is now caught by a **filter** on the scene (and left alone while a drop-down list is open); `OK` is not the default button (Enter in a filter field must not validate).
- Checked with the real launcher under Xvfb: ticking by click, filter + `Select all` / `Unselect all` (`2 selected`, `4 selected`, `1 selected`), `OK` printing the four objects including the hidden one, `--single`, `Cancel` and Esc giving nothing, the error of `--single`.
- Coverage: `io.powerj.fx.model` and `grid` above 90 %; 261 tests in the module.

**Added after phase 7, at the user's request — `--raw`:** `grid --raw` shows the real values without units. `ObjectReader(reader, raw)` turns the `SIZE` columns into plain `NUMBER` columns (bytes) and every `Duration` (value or declared type, so a missing one does not turn the column into text) into a `BigDecimal` number of seconds, shown without exponent (`CellFormatter`). Sort, filter, copy and export then treat them as numbers. Not done: a toggle in the window (the option is chosen when the command runs).

### Phase 8 — Documentation, CI and wrap-up — **done** (except the Windows run)
- `docs/SPECIFICATION.md`: new **FR-77 — Graphical cmdlets (module `fx`), `grid`** (syntax, options, records, acceptance criteria), §4.4 (a module with dependencies, example `fx`), §5.1 module list, §11 row 26, §12.3.
- `docs/acceptance/fx-grid.md`: the manual checklists of phases 1-7 merged, in the format of `grep.md`.
- `README.md`: "Optional graphical module" section (what it is, why it is not in the distribution, how to install it, the cost: ~8-9 MB zip / ~32 MB installed) and the cmdlet in the table.
- `examples/` is untouched: `greet` remains the minimal template, `powerj-fx` is the realistic one (dependencies, i18n, config).
- CI (`.github/workflows/ci.yml`): build `powerj-fx` on `ubuntu` and `windows`, run the unit tests (headless), upload `powerj-fx-*.zip` as an artifact; if Monocle works (R6), run the `fx-ui` profile under `xvfb` on Linux.
- `powerj-coverage`: dependency on `powerj-fx`, the new package rule and the documented `view` exclusion (or Monocle coverage).
- Final check: `./mvnw verify` with the coverage report, and the complete manual checklist on **Windows and Linux**.

**What was done, and what was decided:**
- **Specification:** FR-77 (the module, `grid`, its options, columns, sort, filter, progressive loading, use of the table, `--pass-thru`, acceptance criteria), §4.4 (a module with dependencies), §5.1 (module list), §7 (the zip), §8 (`fx-grid.properties`), §11 (row 26), §12.3 (`grid` done) and §12.4 (`Out-GridView`).
- **README:** the cmdlet in the table, a section "Graphical table (optional module)" with the installation, the cost (about 8 MB to download, 32 MB installed), the main behaviours; the module list, the configuration table, the status and the documentation links.
- **Checklist** `docs/acceptance/fx-grid.md`: rows 1 to 108 in the order of the phases, with a closing section on where it was run and what it does not cover.
- **CI** (`.github/workflows/ci.yml`): on Linux, a smoke test under `xvfb-run` (consent message with no answer, refusal, loading, `help grid`, a window that opens without any message, the message without a display) and the zip kept as an artifact; on Windows, consent, loading and `help grid` only (a window is not opened there: it was not possible to check what the Windows runner allows), and the zip kept as an artifact. The Linux script was run locally as it stands.
- **Coverage:** the rule of `powerj-coverage` (90 % of lines and branches for `io.powerj.fx`, `io.powerj.fx.grid`, `io.powerj.fx.model`, `io.powerj.fx.runtime`) holds, with the `view` package excluded and listed in the report.
- **Not done:** the profile `fx-ui` of UI tests (Monocle does not exist for JavaFX 25, and the real-window checks were done by hand under Xvfb, with a script that is not part of the repository); `--no-focus`; a run on Windows and macOS.

## Order and dependencies
Phase 1 first (it can invalidate the approach through R1-R3). Then 2 → 3 → 4 are sequential (each builds on the model of the previous one). Phase 5 can go before or after 6; phase 7 needs 2-4 only. Phase 8 closes the iteration. A first usable version is already available after **phase 4**.

## Open questions for the user — answered
1. **Cmdlet name:** `grid`, no alias for now.
2. **`--wait`:** not the default: the prompt comes back at once (`--wait`, and `--pass-thru` which implies it, block).
3. **JavaFX:** 25.0.5 (LTS, JDK 23+).
4. **Native access:** the user is asked once, the answer is kept in `config.properties` (`modules.nativeAccess.<module>`); without a terminal the module is not loaded.
5. **Platforms:** Linux checked; Windows used by the user, not run by the session; macOS best effort.
6. **Coverage fallback:** the `view` package is excluded from the threshold (explicit exclusion in `powerj-coverage`); 90 % for `model`, `grid`, `runtime`.
