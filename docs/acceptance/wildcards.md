# Acceptance test — Mini-iteration 28: wildcards in the arguments of a native program, the case rule and compound words

**Goal:** check that an unquoted wildcard in the arguments of a native program is replaced by the names of the files it matches (Linux and macOS), that a quoted word is not, that a word without match is passed as written, and that wildcards keep case unless `POWERJ_GLOB_IGNORE_CASE` says otherwise, for the cmdlets as well as for the native programs.

> Messages are shown in the system language; set `language=en` in config.properties (or `POWERJ_LANG=en`) to get the English texts quoted here.

## Getting the deliverables

Build with `./mvnw verify` and run `powerj-dist/target/…/bin/powerj`, on Linux or macOS. On Windows the program expands its own wildcards, so only rows 17, 18, 26 and 27 apply.

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
| 19 | `^printf "[%s]" "my docs"/*` | `[my docs/a note.txt]`: a quoted text and plain text written without a blank are **one** word (FR-32c), and only the plain part is a wildcard. |
| 20 | `$d = "my docs"` then `^printf "[%s]" $d/*.txt` | `[my docs/a note.txt]`. |
| 21 | `^printf "[%s]" --message="hello world"` | `[--message=hello world]`: one argument. |
| 22 | `^echo x"y"z` | `xyz`. |
| 23 | `^echo "nowhere"/*` | `nowhere/*`: the word matches nothing and is passed as written. It never lists the content of `/`. |
| 24 | `$s = "*.t"` then `^echo $s"xt"` | `*.txt`: what a variable holds is literal, there is no wildcard in the plain pieces. |
| 25 | `^touch "a*b1" aXb2` then `^printf "[%s]" "a*b"*` | `[a*b1]`: the quoted star is the text `*`, the last one is a wildcard. |
| 26 | `ls "my docs"/*.txt` | `a note.txt`, and no error about `/*.txt`: the cmdlet receives the one argument `my docs/*.txt`. |
| 27 | `"x" > "my docs"/out.txt` then `ls "my docs"` | `a note.txt`, `out.txt`: the target of a redirection is a compound word too. |
| 28 | `^echo ~/"x"` and `^echo ~"x"` | `<HOME>/x`; then `~x`: the tilde needs a slash after it to be the home folder. |
| 29 | `^echo foo$ a$b`, then `$d = "my docs"` and `^echo "a"$d` | `foo$ a$b`: a `$` in the middle of plain text is a plain character. Then `amy docs`: a variable right after a quoted text is a piece of the word. |

## Known limitations

- There is no escape character in a word (FR-32b): a name with a space is written between quotes, and a wildcard after it is written glued to the quote (`"my docs"/*.txt`, FR-32c). `my\ docs/*` is two words.
- **Tab completion** does not complete after a text in quotes that is closed (`"my docs"/su<Tab>` proposes nothing); inside the quotes (`"my docs/su<Tab>`) it works, as before.
- A cmdlet receives the text and not how it was written, so it still expands a `*` that came from a quoted piece (`ls "a*b"/x`).
- `$d.txt` and `"x".txt` are the property `txt` of the value (FR-28), not the value followed by `.txt`: write `"$d.txt"`.
- `{a,b}` cannot be written either: a word ends at `{` and `}`.
- On **Windows** the matcher of the platform ignores case and the variable has no effect; the native programs expand their own wildcards.
- The expansion of a native program is **not contextual**: `^find . -name *.txt` needs the quotes, as in a Unix shell. A cmdlet is: `ps *` means every process, `ls --filter *.java` is a filter.
- A name found is limited by the size of the command line of the system (`ARG_MAX`): a very long list makes the program fail to start.
