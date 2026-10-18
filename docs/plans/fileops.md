# Plan — cmdlets `mkdir`, `touch`, `cp`, `mv`, `rm` (mini-iteration 20, FR-72)

## Context
Phase 8 of the cmdlet roadmap (`docs/plans/cmdlet-roadmap.md`). Phases 6 (`uniq`) and 7 (`find`) are cancelled by the user. The backlog (§12.3) lists `cp`, `mv`, `rm`, `mkdir`, `touch` as "File operations".

Today PowerJ has to call the native programs, which differ between Windows and Unix and do not read the objects of the pipeline. These are the first PowerJ cmdlets that **change** the disk. Safety is therefore part of the design.

Decisions taken with the user:
1. **`rm` protections:**
   - a non-empty folder needs `-r`;
   - every command accepts `-n`/`--dry-run`;
   - `rm` refuses to remove **protected paths**, whose list lives **in a file of the configuration folder** (`~/.powerj/protected-paths`);
   - an option, `--allow-protected`, removes them anyway.
2. **Output:**
   - `mkdir` and `touch` emit the `FileEntry` they create or update;
   - `cp`, `mv` and `rm` are silent, except with `-v`, which emits the `FileEntry` of each destination (for `rm`, of each removed entry).
3. **Existing target:** `cp` and `mv` refuse to overwrite without `-f`/`--force`. This gives a non-blocking error, and the file is skipped.

Branch: `claude/cmdlet-fileops`, from `main` @ e7bfeef.

## Syntax
```text
mkdir <path>... [-p|--parents] [-n|--dry-run]
touch <path>... [-n|--dry-run]
cp <source>... <destination> [-r|--recurse] [-f|--force] [-v|--verbose] [-n|--dry-run]
mv <source>... <destination> [-f|--force] [-v|--verbose] [-n|--dry-run] [--allow-protected]
rm <path>... [-r|--recurse] [-f|--force] [-v|--verbose] [-n|--dry-run] [--allow-protected]
```

| Option | Cmdlets | Description |
|---|---|---|
| `-p`, `--parents` | `mkdir` | Creates the missing parent folders. An existing folder is then not an error. |
| `-r`, `--recurse` | `cp`, `rm` | Copies or removes folders with their content. Without it, a folder is refused. |
| `-f`, `--force` | `cp`, `mv` | Overwrites an existing target. |
| `-f`, `--force` | `rm` | A missing path is not an error. |
| `-v`, `--verbose` | `cp`, `mv`, `rm` | Emits the `FileEntry` of each destination (`cp`, `mv`) or of each removed entry (`rm`). |
| `-n`, `--dry-run` | all | Changes nothing. Emits one `FileOperation` per action that would be done. |
| `--allow-protected` | `mv`, `rm` | Allows removing or moving a protected path (see below). |

**Paths:**
- **Arguments:** relative to the current folder; `~` is expanded by the shell. Wildcards on the last element (`rm *.tmp`) go through `FileArguments.expand`, as for `grep` and `cat`.
- **Pipeline:** each received object designates a path, read as `cat` reads it:
  - a `String`, `Path`, `File` or `FileEntry`;
  - another object needs `--property <name>`, available on all five cmdlets.

  For example `ls --filter *.tmp | rm` or `grep TODO -r -s | cp --property path review/`.
- **`cp` and `mv`:** the **last argument is the destination**. Objects received from the pipeline are sources: `ls *.txt | cp backup/`.
  - When there are several sources, or the destination is an existing folder, each source goes **into** the destination, keeping its name.
  - Otherwise, the destination is the new name (`cp a.txt b.txt`, `mv old new`).

## Output
- `mkdir`, `touch`: the `FileEntry` of the folder or file. `FileEntry` creation is shared with `Ls` through a package-private `FileEntries.of(Path)`, moved out of `Ls.entry`.
- `cp`, `mv`, `rm`: nothing by default; with `-v`, `FileEntry`s.
  - For `rm -v`, the entries are read **before** removal.
  - `rm -r -v` emits the content first, then the folder.
