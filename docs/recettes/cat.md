# Acceptance test — Mini-iteration 13: `cat` cmdlet

**Goal:** verify that `cat` reads one or more files, with the output chosen by `--mode`: a `String` per line, one `String` for the whole content, `CatLine` records (line number going on from one file to the next, length, text) or one `CatContent` record (number of characters, content); and that, after a `|`, it reads the files received.

> Messages are shown in the system language; set `language=en` in config.properties (or `POWERJ_LANG=en`) to get the English texts quoted here.

## Getting the deliverables

As in the previous steps: **Actions** tab, latest run of the **CI** workflow, artifact `powerj-windows-x64-installer` or `powerj-windows-x64-portable`.

## Preparation

In a test folder (e.g. `C:\tmp\c`), create with your editor:

- `a.txt`: three lines `first`, `second`, `third` (with a line break after `third`);
- `b.txt`: two lines `un`, `deux` (no line break after `deux`);
- `src\B.java`: `class B {`, `  // TODO later`, `}`;
- `latin.txt` saved in **ISO-8859-1 / Windows-1252** (not UTF-8), containing `café`.

Then `cd C:\tmp\c`.

## Scenario

| # | Action | Expected result |
|---|---|---|
| 1 | `cat a.txt` | Three lines `first`, `second`, `third` (strings). |
| 2 | `cat a.txt b.txt` | The five lines, `a.txt` then `b.txt`. |
| 3 | `cat a.txt b.txt -m numbered` | Table `line  length  text`: lines 1 to 3 for `a.txt`, **4 and 5** for `b.txt` (`un` length 2, `deux` length 4). |
| 4 | `$l = cat a.txt b.txt -m numbered` then `$l[3].path` | The absolute path `C:\tmp\c\b.txt`. |
| 5 | `cat a.txt b.txt -m text` | One string: `first`…`third`, then `un`, `deux`. `(cat a.txt b.txt -m text).length()` gives the number of characters of both files. |
| 6 | `cat a.txt b.txt -m content` | One object `length  content`; `length` is the number of characters of both files together. |
| 6b | `cat a.txt -m content \| map { $_.content }` then the same followed by `> copy.txt` | `first`, `second`, `third` on three lines; `copy.txt` holds the three lines (line breaks kept). |
| 7 | `cat *.txt -m numbered` | `a.txt`, `b.txt` (then the error of row 10 for `latin.txt`), in alphabetical order, numbering continuous. |
| 8 | `cat a.txt -m NUMBERED` | Same as `-m numbered` (case-insensitive). `cat a.txt -m bad` gives an error listing `lines, text, numbered, content`. |
| 9 | `cat absent.txt b.txt` | Error `cat: not found: C:\tmp\c\absent.txt`, then `un`, `deux`. `cat src b.txt`: error `cat: is a directory: C:\tmp\c\src`, then `un`, `deux`. |
| 10 | `cat latin.txt` | Error `cat: cannot read C:\tmp\c\latin.txt: not readable as UTF-8 text (choose the charset with --encoding)`. |
| 11 | `cat latin.txt --encoding windows-1252` | `café`. `cat a.txt --encoding nope`: `cat: unknown charset: nope`. |
| 12 | `cat` | Error `cat: file expected: cat <file>... or ... \| cat`. |
| 13 | `ls --filter *.txt \| cat -m numbered` | The lines of the `.txt` files, numbered continuously. `ls \| cat`: the folder `src` is skipped without error. |
| 14 | `"a.txt" \| cat` | The lines of `a.txt` (a string received is a path). |
| 15 | `cat b.txt -m numbered` then `"a.txt" \| cat b.txt -m numbered` | The second time: `un`, `deux` (1, 2) then the lines of `a.txt` numbered 3 to 5. |
| 16 | `grep TODO -r -s \| cat --property path -m numbered` | The lines of `src\B.java` (the property `path` of each `GrepMatch` gives the file). |
| 17 | `grep TODO -r -s \| cat` | Error `cat: not a file: … (name the property that gives the file with --property)` for each object. |
| 18 | `grep TODO -r -s \| cat --property line` | Error `cat: property line is not a file (String, Path or File expected): …`. |
| 19 | `ls --filter *.txt \| cat -m content` | One `CatContent` with the content of the files received. |
| 20 | `help cat` | Help with `paths`, `-m, --mode`, `--encoding`, `--property`, and the examples. |
| 21 | `POWERJ_LANG=fr`, then `cat absent.txt` | `cat : introuvable : …`. |

## Known limitations

- `cat` declares `Object` as output (it emits `String`, `CatLine` or `CatContent` depending on `--mode`): Tab after `cat … -m numbered | map { l -> l.` does not suggest the properties of `CatLine`.
- With `--mode text` or `content`, the whole content is held in memory and emitted at the end: not suited to very large files.
- With `--mode lines` or `numbered`, the lines are emitted as they are read: if a file turns out not to be decodable in the middle, its first lines have already been emitted before the error.
- A string received from the pipeline is a path as is: wildcards (`*.txt`) are not expanded there.
- In non-interactive mode (`powerj -c "cat"`) with a redirected standard input, that input feeds `cat` (FR-04d): each line read is taken as a file name (`ls *.txt | powerj -c cat` would read them); `cat` without a file then gives no `file expected` error.
