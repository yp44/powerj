# Compiler lint baseline

`-Xlint:all` is on for every module (parent `pom.xml`, `maven-compiler-plugin`) and reported as **warnings**: they do not
fail the build, because 23 remain (target: zero, then `-Werror`). What keeps the number from creeping up is a check:
the CI counts the warnings of its clean build and fails when a module has more than
[`javac-warnings.txt`](javac-warnings.txt) allows (issue #84; the count had gone from 29 to 30 unnoticed, with the
preview work, while this file said it could only go down).

- **The allowed number** is in [`javac-warnings.txt`](javac-warnings.txt), by module and by source set (`main`, `test`).
  A line only goes down: when you fix a warning, lower its line; the script tells you when a count is below its line.
- **The check** is `ci/check-javac-warnings.sh <log of a clean build>`, run by the Linux job after `./mvnw -Pdist verify`.
- **Reproduce it** on your machine (the build must be clean, or javac prints nothing for what it does not recompile):

  ```bash
  ./mvnw -B clean verify 2>&1 | tee build.log
  ci/check-javac-warnings.sh build.log
  ```

  Only the warnings of the Linux build are counted: a warning that only another system prints is not seen.
- **Turning a category into an error** (`-Werror`) is possible only for all categories at once (javac has no switch for
  one), so it waits for zero. A category at zero is kept at zero by the check.

## State on 2026-11-01 (0.2.0-SNAPSHOT, JDK 25.0.4): 23 warnings

(30 at the start of #84, 29 on 2026-10-26, 72 when `-Xlint:all` was switched on, in Phase 0.4. The seven `[serial]` ones were
removed in #84: `serialVersionUID` on the exceptions, and `PjException` no longer holds a `PjError`, which is not
serializable, but rebuilds it from its message and its cause.)

| Module | main | test |
|---|---|---|
| `powerj-cmdlets` | 3 | 7 |
| `powerj-core` | 8 | 5 |
| `powerj-shell` | 0 | 0 |
| other modules | 0 | 0 |

| Warning | Count | Cause | Removed by |
|---|---|---|---|
| "found raw type" / unchecked call (`[rawtypes]`, `[unchecked]`) | 10 | `Collected` used raw in `Collect`; AssertJ generics in tests | `Collect.Received` (open question of the PR), tests |
| Restricted method (`[restricted]`) | 7 | FFM calls in `StandardInput`, `WindowsConsole`, `ModuleLoader` | needs `--enable-native-access` in the launcher and tests; Phase 4. `PosixSpawn` and `WindowsSpawn` (#128) make many more FFM calls and carry `@SuppressWarnings("restricted")`, so that the count above does not move |
| Serializable class without `serialVersionUID` / non-serializable field (`[serial]`) | 0 | (was 7: `PjException` twice, `PreviewGuard.Denied`, `PreviewMode.PreviewFull`, `StageLimit.Full`, `JsonException`, `Unreadable`) | done in #84 |
| `try` on an auto-closeable that `close()` may throw `InterruptedException` from (`[try]`) | 4 | `CmdletRunner.Context` and its test doubles (Phase 1.3) | Phase 3.4 |
| Non-varargs call of varargs method (`FileOpsTest`) | 2 | test code | with the next change to that test |

Removed since the first count:

- the 7 `[serial]` ones (#84), see above;
- the 3 uses of deprecated JLine API (`Terminal.getWidth()` twice in `InteractiveShell`, `new Size(int, int)` in
  `TestTerminal`): `getWidth()` only called `getColumns()`, and `Size.of(int, int)` is the factory that replaces the
  constructor;
- the 35 "public class in an exported package declares no explicit constructors" (`[missing-explicit-ctor]`) went with
  the qualified exports of Phase 3.1 — `core` exports its packages to the shell only, `cmdlets` to the shell only, and
  `shell` exports nothing —, and the "not indirectly exported" ones with them;
- the 3 dangling Javadoc comments (`Completions`, `Members`, `NoHardcodedFrenchTest`) in Phase 3.3.
