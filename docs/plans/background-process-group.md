# Plan — background commands in their own process group, with the FFM API (issue #128)

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
