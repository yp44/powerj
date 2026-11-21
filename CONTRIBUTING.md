# Contributing to PowerJ

Thank you for looking at PowerJ. This page says how to build and check a change, what the build refuses, and the few
habits the project relies on. For what the code is made of, read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) first.
Security issues go through [SECURITY.md](SECURITY.md), not through a public issue.

## Build and test

You need **JDK 25 or newer**. Maven is not needed: the `mvnw` wrapper downloads (and checks) Maven 3.10.1.

```bash
./mvnw verify                          # compile, tests, every gate below
./mvnw -pl powerj-cmdlets -am test     # one module and what it needs: the fast loop while you work
./mvnw -Pdist verify                   # + the application image (jlink runtime, jpackage launcher) and its .sha256
./mvnw -DskipFx verify                 # without the optional graphical module (a platform that has no JavaFX)
./mvnw -Psbom -DskipTests package      # + target/bom.json, the CycloneDX bill of materials
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
| Surefire channel (`ci/check-surefire-channel.sh`) | no test writes to the native stream of the forked JVM (Surefire's "Corrupted channel" warning leaves a `.dumpstream` file) | redirect the output of the child process that is not under test, as `ConsoleInputTest` does |
| `-Xlint:all` with `-Werror` | a javac warning fails the build, in the sources and in the tests; there is none left ([docs/quality/lint-baseline.md](docs/quality/lint-baseline.md)) | fix the warning; a suppression (`@SuppressWarnings`) says why in a comment; on a JDK newer than the CI's that adds a lint category, `-Dpowerj.failOnWarning=false` |

`./mvnw -Pquality verify` adds Error Prone (warnings only for now, baseline in `docs/quality/`). `./mvnw -Pbench -DskipTests package`
builds the JMH benchmarks, which are not part of `verify` ([how to run them](docs/quality/performance.md)).
`xvfb-run -a ./mvnw -Pfx-ui -pl powerj-fx -am test` runs the tests of the graphical window, which need a display (a virtual one
is enough); the ordinary build leaves them out. `./mvnw -Pmutation … org.pitest:pitest-maven:mutationCoverage` measures what the tests of the core would notice
([how to run it and read the survivors](docs/quality/mutation.md)); run it before and after a change to the lexer, the
parser or the interpreter.

**ArchUnit.** The rules are plain JUnit tests in `powerj-architecture/src/test/java`. A violation that existed when a rule
was introduced is *frozen* in `src/test/resources/archunit_store`: it may disappear, a new one fails the build. Never
refreeze to make a build pass. Each rule says in its Javadoc which finding of the
[architecture review](docs/REVUE-ARCHITECTURE.md) it enforces.

**API snapshot.** `powerj-api` is what third parties compile against. A change to it is deliberate: run
`./mvnw -pl powerj-api test -Dpowerj.api.update=true`, read the diff of
`powerj-api/src/test/resources/io/powerj/api/api-surface.txt`, and describe the change in [CHANGELOG.md](CHANGELOG.md).
**Nothing has been published yet, so the API may still be broken**: a removed or changed member goes under "Breaking changes
of the API", without a deprecation first. From the first release on, the rule is to add and never to remove, unless the
member is deprecated first with `@Deprecated(since = …, forRemoval = true)` for one minor version.

## The backlog

**What is still to do lives in [GitHub issues](https://github.com/yp44/powerj/issues)**, not in a file of the
repository: one issue per gap or decision, each with a *Context*, a *To do*, a *Done when* and its *References*. Look
there before starting something, and open an issue (or comment on the existing one) rather than adding a `TODO`
to the code or a line to a document.

- A new issue starts from one of the templates in [.github/ISSUE_TEMPLATE/](.github/ISSUE_TEMPLATE/): *Bug report*
  (a form: the line, the output, `powerj --version`, the system), *Feature or change*, *Decision needed* (options and a
  recommendation, for what waits for the maintainer) and *Task or technical debt*. The last three have the shape of the
  existing issues: Context, To do or Proposal or Options, Done when, References. A security problem is not an issue: see
  [SECURITY.md](SECURITY.md).
- Labels say what kind of work it is (`decision` and `maintainer-action` need the maintainer; `release`, `security`,
  `ci`, `fx`, `cmdlet`, `spec`, `tech-debt`, `performance`, `quality`, `accessibility`, `epic`, `v2`…).
- Work on an issue in a pull request that says `Refs #nn`; the one that finishes it says `Fixes #nn` (see
  [Branches and pull requests](#branches-and-pull-requests)).
- When an issue is done or dropped, close it with the reason. Do not delete the text: it is the record.

What goes where:

| Where | What it holds |
|---|---|
| GitHub issues | work that is **not done yet** and the decisions waiting for someone |
| [CHANGELOG.md](CHANGELOG.md) | what **changed** in each version |
| [docs/adr/](docs/adr/) | decisions taken, with the reason |
| [docs/SPECIFICATION.md](docs/SPECIFICATION.md) | what the product is required to do (`FR-nn`) |
| [docs/REVUE-ARCHITECTURE.md](docs/REVUE-ARCHITECTURE.md), [docs/AUDIT-v2-2026-11-14.md](docs/AUDIT-v2-2026-11-14.md), [docs/plans/](docs/plans/) | the reviews and the plans of past iterations: history, not a to-do list (the state of a finding is that of its issue) |

## Branches and pull requests

- **Every change goes through a branch and a pull request**, a feature as well as a fix: nothing is pushed directly to
  `main`. Name the branch after what it does (`fix/123-glob-escape`, `feat/45-sort-object`), one subject per branch.
- The pull request links its issue: `Refs #nn` while it is a step, `Fixes #nn` when it finishes the issue. It is merged
  only once the CI is green.
- **A pull request is merged with "Squash and merge"**: it becomes one commit on `main`. Rewrite the message of that
  commit before confirming, do not keep the list of the branch's commits: a subject `type(scope): subject (#nn)` with
  the number of the issue, and a body that says what changed and *why*, ending with `Fixes #nn` or `Refs #nn`. For
  example:

  ```text
  fix(fs): escape brackets in Get-ChildItem patterns (#123)

  A path holding `[` was read as a character class, so Get-ChildItem
  missed files whose name contains brackets. Literal paths are now
  escaped before the glob is compiled.

  Fixes #123
  ```

## Dependency updates

Dependabot proposes them every week ([.github/dependabot.yml](.github/dependabot.yml)): the Maven plugins come in one pull
request, the test libraries in one, the minor and patch updates of the GitHub Actions in one; a major version of an action,
JLine and JavaFX, which ship in the application, come alone.

- **A Dependabot pull request is merged, or closed with its reason, within two weeks**, and the queue never holds more than
  three of them: a month of waiting turns a patch into a jump of two major versions (audit v2, B-02). The maintainer merges,
  or says who may.
- **Before merging**: the CI is green on the three systems. For a **runtime dependency** (JLine, JavaFX, the version of the
  JDK of the image) the CI already runs `./mvnw -Pdist verify` and the smoke tests of `ci/smoke/` on the image; read the
  release notes of the library for what a test cannot see.
- **A red update** is not a verdict on the update: check first whether a test depends on the layout of the test runtime
  (#143) or at random (a job that passes on a re-run of the same commit is a test to fix, not a dependency to refuse). The
  same job failing twice is a real break: fix the code in its own pull request, or close the update with the reason.
- **What is not wanted is closed with the reason** (for example a major version of JavaFX while the module stays on 25), and
  `@dependabot ignore this major version` keeps it from coming back.

## Releasing

[docs/RELEASING.md](docs/RELEASING.md): how a version is cut, what the release workflow builds, and what is not done yet
(signing).

## How we work

- **One step per commit** on a branch, and every commit builds and passes the tests. The pull request is squashed when
  it is merged, so the message of the squashed commit is what the history of `main` keeps: make it explicit.
- **A defect gets a failing test first.** Commit it red and `@Disabled` with the reason if the fix comes later; the
  fix removes the `@Disabled`.
- **Break your own test.** After writing a test, change the code on purpose (invert a condition, drop a call) and check
  that the test fails. A test that cannot fail is worth nothing; several tests of this project were corrected this way.
- **Commit messages** follow `type(scope): subject`, in the imperative, and say *why* in the body. The type is one of
  `feat`, `fix`, `refactor`, `docs`, `test`, `ci`, `build`, `perf` (faster, same behavior), `chore` (housekeeping that
  changes neither the product nor its build) and `style` (formatting only); `!` after the type or the scope marks a breaking
  change (`feat(api)!: …`). The scope is optional and goes **in parentheses**, in lower case: the module or the cmdlet
  (`fix(fx): …`, `feat(select): …`, `refactor(api,cmdlets): …`), never in place of the type (`select: …`). The CI checks the
  subject of every commit of a pull request (`ci/check-commit-subjects.sh origin/main HEAD` runs it before you push); a
  merge or a revert written by git passes as it is. Reference the issue (`#nn`, always in the squashed commit), and the
  requirement (`FR-70`) or the review finding (`C-13`) when there is one.
- **Requirements are traced.** Behavior comes from a requirement `FR-nn` of [the specification](docs/SPECIFICATION.md);
  cite it in the Javadoc of the class that implements it and in the test that checks it.
- **A test does not assert what the machine happens to deliver**: no number of milliseconds, no count of profiler samples.
  Wait for a state, or check the rule with a given time. [docs/quality/clock-dependent-tests.md](docs/quality/clock-dependent-tests.md)
  lists the tests that read the real clock and the margin each one relies on.
- **Compare what a user writes exactly.** The shell is case-sensitive, as Java is ([ADR 0012](docs/adr/0012-the-shell-is-case-sensitive.md)):
  no `equalsIgnoreCase`, `compareToIgnoreCase` or `Pattern.CASE_INSENSITIVE` for a name, a value or a unit. The few exceptions
  (the system's own rules, an option that asks to ignore case) are in the list of `CaseSensitivityTest`, each with its reason.
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

**The style of the messages** (the bundles of `core.i18n`, `core.lang`, and the new ones; audit v2, C-04): quote what the user
typed with double quotes (`"{0}"`), not with `'…'` or bare; say **program** for a native command and **built-in command** for
one of the shell; give a position as `(position N)`, counted in characters, at the end of the sentence; say what to do next
when there is something to do (`… use new java.math.BigInteger("…")`); one wording for one failure (`cannot convert {0} to {1}`).
The few short tags that are not sentences (`[internal]` in the completion menu) are the exception. Both languages follow the same
rule.

Two documents are deliberately French: [docs/REVUE-ARCHITECTURE.md](docs/REVUE-ARCHITECTURE.md), the architecture review
and its refactoring plan, and [docs/AUDIT-v2-2026-11-14.md](docs/AUDIT-v2-2026-11-14.md), the audit that followed it; both
are kept in the language in which they were commissioned. The issues they gave rise to are in English, like the rest.

## Adding a cmdlet

1. Plan it in `docs/plans/<name>.md` (the user-visible behavior, the options, the decisions and open questions) and add
   its requirement `FR-nn` to the specification in the same commit.
2. Write the class in `powerj-cmdlets/src/main/java/io/powerj/cmdlets/`: `@CmdletInfo(name, category, summary,
   examples, preview)`, `implements Cmdlet<Params, Input, Output>`, a nested `record Params` with `@Option`, output
   records with `@Display`. A short option (`shortName`) follows the table of
   [FR-18b](docs/SPECIFICATION.md#34-command-naming-and-resolution): `-r` recurse, `-n` dry-run, `-f` force, `-v` verbose,
   `-p` parallel, `-i` ignore case, `-a` all (hidden too), `-l` lines; a reserved letter is never given another meaning
   (`CmdletOptionLettersTest` checks it), and an option that is rare or takes a block has no short form.
3. **State of one execution** goes through `context.state(Type.class, Type::new)`, never through a field of the cmdlet or a
   map: the same instance runs in several pipelines at once. A state that implements `AutoCloseable` is closed however
   the run ends (end of input, error, Ctrl+C); `end` is for what the cmdlet emits once its input is over.
4. **Errors:** throw `UsageException` for a wrong usage (the runner turns it into a message), `context.error(…)` for an
   object that cannot be processed. Evaluate a user block with `block.tryInvoke(…)`: it reports an evaluation error
   as a value and lets cancellation through. In a long loop, check `Thread.interrupted()`.
5. Register it in `BuiltinCmdlets` (the ArchUnit rule `cmdletsAreDeclared` fails if you forget).
6. **Texts:** `<name>.summary`, `<name>.option.<longName>` and the error keys, in both bundles. `help` shows the bundle;
   the text of `@CmdletInfo(summary)` and `@Option(description)` is the same as the English one (`HelpTextsTest` checks it).
   A failure about a file reuses the `files.*` keys (`files.not.found`, `files.access.denied`…) rather than a copy under
   the name of the cmdlet, and a walk error goes through `FileArguments.report`.
7. `preview = true` only if the cmdlet has no side effect; otherwise implement `Previewable` and refuse the options that
   change something.
8. Tests in `powerj-cmdlets/src/test/java/…/<Name>Test.java` with `TestContext` and `CmdletDriver.run(cmdlet, params, context, inputs…)` (module `powerj-testing`: begin, process for each object, end, as the shell does) and `@TempDir`.
9. Docs: the cmdlets table of the README, the acceptance sheet `docs/acceptance/<name>.md` (format of `grep.md`), the
   specification, [CHANGELOG.md](CHANGELOG.md).

A cmdlet written outside the repository follows the same contract: see "Writing your own cmdlet" in the
[README](README.md#writing-your-own-cmdlet) and the example module [`examples/greet`](examples/greet/README.md).

## Acceptance sheets

Each feature has a manual checklist in [`docs/acceptance/`](docs/acceptance/): the commands to type and what to see. They
complete the automated tests, they do not replace them. Update the sheet when the behavior changes.
