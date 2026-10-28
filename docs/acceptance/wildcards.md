# Acceptance test — Mini-iteration 28: wildcards in the arguments of a native program, and the case rule

**Goal:** check that an unquoted wildcard in the arguments of a native program is replaced by the names of the files it matches (Linux and macOS), that a quoted word is not, that a word without match is passed as written, and that wildcards keep case unless `POWERJ_GLOB_IGNORE_CASE` says otherwise, for the cmdlets as well as for the native programs.

> Messages are shown in the system language; set `language=en` in config.properties (or `POWERJ_LANG=en`) to get the English texts quoted here.

## Getting the deliverables

Build with `./mvnw verify` and run `powerj-dist/target/…/bin/powerj`, on Linux or macOS. On Windows the program expands its own wildcards, so only the last part of the table applies (rows 17 and 18).

## Setup

In an empty folder: `touch a.txt b.txt c.log Notes.TXT .hidden.txt`, `mkdir sub "my docs"`, `touch sub/d.txt "my docs/a note.txt"`.

## Scenario

| # | Action | Expected result |
|---|---|---|
| 1 | `^echo *.txt` | `a.txt b.txt`: the shell replaced the wildcard, sorted by name; `Notes.TXT` (another case) and `.hidden.txt` (hidden) are not in it. |
| 2 | `^echo "*.txt"` | `*.txt`: a quoted word is never expanded. |
| 3 | `$p = "*.txt"` then `^echo $p` | `*.txt`: the value of a variable is not expanded. |
| 4 | `^echo *.zip` | `*.zip`: no match, the word is passed as written. |
| 5 | `^echo ?.txt` and `^echo a?.txt` | `a.txt b.txt`; then `a?.txt`: `?` matches exactly one character, so the second word matches nothing and stays as written. |
| 6 | `^echo [a-b].txt` | `a.txt b.txt`. |
| 7 | `^echo .*.txt` | `.hidden.txt`: a hidden file needs a dot in the pattern. |
| 8 | `^echo sub/*.txt` and `^echo */` | `sub/d.txt`; `my docs/ sub/`: the prefix is kept, `/` keeps the folders. |
| 9 | `^echo */*.txt` | `my docs/a note.txt sub/d.txt`. |
| 10 | `^printf "[%s]" my*/*.txt` | `[my docs/a note.txt]`: a name with a space is one argument. |
| 11 | `cd sub` then `^echo *.txt` | `d.txt`: names are matched from the current folder of the session. |
| 12 | `touch -- -n` then `^echo -*` | `./-n`: a name that starts with `-` is not taken for an option. |
| 13 | `^echo *.TXT` and `^echo notes*` | `Notes.TXT`, then `notes*`: case counts by default. |
| 14 | `ls *.txt` and `ls --filter *.TXT` | `a.txt`, `b.txt`; then `Notes.TXT`: the cmdlets follow the same rule. |
| 15 | `env --set POWERJ_GLOB_IGNORE_CASE=true` then `^echo *.txt`, `ls *.txt`, `^echo notes*` | `a.txt b.txt Notes.TXT`, the three files, `Notes.TXT`: case is ignored, from the next command on. |
| 16 | `env --set POWERJ_GLOB_IGNORE_CASE=maybe` then `^echo plain` and `^echo *.txt` | `plain`; then an error that names the variable and the value `maybe`. `env --set POWERJ_GLOB_IGNORE_CASE=false` restores the default. |
| 17 | `ps JAVA*` (with the variable unset) | The Java processes: `ps` always ignores case (FR-74). |
| 18 | `ls "*.txt"` | `a.txt`, `b.txt`: a cmdlet expands its path arguments whatever the quoting (FR-40). |

## Known limitations

- A wildcard in a **directory name that contains a space** cannot be written: a word ends at a double quote, and there is no escape character (`my*/*.txt` works, `"my docs"/*.txt` is two words).
- `{a,b}` cannot be written either: a word ends at `{` and `}`.
- On **Windows** the matcher of the platform ignores case and the variable has no effect; the native programs expand their own wildcards.
- The expansion of a native program is **not contextual**: `^find . -name *.txt` needs the quotes, as in a Unix shell. A cmdlet is: `ps *` means every process, `ls --filter *.java` is a filter.
- A name found is limited by the size of the command line of the system (`ARG_MAX`): a very long list makes the program fail to start.
