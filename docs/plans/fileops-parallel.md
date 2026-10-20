# Plan — parallel `cp -r` and `rm -r` (mini-iteration 21, FR-72b)

## Context
Follow-up of phase 8 (`docs/plans/fileops.md`, FR-72), asked by the user after a review of the file operations. `grep` and `ls` already walk a tree in virtual threads with `-p/--parallel <n>` (FR-64). `cp -r` and `rm -r` are still sequential: one file copied or removed at a time.

Decisions taken with the user:
1. `rm -r` removes while walking the tree, without listing it first in memory. This was done in PR #32 (commit `909c39f`).
2. `cp -r` and `rm -r` get an optional `-p/--parallel <n>`, built on virtual threads like `grep`. It is off by default (`1`).
3. `mv`, `mkdir` and `touch` stay as they are. A `mv` on the same file system is a single rename, and the other two do trivial work.
4. Answers to the open questions:
   - `-p` acts only inside a tree, not on the paths received from the pipeline;
   - `mv` gets no `-p`, not even for its copy-then-remove fallback;
   - `--dry-run` **reflects the parallelism**: with `-p`, `rm -r -n` walks the tree in parallel and lists the entries in the same free order as a real removal (a folder still comes after its content).

When it helps:
- on SSDs (NVMe) and network drives (SMB, NFS), which handle several requests at once. With a network drive, the latency of each request then overlaps with the others;
- on trees with many small files.

On a spinning disk, several copies at once make the head jump between files: `-p` can then be **slower**. This is why it is off by default.

Branch: `claude/cmdlet-fileops-parallel`, from `main` @ 1f85e35.

## Syntax
```text
cp <source>... <destination> [-r|--recurse] [-p|--parallel <n>] [-f|--force] [-v|--verbose] [-n|--dry-run]
rm <path>... [-r|--recurse] [-p|--parallel <n>] [-f|--force] [-v|--verbose] [-n|--dry-run] [--allow-protected]
```

| Option | Type | Description |
|---|---|---|
| `-p`, `--parallel` | `int` ≥ 1 | Copies or removes up to `n` entries of a tree at the same time, in virtual threads. `1` (default): sequential, as today. |

- `-p` acts **inside a tree** (a folder given with `-r`). It has no effect on a single file.
- The paths received from the pipeline are still processed one after the other (see open question 1).
- `-p 0` or a negative value is a blocking error, with the same message as `grep` and `ls`: `--parallel expects a number of threads of at least 1, got 0`.

## Output
- Same objects as today:
  - `rm -v` emits the `FileEntry` of each removed entry, and `rm -n` one `FileOperation` per entry;
  - `cp -v` emits the `FileEntry` of each destination (one per source, not per copied entry), and `cp -n` one `FileOperation` per source. Their order does not change.
- With `-p` > 1, the entries of `rm -v` and `rm -n` come in a **free order**, as for `grep -p`, but a folder always comes after its content.
- Errors are non-blocking and also arrive in a free order. The same errors are reported as in sequential mode.

## Behaviour
Order rules, which make the result identical to the sequential mode:
- **`rm -r`**, post-order:
  - a folder is removed only once all its children have been handled;
  - the files of a folder are removed at the same time, and the sub-folders are walked at the same time;
- the listing of a folder takes a permit before it submits each file, so the number of tasks waiting stays bounded, as in `FileWalker`;
  - each folder keeps a counter of its children still in progress. The last child to finish removes the folder, then decrements the counter of the parent;
  - if a child could not be removed, the removal of the folder is still tried. It then fails with `not empty`, as in sequential mode.
- **`cp -r`**, pre-order:
  - a folder is created (`Files.copy` with `COPY_ATTRIBUTES`) **before** its content is submitted;
  - files and sub-folders are then copied at the same time;
  - a folder that cannot be created skips its content (`SKIP_SUBTREE`), as today.

What does not change:
- **Links** are never followed. A link is removed or copied as a link, and a link to a folder is not entered. The parallel walk therefore reads attributes with `NOFOLLOW_LINKS`, whereas `FileWalker` follows links, which is one reason it is not reused (see Implementation).
- **Hidden files** are always included: removing or copying a folder means all its content.
- **Protected paths**: the checks (not found, protected, folder without `-r`, same file, into itself, existing target without `-f`) are made on the root, in the cmdlet's thread, before the tree is touched.
- **Bounded concurrency**: at most `n` directory listings, copies or removals run at once (a semaphore, as in `FileWalker`). The results wait in a bounded queue (256 entries), so a slow consumer slows down the work instead of filling the memory.
- **Thread of `emit`/`error`**: they are only called in the cmdlet's thread. The workers hand results and errors over through the queue, as in `FileWalker` (FR-64).
- **Ctrl+C**: the walk stops all its threads and throws `CancellationException`. Some entries may already have been copied or removed, exactly as in sequential mode.
- **`mv` across file systems** keeps its sequential copy-then-remove fallback (open question 2).
- **`--dry-run`**: `rm -r -n -p <n>` runs the same parallel walk, without removing anything (decision 4).
- **Preview**: no change, these cmdlets never run in preview (FR-66).

## Implementation
**`powerj-fs`**: a new public class `io.powerj.fs.FileTrees`, so that a third-party module can use it like `FileWalker`.

```java
public final class FileTrees {
    /** Removes a tree, content first (nothing with dryRun); links are not followed. */
    public static boolean delete(Path root, int parallelism, boolean dryRun,
                                 Consumer<? super Removed> removed, BiConsumer<? super Path, ? super IOException> errors);

    /** Copies a file, a link or a tree to target, folders first; links are copied as links. */
    public static boolean copy(Path source, Path target, int parallelism, boolean replace,
                               BiConsumer<? super Path, ? super IOException> errors);

    public record Removed(Path path, BasicFileAttributes attributes) { }
}
```

