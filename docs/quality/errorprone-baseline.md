# Error Prone baseline

Error Prone runs with `./mvnw -Pquality verify` (profile `quality` of the parent `pom.xml`, plus `.mvn/jvm.config`,
which opens the `jdk.compiler` packages Error Prone needs). Every check is reported as a **warning**
(`-XepAllErrorsAsWarnings`): the build never fails on it yet. This file records the state when it was introduced
(review finding C-32, Phase 0.4), so that the number can only go down; the target is zero, then `-Werror` in the profile,
then the profile on by default.

Reproduce: `./mvnw -Pquality clean test-compile 2>&1 | grep '\[WARNING\].*\.java'`.

## State on 2026-10-25 (0.2.0-SNAPSHOT, JDK 25.0.5, Error Prone 2.51.0): 220 warnings

| Module | main | test |
|---|---|---|
| `powerj-core` | 76 | 30 |
| `powerj-cmdlets` | 46 | 13 |
| `powerj-fx` | 12 | 22 |
| `powerj-shell` | 16 | 3 |
| `powerj-fs` | 1 | 0 |
| `powerj-api` | 0 | 2 |

Most frequent checks: `SameNameButDifferent` 22, `ReferenceEquality` 22, `OperatorPrecedence` 14, `StringSplitter` 13,
`MissingSummary` 12, `NonAtomicVolatileUpdate` 8, `BooleanLiteral` 6, then 25 checks with fewer than 5 each.

## The ones that mean something

| Check | Where | Why it matters |
|---|---|---|
| `NonAtomicVolatileUpdate` (8) | `Pipe.java:95,99,131,135`, `PerfRecorder.java:93,97`, `StageProbe.java:115,120` | `volatile long` counters updated with `+=`. Correct only because each counter has a single writer, a rule that is written in a comment and not enforced (review C-17). Fixed by `MeasuredPipe` with `LongAdder` (Phase 2.5). |
| `EnumOrdinal` (4) | `Perf.java:80`, `PerfTest.java:107` | `ordinal()` used as a value (review minor finding). |
| `IdentityHashMapUsage` (1) | `RowSelection.java:24` | An `IdentityHashMap` used as a `Map`: intentional if documented. |
| `LongDoubleConversion` (1) | `FxScheduler.java:14` | Precision loss converting a duration. |
| `InfiniteRecursion` | `SupervisorTest.recurse` | Deliberate (needs a `StackOverflowError`); suppressed with a comment. |

`SystemConsoleNull` (2, `StandardInput.java:29`, `Main.java:183`) is a false alarm: both already ask `console.isTerminal()`.
