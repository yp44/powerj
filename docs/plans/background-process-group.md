# Plan — background commands in their own process group, with the FFM API (issue #128)

> This file is the plan as it was written (the first sections), then the measures, then the decision and what was built (the last
> section). The plan and the measures use the names of the prototypes. Where they differ from the code today:
>
> | In the plan and the measures | Today |
> |---|---|
> | `GroupLauncher` (the experiment of "in a real PowerJ shell") | `BackgroundLauncher`, `PosixJobs`, `WindowsJobs` |
> | `PosixSpawn` in `src/test/.../nativeexec/prototype/` | `PosixSpawn` in `src/main/.../nativeexec/` |
> | `PosixSpawnPrototypeTest`, `WindowsSpawnPrototypeTest` | `PosixSpawnTest`, `WindowsSpawnTest` (in `src/test/.../nativeexec/`, like `PtyProbe`) |
> | `BackgroundGroupTest` | `BackgroundJobsTest` (Unix), `BackgroundJobsWindowsTest` |
> | `powerj-core/src/test/prototype/pty-check.py` | `powerj-core/src/test/tools/pty-check.py` |
> | `WindowsSpawn`, `WindowsCommandLine` as test code | the same classes in `src/main/.../nativeexec/` |
> | the variable `POWERJ_BACKGROUND=group` | gone: the group is the only mode; `POWERJ_BACKGROUND_SH_FALLBACK=true` only allows the old `sh` start on a system that cannot do it |

## Context

A statement ended by `&` starts a native command in the background (FR-75). Ctrl+C must stop the foreground command only.

- **Unix today**: `NativeRunner.startBackground` runs the program through `/bin/sh -c "trap '' INT QUIT; exec …"` (`shielded`). It works and adds no process, but it only *ignores* the signals.
- **Windows today**: no protection; a console program started with `&` receives the Ctrl+C of the console (known limit, FR-75 and `docs/acceptance/background.md`).
- `ProcessBuilder` cannot choose the process group. The FFM API can (`posix_spawn` + `POSIX_SPAWN_SETPGROUP`; `CreateProcessW` + `CREATE_NEW_PROCESS_GROUP`). FFM is already used (`StandardInput`, `WindowsConsole`) and `--enable-native-access=…,io.powerj.core` is already in the launcher (`powerj-dist/pom.xml:199`): no new consent.

The issue is **an exploration first**: its outcome may be "keep `sh`" and a close as not planned.

## Decisions taken with the user

| Question | Answer |
|---|---|
| Branch | `feat/128-background-process-group-ffm`, from `main` (ca348d4). Not the session branch `claude/wizardly-ptolemy-bsi961`. |
| Where is the plan | `docs/plans/background-process-group.md`, committed and pushed on the branch (CONTRIBUTING.md: "Plan it in `docs/plans/<name>.md`"). No pull request and no comment on the issue unless asked. |
| Aimed decision | **(b)** FFM on both platforms, `sh` dropped. The prototypes can still downgrade it to (a) or (c) (see Phase 3). |
| Prototypes | Linux here (download a JDK 25 into the scratchpad: the sandbox has JDK 21 only, FFM is final from 22 and the build requires 25). Windows (and macOS) through the existing CI jobs. |

## What the code review found (not in the issue)

