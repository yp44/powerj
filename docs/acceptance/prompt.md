# Acceptance test — Mini-iteration 30: a prompt computed by `$prompt`

**Goal:** check that a block assigned to `$prompt` gives the prompt of the interactive shell (FR-79): evaluated before each line and
when the mode changes, with `$POWERJ_MODE_CURRENT` and `$ansi`, without touching `$?`, `$exit`, `$last` or `$errors`; and that a
block that fails, returns `null`, is not a block or is too slow never blocks the shell: the default prompt is shown and a message
below the line says why.

Messages are shown in the system language; set `language=en` in config.properties (or `POWERJ_LANG=en`) to get the English texts
quoted here. The steps use a Git repository: run them from any clone (this one will do), on a branch that has an upstream.

## Getting the deliverables

As in the previous steps: **Actions** tab, latest run of the **CI** workflow, artifact of your platform (installer or portable).

## Scenario

| # | Action | Expected result |
|---|---|---|
| 1 | Start `powerj` in the clone, `$prompt = { "λ " + $pwd.fileName + " $ " }` | The next prompt is `λ <folder> $ `. |
| 2 | `cd ..`, then `cd -` | The prompt follows: `λ <parent> $ `, then `λ <folder> $ `. |
| 3 | `$prompt = { "PJ " + $pwd.fileName + " (" + (git branch --show-current) + ")> " }` | `PJ <folder> (<branch>)> `. `git checkout -b test-prompt` changes it at the next line. |
| 4 | `sh -c "exit 3"` (Windows: `cmd /c exit 3`), then `$prompt = { "[" + $exit + "] " + ($? ? "" : "✗ ") + "> " }` | `[3] > `: the block sees the last line. Type `$exit`: still `3` (the block's own `git`, if any, does not count). |
| 5 | `"abc".charAt(9)` | The prompt becomes `[3] ✗ > `. `1 + 1` brings back `[3] > `. |
| 6 | `$prompt = { $POWERJ_MODE_CURRENT + "> " }` | `normal> `. **Alt+V**: `preview> `. **Alt+V** again, then **Alt+P**: `perf> `. **Alt+P**: `normal> `. |
| 7 | `env POWERJ_MODE_CURRENT`, then `$POWERJ_MODE_CURRENT` | `no such variable: POWERJ_MODE_CURRENT` (a PowerJ variable: native programs do not see it), then `normal`. |
| 8 | `$prompt = { $ansi.green("main") + " " + $ansi.bold($ansi.red("!")) + "> " }` | `main` in green, `!` in bold red; the cursor stands right after `> ` (the codes take no room). Restarted with `NO_COLOR=1 powerj` (Windows: `set NO_COLOR=1` first), the same block shows `main !> ` without colors. |
| 9 | `$prompt = { "100%N-%M %{x%}> " }` | `100%N-%M %{x%}> `, exactly: nothing is read as a pattern of the line editor. |
| 10 | `$prompt = { "abc".charAt(10) }` | The default prompt `PJ <dir>> `, and below the line being typed, in red: `$prompt failed: …StringIndexOutOfBoundsException… (the default prompt is shown)`. It goes away when Enter is pressed and comes back at each line. |
| 11 | `$debug = true`, then Enter | The message below the line has the Java stack trace. `$debug = false`. |
| 12 | `$prompt = { null }` | Default prompt; `$prompt returned null (the default prompt is shown)`. |
| 13 | `$prompt = "PJ> "` | Default prompt; `$prompt must be a block such as { "PJ " + $pwd + "> " }, not a String (the default prompt is shown)`. |
| 14 | `env --set POWERJ_PROMPT_TIMEOUT_MS 200`, `$prompt = { Thread.sleep(2000) }` | After about 0.2 s, the default prompt and `$prompt took longer than 200 ms, the limit set by POWERJ_PROMPT_TIMEOUT_MS (the default prompt is shown)`. |
| 15 | `env --set POWERJ_PROMPT_TIMEOUT_MS 10000`, `$prompt = { Thread.sleep(20000) }`, then Enter on an empty line and **Ctrl+C** at once | The default prompt comes back at once, with `$prompt interrupted (Ctrl+C) (the default prompt is shown)`. |
| 16 | `$prompt = null` | `PJ <dir>> ` again, without any message. |
| 17 | Put `$prompt = { "λ " + $pwd.fileName + " $ " }` in `profile.pj`, restart `powerj` | The first prompt is already `λ <folder> $ `. `powerj -c "1 + 1"` prints `2` and nothing else: no prompt outside the interactive shell. |
| 18 | The Git example of the README (section *Configuration*, **Prompt**) in `profile.pj`, in a clone that is ahead of or behind its upstream | `PJ <dir> <branch> ↑<ahead> ↓<behind>> `; in a folder that is not a repository, `PJ <dir> > ` and no error of `git`. |

## Notes

- The block runs before each line: keep it fast. A `git` command takes a few milliseconds in a small repository, more in a large
  one; raise `POWERJ_PROMPT_TIMEOUT_MS` (10,000 at most) rather than living with the default prompt.
- Redirections are not allowed inside a block: to silence a program's errors, run it with `ProcessBuilder` as the README does.