- Both return `true` when no error was reported.
- The consumers are called in the calling thread only, as with `FileWalker`.
- Errors carry the `IOException` itself, not a `WalkError`. This way `FileOps.report` keeps its exact messages (`exists`, `not empty`…), which a `WalkError` would reduce to three kinds.
- `Removed.attributes` come from the walk (the listing, or `preVisitDirectory` in sequential mode), so `-v` costs no extra read.
- `replace` adds `REPLACE_EXISTING`, and an existing folder is merged (behaviour of `cp -f`).
- With `parallelism == 1`, both methods use the sequential walk (`Files.walkFileTree`), moved here from `FileOps`.

Engine:
- the engine mirrors `FileWalker.ParallelWalk`: a virtual-thread executor, a `Semaphore(parallelism)`, a counter of pending tasks that signals the end, a bounded `ArrayBlockingQueue` read by the calling thread, and a `stopped` flag with `shutdownNow()` on error or Ctrl+C;
- `rm` adds a `DirectoryTask` per folder with an `AtomicInteger` of children in progress, and a reference to its parent task;
- memory is bounded by the folders in progress, not by the size of the tree;
- `FileWalker` is **not changed**: its parallel mode does not order a folder against its content, and it follows links. Sharing the engine between the two is left for later, to avoid any regression in `grep`/`ls`.

Parallel errors follow the sequential walk:
- a folder that cannot be listed is reported and kept; its parent then fails with `not empty`, and for a copy it is not created;
- an error while listing keeps the entries already read.

**`powerj-cmdlets`**:
- `FileOps.deleteTree` and `FileOps.copyTree` take a `parallelism` argument and delegate to `FileTrees`. The sequential code leaves `FileOps`, and the messages stay the same.
- `FileOps.parallelism(Integer)` validates the value (`fileops.parallel.invalid`), with `null` meaning `1`.
- `Copy.Params` and `Remove.Params` gain `@Option(shortName = 'p') Integer parallel`, after `recurse`. `Transfer.Options` gains `parallelism`, while `mv` passes `1`.
- i18n: `cp.option.parallel`, `rm.option.parallel` and `fileops.parallel.invalid`, in English and French.

**Docs**:
- FR-72 gets a paragraph and its syntax lines, §11 gets row 21, and `docs/plans/cmdlet-roadmap.md` gets a note;
- `docs/recettes/fileops.md` gets rows for `-p` and a timing on a large tree;
- the README cmdlet table mentions `-p` for `cp`/`rm`.

## Tests
`powerj-fs`, `FileTreesTest`, each case for `parallelism` 1 and 8:
- `delete` removes a tree of several levels (folders, files, empty folders) completely, and `removed` receives every entry once;
- in `removed`, each folder comes after all its content;
- a link to an outside folder is removed and its target is untouched; a broken link is removed;
- hidden files are removed;
- `copy` gives an identical tree (content, modification times); a link is copied as a link;
- `copy` without `replace` onto an existing file gives one error per file and copies the others; with `replace`, it overwrites and merges folders;
- an entry that cannot be removed (read-only folder, Unix only and not as root, `assumeFalse`) gives an error and the walk goes on;
- Ctrl+C: interrupting the calling thread on a large tree throws `CancellationException`, and every worker has finished when `delete` returns;
- an exception thrown by a consumer stops the work and is thrown back to the caller.

`powerj-cmdlets`, `FileOpsTest`:
- `rm t -r -p 4` and `cp a b -r -p 4` give the same result as without `-p`;
- `rm -r -p 4 -v` emits all the removed entries (compared as a set);
- `-p 0` gives the blocking error;
- `-p` on a single file works;
- `rm -r -n -p 8` lists the same entries as without `-p` (as a set), with the folder last, and removes nothing.

## Verification
- `./mvnw verify`
- Manually, with the 4-level test tree (8,421 folders, about 421,000 files):
```text
Stream.iterate(List.of("t"), { l -> l.stream().flatMap({ p -> IntStream.range(0, 20).mapToObj({ i -> p + "/d" + i }) }).toList() }).limit(4).flatMap({ l -> l.stream() }) | mkdir -p | count
ls t -r --dirs | map { d -> IntStream.range(0, 50).mapToObj({ i -> d.path.resolve("f" + i + ".txt") }) } | touch | count
$s = System.nanoTime(); cp t t2 -r -p 16; (System.nanoTime() - $s) / 1000000
$s = System.nanoTime(); cp t t3 -r; (System.nanoTime() - $s) / 1000000
$s = System.nanoTime(); rm t2 -r -p 16; (System.nanoTime() - $s) / 1000000
$s = System.nanoTime(); rm t3 -r; (System.nanoTime() - $s) / 1000000
```
The measured times go into the PR description. They depend on the disk, so no gain is promised in the docs.

## Measures
Container with 4 vCPUs, virtual disk, the 4-level tree of the verification (8,421 folders, 421,000 empty files, 429,421 entries), in milliseconds:

| Command | `-p 1` | `-p 4` | `-p 16` | `-p 64` | native |
|---|---|---|---|---|---|
| `cp t tx -r` | 16,948 | 10,080 | 11,356 | 12,036 | `cp -a`: 25,944 |
| `rm tx -r` | 9,446 | 5,902 | 5,296 | 4,447 | `rm -rf`: 5,324 |

The best value depends on the disk and the processor; on this machine `cp` peaks around `-p 4`.

## Open questions
All answered (decision 4).