1. **`Process` can be subclassed** (`JobsTest.FakeProcess extends Process` is the precedent). A `SpawnedProcess extends Process` (`pid()`, `onExit()`, `waitFor()`, `exitValue()`, `destroy()`, `destroyForcibly()`, `isAlive()`, `toHandle()`; null streams) keeps the signature `List<Process> startBackground(...)`, so `Jobs`, `PipelineExecutor.background` and `Kill` (through `ProcessHandle`) do **not** change.
2. **The existing Unix test cannot pass unchanged.** `InterpreterTest.backgroundCommandGivesControlBackAtOnce` (line 268) sends `kill -INT <pid>` straight to the program and expects it to survive: that tests the *disposition* (`trap ''`). A process group does not ignore anything; `kill -INT <pid>` would kill it. The test must instead prove that a `SIGINT` sent to the **foreground group** does not reach it, and that its `pgid` is its own. The "Done when" of the issue ("existing background tests pass unchanged") must be amended in the decision. This is the behaviour of an interactive bash with job control.
3. **`posix_spawn` has no working directory and no fd hygiene by default.** The session has its own current directory (`ProcessBuilder.directory`): needs `posix_spawn_file_actions_addchdir_np` (glibc ≥ 2.29, macOS ≥ 10.15). `ProcessBuilder` closes every fd above 2 in the child; raw `posix_spawn` does not: `addclosefrom_np` (glibc ≥ 2.34) or `POSIX_SPAWN_CLOEXEC_DEFAULT` (macOS) or explicit `addclose` per fd, serialised by a lock. Also reset the signal mask and the dispositions (`POSIX_SPAWN_SETSIGMASK`/`SETSIGDEF`).
4. **Per-OS constants and opaque structs**: `O_CREAT|O_TRUNC|O_APPEND` differ between Linux and macOS; `posix_spawnattr_t` / `posix_spawn_file_actions_t` are opaque (allocate generously, 1 KiB each); `errno` of `waitpid` through `Linker.Option.captureCallState("errno")`.
5. **The CI does not run on a plain push** (`on: push: [main]`, `pull_request`, `workflow_dispatch`). The Windows and macOS prototypes need either a **draft pull request** (to ask the user for) or a `workflow_dispatch` of `ci.yml` on the branch (the whole CI, the Windows job takes up to 60 minutes).
6. **Windows traps**: `CommandLineToArgvW`-compatible quoting (backslashes before a quote, empty argument), `.bat`/`.cmd` (cmd.exe metacharacters: the JDK has special handling after CVE-2024-24576 "BatBadBut"; do not regress, fall back to `ProcessBuilder` for them or refuse), the Unicode environment block, and handle inheritance (`bInheritHandles=TRUE` leaks every inheritable handle: use `STARTUPINFOEX` + `PROC_THREAD_ATTRIBUTE_HANDLE_LIST`). A `GenerateConsoleCtrlEvent` test needs a console on the runner and must not kill the test JVM.

## Phase 0 — Branch and tools

- `git fetch origin main && git switch -c feat/128-background-process-group-ffm origin/main`.
- JDK 25 (Temurin) extracted into the scratchpad, never into `/usr`; check `JAVA_HOME=… ./mvnw -v`, then the baseline `./mvnw -B -pl powerj-core -am test -Dtest=InterpreterTest,JobsTest`. If the download host is blocked by the network policy, read the `environment.network` documentation and ask the user before changing anything.
- Commit 1: `docs(plans): plan for background process groups with the FFM API (Refs #128)`; `git push -u origin feat/128-background-process-group-ffm`.

## Phase 1 — Linux (and macOS) prototype

Test code only, not shipped: `powerj-core/src/test/java/io/powerj/core/nativeexec/prototype/PosixSpawnPrototypeTest.java`, `@EnabledOnOs({LINUX, MAC})`, so the macOS job of the CI runs it too. Each measure is printed (the numbers go in the decision).

