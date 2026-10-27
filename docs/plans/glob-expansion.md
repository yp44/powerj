# Analysis — expanding wildcards before the arguments reach a command (issue #51, FR-40)

**Status:** proposal, waiting for the maintainer's decision, except the case rule, which is decided (see
[Case rule](#case-rule-decided-by-the-maintainer)). Nothing is implemented; this document is the analysis the issue asks
for ("Pick one for native programs … and, either way, for the cmdlets").

## Context
FR-40 says arguments are passed "after variable and wildcard expansion", which "can be disabled by putting the argument in
quotes". Only variables and `~` (FR-62) are expanded today. The request is to go further than the issue's option 1:
**resolve every unquoted wildcard before the command starts**, for native programs (`^echo *.txt` prints the file names)
**and for cmdlets** (`ls *.txt` receives the list of files).

This document says what the code allows, what each choice breaks, and what it costs. It is based on reading the code and
the measurements written in the issue. The project needs JDK 25, so the behaviour described here was **not run** while
writing it.

## How arguments reach a command today
| Step | Where | What happens |
|---|---|---|
| Lexing | `Lexer.isWordEnd` | A word ends at a blank, `;`, `\|`, `>`, `"`, `(`, `)`, `{`, `}`. `*`, `?` and `[` do not end it. |
| Parsing | `Parser.argument` | A bare word becomes `Ast.WordArgument`; a quoted text, a variable, a group or a block becomes `Ast.ExpressionArgument`. |
| Evaluation | `Evaluator.argument` | `WordArgument` gets `~` expanded (FR-62), `ExpressionArgument` is evaluated. **The quoting is still known here.** |
| Preparation | `PipelineExecutor.prepare` | All the arguments are evaluated first, then the command is resolved (built-in, cmdlet, native). The two lists are aligned one to one. |
| Native | `prepare`, `NativeCall` branch | After `PreviewGuard.deny`, the values become `String`s for `ProcessBuilder`. |
| Cmdlet | `CmdletRunner.run`, `OptionBinder.bind` | The cmdlet receives `List<Object>` and binds options. `--` ends the options. The quoting is lost: `"*.txt"` and `*.txt` are the same `String`. |
| Wildcards | `FileArguments.expand`, `Ls`, `Touch`, `Ps` | Each cmdlet expands its own arguments with `Globs`, on the **last path element only**. |

The tilde is the precedent: it is expanded in `Evaluator.argument`, for **every** command, on unquoted words only.

## Native programs
Expanding in the `NativeCall` branch of `prepare` is small and safe: the quoting is known, the preview guard has already
run (no directory is read on each keystroke of the live preview), and built-in commands and cmdlets are not touched.

Rules proposed by the issue, kept: an unquoted word with `*`, `?` or `[...]` is expanded against the current directory;
no match leaves the word unchanged (bash without `nullglob`); a quoted word is never expanded; Windows is left to the
program (`cmd /c dir *.txt` and `findstr` read their wildcards themselves, so expanding first would change their meaning).
The issue says the C runtime expands on Windows; to my knowledge that holds for MinGW and msys programs but not by default
for MSVC ones. **To check before it is written in the ADR.**

Things the implementation must handle:
- **File names that look like options.** `^rm *` with a file named `-rf` would pass an option. Prefix a match that starts
  with `-` with `./`.
- **Wildcards in directories.** `Globs` and `FileArguments.expand` only expand the last element; `src/*/pom.xml` needs new
  code. `Globs.isPattern` only sees `*` and `?`, not `[abc]`. The lexer ends a word at `{` and `}`, so `{a,b}` cannot be
  written.
- **Spaces in a pattern.** There is no escape character and no partial quoting (a word ends at `"`), so
  `"my docs"/*.txt` cannot be written. Names *found* by a wildcard are fine (one argument each). The issue's "directory
  name containing a space" can only be tested for found names, not for the pattern. A list is not spread into a native
  program either: `Values.text(list)` gives `[a, b]`.
- **Case.** `Globs` ignores case by default today (review C-22). The maintainer decided otherwise: see
  [Case rule](#case-rule-decided-by-the-maintainer).
- **Modules.** `powerj-core` does not require `io.powerj.fs`. It needs the `requires`, the Maven dependency and a check
  of `ArchitectureTest` and of the jlink image.
- **Limits and display.** A very long list can exceed `ARG_MAX` (error `native.startFailed`, generic). `jobs` and `$last`
  show the expanded names. Sorting: by name, case ignored, as `FileArguments.BY_NAME`.
- **Known shell traps appear:** `find . -name *.txt`, a regex with `?`. Double quotes protect; single quotes are a Java
  `char` in PowerJ and do not.

## Cmdlets: why "resolve everything first" breaks things
An evaluator cannot tell which arguments of a cmdlet are paths. Expanding every unquoted word before any command would
break examples that are already documented, as soon as the current folder holds a matching file:

| Documented usage | What would happen |
|---|---|
| `ls -r --filter *.java \| grep TODO` (`docs/acceptance/grep.md`) | `*.java` becomes `A.java B.java`: `--filter` takes the first, the second becomes a path. |
| `ps java* \| where …` (`docs/acceptance/ps-kill.md`) | `ps` matches **process names** with `Globs`, not files. |
| `grep colou?r f.txt` | The first argument is a regular expression; a file named `colour` would replace it silently. |
| `import java.time.*` (the profile, `docs/acceptance/profile.md`) | A file `java.time.x` is enough to break it, if the built-ins are not excluded. |
| `ls *` | `ls` lists the **content** of a folder given as an argument: the meaning changes. |

Other consequences:
- The cmdlets would have to stop expanding themselves, or a file name that contains `*` is expanded twice. Then
  `cat "*.txt"` becomes literal (consistent with FR-40, but a behaviour change).
- Telling the cmdlet which parameters are paths changes `powerj-api` (a new attribute on `@Option`, the `ApiSurfaceTest`
  snapshot), and the binder must keep the "bare word or not" distinction, which touches `CmdletRunner` (`--help`,
  `extractOnError`). Third-party cmdlets are a contract too (ADR 0003).
- The cost of the change is lowest now: nothing is released (#47) and the API may still break (`410e41b`).

## Case rule (decided by the maintainer)
**Wildcards are case-sensitive by default; an environment variable makes them case-insensitive.** The rule is the same for
native programs and for cmdlets. It is independent of options A to C below: the default of the cmdlets changes whichever is
chosen.

Where it stands today: `Globs.matcher(pattern)` ignores case, and `ls`, `ls --filter`, `cat`, `grep` (through
`FileArguments.expand`) and `ps` use that default. `rm`, `cp`, `mv` and `touch` already ignore case only on Windows
(`FileOps.WILDCARDS_IGNORE_CASE = HostOs.WINDOWS`, review C-22). I found nothing in the specification about the case of file
wildcards; it says "case ignored" for `ps` (FR-74).

Proposed shape (the name and the values are mine, to confirm):
- **Variable `POWERJ_GLOB_IGNORE_CASE`**, like `POWERJ_NATIVE_ENCODING` (FR-40b): read from the session environment at each
  use, so `env --set POWERJ_GLOB_IGNORE_CASE=true` applies from the next command. Cmdlets read it through
  `CmdletContext.environment()`, so `powerj-api` does not change; the native expansion reads `Session.environment()`.
  `true` or `1` ignores case, `false` or `0` keeps it, anything else is an explicit error, as for an invalid charset name.
- **One helper in `powerj-fs`** that turns the variable into the `ignoreCase` flag of `Globs.matcher(pattern, ignoreCase)`.
  It replaces `FileOps.WILDCARDS_IGNORE_CASE`, and the one-argument `Globs.matcher(pattern)` becomes case-sensitive.

What changes:
- **For the reading cmdlets, on Linux and macOS.** `ls *.txt`, `cat README*` and `grep x *.md` stop matching `notes.TXT` or
  `readme.md`. This is a behaviour change. It brings them in line with `rm`, `cp`, `mv`, `touch` and with the native
  programs. The tests that state the old rule must say the new one: `GlobsTest.theDefaultMatcherIgnoresCase`,
  `FileWalkerTest.globsIgnoreCase`, and the `*.txt` case of `LsTest` that expects `notes.TXT`.
- **On Windows, nothing.** The matcher of the platform ignores case and cannot be made sensitive
  (`GlobsTest` already skips that test there). The variable has no effect, and native programs keep their own rules. Making
  Windows sensitive would need a matcher of our own (glob to regular expression); not proposed.
- **On macOS** the default file system ignores case but the rule follows the text of the pattern, as C-22 already chose:
  `^ls *.TXT` does not list `a.txt` unless the variable is set.
- **`ps` must keep ignoring case**, since FR-74 says so and process names are not file names. `Ps` has to ask for it
  (`Globs.matcher(pattern, true)`) so that the new default does not change it. *Question below.*
- **`rm`, `cp`, `mv`, `touch` with the variable set** ignore case everywhere: `rm README*` then removes `readme.md`, the
  risk C-22 removed. It is an explicit choice of the user, and `rm -n` shows the list first; the variable's documentation
  should say it. The other possibility is to apply the variable only to reads and natives.
- **Tab completion** (`PathCompleter`, `ignoreCase = Platform.isWindows()`) has its own rule and is not touched.

To do with it: the rule in FR-40 and the variable in the table of environment variables of the specification, a line in the
CHANGELOG (behaviour change), and tests for the sensitive default (skipped on Windows), the variable, an invalid value, and
`ps` unchanged. About half a day, on its own.

## Options
| | Native programs | Cmdlets | Cost* |
|---|---|---|---|
| **A. Everything first** (the request, literally) | expanded | every unquoted word expanded before the cmdlet | high, and breaks the table above |
| **B. Native only** (issue option 1) | expanded on Linux and macOS | keep their own expansion; fix the FR-40 sentence: quotes do not stop a cmdlet from expanding | about a day |
| **C. B, then quote-aware cmdlets** | as B | only the parameters declared as paths are expanded by the shell, with the quoting respected; `--filter`, `ps` names and the `grep` pattern are not | B, plus 2 to 3 days and an ADR |

\* My estimates, not measured.

## Recommendation
1. **Do B now.** It removes `Status: partial` from FR-40, has no effect on what exists, and costs little.
2. For the cmdlets, **do not expand globally.** Write the rule down (a cmdlet always expands its path arguments, whatever
   the quoting) and keep **C** as a later, separate decision.
3. If B is accepted, the issue's "done when" applies: tests for a match, no match, a quoted word, hidden files, case on
   Linux, a found name containing a space, a name starting with `-`; a row in the acceptance sheet; FR-40 updated; a
   CHANGELOG line.

## Decisions for the maintainer
Decided: wildcards are case-sensitive by default, and an environment variable makes them case-insensitive (above).

Still open:
- Natives: limit to Linux and macOS, with `./` before names that start with `-`?
- Cmdlets: B (fix the sentence only) or C (declared path parameters, API change)?
- Windows natives: confirm "left to the program", after checking the MSVC / MinGW behaviour.
- Case: the name and values of the variable; `ps` keeps ignoring case (FR-74) or follows the variable; the variable applies
  to `rm`, `cp`, `mv`, `touch` or only to reads and natives.

## References
Issue #51; FR-40, FR-62; review §16.4 and C-22; `docs/acceptance/grep.md`, `ps-kill.md`, `profile.md`.
