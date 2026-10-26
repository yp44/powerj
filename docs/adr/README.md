# Architecture decision records

One short file per decision that shapes the code and that someone could be tempted to undo. Each says what was true
when the decision was taken (*Context*), what was decided (*Decision*) and what follows from it, good and bad
(*Consequences*). A decision that changes gets a new record that supersedes the old one; the old one stays.

The records were written in Phase 5 of the [architecture review](../REVUE-ARCHITECTURE.md) from decisions taken during the
earlier phases and from the "Decisions taken with the user" of [docs/plans/](../plans/); each names where to find the
evidence.

| # | Decision | Status |
|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | accepted |
| [0002](0002-modules-and-a-layered-core.md) | JPMS modules, and a core made of layered packages | accepted |
| [0003](0003-built-in-cmdlets-are-independent-of-the-engine.md) | Built-in cmdlets are independent of the engine | accepted |
| [0004](0004-the-state-of-a-run-lives-in-the-context.md) | The state of one run of a cmdlet lives in the context | accepted |
| [0005](0005-guard-rails-not-a-sandbox.md) | Guard-rails against accidents, not a sandbox | accepted |
| [0006](0006-consent-to-native-access-is-tied-to-the-content.md) | The consent to native access is tied to the content of the modules | accepted |
| [0007](0007-a-composition-root-without-a-framework.md) | A composition root, without a dependency-injection framework | accepted |
| [0008](0008-english-repository-bilingual-interface.md) | An English repository, a bilingual interface | accepted |

To add one: copy the shape of the last, number it, add a row here.
