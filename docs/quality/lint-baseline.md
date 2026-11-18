# Compiler lint

`-Xlint:all` is on for every module (parent `pom.xml`, `maven-compiler-plugin`) and **a warning fails the build**
(`-Werror`, the `failOnWarning` option, issue #146): the sources and the tests of every module compile with no warning, and
the CI compiles a fresh checkout, so nothing is tolerated and nothing is counted.

- **Fixing a warning** is the rule. When a suppression is right, it is as narrow as it can be (a method, not a module) and
  says why in a comment. Those that exist are of two kinds:
  - `@SuppressWarnings("restricted")` on the methods that make the FFM calls (`PosixSpawn`, `WindowsSpawn`,
    `ProcessSignals`, `StandardInput`, `WindowsConsole`, `ModuleLoader.define`): the calls are the point of the method, and
    the consent for native code is the one of `NativeAccessPolicy` and of the launcher.
  - `@SuppressWarnings("try")` on a resource whose `close()` rethrows the failure of what it closes, an
    `InterruptedException` included, which the caller lets through (`CmdletRunner.Context`, and the states of
    `CmdletRunnerTest`).
- **On a JDK newer than the one of the CI** a new lint category can print a warning that nobody has fixed yet:
  `./mvnw -Dpowerj.failOnWarning=false verify` builds anyway, and the warning is a bug to report.
- **The `quality` profile** (Error Prone, `./mvnw -Pquality verify`) reports its findings as warnings and does not fail the
  build ([errorprone-baseline.md](errorprone-baseline.md)).
- **Seeing the warnings**: a build that compiles nothing prints nothing, so `./mvnw clean verify` is the way to look at them.

## History

72 warnings when `-Xlint:all` was switched on (Phase 0.4 of the architecture review), 30 when the check of #84 was written
(the count had gone from 29 to 30 unnoticed), 23 on 2026-11-01, **0 and `-Werror` with #146** (audit v2, B-05). Until then
`docs/quality/javac-warnings.txt` and `ci/check-javac-warnings.sh` held the allowed number of each module; they are gone.
