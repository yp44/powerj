# Acceptance test — Step 2: native commands

**Goal:** launch system programs from PowerJ, navigate folders, chain commands, use variables and redirections.

## Getting the deliverables

As in the previous steps: **Actions** tab, latest run of the **CI** workflow, artifact `powerj-windows-x64-installer` or `powerj-windows-x64-portable`.

## Scenario

| # | Action | Expected result |
|---|---|---|
| 1 | `git --version` (if git is installed) or `ipconfig` | The program's output is displayed normally. |
| 2 | `git log` in a git repository | git's colors and paging work; `q` to quit paging. |
| 3 | `pwd`, then `cd C:\Windows`, then `pwd` | The prompt becomes `PJ C:\Windows> `; `pwd` displays `C:\Windows`. |
| 4 | `cd ..`, `cd -`, `cd ~`, `cd` | Parent folder; back to the previous folder; user folder (twice). |
| 5 | `cd D:` (if a D: drive exists) | The prompt switches to `D:\`. |
| 6 | `cd "C:\\Program Files"` | Inside quotes, the backslash is doubled (Java escaping); without quotes it is typed normally (`cd C:\Users`). |
| 7 | `cd C:\nulle\part` | `cd : dossier introuvable : C:\nulle\part` in red. |
| 8 | `$l = ipconfig` then `$l[0]`, `$l[-1]` | First and last line of the `ipconfig` output. Accented characters are correct (console code page). |
| 9 | `^cmd /c "exit 3"` then `$exit` | Displays `3`. `$last` displays the details (command, pid, exit code, duration); `$last.duration` the duration alone. |
| 10 | `^cmd /c "exit 1" \|\| "échec"` then `git --version && "ok"` | Displays `échec`, then the git version followed by `ok`. `^cmd /c "exit 1" && "jamais"` displays nothing. |
| 11 | `commandeinexistante; "suite"` | Message `commande inconnue : commandeinexistante` in red, then `suite`. |
| 12 | `git commandeinconnue` | git's error message is displayed; `$?` is `false`. |
| 13 | `git log -n 3 > log.txt`, then `^cmd /c type log.txt` | The file `log.txt` in the current directory contains the output. `>>` appends to the end. |
| 14 | `git commandeinconnue 2> err.txt` | Nothing is displayed; `err.txt` contains the error message. |
| 15 | `$l = git commandeinconnue` | The error message is displayed in red (it is never put into the variable). |
| 16 | `notepad` | Notepad opens and the prompt returns immediately; `$last.pid` gives its pid, `$exit` is empty. |
| 17 | `ping -t localhost`, then Ctrl+C | `ping` stops, `^C` is displayed, the shell stays open; `$?` is `false`. |
| 18 | `which git cd notepad` | `git → natif C:\…\git.exe`, `cd → commande interne`, `notepad → natif C:\Windows\…`. |
| 19 | `$nom = "Yves"` then `"Bonjour $nom"` | Displays `Bonjour Yves`. |
| 20 | Forced encoding: set the Windows environment variable `POWERJ_NATIVE_ENCODING=windows-1252`, relaunch PowerJ, then `$l = ipconfig` and `$l`. | `ipconfig`'s accented characters are now wrong (deliberately unsuitable encoding): proof that the setting is taken into account. Delete the variable to return to the automatic setting. `POWERJ_NATIVE_ENCODING_GIT=UTF-8` would only affect git. |
| 21 | `exit 7`, then in `cmd.exe`: `echo %ERRORLEVEL%` | Displays `7`. |

## Known limitations of step 2

- No `|` pipeline yet (step 4) nor `2>&1` redirection.
- `cmd.exe` built-in commands (`dir`, `echo`, `type`…) are not programs: run them via `^cmd /c dir`.
- `env` (session environment variables) arrives in step 3; until then, the encoding is set via a Windows environment variable.
- `true` and `false` are values (as in Java), not commands.
