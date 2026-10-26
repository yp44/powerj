# 0006. The consent to native access is tied to the content of the modules

**Status:** accepted (Phase 5.3)

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
