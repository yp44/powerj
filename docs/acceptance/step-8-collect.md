# Acceptance test — Mini-iteration 8: `collect` cmdlet

**Goal:** verify that `collect` gathers the objects of a pipeline into **a single list**, always a list (even with a single element or empty), and that the next stage receives this entire list.

> Messages are shown in the system language; set `language=en` in config.properties (or `POWERJ_LANG=en`) to get the English texts quoted here.

## Getting the deliverables

As in the previous steps: **Actions** tab, latest run of the **CI** workflow, artifact `powerj-windows-x64-installer` or `powerj-windows-x64-portable`.

## When to use `collect`

After a `|`, each command receives the objects **one by one** (`where`, `map`). To work on **the entire list** (counting, sorting, `stream()`…):

| Need | Syntax |
|---|---|
| Number of results, even 0 or 1 | `(ls -r \| where { f -> !f.dir } \| collect).size()` |
| Keep the list in a variable | `$l = ls -r \| collect` then `$l.stream()…` |
| Continue the pipeline with the list | `ls -r \| collect \| map { l -> … }` |

Without `collect`, `( … )` and `$l = …` give the object **alone** when there is only one result: `(ls C:\ | where name == Windows).size()` returns the folder's `size` property (`0`), not the number of results.

## Scenario

| # | Action | Expected result |
|---|---|---|
| 1 | `(ls C:\ \| where name == Windows \| collect).size()` | `1` (compare with the same command without `\| collect`: `0`, the folder's size). |
| 2 | `(ls C:\ \| where name == absent \| collect).size()` | `0`. |
| 3 | `$l = ls C:\Windows \| collect` then `$l.size()` | The number of entries in `C:\Windows`. |
| 4 | `$l.getClass().getSimpleName()` | `Collected` (an unmodifiable `List`). |
| 5 | `ls C:\Windows \| collect \| map { l -> l.size() }` | **A single** number (the list arrives whole in `map`). |
| 6 | `ls -r --files \| collect \| map { l -> l.stream().sorted((a, b) -> Long.compare(b.size, a.size)).limit(5).toList() }` | Table of the 5 largest files (current directory and subfolders). |
| 6b | `$l = ls C:\Windows --dirs \| collect` then `$l \| map { f -> f.name }` | One name per line: at the head of a pipeline, the variable is iterated element by element, like any list. Other ways: `$l*.name`, `$l.stream() \| map { f -> f.name }`, `$l.forEach(f -> System.out.println(f.name))`. |
| 7 | `ls C:\Windows --dirs \| collect` | Displayed like `ls C:\Windows --dirs` (a list is displayed through its elements). |
| 8 | `ls \| collect \| where { l -> l.size() > 3 } \| map { l -> "plus de 3 : " + l.size() }` | One line if the current directory has more than 3 entries, nothing otherwise. |
| 9 | `ls \| collect \| map { l -> l.` then Tab Tab | Methods of `List`: `size()`, `stream()`, `get(`… |
| 10 | `help collect` | Help: summary `Gathers the pipeline objects into a single list`, `Output: Collected`, `Examples:`. |
| 11 | `ls \| collect -x` | Error `collect: unknown option -x`. |

## Known limitations

- `collect` waits for the upstream pipeline to finish before emitting: on an infinite stream, it never returns control (Ctrl+C stops it).
- No `sort` or `first` yet for common cases (`ls -r | sort { f -> f.size } | first 5`): go through `collect | map { l -> l.stream()… }`.
