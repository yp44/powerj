# PowerJ architecture

What PowerJ is made of, how the parts depend on each other, and which rule keeps them that way. The
[specification](SPECIFICATION.md) says *what* the shell must do; the [README](../README.md) says how to use it; the
[architecture review](REVUE-ARCHITECTURE.md) (in French) is the history of how the structure got here and what is left to do;
[docs/adr/](adr/) records the decisions and their reasons. This page describes the code as it is.

## 1. Context

```mermaid
flowchart LR
    user([User at a terminal]) -->|keys, lines| powerj[PowerJ]
    powerj -->|objects, text, colors| user
    powerj -->|starts, pipes| native[Native programs<br/>git, sh, cmd...]
    powerj -->|reads, writes| fs[(File system<br/>~/.powerj: config, profile, history, logs)]
    jars[Third-party module jars<br/>~/.powerj/modules] -->|ServiceLoader, one layer each| powerj
    powerj -->|reflection| jre[The Java API of the JRE]
    powerj -.->|optional module| fx[JavaFX window: grid]
```

PowerJ is one JVM process that runs with the rights of the user. It starts the native programs the user types, reads and
writes the user's files, loads the jars the user dropped in `~/.powerj/modules`, and exposes the JRE's classes to the
language. Nothing in it is a security boundary against that user or those jars: see [SECURITY.md](../SECURITY.md).

## 2. Modules

Every module is a JPMS module (`module-info.java`); the only runtime dependency of the product is JLine.

```mermaid
flowchart BT
    api[powerj-api<br/>extension API]
    fs[powerj-fs<br/>file tree walks]
    core[powerj-core<br/>language, engine]
    cmdlets[powerj-cmdlets<br/>built-in cmdlets]
    fx[powerj-fx<br/>grid, optional]
    shell[powerj-shell<br/>Main, REPL]
    core --> api
    cmdlets --> api
    cmdlets --> fs
    fx --> api
    shell --> core
    shell --> jline[(JLine)]
    fx --> javafx[(JavaFX)]
```

| Module | Depends on | Is |
|---|---|---|
| `powerj-api` | the JDK | The contract of a cmdlet (`Cmdlet`, `CmdletContext`, `@CmdletInfo`, `@Option`, `CmdletProvider`), the values it exchanges (`Row`, `Collected`, `Span`…), the capability interfaces (`Previewable`, `ColumnProvider`) and `io.powerj.api.perf`. The only module a third party compiles against. |
| `powerj-fs` | the JDK | `FileWalker` and `FileTrees`: sequential or parallel walks, copy and delete of trees. |
| `powerj-core` | `api` | The language (`lang`), the engine (`runtime`, `command`, `java`, `nativeexec`, `format`), the completion (`assist`), the profiler (`perf`). Exported to the shell only. |
| `powerj-cmdlets` | `api`, `fs` | The built-in cmdlets, published through `provides CmdletProvider with BuiltinCmdlets`. **Never depends on `core`**, so a built-in cmdlet behaves exactly like a third-party one. |
| `powerj-fx` | `api`, JavaFX | `grid`. A real third-party module that happens to live in the repository; not in the distribution, and left out of the build with `-DskipFx`. |
| `powerj-shell` | `core`, JLine | `Main`, the composition root, the REPL, scripts, the profile, history. It does not require `cmdlets`: the built-in cmdlets are a provider found by the service loader, and the image build (`--add-modules` in `powerj-dist`) puts the module in the runtime. |
| `powerj-architecture`, `powerj-coverage` | all | Build only: the architecture rules, and the aggregated coverage report with its thresholds. |
| `powerj-bench` | `api`, `core`, `cmdlets` | JMH benchmarks, outside the default build (`-Pbench`): [docs/quality/performance.md](quality/performance.md). |
| `powerj-dist` | all but `fx` | jlink runtime and jpackage launcher. |
| `examples/greet` | `api` | An example third-party module. Not shipped. |

## 3. The core, by layer

`powerj-core` is ten packages. A package may use the ones below it, never the ones above; the rule is a test.

