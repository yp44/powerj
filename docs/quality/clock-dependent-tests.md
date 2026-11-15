# Tests that read the real clock

A test that reads the real clock (`System.nanoTime`, `Instant.now`, `Thread.sleep`, the `Waits` helper, a `get(n, SECONDS)`)
passes or fails depending on how busy the machine is. This page lists them, with the margin each one relies on, so that the
next flaky one is found by reading this file rather than by a red `main` (audit v2, findings S-01 and S-09, issue #142).
It was made by searching the test sources of every module for `awaitUntil`, `Waits.until`, `System.nanoTime`,
`currentTimeMillis`, `Instant.now`, `Thread.sleep` and timed `get(…)`, then reading each hit.

## How to read the margins

| Kind | What the test needs from the clock | When it can fail | What to do |
|---|---|---|---|
| **Wait for a state** | A condition becomes true within a limit. The limit is only a way to fail instead of hanging. | Only when the condition never comes true, or a runner stalls for the whole limit. | Nothing: this is the pattern to use. Keep the limit large (5 to 60 s). |
| **Time passes on purpose** | Something must *not* happen while a short time passes ("it did not die"). | Never as a flake: a late event makes the test pass wrongly, not fail. | Keep it short and say why in a comment. |
| **Upper bound on elapsed time** | An operation took less than a limit. | When the machine is slower than the limit. | Keep the limit far above what the operation takes; do not tighten it. |
| **Lower bound on elapsed time** | A stage that sleeps or spins took at least what it was told. | Never: a loaded machine only makes it longer. | Fine. |
| **Reads the clock, does not depend on it** | A date or a start time is taken from the clock; no assertion on how long. | Only if the wall clock steps back during the test. | Fine. |

An **assertion on a number measured with the real clock or the real profiler** (a duration, a number of samples) that is
not one of the last two kinds is what failed in S-01: do not add one. Check the rule where it is decided, with a given
time (`StageProbe.measured`, the `beginNanos` of `PerfRecorder`, the fake profiler of `PerfModeTest`), as
`ExecutionObserverTest` and `PerfModeTest` do.

## The tests

### powerj-core

| Class | Reads the clock for | Kind | Margin |
|---|---|---|---|
| `support/Waits` | the helper of most waits below | Wait for a state | 10 s |
| `perf/PerfTest` | `Thread.sleep` in the `numbers` and `slow` cmdlets, a spin of `work -c` | Lower bound; the real profiler | No assertion on a duration or a count of samples since #142. Needs at least one sample of the profiler in 150 ms of spin (a run on macOS gave 5 at worst) and, for the allocation limit, more events without the limit than with it (1 against 1627 measured). |
| `perf/PerfModeTest` | `System.nanoTime` given to `PerfMode.start`, a spin of 1 ms in the fake profiler | Reads the clock, does not depend on it | None: the assertion compares two readings of a monotonic clock taken in order. |
| `perf/ExecutionObserverTest` | `System.nanoTime` given to `PerfRecorder`; `Waits.until` for a reader to block | Reads the clock; wait for a state | 10 s; the times asserted are the given ones. |
| `runtime/StatementOutputTest` | `System.nanoTime` given to `PerfRecorder` | Reads the clock, does not depend on it | None. |
| `runtime/PipelineTest` | `Waits.until` for a producer to run, stop, hold and close a resource | Wait for a state | 10 s, with `@Timeout(20)`. |
| `runtime/InterpreterTest` | a poll with `Thread.sleep(20)` up to 10 s; "`sleep 30 &` gives control back" in less than 5 s | Wait for a state; **upper bound** | 5 s for an operation that returns at once. |
| `runtime/BackgroundJobsTest` | a poll up to 10 s; `Thread.sleep(300)` to see that a `sleep` survives a SIGINT | Wait for a state; time passes on purpose | 10 s. The 300 ms can only produce a false pass. |
| `runtime/BackgroundJobsWindowsTest` | a poll up to 20 s; "`ping -t &` gives control back" in less than 5 s | Wait for a state; **upper bound** | 5 s for an operation that returns at once (Windows only). |
| `runtime/JobsTest` | `Instant.now()` against the start time of a job | Reads the clock | None, unless the wall clock steps back. |
| `nativeexec/PosixSpawnTest` | `get(10 s … 120 s)` on exits; `Thread.sleep(300)` to see that a background process survives a SIGINT; the duration of the 1000-spawn stress test is only logged | Wait for a state; time passes on purpose | 10 s per exit, 120 s for the stress. |
| `nativeexec/WindowsSpawnTest` | `get(30 s … 120 s)` on exits; the stress test duration is only logged | Wait for a state | 30 s per exit (Windows only). |
| `nativeexec/PosixJobsTest` | `Waits.until` for a process to be gone | Wait for a state | 10 s. |
| `nativeexec/ChildFds` (helper) | rereads the list of descriptors of a child every 5 ms for at most 5 s | Wait for a state | 5 s for a window of a few hundred microseconds. |
| `java/JavaClassesConcurrencyTest` | runs importing threads for 1.5 s, then joins within 10 s | Time passes on purpose | 10 s to stop; a short run only tests less. |
| `java/JavaInteropTest` | `Waits.until` for a callback | Wait for a state | 10 s. |

### powerj-fs

| Class | Reads the clock for | Kind | Margin |
|---|---|---|---|
| `ProcessSignalsTest` | a poll of `ps` with `Thread.sleep(20)` up to 10 s | Wait for a state | 10 s. |

### powerj-fx

| Class | Reads the clock for | Kind | Margin |
|---|---|---|---|
| `Threads` (helper) | waits up to 10 s for the toolkit thread | Wait for a state | 10 s. |
| `view/GridViewUiTest` | polls the toolkit thread every 25 ms up to 10 s (the filters wait 200 ms after the last key) | Wait for a state | 10 s, with `@Timeout(60)`. Opt-in profile `fx-ui`. |
| `model/GridScaleTest` | reading, sorting, filtering and searching 100 000 rows, each **under 15 s** | **Upper bound** | 15 s for operations that take of the order of a second (the name of the test), with `@Timeout(120)`. The one place where a slow runner could fail a test on speed alone: do not lower the 15 s. |
| `model/FilterParserTest` | `Instant.now()` given to a date filter | Reads the clock | None. |

### powerj-shell

| Class | Reads the clock for | Kind | Margin |
|---|---|---|---|
| `ReplTest` | its own `awaitUntil`: a poll every 10 ms up to 20 s | Wait for a state | 20 s, with `@Timeout(30)`. |
| `PerfModeTest` | the same `awaitUntil` | Wait for a state | 20 s, with `@Timeout(60)`. |
| `PreviewModeTest` | the same `awaitUntil` | Wait for a state | 20 s, with `@Timeout(60)`. |
| `PreviewControlKeysTest` | the same `awaitUntil`; a stalled source that sleeps 10 minutes so that the 200 ms and 400 ms deadlines of the preview expire | Wait for a state; time passes on purpose | 20 s. The deadline values are settings of the preview that the test reads back, not durations it measures. |

`awaitUntil` is copied in four classes of `powerj-shell` (`ReplTest`, `PerfModeTest`, `PreviewModeTest`,
`PreviewControlKeysTest`) next to `Waits.until` of `powerj-core`: one shared helper would be a change for #95, which
covers the sleeps of the tests.

## Hunting a flaky test

A test that fails once in twenty runs is not seen by one run. The macOS job of the CI can repeat the suite of
`powerj-core` after its ordinary build (`workflow_dispatch` only, the other runs are not changed):

```bash
gh workflow run ci.yml --ref <branch> -f repeat-core-tests=20
```

The loop stops at the first failure and says which extra run it was. A change to a test of this page is done when 20
extra runs pass. On a Linux machine, the same loop with the cores kept busy (one `while :; do :; done` per core) is a cheap way to
get a loaded runner.

## Keeping this page true

- A new test that reads the clock goes in the table of its module, with its kind and its margin, in the same pull request.
- A test that fails on a loaded runner and passes on a re-run is a defect of the test (CONTRIBUTING: "fix the code or the
  test, never skip the test"): find its row here, decide which kind it is, and change it to a *wait for a state* or to a
  check with a given time.
- Rewriting the sleeps of the tests as waits for a state is the subject of #95, not of this page.