- **Dry run:** a new record, `@Display(columns = {"action", "path", "target"}) record FileOperation(String action, Path path, Path target)`, with `action` among `mkdir`, `touch`, `copy`, `move`, `remove`, and `target` `null` except for `copy`/`move`. Nothing is changed.
  - `rm -r -n docs` lists every entry that would be removed.

## Behaviour
- **Errors** are non-blocking, one per path; the other paths go on:
  - not found;
  - exists (`cp`/`mv` without `-f`, `mkdir` without `-p`);
  - is a folder (`rm` or `cp` without `-r`, on a non-empty folder for `rm`);
  - parent missing (`mkdir` without `-p`, or a `cp`/`mv` destination);
  - access denied;
  - protected path;
  - a source copied or moved into itself (`cp -r docs docs/sub`).
- **Usage errors** are blocking: no path at all, `cp`/`mv` with no destination, or an unknown option.
- **Symbolic links are never followed.**
  - `rm` removes the link, not its target.
  - `rm -r` does not walk into a linked folder.
  - `cp` copies the link itself (`NOFOLLOW_LINKS`, `COPY_ATTRIBUTES`).
- **`mv`** uses `Files.move`, trying `ATOMIC_MOVE` first.
  - Across file systems, it falls back to a recursive copy and then a removal of the source.
  - That fallback runs only after the whole copy has succeeded, so a failed copy never loses the source.
- **`rm -r`** removes depth-first with `Files.walkFileTree` and checks Ctrl+C between entries. An entry that cannot be removed gives an error, and the walk goes on.
- **`touch`** creates an empty file, or sets the modification time of an existing one to now. A missing parent is an error.
- **Preview:** none of the five cmdlets runs in preview mode (`preview = false`), including with `-n`, because the preview must never touch the disk even by mistake.

