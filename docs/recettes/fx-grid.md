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
