# PowerJ

An interactive object-oriented shell written in Java 27: commands return **Java objects** (preferably records) whose attributes you extract in a pipeline, with short Unix-style command names, transparent mixing with native commands, and **direct access to the entire Java API of the JRE**.

```text
PJ C:\dev> ls -r --filter *.java | where { $_.size > 10kb }
PJ C:\dev> git status --porcelain | where { $_.startsWith(" M ") }
PJ C:\dev> java.util.List.of("apple", "banana", "orange") | where { $_.contains("b") }
PJ C:\dev> LocalDate.now().plusDays(10).dayOfWeek
```

```text
C:\> powerj -c "ls -r | where size > 1mb"
C:\> dir /b | powerj -c "where { $_.endsWith(\".txt\") }"
```

- Specification: [docs/SPECIFICATION.md](docs/SPECIFICATION.md)
- Acceptance checklists: [docs/recettes/](docs/recettes/)

## Status

Mini-iteration 9: internationalization — messages, help, completion and errors in English or French (`POWERJ_LANG` / `language` key, see [Language](#language)); third-party modules translate their cmdlets with `CmdletProvider.messages(Locale)` ([docs/recettes/i18n.md](docs/recettes/i18n.md)). Mini-iteration 8: `collect` cmdlet — gathers the objects of a pipeline into a single list (`(ls -r | collect).size()`, `ls -r | collect | map { l -> l.stream()… }`). Step 7: third-party modules — a jar dropped into `~/.powerj/modules/` adds its cmdlets at startup (an isolated `ModuleLayer` per module), hot loading with `mod-load`, `mod-list`, name collisions (`module:nom`); example module [`examples/greet`](examples/greet) (CI artifact `greet-module`). Step 6: Tab completion (commands `[pj]`/`[internal]`/`[native]`, options, paths, variables, properties of `$_` and of lambda parameters, Java API with signatures) and input highlighting. Steps 5 and 5b: direct access to the JRE's Java API — static calls (`Math.max(3, 7)`, `java.util.List.of(…)`), fields (`Math.PI`), `new`, instance methods (`$l.stream().toList()`), default imports and `import`, overloads, varargs and conversions, casts `[long] 5`, Java-style lambdas (`ls -r | where { f -> f.size > 1mb }`, `$l.sort((a, b) -> a.length() - b.length())`), method references (`map FileEntry::name`, `String::length`), the `*.` operator for each element of a list (`$f*.name`), the `map` cmdlet, `"""` text blocks, readable Java exceptions (`$errors`, `$debug`), `help java.util.List`, `$( … )` in strings, a second Ctrl+C to abort a stuck computation. Earlier steps: pipeline `|` and `where`, non-interactive mode; `ls` and `env` cmdlets, objects and properties; native commands, `cd`, `;` `&&` `||`, variables, redirections; line editing and history. See the development plan (§11 of the specification).

## Language

PowerJ speaks **English** or **French**. By default it follows the display language of the system (French → French, anything else → English). To choose:

```text
set POWERJ_LANG=en                      # Windows (cmd.exe), before launching powerj
POWERJ_LANG=fr powerj                   # Linux / macOS
```

or, permanently, the line `language=en` (or `language=fr`) in `~/.powerj/config.properties` (`%USERPROFILE%\.powerj\config.properties` on Windows, or `$POWERJ_HOME/config.properties`). Precedence: system property `-Dpowerj.language`, then `POWERJ_LANG`, then `config.properties`, then the system. Commands, options and keywords are the same in both languages; the diagnostic log is always in English. Details: FR-61 in the specification.

## Building

Prerequisites: **JDK 27** (Maven is provided by the `mvnw` wrapper, version 3.9.11).

```bash
./mvnw verify                 # compilation + tests
./mvnw -Pdist verify          # + standalone application image (jlink runtime + jpackage launcher)
```

The image is produced in `powerj-dist/target/jpackage/powerj/` (launcher `bin/powerj` on Linux, `powerj.exe` on Windows), with a zip archive in `powerj-dist/target/`.

On Windows, the `.exe` installer is built with `mvnw.cmd -Pdist,installer verify` and requires [WiX](https://wixtoolset.org/) (`dotnet tool install --global wix --version 5.0.2`). The GitHub Actions CI produces it on every push.

Without JDK 27 at hand, you can check the build with an older JDK that supports the features used: `./mvnw -Djava.release=25 verify`.

## Structure

| Module | Role |
|---|---|
| `powerj-api` | Public API for writing cmdlets |
| `powerj-core` | Parsing, interpretation, pipeline, Java interoperability |
| `powerj-cmdlets` | Built-in cmdlets (`ls`, `where`, `map`, `collect`, `env`) |
| `powerj-shell` | REPL and entry point |
| `powerj-dist` | Distribution: jlink + jpackage |
| `examples/greet` | Example third-party module (`greet`), not shipped: a template for writing your own cmdlets |
