# Compiler lint baseline

`-Xlint:all` is on for every module (parent `pom.xml`, `maven-compiler-plugin`) and reported as **warnings**. This file
records the state when it was switched on (review finding C-32, Phase 0.4), so that the count can only go down.
The target is zero warnings, then `-Werror`.

Reproduce: `./mvnw clean test-compile 2>&1 | grep 'WARNING.*\.java'`.

## State on 2026-10-25, after Phase 3.1 (0.2.0-SNAPSHOT, JDK 25.0.5): 37 warnings

(72 when `-Xlint:all` was switched on, in Phase 0.4.)

| Module | main | test |
|---|---|---|
| `powerj-cmdlets` | 5 | 7 |
| `powerj-core` | 15 | 5 |
| `powerj-shell` | 2 | 2 |
| other modules | 0 | 0 |

| Warning | Count | Cause | Removed by |
|---|---|---|---|
| "found raw type" / unchecked call (`[rawtypes]`, `[unchecked]`) | 10 | `Collected` used raw in `Collect`; AssertJ generics in tests | `Collect.Received` (open question of the PR), tests |
| Restricted method (`[restricted]`) | 7 | FFM calls in `StandardInput`, `WindowsConsole`, `ModuleLoader` | needs `--enable-native-access` in the launcher and tests; Phase 4 |
| Serializable class without `serialVersionUID` / non-serializable field (`[serial]`) | 7 | `PjException`, `SyntaxException`, `PreviewGuard.Denied`, `PreviewMode.PreviewFull`, `JsonException`, `Unreadable` | Phase 3.2 (`CancelledException`, `PjException` rework) |
| `try` on an auto-closeable that `close()` may throw `InterruptedException` from (`[try]`) | 5 | `CmdletRunner.Context` and its test doubles (Phase 1.3) | Phase 3.4 |
| Dangling Javadoc (`[dangling-doc-comments]`) | 3 | comments before blocks | Phase 3.3 (`Completions`, `Members`) |
| Deprecated JLine API (`[deprecation]`) | 3 | `Terminal.getWidth()`, `Size(int,int)` | Phase 4.3 |
| Non-varargs call of varargs method (`FileOpsTest`) | 2 | test code | with the next change to that test |

Removed since the first count: the 35 "public class in an exported package declares no explicit constructors"
(`[missing-explicit-ctor]`) went with the qualified exports of Phase 3.1 — `core` exports its packages to the shell only,
`cmdlets` to the shell only, and `shell` exports nothing —, and the "not indirectly exported" ones with them.
