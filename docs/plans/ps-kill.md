# Plan — cmdlets `ps` and `kill` (mini-iteration 23, FR-74)

## Context
Phase 10, the last one of the cmdlet roadmap (`docs/plans/cmdlet-roadmap.md`). Today, listing or stopping processes means calling `tasklist`/`taskkill` on Windows or `ps`/`kill` on Unix. Their outputs and options differ, and they give text, not objects. With `ps` and `kill`, PowerJ lists processes as objects on every platform (`ps java | sort cpu --desc | first 3`) and stops them from the pipeline (`ps notepad | kill`).

Constraints:
- **JDK only.** The cmdlets use `ProcessHandle` (`java.base`), with no native call, so they behave the same on Windows, Linux and macOS. Checked in the container: `ProcessHandle.current().info()` gives the user, the command, the arguments, the start time and the CPU time.
- **Limits of the JDK**:
  - no memory, no state (running, sleeping) and no CPU percentage. These are documented as limitations;
  - on Windows without administrator rights, the command and the user of other users' processes are unknown (`null`);
  - `destroy()` is always forcible on Windows (`supportsNormalTermination()` is `false`).
- `kill` is the second cmdlet family, after the file operations, that changes the system. It therefore gets the same safety measures: `-n/--dry-run`, refusals, and silence by default.

Decisions taken with the user:
1. `ps` without argument lists **all** the processes of the machine; `-u` filters by user.
2. The name filter uses **wildcards**, as `ls --filter`: case ignored, `.exe` optional on Windows.
3. `kill` takes **pids only**, never names: `ps notepad | kill`.
4. `kill` **returns at once**; `-w` waits for the end.
5. **No protection and no special case**: `kill` treats PowerJ's own pid, and its parents, like any other process. The same signal is sent the same way.
6. **Signals**: `kill -s/--signal <name>` sends `HUP`, `INT`, `QUIT`, `KILL`, `TERM`, `USR1`, `USR2`, `STOP` or `CONT`, with or without `SIG`, case ignored. Default `TERM`; `-f` is short for `-s KILL`. On Windows, only `TERM` and `KILL` exist (both forcible), and any other signal is a blocking error.

Branch: `claude/cmdlet-ps`, from `main` @ 19f9452.

## Syntax
```text
ps [name...] [-u|--user <user>]
kill [pid...] [-s|--signal <name>] [-f|--force] [-w|--wait] [-v|--verbose] [-n|--dry-run]
```

| Option | Cmdlet | Description |
|---|---|---|
| `name` (positional, multiple) | `ps` | Keeps the processes whose name matches one of these patterns: wildcards `*` and `?`, case ignored, and `.exe` optional on Windows (`ps notepad` finds `notepad.exe`). Default: all processes (open question 1). |
| `-u`, `--user` | `ps` | Keeps the processes of this user (case ignored; on Windows, `DOMAIN\user` or `user`). |
| `pid` (positional, multiple) | `kill` | Processes to stop, by number. Default: the objects of the pipeline (open question 3). |
| `-s`, `--signal` | `kill` | Signal to send: `HUP`, `INT`, `QUIT`, `KILL`, `TERM` (default), `USR1`, `USR2`, `STOP`, `CONT`; `SIG` prefix optional, case ignored. Windows: `TERM` or `KILL` only. |
| `-f`, `--force` | `kill` | Short for `-s KILL`: forcible stop. Without it, `TERM`: the process may clean up. Both together give a blocking error. |
| `-w`, `--wait` | `kill` | Waits until each process has ended before going on (open question 4). |
| `-v`, `--verbose` | `kill` | Emits the `ProcessEntry` of each process stopped. |
| `-n`, `--dry-run` | `kill` | Stops nothing; emits the `ProcessEntry` of each process that would be stopped. |

## Output
```java
@Display(columns = {"pid", "name", "user", "cpu", "start"})
public record ProcessEntry(
        long pid,
        Long parent,          // parent pid, null if none or unknown
        String name,          // file name of the command (java, notepad.exe), null if unknown
        String command,       // full path of the executable, null if unknown
        String commandLine,   // command and arguments, null if unknown
        String user,          // owner, null if unknown
        Instant start,        // start time, null if unknown
        Duration cpu)         // total CPU time, null if unknown
{ }
```
- `ps` emits one `ProcessEntry` per process, **sorted by pid**. The order of `ProcessHandle.allProcesses()` is unspecified, and a stable order is easier to read.
- The list is a snapshot: a process that ends in the meantime is simply missing.
- `cpu` is shown as `1 min 23 s` and `start` as a local date and time, by the existing formatter.
- `kill` emits nothing by default. With `-v` or `-n`, it emits the `ProcessEntry` of each process, before it is stopped.

## Behaviour
### `ps`
- Streaming: each process is read and emitted in turn, after the pids have been sorted. `ps | first 3` stops at once.
- Unknown information gives `null`; the process is still listed.
- `preview = true`: `ps` only reads.

### `kill`
- **Targets**:
  - arguments that are numbers (`kill 1234 5678`);
  - and/or pipeline objects: `ProcessEntry`, `ProcessHandle`, `Process` (from Java), or an integer (`Long`/`Integer`).
  - Any other object gives the non-blocking error `not a process: …`, and the object is skipped. An argument that is not a number gives a blocking error.
- **Sending**: the signal goes to each target in turn, PowerJ itself included (decision 5). With `-s STOP`, `CONT`, `HUP`… the process is not stopped: `kill` only sends, and `-w` waits for an end that may never come (Ctrl+C stops the waiting).
- **Errors** (non-blocking, the other targets are processed):
  - a pid that does not exist, or no longer exists: `kill: no process 1234`;
  - access denied (`EPERM`, or `OpenProcess` refused on Windows): `kill: access denied: 1234 (notepad.exe)`;
  - any other failure: `kill: cannot send TERM to 1234: <system message>`.
