# Acceptance test — Step 6: Tab completion and highlighting

**Goal:** verify that Tab completes commands, options, paths, variables, properties and the Java API, and that the input is highlighted according to the kind of each word.

> Messages are shown in the system language; set `language=en` in config.properties (or `POWERJ_LANG=en`) to get the English texts quoted here.

## Getting the deliverables

As in the previous steps: **Actions** tab, latest run of the **CI** workflow, artifact `powerj-windows-x64-installer` or `powerj-windows-x64-portable`.

## How Tab works

- **First Tab**: completes the prefix common to all suggestions; if there is only one, completes it fully.
- **Second Tab**: shows the menu of suggestions with their descriptions; Tab / Shift+Tab (or the arrow keys) to move through it, Enter to choose.
- The menu shows the kind: `ls [pj]` (cmdlet), `cd [internal]`, `notepad [native]`; the signature for Java: `of(Object...) : List`.

## Scenario

| # | Action | Expected result |
|---|---|---|
| 1 | `l` then Tab Tab | Menu: `ls [pj]` and the programs on the `PATH` starting with `l`. |
| 2 | `ls --` then Tab Tab; then `ls -r --` then Tab Tab | Options of `ls` with their descriptions (`--recurse, -r  Walks subdirectories`…); the second time, `--recurse` is no longer suggested. |
| 3 | `cd C:\Pro` then Tab | Completes to `"C:\\Program Files\\` (in quotes, because of the space); another Tab suggests the folder's contents. |
| 4 | `ls C:\Win` then Tab, then `Sys` Tab | `C:\Windows\`, then `C:\Windows\System32\`. |
| 5 | `ls \| where { $_.` then Tab Tab | `name size modified path dir ext` (properties of `FileEntry`, output of `ls`) and the methods. |
| 6 | `ls \| where { f -> f.na` then Tab, then `.sta` Tab | `f.name`, then `startsWith(`: the lambda knows the type of `f`. |
| 7 | `$f = ls` then `$f[0].` Tab Tab | Properties of the first file. Then `$f*.na` Tab → `$f*.name`. |
| 8 | `$` then Tab Tab | Defined variables (`$f`…) and automatic ones (`$exit`, `$last`, `$pwd`…), with their type. |
| 9 | `^no` then Tab | `notepad` (only native programs after `^`). |
| 10 | `java.util.Li` Tab Tab | `List`, `LinkedList`, `LinkedHashMap`… |
| 11 | `List.` Tab Tab | Static methods with their signature: `of(Object...) : List`, `copyOf(Collection) : List`… |
| 12 | `$l = List.of(1, 2)` then `$l.` Tab Tab, then `$l.stream().fi` Tab | `size() : int`, `get(int) : Object`, `stream() : Stream`…; then `filter(`. |
| 13 | `new java.io.F` Tab Tab | `File`, `FileReader`, `FileWriter`… |
| 14 | `import java.sec` Tab, then `Mess` Tab | `java.security.`, then `java.security.MessageDigest`. |
| 15 | `ls \| map String::len` Tab | `String::length`. |
| 16 | `Ma` Tab Tab | `Math`, `Map`, `MatchResult`… (classes imported by default). |
| 17 | Colors: slowly type `ls -r \| nope "x" $y ; git status` | `ls` green, `-r` gray, `nope` red (unknown command), `"x"` yellow, `$y` magenta, `git` cyan (native program). |
| 18 | Type `Math.max(1, 2)` | No red: it is a Java expression, not an unknown command. |
| 19 | Type `cd ..` then Enter | `cd` in green (built-in command). |

## Known limitations of step 6

- No completion inside a `"…"` string (except a quoted path as an argument), nor in a `$( … )` placed inside a string.
- No `@Completion` annotation yet for a cmdlet to provide its own suggestions (FR-23): enum and path options are completed automatically.
- The type of the parameters of a lambda passed to a Java method (`$l.stream().filter(s -> s.`) is not inferred: no suggestions after `s.`.
- The very first Tab can take a few tens of milliseconds (reading the Java class index); afterwards less than a millisecond.
