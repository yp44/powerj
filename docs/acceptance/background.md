# Acceptance test — Mini-iteration 24: background execution with `&`

**Goal:** verify that a native command ended by `&` runs in the background and gives the prompt back at once, that Ctrl+C does not reach it, that its end is reported before the prompt, and that `jobs` lists the jobs, which `kill` can stop.

> Messages are shown in the system language; set `language=en` in config.properties (or `POWERJ_LANG=en`) to get the English texts quoted here.

## Getting the deliverables

As in the previous steps: **Actions** tab, latest run of the **CI** workflow, artifact `powerj-windows-x64-installer` or `powerj-windows-x64-portable`. On Linux or macOS, build with `./mvnw verify` and run `powerj-dist/target/…/bin/powerj`.

## Scenario

On Windows, read `charmap` for `xeyes`, `ping -t localhost > NUL` for `xclock`, and `ping -n 60 localhost > NUL` for `sleep 60`. Do not use `notepad`, `calc` or `mspaint` on Windows 11: these are launchers that start the Store application and end at once (see the known limitations).

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

## Experiment (issue #128, Linux): a job in a process group of its own

Today a background command stays in the foreground process group of the terminal and is only *protected* from Ctrl+C by `sh`
(`trap '' INT QUIT`). The experiment starts the job with `posix_spawn` through the FFM API **in a process group of its own**, so
the terminal's Ctrl+C never reaches it. It is **off by default**; it is switched on and off in the running shell, through a
session variable (plan and results: [docs/plans/background-process-group.md](../plans/background-process-group.md)).

Build and run (any Linux with a JDK 25): `./mvnw -B -Pdist -DskipFx -DskipTests package`, then
`powerj-dist/target/jpackage/powerj/bin/powerj`. In the table, `ps` is the native one (`^ps`); `pgid` is the process group of the
process and `tpgid` the foreground group of the terminal.

| # | Action | Expected result |
|---|---|---|
| E1 | `sleep 300 &` then `^ps -o pid,pgid,tpgid,stat,args -C sleep` | **Before** (variable not set): `pgid` equals `tpgid`, the shell's own group. |
| E2 | `env --set POWERJ_BACKGROUND=group`, then `sleep 301 &` and the same `^ps` | **After**: for `sleep 301`, `pgid` equals its `pid` and differs from `tpgid`. |
| E3 | `sleep 100`, then Ctrl+C | Only this `sleep` stops. `jobs` shows both background `sleep` running (in both modes). |
| E4 | `jobs \| kill`, then Enter | `[n] done sleep 30x (exit 143)` for each job, in both modes. |
| E5 | `sleep 302 \| sleep 303 &` (group mode) | One job; both processes have the same `pgid`, the pid of the first. |
| E6 | `printf "b\na\n" \| sort > tri.txt &`, `cat tri.txt` (group mode) | `a`, `b`. |
| E7 | `stty tostop`, then `sh -c "sleep 1; echo hi" &` and wait (group mode) | The job is **stopped** by `SIGTTOU` (`T` in `ps`) and `hi` is not printed, as in bash. Before, it printed. `stty -tostop` and `kill -f` clean up. |
| E8 | `env --unset POWERJ_BACKGROUND`, then `sleep 304 &` and `^ps` | Back to the `sh` path: `pgid` equals `tpgid` again. |

Known differences of the group mode (the same as a shell with job control): a job that reads the terminal (`/dev/tty`) or, with
`stty tostop`, writes to it, is stopped; a stopped job ignores `SIGTERM` (`jobs | kill` leaves it, `kill -f` ends it); `kill -INT <pid>`
sent straight to the job kills it, where the `sh` wrapper ignored it. The group mode only exists on Linux (glibc 2.34 or later):
elsewhere the variable is ignored. The same comparison, on a pseudo-terminal with a real Ctrl+C, is the script
`powerj-core/src/test/prototype/pty-check.py`.

## Known limitations

- Windows 11: `notepad`, `calc`, `mspaint` (and other Store applications) are launchers: `notepad.exe` starts the real application through Windows, then ends at once with code 0. The job is therefore `done` right away, and its pid no longer exists; the window that opened is another process, which is not a child of PowerJ. Stop it with `ps notepad | kill`.

- Windows: a **console** program in the background still receives the console's Ctrl+C (Java cannot start a process in a new process group). Graphical programs are not affected.
- The output of a background command is not captured: it goes to the terminal, where it may mix with the prompt, unless redirected.
- No `fg`, `bg` or `wait`: follow a job with `jobs`, stop it with `jobs | kill`.
