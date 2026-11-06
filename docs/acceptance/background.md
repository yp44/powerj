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

## A job leads a process group of its own (issue #128)

A background command is started with `posix_spawn` (Linux, macOS) or `CreateProcessW` + `CREATE_NEW_PROCESS_GROUP` (Windows)
through the FFM API, **in a process group of its own**: the terminal's Ctrl+C goes to the foreground group, so it never reaches
the job. There is no `sh` in between. Measures: [docs/plans/background-process-group.md](../plans/background-process-group.md).

Build and run (any system with a JDK 25): `./mvnw -B -Pdist -DskipFx -DskipTests package`, then
`powerj-dist/target/jpackage/powerj/bin/powerj` (Windows: `powerj-dist\target\jpackage\powerj\powerj.exe`). In the Unix rows,
`ps` is the native one (`^ps`), `pgid` is the process group of the process and `tpgid` the foreground group of the terminal.

| # | Action | Expected result |
|---|---|---|
| G1 | Linux: `sleep 300 &` then `^ps -o pid,pgid,tpgid,stat,args -C sleep` | `pgid` equals the `pid` of the `sleep` and differs from `tpgid`; the state has no `+` (not in the foreground). |
| G2 | `sleep 100`, then Ctrl+C | Only this `sleep` stops. `jobs` shows the first one still running. |
| G3 | `sleep 302 \| sleep 303 &` then the same `^ps` | One job; both processes have the same `pgid`, the pid of the first. `kill` stops the last process of the job only. |
| G4 | `printf "b\na\n" \| ^sort > tri.txt &`, then `cat tri.txt` | `a`, `b` (`^sort`: in the real shell `sort` is the cmdlet, which `&` refuses). |
| G5 | `jobs`, then `jobs \| kill`, then Enter | `[n] done sleep 30x (exit 143)` for each job. Show `jobs` first: in the packaged shell `jobs \| kill` alone currently fails with `kill: not a process: Job[…]` until `jobs` has been displayed once (a defect of `main`, not of this change; `kill $last.pid` works). |
| G6 | Unix: `stty tostop`, then `sh -c "sleep 1; echo hi" &` and wait | The job is **stopped** by `SIGTTOU` (`T` in `ps`) and `hi` is not printed, as in bash. `stty -tostop`, then `kill <pid>`: the stopped job ends (`kill` sends `SIGCONT` after `SIGTERM`, which a stopped process would keep pending). |
| G7 | Windows: `ping -t localhost &`, then `ping -n 100 localhost`, and Ctrl+C | Only the foreground `ping` stops. `jobs` still shows `ping -t localhost` running. Before #128 both stopped. |
| G8 | Windows: `ping -t localhost > NUL 2> NUL &` | Same as G7 (the JDK already kept a job whose three streams are redirected off the console; the group makes the other cases equal). |
| G9 | Windows: a batch file in the background (`hello.cmd x &`) | It runs and its output appears, but it is not protected: Ctrl+C reaches it (known limitation). |
| G10 | A Unix system without a usable `posix_spawn` (glibc older than 2.34, Alpine): `sleep 5 &` | Refused: `cannot start a background command on this system (no usable posix_spawn); set POWERJ_BACKGROUND_SH_FALLBACK=true to start it through sh`. With `env --set POWERJ_BACKGROUND_SH_FALLBACK=true` it starts through `sh`, which ignores `SIGINT` and `SIGQUIT` (no process group of its own). |

The same comparison on a pseudo-terminal, with a real Ctrl+C, is the script `powerj-core/src/test/tools/pty-check.py`.

## Known limitations

- Windows 11: `notepad`, `calc`, `mspaint` (and other Store applications) are launchers: `notepad.exe` starts the real application through Windows, then ends at once with code 0. The job is therefore `done` right away, and its pid no longer exists; the window that opened is another process, which is not a child of PowerJ. Stop it with `ps notepad | kill`.

- Windows: a **batch file** (`.bat`, `.cmd`) in the background still receives the console's Ctrl+C: it is started by `ProcessBuilder`, because `cmd.exe` reads its command line by rules that `CreateProcessW` cannot apply safely. Console programs and graphical programs are protected.
- Unix: a text file without a `#!` line cannot be a background command (`Exec format error`): give it to its interpreter, `sh script &`.
- Unix: a job is not in the process group of the shell, so a signal sent to that group does not reach it: closing the terminal (`SIGHUP`), or a harness that aborts a script by its group, leaves the jobs running (as with `nohup`). Stop them with `jobs | kill` before leaving.
- Unix: a job that reads the terminal (`/dev/tty`) or, after `stty tostop`, writes to it, is stopped, as in a shell with job control; `kill` continues it after `SIGTERM` (`SIGCONT`), so that it ends.
- The output of a background command is not captured: it goes to the terminal, where it may mix with the prompt, unless redirected.
- No `fg`, `bg` or `wait`: follow a job with `jobs`, stop it with `jobs | kill`.
