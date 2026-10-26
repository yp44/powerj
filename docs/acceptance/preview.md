# Acceptance test — Mini-iteration 14: preview mode (Alt+V)

**Goal:** check that Alt+V switches between the normal mode and the preview mode, shown by the `PJ?` prompt; that in preview mode the first results of the line appear below it while typing, for the stage under the cursor; that anything that could change something (native command, built-in command, cmdlet not declared previewable, Java call outside the preview set, assignment, redirection) stops the preview without running; and that Enter executes the line normally.

> Messages are shown in the system language; set `language=en` in config.properties (or `POWERJ_LANG=en`) to get the English texts quoted here.

## Getting the deliverables

As in the previous steps: **Actions** tab, latest run of the **CI** workflow, artifact `powerj-windows-x64-installer` or `powerj-windows-x64-portable`.

## Preparation

In a test folder (e.g. `C:\tmp\p`), create with your editor `small.txt` (a few characters), `big.txt` (more than 2 KB) and `keep.txt`. Then `cd C:\tmp\p`.

## Scenario

| # | Action | Expected result |
|---|---|---|
| 1 | Press **Alt+V** | The prompt becomes `PJ? C:\tmp\p> ` (`PJ?` in cyan). |
| 2 | Type `"abc".toUpperCase()` without pressing Enter | Below the line: `── String · 1 ───…` then `ABC`. |
| 3 | Press **Enter** | `ABC` is displayed as usual; the preview area is gone; the next prompt is still `PJ? …`. |
| 4 | Type `ls` | The table of the 3 files below the line, banner `── FileEntry · 3`. |
| 5 | Go on typing ` \| where { f -> f.size > 2kb }` | While typing, the table shrinks to `big.txt` (`FileEntry · 1`). Unfinished input (`where { f ->`) keeps the last table under a gray `… ` banner. |
| 6 | Press **Home** (cursor on `ls`) | The preview shows the 3 files again (output of the 1st stage); **End**: `big.txt` again. |
| 7 | Ctrl+C, then type `java.util.stream.Stream.iterate(1, i -> i + 1)` | `── Integer · 10+` and `1` to `10`: the endless stream is stopped at the 10th value. |
| 8 | Ctrl+C, then type `ls \| git status` (with Git installed) | `⏸ preview off: native command git`; the previous results stay. Nothing runs. |
| 9 | Ctrl+C, then type `ls \| where { f -> Files.deleteIfExists(f.path) }` | `⏸ preview off: Java call outside the preview set: Files.deleteIfExists`. **`keep.txt` still exists** (check in the Explorer). Ctrl+C. |
| 10 | Type `cd ..` | `⏸ preview off: built-in command cd`; the directory has not changed. Ctrl+C. |
| 11 | Type `env --set PREVIEW=1`, then Ctrl+C and `env PREVIEW` | `⏸ preview off: env: these options change something`, then (after Enter on `env PREVIEW`) `env: no such variable: PREVIEW`. |
| 12 | Type `$x = 1`, then Ctrl+C; type `"a" > out.txt`, then Ctrl+C | `⏸ preview off: assignment`, then `⏸ preview off: redirection`; `$x` is not set and `out.txt` does not exist. |
| 13 | Type `"abc".nope()` | The error in red below the line: `String has no method nope()`. |
| 14 | Type `c` then **Tab** | The menu offers `cat` and `collect`, not `cd`. Type `Files.` then Tab: `exists(`, `readString(`…, no `delete(`. |
| 15 | Type `ls \| where { f -> f.size > 2kb }` then **Enter** | Executed normally (`big.txt`). |
| 16 | Type `notepad keep.txt` then **Enter** | `⏸ preview off: native command notepad` while typing; Enter opens Notepad (Enter always executes). Close it. |
| 17 | Press **Alt+V** | The prompt is `PJ C:\tmp\p> ` again; typing shows no preview any more. |

## Notes

- In Windows Terminal, Alt+V reaches PowerJ as is. Under macOS (Terminal, iTerm2), enable "Use Option as Meta key".
- A Java computation that never checks for cancellation (e.g. `LongStream.range(0, Long.MAX_VALUE).sum()`) keeps the preview busy: it no longer refreshes until the computation ends. Input and Enter remain available.
