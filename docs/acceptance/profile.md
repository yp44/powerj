# Acceptance test — Mini-iteration 11: startup profile `profile.pj`

**Goal:** check that `profile.pj` runs when the interactive shell starts, in the session of the prompt, that its errors show their line without blocking the startup, and that `--no-profile` and the non-interactive mode skip it.

Messages are shown in the system language; set `language=en` in config.properties (or `POWERJ_LANG=en`) to get the English texts quoted here.

## Getting the deliverables

As in the previous steps: **Actions** tab, latest run of the **CI** workflow, artifact `powerj-windows-x64-installer` or `powerj-windows-x64-portable`.

## Preparation

Create `%USERPROFILE%\.powerj\profile.pj` (or `$POWERJ_HOME\profile.pj`) with Notepad (UTF-8, BOM included, is fine):

```
# My PowerJ profile
$x = 42
import java.time.*
bonjour
$langs = List.of(
    "java",
    "kotlin")
env --set GREETING=hello
```

## Scenario

| # | Action | Expected result |
|---|---|---|
| 1 | Start `powerj` | Banner, then `profile.pj:4: unknown command: bonjour` in red, then the prompt. |
| 2 | `$x` | `42`. |
| 3 | `Year.of(2024).isLeap()` | `true` (`import java.time.*` from the profile is active). |
| 4 | `$langs.size()` | `2` (a command spanning 3 lines). |
| 5 | `^cmd /c echo %GREETING%` | `hello` (`env --set` from the profile). |
| 6 | `$profile` then `notepad $profile` | Path of the file; Notepad opens it. |
| 7 | ↑ | Recalls your own last command, never a line of the profile. |
| 8 | `exit`, then `powerj --no-profile`, then `$x` | No profile error at startup; `unknown variable: $x`. `$profile` still gives the path. |
| 9 | `powerj -c "$x"` from `cmd.exe` | `unknown variable: $x`: scripts never run the profile. |
| 10 | Add a line `Stream.iterate(0, { $_ + 1 }).forEach({ $_ })` before `$x = 42`, start `powerj`, press Ctrl+C | `profile.pj:2: profile interrupted (Ctrl+C), the following lines are not run`, then the prompt; `$x` is unknown. Remove the line afterwards. |
| 11 | Rename `profile.pj`, start `powerj` | No message at all: a missing profile is not an error. |

## Known limitations

- `alias` does not exist yet: a profile can define variables, imports, environment variables and run commands.
- The profile cannot change the prompt yet (planned next).
