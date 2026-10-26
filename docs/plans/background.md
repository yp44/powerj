# Plan — background execution with `&` (mini-iteration 24, FR-75)

## Context
Today a lone `&` is rejected by the lexer (`syntax.loneAmpersand`, `Lexer.java`), and a native command holds the shell until it ends. Only Windows GUI executables are launched detached (FR-39, `ExecutableKind.isWindowsGui`): on Linux, `xeyes` blocks the prompt. Goal: `xeyes &` gives the prompt back at once, as on Unix.

Decisions taken with the user:
1. **Native commands only**: a native command, or a pipeline made only of native stages. A cmdlet, a Java expression or a built-in followed by `&` is a blocking error (`& only applies to native commands`).
2. **Output**: stdout/stderr inherited from the terminal (as in bash), unless redirected with `>` / `2>`. **stdin is never read**: it comes from the null device.
3. **Minimal management + `jobs`**: `[1] 12345` at launch, `$last.pid` set, `[1] done xeyes (exit 0)` before the next prompt, a `jobs` built-in that lists the jobs as objects, stopping through the existing `kill` (`jobs | kill`). No `fg`/`bg`/`wait`.

Branch: `claude/modest-newton-cmtafy`, from `main` @ a3dceeb.

## Syntax
```text
xeyes &
find / -name "*.log" > logs.txt 2> /dev/null &
xclock & xeyes &              # two jobs
make & echo started           # & also separates statements, like ;
```
- `&` ends a statement and acts as a separator (like `;`): `a & b` starts `a` in the background, then runs `b`.
- `&` at the end of the line is allowed (unlike `&&`), and does not open a continuation (FR-02).
- Syntax errors: `&` alone, `$x = cmd &` (no capture of a background job), `a & && b`.
- `&&` and `2>&1` keep priority in the lexer (already the case).

## Behaviour
- **Launch**: the usual `ProcessBuilder` (current directory, session environment, redirections), but **without waiting**: the job is recorded and the result is a `NativeRun` with `exitCode = null`, as for FR-39. `$?` is `true` if the launch succeeded.
- **stdin**: `Redirect.from(nullDevice)` (`/dev/null` or `NUL`); the console is **not** paused (no `io.console().pause()`).
- **stdout/stderr**: `INHERIT` in interactive mode without redirection; `DISCARD` in non-interactive mode without redirection (no pump thread to keep alive); the file when redirected. A background native pipeline uses `ProcessBuilder.startPipeline`: null device into the first process, terminal/file out of the last one.
- **Ctrl+C** (Unix): the terminal sends `SIGINT` to the whole foreground process group, background jobs included. Java cannot call `setsid`, so on Unix the program is launched through `/bin/sh -c 'trap "" INT QUIT; exec "$0" "$@"' prog args…`: the ignored disposition survives `exec`, which is what `sh` itself does for its background jobs. Thanks to `exec`, the recorded pid is the program's. On Windows, GUI programs are not affected; a **console** program in the background still gets the console's Ctrl+C: documented limitation.
- **End**: `onExit()` of the last process marks the job done with its exit code; before each prompt the REPL prints `[1] done xeyes (exit 0)` (fr: `[1] terminé xeyes (code 0)`) and forgets the job.
- **Exit of the shell**: the jobs keep running (as in bash with the terminal left open). No automatic kill.
- **Preview** (FR-66): a line with `&` gives `preview.off.background`.
- **Script / non-interactive mode**: `&` allowed; no `[1] pid` line and no end notification.

## `jobs`
A built-in, not a cmdlet: it needs the job table of the session, which lives in `powerj-core`.
```java
@Display(columns = {"id", "pid", "running", "exitCode", "command"})
public record Job(int id, long pid, String command, Instant start, boolean running, Integer exitCode) { }
```
- `jobs` emits one `Job` per job still tracked (running, or ended but not yet reported), sorted by id; the ended ones are then forgotten, as in bash.
- Numbering: the smallest free id from 1, as in bash.
- `jobs | kill`: `Kill` (`powerj-cmdlets`, which does not depend on `powerj-core`) also accepts **any record with a `pid` component of type `long`/`Long`** (`Class.getRecordComponents()`). This covers `Job`, and user records, without coupling the modules.

