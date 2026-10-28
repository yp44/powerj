# PowerJ user guide — entering commands

*Version française : [USER-GUIDE_fr.md](USER-GUIDE_fr.md).*

This guide explains what you can type at the PowerJ prompt and how the shell reads it: how a command is called, how
objects travel through `|`, what a complex line may contain, what `{ … }` and `( … )` mean, which variables the shell
defines for you (`$_`, `$?`, `$exit`…) and which environment variables it reads.

It describes the behavior of the shell as implemented. The reference for each requirement is the
[specification](SPECIFICATION.md) (the `FR-nn` numbers below point to it), and the step-by-step checks are in
[`acceptance/`](acceptance/).

## Contents

1. [A line at a glance](#1-a-line-at-a-glance)
2. [Calling a command](#2-calling-a-command)
3. [Arguments: what can follow a command name](#3-arguments-what-can-follow-a-command-name)
4. [Pipelines: how objects flow](#4-pipelines-how-objects-flow)
5. [Blocks](#5-blocks)
6. [Parentheses and brackets](#6-parentheses-and-brackets)
7. [Variables](#7-variables)
8. [Environment variables](#8-environment-variables)
9. [Typing at the prompt](#9-typing-at-the-prompt)
10. [What does not exist](#10-what-does-not-exist)
11. [Common mistakes](#11-common-mistakes)

Conventions: `PJ>` is the prompt; a line that starts with it is what you type. Examples that call Unix programs
(`printf`, `sort`, `git`…) work the same on Windows when the program exists there; the Windows `cmd.exe` built-ins
(`dir`, `echo`, `type`) are not programs and are run with `^cmd /c dir`.

---

## 1. A line at a glance

A line is a sequence of **statements**. A statement is an optional assignment followed by a **pipeline**; a pipeline is
one or more **stages** separated by `|`, optionally followed by **redirections**.

```text
line      = statement { ( ";" | "&&" | "||" | "&" ) statement }
statement = [ $name = ] pipeline
pipeline  = stage { "|" stage } [ redirections ]
stage     = built-in command | cmdlet | native program | Java expression
```

```text
PJ> $big = ls -r --filter *.java | where { $_.size > 10kb } | sort size --desc 2> errors.txt ; $big.size()
```

This line has two statements. The first assigns the result of a three-stage pipeline to `$big` and sends the error
stream to a file; the second calls a Java method on the variable.

### Chaining statements

| Syntax | Effect |
|---|---|
| `a ; b` | Runs `a`, then `b`, whatever the result of `a`. |
| `a && b` | Runs `b` only if `a` **succeeded**. |
| `a \|\| b` | Runs `b` only if `a` **failed**. |
| `a & b` | Starts the native command `a` in the **background**, and runs `b` at once. |

`&&` and `||` are short-circuit operators, as in Java. A trailing `;` or `&` is allowed; an empty statement (`;;`) is ignored.

**What "succeeded" means** (FR-04c):

| Statement | Succeeded when |
|---|---|
| native command | its exit code is 0 |
| cmdlet | it reported no error (a non-blocking error such as an unreadable file counts as a failure) |
| Java expression | no error, and — if the value is a `Boolean` — the value is `true` |

So `Files.exists(Path.of("build")) || mkdir build` creates the folder only when it does not exist, and
`mvn package && java -jar target/app.jar` runs the jar only after a successful build. `$?` holds the outcome of the last
statement that ran ([§7](#automatic-variables)).

An error in one statement is displayed and counts as a failure; it does not stop the following statements of the line
(`commandeinexistante; "suite"` prints the error, then `suite`). A **syntax error** anywhere in the line prevents the
whole line from running.

### Background: `&`

`cmd &` starts a **native** command (or a pipeline of native commands) and gives the prompt back at once:
`xeyes &`, `make > build.log 2>&1 &`. The shell prints `[1] 12345` (job number and pid) and reports `[1] done …` before a
later prompt. `jobs` lists the running jobs; `jobs | kill` stops them. Ctrl+C stops only the foreground command.

`&` applies to native commands only — `ls &` is an error — and an assignment cannot be put in the background. `&` is the
operator only when a blank, `;` or the end of the line follows it: `curl http://x?a=1&b=2` passes the URL whole.

### Redirections

| Syntax | Effect |
|---|---|
| `> file` | The output of the pipeline goes to the file (overwritten). |
| `>> file` | The output is appended. |
| `2> file`, `2>> file` | The error stream (of every stage) goes to the file. |
| `2>&1` | The error stream of **this stage** joins its output and goes down the pipeline. |

- `>` and `>>` apply to the **whole pipeline** and must come at the end: `ls > f | where …` is a syntax error.
  `2>&1` follows the stage it applies to: `cmd 2>&1 | where { $_.contains("erreur") }`.
- Objects written to a file are written in their displayed form (the same table you would see), in UTF-8.
  A native command redirected with `>` writes the file itself, byte for byte.
- An assignment cannot redirect its output (`$x = ls > f` is an error), nor can a group `( … )`.
- The file name is an ordinary argument: unquoted words (with `~`) or a string. There is **no input redirection**: `<`
  is not an operator (feed a program with `cat file | program` instead).

---

## 2. Calling a command

### Which command runs

The first word of a stage is looked up in this order (FR-13):

1. a **built-in command** of the shell: `cd`, `pwd`, `which`, `help`, `import`, `mod-load`, `mod-list`, `jobs`,
   `history`, `exit`;
2. a **cmdlet**, the commands that ship with PowerJ or come from a module: `ls`, `grep`, `where`, `map`, `sort`, `env`…
   (`help` lists them with a summary);
3. a **native program** found by the `PATH` of the session (and `PATHEXT` on Windows), or an explicit path;
4. otherwise the error `unknown command: name`.

`^name` skips steps 1 and 2 and always runs the program: `^ls`, `^sort data.txt`. `which name…` tells what a name
stands for (`ls → cmdlet`, `git → native /usr/bin/git`, `cd → built-in command`). A name that contains a `/` (also `\` or
`:` on Windows) is a path to a program, relative to the current directory: `./build.sh`, `.\tool.exe`.

There is no alias mechanism. A cmdlet name is a single short lowercase word (`ls`, `to-json`); there is no long form.

A line can also **start with a Java expression** instead of a command ([§2.4](#24-a-java-expression-as-a-stage)). The shell
decides by looking at the first word.

### 2.1 Cmdlets

A cmdlet produces typed Java objects (usually records) and receives the objects of the previous stage. Its options
follow the Unix convention (FR-18):

| Form | Example |
|---|---|
| short option | `-r` |
| grouped short options | `-ra` (= `-r -a`) |
| short option with a value | `-p 16`, `-p16` |
| long option | `--recurse` |
| long option with a value | `--filter *.java`, `--filter=*.java` |
| positional argument | `ls src`, `grep TODO src` |
| end of options | `ls -- -file-starting-with-a-dash` |

Details that matter:

- **Long names are case-insensitive and can be abbreviated** while unambiguous (`--rec` for `--recurse`); an ambiguous
  abbreviation lists the candidates. **Short names are case-sensitive.**
- An unknown option is an error with a suggestion: `ls: unknown option --recurce, did you mean --recurse?`
- A boolean option is a flag (`-r`); `--recurse=false` is also accepted (`true`/`false`, `yes`/`no`, `1`/`0`).
- Positional arguments are bound in order. A cmdlet whose last positional is a list (`rm a b c`, `select name size`)
  takes all the remaining ones.
- A negative number (`-5`) and a lone `-` are positional values, not options.
- The binder works on values, not on how they were written: a quoted `"-r"` is still an option. To pass a text that
  starts with `-`, put `--` before it.
- Values are converted to the type the cmdlet declares: `Path`, `int`, `long`, `double`, `boolean`, enums (by name, case
  ignored), `Duration`, lists. A `long` accepts a size (`--min 10kb`) and a `Duration` a duration (`--timeout 30s`).
  A value that cannot be converted is an error before anything runs. A value computed by an expression keeps its Java type:
  `ls $dir` receives the `Path` itself.
- **Every cmdlet accepts `--on-error stop|continue|silent`** (default `continue`), which says what to do with
  *non-blocking* errors (an unreadable file, an object that does not fit): report and go on, stop at the first one, or hide.
- `cmdlet --help` shows the options, the type of the objects it produces and examples; so does `help cmdlet`.
- A cmdlet that works on files expands wildcards in its path arguments itself, quoted or not (`ls *.txt`,
  `cat "*.txt"`, `rm *.tmp`). A wildcard that is not a path stays the cmdlet's own (`ps java*`, `ls --filter *.java`).

### 2.2 Native programs

Any other word is looked up on the `PATH` and run as a program (`git`, `mvn`, `docker`, `notepad`…). **A native command is
never wrapped in an object: its output stays a stream** (FR-37).

| Where the program is in the line | Its standard output | Its standard error |
|---|---|---|
| last stage at the prompt (`git log`) | goes straight to the terminal: colors, paging, `vim`, `ssh`, `python` work | the terminal |
| followed by a stage (`git status \| where …`) | becomes a stream of `String` lines | the **error stream** of PowerJ (red), never mixed with the lines |
| assigned (`$l = ipconfig`) | captured as a list of `String` lines | the error stream |
| followed by another program (`^cat a \| ^sort`) | bytes passed directly to the next process, without decoding | the error stream |
| redirected (`git log > f`) | written to the file by the program | the terminal, or the file of `2>` |

Arguments are passed **as written**, after three expansions:

- **variables and groups** are replaced by the text of their value ([§3](#3-arguments-what-can-follow-a-command-name));
- **`~`** at the start of an unquoted word becomes the home directory (`~`, `~/docs`; not `~user`, not in quotes);
- **wildcards** (Linux and macOS): an unquoted word containing `*`, `?` or `[a-c]` is replaced by the sorted names of the
  files it matches, as a Unix shell does. A word that matches nothing is passed unchanged; a quoted text, a variable or
  a group is never expanded; a name starting with `.` only matches a pattern that starts with `.`; wildcards are
  case-sensitive unless `POWERJ_GLOB_IGNORE_CASE` is set ([§8](#8-environment-variables)). On Windows the program expands its
  own wildcards, so the words go through unchanged (FR-40).

Unquoted words are literal: **the backslash is not an escape character there**, so `cd C:\Users` and `ls D:\photos` work
as typed. Inside double quotes, Java escapes apply and a backslash is doubled: `cd "C:\\Program Files"`. Single quotes
are **not** quotes in PowerJ (`'x y'` is two words, `'x` and `y'`).

After a native command, the shell records what happened without putting it in the output ([§7](#automatic-variables)):
`$exit` is the exit code, `$last` the details, `$?` the success. **A non-zero exit code is not an error of the shell**:
the next statement runs, and `&&` / `||` react to it.

More behaviors:

- The program starts in the **current directory of the shell** with the **environment of the session**
  ([§8](#8-environment-variables)).
- Interactive programs (`vim`, `ssh`…) read the keyboard alone while they run; the line editor waits.
- On Windows, a program of the graphical subsystem (`notepad`) is started detached: the prompt returns at once,
  `$last.pid` is set, `$exit` is empty.
- Text exchanged with a program (decoding of its output, objects written to its input) uses the encoding given by
  `POWERJ_NATIVE_ENCODING` ([§8](#8-environment-variables)).
- Ctrl+C stops the program and its child processes.

### 2.3 Built-in commands

`cd`, `pwd`, `which`, `help`, `import`, `mod-load`, `mod-list`, `jobs`, `history`, `exit` act on the shell itself.
They are only valid as the **first stage** (`ls | cd` is the error `"cd" does not read pipeline objects`), and they are
not available in preview mode.

| Command | Effect |
|---|---|
| `cd path`, `cd`, `cd -`, `cd ~`, `cd D:` | Changes the current directory of the shell (also the base of relative paths for cmdlets, Java calls and programs). `cd` alone goes home; `cd -` to the previous one. |
| `pwd` | The current directory, as a `Path`. |
| `which name…` | What each name stands for. |
| `help`, `help cmdlet`, `help List`, `help members $x` | Overview; a cmdlet; the API of a Java class; the properties and methods of a value. |
| `import java.security.*`, `import` | Adds Java imports for the session; alone, lists them. |
| `mod-load path`, `mod-list` | Loads a third-party module; lists the modules. |
| `jobs` | The background jobs, as `Job` objects. |
| `history`, `history --clear`, `!!`, `!n`, `!text` | The numbered history; re-run the last command, entry *n*, or the last one starting with *text* (only at the start of the line). |
| `exit`, `exit 3` | Leaves the shell, with an exit code. |

### 2.4 A Java expression as a stage

Anything that is Java can start a stage, and its value becomes the output of the stage:

```text
PJ> Math.max(3, 7)
PJ> new StringBuilder("ab").reverse()
PJ> LocalDate.now().plusDays(10).dayOfWeek
PJ> Files.readAllLines(Path.of("notes.txt")) | where { $_.contains("TODO") }
PJ> "today is $(LocalDate.now().dayOfWeek)"
PJ> [long] 5
PJ> (ls)*.name
```

The shell decides between a command and an expression from the first word (FR-46):

- it is an **expression** if it starts with `$`, a quote `"` or `'`, `(`, `{`, `[`, a digit, `true` / `false` / `null` /
  `now`, `new Name`, or a **dotted Java name** that is *immediately* followed by `(` (`Math.max(1, 2)`,
  `Path.of("x")`) or that designates a class or a static field of a known class (`Math.PI`, `DayOfWeek.MONDAY`,
  `java.lang.Math.PI`);
- otherwise it is a **command**. So `java -version` and `notepad.exe notes.txt` stay native commands, while
  `java.lang.Math.max(1, 2)` is a Java call.

When in doubt, put the expression in parentheses: `(Math.max(1, 2))`.

Rules for an expression at the start of a stage:

- All the Java classes of the default imports can be written by their simple name (`java.lang`, `java.math`,
  `java.text`, `java.util` and its `function`, `stream`, `regex`, `concurrent`, `java.io`, `java.nio.file`,
  `java.nio.charset`, `java.net`, `java.net.http`, `java.time`, `java.time.format`). Others need `import` or the full
  name. Only the Java standard library is reachable ([FR-52](SPECIFICATION.md)); `System.exit` and its relatives are
  turned into the `exit` command.
- A value can only be the **first** stage: `ls | $x` is an error. To process a list with a pipeline, put the list first:
  `$l | where { … }`.
- Accesses are written without spaces: `$l.size()`, not `$l .size()`.
- Outside parentheses, an expression stops where the command syntax resumes: `|`, `;`, `&&`, `||`, `>`, `>=`.
  `$a > 1` therefore writes `$a` to a file named `1`. To compare, use parentheses or a block: `($a > 1)`.
  (`$a + 1`, `$a == 3` and `$a * 2` are fine.)
- An exception thrown by Java code is a short error message (`java.lang.NumberFormatException: For input string: "x"`);
  `$debug = true` adds the stack trace, and `$errors[0]` keeps the exception object.

---

## 3. Arguments: what can follow a command name

Everything after the command name, up to the next `|`, `;`, `&&`, `||`, `&` or redirection, is an argument. The kinds:

| Form | Examples | What the command receives |
|---|---|---|
| **Word** (unquoted) | `src`, `C:\Users`, `*.java`, `-r`, `10kb`, `1.5`, `~/docs` | The text as typed, with `~` expanded. A cmdlet converts it to the type it expects. |
| **String** `"…"` | `"C:\\Program Files"`, `"Hello $name"` | The text, after Java escapes and interpolation. Never expanded as a wildcard. |
| **Variable** | `$dir`, `$f.path`, `$l[0]`, `$l.size()` | The value, with its Java type. A native program receives its text. |
| **Java call** | `Path.of("a.txt")`, `Math.max(1, 2)` | The value. The name and `(` must touch. |
| **Group** `( … )` | `(ls)`, `(env HOME).value` | The value(s) of the pipeline or expression ([§6](#6-parentheses-and-brackets)). |
| **Block** `{ … }` | `{ $_.size > 1mb }`, `{ f -> f.dir }` | A function the cmdlet applies to its objects ([§5](#5-blocks)). |
| **Method reference** | `FileEntry::name`, `String::toUpperCase` | A function, like a block that just calls the method. |

### How a line is cut into arguments

An **unquoted word ends** at a blank, `;`, `|`, `"`, `>`, `(`, `)`, `{`, `}`, `&&`, or a `&` followed by a blank, `;` or
the end of the line. `2>` is an operator when it follows a blank. Anything else — `$`, `'`, `[`, `]`, `*`, `?`, `=`, `,`,
`:`, `\`, `-` — is part of the word.

This has consequences that differ from other shells:

- **Arguments are never glued together.** Each token is its own argument, with or without blanks between them:
  `cd $home/docs` is two arguments (`$home` and `/docs`), and `a"b"` is two (`a` and `"b"`). To build a path, use a
  string: `cd "$home/docs"`.
- **`$` is recognized only at the beginning of an argument.** `--out=$file` passes the literal text `--out=$file`; write
  `"--out=$file"`.
- **A `>` always starts a redirection**, even in the middle of a word: `a>b` is the word `a` redirected to `b`. Quote it:
  `"a>b"`.
- An expression used as an argument stops at the first blank and takes **no operators**: `echo $a + 1` receives three
  arguments (`$a`, `+`, `1`). Put the computation in a group: `echo ($a + 1)`.
- A list literal or a cast is an expression only at the start of a stage, or inside `( )` or `{ }`; as an argument, write
  `(…)`: `echo ([1, 2, 3])`.

### Strings

A double-quoted string uses **Java escapes** — `\\`, `\"`, `\n`, `\t`, `\r`, `\uXXXX` — and `\$` for a literal dollar
sign. It also **interpolates**:

| In the string | Meaning |
|---|---|
| `$name` | The variable. |
| `$name.prop`, `$l[0]`, `$last.duration` | The variable followed by properties and integer indexes. |
| `$(expression)` | Any expression, or a pipeline, in parentheses: `"max = $(Math.max(4, 9))"`, `"$(ls \| count) files"`. |

A `$` that is not followed by a name or `(` is a plain dollar sign. Three traps:

- `.name` after a variable is always read as a property: **`"$name.txt"` looks for a property `txt`** and fails. Write
  `"$($name).txt"`, or `$name + ".txt"` inside a block.
- Method calls are not read after `$name`; use `$( )`: `"$($l.size()) items"`.
- A string is evaluated **before** the command runs, so `"$_.size"` outside a block is an error. Conditions on the
  current object go in a `{ }` block.

Text blocks `"""…"""` work as in Java (the opening quotes end their line; the common indentation is removed) and
interpolate the same way. Characters use single quotes (`'a'`, `'\n'`) at the start of a stage or inside blocks.

### Numbers and unit literals

Integers, decimals (`1.5`) and `L` suffixes are Java literals. **Sizes** are `512b 2kb 500mb 1gb 1tb` (multiples of 1024,
a `long` in bytes) and **durations** are `250ms 30s 5m 2h 7d` (a `Duration`); `1.5mb` is allowed. They work in
expressions (`$_.size > 1mb`, `now - 7d`) and, as words, in options (`--min 10kb`). `now` is the current `Instant`.

---

## 4. Pipelines: how objects flow

### What travels

Between two cmdlets, **Java objects** travel — not text. `ls` emits `FileEntry` records, `where` lets some of them
through, `sort` reorders them, and the display turns the objects that reach the end into a table only at the last moment.
What a stage receives therefore depends on what the previous one produced:

| From → to | What crosses the `\|` |
|---|---|
| cmdlet → cmdlet | the objects themselves (records, strings, numbers, anything) |
| expression → anything | the value, unrolled if it is a collection (see below) |
| native → cmdlet | the lines of the program's output, as `String` objects |
| cmdlet → native | the objects in their **displayed form** (a table for records, the text for strings), written to the program's input |
| native → native | the raw bytes, passed process to process |

```text
PJ> ls -r --filter *.java | where { $_.size > 10kb } | map { f -> f.name } | sort
PJ> git status --porcelain | where { $_.startsWith(" M ") }
PJ> ls | ^more
```

In the second line, `$_` is a `String` (a line of `git`), so you call `String` methods on it. In the first, `$_` is a
`FileEntry` and `size`, `name`, `dir`… are its properties. `help members $f` (or `help FileEntry`) lists what an object
offers; Tab completion knows the type produced by the previous cmdlet (`ls | where { $_.` offers `name`, `size`…).

### Unrolling

When a stage produces a collection, its **elements** are sent one by one: an `Iterable` (`List`, `Set`…), an array, a
stream (`Stream`, `IntStream`…), an `Iterator`, an `Optional` (nothing when empty). `String`, `Map` and `Path` are never
unrolled. At the start of a pipeline this includes variables:

```text
PJ> $l = List.of("apple", "banana")      # an assignment keeps the list as one object
PJ> $l | where { $_.length() > 5 }       # a value at the head of a pipeline is unrolled
banana
```

The `collect` cmdlet gathers a whole pipeline into **one** list, which is *not* unrolled when it goes to the next stage:
`ls | collect | map { l -> l.size() }` prints one number. Unrolling is lazy for streams and iterators.

### Streaming, cancellation and early stop

- Each stage runs concurrently in its own virtual thread, connected to the next by a **bounded queue** (256 objects): a
  fast producer waits for a slow consumer, so `ls -r /` into a slow stage does not fill the memory. The last stage runs in
  the shell's own thread.
- Results appear **as they are produced**; there is no wait for the previous stage to finish.
- A stage that has seen enough stops the ones before it: `ls -r / | first 5` ends the walk after five files, and
  `ls -r C:\ | ^more` followed by `q` stops the `ls`.
- **Ctrl+C** cancels every stage, including native processes and their children. A second Ctrl+C abandons a computation
  that ignores the first one.

### Stages that cannot receive objects

- A **built-in command** or a cmdlet that does not read its input cannot be after a `|`: `"ls" does not read pipeline objects`.
- A **block alone** is not a stage — write `where { … }` or `map { … }`.
- A **value** can only be the first stage.
- A cmdlet that expects a given type ignores the others with a non-blocking error:
  `object ignored: String received, FileEntry expected`.

### Errors in a pipeline

| Kind | Examples | Effect |
|---|---|---|
| **Non-blocking** | an unreadable file in `ls -r`, a `where` block that fails on one object | reported on the error stream, the object is skipped, the pipeline goes on; the statement counts as failed |
| **Blocking** | syntax error, unknown command, bad option, a Java exception | the whole pipeline stops |

The error stream is separate from the objects, so it never ends up in `where`; `2>&1` on a stage merges it on purpose,
`2> file` captures it. `--on-error` changes the handling per cmdlet. The recent Java exceptions are in `$errors`.

### Capturing the result

`$x = pipeline` stores what the pipeline produced: **nothing → `null`, one object → the object itself, several → a list**.
A native command's output is stored as `String` lines. `(pipeline)` does the same inside an expression
([§6](#6-parentheses-and-brackets)).

### The end of the pipeline

The objects that reach the end are displayed according to their type (FR-30): a table for successive records of the same
type, a `name : value` list for wide records, a key/value table for a `Map`, the text for scalars, `toString()` for the
rest. Sizes, dates and durations appear in readable form (`14.2 KB`). Redirection to a file writes the same text.

### A worked example

```text
PJ> ls -r ~/dev --filter *.java | where { f -> f.size > 10kb && !f.dir } | group ext | sort count -d | first 3
```

| Stage | Receives | Produces |
|---|---|---|
| `ls -r ~/dev --filter *.java` | nothing (first stage) | `FileEntry` records, as the walk finds them |
| `where { f -> … }` | one `FileEntry` at a time | the same objects, when the block returns `true` |
| `group ext` | the filtered files | one `Group(key, count, items)` per extension |
| `sort count -d` | the groups | the groups, ordered by `count`, descending |
| `first 3` | the sorted groups | the first three; when it has them, it stops the stages before it |

---

## 5. Blocks

A block, written `{ … }`, is a **function written inline**, handed to a cmdlet (`where`, `map`, `sort`, `select`, `group`, `range`, `join`…)
that applies it to its objects. A block in braces is also what you pass to a Java method that takes a functional
interface (`Predicate`, `Function`, `Comparator`, `Runnable`…).

### The three forms

| Form | Example | Meaning |
|---|---|---|
| `$_` block | `{ $_.dir }` | No declared parameter: the received object is `$_`. Short filters typed at the keyboard. |
| Lambda | `{ f -> f.size > 1mb }` | A named parameter, **without `$`**, as in Java. Better as soon as the expression is long. |
| Multi-parameter lambda | `{ (a, b) -> a.length() - b.length() }` | For comparators and reductions. `{ () -> "x" }` for none. |

A lambda can also be written **without braces between the parentheses of a Java call**: `$l.stream().filter(s -> s.length() > 4)`.
As a **cmdlet** argument braces are mandatory: `where f -> f.dir` is a syntax error (the `>` of `->` would read as a
redirection). A method reference (`String::length`, `FileEntry::name`, `ArrayList::new`) replaces a block that only calls a
method: `ls | map FileEntry::name`.

Which one to use ([FR-33b](SPECIFICATION.md)):

- `$_` for one-line filters that mention the object once or twice;
- a **named lambda** when the expression grows, mentions the object several times, takes two parameters, or sits **inside
  another block** (in the inner block `$_` would mean the inner object, so name the outer one):
  `where { f -> List.of("md", "txt").stream().anyMatch(e -> f.name.endsWith("." + e)) }`;
- a **method reference** when it is enough.

Shell variables keep their `$` in a lambda: `$min = 1kb; ls | where { f -> f.size > $min }`.

### The short form of `where`

`where size > 1mb` is `where { $_.size > 1mb }`. In this form only, the `>` after the property name is a comparison and
not a redirection. It accepts `== != < <= > >=`; the value is a number, a size, a duration, `true`, `false`, `null` or a
text (quoted if it holds a space).

### What a block contains

**One Java expression**, not a statement list. There is no `;`, no assignment (`=` is rejected: *to compare, use ==*), no
`if`/`for` (control structures are planned for v2).

| Category | Operators |
|---|---|
| Equality | `==  !=` — compare **values** (`Objects.equals`), numbers by value (`1 == 1L`) |
| Ordering | `<  <=  >  >=` — numbers; `Comparable`s such as dates, durations and strings |
| Logic | `&&  \|\|  !` — short-circuit, **operands must be booleans** |
| Arithmetic | `+  -  *  /  %` — Java rules; `+` concatenates when either side is a `String`; `Instant ± Duration` works |
| Conditional | `condition ? a : b` |
| Cast | `[long] x`, `[int] 3.9` (the Java form `(long) x` would be read as a group) |

Plus everything a value offers:

- **properties**: `$_.name` (without parentheses) is resolved, ignoring case, as a record component, then a getter
  (`getName()`, `isName()`), then a public field, then — on a `Map` — the key `"name"`; they chain: `$_.path.parent`;
- **method calls**: `$_.name.endsWith(".java")`, `Files.size($_.path)`, `Pattern.compile("a+").matcher($_).find()`;
- **static members and constructors** of the Java classes: `Math.max(1, 2)`, `List.of("png", "jpg")`, `new StringBuilder()`;
- **indexing**: `$l[0]`, `$l[-1]`, `$s[2]`, `$map["key"]` (single quotes make a `char`, not a key);
- **spread** `*.`: applies a property or a method to **each element** of a list: `$f*.name`, `$f*.name*.toUpperCase()`.
  A plain `.` always applies to the object itself (`$f.size()` is the number of elements, and `$f.name` on a list is an
  error that suggests `*.name`);
- **literals** (strings, numbers, units, `now`, `[1, 2, 3]`), **variables** (`$x`, the lambda parameters, `$_`);
- **groups with commands**: `(cat a.txt)`, `(ls | collect).size()` — the way to run a command from inside a block
  ([§6](#6-parentheses-and-brackets)).

Comparisons are case-sensitive, as in Java. Needs that other shells cover with special operators use Java methods:

| Need | Write |
|---|---|
| contains / starts / ends | `$_.contains("b")`, `$_.name.startsWith("Pa")`, `$_.name.endsWith(".java")` |
| regular expression | `$_.matches("^[a-m].*")` (whole text) |
| case-insensitive | `$_.toLowerCase().contains("readme")`, `$_.equalsIgnoreCase("ok")` |
| membership | `List.of("png", "jpg").contains($_.ext)` |

### How the block is applied

- The condition of `where` must return a **boolean**. Anything else — `where { f -> f.name }`,
  `null` — is a non-blocking error (`the block must return a boolean, got String`) and the object is skipped.
- An error while evaluating the block for one object (`$_.size > now`) is non-blocking: that object is skipped, the
  others go on. `--on-error silent` hides these reports, `--on-error stop` stops at the first.
- A `null` result of `map` emits nothing, and a collection result is unrolled (like `flatMap`).
- A block that receives the wrong number of arguments says so (`the lambda { f -> … } declares 1 parameter, 2 received`).
- A cmdlet may read a pair `name={ block }` (`select kb={ $_.size / 1kb }`): the word `kb=` and the block are two
  arguments that the cmdlet joins. At the head of a pipeline, `select content={ (cat a.txt) }` evaluates its blocks once,
  with no current object.
- The block text is parsed when the line is read, so a syntax error is reported before anything runs.

---

## 6. Parentheses and brackets

Parentheses `( … )` have four jobs; the shell tells them apart by what is inside and where they stand.

**1. A command or a pipeline whose value is taken** (a *subexpression*). The content is a full pipeline, run first; its
result replaces the group, following the capture rule — nothing → `null`, one object → itself, several → a list:

```text
PJ> (ls)*.name                               # names of all the files, always a list
PJ> (ls -r | where size > 1mb).size()        # number of big files
PJ> (env PATH).value.split(":")              # PATH as an array of folders (";" on Windows)
PJ> $n = (cat notes.txt -t).length()
PJ> select content={ (cat a.txt) }
```

The group holds **one pipeline** — `(ls ; pwd)` and `(ls &)` are syntax errors — and cannot redirect its output
(`(ls > f)` is an error). An empty `()` is an error.

**2. Plain grouping of an expression**, to force an order or to shield it from the command syntax:
`(1 + 2) * 3`, `($a > 1 && $b)`, `(Math.max(1, 2))`.

**3. The arguments of a Java call**, which must touch the name: `Math.max(3, 7)`, `$l.get(0)`, `new File("x")`.
A space before `(` makes it a separate argument or group.

**4. Interpolation in a string**: `"$(expression or pipeline)"` ([§3](#strings)). Outside a string, `$(` is not a command
substitution; use a group.

How the content is read: if it starts like an expression (`$x`, a literal, a Java name…), it is an expression, and may
be followed by a pipeline (`($l | where { … })`); otherwise it is a command. Quoted text and nested groups inside do not end
it early.

**As an argument of a native program**, a group gives **one** argument: the text of the value. A single value works
(`^echo (env HOME).value`, `git add ($f.path)`); a list becomes the text `[a, b]`. Pass the elements you need one by one.

**Brackets** have three uses: a list literal `[1, 2, 3]`, a cast `[long] 5` (the bracket content is a type and is
followed by an operand), and an index `$l[0]` (attached to the value). A list literal or cast at the start of a stage or in
a block is an expression; as an argument, wrap it in parentheses.

---

## 7. Variables

### Your variables

`$name = value` stores the result of a pipeline or expression, for the rest of the session:

```text
PJ> $dir = Path.of("/home/me/dev")
PJ> $files = ls -r $dir --filter *.java
PJ> $files.size()
```

- Names are made of letters, digits and `_`, and are **case-sensitive**. Spaces around `=` are optional.
- The assignment must start a statement (after `;`, `&&`, `||` or `&` too); a pipeline stage cannot assign.
  The whole pipeline on the right is captured (`$x = ls | where …`).
- What is stored is the value as is, with the capture rule of [§4](#capturing-the-result). A list stays one object until
  you put it at the head of a pipeline.
- You cannot assign a property or an element (`$x.y = 1` is an error).
- A name that was never set is an error: `unknown variable: $x`.
- Variables set in `profile.pj` are there when the shell starts; `powerj -c` and script files have no profile.
- Reading is `$name`, optionally followed by attached accesses: `$f.path`, `$l[0]`, `$x.size()`, `$f*.name`.

Two variables are used by the shell when **you** set them: `$debug = true` shows the full Java stack trace of unexpected
errors (`powerj --debug` does it too), and `$profile` holds the path of `profile.pj` (set by the shell at startup).

### Automatic variables

The shell maintains these. They take precedence over a variable of the same name that you assign.

| Variable | Value | Notes |
|---|---|---|
| `$_` | The **current object**: the object a block is being applied to. | Exists **only inside a `{ }` block** applied to the objects of a pipeline (or to the argument a Java method gives it). Anywhere else: `$_ only exists in a { } block applied to the objects of a pipeline`. |
| `$?` | `true` if the **last statement succeeded** (rules of [§1](#chaining-statements)). | Starts `true`. A failing native program makes it `false`; so does a cmdlet that reported an error. |
| `$exit` | The exit code of the **last native command**, or `null` before any, or for a detached/background program. | A shorthand for `$last.exitCode`. |
| `$last` | The **last native command**: `command` (absolute path), `args`, `pid`, `exitCode`, `duration`. | Never injected into the output. |
| `$pwd` | The current directory, a `Path`. | Follows `cd`. |
| `$home` | The home directory **at startup**, a `Path`. | `~` follows `HOME` at each use; `$home` does not change. |
| `$errors` | The recent Java exceptions, **most recent first**, up to 20. | `$errors[0].cause`, `$errors[0].class.name`, `$errors[0].stackTrace`. |
| `$perf` | The report of the last line measured in perf mode (Alt+P). | `null` before any measure. |

#### `$_` in detail

- In `where { $_.size > 1mb }` and `map { $_.name }`, `$_` is each object as it comes. Its type is the type of the
  stage's input: a `FileEntry` after `ls`, a `String` after a native command, whatever an expression produced.
- Each block call has **its own** `$_`. In a block nested in a Java call (`anyMatch(e -> …)`), `$_` of the inner block is the
  inner argument — name the outer object with a lambda parameter if you need both.
- In a one-parameter lambda `$_` also holds that parameter; use the name, it reads better.
- `$_` is bound for the duration of one call, and there is no `$a` / `$b` (use `{ (a, b) -> … }`).
- In a string, `"$_.name"` works inside a block and fails outside ([§3](#strings)).
- At the head of a pipeline `select name={ … }` has no current object.

```text
PJ> ls | where { $_.size > 1mb } | map { $_.name }
PJ> $r = ls | where { $_.dir } ; $r*.name
PJ> mvn -q verify ; $exit ; $?
PJ> sh -c "exit 3" ; $exit ; $last.duration       # 3, then how long the program ran
```

### Environment variables are not `$` variables

`$HOME`, `$PATH`, `$env:X` do **not** read the environment: `$HOME` is an unknown variable (and shell variables are
case-sensitive: the home directory at startup is `$home`). Environment variables are read and changed with the `env`
cmdlet — see the next section.

---

## 8. Environment variables

### The session environment

At startup the shell makes a **copy of its own process environment**. This copy is *the session environment*:

- `env` shows it; `env NAME` shows one variable as an `EnvVar(name, value)` record;
- `env --set NAME=value`, `env --unset NAME`, `env --append NAME value`, `env --prepend NAME value` change it. The change
  applies **from the next command** and lasts until the shell exits; to make it permanent, put the command in
  `profile.pj`. On Windows, names are case-insensitive;
- every **native program** starts with a copy of the session environment, in the session's current directory; this is how
  `env --set MAVEN_OPTS=-Xmx2g` reaches `mvn`. The shell does not maintain `PWD` / `OLDPWD`;
- the shell itself reads its settings from the **session** environment (table below), so `env --set` applies to them too,
  except for the ones read once at startup;
- `System.getenv()` in a Java expression returns the environment the process **started with**, not the session's (the
  JVM cannot change its own): use `env`.

```text
PJ> env --set GREETING=hello
PJ> (env GREETING).value                    # the value, as a String
PJ> env --append PATH ~/bin                 # ":" on Linux/macOS, ";" on Windows
PJ> env | where { $_.name.startsWith("JAVA") }
PJ> "home is $((env HOME).value)"           # inside a string, $( ) holds an expression
PJ> sh -c "echo \$GREETING"                 # the program sees it: \$ keeps the dollar sign for sh
```

`$GREETING` alone would be a PowerJ variable, not the environment variable: PowerJ never expands environment variables
itself, programs do.

### Variables the shell reads

| Variable | Read | Effect |
|---|---|---|
| `HOME`, then `USERPROFILE`, then the JVM's `user.home` | at each `~` | The home directory for `~` and for the protected paths of `rm` / `mv`. `env --set HOME=D:\me` applies at once. |
| `PATH` | at each command lookup | Where native programs are searched; separator `:` (Unix) or `;` (Windows). `env --append PATH …` makes a new tool visible to the next command and to `which`. |
| `PATHEXT` | at each lookup, Windows only | Executable extensions tried, default `.COM;.EXE;.BAT;.CMD`. |
| `POWERJ_HOME` | at startup, from the process environment | The configuration folder (default `~/.powerj`, `%USERPROFILE%\.powerj`): history, `config.properties`, `profile.pj`, `modules/`, `logs/`. |
| `POWERJ_LANG` | at startup, from the process environment | Language of the messages, `en` or `fr`. Precedence: `-Dpowerj.language`, then this variable, then `language` in `config.properties`, then the system language. |
| `POWERJ_NATIVE_ENCODING` | at each program launch | Encoding of the text exchanged with native programs: a Java charset name (`UTF-8`, `cp850`, `windows-1252`) or `auto` (default: the output code page of the Windows console, UTF-8 elsewhere). |
| `POWERJ_NATIVE_ENCODING_<NAME>` | at each program launch | Same, for one program only; `<NAME>` is the executable name in capitals, without extension, other characters turned into `_`: `POWERJ_NATIVE_ENCODING_GIT=UTF-8`. Takes precedence over the global one. |
| `POWERJ_NATIVE_ENCODING_STDIN` | at startup, non-interactive mode | Encoding of the lines read from standard input in `powerj -c …` when it is piped (else `POWERJ_NATIVE_ENCODING`, from the process environment). |
| `POWERJ_GLOB_IGNORE_CASE` | at each wildcard expansion | `true` / `1`: wildcards ignore case (`false`, `0` or unset: they keep it; any other value is an error). No effect on Windows, whose matcher already ignores case. |
| `POWERJ_PERF_AUTOEXIT` | after each measured line | `1`: perf mode (Alt+P) switches itself off after one line. |
| `POWERJ_PERF_JFR_SAMPLEPERIOD` | when a line is measured | Sampling period of the profiler in perf mode: `10ms` (default), `2ms`, `500us`; minimum `1ms`. |
| `POWERJ_PERF_JFR_ALLOCTHROTTLE` | when a line is measured | Limit of the allocations sampled: `100/s`, `5/ms`, `600/m`, or `off`. |
| `NO_COLOR` | at startup of the interactive shell | Any non-empty value disables colors. |
| `COLUMNS` | at startup, non-interactive mode on a terminal | Width used to lay out tables (default 120). In the interactive shell the width comes from the terminal. |

The three `POWERJ_PERF_*` variables and `POWERJ_NATIVE_ENCODING*` are usually set once in `profile.pj` with `env --set`.

---

## 9. Typing at the prompt

The prompt is `PJ <current directory>> `. Editing is Emacs-style (arrows, Home/End, Ctrl+←/→ by word, Ctrl+W, Ctrl+K,
Ctrl+U).

- **Continuation.** If a `"`, `(`, `{` or `[` is still open, or the line ends with `|`, `&&` or `||`, the prompt becomes
  `>> ` and the shell waits for the rest. A backslash at the end of a line is *not* a continuation, so `cd C:\` is valid.
  A command can therefore be written over several lines:

  ```text
  PJ> ls -r --filter *.java |
  >>   where { f ->
  >>       f.size > 10kb && !f.dir } |
  >>   map { f -> f.name }
  ```

- **History.** ↑/↓ browse it (with a typed prefix, only matching entries); Ctrl+R searches backwards. A line that starts
  with a space is not recorded, nor are consecutive duplicates. `!!`, `!n`, `!text` at the very start of a line re-run an
  entry and show the expanded command; elsewhere `!` is negation (`where { !$_.dir }`).
- **Tab completion** offers commands (tagged `[pj]`, `[internal]`, `[native]`), options with their description, paths,
  variables, the properties of `$_` and of lambda parameters, and Java classes and methods with their signatures. It runs
  nothing.
- **Colors** show how a word was read: cmdlets green, native programs cyan, unknown commands red, options gray, strings
  yellow, variables magenta.
- **Preview (Alt+V)** shows the first results of the line below it while you type, running only what changes nothing.
  **Perf mode (Alt+P)** measures each stage of the lines you run. See the README and FR-66 / FR-76.
- **Ctrl+C** clears the line being typed, or cancels the running command; **Ctrl+D** on an empty line, or `exit`, leaves.
- **Comments** (`#`) exist only in script files: in `profile.pj` and in a `.pj` file, a line whose first non-blank
  character is `#` is ignored, blank lines too. At the prompt, `#` is an ordinary character.
- **Non-interactive use.** `powerj -c "line"` runs one line; `powerj script.pj` runs a file (a command may span several
  lines with the same continuation rule); lines piped into `powerj -c` feed the first stage as `String` objects. There
  are no prompt, history or colors, and a blocking error ends the script. The exit code is that of `exit n`, otherwise 0
  on success, the code of the failing native command, or 1 for a PowerJ error.

---

## 10. What does not exist

Knowing what is *not* there avoids a lot of guessing (control structures and functions are planned for v2):

- no `if`, `for`, `foreach`, `while`, functions, no `$(…)` command substitution outside a string, no backticks, no here-documents;
- no input redirection `<`, no `|&`, no `&` for cmdlets, no `fg` / `bg`;
- no aliases, no `$VAR` environment expansion;
- no bitwise operators in blocks, no assignment in blocks;
- no property assignment (`$x.y = 1`);
- no PowerShell or bash syntax compatibility: `-and`, `-match`, `-like` are Java operators and methods here
  ([mapping table](SPECIFICATION.md#124-powershell--powerj-mapping)).

---

## 11. Common mistakes

| You typed | What happens | Write instead |
|---|---|---|
| `ls \| where f -> f.dir` | `"->" outside a block: … a lambda is written in braces` | `ls \| where { f -> f.dir }` |
| `ls \| { $_.name }` | `a block alone is not a pipeline stage: write map { … } … or where { … }` | `ls \| map { $_.name }` |
| `$a > 1` | writes `$a` into a file named `1` | `($a > 1)` |
| `where { $_.size = 3 }` | `"=" in an expression: to compare, use ==` | `where { $_.size == 3 }` |
| `where { f -> f.name }` | non-blocking `the block must return a boolean, got String`, objects skipped | `where { f -> !f.name.isEmpty() }` |
| `$_` at the prompt | `$_ only exists in a { } block …` | put it in a block |
| `ls \| $x` | `a value can only be the first stage of a pipeline` | `$x \| …` |
| `ls \| cd` | `"cd" does not read pipeline objects` | `cd` is a first stage |
| `ls > out.txt \| where …` | `">" redirects the output of the whole pipeline: put it at the end` | `ls \| where … > out.txt` |
| `ls &` | `& only applies to native commands` | run the native program, or no `&` |
| `$x = sleep 5 &` | `no "&" with an assignment` | `sleep 5 &` |
| `cd $home/docs` | two arguments, `cd` refuses | `cd "$home/docs"` |
| `--out=$file` | the text `--out=$file` is passed | `"--out=$file"` |
| `"$name.txt"` | looks for a property `txt` | `"$($name).txt"` |
| `echo $a + 1` | three arguments | `echo ($a + 1)` |
| `$HOME` | `unknown variable: $HOME` | `(env HOME).value`, or `$home` |
| `sort < data.txt` | `<` is passed to `sort` as an argument | `cat data.txt \| sort` |
| `x = 3` | `x` is read as a command name, then `unexpected "="; assignment: $name = value` | `$x = 3` |
| `Math` alone | `unknown command: Math` (a bare class name does not start an expression) | `Math.PI`, `Math.max(1, 2)` |

When a line does not behave as expected, `which name` tells what a word resolves to, `help members $x` shows what a
value offers, and `$debug = true` shows the stack trace of an unexpected error.
