# Acceptance test — Interactive console programs (vim…) on Windows

**Goal:** check that an interactive console program launched from PowerJ (vim, nano, ssh, python…) receives every key, and that Ctrl+C behaves as in the program itself (FR-39b).

Messages are shown in the system language; set `language=en` in config.properties (or `POWERJ_LANG=en`) to get the English texts quoted here.

## Background

Before this fix, the line editor of PowerJ (JLine) kept reading the console in a background thread while the program ran: under Windows, some keys typed in vim were lost (a key had to be pressed twice) and Ctrl+C closed vim. PowerJ now suspends its own reading while the program holds the console.

## Getting the deliverables

**Actions** tab, latest run of the **CI** workflow for the branch `claude/windows-console-input` (PR "Interactive console programs"), artifact `powerj-windows-x64-portable` or `powerj-windows-x64-installer`.

## Scenario

| # | Action | Expected result |
|---|---|---|
| 1 | `vim notes.txt`, then type `ihello world` quickly, `Esc`, `:wq`, Enter | Every character appears once; the file contains `hello world`; back to the PowerJ prompt. |
| 2 | `vim notes.txt`, move with `j`/`k`/arrows, type several commands in a row | No key needs to be pressed twice. |
| 3 | In vim, press Ctrl+C | vim stays open (it shows its own hint about `:qa!`); quit with `:q`. |
| 4 | After leaving vim, type `ls` then Tab, ↑ (history) | The prompt behaves normally (completion, history). |
| 5 | `ping -t localhost` then Ctrl+C | ping stops, back to the prompt; PowerJ is still running. |
| 6 | `python` (if installed), type `1+1`, then `exit()` | `2` is printed; back to the PowerJ prompt. |
| 7 | `nano notes.txt` or `notepad notes.txt` | nano (console) works like vim; Notepad (graphical) opens detached as before. |

## Known limitations

- A program abandoned with a second Ctrl+C (it keeps running in the background) may still read the console; PowerJ takes the keyboard back at the next prompt.
