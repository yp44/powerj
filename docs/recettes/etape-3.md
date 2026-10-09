# Acceptance test — Step 3: object model, `ls`, `env`, `help`

**Goal:** verify that cmdlets return Java objects whose properties can be read, table display, Unix-style option binding, `env` and help.

> Messages are shown in the system language; set `language=en` in config.properties (or `POWERJ_LANG=en`) to get the English texts quoted here.

## Getting the deliverables

As in the previous steps: **Actions** tab, latest run of the **CI** workflow, artifact `powerj-windows-x64-installer` or `powerj-windows-x64-portable`.

## Scenario

| # | Action | Expected result |
|---|---|---|
| 1 | `cd ~` then `ls` | Table `name size modified dir path` (absolute path in the last column): folders first, then files, in alphabetical order; human-readable sizes (`14.2 KB`; `14,2 KB` in French), local dates. |
| 2 | `ls -a` | Hidden files appear as well. |
| 3 | `ls -r --filter *.txt` (in a folder containing `.txt` files) | All `.txt` files in subfolders; the pattern ignores case (`*.TXT` too). |
| 4 | `ls -d`, then `ls --files`, then `ls C:\Windows *.ini` | Folders only; files only; contents of `C:\Windows` followed by the `.ini` files of the current directory. |
| 5 | `(ls)*.name` | Names only, one per line (`*.`: the property of each element). |
| 6 | `$f = ls --files` then `$f[0].size`, `$f[-1].name`, `$f[0].path.parent`, `$f[0].ext` | Size in bytes, name of the last one, parent folder, extension without the dot. |
| 7 | `$f*.name`, then `$f.size()` and `$f.name` | The names of all files; the number of files; an error saying to write `*.name` (without `*`, `.` applies to the list itself). |
| 8 | `$f[0].siz` | Error in red: `FileEntry has no property 'siz' (properties: name, size, …)`. |
| 9 | `ls --recurce` | `ls: unknown option --recurce, did you mean --recurse?` |
| 10 | `ls --rec`, `ls -ra`, `ls --filter=*.txt`, `ls -f*.txt` | Abbreviation, grouped options, `=` and attached value: all accepted. |
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
| 22 | Reduce the window width, then `ls` in a folder with long names | Columns shrink and overly long texts end with `…`. |

## Known limitations of step 3

- No `|` pipeline or `where` yet (step 4).
- No Java method calls yet (`$f[0].path.toFile()`): only properties (step 5).
- No Tab completion yet (step 6).
