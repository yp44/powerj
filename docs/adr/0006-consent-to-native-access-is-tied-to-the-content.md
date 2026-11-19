# 0006. The consent to native access is tied to the content of the modules

**Status:** accepted (Phase 5.3), amended by #170 (the consent is a gate)

## Context
A third-party module that declares `Enable-Native-Access: true` runs native code with the rights of the shell, so PowerJ
asks the user before loading it and keeps the answer in `config.properties`. The answer was keyed by the **name** of the
module (`modules.nativeAccess.<module>=true`): replace the jar with another one that has the same module name, and the
consent given to the first applied to the second. The loader also enables native access for *every* module of the layer
after the consent given for the one that declares it (finding C-24).

## Decision
- The loader computes a **fingerprint** of what it is about to load: the SHA-256 of the modules of the layer, each given by its
  name and the SHA-256 of its jar (or, for a folder, of its file names and contents). It hands it to the policy with the
  question.
- A **yes** is recorded as `modules.nativeAccess.<module>=sha256:<fingerprint>` and holds only for that fingerprint. A
  **no** is `false` and holds whatever the content.
- A recorded value that does not hold (an older `true`, which was not tied to anything, or a digest of another version) is
  not an answer: the user is asked again, and told why. Without a terminal the module is not loaded, and the message
  gives the exact line to record.

## Alternatives not taken
- Hash the root jar only: a dependency of the layer (`javafx.graphics` is where the native libraries are loaded) could change
  unnoticed, and it is the whole layer that gets the access.
- Verify a signature: there is no signing authority for PowerJ modules yet. The fingerprint answers "is this what I allowed"
  without one; it does not answer "who made it".
- Keep `true` valid: that is the hole. No release carried the old format, so nothing is lost.

## Consequences
An update of a module asks once more, which is the point. The consent is no longer something to write by hand (the digest
is long): the message of a refused load gives the line, and the question records it. The fingerprint is computed only for a
module that asks for native access, at load time (about the cost of reading its jars once).

## Amendment: the consent is a gate (audit v2, B-01, #170)
The consent was asked only of the modules that declare `Enable-Native-Access`, and the launcher did not say what happens to
the others. With the default of JDK 25 (`--illegal-native-access=warn`) a module that simply left the attribute out was
loaded without a question and could still call `System.loadLibrary` or the FFM API: the JVM printed a warning and nothing
stopped it, while the Javadoc and the README read like a gate. Two options were put to the maintainer: make it a gate, or
call it a courtesy that silences a warning.

**Decided: it is a gate.** The packaged launcher passes `--illegal-native-access=deny` (`powerj-dist/pom.xml`), next to
the `--enable-native-access` list of the modules of the boot layer. A module of a layer defined at run time has native
access only through `ModuleLayer.Controller.enableNativeAccess`, which `ModuleLoader` calls after the consent and for no
other reason; any other call of a restricted method ends in an `IllegalCallerException`
(`Illegal native access from module …`). `ci/smoke/native-access.sh` keeps both roads checked on the packaged image: a jar
without the attribute is refused, a jar with it is refused until `config.properties` holds the line the refusal prints,
and then it works.

**Consequence.** A library that uses native access without declaring it in its manifest breaks until its author adds the
attribute (JavaFX, in `powerj-fx`, declares it). The gate holds for the **packaged launcher**; a developer who starts the
JVM by hand (`java -p … -m io.powerj.shell`) is in the default `warn` mode unless they pass the option. The consent still
says nothing about who made a module: see `SECURITY.md`, "Third-party modules: full trust".
