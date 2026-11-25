# Mutation testing

Coverage says which lines the tests *run*; it does not say whether they would notice if the line were wrong. A mutation
test changes the code one operator at a time (`<` into `<=`, a call removed, a returned value replaced by `null`) and runs
the tests: a change that no test notices is a **survivor**, and points at an assertion that is missing (review C-32,
practice 12 of §7). [PIT](https://pitest.org) does it on the bytecode of `powerj-core`, packages `lang` (lexer, parser,
scanning) and `runtime` (interpreter, pipelines, native commands, session), and of `powerj-fs` (walks, removal and copy of
trees, globs: [below](#the-file-system-powerj-fs)).

It is not part of `./mvnw verify`: a run takes about 12 minutes on four threads when nothing else runs, and a number that moves with the load of
the machine is a poor gate. It is a measure to take before and after a change to those packages, and the list of
survivors is a to-do list for tests.

## Running it

```bash
./mvnw -Pmutation -pl powerj-core -am test-compile org.pitest:pitest-maven:mutationCoverage
./mvnw -Pmutation -pl powerj-core -am test-compile org.pitest:pitest-maven:mutationCoverage \
    -DtargetClasses='io.powerj.core.lang.Scanning'          # one class: a few minutes, most of them for the build
```

The report is `powerj-core/target/pit-reports/index.html` (and `mutations.xml`). Open a class, read the red lines: each says
which mutation survived. The profile pins PIT 1.30.0 and its JUnit 5 plugin 1.2.3, runs the tests with
`-Dpowerj.language=en` as surefire does, and does not fail when the modules below the core (which `-am` also visits) have
nothing to mutate.

Do not run another build at the same time: PIT stops a mutant that makes a test hang after a timeout, and a loaded machine
makes honest tests time out too.

## Scores

Measured on the code of the commit that introduced this page (one run, 4 threads).

| Package | Mutations | Killed | Score | Killed by a timeout |
|---|---|---|---|---|
| `io.powerj.core.lang` | 782 | 704 | 90.0 % | 23 |
| `io.powerj.core.runtime` | 844 | 714 | 84.6 % | 23 |
| **Both** | **1 626** | **1 418** | **87.2 %** | 46 |

The goal set by the review was 60 %. It is met, and it was met before any test was added: the first run gave 89 % for
`lang` and 84 % for `runtime`. A mutant "killed by a timeout" made a test loop or wait for ever; PIT counts it as killed,
but a test that hangs is the weakest way to notice a defect, and these 46 are the first to look at if a score ever has to
be trusted to the decimal.

The classes with the lowest score (survivors and mutants that no test reaches):

| Class | Mutations | Score | What survives |
|---|---|---|---|
| `StandardInput` | 11 | 27 % | `isTerminal` and the probe of the Windows console: they need a real console |
| `NativeRunner` | 130 | 65 % | the termination of a process tree (`destroyTree`), process groups, background start, the redirection and charset plumbing |
| `Supervisor` | 10 | 70 % | restoring the memory reserve after an `OutOfMemoryError`; the cancellation branch of `failureOf`, which no test reaches |
| `Scanning` | 66 | 73 % | bounds of the scan of a character literal (`characterEnd`) |
| `Interpreter` | 48 | 73 % | `registry()` and `resolve()` accessors, `hadBlockingError`, the reset of the error reporter |
| `PipelineExecutor` | 141 | 82 % | `joinAll`, the start of background jobs |

`CompiledBlock` (24 mutations, 79 %) and `CmdletRunner$Context` (25, 80 %) follow; `ShellIo` (1 mutation) and one anonymous
class of `LineMeasuring` (2) are too small to read. Every other class is at 86 % or more.

## What the first run found

Two gaps that coverage had hidden, both now closed by a test that fails when the code is mutated back:

- **`Scanning.characterEnd`**: the scan that skips `'x'`, `'\n'` and `')'` when looking for the end of a group had no
  test on its limits. `ScanningTest` now pins the end of a character literal for each of the three forms, a quote that opens
  no literal (`it's`), and a literal cut by the end of the text. The class went from 58 % to 73 %.
- **The environment of a native command**: `NativeRunner` clears the environment of the child process and copies the one
  of the session into it, and no test would have noticed if it had not: the child would have inherited the environment of
  the JVM, so a variable that the user removed from the session (`env --unset`) would still have reached it, and so would
  any variable of the JVM that the session does not have. `InterpreterTest` now runs `printenv` for a variable the session
  added, for one it replaced, and for one it does not have.

## The file system (`powerj-fs`)

Added for #96, before the parallel engine of `FileWalker` and `FileTrees` was shared: the score had to be known before the
code moved, and compared after. The module has its own `mutation` profile (`powerj-fs/pom.xml`), which replaces the targets
of the root one with `io.powerj.fs.*`; the build, the timeouts and the threads are those of the root profile.

```bash
./mvnw -Pmutation -pl powerj-fs test-compile org.pitest:pitest-maven:mutationCoverage
```

About four minutes on four threads; the report is `powerj-fs/target/pit-reports/index.html`. Measured as root in a
container: four tests of `FileTreesTest` that rely on permissions being enforced are skipped there, so a run as an
ordinary user may score a little higher, never lower.

| Code | Mutations | Killed | Score | Killed by a timeout |
|---|---|---|---|---|
| `io.powerj.fs`, first run (`f5090e1`, tests of the time) | 414 | 309 | 74.6 % | 28 |
| `io.powerj.fs`, with the tests of #96, before the change | 414 | 347 | 83.8 % | 28 |
| of which `FileTrees` | 153 | 134 | 87.6 % | 17 |
| of which `FileWalker` | 104 | 94 | 90.4 % | 8 |
| `io.powerj.fs`, after the change (two runs) | 399 | 335 / 336 | 84.0 % / 84.2 % | 20 |
| of which `FileTrees` + `FileWalker` + `ParallelEngine` | 242 | 216 / 217 | 89.3 % / 89.7 % | 17 |

Before the change, two runs gave the same numbers to the mutant; the same code scored 88.7 % (228 of 257) in `FileTrees`
and `FileWalker`. After it there are fewer mutations (the stop protocol is mutated once instead of twice) and the score is
not lower. The one mutant that differs between the two runs after is `copied` called on a folder before its last child is
done: it is killed when a child is copied after the folder got its date, which depends on the order in which the threads
end; it was killed in both runs before, and in one of the two after. What the first run found, and the tests of #96 now cover, for every user:

- **No test bounded the parallelism.** Removing the `acquire` of a permit, or releasing a permit twice, survived in both
  classes: the Javadoc promises at most `parallelism` open folders or files, and nothing checked it. `ParallelismBoundTest`
  slows every listing, removal, copy and creation down (`FaultyFileSystem`) and checks that exactly `parallelism` of them
  ran at the same time, for a walk, a removal and a copy.
- **The attributes of a copied folder.** Removing the call that gives the copy of a folder the dates and permissions of its
  source survived, in both modes: the only test of it is the read-only tree, which is skipped for root.
  `FileTreesTest.eachCopiedFolderGetsTheDateAndThePermissionsOfItsSource` checks it for every user.
- **Errors in the workers.** A folder that cannot be opened, an entry that cannot be removed, copied or created were
  tested only through permissions, again skipped for root. `FileTreesFaultTest` injects them, and checks that the parent is
  still finished (removed, or given its attributes) once its other children are done.

What survives in the parallel code is the path of a work already stopped: the permit given back when the executor refuses
a task, the interrupt flag set again in a worker that is ending, and `ParallelEngine.stopped()` answering `false` (the work
then goes on until its end instead of stopping early, and the result is the same). No test can observe them.

## Reading a survivor

Most of what remains is of three kinds. Learn to tell them apart before adding a test:

1. **Equivalent mutants**: the change cannot be observed. `open + 2 < text.length()` into `<=` in `characterEnd` throws
   when the text ends there, which a test of the end of the text sees; the same mutation of a check that a previous line
   already made does not change any result. Do not write a test for these; if one keeps coming back, restructure the code so
   that the redundant check disappears.
2. **Arithmetic on an index that is checked elsewhere** (`at + 1` into `at - 1` in a loop that a bound stops anyway): the
   lexer and the parser hold most of these. A test with the right input kills them, but ask whether the input is one a user
   would type.
3. **Real gaps**: a call removed (`destroyTree`, `restoreMemoryReserve`), a returned value replaced, a negated condition on
   a branch of the error path. These are the ones to write tests for. Start with the methods that have several survivors:
   the table above is sorted by that.

## Limits

- PIT mutates the bytecode that the tests of `core` and of `fs` reach. `cmdlets` and `fx` are not in the scope: the review asked for
  `lang` and `runtime`, and `FilterParser` of the grid is a candidate for the next run.
- A score of 100 % would prove nothing about code that no test describes; the specification-traceability test and the
  conformance corpora ([JSONTestSuite](../../powerj-cmdlets/src/test/resources/jsontestsuite/README.md), RFC 4180) are what
  say that the *right* behavior is pinned.
- There is no threshold in the build. When the scores are stable over a few runs, a floor per package can be checked from a
  scheduled job rather than from the pull request job.