### Protected paths
- **Rule:** `rm` and `mv` (for its sources) refuse a path **if it is a protected path or contains one**. Removing `C:\Users` would remove the home folder, so it is refused too. Paths are compared after `toAbsolutePath().normalize()`, ignoring case on Windows. A protected path is reported as a non-blocking error: `rm: protected path: C:\Windows (remove it anyway with --allow-protected)`.
- **Current folder:** removing the current folder or one of its parents is always refused. That rule does not depend on the file, and `--allow-protected` lifts it too.
- **List:** the file `<config folder>/protected-paths`, `~/.powerj/protected-paths` by default (or under `$POWERJ_HOME`).
  - One path per line. `~` stands for the home folder. Empty lines and lines starting with `#` are ignored.
  - The shell **creates it at first launch** with defaults for the platform, so the user can see and edit it:
    - **all platforms:** every file-system root (`/`, or `C:\`, `D:\`… as found at creation), `~`, the configuration folder itself;
    - **Windows:** `C:\Windows`, `C:\Program Files`, `C:\Program Files (x86)`, `C:\ProgramData`, `C:\Users`;
    - **Unix:** `/bin`, `/boot`, `/dev`, `/etc`, `/home`, `/lib`, `/opt`, `/proc`, `/root`, `/sbin`, `/sys`, `/usr`, `/var`;
    - **macOS:** also `/Applications`, `/Library`, `/System`, `/Users`.
  - If the file is missing or unreadable when `rm` runs (deleted, or a non-interactive run without a configuration folder), the same defaults apply. A missing file never means "nothing is protected".
- The file is read at each execution, so an edit applies at once.

## Implementation
**API and core** (small, compatible):
- `powerj-api/.../CmdletContext.java`: add `default Optional<Path> configDirectory() { return Optional.empty(); }`, the PowerJ configuration folder. It is useful to third-party modules too.
- `powerj-core/.../Session.java`: a `configDirectory` field, set by the shell (`Session.configDirectory(Path)`).
- `powerj-core/.../CmdletRunner.java`: `Context.configDirectory()` returns it.
- `powerj-shell/.../Main.java`, interactive and `-c` modes: set the session's folder from `PowerJHome`.
- `powerj-shell/.../PowerJHome.java`: `protectedPathsFile()`. `createDirectories()` writes the default file when it is missing, with the defaults from `ProtectedPaths.defaults()`.

**Cmdlets** (`powerj-cmdlets/src/main/java/io/powerj/cmdlets/`):
- `ProtectedPaths.java`, package-private:
  - `defaults(FileSystem, Path home, Path configDir)` and `load(Optional<Path> configDir, Path home)`;
  - `check(Path)` returns the protected path that would be removed, or empty;
  - the current-folder rule.
- `FileTargets.java`, package-private:
  - expands the path arguments (wildcards);
  - resolves pipeline objects, logic moved out of `Cat.Reader.file`, which then uses it;
  - splits sources and destination for `cp`/`mv`.

  `Cat` keeps its messages.
- `FileEntries.java`: `of(Path)`, moved out of `Ls`. `Ls` uses it, unchanged in behaviour.
- `FileOperation.java`: the dry-run record.
- `MakeDirectory.java` (`mkdir`), `Touch.java`, `Copy.java` (`cp`), `Move.java` (`mv`), `Remove.java` (`rm`): one class per cmdlet, `@CmdletInfo(category = "Files", preview = false)`.
- Messages in `messages_en`/`_fr`: summaries, options, and the errors above, under prefixes `mkdir.*`, `touch.*`, `cp.*`, `mv.*`, `rm.*`, `fileops.*` for the shared ones.
- `BuiltinCmdlets`: register the five cmdlets.

**Native commands:**
- `mkdir`, `cp`, `mv` and `rm` exist as native programs on Unix, and `mkdir` on Windows too. The cmdlets take precedence (FR-13). `^rm`, or `native.prefer=rm` in `config.properties` (FR-16), runs the native program.
- `help rm` mentions it.

**Docs:**
- `docs/SPECIFICATION.md`: **FR-72**, `configDirectory()` in §4.2, `protected-paths` in §8, the §12.3 rows, row 20 of §11.
- `docs/recettes/fileops.md`.
- `README.md`.
- `docs/plans/cmdlet-roadmap.md`: phases 6 and 7 cancelled.

## Tests
All tests use `@TempDir`; none touches a real system folder.
- **`ProtectedPathsTest`:**
  - defaults per platform (Unix and Windows file-system roots, with a fake `FileSystem` where useful);
  - file parsing: comments, `~`, blank lines;
  - missing file → defaults;
  - a path that contains a protected path is refused;
  - current folder and its parents refused;
  - case on Windows.
- **`MakeDirectoryTest`, `TouchTest`:**
  - create, existing, missing parent with and without `-p`;
  - mtime updated;
  - emitted `FileEntry`;
  - `-n` changes nothing.
- **`CopyTest`, `MoveTest`:**
  - file to file, files into a folder;
  - several sources with a missing destination → error;
  - folder without/with `-r`;
  - existing target without/with `-f`;
  - copy into itself refused;
  - pipeline sources, including `--property`;
  - symbolic link copied as a link (Unix);
  - `-v` output;
  - `-n` changes nothing;
  - `mv` of a protected source refused.
- **`RemoveTest`:**
  - file, wildcard, missing (error, silent with `-f`);
  - non-empty folder without/with `-r`;
  - link not followed (the target survives);
  - protected path refused, then removed with `--allow-protected` (protected paths inside `@TempDir`, through a test `protected-paths` file);
  - current folder refused;
  - `-v` emits the removed entries;
  - `-n` lists and changes nothing;
  - `ls --filter *.tmp | rm` through the pipeline.
- **`powerj-core`:** `configDirectory()` reaches the cmdlet.
- **`powerj-shell`:**
  - `PowerJHomeTest`: the `protected-paths` file is created with defaults at first launch, and kept if it already exists;
  - `BuiltinCmdletsTest`: end to end for the five cmdlets.

## Verification
- `./mvnw verify` (locally `-Djava.release=25`; CI uses JDK 27, Linux and Windows).
- Manual, in a scratch folder:
  - `mkdir -p a/b/c`
  - `touch a/x.txt`
  - `cp a/x.txt a/y.txt`
  - `cp -r a b`
  - `ls b -r`
  - `mv b/a c`
  - `ls -r --filter *.txt | rm -v`
  - `rm -r -n c`
  - `rm -r c`
  - `rm ~`, refused as protected
  - `rm -r ..`, refused (current folder)
  - `cat ~/.powerj/protected-paths`
  - `help rm`
