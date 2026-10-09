# Acceptance test — Mini-iteration 10: `~` = `HOME` directory

**Goal:** check that `~` designates the directory of the `HOME` variable (else `USERPROFILE`) in every unquoted argument and redirection, and that it follows `HOME` when it changes.

Messages are shown in the system language; set `language=en` in config.properties (or `POWERJ_LANG=en`) to get the English texts quoted here.

## Getting the deliverables

As in the previous steps: **Actions** tab, latest run of the **CI** workflow, artifact `powerj-windows-x64-installer` or `powerj-windows-x64-portable`.

## Which directory is `~`

| Situation | `~` |
|---|---|
| `HOME` is set (Git Bash, WSL users, or `env --set HOME=…`) | the `HOME` directory |
| `HOME` is not set (usual on Windows) | `%USERPROFILE%` (e.g. `C:\Users\yves`) |
| neither | the user directory of Java (`user.home`) |

## Scenario

| # | Action | Expected result |
|---|---|---|
| 1 | `cd ~` then `pwd` | `C:\Users\<you>` (or the `HOME` directory if it is set). |
| 2 | `ls ~\Documents` | Contents of your Documents folder. |
| 3 | `ls ~/Documents` | Same result: `/` and `\` are both accepted after `~`. |
| 4 | `ls ~/Doc` then Tab | Completes to `~/Documents/` (path completion follows the same directory). |
| 5 | `"bonjour" > ~/test-tilde.txt` then `notepad ~\test-tilde.txt` | The file is created in your home directory and opens in Notepad (native programs receive the expanded path). |
| 6 | `env --set HOME=C:\Windows` then `cd ~` then `pwd` | `C:\Windows`: `~` follows `HOME` at once. |
| 7 | `env --unset HOME` then `cd ~` | Back to `%USERPROFILE%`. |
| 8 | `^cmd /c echo ~ "~" a~b` | First word expanded (`C:\Users\<you>`); `"~"` (quotes included, as cmd prints them) and `a~b` are left as is. |
| 9 | `cd` (no argument) | Goes to the same directory as `cd ~`. |
| 10 | `mod-load ~/greet.jar` (with `greet.jar` copied to your home directory) | `greet`: the module is loaded from the home directory. |

## Known limitations

- `~user` (another user's home directory) is not supported: the word is passed as is.
- `~` is not expanded inside quotes, in the middle of a word, after `=` in `--option=~/x` (write `--option ~/x`), or in Java expressions (`Path.of("~")` is a relative path named `~`).