- **Wait**:
  - without `-w`, `kill` only sends the signal and goes on. The process may still be alive for a moment, or ignore a `SIGTERM`;
  - with `-w`, `kill` waits for its end (`onExit()`), and Ctrl+C stops the waiting.
- `kill` without argument and without pipeline input is a blocking error: `pid expected: kill <pid>... or ... | kill`.
- `preview = false`, as for `rm`: it never runs while typing.
- The native programs remain available with `^kill`, `^ps`, or `native.prefer` (FR-14, FR-16).

## Implementation
| Class | Role |
|---|---|
| `ProcessEntry` (record, `io.powerj.cmdlets`) | Output of `ps`; `static ProcessEntry of(ProcessHandle)` reads `info()` and `parent()`. |
| `Ps` | `@CmdletInfo(name = "ps", category = "System", preview = true)`. Sorts `ProcessHandle.allProcesses()` by pid, filters by name with `Globs` (`powerj-fs`, already used by `ls --filter`) in case-insensitive mode, ignoring `.exe` on Windows, and by user. |
| `Kill` | `@CmdletInfo(name = "kill", category = "System")`. `begin` checks the options and processes the arguments, `process` the pipeline objects. |
| `Signals` | Sends a signal to a pid with the **Foreign Function & Memory API** (`java.lang.foreign`, final since Java 22). Plain Java calls a C function of the system: no JNI, no C code, no native library to build or ship. PowerJ already uses this API in `powerj-core` (`StandardInput.isatty`). The calls are made the same way for every pid, PowerJ's included: `ProcessHandle.destroy()` is not used, because the JDK forbids it on the current process. |

`Signals` in detail:
- **Linux and macOS**: `int kill(pid_t pid, int sig)` of the C library, looked up with `Linker.nativeLinker().defaultLookup()`. `errno` is captured with `Linker.Option.captureCallState("errno")`: `ESRCH` means no process, `EPERM` means access denied. Signal numbers differ between the two systems for some signals, so they come from a table per system:

| Signal | Linux | macOS |
|---|---|---|
| `HUP`, `INT`, `QUIT`, `KILL`, `TERM` | 1, 2, 3, 9, 15 | 1, 2, 3, 9, 15 |
| `USR1`, `USR2` | 10, 12 | 30, 31 |
| `STOP`, `CONT` | 19, 18 | 17, 19 |

- **Windows**: `OpenProcess(PROCESS_TERMINATE, false, pid)`, `TerminateProcess(handle, 1)` and `CloseHandle` of `kernel32`, for `TERM` and `KILL` alike. `GetLastError` is captured the same way: `ERROR_INVALID_PARAMETER` means no process, `ERROR_ACCESS_DENIED` means access denied.
- **Native access**: the module `io.powerj.cmdlets` is added to `--enable-native-access` in the launcher (`powerj-dist/pom.xml`, next to `io.powerj.core`). Otherwise Java prints a warning at the first call, and a future version would refuse it.

Other changes:
- registration in `BuiltinCmdlets`;
- i18n: `ps.*`, `kill.*`, in English and French;
- no API or core change.

Docs:
- FR-74 in the specification, §11 row 23, §12.3 struck through, and the scope sentence;
- `docs/recettes/ps-kill.md`;
- README;
- roadmap.

**Wrap-up of the roadmap** (planned for this PR):
- `docs/recettes/pipeline-v1.md`: an end-to-end checklist of the §12.3 target pipeline with the cmdlets now available. `find` was cancelled, so it uses `ls -r --filter *.java | where { $_.modified > … }`;
- README "Status and roadmap": the v1 cmdlet backlog is done (`uniq`, `find`, `tee`, `http`, `open`, `table`/`tree` remain), and the next step is v2 control structures.

## Tests
- `PsTest`:
  - `ps` lists `ProcessHandle.current()`, with its pid, its parent and a `start`;
  - the pids are sorted;
  - filtering by name (a process started by the test: `java` running a small sleeping class), with a wildcard and a different case;
  - filtering by `--user` (the current user);
  - a name that matches nothing gives no object and no error.
- `KillTest`:
  - stops a process started by the test (`ProcessBuilder` of `java -cp … Sleep`), with `-w`: it is no longer alive;
  - `-f`, `-s KILL`, `-s SIGTERM`, `-s term` (names);
  - on Unix: `-s STOP` then `-s CONT` on a process started by the test (it is still alive, and `/proc/<pid>/stat` shows `T` then `S` on Linux); on Windows: `-s HUP` gives the blocking error;
  - `-s FOO`, and `-f` with `-s TERM`, give blocking errors;
  - `-n`: emits the entry and the process is still alive;
  - `-v`;
  - pipeline `ProcessEntry`, `ProcessHandle`, `Long`;
  - an unknown pid, an object that is not a process. Stopping PowerJ itself is checked by hand: it would stop the test JVM.
  - an argument that is not a number.
- `BuiltinCmdletsTest`: `ps | where { $_.pid == … }` in the shell, and `help kill`.

## Verification
- `./mvnw -Djava.release=25 verify`
- Manually:
```text
ps | first 5
ps java
ps java | sort cpu --desc | first 3
ps -u root | count        # or your user name
notepad                  # Windows; on Linux: ^sleep 300 &
ps notepad | kill -n
ps notepad | kill -v -w
kill 999999
kill -s STOP <pid>; ps <name>; kill -s CONT <pid>     # Linux / macOS
kill -s QUIT <pid of a JVM>                         # the JVM prints its threads
kill (ProcessHandle.current().pid())                # PowerJ receives TERM and closes
```

## Open questions
All answered (see the decisions in Context).
