# PowerJ — Functional and Technical Specification

| | |
|---|---|
| **Document version** | 0.10 (`~` = `HOME` directory in every argument) |
| **Status** | Pending approval |
| **Target platform** | Windows 10/11 x64 (`powerj.exe`), Linux/macOS as a bonus |
| **Technical foundation** | Java 27, Maven 3.9, JLine 3 |

---

## Table of contents

1. [Vision and goals](#1-vision-and-goals)
2. [Glossary](#2-glossary)
3. [Functional requirements](#3-functional-requirements)
4. [Extension API (third-party cmdlets)](#4-extension-api-third-party-cmdlets)
5. [Technical architecture](#5-technical-architecture)
6. [Use of modern Java features](#6-use-of-modern-java-features)
7. [Build and distribution](#7-build-and-distribution)
8. [Configuration](#8-configuration)
9. [Non-functional requirements](#9-non-functional-requirements)
10. [Test strategy](#10-test-strategy)
11. [Iterative development plan](#11-iterative-development-plan)
12. [Appendices](#12-appendices)

---

## 1. Vision and goals

PowerJ is an **interactive object-oriented shell** written in Java. Like PowerShell, its built-in commands (*cmdlets*) do not produce text but **Java objects** — preferably `record`s — whose attributes can be extracted, filtered and combined in a pipeline. **The entire Java API of the JRE can be called directly** from the command line (`java.util.List.of("apple", "banana")`, `Math.max(3, 7)`). Unlike PowerShell:

- commands have **short names, familiar to Unix users** (`ls`, `where`…) rather than verbose Verb-Noun names (`Get-ChildItem`, `Where-Object`);
- options follow the **Unix convention** (`-r`, `--recurse`);
- **native commands** (`git`, `cat`, `notepad`…) can be used freely and behave as in a classic shell: their standard output and error output remain streams.

```text
PJ C:\dev\powerj> ls -r --filter *.java | where { $_.size > 10kb && $_.modified > now - 7d }

name               size      modified              dir
----               ----      --------              ---
Parser.java        14.2 KB   2026-10-05 18:12      false
Evaluator.java     11.8 KB   2026-10-06 09:40      false

PJ C:\dev\powerj> git status --porcelain | where { $_.startsWith(" M ") }
 M src/core/Parser.java
 M src/core/Evaluator.java

PJ C:\dev\powerj> java.util.List.of("apple", "banana", "orange") | where { $_.contains("b") }
banana

PJ C:\dev\powerj> LocalDate.now().plusDays(10).dayOfWeek
FRIDAY
```

### 1.1 Goals of v1

| ID | Goal |
|---|---|
| OBJ-1 | Interactive shell delivered as an installable `powerj.exe` on Windows. |
| OBJ-2 | Comfortable line editing: persistent history, ↑/↓, **Ctrl+R**, **Tab** completion. |
| OBJ-3 | Pipeline of typed Java objects (preferably records, but any object) with attribute access (`$_.size`). |
| OBJ-4 | Seamless mixing of cmdlets ↔ native commands. |
| OBJ-5 | Extensibility: a third-party developer adds cmdlets by dropping in a `.jar`. |
| OBJ-6 | Exemplary codebase in modern Java (Java 27). |
| OBJ-7 | Direct access to the entire public Java API of the JRE (static methods, constructors, instance methods). |

### 1.2 Non-goals of v1

- No full scripting language (`if`, `foreach`, functions, script files) — planned for v2.
- No syntax compatibility with PowerShell or bash.
- No remote execution (*remoting*).
- No PowerShell-style *providers* (registry, certificates…).
- **Cmdlet scope deliberately limited to 3 cmdlets (`ls`, `where`, `env`)**; the rest of the catalog is in the backlog (§12.3).

---

## 2. Glossary

| Term | Definition |
|---|---|
| **Cmdlet** | Command implemented in Java in PowerJ (or in a third-party module). It consumes and/or produces objects. |
| **Native command** | External program found in the `PATH` (`git.exe`, `notepad.exe`…). It produces text on stdout/stderr. |
| **`^` prefix** | Forces execution of the native command even if a cmdlet has the same name (`^ls`). |
| **Pipeline** | Chain of stages separated by `|`; each stage receives the output stream of the previous one. |
| **Output stream** | Sequence of Java objects produced by a stage (records, arbitrary objects, or `String` lines for a native command). |
| **Java expression** | Call to a Java method, constructor or field directly in the line (`Math.max(3, 7)`, `new java.io.File("x")`). |
| **Unrolling** | Emitting the elements of a collection one by one into a pipeline. |
| **Error stream** | Sequence of error messages, displayed separately (in red), never mixed with the output stream. |
| **Output record** | Java `record` type describing the objects produced by a cmdlet (e.g. `FileEntry`); recommended but not mandatory. |
| **Filter** | Cmdlet that transforms or selects the objects it receives (e.g. `where`). |
| **Expression block** | Expression between braces `{ … }` evaluated for each object, `$_` denoting the current object. |
| **Module** | `.jar` archive providing one or more third-party cmdlets. |
| **Acceptance test** | Manual test scenario run by the PM to validate a development step. |

---

## 3. Functional requirements

Each requirement has an identifier `FR-xx` and one or more verifiable **acceptance criteria** (CA).

### 3.1 REPL

**FR-01 — Prompt.** On startup, PowerJ displays a banner (`PowerJ 0.x — Java 27`) then the prompt `PJ <répertoire courant>> ` (`<current directory>`).
- CA: launching `powerj.exe` from `C:\Users\yves` displays `PJ C:\Users\yves> `.

**FR-02 — Multi-line input.** If a line ends with `|`, `&&` or `||`, or if a brace, parenthesis or quote is left open (no continuation with `\`, so that `cd C:\` remains valid), the shell displays a continuation prompt `>> ` and waits for the rest.
- CA: `ls |` + Enter displays `>> `; `where { $_.dir }` + Enter runs the complete pipeline.

**FR-03 — Interruption (Ctrl+C).** Ctrl+C **kills the running command** and returns control without ever exiting the shell:
- while typing: clears the current line;
- while running: cancels the whole pipeline — cmdlets, Java expressions and native processes (including their child processes) — using the mechanism described in §3.14;
- second Ctrl+C if the command has not stopped: forced abandonment (§3.14).
- CA: `ls -r C:\` then Ctrl+C returns the prompt; `Stream.iterate(0, { $_ + 1 }).forEach({ $_ })` then Ctrl+C returns the prompt; `ping -t localhost` then Ctrl+C stops `ping`.

**FR-04 — Exit (Ctrl+D).** **Ctrl+D on an empty line** or `exit` exits the shell, saving the history; on a non-empty line, Ctrl+D deletes the character under the cursor. `exit <code>` exits with that return code. If abandoned commands are still running (§3.14), the shell terminates them before exiting.

**FR-04b — Directory navigation.** Commands built into the REPL (not cmdlets):

| Command | Effect |
|---|---|
| `cd <chemin>` | Changes the current directory (absolute path, relative path, `..`, `~` = home directory (FR-62), drive `D:`). |
| `cd` | Returns to the user home directory. |
| `cd -` | Returns to the previous directory. |
| `pwd` | Displays the current directory (`Path` object). |

The current directory belongs to the shell: it is the base for relative paths of cmdlets, of Java calls going through PowerJ, and of launched native commands (the process working directory). `$pwd` contains the current `Path`.
- CA: `cd ~`, `cd ..`, `cd -`, `cd D:`, `cd "C:\\Program Files"`; the prompt follows; `git status` runs in the right directory.

**FR-62 — Tilde `~`.** `~` designates the home directory given by the **`HOME`** variable of the session environment; if `HOME` is not set (usual on Windows), `USERPROFILE`, then the user directory of the JVM (`user.home`). `HOME` is read at each use: `env --set HOME=D:\moi` applies at once.
- Expanded like in bash, in every **unquoted word** argument of any command (cmdlet, built-in command, native program) and in redirection targets: `~` alone, `~/…` and `~\…` (`ls ~/docs`, `notepad ~\notes.txt`, `"x" > ~/out.txt`, `mod-load ~/greet.jar`).
- Not expanded: inside quotes (`"~"`), in the middle of a word (`a~b`), `~user`, in Java expressions.
- Path completion (Tab) follows the same directory: `ls ~/Do<Tab>`.
- CA: with `HOME=/home/yves`, `echo ~/x "~"` prints `/home/yves/x ~`; after `env --set HOME=/tmp`, `cd ~` goes to `/tmp`; on Windows without `HOME`, `cd ~` goes to `%USERPROFILE%`.

**FR-63 — Startup profile `profile.pj`.** When the **interactive** shell starts, PowerJ runs the file `profile.pj` of the configuration folder (`~/.powerj/profile.pj`, or `$POWERJ_HOME/profile.pj`; §8), after the banner and the module warnings, before the first prompt. Its commands run in the session of the prompt: variables, `import`, `env --set`, `cd` remain in effect.
- The file is read in UTF-8 (a leading BOM, written by Notepad, is ignored). Blank lines and `#` comments are ignored. A command spans several lines with the same rule as the interactive continuation (FR-02: open brace, parenthesis or quote, line ending with `|` or `&&`).
- Each command runs like a typed line (Supervisor, FR-56) but is not added to the history. An error is displayed prefixed with the file and the line of the command (`profile.pj:4: unknown command: bonjour`) and does not stop the following commands: a faulty profile never prevents the shell from starting. Ctrl+C cancels the current command and skips the rest of the profile. `exit` in the profile quits PowerJ.
- Missing file: nothing is displayed and no file is created. `powerj --no-profile` starts without running it. The non-interactive mode (`-c`, `.pj` file, FR-04d) never runs it.
- `$profile` contains the path of the file, even with `--no-profile` (`notepad $profile`, `vim $profile`).
- CA: with a profile containing `$x = 42`, `import java.time.*` and an unknown command on line 4, the shell displays `profile.pj:4: unknown command: …` then the prompt; `$x` gives `42` and `Year.of(2024).isLeap()` gives `true`; `powerj --no-profile` then `$x` gives `unknown variable: $x`; `powerj -c "$x"` too.

**FR-04c — Command chaining.** At the line level, several commands are chained like Java statements:

| Syntax | Effect |
|---|---|
| `a ; b` | Runs `a` then `b`, regardless of the result. |
| `a && b` | Runs `b` only if `a` **succeeded**. |
| `a \|\| b` | Runs `b` only if `a` **failed**. |

"Succeeded" is defined as follows: native command → return code 0; cmdlet or Java expression → no terminating error; expression with a `Boolean` value → its value. This rule gives the same reading as in Java (`&&`/`||` short-circuit, a command "evaluates to" its success): `mvn package && java -jar target/app.jar`, `Files.exists(Path.of("build")) || mkdir build`. `$?` reflects the success of the last command executed.

**FR-04d — Non-interactive mode.**

| Invocation | Effect |
|---|---|
| `powerj -c "<ligne>"` | Runs the line then exits. |
| `powerj fichier.pj` | Runs the file line by line (in v1: a sequence of lines, without control structures) then exits. |
| `… \| powerj -c "where { $_.contains(\"x\") }"` | If stdin is not a terminal, its lines feed the first stage (`String` lines). |

In this mode: no prompt, no history, no colors if the output is not a terminal, and a terminating error stops execution. Process **return code**: `exit <n>` if called; otherwise 0 if the last command succeeded, the code of the last native command if it failed, 1 for a PowerJ terminating error.
- CA: `powerj -c "ls | where { $_.size > 1mb }"` from `cmd.exe`; `powerj -c "^cmd /c exit 3"` then `echo %ERRORLEVEL%` displays 3; `dir /b | powerj -c "where { $_.endsWith(\".txt\") }"`.

### 3.2 Line editing

**FR-05 — Editing.** Emacs editing by default (←/→, Home/End, Ctrl+←/→ by word, Ctrl+W, Ctrl+K, Ctrl+U), based on JLine 3.

**FR-06 — History navigation.** ↑/↓ cycle through previous commands. If the beginning of a line has been typed, ↑/↓ only offer entries that start with that prefix.
- CA: after `ls -r` and `git status`, typing `gi` then ↑ displays `git status`.

**FR-07 — Reverse search (Ctrl+R).** Ctrl+R opens an incremental search `(reverse-i-search)'…':` in the history; repeated Ctrl+R goes back to the previous occurrence, Ctrl+S moves forward, Enter executes, Esc/→ retrieves the line for editing, Ctrl+G cancels.
- CA: after 3 commands including `ls --filter *.txt`, Ctrl+R then `txt` displays that command.

**FR-08 — Syntax highlighting.** While typing: cmdlet (green), native command (cyan), unknown command (red), options (gray), strings (yellow), variables (magenta).

**FR-66 — Preview mode.** Like the modes of vi, the line editor has two modes: **normal** (default) and **preview**. **Alt+V** ("View") switches from one to the other, keeping the line being typed; the mode stays from one command to the next until the next switch. In preview mode, the **prompt** shows it, `PJ? <directory>> ` instead of `PJ <directory>> ` (`PJ?` in cyan on a terminal with colors); the continuation prompt `>> ` does not change. Ctrl+Esc was ruled out: Windows keeps it for the Start menu and most terminals send it as a plain Esc. Under macOS, Alt+V needs the terminal's option "Use Option as Meta key".

In preview mode, **each change of the line evaluates it in the background**, and its first results are shown **below the line**, without pressing Enter:

```text
PJ? C:\dev> ls -r --filter *.java | where { f -> f.size > 10kb } | map { f -> f.na█
── String · 10+ ─────────────────────────────────────────────────────────────
Parser.java
Interpreter.java
…
```

- **Banner:** type of the values (simple name of their common class, otherwise `Object`) and their number, `10+` when there are more; `no result`; the number of non-blocking errors (objects skipped) when there are some. Then at most 10 values in the usual display format (tables for records, FR-30), cut to the terminal width (12 lines at most).
- **Evaluation:** starts 60 ms after the last key (typing fast does not start one evaluation per key), stops at the 10th value (the next ones are not computed: `ls -r /` answers at once), and after **400 ms**: the values already received are then shown, with `+`. A new key cancels the running evaluation (cancellation mechanism of §3.14). The evaluation that does not react to the cancellation (Java loop without callback, e.g. `LongStream.range(0, Long.MAX_VALUE).sum()`) keeps its thread busy: the preview no longer refreshes until it ends; Alt+V, then normal input, remain available.
- **Stage under the cursor:** the preview shows the output of the stage where the cursor is (the line is cut before the next top-level `|`): moving the cursor into the 2nd stage shows what the 2nd stage produces, without splitting the pipeline. Cursor at the end: the whole line.
- **What runs.** Only what changes nothing:
  - the **cmdlets declared `@CmdletInfo(preview = true)`** (§4.2; default `false`). Built-in cmdlets: `ls`, `where`, `map`, `collect`, `env`, `grep`, `cat`. A cmdlet whose options can change something refines the decision with `Cmdlet.previewable(params)`: `env` is previewed, `env --set` (`--unset`, `--append`, `--prepend`) is not;
  - **expressions**, and the **Java calls of the fixed preview set**, built into PowerJ and not configurable: methods of `Object` (`toString`, `equals`, `hashCode`, `getClass`, `compareTo`); all methods of `String`, `CharSequence`, wrappers (`Integer`, `Long`…), `Math`, `StrictMath`, `BigInteger`, `BigDecimal`, `Enum`, `Optional*`, `Comparator`, `Objects`, `Pattern`, `Matcher`, `Paths`, and of the packages `java.time.*`, `java.util.stream`, `java.util.function`; `Path` (except `register`); the **reading** methods of collections, maps and iterators (`size`, `get`, `contains`, `stream`, `forEach`, `keySet`, `List.of`…, not `add`, `put`, `sort`, `remove`…, nor `parallelStream`); `Arrays` and `Collections` except their mutators (`sort`, `fill`, `shuffle`…); `Files` in read-only mode (`exists`, `size`, `isDirectory`, `readString`, `readAllLines`, `lines`, `list`, `walk`…); `Class` for its names and type tests only (not reflection); the constructors of `String`, `BigInteger`, `BigDecimal` and of the empty collections (`new ArrayList(…)`, `new HashMap()`…). Only JDK classes are concerned: a method of a third-party module never runs in a preview; record accessors always do.
- **What stops the preview:** a native command, a built-in command (`cd`, `help`, `mod-load`…), a cmdlet not declared `preview = true` or whose options change something, a Java call outside the set (including reflection, `System.exit`, a constructor outside the set), an assignment `$x = …`, a redirection, several commands (`;`, `&&`, `||`), a variable holding a stream or an iterator (it could be read only once). The preview then **stays on its last results**, under a gray banner that says why: `⏸ preview off: native command git`, `⏸ preview off: Java call outside the preview set: Files.delete`. Typing goes on normally.
- The check is made **when the call is about to happen**, not by reading the line: it also covers the calls made in a lambda (`stream().forEach(f -> Files.delete(f))`), in a `$( … )`, in a pipeline stage running in its own thread, in a block converted to a Java function before the preview, and while the values are formatted (a stream inside a list is read under the same checks). Parallel streams (`parallel`, `parallelStream`, `Arrays.parallelSort`…) are outside the set: they would call the lambdas in other threads. The forbidden call is never made.
- **Nothing changes during a preview:** nothing is displayed outside the preview area, non-blocking errors are only counted, `$?`, `$errors`, `$exit` and the variables are left as they are.
- **Syntax error** while typing (`ls |`): the last results stay, under a gray banner `… <message>`. **Blocking error** (`"abc".nope()`, unknown command): the message in red replaces the results.
- **Completion (Tab) in preview mode** offers only the cmdlets declared `preview = true` and the Java methods of the preview set; built-in commands and native programs are not offered.
- **Enter** executes the line normally, whatever the state of the preview (a native command included); the preview is cleared before the output. Ctrl+C clears the line and the preview.
- CA:
  - Alt+V displays `PJ? C:\dev> `; Alt+V again displays `PJ C:\dev> `; the line typed is kept;
  - in preview mode, `"abc".toUpperCase()` shows `── String · 1` then `ABC` below the line before Enter;
  - `ls` shows the table of the files with `── FileEntry · n`; adding `| where { f -> f.size > 1kb }` reduces it while typing;
  - in `java.util.List.of("alpha1", "beta2") | where { s -> s.endsWith("2") }`, cursor at the end: `String · 1` (`beta2`); Home (cursor in the 1st stage): `String · 2`;
  - `ls | git status`, `cd ..`, `env --set X=1`, `$x = 1`, `"a" > out.txt`: banner `⏸ preview off: …`, and nothing happens (no file, no variable, same directory) until Enter;
  - `ls | where { f -> Files.deleteIfExists(f.path) }`: `⏸ preview off: Java call outside the preview set: Files.deleteIfExists`, no file deleted;
  - Tab after `c` in preview mode offers `cat` and `collect`, not `cd`; after `Files.` offers `exists(`, not `delete(`.

### 3.3 History

**FR-09 — Persistence.** The history is saved in `~/.powerj/history` (UTF-8) after each command and reloaded on startup.
- CA: type 3 commands, exit, relaunch: ↑ finds them in order.

**FR-10 — Rules.** Configurable maximum size (default 10,000 entries); consecutive duplicates ignored; a line starting with a space is not recorded (sensitive commands).

**FR-11 — History commands.** `history` lists the numbered entries; `history --clear` clears the history; `!!` re-runs the last command; `!n` re-runs entry *n*; `!texte` the last command starting with `texte`. The expanded command is displayed before execution.

### 3.4 Command naming and resolution

**FR-12 — Naming rule.** Each cmdlet has **a single name**, short, lowercase:
- taken from the equivalent Unix program when one exists (`ls`, `cat`, `ps`, `find`…);
- otherwise a short word (`where`, `select`, `sort`) or a hyphenated compound word (`to-json`).

There is **no** long form (`pj-ls`, `Get-ChildItem`…).

**FR-13 — Resolution order.** For the first word of a stage:
1. REPL built-in keyword (`exit`, `history`, `help`, `which`, `cd`, `pwd`, `import`);
2. user-defined alias;
3. **cmdlet** (built-in or provided by a module);
4. **native command** found in the `PATH` (with `PATHEXT` on Windows);
5. otherwise: error `unknown command: xxx` with suggestions (edit distance).

**FR-14 — `^` prefix.** `^nom` skips steps 1 to 3 and always launches the native command: `^ls`, `^find "foo" a.txt`, `^sort data.txt`.
- CA: on Windows, `^find "x" a.txt` runs `C:\Windows\System32\find.exe`.

**FR-15 — `which`.** `which nom` indicates what will be executed: `ls → cmdlet (io.powerj.cmdlets)`, `git → native C:\Program Files\Git\cmd\git.exe`, `cd → built-in command`.

**FR-16 — Configurable preference.** The `native.prefer` key in `config.properties` lists the names for which the native command takes precedence over the cmdlet (e.g. `native.prefer=find,sort`).

**FR-17 — Collisions between modules.** If a module declares a cmdlet whose name already exists, a warning is displayed at load time; the first one loaded keeps the short name, the other remains accessible as `module:nom` (e.g. `docker:ps`), where `module` is the last segment of the Java module name (`com.example.greet` → `greet:greet`). A cmdlet named after a built-in command (`cd`, `help`, `mod-load`…) is ignored with a warning: it could never be called.

### 3.5 Parameters

**FR-18 — Option syntax (Unix style).**

| Form | Example |
|---|---|
| Short option | `-r` |
| Grouped short options | `-ra` (= `-r -a`) |
| Long option | `--recurse` |
| Value separated or with `=` | `--filter *.java`, `--filter=*.java` |
| Positional | `ls C:\temp` |
| End of options | `ls -- -fichier-commencant-par-tiret` |

Option names are case-insensitive; a long option can be abbreviated as long as it is not ambiguous (`--rec`).

**FR-19 — Unit literals.** Sizes `512b 2kb 500mb 1gb` (multiples of 1024) and durations `30s 5m 2h 7d` are language literals, usable both as options and in expressions.

**FR-20 — Conversion and validation.** Values are converted to the declared type of the parameter (`Path`, `int`, `long`, `Duration`, `Instant`, enum, `boolean`…). Missing mandatory option, unconvertible value or unknown option → explicit error before execution, with a suggestion (`ls: unknown option --recurce, did you mean --recurse?`).

### 3.6 Completion (Tab)

**FR-21 — Command completion.** In command position, Tab offers: built-in keywords, aliases, cmdlets, then executables from the `PATH` (cache refreshed in the background). The menu indicates the kind: `ls [pj]`, `cd [internal]`, `less [native]` (in French: `[interne]`, `[natif]`). After `^`, only native executables are offered.
- CA: `l<Tab>` offers `ls [pj]` then the native commands starting with `l`.

**FR-22 — Option completion.** After `-` or `--`, Tab offers the options of the current command with their description, **excluding those already typed**. Options are read from the cmdlet metadata: a third-party cmdlet is therefore completed without any extra code.
- CA: `ls --<Tab>` offers `--all --filter --recurse`.

**FR-23 — Value completion.** Depending on the parameter type: enum → constants; `Path` or positional text → file paths; `boolean` → nothing. A path containing a space is inserted between quotes with Java escapes. A cmdlet will be able to provide its own completion via `@Completion(MonCompleteur.class)` (after v1).

**FR-24 — Property and variable completion.** After `$` → defined variables. After `$_.` in a `{ }` block → components of the record produced by the previous stage (static output type of the upstream cmdlet). After `$var.` → components of the type of the value of `$var`.
- CA: `ls | where { $_.<Tab>` offers `name size modified path dir ext`.

**FR-24b — Java completion.** In a Java expression (§3.13):
- after `java.` / `javax.` / a package name → subpackages and public classes (index of the exported packages of the runtime and of loaded modules);
- after `Classe.` → **static** methods and fields;
- after `$x.` or `expr().` → public methods and properties (getters, fields) of the actual type of the value, or of the known static return type;
- after `new ` → instantiable classes; after `import ` → packages and classes.

The menu displays the signature (`of(E...) : List<E>`). Imported classes (§FR-47) are offered by their simple name.
- CA: `java.util.Li<Tab>` offers `List LinkedList …`; `List.<Tab>` offers `of copyOf`; `$l = List.of(1); $l.<Tab>` offers `size() get(int) stream() …`.

**FR-25 — Native command arguments.** Tab completes file paths.

**FR-26 — Ergonomics.** First Tab: completes the common prefix; next Tab: menu of candidates, Tab/Shift+Tab to move through it; each candidate displays a short description (cmdlet synopsis, option type). Response time < 50 ms.

### 3.7 Object model

**FR-27 — Any Java object.** Any Java object can flow through a pipeline or be stored in a variable: records, `String`, numbers, `List`, `Map`, `java.io.File`, `LocalDate`, objects from a third-party module…

**Records remain the recommended format** for cmdlet outputs (table display, attribute completion, automatic documentation), but they are not mandatory: a cmdlet can produce any type.

**FR-28 — Access to attributes and methods.**
- `$x.nom` (without parentheses) is a **property**, resolved in this order, case-insensitively:
  1. record component `nom()`;
  2. getter `getNom()` or `isNom()` (boolean);
  3. public field `nom`;
  4. on a `Map`: value associated with the key `"nom"`.
- `$x.nom(args)` (with parentheses) is always a **method call** (§3.13).
- Access can be chained: `$x.path.parent`, `$f.toPath().fileName`.
- **`.` always applies to the object itself**, as in Java: on a list, `$f.size()` is the number of elements and `$f.empty` calls `isEmpty()`.
- **`*.` (the "spread" operator, as in Groovy) applies to each element** and returns the list of results: `$f*.name` (names of all files), `$f*.size`, `$f*.name*.toUpperCase()`, `$f*.name.size()` (number of names). A single value counts as one element, `null` as none: `(ls -r)*.name` always gives a list, even with a single file.
- `$f.name` on a list is an explicit error: `List has no property 'name' (for each element: *.name)`. In a pipeline, the equivalent of `*.` is `map`: `ls | map FileEntry::name`.
- Indexing: `$f[0]`, `$f[-1]` on `List`, array or `String`; `$m['clé']` on `Map`.
- Nonexistent property → error `FileEntry has no property 'siz' (properties: name, size, …)`; if a method with that name exists, the message says so: `String has no property 'length' (method: length())`.

**FR-29 — Introspection.** `help members` on a value (`$f | help members` or `help members FileEntry`) lists the components: name, type, description (Javadoc / `@Doc` annotation).

**FR-30 — Default display.** At the end of a pipeline, each object is displayed according to its type:
- **record**: as a table if ≤ 5 displayable components (aligned columns, width adapted to the terminal), otherwise as a `nom : valeur` list; a record can declare its columns via `@Display(columns = {"name", "size", "modified"})`;
- **`Row`** (output of `select`, FR-69): successive rows with the same columns form one table, whatever the number of columns;
- **`Map`**: `clé / valeur` (key / value) table;
- **scalars** (`String`, numbers, booleans, dates, `Path`, enums): readable form; the line breaks of a text are kept, also in a table cell or a redirected file (`cat a.txt -t`, `cat a.txt -c | map { $_.content } > copie.txt`), and a text that already ends with a line break gets no other one (the copy is identical to the file);
- **other objects**: `toString()`; `help members` lets you explore their properties.

Successive objects of the same record type are grouped in the same table.

**FR-30b — Unrolling collections.** When a pipeline stage produces an `Iterable` (`List`, `Set`…), an array, a stream (`Stream`, `IntStream`, `LongStream`, `DoubleStream`: boxed elements), an `Iterator` or an `Optional` (and `OptionalInt`, `OptionalLong`, `OptionalDouble`), its elements are **emitted one by one** into the stream (empty `Optional` → nothing). A stream displayed directly (`IntStream.range(0, 3)`) likewise shows its elements. `String` and `Map` are **never** unrolled, nor is the list produced by `collect` (FR-36d).
In an **assignment**, the object is kept as is:

```text
PJ> java.util.List.of("apple", "banana", "orange") | where { $_.contains("b") }
banana
PJ> $l = java.util.List.of("apple", "banana")
PJ> $l.size()
2
PJ> $l | where { $_.length() > 5 }
banana
```

Unrolling is lazy for `Stream` and `Iterator` (no materialization in memory).

Sizes and durations in readable format (`14.2 KB`, `2 h 05 min`; the decimal separator follows the language, `14,2 KB` in French, FR-61), dates in local time.

### 3.8 Expression language

**FR-31 — Variables.** `$nom = <pipeline>` assigns the result (one object → the object; several → list; none → `null`). Automatic variables: `$_` (current object), `$last` (metadata of the last native command), `$exit` (its return code), `$?` (success of the last command), `$errors` (recent errors), `$home`, `$pwd`.

**FR-32 — Literals.** As in Java, with a few shell additions:
- **strings** between double quotes, with **Java escapes**: `\\` for a backslash, `\"`, `\n`, `\t`, `\uXXXX` → `"C:\\Users\\yves"`; interpolation `$var` and `$(expr)` (`\$` for a literal `$`); text blocks `"""…"""`;
- **characters** between single quotes: `'a'`, `'\n'` (type `char`, as in Java);
- integers, decimals, `true`/`false`/`null`, sizes and durations (FR-19), `now`, lists `[1, 2, 3]`.

**FR-32b — Unquoted command arguments.** A command argument written **without quotes** (`cd C:\Users`, `ls D:\photos`, `git log -n 5`) is taken **as is**: the backslash is not an escape character there, which makes it possible to type Windows paths naturally. Java escapes only apply **inside quotes**. An argument containing spaces is put between quotes, doubling the backslashes: `cd "C:\\Program Files"`.

**FR-33 — Operators in `{ }`: Java syntax.** Blocks use Java operators; for everything else (patterns, regular expressions, membership…), you call **the Java methods** of the objects (§3.13). There is no shell-specific operator such as `like` or `-match`.

| Category | Operators | Semantics |
|---|---|---|
| Equality | `==  !=` | **Value** equality (`Objects.equals`), not reference; numbers compared by value (`1 == 1L`). |
| Ordering | `<  <=  >  >=` | Numbers by value; other types via `Comparable.compareTo` (dates, `Duration`, strings…). |
| Logical | `&&  \|\|  !` | Short-circuit, as in Java. |
| Arithmetic | `+  -  *  /  %` | Java rules; `+` concatenates if either operand is a `String`; `Instant - Duration`, `Instant + Duration` supported. |
| Ternary | `cond ? a : b` | As in Java. |

String comparisons are **case-sensitive**, as in Java (`equalsIgnoreCase`, `toLowerCase()` for the opposite).

Equivalents for common needs:

| Need | PowerJ syntax |
|---|---|
| Contains | `$_.contains("b")` |
| Starts / ends with | `$_.name.startsWith("Pa")`, `$_.name.endsWith(".java")` |
| Regular expression | `$_.matches("^[a-m].*")` (whole line) or `Pattern.compile("IPv4").matcher($_).find()` |
| Case-insensitive | `$_.toLowerCase().contains("readme")`, `$_.equalsIgnoreCase("ok")` |
| Membership | `List.of("png", "jpg").contains($_.ext)` |
| File wildcard | `FileSystems.getDefault().getPathMatcher("glob:*.java").matches($_.path.fileName)` (or the `--filter` option of `ls`) |

Note: `!` at the beginning of a line remains history expansion (FR-11); inside an expression, it is negation. In a `{ }` block, `&&` and `||` are the logical operators; outside a block, they chain commands (FR-04c).

**FR-33b — Blocks and lambdas: Java syntax first.** PowerJ favors Java syntax whenever it exists; borrowings from shells are reserved for what Java cannot express (variables `$x`, interpolation `"$x"`, `$?`, redirections, literals `10kb` / `7d`).

| Form | Example | Meaning |
|---|---|---|
| Single-parameter lambda | `{ f -> f.size > 1mb }` | `f` is the received object. Parameters without `$`, as in Java; shell variables keep their `$`: `{ f -> f.size > $min }`. |
| Multi-parameter lambda | `{ (a, b) -> a.length() - b.length() }` | For `Comparator`, `BiFunction`, `reduce`… No parameters: `{ () -> "x" }`. |
| `$_` shorthand | `{ $_.dir }` | Block with no declared parameter: `$_` is the received object (single parameter). Handy for short filters. |
| Lambda without braces | `$l.stream().map(s -> s.length())` | Only **between the parentheses of a Java call**; as a cmdlet argument, braces remain mandatory (the `>` of `->` would otherwise be a redirection). |
| Method reference | `String::length`, `Path::of`, `ArrayList::new`, `$x::equals` | As in Java: static, unbound instance, bound to an object, constructor. |

**When to use `$_`, a lambda or a method reference.** All three forms do the same thing; choose the most readable:

| Situation | Recommended form | Example |
|---|---|---|
| **Short** condition or transformation that mentions the object only once or twice | `$_` | `ls \| where { $_.dir }`, `ipconfig \| where { $_.contains("IPv4") }`, `ls \| map { $_.name }` |
| **Long** expression, or one that mentions the object several times: a meaningful name helps rereading | named lambda | `ls -r \| where { f -> f.size > 1mb && f.modified > now - 7d && !f.name.startsWith(".") }` |
| Block **nested** in another block: `$_` would denote the inner block's object, not the outer one's | named lambda (mandatory for the outer one) | `ls -r \| where { f -> List.of("md", "txt").stream().anyMatch(e -> f.name.endsWith("." + e)) }` |
| **Two or more parameters** (`Comparator`, `reduce`, `BiFunction`) | lambda | `$m.sort((a, b) -> a.length() - b.length())` |
| No parameters (`Supplier`, `Runnable`) | lambda `() ->` | `Optional.empty().orElseGet(() -> "vide")` |
| The block merely **calls a method** on the object, or passes it to a method | method reference | `ls \| map FileEntry::name`, `$l.stream().map(String::toUpperCase)`, `$noms.stream().map(Path::of)` |
| Argument of a **Java method** (`stream().filter(…)`, `sort(…)`) | lambda without braces | `$l.stream().filter(s -> s.length() > 4)` |
| Argument of a **cmdlet** (`where`, `map`) | braces mandatory | `where { f -> f.size > 1mb }` (never `where f -> …`) |
| `.pj` script meant to be reread and maintained | named lambda | `where { fichier -> fichier.ext == "log" }` |

Short rule: **`$_` for one-line filters typed at the keyboard, a named lambda as soon as the expression grows, nests or takes two parameters, a method reference when it is enough.**

- A lambda parameter shadows, in the body of the block, a class with the same name (rare case: name parameters in lowercase).
- `$a`, `$b` and `$args` (step 5) are **removed**: write a two-parameter lambda instead.
- **Strict booleans**: wherever a condition is expected (`where`, `filter`, `&&`, `||`, `!`, ternary), the value must be a `boolean`, like a Java `Predicate`. `where { f -> f.name }` is a non-terminating error (`the block must return a boolean, got String …`); write `where { f -> !f.name.isEmpty() }`. `null` is not a boolean.
- Conversions keep the `[type] valeur` notation: the Java form `(type) valeur` would be ambiguous with `(commande)`.
- **Text blocks**: `"""…"""` as in Java (common indentation removed), with interpolation `$x` and `$( … )`.

**FR-34 — Redirections (outside blocks).** `> fichier` (overwrites), `>> fichier` (appends) for the output stream; `2> fichier`, `2>&1` for the error stream. Objects redirected to a file are written in their displayed form.

### 3.9 Cmdlets in the current scope

Only **four cmdlets** are in the scope of the first steps: `ls`, `where`, `map` and `env`; the mini-iterations add `collect` (FR-36d), `grep` (FR-64), `cat` (FR-65), `sort` (FR-67), `first`, `last` and `range` (FR-68), `select` (FR-69), `count` and `measure` (FR-70), `group` (FR-71), and the file operations `mkdir`, `touch`, `cp`, `mv`, `rm` (FR-72), and the conversions `to-json`, `from-json`, `to-csv`, `from-csv` (FR-73). The first two alone cover the core mechanisms: producing record objects, attribute access, the pipeline, expressions, and mixing with native commands.

#### FR-35 — `ls`: list files

```text
ls [chemin...] [-a|--all] [-r|--recurse] [-f|--filter <motif>] [-d|--dirs] [--files] [-p|--parallel <n>]
```

| Option | Type | Description |
|---|---|---|
| `chemin` (positional, multiple) | `Path` | Folder(s) or file(s) to list; default: current directory. Wildcards accepted (`*.txt`). |
| `-a`, `--all` | boolean | Includes hidden/system files. |
| `-r`, `--recurse` | boolean | Walks subfolders. |
| `-f`, `--filter` | pattern | Keeps only the names matching the pattern (`*.java`). |
| `-d`, `--dirs` | boolean | Folders only. |
| `--files` | boolean | Files only. |
| `-p`, `--parallel` | `int` ≥ 1 | Walks the tree with up to `n` directories and entries read at the same time, in **virtual threads** (`powerj-fs`, FR-64). The order of the entries is then free; `1` (default): sequential, folders first in alphabetical order. |

Output: stream of

```java
public record FileEntry(
        String name,       // name with extension
        long size,         // size in bytes (0 for a folder)
        Instant modified,  // last modification
        Path path,         // absolute path
        boolean dir,       // true if folder
        String ext) { }    // extension without the dot, "" if none
```

Columns displayed by default: `name size modified dir`. The absolute path is the `path` property, not displayed but usable: `(ls)*.path`, `ls | map { f -> f.path }`. Folders are listed before files, in alphabetical order (except with `--parallel`). The walk is **lazy** (streaming): `ls -r C:\ | where …` displays the first results immediately and Ctrl+C interrupts it. An inaccessible folder produces a non-blocking error and the walk continues.

CA:
- `ls` displays the contents of the current directory as a table;
- `ls -r --filter *.txt` recursively lists the `.txt` files;
- `ls -r -p 16` lists the same entries as `ls -r`, in any order;
- `(ls)*.name` displays only the names;
- `$f = ls; $f[0].size` displays the size of the first element;
- `^ls` runs the native `ls` if it exists (Git Bash, WSL…), otherwise error `native command not found: ls`.

#### FR-64 — `grep`: search a regular expression

```text
grep <motif> [chemin...] [-r|--recurse] [-p|--parallel <n>] [--max-depth <n>] [-i|--ignore-case]
     [-v|--invert] [-a|--all] [--encoding <charset>] [--property <nom>] [--binary] [-s|--single-match]
```

| Option | Type | Description |
|---|---|---|
| `motif` (positional, mandatory) | `String` | Regular expression, **`java.util.regex.Pattern`** syntax (`\w`, `(?<nom>…)`, `(?i)`…). Unquoted, the argument is taken as is (`grep class\s+\w+`, FR-32b); in quotes, backslashes are doubled as in Java source (`grep "class (\\w+)"`). |
| `chemin` (positional, multiple) | `String` | File(s) or folder(s) to search; wildcards on the last element (`*.log`, `src/*.java`). Default: the current directory, unless `grep` receives objects from the pipeline. A folder without `-r` is searched one level deep (its files). |
| `-r`, `--recurse` | boolean | Walks subfolders. Symbolic links to folders are not followed. |
| `-p`, `--parallel` | `int` ≥ 1 | Walks the tree and reads up to `n` files at the same time, in **virtual threads**. The order of the files is then free; the lines of a file stay grouped and in order. `1` (default): sequential, files in alphabetical order. |
| `--max-depth` | `int` ≥ 1 | Maximum depth (`1`: only the files of the folder); implies `-r`. |
| `-i`, `--ignore-case` | boolean | Ignores case (Unicode). |
| `-v`, `--invert` | boolean | Keeps the lines (or the objects) that do **not** match. |
| `-a`, `--all` | boolean | Includes hidden files and folders. |
| `--encoding` | charset | Charset of the files; default **UTF-8**, without fallback: a file that is not valid in this charset produces the non-blocking error `cannot read <file>: not readable as UTF-8 text (choose the charset with --encoding)`. |
| `--property` | name | Searches this property of the pipeline objects (`ls \| grep --property name ^a`). |
| `--binary` | boolean | Also searches binary files: disables the NUL-byte check below. Combine with `--encoding ISO-8859-1` to read any bytes without a decoding error. |
| `-s`, `--single-match` | boolean | Only the **first** matching line of each file searched (`GrepMatch`), and the file is not read further: finds quickly the files that match at least once (`grep TODO -r -s \| map { m -> m.path }`). With `-v`: the files that **never** match, one `GrepMatch` each with `line` 0, empty `text` and empty `matches` (reading stops at the first match, which excludes the file). No effect on the pipeline objects filtered like `where`. |

Output for files: stream of

```java
@Display(columns = {"path", "line", "text"})
public record GrepMatch(
        Path path,                  // absolute path of the file
        int line,                   // line number, from 1
        @Highlight("matches") String text,   // line without its end-of-line characters
        List<Span> matches,         // position of each match in text (empty with -v)
        List<String> groups,        // groups of the first match: groups[0] = whole match, groups[1]…
        Map<String, String> named)  // named groups (?<nom>…) of the first match: m.named.nom
{ }
```

The table displays `path line text`; on a terminal that accepts colors, the matches are shown in **bold red** in `text` (`@Highlight`, §4.2; no color in a redirected file, in non-interactive mode or with the `NO_COLOR` variable). Binary files (a NUL byte in their first 8 KB, except with a UTF-16/UTF-32 `--encoding` or with `--binary`) are skipped silently. An unreadable file or folder, or a missing path, produces a non-blocking error and the search goes on; an invalid regular expression is a blocking error with its position.

**Pipeline input.** After a `|`, `grep` processes each object received:
- a file (`FileEntry`, `Path`, `java.io.File`) without `--property`: its **content** is searched, output `GrepMatch` (`ls -r --filter *.java | grep TODO`); a folder is walked only with `-r` (otherwise skipped: `ls -r` already emits its files);
- any other object is **filtered** like `where`: it is emitted unchanged if its text (`toString`, or the value of `--property`) matches (`git log --oneline | grep -i fix`).

With objects in input and no `chemin`, the current directory is not searched. The `chemin` given are searched in any case. In non-interactive mode, a redirected standard input is such an input (FR-04d): `powerj -c "grep TODO . -r"` names the folder explicitly.

**Walk library.** The walk lives in the module **`powerj-fs`** (`io.powerj.fs.FileWalker`), reusable by any cmdlet (`ls -r` uses it; a third-party module can `requires io.powerj.fs`): depth, hidden files, order, parallelism in virtual threads with a bounded number of files open at once, cancellation by Ctrl+C, non-blocking errors. The visitor runs in the worker threads; results and errors are handed over to the cmdlet's thread, which alone calls `emit` / `error`.

CA:
- `grep TODO src -r` displays `path line text` with absolute paths, TODO in red;
- `grep TODO src -r -p 16` gives the same lines (in any file order);
- `grep "<version>(?<v>[^<]+)" pom.xml | map { m -> m.named.v }` displays the version;
- `grep -i fixme --max-depth 2`, `grep -v "^#" config.txt`;
- `grep TODO -r -s` gives one line per file containing `TODO`; `grep TODO -r -s -v` the files without `TODO` (line 0);
- `ls -r --filter *.java | grep TODO` and `git log --oneline | grep -i fix`;
- `ls | grep --property name "^a"` keeps the `FileEntry` whose name starts with `a`;
- `grep "(" x.txt`: `grep: invalid regular expression (: Unclosed group (position 1)`;
- a Latin-1 file: non-blocking error suggesting `--encoding`, then `grep café latin.txt --encoding ISO-8859-1` finds the line.

#### FR-65 — `cat`: read files

```text
cat [chemin...] [-l|--lines | -t|--text | -n|--numbered | -c|--content] [--encoding <charset>] [--property <nom>]
```

| Option | Type | Description |
|---|---|---|
| `chemin` (positional, multiple) | `String` | File(s) to read, in the order given; wildcards on the last element (`*.log`, `src/*.java`), matches in alphabetical order. Default: the pipeline objects. |
| `-l`, `-t`, `-n`, `-c` | boolean | Shape of the output, see below. **Exclusive**: two of them give the blocking error `only one output at a time among --lines, --text, --numbered, --content`. None: `--lines`. |
| `--encoding` | charset | Charset of the files; default **UTF-8**, without fallback: a file that is not valid in this charset produces the non-blocking error `cannot read <file>: not readable as UTF-8 text (choose the charset with --encoding)`. |
| `--property` | name | Property of the pipeline objects that gives the file to read (`String`, `Path` or `File`): `grep TODO -r -s \| cat --property path`. |

Output, according to the option:

| Option | Output |
|---|---|
| `-l`, `--lines` (default) | One `String` per line, without its end-of-line characters (`\n`, `\r\n`). |
| `-t`, `--text` | **One** `String`: the content of all the files, one after the other, end-of-line characters included (like Unix `cat`). |
| `-n`, `--numbered` | One `CatLine` per line. |
| `-c`, `--content` | **One** `CatContent`: the content of all the files, one after the other. |

```java
@Display(columns = {"line", "length", "text"})
public record CatLine(
        Path path,      // absolute path of the file (not displayed by default)
        int line,       // line number, from 1; it goes on from one file to the next
        int length,     // number of characters of text
        String text)    // line without its end-of-line characters
{ }

@Display(columns = {"length", "content"})
public record CatContent(
        int length,     // number of characters of content
        String content) // content of the files, end-of-line characters included
{ }
```

**Several files.** They are read one after the other. The line numbers go on: if the first file has 3 lines, the first line of the second file is line 4. With `--text` and `--content`, a single object holds the content of all the files (`CatContent` has no `path`: it can mix several files).

**Pipeline input.** After a `|`, each object received is read **as if it had been given as a `chemin`**, after the `chemin` arguments (numbering and content go on):
- a `String` is a path, relative to the current directory (no wildcards): `"a.txt" | cat`, `git ls-files | cat -n`;
- a `Path`, a `java.io.File`, or a `FileEntry` (`ls --filter *.txt | cat`) is read; a folder listed by `ls` is skipped;
- any other object: `--property <nom>` names its property that gives the file (`String`, `Path` or `File`); without `--property`, or if the property is not a file, non-blocking error `not a file: …` and the object is skipped. With `--property`, the property is read on every object.

With `--text` and `--content`, the single object is emitted at the end of the pipeline, if at least one file was read. A missing file, a folder given as `chemin`, an unreadable or undecodable file produce a non-blocking error and the reading goes on with the next file. `cat` without `chemin` and without pipeline input is a blocking error `file expected: cat <file>... or ... | cat`.

CA:
- `cat a.txt` displays the lines of `a.txt`;
- `cat a.txt b.txt -n` displays `line length text`; the first line of `b.txt` has the number following the last line of `a.txt`;
- `cat a.txt b.txt -t` gives one string; `(cat a.txt -c).length` the number of characters of the file;
- `ls --filter *.txt | cat -n` and `"a.txt" | cat`;
- `grep TODO -r -s | cat --property path -n`;
- `cat latin.txt`: non-blocking error suggesting `--encoding`, then `cat latin.txt --encoding ISO-8859-1` reads it;
- `cat absent.txt a.txt`: error `cat: not found: …\absent.txt`, then the lines of `a.txt`.

#### FR-36 — `where`: filter objects

```text
where { <expression> }
where <attribut> <opérateur> <valeur>        # short form
```

Evaluates the condition for each received object (lambda parameter, or `$_`) and lets through only those for which it is `true`. Works on records, scalars and therefore on **`String` lines produced by a native command**. The short form `where size > 1mb` is equivalent to `where { $_.size > 1mb }`.

The condition must return a `boolean` (FR-33b); any other value, or an evaluation error, produces a non-blocking error and the object is skipped.

CA:
- `ls -r | where { $_.size > 1mb }`;
- `ls -r | where { f -> f.size > 1mb && !f.dir }`;
- `ls | where { $_.name.endsWith(".java") && !$_.dir }`;
- `ls | where { List.of("png", "jpg").contains($_.ext) }`;
- `git status --porcelain | where { $_.startsWith(" M ") }`;
- `ipconfig | where { $_.contains("IPv4") }`.

#### FR-36c — `map`: transform objects

```text
map { <lambda ou expression> }
map <référence de méthode>
```

Applies the block to each received object and emits the result, like `Stream.map`: `ls -r | map { f -> f.name + " : " + f.name.length() }`, `ls | map FileEntry::name`. A `null` result emits nothing; a collection result is unrolled (FR-30b), like a `flatMap`. An evaluation error is non-blocking (object skipped).

CA:
- `ls -r | map { f -> f.name.length() }`;
- `ls | map { $_.name.toUpperCase() } | where { s -> s.startsWith("P") }`;
- `env | map EnvVar::name`.

#### FR-36d — `collect`: gather objects into a list

```text
collect
```

Gathers all received objects into **a single** unmodifiable **list** (type `io.powerj.api.Collected`, a `List`), like `Stream.toList()`. This is the way to process the whole list after a `|`:

- the result is **always a list**, even when empty or with a single element (a subexpression or an assignment without `collect` yields the object alone when there is only one): `(ls -r | where { f -> !f.dir } | collect).size()`;
- this list is **not unrolled** between two stages (FR-30b): the next stage receives it as a single object, `ls -r | collect | map { l -> l.stream().sorted((a, b) -> Long.compare(b.size, a.size)).limit(5).toList() }` (the result of `map`, an ordinary list, is unrolled again);
- on the other hand, when placed **at the head** of a pipeline (`$l = ls | collect` then `$l | map { f -> f.name }`), it is unrolled like any list: only a list passed from one command to the next stays whole;
- at the end of a pipeline, it is displayed like its elements.

CA:
- `(ls / | where { f -> f.name == "usr" } | collect).size()` is `1`; with no result, `0`;
- `$l = ls | collect` then `$l.size()`, `$l.stream()…`;
- `ls | collect | map { l -> l.size() }` displays a single number;
- Tab after `ls | collect | map { l -> l.` suggests the methods of `List`.

#### FR-36b — `env`: environment variables

```text
env                              # list all variables
env <NOM>                        # a single variable
env --set <NOM>=<valeur>         # create or modify (also: env -s NOM=valeur)
env --unset <NOM>                # remove
env --append <NOM> <valeur>      # append to a list (separator ; on Windows, : elsewhere)
env --prepend <NOM> <valeur>     # same, at the head of the list
```

Output: stream of `record EnvVar(String name, String value)`, sorted by name (names case-insensitive on Windows).

Changes apply to **the PowerJ session environment**: they apply to all native commands launched afterwards, to the lookup of executables in the `PATH` (FR-13) and to the settings read by PowerJ (`POWERJ_NATIVE_ENCODING`…). They are not persisted: to make them permanent, put them in `profile.pj` (§8). Note: `System.getenv()` in a Java expression returns the **initial** environment of the process (a JVM cannot modify its own environment); use `env` for the session environment.

CA:
- `env | where { $_.name.startsWith("JAVA") }`;
- `(env PATH).value.split(";")` lists the folders of the `PATH`;
- `env --append PATH C:\tools` then a tool from `C:\tools` is found and `which` reports it;
- `env --set MAVEN_OPTS=-Xmx2g` then `mvn` receives the variable;
- `env --unset MAVEN_OPTS`.

#### FR-67 — `sort`: sort objects

```text
sort [<clé>...] [-d|--desc] [-i|--ignore-case]
```

| Option | Short | Type | Description |
|---|---|---|---|
| `clé` (position 0, multiple) | — | property, property path or block | Sort keys by priority: ties on a key are broken by the next one. None: the objects themselves. |
| `--desc` | `-d` | boolean | Descending order, for all keys. |
| `--ignore-case` | `-i` | boolean | Compares `String` keys ignoring case. |

A key is a property name (`sort size`, same as `sort { $_.size }`), a property path (`sort path.fileName`), a block (`sort { $_.name.length() }`, `sort { f -> f.modified }`) or a method reference (`sort FileEntry::size`). An invalid property name or a key of another kind is a blocking error.

Output: the received objects, unchanged, in the new order. `sort` emits once its input has ended (it keeps all objects in memory). The sort is **stable**: objects with equal keys keep their order, also with `--desc`.

Comparison of two key values: `null` is smaller than any value (first in ascending order, last with `--desc`, as in PowerShell's `Sort-Object`); numbers by value whatever their class (`3` < `10L` < `10.5`); strings with `compareTo` (or ignoring case with `-i`); other `Comparable`s of the same class (dates, `Path`, enums, booleans) with `compareTo`. Values that cannot be compared (mixed types) are sorted by their text, with a single non-blocking error `cannot compare <type> with <type>: these values are sorted by their text`. An error while evaluating a key is non-blocking: the object is left out.

`sort` is a filter without side effect (allowed in preview mode, FR-66). The native `sort` remains available with `^sort` or `native.prefer=sort` (FR-14, FR-16).

CA:
- `ls | sort size --desc` lists the largest files first;
- `ls | sort dir name` lists files then folders, each in alphabetical order;
- `ls -r | sort { $_.name.length() } -d`;
- `(ls)*.name | sort -i` ignores case;
- `git branch | sort` sorts the lines of a native command;
- `ls | sort nom-invalide!` is a blocking error; `sort` on mixed strings and numbers reports one error and sorts them by text.

#### FR-68 — `first`, `last`, `range`: slices of the pipeline

```text
first [<n>]                      # the first n objects (default 1)
last [<n>]                       # the last n objects (default 1)
range [--from <borne>] [--to <borne>] [--property <nom>] [-i|--ignore-case] [--include|--exclude] [--repeat]
```

`first n` emits the first `n` objects as they arrive, then **stops the previous stage** (FR-68b): `ls -r C:\ | first 3` answers at once. `last n` emits the last `n` objects, in their order, when the input ends. `n` must be ≥ 0.

`range` emits the objects between two bounds, both included by default. Each bound is read as follows:

| Bound | Without `--property` | With `--property <nom>` |
|---|---|---|
| Integer `5` | Position, from 1: `--from 5` starts at the 5th object, `--to 10` ends at the 10th. | Regular expression searched in the property's text. |
| Text `Paul` | Blocking error: add `--property` or use a block. | Regular expression searched in the property's text, as with `grep`: `Paul` (contains), `^Peter$` (equals); `-i` ignores case. |
| Block `{ … }` | Condition on the object (boolean, as with `where`). | Same (the block ignores `--property`). |

| Option | Short | Description |
|---|---|---|
| `--from` | — | Opening bound. Absent: from the first object. |
| `--to` | — | Closing bound, tested from the object **after** the opening one (as in `sed`). Absent: until the end. |
| `--property` | — | Property (or path, `path.fileName`) on which the text bounds are searched. |
| `--ignore-case` | `-i` | The text bounds ignore case. |
| `--include` | — | Default: the opening and closing objects are emitted. |
| `--exclude` | — | The opening and closing objects are not emitted. Not allowed with `--include`. |
| `--repeat` | — | After each closing, waits for the next opening (several ranges). Not allowed with a position `--from`. |

A position `--to` already reached when the range opens (`--from { … } --to 3` opening on the 5th object) closes it at once: only the opening object is emitted, as in `sed`. Without `--repeat`, `range` stops the previous stage as soon as its range is closed. A `null` property never matches. An evaluation error (missing property, block that throws or does not return a boolean) is non-blocking: the object is skipped. The three cmdlets have no side effect (allowed in preview mode, FR-66).

**FR-68b — Stopping the previous stage.** A cmdlet that needs no more objects calls `CmdletContext.stopInput()` (§4.2). The shell then stops calling `process`, calls `end`, and stops the previous stage: a cmdlet is interrupted, and a native command is terminated. Being stopped this way is not an error: no message, `$?` does not change (`git log | first 1` succeeds), and the exit code of a native command stopped this way is not recorded in `$exit`.

CA:
- `ls -r C:\ | first 3` displays 3 entries at once; `ls | first` displays the first entry;
- `ls | sort size | last 2` displays the two largest files;
- `cat a.log | range --from 3 --to 5` displays lines 3 to 5; `cat a.log | range --from 5` skips the first 4;
- `List.of("a", "Paul", "b", "Peter", "c") | range --from { $_ == "Paul" } --to { $_ == "Peter" }` displays `Paul b Peter`; with `--exclude`, `b`;
- `ls | range --from ^a --to ^d --property name` displays the entries from the first name starting with `a` to the next one starting with `d`;
- `env | range --from JAVA --to PATH --property name -i`;
- `cat app.log | range --from { $_.contains("BEGIN") } --to { $_.contains("END") } --repeat` displays every BEGIN…END block;
- `ls | range --from Paul` is a blocking error that suggests `--property`;
- `git log --oneline | first 5` then `$?` displays `true`.

#### FR-69 — `select`: choose and compute columns

```text
select <colonne>...
```

Each received object gives a **`Row`** (`io.powerj.api.Row`): an immutable, ordered `Map` of column name → value. Downstream, a column is read like any property (`$_.kb`, `sort kb`, `where { $_.kb > 10 }`), and a `Row` is never unrolled (FR-30b).

| Column | Example | Name | Value |
|---|---|---|---|
| Property | `name` | `name` | `$_.name` |
| Property path | `path.fileName` | `fileName` (last segment) | `$_.path.fileName` |
| Renamed property | `file=name` | `file` | `$_.name` |
| Computed column | `kb={ $_.size / 1kb }` | `kb` | result of the block |
| Unnamed block | `{ $_.name.length() }` | `column1`, `column2`… | result of the block |

Column names are identifiers. No column, an invalid name or a duplicate name is a blocking error.

A property missing on an object, or a block that fails, gives an empty cell (`null`). The `Row` is still emitted, and **one** non-blocking error is reported per column (`select: column nmae: …`), not one per object.

Display: consecutive `Row`s with the same columns form one table, whatever the number of columns. Numbers are right-aligned. A column taken directly from a `@Bytes` component (`select name size` after `ls`) is still shown as a size (`14.2 KB`).

Tab after `ls | select name size | where { $_.` proposes the selected columns. `select` is allowed in preview mode (FR-66). The `--expand` option of the backlog is not part of v1.

CA:
- `ls | select name size` displays a two-column table, sizes in readable form;
- `ls | select file=name kb={ $_.size / 1kb }` displays the columns `file` and `kb`;
- `ls | select name kb={ $_.size / 1kb } | sort kb -d | first 3`;
- `ls -r | select path.fileName size` displays a column `fileName`;
- `ls | select nmae` displays an empty column and a single error;
- `ls | select` and `ls | select a name a=size` are blocking errors.

#### FR-70 — `count`, `measure`: count and aggregate

```text
count
measure [<clé>]
```

`count` emits one number: the objects received (`ls -r | count`). With no input it emits `0`.

`measure` emits one `Row` (FR-69) with the columns `count sum average min max`, computed as the objects arrive (constant memory) and emitted when the input ends. Without a key it measures the objects themselves; a key is a property, a property path or a block, as for `sort` (`ls -r | measure size`, `env | measure { $_.value.length() }`). The values are read like any `Row`: `(ls -r | measure size).sum`.

- `count`: the values measured; a `null` value is left out.
- `sum`, `average`: when all values are numbers. The integral sum is exact (`Long`, `BigInteger` beyond); a decimal value gives a `Double` sum. Otherwise (strings, dates…) both are empty, without error.
- `min`, `max`: compared with the rules of `sort` (FR-67), so dates and strings have a minimum and a maximum (`ls | measure modified`).
- When the key is a size property (`@Bytes`, `measure size` after `ls`), `sum`, `average`, `min` and `max` are shown as sizes.
- An error while evaluating the key is non-blocking: the object is not measured.

Both cmdlets have no side effect (allowed in preview mode, FR-66).

CA:
- `ls -r | count`;
- `ls -r | measure size` displays the count and the total, average, smallest and largest sizes in readable form;
- `(ls -r | measure size).sum` gives the total in bytes;
- `ls | measure modified` gives the oldest and newest dates;
- `List.of(1, 2, 3) | measure` gives `count 3`, `sum 6`, `average 2.0`.

#### FR-71 — `group`: group objects by key

```text
group [<clé>...] [-n|--no-items]
```

Emits one **`Group`** record per distinct key, when the input ends, in the order the keys first appear:

```java
@Display(columns = {"key", "count"})
record Group(Object key, long count, List<Object> items)
```

- A key is a property, a property path or a block, as for `sort` (FR-67): `group ext`, `group path.parent`, `group { $_.size > 1mb }`. With no key, the objects themselves are grouped (`cat a.log | group`). With several keys (`group ext dir`), the key of a group is the list of the values (`[java, false]`).
- Keys are compared with `equals`; integral numbers are compared by value (`1` and `1L` fall in the same group). A `null` key forms its own group.
- `items` holds the objects of the group, in their input order. `-n`/`--no-items` keeps only the count (`items` is empty), so that very large inputs do not fill memory.
- An error while evaluating a key is non-blocking: the object is in no group.

The groups are displayed as a table `key count`. Their properties are completed by Tab and usable downstream: `ls -r | group ext | sort count -d | first 5`, `(ls -r | group ext)[0].items`. `group` is allowed in preview mode (FR-66).

CA:
- `ls -r | group ext | sort count -d | first 5` displays the five most frequent extensions;
- `ls -r | group ext dir` groups by extension and kind;
- `ls -r | group ext -n` displays the same counts, with empty `items`;
- `(ls | group dir)[0].items` gives the objects of the first group;
- `cat app.log | group | where { $_.count > 1 }` displays the repeated lines;
- `ls | group bad!` is a blocking error.

#### FR-72 — `mkdir`, `touch`, `cp`, `mv`, `rm`: file operations

```text
mkdir <chemin>... [-p|--parents] [-n|--dry-run]
touch <chemin>... [-n|--dry-run]
cp <source>... <destination> [-r|--recurse] [-f|--force] [-v|--verbose] [-n|--dry-run]
mv <source>... <destination> [-f|--force] [-v|--verbose] [-n|--dry-run] [--allow-protected]
rm <chemin>... [-r|--recurse] [-f|--force] [-v|--verbose] [-n|--dry-run] [--allow-protected]
```

The paths are arguments (wildcards on the last element: `rm *.tmp`) or the objects received from the pipeline, read as by `cat` (`String`, `Path`, `File`, `FileEntry`, or `--property <nom>`): `ls --filter *.tmp | rm`. For `cp` and `mv`, the last argument is the destination; with several sources, or an existing folder as destination, each source goes into it under its own name (`ls *.txt | cp backup/`).

A **block** `{ … }` instead of a path is evaluated for each object: for `mkdir`, `touch` and `rm`, its result (`String`, `Path`, `File`, `FileEntry`) is the path to create or remove, instead of the object itself (`ls ../src --files | touch { $_.name }` creates the same names in the current folder, without touching the files of `../src`); for `cp` and `mv`, a block as destination gives the destination of each source, its `$_` being the object received or the `FileEntry` of a source argument (`cp *.txt { $_.name + ".bak" }`; a folder result receives the source under its name). A `null` result skips the object without error (`ls | rm { $_.ext == "tmp" ? $_.name : null }`). One block at most, never with `--property`; for `mkdir`, `touch` and `rm` it needs a pipeline input.

- `mkdir` creates folders (`-p`: with their missing parents, and an existing folder is not an error); `touch` creates an empty file or sets the modification time of an existing one. Both emit the `FileEntry` created or updated.
- `cp` copies (`-r` for folders), `mv` moves or renames (atomically when possible; across file systems, a copy then a removal, the source being removed only once the copy has succeeded), `rm` removes (`-r` for a non-empty folder; `-f`: a missing path is not an error). They emit nothing, except with `-v`: the `FileEntry` of each destination, or of each removed entry.
- `cp` and `mv` never overwrite an existing target without `-f`; copying a folder into itself is refused.
- Symbolic links are never followed: `rm` removes the link, `cp` copies the link.
- `-n`/`--dry-run` changes nothing and emits one `FileOperation(action, path, target)` per action that would be done.
- Each error (not found, exists, is a folder, parent missing, access denied, protected path) is non-blocking: the other paths are processed.
- None of these cmdlets runs in preview mode (FR-66).

**Protected paths.** `rm`, and `mv` for its sources, refuse a path that is protected or contains a protected path (removing `C:\Users` would remove the home folder), as well as the current folder and its parents: `rm: protected path: C:\Windows (remove it anyway with --allow-protected)`. The list is the file `protected-paths` of the configuration folder (§8): one path per line, `~` for the home folder, `#` for comments; it is read at each execution. The shell creates it at first launch with the defaults of the platform (file-system roots, `~`, the configuration folder, and the system folders: `C:\Windows`, `C:\Program Files`…, or `/etc`, `/usr`…); if it is missing, the same defaults apply. `--allow-protected` lifts the protection for one command.

The native programs of the same name remain available with `^` or `native.prefer` (FR-14, FR-16).

CA:
- `mkdir -p a/b/c` then `touch a/x.txt` display the created entries;
- `cp a/x.txt a/y.txt`, then again: error `exists` without `-f`;
- `cp -r a b`, `mv b c`, `rm -r c`;
- `ls --filter *.txt | rm -v` displays the removed files;
- `rm -r -n a` lists what would be removed and removes nothing;
- `rm ~` and `rm -r ..` are refused; `rm --allow-protected` lifts the protection;
- `rm docs` on a non-empty folder is refused without `-r`;
- `ls ../src --files | touch { $_.name }` creates `a.txt`… in the current folder and leaves `../src` unchanged; `cp *.txt { $_.name + ".bak" }` makes a `.bak` copy of each file.

**FR-72b — Parallel `cp -r` and `rm -r`.** `cp` and `rm` accept `-p`/`--parallel <n>` (`int` ≥ 1, default `1`: sequential). Inside a tree given with `-r`, up to `n` entries are copied or removed at the same time, in **virtual threads**:

```text
cp <source>... <destination> [-r|--recurse] [-p|--parallel <n>] [-f|--force] [-v|--verbose] [-n|--dry-run]
rm <chemin>... [-r|--recurse] [-p|--parallel <n>] [-f|--force] [-v|--verbose] [-n|--dry-run] [--allow-protected]
```

- **Result**: the same as in sequential mode, with the same checks (protected paths, existing targets…) and the same errors.
- **Order**:
  - `rm` removes a folder only once its content is removed, and `cp` creates a folder before copying its content;
  - apart from that, the order of the `-v` output and of the errors is free, as for `grep -p`.
- **Links** are never followed, and hidden files are included.
- **Resources**: at most `n` operations run at once, `emit` and `error` happen only in the cmdlet's thread, and Ctrl+C stops every thread.
- **Scope**:
  - `-p` has no effect on a single file;
  - the paths received from the pipeline are still processed one after the other;
  - `mv` stays sequential;
  - `rm -r -n -p <n>` runs the same parallel walk without removing anything: its listing reflects the parallelism (free order, a folder after its content).
- **When it helps**: on SSDs and network drives with many files. On a spinning disk it can be slower, which is why it is off by default.
- The walk lives in `powerj-fs` (`io.powerj.fs.FileTrees`), next to `FileWalker`.

CA:
- `rm t -r -p 16` and `cp t t2 -r -p 16` give the same result as without `-p`;
- `rm t -r -p 4 -v` lists every removed entry, each folder after its content;
- `rm t -r -p 0` is refused: `--parallel expects a number of threads of at least 1, got 0`;
- Ctrl+C during `rm big -r -p 8` returns the prompt.

#### FR-73 — `to-json`, `from-json`, `to-csv`, `from-csv`: JSON and CSV

```text
to-json [-c|--compact] [-l|--lines] [--depth <n>]
from-json [--property <nom>]
to-csv [-d|--delimiter <char>] [--no-header]
from-csv [-d|--delimiter <char>] [--no-header] [--property <nom>]
```

No external library: the writers and parsers are part of PowerJ. The four cmdlets change nothing on disk and run in preview (FR-66).
- **No file name.** To write a file, redirect their text: `ls | select name size | to-json > files.json`.
- **Pipeline only.** To read one, pipe it: `cat files.json | from-json`. A `String` received is always text, never a file name. With `--property <nom>`, that property of each object received holds the text: `cat files.json -c | from-json --property content`.

**`to-json`** turns the objects received into JSON text.
- **Output**: by default, one `String` emitted at the end, holding an indented array of all the objects, **always an array**, even for a single object. `--compact` writes it on one line, and `-l`/`--lines` emits one compact `String` per object as they arrive (JSON Lines).
- **Mapping**:
  - a record becomes an object of all its components (a `@Bytes` size stays a number of bytes);
  - a `Row` or a `Map` becomes an object;
  - collections, arrays and streams become arrays;
  - numbers and booleans stay as they are; `NaN` and infinities become `null`;
  - dates are written as ISO-8601 strings, and enums, paths and other objects as their text.
- `--depth` (default 10) limits the nesting: deeper values are written as their text, with one non-blocking error.

**`from-json`** reads JSON text: the `String` objects received, joined with line breaks (`cat data.json | from-json`), or their `--property`.
- The text may hold several values one after the other (JSON Lines); each one is emitted as soon as it is complete.
- A top-level array is unrolled.
- An object gives a `Row` (FR-69), keys in order; an inner array gives a `List`.
- Integers give `Long` (`BigInteger` beyond), other numbers `Double`.
- Invalid JSON gives a non-blocking error with its position (`invalid JSON at line 3, column 7: expected ':' after a key`), and reading resumes at the next value.

**`to-csv`** emits CSV (RFC 4180), one `String` per line, as the objects arrive: the header, then one line per object.
- **Columns**: those of the first object (record components, `Row` or `Map` keys, or `value` for a scalar). A missing column gives an empty field.
- **Fields**:
  - they are quoted when they contain the delimiter, a quote or a line break;
  - numbers use `.` and no exponent;
  - nested values are written as compact JSON.
- `--delimiter` takes one character or `tab` (`;` for Excel in French). `--no-header` omits the header.

**`from-csv`** reads CSV, either the `String` lines received or their `--property`, and emits one `Row` per record as soon as it is complete.
- **Header**: the first record gives the column names. With `--no-header`, the columns are `c1`, `c2`…
- **Lines**:
  - a quoted field may span several lines;
  - empty lines and a UTF-8 byte order mark are skipped;
  - a record with too many fields gives a non-blocking error, and a record with too few fields gets `null` values.
- **Values**: always `String`, as in PowerShell's `Import-Csv` (`007` stays `007`). An empty field is `""`, and a missing column is `null`.

CA:
- `ls | select name size | to-json` displays an indented array; `ls | to-json -l` one line per file;
- `ls a.txt | select name size | to-json` gives `[ { … } ]`;
- `ls | select name size | to-json > f.json`, then `cat f.json | from-json | where { $_.size > 1kb }`;
- `cat f.json | from-json` gives the same rows; `"[1, 2]" | from-json` gives `1` and `2`;
- `"{\"a\": 1,}" | from-json`: error `invalid JSON at line 1, column 9: …`;
- `ls | select name size | to-csv -d ";" > f.csv`, then `cat f.csv | from-csv -d ";"` gives the same names, with the sizes as strings;
- `cat f.json -c | from-json --property content` reads the content of a `CatContent`; `"data.json" | from-json` is invalid JSON;
- a CSV field with a `,`, a `"` and a line break survives `to-csv | from-csv`.

#### FR-74 — `ps`, `kill`: processes

```text
ps [nom...] [-u|--user <utilisateur>]
kill [pid...] [-f|--force] [-w|--wait] [-v|--verbose] [-n|--dry-run]
```

Both cmdlets use the JDK (`ProcessHandle`), without native calls, and behave the same on every platform. The native programs remain available with `^ps`, `^kill` or `native.prefer` (FR-14, FR-16).

**`ps`** lists the processes, sorted by pid, as `ProcessEntry` objects:

```java
@Display(columns = {"pid", "name", "user", "cpu", "start"})
public record ProcessEntry(long pid, Long parent, String name, String command, String commandLine,
                           String user, Instant start, Duration cpu) { }
```

- **Filters**:
  - `nom` keeps the processes whose name matches one of the patterns: wildcards, case ignored, `.exe` optional on Windows (`ps notepad`);
  - `-u` keeps those of a user.
- **Unknown values**: information the system does not give is `null`. On Windows without administrator rights, this is the case for the command and the user of other users' processes.
- **Limitations**: the memory and the state of a process are not available from the JDK.
- `ps` runs in preview (FR-66).

**`kill`** stops the processes given by pid, or received from the pipeline: `ProcessEntry`, `ProcessHandle`, `Process`, or an integer (`ps notepad | kill`).
- **Stop mode**: without `-f`, a normal stop (`SIGTERM` on Unix); with `-f`, a forcible one (`SIGKILL`). On Windows, every stop is forcible. Other signals (`HUP`, `STOP`…) are not available: use the native program (`^kill -HUP 1234`).
- **Output**:
  - `kill` returns without waiting, and `-w` waits for each end;
  - it emits nothing, except with `-v` (the processes stopped) or `-n` (the processes that would be stopped, without stopping them).
- **Errors** (non-blocking, the other targets are processed):
  - a pid that does not exist;
  - a process that cannot be stopped;
  - PowerJ itself: the JDK forbids it, `cannot stop PowerJ itself (use exit)`.
- The parents of PowerJ (the terminal) are stopped like any other process.
- `kill` never runs in preview.

CA:
- `ps | first 5` displays `pid name user cpu start`; `ps java` lists the Java processes, PowerJ included;
- `ps java | sort cpu --desc | first 3`;
- a process started for the test: `ps <nom> | kill -n` lists it and leaves it alive; `ps <nom> | kill -w -v` stops it and displays it;
- `kill 999999`: `kill: no process 999999`; `kill (ProcessHandle.current().pid())`: `kill: cannot stop PowerJ itself (use exit)`.

### 3.10 Native commands

**FR-37 — Principle: streams stay streams.** A native command is **not** wrapped in an object: its standard output and its error output are handled as streams, the way a classic shell does.

| Situation | stdout | stderr |
|---|---|---|
| **Last stage** at the REPL (`git log`) | Inherited directly from the terminal: colors, paging, interactive programs (`vim`, `ssh`, `python`) work. | Inherited from the terminal. |
| **Stage followed by a cmdlet** (`git status \| where …`) | Converted into a **stream of `String` lines** (decoding per FR-40b), streamed. | **PowerJ error stream** (displayed in red), never mixed with the objects. |
| **Assignment** (`$l = ipconfig`) | Captured as a list of `String` lines. | PowerJ error stream. |
| **Native → native** (`^cat a.txt \| ^sort`) | Bytes passed **directly** from one process to the other, without decoding (preserves encoding and binary data). | PowerJ error stream. |
| **Cmdlet → native** (`ls \| ^more`) | Objects are converted to text (displayed form) and written to the process's stdin. | — |

Applicable redirections (FR-34): `git log > log.txt`, `git badcmd 2> err.txt`, `cmd 2>&1 | where …`.

**FR-38 — Execution metadata.** After each native command, PowerJ records (without injecting them into the stream):

```java
public record NativeRun(
        String command,     // absolute path of the executable
        List<String> args,
        long pid,
        int exitCode,
        Duration duration) { }
```

accessible via `$last`; `$exit` is `$last.exitCode`; `$?` is `true` if the code is 0. A non-zero code is **not** a blocking error.
- CA: `^cmd /c "exit 3"` then `$exit` displays `3`; `$last.duration` displays the duration.

**FR-39b — Interactive console programs.** A native command whose standard input is the console (vim, nano, ssh, python, `git commit` opening an editor…) reads the keyboard **alone**: while it runs, the line editor stops reading the console (JLine `Terminal.pause`/`resume`), then reads it again at the next prompt. The line editor's reading thread is asked to stop at its next poll (at most 100 ms, during the program's start-up), never interrupted: an interrupt could make JLine close the terminal. Without this, under Windows the line editor's reading thread would take some of the keys typed for the program and turn Ctrl+C into a cancellation of the program.
- CA (Windows): in `vim notes.txt`, every key typed once is received; Ctrl+C inside vim does not quit vim; `ping -t localhost` then Ctrl+C stops ping and returns to the prompt without quitting PowerJ; the prompt accepts input normally afterwards.

**FR-39 — Graphical applications.** A Windows executable of the GUI subsystem (detected by reading the PE header) is launched **detached**: the shell returns control immediately, `$last.pid` is set, `$exit` is `null`.
- CA: `notepad` opens Notepad and the prompt comes back right away.

**FR-40b — Native command encoding.** Text exchanged with native commands (stdout/stderr decoded into `String`, objects written to stdin) uses the encoding defined by the **`POWERJ_NATIVE_ENCODING` environment variable**:

| Value | Effect |
|---|---|
| *(not set)* or `auto` | Default: output code page of the Windows console (e.g. `cp850` on a French Windows); UTF-8 on Linux/macOS. |
| a Java charset name (`UTF-8`, `cp850`, `windows-1252`…) | Used for all native commands. |

A program-specific value can be given by **`POWERJ_NATIVE_ENCODING_<NOM>`**, where `<NOM>` is the executable name in uppercase, without extension: `POWERJ_NATIVE_ENCODING_GIT=UTF-8` (git produces UTF-8 whereas `ipconfig` uses the console code page). The variable is set in the Windows environment or in the session with `env --set` (FR-36b), and applies from the next command onward. An invalid charset name produces an explicit error when the command is launched. The native → native stream is never decoded (FR-37).
- CA: on a French Windows, `ipconfig | where { $_.contains("Adresse") }` displays accented characters correctly without any configuration; `env --set POWERJ_NATIVE_ENCODING_GIT=UTF-8` then `git log --oneline | where { $_.contains("é") }`.

**FR-40 — Arguments.** Arguments are passed as is after variable and wildcard expansion (wildcard expansion on existing paths, which can be disabled by putting the argument in quotes). On Windows, the command line is built according to the quoting rules of `CommandLineToArgvW`.

### 3.11 Errors

**FR-41 — Two categories.**
- **Non-blocking**: reported on the error stream, the pipeline continues (inaccessible file during `ls -r`).
- **Blocking**: stops the whole pipeline (syntax error, unknown command, invalid option, unexpected exception).

Errors are kept in `$errors` (last 50). Format: `ls: access denied: C:\System Volume Information` (in French `ls : accès refusé : …`, FR-61).

**FR-42 — Common `--on-error` option.** Every cmdlet accepts `--on-error stop|continue|silent` (default `continue`) to change how non-blocking errors are handled.

**FR-43 — Debug mode.** `powerj.exe --debug` (or `$debug = true`) displays the full Java stack trace of unexpected errors; otherwise a short message is displayed.

**FR-44 — Language.** Messages in English or French: see FR-61 (§3.15).

### 3.12 Help

**FR-45 — `help`.** `help` lists cmdlets by category with their summary; `help ls` displays the synopsis, the options, the output record and examples; `ls --help` is equivalent. This information is generated from the cmdlet's metadata (annotations), and is therefore available for third-party cmdlets.

### 3.13 Java interoperability

PowerJ gives direct access to **the entire public Java API** available in the runtime: static methods, constructors, fields and instance methods. The result is an ordinary Java object that fits into the pipeline (FR-27, FR-30b).

**FR-46 — Static calls.** A qualified name followed by parentheses calls a static method; without parentheses, it reads a static field or denotes the class:

```text
PJ> java.util.List.of("apple", "banana", "orange")
apple
banana
orange
PJ> java.lang.Math.max(3, 7)
7
PJ> java.lang.Math.PI
3.141592653589793
PJ> java.time.DayOfWeek.MONDAY
MONDAY
```

**Lexical rule (disambiguation from commands).** In command position, a word of the form `ident(.ident)+` is a **Java expression** if it is **immediately** followed by `(` (no space), or if it denotes a class or a static field of a known class. Otherwise, command resolution (FR-13) applies. Thus `java -version` and `notepad.exe fichier.txt` remain native commands, while `java.lang.Math.max(1, 2)` is a Java call. When in doubt, an expression can always be put in parentheses: `(Math.max(1, 2))`.

**FR-47 — Default imports.** The most useful JDK packages are **imported automatically**: their classes can be used directly by their simple name, without `import`.

| Area | Packages imported by default |
|---|---|
| Core | `java.lang`, `java.math`, `java.text` |
| Collections and streams | `java.util`, `java.util.function`, `java.util.stream`, `java.util.regex`, `java.util.concurrent` |
| Files and I/O | `java.io`, `java.nio.file`, `java.nio.charset` |
| Networking | `java.net`, `java.net.http` |
| Dates | `java.time`, `java.time.format` |

These packages contain no duplicate class names (verified against the JDK), so there is no conflict. Packages likely to create conflicts (`java.awt` with `List`, `java.sql` with `Date`…) are not imported by default, but remain usable by their fully qualified name (`java.sql.Date`) or through an explicit `import`.

**Explicit imports.** `import java.security.*` or `import javax.crypto.Cipher` adds imports for the rest of the session (or from `profile.pj`). `import` without an argument lists the active imports. A simple name that has become ambiguous (two imports) produces an error listing the candidates.

```text
PJ> LocalDate.now().plusDays(10).dayOfWeek
FRIDAY
PJ> Files.readString(Path.of("notes.txt")).lines().count()
42
PJ> HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("https://example.com")).build(), HttpResponse.BodyHandlers.ofString()).statusCode()
200
PJ> new BigDecimal("0.1").add(new BigDecimal("0.2"))
0.3
```

**FR-48 — Instantiation.** `new Classe(args)` calls a public constructor: `new java.io.File("C:\\temp")`, `new StringBuilder("ab").reverse()`.

**FR-49 — Instance calls.** Every value exposes its public methods: `$l.size()`, `"abc".toUpperCase().length()`, `$f[0].path.toFile().length()`, `(ls)[0].modified.atZone(ZoneId.systemDefault()).year`. Properties (FR-28) and calls can be combined freely. A method call can appear anywhere an expression is expected, including inside the `{ }` blocks of `where`.

**FR-50 — Overload resolution and conversions.**
- PowerJ values are converted to the parameter types: integer → `int`/`long`/`short`/`byte`/`Integer`/`Long`/`BigInteger` (if lossless); decimal → `double`/`float`/`BigDecimal`; string → `String`, `CharSequence`, `char` (if 1 character), `Path`, `File`, enum (by name); PowerJ list → `List`, `Collection`, array; size (FR-19) → `long`; duration → `Duration`.
- **varargs** supported (`List.of("a", "b", "c")`, `String.format("%s-%s", 1, 2)`).
- Among the applicable overloads, the most specific one is chosen (rules close to JLS §15.12); in case of ambiguity, an error lists the candidate signatures; no candidate → an error lists the existing overloads.
- An explicit conversion is possible with a cast: `[long] 5`, `[java.util.ArrayList] $l` (checked at run time).

**FR-51 — Lambdas and method references to functional interfaces.** A lambda (FR-33b), a block or a method reference passed to a parameter whose type is a functional interface (`Predicate`, `Function`, `Comparator`, `Runnable`, `Supplier`…) is converted automatically:
- the number of lambda parameters must match that of the abstract method; a block with no declared parameter receives its single argument in `$_`;
- the value is converted to the return type of the abstract method (FR-50); `boolean` requires a boolean;
- a method reference is resolved at call time, based on the number of arguments received.

```text
PJ> $l = List.of("apple", "banana", "kiwi")
PJ> $l.stream().filter(s -> s.length() > 4).map(String::toUpperCase).toList()
APPLE
BANANA
PJ> $m = new ArrayList($l); $m.sort((a, b) -> a.length() - b.length()); $m
kiwi
apple
banana
```

**FR-52 — Scope and security.**
- Accessible by default: **`public`** classes and members of **the whole Java SE standard library**, i.e. all packages exported by the runtime's `java.*` modules (the `java.se` aggregate, see §7): `java.base` (lang, util, io, nio, net, math, time, text, security…), `java.net.http`, `java.sql`, `java.xml`, `java.desktop`, `java.logging`, `java.management`, `java.prefs`, `javax.crypto`, `javax.net.ssl`, etc.
- **No external library** in interop: direct calls are limited to the JDK. A need that requires a third-party library (Apache Commons, database client…) is handled by **writing a cmdlet** (§4) that bundles that library. Classes from third-party modules are not exposed in Java expressions.
- No forced reflective access (`setAccessible`), and no access to internal packages (`jdk.internal.*`, `sun.*`).
- `default` interface methods and inherited methods are accessible normally; the call goes through the public interface when the implementation class is not exported (e.g. `List.of(...)` returns an internal class, its methods are called via `java.util.List`).

**FR-53 — Exceptions.** An exception thrown by a Java call is a **blocking error** displayed in short form: `java.lang.NumberFormatException : For input string: "x"` (full stack trace in `--debug` mode). The exception object is kept in `$errors` (`$errors[0].cause`, `$errors[0].stackTrace`).

**FR-54 — Introspection.**
- `help members $x`: public methods (with signatures), derived properties (record components, getters, fields) of the actual type of `$x`.
- `help java.util.List` (or `help List` after import): constructors, static and instance methods, fields.
- `$x.getClass()` remains available.

**FR-55 — Session examples.**

```text
PJ> Files.readAllLines(Path.of("notes.txt")) | where { $_.contains("TODO") }
PJ> Files.size(Path.of("gros.iso")) / 1mb
PJ> new java.io.File("C:\\Windows").listFiles() | where { $_.directory && $_.name.startsWith("S") }
PJ> java.util.UUID.randomUUID()
PJ> java.net.InetAddress.getLocalHost().hostAddress
PJ> String.join(", ", (ls)*.name)
PJ> (ls -r | where size > 1mb).size()
```

### 3.14 Robustness

Principle: **nothing that a line executes can bring the shell down.** A command always ends with one of four outcomes, and the REPL loop never sees an exception.

**FR-56 — Execution outcome.** Each line is executed by a **supervisor** that returns a result of a sealed type:

```java
sealed interface Outcome {
    record Success(List<Object> values)        implements Outcome { }
    record Failure(PjError error)              implements Outcome { }
    record Cancelled()                         implements Outcome { }   // Ctrl+C
    record Abandoned(String commandLine)       implements Outcome { }   // was no longer responding
}
```

The REPL loop boils down to `switch (supervisor.run(line))` over these four cases (display, error message, `^C`, warning). The supervisor catches **any `Throwable`**, including:

| Problem | Handling |
|---|---|
| Java exception (cmdlet, Java call) | Short blocking error (FR-53), object in `$errors`. |
| `StackOverflowError` (infinite recursion) | Error `recursion too deep`; the interpreter also limits its own evaluation depth. |
| `OutOfMemoryError` | A **memory reserve** allocated at startup is released so the shell can continue; the command's objects are released; a message advises filtering earlier in the pipeline. The reserve is reallocated afterwards. |
| `LinkageError`, `ExceptionInInitializerError` | Blocking error with the name of the offending class. |
| PowerJ internal error (bug) | Short message + full stack trace written to `~/.powerj/logs/powerj.log`. |

**FR-57 — Cancellation with Ctrl+C, in three levels.** All stages of a line run in the same structured concurrency scope (§5.3), which makes it possible to cancel everything at once.
1. **Cooperative (immediate)**: a cancellation token (passed via `ScopedValue`) is checked by the interpreter at each evaluated node and at each `{ }` block call, by the pipeline between two objects, and by cmdlets (`ctx.cancelled()`). This covers shell loops and Java calls that call back into a block (`Stream.iterate(0, { $_ + 1 }).forEach(...)`).
2. **Interruption**: the command's threads are interrupted (`Thread.interrupt`), which unblocks I/O, `sleep`, `HttpClient`, and queues. **Native processes** and all their descendants (`ProcessHandle.descendants()`) are stopped, then forcibly killed if they do not stop.
3. **Abandonment**: if the command still has not stopped — typically JDK code that does not check for interruption, such as a catastrophic regular expression or a giant sort — a **second Ctrl+C** abandons it: the shell returns control with the warning `command abandoned, it keeps running in the background: <line>`, its output is ignored, and it is stopped when the shell closes. (Java does not allow forcibly killing a thread; abandonment is the only safe way out.)

**FR-58 — Java calls that are dangerous for the shell.** Some JDK methods would act on the shell itself rather than on the command. They are **intercepted during call resolution** (§5.4), without any additional security mechanism:

| Call | Handling |
|---|---|
| `System.exit(n)`, `Runtime.getRuntime().exit(n)`, `Runtime.getRuntime().halt(n)` | Equivalent to the `exit n` command (clean shutdown, history saved). |
| `System.setOut(…)`, `System.setErr(…)`, `System.setIn(…)` | Refused, with an explanatory message (would break the terminal display). |

Java code that writes to `System.out` / `System.err` (`System.out.println("x")`) is displayed normally, without corrupting the line being typed: at startup, these streams are connected to the JLine terminal.

**FR-59 — Terminal and history always restored.** History is written after each command (FR-09), so an abrupt stop loses nothing. On exit, including on a fatal JVM error or when the window is closed, a shutdown hook restores the terminal to its initial state (raw mode disabled, colors reset).

**FR-60 — Diagnostic log.** Internal errors and warnings are logged to `~/.powerj/logs/powerj.log` (rotation, 5 files maximum), in English whatever the language of the messages (FR-61). `--debug` also displays these details on screen.

### 3.15 Language (internationalization)

**FR-61 — Language.** PowerJ displays its messages (errors, warnings, help, completion descriptions, `which`, `mod-list`, number formatting) in **English** or **French**. Supported languages: `en` and `fr`; any other value, or any other system language, gives English.

The language is resolved once at startup, in this order (the first non-blank value wins):

| Priority | Source | Example |
|---|---|---|
| 1 | System property `powerj.language` | `-Dpowerj.language=fr` (tests, embedding; the Maven tests force `en`) |
| 2 | Environment variable `POWERJ_LANG` | `set POWERJ_LANG=en` (Windows), `POWERJ_LANG=fr powerj` (Linux/macOS) |
| 3 | Key `language` of `config.properties` (§8) | `language=en` in `~/.powerj/config.properties` (or `$POWERJ_HOME/config.properties`) |
| 4 | Display language of the system (`Locale.Category.DISPLAY`) | French system → `fr`; anything else → `en` |

A value is matched on its prefix: `fr`, `fr_FR`, `FR` give French; `en`, `de`… give English. The API class `io.powerj.api.Language` performs this resolution (`Language.current()`).

**Message files.** Texts are not in the code but in `ResourceBundle` files, one pair per package, in UTF-8, with positional placeholders `{0}`, `{1}`… (no `MessageFormat` escaping: apostrophes are written as is):

| Package | Files | Contents |
|---|---|---|
| `io/powerj/api` | `messages_en.properties`, `messages_fr.properties` | Messages of the public API (non-boolean block) |
| `io/powerj/core/lang` | idem | Syntax errors (`syntax: …`) |
| `io/powerj/core/exec` | idem | Execution errors, built-in commands, options, help, completion, Java interop |
| `io/powerj/cmdlets` | idem | Built-in cmdlets (summaries, options, categories, errors) |
| `io/powerj/shell` | idem | REPL, history, startup, configuration |
| `com/example/greet` | idem | Example module (§4.3) |

Third-party modules translate their cmdlets with `CmdletProvider.messages(Locale)` (§4.2).

Language-dependent formatting: the error prefix (`ls: not found: x` / `ls : introuvable : x`, French typography puts a space before `:`), the decimal separator of sizes (`14.2 KB` / `14,2 KB`), built-in categories in `help` (`Files`, `System`, `Filters` / `Fichiers`, `Système`, `Filtres`).

**What is not translated:**
- the **input syntax**: keywords and built-in command names (`where`, `exit`, `true`, `null`, `now`, `new`, `import`, `help members`…), cmdlet and option names (`--recurse`), units (`10kb`, `7d`);
- **option values** (`--on-error stop|continue|silent`, enum constants, `true`/`false`);
- Java names (classes, methods, properties such as `name`, `size`), the messages of Java exceptions (produced by the JDK in its own language) and the outputs of native programs;
- the **diagnostic log** `~/.powerj/logs/powerj.log` (FR-60), always in English so that it can be shared in a bug report.

CA:
- with `POWERJ_LANG=en`, `foo` displays `unknown command: foo`; with `POWERJ_LANG=fr`, `commande inconnue : foo`;
- `language=fr` in `config.properties` without `POWERJ_LANG`: French; `POWERJ_LANG=en` set as well: English (the variable takes precedence);
- without any setting, a French Windows displays French, an English one English;
- `help ls` shows `Options:` and `(required)` in English, `Options :` and `(obligatoire)` in French;
- `greet -n Yves` (example module) displays `Hello Yves!` in English, `Bonjour Yves !` in French.

---

## 4. Extension API (third-party cmdlets)

### 4.1 Principles

- The Maven module **`powerj-api`** is the **only dependency** needed to write a cmdlet. It is published separately and semantically versioned.
- A cmdlet is a class that implements `Cmdlet<P, I, O>`; the output type `O` is free (**a record is recommended** to benefit from table display and attribute completion).
- A cmdlet that changes nothing (no file written, no environment changed, no network side effect) declares `@CmdletInfo(preview = true)`, so that it runs in preview mode (FR-66); `previewable(params)` excludes the options that change something.
- Options are declared by a **parameter record** whose components are annotated with `@Option`.
- Modules are discovered by `ServiceLoader` and loaded into an **isolated `ModuleLayer`** per jar.

### 4.2 Contract

```java
package io.powerj.api;

/** P: parameter record. I: type of the received objects (Void if the cmdlet does not read the pipeline).
 *  O: type of the produced objects (record recommended, any type accepted). */
public interface Cmdlet<P extends Record, I, O> {
    default void begin(P params, CmdletContext<O> ctx) throws Exception { }
    default void process(P params, I input, CmdletContext<O> ctx) throws Exception { }
    default void end(P params, CmdletContext<O> ctx) throws Exception { }
    default boolean previewable(P params) { return true; }  // preview = true, but not with these options (FR-66)
}

public interface CmdletContext<O> {
    void emit(O value);                 // writes to the output stream
    void error(String message);         // non-blocking error
    Path currentDirectory();
    Map<String, String> environment();  // session environment (FR-36b)
    Optional<Object> variable(String name);
    default boolean hasInput() { return false; }  // after a |, even if no object arrives (FR-64)
    boolean cancelled();                // Ctrl+C requested
    ScriptBlock compile(String expression);
    default void stopInput() { }        // reads no more objects: the previous stage is stopped (FR-68b)
    default Optional<Path> configDirectory() { return Optional.empty(); }  // configuration folder (§8)
}

// Immutable ordered Map of named columns, output of select (FR-69); sizeColumns(): columns shown as sizes
public final class Row extends AbstractMap<String, Object> {
    static Row of(Map<String, ?> values);
    static Row of(Map<String, ?> values, Set<String> sizeColumns);
}

@Retention(RUNTIME) @Target(TYPE)
public @interface CmdletInfo {
    String name();
    String category() default "Misc";
    String summary();
    String[] examples() default {};
    boolean preview() default false;   // may run in preview mode: changes nothing (FR-66)
}

@Retention(RUNTIME) @Target(RECORD_COMPONENT)
public @interface Option {
    char shortName() default '\0';
    String longName() default "";      // default: component name
    boolean mandatory() default false;
    int position() default -1;          // >= 0: positional parameter
    String description() default "";
}

@Retention(RUNTIME) @Target(RECORD_COMPONENT)
public @interface Highlight { String value(); }  // parts of a text component shown in color: name of a
                                                 // List<Span> component of the same record (FR-64)
public record Span(int start, int end) { }       // [start, end) range of a text

@Retention(RUNTIME) @Target(RECORD_COMPONENT)
public @interface Completion { Class<? extends Completer> value(); }

public interface CmdletProvider {
    List<Cmdlet<?, ?, ?>> cmdlets();
    default ResourceBundle messages(Locale locale) { return null; }   // translations (FR-61)
}
```

> The reference is the code of the `powerj-api` module (Javadoc); the `@Completion` annotation is not available yet.

**Translation of the cmdlet texts (FR-61).** The texts of `@CmdletInfo` (`summary`, `category`) and `@Option` (`description`) are written **in English**: they are the fallback. A module translates them by overriding `CmdletProvider.messages(Locale)`, which returns its own `ResourceBundle` for the requested language (`Language.current()`, i.e. `Locale.ENGLISH` or `Locale.FRENCH`), loaded from the module itself (`ResourceBundle.getBundle("my.pkg.messages", locale)`):

| Key | Replaces | Example |
|---|---|---|
| `<cmdlet>.summary` | `@CmdletInfo.summary` | `greet.summary=Salue quelqu'un` |
| `<cmdlet>.option.<longName>` | `@Option.description` | `greet.option.name=Nom à saluer` |
| `category.<Category>` | display of `@CmdletInfo.category` in `help` | `category.Examples=Exemples` |

A missing key, or a `null` bundle (default implementation: module not translated), falls back to the annotation text. The `examples` are never translated (they are input syntax). For its own messages (output, errors), a module uses its bundle with `Language.text(bundle, key, args…)`, which replaces `{0}`, `{1}`…; a missing key gives the key itself.

### 4.3 Complete example: `greet` module

```java
// module-info.java
module com.example.greet {
    requires io.powerj.api;
    provides io.powerj.api.CmdletProvider with com.example.greet.GreetProvider;
}

// Greeting.java — output record
public record Greeting(String name, String message, Instant at) { }

// GreetParams.java — parameter record (English texts: fallback of the translations)
public record GreetParams(
        @Option(shortName = 'n', mandatory = true, description = "Name to greet") String name,
        @Option(shortName = 'c', description = "Number of repetitions") int count) {
    public GreetParams {
        if (count <= 0) count = 1;
    }
}

// Greet.java
@CmdletInfo(name = "greet", category = "Examples", summary = "Greets someone",
            examples = "greet --name Yves -c 3")
public final class Greet implements Cmdlet<GreetParams, Void, Greeting> {
    @Override
    public void begin(GreetParams p, CmdletContext<Greeting> ctx) {
        for (int i = 0; i < p.count(); i++) {
            ctx.emit(new Greeting(p.name(), Messages.get("greet.message", p.name()), Instant.now()));
        }
    }
}

// Messages.java — texts of the module in the current language
final class Messages {
    static String get(String key, Object... args) {
        return Language.text(ResourceBundle.getBundle("com.example.greet.messages", Language.current()), key, args);
    }
}

// GreetProvider.java
public final class GreetProvider implements CmdletProvider {
    public List<Cmdlet<?, ?, ?>> cmdlets() { return List.of(new Greet()); }

    @Override
    public ResourceBundle messages(Locale locale) {
        return ResourceBundle.getBundle("com.example.greet.messages", locale);
    }
}
```

```properties
# com/example/greet/messages_en.properties
category.Examples=Examples
greet.summary=Greets someone
greet.option.name=Name to greet
greet.option.count=Number of repetitions
greet.message=Hello {0}!

# com/example/greet/messages_fr.properties
category.Examples=Exemples
greet.summary=Salue quelqu'un
greet.option.name=Nom à saluer
greet.option.count=Nombre de répétitions
greet.message=Bonjour {0} !
```

Usage (in English; in French the message is `Bonjour Yves !`):

```text
PJ C:\> greet --name Yves -c 2 | where { $_.message.contains("Yves") }
name   message       at
----   -------       --
Yves   Hello Yves!   2026-10-07 10:12:03
Yves   Hello Yves!   2026-10-07 10:12:03
```

### 4.4 Installation and loading

- At startup, each `~/.powerj/modules/*.jar` is loaded into its own `ModuleLayer` (dependency isolation between modules). A module that has dependencies goes in a **subfolder** (`~/.powerj/modules/docker/` containing the module's jar and those of its dependencies): the subfolder forms a single layer. The folder follows `POWERJ_HOME` (§8).
- The jar can be an explicit module (`module-info.java` with `provides io.powerj.api.CmdletProvider with …`) or a classic jar declaring the service in `META-INF/services/io.powerj.api.CmdletProvider` (automatic module). It does not need to export its packages: PowerJ has them opened at load time to read the records and options.
- The runtime and PowerJ modules take precedence: a jar that bundles its own copy of `powerj-api` uses the shell's copy.
- `mod-load <path>` hot-loads a module (jar or folder) and displays the added cmdlets; `mod-list` lists the loaded modules (name, version, cmdlets, source), including the built-in cmdlets.
- A module's classes are **not** usable in Java expressions (§3.13): `new com.example.greet.Greeting(…)` answers `class not found: com.example.greet.Greeting`. Only its cmdlets are exposed; the objects they produce are used normally (`$g.message`, `where`, `map`). This is the intended way to use an external library from PowerJ.
- An invalid module (unreadable jar, missing dependency, no cmdlet, module already loaded, conflicting name, exception during loading) is reported with a warning; the other modules are loaded normally.

---

## 5. Technical architecture

### 5.1 Maven modules

```text
powerj/                         (parent POM, packaging pom)
├── powerj-api/                 Public API for cmdlets (no dependencies)
├── powerj-fs/                  File tree walk, sequential or parallel in virtual threads (FR-64)
├── powerj-core/                Lexer, parser, AST, resolution, evaluator, pipeline,
│                               object access, Java interop, formatting,
│                               native execution
├── powerj-cmdlets/             Built-in cmdlets (ls, grep, where, map, collect, env)
├── powerj-shell/               JLine REPL, completion, highlighting, main
├── powerj-sample-module/       Sample third-party module (greet)
└── powerj-dist/                jlink + jpackage → powerj.exe
```

All modules are **JPMS modules** (`module-info.java`).

### 5.2 Processing chain for a line

```text
input line
  → Lexer         (sealed interface Token, records)
  → Parser        (AST: sealed interface Node, records)
  → Resolution    (cmdlet / native / built-in, option binding)
  → Pipeline      (one stage = one virtual thread, bounded queues between stages)
  → Formatting    (table / list) → terminal
```

### 5.3 Pipeline execution

- Each stage runs in a **virtual thread**; stages are connected by **bounded queues** (backpressure: an `ls -r C:\` does not fill memory if the downstream is slow).
- The last stage runs in the thread that receives Ctrl+C; when it stops (end, blocking error, Ctrl+C), it closes its input queue, which stops the upstream stages in cascade (thread interrupted, native processes killed). A stage that stops reading (`ls | ^more` then `q`) likewise stops the upstream. This lifecycle will be handed over to **Structured Concurrency** (`StructuredTaskScope`) once the API is final (it is in preview, not used without the PM's approval).
- The current object `$_` of a block is bound by a **Scoped Value** during evaluation.
- Native commands are launched via `ProcessBuilder` (`INHERIT`, `PIPE` redirections); consecutive native commands form a group launched by `ProcessBuilder.startPipeline` (bytes passed directly); objects sent to a native command are written to its stdin in their displayed form.

### 5.4 Java interoperability (`powerj-core`, `JavaClasses`, `JavaInvoker`, `FunctionalAdapter`)

- **Class resolution**: lookup through the platform class loader (which does not see third-party modules), keeping only the public classes of the packages exported by the `java.*` modules; default imports (FR-47) and session imports; results cached.
- **Member resolution**: public methods by type and by name cached via `ClassValue`, seen through the exported public interface or superclass when the concrete class is not exported (`List.of(…)`); invocation by reflection (`Method.invoke`), sufficient in v1 — `MethodHandle`s remain a possible optimization if Java calls become a bottleneck.
- **Argument conversion**: conversion table (FR-50) expressed as a `switch` on types; overload selection by specificity score.
- **Lambdas and method references → functional interfaces**: implementation via `java.lang.reflect.Proxy` of the single abstract method (`default` methods delegated through `InvocationHandler.invokeDefault`); lambda parameters (or `$_`) bound by `ScopedValue` on each call.
- **Unrolling** (FR-30b): applied to the output of each stage by the pipeline executor.
- **Interceptions** (FR-58): table of redirected or refused methods (`System.exit`, `Runtime.halt`, `System.setOut`…), consulted when resolving a call; cancellation token check (FR-57) on each block invocation.

### 5.5 Dependencies

| Library | Usage |
|---|---|
| JLine 3 | Terminal, line editing, history, completion, highlighting (native Windows terminal via FFM) |
| JUnit 5, AssertJ | Tests |

Any new dependency must be justified and approved.

---

## 6. Use of modern Java features

Java's new features are used **where they bring a concrete benefit**:

| Feature | Usage in PowerJ |
|---|---|
| **Records** | Output objects (`FileEntry`, `NativeRun`), cmdlet parameters, tokens, AST nodes, history entries. Validation in compact constructors. |
| **Sealed interfaces** | Closed hierarchies: `Token`, `Node` (AST), `Resolved` (`CmdletCall` / `NativeCall` / `Builtin`), `Value`. The compiler guarantees exhaustive handling. |
| **Pattern matching `switch` + record patterns + `_`** | Expression evaluator, formatter, completion engine: `case BinaryOp(var l, Op.GT, var r) -> …`, `case FileEntry(var name, _, _, _, true, _) -> …`. |
| **Virtual threads** | One pipeline stage = one virtual thread; reading the stdout/stderr streams of native processes. |
| **Structured Concurrency** | Pipeline lifecycle: global cancellation on Ctrl+C or blocking error — as soon as the API is final (in the meantime: cascading cancellation through the queues, §5.3). |
| **Scoped Values** | Immutable session context per execution, instead of `ThreadLocal`. |
| **Stream Gatherers** | Custom stream operations in the pipeline (windowing, `first`/`last`, deduplication — useful as soon as the backlog cmdlets arrive). |
| **FFM API** | Windows console access (via JLine); reading the PE header to detect GUI applications, without JNI. |
| **Sequenced Collections** | History (`getFirst`/`getLast`/`reversed`), ordered columns. |
| **Reflection, `Proxy`, `ClassValue`** | Java interoperability: method/constructor calls with per-type cache, conversion of `{ }` blocks into functional interfaces (`MethodHandles`: possible optimization later). |
| **`ClassValue`** | Cache of member metadata per type, without classloader leaks (third-party modules). |
| **Module import declarations, flexible constructors** | Code readability. |
| **Primitive patterns** | In the evaluator for numeric comparisons, if finalized in JDK 27. |

**Rule:** a feature still in *preview* in JDK 27 is enabled (`--enable-preview`) only after PM approval. The list above is rechecked against the JEPs actually delivered in JDK 27 at the start of step 0.

---

## 7. Build and distribution

- **Maven 3.9**; `maven-enforcer-plugin` enforces Java 27 and Maven ≥ 3.9; `maven.compiler.release=27`.
- `mvn verify`: compilation, tests, coverage (JaCoCo).
- `powerj-dist` module:
  1. **jlink**: Java runtime containing **all the `java.se` modules** (required so that the whole standard API can be called, §3.13), without the development tools (`--strip-debug --no-header-files --no-man-pages`);
  2. **jpackage `--type app-image`** then **`--type exe`** (WiX Toolset): `powerj.exe` + installer with icon, addition to the `PATH`, Start menu entry, console mode (`--win-console`).
- **GitHub Actions CI**:
  - `build` job (Linux): `mvn verify` on every push and PR;
  - `package-windows` job (`windows-latest`): produces the installer and the portable `powerj/` folder as **downloadable artifacts** on every push to a release branch.
- Linux/macOS launchers (`jpackage --type app-image`) as a bonus, non-blocking.

---

## 8. Configuration

User folder `~/.powerj/` (created on first launch):

| File / folder | Role |
|---|---|
| `history` | Command history (FR-09). |
| `modules/` | Third-party module jars (§4.4). |
| `config.properties` | `history.size=10000`, `language=en` (or `fr`, FR-61), `native.prefer=find,sort`, `colors.cmdlet=green`… |
| `logs/` | Diagnostic log, in English (FR-60). |
| `protected-paths` | Paths that `rm` and `mv` refuse to remove (FR-72): one per line, `~` for the home folder, `#` for comments; created at first launch with the defaults of the platform. |
| `profile.pj` | Commands run when the interactive shell starts (FR-63): variable assignments (`$proj = Path.of("D:/dev")`), `import java.time.*`, `env --set MAVEN_OPTS=-Xmx2g`. `$profile` contains its path. |

Environment variables read by PowerJ:

| Variable | Role |
|---|---|
| `POWERJ_HOME` | Location of the configuration folder (default `~/.powerj`). |
| `POWERJ_NATIVE_ENCODING` | Encoding of native commands (FR-40b). |
| `POWERJ_NATIVE_ENCODING_<NOM>` | Encoding for a specific executable (FR-40b). |
| `POWERJ_LANG` | Language of the messages, `en` or `fr` (FR-61); takes precedence over the `language` key of `config.properties`, and is overridden by the system property `-Dpowerj.language`. |

---

## 9. Non-functional requirements

| ID | Requirement |
|---|---|
| NFR-01 | Startup time: no requirement in v1 (a few seconds is acceptable). Optimization (JDK AOT cache, background class indexing) is planned for a later phase. |
| NFR-02 | Imperceptible typing latency; completion < 50 ms. |
| NFR-03 | Windows 10/11 x64 first; UTF-8 end to end (console in code page 65001). |
| NFR-04 | `ls -r` on 100,000 files without running out of memory (streaming). |
| NFR-05 | Test coverage ≥ 80% on `powerj-core`. |
| NFR-06 | No raw Java exception displayed to the user outside debug mode. |
| NFR-07 | First call of a Java method < 50 ms; subsequent calls (cached) < 1 ms. |

---

## 10. Test strategy

- **Unit**: lexer, parser, resolution, option binding, expression evaluator, property access (records, getters, fields, `Map`), formatting, unrolling.
- **Java interop**: static/instance/constructor calls, overloads and varargs, conversions, blocks → functional interfaces, non-exported classes called through a public interface, exceptions, lexical rule (`java -version` vs `java.lang.Math.max(1,2)`).
- **Cmdlets**: `ls` and `grep` on a temporary directory tree (`@TempDir`), `where` on constructed streams; `FileWalker` sequential and parallel (same results, consumers in the calling thread, cancellation).
- **Native commands**: cross-platform tests with `cmd /c echo` (Windows) / `echo` (Linux), exit code, stderr, native → native.
- **Completion**: expected candidates for partial lines.
- **REPL integration**: "dumb" JLine terminal driven by script (simulated input, verified output), including history and Ctrl+R.
- **Modules**: loading of `powerj-sample-module`, handling of name collisions, module classes not accessible in Java expressions.
- **Default imports**: automated test verifying that no simple name is duplicated across the default-imported packages (protects against classes being added in a future JDK version).
- **Languages**: the Maven tests force English (`-Dpowerj.language=en` in the Surefire configuration) so that they do not depend on the machine or on `POWERJ_LANG`; resolution of the language and French texts are tested explicitly (FR-61).
- **Manual acceptance test**: one checklist per step (§11), executed by the PM on the exe produced by the CI.

---

## 11. Iterative development plan

Each step:
- delivers an **installable, testable `powerj.exe`**, produced by the CI (GitHub Actions artifact);
- comes with an **acceptance checklist** (step-by-step scenario for the PM) and automated tests;
- starts only after the **acceptance test** of the previous step has been **validated**.

### Step 0 — Skeleton and exe

**Content:** Maven multi-module structure, JPMS, enforcer Java 27 / Maven 3.9, jlink + jpackage, CI on Linux + Windows. The REPL is minimal (simple reading, `exit`).

**Acceptance test:**
1. Download the CI artifact, install `powerj.exe`.
2. Launch `powerj` from the Start menu and from a terminal (`PATH`).
3. Check the version banner and the `PJ C:\…> ` prompt.
4. Type `exit`: the shell closes.

### Step 1 — Line editing and history

**Content:** JLine, FR-01 to FR-11 (prompt, multi-line, Ctrl+C, Ctrl+D, editing, ↑/↓, Ctrl+R, persistent history, `history`, `!!`, `!n`); supervisor and `Outcome` result (FR-56), terminal restoration (FR-59), log (FR-60).

**Acceptance test:**
1. Type `bonjour`, `test un`, `test deux` (an `unknown command: …` error is expected).
2. ↑ three times: the lines come back in reverse order.
3. Ctrl+R then `un`: `test un` is suggested.
4. Quit, relaunch: ↑ brings the lines back.
5. `history` lists the entries; `!!` re-runs the last one.
6. Ctrl+C on a line being typed clears it; Ctrl+D on an empty line quits the shell.

### Step 2 — Native commands

**Content:** minimal lexer/parser (commands, arguments, strings, variables), `PATH` resolution, execution with inherited stdout/stderr, error stream, `$last` (`NativeRun`), `$exit`, `$?`, assignment `$x = …` (line capture), detached GUI applications, `which`, redirections `>`, `2>`, native command encoding, `cd`/`pwd` navigation, chaining with `;` `&&` `||`, Ctrl+C on a native command. FR-03, FR-04b, FR-04c, FR-13 to FR-15, FR-31, FR-32b, FR-34, FR-37 to FR-40b, FR-57 (levels 1-2 for native commands).

**Acceptance test:**
1. `git --version` displays the version.
2. `git log`: colors and paging work.
3. `$l = ipconfig` then `$l[0]` displays the first line.
4. `^cmd /c "exit 3"` then `$exit` displays `3`.
5. `git commandeinconnue`: error message in red.
6. `git log > log.txt` creates the file.
7. `notepad`: Notepad opens and the prompt comes back immediately.
8. `which git` displays the path of the executable.
9. `cd C:\Windows`, `cd ..`, `cd -`, `cd ~`, `pwd`; `cd "C:\\Program Files"`.
10. `ipconfig` into a variable (`$l = ipconfig`) then `$l`: accented characters are correct on a French Windows.
11. `^cmd /c "exit 1" || "échec"` displays `échec`; `git --version && "ok"` displays the version then `ok`; `^cmd /c "exit 1" && "jamais"` displays nothing.
12. `ping -t localhost` then Ctrl+C: `ping` stops and the prompt comes back.

### Step 3 — Object model and `ls` cmdlet

**Content:** `powerj-api` (FR: §4.2), cmdlet registry, `env` cmdlet (FR-36b) and encoding setting via variable (FR-40b), cmdlet > native priority, `^`, Unix option binding (FR-18 to FR-20), access to object properties (FR-27 to FR-29: records, getters, fields), type-based display (FR-30), unrolling (FR-30b), `ls` cmdlet (FR-35), `help` (FR-45).

**Acceptance test:**
1. `ls` displays a `name size modified dir` table.
2. `ls -r --filter *.txt` recursively lists the `.txt` files.
3. `(ls)*.name` displays the names only.
4. `$f = ls` then `$f[0].size` and `$f[0].path.parent`.
5. `ls --recurce`: error with the suggestion `--recurse`.
6. `help ls` and `ls --help` display the help.
7. `^ls` runs the native `ls` (if Git Bash is installed); `which ls` reports `cmdlet`.
8. `env`, `env PATH`, `env --set MAVEN_OPTS=-Xmx2g` then `env MAVEN_OPTS`, `env --unset MAVEN_OPTS`.
9. `env --append PATH C:\tools`: a tool in `C:\tools` becomes executable and `which` finds it.
10. `env --set POWERJ_NATIVE_ENCODING_GIT=UTF-8` then `$l = git log --oneline`: accented characters are correct.

### Step 4 — Pipeline and `where` cmdlet

**Content:** streaming pipeline (virtual threads, bounded queues, cascading cancellation), expression language (FR-32, FR-33), unit literals (FR-19), `where` cmdlet (FR-36), native commands in the pipeline (`String` lines, cmdlet → native, native → native), `2>&1`, Ctrl+C on a pipeline, `--on-error`, non-interactive mode (FR-04d).

**Acceptance test:**
1. `ls -r | where { $_.size > 1mb }`.
2. `ls | where { $_.name.endsWith(".java") && !$_.dir }`.
3. `ls | where size > 10kb` (short form).
4. `git status --porcelain | where { $_.startsWith(" M ") }`.
5. `ipconfig | where { $_.contains("IPv4") }`.
6. `ls | ^more`: paged output.
7. `ls -r C:\ | where { $_.ext == "log" }` then Ctrl+C: immediate stop.
8. `git commandeinconnue 2> err.txt`: `err.txt` contains the message.
9. `env | where { $_.name.startsWith("JAVA") }`.
10. From `cmd.exe`: `powerj -c "ls | where { $_.size > 1mb }"`; `powerj -c "^cmd /c exit 3"` then `echo %ERRORLEVEL%` displays 3; `dir /b | powerj -c "where { $_.endsWith(\".txt\") }"`.

### Step 5 — Java interoperability

**Content:** §3.13 (FR-46 to FR-55): static calls, static fields, `import`, `new`, instance calls, overloads and conversions, varargs, casts, blocks → functional interfaces, exceptions, `help members` / `help <classe>`; full `java.se` jlink runtime; robustness of Java calls: cooperative cancellation and abandonment (FR-57), interceptions (FR-58), fatal errors (FR-56).

**Acceptance test:**
1. `java.util.List.of("apple", "banana", "orange") | where { $_.contains("b") }` displays `banana`.
2. `$l = java.util.List.of("apple", "banana")` then `$l.size()` displays `2`.
3. `Math.max(3, 7)` and `java.lang.Math.PI`.
4. `LocalDate.now().plusDays(10).dayOfWeek` (no import: `java.time` is imported by default); `import java.security.*` then `MessageDigest.getInstance("SHA-256")`.
5. `new java.io.File("C:\\Windows").listFiles() | where { $_.directory }`.
6. `$l.stream().map({ $_.toUpperCase() }).toList()`.
7. `String.format("%s-%05d", "id", 42)` (varargs + conversion).
8. `Integer.parseInt("x")`: readable `NumberFormatException` error, stack trace visible with `--debug`.
9. `java -version` still launches the native `java` (if it is installed).
10. `help members $l` and `help java.util.List`.
11. `Stream.iterate(0, { $_ + 1 }).forEach({ $_ })` then Ctrl+C: the prompt comes back.
12. `System.exit(0)`: the shell closes cleanly (history saved); `System.setOut(null)`: refused with a message.
13. `new ArrayList().addAll(Collections.nCopies(2000000000, "x"))`: out-of-memory error, the shell remains usable.

### Step 5b — Java alignment

Delivered with step 5 (same PR, same exe).

**Content:** FR-33b (lambdas `f ->` and `(a, b) ->`, lambdas without braces in Java calls, method references `Classe::méthode`, `$x::méthode`, `Classe::new`, text blocks `"""…"""`), strict booleans (`where`, `&&`, `||`, `!`, ternary), removal of `$a` / `$b` / `$args`, `map` cmdlet (FR-36c). Update of acceptance tests 4 and 5 and of the specification examples.

**Acceptance test:**
1. `ls -r | where { f -> f.size > 1mb && !f.dir }`.
2. `ls -r | map { f -> f.name + " : " + f.name.length() }`; also works when `ls` returns only one file.
3. `ls | map FileEntry::name` and `env | map EnvVar::name`.
4. `$l = List.of("apple", "banana", "kiwi")` then `$l.stream().filter(s -> s.length() > 4).map(String::toUpperCase).toList()`.
5. `$m = new ArrayList($l); $m.sort((a, b) -> a.length() - b.length()); $m`.
6. `$l.stream().map(Path::of).toList()` and `Stream.of("a", "b").map(StringBuilder::new).toList()`.
7. `ls | where { f -> f.name }`: non-blocking error `the block must return a boolean, got …` for each object.
8. `$m.sort({ $a.length() - $b.length() })`: clear error telling the user to write `(a, b) -> …`.
9. `$min = 1kb; ls | where { f -> f.size > $min }` (shell variables in a lambda).
10. Multi-line text block: `$t = """` … `"""` then `$t.lines().count()`.

### Step 6 — Tab completion and highlighting

**Content:** FR-08, FR-21 to FR-26, FR-24b (Java completion).

**Acceptance test:**
1. `l<Tab>` suggests `ls [pj]` and the native commands starting with `l`.
2. `ls --<Tab>` suggests the options; `ls -r --<Tab>` no longer suggests `--recurse`.
3. `ls C:\Pro<Tab>` completes to `"C:\\Program Files\\` (in quotes because of the space, FR-32b).
4. `ls | where { $_.<Tab>` suggests `name size modified path dir ext`.
5. `$f = ls` then `$f[0].<Tab>`.
6. `^no<Tab>` suggests `notepad`.
7. `java.util.Li<Tab>`, `List.<Tab>`, `$l.<Tab>`, `new java.io.F<Tab>` (Java completion with signatures).
8. Check the colors: cmdlet, native command, unknown command, string, variable.

### Step 7 — Third-party modules

**Content:** `powerj-api` publishable on its own, loading of `~/.powerj/modules/*.jar` into isolated `ModuleLayer`s, `mod-load`, `mod-list`, collision handling (FR-17), sample module `greet` (§4.3).

**Acceptance test:**
1. Copy `greet.jar` (CI artifact) into `~/.powerj/modules/`, relaunch.
2. `greet --name Yves -c 2` displays two objects.
3. `gr<Tab>` and `greet --<Tab>` complete.
4. `greet -n Yves | where { $_.message.contains("Yves") }`.
5. `help greet` displays the generated help.
6. `mod-list` lists the module.
7. `new com.example.greet.Greeting(...)`: `class not found: com.example.greet.Greeting` error (module classes are not exposed).

### After these steps

The backlog cmdlets (§12.3) are added **one per mini-iteration**, each with a short specification (options, output record, CA), an exe and an acceptance checklist.

| Mini-iteration | Cmdlet / feature | Specification | Acceptance test |
|---|---|---|---|
| 8 | `collect` | FR-36d | `docs/recettes/etape-8-collect.md` |
| 9 | i18n (en/fr) | FR-61 | `docs/recettes/i18n.md` |
| 10 | `~` = `HOME` | FR-62 | `docs/recettes/tilde.md` |
| 11 | `profile.pj` | FR-63 | `docs/recettes/profile.md` |
| 12 | `grep` and `powerj-fs` | FR-64 | `docs/recettes/grep.md` |
| 13 | `cat` | FR-65 | `docs/recettes/cat.md` |
| 14 | Preview mode (Alt+V) | FR-66 | `docs/recettes/preview.md` |
| 15 | `sort` | FR-67 | `docs/recettes/sort.md` |
| 16 | `first`, `last`, `range`, `stopInput` | FR-68 | `docs/recettes/first-last-range.md` |
| 17 | `select`, `Row` | FR-69 | `docs/recettes/select.md` |
| 18 | `count`, `measure` | FR-70 | `docs/recettes/count-measure.md` |
| 19 | `group` | FR-71 | `docs/recettes/group.md` |
| 20 | `mkdir`, `touch`, `cp`, `mv`, `rm` | FR-72 | `docs/recettes/fileops.md` |
| 21 | `cp -r -p`, `rm -r -p` (parallel) | FR-72b | `docs/recettes/fileops.md` |
| 22 | `to-json`, `from-json`, `to-csv`, `from-csv` | FR-73 | `docs/recettes/json-csv.md` |
| 23 | `ps`, `kill` | FR-74 | `docs/recettes/ps-kill.md` |

Version 2 will introduce scripting (`if`, `foreach`, functions, `.pj` files).

---

## 12. Appendices

### 12.1 Grammar (EBNF, v1)

```ebnf
ligne         = import | [ affectation | pipeline ] [ redirection* ] ;
import        = "import" nom_qualifie [ ".*" ] ;
affectation   = variable "=" pipeline ;
pipeline      = etape { "|" etape } ;
etape         = expr_java | commande | "(" pipeline ")" ;
(* expr_java is tried first: nom_qualifie directly followed by "(", "new",
   cast, or a name designating a known class/static field (FR-46) *)
expr_java     = postfixe ;
commande      = [ "^" ] nom { argument } ;
argument      = option | valeur | bloc ;
option        = "-" lettre { lettre } | "--" ident [ "=" valeur ] | "--" ;
valeur        = chaine | nombre | unite | variable_acces | liste | mot ;
bloc          = "{" ( lambda | expression ) "}" ;
lambda        = params "->" expression ;                 (* FR-33b *)
params        = ident | "(" [ ident { "," ident } ] ")" ;
redirection   = ( ">" | ">>" | "2>" | "2>>" ) chemin | "2>&1" ;

expression    = ou [ "?" expression ":" expression ] ;
ou            = et { "||" et } ;
et            = egalite { "&&" egalite } ;
egalite       = comparaison [ ( "==" | "!=" ) comparaison ] ;
comparaison   = somme [ ( "<" | "<=" | ">" | ">=" ) somme ] ;
somme         = produit { ( "+" | "-" ) produit } ;
produit       = unaire { ( "*" | "/" | "%" ) unaire } ;
unaire        = [ "-" | "!" ] [ cast ] postfixe ;
cast          = "[" nom_qualifie "]" ;
postfixe      = primaire { "." ident [ arguments ] | "*." ident [ arguments ] | "::" ident | "[" expression "]" } ;   (* "*.": each element *)
arguments     = "(" [ arg_java { "," arg_java } ] ")" ;   (* no space before "(" *)
arg_java      = lambda | expression | bloc ;              (* lambda, block, ref_methode → functional interface *)
primaire      = litteral | variable | "(" pipeline ")" | liste | ref_methode
              | "new" nom_qualifie arguments
              | nom_qualifie [ arguments ] ;              (* class, static field or static method *)
nom_qualifie  = ident { "." ident } ;
ref_methode   = ( nom_qualifie | variable ) "::" ( ident | "new" ) ;
variable_acces= postfixe ;
variable      = "$" ( ident | "_" | "?" ) ;
liste         = "[" [ expression { "," expression } ] "]" ;
litteral      = chaine | caractere | nombre | unite | "true" | "false" | "null" | "now" ;
unite         = nombre ( "b" | "kb" | "mb" | "gb" | "tb" | "s" | "m" | "h" | "d" ) ;
chaine        = '"' { car | echappement | "$" ident | "$(" pipeline ")" } '"' | bloc_texte ;
echappement   = "\\" ( "\\" | '"' | "n" | "t" | "r" | "$" | "u" hex hex hex hex ) ;
caractere     = "'" ( car | echappement ) "'" ;
mot           = { car_sans_espace } ;   (* unquoted argument: taken as is, literal "\" *)
```

### 12.2 Sample sessions (scope: `ls` + `where` + native commands)

```text
PJ C:\dev> ls -r --filter *.java | where { $_.modified > now - 1d }
PJ C:\dev> $gros = ls -r | where size > 100mb
PJ C:\dev> $gros.path
PJ C:\dev> git branch --list | where { $_.contains("feature") }
PJ C:\dev> ls -d | where { $_.name.matches("^[a-m].*") } | ^more
PJ C:\dev> mvn -q verify; $exit
PJ C:\dev> code .                       # graphical application, returns control immediately
PJ C:\dev> java.util.List.of("apple", "banana", "orange") | where { $_.contains("b") }
PJ C:\dev> ls -r --filter *.log | where { Files.size($_.path) > 10mb }
PJ C:\dev> (ls)*.name.stream().map({ $_.toUpperCase() }).sorted().toList()
```

### 12.3 Cmdlet backlog (outside the current scope)

| Cmdlet | Description | Planned output record |
|---|---|---|
| ~~`cat`~~ | Read files — **done** (FR-65) | `String`, `CatLine`, `CatContent` |
| `find` | Advanced search (`--name --since --size --type`) | `FileEntry` |
| ~~`cp`, `mv`, `rm`, `mkdir`, `touch`~~ | File operations — **done** (FR-72) | `FileEntry`, `FileOperation` |
| `ps`, `kill` | Processes | `ProcessEntry` |
| ~~`select`~~ | Attribute projection — **done** (FR-69); `--expand` not done | `Row` |
| ~~`sort`~~ | Sorting — **done** (FR-67) | unchanged |
| ~~`collect`~~ | Gather into a list — **done** (FR-36d) | `Collected` |
| ~~`first`, `last`~~ | First / last N — **done** (FR-68) | unchanged |
| ~~`range`~~ | Objects between two bounds — **done** (FR-68) | unchanged |
| ~~`group`~~ | Grouping — **done** (FR-71) | `Group` |
| ~~`count`, `measure`~~ | Aggregates (count, sum, average, min, max) — **done** (FR-70) | `Long`, `Row` |
| `uniq` | Deduplication | unchanged |
| `tee` | Copy into a variable | unchanged |
| `table`, `tree` | Display formats | — |
| ~~`from-json`, `to-json`, `from-csv`, `to-csv`~~ | Conversions — **done** (FR-73) | `Row` / `String` |
| `http`, `ping` | Network | `HttpResponse`, `PingResult` |
| `env` | Environment variables | `EnvVar` |
| `open` | Open with the associated application | — |

Target vision once the backlog is complete:

```text
find src --name *.java --since 7d | where { $_.size > 2kb } | group { $_.path.parent }
    | sort count --desc | first 5 | to-json > rapport.json
```

### 12.4 PowerShell → PowerJ mapping

| PowerShell | PowerJ |
|---|---|
| `Get-ChildItem -Recurse -Filter *.java` | `ls -r --filter *.java` |
| `Where-Object { $_.Length -gt 1MB }` | `where { $_.size > 1mb }` or `where { f -> f.size > 1mb }` |
| `ForEach-Object { $_.Name }` | `map { f -> f.name }` or `map FileEntry::name` |
| `$_.Name -like '*.txt'` | `$_.name.endsWith(".txt")` |
| `-and`, `-or`, `-not` | `&&`, `\|\|`, `!` |
| `$_ -match 'IPv4'` | `$_.contains("IPv4")` / `$_.matches(".*IPv4.*")` |
| `$LASTEXITCODE` | `$exit` |
| `& "C:\outil.exe"` | `^"C:\outil.exe"` |
| `Get-Member` | `help members` |
| `[System.Math]::Max(3, 7)` | `Math.max(3, 7)` |
| `[System.IO.File]::ReadAllLines("a.txt")` | `java.nio.file.Files.readAllLines(Path.of("a.txt"))` |
| `New-Object System.Text.StringBuilder` | `new StringBuilder()` |
| `using namespace System.Security` | `import java.security.*` (java.io, java.util, java.nio.file… are imported by default) |
| `[int] "42"` | `[int] "42"` |

### 12.5 Open questions for the PM

1. **Colors and theme**: should a configurable light / dark theme be available as early as v1?
2. **Code signing** of the exe and the installer (avoids the SmartScreen warning): is a certificate available?
3. **Installer name and publisher** displayed in "Programs and Features".
4. **Dictionary literals** (`{k: v}`): useful in v1 or postponed? (With interop, `java.util.Map.of("k", "v")` already covers the need.)
5. **License** of the `powerj-api` module for third-party module authors (same license as the project?).
6. **Interop and side effects**: should there be a configuration option to disable Java interop (`interop.enabled=false`) in restricted contexts?
