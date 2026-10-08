# Acceptance test — Step 1: line editing and history

**Goal:** verify line editing (JLine), persistent history, ↑/↓, Ctrl+R, Ctrl+C, Ctrl+D and multi-line input.

## Getting the deliverables

As for step 0: the repository's **Actions** tab, latest run of the **CI** workflow, artifacts `powerj-windows-x64-installer` (installer) or `powerj-windows-x64-portable` (zip).

To start from an empty history, delete the file `%USERPROFILE%\.powerj\history` before starting.

## Scenario

| # | Action | Expected result |
|---|---|---|
| 1 | Launch PowerJ. Type `bonjour`, `test un`, `test deux` (Enter after each). | `commande inconnue : …` after each line. |
| 2 | Press ↑ three times. | The lines come back in reverse order: `test deux`, `test un`, `bonjour`. ↓ goes the other way. |
| 3 | Clear the line, type `te` then ↑. | Only lines starting with `te` are offered: `test deux`, then `test un`. |
| 4 | Empty line, Ctrl+R then type `un`. | `(reverse-i-search)` offers `test un`; Enter runs it. Ctrl+G cancels the search. |
| 5 | Type `abc` then Ctrl+C. | The line is cleared, a new prompt is displayed, the shell does not close. |
| 6 | Type `history`. | Numbered list: `1  bonjour`, `2  test un`… (`abc` is not in it). |
| 7 | Type `!!`, then `!1`, then `!te`. | Re-run respectively the last command, entry no. 1, and the last one starting with `te`. |
| 8 | Type `!zzz`. | `historique : aucune commande ne correspond à !zzz`. |
| 9 | Type ` secret` (with a leading space), then `history`. | `secret` does not appear in the history. |
| 10 | Type `ls |` then Enter. | Continuation prompt `>> `; type `where { $_.dir` then Enter: `>> ` again; type `}`: the complete command is executed (`commande inconnue : ls`). |
| 11 | Type `essai C:\` then Enter. | No `>>` continuation prompt: the trailing backslash does not extend the line, the command is executed immediately (`commande inconnue : essai`). This scenario only tests continuation; `cd` arrives in step 2. |
| 12 | Editing: type some text, use ←/→, Home/End, Ctrl+←/→ (word by word), Ctrl+W, Ctrl+K, Ctrl+U. | Usual Emacs behavior. |
| 13 | On a non-empty line, Ctrl+D. | Deletes the character under the cursor. |
| 14 | On an empty line, Ctrl+D. | The shell closes. |
| 15 | Relaunch PowerJ, press ↑. | The history of the previous session is restored. |
| 16 | `history --clear`, then ↑. | Nothing left in the history. |
| 17 | Configuration: create `%USERPROFILE%\.powerj\config.properties` containing `history.size=3`, relaunch, type 5 commands, `history`. | Only the last 3 entries are kept. |

## Known limitations of step 1

- No syntax highlighting or Tab completion yet (step 6).
- Commands other than `exit` and `history` remain "unknown": native commands come in step 2.
- The diagnostic log is in `%USERPROFILE%\.powerj\logs\`.
