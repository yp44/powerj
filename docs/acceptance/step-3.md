# Acceptance test — Step 3: object model, `ls`, `env`, `help`

**Goal:** verify that cmdlets return Java objects whose properties can be read, table display, Unix-style option binding, `env` and help.

> Messages are shown in the system language; set `language=en` in config.properties (or `POWERJ_LANG=en`) to get the English texts quoted here.

## Getting the deliverables

As in the previous steps: **Actions** tab, latest run of the **CI** workflow, artifact `powerj-windows-x64-installer` or `powerj-windows-x64-portable`.

## Scenario

| # | Action | Expected result |
|---|---|---|
| 1 | `cd ~` then `ls` | Table `name size modified dir` (the absolute path is the `path` property, not displayed: `(ls)*.path`): folders first, then files, in alphabetical order; human-readable sizes (`14.2 KB`; `14,2 KB` in French), local dates. |
| 2 | `ls -a` | Hidden files appear as well. |
| 3 | `ls -r --filter *.txt` (in a folder containing `.txt` files) | All `.txt` files in subfolders; the pattern is case-sensitive (`*.TXT` finds the files whose extension is in capitals, FR-40; on Windows the system ignores case). |
| 3b | `ls --max-depth 2` (in a folder with subfolders of subfolders) | The folder, its subfolders and their entries, nothing deeper; `ls --max-depth 0` is refused (`--max-depth expects a depth of at least 1, got 0`). |
| 4 | `ls -d`, then `ls --files`, then `ls C:\Windows *.ini` | Folders only; files only; contents of `C:\Windows` followed by the `.ini` files of the current directory. |
| 5 | `(ls)*.name` | Names only, one per line (`*.`: the property of each element). |
| 6 | `$f = ls --files` then `$f[0].size`, `$f[-1].name`, `$f[0].path.parent`, `$f[0].ext` | Size in bytes, name of the last one, parent folder, extension without the dot. |
| 7 | `$f*.name`, then `$f.size()` and `$f.name` | The names of all files; the number of files; an error saying to write `*.name` (without `*`, `.` applies to the list itself). |
| 8 | `$f[0].siz` | Error in red: `FileEntry has no property 'siz' (properties: name, size, …)`. |
| 9 | `ls --recurce` | `ls: unknown option --recurce, did you mean --recurse?` |
| 10 | `ls -ra`, `ls --filter=*.txt`, `ls -f*.txt` | Grouped options, `=` and attached value: all accepted. |
| 11 | `ls C:\nulle\part; "suite"` | `ls: not found: C:\nulle\part` in red, then `suite`. |
| 12 | `ls C:\nulle\part --on-error silent` | No message; `$?` is `false`. |
| 13 | `help` | Built-in commands, then cmdlets by category (`Files`: `ls`; `Filters`: `where`, `map`, `collect`; `System`: `env`). |
| 14 | `help ls` and `ls --help` | `Usage: ls …` synopsis, `Options:` with descriptions, output type `FileEntry (name, size, modified, path, dir, ext)`, examples. |
| 15 | `help members $f[0]` | Properties (`name`, `size`…) and methods of `FileEntry`, with their types. |
| 16 | `which ls env ^ls cd` | `ls → cmdlet (io.powerj.cmdlets)`, `env → cmdlet (…)`, `^ls → native …` (if an `ls.exe` exists, e.g. Git Bash), `cd → built-in command`. |
| 17 | `env` then `env PATH` then `(env PATH).value` | All variables; the `PATH` line; the value alone. |
| 18 | `env --set MAVEN_OPTS=-Xmx2g` then `env MAVEN_OPTS` then `env --unset MAVEN_OPTS` | Variable created, displayed, deleted (for this session only). |
| 19 | `env --append PATH C:\outils` then `which monoutil` (a tool placed in `C:\outils`) | The tool becomes executable in the session and `which` finds it. |
| 20 | `env --set POWERJ_NATIVE_ENCODING=windows-1252`, then `$l = ipconfig` and `$l`; `env --unset POWERJ_NATIVE_ENCODING` | `ipconfig`'s accented characters are wrong (setting applied immediately), then restored. |
| 21 | `ls > liste.txt` then `^cmd /c type liste.txt` | The table is written to the file, without truncation. |
| 21b | (Linux/macOS) `^sh -c "echo e1 >&2; echo x" \| first --on-error stop 2> e.txt`, then `cat e.txt` | `e.txt` holds the error of the first stage (`e1`) **and**, if the last stage ends with a blocking error, that error: a blocking error of the last stage no longer truncates the file (#168). The automated test is `BlockingErrorRedirectionTest`. |
| 21c | `help ls`, then `ls -p x`, then `cp` (without arguments), then `kill -9` | `help ls` shows `--parallel <n>`, `--filter <text>`… instead of `<value>`; `ls -p x` says `invalid value for --parallel: "x" (an integer expected)`; the usage line of a command with a mandatory named option shows it (#176). |
| 22 | Reduce the window width, then `ls` in a folder with long names | Columns shrink and overly long texts end with `…`. |
| 22b | (Linux/macOS) `mkdir real`, `ln -s real alias`, `ln -s nowhere broken`, then `ls`, `(ls)*.link`, `ls broken`, `ls -a \| where { $_.hidden }` | `alias` is listed as a folder, `broken` is listed too; `(ls)*.link` gives `False` for `real` and `True` for `alias` and `broken`; `ls broken` lists the one entry (it said nothing before); the last one lists the dot files. |
| 22c | `env --append PATH /tmp/x`, again, then `env PATH --append /tmp/x` | The first adds `/tmp/x` to `PATH` once (the second does nothing: it is already there); the last is `env: --append expects the name of a variable, got /tmp/x: write env --append NAME value`, and no variable `/tmp/x` exists. |
| 22d | `env --set GREETING hello world`, then `env GREETING PATH NOPE`, then `env --set GREETING` | `GREETING` is `hello world`; the second shows `GREETING` and `PATH`, then `env: no such variable: NOPE`; the last is `env: value expected: env --set NAME value` and changes nothing. |

## Known limitations of step 3

- No `|` pipeline or `where` yet (step 4).
- No Java method calls yet (`$f[0].path.toFile()`): only properties (step 5).
- No Tab completion yet (step 6).
