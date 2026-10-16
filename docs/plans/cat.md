# Plan — cmdlet `cat` (mini-iteration 13, FR-65)

## Context
`cat` is in the backlog (SPECIFICATION §12.3: "Read a file line by line → String"). The user wants it now, reading one or more files, with a configurable output shape: one String per line, one String for the whole content, a record per line (n°, length, text), or a record for the whole content (nb chars, content). Line numbers run continuously across files (file 2 starts at lines(file 1) + 1).

Decisions taken with the user:
- Full-content modes with several files → **one single concatenated object** (like Unix `cat`).
- `path` is carried by the per-line record only (not on the full-content record, which may mix several files).

Branch: `claude/adoring-gauss-fj7705` (already exists and checked out, based on `main` @ d4c7a31).

## Syntax
```text
cat [chemin...] [-l|--lines | -t|--text | -n|--numbered | -c|--content] [--encoding <charset>] [--property <nom>]
```
- `chemin` (position 0, multiple): files; wildcards on the last element (`*.log`) as in `grep`.
- Output: one of the exclusive options below (initially an enum `--mode`, replaced by these options at the user's request); none given: `--lines`:
  - `-l`/`--lines` → one `String` per line (no EOL chars)
  - `-t`/`--text` → one `String`: the raw content of all files concatenated as is
  - `-n`/`--numbered` → one `CatLine(Path path, int line, int length, String text)` per line, `@Display(columns = {"line", "length", "text"})` (path accessible: `m.path`)
  - `-c`/`--content` → one `CatContent(int length, String content)` (length = number of chars), `@Display(columns = {"length", "content"})`
- `--encoding`: default UTF-8, no fallback (same behaviour/message style as grep).
- `--property <nom>`: property of the pipeline objects that designates the file (see below).

### Pipeline input (`cat` after a `|`)
Each received object is read **as if it had been given as a parameter** of `cat` (same numbering, same concatenation; numbering continues across objects and after the `chemin` arguments):
- `String` → converted to `Path` (resolved against the current directory, no wildcard expansion): `"a.txt" | cat`, `git ls-files | cat -n`;
- `Path`, `java.io.File` → read directly;
- `FileEntry` (output of `ls`) → its `path` is read (convenience so that `ls --filter *.txt | cat` works without `--property`);
- any other object → **`--property <nom>`** names the property holding the file: evaluated with `context.compile("$_." + name)` after validation by `Where.PROPERTY` (exactly as `grep --property`, `Grep.java:212`). Its value must be a `String` (→ `Path`), a `Path` or a `File`; otherwise non-blocking error `cat: property <nom> is not a file: <objet>` and the object is skipped. Example: `grep TODO -r -s | cat --property path -n`.
- When `--property` is given it applies to every received object (consistent with `grep`).
- Other object without `--property` → non-blocking error suggesting `--property`, object skipped.

## Behaviour
- Line modes stream: emit as each line is read; counter kept per execution.
- Full-content modes accumulate in a `StringBuilder` and emit once in `end()` (so pipeline inputs are concatenated too). Nothing emitted if no file was read.
- Errors non-blocking, processing continues: not found, is a directory, access denied, decode failure (suggests `--encoding`), no file matches wildcard. Unknown charset → blocking `IllegalArgumentException`.
- No path and no pipeline input → blocking error "file expected".
- Cancellation: check `Thread.interrupted()` every 4096 lines as in grep.

## Implementation
New files in `powerj-cmdlets/src/main/java/io/powerj/cmdlets/`:
- `Cat.java` — `@CmdletInfo(name = "cat", category = "Files", ...)`, `Cmdlet<Cat.Params, Object, Object>`, nested `record Params(List<String> paths, Mode mode, String encoding, String property)` and `enum Mode { LINES, TEXT, NUMBERED, CONTENT }`. Per-execution state in `Collections.synchronizedMap(new IdentityHashMap<>())` keyed by params (same pattern as `Grep.searches`), holding the line counter, the charset and the content buffer. Reads with `BufferedReader` over `charset.newDecoder()` (strict decoding like grep); for `text`/`content`, read chars and append raw (EOLs preserved).
- `CatLine.java`, `CatContent.java` — records with `Objects.requireNonNull` in compact constructors (like `GrepMatch`/`EnvVar`).

Reuse / small refactor:
- Extract wildcard expansion `Grep.roots(...)` (`Grep.java:144`) into a package-private helper (e.g. `FileArguments.expand(argument, includeHidden, context, errorPrefix)`) used by both `grep` and `cat`; extract `Grep.Search.charset` the same way (`FileArguments.charset(encoding, unknownKey)`). Grep behaviour unchanged (GrepTest must stay green).
- `Grep.file(Object, CmdletContext)` (`Grep.java:134`) → move to the same helper for pipeline file objects.

Registration & i18n:
- `BuiltinCmdlets.java`: add `new Cat()` to the list.
- `messages_en.properties` / `messages_fr.properties`: `cat.summary`, `cat.option.paths|mode|encoding`, `cat.not.found`, `cat.no.match`, `cat.is.directory`, `cat.access.denied`, `cat.read.failed`, `cat.decode.failed`, `cat.encoding.unknown`, `cat.not.a.file` (suggests --property), `cat.property.not.a.file`, `cat.file.expected`; reuse `where.property.invalid` and `pipeline.object.skipped`.

Docs:
- `docs/SPECIFICATION.md`: new section **FR-65 — `cat`** after FR-64 (syntax, option table, records, CA); update §12.3 backlog row for `cat`; add row 13 to the mini-iteration table; mention `cat` in the cmdlet scope sentence (line ~373).
- `docs/recettes/cat.md`: acceptance checklist in the grep.md format.
- `README.md`: add `cat` to the `powerj-cmdlets` row and a "Mini-iteration 13" sentence.

## Tests — `powerj-cmdlets/src/test/java/io/powerj/cmdlets/CatTest.java`
Using `TestContext` + `@TempDir` (like `GrepTest`):
- lines mode, single file; files without trailing newline; empty file
- two files, `numbered`: second file starts at lines(first)+1, `path` correct, `length` = text length
- `text` and `content` with two files: one object, exact concatenation, `length` = char count
- pipeline input via `process` (`hasInput = true`): `String`, `Path`, `File`, `FileEntry` read; numbering continued after the arguments; content emitted in `end`
- `--property path` on a record (e.g. `GrepMatch`) and on a record whose property is a `String`; property that is not a file → error, object skipped; other object without `--property` → error (use `TestContext.compiler` to simulate compilation, as GrepTest does)
- wildcard `*.txt`, missing file / directory → error in `context.errors`, others still read
- Latin-1 file in UTF-8 → decode error; OK with `--encoding ISO-8859-1`; unknown charset → exception
- no path and no input → exception

## Verification
- `./mvnw -pl powerj-cmdlets -am test` (CatTest + GrepTest unchanged), then `./mvnw verify` for the full build.
- Manual: `./mvnw -pl powerj-dist -am package` (or the shell module) and run `powerj -c "cat a.txt b.txt -n"`, `powerj -c "cat *.txt -c"`, `powerj -c "ls --filter *.txt | cat"`, `powerj -c "grep TODO -r -s | cat --property path -n"`, `help cat`.
- Commit on `claude/adoring-gauss-fj7705` and `git push -u origin claude/adoring-gauss-fj7705` (no PR unless asked).
