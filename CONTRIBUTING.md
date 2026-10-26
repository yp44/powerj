# Contributing to PowerJ

Thank you for looking at PowerJ. This page says how to build and check a change, what the build refuses, and the few
habits the project relies on. For what the code is made of, read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) first.
Security issues go through [SECURITY.md](SECURITY.md), not through a public issue.

## Build and test

You need **JDK 25 or newer**. Maven is not needed: the `mvnw` wrapper downloads (and checks) Maven 3.9.11.

```bash
./mvnw verify                          # compile, tests, every gate below
./mvnw -pl powerj-cmdlets -am test     # one module and what it needs: the fast loop while you work
./mvnw -Pdist verify                   # + the application image (jlink runtime, jpackage launcher)
bash ci/smoke/launcher.sh              # then any script of ci/smoke/ against that image
./mvnw spotless:apply                  # fixes the formatting that `verify` would refuse
```

Use `-pl <module> -am`, never `-pl <module>` alone: the other modules are then taken from your local repository, which
may hold an older build. The smoke tests of `ci/smoke/` exercise what unit tests cannot: the module system of the
packaged launcher. `ci/smoke/fx.sh` needs a display (Xvfb is enough).

## The gates of `./mvnw verify`

| Gate | What it protects | If it fails |
|---|---|---|
| Tests (JUnit 5, AssertJ) | behavior; the default timeout of a test is 60 s | fix the code or the test, never skip the test |
| JaCoCo | 80 % of lines and branches overall, and a per-module floor that only goes up | add the missing tests; do not lower a floor |
| Spotless | formatting (`.editorconfig` states the conventions) | `./mvnw spotless:apply` |
| Maven Enforcer | JDK and Maven versions, dependency convergence, pinned plugins | pin the version |
| ArchUnit (`powerj-architecture`) | dependencies between modules and packages, no cycle, only `Main` ends the JVM, every cmdlet declared and registered | see below |
| API snapshot (`powerj-api`) | the public surface of the extension API does not change by accident | see below |
| `-Xlint:all` | warnings are reported but not yet fatal; the count is in [docs/quality/lint-baseline.md](docs/quality/lint-baseline.md) and must not rise | fix the warning |

`./mvnw -Pquality verify` adds Error Prone (warnings only for now, baseline in `docs/quality/`).

**ArchUnit.** The rules are plain JUnit tests in `powerj-architecture/src/test/java`. A violation that existed when a rule
was introduced is *frozen* in `src/test/resources/archunit_store`: it may disappear, a new one fails the build. Never
refreeze to make a build pass. Each rule says in its Javadoc which finding of the
[architecture review](docs/REVUE-ARCHITECTURE.md) it enforces.

**API snapshot.** `powerj-api` is what third parties compile against. A change to it is deliberate: run
`./mvnw -pl powerj-api test -Dpowerj.api.update=true`, read the diff of
`powerj-api/src/test/resources/io/powerj/api/api-surface.txt`, and describe the change in [CHANGELOG.md](CHANGELOG.md)
(add, never remove, unless you deprecate first with `@Deprecated(since = …, forRemoval = true)`).

## How we work

- **One step per commit**, and every commit builds and passes the tests. The history is read, not squashed.
- **A defect gets a failing test first.** Commit it red and `@Disabled` with the reason if the fix comes later; the
  fix removes the `@Disabled`.
- **Break your own test.** After writing a test, change the code on purpose (invert a condition, drop a call) and check
  that the test fails. A test that cannot fail is worth nothing; several tests of this project were corrected this way.
- **Commit messages** follow `type(scope): subject` (`feat`, `fix`, `refactor`, `docs`, `test`, `ci`, `build`), in the
  imperative, and say *why* in the body. Reference the requirement (`FR-70`) or the review finding (`C-13`) when there is
  one.
- **Requirements are traced.** Behavior comes from a requirement `FR-nn` of [the specification](docs/SPECIFICATION.md);
  cite it in the Javadoc of the class that implements it and in the test that checks it.
- **No new dependency without agreement.** The only runtime dependency is JLine; adding another is a decision to record
  in [docs/adr/](docs/adr/).
- **Do not lower a gate to get a green build**: no `@Disabled` without a reason and a way back, no baseline raised, no
  frozen rule added.

## Language

The repository is in **English**: code, comments, Javadoc, commit messages, pull requests, CI, build files,
documentation, thread names, log messages.

The **user interface** is English and French. Every text shown to the user goes through a resource bundle
(`messages_en.properties` and `messages_fr.properties` of the module); the two files must hold the same keys
(`MessageBundlesTest` in `powerj-shell` checks it for every module), and `NoHardcodedFrenchTest` rejects French text in
the sources. Tests force
English so that they do not depend on the machine. French strings used as *test data* (the expected translation of a
message) are fine.

One document is deliberately French: [docs/REVUE-ARCHITECTURE.md](docs/REVUE-ARCHITECTURE.md), the architecture review
and its refactoring plan, kept in the language in which it was commissioned.

## Adding a cmdlet

1. Plan it in `docs/plans/<name>.md` (the user-visible behavior, the options, the decisions and open questions) and add
   its requirement `FR-nn` to the specification in the same commit.
2. Write the class in `powerj-cmdlets/src/main/java/io/powerj/cmdlets/`: `@CmdletInfo(name, category, summary,
   examples, preview)`, `implements Cmdlet<Params, Input, Output>`, a nested `record Params` with `@Option`, output
   records with `@Display`.
3. **State of one execution** goes through `context.state(Type.class, Type::new)`, never through a field of the cmdlet or a
   map: the same instance runs in several pipelines at once. A state that implements `AutoCloseable` is closed however
   the run ends (end of input, error, Ctrl+C); `end` is for what the cmdlet emits once its input is over.
4. **Errors:** throw `UsageException` for a wrong usage (the runner turns it into a message), `context.error(…)` for an
   object that cannot be processed. Evaluate a user block with `block.tryInvoke(…)`: it reports an evaluation error
   as a value and lets cancellation through. In a long loop, check `Thread.interrupted()`.
5. Register it in `BuiltinCmdlets` (the ArchUnit rule `cmdletsAreDeclared` fails if you forget).
6. **Texts:** `<name>.summary`, `<name>.option.<longName>` and the error keys, in both bundles.
7. `preview = true` only if the cmdlet has no side effect; otherwise implement `Previewable` and refuse the options that
   change something.
8. Tests in `powerj-cmdlets/src/test/java/…/<Name>Test.java` with `TestContext` and `@TempDir`.
9. Docs: the cmdlets table of the README, the acceptance sheet `docs/acceptance/<name>.md` (format of `grep.md`), the
   specification, [CHANGELOG.md](CHANGELOG.md).

A cmdlet written outside the repository follows the same contract: see "Writing your own cmdlet" in the
[README](README.md#writing-your-own-cmdlet) and the example module [`examples/greet`](examples/greet/README.md).

## Acceptance sheets

Each feature has a manual checklist in [`docs/acceptance/`](docs/acceptance/): the commands to type and what to see. They
complete the automated tests, they do not replace them. Update the sheet when the behavior changes.
