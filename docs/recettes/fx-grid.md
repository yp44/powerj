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
| 3 | `ls \| grid` | A window titled `PowerJ grid` opens and says `n objects received`; **the prompt is back at once**. |
| 4 | `ls \| grid ; ls / \| grid` | Two windows, the prompt is back. Closing one leaves the other open. |
| 5 | `ls \| select name \| grid` then a single-file folder: `ls somefile \| grid` | `1 object received` (singular). |
| 6 | `grid` (nothing before it) | Error `grid: grid expects objects from a pipeline (ls \| grid)`; the shell goes on. |
| 7 | `ls \| where { $_.size > 1tb } \| grid` | A window with `0 objects received`, not an error. |
| 8 | `exit` while a window is open | The process ends, the windows disappear. |
| 9 | With the language set to French (`POWERJ_LANG=fr`), `ls \| grid` | The summary in `help grid` and the window text are in French (`n objets reçus`). |
| 10 | Without a display (`env -u DISPLAY powerj`, or over SSH), `ls \| grid` | Error `grid: the graphical toolkit cannot start (no graphical display?): Unable to open DISPLAY`; the shell keeps working. |
| 11 | Same session, `ls \| grid` again | At once (no hang), error `… failed to start earlier in this session and cannot be restarted: restart PowerJ (…)`. |
| 12 | Start `powerj` **without** the module in `~/.powerj/modules`, then `mod-load ~/.powerj/modules/fx` and `ls \| grid` | The module loads without restarting, `grid` works. |
| 13 | `mod-load ~/.powerj/modules/fx` a second time | `fx: module io.powerj.fx already loaded` then `mod-load: fx not loaded`; nothing else changes. |

## Known limitation (decision pending, see the plan, risk R3)

At the first window, JDK 25 prints `WARNING: A restricted method in java.lang.System has been called … javafx.graphics`. It is harmless for now.

## Build checks

- `./mvnw -Pdist verify` is green, the coverage rule of `io.powerj.fx`, `io.powerj.fx.grid` and `io.powerj.fx.runtime` (90 %) holds, and `io.powerj.fx.view` is listed at its real value in `powerj-coverage/target/site/jacoco-aggregate/`.
- `powerj-dist` is unchanged in size by this module (JavaFX is not in the distribution).
