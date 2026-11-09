# Acceptance test — The shell is case-sensitive ([#130](https://github.com/yp44/powerj/issues/130))

**Goal:** verify that what a user writes is compared exactly, as in Java (FR-12, [ADR 0012](../adr/0012-the-shell-is-case-sensitive.md)): the name of an option and of its value, a property, a unit, a literal, a variable, a class, a constant, a setting. A name written in the wrong case is refused, and the error gives the name to use. Then verify the four things that keep their own rule: the system's, the user's request, the encodings, the answer to a question.

> Messages are shown in the system language; set `language=en` in config.properties (or `POWERJ_LANG=en`) to get the English texts quoted here.

## Getting the deliverables

As in the previous steps: **Actions** tab, latest run of the **CI** workflow, artifact `powerj-<system>-installer` or `powerj-<system>-portable`.

## Preparation

A folder with `a.txt`, `Notes.TXT` and a sub-folder. Where a row says "Windows" or "Linux and macOS", the rule of the system is the one that is checked.

## Scenario

| # | Action | Expected result |
|---|---|---|
| 1 | `ls --recurse`, then `ls --Recurse` | The first works; the second: `ls: unknown option --Recurse, did you mean --recurse?` |
| 2 | `ls --rec`, then `ls --REC` | The abbreviation works in the case written; the second is refused: `ls: unknown option --REC`. |
| 3 | `ls \| sort size`, then `ls \| sort Size` | The first works; the second, for each object: `sort: FileEntry has no property 'Size' (did you mean 'size'? The case of a name matters) (object skipped: …)`. |
| 4 | `ls \| select name`, then `ls \| select Name` | The same rule for the columns of `select` (FR-69): `select: column Name: FileEntry has no property 'Name' (did you mean 'name'? The case of a name matters)`. |
| 5 | `(ls)*.name`, then `(ls)*.Name` | The same rule for `$_.name` and `*.name` (FR-28): `FileEntry has no property 'Name' (did you mean 'name'? The case of a name matters)`. |
| 6 | `perf --view stages` (or Tab after `perf --view `), then `perf --view STAGES` | The first is accepted and the completion offers the lower-case names; the second is refused: `perf: --view accepts [stages, blocks, natives, …], not 'STAGES'`. |
| 7 | `ls \| to-csv --delimiter tab`, then `ls \| to-csv --delimiter TAB` | The first writes the tab-separated text; the second: `to-csv: --delimiter expects one character or tab, got TAB`. |
| 8 | `ls \| where size > 1kb`, then `ls \| where { $_.size > 1KB }`, then `ls \| where size > 1KB` | The first works; the second: `syntax: invalid number: 1KB (units: b kb mb gb tb, ms s m h d)`; in the short form `1KB` is a text, so the third: `where: ">" is not possible between Long 0 and String "1KB"` for each object. |
| 9 | `ls \| where dir == true`, then `ls \| where dir == TRUE` | The first lists the folders; the second lists nothing (`TRUE` is a text, not the literal, and no boolean equals it). |
| 10 | `5s`, then `5S` | `5.000 s`; then `syntax: invalid number: 5S (units: b kb mb gb tb, ms s m h d)`. |
| 11 | `$name = 1` then `$name`, then `$Name` | `1`; then `unknown variable: $Name`. |
| 12 | `Jobs`, then `jobs` | **Linux**: `unknown command: Jobs`; `jobs` works. **macOS and Windows**: `Jobs` is not the built-in either, but the word is looked up as a program and the file system ignores case, so it runs the program `jobs` if the system has one (macOS has `/usr/bin/jobs`); the same for `TRUE` and `/usr/bin/true`. |
| 13 | `Math.max(1, 2)`, then `math.max(1, 2)` | `2`; then `"math" unknown: neither a Java class nor a command here (a variable is written $math)`. |
| 14 | `import java.math.RoundingMode` then `RoundingMode.HALF_UP`, then `RoundingMode.half_up` | `HALF_UP`; then `RoundingMode has no static field half_up` (the constants of Java are written as Java writes them). |
| 15 | Start PowerJ with `POWERJ_LANG=FR`, then with `POWERJ_LANG=fr`, and type `ls --Recurse` | The first shows the English message (`FR` is not a language, FR-61); the second the French one (`ls : option inconnue --Recurse, vouliez-vous dire --recurse ?`). |
| 16 | `env --set POWERJ_GLOB_IGNORE_CASE=TRUE` then `^echo *.txt` | `the variable POWERJ_GLOB_IGNORE_CASE must be true, false, 1 or 0, not 'TRUE'`. `env --set POWERJ_GLOB_IGNORE_CASE=false` restores the default. |
| 17 | `env --set POWERJ_NATIVE_ENCODING=AUTO` then `^echo x` | `unknown encoding 'AUTO' (variable POWERJ_NATIVE_ENCODING)`: `AUTO` is read as the name of a charset. `env --set POWERJ_NATIVE_ENCODING=auto` restores the default. |
| 18 | `ps powerj*`, then `ps POWERJ*`; then `ps -u <your user>`, then `ps -u <YOUR USER>` | **Linux and macOS**: PowerJ itself for `powerj*`, none for `POWERJ*`; your processes for the first user name, none for the second. **Windows**: both find them (the system ignores case; the process is `powerj.exe`). |
| 19 | `env --set Foo=1`, then `env \| where { $_.name == "FOO" }` | **Linux and macOS**: nothing (`Foo` and `FOO` are two variables). **Windows**: the variable, as the system sees it. |

### What keeps its own rule

| # | Action | Expected result |
|---|---|---|
| 20 | `ls \| sort name -i` and `grep -i todo a.txt` | The explicit request to ignore case works (`sort -i`, `grep -i`, `range -i`). |
| 21 | `ls \| grid`, then type `NOTES` in the box of the column `name` | The grid finds `Notes.TXT`: the box of the filter is a search typed in a window (FR-77). |
| 22 | Start with a module that uses native code (`Enable-Native-Access: ALL-UNNAMED` in its manifest) | The consent question is asked once; `Y` answers as `y` does, and the fingerprint written in `config.properties` is accepted in any case of its digits. |
| 23 | `ls` in a folder with `a.txt` and `B.txt` | The names are sorted as a reader sorts them (`a.txt`, `B.txt`), whatever the case: an order, not a comparison. |
