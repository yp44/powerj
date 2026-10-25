# Compiler lint baseline

`-Xlint:all` is on for every module (parent `pom.xml`, `maven-compiler-plugin`) and reported as **warnings**. This file
records the state when it was switched on (review finding C-32, Phase 0.4), so that the count can only go down.
The target is zero warnings, then `-Werror`.

Reproduce: `./mvnw clean test-compile 2>&1 | grep 'WARNING.*\.java'`.

## State on 2026-10-25 (0.2.0-SNAPSHOT, JDK 25.0.5): 72 warnings

| Module | main | test |
|---|---|---|
| `powerj-cmdlets` | 34 | 7 |
| `powerj-core` | 19 | 3 |
| `powerj-shell` | 7 | 2 |
| other modules | 0 | 0 |

| Warning | Count | Cause | Removed by |
|---|---|---|---|
| Public class in an exported package "declares no explicit constructors" (`[missing-explicit-ctor]`) | 35 | `cmdlets` and `core.exec` export every package in full | Phase 3.1 (qualified exports) |
| "found raw type" / unchecked call (`[rawtypes]`, `[unchecked]`) | 11 | `Collected` used raw in `Collect`; AssertJ generics in tests | Phase 1.8 |
| Serializable class without `serialVersionUID` / non-serializable field (`[serial]`) | 6 | `PjException` and friends | Phase 3.2 (`PjError`/`PjException` move) |
| Restricted method (`[restricted]`) | 7 | FFM calls in `StandardInput`, `WindowsConsole`, `ModuleLoader` | needs `--enable-native-access` in the launcher and tests; Phase 4 |
| "not indirectly exported using `requires transitive`" (`[exports]`) | 5 | `core` and `shell` expose JLine / API types | Phase 3.1 |
| Dangling Javadoc (`[dangling-doc-comments]`) | 3 | comments before `package`/blocks | Phase 1.8 |
| Deprecated JLine API (`[deprecation]`) | 3 | `Terminal.getWidth()`, `Size(int,int)` | Phase 4.3 |
| Non-varargs call of varargs method (`FileOpsTest`) | 2 | test code | Phase 1.8 |

81 of the 86 "not indirectly exported" warnings that existed before are gone: `powerj-cmdlets` now declares
`requires transitive io.powerj.api`, which it has to, since its public types expose API types.
