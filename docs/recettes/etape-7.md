# Acceptance test — Step 7: third-party modules

**Goal:** verify that a third-party module (a jar) adds its cmdlets to PowerJ, at startup or on the fly, without exposing its classes to Java, and that name conflicts are reported.

> Messages are shown in the system language; set `language=en` in config.properties (or `POWERJ_LANG=en`) to get the English texts quoted here.

## Getting the deliverables

As in the previous steps: **Actions** tab, latest run of the **CI** workflow, artifact `powerj-windows-x64-installer` or `powerj-windows-x64-portable`.

In addition: the **`greet-module`** artifact contains `greet.jar`, the example module (source: `examples/greet`, §4.3 of the specification).

## Where to put a module

- Folder: `%USERPROFILE%\.powerj\modules\` (or `$POWERJ_HOME\modules\` if the `POWERJ_HOME` variable is set). Create it if it does not exist.
- A standalone jar is dropped directly into it. A module that has dependencies goes into a **subfolder** together with its dependencies (`modules\docker\docker.jar`, `modules\docker\lib1.jar`…).
- Modules are loaded at startup; `mod-load chemin\vers\module.jar` loads one without restarting.

## Scenario

| # | Action | Expected result |
|---|---|---|
| 1 | Copy `greet.jar` into `%USERPROFILE%\.powerj\modules\`, start PowerJ | Normal startup, no warning. |
| 2 | `greet --name Yves -c 2` | Table of two `name message at` objects: `Yves  Hello Yves!  <date>`. |
| 3 | `gr` then Tab; `greet --` then Tab Tab | `greet`; then `--name`, `--count` with their descriptions. |
| 4 | `greet -n Yves \| where { g -> g.message.contains("Yves") }` | One object; `greet` is highlighted in green (cmdlet). |
| 5 | `greet -n Yves -c 3 \| map { g -> g.message }` | Three lines `Hello Yves!`. |
| 6 | `help greet` | Generated help: summary `Greets someone`, `Options:` with `-n, --name` `(required)` and `-c, --count`, `Output: Greeting (name, message, at)`, `Module: com.example.greet`, `Examples:`. |
| 7 | `mod-list` | Table `name version cmdlets source`: `io.powerj.cmdlets … [ls, where, map, collect, env] (built-in)` and `com.example.greet 0.1.0-SNAPSHOT [greet] C:\Users\…\greet.jar`. |
| 8 | `which greet` | `greet → cmdlet (com.example.greet)`. |
| 9 | `new com.example.greet.Greeting("a", "b", null)` | Error `class not found: com.example.greet.Greeting`: a module's classes are not exposed to Java. |
| 10 | `greet` | Error `greet: missing required option: --name`. |
| 11 | Remove `greet.jar` from the folder, restart; `mod-load C:\chemin\vers\greet.jar` | Displays `greet`; the command `greet -n A` works immediately. |
| 12 | `mod-load C:\chemin\vers\greet.jar` a second time | `greet.jar: module com.example.greet already loaded`, then `mod-load: greet.jar not loaded`. |
| 13 | Copy `greet.jar` **twice** into the folder (`greet.jar` and `greet-copie.jar`), restart | Below the banner: `greet-copie.jar: module com.example.greet already loaded`; `greet` works. |
| 14 | `mod-load C:\Windows\notepad.exe` | `notepad.exe: a module is a .jar file (or a directory of jars)`. |
| 15 | `greet:greet -n Q` | Works: qualified name `module:nom`, useful when two modules declare the same cmdlet (FR-17). |

## Writing your own module

The repository's `examples/greet` folder is a complete template: a `pom.xml` that depends only on `powerj-api`, a `module-info.java` that declares `provides io.powerj.api.CmdletProvider with …`, an options record annotated with `@Option`, an output record, and the cmdlet class annotated with `@CmdletInfo` (texts in English), plus `messages_en.properties` / `messages_fr.properties` returned by `GreetProvider.messages(Locale)` to translate them (§4.2 of the specification, FR-61). `mvnw -pl examples/greet -am package` produces `examples/greet/target/greet.jar`.

## Known limitations of step 7

- No unloading or reloading of a module: for a new version, quit PowerJ, replace the jar and restart (on Windows, a loaded jar is locked while PowerJ is running).
- Name collision: the first module loaded (alphabetical order of the files at startup) keeps the short name; the other cmdlet is only accessible via `module:nom`, which is not suggested by Tab.
- `powerj-api` is a separate Maven artifact, but not yet published to a public repository: to compile a module outside this repository, install it locally with `mvnw -pl powerj-api -am install`.
