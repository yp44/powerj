# PowerJ

[![CI](https://github.com/yp44/powerj/actions/workflows/ci.yml/badge.svg)](https://github.com/yp44/powerj/actions/workflows/ci.yml)
[![License: Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
![Java 27](https://img.shields.io/badge/Java-27-orange.svg)

**An interactive, object-oriented shell written in Java 27.** Commands return **Java objects** instead of text, you filter them on their properties in a pipeline, native programs work as in any shell, and **the whole Java API of the JRE** is available right at the prompt.

```text
PJ ~/dev/powerj> ls -r --filter *.java | where { $_.size > 10kb }

name               size      modified              dir
----               ----      --------              ---
Parser.java        14.2 KB   2026-10-05 18:12      false
Evaluator.java     11.8 KB   2026-10-06 09:40      false

PJ ~/dev/powerj> grep "TODO|FIXME" src -r -p 16 | map { m -> m.path.fileName + ":" + m.line }
Parser.java:118
Evaluator.java:57
Evaluator.java:203
PJ ~/dev/powerj> git status --porcelain | where { $_.startsWith(" M ") }
 M src/core/Parser.java
PJ ~/dev/powerj> java.util.List.of("apple", "banana", "orange") | where { $_.contains("b") }
banana
PJ ~/dev/powerj> LocalDate.now().plusDays(10).dayOfWeek
FRIDAY
PJ ~/dev/powerj> help members (cat -n pom.xml).get(0)

name        kind      type
----        ----      ----
path        property  Path
line        property  int
length      property  int
text        property  String
length()    method    int
line()      method    int
path()      method    Path
text()      method    String
toString()  method    String
```

## Contents

- [What is PowerJ?](#what-is-powerj)
- [Philosophy](#philosophy)
- [Features at a glance](#features-at-a-glance)
- [Getting started](#getting-started)
- [Configuration](#configuration)
- [How it works](#how-it-works)
- [Writing your own cmdlet](#writing-your-own-cmdlet)
- [Tests](#tests)
- [Status and roadmap](#status-and-roadmap)
- [Documentation](#documentation)
- [License](#license)

## What is PowerJ?

PowerJ is a command shell in the spirit of PowerShell, built for people who live in a Unix-style terminal and know Java:

- its built-in commands, the **cmdlets** (`ls`, `grep`, `cat`, `where`, `map`…), produce typed Java objects — usually `record`s such as `FileEntry(name, size, modified, path, dir, ext)` — that flow through the pipeline;
- the expressions you write in `{ … }` blocks, in arguments or at the start of a line **are Java**: `$_.size > 10kb`, `f -> f.name.endsWith(".md")`, `String::toUpperCase`, `Math.max(3, 7)`, `new StringBuilder()`;
- any other word is looked up on the `PATH` and run as a **native command** (`git`, `mvn`, `docker`…), whose output becomes a stream of `String` lines that cmdlets can filter.

It runs on **Linux, macOS and Windows**: the build produces a self-contained launcher with its own Java runtime for each platform.

## Philosophy

- **Objects, not text.** A cmdlet emits records; you select on properties (`where { $_.size > 1mb }`) instead of cutting columns with `awk`. Displaying is the last step: tables are formatted only when the objects reach the terminal.
- **Short names, Unix options.** PowerShell's ideas without `Get-ChildItem -Recurse`: each cmdlet has one short lowercase name, and options follow the Unix convention (`-r`, `-ra`, `--recurse`, `--rec`, `--filter=*.java`, `--`).
- **Native commands are first-class.** `git`, `mvn` or `notepad` run exactly as in a classic shell; their stdout/stderr stay streams; cmdlets and native commands mix freely in a pipeline. `^ls` always runs the native program when a cmdlet has the same name.
- **Java is the expression language.** No new DSL to learn: Java syntax, operators, lambdas and method references, plus direct access to every public class of the `java.*` modules.
- **The shell never crashes.** Every line runs under a supervisor; Ctrl+C cancels the running command (a second Ctrl+C abandons a stuck computation) without leaving the shell; errors are short readable messages (`$debug = true` shows the Java stack trace).
- **Streaming with backpressure.** Pipeline stages are connected by bounded queues: `ls -r /` piped into a slow consumer does not fill memory.
- **Extensible by dropping a jar.** A third-party module is a plain jar implementing a small API; it gets option parsing, completion, help and table display for free.
- **An exemplary modern Java codebase.** Records, sealed interfaces, pattern matching, virtual threads, scoped values, the FFM API — used where they bring a concrete benefit ([§6 of the specification](docs/SPECIFICATION.md#6-use-of-modern-java-features)).

Out of scope for v1: control structures (`if`, `foreach`, functions — planned for v2), syntax compatibility with bash or PowerShell, remoting.

## Features at a glance

### Cmdlets

| Cmdlet | What it does | Acceptance checklist |
|---|---|---|
| `ls` | Lists files as `FileEntry` records (`-r` recursive, `-a` hidden, `--filter *.java`, `-d` dirs, `-p 16` parallel walk) | [etape-3](docs/recettes/etape-3.md) |
| `grep` | Java regex search in files and folders, or in the pipeline objects; `GrepMatch` records with highlighted matches and capture groups | [grep](docs/recettes/grep.md) |
| `cat` | Reads files as lines (`-l`), one text (`-t`), numbered `CatLine` records (`-n`) or a `CatContent` (`-c`) | [cat](docs/recettes/cat.md) |
| `where` | Filters objects: `where { $_.size > 1mb }`, `where { f -> f.dir }`, short form `where name == usr` | [etape-4](docs/recettes/etape-4.md) |
| `map` | Transforms objects: `map { f -> f.name }`, `map FileEntry::name` | [etape-5b](docs/recettes/etape-5b.md) |
| `sort` | Sorts objects by keys: `sort size --desc`, `sort dir name`, `sort { $_.name.length() }`, `-i` ignores case | [sort](docs/recettes/sort.md) |
| `first`, `last` | The first / last objects: `ls -r \| first 5` stops the walk as soon as it has them | [first-last-range](docs/recettes/first-last-range.md) |
| `range` | Objects between two bounds: positions (`range --from 5 --to 10`), regular expressions on a property (`range --from Paul --to Peter --property name`) or blocks, `--exclude`, `--repeat` | [first-last-range](docs/recettes/first-last-range.md) |
| `select` | Chooses and computes columns: `select name size`, `select file=name kb={ $_.size / 1kb }`; the `Row`s are tables and their columns stay properties (`\| sort kb -d`) | [select](docs/recettes/select.md) |
| `count`, `measure` | Count the objects (`ls -r \| count`); count, sum, average, min and max of a property (`ls -r \| measure size`, sizes in readable form) | [count-measure](docs/recettes/count-measure.md) |
| `group` | Groups by key: `ls -r \| group ext \| sort count -d \| first 5`; one `Group(key, count, items)` per key, several keys allowed, `-n` keeps only the counts | [group](docs/recettes/group.md) |
| `mkdir`, `touch`, `cp`, `mv`, `rm` | File operations, also from the pipeline (`ls --filter *.tmp \| rm -v`); `-n` dry run, `-f` to overwrite, `-r` for folders, `-p <n>` to copy or remove a tree in virtual threads (`cp`, `rm`); `rm` and `mv` refuse the protected paths listed in `~/.powerj/protected-paths` | [fileops](docs/recettes/fileops.md) |
| `to-json`, `from-json`, `to-csv`, `from-csv` | JSON and CSV: `ls \| select name size \| to-json > files.json`, `cat files.json \| from-json \| where { $_.size > 1kb }`, `cat data.csv \| from-csv -d ";"`; always an array, JSON Lines with `-l`, CSV values as strings, objects as `Row`s | [json-csv](docs/recettes/json-csv.md) |
| `ps`, `kill` | Processes as objects: `ps java \| sort cpu --desc \| first 3`, `ps notepad \| kill -n`, then `ps notepad \| kill -w`; `-f` forcible stop, `-v` shows what was stopped | [ps-kill](docs/recettes/ps-kill.md) |
| `collect` | Gathers a pipeline into one list: `(ls -r \| collect).size()` | [etape-8](docs/recettes/etape-8-collect.md) |
| `env` | Reads and sets environment variables (`env --set MAVEN_OPTS=-Xmx2g`) | [etape-3](docs/recettes/etape-3.md) |

Built-in commands: `cd` (`cd -`, `cd ~`, `cd D:`), `pwd`, `which`, `help` (`help ls`, `help java.util.List`, `help members $x`), `history` (plus `!!`, `!n`, `!text`), `import`, `mod-load`, `mod-list`, `jobs`, `exit [code]`.

### Java at the prompt

```text
PJ> Math.max(3, 7)                                   # static methods and fields (Math.PI)
PJ> $sb = new StringBuilder("ab"); $sb.reverse()     # constructors, instance methods
PJ> $l = List.of("b", "a", "ccc")
PJ> $l.stream().filter(s -> s.length() > 1).toList()  # lambdas → functional interfaces
PJ> $l | map String::toUpperCase                     # method references
PJ> (ls)*.name                                       # *. applies .name to each element
PJ> [long] 5 ; import java.security.*                # casts, imports (java.util, java.io, java.nio.file, java.time… by default)
PJ> "today is $(LocalDate.now().dayOfWeek)"         # $( … ) inside strings, """ text blocks
```

Overloads, varargs and argument conversions follow Java rules; `System.exit` and the like are intercepted so that Java code cannot kill the shell.

### Shell language

- **Pipelines** `|` of cmdlets, native commands and expressions; collections are unrolled element by element.
- **Chaining** like Java statements: `a ; b`, `a && b`, `a || b` (`mvn package && java -jar target/app.jar`); `$?` and `$exit` give the last result.
- **Background** native commands with `&`, as on Unix: `xeyes &` gives the prompt back at once; `jobs` lists them, `jobs | kill` stops them, and the end of each one is reported before the prompt.
- **Variables** `$x = …`, `$_` in blocks, `$errors`, `$profile`.
- **Unit literals** `512b 10kb 1gb`, `30s 5m 7d`, usable in options and expressions.
- **Redirections** `>`, `>>`, `2>`, `2>>`, `2>&1`; `~` is the home directory in any unquoted argument.

### Line editing

Persistent history with prefix search on ↑/↓, **Ctrl+R** reverse search, **Tab completion** (commands tagged `[pj]` / `[internal]` / `[native]`, options with descriptions, paths, variables, properties of `$_` and of lambda parameters, Java classes and methods with their signatures), **syntax highlighting** as you type, and a `>>` continuation prompt when a brace, parenthesis or quote is left open or a line ends with `|`, `&&` or `||`.

### Preview mode

Press **Alt+V**: the prompt becomes `PJ?` and the first results of the line appear **below it while you type**, before Enter, for the pipeline stage under the cursor. Only what changes nothing runs: cmdlets declared `preview = true` and a fixed set of JDK methods; a native command, a built-in command, a Java call that could change something, an assignment or a redirection pauses the preview with the reason. Enter executes the line as usual. Details: FR-66 in the specification, checklist [preview](docs/recettes/preview.md).

```text
PJ? C:\dev> ls -r --filter *.java | where { f -> f.size > 10kb }█
── FileEntry · 10+ ──────────────────────────────────
name              size      modified          dir
Parser.java       12.4 KB   2026-10-12 09:41  false
…
```

### Languages

Messages, help, completion and errors in **English or French**; commands and options are the same in both. See [Configuration](#configuration).

## Getting started

### Prerequisites

- **JDK 27**. Maven is not needed: the `mvnw` wrapper downloads Maven 3.9.11.
- Without JDK 27 at hand, you can check the build with an older JDK that supports the features used: `./mvnw -Djava.release=25 verify`.

### Build

```bash
./mvnw verify                 # compile + tests
./mvnw -Pdist verify          # + standalone application image (jlink runtime + jpackage launcher)
```

The image is produced in `powerj-dist/target/jpackage/powerj/` (launcher `bin/powerj` on Linux/macOS, `powerj.exe` on Windows), with a zip archive in `powerj-dist/target/`. It embeds its own Java runtime containing all the `java.se` modules: nothing else needs to be installed on the target machine.

Optionally, on Windows, an `.exe` installer (adds PowerJ to the `PATH` and the Start menu) is built with `mvnw.cmd -Pdist,installer verify` and requires [WiX](https://wixtoolset.org/) (`dotnet tool install --global wix --version 5.0.2`). The GitHub Actions CI builds and smoke-tests the launcher on every push, and produces the Windows installer as a downloadable artifact.

### Run

| Invocation | Effect |
|---|---|
| `powerj` | Interactive shell (runs `~/.powerj/profile.pj` first) |
| `powerj --no-profile` | Interactive shell without the startup profile |
| `powerj -c "ls -r \| where size > 1mb"` | Runs one line, then exits |
| `powerj script.pj` | Runs a file line by line, then exits |
| `ls /etc \| powerj -c 'where { $_.endsWith(".conf") }'` | Standard input feeds the first stage as `String` lines |
| `powerj --debug …` | Also prints the detailed diagnostic log to the console |

In non-interactive mode the exit code is the argument of `exit` if called, otherwise 0 on success, the code of the last native command if it failed, or 1 for a PowerJ error.

### A first session

```text
PJ ~> help                                        # overview of commands and syntax
PJ ~> which ls ; which git                        # cmdlet, built-in or native?
PJ ~> ls ~/projects | where { $_.dir }            # objects filtered on a property
PJ ~> ls -r --filter *.java | map { f -> f.size } | collect
PJ ~> grep -ri "todo" src | where { m -> m.path.toString().contains("core") }
PJ ~> git log --oneline | grep -i fix             # native output filtered by a cmdlet
PJ ~> $f = (ls).getFirst() ; help members $f      # what can I do with this object?
PJ ~> Files.readString(Path.of("README.md")).lines().count()
```

## Configuration

PowerJ keeps its files in `~/.powerj/` (`%USERPROFILE%\.powerj\` on Windows, or `$POWERJ_HOME`), created on first launch:

| File / folder | Role |
|---|---|
| `history` | Command history (UTF-8), reloaded at startup |
| `config.properties` | `history.size=10000`, `language=en` (or `fr`) |
| `profile.pj` | Commands run when the interactive shell starts: `$proj = Path.of("/home/me/dev")`, `import java.time.*`, `env --set MAVEN_OPTS=-Xmx2g` — errors show their line and never prevent startup ([profile](docs/recettes/profile.md)) |
| `protected-paths` | Paths that `rm` and `mv` refuse to remove (one per line, `~` for the home folder); written with the defaults of the platform on first launch, then yours to edit ([fileops](docs/recettes/fileops.md)) |
| `modules/` | Third-party module jars, loaded at startup |
| `logs/` | Diagnostic log, always in English |

| Environment variable | Role |
|---|---|
| `POWERJ_HOME` | Location of the configuration folder |
| `POWERJ_LANG` | `en` or `fr` |
| `POWERJ_NATIVE_ENCODING`, `POWERJ_NATIVE_ENCODING_<NAME>` | Encoding of the output of native commands, globally or for one program |
| `NO_COLOR` | Disables colors |

**Language.** By default PowerJ follows the display language of the system (French → French, anything else → English). Precedence: system property `-Dpowerj.language`, then `POWERJ_LANG`, then the `language` key of `config.properties`, then the system ([i18n](docs/recettes/i18n.md)).

```text
POWERJ_LANG=fr powerj                   # Linux / macOS
set POWERJ_LANG=en                      # Windows (cmd.exe), before launching powerj
```

## How it works

### Modules

All modules are JPMS modules (`module-info.java`); the only runtime dependency is [JLine 3](https://github.com/jline/jline3).

| Module | Role |
|---|---|
| `powerj-api` | Public API for writing cmdlets (`Cmdlet`, `@CmdletInfo`, `@Option`, `CmdletProvider`); no dependencies |
| `powerj-fs` | File tree walk, sequential or parallel in virtual threads (`FileWalker`), reusable by any cmdlet |
| `powerj-core` | Lexer, parser, interpreter, pipeline, option binding, Java interoperability, completion engine, output formatting, native execution |
| `powerj-cmdlets` | Built-in cmdlets (`ls`, `grep`, `cat`, `where`, `map`, `sort`, `first`, `last`, `range`, `select`, `count`, `measure`, `group`, `mkdir`, `touch`, `cp`, `mv`, `rm`, `collect`, `env`) |
| `powerj-shell` | Entry point (`Main`), JLine REPL, profile, script mode, history expansion |
| `powerj-dist` | Distribution: jlink runtime + jpackage launcher / installer |
| `examples/greet` | Example third-party module, not shipped: a template for your own cmdlets |

### Life of a line

```text
 keyboard
    │   JLine LineReader ── ShellParser / InputCompleteness: open brace or trailing | → ">>" prompt
    ▼                      ShellCompletion (Tab) and highlighter, backed by core Completions
 Lexer ──────────► Token stream          (sealed interface, records)
    ▼
 Parser ─────────► Ast                    (statements ; && ||, pipelines, commands, redirections)
    │  └ ExpressionParser                 (Java-syntax expressions, blocks { … }, lambdas)
    ▼
 Interpreter ────► per command: built-in → cmdlet → executable on the PATH  (^name = native)
    │  ├ OptionBinder                     (Unix options → the cmdlet's parameter record)
    │  └ Evaluator / JavaInvoker          (expressions and Java calls)
    ▼
 Pipeline ───────► one virtual thread per stage, bounded queues (Pipe) between stages
    ▼
 OutputFormatter ► table / list / text on the terminal
```

The whole line runs under the `Supervisor`, which catches any `Throwable` and handles cancellation (Ctrl+C).

### The shell

`Main` resolves the configuration folder, the language and the mode: no argument starts the `Repl`, `-c` or a file starts `ScriptMode`. The `Repl` reads lines with JLine (history, Ctrl+R, editing), expands `!!`/`!n` (`HistoryExpander`), runs the profile on startup, and routes Ctrl+C to `Supervisor.cancel()` so that a command is interrupted without leaving the shell.

### Parsing

- The **`Lexer`** splits a line into tokens. Unquoted words are kept as is, backslashes included, so that `cd C:\Users` works; `;`, `&&`, `||`, `|`, `>`, `>>`, `2>`, `2>&1` are operators even when attached to a word; `$x =` at the start of a statement marks an assignment; unit literals (`10kb`, `7d`) are recognized by `Units`.
- Command syntax and Java syntax live side by side: strings, variables, `{ … }` blocks, `( … )` groups and — at the start of a statement — literals, `new`, `[type]` casts and dotted Java names are handed to the **`ExpressionParser`**, a precedence-climbing parser for Java expressions (ternary, `||`, `&&`, comparisons, arithmetic, casts, member access, lambdas, method references). In a command line an expression stops where the command syntax resumes, so `java -version` stays a native command while `java.lang.Math.max(1, 2)` is a Java call.
- The **`Parser`** assembles everything into an `Ast` of records (sealed hierarchy), which the interpreter walks with pattern-matching `switch`es.

### Command resolution and options

For the first word of a stage, the `Interpreter` looks for a built-in command, then a cmdlet in the `CmdletRegistry` (built-in cmdlets and third-party modules), then an executable via `CommandResolver` (`PATH`, `PATHEXT` on Windows, explicit paths). For a cmdlet, the `OptionBinder` builds its **parameter record** from the arguments: short and long options, grouped flags, unambiguous abbreviations, `--opt=value`, positionals, typed conversion (`Path`, `int`, `Duration`, enums, sizes…), and errors with suggestions (`unknown option --recurce, did you mean --recurse?`).

### Pipeline

Each stage runs in a **virtual thread**; stages are connected by bounded queues (`Pipe`), so a fast producer waits for a slow consumer. When the last stage stops (end, error, Ctrl+C, or `ls | ^more` then `q`), it closes its input and the upstream stages stop in cascade, killing native processes. A cmdlet receives `begin` / `process(input)` / `end` calls; collections returned by expressions are unrolled. Consecutive native commands are started together with `ProcessBuilder.startPipeline`, so bytes flow directly between them; objects sent to a native command are written to its stdin as displayed text. The current object `$_` and lambda parameters are bound with **scoped values**.

### Java interoperability

`JavaClasses` resolves class names among the public classes of the packages exported by the `java.*` modules (default imports plus session `import`s); classes of third-party modules stay invisible. `JavaInvoker` calls methods, fields and constructors by reflection, choosing among overloads (varargs included) the one with the cheapest conversions, then the most specific. Member metadata is cached per type with `ClassValue` (`Members`). Lambdas and method references are turned into functional interfaces by `FunctionalAdapter` with a `java.lang.reflect.Proxy`. Calls such as `System.exit` or `System.setOut` are intercepted.

### Completion and highlighting

`Completions` (in `powerj-core`) computes Tab candidates **without running anything**: it infers types statically from variable values, the declared output type of the upstream cmdlet (so `ls | where { $_.` offers `name`, `size`, `modified`…), lambda parameters and Java signatures; a Java class index (`JavaIndex`) is warmed up at startup. Options come from the cmdlet's annotations, so a third-party cmdlet is completed with no extra code. `ShellParser` tells JLine which word is being completed, `ShellCompletion` adapts the candidates and the highlighter, and `Highlights` colors the input (cmdlet green, native cyan, unknown red, options, strings, variables).

### Output

`OutputFormatter` displays successive records of the same type as a **table streamed as it arrives** (columns from the record components or from `@Display`; widths computed on the first lines), other objects as `name : value` lists, maps as key/value tables, collections one element per line, and sizes, dates and durations in a readable form. `grep` matches are shown in color through the `Highlight` type.

## Writing your own cmdlet

A cmdlet is a Java class implementing `Cmdlet<P extends Record, I, O>`: `P` is a record of options annotated with `@Option`, `I` the type received from the pipeline (`Void` if none), `O` the type produced (a record is recommended). The shell calls `begin`, then `process` for each input object, then `end`.

```java
public record GreetParams(
        @Option(shortName = 'n', mandatory = true, description = "Name to greet") String name,
        @Option(shortName = 'c', description = "Number of repetitions") int count) {}

public record Greeting(String name, String message, Instant at) {}

@CmdletInfo(name = "greet", category = "Examples", summary = "Greets someone",
        examples = "greet --name Yves -c 3")
public final class Greet implements Cmdlet<GreetParams, Void, Greeting> {
    @Override
    public void begin(GreetParams p, CmdletContext<Greeting> ctx) {
        for (int i = 0; i < p.count(); i++) {
            ctx.emit(new Greeting(p.name(), "Hello " + p.name(), Instant.now()));
        }
    }
}
```

A `CmdletProvider` lists the module's cmdlets (and may provide translations through `messages(Locale)`), declared in `module-info.java` with `provides io.powerj.api.CmdletProvider with …`. Build the jar and drop it into `~/.powerj/modules/`, or load it without restarting with `mod-load path/to/greet.jar`; `mod-list` shows what is loaded. Each module gets its own `ModuleLayer`, so dependencies of different modules do not clash; if two modules declare the same name, the second one is reachable as `module:name`.

The complete example lives in [`examples/greet`](examples/greet) (also built by the CI as the `greet-module` artifact); the contract is detailed in [§4 of the specification](docs/SPECIFICATION.md#4-extension-api-third-party-cmdlets).

## Tests

```bash
./mvnw verify
```

Unit and integration tests use JUnit 5 and AssertJ: lexer, parser, option binding, Java interop, pipeline, completion, native commands, module loading, and the REPL driven through a scripted terminal. Tests force English messages so that they do not depend on the machine. The CI additionally runs a smoke test against the packaged launcher. Each development step has a manual acceptance checklist in [`docs/recettes/`](docs/recettes/).

## Status and roadmap

PowerJ is in active development (version `0.1.0-SNAPSHOT`). Implemented so far:

- REPL with line editing, persistent history and Ctrl+R; native commands, `cd`, chaining, background commands (`&`, `jobs`), variables, redirections; non-interactive mode;
- object pipeline with the `ls`, `where`, `map`, `sort`, `first`, `last`, `range`, `select`, `count`, `measure`, `group`, `mkdir`, `touch`, `cp`, `mv`, `rm`, `to-json`, `from-json`, `to-csv`, `from-csv`, `ps`, `kill`, `collect`, `env`, `grep` and `cat` cmdlets, and a parallel file walker;
- direct access to the JRE's Java API, Java-style lambdas and method references;
- Tab completion and syntax highlighting;
- third-party modules loaded from jars;
- English / French interface, `~` expansion, startup profile;
- preview mode (Alt+V): results shown while typing.

The v1 cmdlet roadmap is done ([roadmap](docs/plans/cmdlet-roadmap.md), end-to-end check in [pipeline-v1](docs/recettes/pipeline-v1.md)). Still in the backlog ([§12.3](docs/SPECIFICATION.md#123-cmdlet-backlog-outside-the-current-scope)): `uniq`, `find`, `tee`, `table`/`tree`, `http`/`ping`, `open`. Next: a scripting language with control structures in v2. The step-by-step plan is in [§11 of the specification](docs/SPECIFICATION.md#11-iterative-development-plan).

## Documentation

- [Specification](docs/SPECIFICATION.md): functional requirements, grammar, extension API, architecture.
- [Acceptance checklists](docs/recettes/) for each development step, and [plans](docs/plans/) of the latest iterations.
- [Example module `greet`](examples/greet/README.md).

Coming from PowerShell? A few equivalences (full table in [§12.4](docs/SPECIFICATION.md#124-powershell--powerj-mapping)):

| PowerShell | PowerJ |
|---|---|
| `Get-ChildItem -Recurse -Filter *.java` | `ls -r --filter *.java` |
| `Where-Object { $_.Length -gt 1MB }` | `where { $_.size > 1mb }` |
| `ForEach-Object { $_.Name }` | `map { f -> f.name }` or `map FileEntry::name` |
| `-and`, `-or`, `-not` | `&&`, `\|\|`, `!` |
| `$LASTEXITCODE` | `$exit` |
| `[System.Math]::Max(3, 7)` | `Math.max(3, 7)` |
| `Get-Member` | `help members` |

## License

PowerJ is released under the [Apache License 2.0](LICENSE).
