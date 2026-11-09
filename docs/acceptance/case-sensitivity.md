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
| 2 | `ls --rec`, then `ls --REC` | The abbreviation works in the case written; the second is refused with the same kind of hint. |
| 3 | `ls \| sort size`, then `ls \| sort Size` | The first works; the second: `FileEntry has no property 'Size' (did you mean 'size'?)`. |
| 4 | `ls \| select name`, then `ls \| select Name` | The same rule for the columns of `select` (FR-69). |
| 5 | `(ls)*.name`, then `(ls)*.Name` | The same rule for `$_.name`, `*.name` (FR-28). |
| 6 | `perf --view stages` (or Tab after `perf --view `), then `perf --view STAGES` | The first is accepted and the completion offers the lower-case names; the second is refused with the hint `did you mean stages?`. |
| 7 | `ls \| to-csv --delimiter tab`, then `ls \| to-csv --delimiter TAB` | The first writes the tab-separated text; the second is refused. |
| 8 | `ls \| where size > 1kb`, then `ls \| where size > 1KB`, then `ls \| where size > 1Kb` | The first works; the others: `invalid number: 1KB (units: b kb mb gb tb, ms s m h d)`. |
| 9 | `ls \| where dir == true`, then `ls \| where dir == TRUE` | The first lists the folders; the second does not understand `TRUE` (`TRUE` is not a literal). |
| 10 | `sleep 5s` (or `1+1` then `5s`), then `5S` | `5s` is a duration; `5S` is an error that lists the units. |
| 11 | `$name = 1` then `$name`, then `$Name` | `1`; then an error: the variable `Name` does not exist (the one that exists is `name`). |
| 12 | `Jobs`, then `jobs` | `Jobs` is not a command (it is not found, FR-13); `jobs` works. |
| 13 | `Math.max(1, 2)`, then `math.max(1, 2)` | `2`; then an error: `math` is not a class or a variable. |
| 14 | `import java.math.RoundingMode` then `RoundingMode.HALF_UP`, then `RoundingMode.half_up` | The constant; then an error: no constant `half_up` (the constants of Java are written as Java writes them). |
| 15 | `env --set POWERJ_LANG=FR` then `help ls`, then `env --set POWERJ_LANG=fr` then `help ls` | The first leaves the English text (`FR` is not a language, FR-61); the second shows the French text. Put the language back afterwards. |
| 16 | `env --set POWERJ_GLOB_IGNORE_CASE=TRUE` then `^echo *.txt` | An error that names the variable and the value `TRUE`. `env --set POWERJ_GLOB_IGNORE_CASE=false` restores the default. |
| 17 | `env --set POWERJ_NATIVE_ENCODING=AUTO` then `^echo x` | An error: `AUTO` is read as the name of a charset and is unknown. `env --set POWERJ_NATIVE_ENCODING=auto` restores the default. |
| 18 | `ps java*`, then `ps JAVA*` | **Linux and macOS**: the Java processes for `java*`, none for `JAVA*`. **Windows**: both find them (the system ignores case). |
| 19 | `env --set Foo=1`, then `env \| where { $_.name == "FOO" }` | **Linux and macOS**: nothing (`Foo` and `FOO` are two variables). **Windows**: the variable, as the system sees it. |

### What keeps its own rule

| # | Action | Expected result |
|---|---|---|
| 20 | `ls \| sort name -i` and `grep -i todo a.txt` | The explicit request to ignore case works (`sort -i`, `grep -i`, `range -i`). |
| 21 | `ls \| grid`, then type `NOTES` in the box of the column `name` | The grid finds `Notes.TXT`: the box of the filter is a search typed in a window (FR-77). |
| 22 | Start with a module that uses native code (`Enable-Native-Access: ALL-UNNAMED` in its manifest) | The consent question is asked once; `Y` answers as `y` does, and the fingerprint written in `config.properties` is accepted in any case of its digits. |
| 23 | `ls` in a folder with `a.txt` and `B.txt` | The names are sorted as a reader sorts them (`a.txt`, `B.txt`), whatever the case: an order, not a comparison. |
