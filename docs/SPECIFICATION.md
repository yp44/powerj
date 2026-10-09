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
- **`Map`**: `clé / valeur` (key / value) table;
- **scalars** (`String`, numbers, booleans, dates, `Path`, enums): one line, readable form;
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

Only **four cmdlets** are in the scope of this document: `ls`, `where`, `map` and `env`. The first two alone cover the core mechanisms: producing record objects, attribute access, the pipeline, expressions, and mixing with native commands.

#### FR-35 — `ls`: list files

```text
ls [chemin...] [-a|--all] [-r|--recurse] [-f|--filter <motif>] [-d|--dirs] [--files]
```

| Option | Type | Description |
|---|---|---|
| `chemin` (positional, multiple) | `Path` | Folder(s) or file(s) to list; default: current directory. Wildcards accepted (`*.txt`). |
| `-a`, `--all` | boolean | Includes hidden/system files. |
| `-r`, `--recurse` | boolean | Walks subfolders. |
| `-f`, `--filter` | pattern | Keeps only the names matching the pattern (`*.java`). |
| `-d`, `--dirs` | boolean | Folders only. |
| `--files` | boolean | Files only. |

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

Columns displayed by default: `name size modified dir path` (`path`: absolute path, truncated if the window is narrow). Folders are listed before files, in alphabetical order. The walk is **lazy** (streaming): `ls -r C:\ | where …` displays the first results immediately and Ctrl+C interrupts it. An inaccessible folder produces a non-blocking error and the walk continues.

CA:
- `ls` displays the contents of the current directory as a table;
- `ls -r --filter *.txt` recursively lists the `.txt` files;
- `(ls)*.name` displays only the names;
- `$f = ls; $f[0].size` displays the size of the first element;
- `^ls` runs the native `ls` if it exists (Git Bash, WSL…), otherwise error `native command not found: ls`.

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
}

public interface CmdletContext<O> {
    void emit(O value);                 // writes to the output stream
    void error(String message);         // non-blocking error
    Path currentDirectory();
    Map<String, String> environment();  // session environment (FR-36b)
    Optional<Object> variable(String name);
    boolean cancelled();                // Ctrl+C requested
    ScriptBlock compile(String expression);
}

@Retention(RUNTIME) @Target(TYPE)
public @interface CmdletInfo {
    String name();
    String category() default "Misc";
    String summary();
    String[] examples() default {};
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
├── powerj-core/                Lexer, parser, AST, resolution, evaluator, pipeline,
│                               object access, Java interop, formatting,
│                               native execution
├── powerj-cmdlets/             Built-in cmdlets (ls, where)
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
| `profile.pj` | Lines executed at startup (variable assignments, aliases: `alias ll = ls -a`). |

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
- **Cmdlets**: `ls` on a temporary directory tree (`@TempDir`), `where` on constructed streams.
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
1. `ls` displays a `name size modified dir path` table.
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
| `cat` | Read a file line by line | `String` |
| `find` | Advanced search (`--name --since --size --type`) | `FileEntry` |
| `cp`, `mv`, `rm`, `mkdir`, `touch` | File operations | `FileEntry` |
| `ps`, `kill` | Processes | `ProcessEntry` |
| `select` | Attribute projection, `--expand` | `Row` |
| `sort` | Sorting (`--desc`) | unchanged |
| ~~`collect`~~ | Gather into a list — **done** (FR-36d) | `Collected` |
| `first`, `last` | First / last N | unchanged |
| `group` | Grouping | `Group<T>` |
| `count`, `sum`, `avg`, `min`, `max` | Aggregates | `Stats` |
| `uniq` | Deduplication | unchanged |
| `tee` | Copy into a variable | unchanged |
| `table`, `tree` | Display formats | — |
| `from-json`, `to-json`, `from-csv`, `to-csv` | Conversions | `Map` / `String` |
| `http`, `ping` | Network | `HttpResponse`, `PingResult` |
| `env` | Environment variables | `EnvVar` |
| `open` | Open with the associated application | — |

Target vision once the backlog is complete:

```text
find src --name *.java --since 7d | where { $_.size > 2kb } | group { $_.path.parent }
    | sort count --desc | first 5 | to-json rapport.json
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
