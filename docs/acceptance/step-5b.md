# Acceptance test — Step 5b: alignment with Java

**Goal:** verify that blocks are written as in Java — named lambdas, lambdas without braces in Java calls, method references —, that `$_` remains available for short filters, that conditions are strictly boolean, and the new `map` cmdlet.

> Messages are shown in the system language; set `language=en` in config.properties (or `POWERJ_LANG=en`) to get the English texts quoted here.

> Step 5b ships with step 5, in the same exe: both acceptance tests are run on the same version.

## Getting the deliverables

As in the previous steps: **Actions** tab, latest run of the **CI** workflow, artifact `powerj-windows-x64-installer` or `powerj-windows-x64-portable`.

## `$_` or lambda: which one to use?

Both forms do the same thing; they read differently.

| I want… | I write | Why |
|---|---|---|
| a **one-line** filter or transformation, typed at the keyboard | `where { $_.dir }`, `map { $_.name }` | `$_` is the shortest; unambiguous when the object appears only once or twice. |
| the property of **each element** of a list | `$f*.name`, `$f*.size` | `.` applies to the list itself (`$f.size()` = number of elements); `*.` to each element. |
| a **long** condition that mentions the object several times | `where { f -> f.size > 1mb && !f.dir && f.ext == "log" }` | A name (`f`, `fichier`, `ligne`) reads better than a series of `$_`. |
| a block **inside** another block | `where { f -> List.of("md", "txt").stream().anyMatch(e -> f.name.endsWith("." + e)) }` | In the inner block, `$_` would refer to `e`: the outer object must have a name. |
| **two parameters** (sorting, reduction) | `$m.sort((a, b) -> a.length() - b.length())` | Only a lambda declares several parameters (`$a` / `$b` no longer exist). |
| just **call a method** | `map FileEntry::name`, `map(String::toUpperCase)` | The method reference says it all, with no parameter to name. |
| pass a function to a **Java method** | `$l.stream().filter(s -> s.length() > 4)` | Exact Java syntax; braces are optional between the parentheses of a call. |
| pass a function to a **cmdlet** | `where { f -> … }`, `map { f -> … }` | Braces are mandatory: without them, the `>` of `->` would be a redirection. |

In short: **`$_` for short filters typed at the keyboard; a named lambda as soon as the expression grows, nests, or takes two parameters; a method reference when it is enough.**

Reminders:
- Lambda parameters are written **without `$`** (as in Java); shell variables keep their `$`: `$min = 1kb; ls | where { f -> f.size > $min }`.
- A condition must return a **boolean** (`true` / `false`), like a Java `Predicate`: `where { f -> f.name }` is an error; write `where { f -> !f.name.isEmpty() }`.

## Scenario

| # | Action | Expected result |
|---|---|---|
| 1 | `ls -r \| where { $_.size > 1mb }` then `ls -r \| where { f -> f.size > 1mb }` | Same result: `$_` and a named lambda are equivalent. |
| 2 | `ls -r \| where { f -> f.size > 1mb && !f.dir && f.modified > now - 7d }` | Files larger than 1 MB modified within the last 7 days. |
| 3 | `ls -r \| map { f -> f.name + " : " + f.name.length() }` | One line per file (`notes.txt : 9`); also works when only one file is found. |
| 4 | `ls \| map FileEntry::name` then `env \| map EnvVar::name` | File names; environment variable names. |
| 5 | `ls -r \| where { f -> List.of("md", "txt").stream().anyMatch(e -> f.name.endsWith("." + e)) }` | `.md` and `.txt` files (nested lambda: `f` remains the outer object). |
| 6 | `$l = List.of("apple", "banana", "kiwi")` then `$l.stream().filter(s -> s.length() > 4).map(String::toUpperCase).toList()` | `APPLE` then `BANANA`. |
| 7 | `$m = new ArrayList($l); $m.sort((a, b) -> a.length() - b.length()); $m` | `kiwi`, `apple`, `banana`. |
| 8 | `$l.stream().map(Path::of).toList()` then `Stream.of("a", "b").map(StringBuilder::new).toList()` | Static method and constructor references. |
| 9 | `ls \| where { f -> f.name }` | For each object, non-blocking error `where: the block must return a boolean, got String … (object skipped: …)`; the shell continues. |
| 10 | `$m.sort({ $a.length() - $b.length() })` | Clear error saying to write `(a, b) -> …`. |
| 11 | `$min = 1kb; ls \| where { f -> f.size > $min }` | Shell variable used in a lambda. |
| 12 | `ls \| where f -> f.dir` | Syntax error explaining that braces are mandatory as a cmdlet argument. |
| 13 | `$f = ls -r` then `$f.size()`, `$f*.size`, `$f*.name*.toUpperCase()`, `$f*.name.size()` | Number of files; size of each file; uppercase names; number of names. `.` applies to the list, `*.` to each element. |
| 14 | `$f.size` | Error: `… has no property 'size' (for each element: *.size; method: size())`. |
| 15 | Type `$t = """` then two lines of text then `"""`, then `$t.lines().count()` | `2`: multi-line text block. |