```text
 Plugged in   assist (completion, highlighting, help for the editor)    perf (JFR profiler, perf reports)
                  │ both plug into runtime through interfaces: CommandCatalog, ExecutionObserver, LineMeasuring
 Runtime      runtime   Interpreter (a facade), PipelineExecutor, Pipe, Supervisor, Session, Evaluator, Jobs…
 Command      command   CmdletRegistry, OptionBinder, ModuleLoader, NativeAccessPolicy, Help
 Services     format (OutputFormatter, ValueUnroller)   java (JavaInvoker, CallPolicy, PreviewPolicy…)   nativeexec
 Base         lang (Lexer, Parser, Ast: only the JDK and the API)    error    i18n
```

- **`lang`** knows nothing of the rest: a line becomes an `Ast` (a sealed hierarchy of records) and nothing more.
- **`runtime`** walks the `Ast`. `Interpreter` only orchestrates; it resolves a command through `CommandResolution` (one
  rule, a sealed `Resolved`: built-in, cmdlet, native, unknown), runs it through `PipelineExecutor`, and sends output
  through `StatementOutput` and `Redirections`.
- **`assist`** reads the world through `CommandCatalog`, never through the concrete `Interpreter`, and runs nothing:
  completion types expressions by signature and never calls a getter on a real value.
- **`perf`** is switched on by `PerfMode`; the interpreter only knows an `ExecutionObserver` (`NONE` by default), so the
  execution code has no `if (perf != null)`.

## 4. How a line runs

1. **Reading.** The shell's `Repl` reads a line with a `LineEditor` (JLine behind `InteractiveShell`); `ShellParser` and `InputCompleteness`
   decide whether the line is complete.
2. **Parsing.** `Lexer` → `Parser` (with `ExpressionParser` for Java-syntax expressions) → `Ast`. `ParseContext` carries what
   the grammar must know from outside (names of Java classes in scope, commands that take the short comparison form).
3. **Resolution.** For the first word of each command: built-in, then cmdlet, then executable on the `PATH`
   (`^name` forces native). `OptionBinder` builds the cmdlet's `Params` record from the arguments.
4. **Execution.** `PipelineExecutor` starts one virtual thread per stage, joined by bounded `Pipe`s; the last stage runs
   in the calling thread. A cmdlet gets `begin`, `process` for each object, `end`; the state of one run lives in
   `context.state(…)`, closed however the run ends. Consecutive native commands are chained with
   `ProcessBuilder.startPipeline`.
5. **Supervision.** `Supervisor` runs the whole line, turns any `Throwable` into an `Outcome` (sealed: `Success`,
   `Failure`, `Cancelled`, `Abandoned`), and handles Ctrl+C by interrupting; a second Ctrl+C abandons a command that does not
   stop. Cancellation is the interruption of the thread plus `CancellationException`; the stages stop in cascade.
6. **Output.** `OutputFormatter` prints tables, lists and text; `ValueUnroller` is the one place that decides which
   values are unrolled into several objects.

The **session** is what a line may read or change: `Session` is a facade over `SessionState` (current, previous and
configuration folders, home, environment), `Variables`, `ShellStatistics` (last result, recent errors, perf reports),
`JavaClasses` (imports) and `Jobs`. It is written by the command thread and read by stages, jobs, completion and
preview, so its fields are `volatile` or concurrent collections.

## 5. Modes: preview and perf

`ShellMode` (`NORMAL`, `PREVIEW`, `PERF`) is held by one `ModeController` in the shell, which switches modes and tells
who shows them. **Preview** runs a line while it is typed, and only through what `PreviewPolicy` allows (an ordered
list of `PreviewRule`s: the first that has an opinion decides, no opinion means refused) and `PreviewGuard`. **Perf**
measures each line with counters, and with JFR when the runtime has it.

## 6. The shell: composition root

`Main` is one call: `System.exit(ShellBootstrap.system().execute(args))`. `ShellBootstrap` reads the arguments, resolves the
home folder, installs the log, loads `ShellConfig`, picks the language, builds one `Session`, and starts an
`InteractiveRun` or a `ScriptRun`. The process is read in three places only, `Environment` (variables, properties,
streams), `SystemTerminals` and `Main`; everything else is given what it needs, so a shell starts in a test with a folder and a few
streams. There is no dependency-injection framework (see ADR 0007).

