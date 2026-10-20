# Acceptance test — Mini-iteration 25: perf mode (Alt+P) and Flight Recorder events

**Goal:** check that Alt+P switches perf mode on and off, shown by the `PJ⏱` prompt; that the runtime plan of the whole line appears below it while typing, without running anything; that after Enter a report gives the time of the line, of each statement and of each stage; and that perf mode and preview mode exclude each other.

> Messages are shown in the system language; set `language=en` in config.properties (or `POWERJ_LANG=en`) to get the English texts quoted here.

## Preparation

In a test folder (e.g. `C:\tmp\p`), create a few files (`a.txt`, `b.txt`, `big.txt` of more than 2 KB). Then `cd C:\tmp\p`.

## Scenario

| # | Action | Expected result |
|---|---|---|
| 1 | Press **Alt+P** | The prompt becomes `PJ⏱ C:\tmp\p> ` (`PJ⏱` in yellow). |
| 2 | Type `ls \| where size > 0 \| sort name` without pressing Enter | Below the line: `── Plan · 3 stage(s) ───…`, then `ls`, `where …`, `sort name`, each with `cmdlet`; the first two `own thread`, the last `calling thread`. |
| 3 | Press **Enter** | The files are displayed, then `── Perf · total … ms ──`, `statement 1 · … ms`, one line per stage with `in` / `out` / `first`; the `in` of a stage equals the `out` of the previous one; last line: `stages run at the same time: their durations overlap`. |
| 4 | Type `ls ; cd .. && echo hi \|\| nope` | The plan lists 4 statements; `;`, `&&`, `\|\|` are shown; `cd` is `built-in`, `nope` is `unknown`. Nothing runs before Enter (the directory does not change). Ctrl+C. |
| 5 | Type `$x = ls & ` then `xeyes &` (or any program) | The plan shows `assigned to $x` and `in the background`. Ctrl+C. |
| 6 | Type `ls \| sh -c "touch made.txt"` (on Windows: `ls \| notepad`) | The plan shows `program` and the stage; **`made.txt` does not exist** until Enter. |
| 7 | Type `ls \|` | A gray banner `… <message>`; no crash. |
| 8 | Run `nosuchcommand && ls` | The error is displayed, then the report with `statement 1` marked `✗ failed`; the statement after `&&` is absent. |
| 9 | Run `sh -c "sleep 2"` and press Ctrl+C during the sleep | `^C`, then a report for the statement that had started. |
| 10 | Press **Alt+V**, then **Alt+P**, then **Alt+V** | Prompts `PJ? …`, `PJ⏱ …`, `PJ? …`: one mode at a time. |
| 11 | Press **Alt+V** again, then **Alt+P** twice | `PJ …` then `PJ⏱ …` then `PJ …`: no plan, no report any more. |
| 12 | Without perf mode, run `ls` | No `── Perf` report. |

## Flight Recorder events

| # | Action | Expected result |
|---|---|---|
| 13 | `JAVA_TOOL_OPTIONS="-XX:StartFlightRecording=filename=pj.jfr,dumponexit=true" powerj -c 'ls \| where size > 0 \| count'` | The file `pj.jfr` is written. |
| 14 | `jfr print --events powerj.Command,powerj.Stage pj.jfr` | One `powerj.Command` (`commands = ls \| where \| count`, `statements = 1`) and three `powerj.Stage` (`ls`, `where`, `count`, with `kind = cmdlet`); the arguments (`size > 0`) appear nowhere. |

## Notes

- The durations are wall-clock and overlap (the stages run at the same time); the statement figure is the cost of the pipeline.
- Between two native commands the data travels as bytes and is not counted (`out` stays `0`).
- Under macOS (Terminal, iTerm2), enable "Use Option as Meta key" for Alt+P.