| # | Check | How |
|---|---|---|
| 1 | Own process group | `pgid(child) == child != pgid(JVM)` (`/proc/<pid>/stat` on Linux, `ps -o pgid= -p` on macOS) |
| 2 | Survives the foreground group's SIGINT | start a group leader `L` (`SETPGROUP`, 0) and a member `F` (`setpgroup = L`) = "foreground"; start the background `B` with our code; `kill(-L, SIGINT)`: `L` and `F` die, `B` lives. Control: a `ProcessBuilder` child placed in `L` dies |
| 3 | Exit code | `waitpid` thread: `sh -c 'exit 7'` gives 7; killed by signal gives 128+n (the `ProcessBuilder` convention); 1000 runs, count lost statuses (race with the JDK reaper); does `ProcessHandle.of(pid).onExit()` still complete |
| 4 | Kill | `ProcessHandle.of(pid).destroy()` and `destroyForcibly()` stop a spawned child |
| 5 | fd hygiene | the child sees only 0, 1, 2 (`/proc/<pid>/fd`, `ls /dev/fd` on macOS) while the JVM holds open files and pipes |
| 6 | Redirections | `> f`, `>> f`, `2>&1`, `2> g` through `addopen`/`adddup2`; stdin on `/dev/null` |
| 7 | Pipeline | `printf 'a\nb\n' \| sort > out.txt`: `pipe` + `adddup2`/`addclose`, EOF reaches `sort`, no fd left in the parent |
| 8 | Directory and environment | `addchdir_np` gives the session directory; `env` prints exactly the session snapshot (a variable only in the JVM's environment is absent) |
| 9 | Start errors | missing file, non-executable file: same `native.startFailed` text as `ProcessBuilder` |
| 10 | Latency | median of 200 spawns of `true`: `posix_spawn` vs `ProcessBuilder` |
| 11 | Terminal effects (manual, Linux and macOS) | `sleep 30 &` then `cat`, then `Ctrl+C`: only `cat` stops. `stty tostop; echo hi &` stops the job with `SIGTTOU` (as bash). A job that reads `/dev/tty` stops with `SIGTTIN`. Written into the acceptance file |

## Phase 2 — Windows prototype

`powerj-core/src/test/java/io/powerj/core/nativeexec/prototype/WindowsSpawnPrototypeTest.java`, `@EnabledOnOs(WINDOWS)`, executed by the `windows` job.

| # | Check | How |
|---|---|---|
| 1 | Survives Ctrl+C | `ping -t localhost` started with `CREATE_NEW_PROCESS_GROUP`; the test JVM installs a Java handler with `SetConsoleCtrlHandler` (an FFM upcall returning `TRUE`, so it neither dies nor sets the "ignore" flag that children inherit); `GenerateConsoleCtrlEvent(CTRL_C_EVENT, 0)`; the child lives. Control: the same program from `ProcessBuilder` ends with `0xC000013A`. If the runner has no console, `AllocConsole()` first (to find out) |
| 2 | Exit code | `WaitForSingleObject` + `GetExitCodeProcess` on a platform daemon thread; `cmd /c exit 7` gives 7 |
| 3 | Kill | `TerminateProcess` on our handle, and `ProcessHandle.of(pid).destroy()` |
| 4 | Redirections, pipeline | handles opened by `CreateFileW` (`FILE_APPEND_DATA` for `>>`), `NUL` for stdin, `CreatePipe`; `PROC_THREAD_ATTRIBUTE_HANDLE_LIST` so that nothing else is inherited (check with a child that lists its handles) |
| 5 | Command line | a table of nasty arguments (spaces, quotes, trailing backslashes, empty, non-ASCII) run through a small Java `main` that prints `args`, compared with the result of `ProcessBuilder`. The quoting function is pure: it also gets a unit test that runs on Linux |
| 6 | Environment | Unicode block (`CREATE_UNICODE_ENVIRONMENT`) built from the session snapshot, sorted; a variable only in the JVM's is absent |
| 7 | `.bat` / `.cmd` | decide: fall back to `ProcessBuilder` (documented limit) or refuse; never build the `cmd.exe` quoting ourselves without the JDK's checks |

## Phase 3 — Decision (gate)

Criteria, filled with the numbers of Phases 1-2:

- **Go (b)**: checks 1-9 pass on Linux and macOS, 1-6 on Windows; no exit status lost in 1000 runs; no fd or handle leak; latency within a few ms of `ProcessBuilder`; the `.bat` case has a safe answer.
- **Downgrade to (a)** if only Windows is worth it (Unix `sh` is proven, Windows is the real defect), or **(c)** if Windows has no console-survival or no safe quoting.
- The decision is a **draft comment for issue #128** with the numbers and the amended "Done when" (point 2 above). I show it to the user and post it only if asked.

## Phase 4 — Implementation (only if go), one platform per pull request

Order: **Unix first** (testable here, creates the shared seam), then Windows. The order can be swapped.

New code in `powerj-core/src/main/java/io/powerj/core/nativeexec/` (package already exported, FFM already there):

- `BackgroundLauncher` (package-private seam: `List<Process> start(List<Command>, Spawn)`), chosen by `Host.windows()`.
- `SpawnedProcess extends Process` (shared; the waiter thread completes `onExit()`).
- Unix PR: `PosixSpawn` (bindings: `posix_spawn`, `posix_spawnattr_*`, `posix_spawn_file_actions_*`, `pipe`, `waitpid`, per-OS constants) and the pure helpers for `argv`/`envp`/exit-status decoding.
- Windows PR: `WindowsSpawn` and `WindowsCommandLine`.
- `NativeRunner.startBackground` keeps its signature and delegates; **delete `shielded` and `/bin/sh`**; `newBuilder` stays the one place with `environment().clear()` for the foreground paths (`run`, `runGroup`, unchanged), and the new path reads the same `session.environmentSnapshot()` (a test proves a JVM-only variable is absent).
- `Jobs`, `PipelineExecutor.background`, `Kill`: untouched.

Tests: rewrite `InterpreterTest.backgroundCommandGivesControlBackAtOnce` (foreground-group SIGINT instead of `kill -INT <pid>`); keep the other background tests as they are (`backgroundRedirectionsPipelinesAndNextStatement`, environment rule); promote the checks of Phases 1-2 into permanent tests; unit tests for the pure helpers (JaCoCo floor: new FFM code is hard to cover, keep the logic out of the downcalls).

Documents updated in the same PR: `docs/SPECIFICATION.md` FR-75 (the Ctrl+C line, ~1130), `docs/acceptance/background.md` (drop the Windows known limit at line 39; add `ping -t localhost &` + Ctrl+C, the `pgid` row, the `stty tostop` row), `CHANGELOG.md`, `docs/quality/lint-baseline.md` (restricted-method count, now 7), `docs/quality/mutation.md` (NativeRunner row), `docs/ARCHITECTURE.md` if it mentions `startPipeline` for background.

Commits: one step per commit, each builds and passes the tests; `type(scope): subject` in English; `Refs #128`, and `Fixes #128` on the last PR.

## Verification

- Linux, JDK 25: `./mvnw -B -Pdist verify 2>&1 | tee build.log`, then `ci/check-javac-warnings.sh build.log` (the count may go down, never up).
- Phases 1-2 results: macOS and Windows jobs of the CI (draft PR or `workflow_dispatch`).
- `grep -c "environment().clear()" powerj-core/src/main/java/io/powerj/core/runtime/NativeRunner.java` stays 1.
- Manual acceptance (`docs/acceptance/background.md`): Windows `ping -t localhost &`, Ctrl+C on another command, `jobs` still lists `ping`; Unix `sleep 30 & cat`, Ctrl+C, `jobs`; `jobs | kill`; `exit` leaves the job running.

## Risks and open points

- JDK 25 may not be downloadable in the sandbox (network policy): then the Linux prototype also runs in the CI.
- `waitpid` may race with the JDK's own reaper (check 3): if it does, fall back to polling `ProcessHandle.onExit()` and give up the exit code of Unix jobs (a regression: `[1] done … (exit 0)`), which would push the decision toward (a).

## Results — Linux prototype (Phase 1)

Run on Linux x86_64 (Ubuntu 24.04, glibc 2.39), OpenJDK 25.0.4. Code when it was measured: `powerj-core/src/test/java/io/powerj/core/nativeexec/prototype/`
(`PosixSpawn`, `PosixSpawnPrototypeTest`, `PtyProbe`) and `powerj-core/src/test/prototype/pty-check.py`; test code only, nothing
shipped. Today `PosixSpawn` is production code (`src/main`), the 14 checks are `PosixSpawnTest`, and the script is
`powerj-core/src/test/tools/pty-check.py`. The 14 tests pass, the whole `powerj-core` suite passes (940 tests), and the javac warnings stay at 8 (main) and 5 (test).
**Not run: macOS, glibc older than 2.34, musl, aarch64.**

| # | Check | Result |
|---|---|---|
| 1 | Own process group | `pgid(child) == child`, not the JVM's. A `ProcessBuilder` child and the `sh` + `trap` child are both in the JVM's group. |
| 2 | Survives the foreground group's SIGINT | `kill(-leader, SIGINT)`: leader and member exit 130, the background process (own group) is alive 300 ms later. |
| 2b | **`kill -INT <pid>` straight to the process** | exit 130: it dies. The `sh` + `trap` child survives it. The existing test `backgroundCommandGivesControlBackAtOnce` must change, as announced. |
| 3 | Exit code | `exit 7` gives 7, SIGTERM 143, SIGKILL 137 (128 + signal, as `Process`). **1000 runs on 4 threads with 239-268 `ProcessBuilder` runs at the same time and 470 children also followed by `ProcessHandle.onExit()`: 0 wrong code, 0 lost status.** `ProcessHandle` (`isAlive`, `parent`, `children`, `onExit`) works on a `posix_spawn` child. |
| 4 | Kill | `ProcessHandle.destroy()` gives 143, `destroyForcibly()` 137: `Kill` and `jobs \| kill` need no change. |
| 5 | Descriptors | With `addclosefrom_np(3)` the child has exactly 0, 1, 2. Without it (control) it inherits every descriptor of the JVM that is not close-on-exec (27 to 44 descriptors, including a pipe of the JVM). |
| 6 | Redirections | `>` truncates, `2>>` appends, `2>&1` joins, stdin on `/dev/null` ends `cat` at once. |
| 7 | Pipeline | `printf \| sort > f`: 50 rounds, EOF reaches `sort`, the JVM has the same 3 pipes before and after. |
| 8 | Directory, environment | `addchdir_np` gives the session directory; `env` prints exactly the variables given, none of the JVM's 175. |
| 9 | Start errors | `posix_spawn` returns the errno (2 for a missing file, 13 for a file that is not executable or a directory), the same numbers and texts as `ProcessBuilder` (JDK 25: `Exec failed, error: 2 (No such file or directory)`). No test depends on the exact text of `native.startFailed`. |
| 10 | Latency | Median of 200 starts of `true`: `posix_spawn` 1.0-1.2 ms, `ProcessBuilder` 1.8-2.1 ms, `ProcessBuilder` through `sh` + `trap` 2.6-3.0 ms. |
| 11 | Terminal, on a real pseudo-terminal | See below. |
| 12 | Signal state of the child | With `SETSIGDEF` (all signals) and `SETSIGMASK` (empty): `SigIgn = 0`, `SigBlk = 0`, same as a `ProcessBuilder` child. The `sh` + `trap` child has `SigIgn = 6` (INT, QUIT). |

**Check 11** (`pty-check.py`): a pty whose session leader plays the shell, starts the probe in its own foreground group, then Ctrl+C
(`\x03`) is written to the master. B is the background command, C a companion `sleep` in the foreground group.

| Scenario | B started as | B | C |
|---|---|---|---|
| Ctrl+C | `ProcessBuilder` (what a Windows console program is today) | **dead** | dead |
| Ctrl+C | `sh` + `trap` (Unix today) | alive | dead |
| Ctrl+C | **`posix_spawn`, own group** | **alive** | dead |
| B writes to the terminal, `tostop` off | `sh` + `trap` / own group | alive, line printed / alive, line printed | |
| B writes to the terminal, `stty tostop` | `sh` + `trap` | alive, line printed | |
| B writes to the terminal, `stty tostop` | own group | **STOPPED** (`SIGTTOU`), line not printed | |
| B reads the terminal | `sh` + `trap` | alive (reads) | alive |
| B reads the terminal | own group | **STOPPED** (`SIGTTIN`) | alive |

### What the results change in the plan

1. **Behaviour changes on Unix**, the same as an interactive bash with job control, to write into FR-75 and the acceptance file:
   with `stty tostop`, a background command that writes to the terminal is stopped; one that reads the terminal (`/dev/tty`: `ssh`,
   `sudo`, `gpg`) is stopped. Today they are not, because they stay in the foreground group.
2. **A stopped job ignores `SIGTERM`** (measured: the signal stays pending until `SIGCONT`; `SIGKILL` works). `jobs | kill`
   without `-f` would leave such a job alone. **Done** (review of #129, point 2): `Kill` sends `SIGCONT` after a normal `SIGTERM`
   on Unix, through `kill(2)` called by FFM (`io.powerj.fs.ProcessSignals`, no program started); `PsKillTest.killEndsAProcessThatTheTerminalHasStopped` fails without it.
3. **The pty harness must not make the probe the session leader**: when the leader dies, the kernel sends `SIGHUP` to its
   foreground group, which killed the `sh` + `trap` child in a first version of the script. A real terminal's leader is the
   user's shell; `pty-check.py` now plays it.
4. **Production code needs, beyond the prototype**: the arguments in the charset of `sun.jnu.encoding` (the prototype uses UTF-8);
   `addclosefrom_np` only exists from glibc 2.34 (macOS: `POSIX_SPAWN_CLOEXEC_DEFAULT` and `addinherit_np`, to verify on the macOS job;
   musl: explicit `addclose` of the descriptors found in `/proc/self/fd`, or fall back to `sh`); the same `native.startFailed` message
   built from the errno; the restricted-method warnings (`downcallHandle` x2, `reinterpret` x1 here) added to the count in
   `docs/quality/lint-baseline.md`.
5. **Phase 3 gate, Linux side: go.** Checks 1-10 and 12 pass, no lost status, no leak, faster than the `sh` wrapper. The decision
   waits for macOS and Windows.

## Results — in a real PowerJ shell (group mode, Linux)

> The opt-in variable `POWERJ_BACKGROUND=group` of this experiment no longer exists: when option (b) was chosen the group mode became the
> only mode (see "Decision" at the end). The measures stand.

`POWERJ_BACKGROUND=group` in the session started a background job through `GroupLauncher` (`NativeRunner.startBackground`; the class became
`BackgroundLauncher`); unset, nothing changed. Checked on the packaged image (`./mvnw -Pdist -DskipFx -DskipTests package`, 16 s) driven on a pseudo-terminal by a script
that types the lines of `docs/acceptance/background.md` (E1 to E8) and a real Ctrl+C. `ps -o pid,pgid,tpgid,stat,args`:

| Step | Result |
|---|---|
| E1, variable unset: `sleep 300 &` | `pgid 22281 = tpgid 22281`, state `S+`: in the foreground group of the terminal (protected only by `sh`). |
| E2, `env --set POWERJ_BACKGROUND=group`, `sleep 301 &` | `pid 22333 = pgid 22333`, `tpgid 22281`, state `S`: own group, background. |
| E5, `sleep 302 \| sleep 303 &` | both processes in `pgid 22340`, the pid of the first stage. |
| E3, `sleep 100` then Ctrl+C (`\x03`) | `^C`, only this `sleep` stops; `jobs` still lists the three jobs as running. |
| E6, `printf ... \| ^sort > f &` | `a`, `b` in both modes (the first stage ends at once: see the fix below). |
| E7, `stty tostop` then `sh -c "sleep 1; echo hi" &` | group mode: job `T` (stopped by `SIGTTOU`), nothing printed. `sh` path: `hi-from-job` printed, job ends with exit 0. |
| E8, `env --unset POWERJ_BACKGROUND`, `sleep 304 &` | `pgid = tpgid` again. |

Found on the way:

1. **A pipeline whose first stage ends at once failed** (`cannot start [printf, sort]: error=1, Operation not permitted`): the
   second stage joins the group of the first with `setpgid`, which fails once the first has been reaped and its group is gone.
   The launcher (`PosixJobs` today) now starts every stage first and only then starts the `waitpid` threads (`PosixSpawn.reap`: a zombie keeps its group).
   Covered by `BackgroundJobsTest.redirectionsAndPipelinesWork` and `PosixJobsTest`.
2. **A defect of `main`, unrelated to the experiment**: in the packaged shell `jobs | kill` fails with `kill: not a process: Job[...]`
   until `jobs` has been displayed once (with the variable unset too); `kill $last.pid` works. `Kill.pidOf` returns `null` for a `Job`:
   `io.powerj.core.runtime` is exported to `io.powerj.shell` only, so the cmdlets module cannot read the record by reflection, unless the
   display of the core has made the same `Method` accessible (`RecordView` hands one `Method` to every module). The defect is wider
   (`jobs | to-json`, `greet | to-json`). Filed as [#130](https://github.com/yp44/powerj/issues/130), not fixed by this change; fixed
   by making every command read through the engine ([ADR 0011](../adr/0011-every-command-reads-the-properties-of-an-object-through-the-engine.md)).
3. `kill` on a pipeline job stops its last process only (the pid of the job); the other stages stay. True of the `sh` path too. A job being
   a process group, the whole job could be stopped (`kill(-pgid)`) in Phase 4.
4. In the real shell `sort` and `cat` are cmdlets: a pipeline with `&` needs `^sort` (the acceptance row 7 of the existing checklist
   says `sort`; it only works in the unit test, whose registry is empty).

## Results — Windows prototype (Phase 2)

Run on the `windows-latest` job of the CI (run 259, then run 260 for the Ctrl+C report), JDK 25. Code when it was measured: `WindowsSpawn`,
`WindowsCommandLine`, `WindowsConsoleProbe`, `ArgvEcho`, `StdioEcho` and `WindowsSpawnPrototypeTest` in
`powerj-core/src/test/java/io/powerj/core/nativeexec/`, test code only. Today `WindowsSpawn` and `WindowsCommandLine` are production code
(`src/main`) and the checks are `WindowsSpawnTest`. The 12 checks pass; `WindowsCommandLineTest` (the pure
quoting and environment block, 8 tests) also runs on Linux and macOS.

| # | Check | Result |
|---|---|---|
| 1 | Ctrl+C | See the table below. |
| 2 | Exit code | `cmd /c exit 7` gives 7; `ProcessHandle.isAlive`/`onExit` work on a `CreateProcessW` child. |
| 2b | Lost status, leaked handle | 200 runs on 4 threads: 0 wrong code, 670 ms; handles of the JVM 446 before, 441 after. |
| 3 | Kill | `TerminateProcess`, `ProcessHandle.destroy()` and `destroyForcibly()` all give exit code 1 (what the JDK does). |
| 4 | Redirections | `>` truncates, `>>` appends (`FILE_APPEND_DATA`), `2>&1` shares one handle, stdin on `NUL` ends at once. |
| 4b | Pipeline | `type f \| sort > g`: sort sees the end of its input, so nobody else holds the write end. |
| 4c | Handle list | With `PROC_THREAD_ATTRIBUTE_HANDLE_LIST` a read on a pipe ends at once (`ERROR_BROKEN_PIPE`) once the parent closed its write end; without it (control) the child holds the write end and the read blocks until the child is killed. |
| 5 | Command line | `CreateProcessW` with `WindowsCommandLine`: 12 of 12 tricky arguments come back exactly (spaces, `"`, trailing `\`, tab…). **`ProcessBuilder` gets 8 of the 12 wrong** (an embedded quote splits the argument). An empty argument is the same for both. |
| 6 | Directory, environment | The child sees the Unicode variable of the session, not `USERPROFILE` of the JVM, and the directory given. |
| 7 | `.bat` / `.cmd` | Refused by the prototype; `ProcessBuilder` runs them (`hello x`). The launcher must hand them to `ProcessBuilder`. |
| 8 | Start errors | Same Win32 numbers as `ProcessBuilder`: 2 (missing), 193 (not an executable), 5 (a folder). |
| 9 | Latency | Median of 100 starts of `cmd /c exit 0`: `CreateProcessW` 7 ms, `ProcessBuilder` 16 ms. |

**Check 1** (run 260): a probe in a console of its own (`CREATE_NEW_CONSOLE`, with the standard handles of that console) starts
seven `ping -t`, lists the processes of its console (`GetConsoleProcessList`), then sends `GenerateConsoleCtrlEvent(CTRL_C_EVENT, 0)`.

| Child | Started as | On the console | After Ctrl+C |
|---|---|---|---|
| A | `ProcessBuilder`, stdin `NUL`, output and errors discarded | **no** | alive |
| A2 | `ProcessBuilder`, stdin `NUL`, output and errors inherited (**PowerJ at a terminal**) | yes | **dead, `0xC000013A`** |
| A3 | `ProcessBuilder`, `inheritIO()` | yes | **dead, `0xC000013A`** |
| B | `CreateProcessW`, `CREATE_NEW_PROCESS_GROUP`, `NUL` | yes | **alive** |
| B2 | `CreateProcessW`, `CREATE_NEW_PROCESS_GROUP`, the handles of the console | yes | **alive** |
| C | `CreateProcessW`, no flag, `NUL` (control) | yes | dead, `0xC000013A` |
| C2 | `CreateProcessW`, no flag, the handles of the console (control) | yes | dead, `0xC000013A` |

### What the results change in the plan

1. **The premise of the issue holds** for what PowerJ does at a terminal (A2): a console program started with `&` is on the console
   and dies of its Ctrl+C. The new process group removes it, with the same inherited handles (B2). Option (c) is not justified on Windows.
2. **A nuance the issue did not have**: when *all three* streams are redirected (`cmd > f 2> g &`, `2>&1`, or a script without a terminal), the JDK
   starts the child **off the console of its parent** (A is not in the console's process list), so it does not receive Ctrl+C. The defect is
   therefore limited to a job whose output or errors go to the terminal, the usual case of `ping -t localhost &`. The mechanism is the JDK's, not measured here.
3. **The Windows implementation is smaller than feared and quicker than `ProcessBuilder`** (7 ms against 16 ms), with no handle leak.
   It brings two differences with the foreground path to decide: the arguments are quoted correctly (so an argument with a `"` differs from
   what `ProcessBuilder` sends), and `.bat`/`.cmd` stay on `ProcessBuilder`.
4. **Not measured**: macOS; a Windows GUI program (FR-39 already starts those detached); `Ctrl+Break` (it still reaches a process of a
   new group, by design); PowerJ started by a parent without a console; the `.exe` launcher of the image.
5. **Phase 3 gate, Windows side: go.** With the Linux side (all checks pass, one behaviour change to accept: a job that reads the terminal or,
   with `stty tostop`, writes to it, is stopped), the data supports both (a) and (b). (a) changes nothing on Unix, where `sh` is proven;
   (b) unifies the two platforms at the price of the Unix behaviour changes and of an unverified macOS.

## Decision — option (b), and what was built

**(b) FFM on both platforms, `sh` dropped** was chosen after the measures above: the defect of the issue is real on Windows (a
console program started as PowerJ starts a background command at a terminal dies of the console's Ctrl+C), the Linux prototype passes
every check, and one mechanism for every system is simpler to explain than two. The work stays in this one pull request (#129),
against the issue's "one platform per pull request", by the owner's choice.

Built (in `io.powerj.core.nativeexec` of `powerj-core`, except the last two rows):

| Class | Role |
|---|---|
| `BackgroundLauncher` | The public entry: `supportedSystem()`, `available()`, `handles(stages)`, `shFallbackAllowed(...)`, `start(...)`. Chooses `PosixJobs` or `WindowsJobs`. |
| `PosixSpawn`, `PosixJobs` | Linux and macOS: `posix_spawn`, one group per job (the first stage leads), pipes, file actions, a `waitpid` thread per process. Stages are reaped only once all are started. |
| `WindowsSpawn`, `WindowsJobs`, `WindowsCommandLine` | Windows: `CreateProcessW` + `CREATE_NEW_PROCESS_GROUP`, the handle list, pipes, the quoted command line, the Unicode environment block, a wait on the process handle. |
| `SpawnedProcess` | A `Process` for the jobs: `Jobs`, `PipelineExecutor.background` and `Kill` did not change. |
| `NativeRunner.startBackground` | Delegates to `BackgroundLauncher`; keeps the `ProcessBuilder` path for a Windows batch file and for the `sh` fallback; refuses the command when the fallback is not allowed (`NativeRunner.refusal`). `environment().clear()` still appears once, in `newBuilder`. |
| `io.powerj.fs.ProcessSignals` (`powerj-fs`) | `SIGCONT` through `kill(2)` by FFM, without starting a program: `Kill` sends it after `SIGTERM`, so that a job stopped by the terminal ends. The launcher enables native access for `io.powerj.fs`. |
| `io.powerj.fs.Host` (`powerj-fs`) | `Host.linux()` next to `windows()` and `mac()`: `Host` is the one place that reads `os.name`. |

Choices, and why:

1. **The `sh` wrapper stays as a fallback only**, for a Unix system that cannot use `posix_spawn` that way (glibc older than 2.34, musl),
   **or that is not supported at all** (anything but Linux, macOS and Windows: FreeBSD, OpenBSD, Solaris, AIX…), and **only when the session
   variable `POWERJ_BACKGROUND_SH_FALLBACK` is `true`** (the owner's rule). Without it the command is refused with a message that names the
   variable, and the message tells the two causes apart: `background.unavailable` (a supported system whose libc lacks a function) and
   `background.unsupported` (a system that is not in the list). So `sh` is no longer on the normal path, but it is not gone from the code.
   **A system is not simply tried**: the constants (`O_CREAT`, `O_TRUNC`, `O_APPEND`, the `POSIX_SPAWN_*` flags, `SIGCONT`) and the way the other
   descriptors are kept out of the child are written per system, and a wrong one does not fail, it opens the wrong file or sends another
   signal; none of this has been run on FreeBSD and the CI has no FreeBSD. Which systems, how to check one and how to add it:
   `docs/ARCHITECTURE.md`, section 11.
2. **A Windows batch file is started by `ProcessBuilder`**, without protection: `cmd.exe` reads its command line by other rules, and the JDK has
   checks for them (CVE-2024-24576) that `CreateProcessW` would have to copy.
3. **The "Done when" of the issue is amended**: "on Unix the existing background tests pass unchanged" cannot hold. `InterpreterTest.backgroundCommandGivesControlBackAtOnce`
   sent `kill -INT <pid>` to the job and expected it to survive, which measured the *disposition* set by `trap ''`. A process group ignores
   nothing: it is out of the reach of the terminal's signal. The test now checks that the job leads a group of its own, and
   `PosixSpawnTest.check02` that a `SIGINT` sent to the foreground group does not reach it.
4. **Behaviour that changes on Unix** (a shell with job control does the same): a job that reads the terminal, or writes to it after
   `stty tostop`, is stopped (`kill` continues it so that it ends); `kill -INT <pid>` kills a job; and **a signal sent to the process group of the
   shell no longer reaches the jobs**: a closed terminal (`SIGHUP`) or a CI harness that aborts a script by its group leaves them running,
   as with `nohup` (found by the review of #129, point 1; documented in FR-75).
5. **Windows arguments are quoted correctly** for a background command: they differ from what `ProcessBuilder` sends for an argument that holds a `"`.
6. **An executable text file without a `#!` line no longer starts in the background.** `posix_spawn` does not run it (`Exec format error`), where
   the former `sh` wrapper, and `ProcessBuilder` for a foreground command, ran it as a shell script. Decided in the review of #129: a script has
   to be given to its interpreter (`sh script &`), and no code is added to guess one. It is in the changelog and in the known limitations of
   `docs/acceptance/background.md`.

Not measured, to look at on a real machine: macOS (the CI runs the Unix tests there), a Windows GUI program in the background, `Ctrl+Break`,
PowerJ started by a parent without a console, a PowerJ built on musl and the `sh` fallback on a real Alpine, FreeBSD and the other systems
(refused on purpose, see choice 1).
