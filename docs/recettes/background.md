# Acceptance test — Mini-iteration 24: background execution with `&`

**Goal:** verify that a native command ended by `&` runs in the background and gives the prompt back at once, that Ctrl+C does not reach it, that its end is reported before the prompt, and that `jobs` lists the jobs, which `kill` can stop.

> Messages are shown in the system language; set `language=en` in config.properties (or `POWERJ_LANG=en`) to get the English texts quoted here.

## Getting the deliverables

As in the previous steps: **Actions** tab, latest run of the **CI** workflow, artifact `powerj-windows-x64-installer` or `powerj-windows-x64-portable`. On Linux or macOS, build with `./mvnw verify` and run `powerj-dist/target/…/bin/powerj`.

## Scenario

On Windows, read `notepad` for `xeyes`, `mspaint` for `xclock`, and `ping -n 60 localhost > NUL` for `sleep 60`.

| # | Action | Expected result |
|---|---|---|
| 1 | `xeyes &` | `[1] <pid>`, the window opens and the prompt comes back at once. |
| 2 | `$last.pid`, `$exit`, `$?` | The pid of step 1, `null`, `true`. |
| 3 | `xclock & jobs` | `[2] <pid>`, then the table `id pid running exitCode command` with `xeyes` and `xclock`, both `running = true`. |
| 4 | Close the `xeyes` window, then press Enter | `[1] done xeyes (exit 0)` before the prompt. |
| 5 | `jobs \| where { $_.command == "xclock" } \| kill`, then Enter | The window closes; `[2] done xclock (exit 143)` (any code on Windows). |
| 6 | `sleep 60 & sleep 100`, then Ctrl+C | `sleep 100` stops (`^C`); `jobs` still shows `sleep 60` running. (Linux/macOS.) |
| 7 | `printf "b\na\n" \| sort > tri.txt &`, then `cat tri.txt` | `a`, `b`: a pipeline of native commands, redirected. |
| 8 | `sh -c "echo hello; echo oops >&2" &` | `hello` and `oops` appear in the terminal (possibly after the prompt). |
| 9 | `make & echo started` | `[n] <pid>`, then `started` at once. |
| 10 | `ls &`, `pwd &`, `"x" &` | `& only applies to native commands (programs), not to cmdlets, built-in commands or expressions`. |
| 11 | `$x = xeyes &` | Syntax error: `no "&" with an assignment: the output of a background command cannot be captured in $x`. |
| 12 | `&`, `a & && b` | `"&" without a command before it`, `"&&" without a command before it`. |
| 13 | `echo a=1&b=2` (Windows: `cmd /c echo a=1^&b=2`) | `a=1&b=2`: a `&` inside a word is kept. |
| 14 | Alt+V, then type `xeyes &` | The preview is off: `⏸ preview off: background command (&)`. |
| 15 | `help jobs` | Its usage and description. |
| 16 | `xeyes &`, then `exit` | PowerJ exits; the `xeyes` window stays open. |
| 17 | `POWERJ_LANG=fr`, `sleep 1 &`, then Enter after a second | `[1] terminé sleep 1 (code 0)`. |

## Known limitations

- Windows: a **console** program in the background still receives the console's Ctrl+C (Java cannot start a process in a new process group). Graphical programs are not affected.
- The output of a background command is not captured: it goes to the terminal, where it may mix with the prompt, unless redirected.
- No `fg`, `bg` or `wait`: follow a job with `jobs`, stop it with `jobs | kill`.
