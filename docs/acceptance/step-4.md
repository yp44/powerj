# Acceptance test — Step 4: pipeline and `where`

**Goal:** verify the `|` pipeline (cmdlets and native commands mixed), `{ }` blocks with Java syntax, the `where` cmdlet, the `2>&1` redirection, Ctrl+C on a pipeline and non-interactive mode (`powerj -c`, `.pj` file, `--cd` start-up directory).

> Messages are shown in the system language; set `language=en` in config.properties (or `POWERJ_LANG=en`) to get the English texts quoted here.

## Getting the deliverables

As in the previous steps: **Actions** tab, latest run of the **CI** workflow, artifact `powerj-windows-x64-installer` or `powerj-windows-x64-portable`.

## Scenario

| # | Action | Expected result |
|---|---|---|
| 1 | `cd ~` then `ls -r \| where { $_.size > 1mb }` | Files larger than 1 MB in the tree, as a table, displayed as they are found. |
| 2 | `ls \| where { $_.name.endsWith(".txt") && !$_.dir }` | The `.txt` files of the folder (Java methods on properties, `&&` and `!` operators). |
| 3 | `ls \| where size > 10kb`, then `ls \| where ext == txt`, then `ls \| where dir == true` | Short form, equivalent to `where { $_.size > 10kb }`; here `>` is the operator, not a redirection. |
| 4 | In a git repository: `git status --porcelain \| where { $_.startsWith(" M ") }` | Only the lines of modified files (the output of a native command becomes `String` lines). |
| 5 | `ipconfig \| where { $_.contains("IPv4") }` | Lines containing `IPv4`, accented characters correct. |
| 6 | `ls -r C:\Windows\System32 \| ^more` then Space, then `q` | The table scrolls page by page; `q` returns control immediately (the upstream `ls` stops). |
| 7 | `ls -r C:\ \| where { $_.ext == "log" }` then Ctrl+C | Immediate stop, `^C` in red, new prompt; the shell remains usable. |
| 8 | `git commandeinconnue 2> err.txt` then `^cmd /c type err.txt` | `err.txt` contains git's error message. |
| 9 | `env \| where { $_.name.startsWith("JAVA") }` | Variables whose name starts with `JAVA`. |
| 10 | `^cmd /c "echo dehors& echo erreur 1>&2" 2>&1 \| where { $_.contains("erreur") }` | `erreur`: with `2>&1`, the error stream joins the output and goes through the pipeline. |
| 11 | `^cmd /c "echo b& echo a" \| ^sort` | `a` then `b`: native → native, bytes passed directly. |
| 12 | `$gros = ls -r \| where size > 1mb` then `$gros*.name` then `(ls \| where { $_.dir })*.name` | The result of a pipeline can be assigned and used inside parentheses. |
| 13 | `ls \| where { $_.size > now }` | For each object, a non-blocking error in red (`where: ">" is not possible between Long … and Instant … (object skipped: …)`), then the prompt; adding `--on-error silent` hides them, `--on-error stop` stops at the first one. |
| 14 | `ls \| where { $_.size = 3 }` | `syntax: "=" in an expression: to compare, use == (position …)`: nothing is executed. |
| 15 | `ls \| ls` | `"ls" does not read pipeline objects`. |
| 16 | `ls \| where { $_.modified > now - 7d }` | Files modified less than 7 days ago (`now`, durations `7d`, `2h`, `30m`…). |
| 17 | From `cmd.exe`, in the folder of `powerj.exe`: `powerj -c "ls C:\Windows \| where { $_.size > 1mb }"` | The table, then back to `cmd.exe`. |
| 18 | `powerj -c "^cmd /c exit 3"` then `echo %ERRORLEVEL%` | `3`. |
| 19 | `dir /b C:\Windows \| powerj -c "where { $_.endsWith(\".exe\") }"` | The `.exe` files of `C:\Windows`: the lines from standard input feed `where`. |
| 20 | Create `test.pj` containing the two lines `ls C:\Windows \| where dir == true` and `exit 5`, then `powerj test.pj` and `echo %ERRORLEVEL%` | The folders of `C:\Windows`, then `5`. |
| 21 | `powerj -c "commandeinconnue"` then `echo %ERRORLEVEL%` | `unknown command: commandeinconnue`, then `1`. |
| 21a | From `cmd.exe`, in any folder: `powerj --cd C:\Windows -c pwd`, then `powerj --cd=C:\Windows\System32` and read the prompt | `C:\Windows`, then the prompt `PJ C:\Windows\System32>`: the session starts in the folder given. |
| 21b | `powerj --cd ~ -c pwd`, then `powerj --cd ..` from a subfolder of your home folder | Your home folder (`~` expanded although `cmd.exe` does not), then the prompt shows the parent of the folder you were in. |
| 21c | Create `C:\work\test.pj` containing `pwd`, go to `C:\work`, run `powerj --cd C:\Windows test.pj` | `C:\Windows`: the file is found where it was typed, and runs in the `--cd` folder. |
| 21d | `powerj --cd C:\absent -c pwd` then `echo %ERRORLEVEL%` | `PowerJ: --cd: directory not found: C:\absent` (a file instead of a folder: `not a directory`), then `1`; the shell is not started. |
| 21e | `powerj --cd` then `echo %ERRORLEVEL%` | The usage line (which lists `[--cd <dir>]`), then `2`. |
| 21f | Put `cd C:\Users` in `profile.pj`, run `powerj --cd C:\Windows`, then `powerj --no-profile --cd C:\Windows` | First prompt `PJ C:\Users>` (the profile wins), then `PJ C:\Windows>`. |

Linux / macOS variant (archive `powerj-linux-x64`): replace `ipconfig` with `ip addr`, `^cmd /c …` with `sh -c "…"`, `dir /b` with `ls -1`, and launch `bin/powerj`.

## Syntax points to know

- In a `{ }` block, `$_` is the current object and the syntax is Java's: `==` compares values, strings are case-sensitive (`$_.toLowerCase().contains("readme")` for the opposite), and common needs are handled through Java methods (`contains`, `startsWith`, `matches`…).
- Outside a block, a double-quoted string interpolates variables: `"$_.size"` is evaluated before the call. Write the condition in a `{ … }` block.
- The short form of `where` accepts `==`, `!=`, `<`, `<=`, `>`, `>=`; the value is a number, a size, a duration, `true`, `false`, `null` or a text (without quotes if it contains no space).
- In non-interactive mode, lines received on standard input are decoded like the output of native commands (`POWERJ_NATIVE_ENCODING`, or `POWERJ_NATIVE_ENCODING_STDIN` for a setting specific to input).

## Known limitations of step 4

- No Java class calls yet (`List.of(…)`, `Math.max(…)`, `new …`): only object methods (`$_.contains(…)`). Step 5.
- No `$(expr)` in strings yet, nor `"""…"""` text blocks.
- The exit code of a pipeline is that of its last stage (a blocking error in an upstream stage also makes it fail).
- No Tab completion yet (step 6).