## 7. Extension points

| Point | How | Where |
|---|---|---|
| A cmdlet or a set of cmdlets | implement `CmdletProvider`, `provides … with …` in `module-info`, or a jar in `~/.powerj/modules` | `powerj-api`, loaded by `ModuleLoader` into its own `ModuleLayer` |
| What a cmdlet can do beyond `begin/process/end` | implement a capability interface: `Previewable`, `ColumnProvider`, `AutoCloseable` state | `powerj-api` |
| What a cmdlet can ask of the shell | `context.service(PerfReports.class)`, `context.property("a.b")`, `context.setEnvironment(…)` | `CmdletContext` |
| A profiler | a `Profiler` service of `powerj-core` (JFR is the one provided) | `core.perf` |

The API only grows: a member is deprecated with `@Deprecated(forRemoval = true)` for a release before it goes, and
`ApiSurfaceTest` fails when the public surface changes without its snapshot being regenerated.

## 8. `powerj-fx`

`model` (table, filter, selection; it may use `javafx.base` but not the classes of the user interface), `view` (the controls), `runtime` (the thread of the toolkit) and `grid` (the cmdlet, its
options, the window opener). What touches the toolkit is behind ports (`FxPlatform`, `Scheduler`, `WindowOpener`,
`PropertyReader`), so the model is tested without a display.

## 9. The rules, and where they are checked

| Rule | Test (`powerj-architecture`) |
|---|---|
| `api` and `fs` use only the JDK | `apiDependsOnlyOnTheJdk`, `fsDependsOnlyOnTheJdk` |
| built-in cmdlets do not know the engine | `cmdletsAreIndependentOfTheEngine` |
| `fx` uses only the API; its model does not use the toolkit's UI | `fxDependsOnlyOnTheApi`, `fxModelDoesNotUseTheToolkitUi` |
| the core does not know its clients; the shell does not know `fx` | `coreDoesNotKnowItsClients`, `shellDoesNotKnowTheOptionalModule` |
| no cycle between modules, none between core packages | `modulesHaveNoCycle`, `corePackagesHaveNoCycle` |
| the layers of the core | `corePackagesRespectTheLayers`, `langDependsOnlyOnTheJdkAndTheApi` |
| completion does not use the concrete interpreter | `assistDoesNotUseTheConcreteInterpreter` |
| the process is read in three places; the read loop does not know the editor; the shell does not use the built-in cmdlets | `shellReadsTheProcessInThreePlacesOnly`, `theReadLoopDoesNotKnowTheConcreteLineEditor`, `shellDoesNotUseTheBuiltInCmdlets` |
| no mutable static field (one exception frozen: `Language.current`); only `Main` ends the JVM | `staticFieldsAreFinal`, `onlyMainEndsTheJvm` |
| every cmdlet is declared and registered | `cmdletsAreDeclared` |

## 10. Where to look

| You want to… | Start with |
|---|---|
| change what a line means | `core.lang` (`Parser`, `ExpressionParser`), then `core.runtime.Evaluator` |
| change how a command is found | `core.runtime.CommandResolution` |
| change how a pipeline runs or stops | `PipelineExecutor`, `Pipe`, `Supervisor`, `CmdletRunner` |
| change the options of a cmdlet | its `Params` record and `@Option`; `core.command.OptionBinder` |
| call Java from a line | `core.java.JavaInvoker`, `CallPolicy`, `FunctionalAdapter` |
| change Tab completion or colors | `core.assist` (a `…Completer` per kind), `ShellCompletion` in the shell |
| change the prompt, history, profile | `PromptRenderer`, `Repl`, `ProfileRunner` in the shell |
| load a third-party module | `core.command.ModuleLoader`, `NativeAccessPolicy`, `shell.NativeAccessConsent` |
| start-up and options | `shell.ShellBootstrap` |