## Implementation
| File | Change |
|---|---|
| `lang/Token.java`, `lang/Lexer.java` | New token `Token.Background`: a lone `&` gives it instead of the exception; attached to a word, `&` ends the word only before a blank, `;` or the end of the line (`?a=1&b=2` stays whole). |
| `lang/Parser.java`, `lang/Ast.java` | The `&` that **follows** a statement marks it: `boolean background` on `Ast.Statement`. The next step is chained with `ALWAYS`. `&` allowed at the end of the line. Background + assignment rejected. |
| `lang/messages_*.properties` | Remove `syntax.loneAmpersand`; add `syntax.backgroundAssignment`. |
| `exec/Interpreter.java` | `statement()`: if `background`, check that every stage resolves to a native command (reuse `segments()` / `prepare()`: only `NativeStep`s), otherwise `PjException(background.nativeOnly)`. Call `nativeRunner.startBackground(…)`, record the job, print `[id] pid`, set `$last`. `jobs` built-in. Preview off. |
| `exec/NativeRunner.java` | `startBackground(List<Command>, Session, ShellIo, Optional<FileTarget> out, Optional<FileTarget> err)` → `List<Process>`. Factor out the existing construction of the `ProcessBuilder`s (environment, directory, redirections); `sh -c trap` wrapper on Unix (`Platform.isWindows()`). |
| `exec/Jobs.java`, `exec/Job.java` (new) | Thread-safe table: `add(command, processes)`, `list()`, `drainFinished()` for the notification. |
| `exec/Session.java` | `Jobs jobs()`. |
| `shell/Repl.java` | Before `reader.readLine(prompt())`: print `session.jobs().drainFinished()`. |
| `shell/InputCompleteness.java` | A line ending with a lone `&` is complete (`endsWith("&&")` is already right; add a test). |
| `exec/Highlights.java`, `exec/Completions.java` | A lone `&` is a separator: what follows is a command position (Highlights already handles `&`; Completions only handles `&&`). |
| `cmdlets/Kill.java` | Generic case: record with a `pid` component. |
| i18n (core and cmdlets, en and fr) | `background.started`, `background.done`, `background.nativeOnly`, `preview.off.background`, `jobs.*`. |
| `Help` / completion | `help jobs`, `jobs [internal]`. |

## Docs
- Specification: new **FR-75 — Background execution** (§3.10, next to FR-39); grammar §12.1 (`script = statement { ( ";" | "&&" | "||" | "&" ) statement } [ "&" ]`); chaining table; §11 row 24; PowerShell mapping §12.4 (`Start-Process`); the list "What stops the preview".
- `docs/acceptance/background.md` (manual checklist).
- README: `xeyes &` and `jobs`.

## Tests
- `LexerTest` / `ParserTest`: `xeyes &`, `a & b`, `a &; b`, `a & && b` (error), `$x = a &` (error), `cmd 2>&1 &`; `&&` / `||` unchanged; `&` inside a string or a block `{ a && b }` unchanged.
- `InterpreterTest` / `NativeRunnerTest` (Unix, `assumeFalse(Windows)` where needed):
  - `sleep 5 &` returns in less than a second, `$last.pid` is alive, `$last.exitCode` is `null`, `$?` is `true`;
  - `jobs` lists the job; after its end, the notification, then it is gone;
  - `sh -c 'echo hi' > out.txt &` writes the file;
  - the process ignores `SIGINT` (native `kill -INT`: still alive);
  - `ls &` and `ls | sleep 1 &`: `nativeOnly` error;
  - background native pipeline `printf 'a\nb\n' | sort > out.txt &`.
- `KillTest`: a `Job`, and a user record with a `pid`, are accepted.
- `InputCompletenessTest`: `xeyes &` is complete.
- `PreviewTest`: `sleep 1 &` turns the preview off.

## Verification
- `./mvnw verify`
- Manually (Linux with X, or Windows):
```text
xeyes &                 # [1] 12345, prompt at once
xclock & jobs           # two jobs, running
jobs | where { $_.command == "xclock" } | kill
<Enter>                 # [2] done xclock (exit 143)
sleep 30 & sleep 100    # Ctrl+C: only sleep 100 stops; jobs still shows sleep 30
ls &                    # error: & only applies to native commands
exit                    # xeyes stays open
```

## Open questions
None (see the decisions in Context). Known limitation, documented: on Windows, a **console** program in the background gets the console's Ctrl+C.
