# Acceptance test — Mini-iteration 23: `ps` and `kill`

**Goal:** verify that `ps` lists the processes as `ProcessEntry` objects, sorted by pid and filtered by name or user, and that `kill` stops the processes given by pid or received from the pipeline, with `-f`, `-w`, `-v` and `-n`.

> Messages are shown in the system language; set `language=en` in config.properties (or `POWERJ_LANG=en`) to get the English texts quoted here.

## Getting the deliverables

As in the previous steps: **Actions** tab, latest run of the **CI** workflow, artifact `powerj-windows-x64-installer` or `powerj-windows-x64-portable`.

## Preparation

Open two Notepad windows (`notepad`, twice). On Linux or macOS, run `sleep 600` in two other terminals instead, and read `sleep` for `notepad` below.

## Scenario

| # | Action | Expected result |
|---|---|---|
| 1 | `ps \| first 5` | Table `pid name user cpu start`, sorted by pid; system processes may have empty `name` and `user` (not readable without administrator rights). |
| 2 | `ps notepad`, then `ps NOTEPAD.EXE`, then `ps note*` | The two Notepad processes each time (case ignored, `.exe` optional). |
| 3 | `ps -u <your user name> \| count` | The number of your processes. |
| 4 | `ps java* \| where { $_.pid == ProcessHandle.current().pid() }` | PowerJ itself. |
| 5 | `ps notepad \| map { $_.commandLine }` | The command line of each Notepad. |
| 6 | `ps \| sort cpu --desc \| first 3` | The three processes that used the most CPU time. |
| 7 | `ps notepad \| kill -n` | The two processes are listed; both windows stay open. |
| 8 | `kill <pid of the first Notepad> -v` | The window closes; its `ProcessEntry` is displayed. |
| 9 | `ps notepad \| kill -f -w` | The second window closes; the prompt comes back once it has ended. |
| 10 | `ps notepad` | Nothing. |
| 11 | `kill 999999999` | `kill: no process 999999999`. |
| 12 | `kill abc` | `kill: invalid pid: abc (a process number is expected)`. |
| 13 | `kill (ProcessHandle.current().pid())` | `kill: cannot stop PowerJ itself (use exit)`; PowerJ goes on. |
| 14 | `"x" \| kill` | `kill: not a process: x`. |
| 15 | `kill` | `kill: pid expected: kill <pid>... or ... \| kill`. |
| 16 | Alt+V, then type `ps note` | The preview shows the processes; Alt+V, then type `kill 1` shows no result (`kill` never runs in preview). |
| 17 | `help ps`, `help kill` | Their help, with the options and the examples. |
| 18 | `POWERJ_LANG=fr`, then `kill 999999999` | `kill : aucun processus 999999999`. |
| 19 | Linux or macOS: `^kill -STOP <pid>`, then `^kill -CONT <pid>` | Other signals go through the native program. |

## Known limitations

- The JDK gives no memory, no state and no CPU percentage of a process.
- On Windows without administrator rights, the command and the user of other users' processes are unknown (empty).
- On Windows, every stop is forcible: the program cannot save its work, as with `taskkill /F`.
- `kill` sends no other signal than the stop (`TERM`, or `KILL` with `-f`): use `^kill -HUP <pid>` and the like on Linux and macOS.
- PowerJ cannot stop itself with `kill`: use `exit`.
