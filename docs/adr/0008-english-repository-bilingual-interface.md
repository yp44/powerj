# 0008. An English repository, a bilingual interface

**Status:** accepted (Phases 1.8 and 5.1)

## Context
Code and documentation were mostly in English, with French in places that mattered: the name of a thread that a profiler
parsed, comments and messages of the CI, the project description, folder names of the acceptance sheets (finding C-36).
The product itself is used in English and French.

## Decision
- The **repository** is in English: code, comments, Javadoc, commits, pull requests, CI, build files, documentation, names
  of threads and of log messages. The one exception is the architecture review, which stays in the language it was
  commissioned in.
- The **interface** is English and French through resource bundles with the same keys (`MessageBundlesTest`), the log is always
  in English, and tests force English. No French text in the sources (`NoHardcodedFrenchTest`).
- French strings used as test data are allowed.

## Consequences
One language to read for a contributor; the translation work is confined to bundles. The rule is written in
[CONTRIBUTING.md](../../CONTRIBUTING.md) and checked by tests for the part that can be.
