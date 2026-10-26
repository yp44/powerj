# Acceptance test — Mini-iteration 12: `grep` cmdlet and `powerj-fs` library

**Goal:** verify that `grep` searches a Java regular expression in files and folders (recursively, in parallel with virtual threads if requested), produces `GrepMatch` objects (absolute path, line number, line with the matches in color, groups), and filters the pipeline objects. Also check that `ls -r`, now based on the `powerj-fs` library, behaves as before.

> Messages are shown in the system language; set `language=en` in config.properties (or `POWERJ_LANG=en`) to get the English texts quoted here.

## Getting the deliverables

As in the previous steps: **Actions** tab, latest run of the **CI** workflow, artifact `powerj-windows-x64-installer` or `powerj-windows-x64-portable`.

## Preparation

In a test folder (e.g. `C:\tmp\g`), create with your editor:

- `a.txt`: three lines `first line`, `TODO: write`, `last TODO todo`;
- `src\B.java`: `class B {`, `  // TODO later`, `}`;
- `src\deep\C.java`: `class C { } // todo`;
- `pom.xml`: a line `<version>1.2.3</version>`;
- `latin.txt` saved in **ISO-8859-1 / Windows-1252** (not UTF-8), containing `café TODO`.

Then `cd C:\tmp\g`.

## Regular expressions and backslashes

| Writing | Example |
|---|---|
| Unquoted: taken as is (Windows paths, FR-32b) | `grep class\s+\w+ -r` |
| In quotes: Java escapes, backslash doubled as in Java source | `grep "class (\\w+)" -r` |
| `"` inside quotes | `grep "version=\"(?<v>[^\"]+)\"" pom.xml` |

## Scenario

| # | Action | Expected result |
|---|---|---|
| 1 | `grep TODO a.txt` | Table `path  line  text`: two lines (2 and 3), **absolute** path `C:\tmp\g\a.txt`, `TODO` in **bold red** in `text`. |
| 2 | `grep TODO` | Same as 1: without a path, the current folder, one level deep (not `src`). |
| 3 | `grep TODO -r` | Adds `src\B.java` line 2 (`C.java` contains `todo` in lowercase). |
| 4 | `grep -i todo -r` | Adds `src\deep\C.java` line 1; on line 3 of `a.txt`, the two occurrences are in red. |
| 5 | `grep -i todo --max-depth 2` | `a.txt` and `src\B.java`, not `src\deep\C.java`. |
| 6 | `grep -v TODO a.txt` | Only line 1 `first line`. |
| 7 | `grep -i todo -r -p 16` | Same lines as 4 (file order may differ; the lines of a file stay together and in order). |
| 7b | `grep -i todo -r -s` then `grep -i todo -r -s -v` | One line per file (the first match): `a.txt` line 2, `src\B.java` line 2, `src\deep\C.java` line 1. With `-v`: only the files without `todo` (e.g. `pom.xml`), with line `0` and an empty text. |
| 8 | `grep "<version>(?<v>[^<]+)" pom.xml \| map { m -> m.named.v }` | `1.2.3`. |
| 9 | `grep "class (\\w+)" -r \| map { m -> m.groups[1] }` | `B` and `C` (`groups[0]` is the whole match `class B`). |
| 10 | `$m = grep TODO a.txt` then `$m[0].line`, `$m[0].path`, `$m[0].matches` | `2`, the absolute path, a `start end` table with `0 4`. |
| 11 | `grep -i todo src\*.JAVA` | `src\B.java` line 2 (wildcards, case-insensitive). |
| 12 | `grep TODO latin.txt` | Error `grep: cannot read C:\tmp\g\latin.txt: not readable as UTF-8 text (choose the charset with --encoding)`. |
| 13 | `grep café latin.txt --encoding windows-1252` | The line `café TODO`. |
| 13b | `grep TODO image.bin` (a binary file, e.g. any `.png` renamed) then `grep TODO image.bin --binary --encoding ISO-8859-1` | Nothing the first time (binary file skipped); the second time, the lines containing `TODO` are found. |
| 14 | `grep "(" a.txt` | Error `grep: invalid regular expression (: Unclosed group (position 1)`. |
| 15 | `grep x absent.txt` | Error `grep: not found: C:\tmp\g\absent.txt`. |
| 16 | `ls -r --filter *.java \| grep TODO` | `GrepMatch` of `src\B.java`: the files received from the pipeline are searched. |
| 17 | `git log --oneline \| grep -i fix` (in a Git repository) | Only the commits whose line contains `fix`, unchanged (strings). |
| 18 | `ls \| grep --property name "^a"` | The `FileEntry` table of `a.txt` (filter on a property). |
| 19 | `grep -i "error\|exception" C:\Windows\Logs -r -p 32 --on-error silent` then **Ctrl+C** | Results appear as soon as they are found; Ctrl+C stops the search immediately and gives the prompt back. |
| 20 | Same command without `-p 32`, then with it, prefixed by `(…\| collect).size()` | Same number of results; with `-p 32`, noticeably faster on a cold large folder. |
| 21 | `ls -r` and `ls -r --filter *.java` | Same display as before the mini-iteration (folders first, alphabetical order). |
| 21b | `(ls -r C:\Windows\System32 --on-error silent \| collect).size()` then the same with `-p 16` | Same number of entries; with `-p 16`, the order of the entries is free and the walk faster on a cold folder. `ls -p 0` gives `ls: --parallel expects a number of threads of at least 1, got 0`. |
| 22 | `help grep` | Help with the options `-r`, `-p, --parallel`, `--max-depth`, `-i`, `-v`, `-a`, `--encoding`, `--property`, and the examples. |
| 23 | `grep TODO a.txt > out.txt` then `type out.txt` | The table, **without** color codes. |

## Known limitations

- The display of `GrepMatch` is the usual PowerJ table; long absolute paths take room (the `text` column is truncated to the terminal width). A grep-style display (`path:line:text`) may come later.
- The highlighting applies to `GrepMatch` (files); filtered pipeline objects (`git log | grep fix`) are emitted unchanged, without color.
- `grep` declares `Object` as output (it can emit `GrepMatch` or the filtered objects): Tab after `grep … | map { m -> m.` does not suggest the properties of `GrepMatch`.
- With `-p`, the files received from the pipeline are searched one after the other (the parallelism applies to the walk of a folder).
- In non-interactive mode (`powerj -c "grep …"`) with a redirected standard input, that input feeds `grep` like `where` (FR-04d): without a path, `grep` filters its lines and does not search the current folder (as GNU grep without a file). Give the folder explicitly: `powerj -c "grep TODO . -r"`.
