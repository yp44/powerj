# Plan — cmdlets `ps` and `kill` (mini-iteration 23, FR-74)

## Context
Phase 10, the last one of the cmdlet roadmap (`docs/plans/cmdlet-roadmap.md`). Today, listing or stopping processes means calling `tasklist`/`taskkill` on Windows or `ps`/`kill` on Unix. Their outputs and options differ, and they give text, not objects. With `ps` and `kill`, PowerJ lists processes as objects on every platform (`ps java | sort cpu --desc | first 3`) and stops them from the pipeline (`ps notepad | kill`).

Constraints:
- **JDK only.** The cmdlets use `ProcessHandle` (`java.base`), with no native call, so they behave the same on Windows, Linux and macOS. Checked in the container: `ProcessHandle.current().info()` gives the user, the command, the arguments, the start time and the CPU time.
- **Limits of the JDK**:
  - no memory, no state (running, sleeping) and no CPU percentage. These are documented as limitations;
  - on Windows without administrator rights, the command and the user of other users' processes are unknown (`null`);
  - `destroy()` is always forcible on Windows (`supportsNormalTermination()` is `false`).
- `kill` is the second cmdlet family, after the file operations, that changes the system. It therefore gets the same safety measures: `-n/--dry-run`, and silence by default.

Decisions taken with the user:
1. `ps` without argument lists **all** the processes of the machine; `-u` filters by user.
2. The name filter uses **wildcards**, as `ls --filter`: case ignored, `.exe` optional on Windows.
3. `kill` takes **pids only**, never names: `ps notepad | kill`.
4. `kill` **returns at once**; `-w` waits for the end.
5. **No protection**: `kill` has no list of protected processes; the parents of PowerJ (the terminal) are stopped like any other process.
6. **JDK API only** (`ProcessHandle.destroy()` / `destroyForcibly()`), no `java.lang.foreign`. The user accepts its limits:
   - no other signal than the stop (`TERM`, or `KILL` with `-f`); `HUP`, `STOP`, `CONT`… go through the native program (`^kill -HUP 1234`);
   - the JDK forbids stopping the current process: PowerJ's own pid gives the non-blocking error `cannot stop PowerJ itself (use exit)`. This is the JDK's rule, reported like the other errors, not a list of protected pids;
   - "access denied" and other failures are not told apart (`destroy()` only returns `false`).

Branch: `claude/cmdlet-ps`, from `main` @ 19f9452.

## Syntax
```text
ps [name...] [-u|--user <user>]
kill [pid...] [-f|--force] [-w|--wait] [-v|--verbose] [-n|--dry-run]
```

| Option | Cmdlet | Description |
|---|---|---|
| `name` (positional, multiple) | `ps` | Keeps the processes whose name matches one of these patterns: wildcards `*` and `?`, case ignored, and `.exe` optional on Windows (`ps notepad` finds `notepad.exe`). Default: all processes (decision 1). |
| `-u`, `--user` | `ps` | Keeps the processes of this user (case ignored; on Windows, `DOMAIN\user` or `user`). |
| `pid` (positional, multiple) | `kill` | Processes to stop, by number. Default: the objects of the pipeline (decision 3). |
| `-f`, `--force` | `kill` | Forcible stop: `destroyForcibly()` (`SIGKILL` on Unix). Without it, `destroy()` (`SIGTERM` on Unix: the process may clean up). On Windows, both are forcible. |
| `-w`, `--wait` | `kill` | Waits until each process has ended before going on (decision 4). |
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
- **Stop**: each target in turn gets `destroy()`, or `destroyForcibly()` with `-f`.
- **Errors** (non-blocking, the other targets are processed):
  - a pid that does not exist, or no longer exists (`ProcessHandle.of(pid)` empty): `kill: no process 1234`;
  - PowerJ's own pid (the JDK throws `IllegalStateException`): `kill: cannot stop PowerJ itself (use exit)`;
  - `destroy()` returns `false` (access denied, system process…): `kill: cannot stop 1234 (notepad.exe)`.
- **Wait**:
  - without `-w`, `kill` only asks for the stop and goes on. The process may still be alive for a moment, or ignore a `SIGTERM`;
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
  - `-f`;
  - `-n`: emits the entry and the process is still alive;
  - `-v`;
  - pipeline `ProcessEntry`, `ProcessHandle`, `Long`;
  - an unknown pid, the pid of the test JVM (`cannot stop PowerJ itself`), an object that is not a process;
  - an argument that is not a number.
- `BuiltinCmdletsTest`: `ps | where { $_.pid == … }` in the shell, and `help kill`.

## Verification
- `./mvnw verify`
- Manually:
```text
ps | first 5
ps java
ps java | sort cpu --desc | first 3
ps -u root | count        # or your user name
notepad                  # Windows; on Linux, sleep 300 in another terminal
ps notepad | kill -n
ps notepad | kill -v -w
kill 999999
kill (ProcessHandle.current().pid())   # cannot stop PowerJ itself (use exit)
^kill -HUP <pid>                        # other signals: the native program
```

## Open questions
All answered (see the decisions in Context).
