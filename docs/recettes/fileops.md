# Acceptance test — Mini-iteration 20: `mkdir`, `touch`, `cp`, `mv`, `rm`

**Goal:** verify the five file operations:
- paths from arguments (wildcards) or from the pipeline;
- `-r` for folders, `-f` to overwrite, `-v` to see what was done, `-n` to change nothing;
- links never followed;
- protected paths refused unless `--allow-protected` is given. The list of protected paths is in `~/.powerj/protected-paths`.

> Messages are shown in the system language; set `language=en` in config.properties (or `POWERJ_LANG=en`) to get the English texts quoted here.

> Work only in a scratch folder (e.g. `C:\tmp\ops`): these cmdlets change the disk.

## Getting the deliverables

As in the previous steps: **Actions** tab, latest run of the **CI** workflow, artifact `powerj-windows-x64-installer` or `powerj-windows-x64-portable`.

## Preparation

Create the empty folder `C:\tmp\ops`, then `cd C:\tmp\ops`.

## Scenario

| # | Action | Expected result |
|---|---|---|
| 1 | `cat ~/.powerj/protected-paths` | The commented list: the disk roots (`C:\`…), `~`, the configuration folder, `C:\Windows`, `C:\Program Files`, `C:\Users`… (on Linux `/`, `/etc`, `/usr`…). |
| 2 | `mkdir a/b` | Error `mkdir: parent folder missing: …\a\b`. |
| 3 | `mkdir -p a/b/c` | The entry `c` (a folder). `mkdir -p a/b/c` again: no error. `mkdir a`: `mkdir: already exists: …\a (with -p, an existing folder is accepted)`. |
| 4 | `touch a/x.txt a/y.log` | The two empty files. `touch a/x.txt` again: its `modified` date changes. |
| 5 | `cp a/x.txt a/z.txt -v`, then `cp a/x.txt a/z.txt` | The entry `z.txt`; then `cp: already exists: …\a\z.txt (use -f to overwrite)`. With `-f`: no error. |
| 6 | `cp a b` | `cp: is a folder: …\a (use -r to copy it with its content)`. |
| 7 | `cp -r a b`, then `ls -r b` | `b` holds `b\c`, `x.txt`, `y.log`, `z.txt`. |
| 8 | `cp -r a a/b/inside` | `cp: cannot copy or move a folder into itself: …`. |
| 9 | `mkdir backup`, then `ls a --files \| cp backup/` | The files of `a` are in `backup`. `cp a/x.txt nowhere/`: `cp: destination is not a folder: …\nowhere`. |
| 10 | `mv b c -v` | The entry `c`; `b` no longer exists. `mv a/x.txt a/renamed.txt`: renamed. |
| 11 | `rm c` | `rm: folder not empty: …\c (use -r to remove it with its content)`. |
| 12 | `rm -r -n c` | Table `action path target`: one `remove` line per entry, content first; nothing is removed. |
| 13 | `ls -r --filter *.txt \| rm -v` | The removed `.txt` files. |
| 14 | `rm -r c`, then `rm absent.txt`, then `rm -f absent.txt` | `c` removed; then `rm: not found: …`; then no error. |
| 15 | `rm ~` | `rm: protected path: C:\Users\<you> (remove it anyway with --allow-protected)`. |
| 16 | `rm -r C:\Users` | `rm: contains the protected path …: C:\Users (…)`. Nothing is removed. |
| 17 | `rm -r ..` | Refused: it is the current folder's parent. |
| 18 | Add `C:\tmp\ops\backup` to `~/.powerj/protected-paths`, then `rm -r backup`, then `rm -r backup --allow-protected` | Refused; then removed. |
| 19 | `mv ~/Documents C:\tmp` | Refused (protected source). |
| 20 | (Linux/macOS) `ln -s /etc link`, then `rm -r link` | Only `link` is removed; `/etc` is intact. |
| 20b | `mkdir src2`, `touch src2/a.txt src2/b.txt`, then `ls src2 --files \| touch { $_.name }` | `a.txt` and `b.txt` are created in the current folder; the dates of `src2\a.txt` and `src2\b.txt` do not change. |
| 20c | `cp src2/*.txt { $_.name + ".bak" } -v`, then `ls --files \| where { $_.name.endsWith(".bak") } \| mv { $_.name.replace(".bak", ".old") } -v` | `a.txt.bak`, `b.txt.bak`; then renamed `a.txt.old`, `b.txt.old`. |
| 20d | `ls \| rm { $_.ext == "old" ? $_.name : null } -v`, then `touch { $_.name }` | Only the `.old` files are removed; then `touch: the block $_.name gives the path of each pipeline object: use it after a \|, …`. |
| 21 | Alt+V, then type `rm -r c` | The preview stops (`⏸ preview off: …`): nothing runs before Enter. |
| 22 | `^rm --version` (Linux/macOS) | The native `rm` runs. |
| 23 | `help rm`, `help cp` | Their help, with `--allow-protected`, `-n, --dry-run`, `-v, --verbose`. |
| 24 | `POWERJ_LANG=fr`, then `rm absent.txt` | `rm : introuvable : …`. |

## Known limitations

- `rm -f` silences missing paths, not a wildcard that matches nothing (`rm -f *.tmp` with no `.tmp` reports `no file matches`).
- Removing a file **inside** a protected folder is allowed: only the folder itself, and the folders that contain it, are protected.
- `ls -r | rm -r` may report `not found` for entries already removed with their folder; filter files (`ls -r --files`) or remove the top folder.
- There is no trash: `rm` removes for good. Use `-n` first.
